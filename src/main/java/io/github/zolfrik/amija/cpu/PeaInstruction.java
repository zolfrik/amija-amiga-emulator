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

public class PeaInstruction extends AbstractM68kInstruction {
    public static final PeaInstruction INSTANCE = new PeaInstruction();
    private PeaInstruction() {}
    private static final int EXT1 = 0, EXT2 = 1;
    @Override
    public boolean executeStep(Motorola68000 cpu) {
        int op = cpu.getIr();
        int eaMode = (op >> 3) & 0x7;
        int eaReg = op & 0x7;

        while (true) {
            if (cpu.isRisingEdgeConsumed()) return false;

            switch (getStep(cpu)) {
                case 0 -> {
                    // Initial gap for indexed modes (Mode 6 and 7.3)
                    if (eaMode == 6 || (eaMode == 7 && eaReg == 3)) {
                        cpu.idleCycles(2); // n
                        nextStep(cpu);
                        return false;
                    }
                    nextStep(cpu);
                }
                case 1 -> {
                    // WinUAE op_4870/op_487b _14_ff: `do_cycles_ce000_internal(2)` e SUBITO
                    // DOPO `ipl_fetch_now()`, prima del calcolo dell'EA. L'ancora di PEA e'
                    // PER MODO, come in gencpu: solo i due modi INDICIZZATI ancorano NOW,
                    // gli altri quattro NEXT alla prefetch finale (step 4).
                    // ⚠️ Va dichiarata QUI e non allo step 0: markIplAnchor timbra il ciclo
                    // CORRENTE, e allo step 0 i 2 cicli interni non sono ancora trascorsi —
                    // l'ancora finirebbe 2 clock in anticipo, che si misura (PEA3/PEA6).
                    if (eaMode == 6 || (eaMode == 7 && eaReg == 3)) {
                        cpu.markIplAnchor(Motorola68000.IplAnchor.NOW);
                    }
                    if (hasExt1(eaMode, eaReg)) {
                        if (!fetchExtension(cpu, EXT1)) return false;
                        if (eaMode == 6 || (eaMode == 7 && eaReg == 3)) {
                            nextStep(cpu);
                        } else {
                            setStep(cpu, 3);
                        }
                        return false;
                    }
                    setStep(cpu, 4);
                }
                case 2 -> {
                    // Second gap for indexed modes
                    cpu.idleCycles(2); // n
                    nextStep(cpu);
                    return false;
                }
                case 3 -> {
                    if (hasExt2(eaMode, eaReg, 4)) {
                        if (!fetchExtension(cpu, EXT2)) return false;
                    }
                    nextStep(cpu);
                }
                case 4 -> {
                    // Pipeline refill (np) for non-absolute modes
                    if (eaMode == 2 || eaMode == 5 || eaMode == 6 || (eaMode == 7 && eaReg == 2) || (eaMode == 7 && eaReg == 3)) {
                        // WinUAE op_4850_14_ff: qui la prefetch finale precede le push, e
                        // l'ancora e' lei (`ipl_fetch_next_pre()`); le push non ancorano.
                        // ⛔ NON per i due modi INDICIZZATI: op_4870/op_487b emettono
                        // `ipl_fetch_now()` in testa e l'hanno gia' dichiarata allo step 0.
                        // Ri-dichiarare qui la sovrascriverebbe (markIplAnchor riazzera).
                        if (eaMode != 6 && !(eaMode == 7 && eaReg == 3)) {
                            cpu.markIplAnchor(Motorola68000.IplAnchor.NEXT);
                        }
                        refillPipeline(cpu);
                        nextStep(cpu);
                        return false;
                    }
                    nextStep(cpu);
                }
                case 5 -> {
                    // Push High Word (MSW) - nS
                    if (!cpu.isBusCycleIdle()) return false;
                    int addr = calculateEA(cpu, eaMode, eaReg, 4, cpu.getOpcodeAddress() + 2, getTemp(cpu, EXT1), getTemp(cpu, EXT2), false, false);
                    setTemp(cpu, 2, addr);
                    int ssp = cpu.getA7();
                    cpu.setA7(ssp - 4);
                    cpu.scheduleWrite(ssp - 4, (addr >>> 16) & 0xFFFF, 2);
                    nextStep(cpu);
                    return false;
                }
                case 6 -> {
                    // Push Low Word (LSW) - ns
                    if (!cpu.isBusCycleIdle()) return false;
                    int addr = getTemp(cpu, 2);
                    cpu.scheduleWrite(cpu.getA7() + 2, addr & 0xFFFF, 2);
                    nextStep(cpu);
                    return false;
                }
                case 7 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    // Pipeline refill (np) for absolute modes
                    if (eaMode == 7 && (eaReg == 0 || eaReg == 1)) {
                        // WinUAE op_4878_14_ff: qui le push PRECEDONO la prefetch finale, e
                        // `ipl_fetch_next()` sta fra le due — l'ancora e' la prefetch.
                        cpu.markIplAnchor(Motorola68000.IplAnchor.NEXT);
                        refillPipeline(cpu);
                    }
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

    @Override public String getDisassembly(Motorola68000 cpu) {
        int op = cpu.getIr(); return "PEA " + Disassembly.formatEA((op >> 3) & 0x7, op & 0x7, 4, cpu.extensionWordAt(cpu.getOpcodeAddress() + 2), cpu.extensionWordAt(cpu.getOpcodeAddress() + 4));
    }
}

