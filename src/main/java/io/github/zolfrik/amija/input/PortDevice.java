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
 * Periferica host collegata a una game port dell'Amiga, come scelto dall'utente
 * (property {@code input.port0.device} / {@code input.port1.device} e tendine
 * "Port 0" / "Port 1" del popup F12).
 *
 * <p>A differenza di {@link GamePortDevice} (che descrive cosa la porta
 * <i>riporta</i> a livello hardware: mouse, joystick o niente), questo enum
 * descrive quale <i>sorgente di input host</i> pilota la porta:
 * <ul>
 *   <li>{@link #MOUSE} – il mouse host (via {@code AmigaMouseAdapter});</li>
 *   <li>{@link #JOYSTICK_REAL} – un joystick/gamepad fisico letto via GLFW;</li>
 *   <li>{@link #JOYSTICK_KEYBOARD} – joystick emulato su frecce + Ctrl sinistro;</li>
 *   <li>{@link #NONE} – nessuna periferica (porta vuota).</li>
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

    /** Valore usato in {@code amiga.properties}. */
    public String configValue() { return configValue; }

    /** Chiave i18n dell'etichetta mostrata nella tendina del popup F12. */
    public String messageKey() { return messageKey; }

    /**
     * Converte il valore di {@code amiga.properties} nel corrispondente enum.
     * Valori non riconosciuti (o {@code null}) ripiegano su {@code def}.
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

    /** Corrispondente periferica hardware riportata dalla porta. */
    public GamePortDevice hardwareDevice() {
        return switch (this) {
            case MOUSE -> GamePortDevice.MOUSE;
            case JOYSTICK_REAL, JOYSTICK_KEYBOARD -> GamePortDevice.JOYSTICK;
            case NONE -> GamePortDevice.NONE;
        };
    }
}
