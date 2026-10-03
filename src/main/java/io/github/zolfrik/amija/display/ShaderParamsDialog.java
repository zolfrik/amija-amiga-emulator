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

import io.github.zolfrik.amija.display.render.gl.ShaderParams;
import io.github.zolfrik.amija.i18n.Messages;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Popup editing the parameters ({@code #pragma parameter}) of a RetroArch shader. One slider
 * per parameter; the changes apply <b>live</b> (through {@code liveUpdate}) and are saved next
 * to the preset ({@code <preset>.params}) when it closes, so they survive restarts.
 */
public final class ShaderParamsDialog {

    private static final Color BG       = new Color(0x2B, 0x2D, 0x31);
    private static final Color PANEL_BG = new Color(0x33, 0x36, 0x3B);
    private static final Color FG       = new Color(0xE6, 0xE6, 0xE8);
    private static final Color SUBFG    = new Color(0xA8, 0xAC, 0xB3);
    private static final Color ACCENT   = new Color(0x4D, 0x9D, 0xE0);

    private ShaderParamsDialog() { }

    /**
     * Shows the (modal) dialog for the preset {@code glslp}. {@code liveUpdate} receives
     * {@code (name, value)} at every change, to apply it live to the shader.
     */
    public static void show(Window parent, Path glslp, BiConsumer<String, Float> liveUpdate) {
        List<ShaderParams.Param> params;
        try {
            params = ShaderParams.parse(glslp);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(parent, e.getMessage(),
                    Messages.t("shaderParams.title"), JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (params.isEmpty()) {
            JOptionPane.showMessageDialog(parent, Messages.t("shaderParams.none"),
                    Messages.t("shaderParams.title"), JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        // Current values: saved overrides, otherwise the parameter's default.
        Map<String, Float> saved = ShaderParams.loadOverrides(glslp);
        Map<String, Float> values = new LinkedHashMap<>();
        for (ShaderParams.Param p : params) {
            values.put(p.name(), saved.getOrDefault(p.name(), p.def()));
        }

        JDialog dialog = new JDialog(parent, Messages.t("shaderParams.title"),
                java.awt.Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JPanel content = new JPanel(new GridBagLayout());
        content.setBackground(BG);
        content.setBorder(pad(12, 16, 12, 16));

        // One control (slider + value) per parameter; the label update is registered for
        // "Reset defaults".
        Map<String, Runnable> resetters = new LinkedHashMap<>();
        int row = 0;
        for (ShaderParams.Param p : params) {
            addParamRow(content, row++, p, values, liveUpdate, resetters);
        }

        JScrollPane scroll = new JScrollPane(content,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getViewport().setBackground(BG);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setPreferredSize(dim(600, 520));

        // Intestazione.
        JPanel headerAndBody = new JPanel(new BorderLayout());
        headerAndBody.setBackground(BG);
        JPanel header = new JPanel();
        header.setOpaque(false);
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setBorder(pad(12, 16, 4, 16));
        JLabel title = new JLabel(glslp.getFileName().toString());
        title.setForeground(FG);
        title.setFont(derive(title.getFont(), Font.BOLD, 16f));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel sub = new JLabel(Messages.t("shaderParams.subtitle"));
        sub.setForeground(SUBFG);
        sub.setFont(derive(sub.getFont(), Font.PLAIN, 11f));
        sub.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.add(title);
        header.add(Box.createVerticalStrut(2));
        header.add(sub);
        headerAndBody.add(header, BorderLayout.NORTH);
        headerAndBody.add(scroll, BorderLayout.CENTER);

        // Buttons.
        JButton reset = button(Messages.t("button.resetDefaults"), false);
        JButton close = button(Messages.t("button.close"), true);
        reset.addActionListener(e -> {
            for (ShaderParams.Param p : params) {
                values.put(p.name(), p.def());
                if (liveUpdate != null) liveUpdate.accept(p.name(), p.def());
            }
            resetters.values().forEach(Runnable::run);
        });
        close.addActionListener(e -> {
            saveOverrides(dialog, glslp, values);
            dialog.dispose();
        });

        JPanel buttons = new JPanel(new BorderLayout());
        buttons.setBackground(PANEL_BG);
        buttons.setBorder(pad(10, 16, 10, 16));
        JPanel left = new JPanel();
        left.setOpaque(false);
        left.add(reset);
        JPanel right = new JPanel();
        right.setOpaque(false);
        right.add(close);
        buttons.add(left, BorderLayout.WEST);
        buttons.add(right, BorderLayout.EAST);

        dialog.getContentPane().setBackground(BG);
        dialog.getContentPane().setLayout(new BorderLayout());
        dialog.getContentPane().add(headerAndBody, BorderLayout.CENTER);
        dialog.getContentPane().add(buttons, BorderLayout.SOUTH);

        dialog.getRootPane().setDefaultButton(close);
        dialog.getRootPane().registerKeyboardAction(
                e -> { saveOverrides(dialog, glslp, values); dialog.dispose(); },
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        dialog.pack();
        dialog.setMinimumSize(dim(560, 360));
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }

    private static void addParamRow(JPanel panel, int row, ShaderParams.Param p,
            Map<String, Float> values, BiConsumer<String, Float> liveUpdate,
            Map<String, Runnable> resetters) {
        int steps = (p.step() > 0f) ? Math.max(1, Math.round((p.max() - p.min()) / p.step())) : 100;
        steps = Math.min(steps, 100000);

        JLabel desc = new JLabel(p.desc().isEmpty() ? p.name() : p.desc());
        desc.setForeground(FG);
        desc.setToolTipText(p.name() + "  [" + fmt(p.min()) + " … " + fmt(p.max()) + "]");

        JLabel valueLabel = new JLabel();
        valueLabel.setForeground(ACCENT);
        valueLabel.setPreferredSize(dim(58, 20));
        valueLabel.setHorizontalAlignment(JLabel.RIGHT);

        JSlider slider = new JSlider(0, steps, sliderPos(p, values.get(p.name()), steps));
        slider.setOpaque(false);
        slider.setPreferredSize(dim(280, 22));

        final int fSteps = steps;
        slider.addChangeListener(e -> {
            float v = valueAt(p, slider.getValue(), fSteps);
            values.put(p.name(), v);
            valueLabel.setText(fmt(v));
            if (liveUpdate != null) liveUpdate.accept(p.name(), v);
        });
        valueLabel.setText(fmt(values.get(p.name())));

        // Recorder for the reset: repositions the slider (which fires the changeListener again).
        resetters.put(p.name(), () -> slider.setValue(sliderPos(p, values.get(p.name()), fSteps)));

        GridBagConstraints gc = new GridBagConstraints();
        gc.gridy = row;
        gc.insets = new Insets(px(3), px(4), px(3), px(8));
        gc.anchor = GridBagConstraints.WEST;
        gc.gridx = 0;
        gc.weightx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(desc, gc);
        gc.gridx = 1;
        gc.weightx = 0;
        gc.fill = GridBagConstraints.NONE;
        panel.add(slider, gc);
        gc.gridx = 2;
        panel.add(valueLabel, gc);
    }

    private static int sliderPos(ShaderParams.Param p, float value, int steps) {
        float t = (p.max() == p.min()) ? 0f : (value - p.min()) / (p.max() - p.min());
        int pos = Math.round(t * steps);
        return Math.max(0, Math.min(steps, pos));
    }

    private static float valueAt(ShaderParams.Param p, int pos, int steps) {
        float v = (p.step() > 0f) ? p.min() + pos * p.step()
                                  : p.min() + (pos / (float) steps) * (p.max() - p.min());
        return Math.max(p.min(), Math.min(p.max(), v));
    }

    private static void saveOverrides(Window parent, Path glslp, Map<String, Float> values) {
        try {
            ShaderParams.saveOverrides(glslp, values);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(parent, e.getMessage(),
                    Messages.t("shaderParams.title"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private static String fmt(float v) {
        if (v == Math.rint(v) && !Float.isInfinite(v)) {
            return Integer.toString((int) v);
        }
        String s = String.format(Locale.US, "%.3f", v);
        // toglie zeri finali superflui
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return s;
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
