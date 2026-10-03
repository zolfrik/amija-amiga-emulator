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

import io.github.zolfrik.amija.common.AppHome;
import io.github.zolfrik.amija.custom.FloppyDrive;
import io.github.zolfrik.amija.display.render.RendererType;
import io.github.zolfrik.amija.display.render.gl.GlDisplaySurface;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetDragEvent;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.dnd.DropTargetEvent;
import java.awt.dnd.DropTargetListener;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.ObjIntConsumer;

/**
 * View on the raw raster produced by VirtualCRT.
 *
 * The JFrame has a fixed size (frameWidth x frameHeight). The Amiga's display window (DIW)
 * is always centred in the JFrame. In lores every raw pixel is doubled horizontally. In
 * non-interlaced mode line doubling is always on. The image is never cropped: the window
 * is the natural viewport.
 */
public class AmigaDisplay {

    private static final long DUMP_INTERVAL_MS = 5000;

    // Set to true to draw the white debug rectangle of the visible DIW area.
    private static final boolean DEBUG_VISIBLE_AREA = false;
    private static final int DEBUG_COLOR = 0xFFFFFF;

    // Switch for the status LEDs (power/drive) overlaid in the overscan. When false the
    // feature is entirely off (no drawing, zero cost). Set from amiga.properties
    // (leds.visible) through setStatusLedsEnabled.
    private volatile boolean statusLedsEnabled = true;

    // Optional diagnostic overlays, OFF by default (no drawing nor computation when off):
    //  - fpsOverlayEnabled: real presentation FPS (bottom left), the emulator's actual
    //    speed on the host.
    //  - frameCounterOverlayEnabled: counter of emulated frames (bottom centre),
    //    independent of the emulation speed, a reproducible time reference ("at which
    //    frame does the event happen").
    // Set only from amiga.properties (NOT in the F12 popup).
    private volatile boolean fpsOverlayEnabled = false;
    private volatile boolean frameCounterOverlayEnabled = false;

    // State of the FPS computation (updated only while the FPS overlay is on).
    private int  fpsFrameAccum;
    private long fpsWindowStartNs;
    private volatile int fpsValue;

    private boolean dumpEnabled = false;
    private long lastDumpTime = 0;

    private int width;
    private int height;
    private BufferedImage image;
    private int[] pixels;
    private final Object imageLock = new Object();
    private FrameInfo lastFrameInfo;
    private final JFrame frame;
    private final DisplayPanel label;

    // Rendering backend. In SOFTWARE the display composes through Java2D on the
    // {@code label}. In OPENGL the RAW framebuffer is presented by the GPU through
    // {@code glSurface} (LWJGL), hosted in the same window: {@code label} is null and
    // renderFrame() delegates to the GL mailbox. All the GL code is confined to
    // {@code display.render.gl}; the core knows nothing about it.
    private final RendererType renderer;
    private final GlDisplaySurface glSurface;   // null: software or headless
    // Component the caller attaches the input listeners to: the frame in software, the GL
    // canvas (heavyweight) in OpenGL.
    private final Component inputComponent;
    // Target component of the floppy drag-and-drop: the label in software, the GL canvas in
    // OpenGL (in GL the drag overlay is absent, but the drop still works).
    private final Component dndComponent;

    // Overlay (LEDs/FPS/counter) for the OpenGL backend: composed here with Java2D in an
    // ARGB image and handed to the GL backend, which alpha-blends it over the image. It
    // reuses the SAME drawing as the software path (drawStatusLeds/drawCounters).
    private BufferedImage glOverlayImage;
    private int[] glOverlayPixels;
    private volatile BufferedImage pendingLiveCanvas;
    private final Runnable repaintTask = new Runnable() {
        @Override
        public void run() {
            publishPendingLiveCanvas();
        }
    };

    // Fixed size of the JFrame's content.
    private final int frameWidth;
    private final int frameHeight;

    // First visible raster line (end of VBlank). The image is anchored at the top on this
    // line, with no vertical centring (minimum first line = 26). Same as
    // VirtualCRT.REGRESSION_CROP_Y (declared again here so the display package does not
    // depend on common).
    private static final int DISPLAY_TOP_LINE = 26;
    // COMPOSITION size: the one at which Java2D composes image + LEDs + counters, always
    // the native frame. It never grows with integer scaling: the enlargement is the last
    // blit and nothing else (see videoScale).
    private final int windowWidth;
    private final int windowHeight;
    private final AmigaWindowMode windowMode;
    /** Integer scaling factor of the emulated window ({@code video.integerScaling}), at least 1. */
    private final int videoScale;
    private GraphicsDevice fullScreenDevice;
    /** Monitor scelto in configurazione ({@code video.monitor}); null in headless. */
    private final GraphicsDevice screenDevice;

    // Persistent buffer for interlace: accumulates the two alternating fields.
    private int[] interlaceBuffer;
    private int   interlaceBufferWidth = -1;

    // Double buffering: a persistent back buffer in which the frame is composed, swapped
    // with the front (image/pixels) at the end, instead of a new int[] and BufferedImage at
    // every frame (50-60/s). Reallocated only if the size changes (in practice never, since
    // VirtualCRT uses the constants RAW_WIDTH/RAW_HEIGHT). Safe because buildFrameCanvas()
    // copies the image synchronously onto a separate canvas for the EDT, so the reused back
    // buffer is never read concurrently.
    private BufferedImage backImage;
    private int[]         backPixels;

    // Drives DF0-DF3 (index 0..3) targeted by drag-and-drop. Set through setDrives(). The
    // availability of each is read at run time from FloppyDrive.isConnected().
    private volatile FloppyDrive[] drives = new FloppyDrive[4];

    // State of the drop overlay: active while a file is dragged over the frame.
    private volatile boolean dragOverlayActive = false;
    // Quadrant currently under the cursor (0=NW/DF0, 1=NE/DF1, 2=SW/DF2, 3=SE/DF3), -1 = none.
    private volatile int hoverQuadrant = -1;
    // Greyscale snapshot of the frame, frozen when the drag starts as the overlay background.
    private BufferedImage dragBgSnapshot;
    // Base font of the overlay (Segoe UI on Windows, automatic fallback elsewhere).
    private final Font overlayFont = new Font("Segoe UI", Font.PLAIN, 12);
    private final Font ledLabelFont = overlayFont.deriveFont(Font.BOLD, 11f);

    // Pre-rendered foreground of the overlay (dark veil + grid + 4 quadrants). The drives
    // do not change at run time, so the only variable is the quadrant under hover: one
    // ARGB layer is pre-rendered per state, indexed by (hoverQuadrant+1), so every frame of
    // the drag comes down to two drawImage calls (grey background + foreground).
    private volatile BufferedImage[] overlayFgCache;

    // Sources of the LED states (read once per frame in the compose path, negligible cost).
    // powerLedBright: true = bright power LED (audio filter on). The floppy state is read
    // straight from the FloppyDrives (see setDrives), the hard disks' from their
    // HdControllers (see setHardDisks): no extra polling.
    private volatile BooleanSupplier powerLedBright;

    // Activity sources of the "HD" LEDs, one per unit (in order, left to right: WHDLoad,
    // master, slave). Each supplier is true while that disk is reading/writing. Empty/null =
    // no hard disk: the HD LED and its label are not drawn.
    private volatile BooleanSupplier[] hdLedSources;

    // Remote play light: visible only during a session, lit when the other end is really
    // there. Both null = feature off, and the row is not drawn.
    private volatile BooleanSupplier remotePlayLedVisible;
    private volatile BooleanSupplier remotePlayLedActive;

    // Notification (absolute path, drive index 0..3) when a disk is inserted by
    // drag-and-drop, so the caller can update the configuration (see F12).
    private volatile ObjIntConsumer<String> diskDropListener;

    /** Registers the listener notified when a disk is dropped on a drive. */
    public void setDiskDropListener(ObjIntConsumer<String> listener) {
        this.diskDropListener = listener;
    }

    /** Connects the state source of the power LED (see statusLedsEnabled). */
    public void setStatusLedSource(BooleanSupplier powerLedBright) {
        this.powerLedBright = powerLedBright;
    }

    /**
     * Connects the activity sources used by the "HD" LED, one per hard disk unit (e.g.
     * WHDLoad, master, slave). The order matters: the first is drawn on the left. Each
     * supplier must be {@code true} while that disk is reading or writing. Pass
     * {@code null}/empty if there is no hard disk: then the HD LED and its label are not
     * drawn.
     */
    public void setHdLedSources(BooleanSupplier... sources) {
        this.hdLedSources = sources;
    }

    /**
     * Connects the remote play light (docs/remote-play-plan.md section 9).
     *
     * <p>It answers "am I still connected?" without opening F12, which is the only other way
     * to check and a <b>destructive</b> one: opening the popup pauses the machine, and on a
     * paused host the guest's screen freezes. A light costs one rectangle per frame and
     * removes the problem.
     *
     * @param visible {@code true} while there is a session (hosting or guest): otherwise the
     *                row is not drawn at all
     * @param active  {@code true} when the other end is really there; with {@code false} the
     *                light stays dimly lit, the difference between "listening" and
     *                "connected"
     */
    public void setRemotePlayLedSource(BooleanSupplier visible, BooleanSupplier active) {
        this.remotePlayLedVisible = visible;
        this.remotePlayLedActive = active;
    }

    /** Shows or hides the power/drive LED panel (false: nothing drawn). */
    public void setStatusLedsEnabled(boolean enabled) {
        this.statusLedsEnabled = enabled;
    }

    /** Shows/hides the real FPS overlay at the bottom left (false: zero cost). */
    public void setFpsOverlayEnabled(boolean enabled) {
        this.fpsOverlayEnabled = enabled;
    }

    /** Shows/hides the emulated frame counter overlay at the bottom centre. */
    public void setFrameCounterOverlayEnabled(boolean enabled) {
        this.frameCounterOverlayEnabled = enabled;
    }

    /** Sets the four drives (DF0..DF3) targeted by drag-and-drop. */
    public void setDrives(FloppyDrive df0, FloppyDrive df1, FloppyDrive df2, FloppyDrive df3) {
        this.drives = new FloppyDrive[]{df0, df1, df2, df3};
        prebuildOverlayCacheAsync();
    }

    /** Convenience: sets DF0 only, leaving the other drives unchanged. */
    public void setDf0(FloppyDrive df0) {
        FloppyDrive[] d = this.drives;
        this.drives = new FloppyDrive[]{df0, d[1], d[2], d[3]};
        prebuildOverlayCacheAsync();
    }

    public AmigaDisplay(int frameWidth, int frameHeight) {
        this(frameWidth, frameHeight, AmigaWindowMode.WINDOWED, RendererType.SOFTWARE);
    }

    public AmigaDisplay(int frameWidth, int frameHeight, AmigaWindowMode windowMode) {
        this(frameWidth, frameHeight, windowMode, RendererType.SOFTWARE, 1);
    }

    public AmigaDisplay(int frameWidth, int frameHeight, AmigaWindowMode windowMode,
                        RendererType renderer) {
        this(frameWidth, frameHeight, windowMode, renderer, 1);
    }

    // Integer scaling: done by AmiJa, by an INTEGER factor, the same for both renderers.
    // Java2D scales nothing (Amija switches sun.java2d.uiScale off), so 1 Java pixel = 1
    // physical pixel and the window receives exact pixels.
    //  - SOFTWARE: the window is created at frame x videoScale and DisplayPanel draws the
    //    composed canvas enlarged x videoScale with NEAREST_NEIGHBOR.
    //  - OPENGL: the same for the window, but the GPU magnifies and the shader renders at
    //    that resolution (decisive for high-resolution shaders like crt-royale).
    // In fullscreen/borderless the window is as large as the screen and the enlarged frame
    // is centred in it, with black borders.
    public AmigaDisplay(int frameWidth, int frameHeight, AmigaWindowMode windowMode,
                        RendererType renderer, int videoScale) {
        this(frameWidth, frameHeight, windowMode, renderer, videoScale, 0);
    }

    public AmigaDisplay(int frameWidth, int frameHeight, AmigaWindowMode windowMode,
                        RendererType renderer, int videoScale, int monitorIndex) {
        this.screenDevice = screenDeviceFor(monitorIndex);
        this.frameWidth  = frameWidth;
        this.frameHeight = frameHeight;
        this.windowWidth = frameWidth;
        this.windowHeight = frameHeight;
        this.windowMode = windowMode == null ? AmigaWindowMode.WINDOWED : windowMode;
        this.renderer = renderer == null ? RendererType.SOFTWARE : renderer;
        this.videoScale = Math.max(1, videoScale);
        Dimension contentSize = contentSizeFor(this.windowMode,
                windowWidth * this.videoScale, windowHeight * this.videoScale, screenDevice);
        this.width  = frameWidth;
        this.height = frameHeight;
        this.image  = new BufferedImage(frameWidth, frameHeight, BufferedImage.TYPE_INT_RGB);
        this.pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();

        if (GraphicsEnvironment.isHeadless()) {
            this.frame = null;
            this.label = null;
            this.glSurface = null;
            this.inputComponent = null;
            this.dndComponent = null;
            return;
        }

        JFrame f = new JFrame(io.github.zolfrik.amija.i18n.Messages.t("app.title")
                + " - " + io.github.zolfrik.amija.common.Version.version());
        f.setUndecorated(this.windowMode != AmigaWindowMode.WINDOWED);
        AppIcons.apply(f);
        AppIcons.applyToTaskbar();

        GlDisplaySurface surface = (this.renderer == RendererType.OPENGL)
                ? createGlSurface(frameWidth, frameHeight) : null;
        DisplayPanel lbl = null;
        Component input;
        Component dnd;

        if (surface != null) {
            // OpenGL: the heavyweight GL canvas replaces the Swing panel. Input and
            // drag-and-drop are attached to the canvas (its events do not reach the frame).
            Component canvas = surface.getComponent();
            canvas.setBackground(Color.BLACK);
            JPanel host = new JPanel(new BorderLayout());
            host.setOpaque(true);
            host.setBackground(Color.BLACK);
            host.setPreferredSize(contentSize);
            host.add(canvas, BorderLayout.CENTER);
            f.setContentPane(host);
            input = canvas;
            dnd = canvas;
        } else {
            // Software (or fallback if the GL backend is not available).
            lbl = new DisplayPanel(this.videoScale);
            lbl.setPreferredSize(contentSize);
            lbl.setOpaque(true);
            lbl.setBackground(Color.BLACK);
            f.setContentPane(lbl);
            input = f;   // the listeners stay on the frame
            dnd = lbl;
        }

        f.pack();
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        f.setResizable(false);

        this.frame = f;
        this.label = lbl;
        this.glSurface = surface;
        this.inputComponent = input;
        this.dndComponent = dnd;

        applyWindowMode();     // makes the window visible
        setupDragAndDrop();
        if (surface != null) {
            surface.start();   // start the GL thread after the window is visible
        }
    }

    /** Creates the GL backend, falling back on software (null) if not available. */
    private static GlDisplaySurface createGlSurface(int frameWidth, int frameHeight) {
        try {
            return new GlDisplaySurface(frameWidth, frameHeight);
        } catch (Throwable t) {
            System.err.println("[GL] Backend OpenGL non disponibile, uso il rendering "
                    + "software: " + t);
            return null;
        }
    }

    /** Component to attach the input listeners to (GL canvas or software frame). */
    public Component getInputComponent() {
        return inputComponent;
    }

    /**
     * Enlargement factor of the emulated window, at least 1. Needed by whoever works in
     * screen pixels and must go back to Amiga pixels: the mouse above all.
     */
    public int getVideoScale() {
        return videoScale;
    }

    /**
     * Selects (live) the RetroArch shader preset, if the backend is OpenGL. No-op in
     * software/headless. {@code preset} = path of the preset, or {@code null} for no shader
     * (passthrough).
     */
    public void setShaderPreset(java.nio.file.Path preset) {
        if (glSurface != null) {
            glSurface.setShaderPreset(preset);
        }
    }

    /** Updates a parameter of the active OpenGL shader live. No-op in software. */
    public void setShaderParam(String name, float value) {
        if (glSurface != null) {
            glSurface.setShaderParam(name, value);
        }
    }

    private static Dimension contentSizeFor(AmigaWindowMode mode, int windowWidth, int windowHeight,
                                            GraphicsDevice device) {
        if (mode == AmigaWindowMode.WINDOWED || GraphicsEnvironment.isHeadless() || device == null) {
            return new Dimension(windowWidth, windowHeight);
        }
        Rectangle bounds = device.getDefaultConfiguration().getBounds();
        return new Dimension(bounds.width, bounds.height);
    }

    /**
     * Monitor on which to open the window, by configuration index ({@code video.monitor}):
     * 0 = primary screen, 1..n = other connected monitors in the order returned by the
     * toolkit. Index out of range (or headless) -&gt; primary (or {@code null} in headless).
     */
    public static GraphicsDevice screenDeviceFor(int monitorIndex) {
        if (GraphicsEnvironment.isHeadless()) return null;
        GraphicsDevice[] devices = screenDevices();
        if (devices.length == 0) return null;
        int i = (monitorIndex >= 0 && monitorIndex < devices.length) ? monitorIndex : 0;
        return devices[i];
    }

    /**
     * Connected monitors with the primary screen forced to position 0 (the order returned
     * by AWT does not guarantee the first is the primary).
     */
    public static GraphicsDevice[] screenDevices() {
        if (GraphicsEnvironment.isHeadless()) return new GraphicsDevice[0];
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice[] all = ge.getScreenDevices();
        if (all.length == 0) return all;
        GraphicsDevice def = ge.getDefaultScreenDevice();
        GraphicsDevice[] out = new GraphicsDevice[all.length];
        int n = 0;
        out[n++] = def;
        for (GraphicsDevice d : all) {
            if (d != def && n < out.length) out[n++] = d;
        }
        return n == out.length ? out : java.util.Arrays.copyOf(out, n);
    }

    /**
     * The panel the emulated window lives on in the software path. Here, and only here, the
     * enlargement happens: the composed canvas arrives at the frame's resolution and is
     * drawn x{@code scale} with NEAREST_NEIGHBOR, centred, with black around. No
     * intermediate buffer grows with the scaling.
     */
    private static final class DisplayPanel extends JComponent {
        private volatile BufferedImage image;
        private final int scale;

        DisplayPanel(int scale) {
            this.scale = Math.max(1, scale);
        }

        void setDisplayImage(BufferedImage image) {
            this.image = image;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            paintScaled(g);
        }

        /**
         * Draws the current canvas enlarged. Extracted from {@link #paintComponent} because
         * the active rendering of the drag overlay uses it too, without going through repaint.
         */
        void paintScaled(Graphics g) {
            g.setColor(getBackground());
            g.fillRect(0, 0, getWidth(), getHeight());
            BufferedImage current = image;
            if (current == null) {
                return;
            }
            int dw = current.getWidth() * scale;
            int dh = current.getHeight() * scale;
            // Horizontal: centred. If the image is wider than the window the cut is
            // symmetric, consistent with the centring on the DIW done in composition.
            int dx = (getWidth() - dw) / 2;
            // Vertical: centred while it fits, anchored at the TOP when it does not.
            // Centring an image taller than the window would cut the first lines, and those
            // are not to be touched: the composition anchors the picture on the first
            // visible line on purpose (see composeFrameCanvas) so the content at the top is
            // never pushed down or cut. It really happens: a decorated window cannot exceed
            // the screen (Windows limits it and not even setSize forces it), so PAL overscan
            // at 2x (1152 px) on a 1080 panel ends up here.
            int dy = Math.max(0, (getHeight() - dh) / 2);
            if (scale == 1) {
                g.drawImage(current, dx, dy, null); // no transformation: historical path
                return;
            }
            Graphics2D g2 = (Graphics2D) g;
            Object old = g2.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
            // Integer scaling: nearest neighbour, otherwise Java2D would interpolate and the
            // Amiga pixels would blur, exactly what the option avoids.
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g2.drawImage(current, dx, dy, dx + dw, dy + dh,
                    0, 0, current.getWidth(), current.getHeight(), null);
            if (old != null) {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, old);
            }
        }
    }

    /**
     * Centres the window on the configured monitor ({@code setLocationRelativeTo(null)}
     * would always use the primary screen).
     */
    private void centerOnScreenDevice() {
        if (screenDevice == null) {
            frame.setLocationRelativeTo(null);
            return;
        }
        Rectangle b = screenDevice.getDefaultConfiguration().getBounds();
        frame.setLocation(b.x + (b.width - frame.getWidth()) / 2,
                          b.y + (b.height - frame.getHeight()) / 2);
    }

    private void applyWindowMode() {
        if (frame == null) return;
        if (windowMode == AmigaWindowMode.WINDOWED) {
            centerOnScreenDevice();
            frame.setVisible(true);
            return;
        }

        fullScreenDevice = screenDevice != null ? screenDevice
                : GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        Rectangle bounds = fullScreenDevice.getDefaultConfiguration().getBounds();
        frame.setLocation(bounds.x, bounds.y);
        frame.setBackground(Color.BLACK);
        frame.getContentPane().setBackground(Color.BLACK);

        if (windowMode == AmigaWindowMode.FULLSCREEN && fullScreenDevice.isFullScreenSupported()) {
            frame.setVisible(true);
            fullScreenDevice.setFullScreenWindow(frame);
        } else {
            frame.setBounds(bounds);
            frame.setVisible(true);
        }
    }

    /**
     * Suspends exclusive fullscreen before opening modal Swing dialogs. On Windows a
     * JDialog above an exclusive window can bring the desktop to the front, so the frame is
     * left as a normal borderless fullscreen.
     */
    public boolean suspendExclusiveFullScreen() {
        if (frame == null || windowMode != AmigaWindowMode.FULLSCREEN
                || fullScreenDevice == null
                || fullScreenDevice.getFullScreenWindow() != frame) {
            return false;
        }
        Rectangle bounds = fullScreenDevice.getDefaultConfiguration().getBounds();
        fullScreenDevice.setFullScreenWindow(null);
        frame.setBounds(bounds);
        frame.toFront();
        frame.requestFocus();
        return true;
    }

    /** Restores exclusive fullscreen after the F12 popup closes. */
    public void resumeExclusiveFullScreen() {
        if (frame == null || windowMode != AmigaWindowMode.FULLSCREEN
                || fullScreenDevice == null
                || !fullScreenDevice.isFullScreenSupported()) {
            return;
        }
        frame.toFront();
        fullScreenDevice.setFullScreenWindow(frame);
        frame.requestFocus();
    }

    /** Releases any exclusive fullscreen and closes the window. */
    public void dispose() {
        if (glSurface != null) {
            glSurface.dispose(); // stop the GL thread before destroying the window
        }
        if (fullScreenDevice != null && fullScreenDevice.getFullScreenWindow() == frame) {
            fullScreenDevice.setFullScreenWindow(null);
        }
        if (frame != null) {
            frame.dispose();
        }
    }

    public void setPixel(int x, int y, int rgb) {
        synchronized (imageLock) {
            if (x >= 0 && x < width && y >= 0 && y < height) {
                pixels[y * width + x] = rgb;
            }
        }
    }

    /**
     * Receives the raw framebuffer from VirtualCRT and produces the image to show.
     *
     * <p>Pixel doubling: in lores (non-hires) every lores pixel takes 2 raw columns in
     * Denise; the right column is written identical to the left, so displayWidth ==
     * rawWidth in every case (912 pixels).
     *
     * <p>Line doubling: in non-interlaced mode every raw line is written twice in a row,
     * bringing displayHeight to rawHeight * 2 (624 lines).
     *
     * <p>Interlace: the 312 lines of the current field are interleaved with those of the
     * previous field in the persistent {@code interlaceBuffer} (even/odd lines).
     */
    public void renderFrame(int[] rawBuffer, int rawWidth, int rawHeight, FrameInfo frameInfo) {
        if (rawBuffer == null) {
            return;
        }
        if (fpsOverlayEnabled) {
            updateFps();
        }
        this.lastFrameInfo = frameInfo;

        // OpenGL: hand the RAW (and the LED/FPS/counter overlay) to the GPU mailbox and
        // return. The composition of the image (crop, line doubling, shader) is on the GPU;
        // here only the overlay is drawn in a small ARGB image (as in software).
        if (glSurface != null) {
            glSurface.present(rawBuffer, rawWidth, rawHeight, frameInfo, renderGlOverlay());
            return;
        }

        boolean hires      = frameInfo != null && frameInfo.hires();
        boolean interlaced = frameInfo != null && frameInfo.interlaced();
        boolean evenField  = frameInfo == null || !interlaced || frameInfo.evenField();

        // Pixel doubling is automatic in lores; line doubling automatic if not interlaced.
        // Either way displayWidth = rawWidth and displayHeight = rawHeight * 2.
        int displayWidth  = rawWidth;
        int displayHeight = rawHeight * 2;

        if (interlaced) {
            if (interlaceBuffer == null || interlaceBufferWidth != displayWidth) {
                interlaceBuffer      = new int[displayWidth * rawHeight * 2];
                interlaceBufferWidth = displayWidth;
                Arrays.fill(interlaceBuffer, 0);
            }
        } else {
            interlaceBuffer      = null;
            interlaceBufferWidth = -1;
        }

        // Persistent back buffer, reallocated only when the size changes.
        if (backImage == null || backImage.getWidth() != displayWidth
                              || backImage.getHeight() != displayHeight) {
            backImage  = new BufferedImage(displayWidth, displayHeight, BufferedImage.TYPE_INT_RGB);
            backPixels = ((DataBufferInt) backImage.getRaster().getDataBuffer()).getData();
        }
        int[] outPixels = backPixels;

        // With interlace the frame is composed in the persistent buffer (which accumulates
        // the fields) and then copied to the back; otherwise it is written straight to back.
        int[] target = interlaced ? interlaceBuffer : outPixels;

        for (int srcY = 0; srcY < rawHeight; srcY++) {
            if (interlaced) {
                copyRow(rawBuffer, rawWidth, srcY, target, displayWidth,
                        srcY * 2 + (evenField ? 0 : 1), hires);
            } else {
                // Line doubling: every raw line written twice in a row
                int dstY = srcY * 2;
                copyRow(rawBuffer, rawWidth, srcY, target, displayWidth, dstY, hires);
                System.arraycopy(target, dstY * displayWidth,
                        target, (dstY + 1) * displayWidth, displayWidth);
            }
        }

        if (interlaced) {
            System.arraycopy(interlaceBuffer, 0, outPixels, 0, outPixels.length);
        }

        if (DEBUG_VISIBLE_AREA && frameInfo != null) {
            // vScale is always 2: interlace or line doubling cover both cases.
            drawVisibleAreaRect(outPixels, displayWidth, displayHeight,
                    frameInfo.visibleX(),
                    frameInfo.visibleY()      * 2,
                    frameInfo.visibleWidth(),
                    frameInfo.visibleHeight() * 2);
        }

        synchronized (imageLock) {
            // Swap front/back: the frame just composed (back) becomes the one shown; the old
            // front is recycled as the next back (zero allocations in steady state, once
            // both buffers have their stable size).
            BufferedImage shown    = backImage;
            int[]         shownPix = backPixels;
            backImage  = image;
            backPixels = pixels;
            image  = shown;
            pixels = shownPix;
            width  = displayWidth;
            height = displayHeight;
        }
        refresh();
    }

    /**
     * Copies a line from the raw buffer to the destination buffer. In hires it copies
     * straight. In lores it doubles every pixel (pixel doubling): the lores pixel at index
     * x is at raw[x*2] and is written to dst[x*2] and dst[x*2+1].
     */
    private static void copyRow(int[] src, int srcW, int srcY,
                                int[] dst, int dstW, int dstY, boolean hires) {
        int srcOff = srcY * srcW;
        int dstOff = dstY * dstW;
        if (hires) {
            System.arraycopy(src, srcOff, dst, dstOff, dstW);
        } else {
            int loresW = srcW / 2;
            for (int x = 0; x < loresW; x++) {
                int color = src[srcOff + x * 2];
                dst[dstOff + x * 2]     = color;
                dst[dstOff + x * 2 + 1] = color;
            }
        }
    }

    private static void drawVisibleAreaRect(int[] pixels, int w, int h,
                                            int rx, int ry, int rw, int rh) {
        int x1 = rx,      y1 = ry;
        int x2 = rx + rw - 1, y2 = ry + rh - 1;
        for (int x = x1; x <= x2; x++) {
            if (x >= 0 && x < w) {
                if (y1 >= 0 && y1 < h) pixels[y1 * w + x] = DEBUG_COLOR;
                if (y2 >= 0 && y2 < h) pixels[y2 * w + x] = DEBUG_COLOR;
            }
        }
        for (int y = y1; y <= y2; y++) {
            if (y >= 0 && y < h) {
                if (x1 >= 0 && x1 < w) pixels[y * w + x1] = DEBUG_COLOR;
                if (x2 >= 0 && x2 < w) pixels[y * w + x2] = DEBUG_COLOR;
            }
        }
    }

    // PERF: pool of reused canvases for the live path (refresh). Allocating a new
    // frameWidth x frameHeight BufferedImage (~1.6-3 MB) every frame was the main source of
    // garbage (GC pauses = micro-stutters). Triple buffering: the render thread composes in
    // the current canvas and passes it to the EDT; when it comes back to that canvas (3
    // frames later) the EDT has already drawn it.
    private BufferedImage[] livePool;
    private int livePoolIdx;

    public void refresh() {
        // In OpenGL presentation is on the GPU: no Swing composition here.
        if (label == null) {
            return;
        }
        // During a drag the overlay owns the label: the render thread must not overwrite
        // its icon with the live frame (it stays frozen under the overlay).
        if (frame != null && !dragOverlayActive) {
            if (livePool == null || livePool[0].getWidth() != windowWidth
                                 || livePool[0].getHeight() != windowHeight) {
                livePool = new BufferedImage[3];
                for (int i = 0; i < livePool.length; i++) {
                    livePool[i] = new BufferedImage(windowWidth, windowHeight,
                            BufferedImage.TYPE_INT_RGB);
                }
                livePoolIdx = 0;
            }
            BufferedImage canvas = livePool[livePoolIdx];
            livePoolIdx = (livePoolIdx + 1) % livePool.length;
            composeFrameCanvas(canvas);
            pendingLiveCanvas = canvas;
            SwingUtilities.invokeLater(repaintTask);
        }

        if (dumpEnabled) {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastDumpTime >= DUMP_INTERVAL_MS) {
                saveDump();
                lastDumpTime = currentTime;
            }
        }
    }

    private void publishPendingLiveCanvas() {
        if (frame == null || dragOverlayActive) {
            return;
        }
        BufferedImage canvas = pendingLiveCanvas;
        if (canvas != null) {
            label.setDisplayImage(canvas);
            frame.repaint();
        }
    }

    /**
     * Composes the current image, centred on the DIW area, into the given canvas, which is
     * always the size of the native frame, in EVERY window mode. The canvas is first
     * cleared to black because it may be reused from the pool and hold the previous frame.
     *
     * <p>Nothing is enlarged or centred in the window here: integer scaling and centring
     * (with the black borders of fullscreen) are the last blit, in
     * {@link DisplayPanel#paintScaled}. Keeping the composition at the frame's resolution
     * is what stops the per-frame memory traffic from growing with the scaling or the
     * screen resolution.
     */
    private void composeFrameCanvas(BufferedImage canvas) {
        synchronized (imageLock) {
            Graphics2D g = canvas.createGraphics();

            int imgX, imgY;
            FrameInfo fi = lastFrameInfo;
            // VERTICAL: no centring. The image is anchored at the top on the first visible
            // line (end of VBlank = line 26). The beam starts there and goes down; what
            // exceeds at the bottom stays out (overscan), but the content at the top is
            // never pushed down or cut. Centring vertically on the DIW pushed up the games
            // that open the DIW over the whole overscan but draw a sub-region (It Came From
            // The Desert) and could cut the bottom edge.
            // HORIZONTAL: centred on the DIW centre.
            imgY = -(DISPLAY_TOP_LINE * 2);
            if (fi != null && fi.visibleWidth() > 0) {
                int visCenterX = fi.visibleX() + fi.visibleWidth() / 2;
                imgX = frameWidth / 2 - visCenterX;
            } else {
                imgX = (frameWidth - image.getWidth()) / 2;
            }

            g.setColor(java.awt.Color.BLACK);
            g.fillRect(0, 0, frameWidth, frameHeight);
            g.drawImage(image, imgX, imgY, null);
            if (statusLedsEnabled) {
                drawStatusLeds(g);
            }
            if (fpsOverlayEnabled || frameCounterOverlayEnabled) {
                drawCounters(g);
            }
            g.dispose();
        }
    }

    /**
     * Composes the overlay for the OpenGL backend (LEDs/FPS/counter) in a transparent ARGB
     * image of the frame's size, reusing the same drawing as the software path
     * ({@link #drawStatusLeds}/{@link #drawCounters}). Returns {@code null} if no overlay is
     * on (so the GL backend uploads nothing).
     */
    private int[] renderGlOverlay() {
        if (!(statusLedsEnabled || fpsOverlayEnabled || frameCounterOverlayEnabled)) {
            return null;
        }
        if (glOverlayImage == null) {
            glOverlayImage = new BufferedImage(frameWidth, frameHeight, BufferedImage.TYPE_INT_ARGB);
            glOverlayPixels = ((DataBufferInt) glOverlayImage.getRaster().getDataBuffer()).getData();
        }
        Arrays.fill(glOverlayPixels, 0); // azzera a trasparente
        Graphics2D g = glOverlayImage.createGraphics();
        if (statusLedsEnabled) {
            drawStatusLeds(g);
        }
        if (fpsOverlayEnabled || frameCounterOverlayEnabled) {
            drawCounters(g);
        }
        g.dispose();
        return glOverlayPixels;
    }

    // -------------------------------------------------------------------------
    // FPS / frame counter overlays (diagnostics, OFF by default)
    // -------------------------------------------------------------------------

    private static final Font   COUNTER_FONT   = new Font("Monospaced", Font.BOLD, 16);
    private static final Color  COUNTER_COLOR  = new Color(0xFF, 0xE0, 0x40); // readable yellow
    private static final Color  COUNTER_SHADOW = new Color(0, 0, 0, 190);

    /** Updates the real FPS estimate over a moving 1-second window (only if the overlay is on). */
    private void updateFps() {
        long now = System.nanoTime();
        if (fpsWindowStartNs == 0L) {
            fpsWindowStartNs = now;
            fpsFrameAccum = 0;
            return;
        }
        fpsFrameAccum++;
        long elapsed = now - fpsWindowStartNs;
        if (elapsed >= 1_000_000_000L) {
            fpsValue = (int) Math.round(fpsFrameAccum * 1_000_000_000.0 / elapsed);
            fpsFrameAccum = 0;
            fpsWindowStartNs = now;
        }
    }

    /** Draws the active overlays: FPS (bottom left) and frame counter (bottom centre). */
    private void drawCounters(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(COUNTER_FONT);
        FontMetrics fm = g.getFontMetrics();
        int margin = 6;
        int baselineY = frameHeight - margin - fm.getDescent();

        if (fpsOverlayEnabled) {
            drawTextShadowed(g, "FPS " + fpsValue, margin, baselineY);
        }
        if (frameCounterOverlayEnabled) {
            FrameInfo fi = lastFrameInfo;
            long frames = fi != null ? fi.frameCount() : 0L;
            String s = "FRAME " + frames;
            int x = (frameWidth - fm.stringWidth(s)) / 2;
            drawTextShadowed(g, s, x, baselineY);
        }
    }

    /** Draws a string with a 1px shadow, readable on any background. */
    private void drawTextShadowed(Graphics2D g, String s, int x, int y) {
        g.setColor(COUNTER_SHADOW);
        g.drawString(s, x + 1, y + 1);
        g.setColor(COUNTER_COLOR);
        g.drawString(s, x, y);
    }

    // -------------------------------------------------------------------------
    // Status LEDs (power / drive): cosmetic overlay in the overscan
    // -------------------------------------------------------------------------

    // LED colours as on the real A500: POWER red, DRIVE green.
    private static final Color LED_POWER = new Color(0xFF, 0x20, 0x40); // rosso alimentazione
    private static final Color LED_DRIVE = new Color(0x5A, 0xFF, 0x2A); // green floppy activity
    private static final Color LED_REFLECTION_OFF = new Color(255, 255, 255, 0);
    private static final Color LED_BORDER = new Color(0, 0, 0, 130);
    private static final Color LED_LABEL = new Color(210, 210, 210, 180);
    private static final BasicStroke LED_BORDER_STROKE = new BasicStroke(1f);
    private static final LedStyle POWER_BRIGHT_STYLE = ledStyle(LED_POWER, 1.0f);
    private static final LedStyle POWER_DIM_STYLE    = ledStyle(LED_POWER, 0.6f);
    private static final LedStyle DRIVE_ACTIVE_STYLE = ledStyle(LED_DRIVE, 1.0f);
    private static final LedStyle DRIVE_IDLE_STYLE   = ledStyle(LED_DRIVE, 0.16f);

    private static final class LedStyle {
        final Color glowOuter;
        final Color glowInner;
        final Color core;
        final Color reflection;
        final boolean glow;

        LedStyle(Color glowOuter, Color glowInner, Color core, Color reflection, boolean glow) {
            this.glowOuter = glowOuter;
            this.glowInner = glowInner;
            this.core = core;
            this.reflection = reflection;
            this.glow = glow;
        }
    }

    private static LedStyle ledStyle(Color color, float intensity) {
        boolean glow = intensity > 0.4f;
        Color glowOuter = glow ? ledAlpha(color, (int) (55 * intensity / 2)) : color;
        Color glowInner = glow ? ledAlpha(color, (int) (55 * intensity)) : color;
        float k = 0.22f + 0.78f * intensity;
        Color core = new Color(
                Math.min(255, (int) (color.getRed()   * k)),
                Math.min(255, (int) (color.getGreen() * k)),
                Math.min(255, (int) (color.getBlue()  * k)));
        Color reflection = intensity > 0.0f
                ? new Color(255, 255, 255, (int) (95 * intensity))
                : LED_REFLECTION_OFF;
        return new LedStyle(glowOuter, glowInner, core, reflection, glow);
    }

    private static Color ledAlpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
    }

    private static final boolean[] NO_LED_STATES = new boolean[0];

    /** A row of the LED panel: label + one or more LEDs with the same style. */
    private static final class LedRow {
        final String label;
        final boolean[] states;      // one state per LED (true = lit/active)
        final LedStyle activeStyle;  // style when the state is true
        final LedStyle idleStyle;    // style when the state is false

        LedRow(String label, boolean[] states, LedStyle activeStyle, LedStyle idleStyle) {
            this.label = label;
            this.states = states;
            this.activeStyle = activeStyle;
            this.idleStyle = idleStyle;
        }
    }

    /**
     * Draws the LEDs as on the A500: thin stacked rectangular bars with the label on the
     * left of each, bottom right in the overscan. POWER is always there; the DRIVE row
     * appears only if at least one floppy is connected and the HD row only if at least one
     * hard disk is connected (master on the left, slave on the right). Each row is split
     * into as many LEDs as there are connected units. Vector drawing of a few filled
     * rectangles: negligible cost, run once per frame in the compose.
     */
    private void drawStatusLeds(Graphics2D g) {
        boolean powerBright = powerLedBright != null && powerLedBright.getAsBoolean();
        boolean[] floppyStates = connectedFloppyLedStates();
        boolean[] hdStates = connectedHdLedStates();

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // Visible rows: POWER always; DRIVE and HD only if their units are connected. HD
        // shares the floppy's green (same styles) but with the label "HD".
        java.util.List<LedRow> rows = new java.util.ArrayList<>(3);
        rows.add(new LedRow("POWER", new boolean[]{powerBright}, POWER_BRIGHT_STYLE, POWER_DIM_STYLE));
        if (floppyStates.length > 0) {
            rows.add(new LedRow("DRIVE", floppyStates, DRIVE_ACTIVE_STYLE, DRIVE_IDLE_STYLE));
        }
        if (hdStates.length > 0) {
            rows.add(new LedRow("HD", hdStates, DRIVE_ACTIVE_STYLE, DRIVE_IDLE_STYLE));
        }
        BooleanSupplier netVisible = remotePlayLedVisible;
        if (netVisible != null && netVisible.getAsBoolean()) {
            BooleanSupplier netActive = remotePlayLedActive;
            // Green like DRIVE and HD, not red like POWER: it says "it is happening", and red
            // on this panel already means something else. If it does not look right on
            // screen, it is a constant (plan section 15.4).
            rows.add(new LedRow("NET",
                    new boolean[]{ netActive != null && netActive.getAsBoolean() },
                    DRIVE_ACTIVE_STYLE, DRIVE_IDLE_STYLE));
        }

        final int baseLedW = 59, ledH = 10; // thin bars like the real ones (84 reduced by 30%)
        final int smallLedGap = 3;
        final int labelGap = 10;         // label -> LED distance
        final int rowGap = 11;           // vertical distance between the rows
        final int padX = 14, padY = 12;  // inner padding

        g.setFont(ledLabelFont);
        FontMetrics fm = g.getFontMetrics();

        // Width of the LED group aligned on the largest number of LEDs among the rows, so
        // the columns stay aligned (POWER is always a single full-width LED).
        int maxLeds = 1;
        int labelColW = 0;
        for (LedRow r : rows) {
            maxLeds = Math.max(maxLeds, r.states.length);
            labelColW = Math.max(labelColW, fm.stringWidth(r.label));
        }
        final int ledW = alignedLedWidth(baseLedW, maxLeds, smallLedGap);
        int rowH = Math.max(ledH, fm.getAscent());

        int contentW = labelColW + labelGap + ledW;
        int contentH = rowH * rows.size() + rowGap * (rows.size() - 1);
        int housingW = contentW + padX * 2;
        int housingH = contentH + padY * 2;

        // No housing: LEDs and labels are drawn straight on the overscan.
        int contentX = frameWidth - housingW + padX;
        int contentY = frameHeight - housingH + padY;
        int ledX = contentX + labelColW + labelGap;
        int labelRight = contentX + labelColW; // right-alignment column of the labels

        for (int i = 0; i < rows.size(); i++) {
            LedRow r = rows.get(i);
            int cy = contentY + i * (rowH + rowGap) + rowH / 2;
            drawLedGroup(g, ledX, cy - ledH / 2, ledW, ledH, smallLedGap, r);
            drawLabelRight(g, fm, r.label, labelRight, cy);
        }
    }

    /** States of the floppy LED: one per connected drive, empty if there is no drive. */
    private boolean[] connectedFloppyLedStates() {
        FloppyDrive[] snapshot = drives;
        if (snapshot == null) return NO_LED_STATES;
        int count = 0;
        for (FloppyDrive drive : snapshot) {
            if (drive != null && drive.isConnected()) count++;
        }
        if (count == 0) return NO_LED_STATES;
        boolean[] states = new boolean[count];
        int out = 0;
        for (FloppyDrive drive : snapshot) {
            if (drive != null && drive.isConnected()) states[out++] = drive.isMotorRunning();
        }
        return states;
    }

    /** States of the HD LED: one per connected hard disk, empty if there is none. */
    private boolean[] connectedHdLedStates() {
        BooleanSupplier[] snapshot = hdLedSources;
        if (snapshot == null) return NO_LED_STATES;
        int count = 0;
        for (BooleanSupplier hd : snapshot) {
            if (hd != null) count++;
        }
        if (count == 0) return NO_LED_STATES;
        boolean[] states = new boolean[count];
        int out = 0;
        for (BooleanSupplier hd : snapshot) {
            if (hd != null) states[out++] = hd.getAsBoolean();
        }
        return states;
    }

    private static int alignedLedWidth(int baseWidth, int ledCount, int gap) {
        if (ledCount <= 1) return baseWidth;
        int gaps = gap * (ledCount - 1);
        int usable = Math.max(1, baseWidth - gaps);
        int remainder = usable % ledCount;
        return remainder == 0 ? baseWidth : baseWidth + (ledCount - remainder);
    }

    /** Draws the LED row split into {@code states.length} equal bars. */
    private void drawLedGroup(Graphics2D g, int x, int y, int totalW, int h,
                              int gap, LedRow row) {
        int count = Math.max(1, row.states.length);
        int ledW = (totalW - gap * (count - 1)) / count;
        for (int i = 0; i < count; i++) {
            drawLedBar(g, x + i * (ledW + gap), y, ledW, h,
                    row.states[i] ? row.activeStyle : row.idleStyle);
        }
    }

    /** Draws a rectangular LED (sharp corners): halo, body, highlight and border. */
    private void drawLedBar(Graphics2D g, int x, int y, int w, int h, LedStyle style) {
        if (style.glow) {
            g.setColor(style.glowOuter);
            g.fillRect(x - 6, y - 6, w + 12, h + 12);
            g.setColor(style.glowInner);
            g.fillRect(x - 3, y - 3, w + 6, h + 6);
        }

        g.setColor(style.core);
        g.fillRect(x, y, w, h);

        // Specular reflection in the upper half.
        g.setColor(style.reflection);
        g.fillRect(x + 1, y + 1, w - 2, Math.max(1, h / 3));

        // Dark border to stand out from the background.
        g.setColor(LED_BORDER);
        g.setStroke(LED_BORDER_STROKE);
        g.drawRect(x, y, w - 1, h - 1);
    }

    /** Draws a string right-aligned at {@code rightX}, vertically centred on {@code cy}. */
    private void drawLabelRight(Graphics2D g, FontMetrics fm, String s, int rightX, int cy) {
        g.setColor(LED_LABEL);
        int x = rightX - fm.stringWidth(s);
        int y = cy + (fm.getAscent() - fm.getDescent()) / 2;
        g.drawString(s, x, y);
    }

    /**
     * Composes a new (allocated) canvas for a screenshot/dump (not a hot path).
     */
    private BufferedImage buildFrameCanvas() {
        BufferedImage canvas = new BufferedImage(windowWidth, windowHeight,
                BufferedImage.TYPE_INT_RGB);
        composeFrameCanvas(canvas);
        return canvas;
    }

    private void saveDump() {
        try {
            File dir = AppHome.file("screenshots");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            File file = new File(dir, "dump_" + timestamp + ".png");
            ImageIO.write(captureForScreenshot(), "png", file);
        } catch (IOException e) {
            System.err.println("Errore durante il salvataggio del dump: " + e.getMessage());
        }
    }

    public void saveScreenshot(File file) throws IOException {
        File dir = file.getParentFile();
        if (dir != null && !dir.exists()) {
            dir.mkdirs();
        }
        ImageIO.write(captureForScreenshot(), "png", file);
    }

    /**
     * Image to save for a screenshot/dump: in OpenGL the back buffer read with
     * {@code glReadPixels} on the GL thread; in software (or if the GL capture is not
     * available) the Java2D composition.
     */
    private BufferedImage captureForScreenshot() {
        if (glSurface != null) {
            BufferedImage img = glSurface.snapshot();
            if (img != null) {
                return img;
            }
        }
        return buildFrameCanvas();
    }

    public BufferedImage getImage() {
        synchronized (imageLock) {
            return image;
        }
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public FrameInfo getLastFrameInfo() {
        return lastFrameInfo;
    }

    public JFrame getFrame() {
        return frame;
    }

    // -------------------------------------------------------------------------
    // Floppy drag-and-drop: four-quadrant overlay (DF0-DF3)
    // -------------------------------------------------------------------------

    // Palette dell'overlay.
    private static final Color OK_ACCENT     = new Color(0x3D, 0xDC, 0x84); // verde (drive disponibile)
    private static final Color OK_ACCENT_HI  = new Color(0x6B, 0xFF, 0xA8); // verde acceso (hover)
    private static final Color BAD_ACCENT    = new Color(0xFF, 0x52, 0x63); // red (drive missing)
    private static final Color DIM_VEIL      = new Color(0, 0, 0, 130);     // velo scuro sullo sfondo
    private static final Color GRID_LINE     = new Color(255, 255, 255, 38);

    /**
     * Installs a DropTarget on the label (contentPane). Unlike a plain TransferHandler, the
     * DropTargetListener gives the cursor position during the drag, so that it can: (1)
     * draw the quadrant overlay, (2) accept or refuse the drop per quadrant (refusing shows
     * the "no-drop" cursor), (3) deliver the file to the right drive.
     */
    private void setupDragAndDrop() {
        DropTargetListener dtl = new DropTargetListener() {
            @Override
            public void dragEnter(DropTargetDragEvent e) {
                if (!isFileDrag(e)) { e.rejectDrag(); return; }
                beginDragOverlay();
                hoverQuadrant = quadrantAt(e.getLocation());
                updateDragAcceptance(e);
                paintDragOverlay();
            }

            @Override
            public void dragOver(DropTargetDragEvent e) {
                if (!dragOverlayActive) { e.rejectDrag(); return; }
                // Repaint at EVERY dragOver, not only when the quadrant changes: direct
                // drawing (getGraphics + sync) takes reliably during dragOver, while the paint
                // of dragEnter can be lost. So any micro-movement makes the overlay appear and
                // stay, even when resting on the same drive (a disabled drive included).
                // Minimal cost: the foreground is cached, two drawImage calls + a sync.
                hoverQuadrant = quadrantAt(e.getLocation());
                paintDragOverlay();
                updateDragAcceptance(e);
            }

            @Override
            public void dropActionChanged(DropTargetDragEvent e) {
                updateDragAcceptance(e);
            }

            @Override
            public void dragExit(DropTargetEvent e) {
                endDragOverlay();
            }

            @Override
            public void drop(DropTargetDropEvent e) {
                handleDrop(e);
            }
        };
        new DropTarget(dndComponent, DnDConstants.ACTION_COPY, dtl, true);
    }

    private static boolean isFileDrag(DropTargetDragEvent e) {
        return e.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
    }

    /** Accepts the drag (copy cursor) only if the quadrant points to a connected drive. */
    private void updateDragAcceptance(DropTargetDragEvent e) {
        FloppyDrive d = driveAt(hoverQuadrant);
        if (d != null && d.isConnected()) {
            e.acceptDrag(DnDConstants.ACTION_COPY);
        } else {
            e.rejectDrag(); // show the "no-drop" cursor on the drives not available
        }
    }

    private FloppyDrive driveAt(int quadrant) {
        FloppyDrive[] d = drives;
        if (d == null || quadrant < 0 || quadrant >= d.length) return null;
        return d[quadrant];
    }

    /** Maps a position (relative to the DnD component) to a quadrant: 0=NW,1=NE,2=SW,3=SE. */
    private int quadrantAt(Point p) {
        int w = dndComponent.getWidth(), h = dndComponent.getHeight();
        if (w <= 0 || h <= 0 || p == null) return -1;
        int col = p.x < w / 2 ? 0 : 1;
        int row = p.y < h / 2 ? 0 : 1;
        return row * 2 + col;
    }

    /** Freezes the current frame as a grey background and activates the overlay. */
    private void beginDragOverlay() {
        dragOverlayActive = true;
        if (glSurface != null) {
            // OpenGL: the grey background is the snapshot of the back buffer (glReadPixels).
            BufferedImage snap = glSurface.snapshot();
            dragBgSnapshot = (snap != null) ? toGreyscale(snap) : null;
            return;
        }
        if (label == null) {
            return;
        }
        dragBgSnapshot = toGreyscale(buildFrameCanvas());
    }

    /** Deactivates the overlay and restores live rendering. */
    private void endDragOverlay() {
        dragOverlayActive = false;
        hoverQuadrant = -1;
        dragBgSnapshot = null;
        if (glSurface != null) {
            glSurface.setDragOverlay(null, 0, 0); // the GL thread resumes the live frame
            return;
        }
        refresh(); // recomposes the live frame on the label at once
    }

    /** Darkened, desaturated version of the canvas, used as the overlay's background. */
    private BufferedImage toGreyscale(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        int[] in  = ((DataBufferInt) src.getRaster().getDataBuffer()).getData();
        int[] dst = ((DataBufferInt) out.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < in.length; i++) {
            int rgb = in[i];
            int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
            int lum = (r * 77 + g * 150 + b * 29) >> 8;   // luminanza ~Rec.601
            lum = (lum * 90) / 255;                        // darkens to make the overlay stand out
            dst[i] = (lum << 16) | (lum << 8) | lum;
        }
        return out;
    }

    /**
     * Composes the overlay and shows it on the label. The grey background is dynamic
     * (changes at every drag), while the foreground (veil + grid + quadrants) is
     * pre-rendered for every hover state: composing a frame of the drag costs two
     * drawImage calls.
     */
    private void paintDragOverlay() {
        BufferedImage bg = dragBgSnapshot;
        if (bg == null || frame == null) return;

        BufferedImage[] cache = overlayFgCache;
        if (cache == null) {                 // fallback if the pre-render is not ready yet
            cache = buildOverlayCache();
            overlayFgCache = cache;
        }
        BufferedImage fg = cache[hoverQuadrant + 1];

        BufferedImage canvas = new BufferedImage(windowWidth, windowHeight,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        g.drawImage(bg, 0, 0, windowWidth, windowHeight, null);
        g.drawImage(fg, 0, 0, null);
        g.dispose();

        if (glSurface != null) {
            // OpenGL: the static overlay is drawn by the GPU over the whole window.
            int[] px = ((DataBufferInt) canvas.getRaster().getDataBuffer()).getData();
            glSurface.setDragOverlay(px, windowWidth, windowHeight);
        } else {
            setOverlayIcon(canvas);
        }
    }

    /**
     * Builds the overlay's foreground layers in the background, so the first drag does not
     * pay the cost of fonts/antialiasing/glyph paths. No effect in headless.
     *
     * <p>Besides the cache, it "warms up" the compose path used at every frame of the drag
     * (toGreyscale + drawImage) and AWT's data transfer subsystem: all first-use costs
     * (class loading/JIT/Java2D) that would otherwise be paid entirely at the first drag.
     */
    private void prebuildOverlayCacheAsync() {
        if (frame == null) return;
        Thread t = new Thread(() -> {
            BufferedImage[] cache = buildOverlayCache();
            overlayFgCache = cache;
            warmupDragPaintPath(cache);
            warmupDndSubsystem();
        }, "overlay-prerender");
        t.setDaemon(true);
        t.start();
    }

    /** Runs the drag's compose path once to pay its first-use costs. */
    private void warmupDragPaintPath(BufferedImage[] cache) {
        try {
            BufferedImage grey = toGreyscale(buildFrameCanvas());
            BufferedImage canvas = new BufferedImage(windowWidth, windowHeight,
                    BufferedImage.TYPE_INT_RGB);
            Graphics2D g = canvas.createGraphics();
            g.drawImage(grey, 0, 0, windowWidth, windowHeight, null);
            g.drawImage(cache[0], 0, 0, null);
            g.dispose();
            canvas.getWidth(); // keeps the best-effort canvas warm-up explicit
        } catch (Throwable ignore) {
            // Best-effort warm-up: an error here must not affect anything.
        }
    }

    /** Touches AWT's data transfer subsystem to bring its class loading forward. */
    private void warmupDndSubsystem() {
        try {
            DataFlavor.javaFileListFlavor.getHumanPresentableName();
            java.awt.datatransfer.SystemFlavorMap.getDefaultFlavorMap();
        } catch (Throwable ignore) {
            // best-effort
        }
    }

    /**
     * Pre-renders the overlay's foreground (transparent ARGB) for every hover state.
     * Index = hoverQuadrant+1 (0 = no hover). States that do not change appearance (hover on
     * a drive not connected) reuse the "no hover" layer.
     */
    private BufferedImage[] buildOverlayCache() {
        BufferedImage[] cache = new BufferedImage[5];
        cache[0] = renderOverlayForeground(-1);          // no quadrant hovered
        for (int i = 0; i < 4; i++) {
            FloppyDrive d = driveAt(i);
            boolean connected = d != null && d.isConnected();
            // Only connected drives change appearance on hover: the others reuse cache[0].
            cache[i + 1] = connected ? renderOverlayForeground(i) : cache[0];
        }
        return cache;
    }

    /** Renders the complete foreground (veil + grid + 4 quadrants) for a hover state. */
    private BufferedImage renderOverlayForeground(int hover) {
        BufferedImage img = new BufferedImage(windowWidth, windowHeight,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        g.setColor(DIM_VEIL);
        g.fillRect(0, 0, windowWidth, windowHeight);

        int halfW = windowWidth / 2, halfH = windowHeight / 2;
        g.setColor(GRID_LINE);
        g.setStroke(new BasicStroke(1f));
        g.drawLine(halfW, 0, halfW, windowHeight);
        g.drawLine(0, halfH, windowWidth, halfH);

        for (int i = 0; i < 4; i++) {
            int col = i % 2, row = i / 2;
            paintQuadrant(g, i, col * halfW, row * halfH, halfW, halfH, hover);
        }
        g.dispose();
        return img;
    }

    /** Draws a single quadrant: card, floppy glyph, DFx label and subtitle. */
    private void paintQuadrant(Graphics2D g, int idx, int qx, int qy, int qw, int qh, int hover) {
        FloppyDrive d  = driveAt(idx);
        boolean connected = d != null && d.isConnected();
        boolean hovered   = idx == hover;
        Color accent      = connected ? OK_ACCENT : BAD_ACCENT;
        Color accentHi    = connected ? OK_ACCENT_HI : BAD_ACCENT;

        int margin = 24;
        int cardX = qx + margin, cardY = qy + margin;
        int cardW = qw - 2 * margin, cardH = qh - 2 * margin;
        int arc = 30;
        RoundRectangle2D card = new RoundRectangle2D.Float(cardX, cardY, cardW, cardH, arc, arc);

        // Translucent fill: stronger for the available drive under the cursor.
        int fillA = connected ? (hovered ? 64 : 26) : 20;
        g.setColor(withAlpha(accent, fillA));
        g.fill(card);

        // Outer halo on the active available quadrant.
        if (hovered && connected) {
            g.setColor(withAlpha(accentHi, 80));
            g.setStroke(new BasicStroke(11f));
            g.draw(card);
        }

        // Border: solid for the available drives, dashed for the missing ones.
        g.setColor(hovered ? accentHi : withAlpha(accent, 205));
        if (connected) {
            g.setStroke(new BasicStroke(hovered ? 3.5f : 2.5f));
        } else {
            g.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                    0f, new float[]{11f, 9f}, 0f));
        }
        g.draw(card);

        int cx = qx + qw / 2, cy = qy + qh / 2;

        drawFloppyGlyph(g, cx, cy - 50, 58, accent, connected);

        // DFx label.
        g.setFont(overlayFont.deriveFont(Font.BOLD, 46f));
        g.setColor(hovered && connected ? accentHi : accent);
        drawCentered(g, "DF" + idx, cx, cy + 18);

        // Status subtitle.
        String sub = connected
                ? (hovered ? io.github.zolfrik.amija.i18n.Messages.t("overlay.drop")
                           : io.github.zolfrik.amija.i18n.Messages.t("overlay.available"))
                : io.github.zolfrik.amija.i18n.Messages.t("overlay.unavailable");
        g.setFont(overlayFont.deriveFont(Font.PLAIN, 15f));
        g.setColor(connected ? withAlpha(Color.WHITE, 180) : withAlpha(BAD_ACCENT, 200));
        drawCentered(g, sub, cx, cy + 52);
    }

    /** Stylised glyph of a 3.5" floppy centred at (cx,cy). */
    private void drawFloppyGlyph(Graphics2D g, int cx, int cy, int s, Color accent, boolean connected) {
        int w = (int) (s * 0.86), h = s;
        int x = cx - w / 2, y = cy - h / 2;
        int cut = (int) (w * 0.30);
        int r = 7;

        // Body with the top right corner cut (typical of the 3.5").
        Path2D body = new Path2D.Float();
        body.moveTo(x, y + r);
        body.quadTo(x, y, x + r, y);
        body.lineTo(x + w - cut, y);
        body.lineTo(x + w, y + cut);
        body.lineTo(x + w, y + h - r);
        body.quadTo(x + w, y + h, x + w - r, y + h);
        body.lineTo(x + r, y + h);
        body.quadTo(x, y + h, x, y + h - r);
        body.closePath();

        g.setColor(withAlpha(accent, connected ? 55 : 36));
        g.fill(body);
        g.setColor(accent);
        g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(body);

        // Metal shutter at the top.
        int shW = (int) (w * 0.40), shH = (int) (h * 0.26);
        int shX = cx - shW / 2, shY = y + (int) (h * 0.07);
        g.fill(new RoundRectangle2D.Float(shX, shY, shW, shH, 4, 4));
        // Notch in the shutter.
        g.setColor(withAlpha(Color.BLACK, 120));
        int notchW = Math.max(3, shW / 5);
        g.fillRect(shX + shW - notchW - 3, shY + 3, notchW, shH - 6);

        // Label area at the bottom.
        int lbW = (int) (w * 0.66), lbH = (int) (h * 0.44);
        int lbX = cx - lbW / 2, lbY = y + h - lbH - (int) (h * 0.10);
        g.setColor(withAlpha(Color.WHITE, connected ? 45 : 28));
        g.fill(new RoundRectangle2D.Float(lbX, lbY, lbW, lbH, 4, 4));
        g.setColor(withAlpha(accent, 160));
        g.setStroke(new BasicStroke(1.4f));
        g.draw(new RoundRectangle2D.Float(lbX, lbY, lbW, lbH, 4, 4));
    }

    /** Draws a string centred horizontally and vertically around (cx,cy). */
    private void drawCentered(Graphics2D g, String s, int cx, int cy) {
        FontMetrics fm = g.getFontMetrics();
        int x = cx - fm.stringWidth(s) / 2;
        int y = cy - (fm.getAscent() + fm.getDescent()) / 2 + fm.getAscent();
        g.drawString(s, x, y);
    }

    private static Color withAlpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
    }

    private void setOverlayIcon(BufferedImage canvas) {
        // The DnD callbacks run on the EDT, but during a native drag the EDT is in a modal
        // loop: asynchronous repaints and paintImmediately show only when AWT generates the
        // "drag-under" feedback (i.e. ONLY on acceptDrag) or at the next dragOver. On
        // rejectDrag (drive disabled, mouse still) the overlay would never appear. So the
        // drawing goes DIRECTLY onto the label's Graphics (active rendering) and the pipeline
        // is flushed with Toolkit.sync(): the pixels reach the screen at once, whatever
        // accept/reject and the mouse movement. setDisplayImage keeps the state consistent
        // for any later repaint.
        label.setDisplayImage(canvas);
        Graphics g = label.getGraphics();
        if (g != null) {
            try {
                // Same blit as paintComponent (enlargement + centring): drawing here at 1:1
                // would leave the window uncovered with integer scaling > 1.
                label.paintScaled(g);
            } finally {
                g.dispose();
            }
            Toolkit.getDefaultToolkit().sync();
        }
    }

    /** Completes a drop: validates quadrant and file, then inserts the disk in the drive. */
    private void handleDrop(DropTargetDropEvent e) {
        int q = quadrantAt(e.getLocation());
        FloppyDrive drive = driveAt(q);
        if (drive == null || !drive.isConnected()
                || !e.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            e.rejectDrop();
            endDragOverlay();
            return;
        }
        e.acceptDrop(DnDConstants.ACTION_COPY);
        boolean ok = false;
        try {
            @SuppressWarnings("unchecked")
            List<File> files = (List<File>) e.getTransferable()
                    .getTransferData(DataFlavor.javaFileListFlavor);
            File disk = firstSupportedDisk(files);
            if (disk != null) {
                handleDiskDrop(drive, q, disk);
                ok = true;
            }
        } catch (Exception ex) {
            System.err.println("[DND] Errore ricezione file: " + ex.getMessage());
        }
        e.dropComplete(ok);
        endDragOverlay();
    }

    /**
     * First .adf/.ipf/.zip file of the dragged list, or null.
     *
     * <p>{@code Locale.ROOT} and not the system language: in Turkish the lower case of
     * {@code 'I'} is a dotless i, so {@code "GIOCO.IPF"} would not match {@code ".ipf"}: a
     * dragged file the emulator silently ignores, and only on some machines. Extensions are
     * identifiers, not text to show: they are compared in the neutral locale.
     */
    private static File firstSupportedDisk(List<File> files) {
        if (files == null) return null;
        for (File f : files) {
            String n = f.getName().toLowerCase(java.util.Locale.ROOT);
            if (n.endsWith(".adf") || n.endsWith(".ipf") || n.endsWith(".zip")) return f;
        }
        return null;
    }

    /**
     * Inserts the dropped disk in the given drive. If the drive already holds a disk: ejects
     * it, waits 1500 ms to give the Kickstart time to notice the eject, then inserts the new
     * one. Runs on a daemon thread so the EDT is not blocked.
     */
    private void handleDiskDrop(FloppyDrive drive, int driveIndex, File file) {
        if (drive == null) return;
        final String id = drive.getId();
        Thread t = new Thread(() -> {
            try {
                if (drive.isDiskInserted()) {
                    System.out.println("[DND] " + id + ": espulsione disco corrente...");
                    drive.ejectDisk();
                    Thread.sleep(1500);
                }
                System.out.println("[DND] " + id + ": inserimento " + file.getName());
                drive.loadAdf(file.getAbsolutePath(), false);
                System.out.println("[DND] " + id + ": disco inserito.");
                // Align the configuration (F12 popup) with the disk just inserted.
                ObjIntConsumer<String> l = diskDropListener;
                if (l != null) l.accept(file.getAbsolutePath(), driveIndex);
            } catch (Exception e) {
                System.err.println("[DND] Errore inserimento disco: " + e.getMessage());
            }
        }, "disk-drop");
        t.setDaemon(true);
        t.start();
    }

}
