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
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Estrattore di archivi LHA/LZH (il formato usato dai pacchetti WHDLoad).
 *
 * <p>Supporta le intestazioni di livello 0, 1 e 2 e i metodi di compressione
 * {@code -lh0-}/{@code -lhd-} (stored / directory), {@code -lh4-}, {@code -lh5-},
 * {@code -lh6-} e {@code -lh7-} (LZSS + Huffman dinamico, finestra 4/8/32/64 KB).
 * È l'insieme che copre di fatto tutti gli archivi della collezione WHDLoad.
 *
 * <p>L'algoritmo di decodifica è il classico di Haruhiko Okumura / Haruyasu
 * Yoshizaki (LHa): bit-stream MSB-first, tre alberi di Huffman (lunghezze dei
 * codici, codici letterali/match, posizioni) ricostruiti a ogni blocco.
 *
 * <p>Estrae mantenendo la struttura di directory interna dell'archivio.
 */
public final class LhaExtractor {

    /** Una voce estratta (file regolare o directory). */
    public static final class Entry {
        public final String path;     // percorso relativo con separatore '/'
        public final boolean directory;
        public final long size;
        Entry(String path, boolean directory, long size) {
            this.path = path; this.directory = directory; this.size = size;
        }
    }

    private final byte[] archive;
    private final List<Entry> entries = new ArrayList<>();
    // Mappa percorso-su-disco (codificato) -> nome Amiga originale, per i nomi che
    // Windows non può rappresentare (punti/spazi finali). Vedi {@link #windowsSafeLeaf}.
    private final Map<String, String> nameOverrides = new LinkedHashMap<>();

    /** Nome del file sidecar che trasporta i nomi Amiga originali a {@link FfsBuilder}. */
    static final String AMIGA_NAMES_SIDECAR = ".amiganames";

    private LhaExtractor(byte[] archive) { this.archive = archive; }

    /**
     * Estrae l'archivio {@code lhaPath} dentro la directory {@code destDir},
     * ricreando la struttura interna. Restituisce l'elenco delle voci estratte.
     */
    public static List<Entry> extract(String lhaPath, Path destDir) throws IOException {
        byte[] data = Files.readAllBytes(Paths.get(lhaPath));
        LhaExtractor ex = new LhaExtractor(data);
        ex.run(destDir);
        return ex.entries;
    }

    private void run(Path destDir) throws IOException {
        int off = 0;
        while (off + 1 < archive.length) {
            int headerSize = archive[off] & 0xFF;
            if (headerSize == 0) break;             // marcatore di fine archivio
            Header h = parseHeader(off);
            if (h == null) break;

            String relPath = sanitize(h.name);
            if (h.isDirectory || h.method.equals("-lhd-")) {
                if (!relPath.isEmpty()) {
                    Path d = destDir.resolve(windowsSafePath(relPath));
                    Files.createDirectories(d);
                    entries.add(new Entry(relPath, true, 0));
                }
            } else {
                byte[] out = decode(h);
                Path f = destDir.resolve(windowsSafePath(relPath));
                if (f.getParent() != null) Files.createDirectories(f.getParent());
                Files.write(f, out);
                entries.add(new Entry(relPath, false, out.length));
            }
            off = h.nextOffset;
        }
        writeSidecar(destDir);
    }

    /**
     * Restituisce un percorso scrivibile su disco Windows equivalente a {@code relPath}.
     *
     * <p>Windows elimina silenziosamente i punti e gli spazi finali da ogni componente
     * del nome (es. il file Amiga {@code ruptile.} diventerebbe {@code ruptile}): un
     * gioco che apre {@code ruptile.} non lo troverebbe più. Su AmigaDOS quei caratteri
     * sono invece legali. Per preservarli, ogni carattere finale problematico viene
     * codificato in percent-encoding ({@code .}=%2E, spazio=%20) — innocuo per Windows —
     * e il nome Amiga originale viene registrato in {@link #nameOverrides}, così
     * {@link FfsBuilder} può riscriverlo esatto nel file system FFS.
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

    /** Percent-encode dei soli punti/spazi finali di un componente di nome. */
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
        long compSize;     // dimensione del payload compresso (byte)
        long origSize;     // dimensione originale (byte)
        String name;
        boolean isDirectory;
        int dataStart;     // offset del primo byte di dati compressi
        int nextOffset;    // offset della voce successiva
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
                // Header di base = 2 + headerSize byte; nessuna intestazione estesa.
                h.name = base;
                h.dataStart = off + 2 + headerSize;
                h.nextOffset = h.dataStart + (int) h.compSize;
            } else {
                // Livello 1: la catena di header estesi inizia a off+headerSize, i
                // dati compressi subito dopo. Le directory provengono dagli header
                // estesi di tipo 0x02; il nome di base è il file foglia. compSize è la
                // dimensione del solo payload compresso → next = dataStart + compSize.
                int p = off + headerSize;
                String dir = "";
                String fname = base;
                int ext = le16(p); p += 2;
                while (ext != 0) {
                    int type = archive[p] & 0xFF;
                    int bodyLen = ext - 3;          // ext = tipo(1) + corpo + word(2)
                    if (type == 0x01) {
                        fname = ascii(p + 1, bodyLen);
                    } else if (type == 0x02) {
                        dir = extDir(p + 1, bodyLen);
                    }
                    p += ext - 2;                   // avanza al prossimo word di lunghezza
                    ext = le16(p); p += 2;
                }
                h.dataStart = p;
                // compSize è la "skip size" misurata da dopo la prima word di
                // lunghezza degli header estesi (off+headerSize+2): include i corpi
                // degli header estesi e il payload compresso fino alla voce seguente.
                h.nextOffset = off + headerSize + 2 + (int) h.compSize;
                h.name = dir + fname;
            }
        } else { // livello 2
            int total = le16(off);                  // dimensione totale header (word LE)
            int crcPos = off + 21;                   // crc(2) + os(1) ...
            // Header estesi a partire da off+24.
            String dir = "", fname = "";
            int p = off + 24;
            int ext = le16(off + 22);
            // Per il livello 2 la prima word di lunghezza è a off+22.
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
        return h;
    }

    /** Header esteso 0x02: componenti separate da 0xFF, ritorna "a/b/" con trailing '/'. */
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
        // Le LhA Amiga memorizzano "nome\0filenota" nel campo del nome (la fnlen
        // conta entrambi); il NUL e il commento che segue non fanno parte del
        // percorso e sono caratteri illegali su Windows. Tronca al primo NUL.
        int nul = name.indexOf('\0');
        if (nul >= 0) name = name.substring(0, nul);
        String s = name.replace('\\', '/');
        // Alcuni archivi (livello 0) separano le directory con 0xFF.
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c == 0xFF ? '/' : c);
        }
        s = sb.toString();
        while (s.startsWith("/")) s = s.substring(1);
        return s;
    }

    // =========================================================================
    // Decodifica
    // =========================================================================

    private byte[] decode(Header h) {
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

    // =========================================================================
    // Lettura interi (little-endian) e ASCII
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
        private final int np;        // numero codici di posizione = dicbit + 1
        private final int pbit;      // bit per leggere il conteggio dell'albero posizioni

        // Bit reader MSB-first.
        private int pos;
        private long buf;
        private int bits;

        // Tabelle di Huffman.
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
            int j = cTable[w >>> 4];          // primi 12 bit della finestra a 16 bit
            if (j >= NC) {
                int mask = 1 << 3;            // bit successivi: 1<<(15-12)
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
                int mask = 1 << 7;            // bit successivi: 1<<(15-8)
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
         * Costruisce una tabella di decodifica Huffman a {@code tablebits} bit dalle
         * lunghezze {@code bitlen}; i codici più lunghi di {@code tablebits} usano gli
         * array {@code left}/{@code right} come albero. Porta del make_table di LHa.
         */
        private void makeTable(int nchar, int[] bitlen, int tablebits, int[] table) {
            int[] count = new int[17];
            int[] weight = new int[17];
            int[] start = new int[18];
            for (int i = 1; i <= 16; i++) count[i] = 0;
            for (int i = 0; i < nchar; i++) count[bitlen[i]]++;
            // start[] è a 16 bit (ushort in LHa): l'accumulo va troncato a 0xFFFF a
            // ogni passo per riprodurre il wrap di un unsigned short.
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
                    // Codice più lungo di tablebits: percorso nell'albero left/right.
                    avail = buildLong(table, ch, len, start[len] & 0xFFFF,
                                      tablebits, jutbits, mask, avail);
                }
                start[len] = nextcode & 0xFFFF;
            }
        }

        /**
         * Inserisce un codice più lungo di {@code tablebits} nell'albero left/right.
         * Porta del ramo "len > tablebits" di make_table (LHa), dove un puntatore C
         * {@code *p} naviga fra {@code table}, {@code right} e {@code left}: qui è
         * rappresentato da (slot, isTable, isRight). Ritorna il nuovo {@code avail}.
         */
        private int buildLong(int[] table, int ch, int len, int k, int tablebits,
                              int jutbits, int mask, int avail) {
            int slot = k >>> jutbits;     // indice corrente nel contenitore puntato
            boolean inTable = true;       // il puntatore punta in 'table'?
            boolean right = false;        // (se non in table) ramo destro o sinistro?
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
