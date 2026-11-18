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

import io.github.zolfrik.amija.i18n.Messages;
import io.github.zolfrik.amija.input.GlfwJoystickService;
import io.github.zolfrik.amija.input.JoypadMap;
import io.github.zolfrik.amija.input.JoystickAction;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.EnumMap;
import java.util.Map;

/**
 * Popup assigning the buttons of a real controller to the buttons of the CD32 pad: click a
 * pad button, then press the controller's button. The directions are not assigned here: they
 * always come from the left stick, the D-pad or the first hat.
 *
 * <p>The capture reads the controller through {@link GlfwJoystickService}, on its own
 * thread: the first controller connected is the one listened to.
 */
public final class JoypadButtonsDialog {

    private static final Color BG       = new Color(0x2B, 0x2D, 0x31);
    private static final Color PANEL_BG = new Color(0x33, 0x36, 0x3B);
    private static final Color FG       = new Color(0xE6, 0xE6, 0xE8);
    private static final Color SUBFG    = new Color(0xA8, 0xAC, 0xB3);
    private static final Color ACCENT   = new Color(0x4D, 0x9D, 0xE0);
    private static final Color BORDER   = new Color(0x45, 0x49, 0x50);

    private JoypadButtonsDialog() { }

    /**
     * Shows the (modal) popup. {@code map} holds the mapping being edited: it is updated when
     * the popup closes with OK, left alone otherwise.
     */
    public static void show(Window parent, JoypadMap[] map) {
        new Editor(parent, map).open();
    }

    /** The name of a pad button, as the CD32 pad calls it. */
    static String buttonName(JoystickAction button) {
        return Messages.t("joypad.button." + button.configKey());
    }

    /** A controller's control as the user reads it: "A", "LT", "Button 3"; "-" if none. */
    static String controlName(String token) {
        if (token == null || token.isEmpty()) return Messages.t("joypad.unassigned");
        if (token.startsWith("BUTTON")) {
            return Messages.t("joypad.rawbutton", token.substring("BUTTON".length()));
        }
        return token;
    }

    private static final class Editor {

        private final JoypadMap[] target;
        private final JoypadMap working;
        private final JDialog dialog;
        private final Map<JoystickAction, JButton> buttons = new EnumMap<>(JoystickAction.class);
        private final JLabel hint = new JLabel(" ");
        private final GlfwJoystickService service = GlfwJoystickService.getInstance();
        /** Pad button waiting for a control, or null. */
        private JoystickAction capturing;
        /** Refreshes the "no controller" line while the popup is open. */
        private final Timer presence;

        Editor(Window parent, JoypadMap[] map) {
            this.target = map;
            this.working = map[0].copy();
            this.dialog = new JDialog(parent, Messages.t("joypad.title"),
                    Dialog.ModalityType.APPLICATION_MODAL);
            this.presence = new Timer(500, e -> refreshHint());
        }

        void open() {
            dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
            JPanel content = new JPanel(new BorderLayout());
            content.setBackground(BG);
            content.add(buildHeader(), BorderLayout.NORTH);
            content.add(buildRows(), BorderLayout.CENTER);
            dialog.getContentPane().setBackground(BG);
            dialog.getContentPane().setLayout(new BorderLayout());
            dialog.getContentPane().add(content, BorderLayout.CENTER);
            dialog.getContentPane().add(buildButtonBar(), BorderLayout.SOUTH);

            // ESC cancels a capture in progress, otherwise closes without changes.
            dialog.getRootPane().registerKeyboardAction(e -> {
                if (capturing != null) {
                    stopCapture();
                } else {
                    dialog.dispose();
                }
            }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
            dialog.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosed(WindowEvent e) {
                    presence.stop();
                    service.captureNextControl(null);
                }
            });

            refreshHint();
            presence.start();
            dialog.pack();
            dialog.setResizable(false);
            dialog.setLocationRelativeTo(dialog.getOwner());
            dialog.setVisible(true);
        }

        private JPanel buildHeader() {
            JPanel header = new JPanel();
            header.setOpaque(false);
            header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
            header.setBorder(pad(14, 18, 6, 18));
            JLabel title = new JLabel(Messages.t("joypad.header"));
            title.setForeground(FG);
            title.setFont(derive(title.getFont(), Font.BOLD, 15f));
            title.setAlignmentX(Component.LEFT_ALIGNMENT);
            JLabel sub = new JLabel(Messages.t("joypad.subtitle"));
            sub.setForeground(SUBFG);
            sub.setFont(derive(sub.getFont(), Font.PLAIN, 11f));
            sub.setAlignmentX(Component.LEFT_ALIGNMENT);
            hint.setForeground(ACCENT);
            hint.setFont(derive(hint.getFont(), Font.PLAIN, 11f));
            hint.setAlignmentX(Component.LEFT_ALIGNMENT);
            header.add(title);
            header.add(Box.createVerticalStrut(3));
            header.add(sub);
            header.add(Box.createVerticalStrut(6));
            header.add(hint);
            return header;
        }

        private JPanel buildRows() {
            JPanel panel = new JPanel(new GridBagLayout());
            panel.setBackground(PANEL_BG);
            panel.setBorder(BorderFactory.createCompoundBorder(
                    pad(6, 18, 12, 18),
                    BorderFactory.createCompoundBorder(
                            BorderFactory.createLineBorder(BORDER, px(1)),
                            pad(10, 12, 10, 12))));
            int row = 0;
            for (JoystickAction button : JoypadMap.BUTTONS) {
                JLabel label = new JLabel(buttonName(button));
                label.setForeground(FG);
                JButton control = new JButton(controlName(working.get(button)));
                control.setFocusPainted(false);
                control.setPreferredSize(dim(150, 30));
                control.setToolTipText(Messages.t("joypad.button.tooltip"));
                control.addActionListener(e -> startCapture(button));
                buttons.put(button, control);

                GridBagConstraints gc = new GridBagConstraints();
                gc.gridy = row++;
                gc.insets = new Insets(px(4), px(4), px(4), px(4));
                gc.anchor = GridBagConstraints.WEST;
                gc.gridx = 0;
                gc.weightx = 1;
                gc.fill = GridBagConstraints.HORIZONTAL;
                panel.add(label, gc);
                gc.gridx = 1;
                gc.weightx = 0;
                gc.fill = GridBagConstraints.NONE;
                panel.add(control, gc);
            }
            return panel;
        }

        private JPanel buildButtonBar() {
            JButton reset = new JButton(Messages.t("button.resetDefaults"));
            JButton ok = new JButton(Messages.t("button.ok"));
            reset.setFocusPainted(false);
            ok.setFocusPainted(false);
            ok.setBackground(ACCENT);
            ok.setForeground(Color.BLACK);
            ok.setFont(ok.getFont().deriveFont(Font.BOLD));
            reset.addActionListener(e -> {
                stopCapture();
                JoypadMap d = JoypadMap.defaults();
                for (JoystickAction b : JoypadMap.BUTTONS) working.set(b, d.get(b));
                refreshButtons();
            });
            ok.addActionListener(e -> {
                stopCapture();
                target[0] = working.copy();
                dialog.dispose();
            });
            JPanel bar = new JPanel(new BorderLayout());
            bar.setBackground(PANEL_BG);
            bar.setBorder(pad(10, 16, 10, 16));
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

        /** Waits for the next control pressed on the controller and assigns it to {@code button}. */
        private void startCapture(JoystickAction button) {
            stopCapture();
            capturing = button;
            buttons.get(button).setText(Messages.t("joypad.press"));
            refreshHint();
            service.captureNextControl(token -> {
                if (capturing != button) return;
                // A control drives one pad button: taken from the one that had it.
                for (JoystickAction other : JoypadMap.BUTTONS) {
                    if (other != button && token.equals(working.get(other))) working.set(other, "");
                }
                working.set(button, token);
                capturing = null;
                refreshButtons();
                refreshHint();
            });
        }

        private void stopCapture() {
            if (capturing == null) return;
            capturing = null;
            service.captureNextControl(null);
            refreshButtons();
            refreshHint();
        }

        private void refreshButtons() {
            for (JoystickAction b : JoypadMap.BUTTONS) {
                if (b != capturing) buttons.get(b).setText(controlName(working.get(b)));
            }
        }

        private void refreshHint() {
            String text;
            if (!service.isControllerPresent()) {
                text = Messages.t("joypad.nocontroller");
            } else if (capturing != null) {
                text = Messages.t("joypad.capturing", buttonName(capturing));
            } else {
                text = " ";
            }
            if (!text.equals(hint.getText())) hint.setText(text);
        }
    }
}
