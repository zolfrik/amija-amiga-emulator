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

    public enum Revision {
        MOS_8520_DIP,
        MOS_8520_PLCC
    }

    private final CIAPinSet pinSet;
    private final boolean isCIAA;
    private Revision revision = Revision.MOS_8520_DIP;
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
    private static final int TIMER_START_DELAY = 8;

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

    private static final long CIASsrToSdr0 = (1L << 38);
    private static final long CIASsrToSdr1 = (1L << 39);
    private static final long CIASsrToSdr2 = (1L << 40);
    private static final long CIASsrToSdr3 = (1L << 41);

    private static final long CIASerClk0   = (1L << 42);
    private static final long CIASerClk1   = (1L << 43);
    private static final long CIASerClk2   = (1L << 44);
    private static final long CIALast      = (1L << 46);

    private static final long CIADelayMask = ~(CIALast
            | CIACountA0 | CIACountB0 | CIALoadA0 | CIALoadB0 | CIAPB6Low0
            | CIAPB7Low0 | CIASetInt0 | CIAClearInt0 | CIAOneShotA0 | CIAOneShotB0
            | CIAReadIcr0 | CIAClearIcr0 | CIAAckIcr0 | CIASetIcr0 | CIATODInt0
            | CIASerInt0 | CIASdrToSsr0 | CIASsrToSdr0 | CIASerClk0);

    // ── Diagnostics ──────────────────────────────────────────────────────────
    public void setTodBug(boolean en) { this.todBug = en; }

    public void setCpuPcSupplier(java.util.function.IntSupplier s) { }

    public void setClkSupplier(java.util.function.LongSupplier s) { }

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
    private int timerAStartDelay, timerBStartDelay;

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
        timerAStartDelay = 0;
        timerBStartDelay = 0;

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

    // ── Configuration ────────────────────────────────────────────────────────────

    public void setRevision(Revision revision) {
        this.revision = revision;
    }

    // ── Timer Helpers ─────────────────────────────────────────────────────────

    private void reloadTimerA() {
        counterA = latchA;
    }

    private void reloadTimerB() {
        counterB = latchB;
    }

    private void triggerTimerIrq() {
        // Delayed interrupt propagation
        delay |= ((delay & CIAReadIcr0) != 0) ? CIASetInt0 : CIASetInt1;
        delay |= ((delay & CIAReadIcr0) != 0) ? CIASetIcr0 : CIASetIcr1;
    }

    private void triggerTodIrq() {
        delay |= ((delay & CIAReadIcr0) != 0) ? CIASetInt0 : CIASetInt1;
        delay |= ((delay & CIAReadIcr0) != 0) ? CIASetIcr0 : CIASetIcr1;
    }

    private void triggerFlagPinIrq() {
        delay |= ((delay & CIAReadIcr0) != 0) ? CIASetInt0 : CIASetInt1;
        delay |= ((delay & CIAReadIcr0) != 0) ? CIASetIcr0 : CIASetIcr1;
    }

    private void triggerSerialIrq() {
        delay |= ((delay & CIAReadIcr0) != 0) ? CIASetInt0 : CIASetInt1;
        delay |= ((delay & CIAReadIcr0) != 0) ? CIASetIcr0 : CIASetIcr1;
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
            if ((imr & 0x04) != 0) triggerTodIrq();
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

    private int computePA() {
        return pinSet.readPortData(0) & 0xFF;
    }

    private int computePB() {
        return pinSet.readPortData(1) & 0xFF;
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
            triggerFlagPinIrq();
        }
    }

    public void emulateRisingEdgeOnCntPin() {
        cnt = true;
        // Timer events triggered by CNT pulse
        if ((cra & 0x21) == 0x21) delay |= CIACountA1;
        if ((crb & 0x61) == 0x21) delay |= CIACountB1;

        // Serial Input logic
        if ((cra & 0x40) == 0) {
            if (serCounter == 0) serCounter = 8;
            ssr = ((ssr << 1) | (sp ? 1 : 0)) & 0xFF;
            if (--serCounter == 0) {
                delay |= CIASsrToSdr0;
                delay |= CIASerInt0;
            }
        }
    }

    public void emulateFallingEdgeOnCntPin() {
        cnt = false;
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

        // (1) Decrement counter.
        // Il ritardo di avvio è un fenomeno del clock interno del CIA (~8 E-clock),
        // NON un numero di eventi di conteggio: va consumato ad ogni tick. Vedi la
        // nota estesa sul timer B, dove la differenza è macroscopica.
        if (timerAStartDelay > 0) {
            timerAStartDelay--;
        } else if ((delay & CIACountA3) != 0) {
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
        // (4) Delayed reload
        if ((delay & CIALoadA1) != 0) reloadTimerA();

        // ── Timer B Pipeline Processing ───────────────────────────────────────

        // Come per il timer A, il ritardo di avvio si consuma sul clock del CIA e non
        // sugli eventi di conteggio.
        //
        // Contarlo in eventi era corretto solo in modalità φ2 (INMODE=00), dove c'è
        // un evento per E-clock. In modalità CASCATA (INMODE=10: il timer B conta gli
        // underflow del timer A) gli eventi sono rarissimi, e i primi 8 underflow di A
        // venivano mangiati dal delay invece di decrementare il contatore: con
        // latchA=7160 sono 8 × 71 600 = 572 800 cicli ≈ 4 frame di ritardo su un
        // periodo nominale di 51 — un errore dell'8%.
        //
        // Dragon's Lair usa esattamente questa configurazione (latchA=7160,
        // latchB=100, CRB=$49) come sveglia e verifica che l'attesa non superi 53
        // frame ($003D1E: SUB.b #$35,D0): 51 nominali passavano a 56 e il controllo
        // falliva, mandando il gioco in stallo con schermo nero.
        if (timerBStartDelay > 0) {
            timerBStartDelay--;
        } else if ((delay & CIACountB3) != 0) {
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
        if ((delay & CIALoadB1) != 0) reloadTimerB();

        // ── Serial Port Processing ───────────────────────────────────────────

        if ((delay & CIASsrToSdr3) != 0) sdr = ssr;

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
            triggerTimerIrq();
        }
        if ((delay & CIATODInt0) != 0) {
            icr |= 0x04;
            if ((imr & 0x04) != 0) triggerTodIrq();
        }
        if ((delay & CIASerInt2) != 0) {
            icr |= 0x08;
            if ((imr & 0x08) != 0) triggerSerialIrq();
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
                // CIA-B specific internal connections: PA0 -> SP, PA1 -> CNT
                boolean pa0 = (pra & 0x01) != 0;
                boolean pa1 = (pra & 0x02) != 0;
                setSpDriveCached(pa0);
                if (pa1 != cnt) {
                    if (pa1) emulateRisingEdgeOnCntPin();
                    else emulateFallingEdgeOnCntPin();
                }
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
            case 0x0:
                pra = val;
                writePortDataCached(0, pra);
                break;
            case 0x1:
                prb = val;
                updatePB();
                break;
            case 0x2:
                ddra = val;
                writeDdrCached(0, ddra);
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
                // Auto-start on high write if one-shot AND stopped
                if ((cra & 0x08) != 0 && (cra & 0x01) == 0) {
                    pb67Toggle |= 0x40;
                    if ((cra & 0x20) == 0) {
                        delay |= CIACountA1 | CIALoadA0 | CIACountA0;
                        feed |= CIACountA0;
                        timerAStartDelay = TIMER_START_DELAY;
                    }
                    cra |= 0x01;
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
                // Auto-start on high write if one-shot AND stopped
                if ((crb & 0x08) != 0 && (crb & 0x01) == 0) {
                    pb67Toggle |= 0x80;
                    if ((crb & 0x60) == 0) {
                        delay |= CIACountB1 | CIALoadB0 | CIACountB0;
                        feed |= CIACountB0;
                        timerBStartDelay = TIMER_START_DELAY;
                    }
                    crb |= 0x01;
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
                if ((val & 0x01) != 0) {
                    delay |= CIACountA0; feed |= CIACountA0;
                    if ((cra & 0x01) == 0) {
                        pb67Toggle |= 0x40;
                        timerAStartDelay = TIMER_START_DELAY;
                    }
                } else {
                    delay &= ~(CIACountA1 | CIACountA0); feed &= ~CIACountA0;
                    timerAStartDelay = 0;
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
                if (((val ^ cra) & 0x40) != 0) {
                    if ((val & 0x40) != 0) sp = false; // Amiga ACK: drive line LOW
                    serCounter = 0;
                    delay &= ~(CIASsrToSdr0 | CIASsrToSdr1 | CIASsrToSdr2 | CIASsrToSdr3);
                    delay &= ~(CIASdrToSsr0 | CIASdrToSsr1);
                    feed &= ~CIASdrToSsr0;
                    delay &= ~(CIASerClk0 | CIASerClk1 | CIASerClk2);
                    feed &= ~CIASerClk0;
                }
                cra = val;
                updatePB();
                break;
            case 0xF:
                if ((val & 0x01) != 0) {
                    delay |= CIACountB0; feed |= CIACountB0;
                    if ((crb & 0x01) == 0) {
                        pb67Toggle |= 0x80;
                        timerBStartDelay = TIMER_START_DELAY;
                    }
                } else {
                    delay &= ~(CIACountB1 | CIACountB0); feed &= ~CIACountB0;
                    timerBStartDelay = 0;
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
