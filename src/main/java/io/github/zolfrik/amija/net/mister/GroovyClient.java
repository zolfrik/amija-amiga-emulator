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

package io.github.zolfrik.amija.net.mister;

import io.github.zolfrik.amija.net.mister.GroovyProtocol.Ack;
import io.github.zolfrik.amija.net.mister.GroovyProtocol.AudioRate;
import io.github.zolfrik.amija.net.mister.GroovyProtocol.BlitKind;
import io.github.zolfrik.amija.net.mister.GroovyProtocol.RgbMode;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.PortUnreachableException;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.concurrent.TimeUnit;

/**
 * Client side of the Groovy_MiSTer protocol: one UDP channel to the MiSTer's command port.
 *
 * <p>The channel is connected to the MiSTer, so datagrams from anyone else are discarded by
 * the operating system. It may be bound to a local address, which matters when the MiSTer
 * is reachable through two interfaces (a direct cable and Wi-Fi): binding to the cable's
 * address keeps the stream off the wireless link.
 *
 * <p>Threading: the send methods are meant for one sender thread; {@link #pollAck()} and
 * {@link #latestAck()} may be called from any thread.
 */
public final class GroovyClient implements Closeable {

    /** How long {@link #open} waits for the MiSTer to acknowledge {@code CMD_INIT}. */
    public static final long DEFAULT_INIT_TIMEOUT_MS = 60;

    private static final int SEND_BUFFER = 2 << 20;

    private final DatagramChannel channel;
    private final Selector selector;
    private final int chunk;
    private final byte[] header = new byte[GroovyProtocol.BLIT_LZ4_DELTA_LENGTH];
    private final ByteBuffer headerBuf = ByteBuffer.wrap(header);
    private final ByteBuffer recvBuf = ByteBuffer.allocate(64);
    private final Object recvLock = new Object();

    private ByteBuffer payloadView = ByteBuffer.allocate(0);

    private volatile Ack latest;
    private volatile AckAt latestAt;
    private volatile AckAt latestStatusAt;

    /** An ACK and the {@link System#nanoTime()} at which it was read off the socket. */
    public record AckAt(Ack ack, long nanos) {
    }
    private volatile int version = -1;
    private volatile boolean opened;
    private RgbMode rgb = RgbMode.RGB565;
    private boolean lz4;
    private AudioRate audioRate = AudioRate.OFF;
    private GroovyModeline modeline;

    /**
     * @param mister    the MiSTer's address and command port
     * @param localBind local address to send from, or {@code null} to let the system choose
     * @param mtu       link MTU; each data datagram carries {@code mtu - 28} bytes
     */
    public GroovyClient(InetSocketAddress mister, InetAddress localBind, int mtu)
            throws IOException {
        this.chunk = GroovyProtocol.chunkSize(mtu);
        DatagramChannel ch = DatagramChannel.open(StandardProtocolFamily.INET);
        try {
            ch.setOption(StandardSocketOptions.SO_SNDBUF, SEND_BUFFER);
            ch.bind(new InetSocketAddress(localBind, 0));
            ch.connect(mister);
            ch.configureBlocking(false);
            this.selector = Selector.open();
            ch.register(selector, SelectionKey.OP_READ);
        } catch (IOException | RuntimeException e) {
            ch.close();
            throw e;
        }
        this.channel = ch;
    }

    /**
     * The local IPv4 address on the same subnet as {@code target}, or {@code null} when no
     * interface is. With the MiSTer reachable both over a direct cable and over Wi-Fi this
     * picks the interface of whichever address the user named.
     */
    public static InetAddress findLocalAddressFor(InetAddress target) throws IOException {
        byte[] t = target.getAddress();
        if (t.length != 4) {
            return null;
        }
        InetAddress best = null;
        int bestPrefix = -1;
        var ifaces = java.net.NetworkInterface.getNetworkInterfaces();
        while (ifaces != null && ifaces.hasMoreElements()) {
            var ni = ifaces.nextElement();
            if (!ni.isUp() || ni.isLoopback()) {
                continue;
            }
            for (var ia : ni.getInterfaceAddresses()) {
                byte[] a = ia.getAddress().getAddress();
                int prefix = ia.getNetworkPrefixLength();
                if (a.length != 4 || prefix <= 0 || prefix > 32 || prefix <= bestPrefix) {
                    continue;
                }
                int mask = (int) (0xFFFFFFFFL << (32 - prefix));
                int x = (a[0] & 0xFF) << 24 | (a[1] & 0xFF) << 16 | (a[2] & 0xFF) << 8 | a[3] & 0xFF;
                int y = (t[0] & 0xFF) << 24 | (t[1] & 0xFF) << 16 | (t[2] & 0xFF) << 8 | t[3] & 0xFF;
                if ((x & mask) == (y & mask)) {
                    best = ia.getAddress();
                    bestPrefix = prefix;
                }
            }
        }
        return best;
    }

    public InetSocketAddress localAddress() throws IOException {
        return (InetSocketAddress) channel.getLocalAddress();
    }

    /** Asks the core's protocol version; returns -1 when nothing answers in time. */
    public int queryVersion(long timeoutMs) throws IOException {
        version = -1;
        send(new byte[]{GroovyProtocol.CMD_GET_VERSION});
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (version < 0 && waitDatagram(deadline)) {
            // waitDatagram stores the version
        }
        return version;
    }

    /**
     * Sends {@code CMD_INIT} and waits for its acknowledgement.
     *
     * @throws IOException when the MiSTer does not answer within {@code timeoutMs}
     */
    public void open(boolean useLz4, AudioRate audio, int channels, RgbMode rgbMode,
            long timeoutMs) throws IOException {
        this.rgb = rgbMode;
        this.lz4 = useLz4;
        this.audioRate = audio;
        this.modeline = null;
        this.latest = null;
        send(GroovyProtocol.init(useLz4, audio, channels, rgbMode));
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (latest == null) {
            if (!waitDatagram(deadline)) {
                throw new IOException("no answer from the MiSTer within " + timeoutMs + " ms");
            }
        }
        opened = true;
    }

    public boolean isOpen() {
        return opened;
    }

    public RgbMode rgbMode() {
        return rgb;
    }

    public boolean lz4() {
        return lz4;
    }

    public AudioRate audioRate() {
        return audioRate;
    }

    public GroovyModeline modeline() {
        return modeline;
    }

    public void switchres(GroovyModeline m) throws IOException {
        requireOpen();
        send(GroovyProtocol.switchres(m));
        modeline = m;
    }

    /** Sends an uncompressed blit; {@code length} must be the modeline's blit size. */
    public void blitRaw(int frame, int field, int vSync, byte[] data, int offset, int length)
            throws IOException {
        requireMode(false);
        int expected = modeline.blitBytes(rgb);
        if (length != expected) {
            throw new IllegalArgumentException("raw blit of " + length + " bytes, mode needs "
                    + expected);
        }
        sendHeader(BlitKind.RAW, frame, field, vSync, 0);
        sendStream(data, offset, length);
    }

    /** Raw mode only: the MiSTer shows the previous frame of the field again. */
    public void blitDuplicate(int frame, int field, int vSync) throws IOException {
        requireMode(false);
        sendHeader(BlitKind.DUPLICATE, frame, field, vSync, 0);
    }

    /**
     * Sends an LZ4 block that expands to one blit.
     *
     * @param delta the block decodes to the byte-wise difference (mod 256) from the previous
     *              frame of the same field, instead of the frame itself
     */
    public void blitLz4(int frame, int field, int vSync, byte[] block, int offset, int length,
            boolean delta) throws IOException {
        requireMode(true);
        sendHeader(delta ? BlitKind.LZ4_DELTA : BlitKind.LZ4, frame, field, vSync, length);
        sendStream(block, offset, length);
    }

    /** Sends a block of interleaved signed 16-bit little-endian samples. */
    public void audio(byte[] pcm, int offset, int length) throws IOException {
        requireOpen();
        send(GroovyProtocol.audio(length));
        sendStream(pcm, offset, length);
    }

    /** Asks for a status ACK, which {@link #pollAck()} will pick up. */
    public void requestStatus() throws IOException {
        send(new byte[]{GroovyProtocol.CMD_GET_STATUS});
    }

    /**
     * Drains every datagram already received, without waiting.
     *
     * @return the newest ACK seen so far, or {@code null} if none yet
     */
    public Ack pollAck() throws IOException {
        synchronized (recvLock) {
            while (readOne()) {
                // keep draining
            }
        }
        return latest;
    }

    /**
     * Waits until an ACK newer than {@code afterFrame} arrives or the timeout expires.
     *
     * @return the newest ACK, which may still be older if the time ran out
     */
    public Ack awaitAck(int afterFrame, long timeoutMs) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (true) {
            Ack a = pollAck();
            if (a != null && Integer.compareUnsigned(a.frameEcho(), afterFrame) > 0) {
                return a;
            }
            if (!waitDatagram(deadline)) {
                return latest;
            }
        }
    }

    public Ack latestAck() {
        return latest;
    }

    /**
     * The newest answer to {@link #requestStatus()} (an ACK echoing frame 0) with the time it
     * arrived, or {@code null} if none yet.
     */
    public AckAt latestStatusAt() {
        return latestStatusAt;
    }

    /** The newest blit ACK with the time it arrived, or {@code null} if none yet. */
    public AckAt latestAckAt() {
        return latestAt;
    }

    /**
     * Waits up to {@code timeoutMs} for datagrams and reads all of them. Meant for a thread of
     * its own that does nothing else, so each ACK is stamped the moment it arrives.
     *
     * @return false once the client is closed
     */
    public boolean receive(long timeoutMs) throws IOException {
        if (!channel.isOpen()) {
            return false;
        }
        try {
            waitDatagram(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs));
            pollAck();
        } catch (java.nio.channels.ClosedSelectorException
                | java.nio.channels.ClosedChannelException e) {
            return false;
        }
        return channel.isOpen();
    }

    @Override
    public void close() throws IOException {
        try {
            if (opened && channel.isOpen()) {
                send(new byte[]{GroovyProtocol.CMD_CLOSE});
            }
        } catch (IOException ignored) {
            // the link is going away anyway
        } finally {
            opened = false;
            selector.close();
            channel.close();
        }
    }

    // ---------------------------------------------------------------------

    private void requireOpen() {
        if (!opened) {
            throw new IllegalStateException("not opened");
        }
    }

    private void requireMode(boolean wantLz4) {
        requireOpen();
        if (modeline == null) {
            throw new IllegalStateException("no modeline sent");
        }
        if (lz4 != wantLz4) {
            throw new IllegalStateException(lz4 ? "session is LZ4" : "session is raw");
        }
    }

    private void sendHeader(BlitKind kind, int frame, int field, int vSync, int cSize)
            throws IOException {
        int n = GroovyProtocol.blit(header, kind, frame, field, vSync, cSize);
        headerBuf.clear().limit(n);
        writeFully(headerBuf);
    }

    private void send(byte[] datagram) throws IOException {
        writeFully(ByteBuffer.wrap(datagram));
    }

    private void sendStream(byte[] data, int offset, int length) throws IOException {
        if (payloadView.array() != data) {
            payloadView = ByteBuffer.wrap(data);
        }
        int end = offset + length;
        for (int p = offset; p < end; p += chunk) {
            payloadView.limit(Math.min(end, p + chunk)).position(p);
            writeFully(payloadView);
        }
    }

    /** A non-blocking channel may refuse a datagram when the send buffer is full. */
    private void writeFully(ByteBuffer b) throws IOException {
        while (true) {
            try {
                if (channel.write(b) > 0 || !b.hasRemaining()) {
                    return;
                }
            } catch (PortUnreachableException e) {
                // an earlier datagram found no listener; this one is still worth sending
                continue;
            }
            Thread.onSpinWait();
        }
    }

    /** Waits for one datagram until {@code deadlineNs}; false when the time ran out. */
    private boolean waitDatagram(long deadlineNs) throws IOException {
        synchronized (recvLock) {
            while (true) {
                if (readOne()) {
                    return true;
                }
                long left = deadlineNs - System.nanoTime();
                if (left <= 0) {
                    return false;
                }
                selector.select(Math.max(1, TimeUnit.NANOSECONDS.toMillis(left)));
                selector.selectedKeys().clear();
            }
        }
    }

    /** Reads and interprets one datagram; false when none is pending. */
    private boolean readOne() throws IOException {
        recvBuf.clear();
        int n;
        try {
            n = channel.read(recvBuf);
        } catch (PortUnreachableException e) {
            return false;
        }
        if (n <= 0) {
            return false;
        }
        if (n == GroovyProtocol.ACK_LENGTH) {
            long at = System.nanoTime();
            Ack a = Ack.parse(recvBuf.array(), 0, n);
            if (a.frameEcho() == 0) {
                latestStatusAt = new AckAt(a, at);
            }
            Ack cur = latest;
            // INIT and STATUS answers echo frame 0: they never replace a blit's ACK.
            if (cur == null || cur.frameEcho() == 0
                    || Integer.compareUnsigned(a.frameEcho(), cur.frameEcho()) > 0) {
                latest = a;
                if (a.frameEcho() != 0) {
                    latestAt = new AckAt(a, at);
                }
            }
        } else if (n == GroovyProtocol.VERSION_LENGTH) {
            version = recvBuf.get(0) & 0xFF;
        }
        return true;
    }
}
