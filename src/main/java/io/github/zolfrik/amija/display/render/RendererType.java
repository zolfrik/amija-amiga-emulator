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
 * Backend di rendering del display.
 *
 * <ul>
 *   <li>{@link #SOFTWARE}: composizione Java2D/Swing (comportamento storico, default).</li>
 *   <li>{@link #OPENGL}: rendering OpenGL via LWJGL, ospitato nella finestra Swing
 *       tramite {@code AWTGLCanvas}. È il backend predisposto per gli shader RetroArch
 *       (implementazione dello shader in una sessione dedicata).</li>
 * </ul>
 *
 * <p>Il valore serializzato in {@code amiga.properties} è {@link #configValue()}; il
 * parsing tollera valori sconosciuti ripiegando su {@link #SOFTWARE}, sul modello di
 * {@code AmigaWindowMode}.
 */
public enum RendererType {
    SOFTWARE("software"),
    OPENGL("opengl");

    private final String configValue;

    RendererType(String configValue) {
        this.configValue = configValue;
    }

    /** Valore usato in {@code amiga.properties}. */
    public String configValue() {
        return configValue;
    }

    /** Converte il valore di configurazione nel tipo, con fallback su {@link #SOFTWARE}. */
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
