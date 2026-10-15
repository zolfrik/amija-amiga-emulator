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

package io.github.zolfrik.amija.cpu.event;

/**
 * A bus error. Thrown by the bus when an access fails and caught by the processor's
 * instruction loop, which aborts the instruction and takes the exception. Preallocated and
 * without a stack trace: a fault is rare, and the normal path pays nothing for it.
 */
public final class BusFault extends RuntimeException {
    public static final BusFault INSTANCE = new BusFault();

    private BusFault() {
        super(null, null, false, false);
    }
}
