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
 * Line-buffered architecture, in two halves:
 *   this class, during the line: registers, shift-register emission into the index buffer,
 *     register writes recorded with the pixel at which they act;
 *   {@link DeniseLineRenderer}, at the end of the line, from the job this class hands over:
 *     border -> scanSprites -> translate -> blitSprites -> checkCollisions -> colorize
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

    static final class RegChange {
        int reg;
        int value;
        /** Lisa's sprite data: the register left-aligned in a long, bit 63 the first pixel. */
        long wide;
    }

    /**
     * Records the register changes made during the current scanline, with their pixel
     * timestamp, so they are applied in the right order during colorize (essential for
     * mid-line Copper effects).
     */
    static final class RegChangeRecorder {
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

        void insertWide(int key, int reg, int value, long wide) {
            if (size < keys.length) {
                elements[size].wide = wide;
            }
            insert(key, reg, value);
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

    /** DENISEID: 0xFFFF = OCS (no register), 0xFFFC = ECS, 0x00F8 = AGA (Lisa). */
    private int     deniseId = 0xFFFF;
    // isECS is effectively final: set only by setDeniseId() during setup, before the
    // emulation starts. It cannot be declared final (setDeniseId is a public setter), so
    // the JIT cannot constant-fold it.
    private boolean isECS    = false;
    /** Lisa: every ECS feature plus the AGA ones. Effectively final, like {@link #isECS}. */
    private boolean isAGA    = false;

    public void setDeniseId(int id) {
        this.deniseId = id;
        this.isECS    = (id != 0xFFFF);
        this.isAGA    = (id & 0xFF) == 0xF8;
        renderer.setEcs(isECS);
        renderer.setAga(isAGA);
    }

    public boolean isECS() { return isECS; }

    public boolean isAGA() { return isAGA; }

    // =========================================================================
    // Control registers
    // =========================================================================

    private int bplcon0, bplcon1, bplcon2, bplcon3, bplcon4;

    // Fields derived from bplcon0, recomputed at every bplcon0 write (see
    // updateBplcon0Derived). They avoid redoing Math.min(...) / shifts at every pixel on
    // the bitplane decoding hot path. They depend ONLY on bplcon0.
    private int     bpu   = 0;            // planes DRAWN by Denise: see deniseBpu()
    private boolean hires = false;        // (bplcon0 & 0x8000) != 0
    private boolean shres = false;        // ECS and Lisa: (bplcon0 & 0x0040) != 0

    private int     hstrt,   hstop;

    private int clxcon;

    /** CLXCON2 (Lisa): planes 7 and 8 in the collisions. A CLXCON write clears it. */
    private int clxcon2;

    /** FMODE as Lisa holds it: the bitplane and sprite data width. */
    private int fmode;

    // Game port 1 (mouse) and port 2 (joystick), optional
    private GamePort gamePort1 = null;
    private GamePort gamePort2 = null;

    // =========================================================================
    // Palette (12-bit Amiga: 0x0RGB)
    // =========================================================================

    private final int[] palette = new int[32];
    public int dbgPalette(int i) { return palette[i & 31]; }

    /**
     * Lisa's 256 colour registers, 24-bit {@code 0xRRGGBB}. A COLORxx write lands in the bank
     * BPLCON3 selects; with LOCT it replaces the low nibble of each component, otherwise it
     * sets both nibbles to the written one. Used only on AGA, where {@link #palette} keeps the
     * high nibbles of the first 32 registers.
     */
    private final int[] paletteAga = new int[256];

    /** Key of an AGA colour change in {@code colChanges}: register n is COLCHANGE_AGA + n. */
    static final int COLCHANGE_AGA = 0x200;

    /** Delay of a colour change on Lisa, in sub-pixels: one hires pixel. */
    private static final int LISA_COLOR_DELAY_PX = 1;

    // =========================================================================
    // Shift register bitplane
    // =========================================================================

    private final int[] bpldat     = new int[8];
    private final int[] bpldatPipe = new int[8];  // pipeline stage
    private final int[] shiftReg   = new int[8];
    // Lisa: the same three stages up to 64 bits wide, and the width the pipe stage holds.
    private final long[] bpldatWide     = new long[8];
    private final long[] bpldatPipeWide = new long[8];
    private final long[] shiftRegWide   = new long[8];
    private int pipeWidth = 16;
    private int shiftWidth = 16;
    private boolean     armedOdd, armedEven;

    /**
     * Log of the <b>incremental emission</b> of the shift registers of the current line, for
     * both groups (odd planes, even planes).
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
     *
     * <p>The emission itself runs in the line renderer ({@code DeniseLineRenderer.emitBitplanes}).
     * The front end only records, in the order they happen, the events it depends on: every
     * word load and every change of the live resolution, each stamped with the number of
     * half-CCK emission steps already taken in the line. Replaying the log in that order
     * reproduces the emission step by step, including the order of a BPLCON0 write and a load
     * within the same half CCK.
     */
    private int[]   bplEvents = new int[DeniseLineJob.BPL_EVENTS_INITIAL];
    private int     bplEventCount;
    /** Half-CCK emission steps taken in the current line. */
    private int     bplHalfCcks;
    /** The live resolution when the current line's log started. */
    private boolean bplStartHires;
    private boolean bplStartShres;

    // =========================================================================
    // Sprite state
    // =========================================================================

    private final int[] sprdata  = new int[8];
    private final int[] sprdatb  = new int[8];
    private final int[] sprpos   = new int[8];
    private final int[] sprctl   = new int[8];
    private int armed;                 // bitmask of the armed sprites

    /** The pixels of the BPL1DAT writes of the line, in the order they came. */
    private final int[] bpl1datPixels = new int[64];
    private int bpl1datCount;

    // =========================================================================
    // Line buffer and line rendering
    // =========================================================================

    /** Turns each completed line into pixels and collision bits. */
    private final DeniseLineRenderer renderer = new DeniseLineRenderer();

    /** The job a line is handed over in when nobody else drives the rendering. */
    private final DeniseLineJob lineJob = new DeniseLineJob();

    /**
     * Brings the renderer up to date with every line handed over so far, when the rendering
     * runs behind the beam on another thread: called before anything the renderer owns is
     * read or changed from this side. {@code null} while lines are rendered as they close.
     */
    private Runnable renderSync;

    /** Column of the right-border wrap cell: the last visible CCK, 4*(227 - HBLANK_MIN). */
    public static final int RIGHT_HBLANK_WRAP_COL = 4 * (227 - 0x12);

    /**
     * Sub-pixels past the end of the line that show the colour of the next line's first colour
     * clocks: two colour clocks, CCK 0 and CCK 1 of the next line.
     */
    public static final int WRAP_CELL_SUBPIXELS = 8;

    /**
     * Hires pixels by which a COLOR00 change reaches the output after the colour stage: one on
     * Lisa, none on Denise. The right-border wrap cells follow it too.
     */
    public int colourDelayPx() {
        return isAGA ? LISA_COLOR_DELAY_PX : 0;
    }


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

    // Swapped with the job's at the end of every line.
    private RegChangeRecorder  conChanges = new RegChangeRecorder(128);
    private RegChangeRecorder  diwChanges = new RegChangeRecorder(128);
    private RegChangeRecorder  colChanges = new RegChangeRecorder(1024);
    private RegChangeRecorder[] sprChanges = new RegChangeRecorder[4];

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
        bpu   = isAGA ? lisaPlanes(bplcon0) : isECS ? ecsPlanes(bplcon0) : deniseBpu(bplcon0);
        final boolean h = (bplcon0 & 0x8000) != 0;
        if (h != hires) {
            hires = h;
            recordBplEvent(DeniseLineJob.BPL_EV_HIRES | (h ? DeniseLineJob.BPL_EV_ARG : 0));
        }
        // Super-hires (bit 6, ECS and Lisa) shifts out two bits per sub-pixel, whatever bit
        // 15 says. An OCS Denise has no such bit.
        final boolean sh = isECS && (bplcon0 & 0x0040) != 0;
        if (sh != shres) {
            shres = sh;
            recordBplEvent(DeniseLineJob.BPL_EV_SHRES | (sh ? DeniseLineJob.BPL_EV_ARG : 0));
        }
    }


    /**
     * Number of planes <b>Denise</b> composes, from BPLCON0.
     *
     * <p><b>More than four planes in HIRES is not a legal configuration on OCS/ECS: Denise
     * draws NONE of them.</b> It is not a clamp to four: the image disappears entirely and
     * the background remains. (SuperHires with more than two planes behaves the same, but is
     * not modelled.) The Agnus counterpart is {@code AbstractAgnus.updateFetchGeometry}:
     * the two chips apply the same threshold, each on its own.
     *
     * <p>The clamp to 6 concerns only BPU=7 in lores: the shift register has six planes, and
     * seven planes in lores are Extra Half-Brite anyway, i.e. the same picture as six.
     */
    static int deniseBpu(int bplcon0) {
        int planes = (bplcon0 >> 12) & 7;
        if ((bplcon0 & 0x8000) != 0 && planes > 4) return 0;
        return Math.min(planes, 6);
    }

    /**
     * Planes an ECS Denise composes: as on OCS, plus super-hires, where more than two planes
     * do not fit the 16-bit data and none is drawn.
     */
    private static int ecsPlanes(int bplcon0) {
        final int planes = deniseBpu(bplcon0);
        return (bplcon0 & 0x0040) != 0 && planes > 2 ? 0 : planes;
    }

    /**
     * Planes Lisa composes, from BPLCON0 and its FMODE: BPU3 (bit 4) alone is eight, with any
     * BPU bit none. As on OCS, more planes than the data width allows in the resolution is not
     * a clamp but no planes at all: in hires more than four with 16-bit data, in super-hires
     * more than two with 16-bit data and more than four with 32-bit data.
     */
    private int lisaPlanes(int bplcon0) {
        final int planes = DeniseLineRenderer.agaPlanes(bplcon0);
        final int f = fmode & 3;
        final int width = f == 0 ? 0 : f == 3 ? 2 : 1;
        final int res = (bplcon0 & 0x0040) != 0 ? 2 : (bplcon0 & 0x8000) != 0 ? 1 : 0;
        if (width == 0 && res > 0 && planes > 4) return 0;
        if (width == 0 && res > 1 && planes > 2) return 0;
        if (width == 1 && res > 1 && planes > 4) return 0;
        return planes;
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
        fmode = 0;
        clxcon2 = 0;
        updateBplcon0Derived();
        bplcon3 = 0x0C00;
        bplcon4 = 0x0011;
        hstrt = 0; hstop = 0;
        clxcon = 0;
        if (hardware) {
            Arrays.fill(palette,  0);
            Arrays.fill(paletteAga, 0);
        }
        Arrays.fill(bpldat,   0);
        Arrays.fill(bpldatPipe, 0);
        Arrays.fill(shiftReg, 0);
        Arrays.fill(bpldatWide, 0);
        Arrays.fill(bpldatPipeWide, 0);
        Arrays.fill(shiftRegWide, 0);
        pipeWidth = 16;
        shiftWidth = 16;
        armedOdd = armedEven = false;
        Arrays.fill(sprdata,  0);
        Arrays.fill(sprdatb,  0);
        Arrays.fill(sprdataWide, 0);
        Arrays.fill(sprdatbWide, 0);
        Arrays.fill(sprpos,   0);
        Arrays.fill(sprctl,   0);
        armed = 0;
        // The words of the line so far are dropped with the log.
        bplEventCount = 0;
        bplHalfCcks = 0;
        bplStartHires = hires;
        bplStartShres = shres;
        conChanges.clear();
        diwChanges.clear();
        colChanges.clear();
        for (int i = 0; i < 4; i++) sprChanges[i].clear();
        sync();
        renderer.reset(hardware);
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
                    // Lisa: the hires fine bit of each coordinate (bit 4 for the start, 12 for
                    // the stop), kept as bit 9; the super-hires one (3 and 11) is below a
                    // sub-pixel.
                    final int fineStart = isAGA && (value & 0x0010) != 0 ? 0x200 : 0;
                    final int fineStop = isAGA && (value & 0x1000) != 0 ? 0x200 : 0;
                    setHSTRT((hstrt & 0xFF) | ((value & 0x0020) != 0 ? 0x100 : 0) | fineStart, pixel);
                    setHSTOP((hstop & 0xFF) | ((value & 0x2000) != 0 ? 0x100 : 0) | fineStop, pixel);
                }
                break;

            // --- Collision ---
            case 0x098:
                clxcon = value;
                clxcon2 = 0;
                break;

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
                // On Lisa the mode also follows BPU3 and dual playfield: every change of the
                // plane count or of the HAM and DPF bits is replayed. On an ECS Denise super-
                // hires is a colour mode too: its pixels take two bits per gun.
                if (isAGA ? ((bplcon0 ^ value) & 0x7C10) != 0
                          : isHamActive(bplcon0) != isHamActive(value) || isEHB(bplcon0) != isEHB(value)
                            || (isECS && ((bplcon0 ^ value) & 0x0040) != 0)) {
                    // Same stage as the conChanges above: HAM and EHB come from Denise's
                    // copy of BPLCON0, not from the early (colour) stage.
                    colChanges.insert(pixel + CON_DELAY_PX, address, value);
                }
                bplcon0 = value;
                updateBplcon0Derived();
                break;
            case 0x102: // BPLCON1 (scroll, effective at once)
                // Lisa keeps all sixteen bits: two more coarse and two fine bits per field.
                bplcon1 = isAGA ? value & 0xFFFF : value & 0xFF;
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
            case 0x106: // BPLCON3 (ECS)
                // On Lisa it also moves the second playfield in the palette (PF2OF).
                if (isAGA) conChanges.insert(pixel + CON_DELAY_PX, address, value);
                bplcon3 = value;
                break;
            case 0x10C: // BPLCON4 (ECS)
                // On Lisa it xors the bitplane colour index and selects the sprite banks.
                if (isAGA) colChanges.insert(pixel + CON_DELAY_PX, address, value);
                bplcon4 = value;
                break;

            // --- BPLxDAT ---
            case 0x110: case 0x112: case 0x114:
            case 0x116: case 0x118: case 0x11A:
                setBPLxDAT((address - 0x110) / 2, value, pixel);
                break;
            case 0x11C: case 0x11E:   // BPL7DAT, BPL8DAT (Lisa)
                if (isAGA) setBPLxDAT((address - 0x110) / 2, value, pixel);
                break;
            case 0x10E:   // CLXCON2 (Lisa)
                if (isAGA) clxcon2 = value;
                break;
            case 0x1FC:   // FMODE (Lisa)
                if (isAGA) {
                    fmode = value;
                    updateBplcon0Derived();
                }
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
                    if (isAGA) {
                        writeColorAga(ci, value, pixel);
                    } else {
                        palette[ci] = value & 0xFFF;
                        colChanges.insert(pixel, address, value);
                    }
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
            case 0x00E: sync(); return renderer.readClxdat();

            // DENISEID ($DFF07C):
            //   ECS Denise 8373: returns $FFFC (ECS chip identifier); AGA Lisa $00F8.
            //   OCS Denise 8362: register not implemented. getLastBusData() is NOT used: the
            //   last data written on the bus (e.g. BPLCON0=$0102) would spoil the Kickstart's
            //   OCS/ECS detection.
            case 0x07C: return deniseId;

            default:
                // Colour registers ($DFF180-$DFF1BE): Lisa reads them back under RDRAM.
                // BPLCON0-4 and, on OCS and ECS, the colours are write-only and never reach
                // here: the bus handles their read.
                if (isAGA && address >= 0x180 && address <= 0x1BE) {
                    return readColorAga((address - 0x180) / 2);
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
     * the pixel index but on {@code counter} (see {@code DeniseLineRenderer.updateBorderBuffer}), and the
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
    }

    private void setHSTOP(int val, int pixel) {
        hstop = val;
        diwChanges.insert(pixel + DIW_AXIS_SHIFT, 0x090, val);
    }

    /**
     * A COLORxx write on Lisa. With RDRAM (BPLCON2 bit 8) the colour registers are read, not
     * written, and the write is lost. The register is {@code bank * 32 + idx}, the bank from
     * BPLCON3 bits 13-15; LOCT (BPLCON3 bit 9) writes the low nibble of each component and
     * keeps the high one, otherwise the nibble goes to both halves.
     */
    private void writeColorAga(int idx, int value, int pixel) {
        if ((bplcon2 & 0x0100) != 0) return;
        final int reg = ((bplcon3 >> 13) & 7) * 32 + idx;
        final int r = (value >> 8) & 0xF;
        final int g = (value >> 4) & 0xF;
        final int b =  value       & 0xF;
        final int old = paletteAga[reg];
        final int rgb;
        if ((bplcon3 & 0x0200) != 0) {
            rgb = (old & 0xF0F0F0) | (r << 16) | (g << 8) | b;
        } else {
            rgb = (r * 0x11 << 16) | (g * 0x11 << 8) | (b * 0x11);
        }
        paletteAga[reg] = rgb;
        if (reg < 32) {
            palette[reg] = ((rgb >> 12) & 0xF00) | ((rgb >> 8) & 0x0F0) | ((rgb >> 4) & 0x00F);
        }
        // Lisa shows a colour change one hires pixel later than the colour stage it enters.
        colChanges.insert(pixel + LISA_COLOR_DELAY_PX, COLCHANGE_AGA + reg, rgb);
    }

    /**
     * A COLORxx read on Lisa: with RDRAM the register of the selected bank, its high nibbles
     * or with LOCT its low ones; without RDRAM the colour registers do not drive the bus.
     */
    private int readColorAga(int idx) {
        if ((bplcon2 & 0x0100) == 0) return 0xFFFF;
        final int rgb = paletteAga[((bplcon3 >> 13) & 7) * 32 + idx];
        final int shift = (bplcon3 & 0x0200) != 0 ? 0 : 4;
        return (((rgb >> (16 + shift)) & 0xF) << 8) | (((rgb >> (8 + shift)) & 0xF) << 4)
                | ((rgb >> shift) & 0xF);
    }

    /**
     * A bitplane data transfer wider than a word, from a fetch with FMODE 1-3 ({@code bits}
     * 32 or 64, the first pixel in the top bit). Lisa only.
     */
    void writeBitplaneWide(int x, long data, int bits, int pixel) {
        if (rgaSlotTaken) return;
        bpldat[x] = (int) data & 0xFFFF;
        bpldatWide[x] = data;
        if (x == 0) {
            copyWideToPipe();
            System.arraycopy(bpldat, 0, bpldatPipe, 0, 8);
            armedOdd  = true;
            armedEven = true;
            if (bpl1datCount < bpl1datPixels.length) bpl1datPixels[bpl1datCount++] = pixel;
        }
    }

    /**
     * BPL1DAT moves the words into the stage the shift registers load from, and fixes the
     * width they are shifted out at: Lisa's FMODE at that instant.
     */
    private void copyWideToPipe() {
        System.arraycopy(bpldatWide, 0, bpldatPipeWide, 0, 8);
        pipeWidth = 16 << fetchWidth();
    }

    /** Lisa's data width from FMODE: 0 = 16 bits, 1 = 32, 2 = 64. */
    private int fetchWidth() {
        final int f = fmode & 3;
        return f == 0 ? 0 : f == 3 ? 2 : 1;
    }

    /** Denise's resolution: 0 lores, 1 hires, 2 super-hires (ECS and Lisa). */
    private int resolution() {
        return (bplcon0 & 0x0040) != 0 && isECS ? 2 : (bplcon0 & 0x8000) != 0 ? 1 : 0;
    }

    /** Super-hires is in force: BPLCON0 bit 6 on an ECS Denise or on Lisa. */
    boolean superHires() {
        return shres;
    }

    /**
     * How many CCKs a word of the shift registers lasts, the period at which a new one loads:
     * its width in pixels at the current resolution, two lores pixels per CCK. Used on Lisa,
     * whose words are up to 64 bits wide, and in super-hires, where a 16-bit word lasts two.
     */
    int wordCcks() {
        return Math.max(1, pipeWidth >> (resolution() + 1));
    }

    /**
     * The scroll of the odd or even planes in sub-pixels (hires pixels), on Lisa and in
     * super-hires. Each field of BPLCON1 is a delay in lores pixels, masked to the length of a
     * word at the current resolution (three lores pixels for a 16-bit word in super-hires).
     * On Lisa the field has six bits (bits 0-3 and 10-11 for the odd planes, 4-7 and 14-15
     * for the even ones) plus two in super-hires pixels (8-9 and 12-13), of which a hires
     * pixel keeps the upper one; an ECS Denise holds only the low byte.
     */
    int scrollSub(boolean even) {
        final int v = even ? bplcon1 >> 4 : bplcon1;
        final int delay = (v & 0x0F) | ((v & 0x0C00) >> 6);
        final int hr = even ? (bplcon1 >> 12) & 3 : (bplcon1 >> 8) & 3;
        final int mask = (pipeWidth - 1) >> resolution();
        return ((delay & mask) << 1) | (hr >> 1);
    }

    private void setBPLxDAT(int x, int value, int pixel) {
        bpldat[x] = value & 0xFFFF;
        if (isAGA) {
            bpldatWide[x] = value & 0xFFFF;
            if (x == 0) copyWideToPipe();
        }
        if (x == 0) {
            // BPL1DAT written by the DMA: loads the pipeline and arms the shift registers.
            // BPL1DAT is the trigger because in the DMA cycle it is the last odd plane
            // fetched (Agnus has already written BPL3/5 in the previous cycles).
            System.arraycopy(bpldat, 0, bpldatPipe, 0, 8);
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
        sprChanges[x / 2].insert(pixel + SPR_POSCTL_PX, 0x140 + x * 8, value);
    }

    private void pokeSPRxCTL(int x, int value, int pixel) {
        sprctl[x] = value;
        // A CTL write disarms the sprite immediately. This prevents stale data from the
        // previous frame rendering on scanlines before VSTART (which would create a vertical
        // line artifact).
        armed &= ~(1 << x);
        sprChanges[x / 2].insert(pixel + SPR_POSCTL_PX, 0x142 + x * 8, value);
    }

    private void pokeSPRxDATA(int x, int value, int pixel) {
        if (isAGA) {
            writeSpriteDataLisa(x, false, cpuSpriteWord(sprdataWide[x], value), pixel);
            return;
        }
        armed    |= (1 << x);
        sprdata[x] = value & 0xFFFF;
        sprChanges[x / 2].insert(pixel + SPR_DAT_PX, 0x144 + x * 8, value);
    }

    private void pokeSPRxDATB(int x, int value, int pixel) {
        if (isAGA) {
            writeSpriteDataLisa(x, true, cpuSpriteWord(sprdatbWide[x], value), pixel);
            return;
        }
        sprdatb[x] = value & 0xFFFF;
        sprChanges[x / 2].insert(pixel + SPR_DAT_PX, 0x146 + x * 8, value);
    }

    // Lisa's sprite data registers, left-aligned: bit 63 is the first pixel.
    private final long[] sprdataWide = new long[8];
    private final long[] sprdatbWide = new long[8];

    /**
     * A word written to SPRxDATA/SPRxDATB by the processor or the Copper on Lisa. The data
     * register is 32 bits wide and the word lands in its low half, behind 16 empty pixels;
     * with 64-bit sprites (FMODE bits 2-3 both set) the register in use is the 64-bit one,
     * which such a write leaves alone.
     */
    private long cpuSpriteWord(long current, int value) {
        if (((fmode >> 2) & 3) == 3) return current;
        return (value & 0xFFFFL) << 32;
    }

    /**
     * Sprite data from the DMA on Lisa: {@code bits} 16 (a 16-bit fetch, which fills the high
     * half of the 32-bit register), 32 or 64.
     */
    void writeSpriteWide(int x, boolean datb, long data, int bits) {
        if (rgaSlotTaken) return;
        final int cck = (agnus != null) ? agnus.getHpos() >> 1 : 0;
        final int pixel = Math.max(0, cck * 4 - 4 * HBLANK_MIN);
        final long aligned = bits == 64 ? data : (data & ((1L << bits) - 1)) << (64 - bits);
        writeSpriteDataLisa(x, datb, aligned, pixel);
    }

    private void writeSpriteDataLisa(int x, boolean datb, long aligned, int pixel) {
        final int top = (int) (aligned >>> 48);
        if (datb) {
            sprdatbWide[x] = aligned;
            sprdatb[x] = top;
            sprChanges[x / 2].insertWide(pixel + SPR_DAT_PX, 0x146 + x * 8, top, aligned);
        } else {
            armed |= (1 << x);
            sprdataWide[x] = aligned;
            sprdata[x] = top;
            sprChanges[x / 2].insertWide(pixel + SPR_DAT_PX, 0x144 + x * 8, top, aligned);
        }
    }


    // =========================================================================
    // End of line: hand-over to the renderer (called at every HSYNC)
    // =========================================================================

    /**
     * Closes the scanline just completed and renders it at once: {@link #captureLine} followed
     * by {@link #renderLine}.
     *
     * @param vpos  vertical scanline number (0-based)
     */
    public void hsyncHandler(int vpos) {
        sync();
        captureLine(vpos, lineJob);
        renderer.render(lineJob);
    }

    /**
     * Installs the hook that brings the renderer up to date (see {@link #renderSync}): the
     * output stage that renders lines behind the beam installs it, {@code null} removes it.
     */
    public void setRenderSync(Runnable sync) {
        this.renderSync = sync;
    }

    /** Waits until every line handed over has been rendered. */
    public void syncRendering() {
        sync();
    }

    private void sync() {
        final Runnable s = renderSync;
        if (s != null) s.run();
    }

    /** A job to hand lines over in, for an output stage that keeps several in flight. */
    public DeniseLineJob newLineJob() {
        return new DeniseLineJob();
    }

    /**
     * Renders a line {@link #captureLine} handed over. Lines must be rendered in the order they
     * were captured, one at a time; the results are left in the job.
     */
    public void renderLine(DeniseLineJob job) {
        renderer.render(job);
    }

    /**
     * Closes the scanline just completed: finishes shifting out the words still in the shift
     * registers and fills {@code job} with everything the renderer needs for it. The index
     * buffer and the change recorders are swapped with the job's, which must come back empty
     * from its last rendering: the front end starts the next line on clean ones.
     *
     * @param vpos  vertical scanline number (0-based)
     */
    public void captureLine(int vpos, DeniseLineJob job) {
        final int[] ev = bplEvents;
        bplEvents = job.bplEvents;
        job.bplEvents = ev;
        job.bplEventCount = bplEventCount;
        job.bplStartHires = bplStartHires;
        job.bplStartShres = bplStartShres;
        bplEventCount = 0;
        bplHalfCcks = 0;
        bplStartHires = hires;
        bplStartShres = shres;
        RegChangeRecorder r = conChanges; conChanges = job.conChanges; job.conChanges = r;
        r = diwChanges; diwChanges = job.diwChanges; job.diwChanges = r;
        r = colChanges; colChanges = job.colChanges; job.colChanges = r;
        final RegChangeRecorder[] s = sprChanges;
        sprChanges = job.sprChanges;
        job.sprChanges = s;
        System.arraycopy(bpl1datPixels, 0, job.bpl1datPixels, 0, bpl1datCount);
        job.bpl1datCount = bpl1datCount;
        bpl1datCount = 0;

        job.vpos = vpos;
        job.visible = agnus != null && !agnus.inVBlankArea(vpos);
        job.maxHpos = agnus != null ? agnus.getMaxHpos() : 454;
        job.hcounterRenderHead = hcounterRenderHead;
        job.clxcon = clxcon;
        job.clxcon2 = clxcon2;
        job.bplcon3 = bplcon3;
        job.bplcon4 = bplcon4;
        job.fmode = fmode;
        job.bplcon0 = bplcon0;
        job.bplcon2 = bplcon2;
        job.hstrt = hstrt;
        job.hstop = hstop;
        job.armed = armed;
        System.arraycopy(palette, 0, job.palette, 0, 32);
        if (isAGA) System.arraycopy(paletteAga, 0, job.paletteAga, 0, 256);
        System.arraycopy(sprpos,  0, job.sprpos,  0, 8);
        System.arraycopy(sprctl,  0, job.sprctl,  0, 8);
        System.arraycopy(sprdata, 0, job.sprdata, 0, 8);
        System.arraycopy(sprdatb, 0, job.sprdatb, 0, 8);
        if (isAGA) {
            System.arraycopy(sprdataWide, 0, job.sprdataWide, 0, 8);
            System.arraycopy(sprdatbWide, 0, job.sprdatbWide, 0, 8);
        }
    }

    // =========================================================================
    // Phase 1: loading the shift registers and emitting the pixels
    // =========================================================================

    /**
     * <b>Loads</b> the shift registers; nothing is drawn here. The pixels leave through
     * {@link #emitBitplanePixels(int)}, four per CCK, at the step of the live resolution.
     * See {@link #bplEvents} for why.
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
        if (isAGA) {
            armShiftOutLisa(grp, cursor);
            return;
        }
        final int w0, w1, w2;
        if (grp == 0) {
            w0 = bpu >= 1 ? shiftReg[0] : 0;
            w1 = bpu >= 3 ? shiftReg[2] : 0;
            w2 = bpu >= 5 ? shiftReg[4] : 0;
        } else {
            w0 = bpu >= 2 ? shiftReg[1] : 0;
            w1 = bpu >= 4 ? shiftReg[3] : 0;
            w2 = bpu >= 6 ? shiftReg[5] : 0;
        }
        if (bplEventCount + DeniseLineJob.BPL_LOAD_SIZE > bplEvents.length) {
            bplEvents = Arrays.copyOf(bplEvents, 2 * bplEvents.length);
        }
        final int[] e = bplEvents;
        int n = bplEventCount;
        e[n++] = (bplHalfCcks << DeniseLineJob.BPL_EV_STEP_SHIFT) | DeniseLineJob.BPL_EV_LOAD
                | (grp != 0 ? DeniseLineJob.BPL_EV_ARG : 0);
        e[n++] = cursor;
        e[n++] = w0;
        e[n++] = w1;
        e[n++] = w2;
        bplEventCount = n;
    }

    /**
     * The load of a word on Lisa: four planes per group, and words as wide as the fetch. The
     * event carries the word length in bits and each plane as two ints, high half first.
     */
    private void armShiftOutLisa(int grp, int cursor) {
        if (bplEventCount + DeniseLineJob.BPL_LOAD_WIDE_SIZE > bplEvents.length) {
            bplEvents = Arrays.copyOf(bplEvents, 2 * bplEvents.length);
        }
        final int[] e = bplEvents;
        int n = bplEventCount;
        e[n++] = (bplHalfCcks << DeniseLineJob.BPL_EV_STEP_SHIFT) | DeniseLineJob.BPL_EV_LOAD_WIDE
                | (grp != 0 ? DeniseLineJob.BPL_EV_ARG : 0);
        e[n++] = cursor;
        e[n++] = shiftWidth;
        for (int p = grp; p < 8; p += 2) {
            final long w = bpu > p ? shiftRegWide[p] : 0;
            e[n++] = (int) (w >>> 32);
            e[n++] = (int) w;
        }
        bplEventCount = n;
    }

    private void recordBplEvent(int event) {
        if (bplEventCount == bplEvents.length) {
            bplEvents = Arrays.copyOf(bplEvents, 2 * bplEvents.length);
        }
        bplEvents[bplEventCount++] = (bplHalfCcks << DeniseLineJob.BPL_EV_STEP_SHIFT) | event;
    }

    /**
     * One <b>half CCK</b> of shift-register output. Called <b>twice per CCK</b> by Agnus: on
     * the rising edge (after any load) and on the falling edge, right after the Copper.
     *
     * <p>A bit lasts <b>two</b> sub-pixels in lores and <b>one</b> in hires, and the
     * resolution is the one live at each half CCK: a switch in the middle of a word changes
     * the output step.
     *
     * <p><b>Why two half CCKs and not one whole CCK.</b> The <b>resolution</b> part of
     * BPLCON0 enters Denise <b>one stage earlier</b> than the rest of the register, and that
     * stage is <b>half a colour clock</b>, which is measured: emitting four sub-pixels as a
     * block at the top of the CCK put the {@code hilo} switch <b>two sub-pixels late</b> on
     * all twelve lines. Splitting the emission in half lets the Copper write of the current
     * CCK arrive in time for the second half.
     *
     * <p>Only the step is counted here; the pixels are written by the line renderer, which
     * replays the loads and the resolution changes against these steps (see
     * {@link #bplEvents}).
     */
    public void emitBitplaneHalfCck() {
        bplHalfCcks++;
    }

    /** Lisa: loads the planes of one group (0 odd, 1 even) and the width they shift out at. */
    private void loadWideShiftRegisters(int grp) {
        for (int p = grp; p < 8; p += 2) {
            if (bpu > p) shiftRegWide[p] = bpldatPipeWide[p];
        }
        shiftWidth = pipeWidth;
    }

    private void loadOddShiftRegisters() {
        if (isAGA) {
            loadWideShiftRegisters(0);
            return;
        }
        if (bpu >= 1) shiftReg[0] = bpldatPipe[0];
        if (bpu >= 3) shiftReg[2] = bpldatPipe[2];
        if (bpu >= 5) shiftReg[4] = bpldatPipe[4];
        if (bpu >= 7) shiftReg[6] = bpldatPipe[6];
    }

    private void loadEvenShiftRegisters() {
        if (isAGA) {
            loadWideShiftRegisters(1);
            return;
        }
        if (bpu >= 2) shiftReg[1] = bpldatPipe[1];
        if (bpu >= 4) shiftReg[3] = bpldatPipe[3];
        if (bpu >= 6) shiftReg[5] = bpldatPipe[5];
        if (bpu >= 8) shiftReg[7] = bpldatPipe[7];
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
    static int amigaToRGB(int c) {
        return (useVAmigaColorLut ? VAMIGA_RGB_LUT : AMIGA_RGB_LUT)[c & 0xFFF];
    }

    /**
     * Computes the effective Y coordinate on the display, taking interlace into account. If
     * LACE (bit 2 of bplcon0) is on, scanlines are doubled and the current field (0 or 1)
     * decides whether even or odd rows are drawn.
     */

    /** HAM is on if bit 11 (HOMOD) of BPLCON0 is set. */
    static boolean isHAM(int bplcon0) {
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
    static boolean isHamActive(int bplcon0) {
        return isHAM(bplcon0) && deniseBpu(bplcon0) > 4;
    }

    /**
     * Extra Half-Brite is on when BPU = 6 (or 7 in lores) with neither HAM nor dual
     * playfield. On OCS/ECS EHB has no explicit enable bit: 6 planes without HAM turn it on.
     * Bit 7 of BPLCON0 (GAUD, genlock audio enable) has nothing to do with it.
     * (HRM, "Extra Half-Brite Mode")
     */
    /** Colour-change key for the KILLEHB bit of a BPLCON2 write: not a register address. */
    static final int COLCHANGE_KILLEHB = 0x105;

    static boolean isEHB(int bplcon0) {
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
     * <p>On ECS KILLEHB (bit 9 of BPLCON2) turns Extra Half-Brite off: see {@code DeniseLineRenderer.ehbKilled}.
     */
    static boolean isDualPlayfield(int bplcon0) {
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
    static int deniseSpecials(int pv, int planes, boolean dpf,
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

    static int dualPlayfieldIndex(int pv, boolean pf2pri) {
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
    public void disarmAllSprites()          { sync(); armed = 0; renderer.disarmSprites(0xFF); }
    public void disarmSprite(int idx)       {
        if (idx >= 0 && idx < 8) { sync(); armed &= ~(1 << idx); renderer.disarmSprites(1 << idx); }
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
    public int[] getRgbLine()               { sync(); return renderer.rgbLine(); }
    /**
     * RGB of the LIVE COLOR00 (border), through the same LUT as {@link #amigaToRGB}. Used by
     * the deep-HBLANK right border (VirtualCRT): the last visible CCK of a line shows the
     * COLOR00 at beam CCK 0 of the NEXT line (the wrap cell belongs to the previous line).
     * Sampled at the start of the next line (after any write at CCK 0).
     */
    public int  getLiveColor0Rgb()          { return isAGA ? paletteAga[0] : amigaToRGB(palette[0]); }

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
     * fetch has already ended, {@code colorizeNormal} uses {@code mBuffer[i] & 0x3F},
     * which is 0 there, so the pixel is {@code palette[0]} - border, drawn with the COLOR00
     * of the end of the line instead of the value live at cck 0 of the next line, which is
     * the instant that cell shows. A non-zero index is real content instead (playfield or
     * sprite: sprite colours start at 16) and must be kept as rendered, being the tail of
     * the shifter crossing the line boundary.
     */
    public boolean isPlayfieldAt(int rawX) {
        sync();
        return renderer.isPlayfieldAt(rawX);
    }
    public int  getPaletteColor(int idx)    { return palette[idx & 31]; }
    public int  getRawPixelColor(int rawX)  { sync(); return rawX >= 0 && rawX < HPIXELS ? renderer.rgbLine()[rawX] : 0; }
    public int  getPixelColor(int rawX, int vpos) { return getRawPixelColor(rawX); }
    public int  getLastRenderedBplcon0()    { sync(); return renderer.lastRenderedBplcon0(); }

    /**
     * True if the scanline just rendered by {@link #hsyncHandler(int)} is entirely vertical
     * border (the BPL1DAT gate never opens on the line), false if
     * the bitplanes produced pixels. VirtualCRT uses it to frame the display on the extent
     * actually drawn rather than on the DIW window (which can be open over the whole
     * overscan, e.g. It Came From The Desert: DIW [5,320) but box [72,255]).
     */
    public boolean isLastLineVBorder()      { sync(); return renderer.isLastLineVBorder(); }

    /** Returns the x coordinate (in raw pixels) of the left edge of the display window. */
    public int getDiwHStartPixel()  { return ((hstrt & 0x1FF) - HBLANK_MIN * 2) * 2; }

    /** Returns the x coordinate (in raw pixels) of the right edge of the display window. */
    public int getDiwHStopPixel()   { return ((hstop & 0x1FF) - HBLANK_MIN * 2) * 2; }

    /**
     * Returns the number of pixels rendered by sprite {@code idx} in the last completed
     * frame (current frame excluded, count reset at every vsync).
     */
    public long getSpritePixelCountLastFrame(int idx) {
        sync();
        return renderer.spritePixelCountLastFrame(idx);
    }

    /**
     * Returns the number of pixels rendered by sprite {@code idx} in the current frame
     * (still in progress, reset at the start of the frame).
     */
    public long getSpritePixelCountThisFrame(int idx) {
        sync();
        return renderer.spritePixelCountThisFrame(idx);
    }
}

