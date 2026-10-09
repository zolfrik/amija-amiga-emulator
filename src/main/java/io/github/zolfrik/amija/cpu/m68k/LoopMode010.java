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

package io.github.zolfrik.amija.cpu.m68k;

/**
 * Loop mode of the 68010: which instructions a {@code DBcc} with displacement -4 keeps in the
 * instruction register, and what each costs while looping.
 *
 * <p>A loopable instruction has its memory operand in {@code (An)}, {@code (An)+} or
 * {@code -(An)}, is not a {@code MOVE} to a register nor a {@code MOVE} from a register to
 * {@code -(An)}, and belongs to: MOVE, ADD, ADDA, ADDX, AND, CMP, CMPA, CMPM, EOR, OR, SUB,
 * SUBA, SUBX, ABCD, SBCD, NBCD, NEG, NEGX, NOT, CLR, TST and the memory shifts and rotates.
 *
 * <p>While looping, the instruction skips its last prefetch. Each entry packs:
 * <ul>
 *   <li>{@code continue}: clocks of a further iteration, in place of the prefetch;</li>
 *   <li>{@code exit}: clocks spent when the loop ends after the instruction;</li>
 *   <li>{@code extra}: clocks added at the end of the instruction while looping;</li>
 *   <li>{@code early}: the {@code continue} clocks are spent at the prefetch point (the
 *       instruction has more to do after it), and no {@code exit} clocks are left, except
 *       with {@code KEEP_EXIT} (CLR).</li>
 * </ul>
 */
final class LoopMode010 {

    private LoopMode010() {}

    static final int LOOPABLE = 1 << 31;
    static final int EARLY = 1 << 30;
    static final int KEEP_EXIT = 1 << 29;

    private static final int[] TABLE = build();

    static int[] table() {
        return TABLE;
    }

    static int continueClocks(int entry) { return entry & 0xFF; }
    static int exitClocks(int entry) { return (entry >> 8) & 0xFF; }
    static int extraClocks(int entry) { return (entry >> 16) & 0xFF; }

    private static int entry(int cont, int exit, int extra, boolean early) {
        return LOOPABLE | cont | (exit << 8) | (extra << 16) | (early ? EARLY : 0);
    }

    private static boolean indirect(int mode) {
        return mode >= 2 && mode <= 4;
    }

    private static int[] build() {
        final int[] t = new int[65536];
        for (int op = 0; op < 65536; op++) {
            if (!Decoder000.isIllegal(op, true)) t[op] = classify(op);
        }
        return t;
    }

    static int classify(int op) {
        final int line = op >>> 12;
        final int mode = (op >> 3) & 7;
        final int opmode = (op >> 6) & 7;
        final int size = (op >> 6) & 3;
        switch (line) {
            case 0x1: case 0x2: case 0x3: {
                final int dmode = (op >> 6) & 7;
                final boolean srcReg = mode <= 1;
                if (mode > 4 || !indirect(dmode) || (srcReg && dmode == 4)) return 0;
                final int cont = dmode == 4 ? 4 : 2;
                return entry(cont, srcReg ? 4 : cont, 0, dmode == 4);
            }
            case 0x4:
                if (size == 3 || !indirect(mode)) return 0;
                switch ((op >> 8) & 0xF) {
                    case 0x0: case 0x4: case 0x6: return entry(4, 4, 0, true);   // NEGX, NEG, NOT
                    case 0x2: return entry(2, 4, 0, true) | KEEP_EXIT;           // CLR
                    case 0xA: return size == 2 ? entry(6, 4, 0, false) : entry(4, 4, 0, false); // TST
                    case 0x8: return size == 0 ? entry(6, 6, 0, true) : 0;      // NBCD
                    default: return 0;
                }
            case 0x8: case 0xC: case 0x9: case 0xD: {
                final boolean addSub = line == 0x9 || line == 0xD;
                if (opmode >= 4 && opmode <= 6 && mode <= 1) {
                    if ((op & 8) == 0) return 0;
                    if (addSub) return size == 2 ? entry(2, 2, 0, false) : entry(4, 4, 0, true); // ADDX/SUBX
                    return opmode == 4 ? entry(6, 6, 0, true) : 0;                // ABCD/SBCD
                }
                if (!indirect(mode)) return 0;
                if (opmode == 3 || opmode == 7) {
                    return addSub ? entry(4, 4, opmode == 7 ? 4 : 2, true) : 0;   // ADDA/SUBA
                }
                if (opmode <= 2) return entry(4, 4, 4, opmode == 2);              // <ea>,Dn
                return entry(4, 4, 0, true);                                      // Dn,<ea>
            }
            case 0xB:
                if (opmode == 3 || opmode == 7) return indirect(mode) ? entry(4, 4, 0, false) : 0; // CMPA
                if (opmode <= 2) {
                    if (!indirect(mode)) return 0;
                    return opmode == 2 ? entry(4, 2, 0, false) : entry(4, 4, 0, false);    // CMP
                }
                if (mode == 1) return size == 2 ? entry(4, 4, 0, false) : entry(2, 2, 0, false); // CMPM
                return indirect(mode) ? entry(4, 4, 0, true) : 0;                 // EOR
            case 0xE:
                return (op & 0x08C0) == 0x00C0 && indirect(mode) ? entry(4, 4, 2, true) : 0;
            default:
                return 0;
        }
    }
}
