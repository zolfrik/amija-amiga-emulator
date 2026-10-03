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

package io.github.zolfrik.amija.custom;

import io.github.zolfrik.amija.input.GamePortControls;
import io.github.zolfrik.amija.input.JoystickAction;
import io.github.zolfrik.amija.input.JoystickKeyMap;

import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;

/**
 * Emulates an Amiga joystick through the host keyboard, independently for the two game
 * ports.
 *
 * <p>Each port ("Port 0" and "Port 1" of the F12 popup) has its own {@link JoystickKeyMap}: by
 * default arrows + left Ctrl for Port 1 and W/A/S/D + right Ctrl for Port 0, but the keys can
 * be configured from {@code amiga.properties} and from the F12 popup. Ports not driven by the
 * keyboard simply stay unconfigured ({@code port == null}).
 *
 * <p>The mapped keys are consumed and NOT forwarded to the Amiga keyboard: this adapter must
 * therefore be registered BEFORE {@code AmigaKeyAdapter} on the input component.
 */
public class AmigaJoystickKeyAdapter extends KeyAdapter {

    /** Number of game ports handled: index 0 = "Port 0", index 1 = "Port 1". */
    private static final int PORTS = 2;

    /** State of a port: target, bound keys and pressed directions. */
    private static final class PortState {
        volatile GamePortControls port;
        volatile JoystickKeyMap keyMap;
        boolean up, down, left, right;
    }

    private final PortState[] states = new PortState[PORTS];

    public AmigaJoystickKeyAdapter() {
        for (int i = 0; i < PORTS; i++) {
            states[i] = new PortState();
            states[i].keyMap = JoystickKeyMap.defaults(i);
        }
    }

    /**
     * Configures (live too, from the F12 popup) the keyboard joystick of a port.
     *
     * @param portIndex 0 = "Port 0", 1 = "Port 1"
     * @param port      game port driven, or {@code null} to disable the port
     * @param keyMap    assigned keys; {@code null} restores the port's defaults
     */
    public void configure(int portIndex, GamePortControls port, JoystickKeyMap keyMap) {
        if (portIndex < 0 || portIndex >= PORTS) return;
        PortState s = states[portIndex];

        // The previous port is reset: keys held at the moment of the reconfiguration must not
        // stay stuck on the old port.
        release(s);

        s.port   = port;
        s.keyMap = (keyMap != null) ? keyMap.copy() : JoystickKeyMap.defaults(portIndex);
        if (port != null) {
            port.setJoystickAxes(0, 0);
            port.pressJoystickFire(false);
        }
    }

    /** {@code true} if at least one port is driven by the keyboard. */
    public boolean isEnabled() {
        for (PortState s : states) {
            if (s.port != null) return true;
        }
        return false;
    }

    @Override
    public void keyPressed(KeyEvent e) {
        if (e.isConsumed()) return;
        if (handleKey(e, true)) e.consume();
    }

    @Override
    public void keyReleased(KeyEvent e) {
        if (handleKey(e, false)) e.consume();
    }

    /**
     * Applies the key to every port that has it mapped. Returns {@code true} if at least one
     * used it (and the event must therefore be consumed).
     */
    private boolean handleKey(KeyEvent e, boolean pressed) {
        boolean handled = false;
        for (PortState s : states) {
            GamePortControls p = s.port;
            if (p == null) continue;
            JoystickAction action = s.keyMap.actionFor(e);
            if (action == null) continue;
            handled = true;
            if (action == JoystickAction.FIRE) {
                p.pressJoystickFire(pressed);
            } else {
                switch (action) {
                    case UP    -> s.up    = pressed;
                    case DOWN  -> s.down  = pressed;
                    case LEFT  -> s.left  = pressed;
                    case RIGHT -> s.right = pressed;
                    default -> { }
                }
                p.setJoystickAxes(s.right ? 1 : s.left ? -1 : 0,
                                  s.down  ? 1 : s.up   ? -1 : 0);
            }
        }
        return handled;
    }

    /** Releases directions and fire on the currently configured port, if any. */
    private void release(PortState s) {
        s.up = s.down = s.left = s.right = false;
        GamePortControls p = s.port;
        if (p != null) {
            p.setJoystickAxes(0, 0);
            p.pressJoystickFire(false);
        }
    }
}
