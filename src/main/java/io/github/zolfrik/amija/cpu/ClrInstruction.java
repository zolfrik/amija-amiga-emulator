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
 * Implementation of the CLR instruction.
 */
public class ClrInstruction extends AbstractM68kInstruction {

    public static final ClrInstruction INSTANCE = new ClrInstruction(false);
    /** The 68010 form: a memory destination is written without being read first. */
    public static final ClrInstruction M68010 = new ClrInstruction(true);

    private final boolean m68010;
    private ClrInstruction(boolean m68010) {
        this.m68010 = m68010;
    }

    private static final int EXT1 = 0;
    private static final int EXT2 = 1;
    private static final int ADDR = 2;

    @Override
    public boolean executeStep(AbstractMotorola cpu) {
        int op = cpu.getIr();
        int s = (op >> 6) & 3;
        int size = (s == 0) ? 1 : (s == 1) ? 2 : 4;
        int eaMode = (op >> 3) & 7, eaReg = op & 7;

        while (true) {
            if (cpu.isRisingEdgeConsumed()) return false;

            switch (getStep(cpu)) {
                case 0 -> { // Step 0: Initial EA Gap (before extensions)
                    // The 68010 takes the indexed gap after the extension word (step 4).
                    if (eaMode == 4 || (eaMode == 6 && !m68010)) {
                        indexedGap2(cpu);
                        nextStep(cpu);
                        return false;
                    }
                    nextStep(cpu);
                }
                case 1 -> { // Step 1: Fetch Extension 1
                    if (hasExt1(eaMode, eaReg)) {
                        if (!fetchExtension(cpu, EXT1)) return false;
                    }
                    nextStep(cpu);
                }
                case 2 -> { // Step 2: Fetch Extension 2
                    if (hasExt2(eaMode, eaReg, size)) {
                        if (!fetchExtension(cpu, EXT2)) return false;
                    }
                    nextStep(cpu);
                }
                case 3 -> { // Step 3: EA calculation
                    if (eaMode == 0) {
                        setStep(cpu, 10);
                        continue;
                    }
                    int addr = calculateEA(cpu, eaMode, eaReg, size, cpu.getOpcodeAddress() + 2, getTemp(cpu, EXT1), getTemp(cpu, EXT2), true, false);
                    if (cpu.getCpuState() == AbstractMotorola.CpuState.EXCEPTION) return false;
                    setTemp(cpu, ADDR, addr);
                    nextStep(cpu);
                }
                case 4 -> { // Step 4: Dummy Read
                    if (m68010) {
                        // 68010: no read. (d8,An,Xn) spends 4 clocks here, then the IPL is
                        // sampled and the queue refilled before the write.
                        setStep(cpu, 20);
                        if (eaMode == 6) {
                            cpu.idleCycles(4);
                            return false;
                        }
                        continue;
                    }
                    int addr = getTemp(cpu, ADDR);
                    cpu.scheduleRead(addr, size);
                    nextStep(cpu);
                    return false;
                }
                case 5 -> { // Step 5: Wait for Read and Refill Pipeline
                    if (!cpu.isBusCycleIdle()) return false;
                    if (cpu.getCpuState() == AbstractMotorola.CpuState.EXCEPTION) return false;
                    updateFlags(cpu);
                    refillPipeline(cpu);
                    nextStep(cpu);
                    return false;
                }
                case 6 -> { // Step 6: Wait for refill and Write 1
                    if (!cpu.isBusCycleIdle()) return false;
                    int addr = getTemp(cpu, ADDR);
                    if (size == 4) {
                        cpu.scheduleWriteWord(addr + 2, 0); // LSW at N+2
                        nextStep(cpu);
                        return false;
                    }
                    // Overlapped prefetch (Phase 3): the .b/.w write is the LAST bus cycle;
                    // finalize in this same step so it overlaps the write and the next
                    // instruction decodes when the write drains (seam 0). See
                    // docs/cpu-overlapped-prefetch-rework-plan.md, Phase 3.
                    cpu.scheduleWrite(addr, 0, size);
                    return finalizeInstructionSafe(cpu);
                }
                case 7 -> { // Step 7: Write 2 (MSW, for .l) + finalize
                    // .l memory: the MSW write is the LAST bus cycle -> finalize with it
                    // (overlapped prefetch, seam 0) instead of deferring to a wait step.
                    // Matches the .b/.w path (case 6). JSON-safe: the write transaction is
                    // captured identically. See docs/cpu-overlapped-prefetch-rework-plan.md,
                    // Phase 3.
                    if (!cpu.isBusCycleIdle()) return false;
                    int addr = getTemp(cpu, ADDR);
                    cpu.scheduleWriteWord(addr, 0); // MSW at N: last bus cycle
                    return finalizeInstructionSafe(cpu);
                }

                case 20 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    updateFlags(cpu);
                    cpu.markIplAnchor(AbstractMotorola.IplAnchor.NOW);
                    refillPipeline(cpu);
                    setStep(cpu, 6);
                    return false;
                }

                // --- Dn Case ---
                case 10 -> { // Step 10: Clear register and Refill
                    if (m68010 && size == 4) {
                        // 68010 CLR.L Dn: n2, then the prefetch.
                        cpu.setD(eaReg, 0, size);
                        updateFlags(cpu);
                        cpu.idleCycles(2);
                        setStep(cpu, 13);
                        return false;
                    }
                    cpu.setD(eaReg, 0, size);
                    updateFlags(cpu);
                    refillPipeline(cpu);
                    if (size == 4) {
                        nextStep(cpu);
                        return false;
                    }
                    return finalizeInstructionSafe(cpu);
                }
                case 11 -> { // Step 11: Trailing idle for .l Dn
                    if (!cpu.isBusCycleIdle()) return false;
                    cpu.idleCycles(2);
                    nextStep(cpu);
                    return false;
                }
                case 12 -> { // Step 12: Finalize
                    return finalizeInstructionSafe(cpu);
                }
                case 13 -> {
                    refillPipeline(cpu);
                    return finalizeInstructionSafe(cpu);
                }
                default -> { return finalizeInstructionSafe(cpu); }
            }
        }
    }

    private void updateFlags(AbstractMotorola cpu) {
        int sr = cpu.getSR() & 0xFFF0; // Clear C, V, Z, N. Keep X.
        sr |= 0x04; // Set Z
        cpu.writeSR(sr, false);
    }

    @Override
    public boolean shouldInhibitPrefetch() {
        return true;
    }

    @Override
    public String getDisassembly(AbstractMotorola cpu) {
        int op = cpu.getIr();
        int s = (op >> 6) & 3;
        int size = (s == 0) ? 1 : (s == 1) ? 2 : 4;
        return "CLR" + Disassembly.getSizeSuffix(size) + " " + Disassembly.formatEA((op >> 3) & 7, op & 7, size, cpu.extensionWordAt(cpu.getOpcodeAddress() + 2), cpu.extensionWordAt(cpu.getOpcodeAddress() + 4));
    }
}

