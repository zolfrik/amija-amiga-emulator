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

package io.github.zolfrik.amija.cpu.m68k;

import io.github.zolfrik.amija.cpu.CpuModel;
import io.github.zolfrik.amija.cpu.event.BusFault;
import io.github.zolfrik.amija.cpu.event.CpuBus;
import io.github.zolfrik.amija.cpu.event.EventCpu;
import io.github.zolfrik.amija.cpu.event.TimeBase;

/**
 * The Motorola 68EC020 of the Amiga 1200, event-driven.
 *
 * <p>Every instruction runs as straight-line code ({@link Op020}, one handler per opcode from
 * {@link Decoder020}) and calls the {@link CpuBus} for each access; the bus brings the rest of
 * the machine up to the time of the access. The processor itself only counts time.
 *
 * <p><b>Timing model.</b> The time of an instruction is not taken from a table: it emerges
 * from four mechanisms, all modelled here.
 * <ol>
 *   <li>The three-word instruction queue: every word the instruction consumes costs two
 *       clocks, less the length of the previous bus access, which overlaps execution
 *       ({@link #internal(int)}).</li>
 *   <li>The bus accesses, timed by the bus according to the memory they reach.</li>
 *   <li>The 64-entry instruction cache and its 32-bit holding register: a hit takes no bus
 *       cycle; two opcode fetches back to back cost two more clocks.</li>
 *   <li>A few explicit internal times (multiply, divide, STOP), and two clocks every sixteen
 *       instructions.</li>
 * </ol>
 *
 * <p>All the state is primitive fields and primitive arrays, so the reflective save state
 * captures it; between two calls of {@link #runUntil(long)} the processor is always at an
 * instruction boundary.
 */
public final class Motorola68020 implements EventCpu {

    /** One processor clock at 14 MHz, in bus time units. */
    static final int CLK = TimeBase.CLOCK_14MHZ;

    // ---------------------------------------------------------------------------------------
    // Programmer's model
    // ---------------------------------------------------------------------------------------

    /** D0-D7 then A0-A7: the register field of an effective address indexes it directly. */
    final int[] r = new int[16];
    /** The inactive stack pointers; the active one is A7. */
    int usp, isp, msp;
    /** Address of the instruction being executed; the next one's once it completes. */
    int pc;
    /** Address of the instruction being executed, for exception frames. */
    int instructionPc;
    /** Offset from {@link #pc} of the next word of the instruction stream to consume. */
    int po;
    int t1, t0, s, m, intmask;
    boolean fx, fn, fz, fv, fc;
    int vbr, sfc, dfc, cacr, caar;

    // ---------------------------------------------------------------------------------------
    // Instruction queue, holding register, cache, pipeline
    // ---------------------------------------------------------------------------------------

    /** The opcode of the next instruction (prefetched). */
    int irc;
    /** Instruction register as STOP uses it (its immediate operand). */
    int ir;
    int pf0, pf1, pf2;
    /** Validity of the queue entries, bit 0..2. */
    int pfValid;
    int holdAddr = -1, holdData;
    final int[] cacheTag = new int[64];
    final int[] cacheData = new int[64];
    final boolean[] cacheValid = new boolean[64];
    int pipelinePos, pipelineR8a = -1, pipelineR8b = -1, pipelineStop;

    // ---------------------------------------------------------------------------------------
    // Timing
    // ---------------------------------------------------------------------------------------

    /** Start and end of the last bus access, and end of the last opcode fetch. */
    long ceStart, ceEnd, cePrefetchEnd;
    /** Instructions since the last two-clock pause. */
    int extraCount;

    // ---------------------------------------------------------------------------------------
    // Exceptions, interrupts, special states
    // ---------------------------------------------------------------------------------------

    static final int SPC_INT = 1, SPC_DOINT = 2, SPC_TRACE = 4, SPC_DOTRACE = 8;
    int spc;
    int lastIpl;
    /** The IPL level seen at the last instruction boundary (see the main loop). */
    int iplSeen;
    /** 0 running, 1 stopped by STOP. */
    int stopped;
    int tracePc;
    /** Units of system time /RESET is still held for; the reset sequence follows. */
    long resetHold;
    boolean resetPending;
    /** Two exceptions while building a frame: the processor halts. */
    boolean halted;

    /** Optional observer of the instruction queue (tests only). */
    QueueObserver observer;

    /** Optional observer of instruction boundaries (tests and tools only). */
    private InstructionObserver instructionObserver;

    /** Observer called at every instruction boundary, before the instruction runs. */
    public interface InstructionObserver {
        void beforeInstruction(Motorola68020 cpu);
    }

    public void setInstructionObserver(InstructionObserver o) {
        this.instructionObserver = o;
    }

    CpuBus bus;

    /** Completed instructions (diagnostics). */
    public long instructionCount;

    @Override
    public void attach(CpuBus bus) {
        this.bus = bus;
    }

    @Override
    public CpuModel model() {
        return CpuModel.M68020;
    }

    // =======================================================================================
    // Run loop
    // =======================================================================================

    @Override
    public void runUntil(long deadline) {
        final CpuBus b = bus;
        while (b.cycles() < deadline) {
            if (resetHold > 0 || resetPending) {
                holdReset(deadline);
                continue;
            }
            if (halted) {
                b.idle(TimeBase.CCK);
                continue;
            }
            instructionPc = pc;
            po = 2;
            final int op = irc;
            if (instructionObserver != null) instructionObserver.beforeInstruction(this);
            try {
                Decoder020.OPS[op].run(this, op);
            } catch (BusFault f) {
                busError();
            }
            if (extraCount >= 16) {
                extraCount = 0;
                b.idle(2 * CLK);
            }
            extraCount++;
            instructionCount++;
            // An interrupt state change reaches the processor here: a level above the mask
            // that appeared during the instruction is recognised at this boundary and taken
            // at the next one. It is the change that is recognised, not the level: a level
            // still pending where an interrupt is being taken must not arm another one for
            // the next boundary. A pending level unmasked later is noted by setSR.
            final int pin = b.ipl();
            if (pin != iplSeen) {
                iplSeen = pin;
                if (pin > intmask) spc |= SPC_DOINT;
            }
            if (spc != 0) specialties();
        }
    }

    private void holdReset(long deadline) {
        while (resetHold > 0 && bus.cycles() < deadline) {
            final long step = Math.min(resetHold, TimeBase.CCK);
            bus.idle((int) step);
            resetHold -= step;
        }
        if (resetHold <= 0 && resetPending) {
            resetPending = false;
            bus.resetLine(false);
            resetSequence();
        }
    }

    /** Interrupts and trace, at an instruction boundary. */
    private void specialties() {
        final int flags = spc;
        if ((flags & SPC_DOTRACE) != 0) exception(9);
        if ((flags & SPC_TRACE) != 0) doTrace();
        if ((flags & SPC_INT) != 0) {
            final int level = bus.ipl();
            spc &= ~(SPC_INT | SPC_DOINT);
            if (level > intmask || (level == 7 && level > lastIpl)) interrupt(level);
            lastIpl = level;
        }
        if ((flags & SPC_DOINT) != 0) {
            spc &= ~SPC_DOINT;
            spc |= SPC_INT;
        }
    }

    private void doTrace() {
        if (stopped != 0) return;
        tracePc = pc;
        if (t0 != 0 && t1 == 0) return;
        if (t1 != 0) activateTrace();
    }

    private void activateTrace() {
        spc &= ~SPC_TRACE;
        spc |= SPC_DOTRACE;
    }

    /** A flow change under T0 trace. */
    void checkT0Trace() {
        if (t0 != 0 && t1 == 0) {
            spc &= ~SPC_TRACE;
            spc |= SPC_DOTRACE;
        }
    }

    private void interrupt(int level) {
        for (;;) {
            exception(level + 24);
            final int next = bus.ipl();
            if (next <= 0 || intmask >= next) break;
            level = next;
        }
        if (bus.ipl() > intmask) spc |= SPC_DOINT;
    }

    // =======================================================================================
    // Reset
    // =======================================================================================

    @Override
    public void powerOnReset(int startDelayCck) {
        reset();
        // The first instruction fetch comes one colour clock after the start delay.
        resetHold = (long) (Math.max(0, startDelayCck) + 1) * TimeBase.CCK;
    }

    @Override
    public void reset() {
        resetPending = true;
        if (bus != null) bus.resetLine(true);
        resetHold = Math.max(resetHold, 512L * TimeBase.CCK / 2);
        stopped = 0;
        halted = false;
        spc = 0;
    }

    @Override
    public void assertReset(int cycles) {
        resetPending = true;
        if (bus != null) bus.resetLine(true);
        resetHold = Math.max(resetHold, (long) cycles * (TimeBase.CCK / 2));
    }

    private void resetSequence() {
        spc = 0;
        lastIpl = 0;
        iplSeen = 0;
        s = 1;
        m = 0;
        t1 = t0 = 0;
        fx = fn = fz = fv = fc = false;
        intmask = 7;
        r[15] = bus.readLongUntimed(0);
        isp = r[15];
        pc = bus.readLongUntimed(4);
        vbr = sfc = dfc = 0;
        irc = 0xFFFF;
        caar = 0;
        cacr = 8;
        setCaches();
        ceStart = ceEnd = 0;
        stopped = 0;
        halted = false;
        fillPrefetch();
    }

    // =======================================================================================
    // Timing primitives
    // =======================================================================================

    /** Internal time that overlaps the previous bus access: only the excess is spent. */
    void internal(int clocks) {
        int cycs = clocks * CLK;
        final long diff = ceEnd - ceStart;
        if (diff > 0) {
            if (diff >= cycs) {
                ceStart += cycs;
                return;
            }
            ceStart = ceEnd;
            cycs -= (int) diff;
        }
        bus.idle(cycs);
    }

    /** Internal time that does not overlap anything (multiply, divide). */
    void internalFull(int clocks) {
        bus.idle(clocks * CLK);
    }

    // =======================================================================================
    // Data accesses
    // =======================================================================================

    int readByte(int a) {
        ceStart = bus.cycles();
        final int v = bus.readByte(a);
        ceEnd = bus.cycles();
        return v;
    }

    int readWord(int a) {
        ceStart = bus.cycles();
        final int v = bus.readWord(a);
        ceEnd = bus.cycles();
        return v;
    }

    int readLong(int a) {
        ceStart = bus.cycles();
        final int v = bus.readLong(a);
        ceEnd = bus.cycles();
        return v;
    }

    void writeByte(int a, int v) {
        ceStart = bus.cycles();
        bus.writeByte(a, v);
        ceEnd = bus.cycles();
    }

    void writeWord(int a, int v) {
        ceStart = bus.cycles();
        bus.writeWord(a, v);
        ceEnd = bus.cycles();
    }

    void writeLong(int a, int v) {
        ceStart = bus.cycles();
        bus.writeLong(a, v);
        ceEnd = bus.cycles();
    }

    int read(int a, int size) {
        return size == 4 ? readLong(a) : size == 2 ? readWord(a) : readByte(a);
    }

    void write(int a, int v, int size) {
        if (size == 4) writeLong(a, v);
        else if (size == 2) writeWord(a, v);
        else writeByte(a, v);
    }

    // =======================================================================================
    // Instruction stream
    // =======================================================================================

    /** Loads the holding register with the long word at {@code addr}, from the cache or the bus. */
    private void fillIcache(int addr, boolean opcode) {
        addr &= ~3;
        if (holdAddr == addr) return;
        final int index = (addr >>> 2) & 63;
        final int tag = s | (addr & ~0xFF);
        if ((cacr & 1) != 0 && cacheValid[index] && cacheTag[index] == tag) {
            holdAddr = addr;
            holdData = cacheData[index];
            return;
        }
        ceStart = bus.cycles();
        // Two opcode fetches back to back cost two more clocks.
        if (opcode && ceStart == cePrefetchEnd) {
            bus.idle(2 * CLK);
            ceStart = bus.cycles();
        }
        final int data = bus.fetchLong(addr);
        ceEnd = bus.cycles();
        cePrefetchEnd = opcode ? ceEnd : ceStart;
        if ((cacr & 3) == 1) {
            cacheTag[index] = tag;
            cacheValid[index] = true;
            cacheData[index] = data;
        }
        holdAddr = addr;
        holdData = data;
    }

    /**
     * Consumes the next word of the queue, the one at {@code wordPc}, and refills the queue
     * from the holding register (fetching the next long word when the consumed word is the
     * second of its long).
     */
    private int consume(int wordPc, boolean opcode) {
        final int v = pf0;
        pf0 = pf1;
        pf1 = pf2;
        pipeline(wordPc);
        if ((wordPc & 2) != 0) {
            if (pipelineStop >= 0) fillIcache(wordPc + 6, opcode);
            pf2 = holdData >>> 16;
        } else {
            pf2 = holdData & 0xFFFF;
        }
        if (observer != null) observer.consumed(opcode);
        internal(2);
        return v;
    }

    /** The next extension word of the instruction (unsigned). */
    int nextWord() {
        final int v = consume(pc + po, false);
        po += 2;
        return v;
    }

    /** The next two extension words as a long. */
    int nextLong() {
        final int hi = consume(pc + po, false);
        final int lo = consume(pc + po + 2, false);
        po += 4;
        return (hi << 16) | lo;
    }

    /** Prefetches the opcode of the next instruction (the word right after this one). */
    void prefetchNext() {
        irc = consume(pc + po, true);
    }

    /** The instruction is complete: the program counter moves past it. */
    void advance() {
        pc += po;
    }

    /** Prefetches the next opcode and moves past the instruction. */
    void finish() {
        irc = consume(pc + po, true);
        pc += po;
    }

    /**
     * The predecoder: follows the instruction stream ahead of execution and stops the
     * prefetch after a branch it recognises.
     */
    private void pipeline(int wordPc) {
        if ((pfValid & 2) == 0) {
            pipelineStop = -1;
            return;
        }
        if (pipelinePos < 0) return;
        final int w = pf1;
        if (pipelinePos > 0) {
            if (pipelinePos == pipelineR8a) {
                pipelineR8a = 0;
                pipelinePos += fullExtensionExtra(w);
                return;
            }
            if (pipelinePos == pipelineR8b) {
                pipelineR8b = 0;
                pipelinePos += fullExtensionExtra(w);
                return;
            }
        }
        if (pipelinePos > 2) {
            pipelinePos -= 2;
            if (pipelineStop > 0 && pipelinePos == 2) pipelineStop = -1;
            return;
        }
        if (pipelineStop != 0) {
            pipelineStop = -1;
            return;
        }
        pipelineR8a = Decoder020.DISP0[w];
        pipelineR8b = Decoder020.DISP1[w];
        pipelinePos = Decoder020.LENGTH[w];
        if (pipelinePos > 0 && Decoder020.BRANCH[w] > 0) pipelineStop = 1;
    }

    private static int fullExtensionExtra(int w) {
        if ((w & 0x100) == 0) return 0;
        int extra = 0;
        if ((w & 0x30) == 0x20) extra += 2;
        if ((w & 0x30) == 0x30) extra += 4;
        if ((w & 0x03) == 0x02) extra += 2;
        if ((w & 0x03) == 0x03) extra += 4;
        return extra;
    }

    private void resetPipeline() {
        pipelinePos = 0;
        pipelineStop = 0;
        pipelineR8a = pipelineR8b = -1;
    }

    private void addPrefetch(int idx, int w, int pcAligned) {
        pf0 = pf1;
        pf1 = pf2;
        pf2 = w & 0xFFFF;
        pfValid = (pfValid >> 1) | 4;
        if (idx >= 1) pipeline(pcAligned);
    }

    /** Refills the queue at {@link #pc} after a change of flow, then prefetches the opcode. */
    void fillPrefetchNoTrace() {
        final int pc2 = pc;
        final int a = pc & ~3;
        resetPipeline();
        fillIcache(a, true);
        if ((pc2 & 2) != 0) {
            addPrefetch(0, holdData, a);
        } else {
            addPrefetch(0, holdData >>> 16, a);
            addPrefetch(1, holdData, a);
        }
        fillIcache(a + 4, true);
        if ((pc2 & 2) != 0) {
            addPrefetch(1, holdData >>> 16, a);
            addPrefetch(2, holdData, a);
        } else {
            addPrefetch(2, holdData >>> 16, a);
        }
        irc = consume(pc, true);
    }

    /** {@link #fillPrefetchNoTrace()} after a branch: also a T0 trace point. */
    void fillPrefetch() {
        fillPrefetchNoTrace();
        checkT0Trace();
    }

    /** The cache control register changed: invalidations, holding register dropped. */
    void setCaches() {
        holdAddr = -1;
        if ((cacr & 0x08) != 0) {
            java.util.Arrays.fill(cacheValid, false);
            cacr &= ~0x08;
        }
        if ((cacr & 0x04) != 0) {
            cacheValid[(caar >>> 2) & 63] = false;
            cacr &= ~0x04;
        }
    }

    // =======================================================================================
    // Effective addresses
    // =======================================================================================

    /** Increment of (An)+ and -(An) for a byte: two for the stack pointer. */
    private static int step(int reg, int size) {
        return size == 1 && reg == 7 ? 2 : size;
    }

    /**
     * Address of a memory effective address, consuming its extension words. (An)+ and -(An)
     * update the register. Mode 7 covers absolute and PC-relative addressing.
     */
    int ea(int mode, int reg, int size) {
        switch (mode) {
            case 2: return r[8 + reg];
            case 3: {
                final int a = r[8 + reg];
                r[8 + reg] = a + step(reg, size);
                return a;
            }
            case 4: {
                final int a = r[8 + reg] - step(reg, size);
                r[8 + reg] = a;
                return a;
            }
            case 5: return r[8 + reg] + (short) nextWord();
            case 6: {
                pc += po;
                po = 0;
                return indexEa(r[8 + reg]);
            }
            default:
                switch (reg) {
                    case 0: return (short) nextWord();
                    case 1: return nextLong();
                    case 2: {
                        final int base = pc + po;
                        return base + (short) nextWord();
                    }
                    case 3: {
                        pc += po;
                        po = 0;
                        return indexEa(pc);
                    }
                    default: throw new IllegalStateException("not a memory address");
                }
        }
    }

    /** Brief and full extension word formats. */
    int indexEa(int base) {
        final int dp = nextWord();
        int regd = r[(dp >> 12) & 15];
        if ((dp & 0x800) == 0) regd = (short) regd;
        regd <<= (dp >> 9) & 3;
        if ((dp & 0x100) == 0) return base + (byte) dp + regd;
        int outer = 0;
        if ((dp & 0x80) != 0) base = 0;
        if ((dp & 0x40) != 0) regd = 0;
        if ((dp & 0x30) == 0x20) base += (short) nextWord();
        if ((dp & 0x30) == 0x30) base += nextLong();
        if ((dp & 0x3) == 0x2) outer = (short) nextWord();
        if ((dp & 0x3) == 0x3) outer = nextLong();
        if ((dp & 0x4) == 0) base += regd;
        if ((dp & 0x3) != 0) base = readLong(base);
        if ((dp & 0x4) != 0) base += regd;
        return base + outer;
    }

    /** Reads a source operand of any mode, sign-preserving within {@code size}. */
    int readEa(int mode, int reg, int size) {
        switch (mode) {
            case 0: return r[reg];
            case 1: return r[8 + reg];
            case 7:
                if (reg == 4) return size == 4 ? nextLong() : size == 2 ? nextWord() : nextWord() & 0xFF;
                // fall through
            default: return read(ea(mode, reg, size), size);
        }
    }

    // =======================================================================================
    // Registers and flags
    // =======================================================================================

    void setD(int reg, int v, int size) {
        if (size == 4) r[reg] = v;
        else if (size == 2) r[reg] = (r[reg] & 0xFFFF0000) | (v & 0xFFFF);
        else r[reg] = (r[reg] & 0xFFFFFF00) | (v & 0xFF);
    }

    static int sext(int v, int size) {
        return size == 4 ? v : size == 2 ? (short) v : (byte) v;
    }

    static int mask(int size) {
        return size == 4 ? 0xFFFFFFFF : size == 2 ? 0xFFFF : 0xFF;
    }

    static int msb(int size) {
        return size == 4 ? 0x80000000 : size == 2 ? 0x8000 : 0x80;
    }

    /** N and Z from a result, V and C cleared. */
    void setNZ(int v, int size) {
        final int x = sext(v, size);
        fn = x < 0;
        fz = x == 0;
        fv = false;
        fc = false;
    }

    /** Flags of ADD (and ADDQ/ADDI): X N Z V C. */
    void addFlags(int src, int dst, int res, int size) {
        final int m = msb(size);
        final boolean sn = (src & m) != 0, dn = (dst & m) != 0, rn = (res & m) != 0;
        fv = (sn ^ rn) & (dn ^ rn);
        fc = (sn & dn) | (!rn & (sn | dn));
        fx = fc;
        fn = rn;
        fz = (res & mask(size)) == 0;
    }

    /** Flags of SUB (and SUBQ/SUBI/NEG): X N Z V C. */
    void subFlags(int src, int dst, int res, int size) {
        cmpFlags(src, dst, res, size);
        fx = fc;
    }

    /** Flags of CMP: N Z V C, X unchanged. */
    void cmpFlags(int src, int dst, int res, int size) {
        final int m = msb(size);
        final boolean sn = (src & m) != 0, dn = (dst & m) != 0, rn = (res & m) != 0;
        fv = (sn ^ dn) & (rn ^ dn);
        fc = Integer.compareUnsigned(src & mask(size), dst & mask(size)) > 0;
        fn = rn;
        fz = (res & mask(size)) == 0;
    }

    /** Flags of ADDX: Z only cleared. */
    void addxFlags(int src, int dst, int res, int size) {
        final int m = msb(size);
        final boolean sn = (src & m) != 0, dn = (dst & m) != 0, rn = (res & m) != 0;
        fv = (sn ^ rn) & (dn ^ rn);
        fc = sn ^ ((sn ^ dn) & (dn ^ rn));
        fx = fc;
        fz = fz & (res & mask(size)) == 0;
        fn = rn;
    }

    /** Flags of SUBX and NEGX: Z only cleared. */
    void subxFlags(int src, int dst, int res, int size) {
        final int m = msb(size);
        final boolean sn = (src & m) != 0, dn = (dst & m) != 0, rn = (res & m) != 0;
        fv = (sn ^ dn) & (dn ^ rn);
        fc = sn ^ ((sn ^ rn) & (dn ^ rn));
        fx = fc;
        fz = fz & (res & mask(size)) == 0;
        fn = rn;
    }

    boolean cond(int cc) {
        switch (cc) {
            case 0: return true;
            case 1: return false;
            case 2: return !fc && !fz;
            case 3: return fc || fz;
            case 4: return !fc;
            case 5: return fc;
            case 6: return !fz;
            case 7: return fz;
            case 8: return !fv;
            case 9: return fv;
            case 10: return !fn;
            case 11: return fn;
            case 12: return fn == fv;
            case 13: return fn != fv;
            case 14: return !fz && fn == fv;
            default: return fz || fn != fv;
        }
    }

    int getCCR() {
        return (fx ? 0x10 : 0) | (fn ? 8 : 0) | (fz ? 4 : 0) | (fv ? 2 : 0) | (fc ? 1 : 0);
    }

    void setCCR(int v) {
        fx = (v & 0x10) != 0;
        fn = (v & 8) != 0;
        fz = (v & 4) != 0;
        fv = (v & 2) != 0;
        fc = (v & 1) != 0;
    }

    int getSR() {
        return (t1 << 15) | (t0 << 14) | (s << 13) | (m << 12) | (intmask << 8) | getCCR();
    }

    /**
     * Writes the status register. {@code t0trace}: 1 from an instruction that is itself a T0
     * trace point, 0 otherwise, -1 from STOP.
     */
    void setSR(int sr, int t0trace) {
        final int oldm = m, olds = s, oldt0 = t0, oldt1 = t1;
        setCCR(sr);
        final int newT1 = (sr >> 15) & 1, newT0 = (sr >> 14) & 1, newS = (sr >> 13) & 1;
        final int newM = (sr >> 12) & 1, newMask = (sr >> 8) & 7;
        if (t1 == newT1 && t0 == newT0 && s == newS && m == newM && intmask == newMask) return;
        t1 = newT1;
        t0 = newT0;
        s = newS;
        m = newM;
        if (intmask != newMask) {
            final int pin = bus.ipl();
            if (pin <= intmask && pin > newMask) spc |= SPC_INT;
            intmask = newMask;
        }
        if (olds != s) {
            if (olds != 0) {
                if (oldm != 0) msp = r[15];
                else isp = r[15];
                r[15] = usp;
            } else {
                usp = r[15];
                r[15] = m != 0 ? msp : isp;
            }
        } else if (olds != 0 && oldm != m) {
            if (oldm != 0) {
                msp = r[15];
                r[15] = isp;
            } else {
                isp = r[15];
                r[15] = msp;
            }
        }
        if (t1 != 0 || t0 != 0) spc |= SPC_TRACE;
        else spc &= ~SPC_TRACE;
        if (stopped == 0 && ((oldt0 != 0 && t0trace > 0) || oldt1 != 0)) activateTrace();
    }

    /** Scratch of the bit field instructions (the bytes around the field). */
    final long[] bfData = new long[2];

    /** STOP: a pending level above the mask is taken at the next boundary. */
    void checkInterrupt() {
        if (bus.ipl() > intmask) spc |= SPC_DOINT;
        if ((spc & SPC_INT) == 0 && (spc & SPC_DOINT) != 0) spc |= SPC_INT;
    }

    /** The interrupt state was reloaded (SR written): a pending level above the mask is noted. */
    void interruptCheck() {
        if (bus.ipl() > intmask) spc |= SPC_DOINT;
    }

    // =======================================================================================
    // Exceptions
    // =======================================================================================

    private void push16(int v) {
        r[15] -= 2;
        writeWord(r[15], v);
    }

    private void push32(int v) {
        r[15] -= 4;
        writeLong(r[15], v);
    }

    /** Takes exception {@code nr} with the frame of the 68020. */
    void exception(int nr) {
        if (stopped != 0) {
            stopped = 0;
            pc += 4;
        }
        final int currpc = pc;
        final boolean isInterrupt = nr >= 24 && nr < 32;
        int sr = getSR();
        if (s == 0) {
            usp = r[15];
            r[15] = m != 0 ? msp : isp;
            s = 1;
        }
        if (nr == 2 || nr == 3) {
            buildLongBusFaultFrame(nr, currpc, sr);
        } else if (m != 0 && isInterrupt) {
            push16(nr * 4);
            push32(currpc);
            push16(sr);
            sr |= 0x2000;
            msp = r[15];
            m = 0;
            r[15] = isp;
            push16(0x1000 + nr * 4);
            push32(exceptionPc(nr, currpc));
            push16(sr);
        } else {
            if (nr == 5 || nr == 6 || nr == 7 || nr == 9) {
                push32(nr == 9 ? tracePc : instructionPc);
                push16(0x2000 | (nr * 4));
            } else {
                push16(nr * 4);
            }
            push32(currpc);
            push16(sr);
        }
        if (isInterrupt) intmask = nr - 24;
        final int newpc = readLong(vbr + 4 * nr);
        if ((newpc & 1) != 0) {
            if (nr == 2 || nr == 3) {
                halted = true;
                return;
            }
            exceptionCheckTrace(nr);
            addressError(newpc);
            return;
        }
        pc = newpc;
        fillPrefetchNoTrace();
        exceptionCheckTrace(nr);
    }

    private int exceptionPc(int nr, int currpc) {
        return (nr == 2 || nr == 3 || nr == 4 || nr == 8 || nr == 10 || nr == 11) ? instructionPc : currpc;
    }

    private void exceptionCheckTrace(int nr) {
        spc &= ~(SPC_TRACE | SPC_DOTRACE);
        if (t1 != 0 && (nr == 5 || nr == 6 || nr == 7 || (nr >= 32 && nr <= 47))) spc |= SPC_DOTRACE;
        t1 = t0 = 0;
    }

    /** A processor-generated exception (TRAP, CHK, divide by zero...): T0 trace after it. */
    void exceptionCpu(int nr) {
        final boolean trace0 = t0 != 0 && t1 == 0;
        exception(nr);
        if (nr != 14 && trace0) activateTrace();
    }

    /** Fault address of the last address error, for its frame. */
    private int faultAddress;

    /** An odd instruction address: address error, long bus fault frame. */
    void addressError(int address) {
        faultAddress = address;
        exception(3);
    }

    private void busError() {
        exception(2);
    }

    /** Format $B frame (long bus cycle fault, 46 words) of a bus or address error. */
    private void buildLongBusFaultFrame(int nr, int currpc, int sr) {
        // Instruction stream access, word size, read.
        final int ssw = ((sr & 0x2000) != 0 ? 6 : 2) | 0x40 | 0x20;
        for (int i = 0; i < 9; i++) push32(0);
        for (int i = 0; i < 4; i++) push16(0);
        push32(faultAddress);
        push32(0);
        push32(0);
        push32(0);
        push32(0);
        push32(0);
        push32(irc | (pf0 << 16));
        push32(faultAddress);
        push16(pf2);
        push16(pf1);
        push16(ssw);
        push16(0);
        push16(0xB000 | (nr * 4));
        push32(currpc);
        push16(sr);
    }

    /** Illegal instruction, line A, line F: no prefetch, the frame holds the instruction. */
    void illegal(int op) {
        final int line = op >>> 12;
        exception(line == 0xA ? 10 : line == 0xF ? 11 : 4);
    }

    /** Privilege violation. */
    void privilege() {
        exception(8);
    }

    // =======================================================================================
    // Processor
    // =======================================================================================

    @Override
    public int getOpcodeAddress() {
        return instructionPc;
    }

    @Override
    public int prefetchWord() {
        return irc & 0xFFFF;
    }

    @Override
    public boolean isAtInstructionBoundary() {
        return true;
    }

    /** Current program counter (next instruction). */
    public int getPC() {
        return pc;
    }

    public int getD(int n) {
        return r[n];
    }

    public int getA(int n) {
        return r[8 + n];
    }

    public int getStatusRegister() {
        return getSR();
    }

    /** The opcode about to run (at an instruction boundary). */
    public int getNextOpcode() {
        return irc;
    }

    /** Observer of the instruction queue, for structural tests of the timing model. */
    interface QueueObserver {
        void consumed(boolean opcode);
    }
}
