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

import io.github.zolfrik.amija.memory.RAM;

/**
 * Zorro II Fast RAM expansion with AutoConfig.
 *
 * <p>The stock Amiga 500 has no Fast RAM: fast memory comes from expansion boards on the
 * Zorro II bus (trapdoor slot or side bus). Unlike Chip/Slow RAM, Fast RAM is asynchronous
 * to the chip bus (no wait states, immediate DTACK) and is <b>not</b> accessible to the
 * custom chips (Agnus/Copper/Blitter): only the CPU sees it. Hence the name "Fast".
 *
 * <p>Why AutoConfig is needed: an expansion board has no fixed, wired address. At boot the
 * Kickstart (expansion.library) queries the configuration space $E80000-$E8FFFF, reads the
 * board's "descriptor" (type, size, manufacturer...), assigns it a base address (the first
 * Zorro II RAM board typically ends up at $200000) and, thanks to the {@code ERTF_MEMLIST}
 * flag, adds the memory to the system list (AddMemList). That is what lets programs and
 * WHDLoad find and use the Fast RAM. Without AutoConfig the RAM would exist on the bus but
 * the operating system would not know about it.
 *
 * <p>The configuration read protocol works by <b>nibbles</b>: each 1-byte register is read in
 * two accesses (high nibble and low nibble) on the top 4 bits of the data bus (D15-D12).
 * Every register except {@code er_Type} (offset 0) is returned <b>inverted</b> and the
 * operating system complements it again.
 *
 * <p>This implementation models a single RAM board, with its descriptor bytes and its
 * AutoConfig state machine.
 *
 * @author alessio.carotenuto
 */
public final class FastRam implements AutoConfigDevice {

    /** Base address of the Zorro II AutoConfig space (64 KB page). */
    public static final int AUTOCONFIG_BASE = 0xE80000;
    public static final int AUTOCONFIG_END  = 0xE8FFFF;

    /** Fast RAM sizes allowed (in bytes): powers of two from 512 KB to 8 MB. They are the
     *  only values that can be encoded in the "size bits" of the Zorro II descriptor. */
    private static final int[] VALID_SIZES = {
        512 * 1024, 1024 * 1024, 2 * 1024 * 1024, 4 * 1024 * 1024, 8 * 1024 * 1024
    };

    // Costanti AutoConfig (expansion/expansion.h).
    private static final int ERT_ZORROII  = 0xC0;
    private static final int ERTF_MEMLIST = 0x20;

    /** State of the board in the AutoConfig sequence. */
    public enum State {
        /** Waiting for configuration: answers in the $E8xxxx space. */
        UNCONFIGURED,
        /** Base address assigned by the Kickstart: the RAM is mapped and usable. */
        CONFIGURED,
        /** The board has been "shut up" by the software: it no longer answers. */
        SHUTUP
    }

    private final int sizeBytes;
    private final RAM  ram;

    private State state;
    private int   baseAddr;

    /** Low byte ($4A) of the base address, latched while waiting for the commit at $48. */
    private int   pendingLoByte;

    /**
     * Creates a Fast RAM board of the requested size (in bytes). The value is normalised to
     * the nearest valid Zorro II size (rounding down); 0 (or a value below 512 KB) means
     * "no Fast RAM".
     */
    public FastRam(int requestedBytes) {
        this.sizeBytes = normalizeBytes(requestedBytes);
        this.ram   = sizeBytes > 0 ? new RAM(sizeBytes, "Fast") : null;
        this.state = sizeBytes > 0 ? State.UNCONFIGURED : State.SHUTUP;
        this.baseAddr = 0;
    }

    /**
     * Normalises a size in bytes to the largest valid Zorro II Fast RAM size that does not
     * exceed it; returns 0 if below 512 KB.
     */
    public static int normalizeBytes(int bytes) {
        int result = 0;
        for (int s : VALID_SIZES) {
            if (bytes >= s) result = s;
        }
        return result;
    }

    // =========================================================================
    // State
    // =========================================================================

    /** {@code true} if there is Fast RAM (size &gt; 0). */
    public boolean present()      { return ram != null; }

    /** {@code true} if the board has received a base address and is mapped. */
    public boolean configured()   { return state == State.CONFIGURED; }

    /** {@code true} if the board still answers in the AutoConfig space. */
    public boolean autoconfiguring() { return state == State.UNCONFIGURED; }

    /** Base address assigned by the Kickstart (valid only if {@link #configured()}). */
    public int baseAddr()         { return baseAddr; }

    @Override
    public int configuredBaseAddr() { return baseAddr; }

    /** Size of the Fast RAM in bytes (0 if absent). */
    public int sizeBytes()        { return sizeBytes; }

    /** The underlying physical RAM (null if absent). */
    public RAM ram()              { return ram; }

    /** Number of 64 KB pages taken by the board. */
    public int pages()            { return sizeBytes / 0x10000; }

    /**
     * System reset: the board goes back to waiting for AutoConfig (as at power-on and after
     * the CPU's RESET instruction, which resets the Zorro bus). The RAM content is kept.
     */
    public void reset() {
        this.state    = present() ? State.UNCONFIGURED : State.SHUTUP;
        this.baseAddr = 0;
        this.pendingLoByte = 0;
    }

    // =========================================================================
    // Descrittore AutoConfig
    // =========================================================================

    /** Size bits encoded in {@code er_Type}. */
    private int sizeBits() {
        switch (pages()) {
            case 0x08: return 0b100; // 512 KB
            case 0x10: return 0b101; // 1 MB
            case 0x20: return 0b110; // 2 MB
            case 0x40: return 0b111; // 4 MB
            case 0x80: return 0b000; // 8 MB
            default:   return 0b000;
        }
    }

    /** Returns byte {@code offset} (0..15) of the configuration descriptor. */
    private int descriptorByte(int offset) {
        switch (offset) {
            case 0x0: return (ERT_ZORROII | ERTF_MEMLIST) | sizeBits();
            case 0x1: return 0x67;                       // product number
            case 0x2: return 0x80;                       // flags
            case 0x3: return 0x00;
            case 0x4: return (0x07B9 >> 8) & 0xFF;        // manufacturer hi
            case 0x5: return 0x07B9 & 0xFF;              // manufacturer low
            case 0x6: return (27182 >> 24) & 0xFF;       // serial
            case 0x7: return (27182 >> 16) & 0xFF;
            case 0x8: return (27182 >>  8) & 0xFF;
            case 0x9: return 27182 & 0xFF;
            case 0xA: return 0x00;                        // initDiagVec hi
            case 0xB: return 0x00;                        // initDiagVec low
            default:  return 0x00;
        }
    }

    /**
     * Read from the AutoConfig space ($E8xxxx). Returns a byte with the configuration nibble
     * in bits 7-4 (the CPU reads it on the high byte of the data bus). When the board is no
     * longer in the AutoConfig phase it returns $FF (floating bus: no board answers).
     */
    public int peekConfigByte(int addr) {
        if (state != State.UNCONFIGURED) return 0xFF;

        int offset = addr & 0xFF;
        int result = 0xFF;

        if ((offset & 1) == 0 && offset < 0x40) {
            int b = descriptorByte(offset >> 2);
            // offset&2 selects the nibble: high for offsets 0/4/8..., low for 2/6/A...
            int nibble = ((offset & 2) != 0) ? (b & 0x0F) : ((b >> 4) & 0x0F);
            // er_Type (offset 0/2) is read straight; every other register inverted.
            result = (offset < 4) ? (nibble << 4) : (~(nibble << 4) & 0xFF);
        } else if (offset == 0x40 || offset == 0x42) {
            result = 0x00; // interrupt pending register
        }
        return result & 0xFF;
    }

    /**
     * Write to the AutoConfig space ($E8xxxx). The Kickstart assigns the base address by
     * writing registers $48 (A23-A20) and $4A (A19-A16); register $4C tells the board to
     * "shut up".
     *
     * @return {@code true} if the write has just completed the configuration (base address
     *         assigned): the caller must map the Fast RAM.
     */
    public boolean pokeConfigByte(int addr, int value) {
        if (state != State.UNCONFIGURED) return false;

        // The base address (A23-A16) is made of two bytes: $4A carries A19-A16 in the high
        // nibble, $48 carries A23-A20 in the high nibble. The write to $48 completes the
        // configuration:
        //   base = ((hi) | (lo >> 4)) << 16
        switch (addr & 0xFF) {
            case 0x4A: // ec_BaseAddress, low part (A19-A16)
                pendingLoByte = value & 0xFF;
                return false;

            case 0x48: // ec_BaseAddress, parte alta (A23-A20): commit + attivazione
                baseAddr = ((value & 0xFF) | (pendingLoByte >> 4)) << 16;
                state = State.CONFIGURED;
                return true;

            case 0x4C: // ec_ShutUp
                state = State.SHUTUP;
                return false;

            default:
                return false;
        }
    }
}
