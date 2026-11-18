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

package io.github.zolfrik.amija.net.serial;

import io.github.zolfrik.amija.net.ProtocolException;
import io.github.zolfrik.amija.net.SessionCrypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;

/**
 * AES-GCM seal of the frames of one direction of a stream. There is no sequence number on
 * the wire: TCP delivers in order, so both ends count, and the count is the nonce. A frame
 * dropped, repeated or altered on the way fails to open, because it is opened with a nonce it
 * was not sealed with.
 */
final class FrameCipher {

    static final int TAG_BYTES = 16;
    private static final int NONCE_BYTES = 12;

    private final SecretKeySpec key;
    private final Cipher seal;
    private final Cipher open;
    private final byte[] sealNonce = new byte[NONCE_BYTES];
    private final byte[] openNonce = new byte[NONCE_BYTES];
    private long sealCount;
    private long openCount;

    /** @param direction 0 at the host, 1 at the guest: the two directions never share a nonce */
    FrameCipher(byte[] keyBytes, byte[] noncePrefix, int direction) {
        if (noncePrefix.length != SessionCrypto.NONCE_PREFIX_BYTES) {
            throw new IllegalArgumentException("nonce prefix of the wrong length");
        }
        key = new SecretKeySpec(keyBytes, "AES");
        try {
            seal = Cipher.getInstance("AES/GCM/NoPadding");
            open = Cipher.getInstance("AES/GCM/NoPadding");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES/GCM not available in this JVM", e);
        }
        System.arraycopy(noncePrefix, 0, sealNonce, 0, noncePrefix.length);
        System.arraycopy(noncePrefix, 0, openNonce, 0, noncePrefix.length);
        // The top bit keeps these nonces apart from the ones a datagram cipher would make
        // from the same key and prefix.
        sealNonce[4] = (byte) (0x80 | direction);
        openNonce[4] = (byte) (0x80 | (direction ^ 1));
    }

    private static void count(byte[] nonce, long n) throws ProtocolException {
        if (n >= 1L << 56) throw new ProtocolException("frame count exhausted");
        for (int i = 0; i < 7; i++) nonce[5 + i] = (byte) (n >>> (8 * i));
    }

    /** Seals {@code len} bytes of {@code plain} into {@code dst}; returns the sealed length. */
    int seal(byte[] plain, int len, byte[] dst) throws ProtocolException {
        count(sealNonce, sealCount++);
        try {
            seal.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BYTES * 8, sealNonce));
            return seal.doFinal(plain, 0, len, dst, 0);
        } catch (GeneralSecurityException e) {
            throw new ProtocolException("encryption failed", e);
        }
    }

    /** Opens the next frame of the stream into {@code dst}; returns the plain length. */
    int open(byte[] src, int len, byte[] dst) throws ProtocolException {
        if (len < TAG_BYTES) throw new ProtocolException("frame of " + len + " bytes: the tag is missing");
        count(openNonce, openCount++);
        try {
            open.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BYTES * 8, openNonce));
            return open.doFinal(src, 0, len, dst, 0);
        } catch (GeneralSecurityException e) {
            throw new ProtocolException("frame not authentic: dropped");
        }
    }
}
