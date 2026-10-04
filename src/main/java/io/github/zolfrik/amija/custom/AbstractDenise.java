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
        renderer.setEcs(isECS);
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

    private int     hstrt,   hstop;

    private int clxcon;

    // Game port 1 (mouse) and port 2 (joystick), optional
    private GamePort gamePort1 = null;
    private GamePort gamePort2 = null;

    // =========================================================================
    // Palette (12-bit Amiga: 0x0RGB)
    // =========================================================================

    private final int[] palette = new int[32];
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
    private int armed;                 // bitmask of the armed sprites

    /** The pixels of the BPL1DAT writes of the line, in the order they came. */
    private final int[] bpl1datPixels = new int[64];
    private int bpl1datCount;

    // =========================================================================
    // Line buffer and line rendering
    // =========================================================================

    /**
     * dBuffer: raw bitplane colour index (before priority) of the line being emitted. At the
     * end of the line it is handed to the renderer in a {@link DeniseLineJob} and an empty one
     * takes its place.
     */
    private byte[] dBuffer = new byte[HPIXELS + 64 + 8];

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
        bpu   = deniseBpu(bplcon0);
        hires = (bplcon0 & 0x8000) != 0;
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
    static int deniseBpu(int bplcon0) {
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
        hstrt = 0; hstop = 0;
        clxcon = 0;
        if (hardware) {
            Arrays.fill(palette,  0);
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
        armed = 0;
        Arrays.fill(dBuffer, (byte) 0);
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
            case 0x00E: sync(); return renderer.readClxdat();

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
        armed    |= (1 << x);
        sprdata[x] = value & 0xFFFF;
        sprChanges[x / 2].insert(pixel + SPR_DAT_PX, 0x144 + x * 8, value);
    }

    private void pokeSPRxDATB(int x, int value, int pixel) {
        sprdatb[x] = value & 0xFFFF;
        sprChanges[x / 2].insert(pixel + SPR_DAT_PX, 0x146 + x * 8, value);
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
        // A word armed at the end of the line cannot shift out completely: the CCKs to do so
        // are gone. Its remaining pixels are written here: it is the tail of the line, past
        // the fetch window. See srCount.
        flushShiftOut();

        final byte[] d = dBuffer;
        dBuffer = job.dBuffer;
        job.dBuffer = d;
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
        job.bplcon0 = bplcon0;
        job.bplcon2 = bplcon2;
        job.hstrt = hstrt;
        job.hstop = hstop;
        job.armed = armed;
        System.arraycopy(palette, 0, job.palette, 0, 32);
        System.arraycopy(sprpos,  0, job.sprpos,  0, 8);
        System.arraycopy(sprctl,  0, job.sprctl,  0, 8);
        System.arraycopy(sprdata, 0, job.sprdata, 0, 8);
        System.arraycopy(sprdatb, 0, job.sprdatb, 0, 8);
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
    public int getDiwHStartPixel()  { return (hstrt - HBLANK_MIN * 2) * 2; }

    /** Returns the x coordinate (in raw pixels) of the right edge of the display window. */
    public int getDiwHStopPixel()   { return (hstop - HBLANK_MIN * 2) * 2; }

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

