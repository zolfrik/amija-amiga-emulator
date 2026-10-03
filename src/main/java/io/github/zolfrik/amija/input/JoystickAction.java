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
 * Function of the keyboard-emulated joystick: the four directions plus the fire button. Each
 * one has a host key, configurable per port (see {@link JoystickKeyMap}).
 *
 * <p>The declaration order is the one in which the entries appear in the key configuration
 * popup.
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
     * Suffix of the key in {@code amiga.properties}
     * ({@code input.portN.joystick.<configKey>}).
     */
    public String configKey() { return configKey; }

    /** i18n key of the label shown in the joystick keys popup. */
    public String messageKey() { return messageKey; }
}
