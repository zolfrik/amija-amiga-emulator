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
 * Gestione automatica dei pacchetti WHDLoad (file {@code .lha}/{@code .lzh}),
 * analoga al supporto WHDLoad di RetroArch/PUAE.
 *
 * <p>Dato un archivio di gioco WHDLoad, costruisce "al volo" un hard disk
 * {@code .hdf} avviabile che contiene:
 * <ul>
 *   <li>il sistema WHDLoad (i file in {@code whdload/WHDLoad_files}: {@code C/},
 *       {@code Devs/}, {@code Libs/}, {@code S/}) — <b>forniti dall'utente</b>, vedi
 *       {@code DEFAULT_RESOURCE_DIR};</li>
 *   <li>il contenuto del gioco estratto dall'archivio;</li>
 *   <li>una {@code S/Startup-Sequence} generata che entra nella cartella del gioco
 *       e lancia {@code WHDLoad <gioco>.slave PRELOAD};</li>
 *   <li>una {@code S/WHDLoad.prefs} <b>generata</b> dai parametri di configurazione
 *       (sezione {@code whdload.prefs.*} di {@code amiga.properties}, iniettati con
 *       {@link #withPrefs(String)}); in assenza, un set di ripiego per l'avvio immediato.</li>
 * </ul>
 *
 * <p>L'immagine viene messa in cache e ricostruita solo se l'archivio è più recente.
 * Il chiamante monta poi l'{@code .hdf} come normale hard disk (boot da DH0).
 *
 * @see FfsBuilder
 * @see LhaExtractor
 */
public final class WhdLoadManager {

    /**
     * Cartella delle risorse WHDLoad, relativa alla working dir (la stessa in cui si
     * trova {@code amiga.properties}); dentro ci va la sottocartella
     * {@code WHDLoad_files}. Il contenuto NON è nel jar e non è ridistribuibile
     * (WHDLoad è freeware di Bert Jahn, le librerie sono AmigaOS: vedi
     * THIRD-PARTY-NOTICES.md), quindi va installato dall'utente. Se manca,
     * {@link #isAvailable()} restituisce {@code false} e la funzione resta spenta.
     */
    private static final String DEFAULT_RESOURCE_DIR = "whdload";
    /** Cartella di cache degli .hdf generati (cancellata all'uscita). */
    private static final String DEFAULT_CACHE_DIR = "whdload-cache";
    /** Cartella PERSISTENTE del volume dei salvataggi WHDLoad (NON cancellata). */
    private static final String DEFAULT_SAVES_DIR = "whdload-saves";
    /**
     * Nome del volume dei salvataggi. Coincide con il riferimento {@code WHDSaves:}
     * usato da {@code SavePath}, così AmigaDOS lo risolve <b>come nome di volume</b>
     * e non serve alcun {@code Assign}: è ciò che permette a WHDLoad di salvare anche
     * su un'installazione priva dei comandi AmigaOS, che non sono ridistribuibili e
     * che l'assistente di download non può procurare (THIRD-PARTY-NOTICES.md §5).
     */
    private static final String SAVES_VOLUME = "WHDSaves";
    /**
     * Nome usato dalle versioni precedenti, quando il volume veniva assegnato a
     * {@code WHDSaves:} dalla Startup-Sequence. I volumi già creati sul disco
     * dell'utente si chiamano così e vanno ancora assegnati, altrimenti i loro
     * salvataggi diventerebbero irraggiungibili.
     */
    private static final String SAVES_VOLUME_LEGACY = "Saves";
    /** Marcatore che registra con quale nome è stato creato il volume dei salvataggi. */
    private static final String SAVES_VOLUME_MARKER = "WHDSaves.hdf.volume";

    /** Blocchi liberi extra nell'immagine FFS per le scritture runtime (salvataggi). */
    private static final int SLACK_BLOCKS = 4096;   // ~2 MB
    /** Dimensione (in blocchi) del volume persistente dei salvataggi. */
    private static final int SAVES_BLOCKS = 8192;   // ~4 MB

    /** Versione del costruttore: cambiandola si invalida la cache degli .hdf. */
    private static final int BUILD_VERSION = 9;

    /** Lunghezza massima dei nomi file/cartella su AmigaDOS FFS. */
    private static final int FFS_MAX_NAME = 30;

    /** Registra una sola volta l'hook che cancella la cache all'uscita della JVM. */
    private static volatile boolean cleanupHookRegistered = false;

    /**
     * Mappa CRC32 → nome del file kickstart atteso da WHDLoad in {@code DEVS:Kickstarts}.
     * I {@code .RTB} di accompagnamento sono già nei file di sistema WHDLoad; serve la
     * dump grezza del ROM. (Come RetroArch, i ROM dell'utente sono associati per CRC.)
     */
    private static final Map<Long, String> KICKSTART_BY_CRC = new HashMap<>();
    static {
        KICKSTART_BY_CRC.put(0x9ED783D0L, "kick33180.A500");   // KS 1.2 33.180 (256 KB)
        KICKSTART_BY_CRC.put(0xC4F0F55FL, "kick34005.A500");   // KS 1.3 34.5  (256 KB)
        KICKSTART_BY_CRC.put(0xFC24AE0DL, "kick40063.A600");   // KS 3.1 40.63 (512 KB)
        KICKSTART_BY_CRC.put(0xE40A5DFBL, "kick40068.A1200");  // KS 3.1 40.68 A1200 (512 KB)
        KICKSTART_BY_CRC.put(0x5FE04842L, "kick40068.A4000");  // KS 3.1 40.68 A4000 (512 KB)
    }

    private final Path resourceDir;
    private final Path cacheDir;
    private final Path savesDir;
    private final List<Path> kickstartDirs = new ArrayList<>();
    /** Contenuto del file {@code S:WHDLoad.prefs} da scrivere; se {@code null} usa il fallback. */
    private String prefsContent;

    public WhdLoadManager() {
        this(Paths.get(DEFAULT_RESOURCE_DIR), Paths.get(DEFAULT_CACHE_DIR), Paths.get(DEFAULT_SAVES_DIR));
    }

    public WhdLoadManager(Path resourceDir, Path cacheDir) {
        this(resourceDir, cacheDir, Paths.get(DEFAULT_SAVES_DIR));
    }

    public WhdLoadManager(Path resourceDir, Path cacheDir, Path savesDir) {
        this.resourceDir = resourceDir;
        this.cacheDir = cacheDir;
        this.savesDir = savesDir;
    }

    /**
     * Aggiunge una cartella in cui cercare le dump dei ROM Kickstart da fornire a
     * WHDLoad (i giochi che richiedono un kickstart diverso da quello in macchina,
     * es. KS 1.3 per molti titoli OCS). I file vengono associati per CRC32.
     */
    public WhdLoadManager addKickstartDirectory(Path dir) {
        if (dir != null) kickstartDirs.add(dir);
        return this;
    }

    /**
     * Imposta il contenuto del file {@code S:WHDLoad.prefs} da scrivere nell'hard disk
     * generato. Tipicamente prodotto da {@code AmigaConfig.whdloadPrefsContent()} a
     * partire dalla sezione {@code whdload.prefs.*} di {@code amiga.properties}. Se non
     * impostato (o {@code null}) viene usato un set di preferenze di ripiego.
     */
    public WhdLoadManager withPrefs(String content) {
        this.prefsContent = content;
        return this;
    }

    /** {@code true} se le risorse di sistema WHDLoad sono presenti. */
    public boolean isAvailable() {
        return Files.isDirectory(resourceDir.resolve("WHDLoad_files"));
    }

    /**
     * Prepara (estraendo + costruendo l'HDF, con cache) il disco avviabile per
     * l'archivio {@code lhaPath} e ne restituisce il percorso.
     *
     * @throws IOException se le risorse mancano, l'archivio non è valido o non si
     *                     trova alcun file {@code .slave}
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

        // 1b. Dump dei ROM Kickstart richiesti da molti slave (associati per CRC32).
        copyKickstarts(work.resolve("Devs/Kickstarts"));

        // 2. Gioco estratto dall'archivio.
        List<LhaExtractor.Entry> entries = LhaExtractor.extract(lhaPath, work);

        // 3. Individua lo slave e calcola cartella/nome.
        String slaveRel = findSlave(entries);
        if (slaveRel == null) {
            throw new IOException("Nessun file .slave trovato nell'archivio " + lhaPath);
        }
        int slash = slaveRel.lastIndexOf('/');
        String gameDir = slash >= 0 ? slaveRel.substring(0, slash) : "";
        String slaveName = slash >= 0 ? slaveRel.substring(slash + 1) : slaveRel;
        // AmigaDOS FFS limita i nomi di file/cartella a 30 caratteri e FfsBuilder
        // (writeName) li tronca di conseguenza. La Startup-Sequence DEVE riferirsi
        // agli stessi nomi troncati, altrimenti WHDLoad non trova lo slave
        // (es. "...AdvIt.slave" = 31 char -> su disco "...AdvIt.slav" -> DOS-Error
        // #205, object not found). Tronchiamo qui ogni componente come l'FFS.
        gameDir = ffsTruncatePath(gameDir);
        slaveName = ffsName(slaveName);

        // 4. WHDLoad.prefs GENERATA dai parametri di configurazione (sezione
        //    whdload.prefs.* di amiga.properties, via withPrefs()) + Startup-Sequence
        //    generata. I salvataggi vanno sul volume persistente "Saves:" (montato a
        //    parte), non su questo disco transitorio.
        writeText(work.resolve("S/WHDLoad.prefs"),
                prefsContent != null ? prefsContent : buildPrefs());
        writeText(work.resolve("S/Startup-Sequence"),
                buildStartupSequence(gameDir, slaveName, savesVolumeName()));

        // 5. Costruzione dell'immagine FFS avviabile.
        Path tmpHdf = cacheDir.resolve(baseName + ".hdf.tmp");
        FfsBuilder.build(work, "WHDLoad", tmpHdf, SLACK_BLOCKS);
        Files.deleteIfExists(hdf);
        Files.move(tmpHdf, hdf);
        Files.writeString(meta, wantMeta);

        deleteRecursive(work);
        return hdf;
    }

    /**
     * Restituisce l'hard disk PERSISTENTE dei salvataggi WHDLoad (volume
     * {@link #SAVES_VOLUME}, che è già il riferimento {@code WHDSaves:} usato da
     * {@code SavePath}). Viene creato vuoto la prima volta e poi riusato così com'è:
     * NON sta nella cache cancellata all'uscita, quindi i salvataggi sopravvivono tra
     * le sessioni. Va montato come unità di servizio NON avviabile accanto al disco
     * WHDLoad del gioco.
     *
     * <p>Alla creazione viene scritto un marcatore col nome del volume, così
     * {@link #savesVolumeName()} sa distinguere i volumi nuovi da quelli creati dalle
     * versioni precedenti — che si chiamano "Saves" e continuano a richiedere l'assign.
     */
    public Path prepareSaves() throws IOException {
        Files.createDirectories(savesDir);
        Path savesHdf = savesDir.resolve("WHDSaves.hdf");
        if (Files.isRegularFile(savesHdf)) {
            return savesHdf;   // esistente: preserva i salvataggi
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
     * Copia in {@code destKickstarts} i ROM Kickstart trovati nelle cartelle
     * configurate il cui CRC32 corrisponde a un kickstart noto a WHDLoad, usando il
     * nome atteso (es. {@code kick34005.A500}). I {@code .RTB} di WHDLoad sono già
     * presenti; serve la dump grezza accanto a essi.
     */
    private void copyKickstarts(Path destKickstarts) throws IOException {
        for (Path dir : kickstartDirs) {
            if (!Files.isDirectory(dir)) continue;
            try (var s = Files.list(dir)) {
                for (Path rom : (Iterable<Path>) s::iterator) {
                    if (!Files.isRegularFile(rom)) continue;
                    long size = Files.size(rom);
                    if (size != 262144 && size != 524288) continue;   // 256/512 KB
                    String name = KICKSTART_BY_CRC.get(crc32(rom));
                    if (name == null) continue;
                    Path target = destKickstarts.resolve(name);
                    if (Files.exists(target)) continue;                // già copiato
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
    // Generazione dei file di avvio
    // =========================================================================

    /**
     * Tronca un nome di file/cartella al limite AmigaDOS FFS (30 caratteri),
     * esattamente come {@link FfsBuilder} quando scrive il nome sul disco.
     */
    private static String ffsName(String name) {
        return name.length() > FFS_MAX_NAME ? name.substring(0, FFS_MAX_NAME) : name;
    }

    /** Applica {@link #ffsName} a ogni componente di un percorso separato da '/'. */
    private static String ffsTruncatePath(String path) {
        if (path.isEmpty()) return path;
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length; i++) parts[i] = ffsName(parts[i]);
        return String.join("/", parts);
    }

    private static String findSlave(List<LhaExtractor.Entry> entries) {
        // Preferisci lo slave meno profondo (percorso più corto).
        return entries.stream()
                .filter(e -> !e.directory && e.path.toLowerCase(Locale.ROOT).endsWith(".slave"))
                .map(e -> e.path)
                .min(Comparator.comparingInt((String p) -> p.length()))
                .orElse(null);
    }

    /**
     * Startup-Sequence generata per l'hard disk WHDLoad.
     *
     * <p>Le righe che invocano comandi AmigaOS sono <b>condizionate alla loro
     * presenza</b>. Quei binari sono copyright Cloanto/Hyperion: non sono
     * ridistribuibili e nemmeno l'assistente di download può procurarli, quindi
     * un'installazione fatta solo con ciò che è scaricabile legittimamente ne è
     * priva. Emetterle comunque non impedirebbe l'avvio — AmigaDOS stampa
     * "Unknown command" e prosegue, e WHDLoad parte lo stesso — ma sporcherebbe lo
     * schermo con errori che non indicano alcun problema reale. Servono solo a
     * preparare {@code T:} e {@code ENV:}, di cui WHDLoad non ha bisogno.
     *
     * @param savesVolume nome del volume dei salvataggi, da {@link #savesVolumeName()}
     */
    private String buildStartupSequence(String gameDir, String slaveName, String savesVolume) {
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
        // Salvataggi sul volume persistente dedicato. Se il volume si chiama già
        // WHDSaves, "WHDSaves:" si risolve da sé e non serve alcun assign; i volumi
        // creati dalle versioni precedenti si chiamano "Saves" e vanno assegnati.
        if (!SAVES_VOLUME.equals(savesVolume) && hasCommand("Assign")) {
            sb.append("C:Assign >NIL: WHDSaves: \"").append(savesVolume).append(":\"\n");
        }
        sb.append("CD \"").append(cd).append("\"\n");
        sb.append("C:WHDLoad \"").append(slaveName).append("\" PRELOAD\n");
        return sb.toString();
    }

    /** {@code true} se il comando AmigaOS {@code name} è tra i file di sistema forniti. */
    private boolean hasCommand(String name) {
        return Files.isRegularFile(resourceDir.resolve("WHDLoad_files").resolve("C").resolve(name));
    }

    /**
     * Nome del volume su cui vive {@code WHDSaves.hdf}: {@link #SAVES_VOLUME} per i
     * volumi creati da questa versione (il marcatore lo registra alla creazione),
     * {@link #SAVES_VOLUME_LEGACY} per un HDF già presente ma senza marcatore, cioè
     * creato da una versione precedente.
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
     * Preferenze WHDLoad di ripiego, usate solo se manca il file di risorsa
     * {@code WHDLoad.prefs}: avvio non presidiato, splash ~5 s, niente attesa pulsante.
     */
    private static String buildPrefs() {
        return ";; WHDLoad.prefs generata automaticamente (fallback).\n"
             + "CoreDumpPath=T:\n"
             + "SavePath=WHDSaves:\n"
             + "NoNetwork\n"
             + "NoWriteCache\n"
             + "ConfigDelay=0\n"
             + "SplashDelay=250\n"     // ~5s leggibili, poi prosegue da solo (niente attesa pulsante)
             + "ReadDelay=0\n"
             + "WriteDelay=0\n";
    }

    // =========================================================================
    // Utilità filesystem
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
     * Registra (una sola volta) uno shutdown hook che rimuove l'intera cartella di
     * cache all'uscita della JVM, così gli {@code .hdf} generati non restano sul disco.
     * Non scatta sul restart in-process (lì la macchina viene solo ricostruita), solo
     * alla chiusura effettiva dell'applicazione.
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
