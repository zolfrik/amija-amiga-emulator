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

import io.github.zolfrik.amija.common.BusRequest;
import io.github.zolfrik.amija.common.BusResponse;

/**
 * Motorola 68010: the 68000 core with the behaviour that differs on the 68010.
 *
 * <ul>
 *   <li>vector base register: every exception vector is read at {@code VBR + 4n};</li>
 *   <li>four-word exception frames (format 0) and the 29-word bus/address error frame
 *       (format 8), unwound by a format-aware {@code RTE};</li>
 *   <li>{@code MOVE SR,<ea>} is privileged, {@code MOVE CCR,<ea>} exists;</li>
 *   <li>{@code MOVEC}, {@code MOVES}, {@code RTD}, {@code BKPT};</li>
 *   <li>the loop mode of {@code DBcc};</li>
 *   <li>a different cycle count for a number of instructions.</li>
 * </ul>
 */
public final class Motorola68010 extends AbstractMotorola {

    /** Vector base register. */
    int vbr;
    /** Source and destination function code registers (3 bits each). */
    int sfc, dfc;

    /** Loop mode entry of every opcode, see {@link LoopModeTable}. */
    private final int[] loopTable;

    public Motorola68010(BusRequest busReq, BusResponse busResp) {
        super(busReq, busResp, CpuModel.M68010);
        AbstractM68kInstruction[] decode = new AbstractM68kInstruction[65536];
        for (int op = 0; op < 65536; op++) decode[op] = getInstructionAt(op);
        loopTable = LoopModeTable.build(decode);
    }

    // -------------------------------------------------------------------------------------
    // Loop mode
    // -------------------------------------------------------------------------------------

    /** The DBcc running the loop: its address and opcode. */
    private int loopDbccAddr, loopDbccOpcode;
    /** The instruction being looped and its table entry. */
    private int loopOpcode, loopEntry;
    private boolean loopPrefetchSeen;
    /** Displacement word of the DBcc running the loop. */
    int loopDisplacement;

    /** {@code true} if a DBcc may keep {@code opcode} in loop mode. */
    boolean isLoopable(int opcode) {
        return (loopTable[opcode & 0xFFFF] & LoopModeTable.LOOPABLE) != 0;
    }

    /** Clocks the looped instruction adds at its end. */
    int loopExtraClocks() {
        return LoopModeTable.extraClocks(loopEntry);
    }

    /**
     * Runs the instruction executed before the DBcc as the body of the loop, without
     * fetching it: the DBcc and the body stay in the instruction registers.
     */
    void beginLoopBody() {
        loopDbccAddr = opcodeAddress;
        loopDbccOpcode = ir;
        loopOpcode = previousOpcode & 0xFFFF;
        loopEntry = loopTable[loopOpcode];
        loopPrefetchSeen = false;
        loopMode = 1;
        loopBody = true;
        ir = loopOpcode;
        currentOpcode = loopOpcode;
        opcodeAddress = loopDbccAddr - 2;
        extensionsConsumed = 0;
        tempRegsWritten = 0;
        group0EaMode = -1;
        group0EaSize = 2;
        group0EaDeltaOverride = Integer.MIN_VALUE;
        group0IrOverride = -1;
        currentInstruction = getInstructionAt(loopOpcode);
        setInstructionStep(0);
        currentInstruction.prepareInstruction(this);
    }

    @Override
    void loopPrefetchPoint() {
        if (loopPrefetchSeen) return;
        loopPrefetchSeen = true;
        int cont = LoopModeTable.continueClocks(loopEntry);
        int exit = LoopModeTable.exitClocks(loopEntry);
        if ((loopEntry & LoopModeTable.EARLY) != 0) {
            // In place of the prefetch's bus cycle, starting in this clock.
            idleCycles(cont);
            loopMode = (loopEntry & LoopModeTable.KEEP_EXIT) != 0 ? (exit << 16) | 1 : 1;
        } else {
            loopMode = cont | (exit << 16);
        }
    }

    @Override
    void loopBodyFinished() {
        loopPrefetchPoint();
        loopBody = false;
        ir = loopDbccOpcode;
        // The looped instruction stays the "previous" one for the next DBcc.
        currentOpcode = loopOpcode;
        opcodeAddress = loopDbccAddr;
        currentInstruction = DBccInstruction.M68010;
        setInstructionStep(DBccInstruction.LOOP_EPILOGUE);
        // The skipped prefetch takes no time: the DBcc resumes in this same clock.
        currentInstruction.executeStep(this);
    }

    /** After a loop iteration: the DBcc and its displacement are back in the queue. */
    void reloadLoopQueue(int displacement) {
        setPrefetchQueue(loopDbccAddr, loopDbccOpcode, displacement);
    }

    @Override
    protected void extendDecodeTable(AbstractM68kInstruction[] table) {
        // Instructions whose 68010 timing differs: the same class, its 68010 instance.
        java.util.Map<AbstractM68kInstruction, AbstractM68kInstruction> forms = new java.util.IdentityHashMap<>();
        forms.put(AddInstruction.INSTANCE, AddInstruction.M68010);
        forms.put(AddiInstruction.INSTANCE, AddiInstruction.M68010);
        forms.put(AddxInstruction.INSTANCE, AddxInstruction.M68010);
        forms.put(AndInstruction.INSTANCE, AndInstruction.M68010);
        forms.put(AndiToCCRInstruction.INSTANCE, AndiToCCRInstruction.M68010);
        forms.put(AndiToSRInstruction.INSTANCE, AndiToSRInstruction.M68010);
        forms.put(BccInstruction.INSTANCE, BccInstruction.M68010);
        forms.put(BclrInstruction.INSTANCE, BclrInstruction.M68010);
        forms.put(ClrInstruction.INSTANCE, ClrInstruction.M68010);
        forms.put(DBccInstruction.INSTANCE, DBccInstruction.M68010);
        forms.put(DivsInstruction.INSTANCE, DivsInstruction.M68010);
        forms.put(DivuInstruction.INSTANCE, DivuInstruction.M68010);
        forms.put(EorInstruction.INSTANCE, EorInstruction.M68010);
        forms.put(EoriToCCRInstruction.INSTANCE, EoriToCCRInstruction.M68010);
        forms.put(EoriToSRInstruction.INSTANCE, EoriToSRInstruction.M68010);
        forms.put(MoveInstruction.INSTANCE, MoveInstruction.M68010);
        forms.put(MoveFromUSPInstruction.INSTANCE, MoveFromUSPInstruction.M68010);
        forms.put(MoveToUSPInstruction.INSTANCE, MoveToUSPInstruction.M68010);
        forms.put(MulsInstruction.INSTANCE, MulsInstruction.M68010);
        forms.put(MuluInstruction.INSTANCE, MuluInstruction.M68010);
        forms.put(OrInstruction.INSTANCE, OrInstruction.M68010);
        forms.put(OriToCCRInstruction.INSTANCE, OriToCCRInstruction.M68010);
        forms.put(OriToSRInstruction.INSTANCE, OriToSRInstruction.M68010);
        forms.put(SccInstruction.INSTANCE, SccInstruction.M68010);
        forms.put(SubInstruction.INSTANCE, SubInstruction.M68010);
        forms.put(SubiInstruction.INSTANCE, SubiInstruction.M68010);
        forms.put(SubxInstruction.INSTANCE, SubxInstruction.M68010);
        forms.put(TasInstruction.INSTANCE, TasInstruction.M68010);
        forms.put(TrapvInstruction.INSTANCE, TrapvInstruction.M68010);
        for (int op = 0; op < 65536; op++) {
            AbstractM68kInstruction form = table[op] == null ? null : forms.get(table[op]);
            if (form != null) table[op] = form;
        }
        for (int op = 0; op < 65536; op++) {
            int mode = (op >> 3) & 7, reg = op & 7;
            // MOVE SR,<ea>: privileged, no read of the destination.
            if ((op & 0xFFC0) == 0x40C0 && isDataAlterable(mode, reg)) table[op] = MoveFromCCRInstruction.SR_68010;
            // MOVE CCR,<ea>.
            if ((op & 0xFFC0) == 0x42C0 && isDataAlterable(mode, reg)) table[op] = MoveFromCCRInstruction.INSTANCE;
            // BKPT #n.
            if ((op & 0xFFF8) == 0x4848) table[op] = BkptInstruction.INSTANCE;
            // MOVES.z <ea>,Rn / Rn,<ea>.
            if ((op & 0xFF00) == 0x0E00 && ((op >> 6) & 3) < 3 && isMemoryAlterable(mode, reg)) table[op] = MovesInstruction.INSTANCE;
        }
        table[0x4E73] = Rte68010Instruction.INSTANCE;
        table[0x4E74] = RtdInstruction.INSTANCE;
        table[0x4E7A] = MovecInstruction.TO_GENERAL;
        table[0x4E7B] = MovecInstruction.TO_CONTROL;
    }

    @Override
    boolean stacksFormatWord() {
        return true;
    }

    @Override
    int vectorAddress(int vector) {
        return vbr + (vector << 2);
    }

    @Override
    public void reset() {
        super.reset();
        vbr = 0;
        sfc = 0;
        dfc = 0;
        loopPrefetchSeen = false;
    }

    // -------------------------------------------------------------------------------------
    // Bus and address error: format 8 frame, 29 words
    // -------------------------------------------------------------------------------------

    /** Data input/output buffers and fetch buffer captured when the fault frame starts. */
    private int faultIn, faultOut, faultIrc;

    /**
     * Four clocks shorter than on the 68000, except for a byte or word read through
     * {@code (An)}, {@code (An)+} or {@code -(An)}, whose aborted read still takes its four.
     */
    @Override
    int group0EntryIdle() {
        if (!addressErrorFault) return 4;
        boolean anRead = !faultIsWrite && !faultIsInstruction
                && (group0EaMode == 2 || group0EaMode == 3 || group0EaMode == 4) && group0EaSize < 4;
        return anRead ? 4 : 0;
    }

    /** Special status word of the format 8 frame. */
    private int specialStatusWord() {
        int ssw = faultFC & 7;
        if (faultIsInstruction) ssw |= 0x2000;          // IF: instruction fetch
        else if (!faultIsWrite) ssw |= 0x1000;          // DF: data read
        if (!faultIsWrite) ssw |= 0x0100;               // RW
        return ssw;
    }

    /**
     * The 68010 stacks 29 words: 25 of processor state first, from the top of the area
     * down to the special status word, then the 4-word frame (PC low, SR, PC high, format
     * word {@code $8000 | vector * 4}). Words at +14, +18 and +22 of the state area are left
     * as they were; the 15 internal words carry the pattern {@code ((i+1) << 8) | (i+2)}.
     */
    @Override
    void processExceptionGroup0() {
        if (busCycleState != BusCycleState.IDLE) return;
        int step = microTaskStep;
        if (step == 0) {
            idleCycles(group0EntryIdle());
            microTaskStep = 1;
            return;
        }
        if (step == 1) {
            faultIn = readBuffer;
            faultOut = writeBuffer;
            faultIrc = fetchBuffer;
            setA7(getA7() - 58);
            if ((getA7() & 1) != 0) { haltCPU(); return; }
        }
        int base = getA7();
        // Steps 1..15: internal words at +28..+56 (state area offset +20..+48), ascending.
        if (step >= 1 && step <= 15) {
            int i = step - 1;
            scheduleWriteWord(base + 28 + i * 2, ((i + 1) << 8) | (i + 2));
            microTaskStep++;
            return;
        }
        switch (step) {
            case 16 -> scheduleWriteWord(base + 26, 0);                                  // version
            case 17 -> scheduleWriteWord(base + 24, faultIrc);                           // instruction input buffer
            case 18 -> scheduleWriteWord(base + 20, faultIn);                            // data input buffer
            case 19 -> scheduleWriteWord(base + 16, faultOut);                           // data output buffer
            case 20 -> scheduleWriteWord(base + 12, faultAddress & 0xFFFF);              // fault address low
            case 21 -> scheduleWriteWord(base + 10, (faultAddress >>> 16) & 0xFFFF);     // fault address high
            case 22 -> scheduleWriteWord(base + 8, specialStatusWord());                 // SSW
            case 23 -> scheduleWriteWord(base + 4, group0PushedPC & 0xFFFF);             // PC low
            case 24 -> scheduleWriteWord(base, savedSR);                                 // SR
            case 25 -> scheduleWriteWord(base + 2, (group0PushedPC >>> 16) & 0xFFFF);    // PC high
            case 26 -> {
                exceptionVector = addressErrorFault ? 3 : 2;
                scheduleWriteWord(base + 6, 0x8000 | (exceptionVector << 2));           // format 8
            }
            case 27 -> {
                if (group0PreFaultSR != -1) { sr = group0PreFaultSR & 0xA71F; syncFlagsFromSR(); group0PreFaultSR = -1; }
                if (group0PreFaultAnReg != -1) {
                    if (group0PreFaultAnReg == 7) {
                        // A7 is the frame pointer now: the restored value would discard the frame.
                    } else {
                        setRawA(group0PreFaultAnReg, group0PreFaultAnValue);
                    }
                    group0PreFaultAnReg = -1;
                }
                sr &= ~0x8000;
                scheduleReadWord(vectorAddress(exceptionVector), OpPurpose.EXCEPTION_VECTOR);
            }
            case 28 -> {
                tempExceptionVectorHigh = (latchedBusData & 0xFFFF) << 16;
                scheduleReadWord(vectorAddress(exceptionVector) + 2, OpPurpose.EXCEPTION_VECTOR);
            }
            case 29 -> {
                pc = tempExceptionVectorHigh | (latchedBusData & 0xFFFF);
                flushPrefetch();
                startPrefetchCycle();
            }
            case 30 -> {
                if (!irdValid) return;
                idleCycles(2);
            }
            case 31 -> {
                iplFetchNextAtExceptionEntry();
                startPrefetchCycle();
                cpuState = CpuState.NORMAL;
                currentMicroTask = InternalTask.NONE;
                setInstructionFinished(true);
                exceptionStartsPrefetch = false;
                return;
            }
            default -> { return; }
        }
        microTaskStep++;
    }

    public int getVBR() { return vbr; }
    public int getSFC() { return sfc; }
    public int getDFC() { return dfc; }
}
