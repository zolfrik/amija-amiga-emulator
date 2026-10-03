/*
 * AmiJa - a cycle-exact Amiga 500 emulator in Java.
 * Copyright (C) 2026 zolfrik
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package io.github.zolfrik.amija.common;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Reads the PHYSICAL state of the keys on Windows through {@code user32!GetAsyncKeyState},
 * via the Foreign Function API (no external dependency, JDK 22+ / stable on this runtime).
 *
 * <p>It works around a limit of Windows keyboard input as AWT exposes it: when <em>both</em>
 * Shifts are held and one is released, the OS does <b>not</b> emit the {@code keyReleased} of
 * the first one released (only of the last). The intermediate key-up cannot be recovered on
 * the AWT side. Reading the physical state of the two shifts lets them be driven
 * independently and correctly (the same technique GLFW uses internally).
 *
 * <p>On non-Windows platforms, or if linking the symbol fails, {@link #isAvailable()} returns
 * {@code false} and {@link #isDown(int)} always returns {@code false}: the caller must provide
 * a fallback (AWT handling).
 */
public final class WindowsKeyState {

    /** Virtual-Key code of the left Shift (WinUser.h). */
    public static final int VK_LSHIFT = 0xA0;
    /** Virtual-Key code of the right Shift (WinUser.h). */
    public static final int VK_RSHIFT = 0xA1;
    /** Virtual-Key code of the left Control (WinUser.h). */
    public static final int VK_LCONTROL = 0xA2;
    /** Virtual-Key code of the right Control (WinUser.h). */
    public static final int VK_RCONTROL = 0xA3;
    /** Virtual-Key code dell'Alt sinistro (WinUser.h). */
    public static final int VK_LMENU = 0xA4;
    /** Virtual-Key code dell'Alt destro / AltGr (WinUser.h). */
    public static final int VK_RMENU = 0xA5;
    /** Virtual-Key code of the left Windows key (WinUser.h). */
    public static final int VK_LWIN = 0x5B;
    /** Virtual-Key code of the right Windows key (WinUser.h). */
    public static final int VK_RWIN = 0x5C;

    /** Handle to GetAsyncKeyState, or {@code null} if unavailable. */
    private static final MethodHandle GET_ASYNC_KEY_STATE =
            link("GetAsyncKeyState",
                 FunctionDescriptor.of(ValueLayout.JAVA_SHORT, ValueLayout.JAVA_INT));

    /** Handle to GetKeyboardLayout, or {@code null} if unavailable. */
    private static final MethodHandle GET_KEYBOARD_LAYOUT =
            link("GetKeyboardLayout",
                 FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

    /** Handle to VkKeyScanExW, or {@code null} if unavailable. */
    private static final MethodHandle VK_KEY_SCAN_EX =
            link("VkKeyScanExW",
                 FunctionDescriptor.of(ValueLayout.JAVA_SHORT, ValueLayout.JAVA_SHORT,
                                       ValueLayout.ADDRESS));

    /** HKL for which {@link #cachedHasAltGr} was computed (0 = none). */
    private static long cachedHkl;
    /** Outcome of the scan of layout {@link #cachedHkl}. */
    private static boolean cachedHasAltGr;

    private WindowsKeyState() {}

    private static MethodHandle link(String symbol, FunctionDescriptor descriptor) {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (!os.contains("win")) {
            return null;
        }
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup user32 = SymbolLookup.libraryLookup("user32", Arena.global());
            return linker.downcallHandle(user32.find(symbol).orElseThrow(), descriptor);
        } catch (Throwable t) {
            // No user32, native access denied, etc.: silent fallback.
            return null;
        }
    }

    /** @return true if the physical key state read is usable. */
    public static boolean isAvailable() {
        return GET_ASYNC_KEY_STATE != null;
    }

    /**
     * @param vkey virtual-key code (e.g. {@link #VK_LSHIFT})
     * @return true if the key is physically held NOW; always false if {@link #isAvailable()}
     *         is false.
     */
    public static boolean isDown(int vkey) {
        if (GET_ASYNC_KEY_STATE == null) {
            return false;
        }
        try {
            short s = (short) GET_ASYNC_KEY_STATE.invoke(vkey);
            // Most significant bit (0x8000) = key currently held.
            return (s & 0x8000) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * @return true if the active keyboard layout has an AltGr key, i.e. if at least one
     *         character is obtained with the Ctrl+Alt shift state (Italian, German, French
     *         layouts...; not the US layout, where the right Alt is a normal Alt).
     *
     * <p>Used to recognise the <em>synthetic</em> Control Windows generates together with the
     * right Alt on layouts with AltGr. The outcome is cached per HKL: the real scan happens
     * only at the first use and at every layout change.
     *
     * <p>If the probe cannot be linked {@code true} is assumed on Windows: without this
     * information it is still better to neutralise the synthetic Ctrl, because on a layout
     * without AltGr the only side effect is losing a Ctrl pressed <em>while</em> the right Alt
     * is already down.
     */
    public static boolean activeLayoutHasAltGr() {
        if (GET_KEYBOARD_LAYOUT == null || VK_KEY_SCAN_EX == null) {
            return isAvailable();
        }
        try {
            // idThread = 0 -> layout of the calling thread (normally the system one).
            MemorySegment hkl = (MemorySegment) GET_KEYBOARD_LAYOUT.invoke(0);
            long id = hkl.address();
            if (id != cachedHkl) {
                cachedHasAltGr = probeAltGr(hkl);
                cachedHkl = id;
            }
            return cachedHasAltGr;
        } catch (Throwable t) {
            return isAvailable();
        }
    }

    /** Looks in the layout for a character that needs Ctrl+Alt (= AltGr). */
    private static boolean probeAltGr(MemorySegment hkl) throws Throwable {
        for (int ch = 0x20; ch <= 0xFF; ch++) {
            if (needsAltGr(ch, hkl)) return true;
        }
        return needsAltGr(0x20AC, hkl);   // euro sign
    }

    private static boolean needsAltGr(int ch, MemorySegment hkl) throws Throwable {
        short r = (short) VK_KEY_SCAN_EX.invoke((short) ch, hkl);
        if (r == -1) return false;        // character this layout cannot produce
        // High byte = shift-state: bit0 Shift, bit1 Ctrl, bit2 Alt. Ctrl+Alt = AltGr.
        return ((r >> 8) & 0x06) == 0x06;
    }
}
