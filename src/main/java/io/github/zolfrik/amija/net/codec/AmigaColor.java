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

import java.util.Arrays;

/**
 * Conversione fra il pixel RGB a 24 bit del framebuffer e i 12 bit del colore Amiga.
 *
 * <p><b>Perche' esiste.</b> {@code AbstractDenise} colorizza <i>sempre</i> attraverso
 * {@code amigaToRGB()}, cioe' una LUT a 4096 voci, e lo fa anche in HAM: ogni pixel che
 * esce da Denise e' quindi uno di soli 4096 valori. La Fase 0 del piano
 * {@code docs/remote-play-plan.md} l'ha verificato su 853 milioni di pixel, zero eccezioni.
 * E' il fatto su cui poggia tutta la compressione di {@link FrameEncoder}: il frame non e'
 * un'immagine a 16 milioni di colori, e' a 12 bit.
 *
 * <p><b>Perche' l'inversa costa una LUT da 256 byte e non una tabella hash da 4096 voci.</b>
 * {@code AMIGA_RGB_LUT} costruisce ogni canale come {@code nibble * 17} (0x00, 0x11, ...
 * 0xFF), quindi i canali validi sono 16 su 256 e l'inversa e' una semplice tabella indicizzata
 * dal byte di canale. Tre load per pixel, niente hashing.
 *
 * <p><b>Dominio.</b> Il codec e' definito sui 24 bit bassi del pixel; i bit 24..31 (alpha)
 * sono ignorati in codifica e scritti a zero in decodifica. {@code VirtualCRT} consegna il
 * raster con quel byte gia' a zero, quindi non si perde niente — ma la regola va scritta
 * perche' e' un contratto, non una coincidenza.
 */
public final class AmigaColor {

    /** Valori distinti del colore Amiga: 4 bit per canale. */
    public static final int LEVELS = 4096;

    /** Maschera dei bit del pixel che il codec considera significativi. */
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

    /** 12 bit Amiga -> RGB 24 bit, esattamente come la LUT di Denise. */
    public static int toRgb(int amiga12) {
        return TO_RGB[amiga12 & 0xFFF];
    }

    /**
     * RGB 24 bit -> 12 bit Amiga, oppure {@code -1} se il pixel non e' rappresentabile.
     *
     * <p>Il {@code -1} non e' teorico anche se la Fase 0 non l'ha mai osservato: esiste un
     * ramo di test in Denise ({@code useVAmigaColorLut}) che usa una LUT diversa, e nulla
     * garantisce che una modifica futura del rendering non introduca altri valori. Chi
     * chiama <b>deve</b> gestirlo — {@link FrameEncoder} lo fa ripiegando sul modo RGB.
     */
    public static int fromRgb(int rgb) {
        int r = FROM_CHANNEL[(rgb >>> 16) & 0xFF];
        int g = FROM_CHANNEL[(rgb >>> 8) & 0xFF];
        int b = FROM_CHANNEL[rgb & 0xFF];
        return (r | g | b) < 0 ? -1 : (r << 8) | (g << 4) | b;
    }
}
