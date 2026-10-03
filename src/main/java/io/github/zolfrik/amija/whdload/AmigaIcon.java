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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal reader of AmigaDOS icons ({@code .info}), limited to what is needed here: the
 * <b>default tool</b> and the <b>tooltypes</b>.
 *
 * <p>In a WHDLoad package the game's icon is not decoration: it is the file that says
 * <i>how</i> the slave must be launched. The default tool is {@code WHDLoad} and the tooltypes
 * carry the arguments ({@code SLAVE=}, {@code DATA=}, {@code PRELOAD}, {@code CUSTOM1=},
 * {@code QUITKEY=}...). Without them many games stop at once: a package that keeps the images
 * in a subfolder declares {@code DATA=data}, and without that argument WHDLoad looks for the
 * files in the slave's folder and does not find them (DOS-Error #205).
 *
 * <p>File structure (Workbench 1.x-3.x): a 78-byte {@code DiskObject}, then in order (if the
 * matching pointers are not null) {@code DrawerData} (56 bytes), the gadget image, the
 * selected image, the default tool string, the tooltype list and the {@code ToolWindow}. Every
 * string is a 32-bit length (NUL included) followed by the bytes.
 */
final class AmigaIcon {

    /** Magic of the {@code DiskObject}. */
    private static final int DO_MAGIC = 0xE310;
    private static final int DISK_OBJECT_SIZE = 78;
    private static final int DRAWER_DATA_SIZE = 56;
    private static final int IMAGE_SIZE = 20;

    private final String defaultTool;
    private final List<String> toolTypes;

    private AmigaIcon(String defaultTool, List<String> toolTypes) {
        this.defaultTool = defaultTool;
        this.toolTypes = toolTypes;
    }

    /** Default tool of the icon (never {@code null}; empty if absent). */
    String defaultTool() { return defaultTool; }

    /** Tooltypes of the icon, in the order they appear. */
    List<String> toolTypes() { return toolTypes; }

    /**
     * Reads {@code data} as an AmigaDOS icon. Returns {@code null} if the file is not a
     * readable icon: here an unreadable icon is not an error, just a file with nothing to say
     * about launching the game.
     */
    static AmigaIcon parse(byte[] data) {
        if (data == null || data.length < DISK_OBJECT_SIZE) return null;
        if (u16(data, 0) != DO_MAGIC) return null;

        int gadgetRender = i32(data, 4 + 18);
        int selectRender = i32(data, 4 + 22);
        int pDefaultTool = i32(data, 50);
        int pToolTypes   = i32(data, 54);
        int pDrawerData  = i32(data, 66);

        int pos = DISK_OBJECT_SIZE;
        if (pDrawerData != 0) pos += DRAWER_DATA_SIZE;
        if (gadgetRender != 0) { pos = skipImage(data, pos); if (pos < 0) return null; }
        if (selectRender != 0) { pos = skipImage(data, pos); if (pos < 0) return null; }

        String tool = "";
        if (pDefaultTool != 0) {
            String[] s = readString(data, pos);
            if (s == null) return null;
            tool = s[0];
            pos = Integer.parseInt(s[1]);
        }

        List<String> types = new ArrayList<>();
        if (pToolTypes != 0) {
            if (pos + 4 > data.length) return null;
            // The stored field is (number of tooltypes + 1) * 4: the size of the
            // NULL-terminated pointer array, not the count.
            long raw = u32(data, pos);
            pos += 4;
            long count = raw / 4 - 1;
            if (count < 0 || count > 4096) return null;
            for (long i = 0; i < count; i++) {
                String[] s = readString(data, pos);
                if (s == null) break;
                types.add(s[0]);
                pos = Integer.parseInt(s[1]);
            }
        }
        return new AmigaIcon(tool, types);
    }

    /** Skips a {@code struct Image} and its bitplanes. */
    private static int skipImage(byte[] d, int pos) {
        if (pos + IMAGE_SIZE > d.length) return -1;
        int width  = u16(d, pos + 4);
        int height = u16(d, pos + 6);
        int depth  = u16(d, pos + 8);
        long words = ((long) width + 15) / 16;
        long bytes = words * 2 * height * depth;
        long end = (long) pos + IMAGE_SIZE + bytes;
        if (end > d.length || end < 0) return -1;
        return (int) end;
    }

    /**
     * Reads a string (32-bit length including NUL, then the bytes). Returns
     * {@code {value, nextPosition}} or {@code null} if it does not fit.
     */
    private static String[] readString(byte[] d, int pos) {
        if (pos + 4 > d.length) return null;
        long len = u32(d, pos);
        if (len < 1 || pos + 4 + len > d.length) return null;
        int n = (int) len - 1;      // excludes the trailing NUL
        String s = new String(d, pos + 4, n, StandardCharsets.ISO_8859_1);
        return new String[] { s, Integer.toString(pos + 4 + (int) len) };
    }

    private static int u16(byte[] d, int o) { return ((d[o] & 0xFF) << 8) | (d[o + 1] & 0xFF); }
    private static int i32(byte[] d, int o) {
        return ((d[o] & 0xFF) << 24) | ((d[o + 1] & 0xFF) << 16)
             | ((d[o + 2] & 0xFF) << 8) | (d[o + 3] & 0xFF);
    }
    private static long u32(byte[] d, int o) { return i32(d, o) & 0xFFFFFFFFL; }
}
