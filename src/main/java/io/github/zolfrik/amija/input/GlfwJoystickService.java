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
 * thread ("glfw-joystick") that initialises GLFW and polls every 2 ms.
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
    /**
     * Polling period. The time a change waits to be seen is on average half the period, and
     * a sleep on Windows lasts about 0.6 ms longer than asked: 2 ms keep that wait near 1 ms,
     * well below the USB report interval of most controllers, at a cost of a few
     * microseconds per poll.
     */
    private static final long POLL_INTERVAL_MS = 2;

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

    /** Which control drives each pad button; replaced whole, read by the polling thread. */
    private volatile JoypadMap joypadMap = JoypadMap.defaults();

    /** Waiting for the next control pressed, or null. Guarded by {@link #lock}. */
    private java.util.function.Consumer<String> capture;
    /** Controls held when the capture started or at the last pass: only a new press counts. */
    private java.util.Set<String> captureHeld;
    /** Whether a controller was present at the last pass. */
    private volatile boolean controllerPresent;

    private GlfwJoystickService() { }

    /** Sets which control drives each pad button (applies from the next pass). */
    public void setJoypadMap(JoypadMap map) {
        joypadMap = map == null ? JoypadMap.defaults() : map.copy();
    }

    /** Whether a controller was connected at the last reading (starts the service if needed). */
    public boolean isControllerPresent() {
        ensureStarted();
        return controllerPresent;
    }

    /**
     * Waits for the next control pressed on the first controller and hands its token to
     * {@code onToken} on the Swing thread. Controls already held do not count. A new call
     * replaces the pending one; {@code null} cancels it.
     */
    public void captureNextControl(java.util.function.Consumer<String> onToken) {
        synchronized (lock) {
            capture = onToken;
            captureHeld = null;
        }
        if (onToken != null) ensureStarted();
    }

    /** Previous state of a port, to send the {@link GamePort} only the changes. */
    private static final class Prev {
        int x = 0, y = 0;
        boolean fire, b2, b3;
        /** CD32 extra buttons held, bit n = GamePort.CD32 button n. */
        int cd32;
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
                    apply(p, new Prev(), 0, 0, false, false, false, 0);
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
            System.err.println("[JOYSTICK] glfwInit failed: real joystick unavailable");
            running = false;
            if (errorCallback != null) { errorCallback.free(); errorCallback = null; }
            return;
        }
        gamepadState = GLFWGamepadState.malloc();
        System.out.println("[JOYSTICK] GLFW joystick service started");
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
            System.out.println("[JOYSTICK] GLFW joystick service stopped");
        }
    }

    /**
     * One reading pass, <b>entirely under the lock</b>.
     *
     * <p>The lock is taken by {@link #setPorts} and here, and nowhere else: the contention is
     * between a thread passing every 2 ms and a reconfiguration that happens when the user
     * confirms the popup, so there is nothing to optimise. In exchange a port that leaves the
     * list cannot receive a late write <i>after</i> being released, the only way fire would
     * stay held forever on a port nobody looks at any more.
     */
    private void poll() {
        synchronized (lock) {
            int first = GLFW_JOYSTICK_1;
            while (first <= GLFW_JOYSTICK_LAST && !glfwJoystickPresent(first)) first++;
            controllerPresent = first <= GLFW_JOYSTICK_LAST;
            if (capture != null && controllerPresent) pollCapture(first);

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
                    apply(port, p, 0, 0, false, false, false, 0);
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
        boolean gamepad = glfwJoystickIsGamepad(jid) && glfwGetGamepadState(jid, gamepadState);
        if (gamepad) {
            // Gamepad with a known mapping: uniform layout (left stick + D-pad).
            float ax = gamepadState.axes(GLFW_GAMEPAD_AXIS_LEFT_X);
            float ay = gamepadState.axes(GLFW_GAMEPAD_AXIS_LEFT_Y);
            if (ax < -AXIS_THRESHOLD) x = -1; else if (ax > AXIS_THRESHOLD) x = 1;
            if (ay < -AXIS_THRESHOLD) y = -1; else if (ay > AXIS_THRESHOLD) y = 1;
            if (gamepadState.buttons(GLFW_GAMEPAD_BUTTON_DPAD_LEFT)  == GLFW_PRESS) x = -1;
            if (gamepadState.buttons(GLFW_GAMEPAD_BUTTON_DPAD_RIGHT) == GLFW_PRESS) x = 1;
            if (gamepadState.buttons(GLFW_GAMEPAD_BUTTON_DPAD_UP)    == GLFW_PRESS) y = -1;
            if (gamepadState.buttons(GLFW_GAMEPAD_BUTTON_DPAD_DOWN)  == GLFW_PRESS) y = 1;
        } else {
            // Generic joystick: raw axes/hats.
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
        }
        ByteBuffer raw = glfwGetJoystickButtons(jid);

        // The buttons, through the pad mapping: red is the fire line, blue the second
        // button, green the third (the middle line of a plain joystick).
        JoypadMap map = joypadMap;
        boolean fire = pressed(map.get(JoystickAction.FIRE), gamepad, raw);
        boolean b2 = pressed(map.get(JoystickAction.BLUE), gamepad, raw);
        boolean b3 = pressed(map.get(JoystickAction.GREEN), gamepad, raw);
        int cd32 = 0;
        if (b3) cd32 |= 1 << GamePort.CD32_GREEN;
        if (pressed(map.get(JoystickAction.YELLOW), gamepad, raw)) cd32 |= 1 << GamePort.CD32_YELLOW;
        if (pressed(map.get(JoystickAction.REWIND), gamepad, raw)) cd32 |= 1 << GamePort.CD32_REWIND;
        if (pressed(map.get(JoystickAction.FORWARD), gamepad, raw)) cd32 |= 1 << GamePort.CD32_FORWARD;
        if (pressed(map.get(JoystickAction.PLAY), gamepad, raw)) cd32 |= 1 << GamePort.CD32_PLAY;

        // On a CD32 pad the third button is green, not the middle line.
        if (port.isCd32Pad()) b3 = false;
        else cd32 = 0;
        apply(port, p, x, y, fire, b2, b3, cd32);
    }

    /**
     * Whether the control a token names is held. Layout tokens are read from the gamepad
     * state when the controller has the layout, otherwise from the raw button the layout
     * usually puts there; raw tokens always from the raw buttons.
     */
    private boolean pressed(String token, boolean gamepad, ByteBuffer raw) {
        if (token.isEmpty()) return false;
        if (gamepad) {
            int layout = JoypadMap.layoutIndex(token);
            if (layout >= 0) return gamepadState.buttons(layout) == GLFW_PRESS;
            if (token.equals(JoypadMap.LEFT_TRIGGER)) {
                return gamepadState.axes(GLFW_GAMEPAD_AXIS_LEFT_TRIGGER) > 0;
            }
            if (token.equals(JoypadMap.RIGHT_TRIGGER)) {
                return gamepadState.axes(GLFW_GAMEPAD_AXIS_RIGHT_TRIGGER) > 0;
            }
        }
        int index = token.equals(JoypadMap.LEFT_TRIGGER) || token.equals(JoypadMap.RIGHT_TRIGGER)
                ? -1 : JoypadMap.rawIndex(token);
        return index >= 0 && raw != null && index < raw.capacity() && raw.get(index) == GLFW_PRESS;
    }

    /**
     * One pass of a pending capture on controller {@code jid}: the controls held now, and the
     * first one not held at the previous pass, if any, is delivered. A controller with the
     * gamepad layout answers with layout tokens, any other with raw buttons.
     */
    private void pollCapture(int jid) {
        java.util.Set<String> held = new java.util.LinkedHashSet<>();
        if (glfwJoystickIsGamepad(jid) && glfwGetGamepadState(jid, gamepadState)) {
            for (int b = 0; b < JoypadMap.LAYOUT_BUTTONS.length; b++) {
                if (gamepadState.buttons(b) == GLFW_PRESS) held.add(JoypadMap.LAYOUT_BUTTONS[b]);
            }
            if (gamepadState.axes(GLFW_GAMEPAD_AXIS_LEFT_TRIGGER) > 0) held.add(JoypadMap.LEFT_TRIGGER);
            if (gamepadState.axes(GLFW_GAMEPAD_AXIS_RIGHT_TRIGGER) > 0) held.add(JoypadMap.RIGHT_TRIGGER);
        } else {
            ByteBuffer raw = glfwGetJoystickButtons(jid);
            if (raw != null) {
                for (int b = 0; b < raw.capacity(); b++) {
                    if (raw.get(b) == GLFW_PRESS) held.add(JoypadMap.rawButton(b));
                }
            }
        }
        java.util.Set<String> before = captureHeld;
        captureHeld = held;
        if (before == null) return;
        for (String token : held) {
            if (!before.contains(token)) {
                java.util.function.Consumer<String> target = capture;
                capture = null;
                captureHeld = null;
                javax.swing.SwingUtilities.invokeLater(() -> target.accept(token));
                return;
            }
        }
    }

    /** Sends the port only the values changed from the previous state. */
    private static void apply(GamePortControls port, Prev p,
            int x, int y, boolean fire, boolean b2, boolean b3, int cd32) {
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
        int changed = p.valid ? p.cd32 ^ cd32 : 0x1F;
        for (int b = 0; b < 5; b++) {
            if ((changed & (1 << b)) != 0) port.pressCd32Button(b, (cd32 & (1 << b)) != 0);
        }
        p.cd32 = cd32;
        p.valid = true;
    }
}
