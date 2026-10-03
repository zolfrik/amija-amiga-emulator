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

package io.github.zolfrik.amija.custom;

/**
 * Zorro II expansion board taking part in the AutoConfig sequence.
 *
 * <p>At boot the Kickstart's {@code expansion.library} queries the configuration space
 * $E80000-$E8FFFF: the first board of the chain still <i>unconfigured</i> answers with its
 * descriptor, receives a base address and then withdraws (CONFIGURED or SHUTUP), letting the
 * next board answer. This models the hardware "daisy chain" in which a board's CONFIG_OUT
 * drives the next one's CONFIG_IN.
 *
 * <p>{@link Agnus} keeps an ordered chain of these boards (e.g. Fast RAM + hard disk
 * controller) and routes the accesses to $E8xxxx to the first one still waiting.
 * Implementations: {@link FastRam}, {@link HdController}.
 */
public interface AutoConfigDevice {

    /** {@code true} while the board answers in the AutoConfig space (UNCONFIGURED). */
    boolean autoconfiguring();

    /**
     * Read of a byte of the configuration descriptor ($E8xxxx space). Returns $FF when the
     * board is no longer in the AutoConfig phase.
     */
    int peekConfigByte(int addr);

    /**
     * Write to the AutoConfig space: the Kickstart assigns the base address ($48/$4A) or orders
     * the "shut up" ($4C).
     *
     * @return {@code true} if the write completed the configuration (base assigned): the caller
     *         must map the board at the address returned by {@link #configuredBaseAddr()}.
     */
    boolean pokeConfigByte(int addr, int value);

    /** Base address assigned by the Kickstart (valid after {@link #pokeConfigByte} = true). */
    int configuredBaseAddr();

    /** System reset: the board goes back to waiting for AutoConfig. */
    void reset();
}
