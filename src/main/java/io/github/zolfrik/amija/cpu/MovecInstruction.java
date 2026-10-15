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
 * MOVEC (68010): move to or from a control register.
 *
 * <p>{@code $4E7A} copies a control register into a general register, {@code $4E7B} the
 * other way. The extension word holds the general register (bits 15-12, D0-D7 then A0-A7)
 * and the control register (bits 11-0). The 68010 has four: SFC ({@code $000}), DFC
 * ({@code $001}), USP ({@code $800}) and VBR ({@code $801}); SFC and DFC keep 3 bits.
 *
 * <p>Privileged: in user mode a privilege violation is raised before the extension word is
 * read. Sequence {@code np np n4} (control to general) or {@code np np n2} (general to
 * control), with the IPL sampled between the two prefetches. An unknown control register
 * raises an illegal instruction exception after the two prefetches.
 */
public class MovecInstruction extends AbstractM68kInstruction {

    /** {@code MOVEC Rc,Rn} ($4E7A). */
    public static final MovecInstruction TO_GENERAL = new MovecInstruction(true);
    /** {@code MOVEC Rn,Rc} ($4E7B). */
    public static final MovecInstruction TO_CONTROL = new MovecInstruction(false);

    private final boolean toGeneral;

    private MovecInstruction(boolean toGeneral) {
        this.toGeneral = toGeneral;
    }

    private static final int EXT = 0;

    /** {@code true} if {@code regno} names a control register of the 68010. */
    static boolean isControlRegister(int regno) {
        return (regno & 0x7FF) < 2;
    }

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
                    cpu.markIplAnchor(AbstractMotorola.IplAnchor.NOW);
                    refillPipeline(cpu);
                    nextStep(cpu);
                    return false;
                }
                case 2 -> {
                    if (!cpu.isBusCycleIdle()) return false;
                    int ext = getTemp(cpu, EXT);
                    int regno = ext & 0xFFF;
                    if (!isControlRegister(regno)) {
                        cpu.triggerIllegalInstruction();
                        return false;
                    }
                    Motorola68010 cpu010 = (Motorola68010) cpu;
                    int general = (ext >>> 12) & 0xF;
                    if (toGeneral) {
                        int value = switch (regno) {
                            case 0x000 -> cpu010.sfc;
                            case 0x001 -> cpu010.dfc;
                            case 0x800 -> cpu010.usp;
                            default -> cpu010.vbr;
                        };
                        if (general < 8) cpu.setD(general, value, 4);
                        else cpu.setA(general - 8, value);
                    } else {
                        int value = general < 8 ? cpu.getD(general, 4) : cpu.getA(general - 8, 4);
                        switch (regno) {
                            case 0x000 -> cpu010.sfc = value & 7;
                            case 0x001 -> cpu010.dfc = value & 7;
                            case 0x800 -> cpu010.usp = value;
                            default -> cpu010.vbr = value;
                        }
                    }
                    cpu.idleCycles(toGeneral ? 4 : 2);
                    nextStep(cpu);
                    return false;
                }
                default -> { return finalizeInstructionSafe(cpu); }
            }
        }
    }

    @Override
    public String getDisassembly(AbstractMotorola cpu) {
        int ext = cpu.extensionWordAt(cpu.getOpcodeAddress() + 2);
        int general = (ext >>> 12) & 0xF;
        String rn = (general < 8 ? "D" : "A") + (general & 7);
        String rc = switch (ext & 0xFFF) {
            case 0x000 -> "SFC";
            case 0x001 -> "DFC";
            case 0x800 -> "USP";
            case 0x801 -> "VBR";
            default -> String.format("$%03X", ext & 0xFFF);
        };
        return toGeneral ? "MOVEC " + rc + ", " + rn : "MOVEC " + rn + ", " + rc;
    }
}
