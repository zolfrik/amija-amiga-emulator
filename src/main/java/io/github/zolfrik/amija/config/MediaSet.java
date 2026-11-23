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

package io.github.zolfrik.amija.config;

import io.github.zolfrik.amija.media.DiskImage;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * The media of the machine, in the exact form they take in {@code amiga.properties}: the
 * four floppy drives, the hard disk units and the WHDLoad archive.
 *
 * <p>Besides being the currency exchanged between the views of the F12 popup, it holds the
 * rules that turn a plain list of files into drives ({@link #fromList}): the type comes from
 * the extension, floppies fill DF0 upward, hard disks the units in order, and only the
 * drives needed are connected. The "Drives" list of Auto mode and the files given on the
 * command line go through the same rules.
 */
public final class MediaSet {

    /** Media type, recognised from the file extension. */
    public enum Kind {
        FLOPPY(4, "adf", "ipf", "zip"),
        HARDDISK(AmigaConfig.HD_UNITS.length, "hdf"),
        WHDLOAD(1, "lha", "lzh"),
        /** A CD32 disc: a .cue or .iso, or a zip holding one. */
        CD(1, "cue", "iso", "zip");

        /** How many of this type the machine can mount at once. */
        public final int max;
        /** File extensions, lower case, without the dot. */
        public final String[] extensions;

        Kind(int max, String... extensions) {
            this.max = max;
            this.extensions = extensions;
        }

        /** The type of {@code path}, or {@code null} if the extension is none of ours. */
        public static Kind of(String path) {
            // A multi-disk zip is addressed as <zip>!<n>: the archive is what counts. Only that
            // exact suffix is removed: a '!' elsewhere belongs to the name ("[!]" marks a
            // verified dump).
            String file;
            try {
                file = DiskImage.pathOf(path).toString();
            } catch (RuntimeException e) {
                file = path;
            }
            String name = file.toLowerCase(Locale.ROOT);
            // A zip is a CD32 disc when it holds a .cue or an .iso, floppies otherwise.
            if (name.endsWith(".zip")) {
                try {
                    return io.github.zolfrik.amija.media.CdImage.isDisc(java.nio.file.Path.of(file))
                            ? CD : FLOPPY;
                } catch (RuntimeException e) {
                    return FLOPPY;
                }
            }
            for (Kind k : values()) {
                for (String ext : k.extensions) {
                    if (name.endsWith("." + ext)) return k;
                }
            }
            return null;
        }
    }

    /** One file of a list, with its type and whether it is mounted read-only. */
    public record Entry(Kind kind, String path, boolean readOnly) {}

    public final String[] floppyDisk = new String[4];
    public final boolean[] floppyConnected = new boolean[4];
    public final boolean[] floppyReadOnly = new boolean[4];
    public final String[] hdDisk = new String[AmigaConfig.HD_UNITS.length];
    public final boolean[] hdConnected = new boolean[AmigaConfig.HD_UNITS.length];
    public final boolean[] hdReadOnly = new boolean[AmigaConfig.HD_UNITS.length];
    public boolean whdloadEnabled;
    public String whdloadFile = "";
    /** The CD32 disc; empty if none. */
    public String cdImage = "";

    public static MediaSet fromConfig(AmigaConfig c) {
        MediaSet s = new MediaSet();
        for (int i = 0; i < 4; i++) {
            s.floppyDisk[i] = c.driveDisk(i);
            s.floppyConnected[i] = c.driveEnabled(i);
            s.floppyReadOnly[i] = c.driveReadOnly(i);
        }
        for (int u = 0; u < s.hdDisk.length; u++) {
            s.hdDisk[u] = c.hdDisk(u);
            s.hdConnected[u] = c.hdEnabled(u);
            s.hdReadOnly[u] = c.hdReadOnly(u);
        }
        s.whdloadEnabled = c.whdloadEnabled();
        s.whdloadFile = c.whdloadFile() == null ? "" : c.whdloadFile();
        s.cdImage = c.cdImage() == null ? "" : c.cdImage();
        return s;
    }

    public void toConfig(AmigaConfig c) {
        for (int i = 0; i < 4; i++) {
            c.setDriveEnabled(i, floppyConnected[i]);
            c.setDriveDisk(i, text(floppyDisk[i]));
            c.setDriveReadOnly(i, floppyReadOnly[i] || DiskImage.isZipDisk(text(floppyDisk[i])));
        }
        for (int u = 0; u < hdDisk.length; u++) {
            c.setHdEnabled(u, hdConnected[u]);
            c.setHdDisk(u, text(hdDisk[u]));
            c.setHdReadOnly(u, hdReadOnly[u]);
        }
        c.setWhdloadEnabled(whdloadEnabled);
        c.setWhdloadFile(text(whdloadFile));
        c.setCdImage(text(cdImage));
    }

    /** {@code s} trimmed, or the empty string for {@code null}. */
    public static String text(String s) {
        return s == null ? "" : s.trim();
    }

    /**
     * Spreads a list of files over the machine's drives, in list order: floppies to DF0..DF3,
     * hard disks to the units in order, the WHDLoad archive to WHDLoad. Entries beyond what
     * a type allows are ignored; {@link #check} says whether a list fits. A floppy entry
     * with an empty path is a connected drive with no disk inside.
     */
    public static MediaSet fromList(List<Entry> entries) {
        MediaSet s = new MediaSet();
        int floppies = 0;
        int hds = 0;
        for (Entry item : entries) {
            switch (item.kind()) {
                case FLOPPY -> {
                    if (floppies >= 4) break;
                    s.floppyDisk[floppies] = item.path();
                    s.floppyReadOnly[floppies] = item.readOnly();
                    floppies++;
                }
                case HARDDISK -> {
                    if (hds >= s.hdDisk.length) break;
                    s.hdDisk[hds] = item.path();
                    s.hdConnected[hds] = true;
                    s.hdReadOnly[hds] = item.readOnly();
                    hds++;
                }
                case WHDLOAD -> {
                    s.whdloadEnabled = true;
                    s.whdloadFile = item.path();
                }
                case CD -> s.cdImage = item.path();
            }
        }
        // One connected drive per disk, and as many for the disks of a multi-disk zip in
        // DF0, if any (they are spread at start-up, but only over the drives that exist).
        //
        // With no floppy in the list the drives stay disconnected, exactly like a machine
        // configured by hand with no drive ticked: booting a hard disk or WHDLoad needs no
        // drive, and having one there only costs the time the Kickstart spends polling it.
        // The exception is a completely empty list: there DF0 stays connected because it is
        // the only possible target of a disk dragged onto the window, and without it
        // drag-and-drop would have nowhere to land (a drop on a drive that is not connected
        // is refused). A CD32 disc makes the machine a CD32, which has no floppy drive.
        //
        // A drive left empty is not requested: the drives go up to the last one that has a
        // disk, and the empty ones before it stay connected because without them that one
        // would not exist.
        int lastDisk = -1;
        for (int i = 0; i < 4; i++) {
            if (!text(s.floppyDisk[i]).isEmpty()) lastDisk = i;
        }
        int connected = lastDisk + 1;
        if (lastDisk >= 0) connected = Math.max(connected, zipDiskCount(s.floppyDisk[0]));
        if (entries.isEmpty()) connected = 1;
        if (!text(s.cdImage).isEmpty()) connected = 0;
        for (int i = 0; i < Math.min(4, connected); i++) s.floppyConnected[i] = true;
        return s;
    }

    /**
     * Checks that a list of types can be mounted: no more of a type than the machine has
     * room for, not hard disk and WHDLoad together, since both would be the boot volume, and
     * a CD32 disc alone, since it makes the machine a CD32.
     *
     * @throws IllegalArgumentException saying what does not fit
     */
    public static void check(List<Kind> kinds) {
        int[] count = new int[Kind.values().length];
        for (Kind k : kinds) count[k.ordinal()]++;
        for (Kind k : Kind.values()) {
            if (count[k.ordinal()] > k.max) {
                throw new IllegalArgumentException("too many " + describe(k) + " files: "
                        + count[k.ordinal()] + ", at most " + k.max);
            }
        }
        if (count[Kind.HARDDISK.ordinal()] > 0 && count[Kind.WHDLOAD.ordinal()] > 0) {
            throw new IllegalArgumentException(
                    "a hard disk and a WHDLoad archive cannot be used together");
        }
        if (count[Kind.CD.ordinal()] > 0 && kinds.size() > count[Kind.CD.ordinal()]) {
            throw new IllegalArgumentException(
                    "a CD32 disc makes the machine a CD32: it cannot be used with other media");
        }
    }

    private static String describe(Kind k) {
        return switch (k) {
            case FLOPPY -> "floppy (.adf/.ipf/.zip)";
            case HARDDISK -> "hard disk (.hdf)";
            case WHDLOAD -> "WHDLoad (.lha/.lzh)";
            case CD -> "CD32 disc (.cue/.iso/.zip)";
        };
    }

    /** How many disks the archive contains, 1 if it is not a multi-disk zip. */
    public static int zipDiskCount(String path) {
        if (!DiskImage.isZipDisk(path)) return 1;
        try {
            return Math.max(1, DiskImage.listZipDiskEntries(DiskImage.pathOf(path)).size());
        } catch (IOException e) {
            return 1;
        }
    }
}
