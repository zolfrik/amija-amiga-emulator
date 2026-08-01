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
 * Implementation of the ILLEGAL instruction (opcode 0x4AFC).
 */
public class IllegalInstruction extends AbstractM68kInstruction {

    public static final IllegalInstruction INSTANCE = new IllegalInstruction();
    private IllegalInstruction() {}

    @Override
    public boolean executeStep(Motorola68000 cpu) {
        switch (getStep(cpu)) {
            case 0 -> {
                cpu.addInternalCycles(4);
                cpu.triggerException(4, cpu.getOpcodeAddress());
                nextStep(cpu);
                return false;
            }
            case 1 -> { return finalizeInstruction(cpu); }
            default -> { return finalizeInstruction(cpu); }
        }
    }

    @Override
    public String getDisassembly(Motorola68000 cpu) {
        return "ILLEGAL";
    }
}
