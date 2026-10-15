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
 * RTE as the 68010 executes it: the frame carries a format/vector word.
 *
 * <p>Four reads: SR at SP, the format word at SP+6, PC high at SP+2, PC low at SP+4. Format 0
 * pops 8 bytes, format 8 (bus/address error) pops 58 without resuming the faulted cycle; any
 * other format raises a format error (vector 14) after the third read, with N and Z taken
 * from the format word and V cleared. Then the two refill prefetches, the IPL sampled
 * between them.
 */
public class Rte68010Instruction extends AbstractM68kInstruction {

    public static final Rte68010Instruction INSTANCE = new Rte68010Instruction();

    private Rte68010Instruction() {}

    private static final int FRAME = 0;
    private static final int SAVED_SR = 1;
    private static final int FORMAT = 2;
    private static final int TARGET_HIGH = 3;

    @Override
    public boolean shouldInhibitPrefetch() {
        return true;
    }

    @Override
    public boolean executeStep(AbstractMotorola cpu) {
        if (getStep(cpu) == 0 && !cpu.isSupervisor()) {
            cpu.triggerPrivilegeViolation();
            return finalizeInstruction(cpu);
        }
        while (true) {
            if (cpu.isRisingEdgeConsumed()) return false;
            switch (getStep(cpu)) {
                case 0 -> {
                    int a = cpu.getA7();
                    setTemp(cpu, FRAME, a);
                    cpu.scheduleReadWord(a, AbstractMotorola.OpPurpose.DATA_READ);
                    nextStep(cpu);
                    return false;
                }
                case 1 -> {
                    setTemp(cpu, SAVED_SR, cpu.getLatchedBusData() & 0xFFFF);
                    cpu.scheduleReadWord(getTemp(cpu, FRAME) + 6, AbstractMotorola.OpPurpose.DATA_READ);
                    nextStep(cpu);
                    return false;
                }
                case 2 -> {
                    setTemp(cpu, FORMAT, cpu.getLatchedBusData() & 0xFFFF);
                    cpu.scheduleReadWord(getTemp(cpu, FRAME) + 2, AbstractMotorola.OpPurpose.DATA_READ);
                    nextStep(cpu);
                    return false;
                }
                case 3 -> {
                    setTemp(cpu, TARGET_HIGH, cpu.getLatchedBusData() & 0xFFFF);
                    int format = getTemp(cpu, FORMAT);
                    int frameType = format >>> 12;
                    if (frameType == 0x0) {
                        cpu.setA7(getTemp(cpu, FRAME) + 8);
                    } else if (frameType == 0x8) {
                        cpu.setA7(getTemp(cpu, FRAME) + 58);
                    } else {
                        cpu.n = (format & 0x8000) != 0;
                        cpu.z = format == 0;
                        cpu.v = false;
                        cpu.triggerException(14, cpu.getOpcodeAddress());
                        return false;
                    }
                    cpu.scheduleReadWord(getTemp(cpu, FRAME) + 4, AbstractMotorola.OpPurpose.DATA_READ);
                    nextStep(cpu);
                    return false;
                }
                case 4 -> {
                    int low = cpu.getLatchedBusData() & 0xFFFF;
                    int targetPC = (getTemp(cpu, TARGET_HIGH) << 16) | low;
                    cpu.writeSR(getTemp(cpu, SAVED_SR), true);
                    cpu.setPC(targetPC);
                    if ((targetPC & 1) != 0) {
                        cpu.triggerAddressError(targetPC, false, cpu.currentFCForInstruction(), true, targetPC - 4);
                        return finalizeInstructionSafe(cpu);
                    }
                    cpu.startPrefetchCycle();
                    nextStep(cpu);
                    return false;
                }
                case 5 -> {
                    if (!cpu.isIRDValid()) {
                        cpu.startPrefetchCycle();
                        return false;
                    }
                    cpu.markIplAnchor(AbstractMotorola.IplAnchor.NEXT);
                    cpu.startPrefetchCycle();
                    return finalizeInstructionSafe(cpu);
                }
                default -> { return finalizeInstructionSafe(cpu); }
            }
        }
    }

    @Override
    public String getDisassembly(AbstractMotorola cpu) { return "RTE"; }
}
