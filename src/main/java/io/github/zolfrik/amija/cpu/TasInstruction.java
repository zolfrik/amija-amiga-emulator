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
 * Implementation of the TAS (Test and Set) instruction.
 * Timing based on yacht.txt:
 * Dn: 4(1/0) -> np
 * Memory: 10(1/1) + EA -> nr | n nw np
 */
public class TasInstruction extends AbstractM68kInstruction {

    public static final TasInstruction INSTANCE = new TasInstruction();
    private TasInstruction() {}

    private static final int EXT1 = 0;
    private static final int EXT2 = 1;
    private static final int ADDR = 2;
    private static final int VAL = 3;

    @Override
    public boolean shouldInhibitPrefetch() {
        return true;
    }

    @Override
    public void prepareInstruction(Motorola68000 cpu) {
        super.prepareInstruction(cpu);
    }

    @Override
    public boolean executeStep(Motorola68000 cpu) {
        int op = cpu.getIr();
        int eaMode = (op >> 3) & 7, eaReg = op & 7;

        while (true) {
            if (cpu.isRisingEdgeConsumed()) return false;
            switch (getStep(cpu)) {
                case 0 -> {
                    if (eaMode == 4 || eaMode == 6 || (eaMode == 7 && eaReg == 3)) {
                        indexedGap2(cpu);
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
                    if (hasExt2(eaMode, eaReg, 1)) {
                        if (!fetchExtension(cpu, EXT2)) return false;
                    }
                    nextStep(cpu);
                    continue;
                }
                case 3 -> {
                    if (eaMode == 0) { // Dn
                        int val = cpu.getD(eaReg, 1);
                        updateFlags(cpu, val);
                        cpu.setD(eaReg, val | 0x80, 1);
                        cpu.startPrefetchCycle(); // np
                        return finalizeInstructionSafe(cpu);
                    } else {
                        int addr = calculateEA(cpu, eaMode, eaReg, 1, cpu.getOpcodeAddress() + 2, getTemp(cpu, EXT1), getTemp(cpu, EXT2), true, false);
                        setTemp(cpu, ADDR, addr);
                        cpu.scheduleRmwReadByte(addr); // nr
                        nextStep(cpu);
                        return false;
                    }
                }
                case 4 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    setTemp(cpu, VAL, cpu.getLatchedByte());
                    updateFlags(cpu, getTemp(cpu, VAL));
                    cpu.idleCycles(2); // n gap
                    nextStep(cpu);
                    return false;
                }
                case 5 -> {
                    // TAS su target chip-bus: il ciclo RMW indivisibile del 68000
                    // (AS tenuto alto attraverso read+write) NON può attendere il
                    // bus. Se nel cck in cui la write dovrebbe partire lo slot è
                    // occupato da un DMA, la fase WRITE viene PERSA (4 clock di
                    // idle, memoria intatta); se lo slot è libero la write avviene
                    // normalmente. WinUAE gencpu i_TAS 68000-ce: `if
                    // (is_cycle_ce(srca)) do_cycles(4); else put_byte(...)`, dove
                    // is_cycle_ce = target chip/custom && `read_rga_out()->alloc`.
                    // Root G3/TAS1-TAS2: noi scrivevamo SEMPRE → $080 (verde) su
                    // COLOR00 anche dove WinUAE perde la write sotto contesa.
                    if (cpu.isChipBusTarget(getTemp(cpu, ADDR)) && cpu.isChipBusBusyNow()) {
                        cpu.releaseRmwLock();
                        cpu.idleCycles(4);
                        nextStep(cpu);
                        return false;
                    }
                    cpu.scheduleRmwWriteByte(getTemp(cpu, ADDR), getTemp(cpu, VAL) | 0x80); // nw
                    nextStep(cpu);
                    return false;
                }
                case 6 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    cpu.startPrefetchCycle(); // np
                    return finalizeInstructionSafe(cpu);
                }
                default -> { return finalizeInstruction(cpu); }
            }
        }
    }

    private void updateFlags(Motorola68000 cpu, int val) {
        int sr = cpu.getSR() & 0xFFF0;
        if ((val & 0xFF) == 0) sr |= 0x04;
        if ((val & 0x80) != 0) sr |= 0x08;
        cpu.writeSR(sr, false);
    }

    @Override
    public String getDisassembly(Motorola68000 cpu) {
        int op = cpu.getIr();
        return "TAS " + Disassembly.formatEA((op >> 3) & 7, op & 7, 1, cpu.extensionWordAt(cpu.getOpcodeAddress() + 2), cpu.extensionWordAt(cpu.getOpcodeAddress() + 4));
    }
}

