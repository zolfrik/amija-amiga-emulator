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

package io.github.zolfrik.amija;

import io.github.zolfrik.amija.config.AmigaConfig;
import io.github.zolfrik.amija.display.AmigaWindowMode;
import io.github.zolfrik.amija.common.AppHome;
import io.github.zolfrik.amija.common.BusRequest;
import io.github.zolfrik.amija.common.Chipset;
import io.github.zolfrik.amija.common.BusResponse;
import io.github.zolfrik.amija.common.CIAPinSet;
import io.github.zolfrik.amija.common.MOS8520;
import io.github.zolfrik.amija.common.MasterClock;
import io.github.zolfrik.amija.common.VirtualCRT;
import io.github.zolfrik.amija.common.VideoMode;
import io.github.zolfrik.amija.cpu.Motorola68000;
import io.github.zolfrik.amija.custom.AbstractAgnus;
import io.github.zolfrik.amija.custom.OcsAgnus;
import io.github.zolfrik.amija.custom.EcsAgnus;
import io.github.zolfrik.amija.custom.AmigaJoystickKeyAdapter;
import io.github.zolfrik.amija.custom.AmigaKeyAdapter;
import io.github.zolfrik.amija.custom.AmigaMouseAdapter;
import io.github.zolfrik.amija.custom.Keyboard;
import io.github.zolfrik.amija.custom.AbstractDenise;
import io.github.zolfrik.amija.custom.OcsDenise;
import io.github.zolfrik.amija.custom.EcsDenise;
import io.github.zolfrik.amija.custom.DiskImage;
import io.github.zolfrik.amija.custom.FastRam;
import io.github.zolfrik.amija.custom.FloppyDrive;
import io.github.zolfrik.amija.custom.HdController;
import io.github.zolfrik.amija.memory.HardFile;
import io.github.zolfrik.amija.whdload.WhdLoadManager;
import io.github.zolfrik.amija.custom.audio.FloppySound;
import io.github.zolfrik.amija.custom.Gary;
import io.github.zolfrik.amija.custom.Paula;
import io.github.zolfrik.amija.display.AmigaDisplay;
import io.github.zolfrik.amija.display.ConfigDialog;
import io.github.zolfrik.amija.display.render.RendererType;
import io.github.zolfrik.amija.i18n.Messages;
import io.github.zolfrik.amija.input.GamePort;
import io.github.zolfrik.amija.input.GlfwJoystickService;
import io.github.zolfrik.amija.input.PortDevice;
import io.github.zolfrik.amija.memory.RAM;
import io.github.zolfrik.amija.memory.ROM;
import io.github.zolfrik.amija.state.SaveStateManager;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;

/**
 * Classe principale di AmiJa, l'emulatore Amiga 500. Istanzia e collega tutti i
 * componenti interni, avviando la simulazione cycle-exact.
 *
 * <p>Funge anche da controller del ciclo di vita della macchina: la configurazione
 * è letta da {@link AmigaConfig} ed è modificabile a runtime tramite il popup
 * raggiungibile con <b>F12</b> ({@link ConfigDialog}). All'apertura del popup
 * l'emulazione va in pausa e il mouse host viene rilasciato; alla chiusura la
 * macchina riparte (a freddo o riprendendo) e il mouse viene ricatturato.
 */
public class Amija {

    private static final boolean VERBOSE = false;
    // Modalita' video corrente (PAL/NTSC): letta da config a ogni avvio a freddo
    // (bootMachine). Il cambio nel popup F12 ha effetto solo dopo un cold restart.
    private VideoMode videoMode = VideoMode.PAL;

    // --- Stato persistente tra i riavvii (finestra/display + configurazione) ---
    private AmigaConfig config;
    private AmigaDisplay display;
    private JFrame frame;
    // Componente a cui sono agganciati i listener di input: il frame in software, il
    // canvas GL (heavyweight) in OpenGL. Fissato a ogni (ri)creazione del display.
    private java.awt.Component inputComponent;

    // --- Componenti della macchina corrente (ricreati a ogni avvio a freddo) ---
    private MasterClock clock;
    private Paula paula;
    private VirtualCRT virtualCRT;
    private Motorola68000 cpu;
    private AbstractAgnus agnus;
    private AmigaMouseAdapter mouseAdapter;
    private AmigaKeyAdapter keyAdapter;
    private AmigaJoystickKeyAdapter joystickAdapter;
    // Game port correnti (ricreate a ogni boot): Port 0 = mouse/JOY0DAT, Port 1 = joystick/JOY1DAT.
    private GamePort gamePort1;
    private GamePort gamePort2;
    // Servizio joystick reale (GLFW): singleton, avviato pigramente solo se selezionato.
    private final GlfwJoystickService joystickService = GlfwJoystickService.getInstance();
    private final FloppySound[] floppySounds = new FloppySound[4];
    private final java.util.List<HardFile> hardFiles = new java.util.ArrayList<>();
    // Sorgenti di attività per il LED HD del display, una per unità (WHDLoad, master,
    // slave) nell'ordine in cui devono comparire da sinistra a destra.
    private final java.util.List<java.util.function.BooleanSupplier> hdLedSources =
            new java.util.ArrayList<>();

    public static void main(String[] args) {
        // L'integer scaling video e' demandato a Java2D tramite sun.java2d.uiScale:
        // il valore viene da video.integerScaling in amiga.properties e va impostato
        // QUI, prima che il toolkit AWT/Swing si inizializzi (la prima init avviene in
        // applyWindowsLookAndFeel). Il cambio del valore nel popup F12 richiede quindi
        // il riavvio dell'emulatore per avere effetto.
        // Con renderer=opengl la magnificazione la fa la GPU: uiScale va forzata a 1,
        // altrimenti Java2D scalerebbe anche il canvas GL (finestra sovradimensionata).
        System.setProperty("sun.java2d.uiScale", Integer.toString(effectiveUiScale(AmigaConfig.load())));
        applyWindowsLookAndFeel();
        new Amija().start();
    }

    /**
     * Fattore {@code sun.java2d.uiScale} effettivo: {@code video.integerScaling} col
     * renderer software, ma sempre 1 con OpenGL (lo scaling è a carico della GPU).
     */
    private static int effectiveUiScale(AmigaConfig cfg) {
        return effectiveUiScale(cfg.renderer(), cfg.integerScaling());
    }

    private static int effectiveUiScale(RendererType renderer, int integerScaling) {
        return renderer == RendererType.OPENGL ? 1 : integerScaling;
    }

    /** Applica il Look and Feel nativo di Windows all'intera interfaccia Swing. */
    private static void applyWindowsLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (ReflectiveOperationException | UnsupportedLookAndFeelException e) {
            System.err.println("[UI] Look and Feel Windows non disponibile: " + e.getMessage());
        }
    }

    // =========================================================================
    // Ciclo di vita
    // =========================================================================

    public void start() {
        if (VERBOSE) {
            System.out.println("========================================");
            System.out.println("      AmiJa Emulator - Boot             ");
            System.out.println("========================================");
        }

        config = AmigaConfig.load();
        Messages.setLanguage(config.language()); // prima di creare qualsiasi UI
        boolean overscan = config.overscanEnabled();
        int fw = config.videoMode().displayWidth(overscan);
        int fh = config.videoMode().displayHeight(overscan);
        display = new AmigaDisplay(fw, fh, config.windowMode(), config.renderer(),
                config.integerScaling(), config.monitorIndex());
        frame = display.getFrame();
        inputComponent = display.getInputComponent();
        // Un disco droppato su un drive aggiorna la config in memoria, così il popup F12
        // mostra il percorso nel drive relativo (e lo salva se l'utente conferma con OK).
        display.setDiskDropListener(this::rememberDroppedDisk);
        display.setShaderPreset(resolveShaderPath(config.shader()));
        installConfigHotkey();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            shutdownMachine();
            joystickService.shutdown(); // termina GLFW (solo alla chiusura del processo)
        }, "amiga-shutdown"));

        if (validateRom(config)) {
            bootMachine();
        } else {
            // ROM mancante/errata al primo avvio: apri il popup così l'utente può
            // impostare il percorso del Kickstart e gli altri parametri.
            SwingUtilities.invokeLater(this::openConfig);
        }
    }

    /** Registra la hotkey globale F12 (apre il popup di configurazione). */
    private void installConfigHotkey() {
        if (inputComponent == null) return;
        // Aggiunto PRIMA degli adapter di macchina: consuma F12 prima che raggiunga
        // la tastiera Amiga (dove sarebbe HELP). Va sul componente di input (canvas GL in
        // OpenGL): gli eventi tastiera del canvas heavyweight non risalgono al frame.
        inputComponent.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_F12) {
                    e.consume();
                    openConfig();
                }
            }

            @Override
            public void keyReleased(KeyEvent e) {
                // Va consumato anche il rilascio: senza, alla tastiera Amiga arriverebbe
                // il solo key-up di HELP (F12 è mappato lì) senza la pressione.
                if (e.getKeyCode() == KeyEvent.VK_F12) {
                    e.consume();
                }
            }
        });
    }

    // =========================================================================
    // Popup di configurazione (F12)
    // =========================================================================

    /**
     * Mette in pausa l'emulazione, rilascia il mouse e mostra il popup. In base alla
     * scelta dell'utente salva/riavvia, riavvia a freddo, esegue un soft reset o
     * riprende l'esecuzione; infine ricattura il mouse.
     */
    private void openConfig() {
        boolean wasRunning = clock != null && clock.isRunning();
        if (clock != null) clock.pause();
        if (mouseAdapter != null) mouseAdapter.release();
        boolean exclusiveFullScreenSuspended = display != null && display.suspendExclusiveFullScreen();

        // Stato dei floppy prima della modifica: serve a calcolare il diff da applicare
        // a caldo (inserimento/sostituzione/espulsione) quando l'utente conferma con OK.
        String[] oldDisks = new String[4];
        boolean[] oldEnabled = new boolean[4];
        boolean[] oldReadOnly = new boolean[4];
        for (int i = 0; i < 4; i++) {
            oldDisks[i] = config.driveDisk(i);
            oldEnabled[i] = config.driveEnabled(i);
            oldReadOnly[i] = config.driveReadOnly(i);
        }
        boolean oldOverscan = config.overscanEnabled();
        int oldIntegerScaling = config.integerScaling();
        AmigaWindowMode oldWindowMode = config.windowMode();
        int oldMonitorIndex = config.monitorIndex();
        VideoMode oldVideoMode = config.videoMode();
        Chipset oldChipset = config.chipset();
        RendererType oldRenderer = config.renderer();
        String oldShader = config.shader();

        ConfigDialog.Result result = ConfigDialog.show(frame, config,
                this::takeScreenshotFile, this::updateShaderParam,
                this::saveState, this::loadState);
        boolean displayRecreated = false;
        switch (result) {
            case OK -> {
                // OK applica a caldo input/floppy; le impostazioni display richiedono
                // invece la ricreazione della finestra, quindi un cold restart.
                saveConfig();
                applyRuntimeInputConfig();
                boolean displayChanged =
                        displayConfigChanged(oldOverscan, oldIntegerScaling, oldWindowMode,
                                oldMonitorIndex)
                        || oldVideoMode != config.videoMode()
                        || oldChipset != config.chipset()
                        || oldRenderer != config.renderer();
                if (displayChanged) {
                    // Il cambio di renderer può cambiare l'uiScale effettiva (GPU=1 vs
                    // software=integerScaling): in tal caso rilancio la JVM per
                    // reimpostarla (il toolkit la fissa una sola volta all'avvio),
                    // altrimenti basta il cold restart.
                    if (oldRenderer != config.renderer()
                            && effectiveUiScale(oldRenderer, oldIntegerScaling)
                                != effectiveUiScale(config.renderer(), config.integerScaling())) {
                        relaunchApplication(); // non ritorna se il rilancio riesce
                    }
                    coldRestart();
                    displayRecreated = true;
                } else {
                    // Nessun cold restart: se è cambiato solo lo shader (renderer OpenGL),
                    // applicalo a caldo alla superficie GL.
                    if (config.renderer() == RendererType.OPENGL
                            && !oldShader.equals(config.shader())) {
                        display.setShaderPreset(resolveShaderPath(config.shader()));
                    }
                    if (wasRunning) {
                        clock.resume();
                        applyFloppyChanges(oldDisks, oldEnabled, oldReadOnly);
                    } else {
                        resumeOrStart();
                    }
                }
            }
            case RESTART -> {
                saveConfig();
                // L'integer scaling e' applicato da sun.java2d.uiScale, fissata dal
                // toolkit AWT una sola volta all'avvio: per renderlo effettivo serve
                // un riavvio dell'intero processo, non il solo power-cycle della
                // macchina. Se e' cambiato, rilancio la JVM; altrimenti basta il cold
                // restart (piu' veloce, resta nello stesso processo).
                if (oldIntegerScaling != config.integerScaling()) {
                    relaunchApplication(); // non ritorna se il rilancio riesce
                }
                coldRestart();
                displayRecreated = true;
            }
            case SOFT_RESET -> {
                saveConfig();
                applyRuntimeInputConfig();
                if (cpu != null) cpu.reset();
                resumeOrStart();
            }
            case CANCEL -> resumeOrStart();
            case QUIT -> { System.exit(0); return; } // lo shutdown hook spegne la macchina
        }
        if (exclusiveFullScreenSuspended && !displayRecreated && display != null) {
            display.resumeExclusiveFullScreen();
        }
        recaptureMouse();
    }

    /**
     * Applica a caldo le modifiche ai floppy decise nel popup, confrontando lo stato
     * salvato in {@link #config} con quello precedente all'apertura. Per ogni drive:
     * collega/scollega, espelle il disco rimosso e inserisce quello aggiunto. In caso
     * di sostituzione espelle, attende 1500 ms (come nel drag-and-drop, così il
     * Kickstart rileva l'espulsione) e poi inserisce il nuovo disco. Tutto su un thread
     * daemon per non bloccare l'EDT, mentre la macchina è già in esecuzione.
     */
    private void applyFloppyChanges(String[] oldDisks, boolean[] oldEnabled, boolean[] oldReadOnly) {
        applyFloppyChanges(oldDisks, oldEnabled, oldReadOnly, -1);
    }

    /**
     * @param skipIndex drive già allineato dal chiamante (es. quello appena ricevuto
     *                  via drag-and-drop), da escludere dal diff; -1 = nessuno
     */
    private void applyFloppyChanges(String[] oldDisks, boolean[] oldEnabled,
                                    boolean[] oldReadOnly, int skipIndex) {
        if (agnus == null) return;
        // Prima del diff: propaga agli altri drive i dischi dello zip multi-disco
        // eventualmente inserito in DF0 (o rimuove quelli dello zip precedente).
        syncZipDiskSet(oldDisks[0]);
        final FloppyDrive[] dfs = {
                agnus.getDf0(), agnus.getDf1(), agnus.getDf2(), agnus.getDf3() };
        final String[] newDisks = new String[4];
        final boolean[] newEnabled = new boolean[4];
        final boolean[] newReadOnly = new boolean[4];
        for (int i = 0; i < 4; i++) {
            newDisks[i] = config.driveDisk(i);
            newEnabled[i] = config.driveEnabled(i);
            newReadOnly[i] = config.driveReadOnly(i);
        }

        Thread t = new Thread(() -> {
            for (int i = 0; i < 4; i++) {
                FloppyDrive drive = dfs[i];
                if (drive == null || i == skipIndex) continue;
                if (newEnabled[i] != oldEnabled[i]) {
                    drive.setConnected(newEnabled[i]);
                }
                applyFloppySound(drive, i);
                if (!newEnabled[i]) {
                    // Drive scollegato: niente disco dentro.
                    if (drive.isDiskInserted()) {
                        System.out.println("[CONFIG] DF" + i + ": espulsione (drive scollegato)");
                        drive.ejectDisk();
                    }
                    continue;
                }
                String oldPath = norm(oldDisks[i]);
                String newPath = norm(newDisks[i]);
                boolean readOnlyChanged = oldReadOnly != null && oldReadOnly[i] != newReadOnly[i];
                if (oldPath.equals(newPath) && !readOnlyChanged) continue; // nessuna modifica per questo drive
                try {
                    if (drive.isDiskInserted()) {
                        System.out.println("[CONFIG] DF" + i + ": espulsione disco corrente...");
                        drive.ejectDisk();
                        Thread.sleep(1500);
                    }
                    if (!newPath.isEmpty()) {
                        System.out.println("[CONFIG] DF" + i + ": inserimento " + newPath);
                        drive.loadAdf(newPath, newReadOnly[i]);
                    }
                } catch (Exception e) {
                    System.err.println("[CONFIG] DF" + i
                            + ": errore applicazione disco: " + e.getMessage());
                }
            }
        }, "config-floppy-apply");
        t.setDaemon(true);
        t.start();
    }

    /** Normalizza un percorso disco: null/spazi -> stringa vuota. */
    private static String norm(String s) {
        return s == null ? "" : s.trim();
    }

    /**
     * Distribuisce su DF1..DF3 i dischi di uno zip multi-disco inserito in DF0.
     *
     * <p>Uno zip che contiene più .adf/.ipf è un set di dischi: il primo va in DF0
     * (comportamento storico) e i successivi, nell'ordine in cui compaiono
     * nell'archivio, nei drive seguenti — ma solo se collegati e vuoti. I dischi
     * così montati sono identificati dal percorso {@code <zip>!<n>}: quando lo zip
     * di DF0 viene sostituito o espulso vengono rimossi automaticamente, mentre
     * restano modificabili/espellibili uno a uno con la procedura normale.
     *
     * <p>Agisce solo se DF0 è <i>cambiato</i>: finché lo zip resta lo stesso, DF1..DF3
     * sono lasciati esattamente come l'utente li ha impostati (un disco espulso a mano
     * non viene rimontato). Aggiorna la configurazione, che è la sorgente sia del diff
     * a caldo sia del popup F12.
     *
     * @param oldDf0 percorso presente in DF0 prima della modifica;
     *               {@code null} per forzare la risincronizzazione (avvio macchina)
     */
    private void syncZipDiskSet(String oldDf0) {
        String df0 = norm(config.driveDisk(0));
        if (oldDf0 != null && norm(oldDf0).equals(df0)) return; // DF0 invariato
        String previous = norm(oldDf0);

        java.util.List<String> entries = java.util.List.of();
        if (DiskImage.isZipDisk(df0)) {
            try {
                entries = DiskImage.listZipDiskEntries(DiskImage.pathOf(df0));
            } catch (IOException e) {
                System.err.println("[CONFIG] ZIP non leggibile (" + df0 + "): " + e.getMessage());
            }
        }

        for (int i = 1; i < 4; i++) {
            String disk = norm(config.driveDisk(i));
            // 1. libera i drive che tenevano un disco dello zip (vecchio o nuovo: la
            //    riassegnazione qui sotto è idempotente).
            if (isEntryOf(disk, previous) || isEntryOf(disk, df0)) {
                disk = "";
                config.setDriveDisk(i, "");
            }
            // 2. l'i-esimo disco dello zip va nell'i-esimo drive, se libero e collegato.
            if (i < entries.size() && disk.isEmpty() && config.driveEnabled(i)) {
                config.setDriveDisk(i, DiskImage.zipEntrySpec(df0, i));
                config.setDriveReadOnly(i, true);
                System.out.println("[CONFIG] DF" + i + ": disco " + i + " dello zip -> "
                        + entries.get(i));
            }
        }
    }

    /** {@code true} se {@code disk} è un disco estratto dallo zip {@code zipPath}. */
    private static boolean isEntryOf(String disk, String zipPath) {
        return !zipPath.isEmpty() && DiskImage.isZipDisk(zipPath)
                && disk.regionMatches(true, 0, zipPath, 0, zipPath.length())
                && disk.length() > zipPath.length()
                && disk.charAt(zipPath.length()) == DiskImage.ZIP_ENTRY_SEPARATOR;
    }

    private void rememberDroppedDisk(String path, int index) {
        String[] oldDisks = new String[4];
        boolean[] oldEnabled = new boolean[4];
        boolean[] oldReadOnly = new boolean[4];
        for (int i = 0; i < 4; i++) {
            oldDisks[i] = config.driveDisk(i);
            oldEnabled[i] = config.driveEnabled(i);
            oldReadOnly[i] = config.driveReadOnly(i);
        }
        config.setDriveDisk(index, path);
        config.setDriveReadOnly(index, isZipPath(path));
        // Il drive che ha ricevuto il drop è già a posto: applica solo l'effetto a
        // catena sugli altri (set multi-disco montato o rimosso).
        applyFloppyChanges(oldDisks, oldEnabled, oldReadOnly, index);
    }

    private static boolean isZipPath(String path) {
        return DiskImage.isZipDisk(path);
    }

    private void saveConfig() {
        try {
            config.save();
        } catch (IOException e) {
            error(Messages.t("error.save", e.getMessage()));
        }
    }

    private void applyRuntimeInputConfig() {
        applyPortAssignments();
    }

    /**
     * Applica (anche a caldo) le periferiche host scelte per le due game port. Per ogni
     * porta la property {@code input.portN.device} indica se è pilotata dal mouse, da un
     * joystick reale (GLFW), dal joystick su tastiera o se è vuota. "Port 0" corrisponde
     * alla game port 1 (mouse/JOY0DAT), "Port 1" alla game port 2 (joystick/JOY1DAT).
     *
     * <p>Idempotente e sicuro a caldo: reindirizza gli adapter mouse/tastiera sulla porta
     * corretta (abilitandoli/disabilitandoli) e comunica al servizio GLFW l'elenco delle
     * porte che usano un joystick reale.
     */
    private void applyPortAssignments() {
        if (gamePort1 == null || gamePort2 == null) return;

        PortDevice d0 = config.portDevice(0); // "Port 0" -> game port 1 (mouse/JOY0DAT)
        PortDevice d1 = config.portDevice(1); // "Port 1" -> game port 2 (joystick/JOY1DAT)

        // Periferica hardware riportata da ciascuna porta (per le letture JOYxDAT/POTINP).
        gamePort1.setDevice(d0.hardwareDevice());
        gamePort2.setDevice(d1.hardwareDevice());

        // Mouse host -> porta impostata su MOUSE (priorità a "Port 0" in caso di doppia).
        GamePort mousePort = (d0 == PortDevice.MOUSE) ? gamePort1
                           : (d1 == PortDevice.MOUSE) ? gamePort2 : null;
        if (mouseAdapter != null) {
            mouseAdapter.setPort(mousePort);
            mouseAdapter.setEnabled(mousePort != null);
        }

        // Joystick da tastiera: ciascuna porta ha i propri tasti (input.portN.joystick.*),
        // quindi entrambe possono essere pilotate insieme dalla stessa tastiera.
        if (joystickAdapter != null) {
            joystickAdapter.configure(0,
                    d0 == PortDevice.JOYSTICK_KEYBOARD ? gamePort1 : null,
                    config.joystickKeyMap(0));
            joystickAdapter.configure(1,
                    d1 == PortDevice.JOYSTICK_KEYBOARD ? gamePort2 : null,
                    config.joystickKeyMap(1));
        }

        // Joystick reale (GLFW) -> tutte le porte impostate su di esso, in ordine.
        java.util.List<GamePort> realPorts = new java.util.ArrayList<>(2);
        if (d0 == PortDevice.JOYSTICK_REAL) realPorts.add(gamePort1);
        if (d1 == PortDevice.JOYSTICK_REAL) realPorts.add(gamePort2);
        joystickService.setPorts(realPorts);
    }

    private void applyFloppySounds(FloppyDrive[] drives) {
        for (int i = 0; i < drives.length && i < floppySounds.length; i++) {
            applyFloppySound(drives[i], i);
        }
    }

    private void applyFloppySound(FloppyDrive drive, int index) {
        if (drive == null || index < 0 || index >= floppySounds.length) return;

        boolean enabled = config.floppySoundEnabled() && config.driveEnabled(index);
        float gainDb = config.floppySoundGainDb();
        synchronized (floppySounds) {
            FloppySound sound = floppySounds[index];
            if (!enabled) {
                if (sound != null) {
                    sound.close();
                    floppySounds[index] = null;
                }
                drive.setFloppySound(null);
                return;
            }

            if (sound == null) {
                sound = new FloppySound(true, gainDb);
                floppySounds[index] = sound;
            } else if (sound.getGainDb() != gainDb) {
                sound.setGainDb(gainDb);
            }
            drive.setFloppySound(sound);
        }
    }

    private void closeFloppySounds() {
        synchronized (floppySounds) {
            for (int i = 0; i < floppySounds.length; i++) {
                if (floppySounds[i] != null) {
                    floppySounds[i].close();
                    floppySounds[i] = null;
                }
            }
        }
    }

    private boolean displayConfigChanged(boolean oldOverscan, int oldIntegerScaling,
            AmigaWindowMode oldWindowMode, int oldMonitorIndex) {
        return oldOverscan != config.overscanEnabled()
                || oldIntegerScaling != config.integerScaling()
                || oldWindowMode != config.windowMode()
                || oldMonitorIndex != config.monitorIndex();
    }

    /**
     * Riavvia l'intero processo JVM (non il solo power-cycle della macchina): rilancia
     * un nuovo processo java con lo stesso classpath e working dir, poi esce. Serve a
     * far rileggere {@code sun.java2d.uiScale} (impostata in {@link #main} da
     * {@code video.integerScaling}), che il toolkit AWT fissa una sola volta all'avvio
     * e non e' modificabile a caldo. Se il rilancio fallisce non esce: il chiamante
     * prosegue con un cold restart della sola macchina.
     */
    private void relaunchApplication() {
        try {
            String javaBin = System.getProperty("java.home")
                    + File.separator + "bin" + File.separator + "java";
            // La home va RIPASSATA esplicitamente: qui si rilancia "java -cp ...", non il
            // launcher nativo, quindi le --java-options impostate da jpackage (fra cui
            // -Damija.home=$ROOTDIR) andrebbero perse e il nuovo processo ripiegherebbe
            // sulla cartella del jar, che dentro un'app-image e' <root>/app.
            // inheritIO: il nuovo processo riusa console/stdio correnti. La working dir
            // viene ereditata, quindi amiga.properties (gia' salvata) e' la stessa.
            new ProcessBuilder(javaBin,
                    "-D" + AppHome.PROPERTY + "=" + AppHome.home(),
                    "--enable-native-access=ALL-UNNAMED",
                    "-cp", System.getProperty("java.class.path"),
                    Amija.class.getName())
                    .inheritIO()
                    .start();
            System.exit(0); // lo shutdown hook spegne la macchina corrente
        } catch (IOException ex) {
            System.err.println("[UI] Riavvio del processo non riuscito, eseguo un cold "
                    + "restart (il nuovo integer scaling sara' effettivo al prossimo "
                    + "riavvio manuale): " + ex.getMessage());
        }
    }

    /** Spegne e riaccende la macchina da zero (power-cycle) con la config corrente. */
    private void coldRestart() {
        shutdownMachine();
        recreateDisplay();
        if (validateRom(config)) {
            bootMachine();
        }
    }

    /** Ricrea finestra e display, necessario per impostazioni applicate solo al riavvio. */
    private void recreateDisplay() {
        if (display != null) {
            display.dispose();
        } else if (frame != null) {
            frame.dispose();
        }
        // Modalità video letta da config (non dal campo videoMode, ancora fermo al boot
        // precedente: in coldRestart recreateDisplay precede bootMachine).
        boolean overscan = config.overscanEnabled();
        int fw = config.videoMode().displayWidth(overscan);
        int fh = config.videoMode().displayHeight(overscan);
        display = new AmigaDisplay(fw, fh, config.windowMode(), config.renderer(),
                config.integerScaling(), config.monitorIndex());
        frame = display.getFrame();
        inputComponent = display.getInputComponent();
        display.setDiskDropListener(this::rememberDroppedDisk);
        display.setShaderPreset(resolveShaderPath(config.shader()));
        installConfigHotkey();
    }

    /**
     * Risolve il nome dello shader configurato nel percorso del {@code .glslp} sotto
     * {@code shaders/}. {@code "none"}/vuoto o file assente -&gt; {@code null} (passthrough).
     */
    private static java.nio.file.Path resolveShaderPath(String name) {
        if (name == null) return null;
        String n = name.trim();
        if (n.isEmpty() || n.equalsIgnoreCase("none")) return null;
        java.nio.file.Path p = AppHome.resolve("shaders", n + ".glslp");
        return java.nio.file.Files.isRegularFile(p) ? p : null;
    }

    /**
     * Salva uno screenshot della finestra di gioco in {@code screenshots/} (software:
     * composizione Java2D; OpenGL: {@code glReadPixels}). Ritorna il file salvato o
     * {@code null} in caso di errore. Usato dal pulsante F12 "Take Screenshot".
     */
    private File takeScreenshotFile() {
        if (display == null) return null;
        try {
            File dir = AppHome.file("screenshots");
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            File file = new File(dir, "screenshot_" + ts + ".png");
            display.saveScreenshot(file);
            System.out.println("[SHOT] " + file.getAbsolutePath());
            return file;
        } catch (IOException e) {
            System.err.println("[SHOT] Salvataggio screenshot fallito: " + e.getMessage());
            return null;
        }
    }

    /** Applica a caldo un parametro dello shader OpenGL (dal popup "..."). */
    private void updateShaderParam(String name, Float value) {
        if (display != null && value != null) {
            display.setShaderParam(name, value);
        }
    }

    /**
     * Salva l'intero stato della macchina nello slot unico ({@code savestate/state1.a5s}).
     * Invocato dal pulsante F12 "Save State" a macchina in pausa (il popup è aperto).
     * Ritorna il messaggio di esito localizzato da mostrare all'utente.
     */
    private String saveState() {
        if (cpu == null || agnus == null) {
            return Messages.t("savestate.save.fail", Messages.t("savestate.nomachine"));
        }
        try {
            File file = SaveStateManager.slotFile();
            SaveStateManager.save(file, cpu, agnus, gamePort1, gamePort2, config);
            System.out.println("[STATE] salvato " + file.getAbsolutePath());
            saveStateScreenshot();
            return Messages.t("savestate.save.ok", file.getAbsolutePath());
        } catch (Exception e) {
            System.err.println("[STATE] salvataggio fallito: " + e.getMessage());
            return Messages.t("savestate.save.fail", String.valueOf(e.getMessage()));
        }
    }

    /**
     * Salva accanto allo slot uno screenshot dello schermo ({@code savestate/state1.png}),
     * a nome fisso: ogni salvataggio sovrascrive l'immagine precedente. Un errore qui non
     * invalida lo slot già scritto, quindi viene solo segnalato a console.
     */
    private void saveStateScreenshot() {
        if (display == null) return;
        try {
            File shot = SaveStateManager.slotScreenshot();
            display.saveScreenshot(shot);
            System.out.println("[STATE] screenshot " + shot.getAbsolutePath());
        } catch (Exception e) {
            System.err.println("[STATE] screenshot dello slot fallito: " + e.getMessage());
        }
    }

    /**
     * Ripristina lo stato della macchina dallo slot unico, sovrascrivendo i componenti
     * correnti (macchina in pausa). Invocato dal pulsante F12 "Load State". Ritorna il
     * messaggio di esito localizzato.
     */
    private String loadState() {
        if (cpu == null || agnus == null) {
            return Messages.t("savestate.load.fail", Messages.t("savestate.nomachine"));
        }
        File file = SaveStateManager.slotFile();
        if (!file.isFile()) {
            return Messages.t("savestate.load.none");
        }
        try {
            SaveStateManager.load(file, cpu, agnus, gamePort1, gamePort2, config);
            System.out.println("[STATE] caricato " + file.getAbsolutePath());
            return Messages.t("savestate.load.ok");
        } catch (SaveStateManager.IncompatibleStateException e) {
            return Messages.t("savestate.load.incompatible", e.getMessage());
        } catch (Exception e) {
            System.err.println("[STATE] caricamento fallito: " + e.getMessage());
            return Messages.t("savestate.load.fail", String.valueOf(e.getMessage()));
        }
    }

    /** Riprende la macchina in pausa, oppure la avvia se non era in esecuzione. */
    private void resumeOrStart() {
        if (clock != null && clock.isRunning()) {
            clock.resume();
        } else if (validateRom(config)) {
            bootMachine();
        }
    }

    private void recaptureMouse() {
        if (mouseAdapter == null || frame == null) return;
        final AmigaMouseAdapter target = mouseAdapter;
        // Subito dopo la chiusura di un dialog modale la finestra non è ancora la
        // "active window": requestFocusInWindow() è un no-op e il robot.mouseMove
        // viene scartato durante la transizione di focus (il cursore risulta nascosto
        // ma non agganciato al centro). Rinviamo brevemente la cattura, portando prima
        // la finestra in primo piano e forzando il focus.
        javax.swing.Timer timer = new javax.swing.Timer(150, e -> {
            frame.toFront();
            frame.requestFocus();
            // In OpenGL il fuoco tastiera deve tornare sul canvas (l'input è lì).
            if (inputComponent != null) inputComponent.requestFocusInWindow();
            target.captureMouse();
        });
        timer.setRepeats(false);
        timer.start();
    }

    // =========================================================================
    // Costruzione / distruzione della macchina
    // =========================================================================

    /** Costruisce e avvia un'intera macchina A500 a partire da {@link #config}. */
    private void bootMachine() {
        try {
            // Applica la lingua corrente: il rebuild del display (setDrives) rigenera
            // l'overlay drag-and-drop con le stringhe localizzate aggiornate.
            Messages.setLanguage(config.language());
            // Modalita' video (PAL/NTSC): determina clock, righe per frame e ID Agnus.
            // Letta qui così un cold restart dopo il cambio nel popup F12 la applica.
            videoMode = config.videoMode();
            final String kickstartPath = config.kickstartPath();

            // 1. Decodifica indirizzi (Gary) e memoria.
            Gary gary = Gary.defaultA500();
            RAM chipRam = new RAM(config.chipRamKb() * 1024);
            // La Region CHIP_RAM di defaultA500() ha physicalSize cablato a 512 KB:
            // allineala alla configurazione, altrimenti gli indirizzi sopra $07FFFF
            // wrappano sui primi 512 KB e il Kickstart rileva solo 512 KB di chip
            // (es. WHDLoad richiede chip contigua fino a $A0000 / 640 KB).
            gary.setChipRamSize(config.chipRamKb() * 1024);
            RAM slowRam = config.slowRamKb() > 0 ? new RAM(config.slowRamKb() * 1024) : null;

            byte[] romData = Files.readAllBytes(Paths.get(kickstartPath));
            ROM kickstart = new ROM(romData);

            // 2. CPU e bus.
            cpu = new Motorola68000(new BusRequest(), new BusResponse());

            // 3. Custom chips. Il chipset (OCS/ECS) seleziona la variante di Agnus/Denise.
            // Si rispetta SEMPRE la scelta dell'utente: l'address counter DMA di
            // AbstractAgnus deriva la maschera dei puntatori dalla dimensione della Chip
            // RAM (dmaPtrHiMask = chipMask & 0x1F0000), quindi OcsAgnus indirizza già la
            // Chip RAM fino a 2 MB. NON si forza più ECS quando la Chip RAM supera i
            // 512 KB: oltre a non essere necessario, manderebbe la selezione OCS sul path
            // ECS+Kickstart 3.1 (non ancora validato) provocando un guru all'avvio
            // (regressione: p.es. Agony via WHDLoad con Chip RAM 1 MB).
            Chipset chipset = config.chipset();
            final boolean ecs = chipset.isEcs();
            System.out.println("[CONFIG] Chipset: " + chipset);
            AbstractDenise denise = ecs ? new EcsDenise() : new OcsDenise();
            paula = new Paula();
            paula.startAudioOutput();

            CIAPinSet ciaAPins = new CIAPinSet();
            MOS8520 ciaA = new MOS8520(ciaAPins, true);
            MOS8520 ciaB = new MOS8520(new CIAPinSet(), false);
            ciaA.setName("CIA-A");
            ciaB.setName("CIA-B");

            // Game port 1 (JOY0DAT, "Port 0") e port 2 (JOY1DAT, "Port 1"). La periferica
            // host di ciascuna (mouse / joystick reale / joystick da tastiera / vuota) è
            // decisa da applyPortAssignments in base alla configurazione (property
            // input.port0.device / input.port1.device, tendine del popup F12).
            GamePort port1 = new GamePort(1);
            GamePort port2 = new GamePort(2);
            gamePort1 = port1;
            gamePort2 = port2;
            port1.setCIAAPins(ciaAPins);
            port2.setCIAAPins(ciaAPins);
            denise.setGamePort1(port1);
            denise.setGamePort2(port2);
            paula.setGamePort1(port1);
            paula.setGamePort2(port2);

            agnus = ecs
                    ? new EcsAgnus(cpu, gary, chipRam, slowRam, kickstart, paula, denise, ciaA, ciaB, videoMode)
                    : new OcsAgnus(cpu, gary, chipRam, slowRam, kickstart, paula, denise, ciaA, ciaB, videoMode);

            // Fast RAM (espansione Zorro II con AutoConfig). Assente di default (0):
            // la prima scheda di RAM Zorro II viene mappata dal Kickstart tipicamente
            // a $200000 e aggiunta alla memoria di sistema, rendendola disponibile a
            // programmi e WHDLoad. Va collegata PRIMA del reset della CPU.
            agnus.setFastRam(new FastRam(config.fastRamKb() * 1024));

            // Immediate blit: completa ogni blit istantaneamente (non cycle-exact).
            // Solo da amiga.properties (blitter.immediate), default false, non da F12.
            agnus.getBlitter().setImmediateMode(config.blitterImmediate());

            // Hard disk (master/slave): ogni unità abilitata con un .hdf valido diventa
            // un controller Zorro II con boot ROM. La numerazione delle partizioni DH
            // è progressiva fra le unità. Va collegato PRIMA del reset della CPU.
            setupHardDisks(agnus);

            virtualCRT = new VirtualCRT(agnus, denise, display);

            Keyboard keyboard = new Keyboard(ciaA);
            agnus.setKeyboard(keyboard);

            // Adapter di input (ricreati a ogni avvio: legano i nuovi port/keyboard).
            // La porta di destinazione e lo stato abilitato di mouse/joystick-tastiera
            // sono impostati subito dopo da applyPortAssignments (idem per il joystick reale).
            joystickAdapter = new AmigaJoystickKeyAdapter();
            keyAdapter = new AmigaKeyAdapter(keyboard, frame);
            mouseAdapter = new AmigaMouseAdapter(port1, frame);
            // I listener vanno sul componente di input (il frame in software, il canvas GL
            // heavyweight in OpenGL: i suoi eventi non risalgono al frame). Gli adapter
            // continuano a ricevere il frame per fuoco/cursore/ricentraggio del mouse.
            if (inputComponent != null) {
                // Il joystick adapter deve venire prima: consuma i tasti mappati sulle
                // direzioni e sul fuoco, che non vanno inoltrati alla tastiera Amiga.
                inputComponent.addKeyListener(joystickAdapter);
                inputComponent.addKeyListener(keyAdapter);
                inputComponent.addMouseListener(mouseAdapter);
                inputComponent.addMouseMotionListener(mouseAdapter);
                inputComponent.setFocusTraversalKeysEnabled(false);
                inputComponent.requestFocusInWindow();
            }

            // Assegna le periferiche host alle due game port (mouse / joystick reale /
            // joystick da tastiera / vuota) secondo la configurazione.
            applyPortAssignments();

            // Drive (DF0..DF3): collegamento e disco da inserire all'avvio. Se DF0 ha
            // uno zip multi-disco, i dischi successivi popolano i drive liberi.
            syncZipDiskSet(null);
            FloppyDrive df0 = agnus.getDf0();
            FloppyDrive[] dfs = { df0, agnus.getDf1(), agnus.getDf2(), agnus.getDf3() };
            for (int i = 0; i < dfs.length; i++) {
                dfs[i].setConnected(config.driveEnabled(i));
                String diskPath = config.driveDisk(i);
                if (config.driveEnabled(i) && diskPath != null) {
                    try {
                        dfs[i].loadAdf(diskPath, config.driveReadOnly(i));
                    } catch (IOException ioe) {
                        System.err.println("[CONFIG] Impossibile caricare il disco DF" + i
                                + " (" + diskPath + "): " + ioe.getMessage());
                    }
                }
            }
            display.setDrives(dfs[0], dfs[1], dfs[2], dfs[3]);

            // LED di stato + suoni estetici del floppy (entrambi da configurazione).
            // Il LED HD riflette gli hard disk presenti (WHDLoad e/o master/slave, in ordine).
            display.setStatusLedSource(ciaA::isPowerLedOn);
            display.setHdLedSources(hdLedSources.toArray(new java.util.function.BooleanSupplier[0]));
            display.setStatusLedsEnabled(config.ledsVisible());

            // Overlay diagnostici opzionali (solo da amiga.properties, non nel popup F12).
            display.setFpsOverlayEnabled(config.fpsOverlayEnabled());
            display.setFrameCounterOverlayEnabled(config.frameCounterOverlayEnabled());
            applyFloppySounds(dfs);

            // 4. Master clock + reset + avvio.
            clock = new MasterClock(agnus, videoMode, virtualCRT);
            cpu.reset(); // il reset asserta RESET: Agnus resetta a cascata CIA e floppy.
            clock.start();

            if (VERBOSE) {
                System.out.println("[INIT] Macchina avviata.");
            }
        } catch (IOException e) {
            error(Messages.t("error.boot", e.getMessage()));
        }
    }

    /**
     * Costruisce i controller hard disk dalle unità configurate (master/slave) e li
     * collega ad {@link AbstractAgnus}. Ogni file {@code .hdf} valido produce un controller;
     * le partizioni sono numerate progressivamente (DH0, DH1, …) fra le unità.
     */
    private void setupHardDisks(AbstractAgnus agnus) {
        hardFiles.clear();
        hdLedSources.clear();
        java.util.List<HdController> controllers = new java.util.ArrayList<>();
        int partitionBase = 0;

        // WHDLoad: l'archivio .lha viene estratto e impacchettato in un hard disk FFS
        // avviabile (vedi WhdLoadManager), montato come PRIMA unità così da diventare
        // il volume di boot (DH0) ed eseguire automaticamente lo slave WHDLoad.
        if (config.whdloadEnabled() && config.whdloadFile() != null) {
            try {
                WhdLoadManager whd = new WhdLoadManager();
                // WHDLoad.prefs generata dalla sezione whdload.prefs.* di amiga.properties.
                whd.withPrefs(config.whdloadPrefsContent());
                // Fornisci a WHDLoad i ROM Kickstart presenti accanto a quello in uso
                // (molti slave OCS richiedono p.es. KS 1.3): associati per CRC32.
                String ksPath = config.kickstartPath();
                if (!ksPath.isEmpty()) {
                    java.nio.file.Path ksParent = java.nio.file.Paths.get(ksPath).getParent();
                    if (ksParent != null) whd.addKickstartDirectory(ksParent);
                }
                java.nio.file.Path hdf = whd.prepare(config.whdloadFile());
                HardFile hf = HardFile.load(hdf.toString(), false);
                hardFiles.add(hf);
                HdController hd = new HdController(controllers.size(), hf, partitionBase,
                        () -> { FloppyDrive d = agnus.getDf0(); return d != null && d.isDiskInserted(); });
                controllers.add(hd);
                partitionBase += hf.numPartitions();
                System.out.println("[WHDLOAD] " + config.whdloadFile() + " -> " + hdf
                        + " (" + hf.cylinders() + "/" + hf.heads() + "/" + hf.sectors() + " C/H/S)");

                // Volume persistente dei salvataggi WHDLoad (Saves: → WHDSaves:),
                // montato come unità NON avviabile così non interferisce col boot.
                java.nio.file.Path savesHdf = whd.prepareSaves();
                HardFile shf = HardFile.load(savesHdf.toString(), false);
                shf.setBootable(false);
                hardFiles.add(shf);
                HdController savesHd = new HdController(controllers.size(), shf, partitionBase,
                        () -> { FloppyDrive d = agnus.getDf0(); return d != null && d.isDiskInserted(); });
                controllers.add(savesHd);
                partitionBase += shf.numPartitions();

                // Un solo LED HD per WHDLoad: boot + salvataggi sono la stessa unità logica.
                hdLedSources.add(() -> hd.ledActive() || savesHd.ledActive());
            } catch (IOException ioe) {
                System.err.println("[WHDLOAD] Avvio WHDLoad fallito (" + config.whdloadFile()
                        + "): " + ioe.getMessage());
            }
        }

        for (int unit = 0; unit < AmigaConfig.HD_UNITS.length; unit++) {
            if (!config.hdEnabled(unit)) continue;
            String diskPath = config.hdDisk(unit);
            if (diskPath == null) continue;
            try {
                HardFile hf = HardFile.load(diskPath, config.hdReadOnly(unit));
                hardFiles.add(hf);
                HdController hd = new HdController(controllers.size(), hf, partitionBase,
                        () -> { FloppyDrive d = agnus.getDf0(); return d != null && d.isDiskInserted(); });
                controllers.add(hd);
                hdLedSources.add(hd::ledActive); // master (unit 0) prima, slave (unit 1) dopo
                partitionBase += hf.numPartitions();
                System.out.println("[HDF] " + AmigaConfig.HD_UNITS[unit] + ": montato " + diskPath
                        + " (" + hf.numPartitions() + " part., "
                        + hf.cylinders() + "/" + hf.heads() + "/" + hf.sectors() + " C/H/S"
                        + (hf.readOnly() ? ", sola lettura" : "") + ")");
            } catch (IOException ioe) {
                System.err.println("[HDF] Impossibile montare l'hard disk " + AmigaConfig.HD_UNITS[unit]
                        + " (" + diskPath + "): " + ioe.getMessage());
            }
        }
        agnus.setHdControllers(controllers);
    }

    /** Ferma la macchina corrente: clock, audio, render e listener di input. */
    private void shutdownMachine() {
        if (clock != null) {
            clock.stop();
            clock = null;
        }
        if (virtualCRT != null) {
            virtualCRT.stop();
            virtualCRT = null;
        }
        if (paula != null) {
            paula.stopAudioOutput();
            paula = null;
        }
        if (agnus != null) {
            agnus.getDf0().setFloppySound(null);
            agnus.getDf1().setFloppySound(null);
            agnus.getDf2().setFloppySound(null);
            agnus.getDf3().setFloppySound(null);
        }
        closeFloppySounds();
        // Salva su disco le eventuali scritture agli hard disk montati.
        for (HardFile hf : hardFiles) {
            hf.flush();
        }
        hardFiles.clear();
        if (inputComponent != null) {
            if (mouseAdapter != null) {
                inputComponent.removeMouseListener(mouseAdapter);
                inputComponent.removeMouseMotionListener(mouseAdapter);
                mouseAdapter.dispose(); // rimuove anche il window focus listener
            }
            if (keyAdapter != null) inputComponent.removeKeyListener(keyAdapter);
            if (joystickAdapter != null) inputComponent.removeKeyListener(joystickAdapter);
        }
        if (keyAdapter != null) keyAdapter.dispose(); // ferma il poller shift
        // Il servizio joystick reale (GLFW) resta vivo tra un boot e l'altro (idle):
        // basta staccare le porte, che verranno ricollegate al prossimo boot.
        joystickService.setPorts(java.util.List.of());
        mouseAdapter = null;
        keyAdapter = null;
        joystickAdapter = null;
        gamePort1 = null;
        gamePort2 = null;
        cpu = null;
        agnus = null;
    }

    // =========================================================================
    // Validazione / utilità
    // =========================================================================

    /** Verifica che il Kickstart configurato esista; in caso negativo avvisa l'utente. */
    private boolean validateRom(AmigaConfig cfg) {
        String path = cfg.kickstartPath();
        if (path.isEmpty()) {
            error(Messages.t("error.rom.missing", AmigaConfig.FILE_NAME));
            return false;
        }
        if (!new File(path).isFile()) {
            error(Messages.t("error.rom.notfound", path));
            return false;
        }
        return true;
    }

    private void error(String message) {
        System.err.println("[CONFIG] " + message.replace('\n', ' '));
        if (frame != null) {
            JOptionPane.showMessageDialog(frame, message, Messages.t("error.title"), JOptionPane.ERROR_MESSAGE);
        }
    }
}
