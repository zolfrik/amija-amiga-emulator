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

import java.nio.ByteBuffer;

/**
 * The packet the guest sends to the host, 200 times a second
 * ({@code docs/remote-play-plan.md} section 4.3).
 *
 * <h2>Complete state, never events</h2>
 * Every packet carries the <b>whole state</b> of the controls. A lost packet has no
 * consequences: the next one carries the complete truth, and no release can stay hanging.
 * Mouse movement, by nature a delta, is turned into state with two <b>cumulative
 * counters</b> that are never reset: the host applies the signed difference, and a lost
 * packet is recovered by the next one without anyone having to ask. It also matches the
 * hardware model, where {@code AmigaMouse} is already a pair of wrapping quadrature
 * counters.
 *
 * <h2>The video ACK rides on it too</h2>
 * {@code ackVideoFrameId} is the last video frame received <b>in full</b>: the pivot of
 * section 4.1, what lets the host always compress against something the guest really has.
 * It is here rather than in a packet of its own because this stream already exists, is the
 * more frequent of the two, and so the ACK costs not one byte more than its header would.
 *
 * <pre>
 *    0  u32 seq                monotonic; the host discards anything &lt;= the last applied
 *    4  u32 sessionGen         changes at every reconnection: resets the counters' baselines
 *    8  u32 ackVideoFrameId    last video frame received WHOLE
 *   12  u32 mouseCounterX      cumulative, never reset
 *   16  u32 mouseCounterY      cumulative, never reset
 *   20  u16 audioFillMs        telemetry for the bandwidth governor
 *   22  i8  joyX               -1 / 0 / +1
 *   23  i8  joyY               -1 / 0 / +1
 *   24  u8  buttons            fire, b2, b3, mouseL, mouseR, mouseM
 *   25  u8  portDevice         what the guest plugged in: NONE | MOUSE | JOYSTICK
 *   26  u16 reserved           must be 0
 *   28  u8  keys[16]           bitmap of the 128 Amiga raw codes, with a sticky latch
 *   44  = SIZE
 * </pre>
 *
 * <p><b>44 bytes, not the 48 estimated in the plan.</b> The difference is irrelevant for the
 * bandwidth (44 x 200 x 8 = 70 kbit/s against the 77 estimated) and comes from laying the
 * fields out <b>aligned</b> rather than in the order section 4.3 lists them: the two mouse
 * counters moved up next to the other integers. The reserved field left exists to leave
 * room, and is <b>checked to be zero</b> on purpose: a reserved field the receiver ignores
 * is not room for the future, it is a silent channel.
 *
 * <h2>Why the device is here and not in the handshake</h2>
 * The host assigns the guest <b>one</b> game port; what to plug into it (mouse, joystick,
 * nothing) is decided by the guest, and it can change <b>mid-game</b> (the guest's F12 popup
 * is open exactly for that). A handshake parameter would be fixed at the wrong moment, and a
 * dedicated control message would have to be written, ordered and repeated at reconnection.
 * Here it costs <b>zero bytes</b> (the room was already there), arrives 200 times a second,
 * and above all <b>is state like the rest of the packet</b>: if the packet carrying the
 * change is lost, the next one carries it again. The rule of section 4.3 holds for this field
 * too, with no exceptions to remember.
 */
public final class FeedbackPacket {

    public static final int SIZE = 44;

    /** Bitmap of the 128 raw codes of the Amiga keyboard: 16 bytes. */
    public static final int KEY_BYTES = 16;

    public static final int BUTTON_FIRE = 0x01;
    public static final int BUTTON_2 = 0x02;
    public static final int BUTTON_3 = 0x04;
    public static final int BUTTON_MOUSE_LEFT = 0x08;
    public static final int BUTTON_MOUSE_RIGHT = 0x10;
    public static final int BUTTON_MOUSE_MIDDLE = 0x20;
    private static final int BUTTON_MASK = 0x3F;

    /** Empty port: the guest has plugged nothing in, and {@code JOYxDAT} must read 0. */
    public static final int DEVICE_NONE = 0;
    public static final int DEVICE_MOUSE = 1;
    public static final int DEVICE_JOYSTICK = 2;
    private static final int DEVICE_MAX = DEVICE_JOYSTICK;

    public int seq;
    public int sessionGen;
    public long ackVideoFrameId;
    public int mouseCounterX;
    public int mouseCounterY;
    public int audioFillMs;
    public int joyX;
    public int joyY;
    public int buttons;
    /** What the guest plugged into the port the host assigned to it. */
    public int portDevice;
    public final byte[] keys = new byte[KEY_BYTES];

    /** Writes the packet into {@code dst}, which must have at least {@link #SIZE} bytes. */
    public void writeTo(byte[] dst) {
        ByteBuffer b = RemotePlayProtocol.wrap(dst, 0, SIZE);
        b.putInt(seq);
        b.putInt(sessionGen);
        b.putInt((int) ackVideoFrameId);
        b.putInt(mouseCounterX);
        b.putInt(mouseCounterY);
        b.putShort((short) audioFillMs);
        b.put((byte) joyX);
        b.put((byte) joyY);
        b.put((byte) buttons);
        b.put((byte) portDevice);
        b.putShort((short) 0);
        b.put(keys, 0, KEY_BYTES);
    }

    /**
     * Reads and <b>validates</b> a received packet.
     *
     * <p>These are bytes arriving from the network that end up driving a machine: nothing is
     * accepted that is not exactly what the format provides. Axes outside [-1, +1] and
     * undefined button bits are refused rather than clipped: clipping would hide a sender
     * that does not speak this protocol.
     *
     * @throws ProtocolException if the length or a field does not add up
     */
    public void readFrom(byte[] src, int length) throws ProtocolException {
        if (length != SIZE) {
            throw new ProtocolException("pacchetto di feedback di " + length
                    + " byte, attesi " + SIZE);
        }
        ByteBuffer b = RemotePlayProtocol.wrap(src, 0, SIZE);
        seq = b.getInt();
        sessionGen = b.getInt();
        ackVideoFrameId = b.getInt() & 0xFFFFFFFFL;
        mouseCounterX = b.getInt();
        mouseCounterY = b.getInt();
        audioFillMs = b.getShort() & 0xFFFF;
        joyX = b.get();
        joyY = b.get();
        buttons = b.get() & 0xFF;
        portDevice = b.get() & 0xFF;
        int reserved16 = b.getShort() & 0xFFFF;
        b.get(keys, 0, KEY_BYTES);

        if (joyX < -1 || joyX > 1 || joyY < -1 || joyY > 1) {
            throw new ProtocolException("assi del joystick fuori da [-1, +1]: "
                    + joyX + ", " + joyY);
        }
        if ((buttons & ~BUTTON_MASK) != 0) {
            throw new ProtocolException("bit di pulsante non definiti: 0x"
                    + Integer.toHexString(buttons));
        }
        if (portDevice > DEVICE_MAX) {
            throw new ProtocolException("periferica di porta sconosciuta: " + portDevice);
        }
        if (reserved16 != 0) {
            throw new ProtocolException("campo riservato non nullo");
        }
    }

    /** Clears everything: new session, no state inherited from the previous one. */
    public void clear() {
        seq = 0;
        sessionGen = 0;
        ackVideoFrameId = RemotePlayProtocol.NO_REFERENCE;
        mouseCounterX = 0;
        mouseCounterY = 0;
        audioFillMs = 0;
        joyX = 0;
        joyY = 0;
        buttons = 0;
        portDevice = DEVICE_NONE;
        java.util.Arrays.fill(keys, (byte) 0);
    }

    /** Copies the fields into {@code other}, without allocating. */
    public void copyTo(FeedbackPacket other) {
        other.seq = seq;
        other.sessionGen = sessionGen;
        other.ackVideoFrameId = ackVideoFrameId;
        other.mouseCounterX = mouseCounterX;
        other.mouseCounterY = mouseCounterY;
        other.audioFillMs = audioFillMs;
        other.joyX = joyX;
        other.joyY = joyY;
        other.buttons = buttons;
        other.portDevice = portDevice;
        System.arraycopy(keys, 0, other.keys, 0, KEY_BYTES);
    }

    /** The ACK counts only if it refers to a real frame: {@link RemotePlayProtocol#NO_REFERENCE} does not. */
    public boolean hasAck() {
        return ackVideoFrameId != RemotePlayProtocol.NO_REFERENCE;
    }
}
