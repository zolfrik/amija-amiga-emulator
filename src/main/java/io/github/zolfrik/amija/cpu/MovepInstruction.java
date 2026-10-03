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
 * Implementation of the MOVEP (Move Peripheral Data) instruction.
 */
public class MovepInstruction extends AbstractM68kInstruction {

    public static final MovepInstruction INSTANCE = new MovepInstruction();
    private MovepInstruction() {}

    private static final int DISP = 0;
    private static final int BASE = 1;
    private static final int DATA = 2;

    @Override
    public boolean shouldInhibitPrefetch() {
        return true;
    }

    @Override
    public boolean executeStep(Motorola68000 cpu) {
        int op = cpu.getIr();
        int dataReg = (op >> 9) & 0x7;
        boolean isLong = (op & 0x40) != 0;
        boolean regToMem = (op & 0x80) != 0;
        int addrReg = op & 0x7;

        while (true) {
            switch (getStep(cpu)) {
                case 0 -> {
                    if (!cpu.isIRDValid()) {
                        cpu.startPrefetchCycle();
                        return false;
                    }
                    setTemp(cpu, DISP, (short) cpu.getIRD());
                    cpu.consumeIRC();
                    cpu.startPrefetchCycle(); // Initial np
                    nextStep(cpu);
                    return false;
                }
                case 1 -> {
                    int base = cpu.getA(addrReg, 4) + getTemp(cpu, DISP);
                    setTemp(cpu, BASE, base);
                    if (regToMem) {
                        int data = cpu.getD(dataReg, 4);
                        cpu.scheduleWrite(base, (data >> (isLong ? 24 : 8)) & 0xFF, 1);
                    } else {
                        // Toward the register the anchor is before the byte pair of the LAST
                        // word: for .W it is this one ($0108, `now` 12 clocks from the end), for
                        // .L it is at step 3.
                        if (!isLong) {
                            cpu.markIplAnchor(Motorola68000.IplAnchor.NOW);
                        }
                        cpu.scheduleRead(base, 1);
                    }
                    nextStep(cpu);
                    return false;
                }
                case 2 -> {
                    int base = getTemp(cpu, BASE);
                    if (regToMem) {
                        int data = cpu.getD(dataReg, 4);
                        cpu.scheduleWrite(base + 2, (data >> (isLong ? 16 : 0)) & 0xFF, 1);
                    } else {
                        int b = cpu.getLatchedByte() & 0xFF;
                        setTemp(cpu, DATA, isLong ? (b << 24) : (b << 8));
                        cpu.scheduleRead(base + 2, 1);
                    }
                    nextStep(cpu);
                    return false;
                }
                case 3 -> {
                    if (!isLong) {
                        if (!regToMem) {
                            int b = cpu.getLatchedByte() & 0xFF;
                            cpu.setD(dataReg, getTemp(cpu, DATA) | b, 2);
                        }
                        setStep(cpu, 6);
                        continue;
                    }
                    int base = getTemp(cpu, BASE);
                    if (regToMem) {
                        int data = cpu.getD(dataReg, 4);
                        cpu.scheduleWrite(base + 4, (data >> 8) & 0xFF, 1);
                    } else {
                        int b = cpu.getLatchedByte() & 0xFF;
                        setTemp(cpu, DATA, getTemp(cpu, DATA) | (b << 16));
                        // .L toward the register: the pair of the last word starts here ($0148,
                        // again 12 clocks from the end).
                        cpu.markIplAnchor(Motorola68000.IplAnchor.NOW);
                        cpu.scheduleRead(base + 4, 1);
                    }
                    nextStep(cpu);
                    return false;
                }
                case 4 -> {
                    int base = getTemp(cpu, BASE);
                    if (regToMem) {
                        int data = cpu.getD(dataReg, 4);
                        cpu.scheduleWrite(base + 6, data & 0xFF, 1);
                    } else {
                        int b = cpu.getLatchedByte() & 0xFF;
                        setTemp(cpu, DATA, getTemp(cpu, DATA) | (b << 8));
                        cpu.scheduleRead(base + 6, 1);
                    }
                    nextStep(cpu);
                    return false;
                }
                case 5 -> {
                    if (!regToMem) {
                        int b = cpu.getLatchedByte() & 0xFF;
                        cpu.setD(dataReg, getTemp(cpu, DATA) | b, 4);
                    }
                    setStep(cpu, 6);
                    continue;
                }
                case 6 -> {
                    if (regToMem) {
                        // Toward memory the anchor is the final prefetch, `next` branch
                        // ($0188/$01C8: the sample comes AFTER all the writes), not the last
                        // write as the default would have it.
                        cpu.markIplAnchor(Motorola68000.IplAnchor.NEXT);
                    }
                    cpu.startPrefetchCycle(); // Final np: last bus cycle, finalize with it (seam 0)
                    return finalizeInstructionSafe(cpu);
                }
                default -> {
                    return finalizeInstructionSafe(cpu);
                }
            }
        }
    }

    @Override
    public String getDisassembly(Motorola68000 cpu) {
        int op = cpu.getIr();
        int dr = (op >> 9) & 0x7, il = (op & 0x40), rm = (op & 0x80), ar = op & 0x7;
        int disp = (short) cpu.extensionWordAt(cpu.getOpcodeAddress() + 2);
        String ea = "$" + Disassembly.formatHex(disp, 4) + "(A" + ar + ")";
        if (rm != 0) return "MOVEP" + (il != 0 ? ".l" : ".w") + " D" + dr + ", " + ea;
        else return "MOVEP" + (il != 0 ? ".l" : ".w") + " " + ea + ", D" + dr;
    }
}

