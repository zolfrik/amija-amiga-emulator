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
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Host key assigned to a function of the keyboard joystick.
 *
 * <p>A key is identified by the pair (AWT <i>key code</i>, <i>location</i>): the location tells
 * apart the "double" keys of the keyboard (left and right Ctrl/Shift/Alt, and the numeric
 * keypad) that share the same key code. For example left and right Ctrl are both
 * {@code VK_CONTROL} and differ only by {@link KeyEvent#KEY_LOCATION_LEFT} /
 * {@link KeyEvent#KEY_LOCATION_RIGHT}.
 *
 * <p>In {@code amiga.properties} the key is written as the symbolic name of the
 * {@code KeyEvent.VK_*} constant without prefix, with the location as a suffix when it is not
 * the standard one: {@code W}, {@code UP}, {@code CONTROL:RIGHT}, {@code SHIFT:LEFT}. A plain
 * numeric key code is accepted too, for robustness.
 */
public record KeyBinding(int keyCode, int keyLocation) {

    private static final String LOC_LEFT   = "LEFT";
    private static final String LOC_RIGHT  = "RIGHT";
    private static final String LOC_NUMPAD = "NUMPAD";

    /** Symbolic name {@code VK_*} (without prefix) to/from AWT key code. */
    private static final Map<String, Integer> NAME_TO_CODE = new HashMap<>();
    private static final Map<Integer, String> CODE_TO_NAME = new HashMap<>();

    static {
        for (Field f : KeyEvent.class.getFields()) {
            String name = f.getName();
            if (!name.startsWith("VK_") || f.getType() != int.class
                    || !Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            try {
                int code = f.getInt(null);
                String token = name.substring(3);
                NAME_TO_CODE.put(token, code);
                // Several constants can share the same code: the alphabetically smallest name
                // is always chosen, so the configuration file stays stable from one start to
                // the next (the order of the fields is not guaranteed).
                String prev = CODE_TO_NAME.get(code);
                if (prev == null || token.compareTo(prev) < 0) CODE_TO_NAME.put(code, token);
            } catch (IllegalAccessException ignored) {
                // public field of a public class: cannot happen
            }
        }
    }

    public KeyBinding {
        keyLocation = normalize(keyLocation);
    }

    /** Key pressed in an AWT event, with its location on the keyboard. */
    public static KeyBinding fromEvent(KeyEvent e) {
        return new KeyBinding(e.getKeyCode(), e.getKeyLocation());
    }

    /** {@code true} if the event concerns exactly this key (code and location). */
    public boolean matches(KeyEvent e) {
        return e.getKeyCode() == keyCode && normalize(e.getKeyLocation()) == keyLocation;
    }

    /** Value to write in {@code amiga.properties} (see the class documentation). */
    public String configValue() {
        String name = CODE_TO_NAME.get(keyCode);
        String base = (name != null) ? name : Integer.toString(keyCode);
        return switch (keyLocation) {
            case KeyEvent.KEY_LOCATION_LEFT   -> base + ':' + LOC_LEFT;
            case KeyEvent.KEY_LOCATION_RIGHT  -> base + ':' + LOC_RIGHT;
            case KeyEvent.KEY_LOCATION_NUMPAD -> base + ':' + LOC_NUMPAD;
            default -> base;
        };
    }

    /**
     * Converts the value read from {@code amiga.properties} into the matching key, or
     * {@code null} if the string is empty, not recognised or means ESC (which cannot be
     * assigned: in the configuration popup it cancels the operation).
     */
    public static KeyBinding parse(String value) {
        if (value == null) return null;
        String v = value.trim();
        if (v.isEmpty()) return null;

        int location = KeyEvent.KEY_LOCATION_STANDARD;
        int colon = v.indexOf(':');
        if (colon >= 0) {
            String loc = v.substring(colon + 1).trim().toUpperCase(Locale.ROOT);
            location = switch (loc) {
                case LOC_LEFT   -> KeyEvent.KEY_LOCATION_LEFT;
                case LOC_RIGHT  -> KeyEvent.KEY_LOCATION_RIGHT;
                case LOC_NUMPAD -> KeyEvent.KEY_LOCATION_NUMPAD;
                default -> KeyEvent.KEY_LOCATION_STANDARD;
            };
            v = v.substring(0, colon).trim();
        }

        String token = v.toUpperCase(Locale.ROOT);
        if (token.startsWith("VK_")) token = token.substring(3);
        Integer code = NAME_TO_CODE.get(token);
        if (code == null) {
            try {
                code = Integer.valueOf(v);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (code == KeyEvent.VK_UNDEFINED || code == KeyEvent.VK_ESCAPE) return null;
        return new KeyBinding(code, location);
    }

    /**
     * Symbolic name of the {@code KeyEvent.VK_*} constant (without prefix), or {@code null} if
     * the key code matches no known constant. Used to look up the key's localised name
     * ({@code key.name.<NAME>}).
     */
    public String keyName() {
        return CODE_TO_NAME.get(keyCode);
    }

    /**
     * The key's name according to AWT, with no indication of the location (e.g. {@code "W"},
     * {@code "Up"}, {@code "Ctrl"}). It is translated into the JVM's language, not the one
     * chosen for the interface: to be used as a fallback when the {@code key.name.<NAME>} entry
     * is missing from the message bundle.
     */
    public String keyText() {
        return KeyEvent.getKeyText(keyCode);
    }

    /**
     * i18n key with which to qualify {@link #keyText()} when the key has a specific location
     * (left/right/keypad), or {@code null} for the standard location.
     */
    public String locationMessageKey() {
        return switch (keyLocation) {
            case KeyEvent.KEY_LOCATION_LEFT   -> "key.location.left";
            case KeyEvent.KEY_LOCATION_RIGHT  -> "key.location.right";
            case KeyEvent.KEY_LOCATION_NUMPAD -> "key.location.numpad";
            default -> null;
        };
    }

    /** Unknown and standard locations are equivalent: both are normalised to standard. */
    private static int normalize(int location) {
        return switch (location) {
            case KeyEvent.KEY_LOCATION_LEFT, KeyEvent.KEY_LOCATION_RIGHT,
                 KeyEvent.KEY_LOCATION_NUMPAD -> location;
            default -> KeyEvent.KEY_LOCATION_STANDARD;
        };
    }
}
