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

import io.github.zolfrik.amija.hardware.custom.cd.CddaPlayer;
import io.github.zolfrik.amija.media.CdImage;

import java.io.IOException;
import java.util.List;

/**
 * The CDTV's DMAC: a hardwired Zorro II board of 64 KB holding the DMA controller, a 6525
 * TPI and the port of the Matsushita CD drive.
 *
 * <ul>
 *   <li>{@code $00-$3F}: the AutoConfig descriptor (product 3 of Commodore, {@code $0202}).</li>
 *   <li>{@code $41} ISTR, {@code $43} CNTR, {@code $80-$83} WTC (word count),
 *       {@code $84-$87} ACR (address), {@code $8E-$8F} DAWR.</li>
 *   <li>{@code $A1}: the drive's data port: a write is a command byte, a read a byte of the
 *       answer.</li>
 *   <li>{@code $B0-$BF}: the 6525, registers 0-7 at the even addresses.</li>
 *   <li>{@code $E0/$E1} start the DMA, {@code $E2/$E3} stop it, {@code $E4/$E5} clear ISTR.</li>
 * </ul>
 *
 * <p>The drive takes a command byte by byte and executes it when the last byte arrives; the
 * answer is announced on the next line by the STEN line of the 6525. A data read moves the
 * whole transfer into Chip RAM when the DMA starts, without bus slots, and reports its end
 * after the time the drive takes to deliver that many words at single speed (75 sectors per
 * second), counted in lines. While the motor runs the drive raises a subchannel frame
 * interrupt (SCOR) every 1/75 s; during CD audio play the subchannel bytes follow one by one
 * (SBCP). Everything happens in emulated time: {@link #hsync} runs once per line.
 *
 * <p>The board's interrupt, and the 6525's, is INTREQ PORTS.
 */
public final class CdtvDmac implements AutoConfigDevice {

    /** Byte write to Chip RAM, for the DMA (no bus slots). */
    public interface Memory {
        void writeByte(int address, int value);
    }

    // CNTR bits.
    private static final int CNTR_TCEN = 1 << 7;
    private static final int CNTR_PREST = 1 << 6;
    private static final int CNTR_PDMD = 1 << 5;
    private static final int CNTR_INTEN = 1 << 4;
    // ISTR bits.
    private static final int ISTR_E_INT = 1 << 5;
    private static final int ISTR_INT_P = 1 << 4;
    private static final int ISTR_FE_FLG = 1 << 0;

    private static final byte[] MODEL_NAME = "MATSHITA0.96".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);

    // Audio status of the drive (the Q subchannel's).
    private static final int AUDIO_STATUS_NOT_SUPPORTED = 0x00;
    private static final int AUDIO_STATUS_IN_PROGRESS = 0x11;
    private static final int AUDIO_STATUS_PAUSED = 0x12;
    private static final int AUDIO_STATUS_PLAY_COMPLETE = 0x13;
    private static final int AUDIO_STATUS_PLAY_ERROR = 0x14;
    private static final int AUDIO_STATUS_NO_STATUS = 0x15;

    private static final int SUB_CHANNEL_SIZE = 96;
    private static final int MAX_SUBCODEBUFFER = 36;
    /** Colour clocks between two subchannel bytes. */
    private static final int SUBCODE_CYCLES = 460;

    private transient Memory memory;
    private Runnable interruptSink;

    // AutoConfig.
    private final int[] dmacMemory = new int[128];
    private int configured;
    private int baseAddr;

    // DMAC.
    private int dmacIstr;
    private int dmacCntr;
    private int dmacDawr;
    private int dmacAcr;
    private int dmacWtc;
    private boolean dmacDma;
    private long dmaWait;
    private boolean dmaFinished;
    /** A DMA started while the previous one is still being reported: it starts when that is. */
    private boolean dmaDeferred;

    // The drive.
    private final int[] commandInput = new int[16];
    private int commandCntIn;
    private final int[] commandOutput = new int[16];
    private int commandCntOut = -1;
    private int commandSizeOut;
    private int lastOut;
    private boolean commandDone;
    private boolean activateStch;
    private int cdromOffset;
    private int cdromLength;
    private boolean cdPlaying;
    private boolean cdPaused;
    private boolean cdMotor;
    private boolean cdMedia;
    private boolean cdError;
    private boolean cdFinished;
    private boolean cdIsReady;
    private boolean cdAudioFinished;
    private int cdAudioStatus;
    private int lastCdPosition;
    private int playStart;
    private int playEnd;
    private int lastPlayPos;
    private int lastPlayEnd;
    private int sectorSize;
    private boolean frontPanel;
    private int cdLed;
    private final int[] qcode = new int[16];
    private int readSector = -1;
    private boolean didRead;
    private transient byte[] sectorBuffer = new byte[2352];
    /** Where the unit's head is, for the Q subchannel when no play runs. */
    private int unitLastPos;
    /** True while the unit's position is the player's (a play started after the last read). */
    private boolean unitPositionFromPlayer;

    // Volume DAC.
    private int cdVolume;
    private int cdVolumeStored;
    private int dacControlDataFormat;
    private boolean volStrobe1;
    private boolean volStrobe2;

    // 6525 TPI.
    private int tpA, tpB, tpC, tpAd, tpBd, tpCd;
    private int tpImask, tpCr, tpAir, tpIlatch, tpIlatch2;
    /** Lines toward the 6525's port C: 1 asserted, a negative count after it was seen, 0 off. */
    private int stch, sten, scor, sbcp;

    // Subchannel.
    private final byte[] subcodeBuffer = new byte[MAX_SUBCODEBUFFER * SUB_CHANNEL_SIZE];
    private final boolean[] subcodeBufferInUse = new boolean[MAX_SUBCODEBUFFER];
    private int subcodeBufferOffset;
    private int subcodeBufferOffsetW;
    private int subcodeOffset = -1;
    private final byte[] subTransferBuf = new byte[SUB_CHANNEL_SIZE];
    private int subcodeActiveCnt;
    /** The colour clock of the next subchannel byte step, or -1 when none is due. */
    private long subcodeEventCck = -1;

    // Line counters; a reset leaves them running.
    private int cdtvHsync;
    private int subqCnt;
    private int subchannelCounter;

    // The disc.
    private transient CdImage media;
    private transient List<CdImage.TocEntry> toc = List.of();
    private transient CddaPlayer player;
    private java.util.function.LongSupplier clock = () -> 0L;
    private boolean ntscClock;

    /**
     * Seconds the drive stays empty when the disc is changed: the caddy comes out, the disc is
     * swapped, the caddy goes back in. The drive only knows whether a disc is there, so a disc
     * replaced with no empty interval would go unnoticed.
     */
    public static final int DISC_CHANGE_SECONDS = 3;
    /** The disc waiting to go in after a change, and the lines counted since the change. */
    private transient CdImage pendingDisc;
    private transient int pendingDiscLines = -1;

    public CdtvDmac() {
        java.util.Arrays.fill(dmacMemory, 0xFF);
        ew(0x00, 0xC0 | 0x01);
        ew(0x04, 0x03);
        ew(0x08, 0x40);
        ew(0x10, 0x02);
        ew(0x14, 0x02);
        ew(0x18, 0x00);
        ew(0x1C, 0x00);
        ew(0x20, 0x00);
        ew(0x24, 0x00);
        powerOn();
    }

    private void ew(int addr, int value) {
        if (addr == 0x00 || addr == 0x02 || addr == 0x40 || addr == 0x42) {
            dmacMemory[addr] = value & 0xF0;
            dmacMemory[addr + 2] = (value & 0x0F) << 4;
        } else {
            dmacMemory[addr] = ~(value & 0xF0) & 0xFF;
            dmacMemory[addr + 2] = ~((value & 0x0F) << 4) & 0xFF;
        }
    }

    /** Where the DMA writes. */
    public void setMemory(Memory memory) {
        this.memory = memory;
    }

    /** Called when the board asserts its interrupt. */
    public void setInterruptSink(Runnable sink) {
        this.interruptSink = sink;
    }

    /** The time base of the audio player (units of 1/512 colour clock) and the video standard. */
    public void setClock(java.util.function.LongSupplier unitsClock, boolean ntsc) {
        this.clock = unitsClock;
        this.ntscClock = ntsc;
    }

    /**
     * Puts a disc in the drive ({@code null} empties it) at once, cancelling a pending change;
     * the drive notices at its next poll.
     */
    public void insert(CdImage image) {
        pendingDisc = null;
        pendingDiscLines = -1;
        if (player != null) player.stop();
        media = image;
        toc = image == null ? List.of() : image.toc();
        didRead = false;
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

    /**
     * Changes the disc as a user does: an empty drive takes {@code next} at once; a drive
     * holding a disc is emptied now and takes {@code next} {@value #DISC_CHANGE_SECONDS}
     * seconds later.
     */
    public void changeDisc(CdImage next) {
        if (media == null) {
            insert(next);
            return;
        }
        insert(null);
        pendingDisc = next;
        pendingDiscLines = 0;
    }

    public CdImage media() {
        return media;
    }

    public CddaPlayer player() {
        return player;
    }

    /**
     * After a state load: the table of contents and the sector being transferred come back
     * from the disc in the drive (only the sector's number is state).
     */
    public void stateLoaded() {
        toc = media == null ? List.of() : media.toc();
        if (sectorBuffer == null) sectorBuffer = new byte[2352];
        if (didRead && !fetchSector(readSector)) didRead = false;
    }

    /** Plays the drive's audio up to the present instant. */
    public void syncAudio() {
        if (player != null) player.advanceTo(clock.getAsLong());
    }

    /** The audio the drive plays now, at the volume of the DAC. */
    public short audioLeft() {
        return player == null ? 0 : scaled(player.left());
    }

    public short audioRight() {
        return player == null ? 0 : scaled(player.right());
    }

    private short scaled(short s) {
        int v = (cdVolume << 5) | (cdVolume >> 5);
        return (short) ((s * v) >> 15);
    }

    /** The drive's activity light: a DMA running or reported, or CD audio playing. */
    public boolean ledLit() {
        return dmacDma || dmaFinished || cdPlaying;
    }

    /** True while the DMAC or the 6525 holds its interrupt request. */
    public boolean interruptAsserted() {
        return ((dmacCntr & CNTR_INTEN) != 0 && (dmacIstr & ISTR_E_INT) != 0)
                || (tpIlatch & (1 << 5)) != 0;
    }

    private void int2() {
        if (interruptSink != null) interruptSink.run();
    }

    // ---------------------------------------------------------------------------------------
    // Reset
    // ---------------------------------------------------------------------------------------

    private void powerOn() {
        configured = 0;
        baseAddr = 0;
        commandCntOut = -1;
        resetDrive();
        tpA = tpB = tpC = tpAd = tpBd = tpCd = 0;
        tpImask = tpCr = tpAir = tpIlatch = 0;
        stch = 0;
        sten = 0;
        scor = 0;
        sbcp = 0;
    }

    /** System reset: the board waits for AutoConfig again, its registers are cleared. */
    @Override
    public void reset() {
        powerOn();
    }

    /** The drive's reset, by CNTR PREST or at power-on. */
    private void resetDrive() {
        cdAudioStop();
        cdPlaying = cdPaused = false;
        cdMotor = false;
        cdMedia = false;
        cdError = false;
        cdFinished = false;
        cdLed = 0;
        stch = 1;
        frontPanel = true;
    }

    // ---------------------------------------------------------------------------------------
    // AutoConfig
    // ---------------------------------------------------------------------------------------

    @Override
    public boolean autoconfiguring() {
        return configured == 0;
    }

    @Override
    public int peekConfigByte(int addr) {
        // Unconfigured, the board answers in the AutoConfig page as in its own: the
        // descriptor below $40, its registers above.
        return readByte(addr);
    }

    @Override
    public boolean pokeConfigByte(int addr, int value) {
        switch (addr & 0xFF) {
            case 0x4A:
                baseAddr |= (value & 0xF0) << 12;
                return false;
            case 0x48:
                baseAddr |= (value & 0xF0) << 16;
                configured = 1;
                return true;
            case 0x4C:
                configured = -1;
                return false;
            default:
                return false;
        }
    }

    @Override
    public int configuredBaseAddr() {
        return baseAddr;
    }

    /** True once the Kickstart has given the board its page. */
    public boolean mapped() {
        return configured > 0;
    }

    // ---------------------------------------------------------------------------------------
    // Bus
    // ---------------------------------------------------------------------------------------

    public int readWord(int addr) {
        return (readByte(addr) << 8) | readByte(addr + 1);
    }

    public void writeWord(int addr, int value) {
        writeByte(addr, (value >> 8) & 0xFF);
        writeByte(addr + 1, value & 0xFF);
    }

    public int readByte(int addr) {
        addr &= 0xFFFF;
        if (addr < 0x40) return dmacMemory[addr];
        if (addr >= 0xB0 && addr < 0xC0) return tpRead((addr - 0xB0) / 2);
        int v = 0;
        switch (addr) {
            case 0x41 -> {
                v = dmacIstr;
                if (v != 0) v |= ISTR_INT_P;
                dmacIstr &= ~0x0F;
            }
            case 0x43 -> v = dmacCntr;
            case 0xA1 -> {
                sten = 0;
                if (commandCntOut >= 0) {
                    v = lastOut = commandOutput[commandCntOut];
                    commandOutput[commandCntOut++] = 0;
                    if (commandCntOut >= commandSizeOut) {
                        commandSizeOut = 0;
                        commandCntOut = -1;
                        sten = 0;
                    } else {
                        sten = 1;
                    }
                    tpCheckInterrupts();
                } else {
                    v = lastOut;
                }
            }
            case 0xE8, 0xE9 -> dmacIstr |= ISTR_FE_FLG;
            case 0xA3, 0xA5, 0xA7 -> v = 0xFF;
            default -> { }
        }
        return v & 0xFF;
    }

    public void writeByte(int addr, int b) {
        addr &= 0xFFFF;
        b &= 0xFF;
        if (configured <= 0) return;
        if (addr >= 0xB0 && addr < 0xC0) {
            tpWrite((addr - 0xB0) / 2, b);
            return;
        }
        switch (addr) {
            case 0x43 -> {
                dmacCntr = b;
                if ((dmacCntr & CNTR_PREST) != 0) resetDrive();
            }
            case 0x80 -> dmacWtc = (dmacWtc & 0x00FFFFFF) | (b << 24);
            case 0x81 -> dmacWtc = (dmacWtc & 0xFF00FFFF) | (b << 16);
            case 0x82 -> dmacWtc = (dmacWtc & 0xFFFF00FF) | (b << 8);
            case 0x83 -> dmacWtc = (dmacWtc & 0xFFFFFF00) | b;
            case 0x84 -> dmacAcr = (dmacAcr & 0x00FFFFFF) | (b << 24);
            case 0x85 -> dmacAcr = (dmacAcr & 0xFF00FFFF) | (b << 16);
            case 0x86 -> dmacAcr = (dmacAcr & 0xFFFF00FF) | (b << 8);
            case 0x87 -> dmacAcr = (dmacAcr & 0xFFFFFF01) | (b & ~1);
            case 0x8E -> dmacDawr = (dmacDawr & 0x00FF) | (b << 8);
            case 0x8F -> dmacDawr = (dmacDawr & 0xFF00) | b;
            case 0xA1 -> commandByte(b);
            case 0xE0, 0xE1 -> {
                if (!dmacDma) {
                    dmacDma = true;
                    startDma();
                }
            }
            case 0xE2, 0xE3 -> {
                dmacDma = false;
                dmaFinished = false;
            }
            case 0xE4, 0xE5 -> {
                dmacIstr = 0;
                checkInt();
            }
            case 0xE8, 0xE9 -> dmacIstr |= ISTR_FE_FLG;
            default -> { }
        }
        tpCheckInterrupts();
    }

    private void checkInt() {
        if ((dmacCntr & CNTR_INTEN) != 0 && (dmacIstr & ISTR_E_INT) != 0) int2();
    }

    /** The interrupt lines looked at again (after a write to INTREQ). */
    public void rethink() {
        checkInt();
        tpCheckInterrupts();
    }

    // ---------------------------------------------------------------------------------------
    // 6525
    // ---------------------------------------------------------------------------------------

    private int tpC() {
        return (sbcp != 0 ? 0 : 1) | (scor != 0 ? 0 : 2) | (stch != 0 ? 0 : 4) | (sten != 0 ? 0 : (8 | 16));
    }

    private int tpCLevel() {
        int v = (sbcp == 1 ? 0 : 1) | (scor == 1 ? 0 : 2) | (stch == 1 ? 0 : 4) | (sten == 1 ? 0 : 8) | 16;
        if (sten == 1) sten = -1;
        if (scor == 1) scor = -1;
        if (sbcp == 1) sbcp = -1;
        return v;
    }

    private void tpCheckInterrupts() {
        if ((tpCr & 1) != 1) {
            tpCLevel();
            return;
        }
        tpIlatch |= tpCLevel() ^ 0x1F;
        stch = 0;
        if ((tpIlatch & (1 << 5)) == 0 && (tpIlatch & tpImask) != 0) {
            tpAir = 0;
            int mask = 0x10;
            while (((tpIlatch & tpImask) & mask) == 0) mask >>= 1;
            tpAir |= tpIlatch & mask;
            tpIlatch |= 1 << 5;
            tpIlatch2 = tpIlatch & mask;
            tpIlatch &= ~mask;
        }
        if ((tpIlatch & (1 << 5)) != 0) int2();
    }

    private void tpWrite(int reg, int v) {
        switch (reg) {
            case 0 -> tpA = v;
            case 1 -> tpB = v;
            case 2 -> {
                if ((tpCr & 1) != 0) {
                    tpIlatch &= 0xE0 | v;
                } else {
                    tpC = tpC() & ~tpCd;
                    tpC |= v & tpCd;
                    if ((tpC & (1 << 5)) != 0) int2();
                }
            }
            case 3 -> tpAd = v;
            case 4 -> tpBd = v;
            case 5 -> {
                if ((tpCr & 1) != 0) tpImask = v & 0x1F;
                else tpCd = v;
            }
            case 6 -> tpCr = v;
            case 7 -> tpAir = v;
            default -> { }
        }
        // Port B bit 5 is the DAC data, bit 6 its clock, bit 7 the strobe that loads the volume.
        boolean clk = ((tpB >> 6) & 1) != 0;
        if (!volStrobe1 && clk) {
            dacControlDataFormat >>= 1;
            dacControlDataFormat |= ((tpB >> 5) & 1) << 11;
            volStrobe1 = true;
        } else if (volStrobe1 && !clk) {
            volStrobe1 = false;
        }
        boolean strobe = ((tpB >> 7) & 1) != 0;
        if (!volStrobe2 && strobe) {
            cdVolume = dacControlDataFormat & 1023;
            cdVolumeStored = cdVolume;
            volStrobe2 = true;
        } else if (volStrobe2 && !strobe) {
            volStrobe2 = false;
        }
        tpCheckInterrupts();
    }

    private int tpRead(int reg) {
        int v = 0;
        switch (reg) {
            case 0 -> {
                if (subcodeOffset < 0 || subcodeOffset >= SUB_CHANNEL_SIZE) {
                    v = 0;
                } else {
                    int s = subTransferBuf[subcodeOffset] & 0xFF;
                    tpA = Integer.reverse(s) >>> 24;
                    v = tpA;
                    sbcp = 0;
                    subcodeOffset++;
                    if (subcodeOffset >= SUB_CHANNEL_SIZE) subcodeOffset = -2;
                }
            }
            case 1 -> v = tpB;
            case 2 -> v = (tpCr & 1) != 0 ? tpIlatch | tpIlatch2 : tpC();
            case 3 -> v = tpAd;
            case 4 -> v = tpBd;
            case 5 -> v = (tpCr & 1) != 0 ? tpImask : tpCd;
            case 6 -> v = tpCr;
            case 7 -> {
                v = tpAir;
                if ((tpCr & 1) != 0) {
                    tpIlatch &= ~(1 << 5);
                    tpIlatch2 = 0;
                }
                tpAir = 0;
            }
            default -> { }
        }
        tpCheckInterrupts();
        return v & 0xFF;
    }

    // ---------------------------------------------------------------------------------------
    // The drive: commands
    // ---------------------------------------------------------------------------------------

    private void accepted(int size) {
        commandSizeOut = size;
        commandCntIn = 0;
        commandCntOut = 0;
        commandDone = true;
    }

    private void commandByte(int b) {
        commandInput[commandCntIn++] = b;
        int[] s = commandInput;
        switch (s[0]) {
            case 0x00, 0x80 -> {
                if (commandCntIn == 2) {
                    commandOutput[0] = 0xAA;
                    commandOutput[1] = 0x55;
                    accepted(2);
                }
            }
            case 0x01 -> { // seek
                if (commandCntIn == 7) {
                    accepted(0);
                    cdFinished = true;
                    activateStch = true;
                }
            }
            case 0x02 -> { // read
                if (commandCntIn == 7) {
                    readSectors((s[1] << 16) | (s[2] << 8) | s[3], (s[4] << 8) | s[5]);
                    accepted(0);
                }
            }
            case 0x04 -> { // motor on
                if (commandCntIn == 7) {
                    cdMotor = true;
                    accepted(0);
                    cdFinished = true;
                }
            }
            case 0x05 -> { // motor off
                if (commandCntIn == 7) {
                    cdMotor = false;
                    accepted(0);
                    cdFinished = true;
                }
            }
            case 0x09, 0x0A -> { // play LSN / MSF
                if (commandCntIn == 7) accepted(playCd(s));
            }
            case 0x0B -> { // play track
                if (commandCntIn == 7) accepted(playCdTrack(s));
            }
            case 0x81 -> { // status
                if (commandCntIn == 1) {
                    int flag = 0;
                    if (!cdIsReady) flag |= 1;
                    if (cdPlaying) flag |= 4;
                    if (cdFinished) flag |= 8;
                    if (cdError) flag |= 0x10;
                    if (cdMotor) flag |= 0x20;
                    if (cdMedia) flag |= 0x40;
                    commandOutput[0] = flag;
                    accepted(1);
                    cdFinished = false;
                }
            }
            case 0x82 -> { // error
                if (commandCntIn == 7) {
                    if (cdError) commandOutput[2] |= 1 << 4;
                    cdError = false;
                    cdIsReady = false;
                    accepted(6);
                    cdFinished = true;
                }
            }
            case 0x83 -> { // model
                if (commandCntIn == 7) {
                    for (int i = 0; i < MODEL_NAME.length; i++) commandOutput[i] = MODEL_NAME[i] & 0xFF;
                    accepted(MODEL_NAME.length);
                    cdFinished = true;
                }
            }
            case 0x84 -> { // mode set
                if (commandCntIn == 7) {
                    accepted(modeSet(s));
                    cdFinished = true;
                }
            }
            case 0x85 -> { // mode sense
                if (commandCntIn == 1) {
                    commandOutput[0] = (sectorSize >> 8) & 0xFF;
                    commandOutput[1] = sectorSize & 0xFF;
                    accepted(2);
                }
            }
            case 0x86 -> { // capacity
                if (commandCntIn == 1) {
                    int size = lastAddress() - 1;
                    commandOutput[0] = (size >> 16) & 0xFF;
                    commandOutput[1] = (size >> 8) & 0xFF;
                    commandOutput[2] = size & 0xFF;
                    commandOutput[3] = (sectorSize >> 8) & 0xFF;
                    commandOutput[4] = sectorSize & 0xFF;
                    if (media == null) cdError = true;
                    accepted(media == null ? -1 : 5);
                }
            }
            case 0x87 -> { // subq
                if (commandCntIn == 7) accepted(subq((s[1] & 2) != 0));
            }
            case 0x88 -> {
                if (commandCntIn == 1) {
                    java.util.Arrays.fill(commandOutput, 0, 14, 0);
                    accepted(14);
                }
            }
            case 0x89 -> { // info
                if (commandCntIn == 7) accepted(info());
            }
            case 0x8A -> { // read TOC
                if (commandCntIn == 7) accepted(readToc(s[2], (s[1] & 2) != 0));
            }
            case 0x8B -> { // pause
                if (commandCntIn == 7) {
                    pauseAudio(s[1] == 0x00);
                    accepted(0);
                    cdFinished = true;
                }
            }
            case 0xA2 -> {
                if (commandCntIn == 1) {
                    commandOutput[0] = commandOutput[1] = commandOutput[2] = commandOutput[3] = 0;
                    accepted(4);
                }
            }
            case 0xA3 -> { // front panel
                if (commandCntIn == 7) {
                    frontPanel = s[1] != 0;
                    accepted(0);
                    cdFinished = true;
                }
            }
            default -> {
                cdError = true;
                accepted(0);
            }
        }
    }

    private int lastAddress() {
        return media == null ? 0 : media.leadOut();
    }

    private void readSectors(int start, int length) {
        if (cdPlaying) cdAudioStop();
        cdromOffset = start * sectorSize;
        cdromLength = length * sectorSize;
        cdMotor = true;
        cdAudioStatus = AUDIO_STATUS_NOT_SUPPORTED;
    }

    private int modeSet(int[] cmd) {
        int size = (cmd[2] << 8) | cmd[3];
        if (size != 512 && size != 1024 && size != 2048 && size != 2052 && size != 2336 && size != 2340) {
            cdError = true;
            return 0;
        }
        sectorSize = size;
        return 0;
    }

    private int info() {
        if (media == null) return -1;
        cdMotor = true;
        commandOutput[0] = 1;
        commandOutput[1] = media.tracks().size();
        int size = lsnToMsf(lastAddress());
        commandOutput[2] = (size >> 16) & 0xFF;
        commandOutput[3] = (size >> 8) & 0xFF;
        commandOutput[4] = size & 0xFF;
        cdFinished = true;
        return 5;
    }

    private int readToc(int track, boolean msf) {
        if (media == null) return -1;
        cdMotor = true;
        for (CdImage.TocEntry e : toc) {
            if (e.point() == track) {
                int lsn = e.address();
                int a = msf ? lsnToMsf(lsn) : lsn;
                commandOutput[0] = 0;
                commandOutput[1] = (e.adr() << 4) | e.control();
                commandOutput[2] = e.point();
                commandOutput[3] = media.tracks().size();
                commandOutput[4] = 0;
                commandOutput[5] = (a >> 16) & 0xFF;
                commandOutput[6] = (a >> 8) & 0xFF;
                commandOutput[7] = a & 0xFF;
                cdFinished = true;
                return 8;
            }
        }
        return -1;
    }

    private int subq(boolean msf) {
        int[] s = qcode;
        commandOutput[0] = cdAudioStatus;
        int ctrl = s[4];
        commandOutput[1] = ((ctrl >> 4) | (ctrl << 4)) & 0xFF;
        commandOutput[2] = fromBcd(s[5]);
        commandOutput[3] = fromBcd(s[6]);
        int trackMsf = fromLongBcd(s, 7);
        int diskMsf = fromLongBcd(s, 11);
        int trackLsn = msfToLsn(trackMsf);
        int diskLsn = msfToLsn(diskMsf);
        int disk = msf ? diskMsf : diskLsn;
        int trk = msf ? trackMsf : trackLsn;
        commandOutput[4] = 0;
        commandOutput[5] = (disk >> 16) & 0xFF;
        commandOutput[6] = (disk >> 8) & 0xFF;
        commandOutput[7] = disk & 0xFF;
        commandOutput[8] = 0;
        commandOutput[9] = (trk >> 16) & 0xFF;
        commandOutput[10] = (trk >> 8) & 0xFF;
        commandOutput[11] = trk & 0xFF;
        commandOutput[12] = 0;
        if (cdAudioStatus == AUDIO_STATUS_IN_PROGRESS) lastPlayPos = diskLsn;
        return 13;
    }

    // ---------------------------------------------------------------------------------------
    // The drive: audio
    // ---------------------------------------------------------------------------------------

    private void cdAudioStopUnit() {
        if (player == null) return;
        player.pause(false, clock.getAsLong());
        player.stop();
    }

    private void cdAudioStop() {
        cdFinished = false;
        cdAudioStatus = AUDIO_STATUS_NO_STATUS;
        if (cdPlaying) {
            cdAudioStatus = AUDIO_STATUS_PLAY_COMPLETE;
            cdFinished = true;
        }
        cdPlaying = false;
        cdPaused = false;
        cdMotor = false;
        cdAudioFinished = false;
        cdAudioStopUnit();
    }

    private void pauseAudio(boolean pause) {
        if (player != null) player.pause(pause, clock.getAsLong());
        if (!cdPlaying) {
            cdPaused = false;
            cdAudioStatus = AUDIO_STATUS_NO_STATUS;
            return;
        }
        cdPaused = pause;
        cdAudioStatus = pause ? AUDIO_STATUS_PAUSED : AUDIO_STATUS_IN_PROGRESS;
        subReset();
    }

    private void startPlay() {
        subReset();
        if (player != null) {
            subReset();
            player.pause(false, clock.getAsLong());
            unitPositionFromPlayer = true;
            player.play(playStart, playEnd, clock.getAsLong());
        }
        if (!cdMotor) {
            cdMotor = true;
            activateStch = true;
        }
    }

    // ---------------------------------------------------------------------------------------
    // The front panel
    // ---------------------------------------------------------------------------------------

    /** Front panel buttons, in the order of their key codes $72-$77. */
    public static final int PANEL_STOP = 0, PANEL_PLAY_PAUSE = 1, PANEL_PREV = 2,
            PANEL_NEXT = 3, PANEL_REW = 4, PANEL_FF = 5;

    /** Panel buttons pressed from another thread, bit n = button n, taken at the next line. */
    private transient volatile int panelPending;

    /**
     * Whether the drive handles the front panel itself: from its reset until the firmware
     * hands the buttons to the keyboard line (command $A3), and only on a configured board.
     */
    public boolean frontPanelActive() {
        return frontPanel && configured > 0;
    }

    /** Queues a press of a panel button, from any thread; it acts at the next line. */
    public synchronized void queuePanelButton(int button) {
        panelPending |= 1 << button;
    }

    private synchronized int takePanelButtons() {
        int p = panelPending;
        panelPending = 0;
        return p;
    }

    /**
     * A press of a panel button while the drive handles the panel: Stop, Play/Pause, and the
     * previous and next track during a play, which restart it two seconds before the track.
     * Returns whether the drive took the press.
     */
    public boolean frontPanelButton(int button) {
        if (!frontPanelActive()) return false;
        switch (button) {
            case PANEL_STOP -> {
                if (cdPaused) pauseAudio(false);
                cdAudioStopUnit();
                cdAudioStatus = AUDIO_STATUS_NO_STATUS;
                activateStch = true;
                cdFinished = false;
                cdPlaying = false;
                cdPaused = false;
                cdMotor = false;
            }
            case PANEL_PLAY_PAUSE -> {
                if (cdPlaying) {
                    pauseAudio(!cdPaused);
                } else if (cdMedia) {
                    initPlay(0, lastCdPosition);
                }
            }
            case PANEL_PREV, PANEL_NEXT -> {
                if (!cdPlaying) return true;
                int track = fromBcd(qcode[4 + 1]);
                int pos = 0;
                for (int j = 0; j < toc.size(); j++) {
                    CdImage.TocEntry e = toc.get(j);
                    int t = e.track();
                    pos = e.address();
                    if (t == 1 && track == 1 && button == PANEL_PREV) break;
                    if (j == toc.size() - 1 && t == track && button == PANEL_NEXT) break;
                    if (t == track - 1 && track > 1 && button == PANEL_PREV) break;
                    if (t == track + 1 && track < 99 && button == PANEL_NEXT) break;
                }
                initPlay(pos - 150, lastCdPosition);
            }
            default -> { }
        }
        return true;
    }

    private void initPlay(int start, int end) {
        playEnd = end;
        playStart = start;
        lastPlayPos = start;
        lastPlayEnd = end;
        startPlay();
    }

    private int playCdTrack(int[] p) {
        int trackStart = p[1];
        int trackEnd = p[3];
        if (trackStart == 0 && trackEnd == 0) return 0;
        int end = lastCdPosition;
        int start = 0;
        boolean startFound = false;
        for (CdImage.TocEntry e : toc) {
            if (e.point() < 1 || e.point() > 99) continue;
            if (trackStart == e.track()) {
                startFound = true;
                start = e.address();
            }
            if (trackEnd == e.track()) end = e.address();
        }
        if (!startFound) {
            cdAudioStop();
            cdError = true;
            activateStch = true;
            return 0;
        }
        playEnd = end;
        playStart = start;
        lastPlayPos = start;
        lastPlayEnd = end;
        startPlay();
        return 0;
    }

    private int playCd(int[] p) {
        int start = (p[1] << 16) | (p[2] << 8) | p[3];
        int end = (p[4] << 16) | (p[5] << 8) | p[6];
        if (p[0] == 0x09) end += start;
        if (start == 0 && end == 0) {
            cdFinished = cdPlaying;
            cdPlaying = false;
            cdPaused = false;
            cdMotor = false;
            cdAudioStopUnit();
            cdAudioStatus = AUDIO_STATUS_NO_STATUS;
            cdError = true;
            activateStch = true;
            return 0;
        }
        if (p[0] != 0x09) {
            start = msfToLsn(start);
            if (end < 0x00FFFFFF) end = msfToLsn(end);
        }
        if (end >= 0x00FFFFFF || end > lastCdPosition) end = lastCdPosition;
        playEnd = end;
        playStart = start;
        lastPlayPos = start;
        lastPlayEnd = end;
        startPlay();
        return 0;
    }

    private void playerStatus(int status) {
        if (cdAudioStatus != status) {
            if (status == AUDIO_STATUS_PLAY_COMPLETE || status == AUDIO_STATUS_PLAY_ERROR) {
                cdAudioFinished = true;
            } else {
                if (status == AUDIO_STATUS_IN_PROGRESS) cdPlaying = true;
                activateStch = true;
            }
        }
        if (status == AUDIO_STATUS_IN_PROGRESS && player != null) lastPlayPos = player.position();
        cdAudioStatus = status;
    }

    private void playerSubcode(byte[] data) {
        if (subcodeBufferInUse[subcodeBufferOffsetW]) {
            java.util.Arrays.fill(subcodeBufferInUse, false);
            subcodeBufferOffsetW = subcodeBufferOffset = 0;
            subcodeOffset = -1;
            return;
        }
        int offset = subcodeBufferOffsetW;
        subcodeBufferInUse[offset] = true;
        System.arraycopy(data, 0, subcodeBuffer, offset * SUB_CHANNEL_SIZE, SUB_CHANNEL_SIZE);
        offset++;
        if (offset >= MAX_SUBCODEBUFFER) offset = 0;
        subcodeBufferOffsetW = offset;
    }

    private void subReset() {
        java.util.Arrays.fill(subcodeBufferInUse, false);
        subcodeBufferOffsetW = subcodeBufferOffset = 0;
        subcodeOffset = -1;
        sbcp = 0;
        scor = 0;
    }

    // ---------------------------------------------------------------------------------------
    // The drive: data
    // ---------------------------------------------------------------------------------------

    private void startDma() {
        if ((dmacCntr & CNTR_PDMD) != 0) return; // the SCSI DMA: no SCSI controller
        if (dmaFinished) {
            dmaDeferred = true;
            return;
        }
        doDma();
    }

    /** The whole transfer goes to memory now; its end is reported after the drive's time. */
    private void doDma() {
        if (sectorSize == 0) return;
        int cnt = dmacWtc;
        dmaWait += cnt * 312L * 50 / 75 + 1;
        while (cnt > 0 && dmacDma) {
            int sector = cdromOffset / sectorSize;
            if (!didRead || readSector != sector) {
                readSector = sector;
                didRead = readSector(sector);
                if (!didRead) {
                    cdError = true;
                    activateStch = true;
                    break;
                }
            }
            int at = cdromOffset % sectorSize;
            memory.writeByte(dmacAcr & 0xFFFFFF, sectorBuffer[at] & 0xFF);
            memory.writeByte((dmacAcr + 1) & 0xFFFFFF, sectorBuffer[at + 1] & 0xFF);
            cnt--;
            dmacAcr += 2;
            cdromLength -= 2;
            cdromOffset += 2;
        }
        dmacWtc = 0;
        dmacDma = false;
        dmaFinished = true;
        cdFinished = true;
    }

    /** One sector in the drive's current sector size, into {@link #sectorBuffer}. */
    private boolean readSector(int sector) {
        if (!fetchSector(sector)) return false;
        unitLastPos = sector + 1;
        unitPositionFromPlayer = false;
        if (player != null) player.stop();
        return true;
    }

    /** The bytes of a sector in the current sector size, with no effect on the drive. */
    private boolean fetchSector(int sector) {
        if (media == null || sectorSize < 2048) return false;
        byte[] raw = new byte[2352];
        try {
            if (!media.readRaw(sector, raw)) return false;
        } catch (IOException e) {
            return false;
        }
        if (sectorSize == 2048) {
            System.arraycopy(raw, raw[15] == 2 ? 24 : 16, sectorBuffer, 0, 2048);
        } else {
            System.arraycopy(raw, 2352 - sectorSize, sectorBuffer, 0, sectorSize);
        }
        return true;
    }

    // ---------------------------------------------------------------------------------------
    // The drive: polls
    // ---------------------------------------------------------------------------------------

    /** The drive's media poll: notices a disc inserted or removed, reads the Q subchannel. */
    private void pollMedia() {
        boolean m = media != null;
        if (m != cdMedia) {
            cdMedia = m;
            lastCdPosition = lastAddress();
            activateStch = true;
            if (cdPlaying) cdError = true;
        }
        if (cdMedia) readQcode();
    }

    private void readQcode() {
        java.util.Arrays.fill(qcode, 0);
        int status = player != null ? player.takeQStatus() : AUDIO_STATUS_NOT_SUPPORTED;
        int pos = unitPositionFromPlayer && player != null ? player.position() : unitLastPos;
        qcode[1] = status;
        qcode[3] = 12;
        byte[] q = qSubchannel(pos);
        if (q != null) for (int i = 0; i < 12; i++) qcode[4 + i] = q[i] & 0xFF;
        qcode[1] = cdAudioStatus;
    }

    /** The Q subchannel of {@code sector}, 12 bytes, as the drive delivers it. */
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

    private void doStch() {
        if ((tpCr & 1) != 0 && (tpAir & (1 << 2)) == 0) {
            stch = 1;
            activateStch = false;
            tpCheckInterrupts();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Time
    // ---------------------------------------------------------------------------------------

    /**
     * One line. {@code cntMax} is the number of lines of 1/75 s, less two: the period of the
     * subchannel frame and of the drive's polls.
     */
    public void hsync(int linesPerFrame, float frameRate) {
        if (pendingDisc != null && ++pendingDiscLines >= (int) (linesPerFrame * frameRate * DISC_CHANGE_SECONDS)) {
            CdImage next = pendingDisc;
            pendingDisc = null;
            pendingDiscLines = -1;
            insert(next);
        }
        if (player != null) player.advanceTo(clock.getAsLong());
        if (configured <= 0) return;
        if (panelPending != 0) {
            int panel = takePanelButtons();
            for (int b = 0; panel != 0; b++, panel >>= 1) {
                if ((panel & 1) != 0) frontPanelButton(b);
            }
        }
        cdtvHsync++;

        if (dmaWait >= 1024) dmaWait -= 1024;
        if (dmaWait < 1024 && dmaFinished) {
            if ((dmacCntr & (CNTR_INTEN | CNTR_TCEN)) == (CNTR_INTEN | CNTR_TCEN)) {
                dmacIstr |= ISTR_INT_P | ISTR_E_INT;
            }
            dmaFinished = false;
            cdtvHsync = -1;
        }
        if (dmaDeferred && !dmaFinished) {
            dmaDeferred = false;
            doDma();
        }
        checkInt();

        if (commandDone) {
            commandDone = false;
            sten = 1;
            stch = 0;
            tpCheckInterrupts();
        }

        if (sten < 0) {
            sten--;
            if (sten < -3) sten = 0;
        }

        if (cdAudioFinished) {
            cdAudioFinished = false;
            cdPlaying = false;
            cdFinished = true;
            cdPaused = false;
            activateStch = true;
        }

        int cntMax = (int) ((float) linesPerFrame * frameRate / 75 - 2);
        if (subchannelCounter > 0) subchannelCounter--;
        if (subchannelCounter <= 0) {
            if (cdPlaying || cdMedia) {
                if (subcodeBufferOffset != subcodeBufferOffsetW) {
                    subcodeOffset = -1;
                    if (subcodeBufferInUse[subcodeBufferOffset]) {
                        subcodeBufferInUse[subcodeBufferOffset] = false;
                        System.arraycopy(subcodeBuffer, subcodeBufferOffset * SUB_CHANNEL_SIZE,
                                subTransferBuf, 0, SUB_CHANNEL_SIZE);
                        subcodeBufferOffset++;
                        if (subcodeBufferOffset >= MAX_SUBCODEBUFFER) subcodeBufferOffset -= MAX_SUBCODEBUFFER;
                        sbcp = 0;
                        scor = 1;
                        subcodeOffset = 0;
                        subcodeActiveCnt = 5;
                        subcodeEventCck = nowCck() + SUBCODE_CYCLES;
                        tpCheckInterrupts();
                    }
                    subchannelCounter = cntMax;
                }
            }
            if (scor == 0 && !cdPlaying) {
                // Frame interrupts come all the time the motor runs.
                scor = 1;
                tpCheckInterrupts();
                scor = 0;
                subchannelCounter = cntMax;
            }
        }

        if (cdtvHsync < cntMax && cdtvHsync >= 0) return;
        cdtvHsync = 0;
        cdLed = dmacDma || dmaFinished ? 1 : 0;

        subqCnt--;
        if (subqCnt < 0) {
            pollMedia();
            subqCnt = cdPlaying ? 10 : 75;
        }
        if (activateStch) doStch();
    }

    private long nowCck() {
        return clock.getAsLong() / CddaPlayer.UNITS_PER_CCK;
    }

    /**
     * Steps the drive to colour clock {@code cck}: the player changes buffer, and with it the
     * drive refills, reports its status and queues subchannel frames, in the colour clock that
     * holds that instant; every {@value #SUBCODE_CYCLES} colour clocks the next subchannel
     * byte is offered (SBCP).
     */
    public void tick(long cck) {
        if (player != null) {
            long next = player.nextEventUnits();
            if (next < (cck + 1) * CddaPlayer.UNITS_PER_CCK) player.advanceTo(next);
        }
        if (subcodeEventCck >= 0 && cck >= subcodeEventCck) {
            subcodeEventCck = -1;
            subcodeInterrupt();
        }
    }

    private void subcodeInterrupt() {
        if (subcodeOffset < -1) return;
        if (scor < 0) {
            scor = 0;
            tpCheckInterrupts();
        }
        subcodeActiveCnt--;
        if (subcodeActiveCnt > 0) {
            if (sbcp != 0 || scor != 0) {
                subcodeEventCck = nowCck() + SUBCODE_CYCLES;
                return;
            }
        }
        if (sbcp != 0 && scor == 0) {
            sbcp = 0;
            return;
        }
        if (subcodeOffset >= 0) {
            sbcp = 1;
            tpCheckInterrupts();
        }
        subcodeActiveCnt = 2;
        subcodeEventCck = nowCck() + SUBCODE_CYCLES;
    }

    // ---------------------------------------------------------------------------------------
    // BCD and addresses
    // ---------------------------------------------------------------------------------------

    private static int bcd(int v) {
        v &= 0xFF;
        return ((v / 10) << 4) | (v % 10);
    }

    private static int fromBcd(int v) {
        return ((v >> 4) & 0x0F) * 10 + (v & 0x0F);
    }

    private static int fromLongBcd(int[] p, int at) {
        return (fromBcd(p[at]) << 16) | (fromBcd(p[at + 1]) << 8) | fromBcd(p[at + 2]);
    }

    private static int lsnToMsf(int lsn) {
        lsn += 150;
        int m = lsn / (60 * 75);
        lsn -= m * 60 * 75;
        int s = lsn / 75;
        int f = lsn - s * 75;
        return (m << 16) | (s << 8) | f;
    }

    private static int msfToLsn(int msf) {
        int m = (msf >> 16) & 0xFF;
        int s = (msf >> 8) & 0xFF;
        int f = msf & 0xFF;
        return m * 60 * 75 + s * 75 + f - 150;
    }
}
