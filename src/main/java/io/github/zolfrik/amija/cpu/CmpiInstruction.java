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
 * Implementation of the CMPI (Compare Immediate) instruction.
 */
public class CmpiInstruction extends AbstractM68kInstruction {

    public static final CmpiInstruction INSTANCE = new CmpiInstruction();
    private CmpiInstruction() {}

    private static final int IMM = 0;
    private static final int EXT1 = 1;
    private static final int EXT2 = 2;
    private static final int ADDR = 3;
    private static final int DST_VAL = 4;

    @Override
    public boolean shouldInhibitPrefetch() {
        return true;
    }

    @Override
    public boolean executeStep(AbstractMotorola cpu) {
        int op = cpu.getIr();
        int s = (op >> 6) & 0x3, size = (s == 0) ? 1 : (s == 1) ? 2 : 4;
        int eaMode = (op >> 3) & 0x7, eaReg = op & 0x7;

        while (true) {
            if (cpu.isRisingEdgeConsumed()) return false;
            switch (getStep(cpu)) {
                case 0 -> {
                    // Fetch immediate extension(s)
                    if (!fetchExtension(cpu, IMM)) return false;
                    nextStep(cpu);
                    return false; // np
                }
                case 1 -> {
                    if (size == 4) {
                        if (!fetchExtension(cpu, EXT1)) return false;
                        setTemp(cpu, IMM, (getTemp(cpu, IMM) << 16) | (getTemp(cpu, EXT1) & 0xFFFF));
                        nextStep(cpu);
                        return false; // np
                    }
                    int imm = getTemp(cpu, IMM);
                    if (size == 1) imm &= 0xFF; else imm = (short) imm;
                    setTemp(cpu, IMM, imm);
                    nextStep(cpu);
                }
                case 2 -> {
                    // EA Initial Gap (n)
                    if (eaMode == 4 || eaMode == 6 || (eaMode == 7 && eaReg == 3)) {
                        cpu.idleCycles(2);
                        nextStep(cpu);
                        return false;
                    }
                    nextStep(cpu);
                }
                case 3 -> {
                    if (hasExt1(eaMode, eaReg)) {
                        if (!fetchExtension(cpu, EXT1)) return false;
                        nextStep(cpu);
                        return false; // np
                    }
                    nextStep(cpu);
                }
                case 4 -> {
                    if (hasExt2(eaMode, eaReg, size)) {
                        if (!fetchExtension(cpu, EXT2)) return false;
                        nextStep(cpu);
                        return false; // np
                    }
                    nextStep(cpu);
                }
                case 5 -> {
                    if (eaMode == 0) {
                        calculateCmp(cpu, getTemp(cpu, IMM), cpu.getD(eaReg, size), size);
                        setStep(cpu, 10);
                        continue;
                    }
                    int basePC = cpu.getOpcodeAddress() + 2 + (size == 4 ? 4 : 2);
                    int addr = calculateEA(cpu, eaMode, eaReg, size, basePC, getTemp(cpu, EXT1), getTemp(cpu, EXT2), true, false);
                    setTemp(cpu, ADDR, addr);
                    if (size == 4) {
                        cpu.scheduleRead(addr, 2); // nR
                    } else {
                        cpu.scheduleRead(addr, size); // nr
                    }
                    nextStep(cpu);
                    return false;
                }
                case 6 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    if (size == 4) {
                        setTemp(cpu, DST_VAL, cpu.getLatchedBusData() << 16);
                        cpu.scheduleRead(getTemp(cpu, ADDR) + 2, 2); // nr
                        nextStep(cpu);
                        return false;
                    } else {
                        int dst = (size == 1) ? cpu.getLatchedByte() : cpu.getLatchedBusData() & 0xFFFF;
                        calculateCmp(cpu, getTemp(cpu, IMM), dst, size);
                        setStep(cpu, 10);
                    }
                }
                case 7 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    int dst = getTemp(cpu, DST_VAL) | (cpu.getLatchedBusData() & 0xFFFF);
                    calculateCmp(cpu, getTemp(cpu, IMM), dst, size);
                    setStep(cpu, 10);
                }
                case 10 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    refillPipeline(cpu); // np
                    nextStep(cpu);
                    return false;
                }
                case 11 -> {
                    if (size == 4 && eaMode == 0) {
                        cpu.idleCycles(2); // Final n for .L Dn
                        nextStep(cpu);
                        return false;
                    }
                    return finalizeInstructionSafe(cpu);
                }
                case 12 -> {
                    return finalizeInstructionSafe(cpu);
                }
                default -> { return finalizeInstruction(cpu); }
            }
        }
    }

    @Override
    public String getDisassembly(AbstractMotorola cpu) {
        int op = cpu.getIr();
        int s = (op >> 6) & 0x3, size = (s == 0) ? 1 : (s == 1) ? 2 : 4;
        int pc = cpu.getOpcodeAddress();
        int immHigh = cpu.extensionWordAt(cpu.getOpcodeAddress() + 2);
        int imm = immHigh, offset = 4;
        if (size == 4) { imm = (immHigh << 16) | (cpu.extensionWordAt(cpu.getOpcodeAddress() + 4) & 0xFFFF); offset = 6; }
        else if (size == 1) imm &= 0xFF; else imm = (short) imm;
        return "CMPI" + Disassembly.getSizeSuffix(size) + " #$" + Disassembly.formatHex(imm, size * 2) + ", " + Disassembly.formatEA((op >> 3) & 7, op & 7, size, cpu.extensionWordAt(cpu.getOpcodeAddress() + offset), cpu.extensionWordAt(cpu.getOpcodeAddress() + offset + 2));
    }
}

