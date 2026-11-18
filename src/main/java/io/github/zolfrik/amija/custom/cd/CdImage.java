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

package io.github.zolfrik.amija.custom.cd;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A CD image: a {@code .cue} sheet with its track files (binary data tracks, WAVE audio
 * tracks), or a bare {@code .iso} / raw {@code .bin}, read from a folder or from inside a
 * {@code .zip} ({@link TrackSource}).
 *
 * <p>Addresses are logical sector numbers (LSN, 75 per second, 0 = the start of the program
 * area, i.e. MSF 00:02:00). The table of contents is built from the sheet the way the drive
 * reports it: per track its address (the {@code INDEX 01}, pregaps added), its control nibble
 * (4 = data) and the lead-out address.
 */
public final class CdImage implements Closeable {

    /** One track of the sheet. */
    public static final class Track {
        /** Track number, 1-99. */
        public int number;
        /** Control nibble: bit 2 data track, bit 0 pre-emphasis, bit 1 copy, bit 3 four channels. */
        public int ctrl;
        public int adr = 1;
        /** LSN of INDEX 01. */
        public int address;
        /** Bytes per sector in the file: 2048, 2336 or 2352. */
        public int size;
        /** Byte offset in the file of the sector at {@link #address} minus {@link #index1}. */
        public long offset;
        /** Bytes of track data in the file (for WAVE files, the size of the data chunk). */
        public long filesize;
        public int pregap;
        public int postgap;
        /** Sectors between INDEX 00 and INDEX 01. */
        public int index1;
        public boolean wave;
        public String fileName;
        TrackSource source;

        public boolean isData() {
            return (ctrl & 4) != 0;
        }
    }

    /** One entry of the drive's table of contents. */
    public record TocEntry(int adr, int control, int point, int track, int address) { }

    private final List<Track> tracks = new ArrayList<>();
    /** LSN of the lead-out. */
    private int leadOut;
    private final List<TrackSource> sources = new ArrayList<>();
    private final String name;
    private TrackSource.Container container;

    private CdImage(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    public List<Track> tracks() {
        return tracks;
    }

    public int leadOut() {
        return leadOut;
    }

    /**
     * Opens an image: a {@code .cue}, an {@code .iso}/{@code .bin}, or a {@code .zip}
     * holding either (a sheet is preferred to a bare image).
     */
    public static CdImage open(Path path) throws IOException {
        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
        TrackSource.Container container = lower.endsWith(".zip")
                ? TrackSource.zip(path) : TrackSource.folder(path.toAbsolutePath().getParent());
        try {
            String sheet;
            if (lower.endsWith(".zip")) {
                sheet = container.find(n -> n.toLowerCase(Locale.ROOT).endsWith(".cue"));
                if (sheet == null) {
                    sheet = container.find(n -> {
                        String l = n.toLowerCase(Locale.ROOT);
                        return l.endsWith(".iso") || l.endsWith(".bin") || l.endsWith(".img");
                    });
                }
                if (sheet == null) throw new IOException("no CD image inside " + path.getFileName());
            } else {
                sheet = path.getFileName().toString();
            }
            CdImage image = new CdImage(path.getFileName().toString());
            image.container = container;
            if (sheet.toLowerCase(Locale.ROOT).endsWith(".cue")) {
                byte[] text = container.readAll(sheet);
                image.parseCue(new String(text, StandardCharsets.ISO_8859_1), container);
            }
            if (image.tracks.isEmpty()) image.plainImage(container, sheet);
            if (image.tracks.isEmpty()) throw new IOException("not a CD image: " + path.getFileName());
            return image;
        } catch (IOException | RuntimeException e) {
            container.close();
            throw e;
        }
    }

    /** A bare image: one data track, 2048 or 2352 bytes per sector. */
    private void plainImage(TrackSource.Container container, String file) throws IOException {
        TrackSource src = container.open(file);
        if (src == null) return;
        long siz = src.size();
        if (siz >= 16384 && ((siz % 2048) == 0 || (siz % 2352) == 0)) {
            Track t = new Track();
            t.number = 1;
            t.ctrl = 4;
            t.size = (siz % 2048) == 0 ? 2048 : 2352;
            t.filesize = siz;
            t.fileName = file;
            t.source = src;
            sources.add(src);
            tracks.add(t);
            leadOut = (int) (siz / t.size);
        } else {
            src.close();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Cue sheet
    // ---------------------------------------------------------------------------------------

    /** Splits the next token: a quoted string or a run of non-blank characters. */
    private static final class Tokens {
        private final String s;
        private int p;

        Tokens(String s, int p) {
            this.s = s;
            this.p = p;
        }

        String next() {
            while (p < s.length() && Character.isWhitespace(s.charAt(p))) p++;
            if (p >= s.length()) return null;
            int start;
            String out;
            if (s.charAt(p) == '"') {
                start = ++p;
                while (p < s.length() && s.charAt(p) != '"') p++;
                out = s.substring(start, p);
                p++;
            } else {
                start = p;
                while (p < s.length() && !Character.isWhitespace(s.charAt(p))) p++;
                out = s.substring(start, p);
            }
            return out;
        }
    }

    private static int atoi(String s, int from) {
        int i = from;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        boolean neg = false;
        if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) neg = s.charAt(i++) == '-';
        int v = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i))) v = v * 10 + (s.charAt(i++) - '0');
        return neg ? -v : v;
    }

    /** {@code mm:ss:ff} to sectors. */
    private static int msf(String t) {
        if (t == null) return 0;
        return atoi(t, 0) * 60 * 75 + (t.length() > 3 ? atoi(t, 3) * 75 : 0) + (t.length() > 6 ? atoi(t, 6) : 0);
    }

    private static boolean startsWithIgnoreCase(String s, int p, String word) {
        return s.regionMatches(true, p, word, 0, word.length());
    }

    private void parseCue(String text, TrackSource.Container container) throws IOException {
        Track[] toc = new Track[100];
        int trackNum = 0;
        long fileOffset = 0;
        int secOffset = 0;
        boolean newFile = false;
        int ctrl = 0;
        int index0 = -1;
        int pregap = 0;
        int postgap = 0;
        int lastPregap = 0;
        int lastPostgap = 0;
        boolean wave = false;
        String fname = null;
        int count = 0;

        for (String line : text.split("\r\n|\r|\n")) {
            int p = 0;
            while (p < line.length() && Character.isWhitespace(line.charAt(p))) p++;
            if (startsWithIgnoreCase(line, p, "FILE")) {
                Tokens tk = new Tokens(line, p + 4);
                fname = tk.next();
                String type = tk.next();
                if (type == null) break;
                wave = !"BINARY".equalsIgnoreCase(type);
                fileOffset = 0;
                newFile = true;
                ctrl = 0;
            } else if (startsWithIgnoreCase(line, p, "FLAGS")) {
                Tokens tk = new Tokens(line, p + 5);
                ctrl &= ~(1 | 2 | 8);
                for (String f; (f = tk.next()) != null; ) {
                    if (f.equalsIgnoreCase("PRE")) ctrl |= 1;
                    if (f.equalsIgnoreCase("DCP")) ctrl |= 2;
                    if (f.equalsIgnoreCase("4CH")) ctrl |= 8;
                }
            } else if (startsWithIgnoreCase(line, p, "TRACK")) {
                Tokens tk = new Tokens(line, p + 5);
                index0 = -1;
                lastPregap = 0;
                lastPostgap = 0;
                String num = tk.next();
                trackNum = num == null ? 0 : atoi(num, 0);
                if (trackNum == 0 && "0".equals(num) && count == 0) trackNum = 1;
                String type = tk.next();
                if (type == null) break;
                int size = 2352;
                if (type.equalsIgnoreCase("AUDIO")) {
                    ctrl &= ~4;
                } else {
                    ctrl |= 4;
                    if (type.equalsIgnoreCase("MODE1/2048")) size = 2048;
                    else if (type.equalsIgnoreCase("MODE2/2336") || type.equalsIgnoreCase("CDI/2336")) size = 2336;
                }
                if (trackNum >= 1 && trackNum <= 99) {
                    if (trackNum > 1 && newFile) {
                        Track prev = toc[trackNum - 2];
                        if (prev != null && prev.size != 0) secOffset += (int) (prev.filesize / prev.size);
                    }
                    newFile = false;
                    Track t = new Track();
                    toc[trackNum - 1] = t;
                    t.number = trackNum;
                    t.ctrl = ctrl;
                    t.size = size;
                    t.fileName = fname;
                    t.source = fname == null ? null : container.open(fname);
                    if (t.source != null) {
                        sources.add(t.source);
                        t.filesize = t.source.size();
                    }
                    if (trackNum > count) count = trackNum;
                }
            } else if (startsWithIgnoreCase(line, p, "PREGAP")) {
                int tn = msf(new Tokens(line, p + 6).next());
                pregap += tn;
                lastPregap = tn;
            } else if (startsWithIgnoreCase(line, p, "POSTGAP")) {
                int tn = msf(new Tokens(line, p + 7).next());
                postgap += tn;
                lastPostgap = tn;
            } else if (startsWithIgnoreCase(line, p, "INDEX")) {
                Tokens tk = new Tokens(line, p + 5);
                String idx = tk.next();
                int idxNum = idx == null ? 0 : atoi(idx, 0);
                int tn = msf(tk.next());
                if (idxNum == 0) {
                    index0 = tn;
                } else if (idxNum == 1 && trackNum >= 1 && trackNum <= 99 && toc[trackNum - 1] != null) {
                    Track t = toc[trackNum - 1];
                    if (t.address == 0) {
                        t.address = tn + secOffset;
                        t.address += pregap;
                        t.pregap = lastPregap;
                        t.postgap = lastPostgap;
                        if (index0 >= 0) t.index1 = tn - index0;
                        if (lastPregap != 0 && secOffset == 0) t.index1 = lastPregap;
                        int blockOffset = t.address - t.index1;
                        Track prev = trackNum > 1 ? toc[trackNum - 2] : null;
                        if (prev != null) blockOffset -= prev.address - prev.index1;
                        fileOffset += (long) blockOffset * (prev != null ? prev.size : 0);
                        if (secOffset == 0) {
                            t.offset = fileOffset - (long) pregap * t.size;
                        } else {
                            t.pregap = 0;
                        }
                        t.address += postgap;
                        if (wave && t.source != null) readWaveHeader(t);
                    }
                }
            }
        }
        if (count == 0) return;
        for (int i = 0; i < count; i++) {
            if (toc[i] == null) throw new IOException("cue sheet without track " + (i + 1));
            tracks.add(toc[i]);
        }
        Track last = toc[count - 1];
        long size = last.filesize;
        if (secOffset == 0) size -= fileOffset;
        if (size < 0) size = 0;
        size /= last.size;
        leadOut = last.address + (int) size;
    }

    /** A WAVE track: the samples start at the data chunk, whose size is the track's. */
    private static void readWaveHeader(Track t) throws IOException {
        t.wave = true;
        byte[] buf = new byte[12];
        if (t.source.read(0, buf, 0, 12) != 12) return;
        if (!new String(buf, 0, 4, StandardCharsets.ISO_8859_1).equals("RIFF")
                || !new String(buf, 8, 4, StandardCharsets.ISO_8859_1).equals("WAVE")) {
            return;
        }
        long pos = 12;
        int size = 0;
        byte[] chunk = new byte[8];
        for (;;) {
            java.util.Arrays.fill(chunk, (byte) 0);
            if (t.source.read(pos, chunk, 0, 8) != 8) break;
            pos += 8;
            size = (chunk[4] & 0xFF) | (chunk[5] & 0xFF) << 8 | (chunk[6] & 0xFF) << 16 | (chunk[7] & 0xFF) << 24;
            if (new String(chunk, 0, 4, StandardCharsets.ISO_8859_1).equals("data")) break;
            if (size <= 0) break;
            pos += size;
        }
        t.offset += pos;
        t.filesize = size;
    }

    // ---------------------------------------------------------------------------------------
    // Table of contents
    // ---------------------------------------------------------------------------------------

    /**
     * The table of contents as the drive reports it: point {@code $A0} (first track), one
     * entry per track, {@code $A1} (last track) and {@code $A2} (lead-out). The control nibble
     * of the three special points is 4 when every track is a data track, 0 otherwise.
     */
    public List<TocEntry> toc() {
        List<TocEntry> out = new ArrayList<>();
        int mask = 4;
        for (Track t : tracks) if ((t.ctrl & 4) == 0) mask = 0;
        out.add(new TocEntry(1, mask, 0xA0, 1, 0));
        for (int i = 0; i < tracks.size(); i++) {
            Track t = tracks.get(i);
            out.add(new TocEntry(t.adr, t.ctrl, i + 1, i + 1, t.address));
        }
        out.add(new TocEntry(1, mask, 0xA1, tracks.size(), leadOut));
        out.add(new TocEntry(1, mask, 0xA2, 0, leadOut));
        return out;
    }

    /**
     * The track holding {@code lsn} and the sector's index inside its file: the last track
     * whose INDEX 00 is at or before it; an address before the first track reads its first
     * sector. {@code null} past the lead-out or for a negative address.
     */
    private Track findTrack(int lsn, int[] relative) {
        if (lsn < 0) return null;
        for (int i = 0; i <= tracks.size(); i++) {
            int start = i < tracks.size() ? tracks.get(i).address - tracks.get(i).index1 : leadOut;
            if (start > lsn) {
                if (i == 0) {
                    relative[0] = 0;
                    return tracks.get(0);
                }
                Track t = tracks.get(i - 1);
                relative[0] = lsn - (t.address - t.index1);
                return t;
            }
        }
        return null;
    }

    /** The track holding {@code lsn}, or {@code null} past the lead-out. */
    public Track trackAt(int lsn) {
        return findTrack(lsn, new int[1]);
    }

    // ---------------------------------------------------------------------------------------
    // Sectors
    // ---------------------------------------------------------------------------------------

    /**
     * Sector {@code lsn} as the drive delivers it, 2352 bytes: a sector of a raw track as
     * stored, a sector of a 2048-byte track rebuilt in full (sync, header, data, EDC, ECC).
     * {@code false} past the lead-out or for a 2336-byte track.
     */
    public boolean readRaw(int lsn, byte[] out) throws IOException {
        return readRawRun(lsn, 1, out, 0);
    }

    /**
     * {@code count} consecutive raw sectors from {@code lsn} into {@code out} at {@code at},
     * 2352 bytes each, all read as sectors of the track holding the first. A 2048-byte sector
     * is rebuilt with the header address counted from the start of that track's file; bytes
     * past the end of the file are left as they were. {@code false} if {@code lsn} is past the
     * lead-out or the track is not a 2048 or 2352-byte one.
     */
    public boolean readRawRun(int lsn, int count, byte[] out, int at) throws IOException {
        int[] rel = new int[1];
        Track t = findTrack(lsn, rel);
        if (t == null || t.source == null) return false;
        if (t.size != 2048 && t.size != 2352) return false;
        int sector = rel[0];
        for (int n = 0; n < count; n++, sector++, at += 2352) {
            long pos = t.offset + (long) sector * t.size;
            if (t.size == 2048) {
                java.util.Arrays.fill(out, at, at + 16, (byte) 0);
                t.source.read(pos, out, at + 16, 2048);
                CdSectorEncoder.encodeMode1(out, at, sector + 150);
            } else {
                t.source.read(pos, out, at, 2352);
            }
        }
        return true;
    }

    @Override
    public void close() throws IOException {
        for (TrackSource s : sources) s.close();
        sources.clear();
        if (container != null) container.close();
        container = null;
    }

    /**
     * True for a CD image: a {@code .cue} or {@code .iso}, or a zip holding one (a zip of
     * floppy disks is not).
     */
    public static boolean isDisc(Path path) {
        String l = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (isImageName(l)) return true;
        if (!l.endsWith(".zip")) return false;
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(path.toFile())) {
            return zip.stream().anyMatch(e -> isImageName(e.getName()));
        } catch (IOException e) {
            return false;
        }
    }

    /** True for a file name AmiJa opens as a CD image. */
    public static boolean isImageName(String fileName) {
        String l = fileName.toLowerCase(Locale.ROOT);
        return l.endsWith(".cue") || l.endsWith(".iso");
    }
}
