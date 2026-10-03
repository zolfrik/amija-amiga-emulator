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

public class NopInstruction extends AbstractM68kInstruction {
    public static final NopInstruction INSTANCE = new NopInstruction();
    private NopInstruction() {}

    @Override
    public void prepareInstruction(Motorola68000 cpu) {
        cpu.consumeIRC();
        // Inhibit automatic prefetch to match Yacht np sequence exactly
    }

    @Override
    public boolean shouldInhibitPrefetch() {
        return true;
    }

    @Override
    public boolean executeStep(Motorola68000 cpu) {
        // Overlapped prefetch (Phase 1): issue the terminal np and finalize in a SINGLE
        // executeStep invocation, exactly like MOVEQ. The np runs in the background while the
        // next instruction decodes the instant the bus drains: seam cost 0 (delta 4 mc),
        // instead of deferring finalize to a later step (which wasted 1 mc). See
        // docs/cpu-overlapped-prefetch-rework-plan.md, Phase 1.
        refillPipeline(cpu); // np
        return finalizeInstructionSafe(cpu);
    }
    @Override public String getDisassembly(Motorola68000 cpu) { return "NOP"; }
}

