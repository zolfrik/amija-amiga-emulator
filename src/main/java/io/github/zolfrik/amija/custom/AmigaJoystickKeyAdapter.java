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

import io.github.zolfrik.amija.input.GamePort;
import io.github.zolfrik.amija.input.JoystickAction;
import io.github.zolfrik.amija.input.JoystickKeyMap;

import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;

/**
 * Emula un joystick Amiga via tastiera host, indipendentemente per le due game port.
 *
 * <p>Ogni porta ("Port 0" e "Port 1" del popup F12) ha la propria {@link JoystickKeyMap}:
 * di default frecce + Ctrl sinistro per Port 1 e W/A/S/D + Ctrl destro per Port 0, ma i
 * tasti sono configurabili da {@code amiga.properties} e dal popup F12. Le porte non
 * pilotate dalla tastiera restano semplicemente sconfigurate ({@code port == null}).
 *
 * <p>I tasti mappati vengono consumati e NON inoltrati alla tastiera Amiga: questo
 * adapter va quindi registrato PRIMA di {@code AmigaKeyAdapter} sul componente di input.
 */
public class AmigaJoystickKeyAdapter extends KeyAdapter {

    /** Numero di game port gestite: indice 0 = "Port 0", indice 1 = "Port 1". */
    private static final int PORTS = 2;

    /** Stato di una porta: destinazione, tasti associati e direzioni premute. */
    private static final class PortState {
        volatile GamePort port;
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
     * Configura (anche a caldo, dal popup F12) il joystick da tastiera di una porta.
     *
     * @param portIndex 0 = "Port 0", 1 = "Port 1"
     * @param port      game port pilotata, o {@code null} per disattivare la porta
     * @param keyMap    tasti associati; {@code null} ripristina i default della porta
     */
    public void configure(int portIndex, GamePort port, JoystickKeyMap keyMap) {
        if (portIndex < 0 || portIndex >= PORTS) return;
        PortState s = states[portIndex];

        // La porta precedente viene azzerata: i tasti eventualmente premuti al momento
        // della riconfigurazione non devono restare "incantati" sulla vecchia porta.
        release(s);

        s.port   = port;
        s.keyMap = (keyMap != null) ? keyMap.copy() : JoystickKeyMap.defaults(portIndex);
        if (port != null) {
            port.setJoystickAxes(0, 0);
            port.pressJoystickFire(false);
        }
    }

    /** {@code true} se almeno una porta è pilotata dalla tastiera. */
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
     * Applica il tasto a tutte le porte che lo hanno mappato. Ritorna {@code true} se
     * almeno una lo ha usato (e l'evento va quindi consumato).
     */
    private boolean handleKey(KeyEvent e, boolean pressed) {
        boolean handled = false;
        for (PortState s : states) {
            GamePort p = s.port;
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

    /** Rilascia direzioni e fuoco sulla porta attualmente configurata, se presente. */
    private void release(PortState s) {
        s.up = s.down = s.left = s.right = false;
        GamePort p = s.port;
        if (p != null) {
            p.setJoystickAxes(0, 0);
            p.pressJoystickFire(false);
        }
    }
}
