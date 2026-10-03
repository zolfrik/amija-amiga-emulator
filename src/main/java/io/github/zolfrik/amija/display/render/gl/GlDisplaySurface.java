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

package io.github.zolfrik.amija.display.render.gl;

import io.github.zolfrik.amija.display.FrameInfo;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.awt.AWTGLCanvas;
import org.lwjgl.opengl.awt.GLData;

import java.awt.Component;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.IntBuffer;
import java.nio.file.Path;

import static org.lwjgl.opengl.GL30.*;

/**
 * OpenGL (LWJGL) rendering surface hosted inside the existing Swing window through
 * {@code AWTGLCanvas} (lwjgl3-awt). With {@code renderer=opengl} it replaces the Swing panel
 * of {@code AmigaDisplay}: it receives the RAW 912x312 framebuffer produced by
 * {@code VirtualCRT} and presents it on the GPU.
 *
 * <p><b>Confinement.</b> It is the only place in the project that depends on LWJGL: the
 * emulation core knows nothing about it and keeps calling {@code AmigaDisplay.renderFrame}.
 *
 * <p><b>Prepass (always on).</b> The RAW is uploaded to a texture and cropped into the
 * source texture <b>ORIG</b> (at native height) with the same framing as the software path
 * (anchored on line 26, centred horizontally on the centre of the DIW window; see
 * {@code AmigaDisplay.composeLogicalFrameCanvas}); lores pixels have the odd column
 * duplicated from the even one (as {@code AmigaDisplay.copyRow}).
 *
 * <p><b>Presentation.</b>
 * <ul>
 *   <li><b>No shader</b>: ORIG is drawn on the back buffer with aspect fit (black bars);
 *       line doubling is implicit in the GPU scaling.</li>
 *   <li><b>Shader</b>: ORIG enters a RetroArch {@link ShaderChain} ({@code .glslp}); the
 *       last pass draws on the back buffer with the aspect-fit viewport.
 *       {@link #setShaderPreset(Path)} selects the preset live (rebuilt on the GL
 *       thread).</li>
 * </ul>
 *
 * <p><b>Threading.</b> Every GL call happens on a dedicated thread ("amiga-gl-render")
 * that calls {@link AWTGLCanvas#render()}. The emulation thread hands over frames through
 * {@link #present} into a mailbox (last frame wins): it never blocks and shares no logical
 * state with the emulation.
 */
public final class GlDisplaySurface {

    /** Width of the RAW framebuffer (912). */
    public static final int RAW_W = 912;
    /** Height of the RAW framebuffer (312). */
    public static final int RAW_H = 312;

    /** First visible raster line (end of VBlank), as {@code AmigaDisplay.DISPLAY_TOP_LINE}. */
    private static final int DISPLAY_TOP_LINE = 26;

    // Logical size of the crop (e.g. 724x576 PAL overscan): defines the source rectangle cut
    // from the RAW and the target aspect ratio of the viewport.
    private final int frameWidth;
    private final int frameHeight;

    private final AWTGLCanvas canvas;
    private volatile boolean running;
    private Thread renderThread;
    private volatile boolean renderErrorLogged;
    // lwjgl3-awt's disposeCanvas() is NOT idempotent: on the second call the
    // JAWTDrawingSurface is already null and it throws an NPE. The context must be released
    // on the GL thread (in the loop's finally), but then the peer's removeNotify (EDT) calls
    // disposeCanvas again: this flag makes the second call a no-op.
    private volatile boolean glContextDisposed;

    // ---- Mailbox (emulation thread -> GL thread) ---------------------------------------
    private final Object lock = new Object();
    private int[] mailboxPixels = new int[RAW_W * RAW_H];
    private long submittedId;   // incremented at every present()
    private long uploadedId;    // last id uploaded to the texture by the GL thread
    // Crop geometry of the frame in the mailbox (updated in present()).
    private int pendingSrcX0, pendingSrcY0, pendingSrcW, pendingSrcH;
    private boolean pendingLores;

    // Size of the ORIG source texture (crop at NATIVE height, not doubled): width =
    // frameWidth, height = frameHeight/2. It is the "InputSize" the shaders see.
    private final int origW;
    private final int origH;

    // ---- State of the GL thread only --------------------------------------------------
    private int texId;              // RAW 912x312 texture
    private int origFbo, origTex;   // FBO+texture ORIG (prepass)
    private final int[] scratch = new int[RAW_W * RAW_H];
    private final IntBuffer uploadBuf = BufferUtils.createIntBuffer(RAW_W * RAW_H);
    private boolean newUpload;
    private int gSrcX0, gSrcY0, gSrcW, gSrcH;
    private long frameCounter;      // frames presented (FrameCount uniform of the shaders)

    // ---- Shader chain -----------------------------------------------------------------
    private volatile Path requestedShader;   // null = none (passthrough)
    private volatile boolean shaderDirty;
    private volatile RenderChain chain;      // built/used by the GL thread; setParam from the EDT

    // ---- Overlay (LEDs, FPS, counter) -----------------------------------------------------
    // ARGB image (frameWidth x frameHeight) composed with Java2D by AmigaDisplay and handed
    // over with present(): the GL thread uploads it to a texture and alpha-blends it over
    // the image, aligned with the viewport.
    private final int[] overlayMailbox;
    private final int[] overlayScratch;
    private final IntBuffer overlayBuf;
    private boolean overlayPresent;          // there is an overlay to show
    private long overlaySubmittedId, overlayUploadedId;
    private int overlayTex;

    // ---- Snapshot (glReadPixels, for F12 screenshots)---------------------------------
    private SnapshotReq pendingSnapshot;

    // ---- Static drag-and-drop overlay (covers the whole window during the drag) -----------
    // Opaque ARGB image (frozen grey background + DFx quadrants) composed by AmigaDisplay:
    // while it is present, the GL thread draws it over the whole window instead of the live
    // frame (like the label "freeze" in the software path).
    private volatile boolean dragPresent;
    private int[] dragMailbox;               // size dw*dh (allocated on demand)
    private int[] dragScratch;
    private IntBuffer dragBuf;
    private int dragW, dragH;
    private boolean dragDirty;
    private int dragTex, dragTexW, dragTexH;

    public GlDisplaySurface(int frameWidth, int frameHeight) {
        this.frameWidth = frameWidth;
        this.frameHeight = frameHeight;
        this.origW = frameWidth;
        this.origH = frameHeight / 2;
        this.overlayMailbox = new int[frameWidth * frameHeight];
        this.overlayScratch = new int[frameWidth * frameHeight];
        this.overlayBuf = BufferUtils.createIntBuffer(frameWidth * frameHeight);

        GLData data = new GLData();
        // Compatibility profile (default on Windows drivers): allows fixed-function for the
        // passthrough and the compilation of "classic" RetroArch GLSL shaders.
        data.swapInterval = 0; // the cadence is set by the emulation (50/60 Hz)
        this.canvas = new AWTGLCanvas(data) {
            @Override
            public void initGL() {
                GL.createCapabilities();
                createGlObjects();
            }

            @Override
            public void paintGL() {
                renderGl();
                swapBuffers();
            }

            @Override
            public void disposeCanvas() {
                // Idempotent: the GL thread calls it in the loop's finally, then the peer's
                // removeNotify (EDT) would call it again on a surface already released.
                if (glContextDisposed) return;
                glContextDisposed = true;
                super.disposeCanvas();
            }
        };
        this.canvas.setFocusable(true);
    }

    /** The AWT (heavyweight) component to put in the window and to attach the input to. */
    public Component getComponent() {
        return canvas;
    }

    /** Starts the GL rendering thread (idempotent). Call after the window is visible. */
    public void start() {
        if (renderThread != null) return;
        running = true;
        renderThread = new Thread(this::renderLoop, "amiga-gl-render");
        renderThread.setDaemon(true);
        renderThread.start();
    }

    /** Stops the GL thread and releases the surface. */
    public void dispose() {
        running = false;
        Thread t = renderThread;
        renderThread = null;
        if (t != null) {
            synchronized (lock) {
                lock.notifyAll();
            }
            try {
                t.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Hands over a RAW frame to present (called by the emulation thread). Copies the buffer
     * into the mailbox and computes the crop geometry; returns at once. If the GL thread is
     * behind, the previous frame not yet uploaded is simply overwritten (latest frame wins).
     */
    public void present(int[] raw, int rawW, int rawH, FrameInfo info) {
        present(raw, rawW, rawH, info, null);
    }

    /**
     * Like {@link #present(int[], int, int, FrameInfo)} but with an {@code overlayArgb}
     * (ARGB image {@code frameWidth x frameHeight}, {@code null} = no overlay) to alpha-blend
     * over the image (LEDs/FPS/counter).
     */
    public void present(int[] raw, int rawW, int rawH, FrameInfo info, int[] overlayArgb) {
        if (raw == null || raw.length != RAW_W * RAW_H) return;
        synchronized (lock) {
            System.arraycopy(raw, 0, mailboxPixels, 0, RAW_W * RAW_H);
            boolean lores = info != null && !info.hires();
            int visCenterX = (info != null && info.visibleWidth() > 0)
                    ? info.visibleX() + info.visibleWidth() / 2
                    : RAW_W / 2;
            // Same arithmetic as AmigaDisplay.composeLogicalFrameCanvas:
            // a frameWidth-wide window centred on the DIW centre, anchored at line 26.
            pendingSrcX0 = visCenterX - frameWidth / 2;
            pendingSrcY0 = DISPLAY_TOP_LINE;
            pendingSrcW = frameWidth;
            pendingSrcH = frameHeight / 2;
            pendingLores = lores;
            if (overlayArgb != null && overlayArgb.length == overlayMailbox.length) {
                System.arraycopy(overlayArgb, 0, overlayMailbox, 0, overlayMailbox.length);
                overlayPresent = true;
                overlaySubmittedId++;
            } else {
                overlayPresent = false;
            }
            submittedId++;
            lock.notifyAll();
        }
    }

    /**
     * Captures the current content of the back buffer (for the F12 screenshot). Callable from
     * any thread: the {@code glReadPixels} read is done by the GL thread at the next frame.
     * Returns {@code null} if nothing arrives within the timeout.
     */
    public BufferedImage snapshot() {
        SnapshotReq req = new SnapshotReq();
        synchronized (lock) {
            pendingSnapshot = req;
            lock.notifyAll();
        }
        synchronized (req) {
            // Generous timeout: the first compilation of a heavy shader (e.g. crt-royale, 12
            // passes + LUTs) keeps the GL thread busy for a few seconds; a screenshot asked
            // for at that moment must wait, not fall back on black at once.
            long deadline = System.currentTimeMillis() + Long.getLong("amiga.gl.snaptimeout", 5000);
            while (!req.done) {
                long wait = deadline - System.currentTimeMillis();
                if (wait <= 0) break;
                try {
                    req.wait(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return req.image;
    }

    /** Snapshot request: the GL thread fills {@code image} and signals {@code done}. */
    private static final class SnapshotReq {
        volatile BufferedImage image;
        volatile boolean done;
    }

    /**
     * Sets (or removes, with {@code argb == null}) the static drag-and-drop overlay: an
     * opaque ARGB image {@code w x h} drawn over the whole window instead of the live frame.
     * Callable from the EDT; the upload happens on the GL thread.
     */
    public void setDragOverlay(int[] argb, int w, int h) {
        synchronized (lock) {
            if (argb == null || w <= 0 || h <= 0) {
                dragPresent = false;
            } else {
                int n = w * h;
                if (dragMailbox == null || dragMailbox.length < n) {
                    dragMailbox = new int[n];
                    dragScratch = new int[n];
                }
                System.arraycopy(argb, 0, dragMailbox, 0, Math.min(argb.length, n));
                dragW = w;
                dragH = h;
                dragDirty = true;
                dragPresent = true;
            }
            lock.notifyAll();
        }
    }

    /**
     * Selects (live) the RetroArch shader preset. The (re)compilation happens on the GL
     * thread at the next frame; an error falls back on passthrough without crashing.
     *
     * @param preset path of the {@code .glslp} or {@code .slangp}, or {@code null} for
     *               "none".
     */
    public void setShaderPreset(Path preset) {
        synchronized (lock) {
            requestedShader = preset;
            shaderDirty = true;
            lock.notifyAll();
        }
    }

    /**
     * Updates a parameter of the active shader live (from the parameters popup). No-op if no
     * shader is active or the parameter does not exist. Applied at the next frame by the GL
     * thread.
     */
    public void setShaderParam(String name, float value) {
        RenderChain c = chain;
        if (c != null) {
            c.setParam(name, value);
        }
    }

    // =====================================================================================
    // Thread GL
    // =====================================================================================

    private void renderLoop() {
        try {
            while (running) {
                try {
                    if (canvas.isValid()) {
                        canvas.render();
                    }
                } catch (Throwable t) {
                    if (!renderErrorLogged) {
                        renderErrorLogged = true;
                        System.err.println("[GL] Errore di rendering: " + t);
                    }
                }
                // Waits for a new frame (or a short timeout, for canvas resizes).
                synchronized (lock) {
                    try {
                        lock.wait(8);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } finally {
            // The shader chain must be released here, with the context still alive on this thread.
            if (chain != null) {
                try {
                    chain.dispose();
                } catch (Throwable ignore) {
                    // best-effort
                }
                chain = null;
            }
            // Release the GL context on the SAME thread that owns it (initGL/render happened
            // here). Delegating it to the EDT, or leaving it to the peer's removeNotify when
            // the window is disposed, makes the native JAWT teardown access a context that
            // thread cannot handle, with a crash (access violation in awt.dll). Done here,
            // the later removeNotify finds the context already released (idempotent).
            try {
                canvas.disposeCanvas();
            } catch (Throwable ignore) {
                // best effort: context never created or already destroyed
            }
        }
    }

    /** Creates textures and GL state (once, on the GL thread with the context current). */
    private void createGlObjects() {
        // RAW 912x312 texture, updated with glTexSubImage2D at every new frame.
        texId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texId);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, RAW_W, RAW_H, 0,
                GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, 0L);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        // CLAMP_TO_BORDER + black border: columns/rows outside the RAW (extreme DIW, aspect
        // fit) stay black instead of smearing the edge.
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_BORDER);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_BORDER);
        glTexParameterfv(GL_TEXTURE_2D, GL_TEXTURE_BORDER_COLOR, new float[]{0f, 0f, 0f, 1f});

        // ORIG texture + FBO (crop at native height): source of the passthrough and the shaders.
        origTex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, origTex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, origW, origH, 0,
                GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, 0L);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_BORDER);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_BORDER);
        glTexParameterfv(GL_TEXTURE_2D, GL_TEXTURE_BORDER_COLOR, new float[]{0f, 0f, 0f, 1f});
        origFbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, origFbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, origTex, 0);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);

        // Overlay texture (LEDs/FPS/counter), ARGB frameWidth x frameHeight.
        overlayTex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, overlayTex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, frameWidth, frameHeight, 0,
                GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, 0L);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        // Texture for the static drag-and-drop overlay (size set at the first upload).
        dragTex = glGenTextures();

        glDisable(GL_BLEND);
        glDisable(GL_DEPTH_TEST);
        glClearColor(0f, 0f, 0f, 1f);

        // The context version decides what can be loaded: complex .slangp presets want 4.3
        // (see SlangChain.build). Worth the log line.
        System.out.println("[GL] OpenGL " + glGetString(GL_VERSION)
                + " — " + glGetString(GL_RENDERER));
    }

    private void renderGl() {
        if (shaderDirty) {
            rebuildChain();
        }

        int cw = Math.max(1, canvas.getWidth());
        int ch = Math.max(1, canvas.getHeight());

        // During drag-and-drop: draw the static overlay over the whole window and ignore the
        // live frame (like the label "freeze" in the software path).
        if (dragPresent) {
            drawDragOverlay(cw, ch);
            return;
        }

        boolean haveFrame;
        synchronized (lock) {
            if (submittedId != uploadedId) {
                fillScratchLocked();
                gSrcX0 = pendingSrcX0;
                gSrcY0 = pendingSrcY0;
                gSrcW = pendingSrcW;
                gSrcH = pendingSrcH;
                uploadedId = submittedId;
                newUpload = true;
            }
            haveFrame = uploadedId > 0;
        }

        glViewport(0, 0, cw, ch);
        glClear(GL_COLOR_BUFFER_BIT);
        if (!haveFrame) {
            return; // nothing to show yet; swapBuffers by the caller
        }

        if (newUpload) {
            glBindTexture(GL_TEXTURE_2D, texId);
            uploadBuf.clear();
            uploadBuf.put(scratch, 0, scratch.length);
            uploadBuf.flip();
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, RAW_W, RAW_H,
                    GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, uploadBuf);
            newUpload = false;
            frameCounter++;
        }

        // Prepass: crop of the RAW into the ORIG texture (native height).
        renderOrig();

        // Aspect-fit viewport (target aspect = frameWidth:frameHeight) with black bars.
        float targetAspect = frameWidth / (float) frameHeight;
        float canvasAspect = cw / (float) ch;
        int vw, vh;
        if (canvasAspect > targetAspect) {
            vh = ch;
            vw = Math.round(ch * targetAspect);
        } else {
            vw = cw;
            vh = Math.round(cw / targetAspect);
        }
        int vx = (cw - vw) / 2;
        int vy = (ch - vh) / 2;

        boolean drew = false;
        if (chain != null) {
            try {
                chain.render(origTex, origW, origH, vx, vy, vw, vh, frameCounter);
                drew = true;
            } catch (Throwable t) {
                System.err.println("[GL] Errore esecuzione shader, passthrough: " + t);
                chain.dispose();
                chain = null;
            }
        }
        if (!drew) {
            drawPassthrough(vx, vy, vw, vh);
        }

        drawOverlay(vx, vy, vw, vh);
        handleSnapshot(cw, ch);
    }

    /** Alpha-blends the ARGB overlay (LEDs/FPS/counter) over the viewport. */
    private void drawOverlay(int vx, int vy, int vw, int vh) {
        boolean present;
        boolean upload = false;
        synchronized (lock) {
            present = overlayPresent;
            if (present && overlaySubmittedId != overlayUploadedId) {
                System.arraycopy(overlayMailbox, 0, overlayScratch, 0, overlayScratch.length);
                overlayUploadedId = overlaySubmittedId;
                upload = true;
            }
        }
        if (!present) return;

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(vx, vy, vw, vh);
        glUseProgram(0);
        glMatrixMode(GL_PROJECTION);
        glLoadIdentity();
        glMatrixMode(GL_MODELVIEW);
        glLoadIdentity();

        glBindTexture(GL_TEXTURE_2D, overlayTex);
        if (upload) {
            overlayBuf.clear();
            overlayBuf.put(overlayScratch, 0, overlayScratch.length);
            overlayBuf.flip();
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, frameWidth, frameHeight,
                    GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, overlayBuf);
        }

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glEnable(GL_TEXTURE_2D);
        glColor4f(1f, 1f, 1f, 1f);
        // Overlay uploaded with row 0 at the top (t=0=top): NDC +1 samples t=0.
        glBegin(GL_QUADS);
        glTexCoord2f(0f, 0f); glVertex2f(-1f, 1f);
        glTexCoord2f(1f, 0f); glVertex2f(1f, 1f);
        glTexCoord2f(1f, 1f); glVertex2f(1f, -1f);
        glTexCoord2f(0f, 1f); glVertex2f(-1f, -1f);
        glEnd();
        glDisable(GL_TEXTURE_2D);
        glDisable(GL_BLEND);
    }

    /** Draws the static drag-and-drop overlay over the whole window (opaque image). */
    private void drawDragOverlay(int cw, int ch) {
        int dw = 0, dh = 0;
        boolean up = false;
        synchronized (lock) {
            if (!dragPresent) return;
            if (dragDirty) {
                dw = dragW;
                dh = dragH;
                System.arraycopy(dragMailbox, 0, dragScratch, 0, dw * dh);
                dragDirty = false;
                up = true;
            }
        }

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, cw, ch);
        glClear(GL_COLOR_BUFFER_BIT);
        glUseProgram(0);
        glMatrixMode(GL_PROJECTION);
        glLoadIdentity();
        glMatrixMode(GL_MODELVIEW);
        glLoadIdentity();

        glBindTexture(GL_TEXTURE_2D, dragTex);
        if (up) {
            if (dragBuf == null || dragBuf.capacity() < dw * dh) {
                dragBuf = BufferUtils.createIntBuffer(dw * dh);
            }
            dragBuf.clear();
            dragBuf.put(dragScratch, 0, dw * dh);
            dragBuf.flip();
            if (dragTexW != dw || dragTexH != dh) {
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, dw, dh, 0,
                        GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, dragBuf);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
                dragTexW = dw;
                dragTexH = dh;
            } else {
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, dw, dh,
                        GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, dragBuf);
            }
        }
        if (dragTexW == 0) return; // nothing uploaded yet

        glDisable(GL_BLEND);
        glEnable(GL_TEXTURE_2D);
        glColor3f(1f, 1f, 1f);
        // ARGB from a BufferedImage (row 0 at the top -> t=0=top): NDC +1 samples t=0.
        glBegin(GL_QUADS);
        glTexCoord2f(0f, 0f); glVertex2f(-1f, 1f);
        glTexCoord2f(1f, 0f); glVertex2f(1f, 1f);
        glTexCoord2f(1f, 1f); glVertex2f(1f, -1f);
        glTexCoord2f(0f, 1f); glVertex2f(-1f, -1f);
        glEnd();
        glDisable(GL_TEXTURE_2D);
    }

    /** If requested, reads the back buffer (before the swap) into a BufferedImage. */
    private void handleSnapshot(int cw, int ch) {
        SnapshotReq req;
        synchronized (lock) {
            req = pendingSnapshot;
            pendingSnapshot = null;
        }
        if (req == null) return;

        IntBuffer buf = BufferUtils.createIntBuffer(cw * ch);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glReadBuffer(GL_BACK);
        glReadPixels(0, 0, cw, ch, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, buf);
        BufferedImage img = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_RGB);
        int[] dst = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        // glReadPixels is bottom-up: flip the rows.
        for (int y = 0; y < ch; y++) {
            buf.position((ch - 1 - y) * cw);
            buf.get(dst, y * cw, cw);
        }
        synchronized (req) {
            req.image = img;
            req.done = true;
            req.notifyAll();
        }
    }

    /**
     * Prepass: draws the crop of the RAW into the ORIG texture (fixed-function). ORIG is
     * stored "upright" (t=1 = top of the image), so the last pass of the chain (or the
     * passthrough) comes out vertically right on the back buffer.
     */
    private void renderOrig() {
        glBindFramebuffer(GL_FRAMEBUFFER, origFbo);
        glViewport(0, 0, origW, origH);
        glClear(GL_COLOR_BUFFER_BIT);

        glUseProgram(0);
        glMatrixMode(GL_PROJECTION);
        glLoadIdentity();
        glMatrixMode(GL_MODELVIEW);
        glLoadIdentity();

        glEnable(GL_TEXTURE_2D);
        glBindTexture(GL_TEXTURE_2D, texId);
        glColor3f(1f, 1f, 1f);

        float u0 = gSrcX0 / (float) RAW_W;
        float u1 = (gSrcX0 + gSrcW) / (float) RAW_W;
        float v0 = gSrcY0 / (float) RAW_H;              // top of the image (line 26)
        float v1 = (gSrcY0 + gSrcH) / (float) RAW_H;    // bottom dell'immagine
        // FBO bottom (NDC -1) -> v1 (image bottom); FBO top (NDC +1) -> v0 (top).
        glBegin(GL_QUADS);
        glTexCoord2f(u0, v1); glVertex2f(-1f, -1f);
        glTexCoord2f(u1, v1); glVertex2f(1f, -1f);
        glTexCoord2f(u1, v0); glVertex2f(1f, 1f);
        glTexCoord2f(u0, v0); glVertex2f(-1f, 1f);
        glEnd();

        glDisable(GL_TEXTURE_2D);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    /** Presentation without a shader: ORIG -> backbuffer (aspect-fit), NEAREST. */
    private void drawPassthrough(int vx, int vy, int vw, int vh) {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(vx, vy, vw, vh);
        glUseProgram(0);
        glMatrixMode(GL_PROJECTION);
        glLoadIdentity();
        glMatrixMode(GL_MODELVIEW);
        glLoadIdentity();
        glEnable(GL_TEXTURE_2D);
        glBindTexture(GL_TEXTURE_2D, origTex);
        glColor3f(1f, 1f, 1f);
        // ORIG upright (t=1 = top): the top vertex (NDC +1) samples t=1.
        glBegin(GL_QUADS);
        glTexCoord2f(0f, 1f); glVertex2f(-1f, 1f);
        glTexCoord2f(1f, 1f); glVertex2f(1f, 1f);
        glTexCoord2f(1f, 0f); glVertex2f(1f, -1f);
        glTexCoord2f(0f, 0f); glVertex2f(-1f, -1f);
        glEnd();
        glDisable(GL_TEXTURE_2D);
    }

    /** (Re)builds the shader chain on the GL thread. Error -> passthrough (no crash). */
    private void rebuildChain() {
        Path p;
        synchronized (lock) {
            p = requestedShader;
            shaderDirty = false;
        }
        if (chain != null) {
            chain.dispose();
            chain = null;
        }
        if (p == null) {
            System.out.println("[GL] Shader: nessuno (passthrough).");
            return;
        }
        try {
            chain = RenderChain.build(p);
            System.out.println("[GL] Shader attivo: " + p);
        } catch (Throwable t) {
            chain = null;
            System.err.println("[GL] Shader '" + p + "' non caricato (passthrough): " + t.getMessage());
        }
    }

    /** Copies the mailbox into {@link #scratch}, duplicating the even column in lores. */
    private void fillScratchLocked() {
        if (pendingLores) {
            for (int y = 0; y < RAW_H; y++) {
                int off = y * RAW_W;
                for (int x = 0; x < RAW_W; x += 2) {
                    int c = mailboxPixels[off + x];
                    scratch[off + x] = c;
                    scratch[off + x + 1] = c;
                }
            }
        } else {
            System.arraycopy(mailboxPixels, 0, scratch, 0, RAW_W * RAW_H);
        }
    }
}
