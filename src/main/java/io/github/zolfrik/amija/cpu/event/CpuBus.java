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

package io.github.zolfrik.amija.cpu.event;

/**
 * The system as an event-driven processor sees it.
 *
 * <p>The processor runs whole instructions as straight-line code and calls this bus for every
 * access; the bus brings the rest of the machine up to the time of the access, waits for the
 * slot the access needs and performs it. Between two calls the rest of the machine does not
 * run: time is a counter, not a sequence of clock edges.
 *
 * <p><b>Time.</b> {@link #cycles()} counts in units of 1/{@value TimeBase#CCK} of a colour
 * clock and advances only in whole colour clocks: the part of the processor's time shorter
 * than a colour clock is carried by the bus and handed over with the next whole one. A
 * processor at four clocks per colour clock spends {@value TimeBase#CLOCK_14MHZ} units per
 * clock.
 *
 * <p>Accesses are performed in the order they are called and take the time the bus decides;
 * when they return, {@link #cycles()} is the end of the access. A failed access throws
 * {@link BusFault#INSTANCE}.
 */
public interface CpuBus {

    /** Elapsed time in units, a whole number of colour clocks. */
    long cycles();

    /** The processor spends {@code units} of internal time (no bus activity). */
    void idle(int units);

    /**
     * Elapsed time in units, including the part shorter than a colour clock not yet handed
     * to the chipset.
     */
    default long now() {
        return cycles();
    }

    /** Instruction fetch of an aligned long word (instruction cache line fill). */
    int fetchLong(int address);

    /** Instruction fetch of a word (a processor with a 16-bit bus). */
    default int fetchWord(int address) {
        return readWord(address);
    }

    int readByte(int address);
    int readWord(int address);
    int readLong(int address);

    void writeByte(int address, int value);
    void writeWord(int address, int value);
    void writeLong(int address, int value);

    /** Reads a long word without taking any time (the reset vectors). */
    int readLongUntimed(int address);

    /** The interrupt priority level on the processor's IPL pins, at the current time. */
    int ipl();

    /**
     * Number of changes of the IPL pins so far: lets the processor see a change that came
     * and went between two instruction boundaries.
     */
    int iplChanges();

    /**
     * The IPL pins at time {@code time} (not later than {@link #now()}): the level set by the
     * last change at or before that time.
     */
    default int iplAt(long time) {
        return ipl();
    }

    /**
     * Time of the last change of the IPL pins at or before {@code time}; a time long before
     * any access if there was none.
     */
    default long iplChangeAt(long time) {
        return Long.MIN_VALUE / 4;
    }

    /**
     * The IPL pins a 16-bit processor latches late in its last access, or -1: on the chip
     * bus, the level at the start of the last colour clock it waited for a free slot (-1 if
     * it did not wait); on the synchronous E-clock bus, the level two clocks before the end
     * of the access.
     */
    default int iplLate() {
        return -1;
    }

    /**
     * True if {@code address} is on the chip bus and a DMA channel holds its slot now: the
     * write half of an indivisible read-modify-write cannot wait for the bus and is lost.
     */
    default boolean chipSlotTaken(int address) {
        return false;
    }

    /** Interrupt acknowledge for {@code level}: the vector number. */
    int interruptAcknowledge(int level);

    /** The RESET instruction asserts the system /RESET line. */
    void resetInstruction();

    /**
     * The processor's own /RESET output while it is held in reset (power-on, a reset from
     * outside): the rest of the machine resets on its falling edge, as with a clocked
     * processor.
     */
    void resetLine(boolean asserted);
}
