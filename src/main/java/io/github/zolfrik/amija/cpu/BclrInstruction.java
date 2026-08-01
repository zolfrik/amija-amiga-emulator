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
 * Implementation of the BCLR (Bit Test and Clear) instruction.
 */
public class BclrInstruction extends AbstractM68kInstruction {

    public static final BclrInstruction INSTANCE = new BclrInstruction();
    private BclrInstruction() {}

    private static final int EXT1 = 0;
    private static final int EXT2 = 1;
    private static final int ADDR = 2;
    private static final int BIT = 3;
    private static final int VAL = 4;

    @Override
    public boolean shouldInhibitPrefetch() {
        return true;
    }

    @Override
    public boolean executeStep(Motorola68000 cpu) {
        int op = cpu.getIr();
        boolean isStatic = (op & 0x0100) == 0;
        int eaMode = (op >> 3) & 0x7, eaReg = op & 0x7;
        int size = (eaMode == 0) ? 4 : 1;

        while (true) {
            if (cpu.isRisingEdgeConsumed()) return false;
            switch (getStep(cpu)) {
                case 0 -> {
                    if (isStatic) {
                        if (!fetchExtension(cpu, BIT)) return false;
                        setTemp(cpu, BIT, getTemp(cpu, BIT) & 0xFF);
                    } else {
                        setTemp(cpu, BIT, cpu.getD((op >> 9) & 7, 4));
                    }
                    nextStep(cpu);
                    continue;
                }
                case 1 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    if (eaMode == 4 || eaMode == 6 || (eaMode == 7 && eaReg == 3)) {
                        cpu.idleCycles(2);
                        nextStep(cpu);
                        return false;
                    }
                    nextStep(cpu);
                    continue;
                }
                case 2 -> {
                    if (hasExt1(eaMode, eaReg)) {
                        if (!fetchExtension(cpu, EXT1)) return false;
                    }
                    nextStep(cpu);
                    continue;
                }
                case 3 -> {
                    if (hasExt2(eaMode, eaReg, size)) {
                        if (!fetchExtension(cpu, EXT2)) return false;
                    }
                    nextStep(cpu);
                    continue;
                }
                case 4 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    if (eaMode == 0) {
                        int val = cpu.getD(eaReg, 4);
                        int bit = getTemp(cpu, BIT) & 31;
                        int sr = cpu.getSR() & 0xFFFB;
                        if ((val & (1 << bit)) == 0) sr |= 0x04;
                        cpu.writeSR(sr, false);
                        cpu.setD(eaReg, val & ~(1 << bit), 4);

                        refillPipeline(cpu);
                        setStep(cpu, 10);
                        return false;
                    }
                    int basePC = cpu.getOpcodeAddress() + (isStatic ? 4 : 2);
                    int addr = calculateEA(cpu, eaMode, eaReg, size, basePC, getTemp(cpu, EXT1), getTemp(cpu, EXT2), true, false);
                    setTemp(cpu, ADDR, addr);
                    cpu.scheduleRead(addr, 1);
                    nextStep(cpu);
                    return false;
                }
                case 5 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    int val = cpu.getLatchedByte();
                    int bit = getTemp(cpu, BIT) & 7;
                    int sr = cpu.getSR() & 0xFFFB;
                    if ((val & (1 << bit)) == 0) sr |= 0x04;
                    cpu.writeSR(sr, false);
                    setTemp(cpu, VAL, val & ~(1 << bit));

                    refillPipeline(cpu);
                    nextStep(cpu);
                    return false;
                }
                case 6 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    cpu.scheduleWrite(getTemp(cpu, ADDR), getTemp(cpu, VAL), 1);
                    return finalizeInstructionSafe(cpu);
                }
                case 10 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    int bit = getTemp(cpu, BIT) & 31;
                    int gap = (bit < 16) ? 4 : 6;
                    cpu.idleCycles(gap);
                    nextStep(cpu);
                    return false;
                }
                case 11 -> {
                    return finalizeInstructionSafe(cpu);
                }
                default -> { return finalizeInstruction(cpu); }
            }
        }
    }

    @Override
    public String getDisassembly(Motorola68000 cpu) {
        int op = cpu.getIr();
        boolean isStatic = (op & 0x0100) == 0;
        int eaMode = (op >> 3) & 0x7, eaReg = op & 0x7;
        int pc = cpu.getOpcodeAddress();
        String bitStr = isStatic ? "#$" + Disassembly.formatHex(cpu.extensionWordAt(cpu.getOpcodeAddress() + 2) & 0xFF, 2) : "D" + ((op >> 9) & 7);
        int offset = isStatic ? 4 : 2;
        return "BCLR " + bitStr + ", " + Disassembly.formatEA(eaMode, eaReg, (eaMode == 0 ? 4 : 1), cpu.extensionWordAt(cpu.getOpcodeAddress() + offset), cpu.extensionWordAt(cpu.getOpcodeAddress() + offset + 2));
    }
}

