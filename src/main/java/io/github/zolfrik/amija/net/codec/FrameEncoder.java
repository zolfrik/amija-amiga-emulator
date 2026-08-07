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

import java.util.zip.Deflater;

/**
 * Codificatore video <b>AJV1</b> del gioco a distanza: da un raster RGB e dal suo
 * riferimento produce il payload lossless descritto in {@link FrameCodec}.
 *
 * <p>Funzione pura sul piano logico — stesso ingresso, stessa uscita — ma <b>non</b> priva
 * di stato: possiede i propri buffer di lavoro e non alloca niente per frame, perche' gira
 * 50 volte al secondo. Non e' thread-safe: un'istanza per thread di codifica.
 *
 * <h2>La griglia di tile non serve a comprimere, serve a costare poco</h2>
 * Deflate azzererebbe comunque le zone ferme, quindi saltare i tile puliti quasi non cambia
 * i byte sul filo. Cambia il <b>tempo</b>, ed e' li' che si gioca tutto: la Fase 0 ha
 * misurato che una codifica che scandisce l'intero raster costa 2,7-4,0 ms per frame
 * <i>anche a schermo fermo</i>, perche' il lavoro non dipende da quanto e' cambiato. Con la
 * griglia si scende a 0,15-0,4 ms sul caso comune. Un emulatore che gira a 50 fps con poco
 * margine non puo' pagare 4 ms per un frame in cui non si e' mosso niente
 * ({@code docs/remote-play-plan.md} §11.3).
 *
 * <h2>Dettagli che sembrano micro-ottimizzazioni e non lo sono</h2>
 * <ul>
 *   <li>Il marcatore {@code seen[4096]} della palette si ripulisce <b>solo sulle voci
 *       usate</b>. Un {@code Arrays.fill} su 4096 booleani a ogni frame costerebbe piu'
 *       dell'intera codifica di una scena ferma (0,16 ms).</li>
 *   <li>I due modi si producono in <b>una sola passata</b> sui pixel sporchi, non due:
 *       l'unica conversione a 12 bit alimenta sia gli indici di palette sia lo XOR.</li>
 *   <li>La rilevazione dei tile sporchi confronta gli <b>int RGB</b>, non i 12 bit: cosi'
 *       non serve convertire il raster intero per sapere cosa e' cambiato.</li>
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

    /** Corpo del modo X: bitmap + 2 byte per pixel sporco. */
    private final byte[] bodyXor;
    /** Corpo del modo P: bitmap + 3 byte di intestazione + max 2 byte/pixel + palette. */
    private final byte[] bodyPal;
    /** Corpo del modo R. Allocato pigramente: in pratica non serve mai (vedi Fase 0). */
    private byte[] bodyRgb;

    /** Uscita compressa dei modi X e R (mutuamente esclusivi) e del modo P. */
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
    /** Lunghezza del corpo NON compresso, -1 se il modo non e' stato prodotto. */
    private int paletteBytes = -1;
    private int xorBytes = -1;
    /** Lunghezza del payload compresso di ciascun modo, -1 se non prodotto. */
    private int encodedPaletteBytes = -1;
    private int encodedXorBytes = -1;

    /** Encoder con il livello di compressione di default (quello con cui la Fase 0 ha misurato). */
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
     * Codifica {@code frame} contro {@code reference}, scegliendo il modo piu' piccolo.
     *
     * @param frame     raster RGB, {@code width*height} pixel; i bit 24..31 sono ignorati
     * @param reference raster di riferimento con la stessa geometria, oppure {@code null}
     *                  per un <b>keyframe</b> (delta contro il nero)
     * @return la lunghezza del payload, che si legge in {@link #payload()}
     */
    public int encode(int[] frame, int[] reference) {
        return encode(frame, reference, FrameCodec.MODE_AUTO);
    }

    /**
     * Come {@link #encode(int[], int[])} ma con il modo imposto.
     *
     * <p>Serve ai test per esercitare rami che la scelta automatica non raggiungerebbe mai
     * (il ripiego a 12 bit grezzi del modo P non e' mai stato osservato in Fase 0), e sara'
     * la leva dell'eventuale <b>isteresi</b> del §4.1: tenere il modo che ha vinto l'ultimo
     * frame dimezza il lavoro di codifica sul picco. Va fatta solo se la misura la chiede.
     *
     * <p>Il modo imposto non e' una garanzia: se il frame contiene un pixel fuori dai 4096
     * colori Amiga si ripiega comunque sul modo R, perche' l'alternativa sarebbe spedire
     * un frame sbagliato.
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
            // Modo X imposto, ma il riferimento contiene un pixel fuori tavolozza: lo XOR
            // non e' calcolabile e non resta che il modo assoluto.
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

        // A parita' vince P: 1 byte per pixel si decodifica con meta' delle letture.
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
     * Buffer che contiene il payload dell'ultima {@link #encode}, dall'offset 0 per
     * {@link #payloadLength()} byte. <b>E' un buffer interno</b>: vale fino alla codifica
     * successiva, chi deve conservarlo lo copia.
     */
    public byte[] payload() {
        return payload;
    }

    public int payloadLength() {
        return payloadLength;
    }

    /** Copia difensiva del payload, per i test e per chi deve accodarlo. */
    public byte[] payloadCopy() {
        byte[] copy = new byte[payloadLength];
        System.arraycopy(payload, 0, copy, 0, payloadLength);
        return copy;
    }

    /** Modo scelto dall'ultima codifica: {@code MODE_PALETTE}, {@code MODE_XOR} o {@code MODE_RGB}. */
    public int lastMode() {
        return mode;
    }

    public int lastDirtyTiles() {
        return dirtyTiles;
    }

    public int lastDirtyPixels() {
        return dirtyPixels;
    }

    /** Colori distinti nei tile sporchi, o 0 se il modo P non e' stato prodotto. */
    public int lastPaletteSize() {
        return paletteSize;
    }

    /** false = il modo P ha superato i 256 colori ed e' ripiegato sui 12 bit grezzi. */
    public boolean lastIndexed() {
        return indexed;
    }

    /**
     * Byte compressi che sarebbe costato il modo P, o -1 se non prodotto. Insieme a
     * {@link #lastXorBytes()} misura quanto rende davvero la scelta per frame — cioe' se
     * l'isteresi del §4.1 costerebbe poco o tanto.
     */
    public int lastPaletteBytes() {
        return encodedPaletteBytes;
    }

    /** Byte compressi che sarebbe costato il modo X, o -1 se non prodotto. */
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
    // Interno
    // =====================================================================

    /**
     * Marca i tile in cui almeno un pixel differisce dal riferimento. Il confronto e' sugli
     * int RGB con uscita anticipata al primo pixel diverso: su una scena in movimento la
     * maggior parte dei tile sporchi esce dopo pochi pixel.
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
     * Passata unica sui pixel sporchi: riempie il corpo del modo X e quello del modo P.
     *
     * @return false se un pixel del <b>frame</b> non e' rappresentabile a 12 bit, cioe' se
     *         l'unico modo lossless e' R. Un pixel non rappresentabile nel <b>riferimento</b>
     *         non e' invece fatale: disabilita il solo modo X, perche' P spedisce valori
     *         assoluti e non gli importa cosa ci fosse prima.
     */
    private boolean scanDirtyPixels(int[] cur, int[] ref, boolean wantXor, boolean wantPal) {
        int px = bitmapBytes;
        int pp = bitmapBytes + 3;   // dopo u8 indexed + u16 palCount, riempiti alla fine
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
            // Mai osservato in Fase 0. Il modo P rinuncia agli indici e spedisce i 12 bit
            // grezzi: seconda passata sui soli pixel sporchi, in un caso che non capita.
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

    /** Ripiego del modo P quando i colori distinti superano 256: valori assoluti a 12 bit. */
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

    /** Modo R: RGB assoluto, 3 byte per pixel sporco. Non guarda il riferimento. */
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

    /** Comprime {@code body[0..len)} in {@code dest[1..)}, con {@code dest[0]} = byte di modo. */
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
