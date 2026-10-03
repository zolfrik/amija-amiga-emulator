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
 * The analogue chain of one A500 output channel: one instance per side.
 *
 * <p>The A500 filter model has two distinct stages:
 *
 * <ul>
 *   <li>the <b>fixed</b> filter, always in circuit: two cascaded RCs at 6.2 kHz and 20 kHz. It
 *       is what gives the A500 its timbre;</li>
 *   <li>the <b>LED</b> filter, which the software switches in from bit 1 of CIA-A PRA: three
 *       cascaded RCs at 7 kHz, <b>after</b> the fixed filter.</li>
 * </ul>
 *
 * <p>Both chains <b>always</b> run, and the LED state only chooses which output to take.
 * Running one intermittently would leave it with a stale state, and every switch of the
 * filter would be a discontinuity: a click.
 */
public final class A500AudioFilter {

    private static final double FIXED_CUTOFF_1 = 6_200.0;
    private static final double FIXED_CUTOFF_2 = 20_000.0;
    private static final double LED_CUTOFF     = 7_000.0;

    private final LeakyIntegrator fixed1;
    private final LeakyIntegrator fixed2;
    private final LeakyIntegrator led1;
    private final LeakyIntegrator led2;
    private final LeakyIntegrator led3;

    public A500AudioFilter(int sampleRate) {
        fixed1 = new LeakyIntegrator(FIXED_CUTOFF_1, sampleRate);
        fixed2 = new LeakyIntegrator(FIXED_CUTOFF_2, sampleRate);
        led1 = new LeakyIntegrator(LED_CUTOFF, sampleRate);
        led2 = new LeakyIntegrator(LED_CUTOFF, sampleRate);
        led3 = new LeakyIntegrator(LED_CUTOFF, sampleRate);
    }

    /**
     * @param ledFilterOn whether the LED filter is switched in (LED on)
     * @return the filtered sample
     */
    public float process(float input, boolean ledFilterOn) {
        float normal = fixed2.process(fixed1.process(input));
        float led = led3.process(led2.process(led1.process(normal)));
        return ledFilterOn ? led : normal;
    }

    public void reset() {
        fixed1.reset();
        fixed2.reset();
        led1.reset();
        led2.reset();
        led3.reset();
    }
}
