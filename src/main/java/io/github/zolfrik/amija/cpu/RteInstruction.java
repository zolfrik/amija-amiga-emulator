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
 * RTE: Return from Exception
 */
public class RteInstruction extends AbstractM68kInstruction {

    public static final RteInstruction INSTANCE = new RteInstruction();

    private RteInstruction() {}

    private static final int TARGET_HIGH = 0;
    private static final int SAVED_SR = 1;

    @Override
    public boolean shouldInhibitPrefetch() {
        return true;
    }

    @Override
    public boolean executeStep(Motorola68000 cpu) {
        if (getStep(cpu) == 0 && !cpu.isSupervisor()) {
            cpu.triggerPrivilegeViolation();
            return finalizeInstruction(cpu);
        }
        while (true) {
            if (cpu.isRisingEdgeConsumed()) return false;
            switch (getStep(cpu)) {
                case 0 -> {
                    // nU: Read PC High from SSP+2
                    cpu.scheduleReadWord(cpu.getA7() + 2, Motorola68000.OpPurpose.DATA_READ);
                    nextStep(cpu);
                    return false;
                }
                case 1 -> {
                    // nu: Read SR from SSP
                    setTemp(cpu, TARGET_HIGH, cpu.getLatchedBusData() & 0xFFFF);
                    cpu.scheduleReadWord(cpu.getA7(), Motorola68000.OpPurpose.DATA_READ);
                    nextStep(cpu);
                    return false;
                }
                case 2 -> {
                    // nu: Read PC Low from SSP+4
                    setTemp(cpu, SAVED_SR, cpu.getLatchedBusData() & 0xFFFF);
                    cpu.scheduleReadWord(cpu.getA7() + 4, Motorola68000.OpPurpose.DATA_READ);
                    nextStep(cpu);
                    return false;
                }
                case 3 -> {
                    // np: First prefetch refill
                    int low = cpu.getLatchedBusData() & 0xFFFF;
                    int sr = getTemp(cpu, SAVED_SR);
                    int targetPC = (getTemp(cpu, TARGET_HIGH) << 16) | low;

                    // Update A7 (SSP) BEFORE mode switch
                    cpu.setA7(cpu.getA7() + 6);

                    // Update SR (might switch mode and swap A7)
                    cpu.writeSR(sr, true);

                    // Update PC (this will flush prefetch)
                    cpu.setPC(targetPC);

                    if ((targetPC & 1) != 0) {
                        // For 68000, PC on stack for RTE/RTR/RTS returning to odd address is Target - 4
                        cpu.triggerAddressError(targetPC, false, cpu.currentFCForInstruction(), true, targetPC - 4);
                        return finalizeInstructionSafe(cpu);
                    }

                    cpu.startPrefetchCycle();
                    nextStep(cpu);
                    return false;
                }
                case 4 -> {
                    // np: Second prefetch refill
                    if (!cpu.isIRDValid()) {
                        cpu.startPrefetchCycle();
                        return false;
                    }
                    cpu.startPrefetchCycle();
                    return finalizeInstructionSafe(cpu);
                }
                default -> { return finalizeInstructionSafe(cpu); }
            }
        }
    }

    @Override
    public String getDisassembly(Motorola68000 cpu) { return "RTE"; }
}
