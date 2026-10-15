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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Amiga hard disk image ({@code .hdf} file) mounted as a "hardfile".
 *
 * <p>Purpose: launching games installed on .hdf (and WHDLoad) at minimal implementation cost,
 * favouring performance. The file is loaded entirely into a {@code byte[]}; the driver's
 * reads/writes are plain memory copies (no cycle-exact emulation of the SCSI/IDE bus). The
 * AmigaOS driver is provided by {@link io.github.zolfrik.amija.custom.HdController}.
 *
 * <p>Two formats are recognised:
 * <ul>
 *   <li><b>RDB</b> (Rigid Disk Block): the image contains an {@code RDSK} block among the
 *       first 16 sectors, with the geometry and the partition table;</li>
 *   <li><b>headerless</b> (single partition): the whole file is a single AmigaDOS file
 *       system (block 0 starts with {@code DOS}). The geometry is synthesised from the file
 *       size and the {@code DosType} is read from the boot block. That is the case of
 *       "Castle Master v2.0.hdf" (DOS\1 = FFS).</li>
 * </ul>
 *
 * <p>Custom file systems referenced in the RDB (LSEG) are not supported: the partitions are
 * assumed to use the file system in ROM (OFS/FFS), present from Kickstart 2.0 on.
 */
public final class HardFile {

    private static final int BSIZE = 512;

    // Geometry limits.
    private static final int C_MIN = 16,  C_MAX = 16384;
    private static final int H_MIN = 1,   H_MAX = 16;
    private static final int S_MIN = 16,  S_MAX = 63;

    /** Descriptor of a partition (the subset of the DOS environment needed for the boot). */
    public static final class Partition {
        public String name = "DH";
        public int flags;        // bit0 = bootable
        public int sizeBlock;    // longwords per block (512/4 = 128)
        public int reserved;     // reserved boot blocks (normally 2)
        public int lowCyl;
        public int highCyl;
        public int dosType;      // es. 0x444F5301 = "DOS\1" (FFS)
        public int bootPri;
    }

    private final Path path;
    private final byte[] data;
    private boolean readOnly;
    private boolean modified;

    // Geometria.
    private int cylinders, heads, sectors, bsize = BSIZE;

    private final List<Partition> partitions = new ArrayList<>();

    // =========================================================================
    // Construction
    // =========================================================================

    private HardFile(Path path, byte[] data, boolean readOnly) {
        this.path = path;
        this.data = data;
        this.readOnly = readOnly;
    }

    /**
     * Loads and parses an {@code .hdf} file.
     *
     * @param filePath path of the file
     * @param readOnly if {@code true} writes are ignored
     * @throws IOException if the file cannot be read or is too small
     */
    public static HardFile load(String filePath, boolean readOnly) throws IOException {
        Path p = Paths.get(filePath);
        byte[] bytes = Files.readAllBytes(p);
        if (bytes.length < 2 * BSIZE) {
            throw new IOException("File .hdf troppo piccolo: " + bytes.length + " byte");
        }
        HardFile hf = new HardFile(p, bytes, readOnly);
        hf.parse();
        return hf;
    }

    // =========================================================================
    // Parsing geometria / partizioni
    // =========================================================================

    private void parse() {
        int rdb = seekRDB();
        if (rdb >= 0) {
            parseRDB(rdb);
        } else {
            parseHeaderless();
        }
        if (partitions.isEmpty()) {
            // Should not happen: there is always at least one partition.
            parseHeaderless();
        }
    }

    /** Looks for the {@code RDSK} block among the first 16 sectors; -1 if absent. */
    private int seekRDB() {
        int blocks = Math.min(16, data.length / BSIZE);
        for (int i = 0; i < blocks; i++) {
            if (id4(i * BSIZE).equals("RDSK")) return i * BSIZE;
        }
        return -1;
    }

    private void parseRDB(int rdb) {
        cylinders = r32(rdb + 64);
        sectors   = r32(rdb + 68);
        heads     = r32(rdb + 72);
        int bs    = r32(rdb + 16);
        if (bs == 512) bsize = bs;

        // List of partitions: PartitionList in the RDB -> chain of PART blocks.
        int partRef = r32(rdb + 28); // rdb_PartitionList
        int guard = 0;
        while (partRef != -1 && partRef >= 0 && (long) (partRef + 1) * BSIZE <= data.length && guard++ < 64) {
            int pb = partRef * BSIZE;
            if (!id4(pb).equals("PART")) break;
            Partition part = new Partition();
            part.name      = readBStr(pb + 36, 31);
            part.flags     = r32(pb + 20);
            // DOS environment: starts at offset 128 in the PART block.
            part.sizeBlock = r32(pb + 132);
            part.reserved  = r32(pb + 152);
            part.lowCyl    = r32(pb + 164);
            part.highCyl   = r32(pb + 168);
            part.bootPri   = r32(pb + 188);
            part.dosType   = r32(pb + 192);
            if (part.reserved <= 0) part.reserved = 2;
            partitions.add(part);
            partRef = r32(pb + 16); // pb_Next
        }
    }

    private void parseHeaderless() {
        int numBlocks = predictNumBlocks();
        int[] geo = predictGeometry(numBlocks);
        cylinders = geo[0];
        heads     = geo[1];
        sectors   = geo[2];

        Partition part = new Partition();
        part.name      = "DH0";
        part.sizeBlock = bsize / 4;          // 128
        part.reserved  = 2;
        part.lowCyl    = 0;
        part.highCyl   = cylinders - 1;
        part.bootPri   = 0;
        part.flags     = 1;                   // bootable
        // The DosType is the boot block identifier (e.g. "DOS\1"); if the image is not
        // formatted it falls back on OFS ("DOS\0").
        int boot = r32(0);
        part.dosType = id4(0).equals("DOS") ? boot : 0x444F5300;
        partitions.add(part);
    }

    /**
     * Predicts the number of blocks of the partition of a headerless HDF.
     *
     * <p>The image often has a few blocks of padding past the real end of the partition: the
     * file size does <b>not</b> match the file system's blocks. The truth is the position of
     * the FFS/OFS <i>root block</i>, which is at {@code (reserved + numBlocks - 1) / 2}. A few
     * candidates are tried and the first whose predicted root block is a valid root block is
     * accepted.
     */
    private int predictNumBlocks() {
        int fileBlocks = data.length / BSIZE;
        // Candidate 1: the whole file. Candidate 2: rounded to a multiple of 32 (standard
        // geometry of 32 sectors/track, used by most headerless installers).
        int rounded32 = 32 * (fileBlocks / 32);
        for (int nb : new int[] { fileBlocks, rounded32 }) {
            if (nb > 0 && isRootBlock((2 + nb - 1) / 2)) return nb;
        }
        return fileBlocks;
    }

    /** {@code true} if block {@code n} is an FFS/OFS root block (type=2, sec=ST_ROOT=1). */
    private boolean isRootBlock(int n) {
        long off = (long) n * BSIZE;
        if (n < 0 || off + BSIZE > data.length) return false;
        return r32((int) off) == 2 && r32((int) off + BSIZE - 4) == 1;
    }

    /**
     * Synthesises a CHS geometry with exactly {@code C*H*S == numBlocks} (needed so the
     * partition covers exactly the blocks and the root block falls where the file system
     * looks for it). Prefers 32 sectors/track (standard); otherwise looks for any valid
     * factorisation; as a last resort falls back on an approximate geometry.
     */
    private int[] predictGeometry(int numBlocks) {
        // Preference: 32 sectors/track, minimal heads so the cylinders fit.
        for (int h = H_MIN; h <= H_MAX; h++) {
            if (numBlocks % (h * 32) == 0) {
                int c = numBlocks / (h * 32);
                if (c >= 1 && c <= C_MAX) return new int[] { c, h, 32 };
            }
        }
        // General case: any sector count in [16,63], fewest cylinders.
        int bestC = 0, bestH = 0, bestS = 0;
        for (int h = H_MIN; h <= H_MAX; h++) {
            for (int s = S_MIN; s <= S_MAX; s++) {
                int perCyl = h * s;
                if (numBlocks % perCyl != 0) continue;
                int c = numBlocks / perCyl;
                if (c > C_MAX) continue;
                if (c < C_MIN && h > 1) continue;
                if (bestC == 0 || c < bestC) { bestC = c; bestH = h; bestS = s; }
            }
        }
        if (bestC != 0) return new int[] { bestC, bestH, bestS };

        // Fallback: standard geometry with 32 sectors (may exceed numBlocks).
        int s = 32, h = 1;
        int c = (numBlocks + s - 1) / s;
        while (c > 1024) { c = (c + 1) / 2; h *= 2; }
        return new int[] { c, h, s };
    }

    // =========================================================================
    // Data access (used by the host driver)
    // =========================================================================

    /** {@code true} if the offset/length fall entirely inside the image. */
    public boolean inRange(long offset, long length) {
        return offset >= 0 && length >= 0 && offset + length <= data.length;
    }

    /** Byte at the absolute offset in the image (for the copy to Amiga RAM). */
    public int readByte(int offset) {
        return data[offset] & 0xFF;
    }

    /** Writes a byte at the absolute offset (no-op if the image is read-only). */
    public void writeByte(int offset, int value) {
        if (readOnly) return;
        data[offset] = (byte) value;
        modified = true;
    }

    /**
     * Raw byte array of the image, for the host driver's block transfers
     * ({@code System.arraycopy} instead of a byte-by-byte copy). Used as the source of
     * disk -> RAM reads and as the destination of RAM -> disk writes; the caller
     * ({@link io.github.zolfrik.amija.custom.HdController}) has already checked the range and
     * the read-only flag.
     */
    public byte[] rawData() { return data; }

    /** Marks the image as modified after a block write to {@link #rawData()}. */
    public void markModified() { if (!readOnly) modified = true; }

    /** Saves any changes to disk (no-op if read-only or unchanged). */
    public void flush() {
        if (readOnly || !modified) return;
        try {
            Files.write(path, data);
            modified = false;
        } catch (IOException e) {
            System.err.println("[HDF] Salvataggio fallito di " + path + ": " + e.getMessage());
        }
    }

    // =========================================================================
    // Accessori
    // =========================================================================

    public int cylinders()       { return cylinders; }
    public int heads()           { return heads; }
    public int sectors()         { return sectors; }
    public int blockSize()       { return bsize; }
    public int numPartitions()   { return partitions.size(); }
    public Partition partition(int i) { return partitions.get(i); }
    public List<Partition> partitions() { return partitions; }
    public boolean readOnly()    { return readOnly; }
    public long sizeBytes()      { return data.length; }
    public Path path()           { return path; }

    /**
     * Forces the bootability of every partition (bit 0 of {@code flags}). Used to mark a
     * service disk (e.g. the WHDLoad saves volume) as NOT bootable, so it never appears in
     * the boot list.
     */
    public void setBootable(boolean bootable) {
        for (Partition p : partitions) {
            if (bootable) p.flags |= 1; else p.flags &= ~1;
        }
    }

    /** {@code true} if at least one partition has the boot bit. */
    public boolean isBootable() {
        for (Partition p : partitions) {
            if ((p.flags & 1) != 0) return true;
        }
        return false;
    }

    // =========================================================================
    // Helper
    // =========================================================================

    private int r32(int off) {
        return ((data[off] & 0xFF) << 24) | ((data[off + 1] & 0xFF) << 16)
             | ((data[off + 2] & 0xFF) << 8) | (data[off + 3] & 0xFF);
    }

    private String id4(int off) {
        // 4-byte identifier, truncated at the first NUL (e.g. "DOS\1" -> "DOS").
        StringBuilder sb = new StringBuilder(4);
        for (int i = 0; i < 4; i++) {
            int c = data[off + i] & 0xFF;
            if (c < 0x20 || c > 0x7E) break;
            sb.append((char) c);
        }
        return sb.toString();
    }

    private String readBStr(int off, int max) {
        // BCPL string: length in the first byte, then the characters.
        int len = Math.min(data[off] & 0xFF, max);
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append((char) (data[off + 1 + i] & 0xFF));
        return sb.toString();
    }
}
