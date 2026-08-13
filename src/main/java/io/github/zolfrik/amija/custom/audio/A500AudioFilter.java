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
 * La catena analogica di un canale di uscita dell'A500 — un'istanza per lato.
 *
 * <p>Oracolo: WinUAE {@code audio.cpp}, funzione {@code filter()}, caso
 * {@code FILTER_MODEL_A500} (il default per un A500: {@code sound_filter_type = 0} con
 * {@code sound_filter = FILTER_SOUND_EMUL}). Due stadi distinti:
 *
 * <ul>
 *   <li>il filtro <b>fisso</b>, sempre in circuito: due RC in cascata a 6,2 kHz e 20 kHz.
 *       È quello che dà all'A500 il suo timbro;</li>
 *   <li>il filtro del <b>LED</b>, che il software inserisce dal bit 1 di CIA-A PRA: tre RC
 *       a 7 kHz in cascata, <b>dopo</b> il filtro fisso.</li>
 * </ul>
 *
 * <p>Le due catene girano <b>sempre</b> entrambe, come in WinUAE, dove {@code rc3..rc5}
 * sono aggiornati ad ogni campione e {@code led_filter_on} sceglie soltanto quale uscita
 * prendere. Farne girare una a intermittenza la lascerebbe con uno stato stantio, e ogni
 * accensione del filtro sarebbe un salto di continuità: un clic.
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
     * @param ledFilterOn se il filtro del LED è inserito (LED acceso)
     * @return il campione filtrato
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
