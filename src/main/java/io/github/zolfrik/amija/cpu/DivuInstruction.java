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

public class DivuInstruction extends AbstractM68kInstruction {

    public static final DivuInstruction INSTANCE = new DivuInstruction(false);
    /** The 68010 form. */
    public static final DivuInstruction M68010 = new DivuInstruction(true);

    private final boolean m68010;
    private DivuInstruction(boolean m68010) {
        this.m68010 = m68010;
    }

    /** Clocks of one quotient-bit step, indexed by previous quotient bit, MSB and new quotient bit. */
    private static final int[] STEP_CLOCKS = {8, 8, 4, 6, 6, 6, 4, 4};

    private static final int EXT1 = 0;
    private static final int EXT2 = 1;
    private static final int SRC_VAL = 2;
    private static final int DIVISOR = 3;
    private static final int ACC = 4;
    private static final int PQ = 5;
    private static final int LOOP_COUNT = 6;
    private static final int LAST_MSB = 7;
    private static final int DIVIDEND_ORIG = 8;

    @Override
    public boolean shouldInhibitPrefetch() {
        return true;
    }

    @Override
    public boolean executeStep(AbstractMotorola cpu) {
        if (cpu.isInstructionFinished()) return true;

        int op = cpu.getIr();
        int reg = (op >> 9) & 7, eaMode = (op >> 3) & 7, eaReg = op & 7;

        while (true) {
            switch (getStep(cpu)) {
                case 0 -> {
                    if (eaMode == 4 || eaMode == 6 || (eaMode == 7 && eaReg == 3)) {
                        indexedGap2(cpu);
                        nextStep(cpu);
                        return false;
                    }
                    nextStep(cpu);
                    continue;
                }
                case 1 -> {
                    if (hasExt1(eaMode, eaReg)) {
                        if (!fetchExtension(cpu, EXT1)) return false;
                        nextStep(cpu);
                        continue;
                    }
                    nextStep(cpu);
                    continue;
                }
                case 2 -> {
                    if (hasExt2(eaMode, eaReg, 2)) {
                        if (!fetchExtension(cpu, EXT2)) return false;
                        nextStep(cpu);
                        continue;
                    }
                    nextStep(cpu);
                    continue;
                }
                case 3 -> {
                    if (eaMode == 0) {
                        setTemp(cpu, SRC_VAL, cpu.getD(eaReg, 2));
                        setStep(cpu, 5);
                        continue;
                    } else if (eaMode == 7 && eaReg == 4) {
                        setTemp(cpu, SRC_VAL, getTemp(cpu, EXT1));
                        setStep(cpu, 5);
                        continue;
                    } else {
                        int addr = calculateEA(cpu, eaMode, eaReg, 2, cpu.getOpcodeAddress() + 2, getTemp(cpu, EXT1), getTemp(cpu, EXT2), true, false);
                        cpu.scheduleRead(addr, 2);
                        nextStep(cpu);
                        return false;
                    }
                }
                case 4 -> {
                    setTemp(cpu, SRC_VAL, cpu.getLatchedBusData());
                    nextStep(cpu);
                    continue;
                }
                case 5 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    int divisor = getTemp(cpu, SRC_VAL) & 0xFFFF;
                    int regVal = cpu.getD(reg, 4);
                    setTemp(cpu, DIVIDEND_ORIG, regVal);
                    if (divisor == 0) {
                        cpu.writeCCR(cpu.x ? 0x10 : 0);
                        cpu.addInternalCycles(4);
                        // Group 2: the PC stacked is that of the NEXT instruction, not the one
                        // that failed: an RTE must continue, not divide again.
                        int extensions = (hasExt1(eaMode, eaReg) ? 1 : 0)
                                + (hasExt2(eaMode, eaReg, 2) ? 1 : 0);
                        int nxt = cpu.getOpcodeAddress() + 2 + extensions * 2;
                        cpu.triggerException(5, nxt);
                        return false;
                    }
                    int msw = (int)(regVal >>> 16) & 0xFFFF;
                    if (m68010) {
                        return divide68010(cpu, reg, regVal, divisor);
                    }
                    if (msw >= divisor) {
                        cpu.v = true; cpu.c = false;
                        // np-LAST order (the hardware's, confirmed by the Tom Harte vectors):
                        // internal idles, then the terminating prefetch in step 8. The np-first
                        // order would shift the marker of the contended bands.
                        cpu.idleCycles(6);
                        setStep(cpu, 8);
                        return false;
                    }
                    int lsw = regVal & 0xFFFF;
                    int msb = (msw >> 15) & 1;
                    int next_msw = ((msw << 1) | ((lsw >>> 15) & 1)) & 0x1FFFF;
                    int next_lsw = (lsw << 1) & 0xFFFF;
                    int q = 0;
                    if (next_msw >= divisor) { next_msw = (next_msw - divisor) & 0xFFFF; q = 1; }
                    setTemp(cpu, DIVISOR, divisor);
                    setTemp(cpu, ACC, (next_msw << 16) | (next_lsw | q));
                    setTemp(cpu, PQ, q);
                    setTemp(cpu, LAST_MSB, msb);
                    setTemp(cpu, LOOP_COUNT, 15);
                    // np-LAST order (see the comment in the overflow branch above).
                    cpu.idleCycles(6);
                    nextStep(cpu);
                    return false;
                }
                case 6 -> {
                    int loopCounter = getTemp(cpu, LOOP_COUNT);
                    int pq = getTemp(cpu, PQ);
                    int divisor = getTemp(cpu, DIVISOR);
                    int acc = getTemp(cpu, ACC);
                    int msw = (acc >>> 16) & 0xFFFF;
                    int lsw = acc & 0xFFFF;
                    int msb = (msw >> 15) & 1;
                    int bit = (lsw >> 15) & 1;
                    int next_lsw = (lsw << 1) & 0xFFFF;
                    int next_msw = ((msw << 1) | bit) & 0x1FFFF;
                    int q = 0;
                    if (next_msw >= divisor) { next_msw = (next_msw - divisor) & 0xFFFF; q = 1; }
                    int timing = STEP_CLOCKS[pq * 4 + msb * 2 + q];
                    loopCounter--;
                    setTemp(cpu, ACC, (next_msw << 16) | (next_lsw | q));
                    setTemp(cpu, PQ, q);
                    setTemp(cpu, LOOP_COUNT, loopCounter);
                    setTemp(cpu, LAST_MSB, msb);
                    cpu.idleCycles(timing); 
                    if (loopCounter <= 0) nextStep(cpu);
                    return false;
                }
                case 7 -> {
                    int acc_final = getTemp(cpu, ACC);
                    int q = acc_final & 0xFFFF;
                    int r = (acc_final >>> 16) & 0xFFFF;
                    cpu.setD(reg, (r << 16) | q, 4);
                    int ccr = cpu.getSR() & 0x10; // Preserve X
                    if ((q & 0x8000) != 0) ccr |= 0x08;
                    if (q == 0) ccr |= 0x04;
                    cpu.writeCCR(ccr);
                    int last_q = getTemp(cpu, PQ);
                    int last_msb = getTemp(cpu, LAST_MSB);
                    int div_orig = getTemp(cpu, DIVIDEND_ORIG);
                    int dsr = getTemp(cpu, DIVISOR);
                    int div_s = (div_orig >>> 31) & 1;
                    int dsr_s = (dsr >>> 15) & 1;
                    int closure;
                    if (div_s == 0) {
                        closure = (dsr_s == 1 && last_q == 1 && last_msb == 1) ? 8 : 6;
                    } else {
                        closure = (last_q == 1 && last_msb == 1) ? 6 : 4;
                    }
                    cpu.idleCycles(closure);
                    setStep(cpu, 8);
                    return false;
                }
                case 8 -> {
                    if (cpu.isBusCycleIdle()) {
                        // Between the operand read and the final prefetch run the division's
                        // cycles, and the `next` sample sits right before the prefetch: the
                        // anchor is the prefetch, not the read.
                        cpu.markIplAnchor(AbstractMotorola.IplAnchor.NEXT);
                        refillPipeline(cpu);
                        // Overlapped prefetch (Phase 4): the terminal np is the last bus cycle;
                        // finalize in this same step (seam 0). See
                        // docs/cpu-overlapped-prefetch-rework-plan.md, Phase 4.
                        return finalizeInstructionSafe(cpu);
                    }
                    return false;
                }
                case 9 -> {
                    finalizeInstructionSafe(cpu);
                    return true;
                }
                default -> { cpu.setInstructionFinished(true); return true; }
            }
        }
    }

    /**
     * 68010: 4 clocks on overflow, otherwise 74 plus 2 for every one of the 15 steps of the
     * restoring division whose partial remainder did not carry out; then the prefetch.
     */
    private boolean divide68010(AbstractMotorola cpu, int reg, int dividend, int divisor) {
        long quotient = (dividend & 0xFFFFFFFFL) / divisor;
        int cycles;
        if (quotient > 0xFFFF) {
            cpu.v = true; cpu.c = false; cpu.z = false;
            cpu.n = !(dividend < 0 && (short) divisor < 0);
            cycles = 4;
        } else {
            long remainder = (dividend & 0xFFFFFFFFL) % divisor;
            cpu.setD(reg, (int) ((remainder << 16) | quotient), 4);
            int ccr = cpu.getSR() & 0x10;
            if ((quotient & 0x8000) != 0) ccr |= 0x08;
            if (quotient == 0) ccr |= 0x04;
            cpu.writeCCR(ccr);
            cycles = 74;
            long d = dividend & 0xFFFFFFFFL;
            long h = ((long) divisor) << 16;
            for (int i = 0; i < 15; i++) {
                boolean carry = (d & 0x80000000L) != 0;
                d = (d << 1) & 0xFFFFFFFFL;
                if (carry) {
                    d = (d - h) & 0xFFFFFFFFL;
                } else {
                    cycles += 2;
                    if (d >= h) d = (d - h) & 0xFFFFFFFFL;
                }
            }
        }
        cpu.idleCycles(cycles);
        setStep(cpu, 8);
        return false;
    }

    @Override
    public String getDisassembly(AbstractMotorola cpu) {
        int op = cpu.getIr();
        return "DIVU " + Disassembly.formatEA((op >> 3) & 7, op & 7, 2, cpu.extensionWordAt(cpu.getOpcodeAddress() + 2), cpu.extensionWordAt(cpu.getOpcodeAddress() + 4)) + ", D" + ((op >> 9) & 7);
    }
}

