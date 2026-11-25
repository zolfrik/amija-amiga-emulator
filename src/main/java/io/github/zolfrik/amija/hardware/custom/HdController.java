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

package io.github.zolfrik.amija.hardware.custom;

import io.github.zolfrik.amija.memory.HardFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * operations are {@code memcpy}. The driver answers the trackdisk commands, their TD64 and
 * NSD 64-bit variants (so a partition may lie anywhere on a disk of any size), and the NSD
 * device query. Filesystems stored in the disk's RDB are handed to the ROM, which loads them
 * before mounting the partitions.
 *
 * <p>Port protocol (offsets relative to {@code baseAddr + initDiagVec}, where
 * {@code EXPROM_SIZE} is the size of the ROM):
 * <ul>
 *   <li>read  {@code EXPROM_SIZE+0..6}: number of partitions / number of FS drivers / "disable
 *       autoboot" flag / shared folders (0);</li>
 *   <li>write {@code EXPROM_SIZE+0/+2}: high/low word of the pointer to a structure in Amiga
 *       RAM;</li>
 *   <li>write {@code EXPROM_SIZE+4}: command (0xFEDE=I/O, 0xFEDF=init partition,
 *       0xFEE0=FileSystem.resource, 0xFEE1=filesystem info, 0xFEE2=filesystem segments);</li>
 *   <li>read {@code EXPROM_SIZE+0x10..}: the NSD supported-command list, zero-terminated.</li>
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
    private static final int TD_READ64   = 24;
    private static final int TD_WRITE64  = 25;
    private static final int TD_SEEK64   = 26;
    private static final int TD_FORMAT64 = 27;
    private static final int NSCMD_DEVICEQUERY  = 0x4000;
    private static final int NSCMD_TD_READ64    = 0xC000;
    private static final int NSCMD_TD_WRITE64   = 0xC001;
    private static final int NSCMD_TD_SEEK64    = 0xC002;
    private static final int NSCMD_TD_FORMAT64  = 0xC003;
    private static final int NSDEVTYPE_TRACKDISK = 5;
    private static final int IOERR_NOCMD     = 0xFD; // -3
    private static final int IOERR_BADLENGTH = 0xFC; // -4
    private static final int IOERR_BADADDRESS = 0xFB; // -5
    private static final int TDERR_NOTSPECIFIED = 20;

    /** Commands reported by the NSD device query, zero-terminated. */
    private static final int[] SUPPORTED_COMMANDS = {
        1, CMD_READ, CMD_WRITE, 4, 5, 6, 7, 8, 9, 10, TD_FORMAT, 12, 13, 14, 15,
        TD_READ64, TD_WRITE64, TD_SEEK64, TD_FORMAT64,
        NSCMD_DEVICEQUERY, NSCMD_TD_READ64, NSCMD_TD_WRITE64, NSCMD_TD_SEEK64,
        NSCMD_TD_FORMAT64, 0
    };
    /** Offset of the command list after the ROM (past the magic ports). */
    private static final int CMDLIST_OFF = 0x10;

    /** The ROM's stack frame for a filesystem's segments holds three hunks. */
    private static final int MAX_FS_HUNKS = 3;

    /** Transfer buffer for images accessed in place. */
    private static final int IO_CHUNK = 64 * 1024;

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

    // Filesystems from the RDB still to be loaded by the ROM, and the seglist (BPTR) to give
    // the partitions of each DosType. Rebuilt at every reset: the ROM installs them anew
    // at every boot.
    private final transient List<HardFile.FileSystem> pendingFs = new ArrayList<>();
    private final transient Map<Integer, Integer> segListByDosType = new HashMap<>();
    private transient byte[] ioBuffer;

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
        resetFileSystems();
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
        resetFileSystems();
    }

    /** Every loadable filesystem of the RDB becomes pending again. */
    private void resetFileSystems() {
        pendingFs.clear();
        segListByDosType.clear();
        if (hdf == null) return;
        for (HardFile.FileSystem fs : hdf.fileSystems()) {
            if (fs.code.numHunks() > MAX_FS_HUNKS) {
                System.err.println("[HDF] Filesystem " + HardFile.dosTypeString(fs.dosType)
                        + " has " + fs.code.numHunks() + " hunks (at most " + MAX_FS_HUNKS
                        + " can be loaded): not installed");
                continue;
            }
            pendingFs.add(fs);
        }
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
            case 2: return pendingFs.size();  // number of filesystems still to install
            case 4: return disableAutoboot() ? 1 : 0;
            case 6: return 0;                 // shared folders (not supported)
            default:
                int cl = off - EXPROM_SIZE - CMDLIST_OFF;
                if (cl >= 0 && (cl & 1) == 0 && cl / 2 < SUPPORTED_COMMANDS.length) {
                    return SUPPORTED_COMMANDS[cl / 2];
                }
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
                case 0xFEE0: processResource(pointer); break;
                case 0xFEE1: processInfoReq(pointer);  break;
                case 0xFEE2: processInitSeg(pointer);  break;
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
        long low   = busReadLong(ptr + IO_OFFSET) & 0xFFFFFFFFL;
        long high  = busReadLong(ptr + IO_ACTUAL) & 0xFFFFFFFFL;
        long length = busReadLong(ptr + IO_LENGTH) & 0xFFFFFFFFL;
        int addr   = busReadLong(ptr + IO_DATA);
        long offset64 = (high << 32) | low; // TD64/NSD: io_Actual holds the high half

        int error = 0;
        int actual = 0;
        switch (cmd) {
            case CMD_READ, TD_READ64, NSCMD_TD_READ64 -> {
                lastActivityNanos = System.nanoTime(); // LED on before the transfer
                error = doRead(cmd == CMD_READ ? low : offset64, length, addr);
                actual = (int) length;
            }
            case CMD_WRITE, TD_FORMAT, TD_WRITE64, TD_FORMAT64,
                 NSCMD_TD_WRITE64, NSCMD_TD_FORMAT64 -> {
                lastActivityNanos = System.nanoTime(); // LED on before the transfer
                boolean wide = cmd != CMD_WRITE && cmd != TD_FORMAT;
                error = doWrite(wide ? offset64 : low, length, addr);
                actual = (int) length;
            }
            case TD_SEEK64, NSCMD_TD_SEEK64 -> { }
            case NSCMD_DEVICEQUERY -> {
                if (length < 16) {
                    error = IOERR_BADLENGTH;
                } else {
                    busWriteLong(addr, 0);                       // DevQueryFormat
                    busWriteLong(addr + 4, 16);                  // SizeAvailable
                    busWriteWord(addr + 8, NSDEVTYPE_TRACKDISK); // DeviceType
                    busWriteWord(addr + 10, 0);                  // DeviceSubType
                    busWriteLong(addr + 12, baseAddr + 0x40 + EXPROM_SIZE + CMDLIST_OFF);
                    actual = 16;
                }
            }
            default -> {
                // CMD_RESET/UPDATE/CLEAR/STOP/START/FLUSH/TD_* status commands: no-op, success.
                if (cmd < 1 || cmd > 18) error = IOERR_NOCMD;
            }
        }

        busWriteByte(ptr + IO_ERROR, error & 0xFF);
        if (error == 0) busWriteLong(ptr + IO_ACTUAL, actual);
    }

    private int doRead(long offset, long length, int addr) {
        if (!hdf.inRange(offset, length) || length > Integer.MAX_VALUE) return IOERR_BADADDRESS;
        try {
            if (hdf.inMemory()) {
                // Block transfer (memcpy) disk -> RAM.
                bus.writeBlock(addr, hdf.rawData(), (int) offset, (int) length);
                return 0;
            }
            byte[] buf = ioBuffer();
            for (long done = 0; done < length; ) {
                int n = (int) Math.min(buf.length, length - done);
                hdf.read(offset + done, buf, 0, n);
                bus.writeBlock(addr + (int) done, buf, 0, n);
                done += n;
            }
            return 0;
        } catch (UncheckedIOException e) {
            System.err.println("[HDF] Read failed: " + e.getMessage());
            return TDERR_NOTSPECIFIED;
        }
    }

    private int doWrite(long offset, long length, int addr) {
        if (!hdf.inRange(offset, length) || length > Integer.MAX_VALUE) return IOERR_BADADDRESS;
        try {
            if (hdf.inMemory()) {
                // Block transfer (memcpy) RAM -> disk, straight into the image's array (a
                // read-only image keeps it for the session: it is never saved).
                bus.readBlock(addr, hdf.rawData(), (int) offset, (int) length);
                hdf.markModified();
                return 0;
            }
            byte[] buf = ioBuffer();
            for (long done = 0; done < length; ) {
                int n = (int) Math.min(buf.length, length - done);
                bus.readBlock(addr + (int) done, buf, 0, n);
                hdf.write(offset + done, buf, 0, n);
                done += n;
            }
            return 0;
        } catch (UncheckedIOException e) {
            System.err.println("[HDF] Write failed: " + e.getMessage());
            return TDERR_NOTSPECIFIED;
        }
    }

    private byte[] ioBuffer() {
        if (ioBuffer == null) ioBuffer = new byte[IO_CHUNK];
        return ioBuffer;
    }

    // =========================================================================
    // Filesystems from the RDB
    // =========================================================================

    /**
     * The ROM hands over FileSystem.resource: every pending filesystem the resource already
     * holds at the same or a newer version is dropped, and the partitions of its DosType get
     * the seglist of the resource's entry.
     */
    private void processResource(int ptr) {
        if (bus == null) return;
        int node = busReadLong(ptr + 0x12); // fsr_FileSysEntries.lh_Head
        for (int guard = 0; node != 0 && guard < 256; guard++) {
            int succ = busReadLong(node);   // ln_Succ
            if (succ == 0) break;           // the list's tail node
            int dosType = busReadLong(node + 0x0E); // fse_DosType
            int version = busReadLong(node + 0x12); // fse_Version
            int segList = busReadLong(node + 0x36); // fse_SegList
            boolean dropped = pendingFs.removeIf(fs -> fs.dosType == dosType
                    && Integer.compareUnsigned(version, fs.version) >= 0);
            if (dropped && segList != 0) segListByDosType.put(dosType, segList);
            node = succ;
        }
    }

    /** Fills the ROM's fsinfo structure for pending filesystem number {@code fsinfo_num}. */
    private void processInfoReq(int ptr) {
        if (bus == null) return;
        int num = busReadWord(ptr);
        if (num >= pendingFs.size()) return;
        HardFile.FileSystem fs = pendingFs.get(num);
        busWriteLong(ptr + 0x02, fs.dosType);
        busWriteLong(ptr + 0x06, fs.version);
        busWriteLong(ptr + 0x0A, fs.code.numHunks());
        for (int i = 0; i < fs.code.numHunks(); i++) {
            busWriteLong(ptr + 0x0E + 4 * i, fs.code.memRaw(i));
        }
    }

    /**
     * Loads a filesystem into the hunk blocks the ROM allocated (their addresses at
     * {@code ptr+0/4/8}, the filesystem number at {@code ptr+12}): each block gets its size,
     * the BPTR of the next block and the relocated hunk.
     */
    private void processInitSeg(int ptr) {
        if (bus == null) return;
        int num = busReadLong(ptr + 12);
        if (num < 0 || num >= pendingFs.size()) return;
        HardFile.FileSystem fs = pendingFs.get(num);
        int n = fs.code.numHunks();
        int[] seg = new int[n];
        int[] dataAddr = new int[n];
        for (int i = 0; i < n; i++) {
            seg[i] = busReadLong(ptr + 4 * i);
            if (seg[i] == 0) {
                System.err.println("[HDF] No memory for filesystem "
                        + HardFile.dosTypeString(fs.dosType));
                return;
            }
            dataAddr[i] = seg[i] + 8;
        }
        for (int i = 0; i < n; i++) {
            busWriteLong(seg[i], fs.code.memSize(i) + 8);
            busWriteLong(seg[i] + 4, i == n - 1 ? 0 : (seg[i + 1] + 4) >>> 2);
            byte[] img = fs.code.image(i, dataAddr);
            bus.writeBlock(dataAddr[i], img, 0, img.length);
        }
        segListByDosType.put(fs.dosType, (seg[0] + 4) >>> 2);
        System.out.println("[HDF] Filesystem " + HardFile.dosTypeString(fs.dosType)
                + " " + (fs.version >>> 16) + "." + (fs.version & 0xFFFF) + " loaded at $"
                + Integer.toHexString(seg[0]));
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
        busWriteLong(ptr + 0x1C, part.surfaces);    // devn_numHeads
        busWriteLong(ptr + 0x20, 1);                // devn_secsPerBlk
        busWriteLong(ptr + 0x24, part.blocksPerTrack); // devn_blkTrack
        busWriteLong(ptr + 0x28, part.reserved);    // devn_resBlks
        busWriteLong(ptr + 0x30, 0);                // devn_interleave
        busWriteLong(ptr + 0x34, part.lowCyl);      // devn_lowCyl
        busWriteLong(ptr + 0x38, part.highCyl);     // devn_upperCyl
        busWriteLong(ptr + 0x3C, part.numBuffers);  // devn_numBuffers
        busWriteLong(ptr + 0x40, part.bufMemType);  // devn_memBufType
        busWriteLong(ptr + 0x44, part.maxTransfer); // devn_transferSize
        busWriteLong(ptr + 0x48, part.mask);        // devn_addMask
        busWriteLong(ptr + 0x4C, part.bootPri);     // devn_bootPrio
        busWriteLong(ptr + 0x50, part.dosType);     // devn_dName
        busWriteLong(ptr + 0x54, bootFlag);         // devn_bootflags
        // devn_segList: the filesystem loaded from the RDB for this DosType, 0 = in ROM.
        busWriteLong(ptr + 0x58, segListByDosType.getOrDefault(part.dosType, 0));
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

    private void busWriteWord(int addr, int value) {
        bus.writeByte(addr,     (value >>> 8) & 0xFF);
        bus.writeByte(addr + 1,  value        & 0xFF);
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
                System.err.println("[HDF] Resource /hd/exprom.bin not found: hard disk disabled");
                return new byte[0];
            }
            return in.readAllBytes();
        } catch (IOException e) {
            System.err.println("[HDF] Reading /hd/exprom.bin failed: " + e.getMessage());
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
