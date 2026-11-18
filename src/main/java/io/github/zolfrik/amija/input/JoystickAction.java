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

/**
 * Function of the keyboard-emulated joystick: the four directions plus the fire button, and
 * the CD32 pad's other six buttons. Each one has a host key, configurable per port (see
 * {@link JoystickKeyMap}).
 *
 * <p>The CD32 functions act only while the port holds a CD32 pad: on any other device their
 * keys are left to the Amiga keyboard.
 *
 * <p>The declaration order is the one in which the entries appear in the key configuration
 * popup.
 */
public enum JoystickAction {

    UP("up", "joykeys.action.up"),
    DOWN("down", "joykeys.action.down"),
    RIGHT("right", "joykeys.action.right"),
    LEFT("left", "joykeys.action.left"),
    FIRE("fire", "joykeys.action.fire"),
    BLUE("blue", "joykeys.action.blue", -2),
    GREEN("green", "joykeys.action.green", GamePort.CD32_GREEN),
    YELLOW("yellow", "joykeys.action.yellow", GamePort.CD32_YELLOW),
    REWIND("rewind", "joykeys.action.rewind", GamePort.CD32_REWIND),
    FORWARD("forward", "joykeys.action.forward", GamePort.CD32_FORWARD),
    PLAY("play", "joykeys.action.play", GamePort.CD32_PLAY);

    /** {@link #cd32Button()} of BLUE: the pad's blue button is the joystick's second one. */
    public static final int CD32_BUTTON2 = -2;

    private final String configKey;
    private final String messageKey;
    private final int cd32Button;

    JoystickAction(String configKey, String messageKey) {
        this(configKey, messageKey, -1);
    }

    JoystickAction(String configKey, String messageKey, int cd32Button) {
        this.configKey  = configKey;
        this.messageKey = messageKey;
        this.cd32Button = cd32Button;
    }

    /** True for the CD32 pad's buttons other than fire (red). */
    public boolean isCd32() { return cd32Button != -1; }

    /**
     * The CD32 button driven: {@link #CD32_BUTTON2} for blue, otherwise one of
     * {@code GamePort.CD32_*}; -1 for the joystick's own functions.
     */
    public int cd32Button() { return cd32Button; }

    /**
     * Suffix of the key in {@code amiga.properties}
     * ({@code input.portN.joystick.<configKey>}).
     */
    public String configKey() { return configKey; }

    /** i18n key of the label shown in the joystick keys popup. */
    public String messageKey() { return messageKey; }
}
