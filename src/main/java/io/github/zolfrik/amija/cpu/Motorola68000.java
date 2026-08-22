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

package io.github.zolfrik.amija.cpu;

import io.github.zolfrik.amija.common.BusRequest;
import io.github.zolfrik.amija.common.BusResponse;
import java.util.Arrays;
import java.util.Objects;

public final class Motorola68000 {

    public boolean quirksMode = false;

    private final int[] d = new int[8];
    private final int[] a = new int[8];
    int usp, ssp;
    int pc;
    int opcodeAddress;
    int extensionsConsumed;
    int sr;
    public boolean x, n, z, v, c;

    int irc, ird;
    int ircAddr, irdAddr;
    boolean ircValid, irdValid;
    int ir;
    int currentOpcode;
    private boolean prefetchPending;
    private int pendingPrefetchAddr;

    private boolean inInstructionBoundary;
    private boolean tracePending;
    int internalCyclesRemaining;

    // Overlapped-finalize seam (docs/cpu-overlapped-prefetch-rework-plan.md §Phase 8): when an
    // instruction finishes with the bus IDLE and the prefetch queue already refilled (the
    // trailing-internal-idle group), the next instruction decodes in the SAME mc instead of
    // burning a dead boundary mc (+1 seam). The single-instruction JSON harness sets this
    // false — one instruction in isolation has no next instruction to overlap, and its own
    // transactions are unchanged either way.
    public boolean seamOverlapEnabled = true;
    private int resetSignalCyclesRemaining = 0;

    private long totalCycles = 0;
    /**
     * Fase dell'E-clock (&phi;2/10) che governa la macchina {@code S_VMA}.
     *
     * <p>⛔ <b>Parte da 9, non da 0, e il master-cycle di differenza è una FASE, non un
     * ritardo.</b> Sul 68000 l'E-clock è generato dal processore stesso dividendo CLK per
     * 10, e in WinUAE la sua griglia è ricavata dallo <b>stesso</b> contatore del chip-bus:
     * {@code cia.cpp} misura tutto in {@code E_CYCLE_UNIT = CYCLE_UNIT / 2} (mezzo CCK) e le
     * sue costanti sono <b>tutte pari</b> — {@code E_CLOCK_SYNC_N 2} (= 1 CCK),
     * {@code START 4} (= 2 CCK), {@code END 6} (= 3 CCK), {@code DIV10 = 5 · CYCLE_UNIT}
     * (= 5 CCK). Un ciclo sincrono comincia e finisce quindi <b>sempre</b> su un fronte di
     * CCK, e dura un numero pari di master-cycle.
     *
     * <p>Con la partenza a 0 la fase 2 cadeva su un master-cycle di parità sbagliata
     * rispetto alla griglia del chip-bus: il ciclo sincrono durava <b>15</b> master-cycle
     * (mezzo CCK, una durata che l'hardware non può produrre) invece di 16, e il prefetch
     * che lo segue ne pagava uno di riallineamento (5 invece di 4). I due errori si
     * compensano quando il bus è libero — per questo il totale di una {@code MOVE.L} da
     * spazio CIA restava giusto — ma non quando c'è contesa DMA: quel master-cycle sposta
     * l'accesso in uno slot diverso. Misurato con la sonda {@code WINUAE_CIASYNCLOG} contro
     * il nostro {@code BUSLOG} su vAmigaTS {@code Memory/Ram/memtim1}: le tre regioni CIA
     * passano da +620/+80/+60 pixel a <b>bit-exact, iterazione per iterazione</b>.
     *
     * <p>⛔ <b>Va insieme all'ancora di {@code SLOT_CIAA}</b> in {@code AbstractAgnus}
     * ({@code masterClock + 9}): sul silicio è un filo solo, e muovere un contatore senza
     * l'altro riapre il doppione chiuso il 2026-08-21. L'invariante «la CIA ticka a
     * {@code eClockCounter == 0}» è congelata da {@code CiaEClockPhaseTest}.
     * Vedi {@code docs/vamigats-import-order.md} §Esiti {@code memtim1}.
     */
    private int eClockCounter = 9;
    int lastFinishedPC = 0;
    private int waitCycles = 0;

    // --- Boot/compatibility diagnostic counters (cheap, sempre attivi) ---
    // Indice = vettore d'eccezione
    // (tracciati: 2=bus error, 3=address error, 4=illegal, 8=privilege, 9=trace,
    // 10=line-A, 11=line-F; gli altri <16 includono i TRAP/CHK/TRAPV).
    public final long[] diagExceptionCounts = new long[16];
    public long diagInstrCount = 0;             // istruzioni completate nell'intervallo
    public int  diagPcMin = Integer.MAX_VALUE;  // finestra dei PC eseguiti (loop detection)
    public int  diagPcMax = Integer.MIN_VALUE;
    public int  getIrd() { return ird; }

    public void resetDiagCounters() {
        java.util.Arrays.fill(diagExceptionCounts, 0L);
        diagInstrCount = 0;
        diagPcMin = Integer.MAX_VALUE;
        diagPcMax = Integer.MIN_VALUE;
    }

    AbstractM68kInstruction currentInstruction;
    private int instructionStep = 0;
    private boolean risingEdgeConsumed = false;
    private final AbstractM68kInstruction[] decodeTable = new AbstractM68kInstruction[65536];
    private final int[] tempRegs = new int[32];

    private final BusRequest busReq;
    private final BusResponse busResp;
    private int latchedBusData;
    private int latchedByte;
    private int latchedLongHigh, latchedLongLow;
    private boolean longReadHighValid;

    private static final int FC_USER_DATA = 0b001;
    private static final int FC_USER_PROGRAM = 0b010;
    private static final int FC_SUPERVISOR_DATA = 0b101;
    private static final int FC_SUPERVISOR_PROGRAM = 0b110;
    private static final int FC_CPU_SPACE = 0b111;

    public enum CpuState { NORMAL, STOPPED, HALTED, EXCEPTION, RESET }
    public enum BusCycleState { IDLE, S0, S1, S2, S3, S4, S_VMA, S5, S6, S7 }

    CpuState cpuState = CpuState.HALTED;
    public CpuState getCpuState() { return cpuState; }
    BusCycleState busCycleState = BusCycleState.IDLE;
    BusOp currentBusOp = null;
    private boolean busLocked = false;
    private static final int BUS_QUEUE_SIZE = 16;
    private final BusOp[] busQueue = new BusOp[BUS_QUEUE_SIZE];
    private int busQueueHead;
    private int busQueueTail;
    private int busQueueSize;

    /**
     * STEP 1 (PERFORMANCE_REPORT_FINAL): pool circolare di BusOp pre-allocati.
     *
     * {@link #createOp} riusa questi oggetti invece di allocare un nuovo BusOp
     * ad ogni accesso al bus (~1.8M/s), eliminando la pressione GC nel core CPU.
     *
     * Dimensione 32 (potenza di 2) = ampio margine di sicurezza: la busQueue non
     * supera ~4 op vive contemporaneamente, perché le istruzioni schedulano una
     * sola op alla volta attendendo il bus idle, e il batch massimo schedulato in
     * un colpo è una long read/write = 2 op. L'indice viene mascherato con
     * {@code & (SIZE-1)}, quindi anche l'overflow di {@code busOpPoolHead} resta
     * un indice valido 0..31. Ogni BusOp viene completamente reinizializzato da
     * createOp prima dell'uso, quindi il riuso è trasparente al comportamento. */
    private static final int BUS_OP_POOL_SIZE = 32;
    private final BusOp[] busOpPool = new BusOp[BUS_OP_POOL_SIZE];
    private int busOpPoolHead = 0;

    enum InternalTask { NONE, RESET_SEQ, EXCEPTION_GROUP0, EXCEPTION_GROUP1_2, EXCEPTION_INTERRUPT, TRACE_EXCEPTION }

    InternalTask currentMicroTask = InternalTask.NONE;
    private int microTaskStep = 0;
    private int exceptionVector, exceptionPC, faultAddress, faultIR, faultFC, tempResetSSP, tempResetPC, tempExceptionVectorHigh, savedSR, latchedIpl = 0, acknowledgedIpl = 0;
    private int flushCount = 0;

    // POLL_IPL (Moira, MoiraMacros.h `#define POLL_IPL reg.ipl = ipl`): la CPU non
    // decide l'interrupt sul pin IPL LIVE, ma su un valore campionato a un punto
    // FISSO dentro l'istruzione — l'ultima prefetch (~2 CCK prima del boundary,
    // `fullPrefetch<POLL>`) — e usato al boundary successivo. Il nostro modello
    // latchava IPL a OGNI fallingEdge (latchedIpl) e lo usava al boundary, quindi
    // riconosceva un edge arrivato DOPO il punto POLL un'istruzione troppo presto
    // (residuo marker dei test vAmigaTS: IRQ preso 1 iterazione `bra.s` in anticipo).
    // Campiniamo polledIpl all'S0 di ogni prefetch (INSTRUCTION_FETCH) e checkInterrupts
    // lo usa nel path NORMAL. È il modello hardware corretto: rende il cycle-trace CPU
    // bit-identico sui test vAmigaTS (NOP 0.70→0.38%, tutti i marker cycle-exact).
    // Il campionamento avviene all'S0 del ciclo bus ancora (iplPollState = 0).
    // Moira campiona all'avvio (S0); WinUAE — che è l'oracolo — lo fa nella PARTE
    // DATI del ciclo: gencpu.cpp check_ipl, "IPL fetch happens when CPU is doing
    // memory cycle data part followed by prefetch cycle ... after possible bus error
    // has been detected but before following prefetch memory cycle".
    private static final int IPL_POLL_STATE = 0;
    private int polledIpl = 0;

    // ── Regola WinUAE `ipl_fetch_next` (newcpu.cpp) ────────────────────────────
    // WinUAE non usa il pin nudo al punto di POLL: confronta l'ANZIANITÀ del cambio
    // di pin con due soglie (cpuipldelay4 = 4 clock, cpuipldelay2 = 2 clock):
    //   cd  >= 4  → ipl[0] = pin        (interrupt preso a fine ISTRUZIONE CORRENTE)
    //   cdp >= 2  → ipl[0] = pin_prev   (il cambio è troppo fresco: si ignora)
    //   altrimenti → ipl[1] = pin       (DIFFERITO all'istruzione SUCCESSIVA)
    // e a ogni ritiro di istruzione fa `ipl[0] = ipl[1]; ipl[1] = 0`.
    // Le soglie sono misurate dall'ISTANTE DEL CAMBIO DI PIN, quindi dipendono dalla
    // fase del nostro pin IPL rispetto a quello di WinUAE (la nostra pipe IPL è un
    // port di vAmiga: 3 stadi da 1 CCK + poke-delay).
    private static final int IPL_DELAY4 = 4;
    private static final int IPL_DELAY2 = 2;
    // ── Selezione CONDIZIONALE now/next (WinUAE gencpu.cpp ~9498) ─────────────
    // WinUAE non applica sempre le soglie di `ipl_fetch_next`: sceglie quale delle due
    // funzioni emettere in base alla distanza fra il punto di POLL e la fine
    // dell'istruzione, in clock CPU:
    //     if (tc - ipl_fetch_cycles > 4 || ipl_fetched == 3) ipl_fetch_now();
    //     else                                               ipl_fetch_next();
    // cioè: POLL lontano dalla fine → campionamento incondizionato del pin; POLL a
    // <= 4 clock dalla fine (tipicamente: l'ancora È la prefetch finale, perché
    // l'istruzione non ha accessi dati) → soglie 4/2 + differimento a ipl[1].
    // In WinUAE è una decisione di CODEGEN, presa sui cicli NOMINALI; a runtime la
    // distanza POLL→boundary si conosce solo al boundary, quindi al POLL registriamo
    // ENTRAMBI i candidati e la scelta si fa al boundary (resolveIplAtBoundary).
    // Criterio adottato (2026-07-31): STRUTTURALE, indipendente dalla contention —
    // ancora = prefetch → next, ancora = accesso dati → now (l'ancora dati è per
    // costruzione seguita dalla prefetch finale, quindi dista 8 clock dalla fine).
    // ── Ri-campionamento del pin durante i WAIT-STATE del bus (WinUAE) ─────────
    // `wait_cpu_cycle_read/write` (custom.cpp) salva `now = get_cycles()` PRIMA di
    // attendere il bus e, alla fine dell'accesso, fa:
    //     if (now == regs.ipl_evt) regs.ipl[0] = ipl;   // "CPU had wait states"
    // dove `ipl` è aggiornato dentro `dma_cycle()` a ogni CCK bloccato
    // (`*ipl = regs.ipl_pin; do_cck(true);`). Cioè: se il POLL è ancorato a QUESTO
    // accesso (`ipl_fetch_now()` emesso subito prima, quindi `ipl_evt == now`) e il
    // chip bus era occupato, il valore campionato NON è quello dell'istante in cui
    // la CPU era pronta, ma quello dell'ULTIMO CCK di attesa. Senza contention
    // l'override è un no-op (il ciclo `while` non gira).
    // Noi campionavamo sempre all'S0 = istante "pronta": sotto contention di
    // bitplane vedevamo il pin ~1 CCK troppo presto e perdevamo l'edge, prendendo
    // l'interrupt un'istruzione dopo. Criterio adottato (2026-07-31): si usa il pin
    // dell'ULTIMO master-cycle di attesa.
    // ── Ancora dell'IPL in coda all'INGRESSO IN ECCEZIONE (WinUAE Exception_ce000) ──
    // WinUAE, in fondo a `Exception_ce000` (newcpu.cpp ~2937), emette `ipl_fetch_next()`
    // DOPO la prima prefetch di refill e i suoi 2 clock idle, cioè 4 clock prima della
    // fine dell'entry:
    //     regs.ir  = x_get_word(m68k_getpc());        // prefetch 1
    //     x_do_cycles(2 * cpucycleunit);
    //     if (m68k_accurate_ipl) ipl_fetch_next();    // <-- qui
    //     regs.irc = x_get_word(m68k_getpc() + 2);    // prefetch 2
    // Noi invece lasciavamo l'ancora sull'ultimo ACCESSO DATI dell'entry (la lettura
    // del vettore), che con `iplcond=2` ha semantica `now`: campionamento
    // INCONDIZIONATO del pin ~14 clock prima della fine, invece delle soglie 4/2
    // applicate a 4 clock dalla fine. Dal 2026-07-31 l'ancora è `ipl_fetch_next()`
    // in coda all'entry, come WinUAE.
    private boolean iplAnchorIsCurrentOp = false, iplAnchorHadWait = false;
    /**
     * Su quale campione scrive l'override dei wait-state: il CANDIDATO di
     * {@code check_ipl()} (accesso dati del percorso di default) oppure l'ancora già
     * decisa (dichiarazione {@code ipl_fetch_now()} nello stesso ciclo dell'accesso).
     */
    private boolean iplWaitOnCandidate = false;
    private int iplWaitIpl0 = 0, iplWaitIpl1 = 0;
    /**
     * Candidato di {@code check_ipl()}: l'ULTIMO accesso dati emesso finora, cioè
     * {@code last_access_offset_ipl_prev} di gencpu. <b>Non è ancora l'ancora</b> — lo
     * diventa alla prima prefetch che lo segue, ed è tutta la differenza: dopo l'ultima
     * prefetch non c'è più nessuna {@code check_ipl()} a leggerlo.
     */
    private boolean iplCandValid = false;
    private int iplCandNow = 0;
    /**
     * L'istruzione ha dichiarato la sua ancora con {@link #markIplAnchor}: equivale a
     * {@code ipl_fetched >= 2} di gencpu, che fa uscire subito {@code check_ipl()}.
     */
    private boolean iplAnchorDeclared = false;
    /**
     * Ciclo in cui l'ultima dichiarazione ha emesso {@code ipl_fetch_now()}, cioè in cui
     * WinUAE scrive {@code regs.ipl_evt}. Serve solo a riconoscere l'accesso dati che
     * comincia NELLO STESSO ciclo: è l'unico a cui si applica l'override dei wait-state
     * di {@code wait_cpu_cycle_read/write} ({@code if (now == regs.ipl_evt)}).
     */
    private long iplNowDeclaredCycle = Long.MIN_VALUE;
    private int iplPin = 0, iplPinPrev = 0, pendingIpl = 0;
    private long iplPinChangeCycle = -1000, iplPinChangeCycleP = -1000;
    // Candidati registrati all'ultimo POLL dell'istruzione corrente (modo iplcond).
    private boolean iplPollValid = false, iplPollOnFetch = false, iplNextDefer = false;
    private int iplNowIpl = 0, iplNextIpl = 0, iplNextPending = 0;
    /** true se una delle leve che leggono la STORIA del pin è attiva (default: sì). */

    // ── Wake da STOP: modello dei round di WinUAE ─────────────────────────────
    // WinUAE (m68k_run_1_ce + op_4e72_13_ff): mentre è stopped la CPU RI-ESEGUE lo
    // STOP in round da 4 clock (`do_cycles_stop(4)`) ancorati all'ingresso, con due
    // registri ipl[0]/ipl[1]:
    //   • a INIZIO round (T)   → `ipl_fetch_next()`: soglie 4/2 sull'anzianità del
    //                            cambio di pin (le stesse di §iplcond);
    //   • a FINE round (T+4)   → `do_specialties()` riconosce `ipl[0]`, poi
    //                            `ipl[0] = ipl[1]; ipl[1] = 0`.
    // Il wake cade quindi al PRIMO fine-round successivo al primo inizio-round che
    // vede il pin "vecchio" di ≥4 clock: la correzione rispetto a un ritardo COSTANTE
    // vale 4 o 8 clock secondo la fase del pin nella griglia. È questa dipendenza
    // dalla fase — non un delay fisso — a chiudere `stop1` e `stop5` insieme, che era
    // il residuo aperto di FIX #24 (nessuno sweep di un ritardo costante li prendeva
    // entrambi: i due wake sbagliavano di segno OPPOSTO).
    private int stoppedRecogIpl = 0;
    private long stopRoundAnchor = 0;
    private int stopIpl0 = 0, stopIpl1 = 0;
    /** Reset dello stato IPL all'ingresso in STOPPED (nessun wake spurio da valori stantii). */
    public void resetStoppedIplPipe() {
        stoppedRecogIpl = latchedIpl;
        stopRoundAnchor = totalCycles;
        stopIpl0 = 0;
        stopIpl1 = 0;
    }

    // ── Classificatore target chip-bus (per TAS write-drop, oracolo WinUAE) ────
    // L'Amiga non supporta il ciclo RMW indivisibile del 68000 sul chip bus: la
    // fase WRITE di TAS verso un banco chip viene soppressa (WinUAE gencpu i_TAS
    // 68000-ce: `if (is_cycle_ce(srca)) do_cycles(4)` al posto della write).
    // L'Agnus inietta qui il predicato; nel harness JSON standalone resta null →
    // write normale, come i vettori Tom Harte (68000 puro, nessuna piattaforma).
    private java.util.function.IntPredicate chipBusTargetClassifier = null;
    public void setChipBusTargetClassifier(java.util.function.IntPredicate p) { chipBusTargetClassifier = p; }
    boolean isChipBusTarget(int addr) {
        return chipBusTargetClassifier != null && chipBusTargetClassifier.test(addr & 0xFFFFFF);
    }
    // Probe "slot chip-bus occupato in QUESTO cck" (WinUAE `read_rga_out()->alloc`),
    // iniettata dall'Agnus. Nel harness JSON resta null → bus sempre libero.
    private java.util.function.BooleanSupplier chipBusBusyProbe = null;
    public void setChipBusBusyProbe(java.util.function.BooleanSupplier p) { chipBusBusyProbe = p; }
    boolean isChipBusBusyNow() { return chipBusBusyProbe != null && chipBusBusyProbe.getAsBoolean(); }

    // Supporto per il refactor STOP (frontiera 2). Vedi StopInstruction + ramo STOPPED sotto.
    boolean isTracePending() { return tracePending; }
    // Fa scattare l'eccezione trace direttamente dallo StopInstruction (Moira: TRACE_EXC
    // latchato nel giro di execStop → scatta subito, senza round-trip STOPPED, senza sync extra).
    void fireStopTrace() { tracePending = false; triggerTraceException(); }

    private int group0PreFaultSR = -1; // SR to restore in the CPU after the AE frame is built (-1 = no override)
    private boolean group0SavedSRValid = false; // if true, savedSR was pre-set by the instruction, don't overwrite
    private int group0PreFaultAnReg = -1;  // An register index to restore (-1 = no override)
    private int group0PreFaultAnValue = 0; // value to restore to An

    // BUG FIX: il livello 7 sul 68000 reale è NMI edge-triggered: viene accettato solo quando
    // le linee IPL transitano da un valore < 7 a 7. Se IPL rimane fisso a 7, l'interrupt non
    // si riattiva finché IPL non scende sotto 7 e poi risale. Senza questo flag si avrebbero
    // riattivazioni spurie in loop con hardware che mantiene IPL=7 a lungo.
    private boolean level7EdgeArmed = true;

    private boolean exceptionStartsPrefetch = false;

    public void setExceptionStartsPrefetch(boolean v) { this.exceptionStartsPrefetch = v; }

    public enum Phase { T1, T2, T3, T4 }
    private final Phase currentPhase = Phase.T1;

    public Phase getPhase() { return currentPhase; }
    public boolean isT1() { return currentPhase == Phase.T1; }
    public boolean isT2() { return currentPhase == Phase.T2; }
    public boolean isT3() { return currentPhase == Phase.T3; }
    public boolean isT4() { return currentPhase == Phase.T4; }

    private boolean faultIsWrite, faultIsInstruction, addressErrorFault, group0PushedPCValid, processingReset, iackInProgress = false, vmaStartedHigh = false;
    private int group0PushedPC;

    /**
     * Modo di indirizzamento dell'ultima EA calcolata, codificato come {@code mode} 0..6 e
     * {@code 8 + reg} per il modo 7 (8 = {@code (xxx).W}, 9 = {@code (xxx).L},
     * 10 = {@code (d16,PC)}, 11 = {@code (d8,PC,Xn)}, 12 = immediato); -1 = nessuna.
     * Lo registra {@link AbstractM68kInstruction#calculateEA} — che è il nostro
     * {@code genamode} — perché è quella l'EA su cui l'accesso sta per fallire.
     */
    int group0EaMode = -1;
    /** Taglia in byte dell'ultima EA calcolata (1/2/4). */
    int group0EaSize = 2;
    /** {@code +2}/{@code -2}/{@code 0} imposto dall'istruzione, o {@link Integer#MIN_VALUE}. */
    int group0EaDeltaOverride = Integer.MIN_VALUE;
    /** IR dichiarato per il frame di gruppo 0, o -1 = usa l'opcode corrente. */
    int group0IrOverride = -1;

    static final class BusOp { int address, size, dataOut, fc, flushTag; boolean read, uds, lds, rmwLocked; BusOpType type; OpPurpose purpose; }
    public enum BusOpType { NORMAL_READ, NORMAL_WRITE, INSTRUCTION_FETCH, RMW_READ, RMW_WRITE, INTERRUPT_ACK }
    public enum OpPurpose { PREFETCH_IRC, PREFETCH_IRD, DATA_READ, DATA_READ_LONG, DATA_WRITE, STACK_PUSH, STACK_PULL, EXCEPTION_VECTOR, RESET_VECTOR }

    public Motorola68000(BusRequest busReq, BusResponse busResp) {
        this.busReq = Objects.requireNonNull(busReq); this.busResp = Objects.requireNonNull(busResp);
        for (int i = 0; i < BUS_OP_POOL_SIZE; i++) busOpPool[i] = new BusOp();
        Arrays.fill(d, 0); Arrays.fill(a, 0); initDecodeTable();
    }

    private void initDecodeTable() {
        Arrays.fill(decodeTable, null);
        for (int op = 0; op < 65536; op++) {
            if ((op & 0xFFC0) == 0x4800 && isDataAlterable((op >> 3) & 0x7, op & 0x7)) decodeTable[op] = NbcdInstruction.INSTANCE;
            if ((op & 0xFFF8) == 0x4840) decodeTable[op] = SwapInstruction.INSTANCE;
            if ((op & 0xF100) == 0xC100) { int om = (op >> 3) & 0x1F; if (om == 0x08 || om == 0x09 || om == 0x11) decodeTable[op] = ExgInstruction.INSTANCE; }
            if ((op & 0xFFB8) == 0x4880) { int om = (op >> 6) & 0x7; if (om == 2 || om == 3) decodeTable[op] = ExtInstruction.INSTANCE; }
            if ((op & 0xFFC0) == 0x4EC0 && isValidJmpAddress((op >> 3) & 0x7, op & 0x7)) decodeTable[op] = JmpInstruction.INSTANCE;
            if ((op & 0xFFC0) == 0x4E80 && isValidJmpAddress((op >> 3) & 0x7, op & 0x7)) decodeTable[op] = JsrInstruction.INSTANCE;
            if ((op & 0xF1F8) == 0xC100 && decodeTable[op] == null) decodeTable[op] = AbcdInstruction.INSTANCE;
            if ((op & 0xF1F8) == 0xC108 && decodeTable[op] == null) decodeTable[op] = AbcdInstruction.MEMORY_INSTANCE;
            if ((op & 0xF1F8) == 0x8100 && decodeTable[op] == null) decodeTable[op] = SbcdInstruction.INSTANCE;
            if ((op & 0xF1F8) == 0x8108 && decodeTable[op] == null) decodeTable[op] = SbcdInstruction.MEMORY_INSTANCE;
            if ((op & 0xF000) == 0x8000) { int om = (op >> 6) & 7, m = (op >> 3) & 7, r = op & 7; if (om != 3 && om != 7) { if (om < 4) { if (isValidAndSource(m, r) && decodeTable[op] == null) decodeTable[op] = OrInstruction.INSTANCE; } else if (isMemoryAlterable(m, r) && decodeTable[op] == null) decodeTable[op] = OrInstruction.INSTANCE; } }
            if ((op & 0xF000) == 0xC000) { int om = (op >> 6) & 7, m = (op >> 3) & 7; if (om != 3 && om != 7) { if (om < 4 && m != 1 && decodeTable[op] == null) decodeTable[op] = AndInstruction.INSTANCE; else if (om >= 4 && m >= 2 && decodeTable[op] == null) decodeTable[op] = AndInstruction.INSTANCE; } }
            if ((op & 0xFFC0) == 0x40C0 && isDataAlterable((op >> 3) & 0x7, op & 0x7)) decodeTable[op] = MoveFromSRInstruction.INSTANCE;
            if ((op & 0xFF00) == 0x4000 && ((op >> 6) & 3) < 3 && isDataAlterable((op >> 3) & 7, op & 7) && decodeTable[op] == null) decodeTable[op] = NegxInstruction.INSTANCE;
            if ((op & 0xFFC0) == 0x44C0 && ((op >> 3) & 0x7) != 1 && isValidMoveSource((op >> 3) & 0x7, op & 0x7, 3)) decodeTable[op] = MoveToCCRInstruction.INSTANCE;
            if ((op & 0xFF00) == 0x4400 && ((op >> 6) & 3) < 3 && isDataAlterable((op >> 3) & 7, op & 7) && decodeTable[op] == null) decodeTable[op] = NegInstruction.INSTANCE;
            if ((op & 0xFFC0) == 0x46C0 && ((op >> 3) & 0x7) != 1 && isValidMoveSource((op >> 3) & 0x7, op & 0x7, 3)) decodeTable[op] = MoveToSRInstruction.INSTANCE;
            if ((op & 0xFF00) == 0x4600 && ((op >> 6) & 3) < 3 && isDataAlterable((op >> 3) & 7, op & 7) && decodeTable[op] == null) decodeTable[op] = NotInstruction.INSTANCE;
            if ((op & 0xFB80) == 0x4880 && decodeTable[op] == null) decodeTable[op] = MovemInstruction.INSTANCE;
            if ((op & 0xF0C0) == 0x90C0 && isValidMoveSource((op >> 3) & 7, op & 7, 2)) decodeTable[op] = SubaInstruction.INSTANCE;
            if ((op & 0xF0C0) == 0xD0C0 && isValidMoveSource((op >> 3) & 7, op & 7, 2)) decodeTable[op] = AddaInstruction.INSTANCE;
            if ((op & 0xC1C0) == 0x0040) { int szb = (op >> 12) & 3; if (szb >= 2 && isValidMoveSource((op >> 3) & 7, op & 7, szb)) decodeTable[op] = MoveInstruction.INSTANCE; }
            if ((op & 0xC000) == 0x0000) { int szb = (op >> 12) & 3; if (szb != 0 && ((op >> 6) & 7) != 1) { if (isValidMoveSource((op >> 3) & 7, op & 7, szb) && isValidMoveDest((op >> 6) & 7, (op >> 9) & 7, szb) && decodeTable[op] == null) { decodeTable[op] = MoveInstruction.INSTANCE; } } }
            if ((op & 0xF130) == 0x9100 && ((op >> 6) & 3) < 3) decodeTable[op] = SubxInstruction.INSTANCE;
            if ((op & 0xF000) == 0x9000 && decodeTable[op] == null) { int om = (op >> 6) & 7, m = (op >> 3) & 7, r = op & 7; if (om != 3 && om != 7) { if (om < 4) { if (isValidAddSource(m, r, om)) decodeTable[op] = SubInstruction.INSTANCE; } else if (isMemoryAlterable(m, r)) decodeTable[op] = SubInstruction.INSTANCE; } }
            if ((op & 0xF130) == 0xD100 && ((op >> 6) & 3) < 3) decodeTable[op] = AddxInstruction.INSTANCE;
            if ((op & 0xF000) == 0xD000 && decodeTable[op] == null) { int om = (op >> 6) & 7, m = (op >> 3) & 7, r = op & 7; if (om != 3 && om != 7) { if (om < 4) { if (isValidAddSource(m, r, om)) decodeTable[op] = AddInstruction.INSTANCE; } else if (isMemoryAlterable(m, r)) decodeTable[op] = AddInstruction.INSTANCE; } }
            if ((op & 0xFF00) == 0x0400 && ((op >> 6) & 3) < 3 && isDataAlterable((op >> 3) & 7, op & 7)) decodeTable[op] = SubiInstruction.INSTANCE;
            if ((op & 0xFF00) == 0x0600 && ((op >> 6) & 3) < 3 && isDataAlterable((op >> 3) & 7, op & 7)) decodeTable[op] = AddiInstruction.INSTANCE;
            if (op == 0x003C) decodeTable[op] = OriToCCRInstruction.INSTANCE;
            if (op == 0x007C) decodeTable[op] = OriToSRInstruction.INSTANCE;
            if ((op & 0xFF00) == 0x0000 && ((op >> 6) & 3) < 3 && isDataAlterable((op >> 3) & 7, op & 7) && decodeTable[op] == null) decodeTable[op] = OriInstruction.INSTANCE;
            if ((op & 0xF138) == 0x0108) decodeTable[op] = MovepInstruction.INSTANCE;
            if ((op & 0xF100) == 0x7000) decodeTable[op] = MoveqInstruction.INSTANCE;
            if (op == 0x0A3C) decodeTable[op] = EoriToCCRInstruction.INSTANCE;
            if (op == 0x0A7C) decodeTable[op] = EoriToSRInstruction.INSTANCE;
            if ((op & 0xFF00) == 0x0A00 && ((op >> 6) & 3) < 3 && isDataAlterable((op >> 3) & 7, op & 7) && decodeTable[op] == null) decodeTable[op] = EoriInstruction.INSTANCE;
            if ((op & 0xFF00) == 0x0200 && ((op >> 6) & 3) < 3 && isDataAlterable((op >> 3) & 7, op & 7) && decodeTable[op] == null) decodeTable[op] = AndiInstruction.INSTANCE;
            if ((op & 0xF100) == 0x5100 && ((op >> 6) & 3) < 3 && isAlterable((op >> 3) & 7, op & 7) && !(((op >> 3) & 7) == 1 && ((op >> 6) & 3) == 0)) decodeTable[op] = SubqInstruction.INSTANCE;
            if ((op & 0xF100) == 0x5000 && ((op >> 6) & 3) < 3 && isAlterable((op >> 3) & 7, op & 7) && !(((op >> 3) & 7) == 1 && ((op >> 6) & 3) == 0)) decodeTable[op] = AddqInstruction.INSTANCE;
            if (op == 0x023C) decodeTable[op] = AndiToCCRInstruction.INSTANCE;
            if (op == 0x027C) decodeTable[op] = AndiToSRInstruction.INSTANCE;
            if ((op & 0xF118) == 0xE100 && ((op >> 6) & 3) != 3 && decodeTable[op] == null) decodeTable[op] = AslInstruction.INSTANCE;
            if ((op & 0xF118) == 0xE000 && ((op >> 6) & 3) != 3 && decodeTable[op] == null) decodeTable[op] = AsrInstruction.INSTANCE;
            if ((op & 0xFFC0) == 0xE0C0 && ((op >> 3) & 7) >= 2 && decodeTable[op] == null) decodeTable[op] = AsrInstruction.INSTANCE;
            if ((op & 0xFFC0) == 0xE1C0 && ((op >> 3) & 7) >= 2 && decodeTable[op] == null) decodeTable[op] = AslInstruction.INSTANCE;
            if ((op & 0xF1C0) == 0x0140 && decodeTable[op] == null) decodeTable[op] = BchgInstruction.INSTANCE;
            if ((op & 0xFFC0) == 0x0840 && decodeTable[op] == null) decodeTable[op] = BchgInstruction.INSTANCE;
            if ((op & 0xF1C0) == 0x0180 && isDataAlterable((op >> 3) & 0x7, op & 0x7) && decodeTable[op] == null) decodeTable[op] = BclrInstruction.INSTANCE;
            if ((op & 0xFFC0) == 0x0880 && isDataAlterable((op >> 3) & 0x7, op & 0x7) && decodeTable[op] == null) decodeTable[op] = BclrInstruction.INSTANCE;
            if ((op & 0xF1C0) == 0x01C0 && isDataAlterable((op >> 3) & 0x7, op & 0x7) && decodeTable[op] == null) decodeTable[op] = BsetInstruction.INSTANCE;
            if ((op & 0xFFC0) == 0x08C0 && isDataAlterable((op >> 3) & 0x7, op & 0x7) && decodeTable[op] == null) decodeTable[op] = BsetInstruction.INSTANCE;
            if ((op & 0xFF00) == 0x6100 && decodeTable[op] == null) decodeTable[op] = BsrInstruction.INSTANCE;
            if ((op & 0xF000) == 0x6000 && decodeTable[op] == null) decodeTable[op] = BccInstruction.INSTANCE;
            if ((op & 0xF0F8) == 0x50C8) decodeTable[op] = DBccInstruction.INSTANCE;
            if ((op & 0xF100) == 0xB100 && ((op >> 6) & 3) < 3 && isDataAlterable((op >> 3) & 7, op & 7)) decodeTable[op] = EorInstruction.INSTANCE;
            if ((op & 0xF1C0) == 0x80C0) decodeTable[op] = DivuInstruction.INSTANCE;
            if ((op & 0xF1C0) == 0x81C0) decodeTable[op] = DivsInstruction.INSTANCE;
            if ((op & 0xF1C0) == 0xC0C0 && ((op >> 3) & 7) != 1) decodeTable[op] = MuluInstruction.INSTANCE;
            if ((op & 0xF1C0) == 0xC1C0 && ((op >> 3) & 7) != 1) decodeTable[op] = MulsInstruction.INSTANCE;
            if ((op & 0xF1C0) == 0x0100 && isValidBtstDest((op >> 3) & 0x7, op & 0x7, false) && decodeTable[op] == null) decodeTable[op] = BtstInstruction.INSTANCE;
            if ((op & 0xFFC0) == 0x0800 && isValidBtstDest((op >> 3) & 0x7, op & 0x7, true) && decodeTable[op] == null) decodeTable[op] = BtstInstruction.INSTANCE;
            if ((op & 0xF1C0) == 0x4180 && isValidChkDest((op >> 3) & 0x7, op & 0x7) && decodeTable[op] == null) decodeTable[op] = ChkInstruction.INSTANCE;
            if ((op & 0xFF00) == 0x4200 && ((op >> 6) & 3) < 3 && isDataAlterable((op >> 3) & 7, op & 7) && decodeTable[op] == null) decodeTable[op] = ClrInstruction.INSTANCE;
            if ((op & 0xFFC0) == 0x4AC0 && isDataAlterable((op >> 3) & 7, op & 7) && decodeTable[op] == null) decodeTable[op] = TasInstruction.INSTANCE;
            if ((op & 0xFF00) == 0x4A00 && ((op >> 6) & 3) < 3 && isDataAlterable((op >> 3) & 7, op & 7)) decodeTable[op] = TstInstruction.INSTANCE;
            if ((op & 0xF0C0) == 0xB0C0) decodeTable[op] = CmpaInstruction.INSTANCE;
            if ((op & 0xF138) == 0xB108 && ((op >> 6) & 3) < 3) decodeTable[op] = CmpmInstruction.INSTANCE;
            if ((op & 0xF100) == 0xB000 && ((op >> 6) & 3) < 3 && decodeTable[op] == null) decodeTable[op] = CmpInstruction.INSTANCE;
            if ((op & 0xFF00) == 0x0C00 && ((op >> 6) & 3) < 3 && decodeTable[op] == null) decodeTable[op] = CmpiInstruction.INSTANCE;
            if ((op & 0xFFC0) == 0x4840 && isValidJmpAddress((op >> 3) & 7, op & 7)) decodeTable[op] = PeaInstruction.INSTANCE;
            if ((op & 0xF1C0) == 0x41C0 && isValidJmpAddress((op >> 3) & 7, op & 7)) decodeTable[op] = LeaInstruction.INSTANCE;
            if ((op & 0xFFF8) == 0x4E50) decodeTable[op] = LinkInstruction.INSTANCE;
            if ((op & 0xFFF8) == 0x4E58) decodeTable[op] = UnlkInstruction.INSTANCE;
            if ((op & 0xF0C0) == 0x50C0 && isDataAlterable((op >> 3) & 7, op & 7) && decodeTable[op] == null) decodeTable[op] = SccInstruction.INSTANCE;
            if ((op & 0xFFF0) == 0x4E40) decodeTable[op] = TrapInstruction.INSTANCE;
            if (op == 0x4AFC) decodeTable[op] = IllegalInstruction.INSTANCE;
            if (op == 0x4E70) decodeTable[op] = ResetInstruction.INSTANCE;
            if (op == 0x4E72) decodeTable[op] = StopInstruction.INSTANCE;
            if (op == 0x4E71) decodeTable[op] = NopInstruction.INSTANCE;
            if (op == 0x4E73) decodeTable[op] = RteInstruction.INSTANCE;
            if (op == 0x4E75) decodeTable[op] = RtsInstruction.INSTANCE;
            if (op == 0x4E76) decodeTable[op] = TrapvInstruction.INSTANCE;
            if (op == 0x4E77) decodeTable[op] = RtrInstruction.INSTANCE;
            if ((op & 0xFFF8) == 0x4E60) decodeTable[op] = MoveToUSPInstruction.INSTANCE;
            if ((op & 0xFFF8) == 0x4E68) decodeTable[op] = MoveFromUSPInstruction.INSTANCE;
            if ((op & 0xF118) == 0xE108 && ((op >> 6) & 3) != 3 && decodeTable[op] == null) decodeTable[op] = LslInstruction.INSTANCE;
            if ((op & 0xFFC0) == 0xE3C0 && isMemoryAlterable((op >> 3) & 7, op & 7) && decodeTable[op] == null) decodeTable[op] = LslInstruction.INSTANCE;
            if ((op & 0xF118) == 0xE008 && ((op >> 6) & 3) != 3 && decodeTable[op] == null) decodeTable[op] = LsrInstruction.INSTANCE;
            if ((op & 0xFFC0) == 0xE2C0 && isMemoryAlterable((op >> 3) & 7, op & 7) && decodeTable[op] == null) decodeTable[op] = LsrInstruction.INSTANCE;
            if ((op & 0xF118) == 0xE118 && ((op >> 6) & 3) != 3 && decodeTable[op] == null) decodeTable[op] = RolInstruction.INSTANCE;
            if ((op & 0xFFC0) == 0xE7C0 && isMemoryAlterable((op >> 3) & 7, op & 7) && decodeTable[op] == null) decodeTable[op] = RolInstruction.INSTANCE;
            if ((op & 0xF118) == 0xE018 && ((op >> 6) & 3) != 3 && decodeTable[op] == null) decodeTable[op] = RorInstruction.INSTANCE;
            if ((op & 0xFFC0) == 0xE6C0 && isMemoryAlterable((op >> 3) & 7, op & 7) && decodeTable[op] == null) decodeTable[op] = RorInstruction.INSTANCE;
            if ((op & 0xF118) == 0xE110 && ((op >> 6) & 3) != 3 && decodeTable[op] == null) decodeTable[op] = RoxlInstruction.INSTANCE;
            if ((op & 0xFFC0) == 0xE5C0 && isMemoryAlterable((op >> 3) & 7, op & 7) && decodeTable[op] == null) decodeTable[op] = RoxlInstruction.INSTANCE;
            if ((op & 0xF118) == 0xE010 && ((op >> 6) & 3) != 3 && decodeTable[op] == null) decodeTable[op] = RoxrInstruction.INSTANCE;
            if ((op & 0xFFC0) == 0xE4C0 && isMemoryAlterable((op >> 3) & 7, op & 7) && decodeTable[op] == null) decodeTable[op] = RoxrInstruction.INSTANCE;
        }
    }

    private boolean isValidMoveSource(int mode, int reg, int sizeBits) {
        if (mode == 0) return true; if (mode == 1) return sizeBits != 1; if (mode >= 2 && mode <= 6) return true; if (mode == 7) return reg <= 4; return false;
    }
    private boolean isValidJmpAddress(int mode, int reg) {
        if (mode == 2 || mode == 5 || mode == 6) return true; if (mode == 7) return reg <= 3; return false;
    }
    private boolean isValidAndSource(int mode, int reg) {
        if (mode == 0) return true; if (mode == 1) return false; if (mode <= 6) return true; if (mode == 7) return reg <= 4; return false;
    }
    private boolean isValidMoveDest(int mode, int reg, int sizeBits) {
        if (mode == 0) return true; if (mode == 1) return sizeBits != 1; if (mode >= 2 && mode <= 6) return true; if (mode == 7) return reg <= 1; return false;
    }
    private boolean isValidAddSource(int mode, int reg, int opmode) {
        if (mode == 0) return true; if (mode == 1) return (opmode & 0x3) != 0; if (mode <= 6) return true; if (mode == 7) return reg <= 4; return false;
    }
    private boolean isDataAlterable(int mode, int reg) {
        if (mode == 0) return true; if (mode >= 2 && mode <= 6) return true; if (mode == 7) return reg <= 1; return false;
    }
    private boolean isMemoryAlterable(int mode, int reg) {
        if (mode >= 2 && mode <= 6) return true; if (mode == 7) return reg <= 1; return false;
    }
    private boolean isAlterable(int mode, int reg) {
        if (mode <= 6) return true; if (mode == 7) return reg <= 1; return false;
    }
    private boolean isValidBtstDest(int mode, int reg, boolean isStatic) {
        if (mode == 0) return true; if (mode >= 2 && mode <= 6) return true; if (mode == 7) { if (reg <= 3) return true; if (!isStatic && reg == 4) return true; } return false;
    }
    private boolean isValidChkDest(int mode, int reg) {
        if (mode == 0) return true; if (mode >= 2 && mode <= 6) return true; if (mode == 7) return reg <= 4; return false;
    }

    public void fillPrefetch(int ird, int irc) {
        this.ird = ird;
        this.irdAddr = pc;
        this.irdValid = true;
        this.irc = irc;
        this.ircAddr = pc + 2;
        this.ircValid = true;
    }

    public void prepareForDisassembly(int addr, int opcode, int nextWord, int afterNextWord, AbstractM68kInstruction instr) {
        this.opcodeAddress = addr; this.ir = opcode;
        this.ird = nextWord; this.irdAddr = addr + 2; this.irdValid = true;
        this.irc = afterNextWord; this.ircAddr = addr + 4; this.ircValid = true;
        this.pc = addr + 6; this.instructionStep = 0; this.currentInstruction = instr;
    }

    public int extensionWordAt(int address) {
        if (irdValid && address == irdAddr) return ird & 0xFFFF;
        if (ircValid && address == ircAddr) return irc & 0xFFFF;
        return 0;
    }

    public void injectState(int pc, int sr, int usp, int ssp, int ird, int irc) {
        this.sr = sr & 0xA71F; syncFlagsFromSR(); this.usp = usp; this.ssp = ssp; a[7] = isSupervisor() ? ssp : usp;
        this.opcodeAddress = pc;
        this.ird = ird; this.irdAddr = pc; this.irdValid = true;
        this.irc = irc; this.ircAddr = pc + 2; this.ircValid = true;
        this.pc = pc + 4;
        this.cpuState = CpuState.NORMAL; this.busCycleState = BusCycleState.IDLE;
        this.currentMicroTask = InternalTask.NONE; this.microTaskStep = 0; this.currentBusOp = null; this.currentInstruction = null;
        this.instructionStep = 0; this.inInstructionBoundary = true; this.totalCycles = 0; this.processingReset = false;
        this.instructionFinishedThisCycle = false;
        this.tracePending = false; this.iackInProgress = false; this.eClockCounter = 9; this.internalCyclesRemaining = 0;
        this.resetSignalCyclesRemaining = 0; this.prefetchPending = false; this.busLocked = false; clearBusQueue();
        this.exceptionStartsPrefetch = false;
        this.level7EdgeArmed = true;
        Arrays.fill(tempRegs, 0); tempRegsWritten = 0;
    }

    public int getUSP() { return usp; }
    public int getSSP() { return ssp; }
    public int[] getPrefetchQueue() { return new int[]{ird, irc}; }
    public long getTotalCycles() { return totalCycles; }
    public int getSR() { int ccr = (x ? 0x10 : 0) | (n ? 0x08 : 0) | (z ? 0x04 : 0) | (v ? 0x02 : 0) | (c ? 0x01 : 0); return (sr & 0xA700) | ccr; }
    private void syncFlagsFromSR() { x = (sr & 0x10) != 0; n = (sr & 0x08) != 0; z = (sr & 0x04) != 0; v = (sr & 0x02) != 0; c = (sr & 0x01) != 0; }
    public int getPC() {
        if (irdValid) {
            return irdAddr;
        }
        if (prefetchPending) {
            return pendingPrefetchAddr;
        }
        return pc;
    }
    public int getIrcAddr() { return ircAddr; }
    public int getD(int reg, int size) { if (size == 4) return d[reg]; if (size == 2) return d[reg] & 0xFFFF; return d[reg] & 0xFF; }
    public void setD(int reg, int val, int size) { if (size == 4) d[reg] = val; else if (size == 2) d[reg] = (d[reg] & 0xFFFF0000) | (val & 0xFFFF); else d[reg] = (d[reg] & 0xFFFFFF00) | (val & 0xFF); }
    public int getRawD(int reg) { return d[reg]; }
    public void setRawD(int reg, int val) { d[reg] = val; }
    public int getA(int reg, int size) { return (reg == 7) ? getA7() : a[reg]; }
    public void setA(int reg, int val) { if (reg == 7) setA7(val); else a[reg] = val; }
    public int getRawA(int reg) { return a[reg]; }
    public void setRawA(int reg, int val) { a[reg] = val; }
    public int getA7() { return a[7]; }
    public void setA7(int v) { a[7] = v; if (isSupervisor()) ssp = v; else usp = v; }
    public void setPC(int newPC) { pc = newPC; flushPrefetch(); }
    public int getOpcodeAddress() { return opcodeAddress; }
    public AbstractM68kInstruction getCurrentInstruction() { return currentInstruction; }
    public boolean isSupervisor() { return (sr & 0x2000) != 0; }
    private int getIntMask() { return (sr >>> 8) & 0x7; }
    private boolean isTraceEnabled() { return (sr & 0x8000) != 0; }
    public void writeCCR(int newCcr) { this.sr = ((this.sr & 0xFF00) | (newCcr & 0x001F)) & 0xA71F; syncFlagsFromSR(); }

    public void writeSR(int newSr, boolean privileged) {
        boolean internalAccess = (currentMicroTask != InternalTask.NONE) || processingReset;

        if (privileged) {
            if (!internalAccess && !isSupervisor()) {
                triggerPrivilegeViolation();
                return;
            }
            boolean oldS = isSupervisor();
            if (oldS) ssp = a[7]; else usp = a[7];
            this.sr = newSr & 0xA71F;
            syncFlagsFromSR();
            boolean newS = isSupervisor();
            if (oldS != newS) {
                a[7] = newS ? ssp : usp;
            }
        } else {
            // CCR only update - never privileged
            this.sr = ((this.sr & 0xFF00) | (newSr & 0x001F)) & 0xA71F;
            syncFlagsFromSR();
        }
    }

    public void assertReset(int cycles) { resetSignalCyclesRemaining = cycles; }
    public void reset() {
        assertReset(512);
        // Reset dei registri D/A a 0, come Moira/vAmiga (`reg = { }`, Moira.cpp:204).
        // Il 68000 lascia i registri INDEFINITI dopo il reset; il valore preciso è
        // "don't care" per il codice ben formato, ma i test screenshot vAmigaTS che
        // leggono un registro dato non inizializzato (es. `move.w d5,(a4)` con d5 mai
        // scritto dal boot) confrontano il valore col riferimento vAmiga → per
        // combaciare a 0% il fill di reset deve essere identico all'oracolo: 0, non
        // il pattern diagnostico $AAAAAAAA usato in precedenza.
        Arrays.fill(d, 0);
        Arrays.fill(a, 0);
        sr = 0x2700 & 0xA71F; syncFlagsFromSR(); processingReset = true; cpuState = CpuState.RESET;
        busCycleState = BusCycleState.IDLE; currentMicroTask = InternalTask.RESET_SEQ; microTaskStep = 0; currentInstruction = null;
        instructionStep = 0; inInstructionBoundary = true; tracePending = false; irdValid = false; ircValid = false; prefetchPending = false;
        ssp = 0; usp = 0; opcodeAddress = 0; setA7(0); iackInProgress = false; busReq.clear(); currentBusOp = null; totalCycles = 0;
        eClockCounter = 9; internalCyclesRemaining = 0; busLocked = false; addressErrorFault = false; group0PushedPC = 0; group0PushedPCValid = false; group0PreFaultSR = -1; group0SavedSRValid = false; group0PreFaultAnReg = -1;
        exceptionStartsPrefetch = false;
        level7EdgeArmed = true; // Reset edge-detection NMI: pronto ad accettare il primo livello 7
        clearBusQueue(); Arrays.fill(tempRegs, 0); tempRegsWritten = 0;
    }

    public void scheduleRead(int addr, int size) { scheduleRead(addr, size, currentFCForData()); }

    public void scheduleRead(int addr, int size, int fc) {
        if (size == 1) {
            BusOp op = createOp(addr, 1, true, 0, fc);
            op.type = BusOpType.NORMAL_READ; op.purpose = OpPurpose.DATA_READ;
            enqueueBusOp(op);
        } else if (size == 2) {
            if ((addr & 1) != 0) { triggerAddressError(addr, false, fc, false); return; }
            BusOp op = createOp(addr, 2, true, 0, fc);
            op.type = BusOpType.NORMAL_READ; op.purpose = OpPurpose.DATA_READ;
            enqueueBusOp(op);
        } else {
            if ((addr & 1) != 0) { triggerAddressError(addr, false, fc, false); return; }
            longReadHighValid = false;
            BusOp op1 = createOp(addr, 2, true, 0, fc);
            op1.type = BusOpType.NORMAL_READ; op1.purpose = OpPurpose.DATA_READ_LONG;
            enqueueBusOp(op1);
            BusOp op2 = createOp(addr + 2, 2, true, 0, fc);
            op2.type = BusOpType.NORMAL_READ; op2.purpose = OpPurpose.DATA_READ_LONG;
            enqueueBusOp(op2);
        }
    }
    public void scheduleWrite(int addr, int val, int size) { if (size == 1) scheduleWriteByte(addr, val); else if (size == 4) scheduleWriteLong(addr, val); else scheduleWriteWord(addr, val); }

    public void scheduleReadWord(int address, OpPurpose purpose) {
        int fc = (purpose == OpPurpose.PREFETCH_IRC || purpose == OpPurpose.PREFETCH_IRD) ? currentFCForInstruction() : currentFCForData();
        if ((address & 1) != 0) {
            triggerAddressError(address, false, fc, purpose == OpPurpose.PREFETCH_IRC || purpose == OpPurpose.PREFETCH_IRD);
            return;
        }
        BusOp op = createOp(address, 2, true, 0, fc);
        op.type = (purpose == OpPurpose.PREFETCH_IRC || purpose == OpPurpose.PREFETCH_IRD)
          ? BusOpType.INSTRUCTION_FETCH : BusOpType.NORMAL_READ;
        op.purpose = purpose;
        enqueueBusOp(op);
    }
    private void scheduleWriteByte(int address, int value) {
        BusOp op = createOp(address, 1, false, value, currentFCForData()); op.type = BusOpType.NORMAL_WRITE; op.purpose = OpPurpose.DATA_WRITE; enqueueBusOp(op);
    }
    public void scheduleWriteWord(int address, int value) {
        if ((address & 1) != 0) { triggerAddressError(address, true, currentFCForData(), false); return; }

        BusOp op = createOp(address, 2, false, value, currentFCForData());
        op.type = BusOpType.NORMAL_WRITE;
        op.purpose = OpPurpose.DATA_WRITE;
        enqueueBusOp(op);
    }
    private void scheduleWriteLong(int address, int value) {
        if ((address & 1) != 0) { triggerAddressError(address, true, currentFCForData(), false); return; }

        scheduleWriteWord(address, (value >>> 16) & 0xFFFF);
        scheduleWriteWord(address + 2, value & 0xFFFF);
    }
    public void scheduleRmwReadByte(int address) { BusOp op = createOp(address, 1, true, 0, currentFCForData()); op.type = BusOpType.RMW_READ; op.purpose = OpPurpose.DATA_READ; op.rmwLocked = true; enqueueBusOp(op); }
    public void scheduleRmwWriteByte(int address, int value) {
        BusOp op = createOp(address, 1, false, value, currentFCForData()); op.type = BusOpType.RMW_WRITE; op.purpose = OpPurpose.DATA_WRITE; op.rmwLocked = true; enqueueBusOp(op);
    }

    private BusOp createOp(int addr, int size, boolean read, int data, int fc) {
        BusOp op = busOpPool[busOpPoolHead & (BUS_OP_POOL_SIZE - 1)];
        busOpPoolHead++;
        op.address = addr;
        op.size = size;
        op.read = read;
        op.fc = fc;
        op.flushTag = flushCount;
        op.rmwLocked = false;

        if (size == 2) {
            op.uds = true;
            op.lds = true;
            op.dataOut = read ? 0 : (data & 0xFFFF);
        } else {
            boolean upper = (addr & 1) == 0;
            op.uds = upper;
            op.lds = !upper;
            if (read) {
                op.dataOut = 0;
            } else {
                int bv = data & 0xFF;
                op.dataOut = (bv << 8) | bv;
            }
        }
        return op;
    }

    private BusOp createInterruptAckOp(int ipl) {
        BusOp op = createOp(0xFFFFFF00 | ((ipl & 0x7) << 1) | 1, 2, true, 0, FC_CPU_SPACE);
        op.type = BusOpType.INTERRUPT_ACK;
        op.purpose = OpPurpose.DATA_READ;
        return op;
    }

    private void enqueueBusOp(BusOp op) {
        pushBusOp(op);
        if (busCycleState == BusCycleState.IDLE && currentBusOp == null) {
            BusOp next = pollBusOp();
            if (next != null) startBusCycle(next);
        }
    }

    private void pushBusOp(BusOp op) {
        if (busQueueSize >= BUS_QUEUE_SIZE) {
            throw new IllegalStateException("CPU bus queue overflow: " + BUS_QUEUE_SIZE);
        }
        busQueue[busQueueTail] = op;
        busQueueTail = (busQueueTail + 1) & (BUS_QUEUE_SIZE - 1);
        busQueueSize++;
    }

    private BusOp pollBusOp() {
        if (busQueueSize == 0) return null;
        BusOp op = busQueue[busQueueHead];
        busQueue[busQueueHead] = null;
        busQueueHead = (busQueueHead + 1) & (BUS_QUEUE_SIZE - 1);
        busQueueSize--;
        return op;
    }

    private boolean isBusQueueEmpty() {
        return busQueueSize == 0;
    }

    private void clearBusQueue() {
        while (busQueueSize > 0) {
            busQueue[busQueueHead] = null;
            busQueueHead = (busQueueHead + 1) & (BUS_QUEUE_SIZE - 1);
            busQueueSize--;
        }
        busQueueHead = 0;
        busQueueTail = 0;
    }

    private void startBusCycle(BusOp op) {
        currentBusOp = op;
        busCycleState = BusCycleState.S1;
        if (op.rmwLocked) busLocked = true;
        busReq.clear();
        applyBusSignalsS0(op);
    }
    public void flushPrefetch() {
        clearBusQueue();
        // If we are in the middle of an instruction fetch, we can consider it "cancelled"
        // by the flushCount, but clearing the queue prevents NEW obsolete fetches from starting.
        irdValid = false;
        ircValid = false;
        prefetchPending = false;
        flushCount++;
    }
    private void handleArbitration() { if (busResp.br) { if (!busReq.bg && !busLocked) busReq.bg = true; } else { if (busReq.bg) busReq.bg = false; } }

    public void startPrefetchCycle() {
        if (prefetchPending || busReq.bg || busLocked) {
            return;
        }
        int fc = currentFCForInstruction();
        if ((pc & 1) != 0) { triggerAddressError(pc, false, fc, true); return; }
        if (!irdValid) {
            BusOp op = createOp(pc, 2, true, 0, fc); op.type = BusOpType.INSTRUCTION_FETCH; op.purpose = OpPurpose.PREFETCH_IRD;
            prefetchPending = true; pendingPrefetchAddr = pc; pc += 2; enqueueBusOp(op);
        }
        else if (!ircValid) {
            BusOp op = createOp(pc, 2, true, 0, fc); op.type = BusOpType.INSTRUCTION_FETCH; op.purpose = OpPurpose.PREFETCH_IRC;
            prefetchPending = true; pendingPrefetchAddr = pc; pc += 2; enqueueBusOp(op);
        }
    }

    public void fallingEdge() {
        if (cpuState == CpuState.HALTED) { busReq.halt = true; return; }
        // STEP 7 (PERFORMANCE_REPORT_FINAL): evita la divisione intera (% 10, non
        // potenza di 2) eseguita ~7M volte/s. eClockCounter è invariante in [0,9],
        // quindi l'incremento con reset condizionale è identico a (x+1) % 10 ma
        // sostituisce un IDIV (20-90 cicli host) con un confronto+branch.
        //
        // `pinChangeAt` è il clock a cui APPARTIENE questo fronte, cioè quello che il
        // risingEdge appena trascorso ha usato: `totalCycles` viene incrementato qui
        // sotto e da lì in poi nomina il ciclo che il fallingEdge sta eseguendo, ma gli
        // ancoraggi del POLL (pollIpl → recordIplFetchNext) lo leggono dentro
        // advanceBusCycleRising(), cioè PRIMA dell'incremento.
        final long pinChangeAt = totalCycles;
        totalCycles++; if (++eClockCounter >= 10) eClockCounter = 0;
        latchedIpl = busResp.ipl & 0x7;
        if (latchedIpl != iplPin) {
            // WinUAE update_ipl(): memorizza valore e istante del cambio PRECEDENTE
            // (ipl_pin_p / ipl_pin_change_evt_p) prima di registrare quello nuovo.
            // L'istante è quello del ciclo in cui il chip ha pilotato la linea — in
            // WinUAE `update_ipl()` è chiamata da `doint()` e timbra `get_cycles()` lì,
            // dentro il ciclo del chip. Timbrare col valore già incrementato faceva
            // risultare il cambio un clock più giovane di quanto è, e le soglie 4/2 di
            // ipl_fetch_next() maturavano un clock più tardi. Misurato su vAmigaTS
            // Paula/Audio: la latenza minima bit-in-INTREQ → accettazione passa da 10
            // clock a 8, che è quella dell'oracolo.
            //
            // ⚠️ Va insieme a Paula.INTREQ_SHADOW_DELAY_MC = 2: separate, ciascuna
            // sposta di un clock il riconoscimento dell'interrupt e il banco vAmigaTS
            // cpu/timing lo vede subito (245 rossi su 253 con la sola di queste due).
            // Vedi docs/vamigats-paula-audio-plan.md §18.
            iplPinPrev = iplPin;
            iplPinChangeCycleP = iplPinChangeCycle;
            iplPin = latchedIpl;
            iplPinChangeCycle = pinChangeAt;
        }
        // BUG FIX: edge detection per NMI livello 7 — quando IPL scende sotto 7
        // il meccanismo si ricarica, permettendo un nuovo NMI al prossimo fronte.
        if (latchedIpl < 7) level7EdgeArmed = true;
        if (busResp.halt && busCycleState == BusCycleState.IDLE) return;
        if (busCycleState != BusCycleState.IDLE && currentBusOp != null) advanceBusCycleFalling();
        busReq.eClock = (eClockCounter >= 6 && eClockCounter <= 9); busReq.resetLine = (resetSignalCyclesRemaining > 0);
    }

    public void risingEdge() {
        instructionFinishedThisCycle = false;
        if (cpuState == CpuState.HALTED) { busReq.halt = true; return; }
        if (resetSignalCyclesRemaining > 0) { resetSignalCyclesRemaining--; return; }
        if (busResp.halt && busCycleState == BusCycleState.IDLE) return;
        handleArbitration();
        if (busResp.berr && busCycleState != BusCycleState.IDLE) {
            if (currentBusOp != null && currentBusOp.type == BusOpType.INTERRUPT_ACK) {
                if (busCycleState == BusCycleState.S4) busCycleState = BusCycleState.S5;
            } else {
                handleBusError();
                return;
            }
        }
        if (busCycleState != BusCycleState.IDLE || !isBusQueueEmpty()) {
            if (busCycleState != BusCycleState.IDLE && currentBusOp != null) {
                advanceBusCycleRising();
                if (busCycleState == BusCycleState.S4 && !busResp.ack && !busResp.berr && !busResp.vpa) {
                    waitCycles++;
                    // WinUAE dma_cycle(): a ogni ciclo bloccato il pin viene ri-letto.
                    if (iplAnchorIsCurrentOp) {
                        iplWaitIpl1 = iplWaitIpl0;
                        iplWaitIpl0 = busResp.ipl & 0x7;
                        iplAnchorHadWait = true;
                    }
                } else if (busCycleState != BusCycleState.S4) {
                    // Fine dell'attesa: commit dell'override (WinUAE lo applica in coda
                    // all'accesso, `if (now == regs.ipl_evt) regs.ipl[0] = ipl;`).
                    if (iplAnchorHadWait) commitIplWaitOverride();
                    if (busCycleState == BusCycleState.S0 || busCycleState == BusCycleState.S5) waitCycles = 0;
                }
            }
            return;
        }
        waitCycles = 0;
        if (internalCyclesRemaining > 0) {
            internalCyclesRemaining--;
            return;
        }
        if (busReq.bg && busCycleState == BusCycleState.IDLE) return;
        if (processingReset) { processResetSequence(); return; }
        if (cpuState == CpuState.STOPPED) {
            latchedIpl = busResp.ipl & 0x7;
            // ORDINE MOIRA (Moira.cpp slow-path): il TRACE_EXC pendente viene processato
            // (line 280 `execException(TRACE)`) PRIMA del blocco STOPPED/privilege (line 302).
            // Perciò il trace va controllato PRIMA della privilege violation, non dopo.
            // Distingue i due percorsi d'ingresso (root frontiera 2, stop3 vs stop4):
            //  - stop4 (`ori #$8000,SR; stop #$0000`, T=1 prima dello STOP): TRACE_EXC è
            //    latchato sull'execute che esegue lo STOP (`TRACING && !STOPPED`), quindi il
            //    TRACE scatta per PRIMO anche se lo STOP ha azzerato S → handler trace → rte
            //    → l'`rte` dell'irq gira in USER → priv (percorso ~72 CCK, non priv-diretta).
            //  - stop3 (`andi #$7FFF,SR; stop #$0000`, T=0): tracePending=false → cade sul
            //    ramo privilege sotto (priv diretta ~21 CCK), invariato.
            // Trace ed STOP (Moira Moira.cpp slow-path):
            //  (a) tracePending = T=1 PRIMA dello STOP (es. `ori #$8000,SR; stop #$2000`):
            //      Moira latcha TRACE_EXC sull'execute che ESEGUE lo STOP (`TRACING &&
            //      !STOPPED`, prima che STOP fermi la CPU) → il trace SCATTA. Va mantenuto.
            //  (b) STOP che CARICA T=1 (es. `stop #$A000`) con tracePending=false: NON
            //      scatta — `TRACING && !STOPPED` è falso perché STOPPED è già set → il
            //      trace è SOPPRESSO mentre la CPU è ferma e si RI-ARMA dal bit T dell'SR
            //      quando un interrupt la sveglia (istruzione post-STOP tracciata dal
            //      percorso normale). Prima usavamo `|| isTraceEnabled()`: sbagliato (il
            //      caso (b) tracciava subito → single-step immediato invece dello stop).
            if (tracePending) {
                // FALLBACK difensivo: con StopInstruction.fireStopTrace il trace pending scatta
                // già DIRETTAMENTE dall'istruzione STOP (Moira: TRACE_EXC latchato nel giro di
                // execStop), quindi questo ramo è normalmente irraggiungibile per lo STOP. Resta
                // come rete di sicurezza, SENZA idle (Moira: execException(TRACE) diretto, no sync).
                tracePending = false;
                triggerTraceException();
                return;
            }
            // FALLBACK difensivo: un STOP che azzera il bit S fa scattare la privilege
            // violation direttamente in StopInstruction step1 (oracolo WinUAE: il loop
            // ri-dispatcha l'opcode STOP e il check `if (!regs.s) Exception(8)` scatta al
            // giro dopo, gratis — né la sync(4) di Moira né il roundtrip STOPPED, che
            // costavano +2 CCK sull'entry di stop3). Non si può entrare in STOPPED con
            // S=0 e S non cambia da fermi, quindi questo ramo è normalmente
            // irraggiungibile; resta come rete di sicurezza, senza idle.
            if (!isSupervisor()) {
                cpuState = CpuState.NORMAL;
                triggerPrivilegeViolation();
                return;
            }
            serviceStopRound();
            return;
        }
        if (currentMicroTask != InternalTask.NONE) { processInternalTask(); return; }
        if (inInstructionBoundary && (cpuState == CpuState.NORMAL || cpuState == CpuState.STOPPED) && !iackInProgress) {
            checkInterrupts();
            if (iackInProgress) return;
        }

        // --- Instruction Execution ---
        if (currentInstruction != null) {
            risingEdgeConsumed = false;
            boolean result = currentInstruction.executeStep(this);
            if (!result || risingEdgeConsumed) {
                // Overlapped finalize (§Phase 8): the trailing-internal-idle group (ABCD/SBCD/
                // NBCD/MULU/MULS/CHK-success and the .L-register ALU paths) finishes with its
                // terminal np already drained during the idle, i.e. bus IDLE + queue refilled.
                // Decode the next instruction in THIS mc instead of burning a dead boundary mc
                // (the +1 seam the JSON vectors can't see). Interrupts are polled at this true
                // boundary first. Bus-terminal instructions finalize with their last bus cycle
                // still in flight (bus busy) → this never fires for them (verified: MOVECCR
                // trace stays bit-identical to vAmiga). Disabled by the single-instruction JSON
                // harness (`seamOverlapEnabled=false`): no next instruction to overlap there.
                if (seamOverlapEnabled && instructionFinishedThisCycle
                        && currentInstruction == null && cpuState == CpuState.NORMAL
                        && busCycleState == BusCycleState.IDLE && isBusQueueEmpty()
                        && currentMicroTask == InternalTask.NONE
                        && internalCyclesRemaining == 0 && irdValid && !iackInProgress) {
                    checkInterrupts();
                    if (iackInProgress) return;
                    risingEdgeConsumed = false;
                    // fall through to Instruction Start
                } else {
                    return;
                }
            }
        }

        // --- Instruction Start ---
        if (currentInstruction == null && cpuState == CpuState.NORMAL) {
            if (internalCyclesRemaining > 0) return;

            if (!irdValid) { startPrefetchCycle(); return; }

            // Check for OpenDevice("trackdisk.device")
            if (tracePending) {
                triggerTraceException();
                tracePending = false;
                // FIX #24 (§S4 stop5, oracolo [ADR]): il trace-dopo-istruzione in Moira è
                // processato al loop-top SENZA costo di boundary — la SYNC(4) parte nel
                // clock stesso in cui l'istruzione tracciata è finita. Il nostro trigger
                // consumava questo mc E POI contava 4 idle → primo push dello stack a
                // np2+10 sc invece di np2+8 (misurato: push cck 119/180 invece di 118/179,
                // +1 CCK uniforme su OGNI trace-entry da istruzione). Il mc corrente conta
                // come primo clock della SYNC(4). Solo questo call-site (boundary): i path
                // fireStopTrace (stop2/4) e STOPPED restano invariati.
                if (currentMicroTask == InternalTask.TRACE_EXCEPTION) {
                    if (microTaskStep == 1 && internalCyclesRemaining >= 1) {
                        internalCyclesRemaining--;
                    } else if (microTaskStep == 0) {
                        idleCycles(3);
                        microTaskStep = 1;
                    }
                }
                return;
            }

            // WinUAE (m68k_run_1_ce): al ritiro di ogni istruzione `ipl[0] = ipl[1];
            // ipl[1] = 0`. Qui = avvio della successiva, dopo il checkInterrupts del
            // boundary e prima che i prefetch di questa istruzione ri-campionino.
            polledIpl = pendingIpl; pendingIpl = 0;
            iplCandValid = false;
            iplAnchorDeclared = false;
            iplNowDeclaredCycle = Long.MIN_VALUE;
            iplPollValid = false;

            ir = ird & 0xFFFF;
            currentOpcode = ir;
            opcodeAddress = irdAddr;
            extensionsConsumed = 0;
            currentInstruction = decodeTable[ir];
            instructionFinishedThisCycle = false;
            if (currentInstruction == null) {
                // Group-1 da DECODE (Illegal/Line-A/Line-F): Moira execException(ILLEGAL) =
                // SYNC(4)+writeStackFrame0000(pc-2)+jumpToVector, IDENTICO a PRIVILEGE. Ma il
                // ramo priv (da executeStep) entra con risingEdgeConsumed=false → triggerException
                // fa il SYNC(4) sull'edge CORRENTE; l'illegal-da-decode invece faceva `return`
                // qui senza resettare risingEdgeConsumed (lasciato true dalla finalize dell'istr.
                // precedente), quindi triggerException prendeva il ramo ELSE (microTaskStep=0) e
                // il SYNC(4) slittava all'edge SUCCESSIVO (processExceptionGroup1_2 case 0) = +1
                // edge di fase vs Moira/privilege. Come per un'istruzione normale (vedi
                // risingEdgeConsumed=false a ~riga 833 prima di executeStep), resettiamo qui così
                // l'entry Group-1 parte sull'edge del decode = simmetrico al privilege. Chiude lo
                // sfasamento del dither synccpu sui test illegal (BKPT/MOVECCR-from-CCR, vettore 4).
                risingEdgeConsumed = false;
                int line = (ir >> 12) & 0xF;
                if (line == 0xA) triggerLineAException();
                else if (line == 0xF) triggerLineFException();
                else triggerIllegalInstruction();
                return;
            }

            instructionStep = 0;
            inInstructionBoundary = false;
            group0PushedPC = 0;
            group0PushedPCValid = false;
            group0EaMode = -1;
            group0EaSize = 2;
            group0EaDeltaOverride = Integer.MIN_VALUE;
            group0IrOverride = -1;
            group0PreFaultSR = -1;
            group0SavedSRValid = false;
            group0PreFaultAnReg = -1;
            tracePending = isTraceEnabled();

            // Invalida tutti gli slot tempRegs dell'istruzione precedente: una sola
            // scrittura al posto dei 32 store del vecchio Arrays.fill (vedi
            // tempRegsWritten). Una lettura non preceduta da scrittura torna 0,
            // com'era prima.
            tempRegsWritten = 0;

            int _pc = opcodeAddress & 0xFFFFFF;

            // OVERLAPPED PREFETCH (Phase 1, core fix): a freshly-decoded instruction must run
            // its first executeStep on THIS rising edge. The previous instruction's
            // finalizeInstructionSafe() leaves risingEdgeConsumed=true, and this Instruction
            // Start path (unlike the Instruction Execution path above) never reset it — so any
            // instruction whose executeStep begins with `if (isRisingEdgeConsumed()) return`
            // (every step-machine instruction) PARKED for 1 dead mc at the seam, while MOVEQ/NOP
            // (no such guard) did not. Resetting here makes back-to-back chip-bus cycles 4 mc
            // (seam 0), matching MOVEQ. JSON vectors are unaffected (each runs one instruction
            // from a fresh reset, where the flag is already false). See
            // docs/cpu-overlapped-prefetch-rework-plan.md §Phase 1.
            risingEdgeConsumed = false;

            currentInstruction.prepareInstruction(this);
            currentInstruction.executeStep(this);

        }

        // --- Post-Step Prefetch ---
        if (busCycleState == BusCycleState.IDLE && !busLocked && currentMicroTask == InternalTask.NONE && cpuState == CpuState.NORMAL) {
            if (instructionFinishedThisCycle) return;
            if (currentInstruction == null || !currentInstruction.shouldInhibitPrefetch()) {
                startPrefetchCycle();
            }
        }
    }

    void advanceBusCycleFalling() {
        switch (busCycleState) {
            case S1 -> { busReq.as = true; if (!currentBusOp.read) { busReq.uds = currentBusOp.uds; busReq.lds = currentBusOp.lds; } busCycleState = BusCycleState.S2; }
            case S3 -> busCycleState = BusCycleState.S4; case S5 -> busCycleState = BusCycleState.S6;
            case S7 -> { if (currentBusOp.type == BusOpType.RMW_READ) { busReq.uds = false; busReq.lds = false; } else busReq.clear(); completeBusCycle(); }
            default -> {}
        }
    }
    void advanceBusCycleRising() {
        switch (busCycleState) {
            case S0 -> { applyBusSignalsS0(currentBusOp); busCycleState = BusCycleState.S1; }
            case S2 -> { if (currentBusOp.read) { busReq.uds = currentBusOp.uds; busReq.lds = currentBusOp.lds; } pollIpl(currentBusOp, 1); busCycleState = BusCycleState.S3; }
            case S4 -> { pollIpl(currentBusOp, 2); if (busResp.vpa) { busCycleState = BusCycleState.S_VMA; vmaStartedHigh = false; } else if (busResp.ack || busResp.berr) busCycleState = BusCycleState.S5; }
            case S_VMA -> { if (!busReq.vma) { if (eClockCounter >= 0 && eClockCounter <= 5) { busReq.vma = true; vmaStartedHigh = false; } } else { if (eClockCounter >= 6 && eClockCounter <= 9) vmaStartedHigh = true; else if (vmaStartedHigh && eClockCounter >= 0 && eClockCounter <= 5) busCycleState = BusCycleState.S5; } }
            case S6 -> { pollIpl(currentBusOp, 3); latchBusData(currentBusOp); busCycleState = BusCycleState.S7; }
            default -> {}
        }
    }
    private void applyBusSignalsS0(BusOp op) { busReq.address = op.address; busReq.sizeBytes = op.size; busReq.read = op.read; busReq.fc = op.fc; busReq.instruction = (op.type == BusOpType.INSTRUCTION_FETCH); busReq.iack = (op.type == BusOpType.INTERRUPT_ACK); busReq.dataOut = op.dataOut; if (!op.read) { busReq.uds = op.uds; busReq.lds = op.lds; }
        // Un nuovo accesso: l'ancora del POLL non è (ancora) questo ciclo bus.
        iplAnchorIsCurrentOp = false; iplAnchorHadWait = false;
        // POLL_IPL: campiona il pin IPL all'avvio di ogni prefetch. Il valore usato al
        // boundary è quello dell'ultima prefetch dell'istruzione (~2 CCK prima), come Moira.
        pollIpl(op, 0); }
    /**
     * Applica l'override di {@code wait_cpu_cycle_read/write}: il POLL ancorato a
     * questo accesso vale il pin dell'ultimo ciclo di attesa, non quello dell'S0.
     */
    private void commitIplWaitOverride() {
        int v = iplWaitIpl0;
        if (iplWaitOnCandidate) iplCandNow = v; else iplNowIpl = v;
        iplAnchorHadWait = false; iplAnchorIsCurrentOp = false;
    }
    /**
     * Semantica dell'ancora, con i nomi delle due funzioni di WinUAE
     * ({@code newcpu.cpp}) che l'istruzione può emettere.
     */
    public enum IplAnchor {
        /** {@code ipl_fetch_now()}: il pin vale così com'è in questo istante. */
        NOW,
        /**
         * {@code ipl_fetch_next()}: valgono le soglie 4/2 sull'anzianità del cambio di pin,
         * quindi l'interrupt è preso a fine istruzione solo se il pin era già cambiato
         * 4 clock prima di qui; altrimenti scivola all'istruzione successiva.
         */
        NEXT
    }

    /**
     * <b>L'istruzione dichiara che il pin IPL si campiona QUI.</b>
     *
     * <p>È il modello di WinUAE trascritto dov'è: gencpu non ha una regola sola per tutte le
     * istruzioni — emette {@code ipl_fetch_now()}/{@code ipl_fetch_next()} in un punto
     * <b>scelto dentro il {@code case} della singola istruzione</b> ({@code set_ipl()},
     * {@code set_ipl_now()}, {@code check_ipl_next()}, …). Da noi la controparte naturale è
     * la macchina a passi dell'istruzione, che è la stessa sequenza: chi conosce il punto è
     * la classe, e lo dice chiamando questo metodo lì.
     *
     * <p>Dichiarare <b>congela</b> l'ancora — equivale a {@code ipl_fetched >= 2}, che fa
     * uscire subito {@code check_ipl()}: nessun accesso successivo dell'istruzione la sposta
     * più. Le istruzioni che <b>non</b> dichiarano nulla ricadono sul percorso di default di
     * gencpu, {@code check_ipl()} chiamata da {@code irc2ir()} — vedi {@link #pollIpl}.
     *
     * @param mode {@link IplAnchor#NOW} o {@link IplAnchor#NEXT}, cioè quale delle due
     *             funzioni WinUAE emette in quel punto.
     */
    public void markIplAnchor(IplAnchor mode) {
        iplAnchorDeclared = true;
        iplAnchorIsCurrentOp = false;
        iplAnchorHadWait = false;
        // `ipl_fetch_now()` scrive regs.ipl_evt, `ipl_fetch_next()` no: solo la prima
        // apre la finestra dell'override sui wait-state, e solo per l'accesso che
        // comincia in QUESTO ciclo. Vedi pollIpl().
        iplNowDeclaredCycle = (mode == IplAnchor.NOW) ? totalCycles : Long.MIN_VALUE;
        iplPollValid = true;
        iplPollOnFetch = (mode == IplAnchor.NEXT);
        iplNowIpl = iplPin;                                   // ipl_fetch_now()
        recordIplFetchNext();                                 // ipl_fetch_next()
    }

    /** Campiona il pin IPL se questo è lo stato-bus configurato come punto di POLL. */
    private void pollIpl(BusOp op, int state) {
        boolean fetch = op.type == BusOpType.INSTRUCTION_FETCH;
        if (state != IPL_POLL_STATE) return;
        if (op.type == BusOpType.INTERRUPT_ACK) return;
        // L'istruzione ha già dichiarato la sua ancora (markIplAnchor): in WinUAE è
        // `ipl_fetched >= 2` e check_ipl() esce subito. Nessun accesso la muove più —
        // tranne l'override sui wait-state, che WinUAE applica in coda all'accesso
        // (`wait_cpu_cycle_read/write`: `if (now == regs.ipl_evt) regs.ipl[0] = ipl;`) e
        // che quindi vale anche qui, purché `ipl_fetch_now()` sia stata emessa nello
        // STESSO ciclo in cui l'accesso comincia — com'è in `op_9108_14_ff`
        // (`ipl_fetch_now(); x_get_byte(srca);`).
        if (iplAnchorDeclared) {
            if (!fetch && iplNowDeclaredCycle == totalCycles) {
                iplAnchorIsCurrentOp = true;
                iplWaitOnCandidate = false;
                iplWaitIpl0 = iplWaitIpl1 = busResp.ipl & 0x7;
            }
            return;
        }
        // ── Percorso di DEFAULT: check_ipl(), che gencpu chiama da irc2ir() ──────────
        // `irc2ir()` è il «IR ← IRC» che precede una prefetch di REFILL, non una fetch di
        // parola d'estensione, e la sua guardia `ir2irc` la rende una sola per refill.
        // L'ancora è quindi decisa **alla prefetch di refill** ed è:
        //   • l'ultimo accesso dati emesso prima di lei (`last_access_offset_ipl_prev`,
        //     ramo `now`);
        //   • se non ce n'è, la prefetch stessa (`set_last_access_ipl()`, ramo `next`).
        // Quale prefetch sia la terminale si sa solo alla fine, quindi qui si ridecide a
        // ogni prefetch e vince l'ultima: è lo stesso risultato, perché una decisione
        // presa a una prefetch intermedia viene comunque riscritta da quella finale.
        //
        // ⛔ Le due proprietà che ne discendono, ed entrambe sono state un bug:
        //   1. una fetch di ESTENSIONE non decide niente e soprattutto non congela nulla.
        //      `op_1150_14_ff` (`MOVE.B (An),(d16,An)`) è nr np(ext) nw np: l'ancora è la
        //      SCRITTURA, a 8 clock dalla fine, non la lettura a 16;
        //   2. dopo l'ultima prefetch non c'è più nessuna check_ipl(), quindi una
        //      scrittura in coda aggiorna il candidato ma NON si prende l'ancora — è il
        //      caso degli ALU con destinazione in memoria, `op_0010_14_ff`
        //      (`OR.B #imm,(An)`) = np nr np nw, dove l'ancora resta sulla lettura.
        if (fetch) {
            iplPollValid = true;
            if (iplCandValid) {
                iplPollOnFetch = false;                       // ramo `now`
                iplNowIpl = iplCandNow;
            } else {
                iplPollOnFetch = true;                        // ramo `next`
                iplNowIpl = iplPin;                           // ipl_fetch_now()
                recordIplFetchNext();                         // ipl_fetch_next()
            }
            return;
        }
        // Accesso DATI = `set_last_access_ipl_prev()`: solo un candidato. WinUAE inserisce
        // `ipl_fetch_now()` SUBITO PRIMA dell'accesso, quindi `regs.ipl_evt == now` e
        // l'override sui wait-state riguarda questo caso (un'ancora-prefetch usa
        // ipl_fetch_next, che non tocca ipl_evt).
        iplAnchorIsCurrentOp = true;
        iplWaitOnCandidate = true;
        iplWaitIpl0 = iplWaitIpl1 = busResp.ipl & 0x7;
        iplCandValid = true;
        iplCandNow = iplPin;
    }
    /**
     * Esito di `ipl_fetch_next()` (WinUAE newcpu.cpp): confronta l'anzianità del
     * cambio di pin con le soglie 4/2.
     *
     * @return il valore da mettere in {@code ipl[0]}, oppure {@code -1} se il cambio
     *         è troppo fresco e va DIFFERITO in {@code ipl[1]}.
     */
    private int iplFetchNextValue() {
        long cd = totalCycles - iplPinChangeCycle;
        long cdp = totalCycles - iplPinChangeCycleP;
        if (cd >= IPL_DELAY4) return iplPin;
        if (cdp >= IPL_DELAY2) return iplPinPrev;
        return -1;
    }
    /** Registra l'esito di `ipl_fetch_next()` nei candidati risolti al boundary. */
    private void recordIplFetchNext() {
        int v = iplFetchNextValue();
        if (v < 0) { iplNextPending = iplPin; iplNextDefer = true; }
        else { iplNextIpl = v; iplNextPending = 0; iplNextDefer = false; }
    }
    /**
     * `ipl_fetch_next()` in coda all'INGRESSO IN ECCEZIONE (WinUAE
     * {@code Exception_ce000}): va chiamata dopo i 2 clock idle che seguono la prima
     * prefetch di refill e prima della seconda, cioè 4 clock prima della fine
     * dell'entry. Sovrascrive l'ancora lasciata dall'ultimo accesso dati dell'entry
     * (lettura del vettore) e ne cambia la semantica da `now` a `next`, che è
     * l'unica cosa che WinUAE emette qui. Vedi {@link #iplExcEntryAnchor}.
     */
    private void iplFetchNextAtExceptionEntry() {
        // L'ancora non è più un accesso dati: l'override sui wait-state
        // (wait_cpu_cycle_*) non si applica a `ipl_fetch_next`, che non tocca ipl_evt.
        iplAnchorIsCurrentOp = false; iplAnchorHadWait = false;
        // È una DICHIARAZIONE come quelle di markIplAnchor: la seconda prefetch di refill,
        // che segue subito, non deve ridecidere l'ancora sul percorso di default.
        iplAnchorDeclared = true;
        iplNowDeclaredCycle = Long.MIN_VALUE;
        iplPollValid = true;
        iplPollOnFetch = true;            // ancora-prefetch → ramo `next`
        iplNowIpl = iplPin;
        recordIplFetchNext();
    }
    /**
     * Applica al boundary la selezione now/next di WinUAE (gencpu.cpp ~9498) sui
     * candidati registrati dall'ultimo POLL dell'istruzione appena terminata.
     * Ramo "now": il pin campionato vale subito (ipl[0]=pin, ipl[1]=0).
     * Ramo "next" differito: ipl[0] resta quello che era — l'interrupt scatta al
     * termine dell'istruzione SUCCESSIVA, via lo shift ipl[0]=ipl[1] all'avvio.
     */
    private void resolveIplAtBoundary() {
        if (!iplPollValid) return;
        iplPollValid = false;
        boolean now = !iplPollOnFetch;
        if (now) { polledIpl = iplNowIpl; pendingIpl = 0; }
        else if (iplNextDefer) { pendingIpl = iplNextPending; }
        else { polledIpl = iplNextIpl; pendingIpl = 0; }
    }
    private void latchBusData(BusOp op) {
        if (op.type == BusOpType.INSTRUCTION_FETCH && op.flushTag != flushCount) { prefetchPending = false; return; }
        if (!op.read) return;
        int data = busResp.dataIn & 0xFFFF;
        if (op.size == 2) {
            if (op.purpose == OpPurpose.PREFETCH_IRD) {
                ird = data; irdAddr = op.address; irdValid = true; prefetchPending = false;
            } else if (op.purpose == OpPurpose.PREFETCH_IRC) {
                irc = data; ircAddr = op.address; ircValid = true; prefetchPending = false;
                if (!irdValid) {
                    ird = irc; irdAddr = ircAddr; ircValid = false; irdValid = true;
                    if (cpuState == CpuState.NORMAL && currentInstruction != null && !currentInstruction.shouldInhibitPrefetch()) {
                        startPrefetchCycle();
                    }
                }
            } else if (op.purpose == OpPurpose.DATA_READ_LONG) {
                if (!longReadHighValid) { latchedLongHigh = data; longReadHighValid = true; }
                else { latchedLongLow = data; latchedBusData = (latchedLongHigh << 16) | (latchedLongLow & 0xFFFF); longReadHighValid = false; }
            } else latchedBusData = data;
        } else {
            if ((op.address & 1) == 0) latchedByte = (data >> 8) & 0xFF; else latchedByte = data & 0xFF;
        }
    }
    private void completeBusCycle() { if (currentBusOp.type == BusOpType.RMW_WRITE) busLocked = false; if (currentBusOp.type == BusOpType.INTERRUPT_ACK) finishIackAndStartException(); if (currentBusOp.type != BusOpType.RMW_READ) busReq.clear(); currentBusOp = null; busCycleState = BusCycleState.IDLE; BusOp next = pollBusOp(); if (next != null) startBusCycle(next); }
    void triggerAddressError(int addr, boolean isWrite, int fc, boolean isInstruction) {
        triggerAddressError(addr, isWrite, fc, isInstruction, -1);
    }

    void haltCPU() {
        cpuState = CpuState.HALTED;
        currentMicroTask = InternalTask.NONE;
        clearBusQueue();
        currentBusOp = null;
        busCycleState = BusCycleState.IDLE;
        busReq.clear();
    }

    void triggerAddressError(int addr, boolean isWrite, int fc, boolean isInstruction, int pushedPC) {
        // Double Fault check: if already in Group 0 exception or Reset sequence
        if (currentMicroTask == InternalTask.EXCEPTION_GROUP0 || processingReset) {
            haltCPU();
            return;
        }
        // On 68000, address error is not restartable and doesn't roll back registers.
        // (No register rollback / checkpoint mechanism exists — the 68000 leaves
        // partially-updated registers as-is on a Group 0 fault.)
        // If an instruction pre-armed savedSR (e.g. MOVE.L predecrement computes flags before write),
        // use that value; otherwise capture the current SR.
        if (group0SavedSRValid) {
            // savedSR already set by the instruction — keep it
            group0SavedSRValid = false;
        } else {
            savedSR = getSR();
        }
        faultAddress = addr;
        faultIR = group0IrOverride >= 0 ? group0IrOverride : ir;
        faultIsWrite = isWrite;
        faultIsInstruction = isInstruction;
        addressErrorFault = true;
        // Un Group 0 ABORTISCE l'istruzione: la trace pendente (armata dal bit T
        // all'inizio dell'istruzione) NON deve scattare, né subito né all'ingresso
        // dell'handler. Senza questo, il trace spurio veniva preso DOPO l'entry
        // dell'address error e lo scavalcava, con PC salvato = indirizzo
        // dell'handler di address error. Concordano:
        //   Moira execAddressError/execBusError: `flags &= ~State::TRACE_EXC`
        //   WinUAE exception_check_trace(): `unset_special(SPCFLAG_TRACE|SPCFLAG_DOTRACE)`
        //     e ri-arma solo per le eccezioni "interne" (DIVZERO/CHK/TRAPV/TRAP),
        //     non per bus/address error.
        tracePending = false;
        if (!group0PushedPCValid) {
            if (pushedPC != -1) {
                group0PushedPC = pushedPC;
            } else if (faultIsInstruction) {
                // For instruction fetch faults (opcode or extensions), use the faulting address
                group0PushedPC = faultAddress;
            } else {
                group0PushedPC = opcodeAddress + group0DataFaultPcOffset();
            }
            group0PushedPCValid = true;
        }
        faultFC = fc;
        cpuState = CpuState.EXCEPTION;
        currentMicroTask = InternalTask.EXCEPTION_GROUP0;
        if (!isSupervisor()) writeSR(getSR() | 0x2000, true);
        currentInstruction = null;
        instructionStep = 0;

        if (busCycleState == BusCycleState.IDLE && !risingEdgeConsumed) {
            if (exceptionStartsPrefetch) {
                startPrefetchCycle();
                risingEdgeConsumed = true;
            } else {
                idleCycles(4);
            }
            microTaskStep = 1;
        } else {
            microTaskStep = 0;
        }
    }
    void triggerIllegalInstruction() { triggerException(4, opcodeAddress); }
    private void triggerLineAException() { triggerException(10, opcodeAddress); }
    private void triggerLineFException() { triggerException(11, opcodeAddress); }
    public void triggerException(int vector, int nextPc) {
        savedSR = getSR();
        exceptionVector = vector;
        if (vector >= 0 && vector < 16) diagExceptionCounts[vector]++;
        // Group 1 (Illegal, Privilege, Line A/F) save opcodeAddress.
        // Group 2 (Divide by Zero, CHK, TRAPV, TRAP) and Trace save nextPc.
        if (vector == 4 || vector == 8 || vector == 10 || vector == 11) {
            exceptionPC = opcodeAddress;
            // Un'istruzione che ABORTISCE (illegal, privilege, line A/F) non completa,
            // quindi NON deve generare un'eccezione di trace. tracePending era stato
            // impostato all'inizio dell'istruzione; va annullato, altrimenti all'ingresso
            // dell'handler scatterebbe una trace spuria. Bug visibile nei giochi che
            // combinano single-step (bit T) e istruzioni privilegiate (protezioni).
            tracePending = false;
        } else {
            exceptionPC = nextPc;
        }
        // Seam overlapped-prefetch: a Group-1 exception (Illegal/Privilege/Line-A/F)
        // ABORTS the faulting instruction and discards the prefetch queue. In the
        // overlapped model the queue-fill prefetch of the previous instruction can
        // still be in-flight (busState=S1) at the fault boundary — its data is
        // discarded by the exception anyway. If we let the exception task serially
        // wait for that speculative fetch to finish (processInternalTask bails while
        // the bus is busy) the exception entry costs +2 CCK vs Moira, whose fixed
        // 34-cycle privilege exception starts with the bus idle (the previous RTE's
        // prefetches complete before the privileged opcode is decoded). Abandon the
        // in-flight *instruction* fetch (not a data access) so the exception starts
        // immediately — this is what re-executing `stop #$0000` in user mode does in
        // the vAmigaTS STOP priv-loop (stop3/stop4): our loop period was 45 CCK vs
        // vAmiga's 43. Only for aborting Group-1 vectors (the queue is discarded).
        if ((vector == 4 || vector == 8 || vector == 10 || vector == 11)
                && busCycleState != BusCycleState.IDLE
                && currentBusOp != null
                && currentBusOp.type == BusOpType.INSTRUCTION_FETCH) {
            busReq.clear();
            currentBusOp = null;
            busCycleState = BusCycleState.IDLE;
            clearBusQueue();
        }
        cpuState = CpuState.EXCEPTION;
        currentMicroTask = InternalTask.EXCEPTION_GROUP1_2;
        if (!isSupervisor()) writeSR(getSR() | 0x2000, true);
        currentInstruction = null;
        instructionStep = 0;

        if (busCycleState == BusCycleState.IDLE && !risingEdgeConsumed) {
            if (exceptionStartsPrefetch) {
                startPrefetchCycle();
            } else {
                idleCycles(4);
            }
            microTaskStep = 1;
        } else {
            microTaskStep = 0;
        }
    }
    void triggerPrivilegeViolation() {
        triggerException(8, opcodeAddress);
    }
    private void triggerTraceException() {
        // Il 68000 salva il PC della PROSSIMA istruzione da eseguire (quella dopo quella tracciata).
        // getPC() restituisce irdAddr quando IRD è valido, che è esattamente l'indirizzo corretto.
        // Usare `pc` è sbagliato perché punta 2-4 byte oltre la coda di prefetch.
        triggerException(9, getPC());
        currentMicroTask = InternalTask.TRACE_EXCEPTION;
    }

    private void handleBusError() {
        if (currentMicroTask == InternalTask.EXCEPTION_GROUP0 || processingReset) {
            haltCPU();
            return;
        }
        savedSR = getSR();
        if (currentBusOp != null) {
            faultAddress = currentBusOp.address;
            faultIR = ir;
            faultIsWrite = !currentBusOp.read;
            faultIsInstruction = currentBusOp.type == BusOpType.INSTRUCTION_FETCH;
            addressErrorFault = false;
            faultFC = currentBusOp.fc;
        }
        diagExceptionCounts[addressErrorFault ? 3 : 2]++; // diagnostica bus/address error
        tracePending = false; // istruzione abortita: nessuna trace (vedi triggerException)
        cpuState = CpuState.EXCEPTION;
        currentMicroTask = InternalTask.EXCEPTION_GROUP0;
        if (!isSupervisor()) writeSR(getSR() | 0x2000, true);
        microTaskStep = 0;
        busCycleState = BusCycleState.IDLE;
        currentBusOp = null;
        clearBusQueue();
    }

    private void processResetSequence() {
        if (busCycleState != BusCycleState.IDLE) return;
        switch (microTaskStep) {
            case 0 -> { scheduleReadWord(0, OpPurpose.RESET_VECTOR); microTaskStep++; }
            case 1 -> { tempResetSSP = (latchedBusData & 0xFFFF) << 16; scheduleReadWord(2, OpPurpose.RESET_VECTOR); microTaskStep++; }
            case 2 -> {
                ssp = tempResetSSP | (latchedBusData & 0xFFFF); setA7(ssp); scheduleReadWord(4, OpPurpose.RESET_VECTOR); microTaskStep++;
            }
            case 3 -> { tempResetPC = (latchedBusData & 0xFFFF) << 16; scheduleReadWord(6, OpPurpose.RESET_VECTOR); microTaskStep++; }
            case 4 -> {
                pc = tempResetPC | (latchedBusData & 0xFFFF); flushPrefetch(); microTaskStep++; startPrefetchCycle();
            }
            case 5 -> { if (irdValid) { microTaskStep++; startPrefetchCycle(); } }
            case 6 -> {
                if (ircValid) {
                    processingReset = false; cpuState = CpuState.NORMAL; currentMicroTask = InternalTask.NONE; inInstructionBoundary = true;
                }
            }
        }
    }
    private void processInternalTask() { if (busCycleState != BusCycleState.IDLE) return; switch (currentMicroTask) { case EXCEPTION_GROUP0 -> processExceptionGroup0(); case EXCEPTION_GROUP1_2 -> processExceptionGroup1_2(); case EXCEPTION_INTERRUPT -> processExceptionInterrupt(); case TRACE_EXCEPTION -> processTraceException(); default -> {} } }
    private void processExceptionGroup0() {
        if (busCycleState != BusCycleState.IDLE) return;
        switch (microTaskStep) {
            case 0 -> {
                if (exceptionStartsPrefetch) {
                    startPrefetchCycle();
                } else {
                    idleCycles(4);
                }
                microTaskStep++;
                return;
            }
            case 1 -> {
                setA7(getA7() - 2);
                if ((getA7() & 1) != 0) { haltCPU(); return; }
                scheduleWriteWord(getA7(), group0PushedPC & 0xFFFF); microTaskStep++; // PC Low at SSP-2
            }
            case 2 -> {
                setA7(getA7() - 4); // Move to SSP-6 for SR
                scheduleWriteWord(getA7(), savedSR); microTaskStep++;
            }
            case 3 -> {
                setA7(getA7() + 2); // Move back to SSP-4 for PC High
                scheduleWriteWord(getA7(), (group0PushedPC >>> 16) & 0xFFFF); microTaskStep++;
            }
            case 4 -> {
                setA7(getA7() - 4); // Move down to SSP-8 for IR
                scheduleWriteWord(getA7(), faultIR); microTaskStep++;
            }
            case 5 -> {
                setA7(getA7() - 2); // SSP-10: Access Addr Low
                scheduleWriteWord(getA7(), faultAddress & 0xFFFF); microTaskStep++;
            }
            case 6 -> {
                setA7(getA7() - 4); // SSP-14: SSW
                int ssw = 0;
                if (!faultIsWrite) ssw |= 0x10;
                if (faultIsInstruction) ssw |= 0x08;
                ssw |= (faultFC & 0x07);
                // Sul 68000 i bit 15-5 dell'SSW contengono i bit alti del bus dati
                // dell'ultimo ciclo fallito (per address error coincide con faultIR).
                ssw |= (faultIR & 0xFFE0);
                scheduleWriteWord(getA7(), ssw);
                microTaskStep++;
            }
            case 7 -> {
                setA7(getA7() + 2); // SSP-12: Access Addr High
                scheduleWriteWord(getA7(), (faultAddress >>> 16) & 0xFFFF);
                microTaskStep++;
            }
            case 8 -> {
                setA7(getA7() - 2); // SSP-14: fondo del frame
                if (group0PreFaultSR != -1) { sr = group0PreFaultSR & 0xA71F; syncFlagsFromSR(); group0PreFaultSR = -1; }
                if (group0PreFaultAnReg != -1) { if (group0PreFaultAnReg == 7) setA7(group0PreFaultAnValue); else a[group0PreFaultAnReg] = group0PreFaultAnValue; group0PreFaultAnReg = -1; }
                sr &= ~0x8000;
                exceptionVector = addressErrorFault ? 3 : 2;
                scheduleReadWord(exceptionVector * 4, OpPurpose.EXCEPTION_VECTOR);
                microTaskStep++;
            }
            case 9 -> { tempExceptionVectorHigh = (latchedBusData & 0xFFFF) << 16; scheduleReadWord((exceptionVector * 4) + 2, OpPurpose.EXCEPTION_VECTOR); microTaskStep++; }
            case 10 -> { pc = tempExceptionVectorHigh | (latchedBusData & 0xFFFF); flushPrefetch(); microTaskStep++; startPrefetchCycle(); }
            case 11 -> { if (irdValid) { idleCycles(2); microTaskStep++; return; } }
            case 12 -> {
                iplFetchNextAtExceptionEntry(); // WinUAE Exception_ce000: prima di np2
                startPrefetchCycle();
                cpuState = CpuState.NORMAL;
                currentMicroTask = InternalTask.NONE;
                // BUG FIX: usare setInstructionFinished() per aggiornare anche lastFinishedPC,
                // in linea con tutti gli altri handler di eccezione.
                setInstructionFinished(true);
                exceptionStartsPrefetch = false;
            }
        }
    }
    private void processExceptionGroup1_2() {
        if (busCycleState != BusCycleState.IDLE) return;
        switch (microTaskStep) {
            case 0 -> {
                if (exceptionStartsPrefetch) {
                    startPrefetchCycle();
                } else {
                    idleCycles(4); // nn (2 idle bus units = 4 clocks)
                }
                microTaskStep++;
            }
            case 1 -> {
                setA7(getA7() - 2);
                if ((getA7() & 1) != 0) { haltCPU(); return; }
                scheduleWriteWord(getA7(), exceptionPC & 0xFFFF); // ns (PC Low at sp-2)
                microTaskStep++;
            }
            case 2 -> {
                setA7(getA7() - 4);
                scheduleWriteWord(getA7(), savedSR); // ns (SR at sp-6)
                microTaskStep++;
            }
            case 3 -> {
                setA7(getA7() + 2);
                scheduleWriteWord(getA7(), (exceptionPC >>> 16) & 0xFFFF); // nS (PC High at sp-4)
                microTaskStep++;
            }
            case 4 -> {
                setA7(getA7() - 2); // Leave A7 at sp-6
                sr &= ~0x8000;
                scheduleReadWord(exceptionVector * 4, OpPurpose.EXCEPTION_VECTOR); // nV (Vector High)
                microTaskStep++;
            }
            case 5 -> {
                tempExceptionVectorHigh = (latchedBusData & 0xFFFF) << 16;
                scheduleReadWord((exceptionVector * 4) + 2, OpPurpose.EXCEPTION_VECTOR); // nv (Vector Low)
                microTaskStep++;
            }
            case 6 -> {
                pc = tempExceptionVectorHigh | (latchedBusData & 0xFFFF);
                flushPrefetch();
                startPrefetchCycle(); // np (Refill 1)
                microTaskStep++;
            }
            case 7 -> {
                if (!isIRDValid()) { startPrefetchCycle(); return; }
                idleCycles(2); // n (1 idle unit = 2 clocks)
                microTaskStep++;
            }
            case 8 -> {
                // Overlapped prefetch (Phase 5): np (Refill 2) is the last bus cycle — finalize
                // in this same step (like the Group-0 handler) so the handler's first
                // instruction decodes the instant np2 drains (seam 0), instead of a separate
                // step 9 (which wasted 1 mc of exception-entry latency). See
                // docs/cpu-overlapped-prefetch-rework-plan.md §Phase 5.
                iplFetchNextAtExceptionEntry(); // WinUAE Exception_ce000: prima di np2
                startPrefetchCycle(); // np (Refill 2)
                cpuState = CpuState.NORMAL;
                currentMicroTask = InternalTask.NONE;
                setInstructionFinished(true);
                exceptionStartsPrefetch = false;
                acknowledgedIpl = 0;
            }
            case 9 -> {
                if (!isIRCValid()) { startPrefetchCycle(); return; }
                cpuState = CpuState.NORMAL;
                currentMicroTask = InternalTask.NONE;
                setInstructionFinished(true);
                // Reset flags for next exception
                exceptionStartsPrefetch = false;
                acknowledgedIpl = 0;
            }
        }
    }
    private void processExceptionInterrupt() {
        if (busCycleState != BusCycleState.IDLE) return;
        switch (microTaskStep) {
            // vAmiga-exact interrupt-entry timing (Moira execInterrupt, C68000): total 44 mc
            // with the 3-word stack frame written at mc offsets 6 / 18 / 22 and the vector
            // read at 26-34. The two idle insertions below reproduce vAmiga's
            // SYNC(6) lead-in and the SYNC(4)+SYNC(4) gap between the PC-low write and the
            // SR write — without them our entry was ~9 mc short (writes back-to-back), so the
            // bbusy bars (driven by interrupt latency → BLTSIZE) landed ~5-8 CCK too far left.
            // See docs/agnus-cpu-contention-rework-plan.md §B10.
            case 0 -> {
                if (!isSupervisor()) writeSR(getSR() | 0x2000, true);
                idleCycles(2); // vAmiga SYNC(6) lead-in (IACK cycle already provided 4 mc)
                microTaskStep++;
            }
            case 1 -> {
                // BUG FIX: stack write order must match real 68000 microcode:
                // ns  → PC low  at [SP-2]
                // ns  → SR      at [SP-6]  (skip 4 bytes downward)
                // nS  → PC high at [SP-4]  (step back up 2 bytes)
                // A7 is left pointing at [SP-6], the bottom of the 6-byte frame.
                setA7(getA7() - 2);
                if ((getA7() & 1) != 0) { haltCPU(); return; }
                scheduleWriteWord(getA7(), exceptionPC & 0xFFFF); // ns: PC low at sp-2
                microTaskStep++;
            }
            case 2 -> { idleCycles(8); microTaskStep++; } // vAmiga SYNC(4)[IACK]+SYNC(4): gap before SR write
            case 3 -> {
                setA7(getA7() - 4);
                scheduleWriteWord(getA7(), savedSR); // ns: SR at sp-6
                microTaskStep++;
            }
            case 4 -> {
                setA7(getA7() + 2);
                scheduleWriteWord(getA7(), (exceptionPC >>> 16) & 0xFFFF); // nS: PC high at sp-4
                microTaskStep++;
            }
            case 5 -> {
                setA7(getA7() - 2); // leave A7 at sp-6 (bottom of frame)
                // Clear trace bit; set interrupt mask to the acknowledged IPL level
                sr &= ~0x8000;
                sr = (sr & 0xF8FF) | (acknowledgedIpl << 8);
                scheduleReadWord(exceptionVector * 4, OpPurpose.EXCEPTION_VECTOR); // nV: vector high
                microTaskStep++;
            }
            case 6 -> { tempExceptionVectorHigh = (latchedBusData & 0xFFFF) << 16; scheduleReadWord((exceptionVector * 4) + 2, OpPurpose.EXCEPTION_VECTOR); microTaskStep++; } // nv: vector low
            case 7 -> { pc = tempExceptionVectorHigh | (latchedBusData & 0xFFFF); flushPrefetch(); startPrefetchCycle(); microTaskStep++; } // np: first prefetch (IRD)
            case 8 -> {
                // BUG FIX: must retry startPrefetchCycle() if the first prefetch stalled,
                // and must add the mandatory 'n' idle bus cycle before the second prefetch,
                // matching the np→n→np timing sequence of all other exception handlers.
                if (!irdValid) { startPrefetchCycle(); return; }
                idleCycles(2); // n: one idle bus cycle (2 clocks)
                microTaskStep++;
            }
            case 9 -> {
                // Overlapped prefetch (Phase 5): np2 is the last bus cycle of interrupt entry —
                // finalize in this same step (seam 0) so the ISR's first instruction decodes the
                // instant np2 drains, instead of a separate step (wasted 1 mc of interrupt
                // latency, which the bbusy bars depend on). See the plan doc §Phase 5.
                iplFetchNextAtExceptionEntry(); // WinUAE Exception_ce000: prima di np2
                startPrefetchCycle(); // np: second prefetch (IRC)
                cpuState = CpuState.NORMAL;
                currentMicroTask = InternalTask.NONE;
                inInstructionBoundary = true;
                instructionFinishedThisCycle = true;
                acknowledgedIpl = 0;
                return;
            }
            case 10 -> {
                // BUG FIX: wait for the second prefetch (IRC) to complete, retrying if needed.
                if (!ircValid) { startPrefetchCycle(); return; }
                cpuState = CpuState.NORMAL;
                currentMicroTask = InternalTask.NONE;
                inInstructionBoundary = true;
                instructionFinishedThisCycle = true;
                acknowledgedIpl = 0;
            }
        }
    }
    private void processTraceException() { processExceptionGroup1_2(); }
    /**
     * Un giro del loop di STOP di WinUAE (`m68k_run_1_ce` che ri-dispatcha
     * `op_4e72_13_ff`). La griglia è di 4 clock CPU, ancorata all'ingresso in
     * STOPPED = l'istante in cui l'istruzione STOP chiama `ipl_fetch_next()`.
     */
    private void serviceStopRound() {
        if (((totalCycles - stopRoundAnchor) & 3) != 0) return;
        boolean firstRound = (totalCycles == stopRoundAnchor);
        if (!firstRound) {
            // FINE del round precedente: `if (regs.ipl[0] > 0) do_specialties(0);`
            stoppedRecogIpl = stopIpl0;
            // `regs.ipl[0] = regs.ipl[1]; regs.ipl[1] = 0;` (ritiro del "giro")
            stopIpl0 = stopIpl1;
            stopIpl1 = 0;
        }
        // INIZIO del round successivo: `ipl_fetch_next()`.
        int v = iplFetchNextValue();
        if (v < 0) stopIpl1 = iplPin;
        else { stopIpl0 = v; stopIpl1 = 0; }
        if (!firstRound) checkInterrupts();
    }
    private void checkInterrupts() {
        // Selezione now/next di WinUAE: si può fare solo qui, al boundary, perché solo
        // qui è nota la distanza POLL→fine istruzione (vedi resolveIplAtBoundary).
        if (cpuState == CpuState.NORMAL) resolveIplAtBoundary();
        // Path NORMAL (boundary istruzione): usa il valore campionato al punto POLL
        // (ultima prefetch), come Moira. Path STOPPED: usa il valore ritardato dalla
        // pipe IPL (Moira POLL_IPL; sync(2) → riconosce sul poll precedente), NON il
        // pin live — vedi il ramo STOPPED di risingEdge e stoppedRecogIpl.
        int ipl = (cpuState == CpuState.NORMAL) ? polledIpl
                : (cpuState == CpuState.STOPPED ? stoppedRecogIpl : latchedIpl);
        // Il livello 7 è NMI ma richiede edge-detection: viene accettato solo se
        // level7EdgeArmed è true (IPL è sceso sotto 7 da quando è stato servito l'ultimo NMI).
        // Gli altri livelli devono semplicemente superare la maschera corrente.
        if (ipl == 7 && !level7EdgeArmed) return;
        if (ipl > getIntMask() || ipl == 7) {
            if (!iackInProgress && ipl != 0 && cpuState != CpuState.EXCEPTION) {
                savedSR = getSR();
                iackInProgress = true;
                acknowledgedIpl = ipl;
                // Consuma l'edge per il livello 7 — non verrà riaccettato finché IPL non scende.
                if (ipl == 7) level7EdgeArmed = false;
                enqueueBusOp(createInterruptAckOp(ipl));
            }
        }
    }
    private void finishIackAndStartException() {
        iackInProgress = false;
        if (busResp.berr) exceptionVector = 24;
        else if (busResp.iackAutovector || busResp.vpa) exceptionVector = 24 + acknowledgedIpl;
        else if (busResp.iackVectorProvided) exceptionVector = busResp.iackVectorNumber & 0xFF;
        else exceptionVector = latchedBusData & 0xFF;
        exceptionPC = getPC();
        cpuState = CpuState.EXCEPTION;
        currentMicroTask = InternalTask.EXCEPTION_INTERRUPT;
        microTaskStep = 0;
    }
    public int currentFCForInstruction() { return isSupervisor() ? FC_SUPERVISOR_PROGRAM : FC_USER_PROGRAM; }
    public int currentFCForData() { return isSupervisor() ? FC_SUPERVISOR_DATA : FC_USER_DATA; }
    public int getLatchedBusData() { return latchedBusData; }
    public int getLatchedByte() { return latchedByte; }
    public int getIRC() { return irc; }
    public int getEClockCounter() { return eClockCounter; }
    public int getIRD() { return ird; }
    public int getIRCAddr() { return ircAddr; }
    public int getIRDAddr() { return irdAddr; }
    public BusCycleState getBusCycleState() { return busCycleState; }
    public int getWaitCycles() { return waitCycles; }
    public boolean isBusLocked() { return busLocked; }
    /**
     * Rilascia il lock RMW e deasserisce AS SENZA eseguire la fase write: usato dal
     * write-drop di TAS su chip bus (la RMW_READ lascia busLocked=true e busReq
     * asserito in attesa della write; se la write viene persa il bus va rilasciato
     * qui, altrimenti la CPU resta bloccata per sempre). Vedi TasInstruction.
     */
    public void releaseRmwLock() { busLocked = false; busReq.clear(); }
    public boolean isIRDValid() { return irdValid; }
    public boolean isIRCValid() { return ircValid; }
    public void consumeIRC() {
        ird = irc; irdAddr = ircAddr; irdValid = ircValid; ircValid = false;
        extensionsConsumed++;
        if (cpuState == CpuState.NORMAL && (currentInstruction == null || !currentInstruction.shouldInhibitPrefetch())) {
            startPrefetchCycle();
        }
    }
    public void consumeIRD() {
        irdValid = false;
        extensionsConsumed++;
        if (cpuState == CpuState.NORMAL && (currentInstruction == null || !currentInstruction.shouldInhibitPrefetch())) {
            startPrefetchCycle();
        }
    }
    public int getIr() { return ir; }
    // =========================================================================
    // tempRegs: maschera di validità al posto dell'azzeramento per istruzione
    // =========================================================================
    //
    // PERF (P-6). All'inizio di OGNI istruzione girava `Arrays.fill(tempRegs, 0)`
    // — 32 store × ~10M istruzioni ogni 150M cicli ≈ 320M store, il 3,6 % del
    // profilo JFR. Non era però codice morto: molte istruzioni **leggono slot mai
    // scritti** e contano sullo 0 che quel fill garantiva. L'idioma ricorrente è
    //
    //     calculateEA(..., getTemp(cpu, EXT1), getTemp(cpu, EXT2), ...)
    //
    // dove Java valuta sempre gli argomenti, anche nei modi di indirizzamento
    // privi di parole di estensione (il valore viene poi ignorato da calculateEA).
    // Un rilevatore a runtime ha contato la cosa su ~30 classi istruzione: ADD,
    // ADDA, ADDI, ADDQ, AND, ANDI, ASL/ASR, BCHG/BCLR/BSET/BTST, CHK, CLR, CMP,
    // CMPA, CMPI, DIVS/DIVU, EOR, EORI, JMP, JSR, LEA, LSL/LSR, MOVE, MOVEM, …
    //
    // Invece di mettere una guardia esplicita in ognuna (decine di siti sparsi,
    // facili da sbagliare o da dimenticare), la garanzia è centralizzata qui: una
    // maschera a 32 bit segna quali slot l'istruzione corrente ha scritto, e la
    // lettura di uno slot non scritto restituisce 0 — esattamente ciò che faceva
    // il fill. L'equivalenza è **per costruzione**, per tutti gli slot e tutte le
    // istruzioni, e il costo per istruzione passa da 32 store a uno solo.
    private int tempRegsWritten;

    public int getTempReg(int index) {
        return (tempRegsWritten & (1 << index)) != 0 ? tempRegs[index] : 0;
    }

    public void setTempReg(int index, int value) {
        tempRegsWritten |= (1 << index);
        tempRegs[index] = value;
    }
    public int getInstructionStep() { return (currentMicroTask != InternalTask.NONE) ? microTaskStep : instructionStep; }
    public void setInstructionStep(int step) {
        if (cpuState == CpuState.EXCEPTION) return;
        if (currentMicroTask != InternalTask.NONE) microTaskStep = step;
        else instructionStep = step;
    }
    public void incrementInstructionStep() { setInstructionStep(getInstructionStep() + 1); }
    public boolean isProcessingReset() { return processingReset; }
    private boolean instructionFinishedThisCycle = false;
    public boolean isInstructionFinished() {
        if (cpuState == CpuState.HALTED) return true;
        return instructionFinishedThisCycle;
    }

    public int getCurrentOpcode() { return currentOpcode & 0xFFFF; }
    public void setInstructionFinished(boolean v) {
        this.instructionFinishedThisCycle = v;
        if (v) {
            this.currentInstruction = null;
            this.inInstructionBoundary = true;
            this.instructionStep = 0;
            this.lastFinishedPC = getPC();
            // Diagnostica: conta le istruzioni e traccia la finestra dei PC eseguiti
            // (un range minuscolo con molte istruzioni = wait-loop stretto).
            diagInstrCount++;
            int dpc = this.lastFinishedPC;
            if (dpc < diagPcMin) diagPcMin = dpc;
            if (dpc > diagPcMax) diagPcMax = dpc;
        }
    }
    public AbstractM68kInstruction getInstructionAt(int opcode) { return decodeTable[opcode & 0xFFFF]; }
    public boolean isBusCycleIdle() {
        return busCycleState == BusCycleState.IDLE && currentBusOp == null;
    }
    public void addInternalCycles(int cycles) { internalCyclesRemaining += cycles; }
    public void idleCycles(int count) {
        if (count <= 0) return;
        addInternalCycles(count - 1);
        risingEdgeConsumed = true;
    }
    public void completeInternalStep() { idleCycles(2); }
    public void forceBusWait() { idleCycles(2); }
    public void triggerDoubleFault() { haltCPU(); }
    public void setGroup0PushedPC(int pushedPC) { this.group0PushedPC = pushedPC; this.group0PushedPCValid = true; }

    /**
     * Dichiarazione, da parte dell'istruzione, dell'offset in byte — dall'indirizzo
     * dell'opcode — del PC che il 68000 impila nel frame di gruppo 0.
     *
     * <p>È la controparte di {@link #markIplAnchor}: in WinUAE il valore non è una regola
     * ma un {@code m68k_incpci(N)} che <b>gencpu emette dentro il singolo ramo di guasto</b>
     * ({@code gencpu.cpp:3262-3276}, {@code exception_pc_offset + exception_pc_offset_extra_000
     * + pcextra}), quindi l'unica forma fedele è che sia l'istruzione a dichiararlo.
     * Chi non dichiara ricade su {@link #group0DataFaultPcOffset()}.
     *
     * <p>Va chiamata <b>prima</b> dell'accesso che può fallire.
     */
    public void markGroup0PcOffset(int bytesFromOpcode) {
        this.group0PushedPC = opcodeAddress + bytesFromOpcode;
        this.group0PushedPCValid = true;
    }

    /**
     * Registra il modo/taglia dell'EA appena calcolata: è quella su cui l'accesso sta per
     * fallire, ed è l'unico dato che la regola generale non ha già.
     */
    public void noteGroup0Ea(int mode, int reg, int size) {
        group0EaMode = (mode == 7) ? 8 + reg : mode;
        group0EaSize = size;
        group0EaDeltaOverride = Integer.MIN_VALUE;
    }

    /** Impone il delta della regola generale senza calcolare l'offset a mano (MOVEM, ADDX/SUBX/CMPM). */
    public void markGroup0EaDelta(int delta) { group0EaDeltaOverride = delta; }

    /**
     * Dichiara l'IR che finira' nel frame di gruppo 0 al posto dell'opcode corrente.
     *
     * <p>Serve alle istruzioni che rifanno la <b>prefetch prima</b> dell'accesso che
     * fallisce: sul 68000 l'IR e' gia' stato ricaricato, e nel frame finisce l'opcode
     * dell'istruzione <b>successiva</b>. Il generato lo scrive per esteso — in
     * {@code op_313c_14_ff} (MOVE.W #imm,-(An)) c'e' {@code regs.ir = regs.irc;} prima
     * della scrittura, e {@code exception3_write} impila {@code regs.ir}
     * ({@code newcpu.cpp:7975}). Ne segue anche la SSW, i cui bit alti sono
     * {@code opcode & ~31} ({@code newcpu_common.cpp:1654}).
     */
    public void markGroup0Ir(int irValue) { group0IrOverride = irValue & 0xFFFF; }

    /**
     * Offset del PC di gruppo 0 per un guasto su <b>accesso a dati</b>.
     *
     * <p>{@code 2 + 2*(parole d'estensione consumate) + delta}, dove il delta dipende dal
     * modo dell'EA che fallisce. Trascritto dal generato di WinUAE
     * ({@code cpuemu_13.cpp}, funzioni {@code _14_ff} = 68000) e verificato su
     * <b>902 dei 906</b> siti {@code exception3_*_access} dell'albero; i 4 residui sono
     * MOVEM verso registri con modo indicizzato, che dichiara da sé.
     *
     * <p>⚠️ I vettori JSON di SingleStepTests danno invece {@code 2*(estensioni)}, cioè
     * questo valore meno {@code 2 + delta}: divergono da WinUAE su 3 modi su 5. Arbitra
     * l'hardware — gli {@code expected000} di vAmigaTS {@code CPU/Exceptions/StackFrame},
     * misurati su un A500 vero, coincidono con WinUAE. Stessa arbitrazione del Bcc in
     * {@code docs/castle-master-copylock-investigation.md} §Causa 6.
     */
    private int group0DataFaultPcOffset() {
        int delta = group0EaDeltaOverride != Integer.MIN_VALUE
                ? group0EaDeltaOverride
                : switch (group0EaMode) {
                    // (d16,An), (d8,An,Xn), (d16,PC), (d8,PC,Xn): la parola d'estensione
                    // dell'EA che fallisce non è ancora contata
                    case 5, 6, 10, 11 -> -2;
                    // -(An): byte e word contano una parola in più, il long no
                    case 4 -> group0EaSize == 4 ? 0 : 2;
                    default -> 0;
                };
        return 2 + ((extensionsConsumed - 1) * 2) + delta;
    }
    /** Allow an instruction to pre-set the SR value that will be pushed in the Group-0 exception frame,
     *  without modifying the CPU's current sr field.  Must be called before the faulting bus operation. */
    public void setGroup0SavedSR(int sr) { this.savedSR = sr; this.group0SavedSRValid = true; }
    /** Set the SR value the CPU should have when the exception handler starts (post-frame sr &= ~T).
     *  This allows instructions that update flags before a fault to decouple the pushed SR from the
     *  handler SR.  Pass -1 to clear (default: no override). */
    public void setGroup0PreFaultSR(int sr) { this.group0PreFaultSR = sr; }
    /** If an AE aborts the instruction, restore register An to originalValue before the handler starts. */
    public void setGroup0PreFaultAn(int reg, int originalValue) { this.group0PreFaultAnReg = reg; this.group0PreFaultAnValue = originalValue; }
    public void setInInstructionBoundary(boolean v) { this.inInstructionBoundary = v; }
    /**
     * True quando la CPU è a un confine d'istruzione "pulito": tra due istruzioni, coda-bus
     * vuota e nessun ciclo interno in sospeso. Usato dal save state per congelare/ripristinare
     * la macchina senza operazioni bus a metà (che non fanno parte dello snapshot). */
    public boolean isAtInstructionBoundary() {
        return inInstructionBoundary && busQueueSize == 0 && internalCyclesRemaining == 0
                // Bus completamente inattivo: nessun ciclo bus in corso e nessuna bus-op in
                // volo. Con il prefetch sovrapposto inInstructionBoundary può essere true
                // mentre l'ultimo prefetch è ancora in volo (currentBusOp != null); fermarsi
                // lì lascerebbe fuori dallo snapshot la bus-op transitoria (oggetto non
                // catturato), facendo divergere il resume.
                && busCycleState == BusCycleState.IDLE && currentBusOp == null;
    }
    public boolean isRisingEdgeConsumed() { return risingEdgeConsumed; }
    public void setRisingEdgeConsumed(boolean v) { this.risingEdgeConsumed = v; }
    public BusRequest getBusReq() { return busReq; }
    public BusResponse getBusResp() { return busResp; }
}
