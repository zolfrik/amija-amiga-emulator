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

package io.github.zolfrik.amija.custom.audio;

/**
 * First-order RC filter (6 dB/octave low-pass), the building block of the whole analogue
 * audio chain of the Amiga.
 *
 * <p>The coefficient comes from a bilinear transform with <b>pre-warping</b> of the angular
 * frequency, i.e. {@code omega = 2*tan(pi*fc/fs)} instead of {@code 2*pi*fc/fs}. Without
 * pre-warping the effective cut-off frequency slips downward as it approaches Nyquist (at
 * 6.2 kHz on 44.1 kHz the error is already 7%) and the chain sounds darker than it should.
 */
public final class LeakyIntegrator {

    private float state;
    private final float alpha;

    public LeakyIntegrator(double cutoffHz, int sampleRate) {
        this.alpha = (float) calculateA0(cutoffHz, sampleRate);
    }

    /** Bilinear coefficient with pre-warping. */
    static double calculateA0(double cutoffHz, int sampleRate) {
        // Above Nyquist the bilinear formula blows up: the filter becomes an all-pass.
        if (cutoffHz >= sampleRate / 2.0) return 1.0;
        double omega = 2.0 * Math.PI * cutoffHz / sampleRate;
        omega = Math.tan(omega / 2.0) * 2.0;
        return 1.0 / (1.0 + 1.0 / omega);
    }

    public float process(float input) {
        state += alpha * (input - state);
        return state;
    }

    public void reset() {
        state = 0.0f;
    }
}
