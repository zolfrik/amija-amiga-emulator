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
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Finds the routers that speak UPnP, with SSDP ({@code docs/remote-play-plan.md} section
 * 7.1).
 *
 * <p>A multicast M-SEARCH to {@code 239.255.255.250:1900}; the answers are collected for a few
 * seconds and from each one the {@code LOCATION} header is kept: the URL of the document that
 * describes the device. From there on {@link IgdClient} does the work.
 *
 * <h2>The search goes out from every interface, not the default one</h2>
 * The only non-obvious thing in this class, and without it the feature does not start on
 * exactly the machines where it is needed most. A multicast sent from a socket with a
 * default destination goes out of <b>one</b> interface, the one the system chooses, and on a
 * computer with a VPN, a virtual machine or two network adapters that choice is often the
 * wrong adapter, where no router answers. So it is sent from each active IPv4 address, and
 * <b>which</b> one got an answer is tracked: that address is also the one to declare to the
 * router as the mapping's destination, and the only one known for sure to be on the same
 * network as whoever answered (no need to guess with a {@code connect} toward the outside,
 * as in section 7.2: the answer proves it).
 *
 * <h2>Some routers never answer: they only announce</h2>
 * A gateway is supposed to answer an M-SEARCH, and some do not, ever: they only multicast a
 * {@code NOTIFY ssdp:alive} every few minutes (measured on a ZyXEL VMG8825, whose IGD answers
 * no search at all, {@code docs/remote-play-r1-plan.md} M3). {@link #awaitAnnouncement} listens
 * for those, and a description URL found once can be tried again directly
 * ({@link #gatewayAt}), which is what an operating system's SSDP cache does too.
 *
 * <h2>Asked several times, and of several targets</h2>
 * UDP does not guarantee delivery, and a lost M-SEARCH is a feature that "does not work".
 * The request is repeated, with two different {@code ST}s: some routers answer to the device
 * type, others only to the service type.
 */
public final class SsdpDiscovery {

    /** The SSDP multicast address, fixed by the UPnP specification. */
    private static final String SSDP_ADDRESS = "239.255.255.250";
    private static final int SSDP_PORT = 1900;

    /** Wait declared in the MX field: the router answers within this number of seconds. */
    private static final int MX_SECONDS = 2;

    /** How many times the request is repeated, so a lost datagram does not stop it. */
    private static final int ATTEMPTS = 2;

    private static final int RECEIVE_BUFFER = 2048;

    /**
     * How many distinct gateways may be kept.
     *
     * <p>Answers to an M-SEARCH come from anyone listening on the multicast, and nothing
     * stops a machine on the network from sending ten thousand with an ever different
     * {@code LOCATION}. The map that deduplicates them would grow with them; a constant cap
     * keeps it where it belongs. Real networks have one router, two at most.
     */
    private static final int MAX_GATEWAYS = 8;

    /**
     * The search targets. The device first (it answers with the complete description), then
     * the two services, for the routers that answer only to those.
     */
    private static final String[] SEARCH_TARGETS = {
        "urn:schemas-upnp-org:device:InternetGatewayDevice:1",
        "urn:schemas-upnp-org:service:WANIPConnection:1",
        "urn:schemas-upnp-org:service:WANPPPConnection:1",
    };

    /** The notification types that announce a gateway able to map ports. */
    private static final String[] GATEWAY_TYPES = {
        "urn:schemas-upnp-org:device:InternetGatewayDevice:",
        "urn:schemas-upnp-org:service:WANIPConnection:",
        "urn:schemas-upnp-org:service:WANPPPConnection:",
    };

    /** How often the announcement listener checks whether it was cancelled. */
    private static final int LISTEN_POLL_MS = 500;

    private SsdpDiscovery() {
    }

    /**
     * A router that answered.
     *
     * @param descriptionUrl the URL of the description document ({@code LOCATION} header)
     * @param localAddress   the address of <b>this</b> machine on the network the answer came
     *                       from: the one the router must map
     */
    public record Gateway(URI descriptionUrl, InetAddress localAddress) {
    }

    /**
     * Looks for reachable gateways.
     *
     * @param timeoutMs how long to listen for answers, per interface
     * @return the gateways found, without duplicates, in the order they answered
     */
    public static List<Gateway> search(int timeoutMs) {
        // Key = URL of the description: the same router answers several times (once per
        // attempt and once per ST) and without deduplication it would be queried six times.
        Map<URI, Gateway> found = new LinkedHashMap<>();
        for (InetAddress local : localIpv4Addresses()) {
            searchFrom(local, timeoutMs, found);
        }
        return new ArrayList<>(found.values());
    }

    private static void searchFrom(InetAddress local, int timeoutMs, Map<URI, Gateway> found) {
        try (DatagramSocket socket = new DatagramSocket(new InetSocketAddress(local, 0))) {
            socket.setSoTimeout(Math.max(50, timeoutMs / ATTEMPTS));
            InetAddress group = InetAddress.getByName(SSDP_ADDRESS);
            for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
                for (String target : SEARCH_TARGETS) {
                    byte[] request = mSearch(target).getBytes(StandardCharsets.US_ASCII);
                    socket.send(new DatagramPacket(request, request.length, group, SSDP_PORT));
                }
                collect(socket, local, found);
            }
        } catch (IOException e) {
            // An interface that cannot send multicast is not a failure: it is the normal case
            // of a disabled virtual adapter. Try the next.
            RemotePlaySettings.log("SSDP from %s not usable: %s", local.getHostAddress(), e.getMessage());
        }
    }

    /** Collects the answers until the socket times out. */
    private static void collect(DatagramSocket socket, InetAddress local,
                                Map<URI, Gateway> found) throws IOException {
        byte[] buffer = new byte[RECEIVE_BUFFER];
        while (found.size() < MAX_GATEWAYS) {
            DatagramPacket response = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(response);
            } catch (SocketTimeoutException e) {
                return;   // nobody answers any more: that is how an SSDP search ends
            }
            String text = new String(response.getData(), response.getOffset(),
                    response.getLength(), StandardCharsets.US_ASCII);
            String location = header(text, "LOCATION");
            if (location == null) {
                continue;
            }
            try {
                URI url = new URI(location.trim());
                if (isTrustworthy(url, response.getAddress())) {
                    found.putIfAbsent(url, new Gateway(url, local));
                }
            } catch (URISyntaxException e) {
                RemotePlaySettings.log("LOCATION not parseable: %s", location);
            }
        }
    }

    /**
     * Whether a {@code LOCATION} can be trusted enough to go and read it.
     *
     * <h2>Whoever answers an M-SEARCH is not necessarily a router</h2>
     * Anyone on the network receives the multicast, and anyone can answer in time: there is
     * no need to <i>be</i> the gateway, only to arrive first. The {@code LOCATION} is
     * therefore a URL chosen by a stranger, and on that URL AmiJa then does a GET and,
     * through {@code URLBase} and {@code controlURL}, a POST of XML. Unchecked, any machine on
     * the network could make AmiJa talk to an address of its choice: a service listening only
     * on {@code localhost}, another device on the LAN, or a host on the Internet. The classic
     * <i>server-side request forgery</i>, and here the victim is the home machine of whoever
     * is playing.
     *
     * <p>Two conditions close it, and neither bothers a real router:
     * <ol>
     *   <li>the <b>scheme</b> must be {@code http} or {@code https}: an IGD serves its
     *       description nowhere else, while {@code file:} and the like are only ways to make
     *       AmiJa read something that is not its own;</li>
     *   <li>the <b>host must be whoever answered</b>. This is the condition that counts: a
     *       device's description lives <i>on</i> that device, so a {@code LOCATION} pointing
     *       elsewhere is not a careless router, it is a bounce. The comparison is on the
     *       datagram's address, which cannot be declared.</li>
     * </ol>
     */
    static boolean isTrustworthy(URI url, InetAddress responder) {
        String scheme = url.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            RemotePlaySettings.log("LOCATION with a scheme not allowed, ignored: %s", url);
            return false;
        }
        String host = url.getHost();
        if (host == null || responder == null) {
            return false;
        }
        if (!sameHost(host, responder)) {
            RemotePlaySettings.log(
                    "LOCATION %s does not point to the responder (%s): ignored",
                    url, responder.getHostAddress());
            return false;
        }
        return true;
    }

    /**
     * Whether a URL's host name designates the very machine that sent the answer.
     *
     * <p>The textual forms are compared first (routers almost always put a literal IP
     * address, and that comparison asks nothing of anyone). Only if they do not match is the
     * name resolved, and <b>every</b> address it resolves to must be the sender's: a name that
     * carries even one different address is exactly the bounce being excluded.
     */
    private static boolean sameHost(String host, InetAddress responder) {
        String bare = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1)
                : host;
        if (bare.equalsIgnoreCase(responder.getHostAddress())) {
            return true;
        }
        try {
            InetAddress[] resolved = InetAddress.getAllByName(bare);
            if (resolved.length == 0) {
                return false;
            }
            for (InetAddress a : resolved) {
                if (!a.equals(responder)) {
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * The value of a header of an SSDP answer, or {@code null} if absent.
     *
     * <p>The comparison is <b>case-insensitive</b> because HTTP prescribes it and routers rely
     * on it: {@code LOCATION}, {@code Location} and {@code location} are seen from the same
     * model depending on the firmware.
     */
    static String header(String message, String name) {
        String wanted = name.toLowerCase(Locale.ROOT) + ":";
        for (String line : message.split("\r\n|\n")) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith(wanted)) {
                return line.substring(wanted.length()).trim();
            }
        }
        return null;
    }

    private static String mSearch(String searchTarget) {
        // The quotes around "ssdp:discover" are mandatory: without them several routers do
        // not answer at all. Lines end in CRLF and the message in an empty line, as every HTTP
        // request.
        return "M-SEARCH * HTTP/1.1\r\n"
                + "HOST: " + SSDP_ADDRESS + ":" + SSDP_PORT + "\r\n"
                + "MAN: \"ssdp:discover\"\r\n"
                + "MX: " + MX_SECONDS + "\r\n"
                + "ST: " + searchTarget + "\r\n"
                + "\r\n";
    }

    /**
     * Waits for a gateway to announce itself with a {@code NOTIFY ssdp:alive}.
     *
     * <p>Listens on the SSDP group from every IPv4 interface, sharing port 1900 with whoever
     * else listens there (the system's own SSDP service). Only announcements of a gateway type
     * count, and their {@code LOCATION} passes the same check as an answer to a search.
     *
     * @param timeoutMs how long to wait at most
     * @param cancelled polled while waiting: {@code true} stops at once
     * @return the gateway, or {@code null} if none announced itself in time
     */
    public static Gateway awaitAnnouncement(long timeoutMs,
                                            java.util.function.BooleanSupplier cancelled) {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        try (MulticastSocket socket = new MulticastSocket(null)) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(SSDP_PORT));
            InetSocketAddress group = new InetSocketAddress(
                    InetAddress.getByName(SSDP_ADDRESS), SSDP_PORT);
            int joined = 0;
            for (NetworkInterface nic : multicastInterfaces()) {
                try {
                    socket.joinGroup(group, nic);
                    joined++;
                } catch (IOException e) {
                    RemotePlaySettings.log("SSDP group not joinable on %s: %s",
                            nic.getName(), e.getMessage());
                }
            }
            if (joined == 0) {
                return null;
            }
            socket.setSoTimeout(LISTEN_POLL_MS);
            byte[] buffer = new byte[RECEIVE_BUFFER];
            while (!cancelled.getAsBoolean() && System.nanoTime() - deadline < 0) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                String text = new String(packet.getData(), packet.getOffset(),
                        packet.getLength(), StandardCharsets.US_ASCII);
                Gateway g = fromAnnouncement(text, packet.getAddress());
                if (g != null) {
                    RemotePlaySettings.say("UPnP: the router announced itself at %s",
                            g.descriptionUrl());
                    return g;
                }
            }
        } catch (IOException e) {
            RemotePlaySettings.log("SSDP announcements cannot be heard: %s", e.getMessage());
        }
        return null;
    }

    /**
     * The gateway a {@code NOTIFY} announces, or {@code null} if it is not a live gateway
     * announcement or its {@code LOCATION} is not to be trusted.
     */
    static Gateway fromAnnouncement(String text, InetAddress sender) {
        if (!text.regionMatches(true, 0, "NOTIFY ", 0, 7)) {
            return null;
        }
        String nts = header(text, "NTS");
        String nt = header(text, "NT");
        String location = header(text, "LOCATION");
        if (nts == null || !nts.equalsIgnoreCase("ssdp:alive") || nt == null
                || location == null || !isGatewayType(nt)) {
            return null;
        }
        try {
            URI url = new URI(location.trim());
            if (!isTrustworthy(url, sender)) {
                return null;
            }
            InetAddress local = localAddressFacing(sender);
            return local == null ? null : new Gateway(url, local);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /**
     * A gateway at a description URL remembered from an earlier session, or {@code null} if
     * that URL can no longer be trusted: it must be plain {@code http(s)} on a literal address
     * of a network this machine is on now.
     */
    public static Gateway gatewayAt(URI descriptionUrl) {
        if (descriptionUrl == null || descriptionUrl.getScheme() == null
                || !(descriptionUrl.getScheme().equalsIgnoreCase("http")
                        || descriptionUrl.getScheme().equalsIgnoreCase("https"))) {
            return null;
        }
        String host = descriptionUrl.getHost();
        if (host == null || !host.matches("[0-9.]+")) {
            return null;   // a name would have to be resolved: only literals are remembered
        }
        try {
            InetAddress router = InetAddress.getByName(host);
            InetAddress local = localAddressFacing(router);
            return local == null ? null : new Gateway(descriptionUrl, local);
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean isGatewayType(String nt) {
        for (String type : GATEWAY_TYPES) {
            if (nt.regionMatches(true, 0, type, 0, type.length())) {
                return true;
            }
        }
        return false;
    }

    /** This machine's IPv4 address on the subnet {@code peer} belongs to, or {@code null}. */
    static InetAddress localAddressFacing(InetAddress peer) {
        if (!(peer instanceof Inet4Address)) {
            return null;
        }
        for (NetworkInterface nic : multicastInterfaces()) {
            for (InterfaceAddress ia : nic.getInterfaceAddresses()) {
                InetAddress mine = ia.getAddress();
                if (mine instanceof Inet4Address && !mine.equals(peer)
                        && samePrefix(mine.getAddress(), peer.getAddress(),
                                ia.getNetworkPrefixLength())) {
                    return mine;
                }
            }
        }
        return null;
    }

    private static boolean samePrefix(byte[] a, byte[] b, int prefix) {
        if (prefix <= 0 || prefix > 32) {
            return false;
        }
        int mask = prefix == 32 ? -1 : ~((1 << (32 - prefix)) - 1);
        int x = ((a[0] & 0xFF) << 24) | ((a[1] & 0xFF) << 16) | ((a[2] & 0xFF) << 8) | (a[3] & 0xFF);
        int y = ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
        return (x & mask) == (y & mask);
    }

    /** Interfaces that are up, not loopback, and can multicast. */
    private static List<NetworkInterface> multicastInterfaces() {
        List<NetworkInterface> list = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface nic = interfaces.nextElement();
                if (nic.isUp() && !nic.isLoopback() && nic.supportsMulticast()) {
                    list.add(nic);
                }
            }
        } catch (IOException e) {
            RemotePlaySettings.log("interface list unavailable: %s", e.getMessage());
        }
        return list;
    }

    /**
     * The IPv4 addresses worth searching from.
     *
     * <p>Interfaces that are down, loopback and those that cannot multicast are discarded:
     * nobody would answer and each would cost the whole wait.
     */
    private static List<InetAddress> localIpv4Addresses() {
        List<InetAddress> addresses = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface nic = interfaces.nextElement();
                if (!nic.isUp() || nic.isLoopback() || !nic.supportsMulticast()) {
                    continue;
                }
                Enumeration<InetAddress> local = nic.getInetAddresses();
                while (local.hasMoreElements()) {
                    InetAddress address = local.nextElement();
                    if (address instanceof Inet4Address) {
                        addresses.add(address);
                    }
                }
            }
        } catch (IOException e) {
            RemotePlaySettings.log("interface list unavailable: %s", e.getMessage());
        }
        return addresses;
    }
}
