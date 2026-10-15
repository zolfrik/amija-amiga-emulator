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

import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * <b>AJV1</b> video decoder: rebuilds the RGB raster from a {@link FrameEncoder} payload and
 * the reference frame.
 *
 * <p>It owns its buffers and allocates nothing per frame; not thread-safe.
 *
 * <h2>These bytes come from the network</h2>
 * The decoder is the only place in remote play where arbitrary third-party data drives
 * indices and lengths, so the rule of plan section 8.2 applies: <b>every length field is
 * compared with a constant cap before use</b>. In practice:
 * <ul>
 *   <li>the inflated body cannot exceed {@link FrameCodec#maxBodyBytes}, which depends only on
 *       the geometry agreed in the WELCOME: the buffer is allocated once at construction and a
 *       stream that produces more is interrupted, not indulged;</li>
 *   <li>the body length must match <b>exactly</b> the one implied by the dirty-tile bitmap:
 *       neither short (truncation) nor long (extra bytes, which would be a hidden channel or a
 *       framing error);</li>
 *   <li>every palette index is checked against the number of entries declared.</li>
 * </ul>
 * No unchecked exception must be able to leave {@link #decode}: any wrong input becomes a
 * {@link CodecException}, i.e. "discard the frame and do not ACK it".
 */
public final class FrameDecoder implements AutoCloseable {

    private final int width;
    private final int height;
    private final int pixelCount;
    private final int tilesAcross;
    private final int tilesDown;
    private final int tileCount;
    private final int bitmapBytes;

    private final boolean[] dirty;
    private final byte[] body;
    private final int[] palette = new int[FrameCodec.MAX_PALETTE];

    private final Inflater inflater = new Inflater(true);
    private boolean closed;

    private int mode;
    private int dirtyTiles;
    private int dirtyPixels;

    public FrameDecoder(int width, int height) {
        FrameCodec.checkGeometry(width, height);
        this.width = width;
        this.height = height;
        this.pixelCount = width * height;
        this.tilesAcross = FrameCodec.tilesAcross(width);
        this.tilesDown = FrameCodec.tilesDown(height);
        this.tileCount = tilesAcross * tilesDown;
        this.bitmapBytes = FrameCodec.bitmapBytes(width, height);
        this.dirty = new boolean[tileCount];
        this.body = new byte[FrameCodec.maxBodyBytes(width, height)];
    }

    /**
     * Rebuilds a frame.
     *
     * @param src       payload produced by {@link FrameEncoder#encode}
     * @param reference reference it was encoded with, or {@code null} for a keyframe
     * @param dst       output raster, {@code width*height} pixels. It may be the same array as
     *                  {@code reference}: clean tiles stay as they are and the modes that read
     *                  the reference do so before writing the same index, so in-place
     *                  reconstruction is safe.
     * @throws CodecException if the payload is malformed, inconsistent or truncated. Every
     *                        check precedes the first write: either {@code dst} is filled
     *                        entirely, or it is not touched at all.
     */
    public void decode(byte[] src, int off, int len, int[] reference, int[] dst)
            throws CodecException {
        if (closed) {
            throw new IllegalStateException("decoder chiuso");
        }
        if (dst == null || dst.length < pixelCount) {
            throw new IllegalArgumentException("destinazione di "
                    + (dst == null ? "null" : dst.length) + " pixel, attesi " + pixelCount);
        }
        if (reference != null && reference.length < pixelCount) {
            throw new IllegalArgumentException("riferimento di " + reference.length
                    + " pixel, attesi " + pixelCount);
        }
        if (src == null || off < 0 || len < 1 || off > src.length - len) {
            throw new CodecException("payload assente o fuori dai limiti");
        }

        mode = src[off] & 0xFF;
        if (mode != FrameCodec.MODE_PALETTE && mode != FrameCodec.MODE_XOR
                && mode != FrameCodec.MODE_RGB) {
            throw new CodecException("modo video sconosciuto: 0x" + Integer.toHexString(mode));
        }

        int bodyLength = inflate(src, off + 1, len - 1);
        if (bodyLength < bitmapBytes) {
            throw new CodecException("corpo di " + bodyLength
                    + " byte: non contiene nemmeno la bitmap dei tile (" + bitmapBytes + ")");
        }
        readBitmap();

        // The bitmap decides how many pixels must follow: the count is the only proof that
        // the body and its header talk about the same frame.
        int p = bitmapBytes;
        int paletteCount = 0;
        boolean indexed = false;
        int bytesPerPixel;
        if (mode == FrameCodec.MODE_PALETTE) {
            if (bodyLength < bitmapBytes + 3) {
                throw new CodecException("corpo troncato prima dell'intestazione di palette");
            }
            int flag = body[p] & 0xFF;
            if (flag > 1) {
                throw new CodecException("flag 'indexed' non valido: " + flag);
            }
            indexed = flag == 1;
            paletteCount = (body[p + 1] & 0xFF) | ((body[p + 2] & 0xFF) << 8);
            p += 3;
            if (!indexed && paletteCount != 0) {
                throw new CodecException("palette dichiarata (" + paletteCount
                        + ") con pixel a 12 bit grezzi");
            }
            if (paletteCount > FrameCodec.MAX_PALETTE) {
                throw new CodecException("palette di " + paletteCount + " voci, massimo "
                        + FrameCodec.MAX_PALETTE);
            }
            bytesPerPixel = indexed ? 1 : 2;
        } else {
            bytesPerPixel = mode == FrameCodec.MODE_RGB ? 3 : 2;
        }

        long expected = (long) p + (long) bytesPerPixel * dirtyPixels
                + (indexed ? 2L * paletteCount : 0L);
        if (expected != bodyLength) {
            throw new CodecException("corpo di " + bodyLength + " byte, attesi " + expected
                    + " per " + dirtyPixels + " pixel sporchi in modo "
                    + FrameCodec.modeName(mode));
        }

        if (indexed) {
            int q = p + dirtyPixels;   // the palette is at the end: see FrameCodec
            for (int i = 0; i < paletteCount; i++) {
                palette[i] = AmigaColor.toRgb((body[q] & 0xFF) | ((body[q + 1] & 0xFF) << 8));
                q += 2;
            }
            // ALL the indices are validated now, not while writing. It costs a pass over bytes
            // already in cache, and buys a guarantee the caller needs: decode() either succeeds
            // entirely or does not touch 'dst'. Otherwise an index outside the palette halfway
            // through the frame would leave the destination half written, and when 'dst' is
            // also the reference (in-place reconstruction) that would be a corrupt reference,
            // an error dragging on into the following frames instead of stopping here.
            for (int i = 0; i < dirtyPixels; i++) {
                if ((body[p + i] & 0xFF) >= paletteCount) {
                    throw new CodecException("indice di palette " + (body[p + i] & 0xFF)
                            + " fuori dalle " + paletteCount + " voci dichiarate");
                }
            }
        }

        fillClean(reference, dst);

        switch (mode) {
            case FrameCodec.MODE_PALETTE -> {
                if (indexed) {
                    applyPaletteIndices(p, dst);
                } else {
                    applyRaw12(p, dst);
                }
            }
            case FrameCodec.MODE_XOR -> applyXor(p, reference, dst);
            default -> applyRgb(p, dst);
        }
    }

    /** Modo dell'ultimo payload decodificato. */
    public int lastMode() {
        return mode;
    }

    public int lastDirtyTiles() {
        return dirtyTiles;
    }

    public int lastDirtyPixels() {
        return dirtyPixels;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            inflater.end();
        }
    }

    // =====================================================================
    // Internal
    // =====================================================================

    private int inflate(byte[] src, int off, int len) throws CodecException {
        inflater.reset();
        inflater.setInput(src, off, len);
        int total = 0;
        while (!inflater.finished()) {
            if (total == body.length) {
                throw new CodecException("corpo oltre il tetto di " + body.length + " byte");
            }
            int n;
            try {
                n = inflater.inflate(body, total, body.length - total);
            } catch (DataFormatException e) {
                throw new CodecException("stream Deflate non valido", e);
            }
            if (n == 0) {
                if (inflater.needsInput() || inflater.needsDictionary()) {
                    throw new CodecException("stream Deflate troncato dopo " + total + " byte");
                }
                if (!inflater.finished()) {
                    throw new CodecException("stream Deflate bloccato dopo " + total + " byte");
                }
            }
            total += n;
        }
        return total;
    }

    private void readBitmap() {
        int tiles = 0;
        int pixels = 0;
        for (int t = 0; t < tileCount; t++) {
            boolean d = (body[t >> 3] & (1 << (t & 7))) != 0;
            dirty[t] = d;
            if (d) {
                tiles++;
                int tx = t % tilesAcross;
                int ty = t / tilesAcross;
                pixels += (Math.min((tx + 1) * FrameCodec.TILE, width) - tx * FrameCodec.TILE)
                        * (Math.min((ty + 1) * FrameCodec.TILE, height) - ty * FrameCodec.TILE);
            }
        }
        dirtyTiles = tiles;
        dirtyPixels = pixels;
    }

    /** Clean tiles are, by definition, identical to the reference (black if there is none). */
    private void fillClean(int[] reference, int[] dst) {
        if (dst == reference) {
            return;
        }
        if (reference == null) {
            java.util.Arrays.fill(dst, 0, pixelCount, 0);
        } else {
            System.arraycopy(reference, 0, dst, 0, pixelCount);
        }
    }

    private void applyPaletteIndices(int p, int[] dst) {
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
                        dst[base + x] = palette[body[p++] & 0xFF];
                    }
                }
            }
        }
    }

    private void applyRaw12(int p, int[] dst) {
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
                        int c = (body[p] & 0xFF) | ((body[p + 1] & 0xFF) << 8);
                        p += 2;
                        dst[base + x] = AmigaColor.toRgb(c);
                    }
                }
            }
        }
    }

    private void applyXor(int p, int[] reference, int[] dst) {
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
                        int d = (body[p] & 0xFF) | ((body[p + 1] & 0xFF) << 8);
                        p += 2;
                        int idx = base + x;
                        // The reference is RGB: it must be brought back to 12 bits to apply the
                        // XOR. The encoder guarantees it is representable, switching to mode R
                        // when it is not; here a value outside the palette just degrades to 0,
                        // without failing the decoding of the whole frame.
                        int r = reference == null ? 0 : AmigaColor.fromRgb(reference[idx]);
                        dst[idx] = AmigaColor.toRgb((r < 0 ? 0 : r) ^ d);
                    }
                }
            }
        }
    }

    private void applyRgb(int p, int[] dst) {
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
                        dst[base + x] = (body[p] & 0xFF)
                                | ((body[p + 1] & 0xFF) << 8)
                                | ((body[p + 2] & 0xFF) << 16);
                        p += 3;
                    }
                }
            }
        }
    }
}
