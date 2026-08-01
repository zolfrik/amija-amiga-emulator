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
 * Chipset custom dell'Amiga emulato. Determina quale variante dei chip Agnus e
 * Denise viene istanziata (vedi {@code AbstractAgnus}/{@code OcsAgnus}/{@code EcsAgnus}
 * e l'analoga gerarchia di Denise) e il comportamento dei registri ECS-only.
 *
 * <ul>
 *   <li>{@link #OCS} — Original Chip Set: Agnus 8371 (512 KB chip RAM), Denise 8362.
 *       È il comportamento storico dell'emulatore (Amiga 500 base).</li>
 *   <li>{@link #ECS} — Enhanced Chip Set: Agnus 8372 "Fat" (1 MB), Denise 8373
 *       "Super Denise" (super-hires, BPLCON3, DIWHIGH, ...).</li>
 * </ul>
 *
 * <p>Letto da {@code amiga.properties} con la chiave {@code chipset} (default
 * {@link #OCS}), analogamente a {@code video.mode}. Il cambio ha effetto solo a
 * macchina riavviata (cold restart). L'enum è volutamente estendibile a una terza
 * voce {@code AGA} in futuro senza toccare la sorgente di verità.
 */
public enum Chipset {
    OCS,
    ECS;

    /** {@code true} se questo chipset è l'ECS (o più recente). */
    public boolean isEcs() {
        return this != OCS;
    }
}
