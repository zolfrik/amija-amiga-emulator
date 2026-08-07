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

package io.github.zolfrik.amija.custom;

import io.github.zolfrik.amija.common.WindowsKeyState;
import io.github.zolfrik.amija.input.AmigaKeyTarget;

import java.awt.Window;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.HashMap;
import java.util.Map;
import javax.swing.Timer;

/**
 * Traduce i KeyEvent AWT nei raw key number Amiga e li passa a
 * Keyboard.pressKey() / releaseKey().
 *
 * <p><b>Gestione shift.</b> Windows non consegna ad AWT il {@code keyReleased}
 * del primo shift rilasciato quando entrambi sono premuti (lo emette solo per
 * l'ultimo): la levetta sinistra del flipper resterebbe incantata. Quando è
 * disponibile la lettura dello stato fisico dei tasti ({@link WindowsKeyState}),
 * i due shift NON sono più gestiti dagli eventi AWT ma da un poller che legge
 * direttamente L/R shift e li pilota in modo indipendente. Se la lettura fisica
 * non è disponibile (non-Windows, o senza finestra), si ricade sulla gestione
 * AWT con {@link #reconcileModifiers} come mitigazione.
 *
 * <p><b>Gestione AltGr.</b> Sui layout con AltGr Windows affianca all'Alt destro
 * un Control sinistro sintetico: all'Amiga andrebbe CTRL + Amiga destra, e con
 * l'Alt sinistro premuto la combinazione diventerebbe un reset. Il Ctrl sintetico
 * viene riconosciuto e ignorato — vedi {@link #isSyntheticAltGrCtrl}.
 */
public class AmigaKeyAdapter extends KeyAdapter {

    private final AmigaKeyTarget keyboard;

    /** Finestra su cui l'adapter è registrato, per il gating sul focus del poller. */
    private final Window focusWindow;

    /** true se i due shift sono pilotati dal poller fisico invece che da AWT. */
    private final boolean shiftPollerActive;

    /** Timer EDT che campiona lo stato fisico dei due shift (~120 Hz). */
    private Timer shiftPoller;

    /** Ultimo stato sintetizzato per L/R shift (per emettere solo le transizioni). */
    private boolean lShiftDown = false;
    private boolean rShiftDown = false;

    /** true se il Ctrl sinistro corrente è quello sintetico di AltGr (press ignorata). */
    private boolean altGrCtrlSuppressed = false;

    // Mappa VK_ Java → raw key number Amiga (Appendice A HW Ref Manual)
    private static final Map<Integer, Integer> KEYMAP = new HashMap<>();
    private static final Map<Character, Integer> CHAR_KEYMAP = new HashMap<>();
    private static final Map<Character, Integer> FALLBACK_KEYMAP = new HashMap<>();
    private final Map<Integer, Integer> pressedKeys = new HashMap<>();

    static {
        // Lettere
        KEYMAP.put(KeyEvent.VK_A, 0x20);
        KEYMAP.put(KeyEvent.VK_B, 0x35);
        KEYMAP.put(KeyEvent.VK_C, 0x33);
        KEYMAP.put(KeyEvent.VK_D, 0x22);
        KEYMAP.put(KeyEvent.VK_E, 0x12);
        KEYMAP.put(KeyEvent.VK_F, 0x23);
        KEYMAP.put(KeyEvent.VK_G, 0x24);
        KEYMAP.put(KeyEvent.VK_H, 0x25);
        KEYMAP.put(KeyEvent.VK_I, 0x17);
        KEYMAP.put(KeyEvent.VK_J, 0x26);
        KEYMAP.put(KeyEvent.VK_K, 0x27);
        KEYMAP.put(KeyEvent.VK_L, 0x28);
        KEYMAP.put(KeyEvent.VK_M, 0x37);
        KEYMAP.put(KeyEvent.VK_N, 0x36);
        KEYMAP.put(KeyEvent.VK_O, 0x18);
        KEYMAP.put(KeyEvent.VK_P, 0x19);
        KEYMAP.put(KeyEvent.VK_Q, 0x10);
        KEYMAP.put(KeyEvent.VK_R, 0x13);
        KEYMAP.put(KeyEvent.VK_S, 0x21);
        KEYMAP.put(KeyEvent.VK_T, 0x14);
        KEYMAP.put(KeyEvent.VK_U, 0x16);
        KEYMAP.put(KeyEvent.VK_V, 0x34);
        KEYMAP.put(KeyEvent.VK_W, 0x11);
        KEYMAP.put(KeyEvent.VK_X, 0x32);
        KEYMAP.put(KeyEvent.VK_Y, 0x15);
        KEYMAP.put(KeyEvent.VK_Z, 0x31);

        // Numeri riga superiore
        KEYMAP.put(KeyEvent.VK_1, 0x01);
        KEYMAP.put(KeyEvent.VK_2, 0x02);
        KEYMAP.put(KeyEvent.VK_3, 0x03);
        KEYMAP.put(KeyEvent.VK_4, 0x04);
        KEYMAP.put(KeyEvent.VK_5, 0x05);
        KEYMAP.put(KeyEvent.VK_6, 0x06);
        KEYMAP.put(KeyEvent.VK_7, 0x07);
        KEYMAP.put(KeyEvent.VK_8, 0x08);
        KEYMAP.put(KeyEvent.VK_9, 0x09);
        KEYMAP.put(KeyEvent.VK_0, 0x0A);
        KEYMAP.put(KeyEvent.VK_BACK_QUOTE, 0x00);
        KEYMAP.put(KeyEvent.VK_MINUS, 0x0B);
        KEYMAP.put(KeyEvent.VK_EQUALS, 0x0C);
        KEYMAP.put(KeyEvent.VK_BACK_SLASH, 0x0D);
        KEYMAP.put(KeyEvent.VK_OPEN_BRACKET, 0x1A);
        KEYMAP.put(KeyEvent.VK_CLOSE_BRACKET, 0x1B);
        KEYMAP.put(KeyEvent.VK_SEMICOLON, 0x29);
        KEYMAP.put(KeyEvent.VK_QUOTE, 0x2A);
        KEYMAP.put(KeyEvent.VK_COMMA, 0x38);
        KEYMAP.put(KeyEvent.VK_PERIOD, 0x39);
        KEYMAP.put(KeyEvent.VK_SLASH, 0x3A);

        // Tasti speciali — usa le costanti già definite in Keyboard
        KEYMAP.put(KeyEvent.VK_ESCAPE, Keyboard.KEY_ESC);
        KEYMAP.put(KeyEvent.VK_BACK_SPACE, Keyboard.KEY_BACKSPACE);
        KEYMAP.put(KeyEvent.VK_TAB, Keyboard.KEY_TAB);
        KEYMAP.put(KeyEvent.VK_ENTER, Keyboard.KEY_RETURN);
        KEYMAP.put(KeyEvent.VK_SPACE, Keyboard.KEY_SPACE);
        KEYMAP.put(KeyEvent.VK_DELETE, Keyboard.KEY_DEL);
        KEYMAP.put(KeyEvent.VK_CAPS_LOCK, Keyboard.KEY_CAPS_LOCK);
        // NB: HELP non sta su F12 — quello apre il popup di configurazione (Amija lo
        // consuma prima degli adapter di macchina). Vedi il blocco navigazione più sotto.

        // Frecce
        KEYMAP.put(KeyEvent.VK_UP, Keyboard.KEY_UP);
        KEYMAP.put(KeyEvent.VK_DOWN, Keyboard.KEY_DOWN);
        KEYMAP.put(KeyEvent.VK_LEFT, Keyboard.KEY_LEFT);
        KEYMAP.put(KeyEvent.VK_RIGHT, Keyboard.KEY_RIGHT);

        // Tasti funzione
        KEYMAP.put(KeyEvent.VK_F1, 0x50);
        KEYMAP.put(KeyEvent.VK_F2, 0x51);
        KEYMAP.put(KeyEvent.VK_F3, 0x52);
        KEYMAP.put(KeyEvent.VK_F4, 0x53);
        KEYMAP.put(KeyEvent.VK_F5, 0x54);
        KEYMAP.put(KeyEvent.VK_F6, 0x55);
        KEYMAP.put(KeyEvent.VK_F7, 0x56);
        KEYMAP.put(KeyEvent.VK_F8, 0x57);
        KEYMAP.put(KeyEvent.VK_F9, 0x58);
        KEYMAP.put(KeyEvent.VK_F10, 0x59);

        // Numpad
        KEYMAP.put(KeyEvent.VK_NUMPAD0, 0x0F);
        KEYMAP.put(KeyEvent.VK_NUMPAD1, 0x1D);
        KEYMAP.put(KeyEvent.VK_NUMPAD2, 0x1E);
        KEYMAP.put(KeyEvent.VK_NUMPAD3, 0x1F);
        KEYMAP.put(KeyEvent.VK_NUMPAD4, 0x2D);
        KEYMAP.put(KeyEvent.VK_NUMPAD5, 0x2E);
        KEYMAP.put(KeyEvent.VK_NUMPAD6, 0x2F);
        KEYMAP.put(KeyEvent.VK_NUMPAD7, 0x3D);
        KEYMAP.put(KeyEvent.VK_NUMPAD8, 0x3E);
        KEYMAP.put(KeyEvent.VK_NUMPAD9, 0x3F);
        KEYMAP.put(KeyEvent.VK_DECIMAL, 0x3C);
        KEYMAP.put(KeyEvent.VK_ADD, 0x5E);
        KEYMAP.put(KeyEvent.VK_SUBTRACT, 0x4A);
        KEYMAP.put(KeyEvent.VK_MULTIPLY, 0x5D);
        KEYMAP.put(KeyEvent.VK_DIVIDE, 0x5C);

        // Simboli stampabili: keyChar segue il layout reale della tastiera host.
        CHAR_KEYMAP.put('`', 0x00);
        CHAR_KEYMAP.put('1', 0x01);
        CHAR_KEYMAP.put('2', 0x02);
        CHAR_KEYMAP.put('3', 0x03);
        CHAR_KEYMAP.put('4', 0x04);
        CHAR_KEYMAP.put('5', 0x05);
        CHAR_KEYMAP.put('6', 0x06);
        CHAR_KEYMAP.put('7', 0x07);
        CHAR_KEYMAP.put('8', 0x08);
        CHAR_KEYMAP.put('9', 0x09);
        CHAR_KEYMAP.put('(', 0x5A);
        CHAR_KEYMAP.put('0', 0x0A);
        CHAR_KEYMAP.put(')', 0x5B);
        CHAR_KEYMAP.put('-', 0x0B);
        CHAR_KEYMAP.put('_', 0x0B);
        CHAR_KEYMAP.put('=', 0x0C);
        CHAR_KEYMAP.put('+', 0x0C);
        CHAR_KEYMAP.put('\\', 0x0D);
        CHAR_KEYMAP.put('|', 0x0D);
        CHAR_KEYMAP.put('[', 0x1A);
        CHAR_KEYMAP.put('{', 0x1A);
        CHAR_KEYMAP.put(']', 0x1B);
        CHAR_KEYMAP.put('}', 0x1B);
        CHAR_KEYMAP.put(';', 0x29);
        CHAR_KEYMAP.put(':', 0x29);
        CHAR_KEYMAP.put('\'', 0x2A);
        CHAR_KEYMAP.put('"', 0x2A);
        CHAR_KEYMAP.put('#', 0x2B);
        CHAR_KEYMAP.put('<', 0x30);
        CHAR_KEYMAP.put('>', 0x30);
        CHAR_KEYMAP.put(',', 0x38);
        CHAR_KEYMAP.put('.', 0x39);
        CHAR_KEYMAP.put('/', 0x3A);
        CHAR_KEYMAP.put('?', 0x3A);

        // Tasti che AWT non sa nominare (keyCode = VK_UNDEFINED) e il cui carattere non
        // esiste sul layout US: senza questa rete resterebbero MUTI. Sono le cinque
        // lettere accentate della tastiera italiana, mappate per POSIZIONE sul raw key
        // number che occupa lo stesso posto sulla tastiera Amiga — quindi il tasto manda
        // sempre lo stesso codice sia nudo sia con Shift, ed è quello giusto se sull'Amiga
        // è caricato il keymap italiano (setmap i). Con il keymap US di default escono
        // invece i simboli che quelle posizioni hanno lì (= [ ; ' #), altrimenti
        // irraggiungibili da una tastiera italiana.
        //
        // Questa mappa è consultata per ULTIMA, quindi non può dirottare i layout in cui
        // gli stessi caratteri arrivano da un tasto con keyCode noto (es. la é dell'AZERTY
        // francese, che sta sulla riga dei numeri ed è già gestita da KEYMAP).
        FALLBACK_KEYMAP.put('ì', 0x0C);   // posizione US '='
        FALLBACK_KEYMAP.put('^', 0x0C);   // Shift+ì
        FALLBACK_KEYMAP.put('è', 0x1A);   // posizione US '['
        FALLBACK_KEYMAP.put('é', 0x1A);   // Shift+è
        FALLBACK_KEYMAP.put('ò', 0x29);   // posizione US ';'
        FALLBACK_KEYMAP.put('ç', 0x29);   // Shift+ò
        FALLBACK_KEYMAP.put('à', 0x2A);   // posizione US '\''
        FALLBACK_KEYMAP.put('°', 0x2A);   // Shift+à
        FALLBACK_KEYMAP.put('ù', 0x2B);   // posizione US '#'
        FALLBACK_KEYMAP.put('§', 0x2B);   // Shift+ù
    }

    public AmigaKeyAdapter(AmigaKeyTarget keyboard) {
        this(keyboard, null);
    }

    /**
     * @param keyboard    tastiera Amiga di destinazione
     * @param focusWindow finestra su cui l'adapter è registrato; se non null e la
     *                    lettura fisica dei tasti è disponibile, i due shift
     *                    vengono pilotati da un poller invece che dagli eventi AWT
     *                    (aggira il keyup di shift perso da Windows). Passare null
     *                    (es. nei test headless) mantiene la sola gestione AWT.
     */
    public AmigaKeyAdapter(AmigaKeyTarget keyboard, Window focusWindow) {
        this.keyboard = keyboard;
        this.focusWindow = focusWindow;
        this.shiftPollerActive = focusWindow != null && WindowsKeyState.isAvailable();
        if (shiftPollerActive) {
            // ~120 Hz: ben oltre la cadenza umana, sotto ogni impatto sensibile.
            shiftPoller = new Timer(8, e -> { pollShiftState(); pollResetComboState(); });
            shiftPoller.setCoalesce(true);
            shiftPoller.start();
        }
    }

    /**
     * Ferma il poller degli shift. Da chiamare quando l'adapter viene rimosso
     * (es. riavvio dell'emulatore) per non lasciare Timer EDT orfani.
     */
    public void dispose() {
        if (shiftPoller != null) {
            shiftPoller.stop();
            shiftPoller = null;
        }
    }

    @Override
    public void keyPressed(KeyEvent e) {
        // Evita key-repeat del sistema operativo: Keyboard.java gestisce già lo stato
        if (e.isConsumed()) {
            return;
        }
        // Gli shift sono di competenza del poller fisico: consuma e ignora qui.
        if (shiftPollerActive && e.getKeyCode() == KeyEvent.VK_SHIFT) {
            e.consume();
            return;
        }
        // AltGr: l'Alt destro va all'Amiga come sola Amiga destra, senza il Ctrl
        // che Windows gli affianca (vedi isSyntheticAltGrCtrl).
        if (isSyntheticAltGrCtrl(e)) {
            altGrCtrlSuppressed = true;
            e.consume();
            return;
        }
        int amigaKey = translate(e);
        if (amigaKey >= 0) {
            int keyId = keyId(e, amigaKey);
            if (!pressedKeys.containsKey(keyId)) {
                pressedKeys.put(keyId, amigaKey);
                keyboard.pressKey(amigaKey);
            }
            e.consume();
        }
    }

    @Override
    public void keyReleased(KeyEvent e) {
        if (e.isConsumed()) return;
        // Gli shift sono di competenza del poller fisico: consuma e ignora qui.
        if (shiftPollerActive && e.getKeyCode() == KeyEvent.VK_SHIFT) {
            e.consume();
            return;
        }
        // Contropartita del press ignorato: Windows chiude AltGr con un keyup di
        // Ctrl sinistro. Ignorarlo evita di rilasciare un Ctrl vero tenuto premuto.
        if (altGrCtrlSuppressed && isLeftCtrl(e)) {
            altGrCtrlSuppressed = false;
            e.consume();
            return;
        }
        int translated = translate(e);
        Integer pressedKey = pressedKeys.remove(keyId(e, translated));
        int amigaKey = pressedKey != null ? pressedKey : translated;
        if (amigaKey >= 0) {
            keyboard.releaseKey(amigaKey);
            e.consume();
        }
        // Fallback (poller fisico non disponibile): recupera i keyReleased che
        // Windows non consegna quando si rilascia uno di due shift tenuti insieme.
        if (!shiftPollerActive) {
            reconcileModifiers(e);
        }
    }

    /**
     * Poller dello stato fisico dei due shift (chiamato sull'EDT dal Timer).
     *
     * Legge L/R shift direttamente dall'OS ({@link WindowsKeyState}) e sintetizza
     * press/release indipendenti per {@link Keyboard#KEY_L_SHIFT} e
     * {@link Keyboard#KEY_R_SHIFT}. Aggira il fatto che Windows non consegna ad
     * AWT il keyup del primo shift rilasciato quando entrambi sono premuti.
     *
     * Il gating sul focus evita di catturare shift destinati ad altre finestre:
     * se la finestra non ha il focus, entrambi gli shift vengono considerati su
     * (ed eventuali sintesi pendenti rilasciate), così non restano incantati.
     */
    private void pollShiftState() {
        boolean focused = focusWindow != null && focusWindow.isFocused();
        boolean lNow = focused && WindowsKeyState.isDown(WindowsKeyState.VK_LSHIFT);
        boolean rNow = focused && WindowsKeyState.isDown(WindowsKeyState.VK_RSHIFT);

        if (lNow != lShiftDown) {
            lShiftDown = lNow;
            if (lNow) keyboard.pressKey(Keyboard.KEY_L_SHIFT);
            else      keyboard.releaseKey(Keyboard.KEY_L_SHIFT);
        }
        if (rNow != rShiftDown) {
            rShiftDown = rNow;
            if (rNow) keyboard.pressKey(Keyboard.KEY_R_SHIFT);
            else      keyboard.releaseKey(Keyboard.KEY_R_SHIFT);
        }
    }

    /**
     * Rilascio "fisico" dei tasti del reset CTRL+AMIGA+AMIGA (chiamato sull'EDT).
     *
     * Finché la combinazione è premuta la macchina è tenuta in reset dalla tastiera,
     * ed esce dal reset solo quando i tasti vengono rilasciati: se l'OS perde uno dei
     * keyup, la macchina resterebbe ferma. È lo stesso limite di Windows aggirato per
     * gli shift in {@link #pollShiftState()} — non consegna il keyReleased del primo
     * modificatore rilasciato quando ne sono premuti due dello stesso tipo, ed è
     * proprio il caso dei due Alt (= i due tasti Amiga) — e vale anche per la perdita
     * di fuoco della finestra.
     *
     * Il poller interviene SOLO mentre la combinazione risulta premuta, quindi non
     * può interferire con la normale digitazione. Il tetto di sicurezza in
     * {@code Keyboard} copre le piattaforme senza lettura dello stato fisico.
     */
    private void pollResetComboState() {
        if (!keyboard.isResetComboHeld()) return;

        boolean focused = focusWindow != null && focusWindow.isFocused();
        if (!focused || !(WindowsKeyState.isDown(WindowsKeyState.VK_LCONTROL)
                       || WindowsKeyState.isDown(WindowsKeyState.VK_RCONTROL))) {
            releaseTrackedAmigaKey(Keyboard.KEY_CTRL);
        }
        if (!focused || !(WindowsKeyState.isDown(WindowsKeyState.VK_LMENU)
                       || WindowsKeyState.isDown(WindowsKeyState.VK_LWIN))) {
            releaseTrackedAmigaKey(Keyboard.KEY_L_AMIGA);
        }
        if (!focused || !(WindowsKeyState.isDown(WindowsKeyState.VK_RMENU)
                       || WindowsKeyState.isDown(WindowsKeyState.VK_RWIN))) {
            releaseTrackedAmigaKey(Keyboard.KEY_R_AMIGA);
        }
    }

    /**
     * true se questo keyPressed è il Control <em>sintetico</em> di AltGr.
     *
     * <p>Sui layout con AltGr (italiano, tedesco, francese…) Windows non consegna
     * il solo Alt destro: genera prima un keydown di Control sinistro e poi quello
     * dell'Alt destro (AltGr <em>è</em> Ctrl+Alt a livello di OS). Tradotti alla
     * lettera arrivano all'Amiga CTRL + Amiga destra, e basta tenere premuto l'Alt
     * sinistro perché la combinazione diventi CTRL+AMIGA+AMIGA, cioè un reset.
     *
     * <p>Il riconoscimento non guarda l'ordine degli eventi ma lo stato FISICO
     * dell'Alt destro ({@link WindowsKeyState}): quando il Ctrl sintetico viene
     * dispatchato, l'Alt destro che l'ha generato risulta già premuto. Il controllo
     * sul layout limita la regola alle tastiere che AltGr ce l'hanno davvero, così
     * sui layout tipo US (Alt destro = Alt normale) "Alt destro + Ctrl" continua a
     * funzionare. Resta ambiguo — e viene ignorato — solo un Ctrl premuto mentre
     * AltGr è già giù: lì l'OS ha comunque il Ctrl segnato come premuto.
     */
    private boolean isSyntheticAltGrCtrl(KeyEvent e) {
        return isLeftCtrl(e)
                && WindowsKeyState.isDown(WindowsKeyState.VK_RMENU)
                && WindowsKeyState.activeLayoutHasAltGr();
    }

    /** true per il Control sinistro, l'unico che Windows sintetizza per AltGr. */
    private static boolean isLeftCtrl(KeyEvent e) {
        return e.getKeyCode() == KeyEvent.VK_CONTROL
                && e.getKeyLocation() != KeyEvent.KEY_LOCATION_RIGHT;
    }

    /**
     * Sincronizza {@link #pressedKeys} con lo stato reale dei modificatori
     * riportato da {@link KeyEvent#getModifiersEx()}. Se la maschera di un
     * modificatore è a 0 (tasto fisicamente su) ma un tasto Amiga corrispondente
     * è ancora tracciato come premuto, ne sintetizza il rilascio.
     *
     * Usato solo come fallback quando il poller fisico non è disponibile.
     * Rilasciamo solo quando l'OS conferma il modificatore su, quindi la
     * sintesi è sempre corretta e non può generare rilasci spuri.
     */
    private void reconcileModifiers(KeyEvent e) {
        int ex = e.getModifiersEx();
        if ((ex & KeyEvent.SHIFT_DOWN_MASK) == 0) {
            releaseTrackedAmigaKey(Keyboard.KEY_L_SHIFT);
            releaseTrackedAmigaKey(Keyboard.KEY_R_SHIFT);
        }
    }

    /**
     * Se {@code amigaKey} risulta ancora tracciato come premuto in
     * {@link #pressedKeys}, lo rimuove e ne notifica il rilascio alla tastiera.
     */
    private void releaseTrackedAmigaKey(int amigaKey) {
        boolean wasTracked = pressedKeys.values().removeIf(v -> v == amigaKey);
        if (wasTracked) {
            keyboard.releaseKey(amigaKey);
        }
    }

    private int translate(KeyEvent e) {
        int vk = e.getKeyCode();

        // Modificatori: distingui Left/Right con getKeyLocation()
        if (vk == KeyEvent.VK_SHIFT) {
            return e.getKeyLocation() == KeyEvent.KEY_LOCATION_RIGHT
                    ? Keyboard.KEY_R_SHIFT : Keyboard.KEY_L_SHIFT;
        }
        if (vk == KeyEvent.VK_ALT) {
            // Left Alt → Left Amiga, Right Alt → Right Amiga
            return e.getKeyLocation() == KeyEvent.KEY_LOCATION_RIGHT
                    ? Keyboard.KEY_R_AMIGA : Keyboard.KEY_L_AMIGA;
        }
        if (vk == KeyEvent.VK_ALT_GRAPH) {
            // Alcune piattaforme/layout consegnano l'Alt destro come AltGr.
            return Keyboard.KEY_R_AMIGA;
        }
        if (vk == KeyEvent.VK_CONTROL) {
            return Keyboard.KEY_CTRL;
        }
        if (vk == KeyEvent.VK_META || vk == KeyEvent.VK_WINDOWS) {
            // Tasto Win/Cmd → Left Amiga (o Right Amiga se a destra)
            return e.getKeyLocation() == KeyEvent.KEY_LOCATION_RIGHT
                    ? Keyboard.KEY_R_AMIGA : Keyboard.KEY_L_AMIGA;
        }

        // Blocco navigazione: tasti che sull'Amiga non esistono, riusati per i tre tasti
        // Amiga che nessun tasto host raggiungerebbe altrimenti. Gli Alt dell'Amiga sono
        // quelli che con il keymap italiano (setmap i) danno @ # [ ] { }: gli Alt host
        // sono già i due tasti Amiga, e F12 apre il popup. Il tastierino è escluso: con
        // NumLock spento manda gli stessi VK del blocco navigazione.
        if (e.getKeyLocation() != KeyEvent.KEY_LOCATION_NUMPAD) {
            switch (vk) {
                case KeyEvent.VK_PAGE_UP:   return Keyboard.KEY_L_ALT;
                case KeyEvent.VK_PAGE_DOWN: return Keyboard.KEY_R_ALT;
                case KeyEvent.VK_HOME:      return Keyboard.KEY_HELP;
                default: break;
            }
        }

        if (vk == KeyEvent.VK_ENTER && e.getKeyLocation() == KeyEvent.KEY_LOCATION_NUMPAD) {
            return 0x43;
        }

        if (e.getKeyLocation() != KeyEvent.KEY_LOCATION_NUMPAD) {
            Integer charMapped = CHAR_KEYMAP.get(e.getKeyChar());
            if (charMapped != null) return charMapped;
        }

        Integer mapped = KEYMAP.get(vk);
        if (mapped != null) return mapped;

        Integer fallback = FALLBACK_KEYMAP.get(e.getKeyChar());
        return fallback != null ? fallback : -1;
    }

    /**
     * Identità del tasto fisico, con cui {@link #pressedKeys} appaia press e release.
     *
     * <p>I tasti serviti da {@link #FALLBACK_KEYMAP} hanno tutti {@code keyCode == 0}
     * ({@link KeyEvent#VK_UNDEFINED}): l'identità per keyCode li confonderebbe fra loro
     * (premendo ò e à il secondo non partirebbe nemmeno). Per loro l'identità è il tasto
     * Amiga di destinazione, che è la stessa per tutti i caratteri prodotti dallo stesso
     * tasto (ò/ç → $29) e quindi regge anche se lo Shift cambia fra press e release.
     */
    private static int keyId(KeyEvent e, int amigaKey) {
        if (e.getKeyCode() == KeyEvent.VK_UNDEFINED) {
            return 0x10000 | (amigaKey & 0xFF);
        }
        return (e.getKeyCode() << 4) | (e.getKeyLocation() & 0x0F);
    }
}
