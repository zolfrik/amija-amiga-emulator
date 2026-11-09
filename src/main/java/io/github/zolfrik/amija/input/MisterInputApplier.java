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

import io.github.zolfrik.amija.net.mister.GroovyProtocol;
import io.github.zolfrik.amija.net.mister.MisterInputState;
import io.github.zolfrik.amija.net.mister.MisterInputTarget;

import java.util.Arrays;

/**
 * Applies the controls plugged into the MiSTer to the machine, once per frame on the
 * emulation thread, following the same choices as the PC's controls.
 *
 * <h2>Where each control goes</h2>
 * Each game port takes the device chosen for it, read from the MiSTer instead of the PC:
 * <ul>
 *   <li>mouse: the MiSTer's mouse (the first port set to it);</li>
 *   <li>real joystick: the MiSTer's joysticks, the first to the first port set to it and the
 *       second to the other;</li>
 *   <li>keyboard joystick: the MiSTer's keyboard, with the port's own keys found at the same
 *       positions ({@link HidKeyMap#hostKey}); those keys do not reach the Amiga keyboard;</li>
 *   <li>empty: nothing.</li>
 * </ul>
 * The rest of the MiSTer's keyboard is the Amiga keyboard. On a joystick, button 1 is fire,
 * buttons 2 and 3 the second and third button; the physical button behind each is chosen in
 * the MiSTer's own menu, as for every core.
 *
 * <p>Every gesture is applied by difference with what was applied before: keys that land on
 * the same Amiga key (both Ctrl keys, Alt and GUI) are counted, so releasing one while the
 * other is held keeps the Amiga key down. CTRL+Amiga+Amiga resets the machine, as from the
 * PC's keyboard.
 */
public final class MisterInputApplier implements MisterInputTarget {

    private static final int PORTS = 2;
    private static final int USAGES = GroovyProtocol.INPUT_KEY_BYTES * 8;

    private final GamePortControls[] ports;
    private final AmigaKeyTarget keyboard;

    private final PortDevice[] devices = {PortDevice.NONE, PortDevice.NONE};
    /** For each port and HID usage, the keyboard joystick action it drives, or null. */
    private final JoystickAction[][] keyActions = new JoystickAction[PORTS][USAGES];
    /** HID usages that drive a keyboard joystick and so never reach the Amiga keyboard. */
    private final boolean[] joystickKey = new boolean[USAGES];

    private final MisterInputState idle = new MisterInputState();
    private final boolean[] keyDown = new boolean[USAGES];
    private final int[] rawHeld = new int[128];
    /** What each port was last given: axes, buttons. */
    private final int[] axisX = new int[PORTS];
    private final int[] axisY = new int[PORTS];
    private final int[] buttons = new int[PORTS];

    private static final int FIRE = 1;
    private static final int BUTTON2 = 2;
    private static final int BUTTON3 = 4;
    private static final int MOUSE_LEFT = 8;
    private static final int MOUSE_RIGHT = 16;
    private static final int MOUSE_MIDDLE = 32;

    /**
     * @param port0    game port 1 ("Port 0", the mouse port)
     * @param port1    game port 2 ("Port 1", the joystick port)
     * @param keyboard the Amiga keyboard
     */
    public MisterInputApplier(GamePortControls port0, GamePortControls port1,
                              AmigaKeyTarget keyboard) {
        this.ports = new GamePortControls[]{port0, port1};
        this.keyboard = keyboard;
    }

    /**
     * Sets the device of each port and the keys of its keyboard joystick. What was held on
     * the previous devices is released first.
     */
    public synchronized void configure(PortDevice device0, JoystickKeyMap keys0,
                                       PortDevice device1, JoystickKeyMap keys1) {
        apply(idle);
        devices[0] = device0 == null ? PortDevice.NONE : device0;
        devices[1] = device1 == null ? PortDevice.NONE : device1;
        JoystickKeyMap[] maps = {keys0, keys1};
        Arrays.fill(joystickKey, false);
        for (int p = 0; p < PORTS; p++) {
            Arrays.fill(keyActions[p], null);
            if (devices[p] != PortDevice.JOYSTICK_KEYBOARD || maps[p] == null) {
                continue;
            }
            for (int usage = 0; usage < USAGES; usage++) {
                KeyBinding host = HidKeyMap.hostKey(usage);
                JoystickAction a = host == null ? null : maps[p].actionFor(host);
                if (a != null) {
                    keyActions[p][usage] = a;
                    joystickKey[usage] = true;
                }
            }
        }
    }

    @Override
    public synchronized void apply(MisterInputState s) {
        int nextJoystick = 0;
        boolean mouseTaken = false;
        for (int p = 0; p < PORTS; p++) {
            switch (devices[p]) {
                case MOUSE -> {
                    if (!mouseTaken) {
                        mouseTaken = true;
                        applyMouse(p, s);
                    }
                }
                case JOYSTICK_REAL -> {
                    int bits = nextJoystick++ == 0 ? s.joy1 : s.joy2;
                    applyJoystick(p, axis(bits, GroovyProtocol.JOY_RIGHT, GroovyProtocol.JOY_LEFT),
                            axis(bits, GroovyProtocol.JOY_DOWN, GroovyProtocol.JOY_UP),
                            ((bits & GroovyProtocol.JOY_B1) != 0 ? FIRE : 0)
                                    | ((bits & GroovyProtocol.JOY_B2) != 0 ? BUTTON2 : 0)
                                    | ((bits & GroovyProtocol.JOY_B3) != 0 ? BUTTON3 : 0));
                }
                case JOYSTICK_KEYBOARD -> applyKeyboardJoystick(p, s.keys);
                case NONE -> {
                    // nothing plugged in
                }
            }
        }
        applyKeys(s.keys);
    }

    @Override
    public synchronized void releaseAll() {
        apply(idle);
    }

    /** As the PC's keyboard joystick: right wins over left, down over up. */
    private void applyKeyboardJoystick(int p, byte[] keys) {
        boolean up = false;
        boolean down = false;
        boolean left = false;
        boolean right = false;
        boolean fire = false;
        for (int usage = 0; usage < USAGES; usage++) {
            JoystickAction a = keyActions[p][usage];
            if (a == null || !held(keys, usage)) {
                continue;
            }
            switch (a) {
                case UP -> up = true;
                case DOWN -> down = true;
                case LEFT -> left = true;
                case RIGHT -> right = true;
                case FIRE -> fire = true;
            }
        }
        applyJoystick(p, right ? 1 : left ? -1 : 0, down ? 1 : up ? -1 : 0, fire ? FIRE : 0);
    }

    private void applyJoystick(int p, int x, int y, int now) {
        GamePortControls port = ports[p];
        if (port == null) {
            return;
        }
        if (x != axisX[p] || y != axisY[p]) {
            axisX[p] = x;
            axisY[p] = y;
            port.setJoystickAxes(x, y);
        }
        int changed = now ^ buttons[p];
        buttons[p] = now;
        if ((changed & FIRE) != 0) {
            port.pressJoystickFire((now & FIRE) != 0);
        }
        if ((changed & BUTTON2) != 0) {
            port.pressJoystickButton2((now & BUTTON2) != 0);
        }
        if ((changed & BUTTON3) != 0) {
            port.pressJoystickButton3((now & BUTTON3) != 0);
        }
    }

    private void applyMouse(int p, MisterInputState s) {
        GamePortControls port = ports[p];
        if (port == null) {
            return;
        }
        if (s.mouseDx != 0 || s.mouseDy != 0) {
            port.moveMouse(s.mouseDx, s.mouseDy);
        }
        int now = ((s.mouseButtons & GroovyProtocol.PS2_LEFT) != 0 ? MOUSE_LEFT : 0)
                | ((s.mouseButtons & GroovyProtocol.PS2_RIGHT) != 0 ? MOUSE_RIGHT : 0)
                | ((s.mouseButtons & GroovyProtocol.PS2_MIDDLE) != 0 ? MOUSE_MIDDLE : 0);
        int changed = now ^ buttons[p];
        buttons[p] = now;
        if ((changed & MOUSE_LEFT) != 0) {
            port.pressMouseLeft((now & MOUSE_LEFT) != 0);
        }
        if ((changed & MOUSE_RIGHT) != 0) {
            port.pressMouseRight((now & MOUSE_RIGHT) != 0);
        }
        if ((changed & MOUSE_MIDDLE) != 0) {
            port.pressMouseMiddle((now & MOUSE_MIDDLE) != 0);
        }
    }

    /** +1, -1 or 0; both directions at once cancel out. */
    private static int axis(int bits, int plus, int minus) {
        return ((bits & plus) != 0 ? 1 : 0) - ((bits & minus) != 0 ? 1 : 0);
    }

    private static boolean held(byte[] keys, int usage) {
        return (keys[usage >> 3] & (1 << (usage & 7))) != 0;
    }

    private void applyKeys(byte[] keys) {
        if (keyboard == null) {
            return;
        }
        for (int usage = 0; usage < USAGES; usage++) {
            boolean now = held(keys, usage) && !joystickKey[usage];
            if (now != keyDown[usage]) {
                keyDown[usage] = now;
                key(HidKeyMap.rawKey(usage), now);
            }
        }
    }

    private void key(int raw, boolean pressed) {
        if (raw < 0) {
            return;
        }
        if (pressed) {
            if (rawHeld[raw]++ == 0) {
                keyboard.pressKey(raw);
            }
        } else if (rawHeld[raw] > 0 && --rawHeld[raw] == 0) {
            keyboard.releaseKey(raw);
        }
    }
}
