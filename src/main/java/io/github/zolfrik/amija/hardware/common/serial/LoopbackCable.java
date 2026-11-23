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

package io.github.zolfrik.amija.hardware.common.serial;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * A loopback plug: TXD wired to RXD, DTR to DSR and CD, RTS to CTS.
 *
 * <p>The plug has no state of its own. What comes back on RXD is what the transmitter put on
 * TXD three colour clocks earlier, the delay of the line driver and receiver, and the
 * handshake inputs are the outputs of the moment.
 */
public final class LoopbackCable implements SerialCable {

    /** TXD to RXD through the line driver and the line receiver: three colour clocks. */
    public static final int DELAY_CYCLES = 6;

    /** The TXD side of the machine: its level at a past instant and its next low level. */
    public interface Transmitter {
        boolean txdLevel(long time);
        long nextTxdSpace(long from);
    }

    private final LongSupplier clock;
    private final Transmitter transmitter;
    private final BooleanSupplier dtr;
    private final BooleanSupplier rts;

    public LoopbackCable(LongSupplier clock, Transmitter transmitter, BooleanSupplier dtr, BooleanSupplier rts) {
        this.clock = clock;
        this.transmitter = transmitter;
        this.dtr = dtr;
        this.rts = rts;
    }

    @Override public boolean rxd() {
        return transmitter.txdLevel(clock.getAsLong() - DELAY_CYCLES);
    }

    @Override public long nextSpace(long from) {
        long t = transmitter.nextTxdSpace(from - DELAY_CYCLES);
        return t == Long.MAX_VALUE ? t : t + DELAY_CYCLES;
    }

    @Override public boolean dsr() { return dtr.getAsBoolean(); }
    @Override public boolean cd()  { return dtr.getAsBoolean(); }
    @Override public boolean cts() { return rts.getAsBoolean(); }

    @Override public void transmit(long start, int firstBitCycles, int bitCycles, int pattern, int bits) { }
    @Override public void transmitBreak(boolean on) { }
    @Override public void control(boolean dtr, boolean rts) { }
}
