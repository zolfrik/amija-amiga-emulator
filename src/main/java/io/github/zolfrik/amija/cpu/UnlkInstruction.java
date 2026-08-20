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

public class UnlkInstruction extends AbstractM68kInstruction {
    public static final UnlkInstruction INSTANCE = new UnlkInstruction();
    private UnlkInstruction() {}

    private static final int TEMP_HIGH = 0;

    @Override
    public boolean shouldInhibitPrefetch() {
        return true;
    }

    @Override
    public boolean executeStep(Motorola68000 cpu) {
        switch (getStep(cpu)) {
            case 0 -> {
                int reg = cpu.getIr() & 0x7;
                int addr = cpu.getA(reg, 4);
                cpu.setA(7, addr); // SP = An

                if ((addr & 1) != 0) {
                    // Group 0 Address Error: pushed PC is opcodeAddress + 2
                    cpu.triggerAddressError(addr, false, cpu.currentFCForData(), false, cpu.getOpcodeAddress() + 2);
                    return false;
                }

                // WinUAE op_4e58_14_ff: `ipl_fetch_now()` sta prima della PRIMA delle due
                // word lette, a 12 clock dalla fine, non sulla seconda a 8.
                cpu.markIplAnchor(Motorola68000.IplAnchor.NOW);
                cpu.scheduleReadWord(addr, Motorola68000.OpPurpose.DATA_READ); // nU
                nextStep(cpu);
                return false;
            }
            case 1 -> {
                setTemp(cpu, TEMP_HIGH, cpu.getLatchedBusData() & 0xFFFF);
                cpu.scheduleReadWord(cpu.getA(7, 4) + 2, Motorola68000.OpPurpose.DATA_READ); // nu
                nextStep(cpu);
                return false;
            }
            case 2 -> {
                int low = cpu.getLatchedBusData() & 0xFFFF;
                int val = (getTemp(cpu, TEMP_HIGH) << 16) | (low & 0xFFFF);
                int reg = cpu.getIr() & 0x7;

                // SP = SP + 4 must happen before An = val to handle UNLK A7 correctly.
                // If An is A7, the loaded value should be the final one.
                cpu.setA(7, cpu.getA(7, 4) + 4);
                cpu.setA(reg, val);

                cpu.startPrefetchCycle(); // np
                return finalizeInstructionSafe(cpu);
            }
            default -> { return finalizeInstructionSafe(cpu); }
        }
    }

    @Override public String getDisassembly(Motorola68000 cpu) { return "UNLK A" + (cpu.getIr() & 0x7); }
}
