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

package io.github.zolfrik.amija.custom;

import io.github.zolfrik.amija.input.GamePortControls;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.AWTException;
import java.awt.Cursor;
import java.awt.Point;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;

/**
 * Translates the host mouse's AWT events into Amiga game port commands.
 *
 * <h3>Two operating modes</h3>
 *
 * <p><b>Free</b> (before capture): the host cursor is visible and moves freely. The Amiga
 * mouse receives movement and clicks only while the host pointer is inside the JFrame. The
 * movement is the delta between consecutive events.
 *
 * <p><b>Captured</b> (after the first left click): the host cursor is made invisible and
 * {@link Robot} brings it back to the centre of the window at every event. The Amiga
 * movement is the delta from the centre: the classic "mouse look". The pointer never
 * reaches the screen edges, so no movement is lost when the user moves the physical mouse a
 * lot (the typical cause of the cursor "stalling" at the edges of the monitor). The delta
 * stays right even under heavy load: AWT coalesces movement events, but the delta is always
 * (current position - centre), so it captures the net movement however many intermediate
 * events are lost.
 *
 * <p>The capture is released with the <b>middle button</b> (or when the window loses focus,
 * so the pointer is not trapped during an alt-tab).
 *
 * <h3>Integer scaling: host deltas are divided</h3>
 *
 * <p>AWT events arrive in screen pixels, and with integer scaling the emulated window is
 * enlarged xN: one Amiga pixel takes N. Delivering the host delta as it is would make the
 * emulated pointer N times faster than the physical mouse, and the sensitivity would change
 * with the zoom, which is not what the user asks for by choosing a zoom. So deltas are
 * divided by {@code videoScale}.
 *
 * <p>The division <b>keeps the remainder</b> ({@link #moveScaled}). Truncating, at N=3 a slow
 * movement of 1-2 pixels per event would always give 0 and the emulated pointer would not
 * move at all: the mouse would be broken exactly in precision movements. Accumulating, N
 * one-pixel events produce the Amiga pixel they are worth.
 *
 * <h3>Button mapping (in captured mode)</h3>
 * <pre>
 *   Left   -> Amiga left button  (CIA-A bit 6, active LOW)
 *   Right  -> Amiga right button (POTINP bit 10/14)
 *   Middle -> RELEASES the capture (not forwarded to the Amiga)
 * </pre>
 */
public class AmigaMouseAdapter extends MouseAdapter {

    private volatile GamePortControls port;
    private volatile boolean enabled = true;
    private final JFrame frame;

    /** Robot for recentring the pointer; null if not available (headless). */
    private final Robot robot;
    /** Transparent cursor used to hide the pointer while captured. */
    private final Cursor blankCursor;
    /** Default cursor restored on release. */
    private final Cursor defaultCursor;

    /** Focus-loss listener registered on the frame; removed by {@link #dispose()}. */
    private final WindowAdapter focusListener;

    /** True while the host mouse is captured (invisible + recentred). */
    private volatile boolean captured = false;

    // --- Free mode state (delta between events, component coordinates) ---
    private boolean insideFrame = false;
    private int lastX = Integer.MIN_VALUE;
    private int lastY = Integer.MIN_VALUE;

    // --- Captured mode state (delta from the centre, screen coordinates) ---
    private int centerX, centerY;

    /** Integer scaling factor of the emulated window ({@code video.integerScaling}), at least 1. */
    private final int videoScale;
    // Remainder of the division by videoScale, in host pixels not yet delivered to the
    // port. Without it, movements shorter than videoScale would be lost (see the class doc).
    private int remainderX, remainderY;

    // The left click that activates the capture is consumed (not forwarded): this flag
    // discards its release so no spurious release is generated.
    private boolean swallowNextLeftRelease = false;

    /** Adapter without integer scaling (window at native size). */
    public AmigaMouseAdapter(GamePortControls port, JFrame frame) {
        this(port, frame, 1);
    }

    /**
     * @param videoScale enlargement factor of the emulated window; host deltas are divided
     *                   by it before reaching the port
     */
    public AmigaMouseAdapter(GamePortControls port, JFrame frame, int videoScale) {
        this.port = port;
        this.frame = frame;
        this.videoScale = Math.max(1, videoScale);

        Robot r = null;
        try {
            r = new Robot();
            r.setAutoWaitForIdle(false);
        } catch (AWTException | SecurityException | java.awt.HeadlessException ex) {
            // Capture not available (e.g. headless): only the free mode remains.
        }
        this.robot = r;

        // Transparent 1x1 cursor: hides the pointer without changing its position.
        BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        this.blankCursor = Toolkit.getDefaultToolkit()
                .createCustomCursor(img, new Point(0, 0), "blank");
        this.defaultCursor = Cursor.getDefaultCursor();

        // Release the capture if the window loses focus (avoids trapping the pointer during
        // alt-tab or when another app takes the focus).
        this.focusListener = new WindowAdapter() {
            @Override
            public void windowLostFocus(WindowEvent e) {
                if (captured) release();
            }
        };
        if (frame != null) {
            frame.addWindowFocusListener(focusListener);
        }
    }

    public boolean isCaptured() {
        return captured;
    }

    /**
     * Connects the host mouse to another game port (live, from the F12 popup): needed when
     * the "Mouse" device is moved from one port to the other. {@code null} disconnects.
     */
    public void setPort(GamePortControls port) {
        GamePortControls old = this.port;
        if (old != null && old != port) {
            old.pressMouseLeft(false);
            old.pressMouseRight(false);
            old.pressMouseMiddle(false);
        }
        this.port = port;
    }

    /**
     * Enables/disables the host mouse. Disabled, it neither moves nor clicks the port, and
     * releases any pointer capture.
     */
    public void setEnabled(boolean enabled) {
        if (this.enabled == enabled) return;
        this.enabled = enabled;
        if (!enabled) {
            if (captured) release();
            GamePortControls p = port;
            if (p != null) {
                p.pressMouseLeft(false);
                p.pressMouseRight(false);
                p.pressMouseMiddle(false);
            }
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Removes the listeners registered on the frame. To be called when the adapter is
     * dismissed (e.g. cold restart of the machine) so focus listeners do not pile up at
     * every rebuild.
     */
    public void dispose() {
        if (frame != null) {
            frame.removeWindowFocusListener(focusListener);
        }
    }

    // -------------------------------------------------------------------------
    // Cattura / rilascio
    // -------------------------------------------------------------------------

    /**
     * Captures the host mouse (hides the cursor and recentres it). Exposed to restore the
     * capture after the configuration popup (F12) closes, since the pointer is released
     * when the dialog opens.
     */
    public void captureMouse() {
        capture();
    }

    /** Activates the capture: hides the cursor and brings it back to the centre. */
    private void capture() {
        if (!enabled || robot == null || frame == null || captured) return;
        captured = true;
        resetRemainder();
        setCursor(blankCursor);
        recenter();
    }

    /** Deactivates the capture: restores the visible cursor and free movement. */
    public void release() {
        if (!captured) return;
        captured = false;
        setCursor(defaultCursor);
        // Reset the free mode state so the first movement after it does not produce a
        // huge delta.
        lastX = lastY = Integer.MIN_VALUE;
        resetRemainder();
    }

    private void setCursor(Cursor c) {
        // Set on the frame and on the content pane: the pointer is over the content pane
        // (the JLabel), which inherits the cursor only if it has none of its own.
        SwingUtilities.invokeLater(() -> {
            frame.setCursor(c);
            if (frame.getContentPane() != null) {
                frame.getContentPane().setCursor(c);
            }
        });
    }

    /** Computes the centre of the window (screen coordinates) and moves the pointer there. */
    private void recenter() {
        if (robot == null || !frame.isShowing()) return;
        Point loc = frame.getLocationOnScreen();
        centerX = loc.x + frame.getWidth() / 2;
        centerY = loc.y + frame.getHeight() / 2;
        robot.mouseMove(centerX, centerY);
    }

    // -------------------------------------------------------------------------
    // Entering / leaving the window (free mode only)
    // -------------------------------------------------------------------------

    @Override
    public void mouseEntered(MouseEvent e) {
        insideFrame = true;
        lastX = e.getX();
        lastY = e.getY();
        resetRemainder();
    }

    @Override
    public void mouseExited(MouseEvent e) {
        if (captured) {
            // Safety net: with a wide/fast movement the pointer can leave the frame's edges
            // before the per-event recentring catches it. Outside the frame no more movement
            // events arrive, so the capture would be "lost" (cursor visible again). Bring it
            // back to the centre at once.
            recenter();
            return;
        }
        insideFrame = false;
        lastX = Integer.MIN_VALUE;
        lastY = Integer.MIN_VALUE;
        resetRemainder();
    }

    // -------------------------------------------------------------------------
    // Movimento
    // -------------------------------------------------------------------------

    @Override
    public void mouseMoved(MouseEvent e) {
        handleMove(e);
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        handleMove(e);
    }

    private void handleMove(MouseEvent e) {
        if (!enabled || port == null) return;
        if (captured) {
            handleMoveCaptured(e);
        } else {
            handleMoveFree(e);
        }
    }

    /** Captured mode: delta from the centre, then recentre the pointer. */
    private void handleMoveCaptured(MouseEvent e) {
        int dx = e.getXOnScreen() - centerX;
        int dy = e.getYOnScreen() - centerY;
        if (dx == 0 && dy == 0) return;
        moveScaled(dx, dy);
        // Bring the pointer back to the centre: the recentring event falls exactly on the
        // centre (dx=dy=0) and is therefore naturally ignored. It must be done at every HOST
        // movement, even when the division by videoScale has not yet produced an Amiga
        // pixel: otherwise the pointer would drift away from the centre until it left the
        // window, losing the capture.
        recenter();
    }

    /** Free mode: delta between consecutive events, only if inside the frame. */
    private void handleMoveFree(MouseEvent e) {
        if (!insideFrame) return;
        int x = e.getX();
        int y = e.getY();
        if (lastX != Integer.MIN_VALUE) {
            int dx = x - lastX;
            int dy = y - lastY;
            if (dx != 0 || dy != 0) {
                moveScaled(dx, dy);
            }
        }
        lastX = x;
        lastY = y;
    }

    /**
     * Converts a delta in host pixels into a delta in Amiga pixels and delivers it to the
     * port.
     *
     * <p>The division by {@code videoScale} keeps the remainder in {@link #remainderX}/
     * {@link #remainderY}, so no movement is lost: N one-pixel host events are worth one
     * Amiga pixel, instead of being truncated to zero one by one. Java's integer division
     * truncates toward zero, so the remainder's sign follows the movement and no drift
     * accumulates when the direction changes.
     */
    private void moveScaled(int dxHost, int dyHost) {
        if (videoScale == 1) {
            port.moveMouse(dxHost, dyHost);   // historical path, no remainder to keep
            return;
        }
        remainderX += dxHost;
        remainderY += dyHost;
        int dx = remainderX / videoScale;
        int dy = remainderY / videoScale;
        remainderX -= dx * videoScale;
        remainderY -= dy * videoScale;
        if (dx != 0 || dy != 0) {
            port.moveMouse(dx, dy);
        }
    }

    /** Clears the accumulated remainder: a new gesture does not inherit the previous one's. */
    private void resetRemainder() {
        remainderX = 0;
        remainderY = 0;
    }

    // -------------------------------------------------------------------------
    // Buttons
    // -------------------------------------------------------------------------

    @Override
    public void mousePressed(MouseEvent e) {
        if (!enabled || port == null) return;
        // Middle button: release the capture (if active) and do not forward to the Amiga.
        if (e.getButton() == MouseEvent.BUTTON2) {
            if (captured) {
                release();
                return;
            }
            // Capture not active: normal behaviour of the middle button.
            port.pressMouseMiddle(true);
            return;
        }

        // First left click outside the capture: capture the mouse and consume the click.
        if (!captured && e.getButton() == MouseEvent.BUTTON1 && robot != null) {
            capture();
            swallowNextLeftRelease = true;
            return;
        }

        if (!captured && !insideFrame) return;

        switch (e.getButton()) {
            case MouseEvent.BUTTON1 -> port.pressMouseLeft(true);
            case MouseEvent.BUTTON3 -> port.pressMouseRight(true);
        }
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        if (!enabled || port == null) return;
        switch (e.getButton()) {
            case MouseEvent.BUTTON1 -> {
                if (swallowNextLeftRelease) {
                    swallowNextLeftRelease = false; // release of the capture click: discarded
                } else {
                    port.pressMouseLeft(false);
                }
            }
            case MouseEvent.BUTTON3 -> port.pressMouseRight(false);
            case MouseEvent.BUTTON2 -> {
                if (!captured) port.pressMouseMiddle(false);
            }
        }
    }
}
