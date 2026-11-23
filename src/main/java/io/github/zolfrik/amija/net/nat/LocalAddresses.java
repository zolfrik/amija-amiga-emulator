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

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * The addresses of this machine worth putting in an invitation, and what counts as "the local
 * network" ({@code docs/remote-play-r1-plan.md} section 4).
 *
 * <p>The interface is the one of the default route, found without sending a byte: a UDP socket
 * {@code connect}ed toward a public address makes the system choose the source address it
 * would use. Other adapters (a virtual switch, a VPN that is not the default route) stay out of
 * the invitation, where they would only be addresses the other player cannot reach.
 */
public final class LocalAddresses {

    /** Most IPv6 addresses offered per interface; a temporary one lasts longer than a game. */
    static final int MAX_IPV6 = 2;

    /** The addresses of the default route's interface. */
    public record Snapshot(InetAddress lanIpv4, List<InetAddress> globalIpv6) {
    }

    private LocalAddresses() {
    }

    /** The current addresses; never {@code null}, possibly empty. */
    public static Snapshot current() {
        InetAddress v4 = routeSource(false);
        List<InetAddress> v6 = new ArrayList<>(MAX_IPV6);
        InetAddress v6Source = routeSource(true);
        NetworkInterface nic = interfaceOf(v6Source != null && isGlobalIpv6(v6Source)
                ? v6Source : v4);
        if (nic != null) {
            if (v6Source != null && isGlobalIpv6(v6Source)) {
                v6.add(strip(v6Source));
            }
            for (InterfaceAddress ia : nic.getInterfaceAddresses()) {
                InetAddress a = strip(ia.getAddress());
                if (v6.size() < MAX_IPV6 && isGlobalIpv6(a) && !v6.contains(a)) {
                    v6.add(a);
                }
            }
        }
        return new Snapshot(v4 == null || v4.isLoopbackAddress() ? null : v4, List.copyOf(v6));
    }

    /** The IPv4 address of the default route, as text; {@code 127.0.0.1} when there is none. */
    public static String primaryIpv4Text() {
        InetAddress a = routeSource(false);
        return a == null ? "127.0.0.1" : a.getHostAddress();
    }

    /**
     * True for a global unicast IPv6 address: in {@code 2000::/3}, which excludes link-local,
     * unique-local ({@code fc00::/7}), loopback and the IPv4-mapped form.
     */
    public static boolean isGlobalIpv6(InetAddress a) {
        if (!(a instanceof Inet6Address)) {
            return false;
        }
        int first = a.getAddress()[0] & 0xFF;
        return (first & 0xE0) == 0x20;
    }

    /**
     * True if {@code peer} is on a network this machine is attached to: the network bits are
     * compared with the real prefix of each interface (for IPv6, in practice, the /64).
     * Loopback and link-local always pass. Being a private address is not enough: between two
     * private networks (a VPN, another building) there is exactly the distance this test is
     * about.
     */
    public static boolean isLocalNetwork(InetAddress peer) {
        if (peer == null) {
            return false;
        }
        if (peer.isLoopbackAddress() || peer.isAnyLocalAddress() || peer.isLinkLocalAddress()) {
            return true;
        }
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface nic = interfaces.nextElement();
                if (!nic.isUp()) {
                    continue;
                }
                for (InterfaceAddress local : nic.getInterfaceAddresses()) {
                    if (sameSubnet(local.getAddress(), local.getNetworkPrefixLength(), peer)) {
                        return true;
                    }
                }
            }
        } catch (IOException e) {
            // Locality cannot be proved without the interfaces: in doubt, it is not local.
        }
        return false;
    }

    /** Compares the first {@code prefix} bits of the two addresses. */
    static boolean sameSubnet(InetAddress mine, int prefix, InetAddress peer) {
        byte[] a = mine.getAddress();
        byte[] b = peer.getAddress();
        if (a.length != b.length || prefix <= 0 || prefix > a.length * 8) {
            return false;
        }
        int full = prefix / 8;
        for (int i = 0; i < full; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        int spare = prefix % 8;
        if (spare == 0) {
            return true;
        }
        int mask = 0xFF << (8 - spare);
        return (a[full] & mask) == (b[full] & mask);
    }

    /**
     * The key under which a peer is counted by the brake on wrong codes: the address itself
     * for IPv4, its /64 for IPv6, since one IPv6 host can take a new address in its /64 at
     * every attempt.
     */
    public static InetAddress throttleKey(InetAddress peer) {
        if (!(peer instanceof Inet6Address) || peer.isLoopbackAddress()) {
            return peer;
        }
        byte[] b = peer.getAddress();
        for (int i = 8; i < 16; i++) {
            b[i] = 0;
        }
        try {
            return InetAddress.getByAddress(b);
        } catch (IOException e) {
            return peer;
        }
    }

    /** The source address the system would use toward the Internet, or {@code null}. */
    private static InetAddress routeSource(boolean ipv6) {
        try (DatagramSocket probe = new DatagramSocket()) {
            // Public resolvers; a UDP connect sends nothing, it only selects the route.
            probe.connect(InetAddress.getByName(ipv6 ? "2001:4860:4860::8888" : "8.8.8.8"), 53);
            InetAddress a = probe.getLocalAddress();
            if (a == null || a.isAnyLocalAddress()) {
                return null;
            }
            return ipv6 ? (a instanceof Inet6Address ? a : null)
                        : (a instanceof Inet4Address ? a : null);
        } catch (IOException | RuntimeException e) {
            return null;   // no route of that family
        }
    }

    private static NetworkInterface interfaceOf(InetAddress a) {
        if (a == null) {
            return null;
        }
        try {
            return NetworkInterface.getByInetAddress(a);
        } catch (IOException e) {
            return null;
        }
    }

    /** The address without its scope: an invitation travels to another machine. */
    private static InetAddress strip(InetAddress a) {
        if (a instanceof Inet6Address && ((Inet6Address) a).getScopeId() != 0) {
            try {
                return InetAddress.getByAddress(a.getAddress());
            } catch (IOException e) {
                return a;
            }
        }
        return a;
    }
}
