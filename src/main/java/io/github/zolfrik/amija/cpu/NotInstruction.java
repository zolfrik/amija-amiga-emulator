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
 * Implementation of the NOT instruction.
 */
public class NotInstruction extends AbstractM68kInstruction {

    public static final NotInstruction INSTANCE = new NotInstruction();
    private NotInstruction() {}

    private static final int EXT1 = 0;
    private static final int EXT2 = 1;
    private static final int ADDR = 2;
    private static final int VAL = 3;

    @Override
    public boolean shouldInhibitPrefetch() {
        return true;
    }

    @Override
    public boolean executeStep(Motorola68000 cpu) {
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
                    if (!cpu.isBusCycleIdle()) return false;
                    if (eaMode == 0) { // Dn
                        int val = cpu.getD(eaReg, size);
                        int res = updateFlags(cpu, ~val, size);
                        cpu.setD(eaReg, res, size);
                        refillPipeline(cpu);
                        if (size == 4) {
                            setStep(cpu, 10);
                            return false;
                        }
                        return finalizeInstructionSafe(cpu);
                    }
                    int addr = calculateEA(cpu, eaMode, eaReg, size, cpu.getOpcodeAddress() + 2, getTemp(cpu, EXT1), getTemp(cpu, EXT2), true, false);
                    if (cpu.getCpuState() == Motorola68000.CpuState.EXCEPTION) return false;
                    setTemp(cpu, ADDR, addr);
                    if (size == 4) cpu.scheduleRead(addr, 2);
                    else cpu.scheduleRead(addr, size);
                    nextStep(cpu);
                    return false;
                }
                case 4 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    if (cpu.getCpuState() == Motorola68000.CpuState.EXCEPTION) return false;

                    if (size == 4) {
                        setTemp(cpu, VAL, cpu.getLatchedBusData() << 16);
                        cpu.scheduleRead(getTemp(cpu, ADDR) + 2, 2);
                        nextStep(cpu);
                        return false;
                    }

                    int val = (size == 1) ? cpu.getLatchedByte() : cpu.getLatchedBusData();
                    setTemp(cpu, VAL, updateFlags(cpu, ~val, size));
                    refillPipeline(cpu);
                    nextStep(cpu, 6);
                    continue;
                }
                case 5 -> { // Only for .L memory read logic
                    if (!cpu.isBusCycleIdle()) return false;
                    int lsw = cpu.getLatchedBusData() & 0xFFFF;
                    int val = getTemp(cpu, VAL) | lsw;
                    setTemp(cpu, VAL, updateFlags(cpu, ~val, 4));
                    refillPipeline(cpu);
                    nextStep(cpu);
                }
                case 6 -> { // Write Result
                    if (!cpu.isBusCycleIdle()) return false;
                    if (size == 4) {
                        cpu.scheduleWriteWord(getTemp(cpu, ADDR) + 2, getTemp(cpu, VAL) & 0xFFFF);
                        nextStep(cpu);
                        return false;
                    }
                    // Overlapped prefetch (Phase 3): the .b/.w write is the LAST bus cycle —
                    // finalize in this same step so it overlaps the write (seam 0) instead of
                    // deferring to step 11 (which wasted 1 mc). See
                    // docs/cpu-overlapped-prefetch-rework-plan.md §Phase 3.
                    cpu.scheduleWrite(getTemp(cpu, ADDR), getTemp(cpu, VAL), size);
                    return finalizeInstructionSafe(cpu);
                }
                case 7 -> { // .L memory MSW write + finalize (last bus cycle → seam 0)
                    if (!cpu.isBusCycleIdle()) return false;
                    cpu.scheduleWriteWord(getTemp(cpu, ADDR), (getTemp(cpu, VAL) >>> 16) & 0xFFFF);
                    return finalizeInstructionSafe(cpu);
                }
                case 10 -> { // .L Dn gap: n
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

    private void nextStep(Motorola68000 cpu, int step) {
        cpu.setInstructionStep(step);
    }

    private int updateFlags(Motorola68000 cpu, int val, int size) {
        int sr = cpu.getSR() & 0xFFF0;
        int mask = (size == 1) ? 0xFF : (size == 2) ? 0xFFFF : 0xFFFFFFFF;
        int msb = (size == 1) ? 0x80 : (size == 2) ? 0x8000 : 0x80000000;
        int res = val & mask;
        if (res == 0) sr |= 0x04;
        if ((res & msb) != 0) sr |= 0x08;
        cpu.writeSR(sr, false);
        return res;
    }

    @Override
    public String getDisassembly(Motorola68000 cpu) {
        int op = cpu.getIr();
        int s = (op >> 6) & 3, size = (s == 0) ? 1 : (s == 1) ? 2 : 4;
        return "NOT" + Disassembly.getSizeSuffix(size) + " " + Disassembly.formatEA((op >> 3) & 7, op & 7, size, cpu.extensionWordAt(cpu.getOpcodeAddress() + 2), cpu.extensionWordAt(cpu.getOpcodeAddress() + 4));
    }
}

