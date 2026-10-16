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

import io.github.zolfrik.amija.common.AppHome;
import io.github.zolfrik.amija.common.Chipset;
import io.github.zolfrik.amija.cpu.CpuModel;
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
 * Emulator configuration loaded from an {@code amiga.properties} file.
 *
 * <p>Where the file is looked for (in order):
 * <ol>
 *   <li>{@code amiga.properties} in the application home ({@link AppHome}, normally the
 *       working directory);</li>
 *   <li>{@code /amiga.properties} on the class path (e.g. {@code src/main/resources});</li>
 *   <li>if it exists in neither place, it is <b>created</b> in the home with the default
 *       values and used.</li>
 * </ol>
 *
 * <p>Properties handled:
 * <ul>
 *   <li>{@code auto.enabled}: automatic mode (default {@code true}): Kickstart, memory,
 *       chipset and video mode are derived from the active media ({@code auto.*}) instead
 *       of chosen by hand;</li>
 *   <li>{@code kickstart.path}: absolute path of the Kickstart ROM file;</li>
 *   <li>{@code chipram.kb} / {@code slowram.kb}: amount of Chip/Slow RAM in KB;</li>
 *   <li>{@code drive.df0..df3.enabled}: whether the drive is connected;</li>
 *   <li>{@code drive.df0..df3.disk}: absolute path of the .adf/.ipf to insert
 *       automatically at start-up;</li>
 *   <li>{@code floppy.sound.volume}: floppy sound volume: 0 = no sound, 1 = minimum
 *       volume, then upward;</li>
 *   <li>{@code leds.visible}: shows or hides the power/drive LED panel.</li>
 * </ul>
 */
public final class AmigaConfig {

    /** Name of the configuration file looked for in the current dir and on the class path. */
    public static final String FILE_NAME = "amiga.properties";

    // Property keys.
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

    /** Hard disk units: index 0 = master, index 1 = slave. */
    public static final String[] HD_UNITS = { "master", "slave" };
    private static final String K_SND_VOLUME  = "floppy.sound.volume";
    private static final String K_LEDS        = "leds.visible";
    /** Host device plugged into a game port: {@code input.port0.device} / {@code input.port1.device}. */
    private static final String K_PORT_DEVICE = "input.port%d.device";
    /** Keyboard joystick key: {@code input.port<N>.joystick.<up|down|right|left|fire>}. */
    private static final String K_JOY_KEY = "input.port%d.joystick.%s";
    private static final String K_LANGUAGE    = "ui.language";
    private static final String K_UI_SCALE    = "ui.scale";
    private static final String K_VIDEO_MODE  = "video.mode";
    private static final String K_CHIPSET     = "chipset";
    private static final String K_CPU_MODEL   = "cpu.model";
    private static final String K_INTEGER_SCALING = "video.integerScaling";
    private static final String K_OVERSCAN = "video.overscan.enabled";
    private static final String K_WINDOW_MODE = "video.windowMode";
    private static final String K_MONITOR = "video.monitor";
    private static final String K_RENDERER = "video.renderer";
    private static final String K_SHADER = "video.shader";
    /** State slot "Save state" and "Load state" act on (1..5). */
    private static final String K_STATE_SLOT = "savestate.slot";
    // Diagnostic overlays (NOT exposed in the F12 popup): file only.
    private static final String K_OVERLAY_FPS = "overlay.fps.enabled";
    private static final String K_OVERLAY_FRAMECOUNTER = "overlay.framecounter.enabled";
    // Immediate blit (NOT exposed in the F12 popup): file only.
    private static final String K_BLITTER_IMMEDIATE = "blitter.immediate";

    // Remote play (docs/remote-play-plan.md section 10). The join code is NOT saved to the
    // file: it is a session secret and is regenerated at every start of the host.
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
    // WHDLoad parameters (section whdload.prefs.* at the end of amiga.properties).
    // The S:WHDLoad.prefs file of the WHDLoad hard disk built on the fly is GENERATED from
    // here. NOT exposed in the F12 popup: file only.
    // =========================================================================

    /** Kind of WHDLoad parameter: presence keyword (flag) or name=value pair. */
    private enum WhdPrefType { FLAG, VALUE }

    /**
     * Descriptor of a WHDLoad parameter: key in {@code amiga.properties}, matching keyword in
     * the {@code WHDLoad.prefs} file, kind, default and comment.
     */
    private record WhdPref(String key, String whdName, WhdPrefType type, String def, String comment) { }

    /**
     * Table of every WHDLoad parameter handled. The order decides both the section in
     * {@code amiga.properties} and the order of the entries in the generated
     * {@code WHDLoad.prefs}. For {@code FLAG}s the value is {@code true}/{@code false} (true =
     * the keyword is present); for {@code VALUE}s it is the string to emit (empty = omitted).
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
    // Automatic mode (auto.*)
    // =========================================================================

    /**
     * Hardware profile of Auto mode: prefix of the keys in {@code amiga.properties} and
     * default values if the user has not overridden them. There are two, chosen from the
     * active media (see {@link #autoProfile}).
     */
    private record AutoDefaults(String prefix, int chipRamKb, int slowRamKb, int fastRamKb,
            Chipset chipset, CpuModel cpuModel) {

        int chipRamKb(AmigaConfig c) { return c.getInt(prefix + "chipram.kb", chipRamKb); }

        int slowRamKb(AmigaConfig c) { return c.getInt(prefix + "slowram.kb", slowRamKb); }

        int fastRamKb(AmigaConfig c) { return c.getInt(prefix + "fastram.kb", fastRamKb); }

        Chipset chipset(AmigaConfig c) {
            return parseChipset(c.props.getProperty(prefix + "chipset"), chipset);
        }

        CpuModel cpuModel(AmigaConfig c) {
            return CpuModel.parse(c.props.getProperty(prefix + "cpu"), cpuModel);
        }
    }

    /** Floppy only: the stock OCS configuration almost every game expects. */
    private static final AutoDefaults AUTO_FLOPPY =
            new AutoDefaults("auto.floppy.", 512, 512, 0, Chipset.OCS, CpuModel.M68000E);

    /**
     * Hard disk or WHDLoad active: the expanded machine that software requires.
     *
     * <p>2 MB of Chip RAM, not 1: the A1200 the WHDLoad installers take for granted. Every
     * slave declares in its header (ws_BaseMemSize) how much contiguous memory it wants
     * from $000000, and that block can only be Chip; when there is not enough, WHDLoad stops
     * with "you need chipmem up to $xxxxxx". Quite a few want more than 1 MB (e.g. Dragon's
     * Lair asks for $180000, 1.5 MB), while those that want less run the same on a larger
     * machine: the maximum is the only choice that never fails.
     */
    private static final AutoDefaults AUTO_EXPANDED =
            new AutoDefaults("auto.hd.", 2048, 512, 8192, Chipset.ECS, CpuModel.M68020);

    /**
     * Software that needs the AGA chipset: an A1200, with its 2 MB of Chip RAM and no Slow
     * RAM. The Fast RAM applies only with a hard disk or WHDLoad, which keeps its own code
     * there while the game takes the whole Chip RAM; from floppy the machine is a stock A1200.
     */
    private static final AutoDefaults AUTO_AGA =
            new AutoDefaults("auto.aga.", 2048, 0, 8192, Chipset.AGA, CpuModel.M68020);

    private static final AutoDefaults[] AUTO_PROFILES = { AUTO_FLOPPY, AUTO_EXPANDED, AUTO_AGA };

    /**
     * System settings derived by Auto mode: what the machine will really use at the next
     * start without the user having to choose anything.
     *
     * @param expanded {@code true} if a hard disk or WHDLoad is active (expanded profile)
     * @param aga      {@code true} if the media needs the AGA chipset (A1200 profile)
     */
    public record AutoProfile(String kickstartPath, boolean expanded, boolean aga, int chipRamKb,
            int slowRamKb, int fastRamKb, Chipset chipset, CpuModel cpuModel, VideoMode videoMode) {

        /** {@code true} if the profile boots Kickstart 3.1 rather than 1.3. */
        public boolean usesKickstart31() {
            return expanded || aga;
        }
    }

    private final Properties props;

    private AmigaConfig(Properties props) {
        this.props = props;
    }

    // =========================================================================
    // Loading
    // =========================================================================

    /**
     * Loads the configuration following the search order described in the class. Never
     * throws: on I/O problems it falls back on the default values in memory.
     */
    public static AmigaConfig load() {
        // 1. File in the application home (normally the working directory).
        Path homeFile = AppHome.resolve(FILE_NAME);
        if (Files.isRegularFile(homeFile)) {
            // UTF-8, and through a Reader: see the comment of save(). With an InputStream
            // Properties decodes ISO-8859-1, which corrupted accented paths at every round
            // of saving and reading back.
            try (Reader in = Files.newBufferedReader(homeFile, StandardCharsets.UTF_8)) {
                Properties p = new Properties();
                p.load(in);
                System.out.println("[CONFIG] Loaded " + homeFile.toAbsolutePath());
                return new AmigaConfig(p);
            } catch (MalformedInputException e) {
                // A file written before the two halves agreed, or by hand with the system
                // editor: not valid UTF-8. It is read again as Latin-1, which accepts any
                // byte, so the configuration is not lost; the first save brings it back to
                // UTF-8.
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

        // 2. File on the class path (e.g. src/main/resources/amiga.properties). UTF-8 like
        //    the other two paths: the charset of this file must be ONE, or the disagreement
        //    that corrupted accented paths comes back.
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

        // 3. No file: create the default one in the home.
        Properties defaults = defaultProperties();
        try (Writer out = Files.newBufferedWriter(homeFile, StandardCharsets.UTF_8)) {
            // store(Writer) and not store(OutputStream): the latter writes ISO-8859-1 and
            // would put the two halves in disagreement again as soon as a default stopped
            // being ASCII.
            defaults.store(out, "AmiJa configuration - Java Amiga emulator");
            System.out.println("[CONFIG] Created default file " + homeFile.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("[CONFIG] Failed to create " + homeFile + ": " + e.getMessage());
        }
        return new AmigaConfig(defaults);
    }

    /** Default properties used at the first run (file missing). */
    private static Properties defaultProperties() {
        Properties p = new Properties();
        // Automatic mode: on by default (it is why it exists).
        p.setProperty(K_AUTO_EN, "true");
        p.setProperty(K_AUTO_KS13, "");                 // to be set by the user
        p.setProperty(K_AUTO_KS31, "");
        for (AutoDefaults d : AUTO_PROFILES) {
            p.setProperty(d.prefix() + "chipram.kb", Integer.toString(d.chipRamKb()));
            p.setProperty(d.prefix() + "slowram.kb", Integer.toString(d.slowRamKb()));
            p.setProperty(d.prefix() + "fastram.kb", Integer.toString(d.fastRamKb()));
            p.setProperty(d.prefix() + "chipset", d.chipset().name());
            p.setProperty(d.prefix() + "cpu", d.cpuModel().label());
        }
        p.setProperty(K_KICKSTART, "");                 // to be set by the user
        p.setProperty(K_CHIPRAM_KB, "512");
        p.setProperty(K_SLOWRAM_KB, "512");
        p.setProperty(K_FASTRAM_KB, "0");               // no Fast RAM by default
        // Only DF0 and DF1 enabled by default.
        p.setProperty(String.format(K_DRIVE_EN, 0), "true");
        p.setProperty(String.format(K_DRIVE_EN, 1), "true");
        p.setProperty(String.format(K_DRIVE_EN, 2), "false");
        p.setProperty(String.format(K_DRIVE_EN, 3), "false");
        // No disk inserted by default.
        for (int i = 0; i < 4; i++) {
            p.setProperty(String.format(K_DRIVE_DISK, i), "");
            p.setProperty(String.format(K_DRIVE_RO, i), "false");
        }
        // Hard disks (master/slave): absent by default.
        for (String unit : HD_UNITS) {
            p.setProperty(String.format(K_HD_EN, unit), "false");
            p.setProperty(String.format(K_HD_DISK, unit), "");
            p.setProperty(String.format(K_HD_RO, unit), "false");
        }
        // WHDLoad: absent by default.
        p.setProperty(K_WHDLOAD_EN, "false");
        p.setProperty(K_WHDLOAD_FILE, "");
        p.setProperty(K_LAST_MEDIA_DIR, "");
        p.setProperty(K_LAST_FLOPPY_DIR, "");
        p.setProperty(K_LAST_ROM_DIR, "");
        p.setProperty(K_LAST_HDF_DIR, "");
        p.setProperty(K_LAST_WHDLOAD_DIR, "");
        p.setProperty(K_SND_VOLUME, "1");               // volume minimo
        p.setProperty(K_LEDS, "true");                   // pannello LED visibile
        // Game port devices: Port 0 = mouse, Port 1 = keyboard joystick.
        p.setProperty(String.format(K_PORT_DEVICE, 0), PortDevice.MOUSE.configValue());
        p.setProperty(String.format(K_PORT_DEVICE, 1), PortDevice.JOYSTICK_KEYBOARD.configValue());
        // Keyboard joystick keys: Port 0 = W/A/S/D + right Ctrl,
        // Port 1 = arrows + left Ctrl.
        for (int port = 0; port < 2; port++) {
            JoystickKeyMap map = JoystickKeyMap.defaults(port);
            for (JoystickAction a : JoystickAction.values()) {
                p.setProperty(String.format(K_JOY_KEY, port, a.configKey()),
                        map.get(a).configValue());
            }
        }
        p.setProperty(K_LANGUAGE, "en");                 // default language: English
        p.setProperty(K_VIDEO_MODE, VideoMode.PAL.name()); // default video mode: PAL
        p.setProperty(K_CHIPSET, Chipset.OCS.name());      // default chipset: OCS
        p.setProperty(K_CPU_MODEL, CpuModel.M68000E.label()); // default processor: event-driven 68000
        // The TWO scales, separate and independent. Both must be kept here: this is the file
        // the user finds at the first run, and a missing key is a feature the user does not
        // know exists.
        p.setProperty(K_INTEGER_SCALING, "1");            // finestra emulata a dimensione nativa
        p.setProperty(K_UI_SCALE, "1");                   // interfaccia a dimensione nativa
        p.setProperty(K_OVERSCAN, "true");               // overscan enabled by default
        p.setProperty(K_WINDOW_MODE, AmigaWindowMode.WINDOWED.configValue());
        p.setProperty(K_MONITOR, "0");                   // 0 = monitor principale
        p.setProperty(K_RENDERER, RendererType.SOFTWARE.configValue());
        p.setProperty(K_SHADER, "none");
        p.setProperty(K_OVERLAY_FPS, "false");           // overlay FPS disattivo
        p.setProperty(K_OVERLAY_FRAMECOUNTER, "false");  // contatore fotogrammi disattivo
        p.setProperty(K_BLITTER_IMMEDIATE, "false");     // immediate blit disattivo (cycle-exact)
        // Remote play: off until the user presses "Host" in the popup. The keys are there
        // anyway, because a missing key is a feature the user does not know exists.
        p.setProperty(K_RP_PORT, "7788");
        p.setProperty(K_RP_UPNP, "true");
        p.setProperty(K_RP_BIND, "");
        p.setProperty(K_RP_LAN_ONLY, "true");
        p.setProperty(K_RP_GUEST_PORT, "1");             // the joystick port to the guest
        p.setProperty(K_RP_GUEST_KEYBOARD, "true");
        p.setProperty(K_RP_GUEST_ALLOWRESET, "false");
        p.setProperty(K_RP_AUDIO_MODE, "adpcm");         // section 15.5, closed while listening
        p.setProperty(K_RP_LAST_HOST, "");
        // WHDLoad parameters (section whdload.prefs.*): defaults as in the stock WHDLoad.prefs.
        for (WhdPref pref : WHD_PREFS) {
            p.setProperty(pref.key(), pref.def());
        }
        return p;
    }

    // =========================================================================
    // Automatic mode: derivation of the system profile
    // =========================================================================

    /**
     * {@code true} (default) if the machine configures itself: Kickstart, memory, chipset
     * and video mode are derived from the active media instead of chosen by hand. In the
     * F12 popup it is the <b>"Expert mode"</b> box, which means the opposite: off (the
     * default) the machine configures itself, on the settings saved by the user apply and
     * everything behaves as before this mode existed.
     */
    public boolean autoEnabled() {
        return getBool(K_AUTO_EN, true);
    }

    /** Kickstart 1.3 of Auto mode (floppy-only profile); empty if not set. */
    public String autoKickstart13() {
        return props.getProperty(K_AUTO_KS13, "").trim();
    }

    /** Kickstart 3.1 of Auto mode (hard disk/WHDLoad profile); empty if not set. */
    public String autoKickstart31() {
        return props.getProperty(K_AUTO_KS31, "").trim();
    }

    /**
     * {@code true} if at least one hard disk or the WHDLoad boot is active, i.e. if Auto mode
     * must use the expanded profile. "Active" means ticked <i>and</i> with a file named: a
     * ticked but empty unit mounts nothing.
     */
    public boolean expandedMediaActive() {
        for (int unit = 0; unit < HD_UNITS.length; unit++) {
            if (hdEnabled(unit) && hdDisk(unit) != null) return true;
        }
        return whdloadEnabled() && whdloadFile() != null;
    }

    /**
     * Every file actually in use: the disks of the connected drives, the mounted hard disks
     * and the WHDLoad archive. The set on which Auto mode looks for region hints (see
     * {@link #impliesNtsc}).
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
     * {@code true} if one of the active files declares itself NTSC in its <b>name</b>: the
     * string {@code NTSC} or {@code (USA)}, case-insensitive. These are the two conventions
     * by which image collections (TOSEC, No-Intro, WHDLoad) mark the 60 Hz versions, which on
     * a PAL machine run too slow or with a cut picture.
     *
     * <p>Only the file name is looked at, not the whole path: a folder called e.g.
     * {@code USA} or {@code NTSC dumps} must not drag everything it contains into NTSC.
     */
    public static boolean impliesNtsc(java.util.List<String> files) {
        for (String f : files) {
            if (f == null) continue;
            String name = fileName(f).toUpperCase(java.util.Locale.ROOT);
            if (name.contains("NTSC") || name.contains("(USA)")) return true;
        }
        return false;
    }

    /** Last segment of a path, whatever separator it uses (or the whole string). */
    private static String fileName(String path) {
        int cut = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return cut < 0 ? path : path.substring(cut + 1);
    }

    /**
     * System settings derived by Auto mode for the media described by the parameters;
     * {@link #autoProfile()} is the same applied to the current state.
     *
     * <p>The profile is one of three: floppy only (stock OCS machine, Kickstart 1.3), hard
     * disk/WHDLoad (expanded machine, Kickstart 3.1), or AGA software (A1200, Kickstart 3.1),
     * which wins over the other two. Every value comes from {@code amiga.properties}
     * ({@code auto.floppy.*} / {@code auto.hd.*} / {@code auto.aga.*}), so anyone can
     * change it by hand without giving up the automation.
     *
     * @param expanded    a hard disk or WHDLoad is active
     * @param activeFiles files in use, for the PAL/NTSC and AGA derivations
     */
    private AutoProfile autoProfile(boolean expanded, java.util.List<String> activeFiles) {
        final boolean aga = agaRequired(activeFiles);
        AutoDefaults d = aga ? AUTO_AGA : expanded ? AUTO_EXPANDED : AUTO_FLOPPY;
        int fastRamKb = aga && !expanded ? 0 : d.fastRamKb(this);
        return new AutoProfile(autoKickstartPath(expanded || aga), expanded, aga,
                d.chipRamKb(this), d.slowRamKb(this), fastRamKb, d.chipset(this),
                d.cpuModel(this),
                autoVideoMode(activeFiles));
    }

    /**
     * {@code true} if the active media need the AGA chipset: the WHDLoad slave says so in its
     * header ({@link io.github.zolfrik.amija.whdload.WhdLoadManager#requiresAga}), or a file
     * says so in its name ({@link #impliesAga}).
     */
    private boolean agaRequired(java.util.List<String> activeFiles) {
        if (whdloadEnabled() && io.github.zolfrik.amija.whdload.WhdLoadManager
                .requiresAga(whdloadFile())) {
            return true;
        }
        return impliesAga(activeFiles);
    }

    /**
     * {@code true} if one of the active files declares itself AGA in its <b>name</b>: the
     * word {@code AGA} standing on its own, as the collections write it ({@code (AGA)},
     * {@code (OCS, AGA)}, {@code _AGA_}, {@code _AGA.lha}). Only the file name is looked at,
     * not the folder: a collection kept in a folder called {@code #AGA} still says it per file.
     */
    public static boolean impliesAga(java.util.List<String> files) {
        for (String f : files) {
            if (f == null) continue;
            String name = fileName(f).toUpperCase(java.util.Locale.ROOT);
            for (int i = name.indexOf("AGA"); i >= 0; i = name.indexOf("AGA", i + 1)) {
                boolean before = i == 0 || !Character.isLetterOrDigit(name.charAt(i - 1));
                boolean after = i + 3 >= name.length() || !Character.isLetterOrDigit(name.charAt(i + 3));
                if (before && after) return true;
            }
        }
        return false;
    }

    /**
     * Video standard derived by Auto mode. A WHDLoad archive <b>declares</b> it in the
     * tooltypes of its icon, and that declaration wins: it says how the game is meant to be
     * seen, while {@link #impliesNtsc} can only read the file name. When the icon is silent
     * (or there is no WHDLoad archive) the name convention remains.
     */
    private VideoMode autoVideoMode(java.util.List<String> activeFiles) {
        if (whdloadEnabled()) {
            VideoMode declared = io.github.zolfrik.amija.whdload.WhdLoadManager
                    .declaredVideoMode(whdloadFile());
            if (declared != null) return declared;
        }
        return impliesNtsc(activeFiles) ? VideoMode.NTSC : VideoMode.PAL;
    }

    /** Settings derived by Auto mode for the media currently configured. */
    public AutoProfile autoProfile() {
        return autoProfile(expandedMediaActive(), activeMediaFiles());
    }

    /**
     * ROM chosen by Auto mode: Kickstart 3.1 for the expanded profile, 1.3 for floppy only.
     * If the profile's box is empty it falls back on the other and finally on the Kickstart
     * set by hand, so anyone coming from an earlier configuration (where only
     * {@code kickstart.path} existed) still starts.
     */
    private String autoKickstartPath(boolean expanded) {
        String preferred = expanded ? autoKickstart31() : autoKickstart13();
        String other = expanded ? autoKickstart13() : autoKickstart31();
        if (!preferred.isEmpty()) return preferred;
        if (!other.isEmpty()) return other;
        return storedKickstartPath();
    }

    // =========================================================================
    // Typed getters (falling back to the defaults if the key is missing/invalid)
    //
    // With Auto mode on, the system parameters are no longer those saved but those derived
    // from the media: the getters below return the EFFECTIVE value, the one the machine is
    // built with. The values chosen by hand stay untouched in the file (stored* getters)
    // and come back into force as soon as Auto is switched off.
    // =========================================================================

    /** Absolute path of the Kickstart, or the empty string if not configured. */
    public String kickstartPath() {
        return autoEnabled() ? autoProfile().kickstartPath() : storedKickstartPath();
    }

    /** Chip RAM in KB (default 512). */
    public int chipRamKb() {
        return autoEnabled() ? autoProfile().chipRamKb() : storedChipRamKb();
    }

    /** Slow RAM in KB (default 512); 0 = no Slow RAM. */
    public int slowRamKb() {
        return autoEnabled() ? autoProfile().slowRamKb() : storedSlowRamKb();
    }

    /**
     * Fast RAM in KB (default 0 = no Fast RAM). Zorro II expansion with AutoConfig: valid
     * values 0/512/1024/2048/4096/8192 (powers of two from 512 KB to 8 MB). Needed by games
     * run from hard disk and WHDLoad.
     */
    public int fastRamKb() {
        return autoEnabled() ? autoProfile().fastRamKb() : storedFastRamKb();
    }

    /** Kickstart chosen by hand (key {@code kickstart.path}), ignored by Auto. */
    public String storedKickstartPath() {
        return props.getProperty(K_KICKSTART, "").trim();
    }

    /** Chip RAM chosen by hand, ignored by Auto. */
    public int storedChipRamKb() {
        return getInt(K_CHIPRAM_KB, 512);
    }

    /** Slow RAM chosen by hand, ignored by Auto. */
    public int storedSlowRamKb() {
        return getInt(K_SLOWRAM_KB, 512);
    }

    /** Fast RAM chosen by hand, ignored by Auto. */
    public int storedFastRamKb() {
        return getInt(K_FASTRAM_KB, 0);
    }

    /** {@code true} if drive {@code df<index>} (0..3) is connected. */
    public boolean driveEnabled(int index) {
        boolean def = (index == 0 || index == 1); // df0/df1 enabled by default
        return getBool(String.format(K_DRIVE_EN, index), def);
    }

    /**
     * Absolute path of the .adf/.ipf to insert in drive {@code df<index>} (0..3), or
     * {@code null} if not configured.
     */
    public String driveDisk(int index) {
        String v = props.getProperty(String.format(K_DRIVE_DISK, index), "").trim();
        return v.isEmpty() ? null : v;
    }

    /** {@code true} if the disk of drive {@code df<index>} must be mounted read-only. */
    public boolean driveReadOnly(int index) {
        return getBool(String.format(K_DRIVE_RO, index), false);
    }

    /** {@code true} if hard disk unit {@code unit} (0=master, 1=slave) is connected. */
    public boolean hdEnabled(int unit) {
        return getBool(String.format(K_HD_EN, hdUnitName(unit)), false);
    }

    /**
     * Absolute path of the {@code .hdf} file to mount on unit {@code unit} (0=master,
     * 1=slave), or {@code null} if not configured.
     */
    public String hdDisk(int unit) {
        String v = props.getProperty(String.format(K_HD_DISK, hdUnitName(unit)), "").trim();
        return v.isEmpty() ? null : v;
    }

    /** {@code true} if the hard disk of unit {@code unit} must be mounted read-only. */
    public boolean hdReadOnly(int unit) {
        return getBool(String.format(K_HD_RO, hdUnitName(unit)), false);
    }

    /** Last folder used by the .hdf browser, or the empty string if not configured. */
    public String lastHdfDirectory() {
        return props.getProperty(K_LAST_HDF_DIR, "").trim();
    }

    /** {@code true} if the automatic WHDLoad boot is enabled. */
    public boolean whdloadEnabled() {
        return getBool(K_WHDLOAD_EN, false);
    }

    /**
     * URL from which the download assistant fetches package {@code id} (key
     * {@code assets.<id>.url}), or {@code fallback} if not configured.
     *
     * <p>It exists for a practical reason: those URLs point to third-party sites and sooner
     * or later they will change. This key lets them be fixed without recompiling. The rule of
     * {@code THIRD-PARTY-NOTICES.md} section 5 still applies: the source must be the
     * official one of the rights holder, never a mirror or an abandonware site.
     */
    public String assetUrl(String id, String fallback) {
        String v = props.getProperty("assets." + id + ".url", "").trim();
        return v.isEmpty() ? fallback : v;
    }

    /**
     * Absolute path of the WHDLoad archive ({@code .lha}/{@code .lzh}) to start
     * automatically, or {@code null} if not configured. It is extracted and packed into a
     * bootable hard disk (see {@code WhdLoadManager}).
     */
    public String whdloadFile() {
        String v = props.getProperty(K_WHDLOAD_FILE, "").trim();
        return v.isEmpty() ? null : v;
    }

    /**
     * Generates the content of the {@code S:WHDLoad.prefs} file from the
     * {@code whdload.prefs.*} section of {@code amiga.properties}. {@code true} flags emit the
     * corresponding WHDLoad keyword; empty values are omitted. Used by
     * {@code WhdLoadManager} while building the WHDLoad hard disk.
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

    /** Last folder used by the WHDLoad browser, or the empty string if not configured. */
    public String lastWhdloadDirectory() {
        return props.getProperty(K_LAST_WHDLOAD_DIR, "").trim();
    }

    private static String hdUnitName(int unit) {
        return HD_UNITS[unit < 0 ? 0 : Math.min(unit, HD_UNITS.length - 1)];
    }

    /** Last folder used by the floppy browser, or the empty string if not configured. */
    public String lastFloppyDirectory() {
        return props.getProperty(K_LAST_FLOPPY_DIR, "").trim();
    }

    /**
     * Last folder used by Auto mode's single browser, which accepts every kind of media. It
     * has its own memory because it mixes floppies, hard disks and WHDLoad archives; while
     * it is empty it falls back on the floppies', so anyone coming from an earlier
     * configuration does not start from nothing.
     */
    public String lastMediaDirectory() {
        String v = props.getProperty(K_LAST_MEDIA_DIR, "").trim();
        return v.isEmpty() ? lastFloppyDirectory() : v;
    }

    /** Last folder used by the ROM browser, or the empty string if not configured. */
    public String lastRomDirectory() {
        return props.getProperty(K_LAST_ROM_DIR, "").trim();
    }

    /** Floppy sound volume: 0 = no sound, 1 = minimum, then upward (default 1). */
    public int floppySoundVolume() {
        return Math.max(0, getInt(K_SND_VOLUME, 1));
    }

    /** {@code true} if the floppy sounds are on (volume &gt; 0). */
    public boolean floppySoundEnabled() {
        return floppySoundVolume() > 0;
    }

    /**
     * Converts the integer volume ({@link #floppySoundVolume()}) into the attenuation in dB
     * used by {@code FloppySound}: volume 1 = about -36 dB (minimum audible), +6 dB per step,
     * capped at 0 dB. Meaningful only if {@link #floppySoundEnabled()}.
     */
    public float floppySoundGainDb() {
        int v = floppySoundVolume();
        return Math.min(0f, -42f + v * 6f);
    }

    /** {@code true} if the power/drive LED panel must be visible (default true). */
    public boolean ledsVisible() {
        return getBool(K_LEDS, true);
    }

    /**
     * Host device plugged into game port {@code port} (0 = "Port 0", the mouse/JOY0DAT port;
     * 1 = "Port 1", the joystick/JOY1DAT port). Default: Port 0 = mouse, Port 1 = keyboard
     * joystick.
     */
    public PortDevice portDevice(int port) {
        PortDevice def = (port == 0) ? PortDevice.MOUSE : PortDevice.JOYSTICK_KEYBOARD;
        return PortDevice.fromConfigValue(
                props.getProperty(String.format(K_PORT_DEVICE, port)), def);
    }

    /**
     * Host keys driving the keyboard joystick of port {@code port} (0 or 1). Missing or
     * unrecognised keys fall back on the port's default ({@link JoystickKeyMap#defaults(int)}):
     * Port 0 = W/A/S/D + right Ctrl, Port 1 = arrows + left Ctrl.
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

    /** Interface language code ({@code "en"} or {@code "it"}; default {@code "en"}). */
    public String language() {
        return props.getProperty(K_LANGUAGE, "en").trim();
    }

    /** Minimum and maximum scale factor accepted for the interface. */
    public static final double UI_SCALE_MIN = 1.0;
    public static final double UI_SCALE_MAX = 4.0;

    /**
     * Scale factor of the <b>interface only</b> (F12 popup, dialogs, choosers); 1 = native
     * size. Unlike {@link #integerScaling()} it can be fractional (an artefact in the
     * interface does not touch the quality of the emulation) and has no effect on the
     * emulated window.
     *
     * <p>There is no "automatic" mode: the desktop's scale factor is neither read nor used,
     * and whoever touches nothing gets 1. See {@code docs/gui-scaling-flatlaf-plan.md}.
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
     * Video mode of the emulated machine: {@link VideoMode#PAL} (default) or
     * {@link VideoMode#NTSC}. Decides the colour clock frequency, the number of lines per
     * frame (312 PAL / 262 NTSC) and the Agnus ID read by the Kickstart. A change takes
     * effect only after a restart (cold restart).
     */
    public VideoMode videoMode() {
        return autoEnabled() ? autoProfile().videoMode() : storedVideoMode();
    }

    /** Video mode chosen by hand (key {@code video.mode}), ignored by Auto. */
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
     * Custom chipset of the emulated machine: {@link Chipset#OCS} (default),
     * {@link Chipset#ECS} or {@link Chipset#AGA}. Selects the Agnus/Denise variant
     * instantiated and enables the ECS registers (BEAMCON0, DIWHIGH, BPLCON3, super-hires,
     * ...) and the AGA ones. A change takes
     * effect only after a restart (cold restart).
     */
    public Chipset chipset() {
        return autoEnabled() ? autoProfile().chipset() : storedChipset();
    }

    /**
     * Processor of the emulated machine: {@link CpuModel#M68000E} by default. In Auto mode
     * the floppy profile uses the event-driven 68000 and the hard disk/WHDLoad profile the
     * 68020. A change takes effect only after a cold restart.
     */
    public CpuModel cpuModel() {
        return autoEnabled() ? autoProfile().cpuModel() : storedCpuModel();
    }

    /** Processor chosen by hand (key {@code cpu.model}), ignored by Auto. */
    public CpuModel storedCpuModel() {
        return CpuModel.parse(props.getProperty(K_CPU_MODEL), CpuModel.M68000E);
    }

    public void setCpuModel(CpuModel model) {
        props.setProperty(K_CPU_MODEL, (model == null ? CpuModel.M68000E : model).label());
    }

    /** Chipset chosen by hand (key {@code chipset}), ignored by Auto. */
    public Chipset storedChipset() {
        return parseChipset(props.getProperty(K_CHIPSET), Chipset.OCS);
    }

    /** Converts the text value of a chipset; {@code def} if missing or invalid. */
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
     * Integer scaling factor of the <b>emulated window only</b>; 1 = native size. It does
     * not touch the interface: the GUI scale is a separate property.
     */
    public int integerScaling() {
        return Math.max(1, getInt(K_INTEGER_SCALING, 1));
    }

    /** {@code true} if overscan is enabled (default true). */
    public boolean overscanEnabled() {
        return getBool(K_OVERSCAN, true);
    }

    /** Mode of the main window (default windowed). */
    public AmigaWindowMode windowMode() {
        return AmigaWindowMode.fromConfigValue(props.getProperty(K_WINDOW_MODE));
    }

    /**
     * Index of the monitor on which to open the emulator window: 0 = primary screen
     * (default). If the configured index matches no connected monitor, the primary is used
     * (handled by {@code AmigaDisplay}).
     */
    public int monitorIndex() {
        return Math.max(0, getInt(K_MONITOR, 0));
    }

    /**
     * Rendering backend of the display: {@link RendererType#SOFTWARE} (Java2D, default) or
     * {@link RendererType#OPENGL} (LWJGL, with RetroArch shaders). A change requires
     * re-creating the window (cold restart), handled by {@code Amija}.
     */
    public RendererType renderer() {
        return RendererType.fromConfigValue(props.getProperty(K_RENDERER));
    }

    /**
     * Name of the selected RetroArch shader (OpenGL renderer only): path of the
     * {@code .slangp} or {@code .glslp} preset relative to the {@code shaders} folder, with
     * {@code /} separators and no extension (e.g. {@code Mega_Bezel/Presets/MBZ__5__POTATO}),
     * or {@code "none"} (default) for no shader (passthrough). Can be applied live from the
     * F12 popup.
     */
    public String shader() {
        String v = props.getProperty(K_SHADER, "none").trim();
        return v.isEmpty() ? "none" : v;
    }

    /**
     * Active state slot: the one "Save state" and "Load state" act on (1 = the first,
     * default). Chosen by the tabs of the "Saved state" section of the F12 popup; it is kept
     * here so the next start resumes from the same slot. The value is not a machine
     * parameter: the valid range is imposed by {@code SaveStateManager}, the only one that
     * knows how many slots exist.
     */
    public int saveStateSlot() {
        return Math.max(1, getInt(K_STATE_SLOT, 1));
    }

    /** {@code true} if the real FPS overlay must be shown (default false). */
    public boolean fpsOverlayEnabled() {
        return getBool(K_OVERLAY_FPS, false);
    }

    /** {@code true} if the emulated frame counter overlay must be shown (default false). */
    public boolean frameCounterOverlayEnabled() {
        return getBool(K_OVERLAY_FRAMECOUNTER, false);
    }

    /**
     * {@code true} if immediate blit is on (default false). In this mode the Blitter
     * completes every operation at once at the BLTSIZE write (no bus cycles emulated, BBUSY
     * never seen high): it is NOT cycle-exact, but speeds up games that poll the Blitter.
     * Configurable only from {@code amiga.properties}; not exposed in the F12 popup.
     */
    public boolean blitterImmediate() {
        return getBool(K_BLITTER_IMMEDIATE, false);
    }

    // =========================================================================
    // Setters (used by the configuration popup before {@link #save()})
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

    /** Writes the keyboard joystick keys of port {@code port} (0 or 1). */
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
     * Chooses the active state slot. Unlike the other settings of the popup this takes
     * effect at once, even if the popup is closed with Cancel: choosing a tab is not a
     * pending change to the machine, it says where to save now.
     */
    public void setSaveStateSlot(int slot) {
        props.setProperty(K_STATE_SLOT, Integer.toString(Math.max(1, slot)));
    }

    /**
     * Writes the interface scale factor. The value goes to the file with a decimal point and
     * no needless zeros (1, 1.25, 1.5, 2): {@code Double.toString} uses the point whatever
     * the system language, while a {@code String.format} with the default locale would
     * write "1,25" on an Italian machine, which {@code Double.parseDouble} would refuse on
     * reading.
     */
    public void setUiScale(double scale) {
        props.setProperty(K_UI_SCALE, formatScale(
                Math.min(UI_SCALE_MAX, Math.max(UI_SCALE_MIN, scale))));
    }

    /** The current factor in the form it takes in the file ({@code 1}, {@code 1.25}, ...). */
    public String uiScaleValue() {
        return formatScale(uiScale());
    }

    private static String formatScale(double v) {
        return v == Math.rint(v) ? Integer.toString((int) v) : Double.toString(v);
    }

    // =========================================================================
    // Remote play (docs/remote-play-plan.md section 10)
    // =========================================================================

    /** TCP+UDP port the host listens on. */
    public int remotePlayPort() {
        int p = getInt(K_RP_PORT, 7788);
        return p < 0 || p > 65535 ? 7788 : p;
    }

    public void setRemotePlayPort(int port) {
        props.setProperty(K_RP_PORT, Integer.toString(port));
    }

    /** Whether to try opening the port on the router with UPnP. */
    public boolean remotePlayUpnp() {
        return getBool(K_RP_UPNP, true);
    }

    public void setRemotePlayUpnp(boolean enabled) {
        props.setProperty(K_RP_UPNP, Boolean.toString(enabled));
    }

    /** Interface to listen on; empty = all. */
    public String remotePlayBind() {
        return props.getProperty(K_RP_BIND, "").trim();
    }

    public void setRemotePlayBind(String bind) {
        props.setProperty(K_RP_BIND, bind == null ? "" : bind.trim());
    }

    /**
     * Whether to refuse connections that do not come from the local network.
     *
     * <p>On by default (section 8.5): a port listening on the Internet is opened <b>after</b>
     * deciding to, not as a side effect of having tried remote play at home.
     */
    public boolean remotePlayLanOnly() {
        return getBool(K_RP_LAN_ONLY, true);
    }

    public void setRemotePlayLanOnly(boolean lanOnly) {
        props.setProperty(K_RP_LAN_ONLY, Boolean.toString(lanOnly));
    }

    /** Game port driven by the guest: 0 or 1. */
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

    /** Whether the guest may reset the machine with CTRL+Amiga+Amiga. Off by default. */
    public boolean remotePlayGuestAllowReset() {
        return getBool(K_RP_GUEST_ALLOWRESET, false);
    }

    public void setRemotePlayGuestAllowReset(boolean allow) {
        props.setProperty(K_RP_GUEST_ALLOWRESET, Boolean.toString(allow));
    }

    /** Audio format toward the guest: {@code adpcm}, {@code pcm16} or {@code off}. */
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

    /** Last host joined: the popup offers it again. */
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
     * Writes the current configuration to {@code amiga.properties} in the home
     * ({@link AppHome}), so it takes precedence over any copy on the class path at the next
     * start. The file is rewritten sorted and commented.
     *
     * <p><b>UTF-8, declared.</b> The charset is stated here and in {@link #load()}, and must
     * be the same: {@code Files.writeString} writes UTF-8 by default, while
     * {@code Properties.load(InputStream)} decodes <i>ISO-8859-1</i> by contract. With the
     * two halves in disagreement every path with an accent (the normal case on an Italian
     * installation) would be corrupted on reading, getting worse at every round because the
     * wrong bytes are written back as such.
     *
     * <p><b>First to a temporary file, then an atomic move.</b> The file is rewritten
     * entirely at every confirmation of the popup: writing straight to the destination, an
     * interruption halfway (crash, full disk, the JVM exiting while the shutdown hook runs)
     * would leave a truncated configuration in place of the good one. With the atomic move
     * either the new file or still the old one is seen.
     */
    public void save() throws IOException {
        Path target = AppHome.resolve(FILE_NAME);
        writeAtomically(target, toPropertiesText());
        System.out.println("[CONFIG] Saved " + target.toAbsolutePath());
    }

    /**
     * Writes {@code text} to {@code target} through a temporary file in the same folder; it
     * must be the same, or the move would cross a file system and stop being atomic.
     *
     * <p>If the system does not offer {@code ATOMIC_MOVE} a normal move is used: still much
     * better than writing in place, because the window in which the destination is
     * incomplete shrinks to that of the {@code move}.
     */
    private static void writeAtomically(Path target, String text) throws IOException {
        // The temporary file MUST be in the destination's folder: elsewhere it would land on
        // another file system and the move would stop being atomic (or fail). With AppHome
        // the parent always exists; the fallback on the current folder avoids depending on
        // that detail.
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

    /** Configuration with only the default values, without touching the disk. */
    public static AmigaConfig defaults() {
        return new AmigaConfig(defaultProperties());
    }

    /** Configuration built on properties already read: the reverse of {@link #toPropertiesText}. */
    public static AmigaConfig fromProperties(Properties props) {
        return new AmigaConfig(props);
    }

    /**
     * The configuration file as text, sorted and commented.
     *
     * <p>Separate from the writing on purpose: so serialisation is a pure function, and
     * what counts (that every key is there with the right value) is checked without a disk
     * in between and without depending on where {@code AppHome} decided to point.
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
        sb.append("#    hard disk or WHDLoad is in use, or the software needs AGA;\n");
        sb.append("#  - memory, chipset and processor: the auto.floppy.* profile (68000), or\n");
        sb.append("#    auto.hd.* (68020) when a hard disk or WHDLoad is in use, or auto.aga.*\n");
        sb.append("#    (A1200: AGA, 68020) when the WHDLoad slave requires AGA or a file name\n");
        sb.append("#    contains the word AGA. All three are editable right below;\n");
        sb.append("#  - video mode: PAL, unless the name of an active file (floppy, hard disk or\n");
        sb.append("#    WHDLoad archive) contains \"NTSC\" or \"(USA)\", in any case.\n");
        sb.append("# The hand-picked settings further down are left untouched and come back into\n");
        sb.append("# force the moment this is set to false.\n");
        line(sb, K_AUTO_EN, Boolean.toString(autoEnabled()));

        sb.append("\n# ABSOLUTE path of the two Kickstart ROMs used by the automatic configuration.\n");
        line(sb, K_AUTO_KS13, autoKickstart13());
        line(sb, K_AUTO_KS31, autoKickstart31());

        sb.append("\n# The three hardware profiles of the automatic configuration: auto.floppy.* is\n");
        sb.append("# used when only floppies are in play, auto.hd.* when a hard disk or WHDLoad is,\n");
        sb.append("# auto.aga.* when the software needs AGA (its Fast RAM only with a hard disk or\n");
        sb.append("# WHDLoad: from floppy it is a stock A1200).\n");
        sb.append("# Same units and values as the hand-picked settings below.\n");
        for (AutoDefaults d : AUTO_PROFILES) {
            line(sb, d.prefix() + "chipram.kb", Integer.toString(d.chipRamKb(this)));
            line(sb, d.prefix() + "slowram.kb", Integer.toString(d.slowRamKb(this)));
            line(sb, d.prefix() + "fastram.kb", Integer.toString(d.fastRamKb(this)));
            line(sb, d.prefix() + "chipset", d.chipset(this).name());
            line(sb, d.prefix() + "cpu", d.cpuModel(this).label());
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
        sb.append("# A zip holding several disks is a set: the bootable one goes into DF0, the rest\n");
        sb.append("# into the free connected drives, addressed as <archive>.zip!<n> (n = index in the zip).\n");
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

        sb.append("\n# Custom chipset: OCS (default), ECS or AGA. Selects the Agnus/Denise variant\n");
        sb.append("# and enables the ECS and AGA registers. Chip RAM > 512 KB requires ECS or AGA.\n");
        sb.append("# Automatic configuration off. The change takes effect only after restarting\n");
        sb.append("# the emulator (cold restart).\n");
        line(sb, K_CHIPSET, storedChipset().name());

        sb.append("\n# Processor: 68000E (default), 68010E or 68020, all event-driven; the\n");
        sb.append("# clocked 68000 and 68010 are still accepted. Automatic configuration off.\n");
        sb.append("# The change takes effect only after restarting the emulator (cold restart).\n");
        line(sb, K_CPU_MODEL, storedCpuModel().label());

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

        sb.append("\n# Active save state slot: 1..5 (default 1). \"Save State\" and \"Load State\"\n");
        sb.append("# in the F12 popup act on this slot, whose files live in\n");
        sb.append("# savestate/Slot<N>/ next to amiga.properties. Picking a tab in the popup\n");
        sb.append("# changes it.\n");
        line(sb, K_STATE_SLOT, Integer.toString(saveStateSlot()));

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

    /** Emits a {@code key=value} line, escaping backslashes (Windows paths). */
    private static void line(StringBuilder sb, String key, String value) {
        sb.append(key).append('=').append(value.replace("\\", "\\\\")).append('\n');
    }

    // =========================================================================
    // Snapshot of the configuration (save state)
    // =========================================================================

    /**
     * The keys that describe <b>the machine</b>, i.e. those that decide what the running
     * Amiga is made of: automatic mode and its profiles, Kickstart, memory, chipset, video
     * mode and mounted media. Everything else in {@code amiga.properties} (language,
     * window, monitor, renderer, shader, volumes, last folders) describes <i>the
     * emulator</i>, not the machine.
     *
     * <p>That is the distinction save states need: a slot is reloaded by rebuilding the
     * machine as it was, not by rolling back the user's interface preferences; finding the
     * window fullscreen or the emulator in another language because a game was loaded would
     * be a side effect, not a restore.
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
            keys.add(d.prefix() + "cpu");
        }
        keys.add(K_KICKSTART);
        keys.add(K_CHIPRAM_KB);
        keys.add(K_SLOWRAM_KB);
        keys.add(K_FASTRAM_KB);
        keys.add(K_CHIPSET);
        keys.add(K_CPU_MODEL);
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
     * Files the machine described by this configuration reads at start-up: the Kickstart
     * (when one is named) and every medium actually mounted, i.e. the disk of each connected
     * drive, the image of each enabled hard disk unit and the WHDLoad archive when its boot is
     * on. A floppy taken from a zip set names the zip file itself.
     */
    public java.util.List<String> machineFiles() {
        java.util.List<String> files = new java.util.ArrayList<>();
        String ks = kickstartPath();
        if (!ks.isEmpty()) files.add(ks);
        for (int i = 0; i < 4; i++) {
            String disk = driveDisk(i);
            if (driveEnabled(i) && disk != null) {
                files.add(io.github.zolfrik.amija.custom.DiskImage.isZipDisk(disk)
                        ? io.github.zolfrik.amija.custom.DiskImage.pathOf(disk).toString()
                        : disk);
            }
        }
        for (int unit = 0; unit < HD_UNITS.length; unit++) {
            String disk = hdDisk(unit);
            if (hdEnabled(unit) && disk != null) files.add(disk);
        }
        if (whdloadEnabled() && whdloadFile() != null) files.add(whdloadFile());
        return files;
    }

    /**
     * The files the machine described by {@code saved} needs (see {@link #machineFiles()})
     * that are not on the file system now. Empty when that machine can be rebuilt.
     */
    public static java.util.List<String> missingMachineFiles(Properties saved) {
        java.util.List<String> missing = new java.util.ArrayList<>();
        if (saved == null) return missing;
        Properties copy = new Properties();
        copy.putAll(saved);
        for (String f : new AmigaConfig(copy).machineFiles()) {
            boolean present;
            try {
                present = Files.isRegularFile(Path.of(f));
            } catch (java.nio.file.InvalidPathException e) {
                present = false;
            }
            if (!present) missing.add(f);
        }
        return missing;
    }

    /**
     * Independent copy of the current properties, i.e. {@code amiga.properties} as it is in
     * memory. Save states keep one inside the slot: it is the only description of the
     * machine that state requires.
     */
    public Properties snapshot() {
        Properties copy = new Properties();
        copy.putAll(props);
        return copy;
    }

    /**
     * {@code true} if {@code saved} describes a machine different from the one configured
     * now, i.e. if {@link #restoreMachineSettings} would have something to do.
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
     * Brings only the machine keys (see {@link #machineKeys()}) back to the values of
     * {@code saved}, leaving the interface preferences untouched. A key absent from
     * {@code saved} is <b>removed</b>, not left as it is: it means that machine ran with the
     * default value, and keeping the current one would rebuild it differently.
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
    // Parsing helpers
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
     * Like {@link #getInt}, but for fractional factors. The decimal separator is ALWAYS the
     * POINT: this is a configuration file, not localised text, and it must read the same on
     * every machine.
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
