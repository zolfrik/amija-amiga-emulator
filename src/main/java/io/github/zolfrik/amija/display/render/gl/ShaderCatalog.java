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
 * Elenco dei preset shader installati sotto {@code shaders/} nella home applicativa, e
 * conversione fra il <b>nome</b> che finisce in {@code amiga.properties}
 * ({@code video.shader}) e il file su disco.
 *
 * <p>Il nome è il percorso <b>relativo</b> a {@code shaders/}, con separatori {@code /} e
 * senza estensione — es. {@code Mega_Bezel/Presets/MBZ__5__POTATO}. Prima dell'arrivo dei
 * {@code .slangp} i preset stavano tutti nella radice e il nome era il solo nome file:
 * quei valori continuano a risolversi identici, la ricerca ricorsiva è una estensione.
 * Sono riconosciute entrambe le estensioni, {@code .glslp} e {@code .slangp}; se esistono
 * due file con lo stesso nome e diversa estensione vince il {@code .slangp}.
 */
public final class ShaderCatalog {

    /** Valore di {@code video.shader} che significa "nessuno shader". */
    public static final String NONE = "none";

    private static final String[] EXTENSIONS = {".slangp", ".glslp"};
    /** Profondità e numero massimi esplorati: una cartella patologica non deve bloccare la UI. */
    private static final int MAX_DEPTH = 8;
    private static final int MAX_ENTRIES = 4000;

    private ShaderCatalog() { }

    /**
     * Nomi dei preset trovati sotto {@code shaders/}, ordinati per percorso (case
     * insensitive). Non include {@link #NONE}: lo aggiunge il chiamante.
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
                    return FileVisitResult.CONTINUE;   // file illeggibile: si prosegue
                }
            });
        } catch (IOException e) {
            System.err.println("[GL] Scansione di " + root + " fallita: " + e.getMessage());
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /**
     * Percorso del preset con quel nome, o {@code null} se il nome è vuoto,
     * {@link #NONE}, o non corrisponde ad alcun file.
     */
    public static Path resolve(String name) {
        if (name == null) return null;
        String n = name.trim();
        if (n.isEmpty() || n.equalsIgnoreCase(NONE)) return null;
        for (String ext : EXTENSIONS) {
            // Paths.get accetta '/' come separatore anche su Windows.
            Path p = AppHome.resolve("shaders", n + ext);
            if (Files.isRegularFile(p)) return p;
        }
        return null;
    }

    /** L'estensione riconosciuta del file, o {@code null} se non è un preset. */
    private static String extensionOf(Path file) {
        String n = file.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String ext : EXTENSIONS) {
            if (n.endsWith(ext)) return ext;
        }
        return null;
    }
}
