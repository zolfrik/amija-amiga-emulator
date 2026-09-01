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
 *
 * <p>⛔ <b>I due contatori di quadratura stanno QUI, nella porta, non nel dispositivo.</b>
 * Sono registri di Denise — una coppia per porta — e <b>esistono anche a porta vuota</b>:
 * JOYTEST li scrive comunque, e JOYxDAT li rilegge comunque. Il dispositivo non li
 * possiede, li <i>pilota</i>: il mouse ci somma i suoi spostamenti, il joystick ne
 * impone i due bit bassi. Oracolo: WinUAE {@code inputdevice.cpp}, dove sono i due array
 * globali {@code mouse_x[MAX_JPORTS]} / {@code mouse_y[MAX_JPORTS]} — indicizzati per
 * <b>porta</b> e mai per periferica, scritti da {@code JOYTEST()} su entrambe le porte
 * senza guardare che cosa vi sia collegato.
 *
 * <p>I bit del registro POTINP li compone Paula, che interroga questa porta con
 * {@link #pullsUpPotLines()}, {@link #isRightOrButton2Pressed()} e
 * {@link #isMiddleOrButton3Pressed()}: la porta dice che cosa c'è attaccato, la regola
 * del registro sta tutta in {@code Paula.potinpBit}.
 *
 * Il pulsante fuoco principale (sinistro del mouse / fire del joystick) viene
 * riflesso direttamente sul pin CIA-A Port A mediante {@code CIAPinSet}:
 *   porta 1 → bit 6 (mask 0x40), porta 2 → bit 7 (mask 0x80), active-LOW.
 *
 * Riferimenti: vAmiga ControlPort.cpp, Amiga HW Ref Manual.
 */
public class GamePort implements GamePortControls {

    private final int portNumber;             // 1 = mouse port, 2 = joystick port
    private GamePortDevice device = GamePortDevice.NONE;

    public final AmigaMouse    mouse    = new AmigaMouse();
    public final AmigaJoystick joystick = new AmigaJoystick();

    // Pin CIA-A Port A per il pulsante fuoco (impostato da chi instanzia la porta)
    private CIAPinSet ciaAPins = null;

    // Indice del pin CIA-A per il fuoco di questa porta (bit 6 per porta 1, bit 7 per porta 2)
    private final int ciaFireBit;

    // Contatori di quadratura di Denise per QUESTA porta (8 bit ciascuno, wrapping).
    // X = byte basso di JOYxDAT, Y = byte alto. Vedi il commento di classe: sono del
    // chip, non del dispositivo, e vivono anche con la porta vuota.
    private int counterX = 0;
    private int counterY = 0;

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

    @Override
    public void setDevice(GamePortDevice d) { device = d; }

    // -------------------------------------------------------------------------
    // Eventi mouse
    // -------------------------------------------------------------------------

    /**
     * Muove il cursore del mouse di (dx, dy) pixel. Imposta device = MOUSE.
     *
     * <p>Lo spostamento finisce direttamente nei contatori della porta, come sull'oracolo
     * ({@code inputdevice_read} somma i delta dell'host in {@code mouse_x/mouse_y}).
     */
    public synchronized void moveMouse(int dx, int dy) {
        device = GamePortDevice.MOUSE;
        counterX = (counterX + dx) & 0xFF;
        counterY = (counterY + dy) & 0xFF;
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
    public synchronized void setJoystickAxes(int x, int y) {
        device = GamePortDevice.JOYSTICK;
        joystick.setAxisX(x);
        joystick.setAxisY(y);
        joyMouseCounter();
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
     * Valore da restituire per JOY0DAT (porta 1) o JOY1DAT (porta 2):
     * bit 15:8 = contatore Y, bit 7:0 = contatore X.
     *
     * <p>⛔ <b>Non dipende da che cosa è collegato.</b> Una porta vuota non legge 0: legge
     * i propri contatori, che sono stati scritti dall'ultimo JOYTEST come quelli della
     * porta con il mouse. È esattamente ciò che misura {@code Mainboard/Ports/JOYxDAT/joytest1},
     * dove il programma dipinge JOY0DATR e JOY1DATR uno dopo l'altro dopo aver scritto
     * JOYTEST: l'oracolo produce un gradiente <i>continuo</i> perché i due registri
     * restituiscono lo stesso valore, non un valore e nero.
     */
    public synchronized int joydat() {
        return (counterY << 8) | counterX;
    }

    /**
     * La periferica attaccata <b>tira su</b> le due linee POTX/POTY di questa porta?
     *
     * <p>⛔ Paula non ha pull-up su quei pin: a tirarli su è il dispositivo, e se non lo fa
     * nessuno il condensatore resta scarico e i bit DAT di POTGOR leggono <b>0</b>. Una
     * porta vuota legge quindi 0, non 1.
     *
     * <p>L'oracolo lo decide per periferica (`inputdevice.cpp`): {@code is_mouse_pullup()}
     * per il mouse, {@code is_joystick_pullup()} — vero <b>solo</b> in modo
     * {@code JSEM_MODE_GAMEPAD} — per il joystick. ⚠️ Il nostro {@code JOYSTICK} è il
     * secondo caso e non il primo: modella un pad a più pulsanti (button2/3 arrivano dai
     * joystick veri via GLFW), che è proprio quello che sull'oracolo il pull-up ce l'ha.
     * Un joystick digitale a un pulsante — il {@code kbd1} che l'oracolo mette in porta 1
     * per default — sulle linee POT è indistinguibile da una porta vuota.
     */
    public boolean pullsUpPotLines() {
        return device == GamePortDevice.MOUSE || device == GamePortDevice.JOYSTICK;
    }

    /**
     * Indica se il pulsante destro (o button2) è premuto.
     * Usato da Paula in {@code capCheck}: il pulsante tiene la linea a massa.
     */
    public boolean isRightOrButton2Pressed() {
        return (device == GamePortDevice.MOUSE)    ? mouse.isRightPressed()
             : (device == GamePortDevice.JOYSTICK) ? joystick.isButton2Pressed() : false;
    }

    /**
     * Indica se il pulsante medio (o button3) è premuto.
     * Usato da Paula in {@code capCheck}: il pulsante tiene la linea a massa.
     */
    public boolean isMiddleOrButton3Pressed() {
        return (device == GamePortDevice.MOUSE)    ? mouse.isMiddlePressed()
             : (device == GamePortDevice.JOYSTICK) ? joystick.isButton3Pressed() : false;
    }

    /**
     * Preset dei contatori da JOYTEST ($DFF036).
     *
     * <p>I 6 bit alti di ciascun byte sovrascrivono il contatore, i 2 bassi — lo stato di
     * quadratura istantaneo — restano quelli che sono. Oracolo: {@code JOYTEST()},
     * {@code mouse_x[n] &= 3; mouse_x[n] |= v & 0xFC;}
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
     * Porta di {@code joymousecounter()} (WinUAE {@code inputdevice.cpp}): la direzione del
     * joystick <b>non</b> è un registro a sé, è la coppia di bit di quadratura in fondo ai
     * contatori della porta — gli stessi che muove il mouse.
     *
     * <p>Perciò un joystick fa avanzare i 6 bit alti: la transizione 0→3 del codice a due
     * bit è un giro di quadratura all'indietro ({@code -4}), quella 3→0 uno in avanti
     * ({@code +4}). È ciò che permette al driver dell'OS di calcolare un delta con segno
     * fra due letture consecutive senza sapere che cosa sia collegato.
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
