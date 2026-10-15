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

/**
 * Bit, shift and condition instructions of the 68000/68010: BTST, BCHG, BCLR, BSET, the
 * shifts and rotates, Scc, TAS.
 */
final class Ops000Bit {
    private Ops000Bit() {}

    static final int BTST = 0, BCHG = 1, BCLR = 2, BSET = 3;

    private static int bitOp(Motorola68000Core c, int kind, int dst, int bit) {
        c.fz = (dst & (1 << bit)) == 0;
        switch (kind) {
            case BCHG: return dst ^ (1 << bit);
            case BCLR: return dst & ~(1 << bit);
            case BSET: return dst | (1 << bit);
            default: return dst;
        }
    }

    /** BTST/BCHG/BCLR/BSET with the bit number in a data register. */
    static void bitDynamic(Motorola68000Core c, int op) {
        final int kind = (op >> 6) & 3;
        final int mode = (op >> 3) & 7, reg = op & 7;
        final int bitSrc = c.r[(op >> 9) & 7];
        if (mode == 0) {
            final int bit = bitSrc & 31;
            c.r[reg] = bitOp(c, kind, c.r[reg], bit);
            c.finishNextPre(2);
            if (kind != BTST && bit > 15) c.internal(2);
            if (kind == BCLR) c.internal(2);
            return;
        }
        if (mode == 7 && reg == 4) {
            final int dst = c.ext() & 0xFF;
            bitOp(c, kind, dst, bitSrc & 7);
            c.finishNextPre(2);
            return;
        }
        final int a = c.eaAddr(mode, reg, 1);
        c.iplNow();
        final int dst = c.readByte(a);
        c.eaUpdate(mode, reg, 1, a);
        final int res = bitOp(c, kind, dst, bitSrc & 7);
        c.prefetchLast();
        if (kind == BCLR && c.m68010) c.internal(2);
        if (kind != BTST) c.writeByte(a, res);
        c.advance();
    }

    /** BTST/BCHG/BCLR/BSET with an immediate bit number. */
    static void bitStatic(Motorola68000Core c, int op) {
        final int kind = (op >> 6) & 3;
        final int mode = (op >> 3) & 7, reg = op & 7;
        if (mode == 0) {
            if (kind == BTST) {
                c.iplNow();
                final int bit = c.ext() & 31;
                bitOp(c, kind, c.r[reg], bit);
                c.finish(2);
                return;
            }
            final int bit = c.ext() & 31;
            c.r[reg] = bitOp(c, kind, c.r[reg], bit);
            c.finishNextPre(2);
            if (bit > 15) c.internal(2);
            if (kind == BCLR) c.internal(2);
            return;
        }
        final int bit = c.ext() & 7;
        final int a = c.eaAddr(mode, reg, 1);
        c.iplNow();
        final int dst = c.readByte(a);
        c.eaUpdate(mode, reg, 1, a);
        final int res = bitOp(c, kind, dst, bit);
        c.prefetchLast();
        if (kind == BCLR && c.m68010) c.internal(2);
        if (kind != BTST) c.writeByte(a, res);
        c.advance();
    }

    // ---------------------------------------------------------------------------------------
    // Shifts and rotates
    // ---------------------------------------------------------------------------------------

    static final int AS = 0, LS = 1, ROX = 2, RO = 3;

    /**
     * Shifts {@code v} ({@code size} bytes) by {@code count} (0..63) and sets the flags.
     * Returns the result.
     */
    static int shift(Motorola68000Core c, int type, boolean left, int v, int count, int size) {
        final int bits = size * 8;
        final int m = Motorola68000Core.mask(size);
        final int sign = Motorola68000Core.msb(size);
        long val = v & (m & 0xFFFFFFFFL);
        c.fv = false;
        switch (type) {
            case AS:
                if (left) {
                    if (count == 0) {
                        c.fc = false;
                    } else if (count >= bits) {
                        c.fv = val != 0;
                        c.fc = count == bits && (val & 1) != 0;
                        c.fx = c.fc;
                        val = 0;
                    } else {
                        final long hmask = ((0xFFFFFFFFL << (bits - 1 - count)) & (m & 0xFFFFFFFFL));
                        c.fv = (val & hmask) != hmask && (val & hmask) != 0;
                        val <<= count - 1;
                        c.fc = (val & sign & 0xFFFFFFFFL) != 0;
                        c.fx = c.fc;
                        val = (val << 1) & (m & 0xFFFFFFFFL);
                    }
                } else {
                    final boolean neg = (val & (sign & 0xFFFFFFFFL)) != 0;
                    if (count == 0) {
                        c.fc = false;
                    } else if (count >= bits) {
                        val = neg ? (m & 0xFFFFFFFFL) : 0;
                        c.fc = neg;
                        c.fx = neg;
                    } else {
                        final long sv = Motorola68000Core.sext(v, size);
                        final long shifted = sv >> (count - 1);
                        c.fc = (shifted & 1) != 0;
                        c.fx = c.fc;
                        val = (shifted >> 1) & (m & 0xFFFFFFFFL);
                    }
                }
                break;
            case LS:
                if (count == 0) {
                    c.fc = false;
                } else if (count > bits) {
                    c.fc = false;
                    c.fx = false;
                    val = 0;
                } else if (left) {
                    val <<= count - 1;
                    c.fc = (val & (sign & 0xFFFFFFFFL)) != 0;
                    c.fx = c.fc;
                    val = (val << 1) & (m & 0xFFFFFFFFL);
                } else {
                    val >>>= count - 1;
                    c.fc = (val & 1) != 0;
                    c.fx = c.fc;
                    val >>>= 1;
                }
                break;
            case ROX: {
                int cnt = count % (bits + 1);
                long x = c.fx ? 1 : 0;
                for (int i = 0; i < cnt; i++) {
                    if (left) {
                        final long out = (val >>> (bits - 1)) & 1;
                        val = ((val << 1) | x) & (m & 0xFFFFFFFFL);
                        x = out;
                    } else {
                        final long out = val & 1;
                        val = (val >>> 1) | (x << (bits - 1));
                        x = out;
                    }
                }
                c.fx = x != 0;
                c.fc = c.fx;
                break;
            }
            default: {
                if (count == 0) {
                    c.fc = false;
                } else {
                    final int cnt = count & (bits - 1);
                    if (cnt != 0) {
                        if (left) val = ((val << cnt) | (val >>> (bits - cnt))) & (m & 0xFFFFFFFFL);
                        else val = ((val >>> cnt) | (val << (bits - cnt))) & (m & 0xFFFFFFFFL);
                    }
                    c.fc = left ? (val & 1) != 0 : (val & (sign & 0xFFFFFFFFL)) != 0;
                }
                break;
            }
        }
        final int res = (int) val;
        c.fz = (res & m) == 0;
        c.fn = (res & sign) != 0;
        return res;
    }

    /** Shift or rotate of a data register, by an immediate count or a register. */
    static void shiftReg(Motorola68000Core c, int op) {
        final int size = Motorola68000Core.sizeOf((op >> 6) & 3);
        final boolean left = (op & 0x100) != 0;
        final int type = (op >> 3) & 3;
        final int reg = op & 7;
        int count;
        if ((op & 0x20) != 0) {
            count = c.r[(op >> 9) & 7] & 63;
        } else {
            count = (op >> 9) & 7;
            if (count == 0) count = 8;
        }
        c.iplNextPre();
        c.ir = c.irc;
        c.prefetch(c.po);
        final int res = shift(c, type, left, c.r[reg], count, size);
        c.internal((size == 4 ? 4 : 2) + 2 * count);
        c.setD(reg, res, size);
        c.advance();
    }

    /** Shift or rotate of a memory word by one. */
    static void shiftMem(Motorola68000Core c, int op) {
        final boolean left = (op & 0x100) != 0;
        final int type = (op >> 9) & 3;
        final int mode = (op >> 3) & 7, reg = op & 7;
        final int a = c.eaAddr(mode, reg, 2);
        if ((a & 1) != 0) c.aeRead(mode, reg, a, 2, 0);
        final int v = c.readAt(a, 2, IPL_LAST);
        c.eaUpdate(mode, reg, 2, a);
        final int res = shift(c, type, left, v, 1, 2);
        c.prefetchLast();
        c.writeWord(a, res);
        c.advance();
    }

    // ---------------------------------------------------------------------------------------
    // Scc, TAS
    // ---------------------------------------------------------------------------------------

    static void scc(Motorola68000Core c, int op) {
        final int mode = (op >> 3) & 7, reg = op & 7;
        final int val = c.cond((op >> 8) & 15) ? 0xFF : 0;
        if (mode == 0) {
            c.setD(reg, val, 1);
            c.finishNext();
            if (val != 0) c.internal(2);
            return;
        }
        if (c.m68010) {
            final int a = c.eaAddrNoRead(mode, reg, 1, 2);
            c.iplNow();
            c.eaUpdate(mode, reg, 1, a);
            c.prefetchLast();
            c.writeByte(a, val);
            c.advance();
            return;
        }
        final int a = c.eaAddr(mode, reg, 1);
        c.iplNow();
        c.readByte(a);
        c.eaUpdate(mode, reg, 1, a);
        c.prefetchLast();
        c.writeByte(a, val);
        c.advance();
    }

    static void tas(Motorola68000Core c, int op) {
        final int mode = (op >> 3) & 7, reg = op & 7;
        if (mode == 0) {
            final int v = c.r[reg] & 0xFF;
            c.setNZ(v, 1);
            c.setD(reg, v | 0x80, 1);
            c.finishNext();
            return;
        }
        final int a = c.m68010 ? c.eaAddrNoRead(mode, reg, 1, 2) : c.eaAddr(mode, reg, 1);
        final int v = c.readByte(a);
        c.eaUpdate(mode, reg, 1, a);
        c.setNZ(v, 1);
        c.internal(2);
        if (!c.bus.chipSlotTaken(a)) {
            c.iplNow();
            c.writeByte(a, v | 0x80);
        } else {
            c.internal(4);
        }
        c.prefetchLast();
        c.advance();
    }
}
