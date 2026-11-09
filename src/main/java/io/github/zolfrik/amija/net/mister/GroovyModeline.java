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

package io.github.zolfrik.amija.net.mister;

/**
 * A video mode for the MiSTer, in modeline terms.
 *
 * <p>{@code hBegin}/{@code hEnd} are the start and end of horizontal sync, likewise
 * {@code vBegin}/{@code vEnd}. {@code interlace} is 0 for progressive, 1 for interlaced
 * (each blit carries one field of {@code vActive / 2} lines), 2 for a progressive
 * framebuffer shown on an interlaced modeline.
 *
 * <p>The Amiga presets are built from the emulated raster itself (see {@link #amiga}): the
 * hires pixel clock, one modeline line per emulated line, and both syncs placed where Agnus
 * places them, so the picture sits on the screen where a real machine would put it.
 */
public record GroovyModeline(double pclockMHz, int hActive, int hBegin, int hEnd, int hTotal,
        int vActive, int vBegin, int vEnd, int vTotal, int interlace) {

    public static final double PAL_PCLOCK_MHZ = 14.187580;
    public static final double NTSC_PCLOCK_MHZ = 14.318180;

    /** Visible width sent to the MiSTer, in hires pixels. */
    public static final int AMIGA_WIDTH = 720;
    /**
     * First raw raster column sent. The raw line starts at horizontal sync (colour clock 18)
     * with four pixels per colour clock, so this is colour clock 49.
     */
    public static final int AMIGA_FIRST_COLUMN = 124;
    /** Horizontal sync lasts from colour clock 18 to 35: 17 colour clocks. */
    public static final int AMIGA_HSYNC_PIXELS = 17 * 4;
    /** First raster line after vertical blanking: PAL. */
    public static final int AMIGA_FIRST_ROW_PAL = 26;
    /** First raster line after vertical blanking: NTSC. */
    public static final int AMIGA_FIRST_ROW_NTSC = 21;

    /**
     * User adjustment of the picture on the tube.
     *
     * @param hShift pixels to move the picture right (negative: left), in hires pixels
     * @param vShift lines to move the picture down (negative: up)
     */
    public record Geometry(int hShift, int vShift) {

        public static final int H_SHIFT_MAX = 64;
        public static final int V_SHIFT_MAX = 20;
        public static final Geometry NEUTRAL = new Geometry(0, 0);

        public Geometry {
            if (Math.abs(hShift) > H_SHIFT_MAX) {
                throw new IllegalArgumentException("hshift outside +-" + H_SHIFT_MAX + ": "
                        + hShift);
            }
            if (Math.abs(vShift) > V_SHIFT_MAX) {
                throw new IllegalArgumentException("vshift outside +-" + V_SHIFT_MAX + ": "
                        + vShift);
            }
        }
    }

    public static final GroovyModeline PAL = amiga(false, false);
    public static final GroovyModeline PAL_LACE = amiga(false, true);
    public static final GroovyModeline NTSC = amiga(true, false);
    public static final GroovyModeline NTSC_LACE = amiga(true, true);

    public GroovyModeline {
        if (!(pclockMHz > 0) || pclockMHz > 200) {
            throw new IllegalArgumentException("pclock " + pclockMHz);
        }
        if (hActive <= 0 || hActive > hBegin || hBegin >= hEnd || hEnd >= hTotal
                || hTotal > 0xFFFF) {
            throw new IllegalArgumentException("horizontal timing " + hActive + "/" + hBegin
                    + "/" + hEnd + "/" + hTotal);
        }
        if (vActive <= 0 || vActive > vBegin || vBegin >= vEnd || vEnd >= vTotal
                || vTotal > 0xFFFF) {
            throw new IllegalArgumentException("vertical timing " + vActive + "/" + vBegin
                    + "/" + vEnd + "/" + vTotal);
        }
        if (interlace < 0 || interlace > 2) {
            throw new IllegalArgumentException("interlace " + interlace);
        }
        if (interlace == 1 && (vActive & 1) != 0) {
            throw new IllegalArgumentException("interlaced vActive must be even: " + vActive);
        }
    }

    /**
     * The modeline that reproduces the Amiga raster.
     *
     * <p>Horizontally: a PAL line is 227 colour clocks, 908 hires pixels; an NTSC line
     * alternates 227 and 228, so its modeline line is the average, 910. The window starts at
     * raw column {@link #AMIGA_FIRST_COLUMN}, and sync starts where the next raw line starts.
     *
     * <p>Vertically: the window starts at the first line after vertical blanking and runs to
     * the last line of the frame; vertical sync covers lines 3-5 (PAL) or 3-6 (NTSC) of the
     * next frame. Interlaced, every vertical figure doubles and the frame gains the half line.
     */
    public static GroovyModeline amiga(boolean ntsc, boolean interlaced) {
        return amiga(ntsc, interlaced, Geometry.NEUTRAL);
    }

    /**
     * {@link #amiga(boolean, boolean)} adjusted by the user.
     *
     * <p>Shifting moves the syncs, not the window: the same raster is sent, and it lands
     * elsewhere on the tube. Where the adjustment leaves less room than the window needs,
     * the window loses its right columns or bottom lines, which lie outside the tube anyway.
     */
    public static GroovyModeline amiga(boolean ntsc, boolean interlaced, Geometry g) {
        int hTotal = ntsc ? 910 : 908;
        double pclock = ntsc ? NTSC_PCLOCK_MHZ : PAL_PCLOCK_MHZ;
        int hSync = AMIGA_HSYNC_PIXELS;
        // Pixels from the start of hsync to the first pixel of the window.
        int startPx = Math.max(AMIGA_FIRST_COLUMN + g.hShift(), hSync + 8);
        int hBegin = hTotal - startPx;
        int hActive = Math.min(AMIGA_WIDTH, hBegin);

        int vTotal = ntsc ? 262 : 312;
        int firstRow = ntsc ? AMIGA_FIRST_ROW_NTSC : AMIGA_FIRST_ROW_PAL;
        int vsyncStart = 3;
        int vsyncEnd = ntsc ? 6 : 5;
        // Upwards the picture can move only until vertical sync reaches the end of the frame.
        int vBegin = Math.min(vTotal + vsyncStart - firstRow - g.vShift(),
                vTotal - (vsyncEnd - vsyncStart) - 1);
        int vEnd = vBegin + vsyncEnd - vsyncStart;
        int vActive = Math.min(vTotal - firstRow, vBegin);
        if (interlaced) {
            return new GroovyModeline(pclock, hActive, hBegin, hBegin + hSync, hTotal,
                    vActive * 2, vBegin * 2, vEnd * 2, vTotal * 2 + 1, 1);
        }
        return new GroovyModeline(pclock, hActive, hBegin, hBegin + hSync, hTotal,
                vActive, vBegin, vEnd, vTotal, 0);
    }

    /** First raw raster row of the window for this standard. */
    public static int amigaFirstRow(boolean ntsc) {
        return ntsc ? AMIGA_FIRST_ROW_NTSC : AMIGA_FIRST_ROW_PAL;
    }

    public boolean interlaced() {
        return interlace != 0;
    }

    /** Lines carried by one blit. */
    public int linesPerBlit() {
        return interlace == 1 ? vActive / 2 : vActive;
    }

    /** Bytes of one uncompressed blit in the given pixel layout. */
    public int blitBytes(GroovyProtocol.RgbMode rgb) {
        return hActive * linesPerBlit() * rgb.bytesPerPixel;
    }

    public double lineRateHz() {
        return pclockMHz * 1e6 / hTotal;
    }

    /** Rate at which the MiSTer consumes blits: fields per second when interlaced. */
    public double blitRateHz() {
        double frame = lineRateHz() / vTotal;
        return interlaced() ? frame * 2 : frame;
    }

    /** Nanoseconds per blit, the period the emulation has to match. */
    public double blitPeriodNs() {
        return 1e9 / blitRateHz();
    }
}
