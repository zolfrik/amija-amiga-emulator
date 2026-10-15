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
 * Implementation of the LEA (Load Effective Address) instruction.
 * Cycle-accurate implementation matching yacht.txt
 */
public class LeaInstruction extends AbstractM68kInstruction {

    public static final LeaInstruction INSTANCE = new LeaInstruction();

    private LeaInstruction() {}

    private static final int EXT1 = 0;
    private static final int EXT2 = 1;

    @Override
    public boolean executeStep(AbstractMotorola cpu) {
        int op = cpu.getIr();
        int reg = (op >> 9) & 0x7;
        int eaMode = (op >> 3) & 0x7;
        int eaReg = op & 0x7;

        while (true) {
            if (cpu.isRisingEdgeConsumed()) return false;
            switch (getStep(cpu)) {
                case 0 -> {
                    // Initial gap for indexed addressing modes (Mode 6 and 7.3)
                    if (eaMode == 6 || (eaMode == 7 && eaReg == 3)) {
                        cpu.idleCycles(2); // n
                        nextStep(cpu);
                        return false;
                    }
                    nextStep(cpu);
                }
                case 1 -> {
                    if (hasExt1(eaMode, eaReg)) {
                        if (!fetchExtension(cpu, EXT1)) return false;
                        if (eaMode == 6 || (eaMode == 7 && eaReg == 3)) {
                            nextStep(cpu);
                        } else {
                            setStep(cpu, 3);
                        }
                        return false;
                    }
                    setStep(cpu, 4);
                }
                case 2 -> {
                    // Second gap for indexed addressing modes
                    cpu.idleCycles(2); // n
                    nextStep(cpu);
                    return false;
                }
                case 3 -> {
                    if (hasExt2(eaMode, eaReg, 4)) {
                        if (!fetchExtension(cpu, EXT2)) return false;
                    }
                    nextStep(cpu);
                }
                case 4 -> {
                    // Ensure the bus is idle before final refill to capture all required transactions
                    if (!cpu.isBusCycleIdle()) return false;
                    int addr = calculateEA(cpu, eaMode, eaReg, 4, cpu.getOpcodeAddress() + 2, getTemp(cpu, EXT1), getTemp(cpu, EXT2), false, false);
                    cpu.setA(reg, addr);
                    refillPipeline(cpu); // final np
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
        int eaMode = (op >> 3) & 0x7;
        int eaReg = op & 0x7;
        int ext1 = cpu.extensionWordAt(cpu.getOpcodeAddress() + 2);
        int ext2 = cpu.extensionWordAt(cpu.getOpcodeAddress() + 4);
        return "LEA " + Disassembly.formatEA(eaMode, eaReg, 4, ext1, ext2) + ", A" + reg;
    }
}

