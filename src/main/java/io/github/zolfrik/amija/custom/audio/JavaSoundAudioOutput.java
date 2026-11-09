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

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;

public final class JavaSoundAudioOutput implements Runnable {

    /** Default output rate; {@link #setOutputRate} changes it for the whole chain. */
    public static final int OUTPUT_RATE = 44_100;
    /** Largest block {@link #render} accepts. */
    public static final int MAX_RENDER_FRAMES = 8192;
    // 512 samples, about 12 ms: the line holds between one and two of them.
    private static final int CHUNK_FRAMES = 512;
    private static final int LINE_BUFFER_FRAMES = CHUNK_FRAMES * 2;
    // Below this the ring is treated as empty (underrun); above it a short read is played.
    private static final int MIN_READ_FRAMES = CHUNK_FRAMES / 4;

    // The cushion of the ring (AudioRingBuffer.targetFrames) starts at its minimum and grows
    // by one step at each underrun that happens while the machine is emulating, at most one
    // step per RAISE_INTERVAL_NS: a host that cannot keep the short cushion filled gets a
    // longer one instead of a crackle every few seconds. Underruns during a pause, or in the
    // first seconds after the start (JIT warm-up) or after a resume, do not count. What has
    // been learned is kept for the rest of the session, across machine rebuilds.
    private static final long START_GRACE_NS = 5_000_000_000L;
    private static final long RESUME_GRACE_NS = 1_000_000_000L;
    private static final long RAISE_INTERVAL_NS = 2_000_000_000L;
    private static volatile int learnedTargetFrames = AudioRingBuffer.TARGET_MIN_FRAMES;

    // The machine's analogue chain, one per side (see AmigaAudioFilter): a single pole would
    // be darker than the real filter and have the wrong slope above the cut-off.
    private final AudioRingBuffer ringBuffer;
    private volatile int outputRate = OUTPUT_RATE;
    // Read side of the ring: the local line and an external consumer may take turns, so the
    // read, the filters and the scratch buffers are used under this lock.
    private final Object consumerLock = new Object();
    private AmigaAudioFilter filterLeft = new AmigaAudioFilter(AmigaAudioFilter.Model.A500, OUTPUT_RATE);
    private AmigaAudioFilter filterRight = new AmigaAudioFilter(AmigaAudioFilter.Model.A500, OUTPUT_RATE);
    private final float[] tmpL = new float[MAX_RENDER_FRAMES];
    private final float[] tmpR = new float[MAX_RENDER_FRAMES];

    private volatile boolean running;
    private volatile boolean ledFilterEnabled;
    private volatile boolean loggingEnabled = Boolean.getBoolean("amiga.audiolog"); // audio diagnostics (one line/sec on stdout); -Damiga.audiolog=true
    private Thread thread;
    private SourceDataLine line;

    private DoubleSupplier correctionSupplier;

    // True while the machine is emulating (not paused); underruns count only then.
    private volatile BooleanSupplier activeSupplier = () -> true;

    /** Tells the output whether the machine is emulating, so pauses are not taken for underruns. */
    public void setActiveSupplier(BooleanSupplier supplier) {
        this.activeSupplier = supplier != null ? supplier : () -> true;
    }

    /**
     * Whoever receives a copy of the PCM chunk just before it reaches the sound card.
     *
     * <p>The buffer is <b>reused</b>: the receiver consumes or copies it, it does not keep it.
     */
    public interface PcmTap {
        /** @param pcm interleaved stereo S16LE, {@code frames*4} bytes */
        void onChunk(byte[] pcm, int frames);
    }

    private volatile PcmTap pcmTap;

    /**
     * Another device that plays the audio with its own clock and pulls it through
     * {@link #render}. While it is active the local line plays silence and leaves the ring
     * alone; when it is not, the local line plays as usual.
     */
    public interface ExternalConsumer {
        boolean active();
    }

    private volatile ExternalConsumer externalConsumer;

    // The cushion learning of the local line, applied to the external consumer as well (see
    // the comment at learnedTargetFrames). Used under consumerLock.
    private long externalActiveSinceNs;
    private long externalGraceNs = START_GRACE_NS;
    private long externalLastRaiseNs;

    /** Installs (or removes, with {@code null}) the external consumer. */
    public void setExternalConsumer(ExternalConsumer consumer) {
        this.externalConsumer = consumer;
    }

    private boolean externalActive() {
        ExternalConsumer c = externalConsumer;
        return c != null && c.active();
    }

    /** Sample rate of the whole chain: the producer's resampler, the filters and the line. */
    public int outputRate() {
        return outputRate;
    }

    /**
     * Changes the sample rate of the chain. The ring is emptied, since what it holds was
     * made for the old rate; a running line is reopened at the new one.
     */
    public synchronized void setOutputRate(int hz) {
        if (hz <= 0) {
            throw new IllegalArgumentException("rate " + hz);
        }
        if (hz == outputRate) {
            return;
        }
        boolean wasRunning = running;
        stop();
        synchronized (consumerLock) {
            outputRate = hz;
            filterLeft = new AmigaAudioFilter(filterLeft.model(), hz);
            filterRight = new AmigaAudioFilter(filterRight.model(), hz);
            ringBuffer.clear();
        }
        if (wasRunning) {
            start();
        }
    }

    /**
     * Takes exactly {@code frames} samples from the ring for an external consumer, filtered
     * and converted as for the local line. A ring that cannot supply them all is faded to
     * silence and padded, so the consumer always gets the count it asked for.
     *
     * @param pcm receives interleaved stereo S16LE, {@code frames*4} bytes
     * @return the samples that came from the ring (less than {@code frames} on an underrun)
     */
    public int render(byte[] pcm, int frames) {
        if (frames < 0 || frames > MAX_RENDER_FRAMES || pcm.length < frames * 4) {
            throw new IllegalArgumentException("frames " + frames);
        }
        synchronized (consumerLock) {
            int target = ringBuffer.targetFrames();
            if (ringBuffer.available() > target * 4L) {
                ringBuffer.discardDownTo(target * 3L / 4);
            }
            int got = ringBuffer.read(tmpL, tmpR, frames);
            long nowNs = System.nanoTime();
            if (activeSupplier.getAsBoolean()) {
                if (externalActiveSinceNs == 0) externalActiveSinceNs = nowNs;
            } else if (externalActiveSinceNs != 0) {
                externalActiveSinceNs = 0;
                externalGraceNs = RESUME_GRACE_NS;
            }
            if (got < frames) {
                // Any shortfall is silence the consumer plays: the cushion grows as it does
                // for the local line.
                if (externalActiveSinceNs != 0 && nowNs - externalActiveSinceNs > externalGraceNs
                        && nowNs - externalLastRaiseNs > RAISE_INTERVAL_NS && ringBuffer.raiseTarget()) {
                    externalLastRaiseNs = nowNs;
                    learnedTargetFrames = ringBuffer.targetFrames();
                    System.out.printf("[AUDIO] Underrun: audio cushion lengthened to %d ms%n",
                            ringBuffer.targetFrames() * 1000 / outputRate);
                }
                fadeOut(got, frames);
            }
            convert(pcm, frames);
            return got;
        }
    }

    /** Ramps the {@code got} samples read down to zero and pads with silence up to {@code frames}. */
    private void fadeOut(int got, int frames) {
        float n = got > 0 ? got : 1.0f;
        for (int i = 0; i < got; i++) {
            float ramp = (n - i) / n;
            tmpL[i] *= ramp;
            tmpR[i] *= ramp;
        }
        for (int i = got; i < frames; i++) {
            tmpL[i] = 0.0f;
            tmpR[i] = 0.0f;
        }
    }

    /** Filters {@code frames} samples of the scratch buffers and writes them as S16LE. */
    private void convert(byte[] pcm, int frames) {
        boolean led = ledFilterEnabled;
        AmigaAudioFilter fl = filterLeft;
        AmigaAudioFilter fr = filterRight;
        for (int i = 0; i < frames; i++) {
            float l = fl.process(tmpL[i], led);
            float r = fr.process(tmpR[i], led);
            int li = (int) (clamp(l) * 32767.0f);
            int ri = (int) (clamp(r) * 32767.0f);
            int off = i * 4;
            pcm[off] = (byte) li;
            pcm[off + 1] = (byte) (li >>> 8);
            pcm[off + 2] = (byte) ri;
            pcm[off + 3] = (byte) (ri >>> 8);
        }
    }

    /**
     * Installs (or removes, with {@code null}) the tap of the PCM toward the network.
     *
     * <p>With the feature off the field is null and the cost on the audio path is a comparison.
     */
    public void setPcmTap(PcmTap tap) {
        this.pcmTap = tap;
    }

    public void setLoggingEnabled(boolean enabled) {
        loggingEnabled = enabled;
    }

    /** Optional source of the current ASR correction (diagnostics only). */
    public void setCorrectionSupplier(DoubleSupplier supplier) {
        this.correctionSupplier = supplier;
    }

    public JavaSoundAudioOutput(AudioRingBuffer ringBuffer) {
        this.ringBuffer = ringBuffer;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this, "amiga-audio");
        thread.setDaemon(true);
        thread.setPriority(Math.max(Thread.NORM_PRIORITY, Thread.MAX_PRIORITY - 1));
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
    }

    /** Selects the output stage of the emulated machine; the filters start from rest. */
    public void setFilterModel(AmigaAudioFilter.Model model) {
        synchronized (consumerLock) {
            if (filterLeft.model() == model) {
                return;
            }
            filterLeft = new AmigaAudioFilter(model, outputRate);
            filterRight = new AmigaAudioFilter(model, outputRate);
        }
    }

    public AmigaAudioFilter.Model filterModel() {
        return filterLeft.model();
    }

    public void setLedFilterEnabled(boolean enabled) {
        ledFilterEnabled = enabled;
    }

    @Override
    public void run() {
        int rate = outputRate;
        AudioFormat format = new AudioFormat(rate, 16, 2, true, false);
        byte[] pcm = new byte[CHUNK_FRAMES * 4];
        byte[] silence = new byte[CHUNK_FRAMES * 4];

        try {
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            line = (SourceDataLine) AudioSystem.getLine(info);
            line.open(format, LINE_BUFFER_FRAMES * 4);
            line.start();

            ringBuffer.setTargetFrames(learnedTargetFrames);

            // Priming: accumulate an initial cushion before draining the buffer. Kept a little
            // below the target level of the producer's ASR, so it is always reached.
            while (running && !externalActive()
                    && ringBuffer.available() < ringBuffer.targetFrames() * 3L / 4) {
                Thread.sleep(2);
            }

            // Diagnostic statistics (reset every second)
            long statLastNs = System.nanoTime();
            long statMinFill = Long.MAX_VALUE;
            long statMaxFill = 0;
            long statSumFill = 0;
            int  statChunks = 0;
            int  statUnderflows = 0;
            long statOverflowBase = ringBuffer.overflowCount();

            long activeSinceNs = 0;          // 0 = not emulating
            long graceNs = START_GRACE_NS;
            long lastRaiseNs = 0;

            while (running) {
                // Read only when the line has room for a whole chunk: it then still holds
                // about one, and the ring has had that long to refill since the last read.
                while (running && line.available() < pcm.length) {
                    Thread.sleep(1);
                }
                if (externalActive()) {
                    // Another device is the clock: the line keeps running on silence, ready
                    // to take over again, and the ring is left to that device.
                    activeSinceNs = 0;
                    graceNs = RESUME_GRACE_NS;
                    line.write(silence, 0, silence.length);
                    continue;
                }
                if (activeSupplier.getAsBoolean()) {
                    if (activeSinceNs == 0) activeSinceNs = System.nanoTime();
                } else if (activeSinceNs != 0) {
                    activeSinceNs = 0;
                    graceNs = RESUME_GRACE_NS;
                }
                int frames;
                synchronized (consumerLock) {
                    // A ring this full means the consumer was held up (the sound device
                    // starting, a stall of the host): everything beyond the cushion is pure
                    // latency, and draining it through the ASR would take seconds at a
                    // lowered pitch.
                    int target = ringBuffer.targetFrames();
                    if (ringBuffer.available() > target * 4L) {
                        ringBuffer.discardDownTo(target * 3L / 4);
                    }
                    long fillBefore = ringBuffer.available();
                    if (fillBefore < statMinFill) statMinFill = fillBefore;
                    if (fillBefore > statMaxFill) statMaxFill = fillBefore;
                    statSumFill += fillBefore;
                    statChunks++;

                    int got = ringBuffer.read(tmpL, tmpR, CHUNK_FRAMES);
                    // A short read is played as it is: the line still holds about a chunk,
                    // so a brief dip of the ring costs nothing audible. Only a nearly empty
                    // ring is an underrun.
                    frames = got;
                    if (got < MIN_READ_FRAMES) {
                        statUnderflows++;
                        long nowNs = System.nanoTime();
                        if (activeSinceNs != 0 && nowNs - activeSinceNs > graceNs
                                && nowNs - lastRaiseNs > RAISE_INTERVAL_NS && ringBuffer.raiseTarget()) {
                            lastRaiseNs = nowNs;
                            learnedTargetFrames = ringBuffer.targetFrames();
                            System.out.printf("[AUDIO] Underrun: audio cushion lengthened to %d ms%n",
                                    ringBuffer.targetFrames() * 1000 / rate);
                        }
                        // Underrun: instead of a hard cut or a DC hold (click), fade the
                        // available samples to zero and fill the rest with silence.
                        fadeOut(got, CHUNK_FRAMES);
                        frames = CHUNK_FRAMES;
                    }
                    convert(pcm, frames);
                }

                // Remote play (docs/remote-play-plan.md section 4.2): the chunk is already
                // converted and filtered, so the guest hears EXACTLY what the host hears. That
                // is why the tee is here and not elsewhere. It costs a comparison with null when
                // the feature is off, and is outside the emulation thread anyway.
                PcmTap tap = pcmTap;
                if (tap != null) {
                    tap.onChunk(pcm, frames);
                }

                line.write(pcm, 0, frames * 4);

                long now = System.nanoTime();
                if (loggingEnabled && now - statLastNs >= 1_000_000_000L) {
                    long overflows = ringBuffer.overflowCount() - statOverflowBase;
                    long avgFill = statChunks > 0 ? statSumFill / statChunks : 0;
                    double corr = correctionSupplier != null ? correctionSupplier.getAsDouble() : 0.0;
                    System.out.printf(
                        "[AUDIO] fill avg=%d min=%d max=%d (cap=%d) underrun=%d/s overflow=%d/s asr=%+.0fHz%n",
                        avgFill, statMinFill == Long.MAX_VALUE ? 0 : statMinFill, statMaxFill,
                        ringBuffer.capacity(), statUnderflows, overflows, corr);
                    statLastNs = now;
                    statMinFill = Long.MAX_VALUE;
                    statMaxFill = 0;
                    statSumFill = 0;
                    statChunks = 0;
                    statUnderflows = 0;
                    statOverflowBase = ringBuffer.overflowCount();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        } catch (LineUnavailableException | IllegalArgumentException e) {
            running = false;
        } finally {
            if (line != null) {
                line.flush();
                line.close();
                line = null;
            }
        }
    }

    private static float clamp(float v) {
        if (v < -1.0f) return -1.0f;
        if (v > 1.0f) return 1.0f;
        return v;
    }
}
