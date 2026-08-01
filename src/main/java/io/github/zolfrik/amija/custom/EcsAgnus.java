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
 * Agnus ECS (8372 "Fat" / 8375 "Fatter") — Enhanced Chip Set.
 *
 * <p>Ridefinisce gli hook di {@link AbstractAgnus} con i valori/comportamenti ECS.
 * Le differenze ECS implementate qui (o nella classe base, gated da {@link #isECS()}):
 * <ul>
 *   <li><b>A1 DDF mask</b>: {@link #ddfMask()} = {@code 0xFE} (granularità 2 CCK).</li>
 *   <li><b>A3 Agnus ID</b>: {@link #agnusIdBits()} = {@code 0x20}/{@code 0x30} (bit 13 = ECS).</li>
 *   <li><b>A4 BEAMCON0</b>: {@link #beamcon0Writable()} = {@code true} (registro scrivibile;
 *       la programmazione fine del beam — bit 5 PAL/NTSC, VARBEAMEN — è rinviata alla Fase 7).</li>
 *   <li><b>A5 Sprite V9</b>: gestito in {@code AbstractAgnus.setSPRxCTL} (gate {@code isECS()}).</li>
 *   <li><b>A6 DIWHIGH</b>: gestito in {@code AbstractAgnus.writeDIWHIGH} (gate {@code isECS()}).</li>
 *   <li><b>A8 REFPTR</b>: {@link #refptrMask()} estende il campo (l'ECS espone più bit del
 *       puntatore di refresh — rif. WinUAE {@code custom.cpp:2806-2824}). È solo questione di
 *       fedeltà in lettura del registro $028: nel nostro modello REFPTR non ha effetto
 *       funzionale (gli slot di refresh sono fissi a CCK 0/2/4/6).</li>
 * </ul>
 * <b>A7 maxvpos programmabile</b> (lunghezza frame da VTOTAL/BEAMCON0) NON è implementato:
 * richiede l'infrastruttura di timing programmabile (VTOTAL + interazione con MasterClock)
 * ed è ad alto rischio per il loop core, senza contenuti ECS per validarlo. {@code maxVpos}
 * resta quindi fisso (262 NTSC / 312 PAL) come su OCS.
 */
public final class EcsAgnus extends AbstractAgnus {

    /** ID Agnus ECS in VPOSR bit 14-8: bit 13 (0x20) = ECS; bit 4 (0x10) = NTSC. */
    private static final int ID_ECS_PAL  = 0x20;
    private static final int ID_ECS_NTSC = 0x30;

    public EcsAgnus(Motorola68000 cpu,
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

    @Override
    protected int refptrMask() {
        // A8 — su ECS il puntatore di refresh ha più bit dell'OCS (9 bit, 0x01FF).
        // Valore senza effetto funzionale (refresh a slot fissi): solo readback di $028.
        return 0x7FFF;
    }
}
