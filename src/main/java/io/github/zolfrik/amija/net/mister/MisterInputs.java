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

package io.github.zolfrik.amija.net.mister;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.PortUnreachableException;
import java.net.StandardProtocolFamily;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import java.util.Arrays;

/**
 * Receives the reports of the controls plugged into the MiSTer (port
 * {@link GroovyProtocol#PORT_INPUTS}) and keeps the latest state for the emulation thread.
 *
 * <h2>Registration</h2>
 * The MiSTer learns where to send the reports from one byte sent to its input port, which it
 * reads only when a session opens: {@link #register()} must therefore precede every
 * {@code CMD_INIT}. One socket lives as long as this object, so a registration byte left
 * waiting on the MiSTer by an earlier attempt still names the right port.
 *
 * <h2>Which reports count</h2>
 * Reports carry no sequence of their own: {@code (frame, order)} is the MiSTer's frame and a
 * counter of input events within it, reset at every blit. A report is dropped only when it
 * repeats the previous one of its kind exactly. The joystick and keyboard parts are states,
 * so a lost report is repaired by the next one. The mouse part is a delta, and the MiSTer
 * repeats its last report as a keep-alive every 2700 frames: a report whose order is 0 was
 * sent before any event of its frame, so it is a keep-alive and its movement is not added
 * again.
 *
 * <h2>Short presses</h2>
 * The state is sampled once per frame, and a press and its release may arrive within the
 * same frame: the MiSTer delivers some of them a few milliseconds apart. Every button or key
 * pressed since the previous {@link #drain} is therefore reported held by it, and released
 * by the next one, so no press goes unseen.
 *
 * <p>Threading: one receiver thread writes, {@link #drain} reads, both under a lock.
 */
public final class MisterInputs implements Closeable {

    private final DatagramChannel channel;
    private final InetSocketAddress mister;
    private final boolean keyboardAndMouse;
    private final Object lock = new Object();
    private final byte[] keys = new byte[GroovyProtocol.INPUT_KEY_BYTES];
    private int joy1;
    private int joy2;
    private int mouseDx;
    private int mouseDy;
    private int mouseButtons;
    // What was pressed since the previous drain, released or not.
    private final byte[] keysSeen = new byte[GroovyProtocol.INPUT_KEY_BYTES];
    private int joy1Seen;
    private int joy2Seen;
    private int mouseButtonsSeen;
    private long lastJoyStamp = -1;
    private long lastPs2Stamp = -1;

    private volatile boolean running;
    private Thread thread;
    private volatile long reports;

    /**
     * @param mister           the MiSTer's address and input port
     * @param localBind        local address to receive on, or {@code null} for any
     * @param keyboardAndMouse whether keyboard and mouse reports count (joysticks always do)
     */
    public MisterInputs(InetSocketAddress mister, InetAddress localBind,
                        boolean keyboardAndMouse) throws IOException {
        this.keyboardAndMouse = keyboardAndMouse;
        this.mister = mister;
        DatagramChannel ch = DatagramChannel.open(StandardProtocolFamily.INET);
        try {
            ch.bind(new InetSocketAddress(localBind, 0));
            ch.connect(mister);
        } catch (IOException | RuntimeException e) {
            ch.close();
            throw e;
        }
        this.channel = ch;
    }

    /** Starts the receiver thread. */
    public synchronized void start() {
        if (thread != null) {
            return;
        }
        running = true;
        thread = new Thread(this::run, "mister-input");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Asks the MiSTer for the reports of the next session, and forgets the state of the
     * previous one: what was held then is released by the next drain.
     */
    public void register() throws IOException {
        synchronized (lock) {
            joy1 = 0;
            joy2 = 0;
            Arrays.fill(keys, (byte) 0);
            mouseDx = 0;
            mouseDy = 0;
            mouseButtons = 0;
            Arrays.fill(keysSeen, (byte) 0);
            joy1Seen = 0;
            joy2Seen = 0;
            mouseButtonsSeen = 0;
            lastJoyStamp = -1;
            lastPs2Stamp = -1;
        }
        try {
            channel.write(ByteBuffer.wrap(new byte[1]));
        } catch (PortUnreachableException e) {
            // an earlier datagram found no listener; this one has been sent all the same
        }
    }

    /**
     * Copies the current state into {@code out}, with what was pressed since the previous
     * call still held and the mouse movement gathered since then.
     */
    public void drain(MisterInputState out) {
        synchronized (lock) {
            out.joy1 = joy1 | joy1Seen;
            out.joy2 = joy2 | joy2Seen;
            for (int i = 0; i < keys.length; i++) {
                out.keys[i] = (byte) (keys[i] | keysSeen[i]);
                keysSeen[i] = 0;
            }
            out.mouseDx = mouseDx;
            out.mouseDy = mouseDy;
            out.mouseButtons = mouseButtons | mouseButtonsSeen;
            joy1Seen = 0;
            joy2Seen = 0;
            mouseButtonsSeen = 0;
            mouseDx = 0;
            mouseDy = 0;
        }
    }

    /** The MiSTer the reports come from. */
    public InetSocketAddress mister() {
        return mister;
    }

    /** Reports accepted so far. */
    public long reports() {
        return reports;
    }

    /** The local address the reports arrive at. */
    public InetSocketAddress localAddress() throws IOException {
        return (InetSocketAddress) channel.getLocalAddress();
    }

    @Override
    public void close() throws IOException {
        running = false;
        channel.close();
        Thread t;
        synchronized (this) {
            t = thread;
            thread = null;
        }
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void run() {
        ByteBuffer buf = ByteBuffer.allocate(256);
        while (running) {
            buf.clear();
            int n;
            try {
                n = channel.read(buf);
            } catch (PortUnreachableException e) {
                continue;
            } catch (ClosedChannelException e) {
                return;
            } catch (IOException e) {
                if (!running) {
                    return;
                }
                continue;
            }
            if (n > 0) {
                accept(buf.array(), n);
            }
        }
    }

    /** Takes one report into the state. */
    void accept(byte[] b, int n) {
        if (n < 5) {
            return;
        }
        long frame = (b[0] & 0xFFL) | (b[1] & 0xFFL) << 8 | (b[2] & 0xFFL) << 16
                | (b[3] & 0xFFL) << 24;
        int order = b[4] & 0xFF;
        long stamp = frame << 8 | order;
        synchronized (lock) {
            if (n == GroovyProtocol.INPUT_JOY_LENGTH
                    || n == GroovyProtocol.INPUT_JOY_ANALOG_LENGTH) {
                if (stamp == lastJoyStamp) {
                    return;
                }
                lastJoyStamp = stamp;
                joy1 = (b[5] & 0xFF) | (b[6] & 0xFF) << 8;
                joy2 = (b[7] & 0xFF) | (b[8] & 0xFF) << 8;
                joy1Seen |= joy1;
                joy2Seen |= joy2;
            } else if (keyboardAndMouse && (n == GroovyProtocol.INPUT_KEYBOARD_LENGTH
                    || n == GroovyProtocol.INPUT_MOUSE_LENGTH)) {
                if (stamp == lastPs2Stamp) {
                    return;
                }
                lastPs2Stamp = stamp;
                for (int i = 0; i < keys.length; i++) {
                    keys[i] = b[5 + i];
                    keysSeen[i] |= b[5 + i];
                }
                if (n == GroovyProtocol.INPUT_MOUSE_LENGTH) {
                    acceptMouse(b, order);
                }
            } else {
                return;
            }
        }
        reports++;
    }

    private void acceptMouse(byte[] b, int order) {
        int status = b[37] & 0xFF;
        mouseButtons = status & (GroovyProtocol.PS2_LEFT | GroovyProtocol.PS2_RIGHT
                | GroovyProtocol.PS2_MIDDLE);
        mouseButtonsSeen |= mouseButtons;
        if (order == 0) {
            return;
        }
        int dx = b[38] & 0xFF;
        int dy = b[39] & 0xFF;
        if ((status & GroovyProtocol.PS2_X_SIGN) != 0) {
            dx -= 256;
        }
        if ((status & GroovyProtocol.PS2_Y_SIGN) != 0) {
            dy -= 256;
        }
        // PS/2 counts upward movement as positive, the Amiga downward.
        mouseDx += dx;
        mouseDy -= dy;
    }
}
