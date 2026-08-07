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

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;

/**
 * Il canale media: datagrammi sigillati su UDP ({@code docs/remote-play-plan.md} §4, §8).
 *
 * <p>Sopra ci passano il video e l'audio verso l'ospite e il feedback verso l'host. Non sa
 * cosa trasporta: mette e toglie il sigillo, conta, e filtra chi non e' il peer.
 *
 * <h2>L'indirizzo del peer si adotta, non si configura</h2>
 * L'ospite conosce l'host dal canale TCP. L'host <b>no</b>: dietro NAT la porta UDP sorgente
 * dell'ospite non ha alcun rapporto con quella del suo TCP, e nessuno dei due lati puo'
 * saperla in anticipo. Percio' l'host aspetta il primo datagramma che <b>si apre con la
 * chiave di sessione</b> e ne adotta il mittente. E' l'unico modo che funzioni dietro NAT ed
 * e' anche il piu' sicuro: l'indirizzo non e' dichiarato da nessuno, e' <i>dimostrato</i>.
 *
 * <h2>Il filtro costa un confronto, non una decifratura</h2>
 * Adottato il peer, i datagrammi che vengono da altrove sono scartati <b>prima</b> di
 * qualunque lavoro crittografico (§8.3). Chi volesse tenere occupata la CPU dell'host con
 * pacchetti finti ottiene un confronto di indirizzi. Prima dell'adozione quel filtro non
 * esiste ancora — li' l'unica difesa e' provare ad aprire — e allora si limita il
 * <i>numero</i> di tentativi: {@value #ADOPTION_ATTEMPTS_PER_SECOND} al secondo, che bastano
 * a chiunque arrivi in buona fede e non bastano a niente altro.
 *
 * <h2>Sui replay</h2>
 * Il tag GCM dimostra che un datagramma e' stato prodotto da chi conosce la chiave, non che
 * sia nuovo. Un pacchetto ripetuto e' pero' innocuo per costruzione, ai due usi che esistono:
 * un frammento video gia' visto lo scarta {@link FragmentReassembler}, e un feedback vecchio
 * lo scarta il {@code seq} monotono di {@link FeedbackPacket}. Filtrare qui sarebbe una
 * seconda barriera contro un riordino che su UDP e' normale, e butterebbe via pacchetti buoni.
 */
public final class MediaLink implements AutoCloseable {

    /** Tentativi di adozione al secondo, prima che l'indirizzo del peer sia noto. */
    public static final int ADOPTION_ATTEMPTS_PER_SECOND = 20;

    /** Esito di una ricezione andata a buon fine. Il chiaro sta nel buffer del chiamante. */
    public record Received(int type, int length, int seq) {
    }

    private final DatagramSocket socket;
    private final MediaCipher cipher;
    private final NetImpairment impairment;
    private final boolean adoptPeer;

    private final byte[] txBuffer = new byte[RemotePlayProtocol.MAX_DATAGRAM];
    private final byte[] rxBuffer = new byte[RemotePlayProtocol.MAX_DATAGRAM];
    private final DatagramPacket txPacket =
            new DatagramPacket(txBuffer, RemotePlayProtocol.MAX_DATAGRAM);
    private final DatagramPacket rxPacket =
            new DatagramPacket(rxBuffer, RemotePlayProtocol.MAX_DATAGRAM);

    private volatile SocketAddress peer;
    private int txSeq;
    private long adoptionWindowStart;
    private int adoptionAttempts;

    private long datagramsSent;
    private long datagramsReceived;
    private long bytesSent;
    private long rejectedForeign;
    private long rejectedUnsealed;

    /**
     * @param socket     socket UDP gia' aperto; il link non lo chiude, lo fa chi lo possiede
     * @param cipher     sigillo di sessione, gia' orientato nel verso giusto
     * @param peer       destinatario noto (ospite), oppure {@code null} per adottarlo (host)
     * @param impairment simulazione di link, {@link NetImpairment#NONE} in produzione
     */
    public MediaLink(DatagramSocket socket, MediaCipher cipher, SocketAddress peer,
                     NetImpairment impairment) {
        this.socket = socket;
        this.cipher = cipher;
        this.peer = peer;
        this.adoptPeer = peer == null;
        this.impairment = impairment == null ? NetImpairment.NONE : impairment;
    }

    public SocketAddress peer() {
        return peer;
    }

    public boolean hasPeer() {
        return peer != null;
    }

    /** Timeout di ricezione: oltre quello {@link #receive} torna {@code null}. */
    public void setTimeout(int millis) throws IOException {
        socket.setSoTimeout(millis);
    }

    /**
     * Sigilla e spedisce un payload.
     *
     * @return {@code false} se il peer non e' ancora noto: non e' un errore, e' l'attesa del
     *         primo datagramma dell'ospite
     */
    public synchronized boolean send(int type, byte[] plain, int off, int length)
            throws IOException {
        SocketAddress destination = peer;
        if (destination == null) {
            return false;
        }
        if (length > RemotePlayProtocol.MAX_PLAINTEXT) {
            throw new ProtocolException("payload di " + length + " byte, massimo "
                    + RemotePlayProtocol.MAX_PLAINTEXT);
        }
        int total = cipher.seal(type, txSeq++, plain, off, length, txBuffer);
        txPacket.setData(txBuffer, 0, total);
        txPacket.setSocketAddress(destination);
        impairment.send(socket, txPacket);
        datagramsSent++;
        bytesSent += total;
        return true;
    }

    /**
     * Riceve e apre il prossimo datagramma valido.
     *
     * @param plainOut buffer per il chiaro, almeno {@link RemotePlayProtocol#MAX_PLAINTEXT}
     * @return l'esito, oppure {@code null} se e' scaduto il timeout o il datagramma e' stato
     *         scartato. <b>Scartare non e' un'eccezione</b>: su un canale aperto a Internet
     *         il traffico non nostro e' la normalita', e trattarlo come un guasto porterebbe
     *         a chiudere la sessione quando basta ignorare un pacchetto.
     */
    public Received receive(byte[] plainOut) throws IOException {
        rxPacket.setData(rxBuffer, 0, RemotePlayProtocol.MAX_DATAGRAM);
        try {
            socket.receive(rxPacket);
        } catch (SocketTimeoutException e) {
            return null;
        }
        int length = rxPacket.getLength();
        SocketAddress from = rxPacket.getSocketAddress();

        SocketAddress known = peer;
        if (known != null) {
            if (!known.equals(from)) {
                rejectedForeign++;
                return null;
            }
        } else if (!allowAdoptionAttempt()) {
            rejectedForeign++;
            return null;
        }

        int plainLength;
        try {
            plainLength = cipher.open(rxBuffer, length, plainOut);
        } catch (ProtocolException e) {
            rejectedUnsealed++;
            return null;
        }

        if (known == null) {
            // Si e' aperto con la chiave di sessione: e' l'ospite, ovunque sia.
            peer = from;
        }
        datagramsReceived++;
        return new Received(RemotePlayProtocol.headerType(rxBuffer), plainLength,
                RemotePlayProtocol.headerSeq(rxBuffer));
    }

    /** Datagrammi da un mittente che non e' il peer, o oltre il ritmo di adozione. */
    public long rejectedForeign() {
        return rejectedForeign;
    }

    /** Datagrammi dal peer che non hanno superato il tag: corrotti o falsi. */
    public long rejectedUnsealed() {
        return rejectedUnsealed;
    }

    public long datagramsSent() {
        return datagramsSent;
    }

    public long datagramsReceived() {
        return datagramsReceived;
    }

    public long bytesSent() {
        return bytesSent;
    }

    /**
     * Non chiude ne' il socket ne' l'impairment: <b>non li possiede</b>.
     *
     * <p>Sull'host il socket UDP e' uno solo per tutta la vita del server e ogni sessione ci
     * costruisce sopra il proprio link; se il link chiudesse l'impairment, la sessione
     * successiva ne troverebbe uno gia' spento. La proprieta' sta in chi li ha creati —
     * {@code RemotePlayHost} e {@code RemotePlayGuest} — e li' resta.
     */
    @Override
    public void close() {
    }

    // =====================================================================

    private boolean allowAdoptionAttempt() {
        long now = System.nanoTime();
        if (now - adoptionWindowStart > 1_000_000_000L) {
            adoptionWindowStart = now;
            adoptionAttempts = 0;
        }
        return ++adoptionAttempts <= ADOPTION_ATTEMPTS_PER_SECOND;
    }
}
