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
 * Guest side of a remote play session: connects, receives the video and sends back its own
 * state ({@code docs/remote-play-plan.md} sections 3, 4).
 *
 * <h2>This machine emulates nothing</h2>
 * No Kickstart, no ROM, no {@code MasterClock} (section 3). The guest receives Denise's
 * <b>raw raster</b> and hands it to whoever draws it, which in the app is the same
 * {@code AmigaDisplay.renderFrame} the emulation uses: crop, integer scaling, overscan,
 * shaders, LEDs and fullscreen stay <b>local</b> choices of the guest, and work without one
 * line of that code knowing about the network.
 *
 * <h2>The feedback starts at once, and that matters</h2>
 * The guest's first datagram is what tells the host <b>where</b> to send the video: behind
 * NAT the source UDP port is not predictable, so the host adopts the sender of the first
 * datagram that opens with the session key ({@link MediaLink}). While the guest is silent,
 * the host has no address to talk to.
 *
 * <p>The rate is {@value #FEEDBACK_HZ} Hz: 44 bytes a time, i.e. 70 kbit/s (nothing), and in
 * exchange the part of input latency due to sampling is halved (sections 4.3, 5).
 */
public final class RemotePlayGuest implements AutoCloseable {

    /** Sampling and sending of the controls' state: 200 Hz, i.e. once every 5 ms. */
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
    /** {@code null} if the host sends no audio, or if there is no sound card here. */
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
     * Connects to a host, performs the handshake and starts receiving.
     *
     * @param sink who receives the rebuilt frames; may be {@code null} to just keep the
     *             session alive
     * @throws Handshake.RejectedException if the host refused, with the reason to show
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

        // The receiver exists if the host sends audio; the PLAYER only if there is also a
        // card to play it. They are separate on purpose: a guest without a sound card must
        // still be able to play, and keeping decoder and output apart also means the network
        // path can be tested on a machine with no sound card, i.e. on almost every test
        // machine.
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
     * Why the session ended.
     *
     * <p>It exists for one reason, and it is not diagnostics: when the connection drops while
     * playing, the user must <b>know that it dropped and why</b>. A light going off does not
     * tell someone watching the game, and a last frozen image looks too much like a hung
     * emulator. Only whoever was listening on the sockets knows the reason, i.e. this class:
     * from here it goes up to the message the user reads.
     */
    public enum EndCause {
        /** The session is still alive. */
        RUNNING,
        /** We closed it ({@link #close()}): nobody to notify. */
        LOCAL,
        /** The host said goodbye or closed the control channel. */
        HOST_CLOSED,
        /** No video for {@value #SESSION_TIMEOUT_MS} ms: the connection is gone. */
        TIMEOUT,
        /** A socket failure; {@link #endDetail()} carries the system message. */
        ERROR
    }

    public boolean isAlive() {
        return alive;
    }

    /** Why the session ended, or {@link EndCause#RUNNING} if it is still alive. */
    public EndCause endCause() {
        return endCause;
    }

    /** Technical detail of the failure (system message), or {@code null} if none. */
    public String endDetail() {
        return endDetail;
    }

    /** True while the host has the F12 popup open: the screen is still, not hung (section 9). */
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
     * The game port the host assigned to this machine (0 or 1), from the WELCOME.
     *
     * <p>Not the same as {@code config.remotePlayGuestPort()}: that says which port
     * <i>this</i> machine would give its own guest when hosting. Here it is the guest, and
     * the port is chosen by whoever owns the emulated machine. It is the number the guest
     * must read its own "Port 0"/"Port 1" drop-down of the F12 popup on, because that is the
     * one its commands will really reach.
     */
    public int gamePort() {
        return session.guestGamePort();
    }

    /** Whether the host granted the keyboard. The host decides: it is its machine. */
    public boolean keyboardShared() {
        return session.keyboardShared();
    }

    /** {@code null} if audio is not active (host not sending it, or no card). */
    public AudioReceiver audioReceiver() {
        return audioReceiver;
    }

    /** {@code null} if there is no sound card: play goes on in silence, it does not fail. */
    public NetAudioPlayer audioPlayer() {
        return audioPlayer;
    }

    /**
     * Listens to the decoded PCM <b>in addition</b> to the audio output, without replacing
     * it.
     *
     * <p>Used by the tests and by the listening check of section 15.5, which needs to write to
     * a file what the guest really hears. Replacing the output instead of flanking it would
     * make it possible to mute the game by mistake.
     */
    public void setAudioMonitor(AudioReceiver.PcmSink monitor) {
        this.audioMonitor = monitor;
    }

    public MediaLink media() {
        return media;
    }

    /**
     * Installs whatever fills in the controls' state before each send.
     *
     * <p>{@code null} leaves the packet at zero: the session stays alive and the video keeps
     * arriving, but this machine touches nothing. It is also what makes the transport
     * testable without building a fake input.
     */
    public void setInputSource(InputSource source) {
        this.inputSource = source;
    }

    /** Who fills the input fields of the packet, called at {@value #FEEDBACK_HZ} Hz. */
    public interface InputSource {
        void snapshot(FeedbackPacket out);
    }

    /**
     * Closes the session and releases everything.
     *
     * <p>The guard is on {@link #closed}, not on {@link #alive}: a session may already have
     * <b>died by itself</b> (the host closed, the link dropped), and in that case the sockets,
     * the three threads and the audio line are all still open. Guarding on {@code alive}
     * this call would do nothing exactly when there is most to close.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        end(EndCause.LOCAL, null);   // no-op if it had already ended on its own
        try {
            control.send(RemotePlayProtocol.CTRL_BYE);
        } catch (IOException ignored) {
            // A goodbye: if it does not go out, disconnect anyway.
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
        // The impairment last, with the threads stopped: the feedback thread could be inside
        // a send, and taking the scheduler from under it would turn an orderly close into an
        // exception on a thread nobody watches.
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
                        // A dropped audio block costs 23 ms of silence, and the absolute index
                        // makes the next one restart in phase by itself.
                        RemotePlaySettings.log("blocco audio scartato: %s", e.getMessage());
                    }
                }
                continue;
            }
            if (received.type() != RemotePlayProtocol.TYPE_VIDEO) {
                continue;   // TYPE_FEEDBACK in this direction is a peer error
            }
            lastVideoNanos = System.nanoTime();
            try {
                receiver.accept(plain, received.length(), sink);
            } catch (ProtocolException e) {
                // The frame was not ACKed, so the host will compress the next one against an
                // older reference: the screen has seen nothing wrong and the system has
                // already caught up by itself. Carry on.
                RemotePlaySettings.log("frame scartato: %s", e.getMessage());
            }
        }
    }

    /**
     * Sends the controls' state at a fixed rate.
     *
     * <p>{@link LockSupport#parkNanos} instead of {@code Thread.sleep}: the quantum is 5 ms and
     * the sleep granularity on Windows would not respect it, while here a delay is recovered
     * at the next tick instead of accumulating.
     */
    private void feedbackLoop() {
        final long periodNanos = 1_000_000_000L / FEEDBACK_HZ;
        long next = System.nanoTime();
        while (alive) {
            InputSource source = inputSource;
            if (source != null) {
                // The sampling is HERE, not on a timer of its own: the packet carries the
                // state at the moment it leaves, and the sticky latch advances one step per
                // packet sent. Sampling elsewhere would make the latch count samples that
                // never crossed the network.
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
                next = System.nanoTime();   // fell behind: restart from now
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
     * Records how it ended and declares it ended, <b>in this order</b>.
     *
     * <p>The order is the only non-obvious thing here: whoever watches the session looks at
     * {@link #isAlive()} and, as soon as it sees it ended, reads the reason. Writing
     * {@code alive} first would open a window in which the session reads as ended without a
     * reason, and the user would get the most generic warning instead of the real one.
     */
    private void end(EndCause cause, String detail) {
        if (!alive) {
            return;   // the first cause is the real one: later ones are consequences
        }
        endDetail = detail;
        endCause = cause;
        alive = false;
    }
}
