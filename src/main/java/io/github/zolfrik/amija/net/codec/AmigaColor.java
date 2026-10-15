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

package io.github.zolfrik.amija.net.codec;

import java.util.Arrays;

/**
 * Conversion between the framebuffer's 24-bit RGB pixel and the 12 bits of the Amiga colour.
 *
 * <p><b>Why it exists.</b> {@code AbstractDenise} always colorizes through {@code amigaToRGB()},
 * i.e. a 4096-entry LUT, and does so in HAM too: every pixel leaving Denise is therefore one of
 * only 4096 values (checked on 853 million pixels, zero exceptions; see
 * {@code docs/remote-play-plan.md}). It is the fact all of {@link FrameEncoder}'s compression
 * rests on: the frame is not a 16-million-colour image, it is 12-bit.
 *
 * <p><b>Why the inverse costs a 256-byte LUT and not a 4096-entry hash table.</b>
 * {@code AMIGA_RGB_LUT} builds each channel as {@code nibble * 17} (0x00, 0x11, ... 0xFF), so 16
 * channel values out of 256 are valid and the inverse is a plain table indexed by the channel
 * byte. Three loads per pixel, no hashing.
 *
 * <p><b>Domain.</b> The codec is defined on the pixel's low 24 bits; bits 24..31 (alpha) are
 * ignored when encoding and written as zero when decoding. {@code VirtualCRT} delivers the
 * raster with that byte already zero, so nothing is lost, but the rule is written down because
 * it is a contract, not a coincidence.
 */
public final class AmigaColor {

    /** Distinct values of the Amiga colour: 4 bits per channel. */
    public static final int LEVELS = 4096;

    /** Mask of the pixel bits the codec considers significant. */
    public static final int RGB_MASK = 0x00FFFFFF;

    private static final int[] TO_RGB = new int[LEVELS];
    private static final int[] FROM_CHANNEL = new int[256];

    static {
        Arrays.fill(FROM_CHANNEL, -1);
        for (int k = 0; k < 16; k++) {
            FROM_CHANNEL[k * 17] = k;
        }
        for (int c = 0; c < LEVELS; c++) {
            TO_RGB[c] = ((((c >> 8) & 0xF) * 17) << 16)
                      | ((((c >> 4) & 0xF) * 17) << 8)
                      |  (((c) & 0xF) * 17);
        }
    }

    private AmigaColor() {
    }

    /** 12-bit Amiga -> 24-bit RGB, exactly as Denise's LUT. */
    public static int toRgb(int amiga12) {
        return TO_RGB[amiga12 & 0xFFF];
    }

    /**
     * 24-bit RGB -> 12-bit Amiga, or {@code -1} if the pixel is not representable.
     *
     * <p>The {@code -1} is not theoretical even though it has never been observed: there is a
     * test branch in Denise ({@code useVAmigaColorLut}) that uses a different LUT, and nothing
     * guarantees that a future change of the rendering will not introduce other values.
     * Callers <b>must</b> handle it; {@link FrameEncoder} does so by falling back on the RGB
     * mode.
     */
    public static int fromRgb(int rgb) {
        int r = FROM_CHANNEL[(rgb >>> 16) & 0xFF];
        int g = FROM_CHANNEL[(rgb >>> 8) & 0xFF];
        int b = FROM_CHANNEL[rgb & 0xFF];
        return (r | g | b) < 0 ? -1 : (r << 8) | (g << 4) | b;
    }
}
