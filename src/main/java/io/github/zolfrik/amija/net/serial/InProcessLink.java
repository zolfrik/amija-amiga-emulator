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

/**
 * Two ends of a cable in one JVM: what one end sends the other receives at once. No thread
 * and no socket, so a pair of machines stepped in turn, always the one behind first, is
 * deterministic by construction. It is the reference every other transport is compared with.
 */
public final class InProcessLink {

    private InProcessLink() { }

    /** Joins the two ends, each sending straight into the other. */
    public static void join(LinkCore a, LinkCore b) {
        a.setTransport(new Direct(b));
        b.setTransport(new Direct(a));
    }

    private record Direct(LinkCore peer) implements LinkCore.Transport {
        @Override public void send(LineEvent event) { peer.receive(event); }
        @Override public void sendHorizon(long horizon) { peer.receiveHorizon(horizon); }
    }
}
