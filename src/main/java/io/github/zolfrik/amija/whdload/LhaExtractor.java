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

package io.github.zolfrik.amija.whdload;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;

/**
 * Extractor of LHA/LZH archives (the format of WHDLoad packages).
 *
 * <p>Supports header levels 0, 1 and 2 and the compression methods {@code -lh0-}/
 * {@code -lhd-} (stored / directory), {@code -lh4-}, {@code -lh5-}, {@code -lh6-} and
 * {@code -lh7-} (LZSS + dynamic Huffman, 4/8/32/64 KB window): in practice the set that
 * covers every archive of the WHDLoad collection.
 *
 * <p>The decoding algorithm is the classic one by Haruhiko Okumura / Haruyasu Yoshizaki
 * (LHa): MSB-first bit stream, three Huffman trees (code lengths, literal/match codes,
 * positions) rebuilt at every block.
 *
 * <p>Extracts keeping the archive's internal directory structure.
 */
public final class LhaExtractor {

    /** One extracted entry (regular file or directory). */
    public static final class Entry {
        public final String path;     // relative path with '/' separator, Amiga name
        /** The same path as stored on the host disk (see {@link #windowsSafePath}). */
        public final String diskPath;
        public final boolean directory;
        public final long size;
        Entry(String path, String diskPath, boolean directory, long size) {
            this.path = path; this.diskPath = diskPath;
            this.directory = directory; this.size = size;
        }
    }

    private final byte[] archive;
    private final List<Entry> entries = new ArrayList<>();
    // Map on-disk path (encoded) -> original Amiga name, for the names Windows cannot
    // represent (trailing dots/spaces). See {@link #windowsSafeLeaf}.
    private final Map<String, String> nameOverrides = new LinkedHashMap<>();

    /** Name of the sidecar file carrying the original Amiga names to {@link FfsBuilder}. */
    static final String AMIGA_NAMES_SIDECAR = ".amiganames";

    /**
     * Cap on the declared size of a single entry (256 MB). A whole WHDLoad game fits in a few
     * tens of MB; beyond this threshold the number describes not a file but a corrupt
     * header.
     */
    private static final long MAX_ENTRY_BYTES = 256L << 20;

    private LhaExtractor(byte[] archive) { this.archive = archive; }

    /**
     * Extracts the archive {@code lhaPath} into the directory {@code destDir}, recreating the
     * internal structure. Returns the list of entries extracted.
     */
    public static List<Entry> extract(String lhaPath, Path destDir) throws IOException {
        byte[] data = Files.readAllBytes(Paths.get(lhaPath));
        LhaExtractor ex = new LhaExtractor(data);
        ex.run(destDir);
        return ex.entries;
    }

    /**
     * Decodes only the archive's icons ({@code .info}), without writing anything to disk
     * and without decompressing the rest. Used to read the tooltypes the installation
     * describes itself with (see {@link AmigaIcon}) even before building the machine: the
     * icons are a few KB, the game tens of MB.
     *
     * @return Amiga path of the icon -> content
     */
    static Map<String, byte[]> extractIcons(String lhaPath) throws IOException {
        return extractBySuffix(lhaPath, ".info");
    }

    /**
     * Decodes only the archive's files whose name ends with {@code suffix} (case-insensitive),
     * without writing anything to disk and without decompressing the rest.
     *
     * @return Amiga path of the file -> content
     */
    static Map<String, byte[]> extractBySuffix(String lhaPath, String suffix) throws IOException {
        byte[] data = Files.readAllBytes(Paths.get(lhaPath));
        LhaExtractor ex = new LhaExtractor(data);
        Map<String, byte[]> found = new LinkedHashMap<>();
        int off = 0;
        while (off + 1 < ex.archive.length) {
            int headerSize = ex.archive[off] & 0xFF;
            if (headerSize == 0) break;
            Header h = ex.parseHeader(off);
            if (h == null) break;
            String relPath = sanitize(h.name);
            boolean dir = h.isDirectory || h.method.equals("-lhd-");
            if (!dir && relPath.toLowerCase(Locale.ROOT).endsWith(suffix)) {
                found.put(relPath, ex.decode(h));
            }
            off = h.nextOffset;
        }
        return found;
    }

    private void run(Path destDir) throws IOException {
        Path base = destDir.toAbsolutePath().normalize();
        int off = 0;
        while (off + 1 < archive.length) {
            int headerSize = archive[off] & 0xFF;
            if (headerSize == 0) break;             // end-of-archive marker
            Header h = parseHeader(off);
            if (h == null) break;

            String relPath = sanitize(h.name);
            if (h.isDirectory || h.method.equals("-lhd-")) {
                if (!relPath.isEmpty()) {
                    String safe = windowsSafePath(relPath);
                    Path d = resolveInside(base, safe);
                    Files.createDirectories(d);
                    entries.add(new Entry(relPath, safe, true, 0));
                }
            } else {
                byte[] out = decode(h);
                String safe = windowsSafePath(relPath);
                Path f = resolveInside(base, safe);
                if (f.getParent() != null) Files.createDirectories(f.getParent());
                Files.write(f, out);
                entries.add(new Entry(relPath, safe, false, out.length));
            }
            off = h.nextOffset;
        }
        writeSidecar(destDir);
    }

    /**
     * Resolves an entry inside {@code base}, refusing anything that would leave it.
     *
     * <p>An entry's path is declared by the <b>archive</b>, and an archive comes from
     * outside: a WHDLoad game's is downloaded from the Internet and named in F12, and
     * {@code AssetInstaller} unpacks one just arrived from the network. That no entry can
     * write outside the work folder therefore cannot depend on the good faith of whoever
     * packed the archive.
     *
     * <p><b>No name manages it today, but by chance.</b> {@code ..} is neutralised by
     * {@link #windowsSafePath}: a component made only of dots ends up entirely in the
     * percent-encoding of trailing dots and becomes the literal name {@code %2E%2E}. That is
     * a side effect of a function written for a different reason (the names Windows cannot
     * represent), and narrowing its scope (e.g. applying it on Windows only, a reasonable
     * change) would reopen the way with nothing to signal it. A guarantee is stated where it
     * is needed, not inferred elsewhere.
     *
     * <p>One case is really closed here: on Windows a name with a drive prefix
     * ({@code C:\...}) is an <b>absolute</b> path, and {@code Path.resolve} returns the
     * argument as it is instead of resolving it inside {@code base}. {@link #sanitize}
     * already removes it; should it escape in some unexpected form, this check stops it.
     */
    private static Path resolveInside(Path base, String relPath) throws IOException {
        Path resolved = base.resolve(relPath).normalize();
        if (!resolved.startsWith(base)) {
            throw new IOException("voce dell'archivio fuori dalla cartella di destinazione: "
                    + relPath);
        }
        return resolved;
    }

    /**
     * Returns a path writable on a Windows disk equivalent to {@code relPath}.
     *
     * <p>Windows silently drops trailing dots and spaces from every name component (e.g. the
     * Amiga file {@code ruptile.} would become {@code ruptile}): a game opening
     * {@code ruptile.} would no longer find it. On AmigaDOS those characters are legal. To
     * keep them, every problematic trailing character is percent-encoded ({@code .}=%2E,
     * space=%20), harmless for Windows, and the original Amiga name is recorded in
     * {@link #nameOverrides}, so {@link FfsBuilder} can write it back exactly in the FFS
     * file system.
     */
    private String windowsSafePath(String relPath) {
        String[] parts = relPath.split("/");
        StringBuilder encodedPath = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String orig = parts[i];
            String safe = windowsSafeLeaf(orig);
            if (i > 0) encodedPath.append('/');
            encodedPath.append(safe);
            if (!safe.equals(orig)) {
                nameOverrides.put(encodedPath.toString(), orig);
            }
        }
        return encodedPath.toString();
    }

    /** Percent-encodes only the trailing dots/spaces of a name component. */
    private static String windowsSafeLeaf(String leaf) {
        int end = leaf.length();
        while (end > 0 && (leaf.charAt(end - 1) == '.' || leaf.charAt(end - 1) == ' ')) end--;
        if (end == leaf.length()) return leaf;
        StringBuilder sb = new StringBuilder(leaf.substring(0, end));
        for (int i = end; i < leaf.length(); i++) {
            sb.append('%').append(String.format("%02X", leaf.charAt(i) & 0xFF));
        }
        return sb.toString();
    }

    private void writeSidecar(Path destDir) throws IOException {
        if (nameOverrides.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : nameOverrides.entrySet()) {
            sb.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        }
        Files.write(destDir.resolve(AMIGA_NAMES_SIDECAR), sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    // =========================================================================
    // Intestazioni
    // =========================================================================

    private static final class Header {
        String method;
        long compSize;     // size of the compressed payload (bytes)
        long origSize;     // original size (bytes)
        String name;
        boolean isDirectory;
        int dataStart;     // offset of the first byte of compressed data
        int nextOffset;    // offset of the next entry

        /**
         * Payload bytes of THIS entry, i.e. {@code nextOffset - dataStart}.
         *
         * <p><b>It is not {@code compSize}</b>, and confusing the two broke WHDLoad. At level
         * 1 the field at offset 7 is a <b>skip size</b> measured from after the first length
         * word of the extended headers: it <i>also</i> includes the bodies of the extended
         * headers, while {@code dataStart} falls after them. Adding the skip size to
         * {@code dataStart} overruns the entry by as many bytes as the extended headers, and
         * on the archive's last entry it overruns the archive.
         */
        int payloadSize;
    }

    private Header parseHeader(int off) {
        int level = archive[off + 20] & 0xFF;
        Header h = new Header();
        h.method = ascii(off + 2, 5);
        h.compSize = le32(off + 7);
        h.origSize = le32(off + 11);

        if (level == 0 || level == 1) {
            int headerSize = archive[off] & 0xFF;
            int fnlen = archive[off + 21] & 0xFF;
            String base = ascii(off + 22, fnlen);
            if (level == 0) {
                // Base header = 2 + headerSize bytes; no extended header.
                h.name = base;
                h.dataStart = off + 2 + headerSize;
                h.nextOffset = h.dataStart + (int) h.compSize;
            } else {
                // Level 1: the chain of extended headers starts at off+headerSize, the
                // compressed data right after. Directories come from extended headers of type
                // 0x02; the base name is the leaf file. compSize is the size of the
                // compressed payload alone -> next = dataStart + compSize.
                int p = off + headerSize;
                String dir = "";
                String fname = base;
                int ext = le16(p); p += 2;
                while (ext != 0) {
                    int type = archive[p] & 0xFF;
                    int bodyLen = ext - 3;          // ext = type(1) + body + word(2)
                    if (type == 0x01) {
                        fname = ascii(p + 1, bodyLen);
                    } else if (type == 0x02) {
                        dir = extDir(p + 1, bodyLen);
                    }
                    p += ext - 2;                   // advance to the next length word
                    ext = le16(p); p += 2;
                }
                h.dataStart = p;
                // compSize is the "skip size" measured from after the first length word of
                // the extended headers (off+headerSize+2): it includes the bodies of the
                // extended headers and the compressed payload up to the next entry.
                h.nextOffset = off + headerSize + 2 + (int) h.compSize;
                h.name = dir + fname;
            }
        } else { // livello 2
            int total = le16(off);                  // total header size (LE word)
            int crcPos = off + 21;                   // crc(2) + os(1) ...
            // Extended headers starting at off+24.
            String dir = "", fname = "";
            int p = off + 24;
            int ext = le16(off + 22);
            // For level 2 the first length word is at off+22.
            p = off + 24;
            int extLen = ext;
            int q = off + 22;
            extLen = le16(q); q += 2;
            while (extLen != 0) {
                int type = archive[q] & 0xFF;
                int bodyLen = extLen - 3;
                if (type == 0x01) fname = ascii(q + 1, bodyLen);
                else if (type == 0x02) dir = extDir(q + 1, bodyLen);
                q += extLen - 2;
                extLen = le16(q); q += 2;
            }
            h.name = dir + fname;
            h.dataStart = off + total;
            h.nextOffset = h.dataStart + (int) h.compSize;
        }
        // A single derivation for all three levels: the payload is what lies between the
        // start of the data and the next entry. See Header.payloadSize.
        h.payloadSize = h.nextOffset - h.dataStart;
        return h;
    }

    /** Extended header 0x02: components separated by 0xFF, returns "a/b/" with a trailing '/'. */
    private String extDir(int off, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            int c = archive[off + i] & 0xFF;
            sb.append(c == 0xFF ? '/' : (char) c);
        }
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '/') sb.append('/');
        return sb.toString();
    }

    private static String sanitize(String name) {
        // Amiga LhAs store "name\0filenote" in the name field (fnlen counts both); the NUL
        // and the comment after it are not part of the path and are illegal characters on
        // Windows. Truncate at the first NUL.
        int nul = name.indexOf('\0');
        if (nul >= 0) name = name.substring(0, nul);
        String s = name.replace('\\', '/');
        // Some archives (level 0) separate directories with 0xFF.
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c == 0xFF ? '/' : c);
        }
        s = sb.toString();
        // Windows drive prefix (C:, D:, ...): removed, or on Windows the path would be
        // ABSOLUTE and Path.resolve would return the argument instead of resolving it inside
        // the work folder. Removed on every platform, so what the extractor does does not
        // depend on where it runs.
        if (s.length() >= 2 && s.charAt(1) == ':' && isDriveLetter(s.charAt(0))) {
            s = s.substring(2);
        }
        while (s.startsWith("/")) s = s.substring(1);
        return s;
    }

    private static boolean isDriveLetter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    // =========================================================================
    // Decoding
    // =========================================================================

    /**
     * The payload of an entry, decompressed.
     *
     * <p>{@code origSize} and {@code dataStart} come from the <b>header</b>, i.e. from the
     * file: an archive truncated halfway through a download, or not an archive at all,
     * would give {@code ArrayIndexOutOfBoundsException} from {@code arraycopy} or
     * {@code OutOfMemoryError} from {@code new byte[]}. Neither is an {@code IOException},
     * so the {@code catch} of whoever mounts the WHDLoad hard disk would not take them and
     * the error would reach the machine start-up, instead of becoming the "unreadable
     * archive" line the user can understand.
     */
    private byte[] decode(Header h) throws IOException {
        checkPayload(h);
        if (h.method.equals("-lh0-") || h.method.equals("-lhd-") || h.method.equals("-lz4-")) {
            byte[] out = new byte[(int) h.origSize];
            System.arraycopy(archive, h.dataStart, out, 0, (int) h.origSize);
            return out;
        }
        int dicbit;
        switch (h.method) {
            case "-lh4-": dicbit = 12; break;
            case "-lh5-": dicbit = 13; break;
            case "-lh6-": dicbit = 15; break;
            case "-lh7-": dicbit = 16; break;
            default: throw new UnsupportedOperationException("Metodo LHA non supportato: " + h.method);
        }
        return new Lh5Decoder(archive, h.dataStart, (int) h.origSize, dicbit).decode();
    }

    /** The declared sizes must fit inside the archive that declares them. */
    private void checkPayload(Header h) throws IOException {
        if (h.origSize < 0 || h.origSize > MAX_ENTRY_BYTES) {
            throw new IOException("voce di " + h.origSize + " byte dichiarati in '"
                    + h.name + "': fuori scala, l'archivio e' corrotto");
        }
        if (h.dataStart < 0 || h.dataStart > archive.length) {
            throw new IOException("voce '" + h.name + "': i dati iniziano fuori "
                    + "dall'archivio, che e' troncato");
        }
        // For the stored methods the payload is as long as the original, and that is what
        // decode() copies; for the others it is payloadSize. NOT compSize: at level 1 that
        // field is a skip size that includes the extended headers too, so adding it to
        // dataStart overruns the entry, and on the last entry it overruns the archive,
        // declaring a sound .lha truncated. See Header.payloadSize: that is the defect that
        // broke the WHDLoad installation and the start of .lha games, all -lh5- level 1.
        long needed = h.method.equals("-lh0-") || h.method.equals("-lhd-")
                || h.method.equals("-lz4-") ? h.origSize : h.payloadSize;
        if (needed < 0 || h.dataStart + needed > archive.length) {
            throw new IOException("voce '" + h.name + "': dichiara " + needed
                    + " byte di dati ma dopo l'inizio ne restano "
                    + (archive.length - h.dataStart) + ": archivio troncato");
        }
    }

    // =========================================================================
    // Reading integers (little-endian) and ASCII
    // =========================================================================

    private int le16(int off) {
        return (archive[off] & 0xFF) | ((archive[off + 1] & 0xFF) << 8);
    }

    private long le32(int off) {
        return (archive[off] & 0xFFL) | ((archive[off + 1] & 0xFFL) << 8)
             | ((archive[off + 2] & 0xFFL) << 16) | ((archive[off + 3] & 0xFFL) << 24);
    }

    private String ascii(int off, int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len && off + i < archive.length; i++) {
            sb.append((char) (archive[off + i] & 0xFF));
        }
        return sb.toString();
    }

    // =========================================================================
    // Decoder LZSS + Huffman dinamico (lh4/5/6/7)
    // =========================================================================

    private static final class Lh5Decoder {
        private static final int THRESHOLD = 3;
        private static final int MAXMATCH = 256;
        private static final int NC = 255 + MAXMATCH + 2 - THRESHOLD; // 510
        private static final int CBIT = 9;
        private static final int NT = 16 + 3;                          // 19
        private static final int TBIT = 5;

        private final byte[] in;
        private final int end;
        private final int origSize;
        private final int np;        // number of position codes = dicbit + 1
        private final int pbit;      // bits to read the count of the position tree

        // Bit reader MSB-first.
        private int pos;
        private long buf;
        private int bits;

        // Huffman tables.
        private final int[] cLen = new int[NC];
        private final int[] cTable = new int[1 << 12];
        private final int[] ptLen = new int[NT > 256 ? NT : 256];
        private final int[] ptTable = new int[1 << 8];
        private final int[] left = new int[2 * NC];
        private final int[] right = new int[2 * NC];

        private int blocksize;

        Lh5Decoder(byte[] in, int dataStart, int origSize, int dicbit) {
            this.in = in;
            this.pos = dataStart;
            this.end = in.length;
            this.origSize = origSize;
            this.np = dicbit + 1;
            this.pbit = (dicbit <= 13) ? 4 : 5;
        }

        byte[] decode() {
            byte[] out = new byte[origSize];
            int outPos = 0;
            blocksize = 0;
            while (outPos < origSize) {
                int c = decodeC();
                if (c < 256) {
                    out[outPos++] = (byte) c;
                } else {
                    int length = c - 256 + THRESHOLD;
                    int dist = decodeP() + 1;
                    int src = outPos - dist;
                    for (int i = 0; i < length && outPos < origSize; i++) {
                        out[outPos++] = out[src + i];
                    }
                }
            }
            return out;
        }

        // --- bit reader ---
        private void ensure(int n) {
            while (bits < n) {
                int b = (pos < end) ? (in[pos++] & 0xFF) : 0;
                buf = (buf << 8) | b;
                bits += 8;
            }
        }
        private int peek(int n) {
            if (n == 0) return 0;
            ensure(n);
            return (int) ((buf >>> (bits - n)) & ((1L << n) - 1));
        }
        private void skip(int n) {
            ensure(n);
            bits -= n;
            buf &= (bits == 0) ? 0 : ((1L << bits) - 1);
        }
        private int getbits(int n) {
            int v = peek(n);
            skip(n);
            return v;
        }

        // --- ricostruzione tabelle ---
        private void readPtLen(int nn, int nbit, int iSpecial) {
            int n = getbits(nbit);
            if (n == 0) {
                int c = getbits(nbit);
                for (int i = 0; i < nn; i++) ptLen[i] = 0;
                for (int i = 0; i < ptTable.length; i++) ptTable[i] = c;
            } else {
                int i = 0;
                while (i < n && i < nn) {
                    int c = peek(3);
                    if (c < 7) {
                        skip(3);
                    } else {
                        skip(3);
                        while (peek(1) == 1) { skip(1); c++; }
                        skip(1);
                    }
                    ptLen[i++] = c;
                    if (i == iSpecial) {
                        int z = getbits(2);
                        while (--z >= 0 && i < nn) ptLen[i++] = 0;
                    }
                }
                while (i < nn) ptLen[i++] = 0;
                makeTable(nn, ptLen, 8, ptTable);
            }
        }

        private void readCLen() {
            int n = getbits(CBIT);
            if (n == 0) {
                int c = getbits(CBIT);
                for (int i = 0; i < NC; i++) cLen[i] = 0;
                for (int i = 0; i < cTable.length; i++) cTable[i] = c;
            } else {
                int i = 0;
                while (i < n && i < NC) {
                    int w = peek(16);
                    int c = ptTable[w >>> 8];
                    if (c >= NT) {
                        int mask = 1 << 7;
                        do {
                            c = ((w & mask) != 0 ? right[c] : left[c]);
                            mask >>= 1;
                        } while (c >= NT);
                    }
                    skip(ptLen[c]);
                    if (c <= 2) {
                        if (c == 0) c = 1;
                        else if (c == 1) c = getbits(4) + 3;
                        else c = getbits(CBIT) + 20;
                        while (--c >= 0 && i < NC) cLen[i++] = 0;
                    } else {
                        cLen[i++] = c - 2;
                    }
                }
                while (i < NC) cLen[i++] = 0;
                makeTable(NC, cLen, 12, cTable);
            }
        }

        private int decodeC() {
            if (blocksize == 0) {
                blocksize = getbits(16);
                readPtLen(NT, TBIT, 3);
                readCLen();
                readPtLen(np, pbit, -1);
            }
            blocksize--;
            int w = peek(16);
            int j = cTable[w >>> 4];          // first 12 bits of the 16-bit window
            if (j >= NC) {
                int mask = 1 << 3;            // following bits: 1<<(15-12)
                do {
                    j = ((w & mask) != 0 ? right[j] : left[j]);
                    mask >>= 1;
                } while (j >= NC);
            }
            skip(cLen[j]);
            return j;
        }

        private int decodeP() {
            int w = peek(16);
            int j = ptTable[w >>> 8];         // primi 8 bit
            if (j >= np) {
                int mask = 1 << 7;            // following bits: 1<<(15-8)
                do {
                    j = ((w & mask) != 0 ? right[j] : left[j]);
                    mask >>= 1;
                } while (j >= np);
            }
            skip(ptLen[j]);
            if (j != 0) {
                j = (1 << (j - 1)) + getbits(j - 1);
            }
            return j;
        }

        /**
         * Builds a {@code tablebits}-bit Huffman decoding table from the lengths
         * {@code bitlen}; codes longer than {@code tablebits} use the {@code left}/
         * {@code right} arrays as a tree. Port of LHa's make_table.
         */
        private void makeTable(int nchar, int[] bitlen, int tablebits, int[] table) {
            int[] count = new int[17];
            int[] weight = new int[17];
            int[] start = new int[18];
            for (int i = 1; i <= 16; i++) count[i] = 0;
            for (int i = 0; i < nchar; i++) count[bitlen[i]]++;
            // start[] is 16-bit (ushort in LHa): the accumulation is truncated to 0xFFFF at
            // every step to reproduce the wrap of an unsigned short.
            start[1] = 0;
            for (int i = 1; i <= 16; i++) {
                start[i + 1] = (start[i] + (count[i] << (16 - i))) & 0xFFFF;
            }

            int jutbits = 16 - tablebits;
            for (int i = 1; i <= tablebits; i++) {
                start[i] = (start[i] & 0xFFFF) >>> jutbits;
                weight[i] = 1 << (tablebits - i);
            }
            for (int i = tablebits + 1; i <= 16; i++) {
                weight[i] = 1 << (16 - i);
            }

            int idx = (start[tablebits + 1] & 0xFFFF) >>> jutbits;
            if (idx != 0) {
                int k = 1 << tablebits;
                if (idx != k) {
                    for (int i = idx; i < k; i++) table[i] = 0;
                }
            }

            int avail = nchar;
            int mask = 1 << (15 - tablebits);
            for (int ch = 0; ch < nchar; ch++) {
                int len = bitlen[ch];
                if (len == 0) continue;
                int nextcode = (start[len] & 0xFFFF) + weight[len];
                if (len <= tablebits) {
                    int s = start[len] & 0xFFFF;
                    int e = nextcode;
                    if (e > table.length) e = table.length;
                    for (int i = s; i < e; i++) table[i] = ch;
                } else {
                    // Code longer than tablebits: a path in the left/right tree.
                    avail = buildLong(table, ch, len, start[len] & 0xFFFF,
                                      tablebits, jutbits, mask, avail);
                }
                start[len] = nextcode & 0xFFFF;
            }
        }

        /**
         * Inserts a code longer than {@code tablebits} into the left/right tree. Port of the
         * "len > tablebits" branch of LHa's make_table, where a C pointer {@code *p} moves
         * among {@code table}, {@code right} and {@code left}: here it is represented by
         * (slot, isTable, isRight). Returns the new {@code avail}.
         */
        private int buildLong(int[] table, int ch, int len, int k, int tablebits,
                              int jutbits, int mask, int avail) {
            int slot = k >>> jutbits;     // current index in the container pointed to
            boolean inTable = true;       // does the pointer point into 'table'?
            boolean right = false;        // (if not in table) right or left branch?
            int kk = k;
            int i = len - tablebits;
            while (i != 0) {
                int cur = read(table, slot, inTable, right);
                if (cur == 0) {
                    this.right[avail] = this.left[avail] = 0;
                    write(table, slot, inTable, right, avail);
                    cur = avail;
                    avail++;
                }
                slot = cur;
                inTable = false;
                right = (kk & mask) != 0;
                kk <<= 1;
                i--;
            }
            write(table, slot, inTable, right, ch);
            return avail;
        }

        private int read(int[] table, int slot, boolean inTable, boolean right) {
            if (inTable) return table[slot];
            return right ? this.right[slot] : this.left[slot];
        }

        private void write(int[] table, int slot, boolean inTable, boolean right, int val) {
            if (inTable) table[slot] = val;
            else if (right) this.right[slot] = val;
            else this.left[slot] = val;
        }
    }
}
