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
 * What can be <b>done</b> to a game port: move the mouse, press its buttons, push the joystick,
 * press its buttons.
 *
 * <p>It is the surface the input adapters drive, extracted from {@link GamePort} for remote
 * play: on the guest there is no game port to drive (there is no machine) and the same
 * gestures must end up in a packet instead of a register. {@code GamePort} implements it as it
 * was; the adapters noticed nothing.
 *
 * <p>Note on {@link #moveMouse}: the only <b>incremental</b> method of this interface, and not
 * by chance: the Amiga mouse is a pair of quadrature counters, not a position. That is also
 * why on the wire the movement travels as a cumulative counter and not as a delta: a lost
 * delta is lost forever, a counter recovers by itself at the next packet (plan section 4.3).
 */
public interface GamePortControls {

    /**
     * Declares <b>what is plugged into</b> the port: it decides whether the reads of
     * {@code JOYxDAT} and of the POT lines talk about a mouse, a joystick or nothing.
     *
     * <p>It is in this interface (and not only in {@link GamePort}) because in remote play the
     * port is filled by the <b>guest</b>, and its choice must reach the host's machine.
     * {@code RemoteInputSender} implements it by putting it in the packet;
     * {@code RemoteInputApplier} reads it back on the other side and puts it on the real port.
     *
     * <p>Implementations must <b>also</b> infer it from the gestures, as {@link GamePort} does:
     * a {@link #moveMouse} says mouse, a {@link #setJoystickAxes} says joystick. The two ways do
     * not contradict each other, because gestures can come only from the device declared; this
     * method makes true also the case in which no gestures arrive at all (empty port, or just
     * changed).
     */
    void setDevice(GamePortDevice device);

    void moveMouse(int dx, int dy);

    void pressMouseLeft(boolean pressed);

    void pressMouseRight(boolean pressed);

    void pressMouseMiddle(boolean pressed);

    /** @param x -1 left, 0 centre, +1 right; {@code y} likewise for up/down */
    void setJoystickAxes(int x, int y);

    void pressJoystickFire(boolean pressed);

    void pressJoystickButton2(boolean pressed);

    void pressJoystickButton3(boolean pressed);
}
