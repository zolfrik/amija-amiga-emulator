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

import io.github.zolfrik.amija.common.BusRequest;
import io.github.zolfrik.amija.common.BusResponse;
import io.github.zolfrik.amija.common.CIAPinSet;
import io.github.zolfrik.amija.common.MOS8520;
import io.github.zolfrik.amija.common.VideoMode;
import io.github.zolfrik.amija.memory.ROM;
import io.github.zolfrik.amija.memory.RAM;
import io.github.zolfrik.amija.cpu.Motorola68000;

import java.util.Arrays;
import java.util.function.IntConsumer;

/**
 * Agnus (8361/8367/8372) — DMA Controller, Copper, Blitter e bus arbiter dell'Amiga 500.
 *
 * Agnus è il cuore del sistema DMA dell'Amiga. Si occupa di:
 *   - Generare i segnali di sincronizzazione video (hpos/vpos)
 *   - Arbitrare il bus tra CPU, Copper, Blitter e DMA custom
 *   - Gestire i DMA slot: Refresh, Disk, Audio, Sprite, Bitplane, Copper, Blitter
 *   - Pilotare Denise con i dati bitplane e sprite via DMA
 *   - Interfacciarsi con le CIA per OVL, TOD, interrupt
 *   - Interfacciarsi con Paula per gli interrupt e il DMA disco
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * TEMPORIZZAZIONE
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * L'Amiga usa due unità di tempo:
 *   - System clock (SC): il clock master PAL a 7.09379 MHz
 *   - Color Clock (CCK): SC/2 = 3.54690 MHz (un CCK = 2 SC)
 *
 * hpos conta i system clock (0–453 PAL, 0–453 NTSC per alcune versioni).
 * I DMA slot sono assegnati in unità CCK (hpos pari = CCK = hpos/2).
 * Un'intera riga PAL ha 227 CCK (454 SC).
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * SEGNALI TOD CIA
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * CIA-A TOD: riceve un impulso dal segnale di rete (50 Hz PAL / 60 Hz NTSC).
 *   Su PAL: 1 impulso per frame, generato a vpos=0 (inizio nuovo frame).
 *   Il segnale rimane HIGH per un breve periodo, poi torna LOW.
 *
 * CIA-A FLAG: riceve l'impulso di indice del floppy disk (~5.4 Hz per giro).
 *   Pilotato dal pin FLAG della CIA-A tramite il segnale di indice del drive.
 *   NON è pilotato dal vsync — è un segnale separato gestito da updateFloppy().
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * INTERRUPT
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * CIA-A IRQ → Paula INTREQ bit 3 (PORTS, IPL 2).
 * CIA-B IRQ → Paula INTREQ bit 13 (EXTER, IPL 6).
 *
 * Il bit PORTS/EXTER viene settato quando il pin /IRQ della CIA scende (LOW).
 * Viene azzerato dal software scrivendo su INTREQ (CLR) — NON automaticamente
 * quando la CIA rilascia il proprio IRQ. Questo è il comportamento hardware
 * corretto: il bit INTREQ è latched e va azzerato esplicitamente.
 *
 * @author alessio.carotenuto
 */
public abstract class AbstractAgnus {

    // =========================================================================
    // Logging
    // =========================================================================

    private int frameCount = 1;

    // =========================================================================
    // Dipendenze
    // =========================================================================

    private final Motorola68000  cpu;
    private final BusRequest     cpuBusReq;
    private final BusResponse    cpuBusResp;

    private final Gary    gary;
    private final RAM chipRAM;
    private final RAM slowRAM;
    private final ROM     rom;

    /**
     * Espansione Fast RAM Zorro II (AutoConfig). null = nessuna Fast RAM (default
     * A500). Iniettata dopo la costruzione via {@link #setFastRam(FastRam)} per non
     * alterare la firma del costruttore (usata da numerosi test). Vista solo dalla
     * CPU: i master DMA (Copper/Blitter/bitplane) non accedono mai alla Fast RAM.
     */
    private FastRam fastRam;

    /**
     * Controller hard disk (schede Zorro II con AutoConfig + boot ROM). Lista vuota
     * = nessun hard disk. Iniettati dopo la costruzione via
     * {@link #setHdControllers}. Come la Fast RAM partecipano all'AutoConfig.
     */
    private final java.util.List<HdController> hdControllers = new java.util.ArrayList<>();

    /** Accesso byte alla RAM Amiga (chip/slow/fast) usato dai controller hard disk. */
    private final HdController.Bus hdBus = new HdController.Bus() {
        @Override public int  readByte(int addr)            { return hdReadByte(addr); }
        @Override public void writeByte(int addr, int value) { hdWriteByte(addr, value); }
        @Override public void writeBlock(int addr, byte[] src, int srcOff, int len) {
            hdWriteBlock(addr, src, srcOff, len);
        }
        @Override public void readBlock(int addr, byte[] dst, int dstOff, int len) {
            hdReadBlock(addr, dst, dstOff, len);
        }
    };

    // Maschera di wrap per gli accessi DMA alla Chip RAM (size-1; la dimensione è
    // sempre una potenza di 2). L'address counter di Agnus avvolge entro la Chip RAM
    // installata come sull'hardware OCS: un puntatore DMA che esce dal range non
    // genera un fault ma rilegge la RAM in mirror. Gli accessi del 68000 sono già
    // mascherati da Gary (vedi physicalSize), ma il DMA bypassa Gary e legge qui
    // direttamente, quindi la maschera va applicata in questo punto.
    private final int chipMask;

    // Maschera dei bit ALTI (16-20) del puntatore DMA scrivibili da BPLxPTH/SPRxPTH/
    // DSKPTH, dimensionata sulla Chip RAM installata. L'OCS (512 KB) usa solo i bit
    // 16-18 (0x07), ma l'ECS/A500+ con 1 MB (es. boot WHDLoad da HDF con KS3.1) colloca
    // i bitplane in chip alta (>512 KB) e richiede il bit 19 (0x80000): mascherare a
    // 0x07 lo perdeva, dirottando un piano nella copper-list in memoria bassa → banda
    // di "neve" sotto la barra del titolo del Workbench. Derivata da chipMask così il
    // wrap resta quello dell'hardware per ogni taglio di RAM (0x07/0x0F/0x1F << 16).
    private final int dmaPtrHiMask;

    private final Copper      copper;
    private final BusRequest  copperBusReq;
    private final BusResponse copperBusResp;

    public AbstractDenise getDenise() {
        return denise;
    }

    public MOS8520 getCiaA() { return ciaA; }
    public MOS8520 getCiaB() { return ciaB; }
    public void setKeyboard(Keyboard keyboard) { this.keyboard = keyboard; }

    private final Blitter     blitter;

    private final Paula  paula;
    protected final AbstractDenise denise;

    private final MOS8520 ciaA;
    private final MOS8520 ciaB;
    private Keyboard keyboard;
    private final BatteryBackedClock batteryClock = new BatteryBackedClock();

    private final FloppyDrive df0, df1, df2, df3;

    // =========================================================================
    // Event Scheduler
    // =========================================================================

    public static final long NEVER = Long.MAX_VALUE;

    public enum EventSlot {
        SLOT_REG, SLOT_CIAA, SLOT_CIAB, SLOT_BPL, SLOT_DAS, SLOT_COP, SLOT_BLT, SLOT_SEC,
        SLOT_CH0, SLOT_CH1, SLOT_CH2, SLOT_CH3, SLOT_DSK, SLOT_VBL, SLOT_IRQ, SLOT_IPL,
        SLOT_KBD, SLOT_TXD, SLOT_RXD, SLOT_TER,
        SLOT_DC0, SLOT_DC1, SLOT_DC2, SLOT_DC3,
        SLOT_COUNT
    }

    /**
     * STEP 4 (PERFORMANCE_REPORT_FINAL): cache statica di EventSlot.values().
     * Enum.values() clona l'array interno $VALUES ad ogni chiamata; in
     * executeEvents() (hot loop, fino a ~709k volte/s per SLOT_CIAA) questo
     * generava un clone per evento. L'array è immutabile, quindi la cache è
     * comportamentalmente identica.
     */
    private static final EventSlot[] SLOT_VALUES = EventSlot.values();

    public enum EventID {
        EVENT_NONE,
        REG_CHANGE,
        CIA_EXECUTE,
        BPL_FETCH,
        DAS_REFRESH, DAS_D0, DAS_D1, DAS_D2, DAS_A0, DAS_A1, DAS_A2, DAS_A3,
        DAS_S0_1, DAS_S0_2, DAS_S1_1, DAS_S1_2, DAS_S2_1, DAS_S2_2, DAS_S3_1, DAS_S3_2,
        DAS_S4_1, DAS_S4_2, DAS_S5_1, DAS_S5_2, DAS_S6_1, DAS_S6_2, DAS_S7_1, DAS_S7_2,
        COP_FETCH,
        VBL_STROBE,
        IRQ_CHECK,
        IPL_CHANGE,
        TXD_BIT,
        RXD_BIT,
        DSK_ROTATE,
        AUDIO_MIX
    }

    private final long[] trigger = new long[EventSlot.SLOT_COUNT.ordinal()];
    private final EventID[] eventIds = new EventID[EventSlot.SLOT_COUNT.ordinal()];
    private final long[] eventData = new long[EventSlot.SLOT_COUNT.ordinal()];
    private long nextTrigger = NEVER;

    /**
     * PERF: bitmask degli slot evento ATTIVI (trigger != NEVER). SLOT_COUNT (25) ≤ 64,
     * quindi un solo long. executeEvents itera solo i bit settati (tipicamente 2-4 slot:
     * CIAA ogni 10 cicli + audio + sporadici) invece di scandire incondizionatamente
     * tutti i 25 slot ad ogni firing (~709k volte/s per la sola SLOT_CIAA). L'ordine di
     * iterazione resta crescente (indice 0→24) tramite numberOfTrailingZeros, identico
     * alla vecchia scansione lineare; gli slot inattivi erano no-op (trigger=NEVER non
     * scatta e NEVER<nextTrigger è falso), quindi saltarli è comportamentalmente neutro.
     * Mantenuta coerente in scheduleAbs/cancel/executeEvents/internalReset, gli unici
     * punti che mutano trigger[].
     */
    private long activeSlotMask = 0L;
    private long masterClock = 0;

    // --- Delayed Register Changes ---
    // RegChange mutabile e riutilizzabile. Il recorder usa un min-heap custom su
    // array preallocati invece di PriorityQueue+Comparator: nessuna allocazione o
    // crescita nascosta durante le write Copper.
    private static final int REG_CHANGE_CAPACITY = 1024;

    private static class RegChange {
        long trigger;
        long sequence;
        AgnusReg reg;
        int value;
        int pc;

        RegChange() { }

        RegChange set(long trigger, long sequence, AgnusReg reg, int value, int pc) {
            this.trigger = trigger;
            this.sequence = sequence;
            this.reg = reg;
            this.value = value;
            this.pc = pc;
            return this;
        }
    }

    private final RegChange[] changeRecorder = new RegChange[REG_CHANGE_CAPACITY];
    private int changeRecorderSize;
    private final RegChange[] regChangePool = createRegChangePool();
    private int regChangePoolTop = REG_CHANGE_CAPACITY;
    private long regChangeSequence;

    private static RegChange[] createRegChangePool() {
        RegChange[] pool = new RegChange[REG_CHANGE_CAPACITY];
        for (int i = 0; i < pool.length; i++) {
            pool[i] = new RegChange();
        }
        return pool;
    }

    private RegChange acquireRegChange() {
        if (regChangePoolTop == 0) {
            throw new IllegalStateException("Agnus delayed register change overflow: " + REG_CHANGE_CAPACITY);
        }
        RegChange rc = regChangePool[--regChangePoolTop];
        regChangePool[regChangePoolTop] = null;
        return rc;
    }

    private void releaseRegChange(RegChange rc) {
        rc.reg = null;
        regChangePool[regChangePoolTop++] = rc;
    }

    private void offerRegChange(RegChange rc) {
        int idx = changeRecorderSize;
        if (idx >= changeRecorder.length) {
            releaseRegChange(rc);
            throw new IllegalStateException("Agnus delayed register heap overflow: " + changeRecorder.length);
        }
        changeRecorderSize = idx + 1;
        while (idx > 0) {
            int parent = (idx - 1) >>> 1;
            RegChange parentChange = changeRecorder[parent];
            if (compareRegChange(parentChange, rc) <= 0) break;
            changeRecorder[idx] = parentChange;
            idx = parent;
        }
        changeRecorder[idx] = rc;
    }

    private RegChange peekRegChange() {
        return changeRecorderSize == 0 ? null : changeRecorder[0];
    }

    private RegChange pollRegChange() {
        if (changeRecorderSize == 0) return null;
        RegChange result = changeRecorder[0];
        RegChange moved = changeRecorder[--changeRecorderSize];
        changeRecorder[changeRecorderSize] = null;
        if (changeRecorderSize > 0) {
            int idx = 0;
            int half = changeRecorderSize >>> 1;
            while (idx < half) {
                int child = (idx << 1) + 1;
                RegChange bestChild = changeRecorder[child];
                int right = child + 1;
                if (right < changeRecorderSize
                        && compareRegChange(changeRecorder[right], bestChild) < 0) {
                    child = right;
                    bestChild = changeRecorder[right];
                }
                if (compareRegChange(moved, bestChild) <= 0) break;
                changeRecorder[idx] = bestChild;
                idx = child;
            }
            changeRecorder[idx] = moved;
        }
        return result;
    }

    private boolean isRegChangeHeapEmpty() {
        return changeRecorderSize == 0;
    }

    private void clearRegChanges() {
        while (changeRecorderSize > 0) {
            RegChange rc = changeRecorder[--changeRecorderSize];
            changeRecorder[changeRecorderSize] = null;
            releaseRegChange(rc);
        }
        regChangeSequence = 0;
    }

    private static int compareRegChange(RegChange a, RegChange b) {
        int byTrigger = Long.compare(a.trigger, b.trigger);
        return byTrigger != 0 ? byTrigger : Long.compare(a.sequence, b.sequence);
    }

    private int syncEvent = 0;
    private static final int EVFL_EOL   = 1 << 0;
    private static final int EVFL_HSYNC = 1 << 1;

    public enum AgnusReg {
        DMACON, VPOSR, VHPOSR, BPLCON0, BPLCON1, DIWSTRT, DIWSTOP, DDFSTRT, DDFSTOP,
        BPL1MOD, BPL2MOD, BPL1PTH, BPL2PTH, BPL3PTH, BPL4PTH, BPL5PTH, BPL6PTH,
        BPL1PTL, BPL2PTL, BPL3PTL, BPL4PTL, BPL5PTL, BPL6PTL,
        SPR0POS, SPR1POS, SPR2POS, SPR3POS, SPR4POS, SPR5POS, SPR6POS, SPR7POS,
        SPR0CTL, SPR1CTL, SPR2CTL, SPR3CTL, SPR4CTL, SPR5CTL, SPR6CTL, SPR7CTL,
        SPR0PTH, SPR1PTH, SPR2PTH, SPR3PTH, SPR4PTH, SPR5PTH, SPR6PTH, SPR7PTH,
        SPR0PTL, SPR1PTL, SPR2PTL, SPR3PTL, SPR4PTL, SPR5PTL, SPR6PTL, SPR7PTL,
        DSKPTH, DSKPTL, COPCON
    }

    public void recordRegisterChange(long delay, AgnusReg reg, int value) {
        offerRegChange(acquireRegChange().set(masterClock + delay, regChangeSequence++, reg, value, getCpuPc()));
        scheduleNextRegEvent();
    }

    private void scheduleNextRegEvent() {
        if (syncEvent != 0) {
            scheduleRel(EventSlot.SLOT_REG, 1, EventID.REG_CHANGE);
        } else if (!isRegChangeHeapEmpty()) {
            scheduleAbs(EventSlot.SLOT_REG, peekRegChange().trigger, EventID.REG_CHANGE);
        }
    }

    private void eolHandler() {
        // Placeholder for end-of-line actions
        Arrays.fill(busOwners, BusOwner.NONE.ordinal());
    }

    private void hsyncHandler() {
        // VirtualCRT consuma l'HSYNC osservando hpos/vpos dal MasterClock.
        // Il modello del potenziometro invece l'HSYNC lo vuole: conta una riga alla volta
        // (oracolo: cap_check(true) da CIA_hsync_posthandler). Vedi Paula.capCheck.
        if (paula != null) paula.potHsync();
    }

    private void serviceRegEvent(long until) {
        if ((syncEvent & EVFL_EOL) != 0) {
            eolHandler();
            syncEvent &= ~EVFL_EOL;
        }
        if ((syncEvent & EVFL_HSYNC) != 0) {
            hsyncHandler();
            syncEvent &= ~EVFL_HSYNC;
        }

        while (!isRegChangeHeapEmpty()) {
            if (peekRegChange().trigger > until) break;
            RegChange change = pollRegChange();
            applyDelayedRegisterChange(change.reg, change.value, change.pc);
            releaseRegChange(change);
        }
        scheduleNextRegEvent();
    }

    /**
     * Una scrittura a BPLxPTH/BPLxPTL è <b>persa</b> se quel piano ha il proprio slot di
     * fetch nel <b>CCK SUCCESSIVO</b>: il puntatore per quello slot è già stato latchato.
     *
     * <p>⛔ <b>Il verso conta, ed era invertito.</b> Il modello che c'era —
     * {@code dropWrite()}, portato da vAmiga: «una scrittura a un registro puntatore è
     * scartata se il puntatore è stato usato un ciclo <i>prima</i>» — guarda il CCK
     * sbagliato, ed era per giunta su un percorso morto. Nell'oracolo la sequenza è una
     * pipeline a due stadi:
     * <pre>
     * cck C  : bitplane_rga_ptmod()  →  r->pv = bplpt[bpl]      (CATTURA)
     * cck C+1: fetch da r->pv, addmodfm(&r->pv), bplpt[num] = r->pv   (USO + WRITE-BACK)
     * </pre>
     * — e il commento di {@code custom.cpp} lo dichiara: <i>«Because BPL and SPR DMA is
     * decided 1 CCK earlier than others, PT and MOD need to be loaded in following
     * cycle»</i>. Una scrittura che cade nel CCK della cattura arriva <b>dopo</b> di essa,
     * e un CCK più tardi il write-back la cancella. Una scrittura un CCK prima, o un CCK
     * dopo, sopravvive.
     *
     * <p>⚠️ <b>Misurato sui due lati con la stessa sonda</b> ({@code WINUAE_BPLPTLOG} da una
     * parte, {@code -Damiga.bplptlog} dall'altra), su {@code BPLPTR/drop31}, che spazza il
     * CCK della scrittura di un'unità alla volta. Delle otto finestre del test l'oracolo
     * perde la scrittura esattamente nelle due in cui il fetch del piano cade al CCK
     * successivo (vpos 73 e 121), e le tiene nelle altre sei. Questa regola le riproduce
     * tutte e otto.
     */
    private boolean bplPointerWriteIsLost(int bidx) {
        // ⛔ Vale per le scritture del COPPER, non per quelle della CPU, e la ragione è
        // l'ordine dentro `do_cck()`: `bitplane_rga_ptmod()` (la cattura) sta in cima
        // all'iterazione, PRIMA che il Copper prenda il suo slot DMA, mentre la CPU gira
        // in `do_cycles_normal()` dopo `inc_cck()`, cioè prima della cattura successiva.
        // La cattura sta quindi FRA la finestra di scrittura della CPU e quella del
        // Copper. ⚠️ Misurato, non dedotto: nei log di `dropcpu41` e `dropcpu61` la
        // scrittura della CPU compare SEMPRE prima della `CAPT` dello stesso cck e
        // sopravvive; in `drop31` quella del Copper compare DOPO e viene persa.
        if (!__inCopperWrite || denise == null || bprun == 0 || !isBitplaneDMAActive()) {
            return false;
        }
        // ⚠️ `bprunCycle` è già stato incrementato per il CCK corrente (la decisione di
        // fetch precede il Copper dentro il ciclo), quindi il suo valore di adesso È la
        // fase del CCK successivo — quello la cui cattura la scrittura sta per perdere.
        final boolean hires = denise.isHires();
        final int plane = bplSequence(bprunCycle, hires);
        return plane >= 0 && plane < bitplaneDmaPlaneLimit(fetchUnitBpu, hires) && plane == bidx;
    }

    /**
     * ⚠️ <b>Percorso morto</b>: {@link #recordRegisterChange} non ha chiamanti, quindi
     * nessuna scrittura passa più di qui. Resta perché la coda dei cambi ritardati serve
     * ad altro; la regola dei puntatori di bitplane vive in
     * {@link #bplPointerWriteIsLost(int)}, sul percorso vivo.
     */
    private boolean dropWrite(BusOwner owner) {
        // vAmiga: A write to a pointer register is dropped if the pointer was used one
        // cycle before the update would happen.
        int cck = hpos >> 1;
        if (cck >= 1 && busOwners[cck - 1] == owner.ordinal()) {
            return true;
        }
        return false;
    }

    private void applyDelayedRegisterChange(AgnusReg reg, int value, int pc) {
        switch (reg) {
            case DMACON:  setDMACON(value, pc); break;
            case BPLCON0: setBPLCON0(value, pc); break;
            case BPLCON1: setBPLCON1(value); break;
            case DIWSTRT:
                diwStrt = value;
                diwVStart = (diwStrt >> 8) & 0xFF;
                if (denise != null) denise.writeRegister(0x08E, value);
                break;
            case DIWSTOP:
                diwStop = value;
                diwVStop = (diwStop >> 8) & 0xFF;
                if ((diwStop & 0x8000) == 0) diwVStop += 256;
                if (denise != null) denise.writeRegister(0x090, value);
                break;
            case DDFSTRT:
                ddfStrt = value;
                ddfCckStart = ddfStrt & ddfMask();
                break;
            case DDFSTOP:
                ddfStop = value;
                ddfCckStop = decodeDdfStopCck(ddfStop);
                break;
            case BPL1MOD:
                bpl1mod = (short) (value & 0xFFFE);
                break;
            case BPL2MOD:
                bpl2mod = (short) (value & 0xFFFE);
                break;
            case BPL1PTH: case BPL2PTH: case BPL3PTH: case BPL4PTH: case BPL5PTH: case BPL6PTH: {
                int bidx = (reg.ordinal() - AgnusReg.BPL1PTH.ordinal());
                if (dropWrite(OWNER_VALUES[BusOwner.BPL1.ordinal() + bidx])) break;
                bplPt[bidx] = (bplPt[bidx] & 0x0000FFFF) | ((value << 16) & dmaPtrHiMask);
                bplPtrInternal[bidx] = (bplPtrInternal[bidx] & 0x0000FFFF) | (bplPt[bidx] & dmaPtrHiMask);
                break;
            }
            case BPL1PTL: case BPL2PTL: case BPL3PTL: case BPL4PTL: case BPL5PTL: case BPL6PTL: {
                int bidx = (reg.ordinal() - AgnusReg.BPL1PTL.ordinal());
                if (dropWrite(OWNER_VALUES[BusOwner.BPL1.ordinal() + bidx])) break;
                bplPt[bidx] = (bplPt[bidx] & dmaPtrHiMask) | (value & 0xFFFE);
                bplPtrInternal[bidx] = bplPt[bidx];
                break;
            }
            case SPR0POS: case SPR1POS: case SPR2POS: case SPR3POS: case SPR4POS: case SPR5POS: case SPR6POS: case SPR7POS: {
                int sidx = (reg.ordinal() - AgnusReg.SPR0POS.ordinal());
                setSPRxPOS(sidx, value);
                break;
            }
            case SPR0CTL: case SPR1CTL: case SPR2CTL: case SPR3CTL: case SPR4CTL: case SPR5CTL: case SPR6CTL: case SPR7CTL: {
                int sidx = (reg.ordinal() - AgnusReg.SPR0CTL.ordinal());
                setSPRxCTL(sidx, value);
                break;
            }
            case SPR0PTH: case SPR1PTH: case SPR2PTH: case SPR3PTH: case SPR4PTH: case SPR5PTH: case SPR6PTH: case SPR7PTH: {
                int sidx = (reg.ordinal() - AgnusReg.SPR0PTH.ordinal());
                if (dropWrite(OWNER_VALUES[BusOwner.SPRITE0.ordinal() + sidx])) break;
                sprPt[sidx] = (sprPt[sidx] & 0x0000FFFF) | ((value << 16) & dmaPtrHiMask);
                // SPRxPT è il puntatore DMA LIVE: una scrittura (CPU o Copper) cambia da
                // dove il prossimo accesso DMA sprite preleva, esattamente come i bitplane
                // (HW Agnus non ha un puntatore interno
                // separato per gli sprite). Senza questo i giochi che riposizionano gli
                // sprite via Copper a inizio frame (es. Silkworm) prelevano dati stantii.
                sprPtrInternal[sidx] = (sprPtrInternal[sidx] & 0x0000FFFF) | (sprPt[sidx] & dmaPtrHiMask);
                break;
            }
            case SPR0PTL: case SPR1PTL: case SPR2PTL: case SPR3PTL: case SPR4PTL: case SPR5PTL: case SPR6PTL: case SPR7PTL: {
                int sidx = (reg.ordinal() - AgnusReg.SPR0PTL.ordinal());
                if (dropWrite(OWNER_VALUES[BusOwner.SPRITE0.ordinal() + sidx])) break;
                sprPt[sidx] = (sprPt[sidx] & dmaPtrHiMask) | (value & 0xFFFE);
                sprPtrInternal[sidx] = sprPt[sidx];
                break;
            }
            case DSKPTH:
                if (dropWrite(BusOwner.DISK)) break;
                dskpt = (dskpt & 0x0000FFFF) | ((value << 16) & dmaPtrHiMask);
                if (paula != null) paula.setDiskDmaPointerHigh(value & 0x001F);
                break;
            case DSKPTL:
                if (dropWrite(BusOwner.DISK)) break;
                dskpt = (dskpt & dmaPtrHiMask) | (value & 0xFFFE);
                if (paula != null) paula.setDiskDmaPointer(dskpt);
                break;
            case COPCON: copper.writeRegister(0x02E, value); break;
        }
    }

    /**
     * Equivalente di {@code sprstartstop()} di WinUAE (custom.cpp): ogni volta che
     * VSTART/VSTOP di uno sprite cambiano (scrittura di SPRxPOS/SPRxCTL da CPU,
     * Copper o fetch DMA del descrittore) lo stato DMA dello sprite va rivalutato
     * SULLA RIGA CORRENTE, non solo al cambio riga. È così che un gioco può
     * (ri)avviare uno sprite a metà frame scrivendo POS/CTL via Copper.
     */
    private void sprStartStop(int idx) {
        // ⛔ La riga è {@link #triggerVpos}, non {@code vpos}: nell'oracolo `sprstartstop()`
        // legge lo stesso `vpos` che `custom_trigger_start()` ha già incrementato, e la
        // differenza si vede negli ULTIMI TRE CCK della riga. È ciò che `sprdma12`/`sprdma13`
        // misurano — *«SPRxCTL modified at the end of a rasterline»*: il Copper scrive
        // SPRxCTL a `WAIT $47DF` (fine riga 71) e l'oracolo, che lì è già alla riga 72, trova
        // `vpos == vstop` col VSTOP VECCHIO e **addormenta** lo sprite; noi eravamo ancora a
        // 71, nessun match, e lo sprite continuava a disegnare per diciotto righe.
        if (inVBlankArea(triggerVpos)) return;
        if (triggerVpos == sprVStart[idx]) sprDmaEnabled[idx] = true;
        if (triggerVpos == sprVStop[idx]) {
            sprDmaEnabled[idx] = false;
            // ⚠️ ASIMMETRIA DELL'ORACOLO, trascritta com'è: sul percorso della SCRITTURA
            // (`sprstartstop`) il VSTOP AZZERA `dmacycle`, mentre allo slot 0 di
            // `generate_sprites()` lo mette a 1. Le due funzioni si somigliano ma non
            // fanno la stessa cosa, ed è la differenza che decide chi vince fra una
            // scrittura del Copper e il DMA.
            sprDmaCycle[idx] = 0;
        }
    }

    /** SPRxPOS: come WinUAE SPRxPOS() → sprstartstop, ricalcolo, sprstartstop. */
    private void writeSPRxPOS(int idx, int value) {
        sprStartStop(idx);
        setSPRxPOS(idx, value);
        sprStartStop(idx);
    }

    /** SPRxCTL: come WinUAE SPRxCTL() → sprstartstop, ricalcolo, sprstartstop. */
    private void writeSPRxCTL(int idx, int value) {
        sprStartStop(idx);
        setSPRxCTL(idx, value);
        sprStartStop(idx);
    }

    private void setSPRxPOS(int idx, int value) {
        // SPRxPOS bit 15-8 = VSTART[7:0]; bit 0 = HSTART bit 0 (ignored here).
        // Il bit 8 di VSTART arriva da SPRxCTL bit 2, già memorizzato in sprVStart.
        sprVStart[idx] = (sprVStart[idx] & 0x100) | ((value >> 8) & 0xFF);
    }

    private void setSPRxCTL(int idx, int value) {
        // SPRxCTL bit 15-8 = VSTOP[7:0]
        // SPRxCTL bit 2    = VSTART bit 8
        // SPRxCTL bit 1    = VSTOP  bit 8
        sprVStart[idx] = (sprVStart[idx] & 0x00FF) | ((value & 0x04) != 0 ? 0x100 : 0);
        sprVStop[idx]  = ((value >> 8) & 0xFF)     | ((value & 0x02) != 0 ? 0x100 : 0);
        // A5 — Sprite V9 (ECS): SPRxCTL guadagna un nono bit verticale codificato
        // nei bit "inutilizzati" 6 (VSTART V9) e 5 (VSTOP V9). Ignorato su OCS.
        // Rif. vAmiga AgnusRegs.cpp:516-525. Non è hot path (≈8 scritture/frame).
        if (isECS()) {
            if ((value & 0x40) != 0) sprVStart[idx] |= 0x200;
            if ((value & 0x20) != 0) sprVStop[idx]  |= 0x200;
        }
    }

    private void setDMACON(int value, int pc) {
        int oldValue = dmacon;
        boolean set = (value & 0x8000) != 0;
        int bits    = value & 0x07FF;
        int newValue;
        if (set) newValue = (oldValue | bits) & 0x07FF;
        else     newValue = (oldValue & ~bits) & 0x07FF;

        if (oldValue == newValue) return;

        boolean oldBitplaneEnabled = (oldValue & 0x0200) != 0 && (oldValue & 0x0100) != 0;
        boolean newBitplaneEnabled = (newValue & 0x0200) != 0 && (newValue & 0x0100) != 0;
        // Un frame è "parziale" (da non mostrare) solo se il DMA bitplane era SPENTO
        // all'apertura della finestra di display e viene acceso più giù, DENTRO la
        // finestra [diwVStart, diwVStop): le prime righe visibili sono bordo invece
        // che contenuto (es. la mano insert-disk del Kickstart accende il DMA a metà
        // finestra mentre la cima è ancora vuota).
        //
        // NON va marcato (= frame completo, da mostrare) quando:
        //  - il DMA viene acceso FUORI dalla finestra (Monkey Island 2 schermata di
        //    protezione: CLR a riga 233 = fine finestra, poi SET a riga 234, come
        //    preparazione del frame successivo); oppure
        //  - il DMA era GIÀ ACCESO all'inizio della finestra e viene solo commutato a
        //    metà (split-screen legittimo: il pannello verbi/inventario di Monkey
        //    Island 2 ri-accende BPLEN a ~riga 178 ogni frame). In questi casi la cima
        //    del display ha già contenuto: scartare il frame congelerebbe lo schermo.
        // La vecchia soglia "vpos != 0" scartava OGNI frame della protezione; quella
        // successiva "dentro la finestra" scartava OGNI frame della schermata di gioco
        // (split-screen) → schermo nero col gioco che girava sotto.
        if (!oldBitplaneEnabled && newBitplaneEnabled
                && vpos >= diwVStart && vpos < diwVStop && !bitplaneDmaOnAtWindowStart) {
            bitplaneDmaEnabledLateThisFrame = true;
        }

        final boolean copWas = copperDmaEnabled;

        dmacon = newValue;
        updateDmaFlags();

        // ⛔ Un cambio di COPEN NON e' visibile all'arbitraggio nello stesso CCK: finche'
        // il ciclo corrente non ha superato la soglia, il Copper legge il valore VECCHIO.
        // L'oracolo lo scrive in due pezzi (custom.cpp):
        //
        //   if (newcop != oldcop && safecpu())
        //       copper_dma_change_cycle = copper_access ? get_cycles()
        //                                               : get_cycles() + CYCLE_UNIT;
        //   static bool is_copper_dma(bool checksame) {
        //       bool dma = dmaen(DMA_COPPER);
        //       if (checksame && get_cycles() <= copper_dma_change_cycle) return !dma;
        //       return dma;
        //   }
        //
        // ⇒ la soglia e' `<=`, quindi una scrittura del COPPER costa 1 CCK e una della
        // CPU ne costa 2. `safecpu()` e' vero per la nostra configurazione (68000
        // cycle-exact), quindi il ritardo vale sempre.
        // ⚠️ E' la stessa forma della classe B del §14 (DDFSTRT/DDFSTOP: il match usa il
        // valore vecchio) e del `spriteDmaOnDecisionCck` qui sopra. Misurato sui quattro
        // `lc` del banco Agnus/Copper, che accendono il DMA del Copper a meta' riga: erano
        // 8 px ciascuno, cioe' 2 CCK di anticipo, sempre su una riga sola.
        if (copWas != copperDmaEnabled) {
            copperDmaChangeCck = (masterClock >> 1) + (__inCopperWrite ? 0 : 1);
        }

        // Notify other components if needed
        if (blitter != null) {
            blitter.setDmaEnabled(blitterDmaEnabled);
        }
        if (copper != null) {
            copper.setDmaEnabled(copperDmaEnabled);
        }
        if (paula != null) {
            paula.setAudioDmaEnabledMask(dmacon);
        }
    }

    private void setBPLCON0(int value, int pc) {
        if (denise != null) denise.writeRegister(0x100, value);
        // ⛔ Il rilascio è IMMEDIATO, l'ingresso no: `checksyncstopped()` dell'oracolo
        // spegne `syncs_stopped` sulla scrittura, mentre lo accende solo nel ramo non
        // cycle-exact. Con la configurazione del banco l'unico ingresso è il confine di
        // riga in `updateBeam`. Quindi un ERSY alzato e riabbassato dentro la stessa riga
        // non arriva mai qui con `syncsStopped` vero, e non fa niente.
        if (syncsStopped && !ersyActive()) {
            syncsStopped = false;
            // ERSY che CADE: la riga riparte da capo.
            //
            // In WinUAE, per tutta la durata del freeze, `setsyncstoppos()` azzera
            // `agnus_hpos` a OGNI cck (custom.cpp, do_cck: "if (syncs_stopped) {
            // setsyncstoppos(); set_fakehsync_handler(); }"), quindi quando la scrittura
            // di BPLCON0 spegne ERSY il pennello è a inizio riga e la riga corrente dura
            // per intero. Da noi le righe hanno continuato a scorrere: senza questo
            // azzeramento il fotogramma del freeze si allunga di un multiplo di riga
            // invece che della durata del freeze, ed è la causa dei 126 CCK di fase che
            // restavano dopo il §13.8 (docs/vamigats-paula-audio-plan.md §13.10.4).
            //
            // Il resto di `setsyncstoppos()` — hpos inchiodato *durante* il freeze, e
            // quindi nessuno slot DMA fisso per quelle ~21 righe — non è modellato: da
            // noi il DMA di refresh/disco/audio continua. Non è osservabile qui (il
            // rilevamento genlock della graphics.library gira senza disco né audio), e
            // costerebbe mandare ogni ciclo nello slot 0.
            // ⚠️ `maxHpos - 2`, non 0: il nostro `vpos++` vive nel WRAP della riga mentre
            // quello di WinUAE (`custom_trigger_start`) sta un CCK DENTRO — la stessa
            // convenzione di INITIAL_HPOS (piano §5.2). Tradotto in questa scala,
            // «il pennello è a inizio riga» si scrive maxHpos-2. Con `hpos = 0` il primo
            // `vpos++` dopo il rilascio cadeva a R+226 invece che a R+3 (misurato sui due
            // lati a ritardo d'avvio e drive fissati, piano §7.1.13): 223 CCK che si
            // propagavano a tutto il resto del boot.
            hpos = maxHpos - 2;
            // `resetfulllinestate()`: la riga che riparte non eredita i latch di quella
            // interrotta.
            fetchWindowOpened = false;
            bpuRelatchCck = -1;
        }
        // Una scrittura di BPLCON0 a finestra di fetch già aperta diventa efficace
        // sul fetch da write+4 CCK: WinUAE pipelinea la write di +2
        // (`pipelined_custom_write(BPLCON0_delayed, v, 2)`, custom.cpp:3585) e la
        // decisione di fetch per il cck X avviene a X−2 (generate_bpl) → effetto
        // visibile dai fetch con cck ≥ W+4. Misurato sui [WRGA] (write a cck 210
        // in entrambi i casi): bbusy1f v96, BPU 0→1 → l'hardware fetcha BPL1DAT
        // a 215 (≥214 ✓); bbusy4 v160, BPU 2→3 → il piano extra a 213 (<214) NON
        // è fetchato. Né il latch immediato né il vecchio latch solo-a-DDFSTRT
        // riproducono entrambi.
        if (fetchWindowOpened) {
            bpuRelatchCck = (hpos >> 1) + 4;
        }
    }

    private void setBPLCON1(int value) {
        bplcon1 = value & 0xFF;
        if (denise != null) denise.writeRegister(0x102, value);
    }

    /**
     * Lo scroll orizzontale di BPLCON1 in <b>sub-pixel</b> (pixel hires), che è l'unità in
     * cui Denise lo applica davvero.
     *
     * <p>⛔ <b>In hires lo scroll NON è più fine: è più CORTO.</b> Una unità di BPLCON1 vale
     * un pixel <b>lores</b> — cioè 2 sub-pixel — <b>in tutte e due le risoluzioni</b>; quel
     * che cambia in hires è che il valore viene <b>mascherato a 3 bit</b> e quindi si
     * avvolge a 8. L'oracolo lo scrive in {@code update_bplcon1()}:
     * <pre>
     * bplcon1_shift_mask = fetchmode_mask_denise &gt;&gt; denise_res;   // 15 in lores, 7 in hires
     * bplcon1_shift[0]   = delay1 &amp; bplcon1_shift_mask;
     * bplcon1_shift_full[0] = (bplcon1_shift[0] &lt;&lt; 2) | bplcon1_hr[0];  // &lt;&lt;2 SEMPRE
     * </pre>
     * dove {@code fetchmode_mask_denise} è 15 su OCS/ECS ({@code 16 << bpldat_fmode}, fmode
     * 0) e {@code bplcon1_hr} è la parte sub-hires di AGA, zero qui. Lo spostamento è quindi
     * {@code (v & mask) << 2} in <b>shres</b>, e uno shres è mezzo sub-pixel dei nostri: da
     * cui il {@code * 2}.
     *
     * <p>⛔ <b>Dedurre «un'unità = un pixel della risoluzione corrente» è sbagliato, ed è
     * stato misurato</b>: con {@code v * (hires ? 1 : 2)} il gruppo {@code simple} peggiora
     * da 6,77 % a 9,55 %. La regola è quella dell'oracolo, non quella che sembra simmetrica.
     *
     * <p>La regola unica {@code sub = (v & mask) * 2} si divide poi in:
     * <ul>
     *   <li><b>grossa</b>, {@code sub >> 2}: quanti CCK ritardare il blocco di draw (un CCK
     *       sono 4 sub-pixel), ed è quel che decide {@link #handleBitplaneDrawEvent};</li>
     *   <li><b>fine</b>, {@code sub & 3}: il resto sotto il CCK, che applica Denise
     *       spostando il cursore di uscita.</li>
     * </ul>
     *
     * <p>⚠️ In lores si riduce a quel che il codice faceva già ({@code v>>1} CCK più il bit
     * 0), ed è per questo che il lores di {@code BPLCON1/simple} era verde. In <b>hires</b>
     * no: mancava la maschera a 3 bit e la parte fine avanzava di un sub-pixel invece che di
     * due. Sono le righe hires di {@code simple0÷14}.
     *
     * <p>Nell'oracolo non esiste nessuna divisione: lo scroll è <b>un solo contatore</b> in
     * Denise ({@code bplcon1_shift[]} + {@code bplshiftcnt[]}). La nostra divisione in due è
     * una deroga di struttura; questa funzione è il punto in cui resta almeno <b>una sola
     * regola</b>.
     */
    private static int scrollSubPixels(int pfh, boolean hires) {
        return (pfh & (hires ? 0x07 : 0x0F)) * 2;
    }

    // --- End of Delayed Register Changes ---

    public long getMasterClock() {
        return masterClock;
    }

    public void scheduleAbs(EventSlot slot, long cycle, EventID id) {
        int s = slot.ordinal();
        trigger[s] = cycle;
        eventIds[s] = id;
        if (cycle != NEVER) {
            activeSlotMask |= (1L << s);
            if (cycle < nextTrigger) nextTrigger = cycle;
        } else {
            // Schedulare a NEVER è equivalente a disattivare lo slot (non scatta mai,
            // non abbassa nextTrigger): toglilo dalla maschera attiva.
            activeSlotMask &= ~(1L << s);
        }
    }

    public void scheduleAbs(EventSlot slot, long cycle, EventID id, long data) {
        scheduleAbs(slot, cycle, id);
        eventData[slot.ordinal()] = data;
    }

    public void scheduleRel(EventSlot slot, long delay, EventID id) {
        scheduleAbs(slot, masterClock + delay, id);
    }

    public void scheduleRel(EventSlot slot, long delay, EventID id, long data) {
        scheduleAbs(slot, masterClock + delay, id, data);
    }

    public void cancel(EventSlot slot) {
        int s = slot.ordinal();
        eventIds[s] = EventID.EVENT_NONE;
        trigger[s] = NEVER;
        activeSlotMask &= ~(1L << s);
        // Recalculating nextTrigger would be expensive, we'll do it during execution if needed
    }

    public boolean hasEvent(EventSlot slot) {
        return eventIds[slot.ordinal()] != EventID.EVENT_NONE;
    }

    private void executeEvents(long cycle) {
        // STEP 13 (PERFORMANCE_REPORT_FINAL): scansione SINGOLA. In precedenza si
        // facevano due scansioni dei 25 slot (una per eseguire gli eventi, una per
        // ricalcolare nextTrigger). Ora si fa scattare gli eventi e si ricostruisce
        // nextTrigger nello stesso passaggio: si parte da NEVER e si prende il minimo
        // dei trigger NON scattati; gli eventi ri-schedulati durante processEvent
        // abbassano nextTrigger tramite scheduleAbs (che fa nextTrigger=min(...)).
        //
        // Correttezza: il firing è identico (stesso ordine, stessa condizione
        // trigger[i] <= cycle). nextTrigger risultante è sempre <= vero minimo dei
        // trigger correnti (mai superiore), quindi nessun evento viene mai perso o
        // anticipato. L'unico effetto possibile è un "stale-low" (nextTrigger più
        // basso del vero minimo se un handler ri-schedula PIÙ TARDI uno slot già
        // scansionato) che provoca al più una chiamata extra no-op a executeEvents —
        // esattamente la stessa classe di comportamento già prodotta da cancel(),
        // che lascia nextTrigger invariato. trigger[] ed eventIds[] sono sempre
        // coerenti (trigger=NEVER ⟺ eventIds=NONE), quindi nessun slot finito è
        // mai trattato come non-scattato per errore.
        nextTrigger = NEVER;
        // Scansione dei soli slot ATTIVI (bit settati in activeSlotMask), in ordine di
        // indice crescente. activeSlotMask è ri-letta ad ogni passo (non snapshot) così
        // un handler che ri-schedula uno slot a indice più alto dentro lo stesso `cycle`
        // viene comunque visitato in questo passaggio — esattamente come la vecchia
        // scansione lineare 0..24 leggeva trigger[]/eventIds[] dal vivo. Gli slot non
        // attivi (saltati) erano no-op, quindi il risultato (firing + nextTrigger) è
        // identico bit-per-bit.
        int i = 0;
        while (true) {
            long remaining = activeSlotMask >>> i;
            if (remaining == 0L) break;
            i += Long.numberOfTrailingZeros(remaining);

            if (trigger[i] <= cycle && eventIds[i] != EventID.EVENT_NONE) {
                EventID id = eventIds[i];
                EventSlot slot = SLOT_VALUES[i];
                long data = eventData[i];

                // Clear event before processing (it might reschedule itself)
                eventIds[i] = EventID.EVENT_NONE;
                trigger[i] = NEVER;
                activeSlotMask &= ~(1L << i);

                processEvent(slot, id, data);
            } else if (trigger[i] < nextTrigger) {
                nextTrigger = trigger[i];
            }
            i++;
        }
    }

    private void processEvent(EventSlot slot, EventID id, long data) {
        switch (slot) {
            case SLOT_REG:
                serviceRegEvent(masterClock);
                break;
            case SLOT_IRQ:
                if (paula != null) paula.serviceIrqEvent();
                break;
            case SLOT_IPL:
                if (paula != null) paula.serviceIplEvent(data);
                break;
            case SLOT_TXD:
                if (paula != null) paula.serviceTxdEvent(id);
                break;
            case SLOT_RXD:
                if (paula != null) paula.serviceRxdEvent(id);
                break;
            case SLOT_DSK:
                if (paula != null) paula.serviceDiskEvent();
                break;
            case SLOT_CIAA:
                serviceCiaEvent();
                break;
            case SLOT_CH0:
                // Produzione di un sample di output audio (event-driven). Sostituisce
                // l'accumulatore outputSamplePhase che girava in paula.tick() ad OGNI
                // master-cycle: ora la matematica del rate gira solo una volta per
                // sample (~44.1k/s invece di ~7.09M/s). Vedi piano
                // docs/event-driven-agnus-step2.md (paula.tick).
                if (paula != null) paula.serviceAudioSampleEvent();
                break;
            // Aggiungi altri gestori se necessario
        }
    }

    /**
     * Tick E-clock delle CIA (CLK/10), del keyboard e del Gary, schedulato come
     * evento auto-rischedulante su {@link EventSlot#SLOT_CIAA}.
     *
     * Sostituisce il vecchio accumulatore {@code ciaTimerPhase} (polling
     * incondizionato ad ogni master-cycle): l'evento scatta esattamente agli stessi
     * master-cycle (multipli di 10) ma a costo zero nei 9 cicli intermedi, perché
     * {@code nextTrigger} impedisce di entrare in executeEvents quando non c'è nulla
     * da fare (piano docs/event-driven-agnus.md, Step 1 / Problema B).
     *
     * Le due CIA vengono tick-ate INSIEME ad ogni E-clock, come nel modello polling
     * precedente: NON si usano due slot a fasi diverse (cambierebbe il timing e
     * romperebbe il golden CRC). Il rate hardware-corretto è CLK/10 (vedi nota sul
     * detune DiagROM 19/200 rimosso a favore della sync audio dei giochi).
     */
    private void serviceCiaEvent() {
        if (ciaA != null) ciaA.tick();
        if (ciaB != null) ciaB.tick();
        if (keyboard != null) keyboard.tick(10);

        // ── Reset da tastiera CTRL+AMIGA+AMIGA (aggiornato a frequenza E-Clock) ──
        // La MPU della tastiera tiene bassa /KBRST; Gary la propaga su /RESET+/HALT
        // verso il 68000. Sul fronte di discesa la CPU viene resettata e a sua volta
        // asserisce la propria linea RESET: da lì la cascata (chip custom, CIA,
        // floppy, bus Zorro, ri-attivazione dell'overlay ROM) è la stessa
        // dell'istruzione RESET, gestita in risingEdge(). La RAM non viene toccata:
        // è un warm reset, esattamente come sull'hardware.
        if (gary != null) {
            if (keyboard != null) gary.setKeyboardResetLine(keyboard.isResetLineAsserted());
            if (gary.isResetAsserted()) {
                if (!systemResetActive) {
                    systemResetActive = true;
                    if (cpu != null) cpu.reset();
                }
                // Finché /RESET è bassa la macchina resta ferma: la finestra di reset
                // della CPU viene rinnovata a ogni E-clock (10 cicli), così scade solo
                // quando la tastiera rilascia la linea e la CPU riparte dal reset vector.
                if (cpu != null) cpu.assertReset(64);
            } else {
                systemResetActive = false;
            }
        }

        // Segnali OVL (ROM overlay) e LED/filtro audio: dipendono solo dal latch
        // PRA/DDRA di CIA-A, che cambia in risposta a scritture CPU sul CIA. Su
        // hardware reale ogni accesso CIA è sincronizzato all'E-clock (bus 6800 via
        // /VMA), quindi campionarli qui (granularità E-clock, ~709k/s) invece che ad
        // ogni master-cycle è funzionalmente equivalente e 10× più economico. La
        // parte TOD-LOW resta nel loop SC (è temporizzata sul raster). Il path di
        // reboot via istruzione CPU RESET è gestito a parte in risingEdge (chiamata
        // diretta a updateCiaOvlLed dopo softReset), perché rilegge il reset vector
        // entro pochi cicli e non può attendere il prossimo E-clock.
        updateCiaOvlLed();

        // Prossimo E-clock fra 10 master-cycle. Eseguito DOPO l'eventuale reset:
        // dopo un hard reset masterClock==0 → ri-arma a 10 (coerente con l'arm di
        // internalReset).
        scheduleRel(EventSlot.SLOT_CIAA, 10, EventID.CIA_EXECUTE);
    }

    // =========================================================================
    // Stato bus
    // =========================================================================

    private enum BusOwner {
        NONE, REFRESH, DISK, AUDIO0, AUDIO1, AUDIO2, AUDIO3,
        SPRITE0, SPRITE1, SPRITE2, SPRITE3, SPRITE4, SPRITE5, SPRITE6, SPRITE7,
        BPL1, BPL2, BPL3, BPL4, BPL5, BPL6, COPPER, BLITTER, CPU, BLOCKED
    }

    /**
     * STEP 4 (PERFORMANCE_REPORT_FINAL): cache statica di BusOwner.values().
     * Usata negli hot path di risingEdge() e nella DMA arbitration (dropWrite),
     * indicizzata con ordinal()+offset. Evita il clone dell'array ad ogni accesso.
     */
    private static final BusOwner[] OWNER_VALUES = BusOwner.values();

    /**
     * Fase (in master-cycle) a cui è ancorata la griglia del clock delle CIA. Vedi la
     * nota estesa sull'arm di {@code SLOT_CIAA} nel costruttore: è una fase, e va
     * insieme al punto in cui una scrittura CIA consegna il dato al chip.
     */
    private static final int CIA_ANCHOR = 5;

    // STEP 14/P-15 (PERFORMANCE_REPORT_FINAL): busOwners come int[] di ordinali
    // invece di BusOwner[]. Arrays.fill(int[]) è più veloce del fill di un array di
    // reference (niente card-marking) ed è eseguito ~15.600 volte/s (eolHandler).
    // 0 == BusOwner.NONE.ordinal(), quindi il default int 0 coincide con NONE.
    // Il confronto per ordinali è equivalente al confronto per reference enum.
    // 228 e non 227: la riga lunga NTSC (LOL) ha un CCK in più, indice 227.
    private final int[] busOwners = new int[228];


    /** DIAG (Fase F): conta, per gli accessi del loop firework ($3000-$31FF), quante
     *  decisioni DTACK sono bloccate e da QUALE owner DMA (busOwners[]) o dall'odd-wait,
     *  per trovare l'over-allocazione slot. Dump da IkPlusBootTest. */
    public static volatile boolean diagBlockTally = false;
    public static final long[] diagBlk = new long[25];
    public static long diagBlkOdd = 0, diagBlkTot = 0;

    // Il ritardo dei poke a INTREQ/INTENA non sta più qui (2026-08-14). Stava, come
    // `INTREQ_DELAY_MC = 1`, e differiva l'INTERA scrittura: il valore riletto da
    // INTREQR/INTENAR cambiava un mezzo CCK dopo insieme alla linea IPL. Nell'oracolo le
    // due cose sono separate — `intreq` cambia subito, `intreq2` (da cui si calcola l'IPL)
    // un CCK dopo — e la differenza non è cosmetica: è l'unica via per cui un interrupt
    // può risultare pendente in lettura e mai consegnato. Ora vive in Paula, su quella
    // sola copia (`Paula.INTREQ_SHADOW_DELAY_MC`, stesso mezzo CCK, stessa misura del pin
    // che l'aveva fissato col FIX #24 della §S4 di vAmigaTS cpu/timing).

    /** DIAG: cattura raster-keyed degli eventi che posizionano le barre bbusy
     *  (COLOR00 $180, INTREQ $09C, BLTSIZE $058). hpos loggato in CCK (hpos>>1).
     *  Consumato da {@code BbusyB0AnalysisTest}. */
    public static volatile boolean diagBb = false;
    public static int diagBbCap = 60000;
    public static final java.util.List<String> diagBbCol = new java.util.ArrayList<>();
    public static final java.util.List<String> diagBbInt = new java.util.ArrayList<>();
    public static final java.util.List<String> diagBbBlt = new java.util.ArrayList<>();
    /** Finestra di PC (opcodeAddress) su cui diagBlockTally campiona. Default = loop
     *  firework di IK+ ($3000-$3200); riconfigurabile (es. al loop synccpu di bbusy). */
    public static volatile int diagBlkPcLo = 0x3000;
    public static volatile int diagBlkPcHi = 0x3200;


    /**
     * Route dell'accesso CPU corrente. Calcolata mentre la CPU presenta il ciclo
     * (stati S2-S4 della SUA FSM) e riusata al trasferimento dati (S5/S6). Persiste
     * per l'intera durata di un singolo accesso. La CPU pre-splitta i long-word in
     * due word distinte (vedi Motorola68000.scheduleRead/scheduleWriteLong), quindi
     * Agnus vede sempre accessi a 8/16 bit: nessun cpuBusCyclePart necessario.
     */
    private Gary.Route cpuCurrentRoute = null;
    private boolean cpuRouteCacheValid = false;
    private int cpuRouteCacheAddress;
    private boolean cpuRouteCacheRead;
    private boolean cpuRouteCacheUds;
    private boolean cpuRouteCacheLds;

    /**
     * Latch "trasferimento dati eseguito" per l'accesso CPU corrente. Garantisce
     * che handleCpuDataTransfer() (con effetti collaterali: scritture in memoria,
     * letture con side-effect, latch del pennello) venga eseguito UNA sola volta
     * per accesso, anche se handleCpuBusCycle() è invocato a ogni fronte di clock.
     */
    private boolean cpuAccessTransferred = false;

    /**
     * PERF (Fase 2): true quando il path "CPU non sul bus" di handleCpuBusCycle ha
     * già azzerato le linee di handshake e la cache route. handleCpuBusCycle è
     * chiamato 2× per master-cycle e per la maggior parte dei cicli la CPU non
     * chiede il bus (AS basso): quel path azzerava incondizionatamente ~10 campi ad
     * ogni invocazione (~14M/s) benché siano idempotenti mentre AS resta basso
     * (nessun altro codice li tocca durante i cicli interni della CPU). Con questo
     * flag l'azzeramento avviene UNA sola volta alla discesa di AS; i fronti idle
     * successivi (e la doppia chiamata) si riducono a un check-and-return. Resettato
     * a false non appena AS risale → comportamento in uscita bit-identico.
     */
    private boolean cpuBusIdle = false;

    /**
     * STEP 2 (PERFORMANCE_REPORT_FINAL): scratch Route dedicati per ciascun master
     * di bus. gary.route(...) riempie questi oggetti invece di allocarne di nuovi
     * ad ogni accesso. Tre istanze distinte perché CPU, Copper e Blitter sono
     * serviti nello stesso fronte di clock: scratch separati evitano l'aliasing.
     * La route CPU persiste tra le fasi S4→S6 del ciclo bus, ma viene consumata
     * (S6) prima della successiva chiamata route() per la CPU (prossimo S4),
     * quindi un singolo cpuScratchRoute è sufficiente.
     */
    private final Gary.Route cpuScratchRoute     = new Gary.Route();
    private final Gary.Route copperScratchRoute  = new Gary.Route();
    private final Gary.Route blitterScratchRoute = new Gary.Route();

    private boolean copperBusProcessed  = false;

    /**
     * Contatore di cicli consecutivi in cui la CPU è stata bloccata dal Blitter.
     * Diagnostica/legacy (getArbitrationReason); la precedenza CPU vera è ora {@link #bls}.
     */
    private int cpuBlockedByBlitterCount = 0;

    /**
     * Segnale BLS (Blitter Slow down, modello vAmiga {@code Agnus::executeUntilBusIsFree}):
     * asserito quando la CPU è in attesa del bus da ≥2 CCK. Con BLTPRI spento, il blitter
     * cede lo slot alla CPU finché bls resta alto ({@code busIsFree<BLITTER>} nega). È il
     * sostituto cycle-exact del vecchio contatore-euristico {@code >=3}.
     */
    private boolean bls = false;
    /** Cicli (CCK) consecutivi in cui la CPU ha voluto il bus senza ottenerlo. */
    private int cpuBusWaitCck = 0;

    /** {@code BLIT_NASTY_CPU_STEAL_CYCLE_COUNT} di blitter.cpp. */
    private static final int BLIT_NASTY_CPU_STEAL_CYCLE_COUNT = 3;

    private boolean lastResetSignal = false;

    /**
     * /RESET di sistema (da Gary) attualmente asserita: la macchina è tenuta ferma
     * in reset dalla tastiera (CTRL+AMIGA+AMIGA). Serve a distinguere il fronte di
     * discesa — che resetta la CPU una volta sola — dal mantenimento della linea.
     */
    private boolean systemResetActive = false;

    private boolean lastLedState    = true;
    private int     lastBusData     = 0xFFFF;
    private int     lastCiaAPra     = -1;
    private int     lastCiaADdra    = -1;

    // =========================================================================
    // Posizione del raster (beam)
    // =========================================================================

    /** Posizione orizzontale corrente in system clock (0 – maxHpos-1). */
    private int hpos;

    /** Posizione verticale corrente in righe (0 – maxVpos-1). */
    private int vpos;

    /**
     * Campo corrente per l'interlace: 0 = frame pari (lungo), 1 = frame dispari (corto).
     * In modalità non-interlace è sempre 0.
     */
    private int field = 0;

    /**
     * {@code agnus_vsync} dell'oracolo: l'impulso di vsync è in corso. Serve solo a far
     * scattare l'aggancio <b>una volta sola</b> per fotogramma — in {@code custom.cpp}
     * {@code vsync_mark()} è dentro un {@code if (!agnus_vsync)}.
     */
    private boolean vsyncActive = false;

    /** Vedi {@link #getDisplayRowShift()}: latchato all'inizio del vsync, una volta per frame. */
    private int displayRowShift = 0;

    /**
     * Numero massimo di righe per frame.
     * PAL: 312 (righe 0-311), NTSC: 262 (righe 0-261).
     */
    // STEP 14/P-12 (PERFORMANCE_REPORT_FINAL) l'aveva reso `final` per il constant
    // folding del JIT. ⛔ Non può più esserlo: su ECS il modo si commuta A CALDO
    // scrivendo BEAMCON0 (vedi {@link #beamNtsc}), e `init_beamcon0()` dell'oracolo
    // riassegna `maxvpos` nel CCK stesso della scrittura. Resta assegnato nel
    // costruttore (262 NTSC / 312 PAL) e cambia SOLO su una scrittura a BEAMCON0 che
    // rovescia il bit PAL — cioè mai, in una macchina che non tocca il registro.
    private int maxVpos;

    /**
     * Numero di system clock della riga <b>corta</b>: 454 SC = 227 CCK.
     * È la lunghezza di ogni riga in PAL e della riga corta in NTSC.
     */
    private static final int MAXHPOS_SHORT = 454;

    /**
     * Riga su cui cade l'impulso TOD di <b>CIA-A</b>, il «50 Hz di rete».
     *
     * <p>⛔ Non è l'inizio del frame: è la <b>fine dell'impulso di vsync</b>, e in PAL cade
     * alla riga <b>5</b>. Oracolo, {@code custom.cpp}: <i>«CIA-A TOD counter increases when
     * vsync pulse ends»</i>, {@code if (vpos == VSYNC_ENDLINE_PAL) CIAA_tod_handler(...)},
     * con {@code VSYNC_ENDLINE_PAL 5} in {@code include/custom.h}. Qui l'impulso stava allo
     * <b>wrap di vpos</b>, cinque righe prima.
     *
     * <p>L'{@code hoffset} che l'oracolo passa insieme alla riga ({@code lof_store ? 132 : 18})
     * è modellato in {@link #todIncOffsetCck(int, long)}.
     */
    private static final int CIAA_TOD_VPOS = 5;

    /**
     * Riga su cui l'impulso di <b>vsync</b> COMINCIA — e con lui il fotogramma che il
     * display assembla.
     *
     * <p>⛔ <b>Non è una costante: dipende da LOF, e solo in PAL.</b> Oracolo,
     * {@code custom.cpp} (il blocco {@code if (realpal) … else …} che alza
     * {@code agnus_vsync}):
     * <pre>
     * PAL : SHS     &amp;&amp; vpos == 3 &amp;&amp;  lof_store  → vsync_mark()
     *       HCENTER &amp;&amp; vpos == 2 &amp;&amp; !lof_store  → vsync_mark()
     * NTSC: SHS     &amp;&amp; vpos == 3 &amp;&amp;  lof_store  → vsync_mark()
     *       HCENTER &amp;&amp; vpos == 3 &amp;&amp; !lof_store  → vsync_mark()
     * </pre>
     * cioè in PAL il fotogramma <b>corto</b> aggancia il vsync <b>una riga prima</b>,
     * mentre in NTSC le due righe coincidono e cambia solo il punto dentro la riga
     * (HSSTRT contro HCENTER, gli stessi due che {@link #TOD_HOFFSET_CIAA_LONG} e
     * {@link #TOD_HOFFSET_CIAA_SHORT} già portano).
     *
     * <p>Conta perché <b>il display si aggancia al vsync, non allo wrap di vpos</b>:
     * in {@code custom.cpp} è {@code vsync_mark()} ad azzerare {@code linear_vpos}, che è
     * la riga del framebuffer. Una riga di vpos V finisce quindi alla riga
     * {@code V − vsyncStartLine}, e in PAL con LOF=0 tutto il quadro scende di una riga.
     * Vedi {@link #getDisplayRowShift()}.
     */
    private static final int VSYNC_START_VPOS_PAL_LONG = 3;
    private static final int VSYNC_START_VPOS_PAL_SHORT = 2;
    private static final int VSYNC_START_VPOS_NTSC = 3;

    /** Riga su cui l'impulso di vsync FINISCE: PAL 5, NTSC 6 ({@code agnus_vsync = false}). */
    private static final int VSYNC_END_VPOS_PAL = 5;
    private static final int VSYNC_END_VPOS_NTSC = 6;

    /**
     * {@code hoffset} dell'impulso TOD di <b>CIA-B</b>: la fine dell'hsync.
     * Oracolo, {@code custom.cpp}: {@code CIAB_tod_handler(35); // hsync end}.
     */
    private static final int TOD_HOFFSET_CIAB = 35;

    /** {@code hoffset} dell'impulso TOD di CIA-A su frame <b>lungo</b> (LOF=1, HCENTER). */
    private static final int TOD_HOFFSET_CIAA_LONG = 132;

    /** {@code hoffset} dell'impulso TOD di CIA-A su frame <b>corto</b> (LOF=0, HSSTRT). */
    private static final int TOD_HOFFSET_CIAA_SHORT = 18;

    /**
     * Ritardo interno del contatore TOD, in CCK: {@code TOD_INC_DELAY} dell'oracolo,
     * {@code 12 * E_CLOCK_LENGTH / 2} = 12 E-clock = 60 CCK ({@code cia.cpp}).
     */
    private static final int TOD_INC_DELAY_CCK = 60;

    /**
     * Passo della griglia a cui l'incremento del TOD si allinea, in CCK: quattro E-clock
     * ({@code unit = (E_CLOCK_LENGTH * 4) / 2} in {@code tod_inc_delay}). ⚠️ È il modulo che
     * rende la posizione dell'impulso <b>diversa riga per riga</b>: 227 non è multiplo di 20.
     */
    private static final int TOD_ALIGN_CCK = 20;

    /** {@code E_CLOCK_TOD_N} dell'oracolo, in CCK: la correzione finale di {@code tod_inc_delay}. */
    private static final int TOD_ECLOCK_TOD_CCK = -2;

    /**
     * Larghezza dell'impulso TOD, in system clock. Due periodi di E-clock: garantisce che
     * la CIA ne veda il fronte di salita a un solo dei propri tick.
     */
    private static final int TOD_PULSE_WIDTH_SC = 20;

    /**
     * Posizione orizzontale all'accensione: <b>un CCK prima della fine della riga</b>,
     * cioè la prima riga della macchina dura 1 CCK e non 227.
     *
     * <p>Non è una scelta arbitraria, è ciò che fa l'oracolo, e il perché sta in
     * {@code HARDWIRED_DMA_TRIGGER_HPOS 1} (WinUAE {@code custom.cpp}): là il
     * {@code vpos++} non vive nel wrap di hpos ma in {@code do_cck()} —
     * {@code if (agnus_hpos == HARDWIRED_DMA_TRIGGER_HPOS) custom_trigger_start();} —
     * cioè un CCK <i>dentro</i> la riga. Al reset {@code agnus_hpos} vale 0 e
     * {@code vpos} pure, quindi il primo incremento arriva un solo CCK dopo e da lì in
     * poi ogni riga ne vale 227. Misurato con {@code WINUAE_VPOSLOG}, che stampa
     * {@code cck=1 vpos=1}, {@code cck=228 vpos=2}, {@code cck=455 vpos=3}, …
     *
     * <p>Da noi il vpos avanza al wrap di hpos, quindi la stessa cronologia si ottiene
     * partendo a 1 CCK dalla fine della riga. Senza, il nostro vpos restava indietro di
     * <b>226 CCK</b> rispetto all'oracolo per tutta la vita della macchina — e ogni
     * attesa del Kickstart agganciata al pennello cadeva altrove. Il residuo è che
     * l'hpos resta sfasato di 1 CCK (il wrap dell'oracolo è a cck≡0, il nostro a
     * cck≡1): un CCK invece di 226. Vedi {@code docs/vamigats-paula-audio-plan.md} §13.9.
     */
    private static final int INITIAL_HPOS = MAXHPOS_SHORT - 2;

    /**
     * Numero massimo di system clock della riga corrente: {@link #MAXHPOS_SHORT},
     * più un CCK (2 SC) sulle righe lunghe NTSC (vedi {@link #lol}).
     */
    private int maxHpos = MAXHPOS_SHORT;

    /**
     * LOL — <i>long line</i>. Su Agnus NTSC la lunghezza della riga <b>alterna</b> fra
     * 227 e 228 CCK: è così che il frame NTSC vale in media 227,5 CCK per riga (la
     * "mezza riga" del segnale NTSC). In PAL non esiste e resta sempre {@code false}.
     *
     * <p>Oracolo WinUAE {@code custom.cpp}: {@code lol = lol ? false : true} a ogni
     * riga quando {@code !(new_beamcon0 & BEAMCON0_PAL) && !(… & BEAMCON0_LOLDIS)},
     * seguito da {@code setmaxhpos()} → {@code maxhpos = maxhpos_short + lol}. Il
     * toggle avviene a fine riga e vale per la riga SUCCESSIVA.
     *
     * <p>Senza questo, la riga NTSC era fissa a 227 CCK: il frame durava 263×227 CCK
     * invece di 262,5×227,5 e la fase fra CPU e pennello restava bloccata su un unico
     * valore per tutta la partita. Un gioco che attende il VBLANK leggendo INTREQR in
     * polling (Out of This World via WHDLoad: {@code move.w #$7FFF,INTREQ} +
     * {@code btst #5,INTREQR}) perdeva così SEMPRE la corsa contro il gestore di
     * interrupt di Exec, che azzerava il bit prima che il polling potesse vederlo →
     * schermo nero all'infinito dopo lo splash WHDLoad.
     */
    private boolean lol = false;

    protected final VideoMode mode;

    // =========================================================================
    // Registri Agnus
    // =========================================================================

    /**
     * DMACON — DMA Control Register.
     * Bit 15: SETCLR (write only)
     * Bit 14: BBUSY  — Blitter busy (read only)
     * Bit 13: BZERO  — Blitter zero flag (read only)
     * Bit 9:  MASTER — DMA master enable
     * Bit 8:  BPLEN  — Bitplane DMA enable
     * Bit 7:  COPEN  — Copper DMA enable
     * Bit 6:  BLTEN  — Blitter DMA enable
     * Bit 5:  SPREN  — Sprite DMA enable
     * Bit 4:  DSKEN  — Disk DMA enable
     * Bit 3:  AUD3EN — Audio channel 3 DMA enable
     * Bit 2:  AUD2EN
     * Bit 1:  AUD1EN
     * Bit 0:  AUD0EN
     * Bit 10: BLTPRI — Blitter nasty (non cede mai il bus alla CPU)
     */
    private int dmacon = 0;

    // Flag DMA derivati da dmacon (+ bitplaneVerticalActive per bitplaneDmaActive).
    // Ricalcolati da updateDmaFlags() ad ogni scrittura di dmacon e da
    // updateBitplaneVerticalWindow() per la finestra verticale. Sostituiscono i
    // test di bit ripetuti ad ogni ciclo nell'arbitraggio del bus (hot path);
    // valore identico al ricalcolo al volo (dipendono solo da dmacon/finestra V).
    private boolean dmaMasterEnabled    = false;
    private boolean bitplaneDmaEnabled  = false;
    private boolean bitplaneDmaActive   = false;
    private boolean spriteDmaEnabled    = false;
    private boolean diskDmaEnabled      = false;
    private boolean audioDmaEnabled     = false;
    private boolean copperDmaEnabled    = false;
    private boolean blitterDmaEnabled   = false;

    // Used by the frame presenter to avoid exposing a frame whose bitplane DMA
    // was enabled after the display window had already started.
    private boolean bitplaneDmaEnabledLateThisFrame = false;
    private boolean lastCompletedFrameHadLateBitplaneDma = false;
    private int lastCompletedFrameField = 0;

    /** REFPTR — contatore DRAM refresh (9 bit). */
    private int refptr = 0;

    /** DSKPT — Disk DMA pointer. */
    private int dskpt = 0;

    /**
     * BEAMCON0 — controllo segnali video.
     * Bit 5: VARVSYEN — PAL/NTSC select (0=NTSC, 1=PAL su ECS; su OCS ignorato).
     * Default: 0x0020 per PAL.
     */
    protected int beamcon0 = 0x0020;

    /** BEAMCON0 bit 5: video PAL. Spento ⇒ NTSC. */
    private static final int BEAMCON0_PAL_BIT = 0x0020;
    /** BEAMCON0 bit 11: LOLDIS, disabilita l'alternanza riga lunga/corta. */
    private static final int BEAMCON0_LOLDIS  = 0x0800;

    /**
     * Modo del <b>pennello</b>: NTSC se il bit PAL di BEAMCON0 è spento.
     *
     * <p>⛔ <b>Non è {@link #mode}</b>. `mode` è la macchina — il quarzo, l'ID di Agnus
     * letto da VPOSR, la scelta fatta alla costruzione; questo è quel che il pennello sta
     * facendo <i>adesso</i>, e su ECS il software lo commuta <b>a caldo</b> scrivendo
     * BEAMCON0. L'oracolo lo calcola in `init_beamcon0()` (custom.cpp) come
     * {@code isntsc = (beamcon0 & BEAMCON0_PAL) ? 0 : 1}, con la deroga
     * {@code if (!ecs_agnus) isntsc = currprefs.ntscmode} — da noi implicita, perché su OCS
     * {@link #beamcon0Writable()} è falso e il registro non cambia mai.
     *
     * <p>Cambia tre cose insieme, e vanno insieme: {@link #maxVpos} (312 ⇄ 262),
     * l'alternanza {@link #lol} della riga lunga (che esiste solo in NTSC) e la fase del
     * VSYNC. Misurato su `Agnus/Misc/ntsc4` e `ntsc5`, gli unici due del banco che spengono
     * il bit PAL.
     */
    private boolean beamNtsc;

    // =========================================================================
    // Chip ID (VPOSR bits 15-8)
    // =========================================================================

    private static final int ID_OCS_PAL  = 0x00;
    private static final int ID_OCS_NTSC = 0x10;
    // =========================================================================
    // DMA Slot (in CCK, ovvero hpos/2)
    // =========================================================================

    // NOTA STORICA: una vecchia scelta metteva il refresh a CCK 0/2/4/6 (inizio riga, pari)
    // per evitare una collisione con la fetch bitplane in overscan (con DDFSTOP grande, es.
    // $D8, l'ultima fetch del piano 0 cadeva a CCK 223 e la refresh a 223-226 la rubava →
    // bitplane traslati). vAmiga risolve mettendo UNA sola refresh a fine riga (0xE2=226),
    // OLTRE la max fetch bitplane (DDFSTOP max in lores → ultima fetch < 226), quindi nessuna
    // collisione. Le altre 3 refresh stanno a inizio riga (1,3,5), prima di DDFSTRT.
    // Posizioni DAS allineate BIT-PER-BIT a vAmiga (SequencerDas.cpp `dasDMA` + serviceDASEvent),
    // in CCK = pos.h. vAmiga mette TUTTA la DMA DAS su CCK DISPARI, con refresh anche a fine
    // riga (0xE2=226). La vecchia scelta 0/2/4/6 (refresh a inizio riga, pari) era un'euristica
    // per evitare una collisione con la fetch bitplane in overscan; sostituita dal modello
    // rigoroso, verificato dai test vAmigaTS. Refresh: 1,3,5,226.
    private static final int SLOT_REFRESH_1 = 1;
    private static final int SLOT_REFRESH_2 = 3;
    private static final int SLOT_REFRESH_3 = 5;
    private static final int SLOT_REFRESH_4 = 226; // 0xE2, fine riga (oltre la max fetch bitplane)

    // Disk DMA: 3 slot per riga (vAmiga p[0x07/09/0B] = CCK 7, 9, 11).
    private static final int SLOT_DISK_1 = 7;
    private static final int SLOT_DISK_2 = 9;
    private static final int SLOT_DISK_3 = 11;

    // Audio DMA: 1 slot per canale (vAmiga p[0x0D/0F/11/13] = CCK 13,15,17,19).
    private static final int SLOT_AUDIO_0 = 13;
    private static final int SLOT_AUDIO_1 = 15;
    private static final int SLOT_AUDIO_2 = 17;
    private static final int SLOT_AUDIO_3 = 19;

    // Sprite DMA: 2 slot per sprite (vAmiga p[0x15..0x33] dispari = CCK 21..51).
    // Sprite 0: 21,23; sprite 1: 25,27; ... sprite 7: 49,51.
    private static final int SLOT_SPRITE_0     = 21;

    /**
     * CCK di anticipo della DECISIONE di uno slot sprite rispetto al trasferimento.
     * ⛔ È l'ancora dell'oracolo, non una taratura: `generate_sprites()` è chiamata da
     * `handle_dmal()`, dove i tap degli sprite nello shift register stanno DUE posizioni
     * più avanti di quelli di disco e audio («sprites have earlier DMA decisions»).
     */
    private static final int SPRITE_DECISION_LEAD_CCK = 2;
    private static final int SLOT_SPRITE_7_END = 51;

    // =========================================================================
    // DMA sequencer table (precomputed per-line schedule, modello vAmiga)
    // =========================================================================
    //
    // Tabella che mappa ogni CCK della riga al tipo di slot DMA "fisso" (DAS =
    // Disk/Audio/Sprite + Refresh) che lo occupa. È la versione data-driven del
    // sequencer hardware di Agnus (cfr. vAmiga Sequencer::dasDMA in
    // Components/Agnus/Sequencer/SequencerDas.cpp): invece di una catena di
    // confronti `if (cck==SLOT_REFRESH_1 || cck==SLOT_REFRESH_2 ...)` per ogni
    // CCK, si fa un singolo lookup. La POSIZIONE degli slot è costante per riga
    // (refresh 0/2/4/6, disk 1/5/9, audio 15/17/19/21, sprite 23..53 dispari);
    // le condizioni dinamiche (DMACON enable, richiesta canale audio, fase/VSTOP
    // sprite) restano valutate al dispatch, quindi la tabella è BIT-NEUTRALE
    // rispetto al vecchio if-chain.
    //
    // NB: la priorità di arbitraggio NON cambia. Refresh/disk/audio restano
    // dispatchati PRIMA del bitplane; lo sprite DOPO (il bitplane vince sullo
    // sprite quando le finestre si sovrappongono in overscan). La tabella serve
    // solo a riconoscere il tipo di slot, non a riordinare l'arbitraggio.
    private static final byte DMASLOT_NONE    = 0;
    private static final byte DMASLOT_REFRESH = 1;
    private static final byte DMASLOT_DISK    = 2;
    private static final byte DMASLOT_AUDIO   = 3;
    private static final byte DMASLOT_SPRITE  = 4;

    private static final byte[] DMA_SLOT_TABLE = buildDmaSlotTable();

    private static byte[] buildDmaSlotTable() {
        // 228 posizioni: 227 CCK della riga corta più il CCK extra della riga lunga
        // NTSC (LOL), che non ospita alcuno slot DMA fisso e resta DMASLOT_NONE.
        byte[] t = new byte[228];
        t[SLOT_REFRESH_1] = DMASLOT_REFRESH;
        t[SLOT_REFRESH_2] = DMASLOT_REFRESH;
        t[SLOT_REFRESH_3] = DMASLOT_REFRESH;
        t[SLOT_REFRESH_4] = DMASLOT_REFRESH;
        t[SLOT_DISK_1] = DMASLOT_DISK;
        t[SLOT_DISK_2] = DMASLOT_DISK;
        t[SLOT_DISK_3] = DMASLOT_DISK;
        t[SLOT_AUDIO_0] = DMASLOT_AUDIO;
        t[SLOT_AUDIO_1] = DMASLOT_AUDIO;
        t[SLOT_AUDIO_2] = DMASLOT_AUDIO;
        t[SLOT_AUDIO_3] = DMASLOT_AUDIO;
        // Sprite: CCK dispari nel range [21, 51] (2 slot per sprite).
        for (int cck = SLOT_SPRITE_0; cck <= SLOT_SPRITE_7_END; cck++) {
            if ((cck & 1) != 0) t[cck] = DMASLOT_SPRITE;
        }
        return t;
    }

    // =========================================================================
    // Light pen
    // =========================================================================

    private boolean vposFrozen = false;
    private int     frozenVpos = 0;
    private int     frozenHpos = 0;

    // Latch coerente della posizione del pennello per una lettura long-word della CPU
    // dei registri VPOSR/VHPOSR ($DFF004). Sull'hardware reale le due word vengono
    // lette in due cicli bus distinti: se il pennello incrementa vpos tra i due cicli
    // (al confine di riga, es. 255→256), V8 (da VPOSR) e V7-0 (da VHPOSR) risultano
    // incoerenti e la posizione ricostruita "salta" (es. a 0). La graphics.library
    // 1.3 sfrutta proprio questa lettura long per il rilevamento PAL/NTSC ($FCB00C):
    // un salto verso il basso al confine 256 la fa concludere erroneamente NTSC.
    // Latchando la posizione all'inizio della lettura long la rendiamo coerente,
    // come avviene di fatto sull'hardware reale nella stragrande maggioranza dei casi.
    private int     beamReadLatchVpos = -1;   // -1 = inattivo
    private int     beamReadLatchHpos = -1;

    // ERSY-freeze (BPLCON0 bit 1 = external resync). Sull'hardware, quando ERSY è
    // attivo e NON c'è un genlock a fornire il sync esterno, i contatori del pennello
    // di Agnus si fermano (WinUAE: issyncstopped()/setsyncstoppos() forza hpos=0).
    // La graphics.library 1.3 sfrutta questo per il rilevamento genlock a $FCAF96:
    // scrive BPLCON0=$0102 (ERSY), poi legge VHPOSR due volte; se HP è cambiato →
    // "genlock presente" e imposta GfxBase.system_bplcon0=$0102 (OR-ato in OGNI
    // BPLCON0 di ogni copper list). Senza modellare il freeze, il pennello avanza,
    // i due HP differiscono e ogni display eredita ERSY+GAUD ($0102) — LED Storm va
    // in schermo nero perché il suo copper riscrive $a302 (ERSY) ogni frame.
    // Non abbiamo mai un genlock, quindi ERSY implica sempre sync-stopped.
    // ⛔ Qui stava un LATCH — la posizione campionata al primo accesso sotto ERSY — ed era
    // un'approssimazione: bastava a far coincidere i due HP del rilevamento genlock, ma il
    // valore era sbagliato. L'oracolo azzera `agnus_hpos` a ogni cck e restituisce QUELLO,
    // cioè HP = 0 fisso, e `ersy2` lo misura direttamente ($4000 ripetuto). Il latch non
    // serve più: il vpos è già fermo perché `updateBeam` non lo incrementa.

    /**
     * {@code syncs_stopped} dell'oracolo: il freeze è <b>in corso</b>.
     *
     * <p>⛔ Non coincide con «ERSY è alto». Entra al primo confine di riga con ERSY alto
     * (vedi {@code updateBeam}), esce <b>subito</b> quando BPLCON0 abbassa il bit (vedi
     * {@code setBPLCON0}) — la stessa asimmetria di {@code custom.cpp}, dove
     * {@code checksyncstopped()} in configurazione cycle-exact sa solo spegnere.
     */
    private boolean syncsStopped = false;

    // =========================================================================
    // Display Window e DMA Fetch
    // =========================================================================

    /**
     * DIWSTRT — Display Window Start.
     * Bit 15-8: linea di inizio (V7-V0).
     * Bit 7-0:  colonna di inizio (H7-H0), in pixel lores.
     */
    private int diwStrt = 0;

    /**
     * DIWSTOP — Display Window Stop.
     * Bit 15-8: linea di fine (V7-V0).
     * Bit 7-0:  colonna di fine (H7-H0), in pixel lores.
     */
    private int diwStop = 0;

    private int diwVStart = 0, diwVStop = 0;
    private boolean bitplaneVerticalActive = false;

    /**
     * DDFSTRT/DDFSTOP — Data Fetch Start/Stop.
     * Definiscono la finestra orizzontale del fetch DMA dei bitplane.
     * In CCK (hpos/2). Solo i bit 7-2 sono significativi (allineamento a 4).
     */
    private int ddfStrt = 0, ddfStop = 0;
    private int ddfCckStart = 0, ddfCckStop = 0;

    private int bplcon1 = 0;
    private int scrollOdd = 0;
    private int scrollEven = 0;

    /** Puntatori bitplane (registri BPLxPT, scritti dalla CPU). */
    private final int[] bplPt          = new int[6];

    /** Puntatori bitplane correnti durante il DMA (aggiornati ad ogni fetch). */
    private final int[] bplPtrInternal = new int[6];

    /** Puntatori sprite (registri SPRxPT). */
    private final int[] sprPt          = new int[8];

    /** Puntatori sprite correnti durante il DMA. */
    private final int[] sprPtrInternal = new int[8];

    /**
     * Latch della decisione presa al <b>primo</b> dei due slot DMA di uno sprite: fa fetch
     * o no ({@link #sprSlotFetch}) e con quale fase ({@link #sprSlotDataPhase}).
     *
     * <p>⛔ <b>Anche il fetch sprite è un SEQUENCER, non un predicato vivo.</b> L'oracolo
     * (`custom.cpp`, `generate_sprites()`) tiene per ogni sprite un contatore {@code dmacycle}
     * che vale 1 al primo slot e 2 al secondo, e lo <b>arma solo allo slot 0</b>:
     * <pre>
     *   if (slot == 0) {
     *       if (!s->dmacycle &amp;&amp; s->dmastate)              s->dmacycle = 1;
     *       if (vpos == s->vstart) { s->dmastate = 1; s->dmacycle = 1; }
     *       if (vpos == s->vstop)  { s->dmastate = 0; s->dmacycle = 1; }
     *   }
     *   if (dmaen(DMA_SPRITE) &amp;&amp; s->dmacycle) { … reg = 0x140 + slot + num*8 + (dmastate ? 4 : 0); }
     * </pre>
     * ⇒ fra i due slot la decisione <b>non si rivaluta</b>: una scrittura a SPRxPOS/SPRxCTL
     * che cade in mezzo cambia {@code vstart}/{@code vstop} ma non ciò che il secondo slot
     * preleva. Da noi {@code spriteNeedsFetch()} era un predicato letto a ogni slot, e il
     * secondo vedeva già il valore nuovo.
     *
     * <p>È la <b>terza</b> volta che questo progetto trova la stessa forma, dopo il fetch
     * bitplane e la decisione del DMA sprite due CCK prima dello slot: dove l'oracolo tiene
     * uno stato, una regola ricalcolata dà la stessa risposta finché nessuno scrive nel
     * mezzo — ed è esattamente quel che i test `spritedma/interfere*` fanno.
     *
     * <p>⚠️ <b>Portata la metà che la misura sostiene</b>: il latch fra i due slot. Il
     * contatore {@code dmacycle} per intero — che fa dormire uno sprite inattivo invece di
     * rileggerne il descrittore a ogni riga — è la seconda metà e vuole una misura propria.
     */
    private final boolean[][] sprSlotFetch = new boolean[8][2];
    /**
     * {@code dmacycle} dell'oracolo: 0 = questo sprite non usa i suoi slot su questa riga,
     * 1 = primo slot, 2 = secondo. È la <b>seconda metà</b> del sequencer, quella che fa
     * <b>dormire</b> uno sprite inattivo: {@code if (!dmacycle && dmastate) dmacycle = 1}
     * non riarma chi ha {@code dmastate == 0}, quindi dopo il fetch POS/CTL della riga di
     * VSTOP lo sprite <b>non fa più DMA</b> finché {@code vpos == vstart}. Prima
     * rileggevamo il descrittore a ogni riga.
     */
    private final int[] sprDmaCycle = new int[8];
    /** Fase latchata alla DECISIONE: {@code true} = DATA/DATB, {@code false} = POS/CTL. */
    private final boolean[][] sprSlotDataPhase = new boolean[8][2];

    /**
     * DATA/DATB phase flag per ciascuno sprite.
     *
     * true  = fase DATA/DATB — fetch dei pixel tra VSTART e VSTOP (esclusa).
     * false = fase POS/CTL   — fetch del descrittore; avviene alla riga VSTOP.
     *
     * Segue la logica di vAmiga: alla riga SPRITE_RESET_LINE (25 PAL) tutti i
     * sprVStop vengono resettati a 25, forzando il primo fetch POS/CTL del frame.
     */
    private final boolean[] sprDmaEnabled = new boolean[8];

    /** Riga PAL in cui il DMA sprite viene resettato per il nuovo frame (vAmiga: 25). */
    private static final int SPRITE_RESET_LINE = 25;

    /**
     * VSTART decoded per ciascuno sprite (9 bit).
     * Bit 8 proviene da SPRxCTL bit 2; bit 7-0 da SPRxPOS bit 15-8.
     */
    private final int[] sprVStart = new int[8];

    /**
     * VSTOP decoded per ciascuno sprite (9 bit).
     * Bit 8 proviene da SPRxCTL bit 1; bit 7-0 da SPRxCTL bit 15-8.
     */
    private final int[] sprVStop  = new int[8];

    /**
     * BPL1MOD — modulo per bitplane dispari (BPL1, BPL3, BPL5).
     * Valore con segno (aggiunto al puntatore a fine riga).
     */
    private int bpl1mod = 0;

    /**
     * BPL2MOD — modulo per bitplane pari (BPL2, BPL4, BPL6).
     */
    private int bpl2mod = 0;

    /**
     * Il modulo che il write-back del fetch somma al puntatore <b>non è il registro
     * vivo</b>: è quello campionato da {@code bitplane_rga_ptmod()}, la <b>prima</b> cosa
     * di {@code do_cck()}, un CCK prima del trasferimento — e l'oracolo ne scrive la
     * ragione: <i>«Because BPL and SPR DMA is decided 1 CCK earlier than others, PT and MOD
     * need to be loaded in following cycle, cycle when request goes to DMA addressing
     * logic»</i>.
     *
     * <p>⛔ <b>Ma non è un ritardo: è un ORDINE dentro il CCK</b>, e da noi le due
     * scritture stanno dalla parte sbagliata. Nell'oracolo la sequenza di un CCK è
     * <pre>CPU(X) → ptmod(X) → rga_out(X) → Copper(X) → CPU(X+1) → ptmod(X+1) → …</pre>
     * cioè, allo stesso CCK, <b>la scrittura della CPU precede il campionamento e quella
     * del Copper lo segue</b>; da noi cadono entrambe dopo il fetch e nell'ordine inverso
     * (prima il Copper in {@code copper.fallingEdge()}, poi la CPU in
     * {@code handleCpuDataTransferPhase()}). Un solo punto di campionamento non può quindi
     * riprodurle entrambe — misurato: qualunque ritardo uniforme rende verdi
     * {@code bplmod5/6} (il Copper esce dalla finestra) e rossi {@code bplmod2/3/4} (la CPU
     * ne esce con lui).
     *
     * <p>La pipe riproduce l'ordine invece del ritardo: <b>due stadi campionati in cima al
     * CCK</b> — che tengono fuori la scrittura del Copper del CCK precedente, esattamente
     * come {@code rga_out} sta dopo {@code ptmod} — più l'<b>iniezione diretta</b> della
     * scrittura della CPU nello stadio d'ingresso, che è ciò che la fa entrare in tempo per
     * il fetch del CCK successivo. L'uscita {@code bplXmodFetch} è la sola letta da
     * {@link #handleBitplaneDMA}; {@code bplXmod} resta il registro vivo, quello che si
     * rilegge e che il savestate porta.
     *
     * <p>⚠️ Misurato sui due lati con {@code WINUAE_BPLPTLOG} / {@code BplModProbe},
     * ancorando sul puntatore (stesso valore ⇒ stesso fetch): la scrittura cade a
     * {@code trasferimento − 1} su entrambi i lati, sia dal Copper sia dalla CPU. La
     * divergenza era tutta nell'ordine dentro il CCK.
     */
    private int bpl1modFetch = 0, bpl2modFetch = 0;
    private int bpl1modStage = 0, bpl2modStage = 0;

    /**
     * Contatore fetch DMA dei bitplane nella riga corrente.
     * Tiene traccia di quanti fetch sono stati fatti per determinare
     * quale bitplane fetchare al prossimo slot.
     */
    /**
     * BPU <b>grezzo</b> campionato all'apertura della finestra di fetch
     * (CCK == ddfCckStart) e usato per tutta la riga. Evita che un cambio di BPU a metà
     * riga avvii un fetch parziale che disallinea i piani. Vedi "2. Bitplane DMA".
     *
     * <p>⚠️ Grezzo: quanti piani il DMA prelevi davvero lo decide
     * {@link #bitplaneDmaPlaneLimit(int, boolean)} al momento dell'uso, perché dipende
     * anche dalla risoluzione — che può cambiare dentro la riga.
     */
    private int fetchUnitBpu = 0;

    /**
     * Risoluzione LATCHATA che il sequencer usa per maschera e sequenza di fetch. Segue
     * esattamente { #fetchUnitBpu}: vengono dalla stessa scrittura di BPLCON0 e dalla
     * stessa pipeline dell'oracolo ({ pipelined_custom_write(BPLCON0_delayed, v, 2)}
     * -> { setup_fmodes()}).
     */
    private boolean fetchUnitHires = false;

    /**
     * ddfCckStart CAMPIONATO all'apertura della finestra di fetch (al primo CCK in
     * cui CCK == ddfCckStart vivo) e usato — per la fase di fetch e per i confini
     * della finestra — per tutta la riga. Sull'hardware reale la sequenza di fetch
     * dei bitplane è "armata" al primo match di DDFSTRT: un cambio di DDFSTRT a metà
     * riga (split-screen come la barra di stato di Last Battle) NON deve riposizionare
     * la fase del fetch sulla riga in corso. Senza questo latch, calcolare la fase
     * come (CCK - ddfCckStart) col registro VIVO produceva una discontinuità di fase
     * quando il Copper riscriveva DDFSTRT a metà riga ($34→$38): la fase saltava di 4,
     * inserendo/eliminando un fetch bitplane a seconda dell'esatto CCK della MOVE del
     * Copper (che oscilla di ±2 CCK fra frame per la contesa DMA degli sprite). Il
     * puntatore bitplane derivava di ±2 byte a frame alterni → la barra di stato
     * "sfarfallava" e i piani pari/dispari si disallineavano (sfondo a scacchiera
     * multicolore invece che uniforme). Vedi "2. Bitplane DMA".
     */
    private int fetchUnitDdfStart = 0;

    /** true dopo l'apertura della finestra di fetch nella riga corrente (reset a fine riga). */
    private boolean fetchWindowOpened = false;

    /**
     * CCK (della riga corrente) da cui un BPLCON0 scritto a finestra aperta diventa
     * efficace sul fetch: write+4 (vedi setBPLCON0). −1 = nessun re-latch pendente.
     * Scartato a fine riga (la nuova riga ricampiona BPU all'apertura finestra).
     */
    private int bpuRelatchCck = -1;

    /** true se il bus di sistema in questo CCK è occupato da un chip custom. */
    private boolean busTakenByCustom = false;

    /**
     * True se il bus chip in questo CCK è preso da un DMA a priorità superiore al
     * blitter (refresh/disk/audio/bitplane/sprite/copper). Usato dal null-blit del
     * Blitter (state INTERNAL): le micro-op BUSIDLE di vAmiga avanzano solo a bus
     * libero (`busidle && !busIsFree<BLITTER> → return`), così la contention allunga
     * la durata di BBUSY (è la dinamica testata da bbusy). Il blitter non è esso
     * stesso un "custom" qui: durante INTERNAL non richiede il bus, quindi questo
     * flag riflette solo i DMA a priorità maggiore.
     */
    public boolean isChipBusTakenThisCck() { return busTakenByCustom; }

    // =========================================================================
    // Costruttore
    // =========================================================================

    protected AbstractAgnus(Motorola68000 cpu,
                 Gary gary,
                 RAM chipRAM,
                 RAM slowRAM,
                 ROM rom,
                 Paula paula,
                 AbstractDenise denise,
                 MOS8520 ciaA,
                 MOS8520 ciaB,
                 VideoMode mode) {
        Arrays.fill(busOwners, BusOwner.NONE.ordinal());
        this.cpu       = cpu;
        // Banchi chip-timed (oracolo WinUAE fill_ce_banks, A500): chip RAM+mirror
        // $000000-$1FFFFF, slow RAM $C00000-$C7FFFF (se presente), $D00000-$DFFFFF
        // (custom+mirror+dummy). Usato da TAS per il write-drop del ciclo RMW.
        cpu.setChipBusTargetClassifier(a ->
                a < 0x200000
                || (slowRAM != null && a >= 0xC00000 && a < 0xC80000)
                || (a >= 0xD00000 && a < 0xE00000));
        cpu.setChipBusBusyProbe(this::isChipBusTakenThisCck);
        this.cpuBusReq  = cpu.getBusReq();
        this.cpuBusResp = cpu.getBusResp();
        this.gary      = gary;
        this.chipRAM   = chipRAM;
        // size() è sempre una potenza di 2 (256/512/1024/2048 KB), quindi size-1 è una
        // maschera di wrap valida; coerente con il mirror applicato da Gary al 68000.
        this.chipMask  = chipRAM.size() - 1;
        // Bit alti del puntatore (16-20) realmente presenti nella Chip RAM installata.
        this.dmaPtrHiMask = chipMask & 0x1F0000;
        this.slowRAM   = slowRAM;
        this.rom       = rom;
        this.mode      = mode;

        if (mode == VideoMode.NTSC) {
            this.maxVpos   = 262;
            this.beamcon0  = 0x0000;
            this.beamNtsc  = true;
        } else {
            this.maxVpos   = 312;
            this.beamcon0  = 0x0020;
            this.beamNtsc  = false;
        }

        this.copperBusReq  = new BusRequest();
        this.copperBusResp = new BusResponse();
        this.copper = new Copper(copperBusReq, copperBusResp, this);

        this.blitter = new Blitter(this);

        this.paula = paula;
        if (this.paula != null) {
            this.paula.setAgnus(this);
            this.paula.setIplChangedCallback(this::updateCpuIpl);
            this.paula.setDmaconSupplier(() -> this.dmacon);
        }

        this.denise = denise;
        if (this.denise != null) {
            this.denise.setAgnus(this);
        }

        this.ciaA = ciaA;
        this.ciaB = ciaB;
        if (gary != null) {
            if (rom != null)  gary.setRomSize(rom.size());
            // La Chip RAM installata la conosce solo Agnus: è il banco che gli passa il
            // costruttore. Gary ne ha bisogno per sapere dove comincia il mirror dentro
            // il chip select $000000-$1FFFFF, quindi glielo diciamo qui insieme alla
            // Slow RAM invece di lasciarlo fare a ogni chiamante: chi costruisse la
            // macchina dimenticando setChipRamSize otterrebbe una macchina che mirrora
            // ogni 512 KB (il default di defaultA500) e un Kickstart che misura 512 KB
            // di Chip qualunque sia la configurazione.
            gary.setChipRamSize(chipRAM.size());
            gary.setSlowRamPresent(slowRAM != null);
            gary.setSlowRamSize(slowRAM != null ? slowRAM.size() : 0);
        }

        this.df0 = new FloppyDrive("DF0", FloppyDrive.DriveType.DD_INT);
        this.df1 = new FloppyDrive("DF1", FloppyDrive.DriveType.DD_EXT);
        this.df2 = new FloppyDrive("DF2", FloppyDrive.DriveType.DD_EXT);
        this.df3 = new FloppyDrive("DF3", FloppyDrive.DriveType.DD_EXT);
        // DF1-DF3 non sono fisicamente collegati; rispondono sul bus come "not present"
        df1.setConnected(false);
        df2.setConnected(false);
        df3.setConnected(false);
        // Sorgente dei bit di scrittura: lo shift register di Paula.
        df0.setWriteBitSource(this::nextDiskWriteBit);
        df1.setWriteBitSource(this::nextDiskWriteBit);
        df2.setWriteBitSource(this::nextDiskWriteBit);
        df3.setWriteBitSource(this::nextDiskWriteBit);

        this.vpos = 0;
        this.triggerVpos = 0;
        this.hpos = INITIAL_HPOS;

        // Arma il tick E-clock delle CIA come evento (Step 1, event-driven). I test
        // non chiamano agnus.reset(): l'arm in costruzione garantisce che SLOT_CIAA
        // scatti dal primo E-clock. internalReset lo ri-arma.
        //
        // ⛔ I master-cycle dell'ancora sono una FASE, non un ritardo, e la fase la
        // decide DOVE cade la scrittura a un registro CIA. Il clock delle CIA e la fase
        // E a cui si sincronizza un accesso della CPU sono lo stesso segnale (φ2/10) —
        // è per questo che la macchina VMA esiste — ma nel modello sono due contatori
        // mod-10 separati: questo evento e Motorola68000.eClockCounter.
        //
        // ⛔ Il 2026-08-24 l'ancora è passata da +9 a +5, INSIEME alla correzione che
        // sposta il trasferimento del dato di una scrittura CIA da S2 alla fine del
        // ciclo sincrono (vedi maybeTransferFastBankWrite). Le due cose sono una sola:
        // finché la scrittura cadeva a S2 arrivava alla CIA a una fase QUALSIASI (fino
        // a 9 master-cycle prima del punto giusto) e il +9 era la fase che compensava
        // meglio quel disallineamento. Con la scrittura al posto suo la compensazione
        // non serve più ed è diventata l'errore: misurata sui 25 test di classe 1 di
        // vAmigaTS CIA/CIA, l'ancora ha un minimo netto a 5÷6 (7 rossi) contro 16 a 9 e
        // 14 a 3/4/7 — un altopiano di due valori con le pareti su entrambi i lati.
        // Fra 5 e 6 il banco non discrimina (esito identico test per test): si prende 5
        // perché mette la scrittura a metà esatta fra due tick, cioè il punto di
        // massimo margine. Vedi docs/vamigats-import-order.md §Esiti CIA/CIA classe 1.
        //
        // ⚠️ Non tocca il ciclo sincrono della CPU: Memory/Ram/memtim1, che misura
        // proprio la temporizzazione degli accessi CIA, resta bit-exact.
        //
        // Il valore iniziale di eClockCounter (9) resta quello del 2026-08-22: serve a
        // far durare 16 master-cycle il ciclo sincrono e non c'entra con questa fase.
        // Il perché sta nel javadoc di Motorola68000.eClockCounter e in
        // docs/vamigats-import-order.md §Esiti memtim1.
        //
        // L'invariante è congelata da CiaEClockPhaseTest.
        scheduleAbs(EventSlot.SLOT_CIAA, masterClock + CIA_ANCHOR, EventID.CIA_EXECUTE);

        // Arma la produzione dei sample audio come evento (Step 2). L'evento
        // SLOT_CH0/AUDIO_MIX si auto-rischedula da solo; questo è solo il primo arm
        // (come per SLOT_CIAA). internalReset lo ri-arma dopo aver svuotato lo
        // scheduler.
        if (this.paula != null) {
            scheduleAbs(EventSlot.SLOT_CH0, masterClock + audioSampleInterval(), EventID.AUDIO_MIX);
        }
    }

    /**
     * Intervallo in master-cycle fra due sample di output audio: PAL_SYSTEM_CLOCK /
     * OUTPUT_RATE ≈ 161. Usato solo per il primo arm dell'evento SLOT_CH0; gli arm
     * successivi sono calcolati in modo bit-esatto da Paula (accumulatore di fase).
     */
    private static long audioSampleInterval() {
        return Math.max(1, 7_093_790L / io.github.zolfrik.amija.custom.audio.JavaSoundAudioOutput.OUTPUT_RATE);
    }

    /**
     * Collega un'espansione Fast RAM Zorro II (AutoConfig). Da chiamare subito dopo
     * il costruttore. Se la scheda è presente, abilita in Gary la decodifica dello
     * spazio di AutoConfig ($E80000) così che il Kickstart possa rilevarla e
     * assegnarle un indirizzo base. Passare {@code null} (o una scheda vuota)
     * lascia la macchina nella configurazione A500 base senza Fast RAM.
     */
    public void setFastRam(FastRam fastRam) {
        this.fastRam = (fastRam != null && fastRam.present()) ? fastRam : null;
        if (gary != null) {
            gary.unmapFastRam();
            updateAutoconfigPresence();
        }
    }

    /**
     * Collega uno o più controller hard disk (schede Zorro II con AutoConfig). Da
     * chiamare subito dopo il costruttore. Ogni controller "plugged in" partecipa
     * alla catena di AutoConfig dopo la Fast RAM e, una volta configurato dal
     * Kickstart, espone la propria boot ROM in una pagina da 64 KB.
     */
    public void setHdControllers(java.util.List<HdController> controllers) {
        hdControllers.clear();
        if (controllers != null) {
            for (HdController c : controllers) {
                if (c != null && c.pluggedIn()) {
                    c.setBus(hdBus);
                    hdControllers.add(c);
                }
            }
        }
        if (gary != null) {
            gary.unmapHdControllers();
            updateAutoconfigPresence();
        }
    }

    /** Abilita lo spazio di AutoConfig se è presente almeno una scheda (Fast RAM o HD). */
    private void updateAutoconfigPresence() {
        if (gary != null) {
            gary.setAutoconfigSpacePresent(fastRam != null || !hdControllers.isEmpty());
        }
    }

    /**
     * Prima scheda della catena di AutoConfig ancora in attesa di configurazione
     * (Fast RAM, poi i controller hard disk in ordine). {@code null} se nessuna
     * scheda risponde più nello spazio $E8xxxx.
     */
    private AutoConfigDevice currentAutoConfigDevice() {
        if (fastRam != null && fastRam.autoconfiguring()) return fastRam;
        for (HdController c : hdControllers) {
            if (c.autoconfiguring()) return c;
        }
        return null;
    }

    /**
     * Le schede AutoConfig di questa macchina, nell'ordine della catena: prima la Fast
     * RAM (se c'è), poi i controller hard disk. Serve al save state, che deve
     * fotografarne lo stato di configurazione — {@code state} e {@code baseAddr} — e non
     * può arrivarci da solo: il walker riflessivo non segue riferimenti a oggetto.
     */
    public java.util.List<Object> autoConfigDevicesForState() {
        java.util.List<Object> out = new java.util.ArrayList<>();
        if (fastRam != null) out.add(fastRam);
        out.addAll(hdControllers);
        return out;
    }

    /**
     * Ricostruisce le Region di Gary per le schede AutoConfig a partire dallo stato
     * (già ripristinato) delle schede stesse.
     *
     * <p>⛔ Serve al load di uno save state. La mappa di memoria di Gary è una
     * {@code List<Region>}, cioè un riferimento a oggetto, e lo snapshot per costruzione
     * non ci entra: senza questo passo uno stato salvato DOPO l'AutoConfig si
     * ripristinava in una macchina che ha la Fast RAM configurata a $200000 ma un Gary
     * che a $200000 non ha nulla — quegli indirizzi rispondevano open bus, con il
     * Kickstart convinto del contrario. Il fingerprint della configurazione non poteva
     * intercettarlo: due macchine con la stessa {@code amiga.properties} differiscono
     * comunque per <i>quando</i> si è salvato.
     */
    public void rebuildAutoconfigMapping() {
        if (gary == null) return;
        gary.unmapFastRam();
        gary.unmapHdControllers();
        if (fastRam != null && fastRam.configured()) {
            gary.mapFastRam(fastRam.baseAddr(), fastRam.sizeBytes());
        }
        for (HdController c : hdControllers) {
            if (!c.autoconfiguring()) gary.mapHdController(c.configuredBaseAddr());
        }
        updateAutoconfigPresence();
        cpuRouteCacheValid = false; // la mappa di memoria è cambiata
    }

    /** Mappa una scheda appena configurata all'indirizzo base che ha ricevuto. */
    private void mapConfiguredDevice(AutoConfigDevice dev) {
        if (dev == fastRam) {
            gary.mapFastRam(fastRam.baseAddr(), fastRam.sizeBytes());
        } else if (dev instanceof HdController hd) {
            gary.mapHdController(hd.configuredBaseAddr());
        }
        cpuRouteCacheValid = false; // la mappa di memoria è cambiata
    }

    /** Controller hard disk la cui pagina contiene {@code addr} (64 KB allineata), o null. */
    private HdController hdControllerAt(int addr) {
        int base = addr & 0xFF0000;
        for (HdController c : hdControllers) {
            if (!c.autoconfiguring() && c.configuredBaseAddr() == base) return c;
        }
        return null;
    }

    /** Lettura byte dalla RAM Amiga (chip/slow/fast) per i controller hard disk. */
    private int hdReadByte(int addr) {
        addr &= 0xFFFFFF;
        if (addr < 0x200000) {
            return chipRAM.readByte(addr & (chipRAM.size() - 1));
        }
        if (fastRam != null && fastRam.configured()) {
            int fb = fastRam.baseAddr();
            if (addr >= fb && addr < fb + fastRam.sizeBytes()) {
                return fastRam.ram().readByte(addr - fb);
            }
        }
        if (slowRAM != null && addr >= 0xC00000 && addr < 0xC00000 + slowRAM.size()) {
            return slowRAM.readByte(addr - 0xC00000);
        }
        return 0;
    }

    /** Scrittura byte nella RAM Amiga (chip/slow/fast) per i controller hard disk. */
    private void hdWriteByte(int addr, int value) {
        addr &= 0xFFFFFF;
        if (addr < 0x200000) {
            chipRAM.writeByte(addr & (chipRAM.size() - 1), value);
            return;
        }
        if (fastRam != null && fastRam.configured()) {
            int fb = fastRam.baseAddr();
            if (addr >= fb && addr < fb + fastRam.sizeBytes()) {
                fastRam.ram().writeByte(addr - fb, value);
                return;
            }
        }
        if (slowRAM != null && addr >= 0xC00000 && addr < 0xC00000 + slowRAM.size()) {
            slowRAM.writeByte(addr - 0xC00000, value);
        }
    }

    /**
     * Trasferimento in blocco {@code src→RAM} per il controller hard disk. Quando l'intero
     * range ricade in un singolo banco senza wrap usa {@code System.arraycopy} (come il
     * {@code mem.patch} di vAmiga); altrimenti ripiega sulla copia byte-per-byte, che gestisce
     * il wrap della Chip RAM e gli indirizzi non mappati esattamente come {@link #hdWriteByte}.
     */
    private void hdWriteBlock(int addr, byte[] src, int srcOff, int len) {
        addr &= 0xFFFFFF;
        if (addr < 0x200000) {
            int local = addr & (chipRAM.size() - 1);
            if (local + len <= chipRAM.size()) { chipRAM.load(local, src, srcOff, len); return; }
        } else if (fastRam != null && fastRam.configured()) {
            int fb = fastRam.baseAddr();
            if (addr >= fb && addr + len <= fb + fastRam.sizeBytes()) {
                fastRam.ram().load(addr - fb, src, srcOff, len); return;
            }
        } else if (slowRAM != null && addr >= 0xC00000 && addr + len <= 0xC00000 + slowRAM.size()) {
            slowRAM.load(addr - 0xC00000, src, srcOff, len); return;
        }
        for (int i = 0; i < len; i++) hdWriteByte(addr + i, src[srcOff + i] & 0xFF);
    }

    /** Trasferimento in blocco {@code RAM→dst} per il controller hard disk (vedi {@link #hdWriteBlock}). */
    private void hdReadBlock(int addr, byte[] dst, int dstOff, int len) {
        addr &= 0xFFFFFF;
        if (addr < 0x200000) {
            int local = addr & (chipRAM.size() - 1);
            if (local + len <= chipRAM.size()) { chipRAM.copyTo(local, dst, dstOff, len); return; }
        } else if (fastRam != null && fastRam.configured()) {
            int fb = fastRam.baseAddr();
            if (addr >= fb && addr + len <= fb + fastRam.sizeBytes()) {
                fastRam.ram().copyTo(addr - fb, dst, dstOff, len); return;
            }
        } else if (slowRAM != null && addr >= 0xC00000 && addr + len <= 0xC00000 + slowRAM.size()) {
            slowRAM.copyTo(addr - 0xC00000, dst, dstOff, len); return;
        }
        for (int i = 0; i < len; i++) dst[dstOff + i] = (byte) hdReadByte(addr + i);
    }

    // =========================================================================
    // Tick principale
    // =========================================================================

    /**
     * Fronte di salita del clock di sistema.
     * Chiamato una volta per system clock (7.09 MHz PAL).
     */
    public void risingEdge() {
        masterClock++;

        if (nextTrigger <= masterClock) {
            executeEvents(masterClock);
        }


        // ── CPU RESET instruction ──────────────────────────────────────────
        if (cpuBusReq.resetLine && !lastResetSignal) {
            this.softReset();
            // Ri-asserisce SUBITO OVL/LED dopo il reset dei CIA (DDRA torna input →
            // pull-up esterno → /OVL HIGH → overlay ON). La parte OVL/LED è altrimenti
            // aggiornata a granularità E-clock in serviceCiaEvent, ma il path di reboot
            // (RESET; MOVEA.L $4,A0; JMP (A0)) rilegge il reset vector entro pochi
            // cicli e DEVE vedere la ROM rimappata a $0 — non può attendere il prossimo
            // E-clock (fino a 10 cicli dopo), altrimenti tornerebbe il guru blink.
            updateCiaOvlLed();
        }
        lastResetSignal = cpuBusReq.resetLine;

        // ── CIA timer clock ─────────────────────────────────────────────────
        // I CIA 8520 dell'Amiga sono cloccati dall'E-clock = clock di sistema / 10.
        // Ora gestito come evento auto-rischedulante su SLOT_CIAA (serviceCiaEvent),
        // schedulato in costruzione e ri-armato a fine internalReset. Il vecchio
        // accumulatore ciaTimerPhase (polling incondizionato) è stato rimosso:
        // l'evento scatta agli stessi master-cycle (multipli di 10) a costo zero
        // nei cicli intermedi (piano event-driven-agnus.md, Step 1).

        // ── Floppy ──────────────────────────────────────────────────────────
        // PERF: i 4 drive venivano tick-ati e update-ati ad OGNI master-cycle
        // (28M/s) anche da fermi. Ora si lavora solo quando qualcosa può cambiare:
        // o un drive è attivo (motore/seek/selezione) oppure i segnali di controllo
        // CIA-B (PRB) sono variati. A regime (in gioco, drive fermi) il costo → ~0.
        // Lettura del PRB priva di effetti collaterali.
        byte ciab_prb = ciaB != null ? ciaB.peekPortB() : 0;
        boolean prbChanged = (ciab_prb & 0xFF) != lastCiabPrb;
        boolean floppyRun = floppyBusy || prbChanged;

        // PERF: il floppy veniva tick-ato e aggiornato ad OGNI master-cycle (~7M/s)
        // mentre il motore gira — il profiler (AmigaProfiler/CORE_HEADLESS) mostra
        // questo blocco come ~24% del tempo core. Il disco emette però un solo bit
        // ogni ~14 cicli e il DMA disco gira sui CCK pari, quindi è sufficiente
        // processarlo a granularità CCK (ogni 2 master-cycle) con tick(n). Per non
        // perdere i fronti dei segnali di controllo (/STEP //SEL //SIDE) si forza
        // l'elaborazione immediata quando il PRB di CIA-B cambia: ogni transizione
        // è quindi colta nel ciclo esatto (preserva il fix side-select di SpeedBall II).
        boolean processFloppy = false;
        int floppyN = 0;
        if (floppyRun) {
            floppyAccumCycles++;
            if (prbChanged || floppyAccumCycles >= 2) {
                processFloppy = true;
                floppyN = floppyAccumCycles;
                floppyAccumCycles = 0;
                tickFloppyDrive(df0, floppyN);
                tickFloppyDrive(df1, floppyN);
                tickFloppyDrive(df2, floppyN);
                tickFloppyDrive(df3, floppyN);
            }
        }

        // ── Shifter di lettura A RIPOSO ─────────────────────────────────────
        // ⛔ Quando NESSUN drive connesso e' selezionato, la linea dati del bus floppy resta
        // al pull-up (sull'A500 cs_floppydatapullup = 0, cioe' ZERI) ma il registro a
        // scorrimento di Paula CONTINUA a scorrere e il comparatore continua a confrontare.
        // E' disk_doupdate_read_reallynothing dell'oracolo, il ramo
        // `(selected | disabled) == 15 && dskdmaen < DSKDMA_WRITE`: durante il boot di
        // Kickstart 1.3 e' da li' che nascono SETTE DSKSYN, tutti con la firma
        // `word=0000 dsksync=0000` — possibili solo perche' DSKSYNC al reset vale ZERO
        // (vedi Paula.dsksync). Un drive DISCONNESSO non conta, come il `disabled`
        // dell'oracolo. Vedi docs/vamigats-paula-audio-plan.md §7.1.24.
        if (++idleDiskBitAccum >= IDLE_DISK_CYCLES_PER_BIT) {
            idleDiskBitAccum = 0;
            if (paula != null && !isAnyDriveSelected(ciab_prb)) {
                paula.shiftInDiskBitIdle(0);
            }
        }

        // ── Paula tick (solo period-counter dei canali audio) ───────────────
        // La produzione dei sample di output (accumulatore outputSamplePhase) NON è
        // più qui: è stata spostata su un evento auto-rischedulante (SLOT_CH0 /
        // AUDIO_MIX, vedi paula.serviceAudioSampleEvent), eliminando una matematica
        // double eseguita incondizionatamente ad ogni master-cycle (~7.09M/s).
        // tick(1) avanza solo i period-counter dei canali audio ATTIVI (gating su
        // activeAudioMask): a regime senza audio è quasi a costo zero, ad audio attivo
        // è lavoro necessario per il timing esatto del DMA/interrupt audio (invariato).
        if (paula != null) paula.tick(1);

        // ── Aggiornamento segnali CIA / floppy / interrupt ──────────────────
        if (processFloppy) {
            lastCiabPrb = ciab_prb & 0xFF;
            updateFloppy(ciab_prb, prbChanged);
            // Ricalcola lo stato "busy" dopo aver applicato i fronti: determina se
            // serve continuare a lavorare nei prossimi cicli.
            floppyBusy = isFloppyDriveBusy(df0) || isFloppyDriveBusy(df1)
                    || isFloppyDriveBusy(df2) || isFloppyDriveBusy(df3);
        }
        // (Il fronte di discesa del TOD di CIA-A è ora in updateBeam(), accanto a quello
        // di salita: entrambi vivono sulla riga CIAA_TOD_VPOS.)
        // updateInterrupts(); // Moved to fallingEdge()

        // Reset della contention all'inizio di ogni CCK (hpos pari), così un DMA che
        // prende il CCK blocca la CPU per ENTRAMBI i system-clock del CCK (2 cicli,
        // pari). Questo è il modello cycle-exact validato contro WinUAE
        // (`dma_cycle`, attesa un color-clock alla volta): la contention è a
        // CCK pieno, non a 1 system-clock. Un reset per-master-cycle (il vecchio modello)
        // sotto-stimava la contention (1 SC) facendo flippare la fase del bus CPU e
        // pagare l'odd-wait `(hpos&1)` a ~metà accessi.
        boolean cckEdge = (hpos & 1) == 0;
        int cck = hpos >> 1;
        if (cckEdge) {
            busTakenByCustom = false;
            // La CPU ruba un ciclo al Blitter dopo aver atteso il bus abbastanza a lungo,
            // e solo con BLTPRI spento. La soglia e' dell'ORACOLO, non di vAmiga:
            // blitter.cpp, `#define BLIT_NASTY_CPU_STEAL_CYCLE_COUNT 3` e
            // `bstreq = blt_info.nasty_cnt >= BLIT_NASTY_CPU_STEAL_CYCLE_COUNT && !pri`.
            // `nasty_cnt` lo incrementa `dma_cycle()` una volta per CCK negato e lo azzera
            // quando la CPU ottiene il bus — cioe' esattamente `cpuBusWaitCck`.
            // Calcolato PRIMA dell'arbitraggio, dal conteggio accumulato nei CCK scorsi.
            bls = cpuBusWaitCck >= BLIT_NASTY_CPU_STEAL_CYCLE_COUNT;
            // Latenza di avvio BBUSY del Blitter (vAmiga BLT_STRT1/STRT2): la
            // scrittura di BLTSIZE arma il blit, ma BBUSY si attiva 2 CCK dopo.
            // Il countdown avanza qui (una volta per CCK), prima dell'arbitraggio
            // del bus blitter più sotto, così quando scade il blit può subito
            // contendere il CCK corrente.
            if (blitter != null) blitter.tickStartCountdown();
        }

        // ── DMA slot (in CCK = hpos pari) ────────────────────────────────────
        if (cckEdge) {
            BusOwner owner = BusOwner.NONE;

            // `bitplane_rga_ptmod()`: il caricamento del modulo è la PRIMA cosa del CCK,
            // prima di qualunque slot. Vedi il campo bpl1modFetch per il perché di due
            // stadi e dell'iniezione della scrittura CPU.
            bpl1modFetch = bpl1modStage;
            bpl2modFetch = bpl2modStage;
            bpl1modStage = bpl1mod;
            bpl2modStage = bpl2mod;

            handleBitplaneDrawEvent(cck);

            // ⛔ L'emissione dei pixel è INCREMENTALE e non è gated dal DMA: gli shift
            // register di Denise continuano a scorrere anche se BPLEN si spegne a metà
            // word, esattamente come il vecchio modello a blocchi aveva già scritto tutti
            // e 16 i pixel all'istante del draw. Gated qui sarebbe una differenza in più,
            // e questo rifacimento ne deve avere UNA sola. Vedi AbstractDenise.srCount.
            //
            // Questa è la PRIMA mezza-CCK; la seconda è nel fronte di discesa, subito dopo
            // il Copper, perché la risoluzione entra in Denise mezzo color clock prima del
            // resto di BPLCON0 (oracolo: expand_drga_early). Vedi emitBitplaneHalfCck().
            if (denise != null) denise.emitBitplaneHalfCck();

            // 1. Slot fissi ad alta priorità (DAS: refresh/disk/audio) — tipo di
            //    slot letto dalla tabella sequencer precomputata DMA_SLOT_TABLE.
            //    Le condizioni dinamiche (DMACON enable, richiesta canale) restano
            //    invariate, quindi il comportamento è identico al vecchio if-chain.
            switch (DMA_SLOT_TABLE[cck]) {
                case DMASLOT_REFRESH:
                    handleRefreshDMA();
                    owner = BusOwner.REFRESH;
                    busTakenByCustom = true;
                    break;
                case DMASLOT_DISK:
                    if (isDiskDMAEnabled() && paula != null && paula.isDiskDmaEnabled()) {
                        handleDiskDMA(cck);
                        owner = BusOwner.DISK;
                        busTakenByCustom = true;
                    }
                    break;
                case DMASLOT_AUDIO:
                    // Lo slot audio va rubato alla CPU SOLO quando il canale ha davvero
                    // un fetch in corso (period scaduto, dmaRequest), non ad ogni riga:
                    // su HW reale il canale preleva una word per periodo (ogni N righe),
                    // negli altri casi lo slot è libero per la CPU. Rubarlo sempre (col
                    // solo DMACON abilitato) sovra-caricava la CPU di ~4 slot/riga (firework
                    // IK+: blocco AUDIO ~5% → ~1%). FIX cycle-exact. Vedi docs/sae-dskr-sequence.md.
                    if (isAudioDMAEnabled()) {
                        int chan = (cck - SLOT_AUDIO_0) / 2;
                        if (paula != null && paula.isAudioDmaRequest(chan)) {
                            handleAudioDMA(cck);
                            owner = OWNER_VALUES[BusOwner.AUDIO0.ordinal() + chan];
                            busTakenByCustom = true;
                        }
                    }
                    break;
                default:
                    break;
            }

            // 2. Bitplane DMA — generate_bpl() dell'oracolo.
            //
            // ⛔ La finestra di fetch NON è un intervallo di posizioni: è un SEQUENCER.
            // La fase la porta `bprunCycle`, un contatore che parte quando la macchina
            // si arma e avanza a ogni CCK finché `bprun` è acceso — non `cck - DDFSTRT`.
            // I due coincidono finché la riga va dritta da DDFSTRT a DDFSTOP, e divergono
            // esattamente dove il banco `Agnus/Registers/DMACON` guarda: quando BPLEN si
            // spegne e si riaccende dentro la riga. Vedi decideBpl().
            if (!busTakenByCustom && bprun > 0) {
                // ⛔ La RISOLUZIONE che conta qui è quella LATCHATA, non quella viva.
                // Non è una scelta nostra: nell'oracolo BPLCON0 arriva ad Agnus da
                // `pipelined_custom_write(BPLCON0_delayed, v, 2)` e **solo** il gestore
                // ritardato chiama `setup_fmodes()`, che è ciò che fissa `bplcon0_res`,
                // `fetchstart_mask`, `fm_maxplane` e `bpl_sequence`. Cioè maschera e
                // sequenza del sequencer cambiano con lo stesso ritardo del BPU, e per la
                // stessa ragione: vengono dalla stessa scrittura e dalla stessa pipeline.
                //
                // ⚠️ Leggendola viva il cambio di risoluzione a metà riga entrava 4 CCK
                // troppo presto, ed è tutto il residuo di `DMACON/resmod1÷4` — che di
                // DMACON non hanno niente: scrivono `BPLCON0 $A200 → $2200`.
                boolean hires = fetchUnitHires;
                boolean lastOfBlock = (bprunCycle & FETCHUNIT_MASK) == FETCHUNIT_MASK;
                if (dmaconBplSeq) {
                    // BPU viene CAMPIONATO una sola volta per riga, all'apertura della
                    // finestra di fetch, e usato per tutta la riga.
                    //
                    // L'intro di Monkey Island accende i bitplani (BPU 0→5) a metà della
                    // riga di transizione del bordo (riga 43, a CCK 124, ben oltre DDFSTRT
                    // a CCK 56). Campionando BPU vivo ad ogni slot, quella riga prelevava
                    // ~11 word parziali: questo (a) disallineava i piani pari/dispari di
                    // 16px e (b) faceva avanzare TUTTI i puntatori di 11 word, traslando
                    // l'intera immagine per il resto del frame (testo "sdoppiato" con
                    // frange di colore).
                    // ⚠️ L'oracolo il BPU lo tiene VIVO (`bplcon0_planes_limit`,
                    // ricalcolato a ogni scrittura di BPLCON0): il latch è una nostra
                    // deroga, misurata su quel caso e tenuta separata dal porto del
                    // sequencer perché due modelli cambiati insieme non si sanno più
                    // attribuire. È il prossimo passo, non questo.
                    if (!fetchWindowOpened) {
                        fetchWindowOpened = true;
                        fetchUnitBpu = denise.getRawBpu();  // grezzo: il limite è di Agnus
                        fetchUnitHires = denise.isHires();
                        bpuRelatchCck = -1;
                    }
                    // BPLCON0 scritto a finestra aperta: BPU efficace da write+4 CCK
                    // (pipeline WinUAE; vedi setBPLCON0 per le misure [WRGA]).
                    if (bpuRelatchCck >= 0 && cck >= bpuRelatchCck) {
                        fetchUnitBpu = denise.getRawBpu();
                        fetchUnitHires = denise.isHires();
                        bpuRelatchCck = -1;
                    }
                    int plane = bplSequence(bprunCycle, hires);
                    if (plane >= 0 && plane < bitplaneDmaPlaneLimit(fetchUnitBpu, hires)) {
                        handleBitplaneDMA(cck, plane, hires);
                        owner = OWNER_VALUES[BusOwner.BPL1.ordinal() + plane];
                        busTakenByCustom = true;
                    }
                }
                bprunCycle++;
                if (lastOfBlock) {
                    // `if (ddf_stopping == 2) bpl_dma_normal_stop(); if (ddf_stopping == 1)
                    // ddf_stopping = 2;` — in quest'ordine: il blocco che chiude con
                    // ddf_stopping a 1 ne fa ancora UNO prima di fermarsi.
                    if (ddfStopping == 2) bplDmaNormalStop();
                    if (ddfStopping == 1) ddfStopping = 2;
                }
            }

            // Il DMA sprite è attivo solo nell'area [SPRITE_RESET_LINE, maxVpos):
            // durante il VBlank in alto (righe < 25 PAL) e sull'ultima riga gli sprite
            // NON fanno fetch. Senza questo gate, uno sprite il cui VSTOP è caduto su
            // una riga del VBlank in alto (es. cursore col VSTOP che "wrappa" a 3
            // perché il gioco non setta EV8 quando VSTART+altezza>255) fa un fetch
            // POS/CTL spurio prima del reset di riga 25, disallineando il puntatore
            // DMA e alternando frame puliti/corrotti (lo sprite "lampeggia" e cambia
            // forma). Cfr. WinUAE do_sprites (vpos<sprite_vblank_endline → return) e
            // vAmiga Sequencer (disable sprites se v<25 || v>=vMax).
            // ⛔ Il bit di DMA sprite che conta qui è quello DECISO DUE CCK PRIMA, non
            // quello vivo — vedi spriteDmaSeq. L'oracolo lo dichiara: in `handle_dmal()`
            // i tap degli sprite nello shift register stanno due posizioni più avanti
            // («sprites have earlier DMA decisions»), quindi `generate_sprites()` legge
            // `dmaen(DMA_SPRITE)` al CCK S−2 e il trasferimento cade al CCK S.
            // ⛔ La DECISIONE dello sprite sta DUE CCK PRIMA del suo slot, e con essa TUTTA
            // la macchina a stati: `generate_sprites()` è chiamata da `handle_dmal()`, dove
            // i tap degli sprite nello shift register stanno due posizioni più avanti
            // («sprites have earlier DMA decisions»). È la stessa ancora già misurata per il
            // bit di DMA (§12 classe D) — qui ci passa anche il confronto VSTART/VSTOP.
            // ⚠️ Si prende anche quando il ciclo non sarà allocato (bitplane o Blitter lo
            // rubano): di là `generate_sprites()` gira comunque, è solo il trasferimento a
            // essere gated da `dmaen()`. Vedi sprSlotFetch.
            final int sprDecideCck = cck + SPRITE_DECISION_LEAD_CCK;
            if (sprDecideCck < DMA_SLOT_TABLE.length
                    && DMA_SLOT_TABLE[sprDecideCck] == DMASLOT_SPRITE) {
                int latchIdx = (sprDecideCck - SLOT_SPRITE_0) / 4;
                int latchSub = ((sprDecideCck - SLOT_SPRITE_0) % 4) / 2;
                if (latchIdx >= 0 && latchIdx <= 7) {
                    if (latchSub == 0) {
                        generateSpritesSlot0(latchIdx);
                    }
                    // ⛔ E alla decisione si guarda anche il SEQUENCER BITPLANE: lo sprite
                    // rinuncia allo slot quando il fetch bitplane e' IN CORSA, e la
                    // condizione e' dell'oracolo per intero (generate_sprites):
                    //   bool bplconflict = false;
                    //   if (bprun && ddf_stopping == 2 && islastbplseq()) bplconflict = true;
                    //   if (bprun != 1 || bplconflict) dodma = true;
                    // ⚠️ Non e' «il bitplane ha preso questo CCK»: e' lo STATO di bprun DUE
                    // CCK PRIMA. E' la differenza che i test `blocked*` misurano allargando
                    // la finestra DDF sopra gli slot sprite (issue #799 di vAmiga).
                    final boolean lastBplSeq = (bprunCycle & FETCHUNIT_MASK) == FETCHUNIT_MASK;
                    final boolean bplConflict = bprun != 0 && ddfStopping == 2 && lastBplSeq;
                    sprSlotFetch[latchIdx][latchSub] =
                            sprDmaCycle[latchIdx] != 0 && (bprun != 1 || bplConflict);
                    sprSlotDataPhase[latchIdx][latchSub] = sprDmaEnabled[latchIdx];
                    // Coda di generate_sprites(), quindi alla decisione e non al trasferimento.
                    if (sprDmaCycle[latchIdx] != 0) {
                        sprDmaCycle[latchIdx]++;
                        if (sprDmaCycle[latchIdx] > 2) {
                            sprDmaCycle[latchIdx] = sprDmaEnabled[latchIdx] ? 1 : 0;
                        }
                    }
                }
            }

            if (!busTakenByCustom && spriteDmaSeq
                    && vpos >= SPRITE_RESET_LINE && vpos < maxVpos
                    && DMA_SLOT_TABLE[cck] == DMASLOT_SPRITE
                    // ⛔ ...e lo slot DECISO nel CCK subito dopo l'accensione del DMA
                    // sprite è «deciso ma NON allocato»: nessun prelievo e nessun
                    // avanzamento del puntatore. Vedi spriteDmaOnDecisionCck.
                    && (masterClock >> 1) - 2 != spriteDmaOnDecisionCck) {
                int sprIdx = (cck - SLOT_SPRITE_0) / 4;
                int sprSub = ((cck - SLOT_SPRITE_0) % 4) / 2;
                if (sprIdx >= 0 && sprIdx <= 7 && sprSlotFetch[sprIdx][sprSub]) {
                    handleSpriteDMA(cck);
                    owner = OWNER_VALUES[BusOwner.SPRITE0.ordinal() + sprIdx];
                    busTakenByCustom = true;
                }
            }

            // 3. Copper — CADENZA PARI (modello vAmiga; Fase 1 refactoring timing, C1/C2/A2).
            //    Su hardware reale il Copper opera con cadenza di 2 CCK e NON si sveglia
            //    mai su un CCK dispari: in vAmiga (CopperEvents.cpp COP_REQ_DMA/WAKEUP/
            //    FETCH) ogni stato fa `if (IS_ODD(pos.h)) reschedule()` e le transizioni
            //    sono schedulate a `delay=2` CCK. Nel nostro loop per-CCK questo si
            //    realizza tickando il Copper SOLO sui CCK pari: i CCK dispari vengono
            //    saltati (equivalente al reschedule +1 di vAmiga che ri-atterra su pari).
            //    Il vecchio commento "il Copper non ha restrizione di parità" era ERRATO.
            //
            //    Coerenza di parità nel nostro sequencer: gli slot DMA fissi disk/audio/
            //    sprite stanno sui CCK DISPARI (disk 1/5/9, audio 15/17/19/21, sprite
            //    23..53 dispari — come vAmiga DAS $07..$33) e i bitplane (≤4 piani) sui
            //    CCK dispari relativi a DDFSTRT (LORES_FETCH: slot liberi ai relCck pari
            //    0/4).
            //
            //    ⚠️ Il Copper va tickato a OGNI CCK, non solo sui pari (com'era in Fase 1):
            //    in vAmiga il vincolo di parità (`IS_ODD(pos.h) → reschedule`) esiste SOLO
            //    negli stati di wakeup (`COP_REQ_DMA`/`COP_WAKEUP`/`COP_WAKEUP_BLIT`) e in
            //    NESSUN altro; dopo un diniego del bus si ritenta a +1 CCK
            //    (`reschedule(delay=1)`), quindi anche su un CCK DISPARI. La cadenza pari
            //    dei fetch è un'emergenza di `schedule(delay=2)`, non una regola. Il gate
            //    pari faceva slittare al pari successivo il fetch rifiutato dal BLOCKED di
            //    $E0, rubando 2 CCK alla CPU (root di §S2). La parità vive ora dentro la
            //    FSM (Copper.risingEdge), che riceve `evenCck`.
            if (isCopperEnabled()) {
                // busFree = vAmiga busIsFree<COPPER>(): nessun DMA a priorità
                // superiore ha già preso lo slot e non è il ciclo $E0 (bloccato).
                // Gli stati idle della FSM Copper (REQ_DMA/WAIT*/SKIP*/JMP*)
                // avanzano solo se il bus è libero; gli stati di lettura memoria
                // (FETCH/MOVE/WAIT_OR_SKIP) assertano comunque la richiesta e la
                // concessione è decisa qui sotto.
                boolean copperBusFree = !busTakenByCustom && cck != 0xE0;
                copper.risingEdge(copperBusFree, (cck & 1) == 0);
                // Fix 1: ensure stale ACK doesn't lead to false ownership if bus is taken.
                copperBusResp.ack = false;

                // Regola $E0/$E1 (vAmiga busIsFree<COPPER>): se il Copper RICHIEDE il bus
                // nell'ultimo CCK utile della riga ($E0 = 224; su long line sarebbe $E1,
                // ma la nostra riga PAL è fissa a 227 CCK senza lol), il bus diventa
                // BLOCKED per TUTTI — né il Copper né la CPU lo ottengono. Il Copper non
                // riceve ack e ritenta al prossimo CCK pari.
                if (cck == 0xE0 && copperBusReq.as) {
                    owner            = BusOwner.BLOCKED;
                    busTakenByCustom = true;
                } else if (!busTakenByCustom) {
                    handleCopperBusCycle();
                    if (copperBusReq.as) {
                        owner = BusOwner.COPPER;
                        busTakenByCustom = true;
                    }
                }
            }

            // 4. Blitter — motore a micro-programmi single-phase (Fase 3, B1/B4).
            //    Il blitter esegue UNA micro-op per CCK (stepCck): le micro-op BUS/BUSIDLE
            //    stallano se il bus non è libero, le NOTHING (coda di drain della pipeline
            //    D) avanzano comunque. Per questo il blitter è tickato ANCHE quando il bus
            //    è già preso da un DMA a priorità superiore (busTakenByCustom): in quel caso
            //    busFree=false e solo le NOTHING progrediscono. La precedenza CPU segue il
            //    modello vAmiga `bls`: quando la CPU è in attesa del bus da ≥2 CCK, e BLTPRI
            //    è spento, il blitter cede lo slot (`busIsFree<BLITTER>` nega).
            // Clear BBUSY differito del line mode: matura ANCHE dopo endBlit e
            // deve girare PRIMA dello step del blitter, così il countdown armato
            // a LINE_LAST_BUS viene processato dal cck SUCCESSIVO in poi.
            if (blitter != null) blitter.tickBusyClear();
            if (isBlitterEnabled() && blitter.isRunning()) {
                boolean cpuWantsBus   = cpuBusReq.as && !cpuBusReq.iack;
                boolean cpuPrecedence = bls && !isBlitterNasty();
                boolean busFree       = !busTakenByCustom && !cpuPrecedence;
                if (blitter.stepCck(busFree)) {
                    owner = BusOwner.BLITTER;
                    busTakenByCustom = true;
                    if (cpuWantsBus) cpuBlockedByBlitterCount++;
                }
            }

            busOwners[cck] = owner.ordinal();

            // ⚠️ DOPO l'arbitraggio, non prima: in `do_cck()` l'ordine è
            // `generate_dma_requests()` (che contiene `generate_bpl`) e POI
            // `decide_bpl(agnus_hpos)`. Una macchina armata al CCK X preleva la prima
            // word al CCK X+1, ed è quel CCK di sfasamento a rendere giuste le finestre.
            decideBpl(cck);

            // Ricampionamento del latch BPLEN, nell'ordine dell'oracolo: DOPO decide_bpl e
            // PRIMA che la scrittura del CCK corrente arrivi al registro. Due stadi perche'
            // le nostre scritture sono immediate: vedi dmaconBplSeq.
            dmaconBplSeq = dmaconBplPipe;
            dmaconBplPipe = bitplaneDmaEnabled;

            // Stessa forma per gli SPRITE, ma la ragione è un'altra: lì è un latch
            // ricampionato, qui è la DECISIONE che sta due CCK prima del trasferimento.
            spriteDmaSeq = spriteDmaPipe;
            spriteDmaPipe = spriteDmaEnabled;
        }

        // 5. CPU — il clock del 68000 non si ferma mai.
        //
        // Su hardware reale Agnus non gate il clock della CPU: la CPU avanza
        // il proprio stato interno ad ogni ciclo. Ciò che si blocca è /DTACK:
        // se il bus è occupato dal DMA, o se l'accesso alla Chip RAM non è
        // allineato al CCK boundary, Agnus nega /DTACK e la CPU entra in
        // wait state (stati S4-S5 ripetuti internamente) senza avanzare oltre.
        //
        // handleCpuBusCycle() implementa questa logica:
        //   — bus preso dal DMA       → cpuBusResp.ack = false  (stall DMA)
        //   — hpos dispari + Chip RAM → cpuBusResp.ack = false  (CCK sync wait)
        //   — altrimenti              → DTACK garantito normalmente
        cpu.risingEdge();
        handleCpuBusCycle();

        // Registra la CPU come proprietaria del bus (solo su CCK) e azzera
        // il contatore di stallo blitter quando la CPU ottiene il bus con successo.
        if (cpuBusReq.as && cpuBusResp.ack) {
            cpuBlockedByBlitterCount = 0;
            if (cckEdge) {
                busOwners[cck] = BusOwner.CPU.ordinal();
            }
        }

        // BLS (vAmiga executeUntilBusIsFree): conteggio dei CCK in cui la CPU vuole il
        // bus ma non lo ottiene. Alimenta `bls` (calcolato a inizio del prossimo CCK).
        // Aggiornato una sola volta per CCK.
        if (cckEdge) {
            boolean cpuWants = cpuBusReq.as && !cpuBusReq.iack;
            if (cpuWants && !cpuBusResp.ack) cpuBusWaitCck++;
            else                             cpuBusWaitCck = 0;
        }
    }

    /**
     * Fronte di discesa del clock di sistema.
     *
     * IMPORTANTE: updateInterrupts() e updateCpuIpl() devono essere chiamati
     * PRIMA di cpu.fallingEdge(), perché è in fallingEdge() che la CPU
     * campiona latchedIpl = busResp.ipl. Se un CIA ha generato un IRQ nel
     * risingEdge di questo stesso ciclo (e.g. timer A underflow), il fronte
     * del pin /IRQ CIA-A potrebbe non essere stato ancora rilevato da
     * updateInterrupts() se il CIA tick è avvenuto troppo tardi nel risingEdge.
     * Chiamandolo qui prima della CPU garantiamo il campionamento corretto.
     */
    public void fallingEdge() {
        // Il fallingEdge deve restare ACCOPPIATO al risingEdge, che ticka il Copper a OGNI
        // CCK: `hpos & 1 == 0` seleziona ogni confine di CCK (cck = hpos>>1). Era
        // `hpos & 3 == 0` (= solo CCK pari) finché anche il risingEdge era gated sui pari;
        // lasciarlo a 3 ora sopprimerebbe il consumo della word sui CCK DISPARI, dove il
        // Copper può legittimamente ottenere il bus dopo un diniego (reschedule +1) — è
        // esattamente il fetch a 225 dopo il BLOCKED di $E0=224. La cadenza +2 fra fetch
        // riusciti è imposta da Copper.tickDelay, non più dal gate.
        if ((hpos & 1) == 0 && isCopperEnabled()) {
            copper.fallingEdge();
        }

        // SECONDA mezza-CCK di Denise, subito DOPO il Copper: la parte risoluzione di
        // BPLCON0 entra uno stadio RGA prima del resto (oracolo: expand_drga_early legge
        // idx0, expand_drga legge idx1), cioè mezzo color clock, ed è ciò che fa cadere la
        // commutazione di `hilo` al sub-pixel giusto. Vedi AbstractDenise.emitBitplaneHalfCck.
        if ((hpos & 1) == 0 && denise != null) denise.emitBitplaneHalfCck();
        // Il Blitter è single-phase (Fase 3): tutta la sua logica (accesso a memoria,
        // avanzamento micro-op) avviene in stepCck() nel risingEdge; nessun fallingEdge.

        // ── Aggiorna interrupt PRIMA del campionamento IPL della CPU ──────────
        // Il CIA tick è già avvenuto nel risingEdge. Se ha generato un nuovo
        // fronte /IRQ, dobbiamo propagarlo a Paula e alla CPU prima che la CPU
        // campioni latchedIpl nel suo fallingEdge().
        updateInterrupts();
        updateCpuIpl();

        // PERF (Fase 2): la seconda chiamata a handleCpuBusCycle() qui era ridondante.
        // La FSM bus del 68000 avanza un solo stato per fronte (advanceBusCycleRising
        // in cpu.risingEdge, advanceBusCycleFalling in cpu.fallingEdge); poiché
        // cpu.fallingEdge() non è ancora stato chiamato, lo stato bus è IDENTICO a
        // quello già visto dalla handleCpuBusCycle() del risingEdge di QUESTO ciclo, e
        // tutti gli input di decideCpuDtack (cpuBusReq, busTakenByCustom, hpos, route
        // in cache) sono invariati tra le due fasi — busTakenByCustom si scrive solo in
        // risingEdge, hpos avanza solo in updateBeam() (a fine fallingEdge). Inoltre
        // advanceBusCycleFalling NON legge ack/vpa/berr: quei segnali li consuma il
        // cpu.risingEdge() del ciclo SUCCESSIVO, e il loro ultimo scrittore resta la
        // handleCpuBusCycle() del risingEdge corrente con valore identico. Rimuoverla è
        // bit-exact (verificato: golden CRC 832316F5 + IkPlus/NorthSouth/LastBattle +
        // BbusyTest tutti INVARIATI rispetto al baseline) e dimezza le invocazioni del
        // path bus CPU (~7M/s in meno).
        cpu.fallingEdge(); // campiona latchedIpl = busResp.ipl qui

        // Il trasferimento dati vive sullo slot che Agnus ha CONCESSO, non mezzo CCK dopo.
        //
        // Oracolo WinUAE `wait_cpu_cycle_write` (custom.cpp:12452): `dma_cycle()` attende lo
        // slot di chip-bus e SUBITO DOPO fa `put_word(addr, v)` — la scrittura cade sul CCK
        // concesso, e infatti ogni riga di WINUAE_COLORLOG ha `clk` PARI (multiplo di CCK)
        // sia per le scritture della CPU sia per quelle del Copper.
        //
        // Da noi il DTACK matura in decideCpuDtack() a `hpos` PARI (confine di CCK) mentre
        // la CPU e' in S4, ma il trasferimento veniva eseguito dalla handleCpuBusCycle() del
        // risingEdge SUCCESSIVO, quando lo stato e' S6 e hpos e' gia' DISPARI: mezzo CCK
        // dopo lo slot concesso. Il Copper e gli interrupt dei chip commitano invece sul
        // confine, e da qui la fase misurata da CpuBeamPhaseTest — 48 phi su 48 dispari dove
        // l'oracolo li ha tutti pari (piano vamigats-paula-audio §6.1.1).
        //
        // Chiamandolo qui, dopo cpu.fallingEdge() (che porta la FSM bus in S5) e PRIMA di
        // updateBeam(), il trasferimento cade nello stesso master-cycle del DTACK. La FSM
        // della CPU, l'arbitraggio e la contesa non si muovono di un ciclo: cambia solo
        // l'istante in cui l'effetto del trasferimento diventa visibile.
        handleCpuDataTransferPhase();

        if (!copperBusReq.as)  copperBusProcessed  = false;

        updateBeam();
    }

    private void scheduleSyncEvent(int flag) {
        syncEvent |= flag;
        scheduleNextRegEvent();
    }

    // =========================================================================
    // Impulsi TOD (tod_inc_delay)
    // =========================================================================

    /** {@code hpos} del fronte di salita/discesa dell'impulso TOD della riga; -1 = nessuno. */
    private int todRiseHposA = -1, todFallHposA = -1, todRiseHposB = -1, todFallHposB = -1;

    /**
     * {@code tod_inc_delay()} dell'oracolo ({@code cia.cpp}): dove cade l'incremento del TOD
     * <b>dentro la riga</b>, in CCK dall'inizio della riga.
     *
     * <p>⛔ <b>Non è una costante e non può esserlo</b>, ed è il banco stesso a dirlo:
     * {@code toddelay1÷3} sono lo stesso programma con la WAIT del Copper a tre hpos diversi,
     * cioè una sonda di fase, e uno sweep su tutta la riga rompe {@code toddelay2} a 96 CCK e
     * {@code toddelay3} a 114 — i due estremi dell'intervallo dell'oracolo. L'oracolo non usa
     * un valore fisso: allinea l'incremento a una griglia di <b>quattro E-clock</b>
     * ({@link #TOD_ALIGN_CCK}) ricavata dal contatore <b>assoluto</b> dei CCK, e 227 non è
     * multiplo di 20, quindi la posizione ruota di 7 CCK a ogni riga.
     *
     * <pre>
     * hoff   = hoffset + 1                    // il pin HSYNC/VSYNC di Agnus ritarda di 1 CCK
     * div10  = (cck + 3 + hoff) mod 20        // 6 * E_CYCLE_UNIT = 3 CCK
     * offset = hoff + 60 + (20 - div10) - 2
     * </pre>
     *
     * <p>Sviluppandola, l'istante assoluto dell'incremento è l'unico CCK <b>≡ 14 (mod 20)</b>
     * nella finestra {@code [A+58, A+77]} con {@code A = cckInizioRiga + hoff}: una griglia
     * rigida in tempo assoluto, di cui la posizione nella riga è solo la lettura relativa.
     *
     * @param hoffset      quello che l'oracolo passa a {@code CIAx_tod_handler}
     * @param lineStartCck il CCK a cui l'oracolo esegue {@code cia_hsync_do()}, cioè il suo
     *                     {@code agnus_hpos == 1}: da noi lo wrap di {@code hpos}
     */
    private static int todIncOffsetCck(int hoffset, long lineStartCck) {
        final int hoff = hoffset + 1;
        final int div10 = (int) Math.floorMod(lineStartCck + 3 + hoff, TOD_ALIGN_CCK);
        return hoff + TOD_INC_DELAY_CCK + (TOD_ALIGN_CCK - div10) + TOD_ECLOCK_TOD_CCK;
    }

    /**
     * Traduce l'offset dell'oracolo (un {@code agnus_hpos}, in CCK) nel nostro {@code hpos},
     * che conta system clock e va un CCK indietro: il suo wrap è a {@code cck ≡ 0}, il nostro
     * a {@code cck ≡ 1}, quindi {@code hpos = 2 * (offset - 1)}.
     *
     * @return -1 se l'impulso non ci sta nella riga. ⚠️ In PAL e in NTSC <b>non succede</b>:
     *         l'offset vale al massimo 114 CCK per CIA-B e 211 per CIA-A contro i 227 della
     *         riga. L'oracolo tratta il caso diversamente per le due CIA — CIA-B salta
     *         l'incremento ({@code delayedevent = false}), CIA-A lo rimanda alla riga dopo
     *         ({@code tod_event_state = 4}) — e qui non è modellato perché non è raggiungibile:
     *         servirebbe una riga programmata con {@code BEAMCON0}, che non emuliamo.
     */
    private int todPulseRiseHpos(int offsetCck) {
        final int rise = 2 * (offsetCck - 1);
        return (rise < 1 || rise + TOD_PULSE_WIDTH_SC >= maxHpos) ? -1 : rise;
    }

    /**
     * Aggancio del <b>vsync</b> a inizio riga: alza e abbassa {@link #vsyncActive} come
     * {@code agnus_vsync} dell'oracolo e, sul fronte di salita, latcha
     * {@link #displayRowShift} per tutto il fotogramma.
     *
     * <p>⛔ Il latch è il punto: {@code vhpos4} scrive VPOSW <b>a fotogramma già
     * cominciato</b>, e se ricalcolassimo lo scorrimento riga per riga il quadro si
     * spezzerebbe a metà. In {@code custom.cpp} {@code vsync_mark()} azzera
     * {@code linear_vpos} una volta sola, quando {@code agnus_vsync} passa da falso a
     * vero, e da lì in poi il fotogramma è ancorato lì qualunque cosa faccia
     * {@code lof_store}.
     */
    private void updateVsyncPhase() {
        final boolean pal = !beamNtsc;
        final int startVpos = pal
                ? ((field == 0) ? VSYNC_START_VPOS_PAL_LONG : VSYNC_START_VPOS_PAL_SHORT)
                : VSYNC_START_VPOS_NTSC;
        final int endVpos = pal ? VSYNC_END_VPOS_PAL : VSYNC_END_VPOS_NTSC;

        if (!vsyncActive && vpos == startVpos) {
            vsyncActive = true;
            // La riga del framebuffer conta dal vsync; le nostre costanti di crop sono
            // tarate sul fotogramma LUNGO, dove il vsync sta a VSYNC_START_VPOS_PAL_LONG.
            displayRowShift = VSYNC_START_VPOS_PAL_LONG - startVpos;
        } else if (vsyncActive && vpos == endVpos) {
            vsyncActive = false;
        }
    }

    /**
     * Di quante righe il quadro scende nel framebuffer rispetto a {@code vpos}.
     *
     * <p>Vale <b>0</b> quasi sempre e <b>+1</b> sul fotogramma corto PAL, perché lì il
     * vsync — a cui il display è agganciato — comincia una riga prima (vpos 2 invece di
     * 3): vedi {@link #VSYNC_START_VPOS_PAL_LONG}. Misurato sul banco
     * {@code Agnus/Registers/VPOS}: {@code lof1} (VPOSW $8001, frame lungo) è bit-exact
     * con {@code shift = 0}, {@code lof2} (VPOSW $0001, frame corto) lo è con
     * {@code shift = +1} — nel riferimento dell'oracolo il quadro comincia alla riga 23
     * invece che alla 22.
     */
    public int getDisplayRowShift() {
        return displayRowShift;
    }

    /** Calcola gli impulsi TOD della riga che comincia. */
    private void armTodPulses(long lineStartCck) {
        todRiseHposB = todPulseRiseHpos(todIncOffsetCck(TOD_HOFFSET_CIAB, lineStartCck));
        todFallHposB = todRiseHposB < 0 ? -1 : todRiseHposB + TOD_PULSE_WIDTH_SC;

        if (vpos == CIAA_TOD_VPOS) {
            // lof_store ? 132 : 18 — da noi LOF=1 è field==0 (vedi longFrame in updateBeam).
            final int hoffset = (field == 0) ? TOD_HOFFSET_CIAA_LONG : TOD_HOFFSET_CIAA_SHORT;
            todRiseHposA = todPulseRiseHpos(todIncOffsetCck(hoffset, lineStartCck));
            todFallHposA = todRiseHposA < 0 ? -1 : todRiseHposA + TOD_PULSE_WIDTH_SC;
        } else {
            todRiseHposA = -1;
            todFallHposA = -1;
        }
    }

    // =========================================================================
    // Aggiornamento raster
    // =========================================================================

    /**
     * Avanza la posizione del raster di 1 system clock.
     *
     * Gestisce:
     *   - Fine riga: reset hpos, moduli bitplane, incremento vpos
     *   - Fine frame: reset vpos, ricarica puntatori DMA, VERTB, COPJMP1
     *   - TOD CIA-A: impulso a inizio di ogni nuovo frame (segnale di rete 50 Hz)
     */
    private void updateBeam() {
        int nextHpos = hpos + 1;
        hpos = nextHpos;

        // ── Impulsi TOD verso le due CIA ────────────────────────────────────
        // Quale RIGA porta l'impulso lo decide la sorgente (CIA-B: ogni hsync; CIA-A:
        // la fine del vsync, riga CIAA_TOD_VPOS). DOVE cade dentro la riga lo calcola
        // todIncOffsetCck(), il tod_inc_delay() dell'oracolo: prima stava fisso a
        // inizio riga. L'arm è a nextHpos == 1, cioè un system clock dopo lo wrap,
        // che è il punto in cui l'oracolo chiama cia_hsync_do().
        if (nextHpos == 1) {
            armTodPulses((masterClock - 1) >> 1);
        }
        if (ciaB != null) {
            if (nextHpos == todRiseHposB) {
                ciaB.getPinSet().getTodPin().setExternalInput(true);
                ciaB.incrementTod();
            } else if (nextHpos == todFallHposB) {
                ciaB.getPinSet().getTodPin().setExternalInput(false);
            }
        }
        if (ciaA != null) {
            if (nextHpos == todRiseHposA) {
                ciaA.getPinSet().getTodPin().setExternalInput(true);
                ciaA.incrementTod();
            } else if (nextHpos == todFallHposA) {
                ciaA.getPinSet().getTodPin().setExternalInput(false);
            }
        }

        // ⛔ La riga dell'ORACOLO avanza TRE CCK prima del nostro wrap di hpos:
        // è dove chiama custom_trigger_start(). Vedi TRIGGER_VPOS_LEAD_HPOS.
        if (nextHpos == maxHpos - TRIGGER_VPOS_LEAD_HPOS) {
            advanceTriggerVpos();
        }

        if (nextHpos < maxHpos) {
            // Ancora nella stessa riga — nessuna azione rilevante
            return;
        }

        // ── Fine riga ──────────────────────────────────────────────────────

        // ⛔ IL DRAW BITPLANE NON SI FERMA AL CONFINE DI RIGA. La griglia degli eventi di
        // draw prosegue nella riga seguente, ma i primi sub-pixel che ne escono
        // appartengono ancora al buffer di QUESTA riga: sono le colonne raw 836÷839
        // (`cck 227`), la stessa cella su cui vive la «colonna del wrap» del §16 — che per
        // il BORDO è già modellata (`VirtualCRT.borderCck0Rgb`) e per il PLAYFIELD non lo
        // era. Con lo scroll grosso il blocco finale cade proprio lì: `BPLCON1/overscan`
        // scrive BPLCON1 ai cicli DMA alti apposta per metterlo a cavallo del confine, e
        // senza questo evento quella word non esce mai — un pixel per riga, 1 px in hires
        // e 2 in lores, su 21 righe di ognuno dei dodici test.
        // ⚠️ UN solo CCK, non di più: `rgbLine` è larga 912 = 228 CCK a partire da
        // `cck 18`, quindi `cck 227` è l'ultima cella che le appartiene.
        handleBitplaneDrawEvent(maxHpos >> 1);

        scheduleSyncEvent(EVFL_HSYNC);
        scheduleSyncEvent(EVFL_EOL);

        // ⛔ Il freeze ERSY ENTRA QUI, non alla scrittura di BPLCON0. Oracolo,
        // `custom.cpp` do_cck: il ramo che chiama `setsyncstopped()` sta dentro
        // `if (agnus_hpos == maxhpos || agnus_hpos == maxhpos_long)`, cioè al confine di
        // riga, e solo `if (!lol)`. Sulla scrittura, `checksyncstopped()` accende il freeze
        // solo nel ramo `!cpu_memory_cycle_exact || m68k_speed < 0` — che in configurazione
        // cycle-exact (la nostra, e quella dell'oracolo del banco) è falso.
        // ⚠️ Non è un dettaglio: un ERSY acceso e spento DENTRO la stessa riga non congela
        // niente. È il caso di `Agnus/Registers/VPOS/ersy1`, dove il Copper alza il bit a
        // hpos $31 e lo riabbassa a $B1 sedici volte di fila.
        if (!syncsStopped && ersyActive() && !lol) {
            syncsStopped = true;
        }

        // LOL: su Agnus NTSC la riga alterna 227/228 CCK. Il toggle vale per la riga
        // che sta per iniziare (WinUAE: toggle nell'hsync handler, poi setmaxhpos()).
        // ⛔ La condizione è quella dell'oracolo per intero:
        //   if (!(new_beamcon0 & BEAMCON0_PAL) && !(new_beamcon0 & BEAMCON0_LOLDIS))
        //       lol = !lol; else lol = false;
        // cioè NTSC *e* LOLDIS spento; altrimenti la riga torna corta e ci resta. Il modo
        // lo decide BEAMCON0, non la macchina (vedi beamNtsc).
        updateLineLength(beamNtsc && (beamcon0 & BEAMCON0_LOLDIS) == 0 && !lol);

        hpos = 0;
        fetchWindowOpened = false;   // riarma il latch della finestra di fetch per la nuova riga
        bpuRelatchCck = -1;          // un re-latch BPU mai maturato non sopravvive alla riga

        // Sblocca il latch light pen dopo la riga 20
        // (il Kickstart usa vpos >= 20 come condizione di reset del freeze)
        if (vpos == 20) vposFrozen = false;

        // Applica i moduli bitplane a fine riga, MA solo se la riga ha effettivamente
        // prelevato bitplane (finestra di fetch aperta). Una riga entro la finestra
        // verticale che NON ha fatto fetch — es. la riga di transizione in cui il Copper
        // riconfigura DDFSTRT/puntatori a metà riga, dopo che il CCK ha superato il nuovo
        // ddfCckStart, così la finestra non si apre — NON deve far avanzare i puntatori.
        // (Pinball Fantasies: a vpos 72 il Copper carica i puntatori del tavolo e sposta
        // DDFSTRT; quella riga non preleva, ma applicando comunque il modulo BPL1 finiva
        // 120 byte oltre i suoi dati, nei gap a zero → bit0 perso → solo colori a indice
        // pari → palette del tavolo "sballata".) Allineato a WinUAE/vAmiga: il modulo è
        // parte della sequenza DMA bitplane e segue gli effettivi fetch della riga.
        // I moduli bitplane NON si applicano più qui a fine riga: sono aggiunti
        // per-piano all'ULTIMO fetch della riga (handleBitplaneDMA, `domod`
        // WinUAE). Il gate lineDidFetch (Pinball Fantasies) è assorbito: una riga
        // senza fetch non raggiunge mai l'unità finale e non applica alcun modulo.
        // (L'impulso TOD di CIA-A non sta più qui: vedi CIAA_TOD_VPOS in updateBeam().)

        // ERSY (BPLCON0 bit 1) senza genlock: il generatore di sync di Agnus aspetta un
        // sync esterno che non arriva e il CONTATORE VERTICALE SI FERMA. In WinUAE è
        // `custom_trigger_start()` — la funzione che contiene `vpos++` — a non essere
        // chiamata affatto mentre `syncs_stopped` (custom.cpp, "if (!syncs_stopped)"),
        // mentre `fakehsync_handler` continua a generare hsync così che le righe, e con
        // loro il resto della macchina, non si fermino. Qui è la stessa cosa: la fine
        // riga fa tutto il suo lavoro, solo vpos non avanza e il frame non si chiude.
        //
        // Non è un dettaglio da poco: la graphics.library 1.3 accende ERSY per il
        // rilevamento genlock ($FCAF96) e lo tiene su ~4881 CCK, cioè 21,5 righe. Senza
        // il freeze il nostro pennello si portava avanti di altrettanto rispetto
        // all'oracolo, e da lì in poi ogni attesa del Kickstart agganciata al raster
        // cadeva altrove. Misurato in docs/vamigats-paula-audio-plan.md §13.6.
        if (syncsStopped) {
            return;
        }

        vpos++;

        // Numero di righe del frame dipendente da LOF (Long Frame), come hardware:
        //  - frame LUNGO (LOF=1) = maxVpos+1 righe → vpos raggiunge maxVpos
        //    (PAL: 312, NTSC: 262);
        //  - frame CORTO = maxVpos righe → vpos max maxVpos-1.
        // Anche in non-interlace LOF alterna: in PAL la media diventa 312,5 righe,
        // mantenendo il rapporto corretto fra VBL e E-clock CIA. Necessario anche
        // perché vari giochi (es. Future Wars) attendono in polling che VPOSR
        // raggiunga vpos==312; ciò accade sui frame lunghi.
        boolean longFrame = field == 0;
        int wrapAt = longFrame ? (maxVpos + 1) : maxVpos;

        if (vpos < wrapAt) {
            updateVsyncPhase();
            updateSpriteDMA(vpos);
            return;
        }

        // ── Fine frame (Start of Line 0) ───────────────────────────────────
        vpos = 0;
        frameCount++;
        updateVsyncPhase();

        // VERTB (Paula INTREQ bit 5, IPL 3): asserito all'inizio della riga 0,
        // come l'hardware reale (cfr. vAmiga serviceVBLEvent: PAL OCS → pos.v==0,
        // pos.h==0). NON va anticipata alla riga 311: farlo manda in esecuzione la
        // ISR vblank del gioco proprio mentre il pennello è sulla riga 312, così un
        // main-loop che attende in polling vpos==312 (es. la protezione di
        // Future Wars, $C088B2: cmp.l #$00013800,(VPOSR)) non la osserva mai e si
        // blocca. Con VERTB a riga 0 la ISR gira sulle prime righe e il main-loop
        // ha tutto il resto del frame (riga 312 inclusa, nei frame lunghi) per
        // campionare la posizione del pennello.
        if (paula != null) paula.assertVertbInterrupt();
        lastCompletedFrameHadLateBitplaneDma = bitplaneDmaEnabledLateThisFrame;
        lastCompletedFrameField = field;   // salvato prima del toggle, usato da VirtualCRT
        bitplaneDmaEnabledLateThisFrame = false;

        // Nuovo frame: tutti gli sprite in fase POS/CTL (sprDmaEnabled=false).
        Arrays.fill(sprDmaEnabled, false);

        // Ricarica tutti i puntatori DMA dai registri base
        reloadDmaPointers();

        updateSpriteDMA(0);

        // Notifica il Copper del VBlank (Copper restarts from COP1LC at beam 0,0)
        copper.onVBlank(isCopperEnabled());

        // LOF (Long Frame) alterna SOLO in interlace, come WinUAE, che lo tocca
        // unicamente dentro `if (bplcon0 & 4)` a fine frame (custom.cpp). In
        // non-interlace il bit NON alterna: field resta al suo valore, che dal reset
        // è 1 (LOF=0, frame CORTO — vedi reset()) e diventa 0 quando il software
        // scrive VPOSW con il bit 15.
        // PRIMA alternavamo field a ogni frame anche in non-interlace ("media
        // 312,5"): ciò faceva variare la lunghezza del frame 313/312 a frame
        // alterni e quindi la fase CPU↔raster, producendo uno sfarfallio a
        // periodo 2 nei programmi che si sincronizzano sul raster (es. il test
        // bbusy della suite vAmigaTS, che vAmiga rende invece stabile).
        boolean interlaced = denise != null && (denise.getBplcon0() & 0x0004) != 0;
        if (interlaced) {
            field = (field == 0) ? 1 : 0;
            denise.setField(field);
        } else {
            if (denise != null) denise.setField(0);
        }

        // Nota: CIA-B TOD è pilotato dall'indice floppy, non dal vsync.
        // Vedi updateFloppy() / updateFromDrive().
    }

    // =========================================================================
    // Segnali CIA
    // =========================================================================

    /**
     * Aggiorna i segnali CIA che dipendono dallo stato del sistema.
     *
     * OVL (Overlay ROM):
     *   Il bit 0 del registro PRA della CIA-A controlla il segnale /OVL che va a Gary.
     *   PA0=1 → overlay attivo (ROM visibile a $000000).
     *   PA0=0 → overlay disattivo (Chip RAM a $000000).
     *   La direzione del pin (DDR) deve essere OUTPUT per avere effetto.
     *
     * LED / Audio filter:
     *   PA1 della CIA-A controlla il LED di alimentazione e il filtro audio.
     *   PA1=1 → LED spento, filtro off; PA1=0 → LED acceso, filtro on.
     */
    /**
     * Aggiorna i segnali derivati dal latch PRA/DDRA di CIA-A: /OVL (ROM overlay
     * verso Gary) e LED/filtro audio. Chiamato a granularità E-clock da
     * {@link #serviceCiaEvent()} (i bit cambiano solo in risposta a scritture CIA,
     * sincronizzate all'E-clock su hardware reale) e — per il path di reboot via
     * istruzione CPU RESET — direttamente da {@link #risingEdge()} dopo softReset.
     */
    private void updateCiaOvlLed() {
        if (ciaA == null || gary == null) return;

        CIAPinSet pinsA = ciaA.getPinSet();
        int pra  = pinsA.readPortData(0) & 0xFF;
        int ddra = ciaA.peekDataDirectionA() & 0xFF;

        if (pra != lastCiaAPra || ddra != lastCiaADdra) {
            lastCiaAPra = pra;
            lastCiaADdra = ddra;

            // OVL: controlled by CIA-A PA0, which drives the /OVL line to Gary.
            //   - As an OUTPUT (DDRA bit0 = 1) the line reflects the PRA latch
            //     (PA0=1 -> overlay ON, PA0=0 -> overlay OFF).
            //   - As an INPUT (DDRA bit0 = 0, e.g. right after a CIA reset caused
            //     by the CPU RESET instruction) the external pull-up makes the
            //     line read HIGH -> overlay ON. Modelling this pull-up is what lets
            //     the reboot path (`RESET; MOVEA.L $4,A0; JMP (A0)`) re-map the ROM
            //     at $0 and read the reset vector, instead of jumping to stale
            //     ExecBase/RAM contents and faulting forever (endless guru blink).
            boolean ciaOvlEnabled = (ddra & 0x01) == 0 || (pra & 0x01) != 0;

            if (ciaOvlEnabled != gary.isRomOverlayEnabled()) {
                gary.setRomOverlay(ciaOvlEnabled);
            }

            // LED / Audio filter
            boolean ledState = (pra & 0x02) != 0;
            if (ledState != lastLedState) {
                lastLedState = ledState;
                if (paula != null) {
                    paula.setLedFilterEnabled(!ledState);
                }
            }
        }
    }

    // =========================================================================
    // Interrupt
    // =========================================================================

    /**
     * Aggiorna i bit PORTS e EXTER di INTREQ in base allo stato dei pin IRQ CIA.
     *
     * Su hardware reale:
     *   - Il pin /IRQ della CIA-A scende (LOW) → setta INTREQ PORTS (bit 3)
     *   - Il pin /IRQ della CIA-B scende (LOW) → setta INTREQ EXTER (bit 13)
     *
     * Il bit INTREQ NON viene azzerato automaticamente quando la CIA rilascia
     * il proprio IRQ. Il software deve azzerarlo scrivendo su INTREQ (CLR).
     * Questo è il comportamento hardware corretto (INTREQ è latched).
     *
     * Nota: non azzerare PORTS/EXTER qui evita il problema di azzeramento
     * prematuro che causerebbe interrupt mancati.
     */
    private void updateInterrupts() {
        if (paula == null) return;

        // Su hardware Amiga, gli interrupt CIA sono level-triggered tramite Paula.
        // Se il pin /IRQ della CIA è LOW, il corrispondente bit in INTREQ di Paula
        // deve essere mantenuto a 1. Se il software tenta di azzerare il bit in
        // INTREQ ma il pin /IRQ della CIA è ancora LOW, il bit deve tornare a 1.
        if (ciaA != null) {
            boolean ciaAIrq = ciaA.getPinSet().getIrqPin().getPinState();
            if (!ciaAIrq && !paula.isPortsInterruptAsserted()) {
                paula.assertPortsInterrupt();
            }
        }

        if (ciaB != null) {
            boolean ciaBIrq = ciaB.getPinSet().getIrqPin().getPinState();
            if (!ciaBIrq && !paula.isExterInterruptAsserted()) {
                paula.assertExterInterrupt();
            }
        }
    }

    private void updateCpuIpl() {
        if (paula != null) {
            // Il pin IPL della CPU vede il valore RITARDATO in cima alla pipe IPL di
            // Paula (cfr. vAmiga), NON calculateIPL() live: i cambi di livello si
            // propagano a 1 CCK/stadio (~2-3 CCK di latenza), come l'hardware. La
            // pipe è pilotata da Paula.checkInterrupt() a ogni scrittura INTREQ/INTENA
            // e da updateInterrupts() per i fronti /IRQ dei CIA.
            cpuBusResp.ipl = paula.getCpuIplFromPipe();
        }
    }

    // =========================================================================
    // Floppy
    // =========================================================================

    /**
     * Aggiorna i segnali CIA-B in base allo stato dei drive floppy.
     *
     * @param prbChanged true se il PRB di CIA-B è cambiato rispetto all'ultimo
     *        valore elaborato. df.update() applica solo i FRONTI dei segnali di
     *        controllo: a PRB invariato non c'è alcun fronte (prevStep==step,
     *        prevSelected==selected, side invariato), quindi le 4 chiamate sono un
     *        no-op costoso e si saltano. Ogni transizione reale dei segnali è un
     *        cambio di PRB → prbChanged=true → i fronti vengono comunque colti
     *        (preserva il fix side-select di SpeedBall II). Lo stato (rdy/tk0/index)
     *        è invece campionato SEMPRE, perché evolve nel tempo a PRB fermo.
     */
    private void updateFloppy(byte ciab_prb, boolean prbChanged) {
        if (ciaA == null || ciaB == null) return;

        // Control lines are active LOW in PRB
        boolean step = (ciab_prb & 0x01) == 0; // /STEP LOW = step
        boolean dir  = (ciab_prb & 0x02) != 0; // DIR: 0=INWARD (tk++), 1=OUTWARD (tk0)
        boolean side = (ciab_prb & 0x04) == 0; // /SIDE LOW = side 1
        boolean sel0 = (ciab_prb & 0x08) == 0; // /SELx LOW = selected
        boolean sel1 = (ciab_prb & 0x10) == 0;
        boolean sel2 = (ciab_prb & 0x20) == 0;
        boolean sel3 = (ciab_prb & 0x40) == 0;
        boolean mtr  = (ciab_prb & 0x80) == 0; // /MTR LOW = motor ON request

        if (prbChanged) {
            df0.update(sel0, mtr, step, dir, side);
            df1.update(sel1, mtr, step, dir, side);
            df2.update(sel2, mtr, step, dir, side);
            df3.update(sel3, mtr, step, dir, side);
        }

        // PERF: i 5 segnali di stato (/RDY //TK0 //WPRO //CHNG //INDEX) venivano
        // ricampionati dai drive e ri-pubblicati sui pin CIA ad OGNI CCK di drive
        // attivo (~3,5M volte/s × 5 letture). A regime — motore a piena velocità
        // durante un caricamento — restano però costanti per milioni di cicli: la
        // pubblicazione è identica a sé stessa e si può saltare. Ogni drive alza il
        // proprio flag (FloppyDrive.isStatusDirty) quando uno dei 5 PUÒ essere
        // cambiato, ed è conservativo: resta alto per tutta la rampa del motore,
        // dove /RDY dipende dal tempo e non da una scrittura di campo.
        // Il PRB non è cambiato (prbChanged==false) ⇒ anche selezione e pull-up
        // sono invariati, quindi nulla da ripubblicare.
        boolean anyDirty = (sel0 && df0.isStatusDirty()) || (sel1 && df1.isStatusDirty())
                        || (sel2 && df2.isStatusDirty()) || (sel3 && df3.isStatusDirty());
        if (!prbChanged && !anyDirty && !FLOPPY_STATUS_VERIFY) {
            return;
        }

        // State signals multiplexed on the bus (wired-OR active LOW logic)
        // STEP 8: riusa l'istanza pre-allocata invece di allocarne una nuova.
        FloppyStatus status = floppyStatus;
        status.rdy = status.tk0 = status.wpro = status.chng = status.index = false;
        boolean anySelected = false;
        if (sel0) { updateFromDrive(df0, status); anySelected = true; }
        if (sel1) { updateFromDrive(df1, status); anySelected = true; }
        if (sel2) { updateFromDrive(df2, status); anySelected = true; }
        if (sel3) { updateFromDrive(df3, status); anySelected = true; }

        CIAPinSet pinsA = ciaA.getPinSet();
        CIAPinSet pinsB = ciaB.getPinSet();

        // If a drive is selected, it guides the lines. If no drive is selected, the lines
        // return to pull-up HIGH (inactive).
        if (anySelected) {
            pinsA.setExternalInput(0, 5, !status.rdy);   // /RDY
            pinsA.setExternalInput(0, 4, !status.tk0);   // /TK0
            pinsA.setExternalInput(0, 3, !status.wpro);  // /WPRO
            pinsA.setExternalInput(0, 2, !status.chng);  // /CHNG

            pinsB.getFlagPin().setExternalInput(!status.index); // /INDEX (CIA-B FLAG)
        } else {
            // No drive selected: all status lines pull up to HIGH (inactive)
            pinsA.setExternalInput(0, 5, true); // /RDY
            pinsA.setExternalInput(0, 4, true); // /TK0
            pinsA.setExternalInput(0, 3, true); // /WPRO
            pinsA.setExternalInput(0, 2, true); // /CHNG
            pinsB.getFlagPin().setExternalInput(true); // /INDEX
        }

        // Pubblicato: i drive selezionati ripartono "puliti".
        if (sel0) df0.clearStatusDirty();
        if (sel1) df1.clearStatusDirty();
        if (sel2) df2.clearStatusDirty();
        if (sel3) df3.clearStatusDirty();

        // Verifica esaustiva (-Damiga.floppyverify=true): quando lo skip SAREBBE
        // scattato, la pubblicazione appena calcolata deve coincidere con l'ultima.
        // Una sola discrepanza significa un sito di mutazione non marcato.
        if (FLOPPY_STATUS_VERIFY) {
            int packed = (anySelected ? 1 : 0)
                    | (status.rdy ? 2 : 0) | (status.tk0 ? 4 : 0) | (status.wpro ? 8 : 0)
                    | (status.chng ? 16 : 0) | (status.index ? 32 : 0);
            boolean wouldSkip = !prbChanged && !anyDirty;
            if (wouldSkip && lastPublishedFloppyStatus != -1 && packed != lastPublishedFloppyStatus) {
                floppyVerifyMismatches++;
                System.out.printf("[FLOPPYVERIFY] MISMATCH #%d cyc=%d prev=%02X now=%02X%n",
                        floppyVerifyMismatches, masterClock, lastPublishedFloppyStatus, packed);
            }
            lastPublishedFloppyStatus = packed;
        }
    }

    /** Verifica esaustiva dell'invalidazione dei segnali floppy (diagnostica). */
    private static final boolean FLOPPY_STATUS_VERIFY = Boolean.getBoolean("amiga.floppyverify");
    private int  lastPublishedFloppyStatus = -1;
    private long floppyVerifyMismatches;

    /** Numero di discrepanze rilevate in modalità {@code -Damiga.floppyverify}. */
    public long getFloppyVerifyMismatches() { return floppyVerifyMismatches; }

    private void updateFromDrive(FloppyDrive df, FloppyStatus status) {
        if (df.isReadyRaw())         status.rdy   = true;
        if (df.isTrack0Raw())        status.tk0   = true;
        if (df.isWriteProtectedRaw())status.wpro  = true;
        if (df.isDiskChangedRaw())   status.chng  = true;
        if (df.isIndexActiveRaw())   status.index = true;
    }

    // PERF: consumer di bit MFM pre-allocato. Prima veniva creata una nuova lambda
    // (cattura paula) ad ogni tick di un drive selezionato. Riusarne una sola evita
    // l'allocazione nel percorso di caricamento.
    private final IntConsumer diskBitConsumer = this::shiftDiskBit;

    private void shiftDiskBit(int bit) {
        if (paula != null) paula.shiftInDiskBit(bit);
    }

    /** Cella MFM successiva da scrivere sul supporto (vedi Paula.nextWriteBit). */
    private int nextDiskWriteBit() {
        return paula != null ? paula.nextWriteBit() : Paula.WRITE_BIT_END;
    }

    /**
     * Almeno un drive <b>connesso</b> e' selezionato? E' il complemento del
     * {@code (selected | disabled) == 15} dell'oracolo (disk.cpp): le linee /SELx del PRB
     * di CIA-B sono attive basse, e un drive non connesso e' il suo {@code disabled}.
     */
    private boolean isAnyDriveSelected(byte ciabPrb) {
        return (df0 != null && df0.isConnected() && (ciabPrb & 0x08) == 0)
            || (df1 != null && df1.isConnected() && (ciabPrb & 0x10) == 0)
            || (df2 != null && df2.isConnected() && (ciabPrb & 0x20) == 0)
            || (df3 != null && df3.isConnected() && (ciabPrb & 0x40) == 0);
    }

    private void tickFloppyDrive(FloppyDrive drive, int cycles) {
        if (drive == null) return;
        if (!drive.isConnected()) return;
        if (!drive.isSelected() && !drive.isBusy()) return;
        if (drive.isSelected() && paula != null) {
            drive.tick(cycles, diskBitConsumer);
        } else {
            drive.tick(cycles);
        }
    }

    private boolean isFloppyDriveBusy(FloppyDrive drive) {
        return drive != null && drive.isConnected() && drive.isBusy();
    }

    // PERF: stato per il gating del lavoro floppy per-ciclo (vedi risingEdge).
    // lastCiabPrb=-1 e floppyBusy=true forzano l'esecuzione al primo ciclo finché
    // lo stato non si assesta. floppyBusy=true finché un drive è attivo.
    private int lastCiabPrb = -1;
    private boolean floppyBusy = true;
    // PERF: accumulatore per processare il floppy a granularità CCK (2 master-cycle)
    // invece che ad ogni master-cycle. Vedi il blocco floppy in risingEdge().
    private int floppyAccumCycles = 0;

    /** Durata di una cella MFM in master-cycle: 7 CCK, come FloppyDrive.CYCLES_PER_BIT. */
    private static final int IDLE_DISK_CYCLES_PER_BIT = 14;
    /** Cadenza dello shifter a riposo (vedi il blocco omonimo in risingEdge). */
    private int idleDiskBitAccum = 0;

    private static class FloppyStatus {
        boolean rdy = false, tk0 = false, wpro = false, chng = false, index = false;
    }

    /**
     * STEP 8 (PERFORMANCE_REPORT_FINAL): istanza riutilizzabile di FloppyStatus.
     * updateFloppy() allocava un nuovo oggetto ad ogni invocazione (~7M/s).
     * L'oggetto non sfugge dal metodo (single-thread, sincrono), quindi si può
     * riusare resettando i 5 campi all'inizio di updateFloppy().
     */
    private final FloppyStatus floppyStatus = new FloppyStatus();

    // =========================================================================
    // DMA handlers
    // =========================================================================

    private void handleRefreshDMA() {
        refptr = (refptr + 1) & 0x1FF;
    }

    /**
     * Gestisce un fetch DMA del disco.
     * Legge una word dalla Chip RAM e la invia al controller disco di Paula.
     */
    private void handleDiskDMA(int cck) {
        if (paula == null || !paula.isDiskDmaEnabled()) return;
        int addr = paula.getDiskDmaPointer();

        if (paula.isDiskDmaWrite()) {
            // WRITE: Chip RAM → drive. Una word viene prelevata solo quando lo shift
            // register di scrittura di Paula si è svuotato (16 celle MFM), così il
            // trasferimento procede al ritmo di rotazione del supporto e non a quello
            // degli slot DMA.
            if (!paula.isWriteWordNeeded()) return;
            int data = chipRAM.readWord(dmaChipAddr(addr));
            openBusLatch = data & 0xFFFF; // il fetch DMA pilota il bus dati (vAmiga busData)
            paula.loadWriteWord(data);
            paula.advanceDiskDma();
        } else {
            // READ: FIFO → Chip RAM — only in READ state (not WAIT).
            // In WAIT, bits are still shifted in looking for sync, but no DMA transfer to RAM.
            if (paula.isDiskDmaReading() && paula.fifoWordCount() > 0 && paula.getDiskLen() > 0) {
                int word = paula.readFifo();
                chipRAM.writeWord(dmaChipAddr(addr), word);
                openBusLatch = word & 0xFFFF; // scrittura DMA su bus dati
                paula.advanceDiskDma();
            }
        }
    }

    private FloppyDrive getSelectedDrive() {
        if (df0.isSelected()) return df0;
        if (df1.isSelected()) return df1;
        if (df2.isSelected()) return df2;
        if (df3.isSelected()) return df3;
        return null;
    }

    /**
     * Gestisce un fetch DMA audio.
     * Legge una word di campioni audio dalla Chip RAM e la invia a Denise/Paula.
     *
     * I slot audio: 0→cck15, 1→cck17, 2→cck19, 3→cck21.
     */
    private void handleAudioDMA(int cck) {
        if (paula != null) {
            int chan = (cck - SLOT_AUDIO_0) / 2;
            if (chan >= 0 && chan < 4 && paula.isAudioDmaRequest(chan)) {
                int addr = paula.getAudioDmaAddress(chan);
                int data = chipRAM.readWord(dmaChipAddr(addr));
                openBusLatch = data & 0xFFFF; // il fetch DMA pilota il bus dati (vAmiga busData)
                paula.receiveAudioDmaWord(chan, data);
            }
        }
    }

    /**
     * Gestisce il fetch DMA degli sprite.
     *
     * Ogni sprite occupa 2 slot CCK per riga:
     *   Slot N   (subSlot=0): SPRxPOS  (fase POS/CTL)  oppure  SPRxDATA  (fase DATA/DATB)
     *   Slot N+2 (subSlot=1): SPRxCTL  (fase POS/CTL)  oppure  SPRxDATB  (fase DATA/DATB)
     *
     * Fase POS/CTL  → durante VBlank e dopo VSTOP: Agnus fetcha il descrittore.
     * Fase DATA/DATB → tra VSTART e VSTOP: Agnus fetcha i pixel dello sprite.
     *
     * Le transizioni di fase avvengono leggendo SPRxCTL (fine POS/CTL → DATA/DATB
     * se vpos >= VSTART) e alla riga di VSTOP (DATA/DATB → POS/CTL).
     */
    private void handleSpriteDMA(int cck) {
        if (denise == null) return;

        int relCck  = cck - SLOT_SPRITE_0;
        int sprIdx  = relCck / 4;         // quale sprite (0-7)
        int subSlot = (relCck % 4) / 2;   // 0 = primo slot (N), 1 = secondo slot (N+2)

        if (sprIdx < 0 || sprIdx > 7) return;

        int addr = sprPtrInternal[sprIdx];
        int data = chipRAM.readWord(dmaChipAddr(addr));
        openBusLatch = data & 0xFFFF; // il fetch DMA pilota il bus dati (vAmiga busData)
        sprPtrInternal[sprIdx] += 2;

        if (!sprSlotDataPhase[sprIdx][subSlot]) {
            // ── Fase POS/CTL: fetch del descrittore sprite ──────────────────
            if (subSlot == 0) {
                // Primo slot: SPRxPOS
                writeSPRxPOS(sprIdx, data);
                denise.writeRegister(0x140 + sprIdx * 8, data); // SPRxPOS
            } else {
                // Secondo slot: SPRxCTL — aggiorna VSTART/VSTOP per updateSpriteDMA
                writeSPRxCTL(sprIdx, data);
                denise.writeRegister(0x142 + sprIdx * 8, data); // SPRxCTL
            }
        } else {
            // ── Fase DATA/DATB: fetch dei pixel sprite ───────────────────────
            if (subSlot == 0) {
                denise.writeRegister(0x144 + sprIdx * 8, data); // SPRxDATA
            } else {
                denise.writeRegister(0x146 + sprIdx * 8, data); // SPRxDATB
            }
        }
    }

    /**
     * Lo {@code slot == 0} di {@code generate_sprites()} (custom.cpp): l'<b>unico</b> punto
     * in cui il sequencer degli sprite si arma.
     *
     * <p>⛔ <b>L'ancora è lo SLOT, non il confine di riga.</b> Prima VSTART/VSTOP li
     * valutava {@code updateSpriteDMA()} al cambio di {@code vpos}; l'oracolo lo fa qui, al
     * primo dei due slot di <i>quello</i> sprite — che per lo sprite <i>n</i> cade al CCK
     * {@code 21 + 4n}. Fra il confine di riga e quel CCK ci stanno le scritture del Copper,
     * ed è precisamente lì che vivono i test `spritedma/interfere*`.
     *
     * <p>⚠️ La riga di reset (25 PAL) è il {@code agnus_vb_active_end_line} dell'oracolo:
     * ferma lo sprite e gli fa rileggere il descrittore, ed è quel che prima ottenevamo
     * riscrivendo {@code sprVStop[i] = 25}. Qui è una condizione, non una scrittura a un
     * registro di stato.
     */
    private void generateSpritesSlot0(int idx) {
        if (sprDmaCycle[idx] == 0 && sprDmaEnabled[idx]) {
            sprDmaCycle[idx] = 1;
        }
        if (vpos == sprVStart[idx]) {
            sprDmaEnabled[idx] = true;
            sprDmaCycle[idx] = 1;
        }
        if (vpos == sprVStop[idx] || vpos == SPRITE_RESET_LINE) {
            sprDmaEnabled[idx] = false;
            sprDmaCycle[idx] = 1;
        }
    }


    /**
     * Aggiorna sprDmaEnabled per la riga nextVpos, seguendo la logica vAmiga:
     * <ul>
     *   <li>A SPRITE_RESET_LINE (25 PAL): tutti i sprVStop vengono resettati a 25,
     *       forzando il primo fetch POS/CTL del frame su quella stessa riga.</li>
     *   <li>Per le righe visibili: sprDmaEnabled[i] viene attivato a sprVStart[i]
     *       e disattivato a sprVStop[i].</li>
     * </ul>
     * Deve essere chiamato DOPO l'incremento di vpos, con nextVpos = nuovo vpos.
     */
    private void updateSpriteDMA(int nextVpos) {
        // ⛔ La valutazione di VSTART/VSTOP NON sta più qui: sta allo slot 0 di ciascuno
        // sprite (generateSpritesSlot0), dove la mette l'oracolo. Resta il solo spegnimento
        // di fine quadro, che di là è il gate `vpos < maxvpos` dell'arbitraggio.
        if (nextVpos >= maxVpos) {
            Arrays.fill(sprDmaEnabled, false);
            Arrays.fill(sprDmaCycle, 0);
        }
    }

    /**
     * Indirizzo Chip RAM per un accesso DMA: word-allineato e avvolto entro la Chip RAM
     * installata. I puntatori DMA di Agnus (bitplane/sprite/disco/audio) sono contatori
     * che si incrementano senza limite e possono superare il range fisico: l'hardware li
     * fa avvolgere (mirror), mentre {@link RAM} farebbe un bound check rigoroso.
     */
    private int dmaChipAddr(int addr) {
        return (addr & chipMask) & ~1;
    }

    private void handleBitplaneDMA(int cck, int bplIdx, boolean hires) {
        if (denise == null) return;
        if (bplIdx < 0 || bplIdx >= 6) return;

        int addr = bplPtrInternal[bplIdx];
        int data = chipRAM.readWord(dmaChipAddr(addr));
        openBusLatch = data & 0xFFFF; // il fetch DMA pilota il bus dati (vAmiga busData)
        denise.writeRegisterAtPixel(0x110 + bplIdx * 2, data, cck * 4 - 70); // BPLxDAT
        bplPtrInternal[bplIdx] += 2;
        // Modulo bitplane hardware-exact (WinUAE generate_bpl/bitplane_rga_ptmod):
        // BPLxMOD è aggiunto al puntatore AL MOMENTO dell'ULTIMO fetch del piano
        // nella riga (fetch-unit finale, `domod`), col valore del registro campionato
        // in quel CCK — NON a fine riga. La differenza è osservabile: Arte (Sanity)
        // scrive i moduli via Copper a WAIT h=222, DOPO l'ultimo fetch (~cck 210) ma
        // PRIMA della fine riga: sull'hardware la scrittura vale per la riga
        // SUCCESSIVA; col vecchio modello a fine riga metà delle MOVE (2 in
        // sequenza, a cavallo del wrap) veniva applicata una riga in anticipo,
        // desincronizzando i piani di una riga bitmap a scanline alterne (glitch
        // "scintille"/righe chiare, docs/glitch1.png / glitch2.png).
        // In hires il domod copre solo la SECONDA metà dell'unità finale da 8 CCK
        // (fm_maxplane==4 && cycle>=4): ogni piano prende il modulo una sola volta.
        //
        // ⛔ Quale sia l'ultimo blocco lo dice il SEQUENCER, non la posizione: è
        // `ddf_stopping == 2`, lo stato in cui la macchina entra dopo che DDFSTOP è
        // passato (generate_bpl). Col vecchio confronto posizionale
        // `cck >= effStop - (hires?4:8)` una riga che spegne e riaccende BPLEN nel mezzo
        // applicava il modulo dove capitava, perche' `effStop` non sa niente di quel che
        // e' successo dentro la riga.
        if (ddfStopping == 2 && (!hires || (bprunCycle & 7) >= 4)) {
            // ⛔ Il modulo è quello CAMPIONATO in cima al CCK (`bitplane_rga_ptmod`), non
            // il registro vivo: vedi bpl1modFetch. Leggendolo vivo, una scrittura del
            // Copper caduta nel CCK precedente entrava un CCK troppo presto.
            int mod = (bplIdx % 2 == 0) ? bpl1modFetch : bpl2modFetch;
            // Il modulo si somma SENZA maschera: il wrap entro la Chip RAM installata è
            // di dmaChipAddr, al momento della fetch (oracolo WinUAE: bitplane_rga_ptmod
            // fa `bplpt[i] += mod` e solo chipmem_agnus_wget applica chipmem_full_mask).
            // Qui c'era un `& 0xFFFFF` cablato a 1 MB: con 2 MB di Chip RAM un piano
            // sopra $0FFFFF perdeva il bit 20 a ogni riga e il display collassava nel
            // primo megabyte — è il caso di WHDLoad, che riserva la Chip bassa al gioco
            // e alloca lo schermo Workbench più in alto.
            bplPtrInternal[bplIdx] = bplPtrInternal[bplIdx] + mod;
        }
    }

    /**
     * ⛔ <b>BPL1DAT è il TRIGGER del display, ed è una SCRITTURA DI REGISTRO</b>: il DMA di
     * bitplane è solo il suo fornitore abituale, non la sua condizione. Con BPLEN
     * <b>spento</b> e il Copper (o la CPU) che scrive BPL1DAT a mano, Denise fa uscire quella
     * word lo stesso — l'oracolo lo tratta come un trigger a sé ({@code bpl1dat_trigger},
     * {@code bpl1dat_enable_bpls()} sul {@code case 0x110} di {@code expand_drga_early}), e
     * non guarda DMACON.
     *
     * <p>⚠️ Il gate qui era {@code isBitplaneDMAActive()}, e faceva sparire <b>tutto</b>
     * l'output del banco {@code Denise/Registers/BPL0DAT}, dove il DMA di bitplane non è mai
     * acceso: quattro test in cui non disegnavamo un pixel. Il gate giusto è «c'è una word
     * armata», che con il DMA acceso è vero esattamente quando lo era prima — il fetch
     * scrive BPL1DAT e arma. La condizione è quindi <b>additiva</b>: tutto ciò che disegnava
     * prima disegna ancora, e in più disegna il caso a DMA spento.
     */
    private void handleBitplaneDrawEvent(int cck) {
        if (denise == null) return;
        if (!isBitplaneDMAActive() && !denise.isBitplaneArmed()) return;

        int bplcon0Val = denise.getBplcon0();
        // Piani DISEGNATI, non prelevati: qui si decide se Denise fa uscire qualcosa,
        // e la soglia è la sua (AbstractDenise.deniseBpu) — più di 4 piani in hires
        // significa nessun piano, non quattro.
        if (denise.getBpu() <= 0) return;

        boolean hires = (bplcon0Val & 0x8000) != 0;
        int cycleLen = hires ? 4 : 8;
        int mask = cycleLen - 1;
        // Parte GROSSA dello scroll: quanti CCK ritardare il blocco. Vedi scrollSubPixels
        // per la regola unica e per perché in hires non è la stessa di lores.
        int subOdd  = scrollSubPixels(bplcon1, hires);
        int subEven = scrollSubPixels(bplcon1 >> 4, hires);
        boolean drawOdd = (cck & mask) == ((subOdd >> 2) & mask);
        boolean drawEven = (cck & mask) == ((subEven >> 2) & mask);
        if (drawOdd || drawEven) {
            // ⚠️ In SPF la parte FINE dei piani pari viene da PF1H (i piani pari scrollano
            // coi dispari), mentre quella GROSSA viene da PF2H come per il DPF. È
            // l'asimmetria che il codice aveva già, e la si conserva di proposito: non è la
            // materia di questo passo, e con PF1H == PF2H — il caso di tutti i giochi e di
            // tutto `BPLCON1/simple` — non è osservabile. ⏸ Da risolvere quando ci sarà un
            // riferimento che la distingua.
            int fineEven = ((bplcon0Val & 0x0400) != 0 ? subEven : subOdd) & 3;
            denise.drawBitplanesAtPixel(cck * 4 - 70, drawOdd, drawEven,
                    subOdd & 3, fineEven);
        }
    }

    private void reloadDmaPointers() {
        System.arraycopy(bplPt, 0, bplPtrInternal, 0, 6);
        System.arraycopy(sprPt, 0, sprPtrInternal, 0, 8);
    }

    // =========================================================================
    // Bus cycles
    // =========================================================================

    private void handleCopperBusCycle() {
        if (copperBusReq.as) {
            Gary.Route route = gary.route(copperBusReq.address,
                    copperBusReq.read ? Gary.AccessType.READ : Gary.AccessType.WRITE,
                    copperBusReq.uds, copperBusReq.lds, false, copperScratchRoute);
            if (route.ok) {
                if (!copperBusProcessed) {
                    if (copperBusReq.read) {
                        copperBusResp.dataIn = readBusData(route, copperBusReq.address);
                    } else {
                        writeBusData(route, copperBusReq.address, copperBusReq.dataOut, copperBusReq.uds, copperBusReq.lds);
                    }
                    copperBusProcessed = true;
                }
                copperBusResp.ack = true;
            } else {
                copperBusResp.berr = true;
            }
        } else {
            copperBusResp.clearHandshake();
            copperBusProcessed = false;
        }
    }

    /**
     * Lettura di memoria SINCRONA per il blitter in modalità immediate: instrada via
     * Gary ed esegue subito la lettura, senza consumare cicli di bus emulati (il blit
     * immediate completa tutto alla scrittura di BLTSIZE). Vedi {@link Blitter#setImmediateMode}.
     */
    public int blitterImmediateRead(int address) {
        Gary.Route route = gary.route(address, Gary.AccessType.READ, true, true, false, blitterScratchRoute);
        return route.ok ? readBusData(route, address) : 0xFFFF;
    }

    /** Scrittura di memoria sincrona per il blitter immediate (vedi {@link #blitterImmediateRead}). */
    public void blitterImmediateWrite(int address, int data) {
        Gary.Route route = gary.route(address, Gary.AccessType.WRITE, true, true, false, blitterScratchRoute);
        if (route.ok) writeBusData(route, address, data, true, true);
    }

    private void handleCpuBusCycle() {
        if (!cpuBusReq.as) {
            // Azzeramento idempotente: una sola volta alla discesa di AS (vedi cpuBusIdle).
            if (!cpuBusIdle) {
                cpuBusResp.clearHandshake();
                cpuBusResp.vpa = false;
                cpuBusResp.berr = false;
                cpuAccessTransferred = false;
                cpuRouteCacheValid = false;
                cpuCurrentRoute = null;
                cpuBusIdle = true;
            }
            return;
        }
        cpuBusIdle = false;

        // Interrupt acknowledge cycle (IACK): ciclo autovettorizzato (AVEC) a durata
        // FISSA (DTACK immediato, 4 clock), NON sincronizzato all'E-clock.
        //
        // PRIMA segnalavamo `vpa=true` → la CPU passava in S_VMA e attendeva la fase
        // dell'E-clock (come un ciclo 6800 sincrono). Ciò rendeva la latenza di entrata
        // interrupt DIPENDENTE dalla fase dell'E-clock libero (CLK/10). Su un frame PAL
        // a campo lungo costante (313 righe × 454 SC = 142102 SC), 142102 mod 10 = 2:
        // la fase E-clock precede di +2 SC ogni frame → orbita a periodo 5. Questo è
        // l'UNICO percorso E-clock-dipendente nel loop di servizio interrupt di bbusy0,
        // ed è la causa esatta del "flicker" a periodo 5 (synccpu entra a una hpos diversa
        // ogni frame). I riferimenti NON sincronizzano l'IACK autovettorizzato all'E-clock:
        //   • vAmiga (Moira execInterrupt): SYNC a durata fissa, nessuna dipendenza E-clock.
        //   • WinUAE (iack_cycle): x_do_cycles(4) fisso.
        // Allineiamo: ACK immediato (S4→S5, 4 clock fissi), vettore ancora autovettorizzato.
        if (cpuBusReq.iack) {
            cpuBusResp.iackAutovector = true;
            cpuBusResp.ack = true;
            cpuBusResp.vpa = false;
            return;
        }

        // ── FSM bus UNIFICATA (refactor allineamento CCK) ────────────────────
        // Agnus NON mantiene più una seconda FSM (ex `cpuBusState` S0-S7) parallela
        // a quella della CPU. Quella doppia FSM, con durata diversa da quella della
        // CPU (2 vs 4 hpos dopo lo skip del lead-up di FIX 1), sfasava la fase ai
        // confini di istruzione: gli accessi del loop "firework" di IK+ cadevano su
        // hpos dispari e pagavano l'odd-wait di allineamento `(hpos&1)`, accumulando
        // un over-charge (~+44 cyc/iter) che faceva sforare il gestore VERTB → reset.
        //
        // Ora Agnus è puramente COMBINATORIA rispetto al ciclo bus della CPU
        // (`cpu.getBusCycleState()`, verificato cycle-exact dai vettori JSON):
        //   • S2-S4 → calcola la route e decide DTACK (ack/vpa/berr), inclusa la
        //             contention DMA e l'allineamento CCK. La CPU campiona DTACK al
        //             proprio S4; ri-valutare durante gli wait-state in S4 è corretto
        //             (ri-arbitraggio finché il bus non è libero/allineato).
        //   • S5-S7 → esegue il trasferimento dati UNA volta (latch
        //             `cpuAccessTransferred`), così la CPU ha il dato pronto quando
        //             latcha al proprio S6.
        // L'accesso dura quindi esattamente il ciclo bus naturale (pari) della CPU:
        // nessun mismatch di durata → la fase non deriva più verso dispari.
        switch (cpu.getBusCycleState()) {
            case S2, S3, S4 -> decideCpuDtack();
            case S5, S6, S7 -> {
                if (!cpuAccessTransferred && cpuCurrentRoute != null && cpuCurrentRoute.ok) {
                    handleCpuDataTransfer();
                    cpuAccessTransferred = true;
                }
            }
            default -> { /* IDLE/S0/S_VMA: niente da decidere o trasferire qui */ }
        }
    }

    /**
     * {@code ce_banktype} di WinUAE: l'accesso è CHIP16 (contesa DMA + quantizzazione al
     * CCK) o FAST16 (DTACK immediato). Vedi il commento esteso in {@link #decideCpuDtack()}.
     */
    private static boolean isChipBusSyncAddress(int physAddr, Gary.Target target) {
        // $D00000-$DFFFFF: CHIP16 incondizionato, chiunque risponda (fill_ce_banks).
        if (physAddr >= 0xD00000 && physAddr < 0xE00000) return true;
        // $C00000-$CFFFFF: FAST16 di suo, ma la Slow RAM INSTALLATA se lo riprende
        // (setmemorywidth + bogomem.chipramtiming). Il target lo dice già: Gary rende
        // SLOW_RAM solo dove c'è RAM vera, e i mirror dei custom sopra di essa sono
        // CUSTOM_REGS.
        if (physAddr >= 0xC00000) return target == Gary.Target.SLOW_RAM;
        return physAddr < 0x200000
                && (target == Gary.Target.CHIP_RAM || target == Gary.Target.OPEN_BUS);
    }

    /**
     * Scrittura su un banco <b>FAST16</b>: il dato lo prende il destinatario all'INIZIO del
     * ciclo bus, non a metà.
     *
     * <p>Oracolo WinUAE, {@code mem_access_delay_word_write} (newcpu.cpp:8275): i due rami
     * non differiscono solo per l'attesa, differiscono per l'ORDINE.
     * <pre>
     *   CHIP16: x_do_cycles_pre(CYCLE_UNIT); dma_cycle(); put_word(); x_do_cycles_post(CYCLE_UNIT);
     *   FAST16: put_word();                                          x_do_cycles_post(4*cpucycleunit);
     * </pre>
     * Stessa durata (2 CCK), ma la {@code put_word} del ramo FAST16 cade <b>un CCK prima</b>.
     * Su un banco senza effetti collaterali non si vede; sui mirror dei registri custom di
     * {@code $C00000-$CFFFFF} (FAST16 per {@code fill_ce_banks}) si vede eccome, ed è il
     * residuo che restava a vAmigaTS {@code Memory/Rom/mirror/custC}: misurato con la sonda
     * gemella di {@code WINUAE_COLORLOG}, ogni COLOR00 del banco cadeva a −3 CCK dalla
     * convenzione dell'oracolo invece che a −4, e tornava a −4 esattamente all'iterazione
     * 1024, cioè dove l'indirizzo entra in {@code $D00000} ed è di nuovo CHIP16.
     *
     * <p>Il punto è <b>S2</b>, cioè il primo fronte in cui /AS è asserito: prima di lì
     * {@code handleCpuBusCycle} esce sul ramo bus-idle, che azzera route e
     * {@code cpuAccessTransferred}. È un {@code hpos} prima dell'S4 dove cade il
     * trasferimento chip-bus, e l'{@code hpos} è ciò che Denise quantizza in CCK: basta a
     * riportare la scrittura nel CCK precedente, che è dove l'oracolo la mette.
     *
     * <p>Solo le SCRITTURE: uds/lds a questo punto valgono per una scrittura
     * ({@code applyBusSignalsS0} li ha copiati all'S0) e il dato letto lo latcha comunque
     * la CPU al proprio S6 — anticipare una lettura non sposterebbe nulla di osservabile.
     */
    private void maybeTransferFastBankWrite() {
        if (cpuAccessTransferred || cpuBusReq.read || cpuBusReq.iack) return;
        routeCpuCurrentBusRequest();
        if (cpuCurrentRoute == null || !cpuCurrentRoute.ok) return;
        // ⛔ Una CIA NON è un banco FAST16: il suo è un ciclo SINCRONO (6800), e il dato
        // lo latcha sul fronte dell'E-clock, in fondo al ciclo VMA — non all'inizio.
        // Passando di qui la scrittura arrivava al chip a S2, cioè al primo fronte con
        // /AS asserito e a una fase E QUALSIASI: misurato su vAmigaTS CIA/CIA, le quattro
        // scritture di un handler cadevano a masterClock mod 10 = 2, 0, 4, 0, fino a 9
        // master-cycle prima del punto in cui l'oracolo le consegna. Un timer armato lì
        // parte fino a due E-clock in anticipo, e l'interrupt che ne segue arriva 5÷10 CCK
        // prima: è tutta la «classe 1» del banco (21 rossi su 35).
        //
        // Oracolo, cia.cpp: `cia_bput` fa `cia_wait_pre()` — che con `get_cia_sync_cycles`
        // porta il clock al punto di sync dell'E-clock e ci aggiunge `e_clock_start` —
        // e SOLO DOPO chiama `WriteCIAA/WriteCIAB`; `cia_wait_post` paga il resto del
        // ciclo. La scrittura cade quindi sempre alla stessa fase, ed è lì che
        // `CIA_calctimers` ri-ancora la griglia del clock CIA.
        //
        // Le letture non passavano di qui (il ramo è per le sole scritture) ed erano già
        // giuste: uscendo, le due direzioni tornano simmetriche.
        if (cpuCurrentRoute.target == Gary.Target.CIA_A
                || cpuCurrentRoute.target == Gary.Target.CIA_B
                || cpuCurrentRoute.target == Gary.Target.CIA_BOTH) return;
        if (isChipBusSyncAddress(cpuBusReq.address & 0xFFFFFF, cpuCurrentRoute.target)) return;
        handleCpuDataTransfer();
        cpuAccessTransferred = true;
    }

    /**
     * Decide DTACK per l'accesso CPU corrente mentre la CPU presenta il ciclo
     * (stati S2-S4 della sua FSM). Calcola la route e imposta ack/vpa/berr in base a
     * target, contention DMA e allineamento CCK. Idempotente: ri-calcolata a ogni
     * fronte finché la CPU resta in S4 (wait-state), così l'accesso è concesso non
     * appena il chip-bus è libero e allineato.
     */
    private void decideCpuDtack() {
        routeCpuCurrentBusRequest();

        if (!cpuCurrentRoute.ok) {
            if (cpuCurrentRoute.busError) {
                cpuBusResp.berr = true;
            } else {
                // Open bus: DTACK immediato, dato = ultimo valore visto sul bus dati.
                cpuBusResp.ack = true;
                if (cpuBusReq.read) cpuBusResp.dataIn = lastBusData;
                cpuAccessTransferred = true; // nessun trasferimento in memoria
            }
            return;
        }

        // Accesso CIA: ciclo sincrono. Agnus asserisce VPA; la sincronizzazione
        // E-clock/VMA è gestita interamente dalla FSM della CPU (stato S_VMA).
        if (cpuCurrentRoute.target == Gary.Target.CIA_A
                || cpuCurrentRoute.target == Gary.Target.CIA_B
                || cpuCurrentRoute.target == Gary.Target.CIA_BOTH) {
            cpuBusResp.vpa = true;
            cpuBusResp.ack = false;
            return;
        }

        // Sincronizzazione al chip-bus (contesa DMA + quantizzazione al CCK).
        //
        // ⛔ La decide il BANCO, cioè `ce_banktype` di WinUAE, e non basta leggerne
        // l'inizializzazione. `fill_ce_banks` (memory.cpp:2746) parte da tutto
        // CE_MEMBANK_FAST16 e mette CHIP16 su $000000-$1FFFFF (solo se a 0 c'è davvero la
        // chip RAM, cioè a overlay spento) e su $D00000-$DFFFFF, questa incondizionata —
        // ma poi, in CODA alla stessa funzione, `setmemorywidth(&currprefs.bogomem, ...)`
        // (memory.cpp:2720) riporta a `ce_banktype[0]`, cioè a CHIP16, le pagine della
        // Slow RAM effettivamente INSTALLATA, perché `bogomem.chipramtiming` è `true` nei
        // default (cfgfile.cpp:8767; lo spengono solo A1000 e Velvet).
        //
        // Quindi in $C00000-$CFFFFF convivono le due nature: dove c'è la trapdoor è
        // CHIP16, e i mirror dei registri custom che stanno SOPRA di essa sono FAST16 —
        // la CPU ci scrive senza pagare né contesa né allineamento. È il banco a
        // decidere: `mem_access_delay_word_write` (newcpu.cpp:8275) chiama
        // `wait_cpu_cycle_write` per CHIP16 e per FAST16 fa `put_word` + 4 cicli secchi.
        //
        // Legare la scelta al TARGET (SLOW_RAM/CUSTOM_REGS sempre sincronizzati) faceva
        // pagare ai mirror una contesa che l'oracolo non paga: in vAmigaTS
        // Memory/Rom/mirror/custC la deriva cresceva fino a 3 CCK verso il fondo dello
        // schermo. ⚠️ Trattarli invece TUTTI come FAST16 — la lettura della sola
        // `fill_ce_banks` — toglie la contesa anche alla Slow RAM vera e sposta la catena
        // di boot: CpuTimingTestSuite passa da 253/0 a rosso su quasi tutti i test.
        // Vedi docs/vamigats-import-order.md §Esiti Memory/Rom.
        //
        // ⚠️ L'overlay resta come prima: a OVL attivo WinUAE ha kickmem a 0 e quindi
        // FAST16 anche lì, noi manteniamo CHIP16 sul target CHIP_RAM. È una
        // divergenza nota, di un'altra materia (chi risponde a 0), non misurata da
        // questo banco e non toccata qui.
        //
        // Root storica di G1/NEG2l: l'handler irq6 fa `neg.l $DDF180`, indirizzo
        // open-bus ma dentro $D00000-$DFFFFF; saltando la contesa gli accessi
        // comprimevano a passo 2 CCK invece di quantizzarsi sugli slot liberi
        // (BPU=6) → marker bianco −8 CCK vs WinUAE.
        int physAddr = cpuBusReq.address & 0xFFFFFF;
        boolean needsChipBusSync = isChipBusSyncAddress(physAddr, cpuCurrentRoute.target);

        if (needsChipBusSync) {
            // Bloccato se: (a) il bus è preso da un DMA in questo CCK (incluso un fetch
            // bitplane), oppure (b) l'accesso non è allineato al CCK (hpos dispari).
            //
            // NB: in lores con ≤4 piani (≤2 in hires) il bitplane occupa solo gli slot
            // CCK dispari del fetch-unit; il 68000 prende NATURALMENTE quelli pari liberi
            // (lì busTakenByCustom è false), ottenendo la giusta banda ~50%. NON va
            // aggiunta un'eccezione "bitplane non-bloccante" sugli slot OCCUPATI: farlo
            // raddoppia la banda CPU nell'area di display e fa correre il loop di blit
            // dei giochi davanti al pennello → corruzione a bande dei piani (es. Altered
            // Beast). Vedi memoria altered-beast-bitplane-contention.
            //
            // odd-wait `(hpos&1)`: la CPU campiona DTACK solo al CCK boundary (hpos
            // pari). È l'allineamento CCK-granulare del 68000 al chip-bus, equivalente
            // al modello di vAmiga (`pos.h` in CCK) / WinUAE (`do_cck`): NON è un hack,
            // fa parte del modello cycle-exact. Con la contention a CCK pieno (reset su
            // hpos pari, vedi sopra) la fase del ciclo bus CPU si auto-mantiene allineata
            // e l'odd-wait scatta raramente — misurato 0 blocchi odd-wait nel loop
            // "firework" di IK+ (Fase F, vedi docs/cpu-chipbus-alignment-fix.md).
            boolean oddWait = (hpos & 1) != 0;
            boolean blocked = busTakenByCustom || oddWait;
            cpuBusResp.ack = !blocked;

            if (diagBlockTally
                    && cpu.getBusCycleState() == Motorola68000.BusCycleState.S4) {
                int oa = cpu.getOpcodeAddress() & 0xFFFFFF;
                if (oa >= diagBlkPcLo && oa < diagBlkPcHi) {
                    diagBlkTot++;
                    if (blocked) {
                        if (busTakenByCustom) {
                            int own = busOwners[hpos >> 1];
                            if (own >= 0 && own < diagBlk.length) diagBlk[own]++;
                        } else if (oddWait) diagBlkOdd++;
                    }
                }
            }
        } else {
            // ROM o altro target non contendibile: DTACK immediato.
            cpuBusResp.ack = true;
        }
    }

    private void routeCpuCurrentBusRequest() {
        int address = cpuBusReq.address;
        boolean read = cpuBusReq.read;
        boolean uds = cpuBusReq.uds;
        boolean lds = cpuBusReq.lds;

        if (cpuRouteCacheValid
                && cpuRouteCacheAddress == address
                && cpuRouteCacheRead == read
                && cpuRouteCacheUds == uds
                && cpuRouteCacheLds == lds) {
            return;
        }

        cpuCurrentRoute = gary.route(address,
                read ? Gary.AccessType.READ : Gary.AccessType.WRITE,
                uds, lds, true, cpuScratchRoute);
        cpuRouteCacheAddress = address;
        cpuRouteCacheRead = read;
        cpuRouteCacheUds = uds;
        cpuRouteCacheLds = lds;
        cpuRouteCacheValid = true;
    }

    /**
     * Esegue il trasferimento dati della CPU sullo slot appena concesso (fase di discesa,
     * FSM bus in S5). Stessa condizione del ramo S5-S7 di {@link #handleCpuBusCycle()}, che
     * resta come rete per i casi in cui la CPU arrivi in S5 senza passare di qui.
     */
    private void handleCpuDataTransferPhase() {
        if (!cpuBusReq.as || cpuBusReq.iack || cpuAccessTransferred) {
            return;
        }
        // Banco FAST16: la scrittura cade all'inizio del ciclo bus, un CCK prima dello slot
        // chip-bus. Sta PRIMA del controllo sulla route perché a S2 la route non c'è ancora:
        // la calcola maybeTransferFastBankWrite(). Vedi il suo javadoc.
        if (cpu.getBusCycleState() == Motorola68000.BusCycleState.S2) {
            maybeTransferFastBankWrite();
            return;
        }
        if (cpuCurrentRoute == null || !cpuCurrentRoute.ok) {
            return;
        }
        switch (cpu.getBusCycleState()) {
            // S4 con DTACK gia' concesso in questo stesso master-cycle: e' LO SLOT concesso.
            // La CPU consuma l'ack solo al fronte di salita successivo (advanceBusCycleRising
            // case S4 -> S5), quindi senza questo ramo il trasferimento scivolerebbe di un
            // master-cycle, cioe' di mezzo CCK.
            case S4 -> {
                if (cpuBusResp.ack) {
                    handleCpuDataTransfer();
                    cpuAccessTransferred = true;
                }
            }
            case S5, S6, S7 -> {
                handleCpuDataTransfer();
                cpuAccessTransferred = true;
            }
            default -> { /* non ancora concesso */ }
        }
    }

    private void handleCpuDataTransfer() {
        // La CPU pre-splitta i long-word in due word distinte (Motorola68000
        // .scheduleRead/scheduleWriteLong): Agnus vede sempre accessi byte/word,
        // quindi l'indirizzo è quello richiesto e UDS/LDS sono i segnali reali.
        int addr = cpuBusReq.address;
        boolean busUds = cpuBusReq.uds;
        boolean busLds = cpuBusReq.lds;

        // Determina se il target corrente è una CIA (calcolato una sola volta).
        boolean isCia = (cpuCurrentRoute.target == Gary.Target.CIA_A
                || cpuCurrentRoute.target == Gary.Target.CIA_B
                || cpuCurrentRoute.target == Gary.Target.CIA_BOTH);
        // Coerenza della lettura della posizione del pennello (VPOSR/VHPOSR).
        // Il 68000 legge `MOVE.L $DFF004` come DUE accessi word distinti ($004 poi $006).
        // Se vpos incrementa tra i due (al confine di riga, es. 255→256) la posizione
        // ricostruita salta (V8 dal vecchio vpos, V7-0 dal nuovo). La graphics.library 1.3
        // usa questa lettura per il rilevamento PAL/NTSC ($FCB00C): un salto verso il basso
        // al confine 256 la fa concludere erroneamente NTSC. Per garantire coerenza (come
        // l'hardware reale nella stragrande maggioranza dei casi) si latcha vpos/hpos alla
        // lettura di VPOSR e lo si riusa per la lettura IMMEDIATAMENTE successiva di VHPOSR.
        int beamReg = (cpuBusReq.read && cpuCurrentRoute.target == Gary.Target.CUSTOM_REGS)
                ? (cpuCurrentRoute.effectiveAddress & 0x1FE) : -1;
        // Pre-lettura: il latch resta valido solo se stiamo leggendo VHPOSR subito dopo
        // VPOSR; in ogni altro caso lo si invalida (così VPOSR usa il vpos live).
        if (beamReg != 0x006) {
            beamReadLatchVpos = -1;
            beamReadLatchHpos = -1;
        }

        if (cpuBusReq.read) {
            // ── LETTURA ──────────────────────────────────────────────────────────
            //
            // readCiaData() legge internamente cpuBusReq.uds/lds; per un accesso
            // long-word il 68000 asserisce entrambi, quindi il comportamento è
            // identico a quello di un accesso word (entrambe le CIA vengono selezionate
            // se il Gary ha riportato CIA_BOTH come target).
            int word = isCia
                    ? readCiaData(cpuCurrentRoute.target, addr)
                    : readBusData(cpuCurrentRoute, addr);
            word &= 0xFFFF;

            // Post-lettura del pennello: VPOSR appena letto → arma il latch (vpos live)
            // per la successiva lettura di VHPOSR; VHPOSR appena letto → consuma il latch.
            if (beamReg == 0x004) {
                beamReadLatchVpos = vposFrozen ? frozenVpos : vpos;
                beamReadLatchHpos = vposFrozen ? frozenHpos : hpos;
            } else if (beamReg == 0x006) {
                beamReadLatchVpos = -1;
                beamReadLatchHpos = -1;
            }

            lastBusData = word;
            cpuBusResp.dataIn = word;

        } else {
            // ── SCRITTURA ────────────────────────────────────────────────────────
            //
            // La CPU duplica un byte su entrambe le corsie del bus dati; UDS/LDS
            // seleziona la lane. Per word/byte la parola pilotata è dataOut intero.
            int word = cpuBusReq.dataOut & 0xFFFF;

            if (isCia) {
                // writeCiaData() usa internamente cpuBusReq.uds/lds per selezionare
                // CIA-A (LDS, lane bassa) o CIA-B (UDS, lane alta).
                writeCiaData(cpuCurrentRoute.target, addr, word);
            } else {
                writeBusData(cpuCurrentRoute, addr, word, busUds, busLds);
            }

            // lastBusData riflette i 16 bit effettivamente pilotati sul bus dati
            // in questo ciclo — mai il long-word a 32 bit nella sua interezza.
            lastBusData = word;
        }
    }

    // =========================================================================
    // Lettura/scrittura CIA
    // =========================================================================

    /**
     * Legge un registro CIA.
     *
     * CIA-A risponde agli indirizzi dispari ($BFExxx), byte alto del bus (D15-D8).
     * CIA-B risponde agli indirizzi pari ($BFDxxx), byte basso del bus (D7-D0).
     *
     * Il registro interno CIA è selezionato dai bit A11-A8 dell'indirizzo:
     *   reg = (address >> 8) & 0x0F
     *
     * CIA-A: i dati sono sul byte basso del bus (D7-D0) ma l'indirizzo è dispari.
     *   Il 68000 usa LDS per CIA-A (indirizzi dispari).
     * CIA-B: i dati sono sul byte alto del bus (D15-D8), indirizzo pari.
     *   Il 68000 usa UDS per CIA-B.
     *
     * La parola a 16 bit restituita ha il byte non CIA = 0xFF (pull-up).
     */
    private int readCiaData(Gary.Target target, int address) {
        int reg = (address >> 8) & 0x0F;
        int result = lastBusData; // Pull-up or last data on the bus

        if (target == Gary.Target.CIA_A || target == Gary.Target.CIA_BOTH) {
            // CIA-A: Byte basso (D7-D0) su LDS (indirizzi dispari)
            if (cpuBusReq.lds) {
                int cv = ciaA.readRegister(reg) & 0xFF;
                result = (result & 0xFF00) | cv;
            }
        }
        if (target == Gary.Target.CIA_B || target == Gary.Target.CIA_BOTH) {
            // CIA-B: Byte alto (D15-D8) su UDS (indirizzi pari)
            if (cpuBusReq.uds) {
                int cv = ciaB.readRegister(reg) & 0xFF;
                result = (result & 0x00FF) | (cv << 8);
            }
        }
        return result;
    }

    private void writeCiaData(Gary.Target target, int address, int data) {
        int reg = (address >> 8) & 0x0F;

        if (target == Gary.Target.CIA_A || target == Gary.Target.CIA_BOTH) {
            // CIA-A: Byte basso (D7-D0) su LDS
            if (cpuBusReq.lds) {
                ciaA.writeRegister(reg, (byte)(data & 0xFF));
            }
        }
        if (target == Gary.Target.CIA_B || target == Gary.Target.CIA_BOTH) {
            // CIA-B: Byte alto (D15-D8) su UDS
            if (cpuBusReq.uds) {
                ciaB.writeRegister(reg, (byte)((data >> 8) & 0xFF));
            }
        }
    }

    // =========================================================================
    // Lettura/scrittura bus generale
    // =========================================================================
    /**
     * Legge una word a 16 bit dal bus.
     *
     * Sul bus Amiga, la memoria pilota sempre tutti i 16 bit del bus dati
     * durante un ciclo di lettura: UDS/LDS non sono rilevanti per il target di
     * memoria — è la CPU a selezionare internamente il byte corretto per
     * accessi byte. Il parametro addr è mantenuto solo per accedere ai registri
     * custom tramite route.effectiveAddress (che per CUSTOM_REGS è il registro
     * canonico).
     */
    /** Ultimo valore (word) pilotato sul bus dati. Alimenta la riletura dei registri
     *  custom write-only ({@link #faultyCustomRead}) e i fetch DMA. */
    private int openBusLatch = 0xFFFF;

    /**
     * Valore che la CPU legge da un indirizzo dove non risponde nessun dispositivo.
     *
     * <p>Oracolo WinUAE {@code dummy_get_safe} (memory.cpp): su 68000/68010 il valore
     * <em>non</em> è l'ultimo dato transitato sul bus, ma
     * <ul>
     *   <li><b>0</b> se il codice che accede sta girando <b>da ROM</b>;</li>
     *   <li>altrimenti {@code regs.irc}, cioè la word già nella coda di prefetch
     *       (byte invertiti se l'indirizzo è dispari).</li>
     * </ul>
     *
     * <p>La distinzione conta e non è un dettaglio: prima restituivamo
     * {@link #openBusLatch}, che dopo una scrittura contiene <em>il dato appena
     * scritto</em>. Un test di memoria "scrivi e rileggi" — esattamente quello con cui
     * il Kickstart dimensiona la RAM — riusciva quindi su indirizzi dove non c'è
     * nulla, e il Kickstart 1.2 concludeva che la Slow RAM arrivasse fino a $DBFFFF,
     * ci piazzava lo stack supervisore ($DBFFF2) e moriva con lo schermo giallo appena
     * lo usava. Con la regola di WinUAE il dimensionamento gira da ROM, legge 0, e la
     * RAM fantasma sparisce.
     *
     * <p>Il ramo "da RAM" resta non-zero, che è ciò a cui si appoggiano i programmi che
     * usano lo spazio non mappato come scratch (p.es. Hollywood Poker Pro scrive $FFFF
     * a $219E5E e ne testa la riletura per decidere se caricare la directory).
     */
    private int floatingBusRead(int addr) {
        if (isExecutingFromRom()) return 0;
        // Il valore è l'ultima word PRELEVATA dalla prefetch (WinUAE dummy_get_safe:
        // `v = regs.irc`). Quando `irc` è stato consumato e la prefetch che deve
        // riempirlo è ancora in volo, `ircValid` è false e `irc` contiene un residuo
        // di parecchie word prima: l'ultima word realmente prelevata è allora `ird`.
        // Senza questa guardia una CMPI/CMP con più extension word verso un indirizzo
        // non mappato leggeva il PROPRIO operando immediato invece della word
        // successiva. È il caso della protezione di Pinball Dreams (IPF originale),
        // `CMPI.W #$1111,$00F00000` a $7ABC8: leggendo $1111 il confronto riusciva e
        // il gioco eseguiva la sua routine di autodistruzione (CLR.B (A0)+ a ciclo).
        int v = (cpu.isIRCValid() ? cpu.getIRC() : cpu.getIRD()) & 0xFFFF;
        return ((addr & 1) != 0) ? (((v << 8) | (v >>> 8)) & 0xFFFF) : v;
    }

    /**
     * true se il PC corrente sta dentro una ROM: la finestra del socket Kickstart, il
     * suo mirror a $E00000, oppure la Chip RAM mentre l'overlay è attivo (subito dopo
     * il reset, prima che il Kickstart abbassi OVL).
     */
    private boolean isExecutingFromRom() {
        int pc = cpu.getOpcodeAddress() & 0xFFFFFF;
        if (pc >= 0xF80000) return true;
        if (pc >= 0xE00000 && pc < 0xE80000) return true;
        return pc < 0x080000 && gary.isRomOverlayEnabled();
    }
    private int readBusData(Gary.Route route, int addr) {
        int eff = route.effectiveAddress;
        int alignedEff = eff & ~1;

        int value = switch (route.target) {
            case CHIP_RAM ->
                chipRAM.readWord(alignedEff);
            case KICKSTART_ROM, OVERLAY_ROM ->
                rom.readWord(alignedEff);
            case CUSTOM_REGS ->
                readCustomRegister(eff);   // usa eff, non addr grezzo
            case SLOW_RAM ->
                (slowRAM != null && alignedEff < slowRAM.size())
                        ? slowRAM.readWord(alignedEff) : floatingBusRead(addr);
            case FAST_RAM ->
                (fastRam != null && alignedEff < fastRam.sizeBytes())
                        ? fastRam.ram().readWord(alignedEff) : floatingBusRead(addr);
            case AUTOCONFIG -> {
                // Spazio AutoConfig: nibble di configurazione sui bit alti del bus
                // (D15-D12). La CPU legge sempre su indirizzo pari (UDS): il dato va
                // nel byte alto della word. $FF se nessuna scheda risponde. Risponde
                // la prima scheda della catena ancora non configurata.
                AutoConfigDevice dev = currentAutoConfigDevice();
                int acf = (dev != null) ? dev.peekConfigByte(addr) : 0xFF;
                yield (acf << 8) | 0xFF;
            }
            case HD_CONTROLLER -> {
                // Come la RAM, la memoria pilota sempre tutti i 16 bit: la word va
                // letta all'offset di pagina ALLINEATO; è la CPU a selezionare il
                // byte (UDS/LDS) per gli accessi byte. Senza l'allineamento le
                // letture byte su indirizzo dispari restituirebbero il byte sbagliato.
                HdController hd = hdControllerAt(addr);
                yield (hd != null) ? hd.peekWord((addr & 0xFFFF) & ~1) : floatingBusRead(addr);
            }
            case RTC ->
                readRtcBusData(route, addr);
            default ->
                // Open bus: nessun dispositivo pilota il bus dati. Vedi floatingBusRead().
                floatingBusRead(addr);
        };

        // Latch del valore sul bus dati per la prossima lettura open-bus.
        if (route.target != Gary.Target.OPEN_BUS) openBusLatch = busLatchValue(value, route.uds, route.lds);
        return value;
    }

    /**
     * Valore da latchare in {@link #openBusLatch} per un accesso di bus.
     *
     * Un accesso BYTE lascia sul latch solo il byte trasferito, zero-esteso: vAmiga
     * assegna {@code dataBus} dal valore a 8 bit ({@code peek8: dataBus = READ_CHIP_8(addr)},
     * {@code peekCustom8: dataBus = HI_BYTE/LO_BYTE(peekCustom16(...))}, {@code poke8:
     * dataBus = value}), non dalla word intera. La differenza è osservabile solo tramite
     * un read faulty successivo (registro write-only → {@link #faultyCustomRead}), che
     * ripropaga il latch nel registro: i test vAmigaTS ADDXb/CMPMb leggono il byte di
     * COLOR00 e rendono visibile il byte alto azzerato.
     */
    private static int busLatchValue(int value, boolean uds, boolean lds) {
        if (uds && lds) return value & 0xFFFF;   // accesso word: l'intera word pilota il bus
        if (uds)        return (value >> 8) & 0xFF;
        if (lds)        return value & 0xFF;
        return value & 0xFFFF;
    }

    private int readRtcBusData(Gary.Route route, int addr) {
        int value = 0;
        if (route.uds) {
            value |= (batteryClock.readByte(addr & ~1) & 0xFF) << 8;
        }
        if (route.lds) {
            value |= batteryClock.readByte(addr | 1) & 0xFF;
        }
        return value;
    }

    private void writeBusData(Gary.Route route, int addr, int data, boolean uds, boolean lds) {
        int eff = route.effectiveAddress;
        // Il dato scritto pilota il bus → prossimo open-bus. Una scrittura BYTE lascia sul
        // latch il solo byte (vAmiga poke8: dataBus = value), TRANNE verso i registri custom:
        // lì poke8 delega a pokeCustom16(addr, HI_LO(v,v)), che ri-pilota il bus con il byte
        // duplicato su entrambe le lane → il latch resta la word.
        openBusLatch = (route.target == Gary.Target.CUSTOM_REGS)
                ? (data & 0xFFFF)
                : busLatchValue(data, uds, lds);

        switch (route.target) {
            case CHIP_RAM:
            case OVERLAY_ROM:  // Scritture durante overlay vanno alla Chip RAM
                if (uds && lds) {
                    chipRAM.writeWord(eff & ~1, data);
                } else if (uds) {
                    chipRAM.writeByte(eff & ~1, (data >> 8) & 0xFF);
                } else if (lds) {
                    chipRAM.writeByte(eff | 1, data & 0xFF);
                }
                break;

            case CUSTOM_REGS:
                // I registri custom sono a 16 bit. Per le scritture BYTE il 68000
                // duplica il byte su ENTRAMBE le metà del bus dati, e il chip custom
                // (word-only) latcha l'intera word: il byte finisce quindi replicato
                // su upper E lower. Vale per indirizzo pari (UDS) e dispari (LDS).
                // Cfr. vAmiga Memory::pokeCustom8 → pokeCustom16(addr&0x1FE, HI_LO(v,v)).
                // Bug precedente: la lane UDS scriveva solo (data & 0xFF00) azzerando il
                // lower byte → una "MOVE.B #$40,AUD0VOL" (indirizzo pari $DFF0A8) dava
                // $4000 → volume (bit 0-6) = 0 → effetto sonoro muto (es. lo sparo di
                // Persian Gulf Inferno). Replicando il byte si ottiene $4040 → vol 64.
                if (uds && lds) {
                    writeCustomRegister(eff, data);
                } else if (uds) {
                    int b = (data >> 8) & 0xFF;
                    writeCustomRegister(eff, (b << 8) | b);
                } else if (lds) {
                    int b = data & 0xFF;
                    writeCustomRegister(eff, (b << 8) | b);
                }
                break;

            case SLOW_RAM:
                if (slowRAM != null && (eff & ~1) < slowRAM.size()) {
                    if (uds && lds) {
                        slowRAM.writeWord(eff & ~1, data);
                    } else if (uds) {
                        slowRAM.writeByte(eff & ~1, (data >> 8) & 0xFF);
                    } else if (lds) {
                        slowRAM.writeByte(eff | 1, data & 0xFF);
                    }
                }
                break;

            case FAST_RAM:
                if (fastRam != null && (eff & ~1) < fastRam.sizeBytes()) {
                    RAM fr = fastRam.ram();
                    if (uds && lds) {
                        fr.writeWord(eff & ~1, data);
                    } else if (uds) {
                        fr.writeByte(eff & ~1, (data >> 8) & 0xFF);
                    } else if (lds) {
                        fr.writeByte(eff | 1, data & 0xFF);
                    }
                }
                break;

            case AUTOCONFIG:
                // Il Kickstart assegna l'indirizzo base scrivendo i registri $48/$4A.
                // La CPU scrive byte su indirizzo pari (UDS) → byte alto del bus.
                // Scrive la prima scheda della catena ancora non configurata.
                AutoConfigDevice dev = currentAutoConfigDevice();
                if (dev != null && gary != null) {
                    boolean configured = false;
                    if (uds) configured |= dev.pokeConfigByte(addr & ~1, (data >> 8) & 0xFF);
                    if (lds) configured |= dev.pokeConfigByte(addr | 1, data & 0xFF);
                    if (configured) {
                        // Mappa la scheda all'indirizzo appena assegnato; la scheda
                        // successiva (se presente) comincia ora a rispondere su $E8xxxx.
                        mapConfiguredDevice(dev);
                    }
                }
                break;

            case HD_CONTROLLER:
                if (uds && lds) {
                    HdController hd = hdControllerAt(addr);
                    if (hd != null) hd.pokeWord(addr & 0xFFFF, data);
                }
                break;

            case RTC:
                if (uds) batteryClock.writeByte(addr & ~1, (data >> 8) & 0xFF);
                if (lds) batteryClock.writeByte(addr | 1, data & 0xFF);
                break;
        }
    }

    // =========================================================================
    // Copper write (accesso con restrizioni di sicurezza)
    // =========================================================================

    /**
     * Scrittura da parte del Copper su un registro custom.
     *
     * Restrizioni hardware:
     *   $000–$03E: VIETATI (mai accessibili al Copper)
     *   $040–$07E: accessibili solo se CDANG=1 in COPCON
     *   $080–$1FE: sempre accessibili
     */
    private boolean __inCopperWrite = false; // DEBUG TEMP
    public boolean isInCopperWrite() { return __inCopperWrite; }
    // VPOSR/VHPOSR restituiscono la posizione del pennello qualche CCK IN ANTICIPO rispetto
    // alla posizione corrente del DMA engine (proprietà dell'Agnus reale: i comparatori di
    // posizione sono pipeline-ati). vAmiga Agnus::peekVHPOSR/peekVPOSR restituisce
    // `auto pos = agnus.pos + 5` campionato al ciclo di lettura S4 della CPU.
    //   if (cck >= lineCck) wrap a riga successiva (v++);  if (cck <= 1) usa il vpos VECCHIO.
    // Senza questo anticipo i loop di sincronizzazione CPU↔pennello che attendono HP==0
    // (es. bbusy0 .synccpu3: `andi.w #$FF,VHPOSR; bne`) non escono mai.
    //
    // [Phase B11] Offset = 4 (non 5). La nostra FSM bus trasferisce il dato della
    // lettura custom in S5-S7 (`cpuAccessTransferred`), cioè ~1 CCK DOPO il punto S4 in
    // cui vAmiga campiona la posizione. Per riprodurre il VALORE `pos+5` di vAmiga al
    // nostro punto di campionamento (1 CCK più avanti) l'offset memorizzato dev'essere 4.
    // Misurato sul test bbusy0: +5→6.58%, +4→5.92% (synccpu1 converge: il write 606
    // passa da cck105 a cck170). +3/+6 rompono synccpu3 (buco CCK).
    // CRC golden invariato (832316F5), NorthSouth/IkPlus/LastBattle PASS, frame period-1.
    // NB: a B7 (pre-B9/B10) +5 batteva +4; l'IACK fisso (B9) e l'interrupt-entry a 44 mc
    // (B10) hanno spostato la fase e +4 era corretto a fine speed-work.
    // FASE 4 (2026-07-04, refactoring timing): ri-sweep con il Copper FSM (Fase 2) +
    // Blitter micro-programmi (Fase 3) attivi → l'ottimo si è RISPOSTATO a +5 (bbusy0
    // {+3:3.40%, +4:2.61%, +5:1.45%, +6:75% rompe synccpu3, +7:2.85%}). Il nuovo timing
    // Copper/Blitter ha spostato di nuovo la fase: +5 vince ora. Configurabile via
    // -Damiga.beamahead per future ri-valutazioni (default 5, CRC golden invariato).
    //
    // ⛔ 2026-08-31 — QUESTO E' UNA TARATURA, NON UN MODELLO, ed e' dichiarato.
    // La provenienza e' vAmiga, che non e' un oracolo, e il metodo era uno sweep sul
    // CONTEGGIO DI PIXEL di bbusy0 — una metrica che questo progetto ha imparato a non
    // trattare come una distanza. L'oracolo dice un'altra cosa: `VHPOSR()` (custom.cpp)
    // prende `GETHPOS()` — in cycle-exact e' `agnus_hpos` — e gli applica `incpos()`, che
    // fa `hp++`. Restituisce quindi `agnus_hpos + 1`; poiche' il nostro hpos va un CCK
    // indietro rispetto ad agnus_hpos (vedi INITIAL_HPOS), l'equivalente sarebbe 2.
    // ⛔ PROVATO E REVOCATO: a 2, 3 e 4 `BbusyTest` crolla da 17/0 a 0/17, e a 2
    // `TodTestSuite` da 14/19 a 8/25. bbusy0 e' generato da WinUAE: e' un veto, non un
    // rosso da negoziare.
    // ✅ 2026-09-02 — L'ANELLO E' CHIUSO, E IL 5 HA UNA DECOMPOSIZIONE.
    // La sonda che mancava esiste (`tools/vamiga-oracle/winuae-vhposlog-patch.py`,
    // `WINUAE_VHPOSLOG`, neutra verificata) e la corsa legge i due rami condizionali
    // invece di dedurli: su 146 letture di `bplmod5` e' sempre `cpu_memory_cycle_exact=1`,
    // `eventtab[ev_sync].active=0` e `syncs_stopped=0`, quindi `GETHPOS() == agnus_hpos`
    // e il ritorno e' `agnus_hpos + 1`. Regola confermata.
    // ⛔ Ma il valore che ne segue -- 3, con l'ancora `agnus_hpos = nostro_cck + 2`
    // misurata dal sequencer -- resta REVOCATO: a 3 `BbusyTest` va 17/0 -> 0/17 e
    // `BplModTest` 4/2 -> 0/6. Il 5 non e' pero' piu' un numero da sweep:
    //     5 = 3 (la regola dell'oracolo al SUO istante di lettura)
    //       + 2 (il nostro: la FSM di bus consegna il dato della lettura custom in
    //            S5-S7, l'oracolo campiona a S4 -- due CCK prima).
    // Il residuo ha quindi un nome, ed e' il PUNTO DI CONSEGNA di una lettura custom,
    // non VHPOSR. Toccarlo sposta la fase di ogni test che legge VHPOSR.
    // ✅ Controprova: i marcatori di `synccpu` (COLOR00 $F0F/$606/$A0A/$404) misurati
    // sui due lati con `WINUAE_COLORLOG` e `SynccpuProbe` danno uno scarto COSTANTE di
    // +4 su tutti gli stadi, sia su `bplmod5` sia su `sprdiscpu1`: al valore corrente la
    // nostra `synccpu` riproduce l'oracolo ciclo per ciclo.
    // Vedi docs/vamigats-import-order.md §synccpu.
    private static final int BEAM_AHEAD_CCK =
            Integer.getInteger("amiga.beamahead", 5);

    /**
     * Imposta LOL e con esso la lunghezza della riga corrente (WinUAE
     * {@code setmaxhpos}: {@code maxhpos = maxhpos_short + lol}).
     */
    private void updateLineLength(boolean longLine) {
        lol = longLine;
        maxHpos = longLine ? MAXHPOS_SHORT + 2 : MAXHPOS_SHORT;   // +1 CCK = +2 SC
    }

    /** Posizione H (in CCK) restituita da VHPOSR: hpos corrente + BEAM_AHEAD_CCK, con wrap di riga. */
    private int beamPosHAhead(int hLores) {
        int lineCck = maxHpos >> 1;                 // 227, o 228 sulla riga lunga NTSC
        int cck = (hLores >> 1) + BEAM_AHEAD_CCK;
        if (cck >= lineCck) cck -= lineCck;
        return cck;
    }

    // ── ERSY / syncs_stopped ────────────────────────────────────────────────────
    // Vero se BPLCON0 ha ERSY (bit 1) attivo. Non emuliamo genlock, quindi ERSY
    // implica sempre "sync fermo" (WinUAE issyncstopped: (con0&2) && !genlock).
    private boolean ersyActive() {
        return denise != null && (denise.getBplcon0() & 0x0002) != 0;
    }

    /**
     * Vpos che VPOSR/VHPOSR riportano <b>mentre il freeze è in corso</b>: quello vero, che
     * non avanza più perché {@code updateBeam} non lo incrementa. Oracolo: {@code GETVPOS()}
     * restituisce {@code vpos} e {@code incpos()} esce subito con {@code syncs_stopped}.
     */
    private int frozenReadVpos() {
        return vposFrozen ? frozenVpos : vpos;
    }

    /** Vpos usato dalle letture VPOSR/VHPOSR fuori dal freeze. */
    private int beamReadVpos() {
        return beamReadLatchVpos >= 0 ? beamReadLatchVpos : (vposFrozen ? frozenVpos : vpos);
    }

    /** Hpos usato dalle letture VPOSR/VHPOSR fuori dal freeze. */
    private int beamReadHpos() {
        return beamReadLatchHpos >= 0 ? beamReadLatchHpos : (vposFrozen ? frozenHpos : hpos);
    }

    /** Vpos restituito da VPOSR/VHPOSR per la posizione 5 CCK in anticipo. */
    private int beamPosVAhead(int vLines, int hLores) {
        int lineCck = maxHpos >> 1;
        int cck = (hLores >> 1) + BEAM_AHEAD_CCK;
        int v = vLines;
        if (cck >= lineCck) {
            cck -= lineCck;
            v = vLines + 1;
            int frameMaxV = (field == 0) ? maxVpos : (maxVpos - 1);
            if (v > frameMaxV) v = 0;               // wrap di frame (vAmiga: v > vMax → 0)
        }
        // "In cycle 0 and 1, return old posv": all'inizio riga il vpos sul chip non è
        // ancora incrementato, quindi si usa il vpos vecchio per il campo verticale.
        return (cck <= 1) ? vLines : v;
    }
    /**
     * Scrittura di un registro custom fatta dal <b>Copper</b>.
     *
     * <p>⛔ <b>Qui non c'è nessun filtro sull'indirizzo, e non deve tornarci.</b> Quale
     * registro il Copper possa toccare lo decide {@code Copper.isIllegalAddress()}, che è
     * l'unico sito della regola — come nell'oracolo, dove è la sola
     * {@code test_copper_dangerous()}:
     * <pre>
     * addr &lt; ((copcon &amp; 2) ? (ecs_agnus ? 0 : 0x40) : 0x80)
     * </pre>
     * cioè con CDANG=1 su <b>ECS non è vietato niente</b>, su OCS solo {@code < $40}; con
     * CDANG=0 tutto {@code < $80}. Ed è una regola con un effetto: l'indirizzo vietato
     * <b>ferma il Copper</b>, non gli fa saltare una scrittura.
     *
     * <p>⚠️ Qui stava un <b>doppione</b> della regola, nella forma sbagliata: un
     * {@code if (regAddr < 0x040) return;} incondizionato, che su ECS con CDANG buttava via
     * scritture del tutto lecite. Lo smaschera {@code Agnus/Registers/VPOS/probe11÷13}, dove
     * il Copper scrive VPOSW ($02A) per accorciare il fotogramma: la scrittura spariva, i
     * nostri fotogrammi restavano lunghi e a fine corsa il pennello era 129 righe fuori.
     */
    public void copperWrite(int regAddr, int value) {
        regAddr &= 0x1FE;
        __inCopperWrite = true;
        try { writeCustomRegister(regAddr, value); } finally { __inCopperWrite = false; }
    }

    // =========================================================================
    // Lettura registri custom
    // =========================================================================

    /** package-private for testing */
    int getBplPt(int idx) { return bplPt[idx]; }

    public int readCustomRegister(int regAddr) {
        return readCustomRegisterImpl(regAddr);
    }

    /**
     * Vero mentre è in corso una {@link #peekCustomRegister(int)}: sopprime gli effetti
     * collaterali che una lettura di registro custom ha sull'hardware vero.
     *
     * <p>⛔ Non è un dettaglio cosmetico. {@link #faultyCustomRead} — la lettura di un
     * write-only OCS — <b>riscrive il registro</b> col valore del bus flottante, ed è
     * comportamento voluto (vAmiga {@code peekCustomFaulty16}). Ma {@link #peekByte} e
     * {@link #peekWord} sono gli accessori del disassemblatore e del debugger: un dump di
     * memoria che attraversasse {@code $DFFxxx} riscriveva registri sprite e palette
     * dell'immagine che stava osservando. Le CIA la promessa la mantenevano già
     * ({@code peekCiaData}); i custom no.
     */
    private boolean peeking = false;

    /**
     * Lettura di un registro custom <b>priva di effetti collaterali</b>, per
     * disassemblatore, debugger e harness d'indagine. Copre i tre siti che mutano:
     * la lettura "faulty" dei write-only, i registri strobe e i tre registri di Paula
     * che consumano stato ({@code Paula.peekRegister}).
     */
    public int peekCustomRegister(int regAddr) {
        boolean saved = peeking;
        peeking = true;
        try {
            return readCustomRegisterImpl(regAddr);
        } finally {
            peeking = saved;
        }
    }

    /**
     * Lettura di un registro custom write-only OCS ("faulty"): riproduce
     * {@code Memory::peekCustomFaulty16} di vAmiga — la lettura restituisce
     * l'ultimo valore sul bus dati (floating) e, come effetto collaterale,
     * riscrive quel valore NEL registro ({@code pokeCustom16(addr, dataBus)}).
     */
    /**
     * Se true, le letture "faulty" restituiscono (e ri-scrivono) 0 invece del valore
     * flottante del bus dati. Serve SOLO ai test di regressione vAmigaTS: i riferimenti
     * {@code .raw} sono rigenerati con WinUAE configurato con la stessa neutralizzazione
     * ({@code custom_wget_1}: {@code v = 0} invece di {@code regs.chipset_latch_rw}),
     * così il confronto isola il TIMING e non i valori del floating bus, che sono
     * comportamento analogico indefinito e differiscono fra emulatori.
     *
     * <p>Default false: i giochi mantengono il floating bus reale (necessario, vedi
     * i fix storici su COPJMP1 / NBCD / ADDXb).
     */
    public static volatile boolean neutralFloatingBus = false;

    private int faultyCustomRead(int regAddr) {
        int busVal = neutralFloatingBus ? 0 : (openBusLatch & 0xFFFF);
        if (!peeking) writeCustomRegister(regAddr, busVal);
        return busVal;
    }

    private int readCustomRegisterImpl(int regAddr) {
        regAddr &= 0x1FE;

        // DENISEID ($07C) su OCS: il registro NON ESISTE, e la lettura non vale 0xFFFF — e'
        // una lettura write-only "faulty" come per ogni altro registro assente. Lo dice
        // WinUAE (custom.cpp, DENISEID()): su OCS Denise con un 68000 compatible o
        // cycle-exact la funzione alza *missing e il chiamante fa `goto writeonly`, cioe'
        // prende la stessa strada di un registro solo-scrittura. Restituire 0xFFFF accendeva
        // tutte e 16 le barre di vAmigaTS Denise/Misc/Id/deniseid.
        // ⚠️ Il README di vAmigaTS avverte che sull'A500 vero il valore NON e' deterministico
        // ("in most cases 0xFFFF, but not always"): e' proprio la firma di un bus flottante.
        if (regAddr == 0x07C && denise != null && !denise.isECS()) {
            return faultyCustomRead(regAddr);
        }

        // Denise (registri leggibili: JOY0/1DAT, CLXDAT, DENISEID, BPLCONx su ECS)
        if (regAddr == 0x00A || regAddr == 0x00C || regAddr == 0x00E || regAddr == 0x07C
                || (regAddr >= 0x100 && regAddr <= 0x106) || regAddr == 0x10C
                || (regAddr >= 0x110 && regAddr <= 0x11A)) {
            return denise != null ? denise.readRegister(regAddr) : 0xFFFF;
        }

        // Sprite pos/ctl/data + registri colore ($140-$1BE). Su ECS Denise i colori
        // sono rileggibili (comportamento diagnostico attuale); su OCS sono write-only
        // → vAmiga peekCustomFaulty16 (Memory.cpp): la lettura restituisce il dato sul
        // bus (floating) E riscrive quel valore NEL registro. È ciò che rende visibili
        // i colori "spazzatura" quando un handler fa una RMW su un custom write-only
        // (es. nbcd (a4) con a4=$DFF181 nei test vAmigaTS CPU/Timing).
        if (regAddr >= 0x140 && regAddr <= 0x1BE) {
            if (denise != null && denise.isECS()) {
                return denise.readRegister(regAddr);
            }
            return faultyCustomRead(regAddr);
        }

        // Paula
        if (regAddr == 0x008 || (regAddr >= 0x010 && regAddr <= 0x01E)
                || (regAddr >= 0x0A0 && regAddr <= 0x0DF)) {
            if (paula == null) return 0xFFFF;
            return peeking ? paula.peekRegister(regAddr) : paula.readRegister(regAddr);
        }

        // Strobe Denise
        if (regAddr == 0x038 || regAddr == 0x03A || regAddr == 0x03C) {
            if (denise != null && !peeking) denise.readRegister(regAddr);
            return 0;
        }

        switch (regAddr) {
            case 0x000: // BLTDDAT
                return lastBusData;
            case 0x002: // DMACONR
            case 0x096: {
                int res = dmacon & 0x07FF; // Bit 10-0 leggibili
                if (blitter.isBusy()) res |= 0x4000; // BBUSY
                if (blitter.isZero()) res |= 0x2000; // BZERO
                return res;
            }
            case 0x004: { // VPOSR
                if (syncsStopped) {
                    // Freeze ERSY: nessun anticipo, nessun avanzamento. Vedi VHPOSR sotto.
                    int result = agnusIdBits() << 8;
                    if ((frozenReadVpos() & 0x100) != 0) result |= 0x0001;
                    if (field == 0) result |= 0x8000;
                    return result;
                }
                int v = beamReadVpos();
                int h = beamReadHpos();
                // VPOSR:
                // Bit 15: LOF (Long Frame)
                // Bit 14-8: Chip ID
                // Bit 0: V8 (Vertical position bit 8)
                // La posizione restituita è 5 CCK in anticipo (come Agnus reale,
                // cfr. vAmiga peekVPOSR/peekVHPOSR: `pos = agnus.pos + 5`).
                int vAhead = beamPosVAhead(v, h);
                int result = agnusIdBits() << 8;
                if ((vAhead & 0x100) != 0) result |= 0x0001; // V8
                // LOF=1 sui frame lunghi. Il bit alterna anche in non-interlace,
                // pur senza spostare verticalmente il display.
                if (field == 0) result |= 0x8000;
                return result;
            }
            case 0x006: { // VHPOSR
                if (syncsStopped) {
                    // ⛔ Sotto freeze HP vale **0**, non la posizione latchata al primo
                    // accesso, e non c'è l'anticipo di 5 CCK. Oracolo: `setsyncstoppos()`
                    // azzera `agnus_hpos` a ogni cck, `GETHPOS()` lo restituisce così com'è
                    // quando `syncs_stopped`, e `incpos()` esce senza toccare niente.
                    // Lo misura `ersy2`: i suoi `expected` passano da $40BF,$40C9,$40D3,$40DD
                    // — la posizione che corre — a **$4000** ripetuto, appena il confine di
                    // riga fa scattare il freeze.
                    return (frozenReadVpos() & 0xFF) << 8;
                }
                int v = beamReadVpos();
                int h = beamReadHpos();
                // VHPOSR: bit 15-8 = VP7-VP0, bit 7-0 = HP8-HP1 (hpos in CCK).
                // Posizione 5 CCK in anticipo (vedi VPOSR sopra). Il valore H avanzato
                // determina sia il campo HP sia (con wrap a fine riga) il vpos restituito.
                return ((beamPosVAhead(v, h) & 0xFF) << 8) | (beamPosHAhead(h) & 0xFF);
            }
            case 0x028: // REFPTR (read-back)
                return refptr;
            case 0x108: return bpl1mod & 0xFFFF;
            case 0x10A: return bpl2mod & 0xFFFF;
            case 0x0E0: case 0x0E4: case 0x0E8: case 0x0EC: case 0x0F0: case 0x0F4: {
                int bidx = (regAddr - 0x0E0) / 4;
                return (bplPt[bidx] >> 16) & 0x7;
            }
            case 0x0E2: case 0x0E6: case 0x0EA: case 0x0EE: case 0x0F2: case 0x0F6: {
                int bidx = (regAddr - 0x0E2) / 4;
                return bplPt[bidx] & 0xFFFF;
            }
            case 0x120: case 0x124: case 0x128: case 0x12C: case 0x130: case 0x134: case 0x138: case 0x13C: {
                int sidx = (regAddr - 0x120) / 4;
                return (sprPt[sidx] >> 16) & 0x7;
            }
            case 0x122: case 0x126: case 0x12A: case 0x12E: case 0x132: case 0x136: case 0x13A: case 0x13E: {
                int sidx = (regAddr - 0x122) / 4;
                return sprPt[sidx] & 0xFFFF;
            }
            // INTENAR / INTREQR letti tramite Paula
            case 0x09A:
                return paula != null ? paula.readRegister(0x01C) : 0xFFFF;
            case 0x09C:
                return paula != null ? paula.readRegister(0x01E) : 0xFFFF;
            case 0x02E: // COPCON (write-only su OCS, ma restituisce lo stato corrente per compatibilità test)
                // Su hardware OCS COPCON è write-only e la lettura restituisce bus noise.
                // Restituiamo il valore corrente del registro per supportare test di diagnostica
                // che verificano lo stato del CDANG bit dopo il reset.
                return copper.isDanger() ? 0x0002 : 0x0000;
            case 0x1DC: // BEAMCON0 (ECS)
                return beamcon0;
            default:
                // Registri custom write-only / non mappati: la lettura restituisce il
                // valore floating del bus dati (e lo riscrive nel registro), come vAmiga
                // peekCustom16 → peekCustomFaulty16 (default). Prima ritornavamo un $FFFF
                // fisso: sbagliato per i test che leggono uno strobe write-only e ne usano
                // il valore (es. vAmigaTS BCHG `move.w COPJMP1(a1),d0` → d0 = floating bus
                // → il numero di bit toggolato dalla bchg successiva dipende da d0). Anche
                // l'effetto strobe della poke-back è corretto: accedere a COPJMP1 (read o
                // write) fa saltare il Copper sull'hardware reale.
                return faultyCustomRead(regAddr);
        }
    }

    // =========================================================================
    // Scrittura registri custom
    // =========================================================================

    public void writeCustomRegister(int regAddr, int value) {
        regAddr &= 0x1FE;

        if (diagBb) {
            int cck = hpos >> 1;
            if (regAddr == 0x180 && diagBbCol.size() < diagBbCap)
                diagBbCol.add("COL v=" + vpos + " h=" + cck + " " + Integer.toHexString(value & 0xFFF));
            else if (regAddr == 0x09C && (value & 0x8000) != 0 && diagBbInt.size() < diagBbCap)
                diagBbInt.add("INT v=" + vpos + " h=" + cck + " bits=" + Integer.toHexString(value & 0x7FFF));
            else if (regAddr == 0x058 && diagBbBlt.size() < diagBbCap) {
                int bv = (value >> 6) & 0x3FF, bh = value & 0x3F;
                if (bv == 0) bv = 1024;
                if (bh == 0) bh = 64;
                diagBbBlt.add("BLT v=" + vpos + " h=" + cck + " " + bh + "x" + bv);
            }
        }

        /*if (regAddr == 0x180) {
            int oldVal = denise != null ? denise.getPaletteColor(0) : 0;
        }*/


        // Blitter
        if (regAddr >= 0x040 && regAddr <= 0x074) {
            blitter.writeRegister(regAddr, value);
            return;
        }

        // Copper
        if (regAddr >= 0x080 && regAddr <= 0x08A) {
            copper.writeRegister(regAddr, value);
            return;
        }

        // Denise
        if (regAddr == 0x036 || regAddr == 0x038 || regAddr == 0x03A || regAddr == 0x03C || regAddr == 0x03E || regAddr == 0x098
                || regAddr == 0x104 || regAddr == 0x106 || regAddr == 0x10C
                || (regAddr >= 0x110 && regAddr <= 0x11A)
                || (regAddr >= 0x140 && regAddr <= 0x1BE)) {
            // SPRxPOS/SPRxCTL ($140-$17E) non riguardano solo Denise: VSTART/VSTOP
            // vivono in AGNUS e pilotano la macchina a stati del DMA sprite. Una
            // scrittura da Copper/CPU deve quindi aggiornare anche sprVStart/sprVStop
            // (WinUAE SPRxPOS()/SPRxCTL() → sprstartstop + SPRxCTLPOS). Senza questo
            // i giochi che (ri)posizionano gli sprite scrivendo POS/CTL dalla Copper
            // list — invece che nel descrittore in Chip RAM — non li vedono mai
            // partire: il solo fetch POS/CTL alla riga 25 legge un descrittore nullo
            // e lo sprite resta spento per tutto il frame (es. Superfrog).
            if (regAddr >= 0x140 && regAddr <= 0x17E) {
                int sidx = (regAddr - 0x140) >> 3;
                int sub  = (regAddr - 0x140) & 7;
                if (sub == 0)      writeSPRxPOS(sidx, value);
                else if (sub == 2) writeSPRxCTL(sidx, value);
            }
            if (denise != null) denise.writeRegister(regAddr, value);
            return;
        }

        // Paula (include POTGO $034)
        if (regAddr == 0x024 || regAddr == 0x026
                || regAddr == 0x030 || regAddr == 0x032 || regAddr == 0x034
                || regAddr == 0x07E
                || regAddr == 0x09A || regAddr == 0x09C || regAddr == 0x09E
                || (regAddr >= 0x0A0 && regAddr <= 0x0DF)) {
            // INTENA ($09A) / INTREQ ($09C) arrivano a Paula SUBITO: il mezzo CCK di
            // ritardo non è della scrittura, è della sola copia da cui si calcola l'IPL,
            // e vive in Paula (INTREQ_SHADOW_DELAY_MC). Vedi Paula.intreq2.
            if (paula != null) paula.writeRegister(regAddr, value);
            return;
        }

        // Strobe Denise
        if (regAddr == 0x038 || regAddr == 0x03A || regAddr == 0x03C || regAddr == 0x03E) {
            if (denise != null) denise.writeRegister(regAddr, value);
            return;
        }

        // Registri Agnus
        switch (regAddr) {
            case 0x020: // DSKPTH
                dskpt = (dskpt & 0x0000FFFF) | ((value << 16) & dmaPtrHiMask);
                if (paula != null) paula.setDiskDmaPointerHigh(value & 0x001F);
                break;
            case 0x022: // DSKPTL
                dskpt = (dskpt & dmaPtrHiMask) | (value & 0xFFFE);
                if (paula != null) paula.setDiskDmaPointer(dskpt);
                break;
            case 0x02A: // VPOSW
                // Bit 15 = LOF
                if ((value & 0x8000) != 0) field = 0; else field = 1;
                vpos = (vpos & 0xFF) | ((value & 1) << 8);
                triggerVpos = vpos;
                // "LOL is always reset when VPOSW is written to. Implemented in all
                // NTSC Agnus versions and ECS/AGA Agnus in NTSC mode" (WinUAE
                // custom.cpp, VPOSW): la riga corrente torna corta.
                if (lol) updateLineLength(false);
                break;
            case 0x02C: // VHPOSW
                vpos = (vpos & 0x100) | ((value >> 8) & 0xFF);
                triggerVpos = vpos;
                hpos = (value & 0xFF) << 1;
                break;
            case 0x08C: // COPINS
                // Copper instruction fetch strobe - handled by Agnus/Copper
                break;
            case 0x1FE: // NO-OP
                break;
            case 0x100: // BPLCON0 (some writes might come through general bus)
                setBPLCON0(value, getCpuPc());
                break;
            case 0x102: // BPLCON1
                setBPLCON1(value);
                break;
            case 0x02E: // COPCON
                copper.writeRegister(0x02E, value);
                break;

            case 0x08E: // DIWSTRT
                diwStrt = value;
                diwVStart = (diwStrt >> 8) & 0xFF;
                // `calcvdiw()` → need_vdiw_check: il confronto verticale rigira SUBITO,
                // a riga in corso. Vedi recheckBitplaneVerticalWindow().
                recheckBitplaneVerticalWindow();
                if (denise != null) denise.writeRegister(0x08E, value);
                break;
            case 0x090: // DIWSTOP
                diwStop = value;
                diwVStop = (diwStop >> 8) & 0xFF;
                if ((diwStop & 0x8000) == 0) diwVStop += 256;
                recheckBitplaneVerticalWindow();
                if (denise != null) denise.writeRegister(0x090, value);
                break;

            case 0x092: // DDFSTRT
                ddfStrt = value;
                ddfCckStart = ddfStrt & ddfMask();
                noteDdfStrtWrite();
                break;
            case 0x094: // DDFSTOP
                ddfStop = value;
                ddfCckStop = decodeDdfStopCck(ddfStop);
                noteDdfStopWrite();
                break;

            case 0x096: // DMACON
                setDMACON(value, getCpuPc());
                break;

            case 0x028: // REFPTR
                refptr = value & refptrMask();
                break;

            // Puntatori bitplane (BPLxPTH/BPLxPTL)
            case 0x0E0: case 0x0E4: case 0x0E8: case 0x0EC: case 0x0F0: case 0x0F4: {
                int bidx = (regAddr - 0x0E0) / 4;
                if (bplPointerWriteIsLost(bidx)) break;
                bplPt[bidx] = (bplPt[bidx] & 0x0000FFFF) | ((value << 16) & dmaPtrHiMask);
                bplPtrInternal[bidx] = (bplPtrInternal[bidx] & 0x0000FFFF) | (bplPt[bidx] & dmaPtrHiMask);
                break;
            }
            case 0x0E2: case 0x0E6: case 0x0EA: case 0x0EE: case 0x0F2: case 0x0F6: {
                int bidx = (regAddr - 0x0E2) / 4;
                if (bplPointerWriteIsLost(bidx)) break;
                bplPt[bidx] = (bplPt[bidx] & dmaPtrHiMask) | (value & 0xFFFE);
                bplPtrInternal[bidx] = bplPt[bidx];
                break;
            }

            // BPL1MOD/BPL2MOD: il registro vivo si aggiorna sempre; lo stadio d'ingresso
            // della pipe SOLO se a scrivere è la CPU, perché nell'oracolo è la sola delle
            // due a girare PRIMA del campionamento dello stesso CCK. Vedi bpl1modFetch.
            case 0x108: // BPL1MOD
                bpl1mod = (short) (value & 0xFFFE);
                if (!__inCopperWrite) bpl1modStage = bpl1mod;
                break;
            case 0x10A: // BPL2MOD
                bpl2mod = (short) (value & 0xFFFE);
                if (!__inCopperWrite) bpl2modStage = bpl2mod;
                break;

            // Puntatori sprite (SPRxPTH/SPRxPTL). SPRxPT è il puntatore DMA LIVE:
            // una scrittura (CPU o Copper) cambia da dove il prossimo accesso DMA
            // sprite preleva, esattamente come i bitplane qui sopra (HW Agnus non ha
            // spr[num].pt = ...). Senza l'aggiornamento di sprPtrInternal i giochi
            // che riposizionano gli sprite via Copper a inizio frame (es. Silkworm)
            // prelevano dati stantii e gli sprite non compaiono.
            case 0x120: case 0x124: case 0x128: case 0x12C:
            case 0x130: case 0x134: case 0x138: case 0x13C: {
                int sidx = (regAddr - 0x120) / 4;
                sprPt[sidx] = (sprPt[sidx] & 0x0000FFFF) | ((value << 16) & dmaPtrHiMask);
                sprPtrInternal[sidx] = (sprPtrInternal[sidx] & 0x0000FFFF) | (sprPt[sidx] & dmaPtrHiMask);
                break;
            }
            case 0x122: case 0x126: case 0x12A: case 0x12E:
            case 0x132: case 0x136: case 0x13A: case 0x13E: {
                int sidx = (regAddr - 0x122) / 4;
                sprPt[sidx] = (sprPt[sidx] & dmaPtrHiMask) | (value & 0xFFFE);
                sprPtrInternal[sidx] = sprPt[sidx];
                break;
            }

            case 0x1DC: // BEAMCON0 (ECS-only): su OCS la scrittura è ignorata.
                if (beamcon0Writable()) {
                    final int prev = beamcon0;
                    beamcon0 = value;
                    // `BEAMCON0()` dell'oracolo chiama `init_beamcon0()` SUBITO quando il
                    // valore cambia, e li' `maxvpos` e' riassegnato nel CCK stesso della
                    // scrittura. Il resto del modo (lol, fase del vsync) segue da se',
                    // perche' e' letto da beamNtsc a ogni riga.
                    if (value != prev) applyBeamcon0();
                }
                break;

            case 0x1E4: // DIWHIGH (ECS-only): estende DIWSTRT/STOP. No-op su OCS.
                writeDIWHIGH(value);
                break;
        }
    }

    // =========================================================================
    // Notifiche da chip interni
    // =========================================================================

    /**
     * Chiamato dal Blitter quando ha completato l'operazione.
     * Genera l'interrupt BLIT (bit 6 di INTREQ, IPL 3).
     */
    public void blitterFinished() {
        if (paula != null) paula.assertBlitInterrupt();
    }

    // =========================================================================
    // Reset
    // =========================================================================

    public void reset()      { hardReset(); }
    public void hardReset()  { internalReset(true);  }
    public void softReset()  { internalReset(false); }

    private void internalReset(boolean hardware) {
        if (hardware) {
            masterClock = 0;
            vpos = 0;
            triggerVpos = 0;
            hpos = INITIAL_HPOS;         // la prima riga dura 1 CCK: vedi INITIAL_HPOS
            frameCount = 1;
            // Gli impulsi TOD sono calcolati per la riga in corso: quella riga non c'è più.
            todRiseHposA = todFallHposA = todRiseHposB = todFallHposB = -1;
        }
        Arrays.fill(trigger, NEVER);
        Arrays.fill(eventIds, EventID.EVENT_NONE);
        activeSlotMask = 0L;
        nextTrigger = NEVER;
        cpuBusIdle = false; // forza un azzeramento handshake al primo ciclo idle post-reset
        // Ri-arma il tick E-clock CIA: lo scheduler è appena stato azzerato e l'evento
        // SLOT_CIAA non si auto-rischedula più (lo faceva il polling). Senza questo le
        // CIA si fermerebbero dopo un'istruzione CPU RESET (softReset).
        // ⛔ +8 e non +10: è la stessa fase dell'arm in costruzione, e va cambiata
        // insieme a quella. Vedi la nota estesa lì e CiaEClockPhaseTest.
        scheduleAbs(EventSlot.SLOT_CIAA, masterClock + CIA_ANCHOR, EventID.CIA_EXECUTE);
        // Ri-arma la produzione dei sample audio (SLOT_CH0): come SLOT_CIAA, l'evento
        // si auto-rischedula ma lo scheduler è appena stato azzerato.
        if (paula != null) {
            scheduleAbs(EventSlot.SLOT_CH0, masterClock + audioSampleInterval(), EventID.AUDIO_MIX);
        }
        clearRegChanges();
        syncEvent = 0;

        dmacon = 0;
        bitplaneVerticalActive = false;
        updateDmaFlags();
        bitplaneDmaEnabledLateThisFrame = false;
        lastCompletedFrameHadLateBitplaneDma = false;
        // LOF=0 al reset, cioè frame CORTO (312 righe PAL, 262 NTSC): WinUAE
        // `custom_reset` fa `lof_store = lof_display = 0;`. Il frame diventa lungo quando
        // il software scrive VPOSW con il bit 15, ed è così che il bootblock del Kickstart
        // ci passa. Partivamo invece dal frame lungo — il default di vAmiga — e finché
        // nessuno scriveva VPOSW l'intera fase CPU↔pennello del boot era spostata:
        // misurato fotogramma per fotogramma contro l'oracolo in
        // docs/vamigats-paula-audio-plan.md §13.3.
        field = 1;
        vsyncActive = false;
        displayRowShift = 0;
        syncsStopped = false;
        updateLineLength(false);             // LOL: si riparte sempre da riga corta
        vposFrozen = false; frozenVpos = 0; frozenHpos = 0;
        beamReadLatchVpos = -1; beamReadLatchHpos = -1;
        cpuAccessTransferred = false; copperBusProcessed = false;
        cpuRouteCacheValid = false;
        cpuCurrentRoute = null;
        bprun = 0; bprunCycle = 0; ddfStopping = 0; fetchUnitHires = false;
        ddfEnableOn = false; ddfLimitIn = false; ddfLimitOut = false;
        hwiOld = false; dmaconBpl2 = false;
        dmaconBplSeq = false; dmaconBplPipe = false;
        spriteDmaSeq = false; spriteDmaPipe = false; spriteDmaOnDecisionCck = -1;
        diwStrt = 0; diwStop = 0; ddfStrt = 0; ddfStop = 0;
        diwVStart = 0; diwVStop = 0; ddfCckStart = 0; ddfCckStop = 0;
        ddfStrtVal = 1; ddfStrtValOld = 1; ddfStopVal = 0; ddfStopValOld = 0;
        ddfStrtChangeCck = Long.MIN_VALUE; ddfStopChangeCck = Long.MIN_VALUE;
        bplcon1 = 0; scrollOdd = 0; scrollEven = 0;
        bitplaneVerticalActive = false;
        Arrays.fill(bplPt, 0); Arrays.fill(sprPt, 0);
        Arrays.fill(bplPtrInternal, 0); Arrays.fill(sprPtrInternal, 0);
        Arrays.fill(sprDmaEnabled, false);   // tutti gli sprite in fase POS/CTL
        Arrays.fill(sprVStart, 0);
        Arrays.fill(sprVStop, 0);
        bpl1mod = 0; bpl2mod = 0;
        bpl1modFetch = 0; bpl2modFetch = 0;
        bpl1modStage = 0; bpl2modStage = 0;
        lastCiaAPra = -1;
        lastCiaADdra = -1;
        if (copper  != null) copper.reset();
        if (blitter != null) blitter.reset();
        if (denise  != null) denise.reset();
        if (paula   != null) paula.reset();

        // Le CIA vengono resettate sia all'hard reset che via istruzione CPU RESET.
        if (ciaA != null) ciaA.reset();
        if (ciaB != null) ciaB.reset();

        if (hardware) {
            // Gary reset solo su hard reset (power-on).
            // L'istruzione RESET della CPU non deve resettare Gary né il flip-flop OVL:
            // su hardware reale la CPU RESET resetta solo i chip periferici sul bus,
            // non il gate array Gary. Il flip-flop OVL si azzera solo alla prima scrittura
            // sul bus dopo il power-on, oppure viene controllato dal CIA-A PA0.
            if (gary != null) gary.reset();
        }
        if (df0  != null) df0.reset();
        if (df1  != null) df1.reset();
        if (df2  != null) df2.reset();
        if (df3  != null) df3.reset();

        // Fast RAM: l'istruzione RESET della CPU resetta anche il bus Zorro, quindi
        // la scheda torna in attesa di AutoConfig (sia su hard che su soft reset) e
        // il Kickstart la riconfigura. Smappiamo la regione Fast RAM finché non
        // verrà riassegnato un indirizzo base; lo spazio di AutoConfig resta attivo.
        if (fastRam != null) {
            fastRam.reset();
            if (gary != null) {
                gary.unmapFastRam();
            }
        }

        // Controller hard disk: come la Fast RAM, l'istruzione RESET resetta il bus
        // Zorro e le schede tornano in attesa di AutoConfig. Smappiamo le pagine
        // finché non verranno riassegnati gli indirizzi base.
        if (!hdControllers.isEmpty()) {
            for (HdController c : hdControllers) c.reset();
            if (gary != null) gary.unmapHdControllers();
        }

        if (gary != null) updateAutoconfigPresence();
    }

    // =========================================================================
    // DIW check
    // =========================================================================

    /**
     * Verifica se la posizione (hpos, vpos) è all'interno della Display Window.
     *
     * @param hpos Posizione orizzontale in system clock (SC)
     * @param vpos Posizione verticale in righe
     */
    public boolean isInsideDIW(int hpos, int vpos) {
        // Horizontal: H[7:0] from the register is directly in SC (system clock) units.
        // hstart = {0, H[7:0]} → plain 8-bit value (bit8=0, range 0..255)
        // hstop  = {1, H[7:0]} → +256 (bit8=1, range 256..511)
        int hstart = diwStrt & 0xFF;
        int hstop  = (diwStop & 0xFF) + 256;
        // Vertical: vstop bit8 = NOT bit15 of DIWSTOP register
        int vstart = (diwStrt >> 8) & 0xFF;
        int vstop  = (diwStop >> 8) & 0xFF;
        if ((diwStop & 0x8000) == 0) vstop += 256;

        return hpos >= hstart && hpos < hstop && vpos >= vstart && vpos < vstop;
    }

    // =========================================================================
    // DMA enable flags
    // =========================================================================

    /**
     * Ricalcola i flag DMA cache da dmacon. Va chiamato ad ogni modifica di
     * dmacon. bitplaneDmaActive dipende anche da bitplaneVerticalActive, quindi
     * viene riaggiornato sia qui sia in updateBitplaneVerticalWindow().
     */
    private void updateDmaFlags() {
        final boolean sprWas = spriteDmaEnabled;
        dmaMasterEnabled   = (dmacon & 0x0200) != 0;
        bitplaneDmaEnabled = dmaMasterEnabled && (dmacon & 0x0100) != 0; // BPLEN
        spriteDmaEnabled   = dmaMasterEnabled && (dmacon & 0x0020) != 0; // SPREN
        if (!sprWas && spriteDmaEnabled) {
            // Vedi spriteDmaOnDecisionCck: il CCK di DECISIONE che questa accensione
            // rende "deciso ma non allocato". L'oracolo lo scrive come
            // `sprite_dma_change_cycle_on = get_cycles() + CYCLE_UNIT`.
            spriteDmaOnDecisionCck = (masterClock >> 1) + 1;
        }
        diskDmaEnabled     = dmaMasterEnabled && (dmacon & 0x0010) != 0; // DSKEN
        audioDmaEnabled    = dmaMasterEnabled && (dmacon & 0x000F) != 0; // AUDxEN
        copperDmaEnabled   = dmaMasterEnabled && (dmacon & 0x0080) != 0; // COPEN
        blitterDmaEnabled  = dmaMasterEnabled && (dmacon & 0x0040) != 0; // BLTEN
        bitplaneDmaActive  = bitplaneDmaEnabled && bitplaneVerticalActive;
    }

    private boolean isBitplaneDMAActive() {
        return bitplaneDmaActive;
    }

    /**
     * Quanto {@link #triggerVpos} — la riga dell'oracolo — precede il wrap di
     * {@code hpos}, in unità di mezzo-CCK: <b>3 CCK</b>.
     *
     * <p>⛔ <b>Misurato sui due lati il 2026-09-06</b> (sonde {@code WINUAE_COPLOG} e
     * {@code WINUAE_SPRLOG} contro le stesse da noi). La griglia fra le due macchine è
     * {@code agnus_hpos = nostro_cck + 4}: lo dicono gli slot sprite (i nostri a cck 21/23,
     * quelli dell'oracolo a hpos 25/27) e lo confermano, uno per uno, i sei MOVE del Copper
     * di {@code Agnus/DIW/OLDDIW/diw4}, che cadono all'ISTANTE IDENTICO. Ma l'oracolo
     * valuta la finestra verticale in {@code custom_trigger_start()}, chiamata ad
     * {@code agnus_hpos == 1} ({@code HARDWIRED_DMA_TRIGGER_HPOS}) — cioè al nostro cck
     * <b>224</b>, tre CCK prima del nostro confine di riga, e col vpos GIÀ incrementato.
     *
     * <p>È esattamente ciò che {@code diw4}/{@code diw5} misurano: una MOVE del Copper a
     * DIWSTOP che cade in quei tre CCK: l'oracolo la vede sulla riga NUOVA (dopo il
     * confronto, che quindi ha già chiuso la finestra col valore vecchio) e noi sulla
     * VECCHIA (prima del confronto, che quindi usa il valore nuovo).
     *
     * <p>⛔ <b>Perché si spostano solo i CONSUMATORI e non {@code vpos} stesso.</b>
     * Spostare il contatore intero è stato provato ed è un <b>veto della rete</b>:
     * {@code Agnus/Copper} 94/20 → 87/27 ({@code cross2}, {@code steal7}, {@code coptim4/5},
     * {@code oldcoptim3/5}), {@code Paula/Interrupts} 82/0 → 77/5 (i cinque
     * {@code intreqcia}), {@code Agnus/Registers} 123/3 → 122/4. La causa è nominata e
     * misurata: la catena «WAIT soddisfatta → MOVE» del nostro Copper è <b>un CCK più
     * corta</b> di quella dell'oracolo, e oggi il {@code +2} del comparatore orizzontale la
     * compensa; sul risveglio VERTICALE quella compensazione non c'è, e i quattro
     * {@code coptim} sbagliano di 4 px per riga, cioè di UN CCK. Muovere il contatore vuole
     * quindi, nella stessa passata, la catena del Copper, l'offset del comparatore,
     * {@code BEAM_AHEAD_CCK} e le ancore degli sprite. Vedi
     * {@code docs/vamigats-import-order.md}, §La riga del contatore verticale.
     */
    private static final int TRIGGER_VPOS_LEAD_HPOS = 6;

    /**
     * Il {@code vpos} dell'ORACOLO nella nostra griglia: {@code vpos} portato avanti di
     * {@link #TRIGGER_VPOS_LEAD_HPOS}. Coincide con {@code vpos} per tutta la riga tranne
     * i tre CCK finali, dove vale già quello della riga che sta per iniziare.
     *
     * <p>Lo leggono i consumatori per cui la misura esiste, uno alla volta: la FINESTRA
     * VERTICALE di display ({@link #updateBitplaneVerticalWindow()} /
     * {@link #compareBitplaneVerticalWindow()}) e {@link #sprStartStop(int)}, che
     * nell'oracolo è {@code sprstartstop()} e legge lo stesso {@code vpos} già
     * incrementato da {@code custom_trigger_start()}. <b>Non</b> lo leggono i consumatori
     * interni alla riga (la macchina a stati del DMA sprite allo slot 0, p.es.), dove
     * {@code triggerVpos == vpos} e la distinzione non si osserva.
     */
    private int triggerVpos = 0;

    /**
     * Il {@code custom_trigger_start()} dell'oracolo: porta {@link #triggerVpos} alla riga
     * che sta per iniziare, tre CCK prima del nostro confine di riga, e rivaluta lì la
     * finestra verticale ({@code check_bpl_vdiw()}).
     */
    private void advanceTriggerVpos() {
        if (syncsStopped) {
            return;
        }
        int next = vpos + 1;
        int wrapAt = (field == 0) ? (maxVpos + 1) : maxVpos;
        if (next >= wrapAt) {
            next = 0;
        }
        triggerVpos = next;
        updateBitplaneVerticalWindow();
    }

    private void updateBitplaneVerticalWindow() {
        // Flip-flop verticale del DMA bitplane (cfr. vAmiga Sequencer::eolHandler):
        //   ON  quando vpos == diwVStart, OFF quando vpos == diwVStop.
        // All'inizio del frame (vpos==0) il flip-flop parte chiuso, A MENO CHE la
        // finestra non cominci proprio alla riga 0 (diwVStart==0): in tal caso deve
        // restare aperto. La vecchia logica trattava vpos==0 come uno stop
        // incondizionato, che vinceva sullo start e azzerava per sempre la finestra
        // quando diwVStart==0 (caso reale: il 2° cracktro di Persian Gulf Inferno
        // imposta DIWSTRT con Vstart=0 → bitplane mai prelevati → solo le barre del
        // Copper, niente testo né vettori). vAmiga resetta il flip-flop all'ultima
        // riga del frame, non alla riga 0, così uno Vstart==0 apre correttamente.
        if (triggerVpos == 0) {
            bitplaneVerticalActive = (diwVStart == 0) && (diwVStop != 0);
            if (bitplaneVerticalActive) bitplaneDmaOnAtWindowStart = bitplaneDmaEnabled;
        } else {
            compareBitplaneVerticalWindow();
        }
        // Vertical blank di Agnus: il VB inizia all'ULTIMA riga del field
        // (WinUAE custom.cpp vsync: `vpos == maxvpos + lof_store - 1` → agnus_vb,
        // che inibisce il fetch bitplane). PAL long frame: v312; short: v311.
        // Senza questo gate fetchavamo bitplane anche su v312 mentre WinUAE no
        // → il .loop di bbusy0 girava a 18 cck/iter invece di 17 su quella riga
        // → fase del beam-lock del frame successivo sfasata (bande in cima).
        // NB: il VB hardware copre anche v0..~v25; qui modelliamo solo la riga
        // d'inizio (subset sicuro: nessun test/gioco fetcha in quell'area).
        boolean lastLineOfField = triggerVpos == ((field == 0) ? maxVpos : maxVpos - 1);
        bitplaneDmaActive = bitplaneDmaEnabled && bitplaneVerticalActive && !lastLineOfField;
    }

    /**
     * Il confronto vero e proprio: {@code vpos} contro Vstart e Vstop, con lo <b>stop che
     * domina</b> lo start. È il corpo di {@code check_bpl_vdiw()} dell'oracolo:
     *
     * <pre>{@code
     * bool start = vpos == plffirstline && !forceoff;
     * bool end   = vpos == plflastline  || forceoff;
     * if (start && !end) vdiwstate = DIW_waiting_stop;   // apre
     * else if (end)      vdiwstate = DIW_waiting_start;  // chiude
     * }</pre>
     *
     * Estratto perché lo chiamano <b>due</b> percorsi: lo scorrimento di riga e la
     * rivalutazione alla scrittura di DIWSTRT/DIWSTOP/DIWHIGH — vedi
     * {@link #recheckBitplaneVerticalWindow()}.
     */
    private void compareBitplaneVerticalWindow() {
        if (triggerVpos == diwVStop) {
            bitplaneVerticalActive = false;
        } else if (triggerVpos == diwVStart) {
            bitplaneVerticalActive = true;
            // Stato del DMA bitplane all'APERTURA della finestra di display: serve a
            // distinguere un frame parziale (bitplane spenti in cima, accesi più giù →
            // mano insert-disk del KS) da uno split-screen legittimo (bitplane accesi
            // in cima, commutati a metà → pannello verbi/inventario di Monkey Island 2).
            bitplaneDmaOnAtWindowStart = bitplaneDmaEnabled;
        }
    }

    /**
     * ⛔ <b>La finestra verticale si rivaluta ALLA SCRITTURA, non solo a inizio riga.</b>
     * Nell'oracolo una scrittura a DIWSTRT/DIWSTOP/DIWHIGH chiama {@code calcvdiw()}, che
     * aggiorna {@code plffirstline}/{@code plflastline} e alza <b>{@code need_vdiw_check}</b>;
     * il flag è consumato dentro {@code do_cck()}, <b>dopo</b> {@code decide_bpl()}, quindi il
     * confronto rigira <b>a riga in corso</b> e il nuovo stato vale dal CCK successivo.
     *
     * <p>Senza questa rivalutazione, un valore che coincide con la riga <b>corrente</b> non
     * apre mai la finestra — e siccome quel valore resta per il resto del fotogramma, non la
     * apre più nemmeno dopo. È tutta la classe A del banco {@code Agnus/DIW}: su
     * {@code diwv10÷12} non disegnavamo un pixel di playfield da vpos 58 in giù, mentre
     * l'oracolo disegnava tutto.
     *
     * <p>⚠️ <b>Perché qui basta chiamarlo subito e non serve un flag «al CCK dopo».</b>
     * L'unica cosa che l'attesa dell'oracolo garantisce è che la scrittura <i>non</i> influenzi
     * la decisione di fetch del proprio CCK ({@code decide_bpl} è già girata). Da noi le due
     * scritture possibili — Copper in {@code copper.fallingEdge()} e CPU in
     * {@code handleCpuDataTransferPhase()} — stanno <b>entrambe</b> nel fronte di discesa,
     * cioè dopo l'arbitraggio degli slot del CCK corrente: la garanzia c'è già per
     * costruzione. Un secondo stadio di pipe sarebbe un CCK di ritardo in più, non una
     * traduzione.
     *
     * <p>⚠️ Il ramo {@code vpos == 0} di {@link #updateBitplaneVerticalWindow()} <b>non</b> si
     * applica qui: quello modella il reset del flip-flop a fine fotogramma (vAmiga
     * {@code eolHandler}, oracolo {@code forceoff}), non un confronto, e riapplicarlo a una
     * scrittura chiuderebbe la finestra dove l'oracolo la lascia com'è.
     */
    private void recheckBitplaneVerticalWindow() {
        compareBitplaneVerticalWindow();
        boolean lastLineOfField = triggerVpos == ((field == 0) ? maxVpos : maxVpos - 1);
        bitplaneDmaActive = bitplaneDmaEnabled && bitplaneVerticalActive && !lastLineOfField;
    }

    private boolean bitplaneDmaOnAtWindowStart = false;

    private boolean isSpriteDMAEnabled() {
        return spriteDmaEnabled;
    }

    private boolean isDiskDMAEnabled() {
        return diskDmaEnabled;
    }

    private boolean isAudioDMAEnabled() {
        return audioDmaEnabled;
    }

    /**
     * Il COPEN <b>come lo vede l'arbitraggio</b>: finche' il CCK corrente non ha superato
     * {@link #copperDmaChangeCck} vale il valore <b>precedente</b>. E' {@code is_copper_dma()}
     * dell'oracolo; la soglia la fissa la scrittura a DMACON — vedi il commento la'.
     */
    public boolean isCopperEnabled() {
        if ((masterClock >> 1) <= copperDmaChangeCck) {
            return !copperDmaEnabled;
        }
        return copperDmaEnabled;
    }

    /**
     * CCK fino al quale (compreso) l'arbitraggio deve usare il COPEN <b>vecchio</b>.
     * {@code Long.MIN_VALUE} = nessun cambio in sospeso.
     */
    private long copperDmaChangeCck = Long.MIN_VALUE;

    public boolean isOCS() {
        // Se Agnus ID (VPOSR bit 14-8) ha il bit 13 (0x20) a 0 siamo su OCS, altrimenti
        // su ECS. L'ID è fornito dal hook agnusIdBits(), specializzato per sottoclasse.
        return (agnusIdBits() & 0x20) == 0;
    }

    public final boolean isECS() {
        return !isOCS();
    }

    // =========================================================================
    // Hook di specializzazione del chipset
    // =========================================================================
    // Punti in cui Agnus differisce fra OCS e ECS. I default di questa classe
    // riproducono ESATTAMENTE il comportamento OCS storico (così OcsAgnus è un
    // no-op semantico); EcsAgnus li ridefinisce. Sono volutamente su percorsi di
    // configurazione/registro (NON per-pixel) per non gravare sull'hot path.

    /**
     * Maschera applicata ai bit bassi di DDFSTRT/DDFSTOP. OCS = 0xFC (granularità
     * 4 CCK), ECS = 0xFE (passo di fetch più fine, 2 CCK in hires). Rif. vAmiga
     * {@code Agnus::ddfMask()}, WinUAE {@code custom.cpp} ({@code ddf_mask}).
     */
    protected int ddfMask() { return 0xFC; }

    /**
     * CCK del limite HARD di fine fetch bitplane. Agnus attiva la condizione DDFSTOP
     * a $D7 anche quando DDFSTOP è programmato oltre: è ciò che tiene la finestra di
     * fetch dentro la riga. Rif. WinUAE {@code decide_bpl()}, {@code hpos == 0xd7}.
     */
    protected static final int DDF_HARD_STOP_CCK = 0xD7;

    /**
     * Limite hard di INIZIO fetch: a CCK {@code $18} Agnus rilascia la condizione DDFSTOP
     * ereditata dalla riga precedente ({@code decide_bpl}: {@code if (hpos == 0x18)
     * ddf_limit_in = false;}). È l'altro estremo di {@link #DDF_HARD_STOP_CCK}.
     */
    protected static final int DDF_HARD_START_CCK = 0x18;

    /**
     * {@code true} se i limiti HARD della finestra di fetch sono disattivati. Su OCS
     * non lo sono mai; su ECS li disabilitano BEAMCON0 VARBEAMEN/HARDDIS e i bit
     * UHRES/SHRES di BPLCON0 (WinUAE {@code harddis_h}).
     */
    protected boolean ddfHardLimitsDisabled() { return false; }

    /**
     * Bit 14-8 dell'ID Agnus restituito da VPOSR ($004). OCS: {@code 0x00} (PAL) o
     * {@code 0x10} (NTSC); ECS aggiunge il bit 13 ({@code 0x20}). {@link #isOCS()} e
     * {@link #isECS()} derivano da qui. Rif. vAmiga {@code Agnus::idBits()}.
     */
    protected int agnusIdBits() {
        return (mode == VideoMode.PAL) ? ID_OCS_PAL : ID_OCS_NTSC;
    }

    /**
     * {@code true} se BEAMCON0 ($1DC) è scrivibile. È un registro ECS-only: su OCS
     * la scrittura è ignorata (default {@code false}). Rif. vAmiga
     * {@code Agnus::pokeBEAMCON0} ({@code if (isOCS()) return;}).
     */
    protected boolean beamcon0Writable() { return false; }

    /**
     * Applica il modo scritto in BEAMCON0 al pennello — l'{@code init_beamcon0()}
     * dell'oracolo, ridotto a quel che modelliamo: {@code maxvpos} e il modo
     * {@link #beamNtsc} da cui dipendono {@link #lol} e la fase del VSYNC.
     *
     * <p>⚠️ Il numero di righe cambia <b>nel CCK della scrittura</b>, non al fotogramma
     * dopo: se il pennello è già oltre la nuova ultima riga, il fotogramma corrente si
     * chiude alla prima occasione utile — che è esattamente quel che fa l'oracolo, dove
     * `maxvpos` è una variabile letta a ogni confronto di riga.
     *
     * <p>⛔ Non modelliamo il resto di `init_beamcon0()`: i modi programmati
     * (VARBEAMEN/VTOTAL/HTOTAL), i sync variabili e il ricalcolo della finestra di
     * visualizzazione sono materia del display, non del contatore.
     */
    private void applyBeamcon0() {
        final boolean ntsc = (beamcon0 & BEAMCON0_PAL_BIT) == 0;
        if (ntsc == beamNtsc) {
            return;
        }
        beamNtsc = ntsc;
        maxVpos = ntsc ? 262 : 312;
    }

    /** Maschera del contatore REFPTR ($028). OCS: 9 bit ({@code 0x01FF}). */
    protected int refptrMask() { return 0x01FF; }

    /**
     * Gestisce la scrittura di DIWHIGH ($1E4), registro ECS-only che estende i bit
     * alti V di DIWSTRT/STOP (lato Agnus) e inoltra la parte H (HSTART/HSTOP bit 8)
     * a Denise. No-op su OCS (gate {@link #isECS()}). Layout DIWHIGH (maschera 0x2727):
     * bit 0-2 = VSTART V8-V10, bit 5 = HSTART H8, bit 8-10 = VSTOP V8-V10, bit 13 =
     * HSTOP H8. Rif. vAmiga {@code Agnus::pokeDIWHIGH} (value &amp;= 0x2727).
     */
    protected void writeDIWHIGH(int value) {
        if (!isECS()) return; // DIWHIGH è ECS-only: su OCS la scrittura è ignorata.
        value &= 0x2727;
        diwVStart = (diwVStart & 0xFF) | ((value & 0x07) << 8);
        diwVStop  = (diwVStop  & 0xFF) | (((value >> 8) & 0x07) << 8);
        // Anche DIWHIGH passa da calcvdiw(): stesso trattamento di DIWSTRT/DIWSTOP.
        recheckBitplaneVerticalWindow();
        if (denise != null) denise.writeRegister(0x1E4, value);
    }

    private boolean isBlitterEnabled() {
        return blitterDmaEnabled;
    }

    private boolean isBlitterNasty() {
        return (dmacon & 0x0400) != 0; // BLTPRI
    }

    // =========================================================================
    // Slot bitplane
    // =========================================================================

    /**
     * Determina quale bitplane fetchare nel CCK corrente.
     *
     * @return Indice del bitplane (0-5) o -1 se lo slot non è usato.
     */
    private int getBitplaneIndexForRasterPosition(int hpos, int bplCount, boolean hires) {
        int cck = hpos >> 1;
        // La finestra di fetch è SEMI-APERTA: [ddfCckStart, getEffectiveDdfStopCck).
        // getEffectiveDdfStopCck() restituisce ddfCckStart + (numero di CCK della
        // finestra, arrotondato all'unità di fetch), cioè il primo CCK *fuori* dalla
        // finestra. Con un confronto inclusivo (cck > stop) si prelevava un CCK extra:
        // in hires (unità da 4 CCK, sequenza piani {3,1,2,0}) quel CCK in più cadeva
        // sempre sul piano 3, che così faceva 41 fetch/riga contro 40 degli altri →
        // il puntatore del piano 3 derivava di +2 byte/riga e l'immagine veniva
        // "traslata" progressivamente solo sulle componenti colore che usano quel
        // piano (es. la banda verde della schermata 640x512 di DiagROM).
        int limit = bitplaneDmaPlaneLimit(bplCount, hires);
        if (cck < fetchUnitDdfStart || cck >= getEffectiveDdfStopCck(hires) || limit <= 0) return -1;
        if ((hpos & 1) != 0) return -1;

        int plane = getBitplaneIndexForFetchUnit(cck - fetchUnitDdfStart, hires);
        return plane < limit ? plane : -1;
    }

    /**
     * Quanti piani il DMA di bitplane preleva davvero, dato il BPU grezzo di BPLCON0.
     *
     * <p>⛔ <b>Non è un troncamento al numero di slot: quando i piani non ci stanno, il
     * DMA di bitplane si spegne del tutto.</b> È {@code real_bitplane_number} di WinUAE
     * ({@code custom.cpp create_cycle_diagram_table}), letto da {@code GET_PLANES_LIMIT}
     * e usato in {@code generate_bpl()} come {@code plane <= bplcon0_planes_limit}:
     * <pre>
     *   int rplanes = planes;
     *   if (rplanes &gt; max_planes) rplanes = 0;                          // niente DMA
     *   if (rplanes == 7 &amp;&amp; fm == 0 &amp;&amp; res == 0 &amp;&amp; !aga_mode) rplanes = 4;
     * </pre>
     * con {@code max_planes = fm_maxplanes[fm*4+res]} = 8 in lores, <b>4 in hires</b>
     * (2 in SuperHires, che non modelliamo). Quindi cinque o sei piani in hires — la
     * configurazione illegale di {@code BPLPTR/drop5x}, {@code drop6x} e
     * {@code dropcpu6x} — non prelevano niente e lasciano liberi tutti gli slot.
     *
     * <p>⚠️ Il caso {@code 7 → 4} è il motivo per cui qui arriva il BPU <b>grezzo</b>
     * ({@link AbstractDenise#getRawBpu()}) e non quello di Denise: i due chip hanno la
     * stessa soglia ma non la stessa tabella, e sette piani in lores sono quattro fetch
     * con sei piani disegnati (i due alti tengono il dato vecchio).
     */
    static int bitplaneDmaPlaneLimit(int rawBpu, boolean hires) {
        if (hires) return rawBpu > 4 ? 0 : rawBpu;
        return rawBpu == 7 ? 4 : rawBpu;
    }

    private int getEffectiveDdfStopCck(boolean hires) {
        // Limite HARD di fine fetch: a CCK $D7 Agnus attiva comunque la condizione
        // DDFSTOP, anche se DDFSTOP è programmato più a destra. WinUAE decide_bpl():
        // `if (hpos == 0xd7) { if (bprun && !ddf_stopping) ddf_stopping = 1; }` sul
        // ramo OCS, e `if (!harddis_h) ddf_limit_in = true;` su quello ECS — cioè
        // esattamente un min() fra DDFSTOP e $D7. Senza questo tetto un DDFSTOP
        // oltre $D7 (Crack, LINEL 1988: DDFSTRT=$30, DDFSTOP=$DF) allargava la
        // finestra di un'unità di fetch intera, con due conseguenze:
        //  - l'unità in più era troncata dalla fine riga, quindi il solo piano che
        //    ha lo slot subito dopo il confine (indice 3 in lores, CCK+1) faceva un
        //    fetch di troppo e derivava di 2 byte/riga;
        //  - il modulo BPLxMOD, che si applica nell'ULTIMA unità, cadeva in
        //    quell'unità fantasma e quindi NON veniva mai applicato agli altri
        //    piani: avanzavano 44 byte/riga invece di 64.
        int stop = ddfCckStop;
        if (!ddfHardLimitsDisabled()) {
            stop = Math.min(stop, DDF_HARD_STOP_CCK + 8);
        }
        // Ancorato allo start CAMPIONATO (fetchUnitDdfStart), non al registro vivo:
        // l'arrotondamento all'unità di 8 CCK deve usare la stessa origine della fase
        // di fetch, altrimenti un cambio di DDFSTRT a metà riga sfaserebbe il confine.
        int relStop = Math.max(0, stop - fetchUnitDdfStart);
        // I fetch bitplane sono raggruppati in unità da 8 CCK. Se DDFSTOP non è
        // allineato all'unità (relativamente a DDFSTRT) la DMA completa comunque
        // l'unità in corso, producendo un fetch extra. Questo allinea la larghezza
        // di riga del display a quella del buffer prodotto dal Blitter: i cracktro
        // (es. "Tv sports basket") usano DDFSTOP dispari ($D4) con DDFSTRT=$30 e
        // senza l'arrotondamento il display avanza 2 byte/riga in meno del blit,
        // producendo uno shear progressivo (testo scroller sfasato/illeggibile).
        // Il ramo hires faceva già questo arrotondamento; ora è coerente anche in lores.
        return fetchUnitDdfStart + ((relStop + 7) & ~7);
    }

    // PERF: tabelle di fetch pre-allocate. Prima erano allocate come array literal
    // a OGNI chiamata (hot path del fetch bitplane, ~milioni/s) → churn di allocazioni
    // = principale sorgente di garbage → pause GC stop-the-world = cali sporadici di
    // frame rate. Rese static final: zero allocazioni, comportamento identico.
    private static final int[] HIRES_FETCH = { 3, 1, 2, 0 };
    private static final int[] LORES_FETCH = { -1, 3, 5, 1, -1, 2, 4, 0 };

    // =========================================================================
    // Sequencer dei bitplane — decide_bpl()/generate_bpl() dell'oracolo
    // =========================================================================

    /**
     * Il blocco di fetch dura <b>otto</b> CCK in tutte le risoluzioni: è
     * {@code fetchunits[] = {8,8,8,...}} di WinUAE con {@code fetchmode 0}. Quel che cambia
     * con la risoluzione è la lunghezza della <b>sequenza</b> dentro il blocco
     * ({@code fetchstarts[]}: 8 CCK in lores, 4 in hires) e quanti piani ci stanno
     * ({@code fm_maxplanes[]}: 8 e 4).
     */
    private static final int FETCHUNIT_MASK = 7;

    /**
     * ⚠️ <b>Sfasamento fra la nostra griglia di CCK e l'{@code hpos} dell'oracolo</b>, da
     * sommare ai valori DDF prima di confrontarli. Non è una taratura libera: è
     * l'ancora già misurata sul banco {@code BPLPTR} — «il nostro {@code hpos} per lo
     * stesso evento è più basso di quello dell'oracolo, e il nostro <i>fetch</i>
     * corrisponde al suo <i>write-back</i>, con la cattura un CCK prima».
     * Il modello posizionale precedente lo assorbiva nella definizione della finestra
     * ({@code relCck = cck - (ddfStrt & mask)}); il sequencer deve dichiararlo.
     */
    private static final int SEQ_PHASE = -2;

    /** Quale piano tocca al CCK di fase {@code bprunCycle}; -1 se lo slot è libero. */
    private static int bplSequence(int bprunCycle, boolean hires) {
        return hires ? HIRES_FETCH[bprunCycle & 0x3] : LORES_FETCH[bprunCycle & 0x7];
    }

    /** Stato di {@code bprun}: 0 = fermo, 1 = in corsa, 2/3 = spegnimento in due CCK. */
    private int bprun;
    /** Fase della sequenza di fetch: avanza a ogni CCK di {@code bprun}, non con hpos. */
    private int bprunCycle;
    /**
     * Condizione «DDFSTOP passato»: 0 = no, 1 = appena scattata, 2 = ultimo blocco (è qui
     * che si applicano i moduli). ⚠️ Non si azzera a fine riga: la azzera solo
     * {@link #bplDmaNormalStop()}, come nell'oracolo.
     */
    private int ddfStopping;
    /** ECS: vero fra il match di DDFSTRT e quello di DDFSTOP. */
    private boolean ddfEnableOn;
    /** Limite hard $18÷$D7, campionato con un CCK di ritardo ({@code ddf_limit_out}). */
    private boolean ddfLimitIn, ddfLimitOut;

    /**
     * ⛔ <b>DDFSTRT e DDFSTOP non entrano in vigore subito</b>, e finche' non lo fanno il
     * confronto usa il valore <b>vecchio</b>. L'oracolo lo dichiara nel commento dei due
     * handler — <i>«new value is available after 2 CCKs»</i> — e lo realizza tenendo il valore
     * precedente accanto al nuovo, col CCK della scrittura come discriminante:
     *
     * <pre>{
     * // scrittura:  ddfstrt_val_old = ddfstrt_val;  ddfstrt_cycle = get_cycles() + CYCLE_UNIT;
     * (hpos == ddfstrt_val && cyc >  ddfstrt_cycle) || (hpos == ddfstrt_val_old && cyc <  ddfstrt_cycle)
     * (hpos == ddfstop     && cyc >  ddfstop_cycle) || (hpos == ddfstop_old     && cyc <= ddfstop_cycle)
     * }</pre>
     *
     * ⚠️ <b>I due non hanno lo stesso operatore, e non è una svista</b>: DDFSTOP usa { <=} e
     * DDFSTRT { <}. ⇒ al CCK <b>esattamente uguale</b> a { ddfstrt_cycle} nessuna delle
     * due condizioni di DDFSTRT è vera — <b>né il valore vecchio né quello nuovo</b> fanno match e
     * la riga non parte affatto. È il caso che il README del §14 dichiara per { ddftimcop9}
     * (<i>«the write cycle matches the trigger cycle which causes neither the old nor the new
     * value to match»</i>, con una riga mancante nell'immagine), e cade fuori dai due operatori
     * invece che da una regola a parte. ⇒ { ddftimcop9} è <b>bit-exact</b>.
     *
     * <p>⚠️ <b>Il { + 1} dell'oracolo diventa { 1 - SEQ_PHASE} da noi, e non è una
     * taratura</b>: il §12 ha misurato che l'etichetta delle due griglie differisce di <b>+4 per
     * una scrittura</b> e di <b>+2 per un fetch</b>, e qui l'istante della scrittura va confrontato
     * sulla griglia del <b>match</b>. La conversione fra le due è { -SEQ_PHASE} = 2, la stessa
     * costante che il sequencer usa per posizionare DDFSTRT e DDFSTOP. ⇒ { 1 + 2 = 3}.
     */
    private static final int DDF_WRITE_DELAY = 1 - SEQ_PHASE;
    private int ddfStrtVal = 1, ddfStrtValOld = 1, ddfStopVal, ddfStopValOld;
    private long ddfStrtChangeCck = Long.MIN_VALUE, ddfStopChangeCck = Long.MIN_VALUE;

    /**
     * ⛔ <b>Il contatore della riga è CIRCOLARE, e il confronto DDF non deve uscirne.</b>
     * L'oracolo confronta {@code agnus_hpos} con {@code ddfstrt_val}; la nostra griglia è
     * spostata di {@link #SEQ_PHASE}, quindi per le coordinate <b>vicinissime a zero</b> la
     * sottrazione porta il punto di match <b>fuori dalla riga</b> e il match non avviene mai.
     *
     * <p>Il caso è reale e il banco §14 lo isola: {@code hwstop1/7/8} scrivono
     * {@code DDFSTRT = DDFSTOP = $00} per una manciata di righe, quindi
     * {@code ddfstrt_val = ($00 & $FC) | 1 = 1}. L'oracolo fa match a {@code hpos == 1} e
     * arma la riga da lì fino all'hard stop; da noi {@code 1 + SEQ_PHASE = −1} non esiste, e
     * quelle righe restavano <b>vuote</b> — 358 pixel per riga, sei righe nella metà lores e
     * sei in quella hires. ⚠️ E la controprova è nel banco stesso: {@code hwstop3} ha le
     * <b>stesse</b> coordinate `LEFT`/`RIGHT` ma scrive {@code $08} invece di {@code $00}, ed
     * era già verde.
     *
     * <p>⚠️ È la stessa forma del §17 classe B — <i>il contatore gira, e un modello che non lo
     * segue perde esattamente i valori vicini al wrap</i>.
     */
    private int ddfMatchCck(int val) {
        int c = val + SEQ_PHASE;
        return c < 0 ? c + maxHpos / 2 : c;
    }

    private void noteDdfStrtWrite() {
        ddfStrtValOld    = ddfStrtVal;
        ddfStrtChangeCck = (masterClock >> 1) + DDF_WRITE_DELAY;
        ddfStrtVal       = (ddfStrt & ddfMask()) | 1;
    }

    private void noteDdfStopWrite() {
        ddfStopValOld    = ddfStopVal;
        ddfStopChangeCck = (masterClock >> 1) + DDF_WRITE_DELAY;
        ddfStopVal       = ddfStop & ddfMask();
    }


    /** Valore precedente di {@code hwi}: la macchina parte sul FRONTE, non sul livello. */
    private boolean hwiOld;
    /** OCS: BPLEN arriva al sequencer con un CCK di ritardo in più che su ECS. */
    private boolean dmaconBpl2;

    /**
     * BPLEN <b>come lo vede il sequencer</b>, e non è il valore vivo del registro.
     *
     * <p>⛔ Nell'oracolo {@code dmacon_bpl} è un <b>latch ricampionato una volta per CCK</b>,
     * e il punto in cui lo fa è quel che conta: in fondo a {@code do_cck()}, <b>dopo</b>
     * {@code decide_bpl()}, dopo {@code inc_cck()} e <b>prima</b> di
     * {@code handle_pipelined_custom_write()} — cioè prima che la scrittura del CCK corrente
     * arrivi al registro:
     * <pre>
     *   generate_dma_requests();  // generate_bpl: usa il latch
     *   decide_bpl(agnus_hpos);   // usa il latch
     *   ...
     *   inc_cck();
     *   dmacon_bpl = (dmacon &amp; DMA_BITPLANE) &amp;&amp; (dmacon &amp; 0x200);   // ricampiona
     *   handle_pipelined_custom_write(false);                        // e SOLO ORA scrive
     * </pre>
     * ⇒ una scrittura a DMACON al CCK X entra in {@code dmacon} dopo il ricampionamento di
     * X, viene presa da quello di X+1 e il sequencer la vede a <b>X+2</b>. Due stadi.
     *
     * <p>⚠️ Misurato, non dedotto: su {@code reenable2} riga 64 il Copper spegne BPLEN a
     * hpos $51 e l'oracolo reagisce a h=87 — sei CCK dopo — mentre noi, leggendo il registro
     * vivo, reagivamo a quattro. Quei due CCK spostavano {@code bprun_cycle} di due, e con
     * esso il blocco su cui cade {@code ddf_stopping}: il sequencer terminava nel punto
     * sbagliato e {@code reenable2} passava dal 1,91% al 26,03%.
     *
     * <p>⚠️ È la stessa forma che l'oracolo usa già, dichiarata, per il ritardo in più di
     * OCS ({@code dmacon_bpl2}, <i>«OCS BPLENA is delayed by extra 1 CCK»</i>): lì gli stadi
     * diventano tre.
     *
     * <p>⛔ <b>I due stadi NON erano il surrogato di un caricamento PT/MOD mancante</b>, ed
     * è stato rimisurato il 2026-09-03, subito dopo aver reso esplicito il campionamento del
     * modulo ({@code bpl1modFetch}): tornando a <b>uno</b> stadio il banco peggiora —
     * {@code DmaConBitplaneTest} 1/7 → 0/8 e {@code OldDmaConTest} 1/19 → 0/20, con
     * {@code BplModTest} fermo a 6/0. Questo latch è di BPLEN, quello è di PT/MOD: due
     * meccanismi diversi che vivono nello stesso CCK. Non ridurli.
     */
    private boolean dmaconBplSeq, dmaconBplPipe;

    /**
     * Il bit di DMA sprite <b>come stava due CCK prima</b> dello slot che lo usa.
     *
     * <p>⛔ Non è un latch di comodo: nell'oracolo la decisione degli sprite è
     * <b>anticipata per costruzione</b>. Gli slot escono da uno shift register
     * ({@code dmal_shifter}) i cui tap degli sprite stanno due posizioni più avanti di
     * quelli di audio e disco — {@code handle_dmal()} lo dichiara nel commento
     * <i>«sprites have earlier DMA decisions»</i> — e {@code generate_sprites()} legge
     * {@code dmaen(DMA_SPRITE)} lì, scrivendo la richiesta nella pipeline RGA; il
     * trasferimento cade due CCK dopo.
     *
     * <p>⚠️ È esattamente ciò che il banco {@code DMACON/sprdiscpu*} misura: quando la CPU
     * spegne il DMA sprite <b>fra la decisione e il trasferimento</b>, l'oracolo fa il
     * fetch lo stesso e noi no. Misurato su {@code sprdiscpu1} con
     * {@code WINUAE_SPRLOG}/{@link io.github.zolfrik.amija.probe.SpriteDmaProbe}: alla riga
     * 65 la scrittura cade a {@code h=24}, fra la decisione di SPRxDATA ({@code h=23}) e il
     * suo trasferimento ({@code h=25}), e l'oracolo preleva SPRxDATA ma non SPRxDATB.
     * Leggendo il bit vivo noi non prelevavamo nessuno dei due, e SPRxDATA/SPRxDATB
     * finivano a righe diverse — due pixel di colore 17 e 18 dove ce n'è uno di colore 19.
     */
    private boolean spriteDmaSeq, spriteDmaPipe;

    /**
     * CCK di <b>decisione</b> reso inefficace dall'accensione del DMA sprite, oppure −1.
     *
     * <p>⛔ Seconda regola dello stesso meccanismo, e l'oracolo la commenta parola per
     * parola: <i>«If sprite DMA is switched on just when sprite DMA is decided, channel is
     * still decided but it is not allocated!»</i>. Accendere SPREN al CCK {@code W} fissa
     * {@code sprite_dma_change_cycle_on = W + 1}; lo slot la cui <b>decisione</b> cade
     * proprio lì entra nella pipeline RGA con {@code alloc = 0} — quindi <b>non preleva e
     * non fa avanzare il puntatore</b>, e il ciclo resta libero per il Blitter.
     *
     * <p>Poiché la decisione sta due CCK prima del trasferimento (vedi {@link #spriteDmaSeq}),
     * lo slot saltato è quello a {@code W + 3}. Misurato su {@code sprenacpu2}, sprite 4,
     * riga 125: l'accensione cade a {@code h=40}, la decisione dello slot B a {@code h=41},
     * e l'oracolo <b>non</b> preleva — il puntatore resta a {@code $0705BA} e la riga
     * successiva riparte da lì. Senza questa regola facevamo un prelievo in più e
     * SPRxDATA/SPRxDATB restavano sfasati di una word per tutto lo sprite.
     */
    private long spriteDmaOnDecisionCck = -1;

    /**
     * {@code decide_bpl()} dell'oracolo: la macchina a stati che decide se il sequencer dei
     * bitplane è in corsa. Va chiamata <b>dopo</b> aver deciso lo slot del CCK corrente —
     * in {@code do_cck()} l'ordine è {@code generate_dma_requests()} (che contiene
     * {@code generate_bpl}) e <b>poi</b> {@code decide_bpl(agnus_hpos)} — quindi una
     * macchina armata al CCK X preleva la prima word al CCK X+1.
     *
     * <p>⛔ <b>Il cuore è che BPRUN parte sul FRONTE di salita di una condizione
     * composta</b>, non sul suo livello:
     * <pre>
     *   hwi = dma &amp;&amp; diw &amp;&amp; ddf_enable_on &amp;&amp; (!ddf_limit_out || harddis_h);
     *   if (!bprun &amp;&amp; hwi &amp;&amp; !hwi_old)      { bprun = 1; bprun_cycle = 0; }
     *   else if (bprun &amp;&amp; !hwi &amp;&amp; hwi_old)  { if (!ddf_stopping) ddf_stopping = 1; }
     * </pre>
     * Il commento dell'oracolo lo dice: <i>«BPRUN can only start if DMA, DIW or DDF state
     * has changed since last time»</i>. Da qui viene il <b>baco del sequencer</b> che
     * {@code DMACON/bpoffbug1} e {@code reenable2/3} misurano: spegnere BPLEN dentro la
     * finestra fa scattare {@code ddf_stopping = 1} sul fronte di discesa, e quando BPLEN
     * torna la sequenza riparte ma si ferma alla fine del blocco in corso — «early
     * termination», che il README del banco descrive come un baco di Agnus.
     *
     * <p>⚠️ E lo spegnimento non è immediato: {@code (!dma || !diw) && bprun == 1} porta
     * {@code bprun} a 2, e da lì 2→3→0, cioè <b>due CCK</b> di coda.
     *
     * <p>⚠️ OCS e ECS sono due rami diversi nell'oracolo e restano due qui. Su OCS BPLEN
     * arriva con un CCK di ritardo in più ({@code dmacon_bpl2}), la partenza è sul
     * <b>match</b> di DDFSTRT invece che sul fronte di {@code hwi}, e sono DDFSTOP e il
     * limite hard a far scattare {@code ddf_stopping} invece della discesa di {@code hwi}.
     */
    private void decideBpl(int cck) {
        // ⛔ L'ULTIMA RIGA DEL FOTOGRAMMA spegne la finestra verticale, DIWSTOP o no.
        // Oracolo, `check_bpl_vdiw()`, col suo commento:
        //     bool forceoff = agnus_bsvb && !harddis_v;
        //     bool end = vpos == plflastline || forceoff;
        //     // VB start line forces vertical display window off (if HARDDIS=0)
        // e `agnus_bsvb` è vero esattamente lì: `vpos == maxvpos + lof_store - 1`
        // (`check_vsyncs`), cioè 312 su un fotogramma lungo PAL.
        // ⚠️ Misurato con la sonda: senza questa riga il sequencer preleva 80 word in più
        // per fotogramma, tutte su vpos 312 — e sono quelle che facevano finire il blitter
        // 32 CCK prima, cioè il crollo di `BbusyTest`. Il modello posizionale non ne aveva
        // bisogno perché la sua finestra non arrivava fin lì per altra via.
        final boolean lastFrameLine = vpos == (field == 0 ? maxVpos : maxVpos - 1);
        final boolean diw = bitplaneVerticalActive && !lastFrameLine;
        final boolean harddis = ddfHardLimitsDisabled();
        // ⛔ Il `| 1`: l'oracolo confronta contro `ddfstrt_val = (ddfstrt & ddf_mask) | 1`
        // (DDFSTRT(), commento «DDFSTRT (odd cycle)»), mentre DDFSTOP resta pari. Il modello
        // posizionale non aveva mai avuto bisogno di saperlo perché non confrontava una
        // posizione con un match, ricavava una fase da una sottrazione.
        final long cyc = masterClock >> 1;
        final boolean ddfStrtMatch =
                   (cck == ddfMatchCck(ddfStrtVal)    && cyc >  ddfStrtChangeCck)
                || (cck == ddfMatchCck(ddfStrtValOld) && cyc <  ddfStrtChangeCck);
        final boolean ddfStopMatch =
                   (cck == ddfMatchCck(ddfStopVal)    && cyc >  ddfStopChangeCck)
                || (cck == ddfMatchCck(ddfStopValOld) && cyc <= ddfStopChangeCck);

        ddfLimitOut = ddfLimitIn;

        if (isECS()) {
            final boolean dma = dmaconBplSeq;

            if (cck == DDF_HARD_START_CCK + SEQ_PHASE) ddfLimitIn = false;
            if (cck == DDF_HARD_STOP_CCK + SEQ_PHASE && !harddis) ddfLimitIn = true;

            if (ddfStrtMatch) ddfEnableOn = true;
            if (ddfStopMatch) ddfEnableOn = false;

            boolean hwi = dma && diw && ddfEnableOn && (!ddfLimitOut || harddis);
            if (bprun == 0 && hwi && !hwiOld) {
                bprun = 1;
                bprunCycle = 0;
            } else if (bprun != 0 && !hwi && hwiOld) {
                if (ddfStopping == 0) ddfStopping = 1;
            }
            hwiOld = hwi;

            if (bprun == 3) bprun = 0;
            if (bprun > 1) bprun++;
            if ((!dma || !diw) && bprun == 1) bprun = 2;

        } else {
            // OCS: BPLENA ritardata di un CCK in più rispetto a ECS/AGA.
            final boolean dma = dmaconBpl2;
            dmaconBpl2 = dmaconBplSeq;

            if (ddfStopMatch && bprun != 0 && ddfStopping == 0) ddfStopping = 1;

            if (cck == DDF_HARD_START_CCK + SEQ_PHASE) ddfLimitIn = false;
            if (cck == DDF_HARD_STOP_CCK + SEQ_PHASE && bprun != 0 && ddfStopping == 0) ddfStopping = 1;

            boolean ddfstrtMatch = ddfStrtMatch;
            if (!ddfLimitOut && ddfstrtMatch && bprun == 0 && dma && diw) {
                bprun = 1;
                bprunCycle = 0;
            }

            if (bprun == 2) {
                // Se DDF è già passato si salta all'ultimo blocco (cracktro di Scoopex
                // «Crash Landing», citato dall'oracolo).
                if (ddfStopping == 1) ddfStopping = 2;
                bprun = 0;
            }

            if ((!dma || !diw) && bprun == 1) {
                bprun = 2;
                if (ddfStopping == 1) {
                    ddfStopping = 2;
                    bprun = 0;
                }
            }
        }
    }

    /**
     * {@code bpl_dma_normal_stop()}: fine regolare della sequenza. ⚠️ Su OCS ri-arma anche
     * il limite hard, che è quel che impedisce a una seconda finestra di aprirsi nella
     * stessa riga.
     */
    private void bplDmaNormalStop() {
        ddfStopping = 0;
        bprun = 0;
        if (!isECS()) ddfLimitIn = true;
    }

    private static int getBitplaneIndexForFetchUnit(int relCck, boolean hires) {
        return hires ? HIRES_FETCH[relCck & 0x3] : LORES_FETCH[relCck & 0x7];
    }

    private int decodeDdfStopCck(int value) {
        return (value & ddfMask()) + 8;
    }

    // =========================================================================
    // Accessori pubblici
    // =========================================================================

    public String getArbitrationReason() {
        if (busTakenByCustom) {
            int cck = hpos >> 1;
            BusOwner owner = OWNER_VALUES[busOwners[cck]];
            return switch (owner) {
                case REFRESH -> "DRAM_REFRESH";
                case DISK -> "DISK_DMA";
                case AUDIO0, AUDIO1, AUDIO2, AUDIO3 -> "AUDIO_DMA";
                case SPRITE0, SPRITE1, SPRITE2, SPRITE3, SPRITE4, SPRITE5, SPRITE6, SPRITE7 -> "SPRITE_DMA";
                case BPL1, BPL2, BPL3, BPL4, BPL5, BPL6 -> "BITPLANE_DMA";
                case COPPER -> "COPPER_FETCH";
                case BLITTER -> "BLITTER_FETCH";
                default -> "CUSTOM_DMA";
            };
        }
        if (cpuBusReq.as && !cpuBusResp.ack) {
            if (isBlitterEnabled() && blitter.isRunning() && !isBlitterNasty() && !bls) {
                return "BLITTER_PRIORITY";
            }
            if (isBlitterNasty() && blitter.isRunning()) {
                return "BLITTER_NASTY";
            }
            if ((hpos & 1) != 0) {
                return "CPU_WAIT_CCK";
            }
            return "CPU_WAIT_DMA";
        }
        return "IDLE";
    }

    public int getCpuPc() {
        return cpu != null ? cpu.getOpcodeAddress() : 0;
    }

    public int  getVpos()            { return vpos; }
    public int  getFrameCount()      { return frameCount; }

    public void notifyDiskTaskArmed() {
    }

    public void notifyDskblkFired() {
    }

    /**
     * Mette in scrittura il drive selezionato (DSKLEN con bit DMAEN+WRITE).
     * La sorgente dei bit è Paula: il drive la interroga una volta per cella MFM.
     */
    public void startFloppyWriting() {
        FloppyDrive drive = getSelectedDrive();
        if (drive != null) drive.setWriting(true);
    }

    /** Termina la scrittura su tutti i drive (DSKLEN azzerato): flush della traccia. */
    public void stopFloppyWriting() {
        df0.setWriting(false);
        df1.setWriting(false);
        df2.setWriting(false);
        df3.setWriting(false);
    }

    public int  getHpos()            { return hpos; }
    public int  getMaxHpos()         { return maxHpos; }
    public int  getMaxVpos()         { return maxVpos; }
    public int  getDiwVStart()       { return diwVStart; }
    public int  getDiwVStop()        { return diwVStop; }

    /** Maschera dei bit alti (16-20) del puntatore DMA presenti nella Chip RAM installata. */
    public int  getDmaPtrHiMask()    { return dmaPtrHiMask; }
    /** Maschera d'indirizzo della Chip RAM installata (size-1): 0x7FFFF (512 KB),
     *  0xFFFFF (1 MB), 0x1FFFFF (2 MB). Usata p.es. dal Copper per non troncare il
     *  PC quando la copper-list sta nella chip alta (>512 KB) su ECS. */
    public int  getChipMask()        { return chipMask; }
    public int  getLastCompletedFrameField() { return lastCompletedFrameField; }
    public boolean lastCompletedFrameHadLateBitplaneDma() { return lastCompletedFrameHadLateBitplaneDma; }
    /**
     * Il Blitter come lo vede il COPPER (BFD): resta occupato per <b>un CCK in piu</b>
     * di quanto lo veda la CPU via DMACONR. E' { blit_busy(false)} dell'oracolo -
     * vedi { Blitter#isBusyForCopper}.
     */
    public boolean isBlitterFinished() { return !blitter.isBusyForCopper(); }
    public FloppyDrive getDf0()      { return df0; }
    public FloppyDrive getDf1()      { return df1; }
    public FloppyDrive getDf2()      { return df2; }
    public FloppyDrive getDf3()      { return df3; }
    public Copper  getCopper()       { return copper; }
    public Blitter getBlitter()      { return blitter; }
    public Motorola68000 getCpu()    { return cpu; }
    public Paula   getPaula()        { return paula; }
    public int     getDMACON()       { return dmacon; }

    /**
     * Lettura diagnostica diretta dalla Chip RAM (nessun side effect di bus/Gary).
     * Restituisce 0 fuori dal range della Chip RAM.
     */
    public int peekChipWord(int addr) {
        addr &= 0xFFFFFF;
        // Chip RAM, per quanta ne è installata ($000000 in su).
        if (chipRAM != null && addr >= 0 && addr + 1 < chipRAM.size()) {
            return chipRAM.readWord(addr) & 0xFFFF;
        }
        // Slow RAM ($C00000-$C7FFFF, 512 KB) — molti giochi vi caricano il codice.
        if (slowRAM != null && addr >= 0xC00000 && addr + 1 < 0xC80000) {
            return slowRAM.readWord(addr - 0xC00000) & 0xFFFF;
        }
        // Kickstart ROM, finestra del socket $F80000-$FFFFFF — per disassemblare codice
        // ROM. L'offset si ricava mascherando, come fa Gary: una ROM da 256 KB si
        // specchia due volte nella finestra, una da 512 KB la riempie tutta. Sottrarre
        // una base fissa funzionerebbe solo per una delle due.
        if (rom != null && addr >= 0xF80000) {
            return rom.readWord(addr & (rom.size() - 1)) & 0xFFFF;
        }
        return 0;
    }
    public int peekChipLong(int addr) {
        return (peekChipWord(addr) << 16) | peekChipWord(addr + 2);
    }

    /** Stato live del display per la diagnostica (puntatori bitplane, finestra DDF/DIW). */
    public String getDisplayStateString() {
        int bc0 = denise != null ? denise.getBplcon0() : 0;
        return String.format(
            "BPLCON0=$%04X BPU=%d HIRES=%b | ddfStrt=$%02X ddfStop=$%02X ddfCck=%d..%d | "
          + "diwV=%d..%d diwStrt=$%04X diwStop=$%04X | bplPt0=$%06X bplPt1=$%06X int0=$%06X int1=$%06X | "
          + "bpl1mod=%d bpl2mod=%d bplcon1=$%04X dmacon=$%04X",
            bc0 & 0xFFFF, (bc0 >> 12) & 7, (bc0 & 0x8000) != 0,
            ddfStrt & 0xFF, ddfStop & 0xFF, ddfCckStart, ddfCckStop,
            diwVStart, diwVStop, diwStrt & 0xFFFF, diwStop & 0xFFFF,
            bplPt[0] & 0xFFFFFF, bplPt[1] & 0xFFFFFF,
            bplPtrInternal[0] & 0xFFFFFF, bplPtrInternal[1] & 0xFFFFFF,
            bpl1mod, bpl2mod, bplcon1 & 0xFFFF, dmacon & 0xFFFF);
    }

    /** Traccia fisica del drive selezionato (diagnostica disco); -1 se nessuno. */
    public int getSelectedDriveTrack() {
        FloppyDrive d = getSelectedDrive();
        return d != null ? d.getPhysicalTrack() : -1;
    }

    /** All'avvio di una DMA READ applica subito il /SIDE comandato sul drive
     *  selezionato (vedi FloppyDrive.commitPendingSideForRead): rende il side-select
     *  effettivo immediatamente, indipendente dalla velocità CPU. */
    public void commitSelectedDriveSideForRead() {
        FloppyDrive d = getSelectedDrive();
        if (d != null) d.commitPendingSideForRead();
    }

    /** DIAG (Fase A-ter): stato side del drive selezionato all'avvio DMA. cmdSide =
     *  livello /SIDE attualmente comandato su CIA-B PRB (cosa vuole il loader ORA);
     *  effSide + pending = cosa leggerà davvero la testina (latch + settle). */
    public String getSelectedDriveSideDebug() {
        int cmdSide = (lastCiabPrb & 0x04) == 0 ? 1 : 0;
        FloppyDrive d = getSelectedDrive();
        return "cmdSide=" + cmdSide + (d != null ? " " + d.getSideDebug() : " (no drive)");
    }

    public boolean inVBlankArea(int vpos) {
        return vpos < (beamNtsc ? 20 : 26);
    }

    public void triggerLightPen() {
        if (!vposFrozen) {
            vposFrozen = true;
            frozenVpos = vpos;
            frozenHpos = hpos;
        }
    }

    // =========================================================================
    // Memory accessors usati dal disassemblatore / debugger
    // =========================================================================

    public int peekByte(int address) {
        Gary.Route route = gary.route(address, Gary.AccessType.READ, (address & 1) == 0, (address & 1) != 0, true);
        if (!route.ok) return 0;
        int eff = route.effectiveAddress;
        return switch (route.target) {
            case CHIP_RAM                       -> chipRAM.readByte(eff);
            case KICKSTART_ROM, OVERLAY_ROM     -> rom.readByte(eff);
            case SLOW_RAM                       -> slowRAM.readByte(eff);
            case FAST_RAM                       -> (fastRam != null) ? fastRam.ram().readByte(eff) : 0x00;
            case CUSTOM_REGS                    -> (address % 2 == 0) ? (peekCustomRegister(address) >> 8) & 0xFF : peekCustomRegister(address) & 0xFF;
            case RTC                            -> batteryClock.readByte(address);
            case CIA_A, CIA_B, CIA_BOTH         -> {
                int word = peekCiaData(route.target, address);
                yield (address % 2 == 0) ? (word >> 8) & 0xFF : word & 0xFF;
            }
            default                             -> 0x00;
        };
    }

    public int peekWord(int address) {
        Gary.Route route = gary.route(address, Gary.AccessType.READ, true, true, true);
        if (!route.ok) return 0;
        int eff = route.effectiveAddress;
        return switch (route.target) {
            case CHIP_RAM                       -> chipRAM.readWord(eff);
            case KICKSTART_ROM, OVERLAY_ROM     -> rom.readWord(eff);
            case SLOW_RAM                       -> slowRAM.readWord(eff);
            case FAST_RAM                       -> (fastRam != null) ? fastRam.ram().readWord(eff) : 0x0000;
            case CUSTOM_REGS                    -> peekCustomRegister(address);
            case RTC                            -> batteryClock.readWord(address);
            case CIA_A, CIA_B, CIA_BOTH         -> peekCiaData(route.target, address);
            default                             -> 0x0000;
        };
    }

    /**
     * Lettura di uno spazio CIA per il debugger. Passa da {@code MOS8520.peekRegister},
     * non da {@code readRegister}: quest'ultima, sull'ICR, rilascia la linea /IRQ e arma
     * il pipeline di ack — cioè un dump di memoria che attraversi $BFExxx cancellerebbe
     * un interrupt pendente della macchina osservata.
     */
    private int peekCiaData(Gary.Target target, int address) {
        int reg = (address >> 8) & 0x0F;
        int result = lastBusData;
        if (target == Gary.Target.CIA_A || target == Gary.Target.CIA_BOTH) {
            result = (result & 0xFF00) | (ciaA.peekRegister(reg) & 0xFF);
        }
        if (target == Gary.Target.CIA_B || target == Gary.Target.CIA_BOTH) {
            result = (result & 0x00FF) | ((ciaB.peekRegister(reg) & 0xFF) << 8);
        }
        return result;
    }

    public int peekLong(int address) {
        int hi = peekWord(address);
        int lo = peekWord(address + 2);
        return (hi << 16) | (lo & 0xFFFF);
    }

    public int peekRAMByte(int address) {
        return peekByte(address);
    }

    public int peekRAMWord(int address) {
        return peekWord(address);
    }

    public int peekRAMLong(int address) {
        return peekLong(address);
    }

    public boolean isTraceEnabled() {
        return false;
    }

    public int getLastBusData() {
        return lastBusData;
    }
}

