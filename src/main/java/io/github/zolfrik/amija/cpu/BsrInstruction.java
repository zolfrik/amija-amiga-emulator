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
 * BSR: Branch to Subroutine
 */
public class BsrInstruction extends AbstractM68kInstruction {

    public static final BsrInstruction INSTANCE = new BsrInstruction();

    private BsrInstruction() {}

    @Override
    public boolean shouldInhibitPrefetch() {
        return true;
    }

    @Override
    public boolean executeStep(AbstractMotorola cpu) {
        int op = cpu.getIr();
        int disp8 = (byte) (op & 0xFF);

        while (true) {
            switch (getStep(cpu)) {
                case 0 -> {
                    // BSR (18 cycles: n nS ns np np)
                    int displacement;
                    int returnPC;
                    if (disp8 == 0) {
                        // Word displacement (16-bit). prepareInstruction already moved it to IRD.
                        if (!cpu.isIRDValid()) return false;
                        displacement = (short) cpu.getIRD();
                        cpu.consumeIRD();
                        returnPC = cpu.getOpcodeAddress() + 4;
                    } else {
                        // Short displacement (8-bit)
                        displacement = disp8;
                        returnPC = cpu.getOpcodeAddress() + 2;
                    }
                    setTemp(cpu, 0, displacement);
                    setTemp(cpu, 1, returnPC);

                    cpu.idleCycles(2); // 'n'
                    nextStep(cpu);
                    return false;
                }
                case 1 -> {
                    if (cpu.internalCyclesRemaining > 0) return false;
                    // Push PC high word at SSP-4
                    int returnPC = getTemp(cpu, 1);
                    cpu.scheduleWriteWord(cpu.getA7() - 4, (returnPC >> 16) & 0xFFFF);
                    nextStep(cpu);
                    return false;
                }
                case 2 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    // Push PC low word at SSP-2
                    int returnPC = getTemp(cpu, 1);
                    cpu.scheduleWriteWord(cpu.getA7() - 2, returnPC & 0xFFFF);
                    nextStep(cpu);
                    return false;
                }
                case 3 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    cpu.setA7(cpu.getA7() - 4);
                    int displacement = getTemp(cpu, 0);
                    int targetPC = cpu.getOpcodeAddress() + 2 + displacement;
                    if ((targetPC & 1) != 0) {
                        setTemp(cpu, 15, targetPC);
                        setStep(cpu, 30);
                        continue;
                    }
                    cpu.setPC(targetPC);
                    cpu.flushPrefetch();
                    cpu.startPrefetchCycle(); // First 'np' at target
                    nextStep(cpu);
                    return false;
                }
                case 4 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    // BSR ($6100): the `next` sample sits BETWEEN the two prefetches at the
                    // target; the two pushes of the return address are not an anchor.
                    cpu.markIplAnchor(AbstractMotorola.IplAnchor.NEXT);
                    cpu.startPrefetchCycle(); // Second 'np' at target+2
                    return finalizeInstructionSafe(cpu);
                }
                case 30 -> { // Address Error handler
                    if (cpu.internalCyclesRemaining > 0) return false;
                    int targetPC = getTemp(cpu, 15);
                    cpu.triggerAddressError(targetPC, false, cpu.currentFCForInstruction(), true, targetPC - 4);
                    return finalizeInstruction(cpu);
                }
                default -> { return finalizeInstruction(cpu); }
            }
        }
    }

    @Override
    public String getDisassembly(AbstractMotorola cpu) {
        int op = cpu.getIr();
        int disp8 = (byte) (op & 0xFF);
        int displacement = (disp8 == 0) ? (short) cpu.extensionWordAt(cpu.getOpcodeAddress() + 2) : disp8;
        return "BSR $" + Integer.toHexString(cpu.getOpcodeAddress() + 2 + displacement);
    }
}

