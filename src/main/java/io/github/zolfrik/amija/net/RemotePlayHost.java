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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Host side of a remote play session: listens, authenticates a guest, sends it the video
 * and receives its feedback ({@code docs/remote-play-plan.md} sections 3, 4, 11).
 *
 * <h2>The contract with the emulation thread</h2>
 * {@link #submitFrame} is the only method the emulation thread calls, and it costs <b>one
 * copy of the framebuffer</b> and nothing else: no encoding, no Deflate, no encryption, no
 * {@code send} (section 11.2). If the encoding thread is still busy with the previous frame,
 * this one <b>is skipped</b>: the non-negotiable rule of section 4.1, "never queue more than
 * one frame", the same principle {@code VirtualCRT} applies when the renderer is busy.
 * Queueing would build a latency spiral with no way out.
 *
 * <h2>One guest at a time, and the spectators</h2>
 * The Amiga has two game ports and the host takes one (section 3). A second player gets
 * {@code REJECT busy} and is closed: an explicit, immediate refusal, not a wait.
 *
 * <p>Besides the player, up to {@code maxSpectators} peers may <b>watch</b> (section 16). They
 * come in with a code of their own, receive one shared stream ({@link SpectatorBroadcast}) and
 * send nothing that reaches the machine. With spectators off the door works exactly as before:
 * the player's place is taken before the handshake, and a second connection is refused at once.
 * With spectators on the role is known only from the HELLO, so the place is taken there; the
 * immediate refusal stays for when the player's place and every spectator place are taken.
 *
 * <h2>One thread reads the UDP socket</h2>
 * Player and spectators share the media socket, so a single thread ({@link #mediaLoop}) reads
 * it and hands each datagram to the endpoint of its sender. Two threads reading the same socket
 * would steal each other's datagrams.
 *
 * <h2>Why the same port for TCP and UDP</h2>
 * So UPnP has to create a single pair of mappings and the user has a single number to pass
 * on (section 4). They are different protocols, so the same number is not a conflict; but
 * the two binds are still independent, and with the ephemeral port (0) TCP chooses the
 * number and UDP must follow: if that number is taken in UDP the whole thing is retried.
 */
public final class RemotePlayHost implements AutoCloseable {

    /** Attempts to find a number free in <b>both</b> TCP and UDP, with port 0. */
    private static final int EPHEMERAL_ATTEMPTS = 16;

    /** How often the accepting thread wakes up to check whether it must stop. */
    private static final int ACCEPT_POLL_MS = 200;

    /** Guest silence beyond which the session is considered lost. */
    private static final int SESSION_TIMEOUT_MS = 10_000;

    /** Refusals that may be queued for a graceful close. Beyond that, the close is abrupt. */
    private static final int REJECT_QUEUE = 16;

    /**
     * Handshakes in flight at once. One is all a player needs; with spectators a few may arrive
     * together, and a silent peer must not hold the door for the others. Beyond this, the
     * newcomer is refused at once instead of queueing.
     */
    private static final int HANDSHAKE_THREADS = 4;

    /** How often the spectators' frame rate is reconsidered against the player's link. */
    private static final long PLAYER_HEALTH_PERIOD_NANOS = 1_000_000_000L;

    /** Healthy periods needed before the spectators get back a step of frame rate. */
    private static final int PLAYER_HEALTHY_PERIODS = 5;

    /** Thinnest spectator stream: one frame in eight. */
    private static final int MAX_SPECTATOR_DIVISOR = 8;

    /**
     * Overall limit of a handshake, beyond which the socket is closed and the guest's place
     * is free again. See {@link #startHandshakeDeadline}.
     */
    static final int HANDSHAKE_BUDGET_MS = 12_000;

    private final int width;
    private final int height;
    private final String joinCode;
    private final NetImpairment impairment;
    private final int guestGamePort;
    private final boolean guestKeyboard;
    /** Audio format toward the guest, or 0 to send none at all (section 4.2, third option). */
    private final int audioFormat;
    /** Whether to accept only peers on the same local network (section 8.5). On by default. */
    private final boolean lanOnly;
    /** Spectator places; 0 turns spectators off and keeps the door as it always was. */
    private final int maxSpectators;
    /** Code that admits a spectator; {@code null} when spectators are off. */
    private final String spectatorCode;
    /** Spectator places taken, by sessions alive or handshakes in progress. */
    private final java.util.concurrent.atomic.AtomicInteger spectatorSlots =
            new java.util.concurrent.atomic.AtomicInteger();
    /** {@code null} when spectators are off. */
    private final SpectatorBroadcast broadcast;
    private Thread mediaThread;
    private long nextHealthCheckNanos;
    private long playerKeyframesSeen;
    private int playerHealthyPeriods;

    private final ServerSocket controlSocket;
    private final DatagramSocket mediaSocket;
    private final int port;

    private volatile boolean running = true;
    private volatile boolean paused;
    private volatile Session session;
    private final AtomicLong sessionCounter = new AtomicLong();

    // Guest input: the network thread deposits it, the emulation thread collects it.
    // pendingInput belongs to the emulation thread and is the only object that crosses the
    // boundary: copied under the lock, read outside it.
    private volatile GuestInputTarget inputTarget;
    private volatile boolean releaseInputPending;
    private final FeedbackPacket pendingInput = new FeedbackPacket();

    private Thread acceptThread;
    /**
     * The single guest's place: taken while a session is alive <b>or</b> while a handshake
     * is in progress.
     *
     * <p>The handshake does not run on the accepting thread (see {@link #acceptLoop()}), so
     * "is there already a guest?" and "take the place" happen on two threads and must be
     * made one step again; otherwise two connections arriving together would both pass the
     * check and the second would overwrite the first one's session.
     */
    private final AtomicBoolean guestSlot = new AtomicBoolean();

    /**
     * The place is taken. Visible to the tests, which need to look at it <b>directly</b>:
     * the guarantee to check is that the place is free at the instant the guest holds the
     * refusal, and inferring it from the reason of the next refusal turns it into a race
     * between two TCP round trips.
     */
    boolean guestSlotTaken() {
        return guestSlot.get();
    }

    /** How fast the join code may be got wrong, per address (section 8). */
    private final AuthThrottle throttle = new AuthThrottle();

    /**
     * Where the handshake runs. A single thread: the guest's place is one, so more than one
     * handshake at a time makes no sense, and whoever arrives while it is taken gets REJECT
     * busy at once instead of queueing.
     */
    private ExecutorService handshakeExecutor;

    /**
     * Who completes the quick refusals, off the accepting thread.
     *
     * <p>{@link ControlChannel#closeGracefully()} waits for the peer so the reason of the
     * refusal arrives instead of an RST that wipes it out, which is right. But that wait,
     * done <b>on the accepting thread</b>, is time in which nobody else can connect: a peer
     * that opens a socket, takes the refusal and then stays silent would make the legitimate
     * guest knocking at the door pay in delay. And the three quick refusals (too many
     * errors, session busy, local network only) do not go through the handshake, so there is
     * not even the throttle to stop whoever repeats them.
     *
     * <p>Here the wait belongs to someone else. The queue is short on purpose: if it fills
     * up (under a burst, the only case in which it happens) the next refusal is closed
     * abruptly. Losing the courtesy of the reason under attack is exactly the right way to
     * degrade; losing the listening is not.
     */
    private ExecutorService rejectExecutor;

    // --- frame delivery from the emulation thread ------------------------------------
    private final Object frameLock = new Object();
    private int[] pendingPixels;
    private int[] encodePixels;
    private final FrameInfo pendingInfo = new FrameInfo();
    private final FrameInfo encodeInfo = new FrameInfo();
    private boolean framePending;
    private long framesSubmitted;
    private long framesDroppedBusy;

    /**
     * Opens the ports, without accepting anyone yet.
     *
     * @param port       TCP and UDP port; 0 to let the system choose (tests)
     * @param bind       interface to listen on, {@code null} for all
     * @param joinCode   access code; {@code null} to generate a new one
     * @param impairment link simulation, {@link NetImpairment#NONE} in production
     */
    public RemotePlayHost(int port, String bind, String joinCode, int width, int height,
                          NetImpairment impairment) throws IOException {
        this(port, bind, joinCode, width, height, impairment,
                RemotePlaySettings.DEFAULT_GUEST_GAME_PORT, true,
                AudioBlockHeader.FORMAT_ADPCM, true);
    }

    /**
     * @param guestGamePort game port assigned to the guest (0 or 1)
     * @param guestKeyboard whether the guest shares the keyboard
     * @param audioFormat   {@link AudioBlockHeader#FORMAT_PCM16},
     *                      {@link AudioBlockHeader#FORMAT_ADPCM}, or 0 to send no audio
     * @param lanOnly       whether to refuse peers not on the same local network (section 8.5)
     */
    public RemotePlayHost(int port, String bind, String joinCode, int width, int height,
                          NetImpairment impairment, int guestGamePort, boolean guestKeyboard,
                          int audioFormat, boolean lanOnly) throws IOException {
        this(port, bind, joinCode, width, height, impairment, guestGamePort, guestKeyboard,
                audioFormat, lanOnly, 0, null);
    }

    /**
     * @param maxSpectators spectator places (section 16); 0 turns spectators off
     * @param spectatorCode code that admits a spectator; {@code null} to generate one
     */
    public RemotePlayHost(int port, String bind, String joinCode, int width, int height,
                          NetImpairment impairment, int guestGamePort, boolean guestKeyboard,
                          int audioFormat, boolean lanOnly, int maxSpectators,
                          String spectatorCode) throws IOException {
        if (maxSpectators < 0) {
            throw new IllegalArgumentException("negative spectator places: " + maxSpectators);
        }
        this.width = width;
        this.height = height;
        this.lanOnly = lanOnly;
        this.joinCode = joinCode == null || joinCode.isBlank()
                ? JoinCode.generate() : joinCode;
        this.maxSpectators = maxSpectators;
        if (maxSpectators == 0) {
            this.spectatorCode = null;
        } else if (spectatorCode != null && !spectatorCode.isBlank()) {
            if (spectatorCode.equals(this.joinCode)) {
                // One code for both roles would let every spectator play.
                throw new IllegalArgumentException(
                        "the spectator code must differ from the guest's");
            }
            this.spectatorCode = spectatorCode;
        } else {
            String generated;
            do {
                generated = JoinCode.generate();
            } while (generated.equals(this.joinCode));
            this.spectatorCode = generated;
        }
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
        // The discarded attempts are kept OPEN until the end, and it is not waste: closing
        // the ServerSocket before retrying, the system offers the same number again, and if
        // that number is taken in UDP, the eight attempts are eight times the same attempt.
        // Keeping them open forces a different number each time.
        List<ServerSocket> discarded = new ArrayList<>();
        try {
            for (int attempt = 0; ; attempt++) {
                ServerSocket candidate = new ServerSocket(port, 4, address);
                try {
                    udp = new DatagramSocket(candidate.getLocalPort(), address);
                    tcp = candidate;
                    break;
                } catch (SocketException e) {
                    // With an explicit port there is nothing to retry: it is taken.
                    if (port != 0 || attempt + 1 >= EPHEMERAL_ATTEMPTS) {
                        int busy = candidate.getLocalPort();
                        candidate.close();
                        throw new IOException("port " + busy
                                + " free on TCP but busy on UDP", e);
                    }
                    discarded.add(candidate);
                }
            }
        } finally {
            for (ServerSocket s : discarded) {
                try {
                    s.close();
                } catch (IOException ignored) {
                    // They were only reservations so the same number would not be offered again.
                }
            }
        }
        this.controlSocket = tcp;
        this.mediaSocket = udp;
        this.port = tcp.getLocalPort();
        this.controlSocket.setSoTimeout(ACCEPT_POLL_MS);
        this.mediaSocket.setSoTimeout(ACCEPT_POLL_MS);
        this.broadcast = maxSpectators == 0 ? null
                : new SpectatorBroadcast(width, height, audioFormat);
    }

    /** Starts accepting. Returns at once: the wait lives on its own thread. */
    public void start() {
        // No queue: a handshake either has a thread now or the peer is refused at once. With
        // spectators off at most one handshake is ever in flight (the player's place is taken
        // before it), so this changes nothing there.
        handshakeExecutor = new java.util.concurrent.ThreadPoolExecutor(
                0, HANDSHAKE_THREADS, 30L, TimeUnit.SECONDS,
                new java.util.concurrent.SynchronousQueue<>(),
                r -> {
                    Thread t = new Thread(r, "amija-remoteplay-handshake");
                    t.setDaemon(true);
                    return t;
                });
        rejectExecutor = new java.util.concurrent.ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(REJECT_QUEUE),
                r -> {
                    Thread t = new Thread(r, "amija-remoteplay-reject");
                    t.setDaemon(true);
                    return t;
                });
        acceptThread = new Thread(this::acceptLoop, "amija-remoteplay-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        mediaThread = new Thread(this::mediaLoop, "amija-remoteplay-host-media");
        mediaThread.setDaemon(true);
        mediaThread.start();
        if (spectatorCode == null) {
            RemotePlaySettings.say("listening on %d (TCP+UDP), join code %s", port, joinCode);
        } else {
            RemotePlaySettings.say("listening on %d (TCP+UDP), join code %s, spectator code"
                    + " %s (%d places)", port, joinCode, spectatorCode, maxSpectators);
        }
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

    /** The code that admits a spectator, or {@code null} if spectators are off. */
    public String spectatorCode() {
        return spectatorCode;
    }

    public int maxSpectators() {
        return maxSpectators;
    }

    /** Spectators connected now. */
    public int spectatorCount() {
        return broadcast == null ? 0 : broadcast.count();
    }

    /** Frames sent to the spectators, all of them counted once. */
    public long spectatorFramesSent() {
        return broadcast == null ? 0 : broadcast.framesSent();
    }

    /** Keyframes in the spectators' stream. */
    public long spectatorKeyframesSent() {
        return broadcast == null ? 0 : broadcast.keyframesSent();
    }

    /** One spectator frame every this many: above 1 while the player's link struggles. */
    public int spectatorFrameDivisor() {
        return broadcast == null ? 1 : broadcast.frameDivisor();
    }

    /**
     * Hands over a frame just completed. <b>Called by the emulation thread.</b>
     *
     * <p>If there is no guest it does nothing. If the encoding thread has not yet taken the
     * previous frame, this one is lost, on purpose.
     */
    public void submitFrame(int[] pixels, FrameInfo info) {
        SpectatorBroadcast b = broadcast;
        if (b != null) {
            b.submitFrame(pixels, info);
        }
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

    /** Frames delivered by the emulation (skipped ones do not count). */
    public long framesSubmitted() {
        return framesSubmitted;
    }

    /** Frames skipped because encoding was still busy: the "never queue" rule. */
    public long framesDroppedBusy() {
        return framesDroppedBusy;
    }

    /** Statistics of the video sent, or {@code null} if there is no session. */
    public VideoSender videoSender() {
        Session s = session;
        return s == null ? null : s.videoSender;
    }

    /** Statistics of the audio sent, or {@code null} if there is no session or it is off. */
    public AudioSender audioSender() {
        Session s = session;
        return s == null ? null : s.audioSender;
    }

    /**
     * Hands over an audio chunk already converted and filtered.
     *
     * <p><b>Called by the audio thread</b>, not by the emulation thread: it is already off
     * the hot path (section 4.2). Unlike the video there is no encoding thread in between:
     * compressing 1024 samples to ADPCM takes microseconds, and crossing another thread
     * boundary would cost more than the work.
     */
    public void submitAudio(byte[] pcm, int frames) {
        SpectatorBroadcast b = broadcast;
        if (b != null) {
            b.submitAudio(pcm, frames);
        }
        Session s = session;
        if (s == null || !s.alive || s.audioSender == null) {
            return;
        }
        try {
            s.audioSender.sendBlock(pcm, frames,
                    (p, n) -> s.media.send(RemotePlayProtocol.TYPE_AUDIO, p, 0, n));
        } catch (IOException e) {
            s.fail("audio sending interrupted: " + e.getMessage());
        }
    }

    /**
     * Installs whatever carries the guest's controls into the machine.
     *
     * <p>{@code null} disconnects them: the guest keeps seeing the video but touches nothing
     * any more. Needed when the machine is rebuilt (cold restart) and the previous game port
     * no longer exists.
     */
    public void setInputTarget(GuestInputTarget target) {
        this.inputTarget = target;
    }

    /**
     * Applies to the machine the last state received from the guest.
     *
     * <p><b>Called by the emulation thread, once per frame</b>, at the frame boundary: the
     * fixed, reproducible quantisation point of section 4.3. The network thread only
     * deposits the state; here it is copied under the lock (44 bytes, tens of nanoseconds)
     * and applied <b>outside</b> it, because that involves calls to the keyboard and the game
     * port that have nothing to do with the network.
     *
     * <p>If nothing new has arrived it does nothing: reapplying the same state would be
     * harmless for the keys (the difference would be empty) but not for the mouse, where it
     * would cost a useless call at every frame.
     */
    public void applyPendingInput() {
        GuestInputTarget target = inputTarget;
        if (target == null) {
            return;
        }
        if (releaseInputPending) {
            // The guest has left. The release must happen HERE and not on the thread that
            // closed the session, because it touches keyboard and game port: the same reason
            // input is applied here and not from the network thread.
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

    /** The last controller state received from the guest, for diagnostics. */
    public FeedbackPacket lastInput() {
        Session s = session;
        return s == null ? null : s.lastApplied;
    }

    /**
     * Tells the guest the host is paused (F12 open, section 9).
     *
     * <p>The state is <b>remembered</b>, and that matters: the session is switched on from
     * the popup, so the normal case is a guest connecting <i>while the popup is still
     * open</i> (the host reading the code to the other player on the phone). At that moment
     * the machine is stopped and no frame comes out: without being told, the guest would
     * consider the connection dropped after ten seconds and disconnect. So whoever connects
     * to a stopped host gets PAUSED at once.
     */
    public void notifyPaused(boolean paused) {
        this.paused = paused;
        if (broadcast != null) {
            broadcast.notifyPaused(paused);
        }
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
            s.fail("sending PAUSED/RESUMED failed: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        running = false;
        Session s = session;
        if (s != null) {
            s.stop(RemotePlayProtocol.REJECT_SHUTDOWN);
        }
        if (broadcast != null) {
            broadcast.close(RemotePlayProtocol.REJECT_SHUTDOWN);
        }
        try {
            controlSocket.close();
        } catch (IOException ignored) {
            // End of session: nothing to recover from a failed close.
        }
        mediaSocket.close();
        synchronized (frameLock) {
            frameLock.notifyAll();
        }
        if (acceptThread != null) {
            join(acceptThread);
        }
        join(mediaThread);
        // After the accepting thread, which is what hands it work: so no handshake just
        // queued is left on an executor already shut down.
        if (handshakeExecutor != null) {
            handshakeExecutor.shutdownNow();
            try {
                handshakeExecutor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (rejectExecutor != null) {
            rejectExecutor.shutdownNow();
        }
        // The session threads before the impairment, and the order matters: the encoding
        // thread could be inside a send, and closing the link under it would turn an orderly
        // close into an exception on a thread nobody watches. Last opened, first closed.
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
                    RemotePlaySettings.say("accept interrupted: %s", e.getMessage());
                }
                return;
            }
            ControlChannel channel = null;
            boolean slotTaken = false;
            try {
                channel = new ControlChannel(socket);
                if (lanOnly && !isLocalNetwork(socket.getInetAddress())) {
                    // Section 8.5: until the user switches on Internet exposure, nobody gets
                    // in from outside, and nobody gets in BEFORE authentication, which is the
                    // point: whoever is not on the home network must not even be able to try
                    // the code.
                    Handshake.reject(channel, RemotePlayProtocol.REJECT_LAN_ONLY);
                    closeAside(channel);
                    RemotePlaySettings.say("refused %s: local network only",
                            socket.getRemoteSocketAddress());
                    continue;
                }
                long wait = throttle.retryAfterMillis(socket.getInetAddress());
                if (wait > 0) {
                    // Before the place and before the derivation: whoever has just got the
                    // code wrong several times must be able neither to take the session nor
                    // to make the host spend the ~60 ms of PBKDF2 the attempt costs.
                    Handshake.reject(channel, RemotePlayProtocol.REJECT_AUTH);
                    closeAside(channel);
                    RemotePlaySettings.say("refused %s: too many wrong codes, retry in %d ms",
                            socket.getRemoteSocketAddress(), wait);
                    continue;
                }
                boolean reservePlayer = maxSpectators == 0;
                if (reservePlayer ? !guestSlot.compareAndSet(false, true)
                        : guestSlot.get() && spectatorSlots.get() >= maxSpectators) {
                    // Spectators off: the player's place is taken right here, as it always was.
                    // Spectators on: the role comes with the HELLO, but when every place is
                    // taken there is no point in waiting for it.
                    Handshake.reject(channel, RemotePlayProtocol.REJECT_BUSY);
                    closeAside(channel);
                    RemotePlaySettings.say("refused %s: a guest is already connected",
                            socket.getRemoteSocketAddress());
                    continue;
                }
                slotTaken = reservePlayer;
                // From here on handshake() takes care of returning whatever place is taken.
                final ControlChannel accepted = channel;
                handshakeExecutor.execute(() -> handshake(accepted, reservePlayer));
                slotTaken = false;   // delivered: the slot is now theirs
            } catch (java.util.concurrent.RejectedExecutionException e) {
                // Every handshake thread is busy: refused at once, with the reason.
                RemotePlaySettings.say("refused %s: too many connections in progress",
                        socket.getRemoteSocketAddress());
                if (slotTaken) {
                    guestSlot.set(false);
                    slotTaken = false;
                }
                Handshake.reject(channel, RemotePlayProtocol.REJECT_BUSY);
                closeAside(channel);
            } catch (IOException e) {
                RemotePlaySettings.say("connection from %s not accepted: %s",
                        socket.getRemoteSocketAddress(), e.getMessage());
                // closeGracefully and not close: if the failure was a REJECT, that message is
                // still queued and an abrupt close would wipe it out.
                if (channel != null) {
                    channel.closeGracefully();
                } else {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                        // idem
                    }
                }
            } finally {
                // The place taken and never handed over must be returned here, or it would
                // stay taken by nobody. It happens if execute() is refused because the
                // executor is closing: rare, but a lost place is never recovered.
                if (slotTaken) {
                    guestSlot.set(false);
                }
            }
        }
    }

    /**
     * Closes gracefully elsewhere, or abruptly if there is no room.
     *
     * <p>The accepting thread must <b>never</b> wait for a peer: it is the only one that can
     * welcome the next, and whoever has just been refused has no right to hold it.
     */
    private void closeAside(ControlChannel channel) {
        ExecutorService pool = rejectExecutor;
        if (pool != null) {
            try {
                pool.execute(channel::closeGracefully);
                return;
            } catch (java.util.concurrent.RejectedExecutionException e) {
                // Queue full or executor shutting down: close here, at once.
            }
        }
        channel.close();
    }

    /**
     * The handshake of a connection already accepted, <b>on its own thread</b>.
     *
     * <p>Inside {@link #acceptLoop()} it would be costly: every read of
     * {@link Handshake#accept} waits up to {@link Handshake#HANDSHAKE_TIMEOUT_MS}, and there
     * are two, plus the PBKDF2 derivation. A peer that opened the socket and then stayed
     * silent would hold the <b>listening</b> for some twenty seconds: in that interval
     * nobody else could connect, and not even the two quick refusals ("local network only"
     * and "there is already a guest") would be evaluated, because they come after the
     * accept. The accepting thread does only those two checks and goes straight back to
     * listening.
     *
     * <p>If the handshake does not complete, the guest's place must be returned: without it a
     * wrong code would close the listening to everyone forever.
     */
    private void handshake(ControlChannel channel, boolean playerReserved) {
        boolean established = false;
        // Which place this handshake holds: taken by the accepting thread (spectators off) or
        // by the admitter once the HELLO says the role. Returned below if nothing is established.
        int[] held = { playerReserved ? RemotePlayProtocol.ROLE_PLAYER : -1 };
        // The reason of the refusal, if any. The REJECT is sent by the finally, AFTER the
        // place has been returned: see Handshake.RejectingException. Sent from inside
        // Handshake.accept, the guest would receive it while the place was still taken, and
        // whoever retried at once would be told "there is already a guest".
        int rejectReason = 0;
        java.net.InetAddress peer = channel.socket().getInetAddress();
        // Whichever of the handshake and its deadline arrives first: only one of the two
        // CLOSES the channel. Otherwise a deadline already fired and stalled an instant
        // before close() would close the channel of a session just established
        // (microseconds, but a good session thrown away).
        AtomicBoolean closing = new AtomicBoolean();
        Thread deadline = startHandshakeDeadline(channel, closing);
        try {
            Handshake.Session negotiated = Handshake.accept(channel, role -> admit(role, held));
            if (negotiated.isSpectator()) {
                startSpectator(channel, negotiated);
            } else {
                startSession(channel, negotiated);
            }
            established = true;
            throttle.recordSuccess(peer);
        } catch (Handshake.RejectingException e) {
            rejectReason = e.reason();
            RemotePlaySettings.say("handshake refused with %s: %s",
                    channel.socket().getRemoteSocketAddress(), e.getMessage());
        } catch (IOException e) {
            RemotePlaySettings.say("handshake failed with %s: %s",
                    channel.socket().getRemoteSocketAddress(), e.getMessage());
        } catch (RuntimeException e) {
            RemotePlaySettings.say("handshake interrupted with %s: %s",
                    channel.socket().getRemoteSocketAddress(), e);
        } finally {
            if (closing.compareAndSet(false, true)) {
                deadline.interrupt();   // must not sleep to the end for nothing
            }
            // The place does NOT follow that race: it is always and only returned by whoever
            // failed to hand it to a session. Having the deadline return it would be wrong in
            // the (rare but possible) case where it fires right after a successful handshake:
            // the place already belongs to the session, and freeing it would give it to a
            // second guest while the first is still in. If the deadline closed the channel of
            // a live session, that session dies by itself and stop(), its owner, returns the
            // place.
            if (!established) {
                // A handshake that does not complete counts as a wrong attempt. A wrong code
                // is not told apart from a dropped socket, on purpose: only the other side
                // knows the difference, and whoever is guessing could fabricate it at will by
                // disconnecting at the right moment.
                throttle.recordFailure(peer);
                release(held[0]);
                // The place BEFORE saying anything to the guest, and the order is the
                // guarantee: when the REJECT reaches the socket the place is already free, so
                // whoever retries (after a wrong code, the normal case) cannot be told "there
                // is already a guest" instead of "wrong code". Freeing it only before the
                // close is not enough if the REJECT leaves from inside Handshake.accept, i.e.
                // before even getting here: the window would remain, as wide as
                // recordFailure(), small but enough to be seen.
                if (rejectReason != 0) {
                    Handshake.reject(channel, rejectReason);
                }
                channel.closeGracefully();   // idempotent: the deadline may have closed it already
            }
        }
    }

    /**
     * Closes the socket if the handshake does not complete within
     * {@value #HANDSHAKE_BUDGET_MS} ms.
     *
     * <p>The timeout of {@link Handshake} applies to <b>each read</b>, not to the whole: a
     * peer silent at every step adds up the timeouts, meanwhile holding the place (there is
     * only one) <i>without having authenticated</i>. The overall limit is what stops a mute
     * connection from denying the session to whoever has the code.
     *
     * <p>Closing the socket is the right way to wake the blocked thread: a
     * {@link Thread#interrupt()} does not touch a socket read, while closing makes it return
     * at once with an exception, which is exactly the failure path already provided, so the
     * place is returned by {@link #handshake} as in any other failure, and this thread must
     * not touch it.
     *
     * <p>Generous on purpose: {@value #HANDSHAKE_BUDGET_MS} ms is an eternity for a handshake
     * that costs ~60 ms of PBKDF2 on a real network, and still tight enough not to leave the
     * session hostage to anyone.
     */
    private Thread startHandshakeDeadline(ControlChannel channel, AtomicBoolean closing) {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(HANDSHAKE_BUDGET_MS);
            } catch (InterruptedException e) {
                return;   // handshake finished in time: nothing to do
            }
            if (!closing.compareAndSet(false, true)) {
                return;   // the handshake got to the end first
            }
            RemotePlaySettings.say("handshake with %s over %d ms: closing",
                    channel.socket().getRemoteSocketAddress(), HANDSHAKE_BUDGET_MS);
            // The channel only. The place is returned by handshake(), the only one that knows
            // whether a session took charge of it: see the comment there.
            channel.close();
        }, "amija-remoteplay-handshake-deadline");
        t.setDaemon(true);
        t.start();
        return t;
    }

    /**
     * True if this address is on a network this machine is attached to.
     *
     * <p>"It is a private address" is not enough: between two different private networks (a
     * VPN, another building behind a tunnel) there is exactly the distance this option says
     * it does not want to cross. So the network bits are compared with the real mask of each
     * interface, the only definition of "my network" the system knows. Loopback always
     * passes: it is this same machine.
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
            // Without the list of interfaces locality cannot be proved, and in doubt the peer
            // is refused: it is a security option, not an optimisation.
            RemotePlaySettings.log("interfaces cannot be listed: %s", e.getMessage());
        }
        return false;
    }

    /** Compares the first {@code prefixLength} bits of the two addresses. */
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

    /**
     * Takes the place for the role of the HELLO, or refuses.
     *
     * <p>The place is recorded in {@code held} so {@link #handshake} can give it back if the
     * handshake then fails.
     */
    private Handshake.Admission admit(int role, int[] held) throws Handshake.RejectingException {
        int gen = (int) sessionCounter.incrementAndGet();
        if (role == RemotePlayProtocol.ROLE_SPECTATOR) {
            if (maxSpectators == 0) {
                throw new Handshake.RejectingException(RemotePlayProtocol.REJECT_NO_SPECTATORS,
                        "spectators not allowed");
            }
            while (true) {
                int taken = spectatorSlots.get();
                if (taken >= maxSpectators) {
                    throw new Handshake.RejectingException(
                            RemotePlayProtocol.REJECT_SPECTATORS_FULL,
                            "no spectator places left (" + maxSpectators + ")");
                }
                if (spectatorSlots.compareAndSet(taken, taken + 1)) {
                    break;
                }
            }
            held[0] = RemotePlayProtocol.ROLE_SPECTATOR;
            // No game port and no keyboard: a spectator gets nothing to drive.
            return new Handshake.Admission(spectatorCode, new Handshake.HostOffer(
                    gen, gen, port, width, height, 44100, 2, audioFormat, 0, false));
        }
        if (held[0] != RemotePlayProtocol.ROLE_PLAYER) {
            if (!guestSlot.compareAndSet(false, true)) {
                throw new Handshake.RejectingException(RemotePlayProtocol.REJECT_BUSY,
                        "a guest is already connected");
            }
            held[0] = RemotePlayProtocol.ROLE_PLAYER;
        }
        return new Handshake.Admission(joinCode, new Handshake.HostOffer(
                gen, gen, port, width, height, 44100, 2, audioFormat,
                guestGamePort, guestKeyboard));
    }

    /** Gives back the place a failed handshake held. */
    private void release(int role) {
        if (role == RemotePlayProtocol.ROLE_PLAYER) {
            guestSlot.set(false);
        } else if (role == RemotePlayProtocol.ROLE_SPECTATOR) {
            spectatorSlots.decrementAndGet();
        }
    }

    private void startSpectator(ControlChannel channel, Handshake.Session negotiated)
            throws IOException {
        AtomicBoolean released = new AtomicBoolean();
        broadcast.add(channel, negotiated, mediaSocket, impairment, () -> {
            if (released.compareAndSet(false, true)) {
                spectatorSlots.decrementAndGet();
            }
        });
        RemotePlaySettings.say("spectator connected from %s (AmiJa %s), %d of %d",
                channel.socket().getRemoteSocketAddress(), negotiated.peerAppVersion(),
                broadcast.count(), maxSpectators);
    }

    private void startSession(ControlChannel channel, Handshake.Session negotiated)
            throws IOException {
        int gen = negotiated.sessionGen();
        Session s = new Session(channel, negotiated);
        session = s;
        s.start();
        if (paused) {
            // Host stopped (popup open): without this, the guest waits for a video that does
            // not come and gives up after ten seconds.
            sendPauseState(s, true);
        }
        RemotePlaySettings.say("guest connected from %s (AmiJa %s), generation %d",
                channel.socket().getRemoteSocketAddress(), negotiated.peerAppVersion(), gen);
    }

    // =====================================================================
    // The media socket
    // =====================================================================

    /**
     * Reads the UDP socket and hands each datagram to the endpoint of its sender.
     *
     * <p>An endpoint whose peer is already known is found by address, which costs a comparison
     * and no decryption. A datagram from an unknown address is offered to the endpoints still
     * waiting for their first datagram: the one whose key opens it adopts the sender
     * ({@link MediaLink#accept}).
     */
    private void mediaLoop() {
        byte[] datagram = new byte[RemotePlayProtocol.MAX_DATAGRAM];
        byte[] plain = new byte[RemotePlayProtocol.MAX_PLAINTEXT];
        java.net.DatagramPacket packet =
                new java.net.DatagramPacket(datagram, RemotePlayProtocol.MAX_DATAGRAM);
        List<MediaEndpoint> endpoints = new ArrayList<>();
        long nextTimeoutCheck = System.nanoTime();
        while (running) {
            boolean got;
            try {
                packet.setData(datagram, 0, RemotePlayProtocol.MAX_DATAGRAM);
                mediaSocket.receive(packet);
                got = true;
            } catch (SocketTimeoutException e) {
                got = false;
            } catch (IOException e) {
                if (running) {
                    RemotePlaySettings.say("media reception interrupted: %s", e.getMessage());
                }
                return;
            }
            endpoints.clear();
            Session s = session;
            if (s != null && s.alive) {
                endpoints.add(s);
            }
            if (broadcast != null) {
                endpoints.addAll(broadcast.peers());
            }
            if (got) {
                dispatch(endpoints, datagram, packet.getLength(), packet.getSocketAddress(),
                        plain);
            }
            long now = System.nanoTime();
            if (now - nextTimeoutCheck >= 0) {
                nextTimeoutCheck = now + ACCEPT_POLL_MS * 1_000_000L;
                for (MediaEndpoint e : endpoints) {
                    e.checkTimeout(now);
                }
                checkPlayerHealth(now);
            }
        }
    }

    private static void dispatch(List<MediaEndpoint> endpoints, byte[] datagram, int length,
                                 java.net.SocketAddress from, byte[] plain) {
        for (MediaEndpoint e : endpoints) {
            if (from.equals(e.media().peer())) {
                MediaLink.Received r = e.media().accept(datagram, length, from, plain);
                if (r != null) {
                    e.onDatagram(r, plain);
                }
                return;
            }
        }
        for (MediaEndpoint e : endpoints) {
            if (!e.media().hasPeer()) {
                MediaLink.Received r = e.media().accept(datagram, length, from, plain);
                if (r != null) {
                    e.onDatagram(r, plain);
                    return;
                }
            }
        }
        RemotePlaySettings.log("datagram from %s without a recipient", from);
    }

    /**
     * Gives the spectators fewer frames while the player's link is in trouble, and gives them
     * back once it has been fine for a while.
     *
     * <p>The sign of trouble is a keyframe in the player's stream after the start: it means the
     * player's confirmations fell out of the ring, i.e. frames were lost or arrived too late.
     * The uplink is shared, and the spectators are the ones who can wait.
     */
    private void checkPlayerHealth(long now) {
        SpectatorBroadcast b = broadcast;
        if (b == null || now - nextHealthCheckNanos < 0) {
            return;
        }
        nextHealthCheckNanos = now + PLAYER_HEALTH_PERIOD_NANOS;
        Session s = session;
        if (s == null || !s.alive || b.count() == 0) {
            playerKeyframesSeen = -1;
            playerHealthyPeriods = 0;
            b.setFrameDivisor(1);
            return;
        }
        long keyframes = s.videoSender.keyframesSent();
        boolean struggling = playerKeyframesSeen >= 0 && keyframes > playerKeyframesSeen
                && now - s.startNanos > 2 * PLAYER_HEALTH_PERIOD_NANOS;
        playerKeyframesSeen = keyframes;
        if (struggling) {
            playerHealthyPeriods = 0;
            int divisor = Math.min(MAX_SPECTATOR_DIVISOR, b.frameDivisor() * 2);
            if (divisor != b.frameDivisor()) {
                RemotePlaySettings.log("guest connection struggling: spectators get"
                        + " one frame every %d", divisor);
            }
            b.setFrameDivisor(divisor);
        } else if (b.frameDivisor() > 1 && ++playerHealthyPeriods >= PLAYER_HEALTHY_PERIODS) {
            playerHealthyPeriods = 0;
            b.setFrameDivisor(b.frameDivisor() / 2);
        }
    }

    // =====================================================================
    // One session
    // =====================================================================

    private final class Session implements MediaEndpoint {

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
        /**
         * Whether {@link #stop} has already been through here. Checking {@code alive} is not
         * enough: {@code encodeLoop}, the media thread and {@code controlLoop} can notice
         * the end at the same instant and all pass it, and since closing also returns the
         * guest's place a second pass would free it a second time, giving it away while it
         * already belongs to someone else.
         */
        private final AtomicBoolean stopping = new AtomicBoolean();
        private volatile long lastFeedbackNanos = System.nanoTime();
        private final long startNanos = System.nanoTime();
        private long lastFeedbackSeq = -1;
        private Thread encodeThread;
        private Thread controlThread;

        Session(ControlChannel control, Handshake.Session negotiated) throws IOException {
            this.control = control;
            this.sessionGen = negotiated.sessionGen();
            this.media = new MediaLink(mediaSocket,
                    negotiated.cipher(Handshake.DIRECTION_HOST), null, impairment);
            this.videoSender = new VideoSender(width, height);
            this.audioSender = audioFormat == 0 ? null : new AudioSender(audioFormat);
            this.lastApplied.clear();
        }

        void start() {
            synchronized (frameLock) {
                framePending = false;      // nothing left over from the previous connection
            }
            encodeThread = thread(this::encodeLoop, "amija-remoteplay-encode");
            controlThread = thread(this::controlLoop, "amija-remoteplay-control");
        }

        private Thread thread(Runnable body, String name) {
            Thread t = new Thread(body, name);
            t.setDaemon(true);
            t.start();
            return t;
        }

        /** Takes the pending frame and sends it. It is the only thread that encodes. */
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
                fail("video sending interrupted: " + e.getMessage());
            } finally {
                videoSender.close();
            }
        }

        @Override
        public MediaLink media() {
            return media;
        }

        /**
         * The guest's feedback: the video ACK and the inputs. On the media thread, which owns
         * {@code plain} for the duration of the call.
         */
        @Override
        public void onDatagram(MediaLink.Received received, byte[] plain) {
            if (received.type() != RemotePlayProtocol.TYPE_FEEDBACK) {
                // The host -> guest direction has nothing to do here: a peer error.
                RemotePlaySettings.log("datagram of type %d ignored", received.type());
                return;
            }
            try {
                incoming.readFrom(plain, received.length());
            } catch (ProtocolException e) {
                RemotePlaySettings.log("feedback dropped: %s", e.getMessage());
                return;
            }
            lastFeedbackNanos = System.nanoTime();
            if (incoming.sessionGen != sessionGen) {
                return;      // left over from the previous generation, in flight from the past
            }
            if (lastFeedbackSeq >= 0
                    && Integer.compareUnsigned(incoming.seq, (int) lastFeedbackSeq) <= 0) {
                return;      // old or repeated: the good state is the one already applied
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

        @Override
        public void checkTimeout(long nowNanos) {
            if (alive && nowNanos - lastFeedbackNanos > SESSION_TIMEOUT_MS * 1_000_000L) {
                fail("the guest has not answered for " + SESSION_TIMEOUT_MS + " ms");
            }
        }

        /** The control channel after the handshake: rare events, and the end of the session. */
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
                            RemotePlaySettings.say("the guest disconnected");
                            stop(-1);
                            return;
                        }
                        default -> RemotePlaySettings.log(
                                "unexpected control message: %d", m.type());
                    }
                }
            } catch (EOFException e) {
                if (alive) {
                    RemotePlaySettings.say("the guest closed the connection");
                    stop(-1);
                }
            } catch (IOException e) {
                if (alive && running) {
                    fail("control channel interrupted: " + e.getMessage());
                }
            }
        }

        /** Waits until the session threads have really finished. Never from inside. */
        void awaitStop() {
            join(encodeThread);
            join(controlThread);
        }

        void fail(String reason) {
            if (alive) {
                RemotePlaySettings.say("session closed: %s", reason);
            }
            stop(-1);
        }

        void stop(int rejectReason) {
            if (!stopping.compareAndSet(false, true)) {
                return;
            }
            alive = false;
            // Whatever the guest held pressed must be released, or it stays pressed forever.
            releaseInputPending = true;
            if (rejectReason >= 0) {
                try {
                    control.send(RemotePlayProtocol.CTRL_BYE,
                            new byte[] { (byte) rejectReason }, 1);
                } catch (IOException ignored) {
                    // A goodbye: if it does not go out, the session ends anyway.
                }
            }
            control.close();
            synchronized (frameLock) {
                frameLock.notifyAll();
            }
            // First clear the field, then free the place: in the opposite order a new guest
            // could take the place and install itself in `session` between the comparison and
            // the clearing, and would be wiped out as soon as it arrived.
            if (session == this) {
                session = null;
            }
            guestSlot.set(false);
        }
    }
}
