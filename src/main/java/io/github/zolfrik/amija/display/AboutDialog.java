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
 * "About" popup: who wrote AmiJa, which version is in use and where the code is.
 *
 * <p>The application logo on the left, three lines of text on the right (version, author,
 * repository address) and a single close button at the bottom. The address line is a link:
 * clicking it opens the system browser; where there is no browser to open ({@link Desktop} not
 * supported, headless environments) the address goes to the clipboard and the user is told,
 * so the popup is useful anyway.
 *
 * <p>The version is not written here: it comes from {@link Version}, i.e. from the
 * {@code version.properties} filtered by Maven with the pom's version.
 */
public final class AboutDialog {

    /** Public page of the project: the same one the window shows as a link. */
    private static final String REPOSITORY_URL =
            "https://github.com/zolfrik/amija-amiga-emulator";

    // Same palette as the F12 popup: this window is an offshoot of it, not something else.
    private static final Color BG        = new Color(0x2B, 0x2D, 0x31);
    private static final Color PANEL_BG  = new Color(0x33, 0x36, 0x3B);
    private static final Color FG        = new Color(0xE6, 0xE6, 0xE8);
    private static final Color SUBFG     = new Color(0xA8, 0xAC, 0xB3);
    private static final Color ACCENT    = new Color(0x4D, 0x9D, 0xE0);
    private static final Color ACCENT_HI = new Color(0x8A, 0xC4, 0xEE);
    private static final Color BORDER    = new Color(0x45, 0x49, 0x50);

    /** Side of the logo shown: the size is taken from the nearest larger icon. */
    private static final int LOGO_SIZE = 96;

    private AboutDialog() {}

    /** Shows the (modal) popup above {@code parent}. Returns when it closes. */
    public static void show(Window parent) {
        JDialog dialog = new JDialog(parent, Messages.t("about.title"),
                Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        // The logo in the title bar too: the window usually opens from a dialog that already
        // passes it its own icons, but it must not depend on that.
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
    // Building the interface
    // =========================================================================

    /** Logo on the left, text column on the right, both centred vertically. */
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

    /** The three lines: version, author, link to the repository. */
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
     * The repository address as a link: underlined, in the accent colour and with the hand
     * pointer, so it shows it is clickable before trying.
     */
    private static JLabel buildLink(JDialog dialog) {
        // The underline comes from HTML: JLabel has no attribute for it, and getting it from
        // the font would mean deriving it with TextAttribute attributes.
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

    /** Closing bar: a single button, in the centre. */
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
    // Utilities
    // =========================================================================

    /**
     * Opens the project page in the system browser. If there is no way to open it (platform
     * without {@link Desktop}, no browser, launch error) the address is copied to the clipboard
     * and the user is told: the link is never a dead end.
     */
    private static void openRepository(JDialog parent) {
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(REPOSITORY_URL));
                return;
            }
        } catch (Exception e) {
            // Browser cannot be started: fall back on the clipboard, as if there were none.
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
            // Clipboard not available: the address is still readable in the message.
        }
    }

    /**
     * The logo at the requested size, taken from the smallest icon that covers it (so it is
     * always scaled down, never up). {@code null} if the icons were not found: then the popup
     * shows the text alone.
     */
    private static Image logoImage(int size) {
        List<Image> icons = AppIcons.getIcons();
        if (icons.isEmpty()) return null;

        Image source = icons.get(icons.size() - 1); // the largest, if none is big enough
        for (Image icon : icons) { // the list is sorted from the smallest to the largest
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
