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
 * ⛔ Sono i <b>due bit bassi</b> dei contatori della porta, non un registro separato:
 * il joystick li impone, il mouse li fa girare, e i 6 bit alti sono gli stessi per
 * entrambi. Vedi {@link GamePort} e {@link #quadX()}.
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
     * Codice di quadratura a 2 bit dell'asse X — i bit 1:0 di JOYxDAT.
     *
     * <p>⛔ Il joystick non produce un valore di JOYxDAT: produce solo questi due bit, che
     * {@link GamePort} innesta in fondo al contatore della porta. Porta letterale di
     * {@code joymousecounter()} (WinUAE {@code inputdevice.cpp}), dove i segnali sono
     * <b>attivi bassi</b> ({@code right = 0} se la leva è a destra):
     * <pre>
     *   b0 = (bot ^ right) ? 1 : 0;    b1 = (right ^ 1) ? 2 : 0;    cntx = b0 | b1;
     * </pre>
     */
    public int quadX() {
        final int right = (axisX ==  1) ? 0 : 1;
        final int bot   = (axisY ==  1) ? 0 : 1;
        return ((bot ^ right) != 0 ? 1 : 0) | ((right ^ 1) != 0 ? 2 : 0);
    }

    /**
     * Codice di quadratura a 2 bit dell'asse Y — i bit 9:8 di JOYxDAT.
     * Oracolo: {@code b8 = (top ^ left) ? 1 : 0; b9 = (left ^ 1) ? 2 : 0; cnty = b8 | b9;}
     */
    public int quadY() {
        final int left = (axisX == -1) ? 0 : 1;
        final int top  = (axisY == -1) ? 0 : 1;
        return ((top ^ left) != 0 ? 1 : 0) | ((left ^ 1) != 0 ? 2 : 0);
    }

    public void reset() {
        axisX = axisY = 0;
        fireButton = button2 = button3 = false;
    }
}
