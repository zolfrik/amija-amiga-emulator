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
 * Implementation of the LINK instruction.
 * Cycle-exact implementation matching yacht.txt: np nS ns np (16 cycles)
 */
public class LinkInstruction extends AbstractM68kInstruction {

    public static final LinkInstruction INSTANCE = new LinkInstruction();
    private LinkInstruction() {}

    private static final int DISP = 0;

    @Override
    public boolean shouldInhibitPrefetch() {
        return true;
    }

    /**
     * The IPL anchor is a `now` sample at the FIRST cycle of the instruction, before any
     * access: the pin counts as it is now and no later access moves the anchor.
     */
    @Override
    public void prepareInstruction(AbstractMotorola cpu) {
        cpu.markIplAnchor(AbstractMotorola.IplAnchor.NOW);
        super.prepareInstruction(cpu);
    }

    @Override
    public boolean executeStep(AbstractMotorola cpu) {
        int op = cpu.getIr();
        int reg = op & 0x7;

        while (true) {
            if (cpu.isRisingEdgeConsumed()) return false;
            switch (getStep(cpu)) {
                case 0 -> { // Start of instruction, fetch displacement
                    if (!cpu.isIRDValid()) return false;
                    setTemp(cpu, DISP, (short) (cpu.getIRD() & 0xFFFF));
                    cpu.consumeIRD(); // Mark extension consumed
                    cpu.startPrefetchCycle(); // Start prefetch for word after extension (np)
                    nextStep(cpu);
                    continue;
                }
                case 1 -> { // Write Register High (nS) to SP-4
                    if (!cpu.isBusCycleIdle()) return false;
                    int val = cpu.getA(reg, 4);
                    if (reg == 7) val -= 4; // M68k quirk: LINK A7 pushes the decremented SP
                    cpu.scheduleWriteWord(cpu.getA7() - 4, (val >>> 16) & 0xFFFF);
                    nextStep(cpu);
                    return false;
                }
                case 2 -> { // Write Register Low (ns) to SP-2
                    if (!cpu.isBusCycleIdle()) return false;
                    int val = cpu.getA(reg, 4);
                    if (reg == 7) val -= 4;
                    cpu.scheduleWriteWord(cpu.getA7() - 2, val & 0xFFFF);
                    nextStep(cpu);
                    return false;
                }
                case 3 -> { // Update registers + Refill (np) + finalize (np is the last bus cycle: seam 0)
                    if (!cpu.isBusCycleIdle()) return false;
                    cpu.setA7(cpu.getA7() - 4);
                    cpu.setA(reg, cpu.getA7());
                    cpu.setA7(cpu.getA7() + getTemp(cpu, DISP));
                    refillPipeline(cpu); // Start prefetch for word after that (np)
                    return finalizeInstructionSafe(cpu);
                }
                default -> { return finalizeInstructionSafe(cpu); }
            }
        }
    }

    @Override
    public String getDisassembly(AbstractMotorola cpu) {
        int op = cpu.getIr();
        int disp = (short) cpu.extensionWordAt(cpu.getOpcodeAddress() + 2);
        return "LINK A" + (op & 7) + ", #$" + Disassembly.formatHex(disp, 4);
    }
}

