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

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Which control of a real controller drives each button of the pad: red (fire), blue,
 * green, yellow, rewind, forward and play. The directions always come from the left stick,
 * the D-pad or the first hat.
 *
 * <p>A control is a token: a button or trigger of the standard gamepad layout ({@code A},
 * {@code B}, {@code X}, {@code Y}, {@code LB}, {@code RB}, {@code BACK}, {@code START},
 * {@code GUIDE}, {@code LS}, {@code RS}, {@code LT}, {@code RT}), read from controllers that
 * have such a layout, or a raw button number ({@code BUTTON3}), read from any controller. A
 * layout token on a controller without the layout falls back on the raw button that layout
 * puts there on most controllers (A = 0, B = 1, X = 2, Y = 3, LB = 4, RB = 5, BACK = 6,
 * START = 7). An empty token leaves the button unassigned.
 */
public final class JoypadMap {

    /** The pad buttons, in the order the configuration window lists them. */
    public static final JoystickAction[] BUTTONS = {
            JoystickAction.FIRE, JoystickAction.BLUE, JoystickAction.GREEN,
            JoystickAction.YELLOW, JoystickAction.REWIND, JoystickAction.FORWARD,
            JoystickAction.PLAY };

    /** Layout tokens, by the index of the standard gamepad button. */
    public static final String[] LAYOUT_BUTTONS = {
            "A", "B", "X", "Y", "LB", "RB", "BACK", "START", "GUIDE", "LS", "RS" };

    /** The two analogue triggers, read as buttons past half travel. */
    public static final String LEFT_TRIGGER = "LT";
    public static final String RIGHT_TRIGGER = "RT";

    private static final String RAW_PREFIX = "BUTTON";

    private final Map<JoystickAction, String> tokens = new EnumMap<>(JoystickAction.class);

    /** The mapping of a controller with the standard layout, buttons by their position. */
    public static JoypadMap defaults() {
        JoypadMap m = new JoypadMap();
        m.set(JoystickAction.FIRE, "A");
        m.set(JoystickAction.BLUE, "B");
        m.set(JoystickAction.GREEN, "X");
        m.set(JoystickAction.YELLOW, "Y");
        m.set(JoystickAction.REWIND, "LB");
        m.set(JoystickAction.FORWARD, "RB");
        m.set(JoystickAction.PLAY, "START");
        return m;
    }

    public JoypadMap copy() {
        JoypadMap m = new JoypadMap();
        m.tokens.putAll(tokens);
        return m;
    }

    /** The token of a pad button, empty if unassigned. */
    public String get(JoystickAction button) {
        return tokens.getOrDefault(button, "");
    }

    /** Assigns a token (normalised; an invalid one leaves the button unassigned). */
    public void set(JoystickAction button, String token) {
        tokens.put(button, normalise(token));
    }

    /** {@code token} in its canonical form, or empty if it is not a control. */
    public static String normalise(String token) {
        if (token == null) return "";
        String t = token.trim().toUpperCase(Locale.ROOT);
        if (t.equals(LEFT_TRIGGER) || t.equals(RIGHT_TRIGGER)) return t;
        for (String b : LAYOUT_BUTTONS) {
            if (b.equals(t)) return t;
        }
        if (t.startsWith(RAW_PREFIX)) {
            try {
                int n = Integer.parseInt(t.substring(RAW_PREFIX.length()));
                if (n >= 0 && n < 64) return RAW_PREFIX + n;
            } catch (NumberFormatException e) {
                return "";
            }
        }
        return "";
    }

    /** The token of a raw button. */
    public static String rawButton(int index) {
        return RAW_PREFIX + index;
    }

    /** Index of the standard layout button a token names, or -1 (raw button, trigger). */
    public static int layoutIndex(String token) {
        for (int i = 0; i < LAYOUT_BUTTONS.length; i++) {
            if (LAYOUT_BUTTONS[i].equals(token)) return i;
        }
        return -1;
    }

    /** The raw button a token reads: its own number, or where the layout puts it; or -1. */
    public static int rawIndex(String token) {
        if (token.startsWith(RAW_PREFIX)) {
            return Integer.parseInt(token.substring(RAW_PREFIX.length()));
        }
        int layout = layoutIndex(token);
        return layout >= 0 && layout <= 7 ? layout : -1;
    }
}
