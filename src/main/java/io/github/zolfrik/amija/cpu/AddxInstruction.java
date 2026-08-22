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
 * Implementation of the ADDX instruction.
 * Cycle-exact implementation matching yacht.txt
 */
public class AddxInstruction extends AbstractM68kInstruction {

    public static final AddxInstruction INSTANCE = new AddxInstruction();

    private AddxInstruction() {}

    private static final int SRC_VAL = 0;
    private static final int DST_VAL = 1;
    private static final int ADDR_X = 2;
    private static final int ADDR_Y = 3;

    @Override
    public boolean executeStep(Motorola68000 cpu) {
        int op = cpu.getIr();
        int regX = (op >> 9) & 0x7;
        int sizeBit = (op >> 6) & 0x3;
        int size = (sizeBit == 0) ? 1 : (sizeBit == 1) ? 2 : 4;
        int regY = op & 0x7;
        boolean isMemory = (op & 0x8) != 0;

        while (true) {
            if (cpu.isRisingEdgeConsumed()) return false;
            switch (getStep(cpu)) {
                case 0 -> {
                    if (isMemory) {
                        // Le forme memoria-memoria dichiarano il proprio offset del PC di gruppo 0:
                        // nel generato di WinUAE il ramo di guasto porta sempre due byte in piu'
                        // della regola generale, per entrambe le taglie (op_9188/op_d188 _14_ff).
                        cpu.markGroup0EaDelta(2);
                        indexedGap2(cpu); // n
                        nextStep(cpu);
                        continue;
                    } else {
                        // Register mode: np (or np nn for .L)
                        int result = calculateAddx(cpu, cpu.getD(regY, size), cpu.getD(regX, size), size);
                        cpu.setD(regX, result, size);
                        refillPipeline(cpu); // np
                        if (size == 4) {
                            setStep(cpu, 10);
                            return false;
                        }
                        return finalizeInstructionSafe(cpu);
                    }
                }
                case 1 -> { // Memory mode: Read source LSW (or byte/word)
                    int dec = (regY == 7 && size == 1) ? 2 : (size == 4 ? 2 : size);
                    cpu.setA(regY, cpu.getA(regY, 4) - dec);
                    int addrY = cpu.getA(regY, 4);
                    setTemp(cpu, ADDR_Y, addrY);
                    // WinUAE op_9108/op_d108 _14_ff (.B/.W -(An),-(An)): `ipl_fetch_now()` sta
                    // PRIMA della lettura sorgente, non sulla lettura destinazione. Per la .L
                    // invece cade in mezzo alla lettura long della destinazione (case 4).
                    if (size != 4) {
                        cpu.markIplAnchor(Motorola68000.IplAnchor.NOW);
                    }
                    cpu.scheduleRead(addrY, size == 4 ? 2 : size);
                    nextStep(cpu);
                    return false;
                }
                case 2 -> { // Memory mode: Read source MSW (if .L) or move to dst read
                    int val = (size == 1) ? cpu.getLatchedByte() : cpu.getLatchedBusData() & 0xFFFF;
                    if (size == 4) {
                        setTemp(cpu, SRC_VAL, val); // Store LSW
                        cpu.setA(regY, cpu.getA(regY, 4) - 2);
                        int addrY = cpu.getA(regY, 4);
                        cpu.scheduleRead(addrY, 2);
                        nextStep(cpu);
                        return false;
                    } else {
                        setTemp(cpu, SRC_VAL, val);
                        setStep(cpu, 3);
                        continue;
                    }
                }
                case 3 -> { // Memory mode: Read destination LSW (or byte/word)
                    if (size == 4) {
                        int msw = cpu.getLatchedBusData() & 0xFFFF;
                        int lsw = getTemp(cpu, SRC_VAL);
                        setTemp(cpu, SRC_VAL, (msw << 16) | lsw);
                    }
                    int dec = (regX == 7 && size == 1) ? 2 : (size == 4 ? 2 : size);
                    cpu.setA(regX, cpu.getA(regX, 4) - dec);
                    int addrX = cpu.getA(regX, 4);
                    setTemp(cpu, ADDR_X, addrX);
                    cpu.scheduleRead(addrX, size == 4 ? 2 : size);
                    nextStep(cpu);
                    return false;
                }
                case 4 -> { // Memory mode: Read destination MSW (if .L) or calculate & write
                    int val = (size == 1) ? cpu.getLatchedByte() : cpu.getLatchedBusData() & 0xFFFF;
                    if (size == 4) {
                        setTemp(cpu, DST_VAL, val); // Store LSW
                        cpu.setA(regX, cpu.getA(regX, 4) - 2);
                        int addrX = cpu.getA(regX, 4);
                        setTemp(cpu, ADDR_X, addrX);
                        // WinUAE op_9188/op_d188 _14_ff: `ipl_fetch_now()` sta FRA le due word
                        // della lettura destinazione — l'unica ancora del modello che cade
                        // dentro un accesso long.
                        cpu.markIplAnchor(Motorola68000.IplAnchor.NOW);
                        cpu.scheduleRead(addrX, 2);
                        nextStep(cpu);
                        return false;
                    } else {
                        int result = calculateAddx(cpu, getTemp(cpu, SRC_VAL), val, size);
                        refillPipeline(cpu); // np
                        cpu.scheduleWrite(getTemp(cpu, ADDR_X), result, size); // nw
                        return finalizeInstructionSafe(cpu);
                    }
                }
                case 5 -> { // Memory mode .L: Write LSW
                    int msw = cpu.getLatchedBusData() & 0xFFFF;
                    int lsw = getTemp(cpu, DST_VAL);
                    int valX = (msw << 16) | lsw;
                    int result = calculateAddx(cpu, getTemp(cpu, SRC_VAL), valX, 4);
                    setTemp(cpu, 10, result);
                    cpu.scheduleWriteWord(getTemp(cpu, ADDR_X) + 2, result & 0xFFFF); // nw
                    nextStep(cpu);
                    return false;
                }
                case 6 -> { // Memory mode .L: refill (np)
                    refillPipeline(cpu); // np
                    nextStep(cpu);
                    return false;
                }
                case 7 -> { // Memory mode .L: Write MSW
                    if (!cpu.isBusCycleIdle()) return false;
                    int result = getTemp(cpu, 10);
                    cpu.scheduleWriteWord(getTemp(cpu, ADDR_X), (result >> 16) & 0xFFFF); // nW
                    return finalizeInstructionSafe(cpu);
                }
                case 10 -> { // Register mode .L gap: nn
                    cpu.idleCycles(4);
                    nextStep(cpu);
                    return false;
                }
                case 11 -> {
                    return finalizeInstructionSafe(cpu);
                }
                default -> { return finalizeInstructionSafe(cpu); }
            }
        }
    }

    @Override
    protected int calculateAddx(Motorola68000 cpu, int src, int dst, int size) {
        int res = super.calculateAddx(cpu, src, dst, size);
        setTemp(cpu, 10, res); // Save result for memory .L mode
        return res;
    }

    @Override
    public boolean shouldInhibitPrefetch() {
        return true;
    }

    @Override
    public String getDisassembly(Motorola68000 cpu) {
        int op = cpu.getIr();
        int regX = (op >> 9) & 0x7;
        int sizeBit = (op >> 6) & 0x3;
        int size = (sizeBit == 0) ? 1 : (sizeBit == 1) ? 2 : 4;
        int regY = op & 0x7;
        boolean isMemory = (op & 0x8) != 0;
        String suffix = Disassembly.getSizeSuffix(size);
        if (isMemory) return "ADDX" + suffix + " -(A" + regY + "), -(A" + regX + ")";
        else return "ADDX" + suffix + " D" + regY + ", D" + regX;
    }
}
