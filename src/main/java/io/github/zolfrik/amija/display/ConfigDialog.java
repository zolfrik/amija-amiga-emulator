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

import io.github.zolfrik.amija.common.AppHome;
import io.github.zolfrik.amija.common.Chipset;
import io.github.zolfrik.amija.common.VideoMode;
import io.github.zolfrik.amija.common.Version;
import io.github.zolfrik.amija.config.AmigaConfig;
import io.github.zolfrik.amija.display.render.RendererType;
import io.github.zolfrik.amija.i18n.Messages;
import io.github.zolfrik.amija.input.JoystickKeyMap;
import io.github.zolfrik.amija.input.PortDevice;
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
import javax.swing.border.TitledBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
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
    public enum Result { CANCEL, OK, RESTART, SOFT_RESET, QUIT }

    private static final Dimension DRIVE_TOOL_BUTTON_SIZE = new Dimension(34, 26);
    private static final Icon LOCK_CLOSED_ICON = new LockIcon(true);
    private static final Icon LOCK_OPEN_ICON = new LockIcon(false);

    private enum BrowseKind { FLOPPY, ROM, HDF, WHDLOAD }

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
     */
    public static Result show(JFrame parent, AmigaConfig config,
            java.util.function.Supplier<java.io.File> onScreenshot,
            java.util.function.BiConsumer<String, Float> shaderParamUpdater,
            java.util.function.Supplier<String> onSaveState,
            java.util.function.Supplier<String> onLoadState) {
        final Result[] result = { Result.CANCEL };

        String title = Messages.t("dialog.title") + " - " + Version.version();
        JDialog dialog = new JDialog(parent, title, true);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        // -------------------------------------------------------------------
        // Sezione: Sistema
        // -------------------------------------------------------------------
        JPanel system = section(Messages.t("section.system"));

        JTextField kickField = textField(config.kickstartPath());
        JButton kickBrowse = browseButton();
        kickBrowse.addActionListener(e -> browse(dialog, config, BrowseKind.ROM, kickField,
                Messages.t("chooser.rom"), "rom"));
        addRow(system, 0, Messages.t("label.kickstart"), kickField, kickBrowse);

        JComboBox<Integer> chipCombo = kbCombo(CHIP_RAM_OPTIONS, config.chipRamKb());
        addRow(system, 1, Messages.t("label.chipram"), chipCombo, null);

        JComboBox<Integer> slowCombo = kbCombo(SLOW_RAM_OPTIONS, config.slowRamKb());
        addRow(system, 2, Messages.t("label.slowram"), slowCombo, null);

        JComboBox<Integer> fastCombo = kbCombo(FAST_RAM_OPTIONS, config.fastRamKb());
        addRow(system, 3, Messages.t("label.fastram"), fastCombo, null);

        JComboBox<String> langCombo = new JComboBox<>(new String[]{ "English", "Italiano" });
        langCombo.setSelectedIndex("it".equalsIgnoreCase(config.language()) ? 1 : 0);
        langCombo.setMaximumSize(new Dimension(160, 26));
        addRow(system, 4, Messages.t("label.language"), langCombo, null);

        // Modalita' video PAL/NTSC: il cambio richiede il riavvio dell'emulatore
        // (gestito da Amija: un OK con modalita' diversa forza un cold restart).
        JComboBox<VideoMode> videoModeCombo = new JComboBox<>(VideoMode.values());
        videoModeCombo.setSelectedItem(config.videoMode());
        videoModeCombo.setMaximumSize(new Dimension(160, 26));
        addRow(system, 5, Messages.t("label.videomode"), videoModeCombo, null);

        // Chipset OCS/ECS: come la modalita' video, il cambio richiede un cold restart.
        JComboBox<Chipset> chipsetCombo = new JComboBox<>(Chipset.values());
        chipsetCombo.setSelectedItem(config.chipset());
        chipsetCombo.setMaximumSize(new Dimension(160, 26));
        addRow(system, 6, "Chipset", chipsetCombo, null);

        // Periferica collegata a ciascuna game port (mouse / joystick reale / joystick
        // da tastiera / vuota). Applicata a caldo: nessun riavvio richiesto.
        // Con "Joystick (via tastiera)" compare accanto alla tendina il pulsante "..."
        // che apre la configurazione dei tasti di quella porta. Le due mappe sono
        // modificate in copia e scritte nella configurazione solo alla conferma.
        JoystickKeyMap[] joyKeyMaps = { config.joystickKeyMap(0), config.joystickKeyMap(1) };

        // Il pulsante va nella colonna di coda della sezione, la stessa del "..." del
        // Kickstart: resta quindi allineato a quello e la tendina mantiene la propria
        // larghezza sia che il pulsante ci sia sia che non ci sia.
        JComboBox<PortDevice> port0Combo = deviceCombo(config.portDevice(0));
        JButton port0Keys = joystickKeysButton();
        addRow(system, 7, Messages.t("label.port0"), port0Combo, port0Keys);

        JComboBox<PortDevice> port1Combo = deviceCombo(config.portDevice(1));
        JButton port1Keys = joystickKeysButton();
        addRow(system, 8, Messages.t("label.port1"), port1Combo, port1Keys);

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
            sync.run();

            JPanel trailing = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
            trailing.setOpaque(false);
            trailing.add(diskBrowse);
            trailing.add(diskReadOnly);
            trailing.add(diskEject);

            hdEnabled[i] = enabled;
            hdDisk[i] = disk;
            hdReadOnly[i] = diskReadOnly;
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
        whdSync.run();

        JPanel whdTrailing = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
        whdTrailing.setOpaque(false);
        whdTrailing.add(whdBrowse);
        whdTrailing.add(whdEject);
        addRow(whd, 0, whdEnabled, whdFile, whdTrailing);

        // -------------------------------------------------------------------
        // Sezione: file opzionali di terze parti (assistente di download).
        // Materiale che AmiJa non può ridistribuire e che l'utente può farsi
        // scaricare dai siti ufficiali. Vedi THIRD-PARTY-NOTICES.md §5.
        // -------------------------------------------------------------------
        JPanel assets = section(Messages.t("section.assets"));
        JButton assetsOpen = button(Messages.t("assets.open"), false);
        assetsOpen.addActionListener(e -> AssetDownloadDialog.show(dialog, config));
        addRow(assets, 0, Messages.t("assets.label"), null, assetsOpen);

        // -------------------------------------------------------------------
        // Sezione: Audio / Video
        // -------------------------------------------------------------------
        JPanel av = section(Messages.t("section.av"));

        // Rendering (prima voce): software (Java2D) o OpenGL (LWJGL, predisposto agli shader).
        JComboBox<RendererType> rendererCombo = new JComboBox<>(RendererType.values());
        rendererCombo.setSelectedItem(config.renderer());
        rendererCombo.setMaximumSize(new Dimension(220, 26));
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
        addRow(av, 0, Messages.t("label.renderer"), rendererCombo, null);

        // Shader RetroArch: popolato coi .glslp nella cartella "shaders" (+ "Nessuno").
        // Modificabile solo col renderer OpenGL.
        JComboBox<String> shaderCombo = new JComboBox<>(scanShaderNames());
        String curShader = config.shader();
        boolean shaderPresent = false;
        for (int i = 0; i < shaderCombo.getItemCount(); i++) {
            if (shaderCombo.getItemAt(i).equals(curShader)) { shaderPresent = true; break; }
        }
        shaderCombo.setSelectedItem(shaderPresent ? curShader : "none");
        shaderCombo.setMaximumSize(new Dimension(220, 26));
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
        addRow(av, 1, Messages.t("label.shader"), shaderRow, null);

        JComboBox<AmigaWindowMode> windowModeCombo = new JComboBox<>(AmigaWindowMode.values());
        windowModeCombo.setSelectedItem(config.windowMode());
        windowModeCombo.setMaximumSize(new Dimension(220, 26));
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
        addRow(av, 2, Messages.t("label.windowMode"), windowModeCombo, null);

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
        monitorCombo.setMaximumSize(new Dimension(220, 26));
        addRow(av, 3, Messages.t("label.monitor"), monitorCombo, null);

        JSpinner scalingSpinner = new JSpinner(
                new SpinnerNumberModel(config.integerScaling(), 1, null, 1));
        scalingSpinner.setPreferredSize(new Dimension(56, 26));
        JPanel scalingWrap = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        scalingWrap.setOpaque(false);
        scalingWrap.add(scalingSpinner);
        addRow(av, 4, Messages.t("label.integerScaling"), scalingWrap, null);

        JCheckBox overscanCheck = check(Messages.t("label.overscan"), config.overscanEnabled());
        addRow(av, 5, overscanCheck, null, null);

        JCheckBox ledCheck = check(Messages.t("label.leds"), config.ledsVisible());
        addRow(av, 6, ledCheck, null, null);

        JSpinner soundSpinner = new JSpinner(
                new SpinnerNumberModel(config.floppySoundVolume(), 0, 10, 1));
        soundSpinner.setPreferredSize(new Dimension(56, 26));
        JPanel soundWrap = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        soundWrap.setOpaque(false);
        soundWrap.add(soundSpinner);
        addRow(av, 7, Messages.t("label.sound"), soundWrap, null);

        // -------------------------------------------------------------------
        // Contenuto scorrevole
        // -------------------------------------------------------------------
        JPanel content = new JPanel();
        content.setBackground(BG);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        content.add(header());
        content.add(Box.createVerticalStrut(10));
        content.add(system);
        content.add(Box.createVerticalStrut(12));
        content.add(drives);
        content.add(Box.createVerticalStrut(12));
        content.add(hds);
        content.add(Box.createVerticalStrut(12));
        content.add(whd);
        content.add(Box.createVerticalStrut(12));
        content.add(assets);
        content.add(Box.createVerticalStrut(12));
        content.add(av);
        content.add(Box.createVerticalGlue());

        JScrollPane scroll = new JScrollPane(content,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getViewport().setBackground(BG);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        // Dimensione visibile dell'area scorrevole: se il contenuto è più alto,
        // compare la barra di scorrimento verticale.
        scroll.setPreferredSize(new Dimension(580, 460));

        // -------------------------------------------------------------------
        // Barra dei pulsanti
        // -------------------------------------------------------------------
        JButton screenshot = button(Messages.t("button.screenshot"), false);
        JButton saveState = button(Messages.t("button.savestate"), false);
        JButton loadState = button(Messages.t("button.loadstate"), false);
        JButton softReset = button(Messages.t("button.softreset"), false);
        JButton restart   = button(Messages.t("button.restart"), false);
        JButton quit      = button(Messages.t("button.quit"), false);
        JButton cancel    = button(Messages.t("button.cancel"), false);
        JButton ok        = button(Messages.t("button.ok"), true);

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

        // "Save State"/"Load State": snapshot dell'intera macchina nello slot unico
        // (savestate/state1.a5s). La macchina è in pausa mentre il popup è aperto; il
        // callback esegue l'operazione e ritorna il messaggio di esito da mostrare.
        saveState.addActionListener(e -> {
            String msg = onSaveState != null ? onSaveState.get() : null;
            JOptionPane.showMessageDialog(dialog,
                    msg != null ? msg : Messages.t("savestate.save.fail", "?"),
                    Messages.t("app.title"), JOptionPane.INFORMATION_MESSAGE);
        });
        loadState.addActionListener(e -> {
            String msg = onLoadState != null ? onLoadState.get() : null;
            JOptionPane.showMessageDialog(dialog,
                    msg != null ? msg : Messages.t("savestate.load.fail", "?"),
                    Messages.t("app.title"), JOptionPane.INFORMATION_MESSAGE);
        });

        Runnable applyConfig = () -> {
            config.setKickstartPath(kickField.getText());
            config.setChipRamKb((Integer) chipCombo.getSelectedItem());
            config.setSlowRamKb((Integer) slowCombo.getSelectedItem());
            config.setFastRamKb((Integer) fastCombo.getSelectedItem());
            for (int i = 0; i < 4; i++) {
                config.setDriveEnabled(i, driveEnabled[i].isSelected());
                config.setDriveDisk(i, driveDisk[i].getText());
                config.setDriveReadOnly(i, driveReadOnly[i].isSelected() || isZipPath(driveDisk[i].getText()));
            }
            for (int i = 0; i < hdEnabled.length; i++) {
                config.setHdEnabled(i, hdEnabled[i].isSelected());
                config.setHdDisk(i, hdDisk[i].getText());
                config.setHdReadOnly(i, hdReadOnly[i].isSelected());
            }
            config.setWhdloadEnabled(whdEnabled.isSelected());
            config.setWhdloadFile(whdFile.getText());
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
            config.setVideoMode((VideoMode) videoModeCombo.getSelectedItem());
            config.setChipset((Chipset) chipsetCombo.getSelectedItem());
            config.setIntegerScaling((Integer) scalingSpinner.getValue());
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
        buttons.setBorder(BorderFactory.createEmptyBorder(10, 16, 10, 16));

        JPanel left = new JPanel();
        left.setOpaque(false);
        left.add(screenshot);
        left.add(saveState);
        left.add(loadState);
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
        dialog.setMinimumSize(new Dimension(560, 320));
        dialog.setLocationRelativeTo(parent);
        prepareDialogFocus(dialog, ok);
        dialog.setVisible(true); // bloccante (modale)

        return result[0];
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
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel sub = new JLabel(Messages.t("header.subtitle"));
        sub.setForeground(new Color(0xA8, 0xAC, 0xB3));
        sub.setFont(sub.getFont().deriveFont(Font.PLAIN, 11f));
        sub.setAlignmentX(Component.LEFT_ALIGNMENT);

        p.add(title);
        p.add(Box.createVerticalStrut(2));
        p.add(sub);
        return p;
    }

    private static JPanel section(String title) {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBackground(PANEL_BG);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        TitledBorder tb = BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(0x45, 0x49, 0x50)), title);
        tb.setTitleColor(ACCENT);
        tb.setTitleFont(p.getFont().deriveFont(Font.BOLD, 13f));
        p.setBorder(BorderFactory.createCompoundBorder(tb,
                BorderFactory.createEmptyBorder(8, 10, 10, 10)));
        return p;
    }

    /** Aggiunge una riga con etichetta testuale, campo centrale e (opzionale) browse. */
    private static void addRow(JPanel panel, int row, String label, JComponent field, JComponent trailing) {
        JLabel l = new JLabel(label);
        l.setForeground(FG);
        addRow(panel, row, l, field, trailing);
    }

    /** Aggiunge una riga con componente iniziale (es. checkbox), campo e browse. */
    private static void addRow(JPanel panel, int row, JComponent lead, JComponent field, JComponent trailing) {
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridy = row;
        gc.insets = new Insets(4, 4, 4, 4);
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
                BorderFactory.createLineBorder(new Color(0x45, 0x49, 0x50)),
                BorderFactory.createEmptyBorder(3, 5, 3, 5)));
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
        b.setMargin(new Insets(2, 4, 2, 4));
        b.setPreferredSize(DRIVE_TOOL_BUTTON_SIZE);
        b.setMinimumSize(DRIVE_TOOL_BUTTON_SIZE);
        b.setMaximumSize(DRIVE_TOOL_BUTTON_SIZE);
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
        combo.setMaximumSize(new Dimension(200, 26));
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
        combo.setMaximumSize(new Dimension(160, 26));
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
        // Istanza condivisa e con elenco cartelle veloce: vedi FastFileChooser.
        JFileChooser fc = FastFileChooser.acquire();
        fc.setDialogTitle(desc);

        String lastDir = switch (kind) {
            case ROM -> config.lastRomDirectory();
            case HDF -> config.lastHdfDirectory();
            case WHDLOAD -> config.lastWhdloadDirectory();
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

        String current = target.getText().trim();
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
        if (fc.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
            File selected = fc.getSelectedFile();
            target.setText(selected.getAbsolutePath());
            target.setCaretPosition(target.getText().length());
            File parentDir = selected.getParentFile();
            if (parentDir != null) {
                rememberLastDirectory(config, kind, parentDir);
            }
        }
    }

    private static void rememberLastDirectory(AmigaConfig config, BrowseKind kind, File dir) {
        switch (kind) {
            case ROM -> config.setLastRomDirectory(dir.getAbsolutePath());
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
