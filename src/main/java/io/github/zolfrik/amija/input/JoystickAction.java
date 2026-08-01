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

/**
 * Funzione del joystick emulato via tastiera: le quattro direzioni più il pulsante
 * di fuoco. A ciascuna è associato un tasto host, configurabile per porta (vedi
 * {@link JoystickKeyMap}).
 *
 * <p>L'ordine di dichiarazione è quello con cui le voci compaiono nel popup di
 * configurazione dei tasti.
 */
public enum JoystickAction {

    UP("up", "joykeys.action.up"),
    DOWN("down", "joykeys.action.down"),
    RIGHT("right", "joykeys.action.right"),
    LEFT("left", "joykeys.action.left"),
    FIRE("fire", "joykeys.action.fire");

    private final String configKey;
    private final String messageKey;

    JoystickAction(String configKey, String messageKey) {
        this.configKey  = configKey;
        this.messageKey = messageKey;
    }

    /**
     * Suffisso della chiave in {@code amiga.properties}
     * ({@code input.portN.joystick.<configKey>}).
     */
    public String configKey() { return configKey; }

    /** Chiave i18n dell'etichetta mostrata nel popup dei tasti joystick. */
    public String messageKey() { return messageKey; }
}
