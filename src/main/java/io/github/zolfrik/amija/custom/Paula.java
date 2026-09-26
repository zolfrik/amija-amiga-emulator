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

import io.github.zolfrik.amija.custom.AbstractAgnus.EventID;
import io.github.zolfrik.amija.custom.AbstractAgnus.EventSlot;
import io.github.zolfrik.amija.custom.audio.AudioRingBuffer;
import io.github.zolfrik.amija.custom.audio.JavaSoundAudioOutput;
import io.github.zolfrik.amija.input.GamePort;
import io.github.zolfrik.amija.input.GamePortDevice;

import java.util.Arrays;
import java.util.function.IntSupplier;

/**
 * Paula (8364) — Audio / Interrupt Controller / Disk DMA / Serial Port dell'Amiga 500.
 *
 * Allineata alla versione C++ di vAmiga.
 */
public final class Paula {
    
    // =========================================================================
    // Dipendenze
    // =========================================================================

    private AbstractAgnus agnus;

    private IntSupplier dmaconSupplier;
    private Runnable iplChangedCallback;

    public void setAgnus(AbstractAgnus agnus) { this.agnus = agnus; }
    public void setDmaconSupplier(IntSupplier s) { this.dmaconSupplier = s; }
    public void setIplChangedCallback(Runnable callback) { this.iplChangedCallback = callback; }

    private int getDmacon() { return (dmaconSupplier != null) ? dmaconSupplier.getAsInt() : 0; }

    // =========================================================================
    // Interrupts
    // =========================================================================

    /**
     * INTREQ e INTENA <b>come li rilegge la CPU</b> (INTREQR/INTENAR). WinUAE
     * {@code intreq}/{@code intena}, e {@code INTREQR()} restituisce proprio questo.
     */
    private int intreq;
    private int intena;
    /**
     * Le <b>copie da cui si calcola la linea IPL</b>, e solo quelle: WinUAE
     * {@code intreq2}/{@code intena2}, con {@code doint()} che fa
     * {@code uae_u16 imask = intreq2 & intena2} (custom.cpp:3146). Non sono un duplicato
     * ridondante: le due coppie si aggiornano in momenti diversi e <b>possono restare
     * disallineate per sempre</b>.
     *
     * <p>Una scrittura di CPU/Copper cambia la copia letta <b>subito</b> e quella dell'IPL
     * un CCK dopo (WinUAE {@code doint_delay_intreq}: «INTREQ or INTENA write: IPL line
     * changes 0.5 CCKs later. 68000 needs one more CPU clock (0.5 CCK) before it detects
     * it»); un interrupt generato da un chip le alza <b>entrambe insieme</b>
     * ({@code event_doint_delay_do_ext}).
     *
     * <p>Da qui nasce uno stato che con una copia sola non esiste: bit alto in
     * {@code intreq} e basso in {@code intreq2}, cioè un interrupt che la CPU
     * <i>rilegge come pendente</i> ma che <i>non viene mai consegnato</i>. È lo stallo che
     * i test vAmigaTS {@code Paula/Audio/timing/audtim*} misurano — l'oracolo invoca il
     * gestore sei volte e poi si ferma per sempre, noi lo servivamo all'infinito. Vedi
     * {@code docs/vamigats-paula-audio-plan.md} §14.5.
     */
    private int intreq2;
    private int intena2;
    private final long[] setIntreq = new long[16];
    private long iplPipe;

    // =========================================================================
    // Potentiometers
    // =========================================================================

    private int potgo;
    /**
     * Stato del potenziometro, indicizzato [porta][linea]: porta 0 = {@link #gamePort1}
     * (sinistra), 1 = {@link #gamePort2} (destra); linea 0 = X (pulsante medio / button 3),
     * 1 = Y (destro / button 2). @see #capCheck(boolean)
     */
    private final int[][] potCap = new int[2][2];
    private final int[][] potDat = new int[2][2];
    private final int[][] potDatAct = new int[2][2];

    /** Righe di scarica veloce dopo START: {@code POTDAT_DELAY} dell'oracolo. */
    private static final int POTDAT_DELAY = 8;

    // Game port 1 (mouse) e port 2 (joystick) per la gestione di POTINP
    private GamePort gamePort1 = null;
    private GamePort gamePort2 = null;

    public void setGamePort1(GamePort p) { gamePort1 = p; }
    public void setGamePort2(GamePort p) { gamePort2 = p; }

    // =========================================================================
    // ADKCON
    // =========================================================================

    private int adkcon;

    // =========================================================================
    // Disk Controller
    // =========================================================================

    private int dskpth, dskptl;
    private int dsklen;
    /**
     * DSKSYNC. ⛔ <b>Al reset vale ZERO</b>, non {@code 0x4489}: in WinUAE
     * {@code dsksync} e' uno static azzerato che {@code DISK_reset()} non tocca mai,
     * quindi finche' il software non lo scrive il comparatore confronta contro
     * {@code 0x0000}. Misurato sui due lati sul boot di {@code Paula/Audio/irqaud1}
     * (20 s emulati): <b>nessuno dei due emulatori scrive DSKSYNC</b> — Kickstart 1.3
     * legge la traccia grezza con WORDSYNC spento (ADKCON {@code $1100}) e decodifica
     * in software — quindi il {@code 0x4489} che ci ritrovavamo era solo il nostro
     * valore di reset. Vedi docs/vamigats-paula-audio-plan.md §7.1.24.
     */
    private int dsksync = 0x0000;
    private int dsk_syncMatches = 0; // diagnostica: sync trovati nella sessione DMA corrente
    /**
     * Latch del rilevatore di sync: l'interrupt DSKSYN e' <b>edge-triggered</b> e parte solo
     * al primo bit in cui lo shifter combacia, non a ognuno. Si riarma quando lo shifter
     * torna a non combaciare. E' il {@code dsksync_on} dell'oracolo (disk.cpp).
     */
    private boolean dsksyncOn = false;
    private int dskbytr_incoming;
    private long dsk_syncCycle;
    private int dsk_dataReg;
    private int dsk_dataRegCount;
    private long dsk_fifo;
    private int dsk_fifoCount;
    /**
     * Words the current read DMA still has to assemble: DSKLEN's length at the start, one
     * less for every word the read shifter completes into the FIFO.
     */
    private int dskWordsLeft;
    /**
     * Disk read slots requested for the current line: the FIFO fill level Paula reports
     * to Agnus once per line (DMAL). See {@link #latchDmaRequests()}.
     */
    private int dskSlotRequests;

    // ── Shift register di SCRITTURA (Paula → testina) ────────────────────────
    // Speculare a dsk_dataReg: la DMA carica una word alla volta (loadWriteWord,
    // richiesta da Agnus solo quando lo shifter è vuoto) e il drive preleva un bit
    // per cella MFM (nextWriteBit), così la velocità di trasferimento è dettata
    // dalla rotazione del supporto e non dal numero di slot DMA disponibili.
    private int dsk_writeShift;
    private int dsk_writeBits;

    public enum DriveDmaState { OFF, READ, WRITE, WAIT, FLUSH }
    private DriveDmaState diskState = DriveDmaState.OFF;

    // =========================================================================
    // UART
    // =========================================================================

    private int serper;
    private int serReceiveBuffer;
    private int serTransmitBuffer;
    private int serTransmitShiftReg;
    private boolean serOvrun;
    // Separate flag to detect a full transmit buffer: serTransmitBuffer==0 is a valid SERDAT value,
    // so we cannot use a zero-check to determine whether the buffer is empty.
    private boolean serTxbFull = false;

    // =========================================================================
    // Audio
    // =========================================================================

    private final int[] audLCH = new int[4];
    private final int[] audLCL = new int[4];
    private final int[] audLEN = new int[4];
    private final int[] audPER = new int[4];
    private final int[] audVOL = new int[4];
    private final int[] audDAT = new int[4];
    private final AudioChannel[] audioChannels = new AudioChannel[4];
    private final AudioRingBuffer audioRingBuffer = new AudioRingBuffer();
    private final JavaSoundAudioOutput audioOutput = new JavaSoundAudioOutput(audioRingBuffer);
    { audioOutput.setCorrectionSupplier(this::getSampleRateCorrection); }
    private double outputSamplePhase;
    private int activeAudioMask;
    /** Cicli accumulati e non ancora applicati ai period-counter (vedi {@link #tick(int)}). */
    private int pendingAudioCycles;
    /** min(periodCounter) sui canali attivi; -1 = da ricalcolare. */
    private int audioMinCounter = -1;
    /** Master-cycle trascorsi dall'ultimo sample di output: divisore dell'integrazione. */
    private int mixIntervalCycles;
    private static final boolean AUDIO_VERIFY =
            Boolean.getBoolean("amiga.audioverify");

    private static final int PAL_SYSTEM_CLOCK = 7_093_790;

    // -------------------------------------------------------------------------
    // ASR — Adaptive Sample Rate (allineato a vAmiga AudioPort::updateSampleRateCorrection)
    //
    // Produttore (questo thread) e consumatore (scheda audio) sono pilotati da
    // due clock indipendenti che driftano sempre. Senza correzione il ring buffer
    // finisce per svuotarsi (underrun → gracchio) o riempirsi (overflow → clic).
    // L'ASR varia impercettibilmente la frequenza di produzione per mantenere il
    // buffer attorno al livello target, eliminando il drift.
    // -------------------------------------------------------------------------
    private static final double ASR_TARGET_FILL = 0.12;   // livello di riempimento desiderato del ring (~90 ms di cuscinetto)
    // ASR a DUE ZONE (deadzone) — risolve il wobble di pitch udibile.
    // Il vecchio controllore proporzionale (gain 25000, cap ±3000 = ±7%) inseguiva il
    // dente di sega del fill (oscillazione ±1500 campioni per gli stalli di rendering
    // per-frame) correggendo ±100-300 Hz di continuo: pitch wow/flutter percepibile
    // ("audio non perfettamente fluido"). Ora:
    //   • DENTRO la deadzone (|fill-target| ≤ ASR_DEADZONE, dove cade tutta la normale
    //     oscillazione): correzione lineare minuscola, max ±ASR_FINE_MAX Hz (~0,07%,
    //     sotto la soglia di percezione del pitch) → la normale oscillazione del buffer
    //     NON modula più il pitch.
    //   • FUORI (squilibrio grosso: avvio, stallo lungo): la correzione cresce fino a
    //     ±ASR_COARSE_MAX per recuperare rapidamente prima di rischiare underrun.
    // Il drift lento host↔Amiga (<0,1%) è ben coperto dalla zona fine.
    private static final double ASR_DEADZONE = 0.06;      // ±~2000 campioni attorno al target (copre l'oscillazione normale)
    private static final double ASR_FINE_MAX = 30.0;      // Hz: correzione max dentro la deadzone (~0,07%, sub-udibile)
    private static final double ASR_COARSE_GAIN = 25000.0; // gain proporzionale fuori dalla deadzone (come l'originale)
    private static final double ASR_COARSE_MAX = 3000.0;  // Hz: correzione max fuori (recupero rapido squilibri grossi: avvio)
    private static final int ASR_UPDATE_INTERVAL = 256;   // ricalcola la correzione ogni N sample prodotti
    // EWMA: leviga ulteriormente la transizione tra le zone e il dente di sega residuo.
    private static final double ASR_SMOOTHING = 0.05;     // peso del nuovo campione nell'EWMA
    private double sampleRateCorrection;
    private int asrCounter;

    // =========================================================================
    // Costruttore & Reset
    // =========================================================================

    public Paula() {
        for (int i = 0; i < audioChannels.length; i++) {
            audioChannels[i] = new AudioChannel(i);
        }
        reset();
    }

    public void reset() { reset(true); }

    /**
     * Reset di Paula. {@code hardware} distingue l'accensione dal reset che l'istruzione CPU
     * RESET porta ai peripheral: <b>DSKSYNC non sta su quella linea</b> e sopravvive al reset
     * software, come nell'oracolo, dove {@code custom_reset} azzera {@code adkcon} e chiama
     * {@code DSKLEN(0, 0)} ma non tocca {@code dsksync} ne' il suo latch {@code dsksync_on}.
     * Azzerandolo anche a caldo, lo shifter di lettura a riposo — che scorre zeri — faceva
     * match su {@code $0000} e alzava un DSKSYN che l'oracolo non ha: misurato 7 CCK dopo il
     * ColdReboot della demo `Jesus On E's` (docs/vamigats-import-order.md §26).
     */
    public void reset(boolean hardware) {
        intena = 0;
        intreq = 0;
        intena2 = 0;
        intreq2 = 0;
        shadowPokeAt = AbstractAgnus.NEVER;
        shadowPokeValue = 0;
        shadowPokeIsIntena = false;
        adkcon = 0;
        potgo = 0;
        Arrays.fill(setIntreq, AbstractAgnus.NEVER);
        iplPipe = 0;

        // ⛔ Al reset i condensatori delle linee POT sono SCARICHI. Partivano da 1.0, e
        // cosi' una porta senza niente attaccato leggeva il proprio bit DAT alto finche'
        // nessuno scriveva START (che e' l'unica cosa che fa girare il ciclo di carica).
        // Misurato su vAmigaTS Mainboard/Ports/POT/potgo0, potgo5, potgo6: nessuno dei tre
        // scrive START, e l'oracolo legge POTGOR $0500 dove leggevamo $5500.
        for (int j = 0; j < 2; j++) {
            for (int i = 0; i < 2; i++) { potCap[j][i] = 0; potDat[j][i] = 0; potDatAct[j][i] = 0; }
        }

        dskpth = dskptl = dsklen = 0;
        if (hardware) {
            dsksync = 0x0000;
        }
        diskState = DriveDmaState.OFF;
        dsklenWriteCount = 0;
        dsk_fifo = 0;
        dsk_fifoCount = 0;
        dskWordsLeft = 0;
        dskSlotRequests = 0;
        dsk_dataReg = 0;
        dsk_dataRegCount = 0;
        dsk_writeShift = 0;
        dsk_writeBits = 0;
        dsk_syncCycle = 0;
        if (hardware) {
            dsksyncOn = false;
        }
        dskbytr_incoming = 0;

        serper = 0;
        serReceiveBuffer = 0;
        serTransmitBuffer = serTransmitShiftReg = 0;
        serOvrun = false;
        serTxbFull = false;

        Arrays.fill(audLCH, 0); Arrays.fill(audLCL, 0);
        Arrays.fill(audLEN, 0); Arrays.fill(audPER, 0);
        Arrays.fill(audVOL, 0); Arrays.fill(audDAT, 0);
        for (AudioChannel ch : audioChannels) ch.reset();
        outputSamplePhase = 0;
        sampleRateCorrection = 0;
        asrCounter = 0;
        activeAudioMask = 0;
        pendingAudioCycles = 0;
        audioMinCounter = -1;
        mixIntervalCycles = 0;
        audioRingBuffer.clear();
    }

    /**
     * Avanza i period-counter dei canali audio ATTIVI di {@code cycles} master-cycle.
     *
     * La produzione dei sample di output (accumulatore {@link #outputSamplePhase})
     * NON è più qui: è ora un evento auto-rischedulante (SLOT_CH0 / AUDIO_MIX, vedi
     * {@link #serviceAudioSampleEvent()}), così la matematica del rate gira una volta
     * per sample (~44.1k/s) invece che ad ogni master-cycle (~7.09M/s). Il gating su
     * {@link #activeAudioMask} rende questo metodo quasi gratuito quando non c'è audio.
     */
    public void tick(int cycles) {
        // Lunghezza dell'intervallo di integrazione del prossimo sample di output.
        // Va contata SEMPRE, anche senza canali attivi: un canale che si spegne a metà
        // intervallo ha accumulato solo la propria parte, e il divisore deve restare
        // l'intervallo intero perché la media venga giusta (il resto vale zero).
        mixIntervalCycles += cycles;
        if (activeAudioMask == 0) {
            // Nessun canale attivo: nulla da accumulare (i contatori sono già allineati).
            pendingAudioCycles = 0;
            audioMinCounter = -1;
            return;
        }
        int min = audioMinCounter;
        if (min < 0) {
            min = audioMinCounter = computeAudioMinCounter();
        } else if (AUDIO_VERIFY) {
            // Rete di sicurezza: la deadline in cache deve coincidere con quella ricalcolata.
            // Una discrepanza = un sito di mutazione che non chiama syncAudioChannels().
            int fresh = computeAudioMinCounter();
            if (fresh != min) {
                System.out.printf(
                        "[PAULA] audioverify: deadline stantia (cache=%d fresh=%d mask=%X)%n",
                        min, fresh, activeAudioMask);
                min = audioMinCounter = fresh;
            }
        }
        int pending = pendingAudioCycles + cycles;
        if (pending < min) {
            // Nessun canale può scadere prima di `min`: nulla di osservabile cambia.
            pendingAudioCycles = pending;
            return;
        }
        pendingAudioCycles = 0;
        applyAudioCycles(pending);
        audioMinCounter = -1;
    }

    /** Applica {@code cycles} master-cycle ai period-counter dei canali attivi. */
    private void applyAudioCycles(int cycles) {
        int mask = activeAudioMask;
        while (mask != 0) {
            int chan = Integer.numberOfTrailingZeros(mask);
            audioChannels[chan].tick(cycles);
            mask &= ~(1 << chan);
        }
    }

    private int computeAudioMinCounter() {
        int min = Integer.MAX_VALUE;
        int mask = activeAudioMask;
        while (mask != 0) {
            int chan = Integer.numberOfTrailingZeros(mask);
            AudioChannel ch = audioChannels[chan];
            int counter = ch.periodCounter;
            if (counter < min) min = counter;
            // Δ2: anche l'elaborazione differita della word DMA è una scadenza, e se il
            // salto la scavalcasse il gestore cadrebbe tardi (vedi AudioChannel.tick).
            if (ch.dmaWordDelay > 0 && ch.dmaWordDelay < min) min = ch.dmaWordDelay;
            mask &= ~(1 << chan);
        }
        return min;
    }

    /**
     * Porta i period-counter al ciclo corrente e invalida la deadline. Va chiamata prima di
     * OGNI mutazione che possa accorciare un contatore o cambiare l'insieme dei canali attivi
     * (scritture AUDxPER/VOL/DAT, DMACON, ADKCON, consegna di una word DMA, reset): dopo la
     * sync i contatori di tutti i canali sono riferiti allo stesso istante, quindi il minimo
     * ricalcolato è confrontabile. È sempre sicura — anticipare l'applicazione dei cicli
     * pendenti non produce alcun effetto osservabile finché nessun contatore è scaduto.
     */
    private void syncAudioChannels() {
        int pending = pendingAudioCycles;
        pendingAudioCycles = 0;
        audioMinCounter = -1;
        if (pending != 0 && activeAudioMask != 0) {
            applyAudioCycles(pending);
        }
    }

    /**
     * Evento auto-rischedulante (Agnus SLOT_CH0 / AUDIO_MIX): produce un sample di
     * output audio e ri-programma il prossimo. Sostituisce l'accumulatore per-ciclo
     * che girava in {@link #tick(int)}.
     */
    public void serviceAudioSampleEvent() {
        // I cicli tenuti in sospeso da tick() sono già contati in mixIntervalCycles ma
        // non ancora negli accumulatori dei canali: senza questa sync il numeratore
        // coprirebbe meno tempo del denominatore e il livello uscirebbe basso.
        syncAudioChannels();
        mixAudioSample();
        updateSampleRateCorrection();
        scheduleNextAudioSample();
    }

    /**
     * Programma il prossimo evento AUDIO_MIX. Il numero di master-cycle {@code k}
     * fino al prossimo sample è il più piccolo {@code k >= 1} tale che
     * {@code outputSamplePhase + rate*k >= PAL_SYSTEM_CLOCK}, con
     * {@code rate = OUTPUT_RATE + sampleRateCorrection}. È identico al vecchio
     * accumulatore per-ciclo: fra due sample {@code rate} è costante (la correzione
     * ASR cambia solo in corrispondenza dei sample), quindi i cicli di emissione
     * coincidono esattamente con il modello a polling precedente.
     */
    private void scheduleNextAudioSample() {
        if (agnus == null) return;
        double rate = JavaSoundAudioOutput.OUTPUT_RATE + sampleRateCorrection;
        if (rate <= 0) rate = JavaSoundAudioOutput.OUTPUT_RATE;
        long k = (long) Math.ceil((PAL_SYSTEM_CLOCK - outputSamplePhase) / rate);
        if (k < 1) k = 1;
        outputSamplePhase += rate * k - PAL_SYSTEM_CLOCK;
        agnus.scheduleRel(EventSlot.SLOT_CH0, k, EventID.AUDIO_MIX);
    }

    /**
     * Aggiorna la correzione ASR in base al riempimento del ring buffer.
     * Errore positivo (buffer sotto target) → produci più veloce; negativo → più lento.
     * La correzione è smussata via EWMA per evitare oscillazioni di pitch udibili.
     */
    private void updateSampleRateCorrection() {
        if (++asrCounter < ASR_UPDATE_INTERVAL) return;
        asrCounter = 0;

        // error > 0 → buffer troppo pieno → rallenta la produzione (correzione negativa).
        double error = audioRingBuffer.fillLevel() - ASR_TARGET_FILL;
        double absErr = Math.abs(error);

        double correction;
        if (absErr <= ASR_DEADZONE) {
            // Zona fine: correzione lineare e minuscola (sub-udibile). Qui cade tutta la
            // normale oscillazione del buffer → il pitch non viene più modulato.
            correction = -(error / ASR_DEADZONE) * ASR_FINE_MAX;
        } else {
            // Zona grossa: controllo proporzionale FORTE come l'originale (gain elevato,
            // clamp ±COARSE_MAX) per recuperare in fretta gli squilibri grossi (es. il
            // boot, dove il consumer della scheda parte in ritardo e il buffer si
            // riempirebbe fino all'overflow). L'EWMA leviga la transizione col bordo
            // della deadzone in un breve glissato (~0,1 s), non un salto netto.
            correction = -error * ASR_COARSE_GAIN;
            if (correction >  ASR_COARSE_MAX) correction =  ASR_COARSE_MAX;
            else if (correction < -ASR_COARSE_MAX) correction = -ASR_COARSE_MAX;
        }

        sampleRateCorrection = sampleRateCorrection * (1.0 - ASR_SMOOTHING) + correction * ASR_SMOOTHING;
    }

    /** Correzione ASR corrente in Hz (diagnostica). */
    public double getSampleRateCorrection() {
        return sampleRateCorrection;
    }

    // =========================================================================
    // Interrupt Service Methods
    // =========================================================================

    public void serviceIrqEvent() {
        long clock = agnus.getMasterClock();

        // ORDINE: prima gli interrupt dei chip, poi il poke differito. Non è
        // indifferente, ed è ciò che decide lo stallo del §14.5 del piano audio: in
        // WinUAE, a parità di ciclo, scatta prima l'evento accodato prima, e un
        // interrupt audio è accodato con 2 CCK di anticipo (AUDIO_IRQ_DELAY_CYCLES)
        // contro il mezzo CCK del poke. Applicando prima il poke, l'ack del gestore
        // finirebbe SOTTO la richiesta del canale invece che sopra, e le due copie
        // resterebbero allineate: lo stallo non si formerebbe mai.
        for (int i = 0; i < 16; i++) {
            if (clock >= setIntreq[i]) {
                setINTREQ(true, (1 << i));
                setIntreq[i] = AbstractAgnus.NEVER;
            }
        }
        if (clock >= shadowPokeAt) {
            applyShadowPoke();
        }
        rescheduleIrqEvent();
    }

    /** Riprogramma SLOT_IRQ alla prima scadenza fra i set differiti e il poke differito. */
    private void rescheduleIrqEvent() {
        long next = shadowPokeAt;
        for (long t : setIntreq) {
            if (t < next) next = t;
        }
        agnus.scheduleAbs(EventSlot.SLOT_IRQ, next, EventID.IRQ_CHECK);
    }

    /**
     * Livello IPL che la CPU vede sui pin: lo stadio 0 della pipe, cioè il valore
     * SUBITO, senza ritardo. È il modello WinUAE (adottato il 2026-07-31): {@code doint()}
     * chiama {@code update_ipl()} immediatamente, senza pipe. Gli stadi alti della pipe
     * restano perché {@link #serviceIplEvent} li usa per propagare il livello, ma il pin
     * non li legge: leggere lo stadio 3 (il vecchio modello) metterebbe il pin ~3 CCK più
     * tardi di WinUAE, differenza invisibile finché il riconoscimento dell'interrupt è
     * quantizzato al boundary d'istruzione ma misurabile con le soglie di
     * {@code ipl_fetch_next} (cd/cdp), che partono dall'istante del cambio di pin.
     */
    public int getCpuIplFromPipe() {
        return (int) (iplPipe & 0xFF);
    }

    /**
     * Avanza la pipe IPL di uno stadio (1 CCK). Porta fedele di vAmiga
     * {@code Paula::serviceIplEvent}: prima aggiorna il pin IPL della CPU col valore
     * in cima alla pipe (byte 3), POI shifta la pipe di un byte propagando il nuovo
     * livello verso l'alto, e si ri-schedula a 1 CCK (2 master-cycle) finché la pipe
     * non è interamente shiftata (contatore repeat in {@code data}).
     */
    public void serviceIplEvent(long repeat) {
        // 1. Aggiorna il pin IPL della CPU col valore in cima alla pipe.
        if (iplChangedCallback != null) {
            iplChangedCallback.run();
        }
        // 2. Shifta la pipe (il nuovo livello, nel byte 0, sale di uno stadio).
        iplPipe = ((iplPipe & 0x00FF_FFFF_FFFF_FFFFL) << 8) | (iplPipe & 0xFFL);
        // 3. Ri-schedula finché la pipe non è completamente attraversata.
        if (repeat != 0) {
            agnus.scheduleRel(EventSlot.SLOT_IPL, 2, EventID.IPL_CHANGE, repeat - 1);
        } else {
            agnus.cancel(EventSlot.SLOT_IPL);
        }
    }

    public void checkInterrupt() {
        int level = calculateIPL();
        if ((iplPipe & 0xFF) != level) {
            iplPipe = (iplPipe & ~0xFFL) | level;
            // vAmiga: scheduleRel<SLOT_IPL>(0, IPL_CHANGE, 5) — primo stadio subito,
            // poi 1 CCK per stadio. Il nostro executeEvents gira a inizio risingEdge,
            // quindi delay=1 mc fa scattare il primo stadio al ciclo successivo.
            agnus.scheduleRel(EventSlot.SLOT_IPL, 1, EventID.IPL_CHANGE, 5);
        }
    }

    /** La linea IPL si calcola dalle copie ritardate, mai da quelle lette. Vedi {@link #intreq2}. */
    public int calculateIPL() {
        if ((intena2 & 0x4000) != 0) {
            int mask = intreq2 & intena2;
            // EXTER (bit 13) + bit 14 — IPL 6.
            // Il bit 14 di INTREQ non ha una sorgente hardware e i manuali non lo
            // elencano, ma è un bit scrivibile e Paula lo include nel gruppo di
            // priorità più alto insieme a EXTER: con il master enable acceso,
            // scrivere INTREQ=$C000 solleva un IPL 6 a tutti gli effetti.
            // Oracolo: WinUAE custom.cpp intlev(), "if (imask & (0x4000 | 0x2000))".
            // Adrenalynn (Loriciel) se ne serve per far ripartire la copper list:
            // la lista scrive INTREQ=$C000 a fine frame e l'handler di livello 6
            // ricarica COP1LC e azzera il bit con INTREQ=$4000. Senza questo,
            // la copper list resta parcheggiata e il gioco attende all'infinito.
            if ((mask & 0x6000) != 0) return 6;
            // DSKSYN (bit 12) + RBF (bit 11) — IPL 5
            if ((mask & 0x1800) != 0) return 5;
            // AUD3-AUD0 (bits 10-7) — IPL 4
            if ((mask & 0x0780) != 0) return 4;
            // BLIT + VERTB + COPER (bits 6-4) — IPL 3
            if ((mask & 0x0070) != 0) return 3;
            // PORTS / CIA-A (bit 3) — IPL 2
            if ((mask & 0x0008) != 0) return 2;
            // SOFT + DSKBLK + TBE (bits 2-0) — IPL 1
            if ((mask & 0x0007) != 0) return 1;
        }
        return 0;
    }

    // =========================================================================
    // Potentiometer Service Methods
    // =========================================================================

    /**
     * Modello del potenziometro — porting di {@code cap_check()} dell'oracolo
     * ({@code inputdevice.cpp}), chiamato una volta per riga da Agnus.
     *
     * <p>⛔ Qui prima non c'era un contatore: {@code POTGO[START]} programmava due eventi a
     * intervallo fisso che <b>azzeravano</b> i quattro contatori, e POTxDAT leggeva sempre
     * <b>0</b>. Misurato su {@code vAmigaTS Mainboard/Ports}: l'oracolo conta <b>4</b> o
     * <b>10</b> a seconda della riga in cui il Copper rilegge, noi zero ovunque.
     *
     * <p>La macchina a stati, per ognuna delle quattro linee:
     * <ol>
     *   <li>{@code POTGO[START]} arma tutte e quattro: {@code act = 1}, {@code dat = 0};</li>
     *   <li>finché {@code act == 1} il condensatore è <b>scaricato in fretta</b> e
     *       {@code dat} conta le prime {@link #POTDAT_DELAY} righe; poi {@code act = 2} e
     *       {@code dat} riparte da zero;</li>
     *   <li>con {@code act == 2} {@code dat} conta una riga alla volta mentre il
     *       condensatore si carica, e si <b>ferma</b> quando la carica supera 10 — a meno
     *       che il pulsante non tenga la linea a massa, che è ciò che rende il conteggio
     *       la misura di quel pulsante;</li>
     *   <li>una linea programmata come <b>uscita</b> non misura: alta ferma il conteggio,
     *       bassa lo lascia correre libero se il pulsante è premuto.</li>
     * </ol>
     *
     * <p>⚠️ Il conteggio si ferma solo se alla porta c'è <b>qualcosa</b>: a porta vuota
     * nessuno carica il condensatore e {@code dat} corre libero, come nell'oracolo
     * ({@code digital_port[joy][i] || mouse_port[joy]}).
     */
    public void potHsync() {
        capCheck(true);
    }

    private void capCheck(boolean hsync) {
        for (int joy = 0; joy < 2; joy++) {
            final GamePort port = (joy == 0) ? gamePort1 : gamePort2;
            final boolean connected = port != null && port.getDevice() != GamePortDevice.NONE;
            final boolean pullup = port != null && port.pullsUpPotLines();
            for (int i = 0; i < 2; i++) {
                final int pdir = 0x0200 << (joy * 4 + i * 2);
                final int pdat = 0x0100 << (joy * 4 + i * 2);
                // i == 0 e' la linea X (pulsante medio / button 3), i == 1 la Y (destro /
                // button 2). Oracolo: getbuttonstate(joy, i == 0 ? JOYBUTTON_3 : JOYBUTTON_2).
                final boolean isButton = port != null
                        && (i == 0 ? port.isMiddleOrButton3Pressed()
                                   : port.isRightOrButton2Pressed());

                boolean canCharge = true;
                int charge = pullup ? 1 : 0;   // carica lenta attraverso il pull-up del device

                if ((potgo & pdir) == 0) {                       // linea in INGRESSO
                    if (potDatAct[joy][i] != 0 && hsync) {
                        potDat[joy][i] = (potDat[joy][i] + 1) & 0xFF;
                    }
                    if (potDatAct[joy][i] == 1) {
                        if (potDat[joy][i] < POTDAT_DELAY) {
                            charge = -2;                          // scarica veloce
                            canCharge = hsync;
                        } else {
                            potDatAct[joy][i] = 2;
                            potDat[joy][i] = 0;
                        }
                    }
                    if (connected && potDatAct[joy][i] == 2
                            && potCap[joy][i] >= 10 && !isButton) {
                        potDatAct[joy][i] = 0;
                    }
                } else {                                          // linea in USCITA
                    boolean high = (potgo & pdat) != 0;
                    charge = high ? 2 : -2;                       // (dis)carica immediata
                    if (high) {
                        potDatAct[joy][i] = 0;                    // alta: conteggio fermo
                    } else if (isButton) {
                        potDat[joy][i] = (potDat[joy][i] + 1) & 0xFF;  // "free running"
                    }
                }

                if (isButton) {
                    charge = -2;    // il pulsante a massa vince su tutto
                }
                if (canCharge) {
                    chargeCap(joy, i, charge);
                }
            }
        }
    }

    /** {@code charge_cap()} dell'oracolo: |carica| &gt; 1 vale 80 volte tanto, e si satura a 511. */
    private void chargeCap(int joy, int i, int charge) {
        int c = (charge < -1 || charge > 1) ? charge * 80 : charge;
        potCap[joy][i] = Math.max(0, Math.min(511, potCap[joy][i] + c));
    }

    // =========================================================================
    // UART Service Methods
    // =========================================================================

    public void serviceTxdEvent(EventID id) {
        // Sentinel-bit scheme (mirrors vAmiga / real Paula behaviour):
        //   shift register layout = [sentinel=1][data bits][start bit=0]
        //   Transmission ends when the shift register becomes 0 after the sentinel
        //   has been shifted out as the stop bit (mark = 1).

        if (serTransmitShiftReg == 0) {
            // Shift register empty: try to reload from buffer.
            if (serTxbFull) {
                int longBit = ((serper & 0x8000) != 0) ? 1 : 0;    // 9-bit vs 8-bit mode
                int numData  = 8 + longBit;
                int dataMask = (1 << numData) - 1;
                // [sentinel at top] | [data] | [start bit = 0 at LSB]
                serTransmitShiftReg = ((1 << numData) | (serTransmitBuffer & dataMask)) << 1;
                serTransmitBuffer = 0;
                serTxbFull = false;
                // TBE: transmit buffer is now empty → raise interrupt
                raiseInterrupt(0x0001);
            } else {
                // Nothing left to send — stop scheduling.
                agnus.cancel(EventSlot.SLOT_TXD);
                return;
            }
        }

        // Consume the current LSB (0 = space, 1 = mark); the TXD pin is not wired anywhere.
        serTransmitShiftReg >>>= 1;   // unsigned right-shift (sentinel propagates correctly)

        agnus.scheduleRel(EventSlot.SLOT_TXD, getSerPulseWidth(), EventID.TXD_BIT);
    }

    public void serviceRxdEvent(EventID id) {
        // Stub for now
    }

    private long getSerPulseWidth() {
        return ((serper & 0x7FFF) + 1) * 2L;
    }

    public void receiveSerialData(int data) {
        if ((intreq & 0x0800) != 0) { // RBF already set
            serOvrun = true;
        }
        serReceiveBuffer = data & 0x1FF;
        raiseInterrupt(0x0800); // RBF
    }

    // =========================================================================
    // Disk Controller Service Methods
    // =========================================================================

    public void serviceDiskEvent() {
        // In vAmiga, this is called every ~2us to transfer bytes
        // In this monolithic version, we can simplify or implement the rotation
        agnus.scheduleRel(EventSlot.SLOT_DSK, 14, EventID.DSK_ROTATE);
    }

    /**
     * true quando la FIFO del disco ha spazio e la DMA deve fornire un'altra word.
     * Agnus consulta questo flag sullo slot DMA del disco: senza di esso la DMA
     * svuoterebbe DSKLEN alla velocità degli slot invece che a quella di rotazione
     * del supporto (1 word ogni 16 celle MFM, ~227 cicli).
     *
     * <p>Anche in scrittura si passa dalla FIFO a 3 word: i tre slot DMA del disco
     * sono raggruppati a inizio riga raster (cck 7/9/11), mentre una word dura
     * mezza riga. Alimentando direttamente lo shift register si riuscirebbe a
     * caricare una sola word per riga — metà della velocità di rotazione reale.
     * La FIFO è esattamente ciò che sull'hardware assorbe questo raggruppamento.
     */
    public boolean isWriteWordNeeded() {
        return diskState == DriveDmaState.WRITE && dsk_fifoCount < 3;
    }

    /** Accoda alla FIFO di scrittura una word prelevata dalla DMA. */
    public void loadWriteWord(int data) {
        writeFifo(data);
    }

    /**
     * Preleva la cella MFM successiva da scrivere sul supporto. Chiamato dal drive
     * una volta per cella, alla cadenza di rotazione.
     *
     * @return 0/1 = bit da scrivere; {@link #WRITE_BIT_STALL} se lo shifter è
     *         momentaneamente vuoto (la DMA non ha ancora fornito la word: la
     *         cella passa sotto la testina senza essere alterata);
     *         {@link #WRITE_BIT_END} quando il trasferimento è concluso e il drive
     *         deve uscire dalla modalità di scrittura.
     */
    public int nextWriteBit() {
        if (dsk_writeBits == 0) {
            int word = readFifo();
            if (word < 0) {
                // FIFO vuota: se la DMA è ancora attiva la word arriverà al prossimo
                // slot del disco, altrimenti l'ultima è già stata incisa per intero.
                return diskState == DriveDmaState.WRITE ? WRITE_BIT_STALL : WRITE_BIT_END;
            }
            dsk_writeShift = word;
            dsk_writeBits  = 16;
        }
        int bit = (dsk_writeShift >>> 15) & 1;
        dsk_writeShift = (dsk_writeShift << 1) & 0xFFFF;
        dsk_writeBits--;
        return bit;
    }

    /** Sentinella di {@link #nextWriteBit()}: nessun dato pronto in questa cella. */
    public static final int WRITE_BIT_STALL = -2;
    /** Sentinella di {@link #nextWriteBit()}: trasferimento in scrittura concluso. */
    public static final int WRITE_BIT_END   = -1;

    /**
     * Shifter di lettura <b>a riposo</b>: nessun drive connesso e' selezionato.
     *
     * <p>E' {@code disk_doupdate_read_reallynothing} dell'oracolo (disk.cpp, ramo
     * {@code (selected | disabled) == 15 && dskdmaen < DSKDMA_WRITE}). Sulla scheda la
     * linea dati del bus floppy resta ferma al valore di pull-up — sull'A500
     * {@code cs_floppydatapullup = 0}, cioe' <b>zeri</b> — ma il registro a scorrimento di
     * Paula continua a scorrere e il comparatore continua a confrontare: e' cosi' che
     * l'oracolo alza sette DSKSYN durante il boot di Kickstart 1.3, tutti con la firma
     * {@code word=0000 dsksync=0000} (docs/vamigats-paula-audio-plan.md §7.1.22).
     *
     * <p>⛔ <b>Fa MENO di {@link #shiftInDiskBit(int)}, ed e' il punto</b>: {@code reallynothing}
     * scorre la word, aggiorna DSKBYTR al confine di byte e valuta il latch del sync. Non
     * tocca {@code dsk_syncCycle} (cioe' WORDEQUAL, l'osservabile su cui vivono le
     * protezioni Copylock), non riallinea lo shifter da WORDSYNC e non travasa niente
     * nella FIFO. Passare a riposo per la strada completa aggiungerebbe divergenze invece
     * di toglierne: e' la ragione per cui il primo tentativo di questo porto fu revocato
     * (§7.1.23).
     */
    public void shiftInDiskBitIdle(int bit) {
        // `dskdmaen < DSKDMA_WRITE`: in scrittura l'oracolo sceglie disk_doupdate_write e
        // questo ramo non gira affatto — non e' solo l'interrupt a essere inibito.
        if (diskState == DriveDmaState.WRITE) {
            return;
        }
        dsk_dataReg = ((dsk_dataReg << 1) | (bit & 1)) & 0xFFFF;
        // The bit counter is ONE modulo-16 counter, shared with the active path: it is the
        // index of the bit just shifted in, and nothing re-anchors it when the drive
        // selection changes. The word boundary the raw track lands on therefore carries
        // across every idle stretch.
        dsk_dataRegCount = (dsk_dataRegCount + 1) & 15;

        // DSKBYT (readable via 0x01A) is updated every byte.
        if ((dsk_dataRegCount & 7) == 0) {
            dskbytr_incoming = 0x8000 | (dsk_dataReg & 0xFF);
        }

        // Il latch `dsksync_on`, identico alla strada completa.
        if (dsk_dataReg != dsksync) {
            dsksyncOn = false;
        }
        if (!dsksyncOn && (adkcon & 0x0200) == 0 && dsk_dataReg == dsksync) {
            raiseInterrupt(0x1000); // DSKSYN
            dsksyncOn = true;
        }
    }

    public void shiftInDiskBit(int bit) {
        dsk_dataReg = ((dsk_dataReg << 1) | (bit & 1)) & 0xFFFF;

        // Ordine di Paula (WinUAE disk.cpp, disk_doupdate_read): prima si CHIUDE la
        // word corrente — aggiornamento di DSKBYT e travaso nella FIFO — e solo dopo
        // si valuta il match di sync. Così la seconda word di sync dei settori
        // standard (che ne hanno due consecutive) viene comunque trasferita.
        // Same modulo-16 counter as the idle path (see shiftInDiskBitIdle): the word
        // boundary is continuous across a change of drive selection.
        dsk_dataRegCount = (dsk_dataRegCount + 1) & 15;

        // DSKBYT (readable via 0x01A) is updated every byte (8 bits).
        if ((dsk_dataRegCount & 7) == 0) {
            int byteVal = dsk_dataReg & 0xFF;
            dskbytr_incoming = 0x8000 | byteVal; // DSKBYT set
        }

        // A completed word enters the FIFO only while the read DMA is transferring.
        if (dsk_dataRegCount == 0 && diskState == DriveDmaState.READ
                && (getDmacon() & 0x0210) == 0x0210) {
            assembleReadWord(dsk_dataReg & 0xFFFF);
        }

        // ── Rilevamento del sync, un bit alla volta ──────────────────────────────
        // ⛔ L'INTERRUPT E' EDGE-TRIGGERED, il rilevamento no. L'oracolo tiene un latch
        // `dsksync_on`: lo alza al primo bit in cui lo shifter combacia e lo riarma solo
        // quando torna a NON combaciare (`if (word != dsksync) dsksync_on = false;`,
        // disk.cpp, tre siti identici). Noi alzavamo DSKSYN a OGNI bit di match: con un
        // DSKSYNC che combacia per piu' bit di fila — il caso limite e' `dsksync == 0` con
        // lo shifter a zero — era una raffica di interrupt invece di uno.
        //
        // ⚠️ Il RILEVAMENTO invece resta a ogni bit: `dsk_syncCycle`/`dsk_syncMatches` sono
        // l'osservabile del comparatore (li legge WORDEQUAL in DSKBYTR), non dell'interrupt,
        // e di la' il log `Sync match` sta in `wordsync_detected`, che il match lo chiama
        // ogni volta. Anche il riallineamento da WORDSYNC sta li' dentro, cioe' fuori dal
        // latch: su match ripetuti lo shifter si riallinea comunque.
        //
        // I due gate sono dell'oracolo anche loro:
        //   - MSBSYNC (ADKCON bit 9): `!(adkcon & 0x200)` racchiude match E riallineamento;
        //   - scrittura: `if (dskdmaen != DSKDMA_WRITE)` inibisce il solo interrupt.
        // ⚠️ WORDSYNC (bit 10) NON entra nella condizione dell'interrupt in nessuno dei due:
        // governa il riallineamento, e da noi era gia' cosi'.
        // Vedi docs/vamigats-paula-audio-plan.md §7.1.21.
        if (dsk_dataReg != dsksync) {
            dsksyncOn = false;
        }
        if (dsk_dataReg == dsksync && (adkcon & 0x0200) == 0) {
            dsk_syncCycle = agnus.getMasterClock();
            dsk_syncMatches++;
            if (!dsksyncOn) {
                if (diskState != DriveDmaState.WRITE) {
                    raiseInterrupt(0x1000); // DSKSYN
                }
                dsksyncOn = true;
            }

            // "wordsync match resets read shifter" (WinUAE wordsync_detected):
            // con WORDSYNC attivo il match RIALLINEA SEMPRE lo shift register di
            // lettura, indipendentemente dallo stato del DMA. Legarlo alla sola
            // transizione WAIT→READ lasciava disallineati i byte per le protezioni
            // che aprono il comparatore con DSKLEN a lunghezza 0 (nessun
            // trasferimento, sola lettura via DSKBYTR): è il caso di Barbarian II,
            // che confronta i bit dati della prima word letta e altrimenti ritenta
            // all'infinito (schermo nero).
            if ((adkcon & 0x0400) != 0) {           // WORDSYNC
                dsk_dataRegCount = 0;
                if (diskState == DriveDmaState.WAIT) {
                    dsk_fifo = 0;
                    dsk_fifoCount = 0;
                    diskState = DriveDmaState.READ;
                }
            }
        }
    }

    /**
     * Push a 16-bit word into the 3-word disk FIFO.
     * On overflow (FIFO already full) the oldest word is silently discarded —
     * this matches real hardware where Agnus must drain the FIFO fast enough.
     */
    /**
     * A word completed by the read shifter during a read DMA. With the FIFO full the word
     * is lost and not counted. When it is the last word of the transfer and the FIFO is
     * empty, the DMA ends right here and that word never reaches the FIFO: Paula checks the
     * FIFO before storing the last word.
     */
    private void assembleReadWord(int word) {
        if (dskWordsLeft <= 0 || dsk_fifoCount == 3) return;
        dskWordsLeft--;
        if (dskWordsLeft == 0 && dsk_fifoCount == 0) {
            finishDiskDma();
            return;
        }
        writeFifo(word);
    }

    /** End of a disk DMA transfer: DSKBLK follows two CCK later. */
    private void finishDiskDma() {
        diskState = DriveDmaState.OFF;
        dsklen &= 0xC000;
        dskWordsLeft = 0;
        scheduleIntreq(1, DSKBLK_DELAY_CYCLES);
    }

    /** Delay of DSKBLK after the end of the transfer, in master cycles (2 CCK). */
    private static final int DSKBLK_DELAY_CYCLES = 4;

    /**
     * DMAL, once per line: Paula reports to Agnus the DMA slots it wants in this line. For
     * the disk, the number of words waiting in the read FIFO; for audio, the channels with
     * a word request pending. A word assembled or a request raised later waits for the
     * next line.
     */
    public void latchDmaRequests() {
        dskSlotRequests = diskState == DriveDmaState.READ ? dsk_fifoCount : 0;
        syncAudioChannels();
        for (AudioChannel ch : audioChannels) {
            ch.dmalRequest = ch.dmaRequest;
        }
    }

    /**
     * Whether read slot {@code slot} (0..2) carries a request: the requested slots are the
     * last ones of the three.
     */
    public boolean isDiskReadSlotRequested(int slot) {
        return dskSlotRequests >= 3 - slot;
    }

    /**
     * A requested disk read slot: the oldest FIFO word, or -1 with the FIFO empty. The
     * transfer ends when every word has been assembled and the FIFO is drained, or in the
     * third slot with one word still queued, which is then never transferred.
     */
    public int serviceDiskReadSlot(int slot) {
        int word = readFifo();
        if (word >= 0 && diskState == DriveDmaState.READ && dskWordsLeft == 0
                && (dsk_fifoCount == 0 || (slot == 2 && dsk_fifoCount == 1))) {
            finishDiskDma();
        }
        return word;
    }

    private void writeFifo(int word) {
        if (dsk_fifoCount == 3) {
            // Overflow: discard oldest (top 16 bits), keep two most-recent words.
            dsk_fifoCount = 2;
        }
        // Shift existing content left by 16 and store new word in bits [15:0].
        dsk_fifo = ((dsk_fifo << 16) | (word & 0xFFFF)) & 0x0000FFFFFFFFFFFFL;
        dsk_fifoCount++;
    }

    /** Read and remove the oldest 16-bit word from the disk FIFO. Returns -1 if empty. */
    public int readFifo() {
        if (dsk_fifoCount == 0) return -1;
        // Oldest word sits at bits [(fifoCount*16)-1 : (fifoCount-1)*16].
        int shift = (dsk_fifoCount - 1) * 16;
        int word  = (int)((dsk_fifo >>> shift) & 0xFFFF);
        // Clear the slot just consumed.
        long mask = ~(0xFFFFL << shift);
        dsk_fifo &= mask;
        dsk_fifoCount--;
        return word;
    }

    /** How many words are currently in the disk FIFO (0–3). */
    public int fifoWordCount() { return dsk_fifoCount; }

    private int dskdatr() {
        int v = readFifo();
        if (v == -1) {
            return agnus != null ? agnus.getLastBusData() : 0xFFFF;
        }
        return v;
    }

    // =========================================================================
    // Register Access
    // =========================================================================

    /**
     * Variante di {@link #readRegister(int)} <b>priva di effetti collaterali</b>, per il
     * debugger/disassemblatore. Tre registri di Paula mutano lo stato quando li si legge,
     * ed è comportamento hardware voluto, non un difetto:
     * <ul>
     *   <li>{@code DSKDATR} ($008) estrae una word dalla FIFO del disco;</li>
     *   <li>{@code SERDATR} ($018) azzera OVRUN;</li>
     *   <li>{@code DSKBYTR} ($01A) azzera il bit DSKBYT.</li>
     * </ul>
     * Qui i primi due restituiscono lo stato <i>senza</i> consumarlo e il terzo lo
     * ricostruisce col bit ancora alto. Tutto il resto passa da {@code readRegister}
     * invariato. Vedi {@code AbstractAgnus.peekCustomRegister}.
     */
    public int peekRegister(int address) {
        address &= 0x1FE;
        switch (address) {
            case 0x008: {
                // Sbircia la word più vecchia della FIFO senza estrarla.
                if (dsk_fifoCount == 0) return agnus != null ? agnus.getLastBusData() : 0xFFFF;
                int shift = (dsk_fifoCount - 1) * 16;
                return (int) ((dsk_fifo >>> shift) & 0xFFFF);
            }
            case 0x018: {
                boolean savedOvrun = serOvrun;
                int v = buildSerdatr();
                serOvrun = savedOvrun;
                return v;
            }
            case 0x01A: {
                int savedIncoming = dskbytr_incoming;
                int v = buildDskbytr();
                dskbytr_incoming = savedIncoming;
                return v;
            }
            default:
                return readRegister(address);
        }
    }

    public int readRegister(int address) {
        address &= 0x1FE;
        switch (address) {
            case 0x008: return dskdatr();
            case 0x010: return adkcon & 0x7FFF; // ADKCONR: bit 15 (SET/CLR strobe) always reads 0
            case 0x012: return (potDat[0][1] << 8) | potDat[0][0];   // POT0DAT
            case 0x014: return (potDat[1][1] << 8) | potDat[1][0];   // POT1DAT
            case 0x016: return buildPotinp();
            case 0x018: return buildSerdatr();
            case 0x01A: return buildDskbytr();
            case 0x01C: return intena & 0x7FFF; // INTENAR: bit 15 is always 0 on OCS/ECS.
            case 0x01E: return intreq & 0x7FFF;   // INTREQR
            default:
                if (address >= 0x0A0 && address <= 0x0DE) return readAudioRegister(address);
                return 0xFFFF;
        }
    }

    /**
     * POTGOR ($DFF016). ⛔ Restituisce <b>solo</b> i quattro bit DAT (maschera {@code $5500}):
     * i bit di direzione non si rileggono. È {@code POTGOR()} dell'oracolo,
     * {@code inputdevice.cpp}: {@code v = handle_joystick_potgor(potgo_value) & 0x5500}.
     *
     * <p>⛔ <b>C'è UNA regola sola, e non guarda la direzione: il bit DAT è la tensione del
     * CONDENSATORE.</b> L'oracolo, {@code handle_joystick_potgor}, non ha nessun ramo
     * «uscita»: per ognuna delle quattro linee fa
     * <pre>
     *   potgor &amp;= ~pdat;
     *   if (pot_cap[joy][i] &gt; 100) potgor |= pdat;
     * </pre>
     * La direzione decide soltanto <b>come si carica</b> il condensatore, e questo succede
     * dentro {@link #capCheck(boolean)}: {@code charge = (potgo_value & pdat) ? 2 : -2},
     * <i>fast (dis)charge if output</i>.
     *
     * <p>⛔ <b>E il pulsante vince comunque</b>, perché nell'oracolo l'ultima parola arriva
     * DOPO il ramo d'uscita: {@code if (isbutton) charge = -2; // button press overrides
     * everything}. Un pulsante è un corto verso massa, il driver d'uscita di Paula è debole:
     * una linea programmata «uscita alta» viene tirata a zero lo stesso.
     *
     * <p>⚠️ È l'idioma con cui i demo aspettano il click a schermo fermo, e leggere il dato
     * scritto invece del condensatore lo rompeva: <i>Hardwired</i>, alla schermata «INSERT
     * HARDWIRED DISK 2», programma la porta 1 come {@code POTGO=$0F00} — entrambe le linee
     * uscite alte — e poi gira su {@code BTST #2,$DFF016} aspettando che DATLY scenda. Con la
     * rilettura del dato scritto DATLY restava 1 per sempre e il tasto destro non arrivava
     * mai: il disco 2 si poteva infilare quanto si voleva. Vedi
     * {@code PotgorButtonOverridesOutputTest}.
     *
     * <p>⚠️ {@code cap_check(false)} è chiamato <b>dalla lettura stessa</b>, come nell'oracolo
     * ({@code handle_joystick_potgor} lo fa in cima): è quello che dà la risposta immediata al
     * pulsante senza aspettare la riga successiva, perché con {@code charge = -2} il
     * condensatore perde 160 per lettura e un poll stretto lo porta sotto soglia subito.
     */
    private int buildPotinp() {
        capCheck(false);
        int v = 0;
        if (potCap[0][0] > 100) v |= 0x0100;   // DATLX — porta 1, linea X
        if (potCap[0][1] > 100) v |= 0x0400;   // DATLY — porta 1, linea Y
        if (potCap[1][0] > 100) v |= 0x1000;   // DATRX — porta 2, linea X
        if (potCap[1][1] > 100) v |= 0x4000;   // DATRY — porta 2, linea Y
        return v;
    }

    private int buildSerdatr() {
        int val = serReceiveBuffer & 0x3FF;
        if (serOvrun)               val |= 0x8000; // OVRUN
        if ((intreq & 0x0800) != 0) val |= 0x4000; // RBF
        if (!serTxbFull)            val |= 0x2000; // TBE  – buffer empty (serTxbFull=false means buffer has no pending byte)
        if (serTransmitShiftReg == 0) val |= 0x1000; // TSRE – shift register empty
        val |= 0x0800; // RXD idle (mark)

        // Reading SERDATR clears only OVRUN. RBF must be cleared explicitly via INTREQ write.
        serOvrun = false;
        return val;
    }

    private int buildDskbytr() {
        int val = dskbytr_incoming & 0x80FF;
        // DMAEN (bit 14): disk DMA is active and enabled in DMACON.
        if (diskState != DriveDmaState.OFF && (getDmacon() & 0x0210) == 0x0210) val |= 0x4000;
        // DSKWRITE (bit 13): current transfer is a write (CPU→disk).
        if ((dsklen & 0x4000) != 0) val |= 0x2000;
        // WORDEQUAL (bit 12): livello asserito dopo un match di DSKSYNC.
        //   Guard: dsk_syncCycle==0 significa che non c'è ancora stato alcun sync
        //   (o che DSKSYNC è stato riscritto: il vecchio match non vale più).
        if (dsk_syncCycle != 0 && agnus != null
                && agnus.getMasterClock() - dsk_syncCycle <= WORDEQUAL_WINDOW) val |= 0x1000;
        // Clear DSKBYT (bit 15) on read — hardware behaviour.
        dskbytr_incoming &= 0x7FFF;
        return val;
    }

    /**
     * Durata del livello WORDEQUAL (DSKBYTR bit 12) dopo un match di DSKSYNC, in
     * master-cycle. Vale 2 celle MFM (~3,95 µs): una cella a doppia densità dura
     * 7 CCK = 14 master-cycle (WinUAE {@code NORMAL_FLOPPY_SPEED}, vedi
     * {@code FloppyDrive.CYCLES_PER_BIT}).
     *
     * <p>vAmiga usa una cella sola ({@code agnus.clock - syncCycle <= USEC(2)}) e
     * WinUAE l'equivalente ({@code word == dsksync} sullo shift register vivo, vero
     * per una cella). Una cella sola però NON basta: le protezioni Rob Northen in
     * variante PIO campionano DSKBYTR con un loop
     * {@code BTST #4,$DFF01A / BEQ} che sull'68000 dura 26 cicli (≈27,6 misurati con
     * la contesa DMA dei bitplane), cioè quasi il DOPPIO della finestra. Il livello
     * viene quindi agganciato solo ~1 volta su 2 e, siccome ogni sync compare una
     * sola volta per giro, ogni ricerca costa in media 2 giri.
     *
     * <p>È esattamente il fallimento di <b>Last Battle</b> (IPF originale): la
     * protezione cerca in sequenza i sync $8911/$8912/$8914/$8951 sulla traccia 1
     * (Copylock, {@code dentype=cpdenCLAmiga}); con una cella sola i primi tre
     * costavano 2 giri ciascuno e sul quarto il watchdog del gioco (handler di
     * interrupt di livello 3, PC $022FA6) spegneva il motore del drive — dopodiché
     * il loop non può più uscire e lo schermo resta nero per sempre. Su WinUAE, che
     * è l'oracolo, la stessa sequenza passa e il gioco arriva al titolo: registri e
     * memoria sono bit-identici fino a questo punto, l'unica differenza misurata è
     * l'aggancio del livello. Con 2 celle ogni sync viene agganciato al primo giro.
     */
    private static final long WORDEQUAL_WINDOW = 28;

    public void writeRegister(int address, int value) {
        address &= 0x1FE;

        switch (address) {
            case 0x020:
                dskpth = value & 0x001F;
                dsklenWriteCount = 0;
                break;
            case 0x022:
                dskptl = value & 0xFFFF;
                dsklenWriteCount = 0;
                break;
            case 0x024: setDSKLEN(value); break;
            case 0x026: /* DSKDAT: write-only, but usually no effect in emulation */ break;
            case 0x030: setSERDAT(value); break;
            case 0x032: serper = value & 0xFFFF; break;
            case 0x034: setPOTGO(value); break;
            case 0x07E:
                dsksync = value & 0xFFFF;
                // Nuovo valore da confrontare: il match precedente non vale più.
                // Senza questo, il livello WORDEQUAL (largo 2 celle) potrebbe essere
                // letto come match del sync APPENA scritto. WinUAE non ne ha bisogno
                // perché confronta lo shift register vivo con il DSKSYNC corrente.
                dsk_syncCycle = 0;
                dsklenWriteCount = 0;
                break;
            case 0x09A: setINTENA(value); break;
            case 0x09C: setINTREQ(value); break;
            case 0x09E:
                setADKCON(value);
                dsklenWriteCount = 0;
                break;
            default:
                if (address >= 0x0A0 && address <= 0x0DE) writeAudioRegister(address, value);
                break;
        }
    }

    private int dsklenWriteCount = 0;

    private void setDSKLEN(int value) {
        int oldValue = dsklen;
        dsklen = value;

        if ((value & 0x8000) == 0) {
            diskState = DriveDmaState.OFF;
            dsklenWriteCount = 0;
            stopWritingOnDrives();
            return;
        }

        dsklenWriteCount++;

        if ((oldValue & value & 0x8000) != 0) {
            // Second consecutive write with bit 15 set → DMA start. Only the FIFO
            // is emptied here.
            //
            // ⛔ The READ shift register and its bit counter are NOT touched. They
            // free-run with the head from the moment the drive is selected, and the
            // word boundary of the data the DMA transfers is the one they already
            // carry. Clearing them re-anchored that boundary to the DSKLEN write, so
            // a whole track landed in Chip RAM shifted by a few bits. Kickstart reads
            // the raw track with WORDSYNC off and re-aligns the sector headers in
            // software, so the machine still boots — but through the slow
            // re-alignment path, which costs about 890 cck on the instant the loaded
            // program starts (measured on the CIA/TOD/latch1 boot chain).
            // A WRITE does clear the shifter: see below.
            dsk_fifo         = 0;
            dsk_fifoCount    = 0;
            dsk_writeShift   = 0;
            dsk_writeBits    = 0;
            // The selected drive's cell clock restarts from this write.
            if (agnus != null) agnus.restartSelectedDrivesBitCell();

            diskState = DriveDmaState.READ; // Default; overridden below
            dsklenWriteCount = 0;
            int length = dsklen & 0x3FFF;
            dskWordsLeft = length;

            if (length == 0) {
                raiseInterrupt(0x0002); // DSKBLK
                return;
            }

            int currentDmacon = getDmacon();
            boolean dmaMaster = (currentDmacon & 0x0200) != 0;
            boolean dmaDisk = (currentDmacon & 0x0010) != 0;
            boolean isRead = (dsklen & 0x4000) == 0;

            if (!isRead) {
                diskState = DriveDmaState.WRITE;
                // Writing clears the read shift register and the DSKBYT flag.
                dsk_dataReg = 0;
                dskbytr_incoming &= 0x7FFF;
                // Il drive selezionato passa in scrittura: da qui in avanti la sua
                // testina consuma le celle da nextWriteBit() invece di leggerle.
                // Come per la lettura, il lato comandato ha effetto immediato.
                if (agnus != null) {
                    agnus.commitSelectedDriveSideForRead();
                    agnus.startFloppyWriting();
                }
            } else {
                diskState = ((adkcon & 0x0400) != 0) ? DriveDmaState.WAIT : DriveDmaState.READ;
                // Il /SIDE comandato ha effetto immediato per la lettura (no settle
                // meccanico come il seek): applica subito un eventuale cambio lato
                // pendente così la DMA legge il lato voluto, indipendente dalla
                // velocità CPU. Vedi Agnus.commitSelectedDriveSideForRead().
                if (agnus != null) agnus.commitSelectedDriveSideForRead();
            }

            dsk_syncMatches = 0;
        }
    }

    private void setSERDAT(int value) {
        serTransmitBuffer = value & 0x1FF; // 9 data bits stored
        serTxbFull = true;
        // If the shift register is idle, start transmission immediately.
        if (serTransmitShiftReg == 0) {
            agnus.scheduleRel(EventSlot.SLOT_TXD, 0, EventID.TXD_BIT);
        }
        // If the shift register is busy the new byte will be picked up automatically
        // when the current word finishes (serviceTxdEvent checks serTxbFull).
    }

    /**
     * POTGO ($DFF034). Porting di {@code POTGO()} dell'oracolo ({@code inputdevice.cpp}).
     *
     * <p>⛔ Non è un assegnamento. I bit di <b>direzione</b> si prendono tutti dalla
     * scrittura, ma il bit di <b>dato</b> di una linea si aggiorna <b>solo se in questa
     * stessa scrittura</b> la sua direzione è messa a 1; altrimenti resta quello di prima.
     * È il {@code potgo_value = potgo_value & 0x5500} dell'oracolo, <i>keep state of data
     * bits</i>, seguito dal ciclo che copia il dato delle sole linee appena dichiarate
     * uscite.
     *
     * <p>Il bit 0 (START) riarma tutte e quattro le linee: contatore a zero e condensatore
     * da scaricare. Vedi {@link #capCheck(boolean)}.
     */
    private void setPOTGO(int value) {
        potgo = (potgo & 0x5500) | (value & 0xAA00);
        for (int i = 0; i < 8; i += 2) {
            final int dir = 0x0200 << i;
            if ((value & dir) != 0) {
                final int data = 0x0100 << i;
                potgo = (potgo & ~data) | (value & data);
            }
        }
        if ((value & 0x0001) != 0) {
            for (int j = 0; j < 2; j++) {
                for (int i = 0; i < 2; i++) {
                    potDatAct[j][i] = 1;
                    potDat[j][i] = 0;
                }
            }
        }
    }

    /** Scrittura di CPU/Copper a INTENA: copia letta subito, copia IPL un CCK dopo. */
    private void setINTENA(int value) {
        boolean set = (value & 0x8000) != 0;
        int bits = value & 0x7FFF;
        if (set) intena |= bits; else intena &= ~bits;
        scheduleShadowPoke(value, true);
    }

    /** Scrittura di CPU/Copper a INTREQ: copia letta subito, copia IPL un CCK dopo. */
    private void setINTREQ(int value) {
        boolean set = (value & 0x8000) != 0;
        int bits = value & 0x7FFF;
        if (set) intreq |= bits; else intreq &= ~bits;
        scheduleShadowPoke(value, false);
    }

    /**
     * Interrupt <b>generato da un chip</b> (audio, disco, Copper, blitter, CIA, seriale):
     * alza o abbassa <b>entrambe</b> le copie nello stesso istante, come WinUAE
     * {@code event_doint_delay_do_ext}, che fa {@code setclr(&intreq, ...)} e
     * {@code setclr(&intreq2, ...)} di seguito e poi chiama {@code doint()}.
     *
     * <p>Non è la stessa cosa di una scrittura del programma a INTREQ: quella passa da
     * {@link #setINTREQ(int)} e differisce la copia dell'IPL. Vedi {@link #intreq2}.
     */
    public void setINTREQ(boolean setclr, int value) {
        if (setclr) {
            intreq  |= value;
            intreq2 |= value;
        } else {
            intreq  &= ~value;
            intreq2 &= ~value;
        }

        // In a real Amiga, INTREQ bit 3 (PORTS) and 13 (EXTER) are set by the falling edge
        // of the CIA IRQ pins. Agnus handles this edge detection and calls assertPortsInterrupt/assertExterInterrupt.
        // Forcing them here based on level could prevent the software from clearing the interrupt.

        checkInterrupt();
    }

    /**
     * Ritardo, in master-cycle, con cui una scrittura di CPU/Copper raggiunge le copie
     * dell'IPL: <b>2 master-cycle = 1 CCK</b>, cioè il valore letterale di
     * {@code doint_delay_intreq} in WinUAE («IPL line changes 0.5 CCKs later. 68000 needs
     * one more CPU clock (0.5 CCK) before it detects it»),
     * {@code event2_newevent_xx(-1, 1 * CYCLE_UNIT, …)}.
     *
     * <p>Fino al 2026-08-14 valeva <b>1</b>, e il commento spiegava perché: il secondo
     * mezzo CCK il nostro modello lo spendeva nel latch del pin in
     * {@code Motorola68000.fallingEdge()}, che timbrava il cambio di pin un clock più
     * tardi di quanto l'oracolo timbra il suo. Corretta quella sfasatura di fase (vedi
     * {@code Motorola68000.fallingEdge()}, {@code pinChangeAt}), la compensazione non
     * serve più e il valore torna quello dell'oracolo.
     *
     * <p><b>Le due modifiche vanno insieme</b>: separate, ciascuna sposta di un clock il
     * riconoscimento dell'interrupt e il banco vAmigaTS {@code cpu/timing} lo vede subito
     * — misurato, 245 rossi su 253 con la sola prima. La misura del pin che aveva fissato
     * il valore vecchio (FIX #24, §S4 di vAmigaTS {@code cpu/timing}) resta valida: la
     * linea IPL si muove allo stesso master-cycle di prima, perché il clock tolto qui è
     * quello aggiunto là. Vedi {@code docs/vamigats-paula-audio-plan.md} §18.
     */
    private static final int INTREQ_SHADOW_DELAY_MC = 2;

    /**
     * Δ2: quanto WinUAE aspetta prima di elaborare una word consegnata dal DMA audio —
     * «{@code AUDxDAT is processed after 1 CCK delay}», {@code audio.cpp} {@code AUDxDAT()},
     * cioè {@code cyc = 1 * CYCLE_UNIT}. Un CCK = due master-cycle. Vedi
     * {@link AudioChannel#receiveDmaWord} e {@code docs/vamigats-paula-audio-plan.md} §22.
     */
    private static final int DMA_WORD_DELAY_MC = 2;

    /** Poke di CPU/Copper in attesa di raggiungere {@link #intreq2}/{@link #intena2}. */
    private long    shadowPokeAt = AbstractAgnus.NEVER;
    private int     shadowPokeValue;
    private boolean shadowPokeIsIntena;

    private void scheduleShadowPoke(int value, boolean isIntena) {
        if (shadowPokeAt != AbstractAgnus.NEVER) {
            // Due poke entro un CCK: in WinUAE sono due eventi distinti e il primo scade
            // comunque per primo, quindi lo si applica qui invece di perderlo.
            applyShadowPoke();
        }
        shadowPokeValue = value;
        shadowPokeIsIntena = isIntena;
        shadowPokeAt = agnus.getMasterClock() + INTREQ_SHADOW_DELAY_MC;
        rescheduleIrqEvent();
    }

    private void applyShadowPoke() {
        boolean set = (shadowPokeValue & 0x8000) != 0;
        int bits = shadowPokeValue & 0x7FFF;
        if (shadowPokeIsIntena) {
            if (set) intena2 |= bits; else intena2 &= ~bits;
        } else {
            if (set) intreq2 |= bits; else intreq2 &= ~bits;
        }
        shadowPokeAt = AbstractAgnus.NEVER;
        checkInterrupt();
    }

    private void setADKCON(int value) {
        boolean set = (value & 0x8000) != 0;
        int bits = value & 0x7FFF;
        syncAudioChannels();
        if (set) adkcon |= bits; else adkcon &= ~bits;
    }

    private void raiseInterrupt(int mask) {
        setINTREQ(true, mask);
    }

    /**
     * Ritardo, in master-cycle, fra il momento in cui la macchina a stati di un canale
     * decide l'interrupt e il momento in cui AUDxIR compare in INTREQ: <b>2 CCK</b>.
     *
     * <p>Oracolo: WinUAE {@code audio.cpp}
     * <pre>
     * static void setirq(int nr, int which) {
     *     // audio interrupts are delayed by 1 CCK
     *     INTREQ_INT(nr + 7, CYCLE_UNIT);
     * }
     * </pre>
     * e {@code custom.cpp}, {@code INTREQ_INT}, che in modo cycle-exact
     * ({@code m68k_interrupt_delay}) accoda l'evento a <b>{@code delay + CYCLE_UNIT}</b>:
     * il commento dice 1 CCK ma il ritardo effettivo e' quello passato PIU' uno, cioe' 2.
     * Solo dopo l'evento {@code intreq} viene aggiornato e {@code doint()} chiamato.
     */
    private static final int AUDIO_IRQ_DELAY_CYCLES = 4;   // 2 CCK = 4 master cycle

    /**
     * Alza AUDxIR come WinUAE: non subito, ma {@link #AUDIO_IRQ_DELAY_CYCLES} dopo.
     * Sollevarlo nello stesso ciclo ci metteva in anticipo sull'oracolo su tutta la
     * cartella vAmigaTS {@code Paula/Audio} (vedi {@code docs/vamigats-paula-audio-plan.md}).
     */
    private void raiseAudioInterrupt(int channel) {
        scheduleIntreq(7 + channel, AUDIO_IRQ_DELAY_CYCLES);
    }

    /**
     * Programma il set di un bit di INTREQ fra {@code delayCycles} master-cycle.
     * Se per quel bit c'e' gia' una richiesta in coda <b>prima</b>, quella vince: due
     * eventi ravvicinati sullo stesso bit collassano in uno, come in WinUAE dove il
     * secondo {@code setclr(&intreq, ...)} trova il bit gia' alzato.
     */
    private void scheduleIntreq(int bit, int delayCycles) {
        long when = agnus.getMasterClock() + delayCycles;
        if (setIntreq[bit] <= when) return;
        setIntreq[bit] = when;
        rescheduleIrqEvent();
    }

    public void setAudioDmaEnabledMask(int dmacon) {
        syncAudioChannels();
        boolean master = (dmacon & 0x0200) != 0;
        for (int i = 0; i < audioChannels.length; i++) {
            audioChannels[i].setDmaEnabled(master && (dmacon & (1 << i)) != 0);
        }
    }

    /**
     * Produce un sample di output facendo la <b>media pesata nel tempo</b> dell'uscita
     * di ciascun canale sull'intervallo appena trascorso, invece di leggerne il valore
     * istantaneo.
     *
     * <p>Oracolo: WinUAE {@code audio.cpp}, modo di interpolazione <i>anti</i>
     * ({@code sound_interpol = 1}, il DEFAULT) — {@code anti_prehandler} accumula
     * {@code current_sample * vol * dt} ad ogni passo dell'emulazione e
     * {@code samplexx_anti_handler} divide per il tempo accumulato.
     *
     * <p>Leggere il valore istantaneo (quello che si faceva prima) equivale a un
     * ricampionamento a vicino-più-prossimo: i fronti dell'uscita di Paula, che cadono
     * su una griglia da 7,09 MHz, vengono spostati fino a 22,7 µs per finire sulla
     * griglia a 44,1 kHz. Quel jitter è un rumore a banda larga con righe fortissime
     * accanto alla fondamentale. Misurato sul banco (una sinusoide da 500 Hz generata
     * come la genera Amiga Test Kit): <b>SNR 20,8 dB</b> col campionamento istantaneo
     * contro i <b>28,8 dB</b> del riferimento ideale, che l'integrazione raggiunge esatto.
     * Su un'onda quadra da 10 kHz il divario era ancora più largo, ma lì il grosso lo
     * faceva un secondo difetto — vedi {@code loadCurrentWord()}.
     *
     * <p>Il residuo a 28,8 dB non è nostro: sono le immagini della scaletta a 8 bit di
     * Paula (15,5 e 16,5 kHz per questo segnale), che ci sono anche in WinUAE e che il
     * filtro fisso dell'A500 attenua a valle. La misura vive in {@code PaulaAudioQualityTest}.
     */
    private void mixAudioSample() {
        int interval = mixIntervalCycles;
        mixIntervalCycles = 0;

        double left = 0.0;
        double right = 0.0;

        for (AudioChannel ch : audioChannels) {
            double sample = ch.takeAverageSample(interval);
            if (ch.index == 0 || ch.index == 3) left += sample;
            else                                right += sample;
        }

        audioRingBuffer.write((float) (left * 0.5), (float) (right * 0.5));
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Ritardo, in master-cycle, fra la discesa della linea /IRQ di un CIA e la comparsa di
     * PORTS/EXTER in INTREQ: <b>1 CCK</b>.
     *
     * <p>Oracolo: {@code cia.cpp} {@code ICR()} → {@code ICRIRQ()} →
     * {@code safe_interrupt_set()}, che in modo cycle-exact chiama
     * {@code INTREQ_INT(inum, 0)}; e {@code INTREQ_INT} accoda l'evento a
     * {@code delay + CYCLE_UNIT}, cioè a <b>un CCK</b> con {@code delay = 0}. È lo stesso
     * meccanismo del ritardo audio ({@link #AUDIO_IRQ_DELAY_CYCLES}), che parte da
     * {@code delay = CYCLE_UNIT} e quindi vale il doppio.
     *
     * <p>Misura (piano §7.1.17): underflow del Timer B → INTREQ bit 3 vale <b>+6 CCK</b>
     * sull'oracolo — 5 per il clock di CIA che la CIA stessa si prende
     * ({@code MOS8520.triggerIrq}) e 1 per questo evento. Prima erano 0 + 0.
     */
    private static final int CIA_IRQ_DELAY_CYCLES = 2;   // 1 CCK = 2 master cycle

    public void assertPortsInterrupt() { scheduleIntreq(3, CIA_IRQ_DELAY_CYCLES); }
    public void assertExterInterrupt() { scheduleIntreq(13, CIA_IRQ_DELAY_CYCLES); }
    public boolean isPortsInterruptAsserted() { return (intreq & 0x0008) != 0; }
    public boolean isExterInterruptAsserted() { return (intreq & 0x2000) != 0; }
    public void assertVertbInterrupt() {
        raiseInterrupt(0x0020);
    }
    public void assertCoperInterrupt() { raiseInterrupt(0x0010); }
    public void assertBlitInterrupt() { raiseInterrupt(0x0040); }

    public int getDiskDmaPointer() { return (dskpth << 16) | dskptl; }

    /** Word di sincronizzazione DSKSYNC corrente (diagnostica). */
    public int getDskSync() { return dsksync & 0xFFFF; }

    public void setDiskDmaPointerHigh(int high) {
        this.dskpth = high & 0x001F;
    }

    public void setDiskDmaPointer(int fullAddress) {
        this.dskpth = (fullAddress >> 16) & 0x001F;
        this.dskptl = fullAddress & 0xFFFE;
    }

    public boolean isDiskDmaEnabled() { return diskState != DriveDmaState.OFF; }
    public boolean isDiskDmaReading() { return diskState == DriveDmaState.READ; }
    public boolean isDiskDmaWrite() { return (dsklen & 0x4000) != 0; }
    public int getDiskLen() { return dsklen & 0x3FFF; }

    /**
     * Called by Agnus after each DMA word transfer (read or write).
     * Advances the DMA pointer and decrements the word counter; fires DSKBLK when done.
     */
    public void advanceDiskDma() {
        // Advance pointer by one word
        int ptr = getDiskDmaPointer();
        ptr = (ptr + 2) & 0x1FFFFE;
        setDiskDmaPointer(ptr);

        // Decrement word count (bits 13:0 of dsklen)
        int count = (dsklen & 0x3FFF) - 1;
        dsklen = (dsklen & 0xC000) | (count & 0x3FFF);

        if (count <= 0) {
            finishDiskDma();
        }
    }

    public int[] reloadAudioChannel(int chan) {
        if (chan < 0 || chan > 3) return null;
        raiseInterrupt(0x0080 << chan);
        int addr = ((audLCH[chan] & 0x001F) << 16) | (audLCL[chan] & 0xFFFE); // word-aligned
        int len  = audLEN[chan] & 0xFFFF;
        return new int[]{addr, len};
    }

    /** Whether the audio slot of {@code chan} carries a request reported at this line's DMAL. */
    public boolean isAudioDmaRequest(int chan) {
        return chan >= 0 && chan < 4 && audioChannels[chan].dmalRequest
                && audioChannels[chan].dmaRequest;
    }

    /**
     * Whether channel {@code chan} is <b>asking</b> for a word, regardless of the DMAL.
     * The two levels are distinct: the channel raises the request as soon as its period
     * expires, while {@link #isAudioDmaRequest} also requires the request to have been
     * reported at the start of this line — a request raised later waits for the next one.
     */
    public boolean isAudioChannelDmaRequest(int chan) {
        return chan >= 0 && chan < 4 && audioChannels[chan].dmaRequest;
    }

    public int getAudioDmaAddress(int chan) {
        return chan >= 0 && chan < 4 ? audioChannels[chan].dmaPointer : 0;
    }

    /**
     * The word of an audio DMA slot. The slot is allocated one CCK before it runs, so a
     * DMACON write in between can turn the channel's DMA off while its cycle still happens:
     * the pointer moves on and the word reaches the channel as a CPU write to AUDxDAT would.
     */
    public void receiveAudioDmaWord(int chan, int value) {
        if (chan >= 0 && chan < 4) {
            syncAudioChannels();
            AudioChannel ch = audioChannels[chan];
            if (ch.dmaEnabled) {
                ch.receiveDmaWord(value);
            } else {
                ch.dmaPointer = (ch.dmaPointer + 2) & 0x1FFFFE;
                audDAT[chan] = value & 0xFFFF;
                ch.writeData(value);
            }
        }
    }

    /**
     * An audio DMA slot whose cycle merged with a bitplane fetch: the request is consumed and
     * no word arrives. A channel still waiting for its first words keeps waiting.
     */
    public void dropAudioDmaSlot(int chan) {
        if (chan >= 0 && chan < 4) {
            syncAudioChannels();
            AudioChannel ch = audioChannels[chan];
            ch.dmaRequest = false;
            ch.dmalRequest = false;
            ch.refreshActiveMask();
        }
    }

    public void startAudioOutput() {
        audioOutput.start();
    }

    public void stopAudioOutput() {
        audioOutput.stop();
    }

    public AudioRingBuffer getAudioRingBuffer() {
        return audioRingBuffer;
    }

    /**
     * Deriva verso la rete il chunk PCM già convertito e filtrato
     * (docs/remote-play-plan.md §4.2). {@code null} la rimuove.
     */
    public void setPcmTap(JavaSoundAudioOutput.PcmTap tap) {
        audioOutput.setPcmTap(tap);
    }

    public void setLedFilterEnabled(boolean enabled) {
        audioOutput.setLedFilterEnabled(enabled);
    }

    public int getAudioCurrentSample(int chan) {
        return chan >= 0 && chan < 4 ? audioChannels[chan].currentSample : 0;
    }

    /**
     * L'uscita del canale <b>verso il mixer</b>: {@code (current_sample * mixvol) & adk_mask}
     * di WinUAE ({@code audio.cpp}, quel che {@code anti_prehandler} integra). Intervallo
     * -8192..8128.
     *
     * <p>Non è {@code getAudioCurrentSample(chan) * AUDxVOL}, e le due differenze sono
     * entrambe volute:
     * <ul>
     *   <li>il volume è quello <b>latchato</b> a {@code loadper()}, non il registro —
     *       una scrittura di AUDxVOL entra nel mixer al confine di periodo successivo;</li>
     *   <li>un canale che <b>modula</b> il successivo è muto: {@code adk_mask} vale 0
     *       per ogni canale che compare in ADKCON come modulatore di volume o di periodo
     *       ({@code audio_update_adkmasks}: {@code t = adkcon | (adkcon >> 4)}).</li>
     * </ul>
     */
    public int getAudioMixerOutput(int chan) {
        if (chan < 0 || chan > 3) return 0;
        int t = adkcon | (adkcon >> 4);
        if ((t & (1 << chan)) != 0) return 0;    // adk_mask = 0
        return audioChannels[chan].currentSample * audioChannels[chan].mixVolume();
    }

    private int readAudioRegister(int address) {
        int chan = (address - 0x0A0) >> 4;
        if (chan < 0 || chan > 3) return 0xFFFF;
        switch (address & 0xF) {
            case 0x0: return audLCH[chan];
            case 0x2: return audLCL[chan];
            case 0x4: return audLEN[chan];
            case 0x6: return audPER[chan];
            case 0x8: return audVOL[chan] & 0x7F;
            case 0xA: return audDAT[chan];
            default: return 0xFFFF;
        }
    }

    private void writeAudioRegister(int address, int value) {
        int chan = (address - 0x0A0) >> 4;
        if (chan < 0 || chan > 3) return;
        syncAudioChannels();
        switch (address & 0xF) {
            case 0x0:
                audLCH[chan] = value & 0x001F;
                break;
            case 0x2:
                audLCL[chan] = value & 0xFFFE;
                break;
            case 0x4:
                audLEN[chan] = value & 0xFFFF;
                break;
            case 0x6:
                audPER[chan] = value & 0xFFFF;
                audioChannels[chan].reloadPeriodClocks();
                break;
            case 0x8:
                // Solo il registro (audvol): nel mixer ci entra al confine di periodo
                // successivo, quando loadPeriod() latcha mixvol. Vedi AudioChannel.mixVolume.
                audVOL[chan] = Math.min(value & 0x7F, 64);
                break;
            case 0xA:
                audDAT[chan] = value & 0xFFFF;
                audioChannels[chan].writeData(value);
                break;
        }
    }

    public int getIntena() { return intena; }
    public int getIntreq() { return intreq; }

    private void stopWritingOnDrives() {
        if (agnus != null) {
            agnus.stopFloppyWriting();
        }
    }

    private int shadowAddress(int chan) {
        return (((audLCH[chan] & 0x001F) << 16) | (audLCL[chan] & 0xFFFE)) & 0x1FFFFE;
    }

    private final class AudioChannel {

        private final int index;
        private boolean dmaEnabled;
        private boolean dmaRequest;
        /** The request as reported at this line's DMAL: only then does the slot fetch. */
        private boolean dmalRequest;
        private int dmaPointer;
        private int remainingWords;
        private int nextWord;
        private boolean nextWordValid;
        private boolean nextWordEndsBlock;
        private int currentWord;
        private boolean currentWordValid;
        private boolean currentWordEndsBlock;
        private boolean dmaPriming;
        /**
         * La word di priming è arrivata e la <b>prossima</b> è la prima che suona: è lo
         * stato 5 di WinUAE. Serve per una ragione sola — quella word <b>non scala la
         * lunghezza residua</b>. WinUAE toglie uno a {@code wlen} nello stato 1 (la word
         * scartata) e poi una volta per ogni word che arriva <i>mentre il canale è negli
         * stati 2/3</i> ({@code event_audxdat_func}); l'arrivo che fa passare da 5 a 2 non
         * cade in nessuno dei due casi, quindi non conta. Contarlo faceva suonare
         * <b>LEN-1</b> word per blocco invece di LEN: il blocco si chiudeva una word
         * prima e da lì in poi eravamo due campioni avanti all'oracolo.
         */
        private boolean dmaFirstWord;
        /**
         * Il canale sta suonando una word scritta dalla CPU in AUDxDAT con il DMA
         * spento — gli stati 2/3 di WinUAE, contro lo stato 0 di riposo. Distingue
         * la scrittura che <i>avvia</i> il canale (che chiede l'interrupt) da tutte
         * quelle successive, che sono solo il rifornimento chiesto dall'interrupt
         * precedente e non ne devono chiedere un altro. Vedi {@link #writeData}.
         */
        private boolean cpuDriven;
        private int bytePhase;
        private int periodCounter;
        private int periodClocks;
        /**
         * Volume <b>latchato</b>, cioè quello che entra davvero nel mixer: WinUAE
         * {@code data.mixvol}. AUDxVOL (il registro, {@code data.audvol}) ci finisce
         * dentro solo a {@code loadper()} — vedi {@link #loadPeriod()} — quindi una
         * scrittura del volume ha effetto al <b>confine di periodo successivo</b>, non
         * subito. Applicarlo all'istante ci metteva in anticipo sull'oracolo su tutta
         * la modulazione di volume (docs/vamigats-paula-audio-plan.md §9.4).
         */
        private int mixVolume;
        private float mixVolumeScale;
        private int currentSample;
        /** Integrale di {@code currentSample * mixVolumeScale} sui cicli dell'intervallo. */
        private double sampleAccum;
        /**
         * Master-cycle che mancano a {@link #processDmaWord()}, cioè il ritardo di un CCK
         * con cui WinUAE elabora una word consegnata dal DMA (Δ2, vedi
         * {@link #receiveDmaWord}). 0 = niente in sospeso.
         */
        private int dmaWordDelay;

        private AudioChannel(int index) {
            this.index = index;
        }

        private void reset() {
            dmaEnabled = false;
            dmaRequest = false;
            dmalRequest = false;
            dmaPointer = 0;
            remainingWords = 0;
            nextWord = 0;
            nextWordValid = false;
            nextWordEndsBlock = false;
            currentWord = 0;
            currentWordValid = false;
            currentWordEndsBlock = false;
            dmaPriming = false;
            dmaFirstWord = false;
            cpuDriven = false;
            bytePhase = 0;
            periodClocks = computePeriodClocks();
            periodCounter = periodClocks;
            latchMixVolume();
            currentSample = 0;
            sampleAccum = 0.0;
            dmaWordDelay = 0;
            refreshActiveMask();
        }

        /**
         * DMACON ha acceso o spento il canale.
         *
         * <p>Lo <b>spegnimento non ferma il canale</b>, e non è un dettaglio: in WinUAE
         * ({@code audio_state_channel2}) solo gli stati 1 e 5 — l'avvio del DMA, prima che
         * la prima word entri in riproduzione — controllano {@code chan_ena} e vanno in
         * {@code zerostate}. Negli stati 2 e 3, cioè a canale che suona, {@code chan_ena}
         * cambia soltanto <i>chi rifornisce</i>: il canale prosegue in modo a interrupt,
         * ripete l'ultima AUDxDAT ({@code loaddat} incondizionato) e si ferma solo quando
         * la CPU smette di acknowledgare. È esattamente ciò che {@code simple/dmaaud2}
         * misura — «the frequency changes, because no samples are fed into AUD0DAT any
         * more» — e ciò che il gemello {@code dmaaud1} mostra sullo schermo, dove il
         * gestore di livello 4 è ancora invocato sette secondi dopo lo spegnimento.
         *
         * <p>E per lo stesso motivo <b>l'accensione dipende da dove il canale si trova</b>:
         * la sequenza d'avvio (0→1→5) è del solo {@code case 0}, cioè del canale fermo. Un
         * canale già negli stati 2/3 la scrittura di DMACON la vede soltanto come un cambio
         * di rifornitore. Sono le due facce della stessa domanda, e finora ne rispondevamo
         * una sola: {@code timing/dmatim2} è il canale fermo (che deve scaricare il
         * fine-blocco pendente), {@code timing/dmatim3} il canale che suona (che non deve
         * ricominciare da capo).
         */
        private void setDmaEnabled(boolean enabled) {
            if (dmaEnabled == enabled) return;
            // Lo stato PRIMA della scrittura di DMACON: a DMA spento isPlaying() sono gli
            // stati 2/3 di WinUAE (il canale suona in modo a interrupt), il resto è lo
            // stato 0, il canale fermo. La sequenza d'avvio del DMA è del secondo caso e
            // basta — vedi sotto.
            final boolean wasPlaying = isPlaying();
            dmaEnabled = enabled;
            if (enabled && wasPlaying) {
                // Stati 2/3: DMACON accende il DMA su un canale che <b>sta ancora
                // suonando</b>, e in WinUAE non succede niente. `audio_state_machine()`
                // richiama `audio_state_channel2(nr, false)`, che nei case 2/3 aggiorna
                // `dmaenstore` ed esce al `if (!perfin) return true;`: nessun priming,
                // nessuna richiesta immediata. Il DMA riprende semplicemente a rifornire
                // al confine di periodo successivo — `case 3: if (chan_ena) { loaddat(nr);
                // setdr(nr, false); if (intreq2 && napnav) setirq(nr, 31); }` — che è la
                // strada normale di advanceSampleByte()/loadCurrentWord().
                //
                // Ripartire dal priming (0->1->5) come si faceva prima costa tre errori in
                // fila, tutti visibili su vAmigaTS timing/dmatim3 (piano §23.3): una
                // richiesta d'interrupt di tipo sbagliato (l'avvio invece del fine-blocco),
                // la lunghezza residua fuori fase e quindi nessun fine-blocco alla riga
                // dopo, e il puntatore che avanza invece di ricaricarsi a ogni giro.
                cpuDriven = false;      // il DMA prende il posto del modo a interrupt
                refreshActiveMask();
                return;
            }
            if (enabled) {
                // Stato 0 -> 1. Se il canale era FERMO e si porta dietro un fine-blocco
                // non ancora consegnato, l'accensione del DMA lo <b>scarica subito</b>:
                //
                //   case 0: if (chan_ena) { ... cdp->dsr = true;
                //       if (cdp->intreq2) { setirq(nr, 0); cdp->intreq2 = false; } }
                //
                // È l'unico interrupt audio che nasce da una scrittura di DMACON invece
                // che da uno slot o da un confine di periodo, e senza di esso su
                // vAmigaTS timing/dmatim2 mancava un'invocazione intera del gestore alle
                // righe 112 e 160 (piano §23). Vive solo perché `intreq2` SOPRAVVIVE allo
                // spegnimento: vedi clearDmaPipeline().
                //
                // Qui `cpuDriven` è già falso: il ramo si prende solo con `wasPlaying`
                // falso, che a DMA spento è esattamente `!cpuDriven`.
                reloadDmaBlock();
                dmaPriming = true;
                if (nextWordEndsBlock) {
                    nextWordEndsBlock = false;
                    raiseAudioInterrupt(index);     // WinUAE: setirq(nr, 0)
                }
                requestNextWord();
            } else if (dmaPriming || dmaFirstWord) {
                // Stati 1 e 5: "if (!chan_ena) { zerostate(nr, false); return true; }".
                // Il canale non ha ancora suonato niente e non suonerà: dmaonoff1.
                clearDmaPipeline();
            } else {
                // Stati 2/3: il canale sopravvive. Cade solo la pipeline DMA — la word
                // in coda non si perde davvero, perché AUDxDAT la contiene ed è da lì
                // che loaddat() ripesca (advanceSampleByte, ramo di fallback).
                dmaRequest = false;
                nextWord = 0;
                nextWordValid = false;
                // `nextWordEndsBlock` (l'`intreq2` di WinUAE) NON si azzera: vedi
                // clearDmaPipeline(). Tenerlo è inerte finché il DMA resta spento —
                // lo consumano solo loadCurrentWord() e consumeQueuedWord(), entrambi
                // dietro `nextWordValid` — e diventa osservabile alla riaccensione.
                cpuDriven = true;
            }
            refreshActiveMask();
        }

        /**
         * Il {@code zerostate(nr, false)} di WinUAE: il canale esce dalla macchina a stati.
         *
         * <p>⚠️ <b>Non azzera il fine-blocco pendente.</b> In modo cycle-exact — quello
         * dell'oracolo — {@code zerostate} lascia stare {@code intreq2}:
         * <pre>
         * if (!currprefs.cpu_memory_cycle_exact || reset) { cdp-&gt;intreq2 = false; }
         * </pre>
         * Il flag sopravvive quindi allo spegnimento del DMA e resta lì finché la
         * riaccensione non lo scarica ({@link #setDmaEnabled}, {@code setirq(nr, 0)}).
         * Solo il <b>reset</b> vero (l'unico {@code zerostate(nr, true)}) lo cancella, ed è
         * infatti quel che fa {@link #reset()}.
         */
        private void clearDmaPipeline() {
            dmaRequest = false;
            remainingWords = 0;
            nextWord = 0;
            nextWordValid = false;
            currentWord = 0;
            currentWordValid = false;
            currentWordEndsBlock = false;
            dmaPriming = false;
            dmaFirstWord = false;
            cpuDriven = false;
            bytePhase = 0;
            // currentSample NON si azzera: zerostate() di WinUAE non tocca
            // data.current_sample. Un canale che si ferma resta all'ultimo valore
            // uscito, non torna a zero — vedi advanceSampleByte(), stesso motivo.
        }

        /**
         * Il canale è in riproduzione, cioè negli stati <b>2/3</b> di WinUAE — gli unici
         * in cui il period counter gira ({@code evtime != MAX_EV}).
         *
         * <p>Gli altri tre si riconoscono da qui: {@code dmaPriming}/{@code dmaFirstWord}
         * sono gli stati 1 e 5 (avvio del DMA, prima word non ancora in riproduzione),
         * e «né DMA né modo a interrupt» è lo stato 0, il canale fermo.
         */
        private boolean isPlaying() {
            if (dmaEnabled) {
                return !dmaPriming && !dmaFirstWord;
            }
            return cpuDriven;
        }

        /**
         * Avanza il canale di {@code cycles} master-cycle <b>integrando</b> l'uscita.
         *
         * <p>Fra due scadenze del period-counter l'uscita del canale è costante, quindi
         * l'integrale sull'intervallo è esatto: basta pesare ogni valore per il numero
         * di cicli in cui è rimasto in uscita. È il {@code anti_prehandler} di WinUAE,
         * che accumula {@code current_sample * vol * best_evtime} ad ogni passo.
         *
         * <p>Il conteggio dei passaggi di byte è identico a prima
         * ({@code periodCounter -= cycles} seguito dai riarmi a {@code periodClocks}):
         * qui si sottrae a pezzi per sapere <i>quanto a lungo</i> è valso ogni valore.
         */
        private void tick(int cycles) {
            // Δ2: l'intervallo si spezza sulla scadenza della word DMA in sospeso, così il
            // gestore differito cade nel master-cycle esatto e non a fine intervallo.
            // computeAudioMinCounter() tiene conto della stessa scadenza, quindi qui il
            // taglio è al più uno per chiamata — ma il ciclo lo gestisce comunque.
            while (dmaWordDelay > 0 && dmaWordDelay <= cycles) {
                int step = dmaWordDelay;
                dmaWordDelay = 0;
                advancePeriod(step);
                cycles -= step;
                processDmaWord();
            }
            if (dmaWordDelay > 0) {
                dmaWordDelay -= cycles;
            }
            advancePeriod(cycles);
        }

        private void advancePeriod(int cycles) {
            if (cycles == 0) {
                return;
            }
            if (!isPlaying()) {
                // Stati 0, 1 e 5 di WinUAE: tutti e tre mettono `evtime = MAX_EV`, cioè
                // il period counter NON gira. Per gli stati 1/5 (avvio del DMA) farlo
                // girare regala al canale confini di periodo prematuri, che consumano
                // word: su dmamod3 bastava a scambiare volume e periodo per sempre,
                // perché le parole della tabella si alternano. Per lo stato 0 (il canale
                // fermo, zerostate) sono confini che chiedono interrupt di rifornimento
                // a un canale che non sta suonando.
                //
                // L'uscita continua a essere integrata: il campione resta quello, e
                // toglierlo dalla maschera attiva lo conterebbe come zero.
                sampleAccum += currentSample * (double) mixVolumeScale * cycles;
                return;
            }
            int remaining = cycles;
            while (remaining >= periodCounter) {
                int step = periodCounter;
                sampleAccum += currentSample * (double) mixVolumeScale * step;
                remaining -= step;
                loadPeriod();                   // periodClocks >= 2: il ciclo termina sempre
                advanceSampleByte();
            }
            sampleAccum += currentSample * (double) mixVolumeScale * remaining;
            periodCounter -= remaining;
        }

        /**
         * Il {@code loadper()} di WinUAE: riarma il contatore di periodo <b>e latcha il
         * volume</b>.
         *
         * <pre>
         * static void loadper(int nr) {
         *     cdp-&gt;evtime = cdp-&gt;per;
         *     cdp-&gt;data.mixvol = cdp-&gt;data.audvol;
         * }
         * </pre>
         *
         * Le due cose stanno insieme perché sono la stessa cosa: il volume che il mixer
         * userà per il campione che sta per uscire è quello che AUDxVOL aveva al confine
         * di periodo. Vedi {@link #mixVolume}.
         *
         * <p><b>Con il DMA spento il confine basso non latcha.</b> WinUAE ci chiama
         * {@code loadperm1()} invece di {@code loadper()}:
         * <pre>
         * case 3: if (cdp-&gt;pbufldl) { newsample(nr, dat2 &amp; 0xff);
         *             if (chan_ena) loadper(nr); else loadperm1(nr); ... }
         * </pre>
         * e {@code loadperm1} riarma il contatore <i>senza</i> toccare {@code mixvol}. Un
         * canale in modo a interrupt latcha quindi il volume <b>una volta per word</b>, al
         * confine alto, non due. Si vede su tutta la modulazione di volume manuale
         * ({@code irqmod1}, {@code irqmod3}), dove un latch di troppo anticipa di mezzo
         * periodo ogni gradino della rampa.
         *
         * <p>({@code loadperm1} spezza anche il periodo in {@code per-1} + {@code 1} CCK
         * per campionare {@code isirq} un CCK prima del confine: la durata totale non
         * cambia, e quel CCK di anticipo non è modellato qui.)
         */
        private void loadPeriod() {
            periodCounter = periodClocks;
            if (dmaEnabled || bytePhase == 0) {
                latchMixVolume();
            }
        }

        private void latchMixVolume() {
            mixVolume = Math.min(audVOL[index] & 0x7F, 64);
            mixVolumeScale = mixVolume / (128.0f * 64.0f);
        }

        /** Il {@code mixvol} corrente, per {@link Paula#getAudioMixerOutput(int)}. */
        private int mixVolume() {
            return mixVolume;
        }

        /**
         * Media dell'uscita sull'intervallo appena chiuso, e azzeramento
         * dell'accumulatore. {@code intervalCycles} è la durata PIENA dell'intervallo:
         * la parte in cui il canale è stato inattivo non è nell'accumulatore e vale
         * zero, esattamente come in WinUAE, dove il prehandler gira comunque su tutti
         * e quattro i canali e somma zero per quelli fermi.
         */
        private double takeAverageSample(int intervalCycles) {
            double accum = sampleAccum;
            sampleAccum = 0.0;
            return intervalCycles > 0 ? accum / intervalCycles : 0.0;
        }

        /**
         * Scrittura della CPU in AUDxDAT. Con il DMA acceso rifornisce e basta;
         * con il DMA spento è il modo "a interrupt", ed è l'unico punto in cui la
         * scrittura può chiedere un interrupt — <b>solo</b> quella che trova il
         * canale fermo.
         *
         * <p>Alzare AUDxIR a <i>ogni</i> scrittura è il modo di non far partire
         * mai più nulla: il gestore di livello 4 che rifornisce il campione si
         * ri-arma da solo prima della propria RTE, rientra subito, e gli interrupt
         * di livello più basso — VERTB in testa — non vengono serviti mai più.
         * Il gioco resta a schermo nero con INTREQ fermo su AUD0+VERTB e la CPU
         * al 100% dentro il gestore audio. In modo a interrupt il ritmo lo detta
         * il periodo: la word successiva la chiede {@link #advanceSampleByte()}
         * quando i due byte sono stati suonati.
         *
         * <p>Oracolo: WinUAE {@code audio.cpp}, {@code audio_state_channel2} stato
         * 0 — {@code else if (cdp->dat_written && !isirq(nr))}, che passa allo
         * stato 2 con {@code setirq}. Con l'interrupt già pendente la scrittura
         * non fa nulla e il canale resta fermo ({@code zerostate}).
         */
        private void writeData(int value) {
            nextWord = value & 0xFFFF;
            nextWordValid = true;
            nextWordEndsBlock = false;
            if (dmaEnabled) {
                // Un modulatore non manda mai la word in riproduzione: la legge da
                // audDAT ai confini di periodo (vedi advanceModulator).
                if (!currentWordValid && !isModulator()) {
                    loadCurrentWord();
                }
                refreshActiveMask();
                return;
            }
            if (cpuDriven) {
                // Rifornimento chiesto dall'interrupt precedente: la word resta in
                // attesa, la prende advanceSampleByte() a fine periodo.
                refreshActiveMask();
                return;
            }
            if ((intreq & (0x0080 << index)) != 0) {
                // Interrupt del canale ancora pendente: il canale non riparte.
                refreshActiveMask();
                return;
            }
            cpuDriven = true;
            raiseAudioInterrupt(index);         // WinUAE: setirq(nr, 1)
            if (isModulator()) {
                // `loaddat(nr)` su un modulatore non manda in riproduzione niente:
                // applica la modulazione di VOLUME, e la applica QUI, all'ingresso
                // nello stato 2 — non al primo confine di periodo. Il primo confine
                // e' percio' il 2->3, cioe' quello del PERIODO: bytePhase parte da 1.
                //
                // Farlo partire da 0 sfasa di mezzo periodo l'intera tabella di
                // modulazione, e su una tabella che alterna volume e periodo
                // (irqmod3/4) scambia i due per sempre: le parole del volume
                // finivano in AUDxPER, il canale modulato si fermava al primo giro
                // e la traccia si chiudeva a 61 eventi contro i 13995 dell'oracolo.
                if ((adkcon & (1 << index)) != 0) {
                    audioChannels[index + 1].setModulatedVolume(audDAT[index]);
                }
                consumeQueuedWord();
                loadPeriod();                   // WinUAE: loadper nel case 2 (pbufldl)
                bytePhase = 1;
            } else {
                loadCurrentWord();              // loaddat(nr): dat2 = dat
                loadPeriod();
                // ...e `newsample(nr, dat2 >> 8)` nello STESSO istante: il case 0
                // mette pbufldl = true e richiama subito la macchina a stati. Il byte
                // alto esce alla scrittura di AUDxDAT, non un periodo dopo. In una
                // traccia riportata al proprio primo evento lo sfasamento e'
                // invisibile, ma sposta di un periodo intero le bande gialle del
                // gestore rispetto al suono — che e' cio' che lo screenshot vede.
                advanceSampleByte();
            }
            refreshActiveMask();
        }

        /**
         * Lo slot DMA ha consegnato la word. <b>Δ2</b>: qui succede solo ciò che WinUAE fa
         * dentro {@code AUDxDAT()} — il dato e, se il canale sta già suonando, {@code intreq2} —
         * mentre tutto il resto è armato a {@link #DMA_WORD_DELAY_MC} master-cycle, cioè
         * <b>un CCK dopo</b>:
         *
         * <pre>
         * if (chan_ena) { cdp-&gt;dat = v; cdp-&gt;dat_written = true; }
         * if (chan_ena) {
         *     cyc = 1 * CYCLE_UNIT;                    // AUDxDAT is processed after 1 CCK delay
         *     if ((cdp-&gt;state &amp; 15) == 2 || (cdp-&gt;state &amp; 15) == 3) {
         *         if (cdp-&gt;wlen == 1) cdp-&gt;intreq2 = true;   // But INTREQ2 is set immediately
         *     }
         * }
         * event2_newevent_xx(-1, cyc, vv, event_audxdat_func);
         * </pre>
         *
         * <p>La condizione è attiva nella configurazione dell'oracolo: dipende da
         * {@code cpu_compatible}, che {@code cycle_exact=full} non imposta ma che
         * {@code default_prefs} mette a 1 (§9.2.1 del piano).
         *
         * <p>Misurato su `timing/dmalen0` (piano §22): lo slot cade a `hpos 17` su entrambi i
         * lati e la richiesta d'interrupt dell'oracolo segue di 1 CCK esatto, su tutti i rami
         * (`which=10` dello stato 1 come `which=31` dello stato 3) e su tutte le otto righe
         * del blocco. Senza questo CCK il gestore prende il confine d'istruzione precedente
         * e la banda si sposta di 5 CCK = 20 px.
         *
         * <p>⚠️ Il §9.2.1 aveva provato Δ2 e l'aveva revocata: differiva <i>tutto</i> il corpo,
         * quando la word che arriva a un canale già in riproduzione non deve entrare in
         * riproduzione affatto. Quella metà è il §21; questa è il ritardo vero e proprio, e le
         * due insieme sono la trascrizione completa.
         */
        private void receiveDmaWord(int value) {
            if (!dmaRequest) return;
            dmalRequest = false;
            audDAT[index] = value & 0xFFFF;
            nextWord = value & 0xFFFF;
            nextWordValid = true;
            dmaRequest = false;

            dmaPointer = (dmaPointer + 2) & 0x1FFFFE;
            if (!dmaPriming && !dmaFirstWord) {
                // `intreq2` si alza SUBITO, prima del ritardo: è l'unica cosa che, negli
                // stati 2/3, WinUAE non accoda. Il decremento di `wlen` che lo giustifica
                // avviene invece nel gestore differito, quindi qui si anticipa il suo
                // risultato: `<= 1` e non `== 1`, perché il decremento è clampato a zero e
                // la lunghezza residua ci resta finché `requestNextWord()` non ricarica il
                // blocco. Con `== 1` la word successiva a quella di fine blocco perdeva il
                // proprio interrupt — misurato su dmalen0, una richiesta in meno per blocco.
                nextWordEndsBlock = remainingWords <= 1;
            }
            dmaWordDelay = DMA_WORD_DELAY_MC;
            refreshActiveMask();
        }

        /** Il corpo di {@code event_audxdat_func}: un CCK dopo lo slot. Vedi {@link #receiveDmaWord}. */
        private void processDmaWord() {
            if (dmaPriming) {
                nextWord = 0;
                nextWordValid = false;
                nextWordEndsBlock = false;
                if (remainingWords != 1) {
                    remainingWords = (remainingWords - 1) & 0xFFFF;
                }
                reloadDmaPointer();
                dmaPriming = false;
                dmaFirstWord = true;
                raiseAudioInterrupt(index);
                requestNextWord();
                refreshActiveMask();
                return;
            }
            final boolean startsPlayback = dmaFirstWord;
            if (dmaFirstWord) {
                // Stato 5 -> 2: questa word entra in riproduzione ma non scala LEN,
                // ed è QUI che il period counter riparte — WinUAE, case 5: loadper(nr).
                dmaFirstWord = false;
                loadPeriod();
                nextWordEndsBlock = remainingWords == 0;
            } else if (remainingWords > 0) {
                remainingWords--;
            }

            // Una word che arriva a un canale GIA' in riproduzione non entra in
            // riproduzione allo slot DMA. Oracolo: WinUAE audio.cpp, event_audxdat_func —
            // negli stati 2 e 3 con il canale in DMA aggiorna soltanto `wlen` (e
            // `intreq2`, che AUDxDAT ha gia' alzato subito) e NON chiama ne' loaddat()
            // ne' newsample(). La word entra al confine di periodo successivo, ed e' li'
            // che nasce l'interrupt di fine blocco: `case 3: ... loaddat(nr); if
            // (cdp->intreq2 && napnav) setirq(nr, 31);`.
            //
            // Misurato su vAmigaTS timing/dmatim5 (docs/vamigats-paula-audio-plan.md
            // §21): lo slot DMA del canale 0 cade a hpos 17 su ENTRAMBI i lati — quindi
            // l'arbitraggio e' esatto — ma l'oracolo chiede l'interrupt 1 o 2 CCK dopo,
            // secondo la fase 2/3 in cui si trova il canale, mentre noi lo chiedevamo
            // nello stesso ciclo dello slot. Con AUD0PER=1 quei 2 CCK fanno mancare al
            // gestore di livello 4 il confine del `dbra` del main loop, e la banda gialla
            // si sposta di 6 CCK: e' l'unica riga sbagliata dello screenshot.
            //
            // Resta immediato il solo avvio (stato 5 -> 2), che in WinUAE passa dall'altro
            // ramo di event_audxdat_func e chiama la macchina a stati subito. Il
            // modulatore non manda mai la word in riproduzione: la legge da audDAT ai
            // confini di periodo (advanceModulator).
            if (startsPlayback && !currentWordValid && !isModulator()) {
                loadCurrentWord();
            }
            if (startsPlayback) {
                // ...e il primo byte esce SUBITO, non al confine di periodo successivo:
                // il case 5 di WinUAE mette `pbufldl = true` e richiama la macchina a
                // stati, che entra nel case 2 e fa `newsample(dat2 >> 8)` nello stesso
                // istante. Aspettare un periodo intero e' invisibile in una traccia
                // (che e' riportata al proprio primo evento) ma non lo e' quando il
                // canale viene spento prima: dmaonoff2 tiene il DMA acceso due righe
                // sole, e cosi' non suonava affatto invece dei 486 eventi dell'oracolo.
                advanceSampleByte();
            }
            refreshActiveMask();
        }

        /**
         * Il canale sta <b>modulando</b> il successivo: ADKCON gli ha assegnato il volume
         * (bit {@code index}) o il periodo (bit {@code index+4}) del canale {@code index+1}.
         * Il canale 3 non modula nessuno ({@code loaddat} esce con {@code if (nr >= 3)}),
         * ma i suoi bit lo ammutoliscono lo stesso — quello lo fa {@code adk_mask}, non
         * questo metodo.
         */
        private boolean isModulator() {
            return index < 3 && (adkcon & (0x11 << index)) != 0;
        }

        private void advanceSampleByte() {
            if (isModulator()) {
                advanceModulator();
                return;
            }
            if (!currentWordValid && cpuDriven && !dmaEnabled) {
                // I due byte della word sono stati suonati: è QUI che il modo a
                // interrupt chiede il rifornimento, un periodo dopo il byte basso
                // (WinUAE stato 3 con perfin e canale non in DMA). Se l'interrupt
                // precedente è ancora pendente la CPU non ha rifornito in tempo e
                // il canale si ferma, esattamente come lo zerostate di WinUAE.
                boolean stillPending = (intreq & (0x0080 << index)) != 0;
                raiseAudioInterrupt(index);
                if (stillPending) {
                    // zerostate(): il canale esce dagli stati 2/3 e il period counter
                    // si ferma (isPlaying() diventa falsa). currentSample NON si tocca —
                    // WinUAE non lo azzera, e l'uscita resta congelata all'ultimo byte
                    // suonato. Azzerarla aggiungeva a dmaonoff2 un evento "-> 0" per
                    // giro che nell'oracolo non c'è: 728 eventi contro 486.
                    cpuDriven = false;
                    nextWordValid = false;
                    refreshActiveMask();
                    return;
                }
            }
            if (!currentWordValid && !loadCurrentWord()) {
                // WinUAE fa loaddat() incondizionato: senza rifornimento il canale
                // risuona l'ultima word scritta invece di zittirsi, e si ferma al
                // primo interrupt non servito (il controllo qui sopra). Tenerlo
                // conserva la cadenza di un interrupt ogni due periodi.
                //
                // Vale anche col DMA ACCESO: se lo slot audio non ha ancora consegnato
                // la word, Paula rimette in riproduzione il contenuto di AUDxDAT, non
                // il silenzio. È la rete di sicurezza dietro la richiesta anticipata
                // qui sotto in loadCurrentWord(): un periodo di zero è un buco udibile,
                // una word ripetuta no.
                currentWord = audDAT[index] & 0xFFFF;
                currentWordValid = true;
                currentWordEndsBlock = false;
                bytePhase = 0;
                if (dmaEnabled && nextWordEndsBlock) {
                    // ...e il fine-blocco rimasto in sospeso si scarica QUI, anche se il
                    // DMA non ha (ancora) consegnato niente. In WinUAE le due cose sono
                    // separate: `loaddat(nr)` è incondizionato e il controllo che segue
                    // guarda solo il flag —
                    //
                    //   case 3: if (chan_ena) { loaddat(nr); if (napnav) setdr(nr, false);
                    //       if (cdp->intreq2 && napnav) { setirq(nr, 31); intreq2 = false; } }
                    //
                    // Da noi il flag viaggia con la word in coda, quindi senza questo ramo
                    // un `intreq2` sopravvissuto allo spegnimento del DMA (§23.2) non
                    // veniva scaricato mai: su timing/dmatim3 l'oracolo chiede l'interrupt
                    // a vpos 144 e 160 — il primo confine di periodo dopo che DMACON ha
                    // riacceso un canale che stava ancora suonando — e noi non chiedevamo
                    // niente.
                    nextWordEndsBlock = false;
                    raiseAudioInterrupt(index);
                }
            }

            int raw = bytePhase == 0 ? (currentWord >>> 8) & 0xFF : currentWord & 0xFF;
            currentSample = (byte) raw;
            bytePhase++;
            if (bytePhase >= 2) {
                bytePhase = 0;
                currentWordValid = false;
                currentWordEndsBlock = false;
            }

            if (dmaEnabled && !nextWordValid && !dmaRequest) {
                requestNextWord();
            }
            refreshActiveMask();
        }

        private boolean loadCurrentWord() {
            if (!nextWordValid) {
                if (dmaEnabled && !dmaRequest) requestNextWord();
                return false;
            }
            currentWord = nextWord;
            currentWordValid = true;
            currentWordEndsBlock = nextWordEndsBlock;
            nextWordValid = false;
            nextWordEndsBlock = false;
            bytePhase = 0;
            if (currentWordEndsBlock) {
                raiseAudioInterrupt(index);
            }
            // La richiesta della word successiva parte SEMPRE quando una word entra in
            // riproduzione — anche per l'ultima del blocco. Oracolo: WinUAE audio.cpp,
            // stato 3 con perfin e canale in DMA, dove `setdr(nr, false)` segue
            // `loaddat(nr)` senza alcuna condizione sulla lunghezza residua; è `setdr`
            // stessa a scegliere fra richiesta normale e richiesta con ricarica del
            // puntatore (`dsr`, quando wlen == 1).
            //
            // Saltarla al confine del blocco — quello che si faceva prima — toglie al
            // DMA un'intera word di anticipo: la richiesta partiva solo dopo che
            // l'ultima word era stata suonata per intero, e lo slot DMA audio passa una
            // volta per riga (454 cicli). Con un periodo corto la word arrivava tardi e
            // il canale restava a ZERO per un periodo intero. Misurato sul banco con
            // un'onda quadra da 10 kHz: 40 buchi al secondo, che nello spettro sono
            // bande laterali distanti ~39,5 Hz dalla fondamentale, forti quanto la
            // fondamentale stessa.
            if (dmaEnabled && !dmaRequest) {
                requestNextWord();
            }
            refreshActiveMask();
            return true;
        }

        /**
         * Un confine di periodo su un canale <b>modulatore</b>.
         *
         * <p>Oracolo: WinUAE {@code audio.cpp}, {@code loaddat(nr, modper)} e i suoi due
         * punti di chiamata. Il modulatore percorre la stessa macchina a stati di un
         * canale normale, ma i due confini di periodo <b>non fanno la stessa cosa</b>:
         * <ul>
         *   <li><b>confine alto</b> (stato 5→2 e 3→2), {@code loaddat(nr, false)}:
         *       {@code update_volume(nr+1, cdp->dat)} se ADKCON chiede il volume;</li>
         *   <li><b>confine basso</b> (stato 2→3), {@code loaddat(nr, true)}:
         *       {@code cdp[1].per = cdp->dat} se ADKCON chiede il periodo.</li>
         * </ul>
         *
         * <p>La word è sempre {@code cdp->dat}, cioè <b>l'ultima arrivata</b>
         * ({@code audDAT[index]}), mai {@code dat2}: un modulatore non "suona" niente.
         *
         * <p>E la richiesta della word successiva parte a confini diversi —
         * {@code setdr} al confine alto solo se {@code napnav = (!audav && !audap) || audav},
         * e al confine basso solo se {@code audap}. Con <b>entrambe</b> le modulazioni
         * attive parte a tutti e due, quindi il canale consuma <b>una word per periodo</b>
         * invece che una ogni due: una alimenta il volume, la successiva il periodo, e le
         * parole si alternano. È esattamente il modo in cui è scritta la tabella di
         * {@code dmamod3} ({@code 0,0, 1,0, 0,2, 2,0, …}) e il motivo per cui
         * {@code dmamod4}, che ne ha un numero dispari, «scambia volume e periodo a giri
         * alterni».
         *
         * <p>Prendere volume <i>e</i> periodo dalla stessa word — quello che si faceva
         * prima — infila in AUDxPER parole destinate al volume: {@code $0002} diventa un
         * periodo di 2 CCK e il canale esplode. Misurato: 594169 eventi contro i 13996
         * dell'oracolo.
         */
        private void advanceModulator() {
            final boolean audav = (adkcon & (1 << index)) != 0;
            final boolean audap = (adkcon & (0x10 << index)) != 0;

            currentSample = 0;      // adk_mask = 0: il modulatore e' muto
            if (bytePhase == 0) {
                if (audav) {
                    audioChannels[index + 1].setModulatedVolume(audDAT[index]);
                    consumeQueuedWord();
                    askForNextWord(true);
                }
            } else {
                if (audap) {
                    audioChannels[index + 1].setModulatedPeriod(audDAT[index]);
                    consumeQueuedWord();
                    askForNextWord(false);
                }
            }
            bytePhase ^= 1;
            refreshActiveMask();
        }

        /**
         * Libera la word in coda. Il modulatore non la manda in riproduzione — legge
         * sempre AUDxDAT — ma la coda va svuotata lo stesso, perché è
         * {@code nextWordValid} a impedire che parta una seconda richiesta. Ed è qui che
         * scatta l'interrupt di fine blocco, nello stesso punto in cui WinUAE controlla
         * {@code cdp->intreq2}: accanto a {@code setdr}.
         */
        private void consumeQueuedWord() {
            if (!nextWordValid) return;
            nextWordValid = false;
            if (nextWordEndsBlock) {
                nextWordEndsBlock = false;
                raiseAudioInterrupt(index);
            }
        }

        /**
         * Il {@code setdr}/{@code setirq} di WinUAE: chiedi la word successiva, per DMA
         * se il canale è in DMA e per interrupt se sta girando in modo manuale
         * ({@code irqmod*}).
         *
         * <p>{@code checkPendingIrq} distingue i due confini di periodo, e la distinzione
         * è indispensabile. Il controllo «l'interrupt precedente è ancora pendente ⇒ la
         * CPU non ha rifornito in tempo ⇒ il canale si ferma» ({@code zerostate}) vive
         * <b>solo nello stato 3</b>, cioè al confine alto:
         * <pre>
         * case 3: ... else {
         *     if (!cdp-&gt;irqcheck) cdp-&gt;irqcheck = isirq(nr);
         *     if (napnav) setirq(nr, 32);
         *     if (cdp-&gt;irqcheck &gt; 0) { zerostate(nr, false); return true; }
         * </pre>
         * Nello stato 2 c'è solo {@code if (audap) setirq(nr, 22);}, senza controllo.
         * Applicarlo a tutti e due i confini ferma il canale al primo giro quando ADKCON
         * chiede entrambe le modulazioni, perché i due interrupt cadono a mezzo periodo
         * l'uno dall'altro e il secondo trova sempre pendente il primo: misurato,
         * {@code irqmod3} passava da 13995 eventi a 61.
         */
        private void askForNextWord(boolean checkPendingIrq) {
            if (dmaEnabled) {
                if (!dmaRequest) requestNextWord();
                return;
            }
            if (!cpuDriven) return;
            boolean stillPending = checkPendingIrq && (intreq & (0x0080 << index)) != 0;
            raiseAudioInterrupt(index);
            if (stillPending) {
                cpuDriven = false;
                nextWordValid = false;
            }
        }

        /**
         * Volume imposto dal canale precedente. WinUAE {@code update_volume()} scrive
         * {@code data.audvol}, <b>non</b> {@code mixvol}: il valore entra nel mixer al
         * {@code loadper()} successivo, non subito. Vedi {@link #mixVolume}.
         */
        private void setModulatedVolume(int word) {
            audVOL[index] = Math.min(word & 0x7F, 64);
        }

        /**
         * Periodo imposto dal canale precedente. WinUAE scrive {@code cdp[1].per} e basta:
         * il contatore in corso <b>non</b> viene riarmato, il periodo nuovo vale dal
         * {@code loadper()} successivo. Riarmarlo subito accorcia o allunga il periodo che
         * sta scorrendo, che è un errore anche quando il valore è quello giusto.
         */
        private void setModulatedPeriod(int word) {
            audPER[index] = word & 0xFFFF;
            periodClocks = computePeriodClocks();
        }

        private void requestNextWord() {
            if (!dmaEnabled || dmaRequest || nextWordValid) return;
            if (remainingWords == 0) {
                reloadDmaBlock();
            }
            dmaRequest = true;
            refreshActiveMask();
        }

        private void reloadDmaBlock() {
            reloadDmaPointer();
            remainingWords = audLEN[index] & 0xFFFF;
            if (remainingWords == 0) {
                remainingWords = 0x10000;
            }
        }

        private void reloadDmaPointer() {
            dmaPointer = shadowAddress(index);
        }

        private int computePeriodClocks() {
            int per = audPER[index] & 0xFFFF;
            if (per == 0) per = 0x10000;
            return Math.max(2, per * 2);
        }

        /**
         * Scrittura della CPU in AUDxPER. Come {@link #setModulatedPeriod}, aggiorna solo
         * la durata del periodo: WinUAE {@code AUDxPER()} assegna {@code cdp->per} e
         * lascia stare {@code cdp->evtime}, quindi il periodo in corso finisce con la
         * durata vecchia.
         */
        private void reloadPeriodClocks() {
            periodClocks = computePeriodClocks();
        }

        private void refreshActiveMask() {
            // cpuDriven entra nella condizione perché il modo a interrupt vive sul
            // period-counter: un canale che uscisse dalla maschera smetterebbe di
            // contare e non chiederebbe mai più la word successiva.
            boolean active = dmaEnabled || dmaRequest || nextWordValid || currentWordValid
                    || cpuDriven || currentSample != 0 || dmaWordDelay > 0;
            if (active) activeAudioMask |= (1 << index);
            else        activeAudioMask &= ~(1 << index);
        }
    }

}

