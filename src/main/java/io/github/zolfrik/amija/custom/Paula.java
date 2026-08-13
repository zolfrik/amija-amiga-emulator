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

    private int intreq;
    private int intena;
    private final long[] setIntreq = new long[16];
    private long iplPipe;

    // =========================================================================
    // Potentiometers
    // =========================================================================

    private int potgo;
    private int potCntX0, potCntY0, potCntX1, potCntY1;
    private double chargeX0, chargeY0, chargeX1, chargeY1;

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
    private int dsksync = 0x4489;
    private int dsk_syncMatches = 0; // diagnostica: sync trovati nella sessione DMA corrente
    private int dskbytr_incoming;
    private long dsk_syncCycle;
    private int dsk_dataReg;
    private int dsk_dataRegCount;
    private long dsk_fifo;
    private int dsk_fifoCount;

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

    public void reset() {
        intena = 0;
        intreq = 0;
        adkcon = 0;
        potgo = 0;
        Arrays.fill(setIntreq, AbstractAgnus.NEVER);
        iplPipe = 0;

        potCntX0 = potCntY0 = potCntX1 = potCntY1 = 0;
        chargeX0 = chargeY0 = chargeX1 = chargeY1 = 1.0;

        dskpth = dskptl = dsklen = 0;
        dsksync = 0x4489;
        diskState = DriveDmaState.OFF;
        dsklenWriteCount = 0;
        dsk_fifo = 0;
        dsk_fifoCount = 0;
        dsk_dataReg = 0;
        dsk_dataRegCount = 0;
        dsk_writeShift = 0;
        dsk_writeBits = 0;
        dsk_syncCycle = 0;
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
            int counter = audioChannels[chan].periodCounter;
            if (counter < min) min = counter;
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
        long next = AbstractAgnus.NEVER;

        for (int i = 0; i < 16; i++) {
            if (clock >= setIntreq[i]) {
                setINTREQ(true, (1 << i));
                setIntreq[i] = AbstractAgnus.NEVER;
            } else {
                next = Math.min(next, setIntreq[i]);
            }
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

    public int calculateIPL() {
        if ((intena & 0x4000) != 0) {
            int mask = intreq & intena;
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

    public void servicePotEvent(EventID id) {
        boolean outry = (potgo & 0x8000) != 0;
        boolean outrx = (potgo & 0x2000) != 0;
        boolean outly = (potgo & 0x0800) != 0;
        boolean outlx = (potgo & 0x0200) != 0;

        if (id == EventID.POT_DISCHARGE) {
            // Lines configured as outputs are driven by the CPU — no RC charge/discharge.
            // Lines configured as inputs (OUTxy=0) are discharged to GND; the capacitor
            // will then charge through the potentiometer resistance.
            if (!outly) chargeY0 = 0.0;
            if (!outlx) chargeX0 = 0.0;
            if (!outry) chargeY1 = 0.0;
            if (!outrx) chargeX1 = 0.0;

            // Per le linee configurate come OUTPUT la misura non si applica: il
            // contatore resta 0 (come vAmiga Paula::servicePotEvent, che azzera i
            // contatori delle linee output). Le linee INPUT partono anch'esse da 0.
            // NB storica: prima le linee output venivano fissate a 0xFF; ciò
            // faceva leggere POT0DAT/POT1DAT = $FFFF a giochi che pilotano i pin
            // pot come output (POTGO=$FFFF, es. Gods), rompendone i controlli.
            potCntY0 = 0;
            potCntX0 = 0;
            potCntY1 = 0;
            potCntX1 = 0;

            agnus.scheduleRel(EventSlot.SLOT_POT, 512, EventID.POT_CHARGE);
        } else if (id == EventID.POT_CHARGE) {
            // Carica massima di default (nessun dispositivo o pulsante rilasciato).
            // I pulsanti destro/medio del mouse (o button2/3 del joystick) portano
            // la linea a GND quando premuti, impedendo la carica completa.
            chargeY0 = (gamePort1 != null && gamePort1.isRightOrButton2Pressed()) ? 0.0 : 1.0;
            chargeX0 = (gamePort1 != null && gamePort1.isMiddleOrButton3Pressed()) ? 0.0 : 1.0;
            chargeY1 = (gamePort2 != null && gamePort2.isRightOrButton2Pressed()) ? 0.0 : 1.0;
            chargeX1 = (gamePort2 != null && gamePort2.isMiddleOrButton3Pressed()) ? 0.0 : 1.0;
        }
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

    public void shiftInDiskBit(int bit) {
        dsk_dataReg = ((dsk_dataReg << 1) | (bit & 1)) & 0xFFFF;

        // Ordine di Paula (WinUAE disk.cpp, disk_doupdate_read): prima si CHIUDE la
        // word corrente — aggiornamento di DSKBYT e travaso nella FIFO — e solo dopo
        // si valuta il match di sync. Così la seconda word di sync dei settori
        // standard (che ne hanno due consecutive) viene comunque trasferita.
        dsk_dataRegCount++;

        // DSKBYT (readable via 0x01A) is updated every byte (8 bits).
        if ((dsk_dataRegCount & 7) == 0) {
            int byteVal = dsk_dataReg & 0xFF;
            dskbytr_incoming = 0x8000 | byteVal; // DSKBYT set
        }

        // The real Paula writes a complete 16-bit word to the FIFO, not individual bytes.
        if (dsk_dataRegCount >= 16) {
            dsk_dataRegCount = 0;
            writeFifo(dsk_dataReg & 0xFFFF);
        }

        // Sync detection is performed on every bit against the full 16-bit data register.
        if (dsk_dataReg == dsksync) {
            dsk_syncCycle = agnus.getMasterClock();
            dsk_syncMatches++;
            raiseInterrupt(0x1000); // DSKSYN

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

    public static long diagIntreqrReads = 0, diagIntreqrBit5 = 0;

    public int readRegister(int address) {
        address &= 0x1FE;
        switch (address) {
            case 0x008: return dskdatr();
            case 0x010: return adkcon & 0x7FFF; // ADKCONR: bit 15 (SET/CLR strobe) always reads 0
            case 0x012: return (potCntY0 << 8) | potCntX0;
            case 0x014: return (potCntY1 << 8) | potCntX1;
            case 0x016: return buildPotinp();
            case 0x018: return buildSerdatr();
            case 0x01A: return buildDskbytr();
            case 0x01C: return intena & 0x7FFF; // INTENAR: bit 15 is always 0 on OCS/ECS.
            case 0x01E: { int v = intreq & 0x7FFF; diagIntreqrReads++; if ((v & 0x20) != 0) diagIntreqrBit5++; return v; }
            default:
                if (address >= 0x0A0 && address <= 0x0DE) return readAudioRegister(address);
                return 0xFFFF;
        }
    }

    private int buildPotinp() {
        int val = 0;
        if (chargeY1 >= 1.0) val |= 0x4000; // Bit 14: DATRY
        if (chargeX1 >= 1.0) val |= 0x1000; // Bit 12: DATRX
        if (chargeY0 >= 1.0) val |= 0x0400; // Bit 10: DATLY
        if (chargeX0 >= 1.0) val |= 0x0100; // Bit  8: DATLX
        // Sovrascrittura in tempo reale con lo stato dei pulsanti delle game port.
        // Questo garantisce risposta immediata senza attendere il ciclo POT_CHARGE.
        if (gamePort1 != null) val = gamePort1.modifyPotinp(val);
        if (gamePort2 != null) val = gamePort2.modifyPotinp(val);
        return val;
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
     * master-cycle. Vale 2 celle MFM (~4 µs): una cella a doppia densità dura
     * 2 µs = 14,12 master-cycle.
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
            // Second consecutive write with bit 15 set → DMA start.
            // Flush the serial shift register and FIFO so the new transfer
            // starts from a clean state (matches real Paula behaviour).
            dsk_dataReg      = 0;
            dsk_dataRegCount = 0;
            dsk_fifo         = 0;
            dsk_fifoCount    = 0;
            dsk_writeShift   = 0;
            dsk_writeBits    = 0;

            diskState = DriveDmaState.READ; // Default; overridden below
            dsklenWriteCount = 0;
            int length = dsklen & 0x3FFF;

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

    private void setPOTGO(int value) {
        potgo = value;
        if ((value & 0x0200) != 0) chargeX0 = ((value & 0x0100) != 0) ? 1.0 : 0.0;
        if ((value & 0x0800) != 0) chargeY0 = ((value & 0x0400) != 0) ? 1.0 : 0.0;
        if ((value & 0x2000) != 0) chargeX1 = ((value & 0x1000) != 0) ? 1.0 : 0.0;
        if ((value & 0x8000) != 0) chargeY1 = ((value & 0x4000) != 0) ? 1.0 : 0.0;

        if ((value & 0x0001) != 0) {
            potCntX0 = potCntY0 = potCntX1 = potCntY1 = 0;
            agnus.scheduleRel(EventSlot.SLOT_POT, 8, EventID.POT_DISCHARGE);
        }
    }

    private void setINTENA(int value) {
        boolean set = (value & 0x8000) != 0;
        int bits = value & 0x7FFF;
        if (set) intena |= bits; else intena &= ~bits;

        checkInterrupt();
    }

    private void setINTREQ(int value) {
        boolean set = (value & 0x8000) != 0;
        int bits = value & 0x7FFF;

        setINTREQ(set, bits);
    }

    public void setINTREQ(boolean setclr, int value) {
        if (setclr) intreq |= value; else intreq &= ~value;

        // In a real Amiga, INTREQ bit 3 (PORTS) and 13 (EXTER) are set by the falling edge
        // of the CIA IRQ pins. Agnus handles this edge detection and calls assertPortsInterrupt/assertExterInterrupt.
        // Forcing them here based on level could prevent the software from clearing the interrupt.

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
        long next = AbstractAgnus.NEVER;
        for (long t : setIntreq) {
            if (t < next) next = t;
        }
        agnus.scheduleAbs(EventSlot.SLOT_IRQ, next, EventID.IRQ_CHECK);
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

    public void assertPortsInterrupt() { raiseInterrupt(0x0008); }
    public void assertExterInterrupt() { raiseInterrupt(0x2000); }
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
            diskState = DriveDmaState.OFF;
            raiseInterrupt(0x0002); // DSKBLK
        }
    }

    public int[] reloadAudioChannel(int chan) {
        if (chan < 0 || chan > 3) return null;
        raiseInterrupt(0x0080 << chan);
        int addr = ((audLCH[chan] & 0x001F) << 16) | (audLCL[chan] & 0xFFFE); // word-aligned
        int len  = audLEN[chan] & 0xFFFF;
        return new int[]{addr, len};
    }

    public boolean isAudioDmaRequest(int chan) {
        return chan >= 0 && chan < 4 && audioChannels[chan].dmaRequest;
    }

    public int getAudioDmaAddress(int chan) {
        return chan >= 0 && chan < 4 ? audioChannels[chan].dmaPointer : 0;
    }

    public void receiveAudioDmaWord(int chan, int value) {
        if (chan >= 0 && chan < 4) {
            syncAudioChannels();
            audioChannels[chan].receiveDmaWord(value);
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

        private AudioChannel(int index) {
            this.index = index;
        }

        private void reset() {
            dmaEnabled = false;
            dmaRequest = false;
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
            refreshActiveMask();
        }

        private void setDmaEnabled(boolean enabled) {
            if (dmaEnabled == enabled) return;
            dmaEnabled = enabled;
            if (enabled) {
                cpuDriven = false;      // il DMA prende il posto del modo a interrupt
                reloadDmaBlock();
                dmaPriming = true;
                requestNextWord();
            } else {
                clearDmaPipeline();
            }
            refreshActiveMask();
        }

        private void clearDmaPipeline() {
            dmaRequest = false;
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
            currentSample = 0;
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
            if (dmaEnabled && (dmaPriming || dmaFirstWord)) {
                // Stati 1 e 5 di WinUAE: entrambi mettono `evtime = MAX_EV`, cioè il
                // period counter NON gira finché la prima word non entra davvero in
                // riproduzione. Farlo girare durante l'avvio del DMA regala al canale
                // uno o più confini di periodo prematuri, che consumano word: su
                // dmamod3 bastava a scambiare volume e periodo per sempre, perché le
                // parole della tabella si alternano.
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
         */
        private void loadPeriod() {
            periodCounter = periodClocks;
            latchMixVolume();
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
            raiseAudioInterrupt(index);
            if (!isModulator()) {
                loadCurrentWord();
            }
            loadPeriod();   // WinUAE: loadper all'ingresso nello stato 2
            refreshActiveMask();
        }

        private void receiveDmaWord(int value) {
            if (!dmaRequest) return;
            audDAT[index] = value & 0xFFFF;
            nextWord = value & 0xFFFF;
            nextWordValid = true;
            dmaRequest = false;

            dmaPointer = (dmaPointer + 2) & 0x1FFFFE;
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
            } else if (remainingWords > 0) {
                remainingWords--;
            }
            nextWordEndsBlock = remainingWords == 0;

            // Un modulatore non manda mai la word in riproduzione: la legge da audDAT
            // ai confini di periodo (advanceModulator). Mandarcela significherebbe anche
            // far partire subito la richiesta successiva, e quindi consumare una word in
            // piu' prima del primo confine — che e' come volume e periodo finivano
            // scambiati su dmamod3.
            if (!currentWordValid && !isModulator()) {
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
                    cpuDriven = false;
                    nextWordValid = false;
                    currentSample = 0;
                    refreshActiveMask();
                    return;
                }
            }
            if (!currentWordValid && !loadCurrentWord()) {
                // dmaPriming: il canale è appena stato acceso e la prima word (quella
                // scartata) non è ancora arrivata. Non ha ancora suonato nulla, quindi
                // AUDxDAT contiene roba vecchia e ripeterla sarebbe un clic. In WinUAE
                // qui il canale è negli stati 1/5, dove evtime = MAX_EV e il period
                // counter non gira affatto.
                if ((!cpuDriven && !dmaEnabled) || dmaPriming) {
                    currentSample = 0;
                    refreshActiveMask();
                    return;
                }
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
                    || cpuDriven || currentSample != 0;
            if (active) activeAudioMask |= (1 << index);
            else        activeAudioMask &= ~(1 << index);
        }
    }

}

