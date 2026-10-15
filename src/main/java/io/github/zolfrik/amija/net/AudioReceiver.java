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

import io.github.zolfrik.amija.net.codec.CodecException;
import io.github.zolfrik.amija.net.codec.ImaAdpcm;

import java.nio.ByteBuffer;

/**
 * Guest side of the audio: puts the blocks back together, decodes them and delivers them <b>in
 * order and in phase</b> ({@code docs/remote-play-plan.md} section 4.2).
 *
 * <h2>A gap is filled with silence, not closed</h2>
 * The only non-obvious thing this class does, and the one that matters. If a lost block were
 * simply skipped, the audio would stay continuous but <b>slip forward by 23 ms</b>, and every
 * later loss would add its own: after a minute of a mediocre link the audio would be seconds
 * ahead of the video. With the absolute index of section 4.2 the gap is recognised and filled
 * with <b>silence of the exact length</b>: what was lost is lost, and the phase holds.
 *
 * <p>The opposite case (a block arriving after its place has passed) is discarded. Queueing it
 * again would play it out of place, i.e. an artefact in place of a silence that already
 * happened.
 */
public final class AudioReceiver {

    /** Receives stereo S16LE PCM ready to play. */
    public interface PcmSink {
        void onPcm(byte[] pcm, int offset, int frames);
    }

    /**
     * Cap on the silence generated for a single gap: half a second.
     *
     * <p>Beyond that it is not a gap but a resumption after a long interruption (or a malicious
     * index), and filling with proportional silence would mean allocating time (or memory) at
     * the request of whoever is on the other side. It just restarts in phase.
     */
    public static final int MAX_GAP_FRAMES = 22_050;

    private final FragmentReassembler reassembler = new FragmentReassembler();
    private final byte[] pcm = new byte[AudioBlockHeader.MAX_FRAMES * 4];
    private final byte[] silence = new byte[4096];

    private long expectedSampleIndex = -1;
    private long blocksDelivered;
    private long blocksLate;
    private long gapFrames;
    private long gaps;
    private long resyncs;

    /**
     * Takes the plaintext payload of an audio datagram.
     *
     * @return {@code true} if a block was completed and delivered
     * @throws ProtocolException if the fragment or the block is malformed
     */
    public boolean accept(byte[] plain, int length, PcmSink sink) throws ProtocolException {
        FragmentReassembler.Slot slot = reassembler.accept(plain, length);
        if (slot == null) {
            return false;
        }
        int blobLength = slot.length();
        if (blobLength < AudioBlockHeader.SIZE) {
            throw new ProtocolException("blocco audio di " + blobLength
                    + " byte: piu' corto della sua intestazione");
        }
        ByteBuffer b = RemotePlayProtocol.wrap(slot.data(), 0, AudioBlockHeader.SIZE);
        AudioBlockHeader header = AudioBlockHeader.readFrom(b, blobLength);

        if (expectedSampleIndex >= 0 && header.sampleIndex() < expectedSampleIndex) {
            // Its place has already passed: playing it now would be an artefact in place of a
            // silence that already happened.
            blocksLate++;
            return false;
        }

        int frames = header.frames();
        if (header.format() == AudioBlockHeader.FORMAT_ADPCM) {
            try {
                ImaAdpcm.decode(slot.data(), AudioBlockHeader.SIZE,
                        blobLength - AudioBlockHeader.SIZE, frames, pcm, 0);
            } catch (CodecException e) {
                throw new ProtocolException("blocco ADPCM non decodificabile", e);
            }
        } else {
            System.arraycopy(slot.data(), AudioBlockHeader.SIZE, pcm, 0, frames * 4);
        }

        if (expectedSampleIndex >= 0 && header.sampleIndex() > expectedSampleIndex) {
            long missing = header.sampleIndex() - expectedSampleIndex;
            gaps++;
            if (missing <= MAX_GAP_FRAMES) {
                gapFrames += missing;
                emitSilence(missing, sink);
            } else {
                // Long interruption: restart in phase instead of generating half a minute of
                // silence to honour a clock that stopped being useful.
                resyncs++;
            }
        }

        sink.onPcm(pcm, 0, frames);
        expectedSampleIndex = header.sampleIndex() + frames;
        blocksDelivered++;
        return true;
    }

    /** Starts from scratch: new session, new clock. */
    public void reset() {
        reassembler.reset();
        expectedSampleIndex = -1;
    }

    public long blocksDelivered() {
        return blocksDelivered;
    }

    /** Blocks arrived after their turn: discarded. */
    public long blocksLate() {
        return blocksLate;
    }

    /** How many gaps were filled with silence. */
    public long gaps() {
        return gaps;
    }

    /** How many silence samples in total: the true measure of how much audio was lost. */
    public long gapFrames() {
        return gapFrames;
    }

    /** Realignments after an interruption longer than {@link #MAX_GAP_FRAMES}. */
    public long resyncs() {
        return resyncs;
    }

    public long droppedIncomplete() {
        return reassembler.droppedIncomplete();
    }

    private void emitSilence(long frames, PcmSink sink) {
        int chunk = silence.length / 4;
        while (frames > 0) {
            int n = (int) Math.min(chunk, frames);
            sink.onPcm(silence, 0, n);
            frames -= n;
        }
    }
}
