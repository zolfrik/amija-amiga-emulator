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
import io.github.zolfrik.amija.net.codec.FrameEncoder;

import java.io.EOFException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The spectators' video and audio: <b>one</b> stream, encoded once, sent to every spectator
 * ({@code docs/remote-play-plan.md} section 16).
 *
 * <h2>The reference is the newest frame every spectator holds</h2>
 * The player's stream compresses against the last frame the player confirmed, and a loss only
 * widens the next delta. The same holds here for many receivers at once: each spectator reports
 * which frames its ring holds ({@link SpectatorFeedback}), and the frame is compressed against
 * the newest one present in all of them. A spectator that holds none of the candidates is left
 * out of the choice and gets a keyframe of its own. Measured on real frames with 1% of the
 * fragments lost: 95-99% of the frames shown, almost no keyframes.
 *
 * <h2>The player comes first</h2>
 * <ul>
 *   <li>its own thread, at a lower priority than the player's encoding;</li>
 *   <li>never more than one frame waiting: if the encoder is busy the frame is skipped;</li>
 *   <li>{@link #setFrameDivisor} thins the spectators' frames when the player's link is in
 *       trouble: the uplink is one, and the spectators give way first.</li>
 * </ul>
 *
 * <h2>Spectators touch nothing</h2>
 * A spectator's datagrams are read only as {@link SpectatorFeedback}; anything else is dropped.
 * None of them reaches the input of the machine.
 */
final class SpectatorBroadcast implements AutoCloseable {

    /** Minimum distance between two keyframes, in frames (200 ms at 50 fps). */
    static final int KEYFRAME_MIN_GAP = 10;

    private static final int POLL_MS = 200;
    private static final int SESSION_TIMEOUT_MS = 10_000;

    private final int width;
    private final int height;
    private final int audioFormat;
    private final FrameEncoder encoder;
    private final ReferenceRing ring;
    private final byte[] sharedBlob = new byte[VideoSender.blobCapacity()];
    private final byte[] keyBlob = new byte[VideoSender.blobCapacity()];
    private final byte[] fragment =
            new byte[RemotePlayProtocol.FRAGMENT_HEADER + RemotePlayProtocol.FRAGMENT_DATA];
    /** {@code null} if the host sends no audio. Used only by the audio thread. */
    private final AudioSender audioSender;
    private final List<Peer> peers = new CopyOnWriteArrayList<>();

    // --- frame delivery from the emulation thread ---------------------------------------
    private final Object frameLock = new Object();
    private int[] pendingPixels;
    private int[] encodePixels;
    private final FrameInfo pendingInfo = new FrameInfo();
    private final FrameInfo encodeInfo = new FrameInfo();
    private boolean framePending;
    private long submitted;
    private volatile int frameDivisor = 1;
    private volatile boolean running = true;
    private volatile boolean paused;
    private final Thread encodeThread;

    // --- encoding thread only -------------------------------------------------------------
    private long nextFrameId;
    private long lastKeyframeId = -KEYFRAME_MIN_GAP;

    // --- statistics -----------------------------------------------------------------------
    private volatile long framesSent;
    private volatile long keyframesSent;
    private volatile long bytesSent;
    private volatile long framesSkippedBusy;
    private volatile long framesSkippedForPlayer;

    /**
     * @param audioFormat {@link AudioBlockHeader#FORMAT_PCM16}, {@link AudioBlockHeader#FORMAT_ADPCM}
     *                    or 0 for no audio
     */
    SpectatorBroadcast(int width, int height, int audioFormat) {
        this.width = width;
        this.height = height;
        this.audioFormat = audioFormat;
        this.encoder = new FrameEncoder(width, height);
        this.ring = new ReferenceRing(width * height, ReferenceRing.SPECTATOR_SLOTS);
        this.pendingPixels = new int[width * height];
        this.encodePixels = new int[width * height];
        this.audioSender = audioFormat == 0 ? null : new AudioSender(audioFormat);
        this.encodeThread = new Thread(this::encodeLoop, "amija-remoteplay-spectators");
        this.encodeThread.setDaemon(true);
        // Below the player's encoder, which runs at the default priority.
        this.encodeThread.setPriority(Thread.NORM_PRIORITY - 1);
        this.encodeThread.start();
    }

    // =====================================================================
    // Peers
    // =====================================================================

    /**
     * Adds a spectator whose handshake has just completed.
     *
     * @param onRelease run once when the spectator leaves: gives its place back
     */
    Peer add(ControlChannel control, Handshake.Session negotiated,
             java.net.DatagramSocket socket, NetImpairment impairment, Runnable onRelease)
            throws IOException {
        Peer peer = new Peer(control, negotiated, socket, impairment, onRelease);
        peers.add(peer);
        peer.start();
        if (paused) {
            peer.sendPauseState(true);
        }
        return peer;
    }

    /** The spectators connected now, for the demultiplexer and the popup. */
    List<Peer> peers() {
        return peers;
    }

    int count() {
        return peers.size();
    }

    // =====================================================================
    // Video
    // =====================================================================

    /**
     * Hands over a frame. <b>Called by the emulation thread</b>: one copy, nothing else.
     */
    void submitFrame(int[] pixels, FrameInfo info) {
        if (peers.isEmpty()) {
            return;
        }
        synchronized (frameLock) {
            submitted++;
            if (submitted % frameDivisor != 0) {
                framesSkippedForPlayer++;
                return;
            }
            if (framePending) {
                framesSkippedBusy++;
                return;
            }
            System.arraycopy(pixels, 0, pendingPixels, 0, pendingPixels.length);
            pendingInfo.copyFrom(info);
            framePending = true;
            frameLock.notifyAll();
        }
    }

    /**
     * Sends the spectators one frame every {@code divisor}: 1 is all of them. Set by the host
     * from the health of the player's link.
     */
    void setFrameDivisor(int divisor) {
        this.frameDivisor = Math.max(1, divisor);
    }

    int frameDivisor() {
        return frameDivisor;
    }

    private void encodeLoop() {
        try {
            while (running) {
                synchronized (frameLock) {
                    while (!framePending && running) {
                        frameLock.wait(POLL_MS);
                    }
                    if (!running) {
                        return;
                    }
                    int[] tmp = encodePixels;
                    encodePixels = pendingPixels;
                    pendingPixels = tmp;
                    encodeInfo.copyFrom(pendingInfo);
                    framePending = false;
                }
                encodeAndSend(encodePixels, encodeInfo);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            encoder.close();
        }
    }

    private void encodeAndSend(int[] pixels, FrameInfo info) {
        List<Peer> targets = peers;
        if (targets.isEmpty()) {
            return;
        }
        long reference = chooseReference(targets);
        int[] referencePixels = reference >= 0 ? ring.get(reference) : null;
        long refFrameId = referencePixels != null ? reference : RemotePlayProtocol.NO_REFERENCE;

        int payloadLength = encoder.encode(pixels, referencePixels);
        int sharedLength = VideoSender.pack(sharedBlob, refFrameId, width, height, info,
                encoder.payload(), payloadLength);
        long frameId = nextFrameId++;
        nextFrameIdPublished = nextFrameId;
        if (sharedLength < 0) {
            return;   // does not fit the fragmentation: one frame less, never a corrupt one
        }
        boolean sharedIsKeyframe = refFrameId == RemotePlayProtocol.NO_REFERENCE;

        // Whoever cannot decode the shared frame gets a keyframe, encoded once for all of them
        // and no more often than KEYFRAME_MIN_GAP: until its report says otherwise, a spectator
        // keeps asking, and the gap keeps it from being served at every frame meanwhile.
        int keyLength = -1;
        if (sharedIsKeyframe) {
            lastKeyframeId = frameId;
        } else if (anyWantsKeyframe(targets) && frameId - lastKeyframeId >= KEYFRAME_MIN_GAP) {
            int keyPayload = encoder.encode(pixels, null);
            keyLength = VideoSender.pack(keyBlob, RemotePlayProtocol.NO_REFERENCE, width, height,
                    info, encoder.payload(), keyPayload);
            if (keyLength > 0) {
                lastKeyframeId = frameId;
            }
        }

        long bytes = 0;
        for (Peer peer : targets) {
            boolean key = keyLength > 0 && peer.wantsKeyframe;
            byte[] blob = key ? keyBlob : sharedBlob;
            int length = key ? keyLength : sharedLength;
            try {
                VideoSender.sendFragments(blob, length, frameId, fragment,
                        (p, n) -> peer.media.send(RemotePlayProtocol.TYPE_VIDEO, p, 0, n));
                bytes += length;
            } catch (IOException e) {
                peer.fail("video sending interrupted: " + e.getMessage());
            }
        }
        ring.store(frameId, pixels);
        framesSent++;
        bytesSent += bytes;
        if (sharedIsKeyframe || keyLength > 0) {
            keyframesSent++;
        }
    }

    /**
     * The newest frame of the ring held by the most spectators, among those not waiting for a
     * keyframe; ties go to the newest. {@code -1} if none holds anything: then the frame is a
     * keyframe for everybody.
     */
    private long chooseReference(List<Peer> targets) {
        int eligible = 0;
        for (Peer peer : targets) {
            if (!peer.wantsKeyframe && peer.held >= 0) {
                eligible++;
            }
        }
        if (eligible == 0) {
            return -1;
        }
        long best = -1;
        int bestCount = 0;
        long newest = nextFrameId - 1;
        for (long id = newest; id >= 0 && id > newest - ring.slots(); id--) {
            if (!ring.holds(id)) {
                continue;
            }
            int count = 0;
            for (Peer peer : targets) {
                if (!peer.wantsKeyframe && peer.holds(id)) {
                    count++;
                }
            }
            if (count > bestCount) {
                best = id;
                bestCount = count;
            }
            if (count == eligible) {
                break;
            }
        }
        return best;
    }

    private static boolean anyWantsKeyframe(List<Peer> targets) {
        for (Peer peer : targets) {
            if (peer.wantsKeyframe) {
                return true;
            }
        }
        return false;
    }

    // =====================================================================
    // Audio and pause
    // =====================================================================

    /**
     * Hands over an audio chunk. <b>Called by the audio thread</b>: encoded once, sent to all.
     */
    void submitAudio(byte[] pcm, int frames) {
        if (audioSender == null || peers.isEmpty()) {
            return;
        }
        try {
            audioSender.sendBlock(pcm, frames, (p, n) -> {
                for (Peer peer : peers) {
                    try {
                        peer.media.send(RemotePlayProtocol.TYPE_AUDIO, p, 0, n);
                    } catch (IOException e) {
                        peer.fail("audio sending interrupted: " + e.getMessage());
                    }
                }
            });
        } catch (IOException e) {
            // The sink above catches per spectator: nothing else can throw here.
            RemotePlaySettings.log("audio agli spettatori: %s", e.getMessage());
        }
    }

    /** Tells every spectator the host is paused, and remembers it for whoever comes next. */
    void notifyPaused(boolean paused) {
        this.paused = paused;
        for (Peer peer : peers) {
            peer.sendPauseState(paused);
        }
    }

    // =====================================================================

    long framesSent() {
        return framesSent;
    }

    long keyframesSent() {
        return keyframesSent;
    }

    long bytesSent() {
        return bytesSent;
    }

    long framesSkippedBusy() {
        return framesSkippedBusy;
    }

    long framesSkippedForPlayer() {
        return framesSkippedForPlayer;
    }

    int audioFormat() {
        return audioFormat;
    }

    /** Says goodbye to every spectator with {@code reason} and stops the encoder. */
    @Override
    public void close() {
        close(RemotePlayProtocol.REJECT_SHUTDOWN);
    }

    void close(int reason) {
        running = false;
        for (Peer peer : peers) {
            peer.stop(reason);
        }
        synchronized (frameLock) {
            frameLock.notifyAll();
        }
        join(encodeThread);
        for (Peer peer : peers) {
            join(peer.controlThread);
        }
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
    // One spectator
    // =====================================================================

    final class Peer implements MediaEndpoint {

        private final ControlChannel control;
        private final MediaLink media;
        private final int sessionGen;
        private final Runnable onRelease;
        private final SpectatorFeedback incoming = new SpectatorFeedback();
        private final AtomicBoolean stopping = new AtomicBoolean();

        /** {@code newest << 16 | mask} from the last report, {@code -1} before the first. */
        private volatile long held = -1;
        /** Until its first report a spectator holds nothing: it waits for a keyframe. */
        private volatile boolean wantsKeyframe = true;
        private volatile long lastFeedbackNanos = System.nanoTime();
        private long lastSeq = -1;
        private volatile boolean alive = true;
        private Thread controlThread;

        Peer(ControlChannel control, Handshake.Session negotiated,
             java.net.DatagramSocket socket, NetImpairment impairment, Runnable onRelease) {
            this.control = control;
            this.sessionGen = negotiated.sessionGen();
            this.onRelease = onRelease;
            this.media = new MediaLink(socket, negotiated.cipher(Handshake.DIRECTION_HOST),
                    null, impairment);
        }

        void start() {
            controlThread = new Thread(this::controlLoop, "amija-remoteplay-spectator-control");
            controlThread.setDaemon(true);
            controlThread.start();
        }

        @Override
        public MediaLink media() {
            return media;
        }

        boolean isAlive() {
            return alive;
        }

        java.net.SocketAddress remoteAddress() {
            return control.socket().getRemoteSocketAddress();
        }

        /** Whether the last report says this spectator holds frame {@code id}. */
        boolean holds(long id) {
            long state = held;
            if (state < 0) {
                return false;
            }
            long newest = state >>> 16;
            long back = newest - id;
            return back >= 0 && back < 16 && (state & (1L << back)) != 0;
        }

        /** Only {@link RemotePlayProtocol#TYPE_SPECTATOR} is read: a spectator sends no input. */
        @Override
        public void onDatagram(MediaLink.Received received, byte[] plain) {
            if (received.type() != RemotePlayProtocol.TYPE_SPECTATOR) {
                RemotePlaySettings.log("datagram of type %d from a spectator ignored",
                        received.type());
                return;
            }
            try {
                incoming.readFrom(plain, received.length());
            } catch (ProtocolException e) {
                RemotePlaySettings.log("spectator report dropped: %s", e.getMessage());
                return;
            }
            lastFeedbackNanos = System.nanoTime();
            if (incoming.sessionGen != sessionGen) {
                return;
            }
            if (lastSeq >= 0 && Integer.compareUnsigned(incoming.seq, (int) lastSeq) <= 0) {
                return;   // old or repeated
            }
            lastSeq = incoming.seq & 0xFFFFFFFFL;
            if (incoming.newestFrameId == RemotePlayProtocol.NO_REFERENCE
                    || incoming.newestFrameId >= nextFrameIdSnapshot()) {
                // Nothing held, or a frame never sent: either way nothing to compress against.
                held = -1;
            } else {
                held = (incoming.newestFrameId << 16) | (incoming.heldMask & 0xFFFF);
            }
            wantsKeyframe = incoming.wantsKeyframe() || held < 0;
        }

        @Override
        public void checkTimeout(long nowNanos) {
            if (alive && nowNanos - lastFeedbackNanos > SESSION_TIMEOUT_MS * 1_000_000L) {
                fail("the spectator has not answered for " + SESSION_TIMEOUT_MS + " ms");
            }
        }

        void sendPauseState(boolean paused) {
            try {
                control.send(paused ? RemotePlayProtocol.CTRL_PAUSED
                        : RemotePlayProtocol.CTRL_RESUMED);
            } catch (IOException e) {
                fail("sending PAUSED/RESUMED failed: " + e.getMessage());
            }
        }

        private void controlLoop() {
            try {
                control.setTimeout(SESSION_TIMEOUT_MS);
                while (alive) {
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
                            RemotePlaySettings.say("a spectator left (%s)",
                                    remoteAddress());
                            stop(-1);
                            return;
                        }
                        default -> RemotePlaySettings.log(
                                "unexpected control message from a spectator: %d", m.type());
                    }
                }
            } catch (EOFException e) {
                if (alive) {
                    RemotePlaySettings.say("a spectator closed the connection (%s)",
                            remoteAddress());
                    stop(-1);
                }
            } catch (IOException e) {
                if (alive) {
                    fail("control channel interrupted: " + e.getMessage());
                }
            }
        }

        void fail(String reason) {
            if (alive) {
                RemotePlaySettings.say("spectator %s disconnected: %s", remoteAddress(), reason);
            }
            stop(-1);
        }

        void stop(int rejectReason) {
            if (!stopping.compareAndSet(false, true)) {
                return;
            }
            alive = false;
            if (rejectReason >= 0) {
                try {
                    control.send(RemotePlayProtocol.CTRL_BYE,
                            new byte[] { (byte) rejectReason }, 1);
                } catch (IOException ignored) {
                    // A goodbye: if it does not go out, the spectator leaves anyway.
                }
            }
            control.close();
            peers.remove(this);
            onRelease.run();
        }
    }

    /** Read by the network thread: a report about a frame beyond this one is not believed. */
    private long nextFrameIdSnapshot() {
        return nextFrameIdPublished;
    }

    private volatile long nextFrameIdPublished;
}
