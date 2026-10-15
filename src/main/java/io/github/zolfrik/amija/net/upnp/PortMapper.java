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

package io.github.zolfrik.amija.net.upnp;

import io.github.zolfrik.amija.net.RemotePlaySettings;

import java.io.IOException;
import java.util.List;

/**
 * Opens the port on the router and keeps it open while hosting
 * ({@code docs/remote-play-plan.md} section 7.1).
 *
 * <p>Everything happens on a thread of its own: finding the router and talking to it costs
 * seconds, and whoever asks (the F12 popup on the EDT, or a session start) cannot wait for
 * them. The state is read with {@link #state()} and the popup's status line shows it as it
 * goes.
 *
 * <h2>First look at the address, then open the port</h2>
 * The order matters. If the router is behind the operator's NAT (section 7.3) the mapping
 * would succeed anyway and be <b>useless</b>: so the external address is asked first, and if
 * it is not reachable the process stops there: no port opened for nothing, and the right
 * diagnosis in a couple of seconds instead of a "Connect" timing out on the other side. If
 * instead the router refuses the question (it happens), the mapping goes ahead anyway: a port
 * open without knowing the address is better than nothing.
 *
 * <h2>The reservation expires, and is renewed</h2>
 * {@value #LEASE_SECONDS} seconds, renewed every {@value #RENEW_MINUTES} minutes. An infinite
 * lease would be refused by quite a few routers, and above all, if AmiJa dies abruptly, a
 * reservation with an expiry disappears by itself instead of staying on the router forever.
 * Whoever refuses even the expiring lease (error {@value IgdClient#ERROR_ONLY_PERMANENT_LEASES})
 * says so with a code of its own, and then the request is retried with no expiry.
 */
public final class PortMapper implements AutoCloseable {

    /** Lease duration requested from the router. */
    static final int LEASE_SECONDS = 3600;

    /** How often to renew, with a generous margin on the expiry. */
    static final int RENEW_MINUTES = 30;

    /** How long to wait for the answers to the M-SEARCH. */
    private static final int DISCOVERY_TIMEOUT_MS = 3000;

    /** How long the withdrawal of the mapping is given before giving up. */
    private static final int TEARDOWN_TIMEOUT_MS = 8000;

    /** How the mapping appears in the router's list. */
    private static final String DESCRIPTION = "AmiJa remote play";

    private static final String[] PROTOCOLS = { "TCP", "UDP" };

    /** How far the port opening has got. */
    public enum State {
        /** Not requested. */
        OFF,
        /** Looking for a cooperating router. */
        SEARCHING,
        /** Port open: {@link #externalAddress()} says where one can be reached. */
        MAPPED,
        /** No UPnP router answered (often: UPnP disabled in the router). */
        NO_GATEWAY,
        /** The router answered but refused to open; {@link #detail()} says why. */
        REFUSED,
        /** The operator exposes no public address: nobody gets in from outside (section 7.3). */
        CARRIER_NAT
    }

    /**
     * How to reach the router.
     *
     * <p>It exists for one reason: testing. Searching through SSDP means sending multicast
     * from the real interfaces, i.e. depending on the network the build runs on, while
     * everything this class <b>decides</b> (first the address, then the port; the fallback on
     * the permanent lease; the withdrawal at closing) is logic that has nothing to do with how
     * the router was found. With this seam it can be exercised against a fake router on
     * loopback.
     */
    interface GatewayFinder {
        IgdClient find();
    }

    private final int port;
    private final GatewayFinder finder;

    private volatile State state = State.OFF;
    private volatile String externalIp;
    private volatile String detail;
    private volatile boolean closed;

    /** The router talked to, to be reused for renewal and removal. */
    private volatile IgdClient gateway;
    private Thread worker;

    public PortMapper(int port) {
        this(port, null);
    }

    /** @param finder how to find the router; {@code null} = SSDP, i.e. the real case */
    PortMapper(int port, GatewayFinder finder) {
        this.port = port;
        this.finder = finder;
    }

    /** Starts searching and opening. Returns at once. */
    public synchronized void open() {
        if (worker != null || closed) {
            return;
        }
        state = State.SEARCHING;
        worker = new Thread(this::run, "amija-remoteplay-upnp");
        worker.setDaemon(true);
        worker.start();
    }

    public State state() {
        return state;
    }

    /**
     * {@code address:port} to give the other player, or {@code null} if there is none.
     *
     * <p>"None" includes the case in which the address <b>is known</b> but the port is not
     * open: behind CGNAT what the router answers is well known, and for exactly that reason it
     * must not be returned: it would be a right-looking address nobody can reach, the most
     * effective way to make people look for the problem in the wrong place. Whoever wants the
     * address for diagnosis uses {@link #externalIp()}, which says what was seen, not what can
     * be used.
     */
    public String externalAddress() {
        String ip = externalIp;
        return state == State.MAPPED && ip != null ? ip + ":" + port : null;
    }

    /** The router's public address, even when it is not reachable (CGNAT). */
    public String externalIp() {
        return externalIp;
    }

    /** The technical reason of the last outcome, or {@code null}. */
    public String detail() {
        return detail;
    }

    /**
     * Withdraws the mapping and stops the renewal.
     *
     * <p>Must <b>always</b> be called: when listening stops and when the application closes
     * (section 7.1). Leaving a port open on someone's home router after they stopped playing
     * is not acceptable, which is why the reservation expires anyway: the network is not
     * reliable and this call may never arrive.
     */
    @Override
    public void close() {
        Thread pending;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            pending = worker;
            worker = null;
            notifyAll();   // wakes the renewal, which is waiting
        }
        if (pending != null) {
            try {
                // Wait, but do NOT INTERRUPT: the thread is about to do the one thing that
                // cannot be lost (withdrawing the mapping), and an interrupt in the middle of
                // that HTTP call would leave the port open on the router.
                pending.join(TEARDOWN_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        state = State.OFF;
        externalIp = null;
    }

    // =====================================================================

    private void run() {
        IgdClient igd = findGateway();
        if (closed) {
            return;   // closed while searching: nothing is opened and there is nothing to say
        }
        if (igd == null) {
            state = State.NO_GATEWAY;
            detail = "nessun router UPnP ha risposto";
            RemotePlaySettings.say("UPnP: nessun router ha risposto");
            return;
        }
        gateway = igd;

        // First the diagnosis (section 7.3): if nobody can get in from outside, opening is useless.
        String external = null;
        try {
            external = igd.externalAddress();
            externalIp = external;
            if (!NatAddress.isReachable(external)) {
                // Real CGNAT or cascaded routers: for whoever wants to play it is the same
                // problem with the same cure: host from the other side.
                state = State.CARRIER_NAT;
                detail = external;
                RemotePlaySettings.say("UPnP: il router ha un indirizzo non pubblico (%s):"
                        + " da Internet non si arriva, meglio ospitare dall'altra parte",
                        external);
                return;
            }
        } catch (IOException e) {
            // Not fatal: some routers open ports and do not tell their address. Carry on
            // without it, and the status line will show the LAN only.
            RemotePlaySettings.log("UPnP: indirizzo esterno non disponibile: %s", e.getMessage());
        }

        if (!map(igd)) {
            return;
        }
        state = State.MAPPED;
        detail = null;
        RemotePlaySettings.say("UPnP: porta %d aperta (TCP+UDP)%s", port,
                external == null ? "" : ", indirizzo pubblico " + external + ":" + port);
        renewLoop(igd);
        removeMapping(igd);
    }

    /** Opens TCP and UDP. {@code false} if the router refuses: the state already says so. */
    private boolean map(IgdClient igd) {
        for (String protocol : PROTOCOLS) {
            try {
                igd.addPortMapping(protocol, port, LEASE_SECONDS, DESCRIPTION);
            } catch (IgdClient.UpnpException e) {
                if (e.code() == IgdClient.ERROR_ONLY_PERMANENT_LEASES) {
                    // This router wants mappings with no expiry. Its condition is accepted:
                    // the removal at closing remains, and at that point it is the only thing
                    // that takes them away.
                    try {
                        igd.addPortMapping(protocol, port, 0, DESCRIPTION);
                        continue;
                    } catch (IOException retry) {
                        return refused(retry);
                    }
                }
                return refused(e);
            } catch (IOException e) {
                return refused(e);
            }
        }
        return true;
    }

    private boolean refused(IOException e) {
        state = State.REFUSED;
        detail = e.getMessage();
        RemotePlaySettings.say("UPnP: il router ha rifiutato di aprire la porta %d (%s)",
                port, e.getMessage());
        return false;
    }

    /** Renews until closed. */
    private void renewLoop(IgdClient igd) {
        while (true) {
            synchronized (this) {
                if (closed) {
                    return;
                }
                try {
                    wait(RENEW_MINUTES * 60_000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (closed) {
                    return;
                }
            }
            if (!map(igd)) {
                return;   // the router changed its mind: the state says so, nothing to withdraw
            }
            RemotePlaySettings.log("UPnP: mappatura della porta %d rinnovata", port);
        }
    }

    private void removeMapping(IgdClient igd) {
        for (String protocol : PROTOCOLS) {
            try {
                igd.deletePortMapping(protocol, port);
            } catch (IOException e) {
                RemotePlaySettings.log("UPnP: rimozione %s fallita: %s", protocol,
                        e.getMessage());
            }
        }
        RemotePlaySettings.log("UPnP: mappatura della porta %d ritirata", port);
    }

    /** The first gateway that answers and can map. */
    private IgdClient findGateway() {
        if (finder != null) {
            return finder.find();
        }
        List<SsdpDiscovery.Gateway> candidates = SsdpDiscovery.search(DISCOVERY_TIMEOUT_MS);
        for (SsdpDiscovery.Gateway candidate : candidates) {
            if (closed) {
                return null;
            }
            try {
                return IgdClient.connect(candidate);
            } catch (IOException e) {
                // Any UPnP device (a printer, a smart TV) answers the M-SEARCH and cannot map
                // anything. Not an error: try the next.
                RemotePlaySettings.log("UPnP: %s non e' un gateway utile (%s)",
                        candidate.descriptionUrl(), e.getMessage());
            }
        }
        return null;
    }
}
