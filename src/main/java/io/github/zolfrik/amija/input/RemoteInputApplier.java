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
import io.github.zolfrik.amija.net.GuestInputTarget;

/**
 * Lato host dell'input: applica alla macchina lo stato mandato dall'ospite
 * ({@code docs/remote-play-plan.md} §4.3).
 *
 * <h2>Un punto solo, una volta per frame, sul thread giusto</h2>
 * Questa classe <b>non</b> viene chiamata dal thread di rete. Il thread di rete si limita a
 * depositare l'ultimo stato autenticato; e' il <b>thread di emulazione</b> a raccoglierlo, al
 * confine di frame, sempre nello stesso punto. Due ragioni, ed entrambe contano:
 * si evita di introdurre scritture concorrenti sullo stato di macchina, e si ottiene un
 * punto di quantizzazione riproducibile — che e' poi come si comporta anche l'input locale,
 * che l'EDT consegna comunque a raffiche.
 *
 * <h2>Applicare uno stato non e' riprodurre eventi</h2>
 * Quel che arriva e' una fotografia, non un racconto. La si applica per differenza:
 * <ul>
 *   <li><b>tasti</b>: {@code bitmap ^ ultimaBitmap}, e sui bit cambiati press o release.
 *       E' cio' che rende impossibile un tasto appeso — se un pacchetto si perde, il
 *       successivo porta comunque la verita' e la differenza la recupera;</li>
 *   <li><b>mouse</b>: la <b>differenza signed</b> dei contatori cumulativi, che gestisce da
 *       sola l'avvolgimento a 32 bit e recupera il movimento dei pacchetti persi;</li>
 *   <li><b>joystick e pulsanti</b>: stato puro, si scrive e basta;</li>
 *   <li><b>la periferica collegata</b>: idem, ed e' l'ospite a dirla. La porta gliel'ha
 *       assegnata l'host, ma cosa infilarci — mouse, joystick, niente — lo sceglie lui nel
 *       proprio popup, anche a partita in corso. Si posa <b>per ultima</b>, dopo i gesti, e
 *       non e' un dettaglio: i gesti sono una <i>differenza</i> rispetto al pacchetto
 *       precedente, quindi possono riguardare la periferica di prima, e {@code GamePort}
 *       deduce la propria da cio' che le si fa. Chi molla il fuoco e nello stesso istante
 *       svuota la porta si ritroverebbe un joystick fermo invece di una porta vuota.</li>
 * </ul>
 *
 * <h2>Qui sta il confine di fiducia</h2>
 * L'ospite puo' mandare qualunque bitmap: il pacchetto e' 44 byte e chiunque puo' scriverne
 * uno. Percio' i divieti stanno <b>qui</b> e non sull'ospite:
 * <ul>
 *   <li><b>CTRL+Amiga+Amiga non resetta la macchina di qualcun altro</b>, salvo che l'host
 *       lo consenta esplicitamente. Il filtro non nega i tre tasti — servono, singolarmente,
 *       in mezzo mondo di programmi — ma <b>nega la terna</b>: quando tutti e tre
 *       risulterebbero premuti insieme, l'Amiga destra non viene consegnata. Vietare i tasti
 *       sarebbe mutilare la tastiera per prevenire una combinazione;</li>
 *   <li><b>la tastiera si condivide solo se l'host lo ha concesso</b>: con
 *       {@code keyboardShared} spento, dell'ospite arriva solo la game port.</li>
 * </ul>
 * Le hotkey locali dell'host (F12 in primis, che aprirebbe un dialog modale sulla macchina
 * di qualcun altro) non passano nemmeno da qui: sono intercettate da {@code Amija} prima
 * degli adapter di macchina, e l'ospite scrive solo su questi ultimi.
 */
public final class RemoteInputApplier implements GuestInputTarget {

    private static final int KEY_COUNT = 128;

    /** I tre tasti della terna di reset (vedi {@code Keyboard.KEY_*}). */
    private static final int KEY_CTRL = 0x63;
    private static final int KEY_L_AMIGA = 0x66;
    private static final int KEY_R_AMIGA = 0x67;

    private final GamePortControls port;
    private final AmigaKeyTarget keyboard;
    private final boolean keyboardShared;
    private final boolean allowReset;

    private final byte[] lastKeys = new byte[FeedbackPacket.KEY_BYTES];
    private int lastMouseX;
    private int lastMouseY;
    private int lastButtons;
    private int lastJoyX;
    private int lastJoyY;
    private int lastSessionGen;
    private boolean started;

    private long applied;
    private long resetCombosBlocked;

    /**
     * @param port           la game port assegnata all'ospite (default: la 1, quella joystick)
     * @param keyboard       la tastiera Amiga, o {@code null} per non condividerla
     * @param allowReset     se l'ospite puo' resettare la macchina con CTRL+Amiga+Amiga
     */
    public RemoteInputApplier(GamePortControls port, AmigaKeyTarget keyboard,
                              boolean allowReset) {
        this.port = port;
        this.keyboard = keyboard;
        this.keyboardShared = keyboard != null;
        this.allowReset = allowReset;
    }

    /**
     * Applica lo stato. <b>Chiamato dal thread di emulazione, una volta per frame.</b>
     */
    @Override
    public void apply(FeedbackPacket state) {
        if (!started || state.sessionGen != lastSessionGen) {
            // Nuova sessione (o prima applicazione): i contatori del mouse ripartono da
            // un'altra origine, e prenderne la differenza vorrebbe dire teletrasportare il
            // puntatore. Si riparte dai valori correnti, cioe' da nessun movimento.
            resetBaseline(state);
        }

        int dx = state.mouseCounterX - lastMouseX;   // signed: gestisce l'avvolgimento
        int dy = state.mouseCounterY - lastMouseY;
        if (dx != 0 || dy != 0) {
            port.moveMouse(dx, dy);
            lastMouseX = state.mouseCounterX;
            lastMouseY = state.mouseCounterY;
        }

        if (state.joyX != lastJoyX || state.joyY != lastJoyY) {
            port.setJoystickAxes(state.joyX, state.joyY);
            lastJoyX = state.joyX;
            lastJoyY = state.joyY;
        }

        int changed = state.buttons ^ lastButtons;
        if (changed != 0) {
            if ((changed & FeedbackPacket.BUTTON_FIRE) != 0) {
                port.pressJoystickFire((state.buttons & FeedbackPacket.BUTTON_FIRE) != 0);
            }
            if ((changed & FeedbackPacket.BUTTON_2) != 0) {
                port.pressJoystickButton2((state.buttons & FeedbackPacket.BUTTON_2) != 0);
            }
            if ((changed & FeedbackPacket.BUTTON_3) != 0) {
                port.pressJoystickButton3((state.buttons & FeedbackPacket.BUTTON_3) != 0);
            }
            if ((changed & FeedbackPacket.BUTTON_MOUSE_LEFT) != 0) {
                port.pressMouseLeft((state.buttons & FeedbackPacket.BUTTON_MOUSE_LEFT) != 0);
            }
            if ((changed & FeedbackPacket.BUTTON_MOUSE_RIGHT) != 0) {
                port.pressMouseRight((state.buttons & FeedbackPacket.BUTTON_MOUSE_RIGHT) != 0);
            }
            if ((changed & FeedbackPacket.BUTTON_MOUSE_MIDDLE) != 0) {
                port.pressMouseMiddle((state.buttons & FeedbackPacket.BUTTON_MOUSE_MIDDLE) != 0);
            }
            lastButtons = state.buttons;
        }

        // Cosa l'ospite ha collegato, e per ULTIMA: i rilasci appena applicati sono la
        // differenza rispetto al pacchetto precedente, quindi possono riguardare la
        // periferica di PRIMA — e GamePort deduce la propria da cio' che le si fa. Lasciare
        // che a parlare per ultimo sia il pacchetto significa che chi molla il fuoco e nello
        // stesso istante svuota la porta ottiene una porta vuota, non un joystick fermo.
        port.setDevice(hardwareDevice(state.portDevice));

        if (keyboardShared) {
            applyKeys(state);
        }
        applied++;
    }

    /**
     * Riporta la macchina a riposo: tutto rilasciato, e la porta <b>vuota</b>.
     *
     * <p>Va chiamato quando l'ospite se ne va, altrimenti l'ultimo tasto che aveva premuto
     * resta premuto per sempre — non perche' il protocollo abbia perso qualcosa, ma perche'
     * non arriva piu' nessuno stato che lo contraddica.
     *
     * <p>Anche la periferica se ne va con lui: chi la teneva collegata non c'e' piu', e una
     * porta che continuasse a dichiarare un joystick fermo sarebbe una bugia che i giochi
     * leggono. La porta resta comunque <b>riservata</b> — l'host non se la riprende finche'
     * ospita, perche' un altro ospite puo' arrivare.
     */
    @Override
    public void releaseAll() {
        port.setJoystickAxes(0, 0);
        port.pressJoystickFire(false);
        port.pressJoystickButton2(false);
        port.pressJoystickButton3(false);
        port.pressMouseLeft(false);
        port.pressMouseRight(false);
        port.pressMouseMiddle(false);
        // Dopo i rilasci, e non prima: ognuno di essi fa dedurre a GamePort una periferica.
        port.setDevice(GamePortDevice.NONE);
        if (keyboardShared) {
            for (int k = 0; k < KEY_COUNT; k++) {
                if ((lastKeys[k >> 3] & (1 << (k & 7))) != 0) {
                    keyboard.releaseKey(k);
                }
            }
        }
        java.util.Arrays.fill(lastKeys, (byte) 0);
        lastButtons = 0;
        lastJoyX = 0;
        lastJoyY = 0;
        started = false;
    }

    /** Quante volte lo stato dell'ospite e' stato applicato. */
    public long applied() {
        return applied;
    }

    /** Quante volte la terna di reset e' stata negata: diagnostica, non un errore. */
    public long resetCombosBlocked() {
        return resetCombosBlocked;
    }

    // =====================================================================

    private void applyKeys(FeedbackPacket state) {
        boolean blockReset = !allowReset && isResetCombo(state.keys);
        if (blockReset) {
            resetCombosBlocked++;
        }
        for (int b = 0; b < FeedbackPacket.KEY_BYTES; b++) {
            int now = state.keys[b] & 0xFF;
            if (blockReset && b == (KEY_R_AMIGA >> 3)) {
                now &= ~(1 << (KEY_R_AMIGA & 7));
            }
            int diff = now ^ (lastKeys[b] & 0xFF);
            if (diff == 0) {
                continue;
            }
            for (int bit = 0; bit < 8; bit++) {
                if ((diff & (1 << bit)) == 0) {
                    continue;
                }
                int raw = (b << 3) | bit;
                if ((now & (1 << bit)) != 0) {
                    keyboard.pressKey(raw);
                } else {
                    keyboard.releaseKey(raw);
                }
            }
            lastKeys[b] = (byte) now;
        }
    }

    private static boolean isResetCombo(byte[] keys) {
        return isSet(keys, KEY_CTRL) && isSet(keys, KEY_L_AMIGA) && isSet(keys, KEY_R_AMIGA);
    }

    private static boolean isSet(byte[] keys, int raw) {
        return (keys[raw >> 3] & (1 << (raw & 7))) != 0;
    }

    /**
     * La periferica corrispondente al codice del pacchetto.
     *
     * <p>Un codice fuori catalogo non arriva fin qui — {@code FeedbackPacket.readFrom} lo
     * rifiuta — ma questo resta il confine di fiducia, e un {@code default} che sceglie la
     * porta vuota e' l'unica risposta che non inventa una periferica per conto di nessuno.
     */
    private static GamePortDevice hardwareDevice(int wire) {
        return switch (wire) {
            case FeedbackPacket.DEVICE_MOUSE -> GamePortDevice.MOUSE;
            case FeedbackPacket.DEVICE_JOYSTICK -> GamePortDevice.JOYSTICK;
            default -> GamePortDevice.NONE;
        };
    }

    private void resetBaseline(FeedbackPacket state) {
        lastSessionGen = state.sessionGen;
        lastMouseX = state.mouseCounterX;
        lastMouseY = state.mouseCounterY;
        releaseAll();
        started = true;
    }
}
