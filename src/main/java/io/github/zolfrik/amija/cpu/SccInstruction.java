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
 * Implementation of the SCC instruction.
 */
public class SccInstruction extends AbstractM68kInstruction {

    public static final SccInstruction INSTANCE = new SccInstruction(false);
    /** The 68010 form: a memory destination is written without being read first. */
    public static final SccInstruction M68010 = new SccInstruction(true);

    private final boolean m68010;
    private SccInstruction(boolean m68010) {
        this.m68010 = m68010;
    }

    private static final int EXT1 = 0;
    private static final int EXT2 = 1;
    private static final int ADDR = 2;
    private static final int RESULT = 3;

    @Override
    public boolean executeStep(AbstractMotorola cpu) {
        int op = cpu.getIr();
        int cond = (op >> 8) & 0xF, eaMode = (op >> 3) & 7, eaReg = op & 7;

        while (true) {
            if (cpu.isRisingEdgeConsumed()) return false;
            switch (getStep(cpu)) {
                case 0 -> {
                    // Scc An is illegal
                    if (eaMode == 1) {
                        cpu.triggerIllegalInstruction();
                        return false;
                    }
                    if (m68010 && eaMode != 0) {
                        setStep(cpu, 40);
                        continue;
                    }
                    // Initial gap for -(An) or indexed
                    if (eaMode == 4 || eaMode == 6 || (eaMode == 7 && eaReg == 3)) {
                        indexedGap2(cpu);
                    }
                    nextStep(cpu);
                    continue;
                }
                case 1 -> {
                    if (hasExt1(eaMode, eaReg)) {
                        if (!fetchExtension(cpu, EXT1)) return false;
                    }
                    nextStep(cpu);
                    continue;
                }
                case 2 -> {
                    if (hasExt2(eaMode, eaReg, 1)) {
                        if (!fetchExtension(cpu, EXT2)) return false;
                    }
                    nextStep(cpu);
                    continue;
                }
                case 3 -> {
                    boolean met = testCondition(cpu, cond);
                    int res = met ? 0xFF : 0;
                    setTemp(cpu, RESULT, res);

                    if (eaMode == 0) {
                        // Dn: np (4 cycles if false) or np n (6 cycles if true)
                        cpu.setD(eaReg, res, 1);
                        refillPipeline(cpu);
                        if (met) {
                            nextStep(cpu); // Proceed to idle gap
                            return false;
                        }
                        return finalizeInstructionSafe(cpu);
                    } else {
                        // Memory: nr np nw
                        int addr = calculateEA(cpu, eaMode, eaReg, 1, cpu.getOpcodeAddress() + 2, getTemp(cpu, EXT1), getTemp(cpu, EXT2), true, false);
                        setTemp(cpu, ADDR, addr);
                        cpu.scheduleRead(addr, 1);
                        nextStep(cpu);
                        return false;
                    }
                }
                case 4 -> {
                    if (eaMode == 0) {
                        // Dn condition was true: trailing idle cycle (n)
                        if (!cpu.isBusCycleIdle()) return false;
                        cpu.idleCycles(2);
                        nextStep(cpu);
                        return false;
                    } else {
                        // Memory: np (refill between read and write)
                        refillPipeline(cpu);
                        nextStep(cpu);
                        return false;
                    }
                }
                case 5 -> {
                    if (eaMode == 0) {
                        return finalizeInstructionSafe(cpu);
                    } else {
                        // Memory: nw (write result)
                        cpu.scheduleWrite(getTemp(cpu, ADDR), getTemp(cpu, RESULT), 1);
                        return finalizeInstructionSafe(cpu);
                    }
                }
                // 68010 memory destination: (An) n2, (An)+/-(An) n4, (d8,An,Xn) np n4,
                // then np and the write; the IPL is sampled before the np.
                case 40 -> {
                    nextStep(cpu);
                    if (eaMode == 2) { cpu.idleCycles(2); return false; }
                    if (eaMode == 3 || eaMode == 4) { cpu.idleCycles(4); return false; }
                }
                case 41 -> {
                    if (hasExt1(eaMode, eaReg)) {
                        if (!fetchExtension(cpu, EXT1)) return false;
                        nextStep(cpu);
                        return false;
                    }
                    nextStep(cpu);
                }
                case 42 -> {
                    if (hasExt2(eaMode, eaReg, 1)) {
                        if (!fetchExtension(cpu, EXT2)) return false;
                        nextStep(cpu);
                        return false;
                    }
                    nextStep(cpu);
                }
                case 43 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    nextStep(cpu);
                    if (eaMode == 6) { cpu.idleCycles(4); return false; }
                }
                case 44 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    int addr = calculateEA(cpu, eaMode, eaReg, 1, cpu.getOpcodeAddress() + 2, getTemp(cpu, EXT1), getTemp(cpu, EXT2), true, false);
                    setTemp(cpu, ADDR, addr);
                    cpu.markIplAnchor(AbstractMotorola.IplAnchor.NOW);
                    refillPipeline(cpu);
                    nextStep(cpu);
                    return false;
                }
                case 45 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    cpu.scheduleWrite(getTemp(cpu, ADDR), testCondition(cpu, cond) ? 0xFF : 0, 1);
                    return finalizeInstructionSafe(cpu);
                }
                default -> { return finalizeInstructionSafe(cpu); }
            }
        }
    }

    @Override
    public boolean shouldInhibitPrefetch() {
        return true;
    }

    @Override
    public String getDisassembly(AbstractMotorola cpu) {
        int op = cpu.getIr();
        String[] conds = {"T", "F", "HI", "LS", "CC", "CS", "NE", "EQ", "VC", "VS", "PL", "MI", "GE", "LT", "GT", "LE"};
        return "S" + conds[(op >> 8) & 0xF] + " " + Disassembly.formatEA((op >> 3) & 7, op & 7, 1, cpu.extensionWordAt(cpu.getOpcodeAddress() + 2), cpu.extensionWordAt(cpu.getOpcodeAddress() + 4));
    }
}

