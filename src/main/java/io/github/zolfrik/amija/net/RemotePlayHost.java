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

import io.github.zolfrik.amija.display.FrameInfo;

import java.io.EOFException;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lato host di una sessione di gioco a distanza: ascolta, autentica un ospite, gli manda il
 * video e riceve il suo feedback ({@code docs/remote-play-plan.md} §3, §4, §11).
 *
 * <h2>Il contratto col thread di emulazione</h2>
 * {@link #submitFrame} e' l'unico metodo che il thread di emulazione chiama, e costa una
 * <b>copia del framebuffer</b> e nient'altro: nessuna codifica, nessun Deflate, nessuna
 * cifratura, nessuna {@code send} (§11.2). Se il thread di codifica e' ancora occupato col
 * frame precedente, questo <b>si salta</b>: e' la regola non negoziabile del §4.1, «mai
 * accodare piu' di un frame», ed e' lo stesso principio che {@code VirtualCRT} applica gia'
 * quando il renderer e' occupato. Accodare significherebbe costruire una spirale di latenza
 * da cui non si esce.
 *
 * <h2>Un ospite alla volta</h2>
 * L'Amiga ha due game port e l'host ne occupa una (§3). Una seconda connessione riceve
 * {@code REJECT busy} e viene chiusa: e' un rifiuto esplicito e immediato, non un'attesa.
 *
 * <h2>Perche' la stessa porta per TCP e UDP</h2>
 * Cosi' UPnP dovra' creare una sola coppia di mapping e l'utente ha un solo numero da
 * comunicare (§4). Sono protocolli diversi, quindi lo stesso numero non e' un conflitto —
 * ma i due bind sono comunque indipendenti, e con la porta effimera (0) il numero lo sceglie
 * il TCP e l'UDP deve seguirlo: se quel numero e' occupato in UDP si ritenta da capo.
 */
public final class RemotePlayHost implements AutoCloseable {

    /** Tentativi di trovare un numero libero <b>sia</b> in TCP sia in UDP, con porta 0. */
    private static final int EPHEMERAL_ATTEMPTS = 16;

    /** Ogni quanto il thread di accettazione si sveglia per controllare se deve fermarsi. */
    private static final int ACCEPT_POLL_MS = 200;

    /** Silenzio dell'ospite oltre il quale la sessione si considera persa. */
    private static final int SESSION_TIMEOUT_MS = 10_000;

    private final int width;
    private final int height;
    private final String joinCode;
    private final NetImpairment impairment;
    private final int guestGamePort;
    private final boolean guestKeyboard;
    /** Formato audio verso l'ospite, o 0 per non mandarne affatto (§4.2, terza opzione). */
    private final int audioFormat;
    /** Se accettare solo chi sta sulla stessa rete locale (§8.5). Acceso di default. */
    private final boolean lanOnly;

    private final ServerSocket controlSocket;
    private final DatagramSocket mediaSocket;
    private final int port;

    private volatile boolean running = true;
    private volatile boolean paused;
    private volatile Session session;
    private final AtomicLong sessionCounter = new AtomicLong();

    // Input dell'ospite: il thread di rete deposita, il thread di emulazione ritira.
    // pendingInput appartiene al thread di emulazione ed e' l'unico oggetto che attraversa
    // il confine — copiato sotto lock, letto fuori.
    private volatile GuestInputTarget inputTarget;
    private volatile boolean releaseInputPending;
    private final FeedbackPacket pendingInput = new FeedbackPacket();

    private Thread acceptThread;

    // --- consegna dei frame dal thread di emulazione ---------------------------------
    private final Object frameLock = new Object();
    private int[] pendingPixels;
    private int[] encodePixels;
    private final FrameInfo pendingInfo = new FrameInfo();
    private final FrameInfo encodeInfo = new FrameInfo();
    private boolean framePending;
    private long framesSubmitted;
    private long framesDroppedBusy;

    /**
     * Apre le porte, senza ancora accettare nessuno.
     *
     * @param port       porta TCP e UDP; 0 per lasciarla scegliere al sistema (test)
     * @param bind       interfaccia su cui ascoltare, {@code null} per tutte
     * @param joinCode   codice di accesso; {@code null} per generarne uno nuovo
     * @param impairment simulazione di link, {@link NetImpairment#NONE} in produzione
     */
    public RemotePlayHost(int port, String bind, String joinCode, int width, int height,
                          NetImpairment impairment) throws IOException {
        this(port, bind, joinCode, width, height, impairment,
                RemotePlaySettings.DEFAULT_GUEST_GAME_PORT, true,
                AudioBlockHeader.FORMAT_ADPCM, true);
    }

    /**
     * @param guestGamePort game port assegnata all'ospite (0 o 1)
     * @param guestKeyboard se l'ospite condivide la tastiera
     * @param audioFormat   {@link AudioBlockHeader#FORMAT_PCM16},
     *                      {@link AudioBlockHeader#FORMAT_ADPCM}, o 0 per non mandare audio
     * @param lanOnly       se rifiutare chi non e' sulla stessa rete locale (§8.5)
     */
    public RemotePlayHost(int port, String bind, String joinCode, int width, int height,
                          NetImpairment impairment, int guestGamePort, boolean guestKeyboard,
                          int audioFormat, boolean lanOnly) throws IOException {
        this.width = width;
        this.height = height;
        this.lanOnly = lanOnly;
        this.joinCode = joinCode == null || joinCode.isBlank()
                ? JoinCode.generate() : joinCode;
        this.impairment = impairment == null ? NetImpairment.NONE : impairment;
        this.guestGamePort = guestGamePort;
        this.guestKeyboard = guestKeyboard;
        this.audioFormat = audioFormat;
        this.pendingPixels = new int[width * height];
        this.encodePixels = new int[width * height];

        InetAddress address = bind == null || bind.isBlank()
                ? null : InetAddress.getByName(bind);
        ServerSocket tcp = null;
        DatagramSocket udp = null;
        // I tentativi scartati si tengono APERTI fino alla fine, e non e' spreco: chiudendo
        // il ServerSocket prima di riprovare, il sistema torna a proporre lo stesso numero —
        // e se quel numero e' occupato in UDP, gli otto tentativi sono otto volte lo stesso
        // tentativo. Tenendoli aperti si costringe a proporne uno diverso ogni volta.
        List<ServerSocket> discarded = new ArrayList<>();
        try {
            for (int attempt = 0; ; attempt++) {
                ServerSocket candidate = new ServerSocket(port, 4, address);
                try {
                    udp = new DatagramSocket(candidate.getLocalPort(), address);
                    tcp = candidate;
                    break;
                } catch (SocketException e) {
                    // Con una porta esplicita non c'e' niente da ritentare: e' occupata.
                    if (port != 0 || attempt + 1 >= EPHEMERAL_ATTEMPTS) {
                        int busy = candidate.getLocalPort();
                        candidate.close();
                        throw new IOException("porta " + busy
                                + " libera in TCP ma occupata in UDP", e);
                    }
                    discarded.add(candidate);
                }
            }
        } finally {
            for (ServerSocket s : discarded) {
                try {
                    s.close();
                } catch (IOException ignored) {
                    // Erano solo prenotazioni per non farsi riproporre lo stesso numero.
                }
            }
        }
        this.controlSocket = tcp;
        this.mediaSocket = udp;
        this.port = tcp.getLocalPort();
        this.controlSocket.setSoTimeout(ACCEPT_POLL_MS);
    }

    /** Comincia ad accettare. Torna subito: l'attesa vive sul suo thread. */
    public void start() {
        acceptThread = new Thread(this::acceptLoop, "amija-remoteplay-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        RemotePlaySettings.say("in ascolto su %d (TCP+UDP), join code %s", port, joinCode);
    }

    public int port() {
        return port;
    }

    public String joinCode() {
        return joinCode;
    }

    public boolean hasGuest() {
        Session s = session;
        return s != null && s.alive;
    }

    /**
     * Consegna un frame appena completato. <b>Chiamato dal thread di emulazione.</b>
     *
     * <p>Se non c'e' nessun ospite non fa niente. Se il thread di codifica non ha ancora
     * preso il frame precedente, questo si perde — apposta.
     */
    public void submitFrame(int[] pixels, FrameInfo info) {
        Session s = session;
        if (s == null || !s.alive) {
            return;
        }
        synchronized (frameLock) {
            if (framePending) {
                framesDroppedBusy++;
                return;
            }
            System.arraycopy(pixels, 0, pendingPixels, 0, pendingPixels.length);
            pendingInfo.copyFrom(info);
            framePending = true;
            framesSubmitted++;
            frameLock.notifyAll();
        }
    }

    /** Frame consegnati dall'emulazione (quelli saltati non contano). */
    public long framesSubmitted() {
        return framesSubmitted;
    }

    /** Frame saltati perche' la codifica era ancora occupata: la regola «mai accodare». */
    public long framesDroppedBusy() {
        return framesDroppedBusy;
    }

    /** Statistiche del video spedito, o {@code null} se non c'e' sessione. */
    public VideoSender videoSender() {
        Session s = session;
        return s == null ? null : s.videoSender;
    }

    /** Statistiche dell'audio spedito, o {@code null} se non c'e' sessione o e' spento. */
    public AudioSender audioSender() {
        Session s = session;
        return s == null ? null : s.audioSender;
    }

    /**
     * Consegna un chunk audio gia' convertito e filtrato.
     *
     * <p><b>Chiamato dal thread audio</b>, non da quello di emulazione: e' gia' fuori dal
     * percorso caldo (§4.2). A differenza del video non c'e' un thread di codifica in mezzo —
     * comprimere 1024 campioni in ADPCM sono microsecondi, e attraversare un altro confine
     * di thread costerebbe piu' del lavoro.
     */
    public void submitAudio(byte[] pcm, int frames) {
        Session s = session;
        if (s == null || !s.alive || s.audioSender == null) {
            return;
        }
        try {
            s.audioSender.sendBlock(pcm, frames,
                    (p, n) -> s.media.send(RemotePlayProtocol.TYPE_AUDIO, p, 0, n));
        } catch (IOException e) {
            s.fail("invio audio interrotto: " + e.getMessage());
        }
    }

    /**
     * Installa chi porta i controlli dell'ospite dentro la macchina.
     *
     * <p>{@code null} li scollega: l'ospite continua a vedere il video ma non tocca piu'
     * niente. E' quel che serve quando la macchina viene ricostruita (riavvio a freddo) e
     * la game port di prima non esiste piu'.
     */
    public void setInputTarget(GuestInputTarget target) {
        this.inputTarget = target;
    }

    /**
     * Applica alla macchina l'ultimo stato ricevuto dall'ospite.
     *
     * <p><b>Chiamato dal thread di emulazione, una volta per frame</b>, al confine di frame:
     * e' il punto di quantizzazione fisso e riproducibile del §4.3. Il thread di rete si
     * limita a depositare lo stato; qui lo si copia sotto lock — 44 byte, decine di
     * nanosecondi — e lo si applica <b>fuori</b> dal lock, perche' dentro ci sono chiamate
     * alla tastiera e alla game port che non hanno niente da spartire con la rete.
     *
     * <p>Se non e' arrivato niente di nuovo non fa nulla: riapplicare lo stesso stato
     * sarebbe innocuo per i tasti (la differenza sarebbe vuota) ma non per il mouse, dove
     * costerebbe una chiamata inutile a ogni frame.
     */
    public void applyPendingInput() {
        GuestInputTarget target = inputTarget;
        if (target == null) {
            return;
        }
        if (releaseInputPending) {
            // L'ospite se n'e' andato. Il rilascio deve avvenire QUI e non nel thread che
            // ha chiuso la sessione, perche' tocca tastiera e game port: e' lo stesso
            // motivo per cui l'input si applica qui e non dal thread di rete.
            releaseInputPending = false;
            target.releaseAll();
            return;
        }
        Session s = session;
        if (s == null || !s.alive) {
            return;
        }
        synchronized (s.inputLock) {
            if (!s.inputDirty) {
                return;
            }
            s.lastApplied.copyTo(pendingInput);
            s.inputDirty = false;
        }
        target.apply(pendingInput);
    }

    /** L'ultimo stato dei controlli ricevuto dall'ospite, per diagnostica. */
    public FeedbackPacket lastInput() {
        Session s = session;
        return s == null ? null : s.lastApplied;
    }

    /**
     * Avvisa l'ospite che l'host e' in pausa (F12 aperto, §9).
     *
     * <p>Lo stato si <b>ricorda</b>, e non e' un dettaglio: la sessione si accende dal
     * popup, quindi il caso normale e' che l'ospite si colleghi <i>mentre il popup e'
     * ancora aperto</i> — l'host sta leggendo il codice all'altro giocatore al telefono.
     * In quel momento la macchina e' ferma e non esce un fotogramma: senza dirglielo,
     * l'ospite considererebbe caduto il collegamento dopo dieci secondi e si
     * scollegherebbe. Percio' chi si collega a host fermo riceve subito il PAUSED.
     */
    public void notifyPaused(boolean paused) {
        this.paused = paused;
        Session s = session;
        if (s != null && s.alive) {
            sendPauseState(s, paused);
        }
    }

    private void sendPauseState(Session s, boolean paused) {
        try {
            s.control.send(paused ? RemotePlayProtocol.CTRL_PAUSED
                    : RemotePlayProtocol.CTRL_RESUMED);
        } catch (IOException e) {
            s.fail("invio di PAUSED/RESUMED fallito: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        running = false;
        Session s = session;
        if (s != null) {
            s.stop(RemotePlayProtocol.REJECT_SHUTDOWN);
        }
        try {
            controlSocket.close();
        } catch (IOException ignored) {
            // Fine sessione: non c'e' niente da recuperare da una chiusura fallita.
        }
        mediaSocket.close();
        synchronized (frameLock) {
            frameLock.notifyAll();
        }
        if (acceptThread != null) {
            join(acceptThread);
        }
        // I thread di sessione prima dell'impairment, e l'ordine conta: il thread di
        // codifica potrebbe essere dentro una send, e chiudergli il link sotto i piedi
        // trasformerebbe una chiusura ordinata in una eccezione su un thread che nessuno
        // guarda. Chi ha aperto per ultimo chiude per primo.
        if (s != null) {
            s.awaitStop();
        }
        impairment.close();
    }

    private static void join(Thread t) {
        if (t == null || t == Thread.currentThread()) {
            return;
        }
        try {
            t.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // =====================================================================
    // Accettazione
    // =====================================================================

    private void acceptLoop() {
        while (running) {
            Socket socket;
            try {
                socket = controlSocket.accept();
            } catch (SocketTimeoutException e) {
                continue;
            } catch (IOException e) {
                if (running) {
                    RemotePlaySettings.say("accettazione interrotta: %s", e.getMessage());
                }
                return;
            }
            ControlChannel channel = null;
            try {
                channel = new ControlChannel(socket);
                if (lanOnly && !isLocalNetwork(socket.getInetAddress())) {
                    // §8.5: finche' l'utente non accende l'esposizione su Internet, da
                    // fuori non si entra — e non si entra PRIMA dell'autenticazione, che e'
                    // il punto: chi non e' sulla rete di casa non deve nemmeno poter
                    // provare il codice.
                    Handshake.reject(channel, RemotePlayProtocol.REJECT_LAN_ONLY);
                    channel.closeGracefully();
                    RemotePlaySettings.say("rifiutato %s: solo rete locale",
                            socket.getRemoteSocketAddress());
                    continue;
                }
                if (hasGuest()) {
                    Handshake.reject(channel, RemotePlayProtocol.REJECT_BUSY);
                    channel.closeGracefully();
                    RemotePlaySettings.say("rifiutato %s: c'e' gia' un ospite",
                            socket.getRemoteSocketAddress());
                    continue;
                }
                startSession(channel);
            } catch (IOException e) {
                RemotePlaySettings.say("handshake fallito con %s: %s",
                        socket.getRemoteSocketAddress(), e.getMessage());
                // closeGracefully e non close: se il fallimento e' stato un REJECT, quel
                // messaggio e' ancora in coda e una chiusura brusca lo cancellerebbe.
                if (channel != null) {
                    channel.closeGracefully();
                } else {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                        // idem
                    }
                }
            }
        }
    }

    /**
     * Vero se questo indirizzo sta su una rete a cui questa macchina e' attaccata.
     *
     * <p>Non basta «e' un indirizzo privato»: fra due reti private diverse — una VPN, un
     * altro edificio dietro un tunnel — c'e' esattamente la distanza che questa opzione dice
     * di non voler superare. Si confrontano quindi i bit di rete con la maschera vera di
     * ciascuna interfaccia, che e' l'unica definizione di «la mia rete» che il sistema
     * conosca. Il loopback passa sempre: e' questa stessa macchina.
     */
    static boolean isLocalNetwork(InetAddress peer) {
        if (peer == null) {
            return false;
        }
        if (peer.isLoopbackAddress() || peer.isAnyLocalAddress() || peer.isLinkLocalAddress()) {
            return true;
        }
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface nic = interfaces.nextElement();
                if (!nic.isUp()) {
                    continue;
                }
                for (InterfaceAddress local : nic.getInterfaceAddresses()) {
                    if (sameSubnet(local, peer)) {
                        return true;
                    }
                }
            }
        } catch (IOException e) {
            // Senza l'elenco delle interfacce non si puo' dimostrare che sia locale, e in
            // dubbio si rifiuta: e' un'opzione di sicurezza, non un'ottimizzazione.
            RemotePlaySettings.log("interfacce non elencabili: %s", e.getMessage());
        }
        return false;
    }

    /** Confronta i primi {@code prefixLength} bit dei due indirizzi. */
    private static boolean sameSubnet(InterfaceAddress local, InetAddress peer) {
        byte[] mine = local.getAddress().getAddress();
        byte[] theirs = peer.getAddress();
        int prefix = local.getNetworkPrefixLength();
        if (mine.length != theirs.length || prefix <= 0 || prefix > mine.length * 8) {
            return false;
        }
        int fullBytes = prefix / 8;
        for (int i = 0; i < fullBytes; i++) {
            if (mine[i] != theirs[i]) {
                return false;
            }
        }
        int spareBits = prefix % 8;
        if (spareBits == 0) {
            return true;
        }
        int mask = 0xFF << (8 - spareBits);
        return (mine[fullBytes] & mask) == (theirs[fullBytes] & mask);
    }

    private void startSession(ControlChannel channel) throws IOException {
        int gen = (int) sessionCounter.incrementAndGet();
        Handshake.HostOffer offer = new Handshake.HostOffer(
                gen, gen, port, width, height, 44100, 2, audioFormat,
                guestGamePort, guestKeyboard);
        Handshake.Session negotiated = Handshake.accept(channel, joinCode, offer);

        Session s = new Session(channel, negotiated);
        session = s;
        s.start();
        if (paused) {
            // Host fermo (popup aperto): senza questo, l'ospite aspetta un video che non
            // arriva e dopo dieci secondi molla.
            sendPauseState(s, true);
        }
        RemotePlaySettings.say("ospite collegato da %s (AmiJa %s), generazione %d",
                channel.socket().getRemoteSocketAddress(), negotiated.peerAppVersion(), gen);
    }

    // =====================================================================
    // Una sessione
    // =====================================================================

    private final class Session {

        private final ControlChannel control;
        private final MediaLink media;
        private final VideoSender videoSender;
        private final AudioSender audioSender;
        private final FeedbackPacket incoming = new FeedbackPacket();
        private final FeedbackPacket lastApplied = new FeedbackPacket();
        private final Object inputLock = new Object();
        private boolean inputDirty;
        private final byte[] plain = new byte[RemotePlayProtocol.MAX_PLAINTEXT];
        private final int sessionGen;

        private volatile boolean alive = true;
        private volatile long lastFeedbackNanos = System.nanoTime();
        private long lastFeedbackSeq = -1;
        private Thread encodeThread;
        private Thread feedbackThread;
        private Thread controlThread;

        Session(ControlChannel control, Handshake.Session negotiated) throws IOException {
            this.control = control;
            this.sessionGen = negotiated.sessionGen();
            this.media = new MediaLink(mediaSocket,
                    negotiated.cipher(Handshake.DIRECTION_HOST), null, impairment);
            this.media.setTimeout(ACCEPT_POLL_MS);
            this.videoSender = new VideoSender(width, height);
            this.audioSender = audioFormat == 0 ? null : new AudioSender(audioFormat);
            this.lastApplied.clear();
        }

        void start() {
            synchronized (frameLock) {
                framePending = false;      // niente resti del collegamento precedente
            }
            encodeThread = thread(this::encodeLoop, "amija-remoteplay-encode");
            feedbackThread = thread(this::feedbackLoop, "amija-remoteplay-feedback");
            controlThread = thread(this::controlLoop, "amija-remoteplay-control");
        }

        private Thread thread(Runnable body, String name) {
            Thread t = new Thread(body, name);
            t.setDaemon(true);
            t.start();
            return t;
        }

        /** Prende il frame pendente e lo spedisce. E' l'unico thread che codifica. */
        private void encodeLoop() {
            try {
                while (alive && running) {
                    synchronized (frameLock) {
                        while (!framePending && alive && running) {
                            frameLock.wait(ACCEPT_POLL_MS);
                        }
                        if (!alive || !running) {
                            return;
                        }
                        int[] tmp = encodePixels;
                        encodePixels = pendingPixels;
                        pendingPixels = tmp;
                        encodeInfo.copyFrom(pendingInfo);
                        framePending = false;
                    }
                    videoSender.sendFrame(encodePixels, encodeInfo,
                            (p, n) -> media.send(RemotePlayProtocol.TYPE_VIDEO, p, 0, n));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                fail("invio video interrotto: " + e.getMessage());
            } finally {
                videoSender.close();
            }
        }

        /** Riceve il feedback dell'ospite: l'ACK video e, dalla Fase 3, gli input. */
        private void feedbackLoop() {
            while (alive && running) {
                MediaLink.Received received;
                try {
                    received = media.receive(plain);
                } catch (IOException e) {
                    if (alive && running) {
                        fail("ricezione media interrotta: " + e.getMessage());
                    }
                    return;
                }
                if (received == null) {
                    if (System.nanoTime() - lastFeedbackNanos > SESSION_TIMEOUT_MS * 1_000_000L) {
                        fail("l'ospite non risponde da " + SESSION_TIMEOUT_MS + " ms");
                        return;
                    }
                    continue;
                }
                if (received.type() != RemotePlayProtocol.TYPE_FEEDBACK) {
                    // Il verso host->ospite non ha nulla da fare qui: e' un errore del peer.
                    RemotePlaySettings.log("datagramma di tipo %d ignorato", received.type());
                    continue;
                }
                try {
                    incoming.readFrom(plain, received.length());
                } catch (ProtocolException e) {
                    RemotePlaySettings.log("feedback scartato: %s", e.getMessage());
                    continue;
                }
                lastFeedbackNanos = System.nanoTime();
                if (incoming.sessionGen != sessionGen) {
                    continue;      // resto della generazione precedente, in volo dal passato
                }
                if (lastFeedbackSeq >= 0
                        && Integer.compareUnsigned(incoming.seq, (int) lastFeedbackSeq) <= 0) {
                    continue;      // vecchio o ripetuto: lo stato buono e' gia' quello applicato
                }
                lastFeedbackSeq = incoming.seq & 0xFFFFFFFFL;
                if (incoming.hasAck()) {
                    videoSender.noteAck(incoming.ackVideoFrameId);
                }
                synchronized (inputLock) {
                    incoming.copyTo(lastApplied);
                    inputDirty = true;
                }
            }
        }

        /** Il canale di controllo dopo l'handshake: eventi rari, e la fine della sessione. */
        private void controlLoop() {
            try {
                control.setTimeout(SESSION_TIMEOUT_MS);
                while (alive && running) {
                    ControlChannel.Message m;
                    try {
                        m = control.receive();
                    } catch (SocketTimeoutException e) {
                        continue;
                    }
                    switch (m.type()) {
                        case RemotePlayProtocol.CTRL_PING ->
                                control.send(RemotePlayProtocol.CTRL_PONG);
                        case RemotePlayProtocol.CTRL_PONG -> { }
                        case RemotePlayProtocol.CTRL_BYE -> {
                            RemotePlaySettings.say("l'ospite si e' disconnesso");
                            stop(-1);
                            return;
                        }
                        default -> RemotePlaySettings.log(
                                "messaggio di controllo inatteso: %d", m.type());
                    }
                }
            } catch (EOFException e) {
                if (alive) {
                    RemotePlaySettings.say("l'ospite ha chiuso il collegamento");
                    stop(-1);
                }
            } catch (IOException e) {
                if (alive && running) {
                    fail("canale di controllo interrotto: " + e.getMessage());
                }
            }
        }

        /** Aspetta che i tre thread di sessione siano davvero finiti. Mai dall'interno. */
        void awaitStop() {
            join(encodeThread);
            join(feedbackThread);
            join(controlThread);
        }

        void fail(String reason) {
            if (alive) {
                RemotePlaySettings.say("sessione chiusa: %s", reason);
            }
            stop(-1);
        }

        void stop(int rejectReason) {
            if (!alive) {
                return;
            }
            alive = false;
            // Quel che l'ospite teneva premuto va rilasciato, o resta premuto per sempre.
            releaseInputPending = true;
            if (rejectReason >= 0) {
                try {
                    control.send(RemotePlayProtocol.CTRL_BYE,
                            new byte[] { (byte) rejectReason }, 1);
                } catch (IOException ignored) {
                    // Un saluto: se non parte, la sessione finisce lo stesso.
                }
            }
            control.close();
            synchronized (frameLock) {
                frameLock.notifyAll();
            }
            if (session == this) {
                session = null;
            }
        }
    }
}
