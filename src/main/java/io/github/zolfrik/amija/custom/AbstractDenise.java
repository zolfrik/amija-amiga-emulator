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

package io.github.zolfrik.amija.custom;

import io.github.zolfrik.amija.input.GamePort;
import java.util.Arrays;

/**
 * Denise (8362 OCS / 8373 ECS): the display encoder of the Amiga 500.
 *
 * Line-buffered architecture:
 *   fillShiftRegisters -> scanSprites -> translate -> blitSprites -> checkCollisions -> colorize
 *
 * Hardware rules this class relies on:
 *
 *  - Extra Half-Brite is on when BPU = 6 and HAM = 0, with no other condition on OCS/ECS.
 *    Bit 7 of BPLCON0 is GAUD (genlock audio enable), not an EHB flag (HRM, BPLCON0).
 *
 *  - BPLCON1: PF1H (bits 3:0) scrolls the odd planes (BPL1/3/5) and PF2H (bits 7:4) the even
 *    planes (BPL2/4/6). The offset is in HPIXELS (2 in lores, 1 in hires); the shift
 *    registers are filled with |= so adjacent DMA fetches overlap correctly.
 *
 *  - Sprite colours: a plain sprite pair uses palette base 16 + (x & 6) * 2 (x = the odd
 *    sprite), i.e. palette[16..19], [20..23], [24..27], [28..31]; an attached pair indexes
 *    colours 16-31 directly with its 4-bit code. An attached pair takes the depth of its
 *    even sprite, the leader of the pair: Z_SP[x & ~1].
 *
 *  - HAM and sprites: a sprite interrupts HAM only if its Z is above that of the current
 *    playfield (zpf from initialBplcon2); Z_ALL_SPRITES covers every sprite depth. The HAM
 *    hold register is NOT reset in the border: it keeps the last value accumulated in the
 *    active area.
 *
 *  - CLXCON for PF2: enables are {@code (clxcon >> 6) & 0x2A} and match values
 *    {@code clxcon & 0x2A} (bits 1, 3, 5 of the six, i.e. the even planes BPL2/4/6), for
 *    sprite-to-playfield and playfield-to-playfield collisions alike.
 *
 *  - In VBlank the sprite change recorders are only cleared, not consumed, so no state
 *    leaks into the next scanline.
 *
 *  - BPLCON0 ($DFF100) is write-only on OCS. The Kickstart 1.3 code at $FCAF42 does
 *    D0 = BPLCON0; D0 |= $1200; BPLCON0 = D0: returning the shadow value ($0102 after the
 *    graphics init at $FCAFBA) would give $1302 (corrupt screen, black bar); returning 0
 *    gives the right $1200. The same holds for BPLCON1/2 (write-only on OCS) and for the
 *    ECS-only BPLCON3/4 (absent on OCS, read 0).
 *
 *  - DENISEID ($DFF07C) is not implemented on the OCS 8362: see {@link #readRegister}.
 *    The ECS 8373 returns $FFFC.
 *
 * @author alessio.carotenuto
 */
public abstract class AbstractDenise {

    // =========================================================================
    // Architectural constants (PAL)
    // =========================================================================

    /**
     * Width of the line buffer in "maximum resolution" pixels (4 sub-pixels per CCK).
     * Covers the whole line, blanking included.
     */
    public static final int RAW_LINE_PIXELS = 912;   // 4 * 228
    private static final int HPIXELS    = RAW_LINE_PIXELS;
    private static final int HBLANK_MIN = 0x12;

    // =========================================================================
    // Inner classes: RegChange and RegChangeRecorder
    // =========================================================================

    private static final class RegChange {
        int reg;
        int value;
    }

    /**
     * Records the register changes made during the current scanline, with their pixel
     * timestamp, so they are applied in the right order during colorize (essential for
     * mid-line Copper effects).
     */
    private static final class RegChangeRecorder {
        final int[]       keys;
        final RegChange[] elements;
        int               size = 0;

        RegChangeRecorder(int capacity) {
            keys     = new int[capacity];
            elements = new RegChange[capacity];
            for (int i = 0; i < capacity; i++) elements[i] = new RegChange();
        }

        void insert(int key, int reg, int value) {
            if (size < keys.length) {
                keys[size]           = key;
                elements[size].reg   = reg;
                elements[size].value = value;
                size++;
            }
            // Silent drop on overflow: raise the capacity if needed.
        }

        void    clear()   { size = 0; }
        boolean isEmpty() { return size == 0; }
        int     size()    { return size; }
    }

    // =========================================================================
    // Dipendenze
    // =========================================================================

    private AbstractAgnus        agnus;
    private int          field;   // 0 or 1 for interlace

    // =========================================================================
    // Chip ID / ECS
    // =========================================================================

    /** 0xFFFF = OCS (default), 0xFFFC = ECS */
    //private int     deniseId = 0xFFFF;
    // isECS is effectively final: set only by setDeniseId() during setup, before the
    // emulation starts. It cannot be declared final (setDeniseId is a public setter), so
    // the JIT cannot constant-fold it.
    private boolean isECS    = false;

    public void setDeniseId(int id) {
        //this.deniseId = id;
        this.isECS    = (id != 0xFFFF);
    }

    public boolean isECS() { return isECS; }

    // =========================================================================
    // Control registers
    // =========================================================================

    private int bplcon0, bplcon1, bplcon2, bplcon3, bplcon4;

    // Fields derived from bplcon0, recomputed at every bplcon0 write (see
    // updateBplcon0Derived). They avoid redoing Math.min(...) / shifts at every pixel on
    // the bitplane decoding hot path. They depend ONLY on bplcon0.
    private int     bpu   = 0;            // planes DRAWN by Denise: see deniseBpu()
    private boolean hires = false;        // (bplcon0 & 0x8000) != 0

    /**
     * Snapshot of the registers at the start of the current scanline. Used by
     * translate/colorize to stay consistent when the Copper changes the registers mid-line.
     */
    // There is no initialBplcon1: the fine scroll is read live, see drawBitplanesAtPixel.
    // BPLCON0 and BPLCON2 are start-of-line snapshots.
    private int initialBplcon0, initialBplcon2;
    private int lastRenderedBplcon0;

    private int     hstrt,   hstop;
    /**
     * <b>The state of HSTRT/HSTOP at the START of the line; without it a mid-line write
     * would apply backwards.</b> {@link #updateBorderBuffer()} runs at the end of the line,
     * when {@link #hstrt}/{@link #hstop} already hold the <b>new</b> value: starting from
     * those, the pixels <i>before</i> the write would be masked with a coordinate not yet
     * written, and {@link #diwChanges}, which reinserts it at its pixel, could not notice,
     * since it would set a value already in use.
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
    private boolean hflop;
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

    // Game port 1 (mouse) and port 2 (joystick), optional
    private GamePort gamePort1 = null;
    private GamePort gamePort2 = null;

    // =========================================================================
    // Palette (12-bit Amiga: 0x0RGB)
    // =========================================================================

    private final int[] palette = new int[32];
    // State of the colour registers at the START of the current scanline (= end of the
    // previous one). COLOR00..31 writes update palette[] at once (for live reads on ECS),
    // but colorize() must rebuild each segment from the colour INHERITED from the previous
    // line and apply the changes at their triggers. Without this snapshot the segment
    // BEFORE the first change would use the post-write value, and a MOVE COLOR00 in the
    // middle of a visible line (e.g. vAmigaTS synccpu) would colour the whole left part
    // with the new colour. The colour counterpart of initialBplcon0.
    private final int[] paletteLineStart = new int[32];

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
    public int dbgPalette(int i) { return palette[i & 31]; }

    // =========================================================================
    // Shift register bitplane
    // =========================================================================

    private final int[] bpldat     = new int[6];
    private final int[] bpldatPipe = new int[6];  // pipeline stage
    private final int[] shiftReg   = new int[6];
    private boolean     armedOdd, armedEven;

    /**
     * State of the <b>incremental emission</b> of the shift registers, one group per
     * playfield (index 0 = odd planes, 1 = even planes).
     *
     * <p><b>Denise does not draw a word as a block: it shifts it out one pixel at a time, at
     * the step of the LIVE resolution.</b> As a shift counter:
     * <pre>
     * bplshiftcnt[0] += res_size;                           // 1 in lores, 2 in hires
     * if (bplshiftcnt[0] &gt;= 4) { bplshiftcnt[0] = 0; shiftbpl6(); }
     * </pre>
     *
     * <p><b>What it changes</b>: a block model writes the 16 pixels of the word all at once,
     * at the draw CCK, with a single {@code pixScale = hires ? 1 : 2}, and <b>cannot</b>
     * express a change of step in the middle of a word. If BPLCON0 switches while the word
     * is shifting out, the remaining bits must leave at the new step: the word ends in half
     * the time and the rest is black. That is the bank
     * {@code Denise/Registers/BPLCON0/hilo}.
     *
     * <p><b>At constant resolution the incremental emission is bit-identical to the
     * block</b>: 16 bits x 2 sub-pixels = 32 entries = 8 CCK in lores, 16 entries = 4 CCK in
     * hires, the same indices and the same values.
     *
     * <p><b>Only {@code hires} is live.</b> {@code bpu}, dual playfield and the fine scroll
     * are <b>latched at load</b>.
     */
    private final int[]  srCount  = new int[2];   // bits still to shift out (0..16)
    private final int[]  srCursor = new int[2];   // next dBuffer index to write
    private final int[]  srPhase  = new int[2];   // sub-pixels of the current bit already emitted
    private final int[]  srBpu    = new int[2];   // BPU latched at load
    private final byte[][] srSlice = new byte[2][16]; // precomputed slices, one per bit

    // =========================================================================
    // Sprite state
    // =========================================================================

    private final int[] sprdata  = new int[8];
    private final int[] sprdatb  = new int[8];
    private final int[] sprpos   = new int[8];
    private final int[] sprctl   = new int[8];
    private final int[] sprhppos = new int[8];  // posizione H precalcolata in pixel
    private final int[] ssra     = new int[8];  // shift register A per sprite
    private final int[] ssrb     = new int[8];  // shift register B per sprite
    private int armed;                 // bitmask of the armed sprites
    private int spriteClipEnd;

    /*
     * BPL1DAT does not only arm the bitplanes, it also unblocks the sprites. A sprite comes
     * out only when two conditions hold: it is inside the horizontal window (the bBuffer mask
     * in colorize) and the BPL1DAT gate below is open.
     */

    /** The pixels of the BPL1DAT writes of the line, in the order they came. */
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
        final int lineSteps = agnus != null ? agnus.getMaxHpos() : 454;
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
     * dBuffer: raw bitplane colour index (before priority).
     * bBuffer: border/DIW mask. 0xFF = inside DIW, otherwise the border colour.
     * iBuffer: future use (interleaved pixels / ECS).
     * mBuffer: final colour index after priority resolution.
     * zBuffer: depth buffer (higher value = closer to the viewer).
     */
    private final byte[] dBuffer = new byte[HPIXELS + 64 + 8];
    private final byte[] bBuffer = new byte[HPIXELS + 64 + 8];
    private final byte[] iBuffer = new byte[HPIXELS + 64 + 8];
    private final byte[] mBuffer = new byte[HPIXELS + 64 + 8];
    private final int[]  zBuffer = new int [HPIXELS + 64 + 8];
    private final int[]  rgbLine = new int[HPIXELS];

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

    // =========================================================================
    // Change recorder (for mid-scanline Copper writes)
    // =========================================================================

    /**
     * Delay, in pixels, with which a <b>BPLCON</b> register acts inside Denise: <b>one
     * CCK</b>.
     *
     * <p><b>It is not a bus delay: it is the STAGE of Denise's RGA pipe.</b> Within one CCK
     * the slots are read at two different indices:
     * <pre>
     *   idx0 = i + startpos - 0   -> colours (COLORxx)
     *   idx1 = i + startpos - 1   -> BPLCON0/1/2/3, BPLxDAT, DIWSTRT...
     * </pre>
     * Colours act in the CCK in which they are written, BPLCONs <b>one CCK later</b>. It is a
     * <b>relative</b> difference, so it shows only where the same line has both.
     *
     * <p>Measured on `Denise/Sprites` (`general/sprites1-4`, `reenable1`,
     * `spritedma/sprdma1-3`): the Copper switches dual playfield with BPLCON0 at hpos `$51`,
     * `$61`, `$71`... and without the stage the colour transition fell <b>4 px = 1 CCK</b> too
     * far left, always, eight tests and 48-56 px each.
     *
     * <p>The same stage also carries BPLCON1, BPLxDAT and DIWSTRT/DIWSTOP, but those three
     * have their own calibrated models (the scroll split between Agnus and Denise, BPL1DAT as
     * the display trigger, `DIW_AXIS_SHIFT`) and were <b>not</b> moved: each would need its
     * own measurement.
     */
    private static final int CON_DELAY_PX = 4;

    private final RegChangeRecorder  conChanges = new RegChangeRecorder(128);
    private final RegChangeRecorder  diwChanges = new RegChangeRecorder(128);
    private final RegChangeRecorder  colChanges = new RegChangeRecorder(1024);
    private final RegChangeRecorder[] sprChanges = new RegChangeRecorder[4];

    // =========================================================================
    // Constructor and reset
    // =========================================================================

    protected AbstractDenise() {
        for (int i = 0; i < 4; i++) sprChanges[i] = new RegChangeRecorder(128);
        reset();
    }

    public void setAgnus(AbstractAgnus agnus)            { this.agnus   = agnus;   }
    public void setField(int field)               { this.field   = field;   }

    private void updateBplcon0Derived() {
        bpu   = deniseBpu(bplcon0);
        hires = (bplcon0 & 0x8000) != 0;
    }

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
     * see {@link #deniseSpecials}.
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

    /**
     * Number of planes <b>Denise</b> composes, from BPLCON0.
     *
     * <p><b>More than four planes in HIRES is not a legal configuration on OCS/ECS: Denise
     * draws NONE of them.</b> It is not a clamp to four: the image disappears entirely and
     * the background remains. (SuperHires with more than two planes behaves the same, but is
     * not modelled.) The Agnus counterpart is {@code AbstractAgnus.bitplaneDmaPlaneLimit}:
     * the two chips apply the same threshold, each on its own.
     *
     * <p>The clamp to 6 concerns only BPU=7 in lores: the shift register has six planes, and
     * seven planes in lores are Extra Half-Brite anyway, i.e. the same picture as six.
     */
    private static int deniseBpu(int bplcon0) {
        int planes = (bplcon0 >> 12) & 7;
        if ((bplcon0 & 0x8000) != 0 && planes > 4) return 0;
        return Math.min(planes, 6);
    }

    public void reset() { reset(true); }

    /**
     * Denise reset. {@code hardware} separates power-on from the reset the CPU's RESET
     * instruction brings to the peripherals: the <b>colour registers</b> are not on that line
     * and survive a software reset, as on the hardware. Measured on the demo `Jesus On E's`,
     * which reboots to load its second part: after the RESET the screen keeps the program's
     * COLOR00 (`$800`) until the Kickstart writes its own `$444`, while clearing the palette
     * would make it black. See docs/vamigats-import-order.md section 26.
     */
    public void reset(boolean hardware) {
        bplcon0 = bplcon1 = bplcon2 = 0;
        updateBplcon0Derived();
        bplcon3 = 0x0C00;
        bplcon4 = 0x0011;
        initialBplcon0 = initialBplcon2 = 0;
        hstrt = 0; hstop = 0;
        hstrtLineStart = 0; hstopLineStart = 0;
        mainStartHstrt = -1; mainStartHstop = -1; mainStartCounter = -1;
        hflop = true;
        borderBufferIsDirty = 0;
        borderColor = 0;
        clxdat = 0; clxcon = 0;
        if (hardware) {
            Arrays.fill(palette,  0);
            Arrays.fill(paletteLineStart, 0);
        }
        Arrays.fill(bpldat,   0);
        Arrays.fill(bpldatPipe, 0);
        Arrays.fill(shiftReg, 0);
        armedOdd = armedEven = false;
        srCount[0] = srCount[1] = 0;
        srPhase[0] = srPhase[1] = 0;
        srCursor[0] = srCursor[1] = 0;
        srBpu[0] = srBpu[1] = 0;
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
        Arrays.fill(dBuffer, (byte) 0);
        Arrays.fill(bBuffer, (byte) 0xFF);
        Arrays.fill(iBuffer, (byte) 0);
        Arrays.fill(mBuffer, (byte) 0);
        Arrays.fill(zBuffer, 0);
        Arrays.fill(rgbLine, 0);
        conChanges.clear();
        diwChanges.clear();
        colChanges.clear();
        for (int i = 0; i < 4; i++) sprChanges[i].clear();
    }

    // =========================================================================
    // Register access
    // =========================================================================

    /**
     * True while Agnus transfers a register write in the CCK whose Denise slot on the RGA
     * bus is already taken by the horizontal strobe or by a bitplane or sprite data transfer:
     * that transfer overwrites the slot, so the write never reaches Denise. See
     * {@link AbstractAgnus} handleCpuDataTransfer.
     */
    private boolean rgaSlotTaken;

    void setRgaSlotTaken(boolean taken) {
        rgaSlotTaken = taken;
    }

    /**
     * Denise's horizontal counter at the start of the head of a line. It is a free-running 9-bit
     * counter, two steps per CCK, that only a strobe resets to 2: OCS Denise on STRHOR and
     * STRVBL, ECS Denise on every strobe. A line whose strobe does not reset it starts where the
     * counter got to, one line length further on. The strobe comes at the end of a line, so this
     * is the head of the next line from then on; the line it ended keeps its own in
     * {@link #hcounterRenderHead}, which is what renders it.
     */
    private int hcounterHead = 2;
    private int hcounterRenderHead = 2;

    /**
     * The strobe of the line's first refresh slot, 0 when the slot carried none; it sets the
     * counter for the head of the next line. {@code lineSteps} is the length of the line in
     * counter steps.
     */
    void receiveLineStrobe(int reg, int lineSteps) {
        hcounterRenderHead = hcounterHead;
        final boolean reset = reg == 0x03C || reg == 0x03A || (isECS && reg == 0x038);
        hcounterHead = reset ? 2 : (hcounterHead + lineSteps) & 0x1FF;
    }

    /** See {@link #hcounterHead}. */
    int hcounterHead() {
        return hcounterHead;
    }

    /** See {@link #hcounterRenderHead}. */
    int hcounterRenderHead() {
        return hcounterRenderHead;
    }

    /** The BPLCON1 value Denise holds, the scroll delay of both playfields. */
    int bplcon1() {
        return bplcon1;
    }

    public void writeRegister(int address, int value) {
        if (rgaSlotTaken) {
            return;
        }
        // Register changes map to pixels as 4*(h - HBLANK_MIN), where h is the WHOLE DMA
        // cycle (CCK, 0..227), with no sub-CCK resolution. hpos has half-CCK granularity
        // (0..454, 2 per CCK) and CPU writes to a custom register complete on the ODD
        // sub-cycle of the CCK, so hpos*2 would be +2 px: the write is quantised to the CCK
        // boundary (cck = hpos/2). Copper writes are already on even hpos, so for them the
        // quantisation is a no-op.
        int cck   = (agnus != null) ? agnus.getHpos() >> 1 : 0;
        int rawPixel = cck * 4 - 4 * HBLANK_MIN;
        // The horizontal window comparator also runs before HBLANK_MIN, at the head of
        // the line (see updateBorderBuffer): the DIW writes keep their real position there.
        int reg = address & 0x1FE;
        boolean diw = reg == 0x08E || reg == 0x090 || reg == 0x1E4;
        writeRegisterAtPixel(address, value, diw ? rawPixel : Math.max(0, rawPixel));
    }

    public void writeRegisterAtPixel(int address, int value, int pixel) {
        address &= 0x1FE;

        switch (address) {

            // --- DIW ---
            case 0x08E: // DIWSTRT
                setHSTRT(value & 0xFF, pixel);
                break;
            case 0x090: // DIWSTOP
                // hstop values 0x00-0x7F are taken as 0x100-0x17F (bit 8 is implicit, it
                // extends the range past 256 pixels).
                setHSTOP((value & 0xFF) | 0x100, pixel);
                break;
            case 0x1E4: // DIWHIGH (D5, ECS): H8 of HSTART (bit 5) and HSTOP (bit 13).
                // On ECS it extends the horizontal range of the display window past the OCS
                // limits; the V part is handled by Agnus. No-op on OCS. The value arrives
                // already masked (0x2727) by AbstractAgnus.writeDIWHIGH.
                if (isECS) {
                    setHSTRT((hstrt & 0xFF) | ((value & 0x0020) != 0 ? 0x100 : 0), pixel);
                    setHSTOP((hstop & 0xFF) | ((value & 0x2000) != 0 ? 0x100 : 0), pixel);
                }
                break;

            // --- Collision ---
            case 0x098: clxcon = value; break;

            // --- JOYTEST ($DFF036) ---
            // Presets the quadrature counters of both ports. The 6 high bits of each byte
            // overwrite the counter; the 2 low bits (instantaneous quadrature) are kept.
            case 0x036:
                if (gamePort1 != null) gamePort1.pokeJoytest(value);
                if (gamePort2 != null) gamePort2.pokeJoytest(value);
                break;

            // --- BPLCON ---
            case 0x100: // BPLCON0
                conChanges.insert(pixel + CON_DELAY_PX, address, value);
                // Change of COLOUR mode: tracked in colChanges for colorize. There are TWO,
                // not only HAM: Extra Half-Brite also turns on and off within the line (BPU
                // going to 6, or leaving an illegal hires configuration), and without this
                // insert the line on which the Copper raises BPU to 6 would be coloured at
                // full brightness throughout (line 22 of `BPLPTR/drop6x` and `dropcpu6x`).
                if (isHamActive(bplcon0) != isHamActive(value) || isEHB(bplcon0) != isEHB(value)) {
                    // Same stage as the conChanges above: HAM and EHB come from Denise's
                    // copy of BPLCON0, not from the early (colour) stage.
                    colChanges.insert(pixel + CON_DELAY_PX, address, value);
                }
                bplcon0 = value;
                updateBplcon0Derived();
                break;
            case 0x102: // BPLCON1 (scroll, effective at once)
                bplcon1 = value & 0xFF;
                break;
            case 0x104: // BPLCON2 (priority)
                conChanges.insert(pixel, address, value);
                // Under HAM+DPF the priority does NOT go through conChanges: the HAM decoder
                // picks the playfield itself, and would read PF2PRI from the start-of-line
                // snapshot, so a write within the line would arrive one line late. Same shape
                // as HAM/EHB above: recorded only when the bit really changes, so nothing is
                // split where the priority is steady.
                if (((bplcon2 ^ value) & 0x0040) != 0) {
                    colChanges.insert(pixel, address, value);
                }
                // KILLEHB changes the colour mode on the BPLCON stage, like HAM and EHB from
                // BPLCON0 above.
                if (isECS && ((bplcon2 ^ value) & 0x0200) != 0) {
                    colChanges.insert(pixel + CON_DELAY_PX, COLCHANGE_KILLEHB, value);
                }
                bplcon2 = value;
                break;
            case 0x106: bplcon3 = value; break;  // ECS
            case 0x10C: bplcon4 = value; break;  // ECS

            // --- BPLxDAT ---
            case 0x110: case 0x112: case 0x114:
            case 0x116: case 0x118: case 0x11A:
                setBPLxDAT((address - 0x110) / 2, value, pixel);
                break;

            // --- SPRxPOS ---
            case 0x140: case 0x148: case 0x150: case 0x158:
            case 0x160: case 0x168: case 0x170: case 0x178:
                pokeSPRxPOS((address - 0x140) / 8, value, pixel);
                break;

            // --- SPRxCTL ---
            case 0x142: case 0x14A: case 0x152: case 0x15A:
            case 0x162: case 0x16A: case 0x172: case 0x17A:
                pokeSPRxCTL((address - 0x142) / 8, value, pixel);
                break;

            // --- SPRxDATA ---
            case 0x144: case 0x14C: case 0x154: case 0x15C:
            case 0x164: case 0x16C: case 0x174: case 0x17C:
                pokeSPRxDATA((address - 0x144) / 8, value, pixel);
                break;

            // --- SPRxDATB ---
            case 0x146: case 0x14E: case 0x156: case 0x15E:
            case 0x166: case 0x16E: case 0x176: case 0x17E:
                pokeSPRxDATB((address - 0x146) / 8, value, pixel);
                break;

            case 0x038: case 0x03A: case 0x03C: case 0x03E:
                // Strobe registers: no effect in this model unless light pen or similar is used
                break;

            default:
                // COLOR00..COLOR31
                if (address >= 0x180 && address <= 0x1BE) {
                    int ci = (address - 0x180) / 2;
                    palette[ci] = value & 0xFFF;
                    colChanges.insert(pixel, address, value);
                }
                break;
        }
    }

    public int readRegister(int address) {
        address &= 0x1FE;
        switch (address) {
            // JOY0DATR / JOY1DATR: the port's quadrature counters. They do not depend on what
            // is plugged in: an empty port reads its own counters, not 0 (see GamePort). The
            // 0 below applies only to a machine built WITHOUT that port, which the A500 is
            // not.
            case 0x00A: return (gamePort1 != null ? gamePort1.joydat() : 0) & 0xFFFF;
            case 0x00C: return (gamePort2 != null ? gamePort2.joydat() : 0) & 0xFFFF;
            // CLXDAT: bit 15 always 1; a read clears the register
            case 0x00E: { int v = (clxdat & 0x7FFF) | 0x8000; clxdat = 0; return v; }

            // DENISEID ($DFF07C):
            //   ECS Denise 8373: returns $FFFC (ECS chip identifier).
            //   OCS Denise 8362: register not implemented. getLastBusData() is NOT used: the
            //   last data written on the bus (e.g. BPLCON0=$0102) would spoil the Kickstart's
            //   OCS/ECS detection.
            case 0x07C: return isECS ? 0xFFFC : 0xFFFF;

            // BPLCON0 ($DFF100): write-only on OCS.
            //
            //   The Kickstart at $FCAF42 runs:
            //     MOVE.W  BPLCON0, D0   ; reads BPLCON0
            //     ORI.W   #$1200, D0    ; adds the display bits
            //     MOVE.W  D0, BPLCON0   ; writes the result
            //
            //   Returning the shadow value ($0102, written by the init at $FCAFBA) would give
            //     D0 = $0102 | $1200 = $1302  -> wrong BPLCON0
            //   On OCS BPLCON0 is write-only and does not return the shadow value, so
            //     D0 = $0000 | $1200 = $1200  -> right BPLCON0
            //
            // ECS Denise makes every control register readable: it returns the current
            // shadow value.
            case 0x100: return isECS ? bplcon0 : 0;

            // BPLCON1 ($DFF102): write-only on OCS, readable on ECS.
            case 0x102: return isECS ? bplcon1 : 0;

            // BPLCON2 ($DFF104): write-only on OCS, readable on ECS.
            case 0x104: return isECS ? bplcon2 : 0;

            // BPLCON3 ($DFF106): ECS register, absent on OCS.
            case 0x106: return isECS ? bplcon3 : 0;

            // BPLCON4 ($DFF10C): ECS/AGA register, absent on OCS.
            case 0x10C: return isECS ? bplcon4 : 0;

            default:
                // Colour registers ($DFF180-$DFF1BE): readable on ECS Denise; write-only
                // on OCS (the bus returns 0 for registers not implemented for reading).
                if (address >= 0x180 && address <= 0x1BE) {
                    return isECS ? palette[(address - 0x180) / 2] : 0;
                }
                return 0xFFFF;
        }
    }

    // =========================================================================
    // Helpers write register
    // =========================================================================

    /**
     * <b>The two DIW writes are recorded on the COUNTER's axis, not on the pixels'</b>, and
     * the two axes have a different phase: {@code DIW_AXIS_SHIFT} is that phase.
     *
     * <p>The horizontal window comparator is the only consumer in Denise that works not on
     * the pixel index but on {@code counter} (see {@link #updateBorderBuffer()}), and the
     * conversion {@code counter = 2*HBLANK_MIN + i/2} lacked a constant. Measured on the
     * six sweep lines of {@code diwtim1}: at the instant of the <b>same</b> write the
     * reference's horizontal counter is our {@code counter} <b>+ 2</b>, and two counter units
     * are <b>one CCK</b>, i.e. four sub-pixels.
     *
     * <p><b>It is not a delay of the write</b>, and that is the distinction that matters:
     * applying the same shift to <b>every</b> Denise write takes {@code Denise/Registers}
     * (section 16) from 52/16 to <b>27/34</b>; on the pixel axis we are already right. The
     * shift applies to the one consumer that changes axis.
     *
     * <p>Equivalent and wrong: moving the origin of {@code counter}. It would also move
     * <b>where the edges</b> of the window fall, which today are right on every line that is
     * not a transition line.
     */
    private static final int DIW_AXIS_SHIFT = 4;

    private void setHSTRT(int val, int pixel) {
        hstrt = val;
        diwChanges.insert(pixel + DIW_AXIS_SHIFT, 0x08E, val);
        borderBufferIsDirty = Math.max(borderBufferIsDirty, 2);
    }

    private void setHSTOP(int val, int pixel) {
        hstop = val;
        diwChanges.insert(pixel + DIW_AXIS_SHIFT, 0x090, val);
        borderBufferIsDirty = Math.max(borderBufferIsDirty, 2);
    }

    private void setBPLxDAT(int x, int value, int pixel) {
        bpldat[x] = value & 0xFFFF;
        if (x == 0) {
            // BPL1DAT written by the DMA: loads the pipeline and arms the shift registers.
            // BPL1DAT is the trigger because in the DMA cycle it is the last odd plane
            // fetched (Agnus has already written BPL3/5 in the previous cycles).
            System.arraycopy(bpldat, 0, bpldatPipe, 0, 6);
            armedOdd  = true;
            armedEven = true;
            if (bpl1datCount < bpl1datPixels.length) bpl1datPixels[bpl1datCount++] = pixel;
        }
    }

    /**
     * Instant at which a write to SPRxDATA/SPRxDATB acts, in pixels from the CCK boundary.
     * It acts in the early (colour) stage and is applied <b>at once</b>.
     *
     * <p>Measured with a sweep on {@code Denise/Sprites/timing} (POS/CTL decoupled and pinned
     * to its value, otherwise both move):
     * <pre>
     *   SPR_DAT_PX        2  [4]  6   8  10  12
     *   SprTimingTest     3   0   2   3   3   3        reds
     *   ManualTest        1   0   0   1   1   1
     * </pre>
     * 4 closes the whole group, `SprTimingTest` 9/0 and `manual6` with it, with walls on
     * both sides.
     *
     * <p>{@link #SPR_POSCTL_PX} {@code = SPR_DAT_PX + 2}: the relation between the two is
     * fixed, and what the sweep measures is their common offset.
     */
    private static final int SPR_DAT_PX = 4;

    /**
     * Instant at which a write to SPRxPOS/SPRxCTL acts: <b>half a CCK later</b> than
     * SPRxDATA.
     *
     * <p>POS and CTL are <b>not</b> applied at the stage they arrive in: they are set aside
     * and consumed during the per-pixel drawing, in the <b>second half</b> of the colour
     * clock and <b>after</b> the horizontal comparison:
     * <pre>
     *   if (h &amp;&amp; pending_reg) {
     *       matchsprites(cnt &lt;&lt; 2);                    // the match uses the OLD value
     *       sprwrite(pending_reg, pending_val);
     *       pending_reg = 0;
     *   }
     * </pre>
     * SPRxDATA/SPRxDATB have no such detour: they apply at once.
     *
     * <p>The pair is {@code (SPR_DAT_PX, SPR_POSCTL_PX) = (4, 6)}: half a CCK apart. When one
     * constant is expressed in terms of another, sweeping only one cannot tell a wrong
     * offset from a wrong relation: they have to be decoupled and both swept (see
     * {@link #SPR_DAT_PX}).
     *
     * <p>A deadline is a yes/no, not a half-CCK offset. On `sprtimpos1` line 73 the sprite
     * must move to `x = 514`, not stay at `x = 322`; the test's README says so: <i>"When the
     * write happens early enough, the sprite is shifted to the right in the corresponding
     * line"</i>. A pair one CCK late refuses that write.
     */
    private static final int SPR_POSCTL_PX = SPR_DAT_PX + 2;

    private void pokeSPRxPOS(int x, int value, int pixel) {
        sprpos[x] = value;
        updateSprHCoords(x);
        sprChanges[x / 2].insert(pixel + SPR_POSCTL_PX, 0x140 + x * 8, value);
    }

    private void pokeSPRxCTL(int x, int value, int pixel) {
        sprctl[x] = value;
        updateSprHCoords(x);
        // A CTL write disarms the sprite immediately. This prevents stale data from the
        // previous frame rendering on scanlines before VSTART (which would create a vertical
        // line artifact).
        armed &= ~(1 << x);
        sprChanges[x / 2].insert(pixel + SPR_POSCTL_PX, 0x142 + x * 8, value);
    }

    private void pokeSPRxDATA(int x, int value, int pixel) {
        armed    |= (1 << x);
        sprdata[x] = value & 0xFFFF;
        sprChanges[x / 2].insert(pixel + SPR_DAT_PX, 0x144 + x * 8, value);
    }

    private void pokeSPRxDATB(int x, int value, int pixel) {
        sprdatb[x] = value & 0xFFFF;
        sprChanges[x / 2].insert(pixel + SPR_DAT_PX, 0x146 + x * 8, value);
    }

    private void updateSprHCoords(int x) {
        sprhppos[x] = spriteStart(((sprpos[x] & 0xFF) << 1) | (sprctl[x] & 0x01));
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
    // Main rendering pipeline (called by Agnus at every HSYNC)
    // =========================================================================

    /**
     * Main entry point: processes the scanline just completed and writes the pixels to the
     * display.
     *
     * @param vpos  vertical scanline number (0-based)
     */
    public void hsyncHandler(int vpos) {
        lastRenderedBplcon0 = initialBplcon0;
        if (vpos == 0) {
            System.arraycopy(spritePixelsThisFrame, 0, spritePixelsLastFrame, 0, 8);
            Arrays.fill(spritePixelsThisFrame, 0L);
        }
        // rgbLine is not pre-filled here: both branches below already cover the WHOLE
        // rgbLine[0..HPIXELS): the visible branch through colorize() (colorizeNormal/
        // colorizeHAM write every pixel, borders included, because the colChanges segments
        // tile [0,HPIXELS)), the VBlank branch through fillVBlankLine().
        // A word armed at the end of the line cannot shift out completely: the CCKs to do so
        // are gone. Its remaining pixels are written here: it is the tail of the line, past
        // the fetch window. See srCount.
        flushShiftOut();

        updateBorderBuffer();
        computeBpl1datGate();

        if (agnus != null && !agnus.inVBlankArea(vpos)) {
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
        // Targeted clearing on the hot path (hsyncHandler runs ~15,600 times/s):
        //
        //  - dBuffer: written with |= by fillShiftRegisters and read ENTIRELY by translate
        //    (arraycopy dBuffer -> mBuffer). Needs a full clear.
        //  - zBuffer: translate() ASSIGNS (not OR) zBuffer[0..HPIXELS) on every visible line
        //    before any read; the tail [HPIXELS, length) is not touched by translate but is
        //    used by blitSprites (|=) and by the reads, so only the tail is cleared.
        //  - mBuffer: translate() overwrites it ENTIRELY through arraycopy at the start of
        //    the pipeline, before any read: no clear needed.
        //  - iBuffer: "future use", never read or written elsewhere: no clear.
        //
        // In VBlank the buffers are not read (see the else branch above), so leaving
        // [0,HPIXELS) of zBuffer uncleared is safe across the VBlank -> visible line
        // transition too (translate reassigns before reading).
        Arrays.fill(dBuffer, (byte) 0);
        Arrays.fill(zBuffer, HPIXELS, zBuffer.length, 0);
        for (int i = 0; i < 8; i++) { ssra[i] = 0; ssrb[i] = 0; }
        spritesOnLineMask = 0;

        // Sprites can be visible anywhere on the active line; the horizontal position is
        // already filtered by sprhppos (negative when in blanking).
        spriteClipEnd   = HPIXELS + 32;

        // Snapshot of the registers: the "initial" state of the scanline about to start,
        // before any update.
        initialBplcon0 = bplcon0;
        initialBplcon2 = bplcon2;
        // Same reasoning as the palette, for the horizontal window: updateBorderBuffer() has
        // just consumed the line, so the live values are those the NEXT line inherits. See
        // hstrtLineStart.
        hstrtLineStart = hstrt;
        hstopLineStart = hstop;
        // Snapshot of the colour registers at the END of the line (= start of the next): in
        // the visible branch colorize() has just brought palette[] back to the end-of-line
        // state; in the VBlank branch palette[] already holds the live values. Either way it
        // is the state the next scanline inherits.
        System.arraycopy(palette, 0, paletteLineStart, 0, 32);
        // Same reasoning for the sprite registers: in the visible branch scanSprites() has
        // just brought the live registers back to the end-of-line state by replaying the
        // changes, in the VBlank branch they are already live. Either way it is the state the
        // next scanline inherits. See armedLineStart.
        armedLineStart = armed;
        System.arraycopy(sprpos,  0, sprposLineStart,  0, 8);
        System.arraycopy(sprctl,  0, sprctlLineStart,  0, 8);
        System.arraycopy(sprdata, 0, sprdataLineStart, 0, 8);
        System.arraycopy(sprdatb, 0, sprdatbLineStart, 0, 8);

        diwChanges.clear();
        conChanges.clear();
        colChanges.clear();
    }

    // =========================================================================
    // VBlank fill
    // =========================================================================

    /**
     * Fills a scanline in the VBlank area with the current background colour. Without it,
     * the pixels left uninitialised would show black.
     */
    private void fillVBlankLine(int vpos) {
        int bgRGB    = amigaToRGB(palette[0]);
        Arrays.fill(rgbLine, bgRGB);
    }

    // =========================================================================
    // Phase 1: loading the shift registers and emitting the pixels
    // =========================================================================

    /**
     * <b>Loads</b> the shift registers; nothing is drawn here. The pixels leave through
     * {@link #emitBitplanePixels(int)}, four per CCK, at the step of the live resolution.
     * See {@link #srCount} for why.
     *
     * @param fineOdd  sub-CCK remainder of the odd planes' scroll, in <b>sub-pixels</b>
     *                 (0-3); the coarse part has already been applied by Agnus by delaying
     *                 the draw CCK. The split and its rule are in
     *                 {@code AbstractAgnus.scrollSubPixels}.
     * @param fineEven the same for the even planes. In SPF Agnus passes PF1H here, because
     *                 the even planes scroll with the odd ones.
     */
    public void drawBitplanesAtPixel(int pixel, boolean drawOdd, boolean drawEven,
                                     int fineOdd, int fineEven) {
        // The scroll is read LIVE, not from the start-of-line snapshot: BPLCON1 written
        // within the line acts at once. Read from a snapshot it would arrive one LINE late,
        // as `BPL0DAT/bpl0dat*` shows, where the Copper writes BPLCON1 and BPL1DAT on the
        // same line: the line at scroll 0 would be exact and every other one behind by its
        // own scroll.
        if (drawOdd && armedOdd) {
            loadOddShiftRegisters();
            armShiftOut(0, pixel, fineOdd);
            armedOdd = false;
        }
        if (drawEven && armedEven) {
            loadEvenShiftRegisters();
            armShiftOut(1, pixel, fineEven);
            armedEven = false;
        }
    }

    /**
     * Arms the output of a word: 16 bits to shift out from {@code pixel}, plus the
     * <b>fine</b> scroll, the sub-CCK remainder, already in SUB-PIXELS (0-3). The coarse part
     * was applied by Agnus by delaying the draw CCK; the rule that splits them is in
     * {@code AbstractAgnus.scrollSubPixels}.
     *
     * <p>{@code bpu} and dual playfield are <b>latched here</b>, at load time.
     */
    private void armShiftOut(int grp, int pixel, int fineScroll) {
        final int cursor = pixel + fineScroll;   // fineScroll is already in sub-pixels
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
        srCursor[grp] = cursor;
        srPhase[grp]  = 0;
        srBpu[grp]    = bpu;
        precomputeSlices(grp);
    }

    /**
     * Shifts out the <b>two sub-pixels of this HALF CCK</b> from both groups of shift
     * registers. Called <b>twice per CCK</b> by Agnus: on the rising edge (after any load)
     * and on the falling edge, right after the Copper.
     *
     * <p>A bit lasts <b>two</b> sub-pixels in lores and <b>one</b> in hires, and the
     * resolution is read again at every sub-pixel: here, and only here, a switch in the
     * middle of a word changes the output step.
     *
     * <p><b>Why two half CCKs and not one whole CCK.</b> The <b>resolution</b> part of
     * BPLCON0 enters Denise <b>one stage earlier</b> than the rest of the register, and that
     * stage is <b>half a colour clock</b>, which is measured: emitting four sub-pixels as a
     * block at the top of the CCK put the {@code hilo} switch <b>two sub-pixels late</b> on
     * all twelve lines. Splitting the emission in half lets the Copper write of the current
     * CCK arrive in time for the second half.
     *
     * <p>Splitting it changes neither <b>which</b> sub-pixels are written nor their values:
     * the cursor advances the same way. Only the instant at which {@code hires} is re-read
     * changes.
     */
    public void emitBitplaneHalfCck() {
        if (srCount[0] == 0 && srCount[1] == 0) return;
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
     * <p><b>An optimisation, not a model</b>, possible only because nothing touches
     * {@code shiftReg} between one load and the next: BPLxDAT writes go to
     * {@code bpldat}/{@code bpldatPipe}, and a new load re-arms everything anyway. Without it
     * the emission costs three tests and three shifts <b>per sub-pixel</b> instead of per
     * word: +12.45% on the A/B bench (`tools/ab-bench`, 5/5 in agreement).
     */
    private void precomputeSlices(int grp) {
        final byte[] dst = srSlice[grp];
        final int bpuL = srBpu[grp];
        final int r0, r1, r2, m0, m1, m2;
        if (grp == 0) {
            r0 = bpuL >= 1 ? shiftReg[0] : 0; m0 = 1;
            r1 = bpuL >= 3 ? shiftReg[2] : 0; m1 = 4;
            r2 = bpuL >= 5 ? shiftReg[4] : 0; m2 = 16;
        } else {
            r0 = bpuL >= 2 ? shiftReg[1] : 0; m0 = 2;
            r1 = bpuL >= 4 ? shiftReg[3] : 0; m1 = 8;
            r2 = bpuL >= 6 ? shiftReg[5] : 0; m2 = 32;
        }
        for (int i = 0; i < 16; i++) {
            final int bit = 0x8000 >> i;
            int slice = 0;
            if ((r0 & bit) != 0) slice |= m0;
            if ((r1 & bit) != 0) slice |= m1;
            if ((r2 & bit) != 0) slice |= m2;
            dst[i] = (byte) slice;
        }
    }

    private void emitGroup(int grp, int subPixels) {
        final byte[] slices = srSlice[grp];
        final int keepMask = (grp == 0) ? 0x2A : 0x15;
        final int bitStep  = hires ? 1 : 2;   // sub-pixels per bit, read LIVE
        int count  = srCount[grp];
        int cursor = srCursor[grp];
        int phase  = srPhase[grp];
        // The cursor stays in phase with the CCK: two sub-pixels per half CCK, always.
        for (int i = 0; i < subPixels && count > 0; i++) {
            if (cursor >= 0 && cursor < dBuffer.length) {
                dBuffer[cursor] = (byte) ((dBuffer[cursor] & keepMask) | slices[16 - count]);
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

    private void loadOddShiftRegisters() {
        if (bpu >= 1) shiftReg[0] = bpldatPipe[0];
        if (bpu >= 3) shiftReg[2] = bpldatPipe[2];
        if (bpu >= 5) shiftReg[4] = bpldatPipe[4];
    }

    private void loadEvenShiftRegisters() {
        if (bpu >= 2) shiftReg[1] = bpldatPipe[1];
        if (bpu >= 4) shiftReg[3] = bpldatPipe[3];
        if (bpu >= 6) shiftReg[5] = bpldatPipe[5];
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
            if (counter == hs) hf = true;
            if (counter == he) hf = false;
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
            if (counter == hs) hf = true;
            if (counter == he) hf = false;
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
                if (isDualPlayfield(currentBplcon0)) {
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
        for (int i = 0; i < 8; i++) updateSprHCoords(i);

        sprStateEvtCount = 0;
        armedsLineStart  = 0;
        for (int i = 0; i < 8; i++) {
            if ((ssra[i] | ssrb[i]) != 0) armedsLineStart |= (1 << i);
        }

        for (int pair = 0; pair < 4; pair++) drawSpritePair(pair);

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

    /** Plain sprite: 2 colour bits (from the MSB of ssrA and of ssrB). */
    private void drawSpritePixel(int hpos, int x) {
        int col = ((ssra[x] >> 15) & 1) | ((ssrb[x] >> 14) & 2);
        if (col != 0) {
            int z    = Z_SP[x];
            int base = 16 + (x & 6) * 2;  // palette[16..19/20..23/24..27/28..31]
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
            int  z    = valz >>> 8;
            for (int p = hpos; p <= hpos + 1 && p < zBuffer.length; p++) {
                if (z > zBuffer[p]) mBuffer[p] = val;
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
            int base = 16;
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
        final boolean pf1 = (clxcon & 0x15 & ((clxcon >> 6) & 0x15)) == 0;
        final boolean pf2 = (clxcon & 0x2A & ((clxcon >> 6) & 0x2A)) == 0;
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
        int enabled1 = (clxcon >> 6) & 0x15;   // ENBP of bits 0,2,4
        int enabled2 = (clxcon >> 6) & 0x2A;   // ENBP of bits 1,3,5
        int match1   =  clxcon       & 0x15 & enabled1;   // MVBP of bits 0,2,4
        int match2   =  clxcon       & 0x2A & enabled2;   // MVBP of bits 1,3,5

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
        int enabled1 = (clxcon >> 6) & 0x15;
        int enabled2 = (clxcon >> 6) & 0x2A;
        int match1   =  clxcon       & 0x15 & enabled1;
        int match2   =  clxcon       & 0x2A & enabled2;

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
        int     colIdx  = 0;

        while (colIdx < colChanges.size()) {
            int trigger = colChanges.keys[colIdx];
            if (hamMode) hold = colorizeHAM(vpos, pixel, trigger, hold, hamDpf, hamDpfPri);
            else              colorizeNormal(vpos, pixel, trigger, ehbMode, killEhb);
            pixel = trigger;
            RegChange c = colChanges.elements[colIdx++];
            if      (c.reg == 0x100) {
                curBplcon0 = c.value;
                hamMode = isHamActive(c.value);
                ehbMode = isEHB(c.value) && !killEhb;
                hamDpf  = (c.value & 0x0400) != 0;
            }
            else if (c.reg == 0x104)  hamDpfPri = (c.value & 0x0040) != 0;
            else if (c.reg == COLCHANGE_KILLEHB) {
                killEhb = ehbKilled(c.value);
                ehbMode = isEHB(curBplcon0) && !killEhb;
            }
            else if (c.reg >= 0x180)  palette[(c.reg - 0x180) / 2] = c.value & 0xFFF;
        }
        if (hamMode) colorizeHAM(vpos, pixel, HPIXELS, hold, hamDpf, hamDpfPri);
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
     * Colorize in HAM (Hold-And-Modify) mode.
     *
     * In HAM the 6 bits of dBuffer mean:
     *   op  = dBuffer[5:4]  (00 = palette, 01 = modify B, 10 = modify R, 11 = modify G)
     *   val = dBuffer[3:0]
     *
     * A sprite interrupts HAM only if its Z is above that of the current playfield
     * (Z_ALL_SPRITES detects the sprite bits).
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

            // Is a sprite in front of the playfield? Detecting a sprite (Z_ALL_SPRITES) is
            // not enough: the sprite's Z must be above the current playfield's Z.
            // Integer.highestOneBit(sprBits) is the Z of the highest-priority sprite pixel.
            int sprBits = zBuffer[i] & Z_ALL_SPRITES;
            if (sprBits != 0 && Integer.highestOneBit(sprBits) > zpf) {
                // The sprite is visible above the playfield: show the sprite colour but do
                // NOT update hold (the HAM register is not touched).
                rgbLine[i] = amigaToRGB(palette[mBuffer[i] & 0x1F]);
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
            rgbLine[i] = amigaToRGB(hold);
        }
        return hold;
    }

    // =========================================================================
    // Utility
    // =========================================================================

    /**
     * Precomputed LUT for converting the 12-bit Amiga colour (0x0RGB) to 24-bit RGB.
     *
     * amigaToRGB() is called for every colorized pixel; the LUT (4096 ints = 16 KB, always
     * in L1/L2 cache) replaces 3 shifts and 3 multiplications with a single load. The values
     * replicate the nibble (0xF -> 0xFF).
     */
    private static final int[] AMIGA_RGB_LUT = new int[4096];
    static {
        for (int c = 0; c < 4096; c++) {
            int r = (c >> 8) & 0xF;
            int g = (c >> 4) & 0xF;
            int b =  c       & 0xF;
            AMIGA_RGB_LUT[c] = ((r * 17) << 16) | ((g * 17) << 8) | (b * 17);
        }
    }

    /**
     * LUT used by the vAmigaTS regression tests. Their {@code .raw} references expand the
     * nibble trivially, {@code nibble<<4} (max 0xF0, NOT 0xFF = nibble*0x11), with no gamma
     * and no colour round trip. Enabled only by the tests through
     * {@link #useVAmigaColorLut}; games use the standard LUT. */
    private static final int[] VAMIGA_RGB_LUT = new int[4096];
    static {
        for (int c = 0; c < 4096; c++) {
            int r = (c >> 4) & 0xF0;   // nibble<<4
            int g = (c)      & 0xF0;
            int b = (c << 4) & 0xF0;
            VAMIGA_RGB_LUT[c] = (r << 16) | (g << 8) | b;
        }
    }

    /** If true, {@link #amigaToRGB} uses the test LUT (vAmigaTS regression tests). Default
     *  false: games keep the standard nibble*0x11 LUT (golden CRCs unchanged). */
    public static volatile boolean useVAmigaColorLut = false;

    /** Converts the 12-bit Amiga colour (0x0RGB) to 24-bit RGB (through the LUT). The
     *  & 0xFFF mask keeps only the 12 low bits, the only ones that contribute. */
    private static int amigaToRGB(int c) {
        return (useVAmigaColorLut ? VAMIGA_RGB_LUT : AMIGA_RGB_LUT)[c & 0xFFF];
    }

    /**
     * Computes the effective Y coordinate on the display, taking interlace into account. If
     * LACE (bit 2 of bplcon0) is on, scanlines are doubled and the current field (0 or 1)
     * decides whether even or odd rows are drawn.
     */

    /** HAM is on if bit 11 (HOMOD) of BPLCON0 is set. */
    private static boolean isHAM(int bplcon0) {
        return (bplcon0 & 0x0800) != 0;
    }

    /**
     * HAM <b>really active</b>: the bit is set <i>and</i> there are more than four planes.
     *
     * <p><b>With four planes or fewer HAM is not HAM: it is a normal palette lookup.</b>
     * The same holds for Extra Half-Brite with five planes or fewer.
     *
     * <p>With HAM alone the exception is <b>invisible</b>: with four planes or fewer bits 5:4
     * of the pixel are zero, the HAM operation is always "pick from the palette" and the
     * result is the normal lookup. It shows only when <b>DPF is on too</b>, because there
     * operation 0 would use the index resolved by the priority instead of the raw pixel.
     *
     * <p>The plane count used is the current one, not the maximum reached in the line: no
     * test of the bank changes BPU within a line under HAM.
     */
    private static boolean isHamActive(int bplcon0) {
        return isHAM(bplcon0) && deniseBpu(bplcon0) > 4;
    }

    /**
     * Extra Half-Brite is on when BPU = 6 (or 7 in lores) with neither HAM nor dual
     * playfield. On OCS/ECS EHB has no explicit enable bit: 6 planes without HAM turn it on.
     * Bit 7 of BPLCON0 (GAUD, genlock audio enable) has nothing to do with it.
     * (HRM, "Extra Half-Brite Mode")
     */
    /**
     * KILLEHB (BPLCON2 bit 9), an ECS Denise bit: it turns Extra-Half-Brite off and the sixth
     * plane is then ignored (see colorizeNormal).
     */
    /** Colour-change key for the KILLEHB bit of a BPLCON2 write: not a register address. */
    private static final int COLCHANGE_KILLEHB = 0x105;

    private boolean ehbKilled(int bplcon2) {
        return isECS && (bplcon2 & 0x0200) != 0;
    }

    private static boolean isEHB(int bplcon0) {
        final int planes = deniseBpu(bplcon0);
        return !isHAM(bplcon0)
            && !isDualPlayfield(bplcon0)
            && (planes == 6 || planes == 7);
    }

    /**
     * Dual playfield, <b>and HAM must not be on</b>.
     *
     * <p><b>Denise's modes are a PRIORITY CHAIN, not three independent bits.</b>
     * <pre>
     * if      (ham)               mode = HAM;
     * else if (dualpf)            mode = DUALPF;
     * else if (ehb &amp;&amp; killehb)    mode = EXTRAHB_ECS_KILLEHB;
     * else if (ehb)               mode = EXTRAHB;
     * else                        mode = NORMAL;
     * </pre>
     * <b>HAM wins over everything</b>, dual playfield comes next, and Extra Half-Brite is on
     * only if neither is:
     * <pre>
     * ehb = (planes == 6 || planes == 7) &amp;&amp; !ham &amp;&amp; !dualpf
     *       &amp;&amp; (!ecs_denise || !(bplcon2 &amp; 0x200));
     * </pre>
     *
     * <p>With <b>HAM and DPF on together</b>, which the manuals do not define, the
     * priorities are NOT resolved as dual playfield with HAM decoded on top: that is
     * class B of section 16, {@code block3}, {@code block3m}, {@code modes3/3b/3c},
     * {@code modes4}, {@code invprio3}, i.e. exactly the tests in which BPLCON0 has bits 11
     * and 10 set.
     *
     * <p>On ECS KILLEHB (bit 9 of BPLCON2) turns Extra Half-Brite off: see {@link #ehbKilled}.
     */
    private static boolean isDualPlayfield(int bplcon0) {
        return (bplcon0 & 0x0400) != 0 && !isHAM(bplcon0);
    }

    /**
     * The colour index <b>dual playfield</b> resolves for the raw pixel {@code pv}: the two
     * playfields are separated and the one with priority wins, the other being used only
     * where the first is transparent.
     *
     * <p><b>Needed in HAM too.</b> With HAM and DPF on together the operation is still HAM's,
     * chosen by bits 5:4 of the pixel, but the <b>value</b> is no longer {@code pv & 0xF}:
     * it is this index.
     * <pre>
     * } else {   /&#42; OCS/ECS mode HAM6 + DPF &#42;/
     *     int *lookup = dualpfpri ? dblpf_ind2 : dblpf_ind1;
     *     int idx = lookup[pv];
     *     switch (pv &amp; 0x30) {
     *         case 0x00: ham_lastcolor = color_regs[idx]; break;
     *         case 0x10: ham_lastcolor &amp;= 0xFF0; ham_lastcolor |= (idx &amp; 0xF); break;
     *         ...
     * </pre>
     * The DPF bit that drives it is the <b>raw</b> bit of BPLCON0, not the resolved mode:
     * there HAM wins and the mode is HAM, but the decoder still looks at bit 10.
     *
     * <p>The table:
     * <pre>
     * plane1 = bits 0,2,4,6 of the pixel  ->  playfield 1 (odd planes)
     * plane2 = bits 1,3,5,7 of the pixel  ->  playfield 2 (even planes), +8 if not zero
     * plane1x = (pv &amp; 0x40) ? 0 : plane1     // "invalid value emulation" of BPLCON2
     * plane2x = (pv &amp; 0x80) ? 0 : plane2
     * dblpf_ind1[pv] = plane1 == 0 ? plane2x : plane1x;   // PF1 in front
     * dblpf_ind2[pv] = plane2 == 0 ? plane1x : plane2x;   // PF2 in front (PF2PRI)
     * </pre>
     *
     * <p><b>Checked arithmetically</b>: on pixel {@code pv = $1F} of {@code block3} the table
     * gives {@code plane1 = 7}, {@code plane2 = 3+8 = 11}, {@code dblpf_ind1 = 7}; with
     * operation {@code $10} (modify blue) the colour becomes {@code 000070}, exactly the
     * reference pixel.
     *
     * <p>Bits 6 and 7 mark the playfield made transparent by an invalid priority (see
     * {@link #deniseSpecials}).
     */
    /**
     * The two <b>undocumented</b> functions Denise has when a playfield priority in BPLCON2
     * is <b>5 or more</b>, i.e. points to a playfield that does not exist. They are applied
     * to the <b>raw</b> pixel, <b>after</b> the collisions are computed:
     * <pre>
     * specials = 0;
     * if ((mode == NORMAL || mode == EXTRAHB || ...) &amp;&amp; planes &gt;= 5 &amp;&amp; plf2pri &gt;= 5)
     *                                                    specials = 1;
     * if (mode == DUALPF &amp;&amp; (plf1pri &gt;= 5 || plf2pri &gt;= 5))
     *                                                    specials = 2;
     * </pre>
     *
     * <p><b>1, "SWIV"</b>, single playfield: with at least 5 planes and PF2P >= 5, if the bit
     * of <b>plane 5</b> is set <i>all the other planes are ignored</i> and the index becomes
     * exactly 16. Named after the game that uses it (the score panel of SWIV).
     *
     * <p><b>2, TRANSPARENT playfield</b>, dual playfield: the playfield whose priority is
     * >= 5 keeps its precedence (it still covers the other) but its colour becomes COLOR00.
     * It is signalled by setting bits 6 and 7 of the pixel, the "unused" plane bits on
     * OCS/ECS, which is exactly why {@link #dualPlayfieldIndex(int, boolean)} reads them:
     * {@code plane1x} and {@code plane2x} become 0 while {@code plane1}/{@code plane2} stay
     * non-zero, so the priority does not change and the colour does. (The Running Man /
     * Scoopex logo.)
     *
     * @param pv       raw 6-bit pixel
     * @param planes   planes Denise is composing
     * @param dpf      true if the mode is dual playfield
     * @param plf1pri  PF1P = {@code bplcon2 & 7}
     * @param plf2pri  PF2P = {@code (bplcon2 >> 3) & 7}
     */
    private static int deniseSpecials(int pv, int planes, boolean dpf,
                                      int plf1pri, int plf2pri) {
        // BOTH functions need AT LEAST FIVE PLANES, and the threshold belongs to the
        // rendering routine, not to the choice of the special: only the variants with more
        // than four planes apply it, so with 2 or 4 planes the special is never applied,
        // even when it reads 2.
        if (planes < 5) {
            return pv;
        }
        if (dpf) {
            if (plf1pri >= 5 && (pv & 0x15) != 0) pv |= 0x40;
            if (plf2pri >= 5 && (pv & 0x2A) != 0) pv |= 0x80;
            return pv;
        }
        // Single playfield: the "SWIV" rule.
        if (plf2pri >= 5 && (pv & 0x10) != 0) return 0x10;
        return pv;
    }

    private static int dualPlayfieldIndex(int pv, boolean pf2pri) {
        int plane1 = ((pv >> 0) & 1) | ((pv >> 1) & 2) | ((pv >> 2) & 4) | ((pv >> 3) & 8);
        int plane2 = ((pv >> 1) & 1) | ((pv >> 2) & 2) | ((pv >> 3) & 4) | ((pv >> 4) & 8);
        if (plane2 > 0) plane2 += 8;
        int plane1x = (pv & 0x40) != 0 ? 0 : plane1;
        int plane2x = (pv & 0x80) != 0 ? 0 : plane2;
        return pf2pri ? (plane2 == 0 ? plane1x : plane2x)
                      : (plane1 == 0 ? plane2x : plane1x);
    }

    // =========================================================================
    // API pubblica aggiuntiva
    // =========================================================================

    /** Signals a light pen event to Agnus (BPLCON0 bit 3 = LPEN). */
    public void triggerLightPen() {
        if ((bplcon0 & 0x0008) != 0 && agnus != null) agnus.triggerLightPen();
    }

    // Explicit disarm (debug/harness). It ALSO touches the start-of-line snapshot, or
    // scanSprites() would bring it back at the next hsync.
    public void disarmAllSprites()          { armed = 0; armedLineStart = 0; }
    public void disarmSprite(int idx)       {
        if (idx >= 0 && idx < 8) { armed &= ~(1 << idx); armedLineStart &= ~(1 << idx); }
    }
    public void setGamePort1(GamePort p)    { gamePort1 = p; }
    public void setGamePort2(GamePort p)    { gamePort2 = p; }
    public int  getBplcon0()                { return bplcon0; }
    public int  getBpu()                    { return bpu; }     // planes DRAWN, see deniseBpu()
    /** Raw BPU of BPLCON0, with no clamp: the value Agnus filters by itself. */
    public int  getRawBpu()                 { return (bplcon0 >> 12) & 7; }
    /** {@code true} if a word is still to be loaded into the shift registers: see
     *  AbstractAgnus.handleBitplaneDrawEvent, where it gates the drawing instead of DMACON. */
    public boolean isBitplaneArmed()        { return armedOdd || armedEven; }

    public boolean isHires()                { return hires; }   // (bplcon0 & 0x8000)!=0, cached
    /** LACE (BPLCON0 bit 2): the display alternates two fields offset by half a line. */
    public boolean isInterlaced()           { return (bplcon0 & 0x0004) != 0; }
    public int[] getRgbLine()               { return rgbLine; }
    /**
     * RGB of the LIVE COLOR00 (border), through the same LUT as {@link #amigaToRGB}. Used by
     * the deep-HBLANK right border (VirtualCRT): the last visible CCK of a line shows the
     * COLOR00 at beam CCK 0 of the NEXT line (the wrap cell belongs to the previous line).
     * Sampled at the start of the next line (after any write at CCK 0).
     */
    public int  getLiveColor0Rgb()          { return amigaToRGB(palette[0]); }

    /**
     * True when raw column {@code rawX} of the line just rendered carries actual content:
     * not vertical border, not outside the horizontal DIW window, and with a non-zero
     * colour index.
     *
     * <p>Used by the right-border wrap cell - see
     * {@code VirtualCRT.saveRegressionScreenshot}. {@code bBuffer} holds the border mask of
     * the line {@link #hsyncHandler(int)} has just closed, so it must be read before the
     * next line recomputes it.
     *
     * <p>An open DIW window is not enough on its own. Where the window is open but the
     * fetch has already ended, {@link #colorizeNormal} uses {@code mBuffer[i] & 0x3F},
     * which is 0 there, so the pixel is {@code palette[0]} - border, drawn with the COLOR00
     * of the end of the line instead of the value live at cck 0 of the next line, which is
     * the instant that cell shows. A non-zero index is real content instead (playfield or
     * sprite: sprite colours start at 16) and must be kept as rendered, being the tail of
     * the shifter crossing the line boundary.
     */
    public boolean isPlayfieldAt(int rawX) {
        return !lineIsVBorder && rawX >= 0 && rawX < bBuffer.length
                && bBuffer[rawX] == (byte) 0xFF
                && (mBuffer[rawX] & 0x3F) != 0;
    }
    public int  getPaletteColor(int idx)    { return palette[idx & 31]; }
    public int  getRawPixelColor(int rawX)  { return rawX >= 0 && rawX < HPIXELS ? rgbLine[rawX] : 0; }
    public int  getPixelColor(int rawX, int vpos) { return getRawPixelColor(rawX); }
    public int  getLastRenderedBplcon0()    { return lastRenderedBplcon0; }

    /**
     * True if the scanline just rendered by {@link #hsyncHandler(int)} is entirely vertical
     * border (the BPL1DAT gate never opens on the line: {@link #lineIsVBorder}), false if
     * the bitplanes produced pixels. VirtualCRT uses it to frame the display on the extent
     * actually drawn rather than on the DIW window (which can be open over the whole
     * overscan, e.g. It Came From The Desert: DIW [5,320) but box [72,255]).
     */
    public boolean isLastLineVBorder()      { return lineIsVBorder; }

    /** Returns the x coordinate (in raw pixels) of the left edge of the display window. */
    public int getDiwHStartPixel()  { return (hstrt - HBLANK_MIN * 2) * 2; }

    /** Returns the x coordinate (in raw pixels) of the right edge of the display window. */
    public int getDiwHStopPixel()   { return (hstop - HBLANK_MIN * 2) * 2; }

    /**
     * Returns the number of pixels rendered by sprite {@code idx} in the last completed
     * frame (current frame excluded, count reset at every vsync).
     */
    public long getSpritePixelCountLastFrame(int idx) {
        return (idx >= 0 && idx < 8) ? spritePixelsLastFrame[idx] : 0;
    }

    /**
     * Returns the number of pixels rendered by sprite {@code idx} in the current frame
     * (still in progress, reset at the start of the frame).
     */
    public long getSpritePixelCountThisFrame(int idx) {
        return (idx >= 0 && idx < 8) ? spritePixelsThisFrame[idx] : 0;
    }
}

