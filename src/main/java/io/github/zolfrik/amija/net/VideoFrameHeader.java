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

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

/**
 * Header of a video frame's <b>blob</b>: metadata plus the length of the encoded payload
 * ({@code docs/remote-play-plan.md} section 4.1).
 *
 * <h2>Why it is in the blob and not in every fragment</h2>
 * An incomplete frame is <b>discarded entirely</b> (never partial reassemblies on screen), so
 * repeating the metadata in every fragment would pay for information that, if fragment 0 is
 * missing, is useless anyway. Here the header is the blob's prefix, the blob is cut into pieces
 * <b>all of the same capacity</b>, and the reassembly becomes a plain {@code arraycopy} at offset
 * {@code fragIdx * FRAGMENT_DATA}: no special case for the first fragment, no offset arithmetic
 * depending on the data received.
 *
 * <h2>Redundant geometry, on purpose</h2>
 * {@code width}/{@code height} are already agreed in the WELCOME. They are repeated because the
 * decoder's buffers are sized on that geometry, and an inconsistent frame is better refused by
 * the <i>check</i> than by the buffer.
 */
public record VideoFrameHeader(
        long refFrameId,      // NO_REFERENCE = keyframe
        int codec,
        boolean hires,
        boolean interlaced,
        boolean evenField,
        int width,
        int height,
        int visibleX,
        int visibleY,
        int visibleWidth,
        int visibleHeight,
        long frameCount,
        int payloadLength) {

    public static final int SIZE = 38;

    public static VideoFrameHeader of(long refFrameId, int width, int height,
                                      FrameInfo info, int payloadLength) {
        return new VideoFrameHeader(refFrameId, RemotePlayProtocol.CODEC_AJV1,
                info.hires(), info.interlaced(), info.evenField(), width, height,
                info.visibleX(), info.visibleY(), info.visibleWidth(), info.visibleHeight(),
                info.frameCount(), payloadLength);
    }

    public boolean isKeyframe() {
        return refFrameId == RemotePlayProtocol.NO_REFERENCE;
    }

    public void writeTo(ByteBuffer b) {
        b.putInt((int) refFrameId);
        b.put((byte) codec);
        b.put((byte) ((hires ? 1 : 0) | (interlaced ? 2 : 0) | (evenField ? 4 : 0)));
        b.putShort((short) width);
        b.putShort((short) height);
        b.putInt(visibleX);
        b.putInt(visibleY);
        b.putInt(visibleWidth);
        b.putInt(visibleHeight);
        b.putLong(frameCount);
        b.putInt(payloadLength);
    }

    /**
     * Reads and <b>validates</b> a header against the agreed geometry.
     *
     * @throws ProtocolException if the blob is truncated or declares something impossible
     */
    public static VideoFrameHeader readFrom(ByteBuffer b, int expectedWidth, int expectedHeight,
                                            int blobLength) throws ProtocolException {
        try {
            long refFrameId = b.getInt() & 0xFFFFFFFFL;
            int codec = b.get() & 0xFF;
            int flags = b.get() & 0xFF;
            int width = b.getShort() & 0xFFFF;
            int height = b.getShort() & 0xFFFF;
            int visX = b.getInt();
            int visY = b.getInt();
            int visW = b.getInt();
            int visH = b.getInt();
            long frameCount = b.getLong();
            int payloadLength = b.getInt();

            if (codec != RemotePlayProtocol.CODEC_AJV1) {
                throw new ProtocolException("codec video sconosciuto: " + codec);
            }
            if ((flags & ~0x07) != 0) {
                throw new ProtocolException("flag di frame non riconosciuti: 0x"
                        + Integer.toHexString(flags));
            }
            if (width != expectedWidth || height != expectedHeight) {
                throw new ProtocolException("geometria " + width + "x" + height
                        + " diversa da quella concordata " + expectedWidth + "x" + expectedHeight);
            }
            if (payloadLength < 0 || payloadLength != blobLength - SIZE) {
                throw new ProtocolException("payload dichiarato " + payloadLength
                        + " byte, nel blob ce ne sono " + (blobLength - SIZE));
            }
            // The visible window sizes no buffer, but it leaves here and ends up in FrameInfo,
            // i.e. in the local presentation chain, which uses it to centre the image. They are
            // four arbitrary integers arriving from the network: adding them up unchecked gives
            // at least an image pushed off screen, and extreme values make the arithmetic of
            // whoever combines them wrap. The cap is the frame's own geometry, the only thing
            // the visible window can talk about.
            if (visX < 0 || visY < 0 || visW < 0 || visH < 0
                    || visX > width || visY > height
                    || visW > width || visH > height
                    || visX + visW > width || visY + visH > height) {
                throw new ProtocolException("finestra visibile " + visW + "x" + visH + " a ("
                        + visX + "," + visY + ") fuori dal raster " + width + "x" + height);
            }
            return new VideoFrameHeader(refFrameId, codec,
                    (flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0,
                    width, height, visX, visY, visW, visH, frameCount, payloadLength);
        } catch (BufferUnderflowException e) {
            throw new ProtocolException("blob di frame troncato nell'intestazione", e);
        }
    }

    public void applyTo(FrameInfo info) {
        info.set(hires, interlaced, evenField, visibleX, visibleY, visibleWidth, visibleHeight);
        info.setFrameCount(frameCount);
    }
}
