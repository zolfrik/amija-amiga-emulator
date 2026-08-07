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

import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWGamepadState;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.List;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Servizio di lettura dei joystick/gamepad fisici tramite GLFW (LWJGL).
 *
 * <p>GLFW è già una dipendenza del progetto (usata solo come binding, non per il
 * windowing: il rendering OpenGL passa da {@code AWTGLCanvas}/JAWT). Le API
 * joystick di GLFW non richiedono una finestra: interrogano direttamente il
 * sottosistema di input del SO. Vanno però chiamate tutte dallo stesso thread che
 * ha eseguito {@code glfwInit}: per questo il servizio possiede un thread daemon
 * dedicato ("glfw-joystick") che inizializza GLFW e fa polling a ~125 Hz.
 *
 * <p>Il thread viene avviato pigramente al primo {@link #setPorts(List)} con
 * almeno una porta (cioè solo se l'utente seleziona "Joystick (reale)"): finché
 * nessuna porta usa un joystick reale, GLFW non viene mai inizializzato e non c'è
 * alcun costo.
 *
 * <p>I joystick presenti vengono assegnati in ordine alle porte richiedenti: il
 * primo controller collegato pilota la prima porta, il secondo la seconda, ecc.
 * Sono supportati sia i gamepad mappati ({@code glfwGetGamepadState}, layout
 * uniforme XInput-like) sia i joystick generici (assi/hat/pulsanti grezzi).
 *
 * <p><b>Threading verso l'emulazione:</b> il servizio scrive sulle porte solo alla
 * <i>variazione</i> di stato (assi/pulsanti), esattamente come fanno gli adapter AWT
 * dall'EDT. Le {@code GamePort} sono già scritte da thread host (EDT) e lette dal
 * thread di emulazione; questo servizio segue lo stesso modello.
 *
 * <p>Le porte sono {@link GamePortControls} e non {@link GamePort}: nel gioco a distanza
 * l'ospite non ha nessuna macchina, quindi nessuna {@code GamePort} da pilotare, e il suo
 * joystick fisico deve poter finire in un pacchetto come ci finiscono mouse e tastiera.
 * Di quel che c'è dietro l'interfaccia questo servizio non usa niente.
 */
public final class GlfwJoystickService {

    /** Soglia oltre la quale un asse analogico è considerato "premuto" (direzione digitale). */
    private static final float AXIS_THRESHOLD = 0.5f;
    /** Periodo di polling (~125 Hz): reattivo senza pesare sulla CPU. */
    private static final long POLL_INTERVAL_MS = 8;

    private static GlfwJoystickService instance;

    /** Istanza singleton (una sola inizializzazione GLFW per processo). */
    public static synchronized GlfwJoystickService getInstance() {
        if (instance == null) instance = new GlfwJoystickService();
        return instance;
    }

    // Porte pilotate dal joystick reale, in ordine, con lo stato precedente parallelo.
    private final Object lock = new Object();
    private GamePortControls[] ports = new GamePortControls[0];
    private Prev[] prev = new Prev[0];

    private volatile boolean running = false;
    private Thread thread;
    private GLFWErrorCallback errorCallback;
    private GLFWGamepadState gamepadState;

    private GlfwJoystickService() { }

    /** Stato precedente di una porta, per inviare alla {@link GamePort} solo le variazioni. */
    private static final class Prev {
        int x = 0, y = 0;
        boolean fire, b2, b3;
        boolean valid = false; // false => forza il primo invio (anche neutro)
    }

    /**
     * Imposta le porte da pilotare col joystick reale (in ordine di preferenza).
     * Lista vuota = nessuna porta usa un joystick reale (il thread, se avviato, resta
     * inattivo). La prima chiamata con lista non vuota avvia il thread GLFW.
     *
     * <p>Le porte che escono dall'elenco vengono <b>rilasciate</b>. Senza, cambiare la
     * periferica di una porta col fuoco premuto lascerebbe il fuoco premuto per sempre:
     * quella porta non la guarda più nessuno, quindi non arriva più niente a contraddirlo.
     *
     * <p>Lo scambio e il rilascio stanno <b>dentro</b> il lock, che {@link #poll()} tiene per
     * tutta la propria passata. Rilasciare fuori non basterebbe: una passata entrata un
     * istante prima dello scambio sta per scrivere sulla porta vecchia, e se quella scrittura
     * atterrasse <i>dopo</i> il rilascio rimetterebbe premuto il fuoco che si è appena
     * mollato — cioè esattamente il guasto che questo rilascio esiste per evitare.
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

    /** Ferma il thread e libera GLFW. Da chiamare allo shutdown della JVM. */
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
                glfwPollEvents(); // gestisce il collegamento/scollegamento a caldo
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
     * Una passata di lettura, <b>tutta sotto il lock</b>.
     *
     * <p>Il lock è preso da {@link #setPorts} e da qui, e basta: la contesa è fra un thread
     * che passa ogni 8 ms e una riconfigurazione che capita quando l'utente conferma il
     * popup, quindi non c'è niente da ottimizzare. In cambio una porta che esce dall'elenco
     * non può ricevere una scrittura in ritardo <i>dopo</i> essere stata rilasciata, che è
     * l'unico modo in cui il fuoco resterebbe premuto per sempre su una porta che nessuno
     * guarda più.
     */
    private void poll() {
        synchronized (lock) {
            GamePortControls[] snap = ports;
            Prev[] prevSnap = prev;
            if (snap.length == 0) return;

            // Assegna i joystick presenti alle porte, nell'ordine dei loro indici GLFW.
            int jid = GLFW_JOYSTICK_1;
            for (int i = 0; i < snap.length; i++) {
                GamePortControls port = snap[i];
                Prev p = prevSnap[i];
                while (jid <= GLFW_JOYSTICK_LAST && !glfwJoystickPresent(jid)) jid++;
                if (jid > GLFW_JOYSTICK_LAST) {
                    // Nessun controller per questa porta: centra e rilascia i pulsanti.
                    apply(port, p, 0, 0, false, false, false);
                    continue;
                }
                readAndApply(jid, port, p);
                jid++;
            }
        }
    }

    /** Legge lo stato del joystick {@code jid} e lo applica alla porta. */
    private void readAndApply(int jid, GamePortControls port, Prev p) {
        int x = 0, y = 0;
        boolean fire = false, b2 = false, b3 = false;

        if (glfwJoystickIsGamepad(jid) && glfwGetGamepadState(jid, gamepadState)) {
            // Gamepad con mapping noto: layout uniforme (stick sinistro + D-pad).
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
            // Joystick generico: assi/hat/pulsanti grezzi.
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

    /** Invia alla porta solo i valori cambiati rispetto allo stato precedente. */
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
