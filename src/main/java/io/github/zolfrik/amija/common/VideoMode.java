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

package io.github.zolfrik.amija.common;

/**
 * Rappresenta la modalità video dell'Amiga (PAL o NTSC) e le relative frequenze di clock.
 * Le frequenze si riferiscono al Color Clock (CCK), che è il Master Clock / 4.
 */
public enum VideoMode {
    /**
     * PAL: Master Clock = 28.37516 MHz, CCK = 7.093790 MHz.
     * Display: 256 righe attive (×2 line-doubling = 512 px), 288 in overscan (576 px).
     */
    PAL(7093790, 512, 576),

    /**
     * NTSC: Master Clock = 28.63636 MHz, CCK = 7.159090 MHz.
     * Display: 200 righe attive (×2 line-doubling = 400 px), 240 in overscan (480 px).
     */
    NTSC(7159090, 400, 480);

    /**
     * Larghezza del viewport senza overscan: il timing ORIZZONTALE è identico in PAL e
     * NTSC (227 CCK / 454 SC per riga), quindi la larghezza non dipende dalla modalità.
     */
    public static final int NORMAL_WIDTH = 640;

    /** Larghezza del viewport con overscan (orizzontale identico PAL/NTSC). */
    public static final int OVERSCAN_WIDTH = 724;

    private final long frequency;
    private final double periodNs;
    private final int normalHeight;
    private final int overscanHeight;

    VideoMode(long frequency, int normalHeight, int overscanHeight) {
        this.frequency = frequency;
        this.periodNs = 1_000_000_000.0 / frequency;
        this.normalHeight = normalHeight;
        this.overscanHeight = overscanHeight;
    }

    /**
     * Ritorna la frequenza in Hz.
     */
    public long getFrequency() {
        return frequency;
    }

    /**
     * Ritorna il periodo di un ciclo in nanosecondi.
     */
    public double getPeriodNs() {
        return periodNs;
    }

    /**
     * Larghezza in pixel del viewport (canvas logico) per questa modalità.
     *
     * @param overscan se {@code true} include l'area di overscan.
     */
    public int displayWidth(boolean overscan) {
        return overscan ? OVERSCAN_WIDTH : NORMAL_WIDTH;
    }

    /**
     * Altezza in pixel del viewport (canvas logico) per questa modalità. NTSC è più
     * basso del PAL (meno righe attive): 400/480 px contro 512/576 px.
     *
     * @param overscan se {@code true} include l'area di overscan.
     */
    public int displayHeight(boolean overscan) {
        return overscan ? overscanHeight : normalHeight;
    }
}
