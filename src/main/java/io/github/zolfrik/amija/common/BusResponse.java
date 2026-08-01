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
 * Risposta del bus per un singolo ciclo del 68000.
 *
 * Convenzioni: - Il dispositivo alza {@code ack} (DTACK) quando il ciclo
 * corrente può completare. - {@code dataIn} è una WORD (16 bit). Per cicli
 * byte, la CPU userà UDS/LDS. - {@code ipl} (0..7) è il livello interrupt visto
 * sulla CPU; la CPU lo campiona tra istruzioni. - {@code berr} segnala Bus
 * Error durante il ciclo (vector 2 sul 68000).
 *
 * IACK (Interrupt Acknowledge): - {@code iackVectorProvided=true} +
 * {@code iackVectorNumber} => il device fornisce un vettore esplicito. -
 * {@code iackAutovector=true} => autovector (AVEC), la CPU calcolerà
 * 24+livello. - Se nessuno dei due è true e {@code berr==true} durante IACK =>
 * Spurious (vector 24).
 */
public final class BusResponse {

    /**
     * DTACK/ACK: true quando il ciclo può completare.
     */
    public boolean ack;

    /**
     * Dato in input (16 bit significativi).
     */
    public int dataIn;

    /**
     * Livello di interrupt sulle linee IPL (0..7), campionato a boundary.
     */
    public int ipl;

    /**
     * Bus Error (BERR) sul ciclo.
     */
    public boolean berr;

    // ======== Nuovi campi per IACK esplicito vs AVEC ========
    /**
     * True se durante IACK un device ha fornito un vettore esplicito.
     */
    public boolean iackVectorProvided;

    /**
     * Numero di vettore fornito (8 bit utili) quando
     * {@code iackVectorProvided==true}.
     */
    public int iackVectorNumber;

    /**
     * True se l’IACK è di tipo autovector (AVEC).
     */
    public boolean iackAutovector;

    /**
     * Bus Request (BR) dall'esterno (es. Agnus).
     */
    public boolean br;

    /**
     * Valid Peripheral Address (VPA) per cicli sincroni.
     */
    public boolean vpa;

    /**
     * Halt signal (bi-directional in hardware, here input).
     */
    public boolean halt;

    public BusResponse() {
        clearHandshake();
        ipl = 0;
        berr = false;
        iackVectorProvided = false;
        iackVectorNumber = 0;
        iackAutovector = false;
        vpa = false;
        halt = false;
    }

    /**
     * Resetta le linee di handshake legate al ciclo (DTACK e dato) e i flag
     * IACK.
     */
    public void clearHandshake() {
        ack = false;
        dataIn = 0;

        // IACK flags: di default sono falsi ad ogni nuovo ciclo
        iackVectorProvided = false;
        iackVectorNumber = 0;
        iackAutovector = false;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(96);
        sb.append("BusResponse{br=").append(br ? 1 : 0);
        sb.append(", ack=").append(ack ? 1 : 0);
        sb.append(", dataIn=").append(String.format("0x%04X", dataIn & 0xFFFF));
        sb.append(", IPL=").append(ipl);
        sb.append(", BERR=").append(berr ? 1 : 0);
        sb.append(", IACK: provided=").append(iackVectorProvided ? 1 : 0);
        sb.append(", vec=").append(iackVectorNumber & 0xFF);
        sb.append(", avec=").append(iackAutovector ? 1 : 0);
        sb.append(", halt=").append(halt ? 1 : 0);
        sb.append('}');
        return sb.toString();
    }
}
