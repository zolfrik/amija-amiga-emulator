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

import io.github.zolfrik.amija.display.FrameInfo;
import io.github.zolfrik.amija.net.codec.CodecException;
import io.github.zolfrik.amija.net.codec.FrameDecoder;

import java.nio.ByteBuffer;

/**
 * Guest side of the video: puts the fragments back together, decodes and delivers the raster
 * ({@code docs/remote-play-plan.md} section 4.1). The twin of {@link VideoSender}.
 *
 * <h2>Why this is the object that "replaces VirtualCRT"</h2>
 * {@code AmigaDisplay.renderFrame(int[], w, h, FrameInfo)} takes the <b>raw raster</b> and does
 * everything else by itself: crop, integer scaling, overscan, shaders, LEDs, fullscreen. This
 * class produces exactly those arguments, so from here on the guest reuses the existing
 * presentation chain without knowing anything, and all those choices stay <b>local</b>.
 *
 * <h2>Old frames are thrown away, not decoded</h2>
 * A frame arriving after a newer one is useless (nobody wants to see backwards) but above all
 * <b>must not be written into the ring</b>: {@code frameId} and {@code frameId+4} share the slot,
 * so decoding a latecomer would evict a newer reference, doing harm to deliver something
 * useless. The rule is therefore strict: only what advances is accepted.
 *
 * <h2>A frame is either rebuilt entirely or never existed</h2>
 * Nothing is ACKed that was not decoded successfully. It is the pivot of the whole scheme: the
 * host compresses against the last ACK, so a frame that is lost, incomplete, hostile or simply
 * undecodable only widens the next delta. There is no path by which an error here becomes a
 * wrong reference that drags on.
 */
public final class VideoReceiver implements AutoCloseable {

    /** Receives a rebuilt frame. The raster is valid only for the duration of the call. */
    public interface FrameSink {
        void onFrame(int[] pixels, int width, int height, FrameInfo info, long frameId);
    }

    private final int width;
    private final int height;
    private final FragmentReassembler reassembler = new FragmentReassembler();
    private final FrameDecoder decoder;
    private final ReferenceRing ring;
    private final FrameInfo info = new FrameInfo();

    /** Read by the thread sending the feedback: the number the ACK carries to the host. */
    private volatile long lastGoodFrameId = -1;

    private long framesDelivered;
    private long framesLate;
    private long framesNoReference;
    private long framesRejected;
    private long keyframesReceived;
    private long decodeNanos;

    public VideoReceiver(int width, int height) {
        this.width = width;
        this.height = height;
        this.decoder = new FrameDecoder(width, height);
        this.ring = new ReferenceRing(width * height);
    }

    /**
     * Takes the plaintext payload of a video datagram.
     *
     * @return {@code true} if the frame was completed, decoded and delivered
     * @throws ProtocolException if the fragment is malformed: the caller discards and goes on
     */
    public boolean accept(byte[] plain, int length, FrameSink sink) throws ProtocolException {
        FragmentReassembler.Slot slot = reassembler.accept(plain, length);
        if (slot == null) {
            return false;
        }
        long frameId = slot.frameId();
        if (frameId <= lastGoodFrameId) {
            framesLate++;
            return false;
        }

        int blobLength = slot.length();
        if (blobLength < VideoFrameHeader.SIZE) {
            throw new ProtocolException("blob di " + blobLength
                    + " byte: piu' corto della sua intestazione");
        }
        ByteBuffer b = RemotePlayProtocol.wrap(slot.data(), 0, VideoFrameHeader.SIZE);
        VideoFrameHeader header = VideoFrameHeader.readFrom(b, width, height, blobLength);

        int[] reference;
        if (header.isKeyframe()) {
            reference = null;
            keyframesReceived++;
        } else {
            reference = ring.get(header.refFrameId());
            if (reference == null) {
                // The host compressed against a frame no longer here. It happens only if ACKs
                // are lost for long; not an error, and the cure comes by itself: without ACKs
                // the host ends up with no references and sends a keyframe.
                framesNoReference++;
                return false;
            }
        }

        int[] dst = ring.buffer(frameId);
        long t0 = System.nanoTime();
        try {
            decoder.decode(slot.data(), VideoFrameHeader.SIZE, header.payloadLength(),
                    reference, dst);
        } catch (CodecException e) {
            // decode() is all-or-nothing: 'dst' was not touched, so the slot stays what it was
            // and no reference was dirtied.
            framesRejected++;
            throw new ProtocolException("frame " + frameId + " non decodificabile", e);
        }
        decodeNanos += System.nanoTime() - t0;

        ring.commit(frameId);
        lastGoodFrameId = frameId;
        framesDelivered++;
        header.applyTo(info);
        if (sink != null) {
            sink.onFrame(dst, width, height, info, frameId);
        }
        return true;
    }

    /** The number the guest puts in the ACK: the last frame received <b>in full</b>. */
    public long ackFrameId() {
        return lastGoodFrameId;
    }

    /** Starts from scratch: new session generation, no shared reference. */
    public void reset() {
        reassembler.reset();
        ring.clear();
        lastGoodFrameId = -1;
    }

    public long framesDelivered() {
        return framesDelivered;
    }

    /** Frames arriving after a newer one: discarded so as not to evict a good reference. */
    public long framesLate() {
        return framesLate;
    }

    /** Frames whose reference had already left the ring: wait for the keyframe. */
    public long framesNoReference() {
        return framesNoReference;
    }

    /** Complete frames that could not be decoded: corrupt or hostile payload. */
    public long framesRejected() {
        return framesRejected;
    }

    public long keyframesReceived() {
        return keyframesReceived;
    }

    public long droppedIncomplete() {
        return reassembler.droppedIncomplete();
    }

    public long duplicateFragments() {
        return reassembler.duplicates();
    }

    public double meanDecodeMs() {
        return framesDelivered == 0 ? 0 : decodeNanos / 1e6 / framesDelivered;
    }

    @Override
    public void close() {
        decoder.close();
    }
}
