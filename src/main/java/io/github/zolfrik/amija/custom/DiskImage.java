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

package io.github.zolfrik.amija.custom;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Rappresenta l'immagine di un floppy fisico Amiga.
 *
 * <p>Formati supportati:
 * <ul>
 *   <li><b>ADF</b> – lettura e scrittura. I settori modificati vengono persisti
 *       sul file sorgente in modo selettivo (solo tracce dirty) tramite tre
 *       politiche di flush: periodica (~5 s PAL), all'eject del disco e alla
 *       chiusura della JVM via shutdown hook.</li>
 *   <li><b>IPF</b> – sola lettura. Il bitstream MFM grezzo viene estratto alla
 *       prima apertura e mantenuto in memoria. Per immagini con copy-protection
 *       complessa potrebbe essere necessaria la libreria CAPS/IPFLib esterna.</li>
 * </ul>
 *
 * <p>Questa classe è {@link AutoCloseable}: usarla in un try-with-resources
 * garantisce il flush finale anche in caso di eccezione.
 */
public final class DiskImage implements AutoCloseable {

    // =========================================================================
    // Costanti pubbliche
    // =========================================================================

    public enum Format { ADF, IPF }

    public static final int SECTOR_SIZE          = 512;
    public static final int SECTORS_PER_TRACK_DD = 11;
    public static final int SECTORS_PER_TRACK_HD = 22;
    public static final int CYLINDERS            = 80;
    public static final int SIDES                = 2;
    public static final int PHYSICAL_TRACKS      = CYLINDERS * SIDES;  // 160

    static final int ADF_DD_SIZE = PHYSICAL_TRACKS * SECTORS_PER_TRACK_DD * SECTOR_SIZE; // 901 120
    static final int ADF_HD_SIZE = PHYSICAL_TRACKS * SECTORS_PER_TRACK_HD * SECTOR_SIZE; // 1 802 240

    // Dimensione del buffer MFM per traccia standard — stessa costante di FloppyDrive.
    static final int TRACK_BUFFER_BYTES = 12_668;
    private static final byte[] BLANK_MFM_TRACK = createBlankMfmTrack();

    // Lunghezza massima di una traccia MFM. Le tracce di protezione IPF ("long
    // track") superano la lunghezza standard (es. North &amp; South: ~13244 byte);
    // il buffer della traccia è dimensionato su questo massimo.
    public static final int MAX_TRACK_BYTES = 16_384;

    // Flush periodico ogni ~5 secondi PAL (base clock 7.09379 MHz)
    private static final long FLUSH_INTERVAL_CYCLES = 35_468_950L;

    // =========================================================================
    // Stato
    // =========================================================================

    private final Path    filePath;
    private final Format  format;
    private final boolean readOnly;
    private final int     sectorsPerTrack;

    /** Dati settore flat per ADF: offset = physicalTrack * sectorsPerTrack * SECTOR_SIZE */
    private final byte[]   sectorData;

    /** Buffer MFM pre-parsati per IPF, uno per traccia fisica */
    private final byte[][] ipfMfmTracks;

    /** Densità di cella per-byte delle tracce IPF (1000 = nominale); null = uniforme. */
    private final int[][] ipfTrackDensity;

    /** Lunghezza ESATTA in bit di ogni traccia IPF (le long track non sono multiple
     *  di 8 bit). Usata da FloppyDrive per avvolgere la rotazione al bit esatto. */
    private final int[] ipfTrackBits;

    private final boolean[] dirtyTracks;
    private volatile boolean anyDirty = false;

    // PERF: cache dell'encoding MFM per traccia (solo ADF). L'encoding è
    // deterministico per traccia; si cacha e si invalida solo alla scrittura
    // (markDirty). I buffer di lavoro sono scratch riusabili dell'istanza.
    private byte[][] mfmTrackCache;
    private final int[] adfTrackWordsScratch;
    private final int[] adfSectorMfmScratch;
    private long cyclesSinceLastFlush = 0L;
    private volatile boolean closed = false;
    /**
     * Rete di sicurezza per il flush a processo che muore. È registrato <b>pigramente</b>,
     * alla prima traccia sporca ({@link #markDirty}), non nel costruttore.
     *
     * <p>⛔ Un hook registrato è una radice GC viva per tutta la vita del JVM, e veniva
     * tolto solo da {@link #close()}. Registrandolo per OGNI immagine — comprese quelle
     * di sola lettura e gli IPF, che non possono mai sporcarsi — ogni macchina smontata
     * senza espellere i dischi lasciava dietro fino a quattro hook e con loro l'intera
     * {@code DiskImage}: {@code sectorData} è ~900 KB per ADF, gli {@code ipfMfmTracks}
     * di più. Su una collezione intera (il game sweep boota un'istanza per file) sono
     * centinaia di MB che non tornano indietro, più centinaia di Thread da avviare
     * all'uscita. Pigro, l'hook esiste solo se c'è davvero qualcosa da salvare.
     *
     * <p>Il difetto complementare — nessuno espelleva i dischi — è chiuso in
     * {@code Amija.shutdownMachine()}, che ora chiude i quattro drive.
     */
    private Thread shutdownHook;

    // =========================================================================
    // Factory methods
    // =========================================================================

    /**
     * Apre un'immagine disco dal path indicato.
     *
     * @param path           percorso del file (.adf, .ipf o .zip)
     * @param forceReadOnly  se {@code true} impone la sola lettura anche per ADF
     */
    public static DiskImage open(Path path, boolean forceReadOnly) throws IOException {
        return open(path, forceReadOnly, 0);
    }

    public static DiskImage open(Path path) throws IOException {
        return open(path, false);
    }

    /**
     * Apre un'immagine disco da uno <i>spec</i>: un normale percorso file oppure la
     * forma {@code <archivio>.zip!<n>} che indirizza l'n-esimo disco contenuto nello
     * zip (vedi {@link #zipEntrySpec(String, int)}).
     *
     * <p>Uno zip <b>senza</b> indice non è il suo primo disco ma il suo disco di
     * <b>avvio</b> ({@link #zipDiskOrder}): è quello che si mette in DF0.
     */
    public static DiskImage open(String spec, boolean forceReadOnly) throws IOException {
        Path path = pathOf(spec);
        int index = hasZipEntryIndex(spec) ? zipEntryIndexOf(spec)
                  : isZipDisk(spec)        ? zipBootDiskIndex(path)
                  : 0;
        return open(path, forceReadOnly, index);
    }

    /**
     * @param zipEntryIndex indice (0-based) del disco da estrarre quando {@code path}
     *                      è uno zip; ignorato per .adf/.ipf
     */
    public static DiskImage open(Path path, boolean forceReadOnly, int zipEntryIndex)
            throws IOException {
        String name = path.getFileName().toString().toLowerCase();
        boolean isZip = name.endsWith(".zip");
        ImageBytes image = isZip ? readZippedDiskImage(path, zipEntryIndex)
                                 : new ImageBytes(name, Files.readAllBytes(path));
        byte[] raw = image.raw;
        boolean isIpf = image.name.endsWith(".ipf") || isIpfMagic(raw);
        return new DiskImage(path, isIpf ? Format.IPF : Format.ADF, raw,
                             isZip || isIpf || forceReadOnly);
    }

    // -------------------------------------------------------------------------
    // Spec "<archivio>.zip!<n>": indirizzamento del singolo disco in uno zip
    // -------------------------------------------------------------------------

    /** Separatore fra il percorso dello zip e l'indice del disco al suo interno. */
    public static final char ZIP_ENTRY_SEPARATOR = '!';

    private static final java.util.regex.Pattern ZIP_SPEC =
            java.util.regex.Pattern.compile("(?i)^(.*\\.zip)" + ZIP_ENTRY_SEPARATOR + "(\\d+)$");

    /** Spec che indirizza l'{@code entryIndex}-esimo disco dentro {@code zipPath}. */
    public static String zipEntrySpec(String zipPath, int entryIndex) {
        return zipPath.trim() + ZIP_ENTRY_SEPARATOR + entryIndex;
    }

    /** {@code true} se lo spec punta a uno zip (con o senza indice di disco). */
    public static boolean isZipDisk(String spec) {
        if (spec == null) return false;
        String s = spec.trim().toLowerCase();
        return s.endsWith(".zip") || ZIP_SPEC.matcher(s).matches();
    }

    /** Percorso del file su disco corrispondente allo spec (suffisso {@code !n} rimosso). */
    public static Path pathOf(String spec) {
        String s = spec.trim();
        var m = ZIP_SPEC.matcher(s);
        return java.nio.file.Paths.get(m.matches() ? m.group(1) : s);
    }

    /** {@code true} se lo spec porta il suffisso {@code !n} con l'indice del disco. */
    public static boolean hasZipEntryIndex(String spec) {
        return spec != null && ZIP_SPEC.matcher(spec.trim()).matches();
    }

    /** Indice del disco indirizzato dallo spec; 0 se non specificato. */
    public static int zipEntryIndexOf(String spec) {
        var m = ZIP_SPEC.matcher(spec.trim());
        return m.matches() ? Integer.parseInt(m.group(2)) : 0;
    }

    /**
     * Nomi dei dischi (.adf/.ipf) contenuti in uno zip, nell'ordine in cui vi
     * compaiono: l'indice nella lista è quello usato da {@link #zipEntrySpec}.
     */
    public static java.util.List<String> listZipDiskEntries(Path zipPath) throws IOException {
        var names = new java.util.ArrayList<String>();
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (isDiskEntry(entry)) names.add(entry.getName());
            }
        }
        return names;
    }

    /**
     * Ordine in cui i dischi di uno zip vanno inseriti nei drive: per primo quello
     * <b>avviabile</b> (blocco di boot AmigaDOS valido), poi tutti gli altri
     * nell'ordine in cui compaiono nell'archivio.
     *
     * <p>Serve perché dentro un archivio l'ordine è quello dei nomi, e il disco da
     * cui si parte non è quasi mai il primo: «Data Disk 1..3» precede «Program
     * Disk», «Data Disk» precede «Program Disk», e così via. Chi inserisce i
     * floppy a mano mette in DF0 quello di avvio; questo metodo fa lo stesso.
     *
     * <p>Se nessun disco è avviabile — set di dischi dati, disco addon, gioco che
     * si avvia da un'altra immagine — l'ordine resta quello dell'archivio.
     *
     * @return gli indici dei dischi, lunghezza pari al numero di dischi nello zip
     */
    public static int[] zipDiskOrder(Path zipPath) throws IOException {
        int count = listZipDiskEntries(zipPath).size();
        int boot = 0;
        for (int i = 0; i < count; i++) {
            try (DiskImage image = open(zipPath, true, i)) {
                if (image.isBootable()) { boot = i; break; }
            } catch (IOException | RuntimeException ignored) {
                // Disco illeggibile: non è candidato all'avvio, si prosegue.
            }
        }
        int[] order = new int[count];
        int k = 0;
        if (count > 0) order[k++] = boot;
        for (int i = 0; i < count; i++) if (i != boot) order[k++] = i;
        return order;
    }

    /** Indice del disco avviabile dello zip, 0 se non ce n'è (o se lo zip è illeggibile). */
    public static int zipBootDiskIndex(Path zipPath) {
        try {
            int[] order = zipDiskOrder(zipPath);
            return order.length > 0 ? order[0] : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    private static boolean isIpfMagic(byte[] raw) {
        return raw.length >= 4
            && raw[0] == 'C' && raw[1] == 'A' && raw[2] == 'P' && raw[3] == 'S';
    }

    private record ImageBytes(String name, byte[] raw) {}

    private static boolean isDiskEntry(ZipEntry entry) {
        if (entry.isDirectory()) return false;
        String name = entry.getName().toLowerCase();
        return name.endsWith(".adf") || name.endsWith(".ipf");
    }

    /** Estrae l'{@code wanted}-esimo disco (.adf/.ipf) presente nello zip. */
    private static ImageBytes readZippedDiskImage(Path path, int wanted) throws IOException {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            int index = 0;
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!isDiskEntry(entry)) continue;
                if (index++ != wanted) continue;
                try (var in = zip.getInputStream(entry)) {
                    return new ImageBytes(entry.getName().toLowerCase(), in.readAllBytes());
                }
            }
        }
        throw new IOException("ZIP: nessun file .adf o .ipf all'indice " + wanted
                + " in " + path.getFileName());
    }

    private boolean hasTrackData(int physicalTrack) {
        if (format == Format.ADF) {
            int trackBytes = sectorsPerTrack * SECTOR_SIZE;
            return (long) (physicalTrack + 1) * trackBytes <= sectorData.length;
        }
        return ipfMfmTracks != null && physicalTrack < ipfMfmTracks.length;
    }

    private static byte[] createBlankMfmTrack() {
        byte[] encoded = new byte[TRACK_BUFFER_BYTES];
        Arrays.fill(encoded, (byte) 0xAA);
        return encoded;
    }

    private static byte[] blankMfmTrack() {
        return BLANK_MFM_TRACK;
    }

    // =========================================================================
    // Costruttore (privato)
    // =========================================================================

    private DiskImage(Path filePath, Format format, byte[] raw, boolean readOnly)
            throws IOException {
        this.filePath = filePath;
        this.format   = format;
        this.readOnly = readOnly;

        if (format == Format.ADF) {
            if      (raw.length == ADF_DD_SIZE) sectorsPerTrack = SECTORS_PER_TRACK_DD;
            else if (raw.length == ADF_HD_SIZE) sectorsPerTrack = SECTORS_PER_TRACK_HD;
            else throw new IOException("ADF: dimensione non valida: " + raw.length
                    + " (attesi " + ADF_DD_SIZE + " o " + ADF_HD_SIZE + " byte)");
            sectorData   = raw;
            ipfMfmTracks = null;
            ipfTrackBits = null;
            ipfTrackDensity = null;
            mfmTrackCache = new byte[PHYSICAL_TRACKS][];
            adfTrackWordsScratch = new int[TRACK_BUFFER_BYTES / 2];
            adfSectorMfmScratch = new int[545];
        } else {
            sectorsPerTrack = SECTORS_PER_TRACK_DD;
            sectorData      = null;
            IpfTracks t     = parseIpf(raw);
            ipfMfmTracks    = t.mfm;
            ipfTrackBits    = t.bits;
            ipfTrackDensity = t.density;
            mfmTrackCache = null;
            adfTrackWordsScratch = null;
            adfSectorMfmScratch = null;
        }

        dirtyTracks = new boolean[PHYSICAL_TRACKS];
        // Lo shutdown hook NON si registra qui: vedi il campo shutdownHook.
    }

    // =========================================================================
    // API per FloppyDrive — lettura
    // =========================================================================

    /**
     * Restituisce {@value #TRACK_BUFFER_BYTES} byte di dati MFM per la traccia
     * fisica indicata, pronti per essere caricati nel buffer di FloppyDrive.
     *
     * <p>Per ADF l'encoding MFM viene eseguito on-demand ad ogni chiamata.
     * Per IPF i dati sono già stati estratti all'apertura del file.
     *
     * @param physicalTrack traccia fisica = cylinder × 2 + side  (0–159)
     */
    public byte[] readTrackMFM(int physicalTrack) {
        checkNotClosed();
        if (physicalTrack < 0) {
            throw new IllegalArgumentException(
                    "physicalTrack fuori range: " + physicalTrack
                    + " (atteso >= 0)");
        }
        if (physicalTrack >= PHYSICAL_TRACKS || !hasTrackData(physicalTrack)) {
            return blankMfmTrack();
        }
        if (format != Format.ADF) return copyIpfMFM(physicalTrack);

        // ADF: restituisci l'encoding MFM cachato (il chiamante loadTrackData ne fa
        // una copia in trackBuffer, quindi è sicuro condividere l'istanza cachata).
        byte[] cached = mfmTrackCache[physicalTrack];
        if (cached == null) {
            cached = new byte[TRACK_BUFFER_BYTES];
            encodeAdfToMFM(physicalTrack, cached);
            mfmTrackCache[physicalTrack] = cached;
        }
        return cached;
    }

    /**
     * Lunghezza in bit della traccia (per l'avvolgimento esatto della rotazione).
     * Per ADF e tracce IPF vuote è la lunghezza standard ({@code byte × 8}); per le
     * tracce IPF è il valore esatto {@code trkbits} (anche non multiplo di 8).
     */
    /**
     * Mappa di densità della traccia: una voce per byte, 1000 = durata di cella
     * nominale. {@code null} quando la densità è uniforme (caso normale, incluso
     * ogni ADF): in quel caso il chiamante usa il periodo di cella costante.
     */
    public int[] getTrackDensity(int physicalTrack) {
        if (format != Format.IPF || ipfTrackDensity == null
                || physicalTrack < 0 || physicalTrack >= PHYSICAL_TRACKS) return null;
        return ipfTrackDensity[physicalTrack];
    }

    /**
     * The cell of an AmigaDOS track where a drive reading it slips its jitter cells (see
     * {@code FloppyDrive}), or -1 for a track with none. It lies in the gap before sector 0,
     * at {@code gapWords * 8 / 3 * 2}. An IPF track has none.
     */
    public int getJitterSkipCell(int physicalTrack) {
        if (format != Format.ADF) return -1;
        int gapWords = TRACK_BUFFER_BYTES / 2 - sectorsPerTrack * 544;
        return gapWords > 0 ? (gapWords * 8) / 3 * 2 : -1;
    }

    public int getTrackBits(int physicalTrack) {
        if (physicalTrack < 0 || physicalTrack >= PHYSICAL_TRACKS || !hasTrackData(physicalTrack))
            return TRACK_BUFFER_BYTES * 8;
        if (format == Format.ADF || ipfTrackBits == null || ipfTrackBits[physicalTrack] <= 0)
            return TRACK_BUFFER_BYTES * 8;
        return ipfTrackBits[physicalTrack];
    }

    /**
     * Restituisce {@value #SECTOR_SIZE} byte di dati raw per il settore indicato.
     * Disponibile solo per immagini ADF.
     */
    public byte[] readSector(int physicalTrack, int sectorIndex) {
        byte[] out = new byte[SECTOR_SIZE];
        readSector(physicalTrack, sectorIndex, out, 0);
        return out;
    }

    public void readSector(int physicalTrack, int sectorIndex, byte[] out, int off) {
        checkNotClosed();
        if (format != Format.ADF)
            throw new UnsupportedOperationException("readSector non disponibile per IPF");
        validateAddress(physicalTrack, sectorIndex);
        if (out == null) throw new NullPointerException("out");
        if (off < 0 || off + SECTOR_SIZE > out.length)
            throw new IndexOutOfBoundsException("off=" + off + " len=" + out.length);
        int offset = (physicalTrack * sectorsPerTrack + sectorIndex) * SECTOR_SIZE;
        System.arraycopy(sectorData, offset, out, off, SECTOR_SIZE);
    }

    // =========================================================================
    // API per FloppyDrive — scrittura (solo ADF non write-protected)
    // =========================================================================

    /**
     * Scrive {@value #SECTOR_SIZE} byte di dati raw nel settore indicato.
     * La traccia viene marcata dirty per il successivo flush.
     */
    public void writeSector(int physicalTrack, int sectorIndex, byte[] data) {
        checkWritable();
        validateAddress(physicalTrack, sectorIndex);
        if (data.length != SECTOR_SIZE)
            throw new IllegalArgumentException("writeSector: attesi " + SECTOR_SIZE + " byte");
        int offset = (physicalTrack * sectorsPerTrack + sectorIndex) * SECTOR_SIZE;
        System.arraycopy(data, 0, sectorData, offset, SECTOR_SIZE);
        markDirty(physicalTrack);
    }

    /**
     * Scrive tutti i settori di una traccia fisica (sectorsPerTrack × 512 byte).
     * Chiamato da FloppyDrive al termine di un trasferimento DMA in scrittura.
     */
    public void writeTrack(int physicalTrack, byte[] sectors) {
        checkWritable();
        validateTrack(physicalTrack);
        int expected = sectorsPerTrack * SECTOR_SIZE;
        if (sectors.length != expected)
            throw new IllegalArgumentException("writeTrack: attesi " + expected + " byte");
        System.arraycopy(sectors, 0, sectorData, physicalTrack * expected, expected);
        markDirty(physicalTrack);
    }

    /**
     * Riversa nell'immagine una traccia MFM incisa dal drive, decodificandone i
     * settori in formato AmigaDOS (l'inverso esatto di {@link #encodeAdfToMFM}).
     *
     * <p>Il bitstream è trattato come CIRCOLARE: la scrittura parte dalla posizione
     * angolare in cui si trovava la testina, quindi un settore può attraversare il
     * punto di avvolgimento della traccia. La scansione cerca le word di sync
     * {@code $4489} bit per bit — i dati incisi non sono allineati al byte del
     * buffer — e accetta un settore solo se il checksum di header E quello dei dati
     * tornano, così le celle rimaste della traccia precedente (gap non riscritti)
     * non possono corrompere i settori.
     *
     * <p>Come WinUAE ({@code drive_write_adf_amigados}) sono supportati i soli
     * settori AmigaDOS standard: un formato custom non è rappresentabile in un ADF
     * e viene silenziosamente ignorato.
     *
     * @param lenBytes lunghezza utile della traccia nel buffer
     * @return numero di settori effettivamente decodificati e scritti
     */
    public synchronized int writeTrackMFM(int physicalTrack, byte[] mfm, int lenBytes) {
        if (closed || readOnly || format != Format.ADF || mfm == null) return 0;
        if (physicalTrack < 0 || physicalTrack >= PHYSICAL_TRACKS
                || !hasTrackData(physicalTrack)) return 0;

        int totalBits = Math.min(lenBytes, mfm.length) * 8;
        if (totalBits < SECTOR_MFM_WORDS * 16) return 0;

        byte[] sector = new byte[SECTOR_SIZE];
        boolean[] seen = new boolean[sectorsPerTrack];
        int written = 0;

        // Scansione con shift register a 16 bit: un giro completo più la finestra
        // iniziale, così anche una sync a cavallo dell'avvolgimento viene trovata.
        int shift = 0;
        for (int i = 0; i < totalBits + 15; i++) {
            shift = ((shift << 1) | mfmBit(mfm, totalBits, i)) & 0xFFFF;
            if (i < 15 || shift != MFM_SYNC) continue;

            // Inizio dell'header: subito dopo l'ultima sync consecutiva
            // (il formato standard ne scrive due).
            int pos = i + 1;
            for (int extra = 0; extra < 4 && mfmWord(mfm, totalBits, pos) == MFM_SYNC; extra++) {
                pos += 16;
            }

            int num = decodeSectorMFM(physicalTrack, mfm, totalBits, pos, sector);
            if (num < 0) continue;
            if (!seen[num]) {
                seen[num] = true;
                System.arraycopy(sector, 0, sectorData,
                                 (physicalTrack * sectorsPerTrack + num) * SECTOR_SIZE,
                                 SECTOR_SIZE);
                written++;
            }
            // Salta oltre il settore appena decodificato (−1: il for incrementa).
            i = pos + SECTOR_MFM_WORDS * 16 - 1;
            shift = 0;
        }

        if (written > 0) markDirty(physicalTrack);
        return written;
    }

    /** Word di sincronizzazione MFM standard AmigaDOS. */
    private static final int MFM_SYNC = 0x4489;
    /** Word MFM di un settore dopo la sync: info(4) + label(16) + chk(8) + dati(512). */
    private static final int SECTOR_MFM_WORDS = 540;

    /**
     * Decodifica un settore la cui area dati inizia alla posizione {@code pos}
     * (prima word dell'header, subito dopo le sync).
     *
     * @return numero del settore, oppure −1 se non è un settore valido per questa
     *         traccia (formato, numero di traccia o checksum non conformi)
     */
    private int decodeSectorMFM(int physicalTrack, byte[] mfm, int totalBits, int pos,
                                byte[] out) {
        // Word 0-3: info = format(FF) | track | sector | settori fino a fine traccia
        int info = mfmOddEvenLong(mfm, totalBits, pos, pos + 2 * 16);
        if (((info >>> 24) & 0xFF) != 0xFF)             return -1;
        if (((info >>> 16) & 0xFF) != physicalTrack)    return -1;
        int num = (info >>> 8) & 0xFF;
        if (num >= sectorsPerTrack)                     return -1;

        // Word 0-19 (info + label) → checksum di header nelle word 20-23.
        int headerChecksum = 0;
        for (int w = 0; w < 20; w += 2) {
            headerChecksum ^= (mfmDataWord(mfm, totalBits, pos + w * 16) << 16)
                            |  mfmDataWord(mfm, totalBits, pos + (w + 1) * 16);
        }
        if (headerChecksum != mfmOddEvenLong(mfm, totalBits, pos + 20 * 16, pos + 22 * 16))
            return -1;

        // Word 28-539 (dati) → checksum dei dati nelle word 24-27.
        int dataChecksum = 0;
        for (int w = 28; w < SECTOR_MFM_WORDS; w += 2) {
            dataChecksum ^= (mfmDataWord(mfm, totalBits, pos + w * 16) << 16)
                          |  mfmDataWord(mfm, totalBits, pos + (w + 1) * 16);
        }
        if (dataChecksum != mfmOddEvenLong(mfm, totalBits, pos + 24 * 16, pos + 26 * 16))
            return -1;

        // 128 long: metà odd nelle word 28-283, metà even nelle word 284-539.
        for (int i = 0; i < SECTOR_SIZE / 4; i++) {
            int value = mfmOddEvenLong(mfm, totalBits,
                                       pos + (28 + 2 * i) * 16,
                                       pos + (284 + 2 * i) * 16);
            out[i * 4]     = (byte) (value >>> 24);
            out[i * 4 + 1] = (byte) (value >>> 16);
            out[i * 4 + 2] = (byte) (value >>> 8);
            out[i * 4 + 3] = (byte)  value;
        }
        return num;
    }

    /**
     * Blocco di boot: i settori 0 e 1 della traccia 0, decodificati dal MFM come
     * fa il drive. Vale per ogni formato, perché parte dal bitstream.
     *
     * @return 1024 byte, oppure {@code null} se uno dei due settori non c'è
     */
    public byte[] readBootBlock() {
        byte[] mfm = readTrackMFM(0);
        int bits = getTrackBits(0);
        if (bits <= 0) bits = mfm.length * 8;
        byte[] boot = new byte[2 * SECTOR_SIZE];
        byte[] sector = new byte[SECTOR_SIZE];
        boolean[] found = new boolean[2];
        for (int pos = 0; pos < bits; pos++) {
            if (mfmWord(mfm, bits, pos) != MFM_SYNC) continue;
            int start = pos;
            while (mfmWord(mfm, bits, start) == MFM_SYNC) start += 16;
            int num = decodeSectorMFM(0, mfm, bits, start, sector);
            if (num < 0) continue;
            if (num < 2 && !found[num]) {
                System.arraycopy(sector, 0, boot, num * SECTOR_SIZE, SECTOR_SIZE);
                found[num] = true;
                if (found[0] && found[1]) return boot;
            }
            pos = start + SECTOR_MFM_WORDS * 16 - 1;
        }
        return found[0] && found[1] ? boot : null;
    }

    /**
     * {@code true} se il disco si avvia: firma {@code DOS} e checksum del blocco
     * di boot valido, gli stessi due controlli che fa lo strap del Kickstart. Un
     * disco formattato ma senza {@code install}, o riempito dal duplicatore con
     * un motivo di filler, non li supera — ed è per quello che il Kickstart
     * continua a chiedere un disco.
     */
    public boolean isBootable() {
        byte[] boot = readBootBlock();
        if (boot == null) return false;
        if (boot[0] != 'D' || boot[1] != 'O' || boot[2] != 'S') return false;
        long sum = 0;
        for (int i = 0; i < boot.length; i += 4) {
            sum += ((boot[i]     & 0xFFL) << 24) | ((boot[i + 1] & 0xFFL) << 16)
                 | ((boot[i + 2] & 0xFFL) << 8)  |  (boot[i + 3] & 0xFFL);
            if (sum > 0xFFFFFFFFL) sum = (sum & 0xFFFFFFFFL) + 1; // riporto di fine giro
        }
        return sum == 0xFFFFFFFFL;
    }

    /** Cella MFM alla posizione indicata; la traccia è circolare. */
    private static int mfmBit(byte[] mfm, int totalBits, int bitPos) {
        int p = bitPos % totalBits;
        return (mfm[p >> 3] >> (7 - (p & 7))) & 1;
    }

    /** 16 celle MFM a partire dalla posizione indicata (MSB first). */
    private static int mfmWord(byte[] mfm, int totalBits, int bitPos) {
        int word = 0;
        for (int i = 0; i < 16; i++) word = (word << 1) | mfmBit(mfm, totalBits, bitPos + i);
        return word;
    }

    /** Come {@link #mfmWord} ma senza i bit di clock: restano i soli bit dati. */
    private static int mfmDataWord(byte[] mfm, int totalBits, int bitPos) {
        return mfmWord(mfm, totalBits, bitPos) & 0x5555;
    }

    /**
     * Ricompone un long dalle due metà odd/even di {@link #putOddEvenLong}
     * (2 word ciascuna): {@code (odd << 1) | even} sui soli bit dati.
     */
    private static int mfmOddEvenLong(byte[] mfm, int totalBits, int oddBit, int evenBit) {
        int odd  = (mfmDataWord(mfm, totalBits, oddBit) << 16)
                 |  mfmDataWord(mfm, totalBits, oddBit + 16);
        int even = (mfmDataWord(mfm, totalBits, evenBit) << 16)
                 |  mfmDataWord(mfm, totalBits, evenBit + 16);
        return (odd << 1) | even;
    }

    // =========================================================================
    // Flush / Close
    // =========================================================================

    /**
     * Deve essere chiamato ad ogni tick di FloppyDrive passando il numero di
     * cicli di clock trascorsi. Esegue automaticamente un flush periodico delle
     * tracce dirty ogni {@value #FLUSH_INTERVAL_CYCLES} cicli (~5 s PAL).
     */
    public void tickCycles(long cycles) {
        if (!anyDirty || readOnly) return;
        cyclesSinceLastFlush += cycles;
        if (cyclesSinceLastFlush >= FLUSH_INTERVAL_CYCLES) {
            cyclesSinceLastFlush = 0L;
            flushQuietly();
        }
    }

    /**
     * Scrive su disco solo le tracce dirty tramite accesso random al file,
     * senza riscrivere l'intera immagine. Operazione idempotente.
     */
    public synchronized void flush() throws IOException {
        if (!anyDirty || readOnly || format != Format.ADF || closed) return;
        try (RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "rw")) {
            for (int t = 0; t < PHYSICAL_TRACKS; t++) {
                if (!dirtyTracks[t]) continue;
                long fileOffset = (long) t * sectorsPerTrack * SECTOR_SIZE;
                raf.seek(fileOffset);
                raf.write(sectorData, (int) fileOffset, sectorsPerTrack * SECTOR_SIZE);
                dirtyTracks[t] = false;
            }
        }
        anyDirty = false;
    }

    /**
     * Flush finale e rilascio risorse. Da chiamare esplicitamente all'eject
     * o alla chiusura dell'emulatore. Lo shutdown hook garantisce il flush
     * anche se questo metodo non viene invocato.
     */
    @Override
    public synchronized void close() throws IOException {
        if (!closed) {
            // Il flush DEVE precedere closed=true: flush() esce subito su immagine
            // chiusa, quindi invertendo l'ordine l'ultimo flush (eject, chiusura
            // dell'emulatore, shutdown hook) veniva silenziosamente saltato e le
            // tracce dirty non ancora scaricate dal flush periodico andavano perse.
            flush();
            closed = true;
            if (shutdownHook != null) {
                try { Runtime.getRuntime().removeShutdownHook(shutdownHook); }
                catch (IllegalStateException ignored) {} // JVM già in shutdown
                shutdownHook = null;
            }
        }
    }

    private void flushQuietly() {
        try { flush(); } catch (IOException ignored) {}
    }

    private synchronized void markDirty(int physicalTrack) {
        dirtyTracks[physicalTrack] = true;
        anyDirty = true;
        // Invalida l'encoding MFM cachato: i dati del settore sono cambiati.
        if (mfmTrackCache != null) mfmTrackCache[physicalTrack] = null;
        armShutdownHook();
    }

    /**
     * Registra la rete di sicurezza del flush alla prima traccia sporca. Idempotente;
     * non fa nulla su immagine di sola lettura, non-ADF o già chiusa — lì non c'è
     * niente da salvare. Vedi il campo {@link #shutdownHook}.
     */
    private void armShutdownHook() {
        if (shutdownHook != null || closed || readOnly || format != Format.ADF) return;
        Thread hook = new Thread(this::flushQuietly,
                                 "DiskImage-flush-" + filePath.getFileName());
        try {
            Runtime.getRuntime().addShutdownHook(hook);
            shutdownHook = hook;
        } catch (IllegalStateException ignored) {
            // JVM già in shutdown: il flush finale lo fa comunque close(), e se non
            // arriva non c'è più nessuno che possa registrare alcunché.
        }
    }

    // =========================================================================
    // Proprietà
    // =========================================================================

    public Format  getFormat()          { return format; }
    public boolean isReadOnly()         { return readOnly; }
    public boolean isDirty()            { return anyDirty; }
    public int     getSectorsPerTrack() { return sectorsPerTrack; }
    public Path    getFilePath()        { return filePath; }

    // =========================================================================
    // Validazione
    // =========================================================================

    private void checkNotClosed() {
        if (closed) throw new IllegalStateException("DiskImage è stata chiusa");
    }

    private void checkWritable() {
        checkNotClosed();
        if (readOnly) throw new IllegalStateException(
                "DiskImage in sola lettura (formato " + format + ")");
    }

    private void validateTrack(int physicalTrack) {
        if (physicalTrack < 0 || physicalTrack >= PHYSICAL_TRACKS)
            throw new IllegalArgumentException(
                    "physicalTrack fuori range: " + physicalTrack
                    + " (0–" + (PHYSICAL_TRACKS - 1) + ")");
    }

    private void validateAddress(int physicalTrack, int sectorIndex) {
        validateTrack(physicalTrack);
        if (sectorIndex < 0 || sectorIndex >= sectorsPerTrack)
            throw new IllegalArgumentException(
                    "sectorIndex fuori range: " + sectorIndex
                    + " (0–" + (sectorsPerTrack - 1) + ")");
    }

    // =========================================================================
    // ADF → MFM encoding
    // =========================================================================

    private synchronized void encodeAdfToMFM(int physicalTrack, byte[] encoded) {
        int totalWords = adfTrackWordsScratch.length;
        int[] words = adfTrackWordsScratch;
        Arrays.fill(words, 0xAAAA);

        int wordPos = totalWords - (sectorsPerTrack * 544);
        int base = physicalTrack * sectorsPerTrack * SECTOR_SIZE;
        int prevBit = 0;

        for (int s = 0; s < sectorsPerTrack; s++) {
            prevBit = encodeSector(physicalTrack, s, base + s * SECTOR_SIZE, words, wordPos, prevBit);
            wordPos += 544;
        }
        // The last sector ends at the end of the track, so the gap word it runs into is
        // word 0: its first clock bit follows the sector's last data bit, like the word
        // before every other sector.
        if (sectorsPerTrack > 0) {
            words[0] = prevBit != 0 ? 0x2AAA : 0xAAAA;
        }

        for (int i = 0; i < words.length; i++) {
            encoded[i * 2] = (byte) ((words[i] >>> 8) & 0xFF);
            encoded[i * 2 + 1] = (byte) (words[i] & 0xFF);
        }
    }

    private int encodeSector(int physicalTrack, int sectorIdx, int dataOffset,
                             int[] dest, int wordPos, int prevBit) {
        int[] mfm = adfSectorMfmScratch;
        int headerChecksum = 0;
        int dataChecksum = 0;

        mfm[0] = prevBit != 0 ? 0x2AAA : 0xAAAA;
        mfm[1] = 0xAAAA;
        mfm[2] = 0x4489;
        mfm[3] = 0x4489;

        int id = (0xFF << 24)
                | ((physicalTrack & 0xFF) << 16)
                | ((sectorIdx & 0xFF) << 8)
                | (sectorsPerTrack - sectorIdx);
        putOddEvenLong(mfm, 4, 6, id);

        for (int i = 0; i < 16; i += 4) {
            putOddEvenLong(mfm, 8 + (i >> 1), 16 + (i >> 1), 0);
        }

        for (int i = 4; i < 24; i += 2) {
            headerChecksum ^= (mfm[i] << 16) | mfm[i + 1];
        }
        putOddEvenLong(mfm, 24, 26, headerChecksum);

        for (int i = 0; i < SECTOR_SIZE; i += 4) {
            int p = dataOffset + i;
            int value = ((sectorData[p] & 0xFF) << 24)
                    | ((sectorData[p + 1] & 0xFF) << 16)
                    | ((sectorData[p + 2] & 0xFF) << 8)
                    | (sectorData[p + 3] & 0xFF);
            putOddEvenLong(mfm, 32 + (i >> 1), 288 + (i >> 1), value);
        }

        for (int i = 32; i < 544; i += 2) {
            dataChecksum ^= (mfm[i] << 16) | mfm[i + 1];
        }
        putOddEvenLong(mfm, 28, 30, dataChecksum);

        mfm[544] = 0;
        mfmCode(mfm, 4, 541);
        for (int i = 0; i < 544; i++) {
            dest[wordPos + i] = mfm[i] & 0xFFFF;
        }
        return mfm[543] & 1;
    }

    private static void putOddEvenLong(int[] words, int oddIndex, int evenIndex, int value) {
        int even = value & 0x55555555;
        int odd = (value >>> 1) & 0x55555555;
        words[oddIndex] = (odd >>> 16) & 0xFFFF;
        words[oddIndex + 1] = odd & 0xFFFF;
        words[evenIndex] = (even >>> 16) & 0xFFFF;
        words[evenIndex + 1] = even & 0xFFFF;
    }

    private static void mfmCode(int[] words, int start, int count) {
        int lastWord = 0;
        for (int i = 0; i < count; i++) {
            int idx = start + i;
            int value = words[idx] & 0x5555;
            int longValue = (lastWord << 16) | value;
            int noClock = 0x55555555 & ~longValue;
            int clockBits = (noClock << 1) & (noClock >>> 1);
            words[idx] = (value | clockBits) & 0xFFFF;
            lastWord = value;
        }
    }

    // =========================================================================
    // IPF parser
    // =========================================================================

    // Dimensione di un Block Descriptor (8 × uint32) nell'area dati di un DATA chunk.
    private static final int IPF_BLOCK_DESC_SIZE = 32;

    /**
     * Parser del formato IPF (SPS/CAPS, encoder CAPS) — sola lettura.
     *
     * <p>Struttura del file: una sequenza di chunk, ognuno con header di 12 byte
     * (tag[4] + length[4] + crc[4], big-endian), dove {@code length} include
     * l'header. I chunk rilevanti:
     * <ul>
     *   <li><b>CAPS</b>/<b>INFO</b> — magic e metadati;</li>
     *   <li><b>IMGE</b> — un descrittore per ogni traccia: cilindro, testina,
     *       numero di blocchi (settori) e l'id {@code dat_chunk} che la collega
     *       al proprio DATA;</li>
     *   <li><b>DATA</b> — i dati codificati di una traccia. La {@code length}
     *       del chunk copre SOLO il descrittore (16 byte: size, bsize, dcrc,
     *       dat_chunk); i {@code size} byte dell'<i>area dati</i> seguono il
     *       chunk e NON sono inclusi nella sua length.</li>
     * </ul>
     *
     * <p>Tutti gli IMGE precedono i DATA. L'area dati di un DATA contiene
     * {@code blockCount} Block Descriptor da {@value #IPF_BLOCK_DESC_SIZE} byte;
     * il campo {@code dataOffset} (8° uint32) punta, relativamente all'inizio
     * dell'area, allo "stream" del blocco. Lo stream è una sequenza di elementi
     * (sync/data/gap/raw/fuzzy) che vengono assemblati in MFM da
     * {@link #decodeBlockStream}.
     */
    /** Tracce IPF decodificate: bitstream MFM + lunghezza esatta in bit. */
    private static final class IpfTracks {
        final byte[][] mfm  = new byte[PHYSICAL_TRACKS][];
        final int[]    bits = new int[PHYSICAL_TRACKS];
        /** Densità per-byte (1000 = nominale); null = densità uniforme. */
        final int[][]  density = new int[PHYSICAL_TRACKS][];
    }

    private static IpfTracks parseIpf(byte[] raw) throws IOException {
        if (!isIpfMagic(raw))
            throw new IOException("IPF: magic 'CAPS' non trovato");

        IpfTracks out = new IpfTracks();
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);

        // 1ª passata: raccogli gli IMGE indicizzati per dat_chunk (precedono i DATA).
        java.util.Map<Integer, IpfImge> imgeByDat = new java.util.HashMap<>();
        int pos = 0;
        while (pos + 12 <= raw.length) {
            String tag = new String(raw, pos, 4, StandardCharsets.ISO_8859_1);
            int size = buf.getInt(pos + 4);
            if (size < 12 || pos + size > raw.length) break;
            int body = pos + 12;
            int next = pos + size;
            if ("IMGE".equals(tag) && size - 12 >= 56) {
                IpfImge im   = new IpfImge();
                im.cylinder  = buf.getInt(body);
                im.head      = buf.getInt(body + 4);
                im.denType   = buf.getInt(body + 8);  // tipo di densità (enum cpden*)
                im.trkSize   = buf.getInt(body + 16); // dimensione traccia in byte
                im.startBit  = buf.getInt(body + 24); // posizione di partenza (bit)
                im.trackBits = buf.getInt(body + 36); // lunghezza traccia (bit)
                im.blockCnt  = buf.getInt(body + 40);
                im.datChunk  = buf.getInt(body + 52);
                imgeByDat.put(im.datChunk, im);
            } else if ("DATA".equals(tag) && size - 12 >= 16) {
                // L'area dati segue il chunk e NON è conteggiata in size.
                next += buf.getInt(body); // body+0 = size (byte) dell'area dati
            }
            pos = next;
        }

        // 2ª passata: decodifica ogni DATA verso la traccia del proprio IMGE.
        pos = 0;
        while (pos + 12 <= raw.length) {
            String tag = new String(raw, pos, 4, StandardCharsets.ISO_8859_1);
            int size = buf.getInt(pos + 4);
            if (size < 12 || pos + size > raw.length) break;
            int body = pos + 12;
            int next = pos + size;
            if ("DATA".equals(tag) && size - 12 >= 16) {
                int areaSize  = buf.getInt(body);
                int datChunk  = buf.getInt(body + 12);
                int area      = body + 16; // inizio area dati (dopo il descrittore)
                next += areaSize;
                IpfImge im = imgeByDat.get(datChunk);
                if (im != null && area + areaSize <= raw.length
                        && im.trackBits > 0 && im.trackBits <= MAX_TRACK_BYTES * 8) {
                    int physTrack = im.cylinder * 2 + im.head;
                    if (physTrack >= 0 && physTrack < PHYSICAL_TRACKS) {
                        out.mfm[physTrack]     = decodeIpfTrack(raw, buf, area, im);
                        out.bits[physTrack]    = im.trackBits;
                        out.density[physTrack] = buildIpfDensity(raw, buf, area, im);
                    }
                }
            }
            pos = next;
        }

        // Tracce senza dati → buffer MFM "vuoto" (clock pattern 0xAA).
        for (int t = 0; t < PHYSICAL_TRACKS; t++)
            if (out.mfm[t] == null) {
                out.mfm[t]  = blankMfmTrack();
                out.bits[t] = TRACK_BUFFER_BYTES * 8;
            }

        return out;
    }

    /**
     * Assembla una traccia a livello di BIT (come CAPSImg CCapsImageStd::ProcessImage):
     * i blocchi sono scritti a partire da {@code startbit} avvolgendosi su esattamente
     * {@code trkbits} bit (le long track di protezione non sono multiple di 8 bit).
     * Ogni blocco produce {@code blockbits} celle (dallo stream sync/data) seguite da
     * {@code gapbits} celle di gap; all'ultimo blocco si aggiunge la differenza fra la
     * lunghezza della traccia e la somma dei blocchi (track gap).
     */
    private static byte[] decodeIpfTrack(byte[] raw, ByteBuffer buf, int area, IpfImge im) {
        TrackBitBuf w = new TrackBitBuf(im.trackBits);

        // Somma blockbits+gapbits di tutti i blocchi (dsctrackbc) per il track gap.
        int sum = 0;
        for (int b = 0; b < im.blockCnt; b++) {
            int d = area + b * IPF_BLOCK_DESC_SIZE;
            if (d + IPF_BLOCK_DESC_SIZE > raw.length) break;
            sum += buf.getInt(d) + buf.getInt(d + 4); // blockbits + gapbits
        }
        int trackDiff = im.trackBits - sum;

        w.seek(im.startBit);
        for (int b = 0; b < im.blockCnt; b++) {
            int d = area + b * IPF_BLOCK_DESC_SIZE;
            if (d + IPF_BLOCK_DESC_SIZE > raw.length) break;
            int gapBits    = buf.getInt(d + 4);
            int dataOffset = buf.getInt(d + 28); // 8° uint32 = offset stream nel data area
            if (b == im.blockCnt - 1 && trackDiff > 0) gapBits += trackDiff;

            decodeBlockStream(raw, area + dataOffset, w); // celle blockbits (sync + dati)
            w.fillGap(gapBits);                            // celle di gap (MFM di zeri)
        }
        return w.buf();
    }

    /**
     * Decodifica lo stream di un blocco scrivendone le celle MFM nel buffer di traccia.
     * Header elemento: 5 bit bassi = tipo (0=fine, 1=sync, 2=data, 3=gap, 4=raw,
     * 5=fuzzy), 3 bit alti = numero di byte del campo count che segue; poi {@code count}
     * byte di payload. Sync/raw sono già celle MFM; data/gap/fuzzy sono dati decodificati
     * e vengono codificati in MFM.
     */
    private static void decodeBlockStream(byte[] raw, int pos, TrackBitBuf w) {
        while (pos < raw.length) {
            int head = raw[pos++] & 0xFF;
            if (head == 0) break; // fine stream
            int type = head & 0x1F;
            int sw   = (head >> 5) & 0x07;
            int count = 0;
            for (int k = 0; k < sw && pos < raw.length; k++)
                count = (count << 8) | (raw[pos++] & 0xFF);
            if (count < 0 || pos + count > raw.length) count = Math.max(0, raw.length - pos);
            switch (type) {
                case 1, 4 -> w.writeRaw(raw, pos, count);   // sync / raw → celle MFM verbatim
                case 2, 3, 5 -> w.writeMfm(raw, pos, count); // data / gap / fuzzy → MFM
                default -> { /* 0 gestito sopra; tipi ignoti: salta */ }
            }
            pos += count;
        }
    }

    /**
     * Scrittore a livello di bit nel buffer di traccia, con avvolgimento esatto a
     * {@code trackBits} bit. Mantiene l'ultimo data bit per i clock bit MFM.
     */
    private static final class TrackBitBuf {
        private final byte[] buf;
        private final int trackBits;
        private int pos = 0;
        private int prevData = 0;

        TrackBitBuf(int trackBits) {
            this.trackBits = Math.max(1, trackBits);
            this.buf = new byte[(this.trackBits + 7) / 8];
        }
        byte[] buf() { return buf; }
        void seek(int bitPos) { pos = ((bitPos % trackBits) + trackBits) % trackBits; }

        private void cell(int bit) {
            int mask = 0x80 >> (pos & 7);
            if (bit != 0) buf[pos >> 3] |= (byte) mask;
            else          buf[pos >> 3] &= (byte) ~mask;
            if (++pos >= trackBits) pos = 0;
        }

        /** sync/raw: i byte sono già celle MFM, copiate bit per bit (MSB first). */
        void writeRaw(byte[] src, int off, int len) {
            for (int i = 0; i < len; i++) {
                int b = src[off + i] & 0xFF;
                for (int k = 7; k >= 0; k--) cell((b >> k) & 1);
            }
            if (len > 0) prevData = src[off + len - 1] & 1;
        }

        /** data/gap/fuzzy: i byte sono dati decodificati → 16 celle MFM per byte
         *  (clock,data); clock = 1 sse il data bit precedente e quello corrente sono 0. */
        void writeMfm(byte[] src, int off, int len) {
            for (int i = 0; i < len; i++) {
                int b = src[off + i] & 0xFF;
                for (int k = 7; k >= 0; k--) {
                    int d = (b >> k) & 1;
                    cell((prevData == 0 && d == 0) ? 1 : 0);
                    cell(d);
                    prevData = d;
                }
            }
        }

        /** Riempie {@code cells} celle di gap (MFM di data bit a 0 → pattern clock,0). */
        void fillGap(int cells) {
            for (int i = 0; i < cells; i++) {
                if ((i & 1) == 0) {
                    cell(prevData == 0 ? 1 : 0);   // clock cell
                } else {
                    cell(0);                       // data cell = 0
                    prevData = 0;
                }
            }
        }
    }

    private byte[] copyIpfMFM(int physicalTrack) {
        return ipfMfmTracks[physicalTrack];
    }

    private static final class IpfImge {
        int cylinder, head, blockCnt, datChunk, trackBits, startBit;
        int denType, trkSize;
    }

    // Tipi di densità IPF (CapsDefinitions.h, enum cpden*).
    private static final int CPDEN_CLAMIGA  = 3; // Copylock Amiga
    private static final int CPDEN_CLAMIGA2 = 4; // Copylock Amiga, nuovo

    /**
     * Costruisce la mappa di densità per-byte della traccia (1000 = durata di cella
     * nominale), come {@code CCapsImageStd::DecodeDensity} di CAPSImg.
     *
     * <p>Le tracce del Rob Northen Copylock sono scritte con tre regioni a velocità
     * DIVERSA, ed è esattamente quello che la protezione misura: legge 1024 byte
     * dopo un sync contando i cicli di polling di DSKBYTR, e confronta il risultato
     * fra i vari sync. Con densità uniforme le tre misure risultano identiche e il
     * controllo fallisce (Barbarian II: schermo nero, caricamento fermo).
     *
     * <p>WinUAE usa la stessa mappa: {@code disk.cpp updatetrackspeed()} calcola
     * {@code trackspeed = base * tracktiming[mfmpos / 8] / 1000}.
     *
     * @return una voce per byte di traccia, oppure {@code null} se la densità è
     *         uniforme (nessun costo aggiuntivo nel caso normale)
     */
    private static int[] buildIpfDensity(byte[] raw, ByteBuffer buf, int area, IpfImge im) {
        boolean cla  = im.denType == CPDEN_CLAMIGA;
        boolean cla2 = im.denType == CPDEN_CLAMIGA2;
        if (!cla && !cla2) return null;

        int timeCnt = im.trkSize > 0 ? im.trkSize : 12_500;
        if (timeCnt <= 0 || im.blockCnt <= 0) return null;
        int[] timing = new int[timeCnt];
        Arrays.fill(timing, 1000);

        // CLA modifica i blocchi 4/5/6 e parte dal gap del blocco 3;
        // CLA2 modifica i blocchi 0/1/2 e parte dal gap dell'ultimo blocco.
        int firstMod = cla ? 4 : 0;
        int seedBlk  = cla ? 3 : im.blockCnt - 1;
        int lg = byteSize(blockField(raw, buf, area, seedBlk, 4)); // gapbits del blocco seme
        int pos = im.startBit >> 3;                                // sdpos = startbit / 8

        for (int b = 0; b < im.blockCnt; b++) {
            int bs = byteSize(blockField(raw, buf, area, b, 0));   // blockbits
            int gs = byteSize(blockField(raw, buf, area, b, 4));   // gapbits
            int delta = (b == firstMod) ? -55 : (b == firstMod + 1) ? -5
                      : (b == firstMod + 2) ? 45 : 0;
            if (delta != 0) {
                for (int cp = -lg; cp < bs; cp++) {
                    timing[Math.floorMod(pos + cp, timeCnt)] += delta;
                }
            }
            pos += bs + gs;
            if (pos >= timeCnt) pos -= timeCnt;
            lg = gs;
        }
        return timing;
    }

    /** Legge un campo (offset in byte) del descrittore di blocco {@code blk}. */
    private static int blockField(byte[] raw, ByteBuffer buf, int area, int blk, int off) {
        int d = area + blk * IPF_BLOCK_DESC_SIZE + off;
        return (blk < 0 || d < 0 || d + 4 > raw.length) ? 0 : buf.getInt(d);
    }

    /** CBitBuffer::CalculateByteSize — arrotondamento per eccesso a byte. */
    private static int byteSize(int bits) {
        return (bits + 7) >> 3;
    }
}
