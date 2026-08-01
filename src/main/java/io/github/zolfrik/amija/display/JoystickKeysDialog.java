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

import io.github.zolfrik.amija.i18n.Messages;
import io.github.zolfrik.amija.input.JoystickAction;
import io.github.zolfrik.amija.input.JoystickKeyMap;
import io.github.zolfrik.amija.input.KeyBinding;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.EnumMap;
import java.util.Map;

/**
 * Popup di configurazione dei tasti del joystick da tastiera di una game port.
 *
 * <p>Una riga per funzione (Su / Giù / Destra / Sinistra / Fuoco): l'etichetta e un
 * pulsante che mostra il tasto attualmente associato. Premendo il pulsante il dialog
 * entra in <b>cattura</b>: il primo tasto premuto diventa la nuova associazione, ESC
 * annulla (e non è quindi assegnabile ad alcuna funzione).
 *
 * <p>Un tasto non può essere usato da due funzioni, né dallo stesso joystick né da
 * quello dell'altra porta: in tal caso l'utente riceve un messaggio che indica a quale
 * funzione (e di quale porta) il tasto è già assegnato, e la scelta viene rifiutata.
 *
 * <p>Le modifiche sono applicate alla mappa del chiamante alla chiusura del popup; il
 * pulsante "Ripristina default" riporta la porta ai tasti predefiniti dell'emulatore.
 */
public final class JoystickKeysDialog {

    private static final Color BG       = new Color(0x2B, 0x2D, 0x31);
    private static final Color PANEL_BG = new Color(0x33, 0x36, 0x3B);
    private static final Color FG       = new Color(0xE6, 0xE6, 0xE8);
    private static final Color SUBFG    = new Color(0xA8, 0xAC, 0xB3);
    private static final Color ACCENT   = new Color(0x4D, 0x9D, 0xE0);
    private static final Color BORDER   = new Color(0x45, 0x49, 0x50);

    private static final Dimension KEY_BUTTON_SIZE = new Dimension(190, 30);

    private JoystickKeysDialog() { }

    /**
     * Mostra il popup (modale) per la porta {@code portIndex} (0 o 1).
     *
     * @param maps mappe di lavoro delle due porte, indicizzate per numero di porta:
     *             quella della porta in esame viene aggiornata alla chiusura, l'altra
     *             è usata (in sola lettura) per rilevare i tasti già occupati.
     */
    public static void show(Window parent, int portIndex, JoystickKeyMap[] maps) {
        new Editor(parent, portIndex, maps).open();
    }

    /** Stato e comportamento di una singola apertura del popup. */
    private static final class Editor {

        private final int portIndex;
        private final JoystickKeyMap[] maps;
        /** Copia di lavoro: confermata su {@code maps[portIndex]} alla chiusura. */
        private final JoystickKeyMap working;
        /** Mappa dell'altra porta, per il controllo dei tasti già assegnati. */
        private final JoystickKeyMap other;

        private final JDialog dialog;
        private final Map<JoystickAction, JButton> keyButtons =
                new EnumMap<>(JoystickAction.class);
        private final JLabel hint = new JLabel();
        private JButton okButton;

        /** Funzione in attesa di un tasto, o {@code null} se non si sta catturando. */
        private JoystickAction capturing;
        private KeyEventDispatcher captureDispatcher;
        /** Aspetto del pulsante prima dell'evidenziazione, da ripristinare a fine cattura. */
        private Color savedBackground;
        private Border savedBorder;
        private Font savedFont;

        Editor(Window parent, int portIndex, JoystickKeyMap[] maps) {
            this.portIndex = portIndex;
            this.maps      = maps;
            this.working   = maps[portIndex].copy();
            this.other     = maps[1 - portIndex];
            this.dialog    = new JDialog(parent, Messages.t("joykeys.title"),
                    Dialog.ModalityType.APPLICATION_MODAL);
        }

        void open() {
            dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

            JPanel content = new JPanel(new BorderLayout());
            content.setBackground(BG);
            content.add(buildHeader(), BorderLayout.NORTH);
            content.add(buildKeyRows(), BorderLayout.CENTER);

            dialog.getContentPane().setBackground(BG);
            dialog.getContentPane().setLayout(new BorderLayout());
            dialog.getContentPane().add(content, BorderLayout.CENTER);
            dialog.getContentPane().add(buildButtonBar(), BorderLayout.SOUTH);

            // ESC chiude il popup solo quando non si sta catturando un tasto: durante la
            // cattura l'evento è intercettato dal dispatcher, che lo usa per annullare.
            dialog.getRootPane().registerKeyboardAction(e -> confirmAndClose(),
                    KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                    JComponent.WHEN_IN_FOCUSED_WINDOW);
            dialog.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    confirmAndClose();
                }

                @Override
                public void windowOpened(WindowEvent e) {
                    // Il fuoco parte da OK: nessun pulsante dei tasti nasce con l'alone
                    // del fuoco, che si confonderebbe con l'evidenziazione della cattura.
                    if (okButton != null) okButton.requestFocusInWindow();
                }
            });

            dialog.pack();
            dialog.setResizable(false);
            hint.setText(" "); // riserva l'altezza senza mostrare nulla a riposo
            dialog.setLocationRelativeTo(parent());
            dialog.setVisible(true); // bloccante (modale)
        }

        private Window parent() {
            return dialog.getOwner();
        }

        // -----------------------------------------------------------------
        // Costruzione dell'interfaccia
        // -----------------------------------------------------------------

        private JPanel buildHeader() {
            JPanel header = new JPanel();
            header.setOpaque(false);
            header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
            header.setBorder(BorderFactory.createEmptyBorder(14, 18, 6, 18));

            JLabel title = new JLabel(Messages.t("joykeys.header", portIndex));
            title.setForeground(FG);
            title.setFont(title.getFont().deriveFont(Font.BOLD, 15f));
            title.setAlignmentX(Component.LEFT_ALIGNMENT);

            JLabel sub = new JLabel(Messages.t("joykeys.subtitle"));
            sub.setForeground(SUBFG);
            sub.setFont(sub.getFont().deriveFont(Font.PLAIN, 11f));
            sub.setAlignmentX(Component.LEFT_ALIGNMENT);

            hint.setForeground(ACCENT);
            hint.setFont(hint.getFont().deriveFont(Font.PLAIN, 11f));
            hint.setAlignmentX(Component.LEFT_ALIGNMENT);
            // Al momento del pack la riga di stato contiene il messaggio di cattura più
            // lungo: così il popup nasce già abbastanza largo da contenerli tutti e il
            // layout non cambia quando la cattura inizia (viene svuotata subito dopo).
            hint.setText(longestCapturingMessage());

            header.add(title);
            header.add(Box.createVerticalStrut(3));
            header.add(sub);
            header.add(Box.createVerticalStrut(6));
            header.add(hint);
            return header;
        }

        private JPanel buildKeyRows() {
            JPanel panel = new JPanel(new GridBagLayout());
            panel.setBackground(PANEL_BG);
            panel.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createEmptyBorder(6, 18, 12, 18),
                    BorderFactory.createCompoundBorder(
                            BorderFactory.createLineBorder(BORDER),
                            BorderFactory.createEmptyBorder(10, 12, 10, 12))));

            int row = 0;
            for (JoystickAction action : JoystickAction.values()) {
                JLabel label = new JLabel(Messages.t(action.messageKey()));
                label.setForeground(FG);

                // Il testo è nero: lo sfondo del pulsante lo disegna il look and feel
                // (chiaro), quindi un colore chiaro renderebbe il tasto illeggibile.
                JButton keyButton = new JButton();
                keyButton.setFocusPainted(false);
                keyButton.setForeground(Color.BLACK);
                keyButton.setPreferredSize(KEY_BUTTON_SIZE);
                keyButton.setMinimumSize(KEY_BUTTON_SIZE);
                keyButton.setToolTipText(Messages.t("joykeys.button.tooltip"));
                keyButton.addActionListener(e -> startCapture(action));
                keyButtons.put(action, keyButton);
                showBinding(action);

                GridBagConstraints gc = new GridBagConstraints();
                gc.gridy = row++;
                gc.insets = new Insets(4, 4, 4, 4);
                gc.anchor = GridBagConstraints.WEST;
                gc.gridx = 0;
                gc.weightx = 1;
                gc.fill = GridBagConstraints.HORIZONTAL;
                panel.add(label, gc);
                gc.gridx = 1;
                gc.weightx = 0;
                gc.fill = GridBagConstraints.NONE;
                panel.add(keyButton, gc);
            }
            return panel;
        }

        private JPanel buildButtonBar() {
            JButton reset = button(Messages.t("button.resetDefaults"), false);
            JButton ok    = button(Messages.t("button.ok"), true);
            okButton = ok;
            reset.addActionListener(e -> resetToDefaults());
            ok.addActionListener(e -> confirmAndClose());

            JPanel bar = new JPanel(new BorderLayout());
            bar.setBackground(PANEL_BG);
            bar.setBorder(BorderFactory.createEmptyBorder(10, 16, 10, 16));
            JPanel left = new JPanel();
            left.setOpaque(false);
            left.add(reset);
            JPanel right = new JPanel();
            right.setOpaque(false);
            right.add(ok);
            bar.add(left, BorderLayout.WEST);
            bar.add(right, BorderLayout.EAST);

            dialog.getRootPane().setDefaultButton(ok);
            return bar;
        }

        // -----------------------------------------------------------------
        // Cattura del tasto
        // -----------------------------------------------------------------

        /**
         * Mette il popup in attesa del prossimo tasto per {@code action}. Finché la
         * cattura è attiva tutti gli eventi di tastiera dell'applicazione sono
         * intercettati (e consumati), così nessun tasto — nemmeno INVIO o SPAZIO —
         * finisce ai pulsanti del dialog.
         */
        private void startCapture(JoystickAction action) {
            cancelCapture();
            capturing = action;

            // Evidenzia il pulsante in attesa. Il bordo colorato è l'unico segnale che
            // ogni look and feel rispetta: lo sfondo dei pulsanti alcuni lo disegnano da sé.
            JButton b = keyButtons.get(action);
            savedBackground = b.getBackground();
            savedBorder     = b.getBorder();
            savedFont       = b.getFont();
            b.setText(Messages.t("joykeys.press"));
            b.setBackground(ACCENT);
            b.setBorder(BorderFactory.createLineBorder(ACCENT, 2));
            b.setFont(savedFont.deriveFont(Font.BOLD));
            hint.setText(Messages.t("joykeys.capturing", Messages.t(action.messageKey())));

            captureDispatcher = e -> {
                if (e.getID() != KeyEvent.KEY_PRESSED) return true;
                JoystickAction target = capturing;
                int code = e.getKeyCode();
                KeyBinding binding = KeyBinding.fromEvent(e);
                stopCapture();
                if (code != KeyEvent.VK_ESCAPE && code != KeyEvent.VK_UNDEFINED) {
                    // Fuori dal dispatch dell'evento: l'eventuale messaggio di conflitto
                    // è modale e non deve aprirsi dentro la catena di dispatch.
                    SwingUtilities.invokeLater(() -> assign(target, binding));
                }
                return true;
            };
            KeyboardFocusManager.getCurrentKeyboardFocusManager()
                    .addKeyEventDispatcher(captureDispatcher);
        }

        /** Termina la cattura in corso, ripristinando l'aspetto del pulsante. */
        private void stopCapture() {
            if (captureDispatcher != null) {
                KeyboardFocusManager.getCurrentKeyboardFocusManager()
                        .removeKeyEventDispatcher(captureDispatcher);
                captureDispatcher = null;
            }
            if (capturing != null) {
                JoystickAction action = capturing;
                capturing = null;
                JButton b = keyButtons.get(action);
                if (b != null) {
                    b.setBackground(savedBackground);
                    b.setBorder(savedBorder);
                    b.setFont(savedFont);
                }
                showBinding(action);
            }
            hint.setText(" ");
        }

        private void cancelCapture() {
            if (capturing != null) stopCapture();
        }

        /**
         * Assegna il tasto alla funzione, se libero. Un tasto già usato dall'altra porta
         * o da un'altra funzione della stessa porta viene rifiutato con un messaggio che
         * indica dove è già impegnato.
         */
        private void assign(JoystickAction action, KeyBinding binding) {
            if (action == null || binding == null) return;

            JoystickAction conflict = other.actionFor(binding);
            if (conflict != null) {
                warn(Messages.t("joykeys.conflict.other", displayName(binding),
                        Messages.t(conflict.messageKey()), 1 - portIndex));
                return;
            }
            conflict = working.actionFor(binding);
            if (conflict != null && conflict != action) {
                warn(Messages.t("joykeys.conflict.same", displayName(binding),
                        Messages.t(conflict.messageKey())));
                return;
            }

            working.set(action, binding);
            showBinding(action);
        }

        /** Riporta la porta ai tasti predefiniti, se nessuno è occupato dall'altra porta. */
        private void resetToDefaults() {
            cancelCapture();
            JoystickKeyMap defaults = JoystickKeyMap.defaults(portIndex);
            for (JoystickAction action : JoystickAction.values()) {
                KeyBinding binding = defaults.get(action);
                JoystickAction conflict = other.actionFor(binding);
                if (conflict != null) {
                    warn(Messages.t("joykeys.reset.conflict", displayName(binding),
                            Messages.t(conflict.messageKey()), 1 - portIndex));
                    return;
                }
            }
            working.copyFrom(defaults);
            for (JoystickAction action : JoystickAction.values()) showBinding(action);
        }

        private void confirmAndClose() {
            cancelCapture();
            maps[portIndex].copyFrom(working);
            dialog.dispose();
        }

        // -----------------------------------------------------------------
        // Utilità
        // -----------------------------------------------------------------

        /** Mostra sul pulsante il tasto attualmente associato alla funzione. */
        private void showBinding(JoystickAction action) {
            JButton b = keyButtons.get(action);
            if (b != null) b.setText(displayName(working.get(action)));
        }

        /** Messaggio di cattura più largo fra tutte le funzioni (per dimensionare il popup). */
        private static String longestCapturingMessage() {
            String longest = " ";
            for (JoystickAction action : JoystickAction.values()) {
                String msg = Messages.t("joykeys.capturing", Messages.t(action.messageKey()));
                if (msg.length() > longest.length()) longest = msg;
            }
            return longest;
        }

        private void warn(String message) {
            JOptionPane.showMessageDialog(dialog, message,
                    Messages.t("joykeys.conflict.title"), JOptionPane.WARNING_MESSAGE);
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
    }

    /**
     * Nome del tasto mostrato all'utente, qualificato con la posizione quando il tasto
     * esiste in più copie sulla tastiera (es. "Ctrl destro", "Shift sinistro").
     *
     * <p>Il nome base viene dal bundle dei messaggi ({@code key.name.<NOME VK>}), così da
     * seguire la lingua scelta per l'interfaccia; per i tasti senza voce dedicata —
     * lettere, cifre e funzione, il cui nome non ha bisogno di traduzione — si usa quello
     * fornito da AWT.
     */
    static String displayName(KeyBinding binding) {
        if (binding == null) return "";
        String name = binding.keyName();
        String text = (name != null) ? Messages.tOrNull("key.name." + name) : null;
        if (text == null) text = binding.keyText();
        String locationKey = binding.locationMessageKey();
        return (locationKey == null) ? text : Messages.t(locationKey, text);
    }
}
