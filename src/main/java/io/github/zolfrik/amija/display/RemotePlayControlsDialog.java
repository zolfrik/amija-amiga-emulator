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

import io.github.zolfrik.amija.i18n.Messages;
import io.github.zolfrik.amija.input.JoystickAction;
import io.github.zolfrik.amija.input.JoystickKeyMap;
import io.github.zolfrik.amija.input.PortDevice;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
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

/**
 * The popup shown to each player when a remote play session starts: which game port is
 * theirs, what is plugged into it, the keys of a keyboard joystick, and who types on the
 * Amiga keyboard. Shown to the host when hosting starts and to the guest when the
 * connection is established; a spectator drives nothing and gets none.
 */
final class RemotePlayControlsDialog {

    private static final Color BG       = new Color(0x2B, 0x2D, 0x31);
    private static final Color PANEL_BG = new Color(0x33, 0x36, 0x3B);
    private static final Color FG       = new Color(0xE6, 0xE6, 0xE8);
    private static final Color SUBFG    = new Color(0xA8, 0xAC, 0xB3);
    private static final Color ACCENT   = new Color(0x4D, 0x9D, 0xE0);
    private static final Color WARN     = new Color(0xE0, 0xA0, 0x40);
    private static final Color BORDER   = new Color(0x45, 0x49, 0x50);

    /** Text width of the explanatory lines, in unscaled pixels. */
    private static final int TEXT_WIDTH = 420;

    private RemotePlayControlsDialog() { }

    /** Who this machine is in the session. */
    enum Role { HOST, GUEST }

    /**
     * What the popup describes.
     *
     * @param role           host or guest
     * @param myPort         the game port this player drives (0 or 1)
     * @param device         what is plugged into it on this computer
     * @param keys           the keys of that port, used when {@code device} is the keyboard joystick
     * @param cd32Pad        whether that port holds a CD32 pad (its extra buttons are listed)
     * @param keyboardShared whether the guest types on the Amiga keyboard too
     */
    record Info(Role role, int myPort, PortDevice device, JoystickKeyMap keys,
                boolean cd32Pad, boolean keyboardShared) {
    }

    /** Shows the (modal) popup and returns when it is closed. */
    static void show(Window parent, Info info) {
        JDialog dialog = new JDialog(parent, Messages.t("remoteplay.controls.title"),
                Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JPanel body = new JPanel();
        body.setBackground(BG);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(pad(14, 18, 10, 18));

        JLabel title = new JLabel(Messages.t("remoteplay.controls.header"));
        title.setForeground(FG);
        title.setFont(derive(title.getFont(), Font.BOLD, 15f));
        body.add(left(title));
        body.add(Box.createVerticalStrut(px(10)));
        body.add(left(portCard(info)));
        body.add(Box.createVerticalStrut(px(10)));
        body.add(left(text(keyboardNote(info), FG)));
        body.add(Box.createVerticalStrut(px(10)));
        body.add(left(text(Messages.t("remoteplay.controls.change"), ACCENT)));

        JButton ok = new JButton(Messages.t("button.ok"));
        ok.setFocusPainted(false);
        ok.setBackground(ACCENT);
        ok.setForeground(Color.BLACK);
        ok.setFont(ok.getFont().deriveFont(Font.BOLD));
        ok.addActionListener(e -> dialog.dispose());
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(PANEL_BG);
        bar.setBorder(pad(8, 16, 8, 16));
        JPanel right = new JPanel();
        right.setOpaque(false);
        right.add(ok);
        bar.add(right, BorderLayout.EAST);

        dialog.getContentPane().setBackground(BG);
        dialog.getContentPane().setLayout(new BorderLayout());
        dialog.getContentPane().add(body, BorderLayout.CENTER);
        dialog.getContentPane().add(bar, BorderLayout.SOUTH);
        dialog.getRootPane().setDefaultButton(ok);
        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.pack();
        dialog.setResizable(false);
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }

    /**
     * This player's port: its name, what is plugged into it, and below that the keys of a
     * keyboard joystick or one line of guidance.
     */
    private static JPanel portCard(Info info) {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(PANEL_BG);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ACCENT, px(2)), pad(8, 12, 10, 12)));

        JLabel port = new JLabel(Messages.t("remoteplay.controls.port", info.myPort()));
        port.setForeground(ACCENT);
        port.setFont(derive(port.getFont(), Font.BOLD, 13f));
        JLabel device = new JLabel(deviceName(info.device(), info.cd32Pad()));
        device.setForeground(info.device() == PortDevice.NONE ? WARN : FG);
        device.setFont(derive(device.getFont(), Font.BOLD, 13f));
        card.add(left(port));
        card.add(Box.createVerticalStrut(px(2)));
        card.add(left(device));

        JComponent detail = switch (info.device()) {
            case MOUSE -> null;
            case JOYSTICK_REAL -> text(Messages.t("remoteplay.controls.real"), SUBFG);
            case NONE -> text(Messages.t("remoteplay.controls.none"), WARN);
            case JOYSTICK_KEYBOARD -> keyTable(info);
        };
        if (detail != null) {
            card.add(Box.createVerticalStrut(px(8)));
            card.add(left(detail));
        }
        return card;
    }

    /** The keys of a keyboard joystick, one action per row. */
    private static JPanel keyTable(Info info) {
        JPanel table = new JPanel(new GridBagLayout());
        table.setOpaque(false);
        int row = 0;
        for (JoystickAction action : JoystickAction.values()) {
            if (action.isCd32() && !info.cd32Pad()) continue;
            JLabel name = new JLabel(Messages.t(action.messageKey()));
            name.setForeground(SUBFG);
            JLabel key = new JLabel(info.keys() == null ? ""
                    : JoystickKeysDialog.displayName(info.keys().get(action)));
            key.setForeground(FG);
            key.setFont(key.getFont().deriveFont(Font.BOLD));
            GridBagConstraints gc = new GridBagConstraints();
            gc.gridy = row++;
            gc.anchor = GridBagConstraints.WEST;
            gc.insets = new Insets(px(1), 0, px(1), px(18));
            gc.gridx = 0;
            table.add(name, gc);
            gc.gridx = 1;
            gc.insets = new Insets(px(1), 0, px(1), 0);
            gc.weightx = 1;
            table.add(key, gc);
        }
        return table;
    }

    private static String keyboardNote(Info info) {
        return Messages.t("remoteplay.controls.kb."
                + (info.role() == Role.HOST ? "host." : "guest.")
                + (info.keyboardShared() ? "shared" : "private"));
    }

    private static String deviceName(PortDevice device, boolean cd32Pad) {
        if (cd32Pad && device == PortDevice.JOYSTICK_REAL) {
            return Messages.t("port.device.joypadReal");
        }
        if (cd32Pad && device == PortDevice.JOYSTICK_KEYBOARD) {
            return Messages.t("port.device.joypadKeyboard");
        }
        return Messages.t(device.messageKey());
    }

    /** A wrapped line of text. */
    private static JLabel text(String s, Color color) {
        JLabel label = new JLabel("<html><body style='width: " + px(TEXT_WIDTH) + "px'>"
                + s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                + "</body></html>");
        label.setForeground(color);
        return label;
    }

    private static <T extends JComponent> T left(T c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        return c;
    }
}
