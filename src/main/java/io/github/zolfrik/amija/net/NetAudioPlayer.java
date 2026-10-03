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

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/**
 * The guest's audio output ({@code docs/remote-play-plan.md} section 4.2).
 *
 * <h2>The trap this class exists to avoid</h2>
 * The samples that arrive have <b>already</b> been through the RC filter and, if on, the LED
 * filter: the host applied them before sending. Putting them through
 * {@code JavaSoundAudioOutput} again would filter them <b>twice</b>, and the result would not be
 * a little darker, it would be wrong. So they are written <b>straight</b> to a
 * {@code SourceDataLine}, and this class shares not one line with that one.
 *
 * <p>Corollary for the UI: on the guest the "LED filter" option has no effect. The host
 * decides it, and the {@code .tip} must say so (section 10).
 *
 * <h2>Clock drift, which over ten minutes is not a detail</h2>
 * Host and guest have two sound cards with different crystals: one produces more samples than
 * the other consumes. Over a few minutes the guest's buffer empties or fills until it breaks.
 * The existing ASR sits on the <i>producer</i> (Paula) and serves the host; the guest cannot
 * drive it.
 *
 * <p>Local remedy: watch its own fill level and, outside a dead band, <b>duplicate or skip one
 * sample in 1024</b>. That is 0.1%: below any threshold of perception, and enough to
 * compensate crystals that differ by much less. Correcting more would be audible; correcting
 * only outside the dead band avoids chasing measurement noise.
 *
 * <h2>Without a sound card nothing happens</h2>
 * As with {@code FloppySound}: if the line does not open, {@link #isActive()} is {@code false}
 * and every call is a no-op. A guest without audio must be able to play, and the tests run on
 * machines that have no sound card.
 */
public final class NetAudioPlayer implements AudioReceiver.PcmSink {

    /** Target fill: three blocks, ~70 ms. It covers the jitter without adding latency. */
    public static final int TARGET_FILL_FRAMES = AudioBlockHeader.FRAMES_PER_BLOCK * 3;

    /** Dead band around the target: inside it nothing is corrected. */
    public static final int DEADBAND_FRAMES = AudioBlockHeader.FRAMES_PER_BLOCK;

    private final int sampleRate;
    private final int capacityFrames;
    private final byte[] ring;
    private final Object lock = new Object();

    private SourceDataLine line;
    private volatile boolean active;
    private volatile boolean running;
    private Thread thread;

    private int head;          // write byte
    private int tail;          // read byte
    private int fillBytes;

    private long framesWritten;
    private long framesPlayed;
    private long underruns;
    private long overflows;
    private long framesDuplicated;
    private long framesDropped;

    public NetAudioPlayer(int sampleRate) {
        this.sampleRate = sampleRate;
        // Half a second of margin: enough to absorb a host pause or a scheduler hiccup, not so
        // much that latency can build up unnoticed.
        this.capacityFrames = sampleRate / 2;
        this.ring = new byte[capacityFrames * 4];
    }

    /**
     * Opens the audio line and starts playback.
     *
     * @return {@code false} if there is no usable sound card: not an error
     */
    public boolean start() {
        AudioFormat format = new AudioFormat(sampleRate, 16, 2, true, false);
        try {
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            line = (SourceDataLine) AudioSystem.getLine(info);
            line.open(format, AudioBlockHeader.FRAMES_PER_BLOCK * 4 * 8);
            line.start();
        } catch (LineUnavailableException | IllegalArgumentException e) {
            RemotePlaySettings.say("nessuna uscita audio disponibile: si gioca in silenzio"
                    + " (%s)", e.getMessage());
            line = null;
            return false;
        }
        active = true;
        running = true;
        thread = new Thread(this::playLoop, "amija-remoteplay-audio");
        thread.setDaemon(true);
        thread.setPriority(Math.max(Thread.NORM_PRIORITY, Thread.MAX_PRIORITY - 1));
        thread.start();
        return true;
    }

    public boolean isActive() {
        return active;
    }

    /** Samples currently queued: the telemetry the guest reports to the host (section 4.3). */
    public int fillFrames() {
        synchronized (lock) {
            return fillBytes / 4;
        }
    }

    /** Fill in milliseconds, for the {@code audioFillMs} field of the feedback. */
    public int fillMillis() {
        return (int) Math.min(65535L, (long) fillFrames() * 1000 / sampleRate);
    }

    @Override
    public void onPcm(byte[] pcm, int offset, int frames) {
        if (!active) {
            return;
        }
        int bytes = frames * 4;
        synchronized (lock) {
            if (fillBytes + bytes > ring.length) {
                // The consumer cannot keep up: drop the oldest, not the newest. Falling behind
                // is the only way to build latency, exactly what a game does not want.
                int excess = fillBytes + bytes - ring.length;
                tail = (tail + excess) % ring.length;
                fillBytes -= excess;
                overflows++;
            }
            int first = Math.min(bytes, ring.length - head);
            System.arraycopy(pcm, offset, ring, head, first);
            if (first < bytes) {
                System.arraycopy(pcm, offset + first, ring, 0, bytes - first);
            }
            head = (head + bytes) % ring.length;
            fillBytes += bytes;
            framesWritten += frames;
            lock.notifyAll();
        }
    }

    public void stop() {
        running = false;
        synchronized (lock) {
            lock.notifyAll();
        }
        if (thread != null) {
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (line != null) {
            line.flush();
            line.close();
            line = null;
        }
        active = false;
    }

    public long framesPlayed() {
        return framesPlayed;
    }

    public long underruns() {
        return underruns;
    }

    public long overflows() {
        return overflows;
    }

    /** Samples duplicated to follow a slower clock: the drift, measured. */
    public long framesDuplicated() {
        return framesDuplicated;
    }

    /** Samples skipped to follow a faster clock. */
    public long framesDropped() {
        return framesDropped;
    }

    // =====================================================================

    private void playLoop() {
        byte[] chunk = new byte[AudioBlockHeader.FRAMES_PER_BLOCK * 4];
        byte[] corrected = new byte[chunk.length + 4];
        // Priming: start with a cushion, or the first hiccup is an underrun.
        synchronized (lock) {
            long deadline = System.currentTimeMillis() + 2000;
            while (running && fillBytes < TARGET_FILL_FRAMES * 4
                    && System.currentTimeMillis() < deadline) {
                try {
                    lock.wait(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        while (running) {
            int frames;
            int correction;
            synchronized (lock) {
                while (running && fillBytes < chunk.length) {
                    try {
                        lock.wait(20);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (!running) {
                    return;
                }
                int fillFrames = fillBytes / 4;
                correction = driftCorrection(fillFrames);
                int first = Math.min(chunk.length, ring.length - tail);
                System.arraycopy(ring, tail, chunk, 0, first);
                if (first < chunk.length) {
                    System.arraycopy(ring, 0, chunk, first, chunk.length - first);
                }
                tail = (tail + chunk.length) % ring.length;
                fillBytes -= chunk.length;
                frames = chunk.length / 4;
            }

            int outFrames = applyCorrection(chunk, frames, correction, corrected);
            line.write(corrected, 0, outFrames * 4);
            framesPlayed += outFrames;
        }
    }

    /**
     * @return {@code +1} to duplicate a sample, {@code -1} to skip one, 0 inside the dead
     *         band
     */
    private int driftCorrection(int fillFrames) {
        if (fillFrames > TARGET_FILL_FRAMES + DEADBAND_FRAMES) {
            return -1;    // too much queued: our clock is slower, speed up
        }
        if (fillFrames < TARGET_FILL_FRAMES - DEADBAND_FRAMES) {
            return +1;    // queue thinning out: slow down
        }
        return 0;
    }

    /**
     * Duplicates or skips <b>one</b> sample in the middle of the block.
     *
     * <p>In the middle and not at the start: at the junction between two blocks the signal
     * already has a potential discontinuity, and adding the correction would make it more
     * audible.
     */
    private int applyCorrection(byte[] chunk, int frames, int correction, byte[] out) {
        if (correction == 0) {
            System.arraycopy(chunk, 0, out, 0, frames * 4);
            return frames;
        }
        int mid = frames / 2;
        if (correction > 0) {
            System.arraycopy(chunk, 0, out, 0, mid * 4);
            System.arraycopy(chunk, (mid - 1) * 4, out, mid * 4, 4);       // campione ripetuto
            System.arraycopy(chunk, mid * 4, out, (mid + 1) * 4, (frames - mid) * 4);
            framesDuplicated++;
            return frames + 1;
        }
        System.arraycopy(chunk, 0, out, 0, mid * 4);
        System.arraycopy(chunk, (mid + 1) * 4, out, mid * 4, (frames - mid - 1) * 4);
        framesDropped++;
        return frames - 1;
    }
}
