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

package io.github.zolfrik.amija.custom.audio;

import io.github.zolfrik.amija.common.AppHome;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * PURELY COSMETIC emulation of the floppy drive sounds (head click, motor hum, spin-up,
 * disk "snatch"), ENTIRELY DECOUPLED from the emulation and from Paula's audio pipeline.
 *
 * <p>For this feature PERFORMANCE has absolute priority over accuracy.
 * <ul>
 *   <li>The WAV samples are loaded and decoded once (static cache) and played through
 *       {@link javax.sound.sampled.Clip}, handled by the system audio mixer on its own
 *       threads: the emulation thread NEVER does per-sample mixing.</li>
 *   <li>The events (motor/step/disk) are queued to a single dedicated daemon thread through
 *       a preallocated ring queue, with no task created per event on the emulation
 *       thread.</li>
 * </ul>
 *
 * <p>When an external route is set ({@link #setExternalRoute}) and active, the same sounds
 * are mixed in software into a stream that another output pulls ({@link #mixInto}), and
 * the clips are muted, so the drive is heard where the rest of the machine's sound is. The
 * emulation thread still does no mixing: the pulling thread does.
 *
 * <p>The whole feature is switched off by a single boolean flag: if no instance is created
 * or hooked up (or if {@code enabled=false}), the {@code FloppyDrive} stays silent at zero
 * cost (just a null check on the events, which are rare anyway). It is enabled only in
 * {@code Amija}.
 */
public final class FloppySound {

    /** Sample variant to use (file prefix: drive_<type>_<VARIANT>.wav). */
    private static final String VARIANT = "A500";

    /**
     * Folder of the WAV samples, relative to the application home (where
     * {@code amiga.properties} is). The files live there and not inside the jar, so the user
     * can replace them with their own keeping the names. If the folder is missing or empty
     * the feature simply stays inert, as with {@code floppy.sound.volume=0}.
     */
    private static final Path SAMPLE_DIR = AppHome.resolve("floppysounds");

    // The samples are normalised to signed 16-bit mono little-endian PCM, KEEPING the file's
    // NATIVE sample rate (the files may mix rates, e.g. click=48 kHz, others=44.1 kHz).
    // Avoiding sample-rate conversion matters: the JVM's default converters often do NOT
    // support it, while normalising encoding/channels alone is always available (and here it
    // is in fact an identity, since the files are already 16-bit mono LE).
    private static final int FRAME_BYTES = 2; // mono 16-bit

    /** Decoded sample: format (with its native rate) + raw PCM. */
    private record Sample(AudioFormat fmt, byte[] pcm) {}

    /** Length of a single click taken from the WAV (frames). ~63 ms at 44.1 kHz. */
    private static final int CLICK_FRAMES = 2800;
    private static final int CLICK_POOL = 6;   // clicks that can overlap during a seek
    private static final int EVENT_QUEUE_SIZE = 128;
    private static final int EVENT_MOTOR = 1;
    private static final int EVENT_STEP = 2;
    private static final int EVENT_DISK = 3;
    private static final int EVENT_GAIN = 4;
    private static final int EVENT_CLOSE = 5;
    private static final int EVENT_ROUTE = 6;
    /** How often the worker looks at the external route while no event arrives. */
    private static final long ROUTE_POLL_MS = 50;

    /**
     * Default attenuation applied to every clip (dB) so the game audio is not drowned. A
     * negative value = quieter (every -6 dB roughly halves the perceived volume). Can be
     * overridden from the constructor or at run time with {@link #setGainDb}.
     */
    public static final float DEFAULT_GAIN_DB = -18.0f;

    // Static cache of the decoded samples (shared between instances): loaded only once.
    private static final Map<String, Sample> SAMPLE_CACHE = new HashMap<>();

    private final boolean active;          // false => istanza inerte (no audio / disabilitata)
    private volatile float gainDb;         // attenuazione corrente in dB (configurabile)
    private final Object eventLock = new Object();
    private final int[] eventTypes = new int[EVENT_QUEUE_SIZE];
    private final int[] eventFlags = new int[EVENT_QUEUE_SIZE];
    private final float[] eventValues = new float[EVENT_QUEUE_SIZE];
    private int eventHead;
    private int eventTail;
    private int eventSize;
    private volatile boolean workerRunning;
    private Thread worker;

    private Clip startupClip;
    private Clip snatchClip;
    private Clip spinClip;                  // motor with a disk
    private Clip spinNdClip;                // motor without a disk (falls back to spinClip if absent)
    private Clip[] clickPool;
    private int clickIdx;

    // Internal state (mutated only on the worker thread).
    private boolean spinning;
    private boolean hasDisk;
    private Clip currentSpin;

    // External route: whether the software mix is wanted, and whether the clips are muted.
    private volatile BooleanSupplier route;
    private boolean routeActive;            // worker thread only
    private boolean clipsMuted;             // worker thread only

    // Software voices for the external route. The worker sets them, the pulling thread
    // plays them; both under mixLock.
    private final Object mixLock = new Object();
    private Pcm startupPcm;
    private Pcm snatchPcm;
    private Pcm spinPcm;
    private Pcm spinNdPcm;
    private Pcm clickPcm;
    private Voice spinVoice;
    private final List<Voice> oneShots = new ArrayList<>();

    /**
     * @param enabled if {@code false} the instance loads nothing and every method is a no-op
     *                (feature off entirely).
     */
    public FloppySound(boolean enabled) {
        this(enabled, DEFAULT_GAIN_DB);
    }

    /**
     * @param enabled if {@code false} the instance loads nothing and every method is a no-op
     *                (feature off entirely).
     * @param gainDb  attenuation in dB applied to every drive sound (negative = quieter).
     *                Can be changed at run time with {@link #setGainDb}.
     */
    public FloppySound(boolean enabled, float gainDb) {
        this.gainDb = gainDb;
        boolean ok = false;
        if (enabled) {
            try {
                Sample click   = loadSample("drive_click_"   + VARIANT);
                Sample spin     = loadSample("drive_spin_"    + VARIANT);
                Sample spinNd   = loadSample("drive_spinnd_"  + VARIANT); // often absent for the A500
                Sample startup  = loadSample("drive_startup_" + VARIANT);
                Sample snatch   = loadSample("drive_snatch_"  + VARIANT);

                startupPcm = Pcm.of(startup);
                snatchPcm = Pcm.of(snatch);
                spinPcm = Pcm.of(spin);
                spinNdPcm = spinNd != null ? Pcm.of(spinNd) : spinPcm;

                startupClip = makeClip(startup);
                snatchClip  = makeClip(snatch);
                spinClip    = makeClip(spin);
                spinNdClip  = (spinNd != null) ? makeClip(spinNd) : spinClip;

                Sample oneClick = extractClick(click);
                if (oneClick != null) {
                    clickPool = new Clip[CLICK_POOL];
                    for (int i = 0; i < CLICK_POOL; i++) clickPool[i] = makeClip(oneClick);
                    clickPcm = Pcm.of(oneClick);
                }

                // At least one sample loaded: the feature is active.
                ok = (startupClip != null || spinClip != null || clickPool != null);
                if (ok) {
                    workerRunning = true;
                    worker = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            workerLoop();
                        }
                    }, "floppy-sound");
                    worker.setDaemon(true);
                    worker.start();
                }
            } catch (Throwable t) {
                // No audio device, missing resources, etc.: the feature is silently inactive.
                System.out.println("[FLOPPYSND] disabled (init failed): " + t);
                ok = false;
            }
        }
        this.active = ok;
    }

    // -- Event API (called by the emulation thread: enqueue on the ring buffer) --

    /** Motor on/off. {@code hasDisk} = a disk is in the drive. */
    public void onMotor(boolean running, boolean hasDisk) {
        if (!active) return;
        enqueueEvent(EVENT_MOTOR, (running ? 1 : 0) | (hasDisk ? 2 : 0), 0.0f);
    }

    /** Head step pulse (one click). */
    public void onStep() {
        if (!active) return;
        enqueueEvent(EVENT_STEP, 0, 0.0f);
    }

    /** Disk insertion/ejection. */
    public void onDiskChange(boolean inserted) {
        if (!active) return;
        enqueueEvent(EVENT_DISK, inserted ? 1 : 0, 0.0f);
    }

    /**
     * Sets the volume of the drive sounds at run time (dB, negative = quieter). Applied to
     * every clip on the audio thread; values outside the control's range are clamped
     * automatically.
     */
    public void setGainDb(float db) {
        if (!active) return;
        enqueueEvent(EVENT_GAIN, 0, db);
    }

    /** Volume corrente in dB. */
    public float getGainDb() { return gainDb; }

    /**
     * Sets the external route ({@code null} removes it). While {@code active} answers true,
     * the clips are muted and the sounds reach whoever calls {@link #mixInto}; while it
     * answers false, the clips play as usual. Polled by the worker thread.
     */
    public void setExternalRoute(BooleanSupplier active) {
        if (!this.active) return;
        route = active;
        enqueueEvent(EVENT_ROUTE, 0, 0.0f);
    }

    /**
     * Adds the drive sounds to {@code frames} interleaved stereo signed 16-bit little-endian
     * samples at {@code rate} Hz, saturating. Called by the thread that pulls the external
     * stream; adds nothing while the route is not active.
     */
    public void mixInto(byte[] pcm, int frames, int rate) {
        if (!active || rate <= 0) return;
        synchronized (mixLock) {
            if (spinVoice == null && oneShots.isEmpty()) return;
            float g = (float) Math.pow(10.0, gainDb / 20.0);
            for (int f = 0; f < frames; f++) {
                float sum = 0.0f;
                if (spinVoice != null) sum += spinVoice.next(rate);
                for (int i = oneShots.size() - 1; i >= 0; i--) {
                    Voice v = oneShots.get(i);
                    if (v.done()) {
                        oneShots.remove(i);
                    } else {
                        sum += v.next(rate);
                    }
                }
                int add = Math.round(sum * g);
                if (add == 0) continue;
                int o = f * 4;
                addSample(pcm, o, add);
                addSample(pcm, o + 2, add);
            }
        }
    }

    private static void addSample(byte[] pcm, int o, int add) {
        int v = (short) ((pcm[o] & 0xFF) | (pcm[o + 1] << 8)) + add;
        if (v > Short.MAX_VALUE) v = Short.MAX_VALUE;
        else if (v < Short.MIN_VALUE) v = Short.MIN_VALUE;
        pcm[o] = (byte) v;
        pcm[o + 1] = (byte) (v >> 8);
    }

    /** Releases the thread and the audio lines. */
    public void close() {
        if (!active) return;
        enqueueEvent(EVENT_CLOSE, 0, 0.0f);
        if (Thread.currentThread() != worker) {
            try {
                worker.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // -- Logic (run on the worker thread) ---------------------------------------------

    private void enqueueEvent(int type, int flags, float value) {
        synchronized (eventLock) {
            if (!workerRunning) return;
            if (eventSize == EVENT_QUEUE_SIZE) {
                if (type == EVENT_STEP) return;
                eventTypes[eventHead] = 0;
                eventHead = (eventHead + 1) & (EVENT_QUEUE_SIZE - 1);
                eventSize--;
            }
            eventTypes[eventTail] = type;
            eventFlags[eventTail] = flags;
            eventValues[eventTail] = value;
            eventTail = (eventTail + 1) & (EVENT_QUEUE_SIZE - 1);
            eventSize++;
            eventLock.notifyAll();
        }
    }

    private void workerLoop() {
        while (true) {
            int type;
            int flags;
            float value;
            synchronized (eventLock) {
                while (workerRunning && eventSize == 0) {
                    try {
                        if (route != null || routeActive) {
                            // A timed wait, so a route that changes on its own is followed.
                            eventLock.wait(ROUTE_POLL_MS);
                            if (eventSize == 0) break;
                        } else {
                            eventLock.wait();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        workerRunning = false;
                        break;
                    }
                }
                if (!workerRunning && eventSize == 0) return;
                if (eventSize == 0) {
                    type = EVENT_ROUTE;
                    flags = 0;
                    value = 0.0f;
                } else {
                    type = eventTypes[eventHead];
                    flags = eventFlags[eventHead];
                    value = eventValues[eventHead];
                    eventTypes[eventHead] = 0;
                    eventHead = (eventHead + 1) & (EVENT_QUEUE_SIZE - 1);
                    eventSize--;
                }
            }

            switch (type) {
                case EVENT_MOTOR -> doMotor((flags & 1) != 0, (flags & 2) != 0);
                case EVENT_STEP -> doStep();
                case EVENT_DISK -> doDiskChange((flags & 1) != 0);
                case EVENT_GAIN -> {
                    gainDb = value;
                    applyGainAll();
                }
                case EVENT_ROUTE -> checkRoute();
                case EVENT_CLOSE -> {
                    closeResources();
                    synchronized (eventLock) {
                        workerRunning = false;
                        eventHead = eventTail = eventSize = 0;
                    }
                    return;
                }
                default -> { }
            }
        }
    }

    private void closeResources() {
        stopSpin();
        closeClip(startupClip);
        closeClip(snatchClip);
        closeClip(spinClip);
        if (spinNdClip != spinClip) closeClip(spinNdClip);
        if (clickPool != null) for (Clip c : clickPool) closeClip(c);
    }

    private void doMotor(boolean running, boolean disk) {
        checkRoute();
        this.hasDisk = disk;
        if (running) {
            if (!spinning) {
                spinning = true;
                playOnce(startupClip);
                playSoft(startupPcm);
            }
            startSpin();
        } else {
            spinning = false;
            stopSpin();
            stop(startupClip);
            stopSoft(startupPcm);
        }
        updateSoftSpin();
    }

    private void doStep() {
        checkRoute();
        if (clickPool == null) return;
        Clip c = clickPool[clickIdx];
        clickIdx = (clickIdx + 1) % clickPool.length;
        playOnce(c);
        playSoft(clickPcm);
    }

    private void doDiskChange(boolean inserted) {
        checkRoute();
        boolean wasDisk = this.hasDisk;
        this.hasDisk = inserted;
        if (inserted && !wasDisk) {
            playOnce(snatchClip);
            playSoft(snatchPcm);
        }
        if (spinning) startSpin(); // switch to the right loop (with/without disk)
        updateSoftSpin();
    }

    /**
     * Follows the external route: while it is active the clips are muted and the software
     * voices play; when it stops, the clips are heard again and the voices are dropped.
     */
    private void checkRoute() {
        BooleanSupplier r = route;
        boolean now = r != null && r.getAsBoolean();
        if (now == routeActive) return;
        routeActive = now;
        synchronized (mixLock) {
            oneShots.clear();
        }
        updateSoftSpin();
        if (clipsMuted != now) {
            clipsMuted = now;
            applyGainAll();
        }
    }

    private void playSoft(Pcm p) {
        if (!routeActive || p == null) return;
        synchronized (mixLock) {
            if (oneShots.size() >= CLICK_POOL * 2) oneShots.remove(0);
            oneShots.add(new Voice(p, false));
        }
    }

    private void stopSoft(Pcm p) {
        synchronized (mixLock) {
            oneShots.removeIf(v -> v.pcm == p);
        }
    }

    /** The software motor loop: the right sample while the motor runs and the route is on. */
    private void updateSoftSpin() {
        Pcm target = null;
        if (routeActive && spinning) {
            target = hasDisk ? spinPcm : spinNdPcm;
            if (target == null) target = spinPcm != null ? spinPcm : spinNdPcm;
        }
        synchronized (mixLock) {
            if (target == null) {
                spinVoice = null;
            } else if (spinVoice == null || spinVoice.pcm != target) {
                spinVoice = new Voice(target, true);
            }
        }
    }

    private void startSpin() {
        Clip target = hasDisk ? spinClip : spinNdClip;
        if (target == null) target = (spinClip != null) ? spinClip : spinNdClip;
        if (target == null) return;
        if (currentSpin == target && target.isRunning()) return;
        stopSpin();
        currentSpin = target;
        target.setFramePosition(0);
        target.loop(Clip.LOOP_CONTINUOUSLY);
    }

    private void stopSpin() {
        if (currentSpin != null) currentSpin.stop();
        currentSpin = null;
    }

    private static void playOnce(Clip c) {
        if (c == null) return;
        c.stop();
        c.setFramePosition(0);
        c.start();
    }

    private static void stop(Clip c) {
        if (c != null && c.isRunning()) c.stop();
    }

    private static void closeClip(Clip c) {
        if (c == null) return;
        try { c.stop(); c.close(); } catch (RuntimeException ignored) {}
    }

    // -- Sample loading -----------------------------------------------------------

    private Clip makeClip(Sample s) {
        if (s == null || s.pcm() == null || s.pcm().length == 0) return null;
        try {
            Clip clip = AudioSystem.getClip();
            clip.open(s.fmt(), s.pcm(), 0, s.pcm().length);
            applyGain(clip);
            return clip;
        } catch (Exception e) {
            return null;
        }
    }

    private void applyGain(Clip clip) {
        if (clip == null) return;
        try {
            if (clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                FloatControl g = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
                g.setValue(clipsMuted ? g.getMinimum()
                        : Math.max(g.getMinimum(), Math.min(g.getMaximum(), gainDb)));
            }
        } catch (RuntimeException ignored) {}
    }

    /** Reapplies the current gain to every open clip. */
    private void applyGainAll() {
        applyGain(startupClip);
        applyGain(snatchClip);
        applyGain(spinClip);
        if (spinNdClip != spinClip) applyGain(spinNdClip);
        if (clickPool != null) for (Clip c : clickPool) applyGain(c);
    }

    /**
     * Loads a WAV from {@link #SAMPLE_DIR} and normalises it to signed 16-bit mono
     * little-endian PCM KEEPING the native sample rate. The result is cached. {@code null} if
     * the file is missing or cannot be decoded.
     */
    private static synchronized Sample loadSample(String name) {
        if (SAMPLE_CACHE.containsKey(name)) return SAMPLE_CACHE.get(name);
        Sample result = null;
        Path path = SAMPLE_DIR.resolve(name + ".wav");
        try (InputStream res = Files.isReadable(path) ? Files.newInputStream(path) : null) {
            if (res != null) {
                try (AudioInputStream in = AudioSystem.getAudioInputStream(new BufferedInputStream(res))) {
                    AudioFormat src = in.getFormat();
                    // Target: same rate, but encoding/channels normalised (identity if already conforming).
                    AudioFormat target = new AudioFormat(src.getSampleRate(), 16, 1, true, false);
                    AudioInputStream conv = src.matches(target) ? in
                            : AudioSystem.getAudioInputStream(target, in);
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = conv.read(buf)) > 0) bos.write(buf, 0, n);
                    result = new Sample(target, bos.toByteArray());
                }
            }
        } catch (Exception e) {
            result = null;
        }
        SAMPLE_CACHE.put(name, result);
        return result;
    }

    /** A sample as signed 16-bit mono values at its native rate, for the software mix. */
    private record Pcm(short[] data, int rate) {
        static Pcm of(Sample s) {
            if (s == null || s.pcm() == null || s.pcm().length < 2 * FRAME_BYTES) return null;
            byte[] b = s.pcm();
            short[] d = new short[b.length / FRAME_BYTES];
            for (int i = 0; i < d.length; i++) {
                d[i] = (short) ((b[i * 2] & 0xFF) | (b[i * 2 + 1] << 8));
            }
            return new Pcm(d, Math.round(s.fmt().getSampleRate()));
        }
    }

    /** One sample played by the software mix, resampled linearly to the output rate. */
    private static final class Voice {
        final Pcm pcm;
        final boolean loop;
        double pos;

        Voice(Pcm pcm, boolean loop) {
            this.pcm = pcm;
            this.loop = loop;
        }

        boolean done() {
            return !loop && pos >= pcm.data.length - 1;
        }

        float next(int outRate) {
            short[] d = pcm.data;
            int i = (int) pos;
            double frac = pos - i;
            int j = i + 1 < d.length ? i + 1 : (loop ? 0 : i);
            float v = (float) (d[i] + (d[j] - d[i]) * frac);
            pos += (double) pcm.rate / outRate;
            if (loop && pos >= d.length) pos -= d.length;
            return v;
        }
    }

    /**
     * Extracts a single "click" from the WAV (which holds many, one per track), taking a
     * window of {@link #CLICK_FRAMES} frames centred on the amplitude peak. Only one is needed
     * (a purely cosmetic rendition).
     */
    private static Sample extractClick(Sample s) {
        if (s == null || s.pcm() == null || s.pcm().length < FRAME_BYTES) return null;
        byte[] pcm = s.pcm();
        int frames = pcm.length / FRAME_BYTES;
        int peak = 0, peakIdx = 0;
        for (int f = 0; f < frames; f++) {
            int lo = pcm[f * 2] & 0xFF;
            int hi = pcm[f * 2 + 1];
            int sm = (hi << 8) | lo;    // sample firmato little-endian
            int a = Math.abs(sm);
            if (a > peak) { peak = a; peakIdx = f; }
        }
        int start = Math.max(0, peakIdx - 128);
        int len = Math.min(CLICK_FRAMES, frames - start);
        if (len <= 0) return null;
        byte[] out = new byte[len * FRAME_BYTES];
        System.arraycopy(pcm, start * FRAME_BYTES, out, 0, out.length);
        return new Sample(s.fmt(), out);
    }
}
