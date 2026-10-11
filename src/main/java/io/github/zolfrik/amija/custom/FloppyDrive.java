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

import io.github.zolfrik.amija.custom.audio.FloppySound;

import java.io.IOException;
import java.util.Arrays;
import java.util.function.IntConsumer;

/**
 * An Amiga floppy drive mechanism.
 *
 * Control and status lines:
 * - /MTR  (active low): latched on the falling edge of /SEL.
 * - /SELx (active low): selects the drive.
 * - /STEP (active low): a pulse (falling edge) moves the head.
 * - DIR   (0 = inward, track+1; 1 = outward, towards track 0): step direction.
 * - /RDY  (active low): low when the motor is up to speed, or the current ID bit.
 * - /TK0  (active low): low when the head is on track 0.
 * - /WPRO (active low): low when the disk is write protected.
 * - /CHNG (active low): latched low at a disk change, reset by a STEP with a disk inside.
 *
 * The head can reach cylinder 83 ({@link #MAX_TRACKS}), past the 80 cylinders of a disk.
 */
public final class FloppyDrive {

    // -------------------------------------------------------------------------
    // Drive type: the hardware ID shifted out on /RDY
    // -------------------------------------------------------------------------

    public enum DriveType {
        /** DF0 of the A500: internal DD drive, no ID hardware. */
        DD_INT(0x00000000),
        /** A 3.5" DD drive with ID hardware: DF1/DF2/DF3, and DF0 of the A1200. */
        DD_EXT(0xFFFFFFFF),
        /** External HD drive (A3000/A4000). */
        HD_EXT(0xAAAAAAAA);

        final int id;
        DriveType(int id) { this.id = id; }
    }

    // -------------------------------------------------------------------------
    // Identity
    // -------------------------------------------------------------------------

    private final String    id;
    private final DriveType driveType;

    // -------------------------------------------------------------------------
    // PAL timing constants (base clock: 7.09379 MHz)
    // -------------------------------------------------------------------------

    private static final long   CYCLES_INDEX_PULSE       =    14_187L;   // INDEX pulse length (~2 ms)
    /**
     * /RDY spin-up: the drive asserts DSKRDY a FIXED time after the motor is
     * commanded on, and that time restarts <b>in full</b> at every off-&gt;on
     * transition. It is not a speed ramp resumed from the residual spin: a
     * loader that drops and re-raises MTR between two tracks pays it whole
     * every time, which is several tenths of a second per track.
     *
     * <p>18 frames of 312 scanlines, counted in horizontal syncs (see
     * {@link #hsync()}): DSKRDY rises on a line boundary, never inside a line.
     */
    private static final int    DSKREADY_UP_LINES        = 18 * 312; // ~0,36 s

    /** A real motor takes about 3 s to stop completely. */
    private static final long   CYCLES_MOTOR_STOP        = 21_000_000L;  // motor stop ramp (~3 s)
    private static final long   CYCLES_SETTLE_STEP       =    21_281L;   // settle after a step in the same direction (~3 ms)
    /**
     * Minimum distance between two STEP pulses for the second to be accepted:
     * <b>140 CCK</b>, i.e. <b>280</b> cycles of the 7.09 MHz base clock.
     */
    private static final long   CYCLES_MIN_STEP_INTERVAL =         280L;   // 140 CCK
    private static final long   CYCLES_SETTLE_DIR_CHG    =   127_688L;   // settle after a direction change (~18 ms)
    /**
     * Length of a double-density MFM cell in master cycles: <b>exactly 7 CCK = 14 master
     * cycles</b>, i.e. {@code 7 * 256} in units of 1/256 CCK.
     *
     * <p>Not "2 us" but 1.9735 us. The two differ by 1.34%, and this is the value that makes
     * a revolution last a round 200 ms: the nominal track has {@link #NOMINAL_TRACK_BITS} =
     * 101344 cells, and 101344 x 7 CCK = 709408 CCK = 200.0 ms, the 300 rpm of an Amiga drive.
     * With 2 us a revolution took 202.7 ms, which delayed the Kickstart's bootblock load by
     * 15112 CCK (docs/vamigats-paula-audio-plan.md section 13.9.4).
     */
    private static final double CYCLES_PER_BIT           =    14.0;
    /**
     * Cells per revolution of a DD track of nominal length ({@code 2 * 8 * 6334}). The cell
     * length of a long track is scaled against it.
     */
    private static final int    NOMINAL_TRACK_BITS       = 12_668 * 8;   // 101 344
    /** {@link #CYCLES_PER_BIT} in units of 1/256 CCK, the unit of the IPF cell clock. */
    private static final int    NOMINAL_CELL_UNITS       = 7 * 256;
    /** A track shorter than this many cells is a 4 us one: its cells last twice as long. */
    private static final int    FOUR_US_TRACK_BITS       = NOMINAL_TRACK_BITS * 4 / 6;
    /** One master cycle in units of 1/256 CCK. */
    private static final int    UNITS_PER_MASTER_CYCLE   = 128;
    /** The head reaches cylinder 83 (0-based). */
    private static final int    MAX_TRACKS               = 84;
    private static final int    BIT_CLOCK_SCALE          = 10_000;
    /** {@link #CYCLES_PER_BIT} in fixed point ({@link #BIT_CLOCK_SCALE}), ADF path. */
    private static final int    ADF_CYCLES_PER_BIT_FIXED = 140_000;
    private static final int    TRACK_BUFFER_SIZE        = DiskImage.TRACK_BUFFER_BYTES;
    private static final int    ID_SEQUENCE_BITS         = 32;

    // -------------------------------------------------------------------------
    // Motor state
    // -------------------------------------------------------------------------

    private boolean motorTarget  = false;
    private boolean motorAtSpeed = false;
    /** Horizontal syncs left before /RDY rises; reloaded whole at every motor-on. */
    private int     readyUpLines = 0;
    private long    switchCycle  = 0L;
    private long    totalCycles  = 0L;
    /** Cycles banked by tick() and not applied yet: they hold no event (see cyclesToNextEvent). */
    private int     pendingCycles = 0;
    /** Cycles that can be banked before tick() must run in full; 0 = at the next call. */
    private int     eventCycles   = 0;
    /** Instant of the last ACCEPTED STEP pulse. */
    private long    lastAcceptedStepCycle = Long.MIN_VALUE / 4;

    // -------------------------------------------------------------------------
    // Head position
    // -------------------------------------------------------------------------

    private int     currentTrack      = 0;
    private int     targetTrack       = 0;
    private long    settleCounter     = 0L;
    private boolean currentSide       = false;

    // -------------------------------------------------------------------------
    // Disk
    // -------------------------------------------------------------------------

    /** Current disk image; null = no file-backed disk. */
    private DiskImage diskImage      = null;

    private boolean diskInserted   = false;
    private boolean writeProtected = false;

    /** Disk change latch: true = /CHNG low (active). */
    private boolean dskchange = false;
    /**
     * True once the machine has started running (first {@link #tick}). It tells a
     * <b>cold</b> insert, made by the configuration before power-on, which is not a change,
     * from a <b>hot</b> one, which latches {@code /CHNG}. A reset does not clear it: a
     * Ctrl-Amiga-Amiga does not put the machine back in the "off with the disk already
     * inside" state.
     */
    private boolean machineRunning = false;

    /** true = drive physically connected to the bus; false = absent (DF1-DF3 not connected). */
    private boolean connected = true;

    // -------------------------------------------------------------------------
    // INDEX pulse
    // -------------------------------------------------------------------------

    private boolean indexPulse        = false;
    private long    indexPulseCounter = 0L;

    // -------------------------------------------------------------------------
    // Drive ID shift register
    // -------------------------------------------------------------------------

    private boolean idBit      = true;
    private int     idBitIndex = 0;

    // -------------------------------------------------------------------------
    // MFM stream
    // -------------------------------------------------------------------------

    // Buffer sized for the longest track (protection long tracks); the length of the
    // current track is trackLenBytes (where the rotation wraps).
    private final byte[] trackBuffer          = new byte[DiskImage.MAX_TRACK_BYTES];
    private       int    trackLenBytes        = TRACK_BUFFER_SIZE;
    // EXACT length of the track in bits (IPF long tracks are not multiples of 8): the
    // rotation wraps at this value, not at trackLenBytes*8.
    private       int    trackBitLen          = TRACK_BUFFER_SIZE * 8;

    // -- Cell density model ----------------------------------------------------
    // On an IPF the cell length is
    //
    //     trackspeed = nominal cell * tracklen / nominal track length
    //
    // so the cell stays at the nominal DD rate and a LONGER track makes the revolution
    // longer: the INDEX pulse comes from the bitstream wrapping, so a long track has a
    // "revolution" longer than 200 ms. That is how "long track" protections (e.g. North &amp;
    // South, custom sync $A144) measure the track length against the index pulse.
    //
    // The opposite model (constant 200 ms revolution, shorter cells on a long track) fed
    // Dragon's Lair's long tracks (107044 cells against the nominal 101344, +5.6%) at a rate
    // 11% off, and its decompressor received a corrupt stream.
    //
    // On VARIABLE density tracks (Copylock) the scaling above does not apply: the base stays
    // nominal and all the timing comes from the per-byte map.
    //
    // ADF tracks bypass the model: the fixed nominal DD rate (CYCLES_PER_BIT, 7 CCK per
    // cell), at no cost.
    private       boolean densityModel        = false;
    // Cell duration per track byte, in 1/256 CCK (IPF tracks with a density map, e.g.
    // Copylock); -1 keeps the previous cell's duration; null = constant trackSpeed.
    private       int[]   cellSpeed           = null;
    // Duration of the current cell on the IPF path, in 1/256 CCK.
    private       int     trackSpeed          = NOMINAL_CELL_UNITS;
    private       int    bitPointer           = 0;
    // Time accumulated towards the next cell on the IPF path, in 1/256 CCK.
    private       long   cellClock            = 0;
    private       long   bitCyclesAccumulatorFixed = 0;
    // The reader of this tick runs at 4 us per bit (see tick(int, IntConsumer, boolean)).
    private       boolean slowBits            = false;
    // The cell where the reader slips its jitter cells, -1 for none (see slipJitterCells).
    private       int    jitterSkipCell       = -1;
    /** The track under the head lies past the medium's formatted tracks: it reads as noise. */
    private       boolean unformattedTrack    = false;

    // -------------------------------------------------------------------------
    // Writing to the medium
    // -------------------------------------------------------------------------

    /**
     * Source of the MFM cells to write to the medium: Paula's write shift register,
     * queried once per cell (see Paula.nextWriteBit).
     */
    @FunctionalInterface
    public interface WriteBitSource {
        /** @return 0/1 = cell to write, {@code -2} = no data ready, {@code -1} = end. */
        int nextWriteBit();
    }

    /** Sentinels of {@link WriteBitSource#nextWriteBit()}. */
    private static final int WRITE_BIT_STALL = Paula.WRITE_BIT_STALL;
    private static final int WRITE_BIT_END   = Paula.WRITE_BIT_END;

    private WriteBitSource writeBitSource = null;

    /** true while the head writes the medium instead of reading it. */
    private boolean writing = false;

    /** true if trackBuffer holds written cells not yet flushed to the image. */
    private boolean trackModified = false;

    /** Keeps the non-AmigaDOS format warning from repeating at every track. */
    private boolean customFormatWarned = false;

    // -------------------------------------------------------------------------
    // Input line state
    // -------------------------------------------------------------------------

    private boolean selected     = false;
    private boolean prevSelected = false;
    private boolean prevStep     = false;
    private boolean lastStepDir  = false;

    // =========================================================================
    // Constructors
    // =========================================================================

    public FloppyDrive(String id, DriveType driveType) {
        this.id        = id;
        this.driveType = driveType;
        this.angleRng  = newAngleRng(id);
        this.readyTailRng = newDriveRng(id, 1);
        this.jitterRng = newDriveRng(id, 2);
        this.shiftRng = newDriveRng(id, 3);
        this.noiseRng = newDriveRng(id, 4);
        reset();
    }

    /** DF0 = DD_INT, the others DD_EXT. */
    public FloppyDrive(String id) {
        this(id, "DF0".equals(id) ? DriveType.DD_INT : DriveType.DD_EXT);
    }

    public void setConnected(boolean connected) {
        flushPending();
        this.connected = connected;
        statusDirty = true;
        final Runnable l = changeListener;   // again after the change: it may come from another thread
        if (l != null) l.run();
    }

    /** true if the drive is physically connected to the bus (DF0 always; DF1-DF3 optional). */
    public boolean isConnected() { return connected; }

    /** Drive identifier ("DF0".."DF3"). */
    public String getId() { return id; }

    // Cosmetic drive sound (click/spin/startup). Null = no sound, at no cost.
    // Attached only by whoever wants it (currently Amija); see FloppySound.
    private FloppySound floppySound;
    public void setFloppySound(FloppySound sound) { this.floppySound = sound; }

    // =========================================================================
    // Main tick
    // =========================================================================

    public void tick(int cycles) {
        tick(cycles, null);
    }

    public void tick(int cycles, IntConsumer bitConsumer) {
        tick(cycles, bitConsumer, false);
    }

    /**
     * @param slowBits the disk controller runs at 4 us per bit (ADKCON FAST clear): each
     *                 bit delivered to {@code bitConsumer} is decoded from two cells and
     *                 the head moves on by two or three (see {@link #readSlowBit}).
     */
    public void tick(int cycles, IntConsumer bitConsumer, boolean slowBits) {
        machineRunning = true;   // see insertDisk: tells cold from hot

        // Deferred work: until the next event (see cyclesToNextEvent) a call only adds up
        // counters, so the cycles are banked and applied when an event is due. totalCycles
        // stays exact at every call: isBusy() and the STEP guard read it.
        final int banked = pendingCycles;
        if (banked + cycles < eventCycles) {
            pendingCycles = banked + cycles;
            totalCycles += cycles;
            return;
        }
        // The banked cycles hold no event, so they are applied as one quiet step, at the
        // time base of the call they ended in; then this call's cycles on their own,
        // exactly as a call per colour clock would see them (the INDEX pulse, for one,
        // counts the whole of the call it starts in).
        pendingCycles = 0;
        if (banked > 0) advance(banked, null);
        this.slowBits = slowBits;
        totalCycles += cycles;
        advance(cycles, bitConsumer);
        eventCycles = cyclesToNextEvent();
    }

    /**
     * Applies the banked cycles at once, before anything else reads or changes the drive.
     * By construction they hold no event, so no cell reaches the bit consumer.
     */
    private void flushPending() {
        final int banked = pendingCycles;
        if (banked > 0) {
            pendingCycles = 0;
            totalCycles -= banked;      // advance() runs at the time base of the banked span
            advance(banked, null);
            totalCycles += banked;
        }
        eventCycles = 0;                // the next call runs in full and looks again
        final Runnable l = changeListener;
        if (l != null) l.run();
    }

    /** Cycles that can still be banked before the next event (see {@link #tick}). */
    public int cyclesUntilEvent() {
        return eventCycles - pendingCycles;
    }

    /**
     * Told whenever the drive changes from outside its own clock (a control line, a disk, a
     * reset): whoever banks colour clocks of drive work must look at the drive again.
     */
    public void setChangeListener(Runnable listener) { this.changeListener = listener; }

    private Runnable changeListener;

    /** Cycles that can be banked before the next event: a sentinel when nothing is due. */
    private static final int NO_EVENT_CYCLES = 1 << 20;

    /**
     * Cycles from now to the first call that can change something the machine sees: the
     * next MFM cell under the head, the end of the INDEX pulse, the end of a settle, the
     * motor's spin-down ending. Until then a call only adds up counters.
     */
    private int cyclesToNextEvent() {
        final boolean dormant = !motorTarget && !motorAtSpeed && settleCounter <= 0
                && (totalCycles - switchCycle) >= CYCLES_MOTOR_STOP;
        if (dormant) return NO_EVENT_CYCLES;   // only the disk image's own flush timer runs
        if (!motorTarget && (motorAtSpeed || indexPulse)) return 0;
        long next = NO_EVENT_CYCLES;
        if (motorTarget) {
            if (densityModel) {
                next = (trackSpeed - cellClock + UNITS_PER_MASTER_CYCLE - 1) / UNITS_PER_MASTER_CYCLE;
            } else {
                next = (ADF_CYCLES_PER_BIT_FIXED - bitCyclesAccumulatorFixed + BIT_CLOCK_SCALE - 1)
                        / BIT_CLOCK_SCALE;
            }
            if (indexPulse) next = Math.min(next, CYCLES_INDEX_PULSE - indexPulseCounter);
        } else {
            next = Math.min(next, CYCLES_MOTOR_STOP - (totalCycles - switchCycle));
        }
        if (settleCounter > 0) next = Math.min(next, settleCounter);
        return (int) Math.max(0, Math.min(next, NO_EVENT_CYCLES));
    }

    /** One step of the drive: what a call of {@link #tick} does once its cycles are due. */
    private void advance(int cycles, IntConsumer bitConsumer) {
        // Periodic flush of the disk image (cheap: returns at once when no track is
        // dirty, the normal case for a read-only game disk).
        if (diskImage != null) diskImage.tickCycles(cycles);

        // PERF: dormant fast path. A drive whose motor stopped long ago and with no seek
        // in progress has nothing to do: the body below would only clear fields already
        // clear. It covers the empty df1-df3 and df0 with its motor off and a disk inside
        // (the usual state in a game after loading). The /RDY spin-up does not depend on
        // this path: hsync() counts it.
        if (!motorTarget && !motorAtSpeed && settleCounter <= 0
                && (totalCycles - switchCycle) >= CYCLES_MOTOR_STOP) {
            return;
        }

        // PERF (see statusDirty): outside the steady "motor up to speed" state the lines
        // can change with time alone, /RDY when the ramp completes and /INDEX at every
        // revolution, so the flag stays raised.
        if (!(motorTarget && motorAtSpeed)) statusDirty = true;

        // /RDY spin-up is counted in lines by hsync(). The motor going off drops
        // DSKRDY at once; an empty drive never asserts it (isReadyRaw).
        if (!motorTarget && motorAtSpeed) {
            motorAtSpeed = false;
        }

        // Platter rotation: the platter turns, and the head reads, as soon as the motor is
        // COMMANDED on, without waiting for full speed. The speed ramp governs /RDY only
        // (isReadyRaw), not the data stream. INDEX comes from bitPointer wrapping (see
        // advanceBitPointer), so the index hole and bit 0 of the track are the same
        // angular position.
        if (motorTarget) {
            if (densityModel) {
                tickMfmBitsDensityModel(cycles, bitConsumer);
            } else {
                tickMfmBitsAdf(cycles, bitConsumer);
            }

            if (indexPulse) {
                indexPulseCounter += cycles;
                if (indexPulseCounter >= CYCLES_INDEX_PULSE) {
                    indexPulse  = false;
                    statusDirty = true;   // end of the INDEX pulse
                }
            }
        } else {
            // A stopped motor freezes the platter, fraction of cell included: it
            // resumes from the same phase at the next motor-on.
            if (indexPulse) {
                indexPulse  = false;
                statusDirty = true;
            }
        }

        // Settle after a step: the seek is already applied at the STEP pulse (see
        // executeStep); the drive only stays busy until it elapses.
        if (settleCounter > 0) {
            settleCounter -= cycles;
            if (settleCounter <= 0) settleCounter = 0;
        }
    }

    // =========================================================================
    // Line updates (called by Agnus/CIA on every DMA cycle or event)
    // =========================================================================

    /**
     * CIA-B reset: the drive takes the control lines as they stand, without acting on
     * their edges. A /SEL that reads active here latches no motor and shifts no ID bit;
     * the next {@link #update} compares against this state.
     */
    public void selectSet(boolean selected, boolean step) {
        flushPending();
        statusDirty  = true;
        this.selected = connected && selected;
        prevSelected = this.selected;
        prevStep     = step;
    }

    public void update(boolean selected, boolean motorOn, boolean step,
                       boolean direction, boolean side) {
        flushPending();
        statusDirty = true;   // called only when CIA-B PRB has changed

        // /STEP is active LOW and the head moves on its trailing edge (asserted -> released),
        // in a drive that was selected before this write, toward the direction now on DIR.
        // A pulse too close to the previous ACCEPTED one is IGNORED: the head cannot follow.
        // Only accepted pulses restart the 140 CCK guard.
        if (prevStep && !step && prevSelected) {
            if (totalCycles - lastAcceptedStepCycle >= CYCLES_MIN_STEP_INTERVAL) {
                lastAcceptedStepCycle = totalCycles;
                executeStep(direction);
            }
        }

        if (!connected) {
            this.selected = false;
            prevSelected = false;
            prevStep = step;
            return;
        }

        // /SIDE acts at once, on every drive: the line is common to all of them and the head
        // is chosen electronically, with no settle, so the bits that follow the write already
        // come from the new side. A drive that is not selected changes head too, and its
        // position is rescaled to the new track as for a selected one.
        if (this.currentSide != side) {
            flushWrittenTrack();   // the track written so far belongs to the old side
            currentSide = side;
            if (diskInserted && diskImage != null) loadCurrentTrack();
        }

        if (!selected) {
            this.selected = false;
            prevSelected = false;
            prevStep = step;
            return;
        }

        boolean selFallingEdge = selected && !prevSelected;

        if (selFallingEdge) {
            boolean oldMotorTarget = motorTarget;
            setMotor(motorOn);
            if (!oldMotorTarget && motorOn) slipShifter();

            if (oldMotorTarget || motorOn) {
                // Motor transition or motor already on: clear the ID shift register
                idBitIndex = 0;
                idBit      = false; // /RDY inactive (high)
                statusDirty = true;
            } else {
                // Motor off: shift out the next ID bit
                updateIdBit();
                if (idBitIndex < ID_SEQUENCE_BITS) idBitIndex++;
            }
        }

        this.selected = selected;

        prevSelected = selected;
        prevStep     = step;
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    /** Updates the ID bit shown on /RDY while the motor is off. */
    private void updateIdBit() {
        statusDirty = true;   // /RDY carries the ID bitstream
        if (!connected) {
            idBit = false;
            return;
        }
        idBitIndex &= (ID_SEQUENCE_BITS - 1);
        idBit = ((driveType.id >>> (31 - idBitIndex)) & 1) != 0;
    }

    /**
     * PERF: whether the drive needs per-cycle work (tick + update). It is busy while
     * selected, with the motor on or still spinning down, or during a seek. When NO drive
     * is busy and the CIA-B control lines do not change, Agnus skips the floppy work
     * altogether. Comparisons only.
     */
    public boolean isBusy() {
        return selected || writing || motorTarget || motorAtSpeed || settleCounter > 0
                || (totalCycles - switchCycle) < CYCLES_MOTOR_STOP;
    }

    /**
     * The /RDY spin-up delay loaded at a motor-on: the fixed base plus a tail of 0..511
     * scanlines, drawn anew at every motor-on. The tail is not noise: a loader that cycles
     * the motor between tracks pays it every time, about ten frames over a long load, and
     * a program that builds its effects while loading can take a different branch without
     * it. {@code -Damiga.dskreadyfix=<lines>} puts the same value on every motor-on instead;
     * like {@code amiga.diskangle}, it lines this machine up with a reference run, and the
     * test suite sets it to 0.
     */
    private int readyUpDelay() {
        Integer fixed = Integer.getInteger("amiga.dskreadyfix");
        int tail = fixed != null ? fixed & 511 : readyTailRng.nextInt(512);
        return DSKREADY_UP_LINES + tail;
    }

    /**
     * Horizontal sync, once per line at the point where the line counter advances.
     * The /RDY spin-up counts these, not master-cycles: after a motor-on DSKRDY rises
     * on the N-th line boundary that follows, so where the motor-on fell inside its
     * line does not move it. The count stands still while no disk is inserted.
     */
    public void hsync() {
        if (readyUpLines > 0 && diskInserted && --readyUpLines == 0 && motorTarget) {
            motorAtSpeed = true;
            statusDirty = true;
            final Runnable l = changeListener;
            if (l != null) l.run();
        }
    }

    private void setMotor(boolean value) {
        if (motorTarget == value) return;
        statusDirty = true;
        switchCycle = totalCycles;
        motorTarget = value;
        // /RDY: the spin-up delay is reloaded WHOLE on every motor-on, and
        // DSKRDY drops the instant the motor is commanded off.
        motorAtSpeed  = false;
        readyUpLines  = value ? readyUpDelay() : 0;
        if (floppySound != null) floppySound.onMotor(value, diskInserted);
    }

    /**
     * A motor switched on, or a head step, jolts the data line of the selected drive: a
     * few bits of noise enter Paula's read shift register outside the bit clock, which
     * moves the word boundary of the next read. One to eight bits, each 0 or 1 at random.
     * {@code -Damiga.randshift=<n>} pushes n bits of 1 instead, and 0 turns it off; the
     * test suite sets 0.
     */
    private void slipShifter() {
        if (shifterSlipSink == null || RANDSHIFT_FIXED == 0) return;
        if (RANDSHIFT_FIXED > 0) {
            for (int i = 0; i < RANDSHIFT_FIXED; i++) shifterSlipSink.accept(1);
            return;
        }
        int bits = 1 + shiftRng.nextInt(8);
        for (int i = 0; i < bits; i++) shifterSlipSink.accept(shiftRng.nextInt(2));
    }

    /** Receives the bits of {@link #slipShifter()}: Paula's read shift register. */
    public void setShifterSlipSink(IntConsumer sink) { this.shifterSlipSink = sink; }

    private IntConsumer shifterSlipSink;

    // -1 = random, 0 = off, n = n bits of 1 (see slipShifter).
    private static final int RANDSHIFT_FIXED = Integer.getInteger("amiga.randshift", -1);

    private void executeStep(boolean direction) {
        statusDirty = true;   // /TK0 and /CHNG change at a step
        long waitTime = (direction == lastStepDir) ? CYCLES_SETTLE_STEP : CYCLES_SETTLE_DIR_CHG;
        lastStepDir = direction;

        // HRM: "it is reset by a STEP pulse when there is a disk in the drive."
        if (diskInserted) dskchange = false;

        // HRM: DIR 0 = Inward (+1), 1 = Outward (-1)
        int move = direction ? -1 : 1;
        targetTrack = Math.max(0, Math.min(MAX_TRACKS - 1, targetTrack + move));

        // The head moves by one track AT the STEP pulse: currentTrack and the buffer are
        // updated at once. The settle below is only the mechanical settling that keeps the
        // drive busy; it does not delay which track is readable. A loader that rereads the
        // header right after a step to check the position (Speedball II: recalibrate, step
        // to cyl 1) must see the new cylinder, or it steps again forever.
        if (currentTrack != targetTrack) {
            flushWrittenTrack();   // the written track belongs to the OLD cylinder
            currentTrack = targetTrack;
            if (diskInserted && diskImage != null) loadCurrentTrack();
        }

        // Math.max keeps a burst of steps from shortening the settle
        settleCounter = Math.max(settleCounter, waitTime);

        if (floppySound != null) floppySound.onStep();
        slipShifter();
    }

    private void tickMfmBitsAdf(int cycles, IntConsumer bitConsumer) {
        bitCyclesAccumulatorFixed += (long) cycles * BIT_CLOCK_SCALE;
        while (bitCyclesAccumulatorFixed >= ADF_CYCLES_PER_BIT_FIXED) {
            bitCyclesAccumulatorFixed -= ADF_CYCLES_PER_BIT_FIXED;
            processCell(bitConsumer);
        }
    }

    /**
     * The IPF cell clock, in units of 1/256 CCK. A cell is taken once the time accumulated
     * reaches the duration of the cell before it; the cell then costs its own duration,
     * which the density map gives at the head's position. Passing the index hole loads
     * the duration of the first cell.
     */
    private void tickMfmBitsDensityModel(int cycles, IntConsumer bitConsumer) {
        cellClock += (long) cycles * UNITS_PER_MASTER_CYCLE;
        while (cellClock >= trackSpeed) {
            updateTrackSpeed();
            processCell(bitConsumer);
            if (bitPointer == 0) updateTrackSpeed();
            cellClock -= trackSpeed;
        }
    }

    /**
     * An MFM cell passes under the head: it is read (and handed to Paula) or, when
     * writing, overwritten with the cell Paula provides.
     */
    private void processCell(IntConsumer bitConsumer) {
        if (writing) {
            int bit = (writeBitSource != null) ? writeBitSource.nextWriteBit() : WRITE_BIT_END;
            if (bit == WRITE_BIT_END) {
                // Transfer over: the written track is flushed to the image.
                setWriting(false);
                if (bitConsumer != null) bitConsumer.accept(getNextMFMBit());
            } else if (bit != WRITE_BIT_STALL) {
                writeCellToTrack(bit);
            }
            // WRITE_BIT_STALL: the cell passes unaltered (the DMA has not supplied the
            // next word yet).
        } else if (bitConsumer != null) {
            if (slowBits) {
                readSlowBit(bitConsumer);
                slipJitterCells();
                return;
            }
            bitConsumer.accept(getNextMFMBit());
            advanceBitPointer();
            slipJitterCells();
            return;
        }
        advanceBitPointer();
    }

    /**
     * Speed jitter of a real drive: once per revolution, when the head of the drive being
     * read reaches {@link #jitterSkipCell}, it slips a few cells that are never read. How
     * many is drawn anew every time, between {@code min} and {@code max} of
     * {@code -Damiga.diskjitter=<min>:<max>} (1:3 by default; the test suite sets 0:0).
     */
    private void slipJitterCells() {
        if (bitPointer != jitterSkipCell || JITTER_MAX <= 0) return;
        int cells = JITTER_MIN + (JITTER_MAX > JITTER_MIN
                ? jitterRng.nextInt(JITTER_MAX - JITTER_MIN + 1) : 0);
        for (int i = 0; i < cells; i++) advanceBitPointer();
    }

    private static final int JITTER_MIN;
    private static final int JITTER_MAX;
    static {
        int min = 1, max = 3;
        String p = System.getProperty("amiga.diskjitter");
        if (p != null) {
            String[] mm = p.split(":", 2);
            try {
                min = Integer.parseInt(mm[0].trim());
                max = mm.length > 1 ? Integer.parseInt(mm[1].trim()) : min;
            } catch (NumberFormatException e) {
                System.err.println("[DISK] amiga.diskjitter non valido: " + p + ", uso 1:3");
                min = 1; max = 3;
            }
        }
        JITTER_MIN = Math.max(0, min);
        JITTER_MAX = Math.max(JITTER_MIN, max);
    }

    /**
     * One bit time of a controller at 4 us per bit reading a 2 us track: the bit is
     * decoded from the cell under the head and the next one, and the head moves on by
     * three cells after a 1 that ends in a set cell, by two otherwise.
     * <pre>
     *   cells 0 0 -> bit 0, +2     cells 1 0 -> bit 1, +2
     *   cells 0 1 -> bit 1, +3     cells 1 1 -> bit 1, +3
     * </pre>
     */
    private void readSlowBit(IntConsumer bitConsumer) {
        if (unformattedTrack) {
            bitConsumer.accept(getNextMFMBit());
            advanceBitPointer();
            advanceBitPointer();
            return;
        }
        int b1 = getNextMFMBit();
        int b2 = mfmBitAt(bitPointer + 1 < trackBitLen ? bitPointer + 1 : 0);
        bitConsumer.accept(b1 | b2);
        int cells = b2 != 0 ? 3 : 2;
        for (int i = 0; i < cells; i++) advanceBitPointer();
    }

    /** Writes an MFM cell to the medium at the current angular position. */
    private void writeCellToTrack(int bit) {
        if (!diskInserted || writeProtected) return;   // no medium / tab closed
        int byteIdx = bitPointer >> 3;
        if (byteIdx >= trackBuffer.length) return;
        int mask = 1 << (7 - (bitPointer & 7));
        if (bit != 0) trackBuffer[byteIdx] |= (byte) mask;
        else          trackBuffer[byteIdx] &= (byte) ~mask;
        trackModified = true;
    }

    /**
     * Flushes the cells written on the current track to the disk image, decoding the
     * AmigaDOS sectors (see DiskImage.writeTrackMFM). Called BEFORE every track or side
     * change and at the end of a write.
     */
    private void flushWrittenTrack() {
        if (!trackModified) return;
        trackModified = false;
        if (diskImage == null || diskImage.isReadOnly()) return;
        int physicalTrack = currentTrack * 2 + (currentSide ? 1 : 0);
        int sectors = diskImage.writeTrackMFM(physicalTrack, trackBuffer, trackLenBytes);
        if (sectors == 0 && !customFormatWarned) {
            // An ADF holds standard AmigaDOS sectors only: data written in a format of
            // the program's own (some games' user disks) cannot be represented. Reported
            // once, so the loss is not silent.
            customFormatWarned = true;
            System.err.printf("[%s] traccia %d scritta in un formato non AmigaDOS: "
                    + "non e' rappresentabile in un file .adf e non viene salvata%n",
                    id, physicalTrack);
        }
    }

    /**
     * Enters or leaves write mode. On leaving, the written track is decoded into the image
     * (the .adf file is then updated by DiskImage's periodic flush, the eject or the
     * shutdown hook).
     */
    public void setWriting(boolean value) {
        flushPending();
        if (writing == value) return;
        writing = value;
        if (!value) flushWrittenTrack();
    }

    public boolean isWriting() { return writing; }

    public void setWriteBitSource(WriteBitSource source) { this.writeBitSource = source; }

    /**
     * On a track with a density map, the duration of the cell under the head (outside a
     * write, which keeps the speed it found). An implausible map entry keeps the previous
     * duration.
     */
    private void updateTrackSpeed() {
        if (cellSpeed == null || writing) return;
        int idx = bitPointer >> 3;
        int ts = cellSpeed[idx < cellSpeed.length ? idx : cellSpeed.length - 1];
        if (ts > 0) trackSpeed = ts;
    }

    /**
     * Moves the head on by one cell. Wrapping at the end of the track is the index hole
     * passing: the pulse starts there, because the hole and bit 0 of the track are the same
     * angular position on the medium. Protections that read "the first sync after INDEX"
     * (e.g. the Rob Northen Copylock of Castle Master) depend on it.
     */
    private void advanceBitPointer() {
        if (++bitPointer >= trackBitLen) {
            bitPointer        = 0;
            indexPulse        = true;
            indexPulseCounter = 0;
            statusDirty       = true;   // start of the INDEX pulse
        }
    }

    private void resetBitClock() {
        cellClock = 0;
        bitCyclesAccumulatorFixed = 0;
    }

    /**
     * Disk DMA start, on every drive: the time accumulated towards the next cell is
     * dropped, so the next cell is counted from this instant.
     */
    public void restartBitCell() {
        flushPending();
        resetBitClock();
    }

    // =========================================================================
    // Output lines
    // =========================================================================

    /**
     * Raw /RDY. What decides it is the motor <b>flip-flop</b>, never the platter speed:
     *
     * <pre>
     *   motor ON   -> /RDY = DSKRDY, which rises at the end of the spin-up, and only with a disk
     *   motor OFF  -> /RDY = the drive's ID bit (DSKRDY has already dropped)
     * </pre>
     *
     * <p>DSKRDY drops <b>at once</b> when the flip-flop turns off. There is no spin-down
     * grace: the platter keeps turning, and the head reading (see {@link #tick}), but /RDY
     * does not. The Kickstart reads the drive ID by switching the motor on, off again at
     * once, and then pulsing /SEL 32 times while reading /RDY, all during the spin-down: a
     * grace period would make DF0 with a disk inside announce a spurious ID ($E0000000), and
     * trackdisk 2.0+ would never mount it.
     *
     * <p>DF0 of the A500 has no ID hardware: {@link DriveType#DD_INT} is $00000000, so
     * {@code idBit} stays false and the line reads zeros, which is what trackdisk expects
     * from a standard Amiga drive. DF0 of the A1200 reports the DD ID like an external one.
     */
    public boolean isReadyRaw() {
        if (!connected) return false;
        if (motorTarget) {
            // Motor commanded on: /RDY is DSKRDY, which rises when the spin-up countdown
            // expires, and only with a disk present.
            return motorAtSpeed && diskInserted;
        }
        // Motor commanded off: DSKRDY has dropped, the line carries the ID bit.
        return idBit;
    }

    // =========================================================================
    // PERF: invalidation of the status lines seen by the bus
    // =========================================================================
    //
    // The five lines the drive drives onto the CIA pins (/RDY, /TK0, /WPRO, /CHNG,
    // /INDEX) would otherwise be resampled and republished by AbstractAgnus.updateFloppy
    // at EVERY CCK of an active drive. With the motor up to speed during a load they stay
    // constant for millions of cycles. This flag says when one of them MAY have changed,
    // so the caller skips the work when nothing did.
    //
    // The rule is CONSERVATIVE: when in doubt the flag stays raised. In particular it stays
    // raised through the whole motor ramp (spin-up and spin-down), where /RDY and /INDEX
    // depend on elapsed time (motorAtSpeed and the wrap of bitPointer change inside tick()).
    // Only the steady states are exploited: motor up to speed, or stopped long ago.
    private boolean statusDirty = true;

    /** True if one of the five status lines may have changed since the last publication. */
    public boolean isStatusDirty() { return statusDirty; }

    /** Called by Agnus after publishing the lines on the CIA pins. */
    public void clearStatusDirty() { statusDirty = false; }

    /** Forces the lines to be republished next time. */
    public void markStatusDirty() { statusDirty = true; }

    public boolean isTrack0Raw()         { return connected && (targetTrack == 0); }
    /**
     * /WPROT as the bus sees it, without the selection gate.
     *
     * <p><b>An EMPTY drive reports write protected.</b> /WPROT is a <b>wired</b> line and ONE
     * selected drive is enough to pull it low: with several drives selected together, an
     * empty one protects the line even when the one with the disk would not. Measured on
     * {@code Paula/Drive/step5}, which selects all four drives while df0 holds a writable
     * disk and df1 is empty.
     */
    public boolean isWriteProtectedRaw() { return !diskInserted || writeProtected; }
    public boolean isDiskChangedRaw()    { return connected && dskchange; }
    public boolean isIndexActiveRaw()    { return connected && indexPulse; }

    public boolean isSelected()       { return selected; }
    /** Current physical track (cylinder*2 + side). Diagnostics. */
    public int getPhysicalTrack()     { return currentTrack * 2 + (currentSide ? 1 : 0); }
    public boolean isReady()          { return selected && isReadyRaw(); }
    public boolean isTrack0()         { return selected && isTrack0Raw(); }
    public boolean isWriteProtected() { return selected && isWriteProtectedRaw(); }
    public boolean isDiskChanged()    { return selected && isDiskChangedRaw(); }
    public boolean isIndexActive()    { return selected && isIndexActiveRaw(); }

    public int     getCurrentTrack()  { return currentTrack; }
    public int     getTargetTrack()   { return targetTrack; }

    /** Current angular position of the head, in cells from the INDEX pulse. */
    public int getBitPointer()  { return bitPointer; }
    /** Length of the current track in cells (one full revolution). */
    public int getTrackBitLen() { return trackBitLen; }

    public boolean isDiskInserted()   { return diskInserted; }
    public boolean isMotorAtSpeed()   { return motorAtSpeed; }
    public boolean isMotorRunning()   { return motorTarget; }
    public DiskImage getDiskImage()   { return diskImage; }

    // =========================================================================
    // MFM stream
    // =========================================================================

    public int getNextMFMBit() {
        // No gate on motorAtSpeed: the head reads the medium as soon as the motor is on.
        // The speed ramp is modelled on /RDY only.
        if (unformattedTrack && motorTarget && diskInserted) return noiseRng.nextInt(2);
        return mfmBitAt(bitPointer);
    }

    private int mfmBitAt(int pos) {
        if (!motorTarget || !diskInserted) return 0;
        int byteIdx   = pos >> 3;
        if (byteIdx >= trackBuffer.length) return 0;
        int bitInByte = 7 - (pos & 7);
        return (trackBuffer[byteIdx] >> bitInByte) & 1;
    }

    // =========================================================================
    // Track handling
    // =========================================================================

    /** Loads the track's MFM data, standard length (bytes x 8). */
    public void loadTrackData(byte[] data) {
        loadTrackData(data, data == null ? 0 : data.length * 8);
    }

    /**
     * Loads the track's MFM data with its EXACT length in bits. The angular position of
     * the head is kept (rescaled to the new track length): changing track or side does
     * not move the medium.
     *
     * @param data      MFM bitstream (bytes) of the track
     * @param trackBits exact track length in bits (not necessarily a multiple of 8, for
     *                  IPF protection long tracks)
     */
    public void loadTrackData(byte[] data, int trackBits) {
        loadTrackData(data, trackBits, null);
    }

    /**
     * As above, with the track's per-byte density map (1000 = nominal cell length;
     * {@code null} = uniform density).
     */
    public void loadTrackData(byte[] data, int trackBits, int[] density) {
        flushPending();
        jitterSkipCell = -1;
        unformattedTrack = false;
        // Length of the current track: standard for ADF and normal IPF tracks, longer for
        // protection long tracks (up to MAX_TRACK_BYTES).
        int len = (data == null) ? TRACK_BUFFER_SIZE
                                 : Math.min(data.length, trackBuffer.length);
        if (len <= 0) len = TRACK_BUFFER_SIZE;
        trackLenBytes = len;
        int oldBitLen = trackBitLen;
        trackBitLen   = (trackBits > 0 && trackBits <= len * 8) ? trackBits : len * 8;

        // Cell durations are whole units of 1/256 CCK, truncated where they are derived:
        // the base is the nominal cell scaled by the track length (not on a track with a
        // density map), doubled on a track short enough to be a 4 us one; a density map
        // gives each byte base * density / 1000.
        boolean variableDensity = densityModel && density != null && density.length > 0;
        int base = NOMINAL_CELL_UNITS;
        if (densityModel && trackBitLen > 0 && !variableDensity) {
            base = (int) ((long) base * trackBitLen / NOMINAL_TRACK_BITS);
        }
        if (densityModel && trackBitLen < FOUR_US_TRACK_BITS) base *= 2;
        trackSpeed = Math.max(1, base);
        if (variableDensity) {
            cellSpeed = new int[density.length];
            for (int i = 0; i < density.length; i++) {
                int ts = (int) ((long) trackSpeed * density[i] / 1000);
                // An implausible entry keeps the previous cell's speed.
                cellSpeed[i] = (ts < 700 || ts > 3000) ? -1 : ts;
            }
        } else {
            cellSpeed = null;
        }
        // The cell phase is not touched either: the platter keeps turning while the
        // head steps or the side changes (only a disk DMA start re-phases it, see
        // restartBitCell).

        // A track or side change does NOT move the medium: the head stays at the same
        // angle. When the new track has a different length (protection long track) the
        // position is rescaled in proportion, not cleared.
        bitPointer = (oldBitLen > 0)
                ? (int) ((long) bitPointer * trackBitLen / oldBitLen) % trackBitLen
                : 0;

        if (data == null) {
            Arrays.fill(trackBuffer, (byte) 0);
            return;
        }
        System.arraycopy(data, 0, trackBuffer, 0, len);
        // clear the unused tail of the buffer (left by a longer previous track)
        if (len < trackBuffer.length)
            Arrays.fill(trackBuffer, len, trackBuffer.length, (byte) 0);
    }

    /** Reloads the MFM buffer with the current track and side from the disk image. */
    private void loadCurrentTrack() {
        if (diskImage == null) return;
        int physicalTrack = currentTrack * 2 + (currentSide ? 1 : 0);
        loadTrackData(diskImage.readTrackMFM(physicalTrack),
                      diskImage.getTrackBits(physicalTrack),
                      diskImage.getTrackDensity(physicalTrack));
        jitterSkipCell = diskImage.getJitterSkipCell(physicalTrack);
        unformattedTrack = diskImage.isUnformatted(physicalTrack);
    }

    // =========================================================================
    // Disk insert / eject
    // =========================================================================

    /**
     * Inserts a file-backed disk image, closing and replacing the previous one. Passing
     * {@code null} is the same as {@link #ejectDisk()}.
     *
     * <p><b>A disk already inside at power-on is NOT a change.</b> {@code /CHNG} is a latch
     * that goes low at every insertion or removal <i>while the machine runs</i>; a disk in
     * the drive since before power-on has nothing to latch.
     *
     * <p><b>A hot insert does assert it</b>: the HRM says "/CHNG goes low on every insertion
     * or removal, and is reset by a STEP pulse when there is a disk in the drive", and the
     * detection of a disk inserted while running relies on it (our ROM,
     * {@code rom/floppy.asm} {@code probeDisk}, and games asking for a disk change). The
     * line between the two is {@link #machineRunning}, which turns true at the first
     * {@link #tick}.
     *
     * <p>Asserting it <b>at a cold insert too</b> cost <b>11 102 CCK</b> of boot: trackdisk
     * saw a pending change and recalibrated the head <b>before</b> switching the motor on,
     * with its 3 ms settles (docs/vamigats-paula-audio-plan.md section 7.1.5).
     */
    public void insertDisk(DiskImage image) {
        flushPending();
        statusDirty = true;
        writing = false;
        flushWrittenTrack();   // save what was written on the outgoing disk
        closeDiskImage();
        diskImage      = image;
        diskInserted   = (image != null);
        writeProtected = (image != null) && image.isReadOnly();
        // Hot, every insertion or removal latches; cold (configuration), none does.
        dskchange      = machineRunning || (image == null);
        // The cell density model applies to IPF only; ADF is bypassed (fixed rate).
        densityModel   = (image != null) && image.getFormat() == DiskImage.Format.IPF;
        if (diskInserted) { loadCurrentTrack(); randomiseAngularPosition(); }
        // A disk dropped into a spinning drive restarts the /RDY delay.
        if (motorTarget) { readyUpLines = readyUpDelay(); motorAtSpeed = false; }
        if (floppySound != null) floppySound.onDiskChange(diskInserted);
    }

    /**
     * Angular position of the platter at an insert: RANDOM.
     *
     * <p>A real drive's motor stops wherever it happens to, and a disk goes in at any
     * orientation: the angle the first read starts from bears no relation to the beam.
     * Starting ALWAYS from bit 0 made every boot read the disk from the same angle, so every
     * read ended at the very same instant of the frame. That is what broke Pinball Dreams
     * from floppy: the bootblock load always ended at vpos 22 and the hand-over to the game
     * at vpos 312, the one point where the VBL crosses the ~15-instruction blind window of
     * its takeover, so it took VERTB in the Kickstart's handler with SysBase already
     * destroyed, and crashed at every boot.
     *
     * <p>Determinism: tests comparing frames or CRCs must reproduce the same run, so the
     * seed can be fixed with {@code -Damiga.diskseed=<n>}. {@code -Damiga.diskangle=<bit>}
     * <b>fixes the position</b> instead of drawing it, to put a run at the same point of the
     * platter as a reference run: the angle alone moves the instant the bootblock is ready
     * by <b>+-8000 CCK</b> (docs/vamigats-paula-audio-plan.md section 6.3). The unit is
     * cells on the MFM track, 101344 per revolution.
     */
    private void randomiseAngularPosition() {
        if (trackBitLen > 0) {
            Long fixed = Long.getLong("amiga.diskangle");
            bitPointer = fixed != null
                    ? (int) Math.floorMod(fixed, trackBitLen)
                    : angleRng.nextInt(trackBitLen);
        }
        resetBitClock();
    }

    /**
     * <b>The generator is PER DRIVE, not static.</b> With one {@code Random} shared by the
     * whole JVM, the starting angle of a test would depend on how many tests ran before
     * it. With one per drive the sequence <b>restarts with the machine</b>: every test gets
     * exactly the angle it gets when run alone. The seed is fixed with
     * {@code -Damiga.diskseed=<n>} (the {@code pom.xml} sets 0 for the tests); the offset by
     * {@code id} keeps two drives from getting the same platter, and
     * {@code String.hashCode()} is specified by the language, so it is reproducible.
     */
    private final java.util.Random angleRng;

    /**
     * The /RDY spin-up tail ({@link #readyUpDelay()}) has its own generator, seeded like
     * {@link #angleRng}, so the platter angle of an insert never depends on how many
     * motor-ons came before it.
     */
    private final java.util.Random readyTailRng;

    /** The bits of {@link #slipShifter()}, seeded like {@link #angleRng}. */
    private final java.util.Random shiftRng;

    /** The jitter draws ({@link #slipJitterCells()}), seeded like {@link #angleRng}. */
    private final java.util.Random jitterRng;

    /** The cells of an unformatted track, seeded like {@link #angleRng}. */
    private final java.util.Random noiseRng;

    private static java.util.Random newAngleRng(String id) {
        return newDriveRng(id, 0);
    }

    private static java.util.Random newDriveRng(String id, int salt) {
        Long seed = Long.getLong("amiga.diskseed");
        return seed == null ? new java.util.Random()
                            : new java.util.Random(seed * 31 + (id == null ? 0 : id.hashCode())
                                                   + salt * 0x9E3779B9L);
    }

    /**
     * Loads a disk file, building a {@link DiskImage} internally. Also accepts the form
     * {@code <archive>.zip!<n>} for the single disks of a multi-disk zip.
     */
    public void loadAdf(String adfPath, boolean writeProtected) throws IOException {
        if (adfPath == null) { ejectDisk(); return; }
        insertDisk(DiskImage.open(adfPath, writeProtected));
    }

    /** Physical eject: data flush, image closed, /CHNG latched. */
    public void ejectDisk() {
        flushPending();
        statusDirty = true;
        writing = false;
        flushWrittenTrack();
        closeDiskImage();
        densityModel = false;
        trackSpeed = NOMINAL_CELL_UNITS;
        cellSpeed = null;
        resetBitClock();
        if (diskInserted) {
            dskchange    = true;
            diskInserted = false;
            if (floppySound != null) floppySound.onDiskChange(false);
        }
    }

    /**
     * Simulates the physical presence or absence of a disk without a backing file, for
     * tests.
     */
    public void insertDisk(boolean inserted, boolean writeProtected) {
        flushPending();
        statusDirty = true;
        if (!inserted) {
            writing = false;
            flushWrittenTrack();
            closeDiskImage();
            densityModel = false;
            trackSpeed = NOMINAL_CELL_UNITS;
            cellSpeed = null;
            resetBitClock();
        }
        if (inserted != this.diskInserted) {
            dskchange            = true;
            diskInserted         = inserted;
            this.writeProtected  = inserted && writeProtected;
            if (floppySound != null) floppySound.onDiskChange(inserted);
        }
    }

    public void insertDisk(boolean inserted) { insertDisk(inserted, false); }

    private void closeDiskImage() {
        if (diskImage != null) {
            try { diskImage.close(); } catch (IOException ignored) {}
            diskImage = null;
        }
    }

    // =========================================================================
    // Reset
    // =========================================================================

    public void reset() {
        flushPending();
        statusDirty = true;
        // A reset in the middle of a write (e.g. Ctrl-Amiga-Amiga) must not lose the
        // track written so far: it is flushed to the image before clearing everything.
        writing = false;
        flushWrittenTrack();

        boolean hadDiskImage = diskImage != null && diskInserted;
        boolean wasWriteProtected = writeProtected;
        boolean wasDiskChanged = dskchange;

        motorTarget          = false;
        motorAtSpeed         = false;
        readyUpLines         = 0;
        // totalCycles is the drive's time base and keeps running: the STEP guard compares it
        // with the instant of the last accepted step, which a restart from 0 would put in the
        // future and reject every step for as long as the machine had run before the reset.
        switchCycle          = totalCycles;
        // currentTrack/targetTrack are kept: the head is mechanical and no reset moves it.
        // After a RESET the Kickstart finds it where it was and steps it back to track 0.
        settleCounter        = 0;
        currentSide          = false;
        dskchange            = hadDiskImage ? wasDiskChanged : true;
        diskInserted         = hadDiskImage;
        writeProtected       = hadDiskImage && wasWriteProtected;
        indexPulse           = false;
        indexPulseCounter    = 0;
        idBit                = false;
        idBitIndex           = 0;
        prevSelected         = false;
        prevStep             = false;
        lastStepDir          = false;
        // bitPointer is kept: it is the angular position of the platter, which no
        // reset signal can rewind: a program that reboots the machine with RESET finds
        // the platter where it was. See randomiseAngularPosition().
        resetBitClock();
        if (hadDiskImage) {
            loadCurrentTrack();
        } else {
            for (int i = 0; i < trackBuffer.length; i++) trackBuffer[i] = 0;
        }

        // reset() clears motorTarget directly (not through setMotor), so the sound's spin
        // loop is stopped explicitly.
        if (floppySound != null) floppySound.onMotor(false, diskInserted);
    }
}
