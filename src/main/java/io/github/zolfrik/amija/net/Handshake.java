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

import io.github.zolfrik.amija.common.Version;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * L'handshake del canale di controllo ({@code docs/remote-play-plan.md} §3, §8).
 *
 * <pre>
 *   GUEST -> HOST   HELLO      proto, appVersion, nonce
 *   HOST  -> GUEST  CHALLENGE  salt, nonce2
 *   GUEST -> HOST   AUTH       guestTag = HMAC(master, "AJ-guest" | nonce | nonce2 | proto)
 *   HOST  -> GUEST  WELCOME    parametri di sessione, hostTag
 *                   ─ oppure ─ REJECT  motivo
 * </pre>
 *
 * <h2>Prima l'autenticazione, poi tutto il resto</h2>
 * E' il minimo non negoziabile del §8: finche' l'AUTH non e' verificato, il socket non
 * ottiene niente — nessun parametro, nessuna porta media, nessuna chiave. E l'ordine conta
 * anche al contrario: il <b>WELCOME contiene il tag dell'host</b>, quindi e' l'ospite a
 * potersi accorgere di stare parlando con qualcun altro <i>prima</i> di mandargli i propri
 * input. Il §3 faceva provare solo all'ospite di conoscere il codice; l'autenticazione
 * mutua costa un secondo HMAC e chiude quel buco.
 *
 * <h2>Cio' che non viaggia</h2>
 * Ne' il join code ne' la chiave del media attraversano la rete: la prima e' provata con un
 * HMAC, la seconda e' <b>derivata</b> dalle stesse due nonce sui due lati. Il §3 metteva la
 * chiave dentro il WELCOME, che su un TCP in chiaro sarebbe stato regalarla a chiunque
 * ascolti — annullando il motivo stesso per cui si cifra il media.
 *
 * <h2>Il tempo</h2>
 * Un peer che apre il socket e non parla piu' tiene occupata l'unica sessione disponibile.
 * Ogni lettura dell'handshake ha percio' un timeout ({@value #HANDSHAKE_TIMEOUT_MS} ms), che
 * e' larghissimo per una LAN e sufficiente per la derivazione PBKDF2 del peer.
 */
public final class Handshake {

    /** Generoso per una rete vera, stretto abbastanza da non lasciare la porta bloccata. */
    public static final int HANDSHAKE_TIMEOUT_MS = 10_000;

    /** Direzione nel nonce AES-GCM: l'host cifra con 0, l'ospite con 1. */
    public static final int DIRECTION_HOST = 0;
    public static final int DIRECTION_GUEST = 1;

    private static final int MAX_APP_VERSION = 64;

    /** Quel che l'host decide e comunica, piu' il materiale crittografico derivato. */
    public record Session(
            int sessionId,
            int sessionGen,
            int mediaPort,
            int width,
            int height,
            int audioSampleRate,
            int audioChannels,
            int audioFormat,
            int guestGamePort,
            boolean keyboardShared,
            String peerAppVersion,
            byte[] mediaKey,
            byte[] noncePrefix) {

        /** Il sigillo del media per il verso indicato. */
        public MediaCipher cipher(int direction) {
            return MediaCipher.aesGcm(mediaKey, noncePrefix, direction);
        }
    }

    /** Parametri che l'host mette nel WELCOME. */
    public record HostOffer(
            int sessionId,
            int sessionGen,
            int mediaPort,
            int width,
            int height,
            int audioSampleRate,
            int audioChannels,
            int audioFormat,
            int guestGamePort,
            boolean keyboardShared) {
    }

    /** REJECT ricevuto: e' un esito previsto, non un guasto, e porta con se' il motivo. */
    public static final class RejectedException extends ProtocolException {
        private static final long serialVersionUID = 1L;
        private final int reason;

        public RejectedException(int reason) {
            super("connessione rifiutata: " + RemotePlayProtocol.rejectReason(reason));
            this.reason = reason;
        }

        public int reason() {
            return reason;
        }
    }

    private Handshake() {
    }

    // =====================================================================
    // Lato host
    // =====================================================================

    /**
     * Esegue il lato host dell'handshake su un socket appena accettato.
     *
     * @return i parametri di sessione, chiavi comprese
     * @throws ProtocolException se il peer non si autentica o non parla il protocollo; il
     *                           REJECT e' gia' stato mandato quando aveva senso mandarlo
     */
    public static Session accept(ControlChannel ch, String joinCode, HostOffer offer)
            throws IOException {
        ch.setTimeout(HANDSHAKE_TIMEOUT_MS);

        ControlChannel.Message hello = ch.expect(RemotePlayProtocol.CTRL_HELLO);
        ByteBuffer hb = hello.body();
        int proto;
        String peerVersion;
        byte[] nonce = new byte[SessionCrypto.NONCE_BYTES];
        try {
            proto = hb.get() & 0xFF;
            int versionLength = hb.get() & 0xFF;
            if (versionLength > MAX_APP_VERSION) {
                throw new ProtocolException("appVersion di " + versionLength
                        + " byte, massimo " + MAX_APP_VERSION);
            }
            byte[] v = new byte[versionLength];
            hb.get(v);
            peerVersion = new String(v, StandardCharsets.UTF_8);
            hb.get(nonce);
        } catch (java.nio.BufferUnderflowException e) {
            throw new ProtocolException("HELLO troncato", e);
        }
        if (proto != RemotePlayProtocol.VERSION) {
            // Il motivo si dice: "non funziona" manda l'utente a controllare il router per un
            // problema che si risolve aggiornando l'emulatore.
            reject(ch, RemotePlayProtocol.REJECT_PROTOCOL);
            throw new ProtocolException("protocollo " + proto + ", questo host parla il "
                    + RemotePlayProtocol.VERSION);
        }

        byte[] salt = SessionCrypto.randomBytes(SessionCrypto.SALT_BYTES);
        byte[] nonce2 = SessionCrypto.randomBytes(SessionCrypto.NONCE_BYTES);
        byte[] challenge = new byte[SessionCrypto.SALT_BYTES + SessionCrypto.NONCE_BYTES];
        System.arraycopy(salt, 0, challenge, 0, SessionCrypto.SALT_BYTES);
        System.arraycopy(nonce2, 0, challenge, SessionCrypto.SALT_BYTES,
                SessionCrypto.NONCE_BYTES);
        ch.send(RemotePlayProtocol.CTRL_CHALLENGE, challenge, challenge.length);

        ControlChannel.Message auth = ch.expect(RemotePlayProtocol.CTRL_AUTH);
        if (auth.length() != SessionCrypto.TAG_BYTES) {
            reject(ch, RemotePlayProtocol.REJECT_AUTH);
            throw new ProtocolException("AUTH di " + auth.length() + " byte, attesi "
                    + SessionCrypto.TAG_BYTES);
        }
        byte[] received = new byte[SessionCrypto.TAG_BYTES];
        System.arraycopy(auth.data(), 0, received, 0, SessionCrypto.TAG_BYTES);

        SessionCrypto crypto = SessionCrypto.derive(joinCode, salt);
        byte[] expected = crypto.guestTag(nonce, nonce2, RemotePlayProtocol.VERSION);
        if (!SessionCrypto.tagsMatch(expected, received)) {
            crypto.destroy();
            reject(ch, RemotePlayProtocol.REJECT_AUTH);
            throw new ProtocolException("autenticazione fallita: join code errato");
        }

        byte[] hostTag = crypto.hostTag(nonce, nonce2, RemotePlayProtocol.VERSION);
        ByteBuffer wb = RemotePlayProtocol.buffer(WELCOME_SIZE);
        wb.putInt(offer.sessionId());
        wb.putInt(offer.sessionGen());
        wb.putShort((short) offer.mediaPort());
        wb.putShort((short) offer.width());
        wb.putShort((short) offer.height());
        wb.putInt(offer.audioSampleRate());
        wb.put((byte) offer.audioChannels());
        wb.put((byte) offer.guestGamePort());
        wb.put((byte) (offer.keyboardShared() ? 1 : 0));
        wb.put((byte) offer.audioFormat());      // 0 = nessun audio
        wb.put(hostTag);
        ch.send(RemotePlayProtocol.CTRL_WELCOME, wb.array(), WELCOME_SIZE);

        Session session = new Session(offer.sessionId(), offer.sessionGen(), offer.mediaPort(),
                offer.width(), offer.height(), offer.audioSampleRate(), offer.audioChannels(),
                offer.audioFormat(), offer.guestGamePort(), offer.keyboardShared(), peerVersion,
                crypto.mediaKey(nonce, nonce2), crypto.noncePrefix(nonce, nonce2));
        crypto.destroy();
        return session;
    }

    /** Rifiuta senza handshake: la sessione e' gia' occupata, o l'host sta chiudendo. */
    public static void reject(ControlChannel ch, int reason) {
        try {
            ch.send(RemotePlayProtocol.CTRL_REJECT, new byte[] { (byte) reason }, 1);
        } catch (IOException ignored) {
            // Il REJECT e' una cortesia: se il socket e' gia' andato non cambia niente.
        }
    }

    // =====================================================================
    // Lato ospite
    // =====================================================================

    /**
     * Esegue il lato ospite dell'handshake.
     *
     * @throws RejectedException se l'host ha risposto REJECT, col motivo da mostrare
     */
    public static Session connect(ControlChannel ch, String joinCode) throws IOException {
        ch.setTimeout(HANDSHAKE_TIMEOUT_MS);

        byte[] nonce = SessionCrypto.randomBytes(SessionCrypto.NONCE_BYTES);
        byte[] version = appVersion();
        ByteBuffer hb = RemotePlayProtocol.buffer(2 + version.length + nonce.length);
        hb.put((byte) RemotePlayProtocol.VERSION);
        hb.put((byte) version.length);
        hb.put(version);
        hb.put(nonce);
        ch.send(RemotePlayProtocol.CTRL_HELLO, hb.array(), hb.position());

        ControlChannel.Message challenge = expectOrReject(ch, RemotePlayProtocol.CTRL_CHALLENGE);
        if (challenge.length() != SessionCrypto.SALT_BYTES + SessionCrypto.NONCE_BYTES) {
            throw new ProtocolException("CHALLENGE di " + challenge.length() + " byte");
        }
        byte[] salt = new byte[SessionCrypto.SALT_BYTES];
        byte[] nonce2 = new byte[SessionCrypto.NONCE_BYTES];
        ByteBuffer cb = challenge.body();
        cb.get(salt);
        cb.get(nonce2);

        SessionCrypto crypto = SessionCrypto.derive(joinCode, salt);
        byte[] guestTag = crypto.guestTag(nonce, nonce2, RemotePlayProtocol.VERSION);
        ch.send(RemotePlayProtocol.CTRL_AUTH, guestTag, guestTag.length);

        ControlChannel.Message welcome = expectOrReject(ch, RemotePlayProtocol.CTRL_WELCOME);
        if (welcome.length() != WELCOME_SIZE) {
            crypto.destroy();
            throw new ProtocolException("WELCOME di " + welcome.length() + " byte, attesi "
                    + WELCOME_SIZE);
        }
        ByteBuffer wb = welcome.body();
        int sessionId = wb.getInt();
        int sessionGen = wb.getInt();
        int mediaPort = wb.getShort() & 0xFFFF;
        int width = wb.getShort() & 0xFFFF;
        int height = wb.getShort() & 0xFFFF;
        int audioRate = wb.getInt();
        int audioChannels = wb.get() & 0xFF;
        int guestGamePort = wb.get() & 0xFF;
        boolean keyboardShared = (wb.get() & 0xFF) != 0;
        int audioFormat = wb.get() & 0xFF;
        byte[] hostTag = new byte[SessionCrypto.TAG_BYTES];
        wb.get(hostTag);

        byte[] expected = crypto.hostTag(nonce, nonce2, RemotePlayProtocol.VERSION);
        if (!SessionCrypto.tagsMatch(expected, hostTag)) {
            crypto.destroy();
            // Chi sta dall'altra parte non conosce il join code: non gli si manda un input.
            throw new ProtocolException("l'host non ha dimostrato di conoscere il codice");
        }
        if (audioFormat != 0 && audioFormat != AudioBlockHeader.FORMAT_PCM16
                && audioFormat != AudioBlockHeader.FORMAT_ADPCM) {
            crypto.destroy();
            throw new ProtocolException("formato audio sconosciuto nel WELCOME: " + audioFormat);
        }
        if (width < 1 || height < 1 || guestGamePort > 1 || audioChannels > 2) {
            crypto.destroy();
            throw new ProtocolException("parametri di sessione fuori range: " + width + "x"
                    + height + ", porta " + guestGamePort + ", " + audioChannels + " canali");
        }

        Session session = new Session(sessionId, sessionGen, mediaPort, width, height,
                audioRate, audioChannels, audioFormat, guestGamePort, keyboardShared, null,
                crypto.mediaKey(nonce, nonce2), crypto.noncePrefix(nonce, nonce2));
        crypto.destroy();
        return session;
    }

    // =====================================================================

    private static final int WELCOME_SIZE = 4 + 4 + 2 + 2 + 2 + 4 + 1 + 1 + 1 + 1
            + SessionCrypto.TAG_BYTES;

    private static ControlChannel.Message expectOrReject(ControlChannel ch, int type)
            throws IOException {
        ControlChannel.Message m = ch.receive();
        if (m.type() == RemotePlayProtocol.CTRL_REJECT) {
            throw new RejectedException(m.length() >= 1 ? m.data()[0] & 0xFF : 0);
        }
        if (m.type() != type) {
            throw new ProtocolException("atteso il messaggio di tipo " + type
                    + ", ricevuto " + m.type());
        }
        return m;
    }

    private static byte[] appVersion() {
        byte[] v = Version.version().getBytes(StandardCharsets.UTF_8);
        if (v.length > MAX_APP_VERSION) {
            byte[] cut = new byte[MAX_APP_VERSION];
            System.arraycopy(v, 0, cut, 0, MAX_APP_VERSION);
            return cut;
        }
        return v;
    }
}
