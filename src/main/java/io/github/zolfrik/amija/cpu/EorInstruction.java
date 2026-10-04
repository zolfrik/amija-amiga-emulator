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
 * Implementation of the EOR instruction.
 */
public class EorInstruction extends AbstractM68kInstruction {

    public static final EorInstruction INSTANCE = new EorInstruction(false);
    /** The 68010 form: 2 clocks less on a long data register destination. */
    public static final EorInstruction M68010 = new EorInstruction(true);

    private final boolean m68010;

    private EorInstruction(boolean m68010) {
        this.m68010 = m68010;
    }

    private static final int EXT1 = 0;
    private static final int EXT2 = 1;
    private static final int SRC_VAL = 2;
    private static final int DST_VAL = 3;
    private static final int ADDR = 4;

    @Override
    public boolean executeStep(AbstractMotorola cpu) {
        int op = cpu.getIr();
        int reg = (op >> 9) & 0x7;
        int opmode = (op >> 6) & 0x7;
        int eaMode = (op >> 3) & 0x7;
        int eaReg = op & 0x7;

        int size = (opmode == 4) ? 1 : (opmode == 5) ? 2 : 4;

        while (true) {
            if (cpu.isRisingEdgeConsumed()) return false;
            switch (getStep(cpu)) {
                case 0 -> {
                    // Initial gap for -(An) or indexed
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
                        return false; // Yield for np
                    }
                    nextStep(cpu);
                }
                case 2 -> {
                    if (hasExt2(eaMode, eaReg, size)) {
                        if (!fetchExtension(cpu, EXT2)) return false;
                        nextStep(cpu);
                        return false; // Yield for np
                    }
                    nextStep(cpu);
                }
                case 3 -> {
                    if (eaMode == 0) { // Dn: np (or np nn for .L)
                        int result = calculateEor(cpu, cpu.getD(reg, size), cpu.getD(eaReg, size), size);
                        cpu.setD(eaReg, result, size);
                        refillPipeline(cpu);
                        if (size == 4) {
                            setStep(cpu, 10);
                            return false;
                        }
                        return finalizeInstructionSafe(cpu);
                    } else {
                        // Memory EA
                        int addr = calculateEA(cpu, eaMode, eaReg, size, cpu.getOpcodeAddress() + 2, getTemp(cpu, EXT1), getTemp(cpu, EXT2), true, false);
                        setTemp(cpu, ADDR, addr);
                        if (size == 4) {
                            cpu.scheduleRead(addr, 2); // nR
                        } else {
                            cpu.scheduleRead(addr, size); // nr
                        }
                        nextStep(cpu);
                        return false;
                    }
                }
                case 4 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    if (size == 4) {
                        setTemp(cpu, DST_VAL, (cpu.getLatchedBusData() & 0xFFFF) << 16);
                        cpu.scheduleRead(getTemp(cpu, ADDR) + 2, 2); // nr
                        nextStep(cpu);
                        return false;
                    } else {
                        setTemp(cpu, DST_VAL, (size == 1) ? cpu.getLatchedByte() : (cpu.getLatchedBusData() & 0xFFFF));
                        refillPipeline(cpu); // np
                        nextStep(cpu);
                        return false;
                    }
                }
                case 5 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    if (size == 4) {
                        int val = getTemp(cpu, DST_VAL) | (cpu.getLatchedBusData() & 0xFFFF);
                        setTemp(cpu, DST_VAL, val);
                        refillPipeline(cpu); // np
                        nextStep(cpu);
                        return false;
                    } else {
                        int result = calculateEor(cpu, cpu.getD(reg, size), getTemp(cpu, DST_VAL), size);
                        cpu.scheduleWrite(getTemp(cpu, ADDR), result, size); // nw
                        return finalizeInstructionSafe(cpu);
                    }
                }
                case 6 -> { // .L Memory Logic and Write Word Low
                    if (!cpu.isBusCycleIdle()) return false;
                    int result = calculateEor(cpu, cpu.getD(reg, size), getTemp(cpu, DST_VAL), size);
                    setTemp(cpu, SRC_VAL, result); // Reuse SRC_VAL to store result for next step
                    cpu.scheduleWriteWord(getTemp(cpu, ADDR) + 2, result & 0xFFFF); // nw
                    nextStep(cpu);
                    return false;
                }
                case 7 -> { // .L Memory Write Word High
                    cpu.scheduleWriteWord(getTemp(cpu, ADDR), (getTemp(cpu, SRC_VAL) >>> 16) & 0xFFFF); // nW
                    return finalizeInstructionSafe(cpu);
                }
                case 10 -> { // .L register gap: nn (n on the 68010)
                    if (!cpu.isBusCycleIdle()) return false;
                    cpu.idleCycles(m68010 ? 2 : 4);
                    nextStep(cpu);
                    return false;
                }
                case 11 -> { return finalizeInstructionSafe(cpu); }
                default -> { return finalizeInstructionSafe(cpu); }
            }
        }
    }

    @Override
    public boolean shouldInhibitPrefetch() {
        return true;
    }

    @Override
    public String getDisassembly(AbstractMotorola cpu) {
        int op = cpu.getIr();
        int reg = (op >> 9) & 0x7;
        int opmode = (op >> 6) & 0x7;
        int eaMode = (op >> 3) & 0x7;
        int eaReg = op & 0x7;
        int size = (opmode == 4) ? 1 : (opmode == 5) ? 2 : 4;
        int ext1 = cpu.extensionWordAt(cpu.getOpcodeAddress() + 2);
        int ext2 = cpu.extensionWordAt(cpu.getOpcodeAddress() + 4);
        return "EOR" + Disassembly.getSizeSuffix(size) + " D" + reg + ", " + Disassembly.formatEA(eaMode, eaReg, size, ext1, ext2);
    }
}

