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

import java.util.function.DoubleSupplier;

public final class JavaSoundAudioOutput implements Runnable {

    public static final int OUTPUT_RATE = 44_100;
    private static final int CHUNK_FRAMES = 1024;
    private static final int LINE_BUFFER_FRAMES = CHUNK_FRAMES * 4;
    // Cushion to accumulate before starting to drain (priming): avoids starting dry, where the
    // producer's slightest jitter would cause an underrun. Kept a little below the target level
    // of the producer's ASR.
    private static final int PRIME_FRAMES = CHUNK_FRAMES * 3;

    // The A500's analogue chain, one per side (see A500AudioFilter): a single pole would be
    // darker than the real filter and have the wrong slope above the cut-off.
    private final AudioRingBuffer ringBuffer;
    private final A500AudioFilter filterLeft = new A500AudioFilter(OUTPUT_RATE);
    private final A500AudioFilter filterRight = new A500AudioFilter(OUTPUT_RATE);

    private volatile boolean running;
    private volatile boolean ledFilterEnabled;
    private volatile boolean loggingEnabled = Boolean.getBoolean("amiga.audiolog"); // audio diagnostics (one line/sec on stdout); -Damiga.audiolog=true
    private Thread thread;
    private SourceDataLine line;

    private DoubleSupplier correctionSupplier;

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

    public void setLedFilterEnabled(boolean enabled) {
        ledFilterEnabled = enabled;
    }

    @Override
    public void run() {
        AudioFormat format = new AudioFormat(OUTPUT_RATE, 16, 2, true, false);
        float[] tmpL = new float[CHUNK_FRAMES];
        float[] tmpR = new float[CHUNK_FRAMES];
        byte[] pcm = new byte[CHUNK_FRAMES * 4];

        try {
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            line = (SourceDataLine) AudioSystem.getLine(info);
            line.open(format, LINE_BUFFER_FRAMES * 4);
            line.start();

            // Priming: accumulate an initial cushion before draining the buffer.
            while (running && ringBuffer.available() < PRIME_FRAMES) {
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

            while (running) {
                long fillBefore = ringBuffer.available();
                if (fillBefore < statMinFill) statMinFill = fillBefore;
                if (fillBefore > statMaxFill) statMaxFill = fillBefore;
                statSumFill += fillBefore;
                statChunks++;

                int got = ringBuffer.read(tmpL, tmpR, CHUNK_FRAMES);
                if (got < CHUNK_FRAMES) {
                    statUnderflows++;
                    // Underrun: instead of a hard cut or a DC hold (click), fade the available
                    // samples to zero with a falling ramp and fill the rest with silence.
                    float n = got > 0 ? got : 1.0f;
                    for (int i = 0; i < got; i++) {
                        float ramp = (n - i) / n;
                        tmpL[i] *= ramp;
                        tmpR[i] *= ramp;
                    }
                    for (int i = got; i < CHUNK_FRAMES; i++) {
                        tmpL[i] = 0.0f;
                        tmpR[i] = 0.0f;
                    }
                }

                boolean led = ledFilterEnabled;
                for (int i = 0; i < CHUNK_FRAMES; i++) {
                    float l = filterLeft.process(tmpL[i], led);
                    float r = filterRight.process(tmpR[i], led);
                    int li = (int) (clamp(l) * 32767.0f);
                    int ri = (int) (clamp(r) * 32767.0f);
                    int off = i * 4;
                    pcm[off] = (byte) li;
                    pcm[off + 1] = (byte) (li >>> 8);
                    pcm[off + 2] = (byte) ri;
                    pcm[off + 3] = (byte) (ri >>> 8);
                }

                // Remote play (docs/remote-play-plan.md section 4.2): the chunk is already
                // converted and filtered, so the guest hears EXACTLY what the host hears. That
                // is why the tee is here and not elsewhere. It costs a comparison with null when
                // the feature is off, and is outside the emulation thread anyway.
                PcmTap tap = pcmTap;
                if (tap != null) {
                    tap.onChunk(pcm, CHUNK_FRAMES);
                }

                line.write(pcm, 0, pcm.length);

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
