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
            // EXTER (bit 13) only — IPL 6
            if ((mask & 0x2000) != 0) return 6;
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
            case 0x01E: return intreq & 0x7FFF;
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

    public void setAudioDmaEnabledMask(int dmacon) {
        syncAudioChannels();
        boolean master = (dmacon & 0x0200) != 0;
        for (int i = 0; i < audioChannels.length; i++) {
            audioChannels[i].setDmaEnabled(master && (dmacon & (1 << i)) != 0);
        }
    }

    private void mixAudioSample() {
        float left = 0.0f;
        float right = 0.0f;

        for (AudioChannel ch : audioChannels) {
            float sample = ch.outputSample();
            if (ch.index == 0 || ch.index == 3) left += sample;
            else                                right += sample;
        }

        audioRingBuffer.write(left * 0.5f, right * 0.5f);
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
                audioChannels[chan].reloadPeriodCounter();
                break;
            case 0x8:
                audVOL[chan] = Math.min(value & 0x7F, 64);
                audioChannels[chan].reloadVolumeScale();
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
        private int bytePhase;
        private int periodCounter;
        private int periodClocks;
        private float volumeScale;
        private int currentSample;

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
            bytePhase = 0;
            periodClocks = computePeriodClocks();
            periodCounter = periodClocks;
            volumeScale = computeVolumeScale();
            currentSample = 0;
            refreshActiveMask();
        }

        private void setDmaEnabled(boolean enabled) {
            if (dmaEnabled == enabled) return;
            dmaEnabled = enabled;
            if (enabled) {
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
            bytePhase = 0;
            currentSample = 0;
        }

        private void tick(int cycles) {
            periodCounter -= cycles;
            while (periodCounter <= 0) {
                periodCounter += periodClocks;
                advanceSampleByte();
            }
        }

        private float outputSample() {
            return currentSample * volumeScale;
        }

        private void writeData(int value) {
            nextWord = value & 0xFFFF;
            nextWordValid = true;
            nextWordEndsBlock = false;
            if (!currentWordValid) {
                loadCurrentWord();
            }
            if (!dmaEnabled) {
                raiseInterrupt(0x0080 << index);
            }
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
                raiseInterrupt(0x0080 << index);
                requestNextWord();
                refreshActiveMask();
                return;
            }
            if (remainingWords > 0) {
                remainingWords--;
            }
            nextWordEndsBlock = remainingWords == 0;

            if (!currentWordValid) {
                loadCurrentWord();
            }
            refreshActiveMask();
        }

        private void advanceSampleByte() {
            if (!currentWordValid && !loadCurrentWord()) {
                currentSample = 0;
                refreshActiveMask();
                return;
            }

            if (applyModulationWord()) {
                if (dmaEnabled && !currentWordEndsBlock && !nextWordValid && !dmaRequest) {
                    requestNextWord();
                }
                refreshActiveMask();
                return;
            }

            int raw = bytePhase == 0 ? (currentWord >>> 8) & 0xFF : currentWord & 0xFF;
            currentSample = (byte) raw;
            bytePhase++;
            if (bytePhase >= 2) {
                bytePhase = 0;
                currentWordValid = false;
                currentWordEndsBlock = false;
            }

            if (dmaEnabled && !currentWordEndsBlock && !nextWordValid && !dmaRequest) {
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
                raiseInterrupt(0x0080 << index);
            }
            if (dmaEnabled && !dmaRequest && !currentWordEndsBlock) {
                requestNextWord();
            }
            refreshActiveMask();
            return true;
        }

        private boolean applyModulationWord() {
            if (index >= 3) return false;
            boolean volumeMod = (adkcon & (1 << index)) != 0;
            boolean periodMod = (adkcon & (0x10 << index)) != 0;
            if (!volumeMod && !periodMod) return false;

            if (volumeMod) {
                audVOL[index + 1] = Math.min(currentWord & 0x7F, 64);
                audioChannels[index + 1].reloadVolumeScale();
            }
            if (periodMod) {
                audPER[index + 1] = currentWord & 0xFFFF;
                audioChannels[index + 1].reloadPeriodCounter();
            }
            currentSample = 0;
            currentWordValid = false;
            currentWordEndsBlock = false;
            bytePhase = 0;
            return true;
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

        private float computeVolumeScale() {
            int volume = Math.min(audVOL[index] & 0x7F, 64);
            return volume / (128.0f * 64.0f);
        }

        private void reloadVolumeScale() {
            volumeScale = computeVolumeScale();
        }

        private void reloadPeriodCounter() {
            periodClocks = computePeriodClocks();
            periodCounter = periodClocks;
        }

        private void refreshActiveMask() {
            boolean active = dmaEnabled || dmaRequest || nextWordValid || currentWordValid || currentSample != 0;
            if (active) activeAudioMask |= (1 << index);
            else        activeAudioMask &= ~(1 << index);
        }
    }

}

