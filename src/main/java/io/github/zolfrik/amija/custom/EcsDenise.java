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
 *   <li><b>D1 DENISEID</b> ($07C) = {@code 0xFFFC} (the Kickstart detects ECS).</li>
 *   <li><b>D4 Readback</b>: BPLCON0/1/2/3/4 and the colour registers ($180-$1BE) are
 *       readable.</li>
 *   <li><b>D5 DIWHIGH-H</b>: the high H8 bits of HSTART/HSTOP (handled in
 *       {@code AbstractDenise.writeRegisterAtPixel}, case $1E4, gate {@code isECS}).</li>
 * </ul>
 *
 * <p><b>Not implemented yet</b> (they need ECS content for validation; see
 * {@code docs/ECS-Chipset-Implementation-Plan.md}):
 * <ul>
 *   <li><b>D2 Super-hires</b> (BPLCON0 bit 6 SHRES, 35 ns/pixel): needs doubling the width of
 *       the raw framebuffer ({@code RAW_LINE_PIXELS} is at hires resolution) and adapting
 *       VirtualCRT/display. A SHRES bit is ignored (rendered as hires) so OCS does not
 *       regress.</li>
 *   <li><b>D3/D6 BPLCON3 effects</b> (BRDRBLNK, BRDSPRT/sprites in the border, colour bank): the
 *       register is stored and readable, but the rendering effects are not applied, so the OCS
 *       border/sprite fixes (North &amp; South, Last Battle) do not regress.</li>
 * </ul>
 */
public final class EcsDenise extends AbstractDenise {

    public EcsDenise() {
        super();
        // DENISEID 0xFFFC -> isECS = true (attiva readback + DIWHIGH-H).
        setDeniseId(0xFFFC);
    }
}
