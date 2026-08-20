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
 * Implementation of the CMPM (Compare Memory) instruction.
 * Timing:
 * .B/.W: 12(3/0) -> nr nr np
 * .L: 20(5/0) -> nR nr nR nr np
 */
public class CmpmInstruction extends AbstractM68kInstruction {

    public static final CmpmInstruction INSTANCE = new CmpmInstruction();
    private CmpmInstruction() {}

    private static final int SRC_VAL = 20;
    private static final int ADDR_DST = 21;

    @Override
    public boolean shouldInhibitPrefetch() {
        return true;
    }

    @Override
    public boolean executeStep(Motorola68000 cpu) {
        int op = cpu.getIr();
        int regX = (op >> 9) & 0x7, regY = op & 0x7, sizeBit = (op >> 6) & 0x3;
        int size = (sizeBit == 0) ? 1 : (sizeBit == 1) ? 2 : 4;

        while (true) {
            if (cpu.isRisingEdgeConsumed()) return false;
            switch (getStep(cpu)) {
                case 0 -> {
                    int addrSrc = cpu.getA(regY, 4);
                    if (size != 4) {
                        // WinUAE op_b108/op_b148 _14_ff: `ipl_fetch_now()` sta prima della
                        // lettura SORGENTE, non sull'ultimo accesso. La .L ha l'ancora fra le
                        // due word della sorgente (case 1), come ADDX/SUBX .L (§6.1.3).
                        cpu.markIplAnchor(Motorola68000.IplAnchor.NOW);
                    }
                    cpu.scheduleRead(addrSrc, (size == 4) ? 2 : size);
                    cpu.setA(regY, addrSrc + (regY == 7 && size == 1 ? 2 : size));
                    nextStep(cpu);
                    return false;
                }
                case 1 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    if (size == 4) {
                        setTemp(cpu, SRC_VAL, (cpu.getLatchedBusData() & 0xFFFF) << 16);
                        // WinUAE op_b188_14_ff: `ipl_fetch_now()` sta FRA le due word della
                        // lettura sorgente, a 16 clock dalla fine.
                        cpu.markIplAnchor(Motorola68000.IplAnchor.NOW);
                        cpu.scheduleRead(cpu.getA(regY, 4) - 2, 2);
                        nextStep(cpu);
                        return false;
                    } else {
                        setTemp(cpu, SRC_VAL, (size == 1) ? cpu.getLatchedByte() : cpu.getLatchedBusData() & 0xFFFF);
                        setStep(cpu, 3); // Move to destination read
                        continue;
                    }
                }
                case 2 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    setTemp(cpu, SRC_VAL, getTemp(cpu, SRC_VAL) | (cpu.getLatchedBusData() & 0xFFFF));
                    nextStep(cpu);
                }
                case 3 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    int addrDst = cpu.getA(regX, 4);
                    cpu.scheduleRead(addrDst, (size == 4) ? 2 : size);
                    cpu.setA(regX, addrDst + (regX == 7 && size == 1 ? 2 : size));
                    nextStep(cpu);
                    return false;
                }
                case 4 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    if (size == 4) {
                        setTemp(cpu, ADDR_DST, (cpu.getLatchedBusData() & 0xFFFF) << 16);
                        cpu.scheduleRead(cpu.getA(regX, 4) - 2, 2);
                        nextStep(cpu);
                        return false;
                    } else {
                        int val = (size == 1) ? cpu.getLatchedByte() : cpu.getLatchedBusData() & 0xFFFF;
                        calculateCmp(cpu, getTemp(cpu, SRC_VAL), val, size);
                        setStep(cpu, 6);
                        continue;
                    }
                }
                case 5 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    int dst = getTemp(cpu, ADDR_DST) | (cpu.getLatchedBusData() & 0xFFFF);
                    calculateCmp(cpu, getTemp(cpu, SRC_VAL), dst, 4);
                    nextStep(cpu);
                }
                case 6 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    refillPipeline(cpu); // np
                    return finalizeInstructionSafe(cpu);
                }
                default -> { return finalizeInstruction(cpu); }
            }
        }
    }

    @Override
    public String getDisassembly(Motorola68000 cpu) {
        int op = cpu.getIr();
        int regX = (op >> 9) & 0x7, regY = op & 0x7, sizeBit = (op >> 6) & 0x3;
        int size = (sizeBit == 0) ? 1 : (sizeBit == 1) ? 2 : 4;
        return "CMPM" + Disassembly.getSizeSuffix(size) + " (A" + regY + ")+, (A" + regX + ")+";
    }
}
