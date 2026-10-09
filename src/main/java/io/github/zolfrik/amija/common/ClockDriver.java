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

package io.github.zolfrik.amija.common;

/**
 * How the machine advances: the clocked processor is ticked edge by edge with the chipset,
 * an event-driven one runs whole instructions and pulls the chipset forward. The
 * {@link MasterClock} only asks for a batch of system clocks and paces it to real time.
 */
@FunctionalInterface
public interface ClockDriver {

    /**
     * Runs the machine for about {@code systemClocks} clocks of the 7 MHz system clock and
     * returns how many it really ran (an event-driven processor stops at an instruction
     * boundary, so a batch can be slightly longer).
     */
    long run(int systemClocks);
}
