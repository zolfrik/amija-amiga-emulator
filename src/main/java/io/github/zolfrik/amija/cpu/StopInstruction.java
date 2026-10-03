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

public class StopInstruction extends AbstractM68kInstruction {
    public static final StopInstruction INSTANCE = new StopInstruction();
    private StopInstruction() {}
    private static final int IMM = 0;
    // STOP: supervisor mode only, then one extension read (ONE prefetch of the immediate),
    // then set SR + STOPPED + 4 internal cycles. The refill prefetch of
    // `prepareInstruction` is inhibited so the single extension read stays in
    // `fetchExtension`: that removes a CCK of phase on stop3/stop4.
    @Override public boolean shouldInhibitPrefetch() { return true; }
    // The STOP round grid opens at the start of the instruction, with its first IPL fetch,
    // not once the immediate is in: rounds of 4 clocks run from this instant.
    @Override public void prepareInstruction(Motorola68000 cpu) {
        super.prepareInstruction(cpu);
        cpu.beginStopRounds();
    }
    @Override public boolean executeStep(Motorola68000 cpu) {
        // Supervisor mode only: STOP in user mode -> immediate privilege violation (checked
        // BEFORE the extension read). It covers re-executing `stop` in USER mode in the
        // stop3/stop4 privilege loop (rte to user -> direct violation, without entering STOPPED).
        if (getStep(cpu) == 0 && !cpu.isSupervisor()) {
            cpu.triggerPrivilegeViolation();
            return finalizeInstruction(cpu);
        }
        switch (getStep(cpu)) {
            // The extension read of the immediate is the very first step, with no preamble: a
            // no-op step before it would waste 1 CCK and shift the stop->trace entry of the
            // stop4 privilege loop.
            case 0 -> { if (!fetchExtension(cpu, IMM)) return false; nextStep(cpu); return false; }
            case 1 -> {
                cpu.writeSR(getTemp(cpu, IMM), true);
                // If the trace was already pending BEFORE the STOP (T=1, e.g.
                // `ori #$8000,SR; stop #$0000`), the trace is latched IN THE SAME round as the
                // STOP (before STOPPED halts the CPU) and fires at the next round WITHOUT an
                // extra sync and WITHOUT going through the STOPPED branch. It is reproduced by
                // firing the trace DIRECTLY here: no STOPPED latency (the 1 CCK of the
                // finalize -> STOPPED branch round trip) and no artificial idle. stop2/stop4 green.
                if (cpu.isTracePending()) {
                    cpu.fireStopTrace();
                    return finalizeInstruction(cpu);
                }
                // A STOP that CLEARS the S bit (e.g. `stop #$0000`): immediate privilege
                // violation, without ever entering STOPPED. The same opcode is re-dispatched at
                // the next round and its `!S` check fires at once, with no extra sync: the
                // re-dispatch is free, so the exception starts as soon as the STOP instruction
                // is done. A round trip through the STOPPED branch of risingEdge would cost
                // +1 CCK (stop3).
                if (!cpu.isSupervisor()) {
                    cpu.triggerPrivilegeViolation();
                    return finalizeInstruction(cpu);
                }
                // STOP with no pending trace (T=0, or a STOP that LOADS T=1 like `stop #$A000`):
                // enters STOPPED and waits for an interrupt / privilege. The 4 internal cycles
                // are spent by the step machine (extension read + this step + finalize).
                cpu.cpuState = Motorola68000.CpuState.STOPPED;
                return finalizeInstruction(cpu);
            }
            default -> { return finalizeInstruction(cpu); }
        }
    }
    @Override public String getDisassembly(Motorola68000 cpu) { return "STOP #$" + Disassembly.formatHex(cpu.extensionWordAt(cpu.getOpcodeAddress() + 2), 4); }
}
