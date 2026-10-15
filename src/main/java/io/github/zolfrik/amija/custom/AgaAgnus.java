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

import io.github.zolfrik.amija.common.MOS8520;
import io.github.zolfrik.amija.common.VideoMode;
import io.github.zolfrik.amija.cpu.Processor;
import io.github.zolfrik.amija.memory.RAM;
import io.github.zolfrik.amija.memory.ROM;

/**
 * AGA Agnus ("Alice", 8374): every ECS feature plus the AGA ones.
 *
 * <ul>
 *   <li>Chip ID {@code 0x23} (PAL) / {@code 0x33} (NTSC) in VPOSR bits 14-8.</li>
 *   <li>REFPTR: the register bits are scattered over the refresh address, the refresh
 *       address does not step (CAS-before-RAS refresh) and covers 2 MB.</li>
 *   <li>FMODE and the wide bitplane and sprite fetch: see {@link AbstractAgnus#isAGA()}.</li>
 * </ul>
 */
public final class AgaAgnus extends EcsAgnus {

    private static final int ID_AGA_PAL  = 0x23;
    private static final int ID_AGA_NTSC = 0x33;

    public AgaAgnus(Processor cpu,
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

    @Override
    public boolean isAGA() {
        return true;
    }

    @Override
    protected int agnusIdBits() {
        return (mode == VideoMode.PAL) ? ID_AGA_PAL : ID_AGA_NTSC;
    }

    @Override
    protected int refreshStep() {
        return 0;
    }

    @Override
    protected int refreshMask() {
        return 0x1FFFFF;
    }

    @Override
    protected int refreshResetValue() {
        return 0x1FFFFE;
    }

    @Override
    protected int refptrFromRegister(int v) {
        int p = 0;
        if ((v & 0x0001) != 0) p |= 0x000080;
        if ((v & 0x0200) != 0) p |= 0x000400;
        if ((v & 0x0400) != 0) p |= 0x000800;
        if ((v & 0x0800) != 0) p |= 0x001000;
        if ((v & 0x1000) != 0) p |= 0x002000;
        if ((v & 0x2000) != 0) p |= 0x004000;
        if ((v & 0x4000) != 0) p |= 0x008000;
        if ((v & 0x8000) != 0) p |= 0x010000;
        return p << 1;
    }
}
