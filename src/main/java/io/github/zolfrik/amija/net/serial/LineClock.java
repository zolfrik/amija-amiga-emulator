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

package io.github.zolfrik.amija.net.serial;

/**
 * Line time of one machine: nanoseconds of emulated time since its end of the cable was
 * plugged in, computed from its master cycle counter with integer arithmetic. It is the unit
 * the two ends share, so a PAL machine and an NTSC one can be joined.
 */
public final class LineClock {

    private static final long NS = 1_000_000_000L;

    private final long hz;
    private final long origin;

    /**
     * @param hz     master cycles per second of the machine
     * @param origin its master cycle counter at line time zero
     */
    public LineClock(long hz, long origin) {
        this.hz = hz;
        this.origin = origin;
    }

    public long hz() { return hz; }

    /** Line time of a master cycle, rounded down. */
    public long lineTime(long cycle) {
        return cyclesToNs(cycle - origin, hz);
    }

    /** The first master cycle whose line time is not before {@code ns}. */
    public long cycleAt(long ns) {
        if (ns <= 0) return origin;
        long c = ns / NS * hz + (ns % NS) * hz / NS;
        while (cyclesToNs(c, hz) < ns) c++;
        return origin + c;
    }

    /** Nanoseconds that {@code cycles} master cycles of a machine at {@code hz} last, rounded down. */
    public static long cyclesToNs(long cycles, long hz) {
        return cycles / hz * NS + (cycles % hz) * NS / hz;
    }
}
