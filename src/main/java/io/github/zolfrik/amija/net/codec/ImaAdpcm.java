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
 * 4:1 IMA ADPCM for the remote play audio channel ({@code docs/remote-play-plan.md} sections
 * 4.2, 15.5).
 *
 * <h2>Why audio became the expensive item</h2>
 * With the video measured at 1-3 Mbit/s (section 6), 1.41 Mbit/s of PCM S16 audio is between 30%
 * and 50% of the stream: the more expensive of the two. IMA ADPCM brings it to 355 kbit/s in
 * some fifty lines and with no new dependencies.
 *
 * <h2>Every block starts from zero, and that matters</h2>
 * ADPCM is a <b>stateful</b> coder: predictor and step index carry over from one sample to the
 * next. On UDP that would be fatal: a lost packet would misalign the decoder's state and from
 * then on the audio would be <i>wrong forever</i>, not just gapped.
 *
 * <p>So every block is <b>independent</b>: the state restarts from the predictor and index
 * written in its header, as in the IMA blocks of WAV files. A lost block costs 23 ms of silence
 * and nothing else. The price is 4 bytes of header per channel every 1024 samples: 0.8%.
 *
 * <pre>
 *   block := channel L { i16 predictor, u8 index, u8 reserved, nibble[] }
 *            channel R { same }
 * </pre>
 * The two channels are <b>consecutive, not interleaved</b>: the per-nibble interleaving of stereo
 * WAVs saves zero bytes and is easy to get wrong.
 *
 * <p>Quality is not a matter of opinion, but it is not the criterion either: section 15.5 says
 * the choice is made <b>by listening</b>. Here there is the codec and {@link #signalToNoiseDb},
 * which measures how much is lost; the judgement stays with the ear.
 */
public final class ImaAdpcm {

    /** Header bytes per channel in each block. */
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

    /** Bytes taken by a stereo block of {@code frames} samples. */
    public static int blockBytes(int frames, int channels) {
        return channels * (BLOCK_HEADER + (frames + 1) / 2);
    }

    /**
     * Encodes an interleaved S16LE stereo block.
     *
     * @param pcm    interleaved samples L,R,L,R... (2 bytes each, little-endian)
     * @param frames samples per channel
     * @param dst    destination, at least {@link #blockBytes} bytes
     * @return bytes written
     */
    public static int encode(byte[] pcm, int pcmOffset, int frames, byte[] dst, int dstOffset) {
        int p = dstOffset;
        for (int ch = 0; ch < 2; ch++) {
            // The predictor starts from the channel's first real sample: so the block starts
            // exact instead of chasing the signal from the first samples.
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
     * Decodes a block produced by {@link #encode}.
     *
     * @throws CodecException if the block is truncated or the step index is outside the table
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
                // It comes from the network: an index outside the table would be an access outside the array.
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
     * Signal-to-noise ratio between two S16LE buffers, in decibels.
     *
     * <p>Used to <b>quantify</b> what the compression costs, not to decide: section 15.5 closes by
     * listening. A high number does not guarantee nothing is heard, and a low one on material
     * that starts from 8-bit samples does not mean much.
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

    /** Applies one code and returns {@code predictor | index<<16}. */
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
