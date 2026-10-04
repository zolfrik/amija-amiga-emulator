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
 * Implementation of the CHK (Check Register Against Bounds) instruction.
 */
public class ChkInstruction extends AbstractM68kInstruction {

    public static final ChkInstruction INSTANCE = new ChkInstruction();
    private ChkInstruction() {}

    private static final int EXT1 = 0;
    private static final int EXT2 = 1;
    private static final int BOUND = 2;

    @Override
    public boolean shouldInhibitPrefetch() {
        return true;
    }

    @Override
    public boolean executeStep(AbstractMotorola cpu) {
        int op = cpu.getIr();
        int reg = (op >> 9) & 7, eaMode = (op >> 3) & 7, eaReg = op & 7;

        while (true) {
            if (cpu.isRisingEdgeConsumed()) return false;
            switch (getStep(cpu)) {
                case 0 -> {
                    if (eaMode == 4 || eaMode == 6 || (eaMode == 7 && eaReg == 3)) {
                        indexedGap2(cpu);
                        nextStep(cpu);
                        return false;
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
                    if (hasExt2(eaMode, eaReg, 2)) {
                        if (!fetchExtension(cpu, EXT2)) return false;
                    }
                    nextStep(cpu);
                    continue;
                }
                case 3 -> {
                    if (eaMode == 0) {
                        setTemp(cpu, BOUND, (short) cpu.getD(eaReg, 2));
                        setStep(cpu, 5);
                        continue;
                    } else if (eaMode == 7 && eaReg == 4) {
                        setTemp(cpu, BOUND, (short) getTemp(cpu, EXT1));
                        setStep(cpu, 5);
                        continue;
                    } else {
                        int addr = calculateEA(cpu, eaMode, eaReg, 2, cpu.getOpcodeAddress() + 2, getTemp(cpu, EXT1), getTemp(cpu, EXT2), true, false);
                        cpu.scheduleRead(addr, 2);
                        nextStep(cpu);
                        return false;
                    }
                }
                case 4 -> {
                    setTemp(cpu, BOUND, (short) cpu.getLatchedBusData());
                    nextStep(cpu);
                    continue;
                }
                case 5 -> {
                    // Wait for the operand read (EA fetch) to drain before deciding.
                    if (!cpu.isBusCycleIdle()) return false;

                    short bound = (short) getTemp(cpu, BOUND);
                    short val = (short) cpu.getD(reg, 2);
                    int sr = cpu.getSR();

                    boolean lowerTrap = (val < 0);
                    boolean upperTrap = (val > bound);

                    // Flag Logic: NMOS 68000 Quirk
                    // N: set if Dn < 0; cleared if Dn > source; preserved otherwise.
                    // Z: Always updated based on Dn == 0.
                    // V, C: cleared. X: preserved.
                    int ccr = (sr & 0x10); // Keep X
                    if (lowerTrap) {
                        ccr |= 0x08; // N=1
                    } else if (upperTrap) {
                        // N=0
                    } else {
                        ccr |= (sr & 0x08); // Preserve N
                    }

                    if (val == 0) ccr |= 0x04; // Z
                    cpu.writeCCR(ccr);

                    if (lowerTrap || upperTrap) {
                        if (cpu.seamOverlapEnabled) {
                            // Trap path: NO next-instruction prefetch. The pre-frame internal
                            // timing is 6 + 2 (upper bound) / 4 (lower bound) = 8/10 cycles, then
                            // the exception (whose frame writes start with NO leading nn on the
                            // 68000). Our Group1/2 exception frame already contributes its own nn
                            // (idleCycles(4)), so emit (8-4)=4 (upper) / (10-4)=6 (lower) idle
                            // cycles here and let the frame add the rest. The np bus access is
                            // replaced by idle, so the frame's stack writes land at the right
                            // CCK (exception-entry marker parity).
                            cpu.idleCycles((lowerTrap && !upperTrap) ? 6 : 4);
                            setStep(cpu, 10);
                            return false;
                        }
                        // JSON/single-instruction harness: keep the historical np-first order
                        // so the captured transactions stay Tom-Harte-compatible.
                        cpu.startPrefetchCycle(); // np
                        setStep(cpu, 8);
                        return false;
                    } else if (cpu.seamOverlapEnabled) {
                        // Success timing: the six internal cycles run BEFORE the terminating
                        // prefetch. This lands the np at the right CCK on contended bitplane
                        // bands (marker parity). The total cycle count is identical to the
                        // np-then-idle order (uncontended bands unchanged).
                        cpu.idleCycles(6); // nn n
                        setStep(cpu, 6);
                        return false;
                    } else {
                        // Single-instruction JSON harness (seamOverlapEnabled=false): keep the
                        // np-then-idle order so the captured transactions match the Tom Harte
                        // serialization convention (trailing idle after the terminating
                        // prefetch). The physical reorder above applies only to the real
                        // machine.
                        // The anchor is the same as in the other order (case 6): the terminating
                        // prefetch, not the operand read. It must be declared here too, or the
                        // two orders of the same instruction would sample the pin at two
                        // different points.
                        cpu.markIplAnchor(AbstractMotorola.IplAnchor.NEXT);
                        cpu.startPrefetchCycle(); // np
                        setStep(cpu, 9);
                        return false;
                    }
                }
                case 6 -> {
                    // Success path (run): terminating prefetch, AFTER the internal cycles.
                    if (!cpu.isBusCycleIdle()) return false;
                    // Between the operand read and the final prefetch run the internal cycles,
                    // and the IPL sample (`ipl_fetch_next`) sits right before the prefetch: the
                    // anchor is the prefetch, not the read.
                    cpu.markIplAnchor(AbstractMotorola.IplAnchor.NEXT);
                    cpu.startPrefetchCycle(); // np
                    setStep(cpu, 7);
                    return false;
                }
                case 9 -> {
                    // Success path (JSON): after the np drains, the trailing internal cycles.
                    if (!cpu.isBusCycleIdle()) return false;
                    cpu.idleCycles(6); // nn n
                    setStep(cpu, 7);
                    return false;
                }
                case 7 -> {
                    // Wait for the terminating prefetch to drain, so the instruction finalizes
                    // with the bus IDLE and IRD refilled: this preserves the Phase 7/8
                    // overlapped-finalize seam (no dead boundary mc).
                    if (!cpu.isBusCycleIdle()) return false;
                    return finalizeInstructionSafe(cpu);
                }
                case 8 -> {
                    // Trap path: after the np drains, apply the trailing internal cycles
                    // and raise the CHK exception (vector 6).
                    if (!cpu.isBusCycleIdle()) return false;

                    short bound = (short) getTemp(cpu, BOUND);
                    short val = (short) cpu.getD(reg, 2);
                    boolean lowerTrap = (val < 0);
                    boolean upperTrap = (val > bound);

                    int extensions = (hasExt1(eaMode, eaReg) ? 1 : 0) + (hasExt2(eaMode, eaReg, 2) ? 1 : 0);
                    int nextPC = cpu.getOpcodeAddress() + 2 + (extensions * 2);

                    if (lowerTrap && !upperTrap) {
                        cpu.addInternalCycles(2);
                    }
                    cpu.triggerException(6, nextPC);
                    return false;
                }
                case 10 -> {
                    // Trap path (run): after the internal cycles, raise the CHK exception
                    // directly (no next-instruction prefetch).
                    if (!cpu.isBusCycleIdle()) return false;
                    int extensions = (hasExt1(eaMode, eaReg) ? 1 : 0) + (hasExt2(eaMode, eaReg, 2) ? 1 : 0);
                    int nextPC = cpu.getOpcodeAddress() + 2 + (extensions * 2);
                    cpu.triggerException(6, nextPC);
                    return false;
                }
                default -> {
                    return finalizeInstructionSafe(cpu);
                }
            }
        }
    }

    @Override
    public String getDisassembly(AbstractMotorola cpu) {
        int op = cpu.getIr();
        return "CHK " + Disassembly.formatEA((op >> 3) & 7, op & 7, 2, cpu.extensionWordAt(cpu.getOpcodeAddress() + 2), cpu.extensionWordAt(cpu.getOpcodeAddress() + 4)) + ", D" + ((op >> 9) & 7);
    }
}

