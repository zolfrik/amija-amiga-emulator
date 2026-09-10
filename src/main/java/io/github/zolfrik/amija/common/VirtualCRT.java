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

package io.github.zolfrik.amija.common;

import io.github.zolfrik.amija.custom.AbstractAgnus;
import io.github.zolfrik.amija.custom.AbstractDenise;
import io.github.zolfrik.amija.display.AmigaDisplay;
import io.github.zolfrik.amija.display.FrameInfo;
import io.github.zolfrik.amija.net.RemotePlayHost;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

/**
 * Rappresenta l'intero raster PAL dell'Amiga, overscan incluso.
 * Nessun crop né elaborazione: ogni pixel raw prodotto da Denise viene scritto
 * direttamente nel framebuffer. La logica di viewport appartiene ad AmigaDisplay.
 */
public final class VirtualCRT {

    /** Larghezza del raster raw PAL (4 * 228 pixel per riga, incluso blanking). */
    public static final int RAW_WIDTH = AbstractDenise.RAW_LINE_PIXELS;  // 912

    /** Numero di righe del raster raw PAL (0–311). */
    public static final int RAW_HEIGHT = 312;

    private final AbstractAgnus agnus;
    private final AbstractDenise denise;
    private final AmigaDisplay display;

    // Doppio buffer per il presenter asincrono: l'emulazione scrive in frameBuffer,
    // il thread di rendering legge renderBuffer. A frame completo i due si scambiano.
    // Così la composizione costosa (line-doubling, allocazione canvas, scaling) NON
    // gira più sul thread del MasterClock (~13% del tempo recuperato all'emulazione).
    private int[] frameBuffer;
    private int[] renderBuffer;

    // Gioco a distanza (docs/remote-play-plan.md §2, §11.1): quando c'è un host attivo,
    // ogni frame completato viene consegnato anche a lui. È l'unico punto in cui nasce
    // un frame intero, quindi è da qui che parte il video. Con la funzione spenta il
    // campo è null e il percorso caldo resta quello di sempre: un confronto con null.
    private volatile RemotePlayHost remotePlayHost;

    private final Object renderLock = new Object();
    private volatile boolean renderRunning;
    private boolean renderBusy;
    private final FrameInfo frameInfo = new FrameInfo();
    private final FrameInfo asyncFrameInfo = new FrameInfo();
    private FrameInfo pendingInfo;
    private Thread renderThread;

    // STEP 11 (PERFORMANCE_REPORT_FINAL): beamX/beamY (overlay di debug del pennello)
    // venivano ricalcolati ad ogni tick (~7M/s) con una divisione intera, ma i getter
    // getBeamX/getBeamY non sono usati né in produzione né nei test. Il calcolo è stato
    // reso lazy (vedi getBeamX/getBeamY): si esegue solo quando effettivamente richiesto.
    private int lastHpos;
    private int lastVpos;
    private int lastFrameCount;

    // ── Bordo-destro deep-HBLANK (vAmiga Beam::pixel, Beam.cpp:244) ──────────────
    // L'ultimo CCK visibile della texture di regressione (col 836-839 = beam cck 0..3 in
    // HBLANK profondo) è mappato da vAmiga sul lato DESTRO della riga PRECEDENTE: col
    // 836-839 della riga V = COLOR00 al beam cck 0 della riga V+1 (border a inizio riga).
    // Il nostro modello per-riga clampa l'HBLANK profondo a sinistra, quindi col 836
    // restava col colore di fine-riga → classe C bordo-destro (stop3/4, CHK4/6, DIVS1,
    // TRAPV5, RTR1, ASL1). Il valore dipende dalla riga SEGUENTE, quindi è campionato a
    // cck 0 di V+1 e memorizzato per-riga; la correzione è applicata SOLO nello screenshot
    // di regressione (saveRegressionScreenshot), NON nel framebuffer live: vAmiga blanka la
    // colonna nel display live e la wrappa solo in RegressionTester::dumpTexture, perciò il
    // display dei giochi resta invariato (niente banda nera del vecchio FIX #15).
    // 4*(227 - HBLANK_MIN) = 4*(227-18) = 836.
    private static final int RIGHT_HBLANK_WRAP_COL = 4 * (227 - 0x12); // 836
    private int wrapPendingRow = -1;                          // riga V, campiona a cck 0 di V+1
    /**
     * Sentinella di {@link #borderCck0Rgb}: <b>nessun campione</b> per questa riga, quindi
     * la colonna di wrap tiene il pixel che abbiamo renderizzato. Non è un colore valido
     * (l'RGB sta in 24 bit), quindi non può collidere con un campione vero.
     */
    private static final int NO_WRAP_SAMPLE = -1;
    private final int[] borderCck0Rgb = new int[RAW_HEIGHT];  // COLOR00 al beam cck 0 di ogni riga
    // 4-bit mask, one bit per sub-pixel of the wrap cell: where the bit is set the
    // line produced content and the wrap does not apply. See saveRegressionScreenshot.
    private final int[] wrapIsPlayfield = new int[RAW_HEIGHT];
    private int frameHiresLines;
    private int frameLoresLines;
    private int frameInterlacedLines;

    /**
     * Riga più alta del framebuffer scritta dal fotogramma corrente, oppure {@code -1}.
     *
     * <p>⛔ Serve perché <b>un fotogramma può essere più corto della finestra</b>: il
     * framebuffer è alto {@link #RAW_HEIGHT} = 312 righe, quanto un frame PAL, ma un frame
     * NTSC ne ha 262 — e su ECS il modo si commuta <b>a caldo</b> scrivendo BEAMCON0
     * ({@code AbstractAgnus.beamNtsc}). Le 50 righe che il pennello non percorre più
     * restavano com'erano, cioè col contenuto di un fotogramma PRECEDENTE, e un
     * framebuffer non ha persistenza: quelle righe non esistono, quindi sono nere.
     * L'oracolo dice la stessa cosa in un altro modo — il suo dump di `ntsc4`/`ntsc5` è
     * alto {@code 262} e tutto ciò che sta sotto è riempito di zeri.
     */
    private int frameMaxRow = -1;

    // Estensione verticale REALE della finestra di display nel frame: prima/ultima
    // scanline che cade dentro la DIW viva (campionata riga per riga). Serve a centrare
    // l'immagine sulla porzione davvero mostrata invece che sul valore della DIW
    // campionato a fine frame: le schermate con split DIW pilotato dal Copper (es. Wings
    // of Fury: pannello strumenti sotto la plancia) cambiano DIWSTRT/DIWSTOP più volte
    // per frame, perciò il valore finale è una banda minuscola e non rappresentativa.
    // -1 = nessuna riga dentro la DIW nel frame.
    private int frameFirstDiwLine = -1;
    private int frameLastDiwLine  = -1;

    // Estensione verticale delle righe EFFETTIVAMENTE DISEGNATE dai bitplane nel
    // frame (output bitplane presente, cioè non bordo verticale). È l'analogo di
    // WinUAE thisframe_first/last_drawn_line (drawing.cpp finish_playfield_line):
    // l'inquadratura verticale si centra su questa estensione, non sulla finestra
    // DIW. Decisivo quando un gioco apre la DIW su tutto l'overscan ma disegna solo
    // una sotto-regione (It Came From The Desert: DIW [5,320) ma box [72,255]):
    // centrare sulla DIW ammasserebbe l'overscan grigio in basso e spingerebbe il
    // box in alto; centrare sull'estensione disegnata lo mantiene inquadrato come
    // su WinUAE. -1 = nessuna riga disegnata nel frame.
    private int frameFirstDrawnLine = -1;
    private int frameLastDrawnLine  = -1;

    // Diagnostica: tempo speso nel rendering (sul thread del MasterClock), una riga/sec.
    private long renderNanos;
    private int  renderFrames;
    private long renderLogStartNs = System.nanoTime();

    public VirtualCRT(AbstractAgnus agnus, AbstractDenise denise, AmigaDisplay display) {
        this.agnus = Objects.requireNonNull(agnus, "agnus");
        this.denise = Objects.requireNonNull(denise, "denise");
        this.display = Objects.requireNonNull(display, "display");
        this.frameBuffer = new int[RAW_WIDTH * RAW_HEIGHT];
        this.renderBuffer = new int[RAW_WIDTH * RAW_HEIGHT];
        reset();
        startRenderThread();
    }

    // Rendering SINCRONO di default (composizione sul thread di emulazione): garantisce
    // il determinismo bit-identico dell'emulazione. La variante ASINCRONA (composizione
    // su un thread separato, ~qualche % di tempo CPU recuperato sul thread di emulazione)
    // introduce un non-determinismo intermittente e sensibile alla tempistica: pur non
    // condividendo stato logico con l'emulazione, la sua esecuzione concorrente perturba
    // l'esecuzione cycle-exact (artefatto JVM/scheduling). Per un emulatore cycle-exact
    // il determinismo vale più dell'headroom (l'emulazione è comunque limitata a 50fps),
    // quindi async è solo opt-in via -Damiga.asyncrender=true.
    private static final boolean ASYNC_RENDER = Boolean.getBoolean("amiga.asyncrender");

    private void startRenderThread() {
        if (!ASYNC_RENDER) return; // rendering sincrono di default
        renderRunning = true;
        renderThread = new Thread(this::renderLoop, "amiga-frame-render");
        renderThread.setDaemon(true);
        renderThread.start();
    }

    /**
     * Attacca (o stacca, con {@code null}) l'host di gioco a distanza: da qui in poi ogni
     * frame completato viene consegnato anche a lui.
     */
    public void setRemotePlayHost(RemotePlayHost host) {
        this.remotePlayHost = host;
    }

    /** Ferma il thread di rendering (opzionale; è comunque daemon). */
    public void stop() {
        synchronized (renderLock) {
            renderRunning = false;
            renderLock.notifyAll();
        }
    }

    private void renderLoop() {
        while (true) {
            int[] buf;
            FrameInfo info;
            synchronized (renderLock) {
                while (renderRunning && !renderBusy) {
                    try {
                        renderLock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (!renderRunning) return;
                buf = renderBuffer;
                info = pendingInfo;
            }

            // Composizione + paint fuori dal lock e fuori dal thread di emulazione.
            long t0 = System.nanoTime();
            display.renderFrame(buf, RAW_WIDTH, RAW_HEIGHT, info);
            renderNanos += System.nanoTime() - t0;
            logRenderStats();

            synchronized (renderLock) {
                renderBusy = false;
                renderLock.notifyAll();
            }
        }
    }

    public void reset() {
        Arrays.fill(frameBuffer, 0);
        frameMaxRow = -1;
        lastHpos = agnus.getHpos();
        lastVpos = agnus.getVpos();
        lastFrameCount = agnus.getFrameCount();
        frameHiresLines = 0;
        frameLoresLines = 0;
        frameInterlacedLines = 0;
        frameFirstDiwLine = -1;
        frameLastDiwLine  = -1;
        frameFirstDrawnLine = -1;
        frameLastDrawnLine  = -1;
    }

    public void tick() {
        int hpos = agnus.getHpos();
        int vpos = agnus.getVpos();
        int frameCount = agnus.getFrameCount();

        if (hpos < lastHpos) {
            completeScanline(lastVpos);
            // La riga appena completata (lastVpos) prende il border-destro dal COLOR00 al
            // beam cck 0 della riga CORRENTE (deep-HBLANK wrap): campionato al tick a cck 0
            // sotto, quando l'eventuale write a cck 0 è già stato applicato al COLOR00 live.
            wrapPendingRow = displayRow(lastVpos);
        }
        if (frameCount != lastFrameCount) {
            // Non attraversare il confine di frame: l'ultima riga userebbe la cck-0 del
            // frame NUOVO. Scarta il campione cross-frame — e ⛔ **azzera anche quello che
            // c'era prima**, altrimenti sull'ultima riga resta il wrap di un fotogramma
            // precedente. In PAL è la riga 311, fuori dal crop, e non si vedeva; dopo una
            // commutazione a NTSC è la 261, che nel crop è la riga 235: quattro pixel
            // accesi dove l'oracolo ha il nero (`Agnus/Misc/ntsc5`).
            if (wrapPendingRow >= 0 && wrapPendingRow < RAW_HEIGHT) {
                borderCck0Rgb[wrapPendingRow] = NO_WRAP_SAMPLE;
            }
            wrapPendingRow = -1;
            // L'input dell'ospite si applica QUI e non dentro presentFrame(): quello può
            // saltare un frame (frame-skip) e i controlli non devono saltare con lui.
            // È il punto di quantizzazione fisso del piano §4.3 — una volta per frame,
            // sul thread di emulazione, sempre nello stesso posto.
            blankRowsBelowFrame();
            RemotePlayHost inputHost = remotePlayHost;
            if (inputHost != null) {
                inputHost.applyPendingInput();
            }
            presentFrame();
        } else if (wrapPendingRow >= 0 && hpos >= 2) {
            // cck 0 della riga corrente completato: il COLOR00 live è il border a inizio
            // riga = colore dell'ultimo CCK visibile (col 836) della riga precedente.
            if (wrapPendingRow < RAW_HEIGHT) {
                borderCck0Rgb[wrapPendingRow] = denise.getLiveColor0Rgb();
                // The wrap is conditional, and the condition is per sub-pixel: COLOR00
                // applies only where the beam is in the border at that instant, while
                // where the line produced content the right pixel is the rendered one.
                // The four sub-pixels of the cell are not alike - a row can carry border
                // on the first two and playfield on the last two - so a single flag for
                // the whole cell would turn all four on or off together. See the comment
                // in saveRegressionScreenshot.
                int mask = 0;
                for (int k = 0; k < 4; k++) {
                    if (denise.isPlayfieldAt(RIGHT_HBLANK_WRAP_COL + k)) mask |= 1 << k;
                }
                wrapIsPlayfield[wrapPendingRow] = mask;
            }
            wrapPendingRow = -1;
        }

        lastHpos = hpos;
        lastVpos = vpos;
        lastFrameCount = frameCount;
    }


    private static final boolean DEBUG_RENDER_LOG = false; // diagnostica [RENDER] (off di default)

    private void logRenderStats() {
        if (!DEBUG_RENDER_LOG) return;
        renderFrames++;
        long now = System.nanoTime();
        long elapsed = now - renderLogStartNs;
        if (elapsed >= 1_000_000_000L) {
            double renderMsPerSec = renderNanos / 1_000_000.0;
            double pct = 100.0 * renderNanos / elapsed;
            double msPerFrame = renderFrames > 0 ? renderMsPerSec / renderFrames : 0;
            System.out.printf("[RENDER] %.1f ms/s (%.1f%% del tempo) frames=%d (%.2f ms/frame)%n",
                    renderMsPerSec, pct, renderFrames, msPerFrame);
            renderNanos = 0;
            renderFrames = 0;
            renderLogStartNs = now;
        }
    }

    /**
     * Azzera le righe del framebuffer che il fotogramma appena chiuso non ha percorso.
     *
     * <p>⚠️ In PAL non fa <b>niente</b>: {@code maxVpos} è 312 = {@link #RAW_HEIGHT} e
     * l'ultima riga scritta è sempre l'ultima del buffer. Vale solo dopo una commutazione
     * a NTSC, dove il fotogramma è di 262 righe. Vedi {@link #frameMaxRow}.
     */
    private void blankRowsBelowFrame() {
        if (frameMaxRow >= 0 && frameMaxRow + 1 < RAW_HEIGHT) {
            final int from = frameMaxRow + 1;
            Arrays.fill(frameBuffer, from * RAW_WIDTH, RAW_HEIGHT * RAW_WIDTH, 0);
            // La colonna di wrap del bordo destro è per-riga e si porta dietro lo stesso
            // residuo: senza questo, delle righe azzerate restano accesi i 4 px di col 836.
            java.util.Arrays.fill(borderCck0Rgb, from, RAW_HEIGHT, 0);
            java.util.Arrays.fill(wrapIsPlayfield, from, RAW_HEIGHT, 0);
        }
        frameMaxRow = -1;
    }

    private void completeScanline(int vpos) {
        denise.hsyncHandler(vpos);

        final int row = displayRow(vpos);
        if (row < 0 || row >= RAW_HEIGHT) {
            return;
        }

        // RAW_WIDTH == Denise.HPIXELS == rgbLine.length, quindi la copia in blocco
        // è esattamente equivalente al loop per-pixel su getRawPixelColor(x), ma
        // evita ~912 chiamate al getter (con bound-check) per ogni scanline.
        System.arraycopy(denise.getRgbLine(), 0, frameBuffer, row * RAW_WIDTH, RAW_WIDTH);
        if (row > frameMaxRow) {
            frameMaxRow = row;
        }

        if (!agnus.inVBlankArea(vpos) && vpos >= agnus.getDiwVStart() && vpos < agnus.getDiwVStop()) {
            int lineBplcon0 = denise.getLastRenderedBplcon0();
            if ((lineBplcon0 & 0x8000) != 0) frameHiresLines++;
            else                             frameLoresLines++;
            if ((lineBplcon0 & 0x0004) != 0) frameInterlacedLines++;

            // Estensione verticale REALE della finestra di display nel frame: union di
            // tutte le righe che cadono dentro la DIW viva (campionata riga per riga,
            // non solo a fine frame). Indipendente dal valore finale della DIW, quindi
            // corretto anche con split DIW pilotato dal Copper (Wings of Fury): senza
            // questo, buildFrameInfo userebbe l'ultima banda residua e centrerebbe male.
            // Il test di appartenenza alla DIW include anche le righe di bordo verticale
            // dentro la finestra (lo sfondo che riempie lo schermo, es. schermo Workbench
            // con DIW aperta 29..312 ma bitplane attivi solo al centro), così l'immagine
            // resta inquadrata come sull'hardware invece di seguire solo il contenuto.
            if (frameFirstDiwLine < 0) frameFirstDiwLine = row;
            frameLastDiwLine = row;

            // Estensione delle righe con output bitplane (non bordo verticale):
            // l'inquadratura verticale si centra su questa (come WinUAE), non
            // sulla DIW. denise.hsyncHandler(vpos) è già stato chiamato sopra, quindi
            // isLastLineVBorder() riflette la riga vpos appena renderizzata.
            if (!denise.isLastLineVBorder()) {
                if (frameFirstDrawnLine < 0) frameFirstDrawnLine = row;
                frameLastDrawnLine = row;
            }
        }
    }

    /**
     * Riga del framebuffer su cui finisce la scanline {@code vpos}.
     *
     * <p>⛔ <b>Non è {@code vpos}</b>: il quadro è agganciato al <b>vsync</b>, non allo wrap
     * del contatore, e in PAL il vsync del fotogramma corto (LOF=0) comincia una riga
     * prima. Il modello sta in {@code AbstractAgnus.getDisplayRowShift()}, portato da
     * {@code custom.cpp} ({@code vsync_mark()} azzera {@code linear_vpos}, che è la riga
     * del framebuffer). Sul fotogramma lungo — quello su cui le costanti di crop sono
     * tarate e su cui girano quasi tutti i riferimenti — lo scorrimento è 0 e questa
     * funzione è l'identità.
     */
    private int displayRow(int vpos) {
        // ⛔ In INTERLACE lo scorrimento non si applica, e non è un'eccezione alla regola:
        // è la stessa grandezza espressa a un'altra scala, e va applicata UNA VOLTA SOLA.
        // Lo scarto fra fotogramma lungo e corto è mezza riga — è proprio ciò che separa i
        // due semiquadri — e in interlace lo porta già l'intreccio di AmigaDisplay, che
        // scrive la riga grezza srcY alla riga di schermo `srcY*2 + (campo pari ? 0 : 1)`.
        // Sommarci anche una riga GREZZA intera significa spostare il semiquadro dispari
        // di TRE righe di schermo invece di una: i due campi non si intrecciano più e
        // l'immagine si sfoca. Misurato su uno schermo interlacciato sintetico: i due
        // campi finivano alle righe 44 e 45 del framebuffer invece che entrambi alla 44.
        //
        // Fuori dall'interlace l'intreccio non esiste (le righe si raddoppiano), quindi
        // è lo scorrimento a portare l'offset: è il caso su cui l'oracolo è stato misurato
        // (lof1/lof2/vhpos4/vhpos5, tutto il banco vAmigaTS è non interlacciato).
        if (denise.isInterlaced()) {
            return vpos;
        }
        return vpos + agnus.getDisplayRowShift();
    }

    // RETE DI SICUREZZA: il frame-skip (lastCompletedFrameHadLateBitplaneDma) serve
    // solo a NASCONDERE un singolo frame transitorio (es. mano insert-disk del KS).
    // Un frame "parziale" è per definizione un evento isolato; un gioco che marca
    // OGNI frame (es. uno split-screen mal classificato) NON deve mai congelare il
    // display. Perciò non saltiamo MAI più di 1 frame consecutivo: al massimo si
    // nasconde un singolo frame, mai una sequenza. Questo rende impossibile il blocco
    // del display da frame-skip a prescindere dall'euristica in Agnus.
    private int consecutiveSkips = 0;

    private void presentFrame() {
        if (agnus.lastCompletedFrameHadLateBitplaneDma() && consecutiveSkips < 1) {
            consecutiveSkips++;
            resetFrameModeCounters();
            return;
        }
        consecutiveSkips = 0;
        buildFrameInfo(frameInfo);
        // Prima di presentare: il ramo asincrono scambia frameBuffer con renderBuffer, e
        // dopo lo scambio il frame appena completato non è più in frameBuffer. Il costo
        // ammesso qui è una copia (§11.2); submitFrame non codifica e non spedisce nulla,
        // e se il thread di rete è ancora occupato salta il frame invece di accodarlo.
        RemotePlayHost host = remotePlayHost;
        if (host != null) {
            host.submitFrame(frameBuffer, frameInfo);
        }
        if (!ASYNC_RENDER) {
            // Default: composizione sincrona sul thread di emulazione (deterministica).
            display.renderFrame(frameBuffer, RAW_WIDTH, RAW_HEIGHT, frameInfo);
            resetFrameModeCounters();
            return;
        }
        synchronized (renderLock) {
            if (!renderBusy) {
                // Scambia il frame appena completato col buffer del renderer e
                // sveglialo. L'emulazione prosegue subito sull'altro buffer.
                int[] tmp = renderBuffer;
                renderBuffer = frameBuffer;
                frameBuffer = tmp;
                asyncFrameInfo.copyFrom(frameInfo);
                pendingInfo = asyncFrameInfo;
                renderBusy = true;
                renderLock.notifyAll();
            }
            // Se il renderer è ancora occupato col frame precedente, scartiamo
            // questo (frame skip): l'emulazione non si blocca mai.
        }
        resetFrameModeCounters();
    }

    private void buildFrameInfo(FrameInfo out) {
        int bplcon0 = denise.getBplcon0();
        boolean hires      = chooseFrameHires(frameHiresLines, frameLoresLines, bplcon0);
        boolean interlaced = frameInterlacedLines > 0 ? frameInterlacedLines >= (frameHiresLines + frameLoresLines + 1) / 2
                                                      : (bplcon0 & 0x0004) != 0;
        // field 0 → righe pari (0,2,4,…), field 1 → righe dispari (1,3,5,…)
        boolean evenField  = agnus.getLastCompletedFrameField() == 0;

        // Finestra orizzontale: deriva dalla DIW, ma CLAMPATA alla finestra video PAL
        // standard. Su hardware reale il monitor mostra una regione raster FISSA; la DIW
        // si limita a blankare i bitplane al suo interno. AmigaDisplay però centra il
        // crop sul centro dell'area visibile: con una DIW estrema/asimmetrica (es. il 2°
        // cracktro di Persian Gulf Inferno usa DIWSTRT con H=$05, hstart pixel negativo)
        // il centro DIW si sposta e trascina il contenuto (posizionato dal DDF) fuori
        // finestra a destra. Il clamp riporta l'inquadratura a quella standard: le
        // schermate normali (DIW dentro i limiti) restano IDENTICHE, mentre quelle con
        // DIW oltre i bordi vengono inquadrate come sull'hardware.
        // STD_LEFT/RIGHT = pixel rgbLine della DIW PAL canonica (DIWSTRT H=$81,
        // DIWSTOP H=$1C1): (H - 2*HBLANK_MIN)*2 con HBLANK_MIN=$12 → 186 e 826.
        final int STD_LEFT = 186, STD_RIGHT = 826;
        int visStart = Math.max(denise.getDiwHStartPixel(), STD_LEFT);
        int visStop  = Math.min(denise.getDiwHStopPixel(),  STD_RIGHT);
        int visibleX      = visStart;
        int visibleWidth  = Math.max(0, visStop - visStart);

        // Verticale: centra sull'estensione delle righe EFFETTIVAMENTE DISEGNATE dai
        // bitplane nel frame (output bitplane presente), come WinUAE
        // (thisframe_first/last_drawn_line). NON sulla finestra DIW: un gioco può
        // aprire la DIW su tutto l'overscan (It Came From The Desert: DIW [5,320)) ma
        // disegnare solo una sotto-regione (box [72,255]); centrare sulla DIW
        // ammasserebbe l'overscan grigio in basso e spingerebbe il contenuto in alto.
        // Centrare sull'estensione disegnata mantiene il contenuto inquadrato come su
        // WinUAE. Fallback: union delle righe dentro la DIW (gestisce gli split DIW
        // pilotati dal Copper, es. Wings of Fury), poi la DIW campionata a fine frame.
        int visibleY, visibleHeight;
        if (frameFirstDrawnLine >= 0) {
            visibleY      = frameFirstDrawnLine;
            visibleHeight = frameLastDrawnLine - frameFirstDrawnLine + 1;
        } else if (frameFirstDiwLine >= 0) {
            visibleY      = frameFirstDiwLine;
            visibleHeight = frameLastDiwLine - frameFirstDiwLine + 1;
        } else {
            visibleY      = agnus.getDiwVStart();
            visibleHeight = agnus.getDiwVStop() - visibleY;
        }

        out.set(hires, interlaced, evenField, visibleX, visibleY, visibleWidth, visibleHeight);
        out.setFrameCount(agnus.getFrameCount());
    }

    static boolean chooseFrameHires(int hiresLines, int loresLines, int currentBplcon0) {
        if (hiresLines > 0) {
            return true;
        }
        if (loresLines > 0) {
            return false;
        }
        return (currentBplcon0 & 0x8000) != 0;
    }

    private void resetFrameModeCounters() {
        frameHiresLines = 0;
        frameLoresLines = 0;
        frameInterlacedLines = 0;
        frameFirstDiwLine = -1;
        frameLastDiwLine  = -1;
        frameFirstDrawnLine = -1;
        frameLastDrawnLine  = -1;
    }

    public int[] getFrameBuffer() {
        return frameBuffer;
    }

    /**
     * Salva il framebuffer raw PAL prodotto da Denise, senza crop, scaling, line doubling
     * o overlay applicati da AmigaDisplay.
     */
    public void saveRawScreenshot(File file) throws IOException {
        Objects.requireNonNull(file, "file");
        File dir = file.getParentFile();
        if (dir != null && !dir.exists()) {
            dir.mkdirs();
        }

        int[] snapshot = Arrays.copyOf(frameBuffer, frameBuffer.length);
        BufferedImage raw = new BufferedImage(RAW_WIDTH, RAW_HEIGHT, BufferedImage.TYPE_INT_RGB);
        int[] dst = ((DataBufferInt) raw.getRaster().getDataBuffer()).getData();
        System.arraycopy(snapshot, 0, dst, 0, snapshot.length);
        ImageIO.write(raw, "png", file);
    }

    // =========================================================================
    // Crop "regression" allineato a vAmiga (suite vAmigaTS)
    // =========================================================================
    //
    // vAmiga (RegressionTester::dumpTexture) ritaglia dalla texture stabile una
    // finestra fissa e la confronta con l'immagine di riferimento .tiff:
    //
    //   Texel *ptr = stablePtr - 4*HBLANK_MIN;          // HBLANK_MIN = 0x12
    //   for (y = Y1; y < Y2; y++)
    //     for (x = X1; x < X2; x++)
    //       pixel = ptr[y*HPIXELS + x];                 // HPIXELS = 912
    //
    //   X1 = 4*0x31 = 196   X2 = HPIXELS = 912          → larghezza 716
    //   Y1 = VBLANK_MAX+1 = 26   Y2 = VPIXELS-2 = 311   → altezza   285
    //
    // La colonna di texture effettivamente letta è (x - 4*HBLANK_MIN) = x - 72,
    // cioè 4*hpos - 72. Il NOSTRO line-buffer (AbstractDenise) ha esattamente lo
    // stesso indice: rgbLine[i] corrisponde a hpos = HBLANK_MIN + i/4, quindi
    // i = 4*hpos - 4*HBLANK_MIN = 4*hpos - 72. Le due indicizzazioni coincidono,
    // perciò il ritaglio è diretto sul frameBuffer raw (912x312):
    //
    //   colonne [124, 840)  (X1 - 4*HBLANK_MIN = 124, larghezza 716)
    //   righe   [26, 311)   (Y1 = 26, altezza 285)
    //
    // La riga (vpos) coincide tra i due emulatori (entrambi contano da 0 in alto).

    /** Colonna iniziale del crop regression: X1(196) - 4*HBLANK_MIN(72). */
    public static final int REGRESSION_CROP_X = 124;
    /** Riga iniziale del crop regression: VBLANK_MAX(25) + 1. */
    public static final int REGRESSION_CROP_Y = 26;
    /** Larghezza del crop regression: X2(912) - X1(196). */
    public static final int REGRESSION_CROP_W = 716;
    /** Altezza del crop regression: (VPIXELS-2 = 311) - Y1(26). */
    public static final int REGRESSION_CROP_H = 285;

    /**
     * Salva uno screenshot ritagliato esattamente come il dump di regressione di
     * vAmiga (716x285), così da poterlo confrontare pixel-per-pixel con le immagini
     * di riferimento della suite vAmigaTS. A differenza di {@link #saveRawScreenshot},
     * non include il blanking né l'overscan esterno alla finestra di confronto.
     */
    public void saveRegressionScreenshot(File file) throws IOException {
        Objects.requireNonNull(file, "file");
        File dir = file.getParentFile();
        if (dir != null && !dir.exists()) {
            dir.mkdirs();
        }

        int[] snapshot = Arrays.copyOf(frameBuffer, frameBuffer.length);
        BufferedImage cropped = new BufferedImage(
                REGRESSION_CROP_W, REGRESSION_CROP_H, BufferedImage.TYPE_INT_RGB);
        int[] dst = ((DataBufferInt) cropped.getRaster().getDataBuffer()).getData();
        int wrapCropCol = RIGHT_HBLANK_WRAP_COL - REGRESSION_CROP_X; // 836-124 = 712
        for (int row = 0; row < REGRESSION_CROP_H; row++) {
            int srcY = REGRESSION_CROP_Y + row;
            System.arraycopy(snapshot, srcY * RAW_WIDTH + REGRESSION_CROP_X,
                    dst, row * REGRESSION_CROP_W, REGRESSION_CROP_W);
            // Bordo-destro deep-HBLANK wrap (vAmiga dumpTexture): l'ultimo CCK visibile
            // (col 836-839 = beam cck 0) mostra il COLOR00 al confine di riga, campionato
            // a cck 0 della riga successiva. Applicato SOLO qui (non nel framebuffer live).
            //
            // ⛔ Ed è CONDIZIONALE, dopo tre misure sullo stesso oggetto:
            //  1. toglierlo del tutto (2026-09-02) affondava TimingTest, BbusyTest,
            //     memtim1 e MemoryRom ⇒ il wrap è reale, non un residuo del porto vAmiga;
            //  2. prendere `rgbLine[0]` della riga V+1 invece di COLOR00 era peggio di
            //     entrambe (oldDMACON 20/20, memtim1 da 0 a 456 px): la nostra riga
            //     comincia a cck 18, non a cck 0, quindi quel pixel non è l'istante giusto;
            //  3. toglierlo DOPO il rifacimento per-pixel di Denise (2026-09-03) portava
            //     `BPLCON1/overscan` da 384 px (0,19%) a 27÷39 px (0,01%) ma rompeva di
            //     nuovo memtim1 (2/0 → 0/2) e MemoryRom (3/1 → 2/2).
            // ⇒ le due popolazioni sono separate e misurate: dove a cck 0 della riga V+1 il
            // pennello è nel BORDO vale COLOR00 (memtim*, MemoryRom), dove invece la riga
            // aveva PLAYFIELD fin lì vale il pixel che abbiamo renderizzato (overscan) —
            // che prima di quel rifacimento non era ancora giusto, ed è per questo che la
            // condizione non si poteva scrivere allora.
            //
            // The "content" test has two parts, and both are needed:
            //  4. an open DIW window alone is not content. Where the window is open but
            //     the fetch has already ended, colorizeNormal uses `mBuffer & 0x3F` = 0,
            //     so the pixel is palette[0] - border, but drawn with the COLOR00 of the
            //     end of the line instead of the value live at cck 0 of the next line,
            //     which is the instant that cell shows. The test is therefore
            //     `bBuffer == 0xFF && (mBuffer & 0x3F) != 0`
            //     (AbstractDenise.isPlayfieldAt);
            //  5. one flag for the whole cell is not enough. The four sub-pixels are not
            //     alike: a row can carry border on 712-713 and playfield on 714-715, so
            //     `wrapIsPlayfield` is a 4-bit mask applied per sub-pixel.
            if (wrapCropCol >= 0 && wrapCropCol + 4 <= REGRESSION_CROP_W
                    && srcY >= 0 && srcY < RAW_HEIGHT
                    && borderCck0Rgb[srcY] != NO_WRAP_SAMPLE) {
                int rgb  = borderCck0Rgb[srcY];
                int mask = wrapIsPlayfield[srcY];
                int di   = row * REGRESSION_CROP_W + wrapCropCol;
                // Sub-pixel by sub-pixel: the cell is not homogeneous. See tick().
                for (int k = 0; k < 4; k++) {
                    if ((mask & (1 << k)) == 0) dst[di + k] = rgb;
                }
            }
        }
        ImageIO.write(cropped, "png", file);
    }

    // STEP 11: calcolo lazy della posizione del pennello (solo su richiesta, p.es.
    // overlay di debug), invece che ad ogni tick. Stessa formula di prima.
    public int getBeamX() {
        return Math.min(RAW_WIDTH - 1,
                Math.max(0, agnus.getHpos() * RAW_WIDTH / Math.max(1, agnus.getMaxHpos())));
    }

    public int getBeamY() {
        return Math.min(RAW_HEIGHT - 1, Math.max(0, agnus.getVpos()));
    }

    public int getPixel(int x, int y) {
        if (x < 0 || x >= RAW_WIDTH || y < 0 || y >= RAW_HEIGHT) {
            return 0;
        }
        return frameBuffer[y * RAW_WIDTH + x];
    }
}

