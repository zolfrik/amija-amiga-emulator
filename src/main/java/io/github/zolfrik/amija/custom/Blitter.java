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

    // Puntatori (20 bit)
    private int bltapt;
    private int bltbpt;
    private int bltcpt;
    private int bltdpt;

    // Moduli (16 bit con segno)
    private int bltamod, bltbmod, bltcmod, bltdmod;

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
    private static final int BLTCON0_USED = 0x0100;
    private static final int BLTCON0_LF   = 0x00FF;

    private static final int BLTCON1_BSH     = 0xF000;
    private static final int BLTCON1_EFE     = 0x0010;
    private static final int BLTCON1_IFE     = 0x0008;
    private static final int BLTCON1_FCI     = 0x0004;
    private static final int BLTCON1_DESC    = 0x0002;
    private static final int BLTCON1_LINE    = 0x0001;

    private static final int BLTCON1_SIGN    = 0x0040;
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
    private static final int[][] LINE_PROG = new int[4][];
    static {
        //                    slot0         slot1     slot2            slot3           slot4  slot5
        // Cadenza LINE dall'oracolo WinUAE (docs/winuae-line-blit-slot-spec.txt,
        // slot RGA catturati con [WBUS] su bbusy7l/9l/13l). Footprint bus per
        // pixel (rel = offset dallo start del pixel):
        //   bc=0 (né B né C, es. USE=A+D $09CA):  bus rel0 (slot A, se USEA)
        //   bc=2 (solo B,   es. USE=A+B+D $0DCA): bus rel0 (A), rel1 (B), rel4 (D dummy)
        //   bc=3 (B+C,      es. USE=B+C+D $07CA): bus rel0 (B), rel1 (C), rel3 (D)
        //   bc=1 (solo C): diagramma classico -C-D (C rel1, D rel3), invariato.
        // Il PERCORSO-DATI resta quello validato (stesse letture B/C, stesso
        // calculateLineWord/shouldWriteLinePixel/executeLineStepping nello stesso
        // ordine): cambia solo la posizione dei cicli di bus. LINE_LAST_BUS marca
        // l'ultimo ciclo di bus del pixel: sull'ULTIMO pixel arma il clear di
        // BBUSY (visibile dal cck successivo, regola misurata sui poll di bbusy*l).
        // Lo slot A (074) è un ciclo di pipe SENZA trasferimento (in line mode
        // BLTAPT è l'accumulatore Bresenham): NON blocca MAI la CPU, con qualunque
        // USE — [WRGA]/[WACC] timestampati su bbusy9l v66 (bc0) e bbusy13l (bc2)
        // mostrano l'accesso CPU nello STESSO cck dello slot 074. Il D-dummy
        // (BUS in bc2) invece alloca il bus (come il dummy BUS di vAmiga).
        // bc0: nessun ciclo di bus in tutto il pixel → il clear BBUSY è armato allo
        // slot REPEAT dell'ultimo pixel (visibile il cck dopo): misurato sui poll
        // DMACONR di bbusy9l — v66 (1px, slot 88-91, poll a 92 legge GIÀ clear) e
        // v74 (5px, slot finali 105-108, poll a 106 BUSY, clear da 109).
        LINE_PROG[0] = new int[]{ LINE_BUS_A, BUSIDLE, HOLD_D|BUSIDLE, BUSIDLE|LINE_LAST_BUS|REPEAT };
        LINE_PROG[1] = new int[]{ BUSIDLE, FETCH_C, HOLD_D|BUSIDLE, WRITE_D|LINE_LAST_BUS|REPEAT };
        LINE_PROG[2] = new int[]{ BUSIDLE, FETCH_B, HOLD_D|BUSIDLE, BUSIDLE, BUS|LINE_LAST_BUS, BUSIDLE|REPEAT };
        LINE_PROG[3] = new int[]{ FETCH_B, FETCH_C, HOLD_D|BUSIDLE, WRITE_D|LINE_LAST_BUS, BUSIDLE, BUSIDLE|REPEAT };
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
    private boolean lineWrite = false; // esito shouldWriteLinePixel() latchato a HOLD_D
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
        running = false; bbusy = false; dmaEnabled = false;
        startPending = false; startCountdown = 0; clearBusyCountdown = 0;
        bltcon0 = 0; bltcon1 = 0;
        bltafwm = 0xFFFF; bltalwm = 0xFFFF;
        bltapt = 0; bltbpt = 0; bltcpt = 0; bltdpt = 0;
        bltamod = 0; bltbmod = 0; bltcmod = 0; bltdmod = 0;
        anew = 0; bnew = 0; aold = 0; bold = 0;
        ahold = 0; bhold = 0; chold = 0; dhold = 0;
        bzero = true; fillCarry = 0; firstDot = true;
        bltpc = 0; lockD = true; birq = false; copyWarmup = 0;
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

            case 0x048: bltcpt = (bltcpt & 0x0000FFFF) | ((value & 0x000F) << 16); break;
            case 0x04A: bltcpt = (bltcpt & 0xFFFF0000) | (value & 0xFFFF);         break;
            case 0x04C: bltbpt = (bltbpt & 0x0000FFFF) | ((value & 0x000F) << 16); break;
            case 0x04E: bltbpt = (bltbpt & 0xFFFF0000) | (value & 0xFFFF);         break;
            case 0x050: bltapt = (bltapt & 0x0000FFFF) | ((value & 0x000F) << 16); break;

            // In Line Mode BLTAPTL è l'errore Bresenham (con segno, 16 bit), non forziamo pari!
            case 0x052: bltapt = (bltapt & 0xFFFF0000) | (value & 0xFFFF);         break;

            case 0x054: bltdpt = (bltdpt & 0x0000FFFF) | ((value & 0x000F) << 16); break;
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
                bnew = value & 0xFFFF;
                // Una scrittura CPU a BLTBDAT carica B-HOLD GIÀ shiftato di BSH (come HW):
                // per i blit con USEB=0 il canale B è preso dal registro, NON da DMA, e il
                // barrel shifter B non viene rieseguito per-word. Il caso USEB=1 ricalcola
                // bhold nella micro-op HOLD_B.
                {
                    int bshDat  = (bltcon1 & BLTCON1_BSH) >>> 12;
                    boolean dsc = (bltcon1 & BLTCON1_DESC) != 0;
                    bhold = dsc ? ((bnew << bshDat) & 0xFFFF) : (bnew >>> bshDat);
                }
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
            running = false; bbusy = false; startPending = false; startCountdown = 0; clearBusyCountdown = 0;
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
        if (!dmaEnabled) { running = false; bbusy = false; clearBusyCountdown = 0; return; }

        recacheAreaBlitFields();

        running = true;
        bbusy   = true;
        bzero   = true;
        birq    = false;
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
            // Warmup idle post-BLTSIZE (spec WinUAE): 3 slot con B+C, 2 altrimenti.
            lineWarmup = (isUsed(BLTCON0_USEB) && isUsed(BLTCON0_USEC)) ? 3 : 2;
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
        clearBusyCountdown = 0; bbusy = false; // niente tick successivi in immediate
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
        if (clearBusyCountdown == 1) {
            bbusy = false;
            clearBusyCountdown = 0;
        }
    }

    /** Esegue la micro-op copy corrente. Ritorna true se ha allocato il bus. */
    private boolean execMicroOp(boolean busFree) {
        // Warmup post-BLTSIZE (vedi startBlit): 2 slot idle soggetti ad arbitraggio
        // (in WinUAE sono richieste RGA regolari: sotto contesa slittano), senza bus.
        if (copyWarmup > 0) {
            if (!busFree) return false;
            copyWarmup--;
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

        // Interrupt BLIT nel ciclo di terminazione (schedulato una volta sola).
        if ((op & BLTDONE) != 0 && !birq) {
            agnus.blitterFinished();
            birq = true;
        }

        // Arbitraggio bus: BUS e BUSIDLE richiedono il bus libero per procedere; le
        // micro-op NOTHING avanzano SEMPRE (anche sotto DMA a priorità superiore).
        if (needBus && !busFree)  return false; // stallo (bltpc invariato, retry)
        if (needIdle && !busFree) return false; // stallo

        boolean tookBus = false;
        bltpc++;

        boolean desc = cachedDesc;

        if ((op & WRITE_D) != 0 && !lockD) {
            int addr = getDpt();
            agnus.blitterImmediateWrite(addr, dhold & 0xFFFF);
            tookBus = true;
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
            setApt(getApt() + (desc ? -2 : 2));
            if (--cntA == 0) { setApt(getApt() + (desc ? -bltamod : bltamod)); cntA = bltsizeH; }
        }
        if ((op & FETCH_B) != 0) {
            bnew = agnus.blitterImmediateRead(getBpt()) & 0xFFFF;
            tookBus = true;
            setBpt(getBpt() + (desc ? -2 : 2));
            if (--cntB == 0) { setBpt(getBpt() + (desc ? -bltbmod : bltbmod)); cntB = bltsizeH; }
        }
        if ((op & FETCH_C) != 0) {
            chold = agnus.blitterImmediateRead(getCpt()) & 0xFFFF;
            tookBus = true;
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
                bbusy = false;
            }
        }

        if ((op & BLTDONE) != 0) {
            endBlit();
        }

        return tookBus;
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

    private void clearBusyFlag() { bbusy = false; }

    private void endBlit() {
        running = false;
        // BBUSY: se il countdown del clear è in corso NON scavalcarlo — le micro-op
        // NOTHING/BLTDONE avanzano anche su cck rubati, quindi sotto contesa il
        // BLTDONE arriva PRIMA dello slot d'uscita che spegne BBUSY (misurato su
        // bbusy0 v202, BPU=4). Il countdown viene completato da tickBusyClear.
        // I path senza countdown (line mode, guardie) azzerano subito come prima.
        if (clearBusyCountdown == 0) bbusy = false;
        state   = State.IDLE;
        // L'interrupt BLIT è già stato asserito nel ciclo BLTDONE (birq). Se per il line
        // mode arriviamo qui senza averlo asserito, lo facciamo ora.
        if (!birq) { agnus.blitterFinished(); birq = true; }
    }

    // =========================================================================
    // Line Mode (percorso-dati validato, guidato single-phase)
    // =========================================================================

    /**
     * Esecuzione LINE per-CCK guidata dai micro-programmi {@link #LINE_PROG} (vAmiga
     * lineBlitInstr). Il PERCORSO-DATI è identico al modello precedente (stesse letture
     * B/C, stesso {@link #calculateLineWord()}, stessa {@link #shouldWriteLinePixel()},
     * stesso {@link #executeLineStepping()} nello stesso ordine): cambia SOLO la cadenza
     * di bus (cicli morti BUSIDLE + dummy BUS), quindi l'output è bit-identico e solo la
     * contesa di bus si allinea all'hardware.
     */
    private boolean lineStepCck(boolean busFree) {
        // Warmup post-BLTSIZE (oracolo WinUAE): slot idle prima del primo ciclo
        // reale del primo pixel (3 con B+C, 2 negli altri casi misurati).
        if (lineWarmup > 0) {
            if (!busFree) return false;
            lineWarmup--;
            return false;
        }
        int bc = (isUsed(BLTCON0_USEB) ? 2 : 0) | (isUsed(BLTCON0_USEC) ? 1 : 0);
        int[] prog = LINE_PROG[bc];
        int op = prog[linePc];

        // WRITE_D prende il bus solo se scrive davvero (single-pixel/SING → salta);
        // FETCH_B/FETCH_C/BUS lo prendono sempre; BUSIDLE richiede solo bus libero.
        // Lo slot A (074) NON prende MAI il bus, con qualunque USE: in line mode
        // BLTAPT è l'accumulatore Bresenham, non un puntatore — è un ciclo di pipe
        // senza trasferimento. Misurato sui [WRGA]/[WACC] timestampati (stesso
        // cy = stesso cck) di bbusy9l v66 (bc0, USE=A+D): accesso CPU nello STESSO
        // cck dello slot 074. La vecchia regola per-USE (bc0 bloccante, bc2
        // permeabile) era un artefatto del tagging non timestampato.
        boolean needBus  = (op & (FETCH_B | FETCH_C | BUS)) != 0
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
            if ((op & REPEAT) != 0) bbusy = false;
            else clearBusyCountdown = 1;
        }

        if ((op & FETCH_B) != 0) {
            bnew = agnus.blitterImmediateRead(getBpt()) & 0xFFFF;
            setBpt(getBpt() + bltbmod);
            tookBus = true;
        }
        if ((op & FETCH_C) != 0) {
            chold = agnus.blitterImmediateRead(getCpt()) & 0xFFFF;
            tookBus = true;
        }
        if ((op & BUS) != 0) tookBus = true; // ciclo di bus fittizio (vAmiga)

        if ((op & HOLD_D) != 0) {
            dhold = calculateLineWord();
            if (dhold != 0) bzero = false;
            lineWrite = shouldWriteLinePixel();
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

    private boolean shouldWriteLinePixel() {
        boolean useC = isUsed(BLTCON0_USEC);
        boolean sing = (bltcon1 & BLTCON1_SING) != 0;
        return useC && (!sing || firstDot);
    }

    private void stepIncX() {
        int current_ash = (bltcon0 & BLTCON0_ASH) >>> 12;
        if (current_ash == 0xF) {
            bltcon0 = (bltcon0 & ~BLTCON0_ASH);
            setCpt(getCpt() + 2);
        } else { bltcon0 += 0x1000; }
    }
    private void stepDecX() {
        int current_ash = (bltcon0 & BLTCON0_ASH) >>> 12;
        if (current_ash == 0) {
            bltcon0 |= BLTCON0_ASH;
            setCpt(getCpt() - 2);
        } else { bltcon0 -= 0x1000; }
    }
    private void stepIncY() { setCpt(getCpt() + bltcmod); fillCarry = 1; firstDot = true; }
    private void stepDecY() { setCpt(getCpt() - bltcmod); fillCarry = 1; firstDot = true; }

    private void executeLineStepping() {
        firstDot = false;

        boolean sign = (bltcon1 & BLTCON1_SIGN) != 0;
        fillCarry = 0;

        if (!sign) {
            if ((bltcon1 & BLTCON1_SUD) != 0) {
                if ((bltcon1 & BLTCON1_SUL) != 0) stepDecY(); else stepIncY();
            } else {
                if ((bltcon1 & BLTCON1_SUL) != 0) stepDecX(); else stepIncX();
            }
        }

        if ((bltcon1 & BLTCON1_SUD) != 0) {
            if ((bltcon1 & BLTCON1_AUL) != 0) stepDecX(); else stepIncX();
        } else {
            if ((bltcon1 & BLTCON1_AUL) != 0) stepDecY(); else stepIncY();
        }

        if (isUsed(BLTCON0_USEA)) {
            int errDelta = sign ? bltbmod : bltamod;
            short currentApt = (short) (bltapt & 0xFFFF);
            currentApt += errDelta;
            bltapt = (bltapt & 0xFFFF0000) | (currentApt & 0xFFFF);
        }

        short newAptSigned = (short)(bltapt & 0xFFFF);
        if (newAptSigned < 0) bltcon1 |= BLTCON1_SIGN;
        else bltcon1 &= ~BLTCON1_SIGN;

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

    private int getApt() { return bltapt & 0xFFFFE; }
    private int getBpt() { return bltbpt & 0xFFFFE; }
    private int getCpt() { return bltcpt & 0xFFFFE; }
    private int getDpt() { return bltdpt & 0xFFFFE; }

    private void setApt(int v) { bltapt = v & 0xFFFFE; }
    private void setBpt(int v) { bltbpt = v & 0xFFFFF; bltbpt &= 0xFFFFE; }
    private void setCpt(int v) { bltcpt = v & 0xFFFFF; bltcpt &= 0xFFFFE; }
    private void setDpt(int v) { bltdpt = v & 0xFFFFF; bltdpt &= 0xFFFFE; }

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

    public void setDmaEnabled(boolean enabled) {
        this.dmaEnabled = enabled;
        if (!enabled) {
            startPending = false; startCountdown = 0;
            if (running || bbusy) {
                running = false; bbusy = false; clearBusyCountdown = 0; state = State.IDLE;
            }
        }
    }
    public boolean isZero() { return bzero; }
    public int getBltcon0() { return bltcon0; }
    public int getBltcon1() { return bltcon1; }
    public State getState() { return state; }
}
