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

package io.github.zolfrik.amija.display.render;

/**
 * Rendering backend of the display.
 *
 * <ul>
 *   <li>{@link #SOFTWARE}: Java2D/Swing composition (default).</li>
 *   <li>{@link #OPENGL}: OpenGL rendering through LWJGL, hosted in the Swing window through
 *       {@code AWTGLCanvas}. The backend that runs the RetroArch shaders.</li>
 * </ul>
 *
 * <p>The value serialised in {@code amiga.properties} is {@link #configValue()}; parsing
 * tolerates unknown values by falling back on {@link #SOFTWARE}, as {@code AmigaWindowMode}
 * does.
 */
public enum RendererType {
    SOFTWARE("software"),
    OPENGL("opengl");

    private final String configValue;

    RendererType(String configValue) {
        this.configValue = configValue;
    }

    /** Value used in {@code amiga.properties}. */
    public String configValue() {
        return configValue;
    }

    /** Converts the configuration value into the type, falling back on {@link #SOFTWARE}. */
    public static RendererType fromConfigValue(String value) {
        if (value != null) {
            String v = value.trim();
            for (RendererType t : values()) {
                if (t.configValue.equalsIgnoreCase(v) || t.name().equalsIgnoreCase(v)) {
                    return t;
                }
            }
        }
        return SOFTWARE;
    }
}
