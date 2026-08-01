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
 * Implementation of the ANDI (AND Immediate) instruction.
 */
public class AndiInstruction extends AbstractM68kInstruction {

    public static final AndiInstruction INSTANCE = new AndiInstruction();

    private AndiInstruction() {}

    private static final int IMM_EXT1 = 0;
    private static final int IMM_EXT2 = 1;
    private static final int EA_EXT1 = 2;
    private static final int EA_EXT2 = 3;
    private static final int SRC_VAL = 4;
    private static final int DST_VAL = 5;
    private static final int ADDR = 6;

    @Override
    public boolean executeStep(Motorola68000 cpu) {
        int op = cpu.getIr();
        int sizeBit = (op >> 6) & 0x3;
        int size = (sizeBit == 0) ? 1 : (sizeBit == 1) ? 2 : 4;
        int eaMode = (op >> 3) & 0x7;
        int eaReg = op & 0x7;

        while (true) {
            if (cpu.isRisingEdgeConsumed()) return false;
            switch (getStep(cpu)) {
                case 0 -> {
                    nextStep(cpu);
                    continue;
                }
                case 1 -> { // Fetch Immediate Extension 1
                    if (!fetchExtension(cpu, IMM_EXT1)) return false;
                    nextStep(cpu);
                    continue;
                }
                case 2 -> { // Fetch Immediate Extension 2 (only for Long)
                    if (size == 4) {
                        if (!fetchExtension(cpu, IMM_EXT2)) return false;
                    }
                    nextStep(cpu);
                    continue;
                }
                case 3 -> { // Gap
                    if (!cpu.isBusCycleIdle()) return false;
                    if (eaMode == 4 || eaMode == 6 || (eaMode == 7 && eaReg == 3)) {
                        indexedGap2(cpu);
                        nextStep(cpu);
                        return false;
                    }
                    nextStep(cpu);
                    continue;
                }
                case 4 -> { // Fetch EA extensions
                    if (hasExt1(eaMode, eaReg)) {
                        if (!fetchExtension(cpu, EA_EXT1)) return false;
                    }
                    nextStep(cpu);
                    continue;
                }
                case 5 -> {
                    if (hasExt2(eaMode, eaReg, size)) {
                        if (!fetchExtension(cpu, EA_EXT2)) return false;
                    }
                    nextStep(cpu);
                    continue;
                }
                case 6 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    int imm;
                    if (size == 4) imm = (getTemp(cpu, IMM_EXT1) << 16) | (getTemp(cpu, IMM_EXT2) & 0xFFFF);
                    else if (size == 2) imm = (short) getTemp(cpu, IMM_EXT1);
                    else imm = (byte) getTemp(cpu, IMM_EXT1);
                    setTemp(cpu, SRC_VAL, imm);

                    if (eaMode == 0) { // Dn
                        int result = calculateAnd(cpu, getTemp(cpu, SRC_VAL), cpu.getD(eaReg, size), size);
                        cpu.setD(eaReg, result, size);
                        refillPipeline(cpu);
                        if (size == 4) {
                            setStep(cpu, 10);
                            return false;
                        }
                        return finalizeInstructionSafe(cpu);
                    } else {
                        // Calculate EA and read
                        int basePC = cpu.getOpcodeAddress() + 2 + (size == 4 ? 4 : 2);
                        int addr = calculateEA(cpu, eaMode, eaReg, size, basePC, getTemp(cpu, EA_EXT1), getTemp(cpu, EA_EXT2), true, false);
                        setTemp(cpu, ADDR, addr);
                        cpu.scheduleRead(addr, size);
                        nextStep(cpu);
                        return false;
                    }
                }
                case 7 -> { // Latch data and refill pipeline
                    setTemp(cpu, DST_VAL, (size == 1) ? cpu.getLatchedByte() : (size == 2 ? cpu.getLatchedBusData() & 0xFFFF : cpu.getLatchedBusData()));
                    int result = calculateAnd(cpu, getTemp(cpu, SRC_VAL), getTemp(cpu, DST_VAL), size);
                    setTemp(cpu, SRC_VAL, result); // result back into SRC_VAL
                    refillPipeline(cpu);
                    nextStep(cpu);
                    return false;
                }
                case 8 -> { // Schedule write
                    if (size == 4) {
                        cpu.scheduleWriteWord(getTemp(cpu, ADDR) + 2, getTemp(cpu, SRC_VAL) & 0xFFFF);
                        cpu.scheduleWriteWord(getTemp(cpu, ADDR), (getTemp(cpu, SRC_VAL) >>> 16) & 0xFFFF);
                    } else {
                        cpu.scheduleWrite(getTemp(cpu, ADDR), getTemp(cpu, SRC_VAL), size);
                    }
                    return finalizeInstructionSafe(cpu);
                }
                case 10 -> { // .L Dn gap
                    if (!cpu.isBusCycleIdle()) return false;
                    cpu.idleCycles(4);
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
    public String getDisassembly(Motorola68000 cpu) {
        int op = cpu.getIr();
        int sizeBit = (op >> 6) & 0x3;
        int size = (sizeBit == 0) ? 1 : (sizeBit == 1) ? 2 : 4;
        int eaMode = (op >> 3) & 0x7;
        int eaReg = op & 0x7;
        int pc = cpu.getOpcodeAddress();
        int imm1 = cpu.extensionWordAt(cpu.getOpcodeAddress() + 2);
        int imm2 = cpu.extensionWordAt(cpu.getOpcodeAddress() + 4);
        int sc = (size == 4 ? 2 : 1);
        int ext1 = cpu.extensionWordAt(cpu.getOpcodeAddress() + 2 + sc * 2);
        int ext2 = cpu.extensionWordAt(cpu.getOpcodeAddress() + 4 + sc * 2);
        String immStr = (size == 4) ? String.format("#$%08X", (imm1 << 16) | (imm2 & 0xFFFF)) : String.format("#$%04X", (short) imm1);
        return "ANDI" + Disassembly.getSizeSuffix(size) + " " + immStr + ", " + Disassembly.formatEA(eaMode, eaReg, size, ext1, ext2);
    }
}

