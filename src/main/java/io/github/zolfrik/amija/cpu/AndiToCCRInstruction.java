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
 * Implementation of the ANDI to CCR instruction.
 */
public class AndiToCCRInstruction extends AbstractM68kInstruction {

    public static final AndiToCCRInstruction INSTANCE = new AndiToCCRInstruction(false);
    /** The 68010 form: the immediate is taken from the prefetch queue without a fetch of its own. */
    public static final AndiToCCRInstruction M68010 = new AndiToCCRInstruction(true);

    private final boolean m68010;

    private AndiToCCRInstruction(boolean m68010) {
        this.m68010 = m68010;
    }

    private static final int IMM = 0;

    @Override
    public boolean executeStep(AbstractMotorola cpu) {
        while (true) {
            if (cpu.isRisingEdgeConsumed()) return false;
            switch (getStep(cpu)) {
                case 0 -> {
                    nextStep(cpu);
                    continue;
                }
                case 1 -> {
                    if (m68010) {
                        setTemp(cpu, IMM, cpu.getIRD() & 0xFFFF);
                    } else if (!fetchExtension(cpu, IMM)) return false;
                    nextStep(cpu);
                    continue;
                }
                case 2 -> {
                    if (!cpu.isBusCycleIdle()) return false;

                    int imm = getTemp(cpu, IMM);
                    int currentSR = cpu.getSR();
                    int result = (currentSR & 0xFF00) | ((currentSR & 0x00FF) & (imm & 0x00FF));
                    cpu.writeSR(result, false);

                    cpu.setPC(cpu.getOpcodeAddress() + 4);

                    cpu.idleCycles(8); // nn nn
                    nextStep(cpu);
                    return false;
                }
                case 3 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    cpu.startPrefetchCycle();
                    nextStep(cpu);
                    return false;
                }
                case 4 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    cpu.startPrefetchCycle();
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
        int imm = cpu.extensionWordAt(cpu.getOpcodeAddress() + 2) & 0xFF;
        return "ANDI #$" + Disassembly.formatHex(imm, 2) + ", CCR";
    }
}

