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

import io.github.zolfrik.amija.common.MOS8520;
import io.github.zolfrik.amija.common.VideoMode;
import io.github.zolfrik.amija.cpu.Motorola68000;
import io.github.zolfrik.amija.memory.RAM;
import io.github.zolfrik.amija.memory.ROM;

/**
 * OCS Agnus (8371): Original Chip Set variant of the stock Amiga 500 (512 KB chip RAM).
 *
 * <p>All the behaviour lives in {@link AbstractAgnus}: its specialisation hooks already have
 * the OCS defaults, so this subclass is a <i>semantic no-op</i> (it overrides nothing). It
 * exists to give a concrete instantiable type and to be the counterpart of {@code EcsAgnus} in
 * the chipset factory.
 */
public final class OcsAgnus extends AbstractAgnus {

    public OcsAgnus(Motorola68000 cpu,
                    Gary gary,
                    RAM chipRAM,
                    RAM slowRAM,
                    ROM rom,
                    Paula paula,
                    AbstractDenise denise,
                    MOS8520 ciaA,
                    MOS8520 ciaB,
                    VideoMode mode) {
        super(cpu, gary, chipRAM, slowRAM, rom, paula, denise, ciaA, ciaB, mode);
    }
}
