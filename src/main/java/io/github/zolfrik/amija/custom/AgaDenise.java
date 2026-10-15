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

package io.github.zolfrik.amija.custom;

/**
 * AGA Denise ("Lisa"): every ECS Denise feature plus the AGA ones (eight bitplanes, 256
 * colour registers of 24 bits, BPLCON4, wide bitplane and sprite data). DENISEID reads
 * {@code $00F8}.
 */
public final class AgaDenise extends AbstractDenise {

    public AgaDenise() {
        super();
        setDeniseId(0x00F8);
    }
}
