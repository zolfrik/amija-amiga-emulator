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

import io.github.zolfrik.amija.common.BusRequest;
import io.github.zolfrik.amija.common.BusResponse;
import io.github.zolfrik.amija.common.CIAPinSet;
import io.github.zolfrik.amija.common.MOS8520;
import io.github.zolfrik.amija.common.VideoMode;
import io.github.zolfrik.amija.memory.ROM;
import io.github.zolfrik.amija.memory.RAM;
import io.github.zolfrik.amija.cpu.AbstractMotorola;
import io.github.zolfrik.amija.cpu.Processor;

import java.util.Arrays;
import java.util.function.IntConsumer;

/**
 * Agnus (8361/8367/8372): DMA controller, Copper, Blitter and bus arbiter of the Amiga 500.
 *
 * Agnus is the heart of the Amiga's DMA system. It:
 *   - generates the video sync signals (hpos/vpos);
 *   - arbitrates the bus between CPU, Copper, Blitter and custom DMA;
 *   - runs the DMA slots: refresh, disk, audio, sprites, bitplanes, Copper, Blitter;
 *   - feeds Denise with bitplane and sprite data by DMA;
 *   - talks to the CIAs for OVL, TOD and interrupts;
 *   - talks to Paula for interrupts and disk DMA.
 *
 * TIMING
 *
 * Two time units:
 *   - system clock (SC): the PAL master clock at 7.09379 MHz;
 *   - colour clock (CCK): SC/2 = 3.54690 MHz (one CCK = 2 SC).
 *
 * The DMA slots are assigned in CCK units. A whole PAL line has 227 CCK (454 SC).
 *
 * CIA TOD LINES
 *
 * CIA-A TOD: a pulse from the vertical sync (50 Hz PAL / 60 Hz NTSC), one per frame.
 *
 * CIA-A FLAG: the floppy index pulse (~5 Hz, once per revolution), driven by the drive's
 *   index signal through updateFloppy(), NOT by the vertical sync.
 *
 * INTERRUPTS
 *
 * CIA-A IRQ -> Paula INTREQ bit 3 (PORTS, IPL 2).
 * CIA-B IRQ -> Paula INTREQ bit 13 (EXTER, IPL 6).
 *
 * The PORTS/EXTER bit is set when the CIA's /IRQ pin goes low. Software clears it by
 * writing INTREQ (CLR); it is NOT cleared when the CIA releases its IRQ: the INTREQ bit is
 * latched and must be cleared explicitly.
 *
 * @author alessio.carotenuto
 */
public abstract class AbstractAgnus {

    // =========================================================================
    // Logging
    // =========================================================================

    private int frameCount = 1;

    // =========================================================================
    // Dependencies
    // =========================================================================

    /** The clocked CPU this Agnus drives edge by edge; null when the processor is event-driven. */
    private final AbstractMotorola  cpu;
    /** The processor, whatever drives it. */
    private final Processor processor;
    /** True when the processor has a 32-bit bus (the 68020): see DENISEID and COPEN. */
    private final boolean wideBusCpu;
    private final BusRequest     cpuBusReq;
    private final BusResponse    cpuBusResp;

    private final Gary    gary;
    private final RAM chipRAM;
    private final RAM slowRAM;
    private final ROM     rom;

    /**
     * Zorro II Fast RAM expansion (AutoConfig). null = no Fast RAM (A500 default). Injected
     * after construction through {@link #setFastRam(FastRam)}. Seen by the CPU only: the
     * DMA masters (Copper/Blitter/bitplanes) never reach Fast RAM.
     */
    private FastRam fastRam;

    /**
     * Hard disk controllers (Zorro II boards with AutoConfig + boot ROM). Empty list = no
     * hard disk. Injected after construction through {@link #setHdControllers}. Like the
     * Fast RAM they take part in AutoConfig.
     */
    private final java.util.List<HdController> hdControllers = new java.util.ArrayList<>();

    /** Byte access to Amiga RAM (chip/slow/fast) for the hard disk controllers. */
    private final HdController.Bus hdBus = new HdController.Bus() {
        @Override public int  readByte(int addr)            { return hdReadByte(addr); }
        @Override public void writeByte(int addr, int value) { hdWriteByte(addr, value); }
        @Override public void writeBlock(int addr, byte[] src, int srcOff, int len) {
            hdWriteBlock(addr, src, srcOff, len);
        }
        @Override public void readBlock(int addr, byte[] dst, int dstOff, int len) {
            hdReadBlock(addr, dst, dstOff, len);
        }
    };

    /**
     * The DMA address space of Agnus, which every DMA channel reaches without going through
     * Gary. An address is masked with {@link #dmaMask}; below the Chip RAM size it is Chip
     * RAM, up to {@link #dmaEnd} it is Slow RAM, and above that nothing answers: a read gives
     * 0 and a write is lost.
     * <ul>
     *   <li>ECS with 512 KB of Chip RAM and at least 512 KB of Slow RAM: the Agnus space is
     *       1 MB and its upper half is the trapdoor RAM, so {@code $80000-$FFFFF} reaches the
     *       Slow RAM at {@code $C00000}. The mask is twice the Slow RAM size minus one.</li>
     *   <li>ECS otherwise: a 1 MB space, or the Chip RAM size when that is larger.</li>
     *   <li>OCS: a 512 KB space. Larger Chip RAM on OCS is an AmiJa configuration no OCS
     *       Agnus can address; the space then follows the Chip RAM.</li>
     * </ul>
     */
    private final int dmaMask;
    private final int dmaEnd;

    /**
     * The bits a DMA pointer keeps: the smallest all-ones mask that covers {@link #dmaMask}.
     * Pointer arithmetic wraps there, the address decode applies {@link #dmaMask}.
     */
    private final int dmaPtrMask;

    /** The high word of {@link #dmaPtrMask}: the bits BPLxPTH/SPRxPTH/DSKPTH/COPxLCH keep. */
    private final int dmaPtrHiMask;

    private final Copper      copper;
    private final BusRequest  copperBusReq;
    private final BusResponse copperBusResp;

    public AbstractDenise getDenise() {
        return denise;
    }

    public MOS8520 getCiaA() { return ciaA; }
    public MOS8520 getCiaB() { return ciaB; }
    public void setKeyboard(Keyboard keyboard) { this.keyboard = keyboard; }

    private final Blitter     blitter;

    private final Paula  paula;
    protected final AbstractDenise denise;

    private final MOS8520 ciaA;
    private final MOS8520 ciaB;
    private Keyboard keyboard;
    private final BatteryBackedClock batteryClock = new BatteryBackedClock();
    /** Gayle's registers; decoded only on the A1200 board (see {@link Gary#setGayleDecode}). */
    private final Gayle gayle = new Gayle();

    private final FloppyDrive df0, df1, df2, df3;

    // =========================================================================
    // Event Scheduler
    // =========================================================================

    public static final long NEVER = Long.MAX_VALUE;

    public enum EventSlot {
        SLOT_REG, SLOT_CIAA, SLOT_CIAB, SLOT_BPL, SLOT_DAS, SLOT_COP, SLOT_BLT, SLOT_SEC,
        SLOT_CH0, SLOT_CH1, SLOT_CH2, SLOT_CH3, SLOT_DSK, SLOT_VBL, SLOT_IRQ, SLOT_IPL,
        SLOT_KBD, SLOT_TXD, SLOT_RXD, SLOT_TER,
        SLOT_DC0, SLOT_DC1, SLOT_DC2, SLOT_DC3,
        SLOT_COUNT
    }

    /**
     * PERF: static cache of EventSlot.values(). Enum.values() clones the array at every
     * call, and executeEvents() is a hot loop (up to ~709k times/s for SLOT_CIAA).
     */
    private static final EventSlot[] SLOT_VALUES = EventSlot.values();

    public enum EventID {
        EVENT_NONE,
        REG_CHANGE,
        CIA_EXECUTE,
        BPL_FETCH,
        DAS_REFRESH, DAS_D0, DAS_D1, DAS_D2, DAS_A0, DAS_A1, DAS_A2, DAS_A3,
        DAS_S0_1, DAS_S0_2, DAS_S1_1, DAS_S1_2, DAS_S2_1, DAS_S2_2, DAS_S3_1, DAS_S3_2,
        DAS_S4_1, DAS_S4_2, DAS_S5_1, DAS_S5_2, DAS_S6_1, DAS_S6_2, DAS_S7_1, DAS_S7_2,
        COP_FETCH,
        VBL_STROBE,
        IRQ_CHECK,
        IPL_CHANGE,
        TXD_BIT,
        RXD_BIT,
        DSK_ROTATE,
        AUDIO_MIX
    }

    private final long[] trigger = new long[EventSlot.SLOT_COUNT.ordinal()];
    private final EventID[] eventIds = new EventID[EventSlot.SLOT_COUNT.ordinal()];
    private final long[] eventData = new long[EventSlot.SLOT_COUNT.ordinal()];
    private long nextTrigger = NEVER;

    /**
     * PERF: bitmask of the ACTIVE event slots (trigger != NEVER). SLOT_COUNT (25) <= 64, so
     * one long. executeEvents iterates the set bits only (typically 2-4 slots) in increasing
     * order (index 0..24, through numberOfTrailingZeros), the same order as a linear scan.
     * Kept consistent in scheduleAbs/cancel/executeEvents/internalReset, the only places
     * that change trigger[].
     */
    private long activeSlotMask = 0L;
    private long masterClock = 0;

    // --- Delayed Register Changes ---
    // Mutable, reusable RegChange objects. The recorder is a custom min-heap over
    // preallocated arrays instead of PriorityQueue+Comparator: no allocation or hidden
    // growth during Copper writes.
    private static final int REG_CHANGE_CAPACITY = 1024;

    private static class RegChange {
        long trigger;
        long sequence;
        AgnusReg reg;
        int value;
        int pc;

        RegChange() { }

        RegChange set(long trigger, long sequence, AgnusReg reg, int value, int pc) {
            this.trigger = trigger;
            this.sequence = sequence;
            this.reg = reg;
            this.value = value;
            this.pc = pc;
            return this;
        }
    }

    private final RegChange[] changeRecorder = new RegChange[REG_CHANGE_CAPACITY];
    private int changeRecorderSize;
    private final RegChange[] regChangePool = createRegChangePool();
    private int regChangePoolTop = REG_CHANGE_CAPACITY;
    private long regChangeSequence;

    private static RegChange[] createRegChangePool() {
        RegChange[] pool = new RegChange[REG_CHANGE_CAPACITY];
        for (int i = 0; i < pool.length; i++) {
            pool[i] = new RegChange();
        }
        return pool;
    }

    private RegChange acquireRegChange() {
        if (regChangePoolTop == 0) {
            throw new IllegalStateException("Agnus delayed register change overflow: " + REG_CHANGE_CAPACITY);
        }
        RegChange rc = regChangePool[--regChangePoolTop];
        regChangePool[regChangePoolTop] = null;
        return rc;
    }

    private void releaseRegChange(RegChange rc) {
        rc.reg = null;
        regChangePool[regChangePoolTop++] = rc;
    }

    private void offerRegChange(RegChange rc) {
        int idx = changeRecorderSize;
        if (idx >= changeRecorder.length) {
            releaseRegChange(rc);
            throw new IllegalStateException("Agnus delayed register heap overflow: " + changeRecorder.length);
        }
        changeRecorderSize = idx + 1;
        while (idx > 0) {
            int parent = (idx - 1) >>> 1;
            RegChange parentChange = changeRecorder[parent];
            if (compareRegChange(parentChange, rc) <= 0) break;
            changeRecorder[idx] = parentChange;
            idx = parent;
        }
        changeRecorder[idx] = rc;
    }

    private RegChange peekRegChange() {
        return changeRecorderSize == 0 ? null : changeRecorder[0];
    }

    private RegChange pollRegChange() {
        if (changeRecorderSize == 0) return null;
        RegChange result = changeRecorder[0];
        RegChange moved = changeRecorder[--changeRecorderSize];
        changeRecorder[changeRecorderSize] = null;
        if (changeRecorderSize > 0) {
            int idx = 0;
            int half = changeRecorderSize >>> 1;
            while (idx < half) {
                int child = (idx << 1) + 1;
                RegChange bestChild = changeRecorder[child];
                int right = child + 1;
                if (right < changeRecorderSize
                        && compareRegChange(changeRecorder[right], bestChild) < 0) {
                    child = right;
                    bestChild = changeRecorder[right];
                }
                if (compareRegChange(moved, bestChild) <= 0) break;
                changeRecorder[idx] = bestChild;
                idx = child;
            }
            changeRecorder[idx] = moved;
        }
        return result;
    }

    private boolean isRegChangeHeapEmpty() {
        return changeRecorderSize == 0;
    }

    private void clearRegChanges() {
        while (changeRecorderSize > 0) {
            RegChange rc = changeRecorder[--changeRecorderSize];
            changeRecorder[changeRecorderSize] = null;
            releaseRegChange(rc);
        }
        regChangeSequence = 0;
    }

    private static int compareRegChange(RegChange a, RegChange b) {
        int byTrigger = Long.compare(a.trigger, b.trigger);
        return byTrigger != 0 ? byTrigger : Long.compare(a.sequence, b.sequence);
    }

    private int syncEvent = 0;
    private static final int EVFL_EOL   = 1 << 0;
    private static final int EVFL_HSYNC = 1 << 1;

    public enum AgnusReg {
        DMACON, VPOSR, VHPOSR, BPLCON0, BPLCON1, DIWSTRT, DIWSTOP, DDFSTRT, DDFSTOP,
        BPL1MOD, BPL2MOD, BPL1PTH, BPL2PTH, BPL3PTH, BPL4PTH, BPL5PTH, BPL6PTH,
        BPL1PTL, BPL2PTL, BPL3PTL, BPL4PTL, BPL5PTL, BPL6PTL,
        SPR0POS, SPR1POS, SPR2POS, SPR3POS, SPR4POS, SPR5POS, SPR6POS, SPR7POS,
        SPR0CTL, SPR1CTL, SPR2CTL, SPR3CTL, SPR4CTL, SPR5CTL, SPR6CTL, SPR7CTL,
        SPR0PTH, SPR1PTH, SPR2PTH, SPR3PTH, SPR4PTH, SPR5PTH, SPR6PTH, SPR7PTH,
        SPR0PTL, SPR1PTL, SPR2PTL, SPR3PTL, SPR4PTL, SPR5PTL, SPR6PTL, SPR7PTL,
        DSKPTH, DSKPTL, COPCON, FMODE_FETCH
    }

    public void recordRegisterChange(long delay, AgnusReg reg, int value) {
        offerRegChange(acquireRegChange().set(masterClock + delay, regChangeSequence++, reg, value, getCpuPc()));
        scheduleNextRegEvent();
    }

    private void scheduleNextRegEvent() {
        if (syncEvent != 0) {
            scheduleRel(EventSlot.SLOT_REG, 1, EventID.REG_CHANGE);
        } else if (!isRegChangeHeapEmpty()) {
            scheduleAbs(EventSlot.SLOT_REG, peekRegChange().trigger, EventID.REG_CHANGE);
        }
    }

    private void eolHandler() {
        // Placeholder for end-of-line actions
        Arrays.fill(busOwners, BusOwner.NONE.ordinal());
    }

    private void hsyncHandler() {
        // VirtualCRT takes HSYNC by watching hpos/vpos from the MasterClock. The
        // potentiometer model needs it explicitly: it counts one line at a time. See
        // Paula.capCheck.
        if (paula != null) paula.potHsync();
    }

    private void serviceRegEvent(long until) {
        if ((syncEvent & EVFL_EOL) != 0) {
            eolHandler();
            syncEvent &= ~EVFL_EOL;
        }
        if ((syncEvent & EVFL_HSYNC) != 0) {
            hsyncHandler();
            syncEvent &= ~EVFL_HSYNC;
        }

        while (!isRegChangeHeapEmpty()) {
            if (peekRegChange().trigger > until) break;
            RegChange change = pollRegChange();
            applyDelayedRegisterChange(change.reg, change.value, change.pc);
            releaseRegChange(change);
        }
        scheduleNextRegEvent();
    }

    /**
     * A write to BPLxPTH/BPLxPTL is <b>lost</b> if that plane has its fetch slot in the
     * <b>NEXT CCK</b>: the pointer for that slot has already been latched.
     *
     * <p>The sequence is a two-stage pipeline:
     * <pre>
     * cck C  : capture the pointer for the slot of C+1
     * cck C+1: fetch from the captured pointer, add the modulo, write it back
     * </pre>
     * Bitplane and sprite DMA are decided 1 CCK earlier than the other channels, so their
     * pointer and modulo are loaded in the following cycle. A write that falls in the
     * capture's CCK arrives <b>after</b> it, and one CCK later the write-back cancels it. A
     * write one CCK earlier, or one CCK later, survives.
     *
     * <p>Measured on {@code BPLPTR/drop31}, which sweeps the CCK of the write one unit at a
     * time: of the test's eight windows the write is lost exactly in the two where the
     * plane's fetch falls at the next CCK (vpos 73 and 121), and kept in the other six.
     */
    private boolean bplPointerWriteIsLost(int bidx) {
        // It applies to COPPER writes, not CPU ones, because of the order within a CCK:
        // the capture comes at the top of the CCK, BEFORE the Copper takes its DMA slot,
        // while the CPU's access completes before the next capture. The capture thus sits
        // BETWEEN the CPU's write window and the Copper's. Measured: in `dropcpu41` and
        // `dropcpu61` the CPU write always comes before the capture of the same CCK and
        // survives; in `drop31` the Copper's comes after and is lost.
        if (!__inCopperWrite) {
            return false;
        }
        // The request fetched at the next CCK has already been captured by now.
        return bplReqNext >= 0 && (bplReqNext & BPLREQ_PLANE) == bidx;
    }

    /**
     * The same as {@link #bplPointerWriteIsLost(int)} for SPRxPT, and not by analogy: the
     * capture of the pointer for the slot leaving the pipe at the next CCK is one mechanism
     * for bitplanes and sprites alike. A Copper write falling in the capture's CCK arrives
     * after it, and the next CCK's write-back cancels it; a CPU write does not, by the order
     * within the CCK measured on the bitplanes.
     *
     * <p>Measured by the sweep {@code Denise/Sprites/sprdrop1-4}: the same program four times
     * with the Copper's WAIT two CCK apart, around the sprite's first DMA slot.
     *
     * <p><b>Only if that slot is an ALLOCATED DMA cycle</b>: the capture happens only for an
     * allocated slot, and a sprite slot is allocated only outside Agnus's vertical blank,
     * with sprite DMA on and with the channel's sequencer asking for it. Where there is no
     * slot there is no capture and no write-back, and the Copper write <b>survives</b>. That
     * is exactly the list of conditions that decides the transfer, so the question is asked
     * once, in {@link #spriteSlotAllocated(int, boolean, long)}.
     *
     * <p>Measured on Agony: its Copper list rearms SPRxPT of channels 3 and 4 at
     * {@code vpos 0}, in the vertical blank, at CCK 32 and 36, the CCK before their slots.
     * Looking at the sequencer latch alone threw both writes away every frame, and the owl's
     * sprites drew leftovers of the previous line.
     */
    private boolean sprPointerWriteIsLost(int sidx) {
        if (!__inCopperWrite) {
            return false;
        }
        final int next = (hpos >> 1) + 1;
        if (next >= DMA_SLOT_TABLE.length || DMA_SLOT_TABLE[next] != DMASLOT_SPRITE) {
            return false;
        }
        if ((next - SLOT_SPRITE_0) / 4 != sidx) {
            return false;
        }
        // The slot is the next CCK's, so it is queried with the values it will have then:
        // the sprite DMA bit decided for it is the one now in the first pipe stage, and its
        // decision fell in the previous CCK.
        return spriteSlotAllocated(next, spriteDmaPipe, (masterClock >> 1) - 1);
    }

    /**
     * True if the sprite slot at CCK {@code cck} is an <b>allocated</b> DMA cycle: one in
     * which Agnus really fetches a word and advances SPRxPT. No slot is allocated during
     * Agnus's vertical blank; it needs sprite DMA on and the channel's own sequencer
     * ({@link #sprSlotFetch}, which already carries the DMA cycle state and the conflict with
     * the bitplanes); and SPREN coming on in the very CCK of the decision leaves the slot
     * <b>decided but not allocated</b> (see {@link #spriteDmaOnDecisionCck}).
     *
     * <p>Two places need the answer and must get the same one: the arbitration, to know
     * whether to fetch, and {@link #sprPointerWriteIsLost(int)}, to know whether that slot
     * captures the pointer one CCK earlier.
     *
     * @param dmaSeq       the sprite DMA bit <b>decided</b> for that slot, two CCK earlier
     * @param decisionCck  the global CCK in which that slot was decided
     */
    private boolean spriteSlotAllocated(int cck, boolean dmaSeq, long decisionCck) {
        return decisionCck != spriteDmaOnDecisionCck && spriteSlotDecided(cck, dmaSeq);
    }

    /**
     * The sprite slot at {@code cck} is decided but not allocated: SPREN came on in the CCK of
     * its decision. Nobody owns the cycle, so a lower-priority channel can take it.
     */
    private boolean spriteSlotDecidedNotAllocated(int cck, boolean dmaSeq, long decisionCck) {
        return decisionCck == spriteDmaOnDecisionCck && spriteSlotDecided(cck, dmaSeq);
    }

    /** Whether the sprite channel decided a transfer for the slot at {@code cck}. */
    private boolean spriteSlotDecided(int cck, boolean dmaSeq) {
        if (syncsStopped || !dmaSeq) {
            return false;
        }
        if (vpos < SPRITE_RESET_LINE || vpos >= maxVpos) {
            return false;
        }
        final int idx = (cck - SLOT_SPRITE_0) / 4;
        if (idx < 0 || idx > 7) {
            return false;
        }
        return sprSlotFetch[idx][((cck - SLOT_SPRITE_0) % 4) / 2];
    }

    /**
     * <b>Dead path</b>: {@link #recordRegisterChange} has no callers, so no write goes
     * through here. The rule for bitplane pointers lives in
     * {@link #bplPointerWriteIsLost(int)}, on the live path.
     */
    private boolean dropWrite(BusOwner owner) {
        // A write to a pointer register is dropped if the pointer was used one cycle
        // before the update would happen.
        int cck = hpos >> 1;
        if (cck >= 1 && busOwners[cck - 1] == owner.ordinal()) {
            return true;
        }
        return false;
    }

    private void applyDelayedRegisterChange(AgnusReg reg, int value, int pc) {
        switch (reg) {
            case DMACON:  setDMACON(value, pc); break;
            case BPLCON0: setBPLCON0(value, pc); break;
            case BPLCON1: setBPLCON1(value); break;
            case DIWSTRT:
                diwStrt = value;
                diwVStart = (diwStrt >> 8) & 0xFF;
                if (denise != null) denise.writeRegister(0x08E, value);
                break;
            case DIWSTOP:
                diwStop = value;
                diwVStop = (diwStop >> 8) & 0xFF;
                if ((diwStop & 0x8000) == 0) diwVStop += 256;
                if (denise != null) denise.writeRegister(0x090, value);
                break;
            case DDFSTRT:
                ddfStrt = value;
                ddfCckStart = ddfStrt & ddfMask();
                break;
            case DDFSTOP:
                ddfStop = value;
                ddfCckStop = decodeDdfStopCck(ddfStop);
                break;
            case BPL1MOD:
                bpl1mod = (short) (value & 0xFFFE);
                break;
            case BPL2MOD:
                bpl2mod = (short) (value & 0xFFFE);
                break;
            case BPL1PTH: case BPL2PTH: case BPL3PTH: case BPL4PTH: case BPL5PTH: case BPL6PTH: {
                int bidx = (reg.ordinal() - AgnusReg.BPL1PTH.ordinal());
                if (dropWrite(OWNER_VALUES[BusOwner.BPL1.ordinal() + bidx])) break;
                bplPt[bidx] = (bplPt[bidx] & 0x0000FFFF) | ((value << 16) & dmaPtrHiMask);
                bplPtrInternal[bidx] = (bplPtrInternal[bidx] & 0x0000FFFF) | (bplPt[bidx] & dmaPtrHiMask);
                break;
            }
            case BPL1PTL: case BPL2PTL: case BPL3PTL: case BPL4PTL: case BPL5PTL: case BPL6PTL: {
                int bidx = (reg.ordinal() - AgnusReg.BPL1PTL.ordinal());
                if (dropWrite(OWNER_VALUES[BusOwner.BPL1.ordinal() + bidx])) break;
                bplPt[bidx] = (bplPt[bidx] & dmaPtrHiMask) | (value & 0xFFFE);
                bplPtrInternal[bidx] = bplPt[bidx];
                break;
            }
            case SPR0POS: case SPR1POS: case SPR2POS: case SPR3POS: case SPR4POS: case SPR5POS: case SPR6POS: case SPR7POS: {
                int sidx = (reg.ordinal() - AgnusReg.SPR0POS.ordinal());
                setSPRxPOS(sidx, value);
                break;
            }
            case SPR0CTL: case SPR1CTL: case SPR2CTL: case SPR3CTL: case SPR4CTL: case SPR5CTL: case SPR6CTL: case SPR7CTL: {
                int sidx = (reg.ordinal() - AgnusReg.SPR0CTL.ordinal());
                setSPRxCTL(sidx, value);
                break;
            }
            case SPR0PTH: case SPR1PTH: case SPR2PTH: case SPR3PTH: case SPR4PTH: case SPR5PTH: case SPR6PTH: case SPR7PTH: {
                int sidx = (reg.ordinal() - AgnusReg.SPR0PTH.ordinal());
                if (dropWrite(OWNER_VALUES[BusOwner.SPRITE0.ordinal() + sidx])) break;
                sprPt[sidx] = (sprPt[sidx] & 0x0000FFFF) | ((value << 16) & dmaPtrHiMask);
                // SPRxPT is the LIVE DMA pointer: a write (CPU or Copper) changes where the
                // next sprite DMA access fetches from, like the bitplanes (Agnus has no
                // separate internal pointer for sprites). Games that reposition sprites
                // through the Copper at the start of the frame (e.g. Silkworm) rely on it.
                sprPtrInternal[sidx] = (sprPtrInternal[sidx] & 0x0000FFFF) | (sprPt[sidx] & dmaPtrHiMask);
                break;
            }
            case SPR0PTL: case SPR1PTL: case SPR2PTL: case SPR3PTL: case SPR4PTL: case SPR5PTL: case SPR6PTL: case SPR7PTL: {
                int sidx = (reg.ordinal() - AgnusReg.SPR0PTL.ordinal());
                if (dropWrite(OWNER_VALUES[BusOwner.SPRITE0.ordinal() + sidx])) break;
                sprPt[sidx] = (sprPt[sidx] & dmaPtrHiMask) | (value & 0xFFFE);
                sprPtrInternal[sidx] = sprPt[sidx];
                break;
            }
            case DSKPTH:
                if (dropWrite(BusOwner.DISK)) break;
                dskpt = (dskpt & 0x0000FFFF) | ((value << 16) & dmaPtrHiMask);
                if (paula != null) paula.setDiskDmaPointerHigh(value & 0x001F);
                break;
            case DSKPTL:
                if (dropWrite(BusOwner.DISK)) break;
                dskpt = (dskpt & dmaPtrHiMask) | (value & 0xFFFE);
                if (paula != null) paula.setDiskDmaPointer(dskpt);
                break;
            case COPCON: copper.writeRegister(0x02E, value); break;
            case FMODE_FETCH:
                bplFetchFmode = value & 3;
                sprFetchFmode = (value >> 2) & 3;
                break;
        }
    }

    /**
     * Every time a sprite's VSTART/VSTOP change (SPRxPOS/SPRxCTL written by the CPU, the
     * Copper or the DMA fetch of the descriptor) the sprite's DMA state is re-evaluated ON
     * THE CURRENT LINE, not only at the line change. That is how a game can (re)start a
     * sprite in the middle of the frame by writing POS/CTL through the Copper.
     */
    private void sprStartStop(int idx) {
        // The line is {@link #triggerVpos}, not {@code vpos}: the difference shows in the
        // LAST THREE CCK of the line. `sprdma12`/`sprdma13` measure it ("SPRxCTL modified
        // at the end of a rasterline"): the Copper writes SPRxCTL at `WAIT $47DF` (end of
        // line 71), where the line counter already reads 72, so `vpos == vstop` matches the
        // OLD VSTOP and the sprite is **put to sleep**.
        if (inVBlankArea(triggerVpos)) return;
        if (triggerVpos == sprVStart[idx]) sprDmaEnabled[idx] = true;
        if (triggerVpos == sprVStop[idx]) {
            sprDmaEnabled[idx] = false;
            // Asymmetry: on the WRITE path VSTOP CLEARS the DMA cycle state, while at
            // slot 0 of the sprite fetch it sets it to 1. This difference decides who wins
            // between a Copper write and the DMA.
            sprDmaCycle[idx] = 0;
        }
    }

    /** SPRxPOS: start/stop check, update, start/stop check. */
    private void writeSPRxPOS(int idx, int value) {
        sprStartStop(idx);
        setSPRxPOS(idx, value);
        sprStartStop(idx);
        // Alice: bit 7 of SPRxPOS marks the sprite for scan doubling under SSCAN2.
        sprScanDouble[idx] = isAGA() && (value & 0x80) != 0;
    }

    /** Per sprite: bit 7 of its last SPRxPOS on Alice, the SSCAN2 doubling flag. */
    private final boolean[] sprScanDouble = new boolean[8];

    /**
     * SSCAN2 (FMODE bit 15): a doubled sprite fetches its data only on the lines of the parity
     * of its VSTART, so every data line shows twice.
     */
    private boolean sprScanDoubleSkip(int idx) {
        return sprScanDouble[idx] && (fmode & 0x8000) != 0 && sprDmaEnabled[idx]
                && (vpos & 1) != (sprVStart[idx] & 1);
    }

    /** SPRxCTL: start/stop check, update, start/stop check. */
    private void writeSPRxCTL(int idx, int value) {
        sprStartStop(idx);
        setSPRxCTL(idx, value);
        sprStartStop(idx);
    }

    private void setSPRxPOS(int idx, int value) {
        // SPRxPOS bit 15-8 = VSTART[7:0]; bit 0 = HSTART bit 0 (ignored here).
        // Bit 8 of VSTART comes from SPRxCTL bit 2, already stored in sprVStart.
        sprVStart[idx] = (sprVStart[idx] & 0x100) | ((value >> 8) & 0xFF);
    }

    private void setSPRxCTL(int idx, int value) {
        // SPRxCTL bit 15-8 = VSTOP[7:0]
        // SPRxCTL bit 2    = VSTART bit 8
        // SPRxCTL bit 1    = VSTOP  bit 8
        sprVStart[idx] = (sprVStart[idx] & 0x00FF) | ((value & 0x04) != 0 ? 0x100 : 0);
        sprVStop[idx]  = ((value >> 8) & 0xFF)     | ((value & 0x02) != 0 ? 0x100 : 0);
        // Sprite V9 (ECS): SPRxCTL gains a ninth vertical bit in the "unused" bits 6
        // (VSTART V9) and 5 (VSTOP V9). Ignored on OCS.
        if (isECS()) {
            if ((value & 0x40) != 0) sprVStart[idx] |= 0x200;
            if ((value & 0x20) != 0) sprVStop[idx]  |= 0x200;
        }
    }

    private void setDMACON(int value, int pc) {
        int oldValue = dmacon;
        boolean set = (value & 0x8000) != 0;
        int bits    = value & 0x07FF;
        int newValue;
        if (set) newValue = (oldValue | bits) & 0x07FF;
        else     newValue = (oldValue & ~bits) & 0x07FF;

        if (oldValue == newValue) return;

        final boolean copWas = copperDmaEnabled;

        dmacon = newValue;
        updateDmaFlags();

        // A COPEN change is NOT visible to the arbitration in the same CCK: until the
        // current cycle has passed the threshold, the Copper sees the OLD value. The
        // threshold is inclusive, so a COPPER write costs 1 CCK and a 68000 write 2.
        // It has the same shape as class B of section 14 (DDFSTRT/DDFSTOP: the match uses
        // the old value) and as `spriteDmaOnDecisionCck`. Measured on the four `lc` tests of
        // the Agnus/Copper bank, which switch Copper DMA on halfway through a line: they
        // were 8 px each, i.e. 2 CCK early, always on a single line.
        // A 68020's write costs 1, like the Copper's: written in CCK W at an even position,
        // COPEN gives the Copper's first cycle at W+2, against W+4 for the 68000 (measured on
        // the KS 1.3 boot of `Timing/Bcc/BRA1` and on `lc1`).
        if (copWas != copperDmaEnabled) {
            copperDmaChangeCck = (masterClock >> 1)
                    + (__inCopperWrite || (eventCpuWrite && wideBusCpu) ? 0 : 1);
        }

        // Notify other components if needed
        if (blitter != null) {
            blitter.setDmaEnabled(blitterDmaEnabled);
        }
        if (copper != null) {
            copper.setDmaEnabled(copperDmaEnabled);
        }
        if (paula != null) {
            paula.setAudioDmaEnabledMask(dmacon);
        }
    }

    private void setBPLCON0(int value, int pc) {
        if (denise != null) denise.writeRegister(0x100, value);
        // The release is IMMEDIATE, the entry is not: the freeze starts only at the line
        // boundary in `updateBeam`. So an ERSY raised and lowered within the same line never
        // gets here with `syncsStopped` true, and does nothing.
        if (syncsStopped && !ersyActive()) {
            syncsStopped = false;
            // ERSY FALLING: the line starts over.
            //
            // During the freeze the horizontal counter is held at the start of the line at
            // every CCK, so when the BPLCON0 write clears ERSY the beam is at the start of a
            // line and the current line lasts in full. Without this reset the frozen frame
            // would grow by a multiple of a line instead of by the length of the freeze
            // (docs/vamigats-paula-audio-plan.md section 13.10.4).
            //
            // The other half (hpos held *during* the freeze, so no DMA slot for its whole
            // length) lives in the arbitration of `risingEdge`, on the `beamFrozen` flag:
            // while the freeze lasts the bus goes to no Agnus channel and stays with the CPU
            // and the Blitter.
            // The line restarts six CCKs before its wrap: one because the vertical counter
            // advances one CCK into the line rather than at the wrap, three for the beam
            // origin (see INITIAL_HPOS), and two for BPLCON0 reaching the sync generator
            // after the write that clears ERSY.
            hpos = maxHpos - 12;
        }
        // The bitplane sequencer sees BPLCON0 two CCKs after the write, and it sees every write:
        // plane count and resolution are live, not latched per line. A write lands on the
        // decision of write+2, i.e. on the fetch of write+4. Measured on bbusy1f (BPU 0->1 at
        // CCK 210, fetched at 215) and bbusy4 (BPU 2->3 at 210, the extra plane at 213 is not).
        queueSequencerBplcon0(value);
    }

    /** A BPLCON0 write on its way to the bitplane sequencer, which sees it two CCKs later. */
    private void queueSequencerBplcon0(int value) {
        drainSequencerBplcon0();
        if (seqBplcon0Count == seqBplcon0Pending.length) {
            // Cannot happen: at most one write per CCK and each waits two. Apply the oldest.
            seqBplcon0 = seqBplcon0Pending[0];
            updateFetchGeometry();
            System.arraycopy(seqBplcon0Pending, 1, seqBplcon0Pending, 0, seqBplcon0Count - 1);
            System.arraycopy(seqBplcon0Due, 1, seqBplcon0Due, 0, seqBplcon0Count - 1);
            seqBplcon0Count--;
        }
        seqBplcon0Pending[seqBplcon0Count] = value;
        seqBplcon0Due[seqBplcon0Count] = (masterClock >> 1) + 2;
        seqBplcon0Count++;
    }

    /** Applies the BPLCON0 writes that have reached the sequencer by this CCK. */
    private void drainSequencerBplcon0() {
        final long now = masterClock >> 1;
        int n = 0;
        while (n < seqBplcon0Count && seqBplcon0Due[n] <= now) {
            seqBplcon0 = seqBplcon0Pending[n++];
        }
        if (n > 0) {
            updateFetchGeometry();
            seqBplcon0Count -= n;
            System.arraycopy(seqBplcon0Pending, n, seqBplcon0Pending, 0, seqBplcon0Count);
            System.arraycopy(seqBplcon0Due, n, seqBplcon0Due, 0, seqBplcon0Count);
        }
    }

    private void setBPLCON1(int value) {
        if (denise == null) {
            bplcon1 = value & 0xFF;
            return;
        }
        // The scroll is Denise's register: a write lost on the RGA bus leaves it unchanged.
        denise.writeRegister(0x102, value);
        bplcon1 = denise.bplcon1();
    }

    /**
     * The horizontal scroll of BPLCON1 in <b>sub-pixels</b> (hires pixels), the unit Denise
     * really applies it in.
     *
     * <p><b>In hires the scroll is NOT finer: it is SHORTER.</b> One BPLCON1 unit is one
     * <b>lores</b> pixel (2 sub-pixels) <b>in both resolutions</b>; what changes in hires is
     * that the value is <b>masked to 3 bits</b> and so wraps at 8. Assuming "one unit = one
     * pixel of the current resolution" is wrong, and was measured: with
     * {@code v * (hires ? 1 : 2)} the {@code simple} group got worse, from 6.77% to 9.55%.
     *
     * <p>The single rule {@code sub = (v & mask) * 2} then splits into:
     * <ul>
     *   <li><b>coarse</b>, {@code sub >> 2}: how many CCK to delay the draw block (one CCK is
     *       4 sub-pixels), decided in {@link #handleBitplaneDrawEvent};</li>
     *   <li><b>fine</b>, {@code sub & 3}: the remainder below the CCK, which Denise applies
     *       by moving its output cursor.</li>
     * </ul>
     *
     * <p>On the hardware the scroll is <b>one counter</b> in Denise; splitting it in two is
     * a structural shortcut, and this function is where it keeps <b>a single rule</b>.
     */
    private static int scrollSubPixels(int pfh, boolean hires) {
        return (pfh & (hires ? 0x07 : 0x0F)) * 2;
    }

    // --- End of Delayed Register Changes ---

    public long getMasterClock() {
        return masterClock;
    }

    public void scheduleAbs(EventSlot slot, long cycle, EventID id) {
        int s = slot.ordinal();
        trigger[s] = cycle;
        eventIds[s] = id;
        if (cycle != NEVER) {
            activeSlotMask |= (1L << s);
            if (cycle < nextTrigger) nextTrigger = cycle;
        } else {
            // Scheduling at NEVER is the same as deactivating the slot (it never fires and
            // does not lower nextTrigger): drop it from the active mask.
            activeSlotMask &= ~(1L << s);
        }
    }

    public void scheduleAbs(EventSlot slot, long cycle, EventID id, long data) {
        scheduleAbs(slot, cycle, id);
        eventData[slot.ordinal()] = data;
    }

    public void scheduleRel(EventSlot slot, long delay, EventID id) {
        scheduleAbs(slot, masterClock + delay, id);
    }

    public void scheduleRel(EventSlot slot, long delay, EventID id, long data) {
        scheduleAbs(slot, masterClock + delay, id, data);
    }

    public void cancel(EventSlot slot) {
        int s = slot.ordinal();
        eventIds[s] = EventID.EVENT_NONE;
        trigger[s] = NEVER;
        activeSlotMask &= ~(1L << s);
        // Recalculating nextTrigger would be expensive, we'll do it during execution if needed
    }

    public boolean hasEvent(EventSlot slot) {
        return eventIds[slot.ordinal()] != EventID.EVENT_NONE;
    }

    private void executeEvents(long cycle) {
        // PERF: a SINGLE scan fires the events and rebuilds nextTrigger: it starts from
        // NEVER and takes the minimum of the triggers NOT fired; events rescheduled during
        // processEvent lower nextTrigger through scheduleAbs.
        //
        // Correctness: the resulting nextTrigger is always <= the true minimum of the
        // current triggers, so no event is ever lost or anticipated. The only possible
        // effect is a "stale low" value (a handler rescheduling an already scanned slot
        // LATER), which costs at most one extra no-op call, the same as cancel(), which
        // leaves nextTrigger unchanged. trigger[] and eventIds[] are always consistent
        // (trigger == NEVER iff eventIds == NONE).
        nextTrigger = NEVER;
        // Only the ACTIVE slots (bits set in activeSlotMask), in increasing index order.
        // activeSlotMask is re-read at every step (not a snapshot), so a handler that
        // reschedules a higher slot within the same `cycle` is still visited in this pass.
        int i = 0;
        while (true) {
            long remaining = activeSlotMask >>> i;
            if (remaining == 0L) break;
            i += Long.numberOfTrailingZeros(remaining);

            if (trigger[i] <= cycle && eventIds[i] != EventID.EVENT_NONE) {
                EventID id = eventIds[i];
                EventSlot slot = SLOT_VALUES[i];
                long data = eventData[i];

                // Clear event before processing (it might reschedule itself)
                eventIds[i] = EventID.EVENT_NONE;
                trigger[i] = NEVER;
                activeSlotMask &= ~(1L << i);

                processEvent(slot, id, data);
            } else if (trigger[i] < nextTrigger) {
                nextTrigger = trigger[i];
            }
            i++;
        }
    }

    private void processEvent(EventSlot slot, EventID id, long data) {
        switch (slot) {
            case SLOT_REG:
                serviceRegEvent(masterClock);
                break;
            case SLOT_IRQ:
                if (paula != null) paula.serviceIrqEvent();
                break;
            case SLOT_IPL:
                if (paula != null) paula.serviceIplEvent(data);
                break;
            case SLOT_TXD:
                if (paula != null) paula.serviceTxdEvent(id);
                break;
            case SLOT_RXD:
                if (paula != null) paula.serviceRxdEvent(id);
                break;
            case SLOT_DSK:
                if (paula != null) paula.serviceDiskEvent();
                break;
            case SLOT_CIAA:
                serviceCiaEvent();
                break;
            case SLOT_CH0:
                // Production of one audio output sample (event driven): the rate
                // arithmetic runs once per sample (~44.1k/s) instead of at every master
                // cycle (~7.09M/s).
                if (paula != null) paula.serviceAudioSampleEvent();
                break;
        }
    }

    /**
     * E-clock tick of the CIAs (CLK/10), the keyboard and Gary, a self-rescheduling event on
     * {@link EventSlot#SLOT_CIAA}. It fires at exactly the master cycles of the E clock
     * (multiples of 10) at no cost in the 9 cycles between, since {@code nextTrigger} keeps
     * executeEvents from running when there is nothing to do.
     *
     * Both CIAs are ticked TOGETHER at every E-clock.
     */
    private void serviceCiaEvent() {
        if (ciaA != null) ciaA.tick();
        if (ciaB != null) ciaB.tick();
        if (keyboard != null) keyboard.tick(10);

        // -- CTRL+AMIGA+AMIGA keyboard reset (updated at E-clock rate) --
        // The keyboard's MPU holds /KBRST low; Gary passes it on to /RESET+/HALT of the
        // 68000. On the falling edge the CPU is reset and in turn asserts its own RESET
        // line: from there the cascade (custom chips, CIAs, floppies, Zorro bus, ROM
        // overlay re-enabled) is the same as the RESET instruction's, handled in
        // risingEdge(). RAM is untouched: it is a warm reset, as on the hardware.
        if (gary != null) {
            if (keyboard != null) gary.setKeyboardResetLine(keyboard.isResetLineAsserted());
            if (gary.isResetAsserted()) {
                if (!systemResetActive) {
                    systemResetActive = true;
                    if (processor != null) processor.reset();
                }
                // While /RESET is low the machine stays halted: the CPU's reset window is
                // renewed at every E-clock (10 cycles), so it expires only when the
                // keyboard releases the line and the CPU restarts from the reset vector.
                if (processor != null) processor.assertReset(64);
            } else {
                systemResetActive = false;
            }
        }

        // OVL (ROM overlay) and LED/audio filter lines: they depend only on CIA-A's
        // PRA/DDRA latch, which changes on CPU writes to the CIA. Every CIA access is
        // synchronised to the E clock (6800 bus via /VMA), so sampling them here (E-clock
        // granularity) instead of at every master cycle is equivalent and 10x cheaper. The
        // reboot path through the CPU's RESET instruction is handled in risingEdge (direct
        // call to updateCiaOvlLed after softReset), since it rereads the reset vector within
        // a few cycles and cannot wait for the next E clock.
        updateCiaOvlLed();

        // Next E clock in 10 master cycles. Done AFTER any reset: after a hard reset
        // masterClock == 0, so it rearms at 10 (consistent with internalReset's arm).
        scheduleRel(EventSlot.SLOT_CIAA, 10, EventID.CIA_EXECUTE);
    }

    // =========================================================================
    // Bus state
    // =========================================================================

    private enum BusOwner {
        NONE, REFRESH, DISK, AUDIO0, AUDIO1, AUDIO2, AUDIO3,
        SPRITE0, SPRITE1, SPRITE2, SPRITE3, SPRITE4, SPRITE5, SPRITE6, SPRITE7,
        BPL1, BPL2, BPL3, BPL4, BPL5, BPL6, BPL7, BPL8, COPPER, BLITTER, CPU, BLOCKED
    }

    /**
     * PERF: static cache of BusOwner.values(), indexed with ordinal()+offset in the hot
     * paths of risingEdge() and the DMA arbitration.
     */
    private static final BusOwner[] OWNER_VALUES = BusOwner.values();

    /**
     * Phase (in master cycles) the CIA clock grid is anchored to. See the note on the arm
     * of {@code SLOT_CIAA} in the constructor: it is a phase, and goes together with the
     * point where a CIA write delivers the data to the chip.
     */
    private static final int CIA_ANCHOR = 5;

    /**
     * {@link #CIA_ANCHOR} for this machine. The CIA clock grid follows the point of the E
     * cycle where the CPU's accesses to the CIAs land, so it moves with
     * {@link #eClockSyncPhase()}: on the A1200 an access waits for a sync point two master
     * cycles later and starts two master cycles later after it, and the grid moves by four.
     */
    private int ciaAnchor() {
        return CIA_ANCHOR + 2 * (eClockSyncPhase() - 2);
    }

    // PERF: busOwners as an int[] of ordinals instead of BusOwner[]: Arrays.fill(int[]) is
    // faster than filling a reference array (no card marking) and runs ~15,600 times/s
    // (eolHandler). 0 == BusOwner.NONE.ordinal(), so the int default is NONE.
    // 228, not 227: the NTSC long line (LOL) has one more CCK, index 227.
    private final int[] busOwners = new int[228];
    /**
     * The DMA owner of every CCK of the line, before the CPU: unlike {@link #busOwners}, a CCK
     * the CPU used for a fast access while a DMA held the chip bus keeps the DMA's name here.
     */
    public final int[] dmaOwners = new int[228];


    /**
     * Route of the current CPU access. Computed while the CPU presents the cycle (states
     * S2-S4 of its FSM) and reused at the data transfer (S5/S6). The CPU splits long words
     * into two words beforehand (see AbstractMotorola.scheduleRead/scheduleWriteLong), so
     * Agnus always sees 8/16-bit accesses.
     */
    private Gary.Route cpuCurrentRoute = null;
    private boolean cpuRouteCacheValid = false;
    private int cpuRouteCacheAddress;
    private boolean cpuRouteCacheRead;
    private boolean cpuRouteCacheUds;
    private boolean cpuRouteCacheLds;

    /**
     * "Data transfer done" latch of the current CPU access. It makes sure
     * handleCpuDataTransfer() (with side effects: memory writes, reads with side effects,
     * beam latch) runs ONCE per access, although handleCpuBusCycle() is called at every
     * clock edge.
     */
    private boolean cpuAccessTransferred = false;

    /**
     * The value a CIA read took when E rose, and whether it has been taken. A synchronous
     * access samples the chip at the rising edge of E while the bus cycle only ends at the
     * following falling edge; the transfer hands over this value instead of reading again.
     */
    private int ciaReadSample;
    private boolean ciaReadSampled;

    /** Whether a CIA write has already reached the chip at the rising edge of E. */
    private boolean ciaWriteDone;

    /**
     * PERF: true once the "CPU not on the bus" path of handleCpuBusCycle has cleared the
     * handshake lines and the route cache. handleCpuBusCycle runs twice per master cycle
     * and most of the time the CPU does not want the bus (AS low); the clearing is
     * idempotent while AS stays low, so it is done ONCE when AS falls, and the following
     * idle edges reduce to a check and return. Reset to false as soon as AS rises.
     */
    private boolean cpuBusIdle = false;

    /**
     * PERF: dedicated scratch Routes per bus master; gary.route(...) fills them instead of
     * allocating new ones at every access. Separate instances because CPU, Copper and
     * Blitter are served in the same clock edge. The CPU route lives from S4 to S6 of the
     * bus cycle and is consumed (S6) before the next route() call for the CPU (next S4), so
     * one cpuScratchRoute is enough.
     */
    private final Gary.Route cpuScratchRoute     = new Gary.Route();
    private final Gary.Route copperScratchRoute  = new Gary.Route();

    private boolean copperBusProcessed  = false;

    /**
     * Consecutive cycles in which the Blitter blocked the CPU. Diagnostics only
     * (getArbitrationReason); the CPU's real precedence is {@link #bls}.
     */
    private int cpuBlockedByBlitterCount = 0;

    /**
     * BLS (Blitter Slow down): asserted when the CPU has been waiting for the bus for >= 2
     * CCK. With BLTPRI off, the blitter yields the slot to the CPU while bls stays high.
     */
    private boolean bls = false;
    /** Consecutive CCKs in which the CPU wanted the bus without getting it. */
    private int cpuBusWaitCck = 0;

    /** How many CCKs the CPU waits before a nasty blitter lets it steal a cycle. */
    private static final int BLIT_NASTY_CPU_STEAL_CYCLE_COUNT = 3;

    private boolean lastResetSignal = false;

    /**
     * System /RESET (from Gary) currently asserted: the keyboard holds the machine in reset
     * (CTRL+AMIGA+AMIGA). It tells the falling edge, which resets the CPU once, from the
     * line being held.
     */
    private boolean systemResetActive = false;

    private boolean lastLedState    = true;
    private int     lastBusData     = 0xFFFF;
    private int     lastCiaAPra     = -1;
    private int     lastCiaADdra    = -1;

    // =========================================================================
    // Raster (beam) position
    // =========================================================================

    /** Current horizontal position in system clocks (0 - maxHpos-1). */
    private int hpos;

    /**
     * The software reset has re-anchored the horizontal counter: at the next {@code hpos 1}
     * the interrupted line must be counted. See {@code updateBeam()} and
     * {@code internalReset()}.
     */
    private boolean resetRowPending = false;
    /** The line advance a custom-chip reset brought forward still owes the drives their sync. */
    private boolean resetDriveHsyncPending = false;

    /**
     * Power-on is over. The first time the CPU raises the reset line is the machine's
     * construction, a hard reset where the beam's origin is {@code INITIAL_HPOS} by
     * construction, so that reset re-anchors nothing.
     */
    private boolean poweredOn = false;

    /** Current vertical position in lines (0 - maxVpos-1). */
    private int vpos;

    /**
     * Current interlace field: 0 = even (long) frame, 1 = odd (short) frame. Without
     * interlace it does not alternate: it keeps what the last VPOSW wrote. At POWER-ON it is
     * 1, i.e. LOF = 0 and a SHORT frame (312 lines PAL, 262 NTSC): the state the Kickstart's
     * bootblock runs through, which only a VPOSW changes from then on. The value is in the
     * declaration, not in the reset, because the custom-chip reset does NOT touch it (see
     * {@code internalReset()} and docs/vamigats-import-order.md section 26).
     */
    private int field = 1;

    /**
     * The vsync pulse is in progress. It only makes the display lock fire <b>once</b> per
     * frame.
     */
    private boolean vsyncActive = false;

    /** See {@link #getDisplayRowShift()}: latched at the start of vsync, once per frame. */
    private int displayRowShift = 0;

    /**
     * Maximum number of lines per frame. PAL: 312 (lines 0-311), NTSC: 262 (lines 0-261).
     */
    // Not final: on ECS the mode switches WHILE RUNNING by writing BEAMCON0 (see
    // {@link #beamNtsc}), which reassigns it in the CCK of the write. Set in the constructor
    // (262 NTSC / 312 PAL) and changed ONLY by a BEAMCON0 write that flips the PAL bit.
    private int maxVpos;

    /**
     * System clocks of the <b>short</b> line: 454 SC = 227 CCK. The length of every PAL line
     * and of the short NTSC line.
     */
    private static final int MAXHPOS_SHORT = 454;

    /**
     * Line where the <b>CIA-A</b> TOD pulse falls, the "50 Hz mains".
     *
     * <p>It is not the start of the frame: it is the <b>end of the vsync pulse</b>, line
     * <b>5</b> in PAL (the CIA-A TOD counter increases when the vsync pulse ends). The
     * horizontal offset within the line is modelled in {@link #todIncOffsetCck(int, long)}.
     */
    private static final int CIAA_TOD_VPOS = 5;

    /**
     * Line where the <b>vsync</b> pulse STARTS, and with it the frame the display assembles.
     *
     * <p><b>Not a constant: it depends on LOF, in PAL only.</b>
     * <pre>
     * PAL : HSSTRT  and vpos == 3 and  LOF -> vsync starts
     *       HCENTER and vpos == 2 and !LOF -> vsync starts
     * NTSC: HSSTRT  and vpos == 3 and  LOF -> vsync starts
     *       HCENTER and vpos == 3 and !LOF -> vsync starts
     * </pre>
     * so in PAL the <b>short</b> frame starts vsync <b>one line earlier</b>, while in NTSC the
     * two lines coincide and only the point within the line changes (HSSTRT against HCENTER,
     * the same two {@link #TOD_HOFFSET_CIAA_LONG} and {@link #TOD_HOFFSET_CIAA_SHORT} carry).
     *
     * <p>It matters because <b>the display locks to vsync, not to the vpos wrap</b>: vsync
     * resets the framebuffer row. A line at vpos V therefore lands on row
     * {@code V - vsyncStartLine}, and in PAL with LOF=0 the whole picture moves down one row.
     * See {@link #getDisplayRowShift()}.
     */
    private static final int VSYNC_START_VPOS_PAL_LONG = 3;
    private static final int VSYNC_START_VPOS_PAL_SHORT = 2;
    private static final int VSYNC_START_VPOS_NTSC = 3;

    /** Line where the vsync pulse ENDS: PAL 5, NTSC 6. */
    private static final int VSYNC_END_VPOS_PAL = 5;
    private static final int VSYNC_END_VPOS_NTSC = 6;

    /**
     * Horizontal offset of the <b>CIA-B</b> TOD pulse: the end of hsync.
     */
    private static final int TOD_HOFFSET_CIAB = 35;

    /** Horizontal offset of the CIA-A TOD pulse on a <b>long</b> frame (LOF=1, HCENTER). */
    private static final int TOD_HOFFSET_CIAA_LONG = 132;

    /** Horizontal offset of the CIA-A TOD pulse on a <b>short</b> frame (LOF=0, HSSTRT). */
    private static final int TOD_HOFFSET_CIAA_SHORT = 18;

    /**
     * Internal delay of the TOD counter, in CCK: 12 E clocks = 60 CCK.
     */
    private static final int TOD_INC_DELAY_CCK = 60;

    /**
     * Step of the grid the TOD increment aligns to, in CCK: four E clocks. It is the modulus
     * that makes the position of the pulse <b>different line by line</b>: 227 is not a
     * multiple of 20.
     */
    private static final int TOD_ALIGN_CCK = 20;

    /** Final correction of the TOD increment delay, in CCK, on the A500 board. */
    private static final int TOD_ECLOCK_TOD_CCK = -2;

    /**
     * The same correction on the A1200 board, where Gayle generates the E clock with its CIA
     * accesses two CCK later in the E cycle (see {@link #eClockSyncPhase()}).
     */
    private static final int TOD_ECLOCK_TOD_CCK_GAYLE = 0;

    /**
     * Width of the TOD pulse, in system clocks. Two E-clock periods: the CIA sees its rising
     * edge at exactly one of its ticks.
     */
    private static final int TOD_PULSE_WIDTH_SC = 20;

    /**
     * Horizontal position at power-on: four CCKs before the end of the line, so the first
     * line of the machine lasts four CCKs. One of them is there because the vertical counter
     * advances one CCK into the line, while ours advances at the wrap; the other three place
     * the beam's grid three CCKs after the start of the machine's clock, which is where the
     * chip bus slots and the frame start fall against the CPU in absolute time.
     */
    private static final int INITIAL_HPOS = MAXHPOS_SHORT - 8;

    /**
     * System clocks of the current line: {@link #MAXHPOS_SHORT}, plus one CCK (2 SC) on NTSC
     * long lines (see {@link #lol}).
     */
    private int maxHpos = MAXHPOS_SHORT;

    /**
     * LOL, <i>long line</i>. On an NTSC Agnus the line length <b>alternates</b> between 227
     * and 228 CCK: that is how the NTSC frame averages 227.5 CCK per line (the "half line"
     * of the NTSC signal). It does not exist in PAL and stays {@code false}. It toggles at
     * the end of every line (unless BEAMCON0 has LOLDIS) and applies to the NEXT line.
     *
     * <p>With the NTSC line fixed at 227 CCK the phase between CPU and beam stayed locked on
     * one value for a whole session. A game waiting for VBLANK by polling INTREQR (Out of
     * This World under WHDLoad: {@code move.w #$7FFF,INTREQ} + {@code btst #5,INTREQR})
     * ALWAYS lost the race against Exec's interrupt handler, which cleared the bit before
     * the poll could see it: black screen forever after the WHDLoad splash.
     */
    private boolean lol = false;

    protected final VideoMode mode;

    // =========================================================================
    // Agnus registers
    // =========================================================================

    /**
     * DMACON - DMA Control Register.
     * Bit 15: SETCLR (write only)
     * Bit 14: BBUSY  - Blitter busy (read only)
     * Bit 13: BZERO  - Blitter zero flag (read only)
     * Bit 9:  MASTER - DMA master enable
     * Bit 8:  BPLEN  - Bitplane DMA enable
     * Bit 7:  COPEN  - Copper DMA enable
     * Bit 6:  BLTEN  - Blitter DMA enable
     * Bit 5:  SPREN  - Sprite DMA enable
     * Bit 4:  DSKEN  - Disk DMA enable
     * Bit 3:  AUD3EN - Audio channel 3 DMA enable
     * Bit 2:  AUD2EN
     * Bit 1:  AUD1EN
     * Bit 0:  AUD0EN
     * Bit 10: BLTPRI - Blitter nasty (never yields the bus to the CPU)
     */
    private int dmacon = 0;

    // DMA flags derived from dmacon (+ bitplaneVerticalActive for bitplaneDmaActive),
    // recomputed by updateDmaFlags() at every dmacon write and by
    // updateBitplaneVerticalWindow() for the vertical window. They replace bit tests
    // repeated at every cycle of the bus arbitration (hot path).
    private boolean dmaMasterEnabled    = false;
    private boolean bitplaneDmaEnabled  = false;
    private boolean bitplaneDmaActive   = false;
    private boolean spriteDmaEnabled    = false;
    private boolean diskDmaEnabled      = false;
    private boolean audioDmaEnabled     = false;
    private boolean copperDmaEnabled    = false;
    private boolean blitterDmaEnabled   = false;

    private int lastCompletedFrameField = 0;

    /** REFPTR - DRAM refresh address, stepped by every refresh cycle. */
    private int refptr = 0;

    /** DSKPT - Disk DMA pointer. */
    private int dskpt = 0;

    /**
     * BEAMCON0 - video signal control.
     * Bit 5: PAL/NTSC select (0=NTSC, 1=PAL on ECS; ignored on OCS).
     * Default: 0x0020 for PAL.
     */
    protected int beamcon0 = 0x0020;

    /** BEAMCON0 bit 5: PAL video. Clear = NTSC. */
    private static final int BEAMCON0_PAL_BIT = 0x0020;
    /** BEAMCON0 bit 11: LOLDIS, disables the long/short line alternation. */
    private static final int BEAMCON0_LOLDIS  = 0x0800;

    /**
     * Mode of the <b>beam</b>: NTSC if BEAMCON0's PAL bit is clear.
     *
     * <p><b>Not {@link #mode}</b>. {@code mode} is the machine (the crystal, the Agnus ID read
     * from VPOSR, chosen at construction); this is what the beam is doing <i>now</i>, and on
     * ECS software switches it <b>while running</b> by writing BEAMCON0. On OCS
     * {@link #beamcon0Writable()} is false and the register never changes.
     *
     * <p>It changes three things together: {@link #maxVpos} (312 / 262), the long-line
     * alternation {@link #lol} (NTSC only) and the VSYNC phase. Measured on
     * {@code Agnus/Misc/ntsc4} and {@code ntsc5}, the only two tests of the bank that clear
     * the PAL bit.
     */
    private boolean beamNtsc;

    // =========================================================================
    // Chip ID (VPOSR bits 15-8)
    // =========================================================================

    private static final int ID_OCS_PAL  = 0x00;
    private static final int ID_OCS_NTSC = 0x10;
    // =========================================================================
    // DMA slots (in CCK, i.e. hpos/2)
    // =========================================================================

    // All the fixed (disk/audio/sprite) DMA sits on ODD CCKs. Refresh: 1, 3, 5 and the
    // strobe on the last CCK of the line (strobeCck), beyond the last possible bitplane
    // fetch, so no refresh collides with an overscan fetch.
    private static final int SLOT_REFRESH_1 = 1;
    private static final int SLOT_REFRESH_2 = 3;
    private static final int SLOT_REFRESH_3 = 5;
    /**
     * The fourth refresh slot, the strobe, is the LAST CCK of the line: 226 on a short line
     * and 227 on an NTSC long one. It opens the next line's fixed slots, so a long line
     * moves it along with the line end and leaves CCK 226 free.
     */
    private int strobeCck() {
        return (maxHpos >> 1) - 1;
    }

    /**
     * The CCK, and the master cycle of its first half, in which a DMA transfer last drove
     * Denise's slot of the RGA bus (bitplane or sprite data). A register write that lands in
     * that same CCK is overwritten by the transfer and never reaches Denise.
     */
    private int deniseDmaSlotCck = -1;

    /**
     * Whether the strobe slot that closed the previous line drove a strobe. Merged with a
     * bitplane request it did not, and a line without its strobe does not load the DMAL.
     */
    private boolean lineStrobeDelivered = true;
    private long deniseDmaSlotClock = -1;

    private void markDeniseDmaSlot(int cck) {
        deniseDmaSlotCck = cck;
        deniseDmaSlotClock = masterClock;
    }

    private boolean deniseSlotTakenByDma() {
        return (hpos >> 1) == deniseDmaSlotCck && masterClock - deniseDmaSlotClock < 2;
    }

    /**
     * The cycle the Copper cannot use, three CCKs before the line end: $E0 on a short line,
     * $E1 on an NTSC long one. It moves with the line end like the strobe.
     */
    private int copperBlockedCck() {
        return (maxHpos >> 1) - 3;
    }

    /**
     * CCK at which Paula's DMA requests (DMAL) are latched for the disk and audio slots of
     * the line: the cycle after the strobe slot ({@link #strobeCck()}) that opens the
     * line's fixed DMA slots.
     */
    private static final int DMAL_LATCH_CCK = 0;

    // Disk DMA: 3 slots per line (CCK 7, 9, 11).
    private static final int SLOT_DISK_1 = 7;
    private static final int SLOT_DISK_2 = 9;
    private static final int SLOT_DISK_3 = 11;

    // Audio DMA: 1 slot per channel (CCK 13, 15, 17, 19).
    private static final int SLOT_AUDIO_0 = 13;
    private static final int SLOT_AUDIO_1 = 15;
    private static final int SLOT_AUDIO_2 = 17;
    private static final int SLOT_AUDIO_3 = 19;

    // Sprite DMA: 2 slots per sprite (odd CCKs 21..51).
    // Sprite 0: 21,23; sprite 1: 25,27; ... sprite 7: 49,51.
    private static final int SLOT_SPRITE_0     = 21;

    /**
     * How many CCKs the DECISION of a sprite slot precedes its transfer. Not a tuning:
     * sprites have earlier DMA decisions, their taps in the DMA request shift register sit
     * TWO positions ahead of the disk and audio ones.
     */
    private static final int SPRITE_DECISION_LEAD_CCK = 2;
    private static final int SLOT_SPRITE_7_END = 51;

    // =========================================================================
    // DMA sequencer table (precomputed per-line schedule)
    // =========================================================================
    //
    // Maps every CCK of the line to the type of "fixed" DMA slot (DAS = Disk/Audio/Sprite
    // + Refresh) that occupies it: a data-driven version of Agnus's sequencer, one lookup
    // instead of a chain of comparisons per CCK. The POSITION of the slots is the same on
    // every line; the dynamic conditions (DMACON enable, channel request, sprite
    // phase/VSTOP) stay out of the table: disk and audio evaluate them at allocation, one
    // CCK before the slot (allocateDasSlot), sprites at their decision.
    //
    // The arbitration priority does NOT change: refresh/disk/audio are dispatched BEFORE
    // the bitplanes, sprites AFTER (bitplanes win over sprites where the windows overlap
    // in overscan). The table only recognises the slot type.
    private static final byte DMASLOT_NONE    = 0;
    private static final byte DMASLOT_REFRESH = 1;
    private static final byte DMASLOT_DISK    = 2;
    private static final byte DMASLOT_AUDIO   = 3;
    private static final byte DMASLOT_SPRITE  = 4;

    private static final byte[] DMA_SLOT_TABLE = buildDmaSlotTable();

    private static byte[] buildDmaSlotTable() {
        // 228 entries: the 227 CCKs of the short line plus the extra CCK of the NTSC long
        // line (LOL). The strobe is not in the table: it takes the last CCK of the line,
        // which depends on the line's length (strobeCck).
        byte[] t = new byte[228];
        t[SLOT_REFRESH_1] = DMASLOT_REFRESH;
        t[SLOT_REFRESH_2] = DMASLOT_REFRESH;
        t[SLOT_REFRESH_3] = DMASLOT_REFRESH;
        t[SLOT_DISK_1] = DMASLOT_DISK;
        t[SLOT_DISK_2] = DMASLOT_DISK;
        t[SLOT_DISK_3] = DMASLOT_DISK;
        t[SLOT_AUDIO_0] = DMASLOT_AUDIO;
        t[SLOT_AUDIO_1] = DMASLOT_AUDIO;
        t[SLOT_AUDIO_2] = DMASLOT_AUDIO;
        t[SLOT_AUDIO_3] = DMASLOT_AUDIO;
        // Sprites: odd CCKs in [21, 51] (2 slots per sprite).
        for (int cck = SLOT_SPRITE_0; cck <= SLOT_SPRITE_7_END; cck++) {
            if ((cck & 1) != 0) t[cck] = DMASLOT_SPRITE;
        }
        return t;
    }

    // =========================================================================
    // Light pen
    // =========================================================================

    private boolean vposFrozen = false;
    private int     frozenVpos = 0;
    private int     frozenHpos = 0;

    // ERSY freeze (BPLCON0 bit 1 = external resync). When ERSY is on and NO genlock provides
    // the external sync, Agnus's beam counters stop, with the horizontal counter held at 0.
    // graphics.library 1.3 relies on it to detect a genlock at $FCAF96: it writes
    // BPLCON0=$0102 (ERSY), then reads VHPOSR twice; if HP changed it assumes "genlock
    // present" and sets GfxBase.system_bplcon0=$0102 (ORed into EVERY BPLCON0 of every copper
    // list). Without the freeze every display inherits ERSY+GAUD ($0102), and LED Storm goes
    // black because its copper writes $a302 (ERSY) every frame. There is never a genlock, so
    // ERSY always means sync stopped. HP reads 0 throughout (`ersy2` measures it: $4000
    // repeated), and vpos stands still because `updateBeam` does not advance it.

    /**
     * The freeze is <b>in progress</b>.
     *
     * <p>Not the same as "ERSY is high". It starts at the first line boundary with ERSY high
     * (see {@code updateBeam}) and ends <b>at once</b> when BPLCON0 clears the bit (see
     * {@code setBPLCON0}).
     */
    private boolean syncsStopped = false;

    // =========================================================================
    // Display window and DMA fetch
    // =========================================================================

    /**
     * DIWSTRT - Display Window Start.
     * Bit 15-8: start line (V7-V0).
     * Bit 7-0:  start column (H7-H0), in lores pixels.
     */
    private int diwStrt = 0;

    /**
     * DIWSTOP - Display Window Stop.
     * Bit 15-8: stop line (V7-V0).
     * Bit 7-0:  stop column (H7-H0), in lores pixels.
     */
    private int diwStop = 0;

    private int diwVStart = 0, diwVStop = 0;
    private boolean bitplaneVerticalActive = false;

    /**
     * DDFSTRT/DDFSTOP - Data Fetch Start/Stop: the horizontal window of bitplane DMA fetch,
     * in CCK (hpos/2). Only bits 7-2 count (alignment to 4).
     */
    private int ddfStrt = 0, ddfStop = 0;
    private int ddfCckStart = 0, ddfCckStop = 0;

    private int bplcon1 = 0;
    private int scrollOdd = 0;
    private int scrollEven = 0;

    /** Bitplane pointers (BPLxPT registers, written by the CPU). */
    private final int[] bplPt          = new int[8];

    /** Current bitplane pointers during DMA (updated at every fetch). */
    private final int[] bplPtrInternal = new int[8];

    /** Sprite pointers (SPRxPT registers). */
    private final int[] sprPt          = new int[8];

    /** Current sprite pointers during DMA. */
    private final int[] sprPtrInternal = new int[8];

    /**
     * Latch of the decision taken at the <b>first</b> of a sprite's two DMA slots: whether
     * it fetches ({@link #sprSlotFetch}) and in which phase ({@link #sprSlotDataPhase}).
     *
     * <p><b>The sprite fetch is a SEQUENCER too, not a live predicate.</b> Each sprite has a
     * DMA cycle counter ({@link #sprDmaCycle}), 1 at the first slot and 2 at the second,
     * <b>armed only at slot 0</b>, so between the two slots the decision is <b>not
     * re-evaluated</b>: a SPRxPOS/SPRxCTL write falling between them changes VSTART/VSTOP but
     * not what the second slot fetches. That is exactly what the
     * {@code spritedma/interfere*} tests do.
     */
    private final boolean[][] sprSlotFetch = new boolean[8][2];
    /**
     * The sprite's DMA cycle: 0 = this sprite does not use its slots on this line, 1 = first
     * slot, 2 = second. It puts an inactive sprite <b>to sleep</b>: slot 0 rearms it only
     * while the sprite is in its data phase, so after the POS/CTL fetch on the VSTOP line the
     * sprite does <b>no more DMA</b> until {@code vpos == vstart}.
     */
    private final int[] sprDmaCycle = new int[8];
    /** Phase latched at the DECISION: {@code true} = DATA/DATB, {@code false} = POS/CTL. */
    private final boolean[][] sprSlotDataPhase = new boolean[8][2];

    /**
     * DATA/DATB phase flag of each sprite.
     *
     * true  = DATA/DATB phase - pixel fetch between VSTART and VSTOP (excluded).
     * false = POS/CTL phase   - descriptor fetch, on the VSTOP line.
     *
     * At SPRITE_RESET_LINE (25 PAL) every sprVStop is reset to 25, forcing the frame's first
     * POS/CTL fetch.
     */
    private final boolean[] sprDmaEnabled = new boolean[8];

    /** PAL line where sprite DMA is reset for the new frame. */
    private static final int SPRITE_RESET_LINE = 25;

    /**
     * Decoded VSTART of each sprite (9 bits). Bit 8 comes from SPRxCTL bit 2; bits 7-0 from
     * SPRxPOS bits 15-8.
     */
    private final int[] sprVStart = new int[8];

    /**
     * Decoded VSTOP of each sprite (9 bits). Bit 8 comes from SPRxCTL bit 1; bits 7-0 from
     * SPRxCTL bits 15-8.
     */
    private final int[] sprVStop  = new int[8];

    /**
     * BPL1MOD - modulo of the odd bitplanes (BPL1, BPL3, BPL5). Signed (added to the pointer
     * at the end of the line).
     */
    private int bpl1mod = 0;

    /**
     * BPL2MOD - modulo of the even bitplanes (BPL2, BPL4, BPL6).
     */
    private int bpl2mod = 0;

    /**
     * The modulo the fetch's write-back adds to the pointer is <b>not the live register</b>:
     * it is the one sampled one CCK before the transfer, when the request goes to the DMA
     * addressing logic (bitplane and sprite DMA are decided 1 CCK earlier than the others).
     *
     * <p><b>But it is not a delay: it is an ORDER within the CCK.</b> Within one CCK the
     * sequence is
     * <pre>CPU(X) -> sample(X) -> RGA out(X) -> Copper(X) -> CPU(X+1) -> sample(X+1) -> ...</pre>
     * i.e. within the same CCK <b>the CPU's write precedes the sampling and the Copper's
     * follows it</b>. In this model both fall after the fetch and in the opposite order
     * (first the Copper in {@code copper.fallingEdge()}, then the CPU in
     * {@code handleCpuDataTransferPhase()}), so a single sampling point cannot reproduce
     * both: any uniform delay turns {@code bplmod5/6} green (the Copper leaves the window)
     * and {@code bplmod2/3/4} red (the CPU leaves it too).
     *
     * <p>The pipe reproduces the order instead of a delay: <b>two stages sampled at the top
     * of the CCK</b>, which keep out the previous CCK's Copper write, plus the <b>direct
     * injection</b> of the CPU's write into the input stage, which gets it in time for the
     * next CCK's fetch. The output {@code bplXmodFetch} is the only one
     * {@link #handleBitplaneDMA} reads; {@code bplXmod} stays the live register, the one read
     * back and saved in a savestate.
     *
     * <p>Measured with {@code BplModProbe}, anchored on the pointer (same value = same
     * fetch): the write lands at {@code transfer - 1}, from the Copper and from the CPU
     * alike.
     */
    private int bpl1modFetch = 0, bpl2modFetch = 0;
    private int bpl1modStage = 0, bpl2modStage = 0;

    /** BPLCON0 as the bitplane sequencer sees it: each write reaches it two CCKs later. */
    private int seqBplcon0 = 0;

    // =========================================================================
    // Fetch geometry: what the sequencer fetches, from its BPLCON0 and FMODE
    // =========================================================================

    /**
     * FMODE ($1FC), the fetch width of bitplanes (bits 0-1) and sprites (bits 2-3) and the
     * scan doubling bits (14-15). Only Alice has it; it stays 0 elsewhere.
     */
    protected int fmode;

    /**
     * Index of the fetch width: 0 = 16 bits (FMODE 0), 1 = 32 bits (FMODE 1 or 2),
     * 2 = 64 bits (FMODE 3).
     */
    private int fetchMode;

    /**
     * Fetch unit, the indivisible block of the sequence (8, 16 or 32 CCK), and fetch start, the
     * distance between two fetches of the same plane, as masks of the sequencer counter.
     */
    private int fetchUnitMask = 7, fetchStartMask = 7;

    /** Planes the current block holds (8, 4 or 2): it picks the cycle order and the modulo CCKs. */
    private int fmMaxPlane = 8;

    /** The plane each CCK of the block fetches, 0-based, -1 for a free cycle. */
    private int[] fetchSequence = FETCH_SEQUENCE_8;

    /** Planes really fetched: 0 when the planes BPLCON0 asks for do not fit the block. */
    private int fetchPlanes;

    /**
     * The bitplane and sprite fetch width as the transfers use it: FMODE bits 0-1 and 2-3,
     * which reach the data path two CCKs after the write. 0 is 16 bits; 1 and 3 are 32 and 64
     * bits; 2 (page mode alone) is a 32-bit cycle carrying one word twice.
     */
    private int bplFetchFmode, sprFetchFmode;

    /**
     * A wide bitplane fetch: the long (FMODE 1 and 2) or the quad word (FMODE 3) the pointer
     * lies in. A pointer to the second word of a long reads that word twice; FMODE 2 reads the
     * first word of the long twice.
     */
    private long fetchWideBitplane(int addr, int fm) {
        if (fm == 3) {
            final int base = addr & ~7;
            final int l1 = (addr & 4) != 0 ? base + 4 : base;
            final int l2 = base + 4;
            if ((addr & 2) != 0) {
                final long w1 = dmaReadWord(l1 + 2) & 0xFFFFL;
                final long w2 = dmaReadWord(l2 + 2) & 0xFFFFL;
                return (w1 << 48) | (w1 << 32) | (w2 << 16) | w2;
            }
            return (readChipLongDma(l1) << 32) | readChipLongDma(l2);
        }
        final int base = addr & ~3;
        final long w;
        if ((addr & 2) != 0) {
            w = dmaReadWord(base + 2) & 0xFFFFL;
        } else if ((fm & 2) != 0) {
            w = dmaReadWord(base) & 0xFFFFL;
        } else {
            return readChipLongDma(base);
        }
        return (w << 16) | w;
    }

    private long readChipLongDma(int addr) {
        return ((dmaReadWord(addr) & 0xFFFFL) << 16) | (dmaReadWord(addr + 2) & 0xFFFFL);
    }

    /**
     * The pointer after a wide fetch: the part below the fetch width is dropped and the step
     * and the modulo added.
     */
    private static int advanceWidePointer(int ptr, int mod, int fm) {
        return fm == 3 ? (ptr & ~7) + 8 + mod : (ptr & ~3) + 4 + mod;
    }

    private static final int[] FETCH_UNITS  = { 8, 8, 8, 0, 16, 8, 8, 0, 32, 16, 8, 0 };
    private static final int[] FETCH_STARTS = { 3, 2, 1, 0, 4, 3, 2, 0, 5, 4, 3, 0 };
    private static final int[] FM_MAXPLANES = { 3, 2, 1, 0, 3, 3, 2, 0, 3, 3, 3, 0 };

    private static final int[] FETCH_SEQUENCE_8 = sequence(7, 3, 5, 1, 6, 2, 4, 0);
    private static final int[] FETCH_SEQUENCE_4 = sequence(3, 1, 2, 0);
    private static final int[] FETCH_SEQUENCE_2 = sequence(1, 0);

    private static int[] sequence(int... planes) {
        final int[] s = new int[32];
        Arrays.fill(s, -1);
        System.arraycopy(planes, 0, s, 0, planes.length);
        return s;
    }

    /**
     * Planes BPLCON0 asks the sequencer for: BPU3 (bit 4) alone is eight, BPU3 with any BPU bit
     * is none. Without Alice the bit does not exist.
     */
    private int requestedPlanes(int bplcon0) {
        if (isAGA() && (bplcon0 & 0x0010) != 0) return (bplcon0 & 0x7000) != 0 ? 0 : 8;
        return (bplcon0 >> 12) & 7;
    }

    /** Resolution the sequencer fetches for: 0 lores, 1 hires, 2 super-hires (ECS and Alice). */
    private int fetchResolution(int bplcon0) {
        if (isECS() && (bplcon0 & 0x0040) != 0) return 2;
        return (bplcon0 & 0x8000) != 0 ? 1 : 0;
    }

    /**
     * Recomputes the fetch geometry from the sequencer's BPLCON0 and FMODE.
     *
     * <p>When the planes do not fit the block, bitplane DMA does not truncate them: it fetches
     * nothing at all, so five or six planes in hires (FMODE 0) leave every slot free. Seven
     * planes in lores fetch four without Alice; Denise still draws six, the two high ones
     * keeping their old data.
     */
    private void updateFetchGeometry() {
        final int f = fmode & 3;
        fetchMode = f == 0 ? 0 : f == 3 ? 2 : 1;
        final int res = fetchResolution(seqBplcon0);
        final int k = fetchMode * 4 + res;
        fetchUnitMask = FETCH_UNITS[k] - 1;
        fetchStartMask = (1 << FETCH_STARTS[k]) - 1;
        fmMaxPlane = 1 << FM_MAXPLANES[k];
        fetchSequence = fmMaxPlane == 8 ? FETCH_SEQUENCE_8
                      : fmMaxPlane == 4 ? FETCH_SEQUENCE_4 : FETCH_SEQUENCE_2;
        int planes = requestedPlanes(seqBplcon0);
        if (planes > fmMaxPlane) {
            planes = 0;
        } else if (planes == 7 && fetchMode == 0 && res == 0 && !isAGA()) {
            planes = 4;
        }
        fetchPlanes = planes;
    }

    /**
     * Whether the sequencer counter stands on the last CCK of its block. On Alice the last
     * block of the line ends after eight CCKs whatever the fetch unit.
     */
    private boolean lastOfFetchBlock() {
        return isAGA() && ddfStopping == 2
                ? (bprunCycle & 7) == 7
                : (bprunCycle & fetchUnitMask) == fetchUnitMask;
    }

    /** Whether the CCK {@code cycle} (0-7) of the last block adds the modulo. */
    private boolean modCycle(int cycle) {
        return fmMaxPlane == 8 || (fmMaxPlane == 4 && cycle >= 4) || (fmMaxPlane == 2 && cycle >= 6);
    }

    /** Writes of BPLCON0 still on their way to the sequencer: value and the CCK they land on. */
    private final int[] seqBplcon0Pending = new int[4];
    private final long[] seqBplcon0Due = new long[4];
    private int seqBplcon0Count = 0;

    /**
     * Bitplane requests between the sequencer decision and the fetch. A request decided
     * at CCK c is captured at c+1 and fetched at c+2: {@code bplReqNext} is fetched at the
     * next CCK, {@code bplReqLater} at the one after. Encoded as plane index (bits 0-2) plus
     * {@link #BPLREQ_HIRES} and {@link #BPLREQ_MOD}; {@code -1} means no request.
     */
    private int bplReqNext = -1, bplReqLater = -1;

    /**
     * Whether the disk or audio slot of the next CCK has been allocated. A disk or audio
     * cycle is allocated one CCK before it runs, with DMACON and Paula's request as they
     * are at that point: a DMACON write in the CCK just before the slot neither cancels an
     * allocated cycle nor creates one. See {@link #allocateDasSlot(int)}.
     */
    private boolean dasSlotAllocated;

    /**
     * DSKPT or the audio pointer as the allocation of the pending disk or audio slot captured
     * it. The slot transfers at this address and writes it back plus two, so a DSKPT write
     * that falls between allocation and transfer is lost.
     */
    private int dasSlotPointer;

    /**
     * The modulo left in each of the four RGA slot buffers by the last plain bitplane fetch
     * that went through it, indexed by the absolute CCK modulo 4. A merged slot reuses it.
     */
    private final int[] rgaSlotBplMod = new int[4];
    private static final int BPLREQ_PLANE = 0x07;
    private static final int BPLREQ_HIRES = 0x08;
    private static final int BPLREQ_MOD = 0x10;

    /** true if the system bus in this CCK is taken by a custom chip. */
    private boolean busTakenByCustom = false;

    /**
     * True if the chip bus in this CCK is taken by a DMA of higher priority than the blitter
     * (refresh/disk/audio/bitplane/sprite/copper). Used by the Blitter's null blit (state
     * INTERNAL): its bus-idle micro-ops advance only on a free bus, so contention lengthens
     * BBUSY (the dynamics bbusy tests). The blitter itself does not count: during INTERNAL it
     * does not request the bus, so this flag reflects only higher-priority DMA.
     */
    public boolean isChipBusTakenThisCck() { return busTakenByCustom; }

    // =========================================================================
    // Constructor
    // =========================================================================

    protected AbstractAgnus(Processor processor,
                 Gary gary,
                 RAM chipRAM,
                 RAM slowRAM,
                 ROM rom,
                 Paula paula,
                 AbstractDenise denise,
                 MOS8520 ciaA,
                 MOS8520 ciaB,
                 VideoMode mode) {
        Arrays.fill(busOwners, BusOwner.NONE.ordinal());
        this.processor = processor;
        this.cpu       = processor instanceof AbstractMotorola m ? m : null;
        this.wideBusCpu = processor != null && processor.model() == io.github.zolfrik.amija.cpu.CpuModel.M68020;
        if (cpu != null) {
            // Chip-bus timed banks (A500): chip RAM + mirror $000000-$1FFFFF, slow RAM
            // $C00000-$C7FFFF (if present), $D00000-$DFFFFF (custom + mirror + dummy). Used by
            // TAS for the write drop of the RMW cycle.
            cpu.setChipBusTargetClassifier(a ->
                    a < 0x200000
                    || (slowRAM != null && a >= 0xC00000 && a < 0xC80000)
                    || (a >= 0xD00000 && a < 0xE00000));
            cpu.setChipBusBusyProbe(this::isChipBusTakenThisCck);
            this.cpuBusReq  = cpu.getBusReq();
            this.cpuBusResp = cpu.getBusResp();
        } else {
            // Event-driven processor: the pins are Agnus' own, driven by its event bus.
            this.cpuBusReq  = new BusRequest();
            this.cpuBusResp = new BusResponse();
        }
        this.gary      = gary;
        this.chipRAM   = chipRAM;
        // size() is always a power of 2 (256/512/1024/2048 KB), so size-1 is a valid wrap
        // mask, consistent with the mirror Gary applies for the 68000.
        final int chipMask = chipRAM.size() - 1;
        this.slowRAM   = slowRAM;
        this.rom       = rom;
        this.mode      = mode;
        final int slowSize = slowRAM != null ? slowRAM.size() : 0;
        if (isECS() && chipRAM.size() == 0x80000 && slowSize >= 0x80000) {
            this.dmaMask = slowSize * 2 - 1;
            this.dmaEnd = 0x100000;
        } else if (isECS()) {
            this.dmaMask = chipRAM.size() < 0x100000 ? 0xFFFFF : chipMask;
            this.dmaEnd = chipRAM.size();
        } else {
            this.dmaMask = Math.max(0x7FFFF, chipMask);
            this.dmaEnd = chipRAM.size();
        }
        this.dmaPtrMask = (Integer.highestOneBit(dmaMask) << 1) - 1;
        this.dmaPtrHiMask = dmaPtrMask & 0xFFFF0000;
        // Building the machine is its power-on: dynamic RAM starts with a pattern set by
        // the Agnus address multiplexing, not with zeroes. Alice multiplexes differently.
        if (isAGA()) {
            chipRAM.fillPowerOnPatternAga();
            if (slowRAM != null) slowRAM.fillPowerOnPatternAga();
        } else {
            chipRAM.fillPowerOnPattern(isECS());
            if (slowRAM != null) slowRAM.fillPowerOnPattern(isECS());
        }

        if (mode == VideoMode.NTSC) {
            this.maxVpos   = 262;
            this.beamcon0  = 0x0000;
            this.beamNtsc  = true;
        } else {
            this.maxVpos   = 312;
            this.beamcon0  = 0x0020;
            this.beamNtsc  = false;
        }

        this.copperBusReq  = new BusRequest();
        this.copperBusResp = new BusResponse();
        this.copper = new Copper(copperBusReq, copperBusResp, this);

        this.blitter = new Blitter(this);

        this.paula = paula;
        if (this.paula != null) {
            this.paula.setAgnus(this);
            this.paula.setIplChangedCallback(this::updateCpuIpl);
            this.paula.setDmaconSupplier(() -> this.dmacon);
        }

        this.denise = denise;
        if (this.denise != null) {
            this.denise.setAgnus(this);
        }

        this.ciaA = ciaA;
        this.ciaB = ciaB;
        if (gary != null) {
            if (rom != null)  gary.setRomSize(rom.size());
            // Only Agnus knows the installed Chip RAM: it is the bank its constructor gets.
            // Gary needs it to know where the mirror starts inside the chip select
            // $000000-$1FFFFF, so it is told here together with the Slow RAM rather than by
            // every caller: forgetting setChipRamSize would give a machine mirroring every
            // 512 KB and a Kickstart measuring 512 KB of Chip whatever the configuration.
            gary.setChipRamSize(chipRAM.size());
            gary.setSlowRamPresent(slowRAM != null);
            gary.setSlowRamSize(slowRAM != null ? slowRAM.size() : 0);
            // Alice lives on the A1200 motherboard, whose address decoding is Gayle's.
            gary.setGayleDecode(isAGA());
        }

        // The A500's internal drive has no ID hardware; the A1200's answers like any 3.5" DD.
        this.df0 = new FloppyDrive("DF0",
                isAGA() ? FloppyDrive.DriveType.DD_EXT : FloppyDrive.DriveType.DD_INT);
        this.df1 = new FloppyDrive("DF1", FloppyDrive.DriveType.DD_EXT);
        this.df2 = new FloppyDrive("DF2", FloppyDrive.DriveType.DD_EXT);
        this.df3 = new FloppyDrive("DF3", FloppyDrive.DriveType.DD_EXT);
        // A drive changed from outside the floppy block must be looked at again at once.
        final Runnable floppyChanged = () -> { floppyForce = true; idleCachePrb = -1; };
        df0.setChangeListener(floppyChanged);
        df1.setChangeListener(floppyChanged);
        df2.setChangeListener(floppyChanged);
        df3.setChangeListener(floppyChanged);
        // DF1-DF3 are not physically connected; they answer on the bus as "not present"
        df1.setConnected(false);
        df2.setConnected(false);
        df3.setConnected(false);
        // Source of the write bits: Paula's shift register.
        df0.setWriteBitSource(this::nextDiskWriteBit);
        df1.setWriteBitSource(this::nextDiskWriteBit);
        df2.setWriteBitSource(this::nextDiskWriteBit);
        df3.setWriteBitSource(this::nextDiskWriteBit);
        df0.setShifterSlipSink(this::slipDiskShifterBit);
        df1.setShifterSlipSink(this::slipDiskShifterBit);
        df2.setShifterSlipSink(this::slipDiskShifterBit);
        df3.setShifterSlipSink(this::slipDiskShifterBit);

        this.vpos = 0;
        this.triggerVpos = 0;
        this.hpos = INITIAL_HPOS;

        // Arms the CIAs' E-clock tick as an event. Tests do not call agnus.reset(): arming it
        // at construction makes SLOT_CIAA fire from the first E clock. internalReset rearms
        // it.
        //
        // The anchor's master cycles are a PHASE, not a delay, and the phase is decided by
        // WHERE a write to a CIA register lands. The CIA clock and the E phase a CPU access
        // synchronises to are the same signal (phi2/10), but the model keeps two separate
        // mod-10 counters: this event and AbstractMotorola.eClockCounter.
        //
        // With the CIA write delivered at the end of the synchronous cycle (see
        // maybeTransferFastBankWrite), the 25 class-1 tests of vAmigaTS CIA/CIA have a clear
        // minimum at an anchor of 5-6 (7 reds) against 16 at 9 and 14 at 3/4/7. Between 5
        // and 6 the bank does not discriminate (same outcome test by test): 5 puts the write
        // exactly halfway between two ticks, the point of maximum margin (see
        // docs/vamigats-import-order.md, CIA/CIA class 1 outcomes). The CPU's synchronous
        // cycle is untouched: Memory/Ram/memtim1, which measures the timing of CIA accesses,
        // stays bit-exact.
        //
        // The initial value of eClockCounter (9) makes the synchronous cycle last 16 master
        // cycles and has nothing to do with this phase (see the javadoc of
        // AbstractMotorola.eClockCounter and docs/vamigats-import-order.md, memtim1 outcomes).
        //
        // CiaEClockPhaseTest freezes the invariant.
        scheduleAbs(EventSlot.SLOT_CIAA, masterClock + ciaAnchor(), EventID.CIA_EXECUTE);

        // Arms audio sample production as an event. SLOT_CH0/AUDIO_MIX reschedules itself;
        // this is only the first arm (as for SLOT_CIAA). internalReset rearms it after
        // clearing the scheduler.
        if (this.paula != null) {
            scheduleAbs(EventSlot.SLOT_CH0, masterClock + audioSampleInterval(), EventID.AUDIO_MIX);
        }
    }

    /**
     * Master cycles between two audio output samples: PAL_SYSTEM_CLOCK / OUTPUT_RATE, about
     * 161. Used only for the first arm of SLOT_CH0; Paula computes the following ones exactly
     * (phase accumulator).
     */
    private static long audioSampleInterval() {
        return Math.max(1, 7_093_790L / io.github.zolfrik.amija.custom.audio.JavaSoundAudioOutput.OUTPUT_RATE);
    }

    /**
     * Attaches a Zorro II Fast RAM expansion (AutoConfig), right after the constructor. With
     * the board present, Gary decodes the AutoConfig space ($E80000) so the Kickstart can
     * find it and give it a base address. {@code null} (or an empty board) leaves the base
     * A500 configuration without Fast RAM.
     */
    public void setFastRam(FastRam fastRam) {
        this.fastRam = (fastRam != null && fastRam.present()) ? fastRam : null;
        if (gary != null) {
            gary.unmapFastRam();
            updateAutoconfigPresence();
        }
    }

    /**
     * Attaches one or more hard disk controllers (Zorro II boards with AutoConfig), right
     * after the constructor. Every controller plugged in joins the AutoConfig chain after the
     * Fast RAM and, once the Kickstart has configured it, shows its boot ROM in a 64 KB page.
     */
    public void setHdControllers(java.util.List<HdController> controllers) {
        hdControllers.clear();
        if (controllers != null) {
            for (HdController c : controllers) {
                if (c != null && c.pluggedIn()) {
                    c.setBus(hdBus);
                    hdControllers.add(c);
                }
            }
        }
        if (gary != null) {
            gary.unmapHdControllers();
            updateAutoconfigPresence();
        }
    }

    /** Enables the AutoConfig space if at least one board (Fast RAM or HD) is present. */
    private void updateAutoconfigPresence() {
        if (gary != null) {
            gary.setAutoconfigSpacePresent(autoconfigSpaceAlwaysMapped
                    || fastRam != null || !hdControllers.isEmpty());
        }
    }

    /** True when the AutoConfig space is decoded even with no board waiting in it. */
    private boolean autoconfigSpaceAlwaysMapped;

    /**
     * Machine configuration: decodes the AutoConfig space even when no board waits there, as
     * on a machine whose expansion bus answers the scan with an empty slot. It is a property
     * of the machine, so it survives resets.
     */
    public void setAutoconfigSpaceAlwaysMapped(boolean mapped) {
        autoconfigSpaceAlwaysMapped = mapped;
        updateAutoconfigPresence();
    }

    /**
     * First board of the AutoConfig chain still waiting to be configured (Fast RAM, then the
     * hard disk controllers in order). {@code null} if no board answers in $E8xxxx any more.
     */
    private AutoConfigDevice currentAutoConfigDevice() {
        if (fastRam != null && fastRam.autoconfiguring()) return fastRam;
        for (HdController c : hdControllers) {
            if (c.autoconfiguring()) return c;
        }
        return null;
    }

    /**
     * This machine's AutoConfig boards in chain order: the Fast RAM first (if any), then the
     * hard disk controllers. For the savestate, which must capture their configuration state
     * ({@code state} and {@code baseAddr}) and cannot reach it on its own: the reflective
     * walker does not follow object references.
     */
    public java.util.List<Object> autoConfigDevicesForState() {
        java.util.List<Object> out = new java.util.ArrayList<>();
        if (fastRam != null) out.add(fastRam);
        out.addAll(hdControllers);
        return out;
    }

    /**
     * Rebuilds Gary's Regions for the AutoConfig boards from the (already restored) state of
     * the boards.
     *
     * <p>For loading a savestate. Gary's memory map is a {@code List<Region>}, an object
     * reference the snapshot does not enter: without this step a state saved AFTER
     * AutoConfig restored into a machine with the Fast RAM configured at $200000 but a Gary
     * with nothing at $200000, so those addresses answered as open bus while the Kickstart
     * believed otherwise.
     */
    public void rebuildAutoconfigMapping() {
        if (gary == null) return;
        gary.unmapFastRam();
        gary.unmapHdControllers();
        if (fastRam != null && fastRam.configured()) {
            gary.mapFastRam(fastRam.baseAddr(), fastRam.sizeBytes());
        }
        for (HdController c : hdControllers) {
            if (!c.autoconfiguring()) gary.mapHdController(c.configuredBaseAddr());
        }
        updateAutoconfigPresence();
        cpuRouteCacheValid = false; // the memory map has changed
    }

    /** Maps a board just configured at the base address it received. */
    private void mapConfiguredDevice(AutoConfigDevice dev) {
        if (dev == fastRam) {
            gary.mapFastRam(fastRam.baseAddr(), fastRam.sizeBytes());
        } else if (dev instanceof HdController hd) {
            gary.mapHdController(hd.configuredBaseAddr());
        }
        cpuRouteCacheValid = false; // the memory map has changed
    }

    /** Hard disk controller whose (64 KB aligned) page contains {@code addr}, or null. */
    private HdController hdControllerAt(int addr) {
        int base = addr & 0xFF0000;
        for (HdController c : hdControllers) {
            if (!c.autoconfiguring() && c.configuredBaseAddr() == base) return c;
        }
        return null;
    }

    /** Byte read from Amiga RAM (chip/slow/fast) for the hard disk controllers. */
    private int hdReadByte(int addr) {
        addr &= 0xFFFFFF;
        if (addr < 0x200000) {
            return chipRAM.readByte(addr & (chipRAM.size() - 1));
        }
        if (fastRam != null && fastRam.configured()) {
            int fb = fastRam.baseAddr();
            if (addr >= fb && addr < fb + fastRam.sizeBytes()) {
                return fastRam.ram().readByte(addr - fb);
            }
        }
        if (slowRAM != null && addr >= 0xC00000 && addr < 0xC00000 + slowRAM.size()) {
            return slowRAM.readByte(addr - 0xC00000);
        }
        return 0;
    }

    /** Byte write to Amiga RAM (chip/slow/fast) for the hard disk controllers. */
    private void hdWriteByte(int addr, int value) {
        addr &= 0xFFFFFF;
        if (addr < 0x200000) {
            chipRAM.writeByte(addr & (chipRAM.size() - 1), value);
            return;
        }
        if (fastRam != null && fastRam.configured()) {
            int fb = fastRam.baseAddr();
            if (addr >= fb && addr < fb + fastRam.sizeBytes()) {
                fastRam.ram().writeByte(addr - fb, value);
                return;
            }
        }
        if (slowRAM != null && addr >= 0xC00000 && addr < 0xC00000 + slowRAM.size()) {
            slowRAM.writeByte(addr - 0xC00000, value);
        }
    }

    /**
     * Block transfer {@code src->RAM} for the hard disk controller. When the whole range lies
     * in one bank without wrapping it uses {@code System.arraycopy}; otherwise it falls back
     * to a byte copy, which handles the Chip RAM wrap and unmapped addresses exactly like
     * {@link #hdWriteByte}.
     */
    private void hdWriteBlock(int addr, byte[] src, int srcOff, int len) {
        addr &= 0xFFFFFF;
        if (addr < 0x200000) {
            int local = addr & (chipRAM.size() - 1);
            if (local + len <= chipRAM.size()) { chipRAM.load(local, src, srcOff, len); return; }
        } else if (fastRam != null && fastRam.configured()) {
            int fb = fastRam.baseAddr();
            if (addr >= fb && addr + len <= fb + fastRam.sizeBytes()) {
                fastRam.ram().load(addr - fb, src, srcOff, len); return;
            }
        } else if (slowRAM != null && addr >= 0xC00000 && addr + len <= 0xC00000 + slowRAM.size()) {
            slowRAM.load(addr - 0xC00000, src, srcOff, len); return;
        }
        for (int i = 0; i < len; i++) hdWriteByte(addr + i, src[srcOff + i] & 0xFF);
    }

    /** Block transfer {@code RAM->dst} for the hard disk controller (see {@link #hdWriteBlock}). */
    private void hdReadBlock(int addr, byte[] dst, int dstOff, int len) {
        addr &= 0xFFFFFF;
        if (addr < 0x200000) {
            int local = addr & (chipRAM.size() - 1);
            if (local + len <= chipRAM.size()) { chipRAM.copyTo(local, dst, dstOff, len); return; }
        } else if (fastRam != null && fastRam.configured()) {
            int fb = fastRam.baseAddr();
            if (addr >= fb && addr + len <= fb + fastRam.sizeBytes()) {
                fastRam.ram().copyTo(addr - fb, dst, dstOff, len); return;
            }
        } else if (slowRAM != null && addr >= 0xC00000 && addr + len <= 0xC00000 + slowRAM.size()) {
            slowRAM.copyTo(addr - 0xC00000, dst, dstOff, len); return;
        }
        for (int i = 0; i < len; i++) dst[dstOff + i] = (byte) hdReadByte(addr + i);
    }

    // =========================================================================
    // Main tick
    // =========================================================================

    /**
     * Rising edge of the system clock, once per system clock (7.09 MHz PAL), with the clocked
     * CPU: the chipset's half, then the CPU's.
     */
    public final void risingEdge() {
        chipRisingEdge();
        cpuRisingEdge();
    }

    /**
     * Falling edge of the system clock with the clocked CPU.
     *
     * updateInterrupts() and updateCpuIpl() run BEFORE cpu.fallingEdge(), because the CPU
     * samples its IPL in fallingEdge(): a CIA IRQ raised in this cycle's risingEdge (e.g. a
     * timer A underflow) reaches Paula and the CPU before that sample.
     */
    public final void fallingEdge() {
        chipFallingEdge();
        cpuFallingEdge();
        chipFallingEdgeEnd();
    }

    /**
     * The chipset's part of the rising edge: events, reset line, floppy, Paula and the DMA
     * arbitration of the colour clock (at even {@code hpos}). With an event-driven processor
     * this is the whole rising edge.
     */
    final void chipRisingEdge() {
        masterClock++;

        if (nextTrigger <= masterClock) {
            executeEvents(masterClock);
        }


        // -- CPU RESET instruction --------------------------------------------
        if (cpuBusReq.resetLine && !lastResetSignal) {
            this.softReset();
            // Re-assert OVL/LED AT ONCE after the CIA reset (DDRA back to input -> external
            // pull-up -> /OVL high -> overlay on). OVL/LED is otherwise updated at E-clock
            // granularity in serviceCiaEvent, but the reboot path (RESET; MOVEA.L $4,A0;
            // JMP (A0)) rereads the reset vector within a few cycles and MUST see the ROM
            // mapped at $0; it cannot wait up to 10 cycles for the next E clock.
            updateCiaOvlLed();
        }
        lastResetSignal = cpuBusReq.resetLine;

        // -- CIA timer clock: the 8520s run on the E clock (system clock / 10), a
        // self-rescheduling event on SLOT_CIAA (serviceCiaEvent).

        // -- Floppy -----------------------------------------------------------
        // PERF: work is done only when something can change: a drive is active
        // (motor/seek/selection) or the CIA-B control lines (PRB) have changed. With the
        // drives idle the cost is ~0. Reading PRB has no side effects.
        if (lastCiabPrb < 0) floppySelectSet();
        byte ciab_prb = ciaB != null ? ciaB.peekPortB() : 0;
        boolean prbChanged = (ciab_prb & 0xFF) != lastCiabPrb;
        boolean floppyRun = floppyBusy || prbChanged;

        // The drives run on the colour-clock grid: their cells are counted one whole CCK at
        // a time, in the second half of that CCK (odd hpos), before the CPU access of that
        // half. A CIA cycle writes PRB in the first half, so a control line written in a
        // CCK reaches the drives before any cell of that CCK: the write acts from the start
        // of its CCK. The count is taken at every CCK, running or not, so a drive woken by a
        // write also receives the first half of the write's CCK.
        boolean processFloppy = false;
        int floppyN = 0;
        floppyAccumCycles++;
        if ((hpos & 1) != 0) {
            if (floppyRun) {
                // Between two drive events, with the control lines unchanged, a colour clock
                // of drive work changes nothing: its cycles are banked and handed over when
                // the next event is due (see FloppyDrive.cyclesUntilEvent).
                final int due = floppyBanked + floppyAccumCycles;
                if (!prbChanged && !floppyForce && due < floppyQuietCycles) {
                    floppyBanked = due;
                } else {
                    processFloppy = true;
                    floppyN = floppyAccumCycles;
                    floppyCatchUp();
                    if (prbChanged && (ciab_prb & 0xFF) != appliedCiabPrb) applyFloppyLines(ciab_prb);
                    tickFloppyDrive(df0, floppyN);
                    tickFloppyDrive(df1, floppyN);
                    tickFloppyDrive(df2, floppyN);
                    tickFloppyDrive(df3, floppyN);
                }
            }
            floppyAccumCycles = 0;
        }

        // -- Read shifter with no drive turning -----------------------------
        // With no connected drive selected the floppy data line rests at its pull-up
        // (zeros on the A500), yet Paula's read shift register keeps shifting at the
        // nominal cell rate: the comparator keeps comparing, and a read DMA keeps
        // transferring the words. A drive not connected does not count as selected.
        // With a drive selected but none turning the data line floats, and the shifter
        // runs on noise only while a read DMA is armed; otherwise it stands.
        // The accumulator STANDS STILL while the bits come from somewhere else or the
        // shifter stands: it is this source's own cell fraction, not an absolute grid, and
        // it resumes from the same value when the source runs again.
        if (paula != null) {
            // Selection and motors change only with PRB or through a drive's own update
            // (which drops the cache, see the drives' change listener).
            if ((ciab_prb & 0xFF) != idleCachePrb) {
                idleCachePrb = ciab_prb & 0xFF;
                idleAnySelected = isAnyDriveSelected(ciab_prb);
                idleAnyTurning = isAnySelectedDriveTurning(ciab_prb);
            }
            final boolean anySelected = idleAnySelected;
            boolean idleRuns = anySelected
                    ? !idleAnyTurning && paula.isDiskReadArmed()
                    : !paula.isDiskWriting();
            if (idleRuns && ++idleDiskBitAccum >= IDLE_DISK_CYCLES_PER_BIT) {
                idleDiskBitAccum = 0;
                if (anySelected) paula.shiftInFloatingDiskBit();
                else paula.shiftInDiskBitIdle(0);
            }
        }

        // -- Paula tick (audio channels' period counters only) ------------------
        // Output samples are produced by a self-rescheduling event (SLOT_CH0 / AUDIO_MIX,
        // see paula.serviceAudioSampleEvent). tick(1) advances only the period counters of
        // the ACTIVE audio channels (gated on activeAudioMask): almost free without audio,
        // necessary work for exact audio DMA/interrupt timing with it.
        if (paula != null) paula.tick(1);

        // -- CIA / floppy / interrupt line updates -----------------------------
        if (processFloppy) {
            lastCiabPrb = ciab_prb & 0xFF;
            updateFloppy(ciab_prb, prbChanged);
            // Recompute the "busy" state after applying the edges: it decides whether
            // work is needed in the next cycles.
            floppyBusy = isFloppyDriveBusy(df0) || isFloppyDriveBusy(df1)
                    || isFloppyDriveBusy(df2) || isFloppyDriveBusy(df3);
            floppyForce = false;
            floppyQuietCycles = Math.min(Math.min(quietCycles(df0), quietCycles(df1)),
                                         Math.min(quietCycles(df2), quietCycles(df3)));
        }
        // (The falling edge of CIA-A's TOD is in updateBeam(), next to the rising one:
        // both live on line CIAA_TOD_VPOS.)

        // Contention is reset at the start of every CCK (even hpos), so a DMA taking the CCK
        // blocks the CPU for BOTH system clocks of the CCK: the CPU waits one colour clock
        // at a time. A per-master-cycle reset underestimated contention (1 SC), flipping
        // the phase of the CPU bus and paying the odd-cycle wait on about half the accesses.
        boolean cckEdge = (hpos & 1) == 0;
        int cck = hpos >> 1;
        if (cckEdge) {
            busTakenByCustom = false;
            // The CPU steals a cycle from the Blitter after waiting for the bus long enough,
            // and only with BLTPRI off: the count of consecutive denied CCKs
            // (`cpuBusWaitCck`, reset when the CPU gets the bus) reaches
            // BLIT_NASTY_CPU_STEAL_CYCLE_COUNT. Computed BEFORE the arbitration, from the
            // count of the previous CCKs.
            bls = cpuBusWaitCck >= BLIT_NASTY_CPU_STEAL_CYCLE_COUNT;
            // Blitter BBUSY start latency: the BLTSIZE write arms the blit, but BBUSY comes
            // on 2 CCK later. The countdown advances here (once per CCK), before the blitter
            // bus arbitration below, so when it expires the blit can contend for the
            // current CCK at once.
            if (blitter != null) blitter.tickStartCountdown();
        }

        // -- DMA slots (in CCK = even hpos) -------------------------------------
        if (cckEdge) {
            BusOwner owner = BusOwner.NONE;

            // Under the ERSY freeze Agnus allocates NO DMA cycle. The horizontal counter is
            // held at the start of the line for the whole freeze, so no position matches: no
            // DDFSTRT, no fixed slot, no parity for the Copper to request the bus on. The bus
            // stays with the CPU and the Blitter, which need no position.
            // `Agnus/Registers/VPOS/ersy2` measures it: with the lines running, the ~125 CCK
            // of freeze fetched 16 words per plane, and the picture came out 32 bytes off.
            //
            // The freeze STARTS where the chip's horizontal counter reaches the end of the
            // line (and not on an NTSC long line), which on our CCK grid is SEQ_PHASE before
            // the wrap, not at the BPLCON0 write. An ERSY switched on and off WITHIN the same
            // line freezes nothing: `Agnus/Registers/VPOS/ersy1`, where the Copper raises the
            // bit at hpos $31 and lowers it at $B1 sixteen times in a row.
            if (cck == (maxHpos >> 1) + SEQ_PHASE && !syncsStopped && ersyActive() && !lol) {
                syncsStopped = true;
            }
            final boolean beamFrozen = syncsStopped;

            // Loading the modulo is the FIRST thing of the CCK, before any slot. See the
            // bpl1modFetch field for the two stages and the injection of the CPU write.
            bpl1modFetch = bpl1modStage;
            bpl2modFetch = bpl2modStage;
            bpl1modStage = bpl1mod;
            bpl2modStage = bpl2mod;

            handleBitplaneDrawEvent(cck);

            // Pixel output is INCREMENTAL and not gated by DMA: Denise's shift registers keep
            // shifting even if BPLEN goes off in the middle of a word. See
            // AbstractDenise.bplEvents.
            //
            // This is the FIRST half CCK; the second is in the falling edge, right after the
            // Copper, because the resolution reaches Denise half a colour clock before the
            // rest of BPLCON0. See emitBitplaneHalfCck().
            if (denise != null) denise.emitBitplaneHalfCck();

            // The bitplane request fetched in this CCK (decided two CCKs ago; see
            // bplReqNext) and BPLEN as the sequencer sees it: the value left by the writes of
            // the previous CCK.
            final int bplReq = bplReqNext;
            bplReqNext = bplReqLater;
            bplReqLater = -1;
            dmaconBplSeq = bitplaneDmaEnabled;

            // DMAL: Paula reports the disk and audio requests for this line, but only a line whose
            // strobe arrived loads it; after a lost strobe the previous line's DMAL stays.
            if (cck == DMAL_LATCH_CCK && paula != null && lineStrobeDelivered) paula.latchDmaRequests();

            // Disk and audio cycles are allocated one CCK ahead: this CCK runs the slot
            // allocated at the previous one, and allocates the next. Both happen before the
            // Copper and the CPU of this CCK, so their writes reach the slot after next.
            final boolean dasAllocated = dasSlotAllocated;
            dasSlotAllocated = !beamFrozen && allocateDasSlot(cck + 1);

            // 1. High-priority fixed slots (DAS: refresh/disk/audio); slot type from the
            //    precomputed sequencer table DMA_SLOT_TABLE.
            switch (beamFrozen ? DMASLOT_NONE
                    : cck == strobeCck() ? DMASLOT_REFRESH : DMA_SLOT_TABLE[cck]) {
                case DMASLOT_REFRESH:
                    handleRefreshDMA(cck, bplReq);
                    owner = BusOwner.REFRESH;
                    busTakenByCustom = true;
                    break;
                case DMASLOT_DISK:
                    if (dasAllocated) {
                        if (bplReq >= 0) mergeBitplaneWithDasSlot(bplReq, cck);
                        else handleDiskDMA(cck);
                        owner = BusOwner.DISK;
                        busTakenByCustom = true;
                    }
                    break;
                case DMASLOT_AUDIO:
                    if (dasAllocated) {
                        if (bplReq >= 0) {
                            mergeBitplaneWithDasSlot(bplReq, cck);
                        } else {
                            handleAudioDMA(cck);
                        }
                        owner = OWNER_VALUES[BusOwner.AUDIO0.ordinal()
                                + (cck - SLOT_AUDIO_0) / 2];
                        busTakenByCustom = true;
                    }
                    break;
                default:
                    break;
            }

            // 2. Bitplane DMA.
            //
            // The fetch window is NOT a range of positions: it is a SEQUENCER. The phase is
            // `bprunCycle`, a counter that starts when the machine arms and advances at every
            // CCK while `bprun` is on, not `cck - DDFSTRT`. The two agree while the line runs
            // straight from DDFSTRT to DDFSTOP, and diverge exactly where the
            // `Agnus/Registers/DMACON` bank looks: when BPLEN goes off and on again within
            // the line. See decideBpl().
            //
            // The decision and the fetch are two CCKs apart: the sequencer decides the plane
            // of a slot at CCK c, the pointer is captured at c+1 and the word is fetched at
            // c+2. Here the request decided two CCKs ago is fetched first, then the sequencer
            // decides the one for two CCKs from now. Everything the sequencer reacts to (DIW,
            // BPLEN, DDFSTRT/DDFSTOP, the hard limits) is therefore compared on the same grid
            // as the register writes, and only the fetch itself lags.
            if (bplReq >= 0 && !busTakenByCustom && !beamFrozen) {
                final int plane = bplReq & BPLREQ_PLANE;
                if (DMA_SLOT_TABLE[cck] == DMASLOT_SPRITE
                        && spriteSlotAllocated(cck, spriteDmaSeq, (masterClock >> 1) - 2)) {
                    // The sprite kept its slot at the end of the fetch (bplConflict below).
                    mergeBitplaneWithSpriteSlot(cck, plane);
                } else {
                    handleBitplaneDMA(cck, plane, (bplReq & BPLREQ_HIRES) != 0,
                            (bplReq & BPLREQ_MOD) != 0);
                }
                owner = OWNER_VALUES[BusOwner.BPL1.ordinal() + plane];
                busTakenByCustom = true;
            }
            if (bprun > 0 && !beamFrozen) {
                // Plane count and resolution are the sequencer's own copy of BPLCON0, which
                // every write reaches two CCKs later (queueSequencerBplcon0): live, not latched.
                drainSequencerBplcon0();
                // The RESOLUTION that counts here is the sequencer's copy, not the live one:
                // the fetch mask, the maximum plane count and the fetch sequence change with
                // the same delay as BPU, since they come from the same write through the
                // same pipeline. Read live, a mid-line resolution change came in 4 CCK too
                // early: the whole residue of `DMACON/resmod1-4`, which write
                // `BPLCON0 $A200 -> $2200`.
                // The geometry of the block (its length, the plane order, how many planes fit)
                // follows the same copy, and FMODE on Alice: see updateFetchGeometry().
                boolean hires = (seqBplcon0 & 0x8000) != 0;
                boolean lastOfBlock = lastOfFetchBlock();
                if (dmaconBplSeq) {
                    int plane = fetchSequence[bprunCycle & fetchStartMask];
                    if (plane >= 0 && plane < fetchPlanes) {
                        // The modulo belongs to the last block of the line, which is the
                        // state the sequencer is in when it decides the slot.
                        boolean domod = ddfStopping == 2 && modCycle(bprunCycle & 7);
                        bplReqLater = plane | (hires ? BPLREQ_HIRES : 0) | (domod ? BPLREQ_MOD : 0);
                    }
                }
                if (sequencerClock(cck)) bprunCycle++;
                if (lastOfBlock) {
                    // In this order: a block that ends with ddfStopping at 1 does ONE more
                    // before stopping.
                    if (ddfStopping == 2) bplDmaNormalStop();
                    if (ddfStopping == 1) ddfStopping = 2;
                }
            }

            // Sprite DMA is active only in [SPRITE_RESET_LINE, maxVpos): during the top
            // vertical blank (lines < 25 PAL) and on the last line sprites do NOT fetch.
            // Without this gate a sprite whose VSTOP fell on a line of the top blank (e.g. a
            // cursor whose VSTOP wraps to 3 because the game does not set EV8 when
            // VSTART+height > 255) makes a spurious POS/CTL fetch before the line-25 reset,
            // misaligning the DMA pointer and alternating clean and corrupt frames.
            // The sprite's DECISION falls TWO CCKs before its slot, and with it the WHOLE
            // state machine (VSTART/VSTOP comparison included): sprites have earlier DMA
            // decisions, their taps in the DMA request shift register sit two positions
            // ahead. The DMA bit that counts is the one decided then (see spriteDmaSeq). The
            // decision is taken even when the cycle will not be allocated (bitplane or
            // Blitter take it): only the transfer is gated by DMA. See sprSlotFetch.
            final int sprDecideCck = cck + SPRITE_DECISION_LEAD_CCK;
            if (!beamFrozen && sprDecideCck < DMA_SLOT_TABLE.length
                    && DMA_SLOT_TABLE[sprDecideCck] == DMASLOT_SPRITE) {
                int latchIdx = (sprDecideCck - SLOT_SPRITE_0) / 4;
                int latchSub = ((sprDecideCck - SLOT_SPRITE_0) % 4) / 2;
                if (latchIdx >= 0 && latchIdx <= 7) {
                    if (latchSub == 0) {
                        generateSpritesSlot0(latchIdx);
                    }
                    // The decision also looks at the BITPLANE SEQUENCER: the sprite gives up
                    // its slot while the bitplane fetch is RUNNING, except at the last
                    // sequence step of a stopping fetch. It is not "the bitplane took this
                    // CCK" but the STATE of bprun TWO CCKs earlier: the difference the
                    // `blocked*` tests measure by widening the DDF window over the sprite
                    // slots.
                    final boolean lastBplSeq = lastOfFetchBlock();
                    final boolean bplConflict = bprun != 0 && ddfStopping == 2 && lastBplSeq;
                    sprSlotFetch[latchIdx][latchSub] =
                            sprDmaCycle[latchIdx] != 0 && (bprun != 1 || bplConflict)
                            && !sprScanDoubleSkip(latchIdx);
                    sprSlotDataPhase[latchIdx][latchSub] = sprDmaEnabled[latchIdx];
                    // The DMA cycle advances at the decision, not at the transfer.
                    if (sprDmaCycle[latchIdx] != 0) {
                        sprDmaCycle[latchIdx]++;
                        if (sprDmaCycle[latchIdx] > 2) {
                            sprDmaCycle[latchIdx] = sprDmaEnabled[latchIdx] ? 1 : 0;
                        }
                    }
                }
            }

            // The slot's allocation conditions (vertical blank, sprite DMA decided two CCKs
            // earlier, channel sequencer, and SPREN coming on, which leaves the slot "decided
            // but NOT allocated") all live in spriteSlotAllocated(), because
            // sprPointerWriteIsLost() asks the same question: where there is no slot there is
            // no pointer capture, and the Copper write survives.
            // A slot left decided but not allocated stays free, and the sprite whose slot it
            // was keeps its pointer register tied to it (see the Blitter below).
            int sprUnallocatedIdx = -1;
            if (!busTakenByCustom && DMA_SLOT_TABLE[cck] == DMASLOT_SPRITE) {
                final long sprDecisionCck = (masterClock >> 1) - 2;
                int sprIdx = (cck - SLOT_SPRITE_0) / 4;
                if (spriteSlotAllocated(cck, spriteDmaSeq, sprDecisionCck)) {
                    handleSpriteDMA(cck);
                    owner = OWNER_VALUES[BusOwner.SPRITE0.ordinal() + sprIdx];
                    busTakenByCustom = true;
                } else if (spriteSlotDecidedNotAllocated(cck, spriteDmaSeq, sprDecisionCck)) {
                    sprUnallocatedIdx = sprIdx;
                }
            }

            // 3. Copper.
            //    The Copper is ticked at EVERY CCK. Its parity constraint applies ONLY in the
            //    wake-up states; after a bus denial it retries one CCK later, odd CCKs
            //    included. The even cadence of its fetches comes from its two-CCK steps, not
            //    from a rule: gating it to even CCKs pushed a fetch refused by the blocked
            //    $E0 cycle to the next even CCK, stealing 2 CCK from the CPU. The parity
            //    lives inside the FSM (Copper.risingEdge).
            // A Copper cycle is allocated one CCK before it acts, and COPEN is checked at
            // the allocation: a cycle allocated while COPEN was on still acts after COPEN
            // goes off.
            copperTickEnabled = copperAllocEnabled && !beamFrozen;
            copperAllocEnabled = isCopperEnabled();
            copper.advanceStrobe(copperTickEnabled, copperRequestParity(cck));
            if (isFrameStrobeCck(cck)) {
                copper.armFrameStrobe();
            }
            if (copperTickEnabled) {
                // busFree: no higher-priority DMA has taken the slot and it is not the
                // blocked $E0 cycle. The Copper FSM's idle states (REQ_DMA/WAIT*/SKIP*/JMP*)
                // advance only on a free bus; the memory-reading states
                // (FETCH/MOVE/WAIT_OR_SKIP) assert the request anyway and the grant is
                // decided below.
                final boolean copperBlocked = cck == copperBlockedCck();
                boolean copperBusFree = !busTakenByCustom && !copperBlocked;
                copper.risingEdge(copperBusFree, copperRequestParity(cck), copperBlocked);
                // Fix 1: ensure stale ACK doesn't lead to false ownership if bus is taken.
                copperBusResp.ack = false;

                // If the Copper REQUESTS the bus in the blocked cycle of the line ($E0 = 224
                // on a short line, $E1 = 225 on an NTSC long one) the bus is BLOCKED for
                // everyone: neither the Copper nor the CPU get it. The Copper gets no ack and
                // retries at the next CCK.
                if (copperBlocked && copperBusReq.as) {
                    owner            = BusOwner.BLOCKED;
                    busTakenByCustom = true;
                } else if (!busTakenByCustom) {
                    handleCopperBusCycle();
                    if (copperBusReq.as) {
                        owner = BusOwner.COPPER;
                        busTakenByCustom = true;
                    }
                }
            }

            // 4. Blitter: single-phase micro-program engine.
            //    The blitter runs ONE micro-op per CCK (stepCck): BUS/BUSIDLE micro-ops stall
            //    if the bus is not free, NOTHING ones (the drain of the D pipeline) advance
            //    anyway. So the blitter is ticked EVEN when a higher-priority DMA has the bus
            //    (busTakenByCustom): then busFree=false and only NOTHING ops progress. CPU
            //    precedence follows `bls`: with the CPU waiting for the bus and BLTPRI off,
            //    the blitter yields the slot.
            // The deferred BBUSY clear of line mode matures EVEN after endBlit and must run
            // BEFORE the blitter step, so the countdown armed at LINE_LAST_BUS is processed
            // from the NEXT CCK on.
            if (blitter != null) blitter.tickBusyClear();
            if (isBlitterEnabled() && blitter.isRunning()) {
                boolean cpuWantsBus   = cpuBusReq.as && !cpuBusReq.iack;
                boolean cpuPrecedence = bls && !isBlitterNasty();
                boolean busFree       = !busTakenByCustom && !cpuPrecedence;
                if (blitter.stepCck(busFree)) {
                    owner = BusOwner.BLITTER;
                    busTakenByCustom = true;
                    if (cpuWantsBus) cpuBlockedByBlitterCount++;
                    // A copy-mode channel transfer in a sprite slot left decided but not
                    // allocated: the transfer is the Blitter's, and the sprite's pointer is
                    // then loaded with the modulo of that channel. The sprite gets no data.
                    if (sprUnallocatedIdx >= 0 && blitter.lastCycleWasCopyChannel()) {
                        sprPtrInternal[sprUnallocatedIdx] = blitter.lastCycleChannelModulo();
                    }
                } else if (sprUnallocatedIdx >= 0 && blitter.lastCycleWasIdle()) {
                    // An idle Blitter cycle in that slot names no register, so the sprite's own
                    // transfer goes ahead with no pointer behind it: the word comes from address
                    // 0, and the pointer continues from there. The bus stays free for the CPU.
                    sprPtrInternal[sprUnallocatedIdx] = 0;
                    handleSpriteDMA(cck);
                }
            }

            busOwners[cck] = owner.ordinal();
            dmaOwners[cck] = owner.ordinal();

            // AFTER the arbitration, not before: the DMA requests of the CCK are generated
            // first and the bitplane sequencer decides after. A machine armed at CCK X decides
            // its first slot at CCK X+1, and that slot's word arrives two CCKs later
            // (bplReqNext).
            if (!beamFrozen) decideBpl(cck);

            // The SPRITE DMA bit has a two-stage latch: the DECISION is two CCKs before the
            // transfer.
            spriteDmaSeq = spriteDmaPipe;
            spriteDmaPipe = spriteDmaEnabled;
        }

    }

    /**
     * The clocked CPU's part of the rising edge: the CPU's own edge, then DTACK and the bus
     * bookkeeping that depends on it. Runs after the arbitration of {@link #chipRisingEdge()}.
     */
    private void cpuRisingEdge() {
        final boolean cckEdge = (hpos & 1) == 0;
        final int cck = hpos >> 1;
        // 5. CPU: the 68000's clock never stops.
        //
        // Agnus does not gate the CPU's clock: the CPU advances its internal state at every
        // cycle. What stalls is /DTACK: if DMA has the bus, or a Chip RAM access is not
        // aligned to a CCK boundary, Agnus withholds /DTACK and the CPU waits (states S4-S5
        // repeated) without going further.
        //
        // handleCpuBusCycle() implements it:
        //   - bus taken by DMA       -> cpuBusResp.ack = false  (DMA stall)
        //   - odd hpos + Chip RAM    -> cpuBusResp.ack = false  (CCK sync wait)
        //   - otherwise              -> DTACK granted
        cpu.risingEdge();
        handleCpuBusCycle();

        // Record the CPU as bus owner (on CCKs only) and clear the blitter stall counter
        // when the CPU gets the bus.
        if (cpuBusReq.as && cpuBusResp.ack) {
            cpuBlockedByBlitterCount = 0;
            if (cckEdge) {
                busOwners[cck] = BusOwner.CPU.ordinal();
            }
        }

        // BLS: CCKs in which a pending chip-bus access of the CPU has been denied. Feeds
        // `bls` (computed at the start of the next CCK), updated once per CCK. A
        // synchronous CIA cycle (VPA) waits for the E clock, not for the chip bus: it
        // does not count, and like any access off the chip bus it leaves the count at 0.
        if (cckEdge) {
            boolean cpuWants = cpuBusReq.as && !cpuBusReq.iack && !cpuBusResp.vpa;
            if (cpuWants && !cpuBusResp.ack) cpuBusWaitCck++;
            else                             cpuBusWaitCck = 0;
        }
    }

    /**
     * The chipset's part of the falling edge up to the interrupt lines: Copper, Denise's
     * second half colour clock, interrupts and the processor's IPL.
     */
    final void chipFallingEdge() {
        // Coupled with risingEdge, which ticks the Copper at EVERY CCK: `hpos & 1 == 0`
        // selects every CCK boundary. Odd CCKs matter: the Copper can get the bus there after
        // a denial (e.g. the fetch at 225 after the blocked $E0 = 224). The +2 cadence
        // between successful fetches is set by Copper.tickDelay, not by this gate.
        if ((hpos & 1) == 0 && copperTickEnabled) {
            copper.fallingEdge();
        }

        // SECOND half CCK of Denise, right AFTER the Copper: the resolution part of BPLCON0
        // reaches Denise one RGA stage before the rest, i.e. half a colour clock, which puts
        // the `hilo` switch on the right sub-pixel. See AbstractDenise.emitBitplaneHalfCck.
        if ((hpos & 1) == 0 && denise != null) denise.emitBitplaneHalfCck();
        // The Blitter is single-phase: all its logic (memory access, micro-op advance)
        // happens in stepCck() in risingEdge; it has no falling edge.

        // -- Interrupts updated BEFORE the CPU samples IPL ---------------------
        // The CIA tick already happened in risingEdge. A new /IRQ edge must reach Paula and
        // the CPU before the CPU samples its IPL in fallingEdge().
        updateInterrupts();
        updateCpuIpl();
    }

    /** The clocked CPU's part of the falling edge: its edge (IPL sample) and the transfer. */
    private void cpuFallingEdge() {
        // No second handleCpuBusCycle() here: the 68000's bus FSM advances one state per
        // edge, cpu.fallingEdge() has not run yet, and all the inputs of decideCpuDtack are
        // unchanged since this cycle's risingEdge (busTakenByCustom is written only in
        // risingEdge, hpos advances only in updateBeam()). The falling-edge bus step does not
        // read ack/vpa/berr either.
        cpu.fallingEdge(); // samples the IPL here

        // The data transfer lives on the slot Agnus GRANTED, not half a CCK later. DTACK
        // matures in decideCpuDtack() at an EVEN hpos (CCK boundary) while the CPU is in S4;
        // performing the transfer in the next risingEdge's handleCpuBusCycle() (S6, odd hpos)
        // put it half a CCK after the granted slot, while the Copper and the chip interrupts
        // commit on the boundary: the phase CpuBeamPhaseTest measures (48 odd phi out of 48,
        // all even on the hardware; audio plan section 6.1.1).
        //
        // Called here, after cpu.fallingEdge() (which takes the bus FSM to S5) and BEFORE
        // updateBeam(), the transfer falls in the master cycle of DTACK. The CPU FSM,
        // arbitration and contention do not move: only the instant the transfer's effect
        // becomes visible changes.
        handleCpuDataTransferPhase();
    }

    // =========================================================================
    // Event-driven processor: the chipset half of the bus (see EventCpuBus)
    // =========================================================================

    /** True at the start of a colour clock, where the arbitration of the slot happens. */
    final boolean atCckStart() { return (hpos & 1) == 0; }

    /** True if the slot of the colour clock just arbitrated was taken by a DMA channel. */
    final boolean eventSlotTaken() { return busTakenByCustom; }

    /**
     * The processor waits for the bus in this colour clock: the slot is taken. Feeds the
     * Blitter's count of denied colour clocks.
     */
    final void eventDenied() {
        cpuBusWaitCck++;
        cpuBusReq.as = true;
    }

    /** The processor takes the slot of this colour clock. */
    final void eventGranted() {
        cpuBusWaitCck = 0;
        cpuBlockedByBlitterCount = 0;
        busOwners[hpos >> 1] = BusOwner.CPU.ordinal();
        cpuBusReq.as = false;
    }

    /** The colour clock passes without a processor access. */
    final void eventNoRequest() {
        cpuBusWaitCck = 0;
    }

    /**
     * Performs a processor access on the bus now: routes it and transfers the data with the
     * same paths the clocked processor uses. {@code size} is 1 (byte) or 2 (word); a word
     * at an odd address reads the two bytes at {@code address} and {@code address + 1}.
     * Returns the data read (byte or word), or -1 for a bus error.
     */
    final int eventTransfer(int address, boolean read, int size, int data) {
        if (size == 2 && (address & 1) != 0) {
            if (read) {
                final int hi = eventTransfer(address, true, 1, 0);
                if (hi < 0) return -1;
                final int lo = eventTransfer(address + 1, true, 1, 0);
                if (lo < 0) return -1;
                return (hi << 8) | lo;
            }
            if (eventTransfer(address, false, 1, (data >> 8) & 0xFF) < 0) return -1;
            return eventTransfer(address + 1, false, 1, data & 0xFF);
        }
        final boolean odd = (address & 1) != 0;
        final boolean uds = size == 2 || !odd;
        final boolean lds = size == 2 || odd;
        cpuBusReq.address = address & ~1;
        cpuBusReq.read = read;
        cpuBusReq.uds = uds;
        cpuBusReq.lds = lds;
        cpuBusReq.iack = false;
        cpuBusReq.vma = false;
        // A byte is driven on both lanes of the data bus.
        cpuBusReq.dataOut = size == 2 ? data & 0xFFFF : ((data & 0xFF) << 8) | (data & 0xFF);
        routeCpuCurrentBusRequest();
        final Gary.Route route = cpuCurrentRoute;
        int word;
        if (!route.ok) {
            if (route.busError) return -1;
            // Open bus: the data bus keeps its last value.
            word = read ? lastBusData : 0;
        } else if (route.target == Gary.Target.CIA_A
                || route.target == Gary.Target.CIA_B
                || route.target == Gary.Target.CIA_BOTH) {
            if (read) {
                word = readCiaData(route.target, cpuBusReq.address) & 0xFFFF;
                lastBusData = word;
            } else {
                word = cpuBusReq.dataOut;
                writeCiaData(route.target, cpuBusReq.address, word);
                lastBusData = word;
            }
        } else {
            ciaReadSampled = false;
            ciaWriteDone = false;
            eventCpuWrite = !read;
            try {
                handleCpuDataTransfer();
            } finally {
                eventCpuWrite = false;
            }
            word = read ? cpuBusResp.dataIn & 0xFFFF : 0;
        }
        if (!read) return 0;
        return size == 2 ? word : (odd ? word & 0xFF : (word >>> 8) & 0xFF);
    }

    /** A write of the event-driven processor is being transferred (see setDMACON). */
    private boolean eventCpuWrite;

    /** True if the address is a CIA (the synchronous E-clock bus). */
    final boolean isCiaAddress(int address) {
        return (address & 0xE00000) == 0xA00000;
    }

    /**
     * Phase of the E clock at the event-driven processor's current master cycle, 0..9, on the
     * same scale as the clocked processor's counter: the CIAs tick at 0, a synchronous access
     * samples at 6. The event bus keeps Agnus one rising edge ahead (EventCpuBus), so the
     * processor's master cycle is the one before {@code masterClock}.
     */
    final int eClockPhase() {
        return (int) ((masterClock - 1) % 10);
    }

    /**
     * Current interrupt priority level on the processor's pins, as an event-driven processor
     * sees it when it looks: Paula's level now. The clocked processor's copy
     * ({@code cpuBusResp.ipl}) is latched at its own sampling edge, one master cycle on, and
     * would show a change made at the end of a colour clock only one colour clock later.
     */
    final int eventIpl() { return paula != null ? paula.getCpuIplFromPipe() : cpuBusResp.ipl; }

    /**
     * RESET instruction of an event-driven processor: the rest of the machine is reset at
     * once, with the ROM overlay back on.
     */
    /** The event-driven processor's /RESET output, seen at the next rising edge. */
    final void eventResetLine(boolean asserted) {
        cpuBusReq.resetLine = asserted;
    }

    final void eventResetInstruction() {
        softReset();
        updateCiaOvlLed();
    }

    /** Installed Slow RAM in bytes. */
    final int slowRamBytes() { return slowRAM != null ? slowRAM.size() : 0; }

    /** The end of the falling edge: Copper bus bookkeeping and the beam counters. */
    final void chipFallingEdgeEnd() {
        if (!copperBusReq.as)  copperBusProcessed  = false;

        updateBeam();
    }

    private void scheduleSyncEvent(int flag) {
        syncEvent |= flag;
        scheduleNextRegEvent();
    }

    // =========================================================================
    // TOD pulses
    // =========================================================================

    /** {@code hpos} of the rising/falling edge of the line's TOD pulse; -1 = none. */
    private int todRiseHposA = -1, todFallHposA = -1, todRiseHposB = -1, todFallHposB = -1;

    /**
     * Where the TOD increment falls <b>within the line</b>, in CCK from the start of the
     * line.
     *
     * <p><b>Not a constant, and it cannot be</b>: {@code toddelay1-3} are the same program
     * with the Copper's WAIT at three different hpos, a phase probe, and a sweep over the
     * whole line breaks {@code toddelay2} at 96 CCK and {@code toddelay3} at 114. The
     * increment aligns to a grid of <b>four E clocks</b> ({@link #TOD_ALIGN_CCK}) taken from
     * the <b>absolute</b> CCK counter, and 227 is not a multiple of 20, so the position turns
     * by 7 CCK at every line.
     *
     * <pre>
     * hoff   = hoffset + 1                    // the HSYNC/VSYNC pin is one CCK late
     * div10  = (handler + 3 + hoff) mod 20    // handler = lineStartCck + TOD_HANDLER_LEAD_CCK
     * offset = hoff + 60 + (20 - div10) - 2
     * </pre>
     *
     * <p>The increment lands on a rigid 20-CCK grid of absolute time, and its position within
     * the line is only the relative reading of it. The increment lands at horizontal position
     * {@code offset}; {@link #todPulseRiseHpos(int)} turns it into our {@code hpos}.
     *
     * @param hoffset      the pulse source's offset within the line (hsync or vsync end)
     * @param lineStartCck the absolute CCK of our wrap of {@code hpos}
     */
    private int todIncOffsetCck(int hoffset, long lineStartCck) {
        final int hoff = hoffset + 1;
        // The alignment grid is taken at the instant the line's CIA handler runs, which is
        // TOD_HANDLER_LEAD_CCK after our wrap of hpos on the absolute CCK counter.
        final int div10 = (int) Math.floorMod(lineStartCck + TOD_HANDLER_LEAD_CCK + 3 + hoff, TOD_ALIGN_CCK);
        return hoff + TOD_INC_DELAY_CCK + (TOD_ALIGN_CCK - div10)
                + (isAGA() ? TOD_ECLOCK_TOD_CCK_GAYLE : TOD_ECLOCK_TOD_CCK);
    }

    /**
     * Half-CCK phase of the E cycle at which a CPU access to a CIA starts its E-clock
     * cycle: 2 on the A500 board, 4 on the A1200 one, where Gayle drives the CIA bus.
     */
    public final int eClockSyncPhase() {
        return isAGA() ? 4 : 2;
    }

    /**
     * Absolute CCKs between our wrap of {@code hpos} and the instant the line's CIA handler runs,
     * the point from which the TOD increment offset is counted. The handler runs at horizontal
     * position 1, three CCKs before our wrap: the TOD grid is 20 CCKs long, so the alignment has
     * to be read there and not at the wrap.
     */
    private static final int TOD_HANDLER_LEAD_CCK = -3;

    /**
     * CCKs to subtract from a TOD increment offset to obtain our CCK within the line: the
     * increment lands at horizontal position {@code offset}, and horizontal position {@code p}
     * is our CCK {@code p - 4}.
     */
    private static final int TOD_OFFSET_TO_CCK = 4;

    /**
     * Turns a TOD increment offset, the horizontal position the increment lands at, into our
     * {@code hpos}: horizontal position {@code p} is our CCK {@code p - 4}, so the CCK is
     * {@code offset - TOD_OFFSET_TO_CCK} and {@code hpos} counts system clocks.
     *
     * @return -1 if the pulse does not fit in the line. In PAL and NTSC this <b>cannot
     *         happen</b>: the offset is at most 114 CCK for CIA-B and 211 for CIA-A against
     *         the line's 227. It would take a line programmed through {@code BEAMCON0}, which
     *         is not emulated.
     */
    private int todPulseRiseHpos(int offsetCck) {
        final int rise = 2 * (offsetCck - TOD_OFFSET_TO_CCK);
        return (rise < 1 || rise + TOD_PULSE_WIDTH_SC >= maxHpos) ? -1 : rise;
    }

    /**
     * <b>Vsync</b> lock at the start of the line: raises and lowers {@link #vsyncActive} and,
     * on the rising edge, latches {@link #displayRowShift} for the whole frame.
     *
     * <p>The latch is the point: {@code vhpos4} writes VPOSW <b>after the frame has
     * started</b>, and recomputing the shift line by line would break the picture in two.
     * The framebuffer row is reset once, when vsync goes from false to true, and from then
     * on the frame is anchored there whatever LOF does.
     */
    private void updateVsyncPhase() {
        final boolean pal = !beamNtsc;
        final int startVpos = pal
                ? ((field == 0) ? VSYNC_START_VPOS_PAL_LONG : VSYNC_START_VPOS_PAL_SHORT)
                : VSYNC_START_VPOS_NTSC;
        final int endVpos = pal ? VSYNC_END_VPOS_PAL : VSYNC_END_VPOS_NTSC;

        if (!vsyncActive && vpos == startVpos) {
            vsyncActive = true;
            // The framebuffer row counts from vsync; the crop constants are set for the
            // LONG frame, where vsync starts at VSYNC_START_VPOS_PAL_LONG.
            displayRowShift = VSYNC_START_VPOS_PAL_LONG - startVpos;
        } else if (vsyncActive && vpos == endVpos) {
            vsyncActive = false;
        }
    }

    /**
     * How many rows the picture moves down in the framebuffer against {@code vpos}.
     *
     * <p><b>0</b> almost always and <b>+1</b> on a short PAL frame, because there vsync, which
     * the display locks to, starts one line earlier (vpos 2 instead of 3): see
     * {@link #VSYNC_START_VPOS_PAL_LONG}. Measured on the {@code Agnus/Registers/VPOS} bank:
     * {@code lof1} (VPOSW $8001, long frame) is bit-exact with {@code shift = 0}, {@code lof2}
     * (VPOSW $0001, short frame) with {@code shift = +1}: the picture starts at row 23
     * instead of 22.
     */
    public int getDisplayRowShift() {
        return displayRowShift;
    }

    /** Computes the TOD pulses of the line that starts. */
    private void armTodPulses(long lineStartCck) {
        todRiseHposB = todPulseRiseHpos(todIncOffsetCck(TOD_HOFFSET_CIAB, lineStartCck));
        todFallHposB = todRiseHposB < 0 ? -1 : todRiseHposB + TOD_PULSE_WIDTH_SC;

        if (vpos == CIAA_TOD_VPOS) {
            // LOF ? 132 : 18; LOF=1 is field==0 (see longFrame in updateBeam).
            final int hoffset = (field == 0) ? TOD_HOFFSET_CIAA_LONG : TOD_HOFFSET_CIAA_SHORT;
            todRiseHposA = todPulseRiseHpos(todIncOffsetCck(hoffset, lineStartCck));
            todFallHposA = todRiseHposA < 0 ? -1 : todRiseHposA + TOD_PULSE_WIDTH_SC;
        } else {
            todRiseHposA = -1;
            todFallHposA = -1;
        }
    }

    // =========================================================================
    // Raster update
    // =========================================================================

    /**
     * Advances the raster position by 1 system clock.
     *
     * Handles:
     *   - end of line: hpos reset, vpos increment;
     *   - end of frame: vpos reset, VERTB, COPJMP1 (the DMA pointers are counters: nothing
     *     reloads them);
     *   - the TOD pulses of the two CIAs.
     */
    private void updateBeam() {
        int nextHpos = hpos + 1;
        hpos = nextHpos;

        // The custom-chip reset RE-ANCHORS the horizontal counter at the instant of the
        // reset: the current line ends there and its remaining CCKs vanish, while the vertical
        // counter advances as at a normal line boundary, one CCK later. Measured on the
        // ColdReboot of the demo Jesus On E's: reset at hpos 155 of 227, and the frame lasts
        // 70 979 CCK instead of 71 051, 72 fewer (docs/vamigats-import-order.md section 26).
        if (resetRowPending && nextHpos == 1) {
            resetRowPending = false;
            resetDriveHsyncPending = true;
            advanceTriggerVpos();
            advanceRow();
        }

        // -- TOD pulses to the two CIAs -----------------------------------------
        // WHICH line carries the pulse is decided by the source (CIA-B: every hsync; CIA-A:
        // the end of vsync, line CIAA_TOD_VPOS). WHERE it falls within the line is computed
        // by todIncOffsetCck(). The arm is at nextHpos == 1, one system clock after the
        // wrap, where the line's CIA handler runs.
        if (nextHpos == 1) {
            armTodPulses((masterClock - 1) >> 1);
        }
        if (ciaB != null) {
            if (nextHpos == todRiseHposB) {
                ciaB.getPinSet().getTodPin().setExternalInput(true);
                ciaB.incrementTod();
            } else if (nextHpos == todFallHposB) {
                ciaB.getPinSet().getTodPin().setExternalInput(false);
            }
        }
        if (ciaA != null) {
            if (nextHpos == todRiseHposA) {
                ciaA.getPinSet().getTodPin().setExternalInput(true);
                ciaA.incrementTod();
            } else if (nextHpos == todFallHposA) {
                ciaA.getPinSet().getTodPin().setExternalInput(false);
            }
        }

        // The line counter that the hardware exposes advances THREE CCKs before our wrap of
        // hpos. See TRIGGER_VPOS_LEAD_HPOS.
        if (nextHpos == maxHpos - TRIGGER_VPOS_LEAD_HPOS) {
            advanceTriggerVpos();
        }
        if (nextHpos == maxHpos - TRIGGER_VPOS_LEAD_HPOS + 1
                || (resetDriveHsyncPending && nextHpos == 2)) {
            resetDriveHsyncPending = false;
            hsyncDrives();
        }

        if (nextHpos < maxHpos) {
            // Still within the same line: nothing to do
            return;
        }

        // -- End of line --------------------------------------------------------

        // THE BITPLANE DRAW DOES NOT STOP AT THE LINE BOUNDARY. The grid of draw events
        // continues into the next line, but the first sub-pixels coming out of it still
        // belong to THIS line's buffer: raw columns 836-839 (CCK 227), the same cell as the
        // wrap column of section 16 (modelled for the border by `VirtualCRT.borderCck0Rgb`).
        // With a large scroll the final block falls right there: `BPLCON1/overscan` writes
        // BPLCON1 at high DMA cycles precisely to put it across the boundary, and without
        // this event that word never comes out (one pixel per line on 21 lines of each of
        // the twelve tests). ONE CCK only: `rgbLine` is 912 = 228 CCK wide from CCK 18, so
        // CCK 227 is its last cell.
        handleBitplaneDrawEvent(maxHpos >> 1);

        scheduleSyncEvent(EVFL_HSYNC);
        scheduleSyncEvent(EVFL_EOL);

        // LOL: on an NTSC Agnus the line alternates 227/228 CCK. The toggle applies to the
        // line about to start, only with NTSC *and* LOLDIS clear; otherwise the line goes
        // back to short and stays there. BEAMCON0 decides the mode, not the machine (see
        // beamNtsc).
        updateLineLength(beamNtsc && (beamcon0 & BEAMCON0_LOLDIS) == 0 && !lol);

        hpos = 0;

        // Unlock the light pen latch after line 20 (the Kickstart uses vpos >= 20 as the
        // freeze reset condition).
        if (vpos == 20) vposFrozen = false;

        // The bitplane moduli are not applied at the end of the line: they are added per
        // plane at the LAST fetch of the line (handleBitplaneDMA), so a line that fetched
        // nothing applies no modulo. (Pinball Fantasies: at vpos 72 the Copper loads the
        // table's pointers and moves DDFSTRT; that line does not fetch, and a modulo applied
        // anyway took BPL1 120 bytes past its data.)

        // ERSY (BPLCON0 bit 1) without a genlock: Agnus's sync generator waits for an
        // external sync that never comes and the VERTICAL COUNTER STOPS, while hsyncs keep
        // coming so the lines, and the rest of the machine, do not stop. The end of line does
        // all its work; only vpos does not advance and the frame does not close.
        //
        // graphics.library 1.3 switches ERSY on for the genlock detection ($FCAF96) and keeps
        // it for ~4881 CCK, 21.5 lines. Without the freeze the beam ran ahead by as much, and
        // every raster-locked wait of the Kickstart fell elsewhere
        // (docs/vamigats-paula-audio-plan.md section 13.6).
        if (syncsStopped) {
            return;
        }

        advanceRow();
    }

    /**
     * Advances the vertical counter by one line and closes the frame when it ends. The normal
     * tail of the end of line; the software reset calls it too, re-anchoring the horizontal
     * counter and counting the interrupted line (see {@code resetRowPending}).
     */
    private void advanceRow() {
        vpos++;

        // Lines per frame depend on LOF (Long Frame):
        //  - LONG frame (LOF=1) = maxVpos+1 lines -> vpos reaches maxVpos (PAL 312, NTSC 262);
        //  - SHORT frame = maxVpos lines -> vpos at most maxVpos-1.
        // Some games (e.g. Future Wars) poll VPOSR for vpos==312, which happens on long
        // frames.
        boolean longFrame = field == 0;
        int wrapAt = longFrame ? (maxVpos + 1) : maxVpos;

        if (vpos < wrapAt) {
            updateVsyncPhase();
            updateSpriteDMA(vpos);
            return;
        }

        // -- End of frame (start of line 0) ---------------------------------------
        vpos = 0;
        frameCount++;
        updateVsyncPhase();

        // VERTB (Paula INTREQ bit 5, IPL 3): asserted at the start of line 0. NOT earlier at
        // line 311: that runs the game's vblank ISR while the beam is on line 312, so a main
        // loop polling for vpos==312 (e.g. Future Wars' protection, $C088B2:
        // cmp.l #$00013800,(VPOSR)) never sees it and hangs. With VERTB at line 0 the ISR
        // runs on the first lines and the main loop has the rest of the frame (line 312
        // included, on long frames) to sample the beam position.
        if (paula != null) paula.assertVertbInterrupt();
        lastCompletedFrameField = field;   // saved before the toggle, used by VirtualCRT

        // New frame: every sprite in the POS/CTL phase (sprDmaEnabled=false).
        Arrays.fill(sprDmaEnabled, false);

        updateSpriteDMA(0);

        // Tell the Copper about the vertical blank (it restarts from COP1LC at beam 0,0)
        copper.onVBlank(isCopperEnabled());

        // LOF (Long Frame) toggles ONLY in interlace, at the end of the frame. Without
        // interlace the bit does NOT toggle: field keeps its value, which is 1 from reset
        // (LOF=0, SHORT frame) and becomes 0 when software writes VPOSW with bit 15.
        // Toggling it every frame made the frame length alternate 313/312 and with it the
        // CPU/raster phase: a period-2 flicker in raster-synchronised programs (e.g. bbusy).
        boolean interlaced = denise != null && (denise.getBplcon0() & 0x0004) != 0;
        if (interlaced) {
            field = (field == 0) ? 1 : 0;
            denise.setField(field);
        } else {
            if (denise != null) denise.setField(0);
        }

    }

    // =========================================================================
    // CIA lines
    // =========================================================================

    /**
     * Updates the lines derived from CIA-A's PRA/DDRA latch:
     *
     * OVL (ROM overlay): PA0 drives the /OVL line to Gary.
     *   PA0=1 -> overlay on (ROM visible at $000000).
     *   PA0=0 -> overlay off (Chip RAM at $000000).
     *
     * LED / audio filter: PA1 drives the power LED and the audio filter.
     *   PA1=1 -> LED off, filter off; PA1=0 -> LED on, filter on.
     *
     * Called at E-clock granularity by {@link #serviceCiaEvent()} (the bits change only on
     * CIA writes, which are synchronised to the E clock) and, for the reboot path through the
     * CPU's RESET instruction, directly by {@link #risingEdge()} after softReset.
     */
    private void updateCiaOvlLed() {
        if (ciaA == null || gary == null) return;

        CIAPinSet pinsA = ciaA.getPinSet();
        int pra  = pinsA.readPortData(0) & 0xFF;
        int ddra = ciaA.peekDataDirectionA() & 0xFF;

        if (pra != lastCiaAPra || ddra != lastCiaADdra) {
            lastCiaAPra = pra;
            lastCiaADdra = ddra;
            // On the A1200 PA0 drives nothing: Gayle owns the overlay (see Gary.route).
            final boolean paOwnsOvl = !gary.isGayleDecode();

            // OVL: controlled by CIA-A PA0, which drives the /OVL line to Gary.
            //   - As an OUTPUT (DDRA bit0 = 1) the line reflects the PRA latch
            //     (PA0=1 -> overlay ON, PA0=0 -> overlay OFF).
            //   - As an INPUT (DDRA bit0 = 0, e.g. right after a CIA reset caused
            //     by the CPU RESET instruction) the external pull-up makes the
            //     line read HIGH -> overlay ON. Modelling this pull-up is what lets
            //     the reboot path (`RESET; MOVEA.L $4,A0; JMP (A0)`) re-map the ROM
            //     at $0 and read the reset vector, instead of jumping to stale
            //     ExecBase/RAM contents and faulting forever (endless guru blink).
            boolean ciaOvlEnabled = (ddra & 0x01) == 0 || (pra & 0x01) != 0;

            if (paOwnsOvl && ciaOvlEnabled != gary.isRomOverlayEnabled()) {
                gary.setRomOverlay(ciaOvlEnabled);
            }

            // LED / Audio filter
            boolean ledState = (pra & 0x02) != 0;
            if (ledState != lastLedState) {
                lastLedState = ledState;
                if (paula != null) {
                    paula.setLedFilterEnabled(!ledState);
                }
            }
        }
    }

    // =========================================================================
    // Interrupt
    // =========================================================================

    /**
     * Updates INTREQ's PORTS and EXTER bits from the state of the CIA IRQ pins:
     *   - CIA-A /IRQ low -> sets INTREQ PORTS (bit 3)
     *   - CIA-B /IRQ low -> sets INTREQ EXTER (bit 13)
     *
     * The INTREQ bit is NOT cleared when the CIA releases its IRQ: software clears it by
     * writing INTREQ (CLR). INTREQ is latched.
     */
    private void updateInterrupts() {
        if (paula == null) return;

        // CIA interrupts are level-triggered through Paula: while a CIA's /IRQ pin is low
        // the INTREQ bit is held at 1, and if software clears it while the pin is still low
        // it comes back.
        if (ciaA != null) {
            boolean ciaAIrq = ciaA.getPinSet().getIrqPin().getPinState();
            if (!ciaAIrq && !paula.isPortsInterruptAsserted()) {
                paula.assertPortsInterrupt();
            }
        }

        if (ciaB != null) {
            boolean ciaBIrq = ciaB.getPinSet().getIrqPin().getPinState();
            if (!ciaBIrq && !paula.isExterInterruptAsserted()) {
                paula.assertExterInterrupt();
            }
        }
    }

    private void updateCpuIpl() {
        if (paula != null) {
            // The CPU's IPL pin sees Paula's IPL pipe (stage 0, see getCpuIplFromPipe),
            // driven by Paula.checkInterrupt() at every INTREQ/INTENA write and by
            // updateInterrupts() for the CIA /IRQ edges.
            cpuBusResp.ipl = paula.getCpuIplFromPipe();
        }
    }

    // =========================================================================
    // Floppy
    // =========================================================================

    /** The CIA-B control lines reach the drives: selection, motor latch, step, side. */
    private void applyFloppyLines(byte ciab_prb) {
        if (ciaA == null || ciaB == null) return;
        appliedCiabPrb = ciab_prb & 0xFF;
        boolean step = (ciab_prb & 0x01) == 0; // /STEP LOW = step
        boolean dir  = (ciab_prb & 0x02) != 0; // DIR: 0=INWARD (tk++), 1=OUTWARD (tk0)
        boolean side = (ciab_prb & 0x04) == 0; // /SIDE LOW = side 1
        boolean mtr  = (ciab_prb & 0x80) == 0; // /MTR LOW = motor ON request
        df0.update((ciab_prb & 0x08) == 0, mtr, step, dir, side);
        df1.update((ciab_prb & 0x10) == 0, mtr, step, dir, side);
        df2.update((ciab_prb & 0x20) == 0, mtr, step, dir, side);
        df3.update((ciab_prb & 0x40) == 0, mtr, step, dir, side);
    }

    /**
     * Publishes the selected drives' status lines on the CIA pins.
     *
     * @param prbChanged true if CIA-B's PRB changed since the last value processed: then
     *        selection and pull-ups may have changed too.
     */
    private void updateFloppy(byte ciab_prb, boolean prbChanged) {
        if (ciaA == null || ciaB == null) return;

        // Control lines are active LOW in PRB (their edges went out in applyFloppyLines)
        boolean sel0 = (ciab_prb & 0x08) == 0; // /SELx LOW = selected
        boolean sel1 = (ciab_prb & 0x10) == 0;
        boolean sel2 = (ciab_prb & 0x20) == 0;
        boolean sel3 = (ciab_prb & 0x40) == 0;

        // PERF: the five status lines (/RDY /TK0 /WPRO /CHNG /INDEX) stay constant for
        // millions of cycles with the motor up to speed during a load, so republishing them
        // can be skipped. Each drive raises its flag (FloppyDrive.isStatusDirty) when one of
        // the five MAY have changed, conservatively: it stays raised through the whole motor
        // ramp, where /RDY depends on time and not on a field write. With PRB unchanged,
        // selection and pull-ups are unchanged too, so there is nothing to republish.
        boolean anyDirty = (sel0 && df0.isStatusDirty()) || (sel1 && df1.isStatusDirty())
                        || (sel2 && df2.isStatusDirty()) || (sel3 && df3.isStatusDirty());
        if (!prbChanged && !anyDirty && !FLOPPY_STATUS_VERIFY) {
            return;
        }

        // State signals multiplexed on the bus (wired-OR active LOW logic)
        // Reuses the preallocated instance instead of allocating a new one.
        FloppyStatus status = floppyStatus;
        status.rdy = status.tk0 = status.wpro = status.chng = status.index = false;
        boolean anySelected = false;
        if (sel0) { updateFromDrive(df0, status); anySelected = true; }
        if (sel1) { updateFromDrive(df1, status); anySelected = true; }
        if (sel2) { updateFromDrive(df2, status); anySelected = true; }
        if (sel3) { updateFromDrive(df3, status); anySelected = true; }

        CIAPinSet pinsA = ciaA.getPinSet();
        CIAPinSet pinsB = ciaB.getPinSet();

        // If a drive is selected, it guides the lines. If no drive is selected, the lines
        // return to pull-up HIGH (inactive).
        if (anySelected) {
            pinsA.setExternalInput(0, 5, !status.rdy);   // /RDY
            pinsA.setExternalInput(0, 4, !status.tk0);   // /TK0
            pinsA.setExternalInput(0, 3, !status.wpro);  // /WPRO
            pinsA.setExternalInput(0, 2, !status.chng);  // /CHNG

            pinsB.getFlagPin().setExternalInput(!status.index); // /INDEX (CIA-B FLAG)
        } else {
            // No drive selected: all status lines pull up to HIGH (inactive)
            pinsA.setExternalInput(0, 5, true); // /RDY
            pinsA.setExternalInput(0, 4, true); // /TK0
            pinsA.setExternalInput(0, 3, true); // /WPRO
            pinsA.setExternalInput(0, 2, true); // /CHNG
            pinsB.getFlagPin().setExternalInput(true); // /INDEX
        }

        // Published: the selected drives start "clean" again.
        if (sel0) df0.clearStatusDirty();
        if (sel1) df1.clearStatusDirty();
        if (sel2) df2.clearStatusDirty();
        if (sel3) df3.clearStatusDirty();

        // Exhaustive check (-Damiga.floppyverify=true): when the skip WOULD have fired, the
        // publication just computed must match the last one. A single mismatch means a
        // mutation site that is not marked.
        if (FLOPPY_STATUS_VERIFY) {
            int packed = (anySelected ? 1 : 0)
                    | (status.rdy ? 2 : 0) | (status.tk0 ? 4 : 0) | (status.wpro ? 8 : 0)
                    | (status.chng ? 16 : 0) | (status.index ? 32 : 0);
            boolean wouldSkip = !prbChanged && !anyDirty;
            if (wouldSkip && lastPublishedFloppyStatus != -1 && packed != lastPublishedFloppyStatus) {
                floppyVerifyMismatches++;
                System.out.printf("[FLOPPYVERIFY] MISMATCH #%d cyc=%d prev=%02X now=%02X%n",
                        floppyVerifyMismatches, masterClock, lastPublishedFloppyStatus, packed);
            }
            lastPublishedFloppyStatus = packed;
        }
    }

    /** Exhaustive check of the floppy line invalidation (diagnostics). */
    private static final boolean FLOPPY_STATUS_VERIFY = Boolean.getBoolean("amiga.floppyverify");
    private int  lastPublishedFloppyStatus = -1;
    private long floppyVerifyMismatches;

    /** Number of mismatches found in {@code -Damiga.floppyverify} mode. */
    public long getFloppyVerifyMismatches() { return floppyVerifyMismatches; }

    private void updateFromDrive(FloppyDrive df, FloppyStatus status) {
        if (df.isReadyRaw())         status.rdy   = true;
        if (df.isTrack0Raw())        status.tk0   = true;
        if (df.isWriteProtectedRaw())status.wpro  = true;
        if (df.isDiskChangedRaw())   status.chng  = true;
        if (df.isIndexActiveRaw())   status.index = true;
    }

    // PERF: preallocated MFM bit consumer, so a selected drive's tick allocates nothing.
    private final IntConsumer diskBitConsumer = this::shiftDiskBit;

    private void shiftDiskBit(int bit) {
        if (paula != null) paula.shiftInDiskBit(bit);
    }

    private void slipDiskShifterBit(int bit) {
        if (paula != null) paula.slipDiskShifterBit(bit);
    }

    /** Next MFM cell to write to the medium (see Paula.nextWriteBit). */
    private int nextDiskWriteBit() {
        return paula != null ? paula.nextWriteBit() : Paula.WRITE_BIT_END;
    }

    /**
     * Is at least one <b>connected</b> drive selected? The /SELx lines of CIA-B's PRB are
     * active low, and a drive that is not connected does not count.
     */
    private boolean isAnyDriveSelected(byte ciabPrb) {
        return (df0 != null && df0.isConnected() && (ciabPrb & 0x08) == 0)
            || (df1 != null && df1.isConnected() && (ciabPrb & 0x10) == 0)
            || (df2 != null && df2.isConnected() && (ciabPrb & 0x20) == 0)
            || (df3 != null && df3.isConnected() && (ciabPrb & 0x40) == 0);
    }

    /** Whether a selected, connected drive has its motor on: its head clocks the shifter. */
    private boolean isAnySelectedDriveTurning(byte ciabPrb) {
        return isTurning(df0, ciabPrb, 0x08) || isTurning(df1, ciabPrb, 0x10)
            || isTurning(df2, ciabPrb, 0x20) || isTurning(df3, ciabPrb, 0x40);
    }

    private static boolean isTurning(FloppyDrive drive, byte ciabPrb, int selBit) {
        return drive != null && drive.isConnected() && (ciabPrb & selBit) == 0 && drive.isMotorRunning();
    }

    private void tickFloppyDrive(FloppyDrive drive, int cycles) {
        if (drive == null) return;
        if (!drive.isConnected()) return;
        if (!drive.isSelected() && !drive.isBusy()) return;
        // The selected drive clocks Paula's read shift register at its own cell rate
        // whenever its motor turns, disk or no disk: an empty drive delivers zero cells.
        // With the motor stopped it delivers nothing, and the idle shifter does not run
        // either, because a drive IS selected (see isAnyDriveSelected).
        if (drive.isSelected() && paula != null) {
            drive.tick(cycles, diskBitConsumer, !paula.isDiskFastMode());
        } else {
            drive.tick(cycles);
        }
    }

    /**
     * The drives take the CIA-B control lines as they stand, with no edge processed:
     * what a CIA-B reset does. Every /SEL that reads active leaves its drive selected but
     * starts no motor; the first PRB write is compared against this state.
     */
    private void floppySelectSet() {
        floppyCatchUp();
        int prb = ciaB != null ? ciaB.peekPortB() & 0xFF : 0xFF;
        boolean step = (prb & 0x01) == 0;
        if (df0 != null) df0.selectSet((prb & 0x08) == 0, step);
        if (df1 != null) df1.selectSet((prb & 0x10) == 0, step);
        if (df2 != null) df2.selectSet((prb & 0x20) == 0, step);
        if (df3 != null) df3.selectSet((prb & 0x40) == 0, step);
        lastCiabPrb = prb;
        appliedCiabPrb = prb;
        floppyBusy = true;
    }

    private boolean isFloppyDriveBusy(FloppyDrive drive) {
        return drive != null && drive.isConnected() && drive.isBusy();
    }

    // PERF: state for gating the per-cycle floppy work (see risingEdge). lastCiabPrb=-1
    // and floppyBusy=true force the work at the first cycle until the state settles;
    // floppyBusy stays true while a drive is active.
    private int lastCiabPrb = -1;
    // The PRB value whose control lines the drives last received.
    private int appliedCiabPrb = -1;
    private boolean floppyBusy = true;
    // Colour clocks of drive work banked while no drive event was due (see risingEdge), how
    // many can be banked before the next one, and a flag that forces the work at the next
    // colour clock: raised by a drive whose state changed from outside the floppy block.
    private int floppyBanked = 0;
    private int floppyQuietCycles = 0;
    private boolean floppyForce = true;

    /**
     * Hands the banked cycles to the drives, as the colour clocks that went by would have:
     * they hold no event. Called before anything changes a drive from outside the floppy
     * block, and at the start of the block itself.
     */
    private void floppyCatchUp() {
        final int banked = floppyBanked;
        if (banked > 0) {
            floppyBanked = 0;
            tickFloppyDrive(df0, banked);
            tickFloppyDrive(df1, banked);
            tickFloppyDrive(df2, banked);
            tickFloppyDrive(df3, banked);
        }
        floppyForce = true;
    }

    /** Cycles a drive can be left alone before its next event; unbounded if it is not ticked. */
    private static int quietCycles(FloppyDrive drive) {
        if (drive == null || !drive.isConnected() || (!drive.isSelected() && !drive.isBusy())) {
            return Integer.MAX_VALUE;
        }
        return drive.cyclesUntilEvent();
    }
    // PERF: accumulator to process the floppy at CCK granularity (2 master cycles). See
    // the floppy block in risingEdge().
    private int floppyAccumCycles = 0;

    /** Length of an MFM cell in master cycles: 7 CCK, as FloppyDrive.CYCLES_PER_BIT. */
    private static final int IDLE_DISK_CYCLES_PER_BIT = 14;
    /** Cadence of the idle shifter (see the block of the same name in risingEdge). */
    private int idleDiskBitAccum = 0;
    /** PRB the idle shifter's view of the drives was taken at; -1 = to be taken again. */
    private int idleCachePrb = -1;
    private boolean idleAnySelected, idleAnyTurning;

    private static class FloppyStatus {
        boolean rdy = false, tk0 = false, wpro = false, chng = false, index = false;
    }

    /**
     * PERF: reusable FloppyStatus instance. It never escapes updateFloppy() (single thread,
     * synchronous), which resets its five fields at the start.
     */
    private final FloppyStatus floppyStatus = new FloppyStatus();

    // =========================================================================
    // DMA handlers
    // =========================================================================

    /**
     * One DRAM refresh cycle. If the bitplane request fetched in this CCK lands on the same
     * slot, the two share one bus cycle: the register addresses are ANDed and the refresh
     * address drives the access, so the plane's pointer is loaded from the refresh counter.
     * On the line strobe the ANDed address is no register at all and the pointer only steps
     * by one word; on a plain refresh it is a BPLxDAT, which is fetched from the refresh
     * address without stepping.
     */
    private void handleRefreshDMA(int cck, int bplReq) {
        final int addr = refptr;
        refptr = (refptr + refreshStep()) & refreshMask();
        if (cck == strobeCck() && denise != null) {
            // The slot carries the line strobe to Denise. Merged with a bitplane request its
            // register is ANDed with BPLxDAT and no longer names a strobe: Denise gets none.
            denise.receiveLineStrobe(bplReq >= 0 ? 0 : lineStrobeRegister(), maxHpos);
        }
        if (cck == strobeCck()) {
            lineStrobeDelivered = bplReq < 0;
        }
        if (bplReq < 0 || denise == null) {
            return;
        }
        final int plane = bplReq & BPLREQ_PLANE;
        if (cck == strobeCck()) {
            bplPtrInternal[plane] = addr + 2;
        } else {
            int data = dmaReadWord(addr);
            openBusLatch = data & 0xFFFF;
            denise.writeRegisterAtPixel(0x110 + plane * 2, data, cck * 4 - 70);
            markDeniseDmaSlot(cck);
            bplPtrInternal[plane] = addr;
        }
    }

    /**
     * The strobe the first refresh slot of a line drives, for the line about to start: STREQU
     * on the equalization lines at the top of the frame, STRVBL on the rest of the vertical
     * blank, STRHOR on every other line (the last line of the field included). A short field
     * has one equalization line more than a long one, in PAL and NTSC alike.
     */
    private int lineStrobeRegister() {
        final int v = triggerVpos;
        if (v <= (field == 0 ? EQUALIZATION_LAST_LINE_LONG : EQUALIZATION_LAST_LINE_SHORT)) {
            return 0x038;
        }
        if (v <= (beamNtsc ? VBLANK_LAST_LINE_NTSC : VBLANK_LAST_LINE_PAL)) {
            return 0x03A;
        }
        return 0x03C;
    }

    private static final int EQUALIZATION_LAST_LINE_LONG = 7;
    private static final int EQUALIZATION_LAST_LINE_SHORT = 8;
    private static final int VBLANK_LAST_LINE_PAL = 25;
    private static final int VBLANK_LAST_LINE_NTSC = 20;

    /** Refresh address step per refresh cycle: one RAS row. */
    protected int refreshStep() { return 0x002; }

    /** Refresh address width. */
    protected int refreshMask() { return 0x7FFFF; }

    /** REFPTR after a reset, on the chips that reset it. */
    protected int refreshResetValue() { return 0; }

    /**
     * Whether a refresh cycle is allocated in CCK {@code cck}, one CCK before it runs. A REFPTR
     * write in that CCK is lost.
     */
    private boolean refreshSlotAllocatedAt(int cck) {
        final int slot = cck + 1;
        return slot == strobeCck()
                || (slot < DMA_SLOT_TABLE.length && DMA_SLOT_TABLE[slot] == DMASLOT_REFRESH);
    }

    /** REFPTR as loaded by a register write. */
    protected int refptrFromRegister(int v) {
        return (v & 0xFFFE) | ((v & 0x7) << 16);
    }

    /** Installed Chip RAM in bytes. */
    protected int chipRamBytes() { return chipRAM.size(); }

    /**
     * A disk DMA slot: moves one word between Chip RAM and Paula's disk controller.
     */
    private void handleDiskDMA(int cck) {
        if (paula == null) return;
        int addr = dasSlotPointer;

        if (!paula.isDiskDmaWrite()) {
            // READ: the oldest FIFO word goes to Chip RAM, and the pointer moves on even if
            // the FIFO was emptied meanwhile.
            int word = paula.serviceDiskReadSlot((cck - SLOT_DISK_1) / 2);
            if (word >= 0) {
                dmaWriteWord(addr, word);
                openBusLatch = word & 0xFFFF;
            }
            paula.setDiskDmaPointer((addr + 2) & 0x1FFFFE);
            return;
        }
        if (!paula.isDiskDmaEnabled()) return;

        // WRITE: Chip RAM -> drive. A word is fetched only when Paula's write FIFO has room,
        // so the transfer follows the rotation of the medium, not the DMA slots.
        if (!paula.isWriteWordNeeded()) return;
        int data = dmaReadWord(addr);
        openBusLatch = data & 0xFFFF; // the DMA fetch drives the data bus
        paula.loadWriteWord(data);
        paula.setDiskDmaPointer(addr);
        paula.advanceDiskDma();
    }

    private FloppyDrive getSelectedDrive() {
        if (df0.isSelected()) return df0;
        if (df1.isSelected()) return df1;
        if (df2.isSelected()) return df2;
        if (df3.isSelected()) return df3;
        return null;
    }

    /**
     * An audio DMA slot: reads a word of samples from Chip RAM and hands it to Paula.
     *
     * Audio slots: channel 0 -> CCK 13, 1 -> 15, 2 -> 17, 3 -> 19.
     */
    private void handleAudioDMA(int cck) {
        if (paula != null) {
            int chan = (cck - SLOT_AUDIO_0) / 2;
            int addr = paula.getAudioDmaAddress(chan);
            int data = dmaReadWord(addr);
            openBusLatch = data & 0xFFFF; // the DMA fetch drives the data bus
            paula.receiveAudioDmaWord(chan, data);
        }
    }

    /**
     * Allocation of the disk or audio cycle at CCK {@code slotCck}, decided one CCK before
     * it runs. A write DMA owns all three disk slots; a read uses only the slots Paula
     * requested for this line (DMAL). An audio slot is taken only when its channel has a
     * fetch pending and reported it at the DMAL, so a line without a fetch leaves the slot
     * to the CPU.
     */
    private boolean allocateDasSlot(int slotCck) {
        if (slotCck >= DMA_SLOT_TABLE.length || paula == null) return false;
        switch (DMA_SLOT_TABLE[slotCck]) {
            case DMASLOT_DISK:
                dasSlotPointer = paula.getDiskDmaPointer();
                return isDiskDMAEnabled() && (paula.isDiskDmaWrite() ? paula.isDiskDmaEnabled()
                        : paula.isDiskReadSlotRequested((slotCck - SLOT_DISK_1) / 2));
            case DMASLOT_AUDIO:
                dasSlotPointer = paula.getAudioDmaAddress((slotCck - SLOT_AUDIO_0) / 2);
                return isAudioDMAEnabled()
                        && paula.isAudioDmaRequest((slotCck - SLOT_AUDIO_0) / 2);
            default:
                return false;
        }
    }

    /**
     * A sprite DMA slot.
     *
     * Each sprite has 2 CCK slots per line:
     *   slot N   (subSlot=0): SPRxPOS (POS/CTL phase)  or  SPRxDATA (DATA/DATB phase)
     *   slot N+2 (subSlot=1): SPRxCTL (POS/CTL phase)  or  SPRxDATB (DATA/DATB phase)
     *
     * POS/CTL phase: during the vertical blank and after VSTOP, Agnus fetches the descriptor.
     * DATA/DATB phase: between VSTART and VSTOP, Agnus fetches the sprite's pixels.
     */
    private void handleSpriteDMA(int cck) {
        if (denise == null) return;

        int relCck  = cck - SLOT_SPRITE_0;
        int sprIdx  = relCck / 4;         // which sprite (0-7)
        int subSlot = (relCck % 4) / 2;   // 0 = first slot (N), 1 = second slot (N+2)

        if (sprIdx < 0 || sprIdx > 7) return;
        if (sprFetchFmode != 0) {
            handleWideSpriteDMA(cck, sprIdx, subSlot);
            return;
        }

        int addr = sprPtrInternal[sprIdx];
        int data = dmaReadWord(addr);
        openBusLatch = data & 0xFFFF; // the DMA fetch drives the data bus
        sprPtrInternal[sprIdx] += 2;
        markDeniseDmaSlot(cck);

        if (!sprSlotDataPhase[sprIdx][subSlot]) {
            // -- POS/CTL phase: sprite descriptor fetch ---------------------------
            if (subSlot == 0) {
                // First slot: SPRxPOS
                writeSPRxPOS(sprIdx, data);
                denise.writeRegister(0x140 + sprIdx * 8, data); // SPRxPOS
            } else {
                // Second slot: SPRxCTL, updates VSTART/VSTOP
                writeSPRxCTL(sprIdx, data);
                denise.writeRegister(0x142 + sprIdx * 8, data); // SPRxCTL
            }
        } else {
            // -- DATA/DATB phase: sprite pixel fetch -------------------------------
            if (isAGA()) {
                // On Lisa a fetched word fills the high half of the sprite data register.
                denise.writeSpriteWide(sprIdx, subSlot != 0, data, 16);
            } else if (subSlot == 0) {
                denise.writeRegister(0x144 + sprIdx * 8, data); // SPRxDATA
            } else {
                denise.writeRegister(0x146 + sprIdx * 8, data); // SPRxDATB
            }
        }
    }

    /**
     * A sprite slot with a wide fetch (FMODE bits 2-3): 32 or 64 bits read as for a bitplane.
     * The descriptor words SPRxPOS and SPRxCTL are the top word of what is read; the data
     * words go to Denise whole. The pointer steps by the sprite width.
     */
    private void handleWideSpriteDMA(int cck, int sprIdx, int subSlot) {
        final int fm = sprFetchFmode;
        final int addr = sprPtrInternal[sprIdx];
        final long data = fetchWideBitplane(addr, fm);
        final int bits = fm == 3 ? 64 : 32;
        final int top = (int) (data >>> (bits - 16)) & 0xFFFF;
        openBusLatch = top;
        sprPtrInternal[sprIdx] = advanceWidePointer(addr, 0, fm);
        markDeniseDmaSlot(cck);
        if (!sprSlotDataPhase[sprIdx][subSlot]) {
            if (subSlot == 0) {
                writeSPRxPOS(sprIdx, top);
                denise.writeRegister(0x140 + sprIdx * 8, top);
            } else {
                writeSPRxCTL(sprIdx, top);
                denise.writeRegister(0x142 + sprIdx * 8, top);
            }
        } else {
            denise.writeSpriteWide(sprIdx, subSlot != 0, data, bits);
        }
    }

    /**
     * Slot 0 of a sprite's decision: the <b>only</b> point where the sprite sequencer arms.
     *
     * <p><b>The anchor is the SLOT, not the line boundary.</b> VSTART/VSTOP are evaluated at
     * the first of <i>that</i> sprite's two slots, CCK {@code 21 + 4n} for sprite <i>n</i>.
     * The Copper's writes fall between the line boundary and that CCK, and that is exactly
     * where the {@code spritedma/interfere*} tests live.
     *
     * <p>The reset line (25 PAL), the end of Agnus's vertical blank, stops the sprite and
     * makes it reread its descriptor: a condition here, not a write to a state register.
     */
    private void generateSpritesSlot0(int idx) {
        if (sprDmaCycle[idx] == 0 && sprDmaEnabled[idx]) {
            sprDmaCycle[idx] = 1;
        }
        if (vpos == sprVStart[idx]) {
            sprDmaEnabled[idx] = true;
            sprDmaCycle[idx] = 1;
        }
        if (vpos == sprVStop[idx] || vpos == SPRITE_RESET_LINE) {
            sprDmaEnabled[idx] = false;
            sprDmaCycle[idx] = 1;
        }
    }


    /**
     * Sprite DMA at the line change, called AFTER the vpos increment with the new vpos.
     */
    private void updateSpriteDMA(int nextVpos) {
        // VSTART/VSTOP are evaluated at each sprite's slot 0 (generateSpritesSlot0). Only
        // the end-of-frame switch-off is left here.
        if (nextVpos >= maxVpos) {
            Arrays.fill(sprDmaEnabled, false);
            Arrays.fill(sprDmaCycle, 0);
        }
    }

    /**
     * A DMA read of a word: the address is decoded in the DMA address space of Agnus (see
     * {@link #dmaMask}), word-aligned. DMA pointers are counters that grow without bound; the
     * mask makes them wrap as the address lines do. Above the installed memory nothing drives
     * the bus and the word is 0.
     */
    public int dmaReadWord(int addr) {
        final int a = addr & dmaMask & ~1;
        if (a < chipRAM.size()) {
            return chipRAM.readWord(a);
        }
        if (a < dmaEnd) {
            return slowRAM.readWord(a - chipRAM.size());
        }
        return 0;
    }

    /** A DMA write of a word, decoded like {@link #dmaReadWord}; above the memory it is lost. */
    public void dmaWriteWord(int addr, int word) {
        final int a = addr & dmaMask & ~1;
        if (a < chipRAM.size()) {
            chipRAM.writeWord(a, word);
        } else if (a < dmaEnd) {
            slowRAM.writeWord(a - chipRAM.size(), word);
        }
    }

    private void handleBitplaneDMA(int cck, int bplIdx, boolean hires, boolean domod) {
        if (denise == null) return;
        if (bplIdx < 0 || bplIdx >= 8) return;
        if (bplFetchFmode != 0) {
            handleWideBitplaneDMA(cck, bplIdx, domod);
            return;
        }

        int addr = bplPtrInternal[bplIdx];
        int data = dmaReadWord(addr);
        openBusLatch = data & 0xFFFF; // the DMA fetch drives the data bus
        denise.writeRegisterAtPixel(0x110 + bplIdx * 2, data, cck * 4 - 70); // BPLxDAT
        markDeniseDmaSlot(cck);
        bplPtrInternal[bplIdx] += 2;
        // BPLxMOD is added to the pointer AT the LAST fetch of the plane in the line (final
        // fetch unit, `domod`), with the register value sampled in that CCK, NOT at the end
        // of the line. The difference shows: Arte (Sanity) writes the moduli through the
        // Copper at WAIT h=222, AFTER the last fetch (~CCK 210) but BEFORE the end of the
        // line, so the write applies to the NEXT line; applied at the end of the line, half
        // of the MOVEs (2 in a row, across the wrap) came one line early, desynchronising
        // the planes by one bitmap row on alternate scanlines.
        // In hires domod covers only the SECOND half of the final 8-CCK unit: each plane
        // takes the modulo once.
        //
        // Which block is the last is told by the SEQUENCER, not by the position: it is
        // `ddfStopping == 2`, the state the machine enters after DDFSTOP has passed. With a
        // positional comparison
        // `cck >= effStop - (hires?4:8)`, a line that switches BPLEN off and on again midway
        // applied the modulo wherever it happened, since `effStop` knows nothing of what
        // happened within the line. The state is the one of the slot's DECISION, two CCKs
        // before the fetch: it arrives here with the request (domod).
        if (domod) {
            // The modulo is the one SAMPLED at the top of the CCK, not the live register:
            // see bpl1modFetch. Read live, a Copper write in the previous CCK came in one
            // CCK too early.
            int mod = bitplaneModulo(bplIdx);
            // The modulo is added WITHOUT a mask: wrapping within the installed Chip RAM is
            // dmaReadWord's job, at the fetch. A mask hard-wired to 1 MB made a plane above
            // $0FFFFF lose bit 20 at every line with 2 MB of Chip RAM, collapsing the display
            // into the first megabyte (WHDLoad reserves low Chip for the game and puts the
            // Workbench screen higher up).
            bplPtrInternal[bplIdx] = bplPtrInternal[bplIdx] + mod;
            rgaSlotBplMod[(int) (masterClock >> 1) & 3] = mod;
        } else {
            rgaSlotBplMod[(int) (masterClock >> 1) & 3] = 0;
        }
    }

    /**
     * A bitplane fetch wider than a word (FMODE 1-3): the long or quad word goes to Denise in
     * one transfer, and the pointer steps by the width, the modulo added in the last block as
     * for a word.
     */
    private void handleWideBitplaneDMA(int cck, int bplIdx, boolean domod) {
        final int fm = bplFetchFmode;
        final int addr = bplPtrInternal[bplIdx];
        final long data = fetchWideBitplane(addr, fm);
        openBusLatch = (int) data & 0xFFFF;
        denise.writeBitplaneWide(bplIdx, data, fm == 3 ? 64 : 32, cck * 4 - 70);
        markDeniseDmaSlot(cck);
        final int mod = domod ? bitplaneModulo(bplIdx) : 0;
        bplPtrInternal[bplIdx] = advanceWidePointer(addr, mod, fm);
        rgaSlotBplMod[(int) (masterClock >> 1) & 3] = mod;
    }

    /**
     * The modulo a plane adds in the last block: BPL1MOD for the odd planes, BPL2MOD for the
     * even ones. With BSCAN2 (FMODE bit 14) the choice goes by line instead, alternating
     * against the parity of DIWSTRT's start line, so that a scan-doubled screen fetches every
     * line twice.
     */
    private int bitplaneModulo(int plane) {
        if ((fmode & 0x4000) != 0) {
            return (((diwStrt >> 8) ^ (vpos ^ 1)) & 1) != 0 ? bpl1modFetch : bpl2modFetch;
        }
        return (plane & 1) == 0 ? bpl1modFetch : bpl2modFetch;
    }

    /**
     * A bitplane fetch and a sprite cycle allocated in the same slot, which happens when the
     * sprite keeps its slot at the end of the bitplane fetch. The two pointers merge into their
     * OR and the register address into the AND of the two. When that address is still a
     * BPLxDAT, the word is fetched from the merged pointer and goes to that register; otherwise
     * nothing is transferred. Either way the sprite receives no data, and the plane and the
     * sprite both continue from the merged pointer plus one word and the modulo left in this
     * slot buffer.
     */
    private void mergeBitplaneWithSpriteSlot(int cck, int plane) {
        final int idx = (cck - SLOT_SPRITE_0) / 4;
        final int sub = ((cck - SLOT_SPRITE_0) % 4) / 2;
        final int sprReg = 0x140 + idx * 8 + (sub != 0 ? 2 : 0)
                + (sprSlotDataPhase[idx][sub] ? 4 : 0);
        final int reg = (0x110 + plane * 2) & sprReg;
        int pv = bplPtrInternal[plane] | sprPtrInternal[idx];
        if (reg >= 0x110 && reg < 0x120 && denise != null) {
            int data = dmaReadWord(pv);
            openBusLatch = data & 0xFFFF;
            denise.writeRegisterAtPixel(reg, data, cck * 4 - 70);
            markDeniseDmaSlot(cck);
        }
        pv += 2 + rgaSlotBplMod[(int) (masterClock >> 1) & 3];
        bplPtrInternal[plane] = pv;
        sprPtrInternal[idx] = pv;
    }

    /**
     * A bitplane fetch and a disk or audio cycle allocated in the same slot. The register address
     * is the AND of the two, and the plane continues from the DAS pointer plus one word and the
     * modulo left in this slot buffer. For planes 1 to 4 the AND names no register and nothing is
     * transferred. For plane 5 or 6 with a disk read slot ($118 or $11A with $008), or plane 5
     * with AUD0 or AUD2, it is $008, the disk read register itself: the slot is then a disk read
     * transfer to the merged address, and the DAS pointer continues one word past it.
     */
    private void mergeBitplaneWithDasSlot(int bplReq, int cck) {
        final int plane = bplReq & BPLREQ_PLANE;
        final int pv = dasSlotPointer + 2 + rgaSlotBplMod[(int) (masterClock >> 1) & 3];
        bplPtrInternal[plane] = pv;
        final boolean disk = DMA_SLOT_TABLE[cck] == DMASLOT_DISK;
        final int dasReg = disk ? (paula.isDiskDmaWrite() ? 0x026 : 0x008)
                : 0x0AA + ((cck - SLOT_AUDIO_0) / 2) * 0x10;
        if (((0x110 + plane * 2) & dasReg) != 0x008) return;
        final int word = paula.serviceDiskReadSlot(disk ? (cck - SLOT_DISK_1) / 2 : 0);
        if (word >= 0) {
            dmaWriteWord(pv, word);
            openBusLatch = word & 0xFFFF;
        }
        if (disk) {
            paula.setDiskDmaPointer((pv + 2) & 0x1FFFFE);
        } else {
            paula.setAudioDmaAddress((cck - SLOT_AUDIO_0) / 2, pv + 2);
        }
    }

    /**
     * <b>BPL1DAT is the display TRIGGER, and it is a REGISTER WRITE</b>: bitplane DMA is only
     * its usual supplier, not its condition. With BPLEN <b>off</b> and the Copper (or the CPU)
     * writing BPL1DAT by hand, Denise outputs that word all the same, whatever DMACON says.
     *
     * <p>The gate is therefore "a word is armed", which with DMA on is true exactly when the
     * fetch wrote BPL1DAT. Gating on DMA made the whole output of the
     * {@code Denise/Registers/BPL0DAT} bank disappear (bitplane DMA is never on there).
     */
    private void handleBitplaneDrawEvent(int cck) {
        if (denise == null) return;
        if (!isBitplaneDMAActive() && !denise.isBitplaneArmed()) return;

        int bplcon0Val = denise.getBplcon0();
        // Planes DRAWN, not fetched: this decides whether Denise outputs anything, and the
        // threshold is Denise's own (AbstractDenise.deniseBpu): more than 4 planes in hires
        // means no planes, not four.
        if (denise.getBpu() <= 0) return;

        boolean hires = (bplcon0Val & 0x8000) != 0;
        int cycleLen, subOdd, subEven;
        if (isAGA() || denise.superHires()) {
            // The block lasts as long as its word: on Lisa the fetch width makes it up to 64
            // bits and the scroll has two more bits on each side, in super-hires a 16-bit
            // word lasts two CCKs. See AbstractDenise.scrollSub.
            cycleLen = denise.wordCcks();
            subOdd  = denise.scrollSub(false);
            subEven = denise.scrollSub(true);
        } else {
            cycleLen = hires ? 4 : 8;
            // COARSE part of the scroll: how many CCKs to delay the block. See
            // scrollSubPixels for the single rule and why hires differs from lores.
            subOdd  = scrollSubPixels(bplcon1, hires);
            subEven = scrollSubPixels(bplcon1 >> 4, hires);
        }
        int mask = cycleLen - 1;
        // The scroll is matched against Denise's own horizontal counter. On a line whose
        // strobe did not reset it the counter runs offset from the CCK, and the block the
        // scroll selects moves with it. The line's strobe comes after the draw of its CCK, so
        // only the draw past it belongs to the line the strobe ended.
        final int head = cck > strobeCck() ? denise.hcounterRenderHead() : denise.hcounterHead();
        final int phase = cck + ((head - 2) >> 1);
        boolean drawOdd = (phase & mask) == ((subOdd >> 2) & mask);
        boolean drawEven = (phase & mask) == ((subEven >> 2) & mask);
        if (drawOdd || drawEven) {
            // The EVEN planes' scroll ALWAYS comes from PF2H, in both parts and in both
            // modes, never from PF1H in single playfield. With PF1H == PF2H (every game) it
            // does not show; `Denise/Misc/ThirdParty/losso` holds PF1H at 0 and sweeps PF2H
            // from 0 to 15.
            int fineEven = subEven & 3;
            denise.drawBitplanesAtPixel(cck * 4 - 70, drawOdd, drawEven,
                    subOdd & 3, fineEven);
        }
    }

    // =========================================================================
    // Bus cycles
    // =========================================================================

    private void handleCopperBusCycle() {
        if (copperBusReq.as && copperBusReq.read) {
            // A Copper fetch is DMA: it is decoded by Agnus, not by Gary.
            if (!copperBusProcessed) {
                copperBusResp.dataIn = dmaReadWord(copperBusReq.address);
                openBusLatch = copperBusResp.dataIn & 0xFFFF;
                copperBusProcessed = true;
            }
            copperBusResp.ack = true;
        } else if (copperBusReq.as) {
            Gary.Route route = gary.route(copperBusReq.address,
                    copperBusReq.read ? Gary.AccessType.READ : Gary.AccessType.WRITE,
                    copperBusReq.uds, copperBusReq.lds, false, copperScratchRoute);
            if (route.ok) {
                if (!copperBusProcessed) {
                    if (copperBusReq.read) {
                        copperBusResp.dataIn = readBusData(route, copperBusReq.address);
                    } else {
                        writeBusData(route, copperBusReq.address, copperBusReq.dataOut, copperBusReq.uds, copperBusReq.lds);
                    }
                    copperBusProcessed = true;
                }
                copperBusResp.ack = true;
            } else {
                copperBusResp.berr = true;
            }
        } else {
            copperBusResp.clearHandshake();
            copperBusProcessed = false;
        }
    }

    /**
     * SYNCHRONOUS memory read for the blitter in immediate mode: done at once, without using
     * emulated bus cycles (an immediate blit completes at the BLTSIZE write). See
     * {@link Blitter#setImmediateMode}.
     */
    public int blitterImmediateRead(int address) {
        final int data = dmaReadWord(address);
        openBusLatch = data & 0xFFFF;
        return data;
    }

    /** Synchronous memory write for the immediate blitter (see {@link #blitterImmediateRead}). */
    public void blitterImmediateWrite(int address, int data) {
        dmaWriteWord(address, data);
        openBusLatch = data & 0xFFFF;
    }

    private void handleCpuBusCycle() {
        if (!cpuBusReq.as) {
            // Idempotent clearing: once, when AS falls (see cpuBusIdle).
            if (!cpuBusIdle) {
                cpuBusResp.clearHandshake();
                cpuBusResp.vpa = false;
                cpuBusResp.berr = false;
                cpuAccessTransferred = false;
                cpuRouteCacheValid = false;
                cpuCurrentRoute = null;
                cpuBusIdle = true;
            }
            return;
        }
        cpuBusIdle = false;

        // Interrupt acknowledge cycle (IACK): an autovectored cycle of FIXED length (DTACK at
        // once, 4 clocks), NOT synchronised to the E clock. Synchronising it made the
        // interrupt entry latency depend on the phase of the free-running E clock (CLK/10):
        // on a constant long PAL frame (313 lines x 454 SC = 142102 SC, 142102 mod 10 = 2) the
        // phase moves by +2 SC per frame, a period-5 orbit, and the period-5 flicker of
        // bbusy0 (synccpu entering at a different hpos every frame).
        if (cpuBusReq.iack) {
            cpuBusResp.iackAutovector = true;
            cpuBusResp.ack = true;
            cpuBusResp.vpa = false;
            return;
        }

        // -- One bus FSM -------------------------------------------------------
        // Agnus keeps no second bus FSM next to the CPU's: it is purely COMBINATIONAL on the
        // CPU's bus cycle (`cpu.getBusCycleState()`, cycle-exact against the JSON vectors):
        //   - S2-S4 -> compute the route and decide DTACK (ack/vpa/berr), DMA contention and
        //             CCK alignment included. The CPU samples DTACK at its S4; re-evaluating
        //             during the S4 wait states re-arbitrates until the bus is free/aligned.
        //   - S5-S7 -> perform the data transfer ONCE (latch `cpuAccessTransferred`), so the
        //             data is ready when the CPU latches it at its S6.
        // The access lasts exactly the CPU's natural (even) bus cycle. A second FSM of a
        // different length moved the phase at instruction boundaries: IK+'s firework loop
        // paid the odd-cycle wait on every access and its VERTB handler overran.
        switch (cpu.getBusCycleState()) {
            case S2, S3, S4 -> decideCpuDtack();
            case S5, S6, S7 -> {
                if (!cpuAccessTransferred && cpuCurrentRoute != null && cpuCurrentRoute.ok) {
                    handleCpuDataTransfer();
                    cpuAccessTransferred = true;
                }
            }
            default -> { /* IDLE/S0/S_VMA: nothing to decide or transfer here */ }
        }
    }

    /**
     * Whether the access is chip-bus timed (DMA contention + CCK quantisation) or fast (DTACK
     * at once). See the comment in {@link #decideCpuDtack()}.
     */
    private static boolean isChipBusSyncAddress(int physAddr, Gary.Target target) {
        // $D00000-$DFFFFF: chip-bus timed unconditionally, whoever answers.
        if (physAddr >= 0xD00000 && physAddr < 0xE00000) return true;
        // $C00000-$CFFFFF: fast on its own, but the INSTALLED Slow RAM is chip-bus timed.
        // The target says it: Gary returns SLOW_RAM only where there is real RAM, and the
        // custom mirrors above it are CUSTOM_REGS.
        if (physAddr >= 0xC00000) return target == Gary.Target.SLOW_RAM;
        return physAddr < 0x200000
                && (target == Gary.Target.CHIP_RAM || target == Gary.Target.OPEN_BUS);
    }

    /**
     * Write to a <b>fast</b> bank: the target takes the data at the START of the bus cycle,
     * not in the middle. A fast-bank write lasts as long as a chip-bus one (2 CCK), but its
     * data lands <b>one CCK earlier</b>. On a bank without side effects it does not show; on
     * the custom register mirrors at {@code $C00000-$CFFFFF} (fast) it does: vAmigaTS
     * {@code Memory/Rom/mirror/custC}, where every COLOR00 of the bank fell at -3 CCK instead
     * of -4, and back to -4 exactly at iteration 1024, where the address enters
     * {@code $D00000} and is chip-bus timed again.
     *
     * <p>The point is <b>S2</b>, the first edge with /AS asserted: before it
     * {@code handleCpuBusCycle} takes the bus-idle branch, which clears the route and
     * {@code cpuAccessTransferred}. It is one {@code hpos} before the S4 of a chip-bus
     * transfer, and {@code hpos} is what Denise quantises into CCKs: enough to bring the
     * write back into the previous CCK.
     *
     * <p>WRITES only: uds/lds here are valid for a write ({@code applyBusSignalsS0} copied
     * them at S0), and the CPU latches read data at its own S6 anyway, so anticipating a read
     * would change nothing observable.
     */
    private void maybeTransferFastBankWrite() {
        if (cpuAccessTransferred || cpuBusReq.read || cpuBusReq.iack) return;
        routeCpuCurrentBusRequest();
        if (cpuCurrentRoute == null || !cpuCurrentRoute.ok) return;
        // A CIA is NOT a fast bank: its cycle is SYNCHRONOUS (6800) and it latches the data
        // on the E-clock edge, at the end of the VMA cycle, not at the start. Delivered here,
        // at S2, the write reached the chip at ANY E phase: on vAmigaTS CIA/CIA the four
        // writes of a handler fell at masterClock mod 10 = 2, 0, 4, 0, up to 9 master cycles
        // early; a timer armed there started up to two E clocks early and its interrupt came
        // 5-10 CCK early (the whole "class 1" of the bank, 21 reds out of 35). The write falls
        // at the same E phase every time, and that is where the CIA clock grid re-anchors.
        if (cpuCurrentRoute.target == Gary.Target.CIA_A
                || cpuCurrentRoute.target == Gary.Target.CIA_B
                || cpuCurrentRoute.target == Gary.Target.CIA_BOTH) return;
        if (isChipBusSyncAddress(cpuBusReq.address & 0xFFFFFF, cpuCurrentRoute.target)) return;
        handleCpuDataTransfer();
        cpuAccessTransferred = true;
    }

    /**
     * Decides DTACK for the current CPU access while the CPU presents the cycle (states
     * S2-S4 of its FSM): computes the route and sets ack/vpa/berr from the target, DMA
     * contention and CCK alignment. Idempotent: recomputed at every edge while the CPU waits
     * in S4, so the access is granted as soon as the chip bus is free and aligned.
     */
    private void decideCpuDtack() {
        routeCpuCurrentBusRequest();

        if (!cpuCurrentRoute.ok) {
            if (cpuCurrentRoute.busError) {
                cpuBusResp.berr = true;
            } else {
                // Open bus: DTACK at once, data = last value seen on the data bus.
                cpuBusResp.ack = true;
                if (cpuBusReq.read) cpuBusResp.dataIn = lastBusData;
                cpuAccessTransferred = true; // no memory transfer
            }
            return;
        }

        // CIA access: synchronous cycle. Agnus asserts VPA; the E-clock/VMA synchronisation
        // is handled entirely by the CPU's FSM (state S_VMA).
        if (cpuCurrentRoute.target == Gary.Target.CIA_A
                || cpuCurrentRoute.target == Gary.Target.CIA_B
                || cpuCurrentRoute.target == Gary.Target.CIA_BOTH) {
            cpuBusResp.vpa = true;
            cpuBusResp.ack = false;
            return;
        }

        // Chip-bus synchronisation (DMA contention + CCK quantisation).
        //
        // The BANK decides it: $000000-$1FFFFF and $D00000-$DFFFFF (unconditionally) are
        // chip-bus timed, the rest is fast, except the pages of the Slow RAM actually
        // INSTALLED, which are chip-bus timed too. So $C00000-$CFFFFF has both natures: where
        // the trapdoor RAM is it is chip-bus timed, and the custom register mirrors ABOVE it
        // are fast: the CPU writes there paying neither contention nor alignment.
        //
        // Tying the choice to the TARGET (SLOW_RAM/CUSTOM_REGS always synchronised) made the
        // mirrors pay a contention they do not pay: in vAmigaTS Memory/Rom/mirror/custC the
        // drift grew up to 3 CCK towards the bottom of the screen. Treating them ALL as fast
        // instead removes contention from the real Slow RAM too and moves the boot chain:
        // CpuTimingTestSuite goes red on almost every test (docs/vamigats-import-order.md,
        // Memory/Rom outcomes).
        //
        // With OVL on, Chip RAM at 0 stays chip-bus timed here, where ROM answers there and
        // would be fast: a known divergence, not measured by this bank.
        //
        // The irq6 handler of G1/NEG2l does `neg.l $DDF180`, an open-bus address inside
        // $D00000-$DFFFFF: skipping the contention compressed the accesses to a 2-CCK step
        // instead of quantising them on the free slots (BPU=6), moving the white marker by
        // -8 CCK.
        int physAddr = cpuBusReq.address & 0xFFFFFF;
        boolean needsChipBusSync = isChipBusSyncAddress(physAddr, cpuCurrentRoute.target);

        if (needsChipBusSync) {
            // Blocked if: (a) a DMA has the bus in this CCK (bitplane fetch included), or
            // (b) the access is not aligned to the CCK (odd hpos).
            //
            // In lores with <= 4 planes (<= 2 in hires) the bitplanes take only the odd CCK
            // slots of the fetch unit, and the 68000 NATURALLY gets the free even ones
            // (busTakenByCustom false there): the right ~50% bandwidth. No "non-blocking
            // bitplane" exception on TAKEN slots: it would double the CPU's bandwidth in the
            // display area and let games' blit loops run ahead of the beam (banded plane
            // corruption, e.g. Altered Beast).
            //
            // Odd wait `(hpos&1)`: the CPU samples DTACK only at a CCK boundary (even hpos),
            // the 68000's CCK-granular alignment to the chip bus. With contention reset per
            // whole CCK the CPU's bus cycle phase stays aligned on its own and the odd wait
            // rarely fires (0 odd-wait blocks measured in IK+'s firework loop,
            // docs/cpu-chipbus-alignment-fix.md).
            boolean oddWait = (hpos & 1) != 0;
            boolean blocked = busTakenByCustom || oddWait;
            cpuBusResp.ack = !blocked;
        } else {
            // ROM or another target with no contention: DTACK at once.
            cpuBusResp.ack = true;
        }
    }

    private void routeCpuCurrentBusRequest() {
        int address = cpuBusReq.address;
        boolean read = cpuBusReq.read;
        boolean uds = cpuBusReq.uds;
        boolean lds = cpuBusReq.lds;

        if (cpuRouteCacheValid
                && cpuRouteCacheAddress == address
                && cpuRouteCacheRead == read
                && cpuRouteCacheUds == uds
                && cpuRouteCacheLds == lds) {
            return;
        }

        cpuCurrentRoute = gary.route(address,
                read ? Gary.AccessType.READ : Gary.AccessType.WRITE,
                uds, lds, true, cpuScratchRoute);
        cpuRouteCacheAddress = address;
        cpuRouteCacheRead = read;
        cpuRouteCacheUds = uds;
        cpuRouteCacheLds = lds;
        cpuRouteCacheValid = true;
    }

    /**
     * Performs the CPU's data transfer on the slot just granted (falling edge, bus FSM in
     * S5). Same condition as the S5-S7 branch of {@link #handleCpuBusCycle()}, which remains
     * as a safety net for a CPU reaching S5 without passing here.
     */
    private void handleCpuDataTransferPhase() {
        if (!cpuBusReq.as || cpuBusReq.iack || cpuAccessTransferred) {
            return;
        }
        // A CIA read takes its data when E rises, two and a half CCKs before the cycle ends
        // at the next fall of E: a register that changes in between, such as a TOD
        // increment, is not seen by this access.
        if (cpuBusReq.read && cpuBusReq.vma && !ciaReadSampled
                && cpu.getBusCycleState() == AbstractMotorola.BusCycleState.S_VMA
                && cpu.getEClockCounter() == 6
                && cpuCurrentRoute != null
                && (cpuCurrentRoute.target == Gary.Target.CIA_A
                    || cpuCurrentRoute.target == Gary.Target.CIA_B
                    || cpuCurrentRoute.target == Gary.Target.CIA_BOTH)) {
            ciaReadSample = readCiaData(cpuCurrentRoute.target, cpuBusReq.address);
            ciaReadSampled = true;
        }
        // A CIA write reaches the chip at the same instant: a TOD increment that follows it in
        // the same cycle already counts from the written value.
        if (!cpuBusReq.read && cpuBusReq.vma && !ciaWriteDone
                && cpu.getBusCycleState() == AbstractMotorola.BusCycleState.S_VMA
                && cpu.getEClockCounter() == 6
                && cpuCurrentRoute != null
                && (cpuCurrentRoute.target == Gary.Target.CIA_A
                    || cpuCurrentRoute.target == Gary.Target.CIA_B
                    || cpuCurrentRoute.target == Gary.Target.CIA_BOTH)) {
            writeCiaData(cpuCurrentRoute.target, cpuBusReq.address, cpuBusReq.dataOut & 0xFFFF);
            ciaWriteDone = true;
        }
        // Fast bank: the write lands at the start of the bus cycle, one CCK before the chip
        // bus slot. It comes BEFORE the route check because at S2 there is no route yet:
        // maybeTransferFastBankWrite() computes it. See its javadoc.
        if (cpu.getBusCycleState() == AbstractMotorola.BusCycleState.S2) {
            maybeTransferFastBankWrite();
            return;
        }
        if (cpuCurrentRoute == null || !cpuCurrentRoute.ok) {
            return;
        }
        switch (cpu.getBusCycleState()) {
            // S4 with DTACK already granted in this same master cycle: this IS the granted
            // slot. The CPU consumes the ack only at the next rising edge (S4 -> S5), so
            // without this branch the transfer would slip by one master cycle, half a CCK.
            case S4 -> {
                if (cpuBusResp.ack) {
                    handleCpuDataTransfer();
                    cpuAccessTransferred = true;
                }
            }
            case S5, S6, S7 -> {
                handleCpuDataTransfer();
                cpuAccessTransferred = true;
            }
            default -> { /* not granted yet */ }
        }
    }

    private void handleCpuDataTransfer() {
        // The CPU splits long words into two words beforehand
        // (AbstractMotorola.scheduleRead/scheduleWriteLong): Agnus always sees byte/word
        // accesses, so the address is the one requested and UDS/LDS are the real lines.
        int addr = cpuBusReq.address;
        boolean busUds = cpuBusReq.uds;
        boolean busLds = cpuBusReq.lds;

        // Whether the current target is a CIA (computed once).
        boolean isCia = (cpuCurrentRoute.target == Gary.Target.CIA_A
                || cpuCurrentRoute.target == Gary.Target.CIA_B
                || cpuCurrentRoute.target == Gary.Target.CIA_BOTH);

        if (cpuBusReq.read) {
            // -- READ -------------------------------------------------------------
            //
            // readCiaData() reads cpuBusReq.uds/lds itself; with both asserted both CIAs
            // are selected when Gary reported CIA_BOTH as the target.
            int word;
            if (isCia) {
                word = ciaReadSampled ? ciaReadSample : readCiaData(cpuCurrentRoute.target, addr);
                ciaReadSampled = false;
            } else {
                word = readBusData(cpuCurrentRoute, addr);
            }
            word &= 0xFFFF;

            lastBusData = word;
            cpuBusResp.dataIn = word;

        } else {
            // -- WRITE ------------------------------------------------------------
            //
            // The CPU duplicates a byte on both lanes of the data bus; UDS/LDS select the
            // lane. The driven word is the whole dataOut.
            int word = cpuBusReq.dataOut & 0xFFFF;

            if (isCia) {
                // writeCiaData() uses cpuBusReq.uds/lds to select CIA-A (LDS, low lane) or
                // CIA-B (UDS, high lane).
                if (!ciaWriteDone) writeCiaData(cpuCurrentRoute.target, addr, word);
                ciaWriteDone = false;
            } else {
                // The first refresh cycle of the line drives the horizontal strobe into
                // Denise's slot of the RGA bus, and it overwrites whatever register write
                // lands in the same CCK: the Agnus side of the register still takes the
                // value, Denise never sees it. A bitplane or sprite data transfer does the
                // same. A cycle synchronised to the chip bus cannot be granted there, so only
                // an unsynchronised one (a FAST16 bank) meets it.
                final boolean rgaSlotTaken = denise != null
                        && ((hpos >> 1) == strobeCck() || deniseSlotTakenByDma());
                if (rgaSlotTaken) denise.setRgaSlotTaken(true);
                writeBusData(cpuCurrentRoute, addr, word, busUds, busLds);
                if (rgaSlotTaken) denise.setRgaSlotTaken(false);
            }

            // lastBusData is the 16 bits actually driven on the data bus in this cycle.
            lastBusData = word;
        }
    }

    // =========================================================================
    // CIA read/write
    // =========================================================================

    /**
     * Reads a CIA register. The register is selected by address bits A11-A8:
     * {@code reg = (address >> 8) & 0x0F}.
     *
     * CIA-A answers at odd addresses ($BFExxx), on the low byte of the bus (D7-D0), LDS.
     * CIA-B answers at even addresses ($BFDxxx), on the high byte of the bus (D15-D8), UDS.
     *
     * The byte of the word no CIA drives keeps the last value on the bus.
     */
    private int readCiaData(Gary.Target target, int address) {
        int reg = (address >> 8) & 0x0F;
        int result = lastBusData; // Pull-up or last data on the bus

        if (target == Gary.Target.CIA_A || target == Gary.Target.CIA_BOTH) {
            // CIA-A: low byte (D7-D0) on LDS (odd addresses)
            if (cpuBusReq.lds) {
                int cv = ciaA.readRegister(reg) & 0xFF;
                result = (result & 0xFF00) | cv;
            }
        }
        if (target == Gary.Target.CIA_B || target == Gary.Target.CIA_BOTH) {
            // CIA-B: high byte (D15-D8) on UDS (even addresses)
            if (cpuBusReq.uds) {
                int cv = ciaB.readRegister(reg) & 0xFF;
                result = (result & 0x00FF) | (cv << 8);
            }
        }
        return result;
    }

    private void writeCiaData(Gary.Target target, int address, int data) {
        int reg = (address >> 8) & 0x0F;

        if (target == Gary.Target.CIA_A || target == Gary.Target.CIA_BOTH) {
            // CIA-A: low byte (D7-D0) on LDS
            if (cpuBusReq.lds) {
                ciaA.writeRegister(reg, (byte)(data & 0xFF));
            }
        }
        if (target == Gary.Target.CIA_B || target == Gary.Target.CIA_BOTH) {
            // CIA-B: high byte (D15-D8) on UDS
            if (cpuBusReq.uds) {
                ciaB.writeRegister(reg, (byte)((data >> 8) & 0xFF));
            }
        }
    }

    // =========================================================================
    // General bus read/write
    // =========================================================================

    /** Last word driven on the data bus. Feeds the readback of write-only custom registers
     *  ({@link #faultyCustomRead}) and the DMA fetches. */
    private int openBusLatch = 0xFFFF;

    /**
     * The value the CPU reads from an address where no device answers. On the 68000 it is
     * <em>not</em> the last data seen on the bus, but
     * <ul>
     *   <li><b>0</b> if the code making the access runs <b>from ROM</b>;</li>
     *   <li>otherwise the word already in the prefetch queue (bytes swapped at an odd
     *       address).</li>
     * </ul>
     *
     * <p>Returning {@link #openBusLatch}, which after a write holds <em>the data just
     * written</em>, made a "write and read back" memory test (exactly how the Kickstart sizes
     * RAM) succeed where there is nothing: Kickstart 1.2 concluded the Slow RAM reached
     * $DBFFFF, put the supervisor stack at $DBFFF2 and died with a yellow screen. The sizing
     * runs from ROM, reads 0, and the phantom RAM disappears.
     *
     * <p>The "from RAM" branch stays non-zero, which programs using unmapped space as scratch
     * rely on (e.g. Hollywood Poker Pro writes $FFFF at $219E5E and tests the readback to
     * decide whether to load its directory).
     */
    private int floatingBusRead(int addr) {
        // The A1200 board pulls the undriven data bus to zero.
        if (gary.isGayleDecode()) return 0;
        if (isExecutingFromRom()) return 0;
        // The value is the last word FETCHED by the prefetch. When `irc` has been consumed
        // and the prefetch refilling it is still in flight, `ircValid` is false and `irc`
        // holds a leftover from several words back: the last word really fetched is then
        // `ird`. Without this guard a CMPI/CMP with several extension words aimed at an
        // unmapped address read its OWN immediate operand instead of the next word: Pinball
        // Dreams' protection (original IPF), `CMPI.W #$1111,$00F00000` at $7ABC8, then
        // matched and ran its self-destruct routine (CLR.B (A0)+ in a loop).
        int v = processor.prefetchWord();
        return ((addr & 1) != 0) ? (((v << 8) | (v >>> 8)) & 0xFFFF) : v;
    }

    /**
     * true if the current PC is inside a ROM: the Kickstart socket window, its mirror at
     * $E00000, or Chip RAM while the overlay is on (right after reset, before the Kickstart
     * clears OVL).
     */
    private boolean isExecutingFromRom() {
        int pc = processor.getOpcodeAddress() & 0xFFFFFF;
        if (pc >= 0xF80000) return true;
        if (pc >= 0xE00000 && pc < 0xE80000) return true;
        return pc < 0x080000 && gary.isRomOverlayEnabled();
    }
    private int readBusData(Gary.Route route, int addr) {
        int eff = route.effectiveAddress;
        int alignedEff = eff & ~1;

        int value = switch (route.target) {
            case CHIP_RAM ->
                chipRAM.readWord(alignedEff);
            case KICKSTART_ROM, OVERLAY_ROM ->
                rom.readWord(alignedEff);
            case CUSTOM_REGS ->
                readCustomRegister(eff);   // eff, not the raw addr
            case SLOW_RAM ->
                (slowRAM != null && alignedEff < slowRAM.size())
                        ? slowRAM.readWord(alignedEff) : floatingBusRead(addr);
            case FAST_RAM ->
                (fastRam != null && alignedEff < fastRam.sizeBytes())
                        ? fastRam.ram().readWord(alignedEff) : floatingBusRead(addr);
            case AUTOCONFIG -> {
                // AutoConfig space: configuration nibble on the high bits of the bus
                // (D15-D12). The CPU always reads at an even address (UDS): the data goes
                // in the high byte of the word. $FF if no board answers. The first board of
                // the chain not yet configured answers.
                AutoConfigDevice dev = currentAutoConfigDevice();
                int acf = (dev != null) ? dev.peekConfigByte(addr) : 0xFF;
                yield (acf << 8) | 0xFF;
            }
            case HD_CONTROLLER -> {
                // Like RAM, the memory drives all 16 bits: the word is read at the ALIGNED
                // page offset and the CPU selects the byte (UDS/LDS) for byte accesses.
                HdController hd = hdControllerAt(addr);
                yield (hd != null) ? hd.peekWord((addr & 0xFFFF) & ~1) : floatingBusRead(addr);
            }
            case RTC ->
                readRtcBusData(route, addr);
            case GAYLE ->
                readGayleBusData(route, addr);
            default ->
                // Open bus: no device drives the data bus. See floatingBusRead().
                floatingBusRead(addr);
        };

        // Latch the value on the data bus for the next open-bus read.
        if (route.target != Gary.Target.OPEN_BUS) openBusLatch = busLatchValue(value, route.uds, route.lds);
        return value;
    }

    /**
     * Value to latch in {@link #openBusLatch} for a bus access.
     *
     * A BYTE access leaves only the transferred byte on the latch, zero-extended, not the
     * whole word. The difference is observable only through a later faulty read (a
     * write-only register, {@link #faultyCustomRead}), which feeds the latch back into the
     * register: the vAmigaTS tests ADDXb/CMPMb read the byte of COLOR00 and show the cleared
     * high byte.
     */
    private static int busLatchValue(int value, boolean uds, boolean lds) {
        if (uds && lds) return value & 0xFFFF;   // word access: the whole word drives the bus
        if (uds)        return (value >> 8) & 0xFF;
        if (lds)        return value & 0xFF;
        return value & 0xFFFF;
    }

    private int readRtcBusData(Gary.Route route, int addr) {
        int value = 0;
        if (route.uds) {
            value |= (batteryClock.readByte(addr & ~1) & 0xFF) << 8;
        }
        if (route.lds) {
            value |= batteryClock.readByte(addr | 1) & 0xFF;
        }
        return value;
    }

    private int readGayleBusData(Gary.Route route, int addr) {
        int value = 0;
        if (route.uds) value |= gayle.readByte(addr & ~1) << 8;
        if (route.lds) value |= gayle.readByte(addr | 1);
        return value;
    }

    private void writeBusData(Gary.Route route, int addr, int data, boolean uds, boolean lds) {
        int eff = route.effectiveAddress;
        // The written data drives the bus for the next open-bus read. A BYTE write leaves
        // only the byte on the latch, EXCEPT towards the custom registers, which are written
        // with the byte duplicated on both lanes: there the latch keeps the word.
        openBusLatch = (route.target == Gary.Target.CUSTOM_REGS)
                ? (data & 0xFFFF)
                : busLatchValue(data, uds, lds);

        switch (route.target) {
            case CHIP_RAM:
            case OVERLAY_ROM:  // writes during overlay go to Chip RAM
                if (uds && lds) {
                    chipRAM.writeWord(eff & ~1, data);
                } else if (uds) {
                    chipRAM.writeByte(eff & ~1, (data >> 8) & 0xFF);
                } else if (lds) {
                    chipRAM.writeByte(eff | 1, data & 0xFF);
                }
                break;

            case CUSTOM_REGS:
                // Custom registers are 16-bit. For BYTE writes the 68000 duplicates the byte
                // on BOTH halves of the data bus, and the (word-only) custom chip latches the
                // whole word: the byte ends up replicated in upper AND lower, at even (UDS)
                // and odd (LDS) addresses alike. "MOVE.B #$40,AUD0VOL" (even address
                // $DFF0A8) gives $4040, volume 64 (Persian Gulf Inferno's gunshot).
                if (uds && lds) {
                    writeCustomRegister(eff, data);
                } else if (uds) {
                    int b = (data >> 8) & 0xFF;
                    writeCustomRegister(eff, (b << 8) | b);
                } else if (lds) {
                    // Alice takes a byte at an odd address as the low byte alone.
                    int b = data & 0xFF;
                    writeCustomRegister(eff, isAGA() ? b : (b << 8) | b);
                }
                break;

            case SLOW_RAM:
                if (slowRAM != null && (eff & ~1) < slowRAM.size()) {
                    if (uds && lds) {
                        slowRAM.writeWord(eff & ~1, data);
                    } else if (uds) {
                        slowRAM.writeByte(eff & ~1, (data >> 8) & 0xFF);
                    } else if (lds) {
                        slowRAM.writeByte(eff | 1, data & 0xFF);
                    }
                }
                break;

            case FAST_RAM:
                if (fastRam != null && (eff & ~1) < fastRam.sizeBytes()) {
                    RAM fr = fastRam.ram();
                    if (uds && lds) {
                        fr.writeWord(eff & ~1, data);
                    } else if (uds) {
                        fr.writeByte(eff & ~1, (data >> 8) & 0xFF);
                    } else if (lds) {
                        fr.writeByte(eff | 1, data & 0xFF);
                    }
                }
                break;

            case AUTOCONFIG:
                // The Kickstart assigns the base address by writing registers $48/$4A. The
                // CPU writes bytes at even addresses (UDS), the high byte of the bus. The
                // first board of the chain not yet configured takes the write.
                AutoConfigDevice dev = currentAutoConfigDevice();
                if (dev != null && gary != null) {
                    boolean configured = false;
                    if (uds) configured |= dev.pokeConfigByte(addr & ~1, (data >> 8) & 0xFF);
                    if (lds) configured |= dev.pokeConfigByte(addr | 1, data & 0xFF);
                    if (configured) {
                        // Map the board at the address just assigned; the next board (if
                        // any) now starts answering at $E8xxxx.
                        mapConfiguredDevice(dev);
                    }
                }
                break;

            case HD_CONTROLLER:
                if (uds && lds) {
                    HdController hd = hdControllerAt(addr);
                    if (hd != null) hd.pokeWord(addr & 0xFFFF, data);
                }
                break;

            case RTC:
                if (uds) batteryClock.writeByte(addr & ~1, (data >> 8) & 0xFF);
                if (lds) batteryClock.writeByte(addr | 1, data & 0xFF);
                break;

            case GAYLE:
                if (uds) gayle.writeByte(addr & ~1, (data >> 8) & 0xFF);
                if (lds) gayle.writeByte(addr | 1, data & 0xFF);
                break;
        }
    }

    // =========================================================================
    // Copper write (access with safety restrictions)
    // =========================================================================

    /**
     * A Copper write to a custom register.
     *
     * Hardware restrictions:
     *   $000-$03E: FORBIDDEN (never reachable by the Copper)
     *   $040-$07E: reachable only with CDANG=1 in COPCON
     *   $080-$1FE: always reachable
     */
    private boolean __inCopperWrite = false; // DEBUG TEMP
    public boolean isInCopperWrite() { return __inCopperWrite; }
    // VPOSR/VHPOSR return the beam position a few CCKs AHEAD of the DMA engine's current
    // position, wrapping to the next line at the end of the line; in cycles 0 and 1 of the
    // line the vertical field still reads the OLD vpos. Without this lead the CPU/beam sync
    // loops that wait for HP==0 (e.g. bbusy0 .synccpu3: `andi.w #$FF,VHPOSR; bne`) never
    // exit.
    //
    // The 5 decomposes as
    //     5 = 3 (position + 1 at the instant a custom read is sampled, i.e. our CCK + 2 + 1)
    //       + 2 (the bus FSM delivers a custom read's data in S5-S7, two CCKs after the S4
    //            where the position is sampled).
    // The residue is therefore the DELIVERY POINT of a custom read, not VHPOSR: touching it
    // moves the phase of every test that reads VHPOSR. At 2, 3 or 4 `BbusyTest`
    // collapses from 17/0 to 0/17, and at 3 `BplModTest` goes 4/2 -> 0/6. The
    // `synccpu` markers (COLOR00 $F0F/$606/$A0A/$404) measured with `SynccpuProbe` match
    // the reference cycle by cycle on `bplmod5` and `sprdiscpu1` at this value
    // (docs/vamigats-import-order.md, synccpu).
    private static final int BEAM_AHEAD_CCK =
            Integer.getInteger("amiga.beamahead", 5);

    /**
     * Sets LOL and with it the length of the current line.
     */
    private void updateLineLength(boolean longLine) {
        lol = longLine;
        maxHpos = longLine ? MAXHPOS_SHORT + 2 : MAXHPOS_SHORT;   // +1 CCK = +2 SC
    }

    /** H position (in CCK) returned by VHPOSR: current hpos + BEAM_AHEAD_CCK, wrapped at the line end. */
    private int beamPosHAhead(int hLores) {
        int lineCck = maxHpos >> 1;                 // 227, or 228 on an NTSC long line
        int cck = (hLores >> 1) + BEAM_AHEAD_CCK;
        if (cck >= lineCck) cck -= lineCck;
        return cck;
    }

    // -- ERSY / sync stopped -----------------------------------------------------
    // True if BPLCON0 has ERSY (bit 1) on. No genlock is emulated, so ERSY always means
    // "sync stopped".
    private boolean ersyActive() {
        return denise != null && (denise.getBplcon0() & 0x0002) != 0;
    }

    /**
     * Vpos that VPOSR/VHPOSR report <b>while the freeze is in progress</b>: the real one,
     * which no longer advances because {@code updateBeam} does not increment it.
     */
    private int frozenReadVpos() {
        return vposFrozen ? frozenVpos : vpos;
    }

    /**
     * Vpos used by the VPOSR/VHPOSR reads outside the freeze. Each word of a long read
     * samples the beam at its own bus cycle, so the two halves of {@code MOVE.L $DFF004}
     * can straddle a line boundary.
     */
    private int beamReadVpos() {
        return vposFrozen ? frozenVpos : vpos;
    }

    /** Hpos used by the VPOSR/VHPOSR reads outside the freeze, sampled at the read itself. */
    private int beamReadHpos() {
        return vposFrozen ? frozenHpos : hpos;
    }

    /** Vpos returned by VPOSR/VHPOSR for the position BEAM_AHEAD_CCK ahead. */
    private int beamPosVAhead(int vLines, int hLores) {
        int lineCck = maxHpos >> 1;
        int cck = (hLores >> 1) + BEAM_AHEAD_CCK;
        int v = vLines;
        if (cck >= lineCck) {
            cck -= lineCck;
            v = vLines + 1;
            int frameMaxV = (field == 0) ? maxVpos : (maxVpos - 1);
            if (v > frameMaxV) v = 0;               // frame wrap
        }
        // In cycles 0 and 1 the chip's vpos has not been incremented yet, so the vertical
        // field uses the old vpos.
        return (cck <= 1) ? vLines : v;
    }
    /**
     * A custom register write made by the <b>Copper</b>.
     *
     * <p><b>There is no address filter here, and there must not be one.</b> Which registers
     * the Copper may touch is decided by {@code Copper.isIllegalAddress()}, the one place of
     * the rule: with CDANG=1 <b>nothing is forbidden on ECS</b>, only {@code < $40} on OCS;
     * with CDANG=0 everything {@code < $80}. And the rule has an effect: a forbidden address
     * <b>stops the Copper</b>, it does not just skip a write. An unconditional
     * {@code < $40} filter here threw away legal writes on ECS with CDANG:
     * {@code Agnus/Registers/VPOS/probe11-13}, where the Copper writes VPOSW ($02A) to
     * shorten the frame.
     */
    public void copperWrite(int regAddr, int value) {
        regAddr &= 0x1FE;
        __inCopperWrite = true;
        try { writeCustomRegister(regAddr, value); } finally { __inCopperWrite = false; }
    }

    // =========================================================================
    // Custom register reads
    // =========================================================================

    /** package-private for testing */
    int getBplPt(int idx) { return bplPt[idx]; }

    public int readCustomRegister(int regAddr) {
        return readCustomRegisterImpl(regAddr);
    }

    /**
     * True during a {@link #peekCustomRegister(int)}: suppresses the side effects a custom
     * register read has on the hardware. {@link #faultyCustomRead}, the read of an OCS
     * write-only register, <b>rewrites the register</b> with the floating bus value, as the
     * hardware does; but {@link #peekByte} and {@link #peekWord} serve the disassembler and
     * debugger, and a memory dump crossing {@code $DFFxxx} would rewrite the sprite and
     * palette registers of the picture being inspected.
     */
    private boolean peeking = false;

    /**
     * Custom register read <b>without side effects</b>, for the disassembler, the debugger and
     * investigation harnesses. Covers the three places that change state: the "faulty" read
     * of write-only registers, the strobe registers, and the three Paula registers that
     * consume state ({@code Paula.peekRegister}).
     */
    public int peekCustomRegister(int regAddr) {
        boolean saved = peeking;
        peeking = true;
        try {
            return readCustomRegisterImpl(regAddr);
        } finally {
            peeking = saved;
        }
    }

    /**
     * If true, "faulty" reads return (and rewrite) 0 instead of the floating data bus value,
     * and so does BLTDDAT, which returns what was left on the bus.
     * For the vAmigaTS regression tests ONLY: their references are generated with the same
     * neutralisation, so the comparison isolates TIMING and not the floating bus values,
     * which are undefined analog behaviour.
     *
     * <p>Default false: games keep the real floating bus (needed by COPJMP1 / NBCD / ADDXb
     * cases).
     */
    public static volatile boolean neutralFloatingBus = false;

    /**
     * Read of an OCS write-only custom register ("faulty"): it returns the last value on the
     * (floating) data bus and, as a side effect, rewrites that value INTO the register.
     */
    private int faultyCustomRead(int regAddr) {
        int busVal = neutralFloatingBus ? 0 : (openBusLatch & 0xFFFF);
        // On Alice the register is rewritten with 0, not with the bus value.
        if (!peeking) writeCustomRegister(regAddr, isAGA() ? 0 : busVal);
        return busVal;
    }

    private int readCustomRegisterImpl(int regAddr) {
        regAddr &= 0x1FE;

        // DENISEID ($07C) on OCS: the register does NOT EXIST, and the read is not 0xFFFF but
        // a "faulty" write-only read like any other missing register. Returning 0xFFFF lit
        // all 16 bars of vAmigaTS Denise/Misc/Id/deniseid. The vAmigaTS README warns that on
        // a real A500 the value is NOT deterministic ("in most cases 0xFFFF, but not
        // always"): the signature of a floating bus.
        // On the 32-bit bus of a 68020 the missing register reads all ones.
        if (regAddr == 0x07C && denise != null && !denise.isECS() && !wideBusCpu) {
            return faultyCustomRead(regAddr);
        }

        // Denise (readable registers: JOY0/1DAT, CLXDAT, DENISEID)
        if (regAddr == 0x00A || regAddr == 0x00C || regAddr == 0x00E || regAddr == 0x07C
                || (regAddr >= 0x110 && regAddr <= 0x11A)) {
            return denise != null ? denise.readRegister(regAddr) : 0xFFFF;
        }

        // BPLCON0-4, sprite pos/ctl/data and colour registers ($140-$1BE) are write-only on
        // every Denise: the read returns the (floating) bus value AND rewrites it INTO the
        // register. That is what makes "garbage" colours visible when a handler does an RMW
        // on a write-only custom register (e.g. nbcd (a4) with a4=$DFF181 in the vAmigaTS
        // CPU/Timing tests). Lisa alone reads its colour registers back, under RDRAM.
        if ((regAddr >= 0x100 && regAddr <= 0x106) || regAddr == 0x10C
                || (regAddr >= 0x140 && regAddr <= 0x1BE)) {
            if (regAddr >= 0x180 && denise != null && denise.isAGA()) {
                return denise.readRegister(regAddr);
            }
            return faultyCustomRead(regAddr);
        }

        // Paula
        if (regAddr == 0x008 || (regAddr >= 0x010 && regAddr <= 0x01E)
                || (regAddr >= 0x0A0 && regAddr <= 0x0DF)) {
            if (paula == null) return 0xFFFF;
            return peeking ? paula.peekRegister(regAddr) : paula.readRegister(regAddr);
        }

        // Strobe Denise
        if (regAddr == 0x038 || regAddr == 0x03A || regAddr == 0x03C) {
            if (denise != null && !peeking) denise.readRegister(regAddr);
            return 0;
        }

        switch (regAddr) {
            case 0x000: // BLTDDAT: what was left on the bus (0 under neutralFloatingBus)
                return neutralFloatingBus ? 0 : lastBusData;
            case 0x002: // DMACONR
            case 0x096: {
                int res = dmacon & 0x07FF; // bits 10-0 readable
                if (blitter.isBusy()) res |= 0x4000; // BBUSY
                if (blitter.isZero()) res |= 0x2000; // BZERO
                return res;
            }
            case 0x004: { // VPOSR
                if (syncsStopped) {
                    // ERSY freeze: no lead, no advance. See VHPOSR below.
                    int result = agnusIdBits() << 8;
                    if ((frozenReadVpos() & 0x100) != 0) result |= 0x0001;
                    if (field == 0) result |= 0x8000;
                    return result;
                }
                int v = beamReadVpos();
                int h = beamReadHpos();
                // VPOSR:
                // Bit 15: LOF (Long Frame)
                // Bit 14-8: Chip ID
                // Bit 0: V8 (Vertical position bit 8)
                // The position returned is BEAM_AHEAD_CCK ahead (see BEAM_AHEAD_CCK).
                int vAhead = beamPosVAhead(v, h);
                int result = agnusIdBits() << 8;
                if ((vAhead & 0x100) != 0) result |= 0x0001; // V8
                // LOF=1 on long frames.
                if (field == 0) result |= 0x8000;
                return result;
            }
            case 0x006: { // VHPOSR
                if (syncsStopped) {
                    // Under the freeze HP is **0** (the horizontal counter is held at the
                    // start of the line), with no lead. `ersy2` measures it: its expected
                    // values go from $40BF,$40C9,$40D3,$40DD (the running position) to
                    // **$4000** repeated as soon as the line boundary starts the freeze.
                    return (frozenReadVpos() & 0xFF) << 8;
                }
                int v = beamReadVpos();
                int h = beamReadHpos();
                // VHPOSR: bits 15-8 = VP7-VP0, bits 7-0 = HP8-HP1 (hpos in CCK).
                // Position BEAM_AHEAD_CCK ahead (see VPOSR above). The advanced H value
                // decides both the HP field and (with the wrap at the line end) the vpos.
                return ((beamPosVAhead(v, h) & 0xFF) << 8) | (beamPosHAhead(h) & 0xFF);
            }
            case 0x108: return bpl1mod & 0xFFFF;
            case 0x10A: return bpl2mod & 0xFFFF;
            case 0x0E0: case 0x0E4: case 0x0E8: case 0x0EC: case 0x0F0: case 0x0F4: {
                int bidx = (regAddr - 0x0E0) / 4;
                return (bplPt[bidx] >> 16) & 0x7;
            }
            case 0x0E2: case 0x0E6: case 0x0EA: case 0x0EE: case 0x0F2: case 0x0F6: {
                int bidx = (regAddr - 0x0E2) / 4;
                return bplPt[bidx] & 0xFFFF;
            }
            case 0x120: case 0x124: case 0x128: case 0x12C: case 0x130: case 0x134: case 0x138: case 0x13C: {
                int sidx = (regAddr - 0x120) / 4;
                return (sprPt[sidx] >> 16) & 0x7;
            }
            case 0x122: case 0x126: case 0x12A: case 0x12E: case 0x132: case 0x136: case 0x13A: case 0x13E: {
                int sidx = (regAddr - 0x122) / 4;
                return sprPt[sidx] & 0xFFFF;
            }
            // INTENAR / INTREQR read through Paula
            case 0x09A:
                return paula != null ? paula.readRegister(0x01C) : 0xFFFF;
            case 0x09C:
                return paula != null ? paula.readRegister(0x01E) : 0xFFFF;
            case 0x02E: // COPCON
                // Write-only on the hardware, where a read returns bus noise. The current
                // CDANG state is returned for diagnostic tests that check it after reset.
                return copper.isDanger() ? 0x0002 : 0x0000;
            case 0x1DC: // BEAMCON0 (ECS)
                return beamcon0;
            default:
                // Write-only / unmapped custom registers: the read returns the floating
                // data bus value (and rewrites it into the register). Tests read a
                // write-only strobe and use the value (e.g. vAmigaTS BCHG
                // `move.w COPJMP1(a1),d0`: the bit the next bchg toggles depends on d0). The
                // strobe effect of the write-back is right too: accessing COPJMP1 (read or
                // write) makes the Copper jump.
                return faultyCustomRead(regAddr);
        }
    }

    // =========================================================================
    // Custom register writes
    // =========================================================================

    public void writeCustomRegister(int regAddr, int value) {
        regAddr &= 0x1FE;

        // Blitter
        if (regAddr >= 0x040 && regAddr <= 0x074) {
            blitter.writeRegister(regAddr, value);
            return;
        }

        // Copper
        if (regAddr >= 0x080 && regAddr <= 0x08A) {
            copper.writeRegister(regAddr, value);
            return;
        }

        // Denise
        if (regAddr == 0x036 || regAddr == 0x038 || regAddr == 0x03A || regAddr == 0x03C || regAddr == 0x03E || regAddr == 0x098
                || regAddr == 0x104 || regAddr == 0x106 || regAddr == 0x10C
                || (regAddr >= 0x110 && regAddr <= 0x11A)
                || (regAddr >= 0x140 && regAddr <= 0x1BE)
                || (isAGA() && (regAddr == 0x10E || regAddr == 0x11C || regAddr == 0x11E))) {
            // SPRxPOS/SPRxCTL ($140-$17E) are not Denise's alone: VSTART/VSTOP live in
            // AGNUS and drive the sprite DMA state machine, so a Copper/CPU write updates
            // sprVStart/sprVStop too. Games that (re)position sprites by writing POS/CTL
            // from the Copper list rather than in the descriptor in Chip RAM rely on it
            // (e.g. Superfrog).
            if (regAddr >= 0x140 && regAddr <= 0x17E) {
                int sidx = (regAddr - 0x140) >> 3;
                int sub  = (regAddr - 0x140) & 7;
                if (sub == 0)      writeSPRxPOS(sidx, value);
                else if (sub == 2) writeSPRxCTL(sidx, value);
            }
            if (denise != null) denise.writeRegister(regAddr, value);
            return;
        }

        // Paula (POTGO $034 included)
        if (regAddr == 0x024 || regAddr == 0x026
                || regAddr == 0x030 || regAddr == 0x032 || regAddr == 0x034
                || regAddr == 0x07E
                || regAddr == 0x09A || regAddr == 0x09C || regAddr == 0x09E
                || (regAddr >= 0x0A0 && regAddr <= 0x0DF)) {
            // INTENA ($09A) / INTREQ ($09C) reach Paula AT ONCE: the delay belongs only to
            // the copy the IPL is computed from, and lives in Paula
            // (INTREQ_SHADOW_DELAY_MC). See Paula.intreq2.
            if (paula != null) paula.writeRegister(regAddr, value);
            return;
        }

        // Denise strobes
        if (regAddr == 0x038 || regAddr == 0x03A || regAddr == 0x03C || regAddr == 0x03E) {
            if (denise != null) denise.writeRegister(regAddr, value);
            return;
        }

        // Agnus registers
        switch (regAddr) {
            case 0x020: // DSKPTH
                dskpt = (dskpt & 0x0000FFFF) | ((value << 16) & dmaPtrHiMask);
                if (paula != null) paula.setDiskDmaPointerHigh(value & 0x001F);
                break;
            case 0x022: // DSKPTL
                dskpt = (dskpt & dmaPtrHiMask) | (value & 0xFFFE);
                if (paula != null) paula.setDiskDmaPointer(dskpt);
                break;
            case 0x02A: // VPOSW
                // Bit 15 = LOF
                if ((value & 0x8000) != 0) field = 0; else field = 1;
                vpos = (vpos & 0xFF) | ((value & 1) << 8);
                triggerVpos = vpos;
                // LOL is always reset when VPOSW is written to (all NTSC Agnus versions
                // and ECS Agnus in NTSC mode): the current line goes back to short.
                if (lol) updateLineLength(false);
                break;
            case 0x02C: // VHPOSW
                vpos = (vpos & 0x100) | ((value >> 8) & 0xFF);
                triggerVpos = vpos;
                hpos = (value & 0xFF) << 1;
                break;
            case 0x08C: // COPINS
                // Copper instruction fetch strobe - handled by Agnus/Copper
                break;
            case 0x1FE: // NO-OP
                break;
            case 0x100: // BPLCON0 (some writes might come through general bus)
                setBPLCON0(value, getCpuPc());
                break;
            case 0x102: // BPLCON1
                setBPLCON1(value);
                break;
            case 0x02E: // COPCON
                copper.writeRegister(0x02E, value);
                break;

            case 0x08E: // DIWSTRT
                diwStrt = value;
                diwVStart = (diwStrt >> 8) & 0xFF;
                // The vertical comparison runs again AT ONCE, within the current line. See
                // recheckBitplaneVerticalWindow().
                recheckBitplaneVerticalWindow();
                if (denise != null) denise.writeRegister(0x08E, value);
                break;
            case 0x090: // DIWSTOP
                diwStop = value;
                diwVStop = (diwStop >> 8) & 0xFF;
                if ((diwStop & 0x8000) == 0) diwVStop += 256;
                recheckBitplaneVerticalWindow();
                if (denise != null) denise.writeRegister(0x090, value);
                break;

            case 0x092: // DDFSTRT
                ddfStrt = value;
                ddfCckStart = ddfStrt & ddfMask();
                noteDdfStrtWrite();
                break;
            case 0x094: // DDFSTOP
                ddfStop = value;
                ddfCckStop = decodeDdfStopCck(ddfStop);
                noteDdfStopWrite();
                break;

            case 0x096: // DMACON
                setDMACON(value, getCpuPc());
                break;

            case 0x028: // REFPTR
                if (!refreshSlotAllocatedAt(hpos >> 1)) refptr = refptrFromRegister(value);
                break;

            // Bitplane pointers (BPLxPTH/BPLxPTL)
            case 0x0E0: case 0x0E4: case 0x0E8: case 0x0EC: case 0x0F0: case 0x0F4: {
                int bidx = (regAddr - 0x0E0) / 4;
                if (bplPointerWriteIsLost(bidx)) break;
                bplPt[bidx] = (bplPt[bidx] & 0x0000FFFF) | ((value << 16) & dmaPtrHiMask);
                bplPtrInternal[bidx] = (bplPtrInternal[bidx] & 0x0000FFFF) | (bplPt[bidx] & dmaPtrHiMask);
                break;
            }
            case 0x0E2: case 0x0E6: case 0x0EA: case 0x0EE: case 0x0F2: case 0x0F6: {
                int bidx = (regAddr - 0x0E2) / 4;
                if (bplPointerWriteIsLost(bidx)) break;
                bplPt[bidx] = (bplPt[bidx] & dmaPtrHiMask) | (value & 0xFFFE);
                bplPtrInternal[bidx] = bplPt[bidx];
                break;
            }

            // BPL1MOD/BPL2MOD: the live register is always updated; the pipe's input stage
            // ONLY when the CPU writes, since it is the only one of the two that acts BEFORE
            // the same CCK's sampling. See bpl1modFetch.
            case 0x108: // BPL1MOD
                bpl1mod = (short) (value & 0xFFFE);
                if (!__inCopperWrite) bpl1modStage = bpl1mod;
                break;
            case 0x10A: // BPL2MOD
                bpl2mod = (short) (value & 0xFFFE);
                if (!__inCopperWrite) bpl2modStage = bpl2mod;
                break;

            // Sprite pointers (SPRxPTH/SPRxPTL). SPRxPT is the LIVE DMA pointer: a write
            // (CPU or Copper) changes where the next sprite DMA access fetches from, like
            // the bitplanes above. Games repositioning sprites through the Copper at the
            // start of the frame (e.g. Silkworm) rely on it.
            case 0x120: case 0x124: case 0x128: case 0x12C:
            case 0x130: case 0x134: case 0x138: case 0x13C: {
                int sidx = (regAddr - 0x120) / 4;
                if (sprPointerWriteIsLost(sidx)) break;
                sprPt[sidx] = (sprPt[sidx] & 0x0000FFFF) | ((value << 16) & dmaPtrHiMask);
                sprPtrInternal[sidx] = (sprPtrInternal[sidx] & 0x0000FFFF) | (sprPt[sidx] & dmaPtrHiMask);
                break;
            }
            case 0x122: case 0x126: case 0x12A: case 0x12E:
            case 0x132: case 0x136: case 0x13A: case 0x13E: {
                int sidx = (regAddr - 0x122) / 4;
                if (sprPointerWriteIsLost(sidx)) break;
                sprPt[sidx] = (sprPt[sidx] & dmaPtrHiMask) | (value & 0xFFFE);
                sprPtrInternal[sidx] = sprPt[sidx];
                break;
            }

            case 0x1DC: // BEAMCON0 (ECS only): ignored on OCS.
                if (beamcon0Writable()) {
                    final int prev = beamcon0;
                    beamcon0 = value;
                    // A changed value applies AT ONCE: maxVpos is reassigned in the CCK of
                    // the write. The rest of the mode (lol, vsync phase) follows, since it
                    // reads beamNtsc at every line.
                    if (value != prev) applyBeamcon0();
                }
                break;

            case 0x1E4: // DIWHIGH (ECS only): extends DIWSTRT/STOP. No-op on OCS.
                writeDIWHIGH(value);
                break;

            // BPL7PT/BPL8PT: the two planes Alice adds.
            case 0x0F8: case 0x0FC: {
                if (!isAGA()) break;
                int bidx = (regAddr - 0x0E0) / 4;
                if (bplPointerWriteIsLost(bidx)) break;
                bplPt[bidx] = (bplPt[bidx] & 0x0000FFFF) | ((value << 16) & dmaPtrHiMask);
                bplPtrInternal[bidx] = (bplPtrInternal[bidx] & 0x0000FFFF) | (bplPt[bidx] & dmaPtrHiMask);
                break;
            }
            case 0x0FA: case 0x0FE: {
                if (!isAGA()) break;
                int bidx = (regAddr - 0x0E2) / 4;
                if (bplPointerWriteIsLost(bidx)) break;
                bplPt[bidx] = (bplPt[bidx] & dmaPtrHiMask) | (value & 0xFFFE);
                bplPtrInternal[bidx] = bplPt[bidx];
                break;
            }

            case 0x1FC: // FMODE (Alice only)
                if (isAGA()) {
                    fmode = value & 0xC00F;
                    updateFetchGeometry();
                    // The geometry of the block follows at once; the width of the data and
                    // the step of the pointers two CCKs later.
                    recordRegisterChange(4, AgnusReg.FMODE_FETCH, fmode);
                    if (denise != null) denise.writeRegister(0x1FC, value);
                }
                break;
        }
    }

    // =========================================================================
    // Notifications from internal chips
    // =========================================================================

    /**
     * Called by the Blitter when it has completed: raises the BLIT interrupt (INTREQ bit 6,
     * IPL 3).
     */
    public void blitterFinished() {
        if (paula != null) paula.assertBlitInterrupt();
    }

    // =========================================================================
    // Reset
    // =========================================================================

    public void reset()      { hardReset(); }
    public void hardReset()  { internalReset(true);  }
    public void softReset()  { internalReset(false); }

    private void internalReset(boolean hardware) {
        gayle.reset();
        if (hardware) {
            masterClock = 0;
            vpos = 0;
            triggerVpos = 0;
            hpos = INITIAL_HPOS;         // the first line is short: see INITIAL_HPOS
            frameCount = 1;
            // The TOD pulses were computed for the current line, which no longer exists.
            todRiseHposA = todFallHposA = todRiseHposB = todFallHposB = -1;
        }
        Arrays.fill(trigger, NEVER);
        Arrays.fill(eventIds, EventID.EVENT_NONE);
        activeSlotMask = 0L;
        nextTrigger = NEVER;
        cpuBusIdle = false; // forces a handshake clear at the first idle cycle after reset
        // Rearm the CIA E-clock tick: the scheduler has just been cleared. Without it the
        // CIAs would stop after a CPU RESET instruction (softReset). The anchor is the same
        // phase as the arm at construction and must change with it: see the note there and
        // CiaEClockPhaseTest.
        scheduleAbs(EventSlot.SLOT_CIAA, masterClock + ciaAnchor(), EventID.CIA_EXECUTE);
        // Rearm audio sample production (SLOT_CH0): like SLOT_CIAA, the event reschedules
        // itself but the scheduler has just been cleared.
        if (paula != null) {
            scheduleAbs(EventSlot.SLOT_CH0, masterClock + audioSampleInterval(), EventID.AUDIO_MIX);
        }
        clearRegChanges();
        syncEvent = 0;

        dmacon = 0;
        bitplaneVerticalActive = false;
        updateDmaFlags();
        // LOF=0 ONLY at power-on, i.e. a SHORT frame (312 lines PAL, 262 NTSC). The frame
        // becomes long when software writes VPOSW with bit 15, which is how the Kickstart's
        // bootblock runs; the whole CPU/beam phase of the boot depends on it
        // (docs/vamigats-paula-audio-plan.md section 13.3).
        // The bit is NOT part of the custom-chip reset: the /RES line the CPU's RESET
        // instruction brings to the peripherals does not touch it. Clearing it on a soft reset
        // too gave short frames until the Kickstart rewrote VPOSW, about a hundred frames
        // after every ColdReboot (docs/vamigats-import-order.md section 26). The power-on
        // value is in the declaration of `field`, since construction does not pass here.
        if (hardware) {
            field = 1;
        }
        vsyncActive = false;
        displayRowShift = 0;
        syncsStopped = false;
        updateLineLength(false);             // LOL: always restart from a short line
        vposFrozen = false; frozenVpos = 0; frozenHpos = 0;
        cpuAccessTransferred = false; copperBusProcessed = false; ciaReadSampled = false; ciaWriteDone = false;
        cpuRouteCacheValid = false;
        cpuCurrentRoute = null;
        bprun = 0; bprunCycle = 0; ddfStopping = 0;
        seqBplcon0 = 0; seqBplcon0Count = 0;
        fmode = 0;
        bplFetchFmode = 0;
        sprFetchFmode = 0;
        updateFetchGeometry();
        bplReqNext = -1; bplReqLater = -1;
        dasSlotAllocated = false;
        ddfEnableOn = false; ddfLimitIn = false; ddfLimitOut = false;
        hwiOld = false; dmaconBpl2 = false;
        dmaconBplSeq = false;
        spriteDmaSeq = false; spriteDmaPipe = false; spriteDmaOnDecisionCck = -1;
        diwStrt = 0; diwStop = 0; ddfStrt = 0; ddfStop = 0;
        diwVStart = 0; diwVStop = 0; ddfCckStart = 0; ddfCckStop = 0;
        ddfStrtVal = 1; ddfStrtValOld = 1; ddfStopVal = 0; ddfStopValOld = 0;
        ddfStrtChangeCck = Long.MIN_VALUE; ddfStopChangeCck = Long.MIN_VALUE;
        bplcon1 = 0; scrollOdd = 0; scrollEven = 0;
        bitplaneVerticalActive = false;
        Arrays.fill(bplPt, 0); Arrays.fill(sprPt, 0);
        Arrays.fill(bplPtrInternal, 0); Arrays.fill(sprPtrInternal, 0);
        Arrays.fill(sprDmaEnabled, false);   // every sprite in the POS/CTL phase
        Arrays.fill(sprScanDouble, false);
        Arrays.fill(sprVStart, 0);
        Arrays.fill(sprVStop, 0);
        bpl1mod = 0; bpl2mod = 0;
        bpl1modFetch = 0; bpl2modFetch = 0;
        bpl1modStage = 0; bpl2modStage = 0;
        // Alice restarts its refresh address at the top of the 2 MB space at every reset.
        if (isAGA()) refptr = refreshResetValue();
        lastCiaAPra = -1;
        lastCiaADdra = -1;
        if (copper  != null) copper.reset();
        if (blitter != null) blitter.reset();
        if (denise  != null) denise.reset(hardware);
        if (paula   != null) paula.reset(hardware);

        // The CIAs are reset both at a hard reset and by the CPU RESET instruction.
        if (ciaA != null) ciaA.reset();
        if (ciaB != null) ciaB.reset();

        if (hardware) {
            // Gary is reset only at a hard reset (power-on). The CPU RESET instruction
            // resets only the peripheral chips on the bus, not the Gary gate array nor the
            // OVL flip-flop, which is cleared only by the first bus write after power-on or
            // driven by CIA-A PA0.
            if (gary != null) gary.reset();
        } else if (gary != null && gary.isGayleDecode()) {
            // Gayle is one of the chips the RESET instruction reaches.
            gary.resetOverlay();
        }
        floppyCatchUp();
        if (df0  != null) df0.reset();
        if (df1  != null) df1.reset();
        if (df2  != null) df2.reset();
        if (df3  != null) df3.reset();
        // The CIA-B reset leaves PRB = 0: the drives take it without acting on its edges.
        floppySelectSet();

        // Fast RAM: the CPU RESET instruction resets the Zorro bus too, so the board waits
        // for AutoConfig again (hard and soft reset alike) and the Kickstart reconfigures
        // it. The Fast RAM region is unmapped until a base address is assigned again; the
        // AutoConfig space stays active.
        if (fastRam != null) {
            fastRam.reset();
            if (gary != null) {
                gary.unmapFastRam();
            }
        }

        // Hard disk controllers: like the Fast RAM, RESET resets the Zorro bus and the boards
        // wait for AutoConfig again. Their pages are unmapped until base addresses are
        // assigned again.
        if (!hdControllers.isEmpty()) {
            for (HdController c : hdControllers) c.reset();
            if (gary != null) gary.unmapHdControllers();
        }

        // The /RES the CPU RESET instruction brings to the custom chips re-anchors the
        // horizontal counter: the current line ends here. Not at power-on, where the beam's
        // origin is INITIAL_HPOS by construction. See updateBeam().
        if (!hardware && poweredOn) {
            hpos = 0;
            resetRowPending = true;
        }
        poweredOn = true;

        if (gary != null) updateAutoconfigPresence();
    }

    // =========================================================================
    // DIW check
    // =========================================================================

    /**
     * Whether the position (hpos, vpos) is inside the display window.
     *
     * @param hpos horizontal position in system clocks (SC)
     * @param vpos vertical position in lines
     */
    public boolean isInsideDIW(int hpos, int vpos) {
        // Horizontal: H[7:0] from the register is directly in SC (system clock) units.
        // hstart = {0, H[7:0]} -> plain 8-bit value (bit8=0, range 0..255)
        // hstop  = {1, H[7:0]} -> +256 (bit8=1, range 256..511)
        int hstart = diwStrt & 0xFF;
        int hstop  = (diwStop & 0xFF) + 256;
        // Vertical: vstop bit8 = NOT bit15 of DIWSTOP register
        int vstart = (diwStrt >> 8) & 0xFF;
        int vstop  = (diwStop >> 8) & 0xFF;
        if ((diwStop & 0x8000) == 0) vstop += 256;

        return hpos >= hstart && hpos < hstop && vpos >= vstart && vpos < vstop;
    }

    // =========================================================================
    // DMA enable flags
    // =========================================================================

    /**
     * Recomputes the cached DMA flags from dmacon, at every dmacon change.
     * bitplaneDmaActive also depends on bitplaneVerticalActive, so it is updated both here
     * and in updateBitplaneVerticalWindow().
     */
    private void updateDmaFlags() {
        final boolean sprWas = spriteDmaEnabled;
        dmaMasterEnabled   = (dmacon & 0x0200) != 0;
        bitplaneDmaEnabled = dmaMasterEnabled && (dmacon & 0x0100) != 0; // BPLEN
        spriteDmaEnabled   = dmaMasterEnabled && (dmacon & 0x0020) != 0; // SPREN
        if (!sprWas && spriteDmaEnabled) {
            // See spriteDmaOnDecisionCck: the DECISION CCK this switch-on leaves "decided but
            // not allocated", one CCK after the write.
            spriteDmaOnDecisionCck = (masterClock >> 1) + 1;
        }
        diskDmaEnabled     = dmaMasterEnabled && (dmacon & 0x0010) != 0; // DSKEN
        audioDmaEnabled    = dmaMasterEnabled && (dmacon & 0x000F) != 0; // AUDxEN
        copperDmaEnabled   = dmaMasterEnabled && (dmacon & 0x0080) != 0; // COPEN
        blitterDmaEnabled  = dmaMasterEnabled && (dmacon & 0x0040) != 0; // BLTEN
        bitplaneDmaActive  = bitplaneDmaEnabled && bitplaneVerticalActive;
    }

    private boolean isBitplaneDMAActive() {
        return bitplaneDmaActive;
    }

    /**
     * How far {@link #triggerVpos}, the line the hardware's line trigger uses, precedes the
     * wrap of {@code hpos}, in half-CCK units: <b>3 CCK</b>.
     *
     * <p>Measured on 2026-09-06 (Copper and sprite logs on both sides): the sprite slots and
     * the six Copper MOVEs of {@code Agnus/DIW/OLDDIW/diw4} fall at the IDENTICAL instant,
     * but the vertical window is evaluated at the line trigger, at our CCK <b>224</b>, three
     * CCKs before our line boundary and with vpos ALREADY incremented. That is what
     * {@code diw4}/{@code diw5} measure: a Copper MOVE to DIWSTOP in those three CCKs is on
     * the NEW line (after the comparison, which already closed the window with the old
     * value).
     *
     * <p><b>Only the CONSUMERS move, not {@code vpos} itself.</b> Moving the whole counter was
     * tried and is a <b>veto of the net</b>: {@code Agnus/Copper} 94/20 -> 87/27
     * ({@code cross2}, {@code steal7}, {@code coptim4/5}, {@code oldcoptim3/5}),
     * {@code Paula/Interrupts} 82/0 -> 77/5 (the five {@code intreqcia}),
     * {@code Agnus/Registers} 123/3 -> 122/4.
     *
     * <p>The Copper is one of the consumers that must stay on {@code vpos}: its vertical
     * comparator has to see the old line at the Copper slot of CCK 225 and the new one at
     * CCK 0, which is exactly what {@code vpos} gives, and the horizontal comparator
     * ({@code bh + 2}, wrapping at {@code $E0}) is taken at the same instant. The wake-up
     * chain from a satisfied WAIT to the MOVE has no missing CCK to compensate. See
     * {@code docs/vamigats-import-order.md}, section 23.
     */
    private static final int TRIGGER_VPOS_LEAD_HPOS = 6;

    /**
     * The line trigger's {@code vpos}: {@code vpos} brought forward by
     * {@link #TRIGGER_VPOS_LEAD_HPOS}. It equals {@code vpos} for the whole line except the
     * last three CCKs, where it already holds the line about to start.
     *
     * <p>Read by the consumers the measurement exists for: the VERTICAL display window
     * ({@link #updateBitplaneVerticalWindow()} / {@link #compareBitplaneVerticalWindow()})
     * and {@link #sprStartStop(int)}. Consumers inside the line (the sprite DMA state machine
     * at slot 0, e.g.) do <b>not</b> read it: there {@code triggerVpos == vpos}.
     */
    private int triggerVpos = 0;

    /**
     * The horizontal sync the drives count their /RDY spin-up on. It comes with the line
     * advance, three CCKs before our wrap of {@code hpos}, and keeps coming while the syncs
     * are stopped. It falls after the CPU access of that CCK: a control line written there
     * reaches the drives first, so a motor switched on in that CCK already counts the line.
     * No cell elapses before the next rising edge, so the drives see the same bits as when
     * the edge waits for it.
     */
    private void hsyncDrives() {
        if (ciaB != null && lastCiabPrb >= 0) {
            byte prb = ciaB.peekPortB();
            if ((prb & 0xFF) != appliedCiabPrb) applyFloppyLines(prb);
        }
        if (df0 != null) df0.hsync();
        if (df1 != null) df1.hsync();
        if (df2 != null) df2.hsync();
        if (df3 != null) df3.hsync();
    }

    /**
     * The line trigger: brings {@link #triggerVpos} to the line about to start, three CCKs
     * before our line boundary, and re-evaluates the vertical window there.
     */
    private void advanceTriggerVpos() {
        if (syncsStopped) {
            return;
        }
        int next = vpos + 1;
        int wrapAt = (field == 0) ? (maxVpos + 1) : maxVpos;
        if (next >= wrapAt) {
            next = 0;
        }
        triggerVpos = next;
        vdiwBeforeTrigger = bitplaneVerticalActive;
        vdiwTriggerCck = masterClock >> 1;
        updateBitplaneVerticalWindow();
    }

    /**
     * The vertical window as it was before the line trigger of CCK {@link #vdiwTriggerCck}. The
     * bitplane sequencer decides that CCK before the trigger compares the new line, so a window
     * that opens or closes there reaches the sequencer one CCK later.
     */
    private boolean vdiwBeforeTrigger;
    private long vdiwTriggerCck = -1;

    /**
     * The vertical window flip-flop at the line trigger. Nothing resets it at line 0: the
     * last line of the field forces it off, and a window open there is closed as by DIWSTOP.
     * A window that starts at line 0 therefore opens normally, and one that is never closed
     * stays open into the next frame when the forced stop is disabled.
     */
    private void updateBitplaneVerticalWindow() {
        compareBitplaneVerticalWindow();
        bitplaneDmaActive = bitplaneDmaEnabled && bitplaneVerticalActive;
    }

    /**
     * True when the vertical blank does not force the vertical window off on the last line of
     * the field. Never on OCS; on ECS BEAMCON0 VARBEAMEN, VARVBEN or HARDDIS disable it.
     */
    protected boolean verticalWindowForceOffDisabled() { return false; }

    /**
     * The comparison itself: the line against Vstart and Vstop, the stop winning over the
     * start. On the last line of the field the vertical blank forces the stop and suppresses
     * the start, unless {@link #verticalWindowForceOffDisabled()}. Called both at the line
     * trigger and when DIWSTRT/DIWSTOP/DIWHIGH is written, see
     * {@link #recheckBitplaneVerticalWindow()}; the forced stop holds for the whole line, so a
     * write on the last line cannot reopen the window either.
     */
    private void compareBitplaneVerticalWindow() {
        final boolean forceOff = !verticalWindowForceOffDisabled()
                && triggerVpos == ((field == 0) ? maxVpos : maxVpos - 1);
        final boolean start = triggerVpos == diwVStart && !forceOff;
        final boolean end = triggerVpos == diwVStop || forceOff;
        if (start && !end) {
            bitplaneVerticalActive = true;
        } else if (end) {
            bitplaneVerticalActive = false;
        }
    }

    /**
     * <b>The vertical window is re-evaluated AT THE WRITE, not only at the start of the
     * line.</b> A write to DIWSTRT/DIWSTOP/DIWHIGH reruns the comparison <b>within the current
     * line</b>, after the bitplane decision of its own CCK, and the new state applies from the
     * next CCK.
     *
     * <p>Without it, a value equal to the <b>current</b> line never opens the window, and
     * since the value stays for the rest of the frame it never opens it later either: the
     * whole class A of the {@code Agnus/DIW} bank ({@code diwv10-12} drew no playfield pixel
     * from vpos 58 down).
     *
     * <p>Calling it at once is enough: the write must only <i>not</i> affect the fetch
     * decision of its own CCK, and both possible writers (the Copper in
     * {@code copper.fallingEdge()}, the CPU in {@code handleCpuDataTransferPhase()}) act in the
     * falling edge, after the current CCK's slot arbitration.
     */
    private void recheckBitplaneVerticalWindow() {
        compareBitplaneVerticalWindow();
        bitplaneDmaActive = bitplaneDmaEnabled && bitplaneVerticalActive;
    }

    private boolean isSpriteDMAEnabled() {
        return spriteDmaEnabled;
    }

    private boolean isDiskDMAEnabled() {
        return diskDmaEnabled;
    }

    private boolean isAudioDMAEnabled() {
        return audioDmaEnabled;
    }

    /**
     * COPEN <b>as the arbitration sees it</b>: until the current CCK has passed
     * {@link #copperDmaChangeCck} it is the <b>previous</b> value. The DMACON write sets the
     * threshold: see the comment there.
     */
    public boolean isCopperEnabled() {
        if ((masterClock >> 1) <= copperDmaChangeCck) {
            return !copperDmaEnabled;
        }
        return copperDmaEnabled;
    }

    /**
     * CCK up to which (inclusive) the arbitration uses the <b>old</b> COPEN.
     * {@code Long.MIN_VALUE} = no change pending.
     */
    private long copperDmaChangeCck = Long.MIN_VALUE;

    /**
     * COPEN as seen by the Copper cycle of the current CCK ({@link #copperTickEnabled}) and
     * by the one of the next CCK ({@link #copperAllocEnabled}): the value at allocation, one
     * CCK before the cycle acts.
     */
    private boolean copperTickEnabled, copperAllocEnabled;

    public boolean isOCS() {
        // With bit 13 (0x20) of the Agnus ID (VPOSR bits 14-8) clear it is OCS, otherwise
        // ECS. The ID comes from the agnusIdBits() hook, specialised per subclass.
        return (agnusIdBits() & 0x20) == 0;
    }

    public final boolean isECS() {
        return !isOCS();
    }

    /**
     * {@code true} on the AGA Agnus (Alice): FMODE, eight bitplanes and the wide fetch. Every
     * ECS rule applies to it too ({@link #isECS()} is true).
     */
    public boolean isAGA() {
        return false;
    }

    // =========================================================================
    // Chipset specialisation hooks
    // =========================================================================
    // Points where Agnus differs between OCS and ECS. The defaults of this class are the
    // OCS behaviour (so OcsAgnus is a semantic no-op); EcsAgnus overrides them. They sit on
    // configuration/register paths (NOT per pixel) so as not to weigh on the hot path.

    /**
     * Mask applied to the low bits of DDFSTRT/DDFSTOP. OCS = 0xFC (4-CCK granularity), ECS =
     * 0xFE (finer fetch step, 2 CCK in hires).
     */
    protected int ddfMask() { return 0xFC; }

    /**
     * CCK of the HARD end-of-fetch limit. Agnus raises the DDFSTOP condition at $D7 even when
     * DDFSTOP is programmed further: that keeps the fetch window within the line.
     */
    protected static final int DDF_HARD_STOP_CCK = 0xD7;

    /**
     * Hard START-of-fetch limit: at CCK {@code $18} Agnus releases the DDFSTOP condition
     * inherited from the previous line. The other end of {@link #DDF_HARD_STOP_CCK}.
     */
    protected static final int DDF_HARD_START_CCK = 0x18;

    /**
     * {@code true} if the HARD limits of the fetch window are disabled. Never on OCS; on ECS
     * BEAMCON0 VARBEAMEN/HARDDIS and the UHRES/SHRES bits of BPLCON0 disable them.
     */
    protected boolean ddfHardLimitsDisabled() { return false; }

    /**
     * Bits 14-8 of the Agnus ID returned by VPOSR ($004). OCS: {@code 0x00} (PAL) or
     * {@code 0x10} (NTSC); ECS adds bit 13 ({@code 0x20}). {@link #isOCS()} and
     * {@link #isECS()} derive from it.
     */
    protected int agnusIdBits() {
        return (mode == VideoMode.PAL) ? ID_OCS_PAL : ID_OCS_NTSC;
    }

    /**
     * {@code true} if BEAMCON0 ($1DC) is writable. An ECS-only register: on OCS the write is
     * ignored (default {@code false}).
     */
    protected boolean beamcon0Writable() { return false; }

    /**
     * Applies the mode written to BEAMCON0 to the beam, reduced to what is modelled: the line
     * count and the {@link #beamNtsc} mode that {@link #lol} and the VSYNC phase depend on.
     *
     * <p>The line count changes <b>in the CCK of the write</b>, not at the next frame: if the
     * beam is already past the new last line, the current frame closes at the first
     * opportunity.
     *
     * <p>The programmed modes (VARBEAMEN/VTOTAL/HTOTAL), variable syncs and the recomputed
     * display window are not modelled.
     */
    private void applyBeamcon0() {
        final boolean ntsc = (beamcon0 & BEAMCON0_PAL_BIT) == 0;
        if (ntsc == beamNtsc) {
            return;
        }
        beamNtsc = ntsc;
        maxVpos = ntsc ? 262 : 312;
    }


    /**
     * A write to DIWHIGH ($1E4), the ECS-only register that extends the high V bits of
     * DIWSTRT/STOP (Agnus side) and passes the H part (HSTART/HSTOP bit 8) on to Denise.
     * No-op on OCS. Layout (mask 0x2727): bits 0-2 = VSTART V8-V10, bit 5 = HSTART H8,
     * bits 8-10 = VSTOP V8-V10, bit 13 = HSTOP H8. Alice adds the fine horizontal bits 3-4
     * and 11-12 (mask 0x3F3F), which only Denise uses.
     */
    protected void writeDIWHIGH(int value) {
        if (!isECS()) return; // DIWHIGH is ECS-only: ignored on OCS.
        value &= isAGA() ? 0x3F3F : 0x2727;
        diwVStart = (diwVStart & 0xFF) | ((value & 0x07) << 8);
        diwVStop  = (diwVStop  & 0xFF) | (((value >> 8) & 0x07) << 8);
        // Same vertical re-check as DIWSTRT/DIWSTOP.
        recheckBitplaneVerticalWindow();
        if (denise != null) denise.writeRegister(0x1E4, value);
    }

    private boolean isBlitterEnabled() {
        return blitterDmaEnabled;
    }

    private boolean isBlitterNasty() {
        return (dmacon & 0x0400) != 0; // BLTPRI
    }

    // =========================================================================
    // Bitplane sequencer
    // =========================================================================

    /**
     * Offset between our CCK and the chip's horizontal counter, added to the DDF values
     * before comparing them. It is the offset of the register writes: the sequencer
     * decides on the same grid the writes land on, and the fetch follows the decision two
     * CCKs later (see {@link #bplReqNext}), so one offset serves both.
     */
    private static final int SEQ_PHASE = -4;

    /**
     * Whether the bitplane sequencer steps at this CCK. It is clocked by the parity of the
     * horizontal counter, so on a line of odd length the CCK that wraps the counter keeps
     * the parity and the sequence holds for one CCK. The counter wraps {@code -SEQ_PHASE}
     * CCKs before our line boundary.
     */
    private boolean sequencerClock(int cck) {
        final int lineCck = maxHpos >> 1;
        return (lineCck & 1) == 0 || cck != lineCck - 1 + SEQ_PHASE;
    }

    /**
     * The Copper's frame restart enters its strobe pipeline two CCKs before the line wrap
     * that closes the frame, and restarts the machine on the first Copper slot of line 0.
     * A strobe that reaches the pipeline around it merges with it.
     */
    private boolean isFrameStrobeCck(int cck) {
        if (cck != (maxHpos >> 1) - 2) {
            return false;
        }
        final int wrapAt = field == 0 ? maxVpos + 1 : maxVpos;
        return vpos == wrapAt - 1 && !syncsStopped && !(ersyActive() && !lol);
    }

    /**
     * Whether the Copper may issue a new request whose cycle lands at this CCK. Copper
     * requests are made on odd values of the horizontal counter and act one CCK later, so
     * the eligible CCKs follow the counter's parity rather than ours: in the body of the
     * line they are our even CCKs, but across the wrap of an odd-length line the counter
     * repeats a parity and the slot after CCK 222 is 225, not 226.
     */
    private boolean copperRequestParity(int cck) {
        final int lineCck = maxHpos >> 1;
        int h = cck - SEQ_PHASE - 1;
        if (h >= lineCck) h -= lineCck;
        return (h & 1) != 0;
    }

    /** {@code bprun} state: 0 = stopped, 1 = running, 2/3 = switching off over two CCKs. */
    private int bprun;
    /** Phase of the fetch sequence: advances at every CCK of {@code bprun}, not with hpos. */
    private int bprunCycle;
    /**
     * "DDFSTOP passed" condition: 0 = no, 1 = just raised, 2 = last block (where the moduli
     * are applied). It is not cleared at the end of the line: only
     * {@link #bplDmaNormalStop()} clears it.
     */
    private int ddfStopping;
    /** ECS: true between the DDFSTRT match and the DDFSTOP match. */
    private boolean ddfEnableOn;
    /** Hard limit $18-$D7, sampled with one CCK of delay. */
    private boolean ddfLimitIn, ddfLimitOut;

    /**
     * <b>DDFSTRT and DDFSTOP do not take effect at once</b>, and until they do the comparison
     * uses the <b>old</b> value (the new value is available after 2 CCKs). The previous value
     * is kept next to the new one, with the CCK of the write deciding between them:
     *
     * <pre>{
     * // write:  oldStart = start;  startCycle = now + 1 CCK;
     * (h == start && cyc >  startCycle) || (h == oldStart && cyc <  startCycle)
     * (h == stop  && cyc >  stopCycle)  || (h == oldStop  && cyc <= stopCycle)
     * }</pre>
     *
     * <b>The two do not use the same operator, deliberately</b>: DDFSTOP uses {@code <=} and
     * DDFSTRT {@code <}. At the CCK <b>exactly equal</b> to the start cycle neither condition
     * of DDFSTRT is true, <b>neither the old nor the new value</b> matches and the line does
     * not start at all: the case the README of section 14 states for {@code ddftimcop9} ("the
     * write cycle matches the trigger cycle which causes neither the old nor the new value to
     * match", with a line missing from the picture), which falls out of the two operators
     * rather than from a rule of its own. {@code ddftimcop9} is <b>bit-exact</b>.
     *
     * <p>The write and the match are on the same grid (see {@link #SEQ_PHASE}), so the
     * delay is the one CCK of the register itself.
     */
    private static final int DDF_WRITE_DELAY = 1;
    private int ddfStrtVal = 1, ddfStrtValOld = 1, ddfStopVal, ddfStopValOld;
    private long ddfStrtChangeCck = Long.MIN_VALUE, ddfStopChangeCck = Long.MIN_VALUE;

    /**
     * <b>The line counter is CIRCULAR, and the DDF comparison must not leave it.</b> Our grid
     * is shifted by {@link #SEQ_PHASE}, so for values <b>very close to zero</b> the
     * subtraction would put the match point <b>outside the line</b> and the match would never
     * happen.
     *
     * <p>The section 14 bank isolates the case: {@code hwstop1/7/8} write
     * {@code DDFSTRT = DDFSTOP = $00} for a few lines, so the start value is
     * {@code ($00 & $FC) | 1 = 1}, which matches at horizontal position 1 and arms the line
     * from there to the hard stop; {@code 1 + SEQ_PHASE = -1} does not exist, and those lines
     * stayed <b>empty</b>. {@code hwstop3} has the <b>same</b> LEFT/RIGHT coordinates but
     * writes {@code $08}, and was already green.
     */
    private int ddfMatchCck(int val) {
        int c = val + SEQ_PHASE;
        return c < 0 ? c + maxHpos / 2 : c;
    }

    private void noteDdfStrtWrite() {
        ddfStrtValOld    = ddfStrtVal;
        ddfStrtChangeCck = (masterClock >> 1) + DDF_WRITE_DELAY;
        ddfStrtVal       = (ddfStrt & ddfMask()) | 1;
    }

    private void noteDdfStopWrite() {
        ddfStopValOld    = ddfStopVal;
        ddfStopChangeCck = (masterClock >> 1) + DDF_WRITE_DELAY;
        ddfStopVal       = ddfStop & ddfMask();
    }


    /** Previous value of {@code hwi}: the machine starts on the EDGE, not the level. */
    private boolean hwiOld;
    /** OCS: BPLEN reaches the sequencer one CCK later than on ECS. */
    private boolean dmaconBpl2;

    /**
     * BPLEN as the sequencer sees it: sampled once per CCK, before the decision, so it holds
     * the value left by the register writes of the previous CCK. A Copper write to DMACON is
     * therefore seen by the decision of the next CCK, and both the plane request and the
     * start or stop of the sequencer follow it by one CCK. On OCS BPLEN reaches the sequencer
     * one CCK later still ({@link #dmaconBpl2}).
     */
    private boolean dmaconBplSeq;

    /**
     * The sprite DMA bit <b>as it was two CCKs before</b> the slot that uses it.
     *
     * <p>Sprite decisions are <b>early by construction</b>: their taps in the DMA request
     * shift register sit two positions ahead of the audio and disk ones (sprites have earlier
     * DMA decisions), the decision reads the sprite DMA bit there, and the transfer falls two
     * CCKs later.
     *
     * <p>That is what the {@code DMACON/sprdiscpu*} bank measures: when the CPU switches
     * sprite DMA off <b>between the decision and the transfer</b>, the fetch happens anyway.
     * Measured on {@code sprdiscpu1} ({@link io.github.zolfrik.amija.probe.SpriteDmaProbe}):
     * at line 65 the write falls at {@code h=24}, between the decision of SPRxDATA
     * ({@code h=23}) and its transfer ({@code h=25}): SPRxDATA is fetched, SPRxDATB is not.
     * Reading the live bit fetched neither, and SPRxDATA/SPRxDATB ended up on different lines.
     */
    private boolean spriteDmaSeq, spriteDmaPipe;

    /**
     * The <b>decision</b> CCK made void by sprite DMA being switched on, or -1.
     *
     * <p>If sprite DMA is switched on just when sprite DMA is decided, the channel is still
     * decided but it is not allocated. Switching SPREN on at CCK {@code W} makes the slot
     * whose <b>decision</b> falls at {@code W + 1} enter the pipeline unallocated: it
     * <b>fetches nothing and does not advance the pointer</b>, and the cycle stays free for
     * the Blitter.
     *
     * <p>With the decision two CCKs before the transfer (see {@link #spriteDmaSeq}), the
     * skipped slot is the one at {@code W + 3}. Measured on {@code sprenacpu2}, sprite 4,
     * line 125: the switch-on at {@code h=40}, the decision of slot B at {@code h=41}, no
     * fetch: the pointer stays at {@code $0705BA} and the next line restarts from there.
     * Without this rule there was one fetch too many and SPRxDATA/SPRxDATB stayed a word out
     * of step for the whole sprite.
     */
    private long spriteDmaOnDecisionCck = -1;

    /**
     * The state machine that decides whether the bitplane sequencer runs. Called <b>after</b>
     * deciding the current CCK's slot, so a machine armed at CCK X decides its first slot at
     * CCK X+1 (and fetches it at X+3).
     *
     * <p><b>BPRUN starts on the RISING EDGE of a compound condition</b>, not on its level:
     * <pre>
     *   hwi = dma &amp;&amp; diw &amp;&amp; ddfEnableOn &amp;&amp; (!ddfLimitOut || harddis);
     *   if (!bprun &amp;&amp; hwi &amp;&amp; !hwiOld)      { bprun = 1; bprunCycle = 0; }
     *   else if (bprun &amp;&amp; !hwi &amp;&amp; hwiOld)  { if (!ddfStopping) ddfStopping = 1; }
     * </pre>
     * BPRUN can only start if the DMA, DIW or DDF state has changed since last time. That is
     * the <b>sequencer bug</b> {@code DMACON/bpoffbug1} and {@code reenable2/3} measure:
     * switching BPLEN off inside the window sets {@code ddfStopping = 1} on the falling edge,
     * and when BPLEN comes back the sequence restarts but stops at the end of the current
     * block ("early termination", which the bank's README describes as an Agnus bug).
     *
     * <p>The switch-off is not immediate either: {@code (!dma || !diw) && bprun == 1} takes
     * {@code bprun} to 2, and from there 2 -> 3 -> 0, <b>two CCKs</b> of tail.
     *
     * <p>OCS and ECS are two different branches. On OCS BPLEN arrives one CCK later
     * ({@code dmaconBpl2}), the start is on the DDFSTRT <b>match</b> rather than on the edge of
     * {@code hwi}, and DDFSTOP and the hard limit raise {@code ddfStopping} rather than the
     * fall of {@code hwi}.
     */
    private void decideBpl(int cck) {
        // The last line of the field closes the vertical window itself (see
        // updateBitplaneVerticalWindow), so the sequencer needs no gate of its own there.
        // The DDFSTRT value is compared with bit 0 set (DDFSTRT matches on an odd cycle),
        // while DDFSTOP stays even.
        final long cyc = masterClock >> 1;
        final boolean vdiw = cyc == vdiwTriggerCck ? vdiwBeforeTrigger : bitplaneVerticalActive;
        final boolean diw = vdiw;
        final boolean harddis = ddfHardLimitsDisabled();
        final boolean ddfStrtMatch =
                   (cck == ddfMatchCck(ddfStrtVal)    && cyc >  ddfStrtChangeCck)
                || (cck == ddfMatchCck(ddfStrtValOld) && cyc <  ddfStrtChangeCck);
        final boolean ddfStopMatch =
                   (cck == ddfMatchCck(ddfStopVal)    && cyc >  ddfStopChangeCck)
                || (cck == ddfMatchCck(ddfStopValOld) && cyc <= ddfStopChangeCck);

        ddfLimitOut = ddfLimitIn;

        if (isECS()) {
            final boolean dma = dmaconBplSeq;

            if (cck == DDF_HARD_START_CCK + SEQ_PHASE) ddfLimitIn = false;
            if (cck == DDF_HARD_STOP_CCK + SEQ_PHASE && !harddis) ddfLimitIn = true;

            if (ddfStrtMatch) ddfEnableOn = true;
            if (ddfStopMatch) ddfEnableOn = false;

            boolean hwi = dma && diw && ddfEnableOn && (!ddfLimitOut || harddis);
            if (bprun == 0 && hwi && !hwiOld) {
                bprun = 1;
                bprunCycle = 0;
            } else if (bprun != 0 && !hwi && hwiOld) {
                if (ddfStopping == 0) ddfStopping = 1;
            }
            hwiOld = hwi;

            if (bprun == 3) bprun = 0;
            if (bprun > 1) bprun++;
            if ((!dma || !diw) && bprun == 1) bprun = 2;

        } else {
            // OCS: BPLEN delayed by one more CCK than on ECS.
            final boolean dma = dmaconBpl2;
            dmaconBpl2 = dmaconBplSeq;

            if (ddfStopMatch && bprun != 0 && ddfStopping == 0) ddfStopping = 1;

            if (cck == DDF_HARD_START_CCK + SEQ_PHASE) ddfLimitIn = false;
            if (cck == DDF_HARD_STOP_CCK + SEQ_PHASE && bprun != 0 && ddfStopping == 0) ddfStopping = 1;

            boolean ddfstrtMatch = ddfStrtMatch;
            if (!ddfLimitOut && ddfstrtMatch && bprun == 0 && dma && diw) {
                bprun = 1;
                bprunCycle = 0;
            }

            if (bprun == 2) {
                // If DDF has already passed, jump to the last block (Scoopex's "Crash
                // Landing" cracktro).
                if (ddfStopping == 1) ddfStopping = 2;
                bprun = 0;
            }

            if ((!dma || !diw) && bprun == 1) {
                bprun = 2;
                if (ddfStopping == 1) {
                    ddfStopping = 2;
                    bprun = 0;
                }
            }
        }
    }

    /**
     * Normal end of the sequence. On OCS it also rearms the hard limit, which keeps a second
     * window from opening in the same line.
     */
    private void bplDmaNormalStop() {
        ddfStopping = 0;
        bprun = 0;
        if (!isECS()) ddfLimitIn = true;
    }

    private int decodeDdfStopCck(int value) {
        return (value & ddfMask()) + 8;
    }

    // =========================================================================
    // Public accessors
    // =========================================================================

    public String getArbitrationReason() {
        if (busTakenByCustom) {
            int cck = hpos >> 1;
            BusOwner owner = OWNER_VALUES[busOwners[cck]];
            return switch (owner) {
                case REFRESH -> "DRAM_REFRESH";
                case DISK -> "DISK_DMA";
                case AUDIO0, AUDIO1, AUDIO2, AUDIO3 -> "AUDIO_DMA";
                case SPRITE0, SPRITE1, SPRITE2, SPRITE3, SPRITE4, SPRITE5, SPRITE6, SPRITE7 -> "SPRITE_DMA";
                case BPL1, BPL2, BPL3, BPL4, BPL5, BPL6, BPL7, BPL8 -> "BITPLANE_DMA";
                case COPPER -> "COPPER_FETCH";
                case BLITTER -> "BLITTER_FETCH";
                default -> "CUSTOM_DMA";
            };
        }
        if (cpuBusReq.as && !cpuBusResp.ack) {
            if (isBlitterEnabled() && blitter.isRunning() && !isBlitterNasty() && !bls) {
                return "BLITTER_PRIORITY";
            }
            if (isBlitterNasty() && blitter.isRunning()) {
                return "BLITTER_NASTY";
            }
            if ((hpos & 1) != 0) {
                return "CPU_WAIT_CCK";
            }
            return "CPU_WAIT_DMA";
        }
        return "IDLE";
    }

    public int getCpuPc() {
        return processor != null ? processor.getOpcodeAddress() : 0;
    }

    public int  getVpos()            { return vpos; }
    public int  getFrameCount()      { return frameCount; }

    public void notifyDiskTaskArmed() {
    }

    public void notifyDskblkFired() {
    }

    /**
     * Puts the selected drive into write mode (DSKLEN with DMAEN+WRITE). Paula is the source
     * of the bits: the drive queries it once per MFM cell.
     */
    public void startFloppyWriting() {
        floppyCatchUp();
        FloppyDrive drive = getSelectedDrive();
        if (drive != null) drive.setWriting(true);
    }

    /** Ends writing on every drive (DSKLEN cleared): the track is flushed. */
    public void stopFloppyWriting() {
        floppyCatchUp();
        df0.setWriting(false);
        df1.setWriting(false);
        df2.setWriting(false);
        df3.setWriting(false);
    }

    public int  getHpos()            { return hpos; }
    public int  getMaxHpos()         { return maxHpos; }
    public int  getMaxVpos()         { return maxVpos; }
    public int  getDiwVStart()       { return diwVStart; }
    public int  getDiwVStop()        { return diwVStop; }

    /** The high word of the bits a DMA pointer keeps, see {@link #dmaPtrHiMask}. */
    public int  getDmaPtrHiMask()    { return dmaPtrHiMask; }
    /** The bits a DMA pointer keeps, see {@link #dmaPtrMask}. */
    public int  getDmaPtrMask()      { return dmaPtrMask; }
    public int  getLastCompletedFrameField() { return lastCompletedFrameField; }
    /**
     * The Blitter as the COPPER sees it (BFD): busy for <b>one CCK more</b> than the CPU sees
     * through DMACONR. See {@link Blitter#isBusyForCopper}.
     */
    public boolean isBlitterFinished() { return !blitter.isBusyForCopper(); }
    public FloppyDrive getDf0()      { return df0; }
    public FloppyDrive getDf1()      { return df1; }
    public FloppyDrive getDf2()      { return df2; }
    public FloppyDrive getDf3()      { return df3; }
    public Copper  getCopper()       { return copper; }
    public Blitter getBlitter()      { return blitter; }
    /** The clocked CPU, or null when the processor is event-driven (see {@link #getProcessor()}). */
    public AbstractMotorola getCpu()    { return cpu; }
    public Processor getProcessor()     { return processor; }
    public Paula   getPaula()        { return paula; }
    public int     getDMACON()       { return dmacon; }

    /**
     * Direct diagnostic read from Chip RAM (no bus/Gary side effects). Returns 0 outside
     * the memory it covers.
     */
    public int peekChipWord(int addr) {
        addr &= 0xFFFFFF;
        // Chip RAM, as much as is installed ($000000 up).
        if (chipRAM != null && addr >= 0 && addr + 1 < chipRAM.size()) {
            return chipRAM.readWord(addr) & 0xFFFF;
        }
        // Slow RAM ($C00000-$C7FFFF, 512 KB): many games load their code there.
        if (slowRAM != null && addr >= 0xC00000 && addr + 1 < 0xC80000) {
            return slowRAM.readWord(addr - 0xC00000) & 0xFFFF;
        }
        // Kickstart ROM, socket window $F80000-$FFFFFF, to disassemble ROM code. The offset
        // is masked as Gary does: a 256 KB ROM mirrors twice in the window, a 512 KB one
        // fills it.
        if (rom != null && addr >= 0xF80000) {
            return rom.readWord(addr & (rom.size() - 1)) & 0xFFFF;
        }
        return 0;
    }
    public int peekChipLong(int addr) {
        return (peekChipWord(addr) << 16) | peekChipWord(addr + 2);
    }

    /** Live display state for diagnostics (bitplane pointers, DDF/DIW window). */
    public String getDisplayStateString() {
        int bc0 = denise != null ? denise.getBplcon0() : 0;
        return String.format(
            "BPLCON0=$%04X BPU=%d HIRES=%b | ddfStrt=$%02X ddfStop=$%02X ddfCck=%d..%d | "
          + "diwV=%d..%d diwStrt=$%04X diwStop=$%04X | bplPt0=$%06X bplPt1=$%06X int0=$%06X int1=$%06X | "
          + "bpl1mod=%d bpl2mod=%d bplcon1=$%04X dmacon=$%04X",
            bc0 & 0xFFFF, (bc0 >> 12) & 7, (bc0 & 0x8000) != 0,
            ddfStrt & 0xFF, ddfStop & 0xFF, ddfCckStart, ddfCckStop,
            diwVStart, diwVStop, diwStrt & 0xFFFF, diwStop & 0xFFFF,
            bplPt[0] & 0xFFFFFF, bplPt[1] & 0xFFFFFF,
            bplPtrInternal[0] & 0xFFFFFF, bplPtrInternal[1] & 0xFFFFFF,
            bpl1mod, bpl2mod, bplcon1 & 0xFFFF, dmacon & 0xFFFF);
    }

    /** Physical track of the selected drive (disk diagnostics); -1 if none. */
    public int getSelectedDriveTrack() {
        FloppyDrive d = getSelectedDrive();
        return d != null ? d.getPhysicalTrack() : -1;
    }

    /**
     * Disk DMA start: re-phases the cell clock of EVERY drive, not just the selected
     * one. A drive that is not being read, spinning or not, loses the fraction of a
     * cell it had accumulated, like the one being read.
     */
    public void restartDrivesBitCell() {
        floppyCatchUp();
        if (df0 != null) df0.restartBitCell();
        if (df1 != null) df1.restartBitCell();
        if (df2 != null) df2.restartBitCell();
        if (df3 != null) df3.restartBitCell();
    }

    public boolean inVBlankArea(int vpos) {
        return vpos < (beamNtsc ? 20 : 26);
    }

    public void triggerLightPen() {
        if (!vposFrozen) {
            vposFrozen = true;
            frozenVpos = vpos;
            frozenHpos = hpos;
        }
    }

    // =========================================================================
    // Memory accessors for the disassembler / debugger
    // =========================================================================

    public int peekByte(int address) {
        Gary.Route route = gary.route(address, Gary.AccessType.READ, (address & 1) == 0, (address & 1) != 0, true);
        if (!route.ok) return 0;
        int eff = route.effectiveAddress;
        return switch (route.target) {
            case CHIP_RAM                       -> chipRAM.readByte(eff);
            case KICKSTART_ROM, OVERLAY_ROM     -> rom.readByte(eff);
            case SLOW_RAM                       -> slowRAM.readByte(eff);
            case FAST_RAM                       -> (fastRam != null) ? fastRam.ram().readByte(eff) : 0x00;
            case CUSTOM_REGS                    -> (address % 2 == 0) ? (peekCustomRegister(address) >> 8) & 0xFF : peekCustomRegister(address) & 0xFF;
            case RTC                            -> batteryClock.readByte(address);
            case CIA_A, CIA_B, CIA_BOTH         -> {
                int word = peekCiaData(route.target, address);
                yield (address % 2 == 0) ? (word >> 8) & 0xFF : word & 0xFF;
            }
            default                             -> 0x00;
        };
    }

    public int peekWord(int address) {
        Gary.Route route = gary.route(address, Gary.AccessType.READ, true, true, true);
        if (!route.ok) return 0;
        int eff = route.effectiveAddress;
        return switch (route.target) {
            case CHIP_RAM                       -> chipRAM.readWord(eff);
            case KICKSTART_ROM, OVERLAY_ROM     -> rom.readWord(eff);
            case SLOW_RAM                       -> slowRAM.readWord(eff);
            case FAST_RAM                       -> (fastRam != null) ? fastRam.ram().readWord(eff) : 0x0000;
            case CUSTOM_REGS                    -> peekCustomRegister(address);
            case RTC                            -> batteryClock.readWord(address);
            case CIA_A, CIA_B, CIA_BOTH         -> peekCiaData(route.target, address);
            default                             -> 0x0000;
        };
    }

    /**
     * Read of a CIA space for the debugger. It goes through {@code MOS8520.peekRegister}, not
     * {@code readRegister}: on the ICR the latter releases the /IRQ line and arms the ack
     * pipeline, so a memory dump crossing $BFExxx would clear a pending interrupt of the
     * machine being inspected.
     */
    private int peekCiaData(Gary.Target target, int address) {
        int reg = (address >> 8) & 0x0F;
        int result = lastBusData;
        if (target == Gary.Target.CIA_A || target == Gary.Target.CIA_BOTH) {
            result = (result & 0xFF00) | (ciaA.peekRegister(reg) & 0xFF);
        }
        if (target == Gary.Target.CIA_B || target == Gary.Target.CIA_BOTH) {
            result = (result & 0x00FF) | ((ciaB.peekRegister(reg) & 0xFF) << 8);
        }
        return result;
    }

    public int peekLong(int address) {
        int hi = peekWord(address);
        int lo = peekWord(address + 2);
        return (hi << 16) | (lo & 0xFFFF);
    }

    public int peekRAMByte(int address) {
        return peekByte(address);
    }

    public int peekRAMWord(int address) {
        return peekWord(address);
    }

    public int peekRAMLong(int address) {
        return peekLong(address);
    }

    public boolean isTraceEnabled() {
        return false;
    }

    public int getLastBusData() {
        return lastBusData;
    }
}

