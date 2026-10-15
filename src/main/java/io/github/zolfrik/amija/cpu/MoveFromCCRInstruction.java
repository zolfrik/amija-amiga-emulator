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
 * 68010 {@code MOVE CCR,<ea>} and the 68010 form of {@code MOVE SR,<ea>}.
 *
 * <p>On the 68010 both store without reading the destination first:
 * <pre>
 *   Dn          np
 *   (An)        n2 np nw
 *   (An)+ -(An) n4 np nw
 *   (d16,An)    np np nw
 *   (d8,An,Xn)  np n4 np nw
 *   (xxx).W     np np nw
 *   (xxx).L     np np np nw
 * </pre>
 * The IPL is sampled right before the refill prefetch. {@code MOVE SR} is privileged: in user
 * mode it raises a privilege violation before any bus cycle.
 */
public class MoveFromCCRInstruction extends AbstractM68kInstruction {

    /** {@code MOVE CCR,<ea>} ($42C0). */
    public static final MoveFromCCRInstruction INSTANCE = new MoveFromCCRInstruction(false);
    /** {@code MOVE SR,<ea>} ($40C0) as the 68010 executes it. */
    public static final MoveFromCCRInstruction SR_68010 = new MoveFromCCRInstruction(true);

    private final boolean fromSr;

    private MoveFromCCRInstruction(boolean fromSr) {
        this.fromSr = fromSr;
    }

    private static final int EXT1 = 0;
    private static final int EXT2 = 1;
    private static final int ADDR = 2;

    @Override
    public boolean shouldInhibitPrefetch() {
        return true;
    }

    private int value(AbstractMotorola cpu) {
        return fromSr ? cpu.getSR() : cpu.getSR() & 0xFF;
    }

    @Override
    public boolean executeStep(AbstractMotorola cpu) {
        int op = cpu.getIr();
        int mode = (op >> 3) & 7, reg = op & 7;

        while (true) {
            if (cpu.isRisingEdgeConsumed()) return false;

            switch (getStep(cpu)) {
                case 0 -> {
                    if (fromSr && !cpu.isSupervisor()) {
                        cpu.triggerPrivilegeViolation();
                        return finalizeInstructionSafe(cpu);
                    }
                    if (mode == 0) {
                        cpu.setD(reg, value(cpu), 2);
                        refillPipeline(cpu);
                        return finalizeInstructionSafe(cpu);
                    }
                    nextStep(cpu);
                    if (mode == 2) {
                        cpu.idleCycles(2);
                        return false;
                    }
                    if (mode == 3 || mode == 4) {
                        cpu.idleCycles(4);
                        return false;
                    }
                }
                case 1 -> {
                    if (hasExt1(mode, reg)) {
                        if (!fetchExtension(cpu, EXT1)) return false;
                        nextStep(cpu);
                        return false;
                    }
                    nextStep(cpu);
                }
                case 2 -> {
                    if (hasExt2(mode, reg, 2)) {
                        if (!fetchExtension(cpu, EXT2)) return false;
                        nextStep(cpu);
                        return false;
                    }
                    nextStep(cpu);
                }
                case 3 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    nextStep(cpu);
                    if (mode == 6) {
                        cpu.idleCycles(4);
                        return false;
                    }
                }
                case 4 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    int addr = calculateEA(cpu, mode, reg, 2, cpu.getOpcodeAddress() + 2,
                            getTemp(cpu, EXT1), getTemp(cpu, EXT2), true, false);
                    setTemp(cpu, ADDR, addr);
                    cpu.markIplAnchor(AbstractMotorola.IplAnchor.NOW);
                    refillPipeline(cpu);
                    nextStep(cpu);
                    return false;
                }
                case 5 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    cpu.scheduleWrite(getTemp(cpu, ADDR), value(cpu), 2);
                    return finalizeInstructionSafe(cpu);
                }
                default -> { return finalizeInstructionSafe(cpu); }
            }
        }
    }

    @Override
    public String getDisassembly(AbstractMotorola cpu) {
        int op = cpu.getIr();
        return (fromSr ? "MOVE SR, " : "MOVE CCR, ") + Disassembly.formatEA((op >> 3) & 7, op & 7, 2,
                cpu.extensionWordAt(cpu.getOpcodeAddress() + 2), cpu.extensionWordAt(cpu.getOpcodeAddress() + 4));
    }
}
