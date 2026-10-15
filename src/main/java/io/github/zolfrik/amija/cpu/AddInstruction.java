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

/**
 * Implementation of the ADD instruction.
 * Cycle-exact implementation matching yacht.txt
 */
public class AddInstruction extends AbstractM68kInstruction {

    public static final AddInstruction INSTANCE = new AddInstruction(false);
    /** The 68010 form: 2 clocks less after a long register or immediate source. */
    public static final AddInstruction M68010 = new AddInstruction(true);

    private final boolean m68010;

    private AddInstruction(boolean m68010) {
        this.m68010 = m68010;
    }

    private static final int EXT1 = 0;
    private static final int EXT2 = 1;
    private static final int DST_VAL = 3;
    private static final int ADDR = 4;

    @Override
    public boolean executeStep(AbstractMotorola cpu) {
        int op = cpu.getIr();
        int reg = (op >> 9) & 0x7;
        int opmode = (op >> 6) & 0x7;
        int eaMode = (op >> 3) & 0x7;
        int eaReg = op & 0x7;

        int sizeBit = opmode & 0x3;
        int size = (sizeBit == 0) ? 1 : (sizeBit == 1) ? 2 : 4;
        boolean toDn = (opmode < 4);

        while (true) {
            if (cpu.isRisingEdgeConsumed()) return false;
            switch (getStep(cpu)) {
                case 0 -> {
                    // Initial gap for -(An) or indexed
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
                    if (hasExt2(eaMode, eaReg, size)) {
                        if (!fetchExtension(cpu, EXT2)) return false;
                    }
                    nextStep(cpu);
                    continue;
                }
                case 3 -> {
                    if (toDn) {
                        if (eaMode == 0) { // Dn: np (or np nn for .L)
                            int result = calculateAdd(cpu, cpu.getD(eaReg, size), cpu.getD(reg, size), size);
                            cpu.setD(reg, result, size);
                            refillPipeline(cpu);
                            if (size == 4) {
                                setStep(cpu, 10); // nn gap
                                return false;
                            }
                            return finalizeInstructionSafe(cpu);
                        } else if (eaMode == 1) { // An: np (or np nn for .L)
                            int val = cpu.getA(eaReg, 4);
                            if (size == 2) val = (short) val;
                            int result = calculateAdd(cpu, val, cpu.getD(reg, size), size);
                            cpu.setD(reg, result, size);
                            refillPipeline(cpu);
                            if (size == 4) {
                                setStep(cpu, 10); // nn gap
                                return false;
                            }
                            return finalizeInstructionSafe(cpu);
                        } else if (eaMode == 7 && eaReg == 4) { // #data: np np (or np np np nn for .L)
                            int val;
                            if (size == 4) val = (getTemp(cpu, EXT1) << 16) | (getTemp(cpu, EXT2) & 0xFFFF);
                            else if (size == 2) val = (short) getTemp(cpu, EXT1);
                            else val = (byte) getTemp(cpu, EXT1);

                            if (!cpu.isBusCycleIdle()) return false; // Wait for extension fetch
                            int result = calculateAdd(cpu, val, cpu.getD(reg, size), size);
                            cpu.setD(reg, result, size);
                            refillPipeline(cpu);
                            if (size == 4) {
                                setStep(cpu, 10); // nn gap
                                return false;
                            }
                            return finalizeInstructionSafe(cpu);
                        } else {
                            // Memory EA: nr ...
                            int addr = calculateEA(cpu, eaMode, eaReg, size, cpu.getOpcodeAddress() + 2, getTemp(cpu, EXT1), getTemp(cpu, EXT2), true, false);
                            setTemp(cpu, ADDR, addr);
                            cpu.scheduleRead(addr, size);
                            nextStep(cpu);
                            return false;
                        }
                    } else {
                        // Dn to Memory: nr np nw
                        int addr = calculateEA(cpu, eaMode, eaReg, size, cpu.getOpcodeAddress() + 2, getTemp(cpu, EXT1), getTemp(cpu, EXT2), true, false);
                        setTemp(cpu, ADDR, addr);
                        cpu.scheduleRead(addr, size);
                        nextStep(cpu);
                        return false;
                    }
                }
                case 4 -> { // Latch and start next prefetch
                    if (toDn) {
                        int srcVal = (size == 1) ? cpu.getLatchedByte() : (size == 2 ? cpu.getLatchedBusData() & 0xFFFF : cpu.getLatchedBusData());
                        int result = calculateAdd(cpu, srcVal, cpu.getD(reg, size), size);
                        cpu.setD(reg, result, size);
                        refillPipeline(cpu);
                        if (size == 4) {
                            nextStep(cpu);
                            return false;
                        }
                        return finalizeInstructionSafe(cpu);
                    } else {
                        int dstVal = (size == 1) ? cpu.getLatchedByte() : (size == 2 ? cpu.getLatchedBusData() & 0xFFFF : cpu.getLatchedBusData());
                        setTemp(cpu, DST_VAL, dstVal);
                        refillPipeline(cpu);
                        nextStep(cpu);
                        return false;
                    }
                }
                case 5 -> { // Logic and potentially finish or schedule write
                    if (toDn) {
                        // size == 4 case with memory EA: n gap
                        if (!cpu.isBusCycleIdle()) return false;
                        cpu.idleCycles(2); // n
                        nextStep(cpu);
                        return false;
                    } else {
                        int result = calculateAdd(cpu, cpu.getD(reg, size), getTemp(cpu, DST_VAL), size);
                        if (size == 4) {
                            // ADD.l writes low word then high word
                            cpu.scheduleWriteWord(getTemp(cpu, ADDR) + 2, result & 0xFFFF);
                            cpu.scheduleWriteWord(getTemp(cpu, ADDR), (result >>> 16) & 0xFFFF);
                        } else {
                            cpu.scheduleWrite(getTemp(cpu, ADDR), result, size);
                        }
                        return finalizeInstructionSafe(cpu);
                    }
                }
                case 10 -> { // .L register or immediate gap: nn (n on the 68010)
                    if (!cpu.isBusCycleIdle()) return false;
                    cpu.idleCycles(m68010 ? 2 : 4);
                    nextStep(cpu);
                    return false;
                }
                case 11 -> {
                    return finalizeInstructionSafe(cpu);
                }
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
        int sizeBit = opmode & 0x3;
        int size = (sizeBit == 0) ? 1 : (sizeBit == 1) ? 2 : 4;
        int ext1 = cpu.extensionWordAt(cpu.getOpcodeAddress() + 2);
        int ext2 = cpu.extensionWordAt(cpu.getOpcodeAddress() + 4);
        String sizeSuffix = Disassembly.getSizeSuffix(size);
        String ea = Disassembly.formatEA(eaMode, eaReg, size, ext1, ext2);
        if (opmode < 4) return "ADD" + sizeSuffix + " " + ea + ", D" + reg;
        else return "ADD" + sizeSuffix + " D" + reg + ", " + ea;
    }
}

