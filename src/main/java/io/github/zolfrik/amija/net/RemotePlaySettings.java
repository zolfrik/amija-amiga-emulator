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

/**
 * Avvio del gioco a distanza da riga di comando ({@code docs/remote-play-plan.md} §12,
 * Fase 2: <i>avvio via {@code -D}, nessun popup</i>).
 *
 * <pre>
 *   -Damija.remoteplay.host=7788             ospita sulla porta indicata
 *   -Damija.remoteplay.join=192.168.1.20:7788   si unisce all'host indicato
 *   -Damija.remoteplay.joincode=K7M2-Q4XZ    obbligatorio per unirsi, facoltativo per
 *                                            ospitare (se manca se ne genera uno)
 *   -Damija.remoteplay.bind=192.168.1.20     interfaccia su cui ascoltare (vuoto = tutte)
 *   -Damija.remoteplay.log=true              traccia handshake, sessione e statistiche
 *   -Damija.remoteplay.guestport=1           game port pilotata dall'ospite (0 o 1)
 *   -Damija.remoteplay.guestkeyboard=true    l'ospite condivide la tastiera
 *   -Damija.remoteplay.guestreset=false      l'ospite puo' resettare con CTRL+Amiga+Amiga
 *   -Damija.remoteplay.audio=adpcm           adpcm (default) | pcm16 | off
 * </pre>
 *
 * <p>Le ultime tre le decide l'<b>host</b>: sono la sua macchina. Le comunica all'ospite nel
 * WELCOME, ma l'ospite non ha voce in capitolo, e i divieti sono <b>applicati</b> sull'host
 * (vedi {@code input.RemoteInputApplier}) — un filtro che vivesse sull'ospite sarebbe
 * aggirabile da chiunque scriva il proprio client.
 *
 * <p><b>{@code guestport} dice quale porta, non cosa c'e' dentro.</b> Assegnare la porta e'
 * dell'host — deve poter tenere per se' quella su cui gioca — ma riempirla e' dell'ospite:
 * mouse, joystick, o joystick da tastiera <b>coi propri tasti</b>, scelti nel popup della
 * <i>sua</i> macchina e cambiabili a partita in corso. Quella scelta non passa di qui: viaggia
 * nel pacchetto di feedback ({@code FeedbackPacket.portDevice}), perche' e' stato che cambia,
 * non un parametro di sessione.
 *
 * <p>Sono <b>property diagnostiche</b>, nel senso della convenzione del progetto: servono
 * finche' non c'e' la sezione nel popup F12 (Fase 5), e restano dopo per collaudare senza
 * passare dalla UI. Le chiavi definitive dell'utente saranno {@code remoteplay.*} in
 * {@code amiga.properties}.
 *
 * <p>Il join code <b>non</b> si mette sulla riga di comando in una partita vera: la riga di
 * comando di un processo e' leggibile da chiunque sulla macchina. Va bene per collaudare, ed
 * e' un'altra ragione per cui questa e' una scorciatoia di fase e non l'interfaccia finale.
 */
public record RemotePlaySettings(Mode mode, String address, int port, String joinCode,
                                 String bindAddress, int guestGamePort,
                                 boolean guestKeyboard, boolean guestAllowReset,
                                 int audioFormat) {

    public enum Mode { OFF, HOST, GUEST }

    /** Porta di default, la stessa che il popup proporra' (§10). */
    public static final int DEFAULT_PORT = 7788;

    /**
     * Game port assegnata all'ospite. La 1 e' quella joystick: l'host tiene la 0, dove sta
     * il mouse, e i due non si pestano i piedi nel caso piu' comune (§4.3).
     */
    public static final int DEFAULT_GUEST_GAME_PORT = 1;

    /** ADPCM di default: col video a 1–3 Mbit/s il PCM sarebbe meta' dello stream (§6). */
    public static final int DEFAULT_AUDIO_FORMAT = AudioBlockHeader.FORMAT_ADPCM;

    /** Traccia dettagliata: {@code -Damija.remoteplay.log=true}. */
    public static final boolean LOG = Boolean.getBoolean("amija.remoteplay.log");

    /** Una riga di log, con il prefisso di questo sottosistema. */
    public static void log(String format, Object... args) {
        if (LOG) {
            System.out.printf("[NET] " + format + "%n", args);
        }
    }

    /** Una riga che si stampa sempre: eventi di sessione che l'utente deve poter vedere. */
    public static void say(String format, Object... args) {
        System.out.printf("[NET] " + format + "%n", args);
    }

    /**
     * Legge la configurazione dalle property {@code -D}.
     *
     * @return {@link Mode#OFF} se non e' stato chiesto niente — il caso normale, in cui
     *         nulla del gioco a distanza viene nemmeno costruito
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

    /** Gioco a distanza spento: il caso normale. */
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
     * Formato audio verso l'ospite: {@code adpcm} (default), {@code pcm16}, {@code off}.
     *
     * <p>Il default e' ADPCM per la ragione del §6: col video sceso a 1–3 Mbit/s, il PCM S16
     * (1,41 Mbit/s) sarebbe il 30–50% dello stream, mentre ADPCM lo porta a 355 kbit/s.
     * Resta {@code pcm16} per chi vuole il bit-esatto su LAN — vedi §15.5, che si chiude
     * <b>ascoltando</b>, non con questo commento.
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
        // 0 e' legittimo e utile: "scegline una libera", che e' cio' che fanno i test.
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("porta fuori range: " + port);
        }
        return port;
    }
}
