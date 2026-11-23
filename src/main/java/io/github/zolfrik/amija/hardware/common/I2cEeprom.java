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

package io.github.zolfrik.amija.hardware.common;

/**
 * A 24Cxx serial EEPROM on a bit-banged I2C bus: the 1 KB 24C08 that holds the CD32's
 * non-volatile memory behind Akiko.
 *
 * <p>The master drives SCL and SDA through {@link #setScl} and {@link #setSda}; each call
 * returns the level of the data line as the master reads it back, the wired AND of the
 * master's level and the device's. A START (SDA falling while SCL is high) begins a
 * transfer, a STOP (SDA rising while SCL is high) ends it. A transfer starts with the device
 * address byte ({@code 1010 xxx R/W}, the three low bits of the address being the high bits
 * of the memory address on the 24C08), followed for a write by the low address byte and the
 * data bytes, which wrap inside a 16-byte page. A read streams bytes from the current
 * address, wrapping at the end of the memory, for as long as the master acknowledges.
 *
 * <p>Writes land in the memory array at once; a STOP reports the written page to the
 * {@link #setWriteListener listener}, which persists it.
 */
public final class I2cEeprom {

    /** Listener told of the bytes a write transfer changed, at its STOP. */
    public interface WriteListener {
        void written(int offset, int length);
    }

    private static final int PAGE_SIZE = 16;
    private static final int DEVICE_ADDRESS = 0xA0;
    private static final int DEVICE_ADDRESS_MASK = 0xF0;

    // Bus state.
    private static final int STOPPED = 0;
    private static final int SENDING_BIT7 = 1;
    private static final int SENDING_BIT0 = 8;
    private static final int WAITING_FOR_ACK = 9;
    private static final int RECEIVING_BIT7 = 10;
    private static final int RECEIVING_BIT0 = 17;
    private static final int SENDING_ACK = 18;
    private static final int SENT_NACK = 19;

    // Transfer state.
    private static final int DEVICE_ADDR = 0;
    private static final int WORD_ADDR = 1;
    private static final int DATA = 2;

    private final byte[] memory;
    private final int addressBitMask;
    private WriteListener writeListener;

    private int state = STOPPED;
    private int lastData;
    private int lastClock;
    private int deviceOut;
    private int buffer;
    private int currentAddr;
    private int transfer;
    private int eepromAddr;
    private int writeOffset;

    /** An EEPROM over {@code memory}, whose length must be a multiple of 256 bytes. */
    public I2cEeprom(byte[] memory) {
        this.memory = memory;
        this.addressBitMask = (memory.length / 256) - 1;
        reset();
    }

    public void setWriteListener(WriteListener listener) {
        this.writeListener = listener;
    }

    public byte[] memory() {
        return memory;
    }

    /** Both lines released high; a transfer in progress is forgotten but not the bus state. */
    public void reset() {
        lastData = 1;
        lastClock = 1;
        deviceOut = 1;
        eepromAddr = 0;
        writeOffset = -1;
        transfer = DEVICE_ADDR;
    }

    private void enterStop() {
        if (writeOffset >= 0 && writeListener != null) {
            int len = Math.min(memory.length - writeOffset, PAGE_SIZE);
            if (len > 0) writeListener.written(writeOffset, len);
            if (len < PAGE_SIZE) writeListener.written(0, PAGE_SIZE - len);
        }
        writeOffset = -1;
        currentAddr = -1;
        state = STOPPED;
        transfer = DEVICE_ADDR;
    }

    private int ret(int level) {
        deviceOut = level;
        return level & lastData;
    }

    private int nop() {
        return ret(deviceOut);
    }

    /** Drives SDA; a negative level leaves it as it is. Returns the data line level. */
    public int setSda(int level) {
        if (level < 0) level = lastData;
        if (level == lastData) return nop();
        lastData = level;
        if (lastClock == 0) return nop();
        if (level == 0) {
            state = SENDING_BIT7;
            currentAddr = -1;
        } else {
            enterStop();
        }
        return ret(1);
    }

    /** Drives SCL; a negative level leaves it as it is. Returns the data line level. */
    public int setScl(int level) {
        if (level < 0) level = lastClock;
        int data = lastData;
        if (lastClock == level) return nop();
        lastClock = level;
        if (level == 0) return ret(1);
        if (state == STOPPED || state == SENT_NACK) return ret(1);
        if (state >= SENDING_BIT7 && state <= SENDING_BIT0) {
            buffer = ((buffer << 1) | data) & 0xFF;
            state++;
            return ret(1);
        }
        if (state == WAITING_FOR_ACK) {
            if (transfer == DEVICE_ADDR) {
                currentAddr = buffer;
                if ((currentAddr & DEVICE_ADDRESS_MASK) != DEVICE_ADDRESS) {
                    state = STOPPED;
                    return ret(0);
                }
                if ((currentAddr & 1) != 0) {
                    transfer = DATA;
                } else {
                    transfer = WORD_ADDR;
                    eepromAddr = ((buffer >> 1) & addressBitMask) << 8;
                }
            } else if (transfer == WORD_ADDR) {
                transfer = DATA;
                eepromAddr &= addressBitMask << 8;
                eepromAddr |= buffer;
            } else if ((currentAddr & 1) == 0) {
                if (writeOffset < 0) writeOffset = eepromAddr;
                memory[eepromAddr] = (byte) buffer;
                eepromAddr = (eepromAddr & ~(PAGE_SIZE - 1)) | ((eepromAddr + 1) & (PAGE_SIZE - 1));
            }
            state = (currentAddr & 1) != 0 ? RECEIVING_BIT7 : SENDING_BIT7;
            return ret(0);
        }
        if (state >= RECEIVING_BIT7 && state <= RECEIVING_BIT0) {
            if (state == RECEIVING_BIT7) {
                buffer = memory[eepromAddr] & 0xFF;
                eepromAddr = (eepromAddr + 1) & (memory.length - 1);
            }
            int bit = (buffer >> 7) & 1;
            state++;
            buffer = (buffer << 1) & 0xFF;
            return ret(bit);
        }
        // SENDING_ACK: the master's acknowledge of the byte just read.
        state = data != 0 ? SENT_NACK : RECEIVING_BIT7;
        return ret(1);
    }
}
