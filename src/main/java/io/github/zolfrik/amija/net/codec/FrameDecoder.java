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

import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Decodificatore video <b>AJV1</b>: ricostruisce il raster RGB da un payload di
 * {@link FrameEncoder} e dal frame di riferimento.
 *
 * <p>Possiede i propri buffer e non alloca per frame; non e' thread-safe.
 *
 * <h2>Questi byte vengono dalla rete</h2>
 * Il decodificatore e' l'unico punto del gioco a distanza in cui dati arbitrari di terzi
 * pilotano indici e lunghezze, quindi vale la regola del §8.2 del piano: <b>ogni campo di
 * lunghezza si confronta con un tetto costante prima di usarlo</b>. In concreto:
 * <ul>
 *   <li>il corpo inflato non puo' superare {@link FrameCodec#maxBodyBytes}, che dipende
 *       solo dalla geometria concordata nel WELCOME: il buffer e' allocato una volta alla
 *       costruzione e uno stream che ne produce di piu' viene interrotto, non assecondato;</li>
 *   <li>la lunghezza del corpo deve corrispondere <b>esattamente</b> a quella implicata
 *       dalla bitmap dei tile sporchi — ne' corta (troncamento) ne' lunga (byte di troppo,
 *       che sarebbero un canale nascosto o un errore di framing);</li>
 *   <li>ogni indice di palette e' verificato contro il numero di voci dichiarate.</li>
 * </ul>
 * Nessuna eccezione non controllata deve poter uscire da {@link #decode}: qualunque input
 * sbagliato diventa una {@link CodecException}, cioe' "scarta il frame e non ACKarlo".
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
     * Ricostruisce un frame.
     *
     * @param src       payload prodotto da {@link FrameEncoder#encode}
     * @param reference riferimento con cui e' stato codificato, o {@code null} per un keyframe
     * @param dst       raster di uscita, {@code width*height} pixel. Puo' essere lo stesso
     *                  array di {@code reference}: i tile puliti restano quello che sono e
     *                  i modi che leggono il riferimento lo fanno prima di scrivere lo
     *                  stesso indice, quindi la ricostruzione in loco e' sicura.
     * @throws CodecException se il payload e' malformato, incoerente o troncato. Tutte le
     *                        verifiche precedono la prima scrittura: o {@code dst} si
     *                        riempie per intero, o non viene toccato affatto.
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

        // La bitmap determina quanti pixel devono seguire: il conto e' l'unica prova che il
        // corpo e la sua intestazione parlino dello stesso frame.
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
            int q = p + dirtyPixels;   // la palette sta in fondo: vedi FrameCodec
            for (int i = 0; i < paletteCount; i++) {
                palette[i] = AmigaColor.toRgb((body[q] & 0xFF) | ((body[q + 1] & 0xFF) << 8));
                q += 2;
            }
            // Gli indici si validano TUTTI adesso, non mentre si scrive. Costa una passata
            // su byte gia' in cache, e compra una garanzia che serve al chiamante: decode()
            // o riesce del tutto o non tocca 'dst'. Senza, un indice fuori palette a meta'
            // frame lascerebbe la destinazione mezza scritta — e quando 'dst' e' anche il
            // riferimento (ricostruzione in loco) quello sarebbe il riferimento corrotto,
            // cioe' un errore che si trascina sui frame successivi invece di fermarsi qui.
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
    // Interno
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

    /** I tile puliti sono, per definizione, identici al riferimento (nero se non c'e'). */
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
                        // Il riferimento e' RGB: va riportato a 12 bit per applicare lo XOR.
                        // Che sia rappresentabile lo garantisce l'encoder, che passa al modo
                        // R quando non lo e'; qui un valore fuori tavolozza degrada a 0 e
                        // basta, senza far saltare la decodifica dell'intero frame.
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
