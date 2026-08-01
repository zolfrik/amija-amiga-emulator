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

package io.github.zolfrik.amija.memory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Immagine di hard disk Amiga (file {@code .hdf}) montata come "hardfile".
 *
 * <p>Scopo: lanciare giochi installati su .hdf (e in futuro WHDLoad) con un costo
 * di implementazione minimo, privilegiando le prestazioni. Il file viene caricato
 * interamente in un {@code byte[]}; le letture/scritture del driver sono semplici
 * copie di memoria (nessuna emulazione cycle-exact del bus SCSI/IDE). Il driver
 * AmigaOS è fornito dalla {@link io.github.zolfrik.amija.custom.HdController}.
 *
 * <p>Sono riconosciuti due formati:
 * <ul>
 *   <li><b>RDB</b> (Rigid Disk Block): l'immagine contiene un blocco {@code RDSK}
 *       fra i primi 16 settori con geometria e tabella delle partizioni;</li>
 *   <li><b>headerless</b> (single partition): l'intero file è un unico filesystem
 *       AmigaDOS (il blocco 0 inizia con {@code DOS}). La geometria viene
 *       sintetizzata dalla dimensione del file e il {@code DosType} è letto dal
 *       boot block. È il caso di "Castle Master v2.0.hdf" (DOS\1 = FFS).</li>
 * </ul>
 *
 * <p>La logica di parsing replica vAmiga ({@code HDFFile}/{@code DeviceDescriptors}).
 * I filesystem custom referenziati nell'RDB (LSEG) non sono supportati: si assume
 * che le partizioni usino il filesystem in ROM (OFS/FFS), presente dal Kickstart
 * 2.0 in poi.
 */
public final class HardFile {

    private static final int BSIZE = 512;

    // Limiti geometria (cfr. vAmiga Constants.h).
    private static final int C_MIN = 16,  C_MAX = 16384;
    private static final int H_MIN = 1,   H_MAX = 16;
    private static final int S_MIN = 16,  S_MAX = 63;

    /** Descrittore di una partizione (sottoinsieme del DOS environment necessario al boot). */
    public static final class Partition {
        public String name = "DH";
        public int flags;        // bit0 = bootable
        public int sizeBlock;    // longword per blocco (512/4 = 128)
        public int reserved;     // boot block riservati (di norma 2)
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
    // Costruzione
    // =========================================================================

    private HardFile(Path path, byte[] data, boolean readOnly) {
        this.path = path;
        this.data = data;
        this.readOnly = readOnly;
    }

    /**
     * Carica e analizza un file {@code .hdf}.
     *
     * @param filePath percorso del file
     * @param readOnly se {@code true} le scritture sono ignorate
     * @throws IOException se il file non è leggibile o è troppo piccolo
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
            // Non dovrebbe accadere: garantisce sempre almeno una partizione.
            parseHeaderless();
        }
    }

    /** Cerca il blocco {@code RDSK} fra i primi 16 settori; -1 se assente. */
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

        // Lista delle partizioni: PartitionList nel RDB → catena di blocchi PART.
        int partRef = r32(rdb + 28); // rdb_PartitionList
        int guard = 0;
        while (partRef != -1 && partRef >= 0 && (long) (partRef + 1) * BSIZE <= data.length && guard++ < 64) {
            int pb = partRef * BSIZE;
            if (!id4(pb).equals("PART")) break;
            Partition part = new Partition();
            part.name      = readBStr(pb + 36, 31);
            part.flags     = r32(pb + 20);
            // DOS environment: comincia a offset 128 nel blocco PART.
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
        // Il DosType è l'identificatore del boot block (es. "DOS\1"); se l'immagine
        // non è formattata ripiega su OFS ("DOS\0").
        int boot = r32(0);
        part.dosType = id4(0).equals("DOS") ? boot : 0x444F5300;
        partitions.add(part);
    }

    /**
     * Predice il numero di blocchi della partizione di un HDF headerless.
     *
     * <p>Spesso l'immagine ha qualche blocco di padding oltre la fine reale della
     * partizione: la dimensione del file <b>non</b> coincide con i blocchi del
     * filesystem. La verità è la posizione del <i>root block</i> FFS/OFS, che si
     * trova a {@code (reserved + numBlocks - 1) / 2}. Replico l'euristica di vAmiga
     * ({@code HDFFile::predictNumBlocks}): provo dei candidati e accetto il primo il
     * cui root block previsto è effettivamente un root block valido.
     */
    private int predictNumBlocks() {
        int fileBlocks = data.length / BSIZE;
        // Candidato 1: l'intero file. Candidato 2: arrotondato a multiplo di 32
        // (geometria standard a 32 settori/traccia, usata dalla maggior parte degli
        // installer headerless).
        int rounded32 = 32 * (fileBlocks / 32);
        for (int nb : new int[] { fileBlocks, rounded32 }) {
            if (nb > 0 && isRootBlock((2 + nb - 1) / 2)) return nb;
        }
        return fileBlocks;
    }

    /** {@code true} se il blocco {@code n} è un root block FFS/OFS (type=2, sec=ST_ROOT=1). */
    private boolean isRootBlock(int n) {
        long off = (long) n * BSIZE;
        if (n < 0 || off + BSIZE > data.length) return false;
        return r32((int) off) == 2 && r32((int) off + BSIZE - 4) == 1;
    }

    /**
     * Sintetizza una geometria CHS con {@code C*H*S == numBlocks} esatto (requisito
     * perché la partizione copra esattamente i blocchi e il root block cada dove il
     * filesystem lo cerca). Preferisce 32 settori/traccia (standard); altrimenti
     * cerca una qualsiasi fattorizzazione valida; in ultima istanza ripiega su una
     * geometria approssimata.
     */
    private int[] predictGeometry(int numBlocks) {
        // Preferenza: 32 settori/traccia, teste minime così che i cilindri rientrino.
        for (int h = H_MIN; h <= H_MAX; h++) {
            if (numBlocks % (h * 32) == 0) {
                int c = numBlocks / (h * 32);
                if (c >= 1 && c <= C_MAX) return new int[] { c, h, 32 };
            }
        }
        // Generale: qualsiasi settore in [16,63], minor numero di cilindri.
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

        // Fallback: geometria standard a 32 settori (può eccedere numBlocks).
        int s = 32, h = 1;
        int c = (numBlocks + s - 1) / s;
        while (c > 1024) { c = (c + 1) / 2; h *= 2; }
        return new int[] { c, h, s };
    }

    // =========================================================================
    // Accesso ai dati (usato dal driver host)
    // =========================================================================

    /** {@code true} se l'offset/lunghezza ricadono interamente nell'immagine. */
    public boolean inRange(long offset, long length) {
        return offset >= 0 && length >= 0 && offset + length <= data.length;
    }

    /** Byte all'offset assoluto nell'immagine (per la copia verso la RAM Amiga). */
    public int readByte(int offset) {
        return data[offset] & 0xFF;
    }

    /** Scrive un byte all'offset assoluto (no-op se l'immagine è in sola lettura). */
    public void writeByte(int offset, int value) {
        if (readOnly) return;
        data[offset] = (byte) value;
        modified = true;
    }

    /**
     * Array byte grezzo dell'immagine, per i trasferimenti in blocco del driver host
     * ({@code System.arraycopy} invece della copia byte-per-byte, come il {@code mem.patch}
     * di vAmiga e il {@code memcpy} di WinUAE). Usato come sorgente in lettura disco→RAM
     * e come destinazione in scrittura RAM→disco; il chiamante ({@link
     * io.github.zolfrik.amija.custom.HdController}) ha già verificato range e sola lettura.
     */
    public byte[] rawData() { return data; }

    /** Segna l'immagine come modificata dopo una scrittura in blocco su {@link #rawData()}. */
    public void markModified() { if (!readOnly) modified = true; }

    /** Salva su disco le eventuali modifiche (no-op se sola lettura o invariato). */
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
     * Forza la bootabilità di tutte le partizioni (bit 0 di {@code flags}). Usato per
     * marcare come NON avviabile un disco di servizio (es. il volume dei salvataggi
     * WHDLoad), così da non comparire mai nella lista di boot.
     */
    public void setBootable(boolean bootable) {
        for (Partition p : partitions) {
            if (bootable) p.flags |= 1; else p.flags &= ~1;
        }
    }

    /** {@code true} se almeno una partizione ha il bit di boot. */
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
        // Identificatore a 4 byte, troncato al primo NUL (es. "DOS\1" → "DOS").
        StringBuilder sb = new StringBuilder(4);
        for (int i = 0; i < 4; i++) {
            int c = data[off + i] & 0xFF;
            if (c < 0x20 || c > 0x7E) break;
            sb.append((char) c);
        }
        return sb.toString();
    }

    private String readBStr(int off, int max) {
        // BCPL string: lunghezza nel primo byte, poi i caratteri.
        int len = Math.min(data[off] & 0xFF, max);
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append((char) (data[off + 1 + i] & 0xFF));
        return sb.toString();
    }
}
