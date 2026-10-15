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

package io.github.zolfrik.amija.net;

import io.github.zolfrik.amija.net.codec.ImaAdpcm;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

/**
 * Header of the <b>AJA1</b> audio block ({@code docs/remote-play-plan.md} section 4.2).
 *
 * <pre>
 *   u64 sampleIndex   ABSOLUTE index of the block's first sample
 *   u8  format        PCM16 | ADPCM
 *   u16 frames        samples per channel
 *   ... payload
 * </pre>
 *
 * <h2>The absolute counter is the whole mechanism</h2>
 * Without it, a lost block would <b>shift forward</b> everything that follows: the audio would
 * stay continuous but drift out of sync with the video, and every later loss would add its bit
 * of delay. With the absolute index the guest notices the gap, puts <b>silence of the right
 * length</b> in it and resumes in phase.
 *
 * <p>The same idea as the cumulative mouse counters (section 4.3): where the natural thing would
 * be a delta, a position is sent, so a loss costs what it lost and leaves no aftermath.
 */
public record AudioBlockHeader(long sampleIndex, int format, int frames) {

    public static final int SIZE = 11;

    /** Stereo S16LE, bit-exact: what the host hears, sample by sample. */
    public static final int FORMAT_PCM16 = 1;
    /** 4:1 IMA ADPCM: a quarter of the bandwidth, every block independent (section 15.5). */
    public static final int FORMAT_ADPCM = 2;

    /** Samples per channel in a block: the chunk of {@code JavaSoundAudioOutput}. */
    public static final int FRAMES_PER_BLOCK = 1024;

    /** Safety cap: nobody produces a block longer than this. */
    public static final int MAX_FRAMES = 4096;

    public static String formatName(int format) {
        return switch (format) {
            case FORMAT_PCM16 -> "PCM S16";
            case FORMAT_ADPCM -> "IMA-ADPCM";
            default -> "sconosciuto(" + format + ")";
        };
    }

    /** Payload bytes this block must have. */
    public int payloadBytes() {
        return format == FORMAT_ADPCM
                ? ImaAdpcm.blockBytes(frames, 2)
                : frames * 4;
    }

    public void writeTo(ByteBuffer b) {
        b.putLong(sampleIndex);
        b.put((byte) format);
        b.putShort((short) frames);
    }

    /**
     * Reads and <b>validates</b> a header.
     *
     * @param blobLength total length of the blob, header included
     * @throws ProtocolException if the block is truncated or declares something impossible
     */
    public static AudioBlockHeader readFrom(ByteBuffer b, int blobLength)
            throws ProtocolException {
        try {
            long sampleIndex = b.getLong();
            int format = b.get() & 0xFF;
            int frames = b.getShort() & 0xFFFF;

            if (format != FORMAT_PCM16 && format != FORMAT_ADPCM) {
                throw new ProtocolException("formato audio sconosciuto: " + format);
            }
            if (frames < 1 || frames > MAX_FRAMES) {
                throw new ProtocolException("blocco audio di " + frames
                        + " campioni, massimo " + MAX_FRAMES);
            }
            if (sampleIndex < 0) {
                throw new ProtocolException("indice di campione negativo: " + sampleIndex);
            }
            AudioBlockHeader h = new AudioBlockHeader(sampleIndex, format, frames);
            if (blobLength - SIZE != h.payloadBytes()) {
                throw new ProtocolException("blocco audio con " + (blobLength - SIZE)
                        + " byte di payload, attesi " + h.payloadBytes() + " per " + frames
                        + " campioni in " + formatName(format));
            }
            return h;
        } catch (BufferUnderflowException e) {
            throw new ProtocolException("blocco audio troncato nell'intestazione", e);
        }
    }
}
