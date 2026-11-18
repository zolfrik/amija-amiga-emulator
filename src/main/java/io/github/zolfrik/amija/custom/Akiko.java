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

import io.github.zolfrik.amija.custom.cd.CdImage;
import io.github.zolfrik.amija.custom.cd.CddaPlayer;

import java.io.IOException;
import java.util.List;

/**
 * Akiko, the CD32's custom chip, at {@code $B80000-$B8FFFF}: its registers fill the first
 * {@code $40} bytes and repeat every 64 bytes up to {@code $B87FFF}; the upper half reads 0.
 *
 * <ul>
 *   <li>{@code $00-$03}: the identification {@code $C0CACAFE}.</li>
 *   <li>{@code $04-$2B}: the interface to the CD drive: interrupt request and enable, the
 *       DMA bases, the command and status rings, the sector block register, the
 *       configuration and the PIO port.</li>
 *   <li>{@code $30-$33}: the I2C lines of the NVRAM ({@link I2cEeprom}): data at {@code $30}
 *       (bit 7 SCL, bit 6 SDA), their directions at {@code $32}.</li>
 *   <li>{@code $38-$3B}: the chunky-to-planar converter: eight long words in, eight out.</li>
 * </ul>
 *
 * <p>The drive speaks a packet protocol. A command packet is up to 13 bytes: the first
 * carries a 4-bit sequence number in its high nibble and the command in its low one, the
 * last is a checksum making the sum of the packet {@code $FF}. The drive answers with a
 * packet of the same form whose first byte repeats the command's. Commands reach the drive
 * from a 256-byte ring in memory ({@code base + $200}) by DMA or byte by byte through the PIO
 * port; answers go to the ring at {@code base}. The drive also sends packets of its own: the
 * disc status after a change, the table of contents one entry per frame, the start and end
 * of audio play.
 *
 * <p>Akiko's time is the line: {@link #hsync} runs once per line and steps the drive.
 * Akiko asserts INTREQ PORTS while any enabled request bit is set.
 */
public final class Akiko {

    /** Byte access to the CPU address space, for Akiko's DMA. */
    public interface Memory {
        int readByte(int address);
        void writeByte(int address, int value);
    }

    // Interrupt request / enable bits.
    static final int INT_SUBCODE = 0x80000000;
    static final int INT_DRIVEXMIT = 0x40000000;
    static final int INT_DRIVERECV = 0x20000000;
    static final int INT_RXDMADONE = 0x10000000;
    static final int INT_TXDMADONE = 0x08000000;
    static final int INT_PBX = 0x04000000;
    static final int INT_OVERFLOW = 0x02000000;

    // Configuration bits.
    static final int FLAG_SUBCODE = 0x80000000;
    static final int FLAG_TXD = 0x40000000;
    static final int FLAG_RXD = 0x20000000;
    static final int FLAG_CAS = 0x10000000;
    static final int FLAG_PBX = 0x08000000;
    static final int FLAG_ENABLE = 0x04000000;
    static final int FLAG_RAW = 0x02000000;
    static final int FLAG_NTSC = 0x00800000;

    // Drive status bits.
    private static final int CDS_ERROR = 0x80;
    private static final int CDS_PLAYING = 0x08;
    private static final int CDS_PLAYEND = 0x00;

    // Drive error codes.
    private static final int ERR_BADCOMMAND = 0x80;
    private static final int ERR_CHECKSUM = 0x88;
    private static final int ERR_NODISK = 0xF8;

    /** Command lengths without the checksum; -1 is an unknown command. */
    private static final int[] COMMAND_LENGTHS = {1, 2, 1, 1, 12, 2, 1, 1, 4, 1, 2, -1, -1, -1, -1, -1};

    /** The firmware identification of the INFO answer, with its terminating zero. */
    private static final byte[] FIRMWARE =
            "CHINON  O-658-2 24\0".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);

    private final Memory memory;
    private final I2cEeprom nvram;
    private Runnable interruptSink;

    /** Observer of the packets between Akiko and the drive (tests and tools only). */
    public interface PacketObserver {
        /** {@code toHost}: an answer of the drive; otherwise a command, checksum included. */
        void packet(boolean toHost, int[] bytes, int length);
    }

    private transient PacketObserver packetObserver;

    public void setPacketObserver(PacketObserver observer) {
        this.packetObserver = observer;
    }

    // NVRAM lines.
    private int i2cDirection;
    private boolean i2cScl = true;
    private boolean i2cSda = true;

    // Chunky to planar.
    private final int[] c2pBuffer = new int[8];
    private final int[] c2pResult = new int[8];
    private int c2pReadOffset;
    private int c2pWriteOffset;

    // CD interface registers.
    private int intreq;
    private int intena;
    private int subcodeOffset;
    private int addressData;
    private int addressMisc;
    private int subcodeAddress;
    private int rxAddress;
    private int txAddress;
    private int flags;
    private int pbx;
    private int txIndex;
    private int rxIndex;
    private int txCompare;
    private int rxCompare;
    private int lastRx;

    // Drive.
    private final int[] resultBuffer = new int[32];
    private final int[] commandBuffer = new int[32];
    private int command;
    private int commandActive;
    private int commandLength;
    private boolean checksumError;
    private boolean unknownCommand;
    private int receiveLength;
    private int receiveOffset;
    private int txDmaDelay;
    private int rxDmaDelay;
    private int initialized;
    private int door = 1;
    private int led;
    private boolean muted;

    private boolean disk;
    private boolean paused;
    private int playing;
    private int audioTimeout;
    private int speed = 1;
    private int currentSector = -1;
    private int sectorCounter;
    private int seekDelay;
    private int tocCounter = -1;
    private int tocPoints;

    /** The disc in the drive, {@code null} when empty. */
    private CdImage media;
    private List<CdImage.TocEntry> toc = List.of();
    /** The table of contents as the drive sends it, 13 bytes per entry. */
    private final byte[] tocBuffer = new byte[103 * 13];
    private int dataOffset = -1;
    private int lastPlayEnd;

    /** Sectors the drive has read ahead: the first one, a use count per sector (3 = fresh), the data. */
    private static final int SECTOR_BUFFER = 128;
    private int bufferStart = -1;
    private byte[] bufferInfo = new byte[SECTOR_BUFFER];
    /** The sectors themselves: not state, read again from the disc after a load. */
    private transient byte[] bufferData = new byte[SECTOR_BUFFER * 2352];
    private int bufferStart2 = -1;
    private byte[] bufferInfo2 = new byte[SECTOR_BUFFER];
    private transient byte[] bufferData2 = new byte[SECTOR_BUFFER * 2352];

    private int lastMediaState = -1;
    private boolean mediaChanged;
    private int mediaCheckCounter;
    /** Line counters of the sector and frame clocks; a reset leaves them running. */
    private float sectorClock;
    private float frameClock;
    private int subcodeClock;

    // Audio play.
    /** The drive's audio player for the disc in the drive; {@code null} without a disc. */
    private CddaPlayer player;
    private java.util.function.LongSupplier clock = () -> 0L;
    private boolean ntscClock;
    /** The Q subchannel last read from the player, 16 bytes, and whether it is valid. */
    private final byte[] qcodeBuffer = new byte[16];
    private boolean qcodeValid;
    /** The play status the firmware last took from the player. */
    private int audioStatus;
    private int qcodeCounter;
    /** Subchannel blocks of the sectors played, waiting to go to memory. */
    private static final int SUBCODE_RING = 36;
    private final byte[][] subcodeRing = new byte[SUBCODE_RING][96];
    private final boolean[] subcodeInUse = new boolean[SUBCODE_RING];
    private int subcodeRead;
    private int subcodeWrite;

    public Akiko(Memory memory, I2cEeprom nvram) {
        this.memory = memory;
        this.nvram = nvram;
        reset();
    }

    /** Called when Akiko asserts its interrupt. */
    public void setInterruptSink(Runnable sink) {
        this.interruptSink = sink;
    }

    public I2cEeprom nvram() {
        return nvram;
    }

    /** True while an enabled request bit is set: INTREQ PORTS is held asserted. */
    public boolean interruptAsserted() {
        return (intreq & intena) != 0;
    }

    /** The NTSC bit of the configuration: the rate of the TOD pulse Akiko gives CIA-A. */
    public boolean ntscMode() {
        return (flags & FLAG_NTSC) != 0;
    }

    /**
     * The time base of the audio player, in units of 1/512 colour clock, and the video standard
     * its 44.1 kHz is derived from.
     */
    public void setClock(java.util.function.LongSupplier unitsClock, boolean ntsc) {
        this.clock = unitsClock;
        this.ntscClock = ntsc;
    }

    /** Puts a disc in the drive ({@code null} empties it); the drive notices within two seconds. */
    public void insert(CdImage image) {
        if (player != null) player.stop();
        this.media = image;
        bufferStart = -1;
        bufferStart2 = -1;
        player = image == null ? null : new CddaPlayer(image, new CddaPlayer.Listener() {
            @Override
            public void status(int status) {
                playerStatus(status);
            }

            @Override
            public void subcode(byte[] interleaved) {
                playerSubcode(interleaved);
            }
        }, ntscClock);
    }

    /** The audio player of the disc in the drive, {@code null} without a disc. */
    public CddaPlayer player() {
        return player;
    }

    /**
     * After a state load: the table of contents and the read-ahead sectors come back from the
     * disc in the drive (only their positions and use counts are state).
     */
    public void stateLoaded() {
        if (media == null) return;
        toc = media.toc();
        if (bufferStart >= 0) readBlocks(bufferStart, SECTOR_BUFFER, bufferData, 0);
    }

    /** Plays the drive's audio up to the present instant. */
    public void syncAudio() {
        if (player != null) player.advanceTo(clock.getAsLong());
    }

    /**
     * The drive's activity light: on while the firmware holds it on (it does so while it
     * reads) or while CD audio plays. Read by the display thread once per frame.
     */
    public boolean ledLit() {
        return led != 0 || playing != 0;
    }

    /** The audio the drive is playing now, left and right, 0 when muted or silent. */
    public short audioLeft() {
        return player == null || muted ? 0 : player.left();
    }

    public short audioRight() {
        return player == null || muted ? 0 : player.right();
    }

    public CdImage media() {
        return media;
    }

    /** CIA-A PA0 low mutes the CD audio. */
    public void setMuted(boolean muted) {
        this.muted = muted;
    }

    public boolean isMuted() {
        return muted;
    }

    /** Power-on and /RESET. */
    public void reset() {
        stopAudio();
        i2cScl = true;
        i2cSda = true;
        i2cDirection = 0;
        nvram.reset();
        speed = 1;
        currentSector = -1;
        txIndex = 0;
        rxIndex = 0;
        txCompare = 0;
        lastMediaState = -1;
        lastRx = 0;
        intreq = INT_SUBCODE;
        subcodeOffset = 0xC2;
        intena = 0;
        flags = 0;
        led = 0;
        receiveLength = 0;
        receiveOffset = 0;
        initialized = 0;
        mediaCheckCounter = 0;
    }

    // ---------------------------------------------------------------------------------------
    // Bus
    // ---------------------------------------------------------------------------------------

    public int readByte(int addr) {
        addr &= 0xFFFF;
        if (addr >= 0x8000) return 0;
        int v = readRegister(addr);
        c2pReadStep(addr);
        return v;
    }

    /** A word: the low byte is accessed first. */
    public int readWord(int addr) {
        addr &= 0xFFFF;
        if (addr >= 0x8000) return 0;
        int v = readRegister(addr + 1);
        v |= readRegister(addr) << 8;
        c2pReadStep(addr);
        return v;
    }

    /** A long word: the bytes are accessed from the lowest to the highest. */
    public int readLong(int addr) {
        addr &= 0xFFFF;
        if (addr >= 0x8000) return 0;
        int v = readRegister(addr + 3);
        v |= readRegister(addr + 2) << 8;
        v |= readRegister(addr + 1) << 16;
        v |= readRegister(addr) << 24;
        c2pReadStep(addr);
        return v;
    }

    public void writeByte(int addr, int v) {
        addr &= 0xFFFF;
        if (addr >= 0x8000) return;
        writeRegister(addr, v);
    }

    public void writeWord(int addr, int v) {
        addr &= 0xFFFF;
        if (addr >= 0x8000) return;
        writeRegister(addr + 1, v);
        writeRegister(addr, v >> 8);
    }

    public void writeLong(int addr, int v) {
        addr &= 0xFFFF;
        if (addr >= 0x8000) return;
        writeRegister(addr + 3, v);
        writeRegister(addr + 2, v >> 8);
        writeRegister(addr + 1, v >> 16);
        writeRegister(addr, v >> 24);
    }

    private static int byteOf(int v, int offset) {
        return (v >>> ((3 - offset) * 8)) & 0xFF;
    }

    private static int withByte(int v, int offset, int b) {
        int shift = (3 - offset) * 8;
        return (v & ~(0xFF << shift)) | ((b & 0xFF) << shift);
    }

    private int readRegister(int addr) {
        addr &= 0x3F;
        switch (addr) {
            case 0x00: return 0xC0;
            case 0x01, 0x02: return 0xCA;
            case 0x03: return 0xFE;
            case 0x30, 0x31, 0x32, 0x33: return nvramRead(addr - 0x30);
            case 0x38, 0x39, 0x3A, 0x3B: return c2pRead(addr - 0x38);
            default: break;
        }
        int v;
        switch (addr) {
            case 0x04, 0x05, 0x06, 0x07 -> v = byteOf(intreq, addr - 0x04);
            case 0x08, 0x09, 0x0A, 0x0B -> v = byteOf(intena, addr - 0x08);
            case 0x0C, 0x0D, 0x0E, 0x0F -> v = byteOf(intena, addr - 0x0C);
            case 0x10, 0x14, 0x18, 0x1C -> v = subcodeOffset;
            case 0x11, 0x15, 0x19, 0x1D -> v = txIndex;
            case 0x12, 0x16, 0x1A, 0x1E -> v = rxIndex;
            case 0x20, 0x21 -> v = byteOf(pbx, addr - 0x20 + 2);
            case 0x24, 0x25, 0x26, 0x27 -> v = byteOf(flags, addr - 0x24);
            case 0x28 -> {
                if ((flags & FLAG_RXD) == 0 && receiveOffset < receiveLength) {
                    lastRx = resultBuffer[receiveOffset++];
                    if (receiveOffset == receiveLength) {
                        intreq &= ~INT_DRIVERECV;
                        receiveLength = 0;
                        setStatus(INT_DRIVEXMIT);
                    }
                } else {
                    intreq &= ~INT_DRIVERECV;
                }
                v = lastRx;
            }
            default -> v = 0;
        }
        internal();
        return v & 0xFF;
    }

    private void writeRegister(int addr, int v) {
        addr &= 0x3F;
        v &= 0xFF;
        switch (addr) {
            case 0x30, 0x31, 0x32, 0x33 -> { nvramWrite(addr - 0x30, v); return; }
            case 0x38, 0x39, 0x3A, 0x3B -> { c2pWrite(addr - 0x38, v); return; }
            default -> { }
        }
        switch (addr) {
            case 0x08, 0x09, 0x0A, 0x0B -> {
                intena = withByte(intena, addr - 0x08, v);
                intena &= 0xFF000000;
            }
            case 0x10, 0x11, 0x12, 0x13 -> {
                addressData = withByte(addressData, addr - 0x10, v);
                addressData &= 0x00FFF000;
            }
            case 0x14, 0x15, 0x16, 0x17 -> {
                addressMisc = withByte(addressMisc, addr - 0x14, v);
                addressMisc &= 0x00FFFC00;
                subcodeAddress = addressMisc | 0x100;
                rxAddress = addressMisc;
                txAddress = addressMisc | 0x200;
            }
            case 0x18 -> intreq &= ~INT_SUBCODE;
            case 0x1D -> {
                intreq &= ~INT_TXDMADONE;
                txCompare = v;
                txDmaDelay = 3;
            }
            case 0x1F -> {
                intreq &= ~INT_RXDMADONE;
                rxCompare = v;
                rxDmaDelay = 3;
            }
            case 0x20, 0x21 -> {
                int old = pbx;
                pbx = withByte(pbx, addr - 0x20 + 2, v);
                pbx |= old;
                pbx &= 0xFFFF;
                if ((flags & FLAG_PBX) == 0) pbx = 0;
                intreq &= ~INT_PBX;
            }
            case 0x24, 0x25, 0x26, 0x27 -> {
                int old = flags;
                flags = withByte(flags, addr - 0x24, v);
                if ((flags & FLAG_ENABLE) != 0 && (old & FLAG_ENABLE) == 0) {
                    sectorCounter = 0;
                    intreq &= ~INT_OVERFLOW;
                }
                if ((flags & FLAG_PBX) == 0) pbx = 0;
                if ((flags & FLAG_SUBCODE) != 0 && (old & FLAG_SUBCODE) == 0) {
                    java.util.Arrays.fill(subcodeInUse, false);
                    subcodeRead = subcodeWrite = 0;
                }
                flags &= 0xFF800000;
            }
            case 0x28 -> {
                if ((flags & FLAG_TXD) == 0) {
                    intreq &= ~INT_DRIVEXMIT;
                    if (canSendCommand()) {
                        addCommandByte(v);
                        if (canSendCommand()) setStatus(INT_DRIVEXMIT);
                    }
                }
            }
            default -> { }
        }
        internal();
    }

    // ---------------------------------------------------------------------------------------
    // NVRAM
    // ---------------------------------------------------------------------------------------

    /** A line whose direction bit is clear is an input: the pull-up holds it high. */
    private void nvramWrite(int offset, int v) {
        if (offset == 0) {
            i2cScl = (i2cDirection & 0x80) == 0 || (v & 0x80) != 0;
            nvram.setScl(i2cScl ? 1 : 0);
            i2cSda = (i2cDirection & 0x40) == 0 || (v & 0x40) != 0;
            nvram.setSda(i2cSda ? 1 : 0);
        } else if (offset == 2) {
            i2cDirection = v;
        }
    }

    private int nvramRead(int offset) {
        if (offset == 0) {
            int v = nvram.setScl(i2cScl ? 1 : 0) != 0 ? 0x80 : 0;
            v |= nvram.setSda(i2cSda ? 1 : 0) != 0 ? 0x40 : 0;
            return v;
        }
        return offset == 2 ? i2cDirection : 0;
    }

    // ---------------------------------------------------------------------------------------
    // Chunky to planar
    // ---------------------------------------------------------------------------------------

    /**
     * Eight long words of chunky pixels, four 8-bit pixels each, become eight long words of
     * planar data: result {@code p} holds bit {@code p} of the 32 pixels, the first pixel in
     * its most significant bit.
     */
    private void c2pConvert() {
        for (int i = 0; i < 8; i++) {
            int r = 0;
            for (int k = 0; k < 32; k += 8) {
                int bit = i + k;
                for (int j = 0; j < 8; j++) {
                    if ((c2pBuffer[j] & (1 << bit)) != 0) r |= 1 << ((bit >> 3) + ((7 - j) << 2));
                }
            }
            c2pResult[i] = r;
        }
    }

    private void c2pWrite(int offset, int v) {
        if (offset == 3) c2pBuffer[c2pWriteOffset] = 0;
        c2pBuffer[c2pWriteOffset] |= v << (8 * (3 - offset));
        if (offset == 0) c2pWriteOffset = (c2pWriteOffset + 1) & 7;
        c2pReadOffset = -1;
    }

    private int c2pRead(int offset) {
        if (c2pReadOffset < 0) {
            c2pConvert();
            c2pReadOffset = 0;
        }
        c2pWriteOffset = 0;
        return (c2pResult[c2pReadOffset] >>> (8 * (3 - offset))) & 0xFF;
    }

    /** A read access of the C2P register moves to the next result. */
    private void c2pReadStep(int addr) {
        if (addr < 0x38 || addr >= 0x3C) return;
        c2pReadOffset = (c2pReadOffset + 1) & 7;
    }

    // ---------------------------------------------------------------------------------------
    // Interrupts
    // ---------------------------------------------------------------------------------------

    private void checkInterrupt() {
        if ((intreq & intena) != 0 && interruptSink != null) interruptSink.run();
    }

    private void setStatus(int status) {
        intreq |= status;
        checkInterrupt();
    }

    // ---------------------------------------------------------------------------------------
    // Drive: answers
    // ---------------------------------------------------------------------------------------

    private boolean canReturnData() {
        return receiveLength <= 0;
    }

    private void startReturnData(int len) {
        if (!canReturnData() || len <= 0) return;
        receiveLength = len;
        int checksum = 0xFF;
        for (int i = 0; i < receiveLength; i++) checksum -= resultBuffer[i];
        resultBuffer[receiveLength++] = checksum & 0xFF;
        receiveOffset = 0;
        if (packetObserver != null) packetObserver.packet(true, resultBuffer, receiveLength);
        setStatus(INT_DRIVERECV);
    }

    /** The receive DMA: answer bytes into the status ring until its end index is reached. */
    private void returnData() {
        if (receiveLength == 0) return;
        if ((flags & FLAG_RXD) == 0) return;
        if (rxIndex == rxCompare) return;
        if (rxDmaDelay > 0) return;
        while (receiveOffset < receiveLength) {
            lastRx = resultBuffer[receiveOffset];
            memory.writeByte(rxAddress + rxIndex, lastRx);
            rxIndex = (rxIndex + 1) & 0xFF;
            receiveOffset++;
            if (rxIndex == rxCompare) {
                setStatus(INT_RXDMADONE);
                break;
            }
        }
        if (receiveOffset == receiveLength) {
            receiveLength = 0;
            receiveOffset = 0;
            intreq &= ~INT_DRIVERECV;
            setStatus(INT_DRIVEXMIT);
        }
    }

    private int mediaStatus() {
        resultBuffer[0] = 0x0A;
        resultBuffer[1] = mediaPresent() ? 0x01 : 0x00;
        return 2;
    }

    private int commandLed() {
        int v = commandBuffer[1];
        led = v & 1;
        if ((v & 0x80) != 0) {
            resultBuffer[0] = command;
            resultBuffer[1] = led;
            return 2;
        }
        return 0;
    }

    private int commandStatus() {
        resultBuffer[1] = door;
        readToc();
        for (int i = 0; i < FIRMWARE.length; i++) resultBuffer[2 + i] = FIRMWARE[i] & 0xFF;
        resultBuffer[0] = command;
        initialized = 2;
        return 20;
    }

    private static final int TOC_REPEAT = 3;

    /** One entry of the table of contents; each entry is sent three times. */
    private int returnTocEntry() {
        resultBuffer[0] = 6;
        if (tocPoints == 0) {
            resultBuffer[1] = CDS_ERROR | door;
            return 15;
        }
        resultBuffer[1] = 0x0A;
        int base = (tocCounter / TOC_REPEAT) * 13;
        for (int i = 0; i < 13; i++) resultBuffer[2 + i] = tocBuffer[base + i] & 0xFF;
        resultBuffer[6] = bcd(99);
        resultBuffer[7] = bcd(24 + tocCounter / 75);
        resultBuffer[8] = bcd(tocCounter % 75);
        tocCounter++;
        if (tocCounter / TOC_REPEAT >= tocPoints) tocCounter = -1;
        return 15;
    }

    private static int bcd(int v) {
        v &= 0xFF;
        return ((v / 10) << 4) | (v % 10);
    }

    private static int fromBcd(int v) {
        return (v >> 4) * 10 + (v & 15);
    }

    /** MSF (minutes, seconds, frames packed in one int) of a logical sector. */
    private static int lsnToMsf(int lsn) {
        lsn += 150;
        int m = lsn / (60 * 75);
        lsn -= m * 60 * 75;
        int sec = lsn / 75;
        int f = lsn - sec * 75;
        return (m << 16) | (sec << 8) | f;
    }

    private static int msfToLsn(int msf) {
        return ((msf >> 16) & 0xFF) * 60 * 75 + ((msf >> 8) & 0xFF) * 75 + (msf & 0xFF) - 150;
    }

    /** Three BCD bytes of the command at {@code at}, as an MSF. */
    private int commandBcdMsf(int at) {
        return (fromBcd(commandBuffer[at]) << 16) | (fromBcd(commandBuffer[at + 1]) << 8)
                | fromBcd(commandBuffer[at + 2]);
    }

    private boolean checkError() {
        if (!disk) {
            resultBuffer[1] = ERR_NODISK | door;
            return true;
        }
        return false;
    }

    private int commandStop() {
        audioTimeout = 0;
        resultBuffer[0] = command;
        if (checkError()) return 2;
        resultBuffer[1] = 0;
        stopAudio();
        return 2;
    }

    private int commandPause() {
        audioTimeout = 0;
        tocCounter = -1;
        resultBuffer[0] = command;
        if (checkError()) return 2;
        resultBuffer[1] = (playing != 0 ? CDS_PLAYING : 0) | door;
        if (paused) return 2;
        paused = true;
        if (playing == 0) return 2;
        if (player != null) player.pause(true, clock.getAsLong());
        return 2;
    }

    private int commandUnpause() {
        resultBuffer[0] = command;
        if (checkError()) return 2;
        resultBuffer[1] = (playing != 0 ? CDS_PLAYING : 0) | door;
        if (!paused) return 2;
        paused = false;
        if (playing == 0) return 2;
        if (player != null) player.pause(false, clock.getAsLong());
        return 2;
    }

    /** Seek, then read data sectors or play audio. */
    private int commandMulti() {
        int seekPos = msfToLsn(commandBcdMsf(1));
        int endPos = msfToLsn(commandBcdMsf(4));
        if (playing != 0) stopAudio();
        paused = false;
        speed = (commandBuffer[8] & 0x40) != 0 ? 2 : 1;
        resultBuffer[0] = command;
        resultBuffer[1] = 0;
        if (!disk) {
            resultBuffer[1] = 1;
            return 2;
        }
        if ((commandBuffer[7] & 0x80) != 0) {
            dataOffset = seekPos;
            seekDelay = Math.abs(currentSector - dataOffset);
            if (seekDelay < 100) {
                seekDelay = 1;
            } else {
                seekDelay = Math.min(seekDelay / 1000 + 10, 100);
            }
            resultBuffer[1] |= 0x02;
        } else if (seekPos < 0) {
            tocCounter = 0;
        } else {
            tocCounter = -1;
            resultBuffer[1] = 0x42;
            playing = 1;
            if (!playAudio(seekPos, endPos)) audioTimeout = -3;
        }
        return 2;
    }

    private int playEndNotify(int status) {
        resultBuffer[0] = 4;
        if (status < 0) resultBuffer[1] = CDS_ERROR;
        else if (status == 0) resultBuffer[1] = CDS_PLAYING | 2;
        else resultBuffer[1] = CDS_PLAYEND;
        resultBuffer[1] |= door;
        return 2;
    }

    private int commandSubq() {
        resultBuffer[0] = command;
        resultBuffer[1] = 0;
        qcode(2);
        return 15;
    }

    /**
     * The position of the audio play, from the Q subchannel last read, into the answer at
     * {@code at}: control and address, track, index, track time, disc time. Zero unless audio
     * is playing or paused. Near the end of the disc the disc time is the lead-out's.
     */
    private void qcode(int at) {
        for (int i = 0; i < 11; i++) resultBuffer[at + i] = 0;
        int status = qcodeBuffer[1] & 0xFF;
        qcodeBuffer[2] = (byte) 0x80;
        if (!qcodeValid) return;
        if (audioStatus != CddaPlayer.STATUS_IN_PROGRESS && audioStatus != CddaPlayer.STATUS_PAUSED) return;
        int s = 4;
        int lastPlayPos = msfToLsn((fromBcd(qcodeBuffer[s + 7] & 0xFF) << 16)
                | (fromBcd(qcodeBuffer[s + 8] & 0xFF) << 8) | fromBcd(qcodeBuffer[s + 9] & 0xFF));
        qcodeBuffer[2] = 0;
        resultBuffer[at + 1] = qcodeBuffer[s] & 0xFF;
        resultBuffer[at + 2] = qcodeBuffer[s + 1] & 0xFF;
        resultBuffer[at + 3] = qcodeBuffer[s + 2] & 0xFF;
        resultBuffer[at + 4] = qcodeBuffer[s + 3] & 0xFF;
        resultBuffer[at + 5] = qcodeBuffer[s + 4] & 0xFF;
        resultBuffer[at + 6] = qcodeBuffer[s + 5] & 0xFF;
        resultBuffer[at + 8] = qcodeBuffer[s + 7] & 0xFF;
        resultBuffer[at + 9] = qcodeBuffer[s + 8] & 0xFF;
        resultBuffer[at + 10] = qcodeBuffer[s + 9] & 0xFF;
        if (status == CddaPlayer.STATUS_IN_PROGRESS) {
            int lastAddress = media.leadOut();
            if (lastPlayPos >= lastAddress || lastAddress - lastPlayPos < 10) {
                int msf = lsnToMsf(lastAddress);
                resultBuffer[at + 8] = bcd(msf >> 16);
                resultBuffer[at + 9] = bcd(msf >> 8);
                resultBuffer[at + 10] = bcd(msf);
            }
        }
    }

    /** Reads the Q subchannel of the play position into {@link #qcodeBuffer}. */
    private void readQcode() {
        if (player == null) return;
        java.util.Arrays.fill(qcodeBuffer, (byte) 0);
        qcodeBuffer[1] = (byte) player.takeQStatus();
        qcodeBuffer[3] = 12;
        byte[] q = qSubchannel(player.position());
        if (q != null) System.arraycopy(q, 0, qcodeBuffer, 4, 12);
        qcodeValid = true;
    }

    /** The status the player reports, as the firmware takes it. */
    private void playerStatus(int status) {
        if (status < 0) return;
        if (audioStatus != status) {
            if (status == CddaPlayer.STATUS_IN_PROGRESS) {
                if (playing == 0) playing = 1;
                audioTimeout = 1;
            }
            if (playing != 0 && status != CddaPlayer.STATUS_IN_PROGRESS
                    && status != CddaPlayer.STATUS_PAUSED && status != CddaPlayer.STATUS_NOT_SUPPORTED) {
                audioTimeout = -1;
            }
        }
        audioStatus = status;
    }

    /** The subchannel of a sector the player has read, queued for the subcode DMA. */
    private void playerSubcode(byte[] interleaved) {
        if ((flags & FLAG_SUBCODE) == 0) return;
        if (subcodeInUse[subcodeWrite]) {
            java.util.Arrays.fill(subcodeInUse, false);
            subcodeWrite = subcodeRead = 0;
            return;
        }
        subcodeInUse[subcodeWrite] = true;
        System.arraycopy(interleaved, 0, subcodeRing[subcodeWrite], 0, 96);
        subcodeWrite = (subcodeWrite + 1) % SUBCODE_RING;
    }

    // ---------------------------------------------------------------------------------------
    // Drive: commands
    // ---------------------------------------------------------------------------------------

    private boolean canSendCommand() {
        return initialized != 0 && commandActive == 0 && receiveLength == 0;
    }

    private void addCommandByte(int b) {
        commandBuffer[commandLength++] = b & 0xFF;
        command = commandBuffer[0];
        int len = COMMAND_LENGTHS[command & 0x0F];
        checksumError = false;
        unknownCommand = false;
        if (len < 0) {
            unknownCommand = true;
            commandActive = 1;
            return;
        }
        if (len + 1 > commandLength) return;
        int checksum = 0;
        for (int i = 0; i < len + 1; i++) checksum += commandBuffer[i];
        if ((checksum & 0xFF) != 0xFF) checksumError = true;
        commandActive = 1;
        commandLength = len;
        if (packetObserver != null) packetObserver.packet(false, commandBuffer, len + 1);
    }

    /** The transmit DMA: one command byte from the command ring per call. */
    private void runCommand() {
        if ((flags & FLAG_TXD) == 0) return;
        if ((flags & FLAG_ENABLE) != 0) return;
        if (txIndex == txCompare) return;
        if (txDmaDelay > 0) return;
        if (!canSendCommand()) return;
        int b = memory.readByte(txAddress + txIndex);
        addCommandByte(b);
        txIndex = (txIndex + 1) & 0xFF;
        if (txIndex == txCompare) setStatus(INT_TXDMADONE);
    }

    private void executeCommand() {
        commandLength = 0;
        commandActive = 0;
        java.util.Arrays.fill(resultBuffer, 0);
        if (checksumError || unknownCommand) {
            resultBuffer[0] = (command & 0xF0) | 5;
            resultBuffer[1] |= (checksumError ? ERR_CHECKSUM : ERR_BADCOMMAND) | door;
            startReturnData(2);
            return;
        }
        int len = switch (command & 0x0F) {
            case 0 -> { resultBuffer[0] = command; yield 1; }
            case 1 -> commandStop();
            case 2 -> commandPause();
            case 3 -> commandUnpause();
            case 4 -> commandMulti();
            case 5 -> commandLed();
            case 6 -> commandSubq();
            case 7 -> commandStatus();
            default -> 0;
        };
        if (len == 0) {
            setStatus(INT_DRIVEXMIT);
            return;
        }
        startReturnData(len);
    }

    /** Runs after every access to the CD registers and at every line. */
    private void internal() {
        if (player != null) player.advanceTo(clock.getAsLong());
        returnData();
        runCommand();
        if (commandActive > 0) {
            commandActive--;
            if (commandActive == 0) executeCommand();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Drive: disc and audio
    // ---------------------------------------------------------------------------------------

    private boolean mediaPresent() {
        return media != null;
    }

    /** Reads the table of contents of the disc into the form the drive sends. */
    private void readToc() {
        tocCounter = -1;
        if (media == null) return;
        toc = media.toc();
        tocPoints = toc.size();
        java.util.Arrays.fill(tocBuffer, (byte) 0);
        for (int j = 0; j < tocPoints; j++) {
            CdImage.TocEntry e = toc.get(j);
            int msf = lsnToMsf(e.address());
            if (e.point() == 0xA0 || e.point() == 0xA1) msf = e.track() << 16;
            int d = j * 13;
            tocBuffer[d + 1] = (byte) (e.adr() | (e.control() << 4));
            tocBuffer[d + 3] = (byte) (e.point() < 100 ? bcd(e.point()) : e.point());
            tocBuffer[d + 8] = (byte) bcd(msf >> 16);
            tocBuffer[d + 9] = (byte) bcd(msf >> 8);
            tocBuffer[d + 10] = (byte) bcd(msf);
        }
    }

    /** True if {@code sector} lies in a data track. */
    private boolean isValidDataSector(int sector) {
        for (int i = 0; i < toc.size(); i++) {
            CdImage.TocEntry e = toc.get(i);
            if (e.point() < 1 || e.point() > 99) continue;
            if ((e.control() & 0x0C) != 4) continue;
            CdImage.TocEntry next = toc.get(i + 1);
            if (sector >= e.address() && (sector < next.address() || next.point() >= 0xA1)) return true;
        }
        return false;
    }

    /** Index in the table of contents of the track {@code lsn} falls in, -1 past the last one. */
    private int trackEntryAt(int lsn) {
        int last = 0;
        for (int i = 0; i < toc.size(); i++) {
            if (toc.get(i).point() >= 1 && toc.get(i).point() <= 99) last = i;
        }
        for (int i = 2; i <= last + 1 && i < toc.size(); i++) {
            if (lsn < toc.get(i).address()) return i - 1;
        }
        return -1;
    }

    /**
     * Starts playing audio from {@code start} to {@code end}; a start inside a data track
     * moves to the next track. The drive reports the start ten lines later.
     */
    private boolean playAudio(int start, int end) {
        if (tocPoints == 0) return false;
        int s = trackEntryAt(start);
        if (s >= 0 && (toc.get(s).control() & 0x0C) == 0x04) {
            s = trackEntryAt(start + 150);
            if (s >= 0 && (toc.get(s).control() & 0x0C) == 0x04) {
                s++;
                start = toc.get(s).address();
                s++;
                end = toc.get(s).address();
            }
            if (s >= 0) start = toc.get(s).address();
        }
        qcodeValid = false;
        lastPlayEnd = end;
        audioTimeout = 10;
        paused = false;
        if (player != null) {
            player.pause(false, clock.getAsLong());
            player.play(start, end, clock.getAsLong());
        }
        return true;
    }

    // ---------------------------------------------------------------------------------------
    // Drive: data sectors
    // ---------------------------------------------------------------------------------------

    /**
     * Transfers the next sector into the highest requested block of the data area, if it is
     * in the read-ahead buffer; then lets the drive refill the buffer around the sector it is
     * on, so a sector missed now is there at the next sector time.
     */
    private void runRead() {
        if ((flags & FLAG_ENABLE) == 0) return;
        if (pbx == 0) return;
        if ((flags & FLAG_PBX) == 0) return;
        if (dataOffset < 0) return;
        if (media == null) return;
        int seccnt;
        for (seccnt = 15; seccnt >= 0; seccnt--) {
            if ((pbx & (1 << seccnt)) != 0) break;
        }
        int sector = currentSector = dataOffset + sectorCounter;
        int sec = sector - bufferStart;
        boolean inc = false;
        if (bufferStart >= 0 && sec >= 0 && sec < SECTOR_BUFFER) {
            int info = bufferInfo[sec] & 0xFF;
            if (info != 0xFF && info != 0) {
                int base = addressData + seccnt * 4096;
                int src = sec * 2352;
                memory.writeByte(base, 0);
                memory.writeByte(base + 1, 0);
                memory.writeByte(base + 2, 0);
                memory.writeByte(base + 3, sectorCounter & 31);
                for (int i = 4; i < 2352; i++) memory.writeByte(base + i, bufferData[src + i]);
                for (int i = 0; i < 73 * 2; i++) memory.writeByte(base + 0xC00 + i, 0);
                pbx &= ~(1 << seccnt);
                setStatus(INT_PBX);
                if ((flags & FLAG_SUBCODE) != 0) writeSubcode(sector);
                inc = true;
            }
            if (info != 0xFF) bufferInfo[sec]--;
        }
        if (inc) sectorCounter++;
        fillBuffer();
    }

    /** The Q subchannel of {@code sector}, interleaved, into the next half of the subcode area. */
    private void writeSubcode(int sector) {
        byte[] sub = new byte[96];
        byte[] q = qSubchannel(sector);
        if (q != null) {
            byte[] deinterleaved = new byte[96];
            System.arraycopy(q, 0, deinterleaved, 12, 12);
            subToInterleaved(deinterleaved, sub);
        }
        subcodeOffset = subcodeOffset >= 128 ? 0 : 128;
        int base = subcodeAddress + subcodeOffset;
        for (int i = 0; i < 96; i++) memory.writeByte(base + i, sub[i]);
        memory.writeByte(base + 96, 0xFF);
        memory.writeByte(base + 97, 0xFF);
        memory.writeByte(base + 98, 0);
        memory.writeByte(base + 99, 0);
        subcodeOffset = (subcodeOffset + 100) & 0xFF;
        setStatus(INT_SUBCODE);
    }

    /**
     * The Q subchannel of {@code sector}: control and address, track number, index 1, the
     * position in the track and on the disc.
     */
    private byte[] qSubchannel(int sector) {
        List<CdImage.Track> tracks = media.tracks();
        int n = tracks.size();
        int td = -1;
        for (int trk = 0; trk <= n; trk++) {
            int addr = trk < n ? tracks.get(trk).address : media.leadOut();
            if (sector < addr) {
                td = trk > 0 ? trk - 1 : 0;
                break;
            }
            td = trk;
            int next = trk + 1 < n ? tracks.get(trk + 1).address : media.leadOut();
            if (trk < n && sector < next) break;
        }
        if (td < 0) return null;
        int ctrl = td < n ? tracks.get(td).ctrl : 0;
        int adr = td < n ? tracks.get(td).adr : 0;
        int address = td < n ? tracks.get(td).address : media.leadOut();
        byte[] s = new byte[12];
        s[0] = (byte) ((ctrl << 4) | adr);
        s[1] = (byte) bcd(td + 1);
        s[2] = (byte) bcd(1);
        int msf = lsnToMsf(sector - address - 150);
        s[3] = (byte) bcd(msf >> 16);
        s[4] = (byte) bcd(msf >> 8);
        s[5] = (byte) bcd(msf);
        msf = lsnToMsf(sector);
        s[7] = (byte) bcd(msf >> 16);
        s[8] = (byte) bcd(msf >> 8);
        s[9] = (byte) bcd(msf);
        return s;
    }

    /** Eight 12-byte channels P..W into 96 bytes holding one bit of each channel. */
    private static void subToInterleaved(byte[] s, byte[] d) {
        for (int i = 0; i < 96; i++) {
            int dmask = 0x80;
            int smask = 1 << (7 - (i & 7));
            int v = 0;
            for (int j = 0; j < 8; j++) {
                if ((s[(i / 8) + j * 12] & smask) != 0) v |= dmask;
                dmask >>= 1;
            }
            d[i] = (byte) v;
        }
    }

    /**
     * The drive's read-ahead: when the sector it is on is a data sector outside the first two
     * thirds of the buffer, it rebuilds the buffer from that sector, keeping what it already
     * holds, and reads up to 128 sectors of the data track.
     */
    private void fillBuffer() {
        int sector = currentSector;
        int secnum;
        for (secnum = 0; secnum < SECTOR_BUFFER; secnum++) {
            if ((bufferInfo[secnum] & 0xFF) == 0xFF) break;
        }
        if (sector < 0 || !isValidDataSector(sector)) return;
        if (!(bufferStart < 0 || sector < bufferStart || sector >= bufferStart + SECTOR_BUFFER * 2 / 3
                || secnum != SECTOR_BUFFER)) {
            return;
        }
        java.util.Arrays.fill(bufferInfo2, (byte) 0);
        bufferStart2 = sector;
        secnum = 0;
        if (bufferStart >= 0 && sector >= bufferStart && sector < bufferStart + SECTOR_BUFFER) {
            int secoff = sector - bufferStart;
            while (secoff < SECTOR_BUFFER) {
                System.arraycopy(bufferData, secoff * 2352, bufferData2, secnum * 2352, 2352);
                bufferInfo2[secnum] = 3;
                secnum++;
                secoff++;
            }
        }
        int blocks;
        if (!isValidDataSector(sector + SECTOR_BUFFER - 1)) {
            for (blocks = SECTOR_BUFFER - 1; blocks > secnum; blocks--) {
                if (isValidDataSector(sector + blocks)) break;
            }
        } else {
            blocks = SECTOR_BUFFER - secnum;
        }
        if (blocks != 0) {
            if (readBlocks(sector, blocks, bufferData2, 0)) {
                for (int i = 0; i < SECTOR_BUFFER; i++) bufferInfo2[i] = (byte) (i < blocks ? 3 : 0);
            } else {
                for (int offset = secnum; offset < SECTOR_BUFFER; offset++) {
                    boolean readOk = isValidDataSector(sector)
                            && readBlocks(sector, 1, bufferData2, offset * 2352);
                    bufferInfo2[offset] = (byte) (readOk ? 3 : 0);
                    sector++;
                }
            }
        }
        if (blocks != 0 || secnum != 0) {
            byte[] t = bufferInfo;
            bufferInfo = bufferInfo2;
            bufferInfo2 = t;
            t = bufferData;
            bufferData = bufferData2;
            bufferData2 = t;
            int ts = bufferStart;
            bufferStart = bufferStart2;
            bufferStart2 = ts;
        }
    }

    /** {@code count} raw sectors from {@code sector}, all read as sectors of the first one's track. */
    private boolean readBlocks(int sector, int count, byte[] dst, int at) {
        try {
            return media.readRawRun(sector, count, dst, at);
        } catch (IOException e) {
            return false;
        }
    }

    /** The firmware stops the audio: its state and the player. */
    private void stopAudio() {
        audioStatus = 0;
        audioTimeout = 0;
        paused = false;
        playing = 0;
        stopPlayer();
    }

    private void stopPlayer() {
        qcodeValid = false;
        if (player != null) {
            player.pause(false, clock.getAsLong());
            player.stop();
        }
    }

    /** The drive's own messages, at most one started per line. */
    private void handler(boolean frameSync) {
        if (receiveLength != 0) return;
        if (initialized == 0) {
            if (mediaPresent()) startReturnData(mediaStatus());
            initialized = 1;
            return;
        }
        if (initialized < 2) return;
        if (mediaChanged) {
            if (canReturnData()) {
                startReturnData(mediaStatus());
                mediaChanged = false;
                readToc();
            }
            return;
        }
        if (audioTimeout > 1) audioTimeout--;
        if (audioTimeout == 1 && canReturnData()) {
            if (playing == 0) playing = 1;
            if (playing == 1) startReturnData(playEndNotify(0));
            playing = 2;
            audioTimeout = 0;
        }
        if (audioTimeout == -1) {
            if (playing != 0) {
                stopAudio();
                audioTimeout = -2;
            } else {
                audioTimeout = 0;
            }
        }
        if (audioTimeout == -2 && canReturnData()) {
            startReturnData(playEndNotify(1));
            audioTimeout = 0;
        }
        if (audioTimeout == -3 && canReturnData()) {
            startReturnData(playEndNotify(-1));
            audioTimeout = 0;
        }
        if (tocCounter >= 0 && commandActive == 0 && frameSync && canReturnData()) {
            startReturnData(returnTocEntry());
        }
    }

    /** The drive looks for a disc change every two seconds. */
    private void checkMedia() {
        int media = mediaPresent() ? 1 : 0;
        if (media != lastMediaState) {
            lastMediaState = media;
            disk = media != 0;
            mediaChanged = true;
            stopPlayer();
        }
    }

    /**
     * One line of Akiko's time. A sector lasts {@code linesPerFrame * frameRate / 75} lines at
     * single speed, the time base of the sector, frame and subcode clocks.
     */
    public void hsync(int linesPerFrame, float frameRate) {
        if (player != null) player.advanceTo(clock.getAsLong());
        float sectorLines = (float) linesPerFrame * frameRate / (75.0f * speed);
        sectorClock--;
        if (sectorClock <= 0) {
            if (seekDelay <= 0) runRead();
            else seekDelay--;
            sectorClock += sectorLines;
        }
        boolean frameSync = false;
        frameClock--;
        if (frameClock <= 0) {
            frameClock += sectorLines;
            frameSync = true;
        }
        if (txDmaDelay > 0) txDmaDelay--;
        if (rxDmaDelay > 0) rxDmaDelay--;
        subcodeClock--;
        if (subcodeClock <= 0) {
            if ((flags & FLAG_SUBCODE) != 0 && playing != 0 && subcodeRead != subcodeWrite) {
                if (subcodeInUse[subcodeRead]) {
                    subcodeOffset = subcodeOffset >= 128 ? 0 : 128;
                    int base = subcodeAddress + subcodeOffset;
                    for (int i = 0; i < 96; i++) memory.writeByte(base + i, subcodeRing[subcodeRead][i]);
                    memory.writeByte(base + 96, 0xFF);
                    memory.writeByte(base + 97, 0xFF);
                    memory.writeByte(base + 98, 0);
                    memory.writeByte(base + 99, 0);
                    subcodeInUse[subcodeRead] = false;
                    subcodeOffset = (subcodeOffset + 100) & 0xFF;
                    subcodeRead = (subcodeRead + 1) % SUBCODE_RING;
                    setStatus(INT_SUBCODE);
                }
            }
            subcodeClock = (int) ((float) linesPerFrame * frameRate / (75 * speed) - 5);
        }
        if (qcodeCounter > 0) qcodeCounter--;
        if (mediaCheckCounter > 0) mediaCheckCounter--;
        if (qcodeCounter <= 0) {
            qcodeCounter = 312 * 50 / 2;
            if (media != null) readQcode();
        }
        if (mediaCheckCounter <= 0) {
            mediaCheckCounter = 312 * 50 * 2;
            checkMedia();
        }
        internal();
        handler(frameSync);
    }
}
