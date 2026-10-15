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

/**
 * Data movement, arithmetic and logic.
 *
 * <p>Every handler follows the order of operations of the instruction on the 68020: which
 * extension words are consumed, which memory operands are read, then where the opcode of
 * the next instruction is prefetched with respect to the reads and the writes. That order
 * is what the timing model measures, so it is part of the semantics, not a detail.
 */
final class OpsAlu020 {
    private OpsAlu020() {}

    static int size(int op) {
        final int z = (op >> 6) & 3;
        return z == 0 ? 1 : z == 1 ? 2 : 4;
    }

    // =======================================================================================
    // MOVE family
    // =======================================================================================

    static int moveSize(int op) {
        final int z = (op >> 12) & 3;
        return z == 1 ? 1 : z == 3 ? 2 : 4;
    }

    static void move(Motorola68020 c, int op) {
        final int size = moveSize(op);
        final int src = c.readEa((op >> 3) & 7, op & 7, size);
        final int dmode = (op >> 6) & 7, dreg = (op >> 9) & 7;
        if (dmode == 0) {
            c.prefetchNext();
            c.setNZ(src, size);
            c.setD(dreg, src, size);
            c.advance();
            return;
        }
        final int a = c.ea(dmode, dreg, size);
        c.prefetchNext();
        c.setNZ(src, size);
        c.write(a, src, size);
        c.advance();
    }

    static void movea(Motorola68020 c, int op) {
        final int size = moveSize(op);
        int src = c.readEa((op >> 3) & 7, op & 7, size);
        if (size == 2) src = (short) src;
        c.prefetchNext();
        c.r[8 + ((op >> 9) & 7)] = src;
        c.advance();
    }

    static void moveq(Motorola68020 c, int op) {
        final int v = (byte) op;
        c.setNZ(v, 4);
        c.prefetchNext();
        c.r[(op >> 9) & 7] = v;
        c.advance();
    }

    static void lea(Motorola68020 c, int op) {
        final int a = c.ea((op >> 3) & 7, op & 7, 4);
        c.prefetchNext();
        c.r[8 + ((op >> 9) & 7)] = a;
        c.advance();
    }

    static void pea(Motorola68020 c, int op) {
        final int a = c.ea((op >> 3) & 7, op & 7, 4);
        c.prefetchNext();
        final int sp = c.r[15] - 4;
        c.r[15] = sp;
        c.writeLong(sp, a);
        c.advance();
    }

    static void clr(Motorola68020 c, int op) {
        final int size = size(op);
        final int mode = (op >> 3) & 7, reg = op & 7;
        c.setNZ(0, size);
        if (mode == 0) {
            c.prefetchNext();
            c.setD(reg, 0, size);
        } else {
            final int a = c.ea(mode, reg, size);
            c.prefetchNext();
            c.write(a, 0, size);
        }
        c.advance();
    }

    static void tst(Motorola68020 c, int op) {
        final int size = size(op);
        final int v = c.readEa((op >> 3) & 7, op & 7, size);
        c.prefetchNext();
        c.setNZ(v, size);
        c.advance();
    }

    static void swap(Motorola68020 c, int op) {
        final int reg = op & 7;
        final int v = (c.r[reg] >>> 16) | (c.r[reg] << 16);
        c.setNZ(v, 4);
        c.prefetchNext();
        c.r[reg] = v;
        c.advance();
    }

    static void extw(Motorola68020 c, int op) {
        final int reg = op & 7;
        final int v = (byte) c.r[reg];
        c.setNZ(v, 2);
        c.prefetchNext();
        c.setD(reg, v, 2);
        c.advance();
    }

    static void extl(Motorola68020 c, int op) {
        final int reg = op & 7;
        final int v = (short) c.r[reg];
        c.setNZ(v, 4);
        c.prefetchNext();
        c.r[reg] = v;
        c.advance();
    }

    static void extbl(Motorola68020 c, int op) {
        final int reg = op & 7;
        final int v = (byte) c.r[reg];
        c.setNZ(v, 4);
        c.prefetchNext();
        c.r[reg] = v;
        c.advance();
    }

    static void exg(Motorola68020 c, int op) {
        final int rx = (op >> 9) & 7, ry = op & 7;
        final int ix, iy;
        switch ((op >> 3) & 0x1F) {
            case 0x08: ix = rx; iy = ry; break;
            case 0x09: ix = 8 + rx; iy = 8 + ry; break;
            default: ix = rx; iy = 8 + ry; break;
        }
        c.prefetchNext();
        final int t = c.r[ix];
        c.r[ix] = c.r[iy];
        c.r[iy] = t;
        c.advance();
    }

    // =======================================================================================
    // MOVEM, MOVEP
    // =======================================================================================

    /** MOVEM registers to memory. */
    static void movemToMem(Motorola68020 c, int op) {
        final int size = (op & 0x40) != 0 ? 4 : 2;
        final int mask = c.nextWord();
        final int mode = (op >> 3) & 7, reg = op & 7;
        if (mode == 4) {
            int a = c.r[8 + reg];
            // Predecrement: the register list is reversed, A7 first.
            for (int i = 0; i < 16; i++) {
                if ((mask & (1 << i)) == 0) continue;
                final int rn = 15 - i;
                a -= size;
                int v = c.r[rn];
                if (rn == 8 + reg) v -= size;
                c.write(a, v, size);
            }
            c.r[8 + reg] = a;
        } else {
            int a = c.ea(mode, reg, size);
            for (int i = 0; i < 16; i++) {
                if ((mask & (1 << i)) == 0) continue;
                c.write(a, c.r[i], size);
                a += size;
            }
        }
        c.prefetchNext();
        c.advance();
    }

    /** MOVEM memory to registers (words are sign-extended). */
    static void movemToReg(Motorola68020 c, int op) {
        final int size = (op & 0x40) != 0 ? 4 : 2;
        final int mask = c.nextWord();
        final int mode = (op >> 3) & 7, reg = op & 7;
        int a = mode == 3 ? c.r[8 + reg] : c.ea(mode, reg, size);
        for (int i = 0; i < 16; i++) {
            if ((mask & (1 << i)) == 0) continue;
            c.r[i] = size == 4 ? c.readLong(a) : (short) c.readWord(a);
            a += size;
        }
        if (mode == 3) c.r[8 + reg] = a;
        c.prefetchNext();
        c.advance();
    }

    static void movep(Motorola68020 c, int op) {
        final int dreg = (op >> 9) & 7;
        final int a = c.r[8 + (op & 7)] + (short) c.nextWord();
        switch ((op >> 6) & 3) {
            case 0: {
                int v = (c.readByte(a) & 0xFF) << 8;
                v |= c.readByte(a + 2) & 0xFF;
                c.prefetchNext();
                c.setD(dreg, v, 2);
                break;
            }
            case 1: {
                int v = (c.readByte(a) & 0xFF) << 24;
                v |= (c.readByte(a + 2) & 0xFF) << 16;
                v |= (c.readByte(a + 4) & 0xFF) << 8;
                v |= c.readByte(a + 6) & 0xFF;
                c.prefetchNext();
                c.r[dreg] = v;
                break;
            }
            case 2: {
                final int v = c.r[dreg];
                c.writeByte(a, v >> 8);
                c.writeByte(a + 2, v);
                c.prefetchNext();
                break;
            }
            default: {
                final int v = c.r[dreg];
                c.writeByte(a, v >> 24);
                c.writeByte(a + 2, v >> 16);
                c.writeByte(a + 4, v >> 8);
                c.writeByte(a + 6, v);
                c.prefetchNext();
                break;
            }
        }
        c.advance();
    }

    // =======================================================================================
    // Binary operations: kinds
    // =======================================================================================

    static final int ADD = 0, SUB = 1, AND = 2, OR = 3, EOR = 4, CMP = 5;

    /** The operation with its flags; returns the result. */
    static int alu(Motorola68020 c, int kind, int src, int dst, int size) {
        switch (kind) {
            case ADD: {
                final int r = dst + src;
                c.addFlags(src, dst, r, size);
                return r;
            }
            case SUB: {
                final int r = dst - src;
                c.subFlags(src, dst, r, size);
                return r;
            }
            case CMP: {
                final int r = dst - src;
                c.cmpFlags(src, dst, r, size);
                return r;
            }
            case AND: {
                final int r = dst & src;
                c.setNZ(r, size);
                return r;
            }
            case OR: {
                final int r = dst | src;
                c.setNZ(r, size);
                return r;
            }
            default: {
                final int r = dst ^ src;
                c.setNZ(r, size);
                return r;
            }
        }
    }

    /** {@code op <ea>,Dn}. */
    static void eaToDn(Motorola68020 c, int op, int kind) {
        final int size = size(op);
        final int src = c.readEa((op >> 3) & 7, op & 7, size);
        final int dn = (op >> 9) & 7;
        final int dst = c.r[dn];
        c.prefetchNext();
        final int r = alu(c, kind, src, dst, size);
        if (kind != CMP) c.setD(dn, r, size);
        c.advance();
    }

    /** {@code op Dn,<ea>} with a memory destination: the opcode prefetch comes before the read. */
    static void dnToEa(Motorola68020 c, int op, int kind) {
        final int size = size(op);
        final int src = c.r[(op >> 9) & 7];
        final int mode = (op >> 3) & 7, reg = op & 7;
        if (mode == 0) {
            final int dst = c.r[reg];
            final int r = alu(c, kind, src, dst, size);
            c.prefetchNext();
            c.setD(reg, r, size);
            c.advance();
            return;
        }
        final int a = c.ea(mode, reg, size);
        c.prefetchNext();
        final int dst = c.read(a, size);
        final int r = alu(c, kind, src, dst, size);
        c.write(a, r, size);
        c.advance();
    }

    /** {@code op #imm,<ea>}: the memory operand is read before the opcode prefetch. */
    static void immToEa(Motorola68020 c, int op, int kind) {
        final int size = size(op);
        final int src = size == 4 ? c.nextLong() : size == 2 ? c.nextWord() : c.nextWord() & 0xFF;
        final int mode = (op >> 3) & 7, reg = op & 7;
        if (mode == 0) {
            final int dst = c.r[reg];
            final int r = alu(c, kind, src, dst, size);
            c.prefetchNext();
            if (kind != CMP) c.setD(reg, r, size);
            c.advance();
            return;
        }
        final int a = c.ea(mode, reg, size);
        final int dst = c.read(a, size);
        c.prefetchNext();
        final int r = alu(c, kind, src, dst, size);
        if (kind != CMP) c.write(a, r, size);
        c.advance();
    }

    static void addEaDn(Motorola68020 c, int op) { eaToDn(c, op, ADD); }
    static void subEaDn(Motorola68020 c, int op) { eaToDn(c, op, SUB); }
    static void andEaDn(Motorola68020 c, int op) { eaToDn(c, op, AND); }
    static void orEaDn(Motorola68020 c, int op) { eaToDn(c, op, OR); }
    static void cmpEaDn(Motorola68020 c, int op) { eaToDn(c, op, CMP); }
    static void addDnEa(Motorola68020 c, int op) { dnToEa(c, op, ADD); }
    static void subDnEa(Motorola68020 c, int op) { dnToEa(c, op, SUB); }
    static void andDnEa(Motorola68020 c, int op) { dnToEa(c, op, AND); }
    static void orDnEa(Motorola68020 c, int op) { dnToEa(c, op, OR); }
    static void eorDnEa(Motorola68020 c, int op) { dnToEa(c, op, EOR); }
    static void addi(Motorola68020 c, int op) { immToEa(c, op, ADD); }
    static void subi(Motorola68020 c, int op) { immToEa(c, op, SUB); }
    static void andi(Motorola68020 c, int op) { immToEa(c, op, AND); }
    static void ori(Motorola68020 c, int op) { immToEa(c, op, OR); }
    static void eori(Motorola68020 c, int op) { immToEa(c, op, EOR); }
    static void cmpi(Motorola68020 c, int op) { immToEa(c, op, CMP); }

    /** ADDA/SUBA/CMPA: word sources are sign-extended, the operation is on 32 bits. */
    static void addressOp(Motorola68020 c, int op, int kind) {
        final int size = (op & 0x100) != 0 ? 4 : 2;
        int src = c.readEa((op >> 3) & 7, op & 7, size);
        if (size == 2) src = (short) src;
        final int an = 8 + ((op >> 9) & 7);
        final int dst = c.r[an];
        if (kind == CMP) {
            c.cmpFlags(src, dst, dst - src, 4);
            c.prefetchNext();
        } else {
            c.prefetchNext();
            c.r[an] = kind == ADD ? dst + src : dst - src;
        }
        c.advance();
    }

    static void adda(Motorola68020 c, int op) { addressOp(c, op, ADD); }
    static void suba(Motorola68020 c, int op) { addressOp(c, op, SUB); }
    static void cmpa(Motorola68020 c, int op) { addressOp(c, op, CMP); }

    /** ADDQ/SUBQ. To an address register: no flags, always 32 bits. */
    static void quick(Motorola68020 c, int op) {
        int q = (op >> 9) & 7;
        if (q == 0) q = 8;
        final boolean sub = (op & 0x100) != 0;
        final int kind = sub ? SUB : ADD;
        final int size = size(op);
        final int mode = (op >> 3) & 7, reg = op & 7;
        if (mode == 1) {
            final int v = c.r[8 + reg];
            final int r = sub ? v - q : v + q;
            c.prefetchNext();
            c.r[8 + reg] = r;
            c.advance();
            return;
        }
        if (mode == 0) {
            final int r = alu(c, kind, q, c.r[reg], size);
            c.prefetchNext();
            c.setD(reg, r, size);
            c.advance();
            return;
        }
        final int a = c.ea(mode, reg, size);
        c.prefetchNext();
        final int dst = c.read(a, size);
        final int r = alu(c, kind, q, dst, size);
        c.write(a, r, size);
        c.advance();
    }

    /** CMPM (Ay)+,(Ax)+. */
    static void cmpm(Motorola68020 c, int op) {
        final int size = size(op);
        final int src = c.read(c.ea(3, op & 7, size), size);
        final int dst = c.read(c.ea(3, (op >> 9) & 7, size), size);
        c.cmpFlags(src, dst, dst - src, size);
        c.prefetchNext();
        c.advance();
    }

    // =======================================================================================
    // ADDX/SUBX/ABCD/SBCD
    // =======================================================================================

    static final int ADDX = 0, SUBX = 1, ABCD = 2, SBCD = 3;

    static int xop(Motorola68020 c, int kind, int src, int dst, int size) {
        final int x = c.fx ? 1 : 0;
        switch (kind) {
            case ADDX: {
                final int r = dst + src + x;
                c.addxFlags(src, dst, r, size);
                return r;
            }
            case SUBX: {
                final int r = dst - src - x;
                c.subxFlags(src, dst, r, size);
                return r;
            }
            case ABCD: return abcd(c, src, dst);
            default: return sbcd(c, src, dst);
        }
    }

    static int abcd(Motorola68020 c, int src, int dst) {
        final int x = c.fx ? 1 : 0;
        final int lo = (src & 0xF) + (dst & 0xF) + x;
        final int hi = (src & 0xF0) + (dst & 0xF0);
        int v = (hi + lo) & 0xFFFF;
        if (lo > 9) v += 6;
        final boolean carry = (v & 0x3F0) > 0x90;
        if (carry) v += 0x60;
        c.fc = carry;
        c.fx = carry;
        c.fz = c.fz & (v & 0xFF) == 0;
        c.fn = (v & 0x80) != 0;
        c.fv = false;
        return v;
    }

    static int sbcd(Motorola68020 c, int src, int dst) {
        final int x = c.fx ? 1 : 0;
        final int lo = ((dst & 0xF) - (src & 0xF) - x) & 0xFFFF;
        final int hi = ((dst & 0xF0) - (src & 0xF0)) & 0xFFFF;
        int v = (hi + lo) & 0xFFFF;
        int bcd = 0;
        if ((lo & 0xF0) != 0) {
            v -= 6;
            bcd = 6;
        }
        if ((((dst & 0xFF) - (src & 0xFF) - x) & 0x100) > 0xFF) v -= 0x60;
        final boolean carry = (((dst & 0xFF) - (src & 0xFF) - bcd - x) & 0x300) > 0xFF;
        c.fc = carry;
        c.fx = carry;
        c.fz = c.fz & (v & 0xFF) == 0;
        c.fn = (v & 0x80) != 0;
        c.fv = false;
        return v;
    }

    static void xRegister(Motorola68020 c, int op, int kind, int size) {
        final int rx = (op >> 9) & 7, ry = op & 7;
        final int r = xop(c, kind, c.r[ry], c.r[rx], size);
        c.prefetchNext();
        c.setD(rx, r, size);
        c.advance();
    }

    static void xMemory(Motorola68020 c, int op, int kind, int size) {
        final int src = c.read(c.ea(4, op & 7, size), size);
        final int a = c.ea(4, (op >> 9) & 7, size);
        final int dst = c.read(a, size);
        final int r = xop(c, kind, src, dst, size);
        c.prefetchNext();
        c.write(a, r, size);
        c.advance();
    }

    static void addx(Motorola68020 c, int op) {
        if ((op & 8) != 0) xMemory(c, op, ADDX, size(op)); else xRegister(c, op, ADDX, size(op));
    }

    static void subx(Motorola68020 c, int op) {
        if ((op & 8) != 0) xMemory(c, op, SUBX, size(op)); else xRegister(c, op, SUBX, size(op));
    }

    static void abcdOp(Motorola68020 c, int op) {
        if ((op & 8) != 0) xMemory(c, op, ABCD, 1); else xRegister(c, op, ABCD, 1);
    }

    static void sbcdOp(Motorola68020 c, int op) {
        if ((op & 8) != 0) xMemory(c, op, SBCD, 1); else xRegister(c, op, SBCD, 1);
    }

    // =======================================================================================
    // Unary operations
    // =======================================================================================

    static final int NEG = 0, NEGX = 1, NOT = 2, NBCD = 3;

    static int unary(Motorola68020 c, int kind, int v, int size) {
        switch (kind) {
            case NEG: {
                final int r = -v;
                c.subFlags(v, 0, r, size);
                return r;
            }
            case NEGX: {
                final int r = 0 - v - (c.fx ? 1 : 0);
                c.subxFlags(v, 0, r, size);
                return r;
            }
            case NOT: {
                final int r = ~v;
                c.setNZ(r, size);
                return r;
            }
            default: return nbcd(c, v);
        }
    }

    static int nbcd(Motorola68020 c, int src) {
        final int x = c.fx ? 1 : 0;
        int lo = (-(src & 0xF) - x) & 0xFFFF;
        final int hi = (-(src & 0xF0)) & 0xFFFF;
        if (lo > 9) lo = (lo - 6) & 0xFFFF;
        int v = (hi + lo) & 0xFFFF;
        final boolean carry = (v & 0x1F0) > 0x90;
        if (carry) v -= 0x60;
        c.fc = carry;
        c.fx = carry;
        c.fz = c.fz & (v & 0xFF) == 0;
        c.fn = (v & 0x80) != 0;
        c.fv = false;
        return v;
    }

    static void unaryOp(Motorola68020 c, int op, int kind, int size) {
        final int mode = (op >> 3) & 7, reg = op & 7;
        if (mode == 0) {
            final int r = unary(c, kind, c.r[reg], size);
            c.prefetchNext();
            c.setD(reg, r, size);
            c.advance();
            return;
        }
        final int a = c.ea(mode, reg, size);
        final int v = c.read(a, size);
        c.prefetchNext();
        final int r = unary(c, kind, v, size);
        c.write(a, r, size);
        c.advance();
    }

    static void neg(Motorola68020 c, int op) { unaryOp(c, op, NEG, size(op)); }
    static void negx(Motorola68020 c, int op) { unaryOp(c, op, NEGX, size(op)); }
    static void not(Motorola68020 c, int op) { unaryOp(c, op, NOT, size(op)); }
    static void nbcdOp(Motorola68020 c, int op) { unaryOp(c, op, NBCD, 1); }

    /** PACK: unpacked BCD pair to a packed byte, adjusted by the extension word. */
    static void pack(Motorola68020 c, int op) {
        final int rx = (op >> 9) & 7, ry = op & 7;
        if ((op & 8) == 0) {
            final int val = (c.r[ry] + c.nextWord()) & 0xFFFF;
            c.r[rx] = (c.r[rx] & 0xFFFFFF00) | ((val >> 4) & 0xF0) | (val & 0xF);
        } else {
            c.r[8 + ry] -= 2;
            int val = c.readWord(c.r[8 + ry]) & 0xFFFF;
            val = (val + c.nextWord()) & 0xFFFF;
            c.r[8 + rx] -= rx == 7 ? 2 : 1;
            c.writeByte(c.r[8 + rx], ((val >> 4) & 0xF0) | (val & 0xF));
        }
        c.prefetchNext();
        c.advance();
    }

    /** UNPK: packed BCD byte to an unpacked pair, adjusted by the extension word. */
    static void unpk(Motorola68020 c, int op) {
        final int rx = (op >> 9) & 7, ry = op & 7;
        if ((op & 8) == 0) {
            int val = c.r[ry] & 0xFFFF;
            val = ((val << 4) & 0xF00) | (val & 0xF);
            val = (val + c.nextWord()) & 0xFFFF;
            c.r[rx] = (c.r[rx] & 0xFFFF0000) | val;
        } else {
            c.r[8 + ry] -= ry == 7 ? 2 : 1;
            int val = c.readByte(c.r[8 + ry]) & 0xFF;
            val = ((val << 4) & 0xF00) | (val & 0xF);
            val = (val + c.nextWord()) & 0xFFFF;
            c.r[8 + rx] -= 2;
            c.writeWord(c.r[8 + rx], val);
        }
        c.prefetchNext();
        c.advance();
    }
}
