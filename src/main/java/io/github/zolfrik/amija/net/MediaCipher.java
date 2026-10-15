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

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;

/**
 * Per-packet seal on the media channel ({@code docs/remote-play-plan.md} section 8).
 *
 * <h2>The main benefit is not the confidentiality of the pixels</h2>
 * It is that the <b>tag authenticates every packet</b>: without it, anyone knowing address and
 * port could inject fake inputs into a running session, i.e. drive someone else's machine
 * knowing two numbers seen passing on the network. With the tag, a packet not produced by
 * whoever knows the join code is not even parsed.
 *
 * <h2>The nonce, the only thing that can really go wrong</h2>
 * AES-GCM loses every guarantee if a nonce repeats under the same key. Here the nonce is
 * <b>built, not chosen</b>: {@code prefix(4, derived) | direction(1) | seq(4) | 000}. The
 * direction separates the two ways, {@code seq} is monotonic per way, and the key is new at
 * every session. Only one way to break it remains (wrapping {@code seq}), and that is why
 * {@link #seal} <b>refuses to continue</b> instead of wrapping: 2^32 packets are ~10 days of
 * uninterrupted play, so the session dies with a clear message in a case nobody will ever
 * see, instead of degrading silently in a case that would compromise everything.
 *
 * <p>There is also the {@link #plaintext()} implementation: it is for <b>measuring</b> what the
 * encryption costs (section 15.2), not for use.
 *
 * <p>Each instance holds two distinct {@link Cipher}s, one for {@link #seal} and one for
 * {@link #open}: they are not thread-safe, and the two directions live on different threads.
 */
public interface MediaCipher {

    /** Bytes the seal adds to the plaintext payload. */
    int overhead();

    /** Name for the logs and diagnostics. */
    String name();

    /**
     * Writes header + sealed payload into {@code dst}.
     *
     * @return the total length of the datagram
     */
    int seal(int type, int seq, byte[] plain, int off, int len, byte[] dst)
            throws ProtocolException;

    /**
     * Checks and opens a received datagram, writing the plaintext into {@code dst}.
     *
     * @return the length of the plaintext
     * @throws ProtocolException if the header is not valid or the tag does not match
     */
    int open(byte[] src, int length, byte[] dst) throws ProtocolException;

    /** AES-GCM seal. {@code direction} tells the two directions apart in the nonce: 0 host, 1 guest. */
    static MediaCipher aesGcm(byte[] key, byte[] noncePrefix, int direction) {
        return new AesGcm(key, noncePrefix, direction);
    }

    /** No seal: only for measuring the cost of the real one. */
    static MediaCipher plaintext() {
        return new Plain();
    }

    // =====================================================================

    final class AesGcm implements MediaCipher {

        private static final int NONCE_BYTES = 12;

        private final Cipher sealCipher;
        private final Cipher openCipher;
        private final SecretKeySpec key;
        private final byte[] sealNonce = new byte[NONCE_BYTES];
        private final byte[] openNonce = new byte[NONCE_BYTES];
        private final int direction;
        private boolean exhausted;

        AesGcm(byte[] keyBytes, byte[] noncePrefix, int direction) {
            if (noncePrefix.length != SessionCrypto.NONCE_PREFIX_BYTES) {
                throw new IllegalArgumentException("prefisso di nonce di lunghezza sbagliata");
            }
            this.key = new SecretKeySpec(keyBytes, "AES");
            this.direction = direction;
            try {
                this.sealCipher = Cipher.getInstance("AES/GCM/NoPadding");
                this.openCipher = Cipher.getInstance("AES/GCM/NoPadding");
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("AES/GCM non disponibile in questa JVM", e);
            }
            System.arraycopy(noncePrefix, 0, sealNonce, 0, noncePrefix.length);
            System.arraycopy(noncePrefix, 0, openNonce, 0, noncePrefix.length);
            sealNonce[4] = (byte) direction;
            openNonce[4] = (byte) (direction ^ 1);   // l'altro verso
        }

        @Override
        public int overhead() {
            return RemotePlayProtocol.GCM_TAG;
        }

        @Override
        public String name() {
            return "AES-GCM";
        }

        @Override
        public int seal(int type, int seq, byte[] plain, int off, int len, byte[] dst)
                throws ProtocolException {
            if (exhausted) {
                throw new ProtocolException("sequenza esaurita: la sessione va rinegoziata");
            }
            if (seq == -1) {
                // 0xFFFFFFFF is the last representable value: beyond it would wrap, and
                // a reused nonce is worth more than a lost session.
                exhausted = true;
            }
            RemotePlayProtocol.writeHeader(dst, type, 0, seq);
            writeSeq(sealNonce, seq);
            try {
                sealCipher.init(Cipher.ENCRYPT_MODE, key,
                        new GCMParameterSpec(RemotePlayProtocol.GCM_TAG * 8, sealNonce));
                sealCipher.updateAAD(dst, 0, RemotePlayProtocol.DATAGRAM_HEADER);
                int n = sealCipher.doFinal(plain, off, len, dst,
                        RemotePlayProtocol.DATAGRAM_HEADER);
                return RemotePlayProtocol.DATAGRAM_HEADER + n;
            } catch (GeneralSecurityException e) {
                throw new ProtocolException("cifratura fallita", e);
            }
        }

        @Override
        public int open(byte[] src, int length, byte[] dst) throws ProtocolException {
            RemotePlayProtocol.checkHeader(src, length);
            int sealed = length - RemotePlayProtocol.DATAGRAM_HEADER;
            if (sealed < RemotePlayProtocol.GCM_TAG) {
                throw new ProtocolException("payload di " + sealed + " byte: manca il tag");
            }
            writeSeq(openNonce, RemotePlayProtocol.headerSeq(src));
            try {
                openCipher.init(Cipher.DECRYPT_MODE, key,
                        new GCMParameterSpec(RemotePlayProtocol.GCM_TAG * 8, openNonce));
                openCipher.updateAAD(src, 0, RemotePlayProtocol.DATAGRAM_HEADER);
                return openCipher.doFinal(src, RemotePlayProtocol.DATAGRAM_HEADER, sealed, dst, 0);
            } catch (GeneralSecurityException e) {
                // "Wrong tag" is not told apart from "wrong key": the message must say what to
                // do (discard), not help whoever is trying to guess.
                throw new ProtocolException("pacchetto non autentico: scartato");
            }
        }

        private static void writeSeq(byte[] nonce, int seq) {
            nonce[5] = (byte) seq;
            nonce[6] = (byte) (seq >>> 8);
            nonce[7] = (byte) (seq >>> 16);
            nonce[8] = (byte) (seq >>> 24);
        }
    }

    // =====================================================================

    final class Plain implements MediaCipher {

        @Override
        public int overhead() {
            return 0;
        }

        @Override
        public String name() {
            return "in chiaro";
        }

        @Override
        public int seal(int type, int seq, byte[] plain, int off, int len, byte[] dst) {
            RemotePlayProtocol.writeHeader(dst, type, 0, seq);
            System.arraycopy(plain, off, dst, RemotePlayProtocol.DATAGRAM_HEADER, len);
            return RemotePlayProtocol.DATAGRAM_HEADER + len;
        }

        @Override
        public int open(byte[] src, int length, byte[] dst) throws ProtocolException {
            RemotePlayProtocol.checkHeader(src, length);
            int n = length - RemotePlayProtocol.DATAGRAM_HEADER;
            System.arraycopy(src, RemotePlayProtocol.DATAGRAM_HEADER, dst, 0, n);
            return n;
        }
    }
}
