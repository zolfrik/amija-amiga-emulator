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
 * Implementation of the MOVE from SR instruction.
 */
public class MoveFromSRInstruction extends AbstractM68kInstruction {

    public static final MoveFromSRInstruction INSTANCE = new MoveFromSRInstruction();
    private MoveFromSRInstruction() {}

    private static final int EXT1 = 0;
    private static final int EXT2 = 1;
    private static final int ADDR = 2;

    @Override
    public boolean shouldInhibitPrefetch() {
        return true;
    }

    @Override
    public boolean executeStep(Motorola68000 cpu) {
        int op = cpu.getIr();
        int mode = (op >> 3) & 0x7, reg = op & 0x7;

        while (true) {
            if (cpu.isRisingEdgeConsumed()) return false;

            switch (getStep(cpu)) {
                case 0 -> { // Initial gap for -(An) or Indexed modes
                    if (mode == 4 || mode == 6 || (mode == 7 && reg == 3)) {
                        cpu.idleCycles(2);
                        nextStep(cpu);
                        return false;
                    }
                    nextStep(cpu);
                    continue;
                }
                case 1 -> { // Fetch Extension 1
                    if (hasExt1(mode, reg)) {
                        if (!fetchExtension(cpu, EXT1)) return false;
                    }
                    nextStep(cpu);
                    continue;
                }
                case 2 -> { // Fetch Extension 2
                    if (hasExt2(mode, reg, 2)) {
                        if (!fetchExtension(cpu, EXT2)) return false;
                    }
                    nextStep(cpu);
                    continue;
                }
                case 3 -> { // EA Read (nr) or Register move
                    if (mode == 0) { // Dn
                        cpu.setD(reg, cpu.getSR(), 2);
                        cpu.startPrefetchCycle(); // np
                        setStep(cpu, 6);
                        continue;
                    } else { // Memory destination
                        if (!cpu.isBusCycleIdle()) return false;
                        int addr = calculateEA(cpu, mode, reg, 2, cpu.getOpcodeAddress() + 2, getTemp(cpu, EXT1), getTemp(cpu, EXT2), true, false);
                        if (cpu.getCpuState() == Motorola68000.CpuState.EXCEPTION) return false;
                        setTemp(cpu, ADDR, addr);
                        cpu.scheduleRead(addr, 2); // nr (Read before Write)
                        nextStep(cpu);
                        return false;
                    }
                }
                case 4 -> { // Start np (Memory)
                    if (!cpu.isBusCycleIdle()) return false;
                    cpu.startPrefetchCycle(); // np
                    nextStep(cpu);
                    return false;
                }
                case 5 -> { // nw (Memory) + finalize — the write is the LAST bus cycle (seam 0)
                    if (!cpu.isBusCycleIdle()) return false;
                    cpu.scheduleWrite(getTemp(cpu, ADDR), cpu.getSR(), 2); // nw
                    return finalizeInstructionSafe(cpu);
                }
                case 6 -> { // finalize or idle gap for Dn
                    if (!cpu.isBusCycleIdle()) return false;
                    if (mode == 0) {
                        cpu.idleCycles(2); // n gap for Dn
                        nextStep(cpu);
                        return false;
                    }
                    return finalizeInstructionSafe(cpu);
                }
                case 7 -> { // Final step for Dn
                    if (!cpu.isBusCycleIdle()) return false;
                    return finalizeInstructionSafe(cpu);
                }
                default -> { return finalizeInstruction(cpu); }
            }
        }
    }

    @Override
    public String getDisassembly(Motorola68000 cpu) {
        int op = cpu.getIr();
        return "MOVE SR, " + Disassembly.formatEA((op >> 3) & 7, op & 7, 2, cpu.extensionWordAt(cpu.getOpcodeAddress() + 2), cpu.extensionWordAt(cpu.getOpcodeAddress() + 4));
    }
}

