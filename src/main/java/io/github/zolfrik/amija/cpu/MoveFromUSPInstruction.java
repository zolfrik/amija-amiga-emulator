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
 * MOVE USP, An
 */
public class MoveFromUSPInstruction extends AbstractM68kInstruction {

    public static final MoveFromUSPInstruction INSTANCE = new MoveFromUSPInstruction();
    private MoveFromUSPInstruction() {}

    @Override
    public boolean shouldInhibitPrefetch() {
        return true;
    }

    @Override
    public boolean executeStep(Motorola68000 cpu) {
        if (getStep(cpu) == 0 && !cpu.isSupervisor()) {
            cpu.triggerPrivilegeViolation();
            return finalizeInstructionSafe(cpu);
        }
        switch (getStep(cpu)) {
            case 0 -> {
                cpu.setA(cpu.getIr() & 7, cpu.usp);
                refillPipeline(cpu); // np
                nextStep(cpu);
                return false;
            }
            case 1 -> { return finalizeInstructionSafe(cpu); }
            default -> { return finalizeInstruction(cpu); }
        }
    }

    @Override
    public String getDisassembly(Motorola68000 cpu) {
        return "MOVE USP, A" + (cpu.getIr() & 7);
    }
}
