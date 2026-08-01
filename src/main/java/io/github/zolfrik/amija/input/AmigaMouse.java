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

package io.github.zolfrik.amija.input;

/**
 * Stato del mouse collegato a una game port Amiga.
 *
 * Mantiene due contatori a 8 bit (X e Y) che incrementano/decrementano
 * seguendo il protocollo di quadratura dell'Amiga. L'OS legge JOYxDAT,
 * calcola il delta con segno tra letture consecutive e lo usa come
 * spostamento del cursore.
 *
 * Convenzione: dx > 0 = destra, dy > 0 = giù (coordinate schermo).
 */
public class AmigaMouse {

    // Contatori quadratura (8 bit wrapping): accumulano il movimento dal host
    private long counterX = 0;
    private long counterY = 0;

    // Stato pulsanti
    private volatile boolean leftButton   = false;
    private volatile boolean rightButton  = false;
    private volatile boolean middleButton = false;

    /**
     * Accumula il movimento del mouse host.
     *
     * @param dx spostamento orizzontale (pixel schermo host)
     * @param dy spostamento verticale   (pixel schermo host)
     */
    public synchronized void move(int dx, int dy) {
        counterX += dx;
        counterY += dy;
    }

    public void setLeftButton(boolean pressed)   { leftButton   = pressed; }
    public void setRightButton(boolean pressed)  { rightButton  = pressed; }
    public void setMiddleButton(boolean pressed) { middleButton = pressed; }

    public boolean isLeftPressed()   { return leftButton;   }
    public boolean isRightPressed()  { return rightButton;  }
    public boolean isMiddlePressed() { return middleButton; }

    /**
     * Valore del registro JOYxDAT.
     * Bit 15:8 = contatore Y (8 bit, segnali quadratura verticale).
     * Bit  7:0 = contatore X (8 bit, segnali quadratura orizzontale).
     */
    public synchronized int joydat() {
        return ((int) (counterY & 0xFF) << 8) | (int) (counterX & 0xFF);
    }

    /**
     * Preset del contatore da JOYTEST ($DFF036).
     * Sovrascrive i 6 bit alti di ciascun contatore preservando i 2 bit bassi
     * (i due bit bassi codificano lo stato di quadratura istantaneo).
     *
     * @param value valore scritto in JOYTEST (16 bit)
     */
    public synchronized void pokeJoytest(int value) {
        counterY = (counterY & 0x03L) | ((value >> 8) & 0xFC);
        counterX = (counterX & 0x03L) | (value & 0xFC);
    }

    public void reset() {
        counterX = counterY = 0;
        leftButton = rightButton = middleButton = false;
    }
}
