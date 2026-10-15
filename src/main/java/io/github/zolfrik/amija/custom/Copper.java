/*
 * AmiJa - a cycle-exact Amiga emulator in Java.
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
 * Copper (co-processor) of the Amiga.
 *
 * The Copper is a co-processor synchronised with the video beam. It runs a list of
 * instructions (the Copper list) that can:
 *   1. MOVE: write a value to a custom register
 *   2. WAIT: wait for the beam to reach a position (V, H)
 *   3. SKIP: skip the next instruction if the beam has already reached (V, H)
 *
 * STATE MACHINE
 *
 * The Copper is ticked by {@link AbstractAgnus} at EVERY CCK and receives `evenCck`. Each
 * action is followed by an explicit delay, kept in {@link #tickDelay}:
 *   action completed -> next state 2 CCKs later
 *   bus denied       -> the same state RETRIES 1 CCK later
 * The rule "no wakeup on an odd CCK" applies ONLY to the wakeup states (REQ_DMA and
 * WAKEUP), to no other: FETCH and the other read states have no parity constraint. The
 * even cadence of the fetches is therefore a consequence of the 2-CCK delay, NOT a rule:
 * it breaks on purpose when a denial ($E0, refresh, DMA) inserts a +1. A fetch refused at
 * the BLOCKED cycle $E0 = 224 retries at 225 rather than sliding to the next even CCK
 * (226 = refresh, then 0 of the next line), which would steal 2 CCKs from the CPU
 * (docs/vamigats-cpu-timing-plan.md, S2).
 *
 * States:
 *   REQ_DMA        after a restart, COPJMP or the VBlank: waits for a slot, then FETCH
 *   FETCH          reads IR1, advances the PC, then MOVE or WAIT_OR_SKIP
 *   MOVE           reads IR2, performs MOVE / COPJMP / illegal
 *   WAIT_OR_SKIP   reads IR2, then WAIT1 or SKIP1
 *   WAIT1          bus-gated idle cycle
 *   WAIT2          evaluates: immediate match -> FETCH, otherwise -> WAKEUP
 *   WAKEUP         polls the comparator at every free even CCK
 *   SKIP1/SKIP2    bus-gated idle cycles
 *   COPJMPx (CPU or Copper MOVE) enters the strobe pipeline; see armStrobe()/advanceStrobe()
 *   frame restart  -> armFrameStrobe() (list 1, through the same pipeline)
 *   DORMANT        Copper stopped (illegal MOVE), until the VBlank or a COPJMP
 *
 * The WAIT uses no absolute scheduler: the comparator is polled in the WAKEUP state, which
 * wakes at the first EVEN CCK with a free bus at which the comparator is true (WAKEUP is a
 * wakeup state, so the parity constraint applies).
 *
 * COMPARATOR
 *
 *   waitpos = IR1 & 0xFFFE                 (VPHP; bit0 = 0)
 *   mask    = (IR2 & 0x7FFE) | 0x8001      (VMHM; VP7 and bit0 forced to 1)
 *
 *   Vertical:   bvHi = vpos & (mask>>8);  wpHi = (waitpos & mask) >> 8
 *     bvHi <  wpHi -> false
 *     bvHi >  wpHi -> true  (the Blitter is still checked by the final gate)
 *     bvHi == wpHi -> horizontal comparison
 *   Horizontal: bh = hpos>>1 (CCK)
 *     hRaw = (bh < 0xE0) ? bh+2 : bh-0xE0        offset +2, wrapping over the last
 *                                                  3 cycles of the line
 *     beamReady = (hRaw & (mask&0xFF)) >= ((waitpos & 0xFF) & (mask&0xFF))
 *
 * The +2 offset and the pipeline (FETCH -> WAIT_OR_SKIP -> WAIT1 -> WAIT2 before the first
 * evaluation) make the idiom for waiting past line 256 ("WAIT VP=255, high HP" followed by
 * "WAIT low VP") work WITHOUT any ad-hoc guard: when WAIT2 evaluates, the beam has already
 * crossed the line boundary and vpos has wrapped to 256 (bvHi = 0 < target). The 8-bit
 * vertical comparator (vpos & mask with mask <= 0xFF) wraps by itself.
 *
 * BFD (bit 15 of IR2)
 *   1 -> ignore the Blitter.
 *   0 -> also wait for the Blitter to finish (the final gate is always evaluated).
 *
 * REGISTER PROTECTION
 *   $000-$03F: always forbidden (OCS: even with CDANG; ECS: allowed with CDANG = 1)
 *   $040-$07F: forbidden when CDANG = 0
 * A MOVE to a forbidden register stops the Copper (DORMANT).
 *
 * PC FOLLOWING
 * A write to COP1LC/COP2LC updates the PC AT ONCE only while the Copper has not yet been
 * active in the frame (`!activeInThisFrame`) and the list written is the current one.
 * `activeInThisFrame` is set by onVBlank() (with Copper DMA on) and by COPEN being switched
 * back on (setDmaEnabled(true)). While the Copper is active, COPxLC writes leave the PC
 * alone: a COPJMP or the VBlank is needed to reload it.
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
    // Registers
    // =========================================================================

    /** COP1LC: pointer to the first Copper list (20 bits, forced even). */
    private int cop1lc = 0;

    /** COP2LC: pointer to the second Copper list. */
    private int cop2lc = 0;

    /** COPCON: bit 1 (CDANG), 1 = the Copper may access registers $040-$07F. */
    private int copcon = 0;

    /** Internal Program Counter: next word to read from the Copper list. */
    private int pc = 0;

    /** Index of the current Copper list (1 or 2). */
    private int copList = 1;

    // =========================================================================
    // Registro istruzioni
    // =========================================================================

    /** IR1 (cop1ins): first word of the instruction (address or VPHP). */
    private int ir1 = 0;

    /** IR2 (cop2ins): second word of the instruction (data or VMHM/BFD). */
    private int ir2 = 0;

    // =========================================================================
    // State
    // =========================================================================

    /** CDANG: true if the Copper may access registers $040-$07F. */
    private boolean danger = false;

    /**
     * True if the current instruction is to be skipped because the previous one was a
     * SKIP whose condition held. It suppresses only the write of a MOVE (the fetches happen
     * anyway). Evaluated at the FETCH of the instruction after the SKIP; cleared in MOVE
     * (when applied) and in WAIT2.
     */
    private boolean skip = false;

    /**
     * Diagnostic flag: true if the Copper attempted a MOVE to a forbidden register.
     * Cleared at every restart and VBlank.
     */
    private boolean forbiddenMoveDetected = false;

    /**
     * True if the Copper has been active in the current frame. Governs PC following. Set by
     * onVBlank() with Copper DMA on and by setDmaEnabled(true); cleared by onVBlank() with
     * Copper DMA off.
     */
    private boolean activeInThisFrame = false;

    /** Local copy of the COPEN bit (to detect the OFF -> ON edge). */
    private boolean dmaEnabled = false;

    /** Address/value of the MOVE in progress (for the debugger/save state). */
    private int pendingWriteAddr = 0;
    private int pendingWriteData = 0;

    // =========================================================================
    // State machine
    // =========================================================================

    public enum State {
        DORMANT,        // Copper stopped (illegal MOVE, or not started yet)
        STROBE,         // initial DMA request after the strobe: the slot is ALLOCATED, the data discarded
        REQ_DMA,        // waits for a free even slot, then FETCH
        FETCH,          // COP_FETCH: reads IR1
        MOVE,           // COP_MOVE: reads IR2 + executes the MOVE
        WAIT_OR_SKIP,   // COP_WAIT_OR_SKIP: reads IR2 of the WAIT/SKIP instruction
        WAIT1,          // COP_WAIT1: idle cycle
        WAIT2,          // COP_WAIT2: valuta condizione
        WAKEUP,         // COP_WAKEUP: polls the comparator
        SKIP1,          // COP_SKIP1: idle cycle
        SKIP2           // COP_SKIP2: idle cycle
    }

    private State state = State.DORMANT;

    /**
     * CCKs still to skip before the next action of the state machine.
     *
     * <p>The Copper is ticked at EVERY CCK. The cadence:
     * <ul>
     *   <li><b>action completed</b>: `tickDelay = 1`, next action <b>+2</b> CCKs later; the
     *       normal cadence, which usually falls on even CCKs;</li>
     *   <li><b>bus denied</b>: `tickDelay = 0`, retried <b>+1</b> CCK later, <b>whatever
     *       the parity</b>.</li>
     * </ul>
     *
     * <p>The even cadence of the fetches is therefore a consequence of the 2-CCK delay,
     * <b>not a rule</b>: it breaks on purpose when a denial ($E0, refresh, DMA) inserts a +1.
     * The true parity constraint applies ONLY to the wakeup states (see {@link #risingEdge}).
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
        startStrobe           = 0;
        strobe                = 0;
        haltAfterRequest      = false;
        halted                = false;
        restartStarted        = false;
        pendingWriteAddr      = 0;
        pendingWriteData      = 0;
        busReq.clear();
    }

    // =========================================================================
    // Register writes (from the CPU or from DMA)
    // =========================================================================

    /**
     * Updates the Copper registers on a write on the custom bus.
     *
     * COP1LCH/L and COP2LCH/L: update the LC pointers; the PC follows only if the Copper is
     *   not active in the frame and the list is the current one.
     * COPJMP1/COPJMP2: strobes, restart the Copper from their list.
     * COPCON: updates CDANG.
     */
    public void writeRegister(int address, int value) {
        address &= 0x1FE;

        switch (address) {

            // -- COP1LC --------------------------------------------------------
            case 0x080: // COP1LCH: bits 19-16
                cop1lc = (cop1lc & 0x0000FFFF) | ((value << 16) & agnus.getDmaPtrHiMask());
                if (!activeInThisFrame && copList == 1) setPC(cop1lc);
                break;

            case 0x082: // COP1LCL: bits 15-0 (forced even)
                cop1lc = (cop1lc & agnus.getDmaPtrHiMask()) | (value & 0xFFFE);
                if (!activeInThisFrame && copList == 1) setPC(cop1lc);
                break;

            // -- COP2LC --------------------------------------------------------
            case 0x084: // COP2LCH: bits 19-16
                cop2lc = (cop2lc & 0x0000FFFF) | ((value << 16) & agnus.getDmaPtrHiMask());
                if (!activeInThisFrame && copList == 2) setPC(cop2lc);
                break;

            case 0x086: // COP2LCL: bits 15-0 (forced even)
                cop2lc = (cop2lc & agnus.getDmaPtrHiMask()) | (value & 0xFFFE);
                if (!activeInThisFrame && copList == 2) setPC(cop2lc);
                break;

            // -- COPJMP (strobe) -----------------------------------------------
            case 0x088: // COPJMP1
                armStrobe(1);
                break;

            case 0x08A: // COPJMP2
                armStrobe(2);
                break;

            // -- COPCON --------------------------------------------------------
            case 0x02E: // COPCON: bit 1 = CDANG
                copcon = value & 0x0002;
                danger = (copcon & 0x0002) != 0;
                break;
        }
    }

    /** Sets the Copper's PC. */
    private void setPC(int address) {
        pc = address & (agnus.getDmaPtrMask() & ~1);
    }

    // =========================================================================
    // Strobe pipeline
    // =========================================================================
    //
    // A restart travels through a register of four-bit stages, one stage per CCK: in each
    // stage bit 3 stops the machine and bits 0-1 select the list. The stop comes one CCK
    // before the list, the list is held in `strobe` until the first Copper cycle of the
    // restart reads it, and the first real fetch after that cycle discards whatever strobe
    // is still held. An access to COPJMPx enters one stage later than the frame restart,
    // and stages that meet are merged: a strobe that lands just after the frame restart has
    // taken its list is lost, one that lands later restarts the machine again.

    /** Stages of the pending restarts, lowest stage first. */
    private int startStrobe = 0;

    /** Lists selected by the last stage, held for the restart's first Copper cycle. */
    private int strobe = 0;

    /** A stop stage was reached: the machine stops after this CCK's request. */
    private boolean haltAfterRequest = false;

    /** The machine is stopped and waits for a strobe. */
    private boolean halted = false;

    /** The restart's first cycle has been read; its first fetch has not started yet. */
    private boolean restartStarted = false;

    private static final int STROBE_STOP = 8;

    /** COPJMPx accessed by the CPU, or written by a Copper MOVE. */
    private void armStrobe(int list) {
        startStrobe |= (STROBE_STOP | list << 4) << 4;
    }

    /** Restart of list 1 at the start of the frame. */
    public void armFrameStrobe() {
        startStrobe |= STROBE_STOP | 1 << 4;
    }

    /**
     * Advances the strobe pipeline by one stage. Called by Agnus once per CCK, before the
     * Copper cycle of that CCK and whether or not COPEN is set.
     *
     * @param ticking the Copper runs in this CCK
     * @param slot    a Copper cycle may land on this CCK
     */
    public void advanceStrobe(boolean ticking, boolean slot) {
        if (restartStarted && ticking && slot && state == State.FETCH && tickDelay == 0) {
            restartStarted = false;
            strobe = 0;
        }
        if (startStrobe == 0) {
            return;
        }
        if ((startStrobe & STROBE_STOP) != 0) {
            startStrobe &= ~STROBE_STOP;
            haltAfterRequest = true;
            restartStarted = false;
        }
        if ((startStrobe & 0xF) != 0) {
            strobe = startStrobe;
        }
        startStrobe >>>= 4;
    }

    /** Enters the restart: the next Copper cycle is allocated and its data discarded. */
    private void enterRestart() {
        final int list = strobe & 3;
        copList = list == 2 ? 2 : 1;
        setPC(list == 3 ? (cop1lc | cop2lc) : list == 2 ? cop2lc : cop1lc);
        state                 = State.STROBE;
        tickDelay             = 0;
        skip                  = false;
        forbiddenMoveDetected = false;
        haltAfterRequest      = false;
        halted                = false;
        busReq.clear();
    }

    /**
     * Called by Agnus at every VBlank: PC-following tracks the Copper DMA state. The restart
     * from list 1 goes through the strobe pipeline, see {@link #armFrameStrobe()}.
     */
    public void onVBlank(boolean copperEnabled) {
        activeInThisFrame = copperEnabled;
    }

    /**
     * Called by Agnus at every change of the COPEN bit in DMACON.
     *
     * COPEN off/on is a pure pause: the Copper keeps its state and PC and resumes where it
     * stopped. Switching it back on does NOT restart the list: the only effect is to mark
     * activeInThisFrame = true. The Copper's tick is gated by the arbitration on
     * isCopperEnabled(): with COPEN off the Copper is simply not ticked.
     */
    public void setDmaEnabled(boolean enabled) {
        boolean old = this.dmaEnabled;
        this.dmaEnabled = enabled;
        if (enabled) {
            // Switching COPEN on makes the Copper active in this frame.
            activeInThisFrame = true;
        } else if (old) {
            busReq.clear();
        }
    }

    // =========================================================================
    // Tick: clock edges
    // =========================================================================

    /**
     * Rising edge, called at EVERY CCK: serves the current state (one transition).
     *
     * The memory-read states (FETCH/MOVE/WAIT_OR_SKIP) assert the bus request: the grant is
     * decided by Agnus's arbitration and the word read is consumed in {@link #fallingEdge()}.
     * The idle states (REQ_DMA/WAIT1/WAIT2/WAKEUP/SKIP1/SKIP2) advance here according to
     * busFree, with no bus transaction.
     *
     * @param busFree  slot free for the Copper (no higher-priority DMA and not $E0)
     * @param evenCck  even CCK: constrains ONLY the wakeup states (REQ_DMA and WAKEUP); the
     *                 read states have no parity constraint
     */
    public void risingEdge(boolean busFree, boolean evenCck) {
        risingEdge(busFree, evenCck, false);
    }

    /**
     * @param slot      a Copper cycle may land on this CCK: every state of the machine
     *                  advances only on these CCKs
     * @param wrapDummy the CCK of the line wrap on which a pending request still drives a
     *                  bus cycle, although no Copper cycle can land there
     */
    public void risingEdge(boolean busFree, boolean slot, boolean wrapDummy) {
        // A held strobe takes the first Copper slot, whatever the machine is doing.
        if (strobe != 0 && slot) {
            enterRestart();
        }
        if (!halted) {
            step(busFree, slot, wrapDummy);
        }
        if (haltAfterRequest) {
            haltAfterRequest = false;
            halted = true;
        }
    }

    private void step(boolean busFree, boolean slot, boolean wrapDummy) {
        final boolean evenCck = slot;
        if (state == State.DORMANT) return;

        // Skip the waiting CCKs.
        if (tickDelay > 0) {
            tickDelay--;
            return;
        }

        // A read state still asserts its request on the wrap CCK, where it is denied;
        // every other transition waits for a Copper slot.
        final boolean readState = state == State.FETCH || state == State.MOVE
                || state == State.WAIT_OR_SKIP;
        if (!slot && !(readState && wrapDummy)) return;

        switch (state) {

            case DORMANT:
                return;

            case REQ_DMA:
                // Denied -> retry at +1. Odd h -> retry at +1 (no wakeup on an odd cycle).
                // Otherwise FETCH at +2.
                if (!busFree) return;
                if (!evenCck) return;
                state = State.FETCH;
                tickDelay = 1;
                return;

            case STROBE:
                // Initial DMA request after the restart strobe: one Copper slot ALLOCATED
                // (it blocks the CPU) before the first real fetch, on even CCKs only; the
                // data fetched is discarded and the PC does not advance. Measured on the
                // VBL restart of bbusy0: the block of v0 is 33 slots (1 strobe + 16
                // instructions); a restart straight into FETCH made 32, freeing the CPU one
                // slot early.
                if (!evenCck) return;
                requestFetch();
                return;

            case WAKEUP:
                // First free EVEN slot at which the comparator (beam + Blitter) is true:
                // the fetch resumes.
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
                // WAIT2: clears skip, then (bus free) evaluates.
                skip = false;
                if (!busFree) return;
                if (isConditionMet()) {
                    // Immediate match: straight to FETCH.
                    state = State.FETCH;
                } else {
                    // Otherwise wait (polling in WAKEUP).
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

            case FETCH:
            case MOVE:
            case WAIT_OR_SKIP:
                // Memory-read states: they assert the bus request, with no parity
                // constraint. If the grant does not come (bus busy / $E0), `tickDelay` stays
                // 0 and the request is asserted again at the NEXT CCK (+1), even an ODD one.
                // fallingEdge() puts the +2 between two completed fetches.
                requestFetch();
                return;
        }
    }

    /**
     * Falling edge: consumes the word read and advances the read states. The other states
     * have already advanced in {@link #risingEdge(boolean)}.
     */
    public void fallingEdge() {
        // A cycle requested just before the machine stopped is read without effect.
        if (halted) {
            busReq.clear();
            return;
        }
        if (state == State.STROBE || state == State.FETCH || state == State.MOVE
                || state == State.WAIT_OR_SKIP) {
            if (busResp.ack && busReq.as && busReq.read) {
                int data = busResp.dataIn & 0xFFFF;
                switch (state) {
                    case STROBE:                                  // data discarded, PC held
                        state = State.FETCH;
                        strobe = 0;
                        restartStarted = true;
                        break;
                    case FETCH:        onFetch(data);      break;
                    case MOVE:         onMove(data);       break;
                    case WAIT_OR_SKIP: onWaitOrSkip(data); break;
                    default: break;
                }
                // Bus cycle COMPLETED: the next state starts 2 CCKs later.
                tickDelay = 1;
            }
            // With NO ack (bus busy / $E0) the state is unchanged and `tickDelay` stays 0:
            // the access is retried at the NEXT CCK, even an odd one. That is the BLOCKED
            // case of $E0 = 224, where the fetch is redone at 225 rather than at 226/0,
            // which would steal 2 CCKs from the CPU (docs/vamigats-cpu-timing-plan.md, S2).
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
    // Handlers of the read states
    // =========================================================================

    /** COP_FETCH: evaluates the skip flag (previous instruction), reads IR1. */
    private void onFetch(int data) {
        // If the instruction CURRENTLY in the registers was a SKIP, the skip flag is
        // computed now, at the fetch position of the next instruction.
        if (isSkipCmd()) {
            skip = isConditionMet();
        }

        ir1 = data;
        pc += 2;

        // Fork on the instruction type: MOVE (IR1 even) or WAIT/SKIP.
        state = isMoveCmd() ? State.MOVE : State.WAIT_OR_SKIP;
    }

    /** COP_MOVE: reads IR2 and executes the MOVE (or COPJMP / illegal). */
    private void onMove(int data) {
        ir2 = data;
        pc += 2;

        int reg = ir1 & 0x1FE;

        // Illegal register: the Copper stops.
        if (isIllegalAddress(reg)) {
            forbiddenMoveDetected = true;
            state = State.DORMANT;
            return;
        }

        // Next instruction by default.
        state = State.FETCH;

        // If this MOVE was skipped by a previous SKIP, the write is suppressed (the
        // fetches have already happened).
        if (skip) {
            skip = false;
            return;
        }

        // A MOVE to COPJMPx strobes like any other access: the machine goes on with the
        // next fetch until the strobe pipeline stops it.
        if (reg == 0x088) { armStrobe(1); return; }
        if (reg == 0x08A) { armStrobe(2); return; }

        // Custom register write (inside Agnus, no extra bus cycle).
        pendingWriteAddr = reg;
        pendingWriteData = ir2;
        agnus.copperWrite(reg, ir2);
    }

    /** COP_WAIT_OR_SKIP: reads IR2, then forks to WAIT1 / SKIP1. */
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
    // Instruction type decoding
    // =========================================================================

    private boolean isMoveCmd() { return (ir1 & 1) == 0; }
    private boolean isWaitCmd() { return (ir1 & 1) != 0 && (ir2 & 1) == 0; }
    private boolean isSkipCmd() { return (ir1 & 1) != 0 && (ir2 & 1) != 0; }

    /**
     * True if the address is forbidden to the Copper:
     *   OCS: CDANG -> addr < $40 forbidden; !CDANG -> addr < $80 forbidden.
     *   ECS: CDANG -> nothing forbidden;     !CDANG -> addr < $80 forbidden.
     */
    private boolean isIllegalAddress(int addr) {
        if (agnus.isOCS()) {
            return danger ? addr < 0x040 : addr < 0x080;
        } else {
            return !danger && addr < 0x080;
        }
    }

    // =========================================================================
    // WAIT / SKIP comparator
    // =========================================================================

    /** Beam comparator (vertical + horizontal with +2 offset and $E0 wrap). */
    private boolean beamComparator() {
        int waitpos = ir1 & 0xFFFE;              // VPHP
        int mask    = (ir2 & 0x7FFE) | 0x8001;   // VMHM (VP7 and bit0 forced)

        // -- Vertical ----------------------------------------------------------
        int hi   = (mask >> 8) & 0xFF;           // 0x80 | VM
        int bvHi = agnus.getVpos() & hi;         // vpos & mask (wrap 8-bit implicito)
        int wpHi = ((waitpos & mask) >> 8) & 0xFF;
        if (bvHi < wpHi) return false;
        if (bvHi > wpHi) return true;

        // -- Horizontal (bvHi == wpHi) -----------------------------------------
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

    // Getters needed by the debugger and save state
    public int getCop1lc()           { return cop1lc; }
    public int getCop2lc()           { return cop2lc; }
    public int getCopcon()           { return copcon; }
    public int getCopList()          { return copList; }
    public int getPendingWriteAddr() { return pendingWriteAddr; }
    public int getPendingWriteData() { return pendingWriteData; }

    public boolean isForbiddenMoveDetected()    { return forbiddenMoveDetected; }
    public void    clearForbiddenMoveDetected() { forbiddenMoveDetected = false; }
}
