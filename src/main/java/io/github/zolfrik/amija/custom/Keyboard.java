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

import io.github.zolfrik.amija.common.CIAPinSet;
import io.github.zolfrik.amija.common.MOS8520;
import io.github.zolfrik.amija.input.AmigaKeyTarget;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.Deque;

/**
 * Amiga 500 keyboard.
 *
 * Implements the serial protocol on CIA-A (KDAT = SP, KCLK = CNT).
 * Hardware reference: Amiga Hardware Reference Manual, appendix A, and schematics rev. 6A.
 *
 * Protocol:
 *   - Bit rate: ~17 kbit/s, i.e. ~58.8 us per bit
 *   - PAL clock: 7.09375 MHz, i.e. ~417 cycles per bit, 3 phases of ~139 cycles each
 *   - Transmission order: bits 6,5,4,3,2,1,0,7 (logical MSB first)
 *   - Inverted logic on the line: logical 1 = 0 V (LOW), logical 0 = 5 V (HIGH)
 *
 * Power-on sequence:
 *   1. Wait for the reset pulse on SP (CIA-A in output mode)
 *   2. Wait at least 1 ms
 *   3. Send 0xFD (Initiate Power-Up Key Stream)
 *   4. Wait for the ACK handshake from the Amiga (KDAT LOW)
 *   5. Wait at least 1 ms
 *   6. Send 0xFE (Terminate Power-Up Key Stream)
 *   7. Wait for the ACK, then IDLE
 *
 * Keycode format (1 byte):
 *   - Bits [7:1]: raw key number (7 bits)
 *   - Bit 0:      0 = key down, 1 = key up
 *
 * Special controller codes:
 *   - 0xF8: Lost Sync
 *   - 0xF9: Keyboard Failure
 *   - 0xFA: Power-Up Stream Error
 *   - 0xFD: Initiate Power-Up Stream
 *   - 0xFE: Terminate Power-Up Stream
 *
 * ACK handling:
 *   - Valid handshake: KDAT LOW for at least MIN_ACK_CYCLES cycles, then back HIGH
 *   - ACK timeout: if none arrives within ~143 ms, send 0xF8 and retry the current byte
 *   - After MAX_RETRIES failed attempts the byte is dropped
 *
 * CTRL+AMIGA+AMIGA reset:
 *   - The keyboard MPU detects the combination and asserts /KBRST (pin 3 of the A500
 *     keyboard connector), which on the motherboard drives /RESET and /HALT to the 68000
 *     and every chip.
 *   - While /KBRST is low the keyboard transmits NOTHING: the keycodes of the three keys
 *     (and of any pressed meanwhile) never reach the Amiga.
 *   - The line stays low for at least 500 ms and for as long as the keys are held: the
 *     machine restarts on release, not on press.
 *   - The line is read with {@link #isResetLineAsserted()}; {@code Gary} propagates it to
 *     /RESET (see AbstractAgnus.serviceCiaEvent).
 */
public class Keyboard implements AmigaKeyTarget {

    // -------------------------------------------------------------------------
    // Raw key numbers (bits [7:1] of the keycode byte transmitted)
    // Full table: Amiga Hardware Reference Manual, Appendix A
    // -------------------------------------------------------------------------
    public static final int KEY_ESC        = 0x45;
    public static final int KEY_F1         = 0x50;
    public static final int KEY_DEL        = 0x46;
    public static final int KEY_RETURN     = 0x44;
    public static final int KEY_SPACE      = 0x40;
    public static final int KEY_BACKSPACE  = 0x41;
    public static final int KEY_TAB        = 0x42;
    public static final int KEY_CTRL       = 0x63;
    public static final int KEY_CAPS_LOCK  = 0x62;
    public static final int KEY_L_SHIFT    = 0x60;
    public static final int KEY_R_SHIFT    = 0x61;
    public static final int KEY_L_ALT      = 0x64;
    public static final int KEY_R_ALT      = 0x65;
    public static final int KEY_L_AMIGA    = 0x66;
    public static final int KEY_R_AMIGA    = 0x67;
    public static final int KEY_HELP       = 0x5F;
    public static final int KEY_UP         = 0x4C;
    public static final int KEY_DOWN       = 0x4D;
    public static final int KEY_LEFT       = 0x4F;
    public static final int KEY_RIGHT      = 0x4E;

    // -------------------------------------------------------------------------
    // Special keyboard codes
    // -------------------------------------------------------------------------
    public static final int KCODE_LOST_SYNC     = 0xF8;
    public static final int KCODE_KBD_FAILURE   = 0xF9;
    public static final int KCODE_POWERUP_ERROR = 0xFA;
    public static final int KCODE_POWERUP_INIT  = 0xFD;
    public static final int KCODE_POWERUP_TERM  = 0xFE;

    // -------------------------------------------------------------------------
    // Timing constants (PAL clock 7.09375 MHz)
    // -------------------------------------------------------------------------

    /** Cycles per phase (KDAT setup / KCLK low / KCLK high, ~19.6 us each). */
    private static final int  PHASE_CYCLES    = 139;

    /** ~1 ms in PAL cycles. */
    private static final long DELAY_1MS       = 7_094L;

    /** ~143 ms: ACK handshake timeout (Hardware Reference Manual, section 9). */
    private static final long ACK_TIMEOUT     = 1_014_506L;

    /** Minimum length of the ACK pulse: ~1 us, ~7 PAL cycles.
     *
     *  The HRM (3rd ed.) says: "While some keyboards can detect a 1 microsecond
     *  handshake pulse, the pulse must be at least 85 microseconds for operation
     *  with ALL models of Amiga keyboards." The Kickstart's keyboard.device pulses the
     *  handshake for ~85 us, but many games with their own keyboard handler (e.g. Pinball
     *  Dreams) pulse it for much less: with an 85 us threshold their ACK was refused, which
     *  led to the 143 ms timeout, Lost Sync and a retransmission, i.e. slow or stuck
     *  flippers and keys. The threshold is therefore ~1 us. */
    private static final long MIN_ACK_CYCLES  = 7L;

    /** Maximum attempts before giving up on an unacknowledged byte. */
    private static final int  MAX_RETRIES     = 3;

    /** Minimum length of the /KBRST pulse: 500 ms (Hardware Reference Manual, keyboard
     *  reset). The MPU holds the line low at least this long even if the keys are released
     *  at once, so the reset cycle is always complete. */
    private static final long KBRST_MIN_CYCLES = 3_546_895L;   // 0,5 s @ 7,09379 MHz

    /** Safety cap on the /KBRST pulse (5 s).
     *
     *  On the hardware the line stays low while the three keys are held, and the MPU always
     *  sees the release. The host does not: Windows does not deliver to AWT the key-up of
     *  the first modifier released while two of the same kind are held (the same limit the
     *  physical poller of AmigaKeyAdapter works around), and a key-up can vanish entirely
     *  when the window loses focus. Without this cap a lost key-up would leave the machine
     *  in reset forever. */
    private static final long KBRST_MAX_CYCLES = 35_468_950L;  // 5 s

    // -------------------------------------------------------------------------
    // State of the state machine
    // -------------------------------------------------------------------------
    private enum State {
        /** Waiting for the initial reset pulse on SP (CIA-A in output). */
        POWERON_WAIT,
        /** SP seen in output: waiting for the falling edge (end of the pulse). */
        DETECTED_OUTPUT,
        /** Waiting 1 ms after the end of the pulse before sending 0xFD. */
        WAIT_AFTER_PULSE,
        /** Transmission in progress: bit-by-bit phase. */
        SENDING_BYTE,
        /** Byte transmitted: waiting for the ACK from the Amiga. */
        WAITING_FOR_ACK,
        /** ACK received for 0xFD: waiting 1 ms before sending 0xFE. */
        WAIT_BEFORE_FE,
        /** Power-up done: waiting for events from the queue. */
        IDLE
    }

    // -------------------------------------------------------------------------
    // Dipendenze hardware
    // -------------------------------------------------------------------------
    private final MOS8520  ciaA;
    private final CIAPinSet pins;

    // -------------------------------------------------------------------------
    // State variables
    // -------------------------------------------------------------------------
    private State state = State.POWERON_WAIT;

    /** Timer generico (timeout, delay). */
    private long timer = 0;

    /** Transmit buffer, bits reordered as the Amiga protocol requires. */
    private int  sendBuffer      = 0;
    /** Original value of the byte being transmitted (for logs and state decisions). */
    private int  currentByteVal  = 0;
    /** Index of the bit to transmit (starts from 7). */
    private int  bitIndex        = 7;
    /** Sub-state of the send machine (0 = KDAT, 1 = KCLK falling, 2 = KCLK rising). */
    private int  sendSubState    = 0;
    /** Cycle accumulator for the phase timer. */
    private int  sendTickCounter = 0;

    /** Counter of the cycles waiting for the ACK (for the timeout). */
    private long ackTimer      = 0;
    /** Cycles KDAT has stayed LOW in the current falling pulse. */
    private long ackLowCycles  = 0;
    /** Previous state of the SP pin (to detect edges). */
    private boolean lastSpState = true;
    /** Flag set by the ACK monitor when it detects a valid pulse. */
    private boolean ackReceived = false;

    /** Byte to resend after the ACK of a 0xF8 (Lost Sync). -1 = none. */
    private int retryByteVal   = -1;
    /** Number of attempts made for the current byte. */
    private int retryCount     = 0;

    /** FIFO of keycodes to transmit (format: rawKeyNum<<1 | upFlag).
     *  Accessed by two threads (MasterClock and the Swing EDT): it must be thread-safe. */
    private final Deque<Integer> keyQueue = new ConcurrentLinkedDeque<>();


    /** State of the modifier keys, to detect CTRL+AMIGA+AMIGA.
     *  Updated by the thread that delivers keyboard events (the Swing EDT). */
    private boolean ctrlDown   = false;
    private boolean lAmigaDown = false;
    private boolean rAmigaDown = false;

    /** True while the three reset keys are held TOGETHER (written by the EDT, read by the
     *  emulation thread: it is what holds /KBRST low). */
    private volatile boolean resetComboHeld = false;

    /** True from the completed combination until all three keys are released: their
     *  keycodes, press and release, are not transmitted. Once the MPU has detected the
     *  combination it stops transmitting and drives /KBRST only; without this flag the
     *  freshly restarted Amiga would receive the orphan key-ups of the reset keys. */
    private volatile boolean resetKeysSuppressed = false;

    /** /KBRST asserted toward the motherboard (true = line low).
     *  Written by the emulation thread in {@link #tick(int)}. */
    private volatile boolean kbResetAsserted = false;

    /** Clock cycles elapsed since /KBRST was asserted. */
    private long kbResetCycles = 0;

    /** True when the MPU is ready to detect a NEW reset combination. It re-arms only once
     *  the keys read released: the assertion follows the edge of the combination, not its
     *  level, so the expiry of the safety cap does not immediately start another pulse
     *  with keys that (apparently) are still held. */
    private boolean resetArmed = true;


    // =========================================================================
    // Costruttore
    // =========================================================================

    public Keyboard(MOS8520 ciaA) {
        this.ciaA = ciaA;
        this.pins = ciaA.getPinSet();
        // Lines released (HIGH) before initialisation
        pins.getSpPin().setExternalInput(true);
        pins.getCntPin().setExternalInput(true);
    }

    /**
     * Queues a key press event.
     *
     * @param rawKeyNum raw key number (7 bits), see the KEY_* constants
     */
    public void pressKey(int rawKeyNum) {
        if (updateModifiers(rawKeyNum, true)) return;   // reset in progress: no transmission
        // Keycode format: {upFlag=0, rawKeyNum[6:0]}; bit rotation applied in beginTransmit
        int keycode = rawKeyNum & 0x7F;
        keyQueue.addLast(keycode);
        // drainQueueIfIdle() is NOT called here: see the threading note in
        // drainQueueIfIdle(). Queueing is the only thread-safe operation.
    }

    /**
     * Queues a key release event.
     *
     * @param rawKeyNum raw key number (7 bits), see the KEY_* constants
     */
    public void releaseKey(int rawKeyNum) {
        if (updateModifiers(rawKeyNum, false)) return;  // reset in progress: no transmission
        // Keycode format: {upFlag=1, rawKeyNum[6:0]}; bit rotation applied in beginTransmit
        int keycode = (rawKeyNum & 0x7F) | 0x80;
        keyQueue.addLast(keycode);
        // drainQueueIfIdle() is NOT called here: see the threading note in
        // drainQueueIfIdle(). Queueing is the only thread-safe operation.
    }

    /**
     * Returns the number of keycodes waiting to be sent.
     */
    public int getPendingKeyCount() {
        return keyQueue.size();
    }

    /**
     * Empties the queue and clears the state, including the /KBRST line and the modifiers.
     * To be called on an external hardware reset (power-on).
     */
    public void hardReset() {
        resetProtocolState();
        ctrlDown = lAmigaDown = rAmigaDown = false;
        resetComboHeld      = false;
        resetKeysSuppressed = false;
        kbResetAsserted     = false;
        kbResetCycles       = 0;
        resetArmed          = true;
    }

    /**
     * Brings the serial protocol back to its power-up state: empty queue, lines released,
     * state machine waiting for the sync pulse on SP. It does NOT touch the modifiers or the
     * /KBRST line, which belong to the MPU and must survive the reset the MPU itself is
     * driving.
     */
    private void resetProtocolState() {
        keyQueue.clear();
        retryByteVal = -1;
        retryCount   = 0;
        ackReceived  = false;
        ackTimer     = 0;
        ackLowCycles = 0;
        lastSpState  = true;
        pins.getSpPin().setExternalInput(true);
        pins.getCntPin().setExternalInput(true);
        state = State.POWERON_WAIT;
    }

    /**
     * @return true if the keyboard is asserting /KBRST toward the motherboard
     *         (CTRL+AMIGA+AMIGA held, or the 500 ms minimum pulse not yet expired). Read at
     *         every E clock by {@code AbstractAgnus}.
     */
    public boolean isResetLineAsserted() {
        return kbResetAsserted;
    }

    /** @return true if the three keys of the reset combination are held now. */
    public boolean isResetComboHeld() {
        return resetComboHeld;
    }

    /**
     * @return true if {@code rawKeyNum} is one of the three keys of the reset combination
     *         and the <b>other two are already held</b>: pressing this one would complete it.
     *
     * <p>Used by remote play, for a reason {@link #isResetComboHeld()} alone does not cover:
     * that one answers after the fact, while the host's filter must decide <i>before</i>
     * delivering a key. Above all, the three keys can be split <b>across the two
     * keyboards</b> (the host holds CTRL and one Amiga key, the guest brings the third), and
     * looking only at what the guest sends is then not enough: none of its packets contains
     * all three keys, yet the machine would reset. This is the one question the keyboard can
     * answer and the filter cannot, because the filter does not see the host's state.
     */
    public boolean resetComboPendingOn(int rawKeyNum) {
        return switch (rawKeyNum) {
            case KEY_CTRL -> lAmigaDown && rAmigaDown;
            case KEY_L_AMIGA -> ctrlDown && rAmigaDown;
            case KEY_R_AMIGA -> ctrlDown && lAmigaDown;
            default -> false;
        };
    }

    // =========================================================================
    // Main tick: called every frame or for every group of cycles
    // =========================================================================

    public void tick(int cycles) {
        // CTRL+AMIGA+AMIGA reset: while /KBRST is low the keyboard is silent (lines
        // released, no keycode, no handshake).
        if (serviceResetLine(cycles)) return;

        // Continuous monitoring of the ACK pulse (independent of the state)
        pollAckPin(cycles);

        int cra = ciaA.readRegister(0xE) & 0xFF;
        boolean spOutputMode = (cra & 0x40) != 0;

        switch (state) {

            case POWERON_WAIT:
                // Waits for the CPU to put SP in output mode (start of the reset pulse)
                if (spOutputMode) {
                    state = State.DETECTED_OUTPUT;
                }
                break;

            case DETECTED_OUTPUT:
                // Waits for the end of the pulse (SP back to input)
                if (!spOutputMode) {
                    state = State.WAIT_AFTER_PULSE;
                    timer = DELAY_1MS;
                }
                break;

            case WAIT_AFTER_PULSE:
                timer -= cycles;
                if (timer <= 0) {
                    retryCount = 0;
                    beginTransmit(KCODE_POWERUP_INIT);
                }
                break;

            case SENDING_BYTE:
                // Advances the transmission bit by bit, PHASE_CYCLES at a time
                sendTickCounter += cycles;
                while (sendTickCounter >= PHASE_CYCLES) {
                    sendTickCounter -= PHASE_CYCLES;
                    handleSendStep();
                    // handleSendStep() may change state: leave the loop
                    if (state != State.SENDING_BYTE) break;
                }
                break;

            case WAITING_FOR_ACK:
                ackTimer += cycles;
                if (ackReceived) {
                    // ACK valido ricevuto
                    ackReceived = false;
                    ackTimer    = 0;
                    onAckReceived();
                } else if (ackTimer >= ACK_TIMEOUT) {
                    // Timeout: Lost Sync
                    ackTimer = 0;
                    handleAckTimeout();
                }
                break;

            case WAIT_BEFORE_FE:
                timer -= cycles;
                if (timer <= 0) {
                    retryCount = 0;
                    beginTransmit(KCODE_POWERUP_TERM);
                }
                break;

            case IDLE:
                // Ready: drain the queue if there are events
                drainQueueIfIdle();
                break;
        }
    }

    // =========================================================================
    // Linea /KBRST (reset CTRL+AMIGA+AMIGA)
    // =========================================================================

    /**
     * Drives the /KBRST line from the reset combination.
     *
     * Behaviour of the A500 keyboard MPU (6570-036):
     *   1. As soon as the three keys are held together, it pulls /KBRST low and abandons
     *      any transmission in progress.
     *   2. It holds the line low for at least 500 ms, and for as long as the keys are held:
     *      the machine stays in reset and restarts on RELEASE, not on press.
     *   3. When the line is released the machine boots; the Kickstart re-initialises
     *      keyboard.device and pulses SP, restarting the power-up sequence (0xFD/0xFE).
     *
     * @param cycles clock cycles elapsed since the last call
     * @return true if /KBRST is asserted (the keyboard must not transmit)
     */
    private boolean serviceResetLine(int cycles) {
        if (!kbResetAsserted) {
            if (!resetComboHeld) { resetArmed = true; return false; }
            if (!resetArmed)     return false;   // combination already served
            kbResetAsserted = true;
            resetArmed      = false;
            kbResetCycles   = 0;
            // The queued keys will never reach the Amiga, which is about to be reset: the
            // MPU truncates the transmission and releases KDAT/KCLK.
            resetProtocolState();
            return true;
        }

        kbResetCycles += cycles;
        boolean minimumElapsed = kbResetCycles >= KBRST_MIN_CYCLES;
        if ((!resetComboHeld && minimumElapsed) || kbResetCycles >= KBRST_MAX_CYCLES) {
            kbResetAsserted = false;
            kbResetCycles   = 0;
            return false;
        }
        return true;
    }

    // =========================================================================
    // Implementazione interna
    // =========================================================================

    /**
     * Watches the SP/KDAT pin for a valid ACK pulse.
     *
     * After receiving a keycode the CIA-A pulls KDAT low for at least ~1 us and then
     * releases it. This method measures how long the line stays LOW and reports a valid
     * ACK at the end of the pulse.
     *
     * The measurement is taken ONLY in WAITING_FOR_ACK. During SENDING_BYTE the KDAT line is
     * driven by the KEYBOARD ITSELF (inverted logic: logical 1 -> LOW), so counting LOW
     * cycles there would read the rising edge of the LAST DATA BIT, when the lines are
     * released at the end of the transmission, as the Amiga's handshake. The last bit sent
     * is sendBuffer[0] = keycode[7] = the up/down flag, which is 1 (line LOW) for every
     * KEY-UP: such a spurious ACK would hit releases systematically, the next keycode would
     * overwrite the CIA's SDR before the program read it, and the key-up would be lost
     * (a stuck flipper in Pinball Dreams until the next press).
     */
    private void pollAckPin(int cycles) {
        if (state != State.WAITING_FOR_ACK) {
            // Outside the wait for the handshake the line is driven by the keyboard: its LOW
            // levels never count as an ACK. Start clean, so only a falling edge DRIVEN BY
            // THE AMIGA after entering WAITING_FOR_ACK can make a valid handshake.
            ackLowCycles = 0;
            lastSpState  = true;
            return;
        }

        boolean spNow = pins.getSpPin().getPinState();

        if (!spNow) {
            // KDAT is LOW: accumulate the length
            ackLowCycles += cycles;
        } else {
            // KDAT back HIGH: rising edge
            if (!lastSpState && ackLowCycles >= MIN_ACK_CYCLES) {
                // Pulse long enough: valid ACK
                ackReceived = true;
            }
            ackLowCycles = 0;
        }
        lastSpState = spNow;
    }

    /** Transition logic after a confirmed ACK. */
    private void onAckReceived() {
        retryCount = 0;

        if (currentByteVal == KCODE_POWERUP_INIT) {
            // 0xFD acknowledged: wait 1 ms, then send 0xFE
            state = State.WAIT_BEFORE_FE;
            timer = DELAY_1MS;

        } else if (currentByteVal == KCODE_POWERUP_TERM) {
            // 0xFE acknowledged: power-up sequence complete
            state = State.IDLE;

        } else if (currentByteVal == KCODE_LOST_SYNC) {
            // 0xF8 (Lost Sync) acknowledged: resend the original byte, if any
            if (retryByteVal >= 0) {
                int toRetry = retryByteVal;
                retryByteVal = -1;
                beginTransmit(toRetry);
            } else {
                state = State.IDLE;
                drainQueueIfIdle();
            }

        } else {
            // Normal keycode acknowledged: next one from the queue
            retryByteVal = -1;
            state = State.IDLE;
            drainQueueIfIdle();
        }
    }

    /**
     * Handles the ACK timeout (~143 ms with no answer from the Amiga).
     * The controller sends 0xF8 (Lost Sync) and then retransmits the last byte.
     */
    private void handleAckTimeout() {
        if (retryCount < MAX_RETRIES) {
            retryCount++;
            // Save the byte to resend (so 0xF8 does not overwrite it)
            if (currentByteVal != KCODE_LOST_SYNC) {
                retryByteVal = currentByteVal;
            }
            beginTransmit(KCODE_LOST_SYNC);
        } else {
            retryCount   = 0;
            retryByteVal = -1;
            state = State.IDLE;
            drainQueueIfIdle();
        }
    }

    /**
     * Takes the next keycode from the queue and starts transmitting it, but only if the
     * machine is IDLE.
     *
     * THREADING: this method (and so beginTransmit) must be called ONLY from the emulation
     * thread, from tick(). pressKey()/releaseKey() are called by the Swing EDT and only
     * queue on keyQueue (concurrent). If the EDT drained the queue too, both threads could
     * read state == IDLE, both pollFirst() and both call beginTransmit(), which overwrites
     * ALL the transmission state (sendBuffer, bitIndex, sendSubState): the losing thread's
     * keycode would be dropped without ever being sent, and a lost key-up leaves the key
     * "held" until the next press. The state is not volatile either, so the EDT could read
     * a stale value.
     *
     * tick() is called at every E clock (~709 kHz), so draining only there costs at most
     * ~1.4 us of latency.
     */
    private void drainQueueIfIdle() {
        if (state == State.IDLE) {
            Integer next = keyQueue.pollFirst();   // atomic: null if empty
            if (next != null) {
                retryCount   = 0;
                retryByteVal = -1;
                beginTransmit(next);
            }
        }
    }

    /**
     * Prepares the transmit buffer and starts the send machine.
     *
     * The bits are reordered as the Amiga protocol requires: the byte sent on the line has
     * the bits in the order 6,5,4,3,2,1,0,7 of the original logical value.
     *
     * @param val logical value of the byte to transmit (0x00-0xFF)
     */
    private void beginTransmit(int val) {
        currentByteVal = val;

        // HRM: bits transmitted in order 6,5,4,3,2,1,0,7 of the keycode byte.
        // This equals a left-rotate-by-1 of the keycode. The CIA-A receives MSB-first,
        // so sendBuffer[7] is transmitted first and becomes CIA-A SDR[7].
        // The ROM reconstructs the keycode with: NOT(SDR), then ROR #1.
        int v = val & 0xFF;
        sendBuffer = ((v << 1) | (v >> 7)) & 0xFF;

        bitIndex        = 7;
        sendSubState    = 0;
        sendTickCounter = 0;
        ackTimer        = 0;
        ackReceived     = false;
        state           = State.SENDING_BYTE;

    }

    /**
     * Advances the transmission of the current byte by one phase.
     *
     * Each bit takes 3 phases (each PHASE_CYCLES cycles long):
     *   Phase 0: drive KDAT to the bit (inverted logic: 1 -> LOW, 0 -> HIGH)
     *   Phase 1: pull KCLK low (falling edge: the Amiga samples KDAT here)
     *   Phase 2: raise KCLK (rising edge: the CIA takes the bit)
     *
     * After all 8 bits the lines are released and the machine moves to WAITING_FOR_ACK.
     */
    private void handleSendStep() {
        switch (sendSubState) {

            case 0: // Set KDAT
                boolean bit = (sendBuffer & (1 << bitIndex)) != 0;
                // Inverted logic: logical 1 -> LOW voltage -> false
                pins.getSpPin().setExternalInput(!bit);
                sendSubState = 1;
                break;

            case 1: // KCLK LOW (falling edge, the Amiga samples KDAT)
                pins.getCntPin().setExternalInput(false);
                sendSubState = 2;
                break;

            case 2: // KCLK HIGH (rising edge, bit latched by the CIA)
                pins.getCntPin().setExternalInput(true);
                bitIndex--;
                if (bitIndex < 0) {
                    // All 8 bits sent: release the lines and wait for the ACK
                    pins.getSpPin().setExternalInput(true);
                    pins.getCntPin().setExternalInput(true);
                    ackTimer    = 0;
                    ackReceived = false;
                    state       = State.WAITING_FOR_ACK;
                } else {
                    sendSubState = 0;
                }
                break;
        }
    }

    /**
     * Updates the state of the modifier keys and evaluates the reset combination.
     *
     * Called by the keyboard-event thread: only the flags are updated here, the /KBRST line
     * is driven by the emulation thread in {@link #serviceResetLine}.
     *
     * @param rawKeyNum raw key number
     * @param pressed   true = press, false = release
     * @return true if the keycode must NOT be transmitted (reset sequence in progress)
     */
    private boolean updateModifiers(int rawKeyNum, boolean pressed) {
        if (rawKeyNum == KEY_CTRL)    ctrlDown   = pressed;
        if (rawKeyNum == KEY_L_AMIGA) lAmigaDown = pressed;
        if (rawKeyNum == KEY_R_AMIGA) rAmigaDown = pressed;

        // CTRL + L.AMIGA + R.AMIGA: the MPU asserts /KBRST and stops transmitting.
        boolean combo = ctrlDown && lAmigaDown && rAmigaDown;
        resetComboHeld = combo;

        // The decision is taken BEFORE lifting the suppression: the release of the last of
        // the three keys also belongs to the reset sequence and must not reach the Amiga as
        // an orphan key-up.
        boolean suppress = resetKeysSuppressed || kbResetAsserted;
        if (combo) {
            resetKeysSuppressed = true;
            suppress = true;
        } else if (resetKeysSuppressed && !ctrlDown && !lAmigaDown && !rAmigaDown) {
            // All three released: back to transmitting normally.
            resetKeysSuppressed = false;
        }
        return suppress;
    }
}
