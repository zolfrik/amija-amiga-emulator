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
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;

/**
 * NAT-PMP (RFC 6886): the older port mapping protocol of the gateway, on UDP port 5351.
 *
 * <p>Two requests are used: the external address (opcode 0) and a mapping (opcode 1 for UDP,
 * 2 for TCP; lifetime 0 removes it). Every answer must come from the gateway that was asked,
 * with the version, the opcode and, for a mapping, the internal port of the request; anything
 * else is ignored, so a stranger on the network cannot answer in its place.
 */
final class NatPmpClient {

    /** The port of the gateway, fixed by the protocol. */
    static final int PORT = 5351;

    /** Waits between retransmissions: the RFC starts at 250 ms and doubles. */
    private static final int[] RETRY_MS = { 250, 500, 1000, 2000 };

    private static final int OP_ADDRESS = 0;
    private static final int OP_MAP_UDP = 1;
    private static final int OP_MAP_TCP = 2;

    /** A refusal of the gateway, with the protocol's result code. */
    static final class NatPmpException extends IOException {
        private static final long serialVersionUID = 1L;
        private final int result;

        NatPmpException(int result) {
            super("NAT-PMP result " + result + " (" + describe(result) + ")");
            this.result = result;
        }

        int result() {
            return result;
        }

        private static String describe(int r) {
            return switch (r) {
                case 1 -> "unsupported version";
                case 2 -> "not authorized";
                case 3 -> "network failure";
                case 4 -> "out of resources";
                case 5 -> "unsupported opcode";
                default -> "unknown";
            };
        }
    }

    /** A mapping granted by the gateway. */
    record Granted(int internalPort, int externalPort, int lifetimeSeconds) {
    }

    private final InetSocketAddress gateway;
    private final InetAddress local;

    /**
     * @param gateway the gateway, port included ({@link #PORT} in real life)
     * @param local   the local address to send from, {@code null} for any
     */
    NatPmpClient(InetSocketAddress gateway, InetAddress local) {
        this.gateway = gateway;
        this.local = local;
    }

    /** The gateway's external IPv4 address. */
    InetAddress externalAddress() throws IOException {
        byte[] answer = exchange(new byte[] { 0, OP_ADDRESS }, OP_ADDRESS, -1);
        if (answer.length < 12) {
            throw new IOException("NAT-PMP address answer too short");
        }
        byte[] ip = { answer[8], answer[9], answer[10], answer[11] };
        return InetAddress.getByAddress(ip);
    }

    /**
     * Asks for a mapping; {@code lifetimeSeconds} 0 with {@code suggestedExternal} 0 removes
     * it. The gateway may grant another external port than the one suggested.
     */
    Granted map(boolean tcp, int internalPort, int suggestedExternal, int lifetimeSeconds)
            throws IOException {
        int op = tcp ? OP_MAP_TCP : OP_MAP_UDP;
        byte[] req = new byte[12];
        req[1] = (byte) op;
        putU16(req, 4, internalPort);
        putU16(req, 6, suggestedExternal);
        putU32(req, 8, lifetimeSeconds);
        byte[] answer = exchange(req, op, internalPort);
        if (answer.length < 16) {
            throw new IOException("NAT-PMP mapping answer too short");
        }
        return new Granted(u16(answer, 8), u16(answer, 10), (int) u32(answer, 12));
    }

    /**
     * Sends with retransmission and returns the first valid answer.
     *
     * @param internalPort for a mapping, the port the answer must carry; -1 otherwise
     */
    private byte[] exchange(byte[] request, int op, int internalPort) throws IOException {
        try (DatagramSocket socket = local == null ? new DatagramSocket()
                : new DatagramSocket(new InetSocketAddress(local, 0))) {
            byte[] buffer = new byte[64];
            for (int wait : RETRY_MS) {
                socket.send(new DatagramPacket(request, request.length, gateway));
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
                    if (!gateway.equals(p.getSocketAddress()) || p.getLength() < 8) {
                        continue;   // not the gateway asked, or not an answer at all
                    }
                    byte[] a = java.util.Arrays.copyOf(p.getData(), p.getLength());
                    if ((a[0] & 0xFF) != 0 || (a[1] & 0xFF) != op + 128) {
                        continue;
                    }
                    int result = u16(a, 2);
                    if (internalPort >= 0 && a.length >= 10 && u16(a, 8) != internalPort) {
                        continue;   // an answer to another request
                    }
                    if (result != 0) {
                        throw new NatPmpException(result);
                    }
                    return a;
                }
            }
        }
        throw new SocketTimeoutException("no NAT-PMP answer from " + gateway);
    }

    static int u16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    static long u32(byte[] b, int off) {
        return ((long) (b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    static void putU16(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 8);
        b[off + 1] = (byte) v;
    }

    static void putU32(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }
}
