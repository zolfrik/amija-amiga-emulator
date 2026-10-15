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

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Handshake cryptography: from the <i>join code</i> to the session keys
 * ({@code docs/remote-play-plan.md} section 8).
 *
 * <h2>What it protects, and from what</h2>
 * A port is opened listening on someone's home PC and inputs that drive a machine travel over
 * it. The non-negotiable minimum of section 8: authentication comes <b>before anything
 * else</b>, and the code never travels in clear.
 *
 * <pre>
 *   master   = PBKDF2-HMAC-SHA256(joinCode, salt, {@value #PBKDF2_ROUNDS} rounds, 256 bits)
 *   guestTag = HMAC-SHA256(master, "AJ-guest" | nonce | nonce2 | proto)
 *   hostTag  = HMAC-SHA256(master, "AJ-host"  | nonce | nonce2 | proto)
 *   mediaKey = HKDF-Expand(master, "AJ-media" | nonce | nonce2, 32)
 * </pre>
 *
 * <h2>Three choices, and why</h2>
 * <ol>
 *   <li><b>The session key does not travel.</b> The control channel is plain TCP: sending the
 *       key there would give it away to anyone listening, defeating the very reason the media
 *       is encrypted. It is <b>derived</b> from the same material on both sides: nothing
 *       secret crosses the network.</li>
 *   <li><b>The host authenticates too.</b> With a shared secret mutual authentication costs a
 *       second HMAC, and without it anyone in the middle could pose as the host and collect
 *       the guest's inputs.</li>
 *   <li><b>PBKDF2 with {@value #PBKDF2_ROUNDS} rounds.</b> The join code has ~40 bits: few for a
 *       key. The slow derivation is what makes them awkward to try exhaustively, and the cost
 *       is paid once per session (~60 ms), not per packet.</li>
 * </ol>
 *
 * <p>The tag comparison uses {@link MessageDigest#isEqual}, which does not exit early: a naive
 * comparison would lose on one side what was gained on the other.
 */
public final class SessionCrypto {

    /** ~60 ms on a 2026 desktop machine: expensive for exhaustive guessing, not for us. */
    public static final int PBKDF2_ROUNDS = 120_000;

    public static final int SALT_BYTES = 16;
    public static final int NONCE_BYTES = 16;
    public static final int TAG_BYTES = 32;
    public static final int KEY_BYTES = 32;
    /** Prefix of the AES-GCM nonce, derived and not transmitted. */
    public static final int NONCE_PREFIX_BYTES = 4;

    private static final byte[] LABEL_GUEST = "AJ-guest".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] LABEL_HOST = "AJ-host".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] LABEL_MEDIA = "AJ-media".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] LABEL_NONCE = "AJ-nonce".getBytes(StandardCharsets.US_ASCII);

    private final byte[] master;

    private SessionCrypto(byte[] master) {
        this.master = master;
    }

    /** Derives the session material from the join code and the salt chosen by the host. */
    public static SessionCrypto derive(String joinCode, byte[] salt) {
        if (salt == null || salt.length != SALT_BYTES) {
            throw new IllegalArgumentException("salt di " + (salt == null ? "null" : salt.length)
                    + " byte, attesi " + SALT_BYTES);
        }
        try {
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            PBEKeySpec spec = new PBEKeySpec(JoinCode.normalise(joinCode).toCharArray(),
                    salt, PBKDF2_ROUNDS, KEY_BYTES * 8);
            byte[] key = f.generateSecret(spec).getEncoded();
            spec.clearPassword();
            return new SessionCrypto(key);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 non disponibile in questa JVM", e);
        }
    }

    public static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }

    /** Tag with which the guest proves it knows the code. */
    public byte[] guestTag(byte[] nonce, byte[] nonce2, int protocolVersion) {
        return hmac(master, LABEL_GUEST, nonce, nonce2, protocolVersion);
    }

    /** Tag with which the host proves the same to the guest. */
    public byte[] hostTag(byte[] nonce, byte[] nonce2, int protocolVersion) {
        return hmac(master, LABEL_HOST, nonce, nonce2, protocolVersion);
    }

    /** AES-GCM key for the media, derived: it never crosses the network. */
    public byte[] mediaKey(byte[] nonce, byte[] nonce2) {
        return hkdfExpand(master, concat(LABEL_MEDIA, nonce, nonce2), KEY_BYTES);
    }

    /** Prefix of the GCM nonce, derived as well. */
    public byte[] noncePrefix(byte[] nonce, byte[] nonce2) {
        return hkdfExpand(master, concat(LABEL_NONCE, nonce, nonce2), NONCE_PREFIX_BYTES);
    }

    /** Constant-time comparison: an {@code Arrays.equals} here would be an oracle. */
    public static boolean tagsMatch(byte[] a, byte[] b) {
        return MessageDigest.isEqual(a, b);
    }

    public void destroy() {
        Arrays.fill(master, (byte) 0);
    }

    // =====================================================================
    // Internal
    // =====================================================================

    private static byte[] hmac(byte[] key, byte[] label, byte[] nonce, byte[] nonce2, int proto) {
        if (nonce == null || nonce.length != NONCE_BYTES
                || nonce2 == null || nonce2.length != NONCE_BYTES) {
            throw new IllegalArgumentException("nonce di lunghezza sbagliata");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update(label);
            mac.update(nonce);
            mac.update(nonce2);
            mac.update(new byte[] { (byte) proto, (byte) (proto >>> 8),
                                    (byte) (proto >>> 16), (byte) (proto >>> 24) });
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 non disponibile in questa JVM", e);
        }
    }

    /**
     * HKDF-Expand (RFC 5869) over HMAC-SHA256, written by hand in a dozen lines.
     *
     * <p>The JDK exposes a recent KDF API, but here the constraint is "no new dependency
     * <i>and</i> no surprises": a function of this size, with an RFC behind it, is easier to
     * verify than to justify.
     */
    private static byte[] hkdfExpand(byte[] prk, byte[] info, int length) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            byte[] out = new byte[length];
            byte[] t = new byte[0];
            int pos = 0;
            for (int counter = 1; pos < length; counter++) {
                mac.reset();
                mac.update(t);
                mac.update(info);
                mac.update((byte) counter);
                t = mac.doFinal();
                int n = Math.min(t.length, length - pos);
                System.arraycopy(t, 0, out, pos, n);
                pos += n;
            }
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 non disponibile in questa JVM", e);
        }
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) {
            n += p.length;
        }
        byte[] out = new byte[n];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }
}
