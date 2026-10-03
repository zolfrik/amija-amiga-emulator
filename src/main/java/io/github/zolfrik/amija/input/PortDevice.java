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
 * Host device plugged into an Amiga game port, as chosen by the user (properties
 * {@code input.port0.device} / {@code input.port1.device} and the "Port 0" / "Port 1"
 * drop-downs of the F12 popup).
 *
 * <p>Unlike {@link GamePortDevice} (which describes what the port <i>reports</i> at hardware
 * level: mouse, joystick or nothing), this enum describes which <i>host input source</i> drives
 * the port:
 * <ul>
 *   <li>{@link #MOUSE}: the host mouse (through {@code AmigaMouseAdapter});</li>
 *   <li>{@link #JOYSTICK_REAL}: a physical joystick/gamepad read through GLFW;</li>
 *   <li>{@link #JOYSTICK_KEYBOARD}: joystick emulated on the keyboard;</li>
 *   <li>{@link #NONE}: no device (empty port).</li>
 * </ul>
 */
public enum PortDevice {

    MOUSE("mouse", "port.device.mouse"),
    JOYSTICK_REAL("joystick-real", "port.device.joystickReal"),
    JOYSTICK_KEYBOARD("joystick-keyboard", "port.device.joystickKeyboard"),
    NONE("none", "port.device.none");

    private final String configValue;
    private final String messageKey;

    PortDevice(String configValue, String messageKey) {
        this.configValue = configValue;
        this.messageKey  = messageKey;
    }

    /** Value used in {@code amiga.properties}. */
    public String configValue() { return configValue; }

    /** i18n key of the label shown in the F12 popup's drop-down. */
    public String messageKey() { return messageKey; }

    /**
     * Converts the value of {@code amiga.properties} into the matching enum. Unrecognised
     * values (or {@code null}) fall back on {@code def}.
     */
    public static PortDevice fromConfigValue(String value, PortDevice def) {
        if (value != null) {
            String v = value.trim();
            for (PortDevice d : values()) {
                if (d.configValue.equalsIgnoreCase(v)) return d;
            }
        }
        return def;
    }

    /** Matching hardware device reported by the port. */
    public GamePortDevice hardwareDevice() {
        return switch (this) {
            case MOUSE -> GamePortDevice.MOUSE;
            case JOYSTICK_REAL, JOYSTICK_KEYBOARD -> GamePortDevice.JOYSTICK;
            case NONE -> GamePortDevice.NONE;
        };
    }
}
