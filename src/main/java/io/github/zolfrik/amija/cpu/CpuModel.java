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

package io.github.zolfrik.amija.cpu;

import io.github.zolfrik.amija.common.BusRequest;
import io.github.zolfrik.amija.common.BusResponse;
import io.github.zolfrik.amija.cpu.event.EventCpu;
import io.github.zolfrik.amija.cpu.m68k.Motorola68000E;
import io.github.zolfrik.amija.cpu.m68k.Motorola68010E;
import io.github.zolfrik.amija.cpu.m68k.Motorola68020;
import java.util.Locale;

/**
 * Processor model of the emulated machine.
 *
 * <ul>
 *   <li>{@link #M68000}: the original Amiga CPU.</li>
 *   <li>{@link #M68010}: pin-compatible successor. Vector base register, privileged
 *       {@code MOVE SR}, four-word exception frames, loop mode, MOVEC/MOVES/RTD and
 *       different timing for a number of instructions.</li>
 *   <li>{@link #M68020}: the 68EC020 of the Amiga 1200, at four clocks per colour clock.
 *       Event-driven: it runs whole instructions and pulls the chipset forward through its
 *       bus, instead of being clocked edge by edge.</li>
 *   <li>{@link #M68000E}, {@link #M68010E}: the 68000 and the 68010, event-driven like the
 *       68020, with the same timing as the clocked ones.</li>
 * </ul>
 *
 * <p>Read from {@code amiga.properties} with the key {@code cpu.model}. A change takes
 * effect only after a cold restart. The popup offers the event-driven models only
 * ({@link #selectable()}); the clocked ones are still accepted from the file.
 */
public enum CpuModel {
    M68000("68000", false),
    M68010("68010", false),
    M68020("68020", true),
    M68000E("68000E", true),
    M68010E("68010E", true);

    private final String label;
    private final boolean eventDriven;

    CpuModel(String label, boolean eventDriven) {
        this.label = label;
        this.eventDriven = eventDriven;
    }

    /**
     * True if this processor is event-driven ({@link #createEventCpu()}), false if it is
     * clocked edge by edge ({@link #create}).
     */
    public boolean isEventDriven() {
        return eventDriven;
    }

    /** The label stored in the configuration. */
    public String label() {
        return label;
    }

    /** The part number shown to the user: the label without the event-driven suffix. */
    public String partNumber() {
        return this == M68000E || this == M68010E ? label.substring(0, label.length() - 1) : label;
    }

    /** The processors offered to the user: the event-driven ones. */
    public static CpuModel[] selectable() {
        return new CpuModel[] { M68000E, M68010E, M68020 };
    }

    /** The event-driven processor with the same part number: this one if it already is. */
    public CpuModel toEventDriven() {
        switch (this) {
            case M68000: return M68000E;
            case M68010: return M68010E;
            default: return this;
        }
    }

    @Override
    public String toString() {
        return label;
    }

    /** Builds a clocked processor of this model on the given pins. */
    public AbstractMotorola create(BusRequest busReq, BusResponse busResp) {
        if (eventDriven) throw new IllegalStateException(label + " is event-driven");
        return this == M68010 ? new Motorola68010(busReq, busResp) : new Motorola68000(busReq, busResp);
    }

    /** Builds an event-driven processor of this model. */
    public EventCpu createEventCpu() {
        if (!eventDriven) throw new IllegalStateException(label + " is clocked");
        switch (this) {
            case M68000E: return new Motorola68000E();
            case M68010E: return new Motorola68010E();
            default: return new Motorola68020();
        }
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
