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

    public static final DBccInstruction INSTANCE = new DBccInstruction(false);
    /** The 68010 form: condition true takes 10 clocks, an expired counter 16. */
    public static final DBccInstruction M68010 = new DBccInstruction(true);

    private final boolean m68010;

    private DBccInstruction(boolean m68010) {
        this.m68010 = m68010;
    }

    private static final int DISPLACEMENT = 0;
    /** Loop mode left by the previous iteration, read when the DBcc starts. */
    private static final int WAS_LOOPING = 1;
    private static final int COUNTER = 2;

    /** Step at which the DBcc resumes after the body of a loop. */
    static final int LOOP_EPILOGUE = 50;

    @Override
    public boolean shouldInhibitPrefetch() {
        return true;
    }

    @Override
    public boolean executeStep(AbstractMotorola cpu) {
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
                    if (m68010) {
                        setTemp(cpu, WAS_LOOPING, cpu.loopMode);
                        cpu.loopMode = 0;
                    }

                    int condition = (op >> 8) & 0xF;
                    if (testCondition(cpu, condition)) {
                        // CC TRUE: 12(2/0) cycles, sequence n n np np (n np np on the 68010)
                        cpu.idleCycles(m68010 ? 2 : 4);
                        setStep(cpu, 20);
                        return false;
                    } else {
                        int reg = op & 0x7;
                        int val = (cpu.getD(reg, 2) - 1) & 0xFFFF;
                        cpu.setD(reg, val, 2);
                        if (m68010 && displacement == -4 && !cpu.isTracePending()
                                && ((Motorola68010) cpu).isLoopable(cpu.previousOpcode)) {
                            // Loop mode: the previous instruction and this DBcc are kept
                            // in the instruction registers and repeated without fetches.
                            setTemp(cpu, COUNTER, val);
                            indexedGap2(cpu);
                            setStep(cpu, 60);
                            return false;
                        }
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
                    if (m68010 && getTemp(cpu, 14) == 0) {
                        // 68010: n between the discarded fetch and the refill.
                        setTemp(cpu, 14, 1);
                        cpu.idleCycles(2);
                        return false;
                    }
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
                // --- 68010 loop mode ---
                case 60 -> {
                    if (cpu.internalCyclesRemaining > 0) return false;
                    if (getTemp(cpu, WAS_LOOPING) == 0) {
                        // Entering: fetch the looped instruction and the DBcc once more.
                        cpu.setPC(cpu.getOpcodeAddress() - 2);
                        cpu.startPrefetchCycle();
                        setStep(cpu, 61);
                        return false;
                    }
                    cpu.idleCycles(2);
                    setStep(cpu, 62);
                    return false;
                }
                case 61 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    cpu.startPrefetchCycle();
                    setStep(cpu, 62);
                    return false;
                }
                case 62 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    cpu.loopMode = 1;
                    if (getTemp(cpu, COUNTER) == 0xFFFF) {
                        // The counter was already 0: leave the loop.
                        cpu.loopMode = 0;
                        cpu.setPC(cpu.getOpcodeAddress() + 4);
                        cpu.startPrefetchCycle();
                        setStep(cpu, 53);
                        return false;
                    }
                    Motorola68010 cpu010 = (Motorola68010) cpu;
                    int disp = getTemp(cpu, DISPLACEMENT);
                    cpu010.beginLoopBody();
                    cpu010.loopDisplacement = disp;
                    return cpu.getCurrentInstruction().executeStep(cpu);
                }
                case LOOP_EPILOGUE -> {
                    // Back from the body: its end clocks, then either the next iteration or,
                    // when the condition is false and the counter has reached 0, the exit.
                    if (!cpu.isBusCycleIdle()) return false;
                    Motorola68010 cpu010 = (Motorola68010) cpu;
                    int reg = op & 0x7;
                    int clocks = cpu.loopMode != 0 ? cpu010.loopExtraClocks() : 0;
                    if (!testCondition(cpu, (op >> 8) & 0xF) && cpu.getD(reg, 2) == 0) {
                        cpu.setD(reg, 0xFFFF, 2);
                        clocks += cpu.loopMode >>> 16;
                        cpu.loopMode = 0;
                        setStep(cpu, 52);
                    } else {
                        clocks += cpu.loopMode & 0xFFFE;
                        setStep(cpu, 51);
                    }
                    if (clocks > 0) {
                        cpu.idleCycles(clocks);
                        return false;
                    }
                    continue;
                }
                case 51 -> { // Next iteration: the DBcc is decoded again from the queue.
                    if (cpu.internalCyclesRemaining > 0) return false;
                    ((Motorola68010) cpu).reloadLoopQueue(((Motorola68010) cpu).loopDisplacement);
                    cpu.markIplAnchor(AbstractMotorola.IplAnchor.NOW);
                    return finalizeInstructionSafe(cpu);
                }
                case 52 -> { // Exit: refill the queue after the DBcc.
                    if (cpu.internalCyclesRemaining > 0) return false;
                    cpu.setPC(cpu.getOpcodeAddress() + 4);
                    cpu.startPrefetchCycle();
                    setStep(cpu, 53);
                    return false;
                }
                case 53 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    cpu.markIplAnchor(AbstractMotorola.IplAnchor.NEXT);
                    cpu.startPrefetchCycle();
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
    public String getDisassembly(AbstractMotorola cpu) {
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

