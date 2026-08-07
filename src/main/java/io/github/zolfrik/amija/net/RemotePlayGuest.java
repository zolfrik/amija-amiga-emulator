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
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.locks.LockSupport;

/**
 * Lato ospite di una sessione di gioco a distanza: si collega, riceve il video e manda
 * indietro il proprio stato ({@code docs/remote-play-plan.md} §3, §4).
 *
 * <h2>Questa macchina non emula niente</h2>
 * Nessun Kickstart, nessuna ROM, nessun {@code MasterClock} (§3). L'ospite riceve il
 * <b>raster grezzo</b> di Denise e lo consegna a chi lo disegna, che nella app e' lo stesso
 * {@code AmigaDisplay.renderFrame} usato dall'emulazione: crop, integer scaling, overscan,
 * shader, LED e fullscreen restano quindi scelte <b>locali</b> dell'ospite, e funzionano
 * senza che una riga di quel codice sappia della rete.
 *
 * <h2>Il feedback parte subito, e non e' un dettaglio</h2>
 * Il primo datagramma dell'ospite e' cio' che permette all'host di sapere <b>dove</b>
 * mandare il video: dietro NAT la porta UDP sorgente non e' prevedibile, quindi l'host
 * adotta il mittente del primo datagramma che si apre con la chiave di sessione
 * ({@link MediaLink}). Finche' l'ospite tace, l'host non ha nessun indirizzo a cui parlare.
 *
 * <p>Il ritmo e' {@value #FEEDBACK_HZ} Hz: sono 44 byte a colpo, cioe' 70 kbit/s — nulla — e
 * in cambio si dimezza la parte di latenza di input dovuta al campionamento (§4.3, §5).
 */
public final class RemotePlayGuest implements AutoCloseable {

    /** Campionamento e invio dello stato dei controlli: 200 Hz, cioe' un colpo ogni 5 ms. */
    public static final int FEEDBACK_HZ = 200;

    private static final int POLL_MS = 200;
    private static final int SESSION_TIMEOUT_MS = 10_000;

    private final ControlChannel control;
    private final DatagramSocket mediaSocket;
    private final MediaLink media;
    private final NetImpairment impairment;
    private final Handshake.Session session;
    private final VideoReceiver receiver;
    private final VideoReceiver.FrameSink sink;
    /** {@code null} se l'host non manda audio, o se non c'e' una scheda audio qui. */
    private final AudioReceiver audioReceiver;
    private final NetAudioPlayer audioPlayer;
    private final AudioReceiver.PcmSink audioSink;

    private final FeedbackPacket outgoing = new FeedbackPacket();
    private final byte[] outgoingBytes = new byte[FeedbackPacket.SIZE];
    private final byte[] plain = new byte[RemotePlayProtocol.MAX_PLAINTEXT];

    private volatile InputSource inputSource;
    private volatile AudioReceiver.PcmSink audioMonitor;
    private volatile boolean alive = true;
    private volatile boolean closed;
    private volatile EndCause endCause = EndCause.RUNNING;
    private volatile String endDetail;
    private volatile boolean hostPaused;
    private volatile long lastVideoNanos = System.nanoTime();
    private final Thread mediaThread;
    private final Thread feedbackThread;
    private final Thread controlThread;

    /**
     * Si collega a un host, esegue l'handshake e comincia a ricevere.
     *
     * @param sink chi riceve i frame ricostruiti; puo' essere {@code null} per limitarsi a
     *             tenere viva la sessione
     * @throws Handshake.RejectedException se l'host ha rifiutato, col motivo da mostrare
     */
    public static RemotePlayGuest connect(String host, int port, String joinCode,
                                          NetImpairment impairment,
                                          VideoReceiver.FrameSink sink) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), Handshake.HANDSHAKE_TIMEOUT_MS);
        ControlChannel channel = new ControlChannel(socket);
        Handshake.Session negotiated;
        try {
            negotiated = Handshake.connect(channel, joinCode);
        } catch (IOException e) {
            channel.close();
            throw e;
        }
        return new RemotePlayGuest(channel, negotiated, host, impairment, sink);
    }

    private RemotePlayGuest(ControlChannel control, Handshake.Session session, String host,
                            NetImpairment impairment, VideoReceiver.FrameSink sink)
            throws IOException {
        this.control = control;
        this.session = session;
        this.sink = sink;
        this.impairment = impairment == null ? NetImpairment.NONE : impairment;
        this.receiver = new VideoReceiver(session.width(), session.height());
        this.mediaSocket = new DatagramSocket();
        this.media = new MediaLink(mediaSocket, session.cipher(Handshake.DIRECTION_GUEST),
                new InetSocketAddress(host, session.mediaPort()), this.impairment);
        this.media.setTimeout(POLL_MS);

        outgoing.clear();
        outgoing.sessionGen = session.sessionGen();

        // Il ricevitore c'e' se l'host manda audio; il RIPRODUTTORE solo se c'e' anche una
        // scheda che lo suoni. Sono due cose distinte di proposito: un ospite senza scheda
        // audio deve poter giocare lo stesso, e tenere separati il decodificatore e l'uscita
        // significa anche che il percorso di rete e' collaudabile su una macchina che una
        // scheda audio non ce l'ha — cioe' su quasi ogni macchina di collaudo.
        if (session.audioFormat() != 0) {
            this.audioReceiver = new AudioReceiver();
            NetAudioPlayer player = new NetAudioPlayer(session.audioSampleRate());
            this.audioPlayer = player.start() ? player : null;
        } else {
            this.audioReceiver = null;
            this.audioPlayer = null;
        }
        this.audioSink = (pcm, off, frames) -> {
            NetAudioPlayer player = audioPlayer;
            if (player != null) {
                player.onPcm(pcm, off, frames);
            }
            AudioReceiver.PcmSink monitor = audioMonitor;
            if (monitor != null) {
                monitor.onPcm(pcm, off, frames);
            }
        };

        RemotePlaySettings.say("collegato a %s:%d — video %dx%d, audio %s, game port %d,"
                + " tastiera %s",
                host, session.mediaPort(), session.width(), session.height(),
                audioReceiver == null ? "spento"
                        : AudioBlockHeader.formatName(session.audioFormat()),
                session.guestGamePort(), session.keyboardShared() ? "condivisa" : "solo host");

        mediaThread = thread(this::mediaLoop, "amija-remoteplay-media");
        feedbackThread = thread(this::feedbackLoop, "amija-remoteplay-feedback");
        controlThread = thread(this::controlLoop, "amija-remoteplay-control");
    }

    /**
     * Perche' la sessione e' finita.
     *
     * <p>Esiste per una ragione sola, e non e' diagnostica: quando il collegamento cade
     * mentre si gioca, l'utente deve <b>sapere che e' caduto e perche'</b>. Una spia che si
     * spegne non lo dice a chi sta guardando il gioco, e un'ultima immagine congelata
     * somiglia troppo a un emulatore piantato. Il motivo lo conosce solo chi era in ascolto
     * sui socket, cioe' questa classe: da qui sale fino al messaggio che l'utente legge.
     */
    public enum EndCause {
        /** La sessione e' ancora viva. */
        RUNNING,
        /** L'abbiamo chiusa noi ({@link #close()}): nessuno da avvisare. */
        LOCAL,
        /** L'host ha salutato o ha chiuso il canale di controllo. */
        HOST_CLOSED,
        /** Nessun video per {@value #SESSION_TIMEOUT_MS} ms: il collegamento non c'e' piu'. */
        TIMEOUT,
        /** Un guasto sui socket; {@link #endDetail()} porta il messaggio di sistema. */
        ERROR
    }

    public boolean isAlive() {
        return alive;
    }

    /** Perche' la sessione e' finita, o {@link EndCause#RUNNING} se e' ancora viva. */
    public EndCause endCause() {
        return endCause;
    }

    /** Dettaglio tecnico del guasto (messaggio di sistema), o {@code null} se non c'e'. */
    public String endDetail() {
        return endDetail;
    }

    /** Vero quando l'host ha il popup F12 aperto: lo schermo e' fermo, non piantato (§9). */
    public boolean hostPaused() {
        return hostPaused;
    }

    public int width() {
        return session.width();
    }

    public int height() {
        return session.height();
    }

    public VideoReceiver receiver() {
        return receiver;
    }

    /**
     * La game port che l'host ha assegnato a questa macchina (0 o 1), dal WELCOME.
     *
     * <p>Non e' la stessa cosa di {@code config.remotePlayGuestPort()}: quella dice quale
     * porta <i>questa</i> macchina darebbe a un proprio ospite quando ospita. Qui si ospiti
     * non si e', e la porta la sceglie chi possiede la macchina emulata. E' il numero su cui
     * l'ospite deve leggere la propria tendina «Port 0»/«Port 1» del popup F12, perche' e'
     * quello a cui i suoi comandi arriveranno davvero.
     */
    public int gamePort() {
        return session.guestGamePort();
    }

    /** Se l'host ha concesso la tastiera. Lo decide lui: e' la sua macchina. */
    public boolean keyboardShared() {
        return session.keyboardShared();
    }

    /** {@code null} se l'audio non e' attivo (host che non lo manda, o niente scheda). */
    public AudioReceiver audioReceiver() {
        return audioReceiver;
    }

    /** {@code null} se non c'e' una scheda audio: si gioca in silenzio, non si fallisce. */
    public NetAudioPlayer audioPlayer() {
        return audioPlayer;
    }

    /**
     * Ascolta il PCM decodificato <b>in aggiunta</b> all'uscita audio, senza sostituirla.
     *
     * <p>Serve al collaudo e alla prova d'ascolto del §15.5, che ha bisogno di scrivere su
     * file quel che l'ospite sente davvero. Sostituire l'uscita invece che affiancarla
     * avrebbe voluto dire poter zittire il gioco per sbaglio.
     */
    public void setAudioMonitor(AudioReceiver.PcmSink monitor) {
        this.audioMonitor = monitor;
    }

    public MediaLink media() {
        return media;
    }

    /**
     * Installa chi riempie lo stato dei controlli prima di ogni invio.
     *
     * <p>{@code null} lascia il pacchetto a zero: la sessione resta viva e il video
     * continua ad arrivare, ma questa macchina non tocca niente. E' anche cio' che rende
     * collaudabile il trasporto senza costruire un input finto.
     */
    public void setInputSource(InputSource source) {
        this.inputSource = source;
    }

    /** Chi riempie i campi di input del pacchetto, chiamato a {@value #FEEDBACK_HZ} Hz. */
    public interface InputSource {
        void snapshot(FeedbackPacket out);
    }

    /**
     * Chiude la sessione e libera tutto.
     *
     * <p>La guardia e' su {@link #closed}, non su {@link #alive}: una sessione puo' essere
     * gia' <b>morta da se'</b> — l'host ha chiuso, il link e' caduto — e in quel caso i
     * socket, i tre thread e la linea audio sono ancora tutti aperti. Guardando {@code alive}
     * questa chiamata non avrebbe fatto niente proprio nel caso in cui c'e' piu' da chiudere.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        end(EndCause.LOCAL, null);   // no-op se era gia' finita per conto suo
        try {
            control.send(RemotePlayProtocol.CTRL_BYE);
        } catch (IOException ignored) {
            // Un saluto: se non parte, ci si disconnette lo stesso.
        }
        control.close();
        mediaSocket.close();
        join(mediaThread);
        join(feedbackThread);
        join(controlThread);
        if (audioPlayer != null) {
            audioPlayer.stop();
        }
        receiver.close();
        // L'impairment per ultimo, e a thread fermi: il thread di feedback potrebbe essere
        // dentro una send, e togliergli lo scheduler sotto i piedi trasformerebbe una
        // chiusura ordinata in un'eccezione su un thread che nessuno guarda.
        impairment.close();
    }

    // =====================================================================

    private Thread thread(Runnable body, String name) {
        Thread t = new Thread(body, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void join(Thread t) {
        try {
            t.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void mediaLoop() {
        while (alive) {
            MediaLink.Received received;
            try {
                received = media.receive(plain);
            } catch (IOException e) {
                if (alive) {
                    fail(EndCause.ERROR, "ricezione media interrotta: " + e.getMessage(),
                            e.getMessage());
                }
                return;
            }
            if (received == null) {
                if (!hostPaused
                        && System.nanoTime() - lastVideoNanos > SESSION_TIMEOUT_MS * 1_000_000L) {
                    fail(EndCause.TIMEOUT, "nessun video da " + SESSION_TIMEOUT_MS + " ms", null);
                    return;
                }
                continue;
            }
            if (received.type() == RemotePlayProtocol.TYPE_AUDIO) {
                if (audioReceiver != null) {
                    try {
                        audioReceiver.accept(plain, received.length(), audioSink);
                    } catch (ProtocolException e) {
                        // Un blocco audio scartato costa 23 ms di silenzio, e l'indice
                        // assoluto fa si' che il successivo riparta in fase da solo.
                        RemotePlaySettings.log("blocco audio scartato: %s", e.getMessage());
                    }
                }
                continue;
            }
            if (received.type() != RemotePlayProtocol.TYPE_VIDEO) {
                continue;   // TYPE_FEEDBACK in questo verso e' un errore del peer
            }
            lastVideoNanos = System.nanoTime();
            try {
                receiver.accept(plain, received.length(), sink);
            } catch (ProtocolException e) {
                // Il frame non e' stato ACKato, quindi l'host comprimera' il prossimo contro
                // un riferimento piu' vecchio: lo schermo non ha visto niente di sbagliato e
                // il sistema si e' gia' rimesso in pari da solo. Si continua.
                RemotePlaySettings.log("frame scartato: %s", e.getMessage());
            }
        }
    }

    /**
     * Manda lo stato dei controlli a ritmo fisso.
     *
     * <p>{@link LockSupport#parkNanos} invece di {@code Thread.sleep}: il quanto e' 5 ms e la
     * granularita' del sonno su Windows non lo rispetterebbe, mentre qui un ritardo si
     * recupera sul colpo successivo invece di accumularsi.
     */
    private void feedbackLoop() {
        final long periodNanos = 1_000_000_000L / FEEDBACK_HZ;
        long next = System.nanoTime();
        while (alive) {
            InputSource source = inputSource;
            if (source != null) {
                // Il campionamento sta QUI, non su un timer suo: il pacchetto porta lo stato
                // al momento in cui parte, e il latch appiccicoso scorre di un passo per
                // pacchetto spedito. Campionare altrove vorrebbe dire che il latch conta
                // campionamenti che non hanno mai attraversato la rete.
                source.snapshot(outgoing);
            }
            outgoing.seq++;
            outgoing.ackVideoFrameId = receiver.ackFrameId() < 0
                    ? RemotePlayProtocol.NO_REFERENCE : receiver.ackFrameId();
            outgoing.audioFillMs = audioPlayer == null ? 0 : audioPlayer.fillMillis();
            outgoing.writeTo(outgoingBytes);
            try {
                media.send(RemotePlayProtocol.TYPE_FEEDBACK, outgoingBytes, 0,
                        FeedbackPacket.SIZE);
            } catch (IOException e) {
                if (alive) {
                    fail(EndCause.ERROR, "invio del feedback interrotto: " + e.getMessage(),
                            e.getMessage());
                }
                return;
            }
            next += periodNanos;
            long wait = next - System.nanoTime();
            if (wait > 0) {
                LockSupport.parkNanos(wait);
            } else {
                next = System.nanoTime();   // rimasti indietro: si riparte da adesso
            }
        }
    }

    private void controlLoop() {
        try {
            control.setTimeout(POLL_MS);
            while (alive) {
                ControlChannel.Message m;
                try {
                    m = control.receive();
                } catch (SocketTimeoutException e) {
                    continue;
                }
                switch (m.type()) {
                    case RemotePlayProtocol.CTRL_PAUSED -> {
                        hostPaused = true;
                        RemotePlaySettings.say("l'host ha messo in pausa");
                    }
                    case RemotePlayProtocol.CTRL_RESUMED -> {
                        hostPaused = false;
                        lastVideoNanos = System.nanoTime();
                        RemotePlaySettings.say("l'host ha ripreso");
                    }
                    case RemotePlayProtocol.CTRL_PING ->
                            control.send(RemotePlayProtocol.CTRL_PONG);
                    case RemotePlayProtocol.CTRL_PONG -> { }
                    case RemotePlayProtocol.CTRL_BYE -> {
                        RemotePlaySettings.say("l'host ha chiuso la sessione");
                        end(EndCause.HOST_CLOSED, null);
                        return;
                    }
                    default -> RemotePlaySettings.log(
                            "messaggio di controllo inatteso: %d", m.type());
                }
            }
        } catch (EOFException e) {
            if (alive) {
                RemotePlaySettings.say("l'host ha chiuso il collegamento");
                end(EndCause.HOST_CLOSED, null);
            }
        } catch (IOException e) {
            if (alive) {
                fail(EndCause.ERROR, "canale di controllo interrotto: " + e.getMessage(),
                        e.getMessage());
            }
        }
    }

    private void fail(EndCause cause, String reason, String detail) {
        if (alive) {
            RemotePlaySettings.say("sessione chiusa: %s", reason);
            end(cause, detail);
        }
    }

    /**
     * Registra come e' finita e la dichiara finita, <b>in quest'ordine</b>.
     *
     * <p>L'ordine e' l'unica cosa non ovvia qui: chi sorveglia la sessione guarda
     * {@link #isAlive()} e, appena la vede finita, legge il motivo. Scrivendo {@code alive}
     * per primo esisterebbe una finestra in cui la sessione risulta finita senza motivo, e
     * l'utente riceverebbe l'avviso piu' generico invece di quello vero.
     */
    private void end(EndCause cause, String detail) {
        if (!alive) {
            return;   // la prima causa e' quella vera: le successive sono conseguenze
        }
        endDetail = detail;
        endCause = cause;
        alive = false;
    }
}
