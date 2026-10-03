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
 * Bus response for a single 68000 cycle.
 *
 * Conventions: the device raises {@code ack} (DTACK) when the current cycle can complete;
 * {@code dataIn} is a WORD (16 bits), for byte cycles the CPU uses UDS/LDS; {@code ipl}
 * (0..7) is the interrupt level seen by the CPU, which samples it between instructions;
 * {@code berr} signals a Bus Error during the cycle (vector 2 on the 68000).
 *
 * IACK (Interrupt Acknowledge): {@code iackVectorProvided=true} + {@code iackVectorNumber}
 * means the device supplies an explicit vector; {@code iackAutovector=true} means autovector
 * (AVEC), the CPU computes 24+level. If neither is true and {@code berr==true} during IACK:
 * spurious (vector 24).
 */
public final class BusResponse {

    /**
     * DTACK/ACK: true when the cycle can complete.
     */
    public boolean ack;

    /**
     * Dato in input (16 bit significativi).
     */
    public int dataIn;

    /**
     * Interrupt level on the IPL lines (0..7), sampled at the boundary.
     */
    public int ipl;

    /**
     * Bus Error (BERR) on the cycle.
     */
    public boolean berr;

    // ======== Fields for explicit IACK vs AVEC ========
    /**
     * True if a device supplied an explicit vector during IACK.
     */
    public boolean iackVectorProvided;

    /**
     * Vector number supplied (8 useful bits) when {@code iackVectorProvided==true}.
     */
    public int iackVectorNumber;

    /**
     * True if the IACK is of the autovector type (AVEC).
     */
    public boolean iackAutovector;

    /**
     * Bus Request (BR) dall'esterno (es. Agnus).
     */
    public boolean br;

    /**
     * Valid Peripheral Address (VPA) for synchronous cycles.
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
     * Resets the handshake lines tied to the cycle (DTACK and data) and the IACK flags.
     */
    public void clearHandshake() {
        ack = false;
        dataIn = 0;

        // IACK flags: false by default at every new cycle
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
