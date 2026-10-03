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
 * Implementation of the CMPA (Compare Address) instruction.
 */
public class CmpaInstruction extends AbstractM68kInstruction {

    public static final CmpaInstruction INSTANCE = new CmpaInstruction();
    private CmpaInstruction() {}

    private static final int EXT1 = 0;
    private static final int EXT2 = 1;
    private static final int SRC_VAL = 2;
    private static final int ADDR = 4;

    @Override
    public boolean shouldInhibitPrefetch() {
        return true;
    }

    @Override
    public boolean executeStep(Motorola68000 cpu) {
        int op = cpu.getIr();
        int dstReg = (op >> 9) & 0x7, opmode = (op >> 6) & 0x7, srcMode = (op >> 3) & 0x7, srcReg = op & 0x7;
        int size = (opmode == 7) ? 4 : 2;

        while (true) {
            if (cpu.isRisingEdgeConsumed()) return false;
            switch (getStep(cpu)) {
                case 0 -> {
                    // EA Initial Gap (n)
                    if (srcMode == 4 || srcMode == 6 || (srcMode == 7 && srcReg == 3)) {
                        cpu.idleCycles(2);
                        nextStep(cpu);
                        return false;
                    }
                    nextStep(cpu);
                }
                case 1 -> {
                    if (hasExt1(srcMode, srcReg)) {
                        if (!fetchExtension(cpu, EXT1)) return false;
                        nextStep(cpu);
                        return false; // Wait for np
                    }
                    nextStep(cpu);
                }
                case 2 -> {
                    if (hasExt2(srcMode, srcReg, size)) {
                        if (!fetchExtension(cpu, EXT2)) return false;
                        nextStep(cpu);
                        return false; // Wait for np
                    }
                    nextStep(cpu);
                }
                case 3 -> {
                    int dst = cpu.getA(dstReg, 4);
                    if (srcMode == 0) {
                        int src = cpu.getD(srcReg, size);
                        if (size == 2) src = (short) src;
                        calculateCmp(cpu, src, dst, 4);
                        setStep(cpu, 10);
                    } else if (srcMode == 1) {
                        int src = cpu.getA(srcReg, 4);
                        if (size == 2) src = (short) src;
                        calculateCmp(cpu, src, dst, 4);
                        setStep(cpu, 10);
                    } else if (srcMode == 7 && srcReg == 4) {
                        int val = (size == 4) ? (getTemp(cpu, EXT1) << 16) | (getTemp(cpu, EXT2) & 0xFFFF) : (short) getTemp(cpu, EXT1);
                        calculateCmp(cpu, val, dst, 4);
                        setStep(cpu, 10);
                    } else {
                        int addr = calculateEA(cpu, srcMode, srcReg, size, cpu.getOpcodeAddress() + 2, getTemp(cpu, EXT1), getTemp(cpu, EXT2), true, false);
                        setTemp(cpu, ADDR, addr);
                        if (size == 4) {
                            cpu.scheduleRead(addr, 2); // Read MSW (nR)
                        } else {
                            cpu.scheduleRead(addr, 2); // Read .w (nr)
                        }
                        nextStep(cpu);
                        return false;
                    }
                }
                case 4 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    if (size == 4) {
                        setTemp(cpu, SRC_VAL, cpu.getLatchedBusData() << 16);
                        cpu.scheduleRead(getTemp(cpu, ADDR) + 2, 2); // Read LSW (nr)
                        nextStep(cpu);
                        return false;
                    } else {
                        int src = (short) cpu.getLatchedBusData();
                        calculateCmp(cpu, src, cpu.getA(dstReg, 4), 4);
                        setStep(cpu, 10);
                    }
                }
                case 5 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    int src = getTemp(cpu, SRC_VAL) | (cpu.getLatchedBusData() & 0xFFFF);
                    calculateCmp(cpu, src, cpu.getA(dstReg, 4), 4);
                    setStep(cpu, 10);
                }
                case 10 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    // CMPA: the `next` sample sits RIGHT BEFORE the final prefetch, so the anchor
                    // is this prefetch and not the operand read ($B0D0/$B1D0).
                    cpu.markIplAnchor(Motorola68000.IplAnchor.NEXT);
                    refillPipeline(cpu); // np
                    nextStep(cpu);
                    return false;
                }
                case 11 -> {
                    cpu.idleCycles(2); // Final 'n'
                    nextStep(cpu);
                    return false;
                }
                case 12 -> {
                    return finalizeInstructionSafe(cpu);
                }
                default -> { return finalizeInstruction(cpu); }
            }
        }
    }

    @Override
    public String getDisassembly(Motorola68000 cpu) {
        int op = cpu.getIr();
        int dstReg = (op >> 9) & 0x7, opmode = (op >> 6) & 0x7, srcMode = (op >> 3) & 0x7, srcReg = op & 0x7;
        int size = (opmode == 7) ? 4 : 2;
        return "CMPA" + Disassembly.getSizeSuffix(size) + " " + Disassembly.formatEA(srcMode, srcReg, size, cpu.extensionWordAt(cpu.getOpcodeAddress() + 2), cpu.extensionWordAt(cpu.getOpcodeAddress() + 4)) + ", A" + dstReg;
    }
}

