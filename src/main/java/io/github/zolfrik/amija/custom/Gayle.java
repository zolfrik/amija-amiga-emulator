/*
 * AmiJa - a cycle-exact Amiga emulator in Java.
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
 * Gayle, the A1200 gate array that replaces Gary: its registers as the motherboard exposes
 * them with no IDE drive and no PCMCIA card attached.
 *
 * <p>Two windows:
 * <ul>
 *   <li>{@code $D80000-$DBFFFF}: the IDE port and the PCMCIA control registers. Only
 *       addresses with A19 and A17 both set decode ({@code $DA0000-$DAFFFF},
 *       {@code $DB0000-$DBFFFF}); the rest reads 0. In each 64 KB page: IDE registers below
 *       {@code $4000}, then the card status ({@code $8000}), interrupt request
 *       ({@code $9000}), interrupt enable ({@code $A000}) and configuration
 *       ({@code $B000}) bytes.</li>
 *   <li>{@code $DD0000-$DEFFFF}: the ID register at offset {@code $1000} of each page.
 *       Every read returns one bit of the ID in bit 7, MSB first; any write rewinds it. The
 *       AGA part answers {@code $D1}.</li>
 * </ul>
 *
 * <p>With nothing attached Gayle never raises an interrupt: the request bits can only be
 * set by a card or a drive.
 */
public final class Gayle {

    /** The ID of the AGA Gayle, read one bit per access, MSB first. */
    private static final int ID = 0xD1;

    /** Status of an IDE port with no drive: busy and error clear, every other bit set. */
    private static final int IDE_STATUS_NO_DRIVE = 0x7F;

    /** Interrupt request bits that a write sets instead of acknowledging (RESET, BERR). */
    private static final int IRQ_WRITE_SET = 0x03;

    private int idCount;
    private int irq;
    private int intEnable;
    private int cs;
    private int csMask;
    private int cfg;

    /** /RESET: the interrupt registers are cleared, the card registers are kept. */
    public void reset() {
        irq = 0;
        intEnable = 0;
    }

    /** True for an address inside one of Gayle's two windows. */
    public static boolean decodes(int addr) {
        int page = (addr >>> 16) & 0xFF;
        return (page >= 0xD8 && page <= 0xDB) || page == 0xDD || page == 0xDE;
    }

    public int readByte(int addr) {
        addr &= 0xFFFFFF;
        if (addr >= 0xDD0000) {
            if ((addr & 0xFFFF) != 0x1000) return 0;
            int bit = (ID >>> (7 - (idCount & 7))) & 1;
            idCount++;
            return bit << 7;
        }
        if ((addr & 0xA0000) != 0xA0000) return 0;
        int reg = addr & 0xFFFF;
        switch (reg) {
            case 0x8000: return csMask | cs;
            case 0x9000: return irq;
            case 0xA000: return intEnable;
            case 0xB000: return cfg & 0x0F;
            default:
                if (reg >= 0x4000) return 0;
                return ((reg & ~0x2020) >>> 2) == 7 ? IDE_STATUS_NO_DRIVE : 0xFF;
        }
    }

    public void writeByte(int addr, int value) {
        addr &= 0xFFFFFF;
        value &= 0xFF;
        if (addr >= 0xDD0000) {
            idCount = 0;
            return;
        }
        if ((addr & 0xA0000) != 0xA0000) return;
        switch (addr & 0xFFFF) {
            case 0x8000 -> {
                csMask = value & ~0x03;
                cs = (cs & ~0x03) | (value & 0x03);
            }
            case 0x9000 -> irq = (irq & value) | (value & IRQ_WRITE_SET);
            case 0xA000 -> intEnable = value;
            case 0xB000 -> cfg = value;
            default -> { }
        }
    }

    public int readWord(int addr) {
        return (readByte(addr) << 8) | readByte(addr + 1);
    }
}
