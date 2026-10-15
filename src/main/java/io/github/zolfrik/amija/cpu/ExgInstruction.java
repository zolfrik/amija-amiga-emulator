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
 * EXG: Exchange Registers
 */
public class ExgInstruction extends AbstractM68kInstruction {

    public static final ExgInstruction INSTANCE = new ExgInstruction();

    private ExgInstruction() {}

    @Override
    public boolean executeStep(AbstractMotorola cpu) {
        while (true) {
            if (cpu.isRisingEdgeConsumed()) return false;
            switch (getStep(cpu)) {
                case 0 -> {
                    // Yacht: np n (6 cycles)
                    // np - prefetch next word
                    refillPipeline(cpu);
                    nextStep(cpu);
                    return false;
                }
                case 1 -> {
                    // n - idle cycle (2 clocks)
                    if (!cpu.isBusCycleIdle()) return false;

                    int op = cpu.getIr();
                    int rx = (op >> 9) & 0x7;
                    int ry = op & 0x7;
                    int opmode = (op >> 3) & 0x1F;

                    if (opmode == 0x08) { // Dn, Dm
                        int valX = cpu.getD(rx, 4);
                        int valY = cpu.getD(ry, 4);
                        cpu.setD(rx, valY, 4);
                        cpu.setD(ry, valX, 4);
                    } else if (opmode == 0x09) { // An, Am
                        int valX = cpu.getA(rx, 4);
                        int valY = cpu.getA(ry, 4);
                        cpu.setA(rx, valY);
                        cpu.setA(ry, valX);
                    } else if (opmode == 0x11) { // Dn, An
                        int valX = cpu.getD(rx, 4);
                        int valY = cpu.getA(ry, 4);
                        cpu.setD(rx, valY, 4);
                        cpu.setA(ry, valX);
                    }

                    cpu.idleCycles(2);
                    nextStep(cpu);
                    return false;
                }
                case 2 -> {
                    return finalizeInstructionSafe(cpu);
                }
                default -> {
                    return finalizeInstructionSafe(cpu);
                }
            }
        }
    }

    @Override
    public boolean shouldInhibitPrefetch() {
        return true;
    }

    @Override
    public String getDisassembly(AbstractMotorola cpu) {
        int op = cpu.getIr(), rx = (op >> 9) & 0x7, ry = op & 0x7, opmode = (op >> 3) & 0x1F;
        String rxStr = (opmode == 0x09) ? "A" + rx : "D" + rx;
        String ryStr = (opmode == 0x08) ? "D" + ry : "A" + ry;
        return "EXG " + rxStr + ", " + ryStr;
    }
}
