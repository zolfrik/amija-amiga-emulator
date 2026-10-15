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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The AmiJa boot ROM, included in the jar.
 *
 * It is original firmware written for this project (source in {@code rom/}), not a Commodore
 * Kickstart: it can therefore be redistributed with the emulator. It lets the Amiga start even
 * for those who own no Kickstart, and boots floppy games with a self-contained bootblock.
 *
 * It is not a full replacement for the Kickstart: it does not implement AmigaDOS, so disks
 * that rely on {@code dos.library} still need a Commodore ROM.
 */
public final class BuiltinRom {

    /** Classpath location of the image produced by {@code romgen/RomBuilder}. */
    public static final String RESOURCE = "/rom/amija.rom";

    /**
     * Start of the version tag embedded in the image, in the form
     * {@code "$VER: AmiJa ROM 1.0 (dd.mm.yyyy)"} with which the Amiga marks its binaries.
     * {@code romgen/RomVersion} writes it right after the reset vectors.
     */
    private static final String VERSION_TAG = "$VER: ";

    /** What the ROM is called when the tag is missing (an image older than 1.0). */
    private static final String UNVERSIONED = "ROM AmiJa";

    private static byte[] cached;
    private static String version;

    private BuiltinRom() {}

    /** True if the image is present in the jar. */
    public static boolean isAvailable() {
        try (InputStream in = BuiltinRom.class.getResourceAsStream(RESOURCE)) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Loads the ROM image. The result is cached: a cold restart reads it again and there is no
     * point paying the extraction from the jar twice.
     *
     * @throws IOException if the image is not in the jar, which means the build is
     *                     incomplete
     */
    public static synchronized byte[] load() throws IOException {
        if (cached != null) return cached.clone();
        try (InputStream in = BuiltinRom.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IOException("ROM AmiJa non presente nel jar (" + RESOURCE + ")");
            }
            cached = in.readAllBytes();
        }
        return cached.clone();
    }

    /**
     * How the included ROM presents itself, e.g. {@code "AmiJa ROM 1.0"}: the image's
     * {@code $VER} tag, without the date.
     *
     * So whoever reports a problem can say with which ROM they saw it: the number is written in
     * the image, not in a Java constant, so it cannot describe a ROM other than the one really
     * mounted.
     */
    public static synchronized String version() {
        if (version != null) return version;
        version = UNVERSIONED;
        try {
            byte[] rom = load();
            int at = indexOf(rom, VERSION_TAG.getBytes(StandardCharsets.ISO_8859_1));
            if (at >= 0) {
                int start = at + VERSION_TAG.length();
                int end = start;
                while (end < rom.length && rom[end] != 0) end++;
                String tag = new String(rom, start, end - start, StandardCharsets.ISO_8859_1);
                int date = tag.indexOf(" (");
                String name = (date > 0 ? tag.substring(0, date) : tag).trim();
                if (!name.isEmpty()) version = name;
            }
        } catch (IOException e) {
            // Image missing: the caller will find out from load(), here it is enough not to
            // fail a log line.
        }
        return version;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
