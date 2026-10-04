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

package io.github.zolfrik.amija.cpu;

import io.github.zolfrik.amija.common.BusRequest;
import io.github.zolfrik.amija.common.BusResponse;
import java.util.Locale;

/**
 * Processor model of the emulated machine.
 *
 * <ul>
 *   <li>{@link #M68000}: the stock Amiga 500 CPU.</li>
 *   <li>{@link #M68010}: pin-compatible successor. Vector base register, privileged
 *       {@code MOVE SR}, four-word exception frames, loop mode, MOVEC/MOVES/RTD and
 *       different timing for a number of instructions.</li>
 * </ul>
 *
 * <p>Read from {@code amiga.properties} with the key {@code cpu.model}. A change takes
 * effect only after a cold restart.
 */
public enum CpuModel {
    M68000("68000"),
    M68010("68010");

    private final String label;

    CpuModel(String label) {
        this.label = label;
    }

    /** The part number, as shown to the user and stored in the configuration. */
    public String label() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }

    /** Builds a processor of this model on the given bus. */
    public AbstractMotorola create(BusRequest busReq, BusResponse busResp) {
        return this == M68010 ? new Motorola68010(busReq, busResp) : new Motorola68000(busReq, busResp);
    }

    /**
     * Parses a configuration value: the part number ({@code 68010}) or the constant name
     * ({@code M68010}), case-insensitive. Returns {@code def} if the value is missing or
     * unknown.
     */
    public static CpuModel parse(String value, CpuModel def) {
        if (value == null) return def;
        String v = value.trim().toUpperCase(Locale.ROOT);
        if (v.isEmpty()) return def;
        for (CpuModel m : values()) {
            if (m.label.equals(v) || m.name().equals(v)) return m;
        }
        return def;
    }
}
