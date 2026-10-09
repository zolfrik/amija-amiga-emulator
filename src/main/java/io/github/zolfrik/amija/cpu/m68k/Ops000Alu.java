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

import static io.github.zolfrik.amija.cpu.m68k.Motorola68000Core.IPL_LAST;
import static io.github.zolfrik.amija.cpu.m68k.Motorola68000Core.IPL_NONE;

/**
 * Arithmetic and logic of the 68000/68010: ADD, SUB, AND, OR, EOR, CMP and their address,
 * quick, immediate and extended forms, NEG, NEGX, NOT, CLR, TST, BCD, multiply, divide, EXT,
 * SWAP, EXG, MOVEQ.
 */
final class Ops000Alu {
    private Ops000Alu() {}

    static final int ADD = 0, SUB = 1, AND = 2, OR = 3, EOR = 4, CMP = 5;

    /** The operation on {@code size} bytes; the flags follow the operation. */
    static int alu(Motorola68000Core c, int kind, int src, int dst, int size) {
        switch (kind) {
            case ADD: {
                final int res = dst + src;
                c.addFlags(src, dst, res, size);
                return res;
            }
            case SUB: {
                final int res = dst - src;
                c.subFlags(src, dst, res, size);
                return res;
            }
            case CMP: {
                final int res = dst - src;
                c.cmpFlags(src, dst, res, size);
                return res;
            }
            case AND: {
                final int res = dst & src;
                c.setNZ(res, size);
                return res;
            }
            case OR: {
                final int res = dst | src;
                c.setNZ(res, size);
                return res;
            }
            default: {
                final int res = dst ^ src;
                c.setNZ(res, size);
                return res;
            }
        }
    }

    /** Read-modify-write of a memory operand: the destination of ADD, SUB, AND, OR, EOR Dn,ea, the quick and immediate forms. */
    private static void rmw(Motorola68000Core c, int kind, int src, int mode, int reg, int size) {
        final int a = c.eaAddr(mode, reg, size);
        if (size != 1 && (a & 1) != 0) c.aeRead(mode, reg, a, size, 0);
        final int dst = c.readAt(a, size, IPL_LAST);
        c.eaUpdate(mode, reg, size, a);
        final int res = alu(c, kind, src, dst, size);
        c.prefetchLast();
        if (kind != CMP) c.writeBack(a, res, size);
        c.advance();
    }

    // ---------------------------------------------------------------------------------------
    // ADD, SUB, AND, OR (lines 8, 9, C, D), CMP and EOR (line B)
    // ---------------------------------------------------------------------------------------

    /** ADD/SUB/AND/OR/CMP {@code <ea>,Dn}. */
    static void eaToDn(Motorola68000Core c, int op, int kind) {
        final int size = Motorola68000Core.sizeOf((op >> 6) & 3);
        final int dn = (op >> 9) & 7, mode = (op >> 3) & 7, reg = op & 7;
        final boolean imm = mode == 7 && reg == 4;
        if (size != 4) {
            if (mode < 2) {
                final int src = mode == 0 ? c.r[reg] : c.r[8 + reg];
                result(c, kind, dn, alu(c, kind, src, c.r[dn], size), size);
                c.finishNext();
            } else if (imm) {
                final int src = c.immediate(size, IPL_NONE);
                result(c, kind, dn, alu(c, kind, src, c.r[dn], size), size);
                c.finishNext();
            } else {
                final int src = c.readEa(mode, reg, size, IPL_LAST);
                result(c, kind, dn, alu(c, kind, src, c.r[dn], size), size);
                c.prefetchLast();
                c.advance();
            }
            return;
        }
        if (mode < 2) {
            final int src = mode == 0 ? c.r[reg] : c.r[8 + reg];
            result(c, kind, dn, alu(c, kind, src, c.r[dn], 4), 4);
            c.finishNextPre(kind == CMP ? 2 : c.longRegClocks());
        } else if (imm) {
            if (kind == ADD || kind == SUB) {
                final int src = c.immediate(4, IPL_LAST);
                result(c, kind, dn, alu(c, kind, src, c.r[dn], 4), 4);
                c.finish(c.longRegClocks());
            } else {
                final int src = c.immediate(4, IPL_NONE);
                result(c, kind, dn, alu(c, kind, src, c.r[dn], 4), 4);
                c.finishNextPre(kind == CMP ? 2 : 4);
            }
        } else if (kind == CMP) {
            final int src = c.readEa(mode, reg, 4, IPL_NONE);
            alu(c, kind, src, c.r[dn], 4);
            c.finishNextPre(2);
        } else {
            final int src = c.readEa(mode, reg, 4, IPL_LAST);
            result(c, kind, dn, alu(c, kind, src, c.r[dn], 4), 4);
            c.finish(2);
        }
    }

    private static void result(Motorola68000Core c, int kind, int dn, int res, int size) {
        if (kind != CMP) c.setD(dn, res, size);
    }

    /** ADD/SUB/AND/OR/EOR {@code Dn,<ea>}: memory destination; EOR also to a data register. */
    static void dnToEa(Motorola68000Core c, int op, int kind) {
        final int size = Motorola68000Core.sizeOf((op >> 6) & 3);
        final int dn = (op >> 9) & 7, mode = (op >> 3) & 7, reg = op & 7;
        if (mode == 0) {
            c.setD(reg, alu(c, kind, c.r[dn], c.r[reg], size), size);
            if (size == 4) c.finishNextPre(c.longRegClocks());
            else c.finishNext();
            return;
        }
        rmw(c, kind, c.r[dn], mode, reg, size);
    }

    static void add(Motorola68000Core c, int op) {
        if ((op & 0x100) == 0) eaToDn(c, op, ADD);
        else dnToEa(c, op, ADD);
    }

    static void sub(Motorola68000Core c, int op) {
        if ((op & 0x100) == 0) eaToDn(c, op, SUB);
        else dnToEa(c, op, SUB);
    }

    static void and(Motorola68000Core c, int op) {
        if ((op & 0x100) == 0) eaToDn(c, op, AND);
        else dnToEa(c, op, AND);
    }

    static void or(Motorola68000Core c, int op) {
        if ((op & 0x100) == 0) eaToDn(c, op, OR);
        else dnToEa(c, op, OR);
    }

    static void cmp(Motorola68000Core c, int op) {
        eaToDn(c, op, CMP);
    }

    static void eor(Motorola68000Core c, int op) {
        dnToEa(c, op, EOR);
    }

    // ---------------------------------------------------------------------------------------
    // ADDA, SUBA, CMPA
    // ---------------------------------------------------------------------------------------

    /** ADDA/SUBA/CMPA; {@code kind} ADD, SUB or CMP. */
    static void addressOp(Motorola68000Core c, int op, int kind) {
        final int size = (op & 0x100) != 0 ? 4 : 2;
        final int an = 8 + ((op >> 9) & 7), mode = (op >> 3) & 7, reg = op & 7;
        final int src = Motorola68000Core.sext(c.readEa(mode, reg, size, IPL_NONE), size);
        final int dst = c.r[an];
        if (kind == ADD) c.r[an] = dst + src;
        else if (kind == SUB) c.r[an] = dst - src;
        else c.cmpFlags(src, dst, dst - src, 4);
        final boolean memLong = size == 4 && mode >= 2 && !(mode == 7 && reg == 4);
        c.finishNextPre(kind == CMP || memLong ? 2 : 4);
    }

    static void adda(Motorola68000Core c, int op) {
        addressOp(c, op, ADD);
    }

    static void suba(Motorola68000Core c, int op) {
        addressOp(c, op, SUB);
    }

    static void cmpa(Motorola68000Core c, int op) {
        addressOp(c, op, CMP);
    }

    // ---------------------------------------------------------------------------------------
    // ADDQ, SUBQ
    // ---------------------------------------------------------------------------------------

    static void quick(Motorola68000Core c, int op) {
        final int kind = (op & 0x100) == 0 ? ADD : SUB;
        int data = (op >> 9) & 7;
        if (data == 0) data = 8;
        final int size = Motorola68000Core.sizeOf((op >> 6) & 3);
        final int mode = (op >> 3) & 7, reg = op & 7;
        if (mode == 0) {
            c.setD(reg, alu(c, kind, data, c.r[reg], size), size);
            if (size == 4) c.finishNextPre(4);
            else c.finishNext();
        } else if (mode == 1) {
            c.r[8 + reg] = kind == ADD ? c.r[8 + reg] + data : c.r[8 + reg] - data;
            c.finishNextPre(4);
        } else {
            rmw(c, kind, data, mode, reg, size);
        }
    }

    // ---------------------------------------------------------------------------------------
    // ADDI, SUBI, ANDI, ORI, EORI, CMPI
    // ---------------------------------------------------------------------------------------

    static void immediate(Motorola68000Core c, int op) {
        final int kind;
        switch ((op >> 9) & 7) {
            case 0: kind = OR; break;
            case 1: kind = AND; break;
            case 2: kind = SUB; break;
            case 3: kind = ADD; break;
            case 5: kind = EOR; break;
            default: kind = CMP; break;
        }
        final int size = Motorola68000Core.sizeOf((op >> 6) & 3);
        final int mode = (op >> 3) & 7, reg = op & 7;
        if (mode == 0) {
            if (size != 4) {
                final int src = c.immediate(size, IPL_NONE);
                result(c, kind, reg, alu(c, kind, src, c.r[reg], size), size);
                c.finishNext();
            } else if (kind == ADD || kind == SUB) {
                final int src = c.immediate(4, IPL_LAST);
                result(c, kind, reg, alu(c, kind, src, c.r[reg], 4), 4);
                c.finish(c.longRegClocks());
            } else {
                final int src = c.immediate(4, IPL_NONE);
                result(c, kind, reg, alu(c, kind, src, c.r[reg], 4), 4);
                c.finishNextPre(kind == CMP ? 2 : 4);
            }
            return;
        }
        final int src = c.immediate(size, IPL_NONE);
        rmw(c, kind, src, mode, reg, size);
    }

    // ---------------------------------------------------------------------------------------
    // NEG, NEGX, NOT, CLR, TST
    // ---------------------------------------------------------------------------------------

    static final int NEG = 0, NEGX = 1, NOT = 2, CLR = 3;

    private static int unary(Motorola68000Core c, int kind, int dst, int size) {
        switch (kind) {
            case NEG: {
                final int res = -dst;
                c.subFlags(dst, 0, res, size);
                return res;
            }
            case NEGX: {
                final int res = 0 - dst - (c.fx ? 1 : 0);
                c.subxFlags(dst, 0, res, size);
                return res;
            }
            case NOT: {
                final int res = ~dst;
                c.setNZ(res, size);
                return res;
            }
            default:
                c.setNZ(0, size);
                return 0;
        }
    }

    static void unary(Motorola68000Core c, int op, int kind) {
        final int size = Motorola68000Core.sizeOf((op >> 6) & 3);
        final int mode = (op >> 3) & 7, reg = op & 7;
        if (mode == 0) {
            c.setD(reg, unary(c, kind, c.r[reg], size), size);
            if (size == 4) c.finishNextPre(2);
            else c.finishNext();
            return;
        }
        final int a = c.eaAddr(mode, reg, size);
        if (size != 1 && (a & 1) != 0) c.aeRead(mode, reg, a, size, 0);
        final int dst = c.readAt(a, size, IPL_LAST);
        c.eaUpdate(mode, reg, size, a);
        final int res = unary(c, kind, dst, size);
        c.prefetchLast();
        c.writeBack(a, res, size);
        c.advance();
    }

    static void neg(Motorola68000Core c, int op) {
        unary(c, op, NEG);
    }

    static void negx(Motorola68000Core c, int op) {
        unary(c, op, NEGX);
    }

    static void not(Motorola68000Core c, int op) {
        unary(c, op, NOT);
    }

    static void clr(Motorola68000Core c, int op) {
        final int mode = (op >> 3) & 7;
        if (c.m68010 && mode != 0) {
            clr010(c, op);
            return;
        }
        if (c.m68010 && (op & 0xC0) == 0x80) {
            c.r[op & 7] = 0;
            c.setNZ(0, 4);
            c.internal(2);
            c.finishNext();
            return;
        }
        unary(c, op, CLR);
    }

    /** The 68010 CLR writes without reading first; a long goes low word first. */
    private static void clr010(Motorola68000Core c, int op) {
        final int size = Motorola68000Core.sizeOf((op >> 6) & 3);
        final int mode = (op >> 3) & 7, reg = op & 7;
        final int a = c.eaAddrNoRead(mode, reg, size, 0);
        c.iplNow();
        if (size != 1 && (a & 1) != 0) {
            c.pc += c.po;
            c.exception3Write(c.ir, size == 4 && mode == 4 ? a + 2 : a, 1, 0, 1);
            throw Motorola68000Core.Abort.INSTANCE;
        }
        c.eaUpdate(mode, reg, size, a);
        c.setNZ(0, size);
        c.prefetchLast();
        if (size == 4) {
            c.writeWord(a + 2, 0);
            c.writeWord(a, 0);
        } else if (size == 2) {
            c.writeWord(a, 0);
        } else {
            c.writeByte(a, 0);
        }
        c.advance();
    }

    static void tst(Motorola68000Core c, int op) {
        final int size = Motorola68000Core.sizeOf((op >> 6) & 3);
        final int mode = (op >> 3) & 7, reg = op & 7;
        if (mode == 0) {
            c.setNZ(c.r[reg], size);
            c.finishNext();
            return;
        }
        c.setNZ(c.readEa(mode, reg, size, IPL_LAST), size);
        c.prefetchLast();
        c.advance();
    }

    /** CMPM {@code (Ay)+,(Ax)+}. */
    static void cmpm(Motorola68000Core c, int op) {
        final int size = Motorola68000Core.sizeOf((op >> 6) & 3);
        final int ax = 8 + ((op >> 9) & 7), ay = 8 + (op & 7);
        if (size != 4) c.iplNow();
        final int srca = c.r[ay];
        if (size != 1 && (srca & 1) != 0) {
            c.r[ay] = srca + size;
            aeExtended(c, srca, size == 4 ? 2 : 1);
        }
        final int src = c.readAt(srca, size, size == 4 ? IPL_LAST : IPL_NONE);
        c.r[ay] = srca + Motorola68000Core.step(ay - 8, size);
        final int dsta = c.r[ax];
        if (size != 1 && (dsta & 1) != 0) {
            c.r[ax] = dsta + size;
            aeExtended(c, dsta, size == 4 ? 2 : 1);
        }
        final int dst = c.readAt(dsta, size, IPL_NONE);
        c.r[ax] = dsta + Motorola68000Core.step(ax - 8, size);
        c.cmpFlags(src, dst, dst - src, size);
        c.prefetchLast();
        c.advance();
    }

    // ---------------------------------------------------------------------------------------
    // ADDX, SUBX
    // ---------------------------------------------------------------------------------------

    static void addx(Motorola68000Core c, int op) {
        extended(c, op, true);
    }

    static void subx(Motorola68000Core c, int op) {
        extended(c, op, false);
    }

    private static int extendedOp(Motorola68000Core c, boolean add, int src, int dst, int size) {
        final int x = c.fx ? 1 : 0;
        if (add) {
            final int res = dst + src + x;
            c.addxFlags(src, dst, res, size);
            return res;
        }
        final int res = dst - src - x;
        c.subxFlags(src, dst, res, size);
        return res;
    }

    private static void extended(Motorola68000Core c, int op, boolean add) {
        final int size = Motorola68000Core.sizeOf((op >> 6) & 3);
        final int rx = (op >> 9) & 7, ry = op & 7;
        if ((op & 8) == 0) {
            c.setD(rx, extendedOp(c, add, c.r[ry], c.r[rx], size), size);
            if (size == 4) c.finishNextPre(c.longRegClocks());
            else c.finishNext();
            return;
        }
        c.internal(2);
        if (size == 4) {
            final int srca = c.r[8 + ry] - 4;
            if ((srca & 1) != 0) {
                c.r[8 + ry] = srca + 2;
                aeExtended(c, srca + 2, 2);
            }
            int src = c.readWord(srca + 2);
            src |= c.readWord(srca) << 16;
            c.r[8 + ry] = srca;
            final int dsta = c.r[8 + rx] - 4;
            if ((dsta & 1) != 0) {
                c.r[8 + rx] = dsta + 2;
                aeExtended(c, dsta + 2, 2);
            }
            int dst = c.readWord(dsta + 2);
            c.iplNow();
            dst |= c.readWord(dsta) << 16;
            c.r[8 + rx] = dsta;
            final int res = extendedOp(c, add, src, dst, 4);
            c.writeWord(dsta + 2, res);
            c.prefetchLast();
            c.writeWord(dsta, res >>> 16);
            c.advance();
            return;
        }
        c.iplNow();
        final int srca = c.r[8 + ry] - Motorola68000Core.step(ry, size);
        if (size == 2 && (srca & 1) != 0) {
            c.r[8 + ry] = srca;
            aeExtended(c, srca, 1);
        }
        final int src = size == 2 ? c.readWord(srca) : c.readByte(srca);
        c.r[8 + ry] = srca;
        final int dsta = c.r[8 + rx] - Motorola68000Core.step(rx, size);
        if (size == 2 && (dsta & 1) != 0) {
            c.r[8 + rx] = dsta;
            aeExtended(c, dsta, 1);
        }
        final int dst = size == 2 ? c.readWord(dsta) : c.readByte(dsta);
        c.r[8 + rx] = dsta;
        final int res = extendedOp(c, add, src, dst, size);
        c.prefetchLast();
        if (size == 2) c.writeWord(dsta, res);
        else c.writeByte(dsta, res);
        c.advance();
    }

    private static void aeExtended(Motorola68000Core c, int addr, int sizeCode) {
        c.pc += 4;
        c.exception3ReadAccess(c.ir, addr, sizeCode, 1);
        throw Motorola68000Core.Abort.INSTANCE;
    }

    // ---------------------------------------------------------------------------------------
    // ABCD, SBCD, NBCD
    // ---------------------------------------------------------------------------------------

    static int abcd(Motorola68000Core c, int src, int dst) {
        final int lo = (src & 0xF) + (dst & 0xF) + (c.fx ? 1 : 0);
        final int hi = (src & 0xF0) + (dst & 0xF0);
        int res = hi + lo;
        final int tmp = res;
        if (lo > 9) res += 6;
        final boolean carry = (res & 0x3F0) > 0x90;
        if (carry) res += 0x60;
        c.fc = carry;
        c.fx = carry;
        c.fz = c.fz & (res & 0xFF) == 0;
        c.fn = (byte) res < 0;
        c.fv = (tmp & 0x80) == 0 && (res & 0x80) != 0;
        return res & 0xFF;
    }

    static int sbcd(Motorola68000Core c, int src, int dst) {
        final int x = c.fx ? 1 : 0;
        final int lo = (dst & 0xF) - (src & 0xF) - x;
        final int hi = (dst & 0xF0) - (src & 0xF0);
        int res = hi + lo;
        final int tmp = res;
        int bcd = 0;
        if ((lo & 0xF0) != 0) {
            res -= 6;
            bcd = 6;
        }
        if ((((dst & 0xFF) - (src & 0xFF) - x) & 0x100) > 0xFF) res -= 0x60;
        final boolean carry = (((dst & 0xFF) - (src & 0xFF) - bcd - x) & 0x300) > 0xFF;
        c.fc = carry;
        c.fx = carry;
        c.fz = c.fz & (res & 0xFF) == 0;
        c.fn = (byte) res < 0;
        c.fv = (tmp & 0x80) != 0 && (res & 0x80) == 0;
        return res & 0xFF;
    }

    static void abcd(Motorola68000Core c, int op) {
        bcd(c, op, true);
    }

    static void sbcd(Motorola68000Core c, int op) {
        bcd(c, op, false);
    }

    private static void bcd(Motorola68000Core c, int op, boolean add) {
        final int rx = (op >> 9) & 7, ry = op & 7;
        if ((op & 8) == 0) {
            final int res = add ? abcd(c, c.r[ry] & 0xFF, c.r[rx] & 0xFF) : sbcd(c, c.r[ry] & 0xFF, c.r[rx] & 0xFF);
            c.setD(rx, res, 1);
            c.finishNextPre(2);
            return;
        }
        c.internal(2);
        final int srca = c.r[8 + ry] - Motorola68000Core.step(ry, 1);
        final int src = c.readByte(srca);
        c.r[8 + ry] = srca;
        c.iplNextPre();
        final int dsta = c.r[8 + rx] - Motorola68000Core.step(rx, 1);
        final int dst = c.readByte(dsta);
        c.r[8 + rx] = dsta;
        final int res = add ? abcd(c, src, dst) : sbcd(c, src, dst);
        c.prefetchLast();
        c.writeByte(dsta, res);
        c.advance();
    }

    static void nbcd(Motorola68000Core c, int op) {
        final int mode = (op >> 3) & 7, reg = op & 7;
        if (mode == 0) {
            c.setD(reg, sbcd(c, c.r[reg] & 0xFF, 0), 1);
            c.finishNextPre(2);
            return;
        }
        final int a = c.eaAddr(mode, reg, 1);
        final int dst = c.readAt(a, 1, IPL_LAST);
        c.eaUpdate(mode, reg, 1, a);
        final int res = sbcd(c, dst, 0);
        c.prefetchLast();
        c.writeByte(a, res);
        c.advance();
    }

    // ---------------------------------------------------------------------------------------
    // MULU, MULS, DIVU, DIVS
    // ---------------------------------------------------------------------------------------

    static void mulu(Motorola68000Core c, int op) {
        mul(c, op, false);
    }

    static void muls(Motorola68000Core c, int op) {
        mul(c, op, true);
    }

    private static void mul(Motorola68000Core c, int op, boolean signed) {
        final int dn = (op >> 9) & 7, mode = (op >> 3) & 7, reg = op & 7;
        final int src;
        if (mode == 0 || (mode == 7 && reg == 4)) {
            src = (mode == 0 ? c.r[reg] : c.ext()) & 0xFFFF;
            c.ir = c.irc;
            c.iplNext();
            c.prefetch(c.po);
        } else {
            src = c.readEa(mode, reg, 2, IPL_LAST) & 0xFFFF;
            c.prefetchLast();
        }
        final int dst = c.r[dn];
        final int res = signed ? (short) dst * (short) src : (dst & 0xFFFF) * src;
        c.fn = res < 0;
        c.fz = res == 0;
        c.fv = false;
        c.fc = false;
        c.internal(signed ? mulsClocks(c, src) : muluClocks(c, src));
        c.r[dn] = res;
        c.advance();
    }

    static int muluClocks(Motorola68000Core c, int src) {
        if (c.m68010) return 40 - 4;
        int clocks = 38 - 4;
        for (int s = src & 0xFFFF; s != 0; s >>>= 1) {
            if ((s & 1) != 0) clocks += 2;
        }
        return clocks;
    }

    static int mulsClocks(Motorola68000Core c, int src) {
        if (c.m68010) return 40 - 4 + ((src & 0x8000) != 0 ? 2 : 0);
        int clocks = 38 - 4;
        int s = (src & 0xFFFF) << 1;
        for (int bits = 0; bits < 16 && s != 0; bits++, s >>>= 1) {
            if ((s & 3) == 1 || (s & 3) == 2) clocks += 2;
        }
        return clocks;
    }

    static void divu(Motorola68000Core c, int op) {
        final int dn = (op >> 9) & 7, mode = (op >> 3) & 7, reg = op & 7;
        final int src = c.readEa(mode, reg, 2, IPL_NONE) & 0xFFFF;
        final int dst = c.r[dn];
        if (src == 0) {
            divByZero(c, false, dst);
            return;
        }
        final long q = Integer.toUnsignedLong(dst) / src;
        final long rem = Integer.toUnsignedLong(dst) % src;
        c.internal(divuClocks(c, dst, src));
        if (q > 0xFFFF) {
            if (c.m68010) {
                c.fn = !(dst < 0 && (short) src < 0);
                c.fz = false;
            }
            c.fv = true;
            c.fc = false;
        } else {
            c.fv = false;
            c.fc = false;
            c.fz = (short) q == 0;
            c.fn = (short) q < 0;
            c.r[dn] = (int) ((q & 0xFFFF) | (rem << 16));
        }
        c.finishNext();
    }

    static void divs(Motorola68000Core c, int op) {
        final int dn = (op >> 9) & 7, mode = (op >> 3) & 7, reg = op & 7;
        final int src = (short) c.readEa(mode, reg, 2, IPL_NONE);
        final int dst = c.r[dn];
        if (src == 0) {
            divByZero(c, true, dst);
            return;
        }
        if (!c.m68010 && divsOverflowsEarly(dst, src)) {
            // The overflow shows in the first steps of the division.
            divsOverflow(c);
            c.internal(dst < 0 ? 14 : 12);
            c.finishNext();
            return;
        }
        int clocks = divsClocks(c, dst, src);
        if (c.divsEarlySample) {
            clocks -= 2;
            c.internal(clocks);
            c.iplNext();
            c.internal(2);
        } else {
            c.internal(clocks);
            c.iplNext();
        }
        if (dst == 0x80000000 && src == -1) {
            divsOverflow(c);
        } else {
            final int q = dst / src;
            int rem = dst % src;
            if ((q & 0xFFFF8000) != 0 && (q & 0xFFFF8000) != 0xFFFF8000) {
                divsOverflow(c);
            } else {
                if (((short) rem < 0) != (dst < 0)) rem = -rem;
                c.fv = false;
                c.fc = false;
                c.fz = (short) q == 0;
                c.fn = (short) q < 0;
                c.r[dn] = (q & 0xFFFF) | (rem << 16);
            }
        }
        c.prefetchLast();
        c.advance();
    }

    /**
     * True if the 68000 detects the overflow of DIVS at the start of the division: the
     * absolute dividend's high word reaches the divisor, the quotient does not fit, or the
     * first trial subtraction (bit 15 of the quotient) succeeds, except for a quotient of
     * exactly -32768.
     */
    private static boolean divsOverflowsEarly(int dividend, int divisor) {
        final long absDividend = Math.abs((long) dividend);
        final long absDivisor = Math.abs((long) divisor);
        final int hi = (int) (absDividend >>> 16) & 0xFFFF;
        final int lo = (int) (absDividend & 0xFFFF);
        if (hi >= absDivisor) return true;
        final long q = (long) dividend / divisor;
        final int r = dividend % divisor;
        final boolean negQuot = (dividend < 0) ^ (divisor < 0);
        if (negQuot ? q < -32768 || (q == -32768 && r != 0) : q > 32767) return true;
        final boolean q15 = ((hi << 1) | (lo >> 15)) >= absDivisor;
        return q15 && !(negQuot && q == -32768 && r == 0);
    }

    private static void divsOverflow(Motorola68000Core c) {
        c.fc = false;
        c.fv = true;
        if (c.m68010) {
            c.fz = false;
            c.fn = true;
        }
    }

    private static void divByZero(Motorola68000Core c, boolean signed, int dst) {
        c.fv = false;
        c.fc = false;
        c.fn = false;
        c.fz = false;
        if (!c.m68010) {
            // The 68000 clears N, Z, V and C.
        } else if (signed) {
            c.fz = true;
        } else {
            final int d = (short) (dst >>> 16);
            if (d < 0) c.fn = true;
            else if (d == 0) c.fz = true;
        }
        c.pc += c.po - 2;
        c.internal(4);
        c.exception(5);
    }

    static int divuClocks(Motorola68000Core c, int dividend, int divisor) {
        if (c.m68010) {
            if ((dividend >>> 16) >= divisor) return 4;
            int mcycles = 74;
            final int hdivisor = divisor << 16;
            for (int i = 0; i < 15; i++) {
                final int temp = dividend;
                dividend <<= 1;
                if (temp < 0) {
                    dividend -= hdivisor;
                } else {
                    mcycles += 2;
                    if (Integer.compareUnsigned(dividend, hdivisor) >= 0) dividend -= hdivisor;
                }
            }
            return mcycles;
        }
        if ((dividend >>> 16) >= divisor) return 5 * 2 - 4;
        int mcycles = 38;
        final int hdivisor = divisor << 16;
        for (int i = 0; i < 15; i++) {
            final int temp = dividend;
            dividend <<= 1;
            if (temp < 0) {
                dividend -= hdivisor;
            } else {
                mcycles += 2;
                if (Integer.compareUnsigned(dividend, hdivisor) >= 0) {
                    dividend -= hdivisor;
                    mcycles--;
                }
            }
        }
        return mcycles * 2 - 4;
    }

    /** Internal clocks of DIVS; sets {@code divsEarlySample} when the interrupt sample comes two clocks early. */
    static int divsClocks(Motorola68000Core c, int dividend, int divisor) {
        c.divsEarlySample = false;
        final long absDividend = Math.abs((long) dividend);
        final int absDivisor = Math.abs(divisor) & 0xFFFF;
        if (c.m68010) {
            if ((absDividend >>> 16) >= absDivisor) return 12;
            int mcycles = 116;
            if (dividend < 0) {
                mcycles += 2;
                c.divsEarlySample = true;
            }
            return mcycles;
        }
        int mcycles = 6;
        if (dividend < 0) mcycles++;
        if (((absDividend & 0xFFFFFFFFL) >>> 16) >= absDivisor) return (mcycles + 2) * 2 - 4;
        c.divsEarlySample = divisor < 0 && dividend >= 0;
        long aquot = (absDividend & 0xFFFFFFFFL) / absDivisor;
        mcycles += 55;
        if (divisor >= 0) {
            if (dividend >= 0) mcycles--;
            else mcycles++;
        }
        for (int i = 0; i < 15; i++) {
            if ((short) aquot >= 0) mcycles++;
            aquot <<= 1;
        }
        return mcycles * 2 - 4;
    }

    // ---------------------------------------------------------------------------------------
    // EXT, SWAP, EXG, MOVEQ
    // ---------------------------------------------------------------------------------------

    static void ext(Motorola68000Core c, int op) {
        final int reg = op & 7;
        if ((op & 0x40) == 0) {
            final int v = (byte) c.r[reg];
            c.setD(reg, v, 2);
            c.setNZ(v, 2);
        } else {
            final int v = (short) c.r[reg];
            c.r[reg] = v;
            c.setNZ(v, 4);
        }
        c.finishNext();
    }

    static void swap(Motorola68000Core c, int op) {
        final int reg = op & 7;
        final int v = (c.r[reg] >>> 16) | (c.r[reg] << 16);
        c.r[reg] = v;
        c.setNZ(v, 4);
        c.finishNext();
    }

    static void exg(Motorola68000Core c, int op) {
        final int rx = (op >> 9) & 7, ry = op & 7;
        final int i, j;
        switch ((op >> 3) & 0x1F) {
            case 0x08: i = rx; j = ry; break;
            case 0x09: i = 8 + rx; j = 8 + ry; break;
            default: i = rx; j = 8 + ry; break;
        }
        final int t = c.r[i];
        c.r[i] = c.r[j];
        c.r[j] = t;
        c.finishNextPre(2);
    }

    static void moveq(Motorola68000Core c, int op) {
        final int v = (byte) op;
        c.r[(op >> 9) & 7] = v;
        c.setNZ(v, 4);
        c.finishNext();
    }
}
