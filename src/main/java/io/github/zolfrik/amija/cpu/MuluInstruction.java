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

package io.github.zolfrik.amija.cpu;

/**
 * Implementation of the MULU (Unsigned Multiply) instruction.
 */
public class MuluInstruction extends AbstractM68kInstruction {

    public static final MuluInstruction INSTANCE = new MuluInstruction();
    private MuluInstruction() {}

    private static final int EXT1 = 0;
    private static final int EXT2 = 1;
    private static final int SRC_VAL = 2;

    @Override
    public void prepareInstruction(Motorola68000 cpu) {
        cpu.consumeIRC();
        // Inhibit automatic prefetch
    }

    @Override
    public boolean shouldInhibitPrefetch() {
        return true;
    }

    @Override
    public boolean executeStep(Motorola68000 cpu) {
        int op = cpu.getIr();
        int reg = (op >> 9) & 7, eaMode = (op >> 3) & 7, eaReg = op & 7;

        while (true) {
            if (cpu.isRisingEdgeConsumed()) return false;

            switch (getStep(cpu)) {
                case 0 -> {
                    // Initial gap 'n' for -(An) and indexed modes
                    if (eaMode == 4 || eaMode == 6 || (eaMode == 7 && eaReg == 3)) {
                        indexedGap2(cpu);
                        nextStep(cpu);
                        return false;
                    }
                    nextStep(cpu);
                }
                case 1 -> {
                    if (hasExt1(eaMode, eaReg)) {
                        if (!fetchExtension(cpu, EXT1)) return false;
                        nextStep(cpu);
                        return false; // Yield to ensure prefetch is captured
                    }
                    nextStep(cpu);
                }
                case 2 -> {
                    if (hasExt2(eaMode, eaReg, 2)) {
                        if (!fetchExtension(cpu, EXT2)) return false;
                        nextStep(cpu);
                        return false; // Yield
                    }
                    nextStep(cpu);
                }
                case 3 -> {
                    if (eaMode == 0) {
                        setTemp(cpu, SRC_VAL, cpu.getD(eaReg, 2) & 0xFFFF);
                        refillPipeline(cpu); // np
                        setStep(cpu, 5);
                        return false;
                    } else if (eaMode == 7 && eaReg == 4) {
                        if (!cpu.isBusCycleIdle()) return false;
                        setTemp(cpu, SRC_VAL, getTemp(cpu, EXT1) & 0xFFFF);
                        refillPipeline(cpu); // np
                        setStep(cpu, 5);
                        return false;
                    } else {
                        if (!cpu.isBusCycleIdle()) return false;
                        // Use includeGap=false because we handled it in Step 0
                        int addr = calculateEA(cpu, eaMode, eaReg, 2, cpu.getOpcodeAddress() + 2, getTemp(cpu, EXT1), getTemp(cpu, EXT2), true, false);
                        if ((addr & 1) != 0) {
                            cpu.triggerAddressError(addr, false, cpu.currentFCForData(), false);
                            return false;
                        }
                        cpu.scheduleRead(addr, 2);
                        nextStep(cpu);
                        return false;
                    }
                }
                case 4 -> {
                    setTemp(cpu, SRC_VAL, cpu.getLatchedBusData() & 0xFFFF);
                    refillPipeline(cpu); // np
                    nextStep(cpu);
                    return false;
                }
                case 5 -> {
                    int src = getTemp(cpu, SRC_VAL) & 0xFFFF;
                    int dst = cpu.getD(reg, 2) & 0xFFFF;
                    long res = (long) src * (long) dst;

                    // Calculation of m: number of '1' bits in source
                    int m = Integer.bitCount(src);

                    // Yacht: n* is 17 + m microcycles = 34 + 2m clocks
                    cpu.idleCycles(34 + (2 * m));

                    // Update flags: N, Z on 32-bit result, V=0, C=0. X unaffected.
                    int sr = cpu.getSR() & 0xFFF0;
                    if ((res & 0x80000000L) != 0) sr |= 0x08;
                    if ((res & 0xFFFFFFFFL) == 0) sr |= 0x04;
                    cpu.writeSR(sr, false);

                    cpu.setD(reg, (int) res, 4);
                    // NOTE (Phase 4): terminal activity is internal idle cycles; finalizing here
                    // breaks the JSON 'n' transaction count, so the +1 back-to-back seam is left
                    // as a documented residual. See docs/cpu-overlapped-prefetch-rework-plan.md.
                    nextStep(cpu);
                    return false;
                }
                case 6 -> {
                    return finalizeInstructionSafe(cpu);
                }
                default -> { return finalizeInstructionSafe(cpu); }
            }
        }
    }

    @Override
    public String getDisassembly(Motorola68000 cpu) {
        int op = cpu.getIr();
        return "MULU " + Disassembly.formatEA((op >> 3) & 7, op & 7, 2, cpu.extensionWordAt(cpu.getOpcodeAddress() + 2), cpu.extensionWordAt(cpu.getOpcodeAddress() + 4)) + ", D" + ((op >> 9) & 7);
    }
}


