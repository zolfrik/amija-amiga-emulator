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

import io.github.zolfrik.amija.config.AmigaConfig;
import io.github.zolfrik.amija.i18n.Messages;
import io.github.zolfrik.amija.net.InviteCode;
import io.github.zolfrik.amija.net.RemotePlayControl;
import io.github.zolfrik.amija.net.serial.SerialControl;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTextField;
import javax.swing.Timer;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.List;

/**
 * The "Remote play" section of the F12 popup ({@code docs/remote-play-plan.md} section 9).
 *
 * <p>It is a class of its own and not inside {@code ConfigDialog} because that one is already
 * two thousand lines long, and because this section has something the others do not: a
 * <b>state that changes while the popup is open</b>. A guest can connect or drop while the
 * user looks at the window, so a timer realigns the controls, and the rest of the class
 * exists to keep that realignment in one place.
 *
 * <h2>Six modes, and the rows that appear</h2>
 * The selector offers the three roles of the video stream ({@code Host / Join / Watch}) and the
 * two ends of a null modem cable to another instance ({@code docs/serial-nullmodem-plan.md}),
 * and shows and hides the rows of the chosen role with {@code setRowVisible}, as Auto mode
 * already does. The same button changes label and action ("Host" becomes "Stop hosting")
 * instead of showing two side by side, one of them always off. Watch is Join as a spectator
 * (section 16): same rows, the spectator code instead of the join code, and nothing of this
 * machine reaches the host's. Hosting asks one question, who may join, and hands out one
 * invitation; audio, reset by the guest, the port, the interface, the bare code and the address
 * to type by hand are the rows of the section's Expert mode, off by default ({@code docs/remote-play-r1-plan.md} section 2). The null
 * modem rows repeat those of the stream wherever the two mean the same thing; the two
 * connections are independent and can run together.
 *
 * <h2>No button closes the popup</h2>
 * Neither hosting nor connecting closes the window: they act at once and the result shows in
 * the <b>status line</b>, which updates by itself. Connecting really opens the session (the
 * video starts arriving behind the popup) but <b>does not unmount the machine</b>: that is
 * reached by confirming with OK, the only thing this section leaves to whoever owns the
 * machine (section 3, {@link RemotePlayControl}).
 *
 * <p>Closing the popup when "Connect" is pressed and connecting afterwards would leave a
 * failure with no window in which to be told, and the user in front of their machine without
 * knowing the connection was not there. The one exception is leaving the session when one is
 * <b>really</b> a guest: there the machine must be rebuilt, and rebuilding it from inside a
 * modal dialog is what is avoided.
 */
final class RemotePlaySection {

    /** How often the status line realigns with reality. */
    private static final int REFRESH_MS = 500;

    private RemotePlaySection() {
    }

    /** What the popup must do when it closes, decided in here. */
    enum Outcome { NONE, JOIN, LEAVE }

    /** What the section hands to the dialog: the panel and the outcome to apply. */
    record Built(JComponent panel, java.util.function.Supplier<Outcome> outcome,
                 Runnable applyToConfig, Runnable dispose) {
    }

    /** Like {@link #build(AmigaConfig, RemotePlayControl, SerialControl,
     * java.util.function.Function, Widgets, Runnable)}, with no serial port and nothing to
     * realign outside this section. */
    static Built build(AmigaConfig config, RemotePlayControl control,
                       java.util.function.Function<String, JComponent> section,
                       Widgets widgets) {
        return build(config, control, null, section, widgets, null, null);
    }

    /**
     * Builds the section.
     *
     * @param control  the real remote play, which lives outside the popup
     * @param serial   the serial port of the running machine; {@code null} when there is
     *                 none (a guest has no machine), and then the null modem modes are not
     *                 offered
     * @param section  factory of panels in the same style as the other sections
     * @param alsoSync other rows of the popup that depend on the session state and must be
     *                 realigned with these: today the game ports, one of which changes owner
     *                 the moment hosting starts. It is here and not on a timer of its own
     *                 because the <b>same</b> event moves them: two timers would mean two
     *                 different moments at which the popup says two different things.
     *                 {@code null} if there is nothing to realign
     * @param ports    what the game ports section holds, for the popup that tells each
     *                 player their controls when a session starts; {@code null} for none
     */
    static Built build(AmigaConfig config, RemotePlayControl control, SerialControl serial,
                       java.util.function.Function<String, JComponent> section,
                       Widgets widgets, Runnable alsoSync, Ports ports) {
        JComponent panel = section.apply(Messages.t("section.remoteplay"));
        Outcome[] outcome = { Outcome.NONE };
        // If already a guest when the popup opened, the machine is already unmounted and
        // there is no transition to ask for at closing: the JOIN outcome belongs only to the
        // sessions opened by this popup.
        final boolean guestAtOpen = control.isGuest();
        // The controls popup is shown once per session this popup opens: a guest learns its
        // port only from the host's answer, so the moment is when the session is there.
        boolean[] guestControlsShown = { guestAtOpen };

        // --- Expert mode: first entry, off by default, as in the System section -------
        // Off, the section shows what an invitation needs; on, also the options a casual
        // host never touches and what the invitation makes unnecessary.
        JCheckBox expertCheck = widgets.check(Messages.t("label.remoteplay.expert"),
                config.expandedPopupSections().contains(EXPERT_ID));
        widgets.addCheckRow(panel, 0, "label.remoteplay.expert", expertCheck);

        // --- Mode -----------------------------------------------------------------
        JComboBox<Mode> modeCombo = widgets.combo(serial == null ? Mode.streamModes() : Mode.values());
        modeCombo.setSelectedItem(control.isSpectator() ? Mode.WATCH
                : control.isGuest() ? Mode.JOIN
                : control.isHosting() ? Mode.HOST
                : serial != null && serial.isSerialHosting() ? Mode.SERIAL_HOST
                : serial != null && (serial.isSerialLinked() || serial.isSerialJoining())
                        ? Mode.SERIAL_JOIN : Mode.OFF);
        widgets.addRow(panel, 1, "label.remoteplay.mode", modeCombo, null);

        // --- Host rows ---------------------------------------------------------------
        JComboBox<Access> accessCombo = widgets.combo(Access.values());
        accessCombo.setSelectedItem(config.remotePlayInternet() ? Access.INTERNET : Access.LAN);
        JLabel accessLabel = widgets.addRow(panel, 2, "label.remoteplay.access",
                accessCombo, null);

        JButton hostButton = widgets.button(Messages.t("button.remoteplay.start"));
        JLabel hostButtonLabel = widgets.addRow(panel, 3, "label.remoteplay.session",
                hostButton, null);

        // The invitation is what the other player needs, all of it: code, addresses, ports.
        JTextField inviteField = widgets.readOnlyText("");
        JButton copyInvite = widgets.button(Messages.t("button.remoteplay.copy"));
        JLabel inviteLabel = widgets.addRow(panel, 4, "label.remoteplay.invite",
                inviteField, copyInvite);

        // Shown only while spectators are accepted: there is no invitation to hand out otherwise.
        JTextField spectatorInviteField = widgets.readOnlyText("");
        JButton copySpectatorInvite = widgets.button(Messages.t("button.remoteplay.copy"));
        JLabel spectatorInviteLabel = widgets.addRow(panel, 5,
                "label.remoteplay.spectatorinvite", spectatorInviteField, copySpectatorInvite);

        // One line; the long explanation of the router's answer is its tooltip.
        JTextField reachField = widgets.readOnlyText("");
        JButton firewallButton = widgets.button(Messages.t("button.remoteplay.firewall"));
        JLabel reachLabel = widgets.addRow(panel, 6, "label.remoteplay.reach", reachField,
                firewallButton);

        Integer[] spectatorChoices = new Integer[AmigaConfig.MAX_SPECTATORS + 1];
        for (int i = 0; i < spectatorChoices.length; i++) {
            spectatorChoices[i] = i;
        }
        JComboBox<Integer> spectatorsCombo = widgets.combo(spectatorChoices);
        spectatorsCombo.setSelectedItem(config.remotePlaySpectatorsMax());
        JLabel spectatorsLabel = widgets.addRow(panel, 7, "label.remoteplay.spectators",
                spectatorsCombo, null);

        JComboBox<GuestPort> guestPortCombo = widgets.combo(GuestPort.values());
        guestPortCombo.setSelectedItem(config.remotePlayGuestPort() == 0
                ? GuestPort.PORT0 : GuestPort.PORT1);
        JCheckBox shareKeyboard = widgets.check(Messages.t("label.remoteplay.guestkeyboard"),
                config.remotePlayGuestKeyboard());
        JLabel guestPortLabel = widgets.addRow(panel, 8, "label.remoteplay.guestport",
                guestPortCombo, shareKeyboard);

        // --- Joiner rows -------------------------------------------------------------
        JTextField enterInviteField = widgets.text("");
        JLabel enterInviteLabel = widgets.addRow(panel, 11, "label.remoteplay.enterinvite",
                enterInviteField, null);

        JButton joinButton = widgets.button(Messages.t("button.remoteplay.connect"));
        JLabel joinButtonLabel = widgets.addRow(panel, 12, "label.remoteplay.session2",
                joinButton, null);

        // --- Expert rows: host options and what an invitation makes unnecessary ------
        JCheckBox allowReset = widgets.check(Messages.t("label.remoteplay.guestreset"),
                config.remotePlayGuestAllowReset());
        JComboBox<AudioMode> audioCombo = widgets.combo(AudioMode.values());
        audioCombo.setSelectedItem(AudioMode.fromConfig(config.remotePlayAudioMode()));
        JLabel audioLabel = widgets.addRow(panel, 40, "label.remoteplay.audio",
                audioCombo, allowReset);

        JTextField portField = widgets.text(Integer.toString(config.remotePlayPort()));
        JCheckBox stunCheck = widgets.check(Messages.t("label.remoteplay.stun"),
                config.remotePlayStun());
        JLabel portLabel = widgets.addRow(panel, 41, "label.remoteplay.port", portField,
                stunCheck);

        JTextField bindField = widgets.text(config.remotePlayBind());
        JCheckBox ipv6Check = widgets.check(Messages.t("label.remoteplay.ipv6"),
                config.remotePlayIpv6());
        JLabel bindLabel = widgets.addRow(panel, 42, "label.remoteplay.bind", bindField,
                ipv6Check);

        JTextField codeField = widgets.readOnlyText("");
        JButton copyCode = widgets.button(Messages.t("button.remoteplay.copy"));
        JLabel codeLabel = widgets.addRow(panel, 43, "label.remoteplay.joincode",
                codeField, copyCode);

        JTextField spectatorCodeField = widgets.readOnlyText("");
        JButton copySpectatorCode = widgets.button(Messages.t("button.remoteplay.copy"));
        JLabel spectatorCodeLabel = widgets.addRow(panel, 44, "label.remoteplay.spectatorcode",
                spectatorCodeField, copySpectatorCode);

        JTextField hostAddressField = widgets.text(config.remotePlayLastHost());
        JLabel hostAddressLabel = widgets.addRow(panel, 45, "label.remoteplay.hostaddress",
                hostAddressField, null);

        JTextField joinCodeField = widgets.text("");
        JLabel joinCodeLabel = widgets.addRow(panel, 46, "label.remoteplay.entercode",
                joinCodeField, null);

        // --- Null modem rows ---------------------------------------------------------
        // The same shape as the stream: who may join is the same row, the invitation carries
        // code and addresses, and port, address and code by hand are Expert rows. The delay of
        // the cable and the loopback plug are set in amiga.properties only.
        JButton serialHostButton = widgets.button(Messages.t("button.remoteplay.start"));
        JLabel serialHostButtonLabel = widgets.addRow(panel, 21, "label.serial.session",
                serialHostButton, null);

        JTextField serialInviteField = widgets.readOnlyText("");
        JButton copySerialInvite = widgets.button(Messages.t("button.remoteplay.copy"));
        JLabel serialInviteLabel = widgets.addRow(panel, 22, "label.serial.invite",
                serialInviteField, copySerialInvite);

        JTextField serialReachField = widgets.readOnlyText("");
        JLabel serialReachLabel = widgets.addRow(panel, 23, "label.serial.reach",
                serialReachField, null);

        JTextField serialEnterInviteField = widgets.text("");
        JLabel serialEnterInviteLabel = widgets.addRow(panel, 26, "label.serial.enterinvite",
                serialEnterInviteField, null);

        JButton serialJoinButton = widgets.button(Messages.t("button.remoteplay.connect"));
        JLabel serialJoinButtonLabel = widgets.addRow(panel, 27, "label.serial.session2",
                serialJoinButton, null);

        JTextField serialPortField = widgets.text(Integer.toString(config.serialPort()));
        JLabel serialPortLabel = widgets.addRow(panel, 51, "label.serial.port",
                serialPortField, null);

        JTextField serialAddressField = widgets.readOnlyText("");
        JButton copySerialAddress = widgets.button(Messages.t("button.remoteplay.copy"));
        JLabel serialAddressLabel = widgets.addRow(panel, 52, "label.serial.address",
                serialAddressField, copySerialAddress);

        JTextField serialCodeField = widgets.readOnlyText("");
        JButton copySerialCode = widgets.button(Messages.t("button.remoteplay.copy"));
        JLabel serialCodeLabel = widgets.addRow(panel, 53, "label.serial.joincode",
                serialCodeField, copySerialCode);

        JTextField serialHostAddressField = widgets.text(serialLastHost(config));
        JLabel serialHostAddressLabel = widgets.addRow(panel, 54, "label.serial.hostaddress",
                serialHostAddressField, null);

        JTextField serialJoinCodeField = widgets.text("");
        JLabel serialJoinCodeLabel = widgets.addRow(panel, 55, "label.serial.entercode",
                serialJoinCodeField, null);

        // --- Status, always visible -------------------------------------------------
        JTextField statusField = widgets.readOnlyText("");
        JLabel statusLabel = widgets.addRow(panel, 61, "label.remoteplay.status",
                statusField, null);

        List<JComponent> accessRows = List.of(accessLabel, accessCombo);
        List<JComponent> hostRows = List.of(hostButtonLabel, hostButton);
        List<JComponent> hostLiveRows = List.of(inviteLabel, inviteField, copyInvite,
                reachLabel, reachField);
        List<JComponent> spectatorInviteRows = List.of(spectatorInviteLabel,
                spectatorInviteField, copySpectatorInvite);
        List<JComponent> hostOptionRows = List.of(spectatorsLabel, spectatorsCombo,
                guestPortLabel, guestPortCombo, shareKeyboard);
        List<JComponent> joinRows = List.of(enterInviteLabel, enterInviteField,
                joinButtonLabel, joinButton);
        List<JComponent> expertHostRows = List.of(audioLabel, audioCombo, allowReset,
                portLabel, portField, stunCheck, bindLabel, bindField, ipv6Check);
        List<JComponent> expertCodeRows = List.of(codeLabel, codeField, copyCode);
        List<JComponent> expertSpectatorRows = List.of(spectatorCodeLabel,
                spectatorCodeField, copySpectatorCode);
        List<JComponent> expertJoinRows = List.of(hostAddressLabel, hostAddressField,
                joinCodeLabel, joinCodeField);
        List<JComponent> serialHostRows = List.of(serialHostButtonLabel, serialHostButton);
        List<JComponent> serialHostLiveRows = List.of(serialInviteLabel, serialInviteField,
                copySerialInvite, serialReachLabel, serialReachField);
        List<JComponent> serialExpertHostRows = List.of(serialPortLabel, serialPortField);
        List<JComponent> serialExpertLiveRows = List.of(serialAddressLabel,
                serialAddressField, copySerialAddress, serialCodeLabel, serialCodeField,
                copySerialCode);
        List<JComponent> serialJoinRows = List.of(serialEnterInviteLabel,
                serialEnterInviteField, serialJoinButtonLabel, serialJoinButton);
        List<JComponent> serialExpertJoinRows = List.of(serialHostAddressLabel,
                serialHostAddressField, serialJoinCodeLabel, serialJoinCodeField);

        String[] serialProblem = { null };

        // A single place where the controls realign with reality: called by the mode change,
        // the buttons and the timer. Three copies would be the sure way to leave one behind.
        Runnable sync = () -> {
            boolean hosting = control.isHosting();
            boolean guest = control.isGuest();
            boolean connecting = control.isJoining();
            // With a session open (or opening) the selector cannot say anything else: from
            // "Off" the rows would disappear, but the session would stay alive and OK would
            // confirm it anyway. The selector shows the rows of a role; here the role has
            // already been taken, and what is seen must say so.
            if (guest) {
                // re-enters here, and the second time it is a no-op
                modeCombo.setSelectedItem(control.isSpectator() ? Mode.WATCH : Mode.JOIN);
            } else if (connecting && !((Mode) modeCombo.getSelectedItem()).joins()) {
                modeCombo.setSelectedItem(Mode.JOIN);
            }
            Mode mode = (Mode) modeCombo.getSelectedItem();
            boolean expert = expertCheck.isSelected();
            boolean spectatorsOn = hosting && control.spectatorCode() != null;

            widgets.setRowVisible(mode == Mode.HOST || mode == Mode.SERIAL_HOST, accessRows);
            widgets.setRowVisible(mode == Mode.HOST, hostRows);
            widgets.setRowVisible(mode == Mode.HOST && hosting, hostLiveRows);
            widgets.setRowVisible(mode == Mode.HOST && hosting && control.firewallFixOffered(),
                    List.of(firewallButton));
            widgets.setRowVisible(mode == Mode.HOST && spectatorsOn, spectatorInviteRows);
            widgets.setRowVisible(mode == Mode.HOST, hostOptionRows);
            widgets.setRowVisible(mode.joins(), joinRows);
            widgets.setRowVisible(mode == Mode.HOST && expert, expertHostRows);
            widgets.setRowVisible(mode == Mode.HOST && expert && hosting, expertCodeRows);
            widgets.setRowVisible(mode == Mode.HOST && expert && spectatorsOn,
                    expertSpectatorRows);
            widgets.setRowVisible(mode.joins() && expert, expertJoinRows);
            widgets.setRowVisible(mode == Mode.SERIAL_HOST, serialHostRows);
            widgets.setRowVisible(mode == Mode.SERIAL_HOST && expert, serialExpertHostRows);
            widgets.setRowVisible(mode == Mode.SERIAL_JOIN, serialJoinRows);
            widgets.setRowVisible(mode == Mode.SERIAL_JOIN && expert, serialExpertJoinRows);
            if (serial != null) {
                boolean serialHosting = serial.isSerialHosting();
                boolean serialJoining = serial.isSerialJoining();
                // A cable joined from this side: the listening end keeps its server open.
                boolean serialJoined = !serialHosting && serial.isSerialLinked();
                widgets.setRowVisible(mode == Mode.SERIAL_HOST && serialHosting,
                        serialHostLiveRows);
                widgets.setRowVisible(mode == Mode.SERIAL_HOST && serialHosting && expert,
                        serialExpertLiveRows);
                serialHostButton.setText(Messages.t(serialHosting
                        ? "button.remoteplay.stop" : "button.remoteplay.start"));
                serialHostButton.setEnabled(!serialJoining && !serialJoined);
                serialJoinButton.setText(Messages.t(serialJoined
                        ? "button.remoteplay.disconnect" : "button.remoteplay.connect"));
                String serialInviteProblem = serialJoined || serialJoining ? null
                        : serialInviteProblem(serialEnterInviteField.getText());
                serialJoinButton.setEnabled(!serialJoining && !serialHosting
                        && serialInviteProblem == null);
                boolean serialIdle = !serialHosting && !serialJoining && !serialJoined;
                serialPortField.setEnabled(serialIdle);
                serialEnterInviteField.setEnabled(serialIdle);
                serialHostAddressField.setEnabled(serialIdle);
                serialJoinCodeField.setEnabled(serialIdle);
                if (serialHosting) {
                    serialInviteField.setText(nullToEmpty(serial.serialInvite()));
                    serialReachField.setText(nullToEmpty(serial.serialReachability()));
                    serialAddressField.setText(nullToEmpty(serial.serialLanAddress()));
                    serialCodeField.setText(nullToEmpty(serial.serialJoinCode()));
                }
                if (mode == Mode.SERIAL_JOIN && serialInviteProblem != null) {
                    serialProblem[0] = serialInviteProblem;
                } else {
                    serialProblem[0] = null;
                }
            }

            hostButton.setText(Messages.t(hosting
                    ? "button.remoteplay.stop" : "button.remoteplay.start"));
            joinButton.setText(Messages.t(guest
                    ? "button.remoteplay.disconnect" : "button.remoteplay.connect"));
            // A pasted invitation is checked as it is typed: a damaged one never reaches the
            // network, and the status line says what is wrong with it instead.
            String inviteProblem = guest || connecting ? null
                    : inviteProblem(enterInviteField.getText());
            // While the connection is in flight there is nothing to press and nothing to
            // change: the address and code of that attempt have already left.
            joinButton.setEnabled(!connecting && inviteProblem == null);
            enterInviteField.setEnabled(!connecting && !guest);
            hostAddressField.setEnabled(!connecting && !guest);
            joinCodeField.setEnabled(!connecting && !guest);
            // The outcome to apply at closing is not a separate choice: it is simply "this
            // popup opened a session", and must be re-read at every round because the session
            // may have closed meanwhile.
            if (!guest) {
                guestControlsShown[0] = false;
            } else if (!guestControlsShown[0]) {
                guestControlsShown[0] = true;
                if (ports != null && !control.isSpectator() && control.remoteGamePort() >= 0) {
                    // Later, not from inside this round: the popup is modal and the timer
                    // keeps calling here while it is open.
                    javax.swing.SwingUtilities.invokeLater(() -> showControls(ports, control,
                            RemotePlayControlsDialog.Role.GUEST));
                }
            }
            if (outcome[0] != Outcome.LEAVE) {
                outcome[0] = !guestAtOpen && guest ? Outcome.JOIN : Outcome.NONE;
            }
            // While hosting, the parameters of the running session are not touched: changing
            // them under a connected guest means nothing.
            accessCombo.setEnabled(!hosting
                    && !(serial != null && serial.isSerialHosting()));
            portField.setEnabled(!hosting);
            bindField.setEnabled(!hosting);
            ipv6Check.setEnabled(!hosting);
            stunCheck.setEnabled(!hosting);
            guestPortCombo.setEnabled(!hosting);
            shareKeyboard.setEnabled(!hosting);
            allowReset.setEnabled(!hosting);
            audioCombo.setEnabled(!hosting);
            spectatorsCombo.setEnabled(!hosting);

            if (hosting) {
                inviteField.setText(nullToEmpty(control.invite()));
                spectatorInviteField.setText(nullToEmpty(control.spectatorInvite()));
                codeField.setText(nullToEmpty(control.joinCode()));
                spectatorCodeField.setText(nullToEmpty(control.spectatorCode()));
                reachField.setText(nullToEmpty(control.reachability()));
                String detail = control.routerStatus();
                reachField.setToolTipText(detail == null || detail.isBlank() ? null : detail);
                String command = control.firewallCommand();
                if (command != null) {
                    firewallButton.setToolTipText(Messages.t("button.remoteplay.firewall.tip")
                            + " " + command);
                }
            }
            statusField.setText(mode.serial() && serial != null
                    ? (serialProblem[0] != null ? serialProblem[0] : serial.serialStatus())
                    : mode.joins() && inviteProblem != null ? inviteProblem
                    : control.status());
            if (alsoSync != null) {
                alsoSync.run();
            }
        };

        modeCombo.addActionListener(e -> sync.run());
        expertCheck.addActionListener(e -> {
            // The look of the popup, like a collapsible section: remembered at once.
            config.setPopupSectionExpanded(EXPERT_ID, expertCheck.isSelected());
            sync.run();
        });
        enterInviteField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { sync.run(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { sync.run(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { sync.run(); }
        });

        hostButton.addActionListener(e -> {
            if (control.isHosting()) {
                control.stopHosting();
            } else {
                // The popup's values go into the configuration BEFORE switching on: that is
                // where startHosting() reads port, game port and audio from.
                applyHostFields(config, accessCombo, portField, bindField,
                        guestPortCombo, shareKeyboard, allowReset, audioCombo, spectatorsCombo);
                config.setRemotePlayIpv6(ipv6Check.isSelected());
                config.setRemotePlayStun(stunCheck.isSelected());
                String error = control.startHosting();
                if (error != null) {
                    widgets.error(error);
                } else if (ports != null) {
                    sync.run();
                    showControls(ports, control, RemotePlayControlsDialog.Role.HOST);
                }
            }
            sync.run();
        });

        joinButton.addActionListener(e -> {
            if (control.isJoining()) {
                return;   // already in flight: the button is off, but the keyboard shortcut is not
            }
            if (control.isGuest()) {
                if (control.requestLeave()) {
                    // Really a guest: there is no machine, and rebuilding it needs the popup
                    // closed. The only case in which this section closes it.
                    outcome[0] = Outcome.LEAVE;
                    widgets.closeDialog();
                    return;
                }
                sync.run();
                return;
            }
            // The invitation when there is one; otherwise the address and code typed in the
            // Expert rows. The connection starts and returns at once: the status line says
            // how it is going, and if it fails the user gets a message anyway.
            String invite = enterInviteField.getText();
            boolean watch = modeCombo.getSelectedItem() == Mode.WATCH;
            String error = invite != null && !invite.isBlank()
                    ? control.requestJoin(invite.strip(), "", watch)
                    : control.requestJoin(hostAddressField.getText(),
                            joinCodeField.getText(), watch);
            if (error != null) {
                widgets.error(error);
            }
            sync.run();
        });

        serialHostButton.addActionListener(e -> {
            if (serial.isSerialHosting()) {
                serial.stopSerial();
            } else {
                applySerialFields(config, serialPortField, serialHostAddressField);
                config.setRemotePlayInternet(accessCombo.getSelectedItem() == Access.INTERNET);
                String error = serial.startSerialHost(config.serialPort(),
                        config.remotePlayInternet());
                if (error != null) {
                    widgets.error(error);
                }
            }
            sync.run();
        });

        serialJoinButton.addActionListener(e -> {
            if (serial.isSerialJoining()) {
                return;   // already in flight
            }
            if (serial.isSerialLinked()) {
                serial.stopSerial();
            } else {
                applySerialFields(config, serialPortField, serialHostAddressField);
                String invite = serialEnterInviteField.getText();
                String typed = serialHostAddressField.getText();
                String error = invite != null && !invite.isBlank()
                        ? serial.joinSerial(invite.strip(), 0, "")
                        : serial.joinSerial(hostOf(typed), portOf(typed, config.serialPort()),
                                serialJoinCodeField.getText());
                if (error != null) {
                    widgets.error(error);
                }
            }
            sync.run();
        });

        copySerialAddress.addActionListener(e -> copy(serialAddressField.getText()));
        copySerialInvite.addActionListener(e -> copy(serialInviteField.getText()));
        serialEnterInviteField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { sync.run(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { sync.run(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { sync.run(); }
        });
        copySerialCode.addActionListener(e -> copy(serialCodeField.getText()));
        copyInvite.addActionListener(e -> copy(inviteField.getText()));
        firewallButton.addActionListener(e -> control.allowInFirewall());
        copySpectatorInvite.addActionListener(e -> copy(spectatorInviteField.getText()));
        copyCode.addActionListener(e -> copy(codeField.getText()));
        copySpectatorCode.addActionListener(e -> copy(spectatorCodeField.getText()));

        // The trailing controls of the row: addRow does not touch them, and without these
        // calls their .tip would be text written and never shown.
        widgets.tip("label.remoteplay.ipv6", ipv6Check);
        widgets.tip("label.remoteplay.stun", stunCheck);
        widgets.tip("button.remoteplay.firewall", firewallButton);
        widgets.tip("label.remoteplay.guestkeyboard", shareKeyboard);
        widgets.tip("label.remoteplay.guestreset", allowReset);
        widgets.tip("button.remoteplay.copy", copyInvite, copySpectatorInvite, copyCode,
                copySpectatorCode, copySerialAddress, copySerialCode, copySerialInvite);
        widgets.tip("label.serial.session", serialHostButton);
        widgets.tip("label.serial.session2", serialJoinButton);
        widgets.tip("label.remoteplay.session", hostButton);
        widgets.tip("label.remoteplay.session2", joinButton);

        // The guest can connect or drop while the window is open: without this, the state
        // shown would be that of when F12 was pressed.
        Timer timer = new Timer(REFRESH_MS, e -> sync.run());
        timer.start();
        sync.run();

        Runnable applyToConfig = () -> {
            applyHostFields(config, accessCombo, portField, bindField, guestPortCombo,
                    shareKeyboard, allowReset, audioCombo, spectatorsCombo);
            config.setRemotePlayIpv6(ipv6Check.isSelected());
            config.setRemotePlayStun(stunCheck.isSelected());
            if (serial != null) {
                applySerialFields(config, serialPortField, serialHostAddressField);
            }
        };

        return new Built(panel, () -> outcome[0], applyToConfig, timer::stop);
    }

    /**
     * Tells this player which game port is theirs and how to drive it. The host plays on the
     * port it did not give away, the guest on the one it was given.
     */
    private static void showControls(Ports ports, RemotePlayControl control,
                                     RemotePlayControlsDialog.Role role) {
        int remote = control.remoteGamePort();
        if (remote < 0) {
            return;
        }
        int mine = role == RemotePlayControlsDialog.Role.HOST ? 1 - remote : remote;
        RemotePlayControlsDialog.show(ports.owner(), new RemotePlayControlsDialog.Info(role,
                mine, ports.device(mine), ports.keys(mine), ports.cd32Pad(mine),
                control.keyboardShared()));
    }

    /**
     * What the game ports section of the popup holds now: the choices shown there, which are
     * the ones in force once the popup is confirmed.
     */
    interface Ports {
        java.awt.Window owner();

        io.github.zolfrik.amija.input.PortDevice device(int port);

        io.github.zolfrik.amija.input.JoystickKeyMap keys(int port);

        /** Whether the port holds a CD32 pad. */
        boolean cd32Pad(int port);
    }

    // =====================================================================

    /** Key of the section's Expert mode in the popup's remembered sections. */
    static final String EXPERT_ID = "remoteplay.expert";

    /**
     * What is wrong with a pasted invitation, in the user's language, or {@code null} when it
     * is usable, empty, or not an invitation at all (then it is an address, and the host says
     * whether it answers).
     */
    static String inviteProblem(String text) {
        if (text == null || text.isBlank() || !InviteCode.looksLikeInvite(text)) {
            return null;
        }
        try {
            InviteCode invite = InviteCode.parse(text);
            return invite.role() == InviteCode.ROLE_NULL_MODEM
                    ? Messages.t("msg.remoteplay.invite.nullmodem") : null;
        } catch (InviteCode.InvalidInviteException e) {
            return Messages.t(switch (e.problem()) {
                case CORRUPTED -> "msg.remoteplay.invite.corrupted";
                case VERSION -> "msg.remoteplay.invite.version";
                case MALFORMED -> "msg.remoteplay.invite.malformed";
            });
        }
    }

    private static void applyHostFields(AmigaConfig config, JComboBox<Access> access,
            JTextField portField, JTextField bindField, JComboBox<GuestPort> guestPort,
            JCheckBox keyboard, JCheckBox allowReset, JComboBox<AudioMode> audio,
            JComboBox<Integer> spectators) {
        try {
            int port = Integer.parseInt(portField.getText().trim());
            if (port > 0 && port <= 65535) {
                config.setRemotePlayPort(port);
            }
        } catch (NumberFormatException ignored) {
            // Port mistyped: keep the previous one instead of refusing the whole popup over
            // one field. The invitation carries whichever port is really used.
        }
        config.setRemotePlayInternet(access.getSelectedItem() == Access.INTERNET);
        config.setRemotePlayBind(bindField.getText());
        config.setRemotePlayGuestPort(
                guestPort.getSelectedItem() == GuestPort.PORT0 ? 0 : 1);
        config.setRemotePlayGuestKeyboard(keyboard.isSelected());
        config.setRemotePlayGuestAllowReset(allowReset.isSelected());
        config.setRemotePlayAudioMode(((AudioMode) audio.getSelectedItem()).configValue());
        Object max = spectators.getSelectedItem();
        if (max instanceof Integer n) {
            config.setRemotePlaySpectatorsMax(n);
        }
    }

    /**
     * Writes the null modem fields to the configuration. The role itself is never stored: a
     * null modem session is started by the user every time, never at boot. A loopback plug
     * set by hand in amiga.properties is never touched.
     */
    private static void applySerialFields(AmigaConfig config, JTextField portField,
            JTextField hostAddress) {
        try {
            int port = Integer.parseInt(portField.getText().trim());
            if (port > 0 && port <= 65535) {
                config.setSerialValue("link.port", Integer.toString(port));
            }
        } catch (NumberFormatException ignored) {
            // Port mistyped: keep the previous one, as the stream does.
        }
        String typed = hostAddress.getText().trim();
        if (!typed.isEmpty()) {
            config.setSerialValue("link.address", hostOf(typed));
        }
    }

    /** Like {@link #inviteProblem}, but an invitation of the stream is wrong here. */
    static String serialInviteProblem(String text) {
        if (text == null || text.isBlank() || !InviteCode.looksLikeInvite(text)) {
            return null;
        }
        try {
            return InviteCode.parse(text).role() == InviteCode.ROLE_NULL_MODEM ? null
                    : Messages.t("msg.serial.invite.stream");
        } catch (InviteCode.InvalidInviteException e) {
            return inviteProblem(text);
        }
    }

    /** The address of the listening end as it was last typed, with the port of the cable. */
    private static String serialLastHost(AmigaConfig config) {
        String address = config.serialValue("link.address");
        return address == null ? "" : address + ":" + config.serialPort();
    }

    /** The host part of {@code host:port}; the whole text when it carries no port. */
    static String hostOf(String typed) {
        String s = typed == null ? "" : typed.trim();
        int colon = s.lastIndexOf(':');
        return colon > 0 && s.indexOf(':') == colon ? s.substring(0, colon).trim() : s;
    }

    /** The port of {@code host:port}; {@code fallback} when there is none or it is not a port. */
    static int portOf(String typed, int fallback) {
        String s = typed == null ? "" : typed.trim();
        int colon = s.lastIndexOf(':');
        if (colon <= 0 || s.indexOf(':') != colon) {
            return fallback;
        }
        try {
            int port = Integer.parseInt(s.substring(colon + 1).trim());
            return port > 0 && port <= 65535 ? port : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static void copy(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(text), null);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** The pieces of {@code ConfigDialog} needed here, passed rather than duplicated. */
    interface Widgets {
        <T> JComboBox<T> combo(T[] values);

        JTextField text(String value);

        JTextField readOnlyText(String value);

        JCheckBox check(String text, boolean selected);

        JButton button(String text);

        JLabel addRow(JComponent panel, int row, String labelKey, JComponent field,
                      JComponent trailing);

        /**
         * Adds a row made of the checkbox alone, in the label's column, with the tooltip of
         * {@code <key>.tip}: the shape of every "Expert mode" entry of the popup.
         */
        void addCheckRow(JComponent panel, int row, String key, JCheckBox check);

        void setRowVisible(boolean visible, List<JComponent> components);

        /**
         * Applies the tooltip of {@code <baseKey>.tip} to the given components.
         *
         * <p>Needed because {@code addRow} puts it only on label and field: the controls
         * <b>trailing the row</b> (the checkboxes next to it, the "Copy" buttons) would be
         * left without. They are options in their own right, so they must explain
         * themselves too.
         */
        void tip(String baseKey, JComponent... components);

        void error(String message);

        void closeDialog();
    }

    // =====================================================================
    // Drop-down entries
    // =====================================================================

    enum Mode {
        OFF, HOST, JOIN, WATCH, SERIAL_HOST, SERIAL_JOIN;

        /** Whether this mode connects to someone else's machine to receive its picture. */
        boolean joins() {
            return this == JOIN || this == WATCH;
        }

        /** Whether this mode is an end of a null modem cable. */
        boolean serial() {
            return this == SERIAL_HOST || this == SERIAL_JOIN;
        }

        /** The modes offered when there is no serial port. */
        static Mode[] streamModes() {
            return new Mode[] { OFF, HOST, JOIN, WATCH };
        }

        @Override
        public String toString() {
            return Messages.t("remoteplay.mode."
                    + name().toLowerCase(java.util.Locale.ROOT).replace("_", ""));
        }
    }

    /** Who may join a host. */
    enum Access {
        LAN, INTERNET;

        @Override
        public String toString() {
            return Messages.t(this == LAN ? "remoteplay.access.lan" : "remoteplay.access.internet");
        }
    }

    enum GuestPort {
        PORT0, PORT1;

        @Override
        public String toString() {
            return Messages.t(this == PORT0 ? "remoteplay.guestport.port0"
                    : "remoteplay.guestport.port1");
        }
    }

    enum AudioMode {
        ADPCM("adpcm"), PCM16("pcm16"), OFF("off");

        private final String configValue;

        AudioMode(String configValue) {
            this.configValue = configValue;
        }

        String configValue() {
            return configValue;
        }

        static AudioMode fromConfig(String value) {
            for (AudioMode m : values()) {
                if (m.configValue.equals(value)) {
                    return m;
                }
            }
            return ADPCM;
        }

        @Override
        public String toString() {
            return Messages.t("remoteplay.audio." + configValue);
        }
    }

    /** Convenience for building component lists without nulls. */
    static List<JComponent> row(JComponent... components) {
        List<JComponent> list = new ArrayList<>(components.length);
        for (JComponent c : components) {
            if (c != null) {
                list.add(c);
            }
        }
        return list;
    }
}
