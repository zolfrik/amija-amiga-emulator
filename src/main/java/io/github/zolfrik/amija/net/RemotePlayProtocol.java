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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Costanti e struttura del protocollo di gioco a distanza
 * ({@code docs/remote-play-plan.md} §4).
 *
 * <h2>Due canali, una porta</h2>
 * <b>TCP</b> per il controllo (handshake, pausa, disconnessione: pochi byte al secondo,
 * dove l'ordine e la consegna garantita sono comodi e gratis) e <b>UDP</b> per il media e
 * l'input. Stessa <i>numerazione</i> per entrambi, cosi' UPnP dovra' creare una sola coppia
 * di mapping e l'utente ha un solo numero da comunicare.
 *
 * <p>Perche' UDP per il media: con TCP una ritrasmissione blocca tutto per un RTT e poi lo
 * stream rincorre. Su un gioco in tempo reale <b>un frame perso e' preferibile a un frame in
 * ritardo</b>, e il codec e' costruito apposta per riprendersi da solo da una perdita (§4.1:
 * l'host comprime contro l'ultimo frame che l'ospite ha confermato, quindi un frame perso
 * allarga il delta successivo invece di corrompere lo schermo).
 *
 * <h2>Datagramma</h2>
 * <pre>
 *   u16 magic      'AJ'
 *   u8  type       VIDEO | AUDIO | FEEDBACK
 *   u8  flags      riservati, devono essere 0
 *   u32 seq        contatore per direzione, monotono, MAI riusato nella sessione
 *   ...            payload sigillato AES-GCM (tag di 16 byte incluso)
 * </pre>
 * L'intestazione viaggia in chiaro — serve per instradare e per costruire il nonce — ma e'
 * <b>autenticata come AAD</b>: chi la altera invalida il tag e il pacchetto viene scartato.
 *
 * <h2>Perche' 1200 byte</h2>
 * Sotto qualunque MTU reale: 1500 Ethernet meno l'incapsulamento peggiore che si incontra
 * in pratica (PPPoE 8, IPv6 40, un tunnel VPN 60+). Frammentare a livello IP significa che
 * la perdita di <i>un</i> frammento IP butta via l'intero datagramma, il che raddoppierebbe
 * l'effetto di ogni perdita.
 */
public final class RemotePlayProtocol {

    /**
     * Versione del protocollo. Un mismatch e' un REJECT esplicito, non un errore oscuro.
     *
     * <p>La 2 ha dato un significato al primo byte riservato del pacchetto di feedback: la
     * periferica che l'ospite ha collegato alla propria porta ({@link FeedbackPacket}). Un
     * peer alla versione 1 lo manderebbe a zero, cioe' <i>porta vuota</i>, e si ritroverebbe
     * i comandi ignorati senza un errore da nessuna parte: e' esattamente il guasto muto che
     * il controllo di versione esiste per trasformare in una frase leggibile.
     */
    public static final int VERSION = 2;

    /** 'A','J' — filtro a costo zero contro il traffico che non ci riguarda. */
    public static final int MAGIC = 0x414A;

    public static final int TYPE_VIDEO = 1;
    public static final int TYPE_AUDIO = 2;      // Fase 4
    public static final int TYPE_FEEDBACK = 3;

    /** Intestazione del datagramma: magic, type, flags, seq. */
    public static final int DATAGRAM_HEADER = 8;

    /** Tetto del datagramma completo, intestazione e tag GCM inclusi. */
    public static final int MAX_DATAGRAM = 1200;

    /** Tag di autenticazione AES-GCM. */
    public static final int GCM_TAG = 16;

    /** Byte di payload in chiaro che entrano in un datagramma. */
    public static final int MAX_PLAINTEXT = MAX_DATAGRAM - DATAGRAM_HEADER - GCM_TAG;

    /** Intestazione di frammento: frameId, fragIdx, fragCount. */
    public static final int FRAGMENT_HEADER = 8;

    /** Byte utili di blob per frammento. */
    public static final int FRAGMENT_DATA = MAX_PLAINTEXT - FRAGMENT_HEADER;

    /**
     * Tetto sui frammenti di un frame: 64 x 1168 = 74 KB. Il keyframe piu' caro misurato in
     * Fase 0/1 e' 22 KB, quindi c'e' tre volte il margine — ed e' un tetto <b>costante</b>,
     * il che e' il punto: un {@code fragCount} malevolo non deve poter chiedere memoria.
     */
    public static final int MAX_FRAGMENTS = 64;

    /** Identificatore di codec nel blob di frame. Un byte, per poter cambiare idea (§15.1). */
    public static final int CODEC_AJV1 = 1;

    /** {@code refFrameId} che significa "nessun riferimento": il frame e' un keyframe. */
    public static final long NO_REFERENCE = 0xFFFFFFFFL;

    // --- canale di controllo (TCP) ---------------------------------------------------

    public static final int CTRL_HELLO = 1;
    public static final int CTRL_CHALLENGE = 2;
    public static final int CTRL_AUTH = 3;
    public static final int CTRL_WELCOME = 4;
    public static final int CTRL_REJECT = 5;
    public static final int CTRL_PAUSED = 6;
    public static final int CTRL_RESUMED = 7;
    public static final int CTRL_PING = 8;
    public static final int CTRL_PONG = 9;
    public static final int CTRL_BYE = 10;

    /** Tetto su un messaggio di controllo. Nessuno di quelli definiti arriva a 200 byte. */
    public static final int MAX_CONTROL_MESSAGE = 4096;

    // --- motivi di REJECT --------------------------------------------------------------

    public static final int REJECT_AUTH = 1;
    public static final int REJECT_BUSY = 2;
    public static final int REJECT_PROTOCOL = 3;
    public static final int REJECT_SHUTDOWN = 4;
    /** L'host accetta solo la propria rete locale (§8.5) e questo peer non ci sta. */
    public static final int REJECT_LAN_ONLY = 5;

    private RemotePlayProtocol() {
    }

    public static String rejectReason(int code) {
        return switch (code) {
            case REJECT_AUTH -> "codice di accesso errato";
            case REJECT_BUSY -> "c'e' gia' un ospite collegato";
            case REJECT_PROTOCOL -> "versione di protocollo diversa";
            case REJECT_SHUTDOWN -> "l'host ha chiuso la sessione";
            case REJECT_LAN_ONLY -> "l'host accetta solo la propria rete locale";
            default -> "motivo sconosciuto (" + code + ")";
        };
    }

    /** Buffer little-endian, come tutto il protocollo. */
    public static ByteBuffer buffer(int capacity) {
        return ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN);
    }

    public static ByteBuffer wrap(byte[] array, int offset, int length) {
        return ByteBuffer.wrap(array, offset, length).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** Scrive l'intestazione del datagramma in testa a {@code dst}. */
    public static void writeHeader(byte[] dst, int type, int flags, int seq) {
        dst[0] = (byte) MAGIC;
        dst[1] = (byte) (MAGIC >>> 8);
        dst[2] = (byte) type;
        dst[3] = (byte) flags;
        dst[4] = (byte) seq;
        dst[5] = (byte) (seq >>> 8);
        dst[6] = (byte) (seq >>> 16);
        dst[7] = (byte) (seq >>> 24);
    }

    /**
     * Verifica magic, tipo e flag di un datagramma ricevuto.
     *
     * <p>Si fa <b>prima</b> di qualunque lavoro crittografico: e' il filtro che rende
     * gratuito ignorare il traffico che non ci appartiene. L'autenticazione vera resta il
     * tag GCM — questo scarta soltanto cio' che non merita nemmeno un tentativo.
     *
     * @throws ProtocolException se l'intestazione non e' nostra o non e' pulita
     */
    public static void checkHeader(byte[] src, int length) throws ProtocolException {
        if (length < DATAGRAM_HEADER) {
            throw new ProtocolException("datagramma di " + length + " byte: troppo corto");
        }
        int magic = (src[0] & 0xFF) | ((src[1] & 0xFF) << 8);
        if (magic != MAGIC) {
            throw new ProtocolException("magic 0x" + Integer.toHexString(magic) + " non nostro");
        }
        int type = src[2] & 0xFF;
        if (type != TYPE_VIDEO && type != TYPE_AUDIO && type != TYPE_FEEDBACK) {
            throw new ProtocolException("tipo di datagramma sconosciuto: " + type);
        }
        if (src[3] != 0) {
            throw new ProtocolException("flag non nulli: 0x" + Integer.toHexString(src[3] & 0xFF));
        }
    }

    public static int headerType(byte[] src) {
        return src[2] & 0xFF;
    }

    public static int headerSeq(byte[] src) {
        return (src[4] & 0xFF) | ((src[5] & 0xFF) << 8)
                | ((src[6] & 0xFF) << 16) | ((src[7] & 0xFF) << 24);
    }
}
