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
 * Implementation of the MOVE and MOVEA instructions.
 * Cycle-exact implementation matching yacht.txt and core requirements.
 * Refactored to fully isolate the execution flows of each instruction type
 * to prevent regression issues when adjusting specific sizes.
 */
public class MoveInstruction extends AbstractM68kInstruction {
    public static final MoveInstruction INSTANCE = new MoveInstruction();

    private MoveInstruction() {}

    private static final int SRC_EXT1 = 0;
    private static final int SRC_EXT2 = 1;
    private static final int DST_EXT1 = 2;
    private static final int DST_EXT2 = 3;
    private static final int SRC_EA = 4;
    private static final int DST_EA = 5;
    private static final int OPERAND = 6;

    @Override
    public boolean shouldInhibitPrefetch() {
        return true;
    }

    private int decodeSize(int opcode) {
        int s = (opcode >> 12) & 3;
        return switch (s) {
            case 1 -> 1; // .B
            case 2 -> 4; // .L
            case 3 -> 2; // .W
            default -> 2;
        };
    }

    @Override
    public void prepareInstruction(Motorola68000 cpu) {
        // MOVE consumes opcode word from pipeline.
        cpu.consumeIRC();
    }

    @Override
    public boolean executeStep(Motorola68000 cpu) {
        int opcode = cpu.getIr();
        int size = decodeSize(opcode);
        int srcMode = (opcode >> 3) & 7;
        int srcReg = opcode & 7;
        int destReg = (opcode >> 9) & 7;
        int destMode = (opcode >> 6) & 7;
        boolean isMovea = (destMode == 1);

        // Branching to fully isolated execution flows
        if (isMovea) {
            if (size == 2) {
                return executeMoveaW(cpu, srcMode, srcReg, destMode, destReg);
            } else {
                return executeMoveaL(cpu, srcMode, srcReg, destMode, destReg);
            }
        } else {
            if (size == 1) {
                return executeMoveB(cpu, srcMode, srcReg, destMode, destReg);
            } else if (size == 2) {
                return executeMoveW(cpu, srcMode, srcReg, destMode, destReg);
            } else {
                return executeMoveL(cpu, srcMode, srcReg, destMode, destReg);
            }
        }
    }

    // =========================================================================
    // ISOLATED FLOW: MOVEA.W (size = 2, isMovea = true)
    // =========================================================================
    private boolean executeMoveaW(Motorola68000 cpu, int srcMode, int srcReg, int destMode, int destReg) {
        while (true) {
            if (cpu.isRisingEdgeConsumed()) return false;
            int step = getStep(cpu);

            switch (step) {
                // --- PHASE 1: SOURCE EA ---
                case 0 -> {
                    if (srcMode == 4 || srcMode == 6 || (srcMode == 7 && srcReg == 3)) { cpu.idleCycles(2); nextStep(cpu); return false; }
                    nextStep(cpu);
                }
                case 1 -> {
                    if (hasExt1(srcMode, srcReg)) { cpu.startPrefetchCycle(); nextStep(cpu); return false; }
                    nextStep(cpu);
                }
                case 2 -> {
                    if (hasExt1(srcMode, srcReg)) {
                        if (!cpu.isIRDValid()) return false;
                        setTemp(cpu, SRC_EXT1, cpu.getIRD() & 0xFFFF);
                        cpu.consumeIRC();
                    }
                    nextStep(cpu);
                }
                case 3 -> {
                    if (hasExt2(srcMode, srcReg, 2)) { cpu.startPrefetchCycle(); nextStep(cpu); return false; }
                    nextStep(cpu);
                }
                case 4 -> {
                    if (hasExt2(srcMode, srcReg, 2)) {
                        if (!cpu.isIRDValid()) return false;
                        setTemp(cpu, SRC_EXT2, cpu.getIRD() & 0xFFFF);
                        cpu.consumeIRC();
                    }
                    nextStep(cpu);
                }

                // --- PHASE 2: SOURCE READ ---
                case 5 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    if (srcMode <= 1) {
                        int val = (srcMode == 0) ? cpu.getD(srcReg, 2) : cpu.getA(srcReg, 2);
                        setTemp(cpu, OPERAND, val); setStep(cpu, 10); continue;
                    } else if (srcMode == 7 && srcReg == 4) {
                        setTemp(cpu, OPERAND, getTemp(cpu, SRC_EXT1)); setStep(cpu, 10); continue;
                    } else {
                        int ea = calculateEA(cpu, srcMode, srcReg, 2, cpu.getOpcodeAddress() + 2, getTemp(cpu, SRC_EXT1), getTemp(cpu, SRC_EXT2), true, false);
                        setTemp(cpu, SRC_EA, ea);
                        cpu.scheduleRead(ea, 2);
                        setStep(cpu, 7);
                        return false;
                    }
                }
                case 7 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    setTemp(cpu, OPERAND, cpu.getLatchedBusData() & 0xFFFF);
                    setStep(cpu, 10);
                }

                // --- PHASE 3: DESTINATION EA ---
                case 10 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    if (destMode <= 1) { setStep(cpu, 20); continue; }
                    if (destMode == 6 || (destMode == 7 && destReg == 3)) { cpu.idleCycles(2); nextStep(cpu); return false; }
                    nextStep(cpu);
                }
                case 11 -> {
                    if (hasExt1(destMode, destReg)) { cpu.startPrefetchCycle(); nextStep(cpu); return false; }
                    nextStep(cpu);
                }
                case 12 -> {
                    if (hasExt1(destMode, destReg)) {
                        if (!cpu.isIRDValid()) return false;
                        setTemp(cpu, DST_EXT1, cpu.getIRD() & 0xFFFF); cpu.consumeIRC();
                    }
                    nextStep(cpu);
                }
                case 13 -> {
                    if (hasExt2(destMode, destReg, 2)) {
                        boolean srcIsRegOrImm = (srcMode <= 1) || (srcMode == 7 && srcReg == 4);
                        if (srcIsRegOrImm) { cpu.startPrefetchCycle(); nextStep(cpu); return false; }
                    }
                    nextStep(cpu);
                }
                case 14 -> {
                    if (hasExt2(destMode, destReg, 2)) {
                        if (!cpu.isIRDValid()) return false;
                        setTemp(cpu, DST_EXT2, cpu.getIRD() & 0xFFFF); cpu.consumeIRC();
                    }
                    nextStep(cpu);
                }

                // --- PHASE 4: EXECUTION (MEMORY) ---
                case 15 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    int val = (int)(short)(getTemp(cpu, OPERAND) & 0xFFFF); // Sign extend MOVEA.W
                    setTemp(cpu, OPERAND, val);

                    int sExts = (hasExt1(srcMode, srcReg) ? 1 : 0) + (hasExt2(srcMode, srcReg, 2) ? 1 : 0);
                    if (srcMode == 7 && srcReg == 4) sExts = 1;
                    int basePC = cpu.getOpcodeAddress() + 2 + (sExts * 2);

                    boolean updateNow = (destMode == 4);
                    int ea = calculateEA(cpu, destMode, destReg, 2, basePC, getTemp(cpu, DST_EXT1), getTemp(cpu, DST_EXT2), updateNow, false);
                    setTemp(cpu, DST_EA, ea);

                    if (destMode == 4) { refillPipeline(cpu); nextStep(cpu); return false; }
                    nextStep(cpu);
                }
                case 16 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    cpu.scheduleWrite(getTemp(cpu, DST_EA), getTemp(cpu, OPERAND), 2);
                    setStep(cpu, 18);
                    return false;
                }
                case 18 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    if (destMode == 3) cpu.setA(destReg, cpu.getA(destReg, 4) + 2);
                    if (destMode != 4) refillPipeline(cpu);
                    if (hasExt2(destMode, destReg, 2)) { nextStep(cpu); return false; }
                    return finalizeInstructionSafe(cpu);
                }
                case 19 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    refillPipeline(cpu);
                    return finalizeInstructionSafe(cpu);
                }

                // --- PHASE 4: REGISTER WRITE ---
                case 20 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    int val = (int) (short) (getTemp(cpu, OPERAND) & 0xFFFF);
                    cpu.setA(destReg, val);
                    refillPipeline(cpu);
                    return finalizeInstructionSafe(cpu);
                }
                case 21 -> { return finalizeInstructionSafe(cpu); }
                default -> { return finalizeInstructionSafe(cpu); }
            }
        }
    }

    // =========================================================================
    // ISOLATED FLOW: MOVEA.L (size = 4, isMovea = true)
    // =========================================================================
    private boolean executeMoveaL(Motorola68000 cpu, int srcMode, int srcReg, int destMode, int destReg) {
        while (true) {
            if (cpu.isRisingEdgeConsumed()) return false;
            int step = getStep(cpu);

            switch (step) {
                // --- PHASE 1: SOURCE EA ---
                case 0 -> {
                    if (srcMode == 4 || srcMode == 6 || (srcMode == 7 && srcReg == 3)) { cpu.idleCycles(2); nextStep(cpu); return false; }
                    nextStep(cpu);
                }
                case 1 -> {
                    if (hasExt1(srcMode, srcReg)) { cpu.startPrefetchCycle(); nextStep(cpu); return false; }
                    nextStep(cpu);
                }
                case 2 -> {
                    if (hasExt1(srcMode, srcReg)) {
                        if (!cpu.isIRDValid()) return false;
                        setTemp(cpu, SRC_EXT1, cpu.getIRD() & 0xFFFF); cpu.consumeIRC();
                    }
                    nextStep(cpu);
                }
                case 3 -> {
                    if (hasExt2(srcMode, srcReg, 4)) { cpu.startPrefetchCycle(); nextStep(cpu); return false; }
                    nextStep(cpu);
                }
                case 4 -> {
                    if (hasExt2(srcMode, srcReg, 4)) {
                        if (!cpu.isIRDValid()) return false;
                        setTemp(cpu, SRC_EXT2, cpu.getIRD() & 0xFFFF); cpu.consumeIRC();
                    }
                    nextStep(cpu);
                }

                // --- PHASE 2: SOURCE READ ---
                case 5 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    if (srcMode <= 1) {
                        int val = (srcMode == 0) ? cpu.getD(srcReg, 4) : cpu.getA(srcReg, 4);
                        setTemp(cpu, OPERAND, val); setStep(cpu, 10); continue;
                    } else if (srcMode == 7 && srcReg == 4) {
                        int v = (getTemp(cpu, SRC_EXT1) << 16) | (getTemp(cpu, SRC_EXT2) & 0xFFFF);
                        setTemp(cpu, OPERAND, v); setStep(cpu, 10); continue;
                    } else {
                        int ea = calculateEA(cpu, srcMode, srcReg, 4, cpu.getOpcodeAddress() + 2, getTemp(cpu, SRC_EXT1), getTemp(cpu, SRC_EXT2), true, false);
                        setTemp(cpu, SRC_EA, ea);
                        cpu.scheduleRead(ea, 2);
                        nextStep(cpu);
                        return false;
                    }
                }
                case 6 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    setTemp(cpu, OPERAND, (cpu.getLatchedBusData() & 0xFFFF) << 16);
                    cpu.scheduleRead(getTemp(cpu, SRC_EA) + 2, 2);
                    nextStep(cpu);
                    return false;
                }
                case 7 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    setTemp(cpu, OPERAND, getTemp(cpu, OPERAND) | (cpu.getLatchedBusData() & 0xFFFF));
                    setStep(cpu, 10);
                }

                // --- PHASE 3: DESTINATION EA ---
                case 10 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    if (destMode <= 1) { setStep(cpu, 20); continue; }
                    if (destMode == 6 || (destMode == 7 && destReg == 3)) { cpu.idleCycles(2); nextStep(cpu); return false; }
                    nextStep(cpu);
                }
                case 11 -> {
                    if (hasExt1(destMode, destReg)) { cpu.startPrefetchCycle(); nextStep(cpu); return false; }
                    nextStep(cpu);
                }
                case 12 -> {
                    if (hasExt1(destMode, destReg)) {
                        if (!cpu.isIRDValid()) return false;
                        setTemp(cpu, DST_EXT1, cpu.getIRD() & 0xFFFF); cpu.consumeIRC();
                    }
                    nextStep(cpu);
                }
                case 13 -> {
                    if (hasExt2(destMode, destReg, 4)) {
                        boolean srcIsRegOrImm = (srcMode <= 1) || (srcMode == 7 && srcReg == 4);
                        if (srcIsRegOrImm) { cpu.startPrefetchCycle(); nextStep(cpu); return false; }
                    }
                    nextStep(cpu);
                }
                case 14 -> {
                    if (hasExt2(destMode, destReg, 4)) {
                        if (!cpu.isIRDValid()) return false;
                        setTemp(cpu, DST_EXT2, cpu.getIRD() & 0xFFFF); cpu.consumeIRC();
                    }
                    nextStep(cpu);
                }

                // --- PHASE 4: EXECUTION (MEMORY) ---
                case 15 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    int sExts = (hasExt1(srcMode, srcReg) ? 1 : 0) + (hasExt2(srcMode, srcReg, 4) ? 1 : 0);
                    if (srcMode == 7 && srcReg == 4) sExts = 2;
                    int basePC = cpu.getOpcodeAddress() + 2 + (sExts * 2);

                    boolean updateNow = (destMode == 4);
                    int ea = calculateEA(cpu, destMode, destReg, 4, basePC, getTemp(cpu, DST_EXT1), getTemp(cpu, DST_EXT2), updateNow, false);
                    setTemp(cpu, DST_EA, ea);

                    if (destMode == 4) {
                        cpu.setGroup0PushedPC(cpu.getOpcodeAddress() + (cpu.extensionsConsumed * 2));
                        cpu.startPrefetchCycle();
                        nextStep(cpu);
                        return false;
                    }
                    boolean srcIsRegOrImm = (srcMode <= 1) || (srcMode == 7 && srcReg == 4);
                    if (destMode == 7 && destReg == 1 && !srcIsRegOrImm) {
                        cpu.setGroup0PushedPC(basePC);
                    }
                    nextStep(cpu);
                }
                case 16 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    int val = getTemp(cpu, OPERAND);
                    int ea = getTemp(cpu, DST_EA);
                    if (destMode == 4) { cpu.scheduleWrite(ea + 2, val & 0xFFFF, 2); }
                    else { cpu.scheduleWrite(ea, (val >> 16) & 0xFFFF, 2); }
                    nextStep(cpu);
                    return false;
                }
                case 17 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    int val = getTemp(cpu, OPERAND);
                    int ea = getTemp(cpu, DST_EA);
                    if (destMode == 4) { cpu.scheduleWrite(ea, (val >> 16) & 0xFFFF, 2); }
                    else { cpu.scheduleWrite(ea + 2, val & 0xFFFF, 2); }
                    nextStep(cpu);
                    return false;
                }
                case 18 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    if (destMode == 3) cpu.setA(destReg, cpu.getA(destReg, 4) + 4);
                    if (destMode != 4) {
                        cpu.setGroup0PushedPC(cpu.getOpcodeAddress() + (cpu.extensionsConsumed * 2));
                        cpu.startPrefetchCycle();
                    }
                    if (hasExt2(destMode, destReg, 4)) { nextStep(cpu); return false; }
                    return finalizeInstructionSafe(cpu);
                }
                case 19 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    cpu.setGroup0PushedPC(cpu.getOpcodeAddress() + (cpu.extensionsConsumed * 2));
                    cpu.startPrefetchCycle();
                    return finalizeInstructionSafe(cpu);
                }

                // --- PHASE 4: REGISTER WRITE ---
                case 20 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    cpu.setA(destReg, getTemp(cpu, OPERAND));
                    cpu.setGroup0PushedPC(cpu.getOpcodeAddress() + (cpu.extensionsConsumed * 2));
                    refillPipeline(cpu);
                    nextStep(cpu);
                    return false;
                }
                case 21 -> { return finalizeInstructionSafe(cpu); }
                default -> { return finalizeInstructionSafe(cpu); }
            }
        }
    }

    // =========================================================================
    // ISOLATED FLOW: MOVE.B (size = 1, isMovea = false)
    // =========================================================================
    private boolean executeMoveB(Motorola68000 cpu, int srcMode, int srcReg, int destMode, int destReg) {
        while (true) {
            if (cpu.isRisingEdgeConsumed()) return false;
            int step = getStep(cpu);

            switch (step) {
                // --- PHASE 1: SOURCE EA ---
                case 0 -> {
                    if (srcMode == 4 || srcMode == 6 || (srcMode == 7 && srcReg == 3)) { cpu.idleCycles(2); nextStep(cpu); return false; }
                    nextStep(cpu);
                }
                case 1 -> {
                    if (hasExt1(srcMode, srcReg)) { cpu.startPrefetchCycle(); nextStep(cpu); return false; }
                    nextStep(cpu);
                }
                case 2 -> {
                    if (hasExt1(srcMode, srcReg)) {
                        if (!cpu.isIRDValid()) return false;
                        setTemp(cpu, SRC_EXT1, cpu.getIRD() & 0xFFFF); cpu.consumeIRC();
                    }
                    nextStep(cpu);
                }
                case 3 -> {
                    if (hasExt2(srcMode, srcReg, 1)) { cpu.startPrefetchCycle(); nextStep(cpu); return false; }
                    nextStep(cpu);
                }
                case 4 -> {
                    if (hasExt2(srcMode, srcReg, 1)) {
                        if (!cpu.isIRDValid()) return false;
                        setTemp(cpu, SRC_EXT2, cpu.getIRD() & 0xFFFF); cpu.consumeIRC();
                    }
                    nextStep(cpu);
                }

                // --- PHASE 2: SOURCE READ ---
                case 5 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    if (srcMode <= 1) {
                        int val = (srcMode == 0) ? cpu.getD(srcReg, 1) : cpu.getA(srcReg, 1);
                        setTemp(cpu, OPERAND, val); setStep(cpu, 10); continue;
                    } else if (srcMode == 7 && srcReg == 4) {
                        setTemp(cpu, OPERAND, getTemp(cpu, SRC_EXT1)); setStep(cpu, 10); continue;
                    } else {
                        int ea = calculateEA(cpu, srcMode, srcReg, 1, cpu.getOpcodeAddress() + 2, getTemp(cpu, SRC_EXT1), getTemp(cpu, SRC_EXT2), true, false);
                        setTemp(cpu, SRC_EA, ea);
                        cpu.scheduleRead(ea, 1);
                        setStep(cpu, 7);
                        return false;
                    }
                }
                case 7 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    setTemp(cpu, OPERAND, cpu.getLatchedByte() & 0xFF);
                    setStep(cpu, 10);
                }

                // --- PHASE 3: DESTINATION EA ---
                case 10 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    if (destMode <= 1) { setStep(cpu, 20); continue; }
                    if (destMode == 6 || (destMode == 7 && destReg == 3)) { cpu.idleCycles(2); nextStep(cpu); return false; }
                    nextStep(cpu);
                }
                case 11 -> {
                    if (hasExt1(destMode, destReg)) { cpu.startPrefetchCycle(); nextStep(cpu); return false; }
                    nextStep(cpu);
                }
                case 12 -> {
                    if (hasExt1(destMode, destReg)) {
                        if (!cpu.isIRDValid()) return false;
                        setTemp(cpu, DST_EXT1, cpu.getIRD() & 0xFFFF); cpu.consumeIRC();
                    }
                    nextStep(cpu);
                }
                case 13 -> {
                    if (hasExt2(destMode, destReg, 1)) {
                        boolean srcIsRegOrImm = (srcMode <= 1) || (srcMode == 7 && srcReg == 4);
                        if (srcIsRegOrImm) { cpu.startPrefetchCycle(); nextStep(cpu); return false; }
                    }
                    nextStep(cpu);
                }
                case 14 -> {
                    if (hasExt2(destMode, destReg, 1)) {
                        if (!cpu.isIRDValid()) return false;
                        setTemp(cpu, DST_EXT2, cpu.getIRD() & 0xFFFF); cpu.consumeIRC();
                    }
                    nextStep(cpu);
                }

                // --- PHASE 4: EXECUTION (MEMORY) ---
                case 15 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    int val = getTemp(cpu, OPERAND);
                    calculateAndSetFlags(cpu, val, 1);

                    int sExts = (hasExt1(srcMode, srcReg) ? 1 : 0) + (hasExt2(srcMode, srcReg, 1) ? 1 : 0);
                    if (srcMode == 7 && srcReg == 4) sExts = 1;
                    int basePC = cpu.getOpcodeAddress() + 2 + (sExts * 2);

                    boolean updateNow = (destMode == 4);
                    int ea = calculateEA(cpu, destMode, destReg, 1, basePC, getTemp(cpu, DST_EXT1), getTemp(cpu, DST_EXT2), updateNow, false);
                    setTemp(cpu, DST_EA, ea);

                    if (destMode == 4) { refillPipeline(cpu); nextStep(cpu); return false; }
                    nextStep(cpu);
                }
                case 16 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    cpu.scheduleWrite(getTemp(cpu, DST_EA), getTemp(cpu, OPERAND), 1);
                    setStep(cpu, 18);
                    return false;
                }
                case 18 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    if (destMode == 3) {
                        int inc = (destReg == 7) ? 2 : 1;
                        cpu.setA(destReg, cpu.getA(destReg, 4) + inc);
                    }
                    if (destMode != 4) refillPipeline(cpu);
                    if (hasExt2(destMode, destReg, 1)) { nextStep(cpu); return false; }
                    return finalizeInstructionSafe(cpu);
                }
                case 19 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    refillPipeline(cpu);
                    return finalizeInstructionSafe(cpu);
                }

                // --- PHASE 4: REGISTER WRITE ---
                case 20 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    int val = getTemp(cpu, OPERAND);
                    calculateAndSetFlags(cpu, val, 1);
                    cpu.setD(destReg, val, 1);
                    refillPipeline(cpu);
                    return finalizeInstructionSafe(cpu);
                }
                case 21 -> { return finalizeInstructionSafe(cpu); }
                default -> { return finalizeInstructionSafe(cpu); }
            }
        }
    }

    // =========================================================================
    // ISOLATED FLOW: MOVE.W (size = 2, isMovea = false)
    // =========================================================================
    private boolean executeMoveW(Motorola68000 cpu, int srcMode, int srcReg, int destMode, int destReg) {
        while (true) {
            if (cpu.isRisingEdgeConsumed()) return false;
            int step = getStep(cpu);

            switch (step) {
                // --- PHASE 1: SOURCE EA ---
                case 0 -> {
                    if (srcMode == 4 || srcMode == 6 || (srcMode == 7 && srcReg == 3)) { cpu.idleCycles(2); nextStep(cpu); return false; }
                    nextStep(cpu);
                }
                case 1 -> {
                    if (hasExt1(srcMode, srcReg)) { cpu.startPrefetchCycle(); nextStep(cpu); return false; }
                    nextStep(cpu);
                }
                case 2 -> {
                    if (hasExt1(srcMode, srcReg)) {
                        if (!cpu.isIRDValid()) return false;
                        setTemp(cpu, SRC_EXT1, cpu.getIRD() & 0xFFFF); cpu.consumeIRC();
                    }
                    nextStep(cpu);
                }
                case 3 -> {
                    if (hasExt2(srcMode, srcReg, 2)) { cpu.startPrefetchCycle(); nextStep(cpu); return false; }
                    nextStep(cpu);
                }
                case 4 -> {
                    if (hasExt2(srcMode, srcReg, 2)) {
                        if (!cpu.isIRDValid()) return false;
                        setTemp(cpu, SRC_EXT2, cpu.getIRD() & 0xFFFF); cpu.consumeIRC();
                    }
                    nextStep(cpu);
                }

                // --- PHASE 2: SOURCE READ ---
                case 5 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    if (srcMode <= 1) {
                        int val = (srcMode == 0) ? cpu.getD(srcReg, 2) : cpu.getA(srcReg, 2);
                        setTemp(cpu, OPERAND, val); setStep(cpu, 10); continue;
                    } else if (srcMode == 7 && srcReg == 4) {
                        setTemp(cpu, OPERAND, getTemp(cpu, SRC_EXT1)); setStep(cpu, 10); continue;
                    } else {
                        int ea = calculateEA(cpu, srcMode, srcReg, 2, cpu.getOpcodeAddress() + 2, getTemp(cpu, SRC_EXT1), getTemp(cpu, SRC_EXT2), true, false);
                        setTemp(cpu, SRC_EA, ea);
                        cpu.scheduleRead(ea, 2);
                        setStep(cpu, 7);
                        return false;
                    }
                }
                case 7 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    setTemp(cpu, OPERAND, cpu.getLatchedBusData() & 0xFFFF);
                    setStep(cpu, 10);
                }

                // --- PHASE 3: DESTINATION EA ---
                case 10 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    if (destMode <= 1) { setStep(cpu, 20); continue; }
                    if (destMode == 6 || (destMode == 7 && destReg == 3)) { cpu.idleCycles(2); nextStep(cpu); return false; }
                    nextStep(cpu);
                }
                case 11 -> {
                    if (hasExt1(destMode, destReg)) { cpu.startPrefetchCycle(); nextStep(cpu); return false; }
                    nextStep(cpu);
                }
                case 12 -> {
                    if (hasExt1(destMode, destReg)) {
                        if (!cpu.isIRDValid()) return false;
                        setTemp(cpu, DST_EXT1, cpu.getIRD() & 0xFFFF); cpu.consumeIRC();
                    }
                    nextStep(cpu);
                }
                case 13 -> {
                    if (hasExt2(destMode, destReg, 2)) {
                        boolean srcIsRegOrImm = (srcMode <= 1) || (srcMode == 7 && srcReg == 4);
                        if (srcIsRegOrImm) { cpu.startPrefetchCycle(); nextStep(cpu); return false; }
                    }
                    nextStep(cpu);
                }
                case 14 -> {
                    if (hasExt2(destMode, destReg, 2)) {
                        if (!cpu.isIRDValid()) return false;
                        setTemp(cpu, DST_EXT2, cpu.getIRD() & 0xFFFF); cpu.consumeIRC();
                    }
                    nextStep(cpu);
                }

                // --- PHASE 4: EXECUTION (MEMORY) ---
                case 15 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    int val = getTemp(cpu, OPERAND);
                    calculateAndSetFlags(cpu, val, 2);

                    int sExts = (hasExt1(srcMode, srcReg) ? 1 : 0) + (hasExt2(srcMode, srcReg, 2) ? 1 : 0);
                    if (srcMode == 7 && srcReg == 4) sExts = 1;
                    int basePC = cpu.getOpcodeAddress() + 2 + (sExts * 2);

                    boolean updateNow = (destMode == 4);
                    int ea = calculateEA(cpu, destMode, destReg, 2, basePC, getTemp(cpu, DST_EXT1), getTemp(cpu, DST_EXT2), updateNow, false);
                    setTemp(cpu, DST_EA, ea);

                    if (destMode == 4) {
                        cpu.setGroup0PushedPC(cpu.getOpcodeAddress() + (cpu.extensionsConsumed * 2));
                        cpu.startPrefetchCycle();
                        nextStep(cpu);
                        return false;
                    }
                    boolean srcIsRegOrImm = (srcMode <= 1) || (srcMode == 7 && srcReg == 4);
                    if (destMode == 7 && destReg == 1 && !srcIsRegOrImm) {
                        cpu.setGroup0PushedPC(basePC);
                    }
                    nextStep(cpu);
                }
                case 16 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    cpu.scheduleWrite(getTemp(cpu, DST_EA), getTemp(cpu, OPERAND), 2);
                    setStep(cpu, 18);
                    return false;
                }
                case 18 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    if (destMode == 3) cpu.setA(destReg, cpu.getA(destReg, 4) + 2);
                    if (destMode != 4) {
                        cpu.setGroup0PushedPC(cpu.getOpcodeAddress() + (cpu.extensionsConsumed * 2));
                        cpu.startPrefetchCycle();
                    }
                    if (hasExt2(destMode, destReg, 2)) { nextStep(cpu); return false; }
                    return finalizeInstructionSafe(cpu);
                }
                case 19 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    cpu.setGroup0PushedPC(cpu.getOpcodeAddress() + (cpu.extensionsConsumed * 2));
                    cpu.startPrefetchCycle();
                    return finalizeInstructionSafe(cpu);
                }

                // --- PHASE 4: REGISTER WRITE ---
                case 20 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    int val = getTemp(cpu, OPERAND);
                    calculateAndSetFlags(cpu, val, 2);
                    cpu.setD(destReg, val, 2);
                    cpu.setGroup0PushedPC(cpu.getOpcodeAddress() + (cpu.extensionsConsumed * 2));
                    cpu.startPrefetchCycle();
                    return finalizeInstructionSafe(cpu);
                }
                case 21 -> { return finalizeInstructionSafe(cpu); }
                default -> { return finalizeInstructionSafe(cpu); }
            }
        }
    }

    // =========================================================================
    // ISOLATED FLOW: MOVE.L (size = 4, isMovea = false)
    //
    // Memory write phase: the two word writes are ALWAYS back-to-back with no
    // intermediate prefetch between them. A single prefetch is issued only after
    // the second (and final) write, mirroring the MOVE.W post-write prefetch.
    //
    // Step layout for non-predecrement memory destinations:
    //   15: calc EA + flags
    //   16: write MSW at EA
    //   17: write LSW at EA+2
    //   18: post-increment An if (An)+; setGroup0PushedPC; startPrefetchCycle;
    //       if hasExt2 -> 22, else finalize
    //   22: abs.l extra prefetch step (mirrors MOVE.W case 19)
    //
    // Step layout for predecrement (-(An)):
    //   15: setGroup0PushedPC + startPrefetchCycle (pre-write prefetch, mirrors MOVE.W)
    //   16: write LSW at EA+2
    //   17: write MSW at EA
    //   18: finalize
    // =========================================================================
    private boolean executeMoveL(Motorola68000 cpu, int srcMode, int srcReg, int destMode, int destReg) {
        while (true) {
            if (cpu.isRisingEdgeConsumed()) return false;
            int step = getStep(cpu);

            switch (step) {
                // --- PHASE 1: SOURCE EA ---
                case 0 -> {
                    if (srcMode == 4 || srcMode == 6 || (srcMode == 7 && srcReg == 3)) { cpu.idleCycles(2); nextStep(cpu); return false; }
                    nextStep(cpu);
                }
                case 1 -> {
                    if (hasExt1(srcMode, srcReg)) { cpu.startPrefetchCycle(); nextStep(cpu); return false; }
                    nextStep(cpu);
                }
                case 2 -> {
                    if (hasExt1(srcMode, srcReg)) {
                        if (!cpu.isIRDValid()) return false;
                        setTemp(cpu, SRC_EXT1, cpu.getIRD() & 0xFFFF); cpu.consumeIRC();
                    }
                    nextStep(cpu);
                }
                case 3 -> {
                    if (hasExt2(srcMode, srcReg, 4)) { cpu.startPrefetchCycle(); nextStep(cpu); return false; }
                    nextStep(cpu);
                }
                case 4 -> {
                    if (hasExt2(srcMode, srcReg, 4)) {
                        if (!cpu.isIRDValid()) return false;
                        setTemp(cpu, SRC_EXT2, cpu.getIRD() & 0xFFFF); cpu.consumeIRC();
                    }
                    nextStep(cpu);
                }

                // --- PHASE 2: SOURCE READ ---
                case 5 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    if (srcMode <= 1) {
                        int val = (srcMode == 0) ? cpu.getD(srcReg, 4) : cpu.getA(srcReg, 4);
                        setTemp(cpu, OPERAND, val); setStep(cpu, 10); continue;
                    } else if (srcMode == 7 && srcReg == 4) {
                        int v = (getTemp(cpu, SRC_EXT1) << 16) | (getTemp(cpu, SRC_EXT2) & 0xFFFF);
                        setTemp(cpu, OPERAND, v); setStep(cpu, 10); continue;
                    } else {
                        int ea = calculateEA(cpu, srcMode, srcReg, 4, cpu.getOpcodeAddress() + 2, getTemp(cpu, SRC_EXT1), getTemp(cpu, SRC_EXT2), true, false);
                        setTemp(cpu, SRC_EA, ea);
                        cpu.scheduleRead(ea, 2);
                        nextStep(cpu);
                        return false;
                    }
                }
                case 6 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    setTemp(cpu, OPERAND, (cpu.getLatchedBusData() & 0xFFFF) << 16);
                    cpu.scheduleRead(getTemp(cpu, SRC_EA) + 2, 2);
                    nextStep(cpu);
                    return false;
                }
                case 7 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    setTemp(cpu, OPERAND, getTemp(cpu, OPERAND) | (cpu.getLatchedBusData() & 0xFFFF));
                    setStep(cpu, 10);
                }

                // --- PHASE 3: DESTINATION EA ---
                case 10 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    if (destMode <= 1) { setStep(cpu, 20); continue; }
                    if (destMode == 6 || (destMode == 7 && destReg == 3)) { cpu.idleCycles(2); nextStep(cpu); return false; }
                    nextStep(cpu);
                }
                case 11 -> {
                    if (hasExt1(destMode, destReg)) { cpu.startPrefetchCycle(); nextStep(cpu); return false; }
                    nextStep(cpu);
                }
                case 12 -> {
                    if (hasExt1(destMode, destReg)) {
                        if (!cpu.isIRDValid()) return false;
                        setTemp(cpu, DST_EXT1, cpu.getIRD() & 0xFFFF); cpu.consumeIRC();
                    }
                    nextStep(cpu);
                }
                case 13 -> {
                    if (hasExt2(destMode, destReg, 4)) {
                        boolean srcIsRegOrImm = (srcMode <= 1) || (srcMode == 7 && srcReg == 4);
                        if (srcIsRegOrImm) { cpu.startPrefetchCycle(); nextStep(cpu); return false; }
                    }
                    nextStep(cpu);
                }
                case 14 -> {
                    if (hasExt2(destMode, destReg, 4)) {
                        if (!cpu.isIRDValid()) return false;
                        setTemp(cpu, DST_EXT2, cpu.getIRD() & 0xFFFF); cpu.consumeIRC();
                    }
                    nextStep(cpu);
                }

                // --- PHASE 4: EXECUTION (MEMORY) ---

                // Calculate destination EA and flags.
                // For predecrement: fire the pre-write prefetch here (mirrors MOVE.W case 15).
                // For abs.l with memory source: prime group0PushedPC for write fault coverage.
                case 15 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    int val = getTemp(cpu, OPERAND);

                    int sExts = (hasExt1(srcMode, srcReg) ? 1 : 0) + (hasExt2(srcMode, srcReg, 4) ? 1 : 0);
                    if (srcMode == 7 && srcReg == 4) sExts = 2;
                    int basePC = cpu.getOpcodeAddress() + 2 + (sExts * 2);

                    if (destMode == 4) {
                        // 68000 predecrement for LONG: An is decremented by 2 per word write.
                        // First decrement of 2 happens here (before the first write).
                        // Second decrement of 2 happens in case 17 (before the second write).
                        // If AE occurs on the first write, An = original - 2 (not -4).
                        int inc = (destReg == 7) ? 2 : 2; // always 2 per word
                        int an = cpu.getA(destReg, 4);
                        int ea = an - 2;  // first word: LSW at (An-2)
                        cpu.setA(destReg, ea);
                        setTemp(cpu, DST_EA, ea - 2); // full long EA = An - 4 (MSW address)
                        cpu.setGroup0PushedPC(cpu.getOpcodeAddress() + (cpu.extensionsConsumed * 2));
                        cpu.startPrefetchCycle();
                        nextStep(cpu);
                        return false;
                    }
                    int ea = calculateEA(cpu, destMode, destReg, 4, basePC, getTemp(cpu, DST_EXT1), getTemp(cpu, DST_EXT2), false, false);
                    setTemp(cpu, DST_EA, ea);
                    calculateAndSetFlags(cpu, val, 4);
                    boolean srcIsRegOrImm = (srcMode <= 1) || (srcMode == 7 && srcReg == 4);
                    if (destMode == 7 && destReg == 1 && !srcIsRegOrImm) {
                        cpu.setGroup0PushedPC(basePC);
                    }
                    nextStep(cpu);
                }

                // First write.
                //   non-predecrement -> MSW at EA
                //   predecrement     -> LSW at (original_An - 2)
                case 16 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    int val = getTemp(cpu, OPERAND);
                    if (destMode == 4) {
                        // Update flags here so triggerAddressError captures the correct post-MOVE SR.
                        calculateAndSetFlags(cpu, val, 4);
                        // First write: LSW at An (already decremented by 2 in case 15)
                        cpu.scheduleWrite(cpu.getA(destReg, 4), val & 0xFFFF, 2);
                    } else {
                        int ea = getTemp(cpu, DST_EA);
                        cpu.scheduleWrite(ea, (val >> 16) & 0xFFFF, 2);
                    }
                    nextStep(cpu);
                    return false;
                }

                // Second write — always back-to-back with the first, no prefetch in between.
                //   non-predecrement -> LSW at EA+2
                //   predecrement     -> MSW at (An - 2) with second decrement of An
                case 17 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    int val = getTemp(cpu, OPERAND);
                    if (destMode == 4) {
                        // Second decrement of 2, then write MSW
                        int ea2 = cpu.getA(destReg, 4) - 2;
                        cpu.setA(destReg, ea2);
                        cpu.scheduleWrite(ea2, (val >> 16) & 0xFFFF, 2);
                    } else {
                        int ea = getTemp(cpu, DST_EA);
                        cpu.scheduleWrite(ea + 2, val & 0xFFFF, 2);
                    }
                    nextStep(cpu);
                    return false;
                }

                // After both writes.
                //   predecrement     -> finalize (the pre-write prefetch from case 15 suffices).
                //   non-predecrement -> post-increment An if (An)+, then issue the one and only
                //                       post-write prefetch; abs.l needs a second prefetch -> 22.
                case 18 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    if (destMode == 4) {
                        return finalizeInstructionSafe(cpu);
                    }
                    if (destMode == 3) cpu.setA(destReg, cpu.getA(destReg, 4) + 4);
                    cpu.setGroup0PushedPC(cpu.getOpcodeAddress() + (cpu.extensionsConsumed * 2));
                    cpu.startPrefetchCycle();
                    if (hasExt2(destMode, destReg, 4)) { setStep(cpu, 22); return false; }
                    return finalizeInstructionSafe(cpu);
                }

                case 19 -> { return finalizeInstructionSafe(cpu); }

                // --- PHASE 4: REGISTER WRITE ---
                case 20 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    int val = getTemp(cpu, OPERAND);
                    calculateAndSetFlags(cpu, val, 4);
                    cpu.setD(destReg, val, 4);
                    cpu.setGroup0PushedPC(cpu.getOpcodeAddress() + (cpu.extensionsConsumed * 2));
                    cpu.startPrefetchCycle();
                    return finalizeInstructionSafe(cpu);
                }

                case 21 -> { return finalizeInstructionSafe(cpu); }

                // abs.l destination: second prefetch step (mirrors MOVE.W case 19).
                case 22 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    cpu.setGroup0PushedPC(cpu.getOpcodeAddress() + (cpu.extensionsConsumed * 2));
                    cpu.startPrefetchCycle();
                    return finalizeInstructionSafe(cpu);
                }

                default -> { return finalizeInstructionSafe(cpu); }
            }
        }
    }

    private void calculateAndSetFlags(Motorola68000 cpu, int res, int size) {
        int newSR = cpu.getSR() & 0xFFF0;
        int msbMask = (size == 1) ? 0x80 : (size == 2) ? 0x8000 : 0x80000000;
        long sizeMask = (size == 1) ? 0xFFL : (size == 2) ? 0xFFFFL : 0xFFFFFFFFL;
        long val = res & sizeMask;
        if ((val & msbMask) != 0) newSR |= 0x08;
        if (val == 0) newSR |= 0x04;
        cpu.writeSR(newSR, false);
    }

    @Override
    public String getDisassembly(Motorola68000 cpu) {
        int opcode = cpu.getIr();
        int size = decodeSize(opcode);
        String s = (size == 1) ? "b" : (size == 2) ? "w" : "l";
        int srcMode = (opcode >> 3) & 7, srcReg = opcode & 7;
        int destReg = (opcode >> 9) & 7, destMode = (opcode >> 6) & 7;
        int sExts = (hasExt1(srcMode, srcReg) ? 1 : 0) + (hasExt2(srcMode, srcReg, size) ? 1 : 0);
        if (srcMode == 7 && srcReg == 4) sExts = (size == 4) ? 2 : 1;
        String srcEa = Disassembly.formatEA(srcMode, srcReg, size, cpu.extensionWordAt(cpu.getOpcodeAddress() + 2), cpu.extensionWordAt(cpu.getOpcodeAddress() + 4));
        String destEa = Disassembly.formatEA(destMode, destReg, size, cpu.extensionWordAt(cpu.getOpcodeAddress() + 2 + (sExts * 2)), cpu.extensionWordAt(cpu.getOpcodeAddress() + 4 + (sExts * 2)));
        if (destMode == 1) return String.format("MOVEA.%s %s, A%d", s, srcEa, destReg);
        return String.format("MOVE.%s %s, %s", s, srcEa, destEa);
    }
}

