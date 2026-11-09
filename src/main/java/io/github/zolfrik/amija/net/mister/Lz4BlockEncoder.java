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

package io.github.zolfrik.amija.net.mister;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Compressor for the LZ4 <em>block</em> format (no frame header, no checksum).
 *
 * <p>A greedy single-probe matcher with a 64K-entry hash table, the classic fast LZ4
 * strategy. The output honours the block-format end rules: the last five bytes are always
 * literals and no match starts within the last twelve bytes. One instance owns its hash
 * table and is reused across frames, so compressing allocates nothing; it is not
 * thread-safe.
 */
public final class Lz4BlockEncoder {

    private static final int MIN_MATCH = 4;
    private static final int LAST_LITERALS = 5;
    private static final int MF_LIMIT = 12;
    private static final int MAX_DISTANCE = 0xFFFF;
    private static final int HASH_BITS = 16;
    /** Larger means a slower climb of the skip step over incompressible data. */
    private static final int SKIP_TRIGGER = 6;

    private static final VarHandle INT_LE =
            MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    private final int[] table = new int[1 << HASH_BITS];

    /** Worst-case output size for {@code length} input bytes. */
    public static int maxCompressedLength(int length) {
        return length + length / 255 + 16;
    }

    private static int hash(int v) {
        return (v * -1640531535) >>> (32 - HASH_BITS);
    }

    /**
     * Compresses {@code src[srcOff, srcOff + srcLen)} into {@code dst} at {@code dstOff}.
     *
     * @return the number of bytes written
     */
    public int compress(byte[] src, int srcOff, int srcLen, byte[] dst, int dstOff) {
        if (dst.length - dstOff < maxCompressedLength(srcLen)) {
            throw new IllegalArgumentException("destination too small");
        }
        final int srcEnd = srcOff + srcLen;
        int op = dstOff;
        int anchor = srcOff;
        if (srcLen >= MF_LIMIT + 1) {
            Arrays.fill(table, -1);
            final int mfLimit = srcEnd - MF_LIMIT;
            final int matchLimit = srcEnd - LAST_LITERALS;
            int ip = srcOff;
            int searches = 1 << SKIP_TRIGGER;
            while (ip <= mfLimit) {
                int seq = (int) INT_LE.get(src, ip);
                int h = hash(seq);
                int ref = table[h];
                table[h] = ip;
                if (ref < 0 || ip - ref > MAX_DISTANCE || (int) INT_LE.get(src, ref) != seq) {
                    ip += searches++ >>> SKIP_TRIGGER;
                    continue;
                }
                searches = 1 << SKIP_TRIGGER;
                while (ip > anchor && ref > srcOff && src[ip - 1] == src[ref - 1]) {
                    ip--;
                    ref--;
                }
                int len = MIN_MATCH;
                while (ip + len < matchLimit && src[ip + len] == src[ref + len]) {
                    len++;
                }
                op = writeSequence(src, anchor, ip - anchor, dst, op, ip - ref, len);
                ip += len;
                anchor = ip;
                if (ip - 2 <= mfLimit) {
                    table[hash((int) INT_LE.get(src, ip - 2))] = ip - 2;
                }
            }
        }
        return writeLastLiterals(src, anchor, srcEnd - anchor, dst, op) - dstOff;
    }

    private static int writeLength(byte[] dst, int op, int rest) {
        while (rest >= 255) {
            dst[op++] = (byte) 255;
            rest -= 255;
        }
        dst[op++] = (byte) rest;
        return op;
    }

    private static int writeSequence(byte[] src, int lit, int litLen, byte[] dst, int op,
            int offset, int matchLen) {
        int token = op++;
        int ml = matchLen - MIN_MATCH;
        int t = (Math.min(litLen, 15) << 4) | Math.min(ml, 15);
        if (litLen >= 15) {
            op = writeLength(dst, op, litLen - 15);
        }
        System.arraycopy(src, lit, dst, op, litLen);
        op += litLen;
        dst[op++] = (byte) offset;
        dst[op++] = (byte) (offset >>> 8);
        if (ml >= 15) {
            op = writeLength(dst, op, ml - 15);
        }
        dst[token] = (byte) t;
        return op;
    }

    private static int writeLastLiterals(byte[] src, int lit, int litLen, byte[] dst, int op) {
        dst[op++] = (byte) (Math.min(litLen, 15) << 4);
        if (litLen >= 15) {
            op = writeLength(dst, op, litLen - 15);
        }
        System.arraycopy(src, lit, dst, op, litLen);
        return op + litLen;
    }
}
