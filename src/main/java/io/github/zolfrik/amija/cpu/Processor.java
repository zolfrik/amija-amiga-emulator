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

/**
 * What the machine knows of any processor, whatever drives it: the clocked 68000-family
 * core ({@link AbstractMotorola}, advanced one clock edge at a time by Agnus) or an
 * event-driven core ({@link io.github.zolfrik.amija.cpu.event.EventCpu}, which runs whole
 * instructions and pulls the chipset forward through its bus).
 */
public interface Processor {

    /** The model of this processor. */
    CpuModel model();

    /** External reset: the processor restarts from the reset vectors. */
    void reset();

    /**
     * Holds /RESET asserted for {@code cycles} more processor clocks of the 7 MHz system
     * clock; renewed while a reset source (the keyboard) keeps the line low.
     */
    void assertReset(int cycles);

    /** Address of the instruction being executed. */
    int getOpcodeAddress();

    /**
     * Last word the instruction prefetch took from the bus: what an access to an address
     * where nothing answers reads, when the code runs from RAM.
     */
    int prefetchWord();

    /** True between two instructions, with no bus activity of the processor in flight. */
    boolean isAtInstructionBoundary();
}
