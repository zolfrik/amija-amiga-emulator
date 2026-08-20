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
                    // ⛔ NIENTE markIplAnchor QUI. WinUAE emette `ipl_fetch_now()` subito dopo
                    // il calcolo dell'EA anche per JMP (op_4ed0/op_4ef9 _14_ff), esattamente
                    // come per JSR — ma portarlo manda in ROSSO vAmigaTS CPU/Timing/JMP3
                    // (4060 px su 204060), che e' un riferimento generato da WinUAE e il cui
                    // loop `.mainLoop: jmp (a6)` misura proprio questo. JSR e LINK, portati
                    // nello stesso passo, restano verdi. Falsificazione registrata in
                    // docs/vamigats-paula-audio-plan.md §6.1.9: prima di riprovarci serve
                    // misurare l'istante di accettazione nel loop di JMP3 sui due lati
                    // (WINUAE_DOINTLOG contro una sonda a master-cycle), non dedurlo.
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

