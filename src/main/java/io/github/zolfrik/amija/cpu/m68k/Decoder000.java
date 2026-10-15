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
 * The decode tables of the event-driven 68000 and 68010: one handler per opcode, the illegal
 * handler where the opcode is not an instruction of the model, and the privilege of each
 * opcode.
 */
final class Decoder000 {
    private Decoder000() {}

    // Effective address codes: 0 Dn, 1 An, 2 (An), 3 (An)+, 4 -(An), 5 (d16,An), 6 (d8,An,Xn),
    // 7 abs.W, 8 abs.L, 9 (d16,PC), 10 (d8,PC,Xn), 11 #imm.
    static final int DN = 1, AN = 2, IMM = 1 << 11, PCREL = (1 << 9) | (1 << 10);
    static final int ALL = 0xFFF;
    static final int DATA = ALL & ~AN;
    static final int ALT = 0x1FF;
    static final int DALT = ALT & ~AN;
    static final int MALT = ALT & ~(AN | DN);
    static final int CTRL = (1 << 2) | (1 << 5) | (1 << 6) | (1 << 7) | (1 << 8) | PCREL;
    static final int CTRL_ALT = CTRL & ~PCREL;

    private static final Op000 ILLEGAL = Ops000Flow::illegal;

    private static final Op000[] TABLE_68000 = build(false);
    private static final Op000[] TABLE_68010 = build(true);

    static Op000[] table(boolean m68010) {
        return m68010 ? TABLE_68010 : TABLE_68000;
    }

    static boolean isIllegal(int op, boolean m68010) {
        return table(m68010)[op & 0xFFFF] == ILLEGAL;
    }

    /** True if the opcode is an instruction that only runs in supervisor mode. */
    static boolean isPrivileged(int op, boolean m68010) {
        op &= 0xFFFF;
        if (isIllegal(op, m68010)) return false;
        if (op == 0x007C || op == 0x027C || op == 0x0A7C) return true;
        if ((op & 0xFFC0) == 0x46C0) return true;
        if (op == 0x4E70 || op == 0x4E72 || op == 0x4E73) return true;
        if ((op & 0xFFF0) == 0x4E60) return true;
        if (m68010) {
            if ((op & 0xFFC0) == 0x40C0) return true;
            if (op == 0x4E7A || op == 0x4E7B) return true;
            if ((op & 0xFF00) == 0x0E00) return true;
        }
        return false;
    }

    private static Op000[] build(boolean m68010) {
        final Op000[] t = new Op000[65536];
        for (int op = 0; op < 65536; op++) {
            final Op000 h = decode(op, m68010);
            t[op] = h != null ? h : ILLEGAL;
        }
        return t;
    }

    static int code(int mode, int reg) {
        if (mode < 7) return mode;
        return reg <= 4 ? 7 + reg : -1;
    }

    /** True if the low six bits of {@code op} are a valid EA in {@code set}; byte never uses An. */
    private static boolean ea(int op, int set, int size) {
        final int code = code((op >> 3) & 7, op & 7);
        if (code < 0 || (set & (1 << code)) == 0) return false;
        return !(code == 1 && size == 1);
    }

    private static int sizeOf(int op) {
        final int z = (op >> 6) & 3;
        return z == 0 ? 1 : z == 1 ? 2 : z == 2 ? 4 : 0;
    }

    private static Op000 decode(int op, boolean m68010) {
        switch (op >>> 12) {
            case 0x0: return line0(op, m68010);
            case 0x1: case 0x2: case 0x3: return lineMove(op);
            case 0x4: return line4(op, m68010);
            case 0x5: return line5(op);
            case 0x6: return (op & 0xFF00) == 0x6100 ? (Op000) Ops000Flow::bsr : Ops000Flow::bcc;
            case 0x7: return (op & 0x100) == 0 ? (Op000) Ops000Alu::moveq : null;
            case 0x8: return line8(op);
            case 0x9: return lineAddSub(op, false);
            case 0xB: return lineB(op);
            case 0xC: return lineC(op);
            case 0xD: return lineAddSub(op, true);
            case 0xE: return lineE(op);
            default: return null;
        }
    }

    private static Op000 line0(int op, boolean m68010) {
        if (op == 0x003C || op == 0x023C || op == 0x0A3C) return Ops000Flow::immediateToSr;
        if (op == 0x007C || op == 0x027C || op == 0x0A7C) return Ops000Flow::immediateToSr;
        if ((op & 0x0100) != 0) {
            if (((op >> 3) & 7) == 1) return Ops000Move::movep;
            final int kind = (op >> 6) & 3;
            return ea(op, kind == 0 ? DATA : DALT, 1) ? Ops000Bit::bitDynamic : null;
        }
        final int hi = (op >> 9) & 7;
        if (hi == 4) {
            final int kind = (op >> 6) & 3;
            return ea(op, kind == 0 ? DATA & ~IMM : DALT, 1) ? Ops000Bit::bitStatic : null;
        }
        if (hi == 7) {
            if (m68010 && sizeOf(op) != 0 && ea(op, MALT, sizeOf(op))) return Ops010::moves;
            return null;
        }
        if (hi == 6 && sizeOf(op) != 0) {
            return ea(op, DALT, sizeOf(op)) ? Ops000Alu::immediate : null;
        }
        final int size = sizeOf(op);
        if (size == 0) return null;
        return ea(op, DALT, size) ? Ops000Alu::immediate : null;
    }

    private static Op000 lineMove(int op) {
        final int sz = (op >> 12) & 3;
        final int size = sz == 1 ? 1 : sz == 3 ? 2 : 4;
        if (!ea(op, ALL, size)) return null;
        final int dmode = (op >> 6) & 7, dreg = (op >> 9) & 7;
        if (dmode == 1) return size == 1 ? null : Ops000Move::move;
        final int dcode = code(dmode, dreg);
        return dcode >= 0 && (DALT & (1 << dcode)) != 0 ? Ops000Move::move : null;
    }

    private static Op000 line4(int op, boolean m68010) {
        final int size = sizeOf(op);
        switch (op & 0xFFC0) {
            case 0x40C0: return ea(op, DALT, 2) ? Ops000Flow::moveFromSr : null;
            case 0x42C0: return m68010 && ea(op, DALT, 2) ? Ops010::moveFromCcr : null;
            case 0x44C0: return ea(op, DATA, 2) ? Ops000Flow::moveToCcr : null;
            case 0x46C0: return ea(op, DATA, 2) ? Ops000Flow::moveToSr : null;
            case 0x4800: return ea(op, DALT, 1) ? Ops000Alu::nbcd : null;
            case 0x4840:
                if (((op >> 3) & 7) == 0) return Ops000Alu::swap;
                if (((op >> 3) & 7) == 1) return m68010 ? Ops010::bkpt : null;
                return ea(op, CTRL, 4) ? Ops000Move::pea : null;
            case 0x4880: case 0x48C0:
                if (((op >> 3) & 7) == 0) return Ops000Alu::ext;
                return ea(op, CTRL_ALT | (1 << 4), 2) ? Ops000Move::movemToMem : null;
            case 0x4AC0:
                if (op == 0x4AFC) return null;
                return ea(op, DALT, 1) ? Ops000Bit::tas : null;
            case 0x4C80: case 0x4CC0:
                return ea(op, CTRL | (1 << 3), 2) ? Ops000Move::movemToReg : null;
            case 0x4E80: return ea(op, CTRL, 4) ? Ops000Flow::jsr : null;
            case 0x4EC0: return ea(op, CTRL, 4) ? Ops000Flow::jmp : null;
            default: break;
        }
        if ((op & 0xF1C0) == 0x41C0) return ea(op, CTRL, 4) ? Ops000Move::lea : null;
        if ((op & 0xF1C0) == 0x4180) return ea(op, DATA, 2) ? Ops000Flow::chk : null;
        if ((op & 0xFFF0) == 0x4E40) return Ops000Flow::trap;
        if ((op & 0xFFF8) == 0x4E50) return Ops000Flow::link;
        if ((op & 0xFFF8) == 0x4E58) return Ops000Flow::unlk;
        if ((op & 0xFFF8) == 0x4E60) return Ops000Flow::moveToUsp;
        if ((op & 0xFFF8) == 0x4E68) return Ops000Flow::moveFromUsp;
        switch (op) {
            case 0x4E70: return Ops000Flow::reset;
            case 0x4E71: return Ops000Flow::nop;
            case 0x4E72: return Ops000Flow::stop;
            case 0x4E73: return m68010 ? Ops010::rte : Ops000Flow::rte;
            case 0x4E74: return m68010 ? Ops010::rtd : null;
            case 0x4E75: return Ops000Flow::rts;
            case 0x4E76: return Ops000Flow::trapv;
            case 0x4E77: return Ops000Flow::rtr;
            case 0x4E7A: case 0x4E7B: return m68010 ? Ops010::movec : null;
            default: break;
        }
        if (size == 0) return null;
        switch (op & 0xFF00) {
            case 0x4000: return ea(op, DALT, size) ? Ops000Alu::negx : null;
            case 0x4200: return ea(op, DALT, size) ? Ops000Alu::clr : null;
            case 0x4400: return ea(op, DALT, size) ? Ops000Alu::neg : null;
            case 0x4600: return ea(op, DALT, size) ? Ops000Alu::not : null;
            case 0x4A00: return ea(op, DALT, size) ? Ops000Alu::tst : null;
            default: return null;
        }
    }

    private static Op000 line5(int op) {
        final int size = sizeOf(op);
        if (size == 0) {
            if (((op >> 3) & 7) == 1) return Ops000Flow::dbcc;
            return ea(op, DALT, 1) ? Ops000Bit::scc : null;
        }
        return ea(op, ALT, size) ? Ops000Alu::quick : null;
    }

    private static Op000 line8(int op) {
        if ((op & 0xF1C0) == 0x80C0) return ea(op, DATA, 2) ? Ops000Alu::divu : null;
        if ((op & 0xF1C0) == 0x81C0) return ea(op, DATA, 2) ? Ops000Alu::divs : null;
        if ((op & 0xF1F0) == 0x8100) return Ops000Alu::sbcd;
        return logical(op, Ops000Alu::or);
    }

    private static Op000 lineC(int op) {
        if ((op & 0xF1C0) == 0xC0C0) return ea(op, DATA, 2) ? Ops000Alu::mulu : null;
        if ((op & 0xF1C0) == 0xC1C0) return ea(op, DATA, 2) ? Ops000Alu::muls : null;
        if ((op & 0xF1F0) == 0xC100) return Ops000Alu::abcd;
        if ((op & 0xF1F8) == 0xC140 || (op & 0xF1F8) == 0xC148 || (op & 0xF1F8) == 0xC188) return Ops000Alu::exg;
        return logical(op, Ops000Alu::and);
    }

    /** AND/OR: {@code <ea>,Dn} from data modes, {@code Dn,<ea>} to memory alterable modes. */
    private static Op000 logical(int op, Op000 handler) {
        final int size = sizeOf(op);
        if (size == 0) return null;
        if ((op & 0x100) == 0) return ea(op, DATA, size) ? handler : null;
        return ea(op, MALT, size) ? handler : null;
    }

    private static Op000 lineAddSub(int op, boolean add) {
        final int opmode = (op >> 6) & 7;
        if (opmode == 3 || opmode == 7) {
            return ea(op, ALL, 4) ? (add ? (Op000) Ops000Alu::adda : Ops000Alu::suba) : null;
        }
        final int size = sizeOf(op);
        if ((op & 0x130) == 0x100) return add ? Ops000Alu::addx : Ops000Alu::subx;
        if ((op & 0x100) == 0) return ea(op, ALL, size) ? (add ? (Op000) Ops000Alu::add : Ops000Alu::sub) : null;
        return ea(op, MALT, size) ? (add ? (Op000) Ops000Alu::add : Ops000Alu::sub) : null;
    }

    private static Op000 lineB(int op) {
        final int opmode = (op >> 6) & 7;
        if (opmode == 3 || opmode == 7) return ea(op, ALL, 4) ? Ops000Alu::cmpa : null;
        final int size = sizeOf(op);
        if (opmode < 3) return ea(op, ALL, size) ? Ops000Alu::cmp : null;
        if (((op >> 3) & 7) == 1) return Ops000Alu::cmpm;
        return ea(op, DALT, size) ? Ops000Alu::eor : null;
    }

    private static Op000 lineE(int op) {
        if (((op >> 6) & 3) == 3) {
            if ((op & 0x0800) != 0) return null;
            return ea(op, MALT, 2) ? Ops000Bit::shiftMem : null;
        }
        return Ops000Bit::shiftReg;
    }
}
