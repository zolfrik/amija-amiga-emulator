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

public final class LeakyIntegrator {

    private float state;
    private final float alpha;

    public LeakyIntegrator(double cutoffHz, int sampleRate) {
        double rc = 1.0 / (2.0 * Math.PI * cutoffHz);
        double dt = 1.0 / sampleRate;
        this.alpha = (float) (dt / (rc + dt));
    }

    public float process(float input) {
        state += alpha * (input - state);
        return state;
    }

    public void reset() {
        state = 0.0f;
    }
}
