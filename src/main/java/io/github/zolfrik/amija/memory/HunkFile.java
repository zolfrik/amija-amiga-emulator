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

package io.github.zolfrik.amija.memory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * An AmigaDOS load file (hunk format) parsed for loading at addresses chosen later.
 *
 * <p>Used for the filesystems stored in a hard disk's Rigid Disk Block: the boot ROM
 * allocates one block per hunk and the host fills them with {@link #image(int, int[])}.
 * Supported blocks: HUNK_NAME, CODE, DATA, BSS, RELOC32, RELOC32SHORT (also as DREL32, its
 * code in load files), SYMBOL, DEBUG and END. Overlays and external references are
 * rejected.
 */
public final class HunkFile {

    private static final int HUNK_NAME = 0x3E8, HUNK_CODE = 0x3E9, HUNK_DATA = 0x3EA,
            HUNK_BSS = 0x3EB, HUNK_RELOC32 = 0x3EC, HUNK_SYMBOL = 0x3F0, HUNK_DEBUG = 0x3F1,
            HUNK_END = 0x3F2, HUNK_HEADER = 0x3F3, HUNK_DREL32 = 0x3F7,
            HUNK_RELOC32SHORT = 0x3FC;

    /** One hunk: its size longword from the header, its contents and its relocations. */
    private static final class Hunk {
        int memRaw;                  // size in longwords, memory flags in bits 30-31
        int memSize;                 // size in bytes
        byte[] contents = new byte[0];
        final List<int[]> relocs = new ArrayList<>(); // {offset, target hunk}
    }

    private final Hunk[] hunks;

    private HunkFile(Hunk[] hunks) { this.hunks = hunks; }

    /**
     * Parses a load file.
     *
     * @throws IOException if the data is not a well-formed load file of a supported kind
     */
    public static HunkFile parse(byte[] file) throws IOException {
        Reader in = new Reader(file);
        if (in.u32() != HUNK_HEADER) throw new IOException("not a load file");
        for (int n = in.u32(); n != 0; n = in.u32()) in.skip(4L * n); // resident libraries
        int count = in.u32();
        int first = in.u32();
        int last = in.u32();
        if (count <= 0 || count > 256 || first != 0 || last != count - 1) {
            throw new IOException("bad hunk table");
        }
        Hunk[] hunks = new Hunk[count];
        for (int i = 0; i < count; i++) {
            Hunk h = new Hunk();
            int raw = in.u32();
            if ((raw & 0xC0000000) == 0xC0000000) {
                in.u32();                 // extended memory attributes: any memory
                raw &= 0x3FFFFFFF;
            }
            h.memRaw = raw;
            h.memSize = (raw & 0x3FFFFFFF) << 2;
            hunks[i] = h;
        }
        int cur = 0;
        while (cur < count && in.remaining() >= 4) {
            int type = in.u32() & 0x3FFFFFFF;
            Hunk h = hunks[cur];
            switch (type) {
                case HUNK_NAME, HUNK_DEBUG -> in.skip(4L * in.u32());
                case HUNK_CODE, HUNK_DATA -> {
                    int len = in.u32() << 2;
                    if (len < 0 || len > h.memSize) throw new IOException("hunk larger than declared");
                    h.contents = in.bytes(len);
                }
                case HUNK_BSS -> in.u32();
                case HUNK_RELOC32 -> {
                    for (int n = in.u32(); n != 0; n = in.u32()) {
                        int target = in.u32();
                        for (int k = 0; k < n; k++) h.relocs.add(new int[] { in.u32(), target });
                    }
                }
                case HUNK_RELOC32SHORT, HUNK_DREL32 -> {
                    int words = 0;
                    for (int n = in.u16(); n != 0; n = in.u16()) {
                        int target = in.u16();
                        words += 2 + n;
                        for (int k = 0; k < n; k++) h.relocs.add(new int[] { in.u16(), target });
                    }
                    words++;                                  // the terminating zero
                    if ((words & 1) != 0) in.u16();           // padded to a longword
                }
                case HUNK_SYMBOL -> {
                    for (int n = in.u32(); n != 0; n = in.u32()) in.skip(4L * n + 4);
                }
                case HUNK_END -> cur++;
                default -> throw new IOException("unsupported hunk type $" + Integer.toHexString(type));
            }
        }
        if (cur < count) throw new IOException("load file truncated");
        for (Hunk h : hunks) {
            for (int[] r : h.relocs) {
                if (r[1] < 0 || r[1] >= count || r[0] < 0 || r[0] + 4 > h.memSize) {
                    throw new IOException("bad relocation");
                }
            }
        }
        return new HunkFile(hunks);
    }

    public int numHunks() { return hunks.length; }

    /** The header's size longword of a hunk: longwords in bits 0-29, CHIP/FAST in 30-31. */
    public int memRaw(int hunk) { return hunks[hunk].memRaw; }

    /** Size in bytes of a hunk's memory. */
    public int memSize(int hunk) { return hunks[hunk].memSize; }

    /**
     * The contents of a hunk, {@link #memSize} bytes, relocated for hunks whose first data
     * byte sits at {@code dataAddr[i]}.
     */
    public byte[] image(int hunk, int[] dataAddr) {
        Hunk h = hunks[hunk];
        byte[] img = Arrays.copyOf(h.contents, h.memSize);
        for (int[] r : h.relocs) {
            int off = r[0];
            int v = ((img[off] & 0xFF) << 24) | ((img[off + 1] & 0xFF) << 16)
                  | ((img[off + 2] & 0xFF) << 8) | (img[off + 3] & 0xFF);
            v += dataAddr[r[1]];
            img[off]     = (byte) (v >>> 24);
            img[off + 1] = (byte) (v >>> 16);
            img[off + 2] = (byte) (v >>> 8);
            img[off + 3] = (byte) v;
        }
        return img;
    }

    /** Big-endian cursor that reports a truncated file as an {@link IOException}. */
    private static final class Reader {
        private final byte[] b;
        private int pos;

        Reader(byte[] b) { this.b = b; }

        int remaining() { return b.length - pos; }

        int u32() throws IOException {
            need(4);
            int v = ((b[pos] & 0xFF) << 24) | ((b[pos + 1] & 0xFF) << 16)
                  | ((b[pos + 2] & 0xFF) << 8) | (b[pos + 3] & 0xFF);
            pos += 4;
            return v;
        }

        int u16() throws IOException {
            need(2);
            int v = ((b[pos] & 0xFF) << 8) | (b[pos + 1] & 0xFF);
            pos += 2;
            return v;
        }

        byte[] bytes(int n) throws IOException {
            need(n);
            byte[] out = Arrays.copyOfRange(b, pos, pos + n);
            pos += n;
            return out;
        }

        void skip(long n) throws IOException {
            if (n < 0 || n > remaining()) throw new IOException("load file truncated");
            pos += (int) n;
        }

        private void need(int n) throws IOException {
            if (n < 0 || n > remaining()) throw new IOException("load file truncated");
        }
    }
}
