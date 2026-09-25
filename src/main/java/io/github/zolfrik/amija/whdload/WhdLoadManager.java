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
 *       e lancia {@code WHDLoad <gioco>.slave} con gli argomenti che l'installazione
 *       dichiara nelle tooltype della propria icona (vedi {@link #findLaunch});</li>
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
     * Cartella delle risorse WHDLoad, relativa alla home dell'applicazione (la stessa
     * in cui si trova {@code amiga.properties}); dentro ci va la sottocartella
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
    private static final int BUILD_VERSION = 10;

    /** Profondità massima esplorata cercando le dump dei kickstart. */
    private static final int KICKSTART_SCAN_DEPTH = 3;

    /** Lunghezza massima dei nomi file/cartella su AmigaDOS FFS. */
    private static final int FFS_MAX_NAME = 30;

    /** Registra una sola volta l'hook che cancella la cache all'uscita della JVM. */
    private static volatile boolean cleanupHookRegistered = false;

    /**
     * Memoria delle risposte di {@link #declaredVideoMode}, per archivio+data di
     * modifica. L'array di un elemento distingue "letto, non dice nulla" da "mai letto".
     */
    private static final Map<String, VideoMode[]> declaredVideoModeCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Mappa CRC32 → nome del file kickstart atteso da WHDLoad in {@code DEVS:Kickstarts}.
     * I {@code .RTB} di accompagnamento sono già nei file di sistema WHDLoad; serve la
     * dump grezza del ROM. (Come RetroArch, i ROM dell'utente sono associati per CRC.)
     */
    private static final Map<Long, String> KICKSTART_BY_CRC = new HashMap<>();
    static {
        KICKSTART_BY_CRC.put(0xA6CE1636L, "kick33180.A500");   // KS 1.2 33.180 (256 KB)
        KICKSTART_BY_CRC.put(0xC4F0F55FL, "kick34005.A500");   // KS 1.3 34.5  (256 KB)
        KICKSTART_BY_CRC.put(0xFC24AE0DL, "kick40063.A600");   // KS 3.1 40.63 (512 KB)
        KICKSTART_BY_CRC.put(0x1483A091L, "kick40068.A1200");  // KS 3.1 40.68 A1200 (512 KB)
        KICKSTART_BY_CRC.put(0xD6BAE334L, "kick40068.A4000");  // KS 3.1 40.68 A4000 (512 KB)
        // Sono esattamente i cinque per cui i file di sistema WHDLoad portano la
        // tabella di rilocazione .RTB (SoftKick 3.46, vedi AssetCatalog). Un install
        // che ne chieda un altro — p.es. kick31034.A1000 — non diventa avviabile
        // fornendogli il ROM: senza il .RTB corrispondente WHDLoad si ferma comunque,
        // e con un'immagine che non riconosce risponde "invalid kickstart image".
    }

    private final Path resourceDir;
    private final Path cacheDir;
    private final Path savesDir;
    private final List<Path> kickstartDirs = new ArrayList<>();
    /** Contenuto del file {@code S:WHDLoad.prefs} da scrivere; se {@code null} usa il fallback. */
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

    /**
     * Standard video che l'archivio WHDLoad {@code lhaPath} dichiara nelle tooltype
     * della propria icona: {@link VideoMode#NTSC} per {@code NTSC},
     * {@link VideoMode#PAL} per {@code PAL}, {@code null} se non dice nulla.
     *
     * <p>È una dichiarazione, non un indizio: su una macchina vera quella tooltype fa
     * aprire a WHDLoad uno schermo di quello standard, e senza il monitor
     * corrispondente in {@code DEVS:Monitors} il gioco non parte affatto. In un
     * emulatore l'equivalente è accendere la macchina in quello standard, perché è la
     * macchina stessa a fare da monitor. Vale più della convenzione sul <i>nome</i> del
     * file, che resta il ripiego quando l'icona tace.
     *
     * <p>Legge le sole icone dell'archivio (pochi KB): non estrae il gioco e non
     * costruisce alcun disco. Se l'archivio non è leggibile restituisce {@code null} —
     * qui non sapere è un esito normale, non un errore.
     */
    public static VideoMode declaredVideoMode(String lhaPath) {
        if (lhaPath == null || lhaPath.isEmpty()) return null;
        // La modalità Auto interroga il profilo a ogni ridisegno: senza memoria
        // rileggeremmo l'archivio da disco ogni volta. La chiave include la data di
        // modifica, così sostituire il file invalida la risposta.
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
            return null;   // archivio illeggibile: non sapere è un esito normale
        }
        declaredVideoModeCache.put(key, new VideoMode[] { found });
        return found;
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

        // 3. Individua come va lanciato il gioco.
        Launch launch = findLaunch(entries, work);
        if (launch == null) {
            throw new IOException("Nessun file .slave trovato nell'archivio " + lhaPath);
        }
        // AmigaDOS FFS limita i nomi di file/cartella a 30 caratteri e FfsBuilder
        // (writeName) li tronca di conseguenza. La Startup-Sequence DEVE riferirsi
        // agli stessi nomi troncati, altrimenti WHDLoad non trova lo slave
        // (es. "...AdvIt.slave" = 31 char -> su disco "...AdvIt.slav" -> DOS-Error
        // #205, object not found). Tronchiamo qui ogni componente come l'FFS.
        String gameDir = ffsTruncatePath(launch.gameDir);
        String slaveName = ffsTruncatePath(launch.slaveName);

        // 4. WHDLoad.prefs GENERATA dai parametri di configurazione (sezione
        //    whdload.prefs.* di amiga.properties, via withPrefs()) + Startup-Sequence
        //    generata. I salvataggi vanno sul volume persistente "Saves:" (montato a
        //    parte), non su questo disco transitorio.
        writeText(work.resolve("S/WHDLoad.prefs"),
                prefsContent != null ? prefsContent : buildPrefs());
        writeText(work.resolve("S/Startup-Sequence"),
                buildStartupSequence(gameDir, slaveName, launch.options, savesVolumeName()));

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
            // Ricerca ricorsiva: le collezioni di ROM tengono le versioni meno comuni
            // in sottocartelle, ed è lì che stanno i kickstart che i pochi install
            // esigenti chiedono (A1200, A4000, 1.2). Il limite di profondità evita di
            // percorrere un albero enorme se la cartella indicata è una radice.
            try (var s = Files.walk(dir, KICKSTART_SCAN_DEPTH)) {
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

    /** Come lanciare il gioco: cartella, nome dello slave e argomenti per WHDLoad. */
    private record Launch(String gameDir, String slaveName, List<String> options) { }

    /**
     * Ricava dall'archivio estratto il comando WHDLoad da mettere nella
     * Startup-Sequence.
     *
     * <p>La fonte autorevole è l'<b>icona del pacchetto</b>: quella il cui tool di
     * default è {@code WHDLoad} porta nelle tooltype gli argomenti con cui
     * l'installazione va lanciata. Non sono facoltativi — un pacchetto che tiene le
     * immagini dei dischi in una sottocartella dichiara {@code DATA=data}, e senza
     * quell'argomento WHDLoad le cerca accanto allo slave e fallisce con DOS-Error
     * #205 appena il gioco chiede il primo file.
     *
     * <p>Se nessuna icona lo dice (pacchetti vecchi, o icone assenti) si ripiega sul
     * solo file {@code .slave} meno profondo, senza argomenti: è il comportamento
     * storico ed è giusto per i pacchetti che tengono tutto in una cartella sola.
     */
    private Launch findLaunch(List<LhaExtractor.Entry> entries, Path work) {
        Launch fromIcon = launchFromIcons(entries, work);
        if (fromIcon != null) return fromIcon;

        // Preferisci lo slave meno profondo (percorso più corto).
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
     * Cerca fra le icone estratte quella che lancia WHDLoad e ne traduce le tooltype
     * in un {@link Launch}. Restituisce {@code null} se non ce n'è nessuna utilizzabile.
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
            // Lo SLAVE= può contenere un percorso relativo alla cartella dell'icona.
            // La verifica va fatta sul percorso COME STA SU DISCO (diskPath), che per
            // i nomi che Windows non sa scrivere differisce da quello Amiga.
            String diskDir = e.diskPath.contains("/")
                    ? e.diskPath.substring(0, e.diskPath.lastIndexOf('/')) : "";
            String slaveOnDisk = diskDir.isEmpty() ? slave : diskDir + "/" + slave;
            if (!Files.isRegularFile(work.resolve(slaveOnDisk.replace('/', java.io.File.separatorChar)))) continue;
            Launch candidate = new Launch(dir, slave, options);
            // A parità di validità vince l'icona meno profonda.
            if (best == null || candidate.gameDir().length() < best.gameDir().length()) best = candidate;
        }
        return best;
    }

    /** {@code true} se il tool di default dell'icona è WHDLoad (con o senza percorso). */
    private static boolean isWhdLoadTool(String tool) {
        if (tool == null) return false;
        String leaf = tool;
        int cut = Math.max(leaf.lastIndexOf('/'), leaf.lastIndexOf(':'));
        if (cut >= 0) leaf = leaf.substring(cut + 1);
        return leaf.equalsIgnoreCase("WHDLoad");
    }

    /**
     * {@code true} se la tooltype è un argomento vero. Vanno scartate le voci
     * disattivate (che il Workbench conserva fra parentesi), i separatori e i dati
     * delle icone NewIcon, che vivono nello stesso elenco ({@code IM1=}/{@code IM2=}
     * e la riga di intestazione che li precede).
     */
    private static boolean isRealToolType(String tt) {
        if (tt == null) return false;
        String s = tt.trim();
        if (s.isEmpty() || s.startsWith("(") || s.startsWith(";")) return false;
        if (s.regionMatches(true, 0, "IM1=", 0, 4) || s.regionMatches(true, 0, "IM2=", 0, 4)) return false;
        return !s.startsWith("*** ");
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
     * @param options     argomenti WHDLoad dell'icona del pacchetto, vedi {@link #findLaunch}
     * @param savesVolume nome del volume dei salvataggi, da {@link #savesVolumeName()}
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
        // Salvataggi sul volume persistente dedicato. Se il volume si chiama già
        // WHDSaves, "WHDSaves:" si risolve da sé e non serve alcun assign; i volumi
        // creati dalle versioni precedenti si chiamano "Saves" e vanno assegnati.
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
        // PRELOAD carica tutto in memoria una volta sola: evita gli accessi al disco
        // durante il gioco. Lo aggiungiamo solo se l'icona non lo chiede già.
        if (!preload) sb.append(" PRELOAD");
        sb.append('\n');
        return sb.toString();
    }

    /**
     * Racchiude fra virgolette un argomento che contiene spazi, come vuole ReadArgs
     * di AmigaDOS; gli altri restano nudi.
     */
    private static String quoteArg(String arg) {
        return arg.indexOf(' ') >= 0 ? "\"" + arg + "\"" : arg;
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
