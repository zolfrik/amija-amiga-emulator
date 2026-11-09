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
 * Whatever carries the controls plugged into the MiSTer into the machine. It keeps the
 * protocol out of the emulation: this package knows the reports, the implementation knows
 * the game ports and the keyboard.
 *
 * <p>Both methods are called by the emulation thread at the frame boundary, except the
 * final {@link #releaseAll()} when the output is stopped, which comes from the thread that
 * stops it: implementations must tolerate that.
 */
public interface MisterInputTarget {

    /**
     * Applies the reported state.
     *
     * @param state a reused buffer, valid only during the call
     */
    void apply(MisterInputState state);

    /**
     * Releases everything held through the MiSTer: called when the session is lost or the
     * output stops, since no report will arrive any more to release it.
     */
    void releaseAll();
}
