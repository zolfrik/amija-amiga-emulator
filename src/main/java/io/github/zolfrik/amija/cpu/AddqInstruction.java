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
 * Implementation of the ADDQ (Add Quick) instruction.
 * Cycle-exact implementation matching yacht.txt
 */
public class AddqInstruction extends AbstractM68kInstruction {

    public static final AddqInstruction INSTANCE = new AddqInstruction();

    private AddqInstruction() {}

    private static final int EXT1 = 0;
    private static final int EXT2 = 1;
    private static final int SRC_VAL = 2;
    private static final int DST_VAL = 3;
    private static final int ADDR = 4;

    @Override
    public boolean executeStep(AbstractMotorola cpu) {
        int op = cpu.getIr();
        int data = (op >> 9) & 0x7;
        if (data == 0) data = 8;
        int sizeBit = (op >> 6) & 0x3;
        int size = (sizeBit == 0) ? 1 : (sizeBit == 1) ? 2 : 4;
        int eaMode = (op >> 3) & 0x7;
        int eaReg = op & 0x7;

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
                    setTemp(cpu, SRC_VAL, data);
                    if (eaMode == 0) { // Dn
                        int result = calculateAdd(cpu, getTemp(cpu, SRC_VAL), cpu.getD(eaReg, size), size);
                        cpu.setD(eaReg, result, size);
                        refillPipeline(cpu);
                        if (size == 4) {
                            setStep(cpu, 10);
                            return false;
                        }
                        return finalizeInstructionSafe(cpu);
                    } else if (eaMode == 1) { // An
                        // ADDQ to An does not set CCR
                        int result = cpu.getA(eaReg, 4) + getTemp(cpu, SRC_VAL);
                        cpu.setA(eaReg, result);
                        refillPipeline(cpu);
                        setStep(cpu, 10);
                        return false;
                    } else {
                        int addr = calculateEA(cpu, eaMode, eaReg, size, cpu.getOpcodeAddress() + 2, getTemp(cpu, EXT1), getTemp(cpu, EXT2), true, false);
                        setTemp(cpu, ADDR, addr);
                        cpu.scheduleRead(addr, size);
                        nextStep(cpu);
                        return false;
                    }
                }
                case 4 -> {
                    int dstVal = (size == 1) ? cpu.getLatchedByte() : (size == 2 ? cpu.getLatchedBusData() & 0xFFFF : cpu.getLatchedBusData());
                    setTemp(cpu, DST_VAL, dstVal);
                    refillPipeline(cpu);
                    nextStep(cpu);
                    return false;
                }
                case 5 -> {
                    int result = calculateAdd(cpu, getTemp(cpu, SRC_VAL), getTemp(cpu, DST_VAL), size);
                    if (size == 4) {
                        cpu.scheduleWriteWord(getTemp(cpu, ADDR) + 2, result & 0xFFFF);
                        cpu.scheduleWriteWord(getTemp(cpu, ADDR), (result >>> 16) & 0xFFFF);
                    } else {
                        cpu.scheduleWrite(getTemp(cpu, ADDR), result, size);
                    }
                    return finalizeInstructionSafe(cpu);
                }
                case 10 -> { // .L or An gap: nn
                    if (!cpu.isBusCycleIdle()) return false;
                    if (eaMode == 1 && sizeBit == 2 && !cpu.seamOverlapEnabled) {
                        // JSON single-instruction path keeps the historic 6-cycle (np n) count.
                        cpu.idleCycles(2);
                    } else {
                        // ADDQ.L to An/Dn: prefetch (with the IPL poll), then 4 internal cycles.
                        // ADDQ.L to An is 8 cycles (np + nn) on real hardware, same as Dn.L.
                        cpu.idleCycles(4);
                    }
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
        int data = (op >> 9) & 0x7;
        if (data == 0) data = 8;
        int sizeBit = (op >> 6) & 0x3;
        int size = (sizeBit == 0) ? 1 : (sizeBit == 1) ? 2 : 4;
        int eaMode = (op >> 3) & 0x7;
        int eaReg = op & 0x7;
        int ext1 = cpu.extensionWordAt(cpu.getOpcodeAddress() + 2);
        int ext2 = cpu.extensionWordAt(cpu.getOpcodeAddress() + 4);
        return "ADDQ" + Disassembly.getSizeSuffix(size) + " #" + data + ", " + Disassembly.formatEA(eaMode, eaReg, size, ext1, ext2);
    }
}

