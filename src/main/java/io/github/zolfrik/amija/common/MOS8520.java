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

package io.github.zolfrik.amija.common;

/**
 * Cycle-exact implementation of the MOS 8520 CIA (Complex Interface Adapter).
 *
 * This implementation follows the "Lorenz" model (by Wolfgang Lorenz), which uses
 * a 64-bit delay pipeline to achieve cycle-level accuracy for timers, interrupts,
 * and serial port events.
 *
 * The Lorenz model is necessary to accurately emulate the specific timing quirks
 * of the 8520, such as delayed interrupt clearing and the specific behavior of
 * one-shot timers.
 *
 * @author alessio.carotenuto
 */
public class MOS8520 {

    private final CIAPinSet pinSet;
    private final boolean isCIAA;
    private boolean todBug = false;
    private int cachedDdra = -1;
    private int cachedDdrb = -1;
    private int cachedPortA = -1;
    private int cachedPortB = -1;
    private boolean cachedIrqDrive;
    private boolean cachedCntDirection;
    private boolean cachedCntDrive;
    private boolean cachedSpDirection;
    private boolean cachedSpDrive;
    private boolean specialPinCacheValid;

    // ── Action flags (Delay Pipeline) ─────────────────────────────────────────
    // These bits represent events scheduled to happen in future cycles.
    // They are shifted left by 1 in every tick().

    private static final long CIACountA0   = (1L << 0);
    private static final long CIACountA1   = (1L << 1);
    private static final long CIACountA2   = (1L << 2); // Count enable (delayed)
    private static final long CIACountA3   = (1L << 3); // Count execution
    private static final long CIACountB0   = (1L << 4);
    private static final long CIACountB1   = (1L << 5);
    private static final long CIACountB2   = (1L << 6);
    private static final long CIACountB3   = (1L << 7);
    private static final long CIALoadA0    = (1L << 8);
    private static final long CIALoadA1    = (1L << 9);
    private static final long CIALoadA2    = (1L << 10);

    private static final long CIALoadB0    = (1L << 11);
    private static final long CIALoadB1    = (1L << 12);
    private static final long CIALoadB2    = (1L << 13);

    private static final long CIAPB6Low0   = (1L << 14);
    private static final long CIAPB6Low1   = (1L << 15);

    private static final long CIAPB7Low0   = (1L << 16);
    private static final long CIAPB7Low1   = (1L << 17);

    private static final long CIASetInt0   = (1L << 18);
    private static final long CIASetInt1   = (1L << 19);

    private static final long CIAClearInt0 = (1L << 20);
    private static final long CIAOneShotA0 = (1L << 21);
    private static final long CIAOneShotB0 = (1L << 22);

    private static final long CIAReadIcr0  = (1L << 23);
    private static final long CIAReadIcr1  = (1L << 24);

    private static final long CIAClearIcr0 = (1L << 25);
    private static final long CIAClearIcr1 = (1L << 26);
    private static final long CIAAckIcr0   = (1L << 28);
    private static final long CIAAckIcr1   = (1L << 29);

    private static final long CIASetIcr0   = (1L << 30);
    private static final long CIASetIcr1   = (1L << 31);

    private static final long CIATODInt0   = (1L << 32);

    private static final long CIASerInt0   = (1L << 33);
    private static final long CIASerInt2   = (1L << 35);

    private static final long CIASdrToSsr0 = (1L << 36);
    private static final long CIASdrToSsr1 = (1L << 37);

    private static final long CIASerClk0   = (1L << 42);
    private static final long CIASerClk1   = (1L << 43);
    private static final long CIASerClk2   = (1L << 44);
    private static final long CIALast      = (1L << 46);

    private static final long CIADelayMask = ~(CIALast
            | CIACountA0 | CIACountB0 | CIALoadA0 | CIALoadB0 | CIAPB6Low0
            | CIAPB7Low0 | CIASetInt0 | CIAClearInt0 | CIAOneShotA0 | CIAOneShotB0
            | CIAReadIcr0 | CIAClearIcr0 | CIAAckIcr0 | CIASetIcr0 | CIATODInt0
            | CIASerInt0 | CIASdrToSsr0 | CIASerClk0);

    // ── Diagnostics ──────────────────────────────────────────────────────────
    public void setTodBug(boolean en) { this.todBug = en; }

    // -------------------------------------------------------------------------
    // Internal registers and state
    // -------------------------------------------------------------------------

    private long clock; // CIA internal cycles
    private long delay; // Delay pipeline
    private long feed;  // Flags fed into the pipeline at each tick

    private int counterA, counterB; // 16-bit timers
    private int latchA, latchB;     // 16-bit latches

    /**
     * Numero di underflow del timer B da inizio esecuzione. Hook di sola lettura per
     * la regressione sul periodo in modalità cascata (vedi {@code CiaCascadeTimerTest}
     * e la nota nel timer B di {@link #tick()}).
     */
    public long diagTimerBUnderflows;
    private int cra, crb;           // Control registers

    private int imr;    // Interrupt Mask Register (write only mask)
    private int icr;    // Interrupt Control Register (read state / write clear)
    private int icrAck; // ICR bits pending clear

    private int pra, prb;   // Data registers
    private int ddra, ddrb; // Data direction registers

    private int pb67TimerMode; // 0 = port register, 1 = timer mode
    private int pb67TimerOut;  // PB output level when in timer mode
    private int pb67Toggle;    // Internal flip-flop for toggle mode

    private boolean sp, cnt, irq; // Pin state signals

    private int sdr;        // Serial Data Register
    private int ssr;        // Serial Shift Register
    private int serCounter; // Remaining bits in SSR

    private int todCounter;  // 24-bit TOD counter
    private final int[] todHistory = new int[64]; // History for reading latency
    private int todLatch;    // Latch value when TODHI is read
    private int todAlarm;    // Alarm threshold
    private boolean todFrozen;  // Freezes on TODHI read, defreezes on TODLO read
    private boolean todStopped; // Stops on TODHI write, starts on TODLO write
    private boolean todMatching; // Alarm edge detector

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public MOS8520(CIAPinSet pinSet, boolean isCIAA) {
        this.pinSet = pinSet;
        this.isCIAA = isCIAA;
        reset();
    }

    // -------------------------------------------------------------------------
    // Reset
    // -------------------------------------------------------------------------

    public void reset() {
        clock = 0;
        delay = 0;
        feed = 0;

        counterA = 0xFFFF;
        counterB = 0xFFFF;
        latchA = 0xFFFF;
        latchB = 0xFFFF;
        cra = 0;
        crb = 0x00;

        imr = 0;
        icr = 0;
        icrAck = 0;

        pra = 0x00;
        prb = 0x00;
        ddra = 0;
        ddrb = 0;

        pb67TimerMode = 0;
        pb67TimerOut = 0;
        pb67Toggle = 0;

        cnt = true;
        sp = true;
        irq = true;

        sdr = 0;
        ssr = 0;
        serCounter = 0;

        todCounter = 0x000000;
        todLatch = 0;
        todAlarm = 0;
        todFrozen = false;
        todStopped = false; // on real hardware TOD counts immediately after reset
        todMatching = (todCounter == todAlarm);

        // Initialize pin directions and drives
        cachedDdra = cachedDdrb = cachedPortA = cachedPortB = -1;
        specialPinCacheValid = false;
        writeDdrCached(0, 0);
        writeDdrCached(1, 0);
        writePortDataCached(0, 0x00);
        writePortDataCached(1, 0x00);
        setIrqDriveCached(true);
        pinSet.setTimerOutputOverride(0, false, true);
        pinSet.setTimerOutputOverride(1, false, true);
        setCntDirectionCached(false);
        setSpDirectionCached(false);
        setSpDriveCached(true);
        setCntDriveCached(true);
    }

    // ── Timer Helpers ─────────────────────────────────────────────────────────

    private void reloadTimerA() {
        counterA = latchA;
    }

    private void reloadTimerB() {
        counterB = latchB;
    }

    /**
     * Arma l'assertion della linea /IRQ e del bit master dell'ICR <b>un clock di CIA dopo</b>
     * l'evento che li ha causati — stadio 0 del pipeline, che diventa stadio 1 al tick
     * successivo.
     *
     * <p>È la forma di WinUAE, e vale per <b>tutte e quattro</b> le sorgenti dell'ICR, senza
     * deroghe: il timer A/B e il seriale mettono il bit in {@code c->icr2} dentro
     * {@code CIA_update_check}, che lo travasa in {@code c->icr1} in cima al ciclo del clock
     * <b>successivo</b> ({@code c->icr1 |= c->icr2}); FLAG e allarme del TOD passano da
     * {@code CIA_sync_interrupt}, che accoda l'evento a {@code DIV10 + syncdelay}, cioè anche
     * loro un clock di CIA. Solo dopo il travaso {@code RethinkICR} alza {@code 0x80|0x40} e
     * chiama {@code ICR()}, che è ciò che porta giù la linea.
     *
     * <p>Misura (piano §7.1.17): underflow → INTREQ bit 3 vale <b>+6 CCK</b> sull'oracolo, di
     * cui 5 sono questo clock di CIA e 1 è l'evento {@code INTREQ_INT} dal lato di Paula.
     * Prima di questa cura la linea scendeva nello <b>stesso</b> tick dell'evento.
     *
     * <p>⚠️ Quel che <b>non</b> è portato: il bit di <i>sorgente</i> dell'ICR (0x01…0x10)
     * resta visibile a una lettura nel tick dell'evento, mentre in WinUAE è staged anche lui
     * (vive in {@code icr2} fino al travaso). Serve un {@code icr} differito e tocca il
     * pipeline di lettura/ack (CIAReadIcr/CIAClearIcr/CIAAckIcr): non c'è misura che oggi lo
     * chieda, e finché non c'è non si tocca.
     */
    private void triggerIrq() {
        delay |= CIASetInt0 | CIASetIcr0;
    }

    // ── TOD Helpers ───────────────────────────────────────────────────────────

    public void incrementTod() {
        if (todStopped) return;

        // Increment bits using nibble overflow carry
        if (!incTodLoNibble(0)) { checkTodIrq(); return; }
        if (todBug) checkTodIrq();

        if (!incTodHiNibble(0)) { checkTodIrq(); return; }
        if (!incTodLoNibble(1)) { checkTodIrq(); return; }
        if (todBug) checkTodIrq();

        if (!incTodHiNibble(1)) { checkTodIrq(); return; }
        if (!incTodLoNibble(2)) { checkTodIrq(); return; }
        incTodHiNibble(2);

        checkTodIrq();
    }

    private boolean incTodLoNibble(int byteIdx) {
        int shift = byteIdx * 8;
        int val = (todCounter >> shift) & 0xFF;
        if ((val & 0x0F) < 0x0F) {
            val += 0x01;
            todCounter = (todCounter & ~(0xFF << shift)) | (val << shift);
            return false;
        } else {
            val &= 0xF0;
            todCounter = (todCounter & ~(0xFF << shift)) | (val << shift);
            return true;
        }
    }

    private boolean incTodHiNibble(int byteIdx) {
        int shift = byteIdx * 8;
        int val = (todCounter >> shift) & 0xFF;
        if ((val & 0xF0) < 0xF0) {
            val += 0x10;
            todCounter = (todCounter & ~(0xFF << shift)) | (val << shift);
            return false;
        } else {
            val &= 0x0F;
            todCounter = (todCounter & ~(0xFF << shift)) | (val << shift);
            return true;
        }
    }

    private void checkTodIrq() {
        if (!todMatching && todCounter == todAlarm) {
            icr |= 0x04; // TOD source bit — ROM reads this to identify the interrupt
            if ((imr & 0x04) != 0) triggerIrq();
        }
        todMatching = (todCounter == todAlarm);
    }

    // ── I/O Port Logic ───────────────────────────────────────────────────────

    private void updatePA() {
        int drivenPra = pra;
        if (!isCIAA) {
            // CIA-B Port A: RS232 /RTS (bit 6) and /DTR (bit 7) are active low
            // Invert them if they are configured as output
            int mask = (ddra & 0xC0);
            drivenPra ^= mask;
        }
        writePortDataCached(0, drivenPra);
    }

    private void updatePB() {
        int drivenPrb = prb;
        if ((pb67TimerMode & 0x40) != 0) {
            drivenPrb = (drivenPrb & ~0x40) | (pb67TimerOut & 0x40);
        }
        if ((pb67TimerMode & 0x80) != 0) {
            drivenPrb = (drivenPrb & ~0x80) | (pb67TimerOut & 0x80);
        }
        writePortDataCached(1, drivenPrb);
    }

    /**
     * Rilettura di PRA ({@code $BFE001} / {@code $BFD000}).
     *
     * <p>⛔ <b>Sul 8520 la DDR non vale per tutti i bit.</b> Onorarla ovunque — cioè
     * restituire il valore emesso per ogni pin configurato come uscita — è il
     * comportamento della variante <b>391078-01</b>, che l'oracolo tiene dietro
     * {@code currprefs.cs_ciatype[0]} e che la macchina di questo banco non attiva
     * ({@code cia.cpp}, {@code ReadCIAA} case 0):
     *
     * <pre>
     * v  = DISK_status_ciaa() &amp; 0x3c;            // bit 2..5: SEMPRE dal drive
     * v |= handle_joystick_buttons(pra, dra);    // bit 6..7: pulsanti, ma vedi sotto
     * v |= (pra | (dra ^ 3)) &amp; 0x03;             // bit 0..1: questi SI' seguono la DDR
     * </pre>
     *
     * I bit 2÷5 (/CHNG, /WPRO, /TK0, /RDY) tornano il livello della linea anche a pin
     * configurato in uscita. I bit 6÷7 sono <b>open collector</b>: il pulsante li tiene
     * alti, ma un'uscita che pilota 0 vince — è la riga
     * {@code if ((dra &amp; mask) &amp;&amp; !(pra &amp; mask)) but &amp;= ~mask;} di
     * {@code handle_joystick_buttons} (inputdevice.cpp). Solo i bit 0÷1 (OVL, LED) sono
     * uscite vere, e da input leggono 1.
     *
     * <p>Su <b>CIA-B</b> la porta A è invece la formula piena
     * {@code (pra &amp; dra) | (dra ^ 0xFF)}: da input legge 1, non un livello esterno.
     *
     * <p>Misurato da {@code CIA/CIA/ciareg}: {@code aprab3} scrive {@code DDRA=$FF} e
     * {@code PRA=$00}, e l'oracolo torna comunque alte le quattro linee del drive
     * mentre noi tornavamo 0. {@code aprab4}, che scrive lo stesso {@code DDRA=$FF} ma
     * {@code PRA=$FE}, coincideva <i>per combinazione</i>. Vedi
     * {@code docs/vamigats-import-order.md} §Esiti {@code CIA/CIA}.
     */
    private int computePA() {
        final int ext = pinSet.readExternal(0);
        if (!isCIAA) {
            return ((pra & ddra) | (ddra ^ 0xFF)) & 0xFF;
        }
        int v = ext & 0x3C;
        // Pulsanti: alti se non premuti, ma un'uscita che pilota 0 li tira giu' lo stesso.
        v |= (ext & 0xC0) & ~(ddra & ~pra);
        v |= (pra | (ddra ^ 0x03)) & 0x03;
        return v & 0xFF;
    }

    /**
     * Rilettura di PRB ({@code $BFE101} / {@code $BFD100}).
     *
     * <p>Le due porte B non hanno la stessa regola nell'oracolo:
     * <ul>
     *   <li><b>CIA-A</b>: {@code (prb & drb) | (drb ^ 0xFF)} — da input legge 1, che è
     *       la porta parallela che flotta alta;</li>
     *   <li>⛔ <b>CIA-B</b>: {@code tmp = c->prb} e basta — <b>la DDR è ignorata del
     *       tutto</b>, la rilettura torna il registro di uscita anche per i bit
     *       configurati come ingresso ({@code cia.cpp}, {@code ReadCIAB} case 1).</li>
     * </ul>
     * Poi entrambe passano da {@code CIA_PBON}, che <b>dopo</b> la maschera di direzione
     * forza PB6/PB7 all'uscita del timer quando il bit PBON del registro di controllo è
     * attivo. Noi lo ripiegavamo <i>prima</i>, dentro il valore pilotato
     * ({@link #updatePB}), dove la DDR poteva ancora mascherarlo.
     *
     * <p>Misurato da {@code CIA/CIA/ciareg}: {@code bprab1} azzera PRB e DDRB di CIA-B e
     * l'oracolo rilegge {@code $00} dove noi rileggevamo {@code $FF} — otto bit su otto.
     */
    private int computePB() {
        int v = isCIAA ? ((prb & ddrb) | (ddrb ^ 0xFF)) : prb;
        if ((pb67TimerMode & 0x40) != 0) {
            v = (v & ~0x40) | (pb67TimerOut & 0x40);
        }
        if ((pb67TimerMode & 0x80) != 0) {
            v = (v & ~0x80) | (pb67TimerOut & 0x80);
        }
        return v & 0xFF;
    }

    private void writeDdrCached(int portIdx, int value) {
        value &= 0xFF;
        if (portIdx == 0) {
            if (cachedDdra == value) return;
            cachedDdra = value;
        } else {
            if (cachedDdrb == value) return;
            cachedDdrb = value;
        }
        pinSet.writeDDR(portIdx, (byte) value);
    }

    private void writePortDataCached(int portIdx, int value) {
        value &= 0xFF;
        if (portIdx == 0) {
            if (cachedPortA == value) return;
            cachedPortA = value;
        } else {
            if (cachedPortB == value) return;
            cachedPortB = value;
        }
        pinSet.writePortData(portIdx, (byte) value);
    }

    private void setIrqDriveCached(boolean value) {
        if (specialPinCacheValid && cachedIrqDrive == value) return;
        cachedIrqDrive = value;
        pinSet.getIrqPin().setInternalDrive(value);
    }

    private void setCntDirectionCached(boolean value) {
        if (specialPinCacheValid && cachedCntDirection == value) return;
        cachedCntDirection = value;
        pinSet.getCntPin().setDirection(value);
    }

    private void setCntDriveCached(boolean value) {
        if (specialPinCacheValid && cachedCntDrive == value) return;
        cachedCntDrive = value;
        pinSet.getCntPin().setInternalDrive(value);
    }

    private void setSpDirectionCached(boolean value) {
        if (specialPinCacheValid && cachedSpDirection == value) return;
        cachedSpDirection = value;
        pinSet.getSpPin().setDirection(value);
    }

    private void setSpDriveCached(boolean value) {
        if (specialPinCacheValid && cachedSpDrive == value) return;
        cachedSpDrive = value;
        pinSet.getSpPin().setInternalDrive(value);
        specialPinCacheValid = true;
    }

    /**
     * Lettura interna side-effect free del Port B.
     * A differenza di {@link #readRegister(int)}, non attraversa la logica dei
     * registri CIA e non modifica latch, pipeline o interrupt.
     */
    public byte peekPortB() {
        return (byte) computePB();
    }

    public byte peekDataDirectionA() {
        return (byte) ddra;
    }

    // ── External Pin Events ──────────────────────────────────────────────────

    public void emulateFallingEdgeOnFlagPin() {
        icr |= 0x10;
        if ((imr & 0x10) != 0) {
            triggerIrq();
        }
    }

    public void emulateRisingEdgeOnCntPin() {
        cnt = true;
        countOnCntEdge();

        // ── Registro a scorrimento in INGRESSO ────────────────────────────────
        // ⛔ Il travaso SSR → SDR e' IMMEDIATO, non ritardato di tre clock.
        // Passava per il pipeline `CIASsrToSdr0→3`, e il pipeline era anche
        // *cancellato* dal passaggio TX↔RX (`case 0xE`): in `ser/ser6`, che scorre
        // quattro bit, commuta CRA a SP-uscita e torna in ingresso, il travaso
        // dell'ottavo fronte veniva annullato dalla commutazione che arriva un paio
        // di clock dopo, e SDR non si aggiornava mai. L'oracolo assegna sul posto
        // (`c->sdr = c->sdr_buf` in `write_ciab_serial`, cia.cpp) e di pipeline non
        // ne ha nessuno. ⚠️ L'INTERRUPT invece resta ritardato (`CIASerInt0`): quello
        // l'oracolo lo passa a `CIA_sync_interrupt`, che non e' immediato.
        if ((cra & 0x40) == 0) {
            if (serCounter == 0) serCounter = 8;
            ssr = ((ssr << 1) | (sp ? 1 : 0)) & 0xFF;
            if (--serCounter == 0) {
                sdr = ssr;
                delay |= CIASerInt0;
            }
        }
    }

    public void emulateFallingEdgeOnCntPin() {
        cnt = false;
    }

    /**
     * Conteggio dei fronti di CNT: avviene <b>dentro il fronte</b>, non nel pipeline.
     *
     * <p>⛔ Prima il fronte si limitava a iniettare {@code CIACountA1}/{@code CIACountB1}
     * nel pipeline di Lorenz: il controllo d'underflow (che chiede {@code CIACountx2})
     * cadeva allora <b>due clock di CIA dopo</b> il fronte, e il decremento tre. Il conto
     * dei fronti era giusto — l'underflow arriva comunque all'ennesimo fronte — ma
     * <b>l'istante</b> no. ⚠️ Misurato sul banco: {@code cnt/cnt1} (timer A sui fronti,
     * IRQ sull'underflow di A) aveva la banda dell'handler in ritardo di 48 pixel = 12 CCK
     * e {@code oldcnt/cnt4} (timer B sui fronti) di 36 pixel = 9 — cioè due clock di CIA
     * (10 CCK) quantizzati dal confine d'istruzione a cui la CPU prende l'interrupt.
     * Il riferimento è a 4 pixel per CCK.
     *
     * <p>L'oracolo conta <b>nell'accesso</b>: {@code WriteCIAB} → {@code write_ciab_serial}
     * → {@code cia_inmode_cnt} (cia.cpp), che decrementa e ricarica sul posto, senza
     * passare da {@code inputpipe}. Questo metodo ne è la traduzione, ordine compreso:
     * prima A, e se A va in underflow ed è B a contarne gli underflow ({@code INMODE=1x})
     * il decremento di B avviene <b>nello stesso fronte</b>; B in modo CNT
     * ({@code INMODE=01}) conta comunque, e le due strade danno <b>un solo</b> decremento
     * di B, non due.
     *
     * <p>⚠️ La cascata da un timer A pilotato dal <b>clock di sistema</b> resta invece nel
     * pipeline ({@code delay |= CIACountB1} in {@link #tick()}): è la stessa asimmetria
     * dell'oracolo, che lì passa da {@code inputpipe |= CIA_PIPE_INPUT}.
     */
    private void countOnCntEdge() {
        boolean decB = false;

        // Timer A: INMODE=1 (fronti di CNT) e START.
        if ((cra & 0x21) == 0x21) {
            if (counterA == 0) {
                timerAUnderflowOnCntEdge();
                // B conta gli underflow di A (INMODE=10 o 11): sul fronte la condizione
                // «CNT alto» dell'INMODE=11 è vera per costruzione.
                if ((crb & 0x41) == 0x41) decB = true;
            } else {
                counterA = (counterA - 1) & 0xFFFF;
            }
        }
        // Timer B: INMODE=01 (fronti di CNT) e START.
        if ((crb & 0x61) == 0x21) decB = true;

        if (decB) {
            if (counterB == 0) timerBUnderflowOnCntEdge();
            else counterB = (counterB - 1) & 0xFFFF;
        }
    }

    /**
     * Underflow del timer A provocato da un fronte di CNT. Fa le stesse cose del blocco
     * {@code timerAOutput} di {@link #tick()}, ma <b>subito</b>: il ricarico dal latch è
     * immediato (l'oracolo lo fa in {@code timer_reset}) e cancella l'eventuale decremento
     * già in canna, che è il {@code inputpipe &= ~CIA_PIPE_CLR1} dell'oracolo.
     *
     * <p>La generazione del clock seriale non c'è perché non può esserci: con
     * {@code CRA} bit 6 a 1 il pin CNT è un'<b>uscita</b> e nessun fronte esterno arriva
     * qui — {@link #updatePins()} chiama questa strada solo a bit 6 azzerato.
     */
    private void timerAUnderflowOnCntEdge() {
        icrAck &= ~0x01;
        if (((delay | feed) & CIAOneShotA0) != 0) {
            cra &= ~0x01;
            delay &= ~(CIACountA2 | CIACountA1 | CIACountA0);
            feed &= ~CIACountA0;
        }
        delay &= ~CIACountA3;
        reloadTimerA();
        pb67Toggle ^= 0x40;
        if ((cra & 0x02) != 0) {
            if ((cra & 0x04) == 0) {
                pb67TimerOut |= 0x40;
                delay |= CIAPB6Low0;
                delay &= ~CIAPB6Low1;
            } else {
                pb67TimerOut ^= 0x40;
            }
        }
        icr |= 0x01;
        if ((imr & 0x01) != 0) triggerIrq();
    }

    /** Gemello di {@link #timerAUnderflowOnCntEdge()} per il timer B. */
    private void timerBUnderflowOnCntEdge() {
        icrAck &= ~0x02;
        if (((delay | feed) & CIAOneShotB0) != 0) {
            crb &= ~0x01;
            delay &= ~(CIACountB2 | CIACountB1 | CIACountB0);
            feed &= ~CIACountB0;
        }
        delay &= ~CIACountB3;
        reloadTimerB();
        diagTimerBUnderflows++;
        pb67Toggle ^= 0x80;
        if ((crb & 0x02) != 0) {
            if ((crb & 0x04) == 0) {
                pb67TimerOut |= 0x80;
                delay |= CIAPB7Low0;
                delay &= ~CIAPB7Low1;
            } else {
                pb67TimerOut ^= 0x80;
            }
        }
        icr |= 0x02;
        if ((imr & 0x02) != 0) triggerIrq();
    }

    /**
     * Executes one CIA clock cycle (~1 MHz).
     *
     * The delay pipeline 'delay' manages the propagation of events.
     * Transitions are derived from the Lorenz hardware model.
     */
    public void tick() {
        clock++;
        todHistory[(int)(clock & 0x3F)] = todCounter;

        // ── Timer A Pipeline Processing ───────────────────────────────────────

        // (0) Un caricamento dal latch cancella il decremento del PROPRIO clock.
        // È la regola di WinUAE `t->inputpipe &= ~CIA_PIPE_CLR1` (cia.cpp): il bit di
        // uscita del pipe di conteggio viene azzerato sia dal caricamento schedulato da
        // una scrittura (`t->loaddelay`, blocco «CIA special cases») sia dal ricarico
        // che segue un underflow in modo continuo (blocco `ovfl`). Vale per tutti e due
        // i timer e per tutte le modalità: non è una deroga.
        // Vedi la nota estesa sul timer B e docs/vamigats-paula-audio-plan.md §7.1.16.
        boolean loadA = (delay & (CIALoadA1 | CIALoadA2)) != 0;
        if (loadA) {
            delay &= ~CIACountA3;
        }

        // (1) Decrement counter.
        if ((delay & CIACountA3) != 0) {
            counterA = (counterA - 1) & 0xFFFF;
        }

        // (2) Underflow logic
        boolean timerAOutput = (counterA == 0 && (delay & CIACountA2) != 0);
        if (timerAOutput) {
            icrAck &= ~0x01;
            // Stop in one-shot mode
            if (((delay | feed) & CIAOneShotA0) != 0) {
                cra &= ~0x01;
                delay &= ~(CIACountA2 | CIACountA1 | CIACountA0);
                feed &= ~CIACountA0;
            }
            // Cascade to Timer B
            if (((crb & 0x61) == 0x41) || ((crb & 0x61) == 0x61 && cnt)) {
                delay |= CIACountB1;
            }
            delay |= CIALoadA1;
        }
        // (4) Delayed reload. Due stadi, come i due bit `0x00000001 << 1` e
        // `<< 2` che WinUAE arma insieme quando il timer è fermo: il caricamento
        // avviene al clock 2 E al clock 3 dopo la scrittura.
        if ((delay & (CIALoadA1 | CIALoadA2)) != 0) reloadTimerA();

        // ── Timer B Pipeline Processing ───────────────────────────────────────

        // ⛔ Qui NON c'è (più) un ritardo d'avvio aggiuntivo. Dal 2026-06 al 2026-08 il
        // codice aspettava 8 tick di E-clock in più prima del primo decremento
        // (TIMER_START_DELAY): era nato nello stesso commit del detune 19/200 dei timer
        // CIA, messo per far quadrare il test "New Test CIAs" di DiagROM. Il detune è
        // stato revocato a favore del rate hardware CLK/10 (memoria
        // cia-timer-eclock-5pct) e la costante è rimasta indietro, senza più il suo
        // gemello. Non ha nessun corrispondente né in WinUAE (che al posto suo ha i due
        // caricamenti di `t->loaddelay`, cia.cpp) né sull'8520, dove il ritardo fra la
        // scrittura di CRA/CRB e il primo decremento è quello del pipeline
        // CIACountB0→B1→B2→B3 qui sotto più i caricamenti.
        //
        // ⚠️ Costava 8 E-clock a OGNI armamento, cioè lo 0,012% su un timer da 65 536
        // tick ma l'8% su uno da 100 — ed è esattamente il bug che il ritardo aveva
        // reso visibile: Dragon's Lair (latchA=7160, latchB=100, CRB=$49) usa la
        // CASCATA (INMODE=10) come sveglia e verifica che l'attesa non superi 53 frame
        // ($003D1E: SUB.b #$35,D0); con gli 8 tick contati sugli EVENTI di conteggio
        // invece che sul clock, i primi 8 underflow del timer A venivano mangiati e i
        // 51 frame nominali diventavano 56. `CiaCascadeTimerTest` è la regressione, e
        // senza il ritardo l'asserzione è soddisfatta a maggior ragione.
        //
        // Il ritardo d'avvio è quello del pipeline `CIALoadB0→B1→B2` più la regola che
        // un caricamento cancella il conteggio del proprio clock (vedi il timer A).
        // Misurato contro l'oracolo (piano §7.1.16, armamento di timer.device a
        // $FE94AC): il primo decremento cade al clock **4** dopo la scrittura di TBHI,
        // perché il latch viene caricato ai clock 2 e 3 e ognuno dei due toglie il
        // decremento del proprio clock. Con un caricamento solo cadeva al clock 3.
        boolean loadB = (delay & (CIALoadB1 | CIALoadB2)) != 0;
        if (loadB) {
            delay &= ~CIACountB3;
        }
        if ((delay & CIACountB3) != 0) {
            counterB = (counterB - 1) & 0xFFFF;
        }

        boolean timerBOutput = (counterB == 0 && (delay & CIACountB2) != 0);
        if (timerBOutput) {
            icrAck &= ~0x02;
            if (((delay | feed) & CIAOneShotB0) != 0) {
                crb &= ~0x01;
                delay &= ~(CIACountB2 | CIACountB1 | CIACountB0);
                feed &= ~CIACountB0;
            }
            delay |= CIALoadB1;
        }
        if ((delay & (CIALoadB1 | CIALoadB2)) != 0) reloadTimerB();

        // ── Serial Port Processing ───────────────────────────────────────────

        // Clock generation for Serial Output (using Timer A)
        if (timerAOutput && (cra & 0x40) != 0) {
            if (serCounter > 0) {
                feed ^= CIASerClk0;
            } else if ((delay & CIASdrToSsr1) != 0) {
                ssr = sdr;
                delay &= ~(CIASdrToSsr1 | CIASdrToSsr0);
                feed &= ~CIASdrToSsr0;
                serCounter = 8;
                feed ^= CIASerClk0;
            }
        }
        // Shifting logic
        if (serCounter > 0 && (cra & 0x40) != 0) {
            if ((delay & (CIASerClk2 | CIASerClk1)) == CIASerClk1) {
                if (serCounter == 1) delay |= CIASerInt0;
            } else if ((delay & (CIASerClk2 | CIASerClk1)) == CIASerClk2) {
                serCounter--;
                // Amiga KDAT is active low: Logical 1 = Voltage LOW (false)
                sp = (ssr & 0x80) == 0;
                ssr = (ssr << 1) & 0xFF;
            }
        }

        // ── PB6/PB7 Pin Output ───────────────────────────────────────────────

        if (timerAOutput) {
            pb67Toggle ^= 0x40;
            if ((cra & 0x02) != 0) {
                if ((cra & 0x04) == 0) {
                    pb67TimerOut |= 0x40;
                    delay |= CIAPB6Low0;
                    delay &= ~CIAPB6Low1;
                } else {
                    pb67TimerOut ^= 0x40;
                }
            }
        }
        if (timerBOutput) {
            pb67Toggle ^= 0x80;
            if ((crb & 0x02) != 0) {
                if ((crb & 0x04) == 0) {
                    pb67TimerOut |= 0x80;
                    delay |= CIAPB7Low0;
                    delay &= ~CIAPB7Low1;
                } else {
                    pb67TimerOut ^= 0x80;
                }
            }
        }
        // End of pulse duration
        if ((delay & CIAPB6Low1) != 0) { pb67TimerOut &= ~0x40; }
        if ((delay & CIAPB7Low1) != 0) { pb67TimerOut &= ~0x80; }

        // ── Interrupt Logic ─────────────────────────────────────────────────

        if (timerAOutput) { icr |= 0x01; }
        if (timerBOutput) { icr |= 0x02; diagTimerBUnderflows++; }
        if ((timerAOutput && (imr & 0x01) != 0) || (timerBOutput && (imr & 0x02) != 0)) {
            triggerIrq();
        }
        if ((delay & CIATODInt0) != 0) {
            icr |= 0x04;
            if ((imr & 0x04) != 0) triggerIrq();
        }
        if ((delay & CIASerInt2) != 0) {
            icr |= 0x08;
            if ((imr & 0x08) != 0) triggerIrq();
        }
        // Scheduled ICR status clearing and IRQ line release
        if ((delay & (CIAClearIcr1 | CIAAckIcr1 | CIASetIcr1 | CIASetInt1 | CIAClearInt0)) != 0) {
            if ((delay & CIAClearIcr1) != 0) { icr &= 0x7F; }
            if ((delay & CIAAckIcr1) != 0) { icr &= ~icrAck; }
            if ((delay & CIASetIcr1) != 0) { icr |= 0x80; }
            if ((delay & CIASetInt1) != 0) { irq = false; }
            if ((delay & CIAClearInt0) != 0) { irq = true; }
        }

        // Shift pipeline and update pin sync
        delay = ((delay << 1) & CIADelayMask) | feed;
        updatePins();
    }

    /**
     * Collegamenti interni della sola CIA-B: PA0 &rarr; SP, PA1 &rarr; CNT.
     *
     * <p>⛔ Il livello e' quello EFFETTIVAMENTE PILOTATO, {@code pra & ddra}: un pin
     * configurato come ingresso non pilota niente, e prendere il solo {@code pra} fa
     * comparire fronti di CNT che sulla scheda non esistono. L'oracolo apre
     * {@code write_ciab_serial()} con {@code uae_u8 val = ndata & ndir;} e confronta il
     * vecchio livello nello stesso modo ({@code (odata & odir) & 2}), cia.cpp.
     *
     * <p>⛔ <b>Va valutato alla SCRITTURA, non al tick successivo.</b> In WinUAE
     * {@code write_ciab_serial()} e' chiamata da {@code WriteCIAB} dentro l'accesso, e
     * il fronte di CNT conta il timer li' ({@code cia_inmode_cnt}). Rimandarlo al tick
     * successivo sposta il conteggio di un clock di CIA rispetto all'oracolo, e con
     * {@code cnt/} il conteggio E' la misura.
     *
     * <p>⛔ <b>PA0 aggiorna anche {@link #sp}, non solo il pin.</b> Il registro a
     * scorrimento in ingresso legge {@code sp} al fronte di CNT, e {@code sp} veniva
     * riletto dal pin <b>solo dentro {@link #updatePins()}</b>, cioè al tick — mentre il
     * fronte di CNT arriva qui, dentro l'accesso. Sul percorso della scrittura il livello
     * era quindi quello vecchio: fermo a {@code true} dal reset, il che faceva entrare
     * otto 1 e leggere {@code SDR = $FF} dove l'oracolo legge il dato vero.
     * ⚠️ Misurato: {@code ser/ser1} riceve {@code $A5} (la sequenza 1,0,1,0,0,1,0,1
     * scritta dal test), noi {@code $FF} — quattro barre su otto sbagliate, e lo stesso in
     * {@code ser2÷4}, {@code ser6}, {@code ser7}. L'oracolo scorre {@code (val & 1)} del
     * livello pilotato, senza inversioni ({@code write_ciab_serial}, cia.cpp).
     */
    private void updateCiabSerialLinks() {
        final int drivenPa = pra & ddra;
        boolean pa0 = (drivenPa & 0x01) != 0;
        boolean pa1 = (drivenPa & 0x02) != 0;
        setSpDriveCached(pa0);
        sp = pa0;
        if (pa1 != cnt) {
            if (pa1) emulateRisingEdgeOnCntPin();
            else emulateFallingEdgeOnCntPin();
        }
    }

    /** Updates the physical pin set based on internal state. */
    private void updatePins() {
        writeDdrCached(0, ddra);
        writeDdrCached(1, ddrb);

        updatePA();
        updatePB();
        boolean specialPinsUpdated = pinSet.updateAll();
        setIrqDriveCached(irq);

        if ((cra & 0x40) != 0) {
            // CNT in output mode: HIGH when idle (serCounter==0), follows SerClk when transmitting.
            // Keeping CNT HIGH when idle prevents a spurious rising edge on the next
            // input-mode tick that would corrupt the subsequent byte reception.
            boolean cntLevel = (serCounter > 0) ? ((feed & CIASerClk0) != 0) : true;
            setCntDirectionCached(true);
            setCntDriveCached(cntLevel);
            setSpDirectionCached(true);
            setSpDriveCached(sp);
        } else {
            setCntDirectionCached(false);
            setSpDirectionCached(false);

            if (!isCIAA) {
                updateCiabSerialLinks();
            } else {
                setCntDriveCached(true);
                setSpDriveCached(true);
            }

            if (specialPinsUpdated) {
                if (pinSet.getCntPin().isRisingEdge()) {
                    emulateRisingEdgeOnCntPin();
                } else if (pinSet.getCntPin().isFallingEdge()) {
                    emulateFallingEdgeOnCntPin();
                }
                this.sp = pinSet.getSpPin().getPinState();
            }
        }
        if (specialPinsUpdated) {
            if (pinSet.getFlagPin().isFallingEdge()) emulateFallingEdgeOnFlagPin();
            if (pinSet.getTodPin().isRisingEdge()) incrementTod();
        }
    }

    // -------------------------------------------------------------------------
    // Register Read Access
    // -------------------------------------------------------------------------

    /**
     * Lettura <b>priva di effetti collaterali</b> di un registro CIA, per il debugger e
     * il disassemblatore.
     *
     * <p>⛔ Serve perché {@link #readRegister(int)} muta parecchio, ed è giusto che lo
     * faccia: leggere l'ICR ($D) alza il bit 7, arma il pipeline di clear/ack e
     * <b>rilascia la linea /IRQ</b>; leggere TODHI ($A) congela il latch del TOD e
     * leggere TODLO ($8) lo scongela. Un dump di memoria che attraversi $BFExxx/$BFDxxx
     * con {@code readRegister} cancella quindi un interrupt CIA pendente della macchina
     * che sta osservando. Tutti gli altri registri sono già puri e passano di là.
     */
    public byte peekRegister(int reg) {
        int addr = reg & 0x0F;
        switch (addr) {
            case 0x8: return (byte) getTodLo();
            case 0xA: return (byte) (((crb & 0x80) == 0 && !todFrozen)
                                     ? (todCounter >> 16) & 0xFF
                                     : getTodHi());
            case 0xD: {
                int v = icr;
                if ((delay & CIASetInt1) != 0 && (icr & 0x1F) != 0) v |= 0x80;
                return (byte) v;
            }
            default:  return readRegister(addr);
        }
    }

    public byte readRegister(int reg) {
        int addr = reg & 0x0F;
        int result;

        switch (addr) {
            case 0x0:
                result = computePA();
                break;
            case 0x1:
                result = computePB();
                break;
            case 0x2:
                result = ddra;
                break;
            case 0x3:
                result = ddrb;
                break;
            case 0x4: result = counterA & 0xFF; break;
            case 0x5: result = (counterA >> 8) & 0xFF; break;
            case 0x6: result = counterB & 0xFF; break;
            case 0x7: result = (counterB >> 8) & 0xFF; break;
            case 0x8: result = getTodLo(); todFrozen = false; break;
            case 0x9: result = getTodMid(); break;
            case 0xA:
                if ((crb & 0x80) == 0 && !todFrozen) {
                    todLatch = todCounter;
                    todFrozen = true;
                }
                result = getTodHi();
                break;
            case 0xB: result = 0xFF; break;
            case 0xC:
                result = sdr & 0xFF;
                break;
            case 0xD:
                if ((delay & CIASetInt1) != 0 && (icr & 0x1F) != 0) icr |= 0x80;
                result = icr;
                if (!irq) delay |= CIAClearInt0;
                delay &= ~(CIASetInt0 | CIASetInt1);
                delay |= CIAClearIcr0 | CIAAckIcr0;
                icrAck = result;
                delay |= CIAReadIcr0;
                break;
            case 0xE: result = cra & ~0x90; break;
            case 0xF: result = crb & ~0x10; break;
            default: result = 0;
        }
        return (byte) result;
    }

    private int getTodLo() { return (todFrozen ? todLatch : getLatchedTod()) & 0xFF; }
    private int getTodMid() { return ((todFrozen ? todLatch : getLatchedTod()) >> 8) & 0xFF; }
    private int getTodHi() { return ((todFrozen ? todLatch : getLatchedTod()) >> 16) & 0xFF; }

    private int getLatchedTod() {
        // vAmiga applies 9.5 cycles for CIA-A and 21 for CIA-B
        int offset = isCIAA ? 10 : 21;
        return todHistory[(int)((clock - offset) & 0x3F)];
    }

    // -------------------------------------------------------------------------
    // Register Write Access
    // -------------------------------------------------------------------------

    public void writeRegister(int reg, byte value) {
        int addr = reg & 0x0F;
        int val = value & 0xFF;

        switch (addr) {
            // ⚠️ Porta A e porta B vanno trattate allo stesso modo: il valore che finisce
            // sui pin è quello PILOTATO, non il registro grezzo. Su CIA-B updatePA()
            // inverte i bit 6-7 (/RTS e /DTR, attivi bassi) per i soli pin configurati
            // come uscita, quindi il valore dipende anche da DDRA: scrivere `pra` diretto
            // — e non ricalcolare nulla alla scrittura di DDRA — lasciava il pin set col
            // valore sbagliato fino al tick successivo, che poi correggeva da sé via
            // updatePins(). Finestra breve e su due linee inerti nell'A500, ma era
            // un'asimmetria gratuita rispetto a PRB/DDRB, che la forma giusta ce l'avevano.
            case 0x0:
                pra = val;
                updatePA();
                if (!isCIAA && (cra & 0x40) == 0) updateCiabSerialLinks();
                break;
            case 0x1:
                prb = val;
                updatePB();
                break;
            case 0x2:
                ddra = val;
                writeDdrCached(0, ddra);
                updatePA();
                if (!isCIAA && (cra & 0x40) == 0) updateCiabSerialLinks();
                break;
            case 0x3:
                ddrb = val;
                writeDdrCached(1, ddrb);
                updatePB();
                break;
            case 0x4:
                latchA = (latchA & 0xFF00) | val;
                if ((delay & CIALoadA2) != 0) counterA = (counterA & 0xFF00) | val;
                break;
            case 0x5:
                latchA = (latchA & 0x00FF) | (val << 8);
                if ((delay & CIALoadA2) != 0) counterA = (counterA & 0x00FF) | (val << 8);
                if ((cra & 0x01) == 0) delay |= CIALoadA0;
                // ⛔ Scrivere TAHI su un timer ONE-SHOT ricarica il contatore ANCHE se il
                // timer e' GIA' IN CORSA. Il blocco di avvio automatico qui sotto era
                // condizionato a «fermo», e cosi' la seconda scrittura di TAHI — quella
                // che `Timer/timer5` e `timer5c` fanno apposta a timer avviato — non
                // faceva assolutamente niente. L'oracolo separa le due cose
                // (`CIA_thi_write`, cia.cpp): il caricamento doppio e' condizionato a
                // `!(t->cr & CR_START)`, ma il ramo `if (t->cr & CR_RUNMODE)` — che alza
                // START e arma un altro caricamento — non ha condizioni.
                // ⚠️ `timer5b`, che riscrive il solo TALO e non TAHI, era ed e' verde:
                // e' il banco stesso a isolare la variabile.
                if ((cra & 0x08) != 0) {
                    boolean wasStopped = (cra & 0x01) == 0;
                    if (wasStopped) {
                        pb67Toggle |= 0x40;
                        if ((cra & 0x20) == 0) {
                            delay |= CIACountA1 | CIACountA0;
                            feed |= CIACountA0;
                        }
                        cra |= 0x01;
                    }
                    delay |= CIALoadA0;
                }
                break;
            case 0x6:
                latchB = (latchB & 0xFF00) | val;
                if ((delay & CIALoadB2) != 0) counterB = (counterB & 0xFF00) | val;
                break;
            case 0x7:
                latchB = (latchB & 0x00FF) | (val << 8);
                if ((delay & CIALoadB2) != 0) counterB = (counterB & 0x00FF) | (val << 8);
                if ((crb & 0x01) == 0) delay |= CIALoadB0;
                // Gemello del case 0x5: vedi la nota sul ricarico a timer gia' in corsa.
                if ((crb & 0x08) != 0) {
                    boolean wasStopped = (crb & 0x01) == 0;
                    if (wasStopped) {
                        pb67Toggle |= 0x80;
                        if ((crb & 0x60) == 0) {
                            delay |= CIACountB1 | CIACountB0;
                            feed |= CIACountB0;
                        }
                        crb |= 0x01;
                    }
                    delay |= CIALoadB0;
                }
                break;
            case 0x8:
                if ((crb & 0x80) != 0) todAlarm = (todAlarm & 0xFFFF00) | val;
                else { todCounter = (todCounter & 0xFFFF00) | val; todStopped = false; checkTodIrq(); }
                break;
            case 0x9:
                if ((crb & 0x80) != 0) todAlarm = (todAlarm & 0xFF00FF) | (val << 8);
                else { todCounter = (todCounter & 0xFF00FF) | (val << 8); checkTodIrq(); }
                break;
            case 0xA:
                if ((crb & 0x80) != 0) todAlarm = (todAlarm & 0x00FFFF) | (val << 16);
                else { todCounter = (todCounter & 0x00FFFF) | (val << 16); todStopped = true; checkTodIrq(); }
                break;
            case 0xB: break;
            case 0xC: sdr = val; delay |= CIASdrToSsr0; feed |= CIASdrToSsr0; break;
            case 0xD:
                if ((val & 0x80) != 0) imr |= (val & 0x1F);
                else imr &= ~(val & 0x1F);
                if ((imr & icr & 0x1F) != 0 && irq && (delay & CIAReadIcr1) == 0) delay |= (CIASetInt1 | CIASetIcr1);
                break;
            case 0xE:
                // ⚠️ L'avvio esplicito (START senza LOAD) inietta DUE stadi, non uno.
                // Il pipe dell'oracolo è lungo 2 bit e `CIA_cr_write` non lo tocca
                // affatto quando parte: `process_pipe` ci mette il bit d'ingresso al
                // primo clock dopo la scrittura, lo fa scendere al secondo e lo tira
                // fuori al TERZO, che è quindi il primo decremento. Iniettando il solo
                // stadio 0 il nostro primo decremento cadeva al QUARTO: un clock di CIA
                // (5 CCK, un periodo di E-clock) di ritardo a ogni avvio, che restava
                // addosso a tutti gli
                // underflow successivi. È la stessa preparazione che l'avvio automatico
                // da TAHI (case 0x5) già faceva — `CIACountA1 | CIACountA0` — e che
                // nessuno aveva riportato qui.
                //
                // ⛔ Non tocca l'avvio con LOAD (`$11`, quello di `Timer/cont*`): lì il
                // caricamento dello stadio 2 cancella comunque il decremento del proprio
                // clock, quindi il primo decremento resta al quarto in entrambe le forme.
                // Ecco perché il difetto era invisibile a `Timer` e visibile a `cnt/cnt3`,
                // `ciaa1`/`ciaa2`, `ciab1`/`ciab2`, che avviano con `$01`.
                if ((val & 0x01) != 0) {
                    delay |= CIACountA0 | CIACountA1; feed |= CIACountA0;
                    if ((cra & 0x01) == 0) pb67Toggle |= 0x40;
                } else {
                    delay &= ~(CIACountA1 | CIACountA0); feed &= ~CIACountA0;
                }
                if ((val & 0x02) != 0) {
                    pb67TimerMode |= 0x40;
                    if ((val & 0x04) == 0) {
                        if ((delay & CIAPB7Low1) == 0) pb67TimerOut &= ~0x40;
                        else pb67TimerOut |= 0x40;
                    } else pb67TimerOut = (pb67TimerOut & ~0x40) | (pb67Toggle & 0x40);
                } else pb67TimerMode &= ~0x40;
                if ((val & 0x08) != 0) feed |= CIAOneShotA0; else feed &= ~CIAOneShotA0;
                if ((val & 0x10) != 0) delay |= CIALoadA0;
                if ((val & 0x20) != 0) { delay &= ~(CIACountA1 | CIACountA0); feed &= ~CIACountA0; }
                // ⛔ Commutare TX↔RX azzera anche il REGISTRO, non solo il contatore:
                // `c->sdr_cnt = 0; c->sdr_load = 0; c->sdr_buf = 0;` (`CIA_cr_write`,
                // cia.cpp). E' cio' che `ser/ser6` misura: quattro bit dentro, commuta,
                // quattro bit dentro — e i quattro primi devono sparire.
                if (((val ^ cra) & 0x40) != 0) {
                    if ((val & 0x40) != 0) sp = false; // Amiga ACK: drive line LOW
                    serCounter = 0;
                    ssr = 0;
                    delay &= ~(CIASdrToSsr0 | CIASdrToSsr1);
                    feed &= ~CIASdrToSsr0;
                    delay &= ~(CIASerClk0 | CIASerClk1 | CIASerClk2);
                    feed &= ~CIASerClk0;
                }
                cra = val;
                updatePB();
                break;
            case 0xF:
                // Gemello del case 0xE: vedi la nota sull'avvio a due stadi.
                if ((val & 0x01) != 0) {
                    delay |= CIACountB0 | CIACountB1; feed |= CIACountB0;
                    if ((crb & 0x01) == 0) pb67Toggle |= 0x80;
                } else {
                    delay &= ~(CIACountB1 | CIACountB0); feed &= ~CIACountB0;
                }
                if ((val & 0x02) != 0) {
                    pb67TimerMode |= 0x80;
                    if ((val & 0x04) == 0) {
                        if ((delay & CIAPB7Low1) == 0) pb67TimerOut &= ~0x80;
                        else pb67TimerOut |= 0x80;
                    } else pb67TimerOut = (pb67TimerOut & ~0x80) | (pb67Toggle & 0x80);
                } else pb67TimerMode &= ~0x80;
                if ((val & 0x08) != 0) feed |= CIAOneShotB0; else feed &= ~CIAOneShotB0;
                if ((val & 0x10) != 0) delay |= CIALoadB0;
                if ((val & 0x60) != 0) { delay &= ~(CIACountB1 | CIACountB0); feed &= ~CIACountB0; }
                crb = val;
                updatePB();
                break;
        }
    }

    public CIAPinSet getPinSet() { return pinSet; }
    public int getTimerA() { return counterA & 0xFFFF; }
    public int getTimerB() { return counterB & 0xFFFF; }
    public int getTodCounter() { return todCounter; }
    public boolean isIrqAsserted() { return !irq; }

    /**
     * Stato del pin /LED di CIA-A (PRA bit 1), che pilota il LED di alimentazione e
     * il filtro audio dell'A500: il bit basso accende il LED (luminoso) e attiva il
     * filtro; il bit alto lo lascia in penombra. Significativo solo per CIA-A.
     */
    public boolean isPowerLedOn() { return (pra & 0x02) == 0; }
}
