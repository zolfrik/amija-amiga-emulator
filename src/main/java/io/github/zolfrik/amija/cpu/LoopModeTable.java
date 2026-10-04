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
 * Loop mode of the 68010: which instructions a {@code DBcc} with displacement -4 can keep in
 * the instruction register, and what each one costs while looping.
 *
 * <p>A loopable instruction has its operands in {@code Dn}, {@code An}, {@code (An)},
 * {@code (An)+} or {@code -(An)}, never both in registers (a single-operand instruction never
 * in a register), is not a {@code MOVE} to a register nor a {@code MOVE} from a register to
 * {@code -(An)}, and belongs to: MOVE, ADD, ADDA, ADDX, AND, CMP, CMPA, CMPM, EOR, OR, SUB,
 * SUBA, SUBX, ABCD, SBCD, NBCD, NEG, NEGX, NOT, CLR, TST and the memory shifts and rotates.
 *
 * <p>While looping the instruction skips its refill prefetch. Each entry packs:
 * <ul>
 *   <li>{@code continue}: clocks spent per iteration in place of the prefetch;</li>
 *   <li>{@code exit}: clocks spent when the loop ends right after the instruction;</li>
 *   <li>{@code extra}: clocks added at the end of the instruction while looping;</li>
 *   <li>{@code early}: the {@code continue} clocks are spent right after the skipped
 *       prefetch (the instruction has more to do after it), and then no {@code exit}
 *       clocks are left, except for CLR, which keeps them.</li>
 * </ul>
 */
final class LoopModeTable {

    private LoopModeTable() {}

    static final int LOOPABLE = 1 << 31;
    static final int EARLY = 1 << 30;
    static final int KEEP_EXIT = 1 << 29;

    static int continueClocks(int entry) { return entry & 0xFF; }
    static int exitClocks(int entry) { return (entry >> 8) & 0xFF; }
    static int extraClocks(int entry) { return (entry >> 16) & 0xFF; }

    private static int entry(int cont, int exit, int extra, boolean early) {
        return LOOPABLE | cont | (exit << 8) | (extra << 16) | (early ? EARLY : 0);
    }

    /** {@code (An)}, {@code (An)+} or {@code -(An)}. */
    private static boolean indirect(int mode) {
        return mode >= 2 && mode <= 4;
    }

    /** Builds the table for the instructions a decode table maps to each opcode. */
    static int[] build(AbstractM68kInstruction[] decode) {
        int[] table = new int[65536];
        for (int op = 0; op < 65536; op++) {
            AbstractM68kInstruction in = decode[op];
            if (in != null) table[op] = classify(in, op);
        }
        return table;
    }

    private static int classify(AbstractM68kInstruction in, int op) {
        int eaMode = (op >> 3) & 7;
        int opmode = (op >> 6) & 7;
        int sizeBits = (op >> 6) & 3;               // 0 .B, 1 .W, 2 .L where the size is bits 7-6
        if (in instanceof MoveInstruction) {
            int dstMode = (op >> 6) & 7;
            boolean srcReg = eaMode <= 1;
            if (eaMode > 4 || !indirect(dstMode) || (srcReg && dstMode == 4)) return 0;
            int cont = dstMode == 4 ? 4 : 2;
            int exit = srcReg ? 4 : cont;
            return entry(cont, exit, 0, dstMode == 4);
        }
        if (in instanceof AddInstruction || in instanceof SubInstruction
                || in instanceof AndInstruction || in instanceof OrInstruction) {
            if (!indirect(eaMode)) return 0;
            if (opmode <= 2) return entry(4, 4, 4, opmode == 2);   // <ea>,Dn
            return entry(4, 4, 0, true);                          // Dn,<ea>
        }
        if (in instanceof EorInstruction) {
            return indirect(eaMode) ? entry(4, 4, 0, true) : 0;
        }
        if (in instanceof AddaInstruction || in instanceof SubaInstruction) {
            return indirect(eaMode) ? entry(4, 4, opmode == 7 ? 4 : 2, true) : 0;
        }
        if (in instanceof CmpaInstruction) {
            return indirect(eaMode) ? entry(4, 4, 0, false) : 0;
        }
        if (in instanceof CmpInstruction) {
            if (!indirect(eaMode)) return 0;
            return opmode == 2 ? entry(4, 2, 0, false) : entry(4, 4, 0, false);
        }
        if (in instanceof CmpmInstruction) {
            return sizeBits == 2 ? entry(4, 4, 0, false) : entry(2, 2, 0, false);
        }
        if (in instanceof AddxInstruction || in instanceof SubxInstruction) {
            if ((op & 0x0008) == 0) return 0;          // Dn,Dn
            return sizeBits == 2 ? entry(2, 2, 0, false) : entry(4, 4, 0, true);
        }
        if (in == AbcdInstruction.MEMORY_INSTANCE || in == SbcdInstruction.MEMORY_INSTANCE) {
            return entry(6, 6, 0, true);
        }
        if (in instanceof NbcdInstruction) {
            return indirect(eaMode) ? entry(6, 6, 0, true) : 0;
        }
        if (in instanceof NegInstruction || in instanceof NegxInstruction || in instanceof NotInstruction) {
            return indirect(eaMode) ? entry(4, 4, 0, true) : 0;
        }
        if (in instanceof ClrInstruction) {
            return indirect(eaMode) ? entry(2, 4, 0, true) | KEEP_EXIT : 0;
        }
        if (in instanceof TstInstruction) {
            if (!indirect(eaMode)) return 0;
            return sizeBits == 2 ? entry(6, 4, 0, false) : entry(4, 4, 0, false);
        }
        if (in instanceof AslInstruction || in instanceof AsrInstruction
                || in instanceof LslInstruction || in instanceof LsrInstruction
                || in instanceof RolInstruction || in instanceof RorInstruction
                || in instanceof RoxlInstruction || in instanceof RoxrInstruction) {
            // Memory form only: size field 3.
            return (op & 0x00C0) == 0x00C0 && indirect(eaMode) ? entry(4, 4, 2, true) : 0;
        }
        return 0;
    }
}
