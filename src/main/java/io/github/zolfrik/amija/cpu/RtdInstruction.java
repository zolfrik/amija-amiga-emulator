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
 * RTD #d16 (68010): return and deallocate. Pops the return address like RTS, then adds the
 * sign-extended displacement to the stack pointer. The displacement word is already in the
 * prefetch queue and is not fetched again. Same bus sequence as RTS.
 */
public class RtdInstruction extends AbstractM68kInstruction {

    public static final RtdInstruction INSTANCE = new RtdInstruction();

    private RtdInstruction() {}

    private static final int TARGET_HIGH = 0;
    private static final int DISPLACEMENT = 1;

    @Override
    public boolean shouldInhibitPrefetch() {
        return true;
    }

    @Override
    public boolean executeStep(AbstractMotorola cpu) {
        while (true) {
            if (cpu.isRisingEdgeConsumed()) return false;
            switch (getStep(cpu)) {
                case 0 -> {
                    setTemp(cpu, DISPLACEMENT, (short) cpu.getIRD());
                    cpu.scheduleReadWord(cpu.getA7(), AbstractMotorola.OpPurpose.DATA_READ);
                    nextStep(cpu);
                    return false;
                }
                case 1 -> {
                    setTemp(cpu, TARGET_HIGH, cpu.getLatchedBusData() & 0xFFFF);
                    cpu.scheduleReadWord(cpu.getA7() + 2, AbstractMotorola.OpPurpose.DATA_READ);
                    nextStep(cpu);
                    return false;
                }
                case 2 -> {
                    int low = cpu.getLatchedBusData() & 0xFFFF;
                    cpu.setA7(cpu.getA7() + 4 + getTemp(cpu, DISPLACEMENT));
                    int targetPC = (getTemp(cpu, TARGET_HIGH) << 16) | low;
                    cpu.setPC(targetPC);
                    if ((targetPC & 1) != 0) {
                        cpu.triggerAddressError(targetPC, false, cpu.currentFCForInstruction(), true, targetPC - 4);
                        return finalizeInstructionSafe(cpu);
                    }
                    refillPipeline(cpu);
                    nextStep(cpu);
                    return false;
                }
                case 3 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    cpu.markIplAnchor(AbstractMotorola.IplAnchor.NEXT);
                    refillPipeline(cpu);
                    return finalizeInstructionSafe(cpu);
                }
                default -> { return finalizeInstructionSafe(cpu); }
            }
        }
    }

    @Override
    public String getDisassembly(AbstractMotorola cpu) {
        return String.format("RTD #$%04X", cpu.extensionWordAt(cpu.getOpcodeAddress() + 2) & 0xFFFF);
    }
}
