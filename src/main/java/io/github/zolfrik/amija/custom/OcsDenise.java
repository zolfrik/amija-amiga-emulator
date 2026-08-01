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
 * Denise OCS (8362) — Display Encoder dell'Amiga 500 base.
 *
 * <p>Tutto il comportamento vive in {@link AbstractDenise}. La variante OCS lascia il
 * flag {@code isECS} al suo default ({@code false}): DENISEID restituisce {@code 0xFFFF},
 * i registri di controllo (BPLCON0/2/3/4) e la palette non sono rileggibili, niente
 * super-hires né effetti BPLCON3 — esattamente il comportamento storico. Questa
 * sottoclasse è quindi un <i>no-op semantico</i> e fa da contraltare a {@code EcsDenise}
 * nella factory del chipset.
 */
public final class OcsDenise extends AbstractDenise {

    public OcsDenise() {
        super();
    }
}
