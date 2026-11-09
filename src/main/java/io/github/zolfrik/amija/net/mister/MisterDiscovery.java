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

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Finds the MiSTers running the Groovy core on the networks of the PC. The core answers the
 * version query of whoever sends it, so the query goes to the broadcast address of every
 * network and each answer names a MiSTer and the local address that reaches it.
 *
 * <p>The core sends its answers to the last sender it heard: a search while another client
 * streams to the same MiSTer would divert that client's acknowledgements for a moment. It is
 * therefore made only before a session opens.
 */
public final class MisterDiscovery {

    /** How long a search waits for the answers. */
    public static final long DEFAULT_TIMEOUT_MS = 400;

    /**
     * A MiSTer that answered.
     *
     * @param mister  its address
     * @param local   the PC's address on the network it answered from
     * @param version the core's protocol version
     */
    public record Found(InetAddress mister, InetAddress local, int version) {

        /** Whether it answered over a network without a router, as a direct cable is. */
        public boolean linkLocal() {
            return mister.isLinkLocalAddress();
        }
    }

    /** Where one query goes: sent from {@code local} to {@code target}. */
    record Probe(InetAddress local, InetSocketAddress target) {
    }

    private MisterDiscovery() {
    }

    /**
     * Searches every network of the PC.
     *
     * @return the MiSTers found, those on a network without a router first; empty if none
     */
    public static List<Found> search(long timeoutMs) throws IOException {
        return search(probes(GroovyProtocol.PORT), timeoutMs);
    }

    /** The preferred of {@code found}, or {@code null} when it is empty. */
    public static Found best(List<Found> found) {
        return found.isEmpty() ? null : found.get(0);
    }

    /** One query per IPv4 network of the PC that has a broadcast address. */
    static List<Probe> probes(int port) throws IOException {
        List<Probe> probes = new ArrayList<>();
        Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
        while (ifaces != null && ifaces.hasMoreElements()) {
            NetworkInterface ni = ifaces.nextElement();
            if (!ni.isUp() || ni.isLoopback()) {
                continue;
            }
            for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                if (ia.getAddress() instanceof Inet4Address && ia.getBroadcast() != null) {
                    probes.add(new Probe(ia.getAddress(),
                            new InetSocketAddress(ia.getBroadcast(), port)));
                }
            }
        }
        return probes;
    }

    /** Sends every query at once and gathers the answers until {@code timeoutMs} runs out. */
    static List<Found> search(List<Probe> probes, long timeoutMs) throws IOException {
        List<Found> found = new ArrayList<>();
        List<DatagramChannel> channels = new ArrayList<>();
        try (Selector selector = Selector.open()) {
            for (Probe p : probes) {
                DatagramChannel ch = DatagramChannel.open(StandardProtocolFamily.INET);
                channels.add(ch);
                try {
                    ch.setOption(StandardSocketOptions.SO_BROADCAST, true);
                    ch.bind(new InetSocketAddress(p.local(), 0));
                    ch.configureBlocking(false);
                    ch.register(selector, SelectionKey.OP_READ, p);
                    ch.send(ByteBuffer.wrap(new byte[]{GroovyProtocol.CMD_GET_VERSION}),
                            p.target());
                } catch (IOException e) {
                    // a network that refuses the query has no MiSTer to find
                }
            }
            ByteBuffer buf = ByteBuffer.allocate(64);
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            long left;
            while ((left = deadline - System.nanoTime()) > 0) {
                if (selector.select(Math.max(1, TimeUnit.NANOSECONDS.toMillis(left))) == 0) {
                    continue;
                }
                for (SelectionKey key : selector.selectedKeys()) {
                    DatagramChannel ch = (DatagramChannel) key.channel();
                    Probe p = (Probe) key.attachment();
                    InetSocketAddress from;
                    while (true) {
                        buf.clear();
                        try {
                            from = (InetSocketAddress) ch.receive(buf);
                        } catch (IOException e) {
                            break;
                        }
                        if (from == null) {
                            break;
                        }
                        if (buf.position() == 1) {
                            add(found, new Found(from.getAddress(), p.local(), buf.get(0) & 0xFF));
                        }
                    }
                }
                selector.selectedKeys().clear();
            }
        } finally {
            for (DatagramChannel ch : channels) {
                ch.close();
            }
        }
        sortPreferred(found);
        return found;
    }

    /** Puts the MiSTers on a network without a router first, keeping the order otherwise. */
    static void sortPreferred(List<Found> found) {
        found.sort(Comparator.comparing((Found f) -> !f.linkLocal()));
    }

    private static void add(List<Found> found, Found f) {
        for (Found g : found) {
            if (g.mister().equals(f.mister())) {
                return;
            }
        }
        found.add(f);
    }
}
