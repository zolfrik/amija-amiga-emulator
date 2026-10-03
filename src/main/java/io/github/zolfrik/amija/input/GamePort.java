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

package io.github.zolfrik.amija.input;

import io.github.zolfrik.amija.common.CIAPinSet;

/**
 * Amiga game port (port 1 = mouse, port 2 = joystick).
 *
 * Each port can host a mouse or a joystick. {@link #joydat()} returns the value to read in
 * Denise's JOY0DAT/JOY1DAT register.
 *
 * <p><b>The two quadrature counters live HERE, in the port, not in the device.</b> They are
 * Denise registers (one pair per port) and <b>exist even with the port empty</b>: JOYTEST
 * writes them anyway, and JOYxDAT reads them back anyway. The device does not own them, it
 * <i>drives</i> them: the mouse adds its movements to them, the joystick imposes their two
 * low bits. They are indexed by <b>port</b>, never by device, and JOYTEST writes both ports
 * without looking at what is plugged in.
 *
 * <p>The bits of the POTINP register are composed by Paula, which queries this port with
 * {@link #pullsUpPotLines()}, {@link #isRightOrButton2Pressed()} and
 * {@link #isMiddleOrButton3Pressed()}: the port says what is attached, the register's rule
 * is all in {@code Paula.potinpBit}.
 *
 * The main fire button (left mouse / joystick fire) is reflected straight onto the CIA-A
 * port A pin through {@code CIAPinSet}:
 *   port 1 -> bit 6 (mask 0x40), port 2 -> bit 7 (mask 0x80), active LOW.
 *
 * Reference: Amiga Hardware Reference Manual.
 */
public class GamePort implements GamePortControls {

    private final int portNumber;             // 1 = mouse port, 2 = joystick port
    private GamePortDevice device = GamePortDevice.NONE;

    public final AmigaMouse    mouse    = new AmigaMouse();
    public final AmigaJoystick joystick = new AmigaJoystick();

    // CIA-A port A pins for the fire button (set by whoever creates the port)
    private CIAPinSet ciaAPins = null;

    // Index of the CIA-A pin for this port's fire (bit 6 for port 1, bit 7 for port 2)
    private final int ciaFireBit;

    // Denise's quadrature counters for THIS port (8 bits each, wrapping). X = low byte of
    // JOYxDAT, Y = high byte. See the class comment: they belong to the chip, not to the
    // device, and live with the port empty too.
    private int counterX = 0;
    private int counterY = 0;

    public GamePort(int portNumber) {
        this.portNumber  = portNumber;
        this.ciaFireBit  = (portNumber == 1) ? 6 : 7;
    }

    /** Connects the CIA-A pins to update the fire button in real time. */
    public void setCIAAPins(CIAPinSet pins) {
        this.ciaAPins = pins;
        // Initialise the pin as not pressed (HIGH = not active)
        if (pins != null) {
            pins.setExternalInput(0, ciaFireBit, true);
        }
    }

    public GamePortDevice getDevice()             { return device; }

    @Override
    public void setDevice(GamePortDevice d) { device = d; }

    // -------------------------------------------------------------------------
    // Eventi mouse
    // -------------------------------------------------------------------------

    /**
     * Moves the mouse cursor by (dx, dy) pixels. Sets device = MOUSE.
     *
     * <p>The movement goes straight into the port's counters (the host deltas are added to
     * them).
     */
    public synchronized void moveMouse(int dx, int dy) {
        device = GamePortDevice.MOUSE;
        counterX = (counterX + dx) & 0xFF;
        counterY = (counterY + dy) & 0xFF;
    }

    /** Presses or releases the left button. Updates the CIA-A pin. */
    public void pressMouseLeft(boolean pressed) {
        device = GamePortDevice.MOUSE;
        mouse.setLeftButton(pressed);
        updateCIAFirePin(pressed);
    }

    /** Presses or releases the right button (read via POTINP). */
    public void pressMouseRight(boolean pressed) {
        device = GamePortDevice.MOUSE;
        mouse.setRightButton(pressed);
    }

    /** Presses or releases the middle button (read via POTINP). */
    public void pressMouseMiddle(boolean pressed) {
        device = GamePortDevice.MOUSE;
        mouse.setMiddleButton(pressed);
    }

    // -------------------------------------------------------------------------
    // Eventi joystick
    // -------------------------------------------------------------------------

    /** Sets the joystick axes. Sets device = JOYSTICK. */
    public synchronized void setJoystickAxes(int x, int y) {
        device = GamePortDevice.JOYSTICK;
        joystick.setAxisX(x);
        joystick.setAxisY(y);
        joyMouseCounter();
    }

    /** Presses or releases the main fire. Updates the CIA-A pin. */
    public void pressJoystickFire(boolean pressed) {
        device = GamePortDevice.JOYSTICK;
        joystick.setFireButton(pressed);
        updateCIAFirePin(pressed);
    }

    /** Presses or releases button 2 (read via POTINP). */
    public void pressJoystickButton2(boolean pressed) {
        device = GamePortDevice.JOYSTICK;
        joystick.setButton2(pressed);
    }

    /** Presses or releases button 3 (read via POTINP). */
    public void pressJoystickButton3(boolean pressed) {
        device = GamePortDevice.JOYSTICK;
        joystick.setButton3(pressed);
    }

    // -------------------------------------------------------------------------
    // Interfaccia hardware
    // -------------------------------------------------------------------------

    /**
     * Value to return for JOY0DAT (port 1) or JOY1DAT (port 2): bits 15:8 = Y counter,
     * bits 7:0 = X counter.
     *
     * <p><b>It does not depend on what is plugged in.</b> An empty port does not read 0: it
     * reads its own counters, written by the last JOYTEST like those of the port with the
     * mouse. That is exactly what {@code Mainboard/Ports/JOYxDAT/joytest1} measures, where the
     * program paints JOY0DATR and JOY1DATR one after the other after writing JOYTEST: the
     * reference shows a <i>continuous</i> gradient because the two registers return the same
     * value, not a value and black.
     */
    public synchronized int joydat() {
        return (counterY << 8) | counterX;
    }

    /**
     * Does the attached device <b>pull up</b> this port's two POTX/POTY lines?
     *
     * <p>Paula has no pull-up on those pins: the device pulls them up, and if nothing does,
     * the capacitor stays discharged and POTGOR's DAT bits read <b>0</b>. An empty port
     * therefore reads 0, not 1.
     *
     * <p>It is decided per device: the mouse pulls up; a joystick does only when it is a
     * multi-button pad. Our {@code JOYSTICK} models a multi-button pad (button2/3 come from
     * real joysticks through GLFW), which does have the pull-up. A one-button digital
     * joystick is indistinguishable from an empty port on the POT lines.
     */
    public boolean pullsUpPotLines() {
        return device == GamePortDevice.MOUSE || device == GamePortDevice.JOYSTICK;
    }

    /**
     * Whether the right button (or button2) is pressed. Used by Paula in {@code capCheck}:
     * the button holds the line to ground.
     */
    public boolean isRightOrButton2Pressed() {
        return (device == GamePortDevice.MOUSE)    ? mouse.isRightPressed()
             : (device == GamePortDevice.JOYSTICK) ? joystick.isButton2Pressed() : false;
    }

    /**
     * Whether the middle button (or button3) is pressed. Used by Paula in {@code capCheck}:
     * the button holds the line to ground.
     */
    public boolean isMiddleOrButton3Pressed() {
        return (device == GamePortDevice.MOUSE)    ? mouse.isMiddlePressed()
             : (device == GamePortDevice.JOYSTICK) ? joystick.isButton3Pressed() : false;
    }

    /**
     * Counter preset from JOYTEST ($DFF036).
     *
     * <p>The 6 high bits of each byte overwrite the counter, the 2 low ones (the instantaneous
     * quadrature state) stay as they are: {@code x &= 3; x |= v & 0xFC;}
     */
    public synchronized void pokeJoytest(int value) {
        counterX = (counterX & 0x03) | (value & 0xFC);
        counterY = (counterY & 0x03) | ((value >> 8) & 0xFC);
    }

    public int getPortNumber() { return portNumber; }

    public synchronized void reset() {
        counterX = counterY = 0;
        mouse.reset();
        joystick.reset();
        if (ciaAPins != null) {
            ciaAPins.setExternalInput(0, ciaFireBit, true); // rilasciato
        }
    }

    // -------------------------------------------------------------------------
    // Privato
    // -------------------------------------------------------------------------

    /**
     * The joystick direction is <b>not</b> a register of its own: it is the pair of
     * quadrature bits at the bottom of the port's counters, the same the mouse moves.
     *
     * <p>So a joystick advances the 6 high bits: the 0 -> 3 transition of the two-bit code is
     * a quadrature turn backward ({@code -4}), the 3 -> 0 one forward ({@code +4}). That lets
     * the OS driver compute a signed delta between two consecutive reads without knowing what
     * is plugged in.
     */
    private void joyMouseCounter() {
        final int cntx = joystick.quadX();
        final int cnty = joystick.quadY();
        final int ocntx = counterX & 3;
        final int ocnty = counterY & 3;

        if (cntx == 3 && ocntx == 0)      counterX = (counterX - 4) & 0xFF;
        else if (cntx == 0 && ocntx == 3) counterX = (counterX + 4) & 0xFF;
        counterX = (counterX & 0xFC) | cntx;

        if (cnty == 3 && ocnty == 0)      counterY = (counterY - 4) & 0xFF;
        else if (cnty == 0 && ocnty == 3) counterY = (counterY + 4) & 0xFF;
        counterY = (counterY & 0xFC) | cnty;
    }

    private void updateCIAFirePin(boolean pressed) {
        if (ciaAPins != null) {
            // Active-LOW: premuto = GND (false), rilasciato = HIGH (true)
            ciaAPins.setExternalInput(0, ciaFireBit, !pressed);
        }
    }
}
