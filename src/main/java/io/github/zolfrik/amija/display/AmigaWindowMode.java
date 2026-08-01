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

package io.github.zolfrik.amija.display;

/**
 * Modalita' della finestra principale dell'emulatore.
 */
public enum AmigaWindowMode {
    WINDOWED("windowed"),
    FULLSCREEN("fullscreen"),
    BORDERLESS_WINDOWED("borderless-windowed");

    private final String configValue;

    AmigaWindowMode(String configValue) {
        this.configValue = configValue;
    }

    public String configValue() {
        return configValue;
    }

    public static AmigaWindowMode fromConfigValue(String value) {
        if (value != null) {
            String v = value.trim();
            for (AmigaWindowMode mode : values()) {
                if (mode.configValue.equalsIgnoreCase(v) || mode.name().equalsIgnoreCase(v)) {
                    return mode;
                }
            }
        }
        return WINDOWED;
    }
}
