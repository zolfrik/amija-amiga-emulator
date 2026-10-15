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

import static io.github.zolfrik.amija.custom.AbstractDenise.COLCHANGE_KILLEHB;
import static io.github.zolfrik.amija.custom.AbstractDenise.amigaToRGB;
import static io.github.zolfrik.amija.custom.AbstractDenise.deniseBpu;
import static io.github.zolfrik.amija.custom.AbstractDenise.deniseSpecials;
import static io.github.zolfrik.amija.custom.AbstractDenise.dualPlayfieldIndex;
import static io.github.zolfrik.amija.custom.AbstractDenise.isDualPlayfield;
import static io.github.zolfrik.amija.custom.AbstractDenise.isEHB;
import static io.github.zolfrik.amija.custom.AbstractDenise.isHAM;
import static io.github.zolfrik.amija.custom.AbstractDenise.isHamActive;

import io.github.zolfrik.amija.custom.AbstractDenise.RegChange;
import io.github.zolfrik.amija.custom.AbstractDenise.RegChangeRecorder;
import java.util.Arrays;

/**
 * The end-of-line half of Denise: turns a completed line into RGB pixels and collision bits.
 *
 * <p>{@link AbstractDenise} is the front end. During the line it holds the live registers,
 * shifts the bitplane words out into the line's index buffer and records every register write
 * with the pixel at which it acts. At the end of the line it hands all of that over in a
 * {@link DeniseLineJob}, and this class does the rest: the horizontal window mask, the BPL1DAT gates,
 * playfield priority, the sprites, the collisions and the colours.
 *
 * <p>The renderer reads nothing but its jobs and its own state, which carries from one line to
 * the next (the start-of-line snapshots, the window flip-flop, the plane maximum...). The
 * registers it replays (palette, sprites) are its own copies, started from the live values the
 * job carries. Its output for a sequence of jobs is therefore fixed by that sequence alone.
 */
final class DeniseLineRenderer {

    private static final int HPIXELS    = AbstractDenise.RAW_LINE_PIXELS;
    private static final int HBLANK_MIN = 0x12;

    private boolean isECS;

    void setEcs(boolean ecs) {
        this.isECS = ecs;
        ecsShres = isECS && !isAGA;
    }

    private boolean isAGA;

    void setAga(boolean aga) {
        this.isAGA = aga;
        ecsShres = isECS && !isAGA;
    }

    /** An ECS Denise, not Lisa: its super-hires pairs two pixels into one colour register. */
    private boolean ecsShres;

    // Inputs of the line being rendered, taken from its job.
    private byte[] dBuffer;
    private RegChangeRecorder conChanges, diwChanges, colChanges;
    private RegChangeRecorder[] sprChanges;
    private int maxHpos;
    private int hcounterRenderHead;

    /**
     * Snapshot of the registers at the start of the current scanline. Used by
     * translate/colorize to stay consistent when the Copper changes the registers mid-line.
     */
    // There is no initialBplcon1: the fine scroll is read live, see drawBitplanesAtPixel.
    // BPLCON0 and BPLCON2 are start-of-line snapshots.
    private int initialBplcon0, initialBplcon2;
    // Lisa: BPLCON3 (PF2OF, sprite resolution, border) and BPLCON4 (colour xor, sprite
    // banks) at the start of the line, and CLXCON2.
    private int initialBplcon3 = 0x0C00, initialBplcon4 = 0x0011;
    private int initialFmode;
    private int clxcon2;
    private int lastRenderedBplcon0;

    /**
     * <b>The state of HSTRT/HSTOP at the START of the line; without it a mid-line write
     * would apply backwards.</b> {@link #updateBorderBuffer()} runs at the end of the line,
     * when the live HSTRT/HSTOP already hold the <b>new</b> value: starting from those, the
     * pixels <i>before</i> the write would be masked with a coordinate not yet written, and
     * {@link #diwChanges}, which reinserts it at its pixel, could not notice, since it would
     * set a value already in use.
     *
     * <p>The difference shows only when the window moves within a line. The
     * {@code Denise/DIW} bank (section 17) makes it observable: {@code diwtim.i} rewrites
     * DIWSTOP twice per line alternating two coordinates 32 lores pixels apart, and on every
     * line where the write falls <b>after</b> the counter has passed both, the old one must
     * still apply. On {@code diwtim1} the window would close at 571 instead of 507: 64
     * pixels of crop, i.e. exactly {@code $80 - $60}.
     *
     * <p>The opposite of the fine scroll, which is read live: what decides is <b>who consumes
     * the value</b>. The fine scroll enters the output cursor while the line runs; the DIW
     * mask is rebuilt all at once at the end of the line and so needs its own initial state.
     */
    private int     hstrtLineStart, hstopLineStart;
    private boolean hflop = true;
    private int     borderBufferIsDirty;
    private int     borderColor;
    /**
     * True when the line being rendered is all border: the BPL1DAT gate never opens in it
     * (see {@link #computeBpl1datGate}), so neither the playfield nor the sprites come out.
     */
    private boolean lineIsVBorder;
    private int clxcon,  clxdat;
    // PERF: mask of the sprites that drew pixels on the current scanline (cleared in
    // hsyncHandler). Used to skip useless collision loops.
    private int spritesOnLineMask;

    /**
     * The pixels the sprites emit on the line, recorded in the order they are produced.
     *
     * <p>The sprite shift registers ({@link #scanSprites}) run <b>before</b>
     * {@link #translate()}, because the end of a sprite's display window is one of the
     * instants at which Denise re-selects its rendering configuration (see
     * {@link #renderPlanes}). The composition onto {@code mBuffer}/{@code zBuffer} must stay
     * <b>after</b>, because the depth comparison reads the playfield's z: the emitted pixels
     * accumulate here and {@link #blitSprites()} pours them downstream.
     *
     * <p>One entry per call: {@code sprEmitPos} is the raw pixel, {@code sprEmitValZ} packs
     * the colour index (low 8 bits) and the depth (from bit 8). The pair of sub-pixels each
     * emission covers is rebuilt by the blit.
     */
    private int[] sprEmitPos  = new int[1024];
    private int[] sprEmitValZ = new int[1024];
    private int   sprEmitCount;

    /**
     * The sprite state transitions on the line, which {@link #translate()} consumes to know
     * <b>when</b> Denise re-selects its rendering configuration (see {@link #renderPlanes}).
     * One entry per transition: the raw pixel and, packed, the sprite index (bits 0-2), the
     * direction (bit 3) and which of the two states changes (bit 4: 0 = <i>armed</i>, i.e.
     * SPRxDATA/SPRxCTL, 1 = <i>drawing</i>, i.e. the shift register loading or emptying).
     *
     * <p>Transitions are produced pair by pair, so out of order; {@link #scanSprites()} sorts
     * them by pixel before handing them to {@link #translate()}.
     */
    private int[] sprStateEvtPos = new int[64];
    private int[] sprStateEvtKey = new int[64];
    private int   sprStateEvtCount;
    /** Sprites drawing at the start of the line (shift register not empty). */
    private int   armedsLineStart;

    private static final int SPR_EVT_UP     = 0x08;
    private static final int SPR_EVT_ARMEDS = 0x10;

    private void recordSprStateEvt(int pos, int key) {
        if (pos >= HPIXELS) return;
        if (sprStateEvtCount == sprStateEvtPos.length) {
            sprStateEvtPos = java.util.Arrays.copyOf(sprStateEvtPos, sprStateEvtCount * 2);
            sprStateEvtKey = java.util.Arrays.copyOf(sprStateEvtKey, sprStateEvtCount * 2);
        }
        sprStateEvtPos[sprStateEvtCount] = pos;
        sprStateEvtKey[sprStateEvtCount] = key;
        sprStateEvtCount++;
    }
    /** The colour registers as colorize() replays them; they start from the job's live values. */
    private final int[] palette = new int[32];
    // State of the colour registers at the START of the current scanline (= end of the
    // previous one). The live COLOR00..31 already hold every write of the line when it ends,
    // but colorize() must rebuild each segment from the colour INHERITED from the previous
    // line and apply the changes at their triggers. Without this snapshot the segment
    // BEFORE the first change would use the post-write value, and a MOVE COLOR00 in the
    // middle of a visible line (e.g. vAmigaTS synccpu) would colour the whole left part
    // with the new colour. The colour counterpart of initialBplcon0.
    private final int[] paletteLineStart = new int[32];

    /** Lisa's colour registers as colorize() replays them, and their start-of-line state. */
    private final int[] paletteAga = new int[256];
    private final int[] paletteAgaLineStart = new int[256];

    /**
     * Start-of-line snapshot of the sprite registers: the exact twin of
     * {@link #paletteLineStart}, for the same reason.
     *
     * <p>{@link #drawSpritePair} replays the register changes at their pixel triggers,
     * starting from this snapshot. Starting from the <b>live</b> values, which at the end of
     * the line already hold ALL of the line's writes, the segment before the first change
     * would use the state after every change: a mid-line MOVE to SPRxDATA would arm the
     * sprite to the left of where it was armed, a MOVE to SPRxCTL would disarm it to the
     * left where it still has to draw, and the starting horizontal position (sprhppos, from
     * POS/CTL) and the attach bit (SPRxCTL bit 7 of the odd sprite) would be those of the
     * end of the line for the whole first chunk.
     *
     * <p>sprhppos is not in the snapshot because it is derived: {@link #updateSprHCoords}
     * recomputes it from POS/CTL on restore.
     */
    private int       armedLineStart;
    private final int[] sprposLineStart  = new int[8];
    private final int[] sprctlLineStart  = new int[8];
    private final int[] sprdataLineStart = new int[8];
    private final int[] sprdatbLineStart = new int[8];
    // The sprite registers as the sprite pass replays them; they start from the job's live
    // values.
    private final int[] sprdata  = new int[8];
    private final int[] sprdatb  = new int[8];
    private final int[] sprpos   = new int[8];
    private final int[] sprctl   = new int[8];
    private final int[] sprhppos = new int[8];  // horizontal start, precomputed in pixels
    private final int[] ssra     = new int[8];  // shift register A per sprite
    private final int[] ssrb     = new int[8];  // shift register B per sprite
    private int armed;                 // bitmask of the armed sprites, replayed like the registers
    private int spriteClipEnd;
    /*
     * BPL1DAT does not only arm the bitplanes, it also unblocks the sprites. A sprite comes
     * out only when two conditions hold: it is inside the horizontal window (the bBuffer mask
     * in colorize) and the BPL1DAT gate below is open.
     */

    /** The pixels of the BPL1DAT writes of the line, in the order they came (from the job). */
    private final int[] bpl1datPixels = new int[64];
    private int bpl1datCount;

    /**
     * A BPL1DAT gate: a flip-flop that a BPL1DAT write opens and the start of the horizontal
     * blank closes. The blank starts where Denise's horizontal counter reaches
     * {@link #hblankStartCounter()}, so it is a position of the line only when the counter was
     * reset: on a line whose strobe did not reset it the close falls elsewhere, or nowhere, and
     * the gate state crosses the line boundary. Denise has two, closed together: one lets the
     * playfield out, the other the sprites, and on OCS Denise a BPL1DAT during the colour burst
     * opens only the first.
     *
     * <p>For the line being rendered, in raw pixels before the enable offsets, the gate is open
     * over [{@code openA}, {@code closeA}) and from {@code openB} on. {@code openA} is
     * {@link Integer#MIN_VALUE} when the gate comes open from the previous line, and an absent
     * interval has its start at {@link Integer#MAX_VALUE}. A BPL1DAT in the colour clock of the
     * close comes before it: Denise takes the register at the start of the clock and compares
     * the counter after, so the close wins.
     */
    private static final class Bpl1datGate {
        private int openA = Integer.MAX_VALUE, closeA = Integer.MAX_VALUE, openB = Integer.MAX_VALUE;
        /** The state at the end of the last rendered line. */
        private boolean carried;
        private int firstBefore, firstAfter;

        void begin() {
            firstBefore = Integer.MAX_VALUE;
            firstAfter = Integer.MAX_VALUE;
        }

        void add(int pixel, int close) {
            if (pixel <= close) firstBefore = Math.min(firstBefore, pixel);
            else                firstAfter  = Math.min(firstAfter, pixel);
        }

        void end(int close) {
            openA  = carried ? Integer.MIN_VALUE : firstBefore;
            closeA = close;
            openB  = firstAfter;
            carried = firstAfter != Integer.MAX_VALUE
                    || (close == Integer.MAX_VALUE && openA != Integer.MAX_VALUE);
        }

        /** Whether the gate is open at {@code pos}, opening {@code offset} pixels late. */
        boolean openAt(int pos, int offset) {
            if (openA != Integer.MAX_VALUE && pos < closeA
                    && (openA == Integer.MIN_VALUE || pos >= openA + offset)) {
                return true;
            }
            return openB != Integer.MAX_VALUE && pos >= openB + offset;
        }

        /** Whether the gate opens anywhere in the first {@code len} pixels. */
        boolean opensWithin(int offset, int len) {
            final boolean a = openA != Integer.MAX_VALUE && closeA > 0
                    && (openA == Integer.MIN_VALUE || (long) openA + offset < Math.min(closeA, len));
            final boolean b = openB != Integer.MAX_VALUE && (long) openB + offset < len;
            return a || b;
        }

        /** Zeroes {@code buf} wherever the gate, opening {@code offset} pixels late, is closed. */
        void clearWhereClosed(byte[] buf, int offset) {
            final int len = buf.length;
            int pos = 0;
            if (openA != Integer.MAX_VALUE) {
                final long start = openA == Integer.MIN_VALUE ? Long.MIN_VALUE : (long) openA + offset;
                final int s = (int) Math.max(0, Math.min(len, start));
                final int e = (int) Math.max(0, Math.min(len, (long) closeA));
                if (s > pos) Arrays.fill(buf, pos, s, (byte) 0);
                pos = Math.max(pos, Math.max(s, e));
            }
            if (openB != Integer.MAX_VALUE) {
                final int s = (int) Math.max(0, Math.min(len, (long) openB + offset));
                if (s > pos) Arrays.fill(buf, pos, s, (byte) 0);
                pos = len;
            }
            if (len > pos) Arrays.fill(buf, pos, len, (byte) 0);
        }
    }

    private final Bpl1datGate playfieldGate = new Bpl1datGate();
    private final Bpl1datGate spriteGate    = new Bpl1datGate();

    /** The counter value at which the horizontal blank starts. */
    private int hblankStartCounter() {
        return isECS ? 0x10 : 0x0F;
    }

    /**
     * Whether the colour burst is on at counter value {@code counter}, as the counter reads at a
     * BPL1DAT pixel. OCS Denise only: a BPL1DAT then does not let the sprites out.
     */
    private boolean burstAt(int counter) {
        return !isECS && counter >= BURST_FIRST_COUNTER && counter <= BURST_LAST_COUNTER;
    }

    private static final int BURST_FIRST_COUNTER = 0x41;
    private static final int BURST_LAST_COUNTER  = 0x53;

    /**
     * The two gates of the line being rendered and the state they leave to the next line. The
     * close is the pixel at which the line's counter reaches the start of the horizontal blank;
     * a line covers 454 of the 512 counter values, and when it misses that one the gates are not
     * closed at all.
     */
    private void computeBpl1datGate() {
        final int lineSteps = maxHpos;
        final int s = (hblankStartCounter() - hcounterRenderHead) & 0x1FF;
        final int close = s < lineSteps ? 2 * (s - HDIW_HEAD_STEPS) : Integer.MAX_VALUE;
        final int mainCounter = (hcounterRenderHead + HDIW_HEAD_STEPS) & 0x1FF;
        playfieldGate.begin();
        spriteGate.begin();
        for (int k = 0; k < bpl1datCount; k++) {
            final int p = bpl1datPixels[k];
            playfieldGate.add(p, close);
            if (!burstAt((mainCounter + (p >> 1)) & 0x1FF)) spriteGate.add(p, close);
        }
        playfieldGate.end(close);
        spriteGate.end(close);
        bpl1datCount = 0;
    }

    /**
     * Distance between the BPL1DAT write and the pixel from which the sprite becomes
     * visible again, in raw pixels (2 per half CCK). <b>Measured</b>: see the plan, section
     * 20, class A.
     */
    private static final int SPRITE_ENABLE_OFFSET = 0;

    /**
     * The same gap for the PLAYFIELD, which comes back one colour clock after the sprite:
     * the bitplane output resumes from the CCK that follows the BPL1DAT write, in raw
     * pixels (4 per CCK). MEASURED and bracketed on both sides - 2 leaves a lores pixel
     * of playfield that should not be there, 6 eats one that should.
     */
    private static final int BPL_ENABLE_OFFSET = 4;

    /** Total pixels rendered per sprite in the current frame (reset at every vsync). */
    private final long[] spritePixelsThisFrame = new long[8];
    /** Total pixels rendered per sprite in the most recently completed frame. */
    private final long[] spritePixelsLastFrame = new long[8];

    // =========================================================================
    // Line buffer
    // =========================================================================

    /**
     * dBuffer: raw bitplane colour index (before priority), the job's.
     * bBuffer: border/DIW mask. 0xFF = inside DIW, otherwise the border colour.
     * mBuffer: final colour index after priority resolution.
     * zBuffer: depth buffer (higher value = closer to the viewer).
     */
    private final byte[] bBuffer = new byte[HPIXELS + 64 + 8];
    private final byte[] mBuffer = new byte[HPIXELS + 64 + 8];
    private final int[]  zBuffer = new int [HPIXELS + 64 + 8];
    /** Lisa: 1 where a sprite pixel won the position, so the playfield colour xor skips it. */
    private final byte[] sBuffer = new byte[HPIXELS + 64 + 8];
    private int[]        rgbLine = new int[HPIXELS];   // the job's, once a line has been rendered

    // =========================================================================
    // Z-buffer constants (priority/depth)
    // Higher value = layer closer to the front.
    //
    // Order (nearest to farthest):
    //   Z_0 > Z_SP0 > Z_SP1 > Z_1 > Z_SP2 > Z_SP3 > Z_2 >
    //   Z_SP4 > Z_SP5 > Z_3 > Z_SP6 > Z_SP7 > Z_4
    //
    // zPF maps the PFxP field of BPLCON2:
    //   PFP = 0 -> Z_4 (behind every sprite)
    //   PFP = 4 -> Z_0 (in front of every sprite)
    //
    // The values are distinct powers of 2, so the OR of a set of Z constants keeps the
    // highest-priority level as its MSB. That makes "z > zBuffer[p]" a correct priority
    // test even when zBuffer accumulates several layers through OR.
    //
    // The auxiliary masks used by translateDPF (0x01-0x07) all fall below Z_4 (0x0008), so
    // they never interfere with the Z_SP comparisons.
    // =========================================================================

    private static final int Z_0   = 0x8000;
    private static final int Z_SP0 = 0x4000;
    private static final int Z_SP1 = 0x2000;
    private static final int Z_1   = 0x1000;
    private static final int Z_SP2 = 0x0800;
    private static final int Z_SP3 = 0x0400;
    private static final int Z_2   = 0x0200;
    private static final int Z_SP4 = 0x0100;
    private static final int Z_SP5 = 0x0080;
    private static final int Z_3   = 0x0040;
    private static final int Z_SP6 = 0x0020;
    private static final int Z_SP7 = 0x0010;
    private static final int Z_4   = 0x0008;

    /** All eight sprite depth bits. */
    private static final int Z_SP_ALL = Z_SP0 | Z_SP1 | Z_SP2 | Z_SP3 | Z_SP4 | Z_SP5 | Z_SP6 | Z_SP7;

    /** How many pixels before the opening of the display window the sprite output is taken. */
    private static final int DIW_OPEN_SPRITE_SAMPLE_PX = 1;

    /** Pixel sampled for the display-window opening collision, -1 if none on this line. */
    private int diwOpenSamplePx = -1;
    /** Depth bits of the sprites that had a pixel there, whether clipped or not. */
    private int diwOpenSpriteZ;

    private static final int[] Z_SP = {
        Z_SP0, Z_SP1, Z_SP2, Z_SP3, Z_SP4, Z_SP5, Z_SP6, Z_SP7
    };

    /**
     * Mask covering the Z bits of all 8 sprites (used by colorizeHAM).
     */
    private static final int Z_ALL_SPRITES =
        Z_SP0 | Z_SP1 | Z_SP2 | Z_SP3 | Z_SP4 | Z_SP5 | Z_SP6 | Z_SP7;
    // = 0x4000|0x2000|0x0800|0x0400|0x0100|0x0080|0x0020|0x0010 = 0x6BB0
    /**
     * The MAXIMUM number of planes since the last decay.
     *
     * <p><b>It does not rise when BPLCON0 changes: it rises when Denise re-selects its
     * rendering configuration</b> ({@link #selectLts}). The two almost always coincide (a
     * change in the number of planes always re-selects), but they are different rules, and
     * the difference shows at the line boundary. If the plane count decreases mid-scanline,
     * old data in the higher planes' shifters must still be shifted out.
     *
     * <p>It decays at the <b>start</b> of every drawn line, back to the live count, and that
     * decay does <b>not</b> re-select anything in lores: that is exactly why
     * {@link #renderPlanes} can stay higher for part of the next line.
     */
    private int deniseMaxPlanes = 0;

    /**
     * The planes with which the current <b>rendering configuration</b> was chosen, and the
     * value that decides whether the two undocumented functions of priority >= 5 apply;
     * see {@link AbstractDenise#deniseSpecials}.
     *
     * <p><b>It survives the line boundary.</b> It is the snapshot of
     * {@link #deniseMaxPlanes} taken at the last {@link #selectLts}, and nothing refreshes it
     * until the next one: the start-of-line decay lowers the maximum but does not
     * re-select, so a line can begin with a five-plane configuration while composing four.
     *
     * <p>That is why this value cannot be read live inside {@link #translate()}: the point
     * where it returns to the real count is not a hardware position but the first
     * re-selection instant, and on a line with sprites that instant is the <b>end of the
     * display window</b> of the last sprite that was drawing.
     */
    private int renderPlanes = 0;

    /**
     * {@code true} if the current rendering configuration is the variant <b>with
     * sprites</b>: {@link #selectLts} chooses it by whether at that instant at least one
     * sprite was armed or drawing, and it decides whether a disarm counts as a re-selection.
     */
    private boolean spriteLtsSelected = false;

    /**
     * Re-selection of Denise's rendering configuration.
     *
     * @param planesNow  the <b>live</b> planes at the instant of the re-selection
     * @param armedCnt   sprites armed (SPRxDATA written, SPRxCTL not yet)
     * @param armedsCnt  sprites <b>drawing</b> at this instant
     */
    private void selectLts(int planesNow, int armedCnt, int armedsCnt) {
        if (planesNow > deniseMaxPlanes) deniseMaxPlanes = planesNow;
        renderPlanes      = deniseMaxPlanes;
        spriteLtsSelected = armedCnt > 0 || armedsCnt > 0;
    }
    private void updateSprHCoords(int x) {
        sprhppos[x] = spriteStart(((sprpos[x] & 0xFF) << 1) | (sprctl[x] & 0x01));
        if (isAGA) {
            // Lisa: with SSCAN2 bit 7 of SPRxPOS is the doubling flag, not a position bit;
            // SH1 (SPRxCTL bit 4) moves the sprite one hires pixel to the right.
            sprhppos2[x] = Integer.MIN_VALUE;
            if ((initialFmode & 0x8000) != 0) {
                // The comparison then looks at eight bits of the counter: the sprite also
                // loads 256 lores pixels further on.
                sprhppos[x] = spriteStart(((sprpos[x] & 0x7F) << 1) | (sprctl[x] & 0x01));
                sprhppos2[x] = sprhppos[x] + 512;
            }
            if ((sprctl[x] & 0x10) != 0) {
                sprhppos[x]++;
                if (sprhppos2[x] != Integer.MIN_VALUE) sprhppos2[x]++;
            }
        }
    }

    /**
     * Where a sprite with horizontal start {@code hstart} loads in the line being rendered:
     * the buffer position at which Denise's horizontal counter reaches it, one lores pixel
     * later. The counter of the line starts at {@link #hcounterRenderHead}, so on a line whose
     * strobe did not reset it the sprite moves, or does not come out at all. A start in the
     * head of the line is a negative position.
     */
    private int spriteStart(int hstart) {
        int d = (hstart - ((hcounterRenderHead + HDIW_HEAD_STEPS) & 0x1FF)) & 0x1FF;
        if (d >= 0x200 - HDIW_HEAD_STEPS) d -= 0x200;
        return 2 * d + 2;
    }
    // =========================================================================
    // Shift-register output (replayed from the front end's log)
    // =========================================================================

    /**
     * State of the <b>incremental emission</b> of the shift registers, one group per
     * playfield (index 0 = odd planes, 1 = even planes). See {@code AbstractDenise.bplEvents}
     * for the model: a word shifts out one pixel at a time at the step of the live
     * resolution, and only {@code hires} is live; the planes and the fine scroll are latched
     * at load. Every line starts with both groups empty: the previous line drained them.
     */
    private final int[]  srCount  = new int[2];   // bits still to shift out (0..16)
    private final int[]  srCursor = new int[2];   // next dBuffer index to write
    private final int[]  srPhase  = new int[2];   // sub-pixels of the current bit already emitted
    private final byte[][] srSlice = new byte[2][64]; // precomputed slices, one per bit
    private final int[]  srLen    = { 16, 16 };   // bits of the word being shifted out
    private boolean srHires;                      // the live resolution during the replay
    private boolean srShres;                      // Lisa: super-hires, two bits per sub-pixel

    /**
     * Writes the line's bitplane indices into {@link #dBuffer} by replaying the front end's
     * log: before each event, the half-CCK steps taken until then; then the event (a word
     * load or a change of resolution). The order is the one the front end saw, so a load and
     * a BPLCON0 write within the same half CCK keep their relative order.
     */
    private void emitBitplanes(DeniseLineJob job) {
        final int[] e = job.bplEvents;
        final int n = job.bplEventCount;
        srHires = job.bplStartHires;
        srShres = job.bplStartShres;
        srCount[0] = srCount[1] = 0;
        int done = 0;
        int i = 0;
        while (i < n) {
            final int header = e[i];
            final int step = header >>> DeniseLineJob.BPL_EV_STEP_SHIFT;
            // With both groups empty a step writes nothing.
            if (srCount[0] == 0 && srCount[1] == 0) done = step;
            while (done < step) {
                emitBitplaneHalfCck();
                done++;
                if (srCount[0] == 0 && srCount[1] == 0) done = step;
            }
            final boolean arg = (header & DeniseLineJob.BPL_EV_ARG) != 0;
            final int type = header & DeniseLineJob.BPL_EV_TYPE;
            if (type == DeniseLineJob.BPL_EV_HIRES) {
                srHires = arg;
                i++;
            } else if (type == DeniseLineJob.BPL_EV_SHRES) {
                srShres = arg;
                i++;
            } else if (type == DeniseLineJob.BPL_EV_LOAD_WIDE) {
                armShiftOutWide(arg ? 1 : 0, e, i + 1);
                i += DeniseLineJob.BPL_LOAD_WIDE_SIZE;
            } else {
                armShiftOut(arg ? 1 : 0, e[i + 1], e[i + 2], e[i + 3], e[i + 4]);
                i += DeniseLineJob.BPL_LOAD_SIZE;
            }
        }
        // A word armed at the end of the line cannot shift out completely: the CCKs to do so
        // are gone. Its remaining pixels are written here: it is the tail of the line, past
        // the fetch window. The steps between the last event and the end of the line are
        // part of the same drain, since nothing changes in between.
        flushShiftOut();
    }

    private void armShiftOut(int grp, int cursor, int w0, int w1, int w2) {
        // THE SHIFTER DOES NOT STOP BETWEEN ONE WORD AND THE NEXT. The shift register
        // advances at EVERY pixel of the line and the scroll only selects the PHASE at
        // which the new word is loaded: there is no cursor, so there is never a hole. Here
        // each word is painted in an absolute window, and when the scroll DROPS mid-line the
        // new word starts PAST the point the old one reached; those sub-pixels would be
        // written by no one and come out as colour 0.
        //
        // Holding the last value is not enough either: measured, the held bit would be 0
        // where the playfield is drawn. What comes out are the NEXT BITS of the old word,
        // which keeps shifting until the new one replaces it. That is what is done here, and
        // the window is bounded by construction: at most the difference between the two
        // scrolls.
        if (srCount[grp] > 0 && cursor > srCursor[grp]) {
            emitGroup(grp, cursor - srCursor[grp]);
        }
        srCount[grp]  = 16;
        srLen[grp]    = 16;
        srCursor[grp] = cursor;
        srPhase[grp]  = 0;
        precomputeSlices(grp, w0, w1, w2);
    }

    /**
     * The load of a word on Lisa ({@code BPL_EV_LOAD_WIDE}): {@code e[at]} is the cursor,
     * {@code e[at + 1]} the word length in bits, then the four planes of the group as
     * (high, low) int pairs, the plane bits left-aligned in the {@code long} they make.
     */
    private void armShiftOutWide(int grp, int[] e, int at) {
        final int cursor = e[at];
        final int bits = e[at + 1];
        if (srCount[grp] > 0 && cursor > srCursor[grp]) {
            emitGroup(grp, cursor - srCursor[grp]);
        }
        srCount[grp]  = bits;
        srLen[grp]    = bits;
        srCursor[grp] = cursor;
        srPhase[grp]  = 0;
        final byte[] dst = srSlice[grp];
        final int m = grp == 0 ? 1 : 2;
        long w0 = word(e, at + 2, bits), w1 = word(e, at + 4, bits);
        long w2 = word(e, at + 6, bits), w3 = word(e, at + 8, bits);
        for (int i = 0; i < bits; i++) {
            final long bit = 1L << 63 >>> i;
            int slice = 0;
            if ((w0 & bit) != 0) slice |= m;
            if ((w1 & bit) != 0) slice |= m << 2;
            if ((w2 & bit) != 0) slice |= m << 4;
            if ((w3 & bit) != 0) slice |= m << 6;
            dst[i] = (byte) slice;
        }
    }

    /** A plane word of a wide load, its {@code bits} bits left-aligned in a long. */
    private static long word(int[] e, int at, int bits) {
        final long v = ((long) e[at] << 32) | (e[at + 1] & 0xFFFFFFFFL);
        return v << (64 - bits);
    }

    /** Shifts out the two sub-pixels of one half CCK from both groups. */
    private void emitBitplaneHalfCck() {
        if (srCount[0] != 0) emitGroup(0, 2);
        if (srCount[1] != 0) emitGroup(1, 2);
    }

    /** Shifts out what is left of the two words, at the end of the line. See the caller. */
    private void flushShiftOut() {
        while (srCount[0] > 0 || srCount[1] > 0) {
            if (srCount[0] > 0) emitGroup(0, 2);
            if (srCount[1] > 0) emitGroup(1, 2);
        }
    }

    /**
     * Precomputes the <b>sixteen slices</b> of the word: the contribution of each bit to
     * {@code dBuffer}, already combined over the group's three planes.
     *
     * <p><b>An optimisation, not a model</b>, possible only because nothing touches the
     * shift register between one load and the next. Without it the emission costs three
     * tests and three shifts <b>per sub-pixel</b> instead of per word: +12.45% on the A/B
     * bench (`tools/ab-bench`, 5/5 in agreement).
     */
    private void precomputeSlices(int grp, int r0, int r1, int r2) {
        final byte[] dst = srSlice[grp];
        final int m0, m1, m2;
        if (grp == 0) { m0 = 1; m1 = 4; m2 = 16; }
        else          { m0 = 2; m1 = 8; m2 = 32; }
        for (int i = 0; i < 16; i++) {
            final int bit = 0x8000 >> i;
            int slice = 0;
            if ((r0 & bit) != 0) slice |= m0;
            if ((r1 & bit) != 0) slice |= m1;
            if ((r2 & bit) != 0) slice |= m2;
            dst[i] = (byte) slice;
        }
    }

    /**
     * Super-hires: a pixel is half a sub-pixel, so each sub-pixel takes two bits. Lisa shows
     * the first of them. An ECS Denise, with at most two planes, keeps both: the first pixel's
     * planes in index bits 0-1 and the second's in bits 2-3, the four-bit index of the colour
     * register the pair shows (see colorizeNormal).
     */
    private void emitGroupShres(int grp, int subPixels) {
        final byte[] slices = srSlice[grp];
        final int keepMask = (grp == 0) ? 0xAA : 0x55;
        final int len      = srLen[grp];
        int count  = srCount[grp];
        int cursor = srCursor[grp];
        for (int i = 0; i < subPixels && count > 0; i++) {
            if (cursor >= 0 && cursor < dBuffer.length) {
                int slice = slices[len - count];
                if (ecsShres && count > 1) slice |= slices[len - count + 1] << 2;
                dBuffer[cursor] = (byte) ((dBuffer[cursor] & keepMask) | slice);
            }
            cursor++;
            count = Math.max(0, count - 2);
        }
        srCount[grp]  = count;
        srCursor[grp] = cursor;
        srPhase[grp]  = 0;
    }

    private void emitGroup(int grp, int subPixels) {
        if (srShres) {
            emitGroupShres(grp, subPixels);
            return;
        }
        final byte[] slices = srSlice[grp];
        final int keepMask = (grp == 0) ? 0xAA : 0x55;
        final int len      = srLen[grp];
        final int bitStep  = srHires ? 1 : 2;   // sub-pixels per bit, at the live resolution
        int count  = srCount[grp];
        int cursor = srCursor[grp];
        int phase  = srPhase[grp];
        // The cursor stays in phase with the CCK: two sub-pixels per half CCK, always.
        for (int i = 0; i < subPixels && count > 0; i++) {
            if (cursor >= 0 && cursor < dBuffer.length) {
                dBuffer[cursor] = (byte) ((dBuffer[cursor] & keepMask) | slices[len - count]);
            }
            cursor++;
            if (++phase >= bitStep) {
                phase = 0;
                count--;
            }
        }
        srCount[grp]  = count;
        srCursor[grp] = cursor;
        srPhase[grp]  = phase;
    }

    // =========================================================================
    // Main rendering pipeline (one job per completed line)
    // =========================================================================

    /**
     * Renders the line {@code job} carries and leaves its buffers ready for reuse: the bitplane
     * buffer cleared and every change recorder emptied.
     */
    void render(DeniseLineJob job) {
        final int vpos = job.vpos;
        dBuffer            = job.dBuffer;
        rgbLine            = job.rgb;
        conChanges         = job.conChanges;
        diwChanges         = job.diwChanges;
        colChanges         = job.colChanges;
        sprChanges         = job.sprChanges;
        clxcon             = job.clxcon;
        clxcon2            = job.clxcon2;
        hcounterRenderHead = job.hcounterRenderHead;
        maxHpos            = job.maxHpos;
        System.arraycopy(job.bpl1datPixels, 0, bpl1datPixels, 0, job.bpl1datCount);
        bpl1datCount       = job.bpl1datCount;
        emitBitplanes(job);
        // The registers the replays start from are the line's live end state, as Denise holds
        // them when the line closes; the replays bring them back to that state.
        System.arraycopy(job.palette, 0, palette, 0, 32);
        if (isAGA) System.arraycopy(job.paletteAga, 0, paletteAga, 0, 256);
        System.arraycopy(job.sprpos,  0, sprpos,  0, 8);
        System.arraycopy(job.sprctl,  0, sprctl,  0, 8);
        System.arraycopy(job.sprdata, 0, sprdata, 0, 8);
        System.arraycopy(job.sprdatb, 0, sprdatb, 0, 8);
        if (isAGA) {
            System.arraycopy(job.sprdataWide, 0, sprdataW, 0, 8);
            System.arraycopy(job.sprdatbWide, 0, sprdatbW, 0, 8);
        }
        armed = job.armed;
        // A DIW write in the line invalidates the cached border mask for this line and the next.
        if (!diwChanges.isEmpty()) borderBufferIsDirty = Math.max(borderBufferIsDirty, 2);

        lastRenderedBplcon0 = initialBplcon0;
        if (vpos == 0) {
            System.arraycopy(spritePixelsThisFrame, 0, spritePixelsLastFrame, 0, 8);
            Arrays.fill(spritePixelsThisFrame, 0L);
        }
        // rgbLine is not pre-filled here: both branches below already cover the WHOLE
        // rgbLine[0..HPIXELS): the visible branch through colorize() (colorizeNormal/
        // colorizeHAM write every pixel, borders included, because the colChanges segments
        // tile [0,HPIXELS)), the VBlank branch through fillVBlankLine().

        updateBorderBuffer();
        computeBpl1datGate();

        if (job.visible) {
            // Visible scanline: full pipeline.
            // OCS: sprites are visible only on lines that produced bitplane output. A line
            // without bitplanes (vertical border, even inside the DIW, e.g. North & South
            // turns the bitplanes off below the flags but leaves the DIW open until line
            // 299) is all border: the cursor/sprite does NOT appear on it (sprites in the
            // border only with BRDSPRT on ECS).
            // A line on which the BPL1DAT gate never opens is all border. See
            // computeBpl1datGate: the gate can also come open from the previous line.
            lineIsVBorder = !playfieldGate.opensWithin(SPRITE_ENABLE_OFFSET, HPIXELS);
            findDiwOpeningSample();
            // The sprite shift registers run BEFORE the playfield: the end of their display
            // window is one of the instants at which Denise re-selects the rendering
            // configuration translate() consumes (see renderPlanes). The composition onto
            // the buffers stays downstream, because it compares the playfield's z.
            scanSprites();
            translate();
            blitSprites();
            checkCollisions();
            colorize(vpos);
        } else {
            lineIsVBorder = false;
            // VBlank: fill with the background colour to avoid a black band.
            fillVBlankLine(vpos);

            // In VBlank scanSprites() is NOT called: rendering would consume the
            // sprChanges and could dirty mBuffer/zBuffer for the first visible scanline.
            // The recorders are still emptied explicitly, so the sprite events recorded
            // during VBlank do not accumulate.
            for (int i = 0; i < 4; i++) sprChanges[i].clear();

            colChanges.clear();
            conChanges.clear();
        }

        // The decay of the plane maximum is NOT here: it is at the start of the DRAWN line,
        // inside translate(), after the start-of-line re-selection. See deniseMaxPlanes.

        // Buffer cleanup for the next scanline.
        //
        //  - dBuffer: written with |= by the shift-register emission (emitBitplanes) and read
        //    ENTIRELY by translate (arraycopy dBuffer -> mBuffer). Needs a full clear before
        //    it goes back to the front end.
        //  - zBuffer: translate() ASSIGNS (not OR) zBuffer[0..HPIXELS) on every visible line
        //    before any read; the tail [HPIXELS, length) is not touched by translate but is
        //    used by blitSprites (|=) and by the reads, so only the tail is cleared.
        //  - mBuffer: translate() overwrites it ENTIRELY through arraycopy at the start of
        //    the pipeline, before any read: no clear needed.
        //
        // In VBlank the buffers are not read (see the else branch above), so leaving
        // [0,HPIXELS) of zBuffer uncleared is safe across the VBlank -> visible line
        // transition too (translate reassigns before reading).
        Arrays.fill(dBuffer, (byte) 0);
        Arrays.fill(zBuffer, HPIXELS, zBuffer.length, 0);
        if (isAGA) Arrays.fill(sBuffer, (byte) 0);
        for (int i = 0; i < 8; i++) { ssra[i] = 0; ssrb[i] = 0; }
        if (isAGA) {
            Arrays.fill(ssraW, 0);
            Arrays.fill(ssrbW, 0);
        }
        spritesOnLineMask = 0;

        // Sprites can be visible anywhere on the active line; the horizontal position is
        // already filtered by sprhppos (negative when in blanking).
        spriteClipEnd   = HPIXELS + 32;

        // Snapshot of the registers: the "initial" state of the scanline about to start,
        // before any update.
        initialBplcon0 = job.bplcon0;
        initialBplcon2 = job.bplcon2;
        initialBplcon3 = job.bplcon3;
        initialBplcon4 = job.bplcon4;
        initialFmode = job.fmode;
        // Same reasoning as the palette, for the horizontal window: updateBorderBuffer() has
        // just consumed the line, so the live values are those the NEXT line inherits. See
        // hstrtLineStart.
        hstrtLineStart = job.hstrt;
        hstopLineStart = job.hstop;
        // Snapshot of the colour registers at the END of the line (= start of the next): in
        // the visible branch colorize() has just brought palette[] back to the end-of-line
        // state; in the VBlank branch palette[] already holds the live values. Either way it
        // is the state the next scanline inherits.
        System.arraycopy(palette, 0, paletteLineStart, 0, 32);
        if (isAGA) System.arraycopy(paletteAga, 0, paletteAgaLineStart, 0, 256);
        // Same reasoning for the sprite registers: in the visible branch scanSprites() has
        // just brought the registers back to the end-of-line state by replaying the changes,
        // in the VBlank branch they are already live. Either way it is the state the next
        // scanline inherits. See armedLineStart.
        armedLineStart = armed;
        System.arraycopy(sprpos,  0, sprposLineStart,  0, 8);
        System.arraycopy(sprctl,  0, sprctlLineStart,  0, 8);
        System.arraycopy(sprdata, 0, sprdataLineStart, 0, 8);
        System.arraycopy(sprdatb, 0, sprdatbLineStart, 0, 8);
        if (isAGA) {
            System.arraycopy(sprdataW, 0, sprdataWLineStart, 0, 8);
            System.arraycopy(sprdatbW, 0, sprdatbWLineStart, 0, 8);
        }

        diwChanges.clear();
        conChanges.clear();
        colChanges.clear();

        // The results the output stage reads, besides the pixels already in job.rgb.
        job.lastRenderedBplcon0 = lastRenderedBplcon0;
        job.vBorder = lineIsVBorder;
        int mask = 0;
        for (int k = 0; k < AbstractDenise.WRAP_CELL_SUBPIXELS; k++) {
            if (isPlayfieldAt(AbstractDenise.RIGHT_HBLANK_WRAP_COL + k)) mask |= 1 << k;
        }
        job.wrapPlayfieldMask = mask;
    }

    // =========================================================================
    // VBlank fill
    // =========================================================================

    /**
     * Fills a scanline in the VBlank area with the current background colour. Without it,
     * the pixels left uninitialised would show black.
     */
    private void fillVBlankLine(int vpos) {
        int bgRGB    = isAGA ? paletteAga[0] : amigaToRGB(palette[0]);
        Arrays.fill(rgbLine, bgRGB);
    }
    // =========================================================================
    // Phase 2: updateBorderBuffer, computes bBuffer (DIW/border mask)
    // =========================================================================

    /**
     * Counter steps of the head of a line: after the strobe resets the counter to 2, the steps
     * before the rendered buffer starts at {@code 2 * HBLANK_MIN}.
     */
    private static final int HDIW_HEAD_STEPS = 2 * HBLANK_MIN - 2;

    /**
     * Sub-pixels of the line's own part of the horizontal comparator: from the end of the head
     * to where the next line's head begins, 0x1C8 on a line whose counter was reset.
     */
    private static final int HDIW_MAIN_SUBPIXELS = 2 * (0x1C8 - 2 * HBLANK_MIN);

    /** Inputs and outcome of the last main-part scan, to reuse bBuffer when nothing moved. */
    private boolean mainStartFlop, mainEndFlop;
    private int     mainStartHstrt = -1, mainStartHstop = -1, mainStartCounter = -1;

    /**
     * Border mask of the line: the horizontal display window is a flip-flop that the
     * counter comparisons set (start) and clear (stop), and that carries its state from
     * one line to the next.
     *
     * <p>The comparator runs on every value Denise's horizontal counter takes, not only on
     * those of the rendered buffer, and the counter is Denise's own: it starts the line at
     * {@link #hcounterRenderHead} and wraps at 512. After a reset that is 2, and the line sweeps
     * 2 to 455; on a line whose strobe did not reset it (the equalization lines on OCS Denise, a
     * strobe lost to a bitplane fetch) it sweeps other values, 456 to 511 included.
     *
     * <p>The head of the line - its first {@link #HDIW_HEAD_STEPS} counter steps - precedes
     * HBLANK_MIN and produces no pixel, but it is compared with the DIW coordinates in force
     * at that instant, writes made there included (they are recorded at their real, negative,
     * position). A start coordinate in the head therefore opens the window for the rest of the
     * line.
     *
     * <p>The main part starts from the state the head leaves, with the coordinates of that
     * instant, and applies the later DIW writes at their positions. With equal coordinates
     * the stop wins, the two comparisons being separate and in that order.
     */
    private void updateBorderBuffer() {
        final int head = hcounterRenderHead;
        int     hs = hstrtLineStart;
        int     he = hstopLineStart;
        boolean hf = hflop;
        int     diwIdx = 0;

        // Head: no pixel comes out of it, only the flip-flop state.
        for (int s = 0; s < HDIW_HEAD_STEPS; s++) {
            final int counter = (head + s) & 0x1FF;
            final int i = 2 * (s - HDIW_HEAD_STEPS);
            while (diwIdx < diwChanges.size() && i >= diwChanges.keys[diwIdx]) {
                RegChange c = diwChanges.elements[diwIdx++];
                if      (c.reg == 0x08E) hs = c.value;
                else if (c.reg == 0x090) he = c.value;
            }
            if (counter == (hs & 0x1FF)) hf = true;
            if (counter == (he & 0x1FF)) hf = false;
        }

        // Same inputs as the previous line and no DIW write in between: same mask.
        final int mainCounter = (head + HDIW_HEAD_STEPS) & 0x1FF;
        if (borderBufferIsDirty == 0 && hf == mainStartFlop && mainCounter == mainStartCounter
                && hs == mainStartHstrt && he == mainStartHstop) {
            hflop = mainEndFlop;
            return;
        }
        if (borderBufferIsDirty > 0) borderBufferIsDirty--;
        mainStartFlop    = hf;
        mainStartHstrt   = hs;
        mainStartHstop   = he;
        mainStartCounter = mainCounter;

        int counter = mainCounter;
        int i = 0;
        for (; i < HDIW_MAIN_SUBPIXELS; i++) {
            while (diwIdx < diwChanges.size() && i >= diwChanges.keys[diwIdx]) {
                RegChange c = diwChanges.elements[diwIdx++];
                if      (c.reg == 0x08E) hs = c.value;
                else if (c.reg == 0x090) he = c.value;
            }
            // Bit 9 of a coordinate is Lisa's hires fine bit: the match falls on the second
            // sub-pixel of the lores pixel.
            if (counter == (hs & 0x1FF) && (i & 1) >= (hs >> 9)) hf = true;
            if (counter == (he & 0x1FF) && (i & 1) >= (he >> 9)) hf = false;
            if ((i & 1) == 1) counter = (counter + 1) & 0x1FF;
            bBuffer[i] = hf ? (byte) 0xFF : (byte) borderColor;
        }
        // Past the wrap: the next line's head decides, this line keeps its final state.
        Arrays.fill(bBuffer, i, HPIXELS, hf ? (byte) 0xFF : (byte) borderColor);

        if (hflop != hf) borderBufferIsDirty = Math.max(borderBufferIsDirty, 1);
        hflop       = hf;
        mainEndFlop = hf;
    }

    // =========================================================================
    // Phase 3: translate, resolves playfield priority -> mBuffer + zBuffer
    // =========================================================================

    private void translate() {
        // BPL1DAT gates the playfield output, not just the sprites: before the first
        // BPL1DAT of the line Denise emits no bitplane pixel at all, so the shift
        // registers contribute nothing even when they still hold a word from an earlier
        // line. Zeroing dBuffer here covers the three consumers that read it - the
        // playfield index, the playfield collisions and the HAM decoder - with one rule.
        playfieldGate.clearWhereClosed(dBuffer, BPL_ENABLE_OFFSET);

        System.arraycopy(dBuffer, 0, mBuffer, 0, mBuffer.length);

        int     pixel          = 0;
        int     currentBplcon0 = initialBplcon0;
        int     currentBplcon2 = initialBplcon2;
        int     currentBplcon3 = initialBplcon3;
        int     conIdx         = 0;
        int     sprIdx         = 0;
        int     armedCnt       = Integer.bitCount(armedLineStart);
        int     armedsCnt      = Integer.bitCount(armedsLineStart);

        // Start of the line, in this order: first the re-selection that fires when no
        // sprite is in play any more, THEN the decay of the plane maximum. That is why the
        // maximum can survive the line: the decay re-selects nothing in lores.
        if (armedCnt == 0 && armedsCnt == 0 && spriteLtsSelected) {
            selectLts(deniseBpu(currentBplcon0), armedCnt, armedsCnt);
        }
        deniseMaxPlanes = deniseBpu(currentBplcon0);

        while (pixel < HPIXELS) {
            int nextCon = conIdx < conChanges.size()  ? conChanges.keys[conIdx]  : HPIXELS;
            int nextSpr = sprIdx < sprStateEvtCount   ? sprStateEvtPos[sprIdx]   : HPIXELS;
            int trigger = Math.min(nextCon, nextSpr);

            if (trigger > pixel) {
                if (isAGA) {
                    if (isDualPlayfield(currentBplcon0)) translateDPFAga(pixel, trigger, currentBplcon2, currentBplcon3);
                    else                                 translateSPFAga(pixel, trigger, currentBplcon2);
                } else if (isDualPlayfield(currentBplcon0)) {
                    translateDPF(pixel, trigger, currentBplcon2);
                } else {
                    translateSPF(pixel, trigger, currentBplcon2, deniseBpu(currentBplcon0));
                }
                pixel = trigger;
            }
            if (trigger >= HPIXELS) break;

            if (nextCon == trigger) {
                RegChange c = conChanges.elements[conIdx++];
                if (c.reg == 0x100) {
                    int old = currentBplcon0;
                    currentBplcon0 = c.value;
                    // A re-selection is requested by a change of resolution, number of
                    // planes or mode. BPLCON2 does not appear because on OCS its only effect
                    // on the mode is KILLEHB, an ECS bit.
                    if (deniseBpu(old) != deniseBpu(currentBplcon0)
                            || ((old ^ currentBplcon0) & 0x8000) != 0
                            || bplMode(old) != bplMode(currentBplcon0)) {
                        selectLts(deniseBpu(currentBplcon0), armedCnt, armedsCnt);
                    }
                } else if (c.reg == 0x104) {
                    currentBplcon2 = c.value;
                } else if (c.reg == 0x106) {
                    currentBplcon3 = c.value;
                }
            } else {
                int key = sprStateEvtKey[sprIdx++];
                boolean up = (key & SPR_EVT_UP) != 0;
                if ((key & SPR_EVT_ARMEDS) != 0) {
                    armedsCnt += up ? 1 : -1;
                    // End of the display window of the last sprite that was drawing. What
                    // counts here is NOT how many sprites are armed, only that none is
                    // drawing any more. No need to check `spriteLtsSelected`: a sprite cannot
                    // reach the drawing state without an earlier re-selection having seen it
                    // armed.
                    if (!up && armedsCnt == 0) {
                        selectLts(deniseBpu(currentBplcon0), armedCnt, armedsCnt);
                    }
                } else {
                    armedCnt += up ? 1 : -1;
                    if (up && armedCnt == 1 && !spriteLtsSelected) {
                        selectLts(deniseBpu(currentBplcon0), armedCnt, armedsCnt);
                    } else if (!up && armedCnt == 0 && armedsCnt == 0 && spriteLtsSelected) {
                        selectLts(deniseBpu(currentBplcon0), armedCnt, armedsCnt);
                    }
                }
            }
        }

        conChanges.clear();
    }

    /**
     * Denise's mode as a priority chain. Used only to know whether a BPLCON0 change
     * re-selects the rendering configuration.
     */
    private static int bplMode(int bplcon0) {
        if (isHAM(bplcon0))           return 3;
        if (isDualPlayfield(bplcon0)) return 2;
        if (isEHB(bplcon0))           return 1;
        return 0;
    }

    /**
     * Single playfield: the sprite/playfield priority uses the PF2P bits (bplcon2 bits 3-5),
     * NOT PF1P. In single-playfield mode the playfield counts as "playfield 2" for its
     * priority against the sprites. With BPLCON2 where PF1P != PF2P (e.g. the Ultima VI menu,
     * BPLCON2=0x0008 -> PF1P=0, PF2P=1) the sprite cursor must stay IN FRONT of the
     * playfield.
     */
    private void translateSPF(int from, int to, int bplcon2, int planesNow) {
        int plf2pri = (bplcon2 >> 3) & 7;
        int zpf2    = zPF(plf2pri);
        // The "SWIV" rule lives here: single playfield, at least 5 planes and PF2P >= 5. The
        // dual-playfield twin is in translateDPF. See deniseSpecials().
        //
        // The two conditions are of a different nature and must be kept apart: the LIVE
        // plane count and PF2P decide whether the special applies, while the planes of the
        // current rendering configuration (renderPlanes) gate the routine.
        boolean specials = planesNow >= 5 && plf2pri >= 5 && renderPlanes >= 5;
        for (int i = from; i < to && i < mBuffer.length; i++) {
            int s = mBuffer[i] & 0x3F;
            if (specials) {
                s = deniseSpecials(s, renderPlanes, false, bplcon2 & 7, plf2pri);
                mBuffer[i] = (byte) s;
            }
            zBuffer[i] = (s != 0) ? zpf2 : 0;
        }
    }

    /**
     * Dual playfield: separates the even (PF1) and odd (PF2) bits and resolves priority from
     * bplcon2.
     */
    private void translateDPF(int from, int to, int bplcon2) {
        boolean pf2pri  = (bplcon2 & 0x0040) != 0;
        int     plf1pri = bplcon2 & 7;
        int     plf2pri = (bplcon2 >> 3) & 7;
        int     zpf1    = zPF(plf1pri);
        int     zpf2    = zPF(plf2pri);

        for (int i = from; i < to && i < mBuffer.length; i++) {
            int s    = dBuffer[i] & 0x3F;
            // Bits 0,2,4 -> PF1 (odd planes); bits 1,3,5 -> PF2 (even planes)
            int idx1 = (s & 1) | ((s & 4) >> 1) | ((s & 16) >> 2);
            int idx2 = ((s & 2) >> 1) | ((s & 8) >> 2) | ((s & 32) >> 3);

            // The COLOUR comes from the table shared with HAM+DPF, which already reads bits
            // 6/7 of the transparent playfield; the PRIORITY (and so the z for the sprites)
            // is still decided by the real planes, because a transparent playfield still
            // covers the one behind it.
            int pv  = deniseSpecials(s, renderPlanes, true, plf1pri, plf2pri);
            byte colour = (byte) dualPlayfieldIndex(pv, pf2pri);

            if (idx1 != 0) {
                if (idx2 != 0) {
                    // Both playfields visible: the configured priority wins
                    if (pf2pri) { mBuffer[i] = colour; zBuffer[i] = zpf2 | 0x05; }
                    else        { mBuffer[i] = colour; zBuffer[i] = zpf1 | 0x04; }
                } else {
                    mBuffer[i] = colour;              zBuffer[i] = zpf1 | 0x02;
                }
            } else {
                if (idx2 != 0) { mBuffer[i] = colour; zBuffer[i] = zpf2 | 0x03; }
                else           { mBuffer[i] = 0;      zBuffer[i] = 0x01; }
            }
        }
    }

    /**
     * Single playfield on Lisa: eight planes and no undocumented specials. The index stays
     * raw here; the colour xor of BPLCON4 is applied when it is coloured.
     */
    private void translateSPFAga(int from, int to, int bplcon2) {
        final int zpf2 = zPF((bplcon2 >> 3) & 7);
        for (int i = from; i < to && i < mBuffer.length; i++) {
            zBuffer[i] = mBuffer[i] != 0 ? zpf2 : 0;
        }
    }

    /** Offsets of the second playfield in the palette, by PF2OF (BPLCON3 bits 10-12). */
    private static final int[] PF2_OFFSETS = { 0, 2, 4, 8, 16, 32, 64, 128 };

    /**
     * Dual playfield on Lisa: four planes per playfield (the odd ones are playfield 1), and the
     * colour of playfield 2 is its index plus the offset PF2OF selects, 8 by default. Priority
     * against the sprites as in {@link #translateDPF}.
     */
    private void translateDPFAga(int from, int to, int bplcon2, int bplcon3) {
        final boolean pf2pri = (bplcon2 & 0x0040) != 0;
        final int zpf1 = zPF(bplcon2 & 7);
        final int zpf2 = zPF((bplcon2 >> 3) & 7);
        final int ofs = PF2_OFFSETS[(bplcon3 >> 10) & 7];
        for (int i = from; i < to && i < mBuffer.length; i++) {
            final int s = dBuffer[i] & 0xFF;
            final int idx1 = (s & 1) | ((s >> 1) & 2) | ((s >> 2) & 4) | ((s >> 3) & 8);
            final int idx2 = ((s >> 1) & 1) | ((s >> 2) & 2) | ((s >> 3) & 4) | ((s >> 4) & 8);
            if (idx1 != 0) {
                if (idx2 != 0 && pf2pri) {
                    mBuffer[i] = (byte) (idx2 + ofs); zBuffer[i] = zpf2 | 0x05;
                } else {
                    mBuffer[i] = (byte) idx1;
                    zBuffer[i] = idx2 != 0 ? zpf1 | 0x04 : zpf1 | 0x02;
                }
            } else if (idx2 != 0) {
                mBuffer[i] = (byte) (idx2 + ofs); zBuffer[i] = zpf2 | 0x03;
            } else {
                mBuffer[i] = 0; zBuffer[i] = 0x01;
            }
        }
    }

    /**
     * Maps the value of the BPLCON2 PFxP priority bits to the corresponding Z level.
     *
     * The scale uses "0 = highest priority" (like the hardware interrupt levels):
     *   PFP = 0 -> Z_0 (playfield in front of ALL the sprites)
     *   PFP = 1 -> Z_1 (playfield in front of SP2..SP7, behind SP0/SP1)
     *   PFP = 2 -> Z_2 (playfield in front of SP4..SP7, behind SP0..SP3)
     *   PFP = 3 -> Z_3 (playfield in front of SP6/SP7, behind the others)
     *   PFP = 4 -> Z_4 (playfield behind ALL the sprites)
     *
     * E.g. BPLCON2=0x0024 (PFP=4): sprite 0 (the cursor) in front of the text.
     */
    private static int zPF(int prio) {
        switch (prio) {
            case 0: return Z_0;
            case 1: return Z_1;
            case 2: return Z_2;
            case 3: return Z_3;
            case 4: return Z_4;
            default: return 0;
        }
    }

    // =========================================================================
    // Phase 4: scanSprites, shifts the sprite shift registers and emits the pixels
    // =========================================================================

    private void scanSprites() {
        // Start again from the state INHERITED from the previous line: this line's writes
        // have already updated the live registers, but the replay of sprChanges must apply
        // them at their triggers. Restoring the start-of-line snapshot, the segment before
        // the first change uses the old (correct) values and the replay brings the registers
        // back to the end-of-line state. Same as colorize()/palette.
        armed = armedLineStart;
        System.arraycopy(sprposLineStart,  0, sprpos,  0, 8);
        System.arraycopy(sprctlLineStart,  0, sprctl,  0, 8);
        System.arraycopy(sprdataLineStart, 0, sprdata, 0, 8);
        System.arraycopy(sprdatbLineStart, 0, sprdatb, 0, 8);
        if (isAGA) {
            System.arraycopy(sprdataWLineStart, 0, sprdataW, 0, 8);
            System.arraycopy(sprdatbWLineStart, 0, sprdatbW, 0, 8);
        }
        for (int i = 0; i < 8; i++) updateSprHCoords(i);

        sprStateEvtCount = 0;
        armedsLineStart  = 0;
        for (int i = 0; i < 8; i++) {
            if ((ssra[i] | ssrb[i]) != 0) armedsLineStart |= (1 << i);
        }

        updateSpriteBases();
        if (isAGA) {
            updateSpriteResolutionAga();
            // BRDSPRT (BPLCON3 bit 1) with ECSENA: the sprites also show in the border and
            // before the line's first BPL1DAT.
            borderSprite = (initialBplcon3 & 0x0002) != 0 && (initialBplcon0 & 0x0001) != 0;
            for (int pair = 0; pair < 4; pair++) drawSpritePairAga(pair);
        } else {
            for (int pair = 0; pair < 4; pair++) drawSpritePair(pair);
        }

        sortSprStateEvents();
    }

    /**
     * Sorts by pixel the transitions collected pair by pair. They are few (a handful per
     * line at most) and almost always already sorted: a stable insertion sort is enough and
     * allocates nothing.
     */
    private void sortSprStateEvents() {
        for (int i = 1; i < sprStateEvtCount; i++) {
            int pos = sprStateEvtPos[i], key = sprStateEvtKey[i];
            int j = i - 1;
            while (j >= 0 && sprStateEvtPos[j] > pos) {
                sprStateEvtPos[j + 1] = sprStateEvtPos[j];
                sprStateEvtKey[j + 1] = sprStateEvtKey[j];
                j--;
            }
            sprStateEvtPos[j + 1] = pos;
            sprStateEvtKey[j + 1] = key;
        }
    }

    private void drawSpritePair(int pair) {
        int                  s1       = pair * 2;
        int                  s2       = pair * 2 + 1;
        RegChangeRecorder    recorder = sprChanges[pair];

        int     strt    = 0;
        // The start follows the counter of this line, which the POS/CTL writes did not know.
        updateSprHCoords(s1);
        updateSprHCoords(s2);
        int     strt1   = sprhppos[s1];
        int     strt2   = sprhppos[s2];
        // `armed` has already been brought back to the start-of-line state by
        // scanSprites(); the replay below raises/lowers it at the triggers and leaves it, at
        // the end of the loop, in the end-of-line state, which the snapshot will take for
        // the next line.
        boolean a1      = (armed & (1 << s1)) != 0;
        boolean a2      = (armed & (1 << s2)) != 0;


        for (int recIdx = 0; recIdx < recorder.size(); ) {
            int trigger = recorder.keys[recIdx];
            renderSpriteChunk(strt, trigger, pair, strt1, strt2, a1, a2);
            strt = trigger;

            RegChange c    = recorder.elements[recIdx++];
            int       sIdx = (c.reg - 0x140) / 8;
            /*
             * Identifies the sub-register from the offset in the 8-byte block:
             *   offset & 6 == 0 -> POS
             *   offset & 6 == 2 -> CTL
             *   offset & 6 == 4 -> DATA (A)
             *   offset & 6 == 6 -> DATB (B)
             */
            int sub = c.reg & 6;
            if (sub == 4) {                          // DATA
                sprdata[sIdx] = c.value;
                if ((armed & (1 << sIdx)) == 0) {
                    recordSprStateEvt(trigger, sIdx | SPR_EVT_UP);
                }
                armed |= (1 << sIdx);
            } else if (sub == 6) {                   // DATB
                sprdatb[sIdx] = c.value;
            } else if (sub == 0) {                   // POS
                sprpos[sIdx] = c.value;
                updateSprHCoords(sIdx);
                if (sIdx == s1) strt1 = sprhppos[s1]; else strt2 = sprhppos[s2];
            } else {                                 // CTL (sub == 2)
                sprctl[sIdx] = c.value;
                updateSprHCoords(sIdx);
                if (sIdx == s1) strt1 = sprhppos[s1]; else strt2 = sprhppos[s2];
                // A CTL write disarms the sprite for the remainder of this scanline.
                if ((armed & (1 << sIdx)) != 0) {
                    recordSprStateEvt(trigger, sIdx);
                }
                armed &= ~(1 << sIdx);
            }
            a1 = (armed & (1 << s1)) != 0;
            a2 = (armed & (1 << s2)) != 0;
        }
        renderSpriteChunk(strt, HPIXELS, pair, strt1, strt2, a1, a2);
        recorder.clear();
    }

    private void renderSpriteChunk(int from, int to, int pair,
                                   int strt1, int strt2,
                                   boolean armed1, boolean armed2) {
        int     s1  = pair * 2;
        int     s2  = pair * 2 + 1;

        // PERF: when both shift registers of the pair are empty, the ONLY possible effect of
        // the loop over [from,to) is the arming of a sprite (the register loading at its
        // horizontal position strt). Jump straight to the first reachable arming; if there
        // is none, the chunk produces nothing and returns. The reachability condition
        // (parity of strt-from, given the +2 step) replicates the loop EXACTLY: no change in
        // behaviour, only skipped iterations.
        if ((ssra[s1] | ssrb[s1] | ssra[s2] | ssrb[s2]) == 0) {
            int next = Integer.MAX_VALUE;
            if (armed1 && strt1 >= from && strt1 < to && (((strt1 - from) & 1) == 0)) next = strt1;
            if (armed2 && strt2 >= from && strt2 < to && (((strt2 - from) & 1) == 0) && strt2 < next) next = strt2;
            if (next == Integer.MAX_VALUE) return;
            from = next;
        }

        boolean att = (sprctl[s2] & 0x80) != 0;

        for (int hpos = from; hpos < to; hpos += 2) {
            if (hpos == strt1 && armed1) {
                boolean was = (ssra[s1] | ssrb[s1]) != 0;
                ssra[s1] = sprdata[s1]; ssrb[s1] = sprdatb[s1];
                if (((ssra[s1] | ssrb[s1]) != 0) != was) {
                    recordSprStateEvt(hpos, s1 | SPR_EVT_ARMEDS | (was ? 0 : SPR_EVT_UP));
                }
            }
            if (hpos == strt2 && armed2) {
                boolean was = (ssra[s2] | ssrb[s2]) != 0;
                ssra[s2] = sprdata[s2]; ssrb[s2] = sprdatb[s2];
                if (((ssra[s2] | ssrb[s2]) != 0) != was) {
                    recordSprStateEvt(hpos, s2 | SPR_EVT_ARMEDS | (was ? 0 : SPR_EVT_UP));
                }
            }

            int r1 = ssra[s1] | ssrb[s1];
            int r2 = ssra[s2] | ssrb[s2];
            if ((r1 | r2) != 0) {
                // The sprite output exists under the border too; only its display is clipped.
                if (diwOpenSamplePx >= hpos && diwOpenSamplePx <= hpos + 1) {
                    if ((((ssra[s1] >> 15) & 1) | ((ssrb[s1] >> 14) & 2)) != 0) diwOpenSpriteZ |= Z_SP[s1];
                    if ((((ssra[s2] >> 15) & 1) | ((ssrb[s2] >> 14) & 2)) != 0) diwOpenSpriteZ |= Z_SP[s2];
                }
                if (spriteGate.openAt(hpos, SPRITE_ENABLE_OFFSET) && hpos < spriteClipEnd) {
                    if (att) drawAttachedSpritePixel(hpos, s2);
                    else   { drawSpritePixel(hpos, s1); drawSpritePixel(hpos, s2); }
                }
                // Shift of the sprite registers (1 bit per CCK = 2 sub-pixels in HPIXELS)
                ssra[s1] = (ssra[s1] << 1) & 0xFFFF; ssrb[s1] = (ssrb[s1] << 1) & 0xFFFF;
                ssra[s2] = (ssra[s2] << 1) & 0xFFFF; ssrb[s2] = (ssrb[s2] << 1) & 0xFFFF;
                // The sprite stops drawing at the pixel AFTER the one that emptied its shift
                // register: the last pixel out is still the one just shifted. After the shift
                // only what was below bit 15 stays on, so there is no need to re-read the
                // registers.
                if (r1 != 0 && (r1 & 0x7FFF) == 0) recordSprStateEvt(hpos + 2, s1 | SPR_EVT_ARMEDS);
                if (r2 != 0 && (r2 & 0x7FFF) == 0) recordSprStateEvt(hpos + 2, s2 | SPR_EVT_ARMEDS);
            }
        }
    }

    /**
     * First colour register of each sprite's four, and of an attached pair's sixteen. On
     * OCS/ECS 16 + 4 per pair, and 16 for an attached pair. On Lisa the even sprites start at
     * the bank ESPRM selects and the odd ones and the attached pairs at the OSPRM one (BPLCON4
     * bits 4-7 and 0-3, 16 colours each), plus 4 per pair for a plain sprite.
     */
    private final int[] spriteBase = { 16, 16, 20, 20, 24, 24, 28, 28 };
    private int attachedSpriteBase = 16;

    private void updateSpriteBases() {
        if (!isAGA) return;
        final int even = ((initialBplcon4 >> 4) & 15) << 4;
        final int odd = (initialBplcon4 & 15) << 4;
        for (int x = 0; x < 8; x++) spriteBase[x] = ((x & 1) == 0 ? even : odd) + (x & 6) * 2;
        attachedSpriteBase = odd;
    }

    // =========================================================================
    // Sprites on Lisa: data registers up to 64 bits, resolution of their own
    // =========================================================================

    // The data registers left-aligned (bit 63 the first pixel), as the replay holds them and
    // at the start of the line, and the shift registers.
    private final long[] sprdataW = new long[8], sprdatbW = new long[8];
    private final long[] sprdataWLineStart = new long[8], sprdatbWLineStart = new long[8];
    private final long[] ssraW = new long[8], ssrbW = new long[8];
    /** Sub-pixels of the current sprite pixel already shown, per sprite. */
    private final int[] sprPhaseAga = new int[8];
    /** Per sprite: the next shift is the first since its data loaded. */
    private final boolean[] sprFirstStep = new boolean[8];
    /** Lisa with SSCAN2: each sprite's second load position on the line, MIN_VALUE if none. */
    private final int[] sprhppos2 = new int[8];
    /** Sub-pixels per sprite pixel (2 lores, 1 hires and super-hires) and bits per step. */
    private int sprStepSub = 2, sprShiftBits = 1;
    /** Lisa: BRDSPRT is on for the line being rendered. */
    private boolean borderSprite;

    /**
     * The sprite resolution of the line, from SPRES (BPLCON3 bits 6-7): lores, hires or
     * super-hires, and with 0 lores unless the display is super-hires, then hires. A
     * super-hires sprite pixel is half a sub-pixel: every other one is shown.
     */
    private void updateSpriteResolutionAga() {
        int sr = (initialBplcon3 >> 6) & 3;
        if (sr == 0) sr = (initialBplcon0 & 0x0040) != 0 ? 2 : 1;
        sprStepSub = sr == 1 ? 2 : 1;
        sprShiftBits = sr == 3 ? 2 : 1;
    }

    private void drawSpritePairAga(int pair) {
        final int s1 = pair * 2;
        final int s2 = pair * 2 + 1;
        final RegChangeRecorder recorder = sprChanges[pair];
        int strt = 0;
        updateSprHCoords(s1);
        updateSprHCoords(s2);
        int strt1 = sprhppos[s1];
        int strt2 = sprhppos[s2];
        boolean a1 = (armed & (1 << s1)) != 0;
        boolean a2 = (armed & (1 << s2)) != 0;
        for (int recIdx = 0; recIdx < recorder.size(); ) {
            final int trigger = recorder.keys[recIdx];
            renderSpriteChunkAga(strt, trigger, pair, strt1, strt2, a1, a2);
            strt = trigger;
            final RegChange c = recorder.elements[recIdx++];
            final int sIdx = (c.reg - 0x140) / 8;
            final int sub = c.reg & 6;
            if (sub == 4) {
                sprdata[sIdx] = c.value;
                sprdataW[sIdx] = c.wide;
                if ((armed & (1 << sIdx)) == 0) recordSprStateEvt(trigger, sIdx | SPR_EVT_UP);
                armed |= (1 << sIdx);
            } else if (sub == 6) {
                sprdatb[sIdx] = c.value;
                sprdatbW[sIdx] = c.wide;
            } else if (sub == 0) {
                sprpos[sIdx] = c.value;
                updateSprHCoords(sIdx);
                if (sIdx == s1) strt1 = sprhppos[s1]; else strt2 = sprhppos[s2];
            } else {
                sprctl[sIdx] = c.value;
                updateSprHCoords(sIdx);
                if (sIdx == s1) strt1 = sprhppos[s1]; else strt2 = sprhppos[s2];
                if ((armed & (1 << sIdx)) != 0) recordSprStateEvt(trigger, sIdx);
                armed &= ~(1 << sIdx);
            }
            a1 = (armed & (1 << s1)) != 0;
            a2 = (armed & (1 << s2)) != 0;
        }
        renderSpriteChunkAga(strt, HPIXELS, pair, strt1, strt2, a1, a2);
        recorder.clear();
    }

    /** The first sprite load of the pair at or after {@code from} and before {@code to}, or -1. */
    private static int nextSpriteLoad(int from, int to, int strt1, boolean armed1,
                                      int strt2, boolean armed2) {
        int next = Integer.MAX_VALUE;
        if (armed1 && strt1 >= from && strt1 < to) next = strt1;
        if (armed2 && strt2 >= from && strt2 < to && strt2 < next) next = strt2;
        return next == Integer.MAX_VALUE ? -1 : next;
    }

    private void renderSpriteChunkAga(int from, int to, int pair, int strt1, int strt2,
                                      boolean armed1, boolean armed2) {
        final int s1 = pair * 2;
        final int s2 = pair * 2 + 1;
        final boolean att = (sprctl[s2] & 0x80) != 0;
        for (int hpos = from; hpos < to; hpos++) {
            if ((ssraW[s1] | ssrbW[s1] | ssraW[s2] | ssrbW[s2]) == 0) {
                int next = nextSpriteLoad(hpos, to, strt1, armed1, strt2, armed2);
                final int next2 = nextSpriteLoad(hpos, to, sprhppos2[s1], armed1, sprhppos2[s2], armed2);
                if (next < 0 || (next2 >= 0 && next2 < next)) next = next2;
                if (next < 0) return;
                hpos = next;
            }
            if ((hpos == strt1 || hpos == sprhppos2[s1]) && armed1) loadSpriteAga(s1, hpos);
            if ((hpos == strt2 || hpos == sprhppos2[s2]) && armed2) loadSpriteAga(s2, hpos);
            final long r1 = ssraW[s1] | ssrbW[s1];
            final long r2 = ssraW[s2] | ssrbW[s2];
            if ((r1 | r2) == 0) continue;
            if (diwOpenSamplePx == hpos) {
                if ((((ssraW[s1] >>> 63) & 1) | ((ssrbW[s1] >>> 62) & 2)) != 0) diwOpenSpriteZ |= Z_SP[s1];
                if ((((ssraW[s2] >>> 63) & 1) | ((ssrbW[s2] >>> 62) & 2)) != 0) diwOpenSpriteZ |= Z_SP[s2];
            }
            if ((borderSprite || spriteGate.openAt(hpos, SPRITE_ENABLE_OFFSET)) && hpos < spriteClipEnd) {
                if (att) {
                    drawAttachedSpritePixelAga(hpos, s2);
                } else {
                    drawSpritePixelAga(hpos, s1);
                    drawSpritePixelAga(hpos, s2);
                }
            }
            advanceSpriteAga(s1, r1, hpos);
            advanceSpriteAga(s2, r2, hpos);
        }
    }

    private void loadSpriteAga(int x, int hpos) {
        final boolean was = (ssraW[x] | ssrbW[x]) != 0;
        ssraW[x] = sprdataW[x];
        ssrbW[x] = sprdatbW[x];
        sprPhaseAga[x] = 0;
        sprFirstStep[x] = true;
        if (((ssraW[x] | ssrbW[x]) != 0) != was) {
            recordSprStateEvt(hpos, x | SPR_EVT_ARMEDS | (was ? 0 : SPR_EVT_UP));
        }
    }

    private void advanceSpriteAga(int x, long r, int hpos) {
        if (r == 0 || ++sprPhaseAga[x] < sprStepSub) return;
        sprPhaseAga[x] = 0;
        // A super-hires sprite steps one bit after its first pixel, two after the others.
        final int bits = sprFirstStep[x] ? 1 : sprShiftBits;
        sprFirstStep[x] = false;
        ssraW[x] <<= bits;
        ssrbW[x] <<= bits;
        if ((ssraW[x] | ssrbW[x]) == 0) recordSprStateEvt(hpos + 1, x | SPR_EVT_ARMEDS);
    }

    private void drawSpritePixelAga(int hpos, int x) {
        final int col = (int) ((ssraW[x] >>> 63) & 1) | (int) ((ssrbW[x] >>> 62) & 2);
        if (col != 0) {
            emitSpritePixel(hpos, spriteBase[x] | col, Z_SP[x] | SPRITE_EMIT_SINGLE);
            spritePixelsThisFrame[x]++;
            spritesOnLineMask |= (1 << x);
        }
    }

    private void drawAttachedSpritePixelAga(int hpos, int x) {
        final int col = (int) ((ssraW[x - 1] >>> 63) & 1) | (int) ((ssrbW[x - 1] >>> 62) & 2)
                      | (int) ((ssraW[x] >>> 61) & 4) | (int) ((ssrbW[x] >>> 60) & 8);
        if (col != 0) {
            emitSpritePixel(hpos, attachedSpriteBase | col, Z_SP[x & ~1] | SPRITE_EMIT_SINGLE);
            spritePixelsThisFrame[x - 1]++;
            spritePixelsThisFrame[x]++;
            spritesOnLineMask |= (1 << (x - 1)) | (1 << x);
        }
    }

    /**
     * Marks an emission that covers one sub-pixel instead of two; it sits above every depth
     * bit, and {@link #blitSprites()} strips it.
     */
    private static final int SPRITE_EMIT_SINGLE = 1 << 20;

    /** Plain sprite: 2 colour bits (from the MSB of ssrA and of ssrB). */
    private void drawSpritePixel(int hpos, int x) {
        int col = ((ssra[x] >> 15) & 1) | ((ssrb[x] >> 14) & 2);
        if (col != 0) {
            int z    = Z_SP[x];
            int base = spriteBase[x];  // OCS: palette[16..19/20..23/24..27/28..31]
            emitSpritePixel(hpos, base | col, z);
            spritePixelsThisFrame[x] += 2;
            spritesOnLineMask |= (1 << x);
        }
    }

    /** Queues a sprite emission; the depth comparison happens in the blit. */
    private void emitSpritePixel(int hpos, int value, int z) {
        if (sprEmitCount == sprEmitPos.length) {
            sprEmitPos  = java.util.Arrays.copyOf(sprEmitPos,  sprEmitCount * 2);
            sprEmitValZ = java.util.Arrays.copyOf(sprEmitValZ, sprEmitCount * 2);
        }
        sprEmitPos[sprEmitCount]  = hpos;
        sprEmitValZ[sprEmitCount] = (value & 0xFF) | (z << 8);
        sprEmitCount++;
    }

    /**
     * Pours onto {@code mBuffer}/{@code zBuffer} the pixels {@link #scanSprites()} emitted,
     * in the same order and with the same depth comparison: the z always accumulates, the
     * colour wins only over what is further back.
     */
    private void blitSprites() {
        for (int i = 0; i < sprEmitCount; i++) {
            int hpos  = sprEmitPos[i];
            int valz  = sprEmitValZ[i];
            byte val  = (byte) (valz & 0xFF);
            int  z    = (valz >>> 8) & ~SPRITE_EMIT_SINGLE;
            final int last = ((valz >>> 8) & SPRITE_EMIT_SINGLE) != 0 ? hpos : hpos + 1;
            for (int p = hpos; p <= last && p < zBuffer.length; p++) {
                if (z > zBuffer[p]) {
                    mBuffer[p] = val;
                    if (isAGA) sBuffer[p] = 1;
                }
                zBuffer[p] |= z;
            }
        }
        sprEmitCount = 0;
    }

    /**
     * Attached sprite: 4 colour bits (two paired sprites, 15 colours + transparent).
     *
     * The 4-bit code indexes colour registers 16-31 DIRECTLY (one palette shared by every
     * pair), so the base is ALWAYS 16; the per-pair offset 16+(x&6)*2 applies only to plain
     * 2-bit sprites (drawSpritePixel).
     *
     * The depth Z of an attached pair is that of the even sprite (the leader of the pair,
     * the higher Z): Z_SP[x & ~1] gives Z_SP0, Z_SP2, Z_SP4, Z_SP6 for x = 1, 3, 5, 7.
     *
     * @param x  index of the ODD sprite of the pair (1, 3, 5, 7)
     */
    private void drawAttachedSpritePixel(int hpos, int x) {
        // x is the odd sprite of the pair; x-1 the even one
        int col = ((ssra[x-1] >> 15) & 1) | ((ssrb[x-1] >> 14) & 2)
                | ((ssra[x]   >> 13) & 4) | ((ssrb[x]   >> 12) & 8);
        if (col != 0) {
            // Z of the even sprite (the higher priority in the pair)
            int z    = Z_SP[x & ~1];
            // With the per-pair offset, on pairs other than 0+1 the codes 8-11 would land on
            // colours 28-31 instead of 24-27 (e.g. the flesh of Last Battle's hero, colour 27
            // EC8, would become 31 842: an orange sprite instead of skin tone).
            int base = attachedSpriteBase;
            emitSpritePixel(hpos, base | col, z);
            spritePixelsThisFrame[x - 1] += 2;
            spritePixelsThisFrame[x]     += 2;
            spritesOnLineMask |= (1 << (x - 1)) | (1 << x);
        }
    }

    // =========================================================================
    // Phase 5: checkCollisions, updates CLXDAT from zBuffer/dBuffer
    // =========================================================================

    /**
     * A pixel takes part in collisions only if Denise <b>renders</b> it: inside the
     * horizontal window and after the first BPL1DAT of the line. They are the same two bits
     * that hide the sprite (the BPL1DAT gate is the second), and they apply to the playfield
     * too: outside them there is no bitplane pixel to compare, and taking one for a real
     * value creates collisions the hardware does not report; with CLXCON expecting zero on a
     * plane, the border (where {@code dBuffer} is 0) would trigger the comparison over the
     * whole line.
     */
    private boolean collisionActiveAt(int pos) {
        return spriteGate.openAt(pos, SPRITE_ENABLE_OFFSET) && pos < spriteClipEnd
                && bBuffer[pos] == (byte) 0xFF;
    }

    /**
     * The planes the collisions compare (ENBPx) and the values they expect (MVBPx), masked by
     * the enables: six planes from CLXCON, and on Lisa planes 7 and 8 from CLXCON2 (ENBP7-8
     * in bits 6-7, MVBP7-8 in bits 0-1).
     */
    private int collisionEnable() {
        final int e = (clxcon >> 6) & 0x3F;
        return isAGA ? e | (clxcon2 & 0xC0) : e;
    }

    private int collisionMatch() {
        int m = clxcon & 0x3F;
        if (isAGA) m |= (clxcon2 & 0x03) << 6;
        return m & collisionEnable();
    }

    private void checkCollisions() {
        // PERF (bit-identical): skips the collision loops that cannot produce any bit in
        // clxdat. checkS2PCollisions(x) would walk the whole line only to 'continue' if
        // sprite x has no pixels; checkS2SCollisions cannot produce collisions if fewer than
        // two sprite pairs are on the line. The skipped loops do not change clxdat.
        int m = spritesOnLineMask;
        if (m != 0) {
            int pairMask = ((m & 0x03) != 0 ? 1 : 0) | ((m & 0x0C) != 0 ? 2 : 0)
                         | ((m & 0x30) != 0 ? 4 : 0) | ((m & 0xC0) != 0 ? 8 : 0);
            if (Integer.bitCount(pairMask) >= 2) checkS2SCollisions();
            for (int i = 0; i < 8; i++) {
                if ((m & (1 << i)) != 0) checkS2PCollisions(i);
            }
        }
        checkP2PCollisions();
        checkDiwOpeningCollisions();
    }

    /**
     * Where the horizontal display window opens, the sprite output of the pixel before it
     * collides once with a bitplane pixel of value 0, even though that pixel is border and
     * no BPL1DAT has been written yet: sprite against sprite, and sprite against the playfield
     * through CLXCON as for any other pixel. OCS/ECS behaviour, and the only collision that
     * does not need a rendered pixel.
     */
    private void checkDiwOpeningCollisions() {
        if (diwOpenSamplePx >= 0 && diwOpenSpriteZ != 0) {
            collideSpritesWithZeroPixel(diwOpenSpriteZ, (initialBplcon0 & 0x0400) != 0);
        }
    }

    /**
     * Finds the pixel whose sprite output {@link #checkDiwOpeningCollisions()} uses: the one
     * before the first opening of the horizontal display window on this line, or -1.
     */
    private void findDiwOpeningSample() {
        diwOpenSamplePx = -1;
        diwOpenSpriteZ = 0;
        for (int pos = DIW_OPEN_SPRITE_SAMPLE_PX; pos < HPIXELS; pos++) {
            if (bBuffer[pos] == (byte) 0xFF && bBuffer[pos - 1] != (byte) 0xFF) {
                diwOpenSamplePx = pos - DIW_OPEN_SPRITE_SAMPLE_PX;
                return;
            }
        }
    }

    /** Collisions of the sprites present in depth mask {@code z} with a playfield pixel of 0. */
    private void collideSpritesWithZeroPixel(int z, boolean dpf) {
        int z_s01 = Z_SP0 | ((clxcon & 0x1000) != 0 ? Z_SP1 : 0);
        int z_s23 = Z_SP2 | ((clxcon & 0x2000) != 0 ? Z_SP3 : 0);
        int z_s45 = Z_SP4 | ((clxcon & 0x4000) != 0 ? Z_SP5 : 0);
        int z_s67 = Z_SP6 | ((clxcon & 0x8000) != 0 ? Z_SP7 : 0);
        if ((z & z_s01) != 0 && (z & z_s23) != 0) clxdat |= 0x0200;
        if ((z & z_s01) != 0 && (z & z_s45) != 0) clxdat |= 0x0400;
        if ((z & z_s01) != 0 && (z & z_s67) != 0) clxdat |= 0x0800;
        if ((z & z_s23) != 0 && (z & z_s45) != 0) clxdat |= 0x1000;
        if ((z & z_s23) != 0 && (z & z_s67) != 0) clxdat |= 0x2000;
        if ((z & z_s45) != 0 && (z & z_s67) != 0) clxdat |= 0x4000;

        // A pixel value of 0 matches a playfield when the enabled match bits are all 0.
        final int match = collisionMatch();
        final boolean pf1 = (match & 0x55) == 0;
        final boolean pf2 = (match & 0xAA) == 0;
        for (int x = 0; x < 8; x++) {
            if ((z & Z_SP[x]) == 0) continue;
            if ((x & 1) != 0 && (clxcon & (1 << (12 + (x >> 1)))) == 0) continue;
            if (pf2) clxdat |= 1 << (5 + (x >> 1));
            // In single playfield the PF1 comparison is nested in the PF2 one.
            if (pf1 && (dpf || pf2)) clxdat |= 1 << (1 + (x >> 1));
        }
    }

    /**
     * Sprite-sprite: checks collisions between the 4 sprite pairs.
     *
     *  - the sprite-sprite CLXDAT bits are 9..14 (HRM, CLXDAT);
     *  - ODD sprites take part only if their ENSPx bit in CLXCON (bits 12..15) is set; even
     *    sprites always take part.
     */
    private void checkS2SCollisions() {
        int z_s01 = Z_SP0 | ((clxcon & 0x1000) != 0 ? Z_SP1 : 0);
        int z_s23 = Z_SP2 | ((clxcon & 0x2000) != 0 ? Z_SP3 : 0);
        int z_s45 = Z_SP4 | ((clxcon & 0x4000) != 0 ? Z_SP5 : 0);
        int z_s67 = Z_SP6 | ((clxcon & 0x8000) != 0 ? Z_SP7 : 0);
        for (int pos = 0; pos < HPIXELS; pos++) {
            if (!collisionActiveAt(pos)) continue;
            int z = zBuffer[pos];
            if ((z & z_s01) != 0 && (z & z_s23) != 0) clxdat |= 0x0200;  // bit 9
            if ((z & z_s01) != 0 && (z & z_s45) != 0) clxdat |= 0x0400;  // bit 10
            if ((z & z_s01) != 0 && (z & z_s67) != 0) clxdat |= 0x0800;  // bit 11
            if ((z & z_s23) != 0 && (z & z_s45) != 0) clxdat |= 0x1000;  // bit 12
            if ((z & z_s23) != 0 && (z & z_s67) != 0) clxdat |= 0x2000;  // bit 13
            if ((z & z_s45) != 0 && (z & z_s67) != 0) clxdat |= 0x4000;  // bit 14
        }
    }

    /**
     * Sprite-playfield for sprite x.
     *
     * CLXCON (HRM, CLXCON):
     *   bits 11:6 = ENBP6..ENBP1, enable the comparison for each plane (1 = enabled)
     *   bits  5:0 = MVBP6..MVBP1, value expected by the comparison
     *
     * PF1 uses the odd planes  (bits 0,2,4 of dBuffer = BPL1,3,5) -> mask 0x15
     * PF2 uses the even planes (bits 1,3,5 of dBuffer = BPL2,4,6) -> mask 0x2A
     *
     * A PF1 pixel is "on" if:   (dBuffer & enabled1) == (match1 & enabled1)
     * A PF2 pixel is "on" if:   (dBuffer & enabled2) == (match2 & enabled2)
     */
    private void checkS2PCollisions(int x) {
        // Odd sprite: takes part only if its ENSPx bit in CLXCON is set.
        if ((x & 1) != 0 && (clxcon & (1 << (12 + (x >> 1)))) == 0) return;

        // Masks for PF1 (odd planes) and PF2 (even planes). The match value is masked with
        // the enable: otherwise an MVBP bit set on a plane that is NOT enabled would make the
        // comparison impossible.
        int enable   = collisionEnable();
        int match    = collisionMatch();
        int enabled1 = enable & 0x55;   // ENBP of the odd planes (bits 0,2,4,6)
        int enabled2 = enable & 0xAA;   // ENBP of the even planes (bits 1,3,5,7)
        int match1   = match  & 0x55;   // MVBP of the odd planes
        int match2   = match  & 0xAA;   // MVBP of the even planes

        for (int pos = 0; pos < HPIXELS; pos++) {
            if ((zBuffer[pos] & Z_SP[x]) == 0) continue;
            if (!collisionActiveAt(pos)) continue;
            int d = dBuffer[pos] & 0xFF;
            // CLXDAT layout (HRM, CLXDAT):
            //   bits 1-4 = sprite pair (x>>1) vs ODD planes  (PF1)
            //   bits 5-8 = sprite pair (x>>1) vs EVEN planes (PF2)
            if ((d & enabled2) == match2) {
                clxdat |= (1 << (5 + (x >> 1)));
            } else if ((zBuffer[pos] & 0x07) == 0) {
                // Hardware quirk in single playfield: if PF2 does not match, PF1 does not
                // match either. In DPF the two comparisons are PARALLEL, in SPF the PF1 one
                // is NESTED inside the PF2 one; it is decided per pixel, because the Copper
                // switches SPF/DPF WITHIN the line.
                // The marker is not bit 0: translateDPF writes 0x01..0x05, and codes 0x02
                // (only PF1 visible) and 0x04 (both, PF1 wins) have bit 0 CLEAR. Reading only
                // `& 0x01` those pixels would pass for single playfield and get the nesting
                // they must not have. translateSPF never writes the three low bits and the Z
                // constants are all 0x0008 and up, so `& 0x07` is exactly "rendered in DPF".
                continue;
            }
            if ((d & enabled1) == match1) clxdat |= (1 << (1 + (x >> 1)));
        }
    }

    /**
     * Playfield 1 vs playfield 2.
     *
     * A pixel belongs to PF1 if the enabled odd planes match the match value; likewise for
     * PF2 with the even planes. A PF1-PF2 collision happens when the same pixel belongs to
     * both playfields at once.
     */
    private void checkP2PCollisions() {
        // The playfield-playfield collision is bit 0 of CLXDAT (HRM), not bit 14 (which is
        // sprite 4/5 vs 6/7).
        if ((clxdat & 0x0001) != 0) return;  // already detected

        // Masks for PF1 and PF2; the match is masked with the enable
        int enable   = collisionEnable();
        int match    = collisionMatch();
        int enabled1 = enable & 0x55;
        int enabled2 = enable & 0xAA;
        int match1   = match  & 0x55;
        int match2   = match  & 0xAA;

        for (int pos = 0; pos < HPIXELS; pos++) {
            if (!collisionActiveAt(pos)) continue;
            int d = dBuffer[pos] & 0xFF;
            if ((d & enabled1) == match1 && (d & enabled2) == match2) {
                clxdat |= 0x0001;
                return;
            }
        }
    }

    // =========================================================================
    // Phase 6: colorize, turns palette indices into RGB and writes to the display
    // =========================================================================

    private void colorize(int vpos) {
        if (isAGA) {
            colorizeAga();
            return;
        }
        // Start again from the colour state INHERITED from the previous line: this line's
        // writes have already updated the live palette[], but the replay of colChanges must
        // apply them at their triggers. Restoring the start-of-line snapshot, the segment
        // before the first change uses the old (correct) colour and the replay brings
        // palette[] back to the end-of-line state.
        System.arraycopy(paletteLineStart, 0, palette, 0, 32);
        int     pixel   = 0;
        int     hold    = palette[0];
        boolean hamMode = isHamActive(initialBplcon0);
        // EHB also follows BPLCON0 within the line, exactly like HAM. Decided once from
        // initialBplcon0 (the start-of-line snapshot), a line on which the Copper raises BPU
        // to 6 after the start would be coloured ENTIRELY at full brightness: one line per
        // screen, the first of the six-plane block of `BPLPTR/drop6x` and `dropcpu6x`
        // (line 22), $88F instead of $447.
        int curBplcon0 = initialBplcon0;
        boolean killEhb = ehbKilled(initialBplcon2);
        boolean ehbMode = isEHB(curBplcon0) && !killEhb;
        // The DPF bit the HAM decoder needs is the RAW one (bit 10 of BPLCON0), not the one
        // of the priority chain: the decoder looks at it even when the mode is HAM. See
        // dualPlayfieldIndex().
        boolean hamDpf  = (initialBplcon0 & 0x0400) != 0;
        // PF2PRI starts from the start-of-line snapshot and then follows the line's writes,
        // carried by colChanges, where BPLCON2 enters when the bit changes. Required by
        // `BPLCON2/dualpf3` and `dualpf7`, the two DPF+HAM tests in which the Copper lowers
        // PF2PRI in the middle of the screen: without it the band with PF1 in front would
        // start and end one line late.
        boolean hamDpfPri = (initialBplcon2 & 0x0040) != 0;
        // ECS super-hires follows BPLCON0 within the line like HAM and EHB.
        boolean shres = ecsShres && (initialBplcon0 & 0x0040) != 0;
        int     colIdx  = 0;

        while (colIdx < colChanges.size()) {
            int trigger = colChanges.keys[colIdx];
            if (hamMode) hold = colorizeHAM(vpos, pixel, trigger, hold, hamDpf, hamDpfPri);
            else if (shres)   colorizeShresEcs(pixel, trigger);
            else              colorizeNormal(vpos, pixel, trigger, ehbMode, killEhb);
            pixel = trigger;
            RegChange c = colChanges.elements[colIdx++];
            if      (c.reg == 0x100) {
                curBplcon0 = c.value;
                hamMode = isHamActive(c.value);
                ehbMode = isEHB(c.value) && !killEhb;
                hamDpf  = (c.value & 0x0400) != 0;
                shres   = ecsShres && (c.value & 0x0040) != 0;
            }
            else if (c.reg == 0x104)  hamDpfPri = (c.value & 0x0040) != 0;
            else if (c.reg == COLCHANGE_KILLEHB) {
                killEhb = ehbKilled(c.value);
                ehbMode = isEHB(curBplcon0) && !killEhb;
            }
            else if (c.reg >= 0x180)  palette[(c.reg - 0x180) / 2] = c.value & 0xFFF;
        }
        if (hamMode) colorizeHAM(vpos, pixel, HPIXELS, hold, hamDpf, hamDpfPri);
        else if (shres) colorizeShresEcs(pixel, HPIXELS);
        else         colorizeNormal(vpos, pixel, HPIXELS, ehbMode, killEhb);

        colChanges.clear();
    }

    private void colorizeNormal(int vpos, int from, int to, boolean ehb, boolean killEhb) {
        for (int i = from; i < to && i < HPIXELS; i++) {
            // lineIsVBorder -> line with no bitplane output: all border (no sprites)
            // bBuffer[i] == 0xFF -> pixel inside the DIW -> uses mBuffer (final index)
            // bBuffer[i] != 0xFF -> border pixel -> uses borderColor (index 0)
            int cIdx  = lineIsVBorder ? (borderColor & 0x3F)
                      : (bBuffer[i] == (byte) 0xFF) ? (mBuffer[i] & 0x3F) : (bBuffer[i] & 0x3F);
            int color;
            if ((cIdx & 0x20) == 0) {
                color = palette[cIdx];
            } else if (ehb) {
                // Extra Half-Brite: brightness halved on all three channels
                color = palette[cIdx & 0x1F];
                int r = ((color >> 8) & 0xF) >> 1;
                int g = ((color >> 4) & 0xF) >> 1;
                int b =  (color       & 0xF) >> 1;
                color = (r << 8) | (g << 4) | b;
            } else if (killEhb) {
                // KILLEHB: the sixth plane is ignored and the colour is the one of the
                // five low bits.
                color = palette[cIdx & 0x1F];
            } else {
                // The palette index does not wrap to five bits. The chip owns thirty-two
                // colour registers, so a six-bit index outside EHB and HAM addresses a
                // register that does not exist and the pixel is black. A sixth plane can
                // reach here with a low BPU too: the render configuration stays at the
                // last maximum (see renderPlanes) while the shift register finishes
                // emptying, which is what a six-plane dual-playfield line does when it
                // turns into single playfield in its last colour clock.
                color = 0;
            }
            rgbLine[i] = amigaToRGB(color);
        }
    }

    /**
     * Colorize in super-hires on an ECS Denise. A hires pixel of the output is a pair of
     * super-hires pixels, and the pair's four plane bits (see emitGroupShres) select one of
     * sixteen colour registers; the first pixel of the pair shows the upper two bits of each
     * gun of that register, widened to four by repeating them. The border, index 0, is
     * quantised the same way. Sprites keep their full colour.
     */
    private void colorizeShresEcs(int from, int to) {
        for (int i = from; i < to && i < HPIXELS; i++) {
            int cIdx  = lineIsVBorder ? (borderColor & 0x3F)
                      : (bBuffer[i] == (byte) 0xFF) ? (mBuffer[i] & 0x3F) : (bBuffer[i] & 0x3F);
            int color = palette[cIdx & 0x1F];
            if (cIdx < 16) {
                color &= 0xCCC;
                color |= color >> 2;
            } else if (cIdx >= 32) {
                color = 0;
            }
            rgbLine[i] = amigaToRGB(color);
        }
    }

    /**
     * Colorize in HAM (Hold-And-Modify) mode.
     *
     * In HAM the 6 bits of dBuffer mean:
     *   op  = dBuffer[5:4]  (00 = palette, 01 = modify B, 10 = modify R, 11 = modify G)
     *   val = dBuffer[3:0]
     *
     * A sprite whose Z is above that of the current playfield covers the output, but the
     * HAM operation still runs on the bitplane data underneath it, so the colour after the
     * sprite continues from the playfield's, not from the one before the sprite.
     *
     * In the border (bBuffer != 0xFF) the hold register is NOT updated: the bitplanes emit
     * no data there, so no HAM operation happens and hold persists. (The colour shown is
     * always palette[0], but hold is unchanged.)
     *
     * @param hold  last accumulated colour (12-bit 0x0RGB)
     * @return      new value of hold at the end of the segment
     */
    private int colorizeHAM(int vpos, int from, int to, int hold, boolean hamDpf,
                            boolean hamDpfPri) {
        // Z of the current playfield. HAM is single playfield: the priority against the
        // sprites uses PF2P (bits 3-5), as in translateSPF.
        int zpf = zPF((initialBplcon2 >> 3) & 7);

        for (int i = from; i < to && i < HPIXELS; i++) {
            if (lineIsVBorder || bBuffer[i] != (byte) 0xFF) {
                // Border area (horizontal or vertical): shows palette[0] but does NOT update
                // hold. The bitplanes produce no output in the border, so the HAM operation
                // does not run and hold persists.
                rgbLine[i] = amigaToRGB(palette[0]);
                continue;
            }

            // HAM: decode op and val from the bitplane's 6 raw bits
            int s   = dBuffer[i] & 0x3F;
            int op  = (s >> 4) & 3;
            // HAM + dual playfield: the operation is still HAM's (bits 5:4), but the VALUE is
            // no longer `s & 0xF`: it is the index resolved by the PRIORITY of the two
            // playfields. See dualPlayfieldIndex().
            int val = hamDpf ? (dualPlayfieldIndex(s, hamDpfPri) & 0xF) : (s & 0xF);
            int r   = (hold >> 8) & 0xF;
            int g   = (hold >> 4) & 0xF;
            int b   =  hold       & 0xF;
            switch (op) {
                // op 0 picks from the palette: in HAM+DPF the index is the resolved one, not `val`.
                case 0: hold = palette[(hamDpf ? dualPlayfieldIndex(s, hamDpfPri) : val) & 0x1F];
                        break;
                case 1: b = val; hold = (r<<8)|(g<<4)|b;    break; // modify B
                case 2: r = val; hold = (r<<8)|(g<<4)|b;    break; // modify R
                case 3: g = val; hold = (r<<8)|(g<<4)|b;    break; // modify G
            }
            // A sprite in front of the playfield replaces the output only: the HAM operation
            // above has run on the bitplane data all the same. The sprite's Z must be above
            // the current playfield's Z; Integer.highestOneBit(sprBits) is the Z of the
            // highest-priority sprite pixel.
            int sprBits = zBuffer[i] & Z_ALL_SPRITES;
            if (sprBits != 0 && Integer.highestOneBit(sprBits) > zpf) {
                rgbLine[i] = amigaToRGB(palette[mBuffer[i] & 0x1F]);
                continue;
            }
            rgbLine[i] = amigaToRGB(hold);
        }
        return hold;
    }

    // =========================================================================
    // Phase 6 on Lisa: 24-bit colour registers
    // =========================================================================

    /**
     * Planes Lisa composes, from BPLCON0: BPU3 (bit 4) alone selects eight, BPU3 with any of
     * the three BPU bits selects none.
     */
    static int agaPlanes(int bplcon0) {
        if ((bplcon0 & 0x0010) != 0) return (bplcon0 & 0x7000) != 0 ? 0 : 8;
        return (bplcon0 >> 12) & 7;
    }

    /**
     * Lisa's colour mode, a priority chain: HAM, dual playfield, Extra Half-Brite (exactly six
     * planes, neither HAM nor dual playfield, KILLEHB clear), normal.
     */
    private static final int AGA_NORMAL = 0, AGA_EHB = 1, AGA_HAM = 2;

    private static int agaMode(int bplcon0, int bplcon2) {
        if ((bplcon0 & 0x0800) != 0) return AGA_HAM;
        if ((bplcon0 & 0x0400) != 0) return AGA_NORMAL;
        if (agaPlanes(bplcon0) == 6 && (bplcon2 & 0x0200) == 0) return AGA_EHB;
        return AGA_NORMAL;
    }

    /**
     * Colorize on Lisa: the same replay of the line's colour and mode changes as
     * {@link #colorize}, over the 256 registers of 24 bits.
     */
    private void colorizeAga() {
        System.arraycopy(paletteAgaLineStart, 0, paletteAga, 0, 256);
        int pixel = 0;
        int hold = paletteAga[0];
        int curBplcon0 = initialBplcon0;
        int curBplcon2 = initialBplcon2;
        int mode = agaMode(curBplcon0, curBplcon2);
        int xor = initialBplcon4 >>> 8;
        int colIdx = 0;
        while (colIdx < colChanges.size()) {
            int trigger = colChanges.keys[colIdx];
            if (mode == AGA_HAM) hold = colorizeHamAga(pixel, trigger, hold, agaPlanes(curBplcon0), xor);
            else                 colorizeNormalAga(pixel, trigger, mode == AGA_EHB, xor);
            pixel = trigger;
            RegChange c = colChanges.elements[colIdx++];
            if (c.reg >= AbstractDenise.COLCHANGE_AGA) {
                paletteAga[c.reg - AbstractDenise.COLCHANGE_AGA] = c.value;
            } else if (c.reg == 0x100) {
                curBplcon0 = c.value;
                mode = agaMode(curBplcon0, curBplcon2);
            } else if (c.reg == COLCHANGE_KILLEHB) {
                curBplcon2 = c.value;
                mode = agaMode(curBplcon0, curBplcon2);
            } else if (c.reg == 0x10C) {
                xor = (c.value >>> 8) & 0xFF;
            }
        }
        if (mode == AGA_HAM) colorizeHamAga(pixel, HPIXELS, hold, agaPlanes(curBplcon0), xor);
        else                 colorizeNormalAga(pixel, HPIXELS, mode == AGA_EHB, xor);
        colChanges.clear();
    }

    private void colorizeNormalAga(int from, int to, boolean ehb, int xor) {
        final int[] pal = paletteAga;
        for (int i = from; i < to && i < HPIXELS; i++) {
            int cIdx;
            if (sBuffer[i] != 0 && (borderSprite || (!lineIsVBorder && bBuffer[i] == (byte) 0xFF))) {
                rgbLine[i] = pal[mBuffer[i] & 0xFF];
                continue;
            } else if (lineIsVBorder || bBuffer[i] != (byte) 0xFF) {
                cIdx = borderColor & 0xFF;
            } else if (sBuffer[i] != 0) {
                rgbLine[i] = pal[mBuffer[i] & 0xFF];
                continue;
            } else {
                cIdx = (mBuffer[i] & 0xFF) ^ xor;
            }
            if (ehb && (cIdx & 0x20) != 0) {
                rgbLine[i] = (pal[cIdx & 0x1F] >> 1) & 0x7F7F7F;
            } else {
                rgbLine[i] = pal[cIdx];
            }
        }
    }

    /**
     * HAM on Lisa. With six planes bits 5:4 select the operation and the four low bits, doubled
     * into a byte, are the new component; with eight planes bits 1:0 select it and the six high
     * bits replace the top of the component, the two low bits of the held colour staying. The
     * border shows its colour and leaves the held one alone; a sprite in front of the
     * playfield only covers the output, the operation still runs on the bitplane data.
     */
    private int colorizeHamAga(int from, int to, int hold, int planes, int xor) {
        final int[] pal = paletteAga;
        final int zpf = zPF((initialBplcon2 >> 3) & 7);
        final boolean ham8 = planes >= 7;
        for (int i = from; i < to && i < HPIXELS; i++) {
            if (lineIsVBorder || bBuffer[i] != (byte) 0xFF) {
                rgbLine[i] = borderSprite && sBuffer[i] != 0 ? pal[mBuffer[i] & 0xFF] : pal[0];
                continue;
            }
            int s = dBuffer[i] & 0xFF;
            int x = s ^ xor;
            if (ham8) {
                switch (x & 3) {
                    case 0: hold = pal[x >> 2]; break;
                    case 1: hold = (hold & 0xFFFF03) | (s & 0xFC); break;
                    case 2: hold = (hold & 0x03FFFF) | ((s & 0xFC) << 16); break;
                    default: hold = (hold & 0xFF03FF) | ((s & 0xFC) << 8); break;
                }
            } else {
                int v = (s & 0xF) * 0x11;
                switch (x & 0x30) {
                    case 0x00: hold = pal[x & 0x0F]; break;
                    case 0x10: hold = (hold & 0xFFFF00) | v; break;
                    case 0x20: hold = (hold & 0x00FFFF) | (v << 16); break;
                    default:   hold = (hold & 0xFF00FF) | (v << 8); break;
                }
            }
            int sprBits = zBuffer[i] & Z_ALL_SPRITES;
            rgbLine[i] = sprBits != 0 && Integer.highestOneBit(sprBits) > zpf
                    ? pal[mBuffer[i] & 0xFF] : hold;
        }
        return hold;
    }

    /**
     * KILLEHB (BPLCON2 bit 9), an ECS Denise bit: it turns Extra-Half-Brite off and the sixth
     * plane is then ignored (see colorizeNormal).
     */
    private boolean ehbKilled(int bplcon2) {
        return isECS && (bplcon2 & 0x0200) != 0;
    }

    // =========================================================================
    // Reset and outputs
    // =========================================================================

    /** Power-on state of the rendering side. {@code hardware}: see {@link AbstractDenise#reset(boolean)}. */
    void reset(boolean hardware) {
        initialBplcon0 = initialBplcon2 = 0;
        initialBplcon3 = 0x0C00;
        initialBplcon4 = 0x0011;
        clxcon2 = 0;
        hstrtLineStart = 0; hstopLineStart = 0;
        mainStartHstrt = -1; mainStartHstop = -1; mainStartCounter = -1;
        hflop = true;
        borderBufferIsDirty = 0;
        borderColor = 0;
        clxdat = 0; clxcon = 0;
        if (hardware) {
            Arrays.fill(palette, 0);
            Arrays.fill(paletteLineStart, 0);
            Arrays.fill(paletteAga, 0);
            Arrays.fill(paletteAgaLineStart, 0);
        }
        Arrays.fill(sprdata,  0);
        Arrays.fill(sprdatb,  0);
        Arrays.fill(sprpos,   0);
        Arrays.fill(sprctl,   0);
        Arrays.fill(sprhppos, 0);
        Arrays.fill(ssra, 0);
        Arrays.fill(ssrb, 0);
        armed = 0;
        armedLineStart = 0;
        armedsLineStart = 0;
        sprEmitCount = 0;
        sprStateEvtCount = 0;
        deniseMaxPlanes = 0;
        renderPlanes = 0;
        spriteLtsSelected = false;
        Arrays.fill(sprposLineStart,  0);
        Arrays.fill(sprctlLineStart,  0);
        Arrays.fill(sprdataLineStart, 0);
        Arrays.fill(sprdatbLineStart, 0);
        spriteClipEnd   = HPIXELS + 32;
        Arrays.fill(bBuffer, (byte) 0xFF);
        Arrays.fill(mBuffer, (byte) 0);
        Arrays.fill(zBuffer, 0);
        Arrays.fill(rgbLine, 0);
    }

    /** CLXDAT as a read returns it; the read clears the collision bits. */
    int readClxdat() {
        int v = (clxdat & 0x7FFF) | 0x8000;
        clxdat = 0;
        return v;
    }

    /** Clears the sprites' armed state at the start of the next line (debug/harness). */
    void disarmSprites(int mask) {
        armedLineStart &= ~mask;
    }

    int[] rgbLine()                 { return rgbLine; }
    int lastRenderedBplcon0()       { return lastRenderedBplcon0; }
    boolean isLastLineVBorder()     { return lineIsVBorder; }

    /** See {@link AbstractDenise#isPlayfieldAt(int)}. */
    boolean isPlayfieldAt(int rawX) {
        return !lineIsVBorder && rawX >= 0 && rawX < bBuffer.length
                && bBuffer[rawX] == (byte) 0xFF
                && (mBuffer[rawX] & 0x3F) != 0;
    }

    long spritePixelCountLastFrame(int idx) {
        return (idx >= 0 && idx < 8) ? spritePixelsLastFrame[idx] : 0;
    }

    long spritePixelCountThisFrame(int idx) {
        return (idx >= 0 && idx < 8) ? spritePixelsThisFrame[idx] : 0;
    }
}
