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
 * Lettura dello stato FISICO dei tasti su Windows tramite
 * {@code user32!GetAsyncKeyState}, via Foreign Function API (nessuna dipendenza
 * esterna, JDK &ge; 22 / stabile su questo runtime).
 *
 * <p>Serve ad aggirare un limite dell'input tastiera di Windows così come lo
 * espone AWT: quando <em>entrambi</em> gli Shift sono premuti e se ne rilascia
 * uno, l'OS <b>non</b> emette il {@code keyReleased} del primo rilasciato (lo
 * emette solo per l'ultimo). Il keyup intermedio è irrecuperabile lato AWT.
 * Leggendo lo stato fisico dei due shift possiamo pilotarli in modo indipendente
 * e corretto — la stessa tecnica usata da WinUAE e, internamente, da GLFW.
 *
 * <p>Su piattaforme non-Windows, o se il linking del simbolo fallisce,
 * {@link #isAvailable()} ritorna {@code false} e {@link #isDown(int)} ritorna
 * sempre {@code false}: il chiamante deve prevedere un fallback (gestione AWT).
 */
public final class WindowsKeyState {

    /** Virtual-Key code dello Shift sinistro (WinUser.h). */
    public static final int VK_LSHIFT = 0xA0;
    /** Virtual-Key code dello Shift destro (WinUser.h). */
    public static final int VK_RSHIFT = 0xA1;
    /** Virtual-Key code del Control sinistro (WinUser.h). */
    public static final int VK_LCONTROL = 0xA2;
    /** Virtual-Key code del Control destro (WinUser.h). */
    public static final int VK_RCONTROL = 0xA3;
    /** Virtual-Key code dell'Alt sinistro (WinUser.h). */
    public static final int VK_LMENU = 0xA4;
    /** Virtual-Key code dell'Alt destro / AltGr (WinUser.h). */
    public static final int VK_RMENU = 0xA5;
    /** Virtual-Key code del tasto Windows sinistro (WinUser.h). */
    public static final int VK_LWIN = 0x5B;
    /** Virtual-Key code del tasto Windows destro (WinUser.h). */
    public static final int VK_RWIN = 0x5C;

    /** Handle a GetAsyncKeyState, o {@code null} se non disponibile. */
    private static final MethodHandle GET_ASYNC_KEY_STATE =
            link("GetAsyncKeyState",
                 FunctionDescriptor.of(ValueLayout.JAVA_SHORT, ValueLayout.JAVA_INT));

    /** Handle a GetKeyboardLayout, o {@code null} se non disponibile. */
    private static final MethodHandle GET_KEYBOARD_LAYOUT =
            link("GetKeyboardLayout",
                 FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

    /** Handle a VkKeyScanExW, o {@code null} se non disponibile. */
    private static final MethodHandle VK_KEY_SCAN_EX =
            link("VkKeyScanExW",
                 FunctionDescriptor.of(ValueLayout.JAVA_SHORT, ValueLayout.JAVA_SHORT,
                                       ValueLayout.ADDRESS));

    /** HKL per cui è stato calcolato {@link #cachedHasAltGr} (0 = nessuno). */
    private static long cachedHkl;
    /** Esito della scansione del layout {@link #cachedHkl}. */
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
            // Ambiente senza user32, native access negato, ecc.: fallback silenzioso.
            return null;
        }
    }

    /** @return true se la lettura dello stato fisico dei tasti è utilizzabile. */
    public static boolean isAvailable() {
        return GET_ASYNC_KEY_STATE != null;
    }

    /**
     * @param vkey virtual-key code (es. {@link #VK_LSHIFT})
     * @return true se il tasto è fisicamente premuto ORA; sempre false se
     *         {@link #isAvailable()} è false.
     */
    public static boolean isDown(int vkey) {
        if (GET_ASYNC_KEY_STATE == null) {
            return false;
        }
        try {
            short s = (short) GET_ASYNC_KEY_STATE.invoke(vkey);
            // Bit più significativo (0x8000) = tasto attualmente premuto.
            return (s & 0x8000) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * @return true se il layout di tastiera attivo ha un tasto AltGr, cioè se
     *         almeno un carattere si ottiene con lo shift-state Ctrl+Alt (è il
     *         caso dei layout italiano, tedesco, francese…; non del layout US,
     *         dove l'Alt destro è un Alt normale).
     *
     * <p>Serve a riconoscere il Control <em>sintetico</em> che Windows genera
     * insieme all'Alt destro sui layout con AltGr. L'esito è messo in cache per
     * HKL: la scansione vera avviene solo al primo uso e a ogni cambio layout.
     *
     * <p>Se il probe non è linkabile si assume {@code true} su Windows: senza
     * questa informazione conviene comunque neutralizzare il Ctrl sintetico,
     * perché su un layout che non ha AltGr l'unico effetto collaterale è perdere
     * un Ctrl premuto <em>mentre</em> l'Alt destro è già giù.
     */
    public static boolean activeLayoutHasAltGr() {
        if (GET_KEYBOARD_LAYOUT == null || VK_KEY_SCAN_EX == null) {
            return isAvailable();
        }
        try {
            // idThread = 0 → layout del thread chiamante (di norma quello di sistema).
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

    /** Cerca nel layout un carattere che richieda Ctrl+Alt (= AltGr). */
    private static boolean probeAltGr(MemorySegment hkl) throws Throwable {
        for (int ch = 0x20; ch <= 0xFF; ch++) {
            if (needsAltGr(ch, hkl)) return true;
        }
        return needsAltGr(0x20AC, hkl);   // €
    }

    private static boolean needsAltGr(int ch, MemorySegment hkl) throws Throwable {
        short r = (short) VK_KEY_SCAN_EX.invoke((short) ch, hkl);
        if (r == -1) return false;        // carattere non producibile con questo layout
        // Byte alto = shift-state: bit0 Shift, bit1 Ctrl, bit2 Alt. Ctrl+Alt = AltGr.
        return ((r >> 8) & 0x06) == 0x06;
    }
}
