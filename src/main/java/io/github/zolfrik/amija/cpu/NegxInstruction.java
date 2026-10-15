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
 * Implementation of the NEGX instruction.
 */
public class NegxInstruction extends AbstractM68kInstruction {

    public static final NegxInstruction INSTANCE = new NegxInstruction();
    private NegxInstruction() {}

    private static final int EXT1 = 0;
    private static final int EXT2 = 1;
    private static final int ADDR = 2;
    private static final int VAL = 3;

    @Override
    public boolean shouldInhibitPrefetch() {
        return true;
    }

    @Override
    public boolean executeStep(AbstractMotorola cpu) {
        int op = cpu.getIr();
        int s = (op >> 6) & 3, size = (s == 0) ? 1 : (s == 1) ? 2 : 4;
        int eaMode = (op >> 3) & 7, eaReg = op & 7;

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
                }
                case 1 -> {
                    if (hasExt1(eaMode, eaReg)) {
                        if (!fetchExtension(cpu, EXT1)) return false;
                    }
                    nextStep(cpu);
                }
                case 2 -> {
                    if (hasExt2(eaMode, eaReg, size)) {
                        if (!fetchExtension(cpu, EXT2)) return false;
                    }
                    nextStep(cpu);
                }
                case 3 -> {
                    if (eaMode == 0) { // Dn
                        int val = cpu.getD(eaReg, size);
                        int res = calculateSubx(cpu, val, 0, size);
                        cpu.setD(eaReg, res, size);
                        refillPipeline(cpu);
                        if (size == 4) {
                            setStep(cpu, 10);
                            return false;
                        } else {
                            return finalizeInstructionSafe(cpu);
                        }
                    }
                    int addr = calculateEA(cpu, eaMode, eaReg, size, cpu.getOpcodeAddress() + 2, getTemp(cpu, EXT1), getTemp(cpu, EXT2), true, false);
                    if (cpu.getCpuState() == AbstractMotorola.CpuState.EXCEPTION) return false;
                    setTemp(cpu, ADDR, addr);

                    if (size == 4) {
                        cpu.scheduleRead(addr, 2);
                    } else {
                        cpu.scheduleRead(addr, size);
                    }
                    nextStep(cpu);
                    return false;
                }
                case 4 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    if (cpu.getCpuState() == AbstractMotorola.CpuState.EXCEPTION) return false;
                    if (size == 4) {
                        setTemp(cpu, VAL, cpu.getLatchedBusData() << 16);
                        cpu.scheduleRead(getTemp(cpu, ADDR) + 2, 2);
                        nextStep(cpu);
                        return false;
                    }
                    int val = (size == 1) ? cpu.getLatchedByte() : cpu.getLatchedBusData();
                    setTemp(cpu, VAL, calculateSubx(cpu, val, 0, size));
                    setStep(cpu, 7);
                    continue;
                }
                case 5 -> { // Only for .L
                    if (!cpu.isBusCycleIdle()) return false;
                    if (cpu.getCpuState() == AbstractMotorola.CpuState.EXCEPTION) return false;
                    int lsw = cpu.getLatchedBusData() & 0xFFFF;
                    int val = getTemp(cpu, VAL) | lsw;
                    setTemp(cpu, VAL, calculateSubx(cpu, val, 0, 4));
                    nextStep(cpu);
                }
                case 6 -> {
                    nextStep(cpu);
                }
                case 7 -> { // np refill
                    refillPipeline(cpu);
                    nextStep(cpu);
                    return false;
                }
                case 8 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    if (size == 4) {
                        cpu.scheduleWrite(getTemp(cpu, ADDR) + 2, getTemp(cpu, VAL) & 0xFFFF, 2);
                        nextStep(cpu);
                        return false;
                    }
                    // Overlapped prefetch (Phase 3): finalize with the .b/.w write (seam 0).
                    // See docs/cpu-overlapped-prefetch-rework-plan.md, Phase 3.
                    cpu.scheduleWrite(getTemp(cpu, ADDR), getTemp(cpu, VAL), size);
                    return finalizeInstructionSafe(cpu);
                }
                case 9 -> { // .L memory MSW write + finalize (last bus cycle: seam 0)
                    if (!cpu.isBusCycleIdle()) return false;
                    cpu.scheduleWrite(getTemp(cpu, ADDR), (getTemp(cpu, VAL) >>> 16) & 0xFFFF, 2);
                    return finalizeInstructionSafe(cpu);
                }
                case 10 -> { // Trailing gap n for .L Dn
                    if (!cpu.isBusCycleIdle()) return false;
                    cpu.idleCycles(2);
                    nextStep(cpu);
                    return false;
                }
                case 11 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    return finalizeInstructionSafe(cpu);
                }
                default -> { return finalizeInstructionSafe(cpu); }
            }
        }
    }

    @Override
    public String getDisassembly(AbstractMotorola cpu) {
        int op = cpu.getIr();
        int s = (op >> 6) & 3, size = (s == 0) ? 1 : (s == 1) ? 2 : 4;
        return "NEGX" + Disassembly.getSizeSuffix(size) + " " + Disassembly.formatEA((op >> 3) & 7, op & 7, size, cpu.extensionWordAt(cpu.getOpcodeAddress() + 2), cpu.extensionWordAt(cpu.getOpcodeAddress() + 4));
    }
}

