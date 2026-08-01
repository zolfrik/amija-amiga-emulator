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
 * Agnus OCS (8371) — variante Original Chip Set dell'Amiga 500 base (512 KB chip RAM).
 *
 * <p>Tutto il comportamento vive in {@link AbstractAgnus}: i suoi hook di
 * specializzazione hanno già i default OCS, quindi questa sottoclasse è un
 * <i>no-op semantico</i> (non ridefinisce nulla). Esiste per dare un tipo concreto
 * istanziabile e fare da contraltare a {@code EcsAgnus} nella factory del chipset.
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
