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

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;

/**
 * The media channel: sealed datagrams over UDP ({@code docs/remote-play-plan.md} sections 4,
 * 8).
 *
 * <p>Video and audio to the guest and feedback to the host travel on it. It does not know what
 * it carries: it puts on and takes off the seal, counts, and filters out whoever is not the
 * peer.
 *
 * <h2>The peer's address is adopted, not configured</h2>
 * The guest knows the host from the TCP channel. The host does <b>not</b>: behind NAT the
 * guest's UDP source port has no relation to that of its TCP, and neither side can know it in
 * advance. So the host waits for the first datagram that <b>opens with the session key</b> and
 * adopts its sender. It is the only way that works behind NAT and also the safest: the
 * address is declared by nobody, it is <i>proved</i>.
 *
 * <h2>The filter costs a comparison, not a decryption</h2>
 * Once the peer is adopted, datagrams coming from elsewhere are discarded <b>before</b> any
 * cryptographic work (section 8.3). Whoever wants to keep the host's CPU busy with fake packets
 * gets an address comparison. Before adoption that filter does not exist yet (there the only
 * defence is trying to open), so the <i>number</i> of attempts is limited:
 * {@value #ADOPTION_ATTEMPTS_PER_SECOND} per second, enough for anyone arriving in good faith
 * and not enough for anything else.
 *
 * <h2>On replays</h2>
 * The GCM tag proves a datagram was produced by someone who knows the key, not that it is
 * new. A repeated packet is harmless by construction for the two uses that exist, though: a
 * video fragment already seen is discarded by {@link FragmentReassembler}, and an old feedback
 * by the monotonic {@code seq} of {@link FeedbackPacket}. Filtering here would be a second
 * barrier against a reordering that is normal on UDP, and would throw away good packets.
 */
public final class MediaLink implements AutoCloseable {

    /** Adoption attempts per second, before the peer's address is known. */
    public static final int ADOPTION_ATTEMPTS_PER_SECOND = 20;

    /** Outcome of a successful receive. The plaintext is in the caller's buffer. */
    public record Received(int type, int length, int seq) {
    }

    private final DatagramSocket socket;
    private final MediaCipher cipher;
    private final NetImpairment impairment;

    private final byte[] txBuffer = new byte[RemotePlayProtocol.MAX_DATAGRAM];
    private final byte[] rxBuffer = new byte[RemotePlayProtocol.MAX_DATAGRAM];
    private final DatagramPacket txPacket =
            new DatagramPacket(txBuffer, RemotePlayProtocol.MAX_DATAGRAM);
    private final DatagramPacket rxPacket =
            new DatagramPacket(rxBuffer, RemotePlayProtocol.MAX_DATAGRAM);

    private volatile SocketAddress peer;
    private int txSeq;
    private long adoptionWindowStart;
    private int adoptionAttempts;

    private long datagramsSent;
    private long datagramsReceived;
    private long bytesSent;
    private long rejectedForeign;
    private long rejectedUnsealed;

    /**
     * @param socket     UDP socket already open; the link does not close it, its owner does
     * @param cipher     session seal, already oriented in the right direction
     * @param peer       known recipient (guest), or {@code null} to adopt it (host)
     * @param impairment link simulation, {@link NetImpairment#NONE} in production
     */
    public MediaLink(DatagramSocket socket, MediaCipher cipher, SocketAddress peer,
                     NetImpairment impairment) {
        this.socket = socket;
        this.cipher = cipher;
        this.peer = peer;
        this.impairment = impairment == null ? NetImpairment.NONE : impairment;
    }

    public SocketAddress peer() {
        return peer;
    }

    public boolean hasPeer() {
        return peer != null;
    }

    /** Receive timeout: beyond it {@link #receive} returns {@code null}. */
    public void setTimeout(int millis) throws IOException {
        socket.setSoTimeout(millis);
    }

    /**
     * Seals and sends a payload.
     *
     * @return {@code false} if the peer is not known yet: not an error, it is the wait for the
     *         guest's first datagram
     */
    public synchronized boolean send(int type, byte[] plain, int off, int length)
            throws IOException {
        SocketAddress destination = peer;
        if (destination == null) {
            return false;
        }
        if (length > RemotePlayProtocol.MAX_PLAINTEXT) {
            throw new ProtocolException("payload di " + length + " byte, massimo "
                    + RemotePlayProtocol.MAX_PLAINTEXT);
        }
        int total = cipher.seal(type, txSeq++, plain, off, length, txBuffer);
        txPacket.setData(txBuffer, 0, total);
        txPacket.setSocketAddress(destination);
        impairment.send(socket, txPacket);
        datagramsSent++;
        bytesSent += total;
        return true;
    }

    /**
     * Receives and opens the next valid datagram.
     *
     * @param plainOut buffer for the plaintext, at least {@link RemotePlayProtocol#MAX_PLAINTEXT}
     * @return the outcome, or {@code null} if the timeout expired or the datagram was
     *         discarded. <b>Discarding is not an exception</b>: on a channel open to the
     *         Internet foreign traffic is normal, and treating it as a failure would close
     *         the session when ignoring a packet is enough.
     */
    public Received receive(byte[] plainOut) throws IOException {
        rxPacket.setData(rxBuffer, 0, RemotePlayProtocol.MAX_DATAGRAM);
        try {
            socket.receive(rxPacket);
        } catch (SocketTimeoutException e) {
            return null;
        }
        int length = rxPacket.getLength();
        SocketAddress from = rxPacket.getSocketAddress();

        SocketAddress known = peer;
        if (known != null) {
            if (!known.equals(from)) {
                rejectedForeign++;
                return null;
            }
        } else if (!allowAdoptionAttempt()) {
            rejectedForeign++;
            return null;
        }

        int plainLength;
        try {
            plainLength = cipher.open(rxBuffer, length, plainOut);
        } catch (ProtocolException e) {
            rejectedUnsealed++;
            return null;
        }

        if (known == null) {
            // It opened with the session key: it is the guest, wherever it is.
            peer = from;
        }
        datagramsReceived++;
        return new Received(RemotePlayProtocol.headerType(rxBuffer), plainLength,
                RemotePlayProtocol.headerSeq(rxBuffer));
    }

    /** Datagrams from a sender that is not the peer, or beyond the adoption rate. */
    public long rejectedForeign() {
        return rejectedForeign;
    }

    /** Datagrams from the peer that failed the tag: corrupt or fake. */
    public long rejectedUnsealed() {
        return rejectedUnsealed;
    }

    public long datagramsSent() {
        return datagramsSent;
    }

    public long datagramsReceived() {
        return datagramsReceived;
    }

    public long bytesSent() {
        return bytesSent;
    }

    /**
     * Closes neither the socket nor the impairment: <b>it does not own them</b>.
     *
     * <p>On the host there is a single UDP socket for the whole life of the server and every
     * session builds its own link on it; if the link closed the impairment, the next session
     * would find one already switched off. Ownership belongs to whoever created them
     * ({@code RemotePlayHost} and {@code RemotePlayGuest}) and stays there.
     */
    @Override
    public void close() {
    }

    // =====================================================================

    private boolean allowAdoptionAttempt() {
        long now = System.nanoTime();
        if (now - adoptionWindowStart > 1_000_000_000L) {
            adoptionWindowStart = now;
            adoptionAttempts = 0;
        }
        return ++adoptionAttempts <= ADOPTION_ATTEMPTS_PER_SECOND;
    }
}
