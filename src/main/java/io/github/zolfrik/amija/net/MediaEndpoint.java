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

package io.github.zolfrik.amija.net;

/**
 * One peer of the host on the shared UDP socket: the player's session or a spectator.
 *
 * <p>The host reads the socket on a single thread and hands each datagram to the endpoint of its
 * sender ({@code RemotePlayHost.mediaLoop}). Each endpoint decides by itself what it accepts:
 * the player only input packets, a spectator only its own report. That is where the
 * guarantee "a spectator cannot touch the machine" lives: there is no path from a spectator's
 * datagram to the input applier.
 */
interface MediaEndpoint {

    /** The link that opens and seals this peer's datagrams. */
    MediaLink media();

    /** A datagram opened with this peer's key; the plaintext is in {@code plain}. */
    void onDatagram(MediaLink.Received received, byte[] plain);

    /** Closes the endpoint if the peer has been silent too long. */
    void checkTimeout(long nowNanos);
}
