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

package io.github.zolfrik.amija.common;

/**
 * Custom chipset of the emulated Amiga. Decides which variant of the Agnus and Denise chips is
 * instantiated (see {@code AbstractAgnus}/{@code OcsAgnus}/{@code EcsAgnus} and the matching
 * Denise hierarchy) and the behaviour of the ECS-only registers.
 *
 * <ul>
 *   <li>{@link #OCS}: Original Chip Set: Agnus 8371 (512 KB chip RAM), Denise 8362. The stock
 *       Amiga 500.</li>
 *   <li>{@link #ECS}: Enhanced Chip Set: Agnus 8372 "Fat" (1 MB), Denise 8373 "Super Denise"
 *       (super-hires, BPLCON3, DIWHIGH, ...).</li>
 *   <li>{@link #AGA}: Advanced Graphics Architecture: Alice (2 MB, wide bitplane and sprite
 *       fetch through FMODE) and Lisa (8 planes, 256 colour registers of 24 bits). Every ECS
 *       feature is part of it.</li>
 * </ul>
 *
 * <p>Read from {@code amiga.properties} with the key {@code chipset} (default {@link #OCS}),
 * like {@code video.mode}. A change takes effect only after a restart (cold restart).
 */
public enum Chipset {
    OCS,
    ECS,
    AGA;

    /** {@code true} if this chipset is ECS (or newer). */
    public boolean isEcs() {
        return this != OCS;
    }

    /** {@code true} if this chipset is AGA. */
    public boolean isAga() {
        return this == AGA;
    }
}
