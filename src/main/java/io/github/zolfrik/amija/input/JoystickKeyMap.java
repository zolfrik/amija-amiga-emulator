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
import java.util.EnumMap;

/**
 * Mapping host key -&gt; function of the keyboard-emulated joystick, for a single game port.
 * Each port has its own map, so the two keyboard joysticks can coexist on the same keyboard.
 *
 * <p>Emulator defaults:
 * <ul>
 *   <li><b>Port 0</b>: W / S / D / A + right Ctrl;</li>
 *   <li><b>Port 1</b>: arrow keys + left Ctrl.</li>
 * </ul>
 *
 * <p>The values can be configured from {@code amiga.properties}
 * ({@code input.portN.joystick.up|down|right|left|fire}) and from the F12 popup.
 */
public final class JoystickKeyMap {

    private final EnumMap<JoystickAction, KeyBinding> bindings =
            new EnumMap<>(JoystickAction.class);

    private JoystickKeyMap() { }

    /** The emulator's default map for the given port (0 or 1). */
    public static JoystickKeyMap defaults(int port) {
        JoystickKeyMap m = new JoystickKeyMap();
        int std = KeyEvent.KEY_LOCATION_STANDARD;
        if (port == 0) {
            m.bindings.put(JoystickAction.UP,    new KeyBinding(KeyEvent.VK_W, std));
            m.bindings.put(JoystickAction.DOWN,  new KeyBinding(KeyEvent.VK_S, std));
            m.bindings.put(JoystickAction.RIGHT, new KeyBinding(KeyEvent.VK_D, std));
            m.bindings.put(JoystickAction.LEFT,  new KeyBinding(KeyEvent.VK_A, std));
            m.bindings.put(JoystickAction.FIRE,
                    new KeyBinding(KeyEvent.VK_CONTROL, KeyEvent.KEY_LOCATION_RIGHT));
        } else {
            m.bindings.put(JoystickAction.UP,    new KeyBinding(KeyEvent.VK_UP, std));
            m.bindings.put(JoystickAction.DOWN,  new KeyBinding(KeyEvent.VK_DOWN, std));
            m.bindings.put(JoystickAction.RIGHT, new KeyBinding(KeyEvent.VK_RIGHT, std));
            m.bindings.put(JoystickAction.LEFT,  new KeyBinding(KeyEvent.VK_LEFT, std));
            m.bindings.put(JoystickAction.FIRE,
                    new KeyBinding(KeyEvent.VK_CONTROL, KeyEvent.KEY_LOCATION_LEFT));
        }
        return m;
    }

    /** Key assigned to the function (never {@code null}: the maps are always complete). */
    public KeyBinding get(JoystickAction action) {
        return bindings.get(action);
    }

    /** Assigns a key to the function, replacing the previous one. */
    public void set(JoystickAction action, KeyBinding binding) {
        if (action == null || binding == null) return;
        bindings.put(action, binding);
    }

    /** Function driven by the keyboard event, or {@code null} if the key is not mapped. */
    public JoystickAction actionFor(KeyEvent e) {
        for (var entry : bindings.entrySet()) {
            if (entry.getValue().matches(e)) return entry.getKey();
        }
        return null;
    }

    /** Function the key is already assigned to, or {@code null} if free. */
    public JoystickAction actionFor(KeyBinding binding) {
        if (binding == null) return null;
        for (var entry : bindings.entrySet()) {
            if (entry.getValue().equals(binding)) return entry.getKey();
        }
        return null;
    }

    /** Independent copy (maps are passed by value between config, popup and adapter). */
    public JoystickKeyMap copy() {
        JoystickKeyMap m = new JoystickKeyMap();
        m.bindings.putAll(bindings);
        return m;
    }

    /** Replaces every assignment with those of {@code other}. */
    public void copyFrom(JoystickKeyMap other) {
        if (other == null) return;
        bindings.clear();
        bindings.putAll(other.bindings);
    }
}
