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
 * The decode table of the 68EC020: one handler per opcode, and for every opcode the data the
 * predecoder of the instruction queue uses (length, position of the index extension words,
 * whether the instruction is a branch that stops the prefetch).
 *
 * <p>The length counts the opcode and every extension word except the index extension
 * words of the {@code (d8,An,Xn)} and {@code (d8,PC,Xn)} modes, whose own length the
 * predecoder reads from the word itself; {@link #DISP0}/{@link #DISP1} give their position,
 * counted back from the end of the instruction.
 */
final class Decoder020 {
    private Decoder020() {}

    static final Op020[] OPS = new Op020[65536];
    static final byte[] LENGTH = new byte[65536];
    static final byte[] DISP0 = new byte[65536];
    static final byte[] DISP1 = new byte[65536];
    static final byte[] BRANCH = new byte[65536];

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

    private static final Op020 ILLEGAL = OpsFlow020::illegal;

    // Length accumulation for the opcode being decoded.
    private static int len;
    private static int nr8;
    private static final int[] r8 = new int[2];

    static {
        for (int op = 0; op < 65536; op++) {
            len = 2;
            nr8 = 0;
            Op020 h = decode(op);
            if (h == null) {
                OPS[op] = ILLEGAL;
                LENGTH[op] = 2;
                continue;
            }
            OPS[op] = h;
            LENGTH[op] = (byte) len;
            DISP0[op] = (byte) (nr8 > 0 ? len - r8[0] + 2 : 0);
            DISP1[op] = (byte) (nr8 > 1 ? len - r8[1] + 2 : 0);
        }
    }

    static int code(int mode, int reg) {
        if (mode < 7) return mode;
        return reg <= 4 ? 7 + reg : -1;
    }

    /** Accounts the extension words of an effective address, in stream order. */
    private static void eaWords(int code, int size) {
        switch (code) {
            case 5: case 7: case 9: len += 2; break;
            case 8: len += 4; break;
            case 6: case 10: r8[nr8++] = len; break;
            case 11: len += size == 4 ? 4 : 2; break;
            default: break;
        }
    }

    /** Valid EA of {@code op}'s low six bits in {@code set}; byte accesses never use An. */
    private static int ea(int op, int set, int size) {
        final int code = code((op >> 3) & 7, op & 7);
        if (code < 0 || (set & (1 << code)) == 0) return -1;
        if (code == 1 && size == 1) return -1;
        return code;
    }

    private static int sizeOf(int op) {
        final int z = (op >> 6) & 3;
        return z == 0 ? 1 : z == 1 ? 2 : z == 2 ? 4 : 0;
    }

    private static Op020 decode(int op) {
        switch (op >>> 12) {
            case 0x0: return line0(op);
            case 0x1: case 0x2: case 0x3: return lineMove(op);
            case 0x4: return line4(op);
            case 0x5: return line5(op);
            case 0x6: return line6(op);
            case 0x7: return (op & 0x100) == 0 ? (Op020) OpsAlu020::moveq : null;
            case 0x8: return line8(op);
            case 0x9: return lineAddSub(op, false);
            case 0xB: return lineB(op);
            case 0xC: return lineC(op);
            case 0xD: return lineAddSub(op, true);
            case 0xE: return lineE(op);
            case 0xF: return lineF(op);
            default: return null; // line A
        }
    }

    // ---------------------------------------------------------------------------------------

    private static Op020 line0(int op) {
        final int size = sizeOf(op);
        // Immediate to CCR/SR.
        if (op == 0x003C || op == 0x023C || op == 0x0A3C) { len += 2; return OpsFlow020::ccrImm; }
        if (op == 0x007C || op == 0x027C || op == 0x0A7C) { len += 2; return OpsFlow020::srImm; }
        if (op == 0x0CFC || op == 0x0EFC) { len += 4; return OpsBit020::cas2; }
        if ((op & 0x0100) != 0) {
            if (((op >> 3) & 7) == 1) { len += 2; return OpsAlu020::movep; }
            final int kind = (op >> 6) & 3;
            final int code = ea(op, kind == 0 ? DATA : DALT, 1);
            if (code < 0) return null;
            eaWords(code, 1);
            return OpsBit020::bitDynamic;
        }
        final int hi = (op >> 9) & 7;
        if (size == 0) {
            // Size field 11: CHK2/CMP2, CAS, CALLM/RTM.
            if (hi <= 2) {
                final int code = ea(op, CTRL, 1);
                if (code < 0) return null;
                len += 2;
                eaWords(code, 1);
                return OpsBit020::chk2;
            }
            if (hi == 3) {
                if ((op & 0x30) == 0) return OpsFlow020::callm; // RTM
                final int code = ea(op, CTRL, 1);
                if (code < 0) return null;
                return OpsFlow020::callm;
            }
            if (hi >= 5) {
                final int code = ea(op, MALT, 1);
                if (code < 0) return null;
                len += 2;
                eaWords(code, 1);
                return OpsBit020::cas;
            }
            if (hi == 4) {
                // BSET #n,<ea> (static, kind 3).
                final int code = ea(op, DALT, 1);
                if (code < 0) return null;
                len += 2;
                eaWords(code, 1);
                return OpsBit020::bitStatic;
            }
            return null;
        }
        switch (hi) {
            case 0: case 1: case 2: case 3: case 5: case 6: {
                if (hi == 3 && false) return null;
                final int set = hi == 6 ? DATA & ~IMM & ~AN : DALT;
                final int code = ea(op, hi == 6 ? (DATA & ~IMM) : DALT, size);
                if (code < 0) return null;
                len += size == 4 ? 4 : 2;
                eaWords(code, size);
                switch (hi) {
                    case 0: return OpsAlu020::ori;
                    case 1: return OpsAlu020::andi;
                    case 2: return OpsAlu020::subi;
                    case 3: return OpsAlu020::addi;
                    case 5: return OpsAlu020::eori;
                    default: return OpsAlu020::cmpi;
                }
            }
            case 4: {
                // Static bit operations: 0000 1000 tt.
                final int kind = (op >> 6) & 3;
                final int code = ea(op, kind == 0 ? DATA & ~IMM : DALT, 1);
                if (code < 0) return null;
                len += 2;
                eaWords(code, 1);
                return OpsBit020::bitStatic;
            }
            default: {
                // MOVES: 0000 1110 zz. Both directions are predecoded: an index extension
                // word is recorded twice.
                final int code = ea(op, MALT, size);
                if (code < 0) return null;
                len += 2;
                eaWords(code, size);
                if (code == 6) r8[nr8++] = r8[0];
                return OpsFlow020::moves;
            }
        }
    }

    private static Op020 lineMove(int op) {
        final int z = (op >> 12) & 3;
        final int size = z == 1 ? 1 : z == 3 ? 2 : 4;
        final int src = ea(op, ALL, size);
        if (src < 0) return null;
        final int dmode = (op >> 6) & 7, dreg = (op >> 9) & 7;
        if (dmode == 1) {
            if (size == 1) return null;
            eaWords(src, size);
            return OpsAlu020::movea;
        }
        final int dst = code(dmode, dreg);
        if (dst < 0 || (DALT & (1 << dst)) == 0) return null;
        eaWords(src, size);
        eaWords(dst, size);
        return OpsAlu020::move;
    }

    private static Op020 line4(int op) {
        final int size = sizeOf(op);
        final int sub = (op >> 8) & 0xF;
        if ((op & 0x0100) != 0) {
            if (op >= 0x49C0 && op <= 0x49C7) return OpsAlu020::extbl;
            final int t = (op >> 6) & 3;
            if (t == 3) {
                final int code = ea(op, CTRL, 4);
                if (code < 0) return null;
                eaWords(code, 4);
                return OpsAlu020::lea;
            }
            if (t == 2 || t == 0) {
                // CHK.W (t=2), CHK.L (t=0)
                final int code = ea(op, DATA, t == 2 ? 2 : 4);
                if (code < 0) return null;
                eaWords(code, t == 2 ? 2 : 4);
                return OpsBit020::chk;
            }
            return null;
        }
        switch (sub) {
            case 0x0: case 0x2: case 0x4: case 0x6: {
                if (size == 0) {
                    final int set = sub == 0 || sub == 2 ? DALT : DATA;
                    final int code = ea(op, set, 2);
                    if (code < 0) return null;
                    eaWords(code, 2);
                    switch (sub) {
                        case 0x0: return OpsFlow020::moveFromSr;
                        case 0x2: return OpsFlow020::moveFromCcr;
                        case 0x4: return OpsFlow020::moveToCcr;
                        default: return OpsFlow020::moveToSr;
                    }
                }
                final int code = ea(op, DALT, size);
                if (code < 0) return null;
                eaWords(code, size);
                switch (sub) {
                    case 0x0: return OpsAlu020::negx;
                    case 0x2: return OpsAlu020::clr;
                    case 0x4: return OpsAlu020::neg;
                    default: return OpsAlu020::not;
                }
            }
            case 0x8: {
                final int t = (op >> 6) & 3;
                final int mode = (op >> 3) & 7;
                if (t == 0) {
                    if (mode == 1) { len += 4; return OpsFlow020::linkLong; }
                    final int code = ea(op, DALT, 1);
                    if (code < 0) return null;
                    eaWords(code, 1);
                    return OpsAlu020::nbcdOp;
                }
                if (t == 1) {
                    if (mode == 0) return OpsAlu020::swap;
                    if (mode == 1) return OpsFlow020::illegal; // BKPT
                    final int code = ea(op, CTRL, 4);
                    if (code < 0) return null;
                    eaWords(code, 4);
                    return OpsAlu020::pea;
                }
                if (mode == 0) return t == 2 ? (Op020) OpsAlu020::extw : OpsAlu020::extl;
                final int code = ea(op, CTRL_ALT | (1 << 4), 2);
                if (code < 0) return null;
                len += 2;
                eaWords(code, 2);
                return OpsAlu020::movemToMem;
            }
            case 0xA: {
                if (op == 0x4AFC) return OpsFlow020::illegal;
                if (size == 0) {
                    final int code = ea(op, DALT, 1);
                    if (code < 0) return null;
                    eaWords(code, 1);
                    return OpsBit020::tas;
                }
                final int code = ea(op, ALL, size);
                if (code < 0) return null;
                eaWords(code, size);
                return OpsAlu020::tst;
            }
            case 0xC: {
                final int t = (op >> 6) & 3;
                if (t <= 1) {
                    final int code = ea(op, DATA, 4);
                    if (code < 0) return null;
                    len += 2;
                    eaWords(code, 4);
                    return t == 0 ? (Op020) OpsBit020::mull : OpsBit020::divl;
                }
                final int code = ea(op, CTRL | (1 << 3), 2);
                if (code < 0) return null;
                len += 2;
                eaWords(code, 2);
                return OpsAlu020::movemToReg;
            }
            case 0xE: {
                if (op >= 0x4E40 && op <= 0x4E4F) return OpsFlow020::trap;
                if (op >= 0x4E50 && op <= 0x4E57) { len += 2; return OpsFlow020::link; }
                if (op >= 0x4E58 && op <= 0x4E5F) return OpsFlow020::unlk;
                if (op >= 0x4E60 && op <= 0x4E6F) return OpsFlow020::moveUsp;
                switch (op) {
                    case 0x4E70: return OpsFlow020::reset;
                    case 0x4E71: return OpsFlow020::nop;
                    case 0x4E72: len = 0; return OpsFlow020::stop;
                    case 0x4E73: BRANCH[op] = 1; return OpsFlow020::rte;
                    case 0x4E74: len += 2; BRANCH[op] = 1; return OpsFlow020::rtd;
                    case 0x4E75: BRANCH[op] = 1; return OpsFlow020::rts;
                    case 0x4E76: return OpsFlow020::trapv;
                    case 0x4E77: BRANCH[op] = 1; return OpsFlow020::rtr;
                    case 0x4E7A: case 0x4E7B: len += 2; return OpsFlow020::movec;
                    default: break;
                }
                final int t = (op >> 6) & 3;
                if (t == 2 || t == 3) {
                    final int code = ea(op, CTRL, 4);
                    if (code < 0) return null;
                    eaWords(code, 4);
                    BRANCH[op] = 1;
                    return t == 2 ? (Op020) OpsFlow020::jsr : OpsFlow020::jmp;
                }
                return null;
            }
            default:
                return null;
        }
    }

    private static Op020 line5(int op) {
        final int size = sizeOf(op);
        if (size == 0) {
            final int mode = (op >> 3) & 7;
            if (mode == 1) { len += 2; BRANCH[op] = -1; return OpsFlow020::dbcc; }
            if (mode == 7 && (op & 7) >= 2 && (op & 7) <= 4) {
                final int w = op & 7;
                len += w == 2 ? 2 : w == 3 ? 4 : 0;
                return OpsFlow020::trapcc;
            }
            final int code = ea(op, DALT, 1);
            if (code < 0) return null;
            eaWords(code, 1);
            return OpsBit020::scc;
        }
        final int code = ea(op, ALT, size);
        if (code < 0) return null;
        eaWords(code, size);
        return OpsAlu020::quick;
    }

    private static Op020 line6(int op) {
        final int d = op & 0xFF;
        if (d == 0) len += 2;
        else if (d == 0xFF) len += 4;
        if (((op >> 8) & 0xF) == 1) {
            BRANCH[op] = 1;
            return OpsFlow020::bsr;
        }
        BRANCH[op] = -1;
        return OpsFlow020::bcc;
    }

    private static Op020 line8(int op) {
        final int size = sizeOf(op);
        final boolean dir = (op & 0x100) != 0;
        if (size == 0) {
            final int code = ea(op, DATA, 2);
            if (code < 0) return null;
            eaWords(code, 2);
            return OpsBit020::divw;
        }
        if (dir) {
            final int mode = (op >> 3) & 7;
            final int t = (op >> 6) & 3;
            if (mode <= 1) {
                if (t == 0) return OpsAlu020::sbcdOp;
                if (t == 1) { len += 2; return OpsAlu020::pack; }
                if (t == 2) { len += 2; return OpsAlu020::unpk; }
            }
            final int code = ea(op, MALT, size);
            if (code < 0) return null;
            eaWords(code, size);
            return OpsAlu020::orDnEa;
        }
        final int code = ea(op, DATA, size);
        if (code < 0) return null;
        eaWords(code, size);
        return OpsAlu020::orEaDn;
    }

    private static Op020 lineAddSub(int op, boolean add) {
        final int size = sizeOf(op);
        if (size == 0) {
            final int code = ea(op, ALL, (op & 0x100) != 0 ? 4 : 2);
            if (code < 0) return null;
            eaWords(code, (op & 0x100) != 0 ? 4 : 2);
            return add ? (Op020) OpsAlu020::adda : OpsAlu020::suba;
        }
        if ((op & 0x100) != 0) {
            final int mode = (op >> 3) & 7;
            if (mode <= 1) return add ? (Op020) OpsAlu020::addx : OpsAlu020::subx;
            final int code = ea(op, MALT, size);
            if (code < 0) return null;
            eaWords(code, size);
            return add ? (Op020) OpsAlu020::addDnEa : OpsAlu020::subDnEa;
        }
        final int code = ea(op, ALL, size);
        if (code < 0) return null;
        eaWords(code, size);
        return add ? (Op020) OpsAlu020::addEaDn : OpsAlu020::subEaDn;
    }

    private static Op020 lineB(int op) {
        final int size = sizeOf(op);
        if (size == 0) {
            final int code = ea(op, ALL, (op & 0x100) != 0 ? 4 : 2);
            if (code < 0) return null;
            eaWords(code, (op & 0x100) != 0 ? 4 : 2);
            return OpsAlu020::cmpa;
        }
        if ((op & 0x100) != 0) {
            if (((op >> 3) & 7) == 1) return OpsAlu020::cmpm;
            final int code = ea(op, DALT, size);
            if (code < 0) return null;
            eaWords(code, size);
            return OpsAlu020::eorDnEa;
        }
        final int code = ea(op, ALL, size);
        if (code < 0) return null;
        eaWords(code, size);
        return OpsAlu020::cmpEaDn;
    }

    private static Op020 lineC(int op) {
        final int size = sizeOf(op);
        if (size == 0) {
            final int code = ea(op, DATA, 2);
            if (code < 0) return null;
            eaWords(code, 2);
            return OpsBit020::mulw;
        }
        if ((op & 0x100) != 0) {
            final int mode = (op >> 3) & 7;
            final int opmode = (op >> 3) & 0x1F;
            if (size == 1 && mode <= 1) return OpsAlu020::abcdOp;
            if (opmode == 0x08 || opmode == 0x09 || opmode == 0x11) return OpsAlu020::exg;
            if (mode <= 1) return null;
            final int code = ea(op, MALT, size);
            if (code < 0) return null;
            eaWords(code, size);
            return OpsAlu020::andDnEa;
        }
        final int code = ea(op, DATA, size);
        if (code < 0) return null;
        eaWords(code, size);
        return OpsAlu020::andEaDn;
    }

    /** Floating-point coprocessor encodings: no coprocessor, length unknown to the predecoder. */
    private static Op020 lineF(int op) {
        final int code = code((op >> 3) & 7, op & 7);
        // cpSAVE and cpRESTORE with a valid address are privileged whatever the coprocessor:
        // in user mode a privilege violation comes before the line F trap. Only the FPU's
        // (coprocessor 1) are FPU encodings for the predecoder.
        final boolean cpSave = (op & 0xF1C0) == 0xF100 && code >= 0
                && ((CTRL_ALT | (1 << 4)) & (1 << code)) != 0;
        final boolean cpRestore = (op & 0xF1C0) == 0xF140 && code >= 0
                && ((CTRL | (1 << 3)) & (1 << code)) != 0;
        if ((cpSave || cpRestore) && (op & 0x0E00) != 0x0200) return OpsFlow020::fpuAbsent;
        Op020 h = null;
        switch (op & 0xFFC0) {
            case 0xF200:
                if (code >= 0) h = OpsFlow020::fpuAbsent;
                break;
            case 0xF240: {
                final int low = op & 0x3F;
                if (low >= 0x3A && low <= 0x3C) h = OpsFlow020::fpuAbsent;
                else if (((op >> 3) & 7) == 1) h = OpsFlow020::fpuAbsent;
                else if (code >= 0 && (DALT & (1 << code)) != 0) h = OpsFlow020::fpuAbsent;
                break;
            }
            case 0xF280: case 0xF2C0:
                h = OpsFlow020::fpuAbsent;
                break;
            case 0xF300: case 0xF340:
                if (cpSave || cpRestore) h = OpsFlow020::fpuAbsent;
                break;
            default:
                break;
        }
        if (h != null) len = -1;
        return h;
    }

    private static Op020 lineE(int op) {
        if (((op >> 6) & 3) == 3) {
            if ((op & 0x800) != 0) {
                final int kind = (op >> 8) & 7;
                final boolean writes = kind == 2 || kind == 4 || kind == 6 || kind == 7;
                final int code = ea(op, DN | (writes ? CTRL_ALT : CTRL), 4);
                if (code < 0) return null;
                len += 2;
                eaWords(code, 4);
                return OpsBit020::bitfield;
            }
            final int code = ea(op, MALT, 2);
            if (code < 0) return null;
            eaWords(code, 2);
            return OpsBit020::shiftMemory;
        }
        return OpsBit020::shiftRegister;
    }
}
