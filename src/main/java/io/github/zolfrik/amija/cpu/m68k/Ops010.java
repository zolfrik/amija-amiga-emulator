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

/** The instructions only the 68010 has: MOVEC, MOVES, RTD, BKPT, MOVE from CCR, its RTE. */
final class Ops010 {
    private Ops010() {}

    /**
     * MOVES: a register to or from memory in the address space of DFC or SFC (one space on
     * this machine). The extension word stays in IRC if the access faults.
     */
    static void moves(Motorola68000Core c, int op) {
        if (c.s == 0) {
            c.privilege();
            return;
        }
        final int size = Motorola68000Core.sizeOf((op >> 6) & 3);
        final int mode = (op >> 3) & 7, reg = op & 7;
        final int extra = c.ext();
        final int rn = (extra >> 12) & 15;
        c.internal(4);
        final int a = c.eaAddrNoRead(mode, reg, size, 2);
        final int sizeCode = size == 4 ? 2 : size == 2 ? 1 : 0;
        if ((extra & 0x800) != 0) {
            if (size != 1 && (a & 1) != 0) {
                c.irc = extra;
                int v = c.r[rn];
                if (rn == 8 + reg && (mode == 3 || mode == 4)) v += mode == 3 ? 2 : -2;
                c.eaUpdate(mode, reg, size, a);
                c.pc += c.po;
                c.exception3WriteAccess(c.ir, a, sizeCode, size == 4 ? v >>> 16 : v, 1);
                throw Motorola68000Core.Abort.INSTANCE;
            }
            c.eaUpdate(mode, reg, size, a);
            final int v = c.r[rn];
            if (size == 4) {
                c.writeWord(a, v >>> 16);
                c.writeWord(a + 2, v);
            } else if (size == 2) {
                c.writeWord(a, v);
            } else {
                c.writeByte(a, v);
            }
        } else {
            if (size != 1 && (a & 1) != 0) {
                c.irc = extra;
                c.writeBuffer = extra;
                c.eaUpdate(mode, reg, size, a);
                c.pc += c.po;
                c.exception3ReadAccess(c.ir, a, sizeCode, 1);
                throw Motorola68000Core.Abort.INSTANCE;
            }
            final int v;
            if (size == 4) {
                final int hi = c.readWord(a);
                c.iplNow();
                v = (hi << 16) | c.readWord(a + 2);
            } else {
                c.iplNow();
                v = size == 2 ? c.readWord(a) : c.readByte(a);
            }
            c.eaUpdate(mode, reg, size, a);
            if (rn >= 8) c.r[rn] = Motorola68000Core.sext(v, size);
            else c.setD(rn, v, size);
        }
        c.prefetchLast();
        c.advance();
    }

    /** MOVE from CCR: MOVE from SR without the privilege, the upper byte read as zero. */
    static void moveFromCcr(Motorola68000Core c, int op) {
        final int mode = (op >> 3) & 7, reg = op & 7;
        final int ccr = c.getSR() & 0xFF;
        if (mode == 0) {
            c.setD(reg, ccr, 2);
            c.finishNext();
            return;
        }
        final int a = c.eaAddrNoRead(mode, reg, 2, 2);
        c.iplNow();
        c.eaUpdate(mode, reg, 2, a);
        c.prefetchLast();
        if ((a & 1) != 0) {
            c.pc += c.po;
            c.exception3Write(c.ir, a, 1, ccr, 1);
            throw Motorola68000Core.Abort.INSTANCE;
        }
        c.writeWord(a, ccr);
        c.advance();
    }

    /** BKPT: no breakpoint acknowledge is answered here, so it ends as an illegal instruction. */
    static void bkpt(Motorola68000Core c, int op) {
        c.internal(4);
        c.illegal(op);
    }

    /**
     * The 68010 RTE reads the format word: format 0 is the four-word frame, format 8 the
     * 29-word bus or address error frame, whose internal state is skipped, not restored; any
     * other format is a format error, with N and Z from the format word.
     */
    static void rte(Motorola68000Core c, int op) {
        if (c.s == 0) {
            c.privilege();
            return;
        }
        final int sp = c.r[15];
        if ((sp & 1) != 0) {
            c.exception3ReadAccess(c.ir, sp, 1, 1);
            throw Motorola68000Core.Abort.INSTANCE;
        }
        final int sr = c.readWord(sp);
        final int format = c.readWord(sp + 6);
        int newpc = c.readWord(sp + 2) << 16;
        final int frame = format >>> 12;
        if (frame == 0x0) {
            c.r[15] = sp + 8;
        } else if (frame == 0x8) {
            c.r[15] = sp + 58;
        } else {
            c.fn = (short) format < 0;
            c.fz = format == 0;
            c.fv = false;
            c.exception(14);
            return;
        }
        newpc |= c.readWord(sp + 4);
        c.setSR(sr);
        if ((newpc & 1) != 0) {
            c.pc += 2;
            c.exception3ReadPrefetchOnly(c.ir, newpc);
            throw Motorola68000Core.Abort.INSTANCE;
        }
        c.pc = newpc;
        c.prefetch(0);
        c.ir = c.irc;
        c.iplNext();
        c.prefetch(2);
    }

    /** RTD: RTS that also adds the displacement in IRC to the stack pointer. */
    static void rtd(Motorola68000Core c, int op) {
        final int sp = c.r[15];
        if ((sp & 1) != 0) {
            c.pc += 2;
            c.exception3ReadAccess(c.ir, sp, 2, 1);
            throw Motorola68000Core.Abort.INSTANCE;
        }
        int newpc = c.readWord(sp) << 16;
        newpc |= c.readWord(sp + 2);
        c.r[15] = sp + 4 + (short) c.irc;
        if ((newpc & 1) != 0) {
            c.pc += 2;
            c.exception3ReadPrefetchOnly(c.ir, newpc);
            throw Motorola68000Core.Abort.INSTANCE;
        }
        c.pc = newpc;
        c.prefetch(0);
        c.ir = c.irc;
        c.iplNext();
        c.prefetch(2);
    }

    /** MOVEC: SFC, DFC, USP and VBR; any other control register is an illegal instruction. */
    static void movec(Motorola68000Core c, int op) {
        if (c.s == 0) {
            c.privilege();
            return;
        }
        final int ext = c.ext();
        c.ir = c.irc;
        c.iplNow();
        c.prefetch(c.po);
        final int regno = ext & 0xFFF;
        final int rn = (ext >> 12) & 15;
        if (regno != 0x000 && regno != 0x001 && regno != 0x800 && regno != 0x801) {
            c.illegal(op);
            return;
        }
        if ((op & 1) != 0) {
            final int v = c.r[rn];
            switch (regno) {
                case 0x000: c.sfc = v & 7; break;
                case 0x001: c.dfc = v & 7; break;
                case 0x800: c.usp = v; break;
                default: c.vbr = v; break;
            }
            c.internal(2);
        } else {
            switch (regno) {
                case 0x000: c.r[rn] = c.sfc; break;
                case 0x001: c.r[rn] = c.dfc; break;
                case 0x800: c.r[rn] = c.usp; break;
                default: c.r[rn] = c.vbr; break;
            }
            c.internal(4);
        }
        c.advance();
    }
}
