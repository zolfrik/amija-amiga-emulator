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
 * Implementation of the MOVE to SR instruction.
 */
public class MoveToSRInstruction extends AbstractM68kInstruction {

    public static final MoveToSRInstruction INSTANCE = new MoveToSRInstruction();
    private MoveToSRInstruction() {}

    private static final int EXT1 = 0;
    private static final int EXT2 = 1;
    private static final int SRC_VAL = 2;
    private static final int ADDR = 3;

    @Override
    public boolean shouldInhibitPrefetch() {
        return true;
    }

    @Override
    public boolean executeStep(Motorola68000 cpu) {
        if (getStep(cpu) == 0 && !cpu.isSupervisor()) {
            cpu.triggerPrivilegeViolation();
            return false;
        }

        int op = cpu.getIr();
        int mode = (op >> 3) & 0x7, reg = op & 0x7;

        while (true) {
            if (cpu.isRisingEdgeConsumed()) return false;

            switch (getStep(cpu)) {
                case 0 -> { // Privilege check + Initial gap
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
                case 3 -> { // Register or Immediate source
                    if (!cpu.isBusCycleIdle()) return false;
                    if (mode == 0) { // Dn
                        setTemp(cpu, SRC_VAL, cpu.getD(reg, 2));
                        cpu.idleCycles(4); // nn
                        setStep(cpu, 6);
                        return false;
                    } else if (mode == 7 && reg == 4) { // Imm
                        setTemp(cpu, SRC_VAL, getTemp(cpu, EXT1));
                        cpu.idleCycles(4); // nn
                        setStep(cpu, 6);
                        return false;
                    }
                    nextStep(cpu);
                    continue;
                }
                case 4 -> { // Memory source - Read
                    if (!cpu.isBusCycleIdle()) return false;
                    int addr = calculateEA(cpu, mode, reg, 2, cpu.getOpcodeAddress() + 2, getTemp(cpu, EXT1), getTemp(cpu, EXT2), true, false);
                    if (cpu.getCpuState() == Motorola68000.CpuState.EXCEPTION) return false;
                    setTemp(cpu, ADDR, addr);
                    cpu.scheduleRead(addr, 2); // nr
                    nextStep(cpu);
                    return false;
                }
                case 5 -> { // Latch memory data + gap nn
                    if (!cpu.isBusCycleIdle()) return false;
                    setTemp(cpu, SRC_VAL, cpu.getLatchedBusData() & 0xFFFF);
                    cpu.idleCycles(4); // nn
                    nextStep(cpu);
                    return false;
                }
                case 6 -> { // Update SR and refill 1
                    if (!cpu.isBusCycleIdle()) return false;
                    int newSrVal = getTemp(cpu, SRC_VAL);
                    cpu.writeSR(newSrVal, true);
                    if (cpu.getCpuState() == Motorola68000.CpuState.EXCEPTION) return false;
                    cpu.flushPrefetch();
                    cpu.setPC(cpu.getOpcodeAddress() + (cpu.extensionsConsumed * 2));
                    cpu.startPrefetchCycle(); // np 1
                    nextStep(cpu);
                    return false;
                }
                case 7 -> { // refill 2 + finalize (overlapped, seam 0, like MoveToCCR/Andi-to-CCR)
                    if (!cpu.isBusCycleIdle()) return false;
                    // MOVE to SR ($46D0): the `next` sample sits BETWEEN the two refill
                    // prefetches, not on the operand read.
                    cpu.markIplAnchor(Motorola68000.IplAnchor.NEXT);
                    cpu.startPrefetchCycle(); // np 2 (last bus cycle, overlaps the next dispatch)
                    return finalizeInstructionSafe(cpu);
                }
                default -> { return finalizeInstruction(cpu); }
            }
        }
    }

    @Override
    public String getDisassembly(Motorola68000 cpu) {
        int op = cpu.getIr();
        int sm = (op >> 3) & 7, sr = op & 7;
        return "MOVE " + Disassembly.formatEA(sm, sr, 2, cpu.extensionWordAt(cpu.getOpcodeAddress() + 2), cpu.extensionWordAt(cpu.getOpcodeAddress() + 4)) + ", SR";
    }
}

