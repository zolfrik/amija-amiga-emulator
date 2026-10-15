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

package io.github.zolfrik.amija.cpu.m68k;

import io.github.zolfrik.amija.cpu.event.BusFault;
import io.github.zolfrik.amija.cpu.event.CpuBus;
import io.github.zolfrik.amija.cpu.event.EventCpu;
import io.github.zolfrik.amija.cpu.event.TimeBase;

/**
 * The event-driven Motorola 68000 and 68010: the state, the run loop, the bus primitives, the
 * interrupt sampling and the exception sequences. The instructions are in {@code Ops000*},
 * one handler per opcode from {@link Decoder000}.
 *
 * <p><b>Shape.</b> Every instruction is straight-line code that calls the {@link CpuBus} for
 * each bus cycle and spends its internal clocks explicitly; the bus brings the chipset to the
 * time of each access. A bus cycle is four clocks (two colour clocks) plus the wait states
 * the bus imposes.
 *
 * <p><b>Prefetch.</b> {@link #ir} holds the opcode being executed and {@link #irc} the word
 * after it. {@link #prefetch(int)} hands out {@link #irc} and reloads it from {@code pc +
 * offset}: extension words are consumed that way, and the last prefetch of an instruction
 * loads the opcode after the next one while {@link #ir} takes the next opcode from
 * {@link #irc}.
 *
 * <p><b>Interrupts.</b> The IPL pins are sampled at a point each instruction chooses:
 * {@link #iplNow()} takes the pins as they are, {@link #iplNext()} judges the last change by
 * its age (a change at least four clocks old counts now, a younger one only after the next
 * instruction, unless the change before it is at least two clocks old). The sample taken at
 * the boundary decides; then the deferred one takes its place. Wait states on the chip bus
 * and the synchronous E-clock bus move a sample taken right before the access to the pins
 * the bus latched late in it.
 *
 * <p>All the state is primitive fields and primitive arrays, so the reflective save state
 * captures it; between two calls of {@link #runUntil(long)} the processor is always at an
 * instruction boundary.
 */
public abstract class Motorola68000Core implements EventCpu {

    /** One processor clock at 7 MHz, in bus time units. */
    static final int CLK = TimeBase.CLOCK_7MHZ;

    /** True for the 68010. */
    final boolean m68010;
    /** The decode table of this model. */
    final Op000[] ops;

    Motorola68000Core(boolean m68010) {
        this.m68010 = m68010;
        this.ops = Decoder000.table(m68010);
        this.loopTable = m68010 ? LoopMode010.table() : null;
    }

    // ---------------------------------------------------------------------------------------
    // Programmer's model
    // ---------------------------------------------------------------------------------------

    /** D0-D7 then A0-A7: the register field of an effective address indexes it directly. */
    final int[] r = new int[16];
    /** The inactive stack pointer: the user one in supervisor mode and vice versa. */
    int usp, ssp;
    /** Address of the instruction being executed (it moves past it when it completes). */
    int pc;
    /** Opcode of the instruction being executed, and the prefetched word after it. */
    int ir, irc;
    /** Opcode in the decode register (the 68010 loop mode keeps the looping one). */
    int ird;
    boolean fx, fn, fz, fv, fc;
    int s, t1, intmask;
    /** 68010 only. */
    int vbr, sfc, dfc;
    /** Last word read and written on the data bus (68010 bus fault frame). */
    int readBuffer, writeBuffer;

    // ---------------------------------------------------------------------------------------
    // Interrupts, trace, special states
    // ---------------------------------------------------------------------------------------

    /** The level that decides at this boundary, and the one deferred to the next. */
    int ipl0, ipl1;
    /** Time of the last immediate sample of the IPL pins. */
    long iplEvt = Long.MIN_VALUE;
    /** A sample was asked to be repeated if the pins change in the middle of the next bus cycle. */
    long iplEvtPre = Long.MIN_VALUE;
    boolean iplPreNext, iplPrePending;

    static final int SPC_TRACE = 1, SPC_DOTRACE = 2;
    int spc;
    /** 1 while stopped by STOP. */
    int stopped;
    int tracePc;
    /** Units of system time /RESET is still held for; the reset sequence follows. */
    long resetHold;
    boolean resetPending;
    /** Two faults while building a group 0 frame: the processor halts until reset. */
    boolean halted;
    /** Nonzero while building an exception frame: -1 for a group 0 one. */
    int exceptionInException;

    /** Address and opcode of the instruction being executed. */
    int instructionPc, opcode;
    /** Completed instructions (diagnostics). */
    public long instructionCount;

    // Information on the access that faulted, for the group 0 frame.
    int lastFaultAddress, lastFaultPc, lastFaultOp, lastFaultFc, lastFaultSize, lastFaultDi;
    boolean lastFaultWrite, lastFaultNotInstruction;

    CpuBus bus;

    /** Optional observer of bus cycles, internal clocks and IPL samples (tests only). */
    Probe probe;

    /** The structural events of an instruction, for the tests that compare them with the model. */
    interface Probe {
        int FETCH = 0, READ_WORD = 1, READ_BYTE = 2, WRITE_WORD = 3, WRITE_BYTE = 4, INTERNAL = 5,
                IPL_NOW = 6, IPL_NEXT = 7, IPL_NOW_PRE = 8, IPL_NEXT_PRE = 9, EXCEPTION = 10, STOP = 11,
                RESET = 12;
        void event(int kind, int arg);
    }

    @Override
    public void attach(CpuBus bus) {
        this.bus = bus;
    }

    // =======================================================================================
    // Run loop
    // =======================================================================================

    @Override
    public void runUntil(long deadline) {
        final CpuBus b = bus;
        final Op000[] table = ops;
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
            final int op = ir;
            opcode = op;
            po = 4;
            try {
                table[op].run(this, op);
            } catch (Abort a) {
                // The instruction raised an address error and the exception is taken.
            } catch (BusFault f) {
                busError();
            }
            if (loopMode == 0) ird = op;
            instructionCount++;
            if (spc != 0 || ipl0 > 0) specialties();
            ipl0 = ipl1;
            ipl1 = 0;
        }
    }

    /** One instruction, for tests: no reset hold, no deadline. */
    void step() {
        instructionPc = pc;
        final int op = ir;
        opcode = op;
        po = 4;
        try {
            ops[op].run(this, op);
        } catch (Abort a) {
            // The instruction raised an address error and the exception is taken.
        } catch (BusFault f) {
            busError();
        }
        if (loopMode == 0) ird = op;
        instructionCount++;
    }

    private void holdReset(long deadline) {
        while (resetHold > 0 && bus.cycles() < deadline) {
            final long stepUnits = Math.min(resetHold, TimeBase.CCK);
            bus.idle((int) stepUnits);
            resetHold -= stepUnits;
        }
        if (resetHold <= 0 && resetPending) {
            resetPending = false;
            bus.resetLine(false);
            resetSequence();
        }
    }

    /** Trace and interrupts, at an instruction boundary. */
    private void specialties() {
        if ((spc & SPC_DOTRACE) != 0) exception(9);
        if ((spc & SPC_TRACE) != 0) doTrace();
        final int level = ipl0;
        if (level > intmask || level == 7) interrupt(level);
    }

    private void doTrace() {
        if (stopped != 0) return;
        tracePc = pc;
        if (t1 != 0) activateTrace();
    }

    final void activateTrace() {
        spc &= ~SPC_TRACE;
        spc |= SPC_DOTRACE;
    }

    private void interrupt(int level) {
        for (;;) {
            exception(level + 24);
            level = ipl0;
            if (level <= 0 || intmask >= level) break;
        }
    }

    // =======================================================================================
    // Reset
    // =======================================================================================

    @Override
    public void powerOnReset(int startDelayCck) {
        reset();
        resetHold = (long) Math.max(0, startDelayCck) * TimeBase.CCK;
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
        ipl0 = ipl1 = 0;
        s = 1;
        t1 = 0;
        fx = fn = fz = fv = fc = false;
        intmask = 7;
        vbr = sfc = dfc = 0;
        stopped = 0;
        halted = false;
        loopMode = 0;
        exceptionInException = 0;
        r[15] = bus.readLongUntimed(0);
        pc = bus.readLongUntimed(4);
        ir = fetch(pc);
        ird = ir;
        irc = fetch(pc + 2);
        readBuffer = irc;
    }

    // =======================================================================================
    // Bus primitives
    // =======================================================================================

    /** Internal clocks. */
    final void internal(int clocks) {
        if (probe != null) probe.event(Probe.INTERNAL, clocks);
        bus.idle(clocks * CLK);
        if (iplPrePending) checkIplPre();
    }

    /** Instruction stream word at {@code a}. */
    final int fetch(int a) {
        if (probe != null) probe.event(Probe.FETCH, a);
        final long t0 = bus.now();
        final int v = bus.fetchWord(a) & 0xFFFF;
        afterAccess(t0);
        return v;
    }

    /** The prefetch: returns {@link #irc} and reloads it from {@code pc + offset}. */
    final int prefetch(int offset) {
        final int v = irc;
        irc = fetch(pc + offset);
        readBuffer = irc;
        return v;
    }

    final int readWord(int a) {
        if (probe != null) probe.event(Probe.READ_WORD, a);
        final long t0 = bus.now();
        final int v = bus.readWord(a) & 0xFFFF;
        afterAccess(t0);
        readBuffer = v;
        return v;
    }

    final int readByte(int a) {
        if (probe != null) probe.event(Probe.READ_BYTE, a);
        final long t0 = bus.now();
        final int v = bus.readByte(a) & 0xFF;
        afterAccess(t0);
        readBuffer = v;
        return v;
    }

    final void writeWord(int a, int v) {
        if (probe != null) probe.event(Probe.WRITE_WORD, a);
        writeBuffer = v & 0xFFFF;
        final long t0 = bus.now();
        bus.writeWord(a, v & 0xFFFF);
        afterAccess(t0);
    }

    final void writeByte(int a, int v) {
        if (probe != null) probe.event(Probe.WRITE_BYTE, a);
        writeBuffer = v & 0xFF;
        final long t0 = bus.now();
        bus.writeByte(a, v & 0xFF);
        afterAccess(t0);
    }

    /** The IPL bookkeeping of a bus cycle that started at {@code t0}. */
    private void afterAccess(long t0) {
        if (iplPrePending) checkIplPre();
        if (t0 == iplEvt) {
            final int late = bus.iplLate();
            if (late >= 0) ipl0 = late;
        }
    }

    // =======================================================================================
    // IPL sampling
    // =======================================================================================

    /** The pins count as they are now: an interrupt is possible after this instruction. */
    final void iplNow() {
        if (probe != null) probe.event(Probe.IPL_NOW, 0);
        iplNowAt(bus.now());
    }

    /** The pins are judged by the age of their last change (see the class comment). */
    final void iplNext() {
        if (probe != null) probe.event(Probe.IPL_NEXT, 0);
        iplNextAt(bus.now());
    }

    /** {@link #iplNow()}, repeated if the pins change in the middle of the next bus cycle. */
    final void iplNowPre() {
        if (probe != null) probe.event(Probe.IPL_NOW_PRE, 0);
        final long t = bus.now();
        iplNowAt(t);
        iplEvtPre = t;
        iplPreNext = false;
        iplPrePending = true;
    }

    /** {@link #iplNext()}, repeated if the pins change in the middle of the next bus cycle. */
    final void iplNextPre() {
        if (probe != null) probe.event(Probe.IPL_NEXT_PRE, 0);
        final long t = bus.now();
        iplNextAt(t);
        iplEvtPre = t;
        iplPreNext = true;
        iplPrePending = true;
    }

    private void iplNowAt(long t) {
        iplEvt = t;
        ipl0 = bus.iplAt(t);
        ipl1 = 0;
    }

    private void iplNextAt(long t) {
        final long change = bus.iplChangeAt(t);
        if (t - change >= 4 * CLK) {
            ipl0 = bus.iplAt(t);
            ipl1 = 0;
            return;
        }
        final long changePrev = bus.iplChangeAt(change - 1);
        if (t - changePrev >= 2 * CLK) {
            ipl0 = bus.iplAt(change - 1);
            ipl1 = 0;
        } else {
            ipl1 = bus.iplAt(t);
        }
    }

    /** A change of the pins two clocks into the bus cycle after a pre-sample repeats it. */
    private void checkIplPre() {
        final long mid = iplEvtPre + 2 * CLK;
        if (bus.now() < mid) return;
        iplPrePending = false;
        if (bus.iplChangeAt(mid) == mid && bus.iplAt(mid) > 0) {
            if (iplPreNext) iplNextAt(mid);
            else iplNowAt(mid);
        }
    }

    // =======================================================================================
    // Status register
    // =======================================================================================

    final int getCCR() {
        return (fx ? 0x10 : 0) | (fn ? 8 : 0) | (fz ? 4 : 0) | (fv ? 2 : 0) | (fc ? 1 : 0);
    }

    final void setCCR(int v) {
        fx = (v & 0x10) != 0;
        fn = (v & 8) != 0;
        fz = (v & 4) != 0;
        fv = (v & 2) != 0;
        fc = (v & 1) != 0;
    }

    final int getSR() {
        return (t1 << 15) | (s << 13) | (intmask << 8) | getCCR();
    }

    /** Writes the status register; {@code fromStop}: the write of STOP. */
    final void setSR(int sr, boolean fromStop) {
        final int olds = s, oldt1 = t1;
        setCCR(sr);
        final int newT1 = (sr >> 15) & 1, newS = (sr >> 13) & 1, newMask = (sr >> 8) & 7;
        if (t1 == newT1 && s == newS && intmask == newMask) return;
        t1 = newT1;
        s = newS;
        if (intmask != newMask) {
            // A STOP that unmasks a level already sampled waits one round of the STOP loop.
            if (fromStop && ipl0 <= intmask && ipl0 > newMask && ipl0 < 7) ipl0 = 0;
            intmask = newMask;
        }
        if (olds != s) {
            if (olds != 0) {
                ssp = r[15];
                r[15] = usp;
            } else {
                usp = r[15];
                r[15] = ssp;
            }
        }
        if (t1 != 0) spc |= SPC_TRACE;
        else spc &= ~SPC_TRACE;
        if (stopped == 0 && oldt1 != 0) activateTrace();
    }

    final void setSR(int sr) {
        setSR(sr, false);
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

    // =======================================================================================
    // Exceptions
    // =======================================================================================

    /** The exception number a vector fetch uses for an interrupt: the autovector. */
    private int iack(int nr) {
        readByte(0xFFFFF1 | ((nr - 24) << 1));
        internal(4);
        return bus.interruptAcknowledge(nr - 24);
    }

    /** True if the opcode in {@link #ir} raises a group 1 exception by itself. */
    final boolean generatesGroup1(int op) {
        return Decoder000.isIllegal(op, m68010) || (s == 0 && Decoder000.isPrivileged(op, m68010));
    }

    /** Takes exception {@code nr}: the frame, the vector, the refill of the prefetch. */
    final void exception(int nr) {
        if (probe != null) {
            probe.event(Probe.EXCEPTION, nr);
            final Probe p = probe;
            probe = null;
            try {
                exceptionBody(nr);
            } finally {
                probe = p;
            }
            return;
        }
        exceptionBody(nr);
    }

    private void exceptionBody(int nr) {
        loopMode = 0;
        if (stopped != 0) {
            stopped = 0;
            pc += 4;
        }
        final int currpc = pc;
        final int sv = s;
        final boolean interrupt = nr >= 24 && nr < 32;
        int vectorNr = nr;
        int frameId = 0;
        final int start;
        if (interrupt) start = 6;
        else if (nr == 7) start = 0;
        else if (nr == 3) start = m68010 ? 4 : 0;
        else if (nr == 2) start = m68010 ? 8 : 12;
        else start = 4;
        if (start != 0) internal(start);

        final int sr = getSR();
        final boolean g1 = generatesGroup1(ir);
        if (s == 0) {
            usp = r[15];
            r[15] = ssp;
            s = 1;
        }
        boolean frameDone = false;
        if (nr == 2 || nr == 3) {
            if ((r[15] & 1) != 0 || exceptionInException < 0) {
                halted = true;
                return;
            }
            if (!m68010) {
                int mode = (sv != 0 ? 4 : 0) | lastFaultFc;
                mode |= lastFaultWrite ? 0 : 16;
                mode |= lastFaultNotInstruction ? 8 : 0;
                mode |= lastFaultOp & ~31;
                r[15] -= 14;
                exceptionInException = -1;
                final int sp = r[15];
                writeWord(sp + 12, lastFaultPc);
                writeWord(sp + 8, sr);
                writeWord(sp + 10, lastFaultPc >>> 16);
                writeWord(sp + 6, lastFaultOp);
                writeWord(sp + 4, lastFaultAddress);
                writeWord(sp, mode);
                writeWord(sp + 2, lastFaultAddress >>> 16);
                frameDone = true;
            } else {
                final int in = readBuffer, out = writeBuffer;
                int ssw = (sv != 0 ? 4 : 0) | lastFaultFc;
                ssw |= lastFaultDi > 0 ? 0 : (lastFaultDi < 0 ? 0x3000 : 0x2000);
                ssw |= (!lastFaultWrite && lastFaultDi != 0) ? 0x1000 : 0;
                ssw |= (lastFaultOp & 0x10000) != 0 ? 0x0400 : 0;
                ssw |= lastFaultSize == 0 ? 0x0200 : 0;
                ssw |= lastFaultWrite ? 0 : 0x0100;
                if ((lastFaultOp & 0x20000) != 0) ssw &= 0x00FF;
                r[15] -= (29 - 4) * 2;
                exceptionInException = -1;
                frameId = 8;
                final int sp = r[15];
                for (int i = 0; i < 15; i++) {
                    writeWord(sp + 20 + i * 2, ((i + 1) << 8) | (i + 2));
                }
                writeWord(sp + 18, 0);
                writeWord(sp + 16, irc);
                writeWord(sp + 12, in);
                writeWord(sp + 8, out);
                writeWord(sp + 4, lastFaultAddress);
                writeWord(sp + 2, lastFaultAddress >>> 16);
                writeWord(sp, ssw);
            }
        }
        if (!frameDone) {
            if (m68010) {
                r[15] -= 8;
                if ((r[15] & 1) != 0) {
                    exception3NotInstruction(ir, r[15] + 4);
                    return;
                }
                exceptionInException = 1;
                final int sp = r[15];
                writeWord(sp + 4, currpc);
                if (interrupt) vectorNr = iack(nr);
                writeWord(sp, sr);
                writeWord(sp + 2, currpc >>> 16);
                writeWord(sp + 6, (frameId << 12) | (vectorNr * 4));
            } else {
                r[15] -= 6;
                if ((r[15] & 1) != 0) {
                    exception3NotInstruction(ir, r[15] + 4);
                    return;
                }
                exceptionInException = 1;
                final int sp = r[15];
                writeWord(sp + 4, currpc);
                if (interrupt) vectorNr = iack(nr);
                writeWord(sp, sr);
                writeWord(sp + 2, currpc >>> 16);
            }
        }
        if ((vbr & 1) != 0) {
            halted = true;
            return;
        }
        if (interrupt) intmask = nr - 24;
        int newpc = readWord(vbr + 4 * vectorNr) << 16;
        newpc |= readWord(vbr + 4 * vectorNr + 2);
        exceptionInException = 0;
        if ((newpc & 1) != 0) {
            if (nr == 2 || nr == 3) {
                halted = true;
                return;
            }
            t1 = 0;
            if (!m68010) {
                pc = vbr + 4 * vectorNr;
                if (interrupt) {
                    ir = nr;
                    exception3ReadAccess(ir | 0x20000 | 0x10000, newpc, 1, 2);
                } else {
                    exception3ReadAccess(ir | 0x40000 | 0x20000 | (g1 ? 0x10000 : 0), newpc, 1, 2);
                }
            } else {
                writeBuffer = 4 * vectorNr;
                readBuffer = newpc & 0xFFFF;
                irc = readBuffer;
                exception3ReadAccess(ir, newpc, 1, 2);
            }
            return;
        }
        pc = newpc;
        ir = fetch(pc);
        ird = ir;
        internal(2);
        iplNextAt(bus.now());
        irc = fetch(pc + 2);
        readBuffer = irc;
        exceptionCheckTrace(nr);
    }

    private void exceptionCheckTrace(int nr) {
        spc &= ~(SPC_TRACE | SPC_DOTRACE);
        if (t1 != 0) {
            if (nr == 5 || nr == 6 || nr == 7 || (nr >= 32 && nr <= 47)) spc |= SPC_DOTRACE;
            if (nr == 14 && m68010) spc |= SPC_DOTRACE;
        }
        t1 = 0;
    }

    /** A bus error from the system on the last access. */
    private void busError() {
        exception(2);
    }

    /** Illegal instruction, line A, line F. */
    final void illegal(int op) {
        final int line = op >>> 12;
        exception(line == 0xA ? 10 : line == 0xF ? 11 : 4);
    }

    /** Privilege violation. */
    final void privilege() {
        exception(8);
    }

    // ---------------------------------------------------------------------------------------
    // Address errors. The opcode argument carries flags in its upper bits: 0x10000 not an
    // instruction access, 0x20000 not raised by a group 1 condition, 0x40000 instruction
    // access.
    // ---------------------------------------------------------------------------------------

    private void exception3f(int opcode, int addr, boolean write, boolean instruction,
                             boolean notInstruction, int faultPc, int size, int fc) {
        lastFaultPc = faultPc;
        lastFaultAddress = addr;
        lastFaultOp = opcode;
        lastFaultWrite = write;
        lastFaultFc = fc >= 0 ? fc : (instruction ? 2 : 1);
        lastFaultNotInstruction = notInstruction || (!m68010 && lastFaultFc == 2);
        lastFaultSize = size;
        exception(3);
    }

    final void exception3Read(int opcode, int addr, int size, int fc) {
        boolean ni = false, ia = false;
        if (!m68010) {
            if (generatesGroup1(this.opcode) && (opcode & 0x20000) == 0) {
                ni = true;
                fc = -1;
            }
            if ((opcode & 0x10000) != 0) ni = true;
            if ((opcode & 0x40000) != 0) ia = true;
            opcode = ir;
        }
        lastFaultDi = 1;
        exception3f(opcode, addr, false, ia, ni, pc, size & 15, fc);
    }

    final void exception3Write(int opcode, int addr, int size, int value, int fc) {
        boolean ni = false, ia = false;
        if (!m68010) {
            if (generatesGroup1(this.opcode) && (opcode & 0x20000) == 0) {
                ni = true;
                fc = -1;
            }
            if ((opcode & 0x10000) != 0) ni = true;
            if ((opcode & 0x40000) != 0) ia = true;
            opcode = ir;
        }
        lastFaultDi = 1;
        writeBuffer = value;
        exception3f(opcode, addr, true, ia, ni, pc, size & 15, fc);
    }

    /** Data read at an odd address: the cycle the processor spends before it faults. */
    final void exception3ReadAccess(int opcode, int addr, int size, int fc) {
        internal(4);
        exception3Read(opcode, addr, size, fc);
    }

    /** The 68010 reads twice before it faults on (An), (An)+, -(An) byte and word sources. */
    final void exception3ReadAccess2(int opcode, int addr, int size, int fc) {
        internal(8);
        exception3Read(opcode, addr, size, fc);
    }

    final void exception3WriteAccess(int opcode, int addr, int size, int value, int fc) {
        internal(4);
        exception3Write(opcode, addr, size, value, fc);
    }

    /** Instruction fetch at an odd address after a change of flow. */
    final void exception3ReadPrefetch(int opcode, int addr) {
        internal(4);
        lastFaultDi = 0;
        if (!m68010) pc += 2;
        exception3f(opcode, addr, false, true, !m68010, pc, 1, -1);
    }

    /** Instruction fetch at an odd address, the program counter left where it was. */
    final void exception3ReadPrefetchOnly(int opcode, int addr) {
        if (m68010) {
            irc = readWord(addr & ~1);
        } else {
            internal(4);
        }
        lastFaultDi = 0;
        exception3f(opcode, addr, false, true, !m68010, pc, 1, -1);
    }

    final void exception3NotInstruction(int opcode, int addr) {
        lastFaultDi = 1;
        exception3f(opcode, addr, true, false, true, pc, 1, -1);
    }

    // =======================================================================================
    // Effective addresses and operands
    // =======================================================================================

    /** Ends the instruction after an exception it raised itself (an address error). */
    static final class Abort extends RuntimeException {
        static final Abort INSTANCE = new Abort();

        private Abort() {
            super(null, null, false, false);
        }
    }

    /** No interrupt sample in the operand access. */
    static final int IPL_NONE = 0;
    /** {@link #iplNow()} right before the last word of the operand. */
    static final int IPL_LAST = 1;

    /** Offset from {@link #pc} of the next word the prefetch loads. */
    int po;

    /** The next extension word (the one in {@link #irc}). */
    final int ext() {
        final int v = prefetch(po);
        po += 2;
        return v;
    }

    /** Increment of (An)+ and decrement of -(An): two for a byte on the stack pointer. */
    static int step(int reg, int size) {
        return size == 1 && reg == 7 ? 2 : size;
    }

    static int sizeOf(int sizeField) {
        return sizeField == 0 ? 1 : sizeField == 1 ? 2 : 4;
    }

    /** Brief extension word of the 68000: scale and full format ignored. */
    final int index(int base, int dp) {
        int regd = r[(dp >> 12) & 15];
        if ((dp & 0x800) == 0) regd = (short) regd;
        return base + (byte) dp + regd;
    }

    /**
     * Address of a memory effective address: consumes its extension words and spends the two
     * internal clocks of -(An) and of the indexed modes. The register of (An)+ and -(An) is
     * not updated ({@link #eaUpdate}).
     */
    final int eaAddr(int mode, int reg, int size) {
        switch (mode) {
            case 2: case 3: return r[8 + reg];
            case 4: {
                final int a = r[8 + reg] - step(reg, size);
                internal(2);
                return a;
            }
            case 5: return r[8 + reg] + (short) ext();
            case 6:
                internal(2);
                return index(r[8 + reg], ext());
            default:
                switch (reg) {
                    case 0: return (short) ext();
                    case 1: {
                        final int hi = ext();
                        return (hi << 16) | ext();
                    }
                    case 2: {
                        final int base = pc + po - 2;
                        return base + (short) ext();
                    }
                    case 3: {
                        final int base = pc + po - 2;
                        internal(2);
                        return index(base, ext());
                    }
                    default: throw new IllegalStateException("not a memory address");
                }
        }
    }

    /**
     * Address of an operand the 68010 writes without reading it first (CLR, Scc, MOVE from
     * SR; TAS before its read): {@code clocks} more internal clocks for {@code (An)} and
     * {@code -(An)}, twice that for {@code (An)+}, and the index computed after its extension
     * word, in four clocks.
     */
    final int eaAddrNoRead(int mode, int reg, int size, int clocks) {
        switch (mode) {
            case 2:
                if (clocks > 0) internal(clocks);
                return r[8 + reg];
            case 3:
                if (clocks > 0) internal(2 * clocks);
                return r[8 + reg];
            case 4: {
                final int a = r[8 + reg] - step(reg, size);
                internal(2 + clocks);
                return a;
            }
            case 6: {
                final int a = index(r[8 + reg], ext());
                internal(4);
                return a;
            }
            default:
                return eaAddr(mode, reg, size);
        }
    }

    /** Writes back the register of (An)+ and -(An) once the access is done. */
    final void eaUpdate(int mode, int reg, int size, int addr) {
        if (mode == 3) r[8 + reg] = addr + step(reg, size);
        else if (mode == 4) r[8 + reg] = addr;
    }

    /** True for the modes whose extension word does not move the stacked PC of an address error. */
    private static boolean dispMode(int mode, int reg) {
        return mode == 5 || mode == 6 || (mode == 7 && (reg == 2 || reg == 3));
    }

    /**
     * An odd word or long access: the address error, as a read, with the program counter
     * past the extension words seen so far. Never returns.
     */
    final void aeRead(int mode, int reg, int addr, int size, int opcodeFlags) {
        pc += po - 2 - (dispMode(mode, reg) ? 2 : 0);
        if (mode == 4) r[8 + reg] = addr;
        else if (mode == 3 && !m68010) r[8 + reg] = addr + step(reg, size);
        final int fc = m68010 && mode == 7 && (reg == 2 || reg == 3) ? 2 : 1;
        exception3ReadAccess(ir | opcodeFlags, addr, size == 4 ? 2 : 1, fc);
        throw Abort.INSTANCE;
    }

    /** Reads {@code size} bytes at {@code a}, a long as two words, high word first. */
    final int readAt(int a, int size, int anchor) {
        if (size == 4) {
            final int hi = readWord(a);
            if (anchor == IPL_LAST) iplNow();
            return (hi << 16) | readWord(a + 2);
        }
        if (anchor == IPL_LAST) iplNow();
        return size == 2 ? readWord(a) : readByte(a);
    }

    /** The immediate operand. */
    final int immediate(int size, int anchor) {
        if (size == 4) {
            final int hi = ext();
            if (anchor == IPL_LAST) iplNow();
            return (hi << 16) | ext();
        }
        if (anchor == IPL_LAST) iplNow();
        return size == 2 ? ext() : ext() & 0xFF;
    }

    /** Scratch of DIVS: its interrupt sample comes two clocks before the end of the division. */
    boolean divsEarlySample;

    /** Address of the last memory operand read by {@link #readEa}. */
    int eaAddress;

    /** Reads an operand of any mode (memory ones through {@link #eaAddr}), checking alignment. */
    final int readEa(int mode, int reg, int size, int anchor) {
        switch (mode) {
            case 0: return r[reg];
            case 1: return r[8 + reg];
            case 7:
                if (reg == 4) return immediate(size, anchor);
                // fall through
            default: {
                final int a = eaAddr(mode, reg, size);
                if (size != 1 && (a & 1) != 0) aeRead(mode, reg, a, size, 0);
                final int v = readAt(a, size, anchor);
                eaUpdate(mode, reg, size, a);
                eaAddress = a;
                return v;
            }
        }
    }

    /** Writes {@code size} bytes at {@code a}; a long low word first (read-modify-write order). */
    final void writeBack(int a, int v, int size) {
        if (size == 4) {
            writeWord(a + 2, v);
            writeWord(a, v >>> 16);
        } else if (size == 2) {
            writeWord(a, v);
        } else {
            writeByte(a, v);
        }
    }

    /** The last prefetch of an instruction, after which it moves past itself. */
    final void prefetchLast() {
        if (loopMode != 0) {
            loopPrefetch();
            return;
        }
        ir = irc;
        prefetch(po);
    }

    /** Internal clocks after the last prefetch of a long operation into a data register. */
    final int longRegClocks() {
        return m68010 ? 2 : 4;
    }

    /** The instruction is done: the program counter moves to the next one. */
    final void advance() {
        pc += po - 2;
    }

    /** {@link #iplNext()}, the last prefetch, the end. */
    final void finishNext() {
        if (loopMode != 0) {
            iplNext();
            loopPrefetch();
            pc += po - 2;
            return;
        }
        ir = irc;
        iplNext();
        prefetch(po);
        pc += po - 2;
    }

    /** {@link #iplNextPre()}, the last prefetch, internal clocks, the end. */
    final void finishNextPre(int clocks) {
        iplNextPre();
        if (loopMode != 0) {
            loopPrefetch();
            if (clocks > 0) internal(clocks);
            pc += po - 2;
            return;
        }
        ir = irc;
        prefetch(po);
        if (clocks > 0) internal(clocks);
        pc += po - 2;
    }

    /** The last prefetch, internal clocks, the end (the interrupt sample was taken before). */
    final void finish(int clocks) {
        if (loopMode != 0) {
            loopPrefetch();
            if (clocks > 0) internal(clocks);
            pc += po - 2;
            return;
        }
        ir = irc;
        prefetch(po);
        if (clocks > 0) internal(clocks);
        pc += po - 2;
    }

    final void setD(int reg, int v, int size) {
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
    final void setNZ(int v, int size) {
        final int x = sext(v, size);
        fn = x < 0;
        fz = x == 0;
        fv = false;
        fc = false;
    }

    final void addFlags(int src, int dst, int res, int size) {
        final int m = msb(size);
        final boolean sn = (src & m) != 0, dn = (dst & m) != 0, rn = (res & m) != 0;
        fv = (sn ^ rn) & (dn ^ rn);
        fc = (sn & dn) | (!rn & (sn | dn));
        fx = fc;
        fn = rn;
        fz = (res & mask(size)) == 0;
    }

    final void subFlags(int src, int dst, int res, int size) {
        cmpFlags(src, dst, res, size);
        fx = fc;
    }

    final void cmpFlags(int src, int dst, int res, int size) {
        final int m = msb(size);
        final boolean sn = (src & m) != 0, dn = (dst & m) != 0, rn = (res & m) != 0;
        fv = (sn ^ dn) & (rn ^ dn);
        fc = Integer.compareUnsigned(src & mask(size), dst & mask(size)) > 0;
        fn = rn;
        fz = (res & mask(size)) == 0;
    }

    final void addxFlags(int src, int dst, int res, int size) {
        final int m = msb(size);
        final boolean sn = (src & m) != 0, dn = (dst & m) != 0, rn = (res & m) != 0;
        fv = (sn ^ rn) & (dn ^ rn);
        fc = sn ^ ((sn ^ dn) & (dn ^ rn));
        fx = fc;
        fz = fz & (res & mask(size)) == 0;
        fn = rn;
    }

    final void subxFlags(int src, int dst, int res, int size) {
        final int m = msb(size);
        final boolean sn = (src & m) != 0, dn = (dst & m) != 0, rn = (res & m) != 0;
        fv = (sn ^ dn) & (dn ^ rn);
        fc = sn ^ ((sn ^ rn) & (dn ^ rn));
        fx = fc;
        fz = fz & (res & mask(size)) == 0;
        fn = rn;
    }

    /**
     * Serializes the few instructions whose internal clocks sit around the last prefetch the
     * way the single-instruction test vectors record them (prefetch first). The machine runs
     * them before the prefetch; the total is the same, only the order on the bus differs.
     */
    boolean vectorOrder;

    // =======================================================================================
    // 68010 loop mode
    // =======================================================================================

    /**
     * Zero outside the loop mode. While the 68010 loops the instruction in IRD under a DBcc,
     * the instruction skips its last prefetch and leaves here what the DBcc still owes: the
     * clocks of a further iteration in bits 1-15 (or bit 0 alone once spent), the clocks of
     * the loop exit in bits 16-31.
     */
    int loopMode;

    /** {@link LoopMode010} entries of the decode table; null on the 68000. */
    final int[] loopTable;

    /**
     * The last prefetch, or in the loop mode what replaces it: the instruction register keeps
     * the DBcc and the clocks of the iteration are owed, or spent here when the instruction
     * has more work after the prefetch point.
     */
    private void loopPrefetch() {
        final int e = loopTable[ird];
        final int cont = LoopMode010.continueClocks(e);
        final int exit = LoopMode010.exitClocks(e);
        if ((e & LoopMode010.EARLY) != 0) {
            internal(cont);
            loopMode = (e & LoopMode010.KEEP_EXIT) != 0 ? (exit << 16) | 1 : 1;
        } else {
            loopMode = cont | (exit << 16);
        }
    }

    /**
     * The clocks a looping instruction adds at its end. The DBcc spends them right after the
     * instruction returns: nothing but internal time lies in between.
     */
    final void loopEnd() {
        final int extra = LoopMode010.extraClocks(loopTable[ird]);
        if (extra > 0) internal(extra);
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
        return ir;
    }
}
