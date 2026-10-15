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
 * Implementation of the TST instruction.
 * Timing:
 * Dn: 4(1/0) -> np
 * Memory: 4(1/0) + EA
 *   .B/.W: EA | nr | np
 *   .L: EA | nR nr | np
 */
public class TstInstruction extends AbstractM68kInstruction {

    public static final TstInstruction INSTANCE = new TstInstruction();
    private TstInstruction() {}

    private static final int EXT1 = 0;
    private static final int EXT2 = 1;

    @Override
    public boolean shouldInhibitPrefetch() {
        return true;
    }

    @Override
    public boolean executeStep(AbstractMotorola cpu) {
        int op = cpu.getIr();
        int s = (op >> 6) & 3, size = (s == 0) ? 1 : (s == 1) ? 2 : 4;
        int eaMode = (op >> 3) & 7, eaReg = op & 7;

        while (true) {
            if (cpu.isRisingEdgeConsumed()) return false;
            switch (getStep(cpu)) {
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
                    if (hasExt2(eaMode, eaReg, size)) {
                        if (!fetchExtension(cpu, EXT2)) return false;
                    }
                    nextStep(cpu);
                    continue;
                }
                case 3 -> {
                    if (eaMode == 0) { // Dn
                        updateFlags(cpu, cpu.getD(eaReg, size), size);
                        refillPipeline(cpu); // np
                        return finalizeInstructionSafe(cpu);
                    } else {
                        int addr = calculateEA(cpu, eaMode, eaReg, size, cpu.getOpcodeAddress() + 2, getTemp(cpu, EXT1), getTemp(cpu, EXT2), true, false);
                        cpu.scheduleRead(addr, size); // nr or nR nr
                        nextStep(cpu);
                        return false;
                    }
                }
                case 4 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    int val = (size == 1) ? cpu.getLatchedByte() : (size == 2 ? cpu.getLatchedBusData() & 0xFFFF : cpu.getLatchedBusData());
                    updateFlags(cpu, val, size);
                    refillPipeline(cpu); // np
                    return finalizeInstructionSafe(cpu);
                }
                default -> { return finalizeInstruction(cpu); }
            }
        }
    }

    private void updateFlags(AbstractMotorola cpu, int val, int size) {
        int msbMask = (size == 1) ? 0x80 : (size == 2) ? 0x8000 : 0x80000000;
        long sizeMask = (size == 1) ? 0xFFL : (size == 2) ? 0xFFFFL : 0xFFFFFFFFL;
        long maskedVal = val & sizeMask;
        int sr = cpu.getSR() & 0xFFF0; // Clear N, Z, V, C. Keep X!
        if ((maskedVal & msbMask) != 0) sr |= 0x08;
        if (maskedVal == 0) sr |= 0x04;
        cpu.writeSR(sr, false);
    }

    @Override
    public String getDisassembly(AbstractMotorola cpu) {
        int op = cpu.getIr();
        int s = (op >> 6) & 3;
        int size = (s == 0) ? 1 : (s == 1) ? 2 : 4;
        return "TST" + Disassembly.getSizeSuffix(size) + " " + Disassembly.formatEA((op >> 3) & 7, op & 7, size, cpu.extensionWordAt(cpu.getOpcodeAddress() + 2), cpu.extensionWordAt(cpu.getOpcodeAddress() + 4));
    }
}

