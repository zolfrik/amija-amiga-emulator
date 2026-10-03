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
 * JMP: Jump
 */
public class JmpInstruction extends AbstractM68kInstruction {

    public static final JmpInstruction INSTANCE = new JmpInstruction();

    private static final int EXT1 = 0;
    private static final int EXT2 = 1;
    private static final int TEMP_TARGET_PC = 10;

    private JmpInstruction() {}

    @Override
    public boolean shouldInhibitPrefetch() {
        return true;
    }

    @Override
    public boolean executeStep(Motorola68000 cpu) {
        int op = cpu.getIr(), eaMode = (op >> 3) & 0x7, eaReg = op & 0x7;

        while (true) {
            switch (getStep(cpu)) {
                case 0 -> {
                    if (hasExt1(eaMode, eaReg)) {
                        boolean needsExt2 = hasExt2(eaMode, eaReg, 4);
                        if (!fetchJmpExtension(cpu, EXT1, !needsExt2)) return false;
                    }
                    nextStep(cpu);
                }
                case 1 -> {
                    if (hasExt2(eaMode, eaReg, 4)) {
                        if (!fetchJmpExtension(cpu, EXT2, true)) return false;
                    }
                    nextStep(cpu);
                }
                case 2 -> {
                    // IPL anchor of JMP. A `now` sample exists for ALL seven modes, but at
                    // different distances from the end: -8 for (An) and (xxx).L, -12 for
                    // (d16,An)/(xxx).W/(d16,PC), -20 for the two INDEXED modes, which have 6
                    // internal cycles between the anchor and the prefetches. It is declared ONLY
                    // for the two indexed modes, and the reason is MEASURED.
                    //
                    // The measurement: interrupt acceptance instant in the `.mainLoop: jmp (a6)`
                    // loop of CPU/Timing/JMP3, boot and angle pinned, 3096 interrupts compared
                    // one by one against the reference:
                    //   - without the anchor: delta = 46 CCK constant on 3095 of 3096, i.e. only
                    //     the boot phase: for mode 2 the default path is already exact;
                    //   - with the anchor on every mode: delta = 46/48/50/51/52, i.e. 2 to 6 CCK
                    //     of variable DELAY.
                    // So on the short modes the declaration is not needed (the default is
                    // already exact) and even does harm; on the indexed ones it is needed, with
                    // the same structure as PEA (see PeaInstruction step 1).
                    //
                    // OPEN RESIDUE, and it is not mode 2: our NOW branch stamps the anchor at an
                    // instant that on the short modes does not coincide with the reference's.
                    // As long as that holds, this per-mode declaration is the most faithful form
                    // we know how to write. Two facts already established: the JMP3 reference
                    // regenerated is BYTE-IDENTICAL to the one shipped, and the poll at S0 is
                    // load-bearing (moving it turns 245 tests of 253 red, plan section 6.1.4).
                    if (eaMode == 6 || (eaMode == 7 && eaReg == 3)) {
                        cpu.markIplAnchor(Motorola68000.IplAnchor.NOW);
                    }
                    int targetPC = calculateEA(cpu, eaMode, eaReg, 4, cpu.getOpcodeAddress() + 2, getTemp(cpu, EXT1), getTemp(cpu, EXT2), false, false);
                    setTemp(cpu, TEMP_TARGET_PC, targetPC);

                    int totalGap = 0;
                    if (eaMode == 5 || (eaMode == 7 && (eaReg == 0 || eaReg == 2))) {
                        totalGap = 2; // n
                    } else if (eaMode == 6 || (eaMode == 7 && eaReg == 3)) {
                        totalGap = 6; // n + nn
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
                    int targetPC = getTemp(cpu, TEMP_TARGET_PC);
                    cpu.setPC(targetPC);
                    cpu.startPrefetchCycle();
                    nextStep(cpu);
                }
                case 10 -> {
                    if (cpu.internalCyclesRemaining > 0) return false;
                    int targetPC = getTemp(cpu, TEMP_TARGET_PC);
                    // Pushed PC for Address Error on JMP target is targetPC - 4.
                    // Even though user suggested targetPC, test suite expects targetPC - 4 to match yacht.txt / official behavior.
                    cpu.triggerAddressError(targetPC, false, cpu.currentFCForInstruction(), true, targetPC - 4);
                    return finalizeInstruction(cpu);
                }
                case 4 -> {
                    if (!cpu.isIRDValid()) return false;
                    cpu.startPrefetchCycle();
                    nextStep(cpu);
                }
                case 5 -> {
                    if (!cpu.isIRCValid()) return false;
                    return finalizeInstructionSafe(cpu);
                }
                default -> { return finalizeInstruction(cpu); }
            }
        }
    }

    private boolean fetchJmpExtension(Motorola68000 cpu, int tempRegIndex, boolean lastExtension) {
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
    public String getDisassembly(Motorola68000 cpu) {
        int op = cpu.getIr(), eaMode = (op >> 3) & 0x7, eaReg = op & 0x7;
        return "JMP " + Disassembly.formatEA(eaMode, eaReg, 4, cpu.extensionWordAt(cpu.getOpcodeAddress() + 2), cpu.extensionWordAt(cpu.getOpcodeAddress() + 4));
    }
}

