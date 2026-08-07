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
 * Crittografia dell'handshake: dal <i>join code</i> alle chiavi di sessione
 * ({@code docs/remote-play-plan.md} §8).
 *
 * <h2>Cosa protegge, e da cosa</h2>
 * Stiamo aprendo una porta in ascolto sul PC di casa di qualcuno e sopra ci facciamo
 * passare input che pilotano una macchina. Il minimo non negoziabile del §8 e':
 * l'autenticazione viene <b>prima di qualunque altra cosa</b>, e il codice non viaggia mai
 * in chiaro.
 *
 * <pre>
 *   master   = PBKDF2-HMAC-SHA256(joinCode, salt, {@value #PBKDF2_ROUNDS} giri, 256 bit)
 *   guestTag = HMAC-SHA256(master, "AJ-guest" | nonce | nonce2 | proto)
 *   hostTag  = HMAC-SHA256(master, "AJ-host"  | nonce | nonce2 | proto)
 *   mediaKey = HKDF-Expand(master, "AJ-media" | nonce | nonce2, 32)
 * </pre>
 *
 * <h2>Tre scelte che si discostano dal §3 del piano, e perche'</h2>
 * <ol>
 *   <li><b>La chiave di sessione non viaggia.</b> Il §3 la metteva dentro il WELCOME, ma il
 *       canale di controllo e' TCP in chiaro: spedirla li' la regalerebbe a chiunque
 *       ascolti, annullando il motivo stesso per cui si cifra il media. Qui la si
 *       <b>deriva</b> dallo stesso materiale su entrambi i lati — nulla di segreto
 *       attraversa la rete.</li>
 *   <li><b>Anche l'host si autentica.</b> Il §3 faceva provare solo all'ospite di conoscere
 *       il codice. Con un segreto condiviso l'autenticazione mutua costa un secondo HMAC, e
 *       senza di essa chiunque si metta in mezzo potrebbe spacciarsi per l'host e
 *       raccogliere gli input dell'ospite.</li>
 *   <li><b>PBKDF2 con {@value #PBKDF2_ROUNDS} giri.</b> Il join code ha ~40 bit: pochi, per
 *       una chiave. La derivazione lenta e' cio' che li rende comunque scomodi da provare a
 *       tappeto, e il costo lo si paga una volta sola per sessione (~60 ms), non per
 *       pacchetto.</li>
 * </ol>
 *
 * <p>Il confronto dei tag usa {@link MessageDigest#isEqual}, che non termina anticipatamente:
 * un confronto ingenuo perderebbe da un lato quello che si e' guadagnato dall'altro.
 */
public final class SessionCrypto {

    /** ~60 ms su una macchina da scrivania del 2026: caro per chi prova a tappeto, non per noi. */
    public static final int PBKDF2_ROUNDS = 120_000;

    public static final int SALT_BYTES = 16;
    public static final int NONCE_BYTES = 16;
    public static final int TAG_BYTES = 32;
    public static final int KEY_BYTES = 32;
    /** Prefisso del nonce AES-GCM, derivato e non trasmesso. */
    public static final int NONCE_PREFIX_BYTES = 4;

    private static final byte[] LABEL_GUEST = "AJ-guest".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] LABEL_HOST = "AJ-host".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] LABEL_MEDIA = "AJ-media".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] LABEL_NONCE = "AJ-nonce".getBytes(StandardCharsets.US_ASCII);

    private final byte[] master;

    private SessionCrypto(byte[] master) {
        this.master = master;
    }

    /** Deriva il materiale di sessione dal join code e dal salt scelto dall'host. */
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

    /** Tag con cui l'ospite dimostra di conoscere il codice. */
    public byte[] guestTag(byte[] nonce, byte[] nonce2, int protocolVersion) {
        return hmac(master, LABEL_GUEST, nonce, nonce2, protocolVersion);
    }

    /** Tag con cui l'host dimostra la stessa cosa all'ospite. */
    public byte[] hostTag(byte[] nonce, byte[] nonce2, int protocolVersion) {
        return hmac(master, LABEL_HOST, nonce, nonce2, protocolVersion);
    }

    /** Chiave AES-GCM per il media, derivata: non attraversa mai la rete. */
    public byte[] mediaKey(byte[] nonce, byte[] nonce2) {
        return hkdfExpand(master, concat(LABEL_MEDIA, nonce, nonce2), KEY_BYTES);
    }

    /** Prefisso del nonce GCM, anch'esso derivato. */
    public byte[] noncePrefix(byte[] nonce, byte[] nonce2) {
        return hkdfExpand(master, concat(LABEL_NONCE, nonce, nonce2), NONCE_PREFIX_BYTES);
    }

    /** Confronto a tempo costante: un {@code Arrays.equals} qui sarebbe un oracolo. */
    public static boolean tagsMatch(byte[] a, byte[] b) {
        return MessageDigest.isEqual(a, b);
    }

    public void destroy() {
        Arrays.fill(master, (byte) 0);
    }

    // =====================================================================
    // Interno
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
     * HKDF-Expand (RFC 5869) su HMAC-SHA256, scritto a mano in una dozzina di righe.
     *
     * <p>Il JDK espone una API KDF recente, ma qui il vincolo del piano e' "nessuna
     * dipendenza nuova <i>e</i> nessuna sorpresa": una funzione di questa taglia, con un
     * RFC alle spalle, e' piu' facile da verificare che da giustificare.
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
