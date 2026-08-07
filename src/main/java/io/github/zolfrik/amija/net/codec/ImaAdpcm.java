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
 * IMA-ADPCM 4:1 per il canale audio del gioco a distanza
 * ({@code docs/remote-play-plan.md} §4.2, §15.5).
 *
 * <h2>Perche' l'audio e' diventato la voce cara</h2>
 * Quando il video era <i>stimato</i> a 12–36 Mbit/s, 1,41 Mbit/s di PCM S16 erano rumore di
 * fondo. Col video <b>misurato</b> a 1–3 Mbit/s (§6), lo stesso audio e' fra il 30% e il 50%
 * dello stream: e' la voce piu' cara delle due. IMA-ADPCM lo porta a **355 kbit/s** in una
 * cinquantina di righe e senza dipendenze nuove.
 *
 * <h2>Ogni blocco riparte da zero, e non e' un dettaglio</h2>
 * ADPCM e' un codificatore <b>a stato</b>: predittore e indice di passo si portano avanti da
 * un campione al successivo. Su UDP questo sarebbe fatale — un pacchetto perso
 * disallineerebbe lo stato del decodificatore e da li' in poi l'audio sarebbe <i>sbagliato
 * per sempre</i>, non solo bucato.
 *
 * <p>Percio' ogni blocco e' <b>indipendente</b>: lo stato riparte dal predittore e
 * dall'indice scritti nella sua intestazione, come nei blocchi IMA dei file WAV. Un blocco
 * perso costa 23 ms di silenzio e nient'altro. Il prezzo sono 4 byte di intestazione per
 * canale ogni 1024 campioni — lo 0,8%.
 *
 * <pre>
 *   blocco := canale L { i16 predittore, u8 indice, u8 riservato, nibble[] }
 *             canale R { idem }
 * </pre>
 * I due canali sono <b>consecutivi, non interlacciati</b>: l'interlacciamento per nibble dei
 * WAV stereo fa risparmiare zero byte e si sbaglia facilmente.
 *
 * <p>La qualita' non e' un'opinione ma non e' nemmeno il criterio: il §15.5 dice che la
 * scelta si fa <b>ascoltando</b>. Qui c'e' il codec e c'e' {@link #signalToNoiseDb}, che
 * misura quanto si perde; il giudizio resta all'orecchio.
 */
public final class ImaAdpcm {

    /** Byte di intestazione per canale in ogni blocco. */
    public static final int BLOCK_HEADER = 4;

    private static final int[] STEP_TABLE = {
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
        50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230, 253,
        279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963, 1060, 1166,
        1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428,
        4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899, 15289,
        16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767,
    };

    private static final int[] INDEX_TABLE = {
        -1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8,
    };

    private ImaAdpcm() {
    }

    /** Byte occupati da un blocco stereo di {@code frames} campioni. */
    public static int blockBytes(int frames, int channels) {
        return channels * (BLOCK_HEADER + (frames + 1) / 2);
    }

    /**
     * Codifica un blocco stereo S16LE interlacciato.
     *
     * @param pcm    campioni interlacciati L,R,L,R... (2 byte l'uno, little-endian)
     * @param frames campioni per canale
     * @param dst    destinazione, almeno {@link #blockBytes} byte
     * @return byte scritti
     */
    public static int encode(byte[] pcm, int pcmOffset, int frames, byte[] dst, int dstOffset) {
        int p = dstOffset;
        for (int ch = 0; ch < 2; ch++) {
            // Il predittore parte dal primo campione vero del canale: cosi' il blocco
            // comincia esatto invece di rincorrere il segnale dai primi campioni.
            int predictor = sample(pcm, pcmOffset, 0, ch);
            int index = 0;
            dst[p] = (byte) predictor;
            dst[p + 1] = (byte) (predictor >> 8);
            dst[p + 2] = (byte) index;
            dst[p + 3] = 0;
            p += BLOCK_HEADER;

            int state = (predictor & 0xFFFF) | (index << 16);
            int nibbleSlot = 0;
            int pending = 0;
            for (int i = 0; i < frames; i++) {
                int target = sample(pcm, pcmOffset, i, ch);
                predictor = (short) state;
                index = state >>> 16;
                int code = encodeSample(target, predictor, index);
                state = advance(code, predictor, index);
                if (nibbleSlot == 0) {
                    pending = code & 0x0F;
                    nibbleSlot = 1;
                } else {
                    dst[p++] = (byte) (pending | ((code & 0x0F) << 4));
                    nibbleSlot = 0;
                }
            }
            if (nibbleSlot == 1) {
                dst[p++] = (byte) pending;
            }
        }
        return p - dstOffset;
    }

    /**
     * Decodifica un blocco prodotto da {@link #encode}.
     *
     * @throws CodecException se il blocco e' troncato o l'indice di passo e' fuori tabella
     */
    public static void decode(byte[] src, int srcOffset, int length, int frames, byte[] pcm,
                              int pcmOffset) throws CodecException {
        int needed = blockBytes(frames, 2);
        if (length < needed) {
            throw new CodecException("blocco ADPCM di " + length + " byte, ne servono " + needed
                    + " per " + frames + " campioni");
        }
        int p = srcOffset;
        for (int ch = 0; ch < 2; ch++) {
            int predictor = (short) ((src[p] & 0xFF) | ((src[p + 1] & 0xFF) << 8));
            int index = src[p + 2] & 0xFF;
            if (index >= STEP_TABLE.length) {
                // Viene dalla rete: un indice fuori tabella sarebbe un accesso fuori array.
                throw new CodecException("indice di passo ADPCM " + index + " fuori tabella");
            }
            p += BLOCK_HEADER;

            int state = (predictor & 0xFFFF) | (index << 16);
            for (int i = 0; i < frames; i++) {
                int code = (i & 1) == 0 ? (src[p] & 0x0F) : ((src[p] >> 4) & 0x0F);
                if ((i & 1) == 1) {
                    p++;
                }
                state = advance(code, (short) state, state >>> 16);
                int out = (short) state;
                int off = pcmOffset + (i * 2 + ch) * 2;
                pcm[off] = (byte) out;
                pcm[off + 1] = (byte) (out >> 8);
            }
            if ((frames & 1) == 1) {
                p++;
            }
        }
    }

    /**
     * Rapporto segnale/rumore fra due buffer S16LE, in decibel.
     *
     * <p>Serve a <b>quantificare</b> quanto costa la compressione, non a decidere: il §15.5
     * chiude ascoltando. Un numero alto non garantisce che non si senta niente, e uno basso
     * su materiale che parte da campioni a 8 bit non vuol dire granche'.
     */
    public static double signalToNoiseDb(byte[] reference, byte[] test, int frames) {
        double signal = 0;
        double noise = 0;
        for (int i = 0; i < frames * 2; i++) {
            int a = (short) ((reference[i * 2] & 0xFF) | ((reference[i * 2 + 1] & 0xFF) << 8));
            int b = (short) ((test[i * 2] & 0xFF) | ((test[i * 2 + 1] & 0xFF) << 8));
            signal += (double) a * a;
            double d = a - b;
            noise += d * d;
        }
        if (noise == 0) {
            return Double.POSITIVE_INFINITY;
        }
        if (signal == 0) {
            return Double.NEGATIVE_INFINITY;
        }
        return 10.0 * Math.log10(signal / noise);
    }

    // =====================================================================

    private static int sample(byte[] pcm, int offset, int frame, int channel) {
        int i = offset + (frame * 2 + channel) * 2;
        return (short) ((pcm[i] & 0xFF) | ((pcm[i + 1] & 0xFF) << 8));
    }

    private static int encodeSample(int target, int predictor, int index) {
        int step = STEP_TABLE[index];
        int diff = target - predictor;
        int code = 0;
        if (diff < 0) {
            code = 8;
            diff = -diff;
        }
        if (diff >= step) {
            code |= 4;
            diff -= step;
        }
        step >>= 1;
        if (diff >= step) {
            code |= 2;
            diff -= step;
        }
        step >>= 1;
        if (diff >= step) {
            code |= 1;
        }
        return code;
    }

    /** Applica un codice e restituisce {@code predittore | indice<<16}. */
    private static int advance(int code, int predictor, int index) {
        int step = STEP_TABLE[index];
        int delta = step >> 3;
        if ((code & 4) != 0) {
            delta += step;
        }
        if ((code & 2) != 0) {
            delta += step >> 1;
        }
        if ((code & 1) != 0) {
            delta += step >> 2;
        }
        int next = (code & 8) != 0 ? predictor - delta : predictor + delta;
        if (next > 32767) {
            next = 32767;
        } else if (next < -32768) {
            next = -32768;
        }
        int nextIndex = index + INDEX_TABLE[code];
        if (nextIndex < 0) {
            nextIndex = 0;
        } else if (nextIndex >= STEP_TABLE.length) {
            nextIndex = STEP_TABLE.length - 1;
        }
        return (next & 0xFFFF) | (nextIndex << 16);
    }
}
