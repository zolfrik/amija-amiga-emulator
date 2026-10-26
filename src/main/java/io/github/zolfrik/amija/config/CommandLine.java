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

import io.github.zolfrik.amija.custom.DiskImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The command line of the emulator, so that another program (a frontend) can launch it with
 * any configuration without editing {@code amiga.properties}.
 *
 * <pre>
 *   AmiJa [options] [file ...] [key=value ...]
 *
 *   file               a floppy, hard disk or WHDLoad file to insert
 *   key=value          sets any key of amiga.properties for this session only
 *   --config FILE      reads (and saves) FILE instead of amiga.properties in the home
 *   --home DIR         the application home (same as -Damija.home=DIR)
 *   --print-config     prints the resulting configuration and exits
 *   --help, -h         prints this help and exits
 * </pre>
 *
 * <p>An argument is a {@code key=value} when what precedes its first {@code =} looks like a
 * key (letters, digits, dots, dashes, underscores); anything else that is not an option is
 * a file, so a path containing {@code =} is still a file. The value may itself contain
 * {@code =}, and may be empty ({@code drive.df0.disk=} empties the drive). A later
 * {@code key=value} wins over an earlier one. Options take their argument either as the
 * next word or after {@code =}.
 *
 * <p><b>Files replace the media set.</b> When at least one is given, they are spread over
 * the drives by {@link MediaSet#fromList}, exactly as the "Drives" list of the F12 popup
 * does, and every drive, unit and WHDLoad slot they do not fill is emptied. A
 * {@code key=value} on a media key still wins over what the files decided.
 *
 * <p>Parsing does not touch the file system nor {@code AppHome}: {@code --home} must be
 * applied before {@code AppHome} is first used, and whether the files exist is checked by
 * {@link #missingMediaFiles()}; both are the caller's job.
 */
public final class CommandLine {

    /** A command line with no arguments. */
    public static final CommandLine EMPTY = new CommandLine(
            List.of(), null, null, false, false, List.of(), Map.of());

    /** What a configuration key looks like; anything else before {@code =} is a path. */
    private static final Pattern KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]*");

    private final List<String> args;
    private final Path configFile;
    private final String home;
    private final boolean help;
    private final boolean printConfig;
    private final List<String> mediaFiles;
    private final Map<String, String> overrides;

    private CommandLine(List<String> args, Path configFile, String home, boolean help,
            boolean printConfig, List<String> mediaFiles, Map<String, String> overrides) {
        this.args = args;
        this.configFile = configFile;
        this.home = home;
        this.help = help;
        this.printConfig = printConfig;
        this.mediaFiles = mediaFiles;
        this.overrides = overrides;
    }

    /**
     * Parses the arguments of {@code main}.
     *
     * @throws IllegalArgumentException with a message for the user when an argument is
     *         malformed, a file is not a media type, or the files do not fit the machine
     */
    public static CommandLine parse(String... argv) {
        Path configFile = null;
        String home = null;
        boolean help = false;
        boolean printConfig = false;
        List<String> mediaFiles = new ArrayList<>();
        List<MediaSet.Kind> kinds = new ArrayList<>();
        Map<String, String> overrides = new LinkedHashMap<>();
        for (int i = 0; i < argv.length; i++) {
            String arg = argv[i];
            if (arg.startsWith("-")) {
                int eq = arg.indexOf('=');
                String name = eq < 0 ? arg : arg.substring(0, eq);
                switch (name) {
                    case "-h", "--help" -> help = true;
                    case "--print-config" -> printConfig = true;
                    case "--config", "--home" -> {
                        String value;
                        if (eq >= 0) {
                            value = arg.substring(eq + 1);
                        } else if (i + 1 < argv.length) {
                            value = argv[++i];
                        } else {
                            throw new IllegalArgumentException(name + " needs a path");
                        }
                        if (value.isBlank()) {
                            throw new IllegalArgumentException(name + " needs a path");
                        }
                        if (name.equals("--config")) {
                            configFile = toAbsolute(value, name);
                        } else {
                            home = toAbsolute(value, name).toString();
                        }
                    }
                    default -> throw new IllegalArgumentException("unknown option: " + arg);
                }
                continue;
            }
            int eq = arg.indexOf('=');
            if (eq > 0 && KEY.matcher(arg.substring(0, eq)).matches()) {
                String key = arg.substring(0, eq);
                overrides.remove(key); // the last one wins, in its own position
                overrides.put(key, arg.substring(eq + 1));
                continue;
            }
            if (arg.isBlank()) {
                throw new IllegalArgumentException("empty argument");
            }
            MediaSet.Kind kind = MediaSet.Kind.of(arg.trim());
            if (kind == null) {
                throw new IllegalArgumentException("not a key=value nor a media file "
                        + "(.adf .ipf .zip .hdf .lha .lzh): " + arg);
            }
            kinds.add(kind);
            mediaFiles.add(toAbsolute(arg, "file").toString());
        }
        MediaSet.check(kinds);
        return new CommandLine(List.of(argv), configFile, home, help, printConfig,
                Collections.unmodifiableList(mediaFiles), Collections.unmodifiableMap(overrides));
    }

    /**
     * Absolute against the working directory at launch: the path must keep meaning the same
     * file when the process relaunches itself.
     */
    private static Path toAbsolute(String value, String what) {
        try {
            return Paths.get(value.trim()).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(what + ": not a valid path: " + value);
        }
    }

    /** The arguments as given, to pass again when the process relaunches itself. */
    public List<String> args() {
        return args;
    }

    /** The file of {@code --config}, absolute; {@code null} for the default one. */
    public Path configFile() {
        return configFile;
    }

    /** The folder of {@code --home}, absolute; {@code null} if not given. */
    public String home() {
        return home;
    }

    public boolean help() {
        return help;
    }

    public boolean printConfig() {
        return printConfig;
    }

    /** The media files, absolute, in order of appearance. */
    public List<String> mediaFiles() {
        return mediaFiles;
    }

    /** The {@code key=value} pairs as written, in order of appearance. */
    public Map<String, String> overrides() {
        return overrides;
    }

    /**
     * Everything the command line sets: the media keys the files decide, if any were given,
     * then the {@code key=value} pairs, which win.
     */
    public Map<String, String> settings() {
        if (mediaFiles.isEmpty()) return overrides;
        List<MediaSet.Entry> entries = new ArrayList<>();
        for (String f : mediaFiles) {
            entries.add(new MediaSet.Entry(MediaSet.Kind.of(f), f, DiskImage.isZipDisk(f)));
        }
        Map<String, String> out = new LinkedHashMap<>(
                AmigaConfig.mediaProperties(MediaSet.fromList(entries)));
        for (Map.Entry<String, String> e : overrides.entrySet()) {
            out.remove(e.getKey());
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /** The media files that do not exist on disk (for a zip, the archive itself). */
    public List<String> missingMediaFiles() {
        List<String> missing = new ArrayList<>();
        for (String f : mediaFiles) {
            if (!Files.isRegularFile(DiskImage.pathOf(f))) missing.add(f);
        }
        return missing;
    }

    /** The help text printed by {@code --help}. */
    public static String usage() {
        return """
                Usage: AmiJa [options] [file ...] [key=value ...]

                  file             a disk to insert: floppy (.adf .ipf .zip), hard disk
                                   (.hdf) or WHDLoad (.lha .lzh). The files given replace
                                   the media in amiga.properties and are spread over the
                                   drives as in the F12 "Drives" list: floppies DF0..DF3,
                                   hard disks master then slave.
                  key=value        sets any key of amiga.properties for this session only:
                                   it is not written to the file unless changed in the F12
                                   popup. An empty value clears the key (drive.df0.disk=).
                  --config FILE    reads and saves FILE instead of amiga.properties
                  --home DIR       the application home (shaders, savestates, WHDLoad...)
                  --print-config   prints the resulting configuration and exits
                  --help, -h       prints this help and exits

                Examples:
                  AmiJa "C:\\Games\\Turrican II (Disk 1).adf" "C:\\Games\\Turrican II (Disk 2).adf"
                  AmiJa /games/Lotus2.lha video.windowMode=fullscreen

                Every key, with its meaning and current value: AmiJa --print-config
                """;
    }
}
