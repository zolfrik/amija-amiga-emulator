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

import io.github.zolfrik.amija.common.BusRequest;
import io.github.zolfrik.amija.common.BusResponse;

/**
 * Copper (Co-Processor) dell'Amiga 500.
 *
 * Il Copper è un co-processore sincronizzato con il fascio video (beam).
 * Esegue una lista di istruzioni (Copper List) che possono:
 *   1. MOVE: scrivere un valore in un registro custom
 *   2. WAIT: attendere che il beam raggiunga una posizione (V, H)
 *   3. SKIP: saltare l'istruzione successiva se il beam ha già raggiunto (V, H)
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * MODELLO A STATI (Fase 2 del refactoring timing — modello vAmiga)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Questa FSM è il porting diretto di vAmiga (`CopperEvents.cpp`,
 * `Copper.cpp`) sul nostro loop per-CCK. In vAmiga il Copper è un evento
 * (`SLOT_COP`) schedulato con un delay ESPLICITO:
 *   `schedule(next, delay = 2)`  → azione riuscita: prossimo stato a +2 CCK
 *   `reschedule(delay = 1)`      → bus negato: RITENTA a +1 CCK
 * e il vincolo "niente wakeup su CCK dispari" (`IS_ODD(pos.h) → reschedule`)
 * esiste SOLO negli stati di wakeup (`COP_REQ_DMA`/`COP_WAKEUP`/`COP_WAKEUP_BLIT`),
 * in NESSUN altro: `COP_FETCH` &c. non hanno alcun vincolo di parità.
 *
 * Qui il Copper è tickato da {@link AbstractAgnus} a OGNI CCK e riceve `evenCck`;
 * la cadenza +2 è resa esplicita da {@link #tickDelay}. La cadenza pari dei fetch
 * è quindi un'EMERGENZA del delay 2, NON una regola: si rompe di proposito quando
 * un diniego ($E0/refresh/DMA) inserisce un +1.
 *
 * ⚠️ FIX #23: fino al 2026-07-17 il Copper era tickato SOLO sui CCK pari (gate
 * `(cck & 1) == 0`, Fase 1). Era troppo forte: un fetch rifiutato dal BLOCKED di
 * $E0=224 slittava al pari successivo (226=refresh → 0 della riga dopo) invece di
 * ritentare a 225, rubando 2 CCK alla CPU. Root di §S2 in
 * docs/vamigats-cpu-timing-plan.md (provata con l'oracolo `[BUS]` di vAmiga).
 *
 * Mappatura stati vAmiga → nostri:
 *   COP_REQ_DMA        → REQ_DMA        (dopo restart/COPJMP/VBlank: attende slot → FETCH)
 *   COP_FETCH          → FETCH          (legge IR1, avanza PC, → MOVE o WAIT_OR_SKIP)
 *   COP_MOVE           → MOVE           (legge IR2, esegue MOVE/COPJMP/illegal)
 *   COP_WAIT_OR_SKIP   → WAIT_OR_SKIP   (legge IR2, → WAIT1 o SKIP1)
 *   COP_WAIT1          → WAIT1          (ciclo idle bus-gated)
 *   COP_WAIT2          → WAIT2          (valuta: match immediato → FETCH, altrimenti → WAKEUP)
 *   COP_WAKEUP(_BLIT)  → WAKEUP         (polling del comparatore ad ogni CCK pari libero)
 *   COP_SKIP1/2        → SKIP1/SKIP2    (cicli idle bus-gated)
 *   COP_JMP1/2         → JMP1/JMP2      (COPJMP interno via MOVE a $88/$8A)
 *   COP_VBLANK         → onVBlank()     (restart da COP1LC a inizio frame)
 *   cancel<SLOT_COP>   → DORMANT        (Copper fermo: MOVE illegale, fino a VBlank/COPJMP)
 *
 * Il WAIT non usa uno scheduler assoluto: il polling del comparatore (stato
 * WAKEUP) è equivalente al `scheduleWaitWakeup` di vAmiga (che salta alla prima
 * posizione di match e ri-verifica): entrambi si svegliano al primo CCK PARI con
 * bus libero in cui il comparatore è vero (il WAKEUP è uno stato di wakeup, quindi
 * il vincolo di parità vale).
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * COMPARATORE (vAmiga runComparator / runHorizontalComparator)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 *   waitpos = IR1 & 0xFFFE                 (VPHP; bit0=0)
 *   mask    = (IR2 & 0x7FFE) | 0x8001      (VMHM; VP7 e bit0 forzati a 1)
 *
 *   Verticale:  bvHi = vpos & (mask>>8);  wpHi = (waitpos & mask) >> 8
 *     bvHi <  wpHi → false
 *     bvHi >  wpHi → true  (il blitter è comunque valutato dal gate finale)
 *     bvHi == wpHi → confronto orizzontale
 *   Orizzontale: bh = hpos>>1 (CCK)
 *     hRaw = (bh < 0xE0) ? bh+2 : bh-0xE0        ← offset +2 con wrap sugli
 *                                                  ultimi 3 cicli di riga
 *     beamReady = (hRaw & (mask&0xFF)) >= ((waitpos & 0xFF) & (mask&0xFF))
 *
 * L'offset +2 e il modello a pipeline (FETCH→WAIT_OR_SKIP→WAIT1→WAIT2 prima
 * della prima valutazione) fanno funzionare l'idioma di attesa oltre la riga
 * 256 ("WAIT VP=255,HP alto" + "WAIT VP basso") SENZA alcuna guardia ad-hoc:
 * quando WAIT2 valuta, il fascio ha già superato il confine di riga e vpos è
 * wrappato a 256 (bvHi=0 < target). Il comparatore verticale a 8 bit
 * (vpos & mask con mask≤0xFF) realizza il wrap automaticamente.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * BFD (bit 15 di IR2)
 * ═══════════════════════════════════════════════════════════════════════════
 *   1 → ignora lo stato del Blitter.
 *   0 → attendi anche il completamento del Blitter (gate finale sempre valutato).
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * PROTEZIONE REGISTRI
 * ═══════════════════════════════════════════════════════════════════════════
 *   $000–$03F: sempre vietati (OCS: anche con CDANG; ECS: liberi con CDANG=1)
 *   $040–$07F: vietati se CDANG=0
 * Una MOVE a registro vietato ferma il Copper (DORMANT), come vAmiga
 * (`cancel<SLOT_COP>`), SAE (COP_stop) e WinUAE (copper_enabled_thisline=0).
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * PC-FOLLOWING (C7)
 * ═══════════════════════════════════════════════════════════════════════════
 * Una scrittura di COP1LC/COP2LC aggiorna SUBITO il PC solo quando il Copper
 * non è ancora attivo nel frame (`!activeInThisFrame`) e la lista scritta è
 * quella corrente. `activeInThisFrame` è settato da onVBlank() (con copper DMA
 * on) e dalla riaccensione di COPEN (setDmaEnabled(true)). Quando il Copper è
 * attivo, le scritture di COPxLC non toccano il PC (servono COPJMP o il VBlank
 * per ricaricarlo): questo è il comportamento vAmiga (`CopperRegs.cpp`).
 *
 * @author alessio.carotenuto
 */
public final class Copper {

    // =========================================================================
    // Dipendenze
    // =========================================================================

    private final BusRequest  busReq;
    private final BusResponse busResp;
    private final AbstractAgnus agnus;

    // =========================================================================
    // Registri
    // =========================================================================

    /** COP1LC — puntatore alla prima Copper List (20 bit, forzato pari). */
    private int cop1lc = 0;

    /** COP2LC — puntatore alla seconda Copper List. */
    private int cop2lc = 0;

    /** COPCON — bit 1 (CDANG): 1 = il Copper può accedere ai registri $040–$07F. */
    private int copcon = 0;

    /** Program Counter interno: prossima word da leggere nella Copper List. */
    private int pc = 0;

    /** Indice della Copper List corrente (1 o 2). */
    private int copList = 1;

    // =========================================================================
    // Registro istruzioni
    // =========================================================================

    /** IR1 (cop1ins): prima word dell'istruzione (address o VPHP). */
    private int ir1 = 0;

    /** IR2 (cop2ins): seconda word dell'istruzione (data o VMHM/BFD). */
    private int ir2 = 0;

    // =========================================================================
    // Stato
    // =========================================================================

    /** CDANG: true → il Copper può accedere ai registri $040–$07F. */
    private boolean danger = false;

    /**
     * Flag `skip` (vAmiga): true se l'istruzione corrente va saltata perché la
     * precedente era una SKIP con condizione soddisfatta. Sopprime solo la
     * scrittura di una MOVE (i fetch avvengono comunque). Valutato al FETCH
     * dell'istruzione successiva alla SKIP; azzerato in MOVE (se applicato) e
     * in WAIT2.
     */
    private boolean skip = false;

    /**
     * Flag diagnostico: true se il Copper ha tentato una MOVE a un registro
     * vietato. Azzerato a ogni restart/VBlank.
     */
    private boolean forbiddenMoveDetected = false;

    /**
     * true se il Copper è stato attivo nel frame corrente (vAmiga
     * `activeInThisFrame`). Governa il PC-following (C7). Settato da onVBlank()
     * con copper DMA on e da setDmaEnabled(true); azzerato da onVBlank() con
     * copper DMA off.
     */
    private boolean activeInThisFrame = false;

    /** Copia locale del bit COPEN (per rilevare il fronte OFF→ON). */
    private boolean dmaEnabled = false;

    /** Lista target di un COPJMP interno del Copper (MOVE a $88/$8A). */
    private int pendingList = 1;

    /** Indirizzo/valore della MOVE in corso (per debugger/save state). */
    private int pendingWriteAddr = 0;
    private int pendingWriteData = 0;

    // =========================================================================
    // State machine (modello vAmiga)
    // =========================================================================

    public enum State {
        DORMANT,        // Copper fermo (MOVE illegale / non ancora avviato) — cancel<SLOT_COP>
        STROBE,         // "Initial DMA request" post-strobe (WinUAE): slot ALLOCATO, dato scartato
        REQ_DMA,        // COP_REQ_DMA: attende slot pari libero → FETCH
        FETCH,          // COP_FETCH: legge IR1
        MOVE,           // COP_MOVE: legge IR2 + esegue MOVE
        WAIT_OR_SKIP,   // COP_WAIT_OR_SKIP: legge IR2 dell'istruzione WAIT/SKIP
        WAIT1,          // COP_WAIT1: ciclo idle
        WAIT2,          // COP_WAIT2: valuta condizione
        WAKEUP,         // COP_WAKEUP: polling del comparatore
        SKIP1,          // COP_SKIP1: ciclo idle
        SKIP2,          // COP_SKIP2: ciclo idle
        JMP1,           // COP_JMP1: COPJMP interno (1° ciclo)
        JMP2            // COP_JMP2: COPJMP interno (2° ciclo) → switch list
    }

    private State state = State.DORMANT;

    /**
     * CCK ancora da saltare prima della prossima azione della FSM — traduzione
     * esplicita di `schedule(next, delay)` / `reschedule(delay)` di vAmiga
     * (Copper.h: `schedule(EventID, int delay = 2)`, `reschedule(int delay = 1)`).
     *
     * <p>Il Copper viene ora tickato a OGNI CCK (prima solo sui pari). La cadenza:
     * <ul>
     *   <li><b>azione riuscita</b> → `tickDelay = 1` = prossima azione a <b>+2</b> CCK
     *       (vAmiga `schedule(..., 2)`): è la cadenza normale, che di norma cade sui pari;</li>
     *   <li><b>diniego del bus</b> → `tickDelay = 0` = ritentata a <b>+1</b> CCK,
     *       <b>qualunque parità</b> (vAmiga `reschedule(..., 1)`).</li>
     * </ul>
     *
     * <p>La cadenza pari dei fetch è quindi un'EMERGENZA del delay 2, <b>non una regola</b>:
     * si rompe di proposito quando un diniego ($E0/refresh/DMA) inserisce un +1. Il vincolo
     * di parità vero vale SOLO sugli stati di wakeup (vedi {@link #risingEdge}).
     */
    private int tickDelay = 0;

    // =========================================================================
    // Costruttore
    // =========================================================================

    public Copper(BusRequest busReq, BusResponse busResp, AbstractAgnus agnus) {
        this.busReq  = busReq;
        this.busResp = busResp;
        this.agnus   = agnus;
        reset();
    }

    // =========================================================================
    // Reset
    // =========================================================================

    public void reset() {
        state                 = State.DORMANT;
        cop1lc                = 0;
        cop2lc                = 0;
        copcon                = 0;
        pc                    = 0;
        copList               = 1;
        ir1                   = 0;
        ir2                   = 0;
        danger                = false;
        skip                  = false;
        forbiddenMoveDetected = false;
        activeInThisFrame     = false;
        dmaEnabled            = false;
        pendingList           = 1;
        pendingWriteAddr      = 0;
        pendingWriteData      = 0;
        busReq.clear();
    }

    // =========================================================================
    // Scrittura registri (dalla CPU o da DMA)
    // =========================================================================

    /**
     * Aggiorna i registri Copper in risposta a una scrittura sul bus custom.
     *
     * COP1LCH/L e COP2LCH/L: aggiornano i puntatori LC; il PC segue solo se il
     *   Copper non è attivo nel frame e la lista è quella corrente (C7).
     * COPJMP1/COPJMP2: strobe — riavviano il Copper dalla lista relativa.
     * COPCON: aggiorna CDANG.
     */
    public void writeRegister(int address, int value) {
        address &= 0x1FE;

        switch (address) {

            // ── COP1LC ──────────────────────────────────────────────────────
            case 0x080: // COP1LCH — bit 19-16
                cop1lc = (cop1lc & 0x0000FFFF) | ((value << 16) & agnus.getDmaPtrHiMask());
                if (!activeInThisFrame && copList == 1) setPC(cop1lc);
                break;

            case 0x082: // COP1LCL — bit 15-0 (forzato pari)
                cop1lc = (cop1lc & agnus.getDmaPtrHiMask()) | (value & 0xFFFE);
                if (!activeInThisFrame && copList == 1) setPC(cop1lc);
                if (io.github.zolfrik.amija.custom.AbstractDenise.DBG_COLOR)
                    io.github.zolfrik.amija.custom.AbstractDenise.DBG_C00_VPOS.merge(
                            "COP1LC=" + Integer.toHexString(cop1lc & 0xFFFFF), 1L, Long::sum);
                break;

            // ── COP2LC ──────────────────────────────────────────────────────
            case 0x084: // COP2LCH — bit 19-16
                cop2lc = (cop2lc & 0x0000FFFF) | ((value << 16) & agnus.getDmaPtrHiMask());
                if (!activeInThisFrame && copList == 2) setPC(cop2lc);
                break;

            case 0x086: // COP2LCL — bit 15-0 (forzato pari)
                cop2lc = (cop2lc & agnus.getDmaPtrHiMask()) | (value & 0xFFFE);
                if (!activeInThisFrame && copList == 2) setPC(cop2lc);
                break;

            // ── COPJMP (strobe dalla CPU) ───────────────────────────────────
            case 0x088: // COPJMP1 — riavvia dalla lista 1
                copList = 1;
                strobeCopperList(cop1lc);
                break;

            case 0x08A: // COPJMP2 — riavvia dalla lista 2
                copList = 2;
                strobeCopperList(cop2lc);
                break;

            // ── COPCON ──────────────────────────────────────────────────────
            case 0x02E: // COPCON — bit 1 = CDANG
                copcon = value & 0x0002;
                danger = (copcon & 0x0002) != 0;
                break;
        }
    }

    /** Imposta il PC del Copper (vAmiga setPC). */
    private void setPC(int address) {
        pc = address & (agnus.getChipMask() & ~1);
    }

    /**
     * Strobe COPJMP dalla CPU (vAmiga switchToCopperList via pokeCOPJMPx<CPU>):
     * carica il PC dalla lista e schedula COP_REQ_DMA. NON tocca
     * activeInThisFrame (come vAmiga): un COPJMP avviene di norma a Copper già
     * attivo nel frame.
     */
    private void strobeCopperList(int address) {
        setPC(address);
        state                 = State.REQ_DMA;
        skip                  = false;
        forbiddenMoveDetected = false;
        busReq.clear();
    }

    /**
     * Chiamato da Agnus a ogni VBlank (vAmiga COP_VBLANK / eofHandler): il
     * Copper riparte SEMPRE dalla lista 1 con PC=COP1LC. activeInThisFrame
     * riflette lo stato del DMA Copper (copdma()). Se il DMA è spento lo stato
     * resta comunque FETCH ma l'arbitraggio non ticka il Copper (busIsFree
     * negato) finché COPEN non torna on → pure resume.
     */
    public void onVBlank(boolean copperEnabled) {
        copList           = 1;
        setPC(cop1lc);
        activeInThisFrame = copperEnabled;
        skip              = false;
        forbiddenMoveDetected = false;
        // Restart via strobe: prima del primo fetch reale c'è lo slot allocato
        // "Initial DMA request" (vedi State.STROBE). NB: i COPJMP (interni JMP1/2
        // e strobe CPU) restano sul modello vAmiga senza slot extra — da misurare.
        state             = State.STROBE;
        busReq.clear();
    }

    /**
     * Chiamato da Agnus ad ogni cambio del bit COPEN in DMACON.
     *
     * COPEN off/on = pure resume (vAmiga): il Copper mantiene stato/PC e riprende
     * dove si era fermato. La riaccensione NON riavvia la lista (nessun FIX #16):
     * l'unico effetto è marcare activeInThisFrame=true (vAmiga setCOPEN(true)).
     * Il "tick" del Copper è gated dall'arbitraggio su isCopperEnabled(): a COPEN
     * off il Copper semplicemente non viene tickato (equivale al reschedule di
     * vAmiga quando busIsFree&lt;COPPER&gt; nega il bus a DMA off).
     */
    public void setDmaEnabled(boolean enabled) {
        boolean old = this.dmaEnabled;
        this.dmaEnabled = enabled;
        if (enabled) {
            // vAmiga Agnus::setCOPEN(true): copper.activeInThisFrame = true.
            activeInThisFrame = true;
        } else if (old) {
            busReq.clear();
        }
    }

    // =========================================================================
    // Tick — fronti di clock (chiamati solo sui CCK pari da AbstractAgnus)
    // =========================================================================

    /**
     * Fronte di salita: serve l'evento corrente (una transizione di stato).
     *
     * @param busFree true se il bus è libero per il Copper in questo CCK
     *                (nessun DMA a priorità superiore e non è il ciclo $E0).
     *                Equivale a vAmiga `busIsFree&lt;COPPER&gt;()`.
     *
     * Gli stati di lettura memoria (FETCH/MOVE/WAIT_OR_SKIP) assertano la
     * richiesta bus: la concessione è decisa dall'arbitraggio di Agnus e la
     * word letta viene consumata in {@link #fallingEdge()}. Gli stati idle
     * (REQ_DMA/WAIT1/WAIT2/WAKEUP/SKIP1/SKIP2/JMP*) avanzano qui in base a
     * busFree (nessuna transazione bus).
     */
    /**
     * Fronte di salita, chiamato a OGNI CCK.
     *
     * @param busFree  slot libero per il Copper (nessun DMA a priorità superiore e non $E0)
     * @param evenCck  CCK pari — vincola SOLO gli stati di wakeup (vAmiga `IS_ODD(pos.h)`
     *                 è testato in `COP_REQ_DMA`/`COP_WAKEUP`/`COP_WAKEUP_BLIT` e in
     *                 NESSUN altro stato: `COP_FETCH` &c. non hanno vincolo di parità)
     */
    public void risingEdge(boolean busFree, boolean evenCck) {
        if (state == State.DORMANT) return;

        // schedule/reschedule di vAmiga: salta i CCK di attesa.
        if (tickDelay > 0) {
            tickDelay--;
            return;
        }

        switch (state) {

            case DORMANT:
                return;

            case REQ_DMA:
                // COP_REQ_DMA. Diniego → reschedule(+1). h dispari → reschedule(+1)
                // ("Don't wake up in an odd cycle"). Altrimenti schedule(COP_FETCH) = +2.
                if (!busFree) return;
                if (!evenCck) return;
                state = State.FETCH;
                tickDelay = 1;
                return;

            case STROBE:
                // "Initial DMA request" dopo lo strobe di restart (WinUAE
                // generate_copper: `if (cop_state.strobe) { if (ena_odd)
                // alloc_copper_cycle(); }`): uno slot copper ALLOCATO (blocca la
                // CPU) prima del primo fetch reale, solo su CCK pari; il dato
                // fetchato è scartato e il PC non avanza. Misurato sul restart
                // VBL di bbusy0: blocco di v0 = 33 slot (1 strobe + 16 istruzioni),
                // il nostro vecchio restart diretto in FETCH ne faceva 32 → CPU
                // libera 1 slot presto → fase synccpu −1 (banda top).
                if (!evenCck) return;
                requestFetch();
                return;

            case WAKEUP:
                // COP_WAKEUP: primo slot libero PARI in cui il comparatore (beam +
                // blitter) è vero → riprende il fetch.
                if (!busFree) return;
                if (!evenCck) return;
                if (isConditionMet()) {
                    state = State.FETCH;
                    tickDelay = 1;
                }
                return;

            case WAIT1:
                if (!busFree) return;
                state = State.WAIT2;
                tickDelay = 1;
                return;

            case WAIT2:
                // vAmiga COP_WAIT2: azzera skip, poi (bus libero) valuta.
                skip = false;
                if (!busFree) return;
                if (isConditionMet()) {
                    // delay==0 in scheduleWaitWakeup → COP_FETCH diretto.
                    state = State.FETCH;
                } else {
                    // Altrimenti attende (polling nel WAKEUP).
                    state = State.WAKEUP;
                }
                tickDelay = 1;
                return;

            case SKIP1:
                if (!busFree) return;
                state = State.SKIP2;
                tickDelay = 1;
                return;

            case SKIP2:
                if (!busFree) return;
                state = State.FETCH;
                tickDelay = 1;
                return;

            case JMP1:
                // COP_JMP1: il ciclo è allocato al Copper ma non serve un fetch.
                // (Il quirk $E0 → schedule(COP_JMP2, 1) di vAmiga resta trascurato.)
                state = State.JMP2;
                tickDelay = 1;
                return;

            case JMP2:
                if (!busFree) return;
                copList = pendingList;
                setPC(pendingList == 1 ? cop1lc : cop2lc);
                state = State.FETCH;
                tickDelay = 1;
                return;

            case FETCH:
            case MOVE:
            case WAIT_OR_SKIP:
                // Stati di lettura memoria: assertano la richiesta bus. Nessun vincolo di
                // parità. Se il grant non arriva (bus occupato / $E0), `tickDelay` resta 0
                // → si ri-asserta al CCK SUCCESSIVO (reschedule +1), anche se DISPARI.
                // Il +2 fra due fetch riusciti lo mette fallingEdge().
                requestFetch();
                return;
        }
    }

    /**
     * Fronte di discesa: consuma la word letta e avanza gli stati di lettura.
     * Gli altri stati sono già avanzati in {@link #risingEdge(boolean)}.
     */
    public void fallingEdge() {
        if (state == State.STROBE || state == State.FETCH || state == State.MOVE
                || state == State.WAIT_OR_SKIP) {
            if (busResp.ack && busReq.as && busReq.read) {
                int data = busResp.dataIn & 0xFFFF;
                switch (state) {
                    case STROBE:       state = State.FETCH; break; // dato scartato, PC fermo
                    case FETCH:        onFetch(data);      break;
                    case MOVE:         onMove(data);       break;
                    case WAIT_OR_SKIP: onWaitOrSkip(data); break;
                    default: break;
                }
                // Ciclo bus RIUSCITO: lo stato successivo parte a +2 CCK
                // (vAmiga `schedule(COP_FETCH/COP_MOVE/...)` con delay 2 di default).
                tickDelay = 1;
            }
            // Se NON c'è stato ack (bus occupato / $E0), lo stato resta invariato e
            // `tickDelay` resta 0: si ritenta al CCK SUCCESSIVO, anche dispari
            // (vAmiga `reschedule(1)`). È il caso del BLOCKED di $E0=224, dove vAmiga
            // rifà il fetch a 225: col vecchio tick solo-pari slittavamo a 226/0
            // rubando 2 CCK alla CPU (root di §S2, docs/vamigats-cpu-timing-plan.md).
        }
        busReq.clear();
    }

    // =========================================================================
    // Richiesta bus
    // =========================================================================

    private void requestFetch() {
        busReq.address   = pc;
        busReq.read      = true;
        busReq.as        = true;
        busReq.uds       = true;
        busReq.lds       = true;
        busReq.sizeBytes = 2;
    }

    // =========================================================================
    // Handler degli stati di lettura
    // =========================================================================

    /** COP_FETCH: valuta il flag skip (istruzione precedente), legge IR1. */
    private void onFetch(int data) {
        // Se l'istruzione ATTUALMENTE nei registri era una SKIP, il flag skip
        // va calcolato ora, alla posizione di fetch dell'istruzione seguente
        // (vAmiga COP_FETCH: `if (isSkipCmd()) skip = runComparator() [&blit]`).
        if (isSkipCmd()) {
            skip = isConditionMet();
        }

        ir1 = data;
        pc += 2;

        // Fork sul tipo di istruzione: MOVE (IR1 pari) oppure WAIT/SKIP.
        state = isMoveCmd() ? State.MOVE : State.WAIT_OR_SKIP;
    }

    /** COP_MOVE: legge IR2 ed esegue la MOVE (o COPJMP / illegal). */
    private void onMove(int data) {
        ir2 = data;
        pc += 2;

        int reg = ir1 & 0x1FE;

        // Registro illegale → il Copper si ferma (vAmiga cancel<SLOT_COP>).
        if (isIllegalAddress(reg)) {
            forbiddenMoveDetected = true;
            state = State.DORMANT;
            return;
        }

        // Prossima istruzione per default.
        state = State.FETCH;

        // Se questa MOVE era saltata da una SKIP precedente: sopprime la
        // scrittura (i fetch sono già avvenuti). vAmiga: `if (skip){skip=false;break;}`.
        if (skip) {
            skip = false;
            return;
        }

        // COPJMP interno (MOVE a $88/$8A): sequenza JMP1/JMP2.
        if (reg == 0x088) { pendingList = 1; state = State.JMP1; return; }
        if (reg == 0x08A) { pendingList = 2; state = State.JMP1; return; }

        // Scrittura del registro custom (interna ad Agnus, nessun ciclo bus extra).
        pendingWriteAddr = reg;
        pendingWriteData = ir2;
        agnus.copperWrite(reg, ir2);
    }

    /** COP_WAIT_OR_SKIP: legge IR2, poi fork WAIT1 / SKIP1. */
    private void onWaitOrSkip(int data) {
        ir2 = data;
        pc += 2;
        if (isWaitCmd()) {
            state = State.WAIT1;
        } else {
            state = State.SKIP1;
        }
    }

    // =========================================================================
    // Decodifica tipo istruzione
    // =========================================================================

    private boolean isMoveCmd() { return (ir1 & 1) == 0; }
    private boolean isWaitCmd() { return (ir1 & 1) != 0 && (ir2 & 1) == 0; }
    private boolean isSkipCmd() { return (ir1 & 1) != 0 && (ir2 & 1) != 0; }

    /**
     * true se l'indirizzo è vietato al Copper (vAmiga isIllegalAddress):
     *   OCS: CDANG → addr<$40 vietato; !CDANG → addr<$80 vietato.
     *   ECS: CDANG → nulla vietato; !CDANG → addr<$80 vietato.
     */
    private boolean isIllegalAddress(int addr) {
        if (agnus.isOCS()) {
            return danger ? addr < 0x040 : addr < 0x080;
        } else {
            return !danger && addr < 0x080;
        }
    }

    // =========================================================================
    // Comparatore WAIT / SKIP (vAmiga runComparator)
    // =========================================================================

    /** Comparatore beam (verticale + orizzontale con offset +2 e wrap $E0). */
    private boolean beamComparator() {
        int waitpos = ir1 & 0xFFFE;              // VPHP
        int mask    = (ir2 & 0x7FFE) | 0x8001;   // VMHM (VP7 e bit0 forzati)

        // ── Verticale ────────────────────────────────────────────────────────
        int hi   = (mask >> 8) & 0xFF;           // 0x80 | VM
        int bvHi = agnus.getVpos() & hi;         // vpos & mask (wrap 8-bit implicito)
        int wpHi = ((waitpos & mask) >> 8) & 0xFF;
        if (bvHi < wpHi) return false;
        if (bvHi > wpHi) return true;

        // ── Orizzontale (bvHi == wpHi) ────────────────────────────────────────
        int lo   = mask & 0xFF;                  // (HM) | 0x01
        int bh   = (agnus.getHpos() >> 1) & 0xFF; // beam.h in CCK
        int hRaw = (bh < 0xE0) ? (bh + 0x02) : (bh - 0xE0);
        return (hRaw & lo) >= ((waitpos & 0xFF) & lo);
    }

    /** Comparatore completo: beam AND (BFD OR blitter terminato). */
    private boolean isConditionMet() {
        boolean beamReady    = beamComparator();
        boolean bfd          = (ir2 & 0x8000) != 0;
        boolean blitterReady = bfd || agnus.isBlitterFinished();
        return beamReady && blitterReady;
    }

    // =========================================================================
    // Accessori pubblici
    // =========================================================================

    public State   getState()            { return state; }
    public int     getPc()               { return pc; }
    public int     getIr1()              { return ir1; }
    public int     getIr2()              { return ir2; }
    public boolean isWaiting()           { return state == State.WAKEUP
                                                  || state == State.WAIT1
                                                  || state == State.WAIT2; }
    public boolean isDanger()            { return danger; }
    public boolean isActiveInThisFrame() { return activeInThisFrame; }

    // Getter necessari per debugger e save state
    public int getCop1lc()           { return cop1lc; }
    public int getCop2lc()           { return cop2lc; }
    public int getCopcon()           { return copcon; }
    public int getCopList()          { return copList; }
    public int getPendingWriteAddr() { return pendingWriteAddr; }
    public int getPendingWriteData() { return pendingWriteData; }

    public boolean isForbiddenMoveDetected()    { return forbiddenMoveDetected; }
    public void    clearForbiddenMoveDetected() { forbiddenMoveDetected = false; }
}
