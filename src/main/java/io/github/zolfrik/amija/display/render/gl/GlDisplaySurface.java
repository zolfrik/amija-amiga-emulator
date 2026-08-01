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
 * Superficie di rendering OpenGL (LWJGL) ospitata dentro la finestra Swing esistente
 * tramite {@code AWTGLCanvas} (lwjgl3-awt). Sostituisce, in modalità
 * {@code renderer=opengl}, il pannello Swing di {@code AmigaDisplay}: riceve il
 * framebuffer RAW 912×312 prodotto da {@code VirtualCRT} e lo presenta sulla GPU.
 *
 * <p><b>Confinamento.</b> È l'unico punto del progetto che dipende da LWJGL: il core
 * di emulazione non ne sa nulla e continua a chiamare {@code AmigaDisplay.renderFrame}.
 *
 * <p><b>Prepass (sempre attivo).</b> Il RAW viene caricato in una texture e ritagliato
 * nella texture sorgente <b>ORIG</b> (ad altezza nativa) con inquadratura identica al
 * path software (ancoraggio alla riga 26, centratura orizzontale sul centro della
 * finestra DIW — vedi {@code AmigaDisplay.composeLogicalFrameCanvas}); i pixel lores
 * hanno la colonna dispari duplicata dalla pari (come {@code AmigaDisplay.copyRow}).
 *
 * <p><b>Presentazione.</b>
 * <ul>
 *   <li><b>Nessuno shader</b>: la ORIG è disegnata sul backbuffer con aspect-fit (barre
 *       nere); il line-doubling è implicito nello scaling GPU.</li>
 *   <li><b>Shader</b>: la ORIG entra in una {@link ShaderChain} RetroArch
 *       ({@code .glslp}); l'ultimo pass disegna sul backbuffer col viewport aspect-fit.
 *       {@link #setShaderPreset(Path)} seleziona il preset a caldo (rebuild sul thread GL).</li>
 * </ul>
 *
 * <p><b>Threading.</b> Tutte le chiamate GL avvengono su un thread dedicato
 * ("amiga-gl-render") che invoca {@link AWTGLCanvas#render()}. Il thread di emulazione
 * consegna i frame via {@link #present} in una mailbox (ultimo frame vince): non si
 * blocca mai, non condivide stato logico con l'emulazione.
 */
public final class GlDisplaySurface {

    /** Larghezza del framebuffer RAW (912). */
    public static final int RAW_W = 912;
    /** Altezza del framebuffer RAW (312). */
    public static final int RAW_H = 312;

    /** Prima riga raster visibile (fine VBlank), come {@code AmigaDisplay.DISPLAY_TOP_LINE}. */
    private static final int DISPLAY_TOP_LINE = 26;

    // Dimensioni logiche del crop (es. 724×576 PAL overscan): definiscono il rettangolo
    // sorgente ritagliato dal RAW e l'aspect ratio target del viewport.
    private final int frameWidth;
    private final int frameHeight;

    private final AWTGLCanvas canvas;
    private volatile boolean running;
    private Thread renderThread;
    private volatile boolean renderErrorLogged;
    // disposeCanvas() di lwjgl3-awt NON è idempotente: alla seconda chiamata la
    // JAWTDrawingSurface è già null e lancia NPE. Il contesto va liberato sul thread GL
    // (nel finally del loop), ma poi il removeNotify del peer (EDT) richiama disposeCanvas:
    // questo flag rende la seconda chiamata un no-op.
    private volatile boolean glContextDisposed;

    // ---- Mailbox (thread di emulazione -> thread GL) ----------------------------------
    private final Object lock = new Object();
    private int[] mailboxPixels = new int[RAW_W * RAW_H];
    private long submittedId;   // incrementato a ogni present()
    private long uploadedId;    // ultimo id caricato in texture dal thread GL
    // Geometria del crop del frame in mailbox (aggiornata in present()).
    private int pendingSrcX0, pendingSrcY0, pendingSrcW, pendingSrcH;
    private boolean pendingLores;

    // Dimensioni della texture sorgente ORIG (crop ad altezza NATIVA, non raddoppiata):
    // larghezza = frameWidth, altezza = frameHeight/2. È l'"InputSize" visto dagli shader.
    private final int origW;
    private final int origH;

    // ---- Stato del solo thread GL -----------------------------------------------------
    private int texId;              // texture RAW 912×312
    private int origFbo, origTex;   // FBO+texture ORIG (prepass)
    private final int[] scratch = new int[RAW_W * RAW_H];
    private final IntBuffer uploadBuf = BufferUtils.createIntBuffer(RAW_W * RAW_H);
    private boolean newUpload;
    private int gSrcX0, gSrcY0, gSrcW, gSrcH;
    private long frameCounter;      // frame presentati (uniform FrameCount degli shader)

    // ---- Shader chain -----------------------------------------------------------------
    private volatile Path requestedShader;   // null = nessuno (passthrough)
    private volatile boolean shaderDirty;
    private volatile ShaderChain chain;      // costruito/usato dal thread GL; setParam dall'EDT

    // ---- Overlay (LED, FPS, contatore) ------------------------------------------------
    // Immagine ARGB (frameWidth×frameHeight) composta con Java2D da AmigaDisplay e
    // consegnata con present(): il thread GL la carica in una texture e la fonde in
    // alpha-blend sopra l'immagine, allineata al viewport.
    private final int[] overlayMailbox;
    private final int[] overlayScratch;
    private final IntBuffer overlayBuf;
    private boolean overlayPresent;          // c'è un overlay da mostrare
    private long overlaySubmittedId, overlayUploadedId;
    private int overlayTex;

    // ---- Snapshot (glReadPixels, per screenshot F12) ----------------------------------
    private SnapshotReq pendingSnapshot;

    // ---- Overlay statico del drag&drop (copre l'intera finestra durante il drag) -------
    // Immagine ARGB opaca (sfondo grigio congelato + quadranti DFx) composta da
    // AmigaDisplay: mentre è presente, il thread GL la disegna a tutta finestra al posto
    // del frame live (come il "freeze" del label nel path software).
    private volatile boolean dragPresent;
    private int[] dragMailbox;               // dimensione dw*dh (allocato su richiesta)
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
        // Profilo compatibility (default sui driver Windows): consente fixed-function per
        // il passthrough e la futura compilazione degli shader GLSL "classici" RetroArch.
        data.swapInterval = 0; // la cadenza la detta l'emulazione (50/60 Hz)
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
                // Idempotente: il thread GL la chiama nel finally del loop, poi il
                // removeNotify del peer (EDT) la richiamerebbe su una surface già liberata.
                if (glContextDisposed) return;
                glContextDisposed = true;
                super.disposeCanvas();
            }
        };
        this.canvas.setFocusable(true);
    }

    /** Il componente AWT (heavyweight) da inserire nella finestra e a cui agganciare l'input. */
    public Component getComponent() {
        return canvas;
    }

    /** Avvia il thread di rendering GL (idempotente). Chiamare dopo che la finestra è visibile. */
    public void start() {
        if (renderThread != null) return;
        running = true;
        renderThread = new Thread(this::renderLoop, "amiga-gl-render");
        renderThread.setDaemon(true);
        renderThread.start();
    }

    /** Ferma il thread GL e rilascia la superficie. */
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
     * Consegna un frame RAW da presentare (chiamato dal thread di emulazione). Copia il
     * buffer nella mailbox e calcola la geometria del crop; ritorna subito. Se il thread
     * GL è indietro, il frame precedente non ancora caricato viene semplicemente
     * sovrascritto (latest-frame-wins).
     */
    public void present(int[] raw, int rawW, int rawH, FrameInfo info) {
        present(raw, rawW, rawH, info, null);
    }

    /**
     * Come {@link #present(int[], int, int, FrameInfo)} ma con un {@code overlayArgb}
     * (immagine ARGB {@code frameWidth×frameHeight}, {@code null} = nessun overlay) da
     * fondere in alpha-blend sopra l'immagine (LED/FPS/contatore).
     */
    public void present(int[] raw, int rawW, int rawH, FrameInfo info, int[] overlayArgb) {
        if (raw == null || raw.length != RAW_W * RAW_H) return;
        synchronized (lock) {
            System.arraycopy(raw, 0, mailboxPixels, 0, RAW_W * RAW_H);
            boolean lores = info != null && !info.hires();
            int visCenterX = (info != null && info.visibleWidth() > 0)
                    ? info.visibleX() + info.visibleWidth() / 2
                    : RAW_W / 2;
            // Stessa aritmetica di AmigaDisplay.composeLogicalFrameCanvas:
            // finestra larga frameWidth centrata sul centro DIW, ancorata alla riga 26.
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
     * Cattura il contenuto corrente del backbuffer (per screenshot F12). Chiamabile da
     * qualsiasi thread: la lettura {@code glReadPixels} è eseguita dal thread GL al frame
     * successivo. Ritorna {@code null} se non arriva nulla entro il timeout.
     */
    public BufferedImage snapshot() {
        SnapshotReq req = new SnapshotReq();
        synchronized (lock) {
            pendingSnapshot = req;
            lock.notifyAll();
        }
        synchronized (req) {
            // Timeout ampio: la prima compilazione di uno shader pesante (es. crt-royale,
            // 12 pass + LUT) occupa il thread GL per qualche secondo; uno screenshot
            // chiesto in quel momento deve attendere, non ripiegare subito sul nero.
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

    /** Richiesta di snapshot: il thread GL riempie {@code image} e segnala {@code done}. */
    private static final class SnapshotReq {
        volatile BufferedImage image;
        volatile boolean done;
    }

    /**
     * Imposta (o rimuove, con {@code argb == null}) l'overlay statico del drag&drop: una
     * immagine ARGB opaca {@code w×h} disegnata a tutta finestra al posto del frame live.
     * Chiamabile dall'EDT; l'upload avviene sul thread GL.
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
     * Seleziona (a caldo) il preset shader RetroArch. La (ri)compilazione avviene sul
     * thread GL al frame successivo; un errore ripiega sul passthrough senza crashare.
     *
     * @param glslp percorso del {@code .glslp}, o {@code null} per "nessuno".
     */
    public void setShaderPreset(Path glslp) {
        synchronized (lock) {
            requestedShader = glslp;
            shaderDirty = true;
            lock.notifyAll();
        }
    }

    /**
     * Aggiorna a caldo un parametro dello shader attivo (dal popup dei parametri).
     * No-op se nessuno shader è attivo o il parametro non esiste. Applicato al frame
     * successivo dal thread GL.
     */
    public void setShaderParam(String name, float value) {
        ShaderChain c = chain;
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
                // Attende un nuovo frame (o un breve timeout per i resize del canvas).
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
            // La catena shader va liberata qui, col contesto ancora vivo su questo thread.
            if (chain != null) {
                try {
                    chain.dispose();
                } catch (Throwable ignore) {
                    // best-effort
                }
                chain = null;
            }
            // Rilascia il contesto GL sullo STESSO thread che lo possiede (initGL/render
            // sono avvenuti qui). Se lo si delega all'EDT — o lo si lascia a removeNotify
            // del peer al dispose della finestra — il teardown nativo JAWT accede a un
            // contesto non gestibile da quel thread e provoca un crash (access violation
            // in awt.dll). Fatto qui, il successivo removeNotify trova il contesto già
            // rilasciato (idempotente).
            try {
                canvas.disposeCanvas();
            } catch (Throwable ignore) {
                // best-effort: contesto mai creato o già distrutto
            }
        }
    }

    /** Crea texture e stato GL (una volta, sul thread GL con contesto attivo). */
    private void createGlObjects() {
        // Texture RAW 912×312, aggiornata con glTexSubImage2D a ogni frame nuovo.
        texId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texId);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, RAW_W, RAW_H, 0,
                GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, 0L);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        // CLAMP_TO_BORDER + bordo nero: le colonne/righe fuori dal RAW (DIW estreme,
        // aspect-fit) restano nere invece di spalmare il bordo.
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_BORDER);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_BORDER);
        glTexParameterfv(GL_TEXTURE_2D, GL_TEXTURE_BORDER_COLOR, new float[]{0f, 0f, 0f, 1f});

        // Texture + FBO ORIG (crop ad altezza nativa): sorgente del passthrough e degli shader.
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

        // Texture overlay (LED/FPS/contatore), ARGB frameWidth×frameHeight.
        overlayTex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, overlayTex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, frameWidth, frameHeight, 0,
                GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, 0L);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        // Texture per l'overlay statico del drag&drop (dimensione impostata al 1° upload).
        dragTex = glGenTextures();

        glDisable(GL_BLEND);
        glDisable(GL_DEPTH_TEST);
        glClearColor(0f, 0f, 0f, 1f);
    }

    private void renderGl() {
        if (shaderDirty) {
            rebuildChain();
        }

        int cw = Math.max(1, canvas.getWidth());
        int ch = Math.max(1, canvas.getHeight());

        // Durante il drag&drop: disegna l'overlay statico a tutta finestra e ignora il
        // frame live (come il "freeze" del label nel path software).
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
            return; // niente ancora da mostrare; swapBuffers dal chiamante
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

        // Prepass: crop del RAW nella texture ORIG (altezza nativa).
        renderOrig();

        // Viewport aspect-fit (aspect target = frameWidth:frameHeight) con barre nere.
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

    /** Fonde l'overlay ARGB (LED/FPS/contatore) in alpha-blend sopra il viewport. */
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
        // Overlay caricato con la riga 0 in alto (t=0=top): NDC +1 campiona t=0.
        glBegin(GL_QUADS);
        glTexCoord2f(0f, 0f); glVertex2f(-1f, 1f);
        glTexCoord2f(1f, 0f); glVertex2f(1f, 1f);
        glTexCoord2f(1f, 1f); glVertex2f(1f, -1f);
        glTexCoord2f(0f, 1f); glVertex2f(-1f, -1f);
        glEnd();
        glDisable(GL_TEXTURE_2D);
        glDisable(GL_BLEND);
    }

    /** Disegna l'overlay statico del drag&drop a tutta finestra (immagine opaca). */
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
        if (dragTexW == 0) return; // niente ancora caricato

        glDisable(GL_BLEND);
        glEnable(GL_TEXTURE_2D);
        glColor3f(1f, 1f, 1f);
        // ARGB da BufferedImage (riga 0 in alto → t=0=top): NDC +1 campiona t=0.
        glBegin(GL_QUADS);
        glTexCoord2f(0f, 0f); glVertex2f(-1f, 1f);
        glTexCoord2f(1f, 0f); glVertex2f(1f, 1f);
        glTexCoord2f(1f, 1f); glVertex2f(1f, -1f);
        glTexCoord2f(0f, 1f); glVertex2f(-1f, -1f);
        glEnd();
        glDisable(GL_TEXTURE_2D);
    }

    /** Se richiesto, legge il backbuffer (prima dello swap) in una BufferedImage. */
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
        // glReadPixels è bottom-up: capovolgi le righe.
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
     * Prepass: disegna il crop del RAW nella texture ORIG (fixed-function). La ORIG è
     * memorizzata "diritta" (t=1 = alto dell'immagine), così l'ultimo pass della catena
     * (o il passthrough) risulta verticalmente corretto sul backbuffer.
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
        float v0 = gSrcY0 / (float) RAW_H;              // top dell'immagine (riga 26)
        float v1 = (gSrcY0 + gSrcH) / (float) RAW_H;    // bottom dell'immagine
        // FBO bottom (NDC -1) -> v1 (bottom immagine); FBO top (NDC +1) -> v0 (top).
        glBegin(GL_QUADS);
        glTexCoord2f(u0, v1); glVertex2f(-1f, -1f);
        glTexCoord2f(u1, v1); glVertex2f(1f, -1f);
        glTexCoord2f(u1, v0); glVertex2f(1f, 1f);
        glTexCoord2f(u0, v0); glVertex2f(-1f, 1f);
        glEnd();

        glDisable(GL_TEXTURE_2D);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    /** Presentazione senza shader: ORIG -> backbuffer (aspect-fit), NEAREST. */
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
        // ORIG diritta (t=1 = top): il vertice in alto (NDC +1) campiona t=1.
        glBegin(GL_QUADS);
        glTexCoord2f(0f, 1f); glVertex2f(-1f, 1f);
        glTexCoord2f(1f, 1f); glVertex2f(1f, 1f);
        glTexCoord2f(1f, 0f); glVertex2f(1f, -1f);
        glTexCoord2f(0f, 0f); glVertex2f(-1f, -1f);
        glEnd();
        glDisable(GL_TEXTURE_2D);
    }

    /** (Ri)costruisce la catena shader sul thread GL. Errore -> passthrough (nessun crash). */
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
            chain = ShaderChain.build(p);
            System.out.println("[GL] Shader attivo: " + p);
        } catch (Throwable t) {
            chain = null;
            System.err.println("[GL] Shader '" + p + "' non caricato (passthrough): " + t.getMessage());
        }
    }

    /** Copia la mailbox in {@link #scratch}, duplicando la colonna pari in lores. */
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
