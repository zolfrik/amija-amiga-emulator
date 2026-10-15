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

public class SwapInstruction extends AbstractM68kInstruction {
    public static final SwapInstruction INSTANCE = new SwapInstruction();
    private SwapInstruction() {}
    @Override
    public boolean shouldInhibitPrefetch() {
        return true;
    }

    @Override
    public boolean executeStep(AbstractMotorola cpu) {
        // SWAP Dn: 4(1/0) np
        int reg = cpu.getIr() & 0x7;
        int val = cpu.getD(reg, 4);
        int swapped = (val << 16) | (val >>> 16);
        cpu.setD(reg, swapped, 4);

        int sr = cpu.getSR() & 0xFFF0; // Clear N, Z, V, C. Preserve X.
        if (swapped < 0) sr |= 0x08;   // N
        if (swapped == 0) sr |= 0x04;  // Z
        cpu.writeSR(sr, false);

        refillPipeline(cpu); // np
        return finalizeInstructionSafe(cpu);
    }
    @Override public String getDisassembly(AbstractMotorola cpu) { return "SWAP D" + (cpu.getIr() & 0x7); }
}
