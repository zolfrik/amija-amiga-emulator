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

import io.github.zolfrik.amija.common.Version;
import io.github.zolfrik.amija.i18n.Messages;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.util.List;

/**
 * Popup "About": chi ha scritto AmiJa, che versione si sta usando e dove sta il codice.
 *
 * <p>Il logo dell'applicazione a sinistra, tre righe di testo a destra (versione, autore,
 * indirizzo del repository) e un solo pulsante di chiusura in fondo. La riga
 * dell'indirizzo è un collegamento: cliccandola si apre il browser di sistema; dove non
 * c'è un browser da aprire ({@link Desktop} non supportato, ambienti headless) l'indirizzo
 * finisce negli appunti e l'utente viene avvisato — così il popup è comunque utile.
 *
 * <p>La versione non è scritta qui: viene da {@link Version}, cioè dal
 * {@code version.properties} filtrato da Maven con la versione del pom.
 */
public final class AboutDialog {

    /** Pagina pubblica del progetto: la stessa che la finestra mostra come collegamento. */
    private static final String REPOSITORY_URL =
            "https://github.com/zolfrik/amija-amiga-emulator";

    // Stessa palette del popup F12: questa finestra ne è una costola, non un'altra cosa.
    private static final Color BG        = new Color(0x2B, 0x2D, 0x31);
    private static final Color PANEL_BG  = new Color(0x33, 0x36, 0x3B);
    private static final Color FG        = new Color(0xE6, 0xE6, 0xE8);
    private static final Color SUBFG     = new Color(0xA8, 0xAC, 0xB3);
    private static final Color ACCENT    = new Color(0x4D, 0x9D, 0xE0);
    private static final Color ACCENT_HI = new Color(0x8A, 0xC4, 0xEE);
    private static final Color BORDER    = new Color(0x45, 0x49, 0x50);

    /** Lato del logo mostrato: la misura si ricava dall'icona più vicina per eccesso. */
    private static final int LOGO_SIZE = 96;

    private AboutDialog() {}

    /** Mostra il popup (modale) sopra {@code parent}. Ritorna alla chiusura. */
    public static void show(Window parent) {
        JDialog dialog = new JDialog(parent, Messages.t("about.title"),
                Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        // Il logo anche nella barra del titolo: la finestra si apre di solito da un
        // dialog che le passa già le proprie icone, ma non deve dipenderne.
        AppIcons.apply(dialog);

        dialog.getContentPane().setBackground(BG);
        dialog.getContentPane().setLayout(new BorderLayout());
        dialog.getContentPane().add(buildBody(dialog), BorderLayout.CENTER);
        dialog.getContentPane().add(buildButtonBar(dialog), BorderLayout.SOUTH);

        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        dialog.pack();
        dialog.setResizable(false);
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true); // bloccante (modale)
    }

    // =========================================================================
    // Costruzione dell'interfaccia
    // =========================================================================

    /** Logo a sinistra, colonna di testo a destra, entrambi centrati verticalmente. */
    private static JPanel buildBody(JDialog dialog) {
        JPanel body = new JPanel(new GridBagLayout());
        body.setBackground(BG);
        body.setBorder(pad(22, 26, 22, 30));

        GridBagConstraints gc = new GridBagConstraints();
        gc.gridy = 0;
        gc.anchor = GridBagConstraints.CENTER;

        Image logo = logoImage(LOGO_SIZE);
        if (logo != null) {
            gc.gridx = 0;
            gc.insets = new Insets(px(0), px(0), px(0), px(24));
            body.add(new JLabel(new ImageIcon(logo)), gc);
        }

        gc.gridx = 1;
        gc.insets = new Insets(px(0), px(0), px(0), px(0));
        gc.anchor = GridBagConstraints.WEST;
        body.add(buildTextColumn(dialog), gc);
        return body;
    }

    /** Le tre righe: versione, autore, collegamento al repository. */
    private static JPanel buildTextColumn(JDialog dialog) {
        JPanel column = new JPanel();
        column.setOpaque(false);
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));

        JLabel version = new JLabel(Messages.t("about.version", Version.version()));
        version.setForeground(FG);
        version.setFont(derive(version.getFont(), Font.BOLD, 18f));
        version.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel author = new JLabel(Messages.t("about.author"));
        author.setForeground(SUBFG);
        author.setFont(derive(author.getFont(), Font.PLAIN, 13f));
        author.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel link = buildLink(dialog);
        link.setAlignmentX(Component.LEFT_ALIGNMENT);

        column.add(version);
        column.add(Box.createVerticalStrut(10));
        column.add(author);
        column.add(Box.createVerticalStrut(10));
        column.add(link);
        return column;
    }

    /**
     * L'indirizzo del repository come collegamento: sottolineato, del colore d'accento e
     * col puntatore a mano, perché si veda che è cliccabile prima di provarci.
     */
    private static JLabel buildLink(JDialog dialog) {
        // La sottolineatura viene dall'HTML: JLabel non ha un attributo per averla, e
        // ricavarla dal font significherebbe derivarlo con gli attributi di TextAttribute.
        JLabel link = new JLabel("<html><u>" + REPOSITORY_URL + "</u></html>");
        link.setForeground(ACCENT);
        link.setFont(derive(link.getFont(), Font.PLAIN, 13f));
        link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        link.setToolTipText(Messages.t("about.link.tip"));
        link.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                openRepository(dialog);
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                link.setForeground(ACCENT_HI);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                link.setForeground(ACCENT);
            }
        });
        return link;
    }

    /** Barra di chiusura: un solo pulsante, al centro. */
    private static JPanel buildButtonBar(JDialog dialog) {
        JButton ok = new JButton(Messages.t("button.ok"));
        ok.setFocusPainted(false);
        ok.setBackground(ACCENT);
        ok.setForeground(Color.BLACK);
        ok.setFont(ok.getFont().deriveFont(Font.BOLD));
        ok.addActionListener(e -> dialog.dispose());

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        bar.setBackground(PANEL_BG);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER),
                pad(12, 16, 12, 16)));
        bar.add(ok);

        dialog.getRootPane().setDefaultButton(ok);
        return bar;
    }

    // =========================================================================
    // Utilità
    // =========================================================================

    /**
     * Apre la pagina del progetto nel browser di sistema. Se non c'è modo di aprirla
     * (piattaforma senza {@link Desktop}, browser assente, errore di lancio) l'indirizzo
     * viene copiato negli appunti e lo si dice all'utente: il collegamento non resta
     * comunque un vicolo cieco.
     */
    private static void openRepository(JDialog parent) {
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(REPOSITORY_URL));
                return;
            }
        } catch (Exception e) {
            // Browser non avviabile: si ripiega sugli appunti, come se non ci fosse.
        }
        copyToClipboard();
        JOptionPane.showMessageDialog(parent,
                Messages.t("about.link.copied", REPOSITORY_URL),
                Messages.t("about.title"), JOptionPane.INFORMATION_MESSAGE);
    }

    private static void copyToClipboard() {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(REPOSITORY_URL), null);
        } catch (Exception e) {
            // Appunti non disponibili: l'indirizzo resta comunque leggibile nel messaggio.
        }
    }

    /**
     * Il logo alla misura richiesta, ricavato dall'icona più piccola che la copre (così
     * si scala sempre in riduzione, mai in ingrandimento). {@code null} se le icone non
     * sono state trovate — in quel caso il popup mostra il solo testo.
     */
    private static Image logoImage(int size) {
        List<Image> icons = AppIcons.getIcons();
        if (icons.isEmpty()) return null;

        Image source = icons.get(icons.size() - 1); // la più grande, se nessuna basta
        for (Image icon : icons) { // la lista è ordinata dalla più piccola alla più grande
            if (icon.getWidth(null) >= size) {
                source = icon;
                break;
            }
        }

        BufferedImage scaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = scaled.createGraphics();
        try {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.drawImage(source, 0, 0, size, size, null);
        } finally {
            g2.dispose();
        }
        return scaled;
    }
}
