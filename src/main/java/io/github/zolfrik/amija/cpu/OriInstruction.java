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
 * Implementation of the ORI (OR Immediate) instruction.
 */
public class OriInstruction extends AbstractM68kInstruction {

    public static final OriInstruction INSTANCE = new OriInstruction();
    private OriInstruction() {}

    private static final int IMM_EXT1 = 0;
    private static final int IMM_EXT2 = 1;
    private static final int EA_EXT1 = 2;
    private static final int EA_EXT2 = 3;
    private static final int VAL = 4;
    private static final int ADDR = 5;

    @Override
    public boolean shouldInhibitPrefetch() {
        return true;
    }

    @Override
    public boolean executeStep(AbstractMotorola cpu) {
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
                    setTemp(cpu, VAL, imm);

                    if (eaMode == 0) { // Dn
                        int result = calculateOr(cpu, getTemp(cpu, VAL), cpu.getD(eaReg, size), size);
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
                        if (cpu.getCpuState() == AbstractMotorola.CpuState.EXCEPTION) return false;
                        setTemp(cpu, ADDR, addr);
                        if (size == 4) cpu.scheduleRead(addr, 2);
                        else cpu.scheduleRead(addr, size);
                        nextStep(cpu);
                        return false;
                    }
                }
                case 7 -> { // Latch data and refill pipeline
                    if (!cpu.isBusCycleIdle()) return false;
                    if (cpu.getCpuState() == AbstractMotorola.CpuState.EXCEPTION) return false;

                    if (size == 4) {
                        setTemp(cpu, EA_EXT1, cpu.getLatchedBusData() << 16); // Reuse EA_EXT1 as temp for MSW
                        cpu.scheduleRead(getTemp(cpu, ADDR) + 2, 2);
                        nextStep(cpu);
                        return false;
                    }

                    int val = (size == 1) ? cpu.getLatchedByte() : cpu.getLatchedBusData();
                    int result = calculateOr(cpu, getTemp(cpu, VAL), val, size);
                    setTemp(cpu, VAL, result); // result back into VAL
                    refillPipeline(cpu);
                    nextStep(cpu);
                    return false;
                }
                case 8 -> { // Schedule write or finish .L read
                    if (!cpu.isBusCycleIdle()) return false;
                    if (cpu.getCpuState() == AbstractMotorola.CpuState.EXCEPTION) return false;

                    if (size == 4) {
                        int lsw = cpu.getLatchedBusData() & 0xFFFF;
                        int eaVal = getTemp(cpu, EA_EXT1) | lsw;
                        int result = calculateOr(cpu, getTemp(cpu, VAL), eaVal, 4);
                        setTemp(cpu, VAL, result);
                        refillPipeline(cpu);
                        nextStep(cpu);
                        return false;
                    } else {
                        // Overlapped prefetch (Phase 3): finalize with the .b/.w write (seam 0)
                        // rather than deferring to step 11. See
                        // docs/cpu-overlapped-prefetch-rework-plan.md, Phase 3.
                        cpu.scheduleWrite(getTemp(cpu, ADDR), getTemp(cpu, VAL), size);
                        return finalizeInstructionSafe(cpu);
                    }
                }
                case 9 -> { // Only for .L memory
                    if (!cpu.isBusCycleIdle()) return false;
                    cpu.scheduleWriteWord(getTemp(cpu, ADDR) + 2, getTemp(cpu, VAL) & 0xFFFF);
                    nextStep(cpu);
                    return false;
                }
                case 10 -> { // Second write for .L memory OR Dn gap
                    if (!cpu.isBusCycleIdle()) return false;
                    if (eaMode == 0) {
                        cpu.idleCycles(4); // nn
                        nextStep(cpu);
                        return false;
                    } else {
                        // .L memory MSW write is the LAST bus cycle: finalize with it (seam 0).
                        cpu.scheduleWriteWord(getTemp(cpu, ADDR), (getTemp(cpu, VAL) >>> 16) & 0xFFFF);
                        return finalizeInstructionSafe(cpu);
                    }
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
        return "ORI" + Disassembly.getSizeSuffix(size) + " " + immStr + ", " + Disassembly.formatEA(eaMode, eaReg, size, ext1, ext2);
    }
}

