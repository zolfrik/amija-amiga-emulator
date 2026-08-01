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
 * Denise ECS (8373 "Super Denise") — Enhanced Chip Set.
 *
 * <p>Imposta il chip in modalità ECS (DENISEID {@code 0xFFFC}); di conseguenza, tramite
 * il gating {@code isECS} già presente in {@link AbstractDenise}, diventano attivi:
 * <ul>
 *   <li><b>D1 DENISEID</b> ($07C) = {@code 0xFFFC} (il Kickstart rileva l'ECS).</li>
 *   <li><b>D4 Readback</b>: BPLCON0/1/2/3/4 e i registri colore ($180-$1BE) sono leggibili.</li>
 *   <li><b>D5 DIWHIGH-H</b>: i bit alti H8 di HSTART/HSTOP (gestiti in
 *       {@code AbstractDenise.writeRegisterAtPixel}, case $1E4, gate {@code isECS}).</li>
 * </ul>
 *
 * <p><b>Non ancora implementati</b> (richiedono contenuti ECS per la validazione — Fase 6;
 * vedi {@code docs/ECS-Chipset-Implementation-Plan.md}):
 * <ul>
 *   <li><b>D2 Super-hires</b> (BPLCON0 bit 6 SHRES, 35 ns/pixel): richiede di raddoppiare
 *       la larghezza del framebuffer raw ({@code RAW_LINE_PIXELS} è a risoluzione hires) e
 *       di adeguare VirtualCRT/display. Oggi un eventuale bit SHRES viene ignorato (reso
 *       come hires) per non regredire l'OCS.</li>
 *   <li><b>D3/D6 BPLCON3 effetti</b> (BRDRBLNK, BRDSPRT/sprite nel bordo, color bank):
 *       il registro è memorizzato e leggibile, ma gli effetti di rendering non sono
 *       applicati per non regredire i fix OCS su bordo/sprite (North &amp; South, Last Battle).</li>
 * </ul>
 */
public final class EcsDenise extends AbstractDenise {

    public EcsDenise() {
        super();
        // DENISEID 0xFFFC -> isECS = true (attiva readback + DIWHIGH-H).
        setDeniseId(0xFFFC);
    }
}
