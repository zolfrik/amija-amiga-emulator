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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Amiga hard disk image ({@code .hdf} file) mounted as a "hardfile".
 *
 * <p>The driver's reads and writes are plain copies (no cycle-exact emulation of the
 * SCSI/IDE bus); the AmigaOS driver is provided by
 * {@link io.github.zolfrik.amija.hardware.custom.HdController}. Images up to
 * {@link #IN_MEMORY_LIMIT} are loaded into a {@code byte[]} and saved back by
 * {@link #flush()}; larger ones are read and written in place through a {@link FileChannel},
 * so their size is limited only by the 64-bit offsets of the driver's commands.
 *
 * <p>A read-only image is never written back, but the disk still behaves as one: what the
 * Amiga writes is kept for the session (in the array, or in a block overlay for an image
 * accessed in place) and read back, so a filesystem always sees its own writes.
 *
 * <p>Two formats are recognised:
 * <ul>
 *   <li><b>RDB</b> (Rigid Disk Block): the image contains an {@code RDSK} block among the
 *       first 16 sectors, with the geometry, the partition table and the filesystems
 *       (FSHD blocks with their LSEG load files) that the partitions need;</li>
 *   <li><b>headerless</b> (single partition): the whole file is a single AmigaDOS file
 *       system (block 0 starts with {@code DOS}). The geometry is synthesised from the file
 *       size and the {@code DosType} is read from the boot block.</li>
 * </ul>
 */
public final class HardFile implements AutoCloseable {

    /** Images larger than this are not loaded into memory but accessed in place. */
    public static final long IN_MEMORY_LIMIT = 256L << 20;

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
        public int surfaces;
        public int blocksPerTrack;
        public int reserved;     // reserved boot blocks (normally 2)
        public int lowCyl;
        public int highCyl;
        public int numBuffers = 30;
        public int bufMemType;
        public int maxTransfer = 0x7FFFFFFF;
        public int mask = 0xFFFFFFFE;
        public int dosType;      // es. 0x444F5301 = "DOS\1" (FFS)
        public int bootPri;
    }

    /** A filesystem stored in the RDB: its DosType, version and load file. */
    public static final class FileSystem {
        public final int dosType;
        public final int version;
        public final HunkFile code;

        FileSystem(int dosType, int version, HunkFile code) {
            this.dosType = dosType;
            this.version = version;
            this.code = code;
        }
    }

    private final Path path;
    private final long size;
    private final byte[] data;          // the whole image, or null when accessed in place
    private final FileChannel channel;  // the open image, or null when in memory
    private boolean readOnly;
    private boolean modified;
    // Session writes to a read-only image accessed in place, by block number.
    private final Map<Long, byte[]> overlay = new HashMap<>();

    // Geometry.
    private int cylinders, heads, sectors, bsize = BSIZE;

    private final List<Partition> partitions = new ArrayList<>();
    private final List<FileSystem> fileSystems = new ArrayList<>();

    // =========================================================================
    // Construction
    // =========================================================================

    private HardFile(Path path, long size, byte[] data, FileChannel channel, boolean readOnly) {
        this.path = path;
        this.size = size;
        this.data = data;
        this.channel = channel;
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
        return load(Paths.get(filePath), readOnly, IN_MEMORY_LIMIT);
    }

    /**
     * Loads and parses an {@code .hdf} file, holding it in memory only up to
     * {@code inMemoryLimit} bytes and accessing it in place beyond that.
     */
    public static HardFile load(Path p, boolean readOnly, long inMemoryLimit) throws IOException {
        long size = Files.size(p);
        if (size < 2 * BSIZE) {
            throw new IOException(".hdf file too small: " + size + " bytes");
        }
        HardFile hf;
        if (size <= inMemoryLimit && size <= Integer.MAX_VALUE - 8) {
            hf = new HardFile(p, size, Files.readAllBytes(p), null, readOnly);
        } else {
            FileChannel ch = readOnly
                    ? FileChannel.open(p, StandardOpenOption.READ)
                    : FileChannel.open(p, StandardOpenOption.READ, StandardOpenOption.WRITE);
            hf = new HardFile(p, size, null, ch, readOnly);
        }
        try {
            hf.parse();
        } catch (RuntimeException e) {
            hf.close();
            throw new IOException("Cannot parse " + p + ": " + e.getMessage(), e);
        }
        return hf;
    }

    // =========================================================================
    // Geometry / partition parsing
    // =========================================================================

    private void parse() {
        long rdb = seekRDB();
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
    private long seekRDB() {
        long blocks = Math.min(16, size / BSIZE);
        for (long i = 0; i < blocks; i++) {
            if (id4(block(i), 0).equals("RDSK")) return i;
        }
        return -1;
    }

    private void parseRDB(long rdbBlock) {
        byte[] rdb = block(rdbBlock);
        cylinders = r32(rdb, 64);
        sectors   = r32(rdb, 68);
        heads     = r32(rdb, 72);
        int bs    = r32(rdb, 16);
        if (bs == 512) bsize = bs;

        // List of partitions: PartitionList in the RDB -> chain of PART blocks.
        int partRef = r32(rdb, 28); // rdb_PartitionList
        int guard = 0;
        while (validBlock(partRef) && guard++ < 64) {
            byte[] pb = block(partRef);
            if (!id4(pb, 0).equals("PART")) break;
            int next = r32(pb, 16); // pb_Next
            int flags = r32(pb, 20);
            if ((flags & 2) != 0) { // PBFF_NOMOUNT
                partRef = next;
                continue;
            }
            // DOS environment: starts at offset 128 in the PART block; de_TableSize says
            // how many of the longwords after it are present.
            int table = r32(pb, 128);
            Partition part = new Partition();
            part.name           = readBStr(pb, 36, 31);
            part.flags          = flags;
            part.sizeBlock      = r32(pb, 132);
            part.surfaces       = r32(pb, 140);
            part.blocksPerTrack = r32(pb, 148);
            part.reserved       = r32(pb, 152);
            part.lowCyl         = r32(pb, 164);
            part.highCyl        = r32(pb, 168);
            if (table >= 11) part.numBuffers  = r32(pb, 172);
            if (table >= 12) part.bufMemType  = r32(pb, 176);
            if (table >= 13) part.maxTransfer = r32(pb, 180);
            if (table >= 14) part.mask        = r32(pb, 184);
            if (table >= 15) part.bootPri     = r32(pb, 188);
            part.dosType = table >= 16 ? r32(pb, 192) : 0x444F5300;
            if (part.sizeBlock <= 0) part.sizeBlock = BSIZE / 4;
            if (part.surfaces <= 0) part.surfaces = heads;
            if (part.blocksPerTrack <= 0) part.blocksPerTrack = sectors;
            if (part.reserved <= 0) part.reserved = 2;
            if (part.numBuffers <= 0) part.numBuffers = 30;
            if (part.maxTransfer == 0) part.maxTransfer = 0x7FFFFFFF;
            if (part.mask == 0) part.mask = 0xFFFFFFFE;
            partitions.add(part);
            partRef = next;
        }
        parseFileSystems(r32(rdb, 32)); // rdb_FileSysHeaderList
    }

    /** Reads the FSHD chain and the LSEG load file of each filesystem. */
    private void parseFileSystems(int fshdRef) {
        int guard = 0;
        while (validBlock(fshdRef) && guard++ < 32) {
            byte[] fh = block(fshdRef);
            if (!id4(fh, 0).equals("FSHD")) break;
            int dosType = r32(fh, 32);
            int version = r32(fh, 36);
            byte[] file = readLoadFile(r32(fh, 72)); // fhb_SegListBlocks
            if (file != null) {
                try {
                    fileSystems.add(new FileSystem(dosType, version, HunkFile.parse(file)));
                } catch (IOException e) {
                    System.err.println("[HDF] Filesystem " + dosTypeString(dosType)
                            + " in " + path + " not loadable: " + e.getMessage());
                }
            }
            fshdRef = r32(fh, 16); // fhb_Next
        }
    }

    /** Joins the data of an LSEG chain; null if the chain is broken. */
    private byte[] readLoadFile(int lsegRef) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int guard = 0;
        while (validBlock(lsegRef)) {
            if (guard++ > 16384) return null;
            byte[] lb = block(lsegRef);
            if (!id4(lb, 0).equals("LSEG")) return null;
            int longs = r32(lb, 4); // lsb_SummedLongs
            if (longs < 5 || longs > BSIZE / 4) return null;
            out.write(lb, 20, longs * 4 - 20);
            lsegRef = r32(lb, 16); // lsb_Next
        }
        return out.size() > 0 ? out.toByteArray() : null;
    }

    private boolean validBlock(int ref) {
        return ref >= 0 && ((long) ref + 1) * BSIZE <= size;
    }

    private void parseHeaderless() {
        int numBlocks = predictNumBlocks();
        int[] geo = predictGeometry(numBlocks);
        cylinders = geo[0];
        heads     = geo[1];
        sectors   = geo[2];

        Partition part = new Partition();
        part.name           = "DH0";
        part.sizeBlock      = bsize / 4;          // 128
        part.surfaces       = heads;
        part.blocksPerTrack = sectors;
        part.reserved       = 2;
        part.lowCyl         = 0;
        part.highCyl        = cylinders - 1;
        part.bootPri        = 0;
        part.flags          = 1;                   // bootable
        // The DosType is the boot block identifier (e.g. "DOS\1"); if the image is not
        // formatted it falls back on OFS ("DOS\0").
        byte[] b0 = block(0);
        part.dosType = id4(b0, 0).equals("DOS") ? r32(b0, 0) : 0x444F5300;
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
        int fileBlocks = (int) Math.min(Integer.MAX_VALUE, size / BSIZE);
        // Candidate 1: the whole file. Candidate 2: rounded to a multiple of 32 (standard
        // geometry of 32 sectors/track, used by most headerless installers).
        int rounded32 = 32 * (fileBlocks / 32);
        for (int nb : new int[] { fileBlocks, rounded32 }) {
            if (nb > 0 && isRootBlock((2L + nb - 1) / 2)) return nb;
        }
        return fileBlocks;
    }

    /** {@code true} if block {@code n} is an FFS/OFS root block (type=2, sec=ST_ROOT=1). */
    private boolean isRootBlock(long n) {
        if (n < 0 || (n + 1) * BSIZE > size) return false;
        byte[] b = block(n);
        return r32(b, 0) == 2 && r32(b, BSIZE - 4) == 1;
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
        return offset >= 0 && length >= 0 && offset + length <= size;
    }

    /** {@code true} if the image is held in memory ({@link #rawData()} is available). */
    public boolean inMemory() { return data != null; }

    /**
     * Raw byte array of an image held in memory, for the host driver's block transfers
     * ({@code System.arraycopy} instead of a copy through a buffer); {@code null} for an
     * image accessed in place.
     */
    public byte[] rawData() { return data; }

    /** Marks the image as modified after a block write to {@link #rawData()}. */
    public void markModified() { if (!readOnly) modified = true; }

    /**
     * Reads {@code len} bytes at {@code offset}; the caller has checked the range.
     *
     * @throws UncheckedIOException if the file cannot be read
     */
    public void read(long offset, byte[] dst, int dstOff, int len) {
        if (data != null) {
            System.arraycopy(data, (int) offset, dst, dstOff, len);
            return;
        }
        readFile(offset, dst, dstOff, len);
        if (overlay.isEmpty()) return;
        for (long b = offset / BSIZE; b * BSIZE < offset + len; b++) {
            byte[] blk = overlay.get(b);
            if (blk == null) continue;
            long from = Math.max(offset, b * BSIZE);
            long to = Math.min(offset + len, (b + 1) * BSIZE);
            System.arraycopy(blk, (int) (from - b * BSIZE), dst, dstOff + (int) (from - offset),
                    (int) (to - from));
        }
    }

    private void readFile(long offset, byte[] dst, int dstOff, int len) {
        try {
            ByteBuffer bb = ByteBuffer.wrap(dst, dstOff, len);
            long pos = offset;
            while (bb.hasRemaining()) {
                int n = channel.read(bb, pos);
                if (n < 0) throw new IOException("unexpected end of file");
                pos += n;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Writes {@code len} bytes at {@code offset}; on a read-only image only for the session.
     * The caller has checked the range.
     *
     * @throws UncheckedIOException if the file cannot be written
     */
    public void write(long offset, byte[] src, int srcOff, int len) {
        if (data != null) {
            System.arraycopy(src, srcOff, data, (int) offset, len);
            if (!readOnly) modified = true;
            return;
        }
        if (readOnly) {
            for (long b = offset / BSIZE; b * BSIZE < offset + len; b++) {
                byte[] blk = overlay.get(b);
                if (blk == null) {
                    blk = new byte[BSIZE];
                    long end = Math.min(size, (b + 1) * BSIZE);
                    readFile(b * BSIZE, blk, 0, (int) (end - b * BSIZE));
                    overlay.put(b, blk);
                }
                long from = Math.max(offset, b * BSIZE);
                long to = Math.min(offset + len, (b + 1) * BSIZE);
                System.arraycopy(src, srcOff + (int) (from - offset), blk, (int) (from - b * BSIZE),
                        (int) (to - from));
            }
            return;
        }
        try {
            ByteBuffer bb = ByteBuffer.wrap(src, srcOff, len);
            long pos = offset;
            while (bb.hasRemaining()) pos += channel.write(bb, pos);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Saves any changes to disk (no-op if read-only or unchanged). */
    public void flush() {
        if (readOnly) return;
        if (channel != null) {
            try {
                if (channel.isOpen()) channel.force(false);
            } catch (IOException e) {
                System.err.println("[HDF] Save failed for " + path + ": " + e.getMessage());
            }
            return;
        }
        if (!modified) return;
        try {
            Files.write(path, data);
            modified = false;
        } catch (IOException e) {
            System.err.println("[HDF] Save failed for " + path + ": " + e.getMessage());
        }
    }

    /** Saves any changes and releases the file of an image accessed in place. */
    @Override
    public void close() {
        flush();
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException e) {
                System.err.println("[HDF] Closing " + path + " failed: " + e.getMessage());
            }
        }
    }

    // =========================================================================
    // Accessors
    // =========================================================================

    public int cylinders()       { return cylinders; }
    public int heads()           { return heads; }
    public int sectors()         { return sectors; }
    public int blockSize()       { return bsize; }
    public int numPartitions()   { return partitions.size(); }
    public Partition partition(int i) { return partitions.get(i); }
    public List<Partition> partitions() { return partitions; }
    public List<FileSystem> fileSystems() { return fileSystems; }
    public boolean readOnly()    { return readOnly; }
    public long sizeBytes()      { return size; }
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

    /** A DosType as text, e.g. {@code PDS\3}. */
    public static String dosTypeString(int dosType) {
        StringBuilder sb = new StringBuilder(8);
        for (int shift = 24; shift >= 8; shift -= 8) {
            int c = (dosType >>> shift) & 0xFF;
            sb.append(c >= 0x20 && c < 0x7F ? (char) c : '?');
        }
        return sb.append('\\').append(dosType & 0xFF).toString();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Block {@code n} of the image; zeros past its end. */
    private byte[] block(long n) {
        byte[] b = new byte[BSIZE];
        long off = n * BSIZE;
        if (n >= 0 && off < size) read(off, b, 0, (int) Math.min(BSIZE, size - off));
        return b;
    }

    private static int r32(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
             | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static String id4(byte[] b, int off) {
        // 4-byte identifier, truncated at the first NUL (e.g. "DOS\1" -> "DOS").
        StringBuilder sb = new StringBuilder(4);
        for (int i = 0; i < 4; i++) {
            int c = b[off + i] & 0xFF;
            if (c < 0x20 || c > 0x7E) break;
            sb.append((char) c);
        }
        return sb.toString();
    }

    private static String readBStr(byte[] b, int off, int max) {
        // BCPL string: length in the first byte, then the characters.
        int len = Math.min(b[off] & 0xFF, max);
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append((char) (b[off + 1 + i] & 0xFF));
        return sb.toString();
    }
}
