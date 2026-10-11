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
 * The image of a physical Amiga floppy.
 *
 * <p>Supported formats:
 * <ul>
 *   <li><b>ADF</b> - read and write. Modified sectors are persisted to the source file
 *       selectively (dirty tracks only) by three flush policies: periodic (~5 s PAL), at
 *       disk eject, and at JVM exit through a shutdown hook.</li>
 *   <li><b>IPF</b> - read only. The raw MFM bitstream is extracted when the file is
 *       opened and kept in memory.</li>
 * </ul>
 *
 * <p>The class is {@link AutoCloseable}: a try-with-resources guarantees the final flush
 * even when an exception is thrown.
 */
public final class DiskImage implements AutoCloseable {

    // =========================================================================
    // Public constants
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

    // MFM buffer size of a standard track, the same constant as FloppyDrive's.
    static final int TRACK_BUFFER_BYTES = 12_668;
    private static final byte[] BLANK_MFM_TRACK = createBlankMfmTrack();

    // Maximum length of an MFM track. IPF protection tracks ("long tracks") exceed the
    // standard length (e.g. North &amp; South: ~13244 bytes); the track buffer is sized
    // for this maximum.
    public static final int MAX_TRACK_BYTES = 16_384;

    // Periodic flush every ~5 PAL seconds (base clock 7.09379 MHz)
    private static final long FLUSH_INTERVAL_CYCLES = 35_468_950L;

    // =========================================================================
    // State
    // =========================================================================

    private final Path    filePath;
    private final Format  format;
    private final boolean readOnly;
    private final int     sectorsPerTrack;

    /** Flat ADF sector data: offset = physicalTrack * sectorsPerTrack * SECTOR_SIZE */
    private final byte[]   sectorData;

    /** Pre-parsed IPF MFM buffers, one per physical track */
    private final byte[][] ipfMfmTracks;

    /** Per-byte cell density of the IPF tracks (1000 = nominal); null = uniform. */
    private final int[][] ipfTrackDensity;

    /** EXACT length in bits of every IPF track (long tracks are not multiples of 8
     *  bits). FloppyDrive wraps the rotation at the exact bit with it. */
    private final int[] ipfTrackBits;

    /**
     * How many physical tracks the medium has: 160 for an ADF, the cylinder and head range
     * of its INFO chunk for an IPF. A track past it was never formatted.
     */
    private final int formattedTracks;

    private final boolean[] dirtyTracks;
    private volatile boolean anyDirty = false;

    // PERF: per-track cache of the MFM encoding (ADF only). The encoding is deterministic
    // per track; it is cached and invalidated only by a write (markDirty). The work
    // buffers are reusable scratch arrays of the instance.
    private byte[][] mfmTrackCache;
    private final int[] adfTrackWordsScratch;
    private final int[] adfSectorMfmScratch;
    private long cyclesSinceLastFlush = 0L;
    private volatile boolean closed = false;
    /**
     * Safety net that flushes when the process dies. It is registered <b>lazily</b>, at the
     * first dirty track ({@link #markDirty}), not in the constructor.
     *
     * <p>A registered hook is a live GC root for the whole life of the JVM, removed only by
     * {@link #close()}. Registered for EVERY image, read-only ones and IPFs included (which
     * can never get dirty), every machine torn down without ejecting its disks would leave
     * up to four hooks behind, and with them the whole {@code DiskImage}. Over a whole
     * collection (the game sweep boots one instance per file) that is hundreds of MB that
     * never come back. Lazily, the hook exists only when there is something to save.
     * {@code Amija.shutdownMachine()} closes the four drives.
     */
    private Thread shutdownHook;

    // =========================================================================
    // Factory methods
    // =========================================================================

    /**
     * Opens a disk image from the given path.
     *
     * @param path           file path (.adf, .ipf or .zip)
     * @param forceReadOnly  {@code true} forces read-only access for an ADF too
     */
    public static DiskImage open(Path path, boolean forceReadOnly) throws IOException {
        return open(path, forceReadOnly, 0);
    }

    public static DiskImage open(Path path) throws IOException {
        return open(path, false);
    }

    /**
     * Opens a disk image from a <i>spec</i>: a plain file path, or the form
     * {@code <archive>.zip!<n>} naming the n-th disk inside the zip (see
     * {@link #zipEntrySpec(String, int)}).
     *
     * <p>A zip <b>without</b> an index is not its first disk but its <b>boot</b> disk
     * ({@link #zipDiskOrder}): the one that goes into DF0.
     */
    public static DiskImage open(String spec, boolean forceReadOnly) throws IOException {
        Path path = pathOf(spec);
        int index = hasZipEntryIndex(spec) ? zipEntryIndexOf(spec)
                  : isZipDisk(spec)        ? zipBootDiskIndex(path)
                  : 0;
        return open(path, forceReadOnly, index);
    }

    /**
     * @param zipEntryIndex index (0-based) of the disk to extract when {@code path} is a
     *                      zip; ignored for .adf/.ipf
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
    // Spec "<archive>.zip!<n>": addressing a single disk inside a zip
    // -------------------------------------------------------------------------

    /** Separator between the zip path and the index of the disk inside it. */
    public static final char ZIP_ENTRY_SEPARATOR = '!';

    private static final java.util.regex.Pattern ZIP_SPEC =
            java.util.regex.Pattern.compile("(?i)^(.*\\.zip)" + ZIP_ENTRY_SEPARATOR + "(\\d+)$");

    /** Spec naming the {@code entryIndex}-th disk inside {@code zipPath}. */
    public static String zipEntrySpec(String zipPath, int entryIndex) {
        return zipPath.trim() + ZIP_ENTRY_SEPARATOR + entryIndex;
    }

    /** {@code true} if the spec points at a zip (with or without a disk index). */
    public static boolean isZipDisk(String spec) {
        if (spec == null) return false;
        String s = spec.trim().toLowerCase();
        return s.endsWith(".zip") || ZIP_SPEC.matcher(s).matches();
    }

    /** Path of the file on disk for the spec ({@code !n} suffix removed). */
    public static Path pathOf(String spec) {
        String s = spec.trim();
        var m = ZIP_SPEC.matcher(s);
        return java.nio.file.Paths.get(m.matches() ? m.group(1) : s);
    }

    /** {@code true} if the spec carries the {@code !n} suffix with the disk index. */
    public static boolean hasZipEntryIndex(String spec) {
        return spec != null && ZIP_SPEC.matcher(spec.trim()).matches();
    }

    /** Index of the disk the spec names; 0 if none is given. */
    public static int zipEntryIndexOf(String spec) {
        var m = ZIP_SPEC.matcher(spec.trim());
        return m.matches() ? Integer.parseInt(m.group(2)) : 0;
    }

    /**
     * Names of the disks (.adf/.ipf) inside a zip, in the order they appear: the index in
     * the list is the one {@link #zipEntrySpec} uses.
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
     * Order in which the disks of a zip go into the drives: the <b>bootable</b> one first
     * (valid AmigaDOS boot block), then all the others in archive order.
     *
     * <p>Inside an archive the order is the names', and the disk to start from is hardly
     * ever the first: "Data Disk 1..3" sorts before "Program Disk", and so on. Someone
     * inserting floppies by hand puts the boot disk in DF0; this method does the same.
     *
     * <p>If no disk is bootable (data disk sets, add-on disks, games booting from another
     * image) the archive order stays.
     *
     * @return the disk indices, as many as there are disks in the zip
     */
    public static int[] zipDiskOrder(Path zipPath) throws IOException {
        int count = listZipDiskEntries(zipPath).size();
        int boot = 0;
        for (int i = 0; i < count; i++) {
            try (DiskImage image = open(zipPath, true, i)) {
                if (image.isBootable()) { boot = i; break; }
            } catch (IOException | RuntimeException ignored) {
                // Unreadable disk: not a boot candidate, go on.
            }
        }
        int[] order = new int[count];
        int k = 0;
        if (count > 0) order[k++] = boot;
        for (int i = 0; i < count; i++) if (i != boot) order[k++] = i;
        return order;
    }

    /** Index of the zip's bootable disk, 0 if there is none (or the zip is unreadable). */
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

    /** Extracts the {@code wanted}-th disk (.adf/.ipf) in the zip. */
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
    // Constructor (private)
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
            formattedTracks = PHYSICAL_TRACKS;
        } else {
            sectorsPerTrack = SECTORS_PER_TRACK_DD;
            sectorData      = null;
            IpfTracks t     = parseIpf(raw);
            ipfMfmTracks    = t.mfm;
            ipfTrackBits    = t.bits;
            ipfTrackDensity = t.density;
            formattedTracks = t.trackCount;
            mfmTrackCache = null;
            adfTrackWordsScratch = null;
            adfSectorMfmScratch = null;
        }

        dirtyTracks = new boolean[PHYSICAL_TRACKS];
        // The shutdown hook is NOT registered here: see the shutdownHook field.
    }

    // =========================================================================
    // API for FloppyDrive: reading
    // =========================================================================

    /**
     * Returns {@value #TRACK_BUFFER_BYTES} bytes of MFM data for the given physical track,
     * ready to be loaded into FloppyDrive's buffer.
     *
     * <p>For an ADF the MFM encoding is made on demand and cached. For an IPF the data was
     * extracted when the file was opened.
     *
     * @param physicalTrack physical track = cylinder x 2 + side
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

        // ADF: return the cached MFM encoding (the caller, loadTrackData, copies it into
        // trackBuffer, so sharing the cached instance is safe).
        byte[] cached = mfmTrackCache[physicalTrack];
        if (cached == null) {
            cached = new byte[TRACK_BUFFER_BYTES];
            encodeAdfToMFM(physicalTrack, cached);
            mfmTrackCache[physicalTrack] = cached;
        }
        return cached;
    }

    /**
     * Density map of the track: one entry per byte, 1000 = nominal cell length.
     * {@code null} when the density is uniform (the normal case, every ADF included): the
     * caller then uses the constant cell period.
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

    /**
     * Whether the physical track lies past the medium's formatted tracks (cylinders 80-83 of
     * an ADF, past the INFO range of an IPF). A drive reading it sees noise.
     */
    public boolean isUnformatted(int physicalTrack) {
        return physicalTrack < 0 || physicalTrack >= formattedTracks;
    }

    /**
     * Length of the track in bits, where the rotation wraps. For an ADF and empty IPF tracks
     * it is the standard length ({@code bytes x 8}); for an IPF track the exact
     * {@code trkbits} (not necessarily a multiple of 8).
     */
    public int getTrackBits(int physicalTrack) {
        if (physicalTrack < 0 || physicalTrack >= PHYSICAL_TRACKS || !hasTrackData(physicalTrack))
            return TRACK_BUFFER_BYTES * 8;
        if (format == Format.ADF || ipfTrackBits == null || ipfTrackBits[physicalTrack] <= 0)
            return TRACK_BUFFER_BYTES * 8;
        return ipfTrackBits[physicalTrack];
    }

    /**
     * Returns the {@value #SECTOR_SIZE} raw data bytes of the given sector. ADF images only.
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
    // API for FloppyDrive: writing (non write-protected ADF only)
    // =========================================================================

    /**
     * Writes {@value #SECTOR_SIZE} raw data bytes to the given sector. The track is marked
     * dirty for the next flush.
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
     * Writes all the sectors of a physical track (sectorsPerTrack x 512 bytes).
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
     * Stores an MFM track written by the drive into the image, decoding its sectors in
     * AmigaDOS format (the exact inverse of {@link #encodeAdfToMFM}).
     *
     * <p>The bitstream is CIRCULAR: the write starts at the angular position the head was
     * at, so a sector can cross the wrap point of the track. The scan looks for the
     * {@code $4489} sync words bit by bit (the written data is not aligned to the buffer's
     * bytes) and accepts a sector only if both the header and the data checksums match, so
     * cells left over from the previous track (gaps not rewritten) cannot corrupt sectors.
     *
     * <p>Only standard AmigaDOS sectors are supported: a custom format cannot be represented
     * in an ADF and is ignored.
     *
     * @param lenBytes useful length of the track in the buffer
     * @return number of sectors actually decoded and written
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

        // Scan with a 16-bit shift register: a full revolution plus the initial window,
        // so a sync straddling the wrap is found too.
        int shift = 0;
        for (int i = 0; i < totalBits + 15; i++) {
            shift = ((shift << 1) | mfmBit(mfm, totalBits, i)) & 0xFFFF;
            if (i < 15 || shift != MFM_SYNC) continue;

            // Start of the header: right after the last consecutive sync (the standard
            // format writes two).
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
            // Skip past the sector just decoded (-1: the loop increments).
            i = pos + SECTOR_MFM_WORDS * 16 - 1;
            shift = 0;
        }

        if (written > 0) markDirty(physicalTrack);
        return written;
    }

    /** Standard AmigaDOS MFM sync word. */
    private static final int MFM_SYNC = 0x4489;
    /** MFM words of a sector after the sync: info(4) + label(16) + chk(8) + data(512). */
    private static final int SECTOR_MFM_WORDS = 540;

    /**
     * Decodes a sector whose data starts at {@code pos} (first word of the header, right
     * after the syncs).
     *
     * @return the sector number, or -1 if it is not a valid sector of this track (format,
     *         track number or checksums do not match)
     */
    private int decodeSectorMFM(int physicalTrack, byte[] mfm, int totalBits, int pos,
                                byte[] out) {
        // Words 0-3: info = format(FF) | track | sector | sectors to the end of the track
        int info = mfmOddEvenLong(mfm, totalBits, pos, pos + 2 * 16);
        if (((info >>> 24) & 0xFF) != 0xFF)             return -1;
        if (((info >>> 16) & 0xFF) != physicalTrack)    return -1;
        int num = (info >>> 8) & 0xFF;
        if (num >= sectorsPerTrack)                     return -1;

        // Words 0-19 (info + label) -> header checksum in words 20-23.
        int headerChecksum = 0;
        for (int w = 0; w < 20; w += 2) {
            headerChecksum ^= (mfmDataWord(mfm, totalBits, pos + w * 16) << 16)
                            |  mfmDataWord(mfm, totalBits, pos + (w + 1) * 16);
        }
        if (headerChecksum != mfmOddEvenLong(mfm, totalBits, pos + 20 * 16, pos + 22 * 16))
            return -1;

        // Words 28-539 (data) -> data checksum in words 24-27.
        int dataChecksum = 0;
        for (int w = 28; w < SECTOR_MFM_WORDS; w += 2) {
            dataChecksum ^= (mfmDataWord(mfm, totalBits, pos + w * 16) << 16)
                          |  mfmDataWord(mfm, totalBits, pos + (w + 1) * 16);
        }
        if (dataChecksum != mfmOddEvenLong(mfm, totalBits, pos + 24 * 16, pos + 26 * 16))
            return -1;

        // 128 longs: odd halves in words 28-283, even halves in words 284-539.
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
     * Boot block: sectors 0 and 1 of track 0, decoded from the MFM as the drive does. It
     * works for every format, since it starts from the bitstream.
     *
     * @return 1024 bytes, or {@code null} if either sector is missing
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
     * {@code true} if the disk boots: {@code DOS} signature and a valid boot block
     * checksum, the same two checks the Kickstart's strap makes. A disk formatted but never
     * {@code install}ed, or filled with a filler pattern by the duplicator, fails them, and
     * that is why the Kickstart keeps asking for a disk.
     */
    public boolean isBootable() {
        byte[] boot = readBootBlock();
        if (boot == null) return false;
        if (boot[0] != 'D' || boot[1] != 'O' || boot[2] != 'S') return false;
        long sum = 0;
        for (int i = 0; i < boot.length; i += 4) {
            sum += ((boot[i]     & 0xFFL) << 24) | ((boot[i + 1] & 0xFFL) << 16)
                 | ((boot[i + 2] & 0xFFL) << 8)  |  (boot[i + 3] & 0xFFL);
            if (sum > 0xFFFFFFFFL) sum = (sum & 0xFFFFFFFFL) + 1; // end-around carry
        }
        return sum == 0xFFFFFFFFL;
    }

    /** MFM cell at the given position; the track is circular. */
    private static int mfmBit(byte[] mfm, int totalBits, int bitPos) {
        int p = bitPos % totalBits;
        return (mfm[p >> 3] >> (7 - (p & 7))) & 1;
    }

    /** 16 MFM cells from the given position (MSB first). */
    private static int mfmWord(byte[] mfm, int totalBits, int bitPos) {
        int word = 0;
        for (int i = 0; i < 16; i++) word = (word << 1) | mfmBit(mfm, totalBits, bitPos + i);
        return word;
    }

    /** Like {@link #mfmWord} without the clock bits: the data bits only. */
    private static int mfmDataWord(byte[] mfm, int totalBits, int bitPos) {
        return mfmWord(mfm, totalBits, bitPos) & 0x5555;
    }

    /**
     * Rebuilds a long from the odd/even halves of {@link #putOddEvenLong} (2 words each):
     * {@code (odd << 1) | even} on the data bits only.
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
     * Called at every FloppyDrive tick with the clock cycles elapsed. Flushes the dirty
     * tracks every {@value #FLUSH_INTERVAL_CYCLES} cycles (~5 s PAL).
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
     * Writes only the dirty tracks to disk through random access to the file, without
     * rewriting the whole image. Idempotent.
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
     * Final flush and release of resources, at eject or when the emulator closes. The
     * shutdown hook guarantees the flush even if this is never called.
     */
    @Override
    public synchronized void close() throws IOException {
        if (!closed) {
            // The flush MUST come before closed=true: flush() returns at once on a closed
            // image, so the other order would silently skip the last flush and lose the
            // dirty tracks the periodic flush had not written yet.
            flush();
            closed = true;
            if (shutdownHook != null) {
                try { Runtime.getRuntime().removeShutdownHook(shutdownHook); }
                catch (IllegalStateException ignored) {} // JVM already shutting down
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
        // Invalidate the cached MFM encoding: the sector data has changed.
        if (mfmTrackCache != null) mfmTrackCache[physicalTrack] = null;
        armShutdownHook();
    }

    /**
     * Registers the flush safety net at the first dirty track. Idempotent; does nothing on
     * a read-only, non-ADF or closed image, where there is nothing to save. See the
     * {@link #shutdownHook} field.
     */
    private void armShutdownHook() {
        if (shutdownHook != null || closed || readOnly || format != Format.ADF) return;
        Thread hook = new Thread(this::flushQuietly,
                                 "DiskImage-flush-" + filePath.getFileName());
        try {
            Runtime.getRuntime().addShutdownHook(hook);
            shutdownHook = hook;
        } catch (IllegalStateException ignored) {
            // JVM already shutting down: close() makes the final flush anyway, and if it
            // does not come there is nobody left to register anything.
        }
    }

    // =========================================================================
    // Properties
    // =========================================================================

    public Format  getFormat()          { return format; }
    public boolean isReadOnly()         { return readOnly; }
    public boolean isDirty()            { return anyDirty; }
    public int     getSectorsPerTrack() { return sectorsPerTrack; }
    public Path    getFilePath()        { return filePath; }

    // =========================================================================
    // Validation
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
    // ADF -> MFM encoding
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

    // Size of a Block Descriptor (8 x uint32) in the data area of a DATA chunk.
    private static final int IPF_BLOCK_DESC_SIZE = 32;

    /** Decoded IPF tracks: MFM bitstream + exact length in bits. */
    private static final class IpfTracks {
        final byte[][] mfm  = new byte[PHYSICAL_TRACKS][];
        final int[]    bits = new int[PHYSICAL_TRACKS];
        /** Per-byte density (1000 = nominal); null = uniform density. */
        final int[][]  density = new int[PHYSICAL_TRACKS][];
        /** Tracks of the cylinder and head range declared by INFO. */
        int trackCount = PHYSICAL_TRACKS;
    }

    /**
     * Parser of the IPF format (SPS/CAPS, CAPS encoder), read only.
     *
     * <p>The file is a sequence of chunks, each with a 12-byte header (tag[4] + length[4] +
     * crc[4], big-endian) where {@code length} includes the header. The relevant chunks:
     * <ul>
     *   <li><b>CAPS</b>/<b>INFO</b> - magic and metadata, among them the cylinder and head
     *       range;</li>
     *   <li><b>IMGE</b> - one descriptor per track: cylinder, head, number of blocks
     *       (sectors) and the {@code dat_chunk} id linking it to its DATA;</li>
     *   <li><b>DATA</b> - the encoded data of a track. The chunk's {@code length} covers ONLY
     *       the descriptor (16 bytes: size, bsize, dcrc, dat_chunk); the {@code size} bytes
     *       of the <i>data area</i> follow the chunk and are NOT included in its length.</li>
     * </ul>
     *
     * <p>All IMGE chunks come before the DATA ones. The data area of a DATA holds
     * {@code blockCount} Block Descriptors of {@value #IPF_BLOCK_DESC_SIZE} bytes; their
     * {@code dataOffset} field (8th uint32) points, relative to the start of the area, at
     * the block's "stream": a sequence of elements (sync/data/gap/raw/fuzzy) that
     * {@link #decodeBlockStream} assembles into MFM.
     */
    private static IpfTracks parseIpf(byte[] raw) throws IOException {
        if (!isIpfMagic(raw))
            throw new IOException("IPF: magic 'CAPS' non trovato");

        IpfTracks out = new IpfTracks();
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);

        // First pass: collect the IMGE chunks indexed by dat_chunk (they precede the DATA).
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
                im.denType   = buf.getInt(body + 8);  // density type (cpden* enum)
                im.trkSize   = buf.getInt(body + 16); // track size in bytes
                im.startBit  = buf.getInt(body + 24); // start position (bits)
                im.trackBits = buf.getInt(body + 36); // track length (bits)
                im.blockCnt  = buf.getInt(body + 40);
                im.datChunk  = buf.getInt(body + 52);
                imgeByDat.put(im.datChunk, im);
            } else if ("INFO".equals(tag) && size - 12 >= 40) {
                // mincylinder, maxcylinder, minhead, maxhead
                int cylinders = buf.getInt(body + 28) - buf.getInt(body + 24) + 1;
                int heads     = buf.getInt(body + 36) - buf.getInt(body + 32) + 1;
                if (cylinders > 0 && heads > 0) out.trackCount = cylinders * heads;
            } else if ("DATA".equals(tag) && size - 12 >= 16) {
                // The data area follows the chunk and is NOT counted in size.
                next += buf.getInt(body); // body+0 = size (bytes) of the data area
            }
            pos = next;
        }

        // Second pass: decode every DATA into the track of its IMGE.
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
                int area      = body + 16; // start of the data area (after the descriptor)
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

        // Tracks without data -> "empty" MFM buffer (clock pattern 0xAA).
        for (int t = 0; t < PHYSICAL_TRACKS; t++)
            if (out.mfm[t] == null) {
                out.mfm[t]  = blankMfmTrack();
                out.bits[t] = TRACK_BUFFER_BYTES * 8;
            }

        return out;
    }

    /**
     * Assembles a track at BIT level: the blocks are written from {@code startbit},
     * wrapping at exactly {@code trkbits} bits (protection long tracks are not multiples
     * of 8 bits). Every block produces {@code blockbits} cells (from the sync/data stream)
     * followed by {@code gapbits} gap cells; the last block also gets the difference
     * between the track length and the sum of the blocks (track gap).
     */
    private static byte[] decodeIpfTrack(byte[] raw, ByteBuffer buf, int area, IpfImge im) {
        TrackBitBuf w = new TrackBitBuf(im.trackBits);

        // Sum of blockbits+gapbits over all blocks, for the track gap.
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
            int dataOffset = buf.getInt(d + 28); // 8th uint32 = stream offset in the data area
            if (b == im.blockCnt - 1 && trackDiff > 0) gapBits += trackDiff;

            decodeBlockStream(raw, area + dataOffset, w); // blockbits cells (sync + data)
            int gapStart = w.pos();
            w.fillGap(gapBits);                            // gap cells (MFM of zeros)
            w.alignGapTail(gapStart, gapBits, b == im.blockCnt - 1);
        }
        return w.buf();
    }

    /**
     * Decodes a block's stream, writing its MFM cells into the track buffer. Element
     * header: low 5 bits = type (0=end, 1=sync, 2=data, 3=gap, 4=raw, 5=fuzzy), high 3 bits
     * = number of bytes of the count field that follows; then {@code count} payload bytes.
     * Sync/raw are MFM cells already; data/gap/fuzzy are decoded data and get MFM encoded.
     */
    private static void decodeBlockStream(byte[] raw, int pos, TrackBitBuf w) {
        while (pos < raw.length) {
            int head = raw[pos++] & 0xFF;
            if (head == 0) break; // end of stream
            int type = head & 0x1F;
            int sw   = (head >> 5) & 0x07;
            int count = 0;
            for (int k = 0; k < sw && pos < raw.length; k++)
                count = (count << 8) | (raw[pos++] & 0xFF);
            if (count < 0 || pos + count > raw.length) count = Math.max(0, raw.length - pos);
            switch (type) {
                case 1, 4 -> w.writeRaw(raw, pos, count);   // sync / raw -> MFM cells verbatim
                case 2, 3, 5 -> w.writeMfm(raw, pos, count); // data / gap / fuzzy -> MFM
                default -> { /* 0 handled above; unknown types: skip */ }
            }
            pos += count;
        }
    }

    /**
     * Bit-level writer into the track buffer, wrapping at exactly {@code trackBits} bits.
     * Keeps the last data bit for the MFM clock bits.
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
        int pos() { return pos; }
        void seek(int bitPos) { pos = ((bitPos % trackBits) + trackBits) % trackBits; }

        private void cell(int bit) {
            int mask = 0x80 >> (pos & 7);
            if (bit != 0) buf[pos >> 3] |= (byte) mask;
            else          buf[pos >> 3] &= (byte) ~mask;
            if (++pos >= trackBits) pos = 0;
        }

        /** sync/raw: the bytes are MFM cells already, copied bit by bit (MSB first). */
        void writeRaw(byte[] src, int off, int len) {
            for (int i = 0; i < len; i++) {
                int b = src[off + i] & 0xFF;
                for (int k = 7; k >= 0; k--) cell((b >> k) & 1);
            }
            if (len > 0) prevData = src[off + len - 1] & 1;
        }

        /** data/gap/fuzzy: the bytes are decoded data -> 16 MFM cells per byte
         *  (clock, data); clock = 1 iff the previous and the current data bits are 0. */
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

        /**
         * Rewrites the second part of a gap so that it lines up with the block that follows.
         *
         * <p>A gap is laid down from both of its ends: the first part continues the cell
         * pattern of the block before it (what {@link #fillGap} writes), the second part is
         * the clock,0 pattern anchored to the start of the next block, so the cell just
         * before that block is always a data cell. The two meet halfway, or at the index
         * when the gap runs across it. Where the two parts disagree in phase, the second
         * one wins. On the track gap (the gap of the last block, which absorbs the
         * difference between the track length and its blocks) the first cells of the
         * second part are inverted: three of them, or the second and third when the first
         * part has an odd number of cells and the second an even one.
         */
        void alignGapTail(int gapStart, int cells, boolean trackGap) {
            if (cells <= 0) return;
            final int n = trackBits;
            final int gapEnd = (gapStart + cells) % n;
            final int junction = gapStart + cells > n ? 0 : (gapStart + cells / 2) % n;
            final int first = (junction - gapStart + n) % n;
            final int second = (gapEnd - junction + n) % n;
            final int invertFrom = (trackGap && (first & 1) == 1 && (second & 1) == 0) ? 1 : 0;
            for (int k = 0; k < second; k++) {
                int p = (junction + k) % n;
                int bit = ((second - k) & 1) == 1 ? 0 : 1;   // data cell 0, clock cell 1
                if (trackGap && k >= invertFrom && k < 3) bit ^= 1;
                int mask = 0x80 >> (p & 7);
                if (bit != 0) buf[p >> 3] |= (byte) mask;
                else          buf[p >> 3] &= (byte) ~mask;
            }
        }

        /** Fills {@code cells} gap cells (MFM of 0 data bits -> clock,0 pattern). */
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

    // IPF density types (cpden* enum).
    private static final int CPDEN_CLAMIGA  = 3; // Copylock Amiga
    private static final int CPDEN_CLAMIGA2 = 4; // Copylock Amiga, new

    /**
     * Builds the track's per-byte density map (1000 = nominal cell length).
     *
     * <p>Rob Northen Copylock tracks are written with three regions at DIFFERENT speeds,
     * and that is exactly what the protection measures: it reads 1024 bytes after a sync
     * counting DSKBYTR polling loops, and compares the result across the syncs. With a
     * uniform density the three measurements come out identical and the check fails
     * (Barbarian II: black screen, loading stopped). The drive takes each cell's length
     * from this map: {@code base * density[pos / 8] / 1000}.
     *
     * @return one entry per track byte, or {@code null} if the density is uniform (no
     *         extra cost in the normal case)
     */
    private static int[] buildIpfDensity(byte[] raw, ByteBuffer buf, int area, IpfImge im) {
        boolean cla  = im.denType == CPDEN_CLAMIGA;
        boolean cla2 = im.denType == CPDEN_CLAMIGA2;
        if (!cla && !cla2) return null;

        int timeCnt = im.trkSize > 0 ? im.trkSize : 12_500;
        if (timeCnt <= 0 || im.blockCnt <= 0) return null;
        int[] timing = new int[timeCnt];
        Arrays.fill(timing, 1000);

        // CLA modifies blocks 4/5/6 and starts from the gap of block 3;
        // CLA2 modifies blocks 0/1/2 and starts from the gap of the last block.
        int firstMod = cla ? 4 : 0;
        int seedBlk  = cla ? 3 : im.blockCnt - 1;
        int lg = byteSize(blockField(raw, buf, area, seedBlk, 4)); // gapbits of the seed block
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

    /** Reads a field (byte offset) of the descriptor of block {@code blk}. */
    private static int blockField(byte[] raw, ByteBuffer buf, int area, int blk, int off) {
        int d = area + blk * IPF_BLOCK_DESC_SIZE + off;
        return (blk < 0 || d < 0 || d + 4 > raw.length) ? 0 : buf.getInt(d);
    }

    /** Bits rounded up to whole bytes. */
    private static int byteSize(int bits) {
        return (bits + 7) >> 3;
    }
}
