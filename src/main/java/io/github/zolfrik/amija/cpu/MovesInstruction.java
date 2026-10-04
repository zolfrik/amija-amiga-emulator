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
 * MOVES (68010): move to or from an address space chosen by the function code registers.
 *
 * <p>The extension word holds the general register (bits 15-12) and the direction (bit 11:
 * register to memory). A read uses SFC as function code, a write DFC. Loading an address
 * register sign-extends a byte or word operand.
 *
 * <p>Privileged: in user mode a privilege violation is raised before the extension word is
 * read. Sequence: {@code np n4}, then the effective address ({@code (An)} n2,
 * {@code (An)+}/{@code -(An)} n4, {@code (d16,An)} np, {@code (d8,An,Xn)} np n4,
 * {@code (xxx).W} np, {@code (xxx).L} np np), the access, and the refill prefetch. A read
 * samples the IPL right before its last word.
 */
public class MovesInstruction extends AbstractM68kInstruction {

    public static final MovesInstruction INSTANCE = new MovesInstruction();

    private MovesInstruction() {}

    private static final int EXT = 0;
    private static final int EA1 = 1;
    private static final int EA2 = 2;
    private static final int ADDR = 3;
    private static final int HIGH = 4;

    @Override
    public boolean shouldInhibitPrefetch() {
        return true;
    }

    @Override
    public boolean executeStep(AbstractMotorola cpu) {
        if (getStep(cpu) == 0 && !cpu.isSupervisor()) {
            cpu.triggerPrivilegeViolation();
            return finalizeInstructionSafe(cpu);
        }
        int op = cpu.getIr();
        int s = (op >> 6) & 3;
        int size = s == 0 ? 1 : s == 1 ? 2 : 4;
        int mode = (op >> 3) & 7, reg = op & 7;
        while (true) {
            if (cpu.isRisingEdgeConsumed()) return false;
            switch (getStep(cpu)) {
                case 0 -> {
                    if (!fetchExtension(cpu, EXT)) return false;
                    nextStep(cpu);
                    return false;
                }
                case 1 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    cpu.idleCycles(4);
                    nextStep(cpu);
                    return false;
                }
                case 2 -> {
                    if (hasExt1(mode, reg)) {
                        if (!fetchExtension(cpu, EA1)) return false;
                        nextStep(cpu);
                        return false;
                    }
                    nextStep(cpu);
                }
                case 3 -> {
                    if (hasExt2(mode, reg, size)) {
                        if (!fetchExtension(cpu, EA2)) return false;
                        nextStep(cpu);
                        return false;
                    }
                    nextStep(cpu);
                }
                case 4 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    nextStep(cpu);
                    int gap = mode == 2 ? 2 : (mode == 3 || mode == 4 || mode == 6) ? 4 : 0;
                    if (gap > 0) {
                        cpu.idleCycles(gap);
                        return false;
                    }
                }
                case 5 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    int ext = getTemp(cpu, EXT);
                    int general = (ext >>> 12) & 0xF;
                    Motorola68010 cpu010 = (Motorola68010) cpu;
                    int addr = calculateEA(cpu, mode, reg, size, cpu.getOpcodeAddress() + 4,
                            getTemp(cpu, EA1), getTemp(cpu, EA2), true, false);
                    setTemp(cpu, ADDR, addr);
                    if ((ext & 0x800) != 0) {
                        // A store samples no IPL: the level deferred by the previous
                        // instruction is the one that counts.
                        cpu.markNoIplSample();
                        int value = general < 8 ? cpu.getD(general, 4) : cpu.getA(general - 8, 4);
                        cpu.scheduleWrite(addr, value, size, cpu010.dfc);
                        setStep(cpu, 8);
                        return false;
                    }
                    if (size == 4) {
                        cpu.scheduleRead(addr, 2, cpu010.sfc);
                    } else {
                        cpu.markIplAnchor(AbstractMotorola.IplAnchor.NOW);
                        cpu.scheduleRead(addr, size, cpu010.sfc);
                    }
                    nextStep(cpu);
                    return false;
                }
                case 6 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    if (cpu.getCpuState() == AbstractMotorola.CpuState.EXCEPTION) return false;
                    nextStep(cpu);
                    if (size == 4) {
                        setTemp(cpu, HIGH, cpu.getLatchedBusData() & 0xFFFF);
                        cpu.markIplAnchor(AbstractMotorola.IplAnchor.NOW);
                        cpu.scheduleRead(getTemp(cpu, ADDR) + 2, 2, ((Motorola68010) cpu).sfc);
                        return false;
                    }
                }
                case 7 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    int ext = getTemp(cpu, EXT);
                    int general = (ext >>> 12) & 0xF;
                    int value = size == 1 ? cpu.getLatchedByte()
                            : size == 2 ? cpu.getLatchedBusData() & 0xFFFF
                            : (getTemp(cpu, HIGH) << 16) | (cpu.getLatchedBusData() & 0xFFFF);
                    if (general >= 8) {
                        int ext32 = size == 1 ? (byte) value : size == 2 ? (short) value : value;
                        cpu.setA(general - 8, ext32);
                    } else {
                        cpu.setD(general, value, size);
                    }
                    refillPipeline(cpu);
                    return finalizeInstructionSafe(cpu);
                }
                case 8 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    refillPipeline(cpu);
                    return finalizeInstructionSafe(cpu);
                }
                default -> { return finalizeInstructionSafe(cpu); }
            }
        }
    }

    @Override
    public String getDisassembly(AbstractMotorola cpu) {
        int op = cpu.getIr();
        int s = (op >> 6) & 3;
        int size = s == 0 ? 1 : s == 1 ? 2 : 4;
        int ext = cpu.extensionWordAt(cpu.getOpcodeAddress() + 2);
        int general = (ext >>> 12) & 0xF;
        String rn = (general < 8 ? "D" : "A") + (general & 7);
        String ea = Disassembly.formatEA((op >> 3) & 7, op & 7, size,
                cpu.extensionWordAt(cpu.getOpcodeAddress() + 4), cpu.extensionWordAt(cpu.getOpcodeAddress() + 6));
        String sfx = Disassembly.getSizeSuffix(size);
        return (ext & 0x800) != 0 ? "MOVES" + sfx + " " + rn + ", " + ea : "MOVES" + sfx + " " + ea + ", " + rn;
    }
}
