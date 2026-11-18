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

import io.github.zolfrik.amija.custom.serial.SerialCable;

import java.util.function.LongSupplier;

/**
 * A null modem cable to another machine: TXD crossed to RXD, DTR to the other's DSR and CD,
 * RTS to the other's CTS. This is the end plugged into one machine; the {@link LinkCore}
 * behind it decides when what the other machine did takes effect here.
 */
public final class NullModemCable implements SerialCable {

    private final LinkCore core;
    private final LongSupplier clock;
    private boolean lastDtr;
    private boolean lastRts;
    private boolean lastBreak;

    /** @param clock the master cycle counter of the machine this end is plugged into */
    public NullModemCable(LinkCore core, LongSupplier clock) {
        this.core = core;
        this.clock = clock;
    }

    public LinkCore core() { return core; }

    @Override public boolean rxd() { return core.rxd(clock.getAsLong()); }
    @Override public long nextSpace(long from) { return core.nextSpace(from); }
    @Override public boolean dsr() { return core.remoteDtr(clock.getAsLong()); }
    @Override public boolean cd()  { return core.remoteDtr(clock.getAsLong()); }
    @Override public boolean cts() { return core.remoteRts(clock.getAsLong()); }

    @Override
    public void transmit(long start, int firstBitCycles, int bitCycles, int pattern, int bits) {
        core.emit(LineEvent.tx(core.clock().lineTime(start), firstBitCycles, bitCycles, pattern, bits));
    }

    @Override
    public void transmitBreak(boolean on) {
        if (on == lastBreak) return;
        lastBreak = on;
        core.emit(LineEvent.brk(core.clock().lineTime(clock.getAsLong()), on));
    }

    @Override
    public void control(boolean dtr, boolean rts) {
        if (dtr == lastDtr && rts == lastRts) return;
        lastDtr = dtr;
        lastRts = rts;
        core.emit(LineEvent.control(core.clock().lineTime(clock.getAsLong()), dtr, rts));
    }
}
