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

package io.github.zolfrik.amija.memory;

import java.util.Arrays;

/**
 * ROM (Read-Only Memory) for the Kickstart and other non-writable areas of the Amiga.
 *
 * CHARACTERISTICS
 *
 *   - Big-endian
 *   - Strict bounds check on every access.
 *   - Run-time writes silently IGNORED (as on the hardware: ROMs do not react to write
 *     pulses on the bus, the data is lost)
 *   - "Programming" operations (loadImage, load) always allowed: they are the loading of
 *     the ROM image when the emulator starts, not run-time CPU writes
 *
 * AMIGA KICKSTART ROM
 *
 * Kickstart 1.3 (256 KB):
 *   ROM rom = new ROM(256 * 1024);
 *   rom.loadImage(kickstart13bytes);  // 262144-byte array
 *   // Mapped by Gary at $FC0000-$FFFFFF and mirrored at $F80000-$FBFFFF
 *
 * Kickstart 2.x / 3.x (512 KB):
 *   ROM rom = new ROM(512 * 1024);
 *   rom.loadImage(kickstart3bytes);   // 524288-byte array
 *   // Mapped by Gary at $F80000-$FFFFFF
 *
 * NOTES ON WRITES
 *
 * On the hardware a write to the ROM area simply has no effect: the ROM chip is not wired
 * to the bus's /WE line, and the data written by the CPU is ignored. Agnus/Gary already
 * prevent most writes to the ROM, and writes during the overlay ($000000-$07FFFF with
 * OVL=1) are redirected to the Chip RAM by Gary itself, so in practice ROM.write*() should
 * never be called for CPU writes. If it is, the right behaviour is to ignore it silently.
 *
 * @author alessio.carotenuto
 */
public final class ROM {

    // =========================================================================
    // Internal state
    // =========================================================================

    /** Byte array holding the content of the ROM. */
    private final byte[] mem;

    /** Size of the ROM in bytes. */
    private final int size;


    /** Flag: the ROM holds valid data (after loadImage). */
    private boolean loaded = false;

    // =========================================================================
    // Costruttori
    // =========================================================================

    /**
     * Creates an empty ROM of the given size. All bytes are initialised to 0xFF (an
     * unprogrammed ROM).
     *
     * @param sizeBytes size in bytes (must be > 0)
     */
    public ROM(int sizeBytes) {
        if (sizeBytes <= 0)
            throw new IllegalArgumentException("ROM size must be > 0, got: " + sizeBytes);
        this.mem  = new byte[sizeBytes];
        this.size = sizeBytes;
        // Unprogrammed ROM = all 0xFF (like blank flash/EPROM)
        Arrays.fill(this.mem, (byte)0xFF);
    }

    /**
     * Creates the ROM loading an image directly. The array is copied internally.
     *
     * @param image byte array with the ROM image (must not be null or empty)
     */
    public ROM(byte[] image) {
        if (image == null || image.length == 0)
            throw new IllegalArgumentException("ROM image must not be null or empty");
        this.mem  = new byte[image.length];
        this.size = image.length;
        System.arraycopy(image, 0, this.mem, 0, image.length);
        this.loaded = true;
    }

    // =========================================================================
    // Image loading ("programming" operations)
    // =========================================================================

    /**
     * Loads a complete ROM image.
     *
     * This is the loading of the Kickstart ROM when the emulator starts, NOT a run-time CPU
     * write.
     *
     * @param src source array of size exactly {@code size}
     * @throws IllegalArgumentException if the size does not match
     */
    public void loadImage(byte[] src) {
        if (src == null)
            throw new NullPointerException("src must not be null");
        if (src.length != size)
            throw new IllegalArgumentException(
                    "ROM image size mismatch: expected " + size + " bytes, got " + src.length);
        System.arraycopy(src, 0, mem, 0, size);
        this.loaded = true;
    }

    /**
     * Loads a ROM image from a larger array, given offset and length. Useful when the image
     * is part of a larger file (e.g. with a header).
     *
     * @param src source array
     * @param off offset in the source array
     * @param len number of bytes to load (must be equal to {@code size})
     */
    public void loadImage(byte[] src, int off, int len) {
        if (src == null) throw new NullPointerException("src");
        if (len != size)
            throw new IllegalArgumentException(
                    "ROM image size mismatch: expected " + size + " bytes, got " + len);
        if (off < 0 || off + len > src.length)
            throw new IndexOutOfBoundsException("Invalid range: off=" + off + " len=" + len);
        System.arraycopy(src, off, mem, 0, len);
        this.loaded = true;
    }

    /**
     * Loads a block of bytes at a given position of the ROM. Used for partial updates or
     * patches of the ROM image.
     *
     * @param address destination address in the ROM
     * @param src     source array
     * @param off     offset in the source array
     * @param len     number of bytes
     */
    public void load(int address, byte[] src, int off, int len) {
        if (src == null) throw new NullPointerException("src");
        if (off < 0 || len < 0 || off + len > src.length)
            throw new IndexOutOfBoundsException(
                    "Invalid range: off=" + off + " len=" + len + " src.length=" + src.length);

        int startIdx = idx(address);
        if (startIdx + len <= size) {
            System.arraycopy(src, off, mem, startIdx, len);
        } else {
            for (int i = 0; i < len; i++) {
                mem[idx(address + i)] = src[off + i];
            }
        }
    }

    // =========================================================================
    // Read access
    // =========================================================================

    /**
     * Reads 1 byte (unsigned 0-255).
     *
     * @param address physical address (24 effective bits)
     * @return unsigned byte value
     */
    public int readByte(int address) {
        return mem[idx(address)] & 0xFF;
    }

    /**
     * Reads one 16-bit word, big-endian.
     *
     * The ROM's wrap gives the hardware mirror: a 256 KB ROM uses {@code address & 0x3FFFF},
     * so reads at $FC0000 and at $F80000 (mirror) give the same data.
     *
     * @param address physical address
     * @return unsigned 16-bit word value
     */
    public int readWord(int address) {
        int i0 = idx(address);
        int i1 = idx(address + 1);
        return ((mem[i0] & 0xFF) << 8) | (mem[i1] & 0xFF);
    }

    /**
     * Reads one 32-bit longword, big-endian.
     *
     * @param address physical address
     * @return 32-bit longword value
     */
    public int readLong(int address) {
        int i0 = idx(address);
        int i1 = idx(address + 1);
        int i2 = idx(address + 2);
        int i3 = idx(address + 3);
        return ((mem[i0] & 0xFF) << 24)
             | ((mem[i1] & 0xFF) << 16)
             | ((mem[i2] & 0xFF) << 8)
             |  (mem[i3] & 0xFF);
    }

    // =========================================================================
    // Write access (run time: always ignored)
    // =========================================================================

    /**
     * Byte write to ROM: silently ignored.
     *
     * ROMs are not wired to /WE: the write has no effect. No exception is thrown, so the
     * normal bus flow during the boot is not interrupted.
     */
    public void writeByte(int address, int value) {
    }

    /**
     * Word write to ROM: silently ignored.
     */
    public void writeWord(int address, int value) {
    }

    /**
     * Long write to ROM: silently ignored.
     */
    public void writeLong(int address, int value) {
    }

    // =========================================================================
    // Dump utilities
    // =========================================================================

    /**
     * Reads a block of bytes from the ROM into a new array.
     *
     * @param address source address
     * @param len     number of bytes
     * @return new array with the data read
     */
    public byte[] dumpBlock(int address, int len) {
        if (len < 0) throw new IllegalArgumentException("len must be >= 0");
        byte[] out = new byte[len];
        int startIdx = idx(address);
        if (startIdx + len <= size) {
            System.arraycopy(mem, startIdx, out, 0, len);
        } else {
            for (int i = 0; i < len; i++) {
                out[i] = mem[idx(address + i)];
            }
        }
        return out;
    }

    /**
     * Hex dump of a window of ROM.
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

    /**
     * Computes the checksum of the ROM image: the sum of all its longwords, ignoring the
     * overflow. Useful to check the integrity of the Kickstart: the sum of all the
     * longwords (the checksum longword included) must be 0xFFFFFFFF.
     *
     * @return 32-bit checksum (sum of all the longwords)
     */
    public long checksum() {
        long sum = 0;
        for (int i = 0; i + 3 < size; i += 4) {
            long word = ((long)(mem[i]   & 0xFF) << 24)
                      | ((long)(mem[i+1] & 0xFF) << 16)
                      | ((long)(mem[i+2] & 0xFF) << 8)
                      |  (long)(mem[i+3] & 0xFF);
            sum = (sum + word) & 0xFFFFFFFFL;
        }
        return sum;
    }

    /**
     * Checks whether the ROM image is valid from its checksum. A valid Amiga Kickstart ROM
     * has checksum = 0xFFFFFFFF.
     *
     * @return true if the checksum is 0xFFFFFFFF
     */
    public boolean isChecksumValid() {
        return checksum() == 0xFFFFFFFFL;
    }

    // =========================================================================
    // Accessori
    // =========================================================================

    /** Returns the size of the ROM in bytes. */
    public int size()            { return size; }


    /** Returns true if the ROM image has been loaded. */
    public boolean isLoaded()    { return loaded; }

    // =========================================================================
    // Internals
    // =========================================================================

    /**
     * Computes the effective index into {@code mem} for the given address, with the ROM's
     * hardware wrapping.
     *
     * @param address physical address
     * @return index in {@code mem[0..size-1]}
     */
    private int idx(int address) {
        int i = address % size;
        return (i < 0) ? i + size : i;
    }

}
