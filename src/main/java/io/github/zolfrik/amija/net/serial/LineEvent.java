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

package io.github.zolfrik.amija.net.serial;

import java.nio.ByteBuffer;

/**
 * Something one machine does to the cable, stamped with the line time at which it does it.
 *
 * <p>Line time is nanoseconds of emulated time since the two ends were joined. An event is
 * applied at the other end exactly at its stamp plus the delay of the cable, never when it
 * happens to arrive.
 *
 * @param kind    {@link #TX}, {@link #BREAK} or {@link #CONTROL}
 * @param time    line time of the event, in nanoseconds
 * @param first   TX: master cycles of the sender the first level lasts
 * @param bit     TX: master cycles of the sender each further level lasts
 * @param pattern TX: the levels, bit 0 first, 1 is mark; BREAK: 1 while TXD is held low;
 *                CONTROL: bit 0 DTR asserted, bit 1 RTS asserted
 * @param bits    TX: how many levels
 */
public record LineEvent(int kind, long time, int first, int bit, int pattern, int bits) {

    public static final int TX = 1;
    public static final int BREAK = 2;
    public static final int CONTROL = 3;

    /** Bytes of an encoded event. */
    public static final int ENCODED_SIZE = 1 + 8 + 4 + 4 + 4 + 1;

    public static LineEvent tx(long time, int first, int bit, int pattern, int bits) {
        return new LineEvent(TX, time, first, bit, pattern, bits);
    }

    public static LineEvent brk(long time, boolean on) {
        return new LineEvent(BREAK, time, 0, 0, on ? 1 : 0, 0);
    }

    public static LineEvent control(long time, boolean dtr, boolean rts) {
        return new LineEvent(CONTROL, time, 0, 0, (dtr ? 1 : 0) | (rts ? 2 : 0), 0);
    }

    public void encode(ByteBuffer out) {
        out.put((byte) kind).putLong(time).putInt(first).putInt(bit).putInt(pattern).put((byte) bits);
    }

    /**
     * Decodes an event, refusing anything a transmitter cannot produce.
     *
     * @throws IllegalArgumentException if the bytes are not a valid event
     */
    public static LineEvent decode(ByteBuffer in) {
        int kind = in.get();
        long time = in.getLong();
        int first = in.getInt();
        int bit = in.getInt();
        int pattern = in.getInt();
        int bits = in.get();
        if (time < 0) throw new IllegalArgumentException("negative line time");
        switch (kind) {
            case TX -> {
                if (bits < 1 || bits > 17 || first < 1 || bit < 1 || first > MAX_BIT_CYCLES || bit > MAX_BIT_CYCLES) {
                    throw new IllegalArgumentException("malformed TX event");
                }
            }
            case BREAK, CONTROL -> {
                if ((pattern & ~3) != 0) throw new IllegalArgumentException("malformed line event");
            }
            default -> throw new IllegalArgumentException("unknown line event " + kind);
        }
        return new LineEvent(kind, time, first, bit, pattern, bits);
    }

    /** The longest bit a transmitter can make: SERPER at its maximum, in master cycles. */
    public static final int MAX_BIT_CYCLES = 0x8000 * 2;
}
