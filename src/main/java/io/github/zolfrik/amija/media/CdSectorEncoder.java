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

package io.github.zolfrik.amija.media;

/**
 * Rebuilds a CD-ROM Mode 1 sector (ECMA-130): the 12-byte sync pattern, the header (address
 * in BCD minutes, seconds, frames, then the mode), the 2048 user bytes already in place at
 * offset 16, the EDC at 2064, eight zero bytes, the P parity (172 bytes at 2076) and the Q
 * parity (104 bytes at 2248).
 */
public final class CdSectorEncoder {

    private static final int[] EDC = new int[256];
    private static final int[] ECC_F = new int[256];
    private static final int[] ECC_B = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int j = (i << 1) ^ ((i & 0x80) != 0 ? 0x11D : 0);
            ECC_F[i] = j & 0xFF;
            ECC_B[(i ^ j) & 0xFF] = i;
            int edc = i;
            for (int k = 0; k < 8; k++) edc = (edc >>> 1) ^ ((edc & 1) != 0 ? 0xD8018001 : 0);
            EDC[i] = edc;
        }
    }

    private CdSectorEncoder() {
    }

    private static int bcd(int v) {
        return ((v / 10) << 4) | (v % 10);
    }

    /** Fills in everything but the user data of a Mode 1 sector at {@code address} (MSF frames, LSN + 150). */
    public static void encodeMode1(byte[] p, int address) {
        encodeMode1(p, 0, address);
    }

    /** As {@link #encodeMode1(byte[], int)}, for the sector starting at {@code o} in {@code p}. */
    public static void encodeMode1(byte[] p, int o, int address) {
        p[o] = 0;
        // Bytes 1-11 all $FF: the drive delivers the twelfth sync byte as $FF, not $00.
        for (int i = 1; i < 12; i++) p[o + i] = (byte) 0xFF;
        p[o + 12] = (byte) bcd(address / (60 * 75));
        p[o + 13] = (byte) bcd((address / 75) % 60);
        p[o + 14] = (byte) bcd(address % 75);
        p[o + 15] = 1;
        int edc = 0;
        for (int i = 0; i < 16 + 2048; i++) edc = EDC[(edc ^ p[o + i]) & 0xFF] ^ (edc >>> 8);
        p[o + 2064] = (byte) edc;
        p[o + 2065] = (byte) (edc >>> 8);
        p[o + 2066] = (byte) (edc >>> 16);
        p[o + 2067] = (byte) (edc >>> 24);
        for (int i = 2068; i < 2076; i++) p[o + i] = 0;
        eccBlock(p, o, 86, 24, 2, 86, 2076);
        eccBlock(p, o, 52, 43, 86, 88, 2248);
    }

    /** One parity block over the header and data (from offset 12), as ECMA-130 Annex A. */
    private static void eccBlock(byte[] p, int o, int majorCount, int minorCount, int majorMult,
                                 int minorInc, int dest) {
        final int size = majorCount * minorCount;
        for (int major = 0; major < majorCount; major++) {
            int index = (major >> 1) * majorMult + (major & 1);
            int a = 0;
            int b = 0;
            for (int minor = 0; minor < minorCount; minor++) {
                int t = p[o + 12 + index] & 0xFF;
                index += minorInc;
                if (index >= size) index -= size;
                a ^= t;
                b ^= t;
                a = ECC_F[a];
            }
            a = ECC_B[ECC_F[a] ^ b];
            p[o + dest + major] = (byte) a;
            p[o + dest + major + majorCount] = (byte) (a ^ b);
        }
    }
}
