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
import io.github.zolfrik.amija.net.RemotePlayControl;

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
 * <h2>Three modes, and the rows that appear</h2>
 * The {@code Off / Host / Join} selector shows and hides the rows of the chosen role with
 * {@code setRowVisible}, as Auto mode already does. The same button changes label and action
 * ("Host" becomes "Stop hosting") instead of showing two side by side, one of them always
 * off.
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

    /** Like {@link #build(AmigaConfig, RemotePlayControl, java.util.function.Function,
     * Widgets, Runnable)}, with nothing to realign outside this section. */
    static Built build(AmigaConfig config, RemotePlayControl control,
                       java.util.function.Function<String, JComponent> section,
                       Widgets widgets) {
        return build(config, control, section, widgets, null);
    }

    /**
     * Builds the section.
     *
     * @param control  the real remote play, which lives outside the popup
     * @param section  factory of panels in the same style as the other sections
     * @param alsoSync other rows of the popup that depend on the session state and must be
     *                 realigned with these: today the game ports, one of which changes owner
     *                 the moment hosting starts. It is here and not on a timer of its own
     *                 because the <b>same</b> event moves them: two timers would mean two
     *                 different moments at which the popup says two different things.
     *                 {@code null} if there is nothing to realign
     */
    static Built build(AmigaConfig config, RemotePlayControl control,
                       java.util.function.Function<String, JComponent> section,
                       Widgets widgets, Runnable alsoSync) {
        JComponent panel = section.apply(Messages.t("section.remoteplay"));
        Outcome[] outcome = { Outcome.NONE };
        // If already a guest when the popup opened, the machine is already unmounted and
        // there is no transition to ask for at closing: the JOIN outcome belongs only to the
        // sessions opened by this popup.
        final boolean guestAtOpen = control.isGuest();

        // --- Modo -----------------------------------------------------------------
        JComboBox<Mode> modeCombo = widgets.combo(Mode.values());
        modeCombo.setSelectedItem(control.isGuest() ? Mode.JOIN
                : control.isHosting() ? Mode.HOST : Mode.OFF);
        widgets.addRow(panel, 0, "label.remoteplay.mode", modeCombo, null);

        // --- Host rows ---------------------------------------------------------------
        JTextField portField = widgets.text(Integer.toString(config.remotePlayPort()));
        JCheckBox upnpCheck = widgets.check(Messages.t("label.remoteplay.upnp"),
                config.remotePlayUpnp());
        JLabel portLabel = widgets.addRow(panel, 1, "label.remoteplay.port",
                portField, upnpCheck);

        JCheckBox lanOnlyCheck = widgets.check(Messages.t("label.remoteplay.lanonly"),
                config.remotePlayLanOnly());
        JLabel lanOnlyLabel = widgets.addRow(panel, 2, "label.remoteplay.security",
                lanOnlyCheck, null);

        JButton hostButton = widgets.button(Messages.t("button.remoteplay.start"));
        JLabel hostButtonLabel = widgets.addRow(panel, 3, "label.remoteplay.session",
                hostButton, null);

        JTextField addressField = widgets.readOnlyText("");
        JButton copyAddress = widgets.button(Messages.t("button.remoteplay.copy"));
        JLabel addressLabel = widgets.addRow(panel, 4, "label.remoteplay.address",
                addressField, copyAddress);

        // Before UPnP there was no honest way to know the public address. When the mapping
        // fails, this field carries the DIAGNOSIS instead of the address: it is the same
        // question ("can I be reached from outside?") and this is the answer needed.
        JTextField internetField = widgets.readOnlyText("");
        JButton copyInternet = widgets.button(Messages.t("button.remoteplay.copy"));
        JLabel internetLabel = widgets.addRow(panel, 5, "label.remoteplay.internet",
                internetField, copyInternet);

        JTextField codeField = widgets.readOnlyText("");
        JButton copyCode = widgets.button(Messages.t("button.remoteplay.copy"));
        JLabel codeLabel = widgets.addRow(panel, 6, "label.remoteplay.joincode",
                codeField, copyCode);

        JComboBox<GuestPort> guestPortCombo = widgets.combo(GuestPort.values());
        guestPortCombo.setSelectedItem(config.remotePlayGuestPort() == 0
                ? GuestPort.PORT0 : GuestPort.PORT1);
        JCheckBox shareKeyboard = widgets.check(Messages.t("label.remoteplay.guestkeyboard"),
                config.remotePlayGuestKeyboard());
        JLabel guestPortLabel = widgets.addRow(panel, 7, "label.remoteplay.guestport",
                guestPortCombo, shareKeyboard);

        JCheckBox allowReset = widgets.check(Messages.t("label.remoteplay.guestreset"),
                config.remotePlayGuestAllowReset());
        JComboBox<AudioMode> audioCombo = widgets.combo(AudioMode.values());
        audioCombo.setSelectedItem(AudioMode.fromConfig(config.remotePlayAudioMode()));
        JLabel audioLabel = widgets.addRow(panel, 8, "label.remoteplay.audio",
                audioCombo, allowReset);

        // --- Joiner rows -------------------------------------------------------------
        JTextField hostAddressField = widgets.text(config.remotePlayLastHost());
        JLabel hostAddressLabel = widgets.addRow(panel, 9, "label.remoteplay.hostaddress",
                hostAddressField, null);

        JTextField joinCodeField = widgets.text("");
        JLabel joinCodeLabel = widgets.addRow(panel, 10, "label.remoteplay.entercode",
                joinCodeField, null);

        JButton joinButton = widgets.button(Messages.t("button.remoteplay.connect"));
        JLabel joinButtonLabel = widgets.addRow(panel, 11, "label.remoteplay.session2",
                joinButton, null);

        // --- Status, always visible -------------------------------------------------
        JTextField statusField = widgets.readOnlyText("");
        JLabel statusLabel = widgets.addRow(panel, 12, "label.remoteplay.status",
                statusField, null);

        List<JComponent> hostRows = List.of(portLabel, portField, upnpCheck,
                lanOnlyLabel, lanOnlyCheck, hostButtonLabel, hostButton);
        List<JComponent> hostLiveRows = List.of(addressLabel, addressField, copyAddress,
                internetLabel, internetField, copyInternet,
                codeLabel, codeField, copyCode);
        List<JComponent> hostOptionRows = List.of(guestPortLabel, guestPortCombo,
                shareKeyboard, audioLabel, audioCombo, allowReset);
        List<JComponent> joinRows = List.of(hostAddressLabel, hostAddressField,
                joinCodeLabel, joinCodeField, joinButtonLabel, joinButton);

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
            if (guest || connecting) {
                modeCombo.setSelectedItem(Mode.JOIN);   // re-enters here, and the second time it is a no-op
            }
            Mode mode = (Mode) modeCombo.getSelectedItem();

            widgets.setRowVisible(mode == Mode.HOST, hostRows);
            widgets.setRowVisible(mode == Mode.HOST && hosting, hostLiveRows);
            widgets.setRowVisible(mode == Mode.HOST, hostOptionRows);
            widgets.setRowVisible(mode == Mode.JOIN, joinRows);

            hostButton.setText(Messages.t(hosting
                    ? "button.remoteplay.stop" : "button.remoteplay.start"));
            joinButton.setText(Messages.t(guest
                    ? "button.remoteplay.disconnect" : "button.remoteplay.connect"));
            // While the connection is in flight there is nothing to press and nothing to
            // change: the address and code of that attempt have already left.
            joinButton.setEnabled(!connecting);
            hostAddressField.setEnabled(!connecting && !guest);
            joinCodeField.setEnabled(!connecting && !guest);
            // The outcome to apply at closing is not a separate choice: it is simply "this
            // popup opened a session", and must be re-read at every round because the session
            // may have closed meanwhile.
            if (outcome[0] != Outcome.LEAVE) {
                outcome[0] = !guestAtOpen && guest ? Outcome.JOIN : Outcome.NONE;
            }
            // While hosting, the parameters of the running session are not touched: changing
            // them under a connected guest means nothing.
            portField.setEnabled(!hosting);
            upnpCheck.setEnabled(!hosting);
            lanOnlyCheck.setEnabled(!hosting);
            guestPortCombo.setEnabled(!hosting);
            shareKeyboard.setEnabled(!hosting);
            allowReset.setEnabled(!hosting);
            audioCombo.setEnabled(!hosting);

            if (hosting) {
                addressField.setText(nullToEmpty(control.lanAddress()));
                codeField.setText(nullToEmpty(control.joinCode()));
                // Either the public address, or the reason there is none. The "Copy" button
                // applies only to the first: copying an error message to the clipboard would
                // be an invitation to paste it to the other player.
                String internet = control.internetAddress();
                internetField.setText(internet != null ? internet
                        : nullToEmpty(control.routerStatus()));
                copyInternet.setEnabled(internet != null);
            }
            statusField.setText(control.status());
            if (alsoSync != null) {
                alsoSync.run();
            }
        };

        modeCombo.addActionListener(e -> sync.run());

        hostButton.addActionListener(e -> {
            if (control.isHosting()) {
                control.stopHosting();
            } else {
                // The popup's values go into the configuration BEFORE switching on: that is
                // where startHosting() reads port, game port and audio from.
                applyHostFields(config, portField, upnpCheck, lanOnlyCheck,
                        guestPortCombo, shareKeyboard, allowReset, audioCombo);
                String error = control.startHosting();
                if (error != null) {
                    widgets.error(error);
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
            // The connection starts and returns at once: the status line says how it is
            // going, and if it fails the user gets a message anyway.
            String error = control.requestJoin(hostAddressField.getText(),
                    joinCodeField.getText());
            if (error != null) {
                widgets.error(error);
            }
            sync.run();
        });

        copyAddress.addActionListener(e -> copy(addressField.getText()));
        copyCode.addActionListener(e -> copy(codeField.getText()));
        copyInternet.addActionListener(e -> copy(internetField.getText()));

        // The trailing controls of the row: addRow does not touch them, and without these
        // calls their .tip would be text written and never shown.
        widgets.tip("label.remoteplay.upnp", upnpCheck);
        widgets.tip("label.remoteplay.guestkeyboard", shareKeyboard);
        widgets.tip("label.remoteplay.guestreset", allowReset);
        widgets.tip("button.remoteplay.copy", copyAddress, copyCode, copyInternet);
        widgets.tip("label.remoteplay.session", hostButton);
        widgets.tip("label.remoteplay.session2", joinButton);

        // The guest can connect or drop while the window is open: without this, the state
        // shown would be that of when F12 was pressed.
        Timer timer = new Timer(REFRESH_MS, e -> sync.run());
        timer.start();
        sync.run();

        Runnable applyToConfig = () -> applyHostFields(config, portField, upnpCheck,
                lanOnlyCheck, guestPortCombo, shareKeyboard, allowReset, audioCombo);

        return new Built(panel, () -> outcome[0], applyToConfig, timer::stop);
    }

    // =====================================================================

    private static void applyHostFields(AmigaConfig config, JTextField portField,
            JCheckBox upnp, JCheckBox lanOnly, JComboBox<GuestPort> guestPort,
            JCheckBox keyboard, JCheckBox allowReset, JComboBox<AudioMode> audio) {
        try {
            int port = Integer.parseInt(portField.getText().trim());
            if (port > 0 && port <= 65535) {
                config.setRemotePlayPort(port);
            }
        } catch (NumberFormatException ignored) {
            // Port mistyped: keep the previous one instead of refusing the whole popup over
            // one field. The status line will say which port is being listened on.
        }
        config.setRemotePlayUpnp(upnp.isSelected());
        config.setRemotePlayLanOnly(lanOnly.isSelected());
        config.setRemotePlayGuestPort(
                guestPort.getSelectedItem() == GuestPort.PORT0 ? 0 : 1);
        config.setRemotePlayGuestKeyboard(keyboard.isSelected());
        config.setRemotePlayGuestAllowReset(allowReset.isSelected());
        config.setRemotePlayAudioMode(((AudioMode) audio.getSelectedItem()).configValue());
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
        OFF, HOST, JOIN;

        @Override
        public String toString() {
            return Messages.t("remoteplay.mode." + name().toLowerCase(java.util.Locale.ROOT));
        }
    }

    enum GuestPort {
        PORT0, PORT1;

        @Override
        public String toString() {
            return Messages.t(this == PORT0 ? "label.port0" : "label.port1");
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
