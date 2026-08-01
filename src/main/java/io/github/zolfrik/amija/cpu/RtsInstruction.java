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
 * RTS: Return from Subroutine
 */
public class RtsInstruction extends AbstractM68kInstruction {

    public static final RtsInstruction INSTANCE = new RtsInstruction();

    private RtsInstruction() {}

    private static final int TARGET_HIGH = 0;

    @Override
    public boolean shouldInhibitPrefetch() {
        return true;
    }

    @Override
    public boolean executeStep(Motorola68000 cpu) {
        while (true) {
            if (cpu.isRisingEdgeConsumed()) return false;
            switch (getStep(cpu)) {
                case 0 -> {
                    // nU: Read PC High from (A7)
                    cpu.scheduleReadWord(cpu.getA7(), Motorola68000.OpPurpose.DATA_READ);
                    nextStep(cpu);
                    return false;
                }
                case 1 -> {
                    // nu: Read PC Low from (A7+2)
                    setTemp(cpu, TARGET_HIGH, cpu.getLatchedBusData() & 0xFFFF);
                    cpu.setA7(cpu.getA7() + 2);
                    cpu.scheduleReadWord(cpu.getA7(), Motorola68000.OpPurpose.DATA_READ);
                    nextStep(cpu);
                    return false;
                }
                case 2 -> {
                    // np: First prefetch refill
                    int low = cpu.getLatchedBusData() & 0xFFFF;
                    cpu.setA7(cpu.getA7() + 2);
                    int targetPC = (getTemp(cpu, TARGET_HIGH) << 16) | low;

                    cpu.setPC(targetPC); // This flushes prefetch

                    if ((targetPC & 1) != 0) {
                        // For 68000, PC on stack for RTS/RTR/RTE returning to odd address is Target - 4
                        cpu.triggerAddressError(targetPC, false, cpu.currentFCForInstruction(), true, targetPC - 4);
                        return finalizeInstructionSafe(cpu);
                    }

                    refillPipeline(cpu); // np 1
                    nextStep(cpu);
                    return false;
                }
                case 3 -> {
                    // np: Second prefetch refill
                    if (!cpu.isBusCycleIdle()) return false;
                    refillPipeline(cpu); // np 2
                    return finalizeInstructionSafe(cpu);
                }
                default -> { return finalizeInstructionSafe(cpu); }
            }
        }
    }

    @Override
    public String getDisassembly(Motorola68000 cpu) { return "RTS"; }
}
