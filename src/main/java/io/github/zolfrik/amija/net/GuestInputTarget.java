/*
 * AmiJa - a cycle-exact Amiga 500 emulator in Java.
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
 * Whatever carries the state of the guest's controls into the host's machine.
 *
 * <p>It exists to keep remote play out of the emulation: {@code net/} can send, receive and
 * authenticate, and must not know that a game port and a keyboard exist. The implementation
 * ({@code input.RemoteInputApplier}) knows the opposite.
 *
 * <p><b>It is called by the emulation thread</b>, once per frame, through
 * {@link RemotePlayHost#applyPendingInput()}, never by the network thread (plan section 4.3).
 */
public interface GuestInputTarget {

    /**
     * @param state last authenticated state of the guest. A <b>reused</b> buffer, valid only for
     *              the duration of the call: the receiver consumes it, it does not keep it
     */
    void apply(FeedbackPacket state);

    /**
     * Brings to rest everything the guest held pressed.
     *
     * <p>Part of the contract, not a convenience: when the guest leaves no state arrives any more
     * to contradict the last one, so the key or the fire it held <b>would stay pressed
     * forever</b>, not because the protocol lost something but because it has nothing more to
     * say.
     *
     * <p>Like {@link #apply}, it must be called by the <b>emulation thread</b>: it touches the
     * machine.
     */
    void releaseAll();
}
