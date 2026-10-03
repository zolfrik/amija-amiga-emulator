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
 * The whole PAL raster of the Amiga, overscan included. No crop and no processing: every
 * raw pixel Denise produces is written straight into the framebuffer. The viewport logic
 * belongs to AmigaDisplay.
 */
public final class VirtualCRT {

    /** Width of the PAL raw raster (4 * 228 pixels per line, blanking included). */
    public static final int RAW_WIDTH = AbstractDenise.RAW_LINE_PIXELS;  // 912

    /** Number of lines of the raw PAL raster (0-311). */
    public static final int RAW_HEIGHT = 312;

    private final AbstractAgnus agnus;
    private final AbstractDenise denise;
    private final AmigaDisplay display;

    // Double buffer for the asynchronous presenter: the emulation writes frameBuffer, the
    // rendering thread reads renderBuffer. When a frame is complete the two are swapped, so
    // the expensive composition (line doubling, canvas allocation, scaling) does NOT run on
    // the MasterClock thread.
    private int[] frameBuffer;
    private int[] renderBuffer;

    // Remote play (docs/remote-play-plan.md sections 2 and 11.1): when a host is active,
    // every completed frame is also handed to it. This is the only place where a whole frame
    // is born, so the video starts here. With the feature off the field is null and the hot
    // path is unchanged: one comparison with null.
    private volatile RemotePlayHost remotePlayHost;

    private final Object renderLock = new Object();
    private volatile boolean renderRunning;
    private boolean renderBusy;
    private final FrameInfo frameInfo = new FrameInfo();
    private final FrameInfo asyncFrameInfo = new FrameInfo();
    private FrameInfo pendingInfo;
    private Thread renderThread;

    // beamX/beamY (debug overlay of the beam) are computed lazily (see getBeamX/getBeamY),
    // only when requested, instead of with an integer division at every tick (~7M/s).
    private int lastHpos;
    private int lastVpos;
    private int lastFrameCount;

    // -- Deep-HBLANK right border -----------------------------------------------------
    // The last visible CCK of the regression texture (cols 836-839 = beam CCK 0..3 in deep
    // HBLANK) belongs to the RIGHT side of the PREVIOUS line: cols 836-839 of line V =
    // COLOR00 at beam CCK 0 of line V+1 (the border at the start of the line). The per-line
    // model clamps deep HBLANK on the left, so col 836 would keep the end-of-line colour.
    // The value depends on the NEXT line, so it is sampled at CCK 0 of V+1 and stored per
    // line; the correction is applied ONLY to the regression screenshot
    // (saveRegressionScreenshot), NOT to the live framebuffer, where that column is
    // blanked, so the games' display is unchanged.
    // 4*(227 - HBLANK_MIN) = 4*(227-18) = 836.
    private static final int RIGHT_HBLANK_WRAP_COL = 4 * (227 - 0x12); // 836
    private int wrapPendingRow = -1;                          // line V, sampled at cck 0 of V+1
    /**
     * Sentinel of {@link #borderCck0Rgb}: <b>no sample</b> for this line, so the wrap column
     * keeps the pixel we rendered. It is not a valid colour (RGB fits in 24 bits), so it
     * cannot collide with a real sample.
     */
    private static final int NO_WRAP_SAMPLE = -1;
    private final int[] borderCck0Rgb = new int[RAW_HEIGHT];  // COLOR00 at beam cck 0 of each line
    // 4-bit mask, one bit per sub-pixel of the wrap cell: where the bit is set the
    // line produced content and the wrap does not apply. See saveRegressionScreenshot.
    private final int[] wrapIsPlayfield = new int[RAW_HEIGHT];
    private int frameHiresLines;
    private int frameLoresLines;
    private int frameInterlacedLines;

    /**
     * Highest framebuffer row written by the current frame, or {@code -1}.
     *
     * <p>Needed because <b>a frame can be shorter than the window</b>: the framebuffer is
     * {@link #RAW_HEIGHT} = 312 lines high, as a PAL frame, but an NTSC frame has 262, and
     * on ECS the mode switches <b>live</b> through BEAMCON0 ({@code AbstractAgnus.beamNtsc}).
     * The 50 lines the beam no longer covers would keep the content of a PREVIOUS frame, and
     * a framebuffer has no persistence: those lines do not exist, so they are black. The
     * reference dump of `ntsc4`/`ntsc5` is {@code 262} lines high and everything below it is
     * zero.
     */
    private int frameMaxRow = -1;

    // REAL vertical extent of the display window in the frame: first/last scanline inside
    // the live DIW (sampled line by line). Used to centre the image on the part really
    // shown rather than on the DIW sampled at the end of the frame: screens with a
    // Copper-driven DIW split (e.g. Wings of Fury: instrument panel below the cockpit)
    // change DIWSTRT/DIWSTOP several times per frame, so the final value is a tiny,
    // unrepresentative band.
    // -1 = no line inside the DIW in the frame.
    private int frameFirstDiwLine = -1;
    private int frameLastDiwLine  = -1;

    // Vertical extent of the lines ACTUALLY DRAWN by the bitplanes in the frame (bitplane
    // output present, i.e. not vertical border). The vertical framing is centred on this
    // extent, not on the DIW. Decisive when a game opens the DIW over the whole overscan but
    // draws only a sub-region (It Came From The Desert: DIW [5,320) but box [72,255]):
    // centring on the DIW would pile the grey overscan at the bottom and push the box up;
    // centring on the drawn extent keeps it framed. -1 = no line drawn in the frame.
    private int frameFirstDrawnLine = -1;
    private int frameLastDrawnLine  = -1;

    // Diagnostics: time spent rendering (on the MasterClock thread), one line per second.
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

    // SYNCHRONOUS rendering by default (composition on the emulation thread): it keeps the
    // emulation bit-identically deterministic. The ASYNCHRONOUS variant (composition on a
    // separate thread, a few % of CPU time recovered on the emulation thread) introduces an
    // intermittent, timing-sensitive non-determinism: although it shares no logical state
    // with the emulation, running concurrently perturbs the cycle-exact execution (a
    // JVM/scheduling artefact). For a cycle-exact emulator determinism is worth more than
    // the headroom (the emulation is capped at 50 fps anyway), so async is opt-in only,
    // through -Damiga.asyncrender=true.
    private static final boolean ASYNC_RENDER = Boolean.getBoolean("amiga.asyncrender");

    private void startRenderThread() {
        if (!ASYNC_RENDER) return; // synchronous rendering by default
        renderRunning = true;
        renderThread = new Thread(this::renderLoop, "amiga-frame-render");
        renderThread.setDaemon(true);
        renderThread.start();
    }

    /**
     * Attaches (or detaches, with {@code null}) the remote play host: from now on every
     * completed frame is also handed to it.
     */
    public void setRemotePlayHost(RemotePlayHost host) {
        this.remotePlayHost = host;
    }

    /** Stops the rendering thread (optional; it is a daemon anyway). */
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

            // Composition + paint outside the lock and outside the emulation thread.
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
            // The line just completed (lastVpos) takes its right border from the COLOR00 at
            // beam CCK 0 of the CURRENT line (deep-HBLANK wrap): sampled at the CCK 0 tick
            // below, when any write at CCK 0 has already reached the live COLOR00.
            wrapPendingRow = displayRow(lastVpos);
        }
        if (frameCount != lastFrameCount) {
            // Do not cross the frame boundary: the last line would use CCK 0 of the NEW
            // frame. The cross-frame sample is dropped, and the previous one is cleared too,
            // or the last line would keep the wrap of an earlier frame. In PAL that is line
            // 311, outside the crop; after a switch to NTSC it is 261, which in the crop is
            // line 235: four pixels lit where the reference is black (`Agnus/Misc/ntsc5`).
            if (wrapPendingRow >= 0 && wrapPendingRow < RAW_HEIGHT) {
                borderCck0Rgb[wrapPendingRow] = NO_WRAP_SAMPLE;
            }
            wrapPendingRow = -1;
            // The guest's input is applied HERE and not inside presentFrame(): that one can
            // skip a frame (frame skip) and the controls must not skip with it. It is the
            // fixed quantisation point of plan section 4.3: once per frame, on the emulation
            // thread, always in the same place.
            blankRowsBelowFrame();
            RemotePlayHost inputHost = remotePlayHost;
            if (inputHost != null) {
                inputHost.applyPendingInput();
            }
            presentFrame();
        } else if (wrapPendingRow >= 0 && hpos >= 2) {
            // CCK 0 of the current line completed: the live COLOR00 is the border at the start
            // of the line = colour of the last visible CCK (col 836) of the previous line.
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


    private static final boolean DEBUG_RENDER_LOG = false; // [RENDER] diagnostics (off by default)

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
     * Clears the framebuffer rows the frame just closed did not cover.
     *
     * <p>In PAL it does <b>nothing</b>: {@code maxVpos} is 312 = {@link #RAW_HEIGHT} and the
     * last row written is always the buffer's last. It matters only after a switch to NTSC,
     * where the frame has 262 lines. See {@link #frameMaxRow}.
     */
    private void blankRowsBelowFrame() {
        if (frameMaxRow >= 0 && frameMaxRow + 1 < RAW_HEIGHT) {
            final int from = frameMaxRow + 1;
            Arrays.fill(frameBuffer, from * RAW_WIDTH, RAW_HEIGHT * RAW_WIDTH, 0);
            // The right-border wrap column is per line and carries the same residue: without
            // this, cleared rows would keep the 4 px of col 836 lit.
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

        // RAW_WIDTH == Denise.HPIXELS == rgbLine.length, so the block copy is exactly the
        // per-pixel loop over getRawPixelColor(x), without ~912 getter calls (with bounds
        // checks) per scanline.
        System.arraycopy(denise.getRgbLine(), 0, frameBuffer, row * RAW_WIDTH, RAW_WIDTH);
        if (row > frameMaxRow) {
            frameMaxRow = row;
        }

        if (!agnus.inVBlankArea(vpos) && vpos >= agnus.getDiwVStart() && vpos < agnus.getDiwVStop()) {
            int lineBplcon0 = denise.getLastRenderedBplcon0();
            if ((lineBplcon0 & 0x8000) != 0) frameHiresLines++;
            else                             frameLoresLines++;
            if ((lineBplcon0 & 0x0004) != 0) frameInterlacedLines++;

            // REAL vertical extent of the display window in the frame: the union of every
            // line inside the live DIW (sampled line by line, not only at the end of the
            // frame). Independent of the final DIW value, so right even with a Copper-driven
            // DIW split (Wings of Fury): without it, buildFrameInfo would use the last
            // remaining band and centre badly. The DIW membership test includes the vertical
            // border lines inside the window too (the background that fills the screen, e.g.
            // a Workbench screen with the DIW open 29..312 but bitplanes active only in the
            // middle), so the image stays framed as on the hardware instead of following the
            // content alone.
            if (frameFirstDiwLine < 0) frameFirstDiwLine = row;
            frameLastDiwLine = row;

            // Extent of the lines with bitplane output (not vertical border): the vertical
            // framing is centred on this, not on the DIW. denise.hsyncHandler(vpos) has
            // already been called above, so isLastLineVBorder() reflects the line vpos just
            // rendered.
            if (!denise.isLastLineVBorder()) {
                if (frameFirstDrawnLine < 0) frameFirstDrawnLine = row;
                frameLastDrawnLine = row;
            }
        }
    }

    /**
     * Framebuffer row on which scanline {@code vpos} ends up.
     *
     * <p><b>It is not {@code vpos}</b>: the picture is locked to the <b>vsync</b>, not to the
     * counter's wrap, and in PAL the vsync of the short frame (LOF=0) starts one line
     * earlier. The model is in {@code AbstractAgnus.getDisplayRowShift()} (the vsync resets
     * the linear line count, which is the framebuffer row). On the long frame (the one the
     * crop constants are calibrated on, and on which nearly every reference runs) the shift
     * is 0 and this function is the identity.
     */
    private int displayRow(int vpos) {
        // In INTERLACE the shift is not applied, and that is not an exception to the rule: it
        // is the same quantity at another scale, and it must be applied ONCE. The difference
        // between long and short frame is half a line (exactly what separates the two
        // fields), and in interlace AmigaDisplay's interleaving already carries it, writing
        // raw row srcY to screen row `srcY*2 + (even field ? 0 : 1)`. Adding a whole RAW row
        // as well would move the odd field by THREE screen rows instead of one: the two
        // fields would no longer interleave and the image would blur. Measured on a synthetic
        // interlaced screen: the two fields ended at framebuffer rows 44 and 45 instead of
        // both at 44.
        //
        // Outside interlace there is no interleaving (lines are doubled), so the shift
        // carries the offset: the case the references were measured on (lof1/lof2/
        // vhpos4/vhpos5, the whole vAmigaTS bank is non-interlaced).
        if (denise.isInterlaced()) {
            return vpos;
        }
        return vpos + agnus.getDisplayRowShift();
    }

    private void presentFrame() {
        buildFrameInfo(frameInfo);
        // Before presenting: the asynchronous branch swaps frameBuffer with renderBuffer, and
        // after the swap the frame just completed is no longer in frameBuffer. The cost
        // allowed here is one copy (section 11.2); submitFrame neither encodes nor sends
        // anything, and if the network thread is still busy it skips the frame instead of
        // queueing it.
        RemotePlayHost host = remotePlayHost;
        if (host != null) {
            host.submitFrame(frameBuffer, frameInfo);
        }
        if (!ASYNC_RENDER) {
            // Default: synchronous composition on the emulation thread (deterministic).
            display.renderFrame(frameBuffer, RAW_WIDTH, RAW_HEIGHT, frameInfo);
            resetFrameModeCounters();
            return;
        }
        synchronized (renderLock) {
            if (!renderBusy) {
                // Swap the frame just completed with the renderer's buffer and wake it up.
                // The emulation goes on at once on the other buffer.
                int[] tmp = renderBuffer;
                renderBuffer = frameBuffer;
                frameBuffer = tmp;
                asyncFrameInfo.copyFrom(frameInfo);
                pendingInfo = asyncFrameInfo;
                renderBusy = true;
                renderLock.notifyAll();
            }
            // If the renderer is still busy with the previous frame, this one is dropped
            // (frame skip): the emulation never blocks.
        }
        resetFrameModeCounters();
    }

    private void buildFrameInfo(FrameInfo out) {
        int bplcon0 = denise.getBplcon0();
        boolean hires      = chooseFrameHires(frameHiresLines, frameLoresLines, bplcon0);
        boolean interlaced = frameInterlacedLines > 0 ? frameInterlacedLines >= (frameHiresLines + frameLoresLines + 1) / 2
                                                      : (bplcon0 & 0x0004) != 0;
        // field 0 -> even rows (0,2,4,...), field 1 -> odd rows (1,3,5,...)
        boolean evenField  = agnus.getLastCompletedFrameField() == 0;

        // Horizontal window: from the DIW, but CLAMPED to the standard PAL video window. On
        // the hardware the monitor shows a FIXED raster region; the DIW only blanks the
        // bitplanes inside it. AmigaDisplay however centres the crop on the centre of the
        // visible area: with an extreme/asymmetric DIW (e.g. the 2nd cracktro of Persian Gulf
        // Inferno uses DIWSTRT with H=$05, a negative hstart pixel) the DIW centre moves and
        // drags the content (positioned by the DDF) out of the window on the right. The clamp
        // brings the framing back to the standard one: normal screens (DIW within the limits)
        // stay IDENTICAL, while those with a DIW past the edges are framed as on the hardware.
        // STD_LEFT/RIGHT = rgbLine pixels of the canonical PAL DIW (DIWSTRT H=$81,
        // DIWSTOP H=$1C1): (H - 2*HBLANK_MIN)*2 with HBLANK_MIN=$12 -> 186 and 826.
        final int STD_LEFT = 186, STD_RIGHT = 826;
        int visStart = Math.max(denise.getDiwHStartPixel(), STD_LEFT);
        int visStop  = Math.min(denise.getDiwHStopPixel(),  STD_RIGHT);
        int visibleX      = visStart;
        int visibleWidth  = Math.max(0, visStop - visStart);

        // Vertical: centred on the extent of the lines ACTUALLY DRAWN by the bitplanes in the
        // frame (bitplane output present), NOT on the DIW: a game can open the DIW over the
        // whole overscan (It Came From The Desert: DIW [5,320)) but draw only a sub-region
        // (box [72,255]); centring on the DIW would pile the grey overscan at the bottom and
        // push the content up. Fallback: the union of the lines inside the DIW (handles
        // Copper-driven DIW splits, e.g. Wings of Fury), then the DIW sampled at the end of
        // the frame.
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
     * Saves the raw PAL framebuffer produced by Denise, without the crop, scaling, line
     * doubling or overlays applied by AmigaDisplay.
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
    // "Regression" crop (vAmigaTS suite)
    // =========================================================================
    //
    // The vAmigaTS reference images are a fixed window cut from the stable texture:
    //
    //   Texel *ptr = stablePtr - 4*HBLANK_MIN;          // HBLANK_MIN = 0x12
    //   for (y = Y1; y < Y2; y++)
    //     for (x = X1; x < X2; x++)
    //       pixel = ptr[y*HPIXELS + x];                 // HPIXELS = 912
    //
    //   X1 = 4*0x31 = 196   X2 = HPIXELS = 912          -> width  716
    //   Y1 = VBLANK_MAX+1 = 26   Y2 = VPIXELS-2 = 311   -> height 285
    //
    // The texture column actually read is (x - 4*HBLANK_MIN) = x - 72, i.e. 4*hpos - 72.
    // Our line buffer (AbstractDenise) has exactly the same index: rgbLine[i] is
    // hpos = HBLANK_MIN + i/4, so i = 4*hpos - 4*HBLANK_MIN = 4*hpos - 72. The two indexings
    // coincide, so the crop is taken straight from the raw frameBuffer (912x312):
    //
    //   columns [124, 840)  (X1 - 4*HBLANK_MIN = 124, width 716)
    //   rows    [26, 311)   (Y1 = 26, height 285)
    //
    // The row (vpos) is the same on both sides (both count from 0 at the top).

    /** First column of the regression crop: X1(196) - 4*HBLANK_MIN(72). */
    public static final int REGRESSION_CROP_X = 124;
    /** First line of the regression crop: VBLANK_MAX(25) + 1. */
    public static final int REGRESSION_CROP_Y = 26;
    /** Width of the regression crop: X2(912) - X1(196). */
    public static final int REGRESSION_CROP_W = 716;
    /** Height of the regression crop: (VPIXELS-2 = 311) - Y1(26). */
    public static final int REGRESSION_CROP_H = 285;

    /**
     * Saves a screenshot cropped exactly like the vAmigaTS regression dumps (716x285), so it
     * can be compared pixel by pixel with the suite's reference images. Unlike
     * {@link #saveRawScreenshot}, it excludes the blanking and the overscan outside the
     * comparison window.
     */
    public void saveRegressionScreenshot(File file) throws IOException {
        saveRegressionScreenshot(file, 0);
    }

    /**
     * As {@link #saveRegressionScreenshot(File)}, with the crop moved {@code xOffset} pixels to
     * the right: a reference dump taken with a horizontal display offset starts that much later.
     */
    public void saveRegressionScreenshot(File file, int xOffset) throws IOException {
        final int cropX = REGRESSION_CROP_X + xOffset;
        Objects.requireNonNull(file, "file");
        File dir = file.getParentFile();
        if (dir != null && !dir.exists()) {
            dir.mkdirs();
        }

        int[] snapshot = Arrays.copyOf(frameBuffer, frameBuffer.length);
        BufferedImage cropped = new BufferedImage(
                REGRESSION_CROP_W, REGRESSION_CROP_H, BufferedImage.TYPE_INT_RGB);
        int[] dst = ((DataBufferInt) cropped.getRaster().getDataBuffer()).getData();
        int wrapCropCol = RIGHT_HBLANK_WRAP_COL - cropX; // 836-124 = 712 without offset
        for (int row = 0; row < REGRESSION_CROP_H; row++) {
            int srcY = REGRESSION_CROP_Y + row;
            System.arraycopy(snapshot, srcY * RAW_WIDTH + cropX,
                    dst, row * REGRESSION_CROP_W, REGRESSION_CROP_W);
            // Deep-HBLANK right-border wrap: the last visible CCK (cols 836-839 = beam CCK 0)
            // shows the COLOR00 at the line boundary, sampled at CCK 0 of the next line.
            // Applied ONLY here (not to the live framebuffer).
            //
            // It is CONDITIONAL, after three measurements of the same thing:
            //  1. removing it entirely sank TimingTest, BbusyTest, memtim1 and MemoryRom: the
            //     wrap is real;
            //  2. taking `rgbLine[0]` of line V+1 instead of COLOR00 was worse than both
            //     (oldDMACON 20/20, memtim1 from 0 to 456 px): our line starts at CCK 18, not
            //     at CCK 0, so that pixel is not the right instant;
            //  3. removing it after the per-pixel Denise rework took `BPLCON1/overscan` from
            //     384 px (0.19%) to 27-39 px (0.01%) but broke memtim1 (2/0 -> 0/2) and
            //     MemoryRom (3/1 -> 2/2) again.
            // So the two populations are separate and measured: where at CCK 0 of line V+1
            // the beam is in the BORDER, COLOR00 applies (memtim*, MemoryRom); where the line
            // had PLAYFIELD up to there, the pixel we rendered applies (overscan).
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

    // Lazy computation of the beam position (only on request, e.g. a debug overlay),
    // instead of at every tick.
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

