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

import io.github.zolfrik.amija.common.ExternalPacer;
import io.github.zolfrik.amija.common.MasterClock;
import io.github.zolfrik.amija.hardware.custom.AbstractAgnus;
import io.github.zolfrik.amija.i18n.Messages;
import io.github.zolfrik.amija.net.AuthThrottle;
import io.github.zolfrik.amija.net.CandidateDialer;
import io.github.zolfrik.amija.net.InviteCode;
import io.github.zolfrik.amija.net.JoinCode;
import io.github.zolfrik.amija.net.nat.HostExposure;
import io.github.zolfrik.amija.net.nat.LocalAddresses;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.function.BooleanSupplier;

/**
 * The serial port of the running machine and what is plugged into it: nothing, a loopback
 * plug, or a null modem cable to another instance.
 *
 * <p>The network side lives on its own threads; the machine is touched only on the
 * emulation thread, from {@link #allowance}, which the clock calls before every batch. There
 * a link that has just been established is plugged in, what arrived is taken in, the horizon
 * of this machine is declared, and the batch is cut at the window: a machine that reaches it
 * is held until the other end has caught up.
 *
 * <p>What a machine does while a cable is plugged in must be continuous in time. Loading a
 * saved state, rewinding or rebuilding the machine breaks that, so the cable is unplugged and
 * has to be joined again: the program sees a cable pulled out and put back.
 */
public final class SerialPortService implements SerialControl, MasterClock.Limiter, ExternalPacer, AutoCloseable {

    /** Master cycles kept clear of the window: a batch may overrun by an instruction. */
    private static final int MARGIN_CYCLES = 1024;
    /** The most the joining end bends its speed to stay in the middle of its window. */
    private static final double MAX_TRIM = 0.005;
    private static final int CONNECT_TIMEOUT_MS = 8_000;
    /** Handshakes in flight at once; beyond them the newcomer is refused at once. */
    private static final int HANDSHAKE_THREADS = 3;
    /** Overall limit of one handshake, the measure of the round trip included. */
    private static final int HANDSHAKE_BUDGET_MS = 15_000;
    /** How fast the join code may be got wrong, per address. */
    private final AuthThrottle throttle = new AuthThrottle();

    /** Ports tried from the preferred one upward before taking any free one. */
    private static final int PORT_ATTEMPTS = 10;

    private final BooleanSupplier pacerTaken;

    private volatile AbstractAgnus agnus;
    private volatile MasterClock clock;
    private long hz;
    private boolean loopback;

    private volatile ServerSocket server;
    private volatile String joinCode;
    /** Whether the listening end accepts peers from the Internet too. */
    private volatile boolean internet;
    /** The exposure settings for a given choice of Internet; null = local network defaults. */
    private volatile java.util.function.Function<Boolean, HostExposure.Options> exposureOptions;
    private volatile HostExposure exposure;
    private volatile HostExposure closingExposure;
    private volatile long forcedDelayNs;
    private volatile boolean joining;
    private volatile String joiningAddress;

    /** Established and waiting for the emulation thread to plug it in. */
    private volatile TcpLink pendingLink;
    private volatile boolean pendingIsHost;
    private volatile TcpLink link;
    private LinkCore core;
    private boolean guestSide;
    private boolean ownPacer;
    private volatile boolean held;
    private volatile boolean peerPaused;
    private volatile double speed = 1.0;
    private volatile long holds;
    private volatile long heldNanos;
    private long heldSince;
    private boolean episode;
    private long quarterWindow;
    private volatile String message;
    /** Why the link ended, set by its threads and read by the emulation thread. */
    private volatile String closedReason;

    /** @param pacerTaken true while something else paces the clock: the port then leaves the speed alone */
    public SerialPortService(BooleanSupplier pacerTaken) {
        this.pacerTaken = pacerTaken;
    }

    // =====================================================================
    // The machine
    // =====================================================================

    /**
     * A machine has been built. To be called before its clock starts.
     *
     * @param loopback whether a loopback plug goes into the port
     */
    public synchronized void bind(AbstractAgnus agnus, MasterClock clock, long hz, boolean loopback) {
        this.agnus = agnus;
        this.clock = clock;
        this.hz = hz;
        this.loopback = loopback;
        if (loopback) agnus.connectSerialLoopback();
        clock.setLimiter(this);
    }

    /** The machine is going away. A cable to another machine is unplugged. */
    public synchronized void unbind() {
        dropLink(SerialLinkProtocol.BYE_RESYNC, Messages.t("msg.serial.unplugged.machine"));
        MasterClock c = clock;
        if (c != null) {
            c.setLimiter(null);
            if (ownPacer) c.setPacer(null);
        }
        ownPacer = false;
        agnus = null;
        clock = null;
    }

    /** The state of the machine was replaced (a saved state, a rewind): time is no longer continuous. */
    public synchronized void stateReplaced() {
        dropLink(SerialLinkProtocol.BYE_RESYNC, Messages.t("msg.serial.unplugged.state"));
    }

    /** The user paused or resumed the machine. */
    public void notifyPaused(boolean paused) {
        TcpLink l = link;
        if (l != null && l.isOpen()) l.sendPaused(paused);
    }

    private void dropLink(int bye, String why) {
        TcpLink p = pendingLink;
        pendingLink = null;
        if (p != null) p.close(bye);
        TcpLink l = link;
        if (l != null) {
            link = null;
            l.close(bye);
            message = why;
            AbstractAgnus a = agnus;
            if (a != null) a.setSerialCable(null);
            releasePacer();
        }
        held = false;
        core = null;
    }

    private void releasePacer() {
        MasterClock c = clock;
        if (ownPacer && c != null) c.setPacer(null);
        ownPacer = false;
        speed = 1.0;
    }

    // =====================================================================
    // The emulation thread
    // =====================================================================

    @Override
    public int allowance(int wanted) {
        TcpLink p = pendingLink;
        if (p != null) {
            pendingLink = null;
            plug(p);
        }
        TcpLink l = link;
        if (l == null) return wanted;
        AbstractAgnus a = agnus;
        if (!l.isOpen()) {
            // The other end went away: the cable is out, the machine carries on.
            link = null;
            core = null;
            held = false;
            a.setSerialCable(null);
            releasePacer();
            String why = closedReason;
            message = why != null ? why : Messages.t("msg.serial.lost");
            return wanted;
        }
        long now = a.getMasterClock();
        if (core.drain(now)) a.getPaula().serialLineChanged();
        core.advance(now);
        long room = core.limitCycle() - now - MARGIN_CYCLES;
        if (guestSide) trim(room);
        if (room <= 0) {
            long t = System.nanoTime();
            if (!held) heldSince = t;
            if (!episode) {
                episode = true;
                holds++;
            }
            held = true;
            // The other machine may be stopped at its own window, waiting for this horizon.
            l.flushHorizon();
            return 0;
        }
        if (held) {
            heldNanos += System.nanoTime() - heldSince;
            held = false;
        }
        // One stop, not one for every short step taken at the edge of the window: the
        // episode is over when a quarter of the window is free again.
        if (episode && room >= quarterWindow) episode = false;
        return (int) Math.min(wanted, room);
    }

    /**
     * The machine had to run past its window (taken to the start of a frame while the other
     * end did not move): what it saw of the cable is no longer what the other end sent, so
     * the cable is pulled out.
     */
    @Override
    public synchronized void overrun() {
        if (link != null) {
            dropLink(SerialLinkProtocol.BYE_RESYNC, Messages.t("msg.serial.unplugged.overrun"));
        }
    }

    /**
     * The joining end keeps itself in the middle of its window by bending its speed by at
     * most half a per cent: ahead of the middle it slows down, behind it it speeds up. The
     * window is what keeps the two machines right; this only keeps them off its edge.
     */
    private void trim(long roomCycles) {
        long delayCycles = core.clock().cycleAt(core.delay()) - core.clock().cycleAt(0);
        double error = (roomCycles - delayCycles / 2.0) / delayCycles;
        speed = 1.0 + Math.max(-MAX_TRIM, Math.min(MAX_TRIM, error * 0.02));
    }

    private void plug(TcpLink l) {
        AbstractAgnus a = agnus;
        if (a == null) {
            l.close(SerialLinkProtocol.BYE_CLOSED);
            return;
        }
        core = new LinkCore(new LineClock(hz, a.getMasterClock()), l.delayNs(), l.peerHz());
        guestSide = !pendingIsHost;
        closedReason = null;
        peerPaused = false;
        holds = 0;
        heldNanos = 0;
        episode = false;
        quarterWindow = (core.clock().cycleAt(core.delay()) - core.clock().cycleAt(0)) / 4;
        l.start(core, new TcpLink.Listener() {
            @Override public void closed(String reason) { closedReason = reason; }
            @Override public void peerPaused(boolean paused) { peerPaused = paused; }
            // Unconditional: a horizon that lands between the drain and the hold must still
            // end the wait; a wake while the machine runs only costs it one more look.
            @Override public void horizonMoved() {
                MasterClock c = clock;
                if (c != null) c.wakeHeld();
            }
        });
        a.setSerialCable(new NullModemCable(core, a::getMasterClock));
        link = l;
        message = null;
        MasterClock c = clock;
        if (guestSide && c != null && !pacerTaken.getAsBoolean()) {
            c.setPacer(this);
            ownPacer = true;
        }
    }

    /** Wall time this machine spent stopped at its window since the cable was plugged in, in nanoseconds. */
    public long heldNanos() { return heldNanos; }

    /** Times this machine had to stop at its window since the cable was plugged in. */
    public long holds() { return holds; }

    /** Events that took effect late since the cable was plugged in: always zero unless the window was overrun. */
    public long lateEvents() {
        LinkCore c = core;
        return c == null ? 0 : c.lateEvents();
    }

    @Override
    public double speed() {
        return speed;
    }

    // =====================================================================
    // The popup
    // =====================================================================

    /**
     * Sets what the next session uses: the delay to force in milliseconds (0 = measured), and
     * how the listening end is exposed for a given choice of Internet (router mapping of TCP
     * only, STUN, IPv6, firewall), the same settings as the stream host.
     *
     * @param exposure {@code null} for a local-network host with no extras
     */
    public void configure(int delayMs,
                          java.util.function.Function<Boolean, HostExposure.Options> exposure) {
        this.forcedDelayNs = Math.max(0, delayMs) * 1_000_000L;
        this.exposureOptions = exposure;
    }

    /** Only the delay; the listening end stays on the local network. */
    public void configure(int delayMs) {
        configure(delayMs, null);
    }

    @Override
    public int serialLedState() {
        if (link != null) return held ? LED_HELD : LED_LINKED;
        return server != null || joining || pendingLink != null ? LED_WAITING : LED_OFF;
    }

    @Override public boolean isSerialHosting() { return server != null; }
    @Override public boolean isSerialLinked() { return link != null || pendingLink != null; }
    @Override public boolean isSerialJoining() { return joining; }
    @Override public String serialJoinCode() { return server == null ? null : joinCode; }

    @Override
    public String serialLanAddress() {
        ServerSocket s = server;
        HostExposure e = exposure;
        return s == null ? null
                : (e != null ? e.lanAddress() : localAddress()) + ":" + s.getLocalPort();
    }

    @Override
    public String serialInvite() {
        ServerSocket s = server;
        HostExposure e = exposure;
        String code = joinCode;
        if (s == null || e == null || code == null) {
            return null;
        }
        java.util.List<InviteCode.Candidate> c = e.candidates();
        return c.isEmpty() ? null : new InviteCode(InviteCode.ROLE_NULL_MODEM, code, c).format();
    }

    @Override
    public boolean serialInvitePending() {
        HostExposure e = exposure;
        return server != null && e != null && e.portPending();
    }

    @Override
    public String serialOpenedPort() {
        HostExposure e = exposure;
        return server == null || e == null ? null : e.openedPort();
    }

    @Override
    public String serialReachability() {
        HostExposure e = exposure;
        return server == null || e == null ? null : e.reachability();
    }

    /** Waits for the router mapping to be withdrawn; only for the shutdown hook. */
    public void awaitExposureClosed(long millis) {
        HostExposure e = closingExposure;
        if (e != null) {
            e.awaitClosed(millis);
        }
    }

    @Override
    public String serialStatus() {
        TcpLink l = link;
        if (l != null) {
            String key = peerPaused ? "msg.serial.peerpaused" : held ? "msg.serial.held" : "msg.serial.linked";
            return Messages.t(key, ms(l.delayNs()), ms(l.rttNs()), holds, ms(heldNanos));
        }
        if (pendingLink != null) return Messages.t("msg.serial.pending");
        if (joining) return Messages.t("msg.serial.joining", joiningAddress);
        String m = message;
        if (server != null) {
            return m != null ? m + " " + Messages.t("msg.serial.waiting") : Messages.t("msg.serial.waiting");
        }
        if (m != null) return m;
        return Messages.t(loopback ? "msg.serial.loopback" : "msg.serial.off");
    }

    /**
     * Starts listening on {@code port}, or on one of the next few when it is taken, or on any
     * free one: the invitation carries the port. 0 asks for a free one at once.
     */
    public synchronized String startHost(int port) {
        if (server != null) return null;
        if (agnus == null) return Messages.t("msg.serial.nomachine");
        try {
            ServerSocket s = bindPreferred(port);
            joinCode = JoinCode.generate();
            server = s;
            message = null;
            java.util.function.Function<Boolean, HostExposure.Options> opts = exposureOptions;
            HostExposure e = new HostExposure(s.getLocalPort(), opts != null
                    ? opts.apply(internet)
                    : new HostExposure.Options(false, false, "", false, java.util.List.of(),
                            null, null));
            exposure = e;
            e.start();
            Thread t = new Thread(() -> acceptLoop(s), "AmiJa-serial-accept");
            t.setDaemon(true);
            t.start();
            return null;
        } catch (IOException e) {
            return Messages.t("msg.serial.hostfailed", port, String.valueOf(e.getMessage()));
        }
    }

    @Override
    public synchronized String startSerialHost(int port, boolean internet) {
        if (server == null) this.internet = internet;
        return startHost(port);
    }

    private static ServerSocket bindPreferred(int preferred) throws IOException {
        IOException first = null;
        if (preferred > 0) {
            for (int i = 0; i < PORT_ATTEMPTS && preferred + i <= 65535; i++) {
                try {
                    return bind(preferred + i);
                } catch (IOException e) {
                    if (first == null) first = e;
                }
            }
        }
        try {
            return bind(0);
        } catch (IOException e) {
            if (first != null) e.addSuppressed(first);
            throw e;
        }
    }

    private static ServerSocket bind(int port) throws IOException {
        ServerSocket s = new ServerSocket();
        try {
            s.bind(new InetSocketAddress(port), 8);
            return s;
        } catch (IOException e) {
            s.close();
            throw e;
        }
    }

    /**
     * Accepts connections and hands each to a handshake thread of its own.
     *
     * <p>The same defences as the stream host, which this end needs as soon as it can be
     * reached from the Internet: the accepting thread never waits for a peer (a silent one
     * would otherwise hold the door for the whole handshake timeout), at most
     * {@value #HANDSHAKE_THREADS} handshakes run at once, each within
     * {@value #HANDSHAKE_BUDGET_MS} ms, and a peer that gets the code wrong too often is
     * refused before the key derivation ({@link AuthThrottle}, per address, per /64 for IPv6).
     * A connection closed before sending a byte is not an attempt at the code.
     */
    private void acceptLoop(ServerSocket s) {
        java.util.concurrent.ThreadPoolExecutor handshakes =
                new java.util.concurrent.ThreadPoolExecutor(0, HANDSHAKE_THREADS, 30L,
                        java.util.concurrent.TimeUnit.SECONDS,
                        new java.util.concurrent.SynchronousQueue<>(), r -> {
                            Thread t = new Thread(r, "AmiJa-serial-handshake");
                            t.setDaemon(true);
                            return t;
                        });
        try {
            while (!s.isClosed()) {
                Socket socket;
                try {
                    socket = s.accept();
                } catch (IOException e) {
                    return;
                }
                InetAddress peer = socket.getInetAddress();
                if (link != null || pendingLink != null) {
                    closeQuietly(socket);   // one cable, one other end
                    continue;
                }
                if (!internet && !LocalAddresses.isLocalNetwork(peer)) {
                    closeQuietly(socket);
                    message = Messages.t("msg.serial.refused.lan", peer.getHostAddress());
                    continue;
                }
                if (throttle.retryAfterMillis(peer) > 0) {
                    closeQuietly(socket);   // too many wrong codes: no derivation for it
                    continue;
                }
                String code = joinCode;
                if (code == null) {
                    closeQuietly(socket);   // hosting is stopping
                    continue;
                }
                try {
                    handshakes.execute(() -> handshake(socket, peer, code));
                } catch (java.util.concurrent.RejectedExecutionException e) {
                    closeQuietly(socket);   // every handshake thread busy: refused at once
                }
            }
        } finally {
            handshakes.shutdownNow();
        }
    }

    /** One handshake, on its own thread, closed by a deadline if it does not complete. */
    private void handshake(Socket socket, InetAddress peer, String code) {
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        Thread deadline = new Thread(() -> {
            try {
                Thread.sleep(HANDSHAKE_BUDGET_MS);
            } catch (InterruptedException e) {
                return;
            }
            if (done.compareAndSet(false, true)) {
                closeQuietly(socket);   // wakes the blocked read with an exception
            }
        }, "AmiJa-serial-handshake-deadline");
        deadline.setDaemon(true);
        deadline.start();
        try {
            TcpLink l = TcpLink.host(socket, code, hz, forcedDelayNs);
            if (!done.compareAndSet(false, true)) {
                l.close();
                return;
            }
            deadline.interrupt();
            throttle.recordSuccess(peer);
            synchronized (this) {
                if (link != null || pendingLink != null || server == null) {
                    l.close();   // another handshake got there first, or hosting stopped
                    return;
                }
                pendingIsHost = true;
                pendingLink = l;
            }
        } catch (io.github.zolfrik.amija.net.Handshake.ClosedBeforeHelloException e) {
            done.set(true);
            deadline.interrupt();
        } catch (IOException e) {
            done.set(true);
            deadline.interrupt();
            throttle.recordFailure(peer);
            message = Messages.t("msg.serial.refused", String.valueOf(e.getMessage()));
        }
    }

    @Override
    public synchronized String joinSerial(String address, int port, String code) {
        if (agnus == null) return Messages.t("msg.serial.nomachine");
        if (link != null || pendingLink != null || joining) return Messages.t("msg.serial.busy");
        if (InviteCode.looksLikeInvite(address)) {
            return joinInvite(address);
        }
        String normalised = JoinCode.normalise(code);
        if (address == null || address.isBlank() || !JoinCode.isWellFormed(normalised)) {
            return Messages.t("msg.serial.badinput");
        }
        joining = true;
        joiningAddress = address.trim() + ":" + port;
        message = null;
        final long myHz = hz;
        Thread t = new Thread(() -> {
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(address.trim(), port), CONNECT_TIMEOUT_MS);
                TcpLink l = TcpLink.join(socket, normalised, myHz, forcedDelayNs);
                pendingIsHost = false;
                pendingLink = l;
            } catch (IOException e) {
                closeQuietly(socket);
                message = Messages.t("msg.serial.joinfailed", joiningAddress, String.valueOf(e.getMessage()));
            } finally {
                joining = false;
            }
        }, "AmiJa-serial-join");
        t.setDaemon(true);
        t.start();
        return null;
    }

    /** Joins through an invitation: every address it carries is tried, the first that answers wins. */
    private String joinInvite(String text) {
        InviteCode invite;
        try {
            invite = InviteCode.parse(text);
        } catch (InviteCode.InvalidInviteException e) {
            return Messages.t(switch (e.problem()) {
                case CORRUPTED -> "msg.remoteplay.invite.corrupted";
                case VERSION -> "msg.remoteplay.invite.version";
                case MALFORMED -> "msg.remoteplay.invite.malformed";
            });
        }
        if (invite.role() != InviteCode.ROLE_NULL_MODEM) {
            return Messages.t("msg.serial.invite.stream");
        }
        joining = true;
        joiningAddress = Messages.t("msg.remoteplay.invite.target");
        message = null;
        final long myHz = hz;
        Thread t = new Thread(() -> {
            try {
                TcpLink l = CandidateDialer.connect(invite.candidates(), CONNECT_TIMEOUT_MS,
                        (s, c) -> TcpLink.join(s, invite.joinCode(), myHz, forcedDelayNs));
                pendingIsHost = false;
                pendingLink = l;
            } catch (CandidateDialer.AllFailedException e) {
                StringBuilder sb = new StringBuilder();
                for (CandidateDialer.Attempt a : e.attempts()) {
                    if (sb.length() > 0) sb.append("; ");
                    sb.append(a.candidate().endpoint()).append(' ')
                            .append(String.valueOf(a.failure().getMessage()));
                }
                message = Messages.t("msg.serial.joinfailed", joiningAddress, sb.toString());
            } catch (IOException e) {
                message = Messages.t("msg.serial.joinfailed", joiningAddress,
                        String.valueOf(e.getMessage()));
            } finally {
                joining = false;
            }
        }, "AmiJa-serial-join");
        t.setDaemon(true);
        t.start();
        return null;
    }

    @Override
    public synchronized void stopSerial() {
        ServerSocket s = server;
        server = null;
        joinCode = null;
        HostExposure e = exposure;
        exposure = null;
        if (e != null) {
            e.close();
            closingExposure = e;
        }
        if (s != null) closeQuietly(s);
        dropLink(SerialLinkProtocol.BYE_CLOSED, null);
        message = null;
    }

    @Override
    public void close() {
        stopSerial();
    }

    /** Nanoseconds as milliseconds with one decimal. */
    private static String ms(long ns) {
        return String.format(java.util.Locale.ROOT, "%.1f", ns / 1_000_000.0);
    }

    private static void closeQuietly(java.io.Closeable c) {
        try {
            c.close();
        } catch (IOException ignored) {
            // closing
        }
    }

    private static String localAddress() {
        return io.github.zolfrik.amija.net.nat.LocalAddresses.primaryIpv4Text();
    }
}
