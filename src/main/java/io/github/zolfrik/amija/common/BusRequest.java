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

public final class BusRequest {

    public int address;
    public int sizeBytes;
    public boolean read = true;
    public boolean as;
    public boolean uds;
    public boolean lds;
    public int dataOut;
    public int fc;
    public boolean instruction;
    public boolean iack;
    public boolean vma; // Valid Memory Access (for synchronous devices)
    public boolean halt; // Halt signal (bi-directional in hardware, here out)
    public boolean resetLine; // Reset signal (asserted by RESET instruction)
    public boolean eClock; // E-Clock signal (1/10 system clock)

    public boolean bg; // Bus Grant

    public void clear() {
        address = 0;
        sizeBytes = 0;
        read = true;
        as = false;
        uds = false;
        lds = false;
        dataOut = 0;
        fc = 0;
        vma = false;
        halt = false;
        resetLine = false;
        eClock = false;
    }

    public void setupWriteWord(int addr, int value, int fcCode) {
        this.address = addr & 0xFFFF_FFFE;
        this.sizeBytes = 2;
        this.read = false;
        this.as = true;
        this.uds = true;
        this.lds = true;
        this.dataOut = value & 0xFFFF;
        this.fc = fcCode & 0b111;
    }
}
