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

import io.github.zolfrik.amija.hardware.common.cpu.CpuModel;
import io.github.zolfrik.amija.hardware.custom.Chipset;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The game database: the games whose Auto profile is known to be wrong, and what they need
 * instead. Auto mode derives the machine from the medium (floppy, WHDLoad, AGA); an entry here
 * corrects that machine for one game in one format and nothing else, and only in Auto mode.
 *
 * <p>The data is the text resource {@value #RESOURCE}, one entry per line:
 * <pre>
 * # comment
 * &lt;format&gt;  &lt;key&gt;  &lt;setting&gt;=&lt;value&gt; ...   # why
 * whdload   Speedball2  cpu=68000
 * </pre>
 * Formats and keys:
 * <ul>
 *   <li>{@code whdload}: the name of the slave, without folder and without {@code .slave},
 *       compared ignoring case. The slave names the game whatever the archive is called and
 *       whatever version of the install it comes from.</li>
 *   <li>{@code floppy}: the title of the disk image, as {@link #floppyKey} makes it from the
 *       file name: everything before the first parenthesis or bracket, lower case, letters and
 *       digits only ({@code FIFA International Soccer (Europe) (En,Fr,De).zip} and
 *       {@code FIFA International Soccer (1994)(Electronic Arts)(Disk 1 of 3).adf} are both
 *       {@code fifainternationalsoccer}). Every disk in the drives is tried.</li>
 * </ul>
 * Settings:
 * <ul>
 *   <li>{@code cpu}: {@code 68000}, {@code 68010} or {@code 68020};</li>
 *   <li>{@code chipset}: {@code OCS}, {@code ECS} or {@code AGA}; {@code AGA} builds the
 *       A1200 profile, as a slave declaring {@code WHDLF_ReqAGA} does;</li>
 *   <li>{@code preload}: {@code on} or {@code off}, WHDLoad only: whether WHDLoad is launched
 *       with {@code PRELOAD}, which copies the game's files from memory at once;</li>
 *   <li>{@code fastram}: Fast RAM in KB, e.g. {@code 2048}.</li>
 * </ul>
 * An entry wins over everything Auto derives, the slave's own flags included: it is a measured
 * fact about that game. A malformed line is reported once and skipped.
 */
public final class GameDatabase {

    /** Class-path location of the database. */
    public static final String RESOURCE = "/gamedb/exceptions.txt";

    /** The media formats an entry can name. */
    public enum Format {
        WHDLOAD("whdload"),
        FLOPPY("floppy");

        final String token;

        Format(String token) {
            this.token = token;
        }

        static Format parse(String token) {
            for (Format f : values()) {
                if (f.token.equalsIgnoreCase(token)) return f;
            }
            return null;
        }
    }

    /**
     * One game's corrections to the Auto profile; a {@code null} field leaves Auto's choice.
     *
     * @param format  the medium the entry applies to
     * @param key     the game, as the format names it
     * @param cpu     processor to build, or {@code null}
     * @param chipset chipset to build, or {@code null}
     * @param preload WHDLoad's {@code PRELOAD}, or {@code null}
     * @param fastRamKb Fast RAM in KB, or {@code null}
     */
    public record Entry(Format format, String key, CpuModel cpu, Chipset chipset, Boolean preload,
                        Integer fastRamKb) {

        /** The entry in the database's own syntax, e.g. {@code whdload Speedball2 cpu=68000}. */
        public String describe() {
            List<String> parts = new ArrayList<>();
            parts.add(format.token);
            parts.add(key);
            if (cpu != null) parts.add("cpu=" + cpu.toString().replace("E", ""));
            if (chipset != null) parts.add("chipset=" + chipset.name());
            if (preload != null) parts.add("preload=" + (preload ? "on" : "off"));
            if (fastRamKb != null) parts.add("fastram=" + fastRamKb);
            return String.join(" ", parts);
        }
    }

    private static volatile GameDatabase builtIn;

    private final Map<String, Entry> entries;

    /** Matches already announced on the console, so each is printed once per run. */
    private final Set<String> announced = Collections.synchronizedSet(new HashSet<>());

    private GameDatabase(Map<String, Entry> entries) {
        this.entries = entries;
    }

    /** The database shipped with AmiJa, read once. */
    public static GameDatabase builtIn() {
        GameDatabase db = builtIn;
        if (db == null) {
            synchronized (GameDatabase.class) {
                db = builtIn;
                if (db == null) {
                    db = loadResource();
                    builtIn = db;
                }
            }
        }
        return db;
    }

    private static GameDatabase loadResource() {
        try (InputStream in = GameDatabase.class.getResourceAsStream(RESOURCE)) {
            if (in == null) return new GameDatabase(Map.of());
            return parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("[GAMEDB] Failed to read " + RESOURCE + ": " + e.getMessage());
            return new GameDatabase(Map.of());
        }
    }

    /** Reads a database in the syntax described in the class. Bad lines are reported and skipped. */
    public static GameDatabase parse(Reader text) throws IOException {
        Map<String, Entry> entries = new HashMap<>();
        BufferedReader in = new BufferedReader(text);
        String line;
        int number = 0;
        while ((line = in.readLine()) != null) {
            number++;
            int hash = line.indexOf('#');
            String body = (hash < 0 ? line : line.substring(0, hash)).trim();
            if (body.isEmpty()) continue;
            Entry entry = parseLine(body);
            if (entry == null) {
                System.err.println("[GAMEDB] Line " + number + " ignored: " + line.trim());
                continue;
            }
            entries.put(mapKey(entry.format(), entry.key()), entry);
        }
        return new GameDatabase(Map.copyOf(entries));
    }

    private static Entry parseLine(String body) {
        String[] f = body.split("\\s+");
        if (f.length < 3) return null;
        Format format = Format.parse(f[0]);
        if (format == null) return null;
        CpuModel cpu = null;
        Chipset chipset = null;
        Boolean preload = null;
        Integer fastRamKb = null;
        for (int i = 2; i < f.length; i++) {
            int eq = f[i].indexOf('=');
            if (eq <= 0) return null;
            String name = f[i].substring(0, eq).toLowerCase(Locale.ROOT);
            String value = f[i].substring(eq + 1).toUpperCase(Locale.ROOT);
            switch (name) {
                case "cpu" -> {
                    cpu = switch (value) {
                        case "68000" -> CpuModel.M68000E;
                        case "68010" -> CpuModel.M68010E;
                        case "68020" -> CpuModel.M68020;
                        default -> null;
                    };
                    if (cpu == null) return null;
                }
                case "chipset" -> {
                    try {
                        chipset = Chipset.valueOf(value);
                    } catch (IllegalArgumentException e) {
                        return null;
                    }
                }
                case "preload" -> {
                    if (format != Format.WHDLOAD) return null;
                    if (value.equals("ON")) preload = true;
                    else if (value.equals("OFF")) preload = false;
                    else return null;
                }
                case "fastram" -> {
                    try {
                        fastRamKb = Integer.parseInt(value);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                    if (fastRamKb < 0) return null;
                }
                default -> {
                    return null;
                }
            }
        }
        return new Entry(format, f[1], cpu, chipset, preload, fastRamKb);
    }

    /**
     * The {@code floppy} key of a disk image: the title its file name starts with, lower case,
     * letters and digits only; {@code null} when nothing is left. A disk inside a zip
     * ({@code <zip>!<n>}) is named by the zip.
     */
    public static String floppyKey(String spec) {
        if (spec == null) return null;
        String name = spec.trim();
        name = name.replaceFirst("!\\d+$", "");
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        int cut = name.length();
        for (char c : new char[] { '(', '[' }) {
            int i = name.indexOf(c);
            if (i >= 0) cut = Math.min(cut, i);
        }
        if (cut == name.length()) {
            int dot = name.lastIndexOf('.');
            if (dot > 0) cut = dot;
        }
        String key = name.substring(0, cut).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return key.isEmpty() ? null : key;
    }

    private static String mapKey(Format format, String key) {
        return format.token + ' ' + key.toLowerCase(Locale.ROOT);
    }

    /** Every entry, in no particular order. */
    public Collection<Entry> entries() {
        return entries.values();
    }

    /** The entry for {@code key} in {@code format}, or {@code null}. */
    public Entry find(Format format, String key) {
        if (key == null) return null;
        return entries.get(mapKey(format, key));
    }

    /**
     * The entry for the first of {@code keys} found in {@code format}, or {@code null}. A
     * match is announced on the console the first time it is made.
     */
    public Entry find(Format format, List<String> keys) {
        for (String key : keys) {
            Entry e = find(format, key);
            if (e != null) {
                if (announced.add(mapKey(format, key))) {
                    System.out.println("[GAMEDB] " + e.describe());
                }
                return e;
            }
        }
        return null;
    }
}
