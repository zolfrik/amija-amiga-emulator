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

package io.github.zolfrik.amija.config;

import io.github.zolfrik.amija.common.AppHome;
import io.github.zolfrik.amija.common.Chipset;
import io.github.zolfrik.amija.common.VideoMode;
import io.github.zolfrik.amija.display.AmigaWindowMode;
import io.github.zolfrik.amija.display.render.RendererType;
import io.github.zolfrik.amija.input.JoystickAction;
import io.github.zolfrik.amija.input.JoystickKeyMap;
import io.github.zolfrik.amija.input.KeyBinding;
import io.github.zolfrik.amija.input.PortDevice;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Properties;

/**
 * Configurazione dell'emulatore caricata da un file {@code amiga.properties}.
 *
 * <p>Strategia di ricerca del file (in ordine):
 * <ol>
 *   <li>{@code amiga.properties} nella home dell'applicazione ({@link AppHome}, che
 *       di norma è la directory di lavoro);</li>
 *   <li>{@code /amiga.properties} sul classpath (es. {@code src/main/resources});</li>
 *   <li>se non esiste in nessuno dei due posti, viene <b>creato</b> nella home con i
 *       valori di default e usato.</li>
 * </ol>
 *
 * <p>Proprietà gestite:
 * <ul>
 *   <li>{@code auto.enabled} – modalità automatica (default {@code true}): Kickstart,
 *       memorie, chipset e modalità video sono dedotti dal supporto attivo
 *       ({@code auto.*}) invece che scelti a mano;</li>
 *   <li>{@code kickstart.path} – percorso assoluto del file ROM Kickstart;</li>
 *   <li>{@code chipram.kb} / {@code slowram.kb} – quantità di Chip/Slow RAM in KB;</li>
 *   <li>{@code drive.df0..df3.enabled} – se il drive è collegato;</li>
 *   <li>{@code drive.df0..df3.disk} – percorso assoluto del .adf/.ipf da inserire
 *       automaticamente all'avvio;</li>
 *   <li>{@code floppy.sound.volume} – volume dei suoni del floppy: 0 = nessun suono,
 *       1 = volume minimo, poi a salire;</li>
 *   <li>{@code leds.visible} – mostra o nasconde il pannello dei LED power/drive.</li>
 * </ul>
 */
public final class AmigaConfig {

    /** Nome del file di configurazione cercato nella dir corrente e sul classpath. */
    public static final String FILE_NAME = "amiga.properties";

    // Chiavi delle proprietà.
    private static final String K_AUTO_EN     = "auto.enabled";
    private static final String K_AUTO_KS13   = "auto.kickstart13.path";
    private static final String K_AUTO_KS31   = "auto.kickstart31.path";
    private static final String K_KICKSTART   = "kickstart.path";
    private static final String K_CHIPRAM_KB  = "chipram.kb";
    private static final String K_SLOWRAM_KB  = "slowram.kb";
    private static final String K_FASTRAM_KB  = "fastram.kb";
    private static final String K_DRIVE_EN    = "drive.df%d.enabled";
    private static final String K_DRIVE_DISK  = "drive.df%d.disk";
    private static final String K_DRIVE_RO    = "drive.df%d.readonly";
    private static final String K_HD_EN       = "hd.%s.enabled";
    private static final String K_HD_DISK     = "hd.%s.disk";
    private static final String K_HD_RO       = "hd.%s.readonly";
    private static final String K_WHDLOAD_EN   = "whdload.enabled";
    private static final String K_WHDLOAD_FILE = "whdload.file";
    private static final String K_LAST_MEDIA_DIR  = "chooser.media.lastDirectory";
    private static final String K_LAST_FLOPPY_DIR = "chooser.floppy.lastDirectory";
    private static final String K_LAST_ROM_DIR    = "chooser.rom.lastDirectory";
    private static final String K_LAST_HDF_DIR    = "chooser.hdf.lastDirectory";
    private static final String K_LAST_WHDLOAD_DIR = "chooser.whdload.lastDirectory";

    /** Unità hard disk: indice 0 = master, indice 1 = slave. */
    public static final String[] HD_UNITS = { "master", "slave" };
    private static final String K_SND_VOLUME  = "floppy.sound.volume";
    private static final String K_LEDS        = "leds.visible";
    /** Periferica host collegata a una game port: {@code input.port0.device} / {@code input.port1.device}. */
    private static final String K_PORT_DEVICE = "input.port%d.device";
    /** Tasto del joystick da tastiera: {@code input.port<N>.joystick.<up|down|right|left|fire>}. */
    private static final String K_JOY_KEY = "input.port%d.joystick.%s";
    private static final String K_LANGUAGE    = "ui.language";
    private static final String K_UI_SCALE    = "ui.scale";
    private static final String K_VIDEO_MODE  = "video.mode";
    private static final String K_CHIPSET     = "chipset";
    private static final String K_INTEGER_SCALING = "video.integerScaling";
    private static final String K_OVERSCAN = "video.overscan.enabled";
    private static final String K_WINDOW_MODE = "video.windowMode";
    private static final String K_MONITOR = "video.monitor";
    private static final String K_RENDERER = "video.renderer";
    private static final String K_SHADER = "video.shader";
    // Overlay diagnostici (NON esposti nel popup F12): solo da file.
    private static final String K_OVERLAY_FPS = "overlay.fps.enabled";
    private static final String K_OVERLAY_FRAMECOUNTER = "overlay.framecounter.enabled";
    // Immediate blit (NON esposto nel popup F12): solo da file.
    private static final String K_BLITTER_IMMEDIATE = "blitter.immediate";

    // Gioco a distanza (docs/remote-play-plan.md §10). Il join code NON si salva su file:
    // e' un segreto di sessione e si rigenera a ogni avvio dell'host.
    private static final String K_RP_PORT = "remoteplay.port";
    private static final String K_RP_UPNP = "remoteplay.upnp";
    private static final String K_RP_BIND = "remoteplay.bind";
    private static final String K_RP_LAN_ONLY = "remoteplay.lan.only";
    private static final String K_RP_GUEST_PORT = "remoteplay.guest.port";
    private static final String K_RP_GUEST_KEYBOARD = "remoteplay.guest.keyboard";
    private static final String K_RP_GUEST_ALLOWRESET = "remoteplay.guest.allowreset";
    private static final String K_RP_AUDIO_MODE = "remoteplay.audio.mode";
    private static final String K_RP_LAST_HOST = "remoteplay.last.host";

    // =========================================================================
    // Parametri WHDLoad (sezione whdload.prefs.* in fondo ad amiga.properties).
    // Da qui viene GENERATO il file S:WHDLoad.prefs dell'hard disk WHDLoad creato
    // al volo. NON esposti nel popup F12: solo da file.
    // =========================================================================

    /** Tipo di parametro WHDLoad: keyword di presenza (flag) o coppia nome=valore. */
    private enum WhdPrefType { FLAG, VALUE }

    /**
     * Descrittore di un parametro WHDLoad: chiave in {@code amiga.properties},
     * keyword corrispondente nel file {@code WHDLoad.prefs}, tipo, default e commento.
     */
    private record WhdPref(String key, String whdName, WhdPrefType type, String def, String comment) { }

    /**
     * Tabella di tutti i parametri WHDLoad gestiti. L'ordine determina sia la sezione
     * in {@code amiga.properties} sia l'ordine delle voci nel {@code WHDLoad.prefs}
     * generato. Per i {@code FLAG} il valore è {@code true}/{@code false} (true = la
     * keyword è presente); per i {@code VALUE} è la stringa da emettere (vuota = omessa).
     */
    private static final WhdPref[] WHD_PREFS = {
        new WhdPref("whdload.prefs.buttonWait",     "ButtonWait",     WhdPrefType.FLAG,  "false",     "ButtonWait: wait for a button press before starting (false = start automatically)."),
        new WhdPref("whdload.prefs.configDelay",    "ConfigDelay",    WhdPrefType.VALUE, "0",         "ConfigDelay: time allowed to pick options in the splash window (1/50 s)."),
        new WhdPref("whdload.prefs.splashDelay",    "SplashDelay",    WhdPrefType.VALUE, "250",       "SplashDelay: how long the WHDLoad splash window stays up (1/50 s). 250 = 5 seconds."),
        new WhdPref("whdload.prefs.readDelay",      "ReadDelay",      WhdPrefType.VALUE, "0",         "ReadDelay/WriteDelay: delay after a disk read/write (1/50 s)."),
        new WhdPref("whdload.prefs.writeDelay",     "WriteDelay",     WhdPrefType.VALUE, "0",         null),
        new WhdPref("whdload.prefs.coreDumpPath",   "CoreDumpPath",   WhdPrefType.VALUE, "T:",        "CoreDumpPath: where to store any coredump files."),
        new WhdPref("whdload.prefs.savePath",       "SavePath",       WhdPrefType.VALUE, "WHDSaves:", "SavePath: base directory for saved games (persistent volume Saves: -> WHDSaves:)."),
        new WhdPref("whdload.prefs.noNetwork",      "NoNetwork",      WhdPrefType.FLAG,  "true",      "NoNetwork: do not connect to the internet."),
        new WhdPref("whdload.prefs.noWriteCache",   "NoWriteCache",   WhdPrefType.FLAG,  "true",      "NoWriteCache: disable the disk write cache."),
        new WhdPref("whdload.prefs.noWriteInfo",    "NoWriteInfo",    WhdPrefType.FLAG,  "false",     "NoWriteInfo: do not write .info files for saved games."),
        new WhdPref("whdload.prefs.chipNoCache",    "ChipNoCache",    WhdPrefType.FLAG,  "false",     "ChipNoCache: disable cacheability of Chip memory."),
        new WhdPref("whdload.prefs.fullChip",       "FullChip",       WhdPrefType.FLAG,  "false",     "FullChip: save/restore the whole Chip memory."),
        new WhdPref("whdload.prefs.noFlushMem",     "NoFlushMem",     WhdPrefType.FLAG,  "false",     "NoFlushMem: do not free memory before loading."),
        new WhdPref("whdload.prefs.noMemReverse",   "NoMemReverse",   WhdPrefType.FLAG,  "false",     "NoMemReverse: do not allocate memory in reverse order."),
        new WhdPref("whdload.prefs.preload",        "Preload",        WhdPrefType.FLAG,  "false",     "Preload: buffer many data files up front at startup."),
        new WhdPref("whdload.prefs.logBuffer",      "LogBuffer",      WhdPrefType.VALUE, "",          "LogBuffer: size of the logging buffer (e.g. $40000; empty = default)."),
        new WhdPref("whdload.prefs.ntsc",           "NTSC",           WhdPrefType.FLAG,  "false",     "NTSC/PAL: force the video mode."),
        new WhdPref("whdload.prefs.pal",            "PAL",            WhdPrefType.FLAG,  "false",     null),
        new WhdPref("whdload.prefs.noFilter",       "NoFilter",       WhdPrefType.FLAG,  "false",     "NoFilter: disable the audio filter."),
        new WhdPref("whdload.prefs.noAutoVec",      "NoAutoVec",      WhdPrefType.FLAG,  "false",     "NoAutoVec: ignore unwanted autovector interrupts."),
        new WhdPref("whdload.prefs.mmu",            "MMU",            WhdPrefType.FLAG,  "false",     "MMU: use the MMU (on 68030+)."),
        new WhdPref("whdload.prefs.quitKey",        "QuitKey",        WhdPrefType.VALUE, "",          "QuitKey/RestartKey/FreezeKey/DebugKey: special keys (rawkey, e.g. $5a; empty = default)."),
        new WhdPref("whdload.prefs.restartKey",     "RestartKey",     WhdPrefType.VALUE, "",          null),
        new WhdPref("whdload.prefs.freezeKey",      "FreezeKey",      WhdPrefType.VALUE, "",          null),
        new WhdPref("whdload.prefs.debugKey",       "DebugKey",       WhdPrefType.VALUE, "",          null),
        new WhdPref("whdload.prefs.expert",         "Expert",         WhdPrefType.FLAG,  "false",     "Expert: enable expert mode."),
        new WhdPref("whdload.prefs.executeStartup", "ExecuteStartup", WhdPrefType.VALUE, "",          "ExecuteStartup/ExecuteCleanup: commands run before/after (e.g. Execute S:WHDLoad-Startup)."),
        new WhdPref("whdload.prefs.executeCleanup", "ExecuteCleanup", WhdPrefType.VALUE, "",          null),
    };

    // =========================================================================
    // Modalità automatica (auto.*)
    // =========================================================================

    /**
     * Profilo hardware della modalità Auto: prefisso delle chiavi in
     * {@code amiga.properties} e valori di default se l'utente non li ha ridefiniti.
     * Ce ne sono due, scelti in base al supporto attivo (vedi {@link #autoProfile}).
     */
    private record AutoDefaults(String prefix, int chipRamKb, int slowRamKb, int fastRamKb,
            Chipset chipset) {

        int chipRamKb(AmigaConfig c) { return c.getInt(prefix + "chipram.kb", chipRamKb); }

        int slowRamKb(AmigaConfig c) { return c.getInt(prefix + "slowram.kb", slowRamKb); }

        int fastRamKb(AmigaConfig c) { return c.getInt(prefix + "fastram.kb", fastRamKb); }

        Chipset chipset(AmigaConfig c) {
            return parseChipset(c.props.getProperty(prefix + "chipset"), chipset);
        }
    }

    /** Solo floppy: la configurazione dell'A500 base che quasi tutti i giochi si aspettano. */
    private static final AutoDefaults AUTO_FLOPPY =
            new AutoDefaults("auto.floppy.", 512, 512, 0, Chipset.OCS);

    /**
     * Hard disk o WHDLoad attivo: la macchina espansa che quel software richiede.
     *
     * <p>2 MB di Chip RAM, non 1: è l'A1200 che gli installer WHDLoad danno per
     * scontato. Ogni slave dichiara nell'header (ws_BaseMemSize) quanta memoria
     * contigua vuole a partire da $000000, e quel blocco può stare solo in Chip;
     * quando non ce n'è abbastanza WHDLoad si ferma con "you need chipmem up to
     * $xxxxxx". Ce ne sono parecchi sopra 1 MB — p.es. Dragon's Lair chiede
     * $180000 (1,5 MB) — mentre chi ne vuole meno gira identico su una macchina
     * più grande: il massimo è la sola scelta che non fallisce mai.
     */
    private static final AutoDefaults AUTO_EXPANDED =
            new AutoDefaults("auto.hd.", 2048, 512, 8192, Chipset.ECS);

    private static final AutoDefaults[] AUTO_PROFILES = { AUTO_FLOPPY, AUTO_EXPANDED };

    /**
     * Impostazioni di sistema dedotte dalla modalità Auto: quello che la macchina
     * userà davvero al prossimo avvio senza che l'utente debba scegliere nulla.
     *
     * @param expanded {@code true} se è attivo un hard disk o WHDLoad (profilo espanso)
     */
    public record AutoProfile(String kickstartPath, boolean expanded, int chipRamKb,
            int slowRamKb, int fastRamKb, Chipset chipset, VideoMode videoMode) { }

    private final Properties props;

    private AmigaConfig(Properties props) {
        this.props = props;
    }

    // =========================================================================
    // Caricamento
    // =========================================================================

    /**
     * Carica la configurazione seguendo l'ordine di ricerca descritto nella classe.
     * Non lancia mai: in caso di problemi di I/O ripiega sui valori di default in
     * memoria.
     */
    public static AmigaConfig load() {
        // 1. File nella home dell'applicazione (di norma la directory di lavoro).
        Path homeFile = AppHome.resolve(FILE_NAME);
        if (Files.isRegularFile(homeFile)) {
            // UTF-8, e con un Reader: vedi il commento di save(). Con un InputStream
            // Properties decodifica ISO-8859-1, che e' cio' che corrompeva i percorsi
            // accentati a ogni giro di salvataggio e rilettura.
            try (Reader in = Files.newBufferedReader(homeFile, StandardCharsets.UTF_8)) {
                Properties p = new Properties();
                p.load(in);
                System.out.println("[CONFIG] Loaded " + homeFile.toAbsolutePath());
                return new AmigaConfig(p);
            } catch (MalformedInputException e) {
                // File scritto prima che le due meta' fossero d'accordo, oppure a mano
                // con l'editor di sistema: non e' UTF-8 valido. Si rilegge in Latin-1,
                // che accetta qualunque byte, cosi' la configurazione non va persa; il
                // primo salvataggio la riporta in UTF-8.
                System.err.println("[CONFIG] " + homeFile + " non e' UTF-8 valido, "
                        + "rileggo in ISO-8859-1 (sara' riscritto in UTF-8 al salvataggio)");
                try (InputStream in = Files.newInputStream(homeFile)) {
                    Properties p = new Properties();
                    p.load(in);
                    return new AmigaConfig(p);
                } catch (IOException e2) {
                    System.err.println("[CONFIG] Failed to read " + homeFile + ": " + e2.getMessage());
                }
            } catch (IOException e) {
                System.err.println("[CONFIG] Failed to read " + homeFile + ": " + e.getMessage());
            }
        }

        // 2. File sul classpath (es. src/main/resources/amiga.properties). UTF-8 come
        //    gli altri due percorsi: la charset di questo file dev'essere UNA, o si torna
        //    al disaccordo che corrompeva i percorsi accentati.
        try (InputStream in = AmigaConfig.class.getResourceAsStream("/" + FILE_NAME)) {
            if (in != null) {
                Properties p = new Properties();
                p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                System.out.println("[CONFIG] Loaded from classpath /" + FILE_NAME);
                return new AmigaConfig(p);
            }
        } catch (IOException e) {
            System.err.println("[CONFIG] Failed to read from classpath: " + e.getMessage());
        }

        // 3. Nessun file: crea quello di default nella home.
        Properties defaults = defaultProperties();
        try (Writer out = Files.newBufferedWriter(homeFile, StandardCharsets.UTF_8)) {
            // store(Writer) e non store(OutputStream): il secondo scrive ISO-8859-1 e
            // rimetterebbe le due meta' in disaccordo appena un default smettesse di
            // essere ASCII.
            defaults.store(out, "AmiJa configuration - Java Amiga emulator");
            System.out.println("[CONFIG] Created default file " + homeFile.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("[CONFIG] Failed to create " + homeFile + ": " + e.getMessage());
        }
        return new AmigaConfig(defaults);
    }

    /** Proprietà di default usate alla prima esecuzione (file mancante). */
    private static Properties defaultProperties() {
        Properties p = new Properties();
        // Modalità automatica: attiva di default (è la ragione per cui esiste).
        p.setProperty(K_AUTO_EN, "true");
        p.setProperty(K_AUTO_KS13, "");                 // da impostare a cura dell'utente
        p.setProperty(K_AUTO_KS31, "");
        for (AutoDefaults d : AUTO_PROFILES) {
            p.setProperty(d.prefix() + "chipram.kb", Integer.toString(d.chipRamKb()));
            p.setProperty(d.prefix() + "slowram.kb", Integer.toString(d.slowRamKb()));
            p.setProperty(d.prefix() + "fastram.kb", Integer.toString(d.fastRamKb()));
            p.setProperty(d.prefix() + "chipset", d.chipset().name());
        }
        p.setProperty(K_KICKSTART, "");                 // da impostare a cura dell'utente
        p.setProperty(K_CHIPRAM_KB, "512");
        p.setProperty(K_SLOWRAM_KB, "512");
        p.setProperty(K_FASTRAM_KB, "0");               // nessuna Fast RAM di default
        // Solo DF0 e DF1 attivi di default.
        p.setProperty(String.format(K_DRIVE_EN, 0), "true");
        p.setProperty(String.format(K_DRIVE_EN, 1), "true");
        p.setProperty(String.format(K_DRIVE_EN, 2), "false");
        p.setProperty(String.format(K_DRIVE_EN, 3), "false");
        // Nessun disco inserito di default.
        for (int i = 0; i < 4; i++) {
            p.setProperty(String.format(K_DRIVE_DISK, i), "");
            p.setProperty(String.format(K_DRIVE_RO, i), "false");
        }
        // Hard disk (master/slave): assenti di default.
        for (String unit : HD_UNITS) {
            p.setProperty(String.format(K_HD_EN, unit), "false");
            p.setProperty(String.format(K_HD_DISK, unit), "");
            p.setProperty(String.format(K_HD_RO, unit), "false");
        }
        // WHDLoad: assente di default.
        p.setProperty(K_WHDLOAD_EN, "false");
        p.setProperty(K_WHDLOAD_FILE, "");
        p.setProperty(K_LAST_MEDIA_DIR, "");
        p.setProperty(K_LAST_FLOPPY_DIR, "");
        p.setProperty(K_LAST_ROM_DIR, "");
        p.setProperty(K_LAST_HDF_DIR, "");
        p.setProperty(K_LAST_WHDLOAD_DIR, "");
        p.setProperty(K_SND_VOLUME, "1");               // volume minimo
        p.setProperty(K_LEDS, "true");                   // pannello LED visibile
        // Periferiche delle game port: Port 0 = mouse, Port 1 = joystick da tastiera.
        p.setProperty(String.format(K_PORT_DEVICE, 0), PortDevice.MOUSE.configValue());
        p.setProperty(String.format(K_PORT_DEVICE, 1), PortDevice.JOYSTICK_KEYBOARD.configValue());
        // Tasti del joystick da tastiera: Port 0 = W/A/S/D + Ctrl destro,
        // Port 1 = frecce + Ctrl sinistro.
        for (int port = 0; port < 2; port++) {
            JoystickKeyMap map = JoystickKeyMap.defaults(port);
            for (JoystickAction a : JoystickAction.values()) {
                p.setProperty(String.format(K_JOY_KEY, port, a.configKey()),
                        map.get(a).configValue());
            }
        }
        p.setProperty(K_LANGUAGE, "en");                 // lingua di default: inglese
        p.setProperty(K_VIDEO_MODE, VideoMode.PAL.name()); // modalita' video di default: PAL
        p.setProperty(K_CHIPSET, Chipset.OCS.name());      // chipset di default: OCS
        // Le DUE scale, separate e indipendenti. Vanno tenute entrambe qui: questo e' il
        // file che l'utente si ritrova alla prima esecuzione, e una chiave assente e' una
        // funzione che non sa di avere.
        p.setProperty(K_INTEGER_SCALING, "1");            // finestra emulata a dimensione nativa
        p.setProperty(K_UI_SCALE, "1");                   // interfaccia a dimensione nativa
        p.setProperty(K_OVERSCAN, "true");               // overscan abilitato di default
        p.setProperty(K_WINDOW_MODE, AmigaWindowMode.WINDOWED.configValue());
        p.setProperty(K_MONITOR, "0");                   // 0 = monitor principale
        p.setProperty(K_RENDERER, RendererType.SOFTWARE.configValue());
        p.setProperty(K_SHADER, "none");
        p.setProperty(K_OVERLAY_FPS, "false");           // overlay FPS disattivo
        p.setProperty(K_OVERLAY_FRAMECOUNTER, "false");  // contatore fotogrammi disattivo
        p.setProperty(K_BLITTER_IMMEDIATE, "false");     // immediate blit disattivo (cycle-exact)
        // Gioco a distanza: spento finche' l'utente non preme "Ospita" nel popup. Le chiavi
        // ci sono comunque, perche' una chiave assente e' una funzione che non sa di avere.
        p.setProperty(K_RP_PORT, "7788");
        p.setProperty(K_RP_UPNP, "true");
        p.setProperty(K_RP_BIND, "");
        p.setProperty(K_RP_LAN_ONLY, "true");
        p.setProperty(K_RP_GUEST_PORT, "1");             // la porta joystick all'ospite
        p.setProperty(K_RP_GUEST_KEYBOARD, "true");
        p.setProperty(K_RP_GUEST_ALLOWRESET, "false");
        p.setProperty(K_RP_AUDIO_MODE, "adpcm");         // §15.5, chiusa ascoltando
        p.setProperty(K_RP_LAST_HOST, "");
        // Parametri WHDLoad (sezione whdload.prefs.*): default come da WHDLoad.prefs storico.
        for (WhdPref pref : WHD_PREFS) {
            p.setProperty(pref.key(), pref.def());
        }
        return p;
    }

    // =========================================================================
    // Modalità automatica: deduzione del profilo di sistema
    // =========================================================================

    /**
     * {@code true} (default) se la macchina si configura da sé: Kickstart, memorie,
     * chipset e modalità video sono dedotti dal supporto attivo invece che scelti a
     * mano. Nel popup F12 è la casella <b>"Expert mode"</b>, che vale l'opposto: spenta
     * (il default) la macchina si configura da sé, accesa valgono le impostazioni
     * salvate dall'utente e tutto si comporta come prima che questa modalità esistesse.
     */
    public boolean autoEnabled() {
        return getBool(K_AUTO_EN, true);
    }

    /** Kickstart 1.3 della modalità Auto (profilo solo floppy); vuoto se non impostato. */
    public String autoKickstart13() {
        return props.getProperty(K_AUTO_KS13, "").trim();
    }

    /** Kickstart 3.1 della modalità Auto (profilo hard disk/WHDLoad); vuoto se non impostato. */
    public String autoKickstart31() {
        return props.getProperty(K_AUTO_KS31, "").trim();
    }

    /**
     * {@code true} se è attivo almeno un hard disk o l'avvio WHDLoad, cioè se la
     * modalità Auto deve usare il profilo espanso. "Attivo" vuol dire spuntato
     * <i>e</i> con un file indicato: un'unità spuntata ma vuota non monta nulla.
     */
    public boolean expandedMediaActive() {
        for (int unit = 0; unit < HD_UNITS.length; unit++) {
            if (hdEnabled(unit) && hdDisk(unit) != null) return true;
        }
        return whdloadEnabled() && whdloadFile() != null;
    }

    /**
     * Tutti i file effettivamente in uso: i dischi dei drive collegati, gli hard disk
     * montati e l'archivio WHDLoad. È l'insieme su cui la modalità Auto cerca gli
     * indizi di regione (vedi {@link #impliesNtsc}).
     */
    public java.util.List<String> activeMediaFiles() {
        java.util.List<String> files = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            if (driveEnabled(i) && driveDisk(i) != null) files.add(driveDisk(i));
        }
        for (int unit = 0; unit < HD_UNITS.length; unit++) {
            if (hdEnabled(unit) && hdDisk(unit) != null) files.add(hdDisk(unit));
        }
        if (whdloadEnabled() && whdloadFile() != null) files.add(whdloadFile());
        return files;
    }

    /**
     * {@code true} se uno dei file attivi si dichiara NTSC nel proprio <b>nome</b>:
     * la stringa {@code NTSC} oppure {@code (USA)}, senza distinzione fra maiuscole e
     * minuscole. Sono le due convenzioni con cui le raccolte di immagini (TOSEC,
     * No-Intro, WHDLoad) marcano le versioni a 60 Hz, che su una macchina PAL girano
     * troppo lente o con il quadro tagliato.
     *
     * <p>Si guarda al solo nome del file, non all'intero percorso: una cartella
     * chiamata p.es. {@code USA} o {@code NTSC dumps} non deve trascinare in NTSC
     * tutto quello che contiene.
     */
    public static boolean impliesNtsc(java.util.List<String> files) {
        for (String f : files) {
            if (f == null) continue;
            String name = fileName(f).toUpperCase(java.util.Locale.ROOT);
            if (name.contains("NTSC") || name.contains("(USA)")) return true;
        }
        return false;
    }

    /** Ultimo segmento di un percorso, qualunque separatore usi (o l'intera stringa). */
    private static String fileName(String path) {
        int cut = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return cut < 0 ? path : path.substring(cut + 1);
    }

    /**
     * Impostazioni di sistema dedotte dalla modalità Auto per il supporto descritto dai
     * parametri; {@link #autoProfile()} è la stessa cosa applicata allo stato corrente.
     *
     * <p>Il profilo è uno dei due: solo floppy (A500 base, Kickstart 1.3) oppure hard
     * disk/WHDLoad (macchina espansa, Kickstart 3.1). Ogni valore viene da
     * {@code amiga.properties} ({@code auto.floppy.*} / {@code auto.hd.*}), così chi
     * vuole può cambiarlo a mano senza rinunciare all'automatismo.
     *
     * @param expanded    è attivo un hard disk o WHDLoad
     * @param activeFiles file in uso, per la deduzione PAL/NTSC
     */
    private AutoProfile autoProfile(boolean expanded, java.util.List<String> activeFiles) {
        AutoDefaults d = expanded ? AUTO_EXPANDED : AUTO_FLOPPY;
        return new AutoProfile(autoKickstartPath(expanded), expanded,
                d.chipRamKb(this), d.slowRamKb(this), d.fastRamKb(this), d.chipset(this),
                impliesNtsc(activeFiles) ? VideoMode.NTSC : VideoMode.PAL);
    }

    /** Impostazioni dedotte dalla modalità Auto per il supporto attualmente configurato. */
    public AutoProfile autoProfile() {
        return autoProfile(expandedMediaActive(), activeMediaFiles());
    }

    /**
     * ROM scelta dalla modalità Auto: il Kickstart 3.1 per il profilo espanso, l'1.3
     * per il solo floppy. Se la casella del profilo è vuota si ripiega sull'altra e
     * infine sul Kickstart impostato a mano — così chi arriva da una configurazione
     * precedente (dove esisteva solo {@code kickstart.path}) parte comunque.
     */
    private String autoKickstartPath(boolean expanded) {
        String preferred = expanded ? autoKickstart31() : autoKickstart13();
        String other = expanded ? autoKickstart13() : autoKickstart31();
        if (!preferred.isEmpty()) return preferred;
        if (!other.isEmpty()) return other;
        return storedKickstartPath();
    }

    // =========================================================================
    // Getter tipizzati (con fallback ai default se la chiave manca/è invalida)
    //
    // Con la modalità Auto attiva i parametri di sistema non sono più quelli salvati
    // ma quelli dedotti dal supporto: i getter qui sotto restituiscono il valore
    // EFFETTIVO, cioè quello con cui la macchina viene costruita. I valori scelti a
    // mano restano intatti nel file (getter stored*) e tornano in vigore appena Auto
    // viene spento.
    // =========================================================================

    /** Percorso assoluto del Kickstart, o stringa vuota se non configurato. */
    public String kickstartPath() {
        return autoEnabled() ? autoProfile().kickstartPath() : storedKickstartPath();
    }

    /** Chip RAM in KB (default 512). */
    public int chipRamKb() {
        return autoEnabled() ? autoProfile().chipRamKb() : storedChipRamKb();
    }

    /** Slow RAM in KB (default 512); 0 = nessuna Slow RAM. */
    public int slowRamKb() {
        return autoEnabled() ? autoProfile().slowRamKb() : storedSlowRamKb();
    }

    /**
     * Fast RAM in KB (default 0 = nessuna Fast RAM). Espansione Zorro II con
     * AutoConfig: valori validi 0/512/1024/2048/4096/8192 (potenze di due da
     * 512 KB a 8 MB). Serve per i giochi via hard disk e WHDLoad.
     */
    public int fastRamKb() {
        return autoEnabled() ? autoProfile().fastRamKb() : storedFastRamKb();
    }

    /** Kickstart scelto a mano (chiave {@code kickstart.path}), ignorato da Auto. */
    public String storedKickstartPath() {
        return props.getProperty(K_KICKSTART, "").trim();
    }

    /** Chip RAM scelta a mano, ignorata da Auto. */
    public int storedChipRamKb() {
        return getInt(K_CHIPRAM_KB, 512);
    }

    /** Slow RAM scelta a mano, ignorata da Auto. */
    public int storedSlowRamKb() {
        return getInt(K_SLOWRAM_KB, 512);
    }

    /** Fast RAM scelta a mano, ignorata da Auto. */
    public int storedFastRamKb() {
        return getInt(K_FASTRAM_KB, 0);
    }

    /** {@code true} se il drive {@code df<index>} (0..3) è collegato. */
    public boolean driveEnabled(int index) {
        boolean def = (index == 0 || index == 1); // df0/df1 attivi di default
        return getBool(String.format(K_DRIVE_EN, index), def);
    }

    /**
     * Percorso assoluto del .adf/.ipf da inserire nel drive {@code df<index>} (0..3),
     * o {@code null} se non configurato.
     */
    public String driveDisk(int index) {
        String v = props.getProperty(String.format(K_DRIVE_DISK, index), "").trim();
        return v.isEmpty() ? null : v;
    }

    /** {@code true} se il disco del drive {@code df<index>} va montato in sola lettura. */
    public boolean driveReadOnly(int index) {
        return getBool(String.format(K_DRIVE_RO, index), false);
    }

    /** {@code true} se l'unità hard disk {@code unit} (0=master, 1=slave) è collegata. */
    public boolean hdEnabled(int unit) {
        return getBool(String.format(K_HD_EN, hdUnitName(unit)), false);
    }

    /**
     * Percorso assoluto del file {@code .hdf} da montare sull'unità {@code unit}
     * (0=master, 1=slave), o {@code null} se non configurato.
     */
    public String hdDisk(int unit) {
        String v = props.getProperty(String.format(K_HD_DISK, hdUnitName(unit)), "").trim();
        return v.isEmpty() ? null : v;
    }

    /** {@code true} se l'hard disk dell'unità {@code unit} va montato in sola lettura. */
    public boolean hdReadOnly(int unit) {
        return getBool(String.format(K_HD_RO, hdUnitName(unit)), false);
    }

    /** Ultima cartella usata dal browser degli .hdf, o stringa vuota se non configurata. */
    public String lastHdfDirectory() {
        return props.getProperty(K_LAST_HDF_DIR, "").trim();
    }

    /** {@code true} se l'avvio automatico WHDLoad è abilitato. */
    public boolean whdloadEnabled() {
        return getBool(K_WHDLOAD_EN, false);
    }

    /**
     * URL da cui l'assistente di download preleva il pacchetto {@code id}
     * (chiave {@code assets.<id>.url}), oppure {@code fallback} se non configurato.
     *
     * <p>Esiste per una ragione pratica: quegli URL puntano a siti di terzi e prima o
     * poi cambieranno. Questa chiave permette di correggerli senza ricompilare. Vale
     * comunque la regola di {@code THIRD-PARTY-NOTICES.md} §5: la fonte dev'essere
     * quella ufficiale del titolare dei diritti, mai un mirror o un sito di
     * abandonware.
     */
    public String assetUrl(String id, String fallback) {
        String v = props.getProperty("assets." + id + ".url", "").trim();
        return v.isEmpty() ? fallback : v;
    }

    /**
     * Percorso assoluto dell'archivio WHDLoad ({@code .lha}/{@code .lzh}) da avviare
     * automaticamente, o {@code null} se non configurato. Viene estratto e impacchettato
     * in un hard disk avviabile (vedi {@code WhdLoadManager}).
     */
    public String whdloadFile() {
        String v = props.getProperty(K_WHDLOAD_FILE, "").trim();
        return v.isEmpty() ? null : v;
    }

    /**
     * Genera il contenuto del file {@code S:WHDLoad.prefs} a partire dalla sezione
     * {@code whdload.prefs.*} di {@code amiga.properties}. I flag {@code true} emettono
     * la keyword WHDLoad corrispondente; i valori vuoti vengono omessi. Usato da
     * {@code WhdLoadManager} durante la costruzione dell'hard disk WHDLoad.
     */
    public String whdloadPrefsContent() {
        StringBuilder sb = new StringBuilder();
        sb.append(";; WHDLoad.prefs generated automatically from the whdload.prefs.* section of amiga.properties.\n");
        sb.append(";; Do not edit by hand: make your changes in amiga.properties instead.\n");
        for (WhdPref pref : WHD_PREFS) {
            String v = props.getProperty(pref.key(), pref.def()).trim();
            if (pref.type() == WhdPrefType.FLAG) {
                if (Boolean.parseBoolean(v)) sb.append(pref.whdName()).append('\n');
            } else if (!v.isEmpty()) {
                sb.append(pref.whdName()).append('=').append(v).append('\n');
            }
        }
        return sb.toString();
    }

    /** Ultima cartella usata dal browser WHDLoad, o stringa vuota se non configurata. */
    public String lastWhdloadDirectory() {
        return props.getProperty(K_LAST_WHDLOAD_DIR, "").trim();
    }

    private static String hdUnitName(int unit) {
        return HD_UNITS[unit < 0 ? 0 : Math.min(unit, HD_UNITS.length - 1)];
    }

    /** Ultima cartella usata dal browser dei floppy, o stringa vuota se non configurata. */
    public String lastFloppyDirectory() {
        return props.getProperty(K_LAST_FLOPPY_DIR, "").trim();
    }

    /**
     * Ultima cartella usata dal browser unico della modalità Auto, che accetta ogni
     * tipo di supporto. Ha una memoria propria perché mescola floppy, hard disk e
     * archivi WHDLoad; finché è vuota ripiega su quella dei floppy, così chi arriva da
     * una configurazione precedente non riparte dal nulla.
     */
    public String lastMediaDirectory() {
        String v = props.getProperty(K_LAST_MEDIA_DIR, "").trim();
        return v.isEmpty() ? lastFloppyDirectory() : v;
    }

    /** Ultima cartella usata dal browser ROM, o stringa vuota se non configurata. */
    public String lastRomDirectory() {
        return props.getProperty(K_LAST_ROM_DIR, "").trim();
    }

    /** Volume dei suoni floppy: 0 = nessun suono, 1 = minimo, poi a salire (default 1). */
    public int floppySoundVolume() {
        return Math.max(0, getInt(K_SND_VOLUME, 1));
    }

    /** {@code true} se i suoni del floppy sono attivi (volume &gt; 0). */
    public boolean floppySoundEnabled() {
        return floppySoundVolume() > 0;
    }

    /**
     * Converte il volume intero ({@link #floppySoundVolume()}) nell'attenuazione in dB
     * usata da {@code FloppySound}: volume 1 ≈ -36 dB (minimo udibile), +6 dB per ogni
     * passo, con tetto a 0 dB. Significativo solo se {@link #floppySoundEnabled()}.
     */
    public float floppySoundGainDb() {
        int v = floppySoundVolume();
        return Math.min(0f, -42f + v * 6f);
    }

    /** {@code true} se il pannello dei LED power/drive deve essere visibile (default true). */
    public boolean ledsVisible() {
        return getBool(K_LEDS, true);
    }

    /**
     * Periferica host collegata alla game port {@code port} (0 = "Port 0", la porta
     * mouse/JOY0DAT; 1 = "Port 1", la porta joystick/JOY1DAT). Default: Port 0 = mouse,
     * Port 1 = joystick da tastiera.
     */
    public PortDevice portDevice(int port) {
        PortDevice def = (port == 0) ? PortDevice.MOUSE : PortDevice.JOYSTICK_KEYBOARD;
        return PortDevice.fromConfigValue(
                props.getProperty(String.format(K_PORT_DEVICE, port)), def);
    }

    /**
     * Tasti host che pilotano il joystick da tastiera della porta {@code port} (0 o 1).
     * Le chiavi mancanti o non riconosciute ricadono sul default della porta
     * ({@link JoystickKeyMap#defaults(int)}): Port 0 = W/A/S/D + Ctrl destro,
     * Port 1 = frecce + Ctrl sinistro.
     */
    public JoystickKeyMap joystickKeyMap(int port) {
        JoystickKeyMap map = JoystickKeyMap.defaults(port);
        for (JoystickAction action : JoystickAction.values()) {
            String key = String.format(K_JOY_KEY, port, action.configKey());
            String value = props.getProperty(key);
            if (value == null || value.trim().isEmpty()) continue;
            KeyBinding binding = KeyBinding.parse(value);
            if (binding != null) {
                map.set(action, binding);
            } else {
                System.err.println("[CONFIG] Invalid joystick key '" + value.trim()
                        + "' for " + key + ", using the default");
            }
        }
        return map;
    }

    /** Codice lingua dell'interfaccia ({@code "en"} o {@code "it"}; default {@code "en"}). */
    public String language() {
        return props.getProperty(K_LANGUAGE, "en").trim();
    }

    /** Fattore di scala minimo e massimo accettati per l'interfaccia. */
    public static final double UI_SCALE_MIN = 1.0;
    public static final double UI_SCALE_MAX = 4.0;

    /**
     * Fattore di scala della <b>sola interfaccia</b> (popup F12, dialoghi, chooser);
     * 1 = dimensione nativa. A differenza di {@link #integerScaling()} può essere
     * frazionario — un artefatto nell'interfaccia non tocca la qualità dell'emulazione —
     * e non ha alcun effetto sulla finestra emulata.
     *
     * <p>Non esiste una modalità "automatica": il fattore di scala del desktop non viene
     * né letto né usato, e chi non tocca niente ottiene 1. Vedi
     * {@code docs/gui-scaling-flatlaf-plan.md}.
     */
    public double uiScale() {
        double v = getDouble(K_UI_SCALE, 1.0);
        if (v < UI_SCALE_MIN || v > UI_SCALE_MAX) {
            System.err.println("[CONFIG] " + K_UI_SCALE + "=" + v + " out of range ["
                    + UI_SCALE_MIN + ".." + UI_SCALE_MAX + "], using 1.0");
            return 1.0;
        }
        return v;
    }

    /**
     * Modalita' video della macchina emulata: {@link VideoMode#PAL} (default) o
     * {@link VideoMode#NTSC}. Determina frequenza del Color Clock, numero di righe
     * per frame (312 PAL / 262 NTSC) e l'ID Agnus letto dal Kickstart. Il cambio ha
     * effetto solo a macchina riavviata (cold restart).
     */
    public VideoMode videoMode() {
        return autoEnabled() ? autoProfile().videoMode() : storedVideoMode();
    }

    /** Modalità video scelta a mano (chiave {@code video.mode}), ignorata da Auto. */
    public VideoMode storedVideoMode() {
        String v = props.getProperty(K_VIDEO_MODE, VideoMode.PAL.name()).trim();
        try {
            return VideoMode.valueOf(v.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            System.err.println("[CONFIG] Invalid video mode '" + v + "', using PAL");
            return VideoMode.PAL;
        }
    }

    /**
     * Chipset custom della macchina emulata: {@link Chipset#OCS} (default) o
     * {@link Chipset#ECS}. Seleziona la variante di Agnus/Denise istanziata e abilita
     * i registri ECS-only (BEAMCON0, DIWHIGH, BPLCON3, super-hires, ...). Il cambio ha
     * effetto solo a macchina riavviata (cold restart). Vincolo: chip RAM &gt; 512 KB
     * richiede ECS (validato in fase di boot).
     */
    public Chipset chipset() {
        return autoEnabled() ? autoProfile().chipset() : storedChipset();
    }

    /** Chipset scelto a mano (chiave {@code chipset}), ignorato da Auto. */
    public Chipset storedChipset() {
        return parseChipset(props.getProperty(K_CHIPSET), Chipset.OCS);
    }

    /** Converte il valore testuale di un chipset; {@code def} se manca o non è valido. */
    private static Chipset parseChipset(String value, Chipset def) {
        if (value == null || value.trim().isEmpty()) return def;
        try {
            return Chipset.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            System.err.println("[CONFIG] Invalid chipset '" + value.trim() + "', using " + def);
            return def;
        }
    }

    /**
     * Fattore di integer scaling della <b>sola finestra emulata</b>; 1 = dimensione
     * nativa. Non tocca l'interfaccia: lo scaling della GUI è una property a parte.
     */
    public int integerScaling() {
        return Math.max(1, getInt(K_INTEGER_SCALING, 1));
    }

    /** {@code true} se l'overscan e' abilitato (default true). */
    public boolean overscanEnabled() {
        return getBool(K_OVERSCAN, true);
    }

    /** Modalita' della finestra principale (default windowed). */
    public AmigaWindowMode windowMode() {
        return AmigaWindowMode.fromConfigValue(props.getProperty(K_WINDOW_MODE));
    }

    /**
     * Indice del monitor su cui aprire la finestra dell'emulatore: 0 = schermo
     * principale (default). Se l'indice configurato non corrisponde a un monitor
     * collegato si ricade sul principale (gestito da {@code AmigaDisplay}).
     */
    public int monitorIndex() {
        return Math.max(0, getInt(K_MONITOR, 0));
    }

    /**
     * Backend di rendering del display: {@link RendererType#SOFTWARE} (Java2D, default)
     * o {@link RendererType#OPENGL} (LWJGL, predisposto per gli shader RetroArch). Il
     * cambio richiede la ricreazione della finestra (cold restart), gestito da
     * {@code Amija}.
     */
    public RendererType renderer() {
        return RendererType.fromConfigValue(props.getProperty(K_RENDERER));
    }

    /**
     * Nome dello shader RetroArch selezionato (solo renderer OpenGL): percorso del preset
     * {@code .slangp} o {@code .glslp} relativo alla cartella {@code shaders}, con
     * separatori {@code /} e senza estensione (es. {@code Mega_Bezel/Presets/MBZ__5__POTATO}),
     * oppure {@code "none"} (default) per nessuno shader (passthrough). Applicabile a caldo
     * dal popup F12.
     */
    public String shader() {
        String v = props.getProperty(K_SHADER, "none").trim();
        return v.isEmpty() ? "none" : v;
    }

    /** {@code true} se l'overlay degli FPS reali deve essere mostrato (default false). */
    public boolean fpsOverlayEnabled() {
        return getBool(K_OVERLAY_FPS, false);
    }

    /** {@code true} se l'overlay del contatore fotogrammi emulati deve essere mostrato (default false). */
    public boolean frameCounterOverlayEnabled() {
        return getBool(K_OVERLAY_FRAMECOUNTER, false);
    }

    /**
     * {@code true} se l'immediate blit è attivo (default false). In questa modalità
     * il blitter completa ogni operazione istantaneamente alla scrittura di BLTSIZE
     * (zero cicli di bus emulati, BBUSY mai osservato alto): NON è cycle-exact, ma
     * velocizza i giochi che attendono in polling il blitter. Configurabile solo da
     * {@code amiga.properties}; non esposto nel popup F12.
     */
    public boolean blitterImmediate() {
        return getBool(K_BLITTER_IMMEDIATE, false);
    }

    // =========================================================================
    // Setter (usati dal popup di configurazione prima di {@link #save()})
    // =========================================================================

    public void setAutoEnabled(boolean enabled) {
        props.setProperty(K_AUTO_EN, Boolean.toString(enabled));
    }

    public void setAutoKickstart13(String path) {
        props.setProperty(K_AUTO_KS13, path == null ? "" : path.trim());
    }

    public void setAutoKickstart31(String path) {
        props.setProperty(K_AUTO_KS31, path == null ? "" : path.trim());
    }

    public void setKickstartPath(String path) {
        props.setProperty(K_KICKSTART, path == null ? "" : path.trim());
    }

    public void setChipRamKb(int kb) {
        props.setProperty(K_CHIPRAM_KB, Integer.toString(kb));
    }

    public void setSlowRamKb(int kb) {
        props.setProperty(K_SLOWRAM_KB, Integer.toString(kb));
    }

    public void setFastRamKb(int kb) {
        props.setProperty(K_FASTRAM_KB, Integer.toString(kb));
    }

    public void setDriveEnabled(int index, boolean enabled) {
        props.setProperty(String.format(K_DRIVE_EN, index), Boolean.toString(enabled));
    }

    public void setDriveDisk(int index, String path) {
        props.setProperty(String.format(K_DRIVE_DISK, index), path == null ? "" : path.trim());
    }

    public void setDriveReadOnly(int index, boolean readOnly) {
        props.setProperty(String.format(K_DRIVE_RO, index), Boolean.toString(readOnly));
    }

    public void setHdEnabled(int unit, boolean enabled) {
        props.setProperty(String.format(K_HD_EN, hdUnitName(unit)), Boolean.toString(enabled));
    }

    public void setHdDisk(int unit, String path) {
        props.setProperty(String.format(K_HD_DISK, hdUnitName(unit)), path == null ? "" : path.trim());
    }

    public void setHdReadOnly(int unit, boolean readOnly) {
        props.setProperty(String.format(K_HD_RO, hdUnitName(unit)), Boolean.toString(readOnly));
    }

    public void setLastHdfDirectory(String path) {
        props.setProperty(K_LAST_HDF_DIR, path == null ? "" : path.trim());
    }

    public void setWhdloadEnabled(boolean enabled) {
        props.setProperty(K_WHDLOAD_EN, Boolean.toString(enabled));
    }

    public void setWhdloadFile(String path) {
        props.setProperty(K_WHDLOAD_FILE, path == null ? "" : path.trim());
    }

    public void setLastWhdloadDirectory(String path) {
        props.setProperty(K_LAST_WHDLOAD_DIR, path == null ? "" : path.trim());
    }

    public void setLastFloppyDirectory(String path) {
        props.setProperty(K_LAST_FLOPPY_DIR, path == null ? "" : path.trim());
    }

    public void setLastMediaDirectory(String path) {
        props.setProperty(K_LAST_MEDIA_DIR, path == null ? "" : path.trim());
    }

    public void setLastRomDirectory(String path) {
        props.setProperty(K_LAST_ROM_DIR, path == null ? "" : path.trim());
    }

    public void setFloppySoundVolume(int volume) {
        props.setProperty(K_SND_VOLUME, Integer.toString(Math.max(0, volume)));
    }

    public void setLedsVisible(boolean visible) {
        props.setProperty(K_LEDS, Boolean.toString(visible));
    }

    public void setPortDevice(int port, PortDevice device) {
        props.setProperty(String.format(K_PORT_DEVICE, port),
                (device == null ? PortDevice.NONE : device).configValue());
    }

    /** Scrive i tasti del joystick da tastiera della porta {@code port} (0 o 1). */
    public void setJoystickKeyMap(int port, JoystickKeyMap map) {
        if (map == null) return;
        for (JoystickAction action : JoystickAction.values()) {
            KeyBinding binding = map.get(action);
            if (binding == null) continue;
            props.setProperty(String.format(K_JOY_KEY, port, action.configKey()),
                    binding.configValue());
        }
    }

    public void setLanguage(String lang) {
        props.setProperty(K_LANGUAGE, "it".equalsIgnoreCase(lang) ? "it" : "en");
    }

    public void setVideoMode(VideoMode mode) {
        props.setProperty(K_VIDEO_MODE, (mode == null ? VideoMode.PAL : mode).name());
    }

    public void setChipset(Chipset chipset) {
        props.setProperty(K_CHIPSET, (chipset == null ? Chipset.OCS : chipset).name());
    }

    public void setIntegerScaling(int scale) {
        props.setProperty(K_INTEGER_SCALING, Integer.toString(Math.max(1, scale)));
    }

    /**
     * Scrive il fattore di scala dell'interfaccia. Il valore va sul file con il punto
     * decimale e senza zeri inutili (1, 1.25, 1.5, 2): {@code Double.toString} usa il
     * punto a prescindere dalla lingua del sistema, mentre un {@code String.format} con
     * la locale di default scriverebbe "1,25" su una macchina italiana — e alla
     * rilettura {@code Double.parseDouble} lo rifiuterebbe.
     */
    public void setUiScale(double scale) {
        props.setProperty(K_UI_SCALE, formatScale(
                Math.min(UI_SCALE_MAX, Math.max(UI_SCALE_MIN, scale))));
    }

    /** Il fattore corrente nella forma in cui va sul file ({@code 1}, {@code 1.25}, ...). */
    public String uiScaleValue() {
        return formatScale(uiScale());
    }

    private static String formatScale(double v) {
        return v == Math.rint(v) ? Integer.toString((int) v) : Double.toString(v);
    }

    // =========================================================================
    // Gioco a distanza (docs/remote-play-plan.md §10)
    // =========================================================================

    /** Porta TCP+UDP su cui l'host ascolta. */
    public int remotePlayPort() {
        int p = getInt(K_RP_PORT, 7788);
        return p < 0 || p > 65535 ? 7788 : p;
    }

    public void setRemotePlayPort(int port) {
        props.setProperty(K_RP_PORT, Integer.toString(port));
    }

    /** Se provare ad aprire la porta sul router con UPnP (Fase 6). */
    public boolean remotePlayUpnp() {
        return getBool(K_RP_UPNP, true);
    }

    public void setRemotePlayUpnp(boolean enabled) {
        props.setProperty(K_RP_UPNP, Boolean.toString(enabled));
    }

    /** Interfaccia su cui ascoltare; vuoto = tutte. */
    public String remotePlayBind() {
        return props.getProperty(K_RP_BIND, "").trim();
    }

    public void setRemotePlayBind(String bind) {
        props.setProperty(K_RP_BIND, bind == null ? "" : bind.trim());
    }

    /**
     * Se rifiutare le connessioni che non vengono dalla rete locale.
     *
     * <p>Acceso di default (§8.5): una porta in ascolto su Internet la si apre <b>dopo</b>
     * averlo deciso, non per il fatto di aver provato il gioco a distanza in casa.
     */
    public boolean remotePlayLanOnly() {
        return getBool(K_RP_LAN_ONLY, true);
    }

    public void setRemotePlayLanOnly(boolean lanOnly) {
        props.setProperty(K_RP_LAN_ONLY, Boolean.toString(lanOnly));
    }

    /** Game port pilotata dall'ospite: 0 o 1. */
    public int remotePlayGuestPort() {
        int p = getInt(K_RP_GUEST_PORT, 1);
        return p == 0 ? 0 : 1;
    }

    public void setRemotePlayGuestPort(int port) {
        props.setProperty(K_RP_GUEST_PORT, Integer.toString(port == 0 ? 0 : 1));
    }

    public boolean remotePlayGuestKeyboard() {
        return getBool(K_RP_GUEST_KEYBOARD, true);
    }

    public void setRemotePlayGuestKeyboard(boolean shared) {
        props.setProperty(K_RP_GUEST_KEYBOARD, Boolean.toString(shared));
    }

    /** Se l'ospite puo' resettare la macchina con CTRL+Amiga+Amiga. Spento di default. */
    public boolean remotePlayGuestAllowReset() {
        return getBool(K_RP_GUEST_ALLOWRESET, false);
    }

    public void setRemotePlayGuestAllowReset(boolean allow) {
        props.setProperty(K_RP_GUEST_ALLOWRESET, Boolean.toString(allow));
    }

    /** Formato audio verso l'ospite: {@code adpcm}, {@code pcm16} o {@code off}. */
    public String remotePlayAudioMode() {
        String v = props.getProperty(K_RP_AUDIO_MODE, "adpcm").trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "pcm16", "pcm", "off", "adpcm" -> v;
            default -> "adpcm";
        };
    }

    public void setRemotePlayAudioMode(String mode) {
        props.setProperty(K_RP_AUDIO_MODE, mode == null ? "adpcm" : mode.trim());
    }

    /** Ultimo host a cui ci si e' uniti: il popup lo ripropone. */
    public String remotePlayLastHost() {
        return props.getProperty(K_RP_LAST_HOST, "").trim();
    }

    public void setRemotePlayLastHost(String host) {
        props.setProperty(K_RP_LAST_HOST, host == null ? "" : host.trim());
    }

    public void setOverscanEnabled(boolean enabled) {
        props.setProperty(K_OVERSCAN, Boolean.toString(enabled));
    }

    public void setWindowMode(AmigaWindowMode mode) {
        props.setProperty(K_WINDOW_MODE,
                (mode == null ? AmigaWindowMode.WINDOWED : mode).configValue());
    }

    public void setMonitorIndex(int index) {
        props.setProperty(K_MONITOR, Integer.toString(Math.max(0, index)));
    }

    public void setRenderer(RendererType renderer) {
        props.setProperty(K_RENDERER,
                (renderer == null ? RendererType.SOFTWARE : renderer).configValue());
    }

    public void setShader(String shader) {
        props.setProperty(K_SHADER,
                (shader == null || shader.trim().isEmpty()) ? "none" : shader.trim());
    }

    // =========================================================================
    // Salvataggio
    // =========================================================================

    /**
     * Scrive la configurazione corrente in {@code amiga.properties} nella home
     * ({@link AppHome}), così da avere precedenza sull'eventuale copia nel classpath al
     * successivo avvio. Il file viene riscritto in forma ordinata e commentata.
     *
     * <p><b>UTF-8, dichiarato.</b> La charset va detta qui e in {@link #load()}, e deve
     * essere la stessa: {@code Files.writeString} scrive UTF-8 per default, mentre
     * {@code Properties.load(InputStream)} decodifica <i>ISO-8859-1</i> per contratto. Le
     * due meta' non erano d'accordo, e ogni percorso con un accento — cioe' il caso
     * normale su un'installazione italiana — si corrompeva alla rilettura, peggiorando a
     * ogni giro perche' i byte sbagliati venivano riscritti come tali.
     *
     * <p><b>Prima su un temporaneo, poi uno spostamento atomico.</b> Il file si riscrive
     * per intero a ogni conferma del popup: scrivendo direttamente sulla destinazione,
     * un'interruzione a meta' (crash, disco pieno, la JVM che esce mentre lo shutdown hook
     * gira) lascerebbe una configurazione troncata al posto di quella buona. Con lo
     * spostamento atomico o si vede il file nuovo o si vede ancora il vecchio.
     */
    public void save() throws IOException {
        Path target = AppHome.resolve(FILE_NAME);
        writeAtomically(target, toPropertiesText());
        System.out.println("[CONFIG] Saved " + target.toAbsolutePath());
    }

    /**
     * Scrive {@code text} in {@code target} passando da un temporaneo nella stessa
     * cartella — dev'essere la stessa, o lo spostamento attraverserebbe un file system e
     * smetterebbe di essere atomico.
     *
     * <p>Se il sistema non offre {@code ATOMIC_MOVE} si ripiega su uno spostamento
     * normale: resta molto meglio di una scrittura sul posto, perche' la finestra in cui
     * la destinazione e' incompleta si riduce a quella della {@code move}.
     */
    private static void writeAtomically(Path target, String text) throws IOException {
        // Il temporaneo DEVE stare nella stessa cartella della destinazione: altrove
        // finirebbe su un altro file system e lo spostamento smetterebbe di essere
        // atomico (o fallirebbe). Con AppHome il genitore c'e' sempre; il ripiego sulla
        // cartella corrente e' per non dipendere da quel dettaglio.
        Path dir = target.getParent() != null ? target.getParent() : Path.of(".");
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, FILE_NAME, ".tmp");
        try {
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    /** Configurazione coi soli valori di default, senza toccare il disco. */
    public static AmigaConfig defaults() {
        return new AmigaConfig(defaultProperties());
    }

    /** Configurazione costruita su proprietà già lette: il rovescio di {@link #toPropertiesText}. */
    public static AmigaConfig fromProperties(Properties props) {
        return new AmigaConfig(props);
    }

    /**
     * Il file di configurazione come testo, ordinato e commentato.
     *
     * <p>Separato dalla scrittura di proposito: così la serializzazione è una funzione pura
     * — quel che conta, cioè che ogni chiave ci sia e col valore giusto, si verifica senza
     * un disco di mezzo e senza dipendere da dove {@code AppHome} abbia deciso di puntare.
     */
    public String toPropertiesText() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Amija configuration - Java Amiga emulator\n");
        sb.append("# Automatically generated file; editable by hand or from the popup (F12).\n\n");

        sb.append("# Automatic configuration. Beware the polarity: true (the default) is the\n");
        sb.append("# \"Expert mode\" box UNTICKED in the F12 popup, false is ticked. Left to\n");
        sb.append("# itself, AmiJa works the machine out from the files you load and the\n");
        sb.append("# System section disappears from the popup:\n");
        sb.append("#  - Kickstart: the 1.3 below for plain floppy games, the 3.1 as soon as a\n");
        sb.append("#    hard disk or WHDLoad is in use;\n");
        sb.append("#  - memory and chipset: the auto.floppy.* profile, or auto.hd.* when a hard\n");
        sb.append("#    disk or WHDLoad is in use. Both are editable right below;\n");
        sb.append("#  - video mode: PAL, unless the name of an active file (floppy, hard disk or\n");
        sb.append("#    WHDLoad archive) contains \"NTSC\" or \"(USA)\", in any case.\n");
        sb.append("# The hand-picked settings further down are left untouched and come back into\n");
        sb.append("# force the moment this is set to false.\n");
        line(sb, K_AUTO_EN, Boolean.toString(autoEnabled()));

        sb.append("\n# ABSOLUTE path of the two Kickstart ROMs used by the automatic configuration.\n");
        line(sb, K_AUTO_KS13, autoKickstart13());
        line(sb, K_AUTO_KS31, autoKickstart31());

        sb.append("\n# The two hardware profiles of the automatic configuration: auto.floppy.* is\n");
        sb.append("# used when only floppies are in play, auto.hd.* when a hard disk or WHDLoad is.\n");
        sb.append("# Same units and values as the hand-picked settings below.\n");
        for (AutoDefaults d : AUTO_PROFILES) {
            line(sb, d.prefix() + "chipram.kb", Integer.toString(d.chipRamKb(this)));
            line(sb, d.prefix() + "slowram.kb", Integer.toString(d.slowRamKb(this)));
            line(sb, d.prefix() + "fastram.kb", Integer.toString(d.fastRamKb(this)));
            line(sb, d.prefix() + "chipset", d.chipset(this).name());
        }

        sb.append("\n# ABSOLUTE path of the Kickstart ROM file (automatic configuration off).\n");
        line(sb, K_KICKSTART, storedKickstartPath());

        sb.append("\n# Memory sizes in KB (automatic configuration off).\n");
        line(sb, K_CHIPRAM_KB, Integer.toString(storedChipRamKb()));
        line(sb, K_SLOWRAM_KB, Integer.toString(storedSlowRamKb()));
        sb.append("# Fast RAM (Zorro II AutoConfig expansion): 0=none, then 512/1024/2048/4096/8192.\n");
        line(sb, K_FASTRAM_KB, Integer.toString(storedFastRamKb()));

        sb.append("\n# Connected drives (DF0..DF3).\n");
        for (int i = 0; i < 4; i++) {
            line(sb, String.format(K_DRIVE_EN, i), Boolean.toString(driveEnabled(i)));
        }

        sb.append("\n# ABSOLUTE path of the .adf/.ipf/.zip inserted automatically at startup (empty = no disk).\n");
        sb.append("# A zip holding several disks is a set: the first goes into DF0, the rest into the\n");
        sb.append("# free connected drives, addressed as <archive>.zip!<n> (n = index inside the zip).\n");
        for (int i = 0; i < 4; i++) {
            String d = driveDisk(i);
            line(sb, String.format(K_DRIVE_DISK, i), d == null ? "" : d);
        }

        sb.append("\n# Read-only mount for the disk inserted in each drive (true = write-protected).\n");
        for (int i = 0; i < 4; i++) {
            line(sb, String.format(K_DRIVE_RO, i), Boolean.toString(driveReadOnly(i)));
        }

        sb.append("\n# Hard disks: .hdf files mounted on master/slave (empty = no disk).\n");
        sb.append("# Games installed on an .hdf are booted through a Zorro II AutoConfig\n");
        sb.append("# controller with a boot ROM (filesystem in ROM: OFS/FFS).\n");
        for (String unit : HD_UNITS) {
            line(sb, String.format(K_HD_EN, unit), Boolean.toString(getBool(String.format(K_HD_EN, unit), false)));
        }
        for (String unit : HD_UNITS) {
            String d = props.getProperty(String.format(K_HD_DISK, unit), "").trim();
            line(sb, String.format(K_HD_DISK, unit), d);
        }
        for (String unit : HD_UNITS) {
            line(sb, String.format(K_HD_RO, unit), Boolean.toString(getBool(String.format(K_HD_RO, unit), false)));
        }

        sb.append("\n# WHDLoad: .lha/.lzh archive started automatically. It is extracted and packed\n");
        sb.append("# \"on the fly\" into a bootable FFS hard disk, like the WHDLoad support in\n");
        sb.append("# RetroArch/PUAE. The WHDLoad system files are NOT shipped with AmiJa: put\n");
        sb.append("# your own copy in whdload/WHDLoad_files next to this file (C/, Devs/, Libs/,\n");
        sb.append("# S/). Without them this feature stays disabled.\n");
        line(sb, K_WHDLOAD_EN, Boolean.toString(whdloadEnabled()));
        String whd = props.getProperty(K_WHDLOAD_FILE, "").trim();
        line(sb, K_WHDLOAD_FILE, whd);

        sb.append("\n# Last directories used by the file browsers of the F12 popup. The \"media\"\n");
        sb.append("# one belongs to the single browser of the automatic configuration, which\n");
        sb.append("# takes every kind of file; empty = start from the floppy one.\n");
        line(sb, K_LAST_MEDIA_DIR, props.getProperty(K_LAST_MEDIA_DIR, "").trim());
        line(sb, K_LAST_FLOPPY_DIR, lastFloppyDirectory());
        line(sb, K_LAST_ROM_DIR, lastRomDirectory());
        line(sb, K_LAST_HDF_DIR, lastHdfDirectory());
        line(sb, K_LAST_WHDLOAD_DIR, lastWhdloadDirectory());

        sb.append("\n# Floppy sound volume: 0 = no sound, 1 = minimum, then upwards. The drive\n");
        sb.append("# samples live in the floppysounds folder next to this file. Replace them\n");
        sb.append("# with your own if you prefer, keeping the names; if the folder is empty\n");
        sb.append("# the emulator simply stays silent.\n");
        line(sb, K_SND_VOLUME, Integer.toString(floppySoundVolume()));

        sb.append("\n# Power/drive LED panel (true = visible, false = hidden).\n");
        line(sb, K_LEDS, Boolean.toString(ledsVisible()));

        sb.append("\n# Device connected to each game port. Values: mouse, joystick-real\n");
        sb.append("# (physical joystick/gamepad via GLFW), joystick-keyboard (joystick emulated\n");
        sb.append("# on the host keyboard), none (empty). Port 0 = mouse port (JOY0DAT),\n");
        sb.append("# Port 1 = joystick port (JOY1DAT).\n");
        sb.append("# Can be changed on the fly from the F12 popup (System section).\n");
        line(sb, String.format(K_PORT_DEVICE, 0), portDevice(0).configValue());
        line(sb, String.format(K_PORT_DEVICE, 1), portDevice(1).configValue());

        sb.append("\n# Keys driving the keyboard joystick of each port (device joystick-keyboard).\n");
        sb.append("# Each port has its own set, so both can be played on the same keyboard.\n");
        sb.append("# Defaults: Port 0 = W/A/S/D + right Ctrl, Port 1 = arrow keys + left Ctrl.\n");
        sb.append("# A key is the name of the java.awt.event.KeyEvent VK_ constant without the\n");
        sb.append("# prefix (W, UP, SPACE, NUMPAD5, ...); for keys that exist twice on the\n");
        sb.append("# keyboard add the position: CONTROL:LEFT, CONTROL:RIGHT, SHIFT:LEFT, ...\n");
        sb.append("# ESCAPE cannot be assigned (it cancels the capture in the F12 popup).\n");
        sb.append("# Editable from the F12 popup: pick \"Joystick (keyboard)\" for a port, then \"...\".\n");
        for (int port = 0; port < 2; port++) {
            JoystickKeyMap map = joystickKeyMap(port);
            for (JoystickAction action : JoystickAction.values()) {
                line(sb, String.format(K_JOY_KEY, port, action.configKey()),
                        map.get(action).configValue());
            }
        }

        sb.append("\n# User interface language: en = English, it = Italian.\n");
        line(sb, K_LANGUAGE, language());

        sb.append("\n# Scale of the user interface (F12 popup, dialogs, file chooser):\n");
        sb.append("# 1 = native size, 1.5 = half as big again, 2 = double. Unlike\n");
        sb.append("# video.integerScaling this one may be fractional, and it does NOT\n");
        sb.append("# touch the emulated window. Use a dot for the decimal separator.\n");
        sb.append("# There is no \"automatic\" mode: the desktop scale factor is never read.\n");
        sb.append("# The change takes effect only after restarting the emulator.\n");
        line(sb, K_UI_SCALE, uiScaleValue());

        sb.append("\n# Video mode: PAL (default, 50 Hz, 312 lines) or NTSC (60 Hz, 262 lines).\n");
        sb.append("# Automatic configuration off. The change takes effect only after restarting\n");
        sb.append("# the emulator (cold restart).\n");
        line(sb, K_VIDEO_MODE, storedVideoMode().name());

        sb.append("\n# Custom chipset: OCS (default) or ECS. Selects the Agnus/Denise variant\n");
        sb.append("# and enables the ECS-only registers. Chip RAM > 512 KB requires ECS.\n");
        sb.append("# Automatic configuration off. The change takes effect only after restarting\n");
        sb.append("# the emulator (cold restart).\n");
        line(sb, K_CHIPSET, storedChipset().name());

        sb.append("\n# Integer scaling of the emulated window: 1 = native size, 2 = double,\n");
        sb.append("# 3 = triple, ... Whole numbers only, so the Amiga pixels stay sharp.\n");
        sb.append("# It does NOT scale the user interface (popup, dialogs): that is a\n");
        sb.append("# separate setting.\n");
        line(sb, K_INTEGER_SCALING, Integer.toString(integerScaling()));

        sb.append("\n# Enable overscan: if true the whole Amiga video area is shown, if false it is\n");
        sb.append("# cropped to the standard active area. The window follows the video mode:\n");
        sb.append("# PAL 724x576 / 640x512, NTSC 724x480 / 640x400.\n");
        line(sb, K_OVERSCAN, Boolean.toString(overscanEnabled()));

        sb.append("\n# Window mode: windowed, fullscreen, borderless-windowed.\n");
        line(sb, K_WINDOW_MODE, windowMode().configValue());

        sb.append("\n# Monitor the emulator opens on: 0 = primary screen (default),\n");
        sb.append("# 1, 2, ... = other connected monitors. If the index does not exist the\n");
        sb.append("# primary one is used. The change takes effect only after restarting the emulator.\n");
        line(sb, K_MONITOR, Integer.toString(monitorIndex()));

        sb.append("\n# Display rendering backend: software (Java2D, default) or\n");
        sb.append("# opengl (LWJGL, set up for RetroArch shaders). With opengl the\n");
        sb.append("# framebuffer is presented by the GPU inside the existing Swing window.\n");
        sb.append("# The change takes effect only after restarting the emulator (cold restart).\n");
        line(sb, K_RENDERER, renderer().configValue());

        sb.append("\n# RetroArch shader (opengl renderer only): path of the .slangp or .glslp preset\n");
        sb.append("# inside the \"shaders\" folder next to amiga.properties, without extension.\n");
        sb.append("# Subfolders are allowed, separated by \"/\" -- e.g.\n");
        sb.append("#   video.shader=Mega_Bezel/Presets/MBZ__5__POTATO\n");
        sb.append("# \"none\" = no shader (passthrough). Can be applied on the fly from the F12 popup.\n");
        line(sb, K_SHADER, shader());

        sb.append("\n# Diagnostic overlays (not present in the F12 popup). Disabled by default:\n");
        sb.append("# no performance impact when false.\n");
        sb.append("# overlay.fps.enabled: real FPS at the bottom left (emulator speed).\n");
        sb.append("# overlay.framecounter.enabled: emulated frame counter at the bottom centre,\n");
        sb.append("# independent of emulation speed (a reference for events).\n");
        line(sb, K_OVERLAY_FPS, Boolean.toString(fpsOverlayEnabled()));
        line(sb, K_OVERLAY_FRAMECOUNTER, Boolean.toString(frameCounterOverlayEnabled()));

        sb.append("\n# Immediate blit (not present in the F12 popup). If true the blitter completes\n");
        sb.append("# every operation instantly when BLTSIZE is written (zero emulated bus cycles,\n");
        sb.append("# BBUSY never observed high): NOT cycle-exact, but it speeds up games that\n");
        sb.append("# poll the blitter while waiting. Default false.\n");
        line(sb, K_BLITTER_IMMEDIATE, Boolean.toString(blitterImmediate()));

        sb.append("\n# ========================== Remote play (two players) ========================\n");
        sb.append("# Play together over LAN or the Internet: ONE machine emulates (the host) and\n");
        sb.append("# sends video and audio; the other one (the guest) emulates nothing and sends\n");
        sb.append("# back only its controls. The guest needs no Kickstart and no disk.\n");
        sb.append("# Everything below is also editable from the F12 popup.\n");
        sb.append("# TCP and UDP port the host listens on (the same number for both).\n");
        line(sb, K_RP_PORT, Integer.toString(remotePlayPort()));
        sb.append("# Ask the router to open that port automatically (UPnP). Nothing is asked\n");
        sb.append("# while the setting below keeps the session on the local network: opening\n");
        sb.append("# a port for connections that would then be refused makes no sense.\n");
        line(sb, K_RP_UPNP, Boolean.toString(remotePlayUpnp()));
        sb.append("# Network interface to listen on; empty = all of them.\n");
        line(sb, K_RP_BIND, remotePlayBind());
        sb.append("# Accept guests from the local network only. Turn this off knowingly: it is\n");
        sb.append("# what keeps a listening port off the open Internet until you want it there.\n");
        line(sb, K_RP_LAN_ONLY, Boolean.toString(remotePlayLanOnly()));
        sb.append("# Game port driven by the guest: 0 or 1 (1 = the joystick one).\n");
        line(sb, K_RP_GUEST_PORT, Integer.toString(remotePlayGuestPort()));
        sb.append("# Share the keyboard with the guest.\n");
        line(sb, K_RP_GUEST_KEYBOARD, Boolean.toString(remotePlayGuestKeyboard()));
        sb.append("# Let the guest reset the machine with CTRL+Amiga+Amiga. It is YOUR machine:\n");
        sb.append("# off by default.\n");
        line(sb, K_RP_GUEST_ALLOWRESET, Boolean.toString(remotePlayGuestAllowReset()));
        sb.append("# Audio sent to the guest: adpcm (default, a quarter of the bandwidth and\n");
        sb.append("# indistinguishable by ear), pcm16 (bit-exact), off.\n");
        line(sb, K_RP_AUDIO_MODE, remotePlayAudioMode());
        sb.append("# Last host joined, so the popup can offer it again.\n");
        line(sb, K_RP_LAST_HOST, remotePlayLastHost());

        sb.append("\n# ===================== WHDLoad parameters (WHDLoad.prefs) ====================\n");
        sb.append("# These values generate the S:WHDLoad.prefs file of the WHDLoad hard disk built\n");
        sb.append("# on the fly (they are not shown in the F12 popup). Flags (true/false) enable\n");
        sb.append("# the matching WHDLoad keyword; empty values are omitted.\n");
        sb.append("# Command line arguments and the slave ToolTypes take precedence.\n");
        for (WhdPref pref : WHD_PREFS) {
            if (pref.comment() != null) sb.append("# ").append(pref.comment()).append('\n');
            line(sb, pref.key(), props.getProperty(pref.key(), pref.def()).trim());
        }
        return sb.toString();
    }

    /** Emette una riga {@code chiave=valore} con escaping del backslash (path Windows). */
    private static void line(StringBuilder sb, String key, String value) {
        sb.append(key).append('=').append(value.replace("\\", "\\\\")).append('\n');
    }

    // =========================================================================
    // Fotografia della configurazione (save state)
    // =========================================================================

    /**
     * Le chiavi che descrivono <b>la macchina</b>, cioè quelle da cui dipende com'è
     * fatto l'Amiga acceso: modalità automatica e suoi profili, Kickstart, memorie,
     * chipset, modalità video e supporti montati. Tutto il resto di
     * {@code amiga.properties} (lingua, finestra, monitor, renderer, shader, volumi,
     * ultime cartelle) descrive <i>l'emulatore</i>, non la macchina.
     *
     * <p>È la distinzione che serve al save state: uno slot si ricarica ricostruendo la
     * macchina com'era, non riportando indietro le preferenze d'interfaccia dell'utente —
     * ritrovarsi la finestra a schermo intero o l'emulatore in un'altra lingua per aver
     * caricato una partita sarebbe un effetto collaterale, non un ripristino.
     */
    private static java.util.List<String> machineKeys() {
        java.util.List<String> keys = new java.util.ArrayList<>();
        keys.add(K_AUTO_EN);
        keys.add(K_AUTO_KS13);
        keys.add(K_AUTO_KS31);
        for (AutoDefaults d : AUTO_PROFILES) {
            keys.add(d.prefix() + "chipram.kb");
            keys.add(d.prefix() + "slowram.kb");
            keys.add(d.prefix() + "fastram.kb");
            keys.add(d.prefix() + "chipset");
        }
        keys.add(K_KICKSTART);
        keys.add(K_CHIPRAM_KB);
        keys.add(K_SLOWRAM_KB);
        keys.add(K_FASTRAM_KB);
        keys.add(K_CHIPSET);
        keys.add(K_VIDEO_MODE);
        for (int i = 0; i < 4; i++) {
            keys.add(String.format(K_DRIVE_EN, i));
            keys.add(String.format(K_DRIVE_DISK, i));
            keys.add(String.format(K_DRIVE_RO, i));
        }
        for (String unit : HD_UNITS) {
            keys.add(String.format(K_HD_EN, unit));
            keys.add(String.format(K_HD_DISK, unit));
            keys.add(String.format(K_HD_RO, unit));
        }
        keys.add(K_WHDLOAD_EN);
        keys.add(K_WHDLOAD_FILE);
        return keys;
    }

    /**
     * Copia indipendente delle proprietà correnti, cioè {@code amiga.properties} così
     * com'è in memoria. Il save state ne conserva una dentro lo slot: è la sola
     * descrizione della macchina che quello stato pretende.
     */
    public Properties snapshot() {
        Properties copy = new Properties();
        copy.putAll(props);
        return copy;
    }

    /**
     * {@code true} se {@code saved} descrive una macchina diversa da quella configurata
     * adesso, cioè se {@link #restoreMachineSettings} avrebbe qualcosa da fare.
     */
    public boolean machineDiffers(Properties saved) {
        if (saved == null) return false;
        for (String key : machineKeys()) {
            if (!java.util.Objects.equals(saved.getProperty(key), props.getProperty(key))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Riporta le sole chiavi di macchina (vedi {@link #machineKeys()}) ai valori di
     * {@code saved}, lasciando intatte le preferenze d'interfaccia. Una chiave assente
     * da {@code saved} viene <b>rimossa</b>, non lasciata com'è: significa che quella
     * macchina girava col valore di default, e conservare quello attuale la ricostruirebbe
     * diversa.
     */
    public void restoreMachineSettings(Properties saved) {
        if (saved == null) return;
        for (String key : machineKeys()) {
            String want = saved.getProperty(key);
            if (want == null) {
                props.remove(key);
            } else {
                props.setProperty(key, want);
            }
        }
    }

    // =========================================================================
    // Helper di parsing
    // =========================================================================

    private int getInt(String key, int def) {
        String v = props.getProperty(key);
        if (v == null) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            System.err.println("[CONFIG] Non-numeric value for " + key + "='" + v + "', using " + def);
            return def;
        }
    }

    private boolean getBool(String key, boolean def) {
        String v = props.getProperty(key);
        if (v == null) return def;
        return Boolean.parseBoolean(v.trim());
    }

    /**
     * Come {@link #getInt}, ma per i fattori frazionari. Il separatore decimale e' il
     * PUNTO, sempre: questo e' un file di configurazione, non un testo localizzato, e
     * deve restare leggibile allo stesso modo su ogni macchina.
     */
    private double getDouble(String key, double def) {
        String v = props.getProperty(key);
        if (v == null) return def;
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            System.err.println("[CONFIG] Non-numeric value for " + key + "='" + v + "', using " + def);
            return def;
        }
    }
}
