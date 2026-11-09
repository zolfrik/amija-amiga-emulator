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

package io.github.zolfrik.amija.net.mister;

import io.github.zolfrik.amija.common.ExternalPacer;
import io.github.zolfrik.amija.display.FrameInfo;
import io.github.zolfrik.amija.net.mister.GroovyClient.AckAt;
import io.github.zolfrik.amija.net.mister.GroovyProtocol.Ack;
import io.github.zolfrik.amija.net.mister.GroovyProtocol.AudioRate;
import io.github.zolfrik.amija.net.mister.GroovyProtocol.RgbMode;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Sends every emulated frame to a MiSTer running the Groovy core.
 *
 * <p>The output stage calls {@link #submitFrame} once per frame: it copies the visible window
 * of the raw raster into a mailbox and returns. A dedicated thread converts the pixels,
 * compresses them and sends them; if it falls behind, the older frame in the mailbox is
 * replaced by the newer one. The emulation never waits for the network.
 *
 * <p>The session survives the MiSTer being unreachable: the thread retries every couple of
 * seconds and the emulation goes on regardless. The video mode follows the emulated machine:
 * a PAL/NTSC switch or a change of interlace sends a new modeline (interlace only after it
 * has held for a few frames, so a single odd frame does not switch the monitor).
 *
 * <h2>The MiSTer keeps the time</h2>
 * The number of a blit is an appointment: the MiSTer shows blit {@code N} in its frame
 * {@code N}. A thread of its own reads the ACKs and stamps each with its arrival, which
 * places the MiSTer's beam to a fraction of a line. From it every blit gets a window:
 * <ul>
 * <li>it is sent when frame {@code N - 1} begins ({@link #GATE_MS} after its start), never
 *     earlier: the MiSTer streams a blit into a video FIFO smaller than a frame, and one that
 *     arrives more than a frame early fills it and stops the decoder;</li>
 * <li>it must have arrived before the vertical blank that ends frame {@code N - 1}, less a
 *     safety time: a blit that reaches the MiSTer in that blank, with its video FIFO empty,
 *     can stop its video pipeline for good.</li>
 * </ul>
 * A frame that is ready too late for its window takes the first number whose window is still
 * open, and that frame of the MiSTer shows the previous picture again. As an
 * {@link ExternalPacer} this class runs the emulation slightly faster or slower so that each
 * frame is ready {@link #READY_LEAD_MS} before its window opens: the emulation then has
 * almost a whole frame of slack against a slow frame of the host. Interlaced, the parity of
 * {@code N} also decides which lines of the tube a field lands on, so it is chosen from the
 * field the MiSTer reports.
 *
 * <h2>Audio</h2>
 * With an audio source set, each blit is preceded by a block of samples. The MiSTer plays
 * them with its own clock, which only its 48 kHz rate divides exactly, so the chain runs at
 * {@link #AUDIO_RATE}. How many samples go with a blit is decided by
 * {@link MisterAudioPacing} from where the MiSTer's beam is. The MiSTer accepts audio only
 * while the audio option of its menu is on, which every ACK reports: {@link #audioActive()}
 * is what tells the local sound card to fall silent or to play.
 */
public final class MisterOutput implements ExternalPacer {

    /** A full frame is sent at least this often per field, so a lost datagram heals. */
    public static final int KEYFRAME_INTERVAL = 100;
    /**
     * After a new modeline the MiSTer's video generator stays still until a blit arrives:
     * one blit starts it, then a status request every {@link #PING_INTERVAL_NS} reads the
     * beam, and blits resume once the frame counter has advanced this many times.
     */
    static final int SWITCH_RUNNING_FRAMES = 3;
    /** Without the counter advancing for this long after the starting blit, another goes. */
    private static final long STARTER_RETRY_NS = TimeUnit.MILLISECONDS.toNanos(300);
    /** Audio blocks while the beam is unknown go out at most this often. */
    private static final long SWITCH_AUDIO_NS = TimeUnit.MILLISECONDS.toNanos(10);
    private static final long PING_INTERVAL_NS = TimeUnit.MILLISECONDS.toNanos(4);
    private static final long PING_TIMEOUT_NS = TimeUnit.MILLISECONDS.toNanos(30);
    /** Longest wait for the generator to run again; then blits resume regardless. */
    private static final long SWITCH_TIMEOUT_NS = TimeUnit.SECONDS.toNanos(3);
    /** ACKs in a row without a whole frame in the MiSTer's video FIFO that mean it stopped. */
    static final int STALLED_VIDEO_ACKS = 50;
    /**
     * A blit acknowledged this many blit periods after it was sent waited in the MiSTer's
     * receive queue: the MiSTer read nothing for a while, and what it kept of the frames
     * sent meanwhile is unknown.
     */
    static final int DEAF_BLITS = 4;
    /** Frames the interlace state must hold before the modeline follows it. */
    public static final int LACE_HYSTERESIS = 4;
    /**
     * Field number sent for the field drawn on the even raster lines.
     */
    static final int FIELD_EVEN = 0;
    /** F1 of the MiSTer field that draws the even raster lines (measured on the tube). */
    static final int EVEN_FIELD_F1 = 0;
    static final int FIELD_ODD = 1;

    private static final long ACK_TIMEOUT_NS = TimeUnit.SECONDS.toNanos(2);
    /** Without ACKs for this long the emulation goes back to the wall clock. */
    private static final long PACER_HOLD_NS = TimeUnit.MILLISECONDS.toNanos(500);
    /** A blit for frame {@code N} is sent this long after frame {@code N - 1} begins. */
    static final double GATE_MS = 1.0;
    /**
     * Time a blit must have arrived before the vertical blank that ends frame {@code N - 1}:
     * the transfer itself and the MiSTer's own delay in reading it.
     */
    static final double SAFETY_MS = 3.0;
    /** How long before its window opens a frame should be ready. Covers its encoding. */
    static final double READY_LEAD_MS = 6.0;
    /** Speed change per frame of readiness error. */
    static final double KP = 0.05;
    /** Integral gain, per frame and frame of readiness error. */
    static final double KI = 0.002;
    static final double SPEED_MIN = 0.95;
    static final double SPEED_MAX = 1.05;
    private static final double INTEGRAL_MAX = 0.03;
    private static final double INTEGRATE_BELOW_FRAMES = 0.3;
    /**
     * How far a blit's number may run ahead of the frame the MiSTer is showing when it
     * arrives.
     */
    static final int LEAD_MAX = 1;
    private static final long RETRY_NS = TimeUnit.SECONDS.toNanos(2);
    /** Longest wait of the sender before it looks at the ACKs again. */
    private static final long POLL_MS = 2;
    /** A wait for a window shorter than this is spun rather than slept. */
    private static final long SPIN_NS = TimeUnit.MICROSECONDS.toNanos(1500);
    private static final long RECEIVE_MS = 50;
    private static final long VERSION_TIMEOUT_MS = 250;
    private static final long INIT_TIMEOUT_MS = 250;

    /** The only rate the MiSTer plays exactly: its audio clock divided by 512. */
    public static final AudioRate AUDIO_RATE = AudioRate.HZ_48000;

    private static final int WIDTH = GroovyModeline.AMIGA_WIDTH;
    private static final int MAX_ROWS = Math.max(312 - GroovyModeline.AMIGA_FIRST_ROW_PAL,
            262 - GroovyModeline.AMIGA_FIRST_ROW_NTSC);

    /** One window of the raster plus what is needed to choose the mode. */
    private static final class Frame {
        final int[] pixels = new int[WIDTH * MAX_ROWS];
        boolean ntsc;
        boolean interlaced;
        boolean evenField;
        /** When the emulation handed it over. */
        long readyNs;
    }

    private enum Kind { RAW, DUPLICATE, LZ4, LZ4_DELTA }

    /** Why the output is not sending. */
    public enum Problem {
        /** Nothing answers the version query: MiSTer off, unreachable, or Groovy not loaded. */
        NO_ANSWER,
        /** No MiSTer answered the search on the PC's networks. */
        NOT_FOUND,
        /** The core answered the version query but not the session opening. */
        INIT_REFUSED,
        /** An open session stopped answering. */
        LINK_LOST,
        /** The network refused the traffic; the detail carries the system's message. */
        NETWORK
    }

    /** A {@link Problem} with the system's own words, when there are any. */
    public record Fault(Problem problem, String detail) {
    }

    private final MisterSettings settings;
    private final int port;
    /** The MiSTer of the current or last session; {@code null} until one is found. */
    private volatile InetAddress host;
    /** Where a search for the MiSTer looks; {@code null} for every network of the PC. */
    volatile List<MisterDiscovery.Probe> discoveryProbes;

    private final Object mailbox = new Object();
    private Frame back = new Frame();
    private Frame pending = new Frame();
    private Frame sending = new Frame();
    private boolean hasPending;

    private volatile boolean running;
    private volatile RgbMode rgbWanted;
    private volatile GroovyModeline.Geometry geometry;
    private Thread thread;

    // Sender thread state.
    private volatile GroovyClient client;
    private volatile GroovyModeline modeline;
    private boolean laceState;
    private int laceCount;
    /** Number of the last blit sent. */
    private int frameNumber;
    private final byte[][] wire = new byte[2][];
    private final byte[][] ref = new byte[2][];
    private final boolean[] haveRef = new boolean[2];
    private final int[] sinceKey = new int[2];
    private byte[] scratch = new byte[0];
    private byte[] comp = new byte[0];
    private final Lz4BlockEncoder encoder = new Lz4BlockEncoder();
    private volatile PcmSource audioSource;
    private final MisterAudioPacing audioPacing;
    private final byte[] audioPcm = new byte[MisterAudioPacing.MAX_BLOCK * 4];
    private boolean audioOffReported;
    private long nextRetryNs;
    private int lastAckEcho;
    private boolean sentSinceOpen;
    private long lastSendNs;

    // The MiSTer's raster clock. An ACK carries the beam's position when the MiSTer read the
    // blit's header, which it does as the blit arrives: paired with the moment the blit was
    // sent, it gives the time at which the raster was at position 0. The ACK itself may come
    // back late by any amount, and the outward trip only adds time, so of the recent samples
    // the latest origin is the one with the least delay.
    private static final int SENT_SLOTS = 256;
    private static final int ORIGIN_WINDOW = 64;
    private final long[] sentNs = new long[SENT_SLOTS];
    private final int[] sentNumber = new int[SENT_SLOTS];
    private final double[] origins = new double[ORIGIN_WINDOW];
    private int originCount;
    private int originNext;
    /** {@link System#nanoTime()} at which the raster was at position 0; valid when originCount > 0. */
    private double rasterOrigin;

    // The encoded frame waiting for its window.
    private boolean prepared;
    private int prepNumber;
    private int prepField;
    private boolean prepEven;
    private Kind prepKind;
    private int prepBytes;
    private byte[] prepData;

    // While the MiSTer applies a new modeline its video generator stops for some frames and
    // the compressed frames that arrive meanwhile are lost: a delta must not be built on
    // them. Full frames are sent until the ACKs show the generator running again.
    private double integral;
    private volatile double speed = DETACHED;
    private volatile double phaseErrorFrames;
    private volatile long resyncs;
    private boolean switching;
    private long switchStartNs;
    private int switchLastFrame;
    private long switchLastFrameNs;
    private int switchRunningFrames;
    private boolean pingOutstanding;
    private long pingSentNs;
    /** When the blit that starts the generator after a new modeline was sent; 0 = not yet. */
    private long starterNs;
    private long switchAudioNs;
    /** When the MiSTer last answered anything: a blit's ACK or a status request. */
    private long lastHeardNs;
    /** The newest report of the MiSTer's field parity: a blit's ACK or a status reply. */
    private Ack parityAck;
    /** ACKs in a row that found the MiSTer's video FIFO with room but no whole frame in it. */
    private int starvedAcks;

    // The controls plugged into the MiSTer: the receiver is opened by the sender thread at
    // the first session and lives until stop(); the state is applied by the emulation thread.
    private final int inputPort;
    private volatile MisterInputs inputs;
    private volatile MisterInputTarget inputTarget;
    private final MisterInputState inputState = new MisterInputState();
    private boolean inputsReported;

    // Statistics, readable from any thread.
    private volatile boolean connected;
    private volatile Fault fault;
    private volatile long framesSent;
    private volatile long framesReplaced;
    private volatile long bytesSent;
    private volatile long frameskipAcks;
    private volatile long lateArrivals;
    private volatile long videoRestarts;
    private volatile long deafSpells;
    private boolean deaf;
    private volatile long encodeNanos;
    private volatile Ack lastAck;
    private volatile long lastAckNs;
    private volatile long audioBlocks;
    private volatile long audioSamples;
    private volatile long audioPadded;

    public MisterOutput(MisterSettings settings) {
        this(settings, GroovyProtocol.PORT, GroovyProtocol.PORT_INPUTS);
    }

    /** As {@link #MisterOutput(MisterSettings)}, on another command port (tests). */
    MisterOutput(MisterSettings settings, int port) {
        this(settings, port, GroovyProtocol.PORT_INPUTS);
    }

    /** As {@link #MisterOutput(MisterSettings)}, on other command and input ports (tests). */
    MisterOutput(MisterSettings settings, int port, int inputPort) {
        this.settings = settings;
        this.inputPort = inputPort;
        this.port = port;
        this.rgbWanted = settings.color().resolve(false);
        this.geometry = settings.geometry();
        this.audioPacing = new MisterAudioPacing(AUDIO_RATE.hz, settings.audioLeadMs());
    }

    /**
     * Sets where the audio comes from, at {@link #AUDIO_RATE}; {@code null} sends none. Has
     * no effect when the settings keep the audio on the PC.
     */
    public void setAudioSource(PcmSource source) {
        audioSource = source;
    }

    /** Whether the settings send the audio to the MiSTer. */
    public boolean audioRequested() {
        return settings.audio();
    }

    /**
     * Whether the MiSTer is playing the audio now: a session is open with audio, an audio
     * source is set and the MiSTer's last ACK says its audio is on. Any thread.
     */
    public boolean audioActive() {
        Ack a = lastAck;
        return settings.audio() && audioSource != null && connected && a != null && a.audio();
    }

    /** Moves or resizes the picture; takes effect with the next frame. */
    public void setGeometry(GroovyModeline.Geometry g) {
        geometry = java.util.Objects.requireNonNull(g);
    }

    public GroovyModeline.Geometry geometry() {
        return geometry;
    }

    /** Tells the output which chipset produces the frames (AGA needs 24-bit colour). */
    public void setAga(boolean aga) {
        rgbWanted = settings.color().resolve(aga);
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(this::run, "mister-output");
        thread.setDaemon(true);
        thread.start();
    }

    /** Stops the sender and closes the session on the MiSTer. */
    public void stop() {
        Thread t;
        synchronized (this) {
            running = false;
            t = thread;
            thread = null;
        }
        if (t != null) {
            synchronized (mailbox) {
                mailbox.notifyAll();
            }
            LockSupport.unpark(t);
            try {
                t.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        MisterInputs in = inputs;
        inputs = null;
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignored) {
                // nothing to release beyond the socket
            }
        }
        MisterInputTarget target = inputTarget;
        inputTarget = null;
        if (target != null) {
            target.releaseAll();
        }
    }

    /**
     * Sets what the controls plugged into the MiSTer drive; {@code null} drives nothing. The
     * previous target is not released: it belongs to a machine that is gone.
     */
    public void setInputTarget(MisterInputTarget target) {
        inputTarget = target;
    }

    /**
     * Applies the controls plugged into the MiSTer. <b>Emulation thread, once per frame.</b>
     * Without a session nothing is reported any more, so what was held is released.
     */
    public void applyPendingInput() {
        MisterInputTarget target = inputTarget;
        if (target == null) {
            return;
        }
        MisterInputs in = inputs;
        if (in == null || !connected) {
            target.releaseAll();
            return;
        }
        in.drain(inputState);
        target.apply(inputState);
    }

    /** Input reports accepted from the MiSTer so far. */
    public long inputReports() {
        MisterInputs in = inputs;
        return in == null ? 0 : in.reports();
    }

    /**
     * Opens the input receiver if needed and asks the MiSTer for the reports of the session
     * about to open. A failure costs the controls, never the picture.
     */
    private void registerInputs(InetAddress mister, InetAddress bind) {
        if (settings.inputs() != MisterSettings.Inputs.MISTER) {
            return;
        }
        try {
            MisterInputs in = inputs;
            if (in != null && !in.mister().getAddress().equals(mister)) {
                // A search found another MiSTer: its reports come from elsewhere.
                inputs = null;
                in.close();
                in = null;
            }
            if (in == null) {
                in = new MisterInputs(new InetSocketAddress(mister, inputPort), bind, true);
                in.start();
                inputs = in;
            }
            in.register();
        } catch (IOException e) {
            if (!inputsReported) {
                System.err.println("[MISTER] controls from the MiSTer unavailable: " + message(e));
                inputsReported = true;
            }
        }
    }

    /**
     * Hands over the frame just completed. Output stage only: copies the visible window and
     * returns at once.
     *
     * @param raw      the raw raster, {@code rawWidth} pixels per line, {@code 0xRRGGBB}
     * @param ntsc     whether the beam ran an NTSC frame
     */
    public void submitFrame(int[] raw, int rawWidth, FrameInfo info, boolean ntsc) {
        if (!running) {
            return;
        }
        Frame f = back;
        int firstRow = GroovyModeline.amigaFirstRow(ntsc);
        int rows = (ntsc ? 262 : 312) - firstRow;
        int src = firstRow * rawWidth + GroovyModeline.AMIGA_FIRST_COLUMN;
        int dst = 0;
        for (int r = 0; r < rows; r++) {
            System.arraycopy(raw, src, f.pixels, dst, WIDTH);
            src += rawWidth;
            dst += WIDTH;
        }
        f.ntsc = ntsc;
        f.interlaced = info.interlaced();
        f.evenField = info.evenField();
        f.readyNs = System.nanoTime();
        synchronized (mailbox) {
            if (hasPending) {
                framesReplaced++;
            }
            back = pending;
            pending = f;
            hasPending = true;
            mailbox.notifyAll();
        }
    }

    // ---------------------------------------------------------------------
    // Sender thread

    private Frame take(long timeoutMs) throws InterruptedException {
        synchronized (mailbox) {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while (running && !hasPending) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return null;
                }
                TimeUnit.NANOSECONDS.timedWait(mailbox, left);
            }
            if (!hasPending) {
                return null;
            }
            Frame f = pending;
            pending = sending;
            sending = f;
            hasPending = false;
            return f;
        }
    }

    private void run() {
        try {
            while (running) {
                try {
                    // The session is checked after a frame is taken, so a frame is always
                    // encoded for the pixel layout wanted when it was handed over.
                    Frame f = prepared ? null : take(POLL_MS);
                    if (!running) {
                        break;
                    }
                    if (!ensureSession()) {
                        // Frames that cannot be sent are dropped while the MiSTer is away.
                        continue;
                    }
                    processAcks();
                    if (f != null) {
                        prepare(f);
                    }
                    if (prepared) {
                        trySend();
                    }
                } catch (LinkLostException e) {
                    drop(new Fault(Problem.LINK_LOST, null));
                } catch (IOException | RuntimeException e) {
                    drop(new Fault(Problem.NETWORK, message(e)));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closeClient();
            connected = false;
        }
    }

    private boolean ensureSession() throws IOException {
        RgbMode rgb = rgbWanted;
        if (client != null && client.rgbMode() != rgb) {
            // A new pixel layout needs a new INIT: reopen at once, this is not a failure.
            closeClient();
            nextRetryNs = System.nanoTime();
        }
        if (client != null) {
            return true;
        }
        long now = System.nanoTime();
        if (now - nextRetryNs < 0) {
            return false;
        }
        nextRetryNs = now + RETRY_NS;
        InetAddress mister = settings.host();
        InetAddress bind = settings.bind();
        if (mister == null) {
            List<MisterDiscovery.Probe> probes = discoveryProbes;
            MisterDiscovery.Found found = MisterDiscovery.best(MisterDiscovery.search(
                    probes != null ? probes : MisterDiscovery.probes(port),
                    MisterDiscovery.DEFAULT_TIMEOUT_MS));
            if (found == null) {
                Fault f = new Fault(Problem.NOT_FOUND, null);
                if (!f.equals(fault)) {
                    System.err.println("[MISTER] no MiSTer answers on the network; retrying"
                            + " every " + TimeUnit.NANOSECONDS.toSeconds(RETRY_NS) + " s");
                }
                fault = f;
                return false;
            }
            mister = found.mister();
            if (bind == null) {
                bind = found.local();
            }
        } else if (bind == null) {
            bind = GroovyClient.findLocalAddressFor(mister);
        }
        host = mister;
        GroovyClient c = new GroovyClient(new InetSocketAddress(mister, port), bind,
                settings.mtu());
        try {
            int version = c.queryVersion(VERSION_TIMEOUT_MS);
            if (version < 0) {
                fail(c, new Fault(Problem.NO_ANSWER, null));
                return false;
            }
            boolean lz4 = settings.compression() != MisterSettings.Compression.RAW;
            // The MiSTer takes the input registration when the session opens: before INIT.
            registerInputs(mister, bind);
            try {
                c.open(lz4, settings.audio() ? AUDIO_RATE : AudioRate.OFF, 2, rgb,
                        INIT_TIMEOUT_MS);
            } catch (IOException e) {
                fail(c, new Fault(Problem.INIT_REFUSED, null));
                return false;
            }
        } catch (IOException e) {
            fail(c, new Fault(Problem.NETWORK, message(e)));
            return false;
        }
        client = c;
        resetOrigin();
        startReceiver(c);
        modeline = null;
        Arrays.fill(haveRef, false);
        deaf = false;
        prepared = false;
        lastAckNs = System.nanoTime();
        lastHeardNs = lastAckNs;
        lastAckEcho = 0;
        lastAck = null;
        parityAck = null;
        sentSinceOpen = false;
        audioPacing.reset();
        audioOffReported = false;
        // A new session restarts the MiSTer's frame counter: our numbering restarts too.
        frameNumber = 0;
        connected = true;
        fault = null;
        System.out.println("[MISTER] connected to " + mister.getHostAddress()
                + (settings.host() == null ? " (found on the network)" : "")
                + (bind != null ? " from " + bind.getHostAddress() : "") + ", " + rgb + ", "
                + settings.compression());
        return true;
    }

    /** Reads the ACKs of {@code c} as they arrive, until it is closed. */
    private void startReceiver(GroovyClient c) {
        Thread rx = new Thread(() -> {
            try {
                while (running && c.receive(RECEIVE_MS)) {
                    // receive() stamps each ACK on arrival
                }
            } catch (IOException e) {
                // the sender notices the silence and drops the session
            }
        }, "mister-ack");
        rx.setDaemon(true);
        rx.setPriority(Thread.MAX_PRIORITY);
        rx.start();
    }

    private void fail(GroovyClient c, Fault f) {
        try {
            c.close();
        } catch (IOException ignored) {
            // nothing to release beyond the socket
        }
        if (!f.equals(fault)) {
            System.err.println("[MISTER] " + f.problem()
                    + (f.detail() != null ? " (" + f.detail() + ")" : "")
                    + " with " + host.getHostAddress() + "; retrying every "
                    + TimeUnit.NANOSECONDS.toSeconds(RETRY_NS) + " s");
        }
        fault = f;
    }

    private void drop(Fault f) {
        if (client != null) {
            System.err.println("[MISTER] link lost: " + f.problem()
                    + (f.detail() != null ? " (" + f.detail() + ")" : ""));
        }
        closeClient();
        fault = f;
        nextRetryNs = System.nanoTime() + RETRY_NS;
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    private void closeClient() {
        GroovyClient c = client;
        client = null;
        connected = false;
        speed = DETACHED;
        integral = 0;
        prepared = false;
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
                // nothing to release beyond the socket
            }
        }
    }

    // ---------------------------------------------------------------------
    // Timing

    /** Whether blits are timed on the MiSTer's beam: an ACK is fresh and no mode switch runs. */
    private boolean timed() {
        return modeline != null && !switching && speed != DETACHED
                && (originCount > 0 || lastAck != null);
    }

    /** The MiSTer's position in frames, fractional, at {@code ns}. */
    private double posAt(long ns) {
        GroovyModeline m = modeline;
        if (originCount > 0) {
            return (ns - rasterOrigin) / m.blitPeriodNs();
        }
        Ack a = lastAck;
        return a.frame() + (double) a.vCount() / m.vTotal()
                + (ns - lastAckNs) / m.blitPeriodNs();
    }

    /** When, in {@link System#nanoTime()}, the MiSTer reaches position {@code frames}. */
    private long timeOf(double frames) {
        GroovyModeline m = modeline;
        if (originCount > 0) {
            return (long) (rasterOrigin + frames * m.blitPeriodNs());
        }
        Ack a = lastAck;
        double from = a.frame() + (double) a.vCount() / m.vTotal();
        return lastAckNs + (long) ((frames - from) * m.blitPeriodNs());
    }

    /** Adds the sample of {@code a} to the raster clock, if its blit's send time is known. */
    private void sampleOrigin(Ack a) {
        GroovyModeline m = modeline;
        int slot = a.frameEcho() & (SENT_SLOTS - 1);
        if (m == null || switching || sentNumber[slot] != a.frameEcho()) {
            return;
        }
        addOrigin(sentNs[slot], a);
    }

    /** Adds the sample of a reply to a request sent at {@code sentAt}. */
    private void addOrigin(long sentAt, Ack a) {
        GroovyModeline m = modeline;
        double pos = a.frame() + (double) a.vCount() / m.vTotal();
        origins[originNext] = sentAt - pos * m.blitPeriodNs();
        originNext = (originNext + 1) % ORIGIN_WINDOW;
        originCount = Math.min(originCount + 1, ORIGIN_WINDOW);
        double best = -Double.MAX_VALUE;
        for (int i = 0; i < originCount; i++) {
            best = Math.max(best, origins[i]);
        }
        rasterOrigin = best;
    }

    /** Forgets the raster clock: a new modeline restarts the MiSTer's video timing. */
    private void resetOrigin() {
        originCount = 0;
        originNext = 0;
    }

    private double framesOf(double ms) {
        return ms * 1e6 / modeline.blitPeriodNs();
    }

    /** Fraction of a frame from the vertical blank to the frame's end. */
    private double blankFrames() {
        GroovyModeline m = modeline;
        return (double) (m.vTotal() - m.vActive()) / m.vTotal();
    }

    /** When the window of blit {@code n} opens. */
    private long gateNs(int n) {
        return timeOf(n - 1 + framesOf(GATE_MS));
    }

    /** The last moment blit {@code n} may be sent. */
    private long latestNs(int n) {
        return timeOf(n - blankFrames() - framesOf(SAFETY_MS));
    }

    /**
     * Interlaced, the MiSTer draws a field on the even lines in the frames it reports with
     * F1 = {@link #EVEN_FIELD_F1}: the parity of the number must match the field.
     */
    private int matchParity(int n, boolean even) {
        Ack a = parityAck;
        if (!laceState || a == null) {
            return n;
        }
        int f1 = ((a.bits() >> 5) & 1) ^ ((n - a.frame()) & 1);
        return f1 == (even ? EVEN_FIELD_F1 : 1 - EVEN_FIELD_F1) ? n : n + 1;
    }

    /** The first number whose window is still open at {@code ns}. */
    private int firstOpenNumber(long ns, boolean even) {
        double pos = posAt(ns);
        int n = (int) Math.floor(pos + blankFrames() + framesOf(SAFETY_MS)) + 1;
        n = Math.max(n, frameNumber + 1);
        return matchParity(n, even);
    }

    // ---------------------------------------------------------------------
    // Sending

    /** Chooses the number of {@code f}, measures how early it was ready, and encodes it. */
    private void prepare(Frame f) throws IOException {
        if (f.interlaced != laceState) {
            if (++laceCount >= LACE_HYSTERESIS) {
                laceState = f.interlaced;
                laceCount = 0;
            }
        } else {
            laceCount = 0;
        }
        GroovyModeline m = GroovyModeline.amiga(f.ntsc, laceState, geometry);
        RgbMode rgb = client.rgbMode();
        if (!m.equals(modeline)) {
            client.switchres(m);
            modeline = m;
            System.out.printf(java.util.Locale.ROOT,
                    "[MISTER] mode: %s %s, %dx%d, %.3f MHz, %.3f Hz%n",
                    f.ntsc ? "NTSC" : "PAL", laceState ? "interlaced" : "progressive",
                    m.hActive(), m.vActive(), m.pclockMHz(), m.blitRateHz());
            int size = m.blitBytes(rgb);
            for (int i = 0; i < 2; i++) {
                wire[i] = new byte[size];
                ref[i] = new byte[size];
                haveRef[i] = false;
            }
            scratch = new byte[size];
            comp = new byte[Lz4BlockEncoder.maxCompressedLength(size)];
            switching = true;
            resetOrigin();
            switchStartNs = System.nanoTime();
            switchLastFrame = Integer.MIN_VALUE;
            switchRunningFrames = 0;
            pingOutstanding = false;
            starterNs = 0;
        }

        int n = frameNumber + 1;
        if (timed()) {
            n = matchParity(n, f.evenField);
            if (f.readyNs > latestNs(n)) {
                // Ready after its window closed: the first number still open. That frame of
                // the MiSTer shows the previous picture again.
                n = firstOpenNumber(f.readyNs, f.evenField);
                resyncs++;
                speed = clamp(1 + integral, SPEED_MIN, SPEED_MAX);
            } else {
                updatePacer(gateNs(n) - f.readyNs);
            }
        }
        prepNumber = n;
        prepEven = f.evenField;
        encode(f, m, rgb);
    }

    /**
     * Phase lock on readiness: each frame should be ready {@link #READY_LEAD_MS} before its
     * window opens. Ready later, the emulation speeds up; earlier, it slows down.
     */
    private void updatePacer(long earlyNs) {
        double period = modeline.blitPeriodNs();
        double e = (READY_LEAD_MS * 1e6 - earlyNs) / period;
        phaseErrorFrames = e;
        // The integral learns the steady ratio between the two clocks; it is fed only near
        // the lock, so a transient (start, a stall) does not wind it up.
        if (Math.abs(e) < INTEGRATE_BELOW_FRAMES) {
            integral = clamp(integral + KI * e, -INTEGRAL_MAX, INTEGRAL_MAX);
        }
        speed = clamp(1 + integral + KP * e, SPEED_MIN, SPEED_MAX);
    }

    private void encode(Frame f, GroovyModeline m, RgbMode rgb) {
        int field = laceState ? (f.evenField ? FIELD_EVEN : FIELD_ODD) : 0;
        int size = wire[field].length;
        long t0 = System.nanoTime();
        int lines = m.linesPerBlit();
        int width = m.hActive();
        if (width < WIDTH) {
            // The adjustment left no room for the whole window: drop its right columns.
            for (int r = 1; r < lines; r++) {
                System.arraycopy(f.pixels, r * WIDTH, f.pixels, r * width, width);
            }
        }
        convert(f.pixels, width * lines, rgb, wire[field]);
        switch (settings.compression()) {
            case RAW -> {
                if (!switching && haveRef[field] && Arrays.equals(wire[field], ref[field])) {
                    prepKind = Kind.DUPLICATE;
                    prepBytes = 0;
                } else {
                    prepKind = Kind.RAW;
                    prepBytes = size;
                    prepData = wire[field];
                }
            }
            case LZ4 -> {
                prepBytes = encoder.compress(wire[field], 0, size, comp, 0);
                prepKind = Kind.LZ4;
                prepData = comp;
            }
            case LZ4_DELTA -> {
                boolean delta = !switching && haveRef[field]
                        && sinceKey[field] < KEYFRAME_INTERVAL;
                byte[] src = wire[field];
                if (delta) {
                    GroovyProtocol.delta(wire[field], ref[field], scratch, size);
                    src = scratch;
                    sinceKey[field]++;
                } else {
                    sinceKey[field] = 0;
                }
                prepBytes = encoder.compress(src, 0, size, comp, 0);
                prepKind = delta ? Kind.LZ4_DELTA : Kind.LZ4;
                prepData = comp;
            }
            default -> throw new IllegalStateException();
        }
        encodeNanos += System.nanoTime() - t0;
        // The reference follows the encoded frame: it is sent, whatever number it ends up with.
        if (prepKind != Kind.DUPLICATE) {
            byte[] t = ref[field];
            ref[field] = wire[field];
            wire[field] = t;
            if (prepKind == Kind.RAW) {
                prepData = ref[field];
            }
        }
        haveRef[field] = true;
        prepField = field;
        prepared = true;
    }

    /** Sends the prepared blit when its window is open; renumbers it if the window closed. */
    private void trySend() throws IOException {
        if (switching) {
            long now = System.nanoTime();
            if (starterNs == 0 || (switchRunningFrames == 0 && now - starterNs > STARTER_RETRY_NS)) {
                // The generator waits for a blit: this one starts it, timed or not.
                starterNs = now;
                transmit();
                return;
            }
            pingDuringSwitch();
            if (switching) {
                return;
            }
        }
        if (timed()) {
            long now = System.nanoTime();
            if (now > latestNs(prepNumber)) {
                int n = firstOpenNumber(now, prepEven);
                prepNumber = n;
                resyncs++;
            }
            long gate = gateNs(prepNumber);
            long wait = gate - now;
            if (wait > TimeUnit.MILLISECONDS.toNanos(POLL_MS)) {
                // Not yet: look at the ACKs again in a while.
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(POLL_MS));
                return;
            }
            if (wait > SPIN_NS) {
                LockSupport.parkNanos(wait - SPIN_NS);
            }
            while (System.nanoTime() < gate) {
                Thread.onSpinWait();
            }
        }
        transmit();
    }

    /**
     * While the MiSTer applies a new modeline: reads its beam with status requests, one at a
     * time, and ends the switch once its frame counter runs. Each reply is placed in time by
     * the moment its request was sent, as a blit's ACK is.
     */
    private void pingDuringSwitch() throws IOException {
        long now = System.nanoTime();
        if (now - switchStartNs > SWITCH_TIMEOUT_NS) {
            endSwitch();
            return;
        }
        if (pingOutstanding) {
            AckAt st = client.latestStatusAt();
            if (st != null && st.nanos() - pingSentNs > 0) {
                pingOutstanding = false;
                lastHeardNs = st.nanos();
                Ack a = st.ack();
                parityAck = a;
                if (a.frame() != switchLastFrame) {
                    boolean next = a.frame() == switchLastFrame + 1
                            && now - switchLastFrameNs < TimeUnit.MILLISECONDS.toNanos(60);
                    if (next) {
                        switchRunningFrames++;
                    } else {
                        // Not running yet, or restarted: the samples so far do not count.
                        switchRunningFrames = 0;
                        resetOrigin();
                    }
                    switchLastFrame = a.frame();
                    switchLastFrameNs = now;
                }
                if (switchRunningFrames > 0) {
                    addOrigin(pingSentNs, a);
                }
                if (switchRunningFrames >= SWITCH_RUNNING_FRAMES) {
                    endSwitch();
                    return;
                }
            } else if (now - pingSentNs > PING_TIMEOUT_NS) {
                pingOutstanding = false;
            }
        }
        if (!pingOutstanding && now - pingSentNs >= PING_INTERVAL_NS) {
            pingSentNs = System.nanoTime();
            client.requestStatus();
            pingOutstanding = true;
        }
        if (now - switchAudioNs >= SWITCH_AUDIO_NS) {
            // The MiSTer keeps playing while its video restarts: keep its audio FIFO fed.
            switchAudioNs = now;
            sendAudio();
        }
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
    }

    private void transmit() throws IOException {
        int n = prepNumber;
        sendAudio();
        int vSync = 0;
        int slot = n & (SENT_SLOTS - 1);
        sentNumber[slot] = n;
        sentNs[slot] = System.nanoTime();
        switch (prepKind) {
            case DUPLICATE -> client.blitDuplicate(n, prepField, vSync);
            case RAW -> client.blitRaw(n, prepField, vSync, prepData, 0, prepBytes);
            case LZ4 -> client.blitLz4(n, prepField, vSync, prepData, 0, prepBytes, false);
            case LZ4_DELTA -> client.blitLz4(n, prepField, vSync, prepData, 0, prepBytes, true);
        }
        prepared = false;
        frameNumber = n;
        sentSinceOpen = true;
        lastSendNs = System.nanoTime();
        framesSent++;
        bytesSent += prepBytes;
    }

    /** The block of samples that goes before a blit. */
    private void sendAudio() throws IOException {
        PcmSource src = audioSource;
        Ack a = lastAck;
        GroovyModeline m = modeline;
        if (src == null || client.audioRate() == AudioRate.OFF || a == null || a.frameEcho() == 0
                || m == null) {
            audioPacing.reset();
            return;
        }
        if (!a.audio()) {
            if (!audioOffReported) {
                audioOffReported = true;
                System.out.println("[MISTER] audio off in the Groovy core menu"
                        + " (Audio > Audio: On): the sound stays on the PC");
            }
            audioPacing.reset();
            return;
        }
        long now = System.nanoTime();
        // During a mode switch the beam's position is unknown: the host's clock stands in.
        double pos = timed() ? posAt(now) : Double.NaN;
        int frames = audioPacing.due(pos, now, m.blitPeriodNs());
        if (frames == 0) {
            return;
        }
        int real = src.render(audioPcm, frames);
        client.audio(audioPcm, 0, frames * 4);
        audioPacing.sent(frames);
        audioBlocks++;
        audioSamples += frames;
        audioPadded += frames - real;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Emulation speed the MiSTer asks for, or {@link #DETACHED}. Any thread. */
    @Override
    public double speed() {
        return speed;
    }

    /** Times a blit missed its window and took a later number. */
    public long resyncs() {
        return resyncs;
    }

    /** Last readiness error, in frames: positive when the frame was ready late. */
    public double phaseErrorFrames() {
        return phaseErrorFrames;
    }

    /** Takes in the newest ACK the receiver thread has read. */
    private void processAcks() {
        GroovyClient c = client;
        AckAt at = c.latestAckAt();
        long now = System.nanoTime();
        if (at != null && at.ack().frameEcho() != lastAckEcho) {
            Ack a = at.ack();
            lastAckEcho = a.frameEcho();
            lastAckNs = at.nanos();
            lastHeardNs = at.nanos();
            lastAck = a;
            if (speed == DETACHED && !switching) {
                speed = clamp(1 + integral, SPEED_MIN, SPEED_MAX);
            }
            parityAck = a;
            sampleOrigin(a);
            if (a.frameskip()) {
                frameskipAcks++;
            }
            if (modeline != null && !switching) {
                double lead = a.frameEcho() - (a.frame() + (double) a.vCount() / modeline.vTotal());
                if (lead < blankFrames()) {
                    lateArrivals++;
                }
            }
            checkVideoStall(a);
            checkDeaf(at);
        } else if (now - lastAckNs > PACER_HOLD_NS && speed != DETACHED) {
            speed = DETACHED;
        }
        // Silent only if we kept sending and nothing came back: a pause of the emulation
        // sends nothing, and that is no reason to drop the session.
        if (sentSinceOpen && lastSendNs - lastHeardNs > ACK_TIMEOUT_NS) {
            throw new LinkLostException();
        }
    }

    /**
     * While the MiSTer reads nothing (its input devices are reopened when one is plugged or
     * unplugged, for instance) the blits queue up, and those that overflow its receive
     * buffer are lost: a delta built on a lost frame draws noise until the next full frame.
     * A blit acknowledged late reveals the queue, so the next frame of each field is sent
     * whole, again and again until the acknowledgements are timely.
     */
    private void checkDeaf(AckAt at) {
        GroovyModeline m = modeline;
        int slot = at.ack().frameEcho() & (SENT_SLOTS - 1);
        if (m == null || switching || sentNumber[slot] != at.ack().frameEcho()) {
            return;
        }
        long waitedNs = at.nanos() - sentNs[slot];
        if (waitedNs <= DEAF_BLITS * m.blitPeriodNs()) {
            deaf = false;
            return;
        }
        haveRef[0] = false;
        haveRef[1] = false;
        if (!deaf) {
            deaf = true;
            deafSpells++;
            System.err.println("[MISTER] the MiSTer did not read for " + waitedNs / 1_000_000
                    + " ms: sending full frames again");
        }
    }

    /**
     * The MiSTer's video pipeline can stop for good after a blit reaches it with the video
     * FIFO empty during the vertical blank: from then on the FIFO has room but never holds a
     * whole frame again, and the screen stays black. Re-applying the modeline restarts it.
     */
    private void checkVideoStall(Ack a) {
        if (switching || !a.vramReady() || a.vramEndFrame()) {
            starvedAcks = 0;
            return;
        }
        if (++starvedAcks >= STALLED_VIDEO_ACKS) {
            starvedAcks = 0;
            videoRestarts++;
            modeline = null;
            // The next frame sends the modeline again and a full frame.
            System.err.println("[MISTER] MiSTer video stalled: video mode sent again");
        }
    }

    /** Back to deltas, starting from one full frame per field. */
    private void endSwitch() {
        switching = false;
        pingOutstanding = false;
        haveRef[0] = false;
        haveRef[1] = false;
        lastHeardNs = System.nanoTime();
        if (originCount > 0) {
            // The beam is known: the first blit of the new mode goes out in its window.
            lastAckNs = lastHeardNs;
            speed = clamp(1 + integral, SPEED_MIN, SPEED_MAX);
        }
    }

    /** Converts {@code count} {@code 0xRRGGBB} pixels to the wire layout. */
    static void convert(int[] px, int count, RgbMode rgb, byte[] out) {
        int o = 0;
        switch (rgb) {
            case RGB565 -> {
                for (int i = 0; i < count; i++) {
                    int c = px[i];
                    int p = (c >>> 8 & 0xF800) | (c >>> 5 & 0x07E0) | (c >>> 3 & 0x001F);
                    out[o++] = (byte) p;
                    out[o++] = (byte) (p >>> 8);
                }
            }
            case BGR888 -> {
                for (int i = 0; i < count; i++) {
                    int c = px[i];
                    out[o++] = (byte) c;
                    out[o++] = (byte) (c >>> 8);
                    out[o++] = (byte) (c >>> 16);
                }
            }
            case BGRA8888 -> {
                for (int i = 0; i < count; i++) {
                    int c = px[i];
                    out[o++] = (byte) c;
                    out[o++] = (byte) (c >>> 8);
                    out[o++] = (byte) (c >>> 16);
                    out[o++] = 0;
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Status

    public boolean isConnected() {
        return connected;
    }

    /** Why the last attempt failed or the link dropped; {@code null} while healthy. */
    public Fault fault() {
        return fault;
    }

    /** Whether the sender thread runs (connected or retrying). */
    public boolean isRunning() {
        return running;
    }

    public MisterSettings settings() {
        return settings;
    }

    /** An open session that stopped answering. */
    private static final class LinkLostException extends RuntimeException {
        LinkLostException() {
            super(null, null, false, false);
        }
    }

    public long framesSent() {
        return framesSent;
    }

    /** Frames overwritten in the mailbox before the sender could take them. */
    public long framesReplaced() {
        return framesReplaced;
    }

    public long bytesSent() {
        return bytesSent;
    }

    public long frameskipAcks() {
        return frameskipAcks;
    }

    /** Blits the MiSTer received in the vertical blank before their frame, or later. */
    public long lateArrivals() {
        return lateArrivals;
    }

    /**
     * The MiSTer of the current or last session: the configured one, or the one a search
     * found; {@code null} while a search has found none.
     */
    public InetAddress host() {
        return host;
    }

    /** Times the MiSTer was found not reading, and full frames were sent. */
    public long deafSpells() {
        return deafSpells;
    }

    /** Times the MiSTer's video pipeline was found stopped and restarted. */
    public long videoRestarts() {
        return videoRestarts;
    }

    public long encodeNanos() {
        return encodeNanos;
    }

    /** Blocks of audio sent. */
    public long audioBlocks() {
        return audioBlocks;
    }

    /** Samples of audio sent, padding included. */
    public long audioSamples() {
        return audioSamples;
    }

    /** Samples sent as silence because the emulation had not produced them. */
    public long audioPadded() {
        return audioPadded;
    }

    /** Times the MiSTer's audio FIFO is estimated to have run dry. */
    public long audioUnderruns() {
        return audioPacing.underruns();
    }

    public Ack lastAck() {
        return lastAck;
    }

    /** The modeline in use, or {@code null} before the first frame. */
    public GroovyModeline modeline() {
        return modeline;
    }
}
