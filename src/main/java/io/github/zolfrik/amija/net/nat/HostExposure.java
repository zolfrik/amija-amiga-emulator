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

import io.github.zolfrik.amija.i18n.Messages;
import io.github.zolfrik.amija.net.InviteCode;
import io.github.zolfrik.amija.net.RemotePlaySettings;
import io.github.zolfrik.amija.net.upnp.NatAddress;

import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Everything that makes one listening port reachable, and what can be said about it
 * ({@code docs/remote-play-r1-plan.md} sections 3-7): the router mapping, the STUN probe, the
 * IPv6 addresses, the Windows firewall, the invitation's addresses and the one-line summary.
 * The stream host and the null modem host each own one; the null modem maps TCP only.
 *
 * <p>Nothing starts before {@link #start()}, and only when the host accepts the Internet: a
 * local-network host asks nothing of the router, of STUN or of the firewall.
 */
public final class HostExposure {

    /**
     * What the host asked for.
     *
     * @param internet          guests from the Internet too
     * @param udp               whether the port is also used over UDP
     * @param bind              the interface chosen by hand, or empty
     * @param ipv6              offer global IPv6 addresses
     * @param stunServers       STUN servers to ask; empty = none
     * @param rememberedUpnp    the UPnP router of an earlier session, or {@code null}
     * @param onUpnpGatewayFound told the description URL of the router that mapped, to be
     *                          remembered; may be {@code null}
     */
    public record Options(boolean internet, boolean udp, String bind, boolean ipv6,
                          List<String> stunServers, URI rememberedUpnp,
                          Consumer<URI> onUpnpGatewayFound) {
    }

    private final int port;
    private final Options options;
    private volatile PortMapping mapping;
    private volatile StunClient.Result stun;
    private volatile WindowsFirewall.Status firewall;
    /** True once the STUN probe has answered or failed, or when there is none to wait for. */
    private volatile boolean stunSettled;
    /** A gateway that mapped before STUN settled, held until its address can be judged. */
    private URI pendingGateway;
    private String pendingGatewayIp;
    private volatile boolean closed;
    private Thread closer;

    public HostExposure(int port, Options options) {
        this.port = port;
        this.options = options;
        this.stunSettled = options.stunServers().isEmpty();
    }

    public boolean internet() {
        return options.internet();
    }

    /** Starts the mapping, the STUN probe and the firewall check, each on its own thread. */
    public synchronized void start() {
        if (!options.internet() || mapping != null || closed) {
            return;
        }
        if (!options.stunServers().isEmpty()) {
            daemon("amija-net-stun", () -> {
                StunClient.Result r = StunClient.probe(options.stunServers());
                if (!closed) {
                    stun = r;
                    if (r.publicAddress() != null) {
                        RemotePlaySettings.say("STUN: seen from %s", r.publicAddress());
                    }
                }
                settleStun();
            });
        }
        if (WindowsFirewall.applies()) {
            Optional<Path> exe = ProcessHandle.current().info().command().map(Path::of);
            exe.ifPresent(p -> daemon("amija-net-firewall", () -> firewall = WindowsFirewall.detect(p)));
        }
        PortMapping m = new PortMapping(port, options.udp(), options.rememberedUpnp());
        m.setOnMapped(() -> {
            URI found = m.foundUpnpDescription();
            if (found != null && options.onUpnpGatewayFound() != null) {
                judgeGateway(found, m.externalIp());
            }
        });
        mapping = m;
        m.open();
    }

    /**
     * Withdraws the mapping on a thread of its own: talking to the router takes seconds, and
     * whoever stops hosting is usually the event thread inside the popup.
     */
    public synchronized void close() {
        closed = true;
        PortMapping m = mapping;
        mapping = null;
        if (m != null) {
            closer = daemon("amija-net-unmap", m::close);
        }
    }

    /**
     * Waits for the withdrawal, at most {@code millis}. Only for the shutdown hook: a daemon
     * thread talking to the router is killed at JVM exit, and the lease is the net under it.
     */
    public void awaitClosed(long millis) {
        Thread t;
        synchronized (this) {
            t = closer;
        }
        if (t != null) {
            try {
                t.join(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // =====================================================================
    // What is known
    // =====================================================================

    /** The address of this machine on the local network: the chosen interface, or the default route's. */
    public String lanAddress() {
        return options.bind().isEmpty() ? LocalAddresses.primaryIpv4Text() : options.bind();
    }

    /** {@code ip:port} of the router mapping once it exists, else {@code null}. */
    public String internetAddress() {
        PortMapping m = mapping;
        return m == null || m.state() != PortMapping.State.MAPPED ? null : m.externalAddress();
    }

    /**
     * Remembers a gateway that mapped, unless STUN contradicts its public address: it may be
     * a machine on the network posing as the router. While STUN has not settled the gateway
     * is held, and judged when it does.
     */
    void judgeGateway(URI found, String externalIp) {
        synchronized (this) {
            if (!stunSettled) {
                pendingGateway = found;
                pendingGatewayIp = externalIp;
                return;
            }
        }
        if (!closed && !stunContradicts(externalIp)) {
            options.onUpnpGatewayFound().accept(found);
        }
    }

    /** STUN has answered or failed: a gateway held meanwhile is judged now. */
    private void settleStun() {
        URI found;
        String ip;
        synchronized (this) {
            stunSettled = true;
            found = pendingGateway;
            ip = pendingGatewayIp;
            pendingGateway = null;
            pendingGatewayIp = null;
        }
        if (found != null) {
            judgeGateway(found, ip);
        }
    }

    /** True when STUN saw a public address and it is not {@code external}. */
    boolean stunContradicts(String external) {
        String seen = stunPublicIp();
        return seen != null && !seen.equals(external);
    }

    /** For tests: a mapping already in place. */
    void useMapping(PortMapping m) {
        mapping = m;
    }

    /** For tests: what the STUN servers saw. */
    void setStunResult(StunClient.Result r) {
        stun = r;
        settleStun();
    }

    /** The public IPv4 the STUN servers saw, if public; else {@code null}. */
    String stunPublicIp() {
        StunClient.Result r = stun;
        if (r == null || r.publicAddress() == null) {
            return null;
        }
        String ip = r.publicAddress().getAddress().getHostAddress();
        return NatAddress.isReachable(ip) ? ip : null;
    }

    /**
     * The addresses of the invitation: the local one, the global IPv6 ones (unverified), and
     * the public IPv4 as the router mapped it (verified) or, failing that, as STUN saw it
     * (unverified: it works if the port was forwarded by hand).
     */
    public List<InviteCode.Candidate> candidates() {
        List<InviteCode.Candidate> list = new ArrayList<>(4);
        add(list, lanAddress(), InviteCode.Scope.LAN, port, port);
        if (options.internet() && options.ipv6()) {
            for (InetAddress v6 : LocalAddresses.current().globalIpv6()) {
                if (list.size() < InviteCode.MAX_CANDIDATES - 1) {
                    list.add(new InviteCode.Candidate(v6, InviteCode.Scope.PUBLIC_UNVERIFIED,
                            port, port));
                }
            }
        }
        PortMapping m = mapping;
        if (m != null && m.state() == PortMapping.State.MAPPED && m.externalTcpPort() > 0) {
            // The router may grant other external ports than the host's, and different ones
            // for TCP and UDP: the invitation carries both. The address is only the gateway's
            // word, and anything on the local network can pose as the gateway: it is presented
            // as verified only once STUN has settled without seeing another public address.
            add(list, m.externalIp(), !stunSettled || stunContradicts(m.externalIp())
                            ? InviteCode.Scope.PUBLIC_UNVERIFIED : InviteCode.Scope.PUBLIC_VERIFIED,
                    m.externalTcpPort(),
                    m.externalUdpPort() > 0 ? m.externalUdpPort() : m.externalTcpPort());
        } else if (options.internet() && stunPublicIp() != null) {
            add(list, stunPublicIp(), InviteCode.Scope.PUBLIC_UNVERIFIED, port, port);
        }
        return list;
    }

    /** Adds a literal address; anything that is not one is skipped, never resolved. */
    private static void add(List<InviteCode.Candidate> into, String literal,
                            InviteCode.Scope scope, int tcp, int udp) {
        if (literal == null || literal.isBlank()
                || !(literal.indexOf(':') >= 0 || literal.matches("[0-9.]+"))) {
            return;
        }
        try {
            InetAddress a = InetAddress.getByName(literal);
            if (!a.isLoopbackAddress() && !a.isAnyLocalAddress()) {
                into.add(new InviteCode.Candidate(a, scope, tcp, udp));
            }
        } catch (java.net.UnknownHostException e) {
            // A literal never needs a lookup; a malformed one is simply left out.
        }
    }

    /**
     * The router's outcome in full, for a tooltip; {@code null} when there is nothing to say.
     */
    public String routerDetail() {
        PortMapping m = mapping;
        if (!options.internet()) {
            return Messages.t("msg.remoteplay.upnp.lanonly");
        }
        if (m == null) {
            return null;
        }
        return switch (m.state()) {
            case SEARCHING -> Messages.t(m.awaitingAnnouncement()
                    ? "msg.remoteplay.upnp.awaiting" : "msg.remoteplay.upnp.searching");
            case MAPPED -> Messages.t("msg.remoteplay.upnp.mapped",
                    m.externalAddress() + " (" + m.method() + ")");
            case NO_GATEWAY -> Messages.t("msg.remoteplay.upnp.nogateway");
            case REFUSED -> Messages.t("msg.remoteplay.upnp.refused", String.valueOf(m.detail()));
            case CARRIER_NAT -> Messages.t("msg.remoteplay.upnp.cgnat",
                    String.valueOf(m.externalIp()));
            case OFF -> null;
        };
    }

    /** Whether and how the port can be reached, in one line. */
    public String reachability() {
        StringBuilder sb = new StringBuilder(Messages.t("msg.remoteplay.reach.lan",
                lanAddress() + ":" + port));
        sb.append(" - ");
        if (!options.internet()) {
            return sb.append(Messages.t("msg.remoteplay.reach.lanonly")).toString();
        }
        if (options.ipv6()) {
            List<InetAddress> v6 = LocalAddresses.current().globalIpv6();
            sb.append(v6.isEmpty() ? Messages.t("msg.remoteplay.reach.noipv6")
                    : Messages.t("msg.remoteplay.reach.ipv6", v6.get(0).getHostAddress()))
                    .append(" - ");
        }
        PortMapping m = mapping;
        String stunIp = stunPublicIp();
        if (m == null) {
            sb.append(Messages.t("msg.remoteplay.reach.router.none"));
        } else {
            sb.append(switch (m.state()) {
                case SEARCHING, OFF -> Messages.t(m.awaitingAnnouncement()
                        ? "msg.remoteplay.reach.router.awaiting"
                        : "msg.remoteplay.reach.router.searching");
                case MAPPED -> Messages.t("msg.remoteplay.reach.router.mapped",
                        m.externalAddress(), m.method());
                case NO_GATEWAY -> stunIp != null
                        ? Messages.t("msg.remoteplay.reach.stunonly", stunIp)
                        : Messages.t("msg.remoteplay.reach.router.none");
                case REFUSED -> stunIp != null
                        ? Messages.t("msg.remoteplay.reach.stunonly", stunIp)
                        : Messages.t("msg.remoteplay.reach.router.refused");
                case CARRIER_NAT -> Messages.t("msg.remoteplay.reach.cgnat");
            });
            StunClient.Result r = stun;
            if (m.state() == PortMapping.State.MAPPED && stunIp != null
                    && m.externalIp() != null && !stunIp.equals(m.externalIp())) {
                // The router mapped a public address the world does not see: another NAT
                // stands between it and the Internet.
                sb.append(" - ").append(Messages.t("msg.remoteplay.reach.doublenat", stunIp));
            } else if (r != null && r.endpointDependent()) {
                sb.append(" - ").append(Messages.t("msg.remoteplay.reach.symmetric"));
            }
        }
        WindowsFirewall.Status f = firewall;
        if (f == WindowsFirewall.Status.BLOCKED) {
            sb.append(" - ").append(Messages.t("msg.remoteplay.reach.firewall.blocked"));
        } else if (f == WindowsFirewall.Status.NO_RULE) {
            sb.append(" - ").append(Messages.t("msg.remoteplay.reach.firewall.norule"));
        }
        return sb.toString();
    }

    // =====================================================================
    // The firewall button
    // =====================================================================

    /** True when the firewall stands in the way and a rule for AmiJa's launcher can be offered. */
    public boolean firewallFixOffered() {
        WindowsFirewall.Status f = firewall;
        return options.internet() && WindowsFirewall.launcherExecutable().isPresent()
                && (f == WindowsFirewall.Status.BLOCKED || f == WindowsFirewall.Status.NO_RULE);
    }

    /** The commands the firewall button runs elevated, or {@code null}. */
    public static String firewallCommand() {
        return WindowsFirewall.launcherExecutable().map(WindowsFirewall::allowCommand).orElse(null);
    }

    /** Runs the elevated rule change on its own thread, then reads the rules again. */
    public void allowInFirewall() {
        Optional<Path> exe = WindowsFirewall.launcherExecutable();
        if (exe.isEmpty() || !firewallFixOffered()) {
            return;
        }
        daemon("amija-net-firewall-allow", () -> {
            boolean ran = WindowsFirewall.allow(exe.get());
            RemotePlaySettings.say("firewall: rule %s", ran ? "requested" : "not added");
            firewall = WindowsFirewall.detect(exe.get());
        });
    }

    private static Thread daemon(String name, Runnable body) {
        Thread t = new Thread(body, name);
        t.setDaemon(true);
        t.start();
        return t;
    }
}
