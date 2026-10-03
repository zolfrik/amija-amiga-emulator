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

package io.github.zolfrik.amija.net.codec;

/**
 * Format of the <b>AJV1</b> video codec of remote play: constants and tile geometry, shared by
 * {@link FrameEncoder} and {@link FrameDecoder}.
 *
 * <h2>What a payload looks like</h2>
 * <pre>
 *   u8   mode                 'P' | 'X' | 'R'      (outside the deflate: needed to inflate it)
 *   ...  deflate-raw(body)                         (nowrap, i.e. without the zlib header)
 * </pre>
 *
 * The body always starts with the <b>dirty-tile bitmap</b>: the raster is divided into
 * {@value #TILE}x{@value #TILE} tiles and a tile is dirty if at least one pixel differs from the
 * reference. One bit per tile, row order, least significant bit first.
 *
 * <pre>
 *   body(P) := bitmap | u8 indexed | u16 palCount | pixel[] | palette[palCount]
 *   body(X) := bitmap | pixel[]
 *   body(R) := bitmap | pixel[]
 * </pre>
 *
 * <ul>
 *   <li><b>Mode P (palette)</b>: the pixels of the dirty tiles are <i>1-byte indices</i> into a
 *       per-frame palette (the distinct colours appearing in the dirty tiles). If they exceed
 *       {@value #MAX_PALETTE}, {@code indexed} is 0 and the pixels become absolute 12-bit
 *       values on 2 bytes, with no table.
 *       <p><b>The palette is at the end, not at the start</b>, on purpose: so the encoder
 *       emits the indices while discovering the colours, in <i>one</i> pass over the dirty
 *       pixels. Putting it first would force one more pass or a memory move of hundreds of
 *       KB, for zero bytes saved on the wire.</li>
 *   <li><b>Mode X (xor)</b>: the pixels of the dirty tiles are the 12-bit XOR with the
 *       reference, 2 bytes per pixel, little-endian.</li>
 *   <li><b>Mode R (rgb)</b>: fallback for a pixel not representable in 12 bits (see
 *       {@link AmigaColor#fromRgb}): <i>absolute</i> RGB values, 3 bytes per pixel, order b,
 *       g, r. It does not depend on the reference for the pixels it sends, so it stays right
 *       even if the reference contains colours outside the palette.</li>
 * </ul>
 *
 * <h2>Why two modes and not one</h2>
 * Measured ({@code docs/remote-play-plan.md} section 4.1): <b>neither dominates the other</b>,
 * and not by a little: P wins by up to 1.75x when the dirty tiles really all change (1
 * byte/pixel against 2, and with few colours the indices form long repetitions LZ77 devours),
 * X wins by up to 1.95x when the dirty tiles are wide but change little <i>inside</i>,
 * because there the XOR produces zeros while P still sends the absolute value of every pixel.
 * So both are encoded and the smaller is sent: by construction the result is never worse than
 * the better of the two.
 */
public final class FrameCodec {

    /**
     * Side of the tile. 16 measured against 32: equivalent in bandwidth, 32 slower. On the
     * 912x312 raster they give 57x20 = 1140 tiles, i.e. a 143-byte bitmap.
     */
    public static final int TILE = 16;

    /** Distinct colours beyond which mode P gives up on indices and switches to raw 12 bits. */
    public static final int MAX_PALETTE = 256;

    /** Lets the encoder pick the smallest mode (normal behaviour). */
    public static final int MODE_AUTO = 0;
    /** Indici in palette per-frame, 1 byte/pixel. */
    public static final int MODE_PALETTE = 'P';
    /** XOR a 12 bit col riferimento, 2 byte/pixel. */
    public static final int MODE_XOR = 'X';
    /** RGB assoluto a 24 bit, 3 byte/pixel: ripiego per pixel fuori dai 4096 colori. */
    public static final int MODE_RGB = 'R';

    private FrameCodec() {
    }

    /** Readable name of a mode, for logs and error messages. */
    public static String modeName(int mode) {
        return switch (mode) {
            case MODE_AUTO -> "auto";
            case MODE_PALETTE -> "P/palette";
            case MODE_XOR -> "X/xor";
            case MODE_RGB -> "R/rgb";
            default -> "sconosciuto(0x" + Integer.toHexString(mode & 0xFF) + ")";
        };
    }

    static int tilesAcross(int width) {
        return (width + TILE - 1) / TILE;
    }

    static int tilesDown(int height) {
        return (height + TILE - 1) / TILE;
    }

    static int bitmapBytes(int width, int height) {
        return (tilesAcross(width) * tilesDown(height) + 7) / 8;
    }

    /**
     * Safety cap on the inflated body, for the given geometry: the bulkiest mode (R, 3
     * bytes/pixel) with every tile dirty. {@link FrameDecoder} refuses any stream that produces
     * more than this <b>without allocating</b>, so a malicious {@code frameSize} cannot ask
     * for arbitrary memory (plan sections 4, 8.2).
     */
    static int maxBodyBytes(int width, int height) {
        return bitmapBytes(width, height) + 3 + 3 * width * height + 2 * MAX_PALETTE;
    }

    /**
     * Cap on Deflate's output for an input of {@code n} bytes. Deflate can <i>expand</i> an
     * incompressible input; zlib's margin is ~0.03%, here 12.5% plus 128 bytes is used
     * because it costs only virtual memory.
     */
    static int deflateBound(int n) {
        return n + (n >> 3) + 128;
    }

    static void checkGeometry(int width, int height) {
        if (width <= 0 || height <= 0 || (long) width * height > Integer.MAX_VALUE / 4) {
            throw new IllegalArgumentException("geometria non valida: " + width + "x" + height);
        }
    }
}
