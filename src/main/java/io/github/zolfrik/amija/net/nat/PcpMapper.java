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

import io.github.zolfrik.amija.net.RemotePlaySettings;
import io.github.zolfrik.amija.net.upnp.NatAddress;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;

/**
 * Opens the port with PCP, or with NAT-PMP when the gateway speaks only that, and keeps it open
 * ({@code docs/remote-play-r1-plan.md} section 3.1).
 *
 * <p>The same rules as the UPnP path: no mapping left behind a carrier-grade NAT (the external
 * address is checked as soon as the gateway states it, and the mapping withdrawn if it is not
 * public), a lease of {@value #LEASE_SECONDS} s renewed at half its granted length, and the
 * withdrawal at closing. The external ports may differ from the internal one and between TCP
 * and UDP: they are reported per protocol.
 */
final class PcpMapper implements PortMapping.Backend {

    static final int LEASE_SECONDS = 3600;

    /** Shortest renewal interval, whatever lifetime the gateway grants. */
    private static final long MIN_RENEW_MS = 30_000;

    private final int port;
    private final boolean udp;
    private final List<GatewayGuess.Candidate> gateways;
    private final int gatewayPort;

    private volatile PortMapping.State state = PortMapping.State.OFF;
    private volatile String detail;
    private volatile String externalIp;
    private volatile int externalTcp;
    private volatile int externalUdp;
    private volatile String protocolName = "PCP";
    private volatile boolean closed;
    private Thread worker;

    /** The protocol in use with the gateway that answered. */
    private interface Session {
        int map(boolean tcp, int suggested, int lifetime) throws IOException;

        String externalAddress() throws IOException;

        int lifetime();
    }

    PcpMapper(int port, boolean udp) {
        this(port, udp, GatewayGuess.candidates(), PcpClient.PORT);
    }

    /** For tests: explicit gateways, and the port they listen on. */
    PcpMapper(int port, boolean udp, List<GatewayGuess.Candidate> gateways, int gatewayPort) {
        this.port = port;
        this.udp = udp;
        this.gateways = gateways;
        this.gatewayPort = gatewayPort;
    }

    @Override
    public String name() {
        return protocolName;
    }

    @Override
    public synchronized void start() {
        if (worker != null || closed) {
            return;
        }
        state = PortMapping.State.SEARCHING;
        worker = new Thread(this::run, "amija-remoteplay-pcp");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public PortMapping.State state() {
        return state;
    }

    @Override
    public String detail() {
        return detail;
    }

    @Override
    public String externalIp() {
        return externalIp;
    }

    @Override
    public int externalPort(boolean tcp) {
        return tcp ? externalTcp : externalUdp;
    }

    @Override
    public void close() {
        Thread pending;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            pending = worker;
            notifyAll();
        }
        if (pending != null) {
            try {
                pending.join(8000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        state = PortMapping.State.OFF;
    }

    // =====================================================================

    private void run() {
        Session session = null;
        for (GatewayGuess.Candidate g : gateways) {
            if (closed) {
                return;
            }
            session = open(g);
            if (session != null || state != PortMapping.State.SEARCHING) {
                break;
            }
        }
        if (session == null) {
            if (state == PortMapping.State.SEARCHING) {
                state = PortMapping.State.NO_GATEWAY;
                detail = "no PCP or NAT-PMP gateway answered";
            }
            return;
        }
        state = PortMapping.State.MAPPED;
        RemotePlaySettings.say("%s: port %d open, external %s TCP %d%s", protocolName, port,
                externalIp, externalTcp, udp ? " UDP " + externalUdp : "");
        renewLoop(session);
        withdraw(session);
    }

    /**
     * Maps on one gateway: PCP first, NAT-PMP if the gateway answers with the older version.
     * {@code null} when this gateway does not answer (the next one is tried) or refuses (the
     * state says so and nothing else is tried).
     */
    private Session open(GatewayGuess.Candidate g) {
        InetSocketAddress at = new InetSocketAddress(g.gateway(), gatewayPort);
        PcpClient pcp = new PcpClient(at, g.local());
        Session s = pcpSession(pcp);
        try {
            if (!mapAll(s)) {
                return null;
            }
            protocolName = "PCP";
            return s;
        } catch (PcpClient.UnsupportedVersionException e) {
            RemotePlaySettings.log("PCP: %s speaks NAT-PMP", g.gateway().getHostAddress());
        } catch (IOException e) {
            return failed(e);
        }
        NatPmpClient pmp = new NatPmpClient(at, g.local());
        Session n = natPmpSession(pmp);
        try {
            // NAT-PMP states the external address apart: checked BEFORE mapping.
            String ext = n.externalAddress();
            externalIp = ext;
            if (!NatAddress.isReachable(ext)) {
                carrierNat(ext);
                return null;
            }
            if (!mapAll(n)) {
                return null;
            }
            protocolName = "NAT-PMP";
            return n;
        } catch (IOException e) {
            return failed(e);
        }
    }

    /**
     * Maps TCP and, if wanted, UDP. {@code false} when the gateway does not answer at all.
     * PCP states the external address only in the mapping answer: if it is not public the
     * mapping just made is withdrawn at once.
     */
    private boolean mapAll(Session s) throws IOException {
        int tcp;
        try {
            tcp = s.map(true, port, LEASE_SECONDS);
        } catch (java.net.SocketTimeoutException e) {
            return false;
        }
        externalTcp = tcp;
        String ext = s.externalAddress();
        if (ext != null) {
            externalIp = ext;
            if (!NatAddress.isReachable(ext)) {
                unmap(s, true);
                carrierNat(ext);
                throw new CarrierNatException();
            }
        }
        if (udp) {
            try {
                externalUdp = s.map(false, port, LEASE_SECONDS);
            } catch (IOException e) {
                unmap(s, true);   // no half mapping left on the gateway
                throw e;
            }
        }
        return true;
    }

    /** Signals that the state is already final; nothing more to report. */
    private static final class CarrierNatException extends IOException {
        private static final long serialVersionUID = 1L;
    }

    private void carrierNat(String ext) {
        state = PortMapping.State.CARRIER_NAT;
        detail = ext;
        RemotePlaySettings.say("%s: the gateway has a non-public address (%s)", protocolName, ext);
    }

    private Session failed(IOException e) {
        if (e instanceof CarrierNatException) {
            return null;
        }
        if (e instanceof java.net.SocketTimeoutException) {
            return null;   // this gateway is silent: the next guess is tried
        }
        state = PortMapping.State.REFUSED;
        detail = e.getMessage();
        RemotePlaySettings.say("%s: the gateway refused port %d (%s)", protocolName, port,
                e.getMessage());
        return null;
    }

    private void renewLoop(Session s) {
        while (true) {
            long waitMs = Math.max(MIN_RENEW_MS, s.lifetime() * 500L);
            synchronized (this) {
                if (closed) {
                    return;
                }
                try {
                    wait(waitMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (closed) {
                    return;
                }
            }
            try {
                externalTcp = s.map(true, externalTcp, LEASE_SECONDS);
                if (udp) {
                    externalUdp = s.map(false, externalUdp, LEASE_SECONDS);
                }
            } catch (IOException e) {
                state = PortMapping.State.REFUSED;
                detail = e.getMessage();
                return;
            }
        }
    }

    private void withdraw(Session s) {
        unmap(s, true);
        if (udp) {
            unmap(s, false);
        }
        RemotePlaySettings.log("%s: mapping of port %d withdrawn", protocolName, port);
    }

    private void unmap(Session s, boolean tcp) {
        try {
            s.map(tcp, 0, 0);
        } catch (IOException e) {
            RemotePlaySettings.log("%s: removal failed: %s", protocolName, e.getMessage());
        }
    }

    // --- the two protocols behind one shape ---------------------------------------------

    private Session pcpSession(PcpClient pcp) {
        return new Session() {
            private String external;
            private int lifetime = LEASE_SECONDS;

            @Override
            public int map(boolean tcp, int suggested, int life) throws IOException {
                PcpClient.Granted g = pcp.map(tcp, port, suggested, life);
                if (life > 0) {
                    external = g.externalAddress() == null ? null
                            : g.externalAddress().getHostAddress();
                    lifetime = g.lifetimeSeconds();
                }
                return g.externalPort();
            }

            @Override
            public String externalAddress() {
                return external;
            }

            @Override
            public int lifetime() {
                return lifetime;
            }
        };
    }

    private Session natPmpSession(NatPmpClient pmp) {
        return new Session() {
            private int lifetime = LEASE_SECONDS;

            @Override
            public int map(boolean tcp, int suggested, int life) throws IOException {
                NatPmpClient.Granted g = pmp.map(tcp, port, suggested, life);
                if (life > 0) {
                    lifetime = g.lifetimeSeconds();
                }
                return g.externalPort();
            }

            @Override
            public String externalAddress() throws IOException {
                InetAddress a = pmp.externalAddress();
                return a.getHostAddress();
            }

            @Override
            public int lifetime() {
                return lifetime;
            }
        };
    }
}
