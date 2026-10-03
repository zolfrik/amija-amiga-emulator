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

package io.github.zolfrik.amija.display.render.gl;

import io.github.zolfrik.amija.common.AppHome;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * List of the shader presets installed under {@code shaders/} in the application home, and
 * conversion between the <b>name</b> that goes into {@code amiga.properties}
 * ({@code video.shader}) and the file on disk.
 *
 * <p>The name is the path <b>relative</b> to {@code shaders/}, with {@code /} separators and no
 * extension (e.g. {@code Mega_Bezel/Presets/MBZ__5__POTATO}). A preset in the root of
 * {@code shaders/} has its file name as its name; the recursive search is an extension of that.
 * Both extensions are recognised, {@code .glslp} and {@code .slangp}; if two files exist with
 * the same name and different extensions, {@code .slangp} wins.
 */
public final class ShaderCatalog {

    /** Value of {@code video.shader} that means "no shader". */
    public static final String NONE = "none";

    private static final String[] EXTENSIONS = {".slangp", ".glslp"};
    /** Maximum depth and count explored: a pathological folder must not block the UI. */
    private static final int MAX_DEPTH = 8;
    private static final int MAX_ENTRIES = 4000;

    private ShaderCatalog() { }

    /**
     * Names of the presets found under {@code shaders/}, sorted by path (case-insensitive). It
     * does not include {@link #NONE}: the caller adds it.
     */
    public static List<String> names() {
        List<String> out = new ArrayList<>();
        Path root = AppHome.resolve("shaders");
        if (!Files.isDirectory(root)) return out;
        try {
            Files.walkFileTree(root, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class),
                    MAX_DEPTH, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String ext = extensionOf(file);
                    if (ext != null) {
                        String rel = root.relativize(file).toString().replace('\\', '/');
                        out.add(rel.substring(0, rel.length() - ext.length()));
                    }
                    return out.size() >= MAX_ENTRIES
                            ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;   // unreadable file: carry on
                }
            });
        } catch (IOException e) {
            System.err.println("[GL] Scansione di " + root + " fallita: " + e.getMessage());
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /**
     * Path of the preset with that name, or {@code null} if the name is empty, {@link #NONE},
     * or matches no file.
     */
    public static Path resolve(String name) {
        if (name == null) return null;
        String n = name.trim();
        if (n.isEmpty() || n.equalsIgnoreCase(NONE)) return null;
        for (String ext : EXTENSIONS) {
            // Paths.get accepts '/' as separator on Windows too.
            Path p = AppHome.resolve("shaders", n + ext);
            if (Files.isRegularFile(p)) return p;
        }
        return null;
    }

    /** The file's recognised extension, or {@code null} if it is not a preset. */
    private static String extensionOf(Path file) {
        String n = file.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String ext : EXTENSIONS) {
            if (n.endsWith(ext)) return ext;
        }
        return null;
    }
}
