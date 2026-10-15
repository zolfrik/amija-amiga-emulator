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

import io.github.zolfrik.amija.custom.AbstractDenise.RegChangeRecorder;

/**
 * One completed scanline on its way from Denise's front end to its renderer.
 *
 * <p>{@link AbstractDenise#captureLine} fills it at the end of the line with everything the
 * renderer reads: the log of the shift-register loads, the register writes with the pixel at
 * which they act, and the live register values at the end of the line. The buffers and recorders are
 * swapped with the front end's, not copied. {@link AbstractDenise#renderLine} renders it and
 * leaves the results here, and the buffers come back empty.
 *
 * <p>A job is owned by one thread at a time: the front end between capture and hand-over, the
 * renderer from there until its results have been read.
 */
public final class DeniseLineJob {

    private static final int HPIXELS = AbstractDenise.RAW_LINE_PIXELS;

    // -- Inputs, written by the front end ------------------------------------------------

    // The shift-register log of the line (see AbstractDenise.bplEvents). Every event opens
    // with a header: the half-CCK step it happened at, its type and one argument bit. A load
    // is followed by the cursor and the three plane words of its group, already masked by
    // the BPU latched at load. A wide load (Lisa) is followed by the cursor, the word length
    // in bits and the four plane words of its group, each as two ints, high half first.
    static final int BPL_EV_LOAD = 0;
    static final int BPL_EV_HIRES = 1;
    static final int BPL_EV_LOAD_WIDE = 2;
    /** Lisa: change of super-hires; the argument is the new state. */
    static final int BPL_EV_SHRES = 3;
    static final int BPL_EV_TYPE = 3;
    /** Load: the even group. Resolution change: the new resolution is hires. */
    static final int BPL_EV_ARG = 4;
    static final int BPL_EV_STEP_SHIFT = 3;
    static final int BPL_LOAD_SIZE = 5;
    static final int BPL_LOAD_WIDE_SIZE = 11;
    static final int BPL_EVENTS_INITIAL = 1024;

    int[] bplEvents = new int[BPL_EVENTS_INITIAL];
    int bplEventCount;
    /** The live resolution when the line's log started. */
    boolean bplStartHires;
    /** Lisa: super-hires was on when the line's log started. */
    boolean bplStartShres;

    /** Raw bitplane colour index of the line: written and consumed by the renderer. */
    byte[] dBuffer = new byte[HPIXELS + 64 + 8];
    RegChangeRecorder conChanges = new RegChangeRecorder(128);
    RegChangeRecorder diwChanges = new RegChangeRecorder(128);
    RegChangeRecorder colChanges = new RegChangeRecorder(1024);
    RegChangeRecorder[] sprChanges = {
        new RegChangeRecorder(128), new RegChangeRecorder(128),
        new RegChangeRecorder(128), new RegChangeRecorder(128)
    };
    final int[] bpl1datPixels = new int[64];
    int bpl1datCount;

    int vpos;
    /** The line is outside the vertical blank area. */
    boolean visible;
    int maxHpos;
    int hcounterRenderHead;
    int clxcon;
    /** CLXCON2 (Lisa). */
    int clxcon2;

    /** Live register values at the end of the line. */
    int bplcon0, bplcon2, hstrt, hstop, armed;
    /** BPLCON3 and BPLCON4 at the end of the line; read on Lisa only. */
    int bplcon3, bplcon4;
    /** Lisa's FMODE at the end of the line. */
    int fmode;
    final int[] palette = new int[32];
    /** Lisa's 256 colour registers, 24-bit; filled on AGA only. */
    final int[] paletteAga = new int[256];
    final int[] sprpos  = new int[8];
    final int[] sprctl  = new int[8];
    final int[] sprdata = new int[8];
    final int[] sprdatb = new int[8];
    /** Lisa's sprite data registers, left-aligned; filled on AGA only. */
    final long[] sprdataWide = new long[8];
    final long[] sprdatbWide = new long[8];

    // -- Results, written by the renderer ------------------------------------------------

    final int[] rgb = new int[HPIXELS];
    int lastRenderedBplcon0;
    boolean vBorder;
    int wrapPlayfieldMask;

    /** The line's pixels, one per raw sub-pixel. */
    public int[] rgb() {
        return rgb;
    }

    /** BPLCON0 at the start of the line, the value its resolution and interlace are read from. */
    public int lastRenderedBplcon0() {
        return lastRenderedBplcon0;
    }

    /** The line is all vertical border: no bitplane output at all. */
    public boolean isVBorder() {
        return vBorder;
    }

    /**
     * Which of the sub-pixels of the two colour clocks past the end of the line, starting at
     * {@link AbstractDenise#RIGHT_HBLANK_WRAP_COL}, carry content (bit k = sub-pixel k). See
     * {@link AbstractDenise#isPlayfieldAt(int)}.
     */
    public int wrapPlayfieldMask() {
        return wrapPlayfieldMask;
    }
}
