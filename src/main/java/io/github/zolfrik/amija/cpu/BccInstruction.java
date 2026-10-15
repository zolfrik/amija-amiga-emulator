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

/**
 * Bcc: Branch Conditionally
 */
public class BccInstruction extends AbstractM68kInstruction {

    public static final BccInstruction INSTANCE = new BccInstruction(false);
    /** The 68010 form: a short branch not taken takes 6 clocks, and an odd target is fetched before the address error. */
    public static final BccInstruction M68010 = new BccInstruction(true);

    private final boolean m68010;

    private BccInstruction(boolean m68010) {
        this.m68010 = m68010;
    }

    @Override
    public boolean shouldInhibitPrefetch() {
        return true;
    }

    @Override
    public boolean executeStep(AbstractMotorola cpu) {
        int op = cpu.getIr();
        int condition = (op >> 8) & 0xF;
        int disp8 = (byte) (op & 0xFF);

        while (true) {
            switch (getStep(cpu)) {
                case 0 -> {
                    if (testCondition(cpu, condition)) {
                        // BRANCH TAKEN (10 cycles: n np np)
                        int displacement;
                        if (disp8 == 0) {
                            // Word displacement (16-bit). prepareInstruction already moved it to IRD.
                            if (!cpu.isIRDValid()) return false;
                            displacement = (short) cpu.getIRD();
                            cpu.consumeIRD();
                        } else {
                            // Short displacement (8-bit)
                            displacement = disp8;
                        }
                        int targetPC = cpu.getOpcodeAddress() + 2 + displacement;
                        if ((targetPC & 1) != 0) {
                            cpu.idleCycles(2); // 'n' gap
                            setTemp(cpu, 15, targetPC);
                            // PC saved in the Group 0 frame: see step 30.
                            setTemp(cpu, 14, cpu.getOpcodeAddress() + 2);
                            setStep(cpu, 30);
                            return false;
                        }
                        cpu.setPC(targetPC);
                        cpu.flushPrefetch();
                        cpu.idleCycles(2); // 'n'
                        setStep(cpu, 10);
                        return false;
                    } else {
                        // BRANCH NOT TAKEN
                        if (disp8 == 0) {
                            // Word branch not taken (12 cycles: nn np np)
                            if (!cpu.isIRDValid()) return false;
                            cpu.consumeIRD(); // Discard displacement word
                            cpu.idleCycles(4); // 'nn'
                            setStep(cpu, 10);
                            return false;
                        } else {
                            // Short branch not taken (8 cycles: nn np; 6 on the 68010: n np)
                            cpu.idleCycles(m68010 ? 2 : 4);
                            setStep(cpu, 20);
                            return false;
                        }
                    }
                }
                case 10 -> { // Start first np of 2
                    if (cpu.internalCyclesRemaining > 0) return false;
                    if (!cpu.isBusCycleIdle()) return false;
                    cpu.startPrefetchCycle();
                    nextStep(cpu);
                    continue;
                }
                case 11 -> { // Start second np and FINISH
                    if (!cpu.isBusCycleIdle()) return false;
                    cpu.startPrefetchCycle();
                    return finalizeInstructionSafe(cpu);
                }
                case 20 -> { // Start single np and FINISH (Short Not Taken)
                    if (cpu.internalCyclesRemaining > 0) return false;
                    if (!cpu.isBusCycleIdle()) return false;
                    cpu.startPrefetchCycle();
                    return finalizeInstructionSafe(cpu);
                }
                case 30 -> { // Address Error handler
                    if (cpu.internalCyclesRemaining > 0) return false;
                    int targetPC = getTemp(cpu, 15);
                    if (m68010 && getTemp(cpu, 13) == 0) {
                        // The 68010 fetches the word at the even-aligned target before the
                        // address error; the fetched word becomes the instruction buffer.
                        setTemp(cpu, 13, 1);
                        setTemp(cpu, 12, cpu.readBuffer);
                        cpu.scheduleRead(targetPC & ~1, 2, cpu.currentFCForInstruction());
                        return false;
                    }
                    if (m68010) {
                        if (!cpu.isBusCycleIdle()) return false;
                        cpu.fetchBuffer = cpu.readBuffer;
                        cpu.readBuffer = getTemp(cpu, 12);
                    }
                    // Frame PC = opcode address + 2 (the internal PC at the moment the branch
                    // computes the destination), NOT target-4: the 68000 restores the old PC,
                    // adds 2 and raises the read address error from there.
                    // The SingleStepTests JSON vectors give target-4 on all 2200 cases (byte and
                    // word) instead: the hardware behaviour wins, as for ASR (section 1 of
                    // castle-master-copylock-investigation). 3D Pool's Copylock uses this PC as
                    // the bound of its own integrity checksum: with target-4 it jumps into the
                    // void.
                    cpu.triggerAddressError(targetPC, false, cpu.currentFCForInstruction(), true,
                                            getTemp(cpu, 14));
                    return finalizeInstruction(cpu);
                }
                default -> { return finalizeInstruction(cpu); }
            }
        }
    }

    @Override
    public String getDisassembly(AbstractMotorola cpu) {
        int op = cpu.getIr();
        int condition = (op >> 8) & 0xF;
        String mnem = switch (condition) {
            case 0 -> "BRA"; case 1 -> "BSR"; case 2 -> "BHI"; case 3 -> "BLS";
            case 4 -> "BCC"; case 5 -> "BCS"; case 6 -> "BNE"; case 7 -> "BEQ";
            case 8 -> "BVC"; case 9 -> "BVS"; case 10 -> "BPL"; case 11 -> "BMI";
            case 12 -> "BGE"; case 13 -> "BLT"; case 14 -> "BGT"; case 15 -> "BLE";
            default -> "B??";
        };
        int disp8 = (byte) (op & 0xFF);
        int displacement = (disp8 == 0) ? (short) cpu.extensionWordAt(cpu.getOpcodeAddress() + 2) : disp8;
        return mnem + " $" + Integer.toHexString(cpu.getOpcodeAddress() + 2 + displacement);
    }
}

