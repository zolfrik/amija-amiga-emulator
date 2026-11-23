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

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * STUN Binding (RFC 8489): asks a public server from which address and port it sees this
 * machine ({@code docs/remote-play-r1-plan.md} section 5).
 *
 * <p>The answer counts only if it comes from the server asked and carries the 96-bit
 * transaction id of the request; it is read attribute by attribute within a constant size cap,
 * and a success answer with an unknown comprehension-required attribute is discarded, as the
 * RFC prescribes. The server sees this machine's public address and nothing else.
 */
public final class StunClient {

    private static final int MAGIC = 0x2112A442;
    private static final int BINDING_REQUEST = 0x0001;
    private static final int BINDING_SUCCESS = 0x0101;
    private static final int ATTR_MAPPED_ADDRESS = 0x0001;
    private static final int ATTR_XOR_MAPPED_ADDRESS = 0x0020;
    private static final int MAX_MESSAGE = 1024;
    private static final int[] RETRY_MS = { 500, 1000, 2000 };

    /** Default servers: free, without an account. */
    public static final String DEFAULT_SERVERS = "stun.cloudflare.com:3478,stun.l.google.com:19302";

    /**
     * What the servers saw.
     *
     * @param publicAddress     the address and port the first server saw, or {@code null}
     * @param endpointDependent true if two servers saw the same address with different ports
     *                          from the same socket: the NAT maps per destination, which no
     *                          port opening by hand can cross (worth knowing, logged only)
     */
    public record Result(InetSocketAddress publicAddress, boolean endpointDependent) {
    }

    private StunClient() {
    }

    /**
     * Asks every server, from one socket, until two have answered.
     *
     * @param servers {@code host:port} entries; names are resolved here
     */
    public static Result probe(List<String> servers) {
        List<InetSocketAddress> seen = new ArrayList<>();
        try (DatagramSocket socket = new DatagramSocket()) {
            for (String entry : servers) {
                InetSocketAddress server = parse(entry);
                if (server == null) {
                    continue;
                }
                try {
                    seen.add(query(socket, server));
                    if (seen.size() == 2) {
                        break;
                    }
                } catch (IOException e) {
                    RemotePlaySettings.log("STUN %s: %s", entry, e.getMessage());
                }
            }
        } catch (IOException e) {
            RemotePlaySettings.log("STUN socket: %s", e.getMessage());
        }
        if (seen.isEmpty()) {
            return new Result(null, false);
        }
        boolean dependent = seen.size() == 2
                && seen.get(0).getAddress().equals(seen.get(1).getAddress())
                && seen.get(0).getPort() != seen.get(1).getPort();
        if (dependent) {
            RemotePlaySettings.say("STUN: the NAT maps per destination (%s, %s)",
                    seen.get(0), seen.get(1));
        }
        return new Result(seen.get(0), dependent);
    }

    /** A {@code host:port} entry, resolved; {@code null} if it does not resolve. */
    static InetSocketAddress parse(String entry) {
        String e = entry == null ? "" : entry.trim();
        if (e.isEmpty()) {
            return null;
        }
        int colon = e.lastIndexOf(':');
        String host = colon > 0 ? e.substring(0, colon) : e;
        int port = 3478;
        if (colon > 0) {
            try {
                port = Integer.parseInt(e.substring(colon + 1).trim());
            } catch (NumberFormatException x) {
                return null;
            }
        }
        try {
            // IPv4 only: the address wanted is the IPv4 one the NAT shows.
            for (InetAddress a : InetAddress.getAllByName(host)) {
                if (a instanceof java.net.Inet4Address) {
                    return new InetSocketAddress(a, port);
                }
            }
        } catch (IOException x) {
            // unresolvable: skipped
        }
        return null;
    }

    /** One Binding transaction with retransmission. */
    static InetSocketAddress query(DatagramSocket socket, InetSocketAddress server)
            throws IOException {
        byte[] tx = new byte[12];
        new SecureRandom().nextBytes(tx);
        byte[] req = new byte[20];
        req[0] = 0;
        req[1] = BINDING_REQUEST;
        putInt(req, 4, MAGIC);
        System.arraycopy(tx, 0, req, 8, 12);
        byte[] buffer = new byte[MAX_MESSAGE];
        for (int wait : RETRY_MS) {
            socket.send(new DatagramPacket(req, req.length, server));
            long deadline = System.nanoTime() + wait * 1_000_000L;
            while (true) {
                long left = (deadline - System.nanoTime()) / 1_000_000L;
                if (left <= 0) {
                    break;
                }
                socket.setSoTimeout((int) left);
                DatagramPacket p = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(p);
                } catch (SocketTimeoutException e) {
                    break;
                }
                if (!server.equals(p.getSocketAddress())) {
                    continue;
                }
                InetSocketAddress mapped = parseResponse(
                        Arrays.copyOf(p.getData(), p.getLength()), tx);
                if (mapped != null) {
                    return mapped;
                }
            }
        }
        throw new SocketTimeoutException("no STUN answer from " + server);
    }

    /**
     * The mapped address of a Binding success answer to transaction {@code tx}, or
     * {@code null} if the message is not that answer or cannot be trusted.
     */
    static InetSocketAddress parseResponse(byte[] m, byte[] tx) {
        if (m.length < 20 || m.length > MAX_MESSAGE || (m[0] & 0xC0) != 0) {
            return null;
        }
        int type = u16(m, 0);
        int length = u16(m, 2);
        if (type != BINDING_SUCCESS || getInt(m, 4) != MAGIC || length % 4 != 0
                || 20 + length != m.length || !Arrays.equals(m, 8, 20, tx, 0, 12)) {
            return null;
        }
        InetSocketAddress xor = null;
        InetSocketAddress plain = null;
        int p = 20;
        while (p + 4 <= m.length) {
            int at = u16(m, p);
            int len = u16(m, p + 2);
            int value = p + 4;
            if (value + len > m.length) {
                return null;
            }
            if (at == ATTR_XOR_MAPPED_ADDRESS) {
                xor = address(m, value, len, true);
            } else if (at == ATTR_MAPPED_ADDRESS) {
                plain = address(m, value, len, false);
            } else if (at < 0x8000) {
                return null;   // unknown and comprehension-required: the answer is discarded
            }
            p = value + ((len + 3) & ~3);
        }
        return xor != null ? xor : plain;
    }

    private static InetSocketAddress address(byte[] m, int off, int len, boolean xored) {
        if (len < 8) {
            return null;
        }
        int family = m[off + 1] & 0xFF;
        int port = u16(m, off + 2);
        int size = family == 1 ? 4 : family == 2 ? 16 : -1;
        if (size < 0 || len < 4 + size) {
            return null;
        }
        byte[] a = Arrays.copyOfRange(m, off + 4, off + 4 + size);
        if (xored) {
            port ^= MAGIC >>> 16;
            // The address is XORed with the magic cookie, then (IPv6) with the transaction id.
            for (int i = 0; i < size; i++) {
                a[i] ^= m[4 + i];
            }
        }
        try {
            return new InetSocketAddress(InetAddress.getByAddress(a), port & 0xFFFF);
        } catch (IOException e) {
            return null;
        }
    }

    private static int u16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static int getInt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }
}
