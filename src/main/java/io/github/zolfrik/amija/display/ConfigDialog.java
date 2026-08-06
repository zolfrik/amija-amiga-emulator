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

package io.github.zolfrik.amija.display;

import static io.github.zolfrik.amija.display.UiScaling.derive;
import static io.github.zolfrik.amija.display.UiScaling.dim;
import static io.github.zolfrik.amija.display.UiScaling.pad;
import static io.github.zolfrik.amija.display.UiScaling.px;

import io.github.zolfrik.amija.assets.AssetCatalog;
import io.github.zolfrik.amija.assets.AssetPackage;
import io.github.zolfrik.amija.common.AppHome;
import io.github.zolfrik.amija.common.Chipset;
import io.github.zolfrik.amija.common.VideoMode;
import io.github.zolfrik.amija.common.Version;
import io.github.zolfrik.amija.config.AmigaConfig;
import io.github.zolfrik.amija.display.render.RendererType;
import io.github.zolfrik.amija.i18n.Messages;
import io.github.zolfrik.amija.input.JoystickKeyMap;
import io.github.zolfrik.amija.input.PortDevice;
import io.github.zolfrik.amija.state.SaveStateManager;
import io.github.zolfrik.amija.whdload.WhdLoadManager;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import javax.swing.SpinnerNumberModel;
import javax.swing.ToolTipManager;
import javax.swing.border.TitledBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;

/**
 * Popup modale (F12) per la modifica di {@code amiga.properties}.
 *
 * <p>Espone tutti i valori configurabili con i controlli adatti: caselle di testo
 * con browse ({@code ...}) per i percorsi assoluti, tendine per Chip/Slow RAM,
 * check per i booleani, uno spinner per il volume dei suoni del floppy. I contenuti
 * sono organizzati in sezioni e racchiusi in un'area scorrevole verticalmente.
 *
 * <p>Restituisce l'azione scelta dall'utente ({@link Result}); su {@link Result#OK}
 * i valori modificati vengono applicati direttamente all'{@link AmigaConfig} passato.
 * Il chiamante salva la configurazione e applica a caldo le sole modifiche ai floppy
 * (inserimento/sostituzione/espulsione) senza riavviare la macchina; gli altri
 * settaggi (ROM, RAM, lingua) hanno effetto solo al successivo riavvio.
 */
public final class ConfigDialog {

    /** Esito della finestra: l'azione che il chiamante deve eseguire. */
    public enum Result {
        CANCEL, OK, RESTART, SOFT_RESET, QUIT,
        /**
         * Lo stato da caricare pretende una macchina diversa da quella accesa. Il popup
         * si è chiuso <b>senza</b> applicare le modifiche in corso: quello che conta ora
         * è la configurazione conservata dentro lo slot, che il chiamante ripristina
         * prima di ricostruire la macchina e ritentare il caricamento.
         */
        STATE_RESTORE
    }

    /**
     * Esito di "Load State" visto dal popup.
     *
     * @param message      testo da mostrare all'utente; {@code null} se non c'è ancora
     *                     niente da dire perché l'operazione prosegue fuori di qui
     * @param needsRestart {@code true} se il caricamento richiede di ricostruire la
     *                     macchina: il popup si chiude con {@link Result#STATE_RESTORE}
     *                     e il resto lo fa il chiamante
     */
    public record LoadStateOutcome(String message, boolean needsRestart) {

        /** Operazione conclusa qui: c'è solo da riferire com'è andata. */
        public static LoadStateOutcome done(String message) {
            return new LoadStateOutcome(message, false);
        }

        /** Operazione da riprendere fuori dal popup, dopo aver ricostruito la macchina. */
        public static LoadStateOutcome restart() {
            return new LoadStateOutcome(null, true);
        }
    }

    /**
     * Quanto resta a schermo un tooltip prima di sparire da solo: il doppio dei 4 s con
     * cui parte Swing. Qui i tooltip non sono etichette da riconoscere ma spiegazioni da
     * leggere, e quattro secondi non bastano per una frase intera.
     *
     * <p>È un valore assoluto e non "il default moltiplicato per due" di proposito:
     * {@link ToolTipManager} è un singleton di JVM, e raddoppiare il valore corrente a
     * ogni apertura del popup lo farebbe crescere senza fine.
     */
    private static final int TOOLTIP_DISMISS_MS = 8000;

    // Metodo e non costante: la misura dipende dalla scala dell'interfaccia, che va letta
    // quando il componente si costruisce, non quando la classe si carica.
    private static Dimension driveToolButtonSize() {
        return dim(34, 26);
    }

    private static final Icon LOCK_CLOSED_ICON = new LockIcon(true);
    private static final Icon LOCK_OPEN_ICON = new LockIcon(false);

    /** Sorgenti di file del popup; ognuna ricorda la propria ultima cartella. */
    private enum BrowseKind { FLOPPY, ROM, HDF, WHDLOAD, MEDIA }

    // Valori sensati/possibili per le tendine della memoria (in KB).
    private static final Integer[] CHIP_RAM_OPTIONS = { 256, 512, 1024, 2048 };
    private static final Integer[] SLOW_RAM_OPTIONS = { 0, 512, 1024, 1536, 1792 };
    // Fast RAM: potenze di due da 512 KB a 8 MB (vincolo AutoConfig Zorro II), 0=nessuna.
    private static final Integer[] FAST_RAM_OPTIONS = { 0, 512, 1024, 2048, 4096, 8192 };

    // Palette sobria/professionale.
    private static final Color BG        = new Color(0x2B, 0x2D, 0x31);
    private static final Color PANEL_BG  = new Color(0x33, 0x36, 0x3B);
    private static final Color FG        = new Color(0xE6, 0xE6, 0xE8);
    private static final Color ACCENT    = new Color(0x4D, 0x9D, 0xE0);

    private ConfigDialog() {}

    /**
     * Mostra il dialog in modalità modale e ritorna l'azione scelta. Se l'esito è
     * {@link Result#OK} le modifiche sono già state scritte in {@code config}.
     *
     * @param onScreenshot        azione del pulsante "Take Screenshot"; ritorna il file
     *                            salvato (o {@code null} in caso di errore).
     * @param shaderParamUpdater  callback {@code (nome, valore)} per applicare a caldo i
     *                            parametri dello shader OpenGL (popup "...").
     * @param onSaveState         salva lo slot e ritorna il messaggio di esito.
     * @param onLoadState         carica lo slot; vedi {@link LoadStateOutcome}.
     */
    public static Result show(JFrame parent, AmigaConfig config,
            java.util.function.Supplier<java.io.File> onScreenshot,
            java.util.function.BiConsumer<String, Float> shaderParamUpdater,
            java.util.function.Supplier<String> onSaveState,
            java.util.function.Supplier<LoadStateOutcome> onLoadState) {
        final Result[] result = { Result.CANCEL };

        // Prima di costruire qualsiasi cosa: i tooltip di questo popup vanno letti, non
        // solo intravisti. Il ToolTipManager è condiviso da tutta la JVM, quindi vale
        // anche per le finestre figlie (tasti joystick, parametri shader, download).
        ToolTipManager.sharedInstance().setDismissDelay(TOOLTIP_DISMISS_MS);

        String title = Messages.t("dialog.title") + " - " + Version.version();
        JDialog dialog = new JDialog(parent, title, true);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        // -------------------------------------------------------------------
        // Sezione: Sistema
        // -------------------------------------------------------------------
        JPanel system = section(Messages.t("section.system"));

        // "Expert mode" (prima voce, spento di default): decide quanto popup l'utente si
        // trova davanti. Spento, la macchina si configura da sé in base al supporto
        // scelto — restano il Kickstart, sdoppiato nelle due ROM fra cui l'automatismo
        // sceglie, e la lista unica dei drive; acceso, compare tutto e decide l'utente.
        // Il cablaggio è più in basso (syncAuto), dopo le sezioni da cui dipende.
        JCheckBox expertCheck = check(Messages.t("label.expert"), !config.autoEnabled());
        tip("label.expert", expertCheck);
        addRow(system, 0, expertCheck, null, null);
        java.util.function.BooleanSupplier simpleMode = () -> !expertCheck.isSelected();

        // Kickstart scelto a mano: visibile solo in Expert.
        JLabel kickLabel = label(Messages.t("label.kickstart"));
        JTextField kickField = textField(config.storedKickstartPath());
        JButton kickBrowse = browseButton();
        kickBrowse.addActionListener(e -> browse(dialog, config, BrowseKind.ROM, kickField,
                Messages.t("chooser.rom"), "rom"));
        tip("label.kickstart", kickLabel, kickField);
        addRow(system, 1, kickLabel, kickField, kickBrowse);

        // Le due ROM fra cui sceglie la configurazione automatica: visibili solo con
        // Expert spento, cioè quando quella scelta la fa l'emulatore. Hanno una voce
        // propria in amiga.properties ma condividono con il campo qui sopra l'ultima
        // cartella visitata (BrowseKind.ROM): i Kickstart stanno tutti insieme.
        //
        // Se nessuna delle due è impostata siamo davanti a una configurazione più vecchia
        // della modalità Auto: la prima parte allora dal Kickstart scelto a mano, che è
        // quello con cui la macchina sta già girando. Senza, all'utente sembrerebbe di
        // aver perso la propria ROM.
        JLabel ks13Label = label(Messages.t("label.kickstart13"));
        JTextField ks13Field = textField(
                config.autoKickstart13().isEmpty() && config.autoKickstart31().isEmpty()
                        ? config.storedKickstartPath() : config.autoKickstart13());
        JButton ks13Browse = browseButton();
        ks13Browse.addActionListener(e -> browse(dialog, config, BrowseKind.ROM, ks13Field,
                Messages.t("chooser.rom"), "rom"));
        tip("label.kickstart13", ks13Label, ks13Field);
        addRow(system, 2, ks13Label, ks13Field, ks13Browse);

        JLabel ks31Label = label(Messages.t("label.kickstart31"));
        JTextField ks31Field = textField(config.autoKickstart31());
        JButton ks31Browse = browseButton();
        ks31Browse.addActionListener(e -> browse(dialog, config, BrowseKind.ROM, ks31Field,
                Messages.t("chooser.rom"), "rom"));
        tip("label.kickstart31", ks31Label, ks31Field);
        addRow(system, 3, ks31Label, ks31Field, ks31Browse);

        // Le cinque voci qui sotto descrivono l'hardware della macchina. Con Expert
        // spento le decide l'emulatore e le righe spariscono del tutto: chi non conosce
        // l'Amiga non ha modo di scegliere fra OCS ed ECS, e vedersele davanti in sola
        // lettura non lo aiuta. Riappaiono, modificabili, appena Expert si accende.
        JComboBox<Integer> chipCombo = kbCombo(CHIP_RAM_OPTIONS, config.chipRamKb());
        JLabel chipLabel = addRow(system, 4, "label.chipram", chipCombo, null);

        JComboBox<Integer> slowCombo = kbCombo(SLOW_RAM_OPTIONS, config.slowRamKb());
        JLabel slowLabel = addRow(system, 5, "label.slowram", slowCombo, null);

        JComboBox<Integer> fastCombo = kbCombo(FAST_RAM_OPTIONS, config.fastRamKb());
        JLabel fastLabel = addRow(system, 6, "label.fastram", fastCombo, null);

        // Modalita' video PAL/NTSC: il cambio richiede il riavvio dell'emulatore
        // (gestito da Amija: un OK con modalita' diversa forza un cold restart).
        JComboBox<VideoMode> videoModeCombo = new JComboBox<>(VideoMode.values());
        videoModeCombo.setSelectedItem(config.videoMode());
        videoModeCombo.setMaximumSize(dim(160, 26));
        JLabel videoLabel = addRow(system, 7, "label.videomode", videoModeCombo, null);

        // Chipset OCS/ECS: come la modalita' video, il cambio richiede un cold restart.
        JComboBox<Chipset> chipsetCombo = new JComboBox<>(Chipset.values());
        chipsetCombo.setSelectedItem(config.chipset());
        chipsetCombo.setMaximumSize(dim(160, 26));
        JLabel chipsetLabel = addRow(system, 8, "label.chipset", chipsetCombo, null);

        // -------------------------------------------------------------------
        // Sezione: Game ports
        // -------------------------------------------------------------------
        // Periferica collegata a ciascuna game port (mouse / joystick reale / joystick
        // da tastiera / vuota). Applicata a caldo: nessun riavvio richiesto.
        // Con "Joystick (via tastiera)" compare accanto alla tendina il pulsante "..."
        // che apre la configurazione dei tasti di quella porta. Le due mappe sono
        // modificate in copia e scritte nella configurazione solo alla conferma.
        JPanel ports = section(Messages.t("section.gameports"));
        JoystickKeyMap[] joyKeyMaps = { config.joystickKeyMap(0), config.joystickKeyMap(1) };

        // Il pulsante va nella colonna di coda della sezione, la stessa in cui le altre
        // sezioni mettono il loro "...": resta quindi allineato a quelli e la tendina
        // mantiene la propria larghezza sia che il pulsante ci sia sia che non ci sia.
        JComboBox<PortDevice> port0Combo = deviceCombo(config.portDevice(0));
        JButton port0Keys = joystickKeysButton();
        addRow(ports, 0, "label.port0", port0Combo, port0Keys);

        JComboBox<PortDevice> port1Combo = deviceCombo(config.portDevice(1));
        JButton port1Keys = joystickKeysButton();
        addRow(ports, 1, "label.port1", port1Combo, port1Keys);

        port0Keys.addActionListener(e -> JoystickKeysDialog.show(dialog, 0, joyKeyMaps));
        port1Keys.addActionListener(e -> JoystickKeysDialog.show(dialog, 1, joyKeyMaps));

        // Il pulsante dei tasti appare solo per la porta impostata sul joystick da tastiera.
        Runnable syncJoystickKeyButtons = () -> {
            setJoystickKeysButtonVisible(port0Keys,
                    port0Combo.getSelectedItem() == PortDevice.JOYSTICK_KEYBOARD);
            setJoystickKeysButtonVisible(port1Keys,
                    port1Combo.getSelectedItem() == PortDevice.JOYSTICK_KEYBOARD);
        };
        port0Combo.addActionListener(e -> syncJoystickKeyButtons.run());
        port1Combo.addActionListener(e -> syncJoystickKeyButtons.run());
        syncJoystickKeyButtons.run();

        // -------------------------------------------------------------------
        // Sezione: Floppy drive
        // -------------------------------------------------------------------
        JPanel drives = section(Messages.t("section.drives"));
        JCheckBox[] driveEnabled = new JCheckBox[4];
        JTextField[] driveDisk   = new JTextField[4];
        JToggleButton[] driveReadOnly = new JToggleButton[4];
        // Le funzioni che riallineano i controlli di una riga al suo stato. Servono anche
        // dall'esterno: quando la lista unica della modalità Auto riversa qui i propri
        // supporti, setSelected/setText non emettono eventi e nessuno le richiamerebbe.
        java.util.List<Runnable> classicSyncs = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            JCheckBox enabled = check(Messages.t("drive.connected", i), config.driveEnabled(i));
            JTextField disk = textField(config.driveDisk(i) == null ? "" : config.driveDisk(i));
            JButton diskBrowse = browseButton();
            JToggleButton diskReadOnly = readOnlyButton(config.driveReadOnly(i));
            JButton diskEject = ejectButton();

            // Il percorso del disco è modificabile solo se il drive è collegato; il
            // pulsante di espulsione è attivo solo se c'è effettivamente un disco.
            Runnable sync = () -> {
                boolean on = enabled.isSelected();
                disk.setEnabled(on);
                diskBrowse.setEnabled(on);
                diskReadOnly.setEnabled(on);
                diskEject.setEnabled(on && !disk.getText().trim().isEmpty());
            };
            diskBrowse.addActionListener(e -> {
                browse(dialog, config, BrowseKind.FLOPPY, disk, Messages.t("chooser.disk"), "adf", "ipf", "zip");
                if (isZipPath(disk.getText())) diskReadOnly.setSelected(true);
                sync.run();
            });
            // Espulsione: svuota il campo. La differenza con lo stato corrente verrà
            // applicata al disco reale alla conferma con OK (eject vero e proprio).
            diskEject.addActionListener(e -> {
                disk.setText("");
                disk.setCaretPosition(0);
                sync.run();
            });
            enabled.addActionListener(e -> sync.run());
            classicSyncs.add(sync);
            sync.run();

            // "..." e "Eject" affiancati nella colonna di coda (eject a destra).
            JPanel trailing = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
            trailing.setOpaque(false);
            trailing.add(diskBrowse);
            trailing.add(diskReadOnly);
            trailing.add(diskEject);

            driveEnabled[idx] = enabled;
            driveDisk[idx] = disk;
            driveReadOnly[idx] = diskReadOnly;
            tip("drive.connected", enabled, disk);
            addRow(drives, i, enabled, disk, trailing);
        }

        // -------------------------------------------------------------------
        // Sezione: Hard disk (master / slave)
        // -------------------------------------------------------------------
        JPanel hds = section(Messages.t("section.harddisks"));
        int hdCount = AmigaConfig.HD_UNITS.length;
        JCheckBox[] hdEnabled = new JCheckBox[hdCount];
        JTextField[] hdDisk   = new JTextField[hdCount];
        JToggleButton[] hdReadOnly = new JToggleButton[hdCount];
        for (int i = 0; i < hdCount; i++) {
            JCheckBox enabled = check(Messages.t("hd." + AmigaConfig.HD_UNITS[i]), config.hdEnabled(i));
            JTextField disk = textField(config.hdDisk(i) == null ? "" : config.hdDisk(i));
            JButton diskBrowse = browseButton();
            JToggleButton diskReadOnly = readOnlyButton(config.hdReadOnly(i));
            JButton diskEject = ejectButton();

            Runnable sync = () -> {
                boolean on = enabled.isSelected();
                disk.setEnabled(on);
                diskBrowse.setEnabled(on);
                diskReadOnly.setEnabled(on);
                diskEject.setEnabled(on && !disk.getText().trim().isEmpty());
            };
            diskBrowse.addActionListener(e -> {
                browse(dialog, config, BrowseKind.HDF, disk, Messages.t("chooser.hdf"), "hdf");
                sync.run();
            });
            diskEject.addActionListener(e -> {
                disk.setText("");
                disk.setCaretPosition(0);
                sync.run();
            });
            enabled.addActionListener(e -> sync.run());
            classicSyncs.add(sync);
            sync.run();

            JPanel trailing = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
            trailing.setOpaque(false);
            trailing.add(diskBrowse);
            trailing.add(diskReadOnly);
            trailing.add(diskEject);

            hdEnabled[i] = enabled;
            hdDisk[i] = disk;
            hdReadOnly[i] = diskReadOnly;
            tip("hd." + AmigaConfig.HD_UNITS[i], enabled, disk);
            addRow(hds, i, enabled, disk, trailing);
        }

        // -------------------------------------------------------------------
        // Sezione: WHDLoad (archivio .lha avviato automaticamente)
        // -------------------------------------------------------------------
        JPanel whd = section(Messages.t("section.whdload"));
        JCheckBox whdEnabled = check(Messages.t("whdload.enabled"), config.whdloadEnabled());
        JTextField whdFile = textField(config.whdloadFile() == null ? "" : config.whdloadFile());
        JButton whdBrowse = browseButton();
        JButton whdEject = ejectButton();

        Runnable whdSync = () -> {
            boolean on = whdEnabled.isSelected();
            whdFile.setEnabled(on);
            whdBrowse.setEnabled(on);
            whdEject.setEnabled(on && !whdFile.getText().trim().isEmpty());
        };
        whdBrowse.addActionListener(e -> {
            browse(dialog, config, BrowseKind.WHDLOAD, whdFile, Messages.t("chooser.whdload"), "lha", "lzh");
            whdSync.run();
            // Punto di bisogno: l'archivio scelto non partirà senza i file di sistema
            // WHDLoad, che AmiJa non può ridistribuire. È qui che la domanda ha un
            // senso — non al primo avvio, dove la porremmo anche a chi non li userà mai.
            if (!whdFile.getText().trim().isEmpty() && !new WhdLoadManager().isAvailable()) {
                int answer = JOptionPane.showConfirmDialog(dialog,
                        Messages.t("assets.whdload.offer"), Messages.t("assets.title"),
                        JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
                if (answer == JOptionPane.YES_OPTION) {
                    AssetDownloadDialog.show(dialog, config);
                }
            }
        });
        whdEject.addActionListener(e -> {
            whdFile.setText("");
            whdFile.setCaretPosition(0);
            whdSync.run();
        });
        whdEnabled.addActionListener(e -> whdSync.run());
        classicSyncs.add(whdSync);
        whdSync.run();

        JPanel whdTrailing = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
        whdTrailing.setOpaque(false);
        whdTrailing.add(whdBrowse);
        whdTrailing.add(whdEject);
        tip("whdload.enabled", whdEnabled, whdFile);
        addRow(whd, 0, whdEnabled, whdFile, whdTrailing);

        // -------------------------------------------------------------------
        // Sezione: Drives — la lista unica della modalità Auto, che prende il posto
        // delle tre sezioni qui sopra. Vedi DriveList.
        // -------------------------------------------------------------------
        JPanel media = section(Messages.t("section.media"));
        DriveList driveList = new DriveList(dialog, config, media);
        driveList.seed(MediaState.fromConfig(config));

        // I supporti come li vedono le tre sezioni classiche, e come rimetterceli.
        // Sono le due direzioni dello scambio con la lista unica.
        java.util.function.Supplier<MediaState> classicMedia = () -> {
            MediaState s = new MediaState();
            for (int i = 0; i < 4; i++) {
                s.floppyDisk[i] = driveDisk[i].getText();
                s.floppyConnected[i] = driveEnabled[i].isSelected();
                s.floppyReadOnly[i] = driveReadOnly[i].isSelected();
            }
            for (int u = 0; u < hdEnabled.length; u++) {
                s.hdDisk[u] = hdDisk[u].getText();
                s.hdConnected[u] = hdEnabled[u].isSelected();
                s.hdReadOnly[u] = hdReadOnly[u].isSelected();
            }
            s.whdloadEnabled = whdEnabled.isSelected();
            s.whdloadFile = whdFile.getText();
            return s;
        };
        java.util.function.Consumer<MediaState> applyToClassic = s -> {
            for (int i = 0; i < 4; i++) {
                driveDisk[i].setText(s.floppyDisk[i] == null ? "" : s.floppyDisk[i]);
                driveDisk[i].setCaretPosition(0);
                driveEnabled[i].setSelected(s.floppyConnected[i]);
                driveReadOnly[i].setSelected(s.floppyReadOnly[i]);
            }
            for (int u = 0; u < hdEnabled.length; u++) {
                hdDisk[u].setText(s.hdDisk[u] == null ? "" : s.hdDisk[u]);
                hdDisk[u].setCaretPosition(0);
                hdEnabled[u].setSelected(s.hdConnected[u]);
                hdReadOnly[u].setSelected(s.hdReadOnly[u]);
            }
            whdEnabled.setSelected(s.whdloadEnabled);
            whdFile.setText(s.whdloadFile == null ? "" : s.whdloadFile);
            whdFile.setCaretPosition(0);
            // setSelected/setText non emettono eventi: i controlli di riga vanno
            // riallineati a mano, o resterebbero abilitati come prima.
            classicSyncs.forEach(Runnable::run);
        };

        // -------------------------------------------------------------------
        // Cablaggio di "Expert mode" (prima voce della sezione Sistema).
        //
        // Sta qui, e non lassù, perché deve conoscere entrambe le viste dei supporti:
        // le tre sezioni classiche e la lista unica. Accendere o spegnere Expert scambia
        // le viste passandosi quello che l'utente ha scelto, così un gioco appena messo
        // nella lista non sparisce cambiando idea sulla modalità.
        // -------------------------------------------------------------------
        Runnable syncAuto = () -> {
            boolean simple = simpleMode.getAsBoolean();
            setRowVisible(!simple, kickLabel, kickField, kickBrowse);
            setRowVisible(simple, ks13Label, ks13Field, ks13Browse);
            setRowVisible(simple, ks31Label, ks31Field, ks31Browse);
            // Fuori da Expert l'hardware lo decide l'emulatore: le righe non servono.
            setRowVisible(!simple, chipLabel, chipCombo);
            setRowVisible(!simple, slowLabel, slowCombo);
            setRowVisible(!simple, fastLabel, fastCombo);
            setRowVisible(!simple, videoLabel, videoModeCombo);
            setRowVisible(!simple, chipsetLabel, chipsetCombo);
            // Una lista sola invece delle tre sezioni per tipo di supporto.
            media.setVisible(simple);
            drives.setVisible(!simple);
            hds.setVisible(!simple);
            whd.setVisible(!simple);
            Container box = media.getParent();
            if (box != null) {
                box.revalidate();
                box.repaint();
            }
        };

        expertCheck.addActionListener(e -> {
            if (simpleMode.getAsBoolean()) {
                driveList.seed(classicMedia.get());
            } else {
                // Accendendo Expert tornano in vigore le impostazioni di sistema scelte
                // a mano, rimaste intatte nel file per tutto il tempo in cui la macchina
                // si configurava da sé; i supporti invece sono quelli della lista.
                applyToClassic.accept(driveList.toMediaState());
                selectKb(chipCombo, config.storedChipRamKb());
                selectKb(slowCombo, config.storedSlowRamKb());
                selectKb(fastCombo, config.storedFastRamKb());
                videoModeCombo.setSelectedItem(config.storedVideoMode());
                chipsetCombo.setSelectedItem(config.storedChipset());
            }
            syncAuto.run();
        });

        // -------------------------------------------------------------------
        // Sezione: file opzionali di terze parti (assistente di download).
        // Materiale che AmiJa non può ridistribuire e che l'utente può farsi
        // scaricare dai siti ufficiali. Vedi THIRD-PARTY-NOTICES.md §5.
        //
        // Se sono già installati tutti, la sezione non ha più nulla da proporre:
        // si omette del tutto (assets == null) invece di offrire un assistente che
        // mostrerebbe solo "Già installato". Lo stato è letto a ogni apertura del
        // popup, quindi la sezione sparisce da sé dopo un download riuscito.
        // -------------------------------------------------------------------
        JPanel assets = null;
        if (!AssetCatalog.packages(config).stream().allMatch(AssetPackage::installed)) {
            assets = section(Messages.t("section.assets"));
            JButton assetsOpen = button(Messages.t("assets.open"), false);
            assetsOpen.addActionListener(e -> AssetDownloadDialog.show(dialog, config));
            addRow(assets, 0, "assets.label", null, assetsOpen);
            tip("assets.label", assetsOpen);
        }

        // -------------------------------------------------------------------
        // Sezione: Audio / Video
        // -------------------------------------------------------------------
        JPanel av = section(Messages.t("section.av"));

        // Rendering (prima voce): software (Java2D) o OpenGL (LWJGL, predisposto agli shader).
        JComboBox<RendererType> rendererCombo = new JComboBox<>(RendererType.values());
        rendererCombo.setSelectedItem(config.renderer());
        rendererCombo.setMaximumSize(dim(220, 26));
        rendererCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof RendererType type) {
                    setText(Messages.t("renderer." + type.configValue()));
                }
                return this;
            }
        });
        addRow(av, 0, "label.renderer", rendererCombo, null);

        // Shader RetroArch: popolato coi .glslp nella cartella "shaders" (+ "Nessuno").
        // Modificabile solo col renderer OpenGL.
        JComboBox<String> shaderCombo = new JComboBox<>(scanShaderNames());
        String curShader = config.shader();
        boolean shaderPresent = false;
        for (int i = 0; i < shaderCombo.getItemCount(); i++) {
            if (shaderCombo.getItemAt(i).equals(curShader)) { shaderPresent = true; break; }
        }
        shaderCombo.setSelectedItem(shaderPresent ? curShader : "none");
        shaderCombo.setMaximumSize(dim(220, 26));
        shaderCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if ("none".equals(value)) setText(Messages.t("shader.none"));
                return this;
            }
        });
        // Pulsante "..." per i parametri dello shader selezionato.
        JButton shaderParamsBtn = button("...", false);
        styleDriveToolButton(shaderParamsBtn);
        shaderParamsBtn.setToolTipText(Messages.t("shaderParams.tooltip"));

        // Abilitato solo se: renderer OpenGL, shader reale (non "none") ED è quello
        // attualmente applicato (così l'anteprima a caldo dei parametri corrisponde).
        final String appliedShader = config.shader();
        Runnable syncShaderTools = () -> {
            boolean gl = rendererCombo.getSelectedItem() == RendererType.OPENGL;
            shaderCombo.setEnabled(gl);
            Object sel = shaderCombo.getSelectedItem();
            boolean real = sel != null && !"none".equals(sel);
            shaderParamsBtn.setEnabled(gl && real && sel.equals(appliedShader));
        };
        rendererCombo.addActionListener(e -> syncShaderTools.run());
        shaderCombo.addActionListener(e -> syncShaderTools.run());
        syncShaderTools.run();

        shaderParamsBtn.addActionListener(e -> {
            Object sel = shaderCombo.getSelectedItem();
            if (sel == null || "none".equals(sel)) return;
            java.nio.file.Path glslp = AppHome.resolve("shaders", sel + ".glslp");
            if (java.nio.file.Files.isRegularFile(glslp)) {
                ShaderParamsDialog.show(dialog, glslp, shaderParamUpdater);
            }
        });

        // BorderLayout: la tendina (CENTER) si allarga per riempire la colonna, il "..."
        // (EAST) resta a destra al bordo. Così tendina + pulsante hanno la stessa larghezza
        // totale delle altre tendine, allineate sia a sinistra sia a destra.
        JPanel shaderRow = new JPanel(new BorderLayout(4, 0));
        shaderRow.setOpaque(false);
        shaderRow.add(shaderCombo, BorderLayout.CENTER);
        shaderRow.add(shaderParamsBtn, BorderLayout.EAST);
        addRow(av, 1, "label.shader", shaderRow, null);

        JComboBox<AmigaWindowMode> windowModeCombo = new JComboBox<>(AmigaWindowMode.values());
        windowModeCombo.setSelectedItem(config.windowMode());
        windowModeCombo.setMaximumSize(dim(220, 26));
        windowModeCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof AmigaWindowMode mode) {
                    setText(Messages.t("windowMode." + mode.configValue()));
                }
                return this;
            }
        });
        addRow(av, 2, "label.windowMode", windowModeCombo, null);

        // Monitor su cui aprire l'emulatore: indici 0..n-1 dei monitor collegati
        // (0 = principale). Il valore configurato resta selezionabile anche se il
        // monitor non è al momento collegato, così non viene perso.
        int monitorCount = Math.max(1, AmigaDisplay.screenDevices().length);
        int selectedMonitor = config.monitorIndex();
        java.util.List<Integer> monitorItems = new java.util.ArrayList<>();
        for (int i = 0; i < monitorCount; i++) monitorItems.add(i);
        if (!monitorItems.contains(selectedMonitor)) monitorItems.add(selectedMonitor);
        JComboBox<Integer> monitorCombo =
                new JComboBox<>(monitorItems.toArray(new Integer[0]));
        monitorCombo.setSelectedItem(selectedMonitor);
        monitorCombo.setMaximumSize(dim(220, 26));
        addRow(av, 3, "label.monitor", monitorCombo, null);

        JSpinner scalingSpinner = new JSpinner(
                new SpinnerNumberModel(config.integerScaling(), 1, null, 1));
        scalingSpinner.setPreferredSize(dim(56, 26));
        JPanel scalingWrap = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        scalingWrap.setOpaque(false);
        scalingWrap.add(scalingSpinner);
        addRow(av, 4, "label.integerScaling", scalingWrap, null);

        JCheckBox overscanCheck = check(Messages.t("label.overscan"), config.overscanEnabled());
        tip("label.overscan", overscanCheck);
        addRow(av, 5, overscanCheck, null, null);

        JCheckBox ledCheck = check(Messages.t("label.leds"), config.ledsVisible());
        tip("label.leds", ledCheck);
        addRow(av, 6, ledCheck, null, null);

        JSpinner soundSpinner = new JSpinner(
                new SpinnerNumberModel(config.floppySoundVolume(), 0, 10, 1));
        soundSpinner.setPreferredSize(dim(56, 26));
        JPanel soundWrap = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        soundWrap.setOpaque(false);
        soundWrap.add(soundSpinner);
        addRow(av, 7, "label.sound", soundWrap, null);

        // -------------------------------------------------------------------
        // Sezione: GUI — l'aspetto del popup e delle finestre di AmiJa, non della
        // macchina emulata. Per ora la sola lingua; le prossime voci di interfaccia
        // vanno qui invece che in "Sistema".
        // -------------------------------------------------------------------
        JPanel gui = section(Messages.t("section.gui"));

        JComboBox<String> langCombo = new JComboBox<>(new String[]{ "English", "Italiano" });
        langCombo.setSelectedIndex("it".equalsIgnoreCase(config.language()) ? 1 : 0);
        langCombo.setMaximumSize(dim(160, 26));
        addRow(gui, 0, "label.language", langCombo, null);

        // Scala della sola interfaccia. Frazionaria: al contrario dell'integer scaling
        // della finestra emulata, qui un'interpolazione non toglie niente a nessuno.
        // Mostrata in percentuale perché è la forma in cui Windows presenta la stessa
        // idea nelle impostazioni schermo; sul file resta un numero (1, 1.25, ...).
        // Un valore scritto a mano fuori dalla lista viene aggiunto invece che perso.
        java.util.List<Double> scaleItems = new java.util.ArrayList<>(
                java.util.List.of(1.0, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0));
        double currentUiScale = config.uiScale();
        if (!scaleItems.contains(currentUiScale)) {
            scaleItems.add(currentUiScale);
            java.util.Collections.sort(scaleItems);
        }
        JComboBox<Double> uiScaleCombo =
                new JComboBox<>(scaleItems.toArray(new Double[0]));
        uiScaleCombo.setSelectedItem(currentUiScale);
        uiScaleCombo.setMaximumSize(dim(160, 26));
        uiScaleCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof Double d) {
                    setText(Math.round(d * 100) + "%");
                }
                return this;
            }
        });
        addRow(gui, 1, "label.uiScale", uiScaleCombo, null);

        // -------------------------------------------------------------------
        // Sezione: Stato salvato (lo slot unico, con la sua foto e la sua data)
        // -------------------------------------------------------------------
        JPanel state = stateSection(dialog, result, onSaveState, onLoadState);

        // -------------------------------------------------------------------
        // Contenuto scorrevole
        // -------------------------------------------------------------------
        JPanel content = new JPanel();
        content.setBackground(BG);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(pad(14, 16, 14, 16));
        content.add(header());
        content.add(Box.createVerticalStrut(10));
        content.add(system);
        content.add(media);       // modalità Auto: sostituisce le tre sezioni qui sotto
        content.add(drives);
        content.add(hds);
        content.add(whd);
        // Lo stato salvato viene dopo i supporti — dopo TUTTI, cioè anche dopo le tre
        // sezioni per tipo che in Expert mode prendono il posto della lista unica: così
        // sta sotto i dischi in entrambe le modalità. È l'unica sezione che non imposta
        // niente ma agisce sulla macchina accesa, e segue il disco a cui appartiene.
        content.add(state);
        content.add(ports);
        content.add(av);
        content.add(gui);
        if (assets != null) {
            content.add(assets);
        }
        content.add(Box.createVerticalGlue());

        // Solo ora che le sezioni sono dentro al contenitore: la prima passata decide
        // quali si vedono, ed è lei a rendere il popup coerente con la modalità corrente.
        syncAuto.run();

        JScrollPane scroll = new JScrollPane(content,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getViewport().setBackground(BG);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        // Dimensione visibile dell'area scorrevole: se il contenuto è più alto,
        // compare la barra di scorrimento verticale.
        scroll.setPreferredSize(dim(580, 460));

        // -------------------------------------------------------------------
        // Barra dei pulsanti
        // -------------------------------------------------------------------
        JButton about      = button(Messages.t("button.about"), false);
        JButton screenshot = button(Messages.t("button.screenshot"), false);
        JButton softReset = button(Messages.t("button.softreset"), false);
        JButton restart   = button(Messages.t("button.restart"), false);
        JButton quit      = button(Messages.t("button.quit"), false);
        JButton cancel    = button(Messages.t("button.cancel"), false);
        JButton ok        = button(Messages.t("button.ok"), true);

        // Anche i pulsanti si spiegano: fra "Soft Reset" e "Riavvia" la differenza non
        // si indovina, e chi apre il popup per la prima volta merita di saperla prima
        // di premere.
        tip("button.about", about);
        tip("button.screenshot", screenshot);
        tip("button.softreset", softReset);
        tip("button.restart", restart);
        tip("button.quit", quit);
        tip("button.cancel", cancel);
        tip("button.ok", ok);

        // "About": logo, versione, autore e indirizzo del progetto. Non tocca niente
        // della configurazione, quindi non ha bisogno di applicare né di chiudere.
        about.addActionListener(e -> AboutDialog.show(dialog));

        // "Take Screenshot": salva la finestra di gioco (non il popup) in screenshots/.
        // Funziona sia in software (composizione Java2D) sia in OpenGL (glReadPixels).
        screenshot.addActionListener(e -> {
            java.io.File f = onScreenshot != null ? onScreenshot.get() : null;
            if (f != null) {
                JOptionPane.showMessageDialog(dialog,
                        Messages.t("screenshot.saved", f.getAbsolutePath()),
                        Messages.t("app.title"), JOptionPane.INFORMATION_MESSAGE);
            } else {
                JOptionPane.showMessageDialog(dialog, Messages.t("screenshot.failed"),
                        Messages.t("app.title"), JOptionPane.ERROR_MESSAGE);
            }
        });

        Runnable applyConfig = () -> {
            config.setAutoEnabled(simpleMode.getAsBoolean());
            config.setAutoKickstart13(ks13Field.getText());
            config.setAutoKickstart31(ks31Field.getText());
            // Con Expert spento i parametri di sistema sono dedotti, non scelti: quelli
            // salvati NON vengono toccati, così accendere Expert restituisce all'utente
            // esattamente la macchina che aveva configurato a mano.
            if (!simpleMode.getAsBoolean()) {
                config.setKickstartPath(kickField.getText());
                config.setChipRamKb((Integer) chipCombo.getSelectedItem());
                config.setSlowRamKb((Integer) slowCombo.getSelectedItem());
                config.setFastRamKb((Integer) fastCombo.getSelectedItem());
                config.setVideoMode((VideoMode) videoModeCombo.getSelectedItem());
                config.setChipset((Chipset) chipsetCombo.getSelectedItem());
            }
            // I supporti vengono dalla vista che l'utente ha davanti: la lista unica con
            // Expert spento, le tre sezioni per tipo con Expert acceso.
            (simpleMode.getAsBoolean() ? driveList.toMediaState() : classicMedia.get()).toConfig(config);
            config.setFloppySoundVolume((Integer) soundSpinner.getValue());
            config.setLedsVisible(ledCheck.isSelected());
            config.setOverscanEnabled(overscanCheck.isSelected());
            config.setWindowMode((AmigaWindowMode) windowModeCombo.getSelectedItem());
            config.setMonitorIndex((Integer) monitorCombo.getSelectedItem());
            config.setRenderer((RendererType) rendererCombo.getSelectedItem());
            config.setShader((String) shaderCombo.getSelectedItem());
            config.setPortDevice(0, (PortDevice) port0Combo.getSelectedItem());
            config.setPortDevice(1, (PortDevice) port1Combo.getSelectedItem());
            config.setJoystickKeyMap(0, joyKeyMaps[0]);
            config.setJoystickKeyMap(1, joyKeyMaps[1]);
            config.setLanguage(langCombo.getSelectedIndex() == 1 ? "it" : "en");
            config.setIntegerScaling((Integer) scalingSpinner.getValue());
            config.setUiScale((Double) uiScaleCombo.getSelectedItem());
        };

        softReset.addActionListener(e -> {
            applyConfig.run();
            result[0] = Result.SOFT_RESET;
            dialog.dispose();
        });
        restart.addActionListener(e -> {
            applyConfig.run();
            result[0] = Result.RESTART;
            dialog.dispose();
        });
        quit.addActionListener(e -> { result[0] = Result.QUIT; dialog.dispose(); });
        cancel.addActionListener(e -> { result[0] = Result.CANCEL; dialog.dispose(); });
        ok.addActionListener(e -> {
            // Applica i valori modificati all'AmigaConfig prima di chiudere.
            applyConfig.run();
            result[0] = Result.OK;
            dialog.dispose();
        });

        JPanel buttons = new JPanel(new BorderLayout());
        buttons.setBackground(PANEL_BG);
        buttons.setBorder(pad(10, 16, 10, 16));

        JPanel left = new JPanel();
        left.setOpaque(false);
        left.add(about);
        left.add(screenshot);
        left.add(softReset);
        left.add(restart);
        left.add(quit);

        JPanel right = new JPanel();
        right.setOpaque(false);
        right.add(cancel);
        right.add(ok);

        buttons.add(left, BorderLayout.WEST);
        buttons.add(right, BorderLayout.EAST);

        // -------------------------------------------------------------------
        // Assemblaggio finale
        // -------------------------------------------------------------------
        dialog.getContentPane().setBackground(BG);
        dialog.getContentPane().setLayout(new BorderLayout());
        dialog.getContentPane().add(scroll, BorderLayout.CENTER);
        dialog.getContentPane().add(buttons, BorderLayout.SOUTH);

        // ESC = Annulla, INVIO = OK.
        dialog.getRootPane().setDefaultButton(ok);
        dialog.getRootPane().registerKeyboardAction(
                e -> { result[0] = Result.CANCEL; dialog.dispose(); },
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        dialog.pack();
        dialog.setMinimumSize(dim(560, 320));
        fitToScreen(dialog);
        dialog.setLocationRelativeTo(parent);
        prepareDialogFocus(dialog, ok);
        dialog.setVisible(true); // bloccante (modale)

        return result[0];
    }

    /**
     * Riduce il popup a quel che ci sta sullo schermo, se dopo il {@code pack()} eccede.
     *
     * <p>Il contenuto è già dentro un riquadro scorrevole, quindi rimpicciolire non
     * nasconde nulla: si scorre. Senza questo, i pulsanti in fondo — OK, Annulla, Riavvia —
     * finiscono fuori dallo schermo e il popup diventa inutilizzabile, perché non è
     * ridimensionabile e i pulsanti sono l'unico modo di confermare.
     *
     * <p>Non è un problema nuovo della scala dell'interfaccia: esisteva già, e bastava un
     * integer scaling alto su uno schermo piccolo per incontrarlo. La scala frazionaria lo
     * rende solo più facile da raggiungere.
     *
     * <p>Si usa l'<em>area di lavoro</em> e non i bordi dello schermo, così il popup non
     * finisce sotto la barra delle applicazioni.
     */
    private static void fitToScreen(JDialog dialog) {
        Rectangle usable = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getMaximumWindowBounds();
        int w = Math.min(dialog.getWidth(), usable.width - px(20));
        int h = Math.min(dialog.getHeight(), usable.height - px(20));
        if (w < dialog.getWidth() || h < dialog.getHeight()) {
            dialog.setSize(w, h);
        }
    }

    private static void prepareDialogFocus(JDialog dialog, Component initialFocus) {
        dialog.setAutoRequestFocus(true);
        dialog.setFocusableWindowState(true);
        dialog.setAlwaysOnTop(true);
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                requestDialogFocus(dialog, initialFocus);
            }

            @Override
            public void windowClosed(WindowEvent e) {
                dialog.setAlwaysOnTop(false);
            }
        });
    }

    private static void requestDialogFocus(JDialog dialog, Component initialFocus) {
        javax.swing.SwingUtilities.invokeLater(() -> {
            dialog.toFront();
            dialog.requestFocus();
            if (initialFocus != null) {
                initialFocus.requestFocusInWindow();
            } else {
                dialog.requestFocusInWindow();
            }
        });
        javax.swing.Timer timer = new javax.swing.Timer(120, e -> {
            dialog.toFront();
            if (initialFocus != null && !initialFocus.hasFocus()) {
                initialFocus.requestFocusInWindow();
            }
        });
        timer.setRepeats(false);
        timer.start();
    }

    // =========================================================================
    // Costruzione dei widget
    // =========================================================================

    private static JPanel header() {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel title = new JLabel(Messages.t("header.title"));
        title.setForeground(FG);
        title.setFont(derive(title.getFont(), Font.BOLD, 18f));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel sub = new JLabel(Messages.t("header.subtitle"));
        sub.setForeground(new Color(0xA8, 0xAC, 0xB3));
        sub.setFont(derive(sub.getFont(), Font.PLAIN, 11f));
        sub.setAlignmentX(Component.LEFT_ALIGNMENT);

        p.add(title);
        p.add(Box.createVerticalStrut(2));
        p.add(sub);
        return p;
    }

    // =========================================================================
    // Sezione "Stato salvato"
    // =========================================================================

    /**
     * La sezione dello slot di stato: a sinistra la foto della macchina com'era quando
     * lo stato è stato preso, a destra il momento in cui è successo e i due pulsanti che
     * lo scrivono e lo rileggono.
     *
     * <p>Lo slot è uno solo ({@code savestate/state1.a5s}), quindi qui non c'è una lista
     * da sfogliare: c'è un'istantanea, e la si guarda per riconoscerla prima di
     * sovrascriverla o di tornarci. Foto e data si rileggono dal disco a ogni
     * salvataggio, così la sezione mostra sempre lo slot vero e non quello che c'era
     * all'apertura del popup.
     *
     * <p>Il caricamento può non finire qui: se lo stato pretende una macchina diversa da
     * quella accesa, il popup si chiude con {@link Result#STATE_RESTORE} e prosegue il
     * chiamante (vedi {@link LoadStateOutcome}).
     */
    private static JPanel stateSection(JDialog dialog, Result[] result,
            java.util.function.Supplier<String> onSaveState,
            java.util.function.Supplier<LoadStateOutcome> onLoadState) {
        JPanel panel = section(Messages.t("section.state"));

        StatePreview preview = new StatePreview();
        JLabel when = label("");
        when.setAlignmentX(Component.LEFT_ALIGNMENT);

        JButton save = button(Messages.t("button.savestate"), false);
        JButton load = button(Messages.t("button.loadstate"), false);
        tip("button.savestate", save);
        tip("button.loadstate", load);

        // Rilegge lo slot dal disco: foto, data e disponibilità del caricamento.
        Runnable refresh = () -> {
            File slot = SaveStateManager.slotFile();
            boolean present = slot.isFile();
            preview.setImage(present ? readImage(SaveStateManager.slotScreenshot()) : null);
            preview.setEmptyText(Messages.t("state.empty.short"));
            when.setText(present
                    ? Messages.t("state.savedOn", formatTimestamp(slotTimestamp(slot)))
                    : Messages.t("state.empty"));
            load.setEnabled(present);
        };
        refresh.run();

        save.addActionListener(e -> {
            String msg = onSaveState != null ? onSaveState.get() : null;
            // Prima si riallinea la sezione, poi si parla: chiudendo il messaggio
            // l'utente trova la foto nuova, non quella di prima.
            refresh.run();
            message(dialog, msg != null ? msg : Messages.t("savestate.save.fail", "?"));
        });
        load.addActionListener(e -> {
            LoadStateOutcome outcome = onLoadState == null ? null : onLoadState.get();
            if (outcome == null) {
                message(dialog, Messages.t("savestate.load.fail", "?"));
                return;
            }
            if (outcome.needsRestart()) {
                // Le modifiche in corso nel popup NON vengono applicate: la macchina che
                // conta adesso è quella descritta dentro lo stato.
                result[0] = Result.STATE_RESTORE;
                dialog.dispose();
                return;
            }
            if (outcome.message() != null) message(dialog, outcome.message());
        });

        JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
        buttons.setOpaque(false);
        buttons.setAlignmentX(Component.LEFT_ALIGNMENT);
        buttons.add(save);
        buttons.add(load);
        // Senza tetto all'altezza, un pannello a flusso si lascia stirare dal BoxLayout e
        // il glue non lo spinge più in fondo: i pulsanti resterebbero a mezz'aria.
        buttons.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                buttons.getPreferredSize().height));

        // Colonna di destra: la data in alto, i pulsanti in basso, il vuoto in mezzo —
        // così restano allineati ai bordi della foto qualunque sia l'altezza del testo.
        JPanel side = new JPanel();
        side.setOpaque(false);
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.add(when);
        side.add(Box.createVerticalGlue());
        side.add(buttons);

        tip("state.slot", preview, when);

        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0;
        gc.gridy = 0;
        gc.insets = new Insets(px(2), px(4), px(2), px(10));
        gc.anchor = GridBagConstraints.NORTHWEST;
        panel.add(preview, gc);

        gc.gridx = 1;
        gc.weightx = 1;
        gc.fill = GridBagConstraints.BOTH;
        gc.insets = new Insets(px(2), px(0), px(2), px(4));
        panel.add(side, gc);
        return panel;
    }

    /** Messaggio informativo nello stile del popup (titolo dell'applicazione). */
    private static void message(JDialog parent, String text) {
        JOptionPane.showMessageDialog(parent, text, Messages.t("app.title"),
                JOptionPane.INFORMATION_MESSAGE);
    }

    /**
     * Istante in cui lo slot è stato salvato, letto dalla sua intestazione. Se lo slot
     * non è leggibile (p.es. scritto da una versione precedente del formato) resta la
     * data del file: sbagliare la data è meglio che non mostrare la sezione.
     */
    private static long slotTimestamp(File slot) {
        try {
            return SaveStateManager.readInfo(slot).savedAt();
        } catch (IOException e) {
            return slot.lastModified();
        }
    }

    /** Data e ora nel formato della lingua scelta per l'interfaccia. */
    private static String formatTimestamp(long millis) {
        return java.text.DateFormat.getDateTimeInstance(
                java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT, Messages.locale())
                .format(new java.util.Date(millis));
    }

    /** Legge un'immagine, o {@code null} se manca o non si lascia decodificare. */
    private static java.awt.image.BufferedImage readImage(File file) {
        if (file == null || !file.isFile()) return null;
        try {
            return javax.imageio.ImageIO.read(file);
        } catch (IOException e) {
            System.err.println("[UI] Anteprima dello stato non leggibile ("
                    + file.getName() + "): " + e.getMessage());
            return null;
        }
    }

    /**
     * Il riquadro con la foto dello slot: l'immagine ci sta dentro intera, rimpicciolita
     * quanto serve e centrata, senza deformarsi. Le dimensioni sono fisse anche quando
     * non c'è nessuno stato salvato, così la sezione non cambia altezza al primo
     * salvataggio facendo saltare tutto il popup.
     */
    private static final class StatePreview extends JComponent {

        private static final int W = 168;
        private static final int H = 126; // 4:3, le proporzioni dello schermo Amiga

        private transient java.awt.image.BufferedImage image;
        private String emptyText = "";

        StatePreview() {
            Dimension size = dim(W, H);
            setPreferredSize(size);
            setMinimumSize(size);
            setMaximumSize(size);
            setOpaque(true);
        }

        void setImage(java.awt.image.BufferedImage image) {
            this.image = image;
            repaint();
        }

        void setEmptyText(String text) {
            this.emptyText = text == null ? "" : text;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                int w = getWidth();
                int h = getHeight();
                g2.setColor(new Color(0x1C, 0x1E, 0x21));
                g2.fillRect(0, 0, w, h);

                if (image != null) {
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    double scale = Math.min((w - 2.0) / image.getWidth(),
                            (h - 2.0) / image.getHeight());
                    int dw = Math.max(1, (int) Math.round(image.getWidth() * scale));
                    int dh = Math.max(1, (int) Math.round(image.getHeight() * scale));
                    g2.drawImage(image, (w - dw) / 2, (h - dh) / 2, dw, dh, null);
                } else if (!emptyText.isEmpty()) {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                            RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(new Color(0x6E, 0x73, 0x7B));
                    g2.setFont(derive(getFont(), Font.PLAIN, 11f));
                    java.awt.FontMetrics fm = g2.getFontMetrics();
                    g2.drawString(emptyText, (w - fm.stringWidth(emptyText)) / 2,
                            (h + fm.getAscent() - fm.getDescent()) / 2);
                }

                g2.setColor(new Color(0x45, 0x49, 0x50));
                g2.drawRect(0, 0, w - 1, h - 1);
            } finally {
                g2.dispose();
            }
        }
    }

    private static JPanel section(String title) {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBackground(PANEL_BG);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        TitledBorder tb = BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(0x45, 0x49, 0x50), px(1)), title);
        tb.setTitleColor(ACCENT);
        tb.setTitleFont(derive(p.getFont(), Font.BOLD, 13f));
        // Lo spazio fra una sezione e la successiva è un margine della sezione, non uno
        // strut nel BoxLayout: così una sezione nascosta si porta via anche il proprio
        // spazio, invece di lasciare un buco (la modalità Auto nasconde tre sezioni).
        p.setBorder(BorderFactory.createCompoundBorder(
                pad(0, 0, 12, 0),
                BorderFactory.createCompoundBorder(tb,
                        pad(8, 10, 10, 10))));
        return p;
    }

    /**
     * Aggiunge una riga la cui etichetta viene dalla chiave {@code labelKey} del bundle,
     * col tooltip preso da {@code <labelKey>.tip} (vedi {@link #tip}). Il tooltip non
     * viene messo sui pulsanti di coda, che hanno già il proprio.
     */
    private static JLabel addRow(JPanel panel, int row, String labelKey, JComponent field, JComponent trailing) {
        JLabel l = label(Messages.t(labelKey));
        tip(labelKey, l, field);
        addRow(panel, row, l, field, trailing);
        return l;
    }

    /**
     * Applica a tutta la riga il tooltip di {@code baseKey}, cioè la voce
     * {@code <baseKey>.tip} del bundle: la convenzione con cui ogni opzione del popup
     * spiega sé stessa. Se la voce non c'è non succede niente.
     *
     * <p><b>Regola:</b> ogni opzione del popup deve avere il proprio {@code .tip}, e
     * quel testo va aggiornato insieme alla funzionalità che descrive. Un tooltip che
     * mente è peggio di un tooltip che manca.
     */
    private static void tip(String baseKey, JComponent... components) {
        String text = Messages.tOrNull(baseKey + ".tip");
        if (text == null) return;
        // L'HTML manda a capo da sé: senza, la spiegazione resterebbe su una riga sola
        // larga quanto lo schermo.
        String html = "<html><body style='width: 260px'>" + text + "</body></html>";
        for (JComponent c : components) {
            applyTip(c, html);
        }
    }

    /**
     * Assegna il tooltip al componente e a tutti i figli che non ne hanno già uno. La
     * discesa serve ai controlli composti: su un JPanel che avvolge tendina e pulsante,
     * o su uno JSpinner (che è un pannello col suo campo di testo), il tooltip del
     * contenitore non comparirebbe mai, perché a ricevere il mouse è il figlio.
     */
    private static void applyTip(Component c, String html) {
        if (!(c instanceof JComponent jc)) return;
        if (jc.getToolTipText() == null) jc.setToolTipText(html);
        for (Component child : jc.getComponents()) {
            applyTip(child, html);
        }
    }

    /** Etichetta di riga nello stile del popup. */
    private static JLabel label(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(FG);
        return l;
    }

    /**
     * Mostra o nasconde un'intera riga di una sezione. Con GridBagLayout la riga
     * scompare del tutto (altezza zero) quando tutti i suoi componenti sono invisibili,
     * quindi non resta il buco: è così che la sezione Sistema scambia il Kickstart
     * unico con i due della modalità Auto.
     */
    private static void setRowVisible(boolean visible, JComponent... components) {
        boolean changed = false;
        for (JComponent c : components) {
            if (c != null && c.isVisible() != visible) {
                c.setVisible(visible);
                changed = true;
            }
        }
        if (changed && components.length > 0 && components[0] != null) {
            Container parent = components[0].getParent();
            if (parent != null) {
                parent.revalidate();
                parent.repaint();
            }
        }
    }

    /**
     * Seleziona un valore in una tendina di memoria, aggiungendolo in coda se non è
     * fra le opzioni previste: i profili della modalità Auto vengono da
     * {@code amiga.properties} e l'utente può averci messo una taglia inconsueta.
     */
    private static void selectKb(JComboBox<Integer> combo, int value) {
        for (int i = 0; i < combo.getItemCount(); i++) {
            if (combo.getItemAt(i) == value) {
                combo.setSelectedIndex(i);
                return;
            }
        }
        combo.addItem(value);
        combo.setSelectedItem(value);
    }

    /** Aggiunge una riga con componente iniziale (es. checkbox), campo e browse. */
    private static void addRow(JPanel panel, int row, JComponent lead, JComponent field, JComponent trailing) {
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridy = row;
        gc.insets = new Insets(px(4), px(4), px(4), px(4));
        gc.anchor = GridBagConstraints.WEST;

        gc.gridx = 0;
        gc.weightx = 0;
        gc.fill = GridBagConstraints.NONE;
        panel.add(lead, gc);

        if (field != null) {
            gc.gridx = 1;
            gc.weightx = 1;
            gc.fill = GridBagConstraints.HORIZONTAL;
            panel.add(field, gc);
        }

        if (trailing != null) {
            gc.gridx = 2;
            gc.weightx = 0;
            gc.fill = GridBagConstraints.NONE;
            panel.add(trailing, gc);
        }
    }

    private static JTextField textField(String value) {
        JTextField tf = new JTextField(value == null ? "" : value);
        tf.setColumns(28);
        tf.setBackground(new Color(0x25, 0x27, 0x2A));
        tf.setForeground(FG);
        tf.setCaretColor(FG);
        tf.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0x45, 0x49, 0x50), px(1)),
                pad(3, 5, 3, 5)));
        return tf;
    }

    private static JButton browseButton() {
        JButton b = button("...", false);
        styleDriveToolButton(b);
        b.setToolTipText(Messages.t("browse.tooltip"));
        return b;
    }

    private static JButton ejectButton() {
        JButton b = button("⏏", false); // simbolo "eject"
        styleDriveToolButton(b);
        b.setToolTipText(Messages.t("eject.tooltip"));
        return b;
    }

    private static JToggleButton readOnlyButton(boolean selected) {
        JToggleButton b = new JToggleButton();
        b.setSelected(selected);
        b.setFocusPainted(false);
        styleDriveToolButton(b);
        b.setToolTipText(Messages.t("readonly.tooltip"));
        updateReadOnlyButtonIcon(b);
        b.addItemListener(e -> updateReadOnlyButtonIcon(b));
        return b;
    }

    private static void updateReadOnlyButtonIcon(JToggleButton b) {
        b.setIcon(b.isSelected() ? LOCK_CLOSED_ICON : LOCK_OPEN_ICON);
    }

    private static void styleDriveToolButton(javax.swing.AbstractButton b) {
        b.setMargin(new Insets(px(2), px(4), px(2), px(4)));
        Dimension size = driveToolButtonSize();
        b.setPreferredSize(size);
        b.setMinimumSize(size);
        b.setMaximumSize(size);
    }

    private static JCheckBox check(String text, boolean selected) {
        JCheckBox c = new JCheckBox(text, selected);
        c.setOpaque(false);
        c.setForeground(FG);
        return c;
    }

    /** Tendina di scelta della periferica di una game port (Mouse / Joystick / Vuota). */
    private static JComboBox<PortDevice> deviceCombo(PortDevice current) {
        JComboBox<PortDevice> combo = new JComboBox<>(PortDevice.values());
        combo.setSelectedItem(current);
        combo.setMaximumSize(dim(200, 26));
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof PortDevice d) setText(Messages.t(d.messageKey()));
                return this;
            }
        });
        return combo;
    }

    /** Pulsante "..." che apre la configurazione dei tasti del joystick da tastiera. */
    private static JButton joystickKeysButton() {
        JButton b = button("...", false);
        styleDriveToolButton(b);
        b.setToolTipText(Messages.t("joykeys.tooltip"));
        return b;
    }

    /**
     * Mostra o nasconde il pulsante dei tasti joystick. La colonna di coda della sezione
     * resta dimensionata dal "..." del Kickstart, quindi la comparsa del pulsante non
     * cambia la larghezza della tendina accanto.
     */
    private static void setJoystickKeysButtonVisible(JButton keysButton, boolean visible) {
        if (keysButton.isVisible() == visible) return;
        keysButton.setVisible(visible);
        Container parent = keysButton.getParent();
        if (parent != null) {
            parent.revalidate();
            parent.repaint();
        }
    }

    private static JComboBox<Integer> kbCombo(Integer[] options, int current) {
        JComboBox<Integer> combo = new JComboBox<>(ensureContains(options, current));
        combo.setSelectedItem(current);
        combo.setMaximumSize(dim(160, 26));
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                setText(formatKb((Integer) value));
                return this;
            }
        });
        return combo;
    }

    /** Garantisce che {@code value} sia tra le opzioni (lo inserisce in coda se assente). */
    private static Integer[] ensureContains(Integer[] options, int value) {
        for (Integer o : options) {
            if (o == value) return options;
        }
        Integer[] extended = new Integer[options.length + 1];
        System.arraycopy(options, 0, extended, 0, options.length);
        extended[options.length] = value;
        return extended;
    }

    private static String formatKb(int kb) {
        if (kb == 0) return Messages.t("ram.none");
        if (kb % 1024 == 0) return (kb / 1024) + " MB";
        return kb + " KB";
    }

    private static JButton button(String text, boolean primary) {
        JButton b = new JButton(text);
        b.setFocusPainted(false);
        if (primary) {
            b.setBackground(ACCENT);
            b.setForeground(Color.BLACK);
            b.setFont(b.getFont().deriveFont(Font.BOLD));
        }
        return b;
    }

    /** Vero anche per i singoli dischi di uno zip multi-disco ({@code <zip>!<n>}). */
    private static boolean isZipPath(String path) {
        return io.github.zolfrik.amija.custom.DiskImage.isZipDisk(path);
    }

    /**
     * Elenca i preset shader disponibili: {@code "none"} seguito dai nomi (senza
     * estensione) dei {@code .glslp} nella cartella {@code shaders} della home
     * dell'applicazione, in ordine alfabetico case-insensitive.
     */
    private static String[] scanShaderNames() {
        java.util.List<String> names = new java.util.ArrayList<>();
        names.add("none");
        File dir = AppHome.file("shaders");
        File[] files = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".glslp"));
        if (files != null) {
            java.util.Arrays.sort(files,
                    (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File f : files) {
                String n = f.getName();
                names.add(n.substring(0, n.length() - ".glslp".length()));
            }
        }
        return names.toArray(new String[0]);
    }

    // =========================================================================
    // Supporti: modello condiviso fra le due viste
    // =========================================================================

    /**
     * I supporti scelti dall'utente, nella forma esatta in cui finiscono in
     * {@code amiga.properties}. È la moneta di scambio fra le due viste del popup — le
     * tre sezioni classiche (Floppy/Hard disk/WHDLoad) e la lista unica della modalità
     * Auto — così passare dall'una all'altra non perde quello che si è appena scelto.
     */
    private static final class MediaState {

        final String[] floppyDisk = new String[4];
        final boolean[] floppyConnected = new boolean[4];
        final boolean[] floppyReadOnly = new boolean[4];
        final String[] hdDisk = new String[AmigaConfig.HD_UNITS.length];
        final boolean[] hdConnected = new boolean[AmigaConfig.HD_UNITS.length];
        final boolean[] hdReadOnly = new boolean[AmigaConfig.HD_UNITS.length];
        boolean whdloadEnabled;
        String whdloadFile = "";

        static MediaState fromConfig(AmigaConfig c) {
            MediaState s = new MediaState();
            for (int i = 0; i < 4; i++) {
                s.floppyDisk[i] = c.driveDisk(i);
                s.floppyConnected[i] = c.driveEnabled(i);
                s.floppyReadOnly[i] = c.driveReadOnly(i);
            }
            for (int u = 0; u < s.hdDisk.length; u++) {
                s.hdDisk[u] = c.hdDisk(u);
                s.hdConnected[u] = c.hdEnabled(u);
                s.hdReadOnly[u] = c.hdReadOnly(u);
            }
            s.whdloadEnabled = c.whdloadEnabled();
            s.whdloadFile = c.whdloadFile() == null ? "" : c.whdloadFile();
            return s;
        }

        void toConfig(AmigaConfig c) {
            for (int i = 0; i < 4; i++) {
                c.setDriveEnabled(i, floppyConnected[i]);
                c.setDriveDisk(i, text(floppyDisk[i]));
                c.setDriveReadOnly(i, floppyReadOnly[i] || isZipPath(text(floppyDisk[i])));
            }
            for (int u = 0; u < hdDisk.length; u++) {
                c.setHdEnabled(u, hdConnected[u]);
                c.setHdDisk(u, text(hdDisk[u]));
                c.setHdReadOnly(u, hdReadOnly[u]);
            }
            c.setWhdloadEnabled(whdloadEnabled);
            c.setWhdloadFile(text(whdloadFile));
        }

        private static String text(String s) {
            return s == null ? "" : s.trim();
        }
    }

    // =========================================================================
    // Lista unica dei supporti (modalità Auto)
    // =========================================================================

    /**
     * La sezione "Drives" della modalità Auto: una sola lista in cui l'utente mette i
     * propri file senza doversi chiedere in quale sezione vadano. Si parte da una voce
     * vuota; scelto un file, la voce prende il nome del posto in cui il file è finito
     * (DF0, hard drive master, WHDLoad) e sotto ne compare un'altra, finché c'è ancora
     * spazio per qualcosa.
     *
     * <p>Il tipo si deduce dall'estensione, e le regole della macchina vera fanno il
     * resto: quattro floppy al massimo, due hard disk, un solo WHDLoad — e hard disk e
     * WHDLoad non convivono, perché sarebbero entrambi il volume di avvio. Quello che
     * non è più aggiungibile sparisce dal filtro del browser, quindi la lista non può
     * arrivare in uno stato che la macchina non saprebbe montare.
     */
    private static final class DriveList {

        /** Tipo di supporto, riconosciuto dall'estensione del file. */
        private enum Kind {
            FLOPPY(4, "adf", "ipf", "zip"),
            HARDDISK(AmigaConfig.HD_UNITS.length, "hdf"),
            WHDLOAD(1, "lha", "lzh");

            final int max;
            final String[] extensions;

            Kind(int max, String... extensions) {
                this.max = max;
                this.extensions = extensions;
            }

            static Kind of(String path) {
                String name = path.toLowerCase(java.util.Locale.ROOT);
                // Uno zip multi-disco è indirizzato come <zip>!<n>: conta l'archivio.
                int cut = name.indexOf(io.github.zolfrik.amija.custom.DiskImage.ZIP_ENTRY_SEPARATOR);
                if (cut > 0) name = name.substring(0, cut);
                for (Kind k : values()) {
                    for (String ext : k.extensions) {
                        if (name.endsWith("." + ext)) return k;
                    }
                }
                return null;
            }
        }

        /** Un file scelto dall'utente: il suo tipo e come va montato. */
        private static final class Item {
            final Kind kind;
            final String path;
            boolean readOnly;

            Item(Kind kind, String path, boolean readOnly) {
                this.kind = kind;
                this.path = path;
                this.readOnly = readOnly;
            }
        }

        private final JDialog owner;
        private final AmigaConfig config;
        private final JPanel panel;
        private final java.util.List<Item> items = new java.util.ArrayList<>();

        DriveList(JDialog owner, AmigaConfig config, JPanel panel) {
            this.owner = owner;
            this.config = config;
            this.panel = panel;
        }

        /**
         * Ricostruisce la lista dai supporti configurati. I dischi che AmiJa ha estratto
         * da uno zip multi-disco in DF0 non diventano voci a sé: la voce è l'archivio, e
         * i dischi che contiene tornano al loro posto da soli al prossimo avvio.
         */
        void seed(MediaState state) {
            items.clear();
            String df0 = MediaState.text(state.floppyDisk[0]);
            for (int i = 0; i < 4; i++) {
                String path = MediaState.text(state.floppyDisk[i]);
                if (path.isEmpty() || !state.floppyConnected[i]) continue;
                if (i > 0 && isZipEntryOf(path, df0)) continue;
                add(path, state.floppyReadOnly[i]);
            }
            for (int u = 0; u < state.hdDisk.length; u++) {
                String path = MediaState.text(state.hdDisk[u]);
                if (!path.isEmpty() && state.hdConnected[u]) add(path, state.hdReadOnly[u]);
            }
            String whd = MediaState.text(state.whdloadFile);
            if (state.whdloadEnabled && !whd.isEmpty()) add(whd, false);
            rebuild();
        }

        private void add(String path, boolean readOnly) {
            Kind kind = Kind.of(path);
            if (kind != null) items.add(new Item(kind, path, readOnly));
        }

        /** I supporti della lista, distribuiti sui drive della macchina. */
        MediaState toMediaState() {
            MediaState s = new MediaState();
            int floppies = 0;
            int hds = 0;
            for (Item item : items) {
                switch (item.kind) {
                    case FLOPPY -> {
                        if (floppies >= 4) break;
                        s.floppyDisk[floppies] = item.path;
                        s.floppyReadOnly[floppies] = item.readOnly;
                        floppies++;
                    }
                    case HARDDISK -> {
                        if (hds >= s.hdDisk.length) break;
                        s.hdDisk[hds] = item.path;
                        s.hdConnected[hds] = true;
                        s.hdReadOnly[hds] = item.readOnly;
                        hds++;
                    }
                    case WHDLOAD -> {
                        s.whdloadEnabled = true;
                        s.whdloadFile = item.path;
                    }
                }
            }
            // Un drive collegato per ogni disco, e altrettanti per i dischi di un
            // eventuale zip multi-disco in DF0 (Amija li distribuisce all'avvio, ma solo
            // sui drive che esistono).
            //
            // Senza floppy nella lista i drive restano scollegati, esattamente come una
            // macchina configurata a mano su cui non si è spuntato nessun drive: chi
            // avvia un hard disk o un WHDLoad non ha bisogno di un lettore, e averlo lì
            // costa solo il tempo che il Kickstart perde a interrogarlo. L'eccezione è
            // la lista del tutto vuota: lì DF0 resta collegato perché è l'unico bersaglio
            // possibile di un disco trascinato sulla finestra, e senza di lui il
            // drag-and-drop non avrebbe dove atterrare (AmigaDisplay rifiuta il drop sui
            // drive non collegati).
            int connected = floppies;
            if (floppies > 0) connected = Math.max(connected, zipDiskCount(s.floppyDisk[0]));
            if (items.isEmpty()) connected = 1;
            for (int i = 0; i < Math.min(4, connected); i++) s.floppyConnected[i] = true;
            return s;
        }

        /** Quanti dischi contiene l'archivio, 1 se non è uno zip multi-disco. */
        private static int zipDiskCount(String path) {
            if (!isZipPath(path)) return 1;
            try {
                return Math.max(1, io.github.zolfrik.amija.custom.DiskImage
                        .listZipDiskEntries(io.github.zolfrik.amija.custom.DiskImage.pathOf(path)).size());
            } catch (IOException e) {
                return 1;
            }
        }

        /** {@code true} se {@code disk} è un disco estratto dallo zip {@code zipPath}. */
        private static boolean isZipEntryOf(String disk, String zipPath) {
            return !zipPath.isEmpty() && isZipPath(zipPath)
                    && disk.regionMatches(true, 0, zipPath, 0, zipPath.length())
                    && disk.length() > zipPath.length()
                    && disk.charAt(zipPath.length())
                        == io.github.zolfrik.amija.custom.DiskImage.ZIP_ENTRY_SEPARATOR;
        }

        /**
         * Tipi ancora aggiungibili nella riga {@code index}, ignorando quello che la riga
         * contiene adesso (la si sta per sostituire). {@code index == items.size()} è la
         * riga vuota in coda.
         */
        private java.util.EnumSet<Kind> allowed(int index) {
            java.util.EnumSet<Kind> out = java.util.EnumSet.noneOf(Kind.class);
            for (Kind kind : Kind.values()) {
                if (count(kind, index) >= kind.max) continue;
                // Hard disk e WHDLoad sono entrambi il volume di avvio: il primo che
                // arriva esclude l'altro.
                if (kind == Kind.HARDDISK && count(Kind.WHDLOAD, index) > 0) continue;
                if (kind == Kind.WHDLOAD && count(Kind.HARDDISK, index) > 0) continue;
                out.add(kind);
            }
            return out;
        }

        /** Quante voci di quel tipo ci sono, escludendo la riga {@code except}. */
        private int count(Kind kind, int except) {
            int n = 0;
            for (int i = 0; i < items.size(); i++) {
                if (i != except && items.get(i).kind == kind) n++;
            }
            return n;
        }

        /** Etichetta della riga: il posto in cui il file è finito, o "Drive n" se vuota. */
        private String labelFor(int index) {
            if (index >= items.size()) {
                return Messages.t("drive.entry.generic", index + 1);
            }
            Item item = items.get(index);
            int nth = 0;
            for (int i = 0; i < index; i++) {
                if (items.get(i).kind == item.kind) nth++;
            }
            return switch (item.kind) {
                case FLOPPY -> Messages.t("drive.entry.floppy", nth);
                case HARDDISK -> Messages.t(nth == 0 ? "drive.entry.hd.master" : "drive.entry.hd.slave");
                case WHDLOAD -> Messages.t("drive.entry.whdload");
            };
        }

        /** Chiave del tooltip della riga, per tipo (le etichette sono dinamiche). */
        private String tipKeyFor(int index) {
            if (index >= items.size()) return "drive.entry.generic";
            return switch (items.get(index).kind) {
                case FLOPPY -> "drive.entry.floppy";
                case HARDDISK -> "drive.entry.hd";
                case WHDLOAD -> "drive.entry.whdload";
            };
        }

        void rebuild() {
            panel.removeAll();
            int rows = items.size() + (allowed(items.size()).isEmpty() ? 0 : 1);
            for (int row = 0; row < rows; row++) {
                addEntryRow(row);
            }
            panel.revalidate();
            panel.repaint();
        }

        private void addEntryRow(int index) {
            boolean filled = index < items.size();
            JLabel label = label(labelFor(index));
            JTextField path = textField(filled ? items.get(index).path : "");
            // Non modificabile a mano: in questa modalità il tipo del file decide dove
            // il file va a finire, quindi si sceglie col browser e basta.
            path.setEditable(false);

            JButton browse = browseButton();
            browse.addActionListener(e -> browseInto(index));

            JToggleButton readOnly = readOnlyButton(filled && items.get(index).readOnly);
            // Il WHDLoad non si monta: AmiJa gli costruisce un hard disk suo, che deve
            // poter scrivere i salvataggi.
            readOnly.setEnabled(filled && items.get(index).kind != Kind.WHDLOAD);
            readOnly.addItemListener(e -> {
                if (index < items.size()) items.get(index).readOnly = readOnly.isSelected();
            });

            JButton eject = ejectButton();
            eject.setToolTipText(Messages.t("eject.entry.tooltip"));
            eject.setEnabled(filled);
            eject.addActionListener(e -> {
                if (index < items.size()) {
                    items.remove(index);
                    rebuild();
                }
            });

            JPanel trailing = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
            trailing.setOpaque(false);
            trailing.add(browse);
            trailing.add(readOnly);
            trailing.add(eject);

            tip(tipKeyFor(index), label, path);
            addRow(panel, index, label, path, trailing);
        }

        /** Sceglie il file della riga {@code index} e ne ricalcola tutta la lista. */
        private void browseInto(int index) {
            java.util.EnumSet<Kind> allowed = allowed(index);
            if (allowed.isEmpty()) return;
            java.util.List<String> extensions = new java.util.ArrayList<>();
            for (Kind kind : allowed) extensions.addAll(java.util.List.of(kind.extensions));
            StringBuilder desc = new StringBuilder(Messages.t("chooser.media")).append(" (");
            for (int i = 0; i < extensions.size(); i++) {
                desc.append(i == 0 ? "*." : ", *.").append(extensions.get(i));
            }
            desc.append(')');

            String current = index < items.size() ? items.get(index).path : "";
            File chosen = chooseFile(owner, config, BrowseKind.MEDIA, current,
                    desc.toString(), extensions.toArray(new String[0]));
            if (chosen == null) return;

            String path = chosen.getAbsolutePath();
            Kind kind = Kind.of(path);
            // Il filtro del browser propone solo i tipi ammessi, ma il nome si può anche
            // digitare: se quello che ne esce non ci sta, si dice perché.
            if (kind == null || !allowed.contains(kind)) {
                JOptionPane.showMessageDialog(owner,
                        Messages.t(kind == null ? "drive.entry.unknown" : "drive.entry.rejected",
                                chosen.getName()),
                        Messages.t("app.title"), JOptionPane.WARNING_MESSAGE);
                return;
            }
            Item item = new Item(kind, path, isZipPath(path));
            if (index < items.size()) {
                items.set(index, item);
            } else {
                items.add(item);
            }
            rebuild();
        }
    }

    private static final class LockIcon implements Icon {
        private final boolean closed;

        LockIcon(boolean closed) {
            this.closed = closed;
        }

        @Override
        public int getIconWidth() {
            return 16;
        }

        @Override
        public int getIconHeight() {
            return 16;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(c.isEnabled() ? new Color(0x20, 0x22, 0x26) : new Color(0x64, 0x68, 0x70));

                if (closed) {
                    g2.setStroke(new java.awt.BasicStroke(2f, java.awt.BasicStroke.CAP_ROUND,
                            java.awt.BasicStroke.JOIN_ROUND));
                    g2.drawArc(x + 4, y + 2, 8, 9, 0, 180);
                    g2.drawLine(x + 4, y + 7, x + 4, y + 9);
                    g2.drawLine(x + 12, y + 7, x + 12, y + 9);
                } else {
                    g2.setStroke(new java.awt.BasicStroke(2f, java.awt.BasicStroke.CAP_ROUND,
                            java.awt.BasicStroke.JOIN_ROUND));
                    g2.drawArc(x + 1, y + 1, 8, 9, 18, 170);
                    g2.drawLine(x + 1, y + 6, x + 1, y + 8);
                }

                g2.fillRoundRect(x + 3, y + 8, 11, 7, 2, 2);
                g2.setColor(new Color(0xE6, 0xE8, 0xEC));
                g2.fillOval(x + 7, y + 10, 3, 3);
            } finally {
                g2.dispose();
            }
        }
    }

    // =========================================================================
    // File chooser
    // =========================================================================

    private static void browse(JDialog parent, AmigaConfig config, BrowseKind kind,
            JTextField target, String desc, String... extensions) {
        File selected = chooseFile(parent, config, kind, target.getText(), desc, extensions);
        if (selected != null) {
            target.setText(selected.getAbsolutePath());
            target.setCaretPosition(target.getText().length());
        }
    }

    /**
     * Apre il browser dei file e ritorna quello scelto, o {@code null} se l'utente
     * annulla. Parte dall'ultima cartella usata da questo {@code kind} (o da quella del
     * file corrente) e, a scelta avvenuta, la ricorda.
     */
    private static File chooseFile(JDialog parent, AmigaConfig config, BrowseKind kind,
            String currentPath, String desc, String... extensions) {
        // Istanza condivisa e con elenco cartelle veloce: vedi FastFileChooser.
        JFileChooser fc = FastFileChooser.acquire();
        fc.setDialogTitle(desc);

        String lastDir = switch (kind) {
            case ROM -> config.lastRomDirectory();
            case HDF -> config.lastHdfDirectory();
            case WHDLOAD -> config.lastWhdloadDirectory();
            case MEDIA -> config.lastMediaDirectory();
            default  -> config.lastFloppyDirectory();
        };
        boolean usingLastDir = false;
        if (!lastDir.isEmpty()) {
            File dir = new File(lastDir);
            if (dir.isDirectory()) {
                fc.setCurrentDirectory(dir);
                usingLastDir = true;
            }
        }

        String current = currentPath == null ? "" : currentPath.trim();
        File currentFile = current.isEmpty() ? null : new File(current);
        if (!usingLastDir && currentFile != null) {
            File f = currentFile;
            if (f.getParentFile() != null && f.getParentFile().isDirectory()) {
                fc.setCurrentDirectory(f.getParentFile());
            }
            if (f.exists()) fc.setSelectedFile(f);
        } else if (currentFile != null && currentFile.exists()) {
            File parentDir = currentFile.getParentFile();
            if (parentDir != null && parentDir.equals(fc.getCurrentDirectory())) {
                fc.setSelectedFile(currentFile);
            }
        }
        if (extensions.length > 0) {
            fc.setFileFilter(new FileNameExtensionFilter(desc, extensions));
        }
        // L'istanza è riusata: senza rilettura mostrerebbe l'elenco del giro precedente,
        // che nel frattempo può essere cambiato sul disco.
        fc.rescanCurrentDirectory();
        if (fc.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) return null;
        File selected = fc.getSelectedFile();
        File parentDir = selected.getParentFile();
        if (parentDir != null) {
            rememberLastDirectory(config, kind, parentDir);
        }
        return selected;
    }

    private static void rememberLastDirectory(AmigaConfig config, BrowseKind kind, File dir) {
        switch (kind) {
            case ROM -> config.setLastRomDirectory(dir.getAbsolutePath());
            case MEDIA -> config.setLastMediaDirectory(dir.getAbsolutePath());
            case HDF -> config.setLastHdfDirectory(dir.getAbsolutePath());
            case WHDLOAD -> config.setLastWhdloadDirectory(dir.getAbsolutePath());
            default  -> config.setLastFloppyDirectory(dir.getAbsolutePath());
        }
        try {
            config.save();
        } catch (IOException ex) {
            System.err.println("[CONFIG] Impossibile salvare l'ultima cartella del file browser: "
                    + ex.getMessage());
        }
    }
}
