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
 * Constants of AJS1, the protocol of the null modem cable between two instances.
 *
 * <p>One TCP connection. The handshake is in the clear and proves that both ends know the
 * join code; every frame after it is sealed with AES-GCM under a key derived from the code
 * and the two nonces, with a sequence number that must arrive in order.
 *
 * <pre>
 * HELLO      guest: "AJS", protocol, master clock in Hz, delay asked for in ns, nonce
 * CHALLENGE  host:  salt, nonce
 * AUTH       guest: proof of the join code
 * WELCOME    host:  proof of the join code, master clock in Hz
 * REJECT     host:  reason
 * SEALED     either: one sealed frame, see the kinds below
 * </pre>
 *
 * A sealed frame is a kind, the sender's horizon, and what the kind says:
 * {@code EVENTS} (count, then the events), {@code BEACON} (nothing: the horizon alone),
 * {@code PING}/{@code PONG} (a time of the sender, sent back as it is), {@code START} (the
 * delay of the cable and the two clocks, as the host fixed them), {@code PAUSE},
 * {@code RESUME}, {@code BYE} (a reason).
 */
public final class SerialLinkProtocol {

    private SerialLinkProtocol() { }

    public static final int VERSION = 1;

    /** Domain of the authentication tags: the join code of a cable proves nothing elsewhere. */
    public static final int AUTH_DOMAIN = 0x414A5300 | VERSION;

    public static final int MSG_HELLO = 0x41;
    public static final int MSG_CHALLENGE = 0x42;
    public static final int MSG_AUTH = 0x43;
    public static final int MSG_WELCOME = 0x44;
    public static final int MSG_REJECT = 0x45;
    public static final int MSG_SEALED = 0x46;

    public static final int KIND_EVENTS = 1;
    public static final int KIND_BEACON = 2;
    public static final int KIND_PING = 3;
    public static final int KIND_PONG = 4;
    public static final int KIND_START = 5;
    public static final int KIND_PAUSE = 6;
    public static final int KIND_RESUME = 7;
    public static final int KIND_BYE = 8;

    public static final int REJECT_AUTH = 1;
    public static final int REJECT_PROTOCOL = 2;
    public static final int REJECT_BUSY = 3;

    public static final int BYE_CLOSED = 1;
    public static final int BYE_RESYNC = 2;

    /** Events in one frame at most. */
    public static final int MAX_EVENTS_PER_FRAME = 64;

    /** The longest cable: one second of line time. */
    public static final long MAX_DELAY_NS = 1_000_000_000L;

    /** The shortest: a batch of the emulation loop has to fit in it. */
    public static final long MIN_DELAY_NS = 500_000L;

    /** Master clocks accepted from the other end, in Hz: PAL and NTSC with room to spare. */
    public static final long MIN_CLOCK_HZ = 6_000_000L;
    public static final long MAX_CLOCK_HZ = 8_000_000L;

    /** Pings of the measure made before the cable is joined. */
    public static final int MEASURE_PINGS = 16;

    public static String rejectReason(int reason) {
        return switch (reason) {
            case REJECT_AUTH -> "wrong join code";
            case REJECT_PROTOCOL -> "the other end speaks a different version of the protocol";
            case REJECT_BUSY -> "the other end already has a cable plugged in";
            default -> "refused (reason " + reason + ")";
        };
    }
}
