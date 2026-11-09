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

import java.nio.ByteBuffer;

/**
 * The packet a spectator sends to the host ({@code docs/remote-play-plan.md} section 16).
 *
 * <h2>Which frames, not which is the last</h2>
 * All spectators share one video stream, encoded once. The host compresses each frame against
 * the newest frame that <b>every</b> spectator holds, so it needs to know the whole content of
 * each spectator's ring, not just its newest frame: a spectator that lost frame 10 but holds 11
 * would look up to date while lacking 10. Hence the mask.
 *
 * <h2>No input</h2>
 * There is nowhere to put one. The host binds input only to the player's session, and a
 * spectator's datagrams of any other type are discarded.
 *
 * <pre>
 *    0  u32 seq                monotonic; the host discards anything &lt;= the last seen
 *    4  u32 sessionGen         the spectator's session
 *    8  u32 newestFrameId      newest frame held, NO_REFERENCE if none
 *   12  u16 heldMask           bit i: frame newestFrameId - i is held
 *   14  u8  flags              bit 0: only a keyframe can repair the chain
 *   15  u8  reserved           must be 0
 *   16  u16 audioFillMs        telemetry
 *   18  u16 reserved           must be 0
 *   20  = SIZE
 * </pre>
 */
public final class SpectatorFeedback {

    public static final int SIZE = 20;

    public static final int FLAG_WANT_KEYFRAME = 0x01;

    public int seq;
    public int sessionGen;
    public long newestFrameId = RemotePlayProtocol.NO_REFERENCE;
    public int heldMask;
    public int flags;
    public int audioFillMs;

    public void writeTo(byte[] dst) {
        ByteBuffer b = RemotePlayProtocol.wrap(dst, 0, SIZE);
        b.putInt(seq);
        b.putInt(sessionGen);
        b.putInt((int) newestFrameId);
        b.putShort((short) heldMask);
        b.put((byte) flags);
        b.put((byte) 0);
        b.putShort((short) audioFillMs);
        b.putShort((short) 0);
    }

    /**
     * Reads and validates a received packet.
     *
     * @throws ProtocolException if the length or a field does not add up
     */
    public void readFrom(byte[] src, int length) throws ProtocolException {
        if (length != SIZE) {
            throw new ProtocolException("spectator packet of " + length
                    + " bytes, expected " + SIZE);
        }
        ByteBuffer b = RemotePlayProtocol.wrap(src, 0, SIZE);
        seq = b.getInt();
        sessionGen = b.getInt();
        newestFrameId = b.getInt() & 0xFFFFFFFFL;
        heldMask = b.getShort() & 0xFFFF;
        flags = b.get() & 0xFF;
        int reserved8 = b.get() & 0xFF;
        audioFillMs = b.getShort() & 0xFFFF;
        int reserved16 = b.getShort() & 0xFFFF;
        if ((flags & ~FLAG_WANT_KEYFRAME) != 0) {
            throw new ProtocolException("undefined spectator flags: 0x"
                    + Integer.toHexString(flags));
        }
        if (reserved8 != 0 || reserved16 != 0) {
            throw new ProtocolException("reserved field not zero");
        }
    }

    public boolean wantsKeyframe() {
        return (flags & FLAG_WANT_KEYFRAME) != 0;
    }

    /** Fills the packet from a receiver's state. */
    public void fillFrom(VideoReceiver receiver) {
        long held = receiver.heldState();
        if (held < 0) {
            newestFrameId = RemotePlayProtocol.NO_REFERENCE;
            heldMask = 0;
        } else {
            newestFrameId = held >>> 16;
            heldMask = (int) (held & 0xFFFF);
        }
        flags = receiver.needsKeyframe() ? FLAG_WANT_KEYFRAME : 0;
    }
}
