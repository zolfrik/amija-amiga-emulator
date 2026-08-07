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
 * La sezione «Gioco a distanza» del popup F12 ({@code docs/remote-play-plan.md} §9).
 *
 * <p>Sta in una classe sua e non dentro {@code ConfigDialog} perche' quella e' gia' lunga
 * duemila righe, e perche' questa sezione ha una cosa che le altre non hanno: uno
 * <b>stato che cambia mentre il popup e' aperto</b>. Un ospite puo' collegarsi o cadere
 * mentre l'utente guarda la finestra, quindi c'e' un timer che riallinea i controlli, e
 * tutto il resto della classe esiste per tenere quel riallineamento in un posto solo.
 *
 * <h2>Tre modi, e le righe che compaiono</h2>
 * Il selettore {@code Off / Ospita / Unisciti} mostra e nasconde le righe del ruolo scelto
 * con {@code setRowVisible}, come fa gia' la modalita' Auto. Lo stesso pulsante cambia
 * etichetta e azione — «Ospita» diventa «Smetti di ospitare» — invece di affiancarne due,
 * di cui uno sempre spento.
 *
 * <h2>Nessun pulsante chiude il popup</h2>
 * Né ospitare né collegarsi chiudono la finestra: agiscono subito e il risultato si legge
 * nella <b>riga di stato</b>, che si aggiorna da sola. Collegarsi apre davvero la sessione —
 * il video comincia ad arrivare dietro al popup — ma <b>non smonta la macchina</b>: a quello
 * si arriva confermando con OK, ed è la sola cosa che questa sezione lascia fare a chi la
 * macchina la possiede (§3, {@link RemotePlayControl}).
 *
 * <p>La versione precedente chiudeva il popup alla pressione di «Collega» e si collegava
 * dopo: un fallimento non aveva più nessuna finestra in cui essere raccontato, e l'utente
 * restava davanti alla sua macchina senza sapere che il collegamento non c'era. L'unica
 * eccezione resta lasciare la sessione quando si è ospiti <b>davvero</b>: lì la macchina va
 * ricostruita, e ricostruirla da dentro un dialog modale è ciò che si evita.
 */
final class RemotePlaySection {

    /** Ogni quanto la riga di stato si riallinea alla realta'. */
    private static final int REFRESH_MS = 500;

    private RemotePlaySection() {
    }

    /** Cosa il popup deve fare alla chiusura, deciso qui dentro. */
    enum Outcome { NONE, JOIN, LEAVE }

    /** Quel che la sezione consegna al dialog: il pannello e l'esito da applicare. */
    record Built(JComponent panel, java.util.function.Supplier<Outcome> outcome,
                 Runnable applyToConfig, Runnable dispose) {
    }

    /** Come {@link #build(AmigaConfig, RemotePlayControl, java.util.function.Function,
     * Widgets, Runnable)}, senza niente da riallineare fuori da questa sezione. */
    static Built build(AmigaConfig config, RemotePlayControl control,
                       java.util.function.Function<String, JComponent> section,
                       Widgets widgets) {
        return build(config, control, section, widgets, null);
    }

    /**
     * Costruisce la sezione.
     *
     * @param control  il gioco a distanza vero, che vive fuori dal popup
     * @param section  fabbrica di pannelli dello stesso stile delle altre sezioni
     * @param alsoSync altre righe del popup che dipendono dallo stato della sessione e
     *                 vanno riallineate insieme a queste — oggi le game port, di cui una
     *                 cambia padrone nel momento in cui si comincia a ospitare. Sta qui e
     *                 non su un timer suo perche' e' lo <b>stesso</b> evento a muoverle:
     *                 due timer vorrebbero dire due momenti diversi in cui il popup dice
     *                 due cose diverse. {@code null} se non c'e' niente da riallineare
     */
    static Built build(AmigaConfig config, RemotePlayControl control,
                       java.util.function.Function<String, JComponent> section,
                       Widgets widgets, Runnable alsoSync) {
        JComponent panel = section.apply(Messages.t("section.remoteplay"));
        Outcome[] outcome = { Outcome.NONE };
        // Se si era già ospiti all'apertura, la macchina è già smontata e non c'è nessuna
        // transizione da chiedere alla chiusura: l'esito JOIN spetta solo alle sessioni
        // aperte da questo popup.
        final boolean guestAtOpen = control.isGuest();

        // --- Modo -----------------------------------------------------------------
        JComboBox<Mode> modeCombo = widgets.combo(Mode.values());
        modeCombo.setSelectedItem(control.isGuest() ? Mode.JOIN
                : control.isHosting() ? Mode.HOST : Mode.OFF);
        widgets.addRow(panel, 0, "label.remoteplay.mode", modeCombo, null);

        // --- Righe di chi ospita ---------------------------------------------------
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

        // La riga che il §9 disegnava e la Fase 5 non poteva riempire: prima di UPnP non
        // c'era nessun modo onesto di sapere l'indirizzo pubblico. Quando la mappatura non
        // riesce, questo campo porta la DIAGNOSI al posto dell'indirizzo — è la stessa
        // domanda («da fuori mi si raggiunge?») e questa è la risposta che serve.
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

        // --- Righe di chi si unisce ------------------------------------------------
        JTextField hostAddressField = widgets.text(config.remotePlayLastHost());
        JLabel hostAddressLabel = widgets.addRow(panel, 9, "label.remoteplay.hostaddress",
                hostAddressField, null);

        JTextField joinCodeField = widgets.text("");
        JLabel joinCodeLabel = widgets.addRow(panel, 10, "label.remoteplay.entercode",
                joinCodeField, null);

        JButton joinButton = widgets.button(Messages.t("button.remoteplay.connect"));
        JLabel joinButtonLabel = widgets.addRow(panel, 11, "label.remoteplay.session2",
                joinButton, null);

        // --- Stato, sempre visibile -------------------------------------------------
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

        // Un solo posto in cui i controlli si riallineano alla realta': lo chiamano il
        // cambio di modo, i pulsanti e il timer. Averne tre copie sarebbe il modo sicuro
        // di lasciarne una indietro.
        Runnable sync = () -> {
            boolean hosting = control.isHosting();
            boolean guest = control.isGuest();
            boolean connecting = control.isJoining();
            // Con una sessione aperta (o in apertura) il selettore non può dire altro: da
            // «Off» le righe sparirebbero, ma la sessione resterebbe viva e l'OK la
            // confermerebbe lo stesso. Il selettore mostra le righe di un ruolo; qui il
            // ruolo è già stato preso, e quel che si vede deve dirlo.
            if (guest || connecting) {
                modeCombo.setSelectedItem(Mode.JOIN);   // rientra qui, e la seconda volta è un no-op
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
            // Mentre il collegamento è in volo non c'è niente da premere e niente da
            // cambiare: l'indirizzo e il codice di quel tentativo sono già partiti.
            joinButton.setEnabled(!connecting);
            hostAddressField.setEnabled(!connecting && !guest);
            joinCodeField.setEnabled(!connecting && !guest);
            // L'esito da applicare alla chiusura non è una scelta a parte: è semplicemente
            // «questo popup ha aperto una sessione», e va riletto a ogni giro perché la
            // sessione può anche essersi chiusa nel frattempo.
            if (outcome[0] != Outcome.LEAVE) {
                outcome[0] = !guestAtOpen && guest ? Outcome.JOIN : Outcome.NONE;
            }
            // Mentre si ospita, i parametri della sessione in corso non si toccano:
            // cambiarli sotto i piedi di un ospite collegato non vuol dire niente.
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
                // O l'indirizzo pubblico, o il motivo per cui non c'è. Il pulsante «Copia»
                // vale solo per il primo: copiare negli appunti un messaggio d'errore
                // sarebbe un invito a incollarlo all'altro giocatore.
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
                // I valori del popup vanno nella configurazione PRIMA di accendere: e'
                // da li' che startHosting() legge porta, game port e audio.
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
                return;   // già in volo: il pulsante è spento, ma la scorciatoia di tastiera no
            }
            if (control.isGuest()) {
                if (control.requestLeave()) {
                    // Ospiti davvero: non c'è nessuna macchina, e ricostruirla vuole il
                    // popup chiuso. È l'unico caso in cui questa sezione lo chiude.
                    outcome[0] = Outcome.LEAVE;
                    widgets.closeDialog();
                    return;
                }
                sync.run();
                return;
            }
            // Il collegamento parte subito e ritorna subito: come sta andando lo dice la
            // riga di stato, e se fallisce l'utente riceve comunque un messaggio.
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

        // I controlli in coda alla riga: addRow non li tocca, e senza queste chiamate i
        // loro .tip sarebbero testo scritto e mai mostrato.
        widgets.tip("label.remoteplay.upnp", upnpCheck);
        widgets.tip("label.remoteplay.guestkeyboard", shareKeyboard);
        widgets.tip("label.remoteplay.guestreset", allowReset);
        widgets.tip("button.remoteplay.copy", copyAddress, copyCode, copyInternet);
        widgets.tip("label.remoteplay.session", hostButton);
        widgets.tip("label.remoteplay.session2", joinButton);

        // L'ospite puo' collegarsi o cadere mentre la finestra e' aperta: senza questo,
        // lo stato mostrato sarebbe quello di quando si e' premuto F12.
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
            // Porta scritta male: si tiene quella di prima invece di rifiutare l'intero
            // popup per un campo. La riga di stato dira' su quale porta si sta ascoltando.
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

    /** I pezzi di {@code ConfigDialog} che servono qui, passati invece che duplicati. */
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
         * Applica il tooltip di {@code <baseKey>.tip} ai componenti indicati.
         *
         * <p>Serve perche' {@code addRow} lo mette solo su etichetta e campo: i controlli
         * <b>in coda alla riga</b> — le spunte accanto, i pulsanti «Copia» — resterebbero
         * senza. E sono opzioni a tutti gli effetti, quindi devono spiegarsi anche loro.
         */
        void tip(String baseKey, JComponent... components);

        void error(String message);

        void closeDialog();
    }

    // =====================================================================
    // Voci delle tendine
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

    /** Comodita' per costruire liste di componenti senza null. */
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
