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
 * Stato del mouse collegato a una game port Amiga: <b>i soli pulsanti</b>.
 *
 * <p>⛔ I contatori di quadratura <b>non stanno qui</b>. Sono registri di Denise, una
 * coppia per <i>porta</i>, e li tiene {@link GamePort}: esistono anche quando la porta è
 * vuota, e JOYTEST li scrive comunque. Il mouse non li possiede, li muove — ogni
 * {@code GamePort.moveMouse} ci somma il proprio spostamento, come
 * {@code inputdevice_read} dell'oracolo somma i delta dell'host in
 * {@code mouse_x[]}/{@code mouse_y[]}.
 *
 * Convenzione: dx > 0 = destra, dy > 0 = giù (coordinate schermo).
 */
public class AmigaMouse {

    // Stato pulsanti
    private volatile boolean leftButton   = false;
    private volatile boolean rightButton  = false;
    private volatile boolean middleButton = false;

    public void setLeftButton(boolean pressed)   { leftButton   = pressed; }
    public void setRightButton(boolean pressed)  { rightButton  = pressed; }
    public void setMiddleButton(boolean pressed) { middleButton = pressed; }

    public boolean isLeftPressed()   { return leftButton;   }
    public boolean isRightPressed()  { return rightButton;  }
    public boolean isMiddlePressed() { return middleButton; }

    public void reset() {
        leftButton = rightButton = middleButton = false;
    }
}
