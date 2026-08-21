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
                    // Ancora IPL di JMP. gencpu emette `ipl_fetch_now()` per TUTTI e sette i
                    // modi, ma a distanze diverse dalla fine: -8 per (An) e (xxx).L, -12 per
                    // (d16,An)/(xxx).W/(d16,PC), -20 per i due INDICIZZATI, che hanno 6 cicli
                    // interni fra l'ancora e le prefetch. Noi la dichiariamo SOLO per i due
                    // indicizzati, e la ragione e' MISURATA, non dedotta.
                    //
                    // La misura (2026-08-21, il passo che il piano §6.1.4 prescriveva): istante
                    // di accettazione dell'interrupt nel loop `.mainLoop: jmp (a6)` di
                    // CPU/Timing/JMP3, `WINUAE_DOINTLOG` contro la sonda gemella, avvio e
                    // angolo fissati, 3096 interrupt confrontati uno per uno:
                    //   • senza ancora: Δ = **46 cck costanti su 3095 su 3096** — cioe' solo la
                    //     fase di boot. Per il modo 2 il nostro default RIPRODUCE ESATTAMENTE
                    //     l'oracolo, benche' l'audit lo descriva come `next @-4` contro `now @-8`;
                    //   • con l'ancora su tutti i modi: Δ = 46/48/50/51/52, cioe' da 2 a 6 cck
                    //     di RITARDO, variabile. Il nostro `markIplAnchor(NOW)` in questo punto
                    //     NON e' la `ipl_fetch_now()` di WinUAE.
                    // Da cui: sui modi corti la dichiarazione non serve (il default e' gia'
                    // esatto) e per giunta fa danno; sugli indicizzati serve, ed e' la stessa
                    // struttura di PEA (op_4870/op_487b, vedi PeaInstruction step 1).
                    //
                    // ⚠️ RESIDUO APERTO, e non e' il modo 2: e' che il nostro ramo NOW timbra
                    // l'ancora in un istante che non coincide con quello dell'oracolo sui modi
                    // corti. Finche' resta cosi', questa dichiarazione per-modo e' la forma piu'
                    // fedele che sappiamo scrivere, non la trascrizione di gencpu.
                    // ⛔ Altri due fatti gia' stabiliti, per non rifare la strada: il .raw di
                    // JMP3 rigenerato con WinUAE e' BYTE-IDENTICO a quello distribuito (la
                    // contraddizione non era fra emulatori), e il POLL a S0 e' load-bearing
                    // (spostarlo manda 245 test su 253 in rosso, piano §6.1.4).
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

