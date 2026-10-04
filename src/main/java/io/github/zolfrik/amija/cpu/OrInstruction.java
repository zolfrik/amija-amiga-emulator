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
 * Implementation of the OR instruction.
 */
public class OrInstruction extends AbstractM68kInstruction {

    public static final OrInstruction INSTANCE = new OrInstruction(false);
    /** The 68010 form: 2 clocks less after a long data register source. */
    public static final OrInstruction M68010 = new OrInstruction(true);

    private final boolean m68010;
    private OrInstruction(boolean m68010) {
        this.m68010 = m68010;
    }

    private static final int EXT1 = 0;
    private static final int EXT2 = 1;
    private static final int ADDR = 2;
    private static final int RESULT = 3;
    private static final int TMP_VAL = 4;

    @Override
    public boolean shouldInhibitPrefetch() {
        return true;
    }

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
                    if (eaMode == 4 || eaMode == 6 || (eaMode == 7 && eaReg == 3)) {
                        indexedGap2(cpu);
                    }
                    nextStep(cpu);
                }
                case 1 -> {
                    if (hasExt1(eaMode, eaReg)) {
                        if (!fetchExtension(cpu, EXT1)) return false;
                    }
                    nextStep(cpu);
                }
                case 2 -> {
                    if (hasExt2(eaMode, eaReg, size)) {
                        if (!fetchExtension(cpu, EXT2)) return false;
                    }
                    nextStep(cpu);
                }
                case 3 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    if (toDn) {
                        if (eaMode == 0) { // Dn
                            int result = calculateOr(cpu, cpu.getD(eaReg, size), cpu.getD(reg, size), size);
                            cpu.setD(reg, result, size);
                            refillPipeline(cpu);
                            if (size == 4) {
                                setStep(cpu, 10);
                                return false;
                            }
                            return finalizeInstructionSafe(cpu);
                        } else if (eaMode == 7 && eaReg == 4) { // #data
                            int imm;
                            if (size == 4) imm = (getTemp(cpu, EXT1) << 16) | (getTemp(cpu, EXT2) & 0xFFFF);
                            else if (size == 2) imm = (short) getTemp(cpu, EXT1);
                            else imm = (byte) getTemp(cpu, EXT1);

                            int result = calculateOr(cpu, imm, cpu.getD(reg, size), size);
                            cpu.setD(reg, result, size);
                            refillPipeline(cpu);
                            if (size == 4) {
                                setStep(cpu, 10);
                                return false;
                            }
                            return finalizeInstructionSafe(cpu);
                        }
                    }
                    // Memory or An
                    int addr = calculateEA(cpu, eaMode, eaReg, size, cpu.getOpcodeAddress() + 2, getTemp(cpu, EXT1), getTemp(cpu, EXT2), true, false);
                    if (cpu.getCpuState() == AbstractMotorola.CpuState.EXCEPTION) return false;
                    setTemp(cpu, ADDR, addr);
                    if (size == 4) cpu.scheduleRead(addr, 2);
                    else cpu.scheduleRead(addr, size);
                    nextStep(cpu);
                    return false;
                }
                case 4 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    if (cpu.getCpuState() == AbstractMotorola.CpuState.EXCEPTION) return false;

                    if (size == 4) {
                        setTemp(cpu, TMP_VAL, cpu.getLatchedBusData() << 16);
                        cpu.scheduleRead(getTemp(cpu, ADDR) + 2, 2);
                        nextStep(cpu);
                        return false;
                    }

                    int val = (size == 1) ? cpu.getLatchedByte() : cpu.getLatchedBusData();
                    if (toDn) {
                        int result = calculateOr(cpu, val, cpu.getD(reg, size), size);
                        cpu.setD(reg, result, size);
                        refillPipeline(cpu);
                        return finalizeInstructionSafe(cpu);
                    } else {
                        int result = calculateOr(cpu, cpu.getD(reg, size), val, size);
                        setTemp(cpu, RESULT, result); // result to write
                        refillPipeline(cpu);
                        setStep(cpu, 8);
                    }
                    continue;
                }
                case 5 -> { // Only for .L memory read
                    if (!cpu.isBusCycleIdle()) return false;
                    int lsw = cpu.getLatchedBusData() & 0xFFFF;
                    int eaVal = getTemp(cpu, TMP_VAL) | lsw;
                    if (toDn) {
                        int result = calculateOr(cpu, eaVal, cpu.getD(reg, 4), 4);
                        cpu.setD(reg, result, 4);
                        refillPipeline(cpu);
                        setStep(cpu, 6);
                    } else {
                        int result = calculateOr(cpu, cpu.getD(reg, 4), eaVal, 4);
                        setTemp(cpu, RESULT, result); // result to write
                        refillPipeline(cpu);
                        setStep(cpu, 7);
                    }
                    continue;
                }
                case 6 -> { // Trailing gap n for .L <ea>, Dn
                    if (!cpu.isBusCycleIdle()) return false;
                    cpu.idleCycles(2);
                    setStep(cpu, 11);
                    return false;
                }
                case 7 -> { // Only for .L memory first write (LSW)
                    if (!cpu.isBusCycleIdle()) return false;
                    cpu.scheduleWriteWord(getTemp(cpu, ADDR) + 2, getTemp(cpu, RESULT) & 0xFFFF);
                    setStep(cpu, 9);
                    return false;
                }
                case 8 -> { // Write result for .B/.W memory
                    if (!cpu.isBusCycleIdle()) return false;
                    // Overlapped prefetch (Phase 3): the .b/.w write is the LAST bus cycle;
                    // finalize in this same step (seam 0) instead of deferring to step 11.
                    // See docs/cpu-overlapped-prefetch-rework-plan.md, Phase 3.
                    cpu.scheduleWrite(getTemp(cpu, ADDR), getTemp(cpu, RESULT), size);
                    return finalizeInstructionSafe(cpu);
                }
                case 9 -> { // .L memory MSW write + finalize (last bus cycle: seam 0)
                    if (!cpu.isBusCycleIdle()) return false;
                    cpu.scheduleWriteWord(getTemp(cpu, ADDR), (getTemp(cpu, RESULT) >>> 16) & 0xFFFF);
                    return finalizeInstructionSafe(cpu);
                }
                case 10 -> { // .L register or immediate gap: nn (n on the 68010 from Dn)
                    if (!cpu.isBusCycleIdle()) return false;
                    cpu.idleCycles(m68010 && eaMode == 0 ? 2 : 4);
                    setStep(cpu, 11);
                    return false;
                }
                case 11 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    return finalizeInstructionSafe(cpu);
                }
                default -> { return finalizeInstructionSafe(cpu); }
            }
        }
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
        if (opmode < 4) return "OR" + sizeSuffix + " " + ea + ", D" + reg;
        else return "OR" + sizeSuffix + " D" + reg + ", " + ea;
    }
}

