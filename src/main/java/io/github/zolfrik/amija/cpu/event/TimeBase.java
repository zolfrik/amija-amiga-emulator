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

package io.github.zolfrik.amija.cpu.event;

/** Units of the event-driven time base (see {@link CpuBus#cycles()}). */
public final class TimeBase {
    private TimeBase() {}

    /** Units in one colour clock. */
    public static final int CCK = 512;
    /** Units in one clock of a processor running at four clocks per colour clock (14 MHz). */
    public static final int CLOCK_14MHZ = CCK / 4;
    /** Units in one clock of a processor running at two clocks per colour clock (7 MHz). */
    public static final int CLOCK_7MHZ = CCK / 2;
}
