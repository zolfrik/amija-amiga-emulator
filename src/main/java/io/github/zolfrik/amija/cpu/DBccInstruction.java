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
 * Implementation of the DBcc (Test Condition, Decrement, and Branch) instruction.
 */
public class DBccInstruction extends AbstractM68kInstruction {

    public static final DBccInstruction INSTANCE = new DBccInstruction();

    private DBccInstruction() {}

    private static final int DISPLACEMENT = 0;

    @Override
    public boolean shouldInhibitPrefetch() {
        return true;
    }

    @Override
    public boolean executeStep(Motorola68000 cpu) {
        int op = cpu.getIr();

        while (true) {
            switch (getStep(cpu)) {
                case 0 -> {
                    // Displacement word is in IRD (moved from IRC by prepareInstruction)
                    if (!cpu.isIRDValid()) {
                        cpu.startPrefetchCycle();
                        return false;
                    }
                    int displacement = (short) cpu.getIRD();
                    setTemp(cpu, DISPLACEMENT, displacement);
                    cpu.consumeIRD();

                    int condition = (op >> 8) & 0xF;
                    if (testCondition(cpu, condition)) {
                        // CC TRUE: 12(2/0) cycles, sequence n n np np
                        setupGap4(cpu);
                        setStep(cpu, 20);
                        return false;
                    } else {
                        int reg = op & 0x7;
                        int val = (cpu.getD(reg, 2) - 1) & 0xFFFF;
                        cpu.setD(reg, val, 2);
                        if (val == 0xFFFF) {
                            // COUNTER EXPIRED: 14(3/0) cycles, sequence n np np np
                            // Yacht notes: fetches PC+2, PC+4, PC+6
                            indexedGap2(cpu);
                            setStep(cpu, 30);
                            return false;
                        } else {
                            // BRANCH TAKEN: 10(2/0) cycles, sequence n np np
                            // Yacht notes: fetches Target, Target+2
                            indexedGap2(cpu);
                            int targetPC = cpu.getOpcodeAddress() + 2 + displacement;
                            if ((targetPC & 1) != 0) {
                                setTemp(cpu, 15, targetPC);
                                setStep(cpu, 40); // Address Error
                                return false;
                            }
                            cpu.setPC(targetPC);
                            setStep(cpu, 10);
                            return false;
                        }
                    }
                }
                case 10 -> { // BRANCH TAKEN refill
                    if (cpu.internalCyclesRemaining > 0) return false;
                    if (!cpu.isBusCycleIdle()) return false;
                    cpu.startPrefetchCycle(); // np 1 (Target)
                    nextStep(cpu);
                    return false;
                }
                case 11 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    cpu.startPrefetchCycle(); // np 2 (Target+2)
                    return finalizeInstructionSafe(cpu);
                }
                case 20 -> { // CC TRUE refill
                    if (cpu.internalCyclesRemaining > 0) return false;
                    if (!cpu.isBusCycleIdle()) return false;
                    cpu.startPrefetchCycle(); // np 1 (PC+4)
                    nextStep(cpu);
                    return false;
                }
                case 21 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    cpu.startPrefetchCycle(); // np 2 (PC+6)
                    return finalizeInstructionSafe(cpu);
                }
                case 30 -> { // COUNTER EXPIRED refill
                    if (cpu.internalCyclesRemaining > 0) return false;
                    if (!cpu.isBusCycleIdle()) return false;
                    // yacht.txt sequence n np np np implies fetching PC+2, PC+4, PC+6
                    cpu.setPC(cpu.getOpcodeAddress() + 2);
                    cpu.startPrefetchCycle(); // np 1 (PC+2)
                    nextStep(cpu);
                    return false;
                }
                case 31 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    cpu.startPrefetchCycle(); // np 2 (PC+4)
                    nextStep(cpu);
                    return false;
                }
                case 32 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    cpu.consumeIRC(); // Move [PC+4] to IRD to make room for np 3
                    cpu.startPrefetchCycle(); // np 3 (PC+6)
                    return finalizeInstructionSafe(cpu);
                }
                case 40 -> { // Address Error
                    if (cpu.internalCyclesRemaining > 0) return false;
                    int targetPC = getTemp(cpu, 15);
                    cpu.triggerAddressError(targetPC, false, cpu.currentFCForInstruction(), true, targetPC - 4);
                    return finalizeInstruction(cpu);
                }
                default -> { return finalizeInstruction(cpu); }
            }
        }
    }

    @Override
    public String getDisassembly(Motorola68000 cpu) {
        int op = cpu.getIr();
        int displacement = cpu.extensionWordAt(cpu.getOpcodeAddress() + 2);
        int targetPC = cpu.getOpcodeAddress() + 2 + (short) displacement;
        String name = switch ((op >> 8) & 0xF) {
            case 0 -> "DBT"; case 1 -> "DBRA"; case 2 -> "DBHI"; case 3 -> "DBLS";
            case 4 -> "DBCC"; case 5 -> "DBCS"; case 6 -> "DBNE"; case 7 -> "DBEQ";
            case 8 -> "DBVC"; case 9 -> "DBVS"; case 10 -> "DBPL"; case 11 -> "DBMI";
            case 12 -> "DBGE"; case 13 -> "DBLT"; case 14 -> "DBGT"; case 15 -> "DBLE";
            default -> "DB??";
        };
        return name + " D" + (op & 0x7) + ", $" + Disassembly.formatHex(targetPC, 8);
    }
}

