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

package io.github.zolfrik.amija.net;

import io.github.zolfrik.amija.display.FrameInfo;
import io.github.zolfrik.amija.net.codec.FrameEncoder;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Host side of the video: encodes a frame against the last reference the guest confirmed and
 * cuts it into fragments to send ({@code docs/remote-play-plan.md} section 4.1).
 *
 * <p>It does not know the sockets. It emits <b>plaintext</b> payloads to a {@link FragmentSink},
 * which seals and sends them. That makes testable without a network the whole part that can
 * really go wrong: choice of the reference, fragmentation, ACK accounting.
 *
 * <p>Not thread-safe: it lives on the encoding thread, the only one that calls it. The only
 * shared field is the ACK, which arrives from the network thread and is {@code volatile}: a
 * slightly stale read costs at most a bigger delta, never a wrong frame.
 */
public final class VideoSender implements AutoCloseable {

    /** Receives the plaintext payload of a datagram. Its implementation seals and sends it. */
    public interface FragmentSink {
        void send(byte[] plain, int length) throws IOException;
    }

    private static final int BLOB_CAPACITY =
            RemotePlayProtocol.MAX_FRAGMENTS * RemotePlayProtocol.FRAGMENT_DATA;

    private final int width;
    private final int height;
    private final FrameEncoder encoder;
    private final ReferenceRing ring;
    private final byte[] blob = new byte[BLOB_CAPACITY];
    private final byte[] fragment =
            new byte[RemotePlayProtocol.FRAGMENT_HEADER + RemotePlayProtocol.FRAGMENT_DATA];

    private long nextFrameId;
    private volatile long ackedFrameId = -1;
    private long framesSent;
    private long keyframesSent;
    private long bytesSent;
    private long framesTooBig;
    private long encodeNanos;

    public VideoSender(int width, int height) {
        this.width = width;
        this.height = height;
        this.encoder = new FrameEncoder(width, height);
        this.ring = new ReferenceRing(width * height);
    }

    /**
     * Encodes and sends a frame.
     *
     * <p>The reference is <b>the last frame the guest confirmed</b>, if the ring still holds it;
     * otherwise a keyframe is sent. There is no other case: no periodic keyframes, no NACK, no
     * retransmissions (section 4.1).
     *
     * @return {@code true} if the frame left, {@code false} if it was skipped because too large
     *         for the fragmentation
     */
    public boolean sendFrame(int[] pixels, FrameInfo info, FragmentSink sink) throws IOException {
        long acked = ackedFrameId;
        int[] reference = acked >= 0 ? ring.get(acked) : null;
        long refFrameId = reference != null ? acked : RemotePlayProtocol.NO_REFERENCE;

        long t0 = System.nanoTime();
        int payloadLength = encoder.encode(pixels, reference);
        encodeNanos += System.nanoTime() - t0;

        int blobLength = VideoFrameHeader.SIZE + payloadLength;
        if (blobLength > BLOB_CAPACITY) {
            // Never happened on the scenes measured (the most expensive keyframe is 22 KB against
            // the 74 available), but a frame that does not fit the fragmentation must be
            // skipped, not truncated: better one frame less than a corrupt one. The next frame
            // retries, and since nothing was ACKed nothing is lost.
            framesTooBig++;
            nextFrameId++;
            return false;
        }

        long frameId = nextFrameId++;
        ByteBuffer b = RemotePlayProtocol.wrap(blob, 0, VideoFrameHeader.SIZE);
        VideoFrameHeader.of(refFrameId, width, height, info, payloadLength).writeTo(b);
        System.arraycopy(encoder.payload(), 0, blob, VideoFrameHeader.SIZE, payloadLength);

        int fragCount = (blobLength + RemotePlayProtocol.FRAGMENT_DATA - 1)
                / RemotePlayProtocol.FRAGMENT_DATA;
        for (int i = 0; i < fragCount; i++) {
            int off = i * RemotePlayProtocol.FRAGMENT_DATA;
            int n = Math.min(RemotePlayProtocol.FRAGMENT_DATA, blobLength - off);
            fragment[0] = (byte) frameId;
            fragment[1] = (byte) (frameId >>> 8);
            fragment[2] = (byte) (frameId >>> 16);
            fragment[3] = (byte) (frameId >>> 24);
            fragment[4] = (byte) i;
            fragment[5] = (byte) (i >>> 8);
            fragment[6] = (byte) fragCount;
            fragment[7] = (byte) (fragCount >>> 8);
            System.arraycopy(blob, off, fragment, RemotePlayProtocol.FRAGMENT_HEADER, n);
            sink.send(fragment, RemotePlayProtocol.FRAGMENT_HEADER + n);
        }

        ring.store(frameId, pixels);
        framesSent++;
        bytesSent += blobLength;
        if (refFrameId == RemotePlayProtocol.NO_REFERENCE) {
            keyframesSent++;
        }
        return true;
    }

    /**
     * Records the ACK arrived from the guest.
     *
     * <p>ACKs are network data like any other: they are accepted only if they advance and only if
     * they refer to a frame the host really sent. An ACK "from the future" would break nothing
     * (the ring would not hold it and a keyframe would follow) but discarding it here avoids
     * having to reason about it further on.
     */
    public void noteAck(long frameId) {
        if (frameId > ackedFrameId && frameId < nextFrameId) {
            ackedFrameId = frameId;
        }
    }

    /** Starts from scratch: new session generation, no shared reference. */
    public void reset() {
        ring.clear();
        ackedFrameId = -1;
        nextFrameId = 0;
    }

    public long framesSent() {
        return framesSent;
    }

    public long keyframesSent() {
        return keyframesSent;
    }

    public long bytesSent() {
        return bytesSent;
    }

    public long framesTooBig() {
        return framesTooBig;
    }

    public long ackedFrameId() {
        return ackedFrameId;
    }

    public double meanEncodeMs() {
        return framesSent == 0 ? 0 : encodeNanos / 1e6 / framesSent;
    }

    @Override
    public void close() {
        encoder.close();
    }
}
