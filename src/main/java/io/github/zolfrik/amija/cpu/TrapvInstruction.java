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

public class TrapvInstruction extends AbstractM68kInstruction {
    public static final TrapvInstruction INSTANCE = new TrapvInstruction(false);
    /** The 68010 form: with V set, 2 idle clocks and the exception, without a prefetch. */
    public static final TrapvInstruction M68010 = new TrapvInstruction(true);

    private final boolean m68010;
    private TrapvInstruction(boolean m68010) {
        this.m68010 = m68010;
    }
    @Override public boolean shouldInhibitPrefetch() { return true; }
    @Override public boolean executeStep(AbstractMotorola cpu) {
        if (cpu.v && m68010) {
            cpu.exceptionLeadIn = 2;
            cpu.triggerException(7, cpu.getPC());
        } else if (cpu.v) {
            cpu.setExceptionStartsPrefetch(true);
            cpu.triggerException(7, cpu.getPC());
        } else {
            cpu.startPrefetchCycle();
            finalizeInstructionSafe(cpu);
        }
        return false;
    }
    @Override public String getDisassembly(AbstractMotorola cpu) { return "TRAPV"; }
}
