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
import io.github.zolfrik.amija.custom.AbstractAgnus;
import io.github.zolfrik.amija.i18n.Messages;
import io.github.zolfrik.amija.net.JoinCode;

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

    private final BooleanSupplier pacerTaken;

    private volatile AbstractAgnus agnus;
    private volatile MasterClock clock;
    private long hz;
    private boolean loopback;

    private volatile ServerSocket server;
    private volatile String joinCode;
    private volatile boolean lanOnly;
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

    /** Sets what the next session uses: the delay to force in milliseconds (0 = measured) and the LAN rule. */
    public void configure(int delayMs, boolean lanOnly) {
        this.forcedDelayNs = Math.max(0, delayMs) * 1_000_000L;
        this.lanOnly = lanOnly;
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
        return s == null ? null : localAddress() + ":" + s.getLocalPort();
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

    /** Starts listening on {@code port}; see {@link SerialControl#startSerialHost(int, boolean)}. */
    public synchronized String startHost(int port) {
        if (server != null) return null;
        if (agnus == null) return Messages.t("msg.serial.nomachine");
        try {
            ServerSocket s = new ServerSocket();
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(port), 2);
            joinCode = JoinCode.generate();
            server = s;
            message = null;
            Thread t = new Thread(() -> acceptLoop(s), "AmiJa-serial-accept");
            t.setDaemon(true);
            t.start();
            return null;
        } catch (IOException e) {
            return Messages.t("msg.serial.hostfailed", port, String.valueOf(e.getMessage()));
        }
    }

    @Override
    public synchronized String startSerialHost(int port, boolean lanOnly) {
        if (server == null) this.lanOnly = lanOnly;
        return startHost(port);
    }

    private void acceptLoop(ServerSocket s) {
        while (!s.isClosed()) {
            Socket socket;
            try {
                socket = s.accept();
            } catch (IOException e) {
                return;
            }
            if (link != null || pendingLink != null) {
                closeQuietly(socket);   // one cable, one other end
                continue;
            }
            if (lanOnly && !isLocal(socket.getInetAddress())) {
                closeQuietly(socket);
                message = Messages.t("msg.serial.refused.lan", socket.getInetAddress().getHostAddress());
                continue;
            }
            try {
                TcpLink l = TcpLink.host(socket, joinCode, hz, forcedDelayNs);
                pendingIsHost = true;
                pendingLink = l;
            } catch (IOException e) {
                message = Messages.t("msg.serial.refused", String.valueOf(e.getMessage()));
            }
        }
    }

    @Override
    public synchronized String joinSerial(String address, int port, String code) {
        if (agnus == null) return Messages.t("msg.serial.nomachine");
        if (link != null || pendingLink != null || joining) return Messages.t("msg.serial.busy");
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

    @Override
    public synchronized void stopSerial() {
        ServerSocket s = server;
        server = null;
        joinCode = null;
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

    private static boolean isLocal(InetAddress a) {
        return a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress();
    }

    private static String localAddress() {
        try (java.net.DatagramSocket probe = new java.net.DatagramSocket()) {
            probe.connect(InetAddress.getByName("8.8.8.8"), 53);
            return probe.getLocalAddress().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }
}
