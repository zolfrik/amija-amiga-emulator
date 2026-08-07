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

package io.github.zolfrik.amija.net;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;

/**
 * Sigillo per pacchetto sul canale media ({@code docs/remote-play-plan.md} §8).
 *
 * <h2>Il beneficio principale non e' la riservatezza dei pixel</h2>
 * E' che il <b>tag autentica ogni pacchetto</b>: senza, chiunque conosca indirizzo e porta
 * potrebbe iniettare input falsi in una sessione in corso — cioe' pilotare la macchina di
 * qualcun altro conoscendo due numeri che si vedono passare in rete. Con il tag, un
 * pacchetto che non e' stato prodotto da chi conosce il join code non viene nemmeno
 * analizzato.
 *
 * <h2>Il nonce, che e' l'unica cosa che si puo' sbagliare davvero</h2>
 * AES-GCM perde ogni garanzia se un nonce si ripete sotto la stessa chiave. Qui il nonce e'
 * <b>costruito, non scelto</b>: {@code prefisso(4, derivato) | direzione(1) | seq(4) |
 * 000}. La direzione separa i due versi, {@code seq} e' monotono per verso, e la chiave e'
 * nuova a ogni sessione. Resta un solo modo di romperlo — far girare {@code seq} — e per
 * questo {@link #seal} <b>rifiuta di continuare</b> invece di avvolgersi: 2^32 pacchetti
 * sono ~10 giorni di gioco ininterrotto, quindi la sessione muore con un messaggio chiaro
 * in un caso che nessuno vedra' mai, invece di degradare in silenzio in un caso che
 * comprometterebbe tutto.
 *
 * <p>Esiste anche l'implementazione {@link #plaintext()}: serve a <b>misurare</b> quanto
 * costa la cifratura (§15.2, decisione ancora aperta), non a essere usata.
 *
 * <p>Ogni istanza tiene due {@link Cipher} distinti, uno per {@link #seal} e uno per
 * {@link #open}: sono oggetti non thread-safe, e le due direzioni vivono su thread diversi.
 */
public interface MediaCipher {

    /** Byte che il sigillo aggiunge al payload in chiaro. */
    int overhead();

    /** Nome per i log e per la diagnostica. */
    String name();

    /**
     * Scrive intestazione + payload sigillato in {@code dst}.
     *
     * @return la lunghezza totale del datagramma
     */
    int seal(int type, int seq, byte[] plain, int off, int len, byte[] dst)
            throws ProtocolException;

    /**
     * Verifica e apre un datagramma ricevuto, scrivendo il chiaro in {@code dst}.
     *
     * @return la lunghezza del chiaro
     * @throws ProtocolException se l'intestazione non e' valida o il tag non torna
     */
    int open(byte[] src, int length, byte[] dst) throws ProtocolException;

    /** Sigillo AES-GCM. {@code direction} distingue i due versi nel nonce: 0 host, 1 ospite. */
    static MediaCipher aesGcm(byte[] key, byte[] noncePrefix, int direction) {
        return new AesGcm(key, noncePrefix, direction);
    }

    /** Nessun sigillo: solo per misurare il costo di quello vero. */
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
                // 0xFFFFFFFF e' l'ultimo valore rappresentabile: oltre si riavvolgerebbe, e
                // un nonce riusato vale piu' di una sessione persa.
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
                // Non si distingue "tag sbagliato" da "chiave sbagliata": il messaggio deve
                // dire cosa fare (scartare), non aiutare chi sta provando a indovinare.
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
