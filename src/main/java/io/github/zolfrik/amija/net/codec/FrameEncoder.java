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

import java.util.zip.Deflater;

/**
 * <b>AJV1</b> video encoder of remote play: from an RGB raster and its reference it produces
 * the lossless payload described in {@link FrameCodec}.
 *
 * <p>Logically a pure function (same input, same output) but <b>not</b> stateless: it owns
 * its work buffers and allocates nothing per frame, because it runs 50 times a second. Not
 * thread-safe: one instance per encoding thread.
 *
 * <h2>The tile grid is not for compression, it is for low cost</h2>
 * Deflate would zero the still areas anyway, so skipping clean tiles hardly changes the bytes
 * on the wire. It changes the <b>time</b>, and that is what matters: an encoding that scans
 * the whole raster costs 2.7-4.0 ms per frame <i>even with a still screen</i>, because the
 * work does not depend on how much changed. With the grid it drops to 0.15-0.4 ms in the
 * common case. An emulator running at 50 fps with little margin cannot pay 4 ms for a frame
 * in which nothing moved ({@code docs/remote-play-plan.md} section 11.3).
 *
 * <h2>Details that look like micro-optimisations and are not</h2>
 * <ul>
 *   <li>The palette's {@code seen[4096]} marker is cleared <b>only on the entries used</b>.
 *       An {@code Arrays.fill} on 4096 booleans every frame would cost more than the whole
 *       encoding of a still scene (0.16 ms).</li>
 *   <li>The two modes are produced in <b>a single pass</b> over the dirty pixels, not two:
 *       the single 12-bit conversion feeds both the palette indices and the XOR.</li>
 *   <li>Dirty-tile detection compares the <b>RGB ints</b>, not the 12 bits: so the whole
 *       raster need not be converted to know what changed.</li>
 * </ul>
 */
public final class FrameEncoder implements AutoCloseable {

    private final int width;
    private final int height;
    private final int pixelCount;
    private final int tilesAcross;
    private final int tilesDown;
    private final int tileCount;
    private final int bitmapBytes;

    private final boolean[] dirty;

    /** Body of mode X: bitmap + 2 bytes per dirty pixel. */
    private final byte[] bodyXor;
    /** Body of mode P: bitmap + 3 header bytes + max 2 bytes/pixel + palette. */
    private final byte[] bodyPal;
    /** Body of mode R. Allocated lazily: in practice it is never needed. */
    private byte[] bodyRgb;

    /** Compressed output of modes X and R (mutually exclusive) and of mode P. */
    private final byte[] outXor;
    private final byte[] outPal;

    private final boolean[] seen = new boolean[AmigaColor.LEVELS];
    private final int[] palIndex = new int[AmigaColor.LEVELS];
    private final int[] palette = new int[FrameCodec.MAX_PALETTE];

    private final Deflater deflater;
    private boolean closed;

    // --- esito dell'ultima codifica -------------------------------------------------
    private byte[] payload;
    private int payloadLength;
    private int mode;
    private int dirtyTiles;
    private int dirtyPixels;
    private int paletteSize;
    private boolean indexed;
    /** Length of the UNcompressed body, -1 if the mode was not produced. */
    private int paletteBytes = -1;
    private int xorBytes = -1;
    /** Length of the compressed payload of each mode, -1 if not produced. */
    private int encodedPaletteBytes = -1;
    private int encodedXorBytes = -1;

    /** Encoder with the default compression level. */
    public FrameEncoder(int width, int height) {
        this(width, height, Deflater.DEFAULT_COMPRESSION);
    }

    public FrameEncoder(int width, int height, int deflateLevel) {
        FrameCodec.checkGeometry(width, height);
        this.width = width;
        this.height = height;
        this.pixelCount = width * height;
        this.tilesAcross = FrameCodec.tilesAcross(width);
        this.tilesDown = FrameCodec.tilesDown(height);
        this.tileCount = tilesAcross * tilesDown;
        this.bitmapBytes = FrameCodec.bitmapBytes(width, height);
        this.dirty = new boolean[tileCount];
        this.bodyXor = new byte[bitmapBytes + 2 * pixelCount];
        this.bodyPal = new byte[bitmapBytes + 3 + 2 * pixelCount + 2 * FrameCodec.MAX_PALETTE];
        this.outXor = new byte[1 + FrameCodec.deflateBound(FrameCodec.maxBodyBytes(width, height))];
        this.outPal = new byte[1 + FrameCodec.deflateBound(bodyPal.length)];
        this.deflater = new Deflater(deflateLevel, true);
    }

    // =====================================================================
    // API
    // =====================================================================

    /**
     * Encodes {@code frame} against {@code reference}, choosing the smallest mode.
     *
     * @param frame     RGB raster, {@code width*height} pixels; bits 24..31 are ignored
     * @param reference reference raster with the same geometry, or {@code null} for a
     *                  <b>keyframe</b> (delta against black)
     * @return the length of the payload, read through {@link #payload()}
     */
    public int encode(int[] frame, int[] reference) {
        return encode(frame, reference, FrameCodec.MODE_AUTO);
    }

    /**
     * Like {@link #encode(int[], int[])} but with the mode imposed.
     *
     * <p>Used by the tests to exercise branches the automatic choice would never reach (the
     * raw 12-bit fallback of mode P has never been observed), and the lever for a possible
     * <b>hysteresis</b> (section 4.1): keeping the mode that won the last frame halves the
     * encoding work at the peak. To be done only if measurement calls for it.
     *
     * <p>The imposed mode is not a guarantee: if the frame contains a pixel outside the 4096
     * Amiga colours, mode R is used anyway, because the alternative would be sending a wrong
     * frame.
     */
    public int encode(int[] frame, int[] reference, int forcedMode) {
        if (closed) {
            throw new IllegalStateException("encoder chiuso");
        }
        if (frame == null || frame.length < pixelCount) {
            throw new IllegalArgumentException("frame di " + (frame == null ? "null" : frame.length)
                    + " pixel, attesi " + pixelCount);
        }
        if (reference != null && reference.length < pixelCount) {
            throw new IllegalArgumentException("riferimento di " + reference.length
                    + " pixel, attesi " + pixelCount);
        }

        paletteBytes = -1;
        xorBytes = -1;
        encodedPaletteBytes = -1;
        encodedXorBytes = -1;
        paletteSize = 0;
        indexed = false;

        markDirty(frame, reference);
        writeBitmap(bodyXor);
        writeBitmap(bodyPal);

        if (forcedMode == FrameCodec.MODE_RGB) {
            return emitRgb(frame);
        }

        boolean wantXor = forcedMode == FrameCodec.MODE_AUTO || forcedMode == FrameCodec.MODE_XOR;
        boolean wantPal = forcedMode == FrameCodec.MODE_AUTO || forcedMode == FrameCodec.MODE_PALETTE;
        if (!wantXor && !wantPal) {
            throw new IllegalArgumentException("modo sconosciuto: " + FrameCodec.modeName(forcedMode));
        }

        if (!scanDirtyPixels(frame, reference, wantXor, wantPal)) {
            return emitRgb(frame);   // pixel fuori dai 4096 colori: unica via lossless
        }
        if (xorBytes < 0 && paletteBytes < 0) {
            // Mode X imposed, but the reference contains a pixel outside the palette: the XOR
            // cannot be computed and only the absolute mode is left.
            return emitRgb(frame);
        }

        if (xorBytes >= 0) {
            encodedXorBytes = deflateInto(bodyXor, xorBytes, outXor, FrameCodec.MODE_XOR);
        }
        if (paletteBytes >= 0) {
            encodedPaletteBytes = deflateInto(bodyPal, paletteBytes, outPal, FrameCodec.MODE_PALETTE);
        }

        int lenXor = encodedXorBytes < 0 ? Integer.MAX_VALUE : encodedXorBytes;
        int lenPal = encodedPaletteBytes < 0 ? Integer.MAX_VALUE : encodedPaletteBytes;

        // On a tie P wins: 1 byte per pixel decodes with half the reads.
        if (lenPal <= lenXor) {
            payload = outPal;
            payloadLength = lenPal;
            mode = FrameCodec.MODE_PALETTE;
        } else {
            payload = outXor;
            payloadLength = lenXor;
            mode = FrameCodec.MODE_XOR;
        }
        return payloadLength;
    }

    /**
     * Buffer holding the payload of the last {@link #encode}, from offset 0 for
     * {@link #payloadLength()} bytes. <b>It is an internal buffer</b>: valid until the next
     * encoding; whoever must keep it copies it.
     */
    public byte[] payload() {
        return payload;
    }

    public int payloadLength() {
        return payloadLength;
    }

    /** Defensive copy of the payload, for the tests and for whoever must queue it. */
    public byte[] payloadCopy() {
        byte[] copy = new byte[payloadLength];
        System.arraycopy(payload, 0, copy, 0, payloadLength);
        return copy;
    }

    /** Mode chosen by the last encoding: {@code MODE_PALETTE}, {@code MODE_XOR} or {@code MODE_RGB}. */
    public int lastMode() {
        return mode;
    }

    public int lastDirtyTiles() {
        return dirtyTiles;
    }

    public int lastDirtyPixels() {
        return dirtyPixels;
    }

    /** Distinct colours in the dirty tiles, or 0 if mode P was not produced. */
    public int lastPaletteSize() {
        return paletteSize;
    }

    /** false = mode P went past 256 colours and fell back on raw 12 bits. */
    public boolean lastIndexed() {
        return indexed;
    }

    /**
     * Compressed bytes mode P would have cost, or -1 if not produced. Together with
     * {@link #lastXorBytes()} it measures how much the per-frame choice really yields, i.e.
     * whether the hysteresis of section 4.1 would cost little or much.
     */
    public int lastPaletteBytes() {
        return encodedPaletteBytes;
    }

    /** Compressed bytes mode X would have cost, or -1 if not produced. */
    public int lastXorBytes() {
        return encodedXorBytes;
    }

    public int totalTiles() {
        return tileCount;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            deflater.end();
        }
    }

    // =====================================================================
    // Internal
    // =====================================================================

    /**
     * Marks the tiles in which at least one pixel differs from the reference. The comparison
     * is on the RGB ints with an early exit at the first different pixel: on a moving scene
     * most dirty tiles exit after a few pixels.
     */
    private void markDirty(int[] cur, int[] ref) {
        int tiles = 0;
        int pixels = 0;
        int t = 0;
        for (int ty = 0; ty < tilesDown; ty++) {
            int y0 = ty * FrameCodec.TILE;
            int y1 = Math.min(y0 + FrameCodec.TILE, height);
            for (int tx = 0; tx < tilesAcross; tx++, t++) {
                int x0 = tx * FrameCodec.TILE;
                int x1 = Math.min(x0 + FrameCodec.TILE, width);
                boolean d = false;
                scan:
                for (int y = y0; y < y1; y++) {
                    int base = y * width;
                    if (ref == null) {
                        for (int x = x0; x < x1; x++) {
                            if ((cur[base + x] & AmigaColor.RGB_MASK) != 0) {
                                d = true;
                                break scan;
                            }
                        }
                    } else {
                        for (int x = x0; x < x1; x++) {
                            if (((cur[base + x] ^ ref[base + x]) & AmigaColor.RGB_MASK) != 0) {
                                d = true;
                                break scan;
                            }
                        }
                    }
                }
                dirty[t] = d;
                if (d) {
                    tiles++;
                    pixels += (x1 - x0) * (y1 - y0);
                }
            }
        }
        dirtyTiles = tiles;
        dirtyPixels = pixels;
    }

    private void writeBitmap(byte[] body) {
        for (int i = 0; i < bitmapBytes; i++) {
            int b = 0;
            int base = i * 8;
            int n = Math.min(8, tileCount - base);
            for (int k = 0; k < n; k++) {
                if (dirty[base + k]) {
                    b |= 1 << k;
                }
            }
            body[i] = (byte) b;
        }
    }

    /**
     * Single pass over the dirty pixels: fills the bodies of mode X and mode P.
     *
     * @return false if a pixel of the <b>frame</b> cannot be represented in 12 bits, i.e. if
     *         the only lossless mode is R. A pixel not representable in the <b>reference</b> is
     *         not fatal: it disables mode X only, because P sends absolute values and does
     *         not care what was there before.
     */
    private boolean scanDirtyPixels(int[] cur, int[] ref, boolean wantXor, boolean wantPal) {
        int px = bitmapBytes;
        int pp = bitmapBytes + 3;   // after u8 indexed + u16 palCount, filled at the end
        int nPal = 0;
        boolean overflow = false;
        int t = 0;

        for (int ty = 0; ty < tilesDown; ty++) {
            int y0 = ty * FrameCodec.TILE;
            int y1 = Math.min(y0 + FrameCodec.TILE, height);
            for (int tx = 0; tx < tilesAcross; tx++, t++) {
                if (!dirty[t]) {
                    continue;
                }
                int x0 = tx * FrameCodec.TILE;
                int x1 = Math.min(x0 + FrameCodec.TILE, width);
                for (int y = y0; y < y1; y++) {
                    int base = y * width;
                    for (int x = x0; x < x1; x++) {
                        int idx = base + x;
                        int c = AmigaColor.fromRgb(cur[idx]);
                        if (c < 0) {
                            clearPalette(nPal);
                            return false;
                        }
                        if (wantXor) {
                            int r = ref == null ? 0 : AmigaColor.fromRgb(ref[idx]);
                            if (r < 0) {
                                wantXor = false;
                            } else {
                                int d = c ^ r;
                                bodyXor[px++] = (byte) d;
                                bodyXor[px++] = (byte) (d >>> 8);
                            }
                        }
                        if (wantPal && !overflow) {
                            if (!seen[c]) {
                                if (nPal == FrameCodec.MAX_PALETTE) {
                                    overflow = true;
                                } else {
                                    seen[c] = true;
                                    palIndex[c] = nPal;
                                    palette[nPal++] = c;
                                }
                            }
                            if (!overflow) {
                                bodyPal[pp++] = (byte) palIndex[c];
                            }
                        }
                    }
                }
            }
        }

        xorBytes = wantXor ? px : -1;

        if (!wantPal) {
            clearPalette(nPal);
            paletteBytes = -1;
        } else if (overflow) {
            // Never observed. Mode P gives up the indices and sends the raw 12 bits: a second
            // pass over the dirty pixels only, in a case that does not happen.
            clearPalette(nPal);
            paletteSize = 0;
            indexed = false;
            paletteBytes = writeRaw12(cur);
        } else {
            paletteSize = nPal;
            indexed = true;
            bodyPal[bitmapBytes] = 1;
            bodyPal[bitmapBytes + 1] = (byte) nPal;
            bodyPal[bitmapBytes + 2] = (byte) (nPal >>> 8);
            for (int i = 0; i < nPal; i++) {
                int c = palette[i];
                bodyPal[pp++] = (byte) c;
                bodyPal[pp++] = (byte) (c >>> 8);
                seen[c] = false;
            }
            paletteBytes = pp;
        }
        return true;
    }

    /** Fallback of mode P when the distinct colours exceed 256: absolute 12-bit values. */
    private int writeRaw12(int[] cur) {
        bodyPal[bitmapBytes] = 0;
        bodyPal[bitmapBytes + 1] = 0;
        bodyPal[bitmapBytes + 2] = 0;
        int p = bitmapBytes + 3;
        int t = 0;
        for (int ty = 0; ty < tilesDown; ty++) {
            int y0 = ty * FrameCodec.TILE;
            int y1 = Math.min(y0 + FrameCodec.TILE, height);
            for (int tx = 0; tx < tilesAcross; tx++, t++) {
                if (!dirty[t]) {
                    continue;
                }
                int x0 = tx * FrameCodec.TILE;
                int x1 = Math.min(x0 + FrameCodec.TILE, width);
                for (int y = y0; y < y1; y++) {
                    int base = y * width;
                    for (int x = x0; x < x1; x++) {
                        int c = AmigaColor.fromRgb(cur[base + x]);
                        bodyPal[p++] = (byte) c;
                        bodyPal[p++] = (byte) (c >>> 8);
                    }
                }
            }
        }
        return p;
    }

    /** Mode R: absolute RGB, 3 bytes per dirty pixel. It does not look at the reference. */
    private int emitRgb(int[] cur) {
        if (bodyRgb == null) {
            bodyRgb = new byte[bitmapBytes + 3 * pixelCount];
        }
        writeBitmap(bodyRgb);
        int p = bitmapBytes;
        int t = 0;
        for (int ty = 0; ty < tilesDown; ty++) {
            int y0 = ty * FrameCodec.TILE;
            int y1 = Math.min(y0 + FrameCodec.TILE, height);
            for (int tx = 0; tx < tilesAcross; tx++, t++) {
                if (!dirty[t]) {
                    continue;
                }
                int x0 = tx * FrameCodec.TILE;
                int x1 = Math.min(x0 + FrameCodec.TILE, width);
                for (int y = y0; y < y1; y++) {
                    int base = y * width;
                    for (int x = x0; x < x1; x++) {
                        int rgb = cur[base + x];
                        bodyRgb[p++] = (byte) rgb;
                        bodyRgb[p++] = (byte) (rgb >>> 8);
                        bodyRgb[p++] = (byte) (rgb >>> 16);
                    }
                }
            }
        }
        paletteBytes = -1;
        xorBytes = -1;
        encodedPaletteBytes = -1;
        encodedXorBytes = -1;
        paletteSize = 0;
        indexed = false;
        payload = outXor;
        payloadLength = deflateInto(bodyRgb, p, outXor, FrameCodec.MODE_RGB);
        mode = FrameCodec.MODE_RGB;
        return payloadLength;
    }

    /** Compresses {@code body[0..len)} into {@code dest[1..)}, with {@code dest[0]} = mode byte. */
    private int deflateInto(byte[] body, int len, byte[] dest, int modeByte) {
        dest[0] = (byte) modeByte;
        deflater.reset();
        deflater.setInput(body, 0, len);
        deflater.finish();
        int n = 1;
        while (!deflater.finished()) {
            if (n == dest.length) {
                throw new IllegalStateException("buffer di uscita insufficiente: "
                        + dest.length + " byte per un corpo di " + len);
            }
            int k = deflater.deflate(dest, n, dest.length - n);
            if (k == 0 && !deflater.finished()) {
                throw new IllegalStateException("Deflate bloccato dopo " + n + " byte");
            }
            n += k;
        }
        return n;
    }

    private void clearPalette(int nPal) {
        for (int i = 0; i < nPal; i++) {
            seen[palette[i]] = false;
        }
    }
}
