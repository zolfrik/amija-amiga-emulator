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

import java.time.LocalDateTime;
import java.util.function.Supplier;

/**
 * Minimal battery-backed clock compatible with the 4-bit BCD RTC devices used
 * by classic Amiga clock boards. It is intentionally stateless: reads reflect
 * the host system time and writes to control registers are accepted but ignored.
 */
public final class BatteryBackedClock {

    private final int[] control = { 0x1, 0, 0x4 };
    private final Supplier<LocalDateTime> clock;

    /**
     * Fixes the time the clock reports ({@code -Damiga.rtctime=2000-01-01T00:00:00}), so that a
     * run reads the same registers every time; the host time otherwise.
     */
    public static final String FIXED_TIME_PROPERTY = "amiga.rtctime";

    public BatteryBackedClock() {
        this(clockSource());
    }

    private static Supplier<LocalDateTime> clockSource() {
        final String fixed = System.getProperty(FIXED_TIME_PROPERTY);
        if (fixed == null || fixed.isBlank()) return LocalDateTime::now;
        final LocalDateTime t = LocalDateTime.parse(fixed.trim());
        return () -> t;
    }

    BatteryBackedClock(Supplier<LocalDateTime> clock) {
        this.clock = clock;
    }

    public int readByte(int address) {
        if (!isValidByteAddress(address)) {
            return 0;
        }
        int reg = register(address);
        LocalDateTime now = clock.get();
        int amigaYear = now.getYear() - 1900;
        int value = switch (reg) {
            case 0x0 -> now.getSecond() % 10;
            case 0x1 -> now.getSecond() / 10;
            case 0x2 -> now.getMinute() % 10;
            case 0x3 -> now.getMinute() / 10;
            case 0x4 -> now.getHour() % 10;
            case 0x5 -> now.getHour() / 10;
            case 0x6 -> now.getDayOfMonth() % 10;
            case 0x7 -> now.getDayOfMonth() / 10;
            case 0x8 -> now.getMonthValue() % 10;
            case 0x9 -> now.getMonthValue() / 10;
            case 0xA -> amigaYear % 10;
            case 0xB -> (amigaYear / 10) & 0x0F;
            case 0xC -> now.getDayOfWeek().getValue() % 7;
            case 0xD, 0xE, 0xF -> control[reg - 0xD];
            default -> 0;
        };
        return value & 0x0F;
    }

    public void writeByte(int address, int value) {
        if (!isValidByteAddress(address)) {
            return;
        }
        int reg = register(address);
        if (reg >= 0xD && reg <= 0xF) {
            control[reg - 0xD] = value & 0x0F;
        }
    }

    public int readWord(int address) {
        return ((readByte(address) & 0xFF) << 8) | (readByte(address + 1) & 0xFF);
    }

    private static int register(int address) {
        return (address & 0x3F) >> 2;
    }

    private static boolean isValidByteAddress(int address) {
        return (address & 1) == 1;
    }
}
