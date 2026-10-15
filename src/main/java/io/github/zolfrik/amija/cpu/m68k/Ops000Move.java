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

package io.github.zolfrik.amija.cpu.m68k;

import static io.github.zolfrik.amija.cpu.m68k.Motorola68000Core.IPL_LAST;
import static io.github.zolfrik.amija.cpu.m68k.Motorola68000Core.IPL_NONE;

/**
 * Data movement of the 68000/68010: MOVE, MOVEA, LEA, PEA, MOVEM, MOVEP.
 *
 * <p>MOVE is the instruction whose bus order depends most on its operands: to -(An) the
 * prefetch comes before the write and a long is written low word first; to (xxx).L from
 * memory the second word of the address is taken from the prefetch and two prefetches follow
 * the write; everywhere else the prefetch follows the write. The interrupt sample moves with
 * them.
 */
final class Ops000Move {
    private Ops000Move() {}

    // Where MOVE samples the IPL pins around its writes.
    private static final int W_NONE = 0, W_BEFORE = 1, W_BETWEEN = 2, W_AFTER_NEXT = 3, W_AFTER_NOW = 4;

    static void move(Motorola68000Core c, int op) {
        final int sz = (op >> 12) & 3;
        final int size = sz == 1 ? 1 : sz == 3 ? 2 : 4;
        final int smode = (op >> 3) & 7, sreg = op & 7;
        final int dmode = (op >> 6) & 7, dreg = (op >> 9) & 7;
        final boolean srcReg = smode < 2;
        final boolean srcImm = smode == 7 && sreg == 4;
        final boolean srcMem = !srcReg && !srcImm;
        final boolean srcAbsL = smode == 7 && sreg == 1;

        // Source, with the samples that belong to it.
        int srcAnchor = IPL_NONE;
        if (size == 4) {
            if (dmode == 4 && srcReg) c.iplNextPre();
            if (dmode == 4 && !srcReg) srcAnchor = IPL_LAST;
            if (dmode <= 1 && srcMem) srcAnchor = IPL_LAST;
        } else {
            if ((dmode == 3 || dmode == 4) && srcReg) c.iplNextPre();
            if ((dmode == 3 || dmode == 4) && srcImm) c.iplNow();
            if ((dmode == 3 || dmode == 4) && srcMem) srcAnchor = IPL_LAST;
            if (dmode <= 1 && srcMem) srcAnchor = IPL_LAST;
        }
        final int src = c.readEa(smode, sreg, size, srcAnchor);

        if (dmode <= 1) {
            if (dmode == 0) {
                c.setD(dreg, src, size);
                c.setNZ(src, size);
            } else {
                c.r[8 + dreg] = Motorola68000Core.sext(src, size);
            }
            if (srcMem) {
                c.prefetchLast();
                c.advance();
            } else {
                c.finishNext();
            }
            return;
        }

        // Destination address.
        final int dsta;
        boolean dual = false;
        switch (dmode) {
            case 2: dsta = c.r[8 + dreg]; break;
            case 3: dsta = c.r[8 + dreg]; c.r[8 + dreg] = dsta + Motorola68000Core.step(dreg, size); break;
            case 4: dsta = c.r[8 + dreg] - Motorola68000Core.step(dreg, size); c.r[8 + dreg] = dsta; break;
            case 5: dsta = c.r[8 + dreg] + (short) c.ext(); break;
            case 6: c.internal(2); dsta = c.index(c.r[8 + dreg], c.ext()); break;
            default:
                if (dreg == 0) {
                    dsta = (short) c.ext();
                } else if (srcMem) {
                    // The low word of the address stays in the prefetch.
                    dsta = (c.ext() << 16) | c.irc;
                    c.po += 2;
                    dual = true;
                } else {
                    final int hi = c.ext();
                    dsta = (hi << 16) | c.ext();
                }
                break;
        }

        // Interrupt sample around the writes.
        final int w;
        if (dmode == 7 && dreg == 1) {
            w = dual ? W_AFTER_NOW : W_AFTER_NEXT;
        } else if (dmode == 7) {
            w = size == 4 ? W_BETWEEN : W_AFTER_NEXT;
        } else if (dmode == 4) {
            w = W_NONE;
        } else if (size == 4) {
            if (dmode == 3) w = srcReg ? W_BEFORE : W_NONE;
            else if (dmode == 2) w = srcReg || srcImm ? W_BEFORE : srcAbsL ? W_AFTER_NEXT : W_BETWEEN;
            else w = srcReg || srcImm ? W_BEFORE : W_BETWEEN;
        } else {
            w = dmode == 3 ? W_NONE : W_BEFORE;
        }
        if (size == 4 && dmode == 3 && !srcReg) c.iplNow();

        if (dmode == 4) {
            c.prefetchLast();
            if (c.m68010 && size == 4 && srcReg) c.internal(2);
        }
        if (size != 1 && (dsta & 1) != 0) aeMove(c, op, size, smode, sreg, dmode, dreg, dsta, src);

        if (dmode == 4) {
            c.setNZ(src, size);
            if (size == 4) {
                c.writeWord(dsta + 2, src);
                c.writeWord(dsta, src >>> 16);
            } else if (size == 2) {
                c.writeWord(dsta, src);
            } else {
                c.writeByte(dsta, src);
            }
            c.advance();
            return;
        }

        c.setNZ(src, size);
        if (w == W_BEFORE) c.iplNow();
        if (size == 4) {
            c.writeWord(dsta, src >>> 16);
            if (w == W_BETWEEN) c.iplNow();
            c.writeWord(dsta + 2, src);
        } else if (size == 2) {
            c.writeWord(dsta, src);
        } else {
            c.writeByte(dsta, src);
        }
        if (dual) {
            c.iplNow();
            c.advance();
            c.po = 4;
            c.irc = c.fetch(c.pc);
            c.ir = c.irc;
            c.prefetch(2);
            return;
        }
        if (w == W_AFTER_NEXT) c.iplNext();
        c.prefetchLast();
        c.advance();
    }

    /** MOVE to an odd address: the address error of the write, the flags already set by the move. */
    private static void aeMove(Motorola68000Core c, int op, int size, int smode, int sreg,
                               int dmode, int dreg, int dsta, int src) {
        final boolean srcMemNotImm = smode >= 2 && !(smode == 7 && sreg == 4);
        final int pcExtra;
        if (srcMemNotImm && dmode == 7 && dreg == 1) pcExtra = -2;
        else if (dmode >= 5) pcExtra = 0;
        else pcExtra = 2;
        c.pc += c.po - 2 + pcExtra;
        int fault = dsta;
        if (dmode == 3) c.r[8 + dreg] -= Motorola68000Core.step(dreg, size);
        if (dmode == 4 && size == 4) {
            // The first word written is the low one, at An - 2: the register stops there.
            c.r[8 + dreg] = dsta + 2;
            fault = dsta + 2;
        }
        c.setNZ(src, size);
        c.exception3WriteAccess(c.ir, fault, size == 4 ? 2 : 1, size == 4 && dmode != 4 ? src >>> 16 : src, 1);
        throw Motorola68000Core.Abort.INSTANCE;
    }

    // ---------------------------------------------------------------------------------------
    // LEA, PEA
    // ---------------------------------------------------------------------------------------

    /** Control effective address of LEA and PEA, with the clocks of the indexed modes. */
    private static int controlEa(Motorola68000Core c, int mode, int reg, boolean pea) {
        switch (mode) {
            case 2: return c.r[8 + reg];
            case 5: return c.r[8 + reg] + (short) c.ext();
            case 6: {
                c.internal(2);
                if (pea) c.iplNow();
                final int a = c.index(c.r[8 + reg], c.ext());
                c.internal(2);
                return a;
            }
            default:
                switch (reg) {
                    case 0: return (short) c.ext();
                    case 1: {
                        final int hi = c.ext();
                        return (hi << 16) | c.ext();
                    }
                    case 2: {
                        final int base = c.pc + c.po - 2;
                        return base + (short) c.ext();
                    }
                    default: {
                        final int base = c.pc + c.po - 2;
                        c.internal(2);
                        if (pea) c.iplNow();
                        final int a = c.index(base, c.ext());
                        c.internal(2);
                        return a;
                    }
                }
        }
    }

    static void lea(Motorola68000Core c, int op) {
        final int a = controlEa(c, (op >> 3) & 7, op & 7, false);
        c.r[8 + ((op >> 9) & 7)] = a;
        c.finishNext();
    }

    static void pea(Motorola68000Core c, int op) {
        final int mode = (op >> 3) & 7, reg = op & 7;
        final int a = controlEa(c, mode, reg, true);
        final boolean abs = mode == 7 && (reg == 0 || reg == 1);
        final int sp = c.r[15] - 4;
        c.r[15] = sp;
        if (abs) {
            c.writeWord(sp, a >>> 16);
            c.writeWord(sp + 2, a);
            c.finishNext();
            return;
        }
        if (!(mode == 6 || (mode == 7 && reg == 3))) c.iplNextPre();
        c.prefetchLast();
        c.writeWord(sp, a >>> 16);
        c.writeWord(sp + 2, a);
        c.advance();
    }

    // ---------------------------------------------------------------------------------------
    // MOVEM
    // ---------------------------------------------------------------------------------------

    /** MOVEM registers to memory. */
    static void movemToMem(Motorola68000Core c, int op) {
        final int size = (op & 0x40) != 0 ? 4 : 2;
        final int mode = (op >> 3) & 7, reg = op & 7;
        final int mask = c.ext();
        if (mode == 4) {
            int a = c.r[8 + reg];
            if (mask != 0 && (a & 1) != 0) {
                a -= 2;
                c.pc += c.po - 2 + 2;
                int v = a;
                if (c.m68010) {
                    // The 68010 frame keeps the first word to write and the mask.
                    v = c.r[15 - Integer.numberOfTrailingZeros(mask)];
                    c.readBuffer = mask;
                }
                c.exception3WriteAccess(c.ir, a, size == 4 ? 2 : 1, v, 1);
                throw Motorola68000Core.Abort.INSTANCE;
            }
            // Bit 0 is A7, bit 15 is D0: the highest register is written first.
            for (int i = 0; i < 16; i++) {
                if ((mask & (1 << i)) == 0) continue;
                final int v = c.r[15 - i];
                if (size == 4) {
                    c.writeWord(a - 2, v);
                    c.writeWord(a - 4, v >>> 16);
                    a -= 4;
                } else {
                    a -= 2;
                    c.writeWord(a, v);
                }
            }
            c.r[8 + reg] = a;
            c.finishNext();
            return;
        }
        int a = movemEa(c, mode, reg);
        if (mask != 0 && (a & 1) != 0) {
            c.pc += c.po - 2 + 2;
            int v = a;
            if (c.m68010) {
                final int first = c.r[Integer.numberOfTrailingZeros(mask)];
                v = size == 4 ? first >>> 16 : first;
                c.readBuffer = mask;
            }
            c.exception3WriteAccess(c.ir, a, size == 4 ? 2 : 1, v, 1);
            throw Motorola68000Core.Abort.INSTANCE;
        }
        for (int i = 0; i < 16; i++) {
            if ((mask & (1 << i)) == 0) continue;
            final int v = c.r[i];
            if (size == 4) {
                c.writeWord(a, v >>> 16);
                c.writeWord(a + 2, v);
                a += 4;
            } else {
                c.writeWord(a, v);
                a += 2;
            }
        }
        c.finishNext();
    }

    /** MOVEM memory to registers. */
    static void movemToReg(Motorola68000Core c, int op) {
        final int size = (op & 0x40) != 0 ? 4 : 2;
        final int mode = (op >> 3) & 7, reg = op & 7;
        if (mode == 3) c.iplNow();
        final int mask = c.ext();
        int a = mode == 3 ? c.r[8 + reg] : movemEa(c, mode, reg);
        if (mask != 0 && (a & 1) != 0) {
            c.pc += c.po;
            if (mode == 3) c.r[8 + reg] = a + 2;
            final int fc = 1;
            c.exception3ReadAccess(c.ir, a, size == 4 ? 2 : 1, fc);
            throw Motorola68000Core.Abort.INSTANCE;
        }
        for (int i = 0; i < 16; i++) {
            if ((mask & (1 << i)) == 0) continue;
            if (size == 4) {
                final int hi = c.readWord(a);
                c.r[i] = (hi << 16) | c.readWord(a + 2);
                a += 4;
            } else {
                c.r[i] = (short) c.readWord(a);
                a += 2;
            }
        }
        c.readWord(a);
        if (mode == 3) {
            c.r[8 + reg] = a;
            c.prefetchLast();
            c.advance();
            return;
        }
        c.finishNext();
    }

    /** Address of a MOVEM operand that is not (An)+ or -(An). */
    private static int movemEa(Motorola68000Core c, int mode, int reg) {
        switch (mode) {
            case 2: return c.r[8 + reg];
            case 5: return c.r[8 + reg] + (short) c.ext();
            case 6:
                c.internal(2);
                return c.index(c.r[8 + reg], c.ext());
            default:
                switch (reg) {
                    case 0: return (short) c.ext();
                    case 1: {
                        final int hi = c.ext();
                        return (hi << 16) | c.ext();
                    }
                    case 2: {
                        final int base = c.pc + c.po - 2;
                        return base + (short) c.ext();
                    }
                    default: {
                        final int base = c.pc + c.po - 2;
                        c.internal(2);
                        return c.index(base, c.ext());
                    }
                }
        }
    }

    // ---------------------------------------------------------------------------------------
    // MOVEP
    // ---------------------------------------------------------------------------------------

    static void movep(Motorola68000Core c, int op) {
        final int dn = (op >> 9) & 7, an = 8 + (op & 7);
        final int a = c.r[an] + (short) c.ext();
        switch ((op >> 6) & 3) {
            case 0: { // word, memory to register
                c.iplNow();
                int v = c.readByte(a) << 8;
                v |= c.readByte(a + 2);
                c.setD(dn, v, 2);
                c.prefetchLast();
                c.advance();
                break;
            }
            case 1: { // long, memory to register
                int v = c.readByte(a) << 24;
                v |= c.readByte(a + 2) << 16;
                c.iplNow();
                v |= c.readByte(a + 4) << 8;
                v |= c.readByte(a + 6);
                c.r[dn] = v;
                c.prefetchLast();
                c.advance();
                break;
            }
            case 2: { // word, register to memory
                final int v = c.r[dn];
                c.writeByte(a, v >> 8);
                c.writeByte(a + 2, v);
                c.finishNext();
                break;
            }
            default: { // long, register to memory
                final int v = c.r[dn];
                c.writeByte(a, v >>> 24);
                c.writeByte(a + 2, v >>> 16);
                c.writeByte(a + 4, v >>> 8);
                c.writeByte(a + 6, v);
                c.finishNext();
                break;
            }
        }
    }
}
