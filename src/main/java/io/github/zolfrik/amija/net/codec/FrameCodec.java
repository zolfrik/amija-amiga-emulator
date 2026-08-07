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
 * Formato del codec video <b>AJV1</b> del gioco a distanza: costanti e geometria dei tile,
 * condivise da {@link FrameEncoder} e {@link FrameDecoder}.
 *
 * <h2>Come e' fatto un payload</h2>
 * <pre>
 *   u8   mode                 'P' | 'X' | 'R'      (fuori dal deflate: serve per inflarlo)
 *   ...  deflate-raw(corpo)                        (nowrap, cioe' senza intestazione zlib)
 * </pre>
 *
 * Il corpo comincia sempre con la <b>bitmap dei tile sporchi</b>: il raster e' diviso in
 * tile {@value #TILE}x{@value #TILE} e un tile e' sporco se almeno un pixel differisce dal
 * riferimento. Un bit per tile, ordine per righe, bit meno significativo per primo.
 *
 * <pre>
 *   corpo(P) := bitmap | u8 indexed | u16 palCount | pixel[] | palette[palCount]
 *   corpo(X) := bitmap | pixel[]
 *   corpo(R) := bitmap | pixel[]
 * </pre>
 *
 * <ul>
 *   <li><b>Modo P (palette)</b> — i pixel dei tile sporchi sono <i>indici a 1 byte</i> in
 *       una palette per-frame (i colori distinti che compaiono nei tile sporchi). Se
 *       superano {@value #MAX_PALETTE}, {@code indexed} vale 0 e i pixel diventano valori
 *       assoluti a 12 bit su 2 byte, senza tabella.
 *       <p><b>La palette sta in fondo, non in testa</b>, ed e' voluto: cosi' l'encoder
 *       emette gli indici mentre scopre i colori, in <i>una</i> passata sui pixel sporchi.
 *       Metterla in testa costringerebbe a una passata in piu' o a uno spostamento di
 *       memoria di centinaia di KB, per zero byte risparmiati sul filo.</li>
 *   <li><b>Modo X (xor)</b> — i pixel dei tile sporchi sono lo XOR a 12 bit col riferimento,
 *       2 byte per pixel, little-endian.</li>
 *   <li><b>Modo R (rgb)</b> — ripiego per il pixel non rappresentabile a 12 bit (§
 *       {@link AmigaColor#fromRgb}): valori RGB <i>assoluti</i>, 3 byte per pixel, ordine
 *       b, g, r. Non dipende dal riferimento per i pixel che spedisce, quindi resta
 *       corretto anche se e' il riferimento a contenere colori fuori tavolozza.</li>
 * </ul>
 *
 * <h2>Perche' due modi e non uno</h2>
 * La Fase 0 (vedi {@code docs/remote-play-plan.md} §4.1) ha misurato che <b>nessuno dei due
 * domina l'altro</b>, e non di poco: P vince fino a 1,75x quando i tile sporchi cambiano
 * davvero tutti (1 byte/pixel contro 2, e con pochi colori gli indici formano ripetizioni
 * lunghe che LZ77 divora), X vince fino a 1,95x quando i tile sporchi sono larghi ma
 * <i>dentro</i> cambiano poco, perche' li' lo XOR produce zeri mentre P spedisce comunque
 * il valore assoluto di ogni pixel. Quindi si codifica con entrambi e si spedisce il piu'
 * piccolo: per costruzione il risultato non e' mai peggiore del migliore dei due.
 */
public final class FrameCodec {

    /**
     * Lato del tile. 16 misurato contro 32 in Fase 0: equivalenti come banda, 32 piu' lento.
     * Sul raster 912x312 danno 57x20 = 1140 tile, cioe' una bitmap da 143 byte.
     */
    public static final int TILE = 16;

    /** Colori distinti oltre i quali il modo P rinuncia agli indici e passa ai 12 bit grezzi. */
    public static final int MAX_PALETTE = 256;

    /** Lascia scegliere all'encoder il modo piu' piccolo (comportamento normale). */
    public static final int MODE_AUTO = 0;
    /** Indici in palette per-frame, 1 byte/pixel. */
    public static final int MODE_PALETTE = 'P';
    /** XOR a 12 bit col riferimento, 2 byte/pixel. */
    public static final int MODE_XOR = 'X';
    /** RGB assoluto a 24 bit, 3 byte/pixel: ripiego per pixel fuori dai 4096 colori. */
    public static final int MODE_RGB = 'R';

    private FrameCodec() {
    }

    /** Nome leggibile di un modo, per log e messaggi d'errore. */
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
     * Tetto di sicurezza sul corpo inflato, per la geometria data: il modo piu' voluminoso
     * (R, 3 byte/pixel) con tutti i tile sporchi. {@link FrameDecoder} rifiuta qualunque
     * stream che produca piu' di questo <b>senza allocare</b>, cosi' un {@code frameSize}
     * malevolo non puo' chiedere memoria arbitraria (piano §4, §8.2).
     */
    static int maxBodyBytes(int width, int height) {
        return bitmapBytes(width, height) + 3 + 3 * width * height + 2 * MAX_PALETTE;
    }

    /**
     * Tetto sull'uscita di Deflate per un ingresso di {@code n} byte. Deflate puo'
     * <i>espandere</i> un ingresso incomprimibile; il margine di zlib e' ~0,03%, qui si usa
     * il 12,5% piu' 128 byte perche' costa memoria virtuale e basta.
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
