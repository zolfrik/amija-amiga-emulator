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

/**
 * Blitter (Block Image Transferrer) of the Amiga 500.
 *
 * <p>The copy blit is a <b>micro-program</b> engine (docs/agnus-timing-refactor-plan.md
 * sections 9 and 10). Each CCK executes one micro-op (bitmask
 * {@code BUSIDLE/BUS/WRITE_D/FETCH_x/HOLD_x/FILL/BLTDONE/REPEAT}); the table
 * {@link #COPY_PROG}{@code [USE][fill][step]} reproduces Table 6.2 of the HRM (with D
 * <b>pipelined</b> by one word through {@code HOLD_D}/{@link #lockD}, and the dead "--"
 * cycles). Execution is single-phase: the memory access happens synchronously through
 * {@link AbstractAgnus#blitterImmediateRead}/{@code blitterImmediateWrite} when the bus
 * arbitration grants the CCK ({@link #stepCck(boolean)}).
 *
 * <p>Line mode runs on a micro-program generated per blit ({@link #buildLineProg}), with the
 * Bresenham stepping distributed over its slots.
 *
 * @author alessio.carotenuto
 */
public final class Blitter {

    // =========================================================================
    // Dipendenze
    // =========================================================================

    private final AbstractAgnus agnus;

    // =========================================================================
    // Registers
    // =========================================================================

    private int bltcon0;
    private int bltcon1;
    private int bltafwm;
    private int bltalwm;

    // DMA pointers. Their width is NOT fixed: like every Agnus DMA channel it is that of
    // the installed Chip RAM (see ptrMask/ptrHiMask), up to 21 bits.
    private int bltapt;
    private int bltbpt;
    private int bltcpt;
    private int bltdpt;

    // Modulos (signed 16 bit)
    private int bltamod, bltbmod, bltcmod, bltdmod;

    /**
     * The last cycle stepCck() spent on the bus: whether it was a copy-mode channel transfer, and
     * the modulo of that channel as the address logic adds it (negated when descending).
     */
    private boolean lastCopyChannel;
    private int lastChannelModulo;
    /** Whether the last copy-mode step was an idle cycle: a bus request that transfers nothing. */
    private boolean lastIdleCycle;

    // Dimensioni blocco
    private int bltsizeH, bltsizeV;

    // Pipeline registers
    private int anew, bnew, aold, bold, ahold, bhold, chold, dhold;

    private final byte[][][] fillPattern    = new byte[2][2][256];
    private final byte[][] nextCarryIn      = new byte[2][256];

    // =========================================================================
    // Costanti bit BLTCON0 / BLTCON1
    // =========================================================================

    private static final int BLTCON0_ASH  = 0xF000;
    private static final int BLTCON0_USEA = 0x0800;
    private static final int BLTCON0_USEB = 0x0400;
    private static final int BLTCON0_USEC = 0x0200;
    // USED (0x0100) does not appear: channel D is never queried with isUsed(); its
    // presence is already encoded in the COPY_PROG micro-programs, indexed by the four
    // USEx bits of BLTCON0.
    private static final int BLTCON0_LF   = 0x00FF;

    private static final int BLTCON1_BSH     = 0xF000;
    private static final int BLTCON1_EFE     = 0x0010;
    private static final int BLTCON1_IFE     = 0x0008;
    private static final int BLTCON1_FCI     = 0x0004;
    private static final int BLTCON1_DESC    = 0x0002;
    private static final int BLTCON1_LINE    = 0x0001;

    private static final int BLTCON1_SIGN    = 0x0040;
    /** X step pending at the start of the blit (BLTCON1 bit 5). */
    private static final int BLTCON1_OVF     = 0x0020;
    private static final int BLTCON1_SUD     = 0x0010;
    private static final int BLTCON1_SUL     = 0x0008;
    private static final int BLTCON1_AUL     = 0x0004;
    private static final int BLTCON1_SING    = 0x0002;

    // =========================================================================
    // Copy blitter micro-ops
    // =========================================================================

    private static final int NOTHING = 0x0000;
    private static final int BUSIDLE = 0x0001;
    private static final int BUS     = 0x0002;
    private static final int WRITE_D = 0x0004;
    private static final int FETCH_A = 0x0008;
    private static final int FETCH_B = 0x0010;
    private static final int FETCH_C = 0x0020;
    private static final int HOLD_A  = 0x0040;
    private static final int HOLD_B  = 0x0080;
    private static final int HOLD_D  = 0x0100;
    private static final int FILL    = 0x0200;
    private static final int BLTDONE = 0x0400;
    private static final int REPEAT  = 0x0800;
    /** LINE: channel A slot (074): pipe cycle WITHOUT a transfer, never on the bus (idle). */
    private static final int LINE_BUS_A    = 0x1000;
    /** LINE: last bus cycle of the pixel; on the last pixel it arms the BBUSY clear. */
    private static final int LINE_LAST_BUS = 0x2000;
    /**
     * LINE: read of B that does NOT advance BLTBPT. In line mode the pointer has no
     * auto-increment, and the modulo is added only by the pass that applies it, i.e. the
     * LAST one ({@code hcounter + 1 == hblitsize}). A pixel therefore reads B
     * {@code hblitsize} times, all from the SAME address, and the modulo is applied once.
     */
    private static final int FETCH_B_NOMOD = 0x4000;
    /**
     * LINE: slot A of the FIRST pass of the pixel. There the state of the pixel is decided
     * ({@link #lineProcStatus}) and the Bresenham accumulator advances
     * ({@link #lineProcApt}). It exists only with USEA: without it that slot is idle and
     * neither runs.
     */
    private static final int LINE_PROC_APT   = 0x00008000;
    /**
     * LINE: slot C of the FIRST pass. The X step is applied <b>after</b> the read, so the
     * later passes read C from the address already moved.
     */
    private static final int LINE_PROC_CPT_X = 0x00010000;
    /**
     * LINE: slot C of a MIDDLE pass. The Y step is applied <b>before</b> the read, only once
     * per pixel and only with SUD off; otherwise it stays at the free slot of the last pass.
     */
    private static final int LINE_PROC_CPT_Y = 0x00020000;
    private static final int FETCH   = FETCH_A | FETCH_B | FETCH_C;

    /**
     * Copy micro-programs (full execution), indexed {@code [USE 0..15][fill 0..1][step 0..5]}.
     * Derived from Table 6.2 of the HRM and the 1985 errata. D is pipelined by one word
     * (HOLD_D computes dhold of the previous word, WRITE_D writes it). The four fill cases
     * with a different timing (USE=$1/$5/$9/$D) have a 6-step sequence.
     */
    private static final int[][][] COPY_PROG = new int[16][2][];
    static {
        // 0
        COPY_PROG[0x0][0] = new int[]{ BUSIDLE, BUSIDLE|REPEAT, NOTHING, BLTDONE, BLTDONE, BLTDONE };
        COPY_PROG[0x0][1] = new int[]{ BUSIDLE, BUSIDLE|REPEAT, NOTHING, BLTDONE, BLTDONE, BLTDONE };
        // 1
        COPY_PROG[0x1][0] = new int[]{ HOLD_D|BUSIDLE, WRITE_D|HOLD_A|REPEAT, HOLD_D, WRITE_D|BLTDONE, BLTDONE, BLTDONE };
        COPY_PROG[0x1][1] = new int[]{ FILL|HOLD_D|BUSIDLE, WRITE_D, BUSIDLE|HOLD_A|REPEAT, FILL|HOLD_D, WRITE_D|BLTDONE, BLTDONE };
        // 2
        COPY_PROG[0x2][0] = new int[]{ HOLD_D|BUSIDLE, FETCH_C|HOLD_A|REPEAT, HOLD_D, BLTDONE, BLTDONE, BLTDONE };
        COPY_PROG[0x2][1] = new int[]{ FILL|HOLD_D|BUSIDLE, FETCH_C|HOLD_A|REPEAT, FILL|HOLD_D, BLTDONE, BLTDONE, BLTDONE };
        // 3
        COPY_PROG[0x3][0] = new int[]{ HOLD_D|BUSIDLE, FETCH_C|HOLD_A, WRITE_D|REPEAT, HOLD_D, WRITE_D|BLTDONE, BLTDONE };
        COPY_PROG[0x3][1] = new int[]{ FILL|HOLD_D|BUSIDLE, FETCH_C|HOLD_A, WRITE_D|REPEAT, FILL|HOLD_D, WRITE_D|BLTDONE, BLTDONE };
        // 4
        COPY_PROG[0x4][0] = new int[]{ HOLD_D|BUSIDLE, FETCH_B|HOLD_A, HOLD_B|BUSIDLE|REPEAT, HOLD_D, BLTDONE, BLTDONE };
        COPY_PROG[0x4][1] = new int[]{ FILL|HOLD_D|BUSIDLE, FETCH_B|HOLD_A, HOLD_B|BUSIDLE|REPEAT, FILL|HOLD_D, BLTDONE, BLTDONE };
        // 5
        COPY_PROG[0x5][0] = new int[]{ BUSIDLE|HOLD_D, FETCH_B|HOLD_A, WRITE_D|HOLD_B|REPEAT, HOLD_D, WRITE_D|BLTDONE, BLTDONE };
        COPY_PROG[0x5][1] = new int[]{ BUSIDLE|FILL|HOLD_D, FETCH_B|HOLD_A, WRITE_D|HOLD_B, BUSIDLE|REPEAT, FILL|HOLD_D, WRITE_D|BLTDONE };
        // 6
        COPY_PROG[0x6][0] = new int[]{ BUSIDLE|HOLD_D, FETCH_B|HOLD_A, FETCH_C|HOLD_B|REPEAT, HOLD_D, BLTDONE, BLTDONE };
        COPY_PROG[0x6][1] = new int[]{ BUSIDLE|FILL|HOLD_D, FETCH_B|HOLD_A, FETCH_C|HOLD_B|REPEAT, FILL|HOLD_D, BLTDONE, BLTDONE };
        // 7
        COPY_PROG[0x7][0] = new int[]{ BUSIDLE|HOLD_D, FETCH_B|HOLD_A, FETCH_C|HOLD_B, WRITE_D|REPEAT, HOLD_D, WRITE_D|BLTDONE };
        COPY_PROG[0x7][1] = new int[]{ BUSIDLE|FILL|HOLD_D, FETCH_B|HOLD_A, FETCH_C|HOLD_B, WRITE_D|REPEAT, FILL|HOLD_D, WRITE_D|BLTDONE };
        // 8
        COPY_PROG[0x8][0] = new int[]{ FETCH_A|HOLD_D, HOLD_A|BUSIDLE|REPEAT, HOLD_D, BLTDONE, BLTDONE, BLTDONE };
        COPY_PROG[0x8][1] = new int[]{ FETCH_A|FILL|HOLD_D, HOLD_A|BUSIDLE|REPEAT, FILL|HOLD_D, BLTDONE, BLTDONE, BLTDONE };
        // 9
        COPY_PROG[0x9][0] = new int[]{ FETCH_A|HOLD_D, WRITE_D|HOLD_A|REPEAT, HOLD_D, WRITE_D|BLTDONE, BLTDONE, BLTDONE };
        COPY_PROG[0x9][1] = new int[]{ FETCH_A|FILL|HOLD_D, WRITE_D|HOLD_A, BUSIDLE|REPEAT, FILL|HOLD_D, WRITE_D|BLTDONE, BLTDONE };
        // A
        COPY_PROG[0xA][0] = new int[]{ FETCH_A|HOLD_D, FETCH_C|HOLD_A|REPEAT, HOLD_D, BLTDONE, BLTDONE, BLTDONE };
        COPY_PROG[0xA][1] = new int[]{ FETCH_A|FILL|HOLD_D, FETCH_C|HOLD_A|REPEAT, FILL|HOLD_D, BLTDONE, BLTDONE, BLTDONE };
        // B
        COPY_PROG[0xB][0] = new int[]{ FETCH_A|HOLD_D, FETCH_C|HOLD_A, WRITE_D|REPEAT, HOLD_D, WRITE_D|BLTDONE, BLTDONE };
        COPY_PROG[0xB][1] = new int[]{ FETCH_A|FILL|HOLD_D, FETCH_C|HOLD_A, WRITE_D|REPEAT, FILL|HOLD_D, WRITE_D|BLTDONE, BLTDONE };
        // C
        COPY_PROG[0xC][0] = new int[]{ FETCH_A|HOLD_D, FETCH_B|HOLD_A, HOLD_B|BUSIDLE|REPEAT, HOLD_D, BLTDONE, BLTDONE };
        COPY_PROG[0xC][1] = new int[]{ FETCH_A|FILL|HOLD_D, FETCH_B|HOLD_A, HOLD_B|BUSIDLE|REPEAT, FILL|HOLD_D, BLTDONE, BLTDONE };
        // D
        COPY_PROG[0xD][0] = new int[]{ FETCH_A|HOLD_D, FETCH_B|HOLD_A, WRITE_D|HOLD_B|REPEAT, HOLD_D, WRITE_D|BLTDONE, BLTDONE };
        COPY_PROG[0xD][1] = new int[]{ FETCH_A|FILL|HOLD_D, FETCH_B|HOLD_A, WRITE_D|HOLD_B, BUSIDLE|REPEAT, FILL|HOLD_D, WRITE_D|BLTDONE };
        // E
        COPY_PROG[0xE][0] = new int[]{ FETCH_A|HOLD_D, FETCH_B|HOLD_A, FETCH_C|HOLD_B|REPEAT, HOLD_D, BLTDONE, BLTDONE };
        COPY_PROG[0xE][1] = new int[]{ FETCH_A|FILL|HOLD_D, FETCH_B|HOLD_A, FETCH_C|HOLD_B|REPEAT, FILL|HOLD_D, BLTDONE, BLTDONE };
        // F
        COPY_PROG[0xF][0] = new int[]{ FETCH_A|HOLD_D, FETCH_B|HOLD_A, FETCH_C|HOLD_B, WRITE_D|REPEAT, HOLD_D, WRITE_D|BLTDONE };
        COPY_PROG[0xF][1] = new int[]{ FETCH_A|FILL|HOLD_D, FETCH_B|HOLD_A, FETCH_C|HOLD_B, WRITE_D|REPEAT, HOLD_D, WRITE_D|BLTDONE };
    }

    /**
     * LINE micro-program, <b>generated per blit</b> from (USEA, USEB, USEC, {@code bltsizeH}).
     *
     * <p>A <b>pass</b> of the shifter takes <b>2 CCKs</b> without B and <b>3</b> with B, and
     * a <b>line pixel takes {@code hblitsize} passes</b> (the horizontal counter counts up to
     * {@code hblitsize}, then the vertical counter advances).
     *
     * <p>Slot roles, with {@code lastw = hcounter + 1 == hblitsize}:
     * <ul>
     *   <li><b>stage 0</b>: {@code lastw} -> the "free" slot (adat/ovf/sign/nxline and the
     *       minterm, i.e. our {@code HOLD_D}); otherwise slot <b>A</b> with USEA, else idle.
     *       Slot A NEVER allocates the bus: in line mode BLTAPT is the Bresenham
     *       accumulator, not a pointer;</li>
     *   <li><b>stage 1</b> (only with USEB): read of <b>B</b> in <b>every</b> pass, all from
     *       the same address; only the last adds the modulo ({@link #FETCH_B_NOMOD});</li>
     *   <li><b>stage 2</b>: {@code lastw} -> write of <b>D</b> with USEC, else idle;
     *       otherwise read of <b>C</b> with USEC, else idle.</li>
     * </ul>
     *
     * <p>Measured sequences: {@code timing15l} (B+C, w=2) -> <b>[A B C][free B D]</b>;
     * {@code timing15l1} (w=1) -> <b>[free B D]</b>; {@code timing15l3} (w=3) ->
     * <b>[A B C][A B C][free B D]</b>; {@code timing11l1} (C without B, w=1) ->
     * <b>[free D]</b>. All from runs byte-identical to the reference.
     */
    private int[] lineProg = null;

    private int[] buildLineProg() {
        boolean useA = isUsed(BLTCON0_USEA);
        boolean useB = isUsed(BLTCON0_USEB);
        boolean useC = isUsed(BLTCON0_USEC);
        int passes  = Math.max(1, bltsizeH);
        int passLen = useB ? 3 : 2;
        int[] prog  = new int[passes * passLen];

        for (int p = 0, i = 0; p < passes; p++) {
            boolean lastw = (p == passes - 1);
            boolean first = (p == 0);
            int stage0 = lastw ? (HOLD_D | BUSIDLE) : (useA ? LINE_BUS_A : BUSIDLE);
            if (first && !lastw && useA) stage0 |= LINE_PROC_APT;
            prog[i++] = stage0;
            if (useB) prog[i++] = lastw ? FETCH_B : FETCH_B_NOMOD;
            // D also carries BUSIDLE: when the pixel is not written (`lineWrite` false) the
            // slot is still a cycle that WAITS for the bus. Without it, it would be the only
            // slot of the program to advance under contention, and the pixel would get
            // shorter (measured on bbusy7l).
            int stage2 = lastw ? (useC ? (WRITE_D | BUSIDLE) : BUSIDLE)
                               : (useC ? FETCH_C : BUSIDLE);
            // Only with USEC: without it there is no C slot at all, so the two steps do not
            // run, and indeed without USEC the line is not drawn.
            if (!lastw && useC) stage2 |= first ? LINE_PROC_CPT_X : LINE_PROC_CPT_Y;
            prog[i++] = stage2;
        }

        // LINE_LAST_BUS marks the LAST bus cycle of the pixel: on the last pixel it arms the
        // BBUSY clear (deferred by one CCK). With USEC it is the D slot, otherwise the B read
        // of the last pass; with neither B nor C there is no bus cycle and the mark falls on
        // the REPEAT slot (synchronous clear). The rule is the one measured by the DMACONR
        // polls of bbusy7l/9l/13l. Marking the second-to-last slot instead is one CCK early:
        // it turns timing11l/15l and irqtim11l/15l red.
        prog[(!useC && useB) ? prog.length - 2 : prog.length - 1] |= LINE_LAST_BUS;
        prog[prog.length - 1] |= REPEAT;
        return prog;
    }

    // =========================================================================
    // State
    // =========================================================================

    // States used by line mode ONLY (copy mode is driven by bltpc + COPY_PROG).
    public enum State { IDLE, FETCH_A, FETCH_B, FETCH_C, WRITE_D, INTERNAL }
    private State state = State.IDLE;

    // running: the blit is in progress (drives the ticking from Agnus). It stays true until
    //          endBlit(), i.e. also during the drain of the D pipeline.
    // bbusy:   the BBUSY flag of DMACON. It clears a few cycles BEFORE the actual end, in
    //          the last REPEAT; it wakes a Copper waiting with BFD at the right moment.
    private boolean running = false;
    private boolean bbusy   = false;

    /**
     * CCK at which {@link #bbusy} went from on to off. {@code Long.MIN_VALUE} = never.
     *
     * <p>Used by the view of the <b>COPPER</b>, which is NOT that of the CPU: <b>the Blitter
     * reads busy to the Copper for one CCK longer than to the CPU</b>, and that is where a
     * WAIT with BFD is woken.
     *
     * <p>The CPU's view ({@link #isBusy}) must not be touched: it is calibrated on the polls
     * of {@code bbusy0} and frozen by {@code BbusyTest} (17/17).
     */
    private long busyClearedCck = Long.MIN_VALUE;

    /**
     * Delay between the <b>end of the blit</b> and bit 6 of INTREQ.
     *
     * <p>Measured at <b>4 CCKs</b> from the end of the blit, three times on {@code irqtim0}.
     *
     * <p><b>It is 3 here, and the CCK of difference is NOT a calibration</b>: it is the
     * instant the count starts from. Our BBUSY edge falls <b>one CCK after</b> the end of the
     * blit (the flag is already down when the CPU looks), and that offset is what makes the
     * DMACONR read right (frozen by {@code BbusyTest} 17/17). Counted from our edge, the +4 is
     * a +3. See docs/vamigats-import-order.md section 24.
     */
    private static final int BLT_IRQ_DELAY_CCK = 3;

    /** CCKs left before INTREQ bit 6 is raised; 0 = no interrupt in flight. */
    private int irqCountdown = 0;

    /**
     * Arms the Blitter interrupt at the instant the blit ends (the same one at which BBUSY
     * falls). Once per blit: {@link #birq} is the guard.
     */
    private void armBlitInterrupt() {
        if (birq) return;
        birq = true;
        irqCountdown = BLT_IRQ_DELAY_CCK;
    }

    /** The only place where BBUSY goes off: records the CCK of the edge. */
    private void clearBbusy() {
        if (bbusy && agnus != null) {
            busyClearedCck = agnus.getMasterClock() >> 1;
        }
        bbusy = false;
    }

    /**
     * The Blitter <b>as the Copper sees it</b>: busy until one CCK after the BBUSY edge.
     *
     * <p><b>The {@code + 1} is the threshold, not a margin.</b> Without it this method gives
     * the CPU's threshold, and inertly so: within a CCK the Copper is ticked BEFORE the
     * Blitter (AbstractAgnus: Copper arbitration, then {@code blitter.stepCck}), so the
     * Copper already sees {@code bbusy == true} for the whole CCK in which BBUSY falls.
     * Measured on {@code sblit/sblit0}; the {@code +1} is worth +58 tests on the
     * Agnus/Blitter bank (91/144 to 149/86), +2 on Agnus/Registers and +1 on Agnus/Copper.
     * See docs/vamigats-import-order.md section 24.
     */
    public boolean isBusyForCopper() {
        return bbusy
            || (agnus != null && (agnus.getMasterClock() >> 1) <= busyClearedCck + 1);
    }

    // "Immediate blit" mode: runs the whole blit at once at the BLTSIZE write (no bus
    // cycles emulated). Not cycle-exact; set only from amiga.properties.
    private boolean immediateMode = false;

    // BBUSY start latency: BBUSY goes on 2 CCKs after the BLTSIZE write. Countdown advanced
    // once per CCK by Agnus.
    private static final int BLT_START_LATENCY_CCK = 2;
    private int     startCountdown = 0;
    private boolean startPending   = false;

    private boolean dmaEnabled = false;
    private int fillCarry = 0;
    private boolean bzero = true;
    private boolean firstDot = true;   // line mode
    private int linePc = 0;            // current step in the LINE_PROG micro-program
    private boolean lineWrite = false; // `blitlinepixel2`: latched at the free slot
    /** `blitlinepixel`: decided by lineProcStatus(), consumed at the free slot. */
    private boolean linePixelPending = false;
    /** Pending X step, waiting for lineOvfApply() to fold it into ASH. */
    private int lineOvf = 0;
    /** The Y step of this pixel has not been applied yet. */
    private boolean lineLoop = true;
    private int lineWarmup = 0;        // idle slots after BLTSIZE before the first pixel
    private int copyWarmup = 0;        // idle slots after BLTSIZE before the first copy micro-op

    // --- Copy micro-program state ---
    private int     bltpc    = 0;      // index of the current micro-op (0..5)
    private int     xCounter = 0;      // down-counter of the words in the row (bltsizeH..1)
    private int     yCounter = 0;      // down-counter of the rows (bltsizeV..1)
    private int     cntA, cntB, cntC, cntD; // per-channel DMA down-counter (modulo)
    private int     mask     = 0xFFFF; // channel A mask for the current word
    private boolean lockD    = true;   // if true WRITE_D does not write (pipelined D)
    private boolean birq     = false;  // BLIT interrupt already scheduled

    // Contatori line-mode
    private int hCount = 0;

    // =========================================================================
    // Costruttore & Inizializzazione Tabelle Fill
    // =========================================================================

    public Blitter(AbstractAgnus agnus) {
        this.agnus   = agnus;

        for (int carryIn = 0; carryIn < 2; carryIn++) {
            for (int data = 0; data < 256; data++) {
                int carry = carryIn;
                int inclResult = 0;
                int exclResult = 0;
                for (int bit = 0; bit < 8; bit++) {
                    int dataBit = (data >>> bit) & 1;
                    inclResult |= (carry | dataBit) << bit;
                    exclResult |= (carry ^ dataBit) << bit;
                    if (dataBit != 0) carry ^= 1;
                }
                fillPattern[0][carryIn][data] = (byte) inclResult;
                fillPattern[1][carryIn][data] = (byte) exclResult;
                nextCarryIn[carryIn][data]    = (byte) carry;
            }
        }

        reset();
    }

    public void reset() {
        state = State.IDLE;
        running = false; clearBbusy(); dmaEnabled = false;
        startPending = false; startCountdown = 0; clearBusyCountdown = 0; agaDoneCountdown = 0;
        bltcon0 = 0; bltcon1 = 0;
        bltafwm = 0xFFFF; bltalwm = 0xFFFF;
        bltapt = 0; bltbpt = 0; bltcpt = 0; bltdpt = 0;
        bltamod = 0; bltbmod = 0; bltcmod = 0; bltdmod = 0;
        anew = 0; bnew = 0; aold = 0; bold = 0;
        ahold = 0; bhold = 0; chold = 0; dhold = 0;
        bzero = true; fillCarry = 0; firstDot = true;
        bltpc = 0; lockD = true; birq = false; irqCountdown = 0; copyWarmup = 0;
        xCounter = 0; yCounter = 0; cntA = cntB = cntC = cntD = 0; mask = 0xFFFF;
        recacheAreaBlitFields();
    }

    // =========================================================================
    // Register writes
    // =========================================================================

    public void writeRegister(int address, int value) {
        address &= 0x1FE;
        switch (address) {
            case 0x040: bltcon0 = value & 0xFFFF; recacheAreaBlitFields(); break;
            case 0x042: bltcon1 = value & 0xFFFF; recacheAreaBlitFields(); break;
            case 0x044: bltafwm = value & 0xFFFF; break;
            case 0x046: bltalwm = value & 0xFFFF; break;

            case 0x048: bltcpt = (bltcpt & 0x0000FFFF) | ((value << 16) & ptrHiMask()); break;
            case 0x04A: bltcpt = (bltcpt & 0xFFFF0000) | (value & 0xFFFF);         break;
            case 0x04C: bltbpt = (bltbpt & 0x0000FFFF) | ((value << 16) & ptrHiMask()); break;
            case 0x04E: bltbpt = (bltbpt & 0xFFFF0000) | (value & 0xFFFF);         break;
            case 0x050: bltapt = (bltapt & 0x0000FFFF) | ((value << 16) & ptrHiMask()); break;

            // In line mode BLTAPTL is the Bresenham error (signed, 16 bits): not forced even.
            case 0x052: bltapt = (bltapt & 0xFFFF0000) | (value & 0xFFFF);         break;

            case 0x054: bltdpt = (bltdpt & 0x0000FFFF) | ((value << 16) & ptrHiMask()); break;
            case 0x056: bltdpt = (bltdpt & 0xFFFF0000) | (value & 0xFFFF);         break;

            case 0x058:
                bltsizeV = (value >> 6) & 0x3FF;
                bltsizeH = value & 0x3F;
                if (bltsizeV == 0) bltsizeV = 1024;
                if (bltsizeH == 0) bltsizeH = 64;
                armBlit();
                break;

            // ECS registers BLTCON0L ($05A) / BLTSIZV ($05C) / BLTSIZH ($05E): blits up to
            // 32768x2048 and a write of the low byte of BLTCON0 alone. ECS only (gate
            // agnus.isECS()). $05A = BLTCON0L, $05C = BLTSIZV (V only, does NOT start),
            // $05E = BLTSIZH (sets H and STARTS).
            case 0x05A: // BLTCON0L: only the low byte (minterm LF) of BLTCON0
                if (agnus.isECS()) {
                    bltcon0 = (bltcon0 & 0xFF00) | (value & 0x00FF);
                    recacheAreaBlitFields();
                }
                break;
            case 0x05C: // BLTSIZV (15 bits): sets V only, does NOT start
                if (agnus.isECS()) bltsizeV = value & 0x7FFF;
                break;
            case 0x05E: // BLTSIZH (11 bit) + start
                if (agnus.isECS()) {
                    bltsizeH = value & 0x07FF;
                    if (bltsizeV == 0) bltsizeV = 0x8000;
                    if (bltsizeH == 0) bltsizeH = 0x0800;
                    armBlit();
                }
                break;

            case 0x060: bltcmod = (short) (value & 0xFFFE); break;
            case 0x062: bltbmod = (short) (value & 0xFFFE); break;
            case 0x064: bltamod = (short) (value & 0xFFFE); break;
            case 0x066: bltdmod = (short) (value & 0xFFFE); break;

            case 0x070: chold = value & 0xFFFF; break;
            case 0x072:
                // "Channel B feature": writing BLTBDAT runs B's barrel shifter AT ONCE, and
                // the result stays in B-HOLD as static data for later blits with channel B
                // disabled. The shift is the REAL one, 32 bits over B-OLD, the same function
                // the DMA load of channel B uses:
                //
                //     bltbhold = desc ? ((v << 16) | bltbold) >> (16 - shift)
                //                     : ((bltbold << 16) | v) >> shift;
                //     bltbold  = v;
                //
                // So "after shift B Old equals B New": a SECOND write to BLTBDAT changes
                // BHOLD again, because it finds B-OLD already updated. That is what
                // misc/barrel1 and undocumented1 measure.
                bnew  = value & 0xFFFF;
                bhold = barrelShifter(bnew, bold, (bltcon1 & BLTCON1_BSH) >>> 12,
                                      (bltcon1 & BLTCON1_DESC) != 0);
                bold  = bnew;
                break;
            case 0x074:
                anew = value & 0xFFFF;
                break;
        }
    }

    // =========================================================================
    // Cached area-blit invariants (re-extracted at every BLTCON0/1 write)
    // =========================================================================

    private boolean cachedDesc;
    private int     cachedAsh;
    private int     cachedBsh;
    private int     cachedMinterm;

    private void recacheAreaBlitFields() {
        cachedDesc    = (bltcon1 & BLTCON1_DESC) != 0;
        cachedAsh     = (bltcon0 & BLTCON0_ASH) >>> 12;
        cachedBsh     = (bltcon1 & BLTCON1_BSH) >>> 12;
        cachedMinterm = bltcon0 & BLTCON0_LF;
    }

    // =========================================================================
    // Armamento / avvio
    // =========================================================================

    /**
     * Arms the blit at the BLTSIZE write: it does NOT start at once (BBUSY at write+0), the
     * start is scheduled {@link #BLT_START_LATENCY_CCK} CCKs later. Agnus advances the
     * countdown through {@link #tickStartCountdown()}.
     */
    private void armBlit() {
        // A BLTSIZE written with Blitter DMA off arms the blit all the same: BBUSY comes on
        // and the blit waits, paused, for BLTEN (see setDmaEnabled). Immediate mode runs it
        // at once only if it can do memory cycles now; otherwise it takes the normal path.
        if (immediateMode && dmaEnabled) {
            startPending = false; startCountdown = 0;
            startBlit();
            runImmediate();
            return;
        }
        startPending   = true;
        startCountdown = BLT_START_LATENCY_CCK;
    }

    /**
     * Called by Agnus once per CCK. Handles the BBUSY start latency: when the countdown
     * expires, the blit actually starts.
     */
    public void tickStartCountdown() {
        if (!startPending) return;
        if (--startCountdown <= 0) {
            startPending = false;
            startBlit();
        }
    }

    /** True between the BLTSIZE write and BBUSY going on. */
    public boolean isStarting() { return startPending; }

    private void startBlit() {
        recacheAreaBlitFields();

        running = true;
        bbusy   = true;
        bzero   = true;
        birq    = false;
        irqCountdown = 0;
        bltpc   = 0;
        clearBusyCountdown = 0;
        agaDoneCountdown = 0;
        aold = 0; bold = 0;

        if ((bltcon1 & BLTCON1_LINE) != 0) {
            // Line mode: existing data path (Bresenham). No word pipeline.
            fillCarry = 0;
            firstDot  = true;
            hCount    = bltsizeV;
            lockD     = false;
            state     = State.IDLE;
            linePc    = 0;
            lineWrite = false;
            linePixelPending = false;
            // The X step pending at the start comes from bit 5 of BLTCON1, not from zero.
            lineOvf   = (bltcon1 & BLTCON1_OVF) != 0 ? 1 : 0;
            lineLoop  = true;
            // Idle warm-up after BLTSIZE: TWO slots, always. Measured on timing15l (B+C):
            // between the start and the first slot of the pixel there are two idle entries.
            lineWarmup = 2;
            lineProg   = buildLineProg();
        } else {
            // Copy mode: motore a micro-programmi.
            xCounter = bltsizeH;
            yCounter = bltsizeV;
            cntA = cntB = cntC = cntD = bltsizeH;
            fillCarry = (bltcon1 & BLTCON1_FCI) != 0 ? 1 : 0;
            lockD = true;
            computeMask();
            // Warm-up after BLTSIZE (bbusy3 v66: BLTSIZE at h=84, idle slots at 86-87,
            // first micro-op at 88): the Blitter's RGA pipe issues 2 ARBITRATED idle slots
            // before the first step of the program. On this axis the BBUSY clear is
            // synchronous to the last REPEAT (see there).
            copyWarmup = 2;
        }
    }

    /**
     * "Immediate" execution of the blit: completes the whole operation synchronously,
     * reusing the SAME cycle-exact engine ({@link #stepCck(boolean)} with the bus always
     * free). Used only when {@link #immediateMode} is on.
     */
    private void runImmediate() {
        long guard = 0;
        long maxIters = (long) bltsizeH * bltsizeV * 8L + (long) bltsizeV * 8L + 128L;
        while (running && guard++ < maxIters) {
            tickBusyClear();
            stepCck(true);
        }
        if (running) endBlit(); // guard expired: do not leave BBUSY stuck
        if (agaDoneCountdown > 0) { agaDoneCountdown = 0; armBlitInterrupt(); }
        clearBusyCountdown = 0; clearBbusy(); // no further ticks in immediate mode
        // Immediate: no later tick, so the armed interrupt is delivered here.
        if (irqCountdown > 0) { irqCountdown = 0; agnus.blitterFinished(); }
    }

    // =========================================================================
    // Per-CCK execution (single-phase)
    // =========================================================================

    /**
     * Executes one Blitter micro-op for the current CCK.
     *
     * @param busFree true if the bus can be granted to the Blitter in this CCK
     *                (no higher-priority DMA and no CPU precedence).
     * @return true if the Blitter ALLOCATED the bus (memory access performed), so Agnus
     *         can mark the CCK as BLITTER and block the CPU.
     */
    public boolean stepCck(boolean busFree) {
        if (!running) return false;
        lastCopyChannel = false;
        lastIdleCycle = false;
        if ((bltcon1 & BLTCON1_LINE) != 0) {
            return lineStepCck(busFree);
        }
        return execMicroOp(busFree);
    }

    /** Deferred BBUSY clear, used by line mode ONLY (LINE_LAST_BUS). 0 = inactive. */
    private int clearBusyCountdown = 0;

    /**
     * CCKs left before BBUSY falls and the interrupt is armed on Alice, after the last word
     * of a blit that writes D. 0 = inactive.
     */
    private int agaDoneCountdown = 0;

    /**
     * Deferred BBUSY clear of line mode: armed to 1 at the last bus cycle of the last pixel
     * (LINE_LAST_BUS), visible to the CPU from the next CCK. Called by Agnus ONCE per CCK,
     * BEFORE the Blitter step and REGARDLESS of {@link #isRunning()} (endBlit does not
     * override it). Copy mode does not come through here: its clear is synchronous to the
     * last REPEAT (see execMicroOp).
     */
    public void tickBusyClear() {
        if (irqCountdown > 0 && --irqCountdown == 0) {
            agnus.blitterFinished();
        }
        if (agaDoneCountdown > 0 && --agaDoneCountdown == 0) {
            clearBbusy();
            armBlitInterrupt();
        }
        if (clearBusyCountdown == 1) {
            clearBusyCountdown = 0;
            clearBbusy();
            armBlitInterrupt();
        }
    }

    /** Executes the current copy micro-op. Returns true if it allocated the bus. */
    private boolean execMicroOp(boolean busFree) {
        // Warm-up after BLTSIZE (see startBlit): 2 idle slots subject to arbitration (they
        // are regular RGA requests, so under contention they slip), with no bus access.
        if (copyWarmup > 0) {
            if (!busFree) return false;
            copyWarmup--;
            lastIdleCycle = true;
            return false;
        }
        int op = COPY_PROG[(bltcon0 >>> 8) & 0xF][bltconFE()][bltpc];

        boolean needBus, needIdle;
        if ((op & WRITE_D) != 0) {
            needBus  = !lockD;
            needIdle =  lockD;
        } else {
            needBus  = (op & (FETCH | BUS)) != 0;
            needIdle = (op & BUSIDLE) != 0;
        }

        // Bus arbitration: BUS and BUSIDLE need the bus free to proceed; NOTHING micro-ops
        // ALWAYS advance (even under higher-priority DMA).
        if (needBus && !busFree)  return false; // stallo (bltpc invariato, retry)
        if (needIdle && !busFree) return false; // stallo

        boolean tookBus = false;
        lastIdleCycle = needIdle && !needBus;
        bltpc++;

        boolean desc = cachedDesc;

        if ((op & WRITE_D) != 0 && !lockD) {
            int addr = getDpt();
            agnus.blitterImmediateWrite(addr, dhold & 0xFFFF);
            tookBus = true;
            noteCopyChannel(desc ? -bltdmod : bltdmod);
            setDpt(addr + (desc ? -2 : 2));
            if (--cntD == 0) {
                setDpt(getDpt() + (desc ? -bltdmod : bltdmod));
                cntD = bltsizeH;
                fillCarry = (bltcon1 & BLTCON1_FCI) != 0 ? 1 : 0;
            }
        }

        if ((op & FETCH_A) != 0) {
            anew = agnus.blitterImmediateRead(getApt()) & 0xFFFF;
            tookBus = true;
            noteCopyChannel(desc ? -bltamod : bltamod);
            setApt(getApt() + (desc ? -2 : 2));
            if (--cntA == 0) { setApt(getApt() + (desc ? -bltamod : bltamod)); cntA = bltsizeH; }
        }
        if ((op & FETCH_B) != 0) {
            bnew = agnus.blitterImmediateRead(getBpt()) & 0xFFFF;
            tookBus = true;
            noteCopyChannel(desc ? -bltbmod : bltbmod);
            setBpt(getBpt() + (desc ? -2 : 2));
            if (--cntB == 0) { setBpt(getBpt() + (desc ? -bltbmod : bltbmod)); cntB = bltsizeH; }
        }
        if ((op & FETCH_C) != 0) {
            chold = agnus.blitterImmediateRead(getCpt()) & 0xFFFF;
            tookBus = true;
            noteCopyChannel(desc ? -bltcmod : bltcmod);
            setCpt(getCpt() + (desc ? -2 : 2));
            if (--cntC == 0) { setCpt(getCpt() + (desc ? -bltcmod : bltcmod)); cntC = bltsizeH; }
        }

        if ((op & HOLD_A) != 0) {
            int maskedAnew = anew & mask;
            ahold = barrelShifter(maskedAnew, aold, cachedAsh, desc);
            aold  = maskedAnew;
        }
        if ((op & HOLD_B) != 0) {
            bhold = barrelShifter(bnew, bold, cachedBsh, desc);
            bold  = bnew;
        }
        if ((op & HOLD_D) != 0) {
            dhold = doMintermLogic(ahold, bhold, chold, cachedMinterm);
            if (!lockD) {
                if ((op & FILL) != 0) doFillD();
                if (dhold != 0) bzero = false;
            }
        }

        if ((op & REPEAT) != 0) {
            lockD = false;
            if (xCounter > 1) {
                bltpc = 0;
                xCounter--;
                computeMask();
            } else if (yCounter > 1) {
                bltpc = 0;
                xCounter = bltsizeH;
                yCounter--;
                computeMask();
            } else {
                // Last word: BBUSY falls HERE, synchronous to the last REPEAT, and the CPU
                // sees it already in this CCK (the DMA section precedes the CPU cycle). With
                // the engine's axis moved +2 CCKs by the warm-up (see startBlit) this
                // reproduces the timeline measured by the polls of bbusy0: the clear follows
                // the (2WH+1)-th allocated slot COUNTING the warm-up and is visible one CCK
                // later; for the null W2H2 blit, 9th slot = b+10, visible at b+11 = our last
                // REPEAT at b+11. The blit goes on (D drain).
                // Alice holds BBUSY and the interrupt two CCKs longer when the blit writes D.
                if (agnus != null && agnus.isAGA() && (bltcon0 & 0x0100) != 0) {
                    agaDoneCountdown = 2;
                } else {
                    clearBbusy();
                    armBlitInterrupt();
                }
            }
        }

        if ((op & BLTDONE) != 0) {
            endBlit();
        }

        return tookBus;
    }

    private void noteCopyChannel(int modulo) {
        lastCopyChannel = true;
        lastChannelModulo = modulo;
    }

    /** Whether the last cycle on the bus was a copy-mode A, B, C or D transfer. */
    public boolean lastCycleWasCopyChannel() {
        return lastCopyChannel;
    }

    /** Whether the last step was a copy-mode idle cycle, which requests the bus but leaves it unused. */
    public boolean lastCycleWasIdle() {
        return lastIdleCycle;
    }

    /** The modulo of the channel of that transfer, sign-extended. */
    public int lastCycleChannelModulo() {
        return lastChannelModulo;
    }

    private void computeMask() {
        mask = 0xFFFF;
        if (xCounter == bltsizeH) mask &= bltafwm; // first word of the row
        if (xCounter == 1)        mask &= bltalwm; // last word of the row
    }

    private void doFillD() {
        int exclusive = (bltcon1 & BLTCON1_EFE) != 0 ? 1 : 0;
        int dataLo    = dhold & 0xFF;
        int dataHi    = (dhold >>> 8) & 0xFF;
        int resLo = fillPattern[exclusive][fillCarry][dataLo] & 0xFF;
        fillCarry = nextCarryIn[fillCarry][dataLo] & 0x1;
        int resHi = fillPattern[exclusive][fillCarry][dataHi] & 0xFF;
        fillCarry = nextCarryIn[fillCarry][dataHi] & 0x1;
        dhold = (resHi << 8) | resLo;
    }

    private void endBlit() {
        running = false;
        // BBUSY: if the clear countdown is running, do NOT override it. NOTHING/BLTDONE
        // micro-ops advance even on stolen CCKs, so under contention BLTDONE arrives BEFORE
        // the exit slot that turns BBUSY off (measured on bbusy0 v202, BPU=4). The countdown
        // is completed by tickBusyClear. Paths with no countdown (line mode, guards) clear
        // at once.
        if (clearBusyCountdown == 0 && agaDoneCountdown == 0) clearBbusy();
        state   = State.IDLE;
        // The interrupt is already armed at the BBUSY edge (armBlitInterrupt). A path that
        // gets here without arming it (line mode with the deferred clear already consumed,
        // or a guard) arms it here, from the same instant.
        if (agaDoneCountdown == 0) armBlitInterrupt();
    }

    // =========================================================================
    // Line mode (validated data path, driven single-phase)
    // =========================================================================

    /**
     * Per-CCK LINE execution driven by the micro-program {@link #buildLineProg}.
     *
     * <p>The Bresenham stepping is <b>distributed over the slots</b>, not run all at once
     * at the end of the pixel: state and accumulator at slot A of the first pass, the X step
     * at slot C of the first pass (after the read), the Y step at slot C of a middle pass
     * (before the read) or at the free slot, ASH at the free slot. That changes <i>which C
     * address is read</i> within a pixel wider than two; for widths up to 2 it is the same as
     * a single step.
     */
    private boolean lineStepCck(boolean busFree) {
        // Warm-up after BLTSIZE: idle slots before the first real cycle of the first pixel.
        if (lineWarmup > 0) {
            if (!busFree) return false;
            lineWarmup--;
            return false;
        }
        int[] prog = lineProg;
        int op = prog[linePc];

        // WRITE_D takes the bus only if it really writes (single pixel/SING skips it);
        // FETCH_B/FETCH_C/BUS always take it; BUSIDLE only needs a free bus. Slot A (074)
        // NEVER takes the bus, whatever USE: in line mode BLTAPT is the Bresenham
        // accumulator, not a pointer, so it is a pipe cycle with no transfer. Measured on
        // bbusy9l v66 (bc0, USE=A+D): a CPU access in the SAME CCK as slot 074.
        boolean needBus  = (op & (FETCH_B | FETCH_B_NOMOD | FETCH_C | BUS)) != 0
                        || ((op & WRITE_D) != 0 && lineWrite);
        boolean needIdle = !needBus && (op & (BUSIDLE | LINE_BUS_A)) != 0;
        if ((needBus || needIdle) && !busFree) return false; // stallo, linePc invariato

        boolean tookBus = false;
        linePc++;

        // Last bus cycle of the LAST pixel: BBUSY goes off from the next CCK (measured on
        // the DMACONR polls of bbusy7l/13l: the poll in the CCK after the slot already reads
        // clear). The countdown is completed by tickBusyClear even after endBlit. With bc0
        // (LINE_LAST_BUS on the REPEAT, no bus cycle in the pixel) the clear is SYNCHRONOUS,
        // visible already in the CCK of the REPEAT: the poll of bbusy9l v98 h=94 (same CCK as
        // the REPEAT, with a slot stolen by the bitplanes at 91) already reads clear.
        if ((op & LINE_LAST_BUS) != 0 && hCount == 1) {
            if ((op & REPEAT) != 0) { clearBbusy(); armBlitInterrupt(); }
            else clearBusyCountdown = 1;
        }

        // Slot A of the first pass: the state of the pixel and the Bresenham accumulator.
        if ((op & LINE_PROC_APT) != 0) { lineProcStatus(); lineProcApt(); }

        // Slot C of a middle pass: the Y step falls BEFORE the read, so that pass reads C
        // from the address already moved by BLTCMOD.
        if ((op & LINE_PROC_CPT_Y) != 0 && lineLoop && (bltcon1 & BLTCON1_SUD) == 0) {
            lineProcCptY();
            lineLoop = false;
        }

        if ((op & (FETCH_B | FETCH_B_NOMOD)) != 0) {
            bnew = agnus.blitterImmediateRead(getBpt()) & 0xFFFF;
            if ((op & FETCH_B) != 0) setBpt(getBpt() + bltbmod);
            tookBus = true;
        }
        if ((op & FETCH_C) != 0) {
            chold = agnus.blitterImmediateRead(getCpt()) & 0xFFFF;
            tookBus = true;
        }
        if ((op & BUS) != 0) tookBus = true; // dummy bus cycle

        // Slot C of the first pass: the X step falls AFTER the read.
        if ((op & LINE_PROC_CPT_X) != 0) lineProcCptX();

        if ((op & HOLD_D) != 0) {
            // Free slot of the last pass. Order: `adat` uses the OLD ASH (inside
            // calculateLineWord), then `ovf` updates it, then the state / remaining Y step,
            // then the sign.
            dhold = calculateLineWord();
            if (dhold != 0) bzero = false;
            lineOvfApply();
            if (bltsizeH <= 1) {
                // w == 1: the free slot IS the only slot, and there the state is decided in
                // place of the Y step, which with w == 1 does not exist.
                lineProcStatus();
            } else if (lineLoop) {
                lineProcCptY();
                lineLoop = false;
            }
            lineSign();
            lineWrite = linePixelPending;
            linePixelPending = false;
            lineLoop = true;
        }

        if ((op & WRITE_D) != 0 && lineWrite) {
            agnus.blitterImmediateWrite(getDpt(), dhold & 0xFFFF);
            tookBus = true;
        }

        if ((op & REPEAT) != 0) {
            executeLineStepping();
            linePc = 0;
            if (--hCount <= 0) endBlit();
        }
        return tookBus;
    }

    private int calculateLineWord() {
        int ash = (bltcon0 & BLTCON0_ASH) >>> 12;
        ahold = barrelShifterLine(anew & bltafwm, ash);

        int bsh = (bltcon1 & BLTCON1_BSH) >>> 12;
        int texBit = ((bnew >>> bsh) & 1) != 0 ? 0xFFFF : 0;

        return doMintermLogic(ahold, texBit, chold, bltcon0 & BLTCON0_LF);
    }

    /**
     * The X step <b>does not touch ASH</b>: it moves BLTCPT when the counter is about to
     * overflow and leaves the rest to {@link #lineOvfApply()}, which runs at the free slot of
     * the last pass. That separation is what lets the stepping be distributed: if ASH were
     * written here, moving the X step would also move the instant at which A's barrel
     * shifter changes, and the minterm of every pixel would be affected.
     */
    private void stepIncX() {
        if (((bltcon0 & BLTCON0_ASH) >>> 12) == 0xF) setCpt(getCpt() + 2);
        lineOvf = 1;
    }
    private void stepDecX() {
        if (((bltcon0 & BLTCON0_ASH) >>> 12) == 0) setCpt(getCpt() - 2);
        lineOvf = -1;
    }
    /** {@code blitter_line_ovf()}: ASH absorbs the X step, at the free slot. */
    private void lineOvfApply() {
        int ash = (((bltcon0 & BLTCON0_ASH) >>> 12) + lineOvf) & 0xF;
        bltcon0 = (bltcon0 & ~BLTCON0_ASH) | (ash << 12);
        lineOvf = 0;
    }
    private void stepIncY() { setCpt(getCpt() + bltcmod); fillCarry = 1; firstDot = true; }
    private void stepDecY() { setCpt(getCpt() - bltcmod); fillCarry = 1; firstDot = true; }

    /**
     * Decides whether the pixel is drawn and marks the point as "already touched". Runs at
     * slot A of the first pass, i.e. <b>only with USEA</b>; with {@code hblitsize == 1} the
     * free slot calls it instead.
     */
    private void lineProcStatus() {
        boolean sing = (bltcon1 & BLTCON1_SING) != 0;
        linePixelPending = isUsed(BLTCON0_USEC) && (!sing || firstDot);
        firstDot = false;
    }

    /** The Bresenham accumulator, at slot A. */
    private void lineProcApt() {
        if (!isUsed(BLTCON0_USEA)) return;
        int errDelta = (bltcon1 & BLTCON1_SIGN) != 0 ? bltbmod : bltamod;
        short currentApt = (short) (bltapt & 0xFFFF);
        currentApt += errDelta;
        bltapt = (bltapt & 0xFFFF0000) | (currentApt & 0xFFFF);
    }

    /** X step: slot C of the first pass, after the read. */
    private void lineProcCptX() {
        boolean sign = (bltcon1 & BLTCON1_SIGN) != 0;
        if (!sign && (bltcon1 & BLTCON1_SUD) == 0) {
            if ((bltcon1 & BLTCON1_SUL) != 0) stepDecX(); else stepIncX();
        }
        if ((bltcon1 & BLTCON1_SUD) != 0) {
            if ((bltcon1 & BLTCON1_AUL) != 0) stepDecX(); else stepIncX();
        }
    }

    /** Y step: slot C of a middle pass, or the free slot. */
    private void lineProcCptY() {
        boolean sign = (bltcon1 & BLTCON1_SIGN) != 0;
        fillCarry = 0;
        if (!sign && (bltcon1 & BLTCON1_SUD) != 0) {
            if ((bltcon1 & BLTCON1_SUL) != 0) stepDecY(); else stepIncY();
        }
        if ((bltcon1 & BLTCON1_SUD) == 0) {
            if ((bltcon1 & BLTCON1_AUL) != 0) stepDecY(); else stepIncY();
        }
    }

    /** Sign of the accumulator: at the free slot, after the accumulator has advanced. */
    private void lineSign() {
        if ((short) (bltapt & 0xFFFF) < 0) bltcon1 |= BLTCON1_SIGN;
        else                               bltcon1 &= ~BLTCON1_SIGN;
    }

    /**
     * End of the pixel: the step of B's barrel shifter and the D pointer following C.
     *
     * <p>{@code setDpt(getCpt())} stays <b>here</b> and not at the free slot, and it is
     * equivalent: the D write sits inside the pixel and reads {@code dpt} as the previous
     * pixel left it, which is what a latch of the old D pointer at the free slot would give.
     */
    private void executeLineStepping() {
        int bsh = ((bltcon1 & BLTCON1_BSH) >>> 12);
        bsh = (bsh - 1) & 0xF;
        bltcon1 = (bltcon1 & ~BLTCON1_BSH) | (bsh << 12);

        setDpt(getCpt());
    }

    // =========================================================================
    // Arithmetic and logic utilities
    // =========================================================================

    private int barrelShifter(int anew_val, int aold_val, int shift, boolean desc) {
        if (desc) return (int) (((((long) anew_val << 16) | aold_val) >> (16 - shift)) & 0xFFFF);
        else return (int) (((((long) aold_val << 16) | anew_val) >> shift) & 0xFFFF);
    }

    private int barrelShifterLine(int data, int ash) {
        return (data >>> ash) & 0xFFFF;
    }

    private int doMintermLogic(int a, int b, int c, int minterm) {
        int result = 0;
        if ((minterm & 0x80) != 0) result |=  a &  b &  c;
        if ((minterm & 0x40) != 0) result |=  a &  b & ~c;
        if ((minterm & 0x20) != 0) result |=  a & ~b &  c;
        if ((minterm & 0x10) != 0) result |=  a & ~b & ~c;
        if ((minterm & 0x08) != 0) result |= ~a &  b &  c;
        if ((minterm & 0x04) != 0) result |= ~a &  b & ~c;
        if ((minterm & 0x02) != 0) result |= ~a & ~b &  c;
        if ((minterm & 0x01) != 0) result |= ~a & ~b & ~c;
        return result & 0xFFFF;
    }

    private int bltconFE() { return (bltcon1 & (BLTCON1_EFE | BLTCON1_IFE)) != 0 ? 1 : 0; }

    private boolean isUsed(int channel) {
        return (bltcon0 & channel) != 0;
    }

    // =========================================================================
    // Bus Cycle & Pointers
    // =========================================================================

    /**
     * Mask of the Blitter's DMA pointers: word-aligned and as wide as the installed Chip
     * RAM, like the other Agnus channels (bitplanes/sprites/disk through
     * {@code dmaPtrHiMask}, the Copper through the same calls, audio at 21 bits).
     *
     * <p>A fixed 20-bit (1 MB) mask would wrap every address above {@code $0FFFFF} with 2 MB
     * of Chip RAM, and a blit aimed past the first megabyte would write over the exception
     * vectors. That is WHDLoad's case, which uses the 2 MB profile: the blit cleared
     * ExecBase at {@code $000004} and the machine died right after.
     *
     * <p>Masking the pointer is equivalent to masking every access with the Chip RAM size
     * minus one, because the mask is a power of two and the increments are additive.
     */
    private int ptrMask()   { return agnus.getDmaPtrMask() & ~1; }
    /** High part (bits 16-20) of {@link #ptrMask()}, for the BLT?PTH writes. */
    private int ptrHiMask() { return agnus.getDmaPtrHiMask(); }

    private int getApt() { return bltapt & ptrMask(); }
    private int getBpt() { return bltbpt & ptrMask(); }
    private int getCpt() { return bltcpt & ptrMask(); }
    private int getDpt() { return bltdpt & ptrMask(); }

    private void setApt(int v) { bltapt = v & ptrMask(); }
    private void setBpt(int v) { bltbpt = v & ptrMask(); }
    private void setCpt(int v) { bltcpt = v & ptrMask(); }
    private void setDpt(int v) { bltdpt = v & ptrMask(); }

    // =========================================================================
    // Accessori pubblici
    // =========================================================================

    /** BBUSY flag of DMACON (it falls a few cycles before the actual end). */
    public boolean isBusy() { return bbusy; }
    /** The blit is still running (drives the ticking from Agnus; includes the drain). */
    public boolean isRunning() { return running; }
    public void setBusy(boolean b) { this.bbusy = b; this.running = b; }

    /** Switches the immediate blit mode on or off (see {@link #immediateMode}). */
    public void setImmediateMode(boolean enabled) { this.immediateMode = enabled; }
    /** {@code true} if the immediate blit mode is on. */
    public boolean isImmediateMode() { return immediateMode; }

    /**
     * BLTEN of DMACON. <b>Switching it off PAUSES a blit in progress, it does not cancel
     * it.</b> The Blitter's state is left alone; each CCK without BLTEN is a cycle the blit
     * skips. The blit resumes where it was when the DMA comes back, and BBUSY stays on
     * throughout.
     *
     * <p>The pause is implicit: Agnus steps the Blitter only inside
     * {@code if (isBlitterEnabled() && blitter.isRunning())}. Measured on
     * {@code dmacon/dmacon9}, which switches BLTEN off in the middle of a 60x120 blit and
     * back on three waits later.
     *
     * <p>The same holds before the blit has started: a BLTSIZE written with the DMA off, or
     * a DMA switched off during the start latency, still sets BBUSY, and the blit, its
     * warm-up included, begins when BLTEN comes back.
     */
    public void setDmaEnabled(boolean enabled) {
        this.dmaEnabled = enabled;
    }
    public boolean isZero() { return bzero; }
    public int getBltcon0() { return bltcon0; }
    public int getBltcon1() { return bltcon1; }
    public State getState() { return state; }
}
