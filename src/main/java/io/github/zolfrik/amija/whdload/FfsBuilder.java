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

package io.github.zolfrik.amija.whdload;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a "headerless" {@code .hdf} hard disk image with an AmigaDOS Fast File System (FFS,
 * {@code DOS\1}) from a host directory tree.
 *
 * <p>The image is compatible with {@link io.github.zolfrik.amija.memory.HardFile} in
 * headerless mode: block 0 starting with {@code DOS\1}, root block at {@code numBlocks/2},
 * number of blocks a multiple of 32 (so the synthesised geometry is Cx1x32). It is mounted as
 * the master/boot unit by the normal Zorro II controller with boot ROM.
 *
 * <p>Format references: Laurent Clévy's FFS documentation / adflib ({@code adf_blk.h}). Only
 * {@code DOS\1} (FFS, no dir cache/intl): raw 512-byte data blocks, file/dir headers with hash
 * table and hash chains.
 */
public final class FfsBuilder {

    private static final int BSIZE = 512;
    private static final int HT_SIZE = 72;        // (BSIZE/4) - 56
    private static final int MAX_NAME = 30;       // limite nomi file/cartella AmigaDOS
    private static final int DBLOCKS = 72;        // data pointers per header/ext
    private static final int BM_COVER = 127 * 32; // blocks mapped by one bitmap block (4064)

    private static final int T_HEADER = 2;
    private static final int T_LIST = 16;
    private static final int ST_ROOT = 1;
    private static final int ST_USERDIR = 2;
    private static final int ST_FILE = -3;

    private byte[] img;
    private int numBlocks;
    private int rootKey;
    private boolean[] used;
    private int allocCursor = 2;
    private final List<Integer> bitmapBlocks = new ArrayList<>();
    // Override on-disk name -> original Amiga name (LhaExtractor's .amiganames sidecar).
    private Path srcRoot;
    private final Map<String, String> nameOverrides = new HashMap<>();

    private FfsBuilder() {}

    /**
     * Builds the FFS image of {@code srcDir} (recursively) and writes it to {@code outHdf}.
     *
     * @param volumeName name of the volume (DH0)
     * @param slackBlocks extra free blocks beyond the content (for WHDLoad's run-time
     *                    writes: saves, .info, etc.)
     */
    public static void build(Path srcDir, String volumeName, Path outHdf, int slackBlocks)
            throws IOException {
        new FfsBuilder().run(srcDir, volumeName, outHdf, slackBlocks);
    }

    // =========================================================================
    // Modello dell'albero sorgente
    // =========================================================================

    private static final class Node {
        String name;
        boolean dir;
        byte[] data;              // files only
        List<Node> children = new ArrayList<>();
        int block;                // assigned during allocation
    }

    private Node scan(Path dir, String name) throws IOException {
        Node n = new Node();
        n.name = truncName(name);
        n.dir = true;
        List<Path> kids;
        try (var s = Files.list(dir)) {
            kids = new ArrayList<>(s.toList());
        }
        // Deterministic order (directories and files together, by name).
        kids.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT)));
        for (Path p : kids) {
            String cn = amigaName(p);
            if (cn == null) continue;          // .amiganames sidecar: does not go into the FFS
            if (Files.isDirectory(p)) {
                n.children.add(scan(p, cn));
            } else {
                Node f = new Node();
                f.name = truncName(cn);
                f.dir = false;
                f.data = Files.readAllBytes(p);
                n.children.add(f);
            }
        }
        return n;
    }

    /**
     * AmigaDOS name to use in the FFS for host path {@code p}: the original Amiga name if
     * {@link LhaExtractor} had encoded it for Windows (the {@code .amiganames} sidecar),
     * otherwise the name of the file on disk. Returns {@code null} for the sidecar file itself,
     * which must not go into the FFS.
     */
    private String amigaName(Path p) {
        String disk = p.getFileName().toString();
        if (srcRoot != null && p.getParent() != null
                && p.getParent().equals(srcRoot) && disk.equals(LhaExtractor.AMIGA_NAMES_SIDECAR)) {
            return null;
        }
        if (!nameOverrides.isEmpty() && srcRoot != null) {
            String rel = srcRoot.relativize(p).toString().replace('\\', '/');
            String orig = nameOverrides.get(rel);
            if (orig != null) return orig;
        }
        return disk;
    }

    private void loadNameOverrides(Path srcDir) throws IOException {
        Path sidecar = srcDir.resolve(LhaExtractor.AMIGA_NAMES_SIDECAR);
        if (!Files.isRegularFile(sidecar)) return;
        for (String line : Files.readAllLines(sidecar, StandardCharsets.UTF_8)) {
            int tab = line.indexOf('\t');
            if (tab > 0) nameOverrides.put(line.substring(0, tab), line.substring(tab + 1));
        }
    }

    // =========================================================================
    // Construction
    // =========================================================================

    private void run(Path srcDir, String volumeName, Path outHdf, int slackBlocks)
            throws IOException {
        this.srcRoot = srcDir;
        loadNameOverrides(srcDir);
        Node root = scan(srcDir, volumeName);

        int content = countBlocks(root);
        int needed = 2 /*boot*/ + 1 /*root*/ + content;
        // Estimate of the bitmap blocks and final sizing (a multiple of 32).
        int total = needed + Math.max(0, slackBlocks);
        int nbm = (total + BM_COVER - 1) / BM_COVER;
        total += nbm + 1;                              // bitmap block + margine
        numBlocks = roundUp(total, 32);
        if (numBlocks < 64) numBlocks = 64;
        rootKey = numBlocks / 2;

        img = new byte[numBlocks * BSIZE];
        used = new boolean[numBlocks];
        used[0] = used[1] = true;
        used[rootKey] = true;

        // Bitmap blocks: assigned at once (the root needs them before it is written).
        nbm = (numBlocks - 2 + BM_COVER - 1) / BM_COVER;
        for (int i = 0; i < nbm; i++) {
            int b = allocBlock();
            bitmapBlocks.add(b);
        }

        // Boot block: starting with "DOS\1" is enough for HardFile to read the dosType; the
        // file system in ROM mounts the partition from the geometry, not from here.
        writeLong(0, 0x444F5301);     // "DOS\1" (FFS)
        writeLong(8, rootKey);        // pointer to the root (courtesy)

        // Recursive allocation of every directory and file (root included in rootKey).
        allocTree(root);

        // Writing the blocks.
        writeRoot(root, volumeName);
        for (Node child : root.children) writeNode(child, rootKey);

        writeBitmap();

        Files.write(outHdf, img);
    }

    /** Counts the blocks the content needs (dirs + file headers + ext + data). */
    private int countBlocks(Node n) {
        int blocks = 0;
        for (Node c : n.children) {
            if (c.dir) {
                blocks += 1 + countBlocks(c);
            } else {
                int db = (c.data.length + BSIZE - 1) / BSIZE;
                int hdr = 1;
                int rem = Math.max(0, db - DBLOCKS);
                int ext = (rem + DBLOCKS - 1) / DBLOCKS;
                blocks += hdr + ext + db;
            }
        }
        return blocks;
    }

    private void allocTree(Node dir) {
        for (Node c : dir.children) {
            c.block = allocBlock();
            if (c.dir) allocTree(c);
        }
    }

    private int allocBlock() {
        while (allocCursor < numBlocks && used[allocCursor]) allocCursor++;
        if (allocCursor >= numBlocks) throw new IllegalStateException("HDF pieno");
        used[allocCursor] = true;
        return allocCursor++;
    }

    // =========================================================================
    // Writing the blocks
    // =========================================================================

    private void writeRoot(Node root, String volumeName) {
        int base = rootKey * BSIZE;
        writeLong(base + 0, T_HEADER);
        writeLong(base + 12, HT_SIZE);
        // hash table
        fillHashTable(base, root.children);
        // bitmap
        writeLong(base + 312, -1);                       // bm_flag valido
        for (int i = 0; i < bitmapBlocks.size() && i < 25; i++) {
            writeLong(base + 316 + i * 4, bitmapBlocks.get(i));
        }
        // volume name (BCPL) at offset 432
        writeName(base + 432, volumeName);
        writeLong(base + 508, ST_ROOT);
        checksum(base, 20);
    }

    private void writeNode(Node n, int parentBlock) {
        if (n.dir) writeDir(n, parentBlock);
        else writeFile(n, parentBlock);
    }

    private void writeDir(Node n, int parentBlock) {
        int base = n.block * BSIZE;
        writeLong(base + 0, T_HEADER);
        writeLong(base + 4, n.block);                    // header key
        fillHashTable(base, n.children);
        writeName(base + 432, n.name);
        writeLong(base + 500, parentBlock);
        writeLong(base + 508, ST_USERDIR);
        checksum(base, 20);
        for (Node c : n.children) writeNode(c, n.block);
    }

    private void writeFile(Node n, int parentBlock) {
        byte[] data = n.data;
        int nblk = (data.length + BSIZE - 1) / BSIZE;

        // Allocation of the data blocks (in file order).
        int[] db = new int[nblk];
        for (int i = 0; i < nblk; i++) db[i] = allocBlock();

        // Extension blocks (if > 72 data blocks).
        int nExt = Math.max(0, (nblk - DBLOCKS + DBLOCKS - 1) / DBLOCKS);
        int[] extBlk = new int[nExt];
        for (int i = 0; i < nExt; i++) extBlk[i] = allocBlock();

        // File header.
        int base = n.block * BSIZE;
        int inHeader = Math.min(nblk, DBLOCKS);
        writeLong(base + 0, T_HEADER);
        writeLong(base + 4, n.block);
        writeLong(base + 8, inHeader);                   // high_seq
        writeLong(base + 16, nblk > 0 ? db[0] : 0);      // first_data
        // data pointers in reverse order: slot 71 = first block
        for (int i = 0; i < inHeader; i++) {
            writeLong(base + 24 + (DBLOCKS - 1 - i) * 4, db[i]);
        }
        writeLong(base + 324, data.length);              // byte_size
        writeName(base + 432, n.name);
        writeLong(base + 500, parentBlock);
        writeLong(base + 504, nExt > 0 ? extBlk[0] : 0); // extension
        writeLong(base + 508, ST_FILE);
        checksum(base, 20);

        // Extension blocks.
        int idx = DBLOCKS;
        for (int e = 0; e < nExt; e++) {
            int eb = extBlk[e] * BSIZE;
            int cnt = Math.min(DBLOCKS, nblk - idx);
            writeLong(eb + 0, T_LIST);
            writeLong(eb + 4, extBlk[e]);
            writeLong(eb + 8, cnt);
            for (int i = 0; i < cnt; i++) {
                writeLong(eb + 24 + (DBLOCKS - 1 - i) * 4, db[idx + i]);
            }
            writeLong(eb + 500, n.block);                // parent = file header
            writeLong(eb + 504, e + 1 < nExt ? extBlk[e + 1] : 0);
            writeLong(eb + 508, ST_FILE);
            checksum(eb, 20);
            idx += cnt;
        }

        // Raw data blocks (FFS).
        for (int i = 0; i < nblk; i++) {
            int off = i * BSIZE;
            int len = Math.min(BSIZE, data.length - off);
            System.arraycopy(data, off, img, db[i] * BSIZE, len);
        }
    }

    /** Fills the hash table of a directory/root block with its children and chains the colliders. */
    private void fillHashTable(int base, List<Node> children) {
        for (Node c : children) {
            int h = hash(c.name);
            int slot = base + 24 + h * 4;
            int existing = readLong(slot);
            // Insertion at the head: the new one points to the previous with the same hash.
            int cb = c.block * BSIZE;
            writeLong(cb + 496, existing);               // next_same_hash
            writeLong(slot, c.block);
        }
    }

    private void writeBitmap() {
        // bit = 1 -> free, bit = 0 -> used. The first block mapped is block 2.
        for (int bi = 0; bi < bitmapBlocks.size(); bi++) {
            int base = bitmapBlocks.get(bi) * BSIZE;
            int firstBlock = 2 + bi * BM_COVER;
            for (int w = 0; w < 127; w++) {
                int word = 0;
                for (int bit = 0; bit < 32; bit++) {
                    int blk = firstBlock + w * 32 + bit;
                    boolean free = blk < numBlocks && !used[blk];
                    if (free) word |= (1 << bit);
                }
                writeLong(base + 4 + w * 4, word);
            }
            checksum(base, 0);
        }
    }

    // =========================================================================
    // Helper
    // =========================================================================

    /**
     * Truncates a name to the AmigaDOS limit (30 characters). It MUST be applied BEFORE the
     * directory hash is computed: the hash ({@link #hash}) and the stored name
     * ({@link #writeName}) must be based on the SAME name, otherwise the file ends up in a
     * hash bucket different from the one AmigaDOS/WHDLoad looks in and is unreachable
     * (DOS-Error #205).
     */
    private static String truncName(String name) {
        return name.length() > MAX_NAME ? name.substring(0, MAX_NAME) : name;
    }

    private static int hash(String name) {
        int h = name.length();
        for (int i = 0; i < name.length(); i++) {
            int c = name.charAt(i) & 0xFF;
            if (c >= 'a' && c <= 'z') c -= 32;           // upper-case non-intl
            h = (h * 13 + c) & 0x7FF;
        }
        return h % HT_SIZE;
    }

    private void writeName(int off, String name) {
        byte[] b = name.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        int len = Math.min(b.length, 30);
        img[off] = (byte) len;
        System.arraycopy(b, 0, img, off + 1, len);
    }

    private void checksum(int base, int checkOff) {
        writeLong(base + checkOff, 0);
        long sum = 0;
        for (int i = 0; i < BSIZE; i += 4) {
            sum = (sum + (readLong(base + i) & 0xFFFFFFFFL)) & 0xFFFFFFFFL;
        }
        writeLong(base + checkOff, (int) ((-sum) & 0xFFFFFFFFL));
    }

    private int roundUp(int v, int m) { return ((v + m - 1) / m) * m; }

    private void writeLong(int off, int v) {
        img[off]     = (byte) (v >>> 24);
        img[off + 1] = (byte) (v >>> 16);
        img[off + 2] = (byte) (v >>> 8);
        img[off + 3] = (byte) v;
    }

    private int readLong(int off) {
        return ((img[off] & 0xFF) << 24) | ((img[off + 1] & 0xFF) << 16)
             | ((img[off + 2] & 0xFF) << 8) | (img[off + 3] & 0xFF);
    }
}
