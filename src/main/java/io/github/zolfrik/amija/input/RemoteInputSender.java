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

package io.github.zolfrik.amija.input;

import io.github.zolfrik.amija.net.FeedbackPacket;

/**
 * Guest side of the input: gathers the local gestures into a <b>complete state</b> to send
 * 200 times a second ({@code docs/remote-play-plan.md} section 4.3).
 *
 * <p>It implements the same two interfaces that drive a real machine, so the emulator's input
 * adapters (with the key translation, the pointer capture, the keyboard joystick map inside)
 * attach to it <b>without knowing</b>. On the guest side nothing changes in how one plays:
 * only where the gestures end up.
 *
 * <h2>Never events: only state</h2>
 * Every packet carries the complete truth, so a lost packet has no consequences and no
 * release can stay hanging. Two things, though, are not state by nature, and must be brought
 * back to state:
 *
 * <ul>
 *   <li><b>mouse movement is a delta.</b> Sending {@code dx,dy}, a lost packet would lose
 *       that movement forever. Here two <b>32-bit counters that are never reset</b>
 *       accumulate: the host applies the difference, and the next packet delivers the
 *       missing movement by itself. It matches the hardware model, where {@code AmigaMouse}
 *       is already a pair of wrapping quadrature counters;</li>
 *   <li><b>a key pressed and released between two samples would vanish.</b> At 200 Hz the
 *       blind window is 5 ms: short, but not zero. The remedy is the <b>sticky latch</b>: the
 *       bit of a key pressed stays raised for {@value #LATCH_SNAPSHOTS} samples (15 ms), so a
 *       tap survives even two packets lost in a row. And the packet <b>stays pure state</b>:
 *       no event log, no counter to reconcile. The release arrives 15 ms later, below the
 *       threshold of perception and below the duration of any human press.</li>
 * </ul>
 *
 * <h2>What is plugged in is state too</h2>
 * The port the host assigns to the guest is filled by <b>the guest</b>: mouse, real joystick,
 * keyboard joystick with <i>its</i> keys, or nothing. That choice is a property of the port
 * like the others, so it travels in the packet ({@link #setDevice}) rather than in a separate
 * message, and like the others it repairs itself, because every packet repeats it.
 *
 * <p>As in {@code GamePort}, the device is also inferred <b>from the gestures</b>: whoever
 * moves the mouse has a mouse. The two ways do not conflict, because the gestures come only
 * from the adapters the choice itself has wired; inferring it serves the case in which no
 * gestures arrive at all, the only way to tell an empty port from a still one.
 *
 * <h2>No security filter here</h2>
 * The ban on resetting the host's machine (CTRL+Amiga+Amiga) is <b>not</b> in this class, and
 * not by oversight: it is the host's machine, so the decision is the host's. A filter placed
 * here could be bypassed by anyone writing their own client, and the packet is 44 bytes:
 * writing one is an afternoon's work. See {@link RemoteInputApplier}.
 *
 * <p>Threads: the gestures come from the EDT, {@link #snapshot} is called by the feedback
 * thread at 200 Hz. Everything is synchronised: tens of nanoseconds on tiny structures, and
 * the alternative (scattered volatile fields) would give a state that was never true all
 * together at any instant.
 */
public final class RemoteInputSender implements GamePortControls, AmigaKeyTarget {

    /** How many samples the bit of a key just pressed stays raised. */
    public static final int LATCH_SNAPSHOTS = 3;

    private static final int KEY_COUNT = 128;

    private final boolean[] held = new boolean[KEY_COUNT];
    private final int[] latch = new int[KEY_COUNT];

    private int mouseCounterX;
    private int mouseCounterY;
    private int joyX;
    private int joyY;
    private int buttons;
    private int device = FeedbackPacket.DEVICE_NONE;

    /** True until the user has touched anything: avoids sending noise. */
    private boolean idle = true;

    /**
     * Pours the current state into the packet and advances the latch by one step.
     *
     * <p>It has a deliberate side effect (the latch advances), so it must be called once per
     * packet and from a single thread. Called twice per packet, a tap would last half as
     * long.
     */
    public synchronized void snapshot(FeedbackPacket out) {
        out.mouseCounterX = mouseCounterX;
        out.mouseCounterY = mouseCounterY;
        out.joyX = joyX;
        out.joyY = joyY;
        out.buttons = buttons;
        out.portDevice = device;
        java.util.Arrays.fill(out.keys, (byte) 0);
        for (int k = 0; k < KEY_COUNT; k++) {
            if (held[k] || latch[k] > 0) {
                out.keys[k >> 3] |= (byte) (1 << (k & 7));
            }
            if (latch[k] > 0) {
                latch[k]--;
            }
        }
    }

    /** True if no gesture has arrived yet: the host has nothing to apply. */
    public synchronized boolean isIdle() {
        return idle;
    }

    /**
     * Forgets all the <b>gestures</b>: new session, nothing inherited from the previous one.
     *
     * <p>The plugged-in device is <b>not</b> reset, and it is the only thing that survives: it
     * is not a gesture, it is the choice the user made in their own F12 popup. Resetting it
     * here would mean that reconnecting empties the port of whoever reconnects.
     */
    public synchronized void reset() {
        java.util.Arrays.fill(held, false);
        java.util.Arrays.fill(latch, 0);
        mouseCounterX = 0;
        mouseCounterY = 0;
        joyX = 0;
        joyY = 0;
        buttons = 0;
        idle = true;
    }

    // =====================================================================
    // Keyboard
    // =====================================================================

    @Override
    public synchronized void pressKey(int rawKeyNum) {
        int k = rawKeyNum & 0x7F;
        held[k] = true;
        latch[k] = LATCH_SNAPSHOTS;
        idle = false;
    }

    @Override
    public synchronized void releaseKey(int rawKeyNum) {
        // The latch is NOT touched: it is exactly the immediate release that must be able to
        // wait. If the press lasted more than 15 ms the latch has already dropped to zero and
        // the release leaves with the next packet, as expected.
        held[rawKeyNum & 0x7F] = false;
        idle = false;
    }

    // =====================================================================
    // Game port
    // =====================================================================

    /**
     * Declares what the user plugged into the port the host assigned to them.
     *
     * <p>Called by {@code Amija} when it wires the adapters, and again at every confirmation
     * of the F12 popup: from then on the field leaves 200 times a second, and the host puts
     * it on the real game port. There is no moment in which the choice is made but has not
     * arrived for longer than one packet.
     */
    @Override
    public synchronized void setDevice(GamePortDevice device) {
        this.device = wire(device);
    }

    @Override
    public synchronized void moveMouse(int dx, int dy) {
        device = FeedbackPacket.DEVICE_MOUSE;
        mouseCounterX += dx;
        mouseCounterY += dy;
        idle = false;
    }

    @Override
    public synchronized void pressMouseLeft(boolean pressed) {
        device = FeedbackPacket.DEVICE_MOUSE;
        setButton(FeedbackPacket.BUTTON_MOUSE_LEFT, pressed);
    }

    @Override
    public synchronized void pressMouseRight(boolean pressed) {
        device = FeedbackPacket.DEVICE_MOUSE;
        setButton(FeedbackPacket.BUTTON_MOUSE_RIGHT, pressed);
    }

    @Override
    public synchronized void pressMouseMiddle(boolean pressed) {
        device = FeedbackPacket.DEVICE_MOUSE;
        setButton(FeedbackPacket.BUTTON_MOUSE_MIDDLE, pressed);
    }

    @Override
    public synchronized void setJoystickAxes(int x, int y) {
        device = FeedbackPacket.DEVICE_JOYSTICK;
        joyX = Integer.signum(x);
        joyY = Integer.signum(y);
        idle = false;
    }

    @Override
    public synchronized void pressJoystickFire(boolean pressed) {
        device = FeedbackPacket.DEVICE_JOYSTICK;
        setButton(FeedbackPacket.BUTTON_FIRE, pressed);
    }

    @Override
    public synchronized void pressJoystickButton2(boolean pressed) {
        device = FeedbackPacket.DEVICE_JOYSTICK;
        setButton(FeedbackPacket.BUTTON_2, pressed);
    }

    @Override
    public synchronized void pressJoystickButton3(boolean pressed) {
        device = FeedbackPacket.DEVICE_JOYSTICK;
        setButton(FeedbackPacket.BUTTON_3, pressed);
    }

    private void setButton(int mask, boolean pressed) {
        buttons = pressed ? (buttons | mask) : (buttons & ~mask);
        idle = false;
    }

    /** The code the packet uses for a device. */
    private static int wire(GamePortDevice device) {
        if (device == null) {
            return FeedbackPacket.DEVICE_NONE;
        }
        return switch (device) {
            case MOUSE -> FeedbackPacket.DEVICE_MOUSE;
            case JOYSTICK -> FeedbackPacket.DEVICE_JOYSTICK;
            case NONE -> FeedbackPacket.DEVICE_NONE;
        };
    }
}
