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
import io.github.zolfrik.amija.net.mister.GroovyModeline;
import io.github.zolfrik.amija.net.mister.MisterControl;
import io.github.zolfrik.amija.net.mister.MisterDiscovery;
import io.github.zolfrik.amija.net.mister.MisterSettings;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JOptionPane;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.SpinnerNumberModel;
import javax.swing.Timer;
import javax.swing.event.ChangeListener;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * The "MiSTer (Groovy)" section of the F12 popup ({@code docs/groovy-mister-plan.md} Phase 7).
 *
 * <p>Like remote play, the output lives outside the popup: the button starts and stops it at
 * once and the status line, refreshed by a timer, tells how it goes. The settings of a running
 * output are fixed, except the position of the picture, which moves live so it can be centred
 * while looking at the tube.
 */
final class MisterSection {

    /** How often the status line realigns with reality. */
    private static final int REFRESH_MS = 500;

    private MisterSection() {
    }

    /** What the section hands to the dialog. */
    record Built(JComponent panel, Runnable applyToConfig, Runnable dispose) {
    }

    static Built build(AmigaConfig config, MisterControl control,
                       Function<String, JComponent> section,
                       RemotePlaySection.Widgets widgets) {
        JComponent panel = section.apply(Messages.t("section.mister"));

        // Expert mode: off, only what a user needs; on, also how the picture and the sound
        // travel.
        JCheckBox expertCheck = widgets.check(Messages.t("mister.expert.on"),
                "true".equalsIgnoreCase(nullToEmpty(config.misterValue("expert"))));
        widgets.addRow(panel, 0, "label.mister.expert", expertCheck, null);

        // The address field and its Search button share the field column, so together they
        // are as wide as the fields of the other rows.
        JTextField hostField = widgets.text(nullToEmpty(config.misterValue("host")));
        hostField.setColumns(15);
        JButton searchButton = widgets.button(Messages.t("button.mister.search"));
        JPanel hostPanel = new JPanel(new BorderLayout(6, 0));
        hostPanel.setOpaque(false);
        hostPanel.add(hostField, BorderLayout.CENTER);
        hostPanel.add(searchButton, BorderLayout.EAST);
        widgets.addRow(panel, 2, "label.mister.host", hostPanel, null);
        widgets.tip("label.mister.host", hostField);

        JComboBox<Choice> colorCombo = widgets.combo(Choice.COLORS);
        colorCombo.setSelectedItem(Choice.find(Choice.COLORS, config.misterValue("color")));
        JLabel colorLabel = widgets.addRow(panel, 3, "label.mister.color", colorCombo, null);

        JComboBox<Choice> compressionCombo = widgets.combo(Choice.COMPRESSIONS);
        compressionCombo.setSelectedItem(Choice.find(Choice.COMPRESSIONS,
                config.misterValue("compression")));
        JLabel compressionLabel = widgets.addRow(panel, 4, "label.mister.compression",
                compressionCombo, null);

        JCheckBox audioCheck = widgets.check(Messages.t("label.mister.audio.on"),
                !"false".equalsIgnoreCase(nullToEmpty(config.misterValue("audio"))));
        JSpinner leadSpinner = spinner(intValue(config, "audiolead",
                MisterSettings.AUDIO_LEAD_DEFAULT_MS), MisterSettings.AUDIO_LEAD_MIN_MS,
                MisterSettings.AUDIO_LEAD_MAX_MS);
        JPanel leadPanel = wrap(leadSpinner);
        JLabel audioLabel = widgets.addRow(panel, 5, "label.mister.audio", audioCheck,
                leadPanel);

        JSpinner hShift = spinner(intValue(config, "hshift", 0),
                -GroovyModeline.Geometry.H_SHIFT_MAX, GroovyModeline.Geometry.H_SHIFT_MAX);
        JSpinner vShift = spinner(intValue(config, "vshift", 0),
                -GroovyModeline.Geometry.V_SHIFT_MAX, GroovyModeline.Geometry.V_SHIFT_MAX);
        JLabel hShiftName = new JLabel(Messages.t("mister.position.hshift"));
        JLabel vShiftName = new JLabel(Messages.t("mister.position.vshift"));
        JPanel positionPanel = wrap(hShiftName, hShift, vShiftName, vShift);
        JLabel positionLabel = widgets.addRow(panel, 6, "label.mister.position",
                positionPanel, null);

        JComboBox<Choice> inputsCombo = widgets.combo(Choice.INPUTS);
        inputsCombo.setSelectedItem(Choice.find(Choice.INPUTS, config.misterValue("inputs")));
        widgets.addRow(panel, 7, "label.mister.inputs", inputsCombo, null);

        JTextField statusField = widgets.readOnlyText("");
        widgets.addRow(panel, 8, "label.mister.status", statusField, null);

        // Start/Stop comes last, below everything it acts on.
        JButton startButton = widgets.button(Messages.t("button.mister.start"));
        widgets.addRow(panel, 9, "label.mister.output", startButton, null);

        List<JComponent> expertRows = List.of(colorLabel, colorCombo, compressionLabel,
                compressionCombo, audioLabel, audioCheck, leadPanel, positionLabel,
                positionPanel);

        // addRow puts the tooltip only on the label and the field of a row.
        widgets.tip("button.mister.search", searchButton);
        widgets.tip("label.mister.audio.on", audioCheck);
        widgets.tip("label.mister.audiolead", leadSpinner);
        widgets.tip("label.mister.hshift", hShiftName, hShift);
        widgets.tip("label.mister.vshift", vShiftName, vShift);

        // A search diverts the answers of a running session: none while the output runs.
        List<JComponent> fixedWhileRunning = List.of(hostField, searchButton, colorCombo,
                compressionCombo, audioCheck, inputsCombo);

        Runnable applyToConfig = () -> {
            config.setMisterValue("expert", Boolean.toString(expertCheck.isSelected()));
            config.setMisterValue("host", hostField.getText());
            config.setMisterValue("color", ((Choice) colorCombo.getSelectedItem()).value());
            config.setMisterValue("compression",
                    ((Choice) compressionCombo.getSelectedItem()).value());
            config.setMisterValue("audio", Boolean.toString(audioCheck.isSelected()));
            config.setMisterValue("audiolead", leadSpinner.getValue().toString());
            config.setMisterValue("inputs", ((Choice) inputsCombo.getSelectedItem()).value());
            config.setMisterValue("hshift", hShift.getValue().toString());
            config.setMisterValue("vshift", vShift.getValue().toString());
        };

        Runnable sync = () -> {
            boolean running = control.isMisterRunning();
            startButton.setText(Messages.t(running
                    ? "button.mister.stop" : "button.mister.start"));
            for (JComponent c : fixedWhileRunning) {
                c.setEnabled(!running);
            }
            leadSpinner.setEnabled(!running && audioCheck.isSelected());
            widgets.setRowVisible(expertCheck.isSelected(), expertRows);
            statusField.setText(control.misterStatus());
        };

        // The picture moves while the user watches the tube: each step reaches the running
        // output at once, and the configuration with it.
        ChangeListener geometry = e -> {
            GroovyModeline.Geometry g = new GroovyModeline.Geometry((Integer) hShift.getValue(),
                    (Integer) vShift.getValue());
            config.setMisterValue("hshift", Integer.toString(g.hShift()));
            config.setMisterValue("vshift", Integer.toString(g.vShift()));
            control.setMisterGeometry(g);
        };
        hShift.addChangeListener(geometry);
        vShift.addChangeListener(geometry);

        audioCheck.addActionListener(e -> sync.run());
        expertCheck.addActionListener(e -> sync.run());

        startButton.addActionListener(e -> {
            if (control.isMisterRunning()) {
                control.stopMister();
            } else {
                // The popup's values go into the configuration first: that is where
                // startMister() reads them from.
                applyToConfig.run();
                String error = control.startMister();
                if (error != null) {
                    widgets.error(error);
                }
            }
            sync.run();
        });

        searchButton.addActionListener(e -> {
            searchButton.setEnabled(false);
            new SwingWorker<List<MisterDiscovery.Found>, Void>() {
                @Override
                protected List<MisterDiscovery.Found> doInBackground() throws Exception {
                    return control.searchMisters();
                }

                @Override
                protected void done() {
                    sync.run();
                    List<MisterDiscovery.Found> found;
                    try {
                        found = get();
                    } catch (Exception ex) {
                        found = List.of();
                    }
                    String chosen = choose(panel, found);
                    if (chosen != null) {
                        hostField.setText(chosen);
                    }
                }
            }.execute();
        });

        Timer timer = new Timer(REFRESH_MS, e -> sync.run());
        timer.start();
        sync.run();
        return new Built(panel, applyToConfig, timer::stop);
    }

    private static int intValue(AmigaConfig config, String key, int dflt) {
        try {
            String v = config.misterValue(key);
            return v == null ? dflt : Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static JSpinner spinner(int value, int min, int max) {
        JSpinner s = new JSpinner(new SpinnerNumberModel(
                Math.max(min, Math.min(max, value)), min, max, 1));
        ((JSpinner.DefaultEditor) s.getEditor()).getTextField().setColumns(3);
        return s;
    }

    private static JPanel wrap(JComponent... components) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        p.setOpaque(false);
        for (JComponent c : components) {
            p.add(c);
        }
        return p;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** A drop-down entry: the value written in the configuration and its label key. */
    record Choice(String value, String labelKey) {

        static final Choice[] COLORS = {
            new Choice("auto", "mister.color.auto"),
            new Choice("565", "mister.color.565"),
            new Choice("888", "mister.color.888"),
        };

        static final Choice[] COMPRESSIONS = {
            new Choice("lz4delta", "mister.compression.lz4delta"),
            new Choice("lz4", "mister.compression.lz4"),
            new Choice("raw", "mister.compression.raw"),
        };

        static final Choice[] INPUTS = {
            new Choice("pc", "mister.inputs.pc"),
            new Choice("mister", "mister.inputs.mister"),
        };

        /** The entry with {@code value}, or the first (the default) when there is none. */
        static Choice find(Choice[] choices, String value) {
            String v = value == null ? "" : value.toLowerCase(Locale.ROOT);
            for (Choice c : choices) {
                if (c.value().equals(v)) {
                    return c;
                }
            }
            return choices[0];
        }

        @Override
        public String toString() {
            return Messages.t(labelKey);
        }
    }

    /** Asks which MiSTer to use; {@code null} when none answered or the user cancelled. */
    private static String choose(JComponent parent, List<MisterDiscovery.Found> found) {
        String title = Messages.t("msg.mister.search.title");
        if (found.isEmpty()) {
            JOptionPane.showMessageDialog(parent, Messages.t("msg.mister.search.none"), title,
                    JOptionPane.INFORMATION_MESSAGE);
            return null;
        }
        if (found.size() == 1) {
            return found.get(0).mister().getHostAddress();
        }
        String[] items = new String[found.size()];
        for (int i = 0; i < items.length; i++) {
            MisterDiscovery.Found f = found.get(i);
            items[i] = Messages.t(f.linkLocal() ? "msg.mister.search.direct"
                    : "msg.mister.search.item", f.mister().getHostAddress(),
                    f.local().getHostAddress());
        }
        Object pick = JOptionPane.showInputDialog(parent, Messages.t("msg.mister.search.choose"),
                title, JOptionPane.QUESTION_MESSAGE, null, items, items[0]);
        for (int i = 0; i < items.length; i++) {
            if (items[i].equals(pick)) {
                return found.get(i).mister().getHostAddress();
            }
        }
        return null;
    }
}
