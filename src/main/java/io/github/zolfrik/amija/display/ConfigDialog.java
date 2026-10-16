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

package io.github.zolfrik.amija.display;

import static io.github.zolfrik.amija.display.UiScaling.derive;
import static io.github.zolfrik.amija.display.UiScaling.dim;
import static io.github.zolfrik.amija.display.UiScaling.pad;
import static io.github.zolfrik.amija.display.UiScaling.px;

import io.github.zolfrik.amija.assets.AssetCatalog;
import io.github.zolfrik.amija.assets.AssetPackage;
import io.github.zolfrik.amija.common.AppHome;
import io.github.zolfrik.amija.common.Chipset;
import io.github.zolfrik.amija.cpu.CpuModel;
import io.github.zolfrik.amija.common.VideoMode;
import io.github.zolfrik.amija.config.AmigaConfig;
import io.github.zolfrik.amija.config.HardwareRules;
import io.github.zolfrik.amija.display.render.RendererType;
import io.github.zolfrik.amija.i18n.Messages;
import io.github.zolfrik.amija.input.JoystickKeyMap;
import io.github.zolfrik.amija.input.PortDevice;
import io.github.zolfrik.amija.net.RemotePlayControl;
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
import javax.swing.JTabbedPane;
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
 * Modal popup (F12) for editing {@code amiga.properties}.
 *
 * <p>Exposes every configurable value with the right control: text fields with a browse
 * button ({@code ...}) for absolute paths, drop-downs for Chip/Slow RAM, checkboxes for the
 * booleans, a spinner for the floppy sound volume. The contents are organised in sections
 * inside a vertically scrolling area.
 *
 * <p>Returns the action chosen by the user ({@link Result}); on {@link Result#OK} the
 * changed values are applied directly to the {@link AmigaConfig} passed in. The caller
 * saves the configuration and applies live only the floppy changes
 * (insert/replace/eject) without restarting the machine; the other settings (ROM, RAM,
 * language) take effect only at the next restart.
 */
public final class ConfigDialog {

    /** Outcome of the window: the action the caller must perform. */
    public enum Result {
        CANCEL, OK, RESTART, SOFT_RESET, QUIT,
        /**
         * The user asked to join a host (or to leave it). The caller performs the
         * transition: a guest emulates nothing, so joining <b>unmounts the machine</b>, and
         * a machine is not unmounted from inside a modal dialog while the emulation thread
         * runs. The popup's changes have not been applied: what counts is the address
         * recorded in {@code RemotePlayControl}.
         */
        REMOTE_PLAY_TRANSITION,
        /**
         * The state to load requires a machine different from the running one. The popup
         * closed <b>without</b> applying the pending changes: what counts now is the
         * configuration kept inside the slot, which the caller restores before rebuilding
         * the machine and retrying the load.
         */
        STATE_RESTORE
    }

    /**
     * Outcome of "Load State" as seen by the popup.
     *
     * @param message      text to show the user; {@code null} if there is nothing to say
     *                     yet because the operation continues elsewhere
     * @param needsRestart {@code true} if the load requires rebuilding the machine: the
     *                     popup closes with {@link Result#STATE_RESTORE} and the caller does
     *                     the rest
     */
    public record LoadStateOutcome(String message, boolean needsRestart) {

        /** Operation finished here: only the outcome remains to report. */
        public static LoadStateOutcome done(String message) {
            return new LoadStateOutcome(message, false);
        }

        /** Operation to resume outside the popup, after rebuilding the machine. */
        public static LoadStateOutcome restart() {
            return new LoadStateOutcome(null, true);
        }
    }

    /**
     * How long a tooltip stays on screen before disappearing by itself: twice Swing's 4 s.
     * Here tooltips are not labels to recognise but explanations to read, and four seconds
     * are not enough for a whole sentence.
     *
     * <p>An absolute value, not "the default times two", on purpose: {@link ToolTipManager}
     * is a JVM singleton, and doubling the current value at every opening of the popup would
     * grow it without end.
     */
    private static final int TOOLTIP_DISMISS_MS = 8000;

    // A method, not a constant: the size depends on the interface scale, which must be read
    // when the component is built, not when the class loads.
    private static Dimension driveToolButtonSize() {
        return dim(34, 26);
    }

    private static final Icon LOCK_CLOSED_ICON = new LockIcon(true);
    private static final Icon LOCK_OPEN_ICON = new LockIcon(false);

    /** File sources of the popup; each one remembers its own last folder. */
    private enum BrowseKind { FLOPPY, ROM, HDF, WHDLOAD, MEDIA }

    // Sensible/possible values for the memory drop-downs (in KB).
    private static final Integer[] CHIP_RAM_OPTIONS = { 256, 512, 1024, 2048 };
    private static final Integer[] SLOW_RAM_OPTIONS = { 0, 512, 1024, 1536, 1792 };
    // Fast RAM: powers of two from 512 KB to 8 MB (Zorro II AutoConfig constraint), 0=none.
    private static final Integer[] FAST_RAM_OPTIONS = { 0, 512, 1024, 2048, 4096, 8192 };

    // Palette sobria/professionale.
    private static final Color BG        = new Color(0x2B, 0x2D, 0x31);
    private static final Color PANEL_BG  = new Color(0x33, 0x36, 0x3B);
    private static final Color FG        = new Color(0xE6, 0xE6, 0xE8);
    private static final Color ACCENT    = new Color(0x4D, 0x9D, 0xE0);

    /**
     * The media mounted on the running machine: how many floppy drives exist, which hard
     * disk images are connected and which WHDLoad archive is running.
     *
     * <p>It is the yardstick with which the single list tells what is already running from
     * what the user is adding: adding a drive is a hardware change, and the hardware of a
     * running machine does not change. Entries that require new hardware are marked with an
     * asterisk and take effect at the next start.
     *
     * @param floppyDrives number of floppy drives connected
     * @param hdDisks      images of the mounted hard disks, in unit order
     * @param whdloadFile  WHDLoad archive running; empty string if none
     */
    public record DriveHardware(int floppyDrives, java.util.List<String> hdDisks,
            String whdloadFile) {

        public DriveHardware {
            hdDisks = java.util.List.copyOf(hdDisks);
            whdloadFile = whdloadFile == null ? "" : whdloadFile.trim();
        }
    }

    private ConfigDialog() {}

    /**
     * Shows the dialog modally and returns the chosen action. If the outcome is
     * {@link Result#OK} the changes have already been written to {@code config}.
     *
     * @param onScreenshot        action of the "Take Screenshot" button; returns the file
     *                            saved (or {@code null} on error).
     * @param shaderParamUpdater  callback {@code (name, value)} that applies the OpenGL
     *                            shader parameters live ("..." popup).
     * @param onSaveState         saves the slot and returns the outcome message.
     * @param onLoadState         loads the slot; see {@link LoadStateOutcome}.
     * @param hardware            the media of the running machine, or {@code null} if there
     *                            is none: without a machine nothing is deferred and the list
     *                            marks nothing.
     */
    public static Result show(JFrame parent, AmigaConfig config,
            java.util.function.Supplier<java.io.File> onScreenshot,
            java.util.function.BiConsumer<String, Float> shaderParamUpdater,
            java.util.function.Supplier<String> onSaveState,
            java.util.function.Supplier<LoadStateOutcome> onLoadState,
            RemotePlayControl remotePlay, DriveHardware hardware) {
        final Result[] result = { Result.CANCEL };

        // Before building anything: the tooltips of this popup are meant to be read, not
        // glimpsed. The ToolTipManager is shared by the whole JVM, so it applies to the child
        // windows too (joystick keys, shader parameters, downloads).
        ToolTipManager.sharedInstance().setDismissDelay(TOOLTIP_DISMISS_MS);

        JDialog dialog = new JDialog(parent, Messages.t("dialog.title"), true);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        // -------------------------------------------------------------------
        // Section: System
        // -------------------------------------------------------------------
        JPanel system = section(Messages.t("section.system"));

        // "Expert mode" (first entry, off by default): decides how much popup the user sees.
        // Off, the machine configures itself from the chosen media; what remains is the
        // Kickstart, split into the two ROMs the automation chooses between, and the single
        // list of drives. On, everything appears and the user decides. The wiring is further
        // down (syncAuto), after the sections it depends on.
        JCheckBox expertCheck = check(Messages.t("label.expert"), !config.autoEnabled());
        tip("label.expert", expertCheck);
        addRow(system, 0, expertCheck, null, null);
        java.util.function.BooleanSupplier simpleMode = () -> !expertCheck.isSelected();

        // Kickstart chosen by hand: visible in Expert only.
        JLabel kickLabel = label(Messages.t("label.kickstart"));
        JTextField kickField = textField(config.storedKickstartPath());
        JButton kickBrowse = browseButton();
        kickBrowse.addActionListener(e -> browse(dialog, config, BrowseKind.ROM, kickField,
                Messages.t("chooser.rom"), "rom"));
        tip("label.kickstart", kickLabel, kickField);
        addRow(system, 1, kickLabel, kickField, kickBrowse);

        // The two ROMs the automatic configuration chooses between: visible only with Expert
        // off, i.e. when the emulator makes that choice. They have their own key in
        // amiga.properties but share with the field above the last folder visited
        // (BrowseKind.ROM): the Kickstarts all live together.
        //
        // If neither is set, the configuration predates Auto mode: the first then starts
        // from the Kickstart chosen by hand, the one the machine is already running with.
        // Otherwise the user would seem to have lost their ROM.
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

        // The six entries below describe the machine's hardware. With Expert off the
        // emulator decides them and the rows disappear entirely: someone who does not know
        // the Amiga has no way to choose between OCS and ECS, and seeing them read-only does
        // not help. They reappear, editable, as soon as Expert is switched on.

        // Processor (event-driven 68000/68010/68020): a change requires a cold restart. A
        // clocked model written by hand in the file shows as its event-driven twin.
        JComboBox<CpuModel> cpuCombo = new JComboBox<>(CpuModel.selectable());
        cpuCombo.setSelectedItem(config.cpuModel().toEventDriven());
        cpuCombo.setMaximumSize(dim(160, 26));
        cpuCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof CpuModel model) setText(model.partNumber());
                return this;
            }
        });
        JLabel cpuLabel = addRow(system, 4, "label.cpu", cpuCombo, null);

        // OCS/ECS/AGA chipset: like the processor, a change requires a cold restart.
        JComboBox<Chipset> chipsetCombo = new JComboBox<>(Chipset.values());
        chipsetCombo.setSelectedItem(config.chipset());
        chipsetCombo.setMaximumSize(dim(160, 26));
        JLabel chipsetLabel = addRow(system, 5, "label.chipset", chipsetCombo, null);

        JComboBox<Integer> chipCombo = kbCombo(CHIP_RAM_OPTIONS, config.chipRamKb());
        JLabel chipLabel = addRow(system, 6, "label.chipram", chipCombo, null);

        JComboBox<Integer> slowCombo = kbCombo(SLOW_RAM_OPTIONS, config.slowRamKb());
        JLabel slowLabel = addRow(system, 7, "label.slowram", slowCombo, null);

        JComboBox<Integer> fastCombo = kbCombo(FAST_RAM_OPTIONS, config.fastRamKb());
        JLabel fastLabel = addRow(system, 8, "label.fastram", fastCombo, null);

        // PAL/NTSC video mode: changing it requires restarting the emulator (handled by
        // Amija: an OK with a different mode forces a cold restart).
        JComboBox<VideoMode> videoModeCombo = new JComboBox<>(VideoMode.values());
        videoModeCombo.setSelectedItem(config.videoMode());
        videoModeCombo.setMaximumSize(dim(160, 26));
        JLabel videoLabel = addRow(system, 9, "label.videomode", videoModeCombo, null);

        // -------------------------------------------------------------------
        // Section: Game ports
        // -------------------------------------------------------------------
        // Device plugged into each game port (mouse / real joystick / keyboard joystick /
        // empty). Applied live: no restart needed. With "Joystick (via keyboard)" the "..."
        // button appears next to the drop-down and opens that port's key configuration. The
        // two maps are edited as copies and written to the configuration only on confirm.
        //
        // In remote play the two ports have TWO OWNERS: the host gives one of them to the
        // guest, and from then on the guest decides what to plug into it and with which
        // keys. Each therefore has the drop-down of its own port and not of the other's;
        // otherwise whoever confirms the popup last would override the other's choice.
        JPanel ports = section(Messages.t("section.gameports"));
        JoystickKeyMap[] joyKeyMaps = { config.joystickKeyMap(0), config.joystickKeyMap(1) };

        // The button goes in the section's trailing column, the same in which the other
        // sections put their "...": it stays aligned with them and the drop-down keeps its
        // width whether the button is there or not.
        JComboBox<PortDevice> port0Combo = deviceCombo(config.portDevice(0));
        JButton port0Keys = joystickKeysButton();
        JLabel port0Label = addRow(ports, 0, "label.port0", port0Combo, port0Keys);

        JComboBox<PortDevice> port1Combo = deviceCombo(config.portDevice(1));
        JButton port1Keys = joystickKeysButton();
        JLabel port1Label = addRow(ports, 1, "label.port1", port1Combo, port1Keys);

        // A port switched off for no reason is a fault; with the reason written below it is
        // a rule. The row appears only when a session is really involved.
        JLabel portOwnerNote = label("");
        addRow(ports, 2, new JLabel(""), portOwnerNote, null);

        port0Keys.addActionListener(e -> JoystickKeysDialog.show(dialog, 0, joyKeyMaps));
        port1Keys.addActionListener(e -> JoystickKeysDialog.show(dialog, 1, joyKeyMaps));

        // A single place where the two rows realign: who owns which port, and the keys
        // button, which appears only on one's OWN port set to the keyboard joystick. Called
        // by the drop-downs and (because guest and host come and go while the popup is open)
        // by the timer of the "Remote play" section.
        Runnable syncPorts = () -> {
            int remotePort = remotePlay == null ? -1 : remotePlay.remoteGamePort();
            boolean hosting = remotePlay != null && remotePlay.isHosting();
            boolean guest = remotePlay != null && remotePlay.isGuest();
            // Hosting, the port given away is lost; as a guest only one's own port exists.
            boolean[] mine = {
                !(hosting && remotePort == 0) && !(guest && remotePort != 0),
                !(hosting && remotePort == 1) && !(guest && remotePort != 1),
            };
            port0Combo.setEnabled(mine[0]);
            port1Combo.setEnabled(mine[1]);
            port0Label.setEnabled(mine[0]);
            port1Label.setEnabled(mine[1]);
            setJoystickKeysButtonVisible(port0Keys, mine[0]
                    && port0Combo.getSelectedItem() == PortDevice.JOYSTICK_KEYBOARD);
            setJoystickKeysButtonVisible(port1Keys, mine[1]
                    && port1Combo.getSelectedItem() == PortDevice.JOYSTICK_KEYBOARD);

            String note = null;
            if (guest && remotePort >= 0) {
                note = Messages.t("msg.ports.guestowns", Messages.t(portName(remotePort)),
                        Messages.t(portName(1 - remotePort)));
            } else if (hosting && remotePort >= 0) {
                note = Messages.t("msg.ports.hostgaveaway", Messages.t(portName(remotePort)));
            }
            portOwnerNote.setText(note == null ? ""
                    : "<html><body style='width: 320px'>" + note + "</body></html>");
            setRowVisible(note != null, portOwnerNote);
        };
        port0Combo.addActionListener(e -> syncPorts.run());
        port1Combo.addActionListener(e -> syncPorts.run());
        syncPorts.run();

        // -------------------------------------------------------------------
        // Section: Floppy drives
        // -------------------------------------------------------------------
        JPanel drives = section(Messages.t("section.drives"));
        JCheckBox[] driveEnabled = new JCheckBox[4];
        JTextField[] driveDisk   = new JTextField[4];
        JToggleButton[] driveReadOnly = new JToggleButton[4];
        // The functions that realign a row's controls to its state. Also needed from outside:
        // when Auto mode's single list pours its media in here, setSelected/setText fire no
        // events and nobody would call them.
        java.util.List<Runnable> classicSyncs = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            JCheckBox enabled = check(Messages.t("drive.connected", i), config.driveEnabled(i));
            JTextField disk = textField(config.driveDisk(i) == null ? "" : config.driveDisk(i));
            JButton diskBrowse = browseButton();
            JToggleButton diskReadOnly = readOnlyButton(config.driveReadOnly(i));
            JButton diskEject = ejectButton();

            // The disk path is editable only if the drive is connected; the eject button is
            // active only if there really is a disk.
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
            // Eject: clears the field. The difference from the current state is applied to
            // the real disk when confirming with OK (the actual eject).
            diskEject.addActionListener(e -> {
                disk.setText("");
                disk.setCaretPosition(0);
                sync.run();
            });
            enabled.addActionListener(e -> sync.run());
            classicSyncs.add(sync);
            sync.run();

            // "..." and "Eject" side by side in the trailing column (eject on the right).
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
        // Section: Hard disk (master / slave)
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
        // Section: WHDLoad (.lha archive started automatically)
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
            // Point of need: the archive chosen will not run without the WHDLoad system
            // files, which AmiJa cannot redistribute. Here the question makes sense, not at
            // the first start, where it would be asked even of those who will never use them.
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
        // Section: Drives, the single list of Auto mode, which replaces the three sections
        // above. See DriveList.
        // -------------------------------------------------------------------
        JPanel media = section(Messages.t("section.media"));
        DriveList driveList = new DriveList(dialog, config, media, hardware);
        driveList.seed(MediaState.fromConfig(config));

        // The media as the three classic sections see them, and how to put them back: the
        // two directions of the exchange with the single list.
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
            // setSelected/setText fire no events: the row controls must be realigned by
            // hand, or they would stay enabled as before.
            classicSyncs.forEach(Runnable::run);
        };

        // -------------------------------------------------------------------
        // Wiring of "Expert mode" (first entry of the System section).
        //
        // It is here, not up there, because it must know both views of the media: the three
        // classic sections and the single list. Switching Expert on or off swaps the views
        // passing along what the user chose, so a game just put in the list does not vanish
        // when changing one's mind about the mode.
        // -------------------------------------------------------------------
        Runnable syncAuto = () -> {
            boolean simple = simpleMode.getAsBoolean();
            expertCheck.setToolTipText(expertTip(config, simple));
            setRowVisible(!simple, kickLabel, kickField, kickBrowse);
            setRowVisible(simple, ks13Label, ks13Field, ks13Browse);
            setRowVisible(simple, ks31Label, ks31Field, ks31Browse);
            // Outside Expert the emulator decides the hardware: the rows are not needed.
            setRowVisible(!simple, chipLabel, chipCombo);
            setRowVisible(!simple, slowLabel, slowCombo);
            setRowVisible(!simple, fastLabel, fastCombo);
            setRowVisible(!simple, videoLabel, videoModeCombo);
            setRowVisible(!simple, chipsetLabel, chipsetCombo);
            setRowVisible(!simple, cpuLabel, cpuCombo);
            // A single list instead of the three sections per media type.
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
                // Switching Expert on brings back the system settings chosen by hand, left
                // untouched in the file for as long as the machine configured itself; the
                // media instead are those of the list.
                applyToClassic.accept(driveList.toMediaState());
                selectKb(chipCombo, config.storedChipRamKb());
                selectKb(slowCombo, config.storedSlowRamKb());
                selectKb(fastCombo, config.storedFastRamKb());
                videoModeCombo.setSelectedItem(config.storedVideoMode());
                chipsetCombo.setSelectedItem(config.storedChipset());
                cpuCombo.setSelectedItem(config.storedCpuModel().toEventDriven());
            }
            syncAuto.run();
        });

        // -------------------------------------------------------------------
        // Section: optional third-party files (download assistant). Material AmiJa cannot
        // redistribute and the user can have downloaded from the official sites. See
        // THIRD-PARTY-NOTICES.md section 5.
        //
        // If they are all installed already, the section has nothing left to offer: it is
        // omitted entirely (assets == null) instead of offering an assistant that would only
        // show "Already installed". The state is read at every opening of the popup, so the
        // section disappears by itself after a successful download.
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
        // Section: Audio / Video
        // -------------------------------------------------------------------
        JPanel av = section(Messages.t("section.av"));

        // Rendering (first entry): software (Java2D) or OpenGL (LWJGL, ready for shaders).
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

        // RetroArch shader: filled with the presets in the "shaders" folder (+ "None").
        // Editable only with the OpenGL renderer.
        JComboBox<String> shaderCombo = new JComboBox<>(scanShaderNames());
        String curShader = config.shader();
        boolean shaderPresent = false;
        for (int i = 0; i < shaderCombo.getItemCount(); i++) {
            if (shaderCombo.getItemAt(i).equals(curShader)) { shaderPresent = true; break; }
        }
        shaderCombo.setSelectedItem(shaderPresent ? curShader : "none");
        shaderCombo.setMaximumSize(dim(220, 26));
        shaderCombo.setMaximumRowCount(16);
        shaderCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if ("none".equals(value)) {
                    setText(Messages.t("shader.none"));
                } else if (value != null) {
                    // A pack like the Mega Bezel brings hundreds of nested presets and the
                    // full path does not fit in a 220px drop-down. The last two parts are
                    // shown (enough to tell apart names repeated in different folders) and
                    // the full path goes in the tooltip.
                    String name = value.toString();
                    int last = name.lastIndexOf('/');
                    int prev = last < 0 ? -1 : name.lastIndexOf('/', last - 1);
                    setText(prev >= 0 ? name.substring(prev + 1) : name);
                    setToolTipText(name);
                }
                return this;
            }
        });
        // "..." button for the parameters of the selected shader.
        JButton shaderParamsBtn = button("...", false);
        styleDriveToolButton(shaderParamsBtn);
        shaderParamsBtn.setToolTipText(Messages.t("shaderParams.tooltip"));

        // Enabled only with: OpenGL renderer, a real shader (not "none") AND the one
        // currently applied (so the live preview of the parameters matches).
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
            java.nio.file.Path preset =
                    io.github.zolfrik.amija.display.render.gl.ShaderCatalog.resolve(sel.toString());
            if (preset != null) {
                ShaderParamsDialog.show(dialog, preset, shaderParamUpdater);
            }
        });

        // BorderLayout: the drop-down (CENTER) widens to fill the column, the "..." (EAST)
        // stays at the right edge. So drop-down + button have the same total width as the
        // other drop-downs, aligned both left and right.
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

        // Monitor to open the emulator on: indices 0..n-1 of the connected monitors (0 =
        // primary). The configured value stays selectable even if that monitor is not
        // connected right now, so it is not lost.
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

        JSpinner soundSpinner = new JSpinner(
                new SpinnerNumberModel(config.floppySoundVolume(), 0, 10, 1));
        soundSpinner.setPreferredSize(dim(56, 26));
        JPanel soundWrap = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        soundWrap.setOpaque(false);
        soundWrap.add(soundSpinner);
        addRow(av, 6, "label.sound", soundWrap, null);

        // -------------------------------------------------------------------
        // Section: GUI, the look of AmiJa's popup and windows, not of the emulated machine:
        // language, interface scale and the LED panel, which is an ornament of the window and
        // not a device. Future interface entries go here rather than in "System".
        // -------------------------------------------------------------------
        JPanel gui = section(Messages.t("section.gui"));

        JComboBox<String> langCombo = new JComboBox<>(new String[]{ "English", "Italiano" });
        langCombo.setSelectedIndex("it".equalsIgnoreCase(config.language()) ? 1 : 0);
        langCombo.setMaximumSize(dim(160, 26));
        addRow(gui, 0, "label.language", langCombo, null);

        // Scale of the interface only. Fractional: unlike the integer scaling of the emulated
        // window, here interpolation takes nothing away from anyone. Shown as a percentage
        // because that is how Windows presents the same idea in its display settings; the
        // file keeps a number (1, 1.25, ...). A value written by hand outside the list is
        // added rather than lost.
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

        JCheckBox ledCheck = check(Messages.t("label.leds"), config.ledsVisible());
        tip("label.leds", ledCheck);
        addRow(gui, 2, ledCheck, null, null);

        // -------------------------------------------------------------------
        // Section: Saved state (the five slots, each with its picture and date)
        // -------------------------------------------------------------------
        JPanel state = stateSection(dialog, result, config, onSaveState, onLoadState);

        // -------------------------------------------------------------------
        // Section: Remote play (docs/remote-play-plan.md section 9)
        // -------------------------------------------------------------------
        // It comes after the game ports because it is about them: the guest takes one. The
        // dialog passes it its widgets instead of duplicating them: they are the same, and
        // two copies would diverge at the first palette tweak.
        // The realignment of the game ports rides on this section's timer instead of having
        // its own: what makes it necessary (a guest connecting or dropping while the popup is
        // open) is exactly what that timer watches.
        RemotePlaySection.Built remotePlaySection = remotePlay == null ? null
                : RemotePlaySection.build(config, remotePlay, ConfigDialog::section,
                        widgets(dialog, result), syncPorts);

        // -------------------------------------------------------------------
        // Scrollable content
        // -------------------------------------------------------------------
        JPanel content = new JPanel();
        content.setBackground(BG);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(pad(14, 16, 14, 16));
        content.add(header());
        content.add(Box.createVerticalStrut(10));
        // Guest mode: this machine emulates NOTHING (plan section 3), so there is no machine
        // to configure. The related sections are not hidden, they are not built into the
        // container at all: the local ones remain (video, audio, interface), which do matter
        // to the guest, because crop, scaling, shaders and fullscreen are its own choice.
        boolean guestMode = remotePlay != null && remotePlay.isGuest();
        if (!guestMode) {
            content.add(system);
            content.add(media);   // Auto mode: replaces the three sections below
            content.add(drives);
            content.add(hds);
            content.add(whd);
        }
        // The saved state comes after the media, after ALL of them, i.e. also after the
        // three per-type sections that in Expert mode replace the single list: so it sits
        // below the disks in both modes. It is the only section that sets nothing but acts on
        // the running machine, and it follows the disk it belongs to.
        if (!guestMode) {
            content.add(state);
        }
        // The game ports are there for the guest too, and that is the point: the port the
        // host gave it is filled by the guest. It needs one of the two rows (the other is the
        // host's and stays off), but without this section it would have no way to say it
        // plays with a joystick, or with which keys. They are its own, read from its
        // amiga.properties.
        content.add(ports);
        if (remotePlaySection != null) {
            content.add(remotePlaySection.panel());
        }
        content.add(av);
        content.add(gui);
        if (assets != null && !guestMode) {
            content.add(assets);
        }
        content.add(Box.createVerticalGlue());

        // Only now that the sections are in the container: the first pass decides which ones
        // are visible, and makes the popup consistent with the current mode.
        syncAuto.run();

        JScrollPane scroll = new JScrollPane(content,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getViewport().setBackground(BG);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        // Visible size of the scrolling area: if the content is taller, the vertical scroll
        // bar appears.
        scroll.setPreferredSize(dim(580, 460));

        // -------------------------------------------------------------------
        // Button bar
        // -------------------------------------------------------------------
        JButton about      = button(Messages.t("button.about"), false);
        JButton screenshot = button(Messages.t("button.screenshot"), false);
        JButton softReset = button(Messages.t("button.softreset"), false);
        JButton restart   = button(Messages.t("button.restart"), false);
        JButton quit      = button(Messages.t("button.quit"), false);
        JButton cancel    = button(Messages.t("button.cancel"), false);
        JButton ok        = button(Messages.t("button.ok"), true);

        // The buttons explain themselves too: the difference between "Soft Reset" and
        // "Restart" cannot be guessed, and someone opening the popup for the first time
        // deserves to know it before pressing.
        tip("button.about", about);
        tip("button.screenshot", screenshot);
        tip("button.softreset", softReset);
        tip("button.restart", restart);
        tip("button.quit", quit);
        tip("button.cancel", cancel);
        tip("button.ok", ok);

        // "About": logo, version, author and address of the project. It touches nothing in
        // the configuration, so it needs neither to apply nor to close.
        about.addActionListener(e -> AboutDialog.show(dialog));

        // "Take Screenshot": saves the game window (not the popup) to screenshots/. Works
        // both in software (Java2D composition) and in OpenGL (glReadPixels).
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

        java.util.function.Consumer<AmigaConfig> applyTo = cfg -> {
            cfg.setAutoEnabled(simpleMode.getAsBoolean());
            cfg.setAutoKickstart13(ks13Field.getText());
            cfg.setAutoKickstart31(ks31Field.getText());
            // With Expert off the system parameters are derived, not chosen: the saved ones
            // are NOT touched, so switching Expert on gives the user back exactly the machine
            // they configured by hand.
            if (!simpleMode.getAsBoolean()) {
                cfg.setKickstartPath(kickField.getText());
                cfg.setChipRamKb((Integer) chipCombo.getSelectedItem());
                cfg.setSlowRamKb((Integer) slowCombo.getSelectedItem());
                cfg.setFastRamKb((Integer) fastCombo.getSelectedItem());
                cfg.setVideoMode((VideoMode) videoModeCombo.getSelectedItem());
                cfg.setChipset((Chipset) chipsetCombo.getSelectedItem());
                cfg.setCpuModel((CpuModel) cpuCombo.getSelectedItem());
            }
            // The media come from the view the user has in front of them: the single list
            // with Expert off, the three per-type sections with Expert on.
            (simpleMode.getAsBoolean() ? driveList.toMediaState() : classicMedia.get()).toConfig(cfg);
            cfg.setFloppySoundVolume((Integer) soundSpinner.getValue());
            cfg.setLedsVisible(ledCheck.isSelected());
            cfg.setOverscanEnabled(overscanCheck.isSelected());
            cfg.setWindowMode((AmigaWindowMode) windowModeCombo.getSelectedItem());
            cfg.setMonitorIndex((Integer) monitorCombo.getSelectedItem());
            cfg.setRenderer((RendererType) rendererCombo.getSelectedItem());
            cfg.setShader((String) shaderCombo.getSelectedItem());
            cfg.setPortDevice(0, (PortDevice) port0Combo.getSelectedItem());
            cfg.setPortDevice(1, (PortDevice) port1Combo.getSelectedItem());
            cfg.setJoystickKeyMap(0, joyKeyMaps[0]);
            cfg.setJoystickKeyMap(1, joyKeyMaps[1]);
            cfg.setLanguage(langCombo.getSelectedIndex() == 1 ? "it" : "en");
            cfg.setIntegerScaling((Integer) scalingSpinner.getValue());
            cfg.setUiScale((Double) uiScaleCombo.getSelectedItem());
        };
        Runnable applyConfig = () -> applyTo.accept(config);

        // The choices are tried on a copy first: a machine whose Kickstart demands what the
        // rest of it lacks would not start, so the popup stays open and says what clashes.
        java.util.function.BooleanSupplier hardwareOk = () -> {
            AmigaConfig probe = config.copy();
            applyTo.accept(probe);
            java.util.List<HardwareRules.Conflict> conflicts = probe.hardwareConflicts();
            if (conflicts.isEmpty()) return true;
            JOptionPane.showMessageDialog(dialog, HardwareRules.describe(conflicts, probe, false),
                    Messages.t("app.title"), JOptionPane.WARNING_MESSAGE);
            return false;
        };

        softReset.addActionListener(e -> {
            if (!hardwareOk.getAsBoolean()) return;
            applyConfig.run();
            result[0] = Result.SOFT_RESET;
            dialog.dispose();
        });
        restart.addActionListener(e -> {
            if (!hardwareOk.getAsBoolean()) return;
            applyConfig.run();
            result[0] = Result.RESTART;
            dialog.dispose();
        });
        quit.addActionListener(e -> { result[0] = Result.QUIT; dialog.dispose(); });
        cancel.addActionListener(e -> { result[0] = Result.CANCEL; dialog.dispose(); });
        ok.addActionListener(e -> {
            if (!hardwareOk.getAsBoolean()) return;
            // Applies the modified values to the AmigaConfig before closing.
            applyConfig.run();
            if (remotePlaySection != null) {
                remotePlaySection.applyToConfig().run();
            }
            // If a connection to a host was made during the popup, confirming means really
            // becoming the guest, i.e. unmounting the machine, which this dialog cannot do by
            // itself. It exits with the outcome that has it done outside.
            boolean joined = remotePlaySection != null
                    && remotePlaySection.outcome().get() == RemotePlaySection.Outcome.JOIN;
            result[0] = joined ? Result.REMOTE_PLAY_TRANSITION : Result.OK;
            dialog.dispose();
        });

        JPanel buttons = new JPanel(new BorderLayout());
        buttons.setBackground(PANEL_BG);
        buttons.setBorder(pad(10, 16, 10, 16));

        JPanel left = new JPanel();
        left.setOpaque(false);
        left.add(about);
        left.add(screenshot);
        // "Reset" and "Restart" act on a machine, and a guest has none: the same reasons it
        // does not see the sections describing it. The screenshot it does have: it is taken
        // of the video it is watching, which is as much its own as anyone's.
        if (!guestMode) {
            left.add(softReset);
            left.add(restart);
        }
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

        // The timer of the "Remote play" section is stopped here and not in its close
        // listener: a Swing Timer is not tied to the window's life cycle and would keep
        // ticking on the EDT for the life of the process, once for every time the popup was
        // opened.
        if (remotePlaySection != null) {
            remotePlaySection.dispose().run();
        }
        return result[0];
    }

    /**
     * Shrinks the popup to what fits on the screen, if it exceeds it after {@code pack()}.
     *
     * <p>The content is already inside a scrolling pane, so shrinking hides nothing: it
     * scrolls. Otherwise the buttons at the bottom (OK, Cancel, Restart) end up off screen
     * and the popup becomes unusable, because it is not resizable and the buttons are the
     * only way to confirm.
     *
     * <p>A high integer scaling on a small screen is enough to hit it; the fractional
     * interface scale only makes it easier to reach.
     *
     * <p>The <em>work area</em> is used, not the screen bounds, so the popup does not end up
     * under the taskbar.
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
    // Widget construction
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
    // "Saved state" section
    // =========================================================================

    /**
     * The section of the state slots: one tab per slot ({@link SaveStateManager#SLOT_COUNT}
     * in all), each with the picture of the machine as it was when that state was taken and
     * the moment it happened. The buttons are below the tabs and always act on the open slot.
     *
     * <p>The chosen tab is not a pending change: it is written to the configuration at once
     * ({@code savestate.slot}), because that is where "Save state" and "Load state", which
     * act immediately with the popup open, read which slot to use. For the same reason it
     * stays valid even if the popup closes with Cancel.
     *
     * <p>Pictures, dates and tab titles are re-read from disk at every operation, so the
     * section always shows the real slots and not those there were when the popup opened.
     * The title of an occupied tab carries a dot: which slots are full can be seen without
     * opening them one by one.
     *
     * <p>Loading may not end here: if the state requires a machine different from the
     * running one, the popup closes with {@link Result#STATE_RESTORE} and the caller carries
     * on (see {@link LoadStateOutcome}).
     */
    private static JPanel stateSection(JDialog dialog, Result[] result, AmigaConfig config,
            java.util.function.Supplier<String> onSaveState,
            java.util.function.Supplier<LoadStateOutcome> onLoadState) {
        JPanel panel = section(Messages.t("section.state"));

        final int slots = SaveStateManager.SLOT_COUNT;
        StatePreview[] previews = new StatePreview[slots];
        JLabel[] whens = new JLabel[slots];

        JTabbedPane tabs = new JTabbedPane();
        tabs.setBackground(PANEL_BG);
        tabs.setOpaque(false);
        for (int i = 0; i < slots; i++) {
            previews[i] = new StatePreview();
            whens[i] = label("");
            JPanel tab = new JPanel(new GridBagLayout());
            tab.setOpaque(false);
            GridBagConstraints tc = new GridBagConstraints();
            tc.gridx = 0;
            tc.gridy = 0;
            tc.insets = new Insets(px(6), px(6), px(6), px(10));
            tc.anchor = GridBagConstraints.NORTHWEST;
            tab.add(previews[i], tc);
            tc.gridx = 1;
            tc.weightx = 1;
            tc.fill = GridBagConstraints.HORIZONTAL;
            tc.insets = new Insets(px(6), px(0), px(6), px(6));
            tab.add(whens[i], tc);
            tabs.addTab(Messages.t("state.slot.name", i + 1), tab);
        }

        JButton save = button(Messages.t("button.savestate"), false);
        JButton load = button(Messages.t("button.loadstate"), false);
        JButton clear = button(Messages.t("button.clearstate"), false);
        tip("button.savestate", save);
        tip("button.loadstate", load);
        tip("button.clearstate", clear);

        // Re-reads ALL the slots from disk: pictures, dates, tab titles and the availability
        // of the buttons for the open slot. It costs five header reads and happens only at
        // each user operation, not continuously.
        Runnable refresh = () -> {
            for (int i = 0; i < slots; i++) {
                int slot = i + 1;
                boolean present = SaveStateManager.slotPresent(slot);
                previews[i].setImage(present
                        ? readImage(SaveStateManager.slotScreenshot(slot)) : null);
                previews[i].setEmptyText(Messages.t("state.empty.short"));
                whens[i].setText(present
                        ? Messages.t("state.savedOn", formatTimestamp(
                                slotTimestamp(SaveStateManager.slotFile(slot))))
                        : Messages.t("state.empty"));
                tabs.setTitleAt(i, present
                        ? Messages.t("state.slot.name.full", slot)
                        : Messages.t("state.slot.name", slot));
            }
            boolean current = SaveStateManager.slotPresent(tabs.getSelectedIndex() + 1);
            load.setEnabled(current);
            clear.setEnabled(current);
        };

        // The tab open when the popup opens is the active slot; changing it changes the slot.
        // clampSlot() because the value comes from a text file.
        tabs.setSelectedIndex(SaveStateManager.clampSlot(config.saveStateSlot()) - 1);
        tabs.addChangeListener(e -> {
            config.setSaveStateSlot(tabs.getSelectedIndex() + 1);
            refresh.run();
        });
        refresh.run();

        save.addActionListener(e -> {
            String msg = onSaveState != null ? onSaveState.get() : null;
            // First realign the section, then speak: closing the message the user finds the
            // new picture, not the old one.
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
                // The pending changes of the popup are NOT applied: the machine that counts
                // now is the one described inside the state.
                result[0] = Result.STATE_RESTORE;
                dialog.dispose();
                return;
            }
            if (outcome.message() != null) message(dialog, outcome.message());
        });
        clear.addActionListener(e -> {
            int slot = tabs.getSelectedIndex() + 1;
            // Clearing a slot is the one action of this section that cannot be undone in any
            // way: confirmation is a must.
            int answer = JOptionPane.showConfirmDialog(dialog,
                    Messages.t("savestate.clear.confirm", slot), Messages.t("app.title"),
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (answer != JOptionPane.YES_OPTION) return;
            String msg;
            try {
                msg = SaveStateManager.deleteSlot(slot)
                        ? Messages.t("savestate.clear.ok", slot)
                        : Messages.t("savestate.load.none");
            } catch (IOException ex) {
                msg = Messages.t("savestate.clear.fail", String.valueOf(ex.getMessage()));
            }
            refresh.run();
            message(dialog, msg);
        });

        JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
        buttons.setOpaque(false);
        buttons.add(save);
        buttons.add(load);
        buttons.add(clear);

        tip("state.slot", tabs);

        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0;
        gc.gridy = 0;
        gc.weightx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.insets = new Insets(px(2), px(4), px(2), px(4));
        gc.anchor = GridBagConstraints.NORTHWEST;
        panel.add(tabs, gc);

        gc.gridy = 1;
        gc.fill = GridBagConstraints.NONE;
        gc.insets = new Insets(px(4), px(0), px(2), px(4));
        panel.add(buttons, gc);
        return panel;
    }

    /** Informational message in the style of the popup (application title). */
    private static void message(JDialog parent, String text) {
        JOptionPane.showMessageDialog(parent, text, Messages.t("app.title"),
                JOptionPane.INFORMATION_MESSAGE);
    }

    /**
     * Instant at which the slot was saved, read from its header. If the slot cannot be read
     * (e.g. written by an earlier version of the format) the file date is used: a wrong date
     * is better than not showing the section.
     */
    private static long slotTimestamp(File slot) {
        try {
            return SaveStateManager.readInfo(slot).savedAt();
        } catch (IOException e) {
            return slot.lastModified();
        }
    }

    /** Date and time in the format of the language chosen for the interface. */
    private static String formatTimestamp(long millis) {
        return java.text.DateFormat.getDateTimeInstance(
                java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT, Messages.locale())
                .format(new java.util.Date(millis));
    }

    /** Reads an image, or {@code null} if it is missing or cannot be decoded. */
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
     * The frame with the slot's picture: the image fits inside whole, scaled down as needed
     * and centred, without distortion. The size is fixed even when there is no saved state,
     * so the section does not change height at the first save, making the whole popup jump.
     */
    private static final class StatePreview extends JComponent {

        private static final int W = 168;
        private static final int H = 126; // 4:3, the proportions of the Amiga screen

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

    /**
     * The pieces of this dialog that {@link RemotePlaySection} uses, passed rather than
     * duplicated: they are the same widgets as the other sections, and two copies would
     * diverge at the first palette tweak.
     */
    private static RemotePlaySection.Widgets widgets(JDialog dialog, Result[] result) {
        return new RemotePlaySection.Widgets() {
            @Override
            public <T> JComboBox<T> combo(T[] values) {
                JComboBox<T> c = new JComboBox<>(values);
                c.setMaximumSize(dim(200, 26));
                return c;
            }

            @Override
            public JTextField text(String value) {
                return textField(value);
            }

            @Override
            public JTextField readOnlyText(String value) {
                JTextField tf = textField(value);
                tf.setEditable(false);
                // Not disabled: disabled, the text fades and cannot be selected, and these are
                // exactly the fields that are copied by hand.
                tf.setFocusable(true);
                return tf;
            }

            @Override
            public JCheckBox check(String text, boolean selected) {
                return ConfigDialog.check(text, selected);
            }

            @Override
            public JButton button(String text) {
                return ConfigDialog.button(text, false);
            }

            @Override
            public JLabel addRow(JComponent panel, int row, String labelKey,
                                 JComponent field, JComponent trailing) {
                return ConfigDialog.addRow((JPanel) panel, row, labelKey, field, trailing);
            }

            @Override
            public void setRowVisible(boolean visible, java.util.List<JComponent> comps) {
                ConfigDialog.setRowVisible(visible, comps.toArray(new JComponent[0]));
            }

            @Override
            public void tip(String baseKey, JComponent... components) {
                ConfigDialog.tip(baseKey, components);
            }

            @Override
            public void error(String message) {
                JOptionPane.showMessageDialog(dialog, message, Messages.t("app.title"),
                        JOptionPane.WARNING_MESSAGE);
            }

            @Override
            public void closeDialog() {
                result[0] = Result.REMOTE_PLAY_TRANSITION;
                dialog.dispose();
            }
        };
    }

    private static JPanel section(String title) {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBackground(PANEL_BG);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        TitledBorder tb = BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(0x45, 0x49, 0x50), px(1)), title);
        tb.setTitleColor(ACCENT);
        tb.setTitleFont(derive(p.getFont(), Font.BOLD, 13f));
        // The space between one section and the next is a margin of the section, not a strut
        // in the BoxLayout: so a hidden section takes its space away with it instead of
        // leaving a hole (Auto mode hides three sections).
        p.setBorder(BorderFactory.createCompoundBorder(
                pad(0, 0, 12, 0),
                BorderFactory.createCompoundBorder(tb,
                        pad(8, 10, 10, 10))));
        return p;
    }

    /**
     * Adds a row whose label comes from the bundle key {@code labelKey}, with the tooltip
     * from {@code <labelKey>.tip} (see {@link #tip}). The tooltip is not put on the trailing
     * buttons, which already have their own.
     */
    private static JLabel addRow(JPanel panel, int row, String labelKey, JComponent field, JComponent trailing) {
        JLabel l = label(Messages.t(labelKey));
        tip(labelKey, l, field);
        addRow(panel, row, l, field, trailing);
        return l;
    }

    /**
     * Applies to the whole row the tooltip of {@code baseKey}, i.e. the bundle entry
     * {@code <baseKey>.tip}: the convention by which every option of the popup explains
     * itself. If the entry is missing nothing happens.
     *
     * <p><b>Rule:</b> every option of the popup must have its own {@code .tip}, and that text
     * must be updated together with the feature it describes. A tooltip that lies is worse
     * than a missing one.
     */
    private static void tip(String baseKey, JComponent... components) {
        String text = Messages.tOrNull(baseKey + ".tip");
        if (text == null) return;
        // HTML wraps by itself: without it the explanation would stay on a single line as
        // wide as the screen.
        String html = "<html><body style='width: 260px'>" + text + "</body></html>";
        for (JComponent c : components) {
            applyTip(c, html);
        }
    }

    /**
     * Tooltip of "Expert mode". With the box unticked it ends with the machine Auto mode is
     * running now, one component per line; the media are left out, the list above shows them.
     */
    private static String expertTip(AmigaConfig config, boolean simple) {
        String text = Messages.t("label.expert.tip");
        if (simple) {
            AmigaConfig.AutoProfile p = config.autoProfile();
            String ks = p.kickstartPath();
            String ksName = ks == null || ks.isEmpty() ? Messages.t("ram.none")
                    : ks.substring(Math.max(ks.lastIndexOf('/'), ks.lastIndexOf('\\')) + 1);
            text += "<br><br><b>" + Messages.t("label.expert.current") + "</b>"
                    + "<br>Kickstart: " + escapeHtml(ksName)
                    + "<br>" + Messages.t("label.cpu") + " " + p.cpuModel().partNumber()
                    + "<br>" + Messages.t("label.chipset") + " " + p.chipset()
                    + "<br>" + Messages.t("label.chipram") + " " + formatKb(p.chipRamKb())
                    + "<br>" + Messages.t("label.slowram") + " " + formatKb(p.slowRamKb())
                    + "<br>" + Messages.t("label.fastram") + " " + formatKb(p.fastRamKb())
                    + "<br>" + Messages.t("label.videomode") + " " + p.videoMode();
        }
        return "<html><body style='width: 260px'>" + text + "</body></html>";
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * Assigns the tooltip to the component and to every child that does not have one. The
     * descent is for composite controls: on a JPanel wrapping a drop-down and a button, or on
     * a JSpinner (a panel with its text field), the container's tooltip would never appear,
     * because the child receives the mouse.
     */
    private static void applyTip(Component c, String html) {
        if (!(c instanceof JComponent jc)) return;
        if (jc.getToolTipText() == null) jc.setToolTipText(html);
        for (Component child : jc.getComponents()) {
            applyTip(child, html);
        }
    }

    /** Row label in the style of the popup. */
    private static JLabel label(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(FG);
        return l;
    }

    /**
     * Shows or hides a whole row of a section. With GridBagLayout the row disappears
     * entirely (zero height) when all its components are invisible, so no hole is left:
     * that is how the System section swaps the single Kickstart for the two of Auto mode.
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
     * Selects a value in a memory drop-down, appending it if it is not among the options:
     * Auto mode's profiles come from {@code amiga.properties} and the user may have put an
     * unusual size there.
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

    /** Adds a row with a leading component (e.g. a checkbox), a field and a browse button. */
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
        JButton b = button("⏏", false); // "eject" symbol
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

    /**
     * Key of a game port's name <b>without the colon</b>: {@code label.portN} has it, because
     * it is a row label, and putting it in the middle of a sentence would give "Port 1: is
     * filled by the guest".
     */
    private static String portName(int port) {
        return port == 0 ? "port.name.0" : "port.name.1";
    }

    /** Drop-down choosing a game port's device (Mouse / Joystick / Empty). */
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

    /** "..." button that opens the key configuration of the keyboard joystick. */
    private static JButton joystickKeysButton() {
        JButton b = button("...", false);
        styleDriveToolButton(b);
        b.setToolTipText(Messages.t("joykeys.tooltip"));
        return b;
    }

    /**
     * Shows or hides the joystick keys button. The section's trailing column stays sized by
     * the Kickstart's "...", so the button appearing does not change the width of the
     * drop-down next to it.
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

    /** Makes sure {@code value} is among the options (appends it if absent). */
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

    /** True for the individual disks of a multi-disk zip as well ({@code <zip>!<n>}). */
    private static boolean isZipPath(String path) {
        return io.github.zolfrik.amija.custom.DiskImage.isZipDisk(path);
    }

    /**
     * Lists the available shader presets: {@code "none"} followed by the names returned by
     * {@link io.github.zolfrik.amija.display.render.gl.ShaderCatalog}, i.e. the
     * {@code .glslp} and {@code .slangp} under {@code shaders/}, subfolders included (a pack
     * like the Mega Bezel brings hundreds, all nested).
     */
    private static String[] scanShaderNames() {
        java.util.List<String> names = new java.util.ArrayList<>();
        names.add(io.github.zolfrik.amija.display.render.gl.ShaderCatalog.NONE);
        names.addAll(io.github.zolfrik.amija.display.render.gl.ShaderCatalog.names());
        return names.toArray(new String[0]);
    }

    // =========================================================================
    // Media: model shared by the two views
    // =========================================================================

    /**
     * The media chosen by the user, in the exact form they take in {@code amiga.properties}.
     * It is the currency exchanged between the two views of the popup (the three classic
     * sections Floppy/Hard disk/WHDLoad and Auto mode's single list), so moving from one to
     * the other does not lose what was just chosen.
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
    // Single media list (Auto mode)
    // =========================================================================

    /**
     * The "Drives" section of Auto mode: a single list in which users put their files
     * without having to wonder which section they belong to. It starts with an empty entry;
     * once a file is chosen, the entry takes the name of the place the file went to (DF0,
     * hard drive master, WHDLoad) and another appears below it, as long as there is room for
     * something more.
     *
     * <p>The type is inferred from the extension, and the rules of the real machine do the
     * rest: four floppies at most, two hard disks, a single WHDLoad, and hard disk and
     * WHDLoad do not coexist, because both would be the boot volume. Whatever can no longer
     * be added disappears from the browser's filter, so the list cannot reach a state the
     * machine could not mount.
     *
     * <p>Adding an entry that requires a new drive is a hardware change, which is not made
     * on a running machine: the entry is marked with an asterisk, the legend at the bottom of
     * the section says why, and the drive appears at the next start. What stays immediate is
     * the content of the drives that already exist: removing and reinserting a floppy while
     * the game runs is the most normal thing in the world, and the drive left empty does not
     * disappear before the restart.
     */
    private static final class DriveList {

        /** Media type, recognised from the file extension. */
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
                // A multi-disk zip is addressed as <zip>!<n>: the archive is what counts.
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

        /** A file chosen by the user: its type and how it is to be mounted. */
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
        /** The media of the running machine, or {@code null} if there is none. */
        private final DriveHardware hardware;
        private final java.util.List<Item> items = new java.util.ArrayList<>();

        DriveList(JDialog owner, AmigaConfig config, JPanel panel, DriveHardware hardware) {
            this.owner = owner;
            this.config = config;
            this.panel = panel;
            this.hardware = hardware;
        }

        /**
         * Rebuilds the list from the configured media. The disks AmiJa extracted from a
         * multi-disk zip in DF0 do not become entries of their own: the entry is the
         * archive, and the disks it contains go back to their places by themselves at the
         * next start.
         */
        void seed(MediaState state) {
            items.clear();
            String df0 = MediaState.text(state.floppyDisk[0]);
            for (int i = 0; i < 4; i++) {
                String path = MediaState.text(state.floppyDisk[i]);
                if (path.isEmpty() || !state.floppyConnected[i]) {
                    // The drive the running machine really has stays in the list even without
                    // a disk: what was ejected is the floppy, not the drive.
                    if (i < liveFloppyDrives()) items.add(emptyFloppy());
                    continue;
                }
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

        /** A connected, empty floppy drive: it is there, but has nothing inside. */
        private static Item emptyFloppy() {
            return new Item(Kind.FLOPPY, "", false);
        }

        /** {@code true} if the entry is an empty drive and not a disk. */
        private static boolean isEmptyDrive(Item item) {
            return item.path.isEmpty();
        }

        /** How many floppy drives the running machine has (0 if there is none). */
        private int liveFloppyDrives() {
            return hardware == null ? 0 : hardware.floppyDrives();
        }

        /** The media of the list, spread over the machine's drives. */
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
            // One connected drive per disk, and as many for the disks of a multi-disk zip in
            // DF0, if any (Amija spreads them at start-up, but only over the drives that
            // exist).
            //
            // With no floppy in the list the drives stay disconnected, exactly like a machine
            // configured by hand with no drive ticked: booting a hard disk or WHDLoad needs no
            // drive, and having one there only costs the time the Kickstart spends polling it.
            // The exception is a completely empty list: there DF0 stays connected because it
            // is the only possible target of a disk dragged onto the window, and without it
            // drag-and-drop would have nowhere to land (AmigaDisplay refuses a drop on
            // drives that are not connected).
            //
            // A drive left empty keeps its place in the list while the machine runs (it is
            // really there, and a disk can be put back in), but it is not requested at the
            // next start: so the drives go up to the last one that has a disk, and the empty
            // ones before it stay connected because without them that one would not exist.
            int lastDisk = -1;
            for (int i = 0; i < 4; i++) {
                if (!MediaState.text(s.floppyDisk[i]).isEmpty()) lastDisk = i;
            }
            int connected = lastDisk + 1;
            if (lastDisk >= 0) connected = Math.max(connected, zipDiskCount(s.floppyDisk[0]));
            if (items.isEmpty()) connected = 1;
            for (int i = 0; i < Math.min(4, connected); i++) s.floppyConnected[i] = true;
            return s;
        }

        /** How many disks the archive contains, 1 if it is not a multi-disk zip. */
        private static int zipDiskCount(String path) {
            if (!isZipPath(path)) return 1;
            try {
                return Math.max(1, io.github.zolfrik.amija.custom.DiskImage
                        .listZipDiskEntries(io.github.zolfrik.amija.custom.DiskImage.pathOf(path)).size());
            } catch (IOException e) {
                return 1;
            }
        }

        /** {@code true} if {@code disk} is a disk extracted from the zip {@code zipPath}. */
        private static boolean isZipEntryOf(String disk, String zipPath) {
            return !zipPath.isEmpty() && isZipPath(zipPath)
                    && disk.regionMatches(true, 0, zipPath, 0, zipPath.length())
                    && disk.length() > zipPath.length()
                    && disk.charAt(zipPath.length())
                        == io.github.zolfrik.amija.custom.DiskImage.ZIP_ENTRY_SEPARATOR;
        }

        /**
         * Types still addable in row {@code index}, ignoring what the row holds now (it is
         * about to be replaced). {@code index == items.size()} is the empty trailing row.
         */
        private java.util.EnumSet<Kind> allowed(int index) {
            java.util.EnumSet<Kind> out = java.util.EnumSet.noneOf(Kind.class);
            for (Kind kind : Kind.values()) {
                if (count(kind, index) >= kind.max) continue;
                // Hard disk and WHDLoad are both the boot volume: the first to arrive
                // excludes the other.
                if (kind == Kind.HARDDISK && count(Kind.WHDLOAD, index) > 0) continue;
                if (kind == Kind.WHDLOAD && count(Kind.HARDDISK, index) > 0) continue;
                out.add(kind);
            }
            return out;
        }

        /** How many entries of that type there are, excluding row {@code except}. */
        private int count(Kind kind, int except) {
            int n = 0;
            for (int i = 0; i < items.size(); i++) {
                if (i != except && items.get(i).kind == kind) n++;
            }
            return n;
        }

        /** Position of the entry among those of the same type (0 = the first). */
        private int ordinal(int index) {
            Kind kind = items.get(index).kind;
            int nth = 0;
            for (int i = 0; i < index; i++) {
                if (items.get(i).kind == kind) nth++;
            }
            return nth;
        }

        /**
         * {@code true} if the entry requires a drive the running machine does not have, or a
         * medium that cannot be swapped live: it takes effect at the next start.
         *
         * <p>The floppy is a half exception: the drive is hardware, the disk inside it is
         * not. An entry in an already connected drive is therefore immediate, and stays so
         * even after being removed and put back. Hard disk and WHDLoad are mounted when the
         * machine is built: there a different file is enough.
         */
        private boolean pending(int index) {
            if (hardware == null || index >= items.size()) return false;
            Item item = items.get(index);
            int nth = ordinal(index);
            return switch (item.kind) {
                // A multi-disk zip in DF0 wants one drive per disk.
                case FLOPPY -> nth >= hardware.floppyDrives()
                        || (nth == 0 && zipDiskCount(item.path) > hardware.floppyDrives());
                case HARDDISK -> nth >= hardware.hdDisks().size()
                        || !hardware.hdDisks().get(nth).equals(item.path);
                case WHDLOAD -> !hardware.whdloadFile().equals(item.path);
            };
        }

        /** Label of the row: the place the file went to, or "Drive n" if empty. */
        private String labelFor(int index) {
            if (index >= items.size()) {
                return Messages.t("drive.entry.generic", index + 1);
            }
            int nth = ordinal(index);
            String text = switch (items.get(index).kind) {
                case FLOPPY -> Messages.t("drive.entry.floppy", nth);
                case HARDDISK -> Messages.t(nth == 0 ? "drive.entry.hd.master" : "drive.entry.hd.slave");
                case WHDLOAD -> Messages.t("drive.entry.whdload");
            };
            return pending(index) ? text + " *" : text;
        }

        /** Key of the row's tooltip, by type (the labels are dynamic). */
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
            // The empty trailing row is for adding: if the last entry is already an empty
            // drive, that row exists by itself and a second one would be a duplicate.
            boolean trailing = !allowed(items.size()).isEmpty()
                    && (items.isEmpty() || !isEmptyDrive(items.get(items.size() - 1)));
            int rows = items.size() + (trailing ? 1 : 0);
            boolean anyPending = false;
            for (int row = 0; row < rows; row++) {
                addEntryRow(row);
                anyPending |= pending(row);
            }
            // The legend appears only when there is an asterisk to explain.
            if (anyPending) {
                JLabel legend = label(Messages.t("drive.entry.pending.legend"));
                legend.setForeground(ACCENT);
                tip("drive.entry.pending.legend", legend);
                addRow(panel, rows, legend, null, null);
            }
            panel.revalidate();
            panel.repaint();
        }

        private void addEntryRow(int index) {
            boolean filled = index < items.size();
            JLabel label = label(labelFor(index));
            JTextField path = textField(filled ? items.get(index).path : "");
            // Not editable by hand: in this mode the file's type decides where it goes, so it
            // is chosen with the browser and that is all.
            path.setEditable(false);

            JButton browse = browseButton();
            browse.addActionListener(e -> browseInto(index));

            boolean hasMedia = filled && !isEmptyDrive(items.get(index));
            JToggleButton readOnly = readOnlyButton(filled && items.get(index).readOnly);
            // WHDLoad is not mounted: AmiJa builds it a hard disk of its own, which must be
            // able to write the saves.
            readOnly.setEnabled(hasMedia && items.get(index).kind != Kind.WHDLOAD);
            readOnly.addItemListener(e -> {
                if (index < items.size()) items.get(index).readOnly = readOnly.isSelected();
            });

            JButton eject = ejectButton();
            boolean liveDrive = filled && items.get(index).kind == Kind.FLOPPY
                    && ordinal(index) < liveFloppyDrives();
            eject.setToolTipText(Messages.t(liveDrive ? "eject.tooltip" : "eject.entry.tooltip"));
            eject.setEnabled(hasMedia);
            eject.addActionListener(e -> {
                if (index >= items.size()) return;
                // On a drive the machine really has, only the disk is ejected: the drive
                // stays, empty, ready for another. It disappears at the next start, when the
                // list no longer asks for that place.
                if (liveDrive) {
                    items.set(index, emptyFloppy());
                } else {
                    items.remove(index);
                }
                rebuild();
            });

            JPanel trailing = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
            trailing.setOpaque(false);
            trailing.add(browse);
            trailing.add(readOnly);
            trailing.add(eject);

            tip(tipKeyFor(index), label, path);
            addRow(panel, index, label, path, trailing);
        }

        /** Chooses the file of row {@code index} and recomputes the whole list. */
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
            // The browser's filter offers only the allowed types, but the name can also be
            // typed: if what comes out does not fit, say why.
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
     * Opens the file browser and returns the file chosen, or {@code null} if the user
     * cancels. Starts from the last folder used by this {@code kind} (or from that of the
     * current file) and, once a choice is made, remembers it.
     */
    private static File chooseFile(JDialog parent, AmigaConfig config, BrowseKind kind,
            String currentPath, String desc, String... extensions) {
        // Shared instance with a fast folder listing: see FastFileChooser.
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
        // The instance is reused: without a rescan it would show the listing of the previous
        // round, which may have changed on disk meanwhile.
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
