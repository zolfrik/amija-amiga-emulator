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

package io.github.zolfrik.amija.net.nat;

import io.github.zolfrik.amija.net.upnp.PortMapper;

import java.net.URI;
import java.util.List;

/**
 * Opens the host's port on the router by whatever means the router understands
 * ({@code docs/remote-play-r1-plan.md} section 3).
 *
 * <p>UPnP and PCP (with NAT-PMP behind it) start together; the first that maps wins and the
 * other is closed, which also withdraws whatever it had mapped. Until one wins the state is the
 * most hopeful of the two; when both have given up it is the most telling failure: a
 * carrier-grade NAT before a refusal, a refusal before silence.
 */
public final class PortMapping implements AutoCloseable {

    /** How far the opening has got; the same meaning as the UPnP states. */
    public enum State { OFF, SEARCHING, MAPPED, NO_GATEWAY, REFUSED, CARRIER_NAT }

    /** One way to open the port. */
    interface Backend {
        String name();

        void start();

        State state();

        String detail();

        String externalIp();

        /** External port for TCP or UDP, 0 if not mapped. */
        int externalPort(boolean tcp);

        void close();
    }

    private final int port;
    private final List<Backend> backends;
    private final UpnpBackend upnp;
    private volatile Backend winner;
    private volatile Runnable onMapped;
    private volatile boolean closed;
    private Thread referee;

    /**
     * @param port       the host's port, TCP and UDP
     * @param udp        whether UDP is wanted too
     * @param remembered description URL of the UPnP router found in an earlier session, or
     *                   {@code null}
     */
    public PortMapping(int port, boolean udp, URI remembered) {
        this.port = port;
        this.upnp = new UpnpBackend(new PortMapper(port, udp, remembered), port);
        this.backends = List.of(upnp, new PcpMapper(port, udp));
    }

    /** For tests: explicit backends. */
    PortMapping(int port, List<Backend> backends) {
        this.port = port;
        this.upnp = null;
        this.backends = List.copyOf(backends);
    }

    /** Called once, on the referee's thread, when a backend has mapped the port. */
    public void setOnMapped(Runnable listener) {
        this.onMapped = listener;
    }

    /** Starts every backend and the referee that keeps the first to map. Returns at once. */
    public synchronized void open() {
        if (referee != null || closed) {
            return;
        }
        for (Backend b : backends) {
            b.start();
        }
        referee = new Thread(this::referee, "amija-remoteplay-portmapping");
        referee.setDaemon(true);
        referee.start();
    }

    private void referee() {
        while (!closed) {
            boolean anySearching = false;
            for (Backend b : backends) {
                State s = b.state();
                if (s == State.MAPPED) {
                    winner = b;
                    for (Backend other : backends) {
                        if (other != b) {
                            other.close();
                        }
                    }
                    Runnable listener = onMapped;
                    if (listener != null) {
                        listener.run();
                    }
                    return;
                }
                anySearching |= s == State.SEARCHING;
            }
            if (!anySearching) {
                return;   // everyone gave up: state() reports the most telling failure
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** Withdraws every mapping. Must always be called when hosting stops. */
    @Override
    public void close() {
        synchronized (this) {
            closed = true;
        }
        Thread r = referee;
        if (r != null) {
            r.interrupt();
            try {
                r.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        for (Backend b : backends) {
            b.close();
        }
    }

    public State state() {
        Backend w = winner;
        if (w != null) {
            return w.state();
        }
        if (closed) {
            return State.OFF;
        }
        State best = State.OFF;
        for (Backend b : backends) {
            best = rank(b.state()) > rank(best) ? b.state() : best;
        }
        return best;
    }

    private static int rank(State s) {
        return switch (s) {
            case OFF -> 0;
            case NO_GATEWAY -> 1;
            case REFUSED -> 2;
            case CARRIER_NAT -> 3;
            case SEARCHING -> 4;
            case MAPPED -> 5;
        };
    }

    /** The backend whose state {@link #state()} reports. */
    private Backend shown() {
        Backend w = winner;
        if (w != null) {
            return w;
        }
        State s = state();
        for (Backend b : backends) {
            if (b.state() == s) {
                return b;
            }
        }
        return backends.get(0);
    }

    /** "UPnP", "PCP" or "NAT-PMP": who opened the port, or who reports the outcome. */
    public String method() {
        return shown().name();
    }

    public String detail() {
        return shown().detail();
    }

    /** The router's public address, even when it is not reachable (CGNAT); may be null. */
    public String externalIp() {
        for (Backend b : backends) {
            if (b.state() == State.CARRIER_NAT || b.state() == State.MAPPED) {
                return b.externalIp();
            }
        }
        return shown().externalIp();
    }

    /** External TCP port once mapped, else 0. */
    public int externalTcpPort() {
        Backend w = winner;
        return w == null || w.state() != State.MAPPED ? 0 : w.externalPort(true);
    }

    /** External UDP port once mapped, else 0. */
    public int externalUdpPort() {
        Backend w = winner;
        return w == null || w.state() != State.MAPPED ? 0 : w.externalPort(false);
    }

    /** {@code ip:tcpPort} once mapped, else {@code null}. */
    public String externalAddress() {
        String ip = externalIp();
        int tcp = externalTcpPort();
        return ip == null || tcp == 0 ? null : ip + ":" + tcp;
    }

    /** True while UPnP waits for a router that only announces itself. */
    public boolean awaitingAnnouncement() {
        return upnp != null && winner == null && upnp.mapper.awaitingAnnouncement();
    }

    /** The UPnP router's description URL found this time, worth remembering; or null. */
    public URI foundUpnpDescription() {
        return upnp == null ? null : upnp.mapper.foundDescription();
    }

    public int port() {
        return port;
    }

    /** The UPnP path as a backend. UPnP always asks for the same external port. */
    private static final class UpnpBackend implements Backend {
        final PortMapper mapper;
        final int port;

        UpnpBackend(PortMapper mapper, int port) {
            this.mapper = mapper;
            this.port = port;
        }

        @Override
        public String name() {
            return "UPnP";
        }

        @Override
        public void start() {
            mapper.open();
        }

        @Override
        public State state() {
            return switch (mapper.state()) {
                case OFF -> State.OFF;
                case SEARCHING -> State.SEARCHING;
                case MAPPED -> State.MAPPED;
                case NO_GATEWAY -> State.NO_GATEWAY;
                case REFUSED -> State.REFUSED;
                case CARRIER_NAT -> State.CARRIER_NAT;
            };
        }

        @Override
        public String detail() {
            return mapper.detail();
        }

        @Override
        public String externalIp() {
            return mapper.externalIp();
        }

        @Override
        public int externalPort(boolean tcp) {
            return mapper.state() == PortMapper.State.MAPPED ? port : 0;
        }

        @Override
        public void close() {
            mapper.close();
        }
    }
}
