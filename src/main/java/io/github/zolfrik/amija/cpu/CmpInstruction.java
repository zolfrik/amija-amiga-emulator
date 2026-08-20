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
 * Implementation of the CMP instruction.
 */
public class CmpInstruction extends AbstractM68kInstruction {

    public static final CmpInstruction INSTANCE = new CmpInstruction();
    private CmpInstruction() {}

    private static final int EXT1 = 0;
    private static final int EXT2 = 1;
    private static final int SRC_VAL = 2;
    private static final int ADDR = 4;

    @Override
    public boolean shouldInhibitPrefetch() {
        return true;
    }

    @Override
    public boolean executeStep(Motorola68000 cpu) {
        int op = cpu.getIr();
        int reg = (op >> 9) & 0x7, opmode = (op >> 6) & 0x7, eaMode = (op >> 3) & 0x7, eaReg = op & 0x7;
        int size = (opmode == 0) ? 1 : (opmode == 1) ? 2 : 4;

        while (true) {
            if (cpu.isRisingEdgeConsumed()) return false;
            switch (getStep(cpu)) {
                case 0 -> {
                    // EA Initial Gap (n)
                    if (eaMode == 4 || eaMode == 6 || (eaMode == 7 && eaReg == 3)) {
                        cpu.idleCycles(2);
                        nextStep(cpu);
                        return false;
                    }
                    nextStep(cpu);
                }
                case 1 -> {
                    if (hasExt1(eaMode, eaReg)) {
                        if (!fetchExtension(cpu, EXT1)) return false;
                        nextStep(cpu);
                        return false; // Wait for np
                    }
                    nextStep(cpu);
                }
                case 2 -> {
                    if (hasExt2(eaMode, eaReg, size)) {
                        if (!fetchExtension(cpu, EXT2)) return false;
                        nextStep(cpu);
                        return false; // Wait for np
                    }
                    nextStep(cpu);
                }
                case 3 -> {
                    int dst = cpu.getD(reg, size);
                    if (eaMode == 0) {
                        calculateCmp(cpu, cpu.getD(eaReg, size), dst, size);
                        setStep(cpu, 10);
                    } else if (eaMode == 1) {
                        int src = cpu.getA(eaReg, 4);
                        if (size == 2) src = (short) src;
                        calculateCmp(cpu, src, dst, size);
                        setStep(cpu, 10);
                    } else if (eaMode == 7 && eaReg == 4) {
                        int val = (size == 4) ? (getTemp(cpu, EXT1) << 16) | (getTemp(cpu, EXT2) & 0xFFFF) : (size == 2 ? (short) getTemp(cpu, EXT1) : (byte) getTemp(cpu, EXT1));
                        calculateCmp(cpu, val, dst, size);
                        setStep(cpu, 10);
                    } else {
                        int addr = calculateEA(cpu, eaMode, eaReg, size, cpu.getOpcodeAddress() + 2, getTemp(cpu, EXT1), getTemp(cpu, EXT2), true, false);
                        setTemp(cpu, ADDR, addr);
                        if (size == 4) {
                            cpu.scheduleRead(addr, 2); // Read MSW (nR)
                        } else {
                            cpu.scheduleRead(addr, size); // Read .b or .w (nr)
                        }
                        nextStep(cpu);
                        return false;
                    }
                }
                case 4 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    if (size == 4) {
                        setTemp(cpu, SRC_VAL, cpu.getLatchedBusData() << 16);
                        cpu.scheduleRead(getTemp(cpu, ADDR) + 2, 2); // Read LSW (nr)
                        nextStep(cpu);
                        return false;
                    } else {
                        int src = (size == 1) ? cpu.getLatchedByte() : cpu.getLatchedBusData() & 0xFFFF;
                        calculateCmp(cpu, src, cpu.getD(reg, size), size);
                        setStep(cpu, 10);
                    }
                }
                case 5 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    int src = getTemp(cpu, SRC_VAL) | (cpu.getLatchedBusData() & 0xFFFF);
                    calculateCmp(cpu, src, cpu.getD(reg, size), size);
                    setStep(cpu, 10);
                }
                case 10 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    // WinUAE `case i_CMP`: `set_ipl_pre()` e' chiamata SOLO per la .L con
                    // destinazione Dn (`if (dmode == Dreg && size == sz_long)`), e sta subito
                    // prima di `fill_prefetch_next_after` — quindi li' l'ancora e' questa
                    // prefetch e non la lettura (op_b090..op_b0bb _14_ff). Per .b/.w gencpu
                    // usa `fill_prefetch_next_t()` senza `set_ipl_pre()`, cioe' il percorso di
                    // default: l'ancora resta la lettura, ed e' quello che facciamo gia'.
                    if (size == 4) {
                        cpu.markIplAnchor(Motorola68000.IplAnchor.NEXT);
                    }
                    refillPipeline(cpu); // np (the last bus cycle for .b/.w)
                    // Overlapped prefetch (Phase 2): finalize in the SAME step that issues the
                    // terminal np so the next instruction decodes the instant the np drains
                    // (seam 0). Deferring finalize to a later step wasted 1 mc. See
                    // docs/cpu-overlapped-prefetch-rework-plan.md §Phase 2.
                    if (size == 4) {
                        nextStep(cpu); // .L still needs the final 'n'
                        return false;
                    }
                    return finalizeInstructionSafe(cpu);
                }
                case 11 -> {
                    cpu.idleCycles(2); // Final 'n' for .L
                    nextStep(cpu);
                    return false;
                }
                case 12 -> {
                    return finalizeInstructionSafe(cpu);
                }
                default -> { return finalizeInstruction(cpu); }
            }
        }
    }

    @Override
    public String getDisassembly(Motorola68000 cpu) {
        int op = cpu.getIr();
        int reg = (op >> 9) & 0x7, opmode = (op >> 6) & 0x7, eaMode = (op >> 3) & 0x7, eaReg = op & 0x7;
        int size = (opmode == 0) ? 1 : (opmode == 1) ? 2 : 4;
        return "CMP" + Disassembly.getSizeSuffix(size) + " " + Disassembly.formatEA(eaMode, eaReg, size, cpu.extensionWordAt(cpu.getOpcodeAddress() + 2), cpu.extensionWordAt(cpu.getOpcodeAddress() + 4)) + ", D" + reg;
    }
}

