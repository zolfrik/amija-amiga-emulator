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

import io.github.zolfrik.amija.common.CIAPinSet;
import io.github.zolfrik.amija.common.MOS8520;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.Deque;

/**
 * Emulazione della tastiera Amiga 500.
 *
 * Implementa il protocollo seriale su CIA-A (KDAT=SP, KCLK=CNT).
 * Specifica hardware di riferimento: Amiga HW Reference Manual, App. A + schematics rev. 6A.
 *
 * Protocollo:
 *   - Bitrate: ~17 kbit/s  →  periodo per bit ≈ 58.8 µs
 *   - Clock PAL: 7.09375 MHz  →  ~417 cicli/bit  →  3 fasi × ~139 cicli ciascuna
 *   - Ordine di trasmissione: bit 6,5,4,3,2,1,0,7 (MSB logico per primo)
 *   - Logica invertita su linea fisica: 1 logico = 0V (LOW), 0 logico = 5V (HIGH)
 *
 * Sequenza power-on:
 *   1. Attende pulse di reset su SP (CIA-A in modalità output)
 *   2. Attende ≥ 1 ms
 *   3. Invia 0xFD  (Initiate Power-Up Key Stream)
 *   4. Attende handshake ACK dall'Amiga (KDAT LOW per ≥ 85 µs)
 *   5. Attende ≥ 1 ms
 *   6. Invia 0xFE  (Terminate Power-Up Key Stream)
 *   7. Attende ACK → entra in IDLE
 *
 * Formato keycode (1 byte):
 *   - Bit [7:1]: raw key number (7 bit)
 *   - Bit 0:     0 = key down, 1 = key up
 *
 * Codici speciali del controller:
 *   - 0xF8: Lost Sync  (tastiera ha perso la sincronizzazione)
 *   - 0xF9: Keyboard Failure
 *   - 0xFA: Power-Up Stream Error
 *   - 0xFD: Initiate Power-Up Stream
 *   - 0xFE: Terminate Power-Up Stream
 *
 * Gestione ACK:
 *   - Handshake valido: KDAT LOW per almeno MIN_ACK_CYCLES cicli, poi ritorna HIGH
 *   - Timeout ACK: se non arriva entro ~143 ms, invia 0xF8 e ritenta il byte corrente
 *   - Dopo MAX_RETRIES tentativi falliti, il byte viene scartato
 *
 * Reset CTRL+AMIGA+AMIGA:
 *   - La MPU della tastiera rileva la combinazione e asserisce la linea /KBRST
 *     (pin 3 del connettore tastiera dell'A500), che sulla scheda madre genera
 *     /RESET e /HALT verso il 68000 e tutti i chip.
 *   - Finché /KBRST è bassa la tastiera NON trasmette nulla: i keycode dei tre
 *     tasti (e di quelli premuti nel frattempo) non arrivano mai all'Amiga.
 *   - La linea resta bassa per almeno 500 ms e comunque finché i tasti restano
 *     premuti: la macchina riparte al rilascio, non alla pressione.
 *   - Lo stato della linea si legge con {@link #isResetLineAsserted()}; è
 *     {@code Gary} a propagarla su /RESET (vedi AbstractAgnus.serviceCiaEvent).
 */
public class Keyboard {

    // -------------------------------------------------------------------------
    // Raw key numbers (bits [7:1] del byte keycode trasmesso)
    // Tabella completa: Amiga HW Reference Manual, Appendix A
    // -------------------------------------------------------------------------
    public static final int KEY_ESC        = 0x45;
    public static final int KEY_F1         = 0x50;
    public static final int KEY_DEL        = 0x46;
    public static final int KEY_RETURN     = 0x44;
    public static final int KEY_SPACE      = 0x40;
    public static final int KEY_BACKSPACE  = 0x41;
    public static final int KEY_TAB        = 0x42;
    public static final int KEY_CTRL       = 0x63;
    public static final int KEY_CAPS_LOCK  = 0x62;
    public static final int KEY_L_SHIFT    = 0x60;
    public static final int KEY_R_SHIFT    = 0x61;
    public static final int KEY_L_ALT      = 0x64;
    public static final int KEY_R_ALT      = 0x65;
    public static final int KEY_L_AMIGA    = 0x66;
    public static final int KEY_R_AMIGA    = 0x67;
    public static final int KEY_HELP       = 0x5F;
    public static final int KEY_UP         = 0x4C;
    public static final int KEY_DOWN       = 0x4D;
    public static final int KEY_LEFT       = 0x4F;
    public static final int KEY_RIGHT      = 0x4E;

    // -------------------------------------------------------------------------
    // Codici speciali tastiera
    // -------------------------------------------------------------------------
    public static final int KCODE_LOST_SYNC     = 0xF8;
    public static final int KCODE_KBD_FAILURE   = 0xF9;
    public static final int KCODE_POWERUP_ERROR = 0xFA;
    public static final int KCODE_POWERUP_INIT  = 0xFD;
    public static final int KCODE_POWERUP_TERM  = 0xFE;

    // -------------------------------------------------------------------------
    // Costanti di timing (clock PAL 7.09375 MHz)
    // -------------------------------------------------------------------------

    /** Cicli per singola fase (KDAT setup / KCLK low / KCLK high ≈ 19.6 µs ciascuna). */
    private static final int  PHASE_CYCLES    = 139;

    /** ~1 ms in cicli PAL. */
    private static final long DELAY_1MS       = 7_094L;

    /** ~143 ms — timeout handshake ACK (HW Ref. Manual §9). */
    private static final long ACK_TIMEOUT     = 1_014_506L;

    /** Durata minima del pulse ACK: ~1 µs ≈ 7 cicli PAL.
     *
     *  L'HRM (3a ed.) dice: "While some keyboards can detect a 1 microsecond
     *  handshake pulse, the pulse must be at least 85 microseconds for operation
     *  with ALL models of Amiga keyboards." I 603 cicli (~85µs) usati in
     *  precedenza erano il limite "garantito su tutti i modelli", ma vAmiga e
     *  WinUAE accettano l'handshake già da ~1µs (vAmiga: (spHigh-spLow)/28 >= 1).
     *  Il keyboard.device del Kickstart pulsa l'handshake per ~85µs, ma molti
     *  giochi con handler di tastiera custom (es. Pinball Dreams) lo pulsano per
     *  molto meno: con la soglia a 85µs il loro ACK veniva rifiutato → timeout
     *  143ms → Lost Sync → ritrasmissione, da cui levette/tasti "lenti/incantati".
     *  Allineiamo la soglia a ~1µs come i reference emulator. */
    private static final long MIN_ACK_CYCLES  = 7L;

    /** Tentativi massimi prima di abbandonare un byte non confermato. */
    private static final int  MAX_RETRIES     = 3;

    /** Durata minima dell'impulso /KBRST: 500 ms (HW Ref. Manual, keyboard reset).
     *  La MPU tiene la linea bassa almeno per questo tempo anche se i tasti vengono
     *  rilasciati subito, così il ciclo di reset è sempre completo. */
    private static final long KBRST_MIN_CYCLES = 3_546_895L;   // 0,5 s @ 7,09379 MHz

    /** Tetto di sicurezza sull'impulso /KBRST (5 s).
     *
     *  Sull'hardware la linea resta bassa finché i tre tasti sono premuti, e la MPU
     *  vede sempre il rilascio. L'host no: Windows non consegna ad AWT il keyup del
     *  primo modificatore rilasciato quando ne sono premuti due dello stesso tipo
     *  (lo stesso limite aggirato dal poller fisico di AmigaKeyAdapter), e con la
     *  finestra che perde il fuoco un keyup può sparire del tutto. Senza questo tetto
     *  un keyup perso lascerebbe la macchina in reset per sempre. */
    private static final long KBRST_MAX_CYCLES = 35_468_950L;  // 5 s

    // -------------------------------------------------------------------------
    // Stato della macchina a stati
    // -------------------------------------------------------------------------
    private enum State {
        /** Attesa del pulse di reset iniziale su SP (CIA-A in output). */
        POWERON_WAIT,
        /** SP rilevato in output — attesa del fronte di discesa (fine pulse). */
        DETECTED_OUTPUT,
        /** Attesa di 1 ms dopo la fine del pulse prima di inviare 0xFD. */
        WAIT_AFTER_PULSE,
        /** Trasmissione in corso: fase bit-per-bit. */
        SENDING_BYTE,
        /** Byte trasmesso — in attesa di ACK dall'Amiga. */
        WAITING_FOR_ACK,
        /** ACK ricevuto per 0xFD — attesa di 1 ms prima di inviare 0xFE. */
        WAIT_BEFORE_FE,
        /** Power-up completato — in attesa di eventi dalla coda. */
        IDLE
    }

    // -------------------------------------------------------------------------
    // Dipendenze hardware
    // -------------------------------------------------------------------------
    private final MOS8520  ciaA;
    private final CIAPinSet pins;

    // -------------------------------------------------------------------------
    // Variabili di stato
    // -------------------------------------------------------------------------
    private State state = State.POWERON_WAIT;

    /** Timer generico (timeout, delay). */
    private long timer = 0;

    /** Buffer di trasmissione con bit riordinati secondo la specifica Amiga. */
    private int  sendBuffer      = 0;
    /** Valore originale del byte in trasmissione (per log e decisioni di stato). */
    private int  currentByteVal  = 0;
    /** Indice del bit da trasmettere (parte da 7). */
    private int  bitIndex        = 7;
    /** Sotto-stato della macchina di invio (0=KDAT, 1=KCLK↓, 2=KCLK↑). */
    private int  sendSubState    = 0;
    /** Accumulatore cicli per il timer di fase. */
    private int  sendTickCounter = 0;

    /** Contatore cicli di attesa ACK (per timeout). */
    private long ackTimer      = 0;
    /** Cicli in cui KDAT è rimasto LOW nell'attuale impulso discendente. */
    private long ackLowCycles  = 0;
    /** Stato precedente del pin SP (per rilevare fronti). */
    private boolean lastSpState = true;
    /** Flag impostato dall'ACK monitor quando rileva un pulse valido. */
    private boolean ackReceived = false;

    /** Byte da reinviare dopo l'ACK di un 0xF8 (Lost Sync). -1 = nessuno. */
    private int retryByteVal   = -1;
    /** Numero di tentativi effettuati per il byte corrente. */
    private int retryCount     = 0;

    /** Coda FIFO dei keycodes da trasmettere (formato: rawKeyNum<<1 | upFlag).
     *  Acceduta da due thread (MasterClock e Swing EDT): deve essere thread-safe. */
    private final Deque<Integer> keyQueue = new ConcurrentLinkedDeque<>();


    /** Stato dei tasti modificatori per rilevare CTRL+AMIGA+AMIGA.
     *  Aggiornati dal thread che consegna gli eventi di tastiera (EDT di Swing). */
    private boolean ctrlDown   = false;
    private boolean lAmigaDown = false;
    private boolean rAmigaDown = false;

    /** true quando i tre tasti del reset sono premuti INSIEME (scritto dall'EDT,
     *  letto dal thread di emulazione: è ciò che tiene bassa /KBRST). */
    private volatile boolean resetComboHeld = false;

    /** true dalla combinazione completata finché tutti e tre i tasti non sono
     *  rilasciati: i loro keycode — pressione e rilascio — non vengono trasmessi.
     *  La MPU reale, rilevata la combinazione, smette di trasmettere e pilota
     *  soltanto /KBRST; senza questo flag l'Amiga appena riavviato riceverebbe i
     *  key-up "orfani" dei tasti del reset. */
    private volatile boolean resetKeysSuppressed = false;

    /** /KBRST asserita verso la scheda madre (true = linea bassa).
     *  Scritta dal thread di emulazione in {@link #tick(int)}. */
    private volatile boolean kbResetAsserted = false;

    /** Cicli di clock trascorsi da quando /KBRST è stata asserita. */
    private long kbResetCycles = 0;

    /** true quando la MPU è pronta a rilevare una NUOVA combinazione di reset.
     *  Si ri-arma solo quando i tasti risultano rilasciati: l'assertione segue il
     *  fronte della combinazione, non il livello, così lo scadere del tetto di
     *  sicurezza non fa ripartire subito un altro impulso a tasti (apparentemente)
     *  ancora premuti. */
    private boolean resetArmed = true;


    // =========================================================================
    // Costruttore
    // =========================================================================

    public Keyboard(MOS8520 ciaA) {
        this.ciaA = ciaA;
        this.pins = ciaA.getPinSet();
        // Linee rilasciate (HIGH) prima dell'inizializzazione
        pins.getSpPin().setExternalInput(true);
        pins.getCntPin().setExternalInput(true);
    }

    /**
     * Accodare un evento di pressione tasto.
     *
     * @param rawKeyNum numero grezzo del tasto (7 bit), vedere costanti KEY_*
     */
    public void pressKey(int rawKeyNum) {
        if (updateModifiers(rawKeyNum, true)) return;   // reset in corso: nessuna trasmissione
        // Keycode format: {upFlag=0, rawKeyNum[6:0]} — bit rotation applied in beginTransmit
        int keycode = rawKeyNum & 0x7F;
        keyQueue.addLast(keycode);
        // NB: NON si chiama drainQueueIfIdle() qui — vedi nota sul threading in
        // drainQueueIfIdle(). L'accodamento è l'unica operazione thread-safe.
    }

    /**
     * Accodare un evento di rilascio tasto.
     *
     * @param rawKeyNum numero grezzo del tasto (7 bit), vedere costanti KEY_*
     */
    public void releaseKey(int rawKeyNum) {
        if (updateModifiers(rawKeyNum, false)) return;  // reset in corso: nessuna trasmissione
        // Keycode format: {upFlag=1, rawKeyNum[6:0]} — bit rotation applied in beginTransmit
        int keycode = (rawKeyNum & 0x7F) | 0x80;
        keyQueue.addLast(keycode);
        // NB: NON si chiama drainQueueIfIdle() qui — vedi nota sul threading in
        // drainQueueIfIdle(). L'accodamento è l'unica operazione thread-safe.
    }

    /**
     * Restituisce il numero di keycodes in attesa di essere inviati.
     */
    public int getPendingKeyCount() {
        return keyQueue.size();
    }

    /**
     * Svuota la coda e azzera lo stato, compresa la linea /KBRST e i modificatori.
     * Da chiamare in caso di reset hardware esterno (power-on).
     */
    public void hardReset() {
        resetProtocolState();
        ctrlDown = lAmigaDown = rAmigaDown = false;
        resetComboHeld      = false;
        resetKeysSuppressed = false;
        kbResetAsserted     = false;
        kbResetCycles       = 0;
        resetArmed          = true;
    }

    /**
     * Riporta il protocollo seriale allo stato di power-up: coda vuota, linee
     * rilasciate, macchina a stati in attesa dell'impulso di sync su SP.
     * NON tocca i modificatori né la linea /KBRST (che appartengono alla MPU e
     * devono sopravvivere al reset che la MPU stessa sta pilotando).
     */
    private void resetProtocolState() {
        keyQueue.clear();
        retryByteVal = -1;
        retryCount   = 0;
        ackReceived  = false;
        ackTimer     = 0;
        ackLowCycles = 0;
        lastSpState  = true;
        pins.getSpPin().setExternalInput(true);
        pins.getCntPin().setExternalInput(true);
        state = State.POWERON_WAIT;
    }

    /**
     * @return true se la tastiera sta assertando /KBRST verso la scheda madre
     *         (CTRL+AMIGA+AMIGA premuti, o impulso minimo da 500 ms non ancora
     *         scaduto). Letta a ogni E-clock da {@code AbstractAgnus}.
     */
    public boolean isResetLineAsserted() {
        return kbResetAsserted;
    }

    /** @return true se i tre tasti della combinazione di reset sono premuti ora. */
    public boolean isResetComboHeld() {
        return resetComboHeld;
    }

    // =========================================================================
    // Tick principale — da chiamare ogni frame o a ogni gruppo di cicli
    // =========================================================================

    public void tick(int cycles) {
        // Reset CTRL+AMIGA+AMIGA: finché /KBRST è bassa la tastiera è muta
        // (linee rilasciate, nessun keycode, nessun handshake).
        if (serviceResetLine(cycles)) return;

        // Monitoraggio continuo del pulse ACK (indipendente dallo stato)
        pollAckPin(cycles);

        int cra = ciaA.readRegister(0xE) & 0xFF;
        boolean spOutputMode = (cra & 0x40) != 0;

        switch (state) {

            case POWERON_WAIT:
                // Attende che la CPU porti SP in modalità output (inizio pulse reset)
                if (spOutputMode) {
                    state = State.DETECTED_OUTPUT;
                }
                break;

            case DETECTED_OUTPUT:
                // Attende la fine del pulse (SP torna in input)
                if (!spOutputMode) {
                    state = State.WAIT_AFTER_PULSE;
                    timer = DELAY_1MS;
                }
                break;

            case WAIT_AFTER_PULSE:
                timer -= cycles;
                if (timer <= 0) {
                    retryCount = 0;
                    beginTransmit(KCODE_POWERUP_INIT);
                }
                break;

            case SENDING_BYTE:
                // Avanza la trasmissione bit per bit con granularità PHASE_CYCLES
                sendTickCounter += cycles;
                while (sendTickCounter >= PHASE_CYCLES) {
                    sendTickCounter -= PHASE_CYCLES;
                    handleSendStep();
                    // handleSendStep() può cambiare state → usciamo dal loop
                    if (state != State.SENDING_BYTE) break;
                }
                break;

            case WAITING_FOR_ACK:
                ackTimer += cycles;
                if (ackReceived) {
                    // ACK valido ricevuto
                    ackReceived = false;
                    ackTimer    = 0;
                    onAckReceived();
                } else if (ackTimer >= ACK_TIMEOUT) {
                    // Timeout: Lost Sync
                    ackTimer = 0;
                    handleAckTimeout();
                }
                break;

            case WAIT_BEFORE_FE:
                timer -= cycles;
                if (timer <= 0) {
                    retryCount = 0;
                    beginTransmit(KCODE_POWERUP_TERM);
                }
                break;

            case IDLE:
                // Pronto: scarica la coda se ci sono eventi
                drainQueueIfIdle();
                break;
        }
    }

    // =========================================================================
    // Linea /KBRST (reset CTRL+AMIGA+AMIGA)
    // =========================================================================

    /**
     * Pilota la linea /KBRST secondo la combinazione di reset.
     *
     * Comportamento della MPU reale (6570-036 dell'A500):
     *   1. Appena i tre tasti risultano premuti insieme, abbassa /KBRST e
     *      abbandona qualunque trasmissione in corso.
     *   2. Tiene la linea bassa per almeno 500 ms, e comunque finché i tasti
     *      restano premuti: la macchina resta ferma in reset e riparte al
     *      RILASCIO, non alla pressione.
     *   3. Al rilascio della linea la macchina esegue il ciclo di boot; il
     *      Kickstart ri-inizializza keyboard.device e pulsa SP, facendo
     *      ripartire da capo la sequenza di power-up (0xFD/0xFE).
     *
     * @param cycles cicli di clock trascorsi dall'ultima chiamata
     * @return true se /KBRST è asserita (la tastiera non deve trasmettere)
     */
    private boolean serviceResetLine(int cycles) {
        if (!kbResetAsserted) {
            if (!resetComboHeld) { resetArmed = true; return false; }
            if (!resetArmed)     return false;   // combinazione già servita
            kbResetAsserted = true;
            resetArmed      = false;
            kbResetCycles   = 0;
            // I tasti in coda non arriveranno mai all'Amiga, che sta per essere
            // resettato: la MPU tronca la trasmissione e rilascia KDAT/KCLK.
            resetProtocolState();
            return true;
        }

        kbResetCycles += cycles;
        boolean minimumElapsed = kbResetCycles >= KBRST_MIN_CYCLES;
        if ((!resetComboHeld && minimumElapsed) || kbResetCycles >= KBRST_MAX_CYCLES) {
            kbResetAsserted = false;
            kbResetCycles   = 0;
            return false;
        }
        return true;
    }

    // =========================================================================
    // Implementazione interna
    // =========================================================================

    /**
     * Monitora il pin SP/KDAT per rilevare un pulse ACK valido.
     *
     * Il protocollo Amiga prevede che, dopo aver ricevuto un keycode, la CIA-A
     * abbassi KDAT per almeno ~1 µs e poi lo rilasci. Questo metodo misura la
     * durata del livello LOW e segnala un ACK valido al termine del pulse.
     *
     * FIX auto-ACK (levette incantate in Pinball Dreams): la misura va fatta
     * SOLO in WAITING_FOR_ACK. Durante SENDING_BYTE la linea KDAT è pilotata
     * dalla TASTIERA STESSA (logica invertita: bit logico 1 → LOW), e la vecchia
     * versione accumulava ackLowCycles anche lì. Al rilascio delle linee a fine
     * trasmissione, il fronte di salita dell'ULTIMO BIT DATI veniva quindi letto
     * come handshake dell'Amiga: ackLowCycles valeva già ≥ PHASE_CYCLES (139),
     * ben oltre MIN_ACK_CYCLES → ACK spurio immediato.
     *
     * L'ultimo bit trasmesso è sendBuffer[0] = keycode[7] = flag up/down, e vale
     * 1 (→ linea LOW) proprio per i KEY-UP: l'auto-ACK colpiva sistematicamente
     * i rilasci. La tastiera passava subito al keycode successivo senza attendere
     * l'handshake reale del gioco; con pressioni rapide il byte seguente
     * sovrascriveva l'SDR della CIA prima che il gioco lo leggesse, e il key-up
     * andava perso → levetta bloccata fino alla pressione successiva.
     */
    private void pollAckPin(int cycles) {
        if (state != State.WAITING_FOR_ACK) {
            // Fuori dall'attesa dell'handshake la linea è pilotata dalla tastiera:
            // i suoi livelli LOW non vanno mai contati come ACK. Si riparte puliti,
            // così solo un fronte di discesa GENERATO DALL'AMIGA dopo l'ingresso in
            // WAITING_FOR_ACK può produrre un handshake valido.
            ackLowCycles = 0;
            lastSpState  = true;
            return;
        }

        boolean spNow = pins.getSpPin().getPinState();

        if (!spNow) {
            // KDAT è LOW — accumulo durata
            ackLowCycles += cycles;
        } else {
            // KDAT tornato HIGH — fronte di salita
            if (!lastSpState && ackLowCycles >= MIN_ACK_CYCLES) {
                // Pulse abbastanza lungo → ACK valido
                ackReceived = true;
            }
            ackLowCycles = 0;
        }
        lastSpState = spNow;
    }

    /** Logica di transizione dopo un ACK confermato. */
    private void onAckReceived() {
        retryCount = 0;

        if (currentByteVal == KCODE_POWERUP_INIT) {
            // 0xFD confermato → attesa 1ms poi invio 0xFE
            state = State.WAIT_BEFORE_FE;
            timer = DELAY_1MS;

        } else if (currentByteVal == KCODE_POWERUP_TERM) {
            // 0xFE confermato → sequenza power-up completata
            state = State.IDLE;

        } else if (currentByteVal == KCODE_LOST_SYNC) {
            // 0xF8 (Lost Sync) confermato → reinviare il byte originale se presente
            if (retryByteVal >= 0) {
                int toRetry = retryByteVal;
                retryByteVal = -1;
                beginTransmit(toRetry);
            } else {
                state = State.IDLE;
                drainQueueIfIdle();
            }

        } else {
            // Keycode normale confermato → prossimo dalla coda
            retryByteVal = -1;
            state = State.IDLE;
            drainQueueIfIdle();
        }
    }

    /**
     * Gestisce il timeout ACK (~143 ms senza risposta dall'Amiga).
     * Il controller reale invia 0xF8 (Lost Sync) e poi ritrasmette l'ultimo byte.
     */
    private void handleAckTimeout() {
        if (retryCount < MAX_RETRIES) {
            retryCount++;
            // Salva il byte da reinviare (evita di sovrascriverlo con 0xF8)
            if (currentByteVal != KCODE_LOST_SYNC) {
                retryByteVal = currentByteVal;
            }
            beginTransmit(KCODE_LOST_SYNC);
        } else {
            retryCount   = 0;
            retryByteVal = -1;
            state = State.IDLE;
            drainQueueIfIdle();
        }
    }

    /**
     * Preleva il prossimo keycode dalla coda e avvia la trasmissione,
     * ma solo se la macchina è in stato IDLE.
     *
     * THREADING: questo metodo (e quindi beginTransmit) va invocato SOLO dal
     * thread di emulazione, da tick(). pressKey()/releaseKey() sono chiamate
     * dall'EDT di Swing e si limitano ad accodare su keyQueue (concorrente).
     *
     * In precedenza anche l'EDT chiamava drainQueueIfIdle(): i due thread
     * potevano leggere entrambi state==IDLE, fare entrambi pollFirst() e
     * chiamare entrambi beginTransmit(), che sovrascrive TUTTO lo stato di
     * trasmissione (sendBuffer, bitIndex, sendSubState). Il keycode del thread
     * perdente veniva scartato senza mai essere trasmesso. Se il byte perso era
     * un key-up, il tasto restava "premuto" fino alla pressione successiva —
     * esattamente le levette incantate di Pinball Dreams sotto pressioni rapide.
     * Lo stato non è volatile, quindi l'EDT poteva anche leggere uno state stale.
     *
     * tick() è chiamato a ogni E-clock (~709 kHz), quindi togliere il drain
     * dall'EDT costa al più ~1.4 µs di latenza: impercettibile.
     */
    private void drainQueueIfIdle() {
        if (state == State.IDLE) {
            Integer next = keyQueue.pollFirst();   // atomico: null se vuota
            if (next != null) {
                retryCount   = 0;
                retryByteVal = -1;
                beginTransmit(next);
            }
        }
    }

    /**
     * Prepara il buffer di trasmissione e avvia la macchina di invio.
     *
     * Il riordino dei bit segue la specifica Amiga:
     * il byte fisico trasmesso ha i bit nell'ordine 6,5,4,3,2,1,0,7
     * rispetto al valore logico originale.
     *
     * @param val valore logico del byte da trasmettere (0x00–0xFF)
     */
    private void beginTransmit(int val) {
        currentByteVal = val;

        // HRM: bits transmitted in order 6,5,4,3,2,1,0,7 of the keycode byte.
        // This equals a left-rotate-by-1 of the keycode. The CIA-A receives MSB-first,
        // so sendBuffer[7] is transmitted first and becomes CIA-A SDR[7].
        // The ROM reconstructs the keycode with: NOT(SDR), then ROR #1.
        int v = val & 0xFF;
        sendBuffer = ((v << 1) | (v >> 7)) & 0xFF;

        bitIndex        = 7;
        sendSubState    = 0;
        sendTickCounter = 0;
        ackTimer        = 0;
        ackReceived     = false;
        state           = State.SENDING_BYTE;

    }

    /**
     * Avanza di una fase nella trasmissione del byte corrente.
     *
     * Ogni bit richiede 3 fasi (ognuna dura PHASE_CYCLES cicli):
     *   Fase 0: porta KDAT al valore corretto (logica invertita: 1→LOW, 0→HIGH)
     *   Fase 1: abbassa KCLK (fronte di discesa — l'Amiga campiona KDAT qui)
     *   Fase 2: alza  KCLK (fronte di salita — bit acquisito dalla CIA)
     *
     * Al termine di tutti gli 8 bit le linee vengono rilasciate e si passa
     * a WAITING_FOR_ACK.
     */
    private void handleSendStep() {
        switch (sendSubState) {

            case 0: // Imposta KDAT
                boolean bit = (sendBuffer & (1 << bitIndex)) != 0;
                // Logica invertita: bit logico 1 → tensione LOW → false
                pins.getSpPin().setExternalInput(!bit);
                sendSubState = 1;
                break;

            case 1: // KCLK → LOW (fronte di discesa, l'Amiga campiona KDAT)
                pins.getCntPin().setExternalInput(false);
                sendSubState = 2;
                break;

            case 2: // KCLK → HIGH (fronte di salita, bit latched dalla CIA)
                pins.getCntPin().setExternalInput(true);
                bitIndex--;
                if (bitIndex < 0) {
                    // Tutti gli 8 bit inviati → rilascia le linee e attendi ACK
                    pins.getSpPin().setExternalInput(true);
                    pins.getCntPin().setExternalInput(true);
                    ackTimer    = 0;
                    ackReceived = false;
                    state       = State.WAITING_FOR_ACK;
                } else {
                    sendSubState = 0;
                }
                break;
        }
    }

    /**
     * Aggiorna lo stato dei tasti modificatori e valuta la combinazione di reset.
     *
     * Chiamato dal thread degli eventi di tastiera: qui si aggiornano solo i flag,
     * la linea /KBRST è pilotata dal thread di emulazione in {@link #serviceResetLine}.
     *
     * @param rawKeyNum numero grezzo del tasto
     * @param pressed   true = pressione, false = rilascio
     * @return true se il keycode NON va trasmesso (sequenza di reset in corso)
     */
    private boolean updateModifiers(int rawKeyNum, boolean pressed) {
        if (rawKeyNum == KEY_CTRL)    ctrlDown   = pressed;
        if (rawKeyNum == KEY_L_AMIGA) lAmigaDown = pressed;
        if (rawKeyNum == KEY_R_AMIGA) rAmigaDown = pressed;

        // CTRL + L.AMIGA + R.AMIGA → la MPU asserisce /KBRST e smette di trasmettere.
        boolean combo = ctrlDown && lAmigaDown && rAmigaDown;
        resetComboHeld = combo;

        // La decisione va presa PRIMA di sciogliere la soppressione: anche il
        // rilascio dell'ultimo dei tre tasti appartiene alla sequenza di reset e
        // non deve arrivare all'Amiga come key-up orfano.
        boolean suppress = resetKeysSuppressed || kbResetAsserted;
        if (combo) {
            resetKeysSuppressed = true;
            suppress = true;
        } else if (resetKeysSuppressed && !ctrlDown && !lAmigaDown && !rAmigaDown) {
            // Tutti e tre rilasciati: si torna a trasmettere normalmente.
            resetKeysSuppressed = false;
        }
        return suppress;
    }
}
