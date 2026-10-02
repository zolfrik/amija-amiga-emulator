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
 * Emulazione del meccanismo floppy disk Amiga.
 *
 * Segue le specifiche hardware Commodore Amiga:
 * - /MTR  (Active LOW): Latchato sul fronte di discesa di /SEL.
 * - /SELx (Active LOW): Seleziona il drive.
 * - /STEP (Active LOW): Un impulso (fronte di discesa) muove la testina.
 * - DIR   (0=INWARD/tk++, 1=OUTWARD/tk0): Direzione del passo.
 * - /RDY  (Active LOW): LOW quando il motore è a regime o bit ID valido.
 * - /TK0  (Active LOW): LOW quando la testina è sulla traccia 0.
 * - /WPRO (Active LOW): LOW quando il disco è protetto da scrittura.
 * - /CHNG (Active LOW): Latchato a LOW al cambio disco, resettato da STEP con disco.
 *
 * Correzioni rispetto alla revisione precedente:
 *  1. DriveType enum con ID corretti (DD_INT=0x00000000, DD_EXT=0xFFFFFFFF, HD_EXT=0xAAAAAAAA).
 *  2. CYCLES_MOTOR_STOP portato a ~3 s PAL reali (~21 M cicli).
 *  3. bitPointer preserva la posizione angolare reale al cambio traccia.
 *  4. isReadyRaw(): sequenza ID terminata correttamente dopo 32 bit (/RDY HIGH).
 *  5. Side change posticipato al termine del settle (non immediato).
 *  6. Header checksum include lo XOR dei longword del label.
 *  7. executeStep(): settle counter usa Math.max per gestire step burst.
 *  8. MAX_TRACKS=84 per compatibilità con drive che leggono oltre la traccia 79.
 */
public final class FloppyDrive {

    // -------------------------------------------------------------------------
    // Tipo di drive — determina l'ID hardware esposto sul pin /RDY
    // -------------------------------------------------------------------------

    public enum DriveType {
        /** DF0: drive interno DD senza ID hardware esterno. */
        DD_INT(0x00000000),
        /** DF1/DF2/DF3: drive esterno 3.5" DD standard. */
        DD_EXT(0xFFFFFFFF),
        /** Drive esterno HD (A3000/A4000) */
        HD_EXT(0xAAAAAAAA);

        final int id;
        DriveType(int id) { this.id = id; }
    }

    // -------------------------------------------------------------------------
    // Identità
    // -------------------------------------------------------------------------

    private final String    id;
    private final DriveType driveType;

    // -------------------------------------------------------------------------
    // Costanti temporali PAL (clock base: 7.09379 MHz)
    // -------------------------------------------------------------------------

    private static final long   CYCLES_INDEX_PULSE       =    14_187L;   // durata impulso INDEX (~2 ms)
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

    /** FIX #2: il motore reale impiega ~3 s per fermarsi completamente. */
    private static final long   CYCLES_MOTOR_STOP        = 21_000_000L;  // rampa arresto motore (~3 s)
    private static final long   CYCLES_SETTLE_STEP       =    21_281L;   // settle dopo step stesso verso (~3 ms)
    /**
     * Distanza minima fra due impulsi di STEP perche' il secondo sia accettato:
     * {@code MIN_STEPLIMIT_CYCLE = CYCLE_UNIT * 140} dell'oracolo, cioe' <b>140 CCK</b>.
     * Qui il clock base e' 7,09 MHz — due unita' per CCK — quindi <b>280</b>.
     */
    private static final long   CYCLES_MIN_STEP_INTERVAL =         280L;   // 140 CCK
    private static final long   CYCLES_SETTLE_DIR_CHG    =   127_688L;   // settle dopo inversione direzione (~18 ms)
    /**
     * Durata di una cella MFM a doppia densità, in master-cycle. È il
     * {@code NORMAL_FLOPPY_SPEED} di WinUAE (disk.cpp), che vale {@code 7 * 256}
     * dove l'unità è 1/256 di CCK ({@code DISK_update} accumula
     * {@code (hpos << 8) - disk_hpos}): <b>7 CCK esatti = 14 master-cycle</b>.
     *
     * <p>Non è "2 µs": 1,9735 µs. I due modi di scriverlo differiscono dell'1,34%
     * e il valore giusto è quello dell'oracolo anche fisicamente, perché è l'unico
     * che fa durare un giro del piatto 200 ms tondi — la traccia nominale ha
     * {@link #NOMINAL_TRACK_BITS} = 101344 celle e 101344 × 7 CCK = 709408 CCK =
     * 200,0 ms, cioè i 300 giri/min di un drive Amiga. Con 2 µs il giro diventava
     * 202,7 ms, cioè il piatto girava all'1,34% in meno: un errore invisibile su
     * un singolo settore ma che sul caricamento del bootblock di Kickstart valeva
     * 15112 CCK di ritardo (docs/vamigats-paula-audio-plan.md §13.9.4).
     */
    private static final double CYCLES_PER_BIT           =    14.0;
    /**
     * Bit per giro di una traccia DD di lunghezza nominale — WinUAE
     * {@code 2 * 8 * FLOPPY_WRITE_LEN} con {@code FLOPPY_WRITE_LEN_PAL/2 = 6334}.
     * È il riferimento rispetto a cui si scala la durata di cella delle long track.
     */
    private static final int    NOMINAL_TRACK_BITS       = 12_668 * 8;   // 101 344
    /** FIX #8: alcuni drive arrivano a traccia 83 (indice 0-based). */
    private static final int    MAX_TRACKS               = 84;
    private static final int    BIT_CLOCK_SCALE          = 10_000;
    /** {@link #CYCLES_PER_BIT} in virgola fissa ({@link #BIT_CLOCK_SCALE}), percorso ADF. */
    private static final int    ADF_CYCLES_PER_BIT_FIXED = 140_000;
    private static final int    TRACK_BUFFER_SIZE        = DiskImage.TRACK_BUFFER_BYTES;
    private static final int    ID_SEQUENCE_BITS         = 32;

    // -------------------------------------------------------------------------
    // Stato motore
    // -------------------------------------------------------------------------

    private boolean motorTarget  = false;
    private boolean motorAtSpeed = false;
    /** Horizontal syncs left before /RDY rises; reloaded whole at every motor-on. */
    private int     readyUpLines = 0;
    private long    switchCycle  = 0L;
    private long    totalCycles  = 0L;
    /** {@code steplimitcycle}: istante dell'ultimo impulso di STEP ACCETTATO. */
    private long    lastAcceptedStepCycle = Long.MIN_VALUE / 4;

    // -------------------------------------------------------------------------
    // Posizionamento testina
    // -------------------------------------------------------------------------

    private int     currentTrack      = 0;
    private int     targetTrack       = 0;
    private long    settleCounter     = 0L;
    private boolean currentSide       = false;

    /** FIX #5: side change pendente — effettivo solo al termine del settle. */

    // -------------------------------------------------------------------------
    // Disco
    // -------------------------------------------------------------------------

    /** Immagine disco corrente; null = nessun disco con file backing. */
    private DiskImage diskImage      = null;

    private boolean diskInserted   = false;
    private boolean writeProtected = false;

    /** Latch cambio disco: true = segnale /CHNG LOW (attivo). */
    private boolean dskchange = false;
    /**
     * Vero da quando la macchina ha cominciato a girare (primo {@link #tick}). Serve a
     * distinguere l'inserimento <b>a freddo</b> — quello del config, prima dell'accensione,
     * che per WinUAE non è un cambio — da quello <b>a caldo</b>, che latcha {@code /CHNG}.
     * ⚠️ Il reset NON lo azzera: un Ctrl-Amiga-Amiga non rimette la macchina nello stato
     * «spenta col disco già dentro».
     */
    private boolean machineRunning = false;

    /** true = unità fisicamente connessa al bus; false = drive assente (DF1-DF3 non collegati). */
    private boolean connected = true;

    // -------------------------------------------------------------------------
    // Impulso INDEX
    // -------------------------------------------------------------------------

    private boolean indexPulse        = false;
    private long    indexPulseCounter = 0L;

    // -------------------------------------------------------------------------
    // Shift register ID drive
    // -------------------------------------------------------------------------

    private boolean idBit      = true;
    private int     idBitIndex = 0;

    // -------------------------------------------------------------------------
    // Flusso MFM
    // -------------------------------------------------------------------------

    // Buffer dimensionato sul massimo (long track di protezione); la lunghezza
    // effettiva della traccia corrente è in trackLenBytes (wrap della rotazione).
    private final byte[] trackBuffer          = new byte[DiskImage.MAX_TRACK_BYTES];
    private       int    trackLenBytes        = TRACK_BUFFER_SIZE;
    // Lunghezza ESATTA della traccia in bit (le long track IPF non sono multiple di 8):
    // la rotazione avvolge a questo valore, non a trackLenBytes*8.
    private       int    trackBitLen          = TRACK_BUFFER_SIZE * 8;

    // ── Modello FDC (densità di cella) ───────────────────────────────────────
    // Per gli IPF la durata della cella segue WinUAE (disk.cpp
    // get_floppy_speed_from_image):
    //
    //     trackspeed = NORMAL_FLOPPY_SPEED * tracklen / (2 * 8 * FLOPPY_WRITE_LEN)
    //
    // cioè la cella resta al rate DD nominale (CYCLES_PER_BIT) e una traccia più LUNGA fa
    // durare di più il giro — l'impulso di INDEX nasce dall'avvolgimento del
    // bitstream, quindi una long track ha un "giro" più lungo di 200 ms. È così
    // che le protezioni "long track" (es. North &amp; South, sync custom $A144)
    // misurano la lunghezza della traccia rispetto all'impulso index.
    //
    // ⚠️ Il modello precedente era l'OPPOSTO (giro costante di 200 ms, celle più
    // corte su traccia lunga): su Dragon's Lair i dati letti dalle long track
    // (107044 bit contro i 101344 nominali, +5.6%) arrivavano a un rate sbagliato
    // dell'11% e il decompressore riceveva un flusso corrotto — il suo contatore
    // saltava lo zero, il loop scriveva oltre la chip RAM e (via mirror a 512 KB)
    // sovrascriveva il proprio codice a $001080 con un opcode illegale.
    //
    // Sulle tracce a densità VARIABILE (Copylock) WinUAE non applica la scala di
    // cui sopra ({@code if (!drv->tracktiming[0])}): la base resta quella nominale
    // e il timing viene tutto dalla mappa per-byte.
    //
    // Per gli ADF il modello è BYPASSATO: si usa il rate DD nominale fisso
    // (CYCLES_PER_BIT, 7 CCK/cella), comportamento identico a prima e a costo zero.
    private       boolean densityModel        = false;
    // Durata di cella pre-calcolata per byte di traccia (solo tracce IPF a densità
    // variabile, es. Copylock); null = periodo costante cyclesPerBit.
    private       double[] cellCycles         = null;
    private       double  cyclesPerBit        = CYCLES_PER_BIT;
    private       int    bitPointer           = 0;
    private       double bitCyclesAccumulator = 0.0;
    private       long   bitCyclesAccumulatorFixed = 0;
    // The reader of this tick runs at 4 us per bit (see tick(int, IntConsumer, boolean)).
    private       boolean slowBits            = false;
    // The cell where the reader slips its jitter cells, -1 for none (see slipJitterCells).
    private       int    jitterSkipCell       = -1;

    // -------------------------------------------------------------------------
    // Scrittura sul supporto
    // -------------------------------------------------------------------------

    /**
     * Sorgente delle celle MFM da incidere sul supporto: è lo shift register di
     * scrittura di Paula, interrogato una volta per cella (vedi Paula.nextWriteBit).
     */
    @FunctionalInterface
    public interface WriteBitSource {
        /** @return 0/1 = cella da scrivere, {@code -2} = nessun dato pronto, {@code -1} = fine. */
        int nextWriteBit();
    }

    /** Sentinelle di {@link WriteBitSource#nextWriteBit()}. */
    private static final int WRITE_BIT_STALL = Paula.WRITE_BIT_STALL;
    private static final int WRITE_BIT_END   = Paula.WRITE_BIT_END;

    private WriteBitSource writeBitSource = null;

    /** true mentre la testina incide il supporto invece di leggerlo. */
    private boolean writing = false;

    /** true se trackBuffer contiene celle scritte non ancora riversate nell'immagine. */
    private boolean trackModified = false;

    /** Evita di ripetere l'avviso di formato non AmigaDOS ad ogni traccia. */
    private boolean customFormatWarned = false;

    // -------------------------------------------------------------------------
    // Stato segnali di ingresso
    // -------------------------------------------------------------------------

    private boolean selected     = false;
    private boolean prevSelected = false;
    private boolean prevStep     = false;
    private boolean lastStepDir  = false;

    // =========================================================================
    // Costruttori
    // =========================================================================

    public FloppyDrive(String id, DriveType driveType) {
        this.id        = id;
        this.driveType = driveType;
        this.angleRng  = newAngleRng(id);
        this.readyTailRng = newDriveRng(id, 1);
        this.jitterRng = newDriveRng(id, 2);
        this.shiftRng = newDriveRng(id, 3);
        reset();
    }

    /** Compatibilità con codice precedente: DF0=DD_INT, altri=DD_EXT. */
    public FloppyDrive(String id) {
        this(id, "DF0".equals(id) ? DriveType.DD_INT : DriveType.DD_EXT);
    }

    public void setConnected(boolean connected) { this.connected = connected; statusDirty = true; }

    /** true se l'unità è fisicamente collegata al bus (DF0 sempre; DF1-DF3 opzionali). */
    public boolean isConnected() { return connected; }

    /** Identificativo dell'unità ("DF0".."DF3"). */
    public String getId() { return id; }

    // Suono ESTETICO del drive (click/spin/startup). Null = nessun suono, costo zero.
    // Agganciato solo da chi vuole la feature (attualmente Amija); vedi FloppySound.
    private FloppySound floppySound;
    public void setFloppySound(FloppySound sound) { this.floppySound = sound; }

    // =========================================================================
    // Tick principale
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
        this.slowBits = slowBits;
        totalCycles += cycles;
        machineRunning = true;   // vedi insertDisk: distingue il freddo dal caldo

        // Flush periodico dell'immagine disco (cheap: esce subito se non ci sono
        // tracce dirty da scrivere — caso normale in gioco con disco di sola lettura).
        if (diskImage != null) diskImage.tickCycles(cycles);

        // PERF: fast-path "dormiente". Un drive col motore fermo da tempo (velocità
        // ormai 0) e senza seek in corso non ha nulla da fare ad ogni master-cycle:
        // il corpo sottostante si limiterebbe a ri-azzerare campi già a zero. Vale
        // per i drive vuoti df1-df3 E per df0 a motore spento col disco inserito
        // (la condizione tipica durante il gioco, dopo il caricamento). Elimina la
        // maggior parte delle ~28M chiamate "piene"/s. The /RDY spin-up does not
        // depend on this path: hsync() counts it.
        if (!motorTarget && !motorAtSpeed && settleCounter <= 0
                && (totalCycles - switchCycle) >= CYCLES_MOTOR_STOP) {
            return;
        }

        // PERF (vedi statusDirty): fuori dallo stato stazionario "motore a regime"
        // i segnali possono cambiare per il solo passare del tempo — /RDY quando la
        // rampa completa (motorAtSpeed, deciso poco sotto) e /INDEX a ogni giro del
        // piatto. Qui il flag resta sempre alto: è la rampa del motore, non il
        // caricamento a regime.
        if (!(motorTarget && motorAtSpeed)) statusDirty = true;

        // /RDY spin-up is counted in lines by hsync(). The motor going off drops
        // DSKRDY at once; an empty drive never asserts it (isReadyRaw).
        if (!motorTarget && motorAtSpeed) {
            motorAtSpeed = false;
        }

        // Rotazione del supporto. Modello di vAmiga (FloppyDrive::read8AndRotate:
        // "if (motor) rotate()"): il piatto gira — e la testina legge — non appena il
        // motore è COMANDATO acceso, senza attendere il regime. La rampa di velocità
        // governa solo /RDY (isReadyRaw), non il flusso di dati.
        //
        // In precedenza il bitstream era congelato finché motorAtSpeed non diventava
        // vero, mentre l'INDEX era generato da un contatore di cicli indipendente:
        // ad ogni accensione/spegnimento del motore i due si sfasavano di una quantità
        // arbitraria. Ora l'INDEX nasce dall'avvolgimento di bitPointer
        // (vedi advanceBitPointer), quindi foro indice e bit 0 della traccia sono la
        // stessa posizione angolare, come su HW reale.
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
                    statusDirty = true;   // fine dell'impulso di INDEX
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
    // Aggiornamento segnali (chiamato da Agnus/CIA ogni ciclo DMA o evento)
    // =========================================================================

    /**
     * CIA-B reset: the drive takes the control lines as they stand, without acting on
     * their edges. A /SEL that reads active here latches no motor and shifts no ID bit;
     * the next {@link #update} compares against this state.
     */
    public void selectSet(boolean selected, boolean step) {
        statusDirty  = true;
        this.selected = connected && selected;
        prevSelected = this.selected;
        prevStep     = step;
    }

    public void update(boolean selected, boolean motorOn, boolean step,
                       boolean direction, boolean side) {
        statusDirty = true;   // chiamata solo quando il PRB di CIA-B è cambiato
        if (!connected) {
            this.selected = false;
            prevSelected = false;
            prevStep = step;
            return;
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
                // Transizione motore o motore già ON: azzera shift register ID
                idBitIndex = 0;
                idBit      = false; // /RDY inattivo (HIGH)
                statusDirty = true;
            } else {
                // Motore OFF: shifta il prossimo bit dell'ID
                updateIdBit();
                if (idBitIndex < ID_SEQUENCE_BITS) idBitIndex++;
            }
        }

        this.selected = selected;

        if (selected) {
            // /SIDE acts at once: the head that reads is chosen electronically, with no
            // settle, so the bits that follow the write already come from the new side.
            if (this.currentSide != side) {
                flushWrittenTrack();   // the track written so far belongs to the old side
                currentSide = side;
                if (diskInserted && diskImage != null) loadCurrentTrack();
            }

            // /STEP is active LOW on the bus; Agnus passes true while the
            // line is asserted, so the physical falling edge is false -> true.
            //
            // ⛔ Un impulso troppo ravvicinato al precedente ACCETTATO viene IGNORATO: la
            // testina non ce la fa. E' `drive_step()` dell'oracolo (disk.cpp):
            //
            //     if (drv->steplimit && get_cycles() - drv->steplimitcycle < MIN_STEPLIMIT_CYCLE)
            //         return;                       // passo ignorato
            //     set_steplimit(drv);               // solo per i passi ACCETTATI
            //
            // con `MIN_STEPLIMIT_CYCLE = CYCLE_UNIT * 140`. ⚠️ La guardia ha due termini, ma
            // il secondo non e' mai vincolante: `steplimit` parte da 4 e cala di uno per
            // hsync, e in meno di 140 CCK passa al massimo UN hsync — quindi quando il
            // tempo e' sotto soglia il contatore di righe e' sempre != 0. La regola si
            // riduce alla sola distanza in cicli.
            if (!prevStep && step) {
                if (totalCycles - lastAcceptedStepCycle >= CYCLES_MIN_STEP_INTERVAL) {
                    lastAcceptedStepCycle = totalCycles;
                    executeStep(direction);
                }
            }
        }

        prevSelected = selected;
        prevStep     = step;
    }

    // =========================================================================
    // Helpers interni
    // =========================================================================

    /** Aggiorna il bit ID esposto su /RDY quando il motore e' spento. */
    private void updateIdBit() {
        statusDirty = true;   // /RDY trasporta il bitstream dell'ID
        if (!connected) {
            idBit = false;
            return;
        }
        idBitIndex &= (ID_SEQUENCE_BITS - 1);
        idBit = ((driveType.id >>> (31 - idBitIndex)) & 1) != 0;
    }

    /**
     * PERF: indica se il drive richiede elaborazione per-ciclo (tick + update).
     * È "busy" se selezionato, col motore acceso/in rampa o ancora in fase di
     * spin-down, oppure durante un seek. Quando NESSUN drive è busy e i segnali
     * di controllo CIA-B non cambiano, Agnus salta del tutto il lavoro floppy.
     * Cheap: solo confronti, nessuna divisione.
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
        statusDirty = true;   // /TK0 e /CHNG cambiano allo step
        long waitTime = (direction == lastStepDir) ? CYCLES_SETTLE_STEP : CYCLES_SETTLE_DIR_CHG;
        lastStepDir = direction;

        // HRM: "it is reset by a STEP pulse when there is a disk in the drive."
        if (diskInserted) dskchange = false;

        // HRM: DIR 0 = Inward (+1), 1 = Outward (-1)
        int move = direction ? -1 : 1;
        targetTrack = Math.max(0, Math.min(MAX_TRACKS - 1, targetTrack + move));

        // La testina si sposta di una traccia ALL'IMPULSO di STEP: aggiorniamo
        // currentTrack e ricarichiamo il buffer SUBITO. Il settle (sotto) è solo un
        // assestamento meccanico che tiene il drive "busy"; NON deve ritardare quale
        // traccia è leggibile. Cfr. vAmiga FloppyDrive::step() (aggiorna head.cylinder
        // all'impulso; il delay serve solo a spaziare gli step e segnalare il
        // completamento, non a posticipare i dati).
        //
        // Ritardare currentTrack a fine settle (modello precedente) faceva leggere la
        // traccia VECCHIA per tutta la durata del settle — fino a ~18ms su inversione
        // di direzione (CYCLES_SETTLE_DIR_CHG). Un loader che dopo lo step rilegge
        // l'header per verificare la posizione (SpeedBall II: recalibrate → step a
        // cyl1) leggeva cyl0 durante il settle → ri-step → ri-armava il settle con
        // Math.max → stallo infinito (motore spento, schermo nero). L'esito dipendeva
        // dalla fase di timing CPU↔disco (a volte il settle completava in tempo, a
        // volte no): da qui la non-determinazione.
        if (currentTrack != targetTrack) {
            flushWrittenTrack();   // la traccia incisa appartiene al cilindro VECCHIO
            currentTrack = targetTrack;
            if (diskInserted && diskImage != null) loadCurrentTrack();
        }

        // FIX #7: Math.max garantisce che uno step burst non accorci il settle
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

    private void tickMfmBitsDensityModel(int cycles, IntConsumer bitConsumer) {
        bitCyclesAccumulator += cycles;
        double cell = currentCellCycles();
        while (bitCyclesAccumulator >= cell) {
            bitCyclesAccumulator -= cell;
            processCell(bitConsumer);
            cell = currentCellCycles();
        }
    }

    /**
     * Una cella MFM passa sotto la testina: viene letta (e consegnata a Paula) o,
     * in modalità scrittura, sovrascritta con la cella fornita da Paula.
     */
    private void processCell(IntConsumer bitConsumer) {
        if (writing) {
            int bit = (writeBitSource != null) ? writeBitSource.nextWriteBit() : WRITE_BIT_END;
            if (bit == WRITE_BIT_END) {
                // Trasferimento concluso: la traccia incisa va riversata nell'immagine.
                setWriting(false);
                if (bitConsumer != null) bitConsumer.accept(getNextMFMBit());
            } else if (bit != WRITE_BIT_STALL) {
                writeCellToTrack(bit);
            }
            // WRITE_BIT_STALL: la cella scorre senza essere alterata (la DMA non ha
            // ancora fornito la word successiva).
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
        int b1 = getNextMFMBit();
        int b2 = mfmBitAt(bitPointer + 1 < trackBitLen ? bitPointer + 1 : 0);
        bitConsumer.accept(b1 | b2);
        int cells = b2 != 0 ? 3 : 2;
        for (int i = 0; i < cells; i++) advanceBitPointer();
    }

    /** Incide una cella MFM sul supporto alla posizione angolare corrente. */
    private void writeCellToTrack(int bit) {
        if (!diskInserted || writeProtected) return;   // nessun supporto / linguetta chiusa
        int byteIdx = bitPointer >> 3;
        if (byteIdx >= trackBuffer.length) return;
        int mask = 1 << (7 - (bitPointer & 7));
        if (bit != 0) trackBuffer[byteIdx] |= (byte) mask;
        else          trackBuffer[byteIdx] &= (byte) ~mask;
        trackModified = true;
    }

    /**
     * Riversa nell'immagine disco le celle incise sulla traccia corrente,
     * decodificando i settori AmigaDOS (vedi DiskImage.writeTrackMFM).
     * Va chiamato PRIMA di ogni cambio di traccia/lato e alla fine della scrittura.
     */
    private void flushWrittenTrack() {
        if (!trackModified) return;
        trackModified = false;
        if (diskImage == null || diskImage.isReadOnly()) return;
        int physicalTrack = currentTrack * 2 + (currentSide ? 1 : 0);
        int sectors = diskImage.writeTrackMFM(physicalTrack, trackBuffer, trackLenBytes);
        if (sectors == 0 && !customFormatWarned) {
            // Un ADF può contenere solo settori AmigaDOS standard: se il software
            // usa un proprio formato (come fanno alcuni giochi con disco utente) i
            // dati non sono rappresentabili. Stessa limitazione di WinUAE; la si
            // segnala una volta sola perché altrimenti la perdita sarebbe silenziosa.
            customFormatWarned = true;
            System.err.printf("[%s] traccia %d scritta in un formato non AmigaDOS: "
                    + "non e' rappresentabile in un file .adf e non viene salvata%n",
                    id, physicalTrack);
        }
    }

    /**
     * Entra/esce dalla modalità di scrittura. All'uscita la traccia incisa viene
     * decodificata e scritta nell'immagine (il file .adf è poi aggiornato dal flush
     * periodico di DiskImage, dall'eject o dallo shutdown hook).
     */
    public void setWriting(boolean value) {
        if (writing == value) return;
        writing = value;
        if (!value) flushWrittenTrack();
    }

    public boolean isWriting() { return writing; }

    public void setWriteBitSource(WriteBitSource source) { this.writeBitSource = source; }

    /**
     * Durata della cella alla posizione angolare corrente. Con densità uniforme è
     * costante; sulle tracce a densità variabile (Copylock) segue la mappa per-byte
     * dell'IPF, come WinUAE {@code disk.cpp updatetrackspeed()}:
     * {@code trackspeed = base * tracktiming[mfmpos / 8] / 1000}.
     */
    private double currentCellCycles() {
        if (cellCycles == null) return cyclesPerBit;
        int idx = bitPointer >> 3;
        return cellCycles[idx < cellCycles.length ? idx : cellCycles.length - 1];
    }

    /**
     * Avanza la testina di una cella. L'avvolgimento a fine traccia è il passaggio del
     * foro di INDEX: è lì che va generato l'impulso, perché il foro e il bit 0 della
     * traccia sono la stessa posizione angolare sul supporto.
     *
     * <p>Prima l'INDEX era generato da un contatore di cicli indipendente
     * ({@code indexCounter}), che si sfasava dal bitstream ogni volta che il motore
     * veniva spento e riacceso (il bitstream restava fermo fuori regime mentre il
     * contatore correva). Le protezioni che leggono "il primo sync dopo l'INDEX" —
     * es. il Rob Northen Copylock di Castle Master — finivano su un settore casuale.
     */
    private void advanceBitPointer() {
        if (++bitPointer >= trackBitLen) {
            bitPointer        = 0;
            indexPulse        = true;
            indexPulseCounter = 0;
            statusDirty       = true;   // inizio dell'impulso di INDEX
        }
    }

    private void resetBitClock() {
        bitCyclesAccumulator = 0.0;
        bitCyclesAccumulatorFixed = 0;
    }

    /**
     * Disk DMA start, on every drive: the time accumulated towards the next cell is
     * dropped, so the next cell is counted from this instant.
     */
    public void restartBitCell() {
        resetBitClock();
    }

    // =========================================================================
    // Segnali di uscita
    // =========================================================================

    /**
     * /RDY raw, modellato su WinUAE ({@code disk.cpp}, {@code DISK_status_ciaa} +
     * {@code drive_motor}). Il discriminante è il <b>flip-flop</b> del motore
     * ({@code drive_running} = {@code !motoroff}), mai la velocità del piatto:
     *
     * <pre>
     *   motore ACCESO  → /RDY = DSKRDY, che sale a fine rampa e solo con un disco
     *   motore SPENTO  → /RDY = bit ID del drive (DSKRDY è già caduto)
     * </pre>
     *
     * <p>La caduta di DSKRDY è <b>istantanea</b>: {@code drive_motor()} esegue
     * {@code if (drv->motoroff) drv->dskready = 0;} nello stesso istante in cui
     * il flip-flop si spegne. Non esiste alcuna "grazia di spin-down": il piatto
     * continua a girare — e la testina a leggere, vedi {@link #tick} — ma la
     * linea /RDY no.
     *
     * <p><b>Perché la grazia è un bug e non un dettaglio</b> (KS 2.0/3.1 non
     * bootava da floppy, 2026-08-08). La sequenza con cui il Kickstart legge
     * l'ID del drive accende il motore, lo rispegne <i>subito</i> e poi batte 32
     * impulsi di /SEL leggendo /RDY: per tutta la durata della lettura il drive
     * è quindi in spin-down. Con la grazia attiva i primi bit letti non erano
     * l'ID ma {@code diskInserted}, così DF0 <b>con un disco dentro</b>
     * annunciava un ID spurio ($E0000000) invece di $00000000. Kickstart 1.3 non
     * consulta l'ID dell'unità 0 e bootava lo stesso; trackdisk 2.0+ sì, non
     * riconosceva il drive e non montava mai l'unità — schermo fermo e motore
     * mai avviato. Col drive <i>vuoto</i> il bug non si vedeva ({@code
     * diskInserted} falso ⇒ 32 zeri ⇒ ID corretto), il che spiega l'asimmetria
     * "senza disco arriva la manina, con il disco non parte niente".
     *
     * <p>Lo stesso meccanismo, sui drive esterni, era già stato corretto nel
     * senso opposto (cambio disco a caldo sotto Workbench): un DF1 vuoto
     * restituiva $00000000 = "nessun drive" e non veniva più interrogato. Ora la
     * regola è una sola per tutti i drive.
     *
     * <p>DF0 (interno A500) non ha hardware di ID — è il caso {@code
     * cs_df0idhw=0} di WinUAE — e infatti {@link DriveType#DD_INT} vale
     * $00000000: {@code idBit} resta sempre falso e la linea legge zeri, che è
     * ciò che trackdisk si aspetta da un drive Amiga standard. Non è modellata
     * la finestra {@code motordelay} di WinUAE (30 cicli CPU dopo lo
     * spegnimento, aggiunta lì per Pro Tennis Tour).
     */
    public boolean isReadyRaw() {
        if (!connected) return false;
        if (motorTarget) {
            // Motore comandato acceso: /RDY è DSKRDY, che sale allo scadere del
            // conto alla rovescia dello spin-up e solo se un disco è presente.
            return motorAtSpeed && diskInserted;
        }
        // Motore comandato spento: DSKRDY è già caduto, la linea porta il bit ID.
        return idBit;
    }

    // =========================================================================
    // PERF: invalidazione dei segnali di stato osservabili dal bus
    // =========================================================================
    //
    // I 5 segnali che il drive impone sui pin CIA (/RDY, /TK0, /WPRO, /CHNG,
    // /INDEX) venivano ricampionati e ri-pubblicati da AbstractAgnus.updateFloppy
    // ad OGNI CCK di un drive attivo (~3,5M volte/s, 5 chiamate ciascuna). Ma a
    // regime — motore a piena velocità durante un caricamento — quei 5 valori
    // restano costanti per milioni di cicli: la ri-pubblicazione è identica a sé
    // stessa. Questo flag segnala quando uno di essi PUÒ essere cambiato, così il
    // chiamante salta il lavoro quando non è cambiato nulla.
    //
    // La regola è CONSERVATIVA: nel dubbio il flag resta alto. In particolare
    // resta alto per tutta la rampa del motore (accensione e spin-down), dove
    // /RDY e /INDEX dipendono dal tempo trascorso — motorAtSpeed e l'avvolgimento
    // di bitPointer cambiano dentro tick(), senza che nessuno scriva un campo da
    // fuori. Si sfrutta il solo stato stazionario: motore a regime, oppure fermo
    // da tempo.
    private boolean statusDirty = true;

    /** True se uno dei 5 segnali di stato può essere cambiato dall'ultima pubblicazione. */
    public boolean isStatusDirty() { return statusDirty; }

    /** Chiamato da Agnus dopo aver pubblicato i segnali sui pin CIA. */
    public void clearStatusDirty() { statusDirty = false; }

    /** Forza la ri-pubblicazione dei segnali al prossimo giro. */
    public void markStatusDirty() { statusDirty = true; }

    public boolean isTrack0Raw()         { return connected && (targetTrack == 0); }
    /**
     * /WPROT come lo vede il bus, senza il gate della selezione.
     *
     * <p>⛔ <b>Un drive VUOTO riporta protetto in scrittura.</b> L'oracolo (disk.cpp,
     * {@code drive_writeprotected}) lo scrive nell'ultimo termine:
     *
     * <pre>
     *   return currprefs.floppy_read_only || drv->wrprot || drv->forcedwrprot
     *          || drv->diskfile == NULL;
     * </pre>
     *
     * <p>Non e' un dettaglio: /WPROT e' una linea <b>wired</b>, e basta UN drive selezionato
     * a tirarla bassa. Con piu' drive selezionati insieme, un drive vuoto protegge la linea
     * anche se quello col disco non lo farebbe. Misurato su {@code Paula/Drive/step5}, che
     * seleziona tutti e quattro i drive (`and.b #$87,CIAB_PRB`) mentre df0 ha un disco
     * scrivibile e df1 e' vuoto.
     */
    public boolean isWriteProtectedRaw() { return !diskInserted || writeProtected; }
    public boolean isDiskChangedRaw()    { return connected && dskchange; }
    public boolean isIndexActiveRaw()    { return connected && indexPulse; }

    public boolean isSelected()       { return selected; }
    /** Traccia fisica corrente (cilindro*2 + lato), 0..159. Diagnostica. */
    public int getPhysicalTrack()     { return currentTrack * 2 + (currentSide ? 1 : 0); }
    public boolean isReady()          { return selected && isReadyRaw(); }
    public boolean isTrack0()         { return selected && isTrack0Raw(); }
    public boolean isWriteProtected() { return selected && isWriteProtectedRaw(); }
    public boolean isDiskChanged()    { return selected && isDiskChangedRaw(); }
    public boolean isIndexActive()    { return selected && isIndexActiveRaw(); }

    public int     getCurrentTrack()  { return currentTrack; }
    public int     getTargetTrack()   { return targetTrack; }

    /** Posizione angolare corrente della testina, in celle dall'impulso di INDEX. */
    public int getBitPointer()  { return bitPointer; }
    /** Lunghezza in celle della traccia corrente (= un giro completo). */
    public int getTrackBitLen() { return trackBitLen; }

    public boolean isDiskInserted()   { return diskInserted; }
    public boolean isMotorAtSpeed()   { return motorAtSpeed; }
    public boolean isMotorRunning()   { return motorTarget; }
    public DiskImage getDiskImage()   { return diskImage; }

    // =========================================================================
    // Flusso MFM
    // =========================================================================

    public int getNextMFMBit() {
        // Nessun gate su motorAtSpeed: come vAmiga (read8 non consulta la velocità),
        // la testina legge il supporto non appena il motore è acceso. La rampa di
        // velocità è modellata solo su /RDY.
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
    // Gestione traccia
    // =========================================================================

    /** Carica i dati MFM della traccia, lunghezza standard (byte x 8). */
    public void loadTrackData(byte[] data) {
        loadTrackData(data, data == null ? 0 : data.length * 8);
    }

    /**
     * Carica i dati MFM della traccia con la sua lunghezza ESATTA in bit.
     * La posizione angolare della testina viene preservata (riscalata sulla nuova
     * lunghezza di traccia): il cambio traccia/lato non muove il supporto.
     *
     * @param data      bitstream MFM (byte) della traccia
     * @param trackBits lunghezza esatta della traccia in bit (anche non multipla di 8,
     *                  per le long track di protezione IPF)
     */
    public void loadTrackData(byte[] data, int trackBits) {
        loadTrackData(data, trackBits, null);
    }

    /**
     * Come sopra, con la mappa di densità per-byte della traccia (1000 = durata di
     * cella nominale; {@code null} = densità uniforme).
     */
    public void loadTrackData(byte[] data, int trackBits, int[] density) {
        jitterSkipCell = -1;
        // Lunghezza della traccia corrente: standard per ADF e per le tracce IPF
        // normali, maggiore per le long track di protezione (fino a MAX_TRACK_BYTES).
        int len = (data == null) ? TRACK_BUFFER_SIZE
                                 : Math.min(data.length, trackBuffer.length);
        if (len <= 0) len = TRACK_BUFFER_SIZE;
        trackLenBytes = len;
        int oldBitLen = trackBitLen;
        trackBitLen   = (trackBits > 0 && trackBits <= len * 8) ? trackBits : len * 8;

        // Modello FDC (WinUAE get_floppy_speed_from_image): la cella resta al rate DD
        // nominale, scalata dal rapporto fra la lunghezza della traccia e quella
        // nominale. Per gli ADF è bypassato (rate fisso).
        boolean variableDensity = densityModel && density != null && density.length > 0;
        cyclesPerBit = (densityModel && trackBitLen > 0 && !variableDensity)
                ? CYCLES_PER_BIT * trackBitLen / (double) NOMINAL_TRACK_BITS
                : CYCLES_PER_BIT;
        // Densità variabile (tracce Copylock): pre-calcola la durata di cella byte per
        // byte, così il percorso caldo resta un semplice accesso ad array.
        if (variableDensity) {
            cellCycles = new double[density.length];
            for (int i = 0; i < density.length; i++)
                cellCycles[i] = cyclesPerBit * density[i] / 1000.0;
        } else {
            cellCycles = null;
        }
        // The cell phase is not touched either: the platter keeps turning while the
        // head steps or the side changes (only a disk DMA start re-phases it, see
        // restartBitCell).

        // Il cambio traccia/lato NON sposta il supporto: la testina resta allo stesso
        // angolo di rotazione. Se la nuova traccia ha una lunghezza diversa (long track
        // di protezione) la posizione va riscalata in proporzione, non azzerata.
        bitPointer = (oldBitLen > 0)
                ? (int) ((long) bitPointer * trackBitLen / oldBitLen) % trackBitLen
                : 0;

        if (data == null) {
            Arrays.fill(trackBuffer, (byte) 0);
            return;
        }
        System.arraycopy(data, 0, trackBuffer, 0, len);
        // azzera l'eventuale coda non usata del buffer (tracce precedenti più lunghe)
        if (len < trackBuffer.length)
            Arrays.fill(trackBuffer, len, trackBuffer.length, (byte) 0);
    }

    /** Ricarica nel buffer MFM i dati della traccia/side correnti dall'immagine disco. */
    private void loadCurrentTrack() {
        if (diskImage == null) return;
        int physicalTrack = currentTrack * 2 + (currentSide ? 1 : 0);
        loadTrackData(diskImage.readTrackMFM(physicalTrack),
                      diskImage.getTrackBits(physicalTrack),
                      diskImage.getTrackDensity(physicalTrack));
        jitterSkipCell = diskImage.getJitterSkipCell(physicalTrack);
    }

    // =========================================================================
    // Inserimento / Eject disco
    // =========================================================================

    /**
     * Inserisce un'immagine disco file-backed. Chiude e sostituisce l'eventuale
     * immagine precedente. Passare {@code null} equivale a chiamare
     * {@link #ejectDisk()}.
     *
     * <p>⛔ <b>Un disco che c'è già all'accensione NON è un cambio.</b> {@code /CHNG} è un
     * latch che va basso a ogni inserimento o estrazione <i>mentre la macchina gira</i>; se
     * il disco è nel drive da prima che si accendesse, non è successo niente da latchare.
     * È il modello di WinUAE alla lettera: {@code disk.cpp}, {@code drive_insert()} — che è
     * la strada del config, cioè del freddo — chiude con {@code drv->dskchange = false},
     * mentre l'inserimento a caldo passa da {@code disk_insert()} → {@code dskeject_time} →
     * espulsione (che asserisce) e solo dopo {@code drive_insert()}.
     *
     * <p>⚠️ <b>A caldo si asserisce, e va tenuto così</b>: l'HRM dice «/CHNG goes low on
     * every insertion or removal, and is reset by a STEP pulse when there is a disk in the
     * drive», ed è su questo che si regge il rilevamento del disco infilato a emulatore
     * acceso (la nostra ROM, {@code rom/floppy.asm} {@code probeDisk}, e i giochi che
     * chiedono di cambiare disco). Il discrimine è {@link #machineRunning}, che diventa vero
     * al primo {@link #tick}: prima di quello siamo nel config, dopo siamo a caldo.
     *
     * <p>⚠️ Asserirla <b>anche a freddo</b> costava <b>11 102 CCK</b> di boot: `trackdisk`
     * vedeva un cambio pendente e ricalibrava la testina <b>prima</b> di accendere il
     * motore, con i suoi 3 ms di settle. Vedi {@code docs/vamigats-paula-audio-plan.md}
     * §7.1.5, dove la controprova è misurata contro l'oracolo.
     */
    public void insertDisk(DiskImage image) {
        statusDirty = true;
        writing = false;
        flushWrittenTrack();   // salva quanto inciso sul disco uscente
        closeDiskImage();
        diskImage      = image;
        diskInserted   = (image != null);
        writeProtected = (image != null) && image.isReadOnly();
        // A caldo ogni inserimento/estrazione latcha; a freddo (config) no.
        dskchange      = machineRunning || (image == null);
        // Modello FDC attivo solo per gli IPF; per gli ADF è bypassato (rate fisso).
        densityModel   = (image != null) && image.getFormat() == DiskImage.Format.IPF;
        if (diskInserted) { loadCurrentTrack(); randomiseAngularPosition(); }
        // A disk dropped into a spinning drive restarts the /RDY delay.
        if (motorTarget) { readyUpLines = readyUpDelay(); motorAtSpeed = false; }
        if (floppySound != null) floppySound.onDiskChange(diskInserted);
    }

    /**
     * Posizione angolare del piatto all'inserimento: CASUALE, come WinUAE
     * ({@code disk.cpp drive_insert}: {@code drv->mfmpos = uaerand() ... % tracklen}).
     *
     * <p>Il motore di un drive vero si ferma dove capita e il disco viene infilato con
     * un orientamento qualunque: l'angolo da cui parte la prima lettura non ha alcuna
     * relazione col pennello video. Partendo invece SEMPRE da bit 0 ogni avvio legge il
     * disco dallo stesso angolo, quindi ogni lettura finisce allo stesso identico
     * istante rispetto al frame — la fase non è "una delle tante", è sempre quella.
     *
     * <p>È esattamente ciò che rompeva Pinball Dreams da floppy: il caricamento del
     * bootblock terminava sempre a vpos 22 e la consegna al gioco cadeva sempre a
     * vpos 312, l'unico punto in cui la finestra cieca di ~15 istruzioni del suo
     * takeover (INTREQ azzerato → vettore $6C installato) viene attraversata dal VBL.
     * Il gioco prendeva quindi la VERTB nel gestore del Kickstart, con SysBase già
     * distrutto, e finiva in Guru $00000025 a ogni singolo avvio.
     *
     * <p>Determinismo: i test che confrontano frame o CRC devono poter riprodurre la
     * stessa corsa, quindi il seme è fissabile con {@code -Damiga.diskseed=<n>}.
     *
     * <p>⚠️ Il seme <b>non basta</b> quando si confronta una corsa con quella di un altro
     * emulatore: fissa la <i>sequenza</i>, non il valore, quindi l'angolo di una macchina
     * dipende da quante ne sono state create prima nella stessa JVM. E l'angolo non è un
     * dettaglio — da solo sposta l'istante in cui il bootblock è pronto di <b>±8000 CCK</b>
     * (docs/vamigats-paula-audio-plan.md §6.3), cioè assai più di tutte le divergenze di
     * timing che il banco vAmigaTS misura. Per questo esiste anche
     * {@code -Damiga.diskangle=<bit>}, che <b>fissa la posizione</b> invece di estrarla:
     * è il modo di mettere le due macchine allo stesso punto del piatto prima di
     * confrontarle. L'unità è la stessa dell'oracolo — bit sulla traccia MFM, con
     * {@code tracklen} 101344 su entrambi — quindi il valore si legge direttamente dal suo
     * {@code drv->mfmpos} (hook {@code WINUAE_DISKLOG}).
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
     * ⛔ <b>Il generatore è PER DRIVE, non statico.</b> Con un {@code Random} condiviso da
     * tutta la JVM l'<i>n</i>-esimo inserimento prendeva l'<i>n</i>-esimo valore della
     * sequenza, quindi <b>l'angolo di partenza di un test dipendeva da quanti test erano
     * girati prima</b>: la stessa classe poteva essere verde da sola e rossa dentro una
     * suite, ed è il motivo per cui
     * {@code docs/vamigats-paula-audio-plan.md} §2.5 prescriveva di riprodurre ogni rosso
     * "da solo" prima di attribuirlo a una modifica.
     *
     * <p>Con un generatore per drive la sequenza <b>riparte con la macchina</b>: ogni test
     * riceve esattamente l'angolo che riceve quando gira <b>da solo</b>, che è la condizione
     * in cui tutti i riferimenti sono stati validati. Il seme resta fissabile con
     * {@code -Damiga.diskseed=<n>} (il {@code pom.xml} lo mette a 0 per i test); l'offset per
     * {@code id} serve a non dare lo stesso piatto a due drive diversi, e
     * {@code String.hashCode()} è specificato dal linguaggio, quindi è riproducibile.
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
     * Compatibilità con codice esistente: carica un file ADF creando
     * internamente un {@link DiskImage}. Accetta anche la forma
     * {@code <archivio>.zip!<n>} per i singoli dischi di uno zip multi-disco.
     */
    public void loadAdf(String adfPath, boolean writeProtected) throws IOException {
        if (adfPath == null) { ejectDisk(); return; }
        insertDisk(DiskImage.open(adfPath, writeProtected));
    }

    /** Eject fisico: flush dei dati, chiusura dell'immagine, segnalazione /CHNG. */
    public void ejectDisk() {
        statusDirty = true;
        writing = false;
        flushWrittenTrack();
        closeDiskImage();
        densityModel = false;
        cyclesPerBit = CYCLES_PER_BIT;
        resetBitClock();
        if (diskInserted) {
            dskchange    = true;
            diskInserted = false;
            if (floppySound != null) floppySound.onDiskChange(false);
        }
    }

    /**
     * Simula la presenza/assenza fisica di un disco senza file backing.
     * Utile per test o per segnalare un disco non formattato.
     */
    public void insertDisk(boolean inserted, boolean writeProtected) {
        statusDirty = true;
        if (!inserted) {
            writing = false;
            flushWrittenTrack();
            closeDiskImage();
            densityModel = false;
            cyclesPerBit = CYCLES_PER_BIT;
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
        statusDirty = true;
        // Un reset a metà scrittura (es. Ctrl-Amiga-Amiga) non deve perdere la
        // traccia già incisa: la si riversa nell'immagine prima di azzerare tutto.
        writing = false;
        flushWrittenTrack();

        boolean hadDiskImage = diskImage != null && diskInserted;
        boolean wasWriteProtected = writeProtected;
        boolean wasDiskChanged = dskchange;

        motorTarget          = false;
        motorAtSpeed         = false;
        readyUpLines         = 0;
        switchCycle          = 0;
        totalCycles          = 0;
        currentTrack         = 0;
        targetTrack          = 0;
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
        // bitPointer NON si azzera: è la posizione angolare del piatto, che nessun
        // segnale di reset può riavvolgere. WinUAE fa lo stesso (disk.cpp
        // reset_drive() non tocca mfmpos).
        //
        // Azzerarlo qui era il vero motivo per cui la fase del floppy era INCHIODATA:
        // il Kickstart esegue una RESET in fase di boot, quindi ogni avvio ripartiva
        // dall'angolo 0 e ogni caricamento finiva allo stesso identico punto del
        // frame (DSKBLK sempre a vpos 22, consegna al gioco sempre a vpos 312).
        // Vedi randomiseAngularPosition() per il perché conta.
        resetBitClock();
        if (hadDiskImage) {
            loadCurrentTrack();
        } else {
            for (int i = 0; i < trackBuffer.length; i++) trackBuffer[i] = 0;
        }

        // reset() azzera motorTarget direttamente (senza setMotor): ferma esplicitamente
        // l'eventuale loop di spin del suono per non lasciarlo a girare a vuoto.
        if (floppySound != null) floppySound.onMotor(false, diskInserted);
    }
}
