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
 * Configuration popup of the keyboard joystick keys of a game port.
 *
 * <p>One row per function (Up / Down / Right / Left / Fire): the label and a button showing
 * the key currently assigned. Pressing the button puts the dialog into <b>capture</b>: the
 * first key pressed becomes the new assignment, ESC cancels (and so cannot be assigned to
 * any function).
 *
 * <p>A key cannot be used by two functions, neither of the same joystick nor of the other
 * port's: then the user gets a message saying to which function (and of which port) the
 * key is already assigned, and the choice is refused.
 *
 * <p>The changes are applied to the caller's map when the popup closes; the "Restore
 * defaults" button brings the port back to the emulator's default keys.
 */
public final class JoystickKeysDialog {

    private static final Color BG       = new Color(0x2B, 0x2D, 0x31);
    private static final Color PANEL_BG = new Color(0x33, 0x36, 0x3B);
    private static final Color FG       = new Color(0xE6, 0xE6, 0xE8);
    private static final Color SUBFG    = new Color(0xA8, 0xAC, 0xB3);
    private static final Color ACCENT   = new Color(0x4D, 0x9D, 0xE0);
    private static final Color BORDER   = new Color(0x45, 0x49, 0x50);

    // A method, not a constant: the size depends on the interface scale, which must be read
    // when the component is built, not when the class loads.
    private static Dimension keyButtonSize() {
        return dim(190, 30);
    }

    private JoystickKeysDialog() { }

    /**
     * Shows the (modal) popup for port {@code portIndex} (0 or 1).
     *
     * @param maps working maps of the two ports, indexed by port number: the one of the port
     *             in question is updated at closing, the other is used (read-only) to detect
     *             the keys already taken.
     */
    public static void show(Window parent, int portIndex, JoystickKeyMap[] maps) {
        new Editor(parent, portIndex, maps).open();
    }

    /** State and behaviour of a single opening of the popup. */
    private static final class Editor {

        private final int portIndex;
        private final JoystickKeyMap[] maps;
        /** Working copy: committed to {@code maps[portIndex]} on close. */
        private final JoystickKeyMap working;
        /** Map of the other port, to check the keys already assigned. */
        private final JoystickKeyMap other;

        private final JDialog dialog;
        private final Map<JoystickAction, JButton> keyButtons =
                new EnumMap<>(JoystickAction.class);
        private final JLabel hint = new JLabel();
        private JButton okButton;

        /** Function waiting for a key, or {@code null} if not capturing. */
        private JoystickAction capturing;
        private KeyEventDispatcher captureDispatcher;
        /** Look of the button before the highlight, to restore at the end of the capture. */
        private Color savedBackground;
        private Color savedForeground;
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

            // ESC closes the popup only when no key is being captured: during the capture the
            // event is intercepted by the dispatcher, which uses it to cancel.
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
                    // The focus starts from OK: no key button starts with the focus halo,
                    // which would be confused with the capture highlight.
                    if (okButton != null) okButton.requestFocusInWindow();
                }
            });

            dialog.pack();
            dialog.setResizable(false);
            hint.setText(" "); // reserves the height without showing anything at rest
            dialog.setLocationRelativeTo(parent());
            dialog.setVisible(true); // bloccante (modale)
        }

        private Window parent() {
            return dialog.getOwner();
        }

        // -----------------------------------------------------------------
        // Building the interface
        // -----------------------------------------------------------------

        private JPanel buildHeader() {
            JPanel header = new JPanel();
            header.setOpaque(false);
            header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
            header.setBorder(pad(14, 18, 6, 18));

            JLabel title = new JLabel(Messages.t("joykeys.header", portIndex));
            title.setForeground(FG);
            title.setFont(derive(title.getFont(), Font.BOLD, 15f));
            title.setAlignmentX(Component.LEFT_ALIGNMENT);

            JLabel sub = new JLabel(Messages.t("joykeys.subtitle"));
            sub.setForeground(SUBFG);
            sub.setFont(derive(sub.getFont(), Font.PLAIN, 11f));
            sub.setAlignmentX(Component.LEFT_ALIGNMENT);

            hint.setForeground(ACCENT);
            hint.setFont(derive(hint.getFont(), Font.PLAIN, 11f));
            hint.setAlignmentX(Component.LEFT_ALIGNMENT);
            // At pack time the status line holds the longest capture message: so the popup is
            // born wide enough to hold them all and the layout does not change when the
            // capture starts (it is cleared right after).
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
                    pad(6, 18, 12, 18),
                    BorderFactory.createCompoundBorder(
                            BorderFactory.createLineBorder(BORDER, px(1)),
                            pad(10, 12, 10, 12))));

            int row = 0;
            for (JoystickAction action : JoystickAction.values()) {
                JLabel label = new JLabel(Messages.t(action.messageKey()));
                label.setForeground(FG);

                // The text colour is chosen by the look and feel, because it draws the
                // button's background: with FlatLaf (dark) it is light, with the system look
                // and feel it is dark. Forcing it black would make the key unreadable on the
                // dark theme. During the capture the background is imposed by us, and there an
                // explicit colour is needed: see startCapture.
                JButton keyButton = new JButton();
                keyButton.setFocusPainted(false);
                keyButton.setPreferredSize(keyButtonSize());
                keyButton.setMinimumSize(keyButtonSize());
                keyButton.setToolTipText(Messages.t("joykeys.button.tooltip"));
                keyButton.addActionListener(e -> startCapture(action));
                keyButtons.put(action, keyButton);
                showBinding(action);

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

        // -----------------------------------------------------------------
        // Key capture
        // -----------------------------------------------------------------

        /**
         * Puts the popup in wait for the next key for {@code action}. While the capture is
         * active every keyboard event of the application is intercepted (and consumed), so
         * no key, not even ENTER or SPACE, reaches the dialog's buttons.
         */
        private void startCapture(JoystickAction action) {
            cancelCapture();
            capturing = action;

            // Highlight the waiting button. The coloured border is the only signal every look
            // and feel honours: some draw the buttons' background themselves.
            JButton b = keyButtons.get(action);
            savedBackground = b.getBackground();
            savedForeground = b.getForeground();
            savedBorder     = b.getBorder();
            savedFont       = b.getFont();
            b.setText(Messages.t("joykeys.press"));
            b.setBackground(ACCENT);
            // Light blue background imposed by us: here the text must be dark, whatever look
            // and feel is in use.
            b.setForeground(Color.BLACK);
            b.setBorder(BorderFactory.createLineBorder(ACCENT, px(2)));
            b.setFont(savedFont.deriveFont(Font.BOLD));
            hint.setText(Messages.t("joykeys.capturing", Messages.t(action.messageKey())));

            captureDispatcher = e -> {
                if (e.getID() != KeyEvent.KEY_PRESSED) return true;
                JoystickAction target = capturing;
                int code = e.getKeyCode();
                KeyBinding binding = KeyBinding.fromEvent(e);
                stopCapture();
                if (code != KeyEvent.VK_ESCAPE && code != KeyEvent.VK_UNDEFINED) {
                    // Outside the event dispatch: any conflict message is modal and must not
                    // open inside the dispatch chain.
                    SwingUtilities.invokeLater(() -> assign(target, binding));
                }
                return true;
            };
            KeyboardFocusManager.getCurrentKeyboardFocusManager()
                    .addKeyEventDispatcher(captureDispatcher);
        }

        /** Ends the capture in progress, restoring the button's look. */
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
                    b.setForeground(savedForeground);
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
         * Assigns the key to the function, if free. A key already used by the other port or
         * by another function of the same port is refused with a message saying where it is
         * already in use.
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

        /** Brings the port back to the default keys, if none is taken by the other port. */
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
        // Utilities
        // -----------------------------------------------------------------

        /** Shows on the button the key currently assigned to the function. */
        private void showBinding(JoystickAction action) {
            JButton b = keyButtons.get(action);
            if (b != null) b.setText(displayName(working.get(action)));
        }

        /** Widest capture message among all the functions (to size the popup). */
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
     * Name of the key shown to the user, qualified with its position when the key exists in
     * several copies on the keyboard (e.g. "Right Ctrl", "Left Shift").
     *
     * <p>The base name comes from the message bundle ({@code key.name.<VK NAME>}), so it
     * follows the language chosen for the interface; for keys without a dedicated entry
     * (letters, digits and function keys, whose names need no translation) AWT's is used.
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
