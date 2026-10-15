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

/**
 * Starting remote play from the command line ({@code docs/remote-play-plan.md} section 12).
 *
 * <pre>
 *   -Damija.remoteplay.host=7788             hosts on the given port
 *   -Damija.remoteplay.join=192.168.1.20:7788   joins the given host
 *   -Damija.remoteplay.joincode=K7M2-Q4XZ    required to join, optional to host
 *                                            (one is generated if missing)
 *   -Damija.remoteplay.bind=192.168.1.20     interface to listen on (empty = all)
 *   -Damija.remoteplay.log=true              traces handshake, session and statistics
 *   -Damija.remoteplay.guestport=1           game port driven by the guest (0 or 1)
 *   -Damija.remoteplay.guestkeyboard=true    the guest shares the keyboard
 *   -Damija.remoteplay.guestreset=false      the guest may reset with CTRL+Amiga+Amiga
 *   -Damija.remoteplay.audio=adpcm           adpcm (default) | pcm16 | off
 * </pre>
 *
 * <p>The last three are decided by the <b>host</b>: it is its machine. It tells the guest in
 * the WELCOME, but the guest has no say, and the bans are <b>enforced</b> on the host (see
 * {@code input.RemoteInputApplier}): a filter living on the guest could be bypassed by anyone
 * writing their own client.
 *
 * <p><b>{@code guestport} says which port, not what is in it.</b> Assigning the port belongs
 * to the host (it must be able to keep the one it plays on), but filling it belongs to the
 * guest: mouse, joystick, or keyboard joystick <b>with its own keys</b>, chosen in the popup of
 * <i>its</i> machine and changeable mid-game. That choice does not go through here: it travels
 * in the feedback packet ({@code FeedbackPacket.portDevice}), because it is changing state, not
 * a session parameter.
 *
 * <p>These are <b>diagnostic properties</b>, in the sense of the project convention: they
 * allow testing without going through the UI. The user's keys are {@code remoteplay.*} in
 * {@code amiga.properties}.
 *
 * <p>The join code is <b>not</b> put on the command line in a real game: a process's command
 * line is readable by anyone on the machine. It is fine for testing.
 */
public record RemotePlaySettings(Mode mode, String address, int port, String joinCode,
                                 String bindAddress, int guestGamePort,
                                 boolean guestKeyboard, boolean guestAllowReset,
                                 int audioFormat) {

    public enum Mode { OFF, HOST, GUEST }

    /** Default port, the same the popup offers (section 10). */
    public static final int DEFAULT_PORT = 7788;

    /**
     * Game port assigned to the guest. 1 is the joystick one: the host keeps 0, where the mouse
     * is, and the two do not tread on each other in the most common case (section 4.3).
     */
    public static final int DEFAULT_GUEST_GAME_PORT = 1;

    /** ADPCM by default: with the video at 1-3 Mbit/s PCM would be half the stream (section 6). */
    public static final int DEFAULT_AUDIO_FORMAT = AudioBlockHeader.FORMAT_ADPCM;

    /** Traccia dettagliata: {@code -Damija.remoteplay.log=true}. */
    public static final boolean LOG = Boolean.getBoolean("amija.remoteplay.log");

    /** A log line, with this subsystem's prefix. */
    public static void log(String format, Object... args) {
        if (LOG) {
            System.out.printf("[NET] " + format + "%n", args);
        }
    }

    /** A line always printed: session events the user must be able to see. */
    public static void say(String format, Object... args) {
        System.out.printf("[NET] " + format + "%n", args);
    }

    /**
     * Reads the configuration from the {@code -D} properties.
     *
     * @return {@link Mode#OFF} if nothing was asked (the normal case, in which nothing of
     *         remote play is even built)
     */
    public static RemotePlaySettings fromSystemProperties() {
        String host = trimmed("amija.remoteplay.host");
        String join = trimmed("amija.remoteplay.join");
        String joinCode = trimmed("amija.remoteplay.joincode");
        String bind = trimmed("amija.remoteplay.bind");

        if (host != null && join != null) {
            throw new IllegalArgumentException(
                    "amija.remoteplay.host e amija.remoteplay.join si escludono: "
                    + "una macchina o ospita o si unisce");
        }
        int guestPort = guestGamePortProperty();
        boolean keyboard = booleanProperty("amija.remoteplay.guestkeyboard", true);
        boolean allowReset = booleanProperty("amija.remoteplay.guestreset", false);
        int audio = audioFormatProperty();

        if (host != null) {
            return new RemotePlaySettings(Mode.HOST, null, parsePort(host), joinCode, bind,
                    guestPort, keyboard, allowReset, audio);
        }
        if (join != null) {
            int colon = join.lastIndexOf(':');
            String address = colon > 0 ? join.substring(0, colon) : join;
            int port = colon > 0 ? parsePort(join.substring(colon + 1)) : DEFAULT_PORT;
            if (joinCode == null || !JoinCode.isWellFormed(joinCode)) {
                throw new IllegalArgumentException(
                        "amija.remoteplay.joincode mancante o malformato: per unirsi serve"
                        + " il codice mostrato dall'host");
            }
            return new RemotePlaySettings(Mode.GUEST, address, port, joinCode, bind,
                    guestPort, keyboard, allowReset, audio);
        }
        return off();
    }

    /** Remote play off: the normal case. */
    public static RemotePlaySettings off() {
        return new RemotePlaySettings(Mode.OFF, null, 0, null, null,
                DEFAULT_GUEST_GAME_PORT, true, false, DEFAULT_AUDIO_FORMAT);
    }

    public boolean isOff() {
        return mode == Mode.OFF;
    }

    private static String trimmed(String key) {
        String v = System.getProperty(key);
        if (v == null) {
            return null;
        }
        v = v.trim();
        return v.isEmpty() ? null : v;
    }

    /**
     * Audio format toward the guest: {@code adpcm} (default), {@code pcm16}, {@code off}.
     *
     * <p>The default is ADPCM for the reason of section 6: with the video down to 1-3 Mbit/s,
     * PCM S16 (1.41 Mbit/s) would be 30-50% of the stream, while ADPCM brings it to
     * 355 kbit/s. {@code pcm16} remains for whoever wants bit-exact audio on a LAN (see
     * section 15.5).
     */
    private static int audioFormatProperty() {
        String v = trimmed("amija.remoteplay.audio");
        if (v == null) {
            return DEFAULT_AUDIO_FORMAT;
        }
        return switch (v.toLowerCase(java.util.Locale.ROOT)) {
            case "adpcm" -> AudioBlockHeader.FORMAT_ADPCM;
            case "pcm16", "pcm" -> AudioBlockHeader.FORMAT_PCM16;
            case "off", "none" -> 0;
            default -> throw new IllegalArgumentException(
                    "amija.remoteplay.audio vuole adpcm, pcm16 oppure off, non: " + v);
        };
    }

    private static int guestGamePortProperty() {
        String v = trimmed("amija.remoteplay.guestport");
        if (v == null) {
            return DEFAULT_GUEST_GAME_PORT;
        }
        int p;
        try {
            p = Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("amija.remoteplay.guestport non numerico: " + v, e);
        }
        if (p != 0 && p != 1) {
            throw new IllegalArgumentException(
                    "amija.remoteplay.guestport deve essere 0 o 1, non " + p);
        }
        return p;
    }

    private static boolean booleanProperty(String key, boolean fallback) {
        String v = trimmed(key);
        if (v == null) {
            return fallback;
        }
        if (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false")) {
            return Boolean.parseBoolean(v);
        }
        throw new IllegalArgumentException(key + " vuole true o false, non: " + v);
    }

    private static int parsePort(String text) {
        int port;
        try {
            port = Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("porta non numerica: " + text, e);
        }
        // 0 is legitimate and useful: "pick a free one", which is what the tests do.
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("porta fuori range: " + port);
        }
        return port;
    }
}
