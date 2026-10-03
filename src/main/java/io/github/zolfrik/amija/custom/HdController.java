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

import io.github.zolfrik.amija.memory.HardFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.function.BooleanSupplier;

/**
 * "Virtual" hard disk controller: a Zorro II board with AutoConfig and a boot ROM.
 *
 * <p>The board exposes a small <b>expansion ROM</b> ({@code exprom}, a 68k driver
 * {@code virtualhd.device}) that the Kickstart detects through AutoConfig (flag
 * {@code ERTF_DIAGVALID}) and installs as a boot device. The driver talks to no real hardware:
 * it communicates with this <i>host</i> emulator by writing "magic" values to some ports
 * mapped right after the ROM. The host (this code) performs the disk reads/writes, copying
 * bytes between the {@code .hdf} file and Amiga RAM ({@link Bus}).
 *
 * <p>The approach is deliberately <b>minimal and fast</b>: no SCSI/IDE bus emulation, the I/O
 * operations are {@code memcpy}. Enough to boot games installed on {@code .hdf} with a file
 * system in ROM (OFS/FFS), and WHDLoad.
 *
 * <p>Port protocol (offsets relative to {@code baseAddr + initDiagVec}, where
 * {@code EXPROM_SIZE} is the size of the ROM):
 * <ul>
 *   <li>read  {@code EXPROM_SIZE+0..6}: number of partitions / number of FS drivers / "disable
 *       autoboot" flag / shared folders (0);</li>
 *   <li>write {@code EXPROM_SIZE+0/+2}: high/low word of the pointer to a structure in Amiga
 *       RAM;</li>
 *   <li>write {@code EXPROM_SIZE+4}: command (0xFEDE=I/O, 0xFEDF=init partition, ...).</li>
 * </ul>
 */
public final class HdController implements AutoConfigDevice {

    // --- Costanti AutoConfig (expansion/expansion.h) ---
    private static final int ERT_ZORROII    = 0xC0;
    private static final int ERTF_DIAGVALID = 0x10;

    // --- Offsets in the IOStdReq (exec/io.h) ---
    private static final int IO_UNIT    = 24;
    private static final int IO_COMMAND = 28;
    private static final int IO_ERROR   = 31;
    private static final int IO_ACTUAL  = 32;
    private static final int IO_LENGTH  = 36;
    private static final int IO_DATA    = 40;
    private static final int IO_OFFSET  = 44;

    // --- Comandi trackdisk/exec rilevanti ---
    private static final int CMD_READ   = 2;
    private static final int CMD_WRITE  = 3;
    private static final int TD_FORMAT  = 11;
    private static final int IOERR_NOCMD     = 0xFD; // -3
    private static final int IOERR_BADADDRESS = 0xFB; // -5

    // --- ROM ---
    private static final byte[] EXPROM = loadExprom();
    private static final int EXPROM_SIZE = EXPROM.length;

    /** State of the board in the AutoConfig sequence. */
    private enum State { UNCONFIGURED, CONFIGURED, SHUTUP }

    /** Byte access to the Amiga RAM (chip/slow/fast), supplied by {@link Agnus}. */
    public interface Bus {
        int  readByte(int addr);
        void writeByte(int addr, int value);

        /**
         * Block-copies {@code len} bytes from {@code src[srcOff..]} into Amiga RAM at
         * {@code addr}. Implementations use {@code System.arraycopy} when the range falls in a
         * single bank; this default falls back on a byte-by-byte copy.
         */
        default void writeBlock(int addr, byte[] src, int srcOff, int len) {
            for (int i = 0; i < len; i++) writeByte(addr + i, src[srcOff + i] & 0xFF);
        }

        /** Bulk-copies {@code len} bytes from the Amiga RAM at {@code addr} into {@code dst[dstOff..]}. */
        default void readBlock(int addr, byte[] dst, int dstOff, int len) {
            for (int i = 0; i < len; i++) dst[dstOff + i] = (byte) readByte(addr + i);
        }
    }

    private final int objid;
    private final HardFile hdf;
    private final int partitionNameBase;     // DH numbering offset for chained boards
    private final byte[] rom;                 // copy of the ROM with the unique device name
    private final BooleanSupplier floppyInDf0; // if df0 holds a disk, disable autoboot
    private Bus bus;

    private State state;
    private int   baseAddr;
    private int   pointer;                     // pointer passed by the driver

    // Activity LED: instant (nanoTime) of the last disk read/write. Updated by the emulation
    // thread at every I/O command (rare events, no cost) and read once per frame by the
    // display thread: a simple time window keeps the LED lit long enough for a single burst
    // to be visible.
    // "Minimum on time" window of the LED after the last read/write. During a load the I/O
    // comes in bursts with micro-pauses between them (the CPU processes/decompresses between
    // one read and the next); too narrow a window makes the LED blink even while the disk is
    // "in use". 250 ms keeps the LED steadily lit through those micro-pauses, while still off
    // during real inactivity (e.g. a game running from RAM).
    private static final long ACTIVITY_WINDOW_NANOS = 250_000_000L; // ~250 ms
    private volatile long lastActivityNanos = Long.MIN_VALUE;

    public HdController(int objid, HardFile hdf, int partitionNameBase,
                        BooleanSupplier floppyInDf0) {
        this.objid = objid;
        this.hdf = hdf;
        this.partitionNameBase = partitionNameBase;
        this.floppyInDf0 = floppyInDf0 != null ? floppyInDf0 : () -> false;
        this.rom = EXPROM.clone();
        patchDeviceName(this.rom, objid);
        this.state = pluggedIn() ? State.UNCONFIGURED : State.SHUTUP;
    }

    public void setBus(Bus bus) { this.bus = bus; }

    public boolean pluggedIn() { return hdf != null; }

    public HardFile hardFile() { return hdf; }

    /** Number of partitions exposed (for the DH numbering of the following boards). */
    public int numPartitions() { return hdf != null ? hdf.numPartitions() : 0; }

    /**
     * {@code true} if the disk performed a read/write within the last activity window (to
     * light the HD LED). Read by the display thread, once per frame.
     */
    public boolean ledActive() {
        long t = lastActivityNanos;
        return t != Long.MIN_VALUE && (System.nanoTime() - t) < ACTIVITY_WINDOW_NANOS;
    }

    // =========================================================================
    // AutoConfigDevice
    // =========================================================================

    @Override public boolean autoconfiguring() { return state == State.UNCONFIGURED; }
    @Override public int configuredBaseAddr()  { return baseAddr; }

    @Override
    public void reset() {
        this.state = pluggedIn() ? State.UNCONFIGURED : State.SHUTUP;
        this.baseAddr = 0;
        this.pointer = 0;
        this.lastActivityNanos = Long.MIN_VALUE;
    }

    /** Byte of the AutoConfig descriptor (same scheme as {@link FastRam}). */
    private int descriptorByte(int idx) {
        int serial = 31415 + objid;
        switch (idx) {
            case 0x0: return (ERT_ZORROII | ERTF_DIAGVALID) | 0b001; // type | size(1 pagina)
            case 0x1: return 0x88;                  // product
            case 0x2: return 0x00;                  // flags
            case 0x3: return 0x00;
            case 0x4: return (0x0539 >> 8) & 0xFF;   // manufacturer hi
            case 0x5: return 0x0539 & 0xFF;         // manufacturer low
            case 0x6: return (serial >> 24) & 0xFF;  // serial
            case 0x7: return (serial >> 16) & 0xFF;
            case 0x8: return (serial >>  8) & 0xFF;
            case 0x9: return serial & 0xFF;
            case 0xA: return 0x00;                   // initDiagVec hi
            case 0xB: return 0x40;                   // initDiagVec low
            default:  return 0x00;
        }
    }

    @Override
    public int peekConfigByte(int addr) {
        if (state != State.UNCONFIGURED) return 0xFF;
        int offset = addr & 0xFF;
        int result = 0xFF;
        if ((offset & 1) == 0 && offset < 0x40) {
            int b = descriptorByte(offset >> 2);
            int nibble = ((offset & 2) != 0) ? (b & 0x0F) : ((b >> 4) & 0x0F);
            result = (offset < 4) ? (nibble << 4) : (~(nibble << 4) & 0xFF);
        } else if (offset == 0x40 || offset == 0x42) {
            result = 0x00; // interrupt pending register
        }
        return result & 0xFF;
    }

    @Override
    public boolean pokeConfigByte(int addr, int value) {
        if (state != State.UNCONFIGURED) return false;
        switch (addr & 0xFF) {
            case 0x4A: // ec_BaseAddress (A19-A16)
                baseAddr |= (value & 0xF0) << 12;
                return false;
            case 0x48: // ec_BaseAddress (A23-A20): commit + attivazione
                baseAddr |= (value & 0xF0) << 16;
                state = State.CONFIGURED;
                return true;
            case 0x4C: // ec_ShutUp
                state = State.SHUTUP;
                return false;
            default:
                return false;
        }
    }

    // =========================================================================
    // Access to the mapped page (ROM + magic ports)
    // =========================================================================

    /** Word read by the CPU at the page offset (addr &amp; 0xFFFF). */
    public int peekWord(int pageOffset) {
        int off = pageOffset - 0x40; // initDiagVec
        switch (off - EXPROM_SIZE) {
            case 0: return hdf != null ? hdf.numPartitions() : 0;
            case 2: return 0;                 // number of filesystem drivers (ROM FS only)
            case 4: return disableAutoboot() ? 1 : 0;
            case 6: return 0;                 // shared folders (not supported)
            default:
                if (off >= 0 && off + 1 < rom.length) {
                    return ((rom[off] & 0xFF) << 8) | (rom[off + 1] & 0xFF);
                }
                if (off >= 0 && off < rom.length) {
                    return (rom[off] & 0xFF) << 8;
                }
                return 0;
        }
    }

    /** Byte read by the CPU at the page offset. */
    public int peekByte(int pageOffset) {
        int word = peekWord(pageOffset & ~1);
        return (pageOffset & 1) == 0 ? (word >> 8) & 0xFF : word & 0xFF;
    }

    /** Word written by the CPU at the page offset. */
    public void pokeWord(int pageOffset, int value) {
        int off = pageOffset - 0x40;
        value &= 0xFFFF;
        if (off == EXPROM_SIZE) {
            pointer = (pointer & 0x0000FFFF) | (value << 16);
        } else if (off == EXPROM_SIZE + 2) {
            pointer = (pointer & 0xFFFF0000) | value;
        } else if (off == EXPROM_SIZE + 4) {
            switch (value) {
                case 0xFEDE: processCmd(pointer);  break;
                case 0xFEDF: processInit(pointer); break;
                case 0xFEE0: /* processResource */ break;
                case 0xFEE1: /* processInfoReq  */ break;
                case 0xFEE2: /* processInitSeg   */ break;
                default: break;
            }
        }
    }

    private boolean disableAutoboot() {
        return floppyInDf0.getAsBoolean() || hdf == null || !hdf.isBootable();
    }

    // =========================================================================
    // Driver commands
    // =========================================================================

    private void processCmd(int ptr) {
        if (bus == null || hdf == null) return;
        int cmd    = busReadWord(ptr + IO_COMMAND);
        long offset = busReadLong(ptr + IO_OFFSET) & 0xFFFFFFFFL;
        long length = busReadLong(ptr + IO_LENGTH) & 0xFFFFFFFFL;
        int addr   = busReadLong(ptr + IO_DATA);

        int error = 0;
        int actual = 0;
        switch (cmd) {
            case CMD_READ:
                lastActivityNanos = System.nanoTime(); // LED on before the transfer
                error = doRead(offset, length, addr);
                actual = (int) length;
                break;
            case CMD_WRITE:
            case TD_FORMAT:
                lastActivityNanos = System.nanoTime(); // LED on before the transfer
                error = doWrite(offset, length, addr);
                actual = (int) length;
                break;
            default:
                // CMD_RESET/UPDATE/CLEAR/STOP/START/FLUSH/TD_* status commands: no-op, success.
                if (cmd < 1 || cmd > 18) error = IOERR_NOCMD;
                break;
        }

        busWriteByte(ptr + IO_ERROR, error & 0xFF);
        if (error == 0) busWriteLong(ptr + IO_ACTUAL, actual);
    }

    private int doRead(long offset, long length, int addr) {
        if (!hdf.inRange(offset, length)) return IOERR_BADADDRESS;
        // Block transfer (memcpy) disk -> RAM.
        bus.writeBlock(addr, hdf.rawData(), (int) offset, (int) length);
        return 0;
    }

    private int doWrite(long offset, long length, int addr) {
        if (!hdf.inRange(offset, length)) return IOERR_BADADDRESS;
        if (hdf.readOnly()) return 0; // write silently ignored on a protected disk
        // Block transfer (memcpy) RAM -> disk, straight into the image's array.
        bus.readBlock(addr, hdf.rawData(), (int) offset, (int) length);
        hdf.markModified();
        return 0;
    }

    /** Fills the DOS environment of a partition (the exprom's "devn" structure). */
    private void processInit(int ptr) {
        if (bus == null || hdf == null) return;
        int unit = busReadLong(ptr + 0x08);
        if (unit < 0 || unit >= hdf.numPartitions()) return;

        HardFile.Partition part = hdf.partition(unit);

        // DOS name "DH<n>" in the buffer pointed to by devn_dosName.
        int namePtr = busReadLong(ptr + 0x00);
        if (namePtr != 0) {
            String dosName = "DH" + (partitionNameBase + unit);
            for (int i = 0; i < dosName.length(); i++) {
                bus.writeByte(namePtr + i, dosName.charAt(i));
            }
            bus.writeByte(namePtr + dosName.length(), 0);
        }

        int bootFlag = (part.flags & 1);
        if (disableAutoboot()) bootFlag = 0;

        busWriteLong(ptr + 0x0C, part.flags);       // devn_flags
        busWriteLong(ptr + 0x14, part.sizeBlock);   // devn_sizeBlock
        busWriteLong(ptr + 0x18, 0);                // devn_secOrg
        busWriteLong(ptr + 0x1C, hdf.heads());      // devn_numHeads
        busWriteLong(ptr + 0x20, 1);                // devn_secsPerBlk
        busWriteLong(ptr + 0x24, hdf.sectors());    // devn_blkTrack
        busWriteLong(ptr + 0x28, part.reserved);    // devn_resBlks
        busWriteLong(ptr + 0x30, 0);                // devn_interleave
        busWriteLong(ptr + 0x34, part.lowCyl);      // devn_lowCyl
        busWriteLong(ptr + 0x38, part.highCyl);     // devn_upperCyl
        busWriteLong(ptr + 0x3C, 30);               // devn_numBuffers
        busWriteLong(ptr + 0x40, 0);                // devn_memBufType
        busWriteLong(ptr + 0x44, 0x7FFFFFFF);       // devn_transferSize
        busWriteLong(ptr + 0x48, 0xFFFFFFFE);       // devn_addMask
        busWriteLong(ptr + 0x4C, 0);                // devn_bootPrio
        busWriteLong(ptr + 0x50, part.dosType);     // devn_dName
        busWriteLong(ptr + 0x54, bootFlag);         // devn_bootflags
        busWriteLong(ptr + 0x58, 0);                // devn_segList (0 = filesystem in ROM)
    }

    // =========================================================================
    // Amiga bus access helpers
    // =========================================================================

    private int busReadWord(int addr) {
        return (bus.readByte(addr) << 8) | bus.readByte(addr + 1);
    }

    private int busReadLong(int addr) {
        return (bus.readByte(addr) << 24) | (bus.readByte(addr + 1) << 16)
             | (bus.readByte(addr + 2) << 8) | bus.readByte(addr + 3);
    }

    private void busWriteByte(int addr, int value) {
        bus.writeByte(addr, value & 0xFF);
    }

    private void busWriteLong(int addr, int value) {
        bus.writeByte(addr,     (value >>> 24) & 0xFF);
        bus.writeByte(addr + 1, (value >>> 16) & 0xFF);
        bus.writeByte(addr + 2, (value >>>  8) & 0xFF);
        bus.writeByte(addr + 3,  value         & 0xFF);
    }

    // =========================================================================
    // ROM
    // =========================================================================

    private static byte[] loadExprom() {
        try (InputStream in = HdController.class.getResourceAsStream("/hd/exprom.bin")) {
            if (in == null) {
                System.err.println("[HDF] Risorsa /hd/exprom.bin non trovata: hard disk disabilitato");
                return new byte[0];
            }
            return in.readAllBytes();
        } catch (IOException e) {
            System.err.println("[HDF] Lettura di /hd/exprom.bin fallita: " + e.getMessage());
            return new byte[0];
        }
    }

    /** Makes the board's 68k device name unique: "virtualhd.device" -> "hrddrive&lt;n&gt;.device". */
    private static void patchDeviceName(byte[] rom, int objid) {
        byte[] from = "virtualhd.device".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        String toStr = "hrddrive" + (objid % 10) + ".device"; // same length (16)
        byte[] to = toStr.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        if (to.length != from.length) return;
        int idx = indexOf(rom, from);
        if (idx >= 0) System.arraycopy(to, 0, rom, idx, to.length);
    }

    private static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
