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

package io.github.zolfrik.amija.cpu.event;

import io.github.zolfrik.amija.cpu.Processor;

/**
 * An event-driven processor, as the machine sees it. It runs on a {@link CpuBus}, which is
 * all it knows of the system.
 */
public interface EventCpu extends Processor {

    /** Connects the processor to the system. Called once, before {@link #powerOnReset}. */
    void attach(CpuBus bus);

    /**
     * Power-on: the processor is reset and holds /RESET for the start delay, in colour
     * clocks, before it fetches the reset vectors.
     */
    void powerOnReset(int startDelayCck);

    /**
     * Runs whole instructions while {@link CpuBus#cycles()} is below {@code deadline}. On
     * return the processor is at an instruction boundary.
     */
    void runUntil(long deadline);
}
