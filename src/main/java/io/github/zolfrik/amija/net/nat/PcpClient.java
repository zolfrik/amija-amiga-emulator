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
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * PCP (RFC 6887), opcode {@code MAP}: asks the gateway for an inbound mapping, on UDP port 5351.
 *
 * <p>Every mapping carries a 96-bit nonce, kept for its renewals and its removal (lifetime 0),
 * and an answer counts only if it comes from the gateway asked and repeats nonce, protocol and
 * internal port. A gateway that only speaks NAT-PMP answers a PCP request with version 0:
 * {@link UnsupportedVersionException} says so, and the caller falls back to NAT-PMP.
 */
final class PcpClient {

    static final int PORT = 5351;

    private static final int VERSION = 2;
    private static final int OP_MAP = 1;
    private static final int REQUEST_SIZE = 24 + 36;
    private static final int MAX_ANSWER = 1100;
    private static final int[] RETRY_MS = { 250, 500, 1000, 2000 };

    /** Result codes used by the callers. */
    static final int RESULT_UNSUPP_VERSION = 1;
    static final int RESULT_NOT_AUTHORIZED = 2;
    static final int RESULT_NO_RESOURCES = 8;

    /** The gateway answered with another protocol version: it speaks NAT-PMP, not PCP. */
    static final class UnsupportedVersionException extends IOException {
        private static final long serialVersionUID = 1L;

        UnsupportedVersionException(int version) {
            super("the gateway answered with version " + version);
        }
    }

    /** A refusal of the gateway, with the protocol's result code. */
    static final class PcpException extends IOException {
        private static final long serialVersionUID = 1L;
        private final int result;

        PcpException(int result) {
            super("PCP result " + result + " (" + describe(result) + ")");
            this.result = result;
        }

        int result() {
            return result;
        }

        private static String describe(int r) {
            return switch (r) {
                case 1 -> "unsupported version";
                case 2 -> "not authorized";
                case 3 -> "malformed request";
                case 4 -> "unsupported opcode";
                case 7 -> "address mismatch";
                case 8 -> "no resources";
                case 11 -> "cannot provide external";
                default -> "error";
            };
        }
    }

    /** A mapping granted by the gateway. */
    record Granted(int externalPort, InetAddress externalAddress, int lifetimeSeconds) {
    }

    private final InetSocketAddress gateway;
    private final InetAddress local;
    private final byte[] nonceTcp = new byte[12];
    private final byte[] nonceUdp = new byte[12];

    /**
     * @param gateway the gateway, port included ({@link #PORT} in real life)
     * @param local   this machine's address toward the gateway: it is sent in the request
     *                and the socket is bound to it, as the protocol requires
     */
    PcpClient(InetSocketAddress gateway, InetAddress local) {
        this.gateway = gateway;
        this.local = local;
        SecureRandom rnd = new SecureRandom();
        rnd.nextBytes(nonceTcp);
        rnd.nextBytes(nonceUdp);
    }

    /**
     * Asks for (or renews, or with lifetime 0 removes) the mapping of an internal port.
     *
     * @param tcp               TCP or UDP
     * @param suggestedExternal external port suggested; the gateway may grant another
     */
    Granted map(boolean tcp, int internalPort, int suggestedExternal, int lifetimeSeconds)
            throws IOException {
        byte[] nonce = tcp ? nonceTcp : nonceUdp;
        int protocol = tcp ? 6 : 17;
        byte[] req = new byte[REQUEST_SIZE];
        req[0] = VERSION;
        req[1] = OP_MAP;
        NatPmpClient.putU32(req, 4, lifetimeSeconds);
        putAddress(req, 8, local);
        System.arraycopy(nonce, 0, req, 24, 12);
        req[36] = (byte) protocol;
        NatPmpClient.putU16(req, 40, internalPort);
        NatPmpClient.putU16(req, 42, suggestedExternal);
        // Suggested external address: all zeros (IPv4-mapped any), "whatever you have".
        req[54] = (byte) 0xFF;
        req[55] = (byte) 0xFF;

        try (DatagramSocket socket = local == null ? new DatagramSocket()
                : new DatagramSocket(new InetSocketAddress(local, 0))) {
            byte[] buffer = new byte[MAX_ANSWER];
            for (int wait : RETRY_MS) {
                socket.send(new DatagramPacket(req, req.length, gateway));
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
                    if (!gateway.equals(p.getSocketAddress()) || p.getLength() < 4) {
                        continue;
                    }
                    byte[] a = Arrays.copyOf(p.getData(), p.getLength());
                    int version = a[0] & 0xFF;
                    if (version != VERSION) {
                        // NAT-PMP gateways answer a PCP request with their version 0.
                        throw new UnsupportedVersionException(version);
                    }
                    if (a.length < REQUEST_SIZE || (a[1] & 0xFF) != (0x80 | OP_MAP)) {
                        continue;
                    }
                    if (!Arrays.equals(a, 24, 36, nonce, 0, 12) || (a[36] & 0xFF) != protocol
                            || NatPmpClient.u16(a, 40) != internalPort) {
                        continue;   // the answer to another request
                    }
                    int result = a[3] & 0xFF;
                    if (result != 0) {
                        throw new PcpException(result);
                    }
                    int lifetime = (int) NatPmpClient.u32(a, 4);
                    int external = NatPmpClient.u16(a, 42);
                    return new Granted(external, readAddress(a, 44), lifetime);
                }
            }
        }
        throw new SocketTimeoutException("no PCP answer from " + gateway);
    }

    /** Writes an address in the protocol's 16-byte form (IPv4 as IPv4-mapped IPv6). */
    private static void putAddress(byte[] b, int off, InetAddress a) {
        if (a == null) {
            return;
        }
        byte[] raw = a.getAddress();
        if (a instanceof Inet4Address) {
            b[off + 10] = (byte) 0xFF;
            b[off + 11] = (byte) 0xFF;
            System.arraycopy(raw, 0, b, off + 12, 4);
        } else {
            System.arraycopy(raw, 0, b, off, 16);
        }
    }

    private static InetAddress readAddress(byte[] b, int off) throws IOException {
        byte[] raw = Arrays.copyOfRange(b, off, off + 16);
        // getByAddress returns an Inet4Address for the IPv4-mapped form.
        return InetAddress.getByAddress(raw);
    }
}
