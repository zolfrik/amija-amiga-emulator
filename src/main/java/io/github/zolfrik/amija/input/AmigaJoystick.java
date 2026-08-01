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
 * Stato del joystick collegato a una game port Amiga.
 *
 * La codifica del registro JOYxDAT per il joystick segue lo schema hardware OCS:
 *
 *   Bit  9 (Y1): sinistra attiva quando 1
 *   Bit  1 (X1): destra   attiva quando 1
 *   Bit  8 (Y0): su        attivo quando Y0 XOR Y1 = 1
 *   Bit  0 (X0): giù       attivo quando X0 XOR X1 = 1
 *
 * Rif: Amiga Hardware Reference Manual, JOY0DAT/JOY1DAT.
 */
public class AmigaJoystick {

    private int axisX = 0;  // -1 = sinistra, 0 = centro, +1 = destra
    private int axisY = 0;  // -1 = su,        0 = centro, +1 = giù

    private boolean fireButton = false;
    private boolean button2    = false;
    private boolean button3    = false;

    public void setAxisX(int x)              { axisX = x; }
    public void setAxisY(int y)              { axisY = y; }
    public void setFireButton(boolean p)     { fireButton = p; }
    public void setButton2(boolean p)        { button2 = p; }
    public void setButton3(boolean p)        { button3 = p; }

    public boolean isFirePressed()    { return fireButton; }
    public boolean isButton2Pressed() { return button2;    }
    public boolean isButton3Pressed() { return button3;    }

    /**
     * Codifica la direzione nel formato JOYxDAT.
     *
     * Logica (identica a vAmiga Joystick::joydat):
     *   Sinistra: Y[1:0] = 11  (Y1=1 attivo, Y0=1 → XOR=0 nessun su/giù)
     *   Destra:   X[1:0] = 11  (X1=1 attivo, X0=1 → XOR=0 nessun su/giù)
     *   Su:       XOR Y0  →  se da solo Y0=1,Y1=0; se con sinistra Y0=0,Y1=1
     *   Giù:      XOR X0  →  logica analoga
     */
    public int joydat() {
        int result = 0;
        if (axisX == -1)      result |= 0x0300;  // sinistra
        else if (axisX == 1)  result |= 0x0003;  // destra
        if (axisY == -1)      result ^= 0x0100;  // su  (XOR Y0)
        else if (axisY == 1)  result ^= 0x0001;  // giù (XOR X0)
        return result;
    }

    public void reset() {
        axisX = axisY = 0;
        fireButton = button2 = button3 = false;
    }
}
