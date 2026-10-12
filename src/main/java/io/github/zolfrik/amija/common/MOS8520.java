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
    /**
     * Defect of the TODMED counter: see {@link #checkTodIrq(boolean)}.
     *
     * <p><b>On</b>, as on the A500, the machine the bank's references are made with. With the
     * defect, {@code CIA/TOD/todbug1} goes from 25 984 differing pixels to <b>112</b>,
     * {@code todbug3} from 33 784 to <b>84</b>, {@code todbug4} from 104 764 to <b>996</b>,
     * and {@code todvice/0alarm} and {@code 1alarm}, the two that expect a <b>double</b>
     * count of alarms, which only the defect gives them, turn <b>green</b>.
     *
     * <p>It depends on the TOD being stopped after a reset (see {@link #reset()}): with the
     * TOD counting from the reset, CIA-B's counter reached {@code $8017} during the boot,
     * went through {@code $001000} and the defect fired where it must not.
     * See {@code docs/vamigats-import-order.md}, {@code CIA/TOD}.
     */
    private boolean todBug = true;
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

    // -- Action flags (delay pipeline) ------------------------------------------
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

    // -- Diagnostics --------------------------------------------------------------
    public void setTodBug(boolean en) { this.todBug = en; }

    // -------------------------------------------------------------------------
    // Internal registers and state
    // -------------------------------------------------------------------------

    private long delay; // Delay pipeline
    private long feed;  // Flags fed into the pipeline at each tick

    private int counterA, counterB; // 16-bit timers
    private int latchA, latchB;     // 16-bit latches

    /**
     * Number of timer B underflows since the start. Read-only hook for the regression on
     * the period in cascade mode (see {@code CiaCascadeTimerTest} and the note on timer B in
     * {@link #tick()}).
     */
    public long diagTimerBUnderflows;
    private int cra, crb;           // Control registers

    private int imr;    // Interrupt Mask Register (write only mask)
    private int icr;    // Interrupt Control Register (read state / write clear)
    private int icrAck; // ICR bits pending clear
    /**
     * Source bits of timer underflows counted by the CIA clock, raised in the current clock
     * and not yet readable. They reach {@link #icr} one clock later (see {@link #tick()}).
     */
    private int icrStaged;

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
    private int todLatch;    // Latch value when TODHI is read
    private int todAlarm;    // Alarm threshold
    private boolean todFrozen;  // Freezes on TODHI read, defreezes on TODLO read
    private boolean todStopped; // Stops on TODHI write, starts on TODLO write

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
        icrStaged = 0;

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
        // The TOD does NOT count until TODLO is written: a reset clears the whole chip
        // state, writing TODLO in counter mode switches the TOD on and writing TODHI
        // switches it off. A TOD counting from the reset would run through the whole boot
        // and reach $8017 on CIA-B, where it must stay at 0 until the program's first
        // TODLO write.
        todStopped = true;

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

    // -- Timer helpers ------------------------------------------------------------

    private void reloadTimerA() {
        counterA = latchA;
    }

    private void reloadTimerB() {
        counterB = latchB;
    }

    /**
     * Arms the assertion of the /IRQ line and of the ICR master bit <b>one CIA clock
     * after</b> the event that caused them: stage 0 of the pipeline, which becomes stage 1
     * at the next tick.
     *
     * <p>This applies to <b>all four</b> ICR sources, with no exception: the timer and
     * serial bits pass through a staging register that becomes readable at the start of the
     * <b>next</b> clock, and the FLAG and TOD alarm are delivered one CIA clock later too.
     * Only then are bits 7 and 6 raised and the line pulled down.
     *
     * <p>Measured (docs/vamigats-paula-audio-plan.md section 7.1.17): underflow to INTREQ bit
     * 3 takes <b>+6 CCK</b>, 5 of them this CIA clock and 1 the INTREQ delay on Paula's side.
     *
     * <p>Clock-driven timer underflows do not come through here: their source bit is staged
     * for one clock as well ({@link #icrStaged}) and raises /IRQ when it becomes readable,
     * in {@link #tick()}.
     */
    private void triggerIrq() {
        delay |= CIASetInt0 | CIASetIcr0;
    }

    // -- TOD helpers --------------------------------------------------------------

    public void incrementTod() {
        if (todStopped) return;

        // Increment bits using nibble overflow carry
        if (!incTodLoNibble(0)) { checkTodIrq(true); return; }
        if (!incTodHiNibble(0)) { checkTodIrq(true); return; }
        if (!incTodLoNibble(1)) { checkTodIrq(true); return; }
        if (!incTodHiNibble(1)) { checkTodIrq(true); return; }
        if (!incTodLoNibble(2)) { checkTodIrq(true); return; }
        incTodHiNibble(2);

        checkTodIrq(true);
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
        checkTodIrq(false);
    }

    /**
     * Comparison with the alarm.
     *
     * <p>The 8520's TODMED counter <b>has a defect, and it is observable</b>: on a carry it
     * counts {@code .. 29 2A 2B 2C 2D 2E 2F 20 30 31 ..}, i.e. for an instant it holds a
     * value whose low twelve bits are zero and whose carry has not propagated yet. If the
     * alarm is that value, it fires <b>a second time</b>. Modelled as a rule on the
     * comparison alone, and <b>only on an increment</b> ({@code inc}), never on a write.
     *
     * <p>That is exactly what {@code todvice/0alarm} measures, as its source says: it draws
     * a green band only if the alarms are <b>twice</b> the passes, <i>"because of the TOD
     * bug"</i>. With the alarm at zero the two occasions are the TODLO write (counter and
     * alarm both 0) and the increment to {@code $001000}, where {@code (tod-1) & $FFF000} is
     * still 0. The rule is on: see {@link #todBug}.
     *
     * <p><b>The comparison is a LEVEL, not an edge.</b> The interrupt is raised whenever
     * counter and alarm are equal at a comparison, even if they already were at the previous
     * one. There are exactly two comparison points: one per increment and one at a TODLO
     * write in counter mode.
     *
     * <p>Measured by {@code CIA/TOD/tod3}: the handler sets alarm <b>and</b> counter back to
     * zero by hand (so they are equal at every pass) and paints the background yellow if the
     * alarm fires. With an edge detector two of six bands came out blue: exactly the passes
     * where the equality was already there and the edge was not.
     */
    private void checkTodIrq(boolean inc) {
        boolean match = todCounter == todAlarm;
        if (!match && todBug && inc
                && (todCounter & 0x000FFF) == 0
                && ((todCounter - 1) & 0xFFF000) == todAlarm) {
            match = true;
        }
        if (match) {
            icr |= 0x04; // TOD source bit; the ROM reads it to identify the interrupt
            if ((imr & 0x04) != 0) triggerIrq();
        }
    }

    // -- I/O port logic -----------------------------------------------------------

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
     * Read-back of PRA ({@code $BFE001} / {@code $BFD000}).
     *
     * <p>On the 8520 <b>the DDR does not apply to every bit.</b> Honouring it everywhere,
     * i.e. returning the output value for every pin configured as an output, is the
     * behaviour of the <b>391078-01</b> variant, not of the A500's CIA-A:
     *
     * <pre>
     * v  = drive status &amp; 0x3c;                 // bits 2..5: ALWAYS from the drive
     * v |= joystick buttons(pra, dra);           // bits 6..7: buttons, but see below
     * v |= (pra | (dra ^ 3)) &amp; 0x03;             // bits 0..1: these DO follow the DDR
     * </pre>
     *
     * Bits 2-5 (/CHNG, /WPRO, /TK0, /RDY) return the level of the line even with the pin
     * configured as an output. Bits 6-7 are <b>open collector</b>: the button holds them
     * high, but an output driving 0 wins. Only bits 0-1 (OVL, LED) are true outputs, and
     * read 1 as inputs.
     *
     * <p>On <b>CIA-B</b> port A is the full formula {@code (pra &amp; dra) | (dra ^ 0xFF)}:
     * as an input it reads 1, not an external level.
     *
     * <p>Measured by {@code CIA/CIA/ciareg}: {@code aprab3} writes {@code DDRA=$FF} and
     * {@code PRA=$00}, and the four drive lines still read high. {@code aprab4}, which
     * writes the same {@code DDRA=$FF} but {@code PRA=$FE}, cannot tell the two rules apart.
     * See {@code docs/vamigats-import-order.md}, {@code CIA/CIA} outcomes.
     */
    private int computePA() {
        final int ext = pinSet.readExternal(0);
        if (!isCIAA) {
            return ((pra & ddra) | (ddra ^ 0xFF)) & 0xFF;
        }
        int v = ext & 0x3C;
        // Buttons: high when not pressed, but an output driving 0 pulls them low anyway.
        v |= (ext & 0xC0) & ~(ddra & ~pra);
        v |= (pra | (ddra ^ 0x03)) & 0x03;
        return v & 0xFF;
    }

    /**
     * Read-back of PRB ({@code $BFE101} / {@code $BFD100}).
     *
     * <p>The two B ports follow different rules:
     * <ul>
     *   <li><b>CIA-A</b>: {@code (prb & drb) | (drb ^ 0xFF)}; as an input it reads 1, the
     *       parallel port floating high;</li>
     *   <li><b>CIA-B</b>: {@code prb} alone: <b>the DDR is ignored entirely</b>, the
     *       read-back returns the output register even for bits configured as inputs.</li>
     * </ul>
     * Both then force PB6/PB7 to the timer outputs when the PBON bit of the control
     * register is set, <b>after</b> the direction mask, so the DDR cannot mask them.
     *
     * <p>Measured by {@code CIA/CIA/ciareg}: {@code bprab1} clears CIA-B's PRB and DDRB and
     * reads back {@code $00}, not {@code $FF}: eight bits out of eight.
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
     * Side-effect-free internal read of port B.
     * Unlike {@link #readRegister(int)}, it does not go through the CIA register logic and
     * changes no latch, pipeline or interrupt.
     */
    public byte peekPortB() {
        // Port B as the disk controller sees it: the output LATCH with the PB6/PB7 timer
        // outputs, not the pins. A DDRB write alone does not reach the drives, and right
        // after a reset (prb = 0) every line reads active: the drives take that state
        // without acting on its edges (AbstractAgnus.floppySelectSet), so no motor starts,
        // but they count as selected and the idle read shifter stands still until the
        // first PRB write. The register read by the CPU follows a different rule.
        return (byte) computePB();
    }

    public byte peekDataDirectionA() {
        return (byte) ddra;
    }

    // -- External pin events ------------------------------------------------------

    public void emulateFallingEdgeOnFlagPin() {
        icr |= 0x10;
        if ((imr & 0x10) != 0) {
            triggerIrq();
        }
    }

    public void emulateRisingEdgeOnCntPin() {
        cnt = true;
        countOnCntEdge();

        // -- Shift register in INPUT --------------------------------------------
        // The SSR -> SDR transfer is IMMEDIATE, not delayed by three clocks: a delayed
        // transfer would also be cancelled by a TX/RX switch (`case 0xE`), and in
        // `ser/ser6`, which shifts four bits, switches CRA to SP output and back to input,
        // SDR would never be updated. The INTERRUPT stays delayed (`CIASerInt0`).
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
     * Counting of CNT edges: it happens <b>inside the edge</b>, not in the pipeline.
     *
     * <p>Injecting {@code CIACountA1}/{@code CIACountB1} into the pipeline instead would put
     * the underflow check two CIA clocks after the edge and the decrement three: the number
     * of edges counted would be right, the <b>instant</b> wrong. Measured on the bank:
     * {@code cnt/cnt1} (timer A on edges, IRQ on A's underflow) had the handler band 48
     * pixels = 12 CCK late and {@code oldcnt/cnt4} (timer B on edges) 36 pixels = 9, i.e.
     * two CIA clocks (10 CCK) quantised by the instruction boundary at which the CPU takes
     * the interrupt. The reference has 4 pixels per CCK.
     *
     * <p>Order: A first, and if A underflows and B counts A's underflows
     * ({@code INMODE=1x}) B is decremented <b>in the same edge</b>; B in CNT mode
     * ({@code INMODE=01}) counts anyway, and the two paths give <b>one</b> decrement of B,
     * not two.
     *
     * <p>The cascade from a timer A driven by the <b>system clock</b> stays in the pipeline
     * instead ({@code delay |= CIACountB1} in {@link #tick()}).
     */
    private void countOnCntEdge() {
        boolean decB = false;

        // Timer A: INMODE=1 (CNT edges) and START.
        if ((cra & 0x21) == 0x21) {
            if (counterA == 0) {
                timerAUnderflowOnCntEdge();
                // B counts A's underflows (INMODE=10 or 11): on the edge the "CNT high"
                // condition of INMODE=11 holds by construction.
                if ((crb & 0x41) == 0x41) decB = true;
            } else {
                counterA = (counterA - 1) & 0xFFFF;
            }
        }
        // Timer B: INMODE=01 (CNT edges) and START.
        if ((crb & 0x61) == 0x21) decB = true;

        if (decB) {
            if (counterB == 0) timerBUnderflowOnCntEdge();
            else counterB = (counterB - 1) & 0xFFFF;
        }
    }

    /**
     * Underflow of timer A caused by a CNT edge. Does what the {@code timerAOutput} block of
     * {@link #tick()} does, but <b>at once</b>: the reload from the latch is immediate and
     * cancels any decrement already in the pipe.
     *
     * <p>There is no serial clock generation because there cannot be: with {@code CRA} bit 6
     * set the CNT pin is an <b>output</b> and no external edge arrives here;
     * {@link #updatePins()} takes this path only with bit 6 clear.
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

    /** Twin of {@link #timerAUnderflowOnCntEdge()} for timer B. */
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

        // Timer underflow bits raised in the previous clock; they become readable in
        // this one, after its pending ICR clears (see the end of this method).
        final int icrTransfer = icrStaged;
        icrStaged = 0;

        // -- Timer A pipeline processing ------------------------------------------

        // (0) A load from the latch cancels the decrement of its OWN clock. The output bit
        // of the count pipe is cleared both by the load scheduled by a write and by the
        // reload that follows an underflow in continuous mode. It applies to both timers
        // and to every mode: it is not an exception.
        // See the note on timer B and docs/vamigats-paula-audio-plan.md section 7.1.16.
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
        // (4) Delayed reload. Two stages, armed together when the timer is stopped: the
        // load happens at clock 2 AND at clock 3 after the write.
        if ((delay & (CIALoadA1 | CIALoadA2)) != 0) reloadTimerA();

        // -- Timer B pipeline processing ------------------------------------------

        // There is no extra start delay: the delay between the write of CRA/CRB and the
        // first decrement is that of the CIACountB0 -> B1 -> B2 -> B3 pipeline below plus
        // the loads. An extra delay counted on count EVENTS would eat the first underflows
        // of timer A in a cascade: Dragon's Lair (latchA=7160, latchB=100, CRB=$49) uses the
        // CASCADE (INMODE=10) as an alarm clock and checks that the wait does not exceed 53
        // frames ($003D1E: SUB.b #$35,D0). `CiaCascadeTimerTest` is the regression.
        //
        // The start delay is that of the `CIALoadB0 -> B1 -> B2` pipeline plus the rule that
        // a load cancels the count of its own clock (see timer A). Measured (plan section
        // 7.1.16, timer.device arming at $FE94AC): the first decrement falls at clock **4**
        // after the TBHI write, because the latch is loaded at clocks 2 and 3 and each load
        // removes the decrement of its own clock.
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

        // -- Serial port processing -----------------------------------------------

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

        // -- PB6/PB7 pin output -----------------------------------------------------

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

        // -- Interrupt logic --------------------------------------------------------

        // A timer underflow is not readable in the clock it happens: its source bit is
        // staged and reaches the ICR one clock later, together with the interrupt.
        if (timerAOutput) { icrStaged |= 0x01; }
        if (timerBOutput) { icrStaged |= 0x02; diagTimerBUnderflows++; }
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

        // Staged timer bits become readable after this clock's clears, so an ICR read
        // made before they arrived neither returns nor acknowledges them, and cannot
        // cancel their interrupt: an enabled source asserts /IRQ here.
        if (icrTransfer != 0) {
            icr |= icrTransfer;
            if ((icrTransfer & imr) != 0) {
                icr |= 0x80;
                irq = false;
            }
        }

        // Shift pipeline and update pin sync
        delay = ((delay << 1) & CIADelayMask) | feed;
        updatePins();
    }

    /**
     * Internal connections of CIA-B only: PA0 to SP, PA1 to CNT.
     *
     * <p>The level is the one ACTUALLY DRIVEN, {@code pra & ddra}: a pin configured as an
     * input drives nothing, and taking {@code pra} alone would create CNT edges that do not
     * exist on the board. The old level is compared the same way.
     *
     * <p><b>Evaluated at the WRITE, not at the next tick.</b> The CNT edge counts the timer
     * inside the access; deferring it to the next tick moves the count by one CIA clock, and
     * in {@code cnt/} the count IS the measurement.
     *
     * <p><b>PA0 also updates {@link #sp}, not just the pin.</b> The input shift register
     * reads {@code sp} at the CNT edge, which arrives here, inside the access; if {@code sp}
     * were refreshed only in {@link #updatePins()}, at the tick, the write path would shift
     * in the old level. Measured: {@code ser/ser1} must receive {@code $A5} (the sequence
     * 1,0,1,0,0,1,0,1 written by the test), not {@code $FF}, and likewise {@code ser2-4},
     * {@code ser6}, {@code ser7}. The bit shifted in is {@code (val & 1)} of the driven
     * level, with no inversion.
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
        }
        // The rising edge of the TOD pin is NOT sampled here; the pin is only the signal
        // Agnus drives. The instant at which the counter advances is computed by
        // AbstractAgnus.todIncOffsetCck(), which already contains the whole synchroniser:
        // the CCK of delay of Agnus's pin, the 12 E clocks of internal delay and the
        // alignment to the four-E-clock grid. Sampling the pin again on our E clock would add
        // a second synchroniser to the same wire, a constant 3.5 CCK late. Agnus calls
        // incrementTod() at the exact instant.
    }

    // -------------------------------------------------------------------------
    // Register Read Access
    // -------------------------------------------------------------------------

    /**
     * <b>Side-effect-free</b> read of a CIA register, for the debugger and the
     * disassembler.
     *
     * <p>Needed because {@link #readRegister(int)} changes a lot, rightly: reading the ICR
     * ($D) sets bit 7, arms the clear/ack pipeline and <b>releases the /IRQ line</b>; reading
     * TODHI ($A) freezes the TOD latch and reading TODLO ($8) unfreezes it. A memory dump
     * crossing $BFExxx/$BFDxxx through {@code readRegister} would therefore cancel a pending
     * CIA interrupt of the machine it is observing. All the other registers are already
     * pure and go through there.
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

    /**
     * A TOD read sees the counter <b>now</b>, not as it was a few clocks ago.
     *
     * <p>There is no read latency: cases 8/9/10 return the counter (or the latch, when
     * latched) and nothing else. The delay between the TOD pin and the counter lives in
     * {@code tod_inc_delay} (95-114 CCK on CIA-B); a read latency on top of it would count
     * the same wait twice.
     *
     * <p>Measured by {@code CIA/TOD/todcntA1} and {@code todpulse1a}: they clear the TOD in
     * the VERTB handler and then <b>spin until TODLO changes</b>. With a latency the read
     * right after the clear would return the old value and the loop would exit <b>at
     * once</b>: VHPOSR at {@code vpos 0} instead of {@code vpos 5}, the line of CIA-A's tick
     * ({@code CIAA_TOD_VPOS}).
     */
    private int getTodLo() { return (todFrozen ? todLatch : todCounter) & 0xFF; }
    private int getTodMid() { return ((todFrozen ? todLatch : todCounter) >> 8) & 0xFF; }
    private int getTodHi() { return ((todFrozen ? todLatch : todCounter) >> 16) & 0xFF; }

    // -------------------------------------------------------------------------
    // Register Write Access
    // -------------------------------------------------------------------------

    public void writeRegister(int reg, byte value) {
        int addr = reg & 0x0F;
        int val = value & 0xFF;

        switch (addr) {
            // Port A and port B are handled the same way: the value on the pins is the one
            // DRIVEN, not the raw register. On CIA-B updatePA() inverts bits 6-7 (/RTS and
            // /DTR, active low) for the pins configured as outputs only, so the value also
            // depends on DDRA and is recomputed at a DDRA write as well.
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
                // Writing TAHI on a ONE-SHOT timer reloads the counter EVEN IF the timer is
                // ALREADY RUNNING: the double load is conditional on the timer being
                // stopped, but the one-shot branch, which sets START and arms another load,
                // has no condition. That is what `Timer/timer5` and `timer5c` measure by
                // writing TAHI a second time on a running timer; `timer5b`, which rewrites
                // TALO only, isolates the variable.
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
                // Twin of case 0x5: see the note on reloading a running timer.
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
            // Only ONE register compares with the alarm on a WRITE: TODLO, right after
            // switching the TOD on; TODMID and TODHI only set the counter. It is a table,
            // not a general rule with an exception, the same shape as the port read-back.
            // Measured: `CIA/TOD/tod/todint4` sets counter and alarm equal by hand writing
            // TODMID last and counts the alarms: 0 in 81 passes, where comparing at every
            // write would give 80, one per TODMID write.
            // A write to the ALARM (crb bit 7) compares on none of the three.
            case 0x8:
                if ((crb & 0x80) != 0) todAlarm = (todAlarm & 0xFFFF00) | val;
                else { todCounter = (todCounter & 0xFFFF00) | val; todStopped = false; checkTodIrq(); }
                break;
            case 0x9:
                if ((crb & 0x80) != 0) todAlarm = (todAlarm & 0xFF00FF) | (val << 8);
                else todCounter = (todCounter & 0xFF00FF) | (val << 8);
                break;
            case 0xA:
                if ((crb & 0x80) != 0) todAlarm = (todAlarm & 0x00FFFF) | (val << 16);
                else { todCounter = (todCounter & 0x00FFFF) | (val << 16); todStopped = true; }
                break;
            case 0xB: break;
            case 0xC: sdr = val; delay |= CIASdrToSsr0; feed |= CIASdrToSsr0; break;
            case 0xD:
                if ((val & 0x80) != 0) imr |= (val & 0x1F);
                else imr &= ~(val & 0x1F);
                if ((imr & icr & 0x1F) != 0 && irq && (delay & CIAReadIcr1) == 0) delay |= (CIASetInt1 | CIASetIcr1);
                break;
            case 0xE:
                // An explicit start (START without LOAD) injects TWO stages, not one. The
                // count pipe is two bits long and is not touched when the timer starts: the
                // input bit enters at the first clock after the write, moves down at the
                // second and comes out at the THIRD, which is the first decrement. Injecting
                // stage 0 alone would put the first decrement at the FOURTH clock: one CIA
                // clock (5 CCK) late at every start, carried by every later underflow. It is
                // the same preparation the automatic start from TAHI (case 0x5) does,
                // `CIACountA1 | CIACountA0`.
                //
                // It does not affect a start with LOAD (`$11`, the one of `Timer/cont*`):
                // there the stage-2 load cancels the decrement of its own clock anyway, so
                // the first decrement is at the fourth clock in both forms. That is why
                // `Timer` cannot see it and `cnt/cnt3`, `ciaa1`/`ciaa2`, `ciab1`/`ciab2`,
                // which start with `$01`, can.
                if ((val & 0x01) != 0) {
                    delay |= CIACountA0 | CIACountA1; feed |= CIACountA0;
                    if ((cra & 0x01) == 0) pb67Toggle |= 0x40;
                } else {
                    // A stop drops the decrement due at the next clock and the stages still to
                    // come, but not the one behind it: the timer counts once more, at the
                    // second clock after the write.
                    delay &= ~(CIACountA3 | CIACountA1 | CIACountA0); feed &= ~CIACountA0;
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
                // Switching TX/RX also clears the REGISTER, not just the counter. That is
                // what `ser/ser6` measures: four bits in, switch, four bits in, and the
                // first four must vanish.
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
                // Twin of case 0xE: see the note on the two-stage start.
                if ((val & 0x01) != 0) {
                    delay |= CIACountB0 | CIACountB1; feed |= CIACountB0;
                    if ((crb & 0x01) == 0) pb67Toggle |= 0x80;
                } else {
                    // A stop drops the decrement due at the next clock and the stages still to
                    // come, but not the one behind it: the timer counts once more, at the
                    // second clock after the write.
                    delay &= ~(CIACountB3 | CIACountB1 | CIACountB0); feed &= ~CIACountB0;
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
     * State of CIA-A's /LED pin (PRA bit 1), which drives the A500's power LED and audio
     * filter: a low bit lights the LED (bright) and enables the filter; a high bit leaves it
     * dim. Meaningful for CIA-A only.
     */
    public boolean isPowerLedOn() { return (pra & 0x02) == 0; }
}
