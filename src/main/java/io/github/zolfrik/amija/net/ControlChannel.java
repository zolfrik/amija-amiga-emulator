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

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;

/**
 * The control channel: rare, reliable messages over TCP ({@code docs/remote-play-plan.md}
 * sections 3, 4).
 *
 * <p>The handshake goes through it and then very little else: host pause and resume, ping,
 * orderly close. Over TCP ordering and delivery come free, so the framing is as simple as it
 * gets:
 *
 * <pre>
 *   u16 length     number of bytes that follow, type included
 *   u8  type
 *   ... payload
 * </pre>
 *
 * <h2>The length is checked before allocating</h2>
 * The rule of section 8.2, very easy to honour here: the receive buffer is <b>a single
 * one</b>, allocated at construction at {@link RemotePlayProtocol#MAX_CONTROL_MESSAGE}. A
 * sender that declares more gets no memory: it gets an exception and the socket closed. No
 * defined message reaches 200 bytes, so the cap is twenty times what is needed and restricts
 * nothing legitimate.
 *
 * <p>Not thread-safe. In the remote play design the control channel is read by a single
 * thread and written by a single thread; where the two meet ({@code RemotePlayHost}) the
 * writing is synchronised by the user.
 */
public final class ControlChannel implements AutoCloseable {

    private static final int LENGTH_PREFIX = 2;

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final byte[] rxBuffer = new byte[RemotePlayProtocol.MAX_CONTROL_MESSAGE];
    private final byte[] txBuffer = new byte[LENGTH_PREFIX + RemotePlayProtocol.MAX_CONTROL_MESSAGE];

    /** A message received. The buffer is reused: it must be consumed before the next read. */
    public record Message(int type, byte[] data, int length) {
        public ByteBuffer body() {
            return RemotePlayProtocol.wrap(data, 0, length);
        }
    }

    public ControlChannel(Socket socket) throws IOException {
        this.socket = socket;
        socket.setTcpNoDelay(true);   // tiny messages: Nagle only adds delay here
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
    }

    public Socket socket() {
        return socket;
    }

    /** Read timeout, in milliseconds. 0 = wait indefinitely. */
    public void setTimeout(int millis) throws IOException {
        socket.setSoTimeout(millis);
    }

    /** Sends a message without a body. */
    public void send(int type) throws IOException {
        send(type, null, 0, 0);
    }

    public void send(int type, byte[] payload, int length) throws IOException {
        send(type, payload, 0, length);
    }

    public synchronized void send(int type, byte[] payload, int off, int length)
            throws IOException {
        int total = 1 + length;
        if (total > RemotePlayProtocol.MAX_CONTROL_MESSAGE) {
            throw new ProtocolException("messaggio di controllo di " + total
                    + " byte, massimo " + RemotePlayProtocol.MAX_CONTROL_MESSAGE);
        }
        txBuffer[0] = (byte) total;
        txBuffer[1] = (byte) (total >>> 8);
        txBuffer[2] = (byte) type;
        if (length > 0) {
            System.arraycopy(payload, off, txBuffer, LENGTH_PREFIX + 1, length);
        }
        out.write(txBuffer, 0, LENGTH_PREFIX + total);
        out.flush();
    }

    /**
     * Reads the next message.
     *
     * @throws java.net.SocketTimeoutException if the set timeout expires
     * @throws EOFException if the peer closed
     * @throws ProtocolException if the framing does not add up
     */
    public Message receive() throws IOException {
        int total = (readByte() & 0xFF) | ((readByte() & 0xFF) << 8);
        if (total < 1) {
            throw new ProtocolException("messaggio di controllo vuoto");
        }
        if (total > RemotePlayProtocol.MAX_CONTROL_MESSAGE) {
            throw new ProtocolException("messaggio di controllo di " + total
                    + " byte, massimo " + RemotePlayProtocol.MAX_CONTROL_MESSAGE);
        }
        int type = readByte() & 0xFF;
        int length = total - 1;
        readFully(rxBuffer, length);
        return new Message(type, rxBuffer, length);
    }

    /**
     * Reads the expected message, or fails.
     *
     * <p>During the handshake a message out of sequence is not a case to handle: it is a peer
     * that does not speak this protocol, or does not speak it honestly. Close.
     */
    public Message expect(int type) throws IOException {
        Message m = receive();
        if (m.type() != type) {
            throw new ProtocolException("atteso il messaggio di tipo " + type
                    + ", ricevuto " + m.type());
        }
        return m;
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Closing is the only thing being done: there is nothing to recover.
        }
    }

    /**
     * Closes after making sure the last message has arrived.
     *
     * <p>Needed for the REJECT, because of a classic TCP trap: closing a socket that still has
     * unread incoming data sends an <b>RST</b>, and the RST <i>cancels</i> what had already left.
     * Instead of the reason for the refusal the peer sees a reset connection, exactly the
     * obscure message the reason was meant to avoid. Windows does it systematically.
     *
     * <p>The cure is the half close: announce the end of sending, consume what the peer still
     * had in the pipe, and only then really close.
     *
     * <h2>Draining does not mean listening forever</h2>
     * The socket timeout limits <b>silence</b>, not duration: a peer that sends without pause
     * makes every {@code read} return before the deadline, and the loop never ends. Not a
     * subtlety: whoever calls this method is closing a connection it has just <b>refused</b>,
     * so the peer on the other side has not authenticated and does not know the join code, and
     * talking would be enough for it to hold a {@code RemotePlayHost} thread as long as it
     * liked.
     *
     * <p>So the queue is drained within two <i>constant</i> caps: {@value #LINGER_MS} ms in all
     * and {@value #LINGER_MAX_BYTES} bytes. Beyond them, the RST being avoided is still
     * preferable to a thread that does not come back. The callers keep this wait off their
     * critical paths anyway (see {@code RemotePlayHost.closeAside}): the cap is the second net,
     * not the first.
     */
    public void closeGracefully() {
        try {
            socket.shutdownOutput();
            socket.setSoTimeout(LINGER_MS);
            byte[] sink = new byte[512];
            long deadline = System.nanoTime() + LINGER_MS * 1_000_000L;
            int drained = 0;
            int n;
            while ((n = in.read(sink)) >= 0) {
                // Discarded: it only serves to drain the queue, so the close is not an RST.
                drained += n;
                if (drained >= LINGER_MAX_BYTES || System.nanoTime() - deadline >= 0) {
                    break;   // the peer is not saying goodbye: it is talking over us
                }
            }
        } catch (IOException ignored) {
            // The peer left on its own: the outcome wanted anyway.
        } finally {
            close();
        }
    }

    /** How long to wait for the peer before really closing, after a REJECT. */
    private static final int LINGER_MS = 500;

    /** How many bytes in all may be thrown away while draining the queue. */
    private static final int LINGER_MAX_BYTES = 64 * 1024;

    // =====================================================================

    private int readByte() throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new EOFException("canale di controllo chiuso dal peer");
        }
        return b;
    }

    private void readFully(byte[] dst, int length) throws IOException {
        int read = 0;
        while (read < length) {
            int n = in.read(dst, read, length - read);
            if (n < 0) {
                throw new EOFException("canale di controllo chiuso a meta' messaggio");
            }
            read += n;
        }
    }
}
