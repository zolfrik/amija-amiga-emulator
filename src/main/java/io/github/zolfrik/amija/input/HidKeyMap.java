/*
 * AmiJa - a cycle-exact Amiga emulator in Java.
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

package io.github.zolfrik.amija.input;

import java.awt.event.KeyEvent;

/**
 * USB HID keyboard usages to Amiga raw key codes.
 *
 * <p>A usage names a key by its <b>position</b>, not by its symbol, and so does an Amiga raw
 * key: the map is positional, and the layout is the Amiga keymap's business, as with a real
 * Amiga keyboard. Keys the Amiga lacks are reused as on the PC's own keyboard: both Alt and
 * both GUI keys are the Amiga keys, Page Up and Page Down the Amiga's two Alt keys, Home is
 * HELP, Num Lock and Scroll Lock the keypad's parentheses. F11, F12 and the rest of the
 * navigation block send nothing.
 */
public final class HidKeyMap {

    private static final int[] RAW = new int[256];
    private static final KeyBinding[] HOST = new KeyBinding[256];

    static {
        java.util.Arrays.fill(RAW, -1);
        // Letters: usages 0x04..0x1D are A..Z.
        int[] letters = {
            0x20, 0x35, 0x33, 0x22, 0x12, 0x23, 0x24, 0x25, 0x17, 0x26, 0x27, 0x28, 0x37,
            0x36, 0x18, 0x19, 0x10, 0x13, 0x21, 0x14, 0x16, 0x34, 0x11, 0x32, 0x15, 0x31,
        };
        for (int i = 0; i < letters.length; i++) {
            RAW[0x04 + i] = letters[i];
        }
        // Digits 1..9, 0.
        for (int i = 0; i < 10; i++) {
            RAW[0x1E + i] = 0x01 + i;
        }
        RAW[0x28] = 0x44;   // Return
        RAW[0x29] = 0x45;   // Esc
        RAW[0x2A] = 0x41;   // Backspace
        RAW[0x2B] = 0x42;   // Tab
        RAW[0x2C] = 0x40;   // Space
        RAW[0x2D] = 0x0B;   // - _
        RAW[0x2E] = 0x0C;   // = +
        RAW[0x2F] = 0x1A;   // [ {
        RAW[0x30] = 0x1B;   // ] }
        RAW[0x31] = 0x0D;   // backslash |
        RAW[0x32] = 0x2B;   // the key left of Return on ISO keyboards
        RAW[0x33] = 0x29;   // ; :
        RAW[0x34] = 0x2A;   // ' "
        RAW[0x35] = 0x00;   // ` ~
        RAW[0x36] = 0x38;   // , <
        RAW[0x37] = 0x39;   // . >
        RAW[0x38] = 0x3A;   // / ?
        RAW[0x39] = 0x62;   // Caps Lock
        // F1..F10.
        for (int i = 0; i < 10; i++) {
            RAW[0x3A + i] = 0x50 + i;
        }
        RAW[0x47] = 0x5B;   // Scroll Lock: keypad )
        RAW[0x4A] = 0x5F;   // HELP (Home)
        RAW[0x4B] = 0x64;   // left Alt (Page Up)
        RAW[0x4C] = 0x46;   // Del
        RAW[0x4E] = 0x65;   // right Alt (Page Down)
        RAW[0x4F] = 0x4E;   // right
        RAW[0x50] = 0x4F;   // left
        RAW[0x51] = 0x4D;   // down
        RAW[0x52] = 0x4C;   // up
        RAW[0x53] = 0x5A;   // Num Lock: keypad (
        RAW[0x54] = 0x5C;   // keypad /
        RAW[0x55] = 0x5D;   // keypad *
        RAW[0x56] = 0x4A;   // keypad -
        RAW[0x57] = 0x5E;   // keypad +
        RAW[0x58] = 0x43;   // keypad Enter
        int[] keypad = { 0x1D, 0x1E, 0x1F, 0x2D, 0x2E, 0x2F, 0x3D, 0x3E, 0x3F, 0x0F };
        for (int i = 0; i < keypad.length; i++) {
            RAW[0x59 + i] = keypad[i];   // keypad 1..9, 0
        }
        RAW[0x63] = 0x3C;   // keypad .
        RAW[0x64] = 0x30;   // the key right of left Shift on ISO keyboards
        RAW[0xE0] = 0x63;   // Ctrl
        RAW[0xE1] = 0x60;   // left Shift
        RAW[0xE2] = 0x66;   // left Amiga (left Alt)
        RAW[0xE3] = 0x66;   // left Amiga (left GUI)
        RAW[0xE4] = 0x63;   // Ctrl
        RAW[0xE5] = 0x61;   // right Shift
        RAW[0xE6] = 0x67;   // right Amiga (right Alt)
        RAW[0xE7] = 0x67;   // right Amiga (right GUI)
    }

    static {
        int std = KeyEvent.KEY_LOCATION_STANDARD;
        int pad = KeyEvent.KEY_LOCATION_NUMPAD;
        int left = KeyEvent.KEY_LOCATION_LEFT;
        int right = KeyEvent.KEY_LOCATION_RIGHT;
        for (int i = 0; i < 26; i++) {
            host(0x04 + i, KeyEvent.VK_A + i, std);
        }
        for (int i = 0; i < 9; i++) {
            host(0x1E + i, KeyEvent.VK_1 + i, std);
        }
        host(0x27, KeyEvent.VK_0, std);
        host(0x28, KeyEvent.VK_ENTER, std);
        host(0x29, KeyEvent.VK_ESCAPE, std);
        host(0x2A, KeyEvent.VK_BACK_SPACE, std);
        host(0x2B, KeyEvent.VK_TAB, std);
        host(0x2C, KeyEvent.VK_SPACE, std);
        host(0x2D, KeyEvent.VK_MINUS, std);
        host(0x2E, KeyEvent.VK_EQUALS, std);
        host(0x2F, KeyEvent.VK_OPEN_BRACKET, std);
        host(0x30, KeyEvent.VK_CLOSE_BRACKET, std);
        host(0x31, KeyEvent.VK_BACK_SLASH, std);
        host(0x33, KeyEvent.VK_SEMICOLON, std);
        host(0x34, KeyEvent.VK_QUOTE, std);
        host(0x35, KeyEvent.VK_BACK_QUOTE, std);
        host(0x36, KeyEvent.VK_COMMA, std);
        host(0x37, KeyEvent.VK_PERIOD, std);
        host(0x38, KeyEvent.VK_SLASH, std);
        host(0x39, KeyEvent.VK_CAPS_LOCK, std);
        for (int i = 0; i < 12; i++) {
            host(0x3A + i, KeyEvent.VK_F1 + i, std);
        }
        host(0x46, KeyEvent.VK_PRINTSCREEN, std);
        host(0x47, KeyEvent.VK_SCROLL_LOCK, std);
        host(0x48, KeyEvent.VK_PAUSE, std);
        host(0x49, KeyEvent.VK_INSERT, std);
        host(0x4A, KeyEvent.VK_HOME, std);
        host(0x4B, KeyEvent.VK_PAGE_UP, std);
        host(0x4C, KeyEvent.VK_DELETE, std);
        host(0x4D, KeyEvent.VK_END, std);
        host(0x4E, KeyEvent.VK_PAGE_DOWN, std);
        host(0x4F, KeyEvent.VK_RIGHT, std);
        host(0x50, KeyEvent.VK_LEFT, std);
        host(0x51, KeyEvent.VK_DOWN, std);
        host(0x52, KeyEvent.VK_UP, std);
        host(0x53, KeyEvent.VK_NUM_LOCK, pad);
        host(0x54, KeyEvent.VK_DIVIDE, pad);
        host(0x55, KeyEvent.VK_MULTIPLY, pad);
        host(0x56, KeyEvent.VK_SUBTRACT, pad);
        host(0x57, KeyEvent.VK_ADD, pad);
        host(0x58, KeyEvent.VK_ENTER, pad);
        for (int i = 0; i < 9; i++) {
            host(0x59 + i, KeyEvent.VK_NUMPAD1 + i, pad);
        }
        host(0x62, KeyEvent.VK_NUMPAD0, pad);
        host(0x63, KeyEvent.VK_DECIMAL, pad);
        host(0x64, KeyEvent.VK_LESS, std);
        host(0xE0, KeyEvent.VK_CONTROL, left);
        host(0xE1, KeyEvent.VK_SHIFT, left);
        host(0xE2, KeyEvent.VK_ALT, left);
        host(0xE3, KeyEvent.VK_WINDOWS, left);
        host(0xE4, KeyEvent.VK_CONTROL, right);
        host(0xE5, KeyEvent.VK_SHIFT, right);
        host(0xE6, KeyEvent.VK_ALT, right);
        host(0xE7, KeyEvent.VK_WINDOWS, right);
    }

    private static void host(int usage, int keyCode, int location) {
        HOST[usage] = new KeyBinding(keyCode, location);
    }

    private HidKeyMap() {
    }

    /**
     * The PC key at the same position as HID usage {@code usage}, as a US keyboard names it,
     * or {@code null} when it has none. It lets the keys chosen for a keyboard joystick on
     * the PC be found on another keyboard.
     */
    public static KeyBinding hostKey(int usage) {
        return usage < 0 || usage >= HOST.length ? null : HOST[usage];
    }

    /** The Amiga raw key for HID usage {@code usage}, or -1 when it has none. */
    public static int rawKey(int usage) {
        return usage < 0 || usage >= RAW.length ? -1 : RAW[usage];
    }
}
