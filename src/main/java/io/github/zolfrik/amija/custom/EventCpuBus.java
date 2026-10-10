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

package io.github.zolfrik.amija.custom;

import io.github.zolfrik.amija.common.VirtualCRT;
import io.github.zolfrik.amija.cpu.CpuModel;
import io.github.zolfrik.amija.cpu.event.BusFault;
import io.github.zolfrik.amija.cpu.event.CpuBus;
import io.github.zolfrik.amija.cpu.event.TimeBase;

/**
 * The bus of an event-driven processor: the chipset half of every access.
 *
 * <p>The chipset advances one colour clock at a time, and only here: a processor's internal
 * time ({@link #idle}) and its accesses bring it forward. A colour clock is the two master
 * cycles of Agnus (rising and falling edge each, plus the CRT), without the clocked
 * processor's hooks.
 *
 * <p><b>Access timing</b>, by the kind of memory the address reaches:
 * <ul>
 *   <li><b>Chip bus</b> (Chip RAM, the custom chip page, installed Slow RAM): one colour
 *       clock, then the processor waits for a slot no DMA channel takes, transfers in it and
 *       the slot's colour clock completes. A long word is two word slots on the 16-bit bus;
 *       a word that straddles a long word boundary is two byte slots. A 68020 on Alice reaches
 *       Chip RAM through a 32-bit path: a long word aligned to four bytes is one slot.</li>
 *   <li><b>CIA</b>: synchronised to the E clock: the wait to its sync phase and the four
 *       half colour clocks of the cycle start, the transfer, six more to its end.</li>
 *   <li><b>Fast</b> (ROM, Fast RAM, expansion space): no chipset involvement. A 68020
 *       spends three processor clocks, six for a long word not aligned to four bytes; a
 *       68000 or 68010 a bus cycle of four clocks.</li>
 * </ul>
 *
 * <p>Time below one colour clock is carried in {@link #extra} and handed over with the next
 * whole one, so {@link #cycles()} only moves in colour clocks. A 16-bit processor (68000,
 * 68010) starts a chip bus access on a colour clock boundary: the part of a colour clock it
 * still carries is rounded up first.
 *
 * <p>For a 16-bit processor the bus also keeps the history of the IPL pins, stamped with the
 * master cycle in which the chipset changed them: such a processor samples the pins at fixed
 * points of its instructions and judges a change by its age.
 */
public final class EventCpuBus implements CpuBus {

    private static final byte FAST32 = 0, CHIP16 = 1, CIA = 2, CHIP32 = 3;
    private static final int CCK = TimeBase.CCK;
    private static final int HALF_CCK = CCK / 2;

    private final AbstractAgnus agnus;
    /** A 68000 or 68010: 16-bit bus, bus cycles of four clocks at two clocks per colour clock. */
    private final boolean bus16;
    /** Duration of an access outside the chip bus and the E-clock bus. */
    private final int memCycle;
    private VirtualCRT crt;
    private final byte[] bank = new byte[256];

    /** Elapsed time in units, whole colour clocks. */
    private long cycles;
    /** Processor time not yet handed to the chipset, below one colour clock. */
    private int extra;
    private int iplChanges;
    private int lastIpl;

    public EventCpuBus(AbstractAgnus agnus, CpuModel model) {
        this.agnus = agnus;
        this.bus16 = model != CpuModel.M68020;
        this.memCycle = bus16 ? 4 * TimeBase.CLOCK_7MHZ : 3 * TimeBase.CLOCK_14MHZ;
        final boolean chip32 = !bus16 && agnus.isAGA();
        final int slowPages = agnus.slowRamBytes() >>> 16;
        // The A1200 decoding leaves $A00000-$BEFFFF to nobody: an access there ends without
        // the E clock.
        final boolean gayle = agnus.isAGA();
        for (int p = 0; p < 256; p++) {
            final byte t;
            if (p < 0x20) t = chip32 ? CHIP32 : CHIP16;
            else if (p >= (gayle ? 0xBF : 0xA0) && p < 0xC0) t = CIA;
            else if (p >= 0xC0 && p < 0xC0 + slowPages && p < 0xD8) t = CHIP16;
            else if (p >= 0xD0 && p < 0xE0) t = CHIP16;
            else t = FAST32;
            bank[p] = t;
        }
    }

    /** The CRT that samples the video output at every master cycle. */
    public void setVirtualCRT(VirtualCRT crt) {
        this.crt = crt;
    }

    // ---------------------------------------------------------------------------------------
    // Chipset stepping
    // ---------------------------------------------------------------------------------------

    private void master() {
        rising();
        agnus.chipFallingEdge();
        if (bus16) sampleIpl(cycles + HALF_CCK);
        agnus.chipFallingEdgeEnd();
        if (crt != null) crt.tick();
    }

    /**
     * Agnus is kept one rising edge ahead of the processor: between two bus operations the
     * rising edge of the next colour clock has already run. That edge is where Agnus
     * arbitrates the slot and where the chipset raises what the colour clock starts with
     * (Blitter interrupt, deferred register writes): the processor, looking at the boundary,
     * sees what the chip bus has already decided for the colour clock to come. The transfer
     * itself still falls in that colour clock's first master cycle.
     */
    private boolean ahead;

    private void ensureAhead() {
        if (ahead) return;
        if (!agnus.atCckStart()) master();
        rising();
        ahead = true;
    }

    /**
     * Completes the colour clock whose rising edge has run: the rest of its first master
     * cycle, its second master cycle, and the rising edge of the next one.
     */
    private void finishCck() {
        agnus.chipFallingEdge();
        if (bus16) sampleIpl(cycles);
        agnus.chipFallingEdgeEnd();
        if (crt != null) crt.tick();
        master();
        rising();
        cycles += CCK;
        trackIpl();
    }

    /** One colour clock with no processor access. */
    private void cck() {
        ensureAhead();
        agnus.eventNoRequest();
        finishCck();
    }

    private void trackIpl() {
        final int ipl = agnus.eventIpl();
        if (ipl != lastIpl) {
            lastIpl = ipl;
            iplChanges++;
        }
    }

    /**
     * The clock driver of an event-driven processor on this bus: each batch runs whole
     * instructions until the requested number of system clocks has elapsed.
     */
    public io.github.zolfrik.amija.common.ClockDriver driver(io.github.zolfrik.amija.cpu.event.EventCpu cpu) {
        return systemClocks -> {
            final long start = cycles;
            cpu.runUntil(start + (long) systemClocks * HALF_CCK);
            return (cycles - start) / HALF_CCK;
        };
    }

    /** Runs the chipset alone up to {@code target} (whole colour clocks), e.g. while held in reset. */
    public void runChipsetUntil(long target) {
        while (cycles < target) cck();
    }

    /**
     * One processor transfer on the chip bus: a colour clock, the wait for a free slot, the
     * transfer in the slot.
     */
    private int chipAccess(int address, boolean read, int size, int data) {
        if (bus16 && extra != 0) {
            extra = 0;
            cck();
        }
        iplLate = -1;
        cck();
        for (;;) {
            if (agnus.eventSlotTaken()) {
                agnus.eventDenied();
                iplLate = iplLevel[iplHead];
                finishCck();
                continue;
            }
            agnus.eventGranted();
            agnus.chipFallingEdge();
            if (bus16) sampleIpl(cycles);
            final int v = agnus.eventTransfer(address, read, size, data);
            agnus.chipFallingEdgeEnd();
            if (crt != null) crt.tick();
            master();
            rising();
            cycles += CCK;
            trackIpl();
            if (v < 0) throw BusFault.INSTANCE;
            return v;
        }
    }

    /**
     * A long word over the 32-bit Chip RAM path: one slot, as {@link #chipAccess}, carrying both
     * words.
     */
    private int chipAccessLong(int address, boolean read, int data) {
        iplLate = -1;
        cck();
        for (;;) {
            if (agnus.eventSlotTaken()) {
                agnus.eventDenied();
                iplLate = iplLevel[iplHead];
                finishCck();
                continue;
            }
            agnus.eventGranted();
            agnus.chipFallingEdge();
            final int hi = agnus.eventTransfer(address, read, 2, data >>> 16);
            final int lo = agnus.eventTransfer(address + 2, read, 2, data & 0xFFFF);
            agnus.chipFallingEdgeEnd();
            if (crt != null) crt.tick();
            master();
            rising();
            cycles += CCK;
            trackIpl();
            if (hi < 0 || lo < 0) throw BusFault.INSTANCE;
            return (hi << 16) | (lo & 0xFFFF);
        }
    }

    /** A CIA access, synchronised to the E clock. */
    private int ciaAccess(int address, boolean read, int size, int data) {
        final int phase = bus16 ? (agnus.eClockPhase() + extra / HALF_CCK) % 10 : agnus.eClockPhase();
        final int sync = 2;
        int add = 0;
        if (phase < sync) add = sync - phase;
        else if (phase > sync) add = 10 - phase + sync;
        idle((add + 4) * HALF_CCK);
        final int v = agnus.eventTransfer(address, read, size, data);
        idle(6 * HALF_CCK);
        if (bus16) iplLate = iplAt(now() - 2 * TimeBase.CLOCK_7MHZ);
        if (v < 0) throw BusFault.INSTANCE;
        return v;
    }

    private int fastAccess(int address, boolean read, int size, int data, int units) {
        iplLate = -1;
        final int v = agnus.eventTransfer(address, read, size, data);
        idle(units);
        if (v < 0) throw BusFault.INSTANCE;
        return v;
    }

    // ---------------------------------------------------------------------------------------
    // CpuBus
    // ---------------------------------------------------------------------------------------

    /** Elapsed time including the part not yet handed to the chipset. */
    public long timeUnits() {
        return cycles + extra;
    }

    @Override
    public long cycles() {
        return cycles;
    }

    @Override
    public long now() {
        return cycles + extra;
    }

    @Override
    public int fetchWord(int address) {
        return readWord(address);
    }

    // ---------------------------------------------------------------------------------------
    // IPL history (16-bit processors)
    // ---------------------------------------------------------------------------------------

    private static final int IPL_HISTORY = 16;
    private final long[] iplTime = new long[IPL_HISTORY];
    private final int[] iplLevel = new int[IPL_HISTORY];
    private int iplHead;
    /** See {@link #iplLate()}. */
    private int iplLate = -1;

    {
        java.util.Arrays.fill(iplTime, Long.MIN_VALUE / 4);
    }

    /** Records the level of the IPL pins after a falling edge of the chipset, at {@code time}. */
    private void sampleIpl(long time) {
        final int v = agnus.eventIpl();
        if (v != iplLevel[iplHead]) {
            iplHead = (iplHead + 1) & (IPL_HISTORY - 1);
            iplLevel[iplHead] = v;
            iplTime[iplHead] = time;
        }
    }

    @Override
    public int iplAt(long time) {
        int i = iplHead;
        for (int k = 0; k < IPL_HISTORY - 1 && iplTime[i] > time; k++) i = (i - 1) & (IPL_HISTORY - 1);
        return iplLevel[i];
    }

    @Override
    public long iplChangeAt(long time) {
        int i = iplHead;
        for (int k = 0; k < IPL_HISTORY - 1 && iplTime[i] > time; k++) i = (i - 1) & (IPL_HISTORY - 1);
        return iplTime[i];
    }

    @Override
    public int iplLate() {
        return iplLate;
    }

    @Override
    public boolean chipSlotTaken(int address) {
        final byte t = bank[(address >>> 16) & 0xFF];
        return (t == CHIP16 || t == CHIP32) && agnus.eventSlotTaken();
    }

    @Override
    public void idle(int units) {
        int e = extra + units;
        while (e >= CCK) {
            e -= CCK;
            cck();
        }
        extra = e;
    }

    @Override
    public int fetchLong(int address) {
        address &= 0xFFFFFF;
        switch (bank[address >>> 16]) {
            case CHIP32:
                if ((address & 3) == 0) return chipAccessLong(address, true, 0);
                // fall through: a misaligned long is two word slots
            case CHIP16: {
                final int hi = chipAccess(address, true, 2, 0);
                final int lo = chipAccess(address + 2, true, 2, 0);
                return (hi << 16) | lo;
            }
            case FAST32: {
                final int hi = agnus.eventTransfer(address, true, 2, 0);
                final int lo = agnus.eventTransfer(address + 2, true, 2, 0);
                idle((address & 3) != 0 ? 2 * memCycle : memCycle);
                if (hi < 0 || lo < 0) throw BusFault.INSTANCE;
                return (hi << 16) | lo;
            }
            default: {
                final int hi = agnus.eventTransfer(address, true, 2, 0);
                final int lo = agnus.eventTransfer(address + 2, true, 2, 0);
                if (hi < 0 || lo < 0) throw BusFault.INSTANCE;
                return (hi << 16) | lo;
            }
        }
    }

    @Override
    public int readByte(int address) {
        address &= 0xFFFFFF;
        switch (bank[address >>> 16]) {
            case CHIP16: case CHIP32: return chipAccess(address, true, 1, 0);
            case CIA: return ciaAccess(address, true, 1, 0);
            default: return fastAccess(address, true, 1, 0, memCycle);
        }
    }

    @Override
    public int readWord(int address) {
        address &= 0xFFFFFF;
        switch (bank[address >>> 16]) {
            case CHIP16: case CHIP32:
                if ((address & 3) == 3) {
                    final int hi = chipAccess(address, true, 1, 0);
                    final int lo = chipAccess(address + 1, true, 1, 0);
                    return (hi << 8) | lo;
                }
                return chipAccess(address, true, 2, 0);
            case CIA: return ciaAccess(address, true, 2, 0);
            default: return fastAccess(address, true, 2, 0, (address & 3) == 3 ? 2 * memCycle : memCycle);
        }
    }

    @Override
    public int readLong(int address) {
        address &= 0xFFFFFF;
        switch (bank[address >>> 16]) {
            case CHIP32:
                if ((address & 3) == 0) return chipAccessLong(address, true, 0);
                // fall through: a misaligned long is two word slots
            case CHIP16: {
                final int hi = chipAccess(address, true, 2, 0);
                final int lo = chipAccess(address + 2, true, 2, 0);
                return (hi << 16) | lo;
            }
            case CIA: {
                final int hi = ciaAccess(address, true, 2, 0);
                final int lo = ciaAccess(address + 2, true, 2, 0);
                return (hi << 16) | lo;
            }
            default: {
                final int hi = agnus.eventTransfer(address, true, 2, 0);
                final int lo = agnus.eventTransfer(address + 2, true, 2, 0);
                idle((address & 3) != 0 ? 2 * memCycle : memCycle);
                if (hi < 0 || lo < 0) throw BusFault.INSTANCE;
                return (hi << 16) | lo;
            }
        }
    }

    @Override
    public void writeByte(int address, int value) {
        address &= 0xFFFFFF;
        switch (bank[address >>> 16]) {
            case CHIP16: case CHIP32: chipAccess(address, false, 1, value); break;
            case CIA: ciaAccess(address, false, 1, value); break;
            default: fastAccess(address, false, 1, value, memCycle); break;
        }
    }

    @Override
    public void writeWord(int address, int value) {
        address &= 0xFFFFFF;
        switch (bank[address >>> 16]) {
            case CHIP16: case CHIP32:
                if ((address & 3) == 3) {
                    chipAccess(address, false, 1, value >> 8);
                    chipAccess(address + 1, false, 1, value);
                } else {
                    chipAccess(address, false, 2, value);
                }
                break;
            case CIA: ciaAccess(address, false, 2, value); break;
            default: fastAccess(address, false, 2, value, (address & 3) == 3 ? 2 * memCycle : memCycle); break;
        }
    }

    @Override
    public void writeLong(int address, int value) {
        address &= 0xFFFFFF;
        switch (bank[address >>> 16]) {
            case CHIP32:
                if ((address & 3) == 0) {
                    chipAccessLong(address, false, value);
                    break;
                }
                // fall through: a misaligned long is two word slots
            case CHIP16:
                chipAccess(address, false, 2, value >>> 16);
                chipAccess(address + 2, false, 2, value);
                break;
            case CIA:
                ciaAccess(address, false, 2, value >>> 16);
                ciaAccess(address + 2, false, 2, value);
                break;
            default: {
                final int a = agnus.eventTransfer(address, false, 2, value >>> 16);
                final int b = agnus.eventTransfer(address + 2, false, 2, value);
                idle((address & 3) != 0 ? 2 * memCycle : memCycle);
                if (a < 0 || b < 0) throw BusFault.INSTANCE;
                break;
            }
        }
    }

    @Override
    public int readLongUntimed(int address) {
        address &= 0xFFFFFF;
        final int hi = agnus.eventTransfer(address, true, 2, 0);
        final int lo = agnus.eventTransfer(address + 2, true, 2, 0);
        return ((hi & 0xFFFF) << 16) | (lo & 0xFFFF);
    }

    @Override
    public int ipl() {
        return agnus.eventIpl();
    }

    @Override
    public int iplChanges() {
        return iplChanges;
    }

    @Override
    public int interruptAcknowledge(int level) {
        return 24 + level;
    }

    @Override
    public void resetInstruction() {
        agnus.eventResetInstruction();
    }

    @Override
    public void resetLine(boolean asserted) {
        pendingResetLine = asserted ? 1 : 0;
    }

    /**
     * A change of the processor's /RESET output not yet presented to Agnus (-1: none). Like a
     * clocked processor's outputs it appears after the chipset's rising edge of the master
     * cycle in progress, so Agnus sees it at the next one.
     */
    private int pendingResetLine = -1;

    private void rising() {
        agnus.chipRisingEdge();
        if (pendingResetLine >= 0) {
            agnus.eventResetLine(pendingResetLine != 0);
            pendingResetLine = -1;
        }
    }
}
