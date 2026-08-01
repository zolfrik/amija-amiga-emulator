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

package io.github.zolfrik.amija.input;

import java.awt.event.KeyEvent;
import java.util.EnumMap;

/**
 * Associazione tasto host &rarr; funzione del joystick emulato via tastiera, per una
 * singola game port. Ogni porta ha la propria mappa, così i due joystick da tastiera
 * possono convivere sulla stessa tastiera.
 *
 * <p>Default dell'emulatore:
 * <ul>
 *   <li><b>Port 0</b>: W / S / D / A + Ctrl destro;</li>
 *   <li><b>Port 1</b>: frecce direzionali + Ctrl sinistro.</li>
 * </ul>
 *
 * <p>I valori sono configurabili da {@code amiga.properties}
 * ({@code input.portN.joystick.up|down|right|left|fire}) e dal popup F12.
 */
public final class JoystickKeyMap {

    private final EnumMap<JoystickAction, KeyBinding> bindings =
            new EnumMap<>(JoystickAction.class);

    private JoystickKeyMap() { }

    /** Mappa di default dell'emulatore per la porta indicata (0 o 1). */
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

    /** Tasto associato alla funzione (mai {@code null}: le mappe sono sempre complete). */
    public KeyBinding get(JoystickAction action) {
        return bindings.get(action);
    }

    /** Associa un tasto alla funzione, sostituendo il precedente. */
    public void set(JoystickAction action, KeyBinding binding) {
        if (action == null || binding == null) return;
        bindings.put(action, binding);
    }

    /** Funzione pilotata dall'evento di tastiera, o {@code null} se il tasto non è mappato. */
    public JoystickAction actionFor(KeyEvent e) {
        for (var entry : bindings.entrySet()) {
            if (entry.getValue().matches(e)) return entry.getKey();
        }
        return null;
    }

    /** Funzione a cui è già associato il tasto, o {@code null} se libero. */
    public JoystickAction actionFor(KeyBinding binding) {
        if (binding == null) return null;
        for (var entry : bindings.entrySet()) {
            if (entry.getValue().equals(binding)) return entry.getKey();
        }
        return null;
    }

    /** Copia indipendente (le mappe si passano per valore fra config, popup e adapter). */
    public JoystickKeyMap copy() {
        JoystickKeyMap m = new JoystickKeyMap();
        m.bindings.putAll(bindings);
        return m;
    }

    /** Sostituisce tutte le associazioni con quelle di {@code other}. */
    public void copyFrom(JoystickKeyMap other) {
        if (other == null) return;
        bindings.clear();
        bindings.putAll(other.bindings);
    }
}
