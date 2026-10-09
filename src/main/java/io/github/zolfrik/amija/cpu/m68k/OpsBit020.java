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

/** Bit operations, shifts and rotates, bit fields, compare-and-swap, bounds, multiply, divide. */
final class OpsBit020 {
    private OpsBit020() {}

    // =======================================================================================
    // BTST/BCHG/BCLR/BSET
    // =======================================================================================

    static final int BTST = 0, BCHG = 1, BCLR = 2, BSET = 3;

    static int bitop(Motorola68020 c, int kind, int bit, int v) {
        final int m = 1 << bit;
        c.fz = (v & m) == 0;
        switch (kind) {
            case BCHG: return v ^ m;
            case BCLR: return v & ~m;
            case BSET: return v | m;
            default: return v;
        }
    }

    /** Bit number in a data register. */
    static void bitDynamic(Motorola68020 c, int op) {
        final int kind = (op >> 6) & 3;
        final int bitSrc = c.r[(op >> 9) & 7];
        final int mode = (op >> 3) & 7, reg = op & 7;
        if (mode == 0) {
            final int r = bitop(c, kind, bitSrc & 31, c.r[reg]);
            c.prefetchNext();
            if (kind != BTST) c.r[reg] = r;
            c.advance();
            return;
        }
        if (mode == 7 && reg == 4) {
            // BTST Dn,#imm
            final int v = c.nextWord() & 0xFF;
            c.prefetchNext();
            bitop(c, kind, bitSrc & 7, v);
            c.advance();
            return;
        }
        final int a = c.ea(mode, reg, 1);
        c.prefetchNext();
        final int v = c.readByte(a);
        final int r = bitop(c, kind, bitSrc & 7, v);
        if (kind != BTST) c.writeByte(a, r);
        c.advance();
    }

    /** Bit number in an immediate word. */
    static void bitStatic(Motorola68020 c, int op) {
        final int kind = (op >> 6) & 3;
        final int bitSrc = c.nextWord();
        final int mode = (op >> 3) & 7, reg = op & 7;
        if (mode == 0) {
            final int r = bitop(c, kind, bitSrc & 31, c.r[reg]);
            c.prefetchNext();
            if (kind != BTST) c.r[reg] = r;
            c.advance();
            return;
        }
        final int a = c.ea(mode, reg, 1);
        final int v = c.readByte(a);
        c.prefetchNext();
        final int r = bitop(c, kind, bitSrc & 7, v);
        if (kind != BTST) c.writeByte(a, r);
        c.advance();
    }

    // =======================================================================================
    // Scc, TAS
    // =======================================================================================

    static void scc(Motorola68020 c, int op) {
        final int v = c.cond((op >> 8) & 15) ? 0xFF : 0;
        final int mode = (op >> 3) & 7, reg = op & 7;
        if (mode == 0) {
            c.prefetchNext();
            c.setD(reg, v, 1);
        } else {
            final int a = c.ea(mode, reg, 1);
            c.prefetchNext();
            c.writeByte(a, v);
        }
        c.advance();
    }

    static void tas(Motorola68020 c, int op) {
        final int mode = (op >> 3) & 7, reg = op & 7;
        if (mode == 0) {
            final int v = c.r[reg];
            c.setNZ(v, 1);
            c.setD(reg, v | 0x80, 1);
            c.prefetchNext();
        } else {
            final int a = c.ea(mode, reg, 1);
            final int v = c.readByte(a);
            c.setNZ(v, 1);
            c.writeByte(a, v | 0x80);
            c.prefetchNext();
        }
        c.advance();
    }

    // =======================================================================================
    // Shifts and rotates
    // =======================================================================================

    static final int AS = 0, LS = 1, ROX = 2, RO = 3;

    /** One shift or rotate of {@code size} by {@code cnt} (already reduced mod 64). */
    static int shift(Motorola68020 c, int type, boolean left, int val, int cnt, int size) {
        final int bits = size * 8;
        final int mask = Motorola68020.mask(size);
        final int msb = Motorola68020.msb(size);
        val &= mask;
        c.fv = false;
        c.fc = false;
        switch (type) {
            case AS:
                if (left) {
                    if (cnt >= bits) {
                        c.fv = val != 0;
                        c.fc = cnt == bits && (val & 1) != 0;
                        c.fx = c.fc;
                        val = 0;
                    } else if (cnt > 0) {
                        final int vmask = (int) ((0xFFFFFFFFL << (bits - 1 - cnt)) & mask);
                        c.fv = (val & vmask) != vmask && (val & vmask) != 0;
                        val <<= cnt - 1;
                        c.fc = (val & msb) != 0;
                        c.fx = c.fc;
                        val = (val << 1) & mask;
                    }
                } else {
                    final boolean sign = (val & msb) != 0;
                    if (cnt >= bits) {
                        val = sign ? mask : 0;
                        c.fc = sign;
                        c.fx = sign;
                    } else if (cnt > 0) {
                        val >>>= cnt - 1;
                        c.fc = (val & 1) != 0;
                        c.fx = c.fc;
                        val >>>= 1;
                        if (sign) val |= (int) ((0xFFFFFFFFL << (bits - cnt)) & mask);
                        val &= mask;
                    }
                }
                break;
            case LS:
                if (left) {
                    if (cnt >= bits) {
                        c.fc = cnt == bits && (val & 1) != 0;
                        c.fx = c.fc;
                        val = 0;
                    } else if (cnt > 0) {
                        val <<= cnt - 1;
                        c.fc = (val & msb) != 0;
                        c.fx = c.fc;
                        val = (val << 1) & mask;
                    }
                } else {
                    if (cnt >= bits) {
                        c.fc = cnt == bits && (val & msb) != 0;
                        c.fx = c.fc;
                        val = 0;
                    } else if (cnt > 0) {
                        val >>>= cnt - 1;
                        c.fc = (val & 1) != 0;
                        c.fx = c.fc;
                        val >>>= 1;
                    }
                }
                break;
            case ROX: {
                int n = cnt % (bits + 1);
                long v = val & 0xFFFFFFFFL;
                int x = c.fx ? 1 : 0;
                for (int i = 0; i < n; i++) {
                    if (left) {
                        final int out = (int) ((v >>> (bits - 1)) & 1);
                        v = ((v << 1) | x) & (mask & 0xFFFFFFFFL);
                        x = out;
                    } else {
                        final int out = (int) (v & 1);
                        v = (v >>> 1) | ((long) x << (bits - 1));
                        x = out;
                    }
                }
                val = (int) v;
                c.fx = x != 0;
                c.fc = c.fx;
                break;
            }
            default: // RO
                if (cnt > 0) {
                    final int n = cnt & (bits - 1);
                    if (left) {
                        if (n != 0) val = ((val << n) | (val >>> (bits - n))) & mask;
                        c.fc = (val & 1) != 0;
                    } else {
                        if (n != 0) val = ((val >>> n) | (val << (bits - n))) & mask;
                        c.fc = (val & msb) != 0;
                    }
                }
                break;
        }
        c.fn = (val & msb) != 0;
        c.fz = (val & mask) == 0;
        return val;
    }

    /** Shift or rotate of a data register, by an immediate or a register count. */
    static void shiftRegister(Motorola68020 c, int op) {
        final int size = OpsAlu020.size(op);
        final int type = (op >> 3) & 3;
        final boolean left = (op & 0x100) != 0;
        final int cr = (op >> 9) & 7;
        int cnt;
        if ((op & 0x20) != 0) {
            cnt = c.r[cr] & 63;
        } else {
            cnt = cr == 0 ? 8 : cr;
        }
        final int reg = op & 7;
        final int r = shift(c, type, left, c.r[reg], cnt, size);
        c.prefetchNext();
        c.setD(reg, r, size);
        c.advance();
    }

    /** Shift or rotate of a memory word by one. */
    static void shiftMemory(Motorola68020 c, int op) {
        final int type = (op >> 9) & 3;
        final boolean left = (op & 0x100) != 0;
        final int a = c.ea((op >> 3) & 7, op & 7, 2);
        final int v = c.readWord(a);
        c.prefetchNext();
        final int r = shift(c, type, left, v, 1, 2);
        c.writeWord(a, r);
        c.advance();
    }

    // =======================================================================================
    // Bit fields
    // =======================================================================================

    static final int BFTST = 0, BFEXTU = 1, BFCHG = 2, BFEXTS = 3, BFCLR = 4, BFFFO = 5, BFSET = 6, BFINS = 7;

    static void bitfield(Motorola68020 c, int op) {
        final int kind = (op >> 8) & 7;
        final int extra = c.nextWord();
        final int mode = (op >> 3) & 7, reg = op & 7;
        int offset = (extra & 0x800) != 0 ? c.r[(extra >> 6) & 7] : (extra >> 6) & 0x1F;
        final int width = ((((extra & 0x20) != 0 ? c.r[extra & 7] : extra) - 1) & 0x1F) + 1;
        final int dreg = (extra >> 12) & 7;
        if (mode == 0) {
            final int offset2 = offset;
            offset &= 0x1F;
            int tmp = c.r[reg];
            if (offset != 0) tmp = Integer.rotateLeft(tmp, offset);
            final int bdata = tmp & (int) ((1L << (32 - width)) - 1);
            c.fn = tmp < 0;
            int field = tmp >>> (32 - width);
            c.fz = field == 0;
            c.fv = false;
            c.fc = false;
            int put = -1;
            switch (kind) {
                case BFEXTU: c.r[dreg] = field; break;
                case BFEXTS: c.r[dreg] = tmp >> (32 - width); break;
                case BFFFO: c.r[dreg] = ffo(field, width, offset2); break;
                case BFCHG: put = field ^ (int) (0xFFFFFFFFL >>> (32 - width)); break;
                case BFCLR: put = 0; break;
                case BFSET: put = (int) (0xFFFFFFFFL >>> (32 - width)); break;
                case BFINS: {
                    put = c.r[dreg] & (int) (0xFFFFFFFFL >>> (32 - width));
                    c.fn = (put & (1 << (width - 1))) != 0;
                    c.fz = put == 0;
                    break;
                }
                default: break;
            }
            if (put != -1 || kind == BFCHG || kind == BFINS || kind == BFSET) {
                final int t = bdata | (int) ((long) put << (32 - width));
                c.r[reg] = offset != 0 ? Integer.rotateRight(t, offset) : t;
            }
            c.prefetchNext();
            c.advance();
            return;
        }
        int a = c.ea(mode, reg, 1);
        final int offset2 = offset;
        a += offset >> 3;
        final long[] bdata = c.bfData;
        int tmp = getBitfield(c, a, bdata, offset, width);
        c.fn = tmp < 0;
        int field = tmp >>> (32 - width);
        c.fz = field == 0;
        c.fv = false;
        c.fc = false;
        switch (kind) {
            case BFEXTU: c.r[dreg] = field; break;
            case BFEXTS: c.r[dreg] = tmp >> (32 - width); break;
            case BFFFO: c.r[dreg] = ffo(field, width, offset2); break;
            case BFCHG: putBitfield(c, a, bdata, field ^ (int) (0xFFFFFFFFL >>> (32 - width)), offset, width); break;
            case BFCLR: putBitfield(c, a, bdata, 0, offset, width); break;
            case BFSET: putBitfield(c, a, bdata, (int) (0xFFFFFFFFL >>> (32 - width)), offset, width); break;
            case BFINS: {
                final int v = c.r[dreg] & (int) (0xFFFFFFFFL >>> (32 - width));
                c.fn = (v & (1 << (width - 1))) != 0;
                c.fz = v == 0;
                putBitfield(c, a, bdata, v, offset, width);
                break;
            }
            default: break;
        }
        c.prefetchNext();
        c.advance();
    }

    private static int ffo(int field, int width, int offset) {
        int m = 1 << (width - 1);
        int o = offset;
        while (m != 0) {
            if ((field & m) != 0) break;
            m >>>= 1;
            o++;
        }
        return o;
    }

    /** Reads the bytes covering a bit field; returns it left-aligned, keeps the rest in bdata. */
    static int getBitfield(Motorola68020 c, int src, long[] bdata, int offset, int width) {
        offset &= 7;
        final long mask = (0xFFFFFFFFL << (32 - width)) & 0xFFFFFFFFL;
        long res;
        switch ((offset + width + 7) >> 3) {
            case 1: {
                final long t1 = c.readByte(src) & 0xFFL;
                res = t1 << (24 + offset);
                bdata[0] = t1 & ~(mask >>> (24 + offset));
                break;
            }
            case 2: {
                final long t1 = c.readWord(src) & 0xFFFFL;
                res = t1 << (16 + offset);
                bdata[0] = t1 & ~(mask >>> (16 + offset));
                break;
            }
            case 3: {
                final long t1 = c.readWord(src) & 0xFFFFL;
                final long t2 = c.readByte(src + 2) & 0xFFL;
                res = t1 << (16 + offset);
                bdata[0] = t1 & ~(mask >>> (16 + offset));
                res |= t2 << (8 + offset);
                bdata[1] = t2 & ~(mask >>> (8 + offset));
                break;
            }
            case 4: {
                final long t1 = c.readLong(src) & 0xFFFFFFFFL;
                res = t1 << offset;
                bdata[0] = t1 & ~(mask >>> offset);
                break;
            }
            default: {
                final long t1 = c.readLong(src) & 0xFFFFFFFFL;
                final long t2 = c.readByte(src + 4) & 0xFFL;
                res = t1 << offset;
                bdata[0] = t1 & ~(mask >>> offset);
                res |= t2 >>> (8 - offset);
                bdata[1] = t2 & ~((mask << (8 - offset)) & 0xFFL);
                break;
            }
        }
        return (int) res;
    }

    static void putBitfield(Motorola68020 c, int dst, long[] bdata, int val, int offset, int width) {
        offset = (offset & 7) + width;
        final long v = val & 0xFFFFFFFFL;
        switch ((offset + 7) >> 3) {
            case 1: c.writeByte(dst, (int) (bdata[0] | (v << (8 - offset)))); break;
            case 2: c.writeWord(dst, (int) (bdata[0] | (v << (16 - offset)))); break;
            case 3:
                c.writeWord(dst, (int) (bdata[0] | (v >>> (offset - 16))));
                c.writeByte(dst + 2, (int) (bdata[1] | (v << (24 - offset))));
                break;
            case 4: c.writeLong(dst, (int) (bdata[0] | (v << (32 - offset)))); break;
            default:
                c.writeLong(dst, (int) (bdata[0] | (v >>> (offset - 32))));
                c.writeByte(dst + 4, (int) (bdata[1] | (v << (40 - offset))));
                break;
        }
    }

    // =======================================================================================
    // CAS, CAS2
    // =======================================================================================

    static void cas(Motorola68020 c, int op) {
        final int z = (op >> 9) & 3;
        final int size = z == 1 ? 1 : z == 2 ? 2 : 4;
        final int ext = c.nextWord();
        final int a = c.ea((op >> 3) & 7, op & 7, size);
        final int dst = c.read(a, size);
        final int rc = ext & 7, ru = (ext >> 6) & 7;
        final int cmp = c.r[rc];
        c.cmpFlags(cmp, dst, dst - cmp, size);
        if (c.fz) {
            c.write(a, c.r[ru], size);
            c.prefetchNext();
        } else {
            c.prefetchNext();
            c.setD(rc, dst, size);
        }
        c.advance();
    }

    static void cas2(Motorola68020 c, int op) {
        final int size = (op & 0x200) != 0 ? 4 : 2;
        final int extra = c.nextLong();
        final int rn1 = c.r[(extra >>> 28) & 15];
        final int rn2 = c.r[(extra >> 12) & 15];
        final int dc1 = (extra >> 16) & 7, dc2 = extra & 7;
        final int du1 = (extra >> 22) & 7, du2 = (extra >> 6) & 7;
        final int dst1 = c.read(rn1, size);
        final int dst2 = c.read(rn2, size);
        c.cmpFlags(c.r[dc1], dst1, dst1 - c.r[dc1], size);
        if (c.fz) {
            c.cmpFlags(c.r[dc2], dst2, dst2 - c.r[dc2], size);
            if (c.fz) {
                c.write(rn2, c.r[du2], size);
                c.write(rn1, c.r[du1], size);
            }
        }
        if (!c.fz) {
            c.setD(dc2, dst2, size);
            c.setD(dc1, dst1, size);
        }
        c.prefetchNext();
        c.advance();
    }

    // =======================================================================================
    // CHK, CHK2/CMP2
    // =======================================================================================

    static void chk(Motorola68020 c, int op) {
        final int size = (op & 0x80) != 0 ? 2 : 4;
        int src = c.readEa((op >> 3) & 7, op & 7, size);
        int dst = c.r[(op >> 9) & 7];
        if (size == 2) {
            src = (short) src;
            dst = (short) dst;
        }
        c.advance();
        chkFlags(c, src, dst, size);
        if (dst > src || dst < 0) {
            c.exceptionCpu(6);
            return;
        }
        c.po = 0;
        c.prefetchNext();
    }

    static void chkFlags(Motorola68020 c, int src, int dst, int size) {
        c.fc = c.fv = false;
        c.fz = dst == 0;
        c.fn = dst < 0;
        if (dst < 0 || dst > src) {
            if (size == 2) {
                final boolean flgs = (short) dst < 0, flgo = (short) src < 0;
                final boolean flgn = (short) (src - dst) < 0;
                c.fv = (flgs ^ flgo) & (flgn ^ flgo);
            } else {
                final boolean flgs = dst < 0, flgo = src < 0, flgn = src - dst < 0;
                c.fv = (flgs ^ flgo) & (flgn ^ flgo);
            }
            c.fc = dst < 0 ? (dst > src || src >= 0) : src >= 0;
        }
    }

    static void chk2(Motorola68020 c, int op) {
        final int z = (op >> 9) & 3;
        final int size = z == 0 ? 1 : z == 1 ? 2 : 4;
        final int extra = c.nextWord();
        final int a = c.ea((op >> 3) & 7, op & 7, size);
        int reg = c.r[(extra >> 12) & 15];
        int lower, upper;
        if (size == 1) {
            lower = (byte) c.readByte(a);
            upper = (byte) c.readByte(a + 1);
            if ((extra & 0x8000) == 0) reg = (byte) reg;
        } else if (size == 2) {
            lower = (short) c.readWord(a);
            upper = (short) c.readWord(a + 2);
            if ((extra & 0x8000) == 0) reg = (short) reg;
        } else {
            lower = c.readLong(a);
            upper = c.readLong(a + 4);
        }
        c.advance();
        c.fc = false;
        c.fz = false;
        chk2Flags(c, lower, upper, reg);
        if (upper == reg || lower == reg) {
            c.fz = true;
        } else {
            if (lower <= upper && (reg < lower || reg > upper)) c.fc = true;
            if (lower > upper && reg > upper && reg < lower) c.fc = true;
        }
        if ((extra & 0x800) != 0 && c.fc) {
            c.exceptionCpu(6);
            return;
        }
        c.po = 0;
        c.prefetchNext();
    }

    private static void chk2Flags(Motorola68020 c, int lower, int upper, int val) {
        c.fn = false;
        c.fv = false;
        if (val == lower || val == upper) return;
        if (lower < 0 && upper >= 0) {
            if (val < lower) c.fn = true;
            if (val >= 0 && val < upper) c.fn = true;
            if (val >= 0 && lower - val >= 0) {
                c.fv = true;
                c.fn = val > upper;
            }
        } else if (lower >= 0 && upper < 0) {
            if (val >= 0) c.fn = true;
            if (val > upper) c.fn = true;
            if (val > lower && upper - val >= 0) {
                c.fv = true;
                c.fn = false;
            }
        } else if (lower >= 0 && upper >= 0 && lower > upper) {
            if (val > upper && val < lower) c.fn = true;
            if (val < 0 && lower - val < 0) c.fv = true;
            if (val < 0 && lower - val >= 0) c.fn = true;
        } else if (lower >= 0 && upper >= 0) {
            if (val >= 0 && val < lower) c.fn = true;
            if (val > upper) c.fn = true;
            if (val < 0 && upper - val < 0) {
                c.fv = true;
                c.fn = true;
            }
        } else if (lower < 0 && upper < 0 && lower > upper) {
            if (val >= 0) c.fn = true;
            if (val > upper && val < lower) c.fn = true;
            if (val >= 0 && val - lower < 0) {
                c.fn = false;
                c.fv = true;
            }
        } else {
            if (val < lower) c.fn = true;
            if (val < 0 && val > upper) c.fn = true;
            if (val >= 0 && val - lower < 0) {
                c.fn = true;
                c.fv = true;
            }
        }
    }

    // =======================================================================================
    // Multiply and divide
    // =======================================================================================

    static void mulw(Motorola68020 c, int op) {
        final boolean signed = (op & 0x100) != 0;
        final int src = c.readEa((op >> 3) & 7, op & 7, 2);
        final int dn = (op >> 9) & 7;
        final int dst = c.r[dn];
        c.prefetchNext();
        final int r = signed ? (short) dst * (short) src : (dst & 0xFFFF) * (src & 0xFFFF);
        c.setNZ(r, 4);
        c.internalFull(20);
        c.r[dn] = r;
        c.advance();
    }

    static void divw(Motorola68020 c, int op) {
        final boolean signed = (op & 0x100) != 0;
        final int src = c.readEa((op >> 3) & 7, op & 7, 2);
        final int dn = (op >> 9) & 7;
        final int dst = c.r[dn];
        // With a data register as divisor the next opcode is fetched after the division,
        // not before it, and not at all before a division by zero. With a memory divisor
        // the fetch comes first, as for the multiply.
        final boolean registerSource = ((op >> 3) & 7) == 0;
        if (!registerSource) c.prefetchNext();
        if ((src & 0xFFFF) == 0) {
            c.fc = c.fz = c.fn = c.fv = false;
            if (signed) {
                c.fz = true;
            } else {
                final short d = (short) (dst >> 16);
                if (d < 0) c.fn = true;
                else if (d == 0) c.fz = true;
                c.fv = true;
            }
            c.advance();
            c.exceptionCpu(5);
            return;
        }
        if (signed) {
            final int s = (short) src;
            if (dst == 0x80000000 && s == -1) {
                divsOverflow(c, dst, s);
            } else {
                final int q = dst / s;
                final int rem = dst % s;
                if ((q & 0xFFFF8000) != 0 && (q & 0xFFFF8000) != 0xFFFF8000) {
                    divsOverflow(c, dst, s);
                } else {
                    c.setNZ(q, 2);
                    c.r[dn] = (q & 0xFFFF) | (rem << 16);
                }
            }
            if (registerSource) {
                c.internalFull(48);
                c.prefetchNext();
                c.advance();
            } else {
                c.advance();
                c.internalFull(48);
            }
        } else {
            final long q = (dst & 0xFFFFFFFFL) / (src & 0xFFFF);
            final long rem = (dst & 0xFFFFFFFFL) % (src & 0xFFFF);
            if (q > 0xFFFF) {
                c.fv = true;
                if (dst < 0) c.fn = true;
            } else {
                c.setNZ((int) q, 2);
                c.r[dn] = (int) ((q & 0xFFFF) | (rem << 16));
            }
            if (registerSource) {
                c.internalFull(34);
                c.prefetchNext();
                c.advance();
            } else {
                c.advance();
                c.internalFull(34);
            }
        }
    }

    private static void divsOverflow(Motorola68020 c, int dividend, int divisor) {
        c.fc = c.fz = c.fn = false;
        c.fv = true;
        final long ad = Math.abs((long) dividend), as = Math.abs((long) (short) divisor) & 0xFFFF;
        if ((ad >>> 16) >= as) return;
        final long aquot = ad / as;
        if ((byte) aquot == 0) c.fz = true;
        if ((byte) aquot < 0) c.fn = true;
    }

    static void mull(Motorola68020 c, int op) {
        final int extra = c.nextWord();
        final int src = c.readEa((op >> 3) & 7, op & 7, 4);
        c.advance();
        final int dl = (extra >> 12) & 7, dh = extra & 7;
        c.fv = false;
        c.fc = false;
        if ((extra & 0x800) != 0) {
            final long a = (long) c.r[dl] * (long) src;
            if ((extra & 0x400) != 0) {
                c.r[dl] = (int) a;
                c.r[dh] = (int) (a >> 32);
                c.fz = a == 0;
                c.fn = a < 0;
            } else {
                c.r[dl] = (int) a;
                if ((a & 0xFFFFFFFF80000000L) != 0 && (a & 0xFFFFFFFF80000000L) != 0xFFFFFFFF80000000L) c.fv = true;
                c.fz = (int) a == 0;
                c.fn = (int) a < 0;
            }
        } else {
            final long a = (c.r[dl] & 0xFFFFFFFFL) * (src & 0xFFFFFFFFL);
            if ((extra & 0x400) != 0) {
                c.r[dl] = (int) a;
                c.r[dh] = (int) (a >>> 32);
                c.fz = a == 0;
                c.fn = a < 0;
            } else {
                c.r[dl] = (int) a;
                if ((a & 0xFFFFFFFF00000000L) != 0) c.fv = true;
                c.fz = (int) a == 0;
                c.fn = (int) a < 0;
            }
        }
        c.po = 0;
        c.prefetchNext();
    }

    static void divl(Motorola68020 c, int op) {
        final int extra = c.nextWord();
        final int src = c.readEa((op >> 3) & 7, op & 7, 4);
        c.advance();
        final int dq = (extra >> 12) & 7, dr = extra & 7;
        if ((extra & 0x800) != 0) {
            long a = c.r[dq];
            if ((extra & 0x400) != 0) a = (a & 0xFFFFFFFFL) | ((long) c.r[dr] << 32);
            if (src == 0) {
                c.fn = false;
                c.fz = true;
                c.fc = false;
                c.exceptionCpu(5);
                return;
            }
            if (a == 0x8000000000000000L && src == -1) {
                divslOverflow(c, extra, a, src);
            } else {
                long rem = a % src;
                final long quot = a / src;
                if ((quot & 0xFFFFFFFF80000000L) != 0 && (quot & 0xFFFFFFFF80000000L) != 0xFFFFFFFF80000000L) {
                    divslOverflow(c, extra, a, src);
                } else {
                    if (((int) rem < 0) != (a < 0)) rem = -rem;
                    c.fv = false;
                    c.fc = false;
                    c.fz = (int) quot == 0;
                    c.fn = (int) quot < 0;
                    c.r[dr] = (int) rem;
                    c.r[dq] = (int) quot;
                }
            }
        } else {
            long a = c.r[dq] & 0xFFFFFFFFL;
            if ((extra & 0x400) != 0) a |= (long) c.r[dr] << 32;
            if (src == 0) {
                final int a32 = (int) a;
                c.fn = a32 < 0;
                c.fz = a32 == 0;
                c.fv = true;
                c.fc = false;
                c.exceptionCpu(5);
                return;
            }
            final long rem = Long.remainderUnsigned(a, src & 0xFFFFFFFFL);
            final long quot = Long.divideUnsigned(a, src & 0xFFFFFFFFL);
            if (Long.compareUnsigned(quot, 0xFFFFFFFFL) > 0) {
                final int a32 = (int) a;
                c.fv = true;
                c.fn = a32 < 0;
                c.fz = a32 == 0;
                c.fc = false;
            } else {
                c.fv = false;
                c.fc = false;
                c.fz = (int) quot == 0;
                c.fn = (int) quot < 0;
                c.r[dr] = (int) rem;
                c.r[dq] = (int) quot;
            }
        }
        c.po = 0;
        c.prefetchNext();
    }

    private static void divslOverflow(Motorola68020 c, int extra, long a, int divider) {
        final int a32 = (int) a;
        final boolean neg64 = a < 0, neg32 = a32 < 0;
        c.fv = true;
        if ((extra & 0x400) != 0) {
            final int ahigh = (int) (a >> 32);
            if (ahigh == 0) {
                c.fz = true;
                c.fn = false;
            } else if (ahigh < 0 && divider < 0 && ahigh > divider) {
                c.fz = false;
                c.fn = false;
            } else if (a32 == 0) {
                c.fz = true;
                c.fn = false;
            } else {
                c.fz = false;
                c.fn = neg32 ^ neg64;
            }
        } else if (a32 == 0) {
            c.fz = true;
            c.fn = false;
        } else {
            c.fn = neg32;
            c.fz = false;
        }
        c.fc = false;
    }
}
