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

package io.github.zolfrik.amija.common;

/**
 * The Amiga's video mode (PAL or NTSC) and its clock frequencies. The frequencies refer to the
 * colour clock (CCK), which is the master clock / 4.
 */
public enum VideoMode {
    /**
     * PAL: master clock = 28.37516 MHz, CCK = 7.093790 MHz.
     * Display: 256 active lines (x2 line doubling = 512 px), 288 in overscan (576 px).
     */
    PAL(7093790, 512, 576),

    /**
     * NTSC: master clock = 28.63636 MHz, CCK = 7.159090 MHz.
     * Display: 200 active lines (x2 line doubling = 400 px), 240 in overscan (480 px).
     */
    NTSC(7159090, 400, 480);

    /**
     * Viewport width without overscan: the HORIZONTAL timing is identical in PAL and NTSC
     * (227 CCK / 454 SC per line), so the width does not depend on the mode.
     */
    public static final int NORMAL_WIDTH = 640;

    /** Viewport width with overscan (horizontal identical in PAL/NTSC). */
    public static final int OVERSCAN_WIDTH = 724;

    private final long frequency;
    private final double periodNs;
    private final int normalHeight;
    private final int overscanHeight;

    VideoMode(long frequency, int normalHeight, int overscanHeight) {
        this.frequency = frequency;
        this.periodNs = 1_000_000_000.0 / frequency;
        this.normalHeight = normalHeight;
        this.overscanHeight = overscanHeight;
    }

    /**
     * Returns the frequency in Hz.
     */
    public long getFrequency() {
        return frequency;
    }

    /**
     * Returns the period of one cycle in nanoseconds.
     */
    public double getPeriodNs() {
        return periodNs;
    }

    /**
     * Width in pixels of the viewport (logical canvas) for this mode.
     *
     * @param overscan if {@code true} the overscan area is included.
     */
    public int displayWidth(boolean overscan) {
        return overscan ? OVERSCAN_WIDTH : NORMAL_WIDTH;
    }

    /**
     * Height in pixels of the viewport (logical canvas) for this mode. NTSC is shorter than PAL
     * (fewer active lines): 400/480 px against 512/576 px.
     *
     * @param overscan if {@code true} the overscan area is included.
     */
    public int displayHeight(boolean overscan) {
        return overscan ? overscanHeight : normalHeight;
    }
}
