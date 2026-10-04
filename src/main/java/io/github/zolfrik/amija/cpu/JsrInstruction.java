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
 * JSR: Jump to Subroutine
 */
public class JsrInstruction extends AbstractM68kInstruction {

    public static final JsrInstruction INSTANCE = new JsrInstruction();

    private JsrInstruction() {}

    private static final int EXT1 = 0;
    private static final int EXT2 = 1;
    private static final int TARGET_PC = 2;
    private static final int RETURN_ADDR = 3;

    @Override
    public boolean shouldInhibitPrefetch() {
        return true;
    }

    @Override
    public boolean executeStep(AbstractMotorola cpu) {
        int op = cpu.getIr(), eaMode = (op >> 3) & 0x7, eaReg = op & 0x7;
        while (true) {
            switch (getStep(cpu)) {
                case 0 -> {
                    if (hasExt1(eaMode, eaReg)) {
                        boolean needsExt2 = hasExt2(eaMode, eaReg, 4);
                        if (!fetchJsrExtension(cpu, EXT1, !needsExt2)) return false;
                    }
                    nextStep(cpu);
                }
                case 1 -> {
                    if (hasExt2(eaMode, eaReg, 4)) {
                        if (!fetchJsrExtension(cpu, EXT2, true)) return false;
                    }
                    nextStep(cpu);
                }
                case 2 -> {
                    // ($4ED0/$4EF9/$4EA8/$4EB9): the `now` sample comes RIGHT AFTER the EA
                    // computation, i.e. at cycle 0 for the modes that read the extension already
                    // in IRC, and after its prefetch for (xxx).L. The anchor is forced `now` and
                    // frozen: JSR's pushes and the two prefetches at the target do not move it.
                    cpu.markIplAnchor(AbstractMotorola.IplAnchor.NOW);
                    int targetPC = calculateEA(cpu, eaMode, eaReg, 4, cpu.getOpcodeAddress() + 2, getTemp(cpu, EXT1), getTemp(cpu, EXT2), false, false);
                    setTemp(cpu, TARGET_PC, targetPC);
                    setTemp(cpu, RETURN_ADDR, cpu.getOpcodeAddress() + (cpu.extensionsConsumed * 2));

                    int totalGap = 0;
                    if (eaMode == 5 || (eaMode == 7 && (eaReg == 0 || eaReg == 2))) {
                        totalGap = 2; // n
                    } else if (eaMode == 6 || (eaMode == 7 && eaReg == 3)) {
                        totalGap = 6; // n nn
                    }

                    if ((targetPC & 1) != 0) {
                        if (totalGap > 0) {
                            cpu.idleCycles(totalGap);
                            setStep(cpu, 10);
                            return true;
                        }
                        setStep(cpu, 10);
                        continue;
                    }

                    if (totalGap > 0) {
                        cpu.idleCycles(totalGap);
                        nextStep(cpu);
                        return true;
                    }
                    nextStep(cpu);
                }
                case 3 -> {
                    int targetPC = getTemp(cpu, TARGET_PC);
                    cpu.setPC(targetPC);
                    cpu.startPrefetchCycle(); // np 1
                    nextStep(cpu);
                    return false;
                }
                case 4 -> {
                    if (!cpu.isIRDValid()) return false;
                    int returnAddress = getTemp(cpu, RETURN_ADDR);
                    // The fault is on the PUSH, not on the EA: for JSR the mode delta does not
                    // apply ($4EA8/$4EBA: +4, not 2). Delta zero.
                    cpu.markGroup0EaDelta(0);
                    cpu.setA7(cpu.getA7() - 4);
                    cpu.scheduleWriteWord(cpu.getA7(), (returnAddress >>> 16) & 0xFFFF); // nS (Push MSW)
                    nextStep(cpu);
                    return false;
                }
                case 5 -> {
                    int returnAddress = getTemp(cpu, RETURN_ADDR);
                    cpu.scheduleWriteWord(cpu.getA7() + 2, returnAddress & 0xFFFF); // ns (Push LSW)
                    nextStep(cpu);
                    return false;
                }
                case 6 -> {
                    // Overlapped prefetch (Phase 5): np2 is the last bus cycle; finalize in this
                    // same step (like RTS) so the target instruction decodes the instant np2
                    // drains (seam 0), instead of waiting in a separate step (wasted 1 mc). See
                    // docs/cpu-overlapped-prefetch-rework-plan.md, Phase 5.
                    cpu.startPrefetchCycle(); // np 2
                    return finalizeInstructionSafe(cpu);
                }
                case 7 -> {
                    if (!cpu.isIRCValid()) return false;
                    return finalizeInstructionSafe(cpu);
                }
                case 10 -> {
                    if (cpu.internalCyclesRemaining > 0) return false;
                    int targetPC = getTemp(cpu, TARGET_PC);
                    cpu.triggerAddressError(targetPC, false, cpu.currentFCForInstruction(), true, targetPC - 4);
                    return finalizeInstruction(cpu);
                }
                default -> { return finalizeInstruction(cpu); }
            }
        }
    }

    private boolean fetchJsrExtension(AbstractMotorola cpu, int tempRegIndex, boolean lastExtension) {
        if (!cpu.isIRDValid()) {
            cpu.startPrefetchCycle();
            return false;
        }
        cpu.setTempReg(tempRegIndex, cpu.getIRD() & 0xFFFF);
        cpu.consumeIRC();
        if (!lastExtension) {
            cpu.startPrefetchCycle();
        }
        return true;
    }

    @Override
    public String getDisassembly(AbstractMotorola cpu) {
        int op = cpu.getIr(), eaMode = (op >> 3) & 0x7, eaReg = op & 0x7;
        return "JSR " + Disassembly.formatEA(eaMode, eaReg, 4, cpu.extensionWordAt(cpu.getOpcodeAddress() + 2), cpu.extensionWordAt(cpu.getOpcodeAddress() + 4));
    }
}

