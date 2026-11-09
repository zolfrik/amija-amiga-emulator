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

/**
 * How many audio samples to send the MiSTer with each blit.
 *
 * <p>The MiSTer plays the samples from a FIFO with its own audio clock and never says how
 * full that FIFO is. Its audio and video clocks come from the same oscillator, so the time
 * it has played is known from where its video beam is: this class keeps a model of the
 * FIFO, adding what is sent and taking away the sample rate times the frames the beam has
 * run, and tops it up to a fixed lead at every blit. The count therefore follows the MiSTer's
 * clock, not the host's, and the FIFO neither drains nor fills over a long session.
 *
 * <p>While the beam's position is unknown (the MiSTer is applying a new modeline) the host's
 * clock stands in for a moment: over a fraction of a second the two do not drift apart.
 *
 * <p>A model gone below zero means the FIFO ran dry (the host stalled): the MiSTer played
 * nothing while empty, so the model restarts from empty rather than owing the gap.
 */
final class MisterAudioPacing {

    /** Largest block per blit, in stereo samples (a quarter of the MiSTer's FIFO). */
    static final int MAX_BLOCK = 2048;

    private final int rate;
    private final double leadSamples;
    private boolean started;
    private double lastPos;
    private long lastNs;
    private double level;
    private long underruns;

    /**
     * @param rate   samples per second the MiSTer plays
     * @param leadMs how much audio the FIFO holds right after a block, in milliseconds
     */
    MisterAudioPacing(int rate, double leadMs) {
        if (rate <= 0 || leadMs <= 0) {
            throw new IllegalArgumentException("rate " + rate + ", lead " + leadMs);
        }
        this.rate = rate;
        this.leadSamples = rate * leadMs / 1000.0;
    }

    /** Forgets the FIFO: the next block starts it again from empty. */
    void reset() {
        started = false;
        level = 0;
    }

    /**
     * Samples to send now.
     *
     * @param framePos the MiSTer's position in frames, fractional, or {@code NaN} when it is
     *                 not known (the host's clock is used instead)
     * @param nowNs    {@link System#nanoTime()} now
     * @param periodNs duration of one of the MiSTer's frames
     */
    int due(double framePos, long nowNs, double periodNs) {
        if (!started || framePos < lastPos - 1) {
            started = true;
            level = 0;
        } else {
            double played = !Double.isNaN(framePos) && !Double.isNaN(lastPos)
                    ? (framePos - lastPos) * rate * periodNs / 1e9
                    : (nowNs - lastNs) * rate / 1e9;
            level -= played;
            if (level < 0) {
                underruns++;
                level = 0;
            }
        }
        lastPos = framePos;
        lastNs = nowNs;
        long want = Math.round(leadSamples - level);
        return (int) Math.max(0, Math.min(MAX_BLOCK, want));
    }

    /** As {@link #due(double, long, double)} with a known position. */
    int due(double framePos, double periodNs) {
        return due(framePos, System.nanoTime(), periodNs);
    }

    /** Records a block of {@code samples} handed to the MiSTer. */
    void sent(int samples) {
        level += samples;
    }

    /** Samples the model thinks the FIFO holds. */
    double level() {
        return level;
    }

    /** Times the model found the FIFO empty. */
    long underruns() {
        return underruns;
    }
}
