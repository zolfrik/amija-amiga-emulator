/*
 * AmiJa - a cycle-exact Amiga 500 emulator in Java.
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

/**
 * What kind of IPv4 address the router declares toward the world ({@code docs/remote-play-plan.md}
 * section 7.3).
 *
 * <h2>Why this diagnosis is what counts</h2>
 * The most common way remote play over the Internet will fail is not a rude router: it is
 * <b>CGNAT</b>. The operator puts the customer behind its own NAT, the router gets its mapping
 * and declares it successful, but its "external address" is itself private and nothing arrives
 * from outside. Without this check the user would see "port open" and then a "Connect" timing
 * out, and would look for the problem in the wrong place (usually the firewall, which has
 * nothing to do with it).
 *
 * <p>It is recognised from the address alone, without asking any external service:
 * <b>100.64.0.0/10</b> is the space RFC 6598 reserves for exactly this, and an RFC 1918 address
 * in place of the public one says the same (a router cascaded to another router, which from
 * the player's point of view is the same problem).
 *
 * <h2>The text is parsed by hand, on purpose</h2>
 * The string comes from a network device, so it is not passed to {@code InetAddress.getByName}:
 * faced with something that is not an address, that method <b>does a DNS lookup</b>. It would
 * be a network request toward a name chosen by whoever answered the M-SEARCH, to answer a
 * question of pure arithmetic.
 */
public final class NatAddress {

    private NatAddress() {
    }

    /** True if the address is in the carrier-grade NAT space ({@code 100.64.0.0/10}). */
    public static boolean isCarrierGrade(String address) {
        int[] o = parse(address);
        return o != null && o[0] == 100 && o[1] >= 64 && o[1] <= 127;
    }

    /** True for RFC 1918 addresses, loopback, link-local and {@code 0.0.0.0}. */
    public static boolean isPrivate(String address) {
        int[] o = parse(address);
        if (o == null) {
            return false;
        }
        return o[0] == 10                                   // 10.0.0.0/8
                || (o[0] == 172 && o[1] >= 16 && o[1] <= 31) // 172.16.0.0/12
                || (o[0] == 192 && o[1] == 168)              // 192.168.0.0/16
                || o[0] == 127                               // loopback
                || (o[0] == 169 && o[1] == 254)              // link-local
                || o[0] == 0;
    }

    /**
     * True if another player, from the Internet, can really reach this address.
     *
     * <p>That is: a valid IPv4, not private and not CGNAT. It does not prove the packets arrive
     * (a firewall can still stop them, section 7.4), but it proves arriving is possible.
     */
    public static boolean isReachable(String address) {
        return parse(address) != null && !isPrivate(address) && !isCarrierGrade(address);
    }

    /** The four octets, or {@code null} if it is not an IPv4 in dotted decimal form. */
    private static int[] parse(String address) {
        if (address == null) {
            return null;
        }
        String[] parts = address.trim().split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            if (parts[i].isEmpty() || parts[i].length() > 3) {
                return null;
            }
            int value = 0;
            for (int c = 0; c < parts[i].length(); c++) {
                char digit = parts[i].charAt(c);
                if (digit < '0' || digit > '9') {
                    return null;
                }
                value = value * 10 + (digit - '0');
            }
            if (value > 255) {
                return null;
            }
            octets[i] = value;
        }
        return octets;
    }
}
