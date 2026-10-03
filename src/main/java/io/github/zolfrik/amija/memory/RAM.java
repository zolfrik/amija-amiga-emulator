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

package io.github.zolfrik.amija.memory;

import java.util.Arrays;

/**
 * Generic RAM.
 *
 * A linear block of memory. Addressing, masking and wrap-around are handled outside, by the
 * system components.
 *
 * CHARACTERISTICS
 *
 *   - Big-endian
 *   - Word and long accesses do NOT check alignment.
 *   - Strict bounds check on every access.
 *
 * @author alessio.carotenuto
 */
public final class RAM {

    // =========================================================================
    // Internal state
    // =========================================================================

    /** Byte array that is the physical memory. */
    private final byte[] mem;

    /** Dimensione in byte. */
    private final int size;

    /**
     * Descriptive label for logging (e.g. "Chip", "Slow"). It does not affect the
     * behaviour.
     */
    private final String label;


    // =========================================================================
    // Costruttori
    // =========================================================================

    /**
     * Creates a RAM of {@code sizeBytes} with the default label.
     *
     * @param sizeBytes size in bytes (must be > 0)
     */
    public RAM(int sizeBytes) {
        this(sizeBytes, "RAM");
    }

    /**
     * Creates a RAM of {@code sizeBytes} with a custom label.
     *
     * @param sizeBytes size in bytes (must be > 0)
     * @param label     label for logging (e.g. "Chip", "Slow")
     */
    public RAM(int sizeBytes, String label) {
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("RAM size must be > 0, got: " + sizeBytes);
        }
        this.mem   = new byte[sizeBytes];
        this.size  = sizeBytes;
        this.label = (label != null && !label.isBlank()) ? label : "RAM";
    }

    // =========================================================================
    // Read access
    // =========================================================================

    /**
     * Reads 1 byte (unsigned value 0-255).
     *
     * @param address physical address (24 effective bits)
     * @return unsigned byte value
     */
    public int readByte(int address) {
        if (address < 0 || address >= size) throwOob(address);
        return mem[address] & 0xFF;
    }

    /**
     * Reads one 16-bit word, big-endian (MC68000).
     *
     * The address is not checked for alignment: that belongs to the 68000 and to Gary.
     * Unaligned accesses produce the bytes in natural big-endian order, which is the right
     * behaviour for byte operations on a 16-bit bus.
     *
     * @param address physical address of the high byte (A0=0, 68000 convention)
     * @return unsigned 16-bit word value
     */
    public int readWord(int address) {
        // A single bounds check equivalent to two idx() calls. Valid iff
        // 0 <= address <= size-2, so it throws iff address < 0 || address >= size-1 (covers
        // both address and address+1, with no risk of overflow, and right for odd addresses
        // too).
        if (address < 0 || address >= size - 1) throwOob(address);
        return ((mem[address] & 0xFF) << 8) | (mem[address + 1] & 0xFF);
    }

    /**
     * Reads one 32-bit longword, big-endian.
     *
     * @param address physical address of the most significant byte
     * @return 32-bit long value (can be read as unsigned through Integer.toUnsignedLong())
     */
    public int readLong(int address) {
        // Single bounds check equivalent to the four per-byte checks.
        // Valid iff 0 <= address <= size-4, so it throws iff address < 0 ||
        // address >= size-3 (covers address to address+3).
        if (address < 0 || address >= size - 3) throwOob(address);
        return ((mem[address]     & 0xFF) << 24)
             | ((mem[address + 1] & 0xFF) << 16)
             | ((mem[address + 2] & 0xFF) << 8)
             |  (mem[address + 3] & 0xFF);
    }

    // =========================================================================
    // Write access
    // =========================================================================

    /**
     * Writes 1 byte.
     *
     * @param address physical address
     * @param value   value (only bits 7-0 are written)
     */
    public void writeByte(int address, int value) {
        if (address < 0 || address >= size) throwOob(address);
        mem[address] = (byte)(value & 0xFF);
    }

    /**
     * Writes one 16-bit word, big-endian.
     *
     * @param address physical address of the high byte
     * @param value   16-bit value (only bits 15-0 are written)
     */
    public void writeWord(int address, int value) {
        // Single bounds check (see readWord).
        if (address < 0 || address >= size - 1) throwOob(address);
        mem[address]     = (byte)((value >>> 8) & 0xFF);
        mem[address + 1] = (byte)( value        & 0xFF);
    }

    /**
     * Writes one 32-bit longword, big-endian.
     *
     * @param address physical address of the most significant byte
     * @param value   32-bit value
     */
    public void writeLong(int address, int value) {
        // Single bounds check (see readLong).
        if (address < 0 || address >= size - 3) throwOob(address);
        mem[address]     = (byte)((value >>> 24) & 0xFF);
        mem[address + 1] = (byte)((value >>> 16) & 0xFF);
        mem[address + 2] = (byte)((value >>>  8) & 0xFF);
        mem[address + 3] = (byte)( value         & 0xFF);
    }

    // =========================================================================
    // Block operations
    // =========================================================================

    /**
     * Copies a block of bytes into the RAM (e.g. loading initial data).
     *
     * <p><b>There is no wrap-around.</b> The range must lie entirely inside the RAM: a block
     * that overflows is an error, not a block to wrap. The aliasing of Chip RAM is done by
     * Gary on the address, not by this class.
     *
     * @param address destination address in the RAM
     * @param src     source array
     * @param off     offset in the source array
     * @param len     number of bytes to copy
     */
    public void load(int address, byte[] src, int off, int len) {
        if (src == null)  throw new NullPointerException("src must not be null");
        if (off < 0 || len < 0 || off + len > src.length)
            throw new IndexOutOfBoundsException(
                    "Invalid range: off=" + off + " len=" + len + " src.length=" + src.length);
        checkBlock(address, len);
        System.arraycopy(src, off, mem, address, len);
    }

    /**
     * Copies a block of bytes from the RAM into an existing array
     * ({@code System.arraycopy}). Allocation-free variant of {@link #dumpBlock(int, int)},
     * for the hard disk driver's block transfers.
     *
     * @param address source address in the RAM
     * @param dst     destination array
     * @param dstOff  offset in the destination array
     * @param len     number of bytes to copy
     */
    public void copyTo(int address, byte[] dst, int dstOff, int len) {
        checkBlock(address, len);
        System.arraycopy(mem, address, dst, dstOff, len);
    }

    /**
     * Reads a block of bytes from the RAM into a new array.
     *
     * @param address source address in the RAM
     * @param len     number of bytes to read
     * @return new byte array with the data read
     */
    public byte[] dumpBlock(int address, int len) {
        if (len < 0) throw new IllegalArgumentException("len must be >= 0");
        checkBlock(address, len);
        byte[] out = new byte[len];
        System.arraycopy(mem, address, out, 0, len);
        return out;
    }

    /**
     * Fills the whole RAM with the given byte. Used for initialisation or memory tests.
     *
     * @param value fill byte (only bits 7-0)
     */
    public void fill(int value) {
        Arrays.fill(mem, (byte)(value & 0xFF));
    }

    /**
     * Clears the whole RAM (same as {@code fill(0)}). Called during the system reset.
     */
    public void clear() {
        Arrays.fill(mem, (byte)0);
    }

    /**
     * Fills the RAM with what dynamic RAM chips hold after power-on, before anything has
     * been written: stripes of all-zero and all-one cells whose layout follows how Agnus
     * multiplexes the address onto the rows and columns of the chips.
     *
     * <p>With an ECS Agnus the pattern repeats every 1024 bytes and flips every two
     * longwords; with an OCS Agnus rows and columns are swapped, and whole 256-byte blocks
     * alternate. Software that reads memory before writing it takes different paths on a
     * zeroed RAM: the Kickstart's slow RAM sizing reads INTENAR through the custom chip
     * mirror and, on RAM, sees this pattern.
     *
     * @param ecsAgnus {@code true} for the ECS layout, {@code false} for the OCS one
     */
    public void fillPowerOnPattern(boolean ecsAgnus) {
        if (ecsAgnus) {
            int fill = 0;
            for (int bank = 0; bank < size / 1024; bank++) {
                fill = ~fill;
                for (int row = bank * 1024; row < (bank + 1) * 1024; row += 4) {
                    writeLong(row, fill);
                    if ((row & 7) == 0) fill = ~fill;
                }
            }
        } else {
            int fill = 0;
            for (int bank = 0; bank < size / 256; bank++) {
                fill = ~fill & 0xFFFF;
                for (int row = bank * 256; row < (bank + 1) * 256; row += 2) {
                    writeWord(row, fill);
                }
            }
        }
    }

    // =========================================================================
    // Debug
    // =========================================================================

    /**
     * Returns a hex dump of a window of memory.
     *
     * Format: {@code XXXXXXXX: YY YY YY ... \n}
     *
     * @param address      start address
     * @param len          number of bytes
     * @param bytesPerLine bytes per output line
     * @return string with the hex dump
     */
    public String hexDump(int address, int len, int bytesPerLine) {
        if (len < 0 || bytesPerLine <= 0)
            throw new IllegalArgumentException("len>=0, bytesPerLine>0 required");

        StringBuilder sb = new StringBuilder(len * 3 + (len / bytesPerLine) * 12);
        for (int i = 0; i < len; i++) {
            if (i % bytesPerLine == 0) {
                if (i > 0) sb.append('\n');
                sb.append(String.format("%08X: ", address + i));
            }
            sb.append(String.format("%02X ", mem[idx(address + i)] & 0xFF));
        }
        return sb.toString();
    }

    // =========================================================================
    // Accessori
    // =========================================================================

    /** Returns the size of the RAM in bytes. */
    public int size()          { return size; }

    /** Returns the descriptive label of this instance. */
    public String getLabel()   { return label; }

    // =========================================================================
    // Internals
    // =========================================================================

    /**
     * Computes the effective index into {@code mem} for the given address.
     *
     * @param address relative offset (0..size-1)
     * @return valid index in {@code mem[0..size-1]}
     * @throws IndexOutOfBoundsException if the address is out of range
     */
    /**
     * Checks that the whole block {@code [address, address+len)} lies inside the RAM, with a
     * message that names the RAM and the address (unlike the one System.arraycopy would
     * give for the tail of the range).
     */
    private void checkBlock(int address, int len) {
        if (address < 0 || len < 0 || address > size - len) throwOob(address);
    }

    private int idx(int address) {
        if (address < 0 || address >= size) {
            throwOob(address);
        }
        return address;
    }

    /**
     * Isolated (cold) error path.
     *
     * Keeping the message construction (String.format) out of the access methods
     * (readByte/Word/Long, writeByte/Word/Long) keeps the fast path's bytecode compact and
     * inlinable by the JIT; this method is compiled as "cold" and does not slow down valid
     * accesses.
     */
    private void throwOob(int address) {
        throw new IndexOutOfBoundsException(
                String.format("[RAM:%s] Address $%08X out of bounds (size=$%08X)",
                        label, address, size));
    }
}
