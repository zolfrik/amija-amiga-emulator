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

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Host side of the audio: takes the PCM chunk already prepared and sends it
 * ({@code docs/remote-play-plan.md} section 4.2).
 *
 * <h2>Where it is taken, and why there</h2>
 * In {@code JavaSoundAudioOutput} the chunk is already converted to S16LE and has already been
 * through the RC filter and, if on, the LED filter. Taking it an instant before
 * {@code line.write()} costs nothing, is <b>off the emulation thread</b>, and lets the guest
 * hear <b>exactly</b> what the host hears, not a reconstruction resembling it.
 *
 * <p>The flip side must be stated, and the popup's {@code .tip} says it: on the guest the "LED
 * filter" option has no effect, because that filter is already inside the samples it
 * receives. The host decides it.
 *
 * <h2>Never queue</h2>
 * As for the video: if the socket is not ready the block is skipped. A late audio block is of no
 * use to anyone: the guest would discard it anyway, because its index would already have
 * passed.
 */
public final class AudioSender {

    private final int format;
    private final byte[] blob;
    private final byte[] fragment =
            new byte[RemotePlayProtocol.FRAGMENT_HEADER + RemotePlayProtocol.FRAGMENT_DATA];

    private long sampleIndex;
    private int blockId;
    private long blocksSent;
    private long bytesSent;
    private long blocksDropped;

    public AudioSender(int format) {
        if (format != AudioBlockHeader.FORMAT_PCM16 && format != AudioBlockHeader.FORMAT_ADPCM) {
            throw new IllegalArgumentException("formato audio non valido: " + format);
        }
        this.format = format;
        this.blob = new byte[AudioBlockHeader.SIZE
                + AudioBlockHeader.MAX_FRAMES * 4];
    }

    public int format() {
        return format;
    }

    /**
     * Encodes and sends a chunk.
     *
     * @param pcm    interleaved stereo S16LE, {@code frames*4} bytes
     * @param frames samples per channel
     */
    public void sendBlock(byte[] pcm, int frames, VideoSender.FragmentSink sink)
            throws IOException {
        if (frames < 1 || frames > AudioBlockHeader.MAX_FRAMES) {
            throw new IllegalArgumentException("blocco di " + frames + " campioni");
        }
        AudioBlockHeader header = new AudioBlockHeader(sampleIndex, format, frames);
        ByteBuffer b = RemotePlayProtocol.wrap(blob, 0, AudioBlockHeader.SIZE);
        header.writeTo(b);

        int payload;
        if (format == AudioBlockHeader.FORMAT_ADPCM) {
            payload = ImaAdpcm.encode(pcm, 0, frames, blob, AudioBlockHeader.SIZE);
        } else {
            payload = frames * 4;
            System.arraycopy(pcm, 0, blob, AudioBlockHeader.SIZE, payload);
        }
        int blobLength = AudioBlockHeader.SIZE + payload;

        int fragCount = (blobLength + RemotePlayProtocol.FRAGMENT_DATA - 1)
                / RemotePlayProtocol.FRAGMENT_DATA;
        if (fragCount > RemotePlayProtocol.MAX_FRAGMENTS) {
            blocksDropped++;
            sampleIndex += frames;
            return;
        }
        int id = blockId++;
        for (int i = 0; i < fragCount; i++) {
            int off = i * RemotePlayProtocol.FRAGMENT_DATA;
            int n = Math.min(RemotePlayProtocol.FRAGMENT_DATA, blobLength - off);
            fragment[0] = (byte) id;
            fragment[1] = (byte) (id >>> 8);
            fragment[2] = (byte) (id >>> 16);
            fragment[3] = (byte) (id >>> 24);
            fragment[4] = (byte) i;
            fragment[5] = (byte) (i >>> 8);
            fragment[6] = (byte) fragCount;
            fragment[7] = (byte) (fragCount >>> 8);
            System.arraycopy(blob, off, fragment, RemotePlayProtocol.FRAGMENT_HEADER, n);
            sink.send(fragment, RemotePlayProtocol.FRAGMENT_HEADER + n);
        }

        // The index ALWAYS advances, even if the block did not leave: it is a clock, not a
        // count of what arrived. If it advanced only on the blocks sent, the guest would have no
        // way to notice a gap.
        sampleIndex += frames;
        blocksSent++;
        bytesSent += blobLength;
    }

    /** Starts from scratch: new session, new sample clock. */
    public void reset() {
        sampleIndex = 0;
        blockId = 0;
    }

    public long blocksSent() {
        return blocksSent;
    }

    public long bytesSent() {
        return bytesSent;
    }

    public long blocksDropped() {
        return blocksDropped;
    }

    /** Average bit rate of the audio alone, in kbit/s, at the given output rate. */
    public double bitrateKbps(int sampleRate) {
        if (blocksSent == 0) {
            return 0;
        }
        double seconds = (double) sampleIndex / sampleRate;
        return seconds <= 0 ? 0 : bytesSent * 8.0 / seconds / 1000.0;
    }
}
