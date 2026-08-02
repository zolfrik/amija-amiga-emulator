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
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
            try (InputStream in = Files.newInputStream(homeFile)) {
                Properties p = new Properties();
                p.load(in);
                System.out.println("[CONFIG] Loaded " + homeFile.toAbsolutePath());
                return new AmigaConfig(p);
            } catch (IOException e) {
                System.err.println("[CONFIG] Failed to read " + homeFile + ": " + e.getMessage());
            }
        }

        // 2. File sul classpath (es. src/main/resources/amiga.properties).
        try (InputStream in = AmigaConfig.class.getResourceAsStream("/" + FILE_NAME)) {
            if (in != null) {
                Properties p = new Properties();
                p.load(in);
                System.out.println("[CONFIG] Loaded from classpath /" + FILE_NAME);
                return new AmigaConfig(p);
            }
        } catch (IOException e) {
            System.err.println("[CONFIG] Failed to read from classpath: " + e.getMessage());
        }

        // 3. Nessun file: crea quello di default nella home.
        Properties defaults = defaultProperties();
        try (OutputStream out = Files.newOutputStream(homeFile)) {
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
        p.setProperty(K_INTEGER_SCALING, "1");            // nessun ridimensionamento
        p.setProperty(K_OVERSCAN, "true");               // overscan abilitato di default
        p.setProperty(K_WINDOW_MODE, AmigaWindowMode.WINDOWED.configValue());
        p.setProperty(K_MONITOR, "0");                   // 0 = monitor principale
        p.setProperty(K_RENDERER, RendererType.SOFTWARE.configValue());
        p.setProperty(K_SHADER, "none");
        p.setProperty(K_OVERLAY_FPS, "false");           // overlay FPS disattivo
        p.setProperty(K_OVERLAY_FRAMECOUNTER, "false");  // contatore fotogrammi disattivo
        p.setProperty(K_BLITTER_IMMEDIATE, "false");     // immediate blit disattivo (cycle-exact)
        // Parametri WHDLoad (sezione whdload.prefs.*): default come da WHDLoad.prefs storico.
        for (WhdPref pref : WHD_PREFS) {
            p.setProperty(pref.key(), pref.def());
        }
        return p;
    }

    // =========================================================================
    // Getter tipizzati (con fallback ai default se la chiave manca/è invalida)
    // =========================================================================

    /** Percorso assoluto del Kickstart, o stringa vuota se non configurato. */
    public String kickstartPath() {
        return props.getProperty(K_KICKSTART, "").trim();
    }

    /** Chip RAM in KB (default 512). */
    public int chipRamKb() {
        return getInt(K_CHIPRAM_KB, 512);
    }

    /** Slow RAM in KB (default 512); 0 = nessuna Slow RAM. */
    public int slowRamKb() {
        return getInt(K_SLOWRAM_KB, 512);
    }

    /**
     * Fast RAM in KB (default 0 = nessuna Fast RAM). Espansione Zorro II con
     * AutoConfig: valori validi 0/512/1024/2048/4096/8192 (potenze di due da
     * 512 KB a 8 MB). Serve per i giochi via hard disk e WHDLoad.
     */
    public int fastRamKb() {
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

    /**
     * Modalita' video della macchina emulata: {@link VideoMode#PAL} (default) o
     * {@link VideoMode#NTSC}. Determina frequenza del Color Clock, numero di righe
     * per frame (312 PAL / 262 NTSC) e l'ID Agnus letto dal Kickstart. Il cambio ha
     * effetto solo a macchina riavviata (cold restart).
     */
    public VideoMode videoMode() {
        String v = props.getProperty(K_VIDEO_MODE, VideoMode.PAL.name()).trim();
        try {
            return VideoMode.valueOf(v.toUpperCase());
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
        String v = props.getProperty(K_CHIPSET, Chipset.OCS.name()).trim();
        try {
            return Chipset.valueOf(v.toUpperCase());
        } catch (IllegalArgumentException e) {
            System.err.println("[CONFIG] Invalid chipset '" + v + "', using OCS");
            return Chipset.OCS;
        }
    }

    /** Fattore di integer scaling video/UI; 1 = dimensione nativa. */
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
     * Nome dello shader RetroArch selezionato (solo renderer OpenGL): nome del file
     * {@code .glslp} nella cartella {@code shaders} (senza estensione), oppure
     * {@code "none"} (default) per nessuno shader (passthrough). Applicabile a caldo dal
     * popup F12.
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
     */
    public void save() throws IOException {
        Path target = AppHome.resolve(FILE_NAME);
        StringBuilder sb = new StringBuilder();
        sb.append("# Amija configuration - Java Amiga emulator\n");
        sb.append("# Automatically generated file; editable by hand or from the popup (F12).\n\n");

        sb.append("# ABSOLUTE path of the Kickstart ROM file.\n");
        line(sb, K_KICKSTART, kickstartPath());

        sb.append("\n# Memory sizes in KB.\n");
        line(sb, K_CHIPRAM_KB, Integer.toString(chipRamKb()));
        line(sb, K_SLOWRAM_KB, Integer.toString(slowRamKb()));
        sb.append("# Fast RAM (Zorro II AutoConfig expansion): 0=none, then 512/1024/2048/4096/8192.\n");
        line(sb, K_FASTRAM_KB, Integer.toString(fastRamKb()));

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

        sb.append("\n# Last directories used by the file browsers of the F12 popup.\n");
        line(sb, K_LAST_FLOPPY_DIR, lastFloppyDirectory());
        line(sb, K_LAST_ROM_DIR, lastRomDirectory());
        line(sb, K_LAST_HDF_DIR, lastHdfDirectory());
        line(sb, K_LAST_WHDLOAD_DIR, lastWhdloadDirectory());

        sb.append("\n# Floppy sound volume: 0 = no sound, 1 = minimum, then upwards. The drive\n");
        sb.append("# samples are NOT shipped with AmiJa: put the .wav files in a floppysounds\n");
        sb.append("# folder next to this file (drive_click_A500.wav, drive_spin_A500.wav,\n");
        sb.append("# drive_startup_A500.wav, drive_snatch_A500.wav). Without them the emulator\n");
        sb.append("# simply stays silent.\n");
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

        sb.append("\n# Video mode: PAL (default, 50 Hz, 312 lines) or NTSC (60 Hz, 262 lines).\n");
        sb.append("# The change takes effect only after restarting the emulator (cold restart).\n");
        line(sb, K_VIDEO_MODE, videoMode().name());

        sb.append("\n# Custom chipset: OCS (default) or ECS. Selects the Agnus/Denise variant\n");
        sb.append("# and enables the ECS-only registers. Chip RAM > 512 KB requires ECS.\n");
        sb.append("# The change takes effect only after restarting the emulator (cold restart).\n");
        line(sb, K_CHIPSET, chipset().name());

        sb.append("\n# Video/UI integer scaling: 1 = native size, 2 = double, 3 = triple, ...\n");
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

        sb.append("\n# RetroArch shader (opengl renderer only): name of the .glslp file in the\n");
        sb.append("# \"shaders\" folder next to amiga.properties, without extension.\n");
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

        sb.append("\n# ===================== WHDLoad parameters (WHDLoad.prefs) ====================\n");
        sb.append("# These values generate the S:WHDLoad.prefs file of the WHDLoad hard disk built\n");
        sb.append("# on the fly (they are not shown in the F12 popup). Flags (true/false) enable\n");
        sb.append("# the matching WHDLoad keyword; empty values are omitted.\n");
        sb.append("# Command line arguments and the slave ToolTypes take precedence.\n");
        for (WhdPref pref : WHD_PREFS) {
            if (pref.comment() != null) sb.append("# ").append(pref.comment()).append('\n');
            line(sb, pref.key(), props.getProperty(pref.key(), pref.def()).trim());
        }

        Files.writeString(target, sb.toString());
        System.out.println("[CONFIG] Saved " + target.toAbsolutePath());
    }

    /** Emette una riga {@code chiave=valore} con escaping del backslash (path Windows). */
    private static void line(StringBuilder sb, String key, String value) {
        sb.append(key).append('=').append(value.replace("\\", "\\\\")).append('\n');
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
}
