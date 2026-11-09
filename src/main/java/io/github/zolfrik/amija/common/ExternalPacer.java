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

package io.github.zolfrik.amija.common;

/**
 * An outside clock the emulation follows instead of the wall clock, such as a display that
 * runs at its own rate.
 *
 * <p>{@link MasterClock} asks for the speed once per batch of colour clocks and runs at
 * {@code speed} times the nominal rate; the value may change at any time. A pacer that is
 * not locked returns {@link #DETACHED} and the clock follows the wall clock as usual.
 */
public interface ExternalPacer {

    /** Returned by {@link #speed()} when the pacer has nothing to say. */
    double DETACHED = 0;

    /** Emulation speed relative to nominal, or {@link #DETACHED}. Called on the clock thread. */
    double speed();
}
