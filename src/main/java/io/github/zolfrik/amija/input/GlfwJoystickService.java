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

import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWGamepadState;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.List;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Service reading physical joysticks/gamepads through GLFW (LWJGL).
 *
 * <p>GLFW is already a dependency of the project (used only as a binding, not for windowing:
 * OpenGL rendering goes through {@code AWTGLCanvas}/JAWT). GLFW's joystick API needs no
 * window: it queries the OS input subsystem directly. It must however be called entirely
 * from the thread that ran {@code glfwInit}: that is why the service owns a dedicated daemon
 * thread ("glfw-joystick") that initialises GLFW and polls at ~125 Hz.
 *
 * <p>The thread is started lazily at the first {@link #setPorts(List)} with at least one port
 * (i.e. only if the user selects "Joystick (real)"): while no port uses a real joystick, GLFW
 * is never initialised and there is no cost.
 *
 * <p>The joysticks present are assigned in order to the requesting ports: the first
 * controller connected drives the first port, the second the second, and so on. Both mapped
 * gamepads ({@code glfwGetGamepadState}, uniform XInput-like layout) and generic joysticks
 * (raw axes/hats/buttons) are supported.
 *
 * <p><b>Threading toward the emulation:</b> the service writes to the ports only on a
 * <i>change</i> of state (axes/buttons), exactly as the AWT adapters do from the EDT. The
 * {@code GamePort}s are already written by host threads (EDT) and read by the emulation
 * thread; this service follows the same model.
 *
 * <p>The ports are {@link GamePortControls}, not {@link GamePort}: in remote play the guest
 * has no machine, so no {@code GamePort} to drive, and its physical joystick must be able to
 * end up in a packet as mouse and keyboard do. This service uses nothing of what is behind
 * the interface.
 */
public final class GlfwJoystickService {

    /** Threshold beyond which an analogue axis counts as "pressed" (digital direction). */
    private static final float AXIS_THRESHOLD = 0.5f;
    /** Polling period (~125 Hz): responsive without weighing on the CPU. */
    private static final long POLL_INTERVAL_MS = 8;

    private static GlfwJoystickService instance;

    /** Singleton instance (a single GLFW initialisation per process). */
    public static synchronized GlfwJoystickService getInstance() {
        if (instance == null) instance = new GlfwJoystickService();
        return instance;
    }

    // Ports driven by the real joystick, in order, with the parallel previous state.
    private final Object lock = new Object();
    private GamePortControls[] ports = new GamePortControls[0];
    private Prev[] prev = new Prev[0];

    private volatile boolean running = false;
    private Thread thread;
    private GLFWErrorCallback errorCallback;
    private GLFWGamepadState gamepadState;

    private GlfwJoystickService() { }

    /** Previous state of a port, to send the {@link GamePort} only the changes. */
    private static final class Prev {
        int x = 0, y = 0;
        boolean fire, b2, b3;
        boolean valid = false; // false => forces the first send (even neutral)
    }

    /**
     * Sets the ports to drive with the real joystick (in order of preference). Empty list =
     * no port uses a real joystick (the thread, if started, stays idle). The first call with
     * a non-empty list starts the GLFW thread.
     *
     * <p>The ports that leave the list are <b>released</b>. Otherwise, changing the device of
     * a port with fire held would leave fire held forever: nobody looks at that port any
     * more, so nothing arrives to contradict it.
     *
     * <p>The swap and the release are <b>inside</b> the lock, which {@link #poll()} holds for
     * its whole pass. Releasing outside would not be enough: a pass entered an instant before
     * the swap is about to write to the old port, and if that write landed <i>after</i> the
     * release it would set fire held again just after it was let go, exactly the failure this
     * release exists to avoid.
     */
    public void setPorts(List<? extends GamePortControls> newPorts) {
        synchronized (lock) {
            for (GamePortControls p : ports) {
                if (!newPorts.contains(p)) {
                    apply(p, new Prev(), 0, 0, false, false, false);
                }
            }
            ports = newPorts.toArray(new GamePortControls[0]);
            prev = new Prev[ports.length];
            for (int i = 0; i < prev.length; i++) prev[i] = new Prev();
        }
        if (!newPorts.isEmpty()) ensureStarted();
    }

    private synchronized void ensureStarted() {
        if (running) return;
        running = true;
        thread = new Thread(this::run, "glfw-joystick");
        thread.setDaemon(true);
        thread.start();
    }

    /** Stops the thread and releases GLFW. To be called at JVM shutdown. */
    public synchronized void shutdown() {
        if (!running) return;
        running = false;
        if (thread != null) {
            try {
                thread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
    }

    // -------------------------------------------------------------------------
    // Thread GLFW
    // -------------------------------------------------------------------------

    private void run() {
        errorCallback = GLFWErrorCallback.createPrint(System.err);
        glfwSetErrorCallback(errorCallback);
        if (!glfwInit()) {
            System.err.println("[JOYSTICK] glfwInit fallita: joystick reale non disponibile");
            running = false;
            if (errorCallback != null) { errorCallback.free(); errorCallback = null; }
            return;
        }
        gamepadState = GLFWGamepadState.malloc();
        System.out.println("[JOYSTICK] Servizio joystick GLFW avviato");
        try {
            while (running) {
                glfwPollEvents(); // handles hot plugging/unplugging
                poll();
                try {
                    Thread.sleep(POLL_INTERVAL_MS);
                } catch (InterruptedException e) {
                    break;
                }
            }
        } finally {
            if (gamepadState != null) { gamepadState.free(); gamepadState = null; }
            glfwTerminate();
            if (errorCallback != null) { errorCallback.free(); errorCallback = null; }
            System.out.println("[JOYSTICK] Servizio joystick GLFW terminato");
        }
    }

    /**
     * One reading pass, <b>entirely under the lock</b>.
     *
     * <p>The lock is taken by {@link #setPorts} and here, and nowhere else: the contention is
     * between a thread passing every 8 ms and a reconfiguration that happens when the user
     * confirms the popup, so there is nothing to optimise. In exchange a port that leaves the
     * list cannot receive a late write <i>after</i> being released, the only way fire would
     * stay held forever on a port nobody looks at any more.
     */
    private void poll() {
        synchronized (lock) {
            GamePortControls[] snap = ports;
            Prev[] prevSnap = prev;
            if (snap.length == 0) return;

            // Assigns the present joysticks to the ports, in the order of their GLFW indices.
            int jid = GLFW_JOYSTICK_1;
            for (int i = 0; i < snap.length; i++) {
                GamePortControls port = snap[i];
                Prev p = prevSnap[i];
                while (jid <= GLFW_JOYSTICK_LAST && !glfwJoystickPresent(jid)) jid++;
                if (jid > GLFW_JOYSTICK_LAST) {
                    // No controller for this port: centre and release the buttons.
                    apply(port, p, 0, 0, false, false, false);
                    continue;
                }
                readAndApply(jid, port, p);
                jid++;
            }
        }
    }

    /** Reads the state of joystick {@code jid} and applies it to the port. */
    private void readAndApply(int jid, GamePortControls port, Prev p) {
        int x = 0, y = 0;
        boolean fire = false, b2 = false, b3 = false;

        if (glfwJoystickIsGamepad(jid) && glfwGetGamepadState(jid, gamepadState)) {
            // Gamepad with a known mapping: uniform layout (left stick + D-pad).
            float ax = gamepadState.axes(GLFW_GAMEPAD_AXIS_LEFT_X);
            float ay = gamepadState.axes(GLFW_GAMEPAD_AXIS_LEFT_Y);
            if (ax < -AXIS_THRESHOLD) x = -1; else if (ax > AXIS_THRESHOLD) x = 1;
            if (ay < -AXIS_THRESHOLD) y = -1; else if (ay > AXIS_THRESHOLD) y = 1;
            if (gamepadState.buttons(GLFW_GAMEPAD_BUTTON_DPAD_LEFT)  == GLFW_PRESS) x = -1;
            if (gamepadState.buttons(GLFW_GAMEPAD_BUTTON_DPAD_RIGHT) == GLFW_PRESS) x = 1;
            if (gamepadState.buttons(GLFW_GAMEPAD_BUTTON_DPAD_UP)    == GLFW_PRESS) y = -1;
            if (gamepadState.buttons(GLFW_GAMEPAD_BUTTON_DPAD_DOWN)  == GLFW_PRESS) y = 1;
            fire = gamepadState.buttons(GLFW_GAMEPAD_BUTTON_A) == GLFW_PRESS;
            b2   = gamepadState.buttons(GLFW_GAMEPAD_BUTTON_B) == GLFW_PRESS;
            b3   = gamepadState.buttons(GLFW_GAMEPAD_BUTTON_X) == GLFW_PRESS;
        } else {
            // Generic joystick: raw axes/hats/buttons.
            FloatBuffer axes = glfwGetJoystickAxes(jid);
            if (axes != null && axes.capacity() >= 2) {
                float ax = axes.get(0);
                float ay = axes.get(1);
                if (ax < -AXIS_THRESHOLD) x = -1; else if (ax > AXIS_THRESHOLD) x = 1;
                if (ay < -AXIS_THRESHOLD) y = -1; else if (ay > AXIS_THRESHOLD) y = 1;
            }
            ByteBuffer hats = glfwGetJoystickHats(jid);
            if (hats != null && hats.capacity() >= 1) {
                int h = hats.get(0);
                if ((h & GLFW_HAT_LEFT)  != 0) x = -1;
                if ((h & GLFW_HAT_RIGHT) != 0) x = 1;
                if ((h & GLFW_HAT_UP)    != 0) y = -1;
                if ((h & GLFW_HAT_DOWN)  != 0) y = 1;
            }
            ByteBuffer btns = glfwGetJoystickButtons(jid);
            if (btns != null) {
                if (btns.capacity() > 0) fire = btns.get(0) == GLFW_PRESS;
                if (btns.capacity() > 1) b2   = btns.get(1) == GLFW_PRESS;
                if (btns.capacity() > 2) b3   = btns.get(2) == GLFW_PRESS;
            }
        }

        apply(port, p, x, y, fire, b2, b3);
    }

    /** Sends the port only the values changed from the previous state. */
    private static void apply(GamePortControls port, Prev p,
            int x, int y, boolean fire, boolean b2, boolean b3) {
        if (!p.valid || p.x != x || p.y != y) {
            port.setJoystickAxes(x, y);
            p.x = x; p.y = y;
        }
        if (!p.valid || p.fire != fire) {
            port.pressJoystickFire(fire);
            p.fire = fire;
        }
        if (!p.valid || p.b2 != b2) {
            port.pressJoystickButton2(b2);
            p.b2 = b2;
        }
        if (!p.valid || p.b3 != b3) {
            port.pressJoystickButton3(b3);
            p.b3 = b3;
        }
        p.valid = true;
    }
}
