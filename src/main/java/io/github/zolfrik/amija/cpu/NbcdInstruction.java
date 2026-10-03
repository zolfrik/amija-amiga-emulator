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

public class NbcdInstruction extends AbstractM68kInstruction {

    public static final NbcdInstruction INSTANCE = new NbcdInstruction();

    private NbcdInstruction() {}

    private static final int EXT1 = 0;
    private static final int EXT2 = 1;
    private static final int ADDR = 5; // Changed from 2 to 5
    private static final int OPERAND = 3;
    private static final int RESULT = 4;

    @Override
    public boolean executeStep(Motorola68000 cpu) {
        int op = cpu.getIr();
        int eaMode = (op >> 3) & 0x7;
        int eaReg = op & 0x7;

        while (true) {
            if (cpu.isRisingEdgeConsumed()) return false;
            switch (getStep(cpu)) {
                case 0:
                    // EA setup and initial timing
                    if (eaMode == 0) {
                        // Dn: np n
                        refillPipeline(cpu);
                        setStep(cpu, 10); // Skip to Dn logic
                        return false;
                    } else if (eaMode == 4) {
                        // -(An): n nr np nw
                        indexedGap2(cpu);
                    } else if (eaMode == 6 || (eaMode == 7 && eaReg == 3)) {
                        // (d8, An, Xn): n np nr np nw
                        // Yacht says n np nr. calculateEA/calculateIndexedAddress will add the gap AFTER np.
                        // So we add the initial n here.
                        indexedGap2(cpu);
                    }
                    nextStep(cpu);
                    continue;

                case 1:
                    // Fetch extension words if any
                    if (eaMode == 5 || eaMode == 6 || (eaMode == 7 && eaReg <= 3)) {
                        if (!fetchExtension(cpu, EXT1)) return false;
                    }
                    nextStep(cpu);
                    continue;

                case 2:
                    if (eaMode == 7 && eaReg == 1) {
                        if (!fetchExtension(cpu, EXT2)) return false;
                    }
                    nextStep(cpu);
                    continue;

                case 3:
                    // Calculate address and schedule read
                    // For mode 6/7.3, we added the gap manually in Step 0 to match n np nr sequence.
                    // So we call calculateEA with includeGap = false.
                    boolean includeGap = !(eaMode == 6 || (eaMode == 7 && eaReg == 3));
                    int addr = calculateEA(cpu, eaMode, eaReg, 1, cpu.getOpcodeAddress() + 2, getTemp(cpu, EXT1), getTemp(cpu, EXT2), true, includeGap);
                    setTemp(cpu, ADDR, addr);
                    cpu.scheduleRead(addr, 1);
                    nextStep(cpu);
                    return false;

                case 4:
                    // Latch operand, calculate, refill, schedule write
                    setTemp(cpu, OPERAND, cpu.getLatchedByte());
                    calculateNbcd(cpu, getTemp(cpu, OPERAND));
                    refillPipeline(cpu);
                    nextStep(cpu);
                    return false;

                case 5:
                    if (!cpu.isBusCycleIdle()) return false;
                    cpu.scheduleWrite(getTemp(cpu, ADDR), getTemp(cpu, RESULT), 1);
                    return finalizeInstructionSafe(cpu);

                case 10:
                    // Dn special case: np n
                    if (!cpu.isBusCycleIdle()) return false;
                    calculateNbcd(cpu, cpu.getD(eaReg, 1));
                    cpu.setD(eaReg, getTemp(cpu, RESULT), 1);
                    // NOTE (Phase 4): terminal internal-idle cycle; finalizing here breaks the
                    // JSON 'n' count, so the +1 seam is left as a documented residual.
                    cpu.idleCycles(2);
                    nextStep(cpu);
                    return false;
                case 11:
                    return finalizeInstructionSafe(cpu);

                default:
                    return finalizeInstructionSafe(cpu);
            }
        }
    }

    private void calculateNbcd(Motorola68000 cpu, int val) {
        int x = cpu.x ? 1 : 0;
        int binaryDiff = (0 - val - x) & 0xFF;
        int t = 0 - (val & 0x0F) - x;
        int res = 0 - val - x;
        boolean highBorrow = (res < 0);

        if (t < 0) res -= 6;
        boolean carry = (res < 0);
        if (highBorrow) res -= 0x60;

        int resultByte = res & 0xFF;

        // V flag quirk: (binaryDiff & (~resultByte) & 0x80) != 0
        cpu.v = (binaryDiff & (~resultByte) & 0x80) != 0;
        cpu.x = cpu.c = carry;

        // Z flag is persistent
        if (resultByte != 0) cpu.z = false;

        // N is bit 7 of result
        cpu.n = (resultByte & 0x80) != 0;

        setTemp(cpu, RESULT, resultByte);
    }

    @Override
    public boolean shouldInhibitPrefetch() {
        return true;
    }

    @Override
    public String getDisassembly(Motorola68000 cpu) {
        int op = cpu.getIr();
        return "NBCD " + Disassembly.formatEA((op >> 3) & 0x7, op & 0x7, 1, cpu.extensionWordAt(cpu.getOpcodeAddress() + 2), cpu.extensionWordAt(cpu.getOpcodeAddress() + 4));
    }
}

