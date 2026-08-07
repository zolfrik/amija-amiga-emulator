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

import io.github.zolfrik.amija.net.FeedbackPacket;

/**
 * Lato ospite dell'input: raccoglie i gesti locali in uno <b>stato completo</b> da spedire
 * 200 volte al secondo ({@code docs/remote-play-plan.md} §4.3).
 *
 * <p>Implementa le stesse due interfacce che pilotano una macchina vera, quindi gli adapter
 * di input dell'emulatore — quelli con dentro la traduzione dei tasti, la cattura del
 * puntatore, la mappa del joystick da tastiera — ci si attaccano <b>senza saperlo</b>. Sul
 * lato ospite non cambia niente di come si gioca: cambia solo dove finiscono i gesti.
 *
 * <h2>Mai eventi: solo stato</h2>
 * Ogni pacchetto porta la verita' completa, cosi' un pacchetto perso non ha conseguenze e
 * nessun rilascio puo' restare appeso. Due cose pero' non sono stato per natura, e vanno
 * riportate a stato:
 *
 * <ul>
 *   <li><b>il movimento del mouse e' un delta.</b> Se si spedisse {@code dx,dy} e il
 *       pacchetto si perdesse, quel movimento sarebbe perso per sempre. Qui si accumulano
 *       due <b>contatori a 32 bit che non si azzerano mai</b>: l'host applica la differenza,
 *       e il pacchetto successivo consegna da solo anche il movimento mancante. Combacia col
 *       modello hardware, dove {@code AmigaMouse} e' gia' una coppia di contatori di
 *       quadratura che avvolgono;</li>
 *   <li><b>un tasto premuto e rilasciato fra due campionamenti sparirebbe.</b> A 200 Hz la
 *       finestra cieca e' 5 ms — corta, ma non zero. Il rimedio e' il <b>latch appiccicoso</b>:
 *       il bit di un tasto premuto resta alzato per {@value #LATCH_SNAPSHOTS} campionamenti
 *       (15 ms), quindi un tocco sopravvive anche a due pacchetti persi di fila. E il
 *       pacchetto <b>resta uno stato puro</b>: nessun registro di eventi, nessun contatore da
 *       riconciliare. Il rilascio arriva 15 ms dopo, che e' sotto la soglia di percezione e
 *       sotto la durata di qualunque pressione umana.</li>
 * </ul>
 *
 * <h2>Anche cosa c'e' collegato e' stato</h2>
 * La porta che l'host assegna all'ospite la riempie <b>l'ospite</b>: mouse, joystick reale,
 * joystick da tastiera con i <i>suoi</i> tasti, o niente. Quella scelta e' una proprieta'
 * della porta come le altre, quindi viaggia nel pacchetto ({@link #setDevice}) invece che in
 * un messaggio a parte — e come le altre si autoripara, perche' ogni pacchetto la ripete.
 *
 * <p>Come {@code GamePort}, la periferica si deduce <b>anche dai gesti</b>: chi muove il
 * mouse ha un mouse. Le due strade non litigano, perche' i gesti arrivano solo dagli adapter
 * che la scelta stessa ha cablato; dedurla serve al caso in cui gesti non ne arrivano
 * affatto, che e' poi il solo modo di distinguere una porta vuota da una ferma.
 *
 * <h2>Nessun filtro di sicurezza qui</h2>
 * Il divieto di resettare la macchina dell'host (CTRL+Amiga+Amiga) <b>non</b> sta in questa
 * classe, e non e' una dimenticanza: e' la macchina dell'host, quindi la decisione e'
 * dell'host. Un filtro messo qui sarebbe aggirabile da chiunque scriva il proprio client —
 * e il pacchetto e' 44 byte, scriverne uno e' un pomeriggio. Vedi {@link RemoteInputApplier}.
 *
 * <p>Thread: i gesti arrivano dall'EDT, {@link #snapshot} e' chiamato dal thread di feedback
 * a 200 Hz. Tutto sincronizzato — sono decine di nanosecondi su strutture minuscole, e
 * l'alternativa (campi volatile sparsi) darebbe uno stato che non e' mai stato vero tutto
 * insieme in nessun istante.
 */
public final class RemoteInputSender implements GamePortControls, AmigaKeyTarget {

    /** Per quanti campionamenti resta alzato il bit di un tasto appena premuto. */
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

    /** Vero finche' l'utente non ha toccato niente: serve a non spedire rumore. */
    private boolean idle = true;

    /**
     * Riversa lo stato corrente nel pacchetto e fa scorrere il latch di un passo.
     *
     * <p>Ha un effetto collaterale voluto — il latch scorre — quindi va chiamato una volta
     * per pacchetto e da un thread solo. Se lo si chiamasse due volte per pacchetto, un
     * tocco durerebbe la meta'.
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

    /** Vero se non e' ancora arrivato nessun gesto: l'host non ha niente da applicare. */
    public synchronized boolean isIdle() {
        return idle;
    }

    /**
     * Dimentica tutti i <b>gesti</b>: nuova sessione, niente ereditato dalla precedente.
     *
     * <p>La periferica collegata <b>non</b> si azzera, ed e' la sola cosa che sopravvive:
     * non e' un gesto, e' la scelta che l'utente ha fatto nel proprio popup F12. Azzerarla
     * qui vorrebbe dire che riconnettersi svuota la porta di chi si riconnette.
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
    // Tastiera
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
        // Il latch NON si tocca: e' proprio il rilascio immediato che deve poter aspettare.
        // Se la pressione e' durata piu' di 15 ms il latch e' gia' sceso a zero e il
        // rilascio parte col pacchetto successivo, come ci si aspetta.
        held[rawKeyNum & 0x7F] = false;
        idle = false;
    }

    // =====================================================================
    // Game port
    // =====================================================================

    /**
     * Dichiara cosa l'utente ha collegato alla porta che l'host gli ha assegnato.
     *
     * <p>Lo chiama {@code Amija} quando cabla gli adapter, e di nuovo a ogni conferma del
     * popup F12: da li' in poi il campo parte 200 volte al secondo, e l'host lo posa sulla
     * game port vera. Non c'e' nessun momento in cui la scelta e' fatta ma non e' ancora
     * arrivata piu' a lungo di un pacchetto.
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

    /** Il codice che il pacchetto usa per una periferica. */
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
