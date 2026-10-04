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
 * Implementation of the ADDA (Add Address) instruction.
 * Cycle-exact implementation matching yacht.txt
 */
public class AddaInstruction extends AbstractM68kInstruction {

    public static final AddaInstruction INSTANCE = new AddaInstruction();

    private AddaInstruction() {}

    private static final int EXT1 = 0;
    private static final int EXT2 = 1;
    private static final int SRC_VAL = 2;
    private static final int ADDR = 3;

    @Override
    public boolean executeStep(AbstractMotorola cpu) {
        int op = cpu.getIr();
        int dstReg = (op >> 9) & 0x7;
        int opmode = (op >> 6) & 0x7;
        int srcMode = (op >> 3) & 0x7;
        int srcReg = op & 0x7;
        int size = (opmode == 7) ? 4 : 2;

        while (true) {
            if (cpu.isRisingEdgeConsumed()) return false;
            switch (getStep(cpu)) {
                case 0 -> {
                    if (srcMode == 4 || srcMode == 6 || (srcMode == 7 && srcReg == 3)) {
                        indexedGap2(cpu);
                    }
                    nextStep(cpu);
                    continue;
                }
                case 1 -> {
                    if (hasExt1(srcMode, srcReg)) {
                        if (!cpu.isIRDValid()) return false;
                        setTemp(cpu, EXT1, cpu.getIRD() & 0xFFFF);
                        cpu.consumeIRC();
                        cpu.startPrefetchCycle();
                    }
                    nextStep(cpu);
                    continue;
                }
                case 2 -> {
                    if (hasExt2(srcMode, srcReg, size)) {
                        if (!cpu.isIRDValid()) return false;
                        setTemp(cpu, EXT2, cpu.getIRD() & 0xFFFF);
                        cpu.consumeIRC();
                        cpu.startPrefetchCycle();
                    }
                    nextStep(cpu);
                    continue;
                }
                case 3 -> {
                    if (srcMode == 0) { // Dn
                        int val = cpu.getD(srcReg, 4);
                        if (size == 2) val = (short) (val & 0xFFFF);
                        setTemp(cpu, SRC_VAL, val);
                        setStep(cpu, 5);
                        continue;
                    } else if (srcMode == 1) { // An
                        int val = cpu.getA(srcReg, 4);
                        if (size == 2) val = (short) (val & 0xFFFF);
                        setTemp(cpu, SRC_VAL, val);
                        setStep(cpu, 5);
                        continue;
                    } else if (srcMode == 7 && srcReg == 4) { // #data
                        int val;
                        if (size == 4) val = (getTemp(cpu, EXT1) << 16) | (getTemp(cpu, EXT2) & 0xFFFF);
                        else val = (short) getTemp(cpu, EXT1);
                        setTemp(cpu, SRC_VAL, val);

                        if (!cpu.isBusCycleIdle()) return false;
                        setStep(cpu, 5);
                        continue;
                    } else {
                        int addr = calculateEA(cpu, srcMode, srcReg, size, cpu.getOpcodeAddress() + 2, getTemp(cpu, EXT1), getTemp(cpu, EXT2), true, false);
                        setTemp(cpu, ADDR, addr);
                        cpu.scheduleRead(addr, size);
                        nextStep(cpu);
                        return false;
                    }
                }
                case 4 -> {
                    int val = (size == 4) ? cpu.getLatchedBusData() : (short) cpu.getLatchedBusData();
                    setTemp(cpu, SRC_VAL, val);
                    nextStep(cpu);
                    continue;
                }
                case 5 -> {
                    // SUBA/ADDA: the `next` sample sits RIGHT BEFORE the final prefetch, so the
                    // anchor is this prefetch and not the operand read ($90D0/$91D0/$D0D0/
                    // $D1D0: the sample sits between the read and the prefetch).
                    cpu.markIplAnchor(AbstractMotorola.IplAnchor.NEXT);
                    refillPipeline(cpu);
                    int srcVal = getTemp(cpu, SRC_VAL);
                    int dstVal = cpu.getA(dstReg, 4);
                    cpu.setA(dstReg, dstVal + srcVal);
                    nextStep(cpu);
                    return false;
                }
                case 6 -> {
                    if (size == 4) {
                        // .L : Dn, An, and #data modes are 8(1/0) cycles -> np nn
                        // others are 6(1/0) cycles -> np n
                        if (srcMode <= 1 || (srcMode == 7 && srcReg == 4)) {
                            cpu.idleCycles(4); // nn
                        } else {
                            cpu.idleCycles(2); // n
                        }
                    } else {
                        // .W : All are 8(1/0) cycles -> np nn
                        cpu.idleCycles(4); // nn
                    }
                    nextStep(cpu);
                    return false;
                }
                case 7 -> {
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
        int dstReg = (op >> 9) & 0x7;
        int opmode = (op >> 6) & 0x7;
        int srcMode = (op >> 3) & 0x7;
        int srcReg = op & 0x7;
        int size = (opmode == 7) ? 4 : 2;
        int ext1 = cpu.extensionWordAt(cpu.getOpcodeAddress() + 2);
        int ext2 = cpu.extensionWordAt(cpu.getOpcodeAddress() + 4);
        return "ADDA" + Disassembly.getSizeSuffix(size) + " " + Disassembly.formatEA(srcMode, srcReg, size, ext1, ext2) + ", A" + dstReg;
    }
}

