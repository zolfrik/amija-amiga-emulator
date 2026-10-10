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

import io.github.zolfrik.amija.common.AppHome;
import io.github.zolfrik.amija.common.VideoMode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * Automatic handling of WHDLoad packages ({@code .lha}/{@code .lzh} files).
 *
 * <p>Given a WHDLoad game archive, it builds on the fly a bootable {@code .hdf} hard disk
 * containing:
 * <ul>
 *   <li>the WHDLoad system (the files in {@code whdload/WHDLoad_files}: {@code C/},
 *       {@code Devs/}, {@code Libs/}, {@code S/}), <b>supplied by the user</b>, see
 *       {@code DEFAULT_RESOURCE_DIR};</li>
 *   <li>the game content extracted from the archive;</li>
 *   <li>a generated {@code S/Startup-Sequence} that enters the game's folder and runs
 *       {@code WHDLoad <game>.slave} with the arguments the installation declares in the
 *       tooltypes of its icon (see {@link #findLaunch});</li>
 *   <li>an {@code S/WHDLoad.prefs} <b>generated</b> from the configuration parameters
 *       (section {@code whdload.prefs.*} of {@code amiga.properties}, injected with
 *       {@link #withPrefs(String)}); without them, a fallback set for an immediate start.</li>
 * </ul>
 *
 * <p>The image is cached and rebuilt only if the archive is newer. The caller then mounts
 * the {@code .hdf} as a normal hard disk (boot from DH0).
 *
 * @see FfsBuilder
 * @see LhaExtractor
 */
public final class WhdLoadManager {

    /**
     * Folder of the WHDLoad resources, relative to the application home (where
     * {@code amiga.properties} is); the {@code WHDLoad_files} subfolder goes inside it. Its
     * content is NOT in the jar and is not redistributable (WHDLoad is Bert Jahn's freeware,
     * the libraries are AmigaOS: see THIRD-PARTY-NOTICES.md), so the user must install it.
     * If it is missing, {@link #isAvailable()} returns {@code false} and the feature stays
     * off.
     */
    private static final String DEFAULT_RESOURCE_DIR = "whdload";
    /** Cache folder of the generated .hdf files (deleted on exit). */
    private static final String DEFAULT_CACHE_DIR = "whdload-cache";
    /** PERSISTENT folder of the WHDLoad saves volume (NOT deleted). */
    private static final String DEFAULT_SAVES_DIR = "whdload-saves";
    /**
     * Name of the saves volume. It matches the reference {@code WHDSaves:} used by
     * {@code SavePath}, so AmigaDOS resolves it <b>as a volume name</b> and no
     * {@code Assign} is needed: that lets WHDLoad save even on an installation without the
     * AmigaOS commands, which are not redistributable and which the download assistant
     * cannot provide (THIRD-PARTY-NOTICES.md section 5).
     */
    private static final String SAVES_VOLUME = "WHDSaves";
    /**
     * Name used by earlier versions, when the volume was assigned to {@code WHDSaves:} by
     * the Startup-Sequence. Volumes already created on the user's disk have this name and
     * must still be assigned, or their saves would become unreachable.
     */
    private static final String SAVES_VOLUME_LEGACY = "Saves";
    /** Marker recording the name the saves volume was created with. */
    private static final String SAVES_VOLUME_MARKER = "WHDSaves.hdf.volume";

    /** Extra free blocks in the FFS image for runtime writes (saves). */
    private static final int SLACK_BLOCKS = 4096;   // ~2 MB
    /** Size (in blocks) of the persistent save volume. */
    private static final int SAVES_BLOCKS = 8192;   // ~4 MB

    /** Builder version: changing it invalidates the .hdf cache. */
    private static final int BUILD_VERSION = 10;

    /** Maximum depth explored when looking for Kickstart dumps. */
    private static final int KICKSTART_SCAN_DEPTH = 3;

    /** Maximum length of file/folder names on AmigaDOS FFS. */
    private static final int FFS_MAX_NAME = 30;

    /** Registers the hook that deletes the cache at JVM exit only once. */
    private static volatile boolean cleanupHookRegistered = false;

    /**
     * Memory of the answers of {@link #declaredVideoMode}, per archive + modification date.
     * The one-element array tells "read, says nothing" from "never read".
     */
    private static final Map<String, VideoMode[]> declaredVideoModeCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Map CRC32 -> name of the Kickstart file WHDLoad expects in {@code DEVS:Kickstarts}.
     * The accompanying {@code .RTB} files are already among the WHDLoad system files; what
     * is needed is the raw ROM dump. The user's ROMs are matched by CRC.
     */
    private static final Map<Long, String> KICKSTART_BY_CRC = new HashMap<>();
    static {
        KICKSTART_BY_CRC.put(0xA6CE1636L, "kick33180.A500");   // KS 1.2 33.180 (256 KB)
        KICKSTART_BY_CRC.put(0xC4F0F55FL, "kick34005.A500");   // KS 1.3 34.5  (256 KB)
        KICKSTART_BY_CRC.put(0xFC24AE0DL, "kick40063.A600");   // KS 3.1 40.63 (512 KB)
        KICKSTART_BY_CRC.put(0x1483A091L, "kick40068.A1200");  // KS 3.1 40.68 A1200 (512 KB)
        KICKSTART_BY_CRC.put(0xD6BAE334L, "kick40068.A4000");  // KS 3.1 40.68 A4000 (512 KB)
        // Exactly the five for which the WHDLoad system files bring the .RTB relocation
        // table (SoftKick 3.46, see AssetCatalog). An install that asks for another (e.g.
        // kick31034.A1000) does not become bootable by supplying the ROM: without the
        // matching .RTB WHDLoad stops anyway, and with an image it does not recognise it
        // answers "invalid kickstart image".
    }

    private final Path resourceDir;
    private final Path cacheDir;
    private final Path savesDir;
    private final List<Path> kickstartDirs = new ArrayList<>();
    /** Content of the {@code S:WHDLoad.prefs} file to write; if {@code null} the fallback is used. */
    private String prefsContent;

    public WhdLoadManager() {
        this(AppHome.resolve(DEFAULT_RESOURCE_DIR), AppHome.resolve(DEFAULT_CACHE_DIR),
                AppHome.resolve(DEFAULT_SAVES_DIR));
    }

    public WhdLoadManager(Path resourceDir, Path cacheDir) {
        this(resourceDir, cacheDir, AppHome.resolve(DEFAULT_SAVES_DIR));
    }

    public WhdLoadManager(Path resourceDir, Path cacheDir, Path savesDir) {
        this.resourceDir = resourceDir;
        this.cacheDir = cacheDir;
        this.savesDir = savesDir;
    }

    /**
     * Adds a folder in which to look for the Kickstart ROM dumps to give WHDLoad (games
     * that need a Kickstart other than the machine's, e.g. KS 1.3 for many OCS titles). The
     * files are matched by CRC32.
     */
    public WhdLoadManager addKickstartDirectory(Path dir) {
        if (dir != null) kickstartDirs.add(dir);
        return this;
    }

    /**
     * Sets the content of the {@code S:WHDLoad.prefs} file to write into the generated hard
     * disk. Usually produced by {@code AmigaConfig.whdloadPrefsContent()} from the
     * {@code whdload.prefs.*} section of {@code amiga.properties}. If not set (or
     * {@code null}) a fallback set of preferences is used.
     */
    public WhdLoadManager withPrefs(String content) {
        this.prefsContent = content;
        return this;
    }

    /**
     * Video standard the WHDLoad archive {@code lhaPath} declares in the tooltypes of its
     * icon: {@link VideoMode#NTSC} for {@code NTSC}, {@link VideoMode#PAL} for {@code PAL},
     * {@code null} if it says nothing.
     *
     * <p>A declaration, not a hint: on a real machine that tooltype makes WHDLoad open a
     * screen of that standard, and without the matching monitor in {@code DEVS:Monitors} the
     * game does not start at all. In an emulator the equivalent is switching the machine on
     * in that standard, because the machine itself is the monitor. It counts more than the
     * convention on the file <i>name</i>, which remains the fallback when the icon is silent.
     *
     * <p>Reads only the archive's icons (a few KB): it extracts no game and builds no disk.
     * If the archive cannot be read it returns {@code null}: here not knowing is a normal
     * outcome, not an error.
     */
    public static VideoMode declaredVideoMode(String lhaPath) {
        if (lhaPath == null || lhaPath.isEmpty()) return null;
        // Auto mode queries the profile at every redraw: without a memory the archive would
        // be read from disk every time. The key includes the modification date, so replacing
        // the file invalidates the answer.
        String key = lhaPath + '\0' + new java.io.File(lhaPath).lastModified();
        VideoMode[] cached = declaredVideoModeCache.get(key);
        if (cached != null) return cached[0];

        VideoMode found = null;
        try {
            outer:
            for (byte[] data : LhaExtractor.extractIcons(lhaPath).values()) {
                AmigaIcon icon = AmigaIcon.parse(data);
                if (icon == null || !isWhdLoadTool(icon.defaultTool())) continue;
                for (String tt : icon.toolTypes()) {
                    if (!isRealToolType(tt)) continue;
                    String s = tt.trim();
                    if (s.equalsIgnoreCase("NTSC")) { found = VideoMode.NTSC; break outer; }
                    if (s.equalsIgnoreCase("PAL"))  { found = VideoMode.PAL;  break outer; }
                }
            }
        } catch (IOException | RuntimeException e) {
            return null;   // unreadable archive: not knowing is a normal outcome
        }
        declaredVideoModeCache.put(key, new VideoMode[] { found });
        return found;
    }

    /** Memory of the answers of {@link #requiresAga}, per archive + modification date. */
    private static final Map<String, Boolean> requiresAgaCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** WHDLF_ReqAGA: bit 5 of the slave's {@code ws_Flags}, the AGA chipset is required. */
    private static final int WHDLF_REQAGA = 0x0020;

    /**
     * {@code true} if a slave of the WHDLoad archive {@code lhaPath} declares in its header
     * that it needs the AGA chipset ({@code WHDLF_ReqAGA} in {@code ws_Flags}, the word that
     * follows {@code ws_Version} after the {@code WHDLOADS} identifier). Without AGA WHDLoad
     * refuses such a slave, so Auto mode builds an A1200 for it.
     *
     * <p>Reads only the {@code .slave} files of the archive. {@code false} when the archive
     * cannot be read: not knowing is a normal outcome.
     */
    public static boolean requiresAga(String lhaPath) {
        if (lhaPath == null || lhaPath.isEmpty()) return false;
        String key = lhaPath + '\0' + new java.io.File(lhaPath).lastModified();
        Boolean cached = requiresAgaCache.get(key);
        if (cached != null) return cached;

        boolean found = false;
        try {
            for (byte[] slave : LhaExtractor.extractBySuffix(lhaPath, ".slave").values()) {
                int id = indexOf(slave, "WHDLOADS".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                if (id < 0 || id + 12 > slave.length) continue;
                int flags = ((slave[id + 10] & 0xFF) << 8) | (slave[id + 11] & 0xFF);
                if ((flags & WHDLF_REQAGA) != 0) { found = true; break; }
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
        requiresAgaCache.put(key, found);
        return found;
    }

    private static int indexOf(byte[] data, byte[] pattern) {
        outer:
        for (int i = 0; i + pattern.length <= data.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    /** {@code true} if the WHDLoad system resources are present. */
    public boolean isAvailable() {
        return Files.isDirectory(resourceDir.resolve("WHDLoad_files"));
    }

    /**
     * Prepares (extracting and building the HDF, with a cache) the bootable disk for the
     * archive {@code lhaPath} and returns its path.
     *
     * @throws IOException if the resources are missing, the archive is invalid or no
     *                     {@code .slave} file is found
     */
    public Path prepare(String lhaPath) throws IOException {
        if (!isAvailable()) {
            throw new IOException("Risorse WHDLoad assenti in " + resourceDir.toAbsolutePath()
                    + " (cartella WHDLoad_files mancante): servono almeno C/WHDLoad e le"
                    + " tabelle Devs/Kickstarts/*.RTB. Si possono scaricare dai siti"
                    + " ufficiali con F12 > File opzionali, oppure installare a mano.");
        }
        Path lha = Paths.get(lhaPath);
        if (!Files.isRegularFile(lha)) {
            throw new IOException("Archivio WHDLoad inesistente: " + lhaPath);
        }

        Files.createDirectories(cacheDir);
        registerCacheCleanup();
        String baseName = stripExtension(lha.getFileName().toString());
        Path hdf = cacheDir.resolve(baseName + ".hdf");
        Path meta = cacheDir.resolve(baseName + ".hdf.meta");

        String wantMeta = BUILD_VERSION + ":" + Files.getLastModifiedTime(lha).toMillis()
                + ":" + Files.size(lha);
        if (Files.isRegularFile(hdf) && Files.isRegularFile(meta)
                && wantMeta.equals(Files.readString(meta).trim())) {
            return hdf;   // cache valida
        }

        Path work = cacheDir.resolve(baseName + ".build");
        deleteRecursive(work);
        Files.createDirectories(work);

        // 1. Sistema WHDLoad.
        copyTree(resourceDir.resolve("WHDLoad_files"), work);

        // 1b. Dump of the Kickstart ROMs many slaves require (matched by CRC32).
        copyKickstarts(work.resolve("Devs/Kickstarts"));

        // 2. Gioco estratto dall'archivio.
        List<LhaExtractor.Entry> entries = LhaExtractor.extract(lhaPath, work);

        // 3. Work out how the game is to be launched.
        Launch launch = findLaunch(entries, work);
        if (launch == null) {
            throw new IOException("Nessun file .slave trovato nell'archivio " + lhaPath);
        }
        // AmigaDOS FFS limits file/folder names to 30 characters and FfsBuilder (writeName)
        // truncates them accordingly. The Startup-Sequence MUST refer to the same truncated
        // names, or WHDLoad does not find the slave (e.g. "...AdvIt.slave" = 31 chars, on
        // disk "...AdvIt.slav", DOS-Error #205, object not found). Every component is
        // truncated here as FFS does.
        String gameDir = ffsTruncatePath(launch.gameDir);
        String slaveName = ffsTruncatePath(launch.slaveName);

        // 4. WHDLoad.prefs GENERATED from the configuration parameters (section
        //    whdload.prefs.* of amiga.properties, through withPrefs()) + generated
        //    Startup-Sequence. The saves go to the persistent "Saves:" volume (mounted
        //    separately), not to this transient disk.
        writeText(work.resolve("S/WHDLoad.prefs"),
                prefsContent != null ? prefsContent : buildPrefs());
        writeText(work.resolve("S/Startup-Sequence"),
                buildStartupSequence(gameDir, slaveName, launch.options, savesVolumeName()));

        // 5. Build the bootable FFS image.
        Path tmpHdf = cacheDir.resolve(baseName + ".hdf.tmp");
        FfsBuilder.build(work, "WHDLoad", tmpHdf, SLACK_BLOCKS);
        Files.deleteIfExists(hdf);
        Files.move(tmpHdf, hdf);
        Files.writeString(meta, wantMeta);

        deleteRecursive(work);
        return hdf;
    }

    /**
     * Returns the PERSISTENT hard disk of the WHDLoad saves (volume {@link #SAVES_VOLUME},
     * which is already the {@code WHDSaves:} reference used by {@code SavePath}). It is
     * created empty the first time and then reused as it is: it is NOT in the cache deleted
     * at exit, so the saves survive between sessions. It must be mounted as a NON-bootable
     * service unit next to the game's WHDLoad disk.
     *
     * <p>At creation a marker with the volume name is written, so {@link #savesVolumeName()}
     * can tell new volumes from those created by earlier versions, which are called "Saves"
     * and still need the assign.
     */
    public Path prepareSaves() throws IOException {
        Files.createDirectories(savesDir);
        Path savesHdf = savesDir.resolve("WHDSaves.hdf");
        if (Files.isRegularFile(savesHdf)) {
            return savesHdf;   // existing: keeps the saves
        }
        Path empty = Files.createTempDirectory("whdsaves-empty");
        try {
            Path tmp = savesDir.resolve("WHDSaves.hdf.tmp");
            FfsBuilder.build(empty, SAVES_VOLUME, tmp, SAVES_BLOCKS);
            Files.move(tmp, savesHdf);
            writeText(savesDir.resolve(SAVES_VOLUME_MARKER), SAVES_VOLUME);
        } finally {
            deleteRecursive(empty);
        }
        System.out.println("[WHDLOAD] Volume salvataggi creato: " + savesHdf
                + " (volume " + SAVES_VOLUME + ")");
        return savesHdf;
    }

    /**
     * Copies into {@code destKickstarts} the Kickstart ROMs found in the configured folders
     * whose CRC32 matches a Kickstart known to WHDLoad, under the expected name (e.g.
     * {@code kick34005.A500}). WHDLoad's {@code .RTB} files are already there; the raw dump
     * is needed next to them.
     */
    private void copyKickstarts(Path destKickstarts) throws IOException {
        for (Path dir : kickstartDirs) {
            if (!Files.isDirectory(dir)) continue;
            // Recursive search: ROM collections keep the less common versions in subfolders,
            // and that is where the Kickstarts the few demanding installs ask for are (A1200,
            // A4000, 1.2). The depth limit avoids walking a huge tree if the folder given is
            // a root.
            try (var s = Files.walk(dir, KICKSTART_SCAN_DEPTH)) {
                for (Path rom : (Iterable<Path>) s::iterator) {
                    if (!Files.isRegularFile(rom)) continue;
                    long size = Files.size(rom);
                    if (size != 262144 && size != 524288) continue;   // 256/512 KB
                    String name = KICKSTART_BY_CRC.get(crc32(rom));
                    if (name == null) continue;
                    Path target = destKickstarts.resolve(name);
                    if (Files.exists(target)) continue;                // already copied
                    Files.createDirectories(destKickstarts);
                    Files.copy(rom, target);
                    System.out.println("[WHDLOAD] Kickstart fornito: " + rom.getFileName() + " -> Devs/Kickstarts/" + name);
                }
            }
        }
    }

    private static long crc32(Path file) throws IOException {
        CRC32 crc = new CRC32();
        crc.update(Files.readAllBytes(file));
        return crc.getValue();
    }

    // =========================================================================
    // Generation of the startup files
    // =========================================================================

    /**
     * Truncates a file/folder name to the AmigaDOS FFS limit (30 characters), exactly as
     * {@link FfsBuilder} does when it writes the name on the disk.
     */
    private static String ffsName(String name) {
        return name.length() > FFS_MAX_NAME ? name.substring(0, FFS_MAX_NAME) : name;
    }

    /** Applies {@link #ffsName} to each component of a '/'-separated path. */
    private static String ffsTruncatePath(String path) {
        if (path.isEmpty()) return path;
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length; i++) parts[i] = ffsName(parts[i]);
        return String.join("/", parts);
    }

    /** How to launch the game: folder, slave name and WHDLoad arguments. */
    private record Launch(String gameDir, String slaveName, List<String> options) { }

    /**
     * Derives from the extracted archive the WHDLoad command to put in the
     * Startup-Sequence.
     *
     * <p>The authoritative source is the <b>package's icon</b>: the one whose default tool is
     * {@code WHDLoad} carries in its tooltypes the arguments the installation must be
     * launched with. They are not optional: a package that keeps the disk images in a
     * subfolder declares {@code DATA=data}, and without that argument WHDLoad looks for them
     * next to the slave and fails with DOS-Error #205 as soon as the game asks for the first
     * file.
     *
     * <p>If no icon says so (old packages, or missing icons) the shallowest {@code .slave}
     * file alone is used, with no arguments: right for packages that keep everything in a
     * single folder.
     */
    private Launch findLaunch(List<LhaExtractor.Entry> entries, Path work) {
        Launch fromIcon = launchFromIcons(entries, work);
        if (fromIcon != null) return fromIcon;

        // Prefer the shallowest slave (shortest path).
        String slaveRel = entries.stream()
                .filter(e -> !e.directory && e.path.toLowerCase(Locale.ROOT).endsWith(".slave"))
                .map(e -> e.path)
                .min(Comparator.comparingInt((String p) -> p.length()))
                .orElse(null);
        if (slaveRel == null) return null;
        int slash = slaveRel.lastIndexOf('/');
        return new Launch(slash >= 0 ? slaveRel.substring(0, slash) : "",
                slash >= 0 ? slaveRel.substring(slash + 1) : slaveRel, List.of());
    }

    /**
     * Looks among the extracted icons for the one that launches WHDLoad and turns its
     * tooltypes into a {@link Launch}. Returns {@code null} if none is usable.
     */
    private Launch launchFromIcons(List<LhaExtractor.Entry> entries, Path work) {
        Launch best = null;
        for (LhaExtractor.Entry e : entries) {
            if (e.directory || !e.path.toLowerCase(Locale.ROOT).endsWith(".info")) continue;
            byte[] data;
            try {
                data = Files.readAllBytes(work.resolve(e.diskPath.replace('/', java.io.File.separatorChar)));
            } catch (IOException ioe) {
                continue;
            }
            AmigaIcon icon = AmigaIcon.parse(data);
            if (icon == null || !isWhdLoadTool(icon.defaultTool())) continue;

            String dir = e.path.contains("/") ? e.path.substring(0, e.path.lastIndexOf('/')) : "";
            String slave = null;
            List<String> options = new ArrayList<>();
            for (String tt : icon.toolTypes()) {
                if (!isRealToolType(tt)) continue;
                if (tt.regionMatches(true, 0, "SLAVE=", 0, 6)) {
                    slave = tt.substring(6).trim();
                } else {
                    options.add(tt);
                }
            }
            if (slave == null || slave.isEmpty()) continue;
            // SLAVE= may hold a path relative to the icon's folder. The check is made on the
            // path AS IT IS ON DISK (diskPath), which for names Windows cannot write differs
            // from the Amiga one.
            String diskDir = e.diskPath.contains("/")
                    ? e.diskPath.substring(0, e.diskPath.lastIndexOf('/')) : "";
            String slaveOnDisk = diskDir.isEmpty() ? slave : diskDir + "/" + slave;
            if (!Files.isRegularFile(work.resolve(slaveOnDisk.replace('/', java.io.File.separatorChar)))) continue;
            Launch candidate = new Launch(dir, slave, options);
            // Among equally valid icons the shallowest wins.
            if (best == null || candidate.gameDir().length() < best.gameDir().length()) best = candidate;
        }
        return best;
    }

    /** {@code true} if the icon's default tool is WHDLoad (with or without a path). */
    private static boolean isWhdLoadTool(String tool) {
        if (tool == null) return false;
        String leaf = tool;
        int cut = Math.max(leaf.lastIndexOf('/'), leaf.lastIndexOf(':'));
        if (cut >= 0) leaf = leaf.substring(cut + 1);
        return leaf.equalsIgnoreCase("WHDLoad");
    }

    /**
     * {@code true} if the tooltype is a real argument. Disabled entries (which the Workbench
     * keeps in parentheses), separators and NewIcon data, which live in the same list
     * ({@code IM1=}/{@code IM2=} and the header line before them), are discarded.
     */
    private static boolean isRealToolType(String tt) {
        if (tt == null) return false;
        String s = tt.trim();
        if (s.isEmpty() || s.startsWith("(") || s.startsWith(";")) return false;
        if (s.regionMatches(true, 0, "IM1=", 0, 4) || s.regionMatches(true, 0, "IM2=", 0, 4)) return false;
        return !s.startsWith("*** ");
    }

    /**
     * Startup-Sequence generated for the WHDLoad hard disk.
     *
     * <p>The lines that call AmigaOS commands are <b>conditional on their presence</b>.
     * Those binaries are Cloanto/Hyperion copyright: not redistributable, and not even the
     * download assistant can provide them, so an installation made only with what can be
     * downloaded legitimately lacks them. Emitting them anyway would not prevent the boot
     * (AmigaDOS prints "Unknown command" and carries on, and WHDLoad starts anyway) but
     * would litter the screen with errors that point to no real problem. They only prepare
     * {@code T:} and {@code ENV:}, which WHDLoad does not need.
     *
     * @param options     WHDLoad arguments from the package's icon, see {@link #findLaunch}
     * @param savesVolume name of the saves volume, from {@link #savesVolumeName()}
     */
    private String buildStartupSequence(String gameDir, String slaveName,
                                        List<String> options, String savesVolume) {
        String cd = gameDir.isEmpty() ? "SYS:" : "SYS:" + gameDir;
        StringBuilder sb = new StringBuilder();
        sb.append(";; Startup-Sequence generata automaticamente per l'avvio WHDLoad.\n");
        sb.append("FAILAT 21\n");
        if (hasCommand("SetPatch")) {
            sb.append("C:SetPatch QUIET\n");
        }
        if (hasCommand("MakeDir")) {
            sb.append("C:MakeDir >NIL: RAM:T RAM:ENV RAM:Clipboards\n");
        }
        if (hasCommand("Assign")) {
            sb.append("C:Assign >NIL: T: RAM:T\n");
            sb.append("C:Assign >NIL: ENV: RAM:ENV\n");
        }
        // Saves on the dedicated persistent volume. If the volume is already called
        // WHDSaves, "WHDSaves:" resolves by itself and no assign is needed; the volumes
        // created by earlier versions are called "Saves" and must be assigned.
        if (!SAVES_VOLUME.equals(savesVolume) && hasCommand("Assign")) {
            sb.append("C:Assign >NIL: WHDSaves: \"").append(savesVolume).append(":\"\n");
        }
        sb.append("CD \"").append(cd).append("\"\n");
        sb.append("C:WHDLoad \"").append(slaveName).append('"');
        boolean preload = false;
        for (String opt : options) {
            if (opt.equalsIgnoreCase("PRELOAD")) preload = true;
            sb.append(' ').append(quoteArg(opt));
        }
        // PRELOAD loads everything into memory once: it avoids disk accesses during the
        // game. Added only if the icon does not ask for it already.
        if (!preload) sb.append(" PRELOAD");
        sb.append('\n');
        return sb.toString();
    }

    /**
     * Quotes an argument containing spaces, as AmigaDOS ReadArgs wants; the others stay
     * bare.
     */
    private static String quoteArg(String arg) {
        return arg.indexOf(' ') >= 0 ? "\"" + arg + "\"" : arg;
    }

    /** {@code true} if the AmigaOS command {@code name} is among the system files supplied. */
    private boolean hasCommand(String name) {
        return Files.isRegularFile(resourceDir.resolve("WHDLoad_files").resolve("C").resolve(name));
    }

    /**
     * Name of the volume {@code WHDSaves.hdf} lives on: {@link #SAVES_VOLUME} for volumes
     * created by this version (the marker records it at creation),
     * {@link #SAVES_VOLUME_LEGACY} for an HDF already present but without the marker, i.e.
     * created by an earlier version.
     */
    private String savesVolumeName() throws IOException {
        Path marker = savesDir.resolve(SAVES_VOLUME_MARKER);
        if (Files.isRegularFile(marker)) {
            String name = Files.readString(marker).trim();
            if (!name.isEmpty()) return name;
        }
        return Files.isRegularFile(savesDir.resolve("WHDSaves.hdf"))
                ? SAVES_VOLUME_LEGACY : SAVES_VOLUME;
    }

    /**
     * Fallback WHDLoad preferences, used only if the {@code WHDLoad.prefs} resource file is
     * missing: unattended start, ~5 s splash, no wait for a button.
     */
    private static String buildPrefs() {
        return ";; WHDLoad.prefs generata automaticamente (fallback).\n"
             + "CoreDumpPath=T:\n"
             + "SavePath=WHDSaves:\n"
             + "NoNetwork\n"
             + "NoWriteCache\n"
             + "ConfigDelay=0\n"
             + "SplashDelay=250\n"     // ~5s readable, then continues on its own (no wait for a button)
             + "ReadDelay=0\n"
             + "WriteDelay=0\n";
    }

    // =========================================================================
    // File system utilities
    // =========================================================================

    private static void copyTree(Path src, Path dest) throws IOException {
        try (var stream = Files.walk(src)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                Path rel = src.relativize(p);
                Path target = dest.resolve(rel.toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    if (target.getParent() != null) Files.createDirectories(target.getParent());
                    Files.copy(p, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /**
     * Registers (once) a shutdown hook that removes the whole cache folder at JVM exit, so
     * the generated {@code .hdf} files do not stay on disk. It does not fire on an
     * in-process restart (there the machine is only rebuilt), only when the application
     * really closes.
     */
    private void registerCacheCleanup() {
        if (cleanupHookRegistered) return;
        synchronized (WhdLoadManager.class) {
            if (cleanupHookRegistered) return;
            cleanupHookRegistered = true;
            final Path dir = cacheDir;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { deleteRecursive(dir); } catch (IOException ignored) { }
            }, "whdload-cache-cleanup"));
        }
    }

    private static void deleteRecursive(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException ignored) { }
            });
        }
    }

    private static void writeText(Path p, String content) throws IOException {
        if (p.getParent() != null) Files.createDirectories(p.getParent());
        Files.write(p, content.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
