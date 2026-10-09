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
import io.github.zolfrik.amija.cpu.Processor;
import io.github.zolfrik.amija.memory.RAM;
import io.github.zolfrik.amija.memory.ROM;

/**
 * ECS Agnus (8372 "Fat" / 8375 "Fatter"): Enhanced Chip Set.
 *
 * <p>Overrides the hooks of {@link AbstractAgnus} with the ECS values/behaviours. The ECS
 * differences implemented here (or in the base class, gated by {@link #isECS()}):
 * <ul>
 *   <li><b>A1 DDF mask</b>: {@link #ddfMask()} = {@code 0xFE} (2-CCK granularity).</li>
 *   <li><b>A3 Agnus ID</b>: {@link #agnusIdBits()} = {@code 0x20}/{@code 0x30} (bit 13 = ECS).</li>
 *   <li><b>A4 BEAMCON0</b>: {@link #beamcon0Writable()} = {@code true} (writable register; the
 *       fine beam programming, bit 5 PAL/NTSC, VARBEAMEN, is not implemented).</li>
 *   <li><b>A5 Sprite V9</b>: handled in {@code AbstractAgnus.setSPRxCTL} (gate {@code isECS()}).</li>
 *   <li><b>A6 DIWHIGH</b>: handled in {@code AbstractAgnus.writeDIWHIGH} (gate {@code isECS()}).</li>
 *   <li><b>A8 REFPTR</b>: the refresh address steps by one RAS row per refresh cycle and
 *       spans 1 MB ({@link #refreshStep()}, {@link #refreshMask()}); a bitplane request that
 *       lands on a refresh slot takes its pointer from it.</li>
 * </ul>
 * <b>A7 programmable maxvpos</b> (frame length from VTOTAL/BEAMCON0) is NOT implemented: it
 * needs the programmable timing infrastructure (VTOTAL + interaction with MasterClock) and is
 * high-risk for the core loop, with no ECS content to validate it. {@code maxVpos} therefore
 * stays fixed (262 NTSC / 312 PAL) as on OCS.
 */
public final class EcsAgnus extends AbstractAgnus {

    /** ID Agnus ECS in VPOSR bit 14-8: bit 13 (0x20) = ECS; bit 4 (0x10) = NTSC. */
    private static final int ID_ECS_PAL  = 0x20;
    private static final int ID_ECS_NTSC = 0x30;

    public EcsAgnus(Processor cpu,
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
    protected int ddfMask() {
        return 0xFE;
    }

    @Override
    protected int agnusIdBits() {
        return (mode == VideoMode.PAL) ? ID_ECS_PAL : ID_ECS_NTSC;
    }

    @Override
    protected boolean beamcon0Writable() {
        return true;
    }

    /**
     * On ECS the HARD limits of the fetch window can be switched off: BEAMCON0 VARBEAMEN
     * ($0080) or HARDDIS ($4000), or the UHRES ($0040) / SHRES ($0080) bits of BPLCON0.
     */
    @Override
    protected boolean ddfHardLimitsDisabled() {
        if ((beamcon0 & (BEAMCON0_VARBEAMEN | BEAMCON0_HARDDIS)) != 0) return true;
        int bplcon0 = denise != null ? denise.getBplcon0() : 0;
        return (bplcon0 & (BPLCON0_UHRES | BPLCON0_SHRES)) != 0;
    }

    /**
     * On ECS BEAMCON0 VARBEAMEN, VARVBEN or HARDDIS keep the vertical blank from forcing the
     * vertical window off on the last line of the field.
     */
    @Override
    protected boolean verticalWindowForceOffDisabled() {
        return (beamcon0 & (BEAMCON0_VARBEAMEN | BEAMCON0_VARVBEN | BEAMCON0_HARDDIS)) != 0;
    }

    private static final int BEAMCON0_VARBEAMEN = 0x0080;
    private static final int BEAMCON0_VARVBEN   = 0x1000;
    private static final int BEAMCON0_HARDDIS   = 0x4000;
    private static final int BPLCON0_UHRES      = 0x0040;
    private static final int BPLCON0_SHRES      = 0x0080;

    @Override
    protected int refreshStep() {
        return 0x200;
    }

    @Override
    protected int refreshMask() {
        return 0xFFFFF;
    }

    @Override
    protected int refptrFromRegister(int v) {
        int p = (v & 0xFFFE) | ((v & 0xF) << 16);
        if (chipRamBytes() > 0x100000 && (v & 0x10) != 0) {
            p |= 0x100000;
        }
        return p;
    }
}
