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
    public boolean executeStep(AbstractMotorola cpu) {
        switch (getStep(cpu)) {
            case 0 -> {
                // NO idle here. Group 1 exception entry already pays its 4 clocks in
                // processExceptionGroup1_2 case 0 (idleCycles(4)). An addInternalCycles(4) here
                // would count them TWICE: 38 clocks instead of the manual's 34, i.e. 2 CCK late
                // on EVERY ILLEGAL. Measured on vAmigaTS CPU/IPL/ILLEGAL1: the illex marker
                // started 8 px (= 2 CCK) to the right of the reference, with every marker width
                // already exact: the error was all in the entry. BKPT has no entry in the decode
                // table and goes through the currentInstruction==null branch, which triggers
                // without the double count.
                cpu.triggerException(4, cpu.getOpcodeAddress());
                nextStep(cpu);
                return false;
            }
            case 1 -> { return finalizeInstruction(cpu); }
            default -> { return finalizeInstruction(cpu); }
        }
    }

    @Override
    public String getDisassembly(AbstractMotorola cpu) {
        return "ILLEGAL";
    }
}
