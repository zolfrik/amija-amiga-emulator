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
 * Implementation of the LSL (Logical Shift Left) instruction.
 */
public class LslInstruction extends AbstractM68kInstruction {

    public static final LslInstruction INSTANCE = new LslInstruction();

    private LslInstruction() {}

    private static final int EXT1 = 0;
    private static final int EXT2 = 1;
    private static final int VAL = 2;
    private static final int ADDR = 3;
    private static final int COUNT = 4;

    @Override
    public boolean executeStep(Motorola68000 cpu) {
        int op = cpu.getIr();
        boolean isMemory = ((op >> 6) & 0x3) == 0x3;

        while (true) {
            if (cpu.isRisingEdgeConsumed()) return false;
            int step = getStep(cpu);
            if (isMemory) {
                int eaMode = (op >> 3) & 0x7, eaReg = op & 0x7;
                switch (step) {
                    case 0 -> {
                        if (eaMode == 4 || eaMode == 6 || (eaMode == 7 && eaReg == 3)) {
                            indexedGap2(cpu);
                        }
                        nextStep(cpu);
                        continue;
                    }
                    case 1 -> {
                        if (hasExt1(eaMode, eaReg)) {
                            if (!fetchExtension(cpu, EXT1)) return false;
                        }
                        nextStep(cpu);
                        continue;
                    }
                    case 2 -> {
                        if (hasExt2(eaMode, eaReg, 2)) {
                            if (!fetchExtension(cpu, EXT2)) return false;
                        }
                        nextStep(cpu);
                        continue;
                    }
                    case 3 -> {
                        int addr = calculateEA(cpu, eaMode, eaReg, 2, cpu.getOpcodeAddress() + 2, getTemp(cpu, EXT1), getTemp(cpu, EXT2), true, false);
                        setTemp(cpu, ADDR, addr);
                        cpu.scheduleRead(addr, 2);
                        nextStep(cpu);
                        return false;
                    }
                    case 4 -> {
                        setTemp(cpu, VAL, cpu.getLatchedBusData() & 0xFFFF);
                        refillPipeline(cpu);
                        nextStep(cpu);
                        return false;
                    }
                    case 5 -> {
                        int result = calculateLsl(cpu, getTemp(cpu, VAL), 1, 2);
                        cpu.scheduleWrite(getTemp(cpu, ADDR), result, 2);
                        return finalizeInstructionSafe(cpu);
                    }
                    default -> { return finalizeInstructionSafe(cpu); }
                }
            } else {
                int sizeBits = (op >> 6) & 0x3, size = (sizeBits == 0) ? 1 : (sizeBits == 1) ? 2 : 4, reg = op & 0x7, countOrReg = (op >> 9) & 0x7;
                boolean isRegCount = ((op >> 5) & 0x1) != 0;
                switch (step) {
                    case 0 -> {
                        int count = isRegCount ? (cpu.getD(countOrReg, 4) & 63) : (countOrReg == 0 ? 8 : countOrReg);
                        setTemp(cpu, COUNT, count);
                        refillPipeline(cpu);
                        nextStep(cpu);
                        return false;
                    }
                    case 1 -> {
                        int count = getTemp(cpu, COUNT);
                        int val = cpu.getD(reg, size);
                        int result = calculateLsl(cpu, val, count, size);
                        cpu.setD(reg, result, size);
                        if (isRegCount) {
                            if (count > 0) {
                                cpu.idleCycles(2 * count);
                                nextStep(cpu);
                                return false;
                            } else {
                                nextStep(cpu);
                                continue;
                            }
                        } else {
                            cpu.idleCycles(size == 4 ? 4 : 2);
                            nextStep(cpu);
                            return false;
                        }
                    }
                    case 2 -> {
                        int count = getTemp(cpu, COUNT);
                        if (isRegCount) {
                            cpu.idleCycles(size == 4 ? 4 : 2);
                        } else {
                            if (count > 0) cpu.idleCycles(2 * count);
                        }
                        nextStep(cpu);
                        return false;
                    }
                    case 3 -> { return finalizeInstructionSafe(cpu); }
                    default -> { return finalizeInstructionSafe(cpu); }
                }
            }
        }
    }

    @Override
    public boolean shouldInhibitPrefetch() {
        return true;
    }

    private int calculateLsl(Motorola68000 cpu, int val, int count, int size) {
        long sizeMask = (size == 1) ? 0xFFL : (size == 2) ? 0xFFFFL : 0xFFFFFFFFL;
        long msbMask = (size == 1) ? 0x80L : (size == 2) ? 0x8000L : 0x80000000L;
        long currentVal = val & sizeMask;
        if (count == 0) {
            int currentSR = cpu.getSR();
            int newSR = currentSR & 0xFFF0; // Clear N, Z, V, C; preserve X
            if ((currentVal & msbMask) != 0) newSR |= 0x08;
            if (currentVal == 0) newSR |= 0x04;
            cpu.writeSR(newSR, false);
            return (int) currentVal;
        }
        boolean lastBitOut = false;
        for (int i = 0; i < count; i++) {
            lastBitOut = (currentVal & msbMask) != 0;
            currentVal = (currentVal << 1) & sizeMask;
        }
        int newSR = cpu.getSR() & 0xFFE0;
        if (lastBitOut) newSR |= 0x11;
        if ((currentVal & msbMask) != 0) newSR |= 0x08;
        if (currentVal == 0) newSR |= 0x04;
        cpu.writeSR(newSR, false);
        return (int) currentVal;
    }

    @Override
    public String getDisassembly(Motorola68000 cpu) {
        int op = cpu.getIr();
        boolean isMemory = ((op >> 6) & 0x3) == 0x3;
        if (isMemory) return "LSL.w " + Disassembly.formatEA((op >> 3) & 0x7, op & 0x7, 2, cpu.extensionWordAt(cpu.getOpcodeAddress() + 2), 0);
        int sizeBits = (op >> 6) & 0x3, size = (sizeBits == 0) ? 1 : (sizeBits == 1) ? 2 : 4, reg = op & 0x7, countOrReg = (op >> 9) & 0x7;
        boolean isRegCount = ((op >> 5) & 0x1) != 0;
        String sizeSuffix = Disassembly.getSizeSuffix(size), countStr = isRegCount ? "D" + countOrReg : "#" + (countOrReg == 0 ? 8 : countOrReg);
        return "LSL" + sizeSuffix + " " + countStr + ", D" + reg;
    }
}

