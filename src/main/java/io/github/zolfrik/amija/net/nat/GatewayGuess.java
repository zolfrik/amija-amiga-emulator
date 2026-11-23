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
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * Where the default gateway probably is ({@code docs/remote-play-r1-plan.md} section 3.2).
 *
 * <p>The JDK does not expose the routing table and no system command is run, so the guess is
 * the first host address of every active IPv4 subnet ({@code x.y.z.1} on a /24): the address
 * home routers take. A wrong guess costs one unanswered request.
 */
final class GatewayGuess {

    /** A guessed gateway and this machine's address on the same subnet. */
    record Candidate(InetAddress gateway, InetAddress local) {
    }

    private GatewayGuess() {
    }

    /** The guesses for the interfaces of this machine. */
    static List<Candidate> candidates() {
        List<Candidate> list = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            while (nics.hasMoreElements()) {
                NetworkInterface nic = nics.nextElement();
                if (!nic.isUp() || nic.isLoopback()) {
                    continue;
                }
                for (InterfaceAddress ia : nic.getInterfaceAddresses()) {
                    Candidate c = guess(ia.getAddress(), ia.getNetworkPrefixLength());
                    if (c != null) {
                        list.add(c);
                    }
                }
            }
        } catch (IOException e) {
            // No interfaces, no guesses.
        }
        return list;
    }

    /**
     * The first host of the subnet of {@code local}, or {@code null} when there is none worth
     * asking: not IPv4, a link-local address, a /31 or /32, or this machine itself.
     */
    static Candidate guess(InetAddress local, int prefix) {
        if (!(local instanceof Inet4Address) || local.isLinkLocalAddress()
                || local.isLoopbackAddress() || prefix < 8 || prefix > 30) {
            return null;
        }
        byte[] b = local.getAddress();
        int ip = ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
        int mask = ~((1 << (32 - prefix)) - 1);
        int first = (ip & mask) + 1;
        if (first == ip) {
            return null;   // this machine is the gateway of its own subnet (a virtual switch)
        }
        try {
            InetAddress gw = InetAddress.getByAddress(new byte[] {
                (byte) (first >>> 24), (byte) (first >>> 16), (byte) (first >>> 8), (byte) first });
            return new Candidate(gw, local);
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
