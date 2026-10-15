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

public class ResetInstruction extends AbstractM68kInstruction {
    public static final ResetInstruction INSTANCE = new ResetInstruction();
    private ResetInstruction() {}

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
        switch (getStep(cpu)) {
            case 0 -> {
                cpu.idleCycles(4); // nn
                nextStep(cpu);
                return false;
            }
            case 1 -> {
                cpu.cpuState = AbstractMotorola.CpuState.RESET;
                cpu.assertReset(123); // (??-)* -> 1 cycle (this one) + 123 skipped cycles = 124 total
                nextStep(cpu);
                return false;
            }
            case 2 -> {
                cpu.cpuState = AbstractMotorola.CpuState.NORMAL;
                refillPipeline(cpu); // np
                return finalizeInstructionSafe(cpu);
            }
            default -> {
                return finalizeInstruction(cpu);
            }
        }
    }

    @Override public String getDisassembly(AbstractMotorola cpu) { return "RESET"; }
}
