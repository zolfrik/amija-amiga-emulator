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
 * Cycle-exact implementation of the DIVS (Signed Divide) instruction for the
 * Motorola 68000. Refactored to match micro-architectural FSM and Yacht timing
 * patterns.
 */
public class DivsInstruction extends AbstractM68kInstruction {

    public static final DivsInstruction INSTANCE = new DivsInstruction();

    private DivsInstruction() {
    }

    private static final int EXT1 = 0;
    private static final int EXT2 = 1;
    private static final int SRC_VAL = 2;
    private static final int QUOT_RES = 3;
    private static final int REM_RES = 4;

    @Override
    public boolean shouldInhibitPrefetch() {
        return true;
    }

    @Override
    public void prepareInstruction(Motorola68000 cpu) {
        cpu.consumeIRC();
    }

    @Override
    public boolean executeStep(Motorola68000 cpu) {
        if (cpu.isInstructionFinished()) {
            return true;
        }

        int op = cpu.getIr();
        int reg = (op >> 9) & 7;
        int eaMode = (op >> 3) & 7;
        int eaReg = op & 7;

        while (true) {
            if (cpu.isRisingEdgeConsumed()) {
                return false;
            }
            switch (getStep(cpu)) {
                case 0 -> { // Initial Addressing Mode Gap (Rule 4a)
                    if (eaMode == 4 || eaMode == 6 || (eaMode == 7 && eaReg == 3)) {
                        indexedGap2(cpu);
                    }
                    nextStep(cpu);
                    continue;
                }
                case 1 -> { // Extension Fetch 1
                    if (hasExt1(eaMode, eaReg)) {
                        if (fetchExtension(cpu, EXT1)) {
                            nextStep(cpu);
                            return false;
                        }
                        return false;
                    }
                    nextStep(cpu);
                    continue;
                }
                case 2 -> { // Extension Fetch 2
                    if (hasExt2(eaMode, eaReg, 2)) {
                        if (fetchExtension(cpu, EXT2)) {
                            nextStep(cpu);
                            return false;
                        }
                        return false;
                    }
                    nextStep(cpu);
                    continue;
                }
                case 3 -> { // Operand Read
                    if (eaMode == 0) { // Dn
                        setTemp(cpu, SRC_VAL, cpu.getD(eaReg, 2) & 0xFFFF);
                        setStep(cpu, 5);
                        continue;
                    } else if (eaMode == 1) { // An (Illegal)
                        cpu.triggerIllegalInstruction();
                        return false;
                    } else if (eaMode == 7 && eaReg == 4) { // Immediate
                        setTemp(cpu, SRC_VAL, getTemp(cpu, EXT1));
                        setStep(cpu, 5);
                        continue;
                    } else { // Memory
                        int addr = calculateEA(cpu, eaMode, eaReg, 2, cpu.getOpcodeAddress() + 2, getTemp(cpu, EXT1), getTemp(cpu, EXT2), true, false);
                        cpu.scheduleRead(addr, 2);
                        nextStep(cpu);
                        return false;
                    }
                }
                case 4 -> { // Latch Operand
                    setTemp(cpu, SRC_VAL, cpu.getLatchedBusData() & 0xFFFF);
                    nextStep(cpu);
                    continue;
                }
                case 5 -> { // DIV_CHECK_ZERO
                    int divisorWord = getTemp(cpu, SRC_VAL) & 0xFFFF;
                    short sDivisor = (short) divisorWord;
                    if (sDivisor == 0) {
                        // Exception 5 (Divide by Zero) - Group 2
                        // Timing: 8-cycle sync gap (nn nn) then 38-cycle stacking
                        cpu.idleCycles(4); // First nn
                        // triggerException(5) adds the second nn (4 cycles) and handles the Group 2 PC push (opcodeAddress)
                        cpu.triggerException(5, cpu.getOpcodeAddress());
                        return false;
                    }
                    nextStep(cpu);
                    continue;
                }
                case 6 -> { // Main Logic and Timing Calculation
                    if (!cpu.isBusCycleIdle()) {
                        return false;
                    }

                    int divisorWord = getTemp(cpu, SRC_VAL) & 0xFFFF;
                    short sDivisor = (short) divisorWord;
                    int dividend = cpu.getD(reg, 4);

                    long absDividend = Math.abs((long) dividend);
                    long absDivisor = Math.abs((long) sDivisor);

                    int d_hi = (int) (absDividend >>> 16) & 0xFFFF;
                    int d_lo = (int) (absDividend & 0xFFFF);

                    // Trial Bit 15 Calculation (Non-restoring algorithm start)
                    int trial_tr = (d_hi << 1) | (d_lo >> 15);
                    boolean q15 = (trial_tr >= absDivisor);

                    long q = (long) dividend / (long) sDivisor;
                    int r = (int) (dividend % sDivisor);

                    boolean negQuot = (dividend < 0) ^ (sDivisor < 0);

                    // Overflow Check
                    boolean overflow = (d_hi >= absDivisor); // Early overflow
                    if (!overflow) {
                        if (negQuot) {
                            if (q < -32768 || (q == -32768 && r != 0)) {
                                overflow = true;
                            }
                        } else {
                            if (q > 32767) {
                                overflow = true;
                            }
                        }
                    }
                    // Quirk: bit 15 trial usually triggers overflow except for specific edge cases
                    if (!overflow && q15 && !(negQuot && q == -32768 && r == 0)) {
                        overflow = true;
                    }

                    if (overflow) {
                        // Flags on Overflow: Preserve Initial X, N, Z. Clear C. Set V.
                        int sr = (cpu.getSR() & ~0x03) | 0x02;
                        cpu.writeSR(sr, false);

                        // Overflow Timing: 12 or 14 cycles gap (matches Yacht n nn nn (n)n).
                        // Ordine np-LAST (idle interni, poi la prefetch terminale): è
                        // l'ordine dell'hardware, confermato da WinUAE (wait_cpu_cycle
                        // trace) e dai vettori Tom Harte (['n',N] prima della 'r' finale).
                        // Il vecchio ramo seamOverlap usava l'ordine np-first: contro
                        // WinUAE sfasava il marker delle bande contese (G1 DIVS1/DIVU1).
                        cpu.idleCycles(dividend < 0 ? 14 : 12);
                        setStep(cpu, 11);
                        return false;
                    }

                    // Success Path Timing Calculation (Based on exact M68K microcode timing)
                    int totalCycles = 122; // Base execution time

                    if (dividend < 0) {
                        totalCycles += 2;
                    }

                    if (sDivisor >= 0) {
                        if (dividend >= 0) {
                            totalCycles -= 2;
                        } else {
                            totalCycles += 2;
                        }
                    }

                    // Loop: 15 iterations checking the top bit of the absolute quotient
                    int aquot = (int) (Math.abs(q) & 0xFFFF);
                    for (int i = 0; i < 15; i++) {
                        if ((aquot & 0x8000) == 0) {
                            totalCycles += 2;
                        }
                        aquot <<= 1;
                    }

                    // The total execution time includes the 4-cycle instruction prefetch.
                    // Since the prefetch cycle is managed separately in our FSM, we subtract 4.
                    setTemp(cpu, QUOT_RES, (int) q);
                    setTemp(cpu, REM_RES, r);
                    // Ordine np-LAST (vedi commento nel ramo overflow): idle interni della
                    // divisione, poi la prefetch terminale in step 7.
                    cpu.idleCycles(totalCycles - 4);
                    nextStep(cpu);
                    return false;
                }
                case 7 -> { // Success Path Finalization
                    int q = getTemp(cpu, QUOT_RES);
                    int r = getTemp(cpu, REM_RES);
                    cpu.setD(reg, ((r & 0xFFFF) << 16) | (q & 0xFFFF), 4);

                    int sr = cpu.getSR() & 0xFFF0; // Clear N,Z,V,C. Keep X!
                    if ((q & 0x8000) != 0) {
                        sr |= 0x08;
                    }
                    if ((short) q == 0) {
                        sr |= 0x04;
                    }
                    cpu.writeSR(sr, false);

                    // Overlapped prefetch (Phase 4): the terminal np is the last bus cycle —
                    // finalize in this same step (seam 0) instead of a separate step 8. See
                    // docs/cpu-overlapped-prefetch-rework-plan.md §Phase 4.
                    cpu.startPrefetchCycle();
                    return finalizeInstructionSafe(cpu);
                }
                case 8 -> { // Success Path Finish
                    cpu.startPrefetchCycle();
                    return finalizeInstructionSafe(cpu);
                }
                case 11 -> { // Overflow Path Finish (overlapped: finalize with the np)
                    cpu.startPrefetchCycle();
                    return finalizeInstructionSafe(cpu);
                }
                case 12 -> { // Overflow Path Finalize
                    cpu.startPrefetchCycle();
                    return finalizeInstructionSafe(cpu);
                }
                default -> {
                    cpu.setInstructionFinished(true);
                    return true;
                }
            }
        }
    }

    @Override
    public String getDisassembly(Motorola68000 cpu) {
        int op = cpu.getIr();
        return "DIVS " + Disassembly.formatEA((op >> 3) & 7, op & 7, 2, cpu.extensionWordAt(cpu.getOpcodeAddress() + 2), cpu.extensionWordAt(cpu.getOpcodeAddress() + 4)) + ", D" + ((op >> 9) & 7);
    }
}

