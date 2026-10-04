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
 * Paula (8364): audio, interrupt controller, disk DMA and serial port of the Amiga 500.
 */
public final class Paula {

    // =========================================================================
    // Dependencies
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
     * INTREQ and INTENA <b>as the CPU reads them back</b> (INTREQR/INTENAR).
     */
    private int intreq;
    private int intena;
    /**
     * The <b>copies the IPL line is computed from</b>, and only that: the line is
     * {@code intreq2 & intena2}. They are not a redundant duplicate: the two pairs are
     * updated at different moments and <b>can stay out of step for good</b>.
     *
     * <p>A CPU or Copper write changes the read-back copy <b>at once</b> and the IPL copy one
     * CCK later (the IPL line changes 0.5 CCK after the write, and the 68000 needs one more
     * CPU clock to detect it); an interrupt raised by a chip sets <b>both together</b>.
     *
     * <p>That makes possible a state a single copy cannot hold: a bit set in {@code intreq}
     * and clear in {@code intreq2}, i.e. an interrupt the CPU <i>reads back as pending</i>
     * that is <i>never delivered</i>. The vAmigaTS tests {@code Paula/Audio/timing/audtim*}
     * measure exactly that stall: the handler runs six times and then never again
     * ({@code docs/vamigats-paula-audio-plan.md} section 14.5).
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
     * Potentiometer state, indexed [port][line]: port 0 = {@link #gamePort1} (left),
     * 1 = {@link #gamePort2} (right); line 0 = X (middle button / button 3), 1 = Y (right
     * button / button 2). @see #capCheck(boolean)
     */
    private final int[][] potCap = new int[2][2];
    private final int[][] potDat = new int[2][2];
    private final int[][] potDatAct = new int[2][2];

    /** Lines of fast discharge after START. */
    private static final int POTDAT_DELAY = 8;

    // Game port 1 (mouse) and port 2 (joystick), for POTINP
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
     * DSKSYNC. <b>It is ZERO at power-on</b>, not {@code 0x4489}: until software writes it
     * the comparator matches against {@code 0x0000}. Kickstart 1.3 never writes it: it reads
     * the raw track with WORDSYNC off (ADKCON {@code $1100}) and decodes in software
     * (docs/vamigats-paula-audio-plan.md section 7.1.24).
     */
    private int dsksync = 0x0000;
    private int dsk_syncMatches = 0; // diagnostics: syncs found in the current DMA session
    /**
     * Sync detector latch: the DSKSYN interrupt is <b>edge-triggered</b> and fires only at
     * the first bit where the shifter matches, not at every one. It rearms when the shifter
     * stops matching.
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

    // -- WRITE shift register (Paula -> head) -----------------------------------
    // The mirror of dsk_dataReg: the DMA loads one word at a time (loadWriteWord,
    // requested by Agnus only when the shifter is empty) and the drive takes one bit per
    // MFM cell (nextWriteBit), so the transfer rate follows the rotation of the medium,
    // not the number of DMA slots available.
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
    /** Cycles accumulated and not yet applied to the period counters (see {@link #tick(int)}). */
    private int pendingAudioCycles;
    /** min(periodCounter) over the active channels; -1 = to be recomputed. */
    private int audioMinCounter = -1;
    /** Master cycles since the last output sample: the divisor of the integration. */
    private int mixIntervalCycles;
    private static final boolean AUDIO_VERIFY =
            Boolean.getBoolean("amiga.audioverify");

    private static final int PAL_SYSTEM_CLOCK = 7_093_790;

    // -------------------------------------------------------------------------
    // ASR - Adaptive Sample Rate
    //
    // Producer (this thread) and consumer (the sound card) run on two independent clocks
    // that always drift. Without correction the ring buffer ends up empty (underrun ->
    // crackle) or full (overflow -> clicks). The ASR varies the production rate
    // imperceptibly to keep the buffer around its target level.
    // -------------------------------------------------------------------------
    private static final double ASR_TARGET_FILL = 0.12;   // target fill of the ring (~90 ms of cushion)
    // TWO-ZONE ASR (dead zone), against audible pitch wobble. A purely proportional
    // controller chases the sawtooth of the fill level (+-1500 samples from per-frame
    // rendering stalls) and keeps correcting by +-100-300 Hz: audible wow/flutter.
    //   - INSIDE the dead zone (|fill-target| <= ASR_DEADZONE, where all the normal
    //     oscillation falls): a tiny linear correction, at most +-ASR_FINE_MAX Hz (~0.07%,
    //     below the pitch perception threshold), so the normal oscillation of the buffer
    //     does not modulate the pitch.
    //   - OUTSIDE (large imbalance: start-up, long stall): the correction grows up to
    //     +-ASR_COARSE_MAX to recover quickly before an underrun.
    // The slow host/Amiga drift (<0.1%) is well within the fine zone.
    private static final double ASR_DEADZONE = 0.06;      // +-~2000 samples around the target (covers the normal oscillation)
    private static final double ASR_FINE_MAX = 30.0;      // Hz: max correction inside the dead zone (~0.07%, inaudible)
    private static final double ASR_COARSE_GAIN = 25000.0; // proportional gain outside the dead zone
    private static final double ASR_COARSE_MAX = 3000.0;  // Hz: max correction outside (fast recovery from large imbalances)
    private static final int ASR_UPDATE_INTERVAL = 256;   // recompute the correction every N samples produced
    // EWMA: further smooths the transition between the zones and the residual sawtooth.
    private static final double ASR_SMOOTHING = 0.05;     // weight of the new sample in the EWMA
    private double sampleRateCorrection;
    private int asrCounter;

    // =========================================================================
    // Constructor and reset
    // =========================================================================

    public Paula() {
        for (int i = 0; i < audioChannels.length; i++) {
            audioChannels[i] = new AudioChannel(i);
        }
        reset();
    }

    public void reset() { reset(true); }

    /**
     * Paula reset. {@code hardware} tells power-on from the reset the CPU's RESET
     * instruction brings to the peripherals: <b>DSKSYNC is not on that line</b> and survives
     * a software reset, with its edge latch. Cleared at a warm reset too, the idle read
     * shifter, which shifts zeros, would match {@code $0000} and raise a DSKSYN 7 CCK after
     * the ColdReboot of the demo Jesus On E's (docs/vamigats-import-order.md section 26).
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

        // At reset the capacitors of the POT lines are DISCHARGED: a port with nothing
        // attached reads its DAT bit low until someone writes START (the only thing that
        // runs the charge cycle). vAmigaTS Mainboard/Ports/POT/potgo0, potgo5 and potgo6
        // never write START and read POTGOR $0500.
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
     * Advances the period counters of the ACTIVE audio channels by {@code cycles} master
     * cycles.
     *
     * The output samples (accumulator {@link #outputSamplePhase}) are produced elsewhere,
     * by a self-rescheduling event (SLOT_CH0 / AUDIO_MIX, see
     * {@link #serviceAudioSampleEvent()}), so the rate arithmetic runs once per sample
     * (~44.1k/s) instead of at every master cycle (~7.09M/s). Gating on
     * {@link #activeAudioMask} makes this method almost free when there is no audio.
     */
    public void tick(int cycles) {
        // Length of the integration interval of the next output sample. It is ALWAYS
        // counted, even with no channel active: a channel that stops halfway through the
        // interval has accumulated only its own part, and the divisor must stay the whole
        // interval for the average to come out right (the rest is zero).
        mixIntervalCycles += cycles;
        if (activeAudioMask == 0) {
            // No channel active: nothing to accumulate (the counters are already aligned).
            pendingAudioCycles = 0;
            audioMinCounter = -1;
            return;
        }
        int min = audioMinCounter;
        if (min < 0) {
            min = audioMinCounter = computeAudioMinCounter();
        } else if (AUDIO_VERIFY) {
            // Safety net: the cached deadline must match the recomputed one. A mismatch is
            // a mutation site that does not call syncAudioChannels().
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
            // No channel can expire before `min`: nothing observable changes.
            pendingAudioCycles = pending;
            return;
        }
        pendingAudioCycles = 0;
        applyAudioCycles(pending);
        audioMinCounter = -1;
    }

    /** Applies {@code cycles} master cycles to the period counters of the active channels. */
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
            // The deferred processing of the DMA word is a deadline too: a jump past it
            // would run the handler late (see AudioChannel.tick).
            if (ch.dmaWordDelay > 0 && ch.dmaWordDelay < min) min = ch.dmaWordDelay;
            if (ch.reloadRequestDelay > 0 && ch.reloadRequestDelay < min) min = ch.reloadRequestDelay;
            mask &= ~(1 << chan);
        }
        return min;
    }

    /**
     * Brings the period counters to the current cycle and invalidates the deadline. Called
     * before EVERY mutation that can shorten a counter or change the set of active channels
     * (AUDxPER/VOL/DAT writes, DMACON, ADKCON, delivery of a DMA word, reset): after the sync
     * the counters of all channels refer to the same instant, so the recomputed minimum is
     * comparable. Always safe: applying the pending cycles early has no observable effect
     * while no counter has expired.
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
     * Self-rescheduling event (Agnus SLOT_CH0 / AUDIO_MIX): produces one audio output
     * sample and schedules the next.
     */
    public void serviceAudioSampleEvent() {
        // The cycles tick() holds back are already counted in mixIntervalCycles but not yet
        // in the channels' accumulators: without this sync the numerator would cover less
        // time than the denominator and the level would come out low.
        syncAudioChannels();
        mixAudioSample();
        updateSampleRateCorrection();
        scheduleNextAudioSample();
    }

    /**
     * Schedules the next AUDIO_MIX event. The number of master cycles {@code k} to the next
     * sample is the smallest {@code k >= 1} such that
     * {@code outputSamplePhase + rate*k >= PAL_SYSTEM_CLOCK}, with
     * {@code rate = OUTPUT_RATE + sampleRateCorrection}. Between two samples {@code rate}
     * is constant (the ASR correction changes only at samples), so this is exactly a
     * per-cycle accumulator.
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
     * Updates the ASR correction from the fill level of the ring buffer. A buffer above
     * target slows production down, below target speeds it up. The correction is smoothed
     * by an EWMA to avoid audible pitch oscillations.
     */
    private void updateSampleRateCorrection() {
        if (++asrCounter < ASR_UPDATE_INTERVAL) return;
        asrCounter = 0;

        // error > 0 -> buffer too full -> slow production down (negative correction).
        double error = audioRingBuffer.fillLevel() - ASR_TARGET_FILL;
        double absErr = Math.abs(error);

        double correction;
        if (absErr <= ASR_DEADZONE) {
            // Fine zone: tiny linear correction (inaudible). All the normal oscillation of
            // the buffer falls here, so the pitch is not modulated.
            correction = -(error / ASR_DEADZONE) * ASR_FINE_MAX;
        } else {
            // Coarse zone: STRONG proportional control (high gain, clamped to
            // +-COARSE_MAX) to recover quickly from large imbalances (e.g. the boot, where
            // the sound card's consumer starts late and the buffer would fill up to
            // overflow). The EWMA turns the edge of the dead zone into a short glide
            // (~0.1 s), not a jump.
            correction = -error * ASR_COARSE_GAIN;
            if (correction >  ASR_COARSE_MAX) correction =  ASR_COARSE_MAX;
            else if (correction < -ASR_COARSE_MAX) correction = -ASR_COARSE_MAX;
        }

        sampleRateCorrection = sampleRateCorrection * (1.0 - ASR_SMOOTHING) + correction * ASR_SMOOTHING;
    }

    /** Current ASR correction in Hz (diagnostics). */
    public double getSampleRateCorrection() {
        return sampleRateCorrection;
    }

    // =========================================================================
    // Interrupt Service Methods
    // =========================================================================

    public void serviceIrqEvent() {
        long clock = agnus.getMasterClock();

        // ORDER: chip interrupts first, then the deferred poke. It matters, and it is
        // what decides the stall of section 14.5 of the audio plan: within one cycle the
        // event queued first fires first, and an audio interrupt is queued 2 CCK ahead
        // (AUDIO_IRQ_DELAY_CYCLES) against the poke's half CCK. With the poke first, the
        // handler's acknowledge would land UNDER the channel's request instead of over it,
        // the two copies would stay aligned, and the stall would never form.
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

    /** Reschedules SLOT_IRQ at the earliest of the deferred sets and the deferred poke. */
    private void rescheduleIrqEvent() {
        long next = shadowPokeAt;
        for (long t : setIntreq) {
            if (t < next) next = t;
        }
        agnus.scheduleAbs(EventSlot.SLOT_IRQ, next, EventID.IRQ_CHECK);
    }

    /**
     * IPL level the CPU sees on its pins: stage 0 of the pipe, i.e. the value AT ONCE,
     * with no delay. The higher stages remain because {@link #serviceIplEvent} propagates
     * the level through them, but the pin does not read them: reading stage 3 would put the
     * pin ~3 CCK late, invisible while interrupt recognition is quantised to instruction
     * boundaries but measurable with the IPL sampling thresholds, which start from the
     * instant the pin changes.
     */
    public int getCpuIplFromPipe() {
        return (int) (iplPipe & 0xFF);
    }

    /**
     * Advances the IPL pipe by one stage (1 CCK): first updates the CPU's IPL pin, THEN
     * shifts the pipe by one byte, carrying the new level upwards, and reschedules itself
     * 1 CCK (2 master cycles) later until the pipe is fully shifted (repeat counter in
     * {@code data}).
     */
    public void serviceIplEvent(long repeat) {
        // 1. Update the CPU's IPL pin.
        if (iplChangedCallback != null) {
            iplChangedCallback.run();
        }
        // 2. Shift the pipe (the new level, in byte 0, moves up one stage).
        iplPipe = ((iplPipe & 0x00FF_FFFF_FFFF_FFFFL) << 8) | (iplPipe & 0xFFL);
        // 3. Reschedule until the pipe has been fully traversed.
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
            // First stage at once, then 1 CCK per stage. executeEvents runs at the start
            // of risingEdge, so delay=1 mc fires the first stage at the next cycle.
            agnus.scheduleRel(EventSlot.SLOT_IPL, 1, EventID.IPL_CHANGE, 5);
        }
    }

    /** The IPL line is computed from the delayed copies, never the read-back ones. See {@link #intreq2}. */
    public int calculateIPL() {
        if ((intena2 & 0x4000) != 0) {
            int mask = intreq2 & intena2;
            // EXTER (bit 13) + bit 14 - IPL 6.
            // Bit 14 of INTREQ has no hardware source and the manuals do not list it, but
            // it is writable and Paula puts it in the highest priority group with EXTER:
            // with the master enable on, writing INTREQ=$C000 raises a real IPL 6.
            // Adrenalynn (Loriciel) uses it to restart its copper list: the list writes
            // INTREQ=$C000 at the end of the frame, and the level 6 handler reloads COP1LC
            // and clears the bit with INTREQ=$4000.
            if ((mask & 0x6000) != 0) return 6;
            // DSKSYN (bit 12) + RBF (bit 11) - IPL 5
            if ((mask & 0x1800) != 0) return 5;
            // AUD3-AUD0 (bits 10-7) - IPL 4
            if ((mask & 0x0780) != 0) return 4;
            // BLIT + VERTB + COPER (bits 6-4) - IPL 3
            if ((mask & 0x0070) != 0) return 3;
            // PORTS / CIA-A (bit 3) - IPL 2
            if ((mask & 0x0008) != 0) return 2;
            // SOFT + DSKBLK + TBE (bits 2-0) - IPL 1
            if ((mask & 0x0007) != 0) return 1;
        }
        return 0;
    }

    // =========================================================================
    // Potentiometer Service Methods
    // =========================================================================

    /**
     * Potentiometer model, called once per line by Agnus.
     *
     * <p>The state machine, for each of the four lines:
     * <ol>
     *   <li>{@code POTGO[START]} arms all four: {@code act = 1}, {@code dat = 0};</li>
     *   <li>while {@code act == 1} the capacitor is <b>discharged fast</b> and {@code dat}
     *       counts the first {@link #POTDAT_DELAY} lines; then {@code act = 2} and
     *       {@code dat} starts again from zero;</li>
     *   <li>with {@code act == 2} {@code dat} counts one line at a time while the capacitor
     *       charges, and <b>stops</b> when the charge exceeds 10, unless the button holds
     *       the line to ground, which is what makes the count measure that button;</li>
     *   <li>a line programmed as an <b>output</b> does not measure: high stops the count,
     *       low lets it run free if the button is pressed.</li>
     * </ol>
     *
     * <p>The count stops only if <b>something</b> is plugged into the port: on an empty port
     * nobody charges the capacitor and {@code dat} runs free. {@code vAmigaTS
     * Mainboard/Ports} reads 4 or 10 depending on the line where the Copper reads back.
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
                // i == 0 is the X line (middle button / button 3), i == 1 the Y line
                // (right button / button 2).
                final boolean isButton = port != null
                        && (i == 0 ? port.isMiddleOrButton3Pressed()
                                   : port.isRightOrButton2Pressed());

                boolean canCharge = true;
                int charge = pullup ? 1 : 0;   // slow charge through the device's pull-up

                if ((potgo & pdir) == 0) {                       // line is an INPUT
                    if (potDatAct[joy][i] != 0 && hsync) {
                        potDat[joy][i] = (potDat[joy][i] + 1) & 0xFF;
                    }
                    if (potDatAct[joy][i] == 1) {
                        if (potDat[joy][i] < POTDAT_DELAY) {
                            charge = -2;                          // fast discharge
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
                } else {                                          // line is an OUTPUT
                    boolean high = (potgo & pdat) != 0;
                    charge = high ? 2 : -2;                       // immediate (dis)charge
                    if (high) {
                        potDatAct[joy][i] = 0;                    // high: count stopped
                    } else if (isButton) {
                        potDat[joy][i] = (potDat[joy][i] + 1) & 0xFF;  // "free running"
                    }
                }

                if (isButton) {
                    charge = -2;    // the button to ground wins over everything
                }
                if (canCharge) {
                    chargeCap(joy, i, charge);
                }
            }
        }
    }

    /** Charges the capacitor: |charge| &gt; 1 counts 80 times over, saturating at 511. */
    private void chargeCap(int joy, int i, int charge) {
        int c = (charge < -1 || charge > 1) ? charge * 80 : charge;
        potCap[joy][i] = Math.max(0, Math.min(511, potCap[joy][i] + c));
    }

    // =========================================================================
    // UART Service Methods
    // =========================================================================

    public void serviceTxdEvent(EventID id) {
        // Sentinel-bit scheme:
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
                // TBE: transmit buffer is now empty -> raise interrupt
                raiseInterrupt(0x0001);
            } else {
                // Nothing left to send - stop scheduling.
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
        // The rotation is driven by the drives' own tick; this only keeps the slot alive.
        agnus.scheduleRel(EventSlot.SLOT_DSK, 14, EventID.DSK_ROTATE);
    }

    /**
     * true when the disk FIFO has room and the DMA must supply another word. Agnus checks
     * this flag at the disk DMA slot: without it the DMA would drain DSKLEN at the rate of
     * the slots instead of the rotation of the medium (1 word every 16 MFM cells, ~227
     * cycles).
     *
     * <p>Writes go through the 3-word FIFO too: the three disk DMA slots are grouped at the
     * start of the raster line (CCK 7/9/11), while a word lasts half a line. Feeding the
     * shift register directly would load only one word per line, half the real rotation
     * rate. The FIFO is exactly what absorbs that grouping on the hardware.
     */
    public boolean isWriteWordNeeded() {
        return diskState == DriveDmaState.WRITE && dsk_fifoCount < 3;
    }

    /** Queues a word fetched by the DMA into the write FIFO. */
    public void loadWriteWord(int data) {
        writeFifo(data);
    }

    /**
     * Takes the next MFM cell to write to the medium. Called by the drive once per cell,
     * at the rotation rate.
     *
     * @return 0/1 = bit to write; {@link #WRITE_BIT_STALL} if the shifter is momentarily
     *         empty (the DMA has not supplied the word yet: the cell passes under the head
     *         unaltered); {@link #WRITE_BIT_END} when the transfer is over and the drive
     *         must leave write mode.
     */
    public int nextWriteBit() {
        if (dsk_writeBits == 0) {
            int word = readFifo();
            if (word < 0) {
                // FIFO empty: while the DMA is still active the word arrives at the next
                // disk slot, otherwise the last one has been written in full.
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

    /** Sentinel of {@link #nextWriteBit()}: no data ready for this cell. */
    public static final int WRITE_BIT_STALL = -2;
    /** Sentinel of {@link #nextWriteBit()}: write transfer over. */
    public static final int WRITE_BIT_END   = -1;

    /**
     * Read shifter with <b>no drive selected</b>: the floppy data line rests at its
     * pull-up, zeros on the A500, and the shift register keeps shifting them in. Kickstart
     * 1.3 sees DSKSYN from here at boot, since DSKSYNC resets to zero.
     *
     * <p>It does <b>less</b> than {@link #shiftInDiskBit(int)}: it shifts, updates DSKBYTR at
     * the byte boundary, compares with DSKSYNC (WORDEQUAL and the DSKSYN latch) and, during a
     * read DMA, hands the word over to the FIFO. It does not realign on WORDSYNC, so it never
     * starts a DMA waiting for a sync.
     */
    public void shiftInDiskBitIdle(int bit) {
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

        if (dsk_dataRegCount == 0 && diskState == DriveDmaState.READ
                && (getDmacon() & 0x0210) == 0x0210) {
            assembleReadWord(dsk_dataReg & 0xFFFF);
        }

        // The comparator is the same whatever feeds the shifter: WORDEQUAL and the
        // `dsksyncOn` latch as on the full path.
        if (dsk_dataReg != dsksync) {
            dsksyncOn = false;
        }
        if ((adkcon & 0x0200) == 0 && dsk_dataReg == dsksync) {
            dsk_syncCycle = agnus.getMasterClock();
            if (!dsksyncOn) {
                raiseInterrupt(0x1000); // DSKSYN
                dsksyncOn = true;
            }
        }
    }

    /**
     * Read shifter with a drive selected but none turning, during an armed read DMA: the
     * data line floats and each cell is noise. It takes the full path, so a DMA transfers
     * the words and a chance sync match acts as any other.
     */
    public void shiftInFloatingDiskBit() {
        shiftInDiskBit(floatingDataRng.nextInt(2));
    }

    /** A read DMA has been armed (DSKLEN written twice) and has not finished. */
    public boolean isDiskReadArmed() {
        return diskState == DriveDmaState.READ || diskState == DriveDmaState.WAIT;
    }

    public boolean isDiskWriting() {
        return diskState == DriveDmaState.WRITE;
    }

    /**
     * The noise of a floating data line. Random in the GUI; {@code -Damiga.diskseed=<n>}
     * fixes it, like the drives' own generators.
     */
    private final java.util.Random floatingDataRng = newFloatingDataRng();

    private static java.util.Random newFloatingDataRng() {
        Long seed = Long.getLong("amiga.diskseed");
        return seed == null ? new java.util.Random() : new java.util.Random(seed * 31 + 0x5EED);
    }

    /**
     * A bit pushed into the read shift register outside the bit clock, by a drive's motor
     * or head moving (see {@code FloppyDrive.slipShifter}). It moves the word boundary and
     * nothing else: it is not compared with DSKSYNC and completes no word.
     */
    public void slipDiskShifterBit(int bit) {
        dsk_dataReg = ((dsk_dataReg << 1) | (bit & 1)) & 0xFFFF;
        dsk_dataRegCount = (dsk_dataRegCount + 1) & 15;
    }

    public void shiftInDiskBit(int bit) {
        dsk_dataReg = ((dsk_dataReg << 1) | (bit & 1)) & 0xFFFF;

        // Paula's order: first the current word is CLOSED (DSKBYT update and hand-over to
        // the FIFO), only then is the sync match evaluated. So the second sync word of
        // standard sectors (which have two in a row) is transferred too.
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

        // -- Sync detection, one bit at a time -------------------------------------
        // The INTERRUPT is EDGE-TRIGGERED, the detection is not. The `dsksyncOn` latch is
        // raised at the first bit where the shifter matches and rearmed only when it stops
        // matching: a DSKSYNC that matches for several bits in a row (the limit case is
        // `dsksync == 0` with the shifter at zero) raises one interrupt, not a burst.
        //
        // The DETECTION stays at every bit: `dsk_syncCycle`/`dsk_syncMatches` are what the
        // comparator shows (WORDEQUAL in DSKBYTR reads them), not the interrupt. The
        // WORDSYNC realignment is in there too, outside the latch: on repeated matches the
        // shifter realigns anyway.
        //
        // The two gates:
        //   - MSBSYNC (ADKCON bit 9) gates both the match and the realignment;
        //   - a write inhibits the interrupt only.
        // WORDSYNC (bit 10) is NOT part of the interrupt condition: it governs the
        // realignment. See docs/vamigats-paula-audio-plan.md section 7.1.21.
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

            // A wordsync match resets the read shifter: with WORDSYNC on the match ALWAYS
            // realigns the read shift register, whatever the DMA state. Protections that
            // open the comparator with a zero-length DSKLEN (no transfer, reading through
            // DSKBYTR only) depend on it: Barbarian II compares the data bits of the first
            // word read and otherwise retries forever (black screen).
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
     * DMAL: Paula reports to Agnus the DMA slots it wants in the line. For the disk, the
     * number of words waiting in the read FIFO; for audio, the channels with a word request
     * pending, which the report hands over: the request is consumed here, and the slot the
     * line allocates for it carries the reload of the pointer along. A word assembled or a
     * request raised later waits for the next load. Agnus loads the DMAL only on a line whose
     * strobe arrived; on any other line the previous line's DMAL stays, and its slots run
     * again.
     */
    public void latchDmaRequests() {
        dskSlotRequests = diskState == DriveDmaState.READ ? dsk_fifoCount : 0;
        syncAudioChannels();
        for (AudioChannel ch : audioChannels) {
            ch.dmalRequest = ch.dmaRequest;
            ch.dmalReload = ch.dmaRequest && ch.dmaReloadRequest;
            ch.dmaRequest = false;
            ch.dmaReloadRequest = false;
            ch.refreshActiveMask();
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

    /** How many words are currently in the disk FIFO (0-3). */
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
     * Variant of {@link #readRegister(int)} <b>without side effects</b>, for the debugger
     * and disassembler. Three Paula registers change state when read, as the hardware does:
     * <ul>
     *   <li>{@code DSKDATR} ($008) takes a word out of the disk FIFO;</li>
     *   <li>{@code SERDATR} ($018) clears OVRUN;</li>
     *   <li>{@code DSKBYTR} ($01A) clears the DSKBYT bit.</li>
     * </ul>
     * Here they return the state <i>without</i> consuming it. Everything else goes through
     * {@code readRegister} unchanged. See {@code AbstractAgnus.peekCustomRegister}.
     */
    public int peekRegister(int address) {
        address &= 0x1FE;
        switch (address) {
            case 0x008: {
                // Peek at the oldest FIFO word without taking it out.
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
     * POTGOR ($DFF016). Returns <b>only</b> the four DAT bits (mask {@code $5500}): the
     * direction bits do not read back.
     *
     * <p><b>There is ONE rule, and it ignores the direction: the DAT bit is the voltage of
     * the CAPACITOR</b> (above 100). The direction only decides <b>how the capacitor
     * charges</b>, inside {@link #capCheck(boolean)}: fast (dis)charge for an output.
     *
     * <p><b>And the button wins anyway</b>: a button is a short to ground and Paula's output
     * driver is weak, so a line programmed as "output high" is pulled to zero all the same.
     *
     * <p>That is the idiom demos use to wait for a click on a still screen: <i>Hardwired</i>,
     * at "INSERT HARDWIRED DISK 2", programs port 1 as {@code POTGO=$0F00} (both lines
     * outputs, high) and then loops on {@code BTST #2,$DFF016} waiting for DATLY to drop.
     * Reading back the written data instead of the capacitor kept DATLY at 1 forever. See
     * {@code PotgorButtonOverridesOutputTest}.
     *
     * <p>The read itself runs {@code capCheck(false)}: that gives the button an immediate
     * response without waiting for the next line, since with {@code charge = -2} the
     * capacitor loses 160 per read and a tight poll takes it below threshold at once.
     */
    private int buildPotinp() {
        capCheck(false);
        int v = 0;
        if (potCap[0][0] > 100) v |= 0x0100;   // DATLX - port 1, line X
        if (potCap[0][1] > 100) v |= 0x0400;   // DATLY - port 1, line Y
        if (potCap[1][0] > 100) v |= 0x1000;   // DATRX - port 2, line X
        if (potCap[1][1] > 100) v |= 0x4000;   // DATRY - port 2, line Y
        return v;
    }

    private int buildSerdatr() {
        int val = serReceiveBuffer & 0x3FF;
        if (serOvrun)               val |= 0x8000; // OVRUN
        if ((intreq & 0x0800) != 0) val |= 0x4000; // RBF
        if (!serTxbFull)            val |= 0x2000; // TBE  - buffer empty (serTxbFull=false means buffer has no pending byte)
        if (serTransmitShiftReg == 0) val |= 0x1000; // TSRE - shift register empty
        val |= 0x0800; // RXD idle (mark)

        // Reading SERDATR clears only OVRUN. RBF must be cleared explicitly via INTREQ write.
        serOvrun = false;
        return val;
    }

    private int buildDskbytr() {
        int val = dskbytr_incoming & 0x80FF;
        // DMAEN (bit 14): disk DMA is active and enabled in DMACON.
        if (diskState != DriveDmaState.OFF && (getDmacon() & 0x0210) == 0x0210) val |= 0x4000;
        // DSKWRITE (bit 13): current transfer is a write (CPU->disk).
        if ((dsklen & 0x4000) != 0) val |= 0x2000;
        // WORDEQUAL (bit 12): level asserted after a DSKSYNC match.
        //   Guard: dsk_syncCycle==0 means no sync yet (or DSKSYNC was rewritten and the
        //   old match no longer counts).
        if (dsk_syncCycle != 0 && agnus != null
                && agnus.getMasterClock() - dsk_syncCycle <= WORDEQUAL_WINDOW) val |= 0x1000;
        // Clear DSKBYT (bit 15) on read - hardware behaviour.
        dskbytr_incoming &= 0x7FFF;
        return val;
    }

    /**
     * Length of the WORDEQUAL level (DSKBYTR bit 12) after a DSKSYNC match, in master
     * cycles: 2 MFM cells (~3.95 us), a double-density cell lasting 7 CCK = 14 master cycles
     * (see {@code FloppyDrive.CYCLES_PER_BIT}).
     *
     * <p>This is a deliberate choice over a level that lasts exactly while the live shift
     * register equals DSKSYNC (one cell). The PIO variant of the Rob Northen protections
     * samples DSKBYTR with a {@code BTST #4,$DFF01A / BEQ} loop that takes 26 cycles on the
     * 68000 (about 27.6 with bitplane DMA contention), almost TWICE a one-cell window: the
     * level would be caught about once in two, and since each sync passes once per
     * revolution every search would cost two revolutions on average.
     *
     * <p>That is how <b>Last Battle</b> (original IPF) fails: its protection looks for the
     * syncs $8911/$8912/$8914/$8951 in turn on track 1 (Copylock); with one cell the first
     * three cost two revolutions each, and at the fourth the game's watchdog (level 3
     * interrupt handler, PC $022FA6) switches the drive motor off, after which the loop can
     * never exit. With 2 cells every sync is caught at the first revolution
     * (docs/boot-randomness-plan.md, WORDEQUAL window).
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
                // New value to compare against: the previous match no longer counts.
                // Without this the WORDEQUAL level (2 cells wide) could read as a match
                // of the sync JUST written.
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
            // Second consecutive write with bit 15 set -> DMA start. Only the FIFO
            // is emptied here.
            //
            // The READ shift register and its bit counter are NOT touched. They
            // free-run with the head from the moment the drive is selected, and the
            // word boundary of the data the DMA transfers is the one they already
            // carry. Clearing them re-anchored that boundary to the DSKLEN write, so
            // a whole track landed in Chip RAM shifted by a few bits. Kickstart reads
            // the raw track with WORDSYNC off and re-aligns the sector headers in
            // software, so the machine still boots, but through the slow
            // re-alignment path, which costs about 890 cck on the instant the loaded
            // program starts (measured on the CIA/TOD/latch1 boot chain).
            // A WRITE does clear the shifter: see below.
            dsk_fifo         = 0;
            dsk_fifoCount    = 0;
            dsk_writeShift   = 0;
            dsk_writeBits    = 0;
            // Every drive's cell clock restarts from this write, selected or not.
            if (agnus != null) agnus.restartDrivesBitCell();

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
                // The selected drive goes into write mode: from here on its head takes
                // the cells from nextWriteBit() instead of reading them.
                if (agnus != null) agnus.startFloppyWriting();
            } else {
                diskState = ((adkcon & 0x0400) != 0) ? DriveDmaState.WAIT : DriveDmaState.READ;
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
     * POTGO ($DFF034).
     *
     * <p>Not a plain assignment. The <b>direction</b> bits are all taken from the write, but
     * the <b>data</b> bit of a line is updated <b>only if this same write</b> sets its
     * direction to 1; otherwise it keeps its previous value.
     *
     * <p>Bit 0 (START) rearms all four lines: counter to zero and capacitor to discharge.
     * See {@link #capCheck(boolean)}.
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

    /** CPU/Copper write to INTENA: read-back copy at once, IPL copy one CCK later. */
    private void setINTENA(int value) {
        boolean set = (value & 0x8000) != 0;
        int bits = value & 0x7FFF;
        if (set) intena |= bits; else intena &= ~bits;
        scheduleShadowPoke(value, true);
    }

    /** CPU/Copper write to INTREQ: read-back copy at once, IPL copy one CCK later. */
    private void setINTREQ(int value) {
        boolean set = (value & 0x8000) != 0;
        int bits = value & 0x7FFF;
        if (set) intreq |= bits; else intreq &= ~bits;
        scheduleShadowPoke(value, false);
    }

    /**
     * Interrupt <b>raised by a chip</b> (audio, disk, Copper, blitter, CIA, serial): sets or
     * clears <b>both</b> copies at the same instant.
     *
     * <p>Not the same as a program's write to INTREQ, which goes through
     * {@link #setINTREQ(int)} and defers the IPL copy. See {@link #intreq2}.
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
     * Delay, in master cycles, with which a CPU/Copper write reaches the IPL copies:
     * <b>2 master cycles = 1 CCK</b> (the IPL line changes 0.5 CCK later, and the 68000
     * needs one more CPU clock, 0.5 CCK, to detect it).
     *
     * <p>This value goes together with the pin stamp in {@code AbstractMotorola.fallingEdge()}
     * ({@code pinChangeAt}): changed apart, each moves interrupt recognition by one clock,
     * and the vAmigaTS bank {@code cpu/timing} sees it at once (245 reds out of 253 with
     * the first alone). See {@code docs/vamigats-paula-audio-plan.md} section 18.
     */
    private static final int INTREQ_SHADOW_DELAY_MC = 2;

    /**
     * How long a word delivered by the audio DMA waits before it is processed: 1 CCK = two
     * master cycles. See {@link AudioChannel#receiveDmaWord} and
     * {@code docs/vamigats-paula-audio-plan.md} section 22.
     */
    private static final int DMA_WORD_DELAY_MC = 2;

    /** CPU/Copper poke waiting to reach {@link #intreq2}/{@link #intena2}. */
    private long    shadowPokeAt = AbstractAgnus.NEVER;
    private int     shadowPokeValue;
    private boolean shadowPokeIsIntena;

    private void scheduleShadowPoke(int value, boolean isIntena) {
        if (shadowPokeAt != AbstractAgnus.NEVER) {
            // Two pokes within one CCK: they are two distinct events and the first one
            // expires first anyway, so it is applied here instead of being lost.
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

    /** ADKCON FAST: the disk controller reads at 2 us per bit (MFM) rather than 4 us. */
    public boolean isDiskFastMode() {
        return (adkcon & 0x0100) != 0;
    }

    private void raiseInterrupt(int mask) {
        setINTREQ(true, mask);
    }

    /**
     * Delay, in master cycles, between the moment a channel's state machine decides the
     * interrupt and the moment AUDxIR appears in INTREQ: <b>2 CCK</b> (one CCK of audio
     * delay plus the CCK every chip interrupt takes to reach INTREQ).
     */
    private static final int AUDIO_IRQ_DELAY_CYCLES = 4;   // 2 CCK = 4 master cycle

    /**
     * Raises AUDxIR not at once but {@link #AUDIO_IRQ_DELAY_CYCLES} later (vAmigaTS
     * {@code Paula/Audio}, see {@code docs/vamigats-paula-audio-plan.md}).
     */
    private void raiseAudioInterrupt(int channel) {
        scheduleIntreq(7 + channel, AUDIO_IRQ_DELAY_CYCLES);
    }

    /**
     * Schedules the set of an INTREQ bit {@code delayCycles} master cycles from now. If a
     * request for that bit is already queued <b>earlier</b>, it wins: two close events on
     * the same bit collapse into one, the second finding the bit already set.
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
     * Produces one output sample as the <b>time-weighted average</b> of each channel's
     * output over the interval just elapsed, rather than its instantaneous value: every
     * step of the emulation accumulates {@code sample * vol * dt}, and the sum is divided by
     * the time accumulated.
     *
     * <p>Reading the instantaneous value amounts to nearest-neighbour resampling: the edges
     * of Paula's output, which fall on a 7.09 MHz grid, move by up to 22.7 us to land on
     * the 44.1 kHz grid. That jitter is broadband noise with very strong lines next to the
     * fundamental. Measured on a 500 Hz sine generated the way Amiga Test Kit generates it:
     * <b>SNR 20.8 dB</b> with instantaneous sampling against the <b>28.8 dB</b> of the ideal
     * reference, which the integration reaches exactly.
     *
     * <p>The residual at 28.8 dB is the images of Paula's 8-bit staircase (15.5 and 16.5 kHz
     * for this signal), which the A500's fixed filter attenuates downstream. The
     * measurement lives in {@code PaulaAudioQualityTest}.
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
     * Delay, in master cycles, between the fall of a CIA's /IRQ line and PORTS/EXTER
     * appearing in INTREQ: <b>1 CCK</b>, the CCK every chip interrupt takes to reach INTREQ
     * (the audio delay, {@link #AUDIO_IRQ_DELAY_CYCLES}, adds one more).
     *
     * <p>Measured (plan section 7.1.17): Timer B underflow to INTREQ bit 3 is <b>+6 CCK</b>,
     * 5 for the CIA clock the CIA itself takes ({@code MOS8520.triggerIrq}) and 1 for this
     * event.
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

    /** Current DSKSYNC word (diagnostics). */
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

    /** Whether the DMAL in force gives channel {@code chan} its audio slot in this line. */
    public boolean isAudioDmaRequest(int chan) {
        return chan >= 0 && chan < 4 && audioChannels[chan].dmalRequest;
    }

    /**
     * Whether channel {@code chan} has a word request pending, not yet handed to a DMAL. The
     * two levels are distinct: the channel raises the request as soon as its period expires,
     * while {@link #isAudioDmaRequest} is the DMAL that was last loaded.
     */
    public boolean isAudioChannelDmaRequest(int chan) {
        return chan >= 0 && chan < 4 && audioChannels[chan].dmaRequest;
    }

    public int getAudioDmaAddress(int chan) {
        return chan >= 0 && chan < 4 ? audioChannels[chan].dmaPointer : 0;
    }

    /** Moves a channel's DMA pointer, as a merged RGA slot that transferred elsewhere does. */
    public void setAudioDmaAddress(int chan, int address) {
        if (chan >= 0 && chan < 4) {
            syncAudioChannels();
            audioChannels[chan].dmaPointer = address & 0x1FFFFE;
        }
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
                ch.stepDmaPointer();
                audDAT[chan] = value & 0xFFFF;
                ch.writeData(value);
            }
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
     * Taps the PCM chunk, already converted and filtered, towards the network
     * (docs/remote-play-plan.md section 4.2). {@code null} removes the tap.
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
     * The channel's output <b>towards the mixer</b>: sample x latched volume, masked. Range
     * -8192..8128.
     *
     * <p>It is not {@code getAudioCurrentSample(chan) * AUDxVOL}, for two reasons:
     * <ul>
     *   <li>the volume is the one <b>latched</b> at the period boundary, not the register:
     *       an AUDxVOL write enters the mixer at the next period boundary;</li>
     *   <li>a channel that <b>modulates</b> the next one is silent: every channel that
     *       appears in ADKCON as a volume or period modulator
     *       ({@code adkcon | (adkcon >> 4)}) is masked to 0.</li>
     * </ul>
     */
    public int getAudioMixerOutput(int chan) {
        if (chan < 0 || chan > 3) return 0;
        int t = adkcon | (adkcon >> 4);
        if ((t & (1 << chan)) != 0) return 0;    // modulator: masked
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
                // The register only: it enters the mixer at the next period boundary, when
                // loadPeriod() latches the mixer volume. See AudioChannel.mixVolume.
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
        /**
         * The pending request reloads the pointer: its slot fetches the word at the pointer
         * and then loads the pointer from AUDxLC instead of stepping it. Set on the request
         * that starts the channel and on the one for the last word of a block.
         */
        private boolean dmaReloadRequest;
        /**
         * The channel's bits in the DMAL last loaded: the line has an audio slot for it, and
         * that slot reloads the pointer instead of stepping it. They stay until the next load,
         * whatever the slot does.
         */
        private boolean dmalRequest;
        private boolean dmalReload;
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
         * The priming word has arrived and the <b>next</b> one is the first to play. It
         * matters for one reason: that word <b>does not decrement the remaining length</b>.
         * The length drops by one for the discarded priming word and then once for every
         * word that arrives <i>while the channel is playing</i>; the arrival that starts it
         * playing is neither, so it does not count. Counting it played <b>LEN-1</b> words per
         * block instead of LEN.
         */
        private boolean dmaFirstWord;
        /**
         * The channel is playing a word the CPU wrote to AUDxDAT with the DMA off (playing in
         * interrupt mode, as opposed to idle). It tells the write that <i>starts</i> the
         * channel (which requests the interrupt) from all the later ones, which are only the
         * refill the previous interrupt asked for and must not request another. See
         * {@link #writeData}.
         */
        private boolean cpuDriven;
        private int bytePhase;
        private int periodCounter;
        private int periodClocks;
        /**
         * The <b>latched</b> volume, the one that actually enters the mixer. AUDxVOL (the
         * register) gets in only at the period boundary (see {@link #loadPeriod()}), so a
         * volume write takes effect at the <b>next period boundary</b>, not at once
         * (docs/vamigats-paula-audio-plan.md section 9.4).
         */
        private int mixVolume;
        private float mixVolumeScale;
        private int currentSample;
        /** Integral of {@code currentSample * mixVolumeScale} over the interval's cycles. */
        private double sampleAccum;
        /**
         * Master cycles left before {@link #processDmaWord()}: the one-CCK delay with which a
         * word delivered by the DMA is processed (see {@link #receiveDmaWord}). 0 = nothing
         * pending.
         */
        private int dmaWordDelay;

        /** Master cycles until a block's reload request is raised; 0 = none pending. */
        private int reloadRequestDelay;

        private AudioChannel(int index) {
            this.index = index;
        }

        private void reset() {
            dmaEnabled = false;
            dmaRequest = false;
            dmaReloadRequest = false;
            dmalRequest = false;
            dmalReload = false;
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
            reloadRequestDelay = 0;
            refreshActiveMask();
        }

        /**
         * DMACON has switched the channel on or off.
         *
         * <p>Switching it <b>off does not stop the channel</b>. Only the DMA start-up, before
         * the first word plays, checks the enable and goes back to idle. A channel that is
         * playing only changes <i>who refills it</i>: it carries on in interrupt mode,
         * repeats the last AUDxDAT and stops only when the CPU stops acknowledging. That is
         * what {@code simple/dmaaud2} measures ("the frequency changes, because no samples
         * are fed into AUD0DAT any more") and what its twin {@code dmaaud1} shows, with the
         * level 4 handler still invoked seven seconds after the switch-off.
         *
         * <p>For the same reason <b>switching on depends on where the channel is</b>: the
         * start-up sequence (idle, priming, first word) belongs to an idle channel only. A
         * channel already playing sees the DMACON write only as a change of supplier.
         * {@code timing/dmatim2} is the idle channel (which must deliver the pending
         * end-of-block), {@code timing/dmatim3} the playing one (which must not start over).
         */
        private void setDmaEnabled(boolean enabled) {
            if (dmaEnabled == enabled) return;
            // The state BEFORE the DMACON write: with the DMA off, isPlaying() means playing
            // in interrupt mode, anything else is idle. The DMA start-up sequence belongs to
            // the idle case alone (see below).
            final boolean wasPlaying = isPlaying();
            dmaEnabled = enabled;
            if (enabled && wasPlaying) {
                // DMACON switches the DMA on for a channel that <b>is still playing</b>:
                // nothing happens. No priming, no immediate request: the DMA simply resumes
                // refilling at the next period boundary, the normal path of
                // advanceSampleByte()/loadCurrentWord().
                //
                // Starting from the priming again costs three errors in a row, all visible
                // on vAmigaTS timing/dmatim3 (plan section 23.3): an interrupt request of the
                // wrong kind (start instead of end-of-block), the remaining length out of
                // phase and so no end-of-block on the next line, and the pointer stepping
                // instead of reloading at every loop.
                cpuDriven = false;      // the DMA takes the place of interrupt mode
                refreshActiveMask();
                return;
            }
            if (enabled) {
                // Idle -> priming. If the channel was IDLE and carries an end-of-block not
                // yet delivered, switching the DMA on <b>delivers it at once</b>. It is the
                // only audio interrupt raised by a DMACON write rather than by a slot or a
                // period boundary; without it vAmigaTS timing/dmatim2 missed a whole
                // handler invocation at lines 112 and 160 (plan section 23). It exists only
                // because the pending end-of-block SURVIVES the switch-off: see
                // clearDmaPipeline().
                //
                // `cpuDriven` is already false here: this branch is taken only with
                // `wasPlaying` false, which with the DMA off is exactly `!cpuDriven`.
                // The start request fetches at the pointer the channel was left with, and its
                // slot then loads the pointer from AUDxLC.
                reloadDmaLength();
                dmaPriming = true;
                if (nextWordEndsBlock) {
                    nextWordEndsBlock = false;
                    raiseAudioInterrupt(index);
                }
                requestNextWord(true);
                dmaReloadRequest = true;
            } else if (dmaPriming || dmaFirstWord) {
                // Start-up (priming or first word): the channel has played nothing yet and
                // will not, it goes back to idle (dmaonoff1).
                clearDmaPipeline();
            } else {
                // Playing: the channel survives. Only the DMA pipeline drops; the queued
                // word is not really lost, since AUDxDAT holds it and the refill picks it
                // up from there (advanceSampleByte, fallback branch).
                // A word request already raised stays pending: the next DMAL hands it over
                // and, while any audio channel has DMA on, its slot still runs and delivers
                // the word as a CPU write to AUDxDAT (receiveAudioDmaWord).
                nextWord = 0;
                nextWordValid = false;
                // `nextWordEndsBlock` is NOT cleared: see clearDmaPipeline(). Keeping it is
                // inert while the DMA stays off (only loadCurrentWord() and
                // consumeQueuedWord() consume it, both behind `nextWordValid`) and becomes
                // observable when the DMA is switched on again.
                cpuDriven = true;
            }
            refreshActiveMask();
        }

        /**
         * The channel goes back to idle.
         *
         * <p><b>It does not clear the pending end-of-block.</b> The flag survives the DMA
         * switch-off and stays until switching it on again delivers it
         * ({@link #setDmaEnabled}). Only a real <b>reset</b> clears it, in {@link #reset()}.
         */
        private void clearDmaPipeline() {
            // The pending word and reload requests are not cleared: only the DMAL load
            // consumes them (see setDmaEnabled).
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
            // currentSample is NOT cleared: a channel that stops stays at the last value
            // it output, it does not return to zero (see advanceSampleByte()).
        }

        /**
         * The channel is playing: the only state in which the period counter runs.
         *
         * <p>The others: {@code dmaPriming}/{@code dmaFirstWord} are the DMA start-up (first
         * word not playing yet), and "neither DMA nor interrupt mode" is idle.
         */
        private boolean isPlaying() {
            if (dmaEnabled) {
                return !dmaPriming && !dmaFirstWord;
            }
            return cpuDriven;
        }

        /**
         * Advances the channel by {@code cycles} master cycles, <b>integrating</b> its output.
         *
         * <p>Between two expiries of the period counter the channel's output is constant, so
         * the integral over the interval is exact: each value is weighted by the number of
         * cycles it stayed at the output. The counter is decremented piecewise to know
         * <i>how long</i> each value lasted.
         */
        private void tick(int cycles) {
            if (reloadRequestDelay > 0) {
                reloadRequestDelay -= cycles;
                if (reloadRequestDelay <= 0) {
                    reloadRequestDelay = 0;
                    dmaRequest = true;
                    dmaReloadRequest = true;
                }
            }
            // The interval is split at the deadline of the pending DMA word, so the
            // deferred handler lands on the exact master cycle, not at the end of the
            // interval. computeAudioMinCounter() accounts for the same deadline, so there is
            // at most one split per call, but the loop handles more anyway.
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
                // Idle or starting up: the period counter does NOT run. During the DMA
                // start-up, running it would give the channel premature period boundaries
                // that consume words: on dmamod3 that swapped volume and period for good,
                // since the table's words alternate. When idle, they would be boundaries
                // requesting refill interrupts from a channel that is not playing.
                //
                // The output is still integrated: the sample stays, and dropping it from
                // the active mask would count it as zero.
                sampleAccum += currentSample * (double) mixVolumeScale * cycles;
                return;
            }
            int remaining = cycles;
            while (remaining >= periodCounter) {
                int step = periodCounter;
                sampleAccum += currentSample * (double) mixVolumeScale * step;
                remaining -= step;
                // The boundary decides first and latches the volume after: a channel in
                // interrupt mode that stops here for want of a refill keeps the volume
                // it had, so a VOL written meanwhile does not reach the held sample.
                boolean latch = dmaEnabled || bytePhase == 0;
                periodCounter = periodClocks;   // periodClocks >= 2: the loop always ends
                advanceSampleByte();
                if (latch && isPlaying()) latchMixVolume();
            }
            sampleAccum += currentSample * (double) mixVolumeScale * remaining;
            periodCounter -= remaining;
        }

        /**
         * Rearms the period counter <b>and latches the volume</b>. The two go together: the
         * volume the mixer uses for the sample about to come out is the one AUDxVOL had at
         * the period boundary. See {@link #mixVolume}.
         *
         * <p><b>With the DMA off the low-byte boundary does not latch</b>: it rearms the
         * counter without touching the volume. A channel in interrupt mode therefore latches
         * the volume <b>once per word</b>, at the high-byte boundary, not twice. It shows on
         * all manual volume modulation ({@code irqmod1}, {@code irqmod3}), where one latch
         * too many moves every step of the ramp half a period early.
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

        /** The current latched volume, for {@link Paula#getAudioMixerOutput(int)}. */
        private int mixVolume() {
            return mixVolume;
        }

        /**
         * Average of the output over the interval just closed, and reset of the
         * accumulator. {@code intervalCycles} is the FULL length of the interval: the part
         * where the channel was inactive is not in the accumulator and counts as zero.
         */
        private double takeAverageSample(int intervalCycles) {
            double accum = sampleAccum;
            sampleAccum = 0.0;
            return intervalCycles > 0 ? accum / intervalCycles : 0.0;
        }

        /**
         * CPU write to AUDxDAT. With the DMA on it only refills; with the DMA off it is
         * interrupt mode, and the only place a write can request an interrupt: <b>only</b>
         * the write that finds the channel idle, and only with no interrupt of the channel
         * already pending (then the channel stays idle).
         *
         * <p>Raising AUDxIR at <i>every</i> write would lock the machine: the level 4 handler
         * that refills the sample would rearm itself before its own RTE, re-enter at once,
         * and lower interrupts (VERTB first) would never be served again. In interrupt mode
         * the period sets the pace: {@link #advanceSampleByte()} asks for the next word when
         * both bytes have played.
         */
        private void writeData(int value) {
            nextWord = value & 0xFFFF;
            nextWordValid = true;
            nextWordEndsBlock = false;
            if (dmaEnabled) {
                // A modulator never plays its word: it reads it from audDAT at period
                // boundaries (see advanceModulator).
                if (!currentWordValid && !isModulator()) {
                    loadCurrentWord();
                }
                refreshActiveMask();
                return;
            }
            if (cpuDriven) {
                // Refill requested by the previous interrupt: the word waits,
                // advanceSampleByte() takes it at the end of the period.
                refreshActiveMask();
                return;
            }
            if ((intreq & (0x0080 << index)) != 0) {
                // The channel's interrupt is still pending: the channel does not restart.
                refreshActiveMask();
                return;
            }
            cpuDriven = true;
            raiseAudioInterrupt(index);
            if (isModulator()) {
                // Loading the word into a modulator plays nothing: it applies the VOLUME
                // modulation, and applies it HERE, as the channel starts playing, not at
                // the first period boundary. The first boundary is therefore the PERIOD
                // one: bytePhase starts from 1.
                //
                // Starting it from 0 shifts the whole modulation table by half a period,
                // and on a table alternating volume and period (irqmod3/4) swaps the two
                // for good: the volume words ended up in AUDxPER, the modulated channel
                // stopped at the first loop and the trace closed at 61 events instead of
                // 13995.
                if ((adkcon & (1 << index)) != 0) {
                    audioChannels[index + 1].setModulatedVolume(audDAT[index]);
                }
                consumeQueuedWord();
                loadPeriod();
                bytePhase = 1;
            } else {
                loadCurrentWord();
                loadPeriod();
                // ...and the high byte comes out at the SAME instant, at the AUDxDAT write,
                // not one period later. In a trace aligned on its own first event the shift
                // is invisible, but it moves the handler's yellow bands by a whole period
                // against the sound, which is what the screenshot sees.
                advanceSampleByte();
            }
            refreshActiveMask();
        }

        /**
         * The DMA slot has delivered the word. Only the data, and for a channel already
         * playing the end-of-block flag, take effect here; everything else is armed
         * {@link #DMA_WORD_DELAY_MC} master cycles later, <b>one CCK after</b> the slot.
         *
         * <p>Measured on {@code timing/dmalen0} (plan section 22): the slot falls at hpos 17
         * and the interrupt request follows exactly 1 CCK later, on every branch and on all
         * eight lines of the block. Without this CCK the handler takes the previous
         * instruction boundary and the band moves by 5 CCK = 20 px. The other half, that a
         * word reaching a channel already playing does not start playing at all, is section
         * 21.
         */
        private void receiveDmaWord(int value) {
            audDAT[index] = value & 0xFFFF;
            nextWord = value & 0xFFFF;
            nextWordValid = true;

            stepDmaPointer();
            if (!dmaPriming && !dmaFirstWord) {
                // The end-of-block flag is set AT ONCE, before the delay. The length
                // decrement that justifies it happens in the deferred handler, so its
                // result is anticipated here: `<= 1` and not `== 1`, because the decrement
                // is clamped at zero and the remaining length stays there until
                // `requestNextWord()` reloads the block. With `== 1` the word after the
                // end-of-block one lost its own interrupt (dmalen0: one request fewer per
                // block).
                nextWordEndsBlock = remainingWords <= 1;
            }
            dmaWordDelay = DMA_WORD_DELAY_MC;
            refreshActiveMask();
        }

        /** The deferred processing of a DMA word, one CCK after the slot. See {@link #receiveDmaWord}. */
        private void processDmaWord() {
            if (dmaPriming) {
                nextWord = 0;
                nextWordValid = false;
                nextWordEndsBlock = false;
                dmaPriming = false;
                dmaFirstWord = true;
                raiseAudioInterrupt(index);
                // The request is decided on the length before this word is counted.
                requestNextWord(true);
                if (remainingWords != 1) {
                    remainingWords = (remainingWords - 1) & 0xFFFF;
                }
                refreshActiveMask();
                return;
            }
            final boolean startsPlayback = dmaFirstWord;
            if (dmaFirstWord) {
                // First word -> playing: this word starts playing but does not decrement
                // LEN, and it is HERE that the period counter restarts.
                dmaFirstWord = false;
                loadPeriod();
                nextWordEndsBlock = remainingWords == 0;
            } else if (remainingWords == 1) {
                // The last word of the block reloads the length at once, from AUDxLEN as it
                // stands: a LEN written afterwards belongs to the block after next, and the
                // next request still sees one word left, so it reloads the pointer.
                reloadDmaLength();
            } else if (remainingWords > 0) {
                remainingWords--;
            }

            // A word reaching a channel ALREADY playing does not start playing at the DMA
            // slot: only the remaining length is updated. The word enters at the next period
            // boundary, and that is where the end-of-block interrupt is raised.
            //
            // Measured on vAmigaTS timing/dmatim5 (docs/vamigats-paula-audio-plan.md
            // section 21): channel 0's DMA slot falls at hpos 17, but the interrupt comes 1
            // or 2 CCK later depending on the byte phase of the channel. With AUD0PER=1
            // requesting it in the slot's own cycle made the level 4 handler miss the main
            // loop's `dbra` boundary, moving the yellow band by 6 CCK.
            //
            // Only the start (first word -> playing) is immediate. A modulator never plays
            // its word: it reads it from audDAT at period boundaries (advanceModulator).
            if (startsPlayback && !currentWordValid && !isModulator()) {
                loadCurrentWord();
            }
            if (startsPlayback) {
                // ...and the first byte comes out AT ONCE, not at the next period boundary.
                // Waiting a whole period is invisible in a trace aligned on its own first
                // event, but not when the channel is switched off early: dmaonoff2 keeps
                // the DMA on for two lines only, and then played nothing instead of 486
                // events.
                advanceSampleByte();
            }
            refreshActiveMask();
        }

        /**
         * The channel is <b>modulating</b> the next one: ADKCON gave it the volume (bit
         * {@code index}) or the period (bit {@code index+4}) of channel {@code index+1}.
         * Channel 3 modulates nobody, but its bits mute it all the same; that is done by
         * {@link Paula#getAudioMixerOutput(int)}, not by this method.
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
                // Both bytes of the word have played: HERE interrupt mode asks for the
                // refill, one period after the low byte. If the previous interrupt is still
                // pending the CPU did not refill in time and the channel stops.
                boolean stillPending = (intreq & (0x0080 << index)) != 0;
                raiseAudioInterrupt(index);
                if (stillPending) {
                    // The channel goes idle and the period counter stops (isPlaying()
                    // turns false). currentSample is NOT touched: the output stays frozen
                    // at the last byte played. Clearing it added a "-> 0" event per loop to
                    // dmaonoff2: 728 events instead of 486.
                    cpuDriven = false;
                    nextWordValid = false;
                    refreshActiveMask();
                    return;
                }
            }
            if (!currentWordValid && !loadCurrentWord()) {
                // The word is loaded unconditionally: without a refill the channel plays
                // the last word written again instead of going silent, and stops at the
                // first interrupt not served (the check above). That keeps the pace of one
                // interrupt every two periods.
                //
                // It holds with the DMA ON too: if the audio slot has not delivered the
                // word yet, Paula plays the content of AUDxDAT again, not silence. It is
                // the safety net behind the early request in loadCurrentWord(): a period
                // of zero is an audible hole, a repeated word is not.
                currentWord = audDAT[index] & 0xFFFF;
                currentWordValid = true;
                currentWordEndsBlock = false;
                bytePhase = 0;
                if (dmaEnabled && nextWordEndsBlock) {
                    // ...and the pending end-of-block is delivered HERE, even if the DMA has
                    // not delivered anything (yet): loading the word and checking the flag
                    // are separate. The flag travels with the queued word, so without this
                    // branch an end-of-block that survived a DMA switch-off (section 23.2)
                    // would never be delivered: on timing/dmatim3 the interrupt comes at
                    // vpos 144 and 160, the first period boundary after DMACON switched a
                    // still-playing channel back on.
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
            // No request here: a channel asks for its next word only at a word boundary
            // (loadCurrentWord), so a word lost to a merged slot is asked for again one
            // word later, not one sample later.
            refreshActiveMask();
        }

        private boolean loadCurrentWord() {
            if (!nextWordValid) {
                if (dmaEnabled) requestNextWord(false);
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
            // The request for the next word is ALWAYS raised when a word starts playing,
            // the last one of the block included, whatever the remaining length; the
            // request itself chooses between a normal request and one that reloads the
            // pointer (one word left).
            //
            // Skipping it at the block boundary takes a whole word of lead away from the
            // DMA: the request would start only after the last word had played in full,
            // and the audio slot comes once per line (454 cycles). With a short period the
            // word arrived late and the channel stayed at ZERO for a whole period: on a
            // 10 kHz square wave, 40 holes per second, sidebands ~39.5 Hz from the
            // fundamental and as strong as the fundamental itself.
            if (dmaEnabled) {
                requestNextWord(false);
            }
            refreshActiveMask();
            return true;
        }

        /**
         * A period boundary on a <b>modulator</b> channel.
         *
         * <p>A modulator runs the same state machine as a normal channel, but its two period
         * boundaries <b>do not do the same thing</b>:
         * <ul>
         *   <li><b>high-byte boundary</b>: sets the next channel's volume if ADKCON asks
         *       for volume modulation;</li>
         *   <li><b>low-byte boundary</b>: sets the next channel's period if ADKCON asks for
         *       period modulation.</li>
         * </ul>
         *
         * <p>The word is always <b>the last one arrived</b> ({@code audDAT[index]}): a
         * modulator plays nothing.
         *
         * <p>And the request for the next word starts at different boundaries: at the high
         * one only with volume modulation (or with neither), at the low one only with period
         * modulation. With <b>both</b> modulations on it starts at both, so the channel
         * consumes <b>one word per period</b> instead of one every two: one feeds the
         * volume, the next the period, alternating. That is how the table of
         * {@code dmamod3} is written ({@code 0,0, 1,0, 0,2, 2,0, ...}), and why
         * {@code dmamod4}, which has an odd number of them, "swaps volume and period on
         * alternate loops".
         *
         * <p>Taking volume <i>and</i> period from the same word puts words meant for the
         * volume into AUDxPER: {@code $0002} becomes a 2 CCK period and the channel explodes
         * (594169 events instead of 13996).
         */
        private void advanceModulator() {
            final boolean audav = (adkcon & (1 << index)) != 0;
            final boolean audap = (adkcon & (0x10 << index)) != 0;

            currentSample = 0;      // a modulator is silent
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
         * Releases the queued word. A modulator does not play it (it always reads AUDxDAT),
         * but the queue must be emptied all the same, since {@code nextWordValid} is what
         * keeps a second request from starting. The end-of-block interrupt fires here, next
         * to the request for the next word.
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
         * Asks for the next word: by DMA if the channel is in DMA, by interrupt if it runs
         * in manual mode ({@code irqmod*}).
         *
         * <p>{@code checkPendingIrq} tells the two period boundaries apart, and the
         * distinction is essential. The check "the previous interrupt is still pending, so
         * the CPU did not refill in time, so the channel stops" applies <b>only at the
         * high-byte boundary</b>; the low-byte one requests without checking. Applying it at
         * both stops the channel at the first loop when ADKCON asks for both modulations,
         * since the two interrupts fall half a period apart and the second always finds the
         * first pending: {@code irqmod3} went from 13995 events to 61.
         */
        private void askForNextWord(boolean checkPendingIrq) {
            if (dmaEnabled) {
                requestNextWord(false);
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
         * Volume set by the previous channel. It sets the register, <b>not</b> the latched
         * volume: the value enters the mixer at the next period boundary, not at once. See
         * {@link #mixVolume}.
         */
        private void setModulatedVolume(int word) {
            audVOL[index] = Math.min(word & 0x7F, 64);
        }

        /**
         * Period set by the previous channel. The running counter is <b>not</b> rearmed: the
         * new period applies from the next period boundary. Rearming it at once would
         * shorten or lengthen the period in progress.
         */
        private void setModulatedPeriod(int word) {
            audPER[index] = word & 0xFFFF;
            periodClocks = computePeriodClocks();
        }

        /**
         * Raises the request for the next word. It is raised again while one is still pending,
         * since the next DMAL load hands over whatever is pending by then: with a single word
         * left in the block it adds the pointer reload to a request that did not have it.
         * At a period boundary, the request for the last word of a block is only the reload
         * request, and it is raised one CCK later: a DMAL loaded on the cycle of the boundary
         * does not see it, and switching DMA off meanwhile does not cancel it. A start-up
         * request (DMA switched on, first word) is raised at once.
         */
        private void requestNextWord(boolean startup) {
            if (!dmaEnabled || nextWordValid) return;
            if (remainingWords == 0) {
                reloadDmaLength();
            }
            if (!startup && remainingWords == 1) {
                if (reloadRequestDelay == 0) reloadRequestDelay = DMA_WORD_DELAY_MC;
                refreshActiveMask();
                return;
            }
            dmaReloadRequest = (dmaRequest && dmaReloadRequest) || remainingWords == 1;
            dmaRequest = true;
            refreshActiveMask();
        }

        private void reloadDmaLength() {
            remainingWords = audLEN[index] & 0xFFFF;
            if (remainingWords == 0) {
                remainingWords = 0x10000;
            }
        }

        /** The pointer after this channel's DMA slot: stepped, or reloaded from AUDxLC. */
        private void stepDmaPointer() {
            dmaPointer = dmalReload ? shadowAddress(index) : (dmaPointer + 2) & 0x1FFFFE;
        }

        private int computePeriodClocks() {
            int per = audPER[index] & 0xFFFF;
            if (per == 0) per = 0x10000;
            return Math.max(2, per * 2);
        }

        /**
         * CPU write to AUDxPER. Like {@link #setModulatedPeriod}, it updates the period length
         * only: the period in progress ends with the old length.
         */
        private void reloadPeriodClocks() {
            periodClocks = computePeriodClocks();
        }

        private void refreshActiveMask() {
            // cpuDriven is part of the condition because interrupt mode lives on the period
            // counter: a channel leaving the mask would stop counting and never ask for the
            // next word again.
            boolean active = dmaEnabled || dmaRequest || nextWordValid || currentWordValid
                    || cpuDriven || currentSample != 0 || dmaWordDelay > 0 || reloadRequestDelay > 0;
            if (active) activeAudioMask |= (1 << index);
            else        activeAudioMask &= ~(1 << index);
        }
    }

}

