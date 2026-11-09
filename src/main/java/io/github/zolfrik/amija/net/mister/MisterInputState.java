/*
 * AmiJa - a cycle-exact Amiga emulator in Java.
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

package io.github.zolfrik.amija.net.mister;

/**
 * The controls plugged into the MiSTer, as last reported: a reused buffer that
 * {@link MisterInputs#drain} fills once per frame for a {@link MisterInputTarget}.
 *
 * <p>Joysticks and keyboard are <b>states</b>; the mouse movement is the sum of the deltas
 * reported since the previous drain, in Amiga orientation (positive {@code mouseDy} = down).
 */
public final class MisterInputState {

    /** Joystick 1 and 2 of the MiSTer: {@code GroovyProtocol.JOY_*} bits. */
    public int joy1;
    public int joy2;
    /** Keys held, by USB HID usage: bit {@code u % 8} of byte {@code u / 8}. */
    public final byte[] keys = new byte[GroovyProtocol.INPUT_KEY_BYTES];
    /** Mouse movement since the previous drain, in mouse counts. */
    public int mouseDx;
    public int mouseDy;
    /** Mouse buttons held: {@code GroovyProtocol.PS2_LEFT/RIGHT/MIDDLE}. */
    public int mouseButtons;

    /** Whether HID usage {@code usage} is held. */
    public boolean keyHeld(int usage) {
        return (keys[usage >> 3] & (1 << (usage & 7))) != 0;
    }
}
