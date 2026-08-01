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

import io.github.zolfrik.amija.assets.AssetCatalog;
import io.github.zolfrik.amija.assets.AssetInstaller;
import io.github.zolfrik.amija.assets.AssetPackage;
import io.github.zolfrik.amija.config.AmigaConfig;
import io.github.zolfrik.amija.i18n.Messages;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.border.TitledBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Assistente di download del materiale di terze parti che AmiJa non può
 * ridistribuire: i suoni del drive, WHDLoad, le tabelle di rilocazione.
 *
 * <p>La finestra <b>è</b> il consenso. Niente parte da sola: ogni pacchetto va
 * spuntato a mano, e prima della spunta l'utente vede a chi appartiene, da quale
 * URL arriva e quanto pesa. Il razionale — scaricare per conto dell'utente non è
 * ridistribuire — è in {@code THIRD-PARTY-NOTICES.md} §5.
 *
 * @see AssetCatalog
 */
public final class AssetDownloadDialog {

    private static final Color BG        = new Color(0x2B, 0x2D, 0x31);
    private static final Color PANEL_BG  = new Color(0x33, 0x36, 0x3B);
    private static final Color FG        = new Color(0xE6, 0xE6, 0xE8);
    private static final Color DIM       = new Color(0x9A, 0x9E, 0xA6);
    private static final Color ACCENT    = new Color(0x4D, 0x9D, 0xE0);
    private static final Color OK        = new Color(0x6C, 0xC6, 0x44);
    private static final Color WARN      = new Color(0xE0, 0xA8, 0x4D);

    private AssetDownloadDialog() {}

    /** Apre l'assistente, modale rispetto a {@code owner}. */
    public static void show(Window owner, AmigaConfig config) {
        List<AssetPackage> packages = AssetCatalog.packages(config);

        JDialog dialog = new JDialog(owner, Messages.t("assets.title"),
                JDialog.ModalityType.APPLICATION_MODAL);
        dialog.getContentPane().setBackground(BG);
        dialog.setLayout(new BorderLayout(0, 10));

        // --- Intestazione: cosa sta per succedere, e perché è lecito. -------
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setBackground(BG);
        header.setBorder(BorderFactory.createEmptyBorder(12, 14, 0, 14));
        header.add(wrapped(Messages.t("assets.intro"), FG, 460));
        header.add(Box.createVerticalStrut(6));
        header.add(wrapped(Messages.t("assets.intro.legal"), DIM, 460));
        dialog.add(header, BorderLayout.NORTH);

        // --- Un riquadro per pacchetto. -------------------------------------
        JPanel list = new JPanel();
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setBackground(BG);
        list.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));

        Map<AssetPackage, JCheckBox> boxes = new LinkedHashMap<>();
        Map<AssetPackage, JLabel> states = new LinkedHashMap<>();
        for (AssetPackage pkg : packages) {
            JPanel card = new JPanel();
            card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
            card.setBackground(PANEL_BG);
            card.setAlignmentX(Component.LEFT_ALIGNMENT);
            TitledBorder tb = BorderFactory.createTitledBorder(
                    BorderFactory.createLineBorder(new Color(0x45, 0x49, 0x50)), pkg.displayName());
            tb.setTitleColor(ACCENT);
            tb.setTitleFont(card.getFont().deriveFont(Font.BOLD, 13f));
            card.setBorder(BorderFactory.createCompoundBorder(tb,
                    BorderFactory.createEmptyBorder(6, 10, 8, 10)));

            JCheckBox box = new JCheckBox(pkg.description());
            box.setBackground(PANEL_BG);
            box.setForeground(FG);
            box.setAlignmentX(Component.LEFT_ALIGNMENT);
            // Già installato ⇒ non lo si riscarica per sbaglio; resta comunque
            // spuntabile a mano, che è il modo per aggiornarlo.
            box.setSelected(!pkg.installed());
            card.add(box);

            JLabel state = small(stateText(pkg), pkg.installed() ? OK : DIM);
            card.add(state);
            card.add(small(Messages.t("assets.owner", pkg.rightsHolder()), DIM));
            card.add(small(pkg.url() + "  (" + mib(pkg.approxBytes()) + ")", DIM));
            card.add(small(Messages.t("assets.dest", pkg.destDir().toString()), DIM));

            boxes.put(pkg, box);
            states.put(pkg, state);
            list.add(card);
            list.add(Box.createVerticalStrut(8));
        }

        JScrollPane scroll = new JScrollPane(list,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getViewport().setBackground(BG);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setPreferredSize(new Dimension(520, 340));
        dialog.add(scroll, BorderLayout.CENTER);

        // --- Avanzamento e comandi. -----------------------------------------
        JPanel south = new JPanel();
        south.setLayout(new BoxLayout(south, BoxLayout.Y_AXIS));
        south.setBackground(BG);
        south.setBorder(BorderFactory.createEmptyBorder(0, 14, 12, 14));

        JProgressBar bar = new JProgressBar(0, 1000);
        bar.setAlignmentX(Component.LEFT_ALIGNMENT);
        bar.setVisible(false);
        JLabel status = small(" ", DIM);
        status.setAlignmentX(Component.LEFT_ALIGNMENT);

        JButton download = new JButton(Messages.t("assets.download"));
        download.setBackground(ACCENT);
        download.setForeground(Color.BLACK);
        download.setFont(download.getFont().deriveFont(Font.BOLD));
        download.setFocusPainted(false);
        JButton close = new JButton(Messages.t("assets.close"));
        close.setFocusPainted(false);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.setBackground(BG);
        buttons.setAlignmentX(Component.LEFT_ALIGNMENT);
        buttons.add(download);
        buttons.add(close);

        south.add(bar);
        south.add(Box.createVerticalStrut(4));
        south.add(status);
        south.add(Box.createVerticalStrut(8));
        south.add(buttons);
        dialog.add(south, BorderLayout.SOUTH);

        close.addActionListener(e -> dialog.dispose());
        download.addActionListener(e -> {
            List<AssetPackage> selected = new ArrayList<>();
            for (Map.Entry<AssetPackage, JCheckBox> entry : boxes.entrySet()) {
                if (entry.getValue().isSelected()) selected.add(entry.getKey());
            }
            if (selected.isEmpty()) {
                status.setText(Messages.t("assets.status.none"));
                status.setForeground(WARN);
                return;
            }
            download.setEnabled(false);
            boxes.values().forEach(b -> b.setEnabled(false));
            bar.setVisible(true);
            new Worker(selected, boxes, states, bar, status, download).execute();
        });

        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }

    // =========================================================================

    /** Esegue i download fuori dall'EDT, riportando l'avanzamento sulla finestra. */
    private static final class Worker extends SwingWorker<Void, Object[]> {

        private final List<AssetPackage> selected;
        private final Map<AssetPackage, JCheckBox> boxes;
        private final Map<AssetPackage, JLabel> states;
        private final JProgressBar bar;
        private final JLabel status;
        private final JButton download;
        private final List<String> warnings = new ArrayList<>();
        private int failed = 0;

        Worker(List<AssetPackage> selected, Map<AssetPackage, JCheckBox> boxes,
               Map<AssetPackage, JLabel> states, JProgressBar bar, JLabel status, JButton download) {
            this.selected = selected;
            this.boxes = boxes;
            this.states = states;
            this.bar = bar;
            this.status = status;
            this.download = download;
        }

        @Override
        protected Void doInBackground() {
            for (AssetPackage pkg : selected) {
                if (isCancelled()) break;
                try {
                    AssetInstaller.Result result = AssetInstaller.install(pkg, new AssetInstaller.Progress() {
                        @Override public void stage(String message) {
                            publish(new Object[] { "stage", message });
                        }
                        @Override public void bytes(long done, long total) {
                            publish(new Object[] { "bytes", done, total });
                        }
                        @Override public boolean cancelled() {
                            return isCancelled();
                        }
                    });
                    warnings.addAll(result.warnings());
                    publish(new Object[] { "done", pkg, result.files() });
                } catch (Exception ex) {
                    failed++;
                    // L'utente deve poter rimediare a mano: il messaggio porta con sé
                    // URL e destinazione (li compone AssetInstaller).
                    String reason = (ex.getMessage() != null) ? ex.getMessage() : ex.toString();
                    System.err.println("[ASSETS] " + pkg.id() + ": " + reason);
                    publish(new Object[] { "failed", pkg, reason });
                }
            }
            return null;
        }

        @Override
        protected void process(List<Object[]> chunks) {
            for (Object[] c : chunks) {
                switch ((String) c[0]) {
                    case "stage" -> {
                        status.setText((String) c[1]);
                        status.setForeground(DIM);
                    }
                    case "bytes" -> {
                        long done = (Long) c[1];
                        long total = (Long) c[2];
                        if (total > 0) {
                            bar.setIndeterminate(false);
                            bar.setValue((int) Math.min(1000, done * 1000 / total));
                        } else {
                            bar.setIndeterminate(true);
                        }
                    }
                    case "done" -> {
                        AssetPackage pkg = (AssetPackage) c[1];
                        JLabel state = states.get(pkg);
                        state.setText(Messages.t("assets.state.installed.now", String.valueOf(c[2])));
                        state.setForeground(OK);
                        boxes.get(pkg).setSelected(false);
                    }
                    case "failed" -> {
                        AssetPackage pkg = (AssetPackage) c[1];
                        JLabel state = states.get(pkg);
                        state.setText(Messages.t("assets.state.failed", (String) c[2]));
                        state.setForeground(WARN);
                    }
                    default -> { }
                }
            }
        }

        @Override
        protected void done() {
            bar.setVisible(false);
            boxes.values().forEach(b -> b.setEnabled(true));
            download.setEnabled(true);
            if (failed > 0) {
                status.setText(Messages.t("assets.status.failed", String.valueOf(failed)));
                status.setForeground(WARN);
            } else if (!warnings.isEmpty()) {
                status.setText(warnings.get(0));
                status.setForeground(WARN);
                warnings.forEach(w -> System.out.println("[ASSETS] " + w));
            } else {
                status.setText(Messages.t("assets.status.ok"));
                status.setForeground(OK);
            }
            // Le finestre che mostrano lo stato dei file (p.es. la config) vanno
            // riaperte per rileggerlo: qui basta rinfrescare l'etichetta.
            SwingUtilities.invokeLater(() -> status.getParent().revalidate());
        }
    }

    // =========================================================================

    private static String stateText(AssetPackage pkg) {
        return pkg.installed()
                ? Messages.t("assets.state.installed")
                : Messages.t("assets.state.missing");
    }

    private static JLabel small(String text, Color color) {
        JLabel l = new JLabel(text);
        l.setForeground(color);
        l.setFont(l.getFont().deriveFont(Font.PLAIN, 11f));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    /** Etichetta multiriga: l'HTML è il modo più semplice di mandare a capo in Swing. */
    private static JLabel wrapped(String text, Color color, int widthPx) {
        JLabel l = new JLabel("<html><body style='width:" + widthPx + "px'>"
                + escape(text) + "</body></html>");
        l.setForeground(color);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String mib(long bytes) {
        return (bytes >= 1024 * 1024)
                ? String.format("%.1f MB", bytes / (1024.0 * 1024.0))
                : String.format("%d KB", Math.max(1, bytes / 1024));
    }
}
