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
 * Game port dell'Amiga (porta 1 = mouse, porta 2 = joystick).
 *
 * Ogni porta può ospitare un mouse o un joystick. Il metodo {@link #joydat()}
 * restituisce il valore da leggere nel registro JOY0DAT/JOY1DAT di Denise.
 * Il metodo {@link #modifyPotinp(int)} aggiusta i bit del registro POTINP
 * (letto da Paula) in base allo stato dei pulsanti destro/medio del mouse
 * o dei pulsanti 2/3 del joystick.
 *
 * Il pulsante fuoco principale (sinistro del mouse / fire del joystick) viene
 * riflesso direttamente sul pin CIA-A Port A mediante {@code CIAPinSet}:
 *   porta 1 → bit 6 (mask 0x40), porta 2 → bit 7 (mask 0x80), active-LOW.
 *
 * Riferimenti: vAmiga ControlPort.cpp, Amiga HW Ref Manual.
 */
public class GamePort {

    private final int portNumber;             // 1 = mouse port, 2 = joystick port
    private GamePortDevice device = GamePortDevice.NONE;

    public final AmigaMouse    mouse    = new AmigaMouse();
    public final AmigaJoystick joystick = new AmigaJoystick();

    // Pin CIA-A Port A per il pulsante fuoco (impostato da chi instanzia la porta)
    private CIAPinSet ciaAPins = null;

    // Indice del pin CIA-A per il fuoco di questa porta (bit 6 per porta 1, bit 7 per porta 2)
    private final int ciaFireBit;

    public GamePort(int portNumber) {
        this.portNumber  = portNumber;
        this.ciaFireBit  = (portNumber == 1) ? 6 : 7;
    }

    /** Collega i pin del CIA-A per aggiornare il pulsante fuoco in tempo reale. */
    public void setCIAAPins(CIAPinSet pins) {
        this.ciaAPins = pins;
        // Inizializza il pin come non premuto (HIGH = non attivo)
        if (pins != null) {
            pins.setExternalInput(0, ciaFireBit, true);
        }
    }

    public GamePortDevice getDevice()             { return device; }
    public void           setDevice(GamePortDevice d) { device = d; }

    // -------------------------------------------------------------------------
    // Eventi mouse
    // -------------------------------------------------------------------------

    /** Muove il cursore del mouse di (dx, dy) pixel. Imposta device = MOUSE. */
    public void moveMouse(int dx, int dy) {
        device = GamePortDevice.MOUSE;
        mouse.move(dx, dy);
    }

    /** Preme o rilascia il pulsante sinistro. Aggiorna il pin CIA-A. */
    public void pressMouseLeft(boolean pressed) {
        device = GamePortDevice.MOUSE;
        mouse.setLeftButton(pressed);
        updateCIAFirePin(pressed);
    }

    /** Preme o rilascia il pulsante destro (letto via POTINP). */
    public void pressMouseRight(boolean pressed) {
        device = GamePortDevice.MOUSE;
        mouse.setRightButton(pressed);
    }

    /** Preme o rilascia il pulsante medio (letto via POTINP). */
    public void pressMouseMiddle(boolean pressed) {
        device = GamePortDevice.MOUSE;
        mouse.setMiddleButton(pressed);
    }

    // -------------------------------------------------------------------------
    // Eventi joystick
    // -------------------------------------------------------------------------

    /** Imposta gli assi del joystick. Imposta device = JOYSTICK. */
    public void setJoystickAxes(int x, int y) {
        device = GamePortDevice.JOYSTICK;
        joystick.setAxisX(x);
        joystick.setAxisY(y);
    }

    /** Preme o rilascia il fuoco principale. Aggiorna il pin CIA-A. */
    public void pressJoystickFire(boolean pressed) {
        device = GamePortDevice.JOYSTICK;
        joystick.setFireButton(pressed);
        updateCIAFirePin(pressed);
    }

    /** Preme o rilascia il pulsante 2 (letto via POTINP). */
    public void pressJoystickButton2(boolean pressed) {
        device = GamePortDevice.JOYSTICK;
        joystick.setButton2(pressed);
    }

    /** Preme o rilascia il pulsante 3 (letto via POTINP). */
    public void pressJoystickButton3(boolean pressed) {
        device = GamePortDevice.JOYSTICK;
        joystick.setButton3(pressed);
    }

    // -------------------------------------------------------------------------
    // Interfaccia hardware
    // -------------------------------------------------------------------------

    /**
     * Valore da restituire per JOY0DAT (porta 1) o JOY1DAT (porta 2).
     *
     * Mouse:     contatori di quadratura a 8 bit per X e Y.
     * Joystick:  codifica direzioni nei 4 bit meno significativi.
     * Nessuno:   0.
     */
    public int joydat() {
        return switch (device) {
            case MOUSE    -> mouse.joydat();
            case JOYSTICK -> joystick.joydat();
            default       -> 0;
        };
    }

    /**
     * Aggiusta i bit del registro POTINP in base allo stato dei pulsanti.
     *
     * I pulsanti destro e medio del mouse (o pulsanti 2 e 3 del joystick)
     * sono connessi alle linee potenziometro e le portano a GND quando premuti.
     *
     *   Porta 1: bit 10 (DATLY) = destro/button2, bit  8 (DATLX) = medio/button3
     *   Porta 2: bit 14 (DATRY) = destro/button2, bit 12 (DATRX) = medio/button3
     *
     * Quando il pulsante è premuto, il bit corrispondente è 0 (linea a GND).
     * Quando il pulsante è rilasciato, il bit è 1 (pull-up attivo).
     *
     * @param potinp valore POTINP calcolato dalla simulazione di carica in Paula
     * @return valore POTINP modificato
     */
    public int modifyPotinp(int potinp) {
        boolean right  = (device == GamePortDevice.MOUSE)    ? mouse.isRightPressed()
                       : (device == GamePortDevice.JOYSTICK) ? joystick.isButton2Pressed() : false;
        boolean middle = (device == GamePortDevice.MOUSE)    ? mouse.isMiddlePressed()
                       : (device == GamePortDevice.JOYSTICK) ? joystick.isButton3Pressed() : false;

        int maskR = (portNumber == 1) ? 0x0400 : 0x4000;
        int maskM = (portNumber == 1) ? 0x0100 : 0x1000;

        if (right)  potinp &= ~maskR; else potinp |= maskR;
        if (middle) potinp &= ~maskM; else potinp |= maskM;

        return potinp;
    }

    /**
     * Indica se il pulsante destro (o button2) è premuto.
     * Usato da Paula nella simulazione di carica (POT_CHARGE).
     */
    public boolean isRightOrButton2Pressed() {
        return (device == GamePortDevice.MOUSE)    ? mouse.isRightPressed()
             : (device == GamePortDevice.JOYSTICK) ? joystick.isButton2Pressed() : false;
    }

    /**
     * Indica se il pulsante medio (o button3) è premuto.
     * Usato da Paula nella simulazione di carica (POT_CHARGE).
     */
    public boolean isMiddleOrButton3Pressed() {
        return (device == GamePortDevice.MOUSE)    ? mouse.isMiddlePressed()
             : (device == GamePortDevice.JOYSTICK) ? joystick.isButton3Pressed() : false;
    }

    /** Preset dei contatori mouse da JOYTEST ($DFF036). */
    public void pokeJoytest(int value) {
        mouse.pokeJoytest(value);
    }

    public int getPortNumber() { return portNumber; }

    public void reset() {
        mouse.reset();
        joystick.reset();
        if (ciaAPins != null) {
            ciaAPins.setExternalInput(0, ciaFireBit, true); // rilasciato
        }
    }

    // -------------------------------------------------------------------------
    // Privato
    // -------------------------------------------------------------------------

    private void updateCIAFirePin(boolean pressed) {
        if (ciaAPins != null) {
            // Active-LOW: premuto = GND (false), rilasciato = HIGH (true)
            ciaAPins.setExternalInput(0, ciaFireBit, !pressed);
        }
    }
}
