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
 * MOVE An, USP
 */
public class MoveToUSPInstruction extends AbstractM68kInstruction {

    public static final MoveToUSPInstruction INSTANCE = new MoveToUSPInstruction(false);
    /** The 68010 form: 2 idle clocks before the prefetch. */
    public static final MoveToUSPInstruction M68010 = new MoveToUSPInstruction(true);

    private final boolean m68010;
    private MoveToUSPInstruction(boolean m68010) {
        this.m68010 = m68010;
    }

    @Override
    public boolean shouldInhibitPrefetch() {
        return true;
    }

    @Override
    public boolean executeStep(AbstractMotorola cpu) {
        if (getStep(cpu) == 0 && !cpu.isSupervisor()) {
            cpu.triggerPrivilegeViolation();
            return finalizeInstructionSafe(cpu);
        }
        switch (getStep(cpu)) {
            case 0 -> {
                if (m68010) {
                    cpu.idleCycles(2);
                    setStep(cpu, 2);
                    return false;
                }
                cpu.usp = cpu.getA(cpu.getIr() & 7, 4);
                refillPipeline(cpu); // np
                nextStep(cpu);
                return false;
            }
            case 1 -> { return finalizeInstructionSafe(cpu); }
            case 2 -> {
                cpu.usp = cpu.getA(cpu.getIr() & 7, 4);
                refillPipeline(cpu);
                return finalizeInstructionSafe(cpu);
            }
            default -> { return finalizeInstruction(cpu); }
        }
    }

    @Override
    public String getDisassembly(AbstractMotorola cpu) {
        return "MOVE A" + (cpu.getIr() & 7) + ", USP";
    }
}
