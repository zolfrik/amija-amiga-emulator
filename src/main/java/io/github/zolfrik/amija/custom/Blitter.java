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
 * Blitter (Block Image Transferrer) dell'Amiga 500.
 *
 * <p><b>Fase 3 (refactoring timing, docs/agnus-timing-refactor-plan.md §9/§10):</b>
 * il percorso copy-blit è ora un motore a <b>micro-programmi</b> portato da vAmiga
 * ({@code SlowBlitter.cpp}). Ogni CCK esegue una micro-op (bitmask
 * {@code BUSIDLE/BUS/WRITE_D/FETCH_x/HOLD_x/FILL/BLTDONE/REPEAT}); la tabella
 * {@link #COPY_PROG}{@code [USE][fill][step]} riproduce esattamente la Table 6.2 dell'HRM
 * (con la D <b>pipelinata</b> di una word tramite {@code HOLD_D}/{@link #lockD} e i cicli
 * morti "--"). L'esecuzione è single-phase: l'accesso a memoria avviene in modo sincrono
 * via {@link AbstractAgnus#blitterImmediateRead}/{@code blitterImmediateWrite} nel momento
 * in cui l'arbitraggio del bus concede il CCK ({@link #stepCck(boolean)}).
 *
 * <p>Il line-mode conserva il percorso-dati validato (Bresenham, texture BSH su BLTBDAT) ed
 * è anch'esso guidato single-phase; il porting completo di {@code lineBlitInstr} è rimandato.
 *
 * @author alessio.carotenuto
 */
public final class Blitter {

    // =========================================================================
    // Dipendenze
    // =========================================================================

    private final AbstractAgnus agnus;

    // =========================================================================
    // Registri
    // =========================================================================

    private int bltcon0;
    private int bltcon1;
    private int bltafwm;
    private int bltalwm;

    // Puntatori DMA. La larghezza NON è fissa: come ogni canale DMA di Agnus è
    // quella della Chip RAM installata (vedi ptrMask/ptrHiMask), fino a 21 bit.
    private int bltapt;
    private int bltbpt;
    private int bltcpt;
    private int bltdpt;

    // Moduli (16 bit con segno)
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
    // USED (0x0100) non compare: il canale D non è mai interrogato con isUsed() —
    // la sua presenza è già codificata nei micro-programmi COPY_PROG, indicizzati
    // sui quattro bit USEx di BLTCON0.
    private static final int BLTCON0_LF   = 0x00FF;

    private static final int BLTCON1_BSH     = 0xF000;
    private static final int BLTCON1_EFE     = 0x0010;
    private static final int BLTCON1_IFE     = 0x0008;
    private static final int BLTCON1_FCI     = 0x0004;
    private static final int BLTCON1_DESC    = 0x0002;
    private static final int BLTCON1_LINE    = 0x0001;

    private static final int BLTCON1_SIGN    = 0x0040;
    /** Passo X pendente all'avvio del blit (`blit_ovf` dell'oracolo). */
    private static final int BLTCON1_OVF     = 0x0020;
    private static final int BLTCON1_SUD     = 0x0010;
    private static final int BLTCON1_SUL     = 0x0008;
    private static final int BLTCON1_AUL     = 0x0004;
    private static final int BLTCON1_SING    = 0x0002;

    // =========================================================================
    // Micro-op del copy blitter (vAmiga SlowBlitter.cpp)
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
    /** LINE: slot canale A (074): ciclo di pipe SENZA trasferimento, mai bus (idle). */
    private static final int LINE_BUS_A    = 0x1000;
    /** LINE: ultimo ciclo di bus del pixel — sull'ultimo pixel arma il clear BBUSY. */
    private static final int LINE_LAST_BUS = 0x2000;
    /**
     * LINE: lettura di B che NON avanza BLTBPT. In line mode il puntatore non ha
     * l'auto-incremento — {@code process_blitter()}: {@code if (!blitline) pt += rga->bltadd;}
     * — e il modulo lo somma solo il passaggio con {@code addmod}, cioe' l'ULTIMO
     * ({@code blitter_hcounter + 1 == hblitsize}). Le letture B di un pixel sono quindi
     * {@code hblitsize}, tutte dallo STESSO indirizzo, e il modulo si applica una volta sola.
     */
    private static final int FETCH_B_NOMOD = 0x4000;
    /**
     * LINE: slot A del PRIMO passaggio del pixel. Lì lo stato del pixel si decide
     * ({@code blitter_line_proc_status()}) e l'accumulatore Bresenham avanza
     * ({@code blitter_line_proc_apt()}). Esiste solo con USEA: senza, quello slot è
     * {@code c == 0} e nessuna delle due gira.
     */
    private static final int LINE_PROC_APT   = 0x00008000;
    /**
     * LINE: slot C del PRIMO passaggio. Il passo X si applica <b>dopo</b> la lettura,
     * quindi i passaggi successivi leggono C dall'indirizzo già mosso.
     */
    private static final int LINE_PROC_CPT_X = 0x00010000;
    /**
     * LINE: slot C di un passaggio di MEZZO. Il passo Y si applica <b>prima</b> della
     * lettura, e solo una volta per pixel ({@code blitlineloop}) e solo con SUD spento;
     * altrimenti resta allo slot libero dell'ultimo passaggio.
     */
    private static final int LINE_PROC_CPT_Y = 0x00020000;
    private static final int FETCH   = FETCH_A | FETCH_B | FETCH_C;

    /**
     * Micro-programmi copy (full execution / level 2), indicizzati
     * {@code [USE 0..15][fill 0..1][step 0..5]}. Trascrizione 1:1 dei blocchi
     * "Full execution" di {@code copyBlitInstr} in vAmiga {@code SlowBlitter.cpp}
     * (derivati dalla Table 6.2 HRM + Errata 1985). La D è pipelinata di una word
     * (HOLD_D calcola dhold della word precedente, WRITE_D lo scrive). I 4 casi
     * di fill con timing diverso (USE=$1/$5/$9/$D) hanno una sequenza a 6 passi.
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

    // Micro-programmi LINE (Fase 5, B3) — trascrizione di vAmiga lineBlitInstr[BC][0]
    // ("Full execution", accuracy level 2). Indice BC = (USEB?2:0)|(USEC?1:0).
    // La CADENZA (cicli morti BUSIDLE, dummy BUS) è quella di vAmiga; il PERCORSO-DATI
    // resta quello validato (Bresenham/BSH): HOLD_A/HOLD_B non fanno lavoro-dati
    // separato — il calcolo di dhold è tutto in HOLD_D (calculateLineWord, funzione
    // pura), quindi l'output di linea è BIT-IDENTICO al modello precedente e cambia
    // solo QUANDO i cicli di bus cadono (contesa). Vedi docs/agnus-timing-refactor-plan.md §12.
    /**
     * Micro-programma LINE, <b>generato per blit</b> da (USEA, USEB, USEC, {@code bltsizeH}).
     *
     * <p>⛔ Sostituisce una tabella a quattro righe che cablava {@code hblitsize == 2}. La
     * legge dell'oracolo è in {@code blit_bltset()}:
     *
     * <pre>
     *   shifter_skip_b = (bltcon0 &amp; BLTCHB) == 0;
     *   if (blitline) shifter_skip_y = true;
     *   blit_cyclecount = 4 - (shifter_skip_b + shifter_skip_y);
     * </pre>
     *
     * ⇒ un <b>passaggio</b> dello shifter dura <b>2 CCK</b> (senza B) o <b>3</b> (con B), e un
     * <b>pixel di linea dura {@code hblitsize} passaggi</b> — {@code blitter_hcounter} conta
     * fino a {@code hblitsize} e solo allora {@code blitter_vcounter++}.
     *
     * <p>I ruoli degli slot vengono da {@code get_current_channel()}, con
     * {@code lastw = hcounter + 1 == hblitsize}:
     * <ul>
     *   <li><b>stadio 0</b>: {@code lastw} ⇒ slot «libero» {@code c==5} (adat/ovf/sign/nxline e
     *       il minterm, cioè il nostro {@code HOLD_D}); altrimenti slot <b>A</b> se USEA, se no
     *       idle. ⚠️ Lo slot A non alloca MAI il bus: in line mode BLTAPT è l'accumulatore
     *       Bresenham, non un puntatore;</li>
     *   <li><b>stadio 1</b> (esiste solo con USEB): lettura <b>B</b> — in <b>ogni</b> passaggio,
     *       quindi {@code hblitsize} volte per pixel;</li>
     *   <li><b>stadio 2</b>: {@code lastw} ⇒ scrittura <b>D</b> se USEC ({@code c==4}), se no
     *       idle ({@code c==6}); altrimenti lettura <b>C</b> se USEC, se no idle.</li>
     * </ul>
     *
     * <p>✅ <b>Misurato</b>, non dedotto, con la sonda {@code WINUAE_BLTLOG} riga {@code BLT LSLOT}
     * (la sequenza dei {@code c} per CCK), su {@code timing/timing15l} (B+C, w=2 → <b>[A B C]
     * [libero B D]</b>), {@code timing15l1} (w=1 → <b>[libero B D]</b>), {@code timing15l3}
     * (w=3 → <b>[A B C][A B C][libero B D]</b>) e {@code timing11l1} (C senza B, w=1 →
     * <b>[libero D]</b>). Le tre corse escono byte-identiche al riferimento.
     *
     * <p>⚠️ Per {@code hblitsize == 2} il generatore riproduce <b>esattamente</b> le vecchie
     * righe bc=0 e bc=1; corregge invece bc=2 e bc=3, dove la tabella aveva il minterm uno slot
     * in anticipo e <b>una sola</b> lettura B per pixel invece di due.
     */
    /**
     * Micro-programma LINE, <b>generato per blit</b> da (USEA, USEB, USEC, {@code bltsizeH}).
     *
     * <p>La legge è dell'oracolo, ed è misurata (sonda {@code WINUAE_BLTLOG}, riga
     * {@code BLT LSLOT}, che stampa il canale di {@code get_current_channel()} per ogni CCK).
     * In {@code blit_bltset()}:
     *
     * <pre>
     *   shifter_skip_b = (bltcon0 &amp; BLTCHB) == 0;
     *   if (blitline) shifter_skip_y = true;
     *   blit_cyclecount = 4 - (shifter_skip_b + shifter_skip_y);
     * </pre>
     *
     * ⇒ un <b>passaggio</b> dello shifter dura <b>2 CCK</b> senza B, <b>3</b> con B; e un
     * <b>pixel di linea dura {@code hblitsize} passaggi</b> ({@code blitter_hcounter} conta
     * fino a {@code hblitsize}, poi {@code blitter_vcounter++}).
     *
     * <p>I ruoli vengono da {@code get_current_channel()}, con
     * {@code lastw = hcounter + 1 == hblitsize}:
     * <ul>
     *   <li><b>stadio 0</b>: {@code lastw} ⇒ slot «libero» {@code c==5} (adat/ovf/sign/nxline
     *       e il minterm, cioè il nostro {@code HOLD_D}); altrimenti slot <b>A</b> se USEA, se
     *       no idle. ⚠️ Lo slot A non alloca MAI il bus: in line mode BLTAPT è l'accumulatore
     *       Bresenham, non un puntatore;</li>
     *   <li><b>stadio 1</b> (solo con USEB): lettura <b>B</b> in <b>ogni</b> passaggio, tutte
     *       dallo stesso indirizzo — solo l'ultima somma il modulo ({@link #FETCH_B_NOMOD});</li>
     *   <li><b>stadio 2</b>: {@code lastw} ⇒ scrittura <b>D</b> se USEC ({@code c==4}), se no
     *       idle ({@code c==6}); altrimenti lettura <b>C</b> se USEC, se no idle.</li>
     * </ul>
     *
     * <p>✅ Sequenze misurate: {@code timing15l} (B+C, w=2) → <b>[A B C][libero B D]</b>;
     * {@code timing15l1} (w=1) → <b>[libero B D]</b>; {@code timing15l3} (w=3) →
     * <b>[A B C][A B C][libero B D]</b>; {@code timing11l1} (C senza B, w=1) →
     * <b>[libero D]</b>. Tutte da corse byte-identiche al riferimento.
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
            // ⚠️ La D porta anche BUSIDLE: quando il pixel non si scrive (`lineWrite`
            // falso) lo slot resta comunque un ciclo che ATTENDE il bus, come il `c==6`
            // dell'oracolo. Senza, sarebbe l'unico slot del programma ad avanzare anche
            // sotto contesa, e il pixel accorcerebbe (misurato su bbusy7l).
            int stage2 = lastw ? (useC ? (WRITE_D | BUSIDLE) : BUSIDLE)
                               : (useC ? FETCH_C : BUSIDLE);
            // ⛔ Solo con USEC: senza, `get_current_channel()` non restituisce mai lo slot
            // C (`nreg` resta `0x1fe` e il ramo D esce con `c == 0`, idle), quindi i due
            // passi non girano affatto — e infatti senza USEC la linea non si disegna.
            if (!lastw && useC) stage2 |= first ? LINE_PROC_CPT_X : LINE_PROC_CPT_Y;
            prog[i++] = stage2;
        }

        // LINE_LAST_BUS marca l'ULTIMO ciclo di bus del pixel: sull'ultimo pixel arma il
        // clear di BBUSY (differito di un CCK). Con USEC e' lo slot D, altrimenti la lettura
        // B dell'ultimo passaggio; senza ne' B ne' C non c'e' nessun ciclo di bus e la marca
        // cade sullo slot di REPEAT (clear sincrono). E' la regola gia' validata dai poll
        // DMACONR di bbusy7l/9l/13l, riespressa sul programma generato.
        // ⛔ Misurando `BLT DONE` si direbbe il PENULTIMO slot (l'oracolo chiama
        // blitter_done_all() allo shifter-out dell'ultimo passaggio, un CCK prima della D):
        // provato, e sbagliato di un CCK — rende rossi timing11l/15l e irqtim11l/15l, che
        // con questa regola sono verdi. Il CCK di scarto e' la stessa convenzione del
        // copy mode (il nostro fronte cade un CCK dopo finishcycle_dmacon).
        prog[(!useC && useB) ? prog.length - 2 : prog.length - 1] |= LINE_LAST_BUS;
        prog[prog.length - 1] |= REPEAT;
        return prog;
    }

    // =========================================================================
    // Stato
    // =========================================================================

    // Stati usati dal SOLO line-mode (il copy-mode è guidato da bltpc + COPY_PROG).
    public enum State { IDLE, FETCH_A, FETCH_B, FETCH_C, WRITE_D, INTERNAL }
    private State state = State.IDLE;

    // running: il blit è in corso (guida il ticking da Agnus). Resta true fino a
    //          endBlit(), cioè anche durante la coda di drain della pipeline D.
    // bbusy:   flag BBUSY di DMACON. vAmiga lo azzera qualche ciclo PRIMA della fine
    //          effettiva (clearBusyFlag nell'ultimo REPEAT). Serve a svegliare il
    //          Copper in attesa BFD al momento giusto.
    private boolean running = false;
    private boolean bbusy   = false;

    /**
     * CCK in cui {@link #bbusy} e' passato da acceso a spento — cioe' il
     * {@code blt_info.finishcycle_dmacon} dell'oracolo. {@code Long.MIN_VALUE} = mai.
     *
     * <p>⛔ Serve alla vista del <b>COPPER</b>, che NON e' quella della CPU. WinUAE le
     * scrive una accanto all'altra (blitter.cpp, {@code blitter_done_all}):
     *
     * <pre>
     *   blt_info.finishcycle_dmacon = get_cycles();
     *   blt_info.finishcycle_copper = get_cycles() + 1 * CYCLE_UNIT;
     * </pre>
     *
     * e {@code blit_busy(bool dmaconr)} sceglie la soglia col parametro, restituendo
     * {@code true} finche' {@code get_cycles() <= } quella giusta. ⇒ <b>Il Blitter risulta
     * ancora occupato per un CCK in piu' al Copper che alla CPU</b>, ed e' li' che
     * {@code generate_blitter()} chiama {@code blitter_done_notify()} per svegliare una WAIT
     * con BFD.
     *
     * <p>⚠️ La vista della CPU ({@link #isBusy}) NON va toccata: e' quella tarata sui poll di
     * {@code bbusy0} e congelata da {@code BbusyTest} (17/17).
     */
    private long busyClearedCck = Long.MIN_VALUE;

    /**
     * Ritardo fra la <b>fine del blit</b> e l'accensione del bit 6 di INTREQ.
     *
     * <p>L'oracolo lo scrive in {@code blitter_done_all()} — lo stesso istante in cui fissa
     * {@code finishcycle_dmacon} — come {@code INTREQ_INT(6, 3)}, e {@code INTREQ_INT} somma
     * un CYCLE_UNIT al ritardo: sono <b>4 CCK</b>, misurati tre volte su {@code irqtim0}
     * ({@code BLT DONE} → {@code CHIPIRQLOG bits=0040}).
     *
     * <p>⛔ <b>Qui vale 3, e il CCK di differenza NON è una taratura</b>: è l'istante da cui
     * si conta. Il nostro fronte di BBUSY cade <b>un CCK dopo</b> {@code finishcycle_dmacon}
     * — l'oracolo resta occupato <i>fino a tutto</i> quel CCK, il nostro flag è già giù
     * quando la CPU guarda — ed è quell'offset che rende giusta la lettura di DMACONR
     * (congelata da {@code BbusyTest} 17/17). Contato dal nostro fronte, il +4 dell'oracolo
     * è un +3. Vedi docs/vamigats-import-order.md §24.
     */
    private static final int BLT_IRQ_DELAY_CCK = 3;

    /** CCK mancanti all'accensione di INTREQ bit 6; 0 = nessun interrupt in volo. */
    private int irqCountdown = 0;

    /**
     * Arma l'interrupt del Blitter all'istante in cui il blit finisce (lo stesso in cui
     * BBUSY cade). Una volta sola per blit: {@link #birq} è la guardia.
     */
    private void armBlitInterrupt() {
        if (birq) return;
        birq = true;
        irqCountdown = BLT_IRQ_DELAY_CCK;
    }

    /** Unico punto in cui BBUSY si spegne: registra il CCK del fronte. */
    private void clearBbusy() {
        if (bbusy && agnus != null) {
            busyClearedCck = agnus.getMasterClock() >> 1;
        }
        bbusy = false;
    }

    /**
     * Il Blitter <b>come lo vede il Copper</b>: {@code blit_busy(false)} dell'oracolo,
     * cioe' occupato fino a {@code finishcycle_copper = finishcycle_dmacon + 1 CCK}.
     *
     * <p>⛔ <b>Il {@code + 1} e' la soglia, non un margine.</b> Senza, questo metodo
     * riproduce {@code finishcycle_dmacon} — la soglia della CPU — e per giunta in modo
     * <b>inerte</b>: dentro il CCK il Copper e' tickato PRIMA del Blitter (AbstractAgnus:
     * arbitraggio Copper, poi {@code blitter.stepCck}), quindi il Copper vede gia'
     * {@code bbusy == true} per tutto il CCK in cui BBUSY cade, e il termine non aggiunge
     * niente. Misurato con la sonda WinUAE {@code WINUAE_BLTLOG} su {@code sblit/sblit0};
     * il {@code +1} vale +58 test sul banco Agnus/Blitter (91/144 → 149/86), +2 su
     * Agnus/Registers e +1 su Agnus/Copper. Vedi docs/vamigats-import-order.md §24.
     */
    public boolean isBusyForCopper() {
        return bbusy
            || (agnus != null && (agnus.getMasterClock() >> 1) <= busyClearedCck + 1);
    }

    // Modalità "immediate blit": esegue l'intero blit in un colpo alla scrittura di
    // BLTSIZE (zero cicli di bus emulati). Non cycle-exact; solo da amiga.properties.
    private boolean immediateMode = false;

    // Latenza di avvio BBUSY (vAmiga BLT_STRT1/STRT2): BBUSY si attiva 2 CCK dopo la
    // scrittura di BLTSIZE. Countdown avanzato una volta per CCK da Agnus.
    private static final int BLT_START_LATENCY_CCK = 2;
    private int     startCountdown = 0;
    private boolean startPending   = false;

    private boolean dmaEnabled = false;
    private int fillCarry = 0;
    private boolean bzero = true;
    private boolean firstDot = true;   // line mode
    private int linePc = 0;            // step corrente nel micro-programma LINE_PROG
    private boolean lineWrite = false; // `blitlinepixel2`: latchato allo slot libero
    /** `blitlinepixel`: deciso da lineProcStatus(), consumato allo slot libero. */
    private boolean linePixelPending = false;
    /** `blit_ovf`: il passo X in attesa che lineOvfApply() lo faccia assorbire all'ASH. */
    private int lineOvf = 0;
    /** `blitlineloop`: il passo Y di questo pixel non è ancora stato applicato. */
    private boolean lineLoop = true;
    private int lineWarmup = 0;        // slot idle post-BLTSIZE prima del primo pixel
    private int copyWarmup = 0;        // slot idle post-BLTSIZE prima della prima micro-op copy

    // --- Stato micro-programma copy ---
    private int     bltpc    = 0;      // indice della micro-op corrente (0..5)
    private int     xCounter = 0;      // down-counter word nella riga (bltsizeH..1)
    private int     yCounter = 0;      // down-counter righe (bltsizeV..1)
    private int     cntA, cntB, cntC, cntD; // down-counter DMA per canale (modulo)
    private int     mask     = 0xFFFF; // maschera canale A per la word corrente
    private boolean lockD    = true;   // se true la WRITE_D non scrive (D pipelinata)
    private boolean birq     = false;  // interrupt BLIT già schedulato

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
        startPending = false; startCountdown = 0; clearBusyCountdown = 0;
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
    // Scrittura registri
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

            // In Line Mode BLTAPTL è l'errore Bresenham (con segno, 16 bit), non forziamo pari!
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

            // Registri ECS BLTCON0L ($05A) / BLTSIZV ($05C) / BLTSIZH ($05E):
            // permettono blit fino a 32768x2048 e la scrittura del solo byte basso di
            // BLTCON0. ECS-only (gate agnus.isECS()). Mappa HW: $05A=BLTCON0L,
            // $05C=BLTSIZV (solo V, NON avvia), $05E=BLTSIZH (imposta H, default e AVVIA).
            case 0x05A: // BLTCON0L — solo il byte basso (minterm LF) di BLTCON0
                if (agnus.isECS()) {
                    bltcon0 = (bltcon0 & 0xFF00) | (value & 0x00FF);
                    recacheAreaBlitFields();
                }
                break;
            case 0x05C: // BLTSIZV (15 bit) — imposta solo V, NON avvia
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
                // ⛔ «Channel B feature» (Toni Wilen, README di undocumented1): scrivere
                // BLTBDAT fa girare SUBITO il barrel shifter di B, e il risultato resta in
                // B-HOLD come dato statico per i blit successivi a canale B disabilitato.
                // ⚠️ E lo shift e' quello VERO, a 32 bit su B-OLD, non uno shift della sola
                // word scritta — ed e' `blitter_loadbdat()` dell'oracolo, la STESSA funzione
                // che usa il caricamento da DMA (`blitter_dodma_new`, canale B):
                //
                //     bltbhold = desc ? ((v << 16) | bltbold) >> (16 - shift)
                //                     : ((bltbold << 16) | v) >> shift;
                //     bltbold  = v;
                //
                // ⇒ «After shift B Old equals B New»: una SECONDA scrittura a BLTBDAT cambia
                // ancora BHOLD, perche' trova B-OLD gia' aggiornato. E' esattamente quel che
                // misurano misc/barrel1 e undocumented1.
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
    // Invarianti area-blit cachati (ri-estratti ad ogni scrittura di BLTCON0/1)
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
     * Armamento del blit alla scrittura di BLTSIZE: NON avvia subito (BBUSY a write+0),
     * ma programma l'avvio dopo {@link #BLT_START_LATENCY_CCK} CCK (latenza vAmiga
     * BLT_STRT1/STRT2). Il countdown è fatto avanzare da Agnus via {@link #tickStartCountdown()}.
     */
    private void armBlit() {
        // Guard SpeedBall II: senza DMA blitter abilitato il blit non parte e BBUSY non
        // si attiva (vedi startBlit()); annulla un eventuale arm pendente.
        if (!dmaEnabled) {
            running = false; clearBbusy(); startPending = false; startCountdown = 0; clearBusyCountdown = 0;
            return;
        }
        if (immediateMode) {
            startPending = false; startCountdown = 0;
            startBlit();
            runImmediate();
            return;
        }
        startPending   = true;
        startCountdown = BLT_START_LATENCY_CCK;
    }

    /**
     * Chiamato da Agnus una volta per CCK. Gestisce la latenza di avvio BBUSY: quando il
     * countdown scade avvia effettivamente il blit.
     */
    public void tickStartCountdown() {
        if (!startPending) return;
        if (--startCountdown <= 0) {
            startPending = false;
            startBlit();
        }
    }

    /** True tra la scrittura di BLTSIZE e l'attivazione di BBUSY (latenza vAmiga). */
    public boolean isStarting() { return startPending; }

    private void startBlit() {
        // Senza DMA blitter abilitato il blitter non può svolgere cicli di memoria: non
        // parte e BBUSY non si attiva (loader che scrivono BLTSIZE con DMA off e poi
        // attendono BBUSY — es. cracktro Speedball II — si bloccherebbero altrimenti).
        if (!dmaEnabled) { running = false; clearBbusy(); clearBusyCountdown = 0; return; }

        recacheAreaBlitFields();

        running = true;
        bbusy   = true;
        bzero   = true;
        birq    = false;
        irqCountdown = 0;
        bltpc   = 0;
        clearBusyCountdown = 0;
        aold = 0; bold = 0;

        if ((bltcon1 & BLTCON1_LINE) != 0) {
            // Line mode: percorso-dati esistente (Bresenham). Nessuna pipeline word.
            fillCarry = 0;
            firstDot  = true;
            hCount    = bltsizeV;
            lockD     = false;
            state     = State.IDLE;
            linePc    = 0;
            lineWrite = false;
            linePixelPending = false;
            // `blit_ovf = (bltcon1 & BLTOVF) != 0` dell'oracolo: il passo X pendente
            // all'avvio viene dal bit 5 di BLTCON1, non da zero.
            lineOvf   = (bltcon1 & BLTCON1_OVF) != 0 ? 1 : 0;
            lineLoop  = true;
            // Warmup idle post-BLTSIZE: DUE slot, sempre. Misurato con BLT LSLOT su
            // timing15l (B+C): fra `BLT START` e il primo slot del pixel ci sono due
            // ingressi idle. Il 3 che valeva per B+C compensava la vecchia forma del
            // programma, che apriva con la lettura B invece che con lo slot di stadio 0.
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
            // Warmup post-BLTSIZE (oracolo WinUAE, [WRGA] bbusy3 v66: BLTSIZE a h=84,
            // slot idle a 86-87, prima micro-op a 88): la pipe RGA del blitter emette
            // 2 slot idle ARBITRATI prima del primo passo del programma. È questo che
            // sposta di +2 CCK il footprint bus rispetto al modello vAmiga; su questo
            // asse il clear di BBUSY torna sincrono all'ultimo REPEAT (vedi lì).
            copyWarmup = 2;
        }
    }

    /**
     * Esecuzione "immediate" del blit: completa l'intera operazione in modo sincrono
     * riusando lo STESSO motore cycle-exact ({@link #stepCck(boolean)} con bus sempre
     * libero). Usato solo quando {@link #immediateMode} è attivo.
     */
    private void runImmediate() {
        long guard = 0;
        long maxIters = (long) bltsizeH * bltsizeV * 8L + (long) bltsizeV * 8L + 128L;
        while (running && guard++ < maxIters) {
            tickBusyClear();
            stepCck(true);
        }
        if (running) endBlit(); // guard scaduto: non lasciare BBUSY incollato
        clearBusyCountdown = 0; clearBbusy(); // niente tick successivi in immediate
        // Immediate: nessun tick successivo, quindi l'interrupt armato va consegnato qui.
        if (irqCountdown > 0) { irqCountdown = 0; agnus.blitterFinished(); }
    }

    // =========================================================================
    // Esecuzione per-CCK (single-phase)
    // =========================================================================

    /**
     * Esegue una micro-op del blitter per il CCK corrente.
     *
     * @param busFree true se il bus è concedibile al blitter in questo CCK
     *                (= nessun DMA a priorità superiore e nessuna precedenza CPU).
     * @return true se il blitter ha ALLOCATO il bus (accesso a memoria eseguito),
     *         così Agnus può marcare il CCK come BLITTER e bloccare la CPU.
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

    /** Clear BBUSY differito, usato dal SOLO line mode (LINE_LAST_BUS). 0 = inattivo. */
    private int clearBusyCountdown = 0;

    /**
     * Clear BBUSY differito del line mode: armato a 1 all'ultimo ciclo di bus
     * dell'ultimo pixel (LINE_LAST_BUS), visibile alla CPU dal CCK successivo.
     * Chiamato da Agnus UNA volta per CCK, PRIMA dello step del blitter e
     * INDIPENDENTEMENTE da {@link #isRunning()} (endBlit non lo scavalca).
     * Il copy mode non passa più di qui: sull'asse +2 del warmup WinUAE il suo
     * clear è sincrono all'ultimo REPEAT (vedi execMicroOp).
     */
    public void tickBusyClear() {
        if (irqCountdown > 0 && --irqCountdown == 0) {
            agnus.blitterFinished();
        }
        if (clearBusyCountdown == 1) {
            clearBusyCountdown = 0;
            clearBbusy();
            armBlitInterrupt();
        }
    }

    /** Esegue la micro-op copy corrente. Ritorna true se ha allocato il bus. */
    private boolean execMicroOp(boolean busFree) {
        // Warmup post-BLTSIZE (vedi startBlit): 2 slot idle soggetti ad arbitraggio
        // (in WinUAE sono richieste RGA regolari: sotto contesa slittano), senza bus.
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

        // Arbitraggio bus: BUS e BUSIDLE richiedono il bus libero per procedere; le
        // micro-op NOTHING avanzano SEMPRE (anche sotto DMA a priorità superiore).
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
                // Ultima word: BBUSY si abbassa QUI, sincrono all'ultimo REPEAT
                // (modello vAmiga clearBusyFlag), ed è visibile alla CPU già in
                // questo CCK (la sezione DMA precede il ciclo CPU). Con l'asse del
                // motore spostato di +2 CCK dal warmup (WinUAE, vedi startBlit)
                // questo riproduce ESATTAMENTE la timeline validata dai poll di
                // bbusy0 (regola WinUAE: clear dopo il (2WH+1)-esimo slot allocato
                // CONTANDO i warmup, visibile il cck dopo — per il null W2H2:
                // 9° slot = b+10, visibile b+11 = il nostro ultimo REPEAT a b+11).
                // Il vecchio countdown a 2 stadi compensava l'asse 2 CCK presto,
                // non un difetto del clear vAmiga. Il blit continua (drain D).
                clearBbusy();
                armBlitInterrupt();
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
        if (xCounter == bltsizeH) mask &= bltafwm; // prima word della riga
        if (xCounter == 1)        mask &= bltalwm; // ultima word della riga
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
        // BBUSY: se il countdown del clear è in corso NON scavalcarlo — le micro-op
        // NOTHING/BLTDONE avanzano anche su cck rubati, quindi sotto contesa il
        // BLTDONE arriva PRIMA dello slot d'uscita che spegne BBUSY (misurato su
        // bbusy0 v202, BPU=4). Il countdown viene completato da tickBusyClear.
        // I path senza countdown (line mode, guardie) azzerano subito come prima.
        if (clearBusyCountdown == 0) clearBbusy();
        state   = State.IDLE;
        // L'interrupt è già armato al fronte di BBUSY (armBlitInterrupt). Se un percorso
        // ci arriva senza averlo armato — line mode con clear differito già consumato, o
        // una guardia — lo si arma qui, dallo stesso istante.
        armBlitInterrupt();
    }

    // =========================================================================
    // Line Mode (percorso-dati validato, guidato single-phase)
    // =========================================================================

    /**
     * Esecuzione LINE per-CCK guidata dal micro-programma {@link #buildLineProg}.
     *
     * <p>Lo stepping Bresenham è <b>distribuito sugli slot</b>, come nell'oracolo, e non
     * eseguito tutto insieme in coda al pixel: lo stato e l'accumulatore allo slot A del
     * primo passaggio, il passo X allo slot C del primo passaggio (dopo la lettura), il
     * passo Y allo slot C di un passaggio di mezzo (prima della lettura) o allo slot
     * libero, l'ASH allo slot libero. È quel che cambia <i>quale indirizzo C viene letto</i>
     * dentro un pixel largo più di due, e per larghezza ≤ 2 coincide con lo stepping unico.
     */
    private boolean lineStepCck(boolean busFree) {
        // Warmup post-BLTSIZE (oracolo WinUAE): slot idle prima del primo ciclo
        // reale del primo pixel (3 con B+C, 2 negli altri casi misurati).
        if (lineWarmup > 0) {
            if (!busFree) return false;
            lineWarmup--;
            return false;
        }
        int[] prog = lineProg;
        int op = prog[linePc];

        // WRITE_D prende il bus solo se scrive davvero (single-pixel/SING → salta);
        // FETCH_B/FETCH_C/BUS lo prendono sempre; BUSIDLE richiede solo bus libero.
        // Lo slot A (074) NON prende MAI il bus, con qualunque USE: in line mode
        // BLTAPT è l'accumulatore Bresenham, non un puntatore — è un ciclo di pipe
        // senza trasferimento. Misurato sui [WRGA]/[WACC] timestampati (stesso
        // cy = stesso cck) di bbusy9l v66 (bc0, USE=A+D): accesso CPU nello STESSO
        // cck dello slot 074. La vecchia regola per-USE (bc0 bloccante, bc2
        // permeabile) era un artefatto del tagging non timestampato.
        boolean needBus  = (op & (FETCH_B | FETCH_B_NOMOD | FETCH_C | BUS)) != 0
                        || ((op & WRITE_D) != 0 && lineWrite);
        boolean needIdle = !needBus && (op & (BUSIDLE | LINE_BUS_A)) != 0;
        if ((needBus || needIdle) && !busFree) return false; // stallo, linePc invariato

        boolean tookBus = false;
        linePc++;

        // Ultimo ciclo di bus dell'ULTIMO pixel: BBUSY si spegne dal cck successivo
        // (misurato sui poll DMACONR di bbusy7l/13l: il poll nel cck dopo lo
        // slot legge già clear). Il countdown è completato da tickBusyClear anche
        // dopo endBlit. ECCEZIONE bc0 (LINE_LAST_BUS sul REPEAT, nessun ciclo di
        // bus nel pixel): clear SINCRONO, visibile già nel cck del REPEAT — il
        // poll di bbusy9l v98 h=94 (stesso cck del REPEAT, con slot rubato dal
        // bitplane a 91) legge già clear.
        if ((op & LINE_LAST_BUS) != 0 && hCount == 1) {
            if ((op & REPEAT) != 0) { clearBbusy(); armBlitInterrupt(); }
            else clearBusyCountdown = 1;
        }

        // Slot A del primo passaggio: lo stato del pixel e l'accumulatore Bresenham.
        if ((op & LINE_PROC_APT) != 0) { lineProcStatus(); lineProcApt(); }

        // Slot C di un passaggio di mezzo: il passo Y cade PRIMA della lettura, quindi
        // quel passaggio legge C dall'indirizzo gia' mosso di BLTCMOD.
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
        if ((op & BUS) != 0) tookBus = true; // ciclo di bus fittizio (vAmiga)

        // Slot C del primo passaggio: il passo X cade DOPO la lettura.
        if ((op & LINE_PROC_CPT_X) != 0) lineProcCptX();

        if ((op & HOLD_D) != 0) {
            // Slot libero dell'ultimo passaggio. L'ordine e' quello del `c == 5`
            // dell'oracolo: `adat` usa l'ASH VECCHIO (ed e' dentro calculateLineWord),
            // poi `ovf` lo aggiorna, poi lo stato/il passo Y residuo, poi il segno.
            dhold = calculateLineWord();
            if (dhold != 0) bzero = false;
            lineOvfApply();
            if (bltsizeH <= 1) {
                // w == 1: lo slot libero E' l'unico slot, e li' l'oracolo chiama
                // proc_status() al posto del passo Y — che con w == 1 non esiste.
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
     * ⛔ Il passo X <b>non tocca l'ASH</b>: muove BLTCPT quando il contatore sta per
     * traboccare e lascia il resto a {@link #lineOvfApply()}, che gira allo slot libero
     * dell'ultimo passaggio ({@code blitter_line_ovf()}). È la separazione che rende
     * portabile la distribuzione dello stepping: se l'ASH si scrivesse qui, spostare il
     * passo X sposterebbe anche l'istante in cui il barrel shifter di A cambia, e il
     * minterm di ogni pixel ne risentirebbe.
     */
    private void stepIncX() {
        if (((bltcon0 & BLTCON0_ASH) >>> 12) == 0xF) setCpt(getCpt() + 2);
        lineOvf = 1;
    }
    private void stepDecX() {
        if (((bltcon0 & BLTCON0_ASH) >>> 12) == 0) setCpt(getCpt() - 2);
        lineOvf = -1;
    }
    /** {@code blitter_line_ovf()}: l'ASH assorbe il passo X, allo slot libero. */
    private void lineOvfApply() {
        int ash = (((bltcon0 & BLTCON0_ASH) >>> 12) + lineOvf) & 0xF;
        bltcon0 = (bltcon0 & ~BLTCON0_ASH) | (ash << 12);
        lineOvf = 0;
    }
    private void stepIncY() { setCpt(getCpt() + bltcmod); fillCarry = 1; firstDot = true; }
    private void stepDecY() { setCpt(getCpt() - bltcmod); fillCarry = 1; firstDot = true; }

    /**
     * {@code blitter_line_proc_status()} — decide se il pixel si disegna e marca il punto
     * come «già toccato». Gira allo slot A del primo passaggio, cioè <b>solo con USEA</b>;
     * con {@code hblitsize == 1} lo chiama invece lo slot libero.
     */
    private void lineProcStatus() {
        boolean sing = (bltcon1 & BLTCON1_SING) != 0;
        linePixelPending = isUsed(BLTCON0_USEC) && (!sing || firstDot);
        firstDot = false;
    }

    /** {@code blitter_line_proc_apt()} — l'accumulatore Bresenham, allo slot A. */
    private void lineProcApt() {
        if (!isUsed(BLTCON0_USEA)) return;
        int errDelta = (bltcon1 & BLTCON1_SIGN) != 0 ? bltbmod : bltamod;
        short currentApt = (short) (bltapt & 0xFFFF);
        currentApt += errDelta;
        bltapt = (bltapt & 0xFFFF0000) | (currentApt & 0xFFFF);
    }

    /** {@code blitter_line_proc_cpt_x()} — slot C del primo passaggio, dopo la lettura. */
    private void lineProcCptX() {
        boolean sign = (bltcon1 & BLTCON1_SIGN) != 0;
        if (!sign && (bltcon1 & BLTCON1_SUD) == 0) {
            if ((bltcon1 & BLTCON1_SUL) != 0) stepDecX(); else stepIncX();
        }
        if ((bltcon1 & BLTCON1_SUD) != 0) {
            if ((bltcon1 & BLTCON1_AUL) != 0) stepDecX(); else stepIncX();
        }
    }

    /** {@code blitter_line_proc_cpt_y()} — slot C di un passaggio di mezzo, o slot libero. */
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

    /** {@code blitter_line_sign()} — allo slot libero, dopo che l'accumulatore è avanzato. */
    private void lineSign() {
        if ((short) (bltapt & 0xFFFF) < 0) bltcon1 |= BLTCON1_SIGN;
        else                               bltcon1 &= ~BLTCON1_SIGN;
    }

    /**
     * Coda del pixel: il passo del barrel shifter di B ({@code blitter_nxline()}) e il
     * puntatore D che insegue C.
     *
     * <p>⚠️ {@code setDpt(getCpt())} resta <b>qui</b> e non allo slot libero, ed è
     * equivalente: l'oracolo allo slot libero <i>latcha</i> il vecchio {@code bltdpt} in
     * {@code bltdptl} (che è quel che la scrittura D usa) e <i>solo dopo</i> fa
     * {@code bltdpt = bltcpt}. Da noi la scrittura D sta dentro il pixel e legge
     * {@code dpt} come lo ha lasciato il pixel precedente: stessa cosa, senza il latch.
     */
    private void executeLineStepping() {
        int bsh = ((bltcon1 & BLTCON1_BSH) >>> 12);
        bsh = (bsh - 1) & 0xF;
        bltcon1 = (bltcon1 & ~BLTCON1_BSH) | (bsh << 12);

        setDpt(getCpt());
    }

    // =========================================================================
    // Utilities Matematiche e Logiche
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
     * Maschera dei puntatori DMA del blitter: word-allineata e larga quanto la Chip RAM
     * installata, esattamente come gli altri canali di Agnus (bitplane/sprite/disco via
     * {@code dmaPtrHiMask}, Copper via le stesse chiamate, audio a 21 bit).
     *
     * <p>Era cablata a 20 bit (1 MB): con 2 MB di Chip RAM ogni indirizzo sopra
     * {@code $0FFFFF} rientrava dal basso, e un blit diretto oltre il megabyte scriveva
     * sui vettori di eccezione. È il caso di WHDLoad, che usa il profilo a 2 MB: il blit
     * azzerava ExecBase in {@code $000004} e la macchina moriva subito dopo.
     *
     * <p>Oracolo WinUAE: i registri BLT?PT sono tenuti interi e mascherati all'accesso da
     * {@code chipmem_full_mask} = dimensione della Chip RAM − 1 ({@code memory.cpp},
     * {@code chipmem_agnus_wget}); qui il mascheramento è sul puntatore, equivalente
     * perché la maschera è una potenza di due e gli incrementi sono additivi.
     */
    private int ptrMask()   { return agnus.getChipMask() & ~1; }
    /** Parte alta (bit 16-20) di {@link #ptrMask()}, per le scritture BLT?PTH. */
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

    /** Flag BBUSY di DMACON (si abbassa qualche ciclo prima della fine effettiva). */
    public boolean isBusy() { return bbusy; }
    /** Il blit è ancora in esecuzione (guida il ticking da Agnus; include la coda di drain). */
    public boolean isRunning() { return running; }
    public void setBusy(boolean b) { this.bbusy = b; this.running = b; }

    /** Attiva/disattiva la modalità immediate blit (vedi {@link #immediateMode}). */
    public void setImmediateMode(boolean enabled) { this.immediateMode = enabled; }
    /** {@code true} se la modalità immediate blit è attiva. */
    public boolean isImmediateMode() { return immediateMode; }

    /**
     * BLTEN di DMACON. ⛔ <b>Spegnerlo METTE IN PAUSA un blit in corso, non lo annulla.</b>
     * Nell'oracolo il gestore di DMACON non tocca lo stato del Blitter: l'unico effetto e'
     * {@code blitter_cant_access()}, che entra nella decisione per-CCK come
     * {@code ena = blitter_cant_access() == 0} e fa saltare il ciclo
     * ({@code blit_misscyclecounter++; goto end;}). Il blit riprende da dove era quando il
     * DMA torna, e BBUSY resta acceso per tutto il tempo.
     *
     * <p>Da noi la pausa e' gia' implicita: Agnus stepp a il Blitter solo dentro
     * {@code if (isBlitterEnabled() && blitter.isRunning())}. Bastava smettere di
     * distruggere lo stato. Misurato su {@code dmacon/dmacon9}, che spegne BLTEN in mezzo a
     * un blit 60x120 e lo riaccende tre attese dopo.
     *
     * <p>⚠️ Resta annullato il solo <b>arm pendente</b>: un BLTSIZE scritto con il DMA gia'
     * spento non parte e non accende BBUSY — e' la guardia di {@code armBlit()}/
     * {@code startBlit()}, tarata sul cracktro di SpeedBall II.
     */
    public void setDmaEnabled(boolean enabled) {
        this.dmaEnabled = enabled;
        if (!enabled) {
            startPending = false; startCountdown = 0;
        }
    }
    public boolean isZero() { return bzero; }
    public int getBltcon0() { return bltcon0; }
    public int getBltcon1() { return bltcon1; }
    public State getState() { return state; }
}
