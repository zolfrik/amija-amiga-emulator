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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The folder of Kickstart ROMs Auto mode picks from. Every {@code .rom} file in it, subfolders
 * included, is recognised from its contents and given the role it can play; for each role the
 * best candidate is chosen.
 *
 * <p>Two operations with very different costs: {@link #fingerprint(Path)} only lists the
 * files (names, sizes, dates) and tells whether the folder changed since the last analysis;
 * {@link #scan(Path)} reads every ROM.
 *
 * <p>Choice among candidates of one role: a ROM whose normalised image has the CRC32 of the
 * known good dump comes first, then the other images carrying the right revision; ties go to
 * the path order. Images with a wrong checksum are skipped, and so are the ROMs of other
 * machines that share a revision (the A3000's 1.3, the A3000 and A4000 3.1 40.68), which are
 * recognised by their CRC32.
 */
public final class KickstartFolder {

    /** What a ROM can be used for. */
    public enum Role {
        /** Kickstart 1.3 (34.5): floppy games. */
        KS13(0xc4f0f55fL),
        /** Kickstart 3.1 for the A500/A600/A2000 (40.63): hard disk and WHDLoad. */
        KS31(0xfc24ae0dL),
        /** Kickstart 3.1 for the A1200 (40.68): AGA software. */
        KS31_A1200(0x1483a091L),
        /** The CD32's Kickstart (40.60). */
        CD32(0x1e62d4a5L),
        /** The CD32's extended ROM (40.60). */
        CD32_EXT(0x87746be2L);

        /** CRC32 of the known good dump. */
        public final long knownCrc;

        Role(long knownCrc) {
            this.knownCrc = knownCrc;
        }
    }

    /**
     * CRC32 of ROMs that carry the revision of a role but belong to another machine: the
     * A3000's 1.3, the A3000's 3.1 40.68 and the A4000's (plain and Cloanto).
     */
    private static final Set<Long> OTHER_MACHINES =
            Set.of(0xe0f37258L, 0xefb239ccL, 0xd6bae334L, 0x43b6dd22L);

    /** Files larger than this are not ROMs (a 1 MB dump plus a loader header at most). */
    private static final long MAX_FILE_SIZE = 1024 * 1024 + 1024;

    /** A ROM chosen for a role. */
    public record Found(Path path, boolean known, String revision) {
        /** The file name, for the user. */
        public String fileName() {
            return path.getFileName().toString();
        }
    }

    /** Outcome of an analysis: the folder's fingerprint and the ROM chosen for each role. */
    public record Result(String fingerprint, Map<Role, Found> found) {
        public Found get(Role role) {
            return found.get(role);
        }
    }

    private KickstartFolder() {
    }

    /**
     * Fingerprint of the folder: its path and, for every ROM file, relative path, size and
     * date. Cheap: nothing is read. {@code "none"} when the folder does not exist.
     */
    public static String fingerprint(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return "none";
        StringBuilder sb = new StringBuilder(dir.toAbsolutePath().normalize().toString()).append('\n');
        for (Path p : romFiles(dir)) {
            try {
                sb.append(dir.relativize(p)).append('|').append(Files.size(p)).append('|')
                  .append(Files.getLastModifiedTime(p).toMillis()).append('\n');
            } catch (IOException e) {
                sb.append(dir.relativize(p)).append("|?\n");
            }
        }
        try {
            byte[] h = MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 12; i++) hex.append(String.format("%02x", h[i]));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(sb.toString().hashCode());
        }
    }

    /** Reads every ROM of the folder and chooses one for each role it can fill. */
    public static Result scan(Path dir) {
        Map<Role, Found> best = new EnumMap<>(Role.class);
        if (dir == null || !Files.isDirectory(dir)) return new Result("none", best);
        String fingerprint = fingerprint(dir);
        for (Path p : romFiles(dir)) {
            KickstartImage img;
            try {
                if (Files.size(p) > MAX_FILE_SIZE) continue;
                img = KickstartImage.load(p);
            } catch (IOException | RuntimeException e) {
                continue;
            }
            Role role = roleOf(img);
            if (role == null) continue;
            Found f = new Found(p, img.crc32() == role.knownCrc,
                    img.romVersion() + "." + img.romRevision());
            Found current = best.get(role);
            // Files are visited in path order: a later one wins only by being the known dump.
            if (current == null || (f.known() && !current.known())) best.put(role, f);
        }
        return new Result(fingerprint, best);
    }

    /** The role an image can play, or null. */
    static Role roleOf(KickstartImage img) {
        if (img.isCd32ExtendedRom()) return Role.CD32_EXT;
        if (!img.isSocketRom() || !img.isChecksumValid()) return null;
        if (OTHER_MACHINES.contains(img.crc32())) return null;
        int v = img.romVersion(), r = img.romRevision();
        if (v == 34 && r == 5) return Role.KS13;
        if (v == 40 && r == 63) return Role.KS31;
        if (v == 40 && r == 68) return Role.KS31_A1200;
        if (img.isCd32Kickstart()) return Role.CD32;
        return null;
    }

    /** The {@code .rom} files of the folder and its subfolders, in path order. */
    private static List<Path> romFiles(Path dir) {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(dir)) {
            s.filter(Files::isRegularFile)
             .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".rom"))
             .forEach(out::add);
        } catch (IOException | RuntimeException e) {
            return out;
        }
        Collections.sort(out);
        return out;
    }
}
