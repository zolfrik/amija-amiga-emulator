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
 * Filtro RC del primo ordine (passa-basso a 6 dB/ottava), il mattone con cui è
 * costruita tutta la catena audio analogica dell'Amiga.
 *
 * <p>Il coefficiente è calcolato come in WinUAE ({@code audio.cpp},
 * {@code rc_calculate_a0}): trasformata bilineare con <b>pre-warping</b> della
 * pulsazione, cioè {@code omega = 2*tan(pi*fc/fs)} invece di {@code 2*pi*fc/fs}.
 * Senza il pre-warping la frequenza di taglio effettiva scivola verso il basso
 * man mano che ci si avvicina a Nyquist — a 6,2 kHz su 44,1 kHz l'errore è già
 * del 7% — e la catena suona più cupa dell'oracolo.
 */
public final class LeakyIntegrator {

    private float state;
    private final float alpha;

    public LeakyIntegrator(double cutoffHz, int sampleRate) {
        this.alpha = (float) calculateA0(cutoffHz, sampleRate);
    }

    /** Oracolo: WinUAE {@code rc_calculate_a0}. */
    static double calculateA0(double cutoffHz, int sampleRate) {
        // Sopra Nyquist la formula bilineare esplode: il filtro diventa un passa-tutto.
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
