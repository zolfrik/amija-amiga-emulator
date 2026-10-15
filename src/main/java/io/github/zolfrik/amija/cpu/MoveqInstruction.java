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
 * Implementation of the MOVEQ (Move Quick) instruction.
 */
public class MoveqInstruction extends AbstractM68kInstruction {

    public static final MoveqInstruction INSTANCE = new MoveqInstruction();
    private MoveqInstruction() {}

    @Override
    public boolean shouldInhibitPrefetch() {
        return true;
    }

    @Override
    public boolean executeStep(AbstractMotorola cpu) {
        // MOVEQ #<data>, Dn: 4(1/0) np
        int op = cpu.getIr();
        int dstReg = (op >> 9) & 0x7;
        int data = (byte) (op & 0xFF); // Sign-extended to 32 bits

        cpu.setD(dstReg, data, 4);

        int sr = cpu.getSR() & 0xFFF0; // Clear N, Z, V, C. Preserve X.
        if (data < 0) sr |= 0x08;   // N
        if (data == 0) sr |= 0x04;  // Z
        cpu.writeSR(sr, false);

        refillPipeline(cpu); // np
        return finalizeInstructionSafe(cpu);
    }

    @Override
    public String getDisassembly(AbstractMotorola cpu) {
        int op = cpu.getIr();
        return "MOVEQ #$" + Disassembly.formatHex(op & 0xFF, 2) + ", D" + ((op >> 9) & 7);
    }
}
