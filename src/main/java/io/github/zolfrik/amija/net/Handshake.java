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
 * The control channel handshake ({@code docs/remote-play-plan.md} sections 3, 8).
 *
 * <pre>
 *   GUEST -> HOST   HELLO      proto, appVersion, nonce
 *   HOST  -> GUEST  CHALLENGE  salt, nonce2
 *   GUEST -> HOST   AUTH       guestTag = HMAC(master, "AJ-guest" | nonce | nonce2 | proto)
 *   HOST  -> GUEST  WELCOME    session parameters, hostTag
 *                   -- or --   REJECT  reason
 * </pre>
 *
 * <h2>Authentication first, everything else after</h2>
 * The non-negotiable minimum of section 8: until the AUTH is verified, the socket gets
 * nothing (no parameters, no media port, no key). The order matters the other way round too:
 * the <b>WELCOME contains the host's tag</b>, so the guest can notice it is talking to
 * someone else <i>before</i> sending it its inputs. Mutual authentication costs a second
 * HMAC and closes the hole of only the guest proving it knows the code.
 *
 * <h2>What does not travel</h2>
 * Neither the join code nor the media key crosses the network: the first is proved with an
 * HMAC, the second is <b>derived</b> from the same two nonces on both sides. Putting the key
 * inside the WELCOME, on a plain TCP, would give it away to anyone listening, defeating the
 * very reason the media is encrypted.
 *
 * <h2>Time</h2>
 * A peer that opens the socket and then stops talking holds the only session available. So
 * every read of the handshake has a timeout ({@value #HANDSHAKE_TIMEOUT_MS} ms), very wide for
 * a LAN and enough for the peer's PBKDF2 derivation.
 */
public final class Handshake {

    /** Generous for a real network, tight enough not to leave the port blocked. */
    public static final int HANDSHAKE_TIMEOUT_MS = 10_000;

    /** Direction in the AES-GCM nonce: the host encrypts with 0, the guest with 1. */
    public static final int DIRECTION_HOST = 0;
    public static final int DIRECTION_GUEST = 1;

    private static final int MAX_APP_VERSION = 64;

    /**
     * Cap on the geometry the host may declare in the WELCOME.
     *
     * <p>The guest sizes its buffers on those two numbers (the raster, the ring of references,
     * the codec's inflated body), and they are large buffers: at 65535x65535, which the
     * WELCOME's format allows, they would be gigabytes asked for at once. The host is
     * <b>authenticated</b> when it says so, so it is not a hole open to anyone; but the cap
     * costs one comparison and it is exactly the case of the rule of section 8.2: a length
     * that comes from the network sizes nothing before being checked. An Amiga raster does not
     * exceed 912x628 even in interlace with all the overscan: there is an order of magnitude
     * of margin here, and whoever exceeds it is not a host playing.
     */
    public static final int MAX_RASTER_SIDE = 4096;

    /** Total pixels allowed: limits the allocation even for a very lopsided geometry. */
    public static final int MAX_RASTER_PIXELS = 4_000_000;

    /** What the host decides and communicates, plus the derived cryptographic material. */
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

        /** The media seal for the given direction. */
        public MediaCipher cipher(int direction) {
            return MediaCipher.aesGcm(mediaKey, noncePrefix, direction);
        }
    }

    /** Parameters the host puts in the WELCOME. */
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

    /** REJECT received: an expected outcome, not a failure, and it carries the reason. */
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

    /**
     * The host refuses, and the REJECT <b>has not left yet</b>: the caller sends it.
     *
     * <p>The difference from {@link RejectedException} is the direction (the guest receives
     * that one, the host throws this one), but the reason it exists is <b>order</b>. If
     * {@link #accept} sent the REJECT itself and then threw, the guest would be told while the
     * caller was still in the middle of its own {@code finally}, i.e. before it had returned
     * the guest's place. Whoever retried faster than that (the normal case: one gets the code
     * wrong and tries again) would be told "there is already a guest" instead of "wrong code".
     *
     * <p>Saying <i>why</i> stays with whoever runs the handshake; saying <i>when</i> belongs to
     * whoever owns the state to put back in order before speaking.
     */
    public static final class RejectingException extends ProtocolException {
        private static final long serialVersionUID = 1L;
        private final int reason;

        public RejectingException(int reason, String message) {
            super(message);
            this.reason = reason;
        }

        public int reason() {
            return reason;
        }
    }

    private Handshake() {
    }

    // =====================================================================
    // Host side
    // =====================================================================

    /**
     * Runs the host side of the handshake on a socket just accepted.
     *
     * @return the session parameters, keys included
     * @throws RejectingException if the peer does not authenticate or does not speak the
     *                            protocol. The REJECT has <b>not</b> been sent: the caller sends
     *                            it, after putting back in order what it must. See
     *                            {@link RejectingException}
     * @throws IOException        if the socket drops or the peer stays silent past the timeout
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
            // The reason is stated: "it does not work" sends the user to check the router for a
            // problem solved by updating the emulator.
            throw new RejectingException(RemotePlayProtocol.REJECT_PROTOCOL,
                    "protocollo " + proto + ", questo host parla il "
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
            throw new RejectingException(RemotePlayProtocol.REJECT_AUTH,
                    "AUTH di " + auth.length() + " byte, attesi "
                    + SessionCrypto.TAG_BYTES);
        }
        byte[] received = new byte[SessionCrypto.TAG_BYTES];
        System.arraycopy(auth.data(), 0, received, 0, SessionCrypto.TAG_BYTES);

        SessionCrypto crypto = SessionCrypto.derive(joinCode, salt);
        byte[] expected = crypto.guestTag(nonce, nonce2, RemotePlayProtocol.VERSION);
        if (!SessionCrypto.tagsMatch(expected, received)) {
            crypto.destroy();
            throw new RejectingException(RemotePlayProtocol.REJECT_AUTH,
                    "autenticazione fallita: join code errato");
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
        wb.put((byte) offer.audioFormat());      // 0 = no audio
        wb.put(hostTag);
        ch.send(RemotePlayProtocol.CTRL_WELCOME, wb.array(), WELCOME_SIZE);

        Session session = new Session(offer.sessionId(), offer.sessionGen(), offer.mediaPort(),
                offer.width(), offer.height(), offer.audioSampleRate(), offer.audioChannels(),
                offer.audioFormat(), offer.guestGamePort(), offer.keyboardShared(), peerVersion,
                crypto.mediaKey(nonce, nonce2), crypto.noncePrefix(nonce, nonce2));
        crypto.destroy();
        return session;
    }

    /** Refuses without a handshake: the session is already taken, or the host is closing. */
    public static void reject(ControlChannel ch, int reason) {
        try {
            ch.send(RemotePlayProtocol.CTRL_REJECT, new byte[] { (byte) reason }, 1);
        } catch (IOException ignored) {
            // The REJECT is a courtesy: if the socket is already gone nothing changes.
        }
    }

    // =====================================================================
    // Guest side
    // =====================================================================

    /**
     * Runs the guest side of the handshake.
     *
     * @throws RejectedException if the host answered REJECT, with the reason to show
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
            // Whoever is on the other side does not know the join code: no input is sent to it.
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
        if (width > MAX_RASTER_SIDE || height > MAX_RASTER_SIDE
                || (long) width * height > MAX_RASTER_PIXELS) {
            crypto.destroy();
            throw new ProtocolException("geometria " + width + "x" + height
                    + " oltre il tetto di " + MAX_RASTER_SIDE + " per lato e "
                    + MAX_RASTER_PIXELS + " pixel");
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
