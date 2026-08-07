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

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;

/**
 * Il canale di controllo: messaggi rari e affidabili su TCP
 * ({@code docs/remote-play-plan.md} §3, §4).
 *
 * <p>Ci passano l'handshake e poi pochissimo altro — pausa e ripresa dell'host, ping,
 * chiusura ordinata. Su TCP l'ordine e la consegna sono gratis, quindi il framing e' quanto
 * di piu' semplice possa esistere:
 *
 * <pre>
 *   u16 length     numero di byte che seguono, tipo compreso
 *   u8  type
 *   ... payload
 * </pre>
 *
 * <h2>La lunghezza si controlla prima di allocare</h2>
 * Regola del §8.2, e qui e' facilissima da rispettare: il buffer di ricezione e' <b>uno
 * solo</b>, allocato alla costruzione su {@link RemotePlayProtocol#MAX_CONTROL_MESSAGE}. Un
 * mittente che dichiara di piu' non ottiene memoria: ottiene una eccezione e la chiusura del
 * socket. Nessun messaggio definito arriva a 200 byte, quindi il tetto e' venti volte il
 * necessario e non stringe niente di legittimo.
 *
 * <p>Non e' thread-safe. Nel disegno del gioco a distanza il canale di controllo e' letto da
 * un solo thread e scritto da un solo thread; dove le due cose si incontrano
 * ({@code RemotePlayHost}) la scrittura e' sincronizzata da chi la usa.
 */
public final class ControlChannel implements AutoCloseable {

    private static final int LENGTH_PREFIX = 2;

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final byte[] rxBuffer = new byte[RemotePlayProtocol.MAX_CONTROL_MESSAGE];
    private final byte[] txBuffer = new byte[LENGTH_PREFIX + RemotePlayProtocol.MAX_CONTROL_MESSAGE];

    /** Un messaggio ricevuto. Il buffer e' riusato: va consumato prima della prossima lettura. */
    public record Message(int type, byte[] data, int length) {
        public ByteBuffer body() {
            return RemotePlayProtocol.wrap(data, 0, length);
        }
    }

    public ControlChannel(Socket socket) throws IOException {
        this.socket = socket;
        socket.setTcpNoDelay(true);   // messaggi minuscoli: Nagle qui aggiunge solo ritardo
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
    }

    public Socket socket() {
        return socket;
    }

    /** Timeout di lettura, in millisecondi. 0 = attesa indefinita. */
    public void setTimeout(int millis) throws IOException {
        socket.setSoTimeout(millis);
    }

    /** Manda un messaggio senza corpo. */
    public void send(int type) throws IOException {
        send(type, null, 0, 0);
    }

    public void send(int type, byte[] payload, int length) throws IOException {
        send(type, payload, 0, length);
    }

    public synchronized void send(int type, byte[] payload, int off, int length)
            throws IOException {
        int total = 1 + length;
        if (total > RemotePlayProtocol.MAX_CONTROL_MESSAGE) {
            throw new ProtocolException("messaggio di controllo di " + total
                    + " byte, massimo " + RemotePlayProtocol.MAX_CONTROL_MESSAGE);
        }
        txBuffer[0] = (byte) total;
        txBuffer[1] = (byte) (total >>> 8);
        txBuffer[2] = (byte) type;
        if (length > 0) {
            System.arraycopy(payload, off, txBuffer, LENGTH_PREFIX + 1, length);
        }
        out.write(txBuffer, 0, LENGTH_PREFIX + total);
        out.flush();
    }

    /**
     * Legge il prossimo messaggio.
     *
     * @throws java.net.SocketTimeoutException se scade il timeout impostato
     * @throws EOFException se il peer ha chiuso
     * @throws ProtocolException se il framing non torna
     */
    public Message receive() throws IOException {
        int total = (readByte() & 0xFF) | ((readByte() & 0xFF) << 8);
        if (total < 1) {
            throw new ProtocolException("messaggio di controllo vuoto");
        }
        if (total > RemotePlayProtocol.MAX_CONTROL_MESSAGE) {
            throw new ProtocolException("messaggio di controllo di " + total
                    + " byte, massimo " + RemotePlayProtocol.MAX_CONTROL_MESSAGE);
        }
        int type = readByte() & 0xFF;
        int length = total - 1;
        readFully(rxBuffer, length);
        return new Message(type, rxBuffer, length);
    }

    /**
     * Legge il messaggio atteso, o fallisce.
     *
     * <p>Durante l'handshake un messaggio fuori sequenza non e' un caso da gestire: e' un
     * peer che non parla questo protocollo, o non lo parla onestamente. Si chiude.
     */
    public Message expect(int type) throws IOException {
        Message m = receive();
        if (m.type() != type) {
            throw new ProtocolException("atteso il messaggio di tipo " + type
                    + ", ricevuto " + m.type());
        }
        return m;
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Chiudere e' l'unica cosa che si stava facendo: non c'e' niente da recuperare.
        }
    }

    /**
     * Chiude dopo essersi assicurati che l'ultimo messaggio sia arrivato.
     *
     * <p>Serve per il REJECT, e la ragione e' una trappola classica di TCP: chiudere un
     * socket che ha ancora dati non letti in ingresso fa mandare un <b>RST</b>, e il RST
     * <i>cancella</i> quel che era gia' partito. Il peer, invece del motivo del rifiuto,
     * vede una connessione azzerata — cioe' esattamente il messaggio oscuro che il motivo
     * doveva evitare. Windows lo fa sistematicamente; il collaudo del "secondo ospite" e'
     * arrivato proprio cosi'.
     *
     * <p>La cura e' la chiusura a meta': si annuncia la fine dell'invio, si consuma quel
     * che il peer aveva ancora in canna, e solo allora si chiude davvero.
     */
    public void closeGracefully() {
        try {
            socket.shutdownOutput();
            socket.setSoTimeout(LINGER_MS);
            byte[] sink = new byte[64];
            while (in.read(sink) >= 0) {
                // Si butta: serve solo a svuotare la coda, cosi' la chiusura non e' un RST.
            }
        } catch (IOException ignored) {
            // Il peer se n'e' andato per conto suo: e' l'esito che si voleva comunque.
        } finally {
            close();
        }
    }

    /** Quanto si aspetta il peer prima di chiudere davvero, dopo un REJECT. */
    private static final int LINGER_MS = 500;

    // =====================================================================

    private int readByte() throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new EOFException("canale di controllo chiuso dal peer");
        }
        return b;
    }

    private void readFully(byte[] dst, int length) throws IOException {
        int read = 0;
        while (read < length) {
            int n = in.read(dst, read, length - read);
            if (n < 0) {
                throw new EOFException("canale di controllo chiuso a meta' messaggio");
            }
            read += n;
        }
    }
}
