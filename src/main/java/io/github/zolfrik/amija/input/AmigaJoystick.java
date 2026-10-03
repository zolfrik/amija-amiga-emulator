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
 * State of the joystick plugged into an Amiga game port.
 *
 * The encoding of the JOYxDAT register for the joystick follows the OCS hardware scheme:
 *
 *   Bit  9 (Y1): left  active when 1
 *   Bit  1 (X1): right active when 1
 *   Bit  8 (Y0): up    active when Y0 XOR Y1 = 1
 *   Bit  0 (X0): down  active when X0 XOR X1 = 1
 *
 * These are the <b>two low bits</b> of the port's counters, not a separate register: the
 * joystick imposes them, the mouse turns them, and the 6 high bits are the same for both. See
 * {@link GamePort} and {@link #quadX()}.
 *
 * Ref: Amiga Hardware Reference Manual, JOY0DAT/JOY1DAT.
 */
public class AmigaJoystick {

    private int axisX = 0;  // -1 = sinistra, 0 = centro, +1 = destra
    private int axisY = 0;  // -1 = up,        0 = centre, +1 = down

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
     * 2-bit quadrature code of the X axis: bits 1:0 of JOYxDAT.
     *
     * <p>The joystick does not produce a JOYxDAT value: it produces only these two bits, which
     * {@link GamePort} plugs into the bottom of the port's counter. The signals are <b>active
     * low</b> ({@code right = 0} if the stick is to the right):
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
     * 2-bit quadrature code of the Y axis: bits 9:8 of JOYxDAT.
     * {@code b8 = (top ^ left) ? 1 : 0; b9 = (left ^ 1) ? 2 : 0; cnty = b8 | b9;}
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
