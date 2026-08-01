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
    private static final long   CYCLES_MOTOR_RAMP        = 3_546_896L;   // rampa avvio motore (~500 ms)
    /** FIX #2: il motore reale impiega ~3 s per fermarsi completamente. */
    private static final long   CYCLES_MOTOR_STOP        = 21_000_000L;  // rampa arresto motore (~3 s)
    private static final long   CYCLES_SETTLE_STEP       =    21_281L;   // settle dopo step stesso verso (~3 ms)
    private static final long   CYCLES_SETTLE_DIR_CHG    =   127_688L;   // settle dopo inversione direzione (~18 ms)
    /** FIX #5: il cambio side ha un breve settle analogico (~2 ms). */
    private static final long   CYCLES_SETTLE_SIDE       =    14_188L;
    private static final double CYCLES_PER_BIT           =    14.1876;   // DD: 2 µs/bit
    /**
     * Bit per giro di una traccia DD di lunghezza nominale — WinUAE
     * {@code 2 * 8 * FLOPPY_WRITE_LEN} con {@code FLOPPY_WRITE_LEN_PAL/2 = 6334}.
     * È il riferimento rispetto a cui si scala la durata di cella delle long track.
     */
    private static final int    NOMINAL_TRACK_BITS       = 12_668 * 8;   // 101 344
    /** FIX #8: alcuni drive arrivano a traccia 83 (indice 0-based). */
    private static final int    MAX_TRACKS               = 84;
    private static final int    BIT_CLOCK_SCALE          = 10_000;
    private static final int    ADF_CYCLES_PER_BIT_FIXED = 141_876;
    private static final int    TRACK_BUFFER_SIZE        = DiskImage.TRACK_BUFFER_BYTES;
    private static final int    SECTORS_PER_TRACK        = DiskImage.SECTORS_PER_TRACK_DD;
    private static final int    SECTOR_SIZE              = DiskImage.SECTOR_SIZE;
    private static final int    ID_SEQUENCE_BITS         = 32;

    // -------------------------------------------------------------------------
    // Stato motore
    // -------------------------------------------------------------------------

    private boolean motorTarget  = false;
    private boolean motorAtSpeed = false;
    private long    switchCycle  = 0L;
    private double  switchSpeed  = 0.0;
    private long    totalCycles  = 0L;

    // -------------------------------------------------------------------------
    // Posizionamento testina
    // -------------------------------------------------------------------------

    private int     currentTrack      = 0;
    private int     targetTrack       = 0;
    private long    settleCounter     = 0L;
    private boolean currentSide       = false;

    /** FIX #5: side change pendente — effettivo solo al termine del settle. */
    private boolean pendingSideChange = false;
    private boolean pendingSideValue  = false;

    // -------------------------------------------------------------------------
    // Disco
    // -------------------------------------------------------------------------

    /** Immagine disco corrente; null = nessun disco con file backing. */
    private DiskImage diskImage      = null;

    private boolean diskInserted   = false;
    private boolean writeProtected = false;

    /** Latch cambio disco: true = segnale /CHNG LOW (attivo). */
    private boolean dskchange = false;

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
    // cioè la cella resta al rate DD nominale (2 µs) e una traccia più LUNGA fa
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
    // (2 µs/bit), comportamento identico a prima e a costo zero.
    private       boolean densityModel        = false;
    // Durata di cella pre-calcolata per byte di traccia (solo tracce IPF a densità
    // variabile, es. Copylock); null = periodo costante cyclesPerBit.
    private       double[] cellCycles         = null;
    private       double  cyclesPerBit        = CYCLES_PER_BIT;
    private       int    bitPointer           = 0;
    private       double bitCyclesAccumulator = 0.0;
    private       long   bitCyclesAccumulatorFixed = 0;

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
        totalCycles += cycles;

        // Flush periodico dell'immagine disco (cheap: esce subito se non ci sono
        // tracce dirty da scrivere — caso normale in gioco con disco di sola lettura).
        if (diskImage != null) diskImage.tickCycles(cycles);

        // PERF: fast-path "dormiente". Un drive col motore fermo da tempo (velocità
        // ormai 0) e senza seek in corso non ha nulla da fare ad ogni master-cycle:
        // il corpo sottostante si limiterebbe a ri-azzerare campi già a zero. Vale
        // per i drive vuoti df1-df3 E per df0 a motore spento col disco inserito
        // (la condizione tipica durante il gioco, dopo il caricamento). Elimina la
        // maggior parte delle ~28M chiamate "piene"/s (motorSpeed con divisione +
        // rami). totalCycles è già avanzato, quindi la rampa motore al riavvio è ok.
        if (!motorTarget && !motorAtSpeed && settleCounter <= 0
                && (totalCycles - switchCycle) >= CYCLES_MOTOR_STOP) {
            return;
        }

        // PERF (vedi statusDirty): fuori dallo stato stazionario "motore a regime"
        // il valore di /RDY dipende da motorSpeed(), funzione continua del tempo:
        // può cambiare senza che nessun campo venga scritto. Qui il flag resta
        // sempre alto — è la rampa del motore, non il caricamento a regime.
        if (!(motorTarget && motorAtSpeed)) statusDirty = true;

        // PERF: a regime (motore acceso e già a velocità) la velocità è
        // costante 100: evitiamo la divisione FP di motorSpeed() — eseguita
        // ~7M volte/s durante un caricamento — che il profiler mostra come
        // costo dominante del tick floppy. Nessun cambio di comportamento:
        // in questo stato nessuna delle transizioni sotto si applica comunque.
        if (!(motorAtSpeed && motorTarget)) {
            double currentSpeed = motorSpeed();
            // Transizioni stato motore
            if (motorTarget && !motorAtSpeed && currentSpeed >= 100.0) {
                motorAtSpeed = true;
            } else if (!motorTarget && motorAtSpeed) {
                motorAtSpeed = false;
            }
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
            if (indexPulse) {
                indexPulse  = false;
                statusDirty = true;
            }
            resetBitClock();
        }

        // Settle: il seek è già applicato all'impulso di STEP (vedi executeStep);
        // qui resta solo l'assestamento del cambio lato pendente (debounce dei
        // transitori /SIDE durante lo stepping — fix SpeedBall II bug-2).
        if (settleCounter > 0) {
            settleCounter -= cycles;
            if (settleCounter <= 0) {
                settleCounter = 0;

                // FIX #5: applica side change a settle completato
                if (pendingSideChange) {
                    pendingSideChange = false;
                    flushWrittenTrack();   // la traccia incisa appartiene al lato VECCHIO
                    currentSide       = pendingSideValue;
                    if (diskInserted && diskImage != null) loadCurrentTrack();
                }
            }
        }
    }

    // =========================================================================
    // Aggiornamento segnali (chiamato da Agnus/CIA ogni ciclo DMA o evento)
    // =========================================================================

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
            // Cambio lato: posticipato al termine del settle, ma il valore deve
            // SEMPRE seguire l'ultimo livello comandato su /SIDE. Un loader può
            // pilotare /SIDE basso per un istante (durante la sequenza di step) e
            // riportarlo subito al lato voluto: latchare il primo transitorio (e
            // ignorare i ritorni con `!pendingSideChange`) faceva leggere il lato
            // sbagliato — es. Speedball II legge la traccia fisica dispari invece
            // di quella pari e il suo verificatore di header va in loop infinito.
            if (this.currentSide != side) {
                if (!pendingSideChange) {
                    pendingSideChange = true;
                    if (settleCounter < CYCLES_SETTLE_SIDE) settleCounter = CYCLES_SETTLE_SIDE;
                }
                pendingSideValue = side;
            } else if (pendingSideChange) {
                // /SIDE è tornato al lato corrente prima del settle: annulla il cambio.
                pendingSideChange = false;
            }

            // /STEP is active LOW on the bus; Agnus passes true while the
            // line is asserted, so the physical falling edge is false -> true.
            if (!prevStep && step) {
                executeStep(direction);
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

    public double motorSpeed() {
        long elapsed = totalCycles - switchCycle;
        if (motorTarget) {
            return Math.min(switchSpeed + 100.0 * ((double) elapsed / CYCLES_MOTOR_RAMP), 100.0);
        } else {
            return Math.max(switchSpeed - 100.0 * ((double) elapsed / CYCLES_MOTOR_STOP), 0.0);
        }
    }

    private void setMotor(boolean value) {
        if (motorTarget == value) return;
        statusDirty = true;
        switchSpeed = motorSpeed();
        switchCycle = totalCycles;
        motorTarget = value;
        if (floppySound != null) floppySound.onMotor(value, diskInserted);
    }

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
            bitConsumer.accept(getNextMFMBit());
        }
        advanceBitPointer();
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

    // =========================================================================
    // Segnali di uscita
    // =========================================================================

    /**
     * /RDY raw, modellato su vAmiga (FloppyDrive::driveStatusFlags / idMode):
     *
     *   idMode = motorStopped() || motorSpeedingUp()
     *   /RDY attivo se:  idMode  → idBit
     *                    altrimenti (a regime O in spin-down) → disco presente
     *
     * FIX (Agony): il punto chiave è lo spin-down del drive INTERNO. Quando il
     * motore viene SPENTO ma il disco sta ancora girando (motorSlowingDown:
     * !motorTarget e velocità &gt; 0), /RDY deve restare ATTIVO finché il piatto
     * rallenta — NON passare subito al bit ID. La revisione precedente ritornava
     * idBit appena motorTarget=false, così un loader che spegne il motore e poi
     * attende /RDY (es. l'handoff cracktro→intro di Agony) non vedeva mai
     * "ready" e si bloccava in un loop di polling. Su DF0 la cosa è innocua
     * perché il drive interno non ha ID hardware (DD_INT = $00000000, idBit
     * sempre 0) ed è lo stesso caso di WinUAE {@code cs_df0idhw=false}, che
     * mantiene DSKRDY attivo per un po' dopo lo spegnimento (motordelay).
     *
     * <p>FIX (cambio disco sotto Workbench): sui drive CON ID hardware (DF1-DF3)
     * la grazia di spin-down NON deve esistere. WinUAE (disk.cpp
     * DISK_status_ciaa) sceglie fra DSKRDY e bit ID in base al SOLO flip-flop del
     * motore ({@code drive_running} = {@code !motoroff}), non alla velocità del
     * piatto; vAmiga fa lo stesso perché di default la meccanica ha rampe nulle.
     * La sequenza con cui il Kickstart legge l'ID (FC4984: seleziona il drive con
     * /MTR basso, poi 32 impulsi di /SEL con /MTR alto leggendo /RDY) accende e
     * subito rispegne il motore: con la grazia attiva i 32 bit letti non erano
     * l'ID ma {@code diskInserted}, quindi un DF1 VUOTO restituiva ID $00000000 =
     * "nessun drive". Da lì trackdisk non apre l'unità, non fa più polling di
     * /CHNG e un disco inserito a caldo non viene mai visto (mentre un DF1 con
     * disco all'avvio restituiva $FFFFFFFF e funzionava).
     */
    public boolean isReadyRaw() {
        if (!connected) return false;
        // PERF fast-path: a regime (motore acceso e già a piena velocità) /RDY è
        // "pronto se c'è disco", senza calcolare motorSpeed() — evita una divisione
        // FP che, chiamata da updateFloppy ad ogni CCK, pesava sul caricamento.
        // motorAtSpeed è vero SOLO mentre la velocità è 100 (motorAtFullSpeed).
        if (motorTarget && motorAtSpeed) return diskInserted;
        if (!motorTarget && driveType != DriveType.DD_INT) {
            // Drive con ID hardware: motore spento (flip-flop) ⇒ la linea /RDY
            // trasporta SEMPRE il bitstream dell'ID, anche mentre il piatto rallenta.
            return idBit;
        }
        double speed = motorSpeed();
        boolean motorStopped    = (speed == 0.0);
        boolean motorSpeedingUp = motorTarget && speed < 100.0;
        if (motorStopped || motorSpeedingUp) {
            // idMode: la linea /RDY trasporta il bitstream dell'ID del drive.
            return idBit;
        }
        // motorAtFullSpeed || motorSlowingDown: pronto se c'è un disco.
        return diskInserted;
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
    // /RDY dipende da motorSpeed() — una funzione continua del tempo, che può
    // cambiare valore senza che nessun campo venga scritto. Si sfrutta il solo
    // stato stazionario: motore a regime, oppure fermo da tempo.
    private boolean statusDirty = true;

    /** True se uno dei 5 segnali di stato può essere cambiato dall'ultima pubblicazione. */
    public boolean isStatusDirty() { return statusDirty; }

    /** Chiamato da Agnus dopo aver pubblicato i segnali sui pin CIA. */
    public void clearStatusDirty() { statusDirty = false; }

    /** Forza la ri-pubblicazione dei segnali al prossimo giro. */
    public void markStatusDirty() { statusDirty = true; }

    public boolean isTrack0Raw()         { return connected && (targetTrack == 0); }
    public boolean isWriteProtectedRaw() { return diskInserted && writeProtected; }
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

    /**
     * All'avvio di una DMA READ il livello /SIDE comandato ha effetto IMMEDIATO:
     * su HW reale la selezione di lato non ha il settle meccanico del seek di
     * traccia (è solo la selezione elettronica dell'elemento testina). Se c'è un
     * cambio lato pendente (ancora in settle), applicalo subito e ricarica la
     * traccia del lato corretto, così la DMA legge il lato voluto invece di quello
     * vecchio. Il debounce dei transitori /SIDE durante lo stepping resta nel path
     * normale (update()/settle) e NON è toccato — preserva il fix SpeedBall II.
     *
     * Questo elimina l'accoppiamento del side-select alla velocità della CPU: il
     * loader IK+ comanda /SIDE=1 e avvia subito la DMA; con CPU veloce il vecchio
     * settle ~2ms non faceva in tempo a completare → leggeva il lato sbagliato
     * (track 84 invece di 85) e il check di protezione falliva. Vedi
     * docs/sae-dskr-sequence.md.
     */
    public void commitPendingSideForRead() {
        if (pendingSideChange && currentSide != pendingSideValue) {
            flushWrittenTrack();   // la traccia incisa appartiene al lato VECCHIO
            currentSide       = pendingSideValue;
            pendingSideChange = false;
            if (diskInserted && diskImage != null) loadCurrentTrack();
        }
    }

    /** Posizione angolare corrente della testina, in celle dall'impulso di INDEX. */
    public int getBitPointer()  { return bitPointer; }
    /** Lunghezza in celle della traccia corrente (= un giro completo). */
    public int getTrackBitLen() { return trackBitLen; }

    /** DIAG (Fase A-ter): lato effettivo della testina + eventuale cambio in settle. */
    public String getSideDebug() {
        return String.format("effSide=%d pendChg=%b pendVal=%d settle=%d",
                currentSide ? 1 : 0, pendingSideChange, pendingSideValue ? 1 : 0, settleCounter);
    }
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
        if (!motorTarget || !diskInserted) return 0;
        int byteIdx   = bitPointer >> 3;
        if (byteIdx >= trackBuffer.length) return 0;
        int bitInByte = 7 - (bitPointer & 7);
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
        resetBitClock();

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
    }

    // =========================================================================
    // Inserimento / Eject disco
    // =========================================================================

    /**
     * Inserisce un'immagine disco file-backed. Chiude e sostituisce l'eventuale
     * immagine precedente. Passare {@code null} equivale a chiamare
     * {@link #ejectDisk()}.
     */
    public void insertDisk(DiskImage image) {
        statusDirty = true;
        writing = false;
        flushWrittenTrack();   // salva quanto inciso sul disco uscente
        closeDiskImage();
        diskImage      = image;
        diskInserted   = (image != null);
        writeProtected = (image != null) && image.isReadOnly();
        dskchange      = true;
        // Modello FDC attivo solo per gli IPF; per gli ADF è bypassato (rate fisso).
        densityModel   = (image != null) && image.getFormat() == DiskImage.Format.IPF;
        if (diskInserted) loadCurrentTrack();
        if (floppySound != null) floppySound.onDiskChange(diskInserted);
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
        switchCycle          = 0;
        switchSpeed          = 0.0;
        totalCycles          = 0;
        currentTrack         = 0;
        targetTrack          = 0;
        settleCounter        = 0;
        pendingSideChange    = false;
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
        bitPointer           = 0;
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
