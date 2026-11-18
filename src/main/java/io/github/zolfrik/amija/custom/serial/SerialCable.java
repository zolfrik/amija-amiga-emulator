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

package io.github.zolfrik.amija.custom.serial;

/**
 * What is plugged into the serial port, seen from the machine it is plugged into.
 *
 * <p>The wire carries levels, not words. The transmitter describes the bits it is about to
 * put on TXD and the receiver samples RXD with its own bit clock, so a baud rate that
 * differs between the two ends, a break, or a period changed in the middle of a word all
 * come out of the same mechanism.
 *
 * <p>Every time is a master cycle of the machine the cable is plugged into, and the inputs
 * are read at the current one. A machine with nothing plugged in has no cable at all.
 */
public interface SerialCable {

    /** Level of the RXD pin now: true is mark, the idle level and a logical 1. */
    boolean rxd();

    /**
     * First master cycle, not before {@code from}, at which RXD is low, as far as it is
     * known now; {@code Long.MAX_VALUE} when no low level is on its way.
     */
    long nextSpace(long from);

    /** Data Set Ready input, true when asserted. */
    boolean dsr();

    /** Carrier Detect input, true when asserted. */
    boolean cd();

    /** Clear To Send input, true when asserted. */
    boolean cts();

    /**
     * The machine puts bits on TXD: from {@code start} the line carries {@code bits} levels
     * taken from {@code pattern}, bit 0 first, the first lasting {@code firstBitCycles} master
     * cycles and the others {@code bitCycles} each, then goes back to mark. Anything announced
     * earlier for the same instants is replaced.
     */
    void transmit(long start, int firstBitCycles, int bitCycles, int pattern, int bits);

    /** The machine forces TXD low (break) or releases it, from now. */
    void transmitBreak(boolean on);

    /** The machine drives its Data Terminal Ready and Request To Send outputs, from now. */
    void control(boolean dtr, boolean rts);
}
