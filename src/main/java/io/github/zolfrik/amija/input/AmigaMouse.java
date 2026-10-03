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
 * State of the mouse plugged into an Amiga game port: <b>the buttons only</b>.
 *
 * <p>The quadrature counters are <b>not here</b>. They are Denise registers, one pair per
 * <i>port</i>, and {@link GamePort} holds them: they exist even when the port is empty, and
 * JOYTEST writes them anyway. The mouse does not own them, it moves them: every
 * {@code GamePort.moveMouse} adds its own movement to them.
 *
 * Convention: dx > 0 = right, dy > 0 = down (screen coordinates).
 */
public class AmigaMouse {

    // Button state
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
