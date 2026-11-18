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

/** What the configuration popup does with the serial port and reads of it. */
public interface SerialControl {

    /** Nothing to show. */
    int LED_OFF = 0;
    /** Waiting for the other machine, or joining it. */
    int LED_WAITING = 1;
    /** The cable is plugged in and the machine runs. */
    int LED_LINKED = 2;
    /** The cable is plugged in and the machine is held at its window, waiting for the other. */
    int LED_HELD = 3;

    /** One of the LED_ constants. */
    int serialLedState();

    /** True while this instance listens for the other end. */
    boolean isSerialHosting();

    /** True while a cable to another machine is plugged in. */
    boolean isSerialLinked();

    /** True while this instance is connecting to the other end. */
    boolean isSerialJoining();

    /** The code the other end must type, while hosting; null otherwise. */
    String serialJoinCode();

    /** Address and port to give to the other end, while hosting; null otherwise. */
    String serialLanAddress();

    /** One line on what the port is doing, for the popup. */
    String serialStatus();

    /**
     * Starts listening for the other end.
     *
     * @param port    TCP port to listen on
     * @param lanOnly whether to refuse connections from outside the local network
     * @return null if started, otherwise why not
     */
    String startSerialHost(int port, boolean lanOnly);

    /**
     * Connects to a listening instance, in the background: {@link #serialStatus} tells how it went.
     *
     * @return null if the attempt started, otherwise why not
     */
    String joinSerial(String address, int port, String joinCode);

    /** Unplugs the cable and stops listening. */
    void stopSerial();
}
