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

package io.github.zolfrik.amija.custom;

import io.github.zolfrik.amija.common.WindowsKeyState;
import io.github.zolfrik.amija.input.AmigaKeyTarget;

import java.awt.Window;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.HashMap;
import java.util.Map;
import javax.swing.Timer;

/**
 * Translates AWT KeyEvents into Amiga raw key numbers and passes them to
 * Keyboard.pressKey() / releaseKey().
 *
 * <p><b>Shift handling.</b> Windows does not deliver to AWT the {@code keyReleased} of the
 * first shift released when both are held (it emits it only for the last): the left flipper
 * would stay stuck. When the physical key state can be read ({@link WindowsKeyState}), the
 * two shifts are NOT handled through AWT events but by a poller that reads L/R shift
 * directly and drives them independently. If the physical read is not available
 * (non-Windows, or no window), AWT handling is used with {@link #reconcileModifiers} as a
 * mitigation.
 *
 * <p><b>AltGr handling.</b> On layouts with AltGr, Windows pairs the right Alt with a
 * synthetic left Control: the Amiga would get CTRL + right Amiga, and with the left Alt held
 * the combination would become a reset. The synthetic Ctrl is recognised and ignored; see
 * {@link #isSyntheticAltGrCtrl}.
 */
public class AmigaKeyAdapter extends KeyAdapter {

    private final AmigaKeyTarget keyboard;

    /** Window the adapter is registered on, for the poller's focus gating. */
    private final Window focusWindow;

    /** True if the two shifts are driven by the physical poller instead of AWT. */
    private final boolean shiftPollerActive;

    /** EDT timer sampling the physical state of the two shifts (~120 Hz). */
    private Timer shiftPoller;

    /** Last state synthesised for L/R shift (to emit only the transitions). */
    private boolean lShiftDown = false;
    private boolean rShiftDown = false;

    /** True if the current left Ctrl is AltGr's synthetic one (press ignored). */
    private boolean altGrCtrlSuppressed = false;

    // Map Java VK_ -> Amiga raw key number (HRM appendix A)
    private static final Map<Integer, Integer> KEYMAP = new HashMap<>();
    private static final Map<Character, Integer> CHAR_KEYMAP = new HashMap<>();
    private static final Map<Character, Integer> FALLBACK_KEYMAP = new HashMap<>();
    private final Map<Integer, Integer> pressedKeys = new HashMap<>();

    static {
        // Lettere
        KEYMAP.put(KeyEvent.VK_A, 0x20);
        KEYMAP.put(KeyEvent.VK_B, 0x35);
        KEYMAP.put(KeyEvent.VK_C, 0x33);
        KEYMAP.put(KeyEvent.VK_D, 0x22);
        KEYMAP.put(KeyEvent.VK_E, 0x12);
        KEYMAP.put(KeyEvent.VK_F, 0x23);
        KEYMAP.put(KeyEvent.VK_G, 0x24);
        KEYMAP.put(KeyEvent.VK_H, 0x25);
        KEYMAP.put(KeyEvent.VK_I, 0x17);
        KEYMAP.put(KeyEvent.VK_J, 0x26);
        KEYMAP.put(KeyEvent.VK_K, 0x27);
        KEYMAP.put(KeyEvent.VK_L, 0x28);
        KEYMAP.put(KeyEvent.VK_M, 0x37);
        KEYMAP.put(KeyEvent.VK_N, 0x36);
        KEYMAP.put(KeyEvent.VK_O, 0x18);
        KEYMAP.put(KeyEvent.VK_P, 0x19);
        KEYMAP.put(KeyEvent.VK_Q, 0x10);
        KEYMAP.put(KeyEvent.VK_R, 0x13);
        KEYMAP.put(KeyEvent.VK_S, 0x21);
        KEYMAP.put(KeyEvent.VK_T, 0x14);
        KEYMAP.put(KeyEvent.VK_U, 0x16);
        KEYMAP.put(KeyEvent.VK_V, 0x34);
        KEYMAP.put(KeyEvent.VK_W, 0x11);
        KEYMAP.put(KeyEvent.VK_X, 0x32);
        KEYMAP.put(KeyEvent.VK_Y, 0x15);
        KEYMAP.put(KeyEvent.VK_Z, 0x31);

        // Digits of the top row
        KEYMAP.put(KeyEvent.VK_1, 0x01);
        KEYMAP.put(KeyEvent.VK_2, 0x02);
        KEYMAP.put(KeyEvent.VK_3, 0x03);
        KEYMAP.put(KeyEvent.VK_4, 0x04);
        KEYMAP.put(KeyEvent.VK_5, 0x05);
        KEYMAP.put(KeyEvent.VK_6, 0x06);
        KEYMAP.put(KeyEvent.VK_7, 0x07);
        KEYMAP.put(KeyEvent.VK_8, 0x08);
        KEYMAP.put(KeyEvent.VK_9, 0x09);
        KEYMAP.put(KeyEvent.VK_0, 0x0A);
        KEYMAP.put(KeyEvent.VK_BACK_QUOTE, 0x00);
        KEYMAP.put(KeyEvent.VK_MINUS, 0x0B);
        KEYMAP.put(KeyEvent.VK_EQUALS, 0x0C);
        KEYMAP.put(KeyEvent.VK_BACK_SLASH, 0x0D);
        KEYMAP.put(KeyEvent.VK_OPEN_BRACKET, 0x1A);
        KEYMAP.put(KeyEvent.VK_CLOSE_BRACKET, 0x1B);
        KEYMAP.put(KeyEvent.VK_SEMICOLON, 0x29);
        KEYMAP.put(KeyEvent.VK_QUOTE, 0x2A);
        KEYMAP.put(KeyEvent.VK_COMMA, 0x38);
        KEYMAP.put(KeyEvent.VK_PERIOD, 0x39);
        KEYMAP.put(KeyEvent.VK_SLASH, 0x3A);

        // Special keys: uses the constants already defined in Keyboard
        KEYMAP.put(KeyEvent.VK_ESCAPE, Keyboard.KEY_ESC);
        KEYMAP.put(KeyEvent.VK_BACK_SPACE, Keyboard.KEY_BACKSPACE);
        KEYMAP.put(KeyEvent.VK_TAB, Keyboard.KEY_TAB);
        KEYMAP.put(KeyEvent.VK_ENTER, Keyboard.KEY_RETURN);
        KEYMAP.put(KeyEvent.VK_SPACE, Keyboard.KEY_SPACE);
        KEYMAP.put(KeyEvent.VK_DELETE, Keyboard.KEY_DEL);
        KEYMAP.put(KeyEvent.VK_CAPS_LOCK, Keyboard.KEY_CAPS_LOCK);
        // HELP is not on F12: that one opens the configuration popup (Amija consumes it
        // before the machine's adapters). See the navigation block further down.

        // Frecce
        KEYMAP.put(KeyEvent.VK_UP, Keyboard.KEY_UP);
        KEYMAP.put(KeyEvent.VK_DOWN, Keyboard.KEY_DOWN);
        KEYMAP.put(KeyEvent.VK_LEFT, Keyboard.KEY_LEFT);
        KEYMAP.put(KeyEvent.VK_RIGHT, Keyboard.KEY_RIGHT);

        // Function keys
        KEYMAP.put(KeyEvent.VK_F1, 0x50);
        KEYMAP.put(KeyEvent.VK_F2, 0x51);
        KEYMAP.put(KeyEvent.VK_F3, 0x52);
        KEYMAP.put(KeyEvent.VK_F4, 0x53);
        KEYMAP.put(KeyEvent.VK_F5, 0x54);
        KEYMAP.put(KeyEvent.VK_F6, 0x55);
        KEYMAP.put(KeyEvent.VK_F7, 0x56);
        KEYMAP.put(KeyEvent.VK_F8, 0x57);
        KEYMAP.put(KeyEvent.VK_F9, 0x58);
        KEYMAP.put(KeyEvent.VK_F10, 0x59);

        // Numpad
        KEYMAP.put(KeyEvent.VK_NUMPAD0, 0x0F);
        KEYMAP.put(KeyEvent.VK_NUMPAD1, 0x1D);
        KEYMAP.put(KeyEvent.VK_NUMPAD2, 0x1E);
        KEYMAP.put(KeyEvent.VK_NUMPAD3, 0x1F);
        KEYMAP.put(KeyEvent.VK_NUMPAD4, 0x2D);
        KEYMAP.put(KeyEvent.VK_NUMPAD5, 0x2E);
        KEYMAP.put(KeyEvent.VK_NUMPAD6, 0x2F);
        KEYMAP.put(KeyEvent.VK_NUMPAD7, 0x3D);
        KEYMAP.put(KeyEvent.VK_NUMPAD8, 0x3E);
        KEYMAP.put(KeyEvent.VK_NUMPAD9, 0x3F);
        KEYMAP.put(KeyEvent.VK_DECIMAL, 0x3C);
        KEYMAP.put(KeyEvent.VK_ADD, 0x5E);
        KEYMAP.put(KeyEvent.VK_SUBTRACT, 0x4A);
        KEYMAP.put(KeyEvent.VK_MULTIPLY, 0x5D);
        KEYMAP.put(KeyEvent.VK_DIVIDE, 0x5C);

        // Printable symbols: keyChar follows the real layout of the host keyboard.
        CHAR_KEYMAP.put('`', 0x00);
        CHAR_KEYMAP.put('1', 0x01);
        CHAR_KEYMAP.put('2', 0x02);
        CHAR_KEYMAP.put('3', 0x03);
        CHAR_KEYMAP.put('4', 0x04);
        CHAR_KEYMAP.put('5', 0x05);
        CHAR_KEYMAP.put('6', 0x06);
        CHAR_KEYMAP.put('7', 0x07);
        CHAR_KEYMAP.put('8', 0x08);
        CHAR_KEYMAP.put('9', 0x09);
        CHAR_KEYMAP.put('(', 0x5A);
        CHAR_KEYMAP.put('0', 0x0A);
        CHAR_KEYMAP.put(')', 0x5B);
        CHAR_KEYMAP.put('-', 0x0B);
        CHAR_KEYMAP.put('_', 0x0B);
        CHAR_KEYMAP.put('=', 0x0C);
        CHAR_KEYMAP.put('+', 0x0C);
        CHAR_KEYMAP.put('\\', 0x0D);
        CHAR_KEYMAP.put('|', 0x0D);
        CHAR_KEYMAP.put('[', 0x1A);
        CHAR_KEYMAP.put('{', 0x1A);
        CHAR_KEYMAP.put(']', 0x1B);
        CHAR_KEYMAP.put('}', 0x1B);
        CHAR_KEYMAP.put(';', 0x29);
        CHAR_KEYMAP.put(':', 0x29);
        CHAR_KEYMAP.put('\'', 0x2A);
        CHAR_KEYMAP.put('"', 0x2A);
        CHAR_KEYMAP.put('#', 0x2B);
        CHAR_KEYMAP.put('<', 0x30);
        CHAR_KEYMAP.put('>', 0x30);
        CHAR_KEYMAP.put(',', 0x38);
        CHAR_KEYMAP.put('.', 0x39);
        CHAR_KEYMAP.put('/', 0x3A);
        CHAR_KEYMAP.put('?', 0x3A);

        // Keys AWT cannot name (keyCode = VK_UNDEFINED) whose character does not exist on
        // the US layout: without this net they would stay MUTE. They are the five accented
        // letters of the Italian keyboard, mapped by POSITION to the raw key number in the
        // same place on the Amiga keyboard, so the key always sends the same code both bare
        // and with Shift, which is the right one if the Italian keymap is loaded on the
        // Amiga (setmap i). With the default US keymap they give the symbols those positions
        // have there (= [ ; ' #), otherwise unreachable from an Italian keyboard.
        //
        // This map is consulted LAST, so it cannot hijack the layouts in which the same
        // characters come from a key with a known keyCode (e.g. the e-acute of the French
        // AZERTY, which is on the number row and already handled by KEYMAP).
        FALLBACK_KEYMAP.put('ì', 0x0C);   // US position '='
        FALLBACK_KEYMAP.put('^', 0x0C);   // Shift+ì
        FALLBACK_KEYMAP.put('è', 0x1A);   // US position '['
        FALLBACK_KEYMAP.put('é', 0x1A);   // Shift+è
        FALLBACK_KEYMAP.put('ò', 0x29);   // US position ';'
        FALLBACK_KEYMAP.put('ç', 0x29);   // Shift+ò
        FALLBACK_KEYMAP.put('à', 0x2A);   // US position '\''
        FALLBACK_KEYMAP.put('°', 0x2A);   // Shift+à
        FALLBACK_KEYMAP.put('ù', 0x2B);   // US position '#'
        FALLBACK_KEYMAP.put('§', 0x2B);   // Shift+ù
    }

    public AmigaKeyAdapter(AmigaKeyTarget keyboard) {
        this(keyboard, null);
    }

    /**
     * @param keyboard    target Amiga keyboard
     * @param focusWindow window the adapter is registered on; if not null and the physical
     *                    key state can be read, the two shifts are driven by a poller
     *                    instead of AWT events (works around the shift key-up Windows
     *                    loses). Passing null (e.g. in headless tests) keeps AWT handling
     *                    only.
     */
    public AmigaKeyAdapter(AmigaKeyTarget keyboard, Window focusWindow) {
        this.keyboard = keyboard;
        this.focusWindow = focusWindow;
        this.shiftPollerActive = focusWindow != null && WindowsKeyState.isAvailable();
        if (shiftPollerActive) {
            // ~120 Hz: well above human cadence, below any noticeable cost.
            shiftPoller = new Timer(8, e -> { pollShiftState(); pollResetComboState(); });
            shiftPoller.setCoalesce(true);
            shiftPoller.start();
        }
    }

    /**
     * Stops the shift poller. To be called when the adapter is removed (e.g. emulator
     * restart) so no orphan EDT timers are left.
     */
    public void dispose() {
        if (shiftPoller != null) {
            shiftPoller.stop();
            shiftPoller = null;
        }
    }

    @Override
    public void keyPressed(KeyEvent e) {
        // Avoid the operating system's key repeat: Keyboard.java already handles the state
        if (e.isConsumed()) {
            return;
        }
        // The shifts belong to the physical poller: consume and ignore here.
        if (shiftPollerActive && e.getKeyCode() == KeyEvent.VK_SHIFT) {
            e.consume();
            return;
        }
        // AltGr: the right Alt goes to the Amiga as right Amiga alone, without the Ctrl
        // Windows pairs with it (see isSyntheticAltGrCtrl).
        if (isSyntheticAltGrCtrl(e)) {
            altGrCtrlSuppressed = true;
            e.consume();
            return;
        }
        int amigaKey = translate(e);
        if (amigaKey >= 0) {
            int keyId = keyId(e, amigaKey);
            if (!pressedKeys.containsKey(keyId)) {
                pressedKeys.put(keyId, amigaKey);
                keyboard.pressKey(amigaKey);
            }
            e.consume();
        }
    }

    @Override
    public void keyReleased(KeyEvent e) {
        if (e.isConsumed()) return;
        // The shifts belong to the physical poller: consume and ignore here.
        if (shiftPollerActive && e.getKeyCode() == KeyEvent.VK_SHIFT) {
            e.consume();
            return;
        }
        // Counterpart of the ignored press: Windows closes AltGr with a left Ctrl key-up.
        // Ignoring it avoids releasing a real Ctrl held down.
        if (altGrCtrlSuppressed && isLeftCtrl(e)) {
            altGrCtrlSuppressed = false;
            e.consume();
            return;
        }
        int translated = translate(e);
        Integer pressedKey = pressedKeys.remove(keyId(e, translated));
        int amigaKey = pressedKey != null ? pressedKey : translated;
        if (amigaKey >= 0) {
            keyboard.releaseKey(amigaKey);
            e.consume();
        }
        // Fallback (physical poller not available): recovers the keyReleased events Windows
        // does not deliver when one of two shifts held together is released.
        if (!shiftPollerActive) {
            reconcileModifiers(e);
        }
    }

    /**
     * Poller of the physical state of the two shifts (called on the EDT by the timer).
     *
     * Reads L/R shift straight from the OS ({@link WindowsKeyState}) and synthesises
     * independent press/release for {@link Keyboard#KEY_L_SHIFT} and
     * {@link Keyboard#KEY_R_SHIFT}. Works around Windows not delivering to AWT the key-up of
     * the first shift released when both are held.
     *
     * The focus gating avoids catching shifts meant for other windows: if the window does
     * not have the focus, both shifts are taken as up (and any pending synthesis released),
     * so they do not stay stuck.
     */
    private void pollShiftState() {
        boolean focused = focusWindow != null && focusWindow.isFocused();
        boolean lNow = focused && WindowsKeyState.isDown(WindowsKeyState.VK_LSHIFT);
        boolean rNow = focused && WindowsKeyState.isDown(WindowsKeyState.VK_RSHIFT);

        if (lNow != lShiftDown) {
            lShiftDown = lNow;
            if (lNow) keyboard.pressKey(Keyboard.KEY_L_SHIFT);
            else      keyboard.releaseKey(Keyboard.KEY_L_SHIFT);
        }
        if (rNow != rShiftDown) {
            rShiftDown = rNow;
            if (rNow) keyboard.pressKey(Keyboard.KEY_R_SHIFT);
            else      keyboard.releaseKey(Keyboard.KEY_R_SHIFT);
        }
    }

    /**
     * "Physical" release of the CTRL+AMIGA+AMIGA reset keys (called on the EDT).
     *
     * While the combination is held the machine is kept in reset by the keyboard, and
     * leaves it only when the keys are released: if the OS loses one of the key-ups, the
     * machine would stay stopped. It is the same Windows limit worked around for the shifts
     * in {@link #pollShiftState()} (no keyReleased for the first modifier released when two
     * of the same kind are held, which is exactly the case of the two Alts = the two Amiga
     * keys), and it also applies when the window loses focus.
     *
     * The poller acts ONLY while the combination reads as held, so it cannot interfere with
     * normal typing. The safety cap in {@code Keyboard} covers the platforms without a
     * physical state read.
     */
    private void pollResetComboState() {
        if (!keyboard.isResetComboHeld()) return;

        boolean focused = focusWindow != null && focusWindow.isFocused();
        if (!focused || !(WindowsKeyState.isDown(WindowsKeyState.VK_LCONTROL)
                       || WindowsKeyState.isDown(WindowsKeyState.VK_RCONTROL))) {
            releaseTrackedAmigaKey(Keyboard.KEY_CTRL);
        }
        if (!focused || !(WindowsKeyState.isDown(WindowsKeyState.VK_LMENU)
                       || WindowsKeyState.isDown(WindowsKeyState.VK_LWIN))) {
            releaseTrackedAmigaKey(Keyboard.KEY_L_AMIGA);
        }
        if (!focused || !(WindowsKeyState.isDown(WindowsKeyState.VK_RMENU)
                       || WindowsKeyState.isDown(WindowsKeyState.VK_RWIN))) {
            releaseTrackedAmigaKey(Keyboard.KEY_R_AMIGA);
        }
    }

    /**
     * True if this keyPressed is AltGr's <em>synthetic</em> Control.
     *
     * <p>On layouts with AltGr (Italian, German, French...) Windows does not deliver the right
     * Alt alone: it generates a left Control key-down first and then the right Alt's (AltGr
     * <em>is</em> Ctrl+Alt at OS level). Translated literally, the Amiga gets CTRL + right
     * Amiga, and holding the left Alt is enough to make the combination CTRL+AMIGA+AMIGA, a
     * reset.
     *
     * <p>The recognition does not look at the order of the events but at the PHYSICAL state
     * of the right Alt ({@link WindowsKeyState}): when the synthetic Ctrl is dispatched, the
     * right Alt that generated it already reads as held. The layout check limits the rule to
     * the keyboards that really have AltGr, so on US-like layouts (right Alt = normal Alt)
     * "right Alt + Ctrl" keeps working. Only a Ctrl pressed while AltGr is already down stays
     * ambiguous, and is ignored: there the OS has the Ctrl marked as held anyway.
     */
    private boolean isSyntheticAltGrCtrl(KeyEvent e) {
        return isLeftCtrl(e)
                && WindowsKeyState.isDown(WindowsKeyState.VK_RMENU)
                && WindowsKeyState.activeLayoutHasAltGr();
    }

    /** True for the left Control, the only one Windows synthesises for AltGr. */
    private static boolean isLeftCtrl(KeyEvent e) {
        return e.getKeyCode() == KeyEvent.VK_CONTROL
                && e.getKeyLocation() != KeyEvent.KEY_LOCATION_RIGHT;
    }

    /**
     * Syncs {@link #pressedKeys} with the real state of the modifiers reported by
     * {@link KeyEvent#getModifiersEx()}. If a modifier's mask is 0 (key physically up) but
     * the matching Amiga key is still tracked as held, it synthesises the release.
     *
     * Used only as a fallback when the physical poller is not available. The release
     * happens only when the OS confirms the modifier is up, so the synthesis is always
     * right and cannot produce spurious releases.
     */
    private void reconcileModifiers(KeyEvent e) {
        int ex = e.getModifiersEx();
        if ((ex & KeyEvent.SHIFT_DOWN_MASK) == 0) {
            releaseTrackedAmigaKey(Keyboard.KEY_L_SHIFT);
            releaseTrackedAmigaKey(Keyboard.KEY_R_SHIFT);
        }
    }

    /**
     * If {@code amigaKey} is still tracked as held in {@link #pressedKeys}, removes it and
     * notifies the keyboard of the release.
     */
    private void releaseTrackedAmigaKey(int amigaKey) {
        boolean wasTracked = pressedKeys.values().removeIf(v -> v == amigaKey);
        if (wasTracked) {
            keyboard.releaseKey(amigaKey);
        }
    }

    private int translate(KeyEvent e) {
        int vk = e.getKeyCode();

        // Modifiers: tell Left/Right apart with getKeyLocation()
        if (vk == KeyEvent.VK_SHIFT) {
            return e.getKeyLocation() == KeyEvent.KEY_LOCATION_RIGHT
                    ? Keyboard.KEY_R_SHIFT : Keyboard.KEY_L_SHIFT;
        }
        if (vk == KeyEvent.VK_ALT) {
            // Left Alt -> Left Amiga, Right Alt -> Right Amiga
            return e.getKeyLocation() == KeyEvent.KEY_LOCATION_RIGHT
                    ? Keyboard.KEY_R_AMIGA : Keyboard.KEY_L_AMIGA;
        }
        if (vk == KeyEvent.VK_ALT_GRAPH) {
            // Some platforms/layouts deliver the right Alt as AltGr.
            return Keyboard.KEY_R_AMIGA;
        }
        if (vk == KeyEvent.VK_CONTROL) {
            return Keyboard.KEY_CTRL;
        }
        if (vk == KeyEvent.VK_META || vk == KeyEvent.VK_WINDOWS) {
            // Win/Cmd key -> Left Amiga (or Right Amiga if on the right)
            return e.getKeyLocation() == KeyEvent.KEY_LOCATION_RIGHT
                    ? Keyboard.KEY_R_AMIGA : Keyboard.KEY_L_AMIGA;
        }

        // Navigation block: keys that do not exist on the Amiga, reused for the three Amiga
        // keys no host key would reach otherwise. The Amiga's Alts are those that with the
        // Italian keymap (setmap i) give @ # [ ] { }: the host Alts are already the two Amiga
        // keys, and F12 opens the popup. The numeric keypad is excluded: with NumLock off it
        // sends the same VKs as the navigation block.
        if (e.getKeyLocation() != KeyEvent.KEY_LOCATION_NUMPAD) {
            switch (vk) {
                case KeyEvent.VK_PAGE_UP:   return Keyboard.KEY_L_ALT;
                case KeyEvent.VK_PAGE_DOWN: return Keyboard.KEY_R_ALT;
                case KeyEvent.VK_HOME:      return Keyboard.KEY_HELP;
                default: break;
            }
        }

        if (vk == KeyEvent.VK_ENTER && e.getKeyLocation() == KeyEvent.KEY_LOCATION_NUMPAD) {
            return 0x43;
        }

        if (e.getKeyLocation() != KeyEvent.KEY_LOCATION_NUMPAD) {
            Integer charMapped = CHAR_KEYMAP.get(e.getKeyChar());
            if (charMapped != null) return charMapped;
        }

        Integer mapped = KEYMAP.get(vk);
        if (mapped != null) return mapped;

        Integer fallback = FALLBACK_KEYMAP.get(e.getKeyChar());
        return fallback != null ? fallback : -1;
    }

    /**
     * Identity of the physical key, with which {@link #pressedKeys} pairs press and
     * release.
     *
     * <p>The keys served by {@link #FALLBACK_KEYMAP} all have {@code keyCode == 0}
     * ({@link KeyEvent#VK_UNDEFINED}): an identity by keyCode would confuse them with each
     * other (pressing two of them, the second would not even start). For them the identity
     * is the target Amiga key, the same for every character produced by the same key, so it
     * holds even if Shift changes between press and release.
     */
    private static int keyId(KeyEvent e, int amigaKey) {
        if (e.getKeyCode() == KeyEvent.VK_UNDEFINED) {
            return 0x10000 | (amigaKey & 0xFF);
        }
        return (e.getKeyCode() << 4) | (e.getKeyLocation() & 0x0F);
    }
}
