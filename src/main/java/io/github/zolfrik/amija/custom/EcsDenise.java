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
 * ECS Denise (8373 "Super Denise"): Enhanced Chip Set.
 *
 * <p>Sets the chip in ECS mode (DENISEID {@code 0xFFFC}); as a consequence, through the
 * {@code isECS} gating already in {@link AbstractDenise}, these become active:
 * <ul>
 *   <li><b>DENISEID</b> ($07C) = {@code 0xFFFC} (the Kickstart detects ECS).</li>
 *   <li><b>DIWHIGH-H</b>: the high H8 bits of HSTART/HSTOP (handled in
 *       {@code AbstractDenise.writeRegisterAtPixel}, case $1E4, gate {@code isECS}).</li>
 *   <li><b>Super-hires</b> (BPLCON0 bit 6, 35 ns pixels) with at most two planes. The output
 *       stays at hires resolution: each output pixel is a pair of super-hires pixels whose
 *       four plane bits select one of sixteen colour registers, shown with the upper two bits
 *       of each gun.</li>
 *   <li><b>KILLEHB</b> (BPLCON2 bit 9).</li>
 * </ul>
 *
 * <p>BPLCON0-4 and the colour registers are write-only, as on OCS. The BPLCON3 border bits
 * (BRDRBLNK, BRDNTRAN) need ECSENA; with the full-overscan output the border keeps COLOR00
 * either way. Sprites over a super-hires line are drawn as on any other line.
 */
public final class EcsDenise extends AbstractDenise {

    public EcsDenise() {
        super();
        // DENISEID 0xFFFC -> isECS = true.
        setDeniseId(0xFFFC);
    }
}
