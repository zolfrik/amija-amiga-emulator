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
 * EXT: Sign Extend
 */
public class ExtInstruction extends AbstractM68kInstruction {

    public static final ExtInstruction INSTANCE = new ExtInstruction();

    private ExtInstruction() {}

    @Override
    public void prepareInstruction(Motorola68000 cpu) {
        cpu.consumeIRC();
        // Inhibit automatic prefetch
    }

    @Override
    public boolean shouldInhibitPrefetch() {
        return true;
    }

    @Override
    public boolean executeStep(Motorola68000 cpu) {
        switch (getStep(cpu)) {
            case 0 -> {
                int op = cpu.getIr(), reg = op & 0x7, opmode = (op >> 6) & 0x7;
                int val = cpu.getD(reg, 4), result;
                if (opmode == 2) { // EXT.w
                    short s = (byte) (val & 0xFF); result = (val & 0xFFFF0000) | (s & 0xFFFF);
                    updateFlags(cpu, s, 2);
                } else { // EXT.l
                    result = (short) (val & 0xFFFF);
                    updateFlags(cpu, result, 4);
                }
                cpu.setD(reg, result, 4);
                refillPipeline(cpu); // np
                return finalizeInstruction(cpu);
            }
            default -> { return finalizeInstruction(cpu); }
        }
    }

    private void updateFlags(Motorola68000 cpu, int result, int size) {
        int sr = cpu.getSR() & 0xFFF0;
        if (size == 2) {
            if ((short) result < 0) sr |= 0x08; if ((short) result == 0) sr |= 0x04;
        } else {
            if (result < 0) sr |= 0x08; if (result == 0) sr |= 0x04;
        }
        cpu.writeSR(sr, false);
    }

    @Override
    public String getDisassembly(Motorola68000 cpu) {
        int op = cpu.getIr();
        return "EXT" + (((op >> 6) & 0x7) == 2 ? ".w" : ".l") + " D" + (op & 0x7);
    }
}

