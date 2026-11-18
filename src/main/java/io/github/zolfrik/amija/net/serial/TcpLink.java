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

package io.github.zolfrik.amija.net.serial;

import io.github.zolfrik.amija.net.ControlChannel;
import io.github.zolfrik.amija.net.ProtocolException;
import io.github.zolfrik.amija.net.RemotePlayProtocol;
import io.github.zolfrik.amija.net.SessionCrypto;

import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * The null modem cable over one TCP connection (protocol AJS1, {@link SerialLinkProtocol}).
 *
 * <p>The serial line wants reliable, ordered delivery, and the head-of-line blocking of TCP
 * costs nothing here: without the missing event the machine would have to stop anyway.
 *
 * <p>Two threads besides the emulation one. The emulation thread only queues events and
 * publishes its horizon, without a lock; the writer drains the queue and writes; the reader
 * hands what arrives to the {@link LinkCore}. Within a frame the events are queued before the
 * horizon moves, which is the order {@link LinkCore#drain} relies on.
 *
 * <p>Life of a link: {@link #host} or {@link #join} run the handshake and the measure of the
 * round trip and return a link that knows the delay of the cable and the clock of the other
 * machine; {@link #start} joins it to a core and starts the threads; {@link #close} ends it.
 */
public final class TcpLink implements LinkCore.Transport, AutoCloseable {

    /** Hears what happens to the link. Called on the threads of the link. */
    public interface Listener {
        /** The link is over; {@code reason} is for the user. */
        void closed(String reason);

        /** The other machine was paused or resumed by its user. */
        void peerPaused(boolean paused);

        /** The other end's horizon has been taken in. Called on the reader thread. */
        default void horizonMoved() { }
    }

    /** Wall time without a frame after which the latest horizon is sent anyway. */
    private static final long FLUSH_NS = 1_000_000L;
    /**
     * Wall time without any frame after which one is sent anyway, even if the horizon has not
     * moved (a paused machine): the other end hears that this one is still there.
     */
    private static final long KEEPALIVE_NS = 1_000_000_000L;
    /** Wall time without a frame from the other end after which the link is given up. */
    static volatile int silenceMs = 10_000;
    /** How long the writer sleeps with nothing to do. */
    private static final long IDLE_PARK_NS = 200_000L;
    private static final int FRAME_MAX = 2048;

    private final ControlChannel channel;
    private final FrameCipher cipher;
    private final long peerHz;
    private final long delayNs;
    private final long rttNs;

    private final ConcurrentLinkedQueue<LineEvent> outbox = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Integer> controls = new ConcurrentLinkedQueue<>();
    private final AtomicLong horizon = new AtomicLong(0);
    private volatile long sentHorizon = -1;
    /** Set when the latest horizon must go at once, without waiting for a quarter of the delay. */
    private volatile boolean flushRequested;
    private volatile boolean open = true;
    private volatile LinkCore core;
    private volatile Listener listener;
    private Thread reader;
    private Thread writer;
    private final byte[] plain = new byte[FRAME_MAX];
    private final byte[] sealed = new byte[FRAME_MAX];
    private final byte[] opened = new byte[FRAME_MAX];
    private String closeReason;
    private volatile int byeReason = SerialLinkProtocol.BYE_CLOSED;
    private static final int CLOSE_WAIT_MS = 500;

    private TcpLink(ControlChannel channel, FrameCipher cipher, long peerHz, long delayNs, long rttNs) {
        this.channel = channel;
        this.cipher = cipher;
        this.peerHz = peerHz;
        this.delayNs = delayNs;
        this.rttNs = rttNs;
    }

    /** Master cycles per second of the other machine. */
    public long peerHz() { return peerHz; }

    /** The delay of the cable, in nanoseconds of line time: the same at both ends. */
    public long delayNs() { return delayNs; }

    /** Round trip measured before the cable was joined, in nanoseconds: the median. */
    public long rttNs() { return rttNs; }

    // =====================================================================
    // Joining
    // =====================================================================

    /**
     * Host side: authenticates the peer that just connected, measures the round trip, fixes
     * the delay of the cable and tells the peer.
     *
     * @param hz       master cycles per second of this machine
     * @param delayNs  the delay to use, or 0 to derive it from the measure
     * @throws IOException if the peer does not authenticate, speaks another protocol or drops
     */
    public static TcpLink host(Socket socket, String joinCode, long hz, long delayNs) throws IOException {
        ControlChannel ch = new ControlChannel(socket);
        try {
            ch.setTimeout(HANDSHAKE_TIMEOUT_MS);
            ControlChannel.Message hello = ch.expect(SerialLinkProtocol.MSG_HELLO);
            ByteBuffer hb = hello.body();
            byte[] nonce = new byte[SessionCrypto.NONCE_BYTES];
            long peerHz;
            long peerDelay;
            try {
                if (hb.get() != 'A' || hb.get() != 'J' || hb.get() != 'S'
                        || (hb.get() & 0xFF) != SerialLinkProtocol.VERSION) {
                    reject(ch, SerialLinkProtocol.REJECT_PROTOCOL);
                    throw new ProtocolException(SerialLinkProtocol.rejectReason(SerialLinkProtocol.REJECT_PROTOCOL));
                }
                peerHz = hb.getLong();
                peerDelay = hb.getLong();
                hb.get(nonce);
            } catch (java.nio.BufferUnderflowException e) {
                throw new ProtocolException("HELLO truncated", e);
            }
            checkClock(peerHz);
            if (peerDelay < 0 || peerDelay > SerialLinkProtocol.MAX_DELAY_NS) {
                throw new ProtocolException("delay out of range: " + peerDelay);
            }

            byte[] salt = SessionCrypto.randomBytes(SessionCrypto.SALT_BYTES);
            byte[] nonce2 = SessionCrypto.randomBytes(SessionCrypto.NONCE_BYTES);
            byte[] challenge = Arrays.copyOf(salt, salt.length + nonce2.length);
            System.arraycopy(nonce2, 0, challenge, salt.length, nonce2.length);
            ch.send(SerialLinkProtocol.MSG_CHALLENGE, challenge, challenge.length);

            ControlChannel.Message auth = ch.expect(SerialLinkProtocol.MSG_AUTH);
            SessionCrypto crypto = SessionCrypto.derive(joinCode, salt);
            byte[] expected = crypto.guestTag(nonce, nonce2, SerialLinkProtocol.AUTH_DOMAIN);
            if (auth.length() != SessionCrypto.TAG_BYTES
                    || !SessionCrypto.tagsMatch(expected, Arrays.copyOf(auth.data(), auth.length()))) {
                crypto.destroy();
                reject(ch, SerialLinkProtocol.REJECT_AUTH);
                throw new ProtocolException(SerialLinkProtocol.rejectReason(SerialLinkProtocol.REJECT_AUTH));
            }
            ByteBuffer wb = RemotePlayProtocol.buffer(SessionCrypto.TAG_BYTES + 8);
            wb.put(crypto.hostTag(nonce, nonce2, SerialLinkProtocol.AUTH_DOMAIN)).putLong(hz);
            ch.send(SerialLinkProtocol.MSG_WELCOME, wb.array(), wb.position());
            FrameCipher cipher = new FrameCipher(crypto.mediaKey(nonce, nonce2),
                    crypto.noncePrefix(nonce, nonce2), 0);
            crypto.destroy();

            // The measure, then the delay: the larger of what either end asked for, or, if
            // neither asked, what the round trip needs.
            Bootstrap boot = new Bootstrap(ch, cipher);
            long rtt = boot.measure();
            long delay = Math.max(delayNs, peerDelay);
            if (delay == 0) delay = delayFor(rtt);
            delay = clampDelay(delay);
            ByteBuffer sb = boot.frame(SerialLinkProtocol.KIND_START);
            sb.putLong(delay).putLong(hz).putLong(peerHz);
            boot.send(sb);
            ch.setTimeout(0);
            TcpLink link = new TcpLink(ch, cipher, peerHz, delay, rtt);
            return link;
        } catch (IOException | RuntimeException e) {
            ch.close();
            throw e;
        }
    }

    /**
     * Guest side: proves the join code to the host and takes the delay the host fixes.
     *
     * @param hz       master cycles per second of this machine
     * @param delayNs  the delay this end asks for, or 0 to leave it to the measure
     * @throws IOException with the reason, if the host refuses or does not know the code
     */
    public static TcpLink join(Socket socket, String joinCode, long hz, long delayNs) throws IOException {
        ControlChannel ch = new ControlChannel(socket);
        try {
            ch.setTimeout(HANDSHAKE_TIMEOUT_MS);
            byte[] nonce = SessionCrypto.randomBytes(SessionCrypto.NONCE_BYTES);
            ByteBuffer hb = RemotePlayProtocol.buffer(4 + 8 + 8 + nonce.length);
            hb.put((byte) 'A').put((byte) 'J').put((byte) 'S').put((byte) SerialLinkProtocol.VERSION);
            hb.putLong(hz).putLong(delayNs).put(nonce);
            ch.send(SerialLinkProtocol.MSG_HELLO, hb.array(), hb.position());

            ControlChannel.Message challenge = expectOrRejected(ch, SerialLinkProtocol.MSG_CHALLENGE);
            if (challenge.length() != SessionCrypto.SALT_BYTES + SessionCrypto.NONCE_BYTES) {
                throw new ProtocolException("CHALLENGE of " + challenge.length() + " bytes");
            }
            byte[] salt = new byte[SessionCrypto.SALT_BYTES];
            byte[] nonce2 = new byte[SessionCrypto.NONCE_BYTES];
            challenge.body().get(salt).get(nonce2);
            SessionCrypto crypto = SessionCrypto.derive(joinCode, salt);
            byte[] tag = crypto.guestTag(nonce, nonce2, SerialLinkProtocol.AUTH_DOMAIN);
            ch.send(SerialLinkProtocol.MSG_AUTH, tag, tag.length);

            ControlChannel.Message welcome = expectOrRejected(ch, SerialLinkProtocol.MSG_WELCOME);
            if (welcome.length() != SessionCrypto.TAG_BYTES + 8) {
                crypto.destroy();
                throw new ProtocolException("WELCOME of " + welcome.length() + " bytes");
            }
            ByteBuffer wb = welcome.body();
            byte[] hostTag = new byte[SessionCrypto.TAG_BYTES];
            wb.get(hostTag);
            long peerHz = wb.getLong();
            if (!SessionCrypto.tagsMatch(crypto.hostTag(nonce, nonce2, SerialLinkProtocol.AUTH_DOMAIN), hostTag)) {
                crypto.destroy();
                throw new ProtocolException("the host did not prove it knows the join code");
            }
            checkClock(peerHz);
            FrameCipher cipher = new FrameCipher(crypto.mediaKey(nonce, nonce2),
                    crypto.noncePrefix(nonce, nonce2), 1);
            crypto.destroy();

            Bootstrap boot = new Bootstrap(ch, cipher);
            ByteBuffer start = boot.answerUntilStart();
            long delay = start.getLong();
            long hostHz = start.getLong();
            long guestHz = start.getLong();
            if (hostHz != peerHz || guestHz != hz || delay < SerialLinkProtocol.MIN_DELAY_NS
                    || delay > SerialLinkProtocol.MAX_DELAY_NS || delay < delayNs) {
                throw new ProtocolException("the host fixed parameters this end cannot accept");
            }
            ch.setTimeout(0);
            TcpLink link = new TcpLink(ch, cipher, peerHz, delay, boot.rtt);
            return link;
        } catch (IOException | RuntimeException e) {
            ch.close();
            throw e;
        }
    }

    private static final int HANDSHAKE_TIMEOUT_MS = 10_000;

    /**
     * The delay a round trip needs. The network is the smaller part of it: what a machine
     * must be able to ride out without stopping is the other one falling a few milliseconds
     * behind real time, as any emulator does now and then, plus the period of the beacon (a
     * quarter of the delay). So: the round trip and three milliseconds, a third more, rounded
     * up to the millisecond. Measured with both ends on one loaded machine, four milliseconds
     * meant about ten short stops a second, eight about three.
     */
    static long delayFor(long rttNs) {
        long d = (rttNs + 3_000_000L) * 4 / 3;
        return (d + 999_999L) / 1_000_000L * 1_000_000L;
    }

    private static long clampDelay(long d) {
        return Math.max(SerialLinkProtocol.MIN_DELAY_NS, Math.min(SerialLinkProtocol.MAX_DELAY_NS, d));
    }

    private static void checkClock(long hz) throws ProtocolException {
        if (hz < SerialLinkProtocol.MIN_CLOCK_HZ || hz > SerialLinkProtocol.MAX_CLOCK_HZ) {
            throw new ProtocolException("master clock out of range: " + hz);
        }
    }

    private static void reject(ControlChannel ch, int reason) {
        try {
            ch.send(SerialLinkProtocol.MSG_REJECT, new byte[] { (byte) reason }, 1);
            ch.closeGracefully();
        } catch (IOException ignored) {
            // the peer is gone: nothing to tell it
        }
    }

    private static ControlChannel.Message expectOrRejected(ControlChannel ch, int type) throws IOException {
        ControlChannel.Message m = ch.receive();
        if (m.type() == SerialLinkProtocol.MSG_REJECT) {
            int reason = m.length() > 0 ? m.data()[0] & 0xFF : 0;
            throw new ProtocolException(SerialLinkProtocol.rejectReason(reason));
        }
        if (m.type() != type) throw new ProtocolException("unexpected message " + m.type());
        return m;
    }

    /** The sealed frames exchanged before the threads exist: the measure and START. */
    private static final class Bootstrap {
        private final ControlChannel ch;
        private final FrameCipher cipher;
        private final byte[] plain = new byte[64];
        private final byte[] sealed = new byte[128];
        private final byte[] opened = new byte[128];
        long rtt;

        Bootstrap(ControlChannel ch, FrameCipher cipher) {
            this.ch = ch;
            this.cipher = cipher;
        }

        ByteBuffer frame(int kind) {
            return ByteBuffer.wrap(plain).put((byte) kind).putLong(0);
        }

        void send(ByteBuffer b) throws IOException {
            int n = cipher.seal(plain, b.position(), sealed);
            ch.send(SerialLinkProtocol.MSG_SEALED, sealed, n);
        }

        ByteBuffer receive(int[] kind) throws IOException {
            ControlChannel.Message m = ch.expect(SerialLinkProtocol.MSG_SEALED);
            ByteBuffer b = TcpLink.open(cipher, m, opened);
            kind[0] = b.get() & 0xFF;
            b.getLong();
            return b;
        }

        /** Host: a burst of pings; the median round trip. */
        long measure() throws IOException {
            long[] samples = new long[SerialLinkProtocol.MEASURE_PINGS];
            int[] kind = new int[1];
            for (int i = 0; i < samples.length; i++) {
                long t0 = System.nanoTime();
                ByteBuffer b = frame(SerialLinkProtocol.KIND_PING);
                b.putLong(t0);
                send(b);
                ByteBuffer r = receive(kind);
                if (kind[0] != SerialLinkProtocol.KIND_PONG || r.getLong() != t0) {
                    throw new ProtocolException("the measure was not answered");
                }
                samples[i] = System.nanoTime() - t0;
            }
            Arrays.sort(samples);
            rtt = samples[samples.length * 3 / 4];
            return rtt;
        }

        /** Guest: answers the pings and returns the body of START. */
        ByteBuffer answerUntilStart() throws IOException {
            int[] kind = new int[1];
            long first = 0;
            long last = 0;
            int pings = 0;
            for (int i = 0; i <= SerialLinkProtocol.MEASURE_PINGS; i++) {
                ByteBuffer r = receive(kind);
                if (kind[0] == SerialLinkProtocol.KIND_START) {
                    if (pings > 1) rtt = (last - first) / (pings - 1);
                    return r;
                }
                if (kind[0] != SerialLinkProtocol.KIND_PING) {
                    throw new ProtocolException("unexpected frame before START: " + kind[0]);
                }
                long now = System.nanoTime();
                if (pings++ == 0) first = now;
                last = now;
                ByteBuffer b = frame(SerialLinkProtocol.KIND_PONG);
                b.putLong(r.getLong());
                send(b);
            }
            throw new ProtocolException("START never came");
        }
    }

    private static ByteBuffer open(FrameCipher cipher, ControlChannel.Message m, byte[] dst)
            throws ProtocolException {
        if (m.length() > dst.length + FrameCipher.TAG_BYTES) {
            throw new ProtocolException("frame of " + m.length() + " bytes");
        }
        int n = cipher.open(m.data(), m.length(), dst);
        if (n < 9) throw new ProtocolException("frame too short");
        return ByteBuffer.wrap(dst, 0, n);
    }

    // =====================================================================
    // Running
    // =====================================================================

    /** Joins the link to its core and starts carrying. */
    public synchronized void start(LinkCore core, Listener listener) {
        this.core = core;
        this.listener = listener;
        core.setTransport(this);
        try {
            // The other end sends at least a keepalive a second: a longer silence means it is
            // gone, even when the network never says so (a cable pulled out, a router reset).
            channel.setTimeout(silenceMs);
        } catch (IOException e) {
            fail("the connection dropped: " + describe(e));
        }
        reader = new Thread(this::readLoop, "AmiJa-serial-rx");
        writer = new Thread(this::writeLoop, "AmiJa-serial-tx");
        reader.setDaemon(true);
        writer.setDaemon(true);
        reader.start();
        writer.start();
    }

    public boolean isOpen() { return open; }

    @Override
    public void send(LineEvent event) {
        outbox.add(event);
        LockSupport.unpark(writer);
    }

    @Override
    public void sendHorizon(long h) {
        horizon.lazySet(h);
        // A beacon every quarter of the delay keeps the other end clear of its limit; less
        // often is enough when nothing else has to go.
        if (h - sentHorizon >= delayNs / 4) LockSupport.unpark(writer);
    }

    /**
     * Sends the latest horizon now. Called when this machine stops at its window: the other
     * one may be stopped at its own, waiting for exactly this horizon.
     */
    public void flushHorizon() {
        if (horizon.get() > sentHorizon) {
            flushRequested = true;
            LockSupport.unpark(writer);
        }
    }

    /** Tells the other end that this machine was paused or resumed by its user. */
    public void sendPaused(boolean paused) {
        controls.add(paused ? SerialLinkProtocol.KIND_PAUSE : SerialLinkProtocol.KIND_RESUME);
        LockSupport.unpark(writer);
    }

    private void writeLoop() {
        long lastWrite = System.nanoTime();
        try {
            while (open) {
                boolean wrote = false;
                // The flush request before the horizon, so the horizon read covers it; the
                // horizon first, the events after: whatever the horizon read here covers was
                // queued before it was published.
                boolean flush = flushRequested;
                if (flush) flushRequested = false;
                long h = horizon.get();
                while (!outbox.isEmpty()) {
                    ByteBuffer b = frame(SerialLinkProtocol.KIND_EVENTS, h);
                    int countAt = b.position();
                    b.put((byte) 0);
                    int n = 0;
                    LineEvent e;
                    while (n < SerialLinkProtocol.MAX_EVENTS_PER_FRAME && (e = outbox.poll()) != null) {
                        e.encode(b);
                        n++;
                    }
                    b.put(countAt, (byte) n);
                    // Only the frame that empties the queue may carry the new horizon: one
                    // that leaves events behind repeats the old one, or the other end could
                    // run past events that are still to come.
                    boolean last = outbox.isEmpty();
                    if (!last) b.putLong(1, Math.max(0, sentHorizon));
                    write(b);
                    if (last) sentHorizon = h;
                    wrote = true;
                }
                Integer c;
                while ((c = controls.poll()) != null) {
                    if (c == SerialLinkProtocol.KIND_BYE) {
                        // The last frame: say why, let it arrive, and stop.
                        write(frame(c, h).put((byte) byeReason));
                        open = false;
                        channel.closeGracefully();
                        return;
                    }
                    write(frame(c, h));
                    sentHorizon = h;
                    wrote = true;
                }
                long now = System.nanoTime();
                if (!wrote && (h > sentHorizon
                        && (flush || h - sentHorizon >= delayNs / 4 || now - lastWrite >= FLUSH_NS)
                        || now - lastWrite >= KEEPALIVE_NS)) {
                    write(frame(SerialLinkProtocol.KIND_BEACON, h));
                    sentHorizon = h;
                    wrote = true;
                }
                if (wrote) {
                    lastWrite = now;
                } else {
                    LockSupport.parkNanos(IDLE_PARK_NS);
                }
            }
        } catch (IOException e) {
            fail("the connection dropped: " + describe(e));
        } catch (RuntimeException e) {
            fail("the cable failed: " + e);
        }
    }

    private ByteBuffer frame(int kind, long h) {
        return ByteBuffer.wrap(plain).put((byte) kind).putLong(h);
    }

    private void write(ByteBuffer b) throws IOException {
        int n = cipher.seal(plain, b.position(), sealed);
        channel.send(SerialLinkProtocol.MSG_SEALED, sealed, n);
    }

    private void readLoop() {
        try {
            while (open) {
                ControlChannel.Message m = channel.receive();
                if (m.type() != SerialLinkProtocol.MSG_SEALED) {
                    throw new ProtocolException("unexpected message " + m.type());
                }
                ByteBuffer b = open(cipher, m, opened);
                int kind = b.get() & 0xFF;
                long h = b.getLong();
                LinkCore c = core;
                switch (kind) {
                    case SerialLinkProtocol.KIND_EVENTS -> {
                        int n = b.get() & 0xFF;
                        if (n > SerialLinkProtocol.MAX_EVENTS_PER_FRAME
                                || b.remaining() != n * LineEvent.ENCODED_SIZE) {
                            throw new ProtocolException("malformed EVENTS frame");
                        }
                        for (int i = 0; i < n; i++) c.receive(LineEvent.decode(b));
                    }
                    case SerialLinkProtocol.KIND_BEACON -> { }
                    case SerialLinkProtocol.KIND_PAUSE, SerialLinkProtocol.KIND_RESUME -> {
                        Listener l = listener;
                        if (l != null) l.peerPaused(kind == SerialLinkProtocol.KIND_PAUSE);
                    }
                    case SerialLinkProtocol.KIND_BYE -> {
                        int reason = b.hasRemaining() ? b.get() & 0xFF : 0;
                        fail(reason == SerialLinkProtocol.BYE_RESYNC
                                ? "the other machine changed state: the cable was unplugged"
                                : "the other end unplugged the cable");
                        return;
                    }
                    default -> throw new ProtocolException("unknown frame " + kind);
                }
                // The events of the frame are in the inbox: now the horizon may move.
                if (h < 0) throw new ProtocolException("negative horizon");
                c.receiveHorizon(h);
                Listener l = listener;
                if (l != null) l.horizonMoved();
            }
        } catch (IllegalArgumentException e) {
            fail("the other end sent something that is not a line event");
        } catch (java.net.SocketTimeoutException e) {
            fail(open ? "the other end stopped answering" : null);
        } catch (IOException e) {
            fail(open ? "the connection dropped: " + describe(e) : null);
        } catch (RuntimeException e) {
            fail("the cable failed: " + e);
        }
    }

    private static String describe(IOException e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m;
    }

    private void fail(String reason) {
        boolean wasOpen;
        synchronized (this) {
            wasOpen = open;
            open = false;
            if (closeReason == null) closeReason = reason;
        }
        channel.close();
        Listener l = listener;
        if (wasOpen && l != null && reason != null) l.closed(reason);
    }

    /** Unplugs the cable, telling the other end why. */
    public void close(int reason) {
        if (!open) return;
        byeReason = reason;
        controls.add(SerialLinkProtocol.KIND_BYE);
        Thread w = writer;
        LockSupport.unpark(w);
        try {
            if (w != null && w != Thread.currentThread()) w.join(CLOSE_WAIT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        open = false;
        channel.close();
    }

    @Override
    public void close() {
        close(SerialLinkProtocol.BYE_CLOSED);
    }

    @Override
    public String toString() {
        return "TcpLink[delay " + delayNs / 1000 + " us, rtt " + rttNs / 1000 + " us, peer " + peerHz + " Hz]";
    }
}
