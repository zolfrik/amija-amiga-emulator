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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Constants and structure of the remote play protocol ({@code docs/remote-play-plan.md}
 * section 4).
 *
 * <h2>Two channels, one port</h2>
 * <b>TCP</b> for control (handshake, pause, disconnection: a few bytes per second, where
 * ordering and guaranteed delivery are handy and free) and <b>UDP</b> for media and input.
 * The same <i>number</i> for both, so UPnP has to create a single pair of mappings and the
 * user has a single number to pass on.
 *
 * <p>Why UDP for the media: with TCP a retransmission blocks everything for an RTT and then
 * the stream chases. In a real-time game <b>a lost frame is better than a late frame</b>, and
 * the codec is built to recover from a loss by itself (section 4.1: the host compresses against
 * the last frame the guest confirmed, so a lost frame widens the next delta instead of
 * corrupting the screen).
 *
 * <h2>Datagram</h2>
 * <pre>
 *   u16 magic      'AJ'
 *   u8  type       VIDEO | AUDIO | FEEDBACK
 *   u8  flags      reserved, must be 0
 *   u32 seq        per-direction counter, monotonic, NEVER reused in the session
 *   ...            AES-GCM sealed payload (16-byte tag included)
 * </pre>
 * The header travels in clear (it is needed for routing and to build the nonce) but is
 * <b>authenticated as AAD</b>: whoever alters it invalidates the tag and the packet is
 * discarded.
 *
 * <h2>Why 1200 bytes</h2>
 * Below any real MTU: 1500 Ethernet minus the worst encapsulation met in practice (PPPoE 8,
 * IPv6 40, a VPN tunnel 60+). Fragmenting at the IP level means the loss of <i>one</i> IP
 * fragment throws away the whole datagram, which would double the effect of every loss.
 */
public final class RemotePlayProtocol {

    /**
     * Protocol version. A mismatch is an explicit REJECT, not an obscure error.
     *
     * <p>Version 2 gave a meaning to the first reserved byte of the feedback packet: the device
     * the guest plugged into its port ({@link FeedbackPacket}). A version-1 peer would send it
     * as zero, i.e. <i>empty port</i>, and would find its commands ignored with no error
     * anywhere: exactly the silent failure the version check exists to turn into a readable
     * sentence.
     */
    public static final int VERSION = 2;

    /** 'A','J': a zero-cost filter against traffic that does not concern us. */
    public static final int MAGIC = 0x414A;

    public static final int TYPE_VIDEO = 1;
    public static final int TYPE_AUDIO = 2;      // Fase 4
    public static final int TYPE_FEEDBACK = 3;

    /** Datagram header: magic, type, flags, seq. */
    public static final int DATAGRAM_HEADER = 8;

    /** Ceiling of the whole datagram, header and GCM tag included. */
    public static final int MAX_DATAGRAM = 1200;

    /** AES-GCM authentication tag. */
    public static final int GCM_TAG = 16;

    /** Plaintext payload bytes that fit in one datagram. */
    public static final int MAX_PLAINTEXT = MAX_DATAGRAM - DATAGRAM_HEADER - GCM_TAG;

    /** Fragment header: frameId, fragIdx, fragCount. */
    public static final int FRAGMENT_HEADER = 8;

    /** Useful blob bytes per fragment. */
    public static final int FRAGMENT_DATA = MAX_PLAINTEXT - FRAGMENT_HEADER;

    /**
     * Cap on the fragments of a frame: 64 x 1168 = 74 KB. The most expensive keyframe measured
     * is 22 KB, so there is three times the margin, and it is a <b>constant</b> cap, which is
     * the point: a malicious {@code fragCount} must not be able to ask for memory.
     */
    public static final int MAX_FRAGMENTS = 64;

    /** Codec identifier in the frame blob. One byte, to be able to change course (section 15.1). */
    public static final int CODEC_AJV1 = 1;

    /** {@code refFrameId} meaning "no reference": the frame is a keyframe. */
    public static final long NO_REFERENCE = 0xFFFFFFFFL;

    // --- control channel (TCP) -------------------------------------------------------

    public static final int CTRL_HELLO = 1;
    public static final int CTRL_CHALLENGE = 2;
    public static final int CTRL_AUTH = 3;
    public static final int CTRL_WELCOME = 4;
    public static final int CTRL_REJECT = 5;
    public static final int CTRL_PAUSED = 6;
    public static final int CTRL_RESUMED = 7;
    public static final int CTRL_PING = 8;
    public static final int CTRL_PONG = 9;
    public static final int CTRL_BYE = 10;

    /** Ceiling on a control message. None of the defined ones reaches 200 bytes. */
    public static final int MAX_CONTROL_MESSAGE = 4096;

    // --- REJECT reasons ----------------------------------------------------------------

    public static final int REJECT_AUTH = 1;
    public static final int REJECT_BUSY = 2;
    public static final int REJECT_PROTOCOL = 3;
    public static final int REJECT_SHUTDOWN = 4;
    /** The host accepts only its own local network (section 8.5) and this peer is not on it. */
    public static final int REJECT_LAN_ONLY = 5;

    private RemotePlayProtocol() {
    }

    public static String rejectReason(int code) {
        return switch (code) {
            case REJECT_AUTH -> "codice di accesso errato";
            case REJECT_BUSY -> "c'e' gia' un ospite collegato";
            case REJECT_PROTOCOL -> "versione di protocollo diversa";
            case REJECT_SHUTDOWN -> "l'host ha chiuso la sessione";
            case REJECT_LAN_ONLY -> "l'host accetta solo la propria rete locale";
            default -> "motivo sconosciuto (" + code + ")";
        };
    }

    /** Little-endian buffer, like the whole protocol. */
    public static ByteBuffer buffer(int capacity) {
        return ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN);
    }

    public static ByteBuffer wrap(byte[] array, int offset, int length) {
        return ByteBuffer.wrap(array, offset, length).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** Writes the datagram header at the start of {@code dst}. */
    public static void writeHeader(byte[] dst, int type, int flags, int seq) {
        dst[0] = (byte) MAGIC;
        dst[1] = (byte) (MAGIC >>> 8);
        dst[2] = (byte) type;
        dst[3] = (byte) flags;
        dst[4] = (byte) seq;
        dst[5] = (byte) (seq >>> 8);
        dst[6] = (byte) (seq >>> 16);
        dst[7] = (byte) (seq >>> 24);
    }

    /**
     * Checks magic, type and flags of a received datagram.
     *
     * <p>Done <b>before</b> any cryptographic work: it is the filter that makes ignoring traffic
     * that is not ours free. The real authentication remains the GCM tag: this only discards
     * what does not even deserve an attempt.
     *
     * @throws ProtocolException if the header is not ours or not clean
     */
    public static void checkHeader(byte[] src, int length) throws ProtocolException {
        if (length < DATAGRAM_HEADER) {
            throw new ProtocolException("datagramma di " + length + " byte: troppo corto");
        }
        int magic = (src[0] & 0xFF) | ((src[1] & 0xFF) << 8);
        if (magic != MAGIC) {
            throw new ProtocolException("magic 0x" + Integer.toHexString(magic) + " non nostro");
        }
        int type = src[2] & 0xFF;
        if (type != TYPE_VIDEO && type != TYPE_AUDIO && type != TYPE_FEEDBACK) {
            throw new ProtocolException("tipo di datagramma sconosciuto: " + type);
        }
        if (src[3] != 0) {
            throw new ProtocolException("flag non nulli: 0x" + Integer.toHexString(src[3] & 0xFF));
        }
    }

    public static int headerType(byte[] src) {
        return src[2] & 0xFF;
    }

    public static int headerSeq(byte[] src) {
        return (src[4] & 0xFF) | ((src[5] & 0xFF) << 8)
                | ((src[6] & 0xFF) << 16) | ((src[7] & 0xFF) << 24);
    }
}
