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

/**
 * Band-limited resampling of Paula's output: every step a channel takes is mixed into the
 * output as a band-limited step (BLEP) placed at the exact master cycle where it happened.
 *
 * <p>A channel's output is a staircase whose edges sit on the 7.09 MHz master clock. Sampling
 * it at the output rate needs a low-pass first, or everything above half the output rate
 * folds back into the audible band. Each edge is therefore recorded with its time and size,
 * and an output sample is the level the channel has now minus, for every recent edge, the part
 * of that edge the low-pass has not let through yet: {@code out = level - sum(delta *
 * residual[age])}, where {@code residual} is one minus the step response of the filter, so it
 * falls from 1 to 0 over the length of the kernel and edges older than that only count through
 * the level.
 *
 * <p>The filter is a Kaiser-windowed sinc with an 80 dB stop band: flat up to 0.40 of the output
 * rate, stopped from 0.50. It is linear phase, so the output lags by half the kernel, about
 * 0.6 ms. The kernel is tabulated per master cycle and read with linear interpolation, since
 * an output instant falls between two master cycles.
 *
 * <p>This is output state only: it changes nothing the emulated machine can observe.
 */
public final class BlepMixer {

    /** Master cycles per second the edge times are counted in. */
    public static final int MASTER_CLOCK = 7_093_790;

    private static final int CHANNELS = 4;
    /** Edges kept per channel; a power of two. An overflow folds the oldest into the level. */
    private static final int QUEUE = 1024;
    private static final double STOP_BAND_DB = 80.0;
    private static final double PASS_EDGE = 0.40;
    private static final double STOP_EDGE = 0.50;

    private final long[][] edgeTime = new long[CHANNELS][QUEUE];
    private final float[][] edgeDelta = new float[CHANNELS][QUEUE];
    /** Index of the next free entry; the oldest edge is at {@code head - count}. */
    private final int[] head = new int[CHANNELS];
    private final int[] count = new int[CHANNELS];
    /** Level of each channel after its last recorded edge. */
    private final float[] level = new float[CHANNELS];
    /** Time each channel's output has been accounted up to. */
    private final long[] cursor = new long[CHANNELS];
    /** Master cycles elapsed. */
    private long now;

    private int kernelRate;
    private float[] residual = new float[0];

    /** The emulation has run {@code cycles} more master cycles. */
    public void advance(int cycles) {
        now += cycles;
    }

    public long now() {
        return now;
    }

    /**
     * Opens a span of the channel's output at {@code start}. A level different from the last
     * recorded one changed at {@code start}, when the previous span ended.
     */
    public void beginSpan(int ch, long start, float currentLevel) {
        if (currentLevel != level[ch]) {
            record(ch, start, currentLevel);
        }
        cursor[ch] = start;
    }

    /** The channel outputs {@code lvl} for the next {@code cycles} master cycles. */
    public void hold(int ch, float lvl, long cycles) {
        if (lvl != level[ch]) {
            record(ch, cursor[ch], lvl);
        }
        cursor[ch] += cycles;
    }

    private void record(int ch, long time, float newLevel) {
        float delta = newLevel - level[ch];
        level[ch] = newLevel;
        int h = head[ch];
        edgeTime[ch][h] = time;
        edgeDelta[ch][h] = delta;
        head[ch] = (h + 1) & (QUEUE - 1);
        if (count[ch] < QUEUE) {
            count[ch]++;
        }
    }

    /**
     * Band-limited output of channel {@code ch} at {@code lag} master cycles before now (an
     * output instant falls between two master cycles), for an output rate of {@code rate} Hz.
     */
    public float output(int ch, int rate, double lag) {
        if (rate != kernelRate) {
            residual = buildResidual(rate);
            kernelRate = rate;
        }
        float[] r = residual;
        int last = r.length - 1;
        long[] times = edgeTime[ch];
        float[] deltas = edgeDelta[ch];
        int n = count[ch];
        int idx = head[ch];
        float sum = level[ch];
        int kept = 0;
        // Newest first: once an edge is older than the kernel, every earlier one is too.
        for (; kept < n; kept++) {
            idx = (idx - 1) & (QUEUE - 1);
            double age = (now - times[idx]) - lag;
            if (age >= last) {
                break;
            }
            float res;
            if (age <= 0) {
                res = 1.0f;
            } else {
                int i = (int) age;
                float f = (float) (age - i);
                res = r[i] + f * (r[i + 1] - r[i]);
            }
            sum -= deltas[idx] * res;
        }
        count[ch] = kept;
        return sum;
    }

    /** One minus the step response of the low-pass for {@code rate}, per master cycle of age. */
    static float[] buildResidual(int rate) {
        double fs = MASTER_CLOCK;
        double transition = 2 * Math.PI * (STOP_EDGE - PASS_EDGE) * rate / fs;
        int length = (int) Math.ceil((STOP_BAND_DB - 7.95) / (2.285 * transition)) | 1;
        double beta = 0.1102 * (STOP_BAND_DB - 8.7);
        double fc = (PASS_EDGE + STOP_EDGE) / 2 * rate / fs;
        double mid = (length - 1) / 2.0;
        double i0Beta = besselI0(beta);
        double[] h = new double[length];
        double total = 0;
        for (int n = 0; n < length; n++) {
            double x = n - mid;
            double sinc = x == 0 ? 2 * fc : Math.sin(2 * Math.PI * fc * x) / (Math.PI * x);
            double w = (n - mid) / mid;
            h[n] = sinc * besselI0(beta * Math.sqrt(1 - w * w)) / i0Beta;
            total += h[n];
        }
        float[] r = new float[length];
        double step = 0;
        for (int n = 0; n < length; n++) {
            step += h[n] / total;
            r[n] = (float) (1 - step);
        }
        r[length - 1] = 0;
        return r;
    }

    private static double besselI0(double x) {
        double sum = 1, term = 1, q = x * x / 4;
        for (int k = 1; k < 64; k++) {
            term *= q / ((double) k * k);
            sum += term;
            if (term < sum * 1e-17) {
                break;
            }
        }
        return sum;
    }
}
