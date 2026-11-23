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

package io.github.zolfrik.amija.net;

import java.io.ByteArrayOutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The invitation: join code, role and every address the host can be reached at, in one text
 * to paste ({@code docs/remote-play-r1-plan.md} section 1.1).
 *
 * <p>Text form: {@code AMJ-} followed by the bytes in Crockford base32 (the alphabet of
 * {@link JoinCode}), in groups of four separated by dashes. The parser ignores dashes, spaces,
 * line breaks and case, and maps the typographic confusions back like {@link JoinCode}.
 *
 * <p>Binary content, big-endian:
 * <pre>
 *   u8   version (4 bits) | role (2 bits) | reserved (2)
 *   40b  join code (its 8 base32 symbols are exactly 40 bits)
 *   u8   number of candidates (1..6)
 *   per candidate:
 *     u8   family (1 bit: v4/v6) | scope (2 bits) | same port (1) | reserved (4)
 *     4|16 address
 *     u16  TCP port
 *     u16  UDP port       (absent with "same port")
 *   u16  CRC-16/CCITT of everything before it
 * </pre>
 *
 * <p>The CRC tells "copied wrong" apart from "host unreachable" before any network is touched.
 * The invitation holds the join code, so it is a secret: it is shown and copied, never saved.
 */
public record InviteCode(int role, String joinCode, List<Candidate> candidates) {

    /** Format version written by this code. */
    public static final int VERSION = 1;

    public static final int ROLE_PLAYER = 0;
    public static final int ROLE_SPECTATOR = 1;
    public static final int ROLE_NULL_MODEM = 2;

    /** Most candidates an invitation may carry. */
    public static final int MAX_CANDIDATES = 6;

    /** Longest text accepted, before any decoding. */
    public static final int MAX_TEXT = 200;

    private static final String PREFIX = "AMJ";
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final int CODE_BYTES = 5;
    /** The shortest invitation: header, code, count, one IPv4 candidate, CRC. */
    private static final int MIN_BYTES = 1 + CODE_BYTES + 1 + 1 + 4 + 2 + 2;
    /** Base32 symbols of the shortest invitation. */
    private static final int MIN_SYMBOLS = (MIN_BYTES * 8 + 4) / 5;
    private static final java.util.regex.Pattern DASHED_SHAPE = java.util.regex.Pattern.compile(
            "(?i)" + PREFIX + "(-[0-9A-Z]{4}){2,}(-[0-9A-Z]{0,4})?");
    private static final java.util.regex.Pattern UNDASHED_SHAPE = java.util.regex.Pattern.compile(
            "(?i)" + PREFIX + "[0-9A-Z-]+");

    /** Where an address leads, which decides when the guest tries it. */
    public enum Scope {
        /** The host's own local network. */
        LAN,
        /** A public address whose port is known to be open (router mapping, open pinhole). */
        PUBLIC_VERIFIED,
        /** A public address nobody confirmed is reachable. */
        PUBLIC_UNVERIFIED,
        /** A virtual network interface (VPN overlay). */
        OVERLAY
    }

    /** One way to reach the host. */
    public record Candidate(InetAddress address, Scope scope, int tcpPort, int udpPort) {
        public Candidate {
            if (address == null || scope == null) {
                throw new IllegalArgumentException("candidate without address or scope");
            }
            checkPort(tcpPort);
            checkPort(udpPort);
        }

        /** A candidate whose TCP and UDP ports are the same number. */
        public Candidate(InetAddress address, Scope scope, int port) {
            this(address, scope, port, port);
        }

        public boolean isIpv6() {
            return address instanceof Inet6Address;
        }

        /** Address and TCP port in the usual text form, brackets around IPv6. */
        public String endpoint() {
            String host = address.getHostAddress();
            int percent = host.indexOf('%');
            if (percent >= 0) {
                host = host.substring(0, percent);
            }
            return (isIpv6() ? "[" + host + "]" : host) + ":" + tcpPort;
        }
    }

    /** Why a text is not a usable invitation. */
    public enum Problem {
        /** Not an invitation at all (no prefix, foreign characters, too long). */
        MALFORMED,
        /** The checksum does not match: copied or pasted incompletely. */
        CORRUPTED,
        /** Written by another version of AmiJa. */
        VERSION
    }

    /** The text is not a usable invitation; {@link #problem()} says why. */
    public static final class InvalidInviteException extends Exception {
        private static final long serialVersionUID = 1L;
        private final Problem problem;

        public InvalidInviteException(Problem problem, String message) {
            super(message);
            this.problem = problem;
        }

        public Problem problem() {
            return problem;
        }
    }

    public InviteCode {
        if (role < ROLE_PLAYER || role > ROLE_NULL_MODEM) {
            throw new IllegalArgumentException("unknown role: " + role);
        }
        if (!JoinCode.isWellFormed(joinCode)) {
            throw new IllegalArgumentException("malformed join code");
        }
        if (candidates == null || candidates.isEmpty() || candidates.size() > MAX_CANDIDATES) {
            throw new IllegalArgumentException("an invitation carries 1.."
                    + MAX_CANDIDATES + " candidates");
        }
        joinCode = JoinCode.normalise(joinCode);
        candidates = List.copyOf(candidates);
    }

    /** The join code with the dash in the middle, as {@link JoinCode#generate()} writes it. */
    public String displayJoinCode() {
        return joinCode.substring(0, 4) + "-" + joinCode.substring(4);
    }

    /**
     * True if the text looks like an invitation, whether or not it is a valid one. Decides
     * between "a broken invitation" and "an address" for what the user pasted. Two shapes
     * count, case and whitespace ignored: the prefix followed by at least two dashed groups
     * of four symbols (what {@link #format} writes, even cut short), or the prefix followed
     * by letters and digits only, as many as the shortest invitation holds (one that lost its
     * dashes). A host name that merely begins with the same letters ({@code amj-pc:7788},
     * {@code amjhost.local}, {@code amjserver}) is neither, and stays an address.
     */
    public static boolean looksLikeInvite(String text) {
        if (text == null) {
            return false;
        }
        String t = text.replaceAll("\\s+", "");
        if (DASHED_SHAPE.matcher(t).matches()) {
            return true;
        }
        return UNDASHED_SHAPE.matcher(t).matches()
                && t.replace("-", "").length() >= PREFIX.length() + MIN_SYMBOLS;
    }

    // =====================================================================
    // Writing
    // =====================================================================

    /** The text to paste: {@code AMJ-XXXX-XXXX-...}. */
    public String format() {
        byte[] payload = encode();
        String symbols = toBase32(payload);
        StringBuilder sb = new StringBuilder(PREFIX.length() + symbols.length() * 5 / 4 + 1);
        sb.append(PREFIX);
        for (int i = 0; i < symbols.length(); i++) {
            if (i % 4 == 0) {
                sb.append('-');
            }
            sb.append(symbols.charAt(i));
        }
        return sb.toString();
    }

    private byte[] encode() {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64);
        out.write((VERSION << 4) | (role << 2));
        out.write(codeBytes(joinCode), 0, CODE_BYTES);
        out.write(candidates.size());
        for (Candidate c : candidates) {
            boolean v6 = c.isIpv6();
            boolean samePort = c.tcpPort() == c.udpPort();
            out.write((v6 ? 0x80 : 0) | (c.scope().ordinal() << 5) | (samePort ? 0x10 : 0));
            byte[] a = c.address().getAddress();
            out.write(a, 0, a.length);
            writeU16(out, c.tcpPort());
            if (!samePort) {
                writeU16(out, c.udpPort());
            }
        }
        byte[] body = out.toByteArray();
        writeU16(out, crc16(body, body.length));
        return out.toByteArray();
    }

    private static void writeU16(ByteArrayOutputStream out, int v) {
        out.write(v >>> 8);
        out.write(v);
    }

    private static byte[] codeBytes(String normalisedCode) {
        long bits = 0;
        for (int i = 0; i < normalisedCode.length(); i++) {
            bits = (bits << 5) | symbolValue(normalisedCode.charAt(i));
        }
        byte[] b = new byte[CODE_BYTES];
        for (int i = CODE_BYTES - 1; i >= 0; i--) {
            b[i] = (byte) bits;
            bits >>>= 8;
        }
        return b;
    }

    private static String codeText(byte[] b, int off) {
        long bits = 0;
        for (int i = 0; i < CODE_BYTES; i++) {
            bits = (bits << 8) | (b[off + i] & 0xFF);
        }
        char[] c = new char[8];
        for (int i = 7; i >= 0; i--) {
            c[i] = ALPHABET[(int) (bits & 31)];
            bits >>>= 5;
        }
        return new String(c);
    }

    // =====================================================================
    // Reading
    // =====================================================================

    /**
     * Reads a pasted invitation.
     *
     * @throws InvalidInviteException with the reason: not an invitation, damaged, or from
     *                                another version
     */
    public static InviteCode parse(String text) throws InvalidInviteException {
        if (text == null || text.length() > MAX_TEXT) {
            throw new InvalidInviteException(Problem.MALFORMED, "not an invitation");
        }
        String n = JoinCode.normalise(text.replaceAll("\\s+", ""));
        if (!n.startsWith(PREFIX)) {
            throw new InvalidInviteException(Problem.MALFORMED, "not an invitation");
        }
        byte[] b = fromBase32(n.substring(PREFIX.length()));
        if (b.length < MIN_BYTES) {
            throw new InvalidInviteException(Problem.CORRUPTED, "invitation too short");
        }
        int version = (b[0] & 0xFF) >>> 4;
        int crcAt = b.length - 2;
        int crc = ((b[crcAt] & 0xFF) << 8) | (b[crcAt + 1] & 0xFF);
        if (crc != crc16(b, crcAt)) {
            // A future version may lay the body out differently, but it keeps the CRC at the
            // end: a matching CRC with an unknown version is a version problem, not damage.
            throw new InvalidInviteException(Problem.CORRUPTED, "checksum mismatch");
        }
        if (version != VERSION) {
            throw new InvalidInviteException(Problem.VERSION,
                    "invitation version " + version + ", this AmiJa reads " + VERSION);
        }
        int role = ((b[0] & 0xFF) >>> 2) & 3;
        if (role > ROLE_NULL_MODEM) {
            throw new InvalidInviteException(Problem.MALFORMED, "unknown role " + role);
        }
        String code = codeText(b, 1);
        int p = 1 + CODE_BYTES;
        int count = b[p++] & 0xFF;
        if (count < 1 || count > MAX_CANDIDATES) {
            throw new InvalidInviteException(Problem.MALFORMED,
                    count + " candidates, allowed 1.." + MAX_CANDIDATES);
        }
        List<Candidate> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            if (p >= crcAt) {
                throw new InvalidInviteException(Problem.MALFORMED, "candidate list truncated");
            }
            int flags = b[p++] & 0xFF;
            boolean v6 = (flags & 0x80) != 0;
            Scope scope = Scope.values()[(flags >>> 5) & 3];
            boolean samePort = (flags & 0x10) != 0;
            int need = (v6 ? 16 : 4) + 2 + (samePort ? 0 : 2);
            if (p + need > crcAt) {
                throw new InvalidInviteException(Problem.MALFORMED, "candidate list truncated");
            }
            byte[] raw = new byte[v6 ? 16 : 4];
            System.arraycopy(b, p, raw, 0, raw.length);
            p += raw.length;
            int tcp = ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
            p += 2;
            int udp = tcp;
            if (!samePort) {
                udp = ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
                p += 2;
            }
            if (tcp == 0 || udp == 0) {
                throw new InvalidInviteException(Problem.MALFORMED, "port 0 in a candidate");
            }
            InetAddress address;
            try {
                address = InetAddress.getByAddress(raw);
            } catch (UnknownHostException e) {
                throw new InvalidInviteException(Problem.MALFORMED, "bad address length");
            }
            // An IPv4-mapped IPv6 is returned by the JDK as an Inet4Address: harmless, it is
            // the same host either way.
            list.add(new Candidate(address, scope, tcp, udp));
        }
        if (p != crcAt) {
            throw new InvalidInviteException(Problem.MALFORMED,
                    (crcAt - p) + " bytes left over after the candidates");
        }
        return new InviteCode(role, code, list);
    }

    // =====================================================================
    // Base32 and CRC
    // =====================================================================

    private static String toBase32(byte[] data) {
        StringBuilder sb = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte v : data) {
            buffer = (buffer << 8) | (v & 0xFF);
            bits += 8;
            while (bits >= 5) {
                sb.append(ALPHABET[(buffer >>> (bits - 5)) & 31]);
                bits -= 5;
            }
        }
        if (bits > 0) {
            sb.append(ALPHABET[(buffer << (5 - bits)) & 31]);
        }
        return sb.toString();
    }

    private static byte[] fromBase32(String symbols) throws InvalidInviteException {
        byte[] out = new byte[symbols.length() * 5 / 8];
        int buffer = 0;
        int bits = 0;
        int n = 0;
        for (int i = 0; i < symbols.length(); i++) {
            int v = symbolValue(symbols.charAt(i));
            if (v < 0) {
                throw new InvalidInviteException(Problem.MALFORMED,
                        "character '" + symbols.charAt(i) + "' is not part of an invitation");
            }
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out[n++] = (byte) (buffer >>> (bits - 8));
                bits -= 8;
            }
        }
        // The writer pads the last symbol with fewer than five zero bits. Anything else is a
        // symbol too many or a damaged last one, which the CRC cannot see.
        if (bits >= 5 || (buffer & ((1 << bits) - 1)) != 0) {
            throw new InvalidInviteException(Problem.CORRUPTED, "trailing symbols");
        }
        return out;
    }

    private static int symbolValue(char c) {
        char u = Character.toUpperCase(c);
        for (int i = 0; i < ALPHABET.length; i++) {
            if (ALPHABET[i] == u) {
                return i;
            }
        }
        return -1;
    }

    /** CRC-16/CCITT-FALSE: polynomial 0x1021, initial value 0xFFFF. */
    static int crc16(byte[] data, int length) {
        int crc = 0xFFFF;
        for (int i = 0; i < length; i++) {
            crc ^= (data[i] & 0xFF) << 8;
            for (int k = 0; k < 8; k++) {
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
            }
        }
        return crc & 0xFFFF;
    }

    private static void checkPort(int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
    }

    @Override
    public String toString() {
        // Never the code: an invitation in a log line is a leaked secret.
        StringBuilder sb = new StringBuilder("InviteCode[role=").append(role).append(", ");
        for (Candidate c : candidates) {
            sb.append(c.scope().name().toLowerCase(Locale.ROOT)).append(' ')
                    .append(c.endpoint()).append(' ');
        }
        return sb.append(']').toString();
    }
}
