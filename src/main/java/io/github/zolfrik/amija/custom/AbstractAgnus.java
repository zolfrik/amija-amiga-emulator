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
        SLOT_KBD, SLOT_TXD, SLOT_RXD, SLOT_POT, SLOT_TER,
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
        POT_DISCHARGE, POT_CHARGE,
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
        if (inVBlankArea(vpos)) return;
        if (vpos == sprVStart[idx]) sprDmaEnabled[idx] = true;
        if (vpos == sprVStop[idx])  sprDmaEnabled[idx] = false;
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

        dmacon = newValue;
        updateDmaFlags();
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
        boolean ersyWasActive = ersyActive();
        if (denise != null) denise.writeRegister(0x100, value);
        if (ersyWasActive && !ersyActive()) {
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
        scrollOdd = (bplcon1 & 0x0E) >> 1;
        scrollEven = (bplcon1 & 0xE0) >> 5;
        if (denise != null) denise.writeRegister(0x102, value);
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
            case SLOT_POT:
                if (paula != null) paula.servicePotEvent(id);
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

    // STEP 14/P-15 (PERFORMANCE_REPORT_FINAL): busOwners come int[] di ordinali
    // invece di BusOwner[]. Arrays.fill(int[]) è più veloce del fill di un array di
    // reference (niente card-marking) ed è eseguito ~15.600 volte/s (eolHandler).
    // 0 == BusOwner.NONE.ordinal(), quindi il default int 0 coincide con NONE.
    // Il confronto per ordinali è equivalente al confronto per reference enum.
    // 228 e non 227: la riga lunga NTSC (LOL) ha un CCK in più, indice 227.
    private final int[] busOwners = new int[228];


    /** DIAG (audit floppy 2b): logga le letture CPU di registri custom/CIA con PC e
     *  valore, per trovare quale stato di timing (raster/CIA) il loader campiona in
     *  modo diverso a velocità CPU diverse. Time-gated dall'harness. */
    public static volatile boolean diagLogTiming = false;

    /** DIAG (Fase A-ter): logga ogni cambio del bit /SIDE su CIA-B PRB (cosa SCRIVE
     *  il loader) con master-cycle/raster, per distinguere se il side sbagliato alla
     *  lettura #11 di IK+ è un wrong-write (loader calcola lato 0) o un wrong-latch
     *  (scrive 1 ma latchiamo 0). Vedi docs/sae-dskr-sequence.md. */
    public static volatile boolean diagLogSide = false;
    private int prevSideBitLogged = -1;

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
     * Numero massimo di righe per frame.
     * PAL: 312 (righe 0-311), NTSC: 262 (righe 0-261).
     */
    // STEP 14/P-12 (PERFORMANCE_REPORT_FINAL): final → il JIT può fare constant
    // folding nei confronti in updateBeam/risingEdge. maxVpos è assegnato nel
    // costruttore (262 NTSC / 312 PAL), maxHpos è costante.
    private final int maxVpos;

    /**
     * Numero di system clock della riga <b>corta</b>: 454 SC = 227 CCK.
     * È la lunghezza di ogni riga in PAL e della riga corta in NTSC.
     */
    private static final int MAXHPOS_SHORT = 454;

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
    // Modello minimale e fedele all'osservabile: mentre ERSY è attivo, le letture
    // VPOSR/VHPOSR restituiscono la posizione latchata al fronte di salita di ERSY
    // (pennello "fermo"), così i due HP coincidono e il rilevamento conclude 0.
    // Non abbiamo mai un genlock, quindi ERSY implica sempre sync-stopped.
    private int     ersyFreezeVpos = -1;      // -1 = ERSY non attivo / non latchato
    private int     ersyFreezeHpos = -1;

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
     * Contatore fetch DMA dei bitplane nella riga corrente.
     * Tiene traccia di quanti fetch sono stati fatti per determinare
     * quale bitplane fetchare al prossimo slot.
     */
    /**
     * BPU campionato all'apertura della finestra di fetch (CCK == ddfCckStart)
     * e usato per tutta la riga. Evita che un cambio di BPU a metà riga avvii
     * un fetch parziale che disallinea i piani. Vedi "2. Bitplane DMA".
     */
    private int fetchUnitBpu = 0;

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
        } else {
            this.maxVpos   = 312;
            this.beamcon0  = 0x0020;
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
        if (this.ciaA != null) {
            this.ciaA.setCpuPcSupplier(() -> cpu.getOpcodeAddress());
            this.ciaA.setClkSupplier(() -> this.masterClock);
            this.ciaA.getPinSet().setClkSupplier(() -> this.masterClock);
            this.ciaA.getPinSet().setPcSupplier(() -> cpu.getOpcodeAddress());
        }
        if (this.ciaB != null) {
            this.ciaB.setCpuPcSupplier(() -> cpu.getOpcodeAddress());
            this.ciaB.setClkSupplier(() -> this.masterClock);
            this.ciaB.getPinSet().setClkSupplier(() -> this.masterClock);
            this.ciaB.getPinSet().setPcSupplier(() -> cpu.getOpcodeAddress());
        }

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
        this.hpos = INITIAL_HPOS;

        // Arma il tick E-clock delle CIA come evento (Step 1, event-driven). I test
        // non chiamano agnus.reset(): l'arm in costruzione garantisce che SLOT_CIAA
        // scatti dal primo E-clock (masterClock==10). internalReset lo ri-arma.
        scheduleAbs(EventSlot.SLOT_CIAA, masterClock + 10, EventID.CIA_EXECUTE);

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
        // TOD CIA-A pin LOW: temporizzato sul raster (a inizio frame, dopo aver
        // alzato il pin in updateBeam), quindi resta nel loop SC — NON è event-driven.
        // La parte OVL/LED è ora in updateCiaOvlLed() (granularità E-clock,
        // serviceCiaEvent). Il test vpos==0 corto-circuita su quasi tutti i cicli.
        if (ciaA != null && vpos == 0 && hpos > 20) {
            var todPinA = ciaA.getPinSet().getTodPin();
            if (todPinA.getPinState()) todPinA.setExternalInput(false);
        }
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
            // BLS (vAmiga): la CPU ha atteso il bus per ≥2 CCK → cede-blitter attivo.
            // Calcolato PRIMA dell'arbitraggio, dal conteggio accumulato nei CCK scorsi.
            bls = cpuBusWaitCck >= 2;
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

            handleBitplaneDrawEvent(cck);

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

            // 2. Bitplane DMA
            if (!busTakenByCustom && isBitplaneDMAActive()) {
                boolean hires = denise.isHires();    // = (bplcon0 & 0x8000)!=0, cached
                // BPU viene CAMPIONATO una sola volta per riga, all'apertura della
                // finestra di fetch (CCK == ddfCckStart), e usato per tutta la riga.
                //
                // Sull'hardware reale la sequenza di fetch dei bitplane è "armata" a
                // DDFSTRT: il numero di piani da prelevare è fissato lì. Un aumento di
                // BPU a metà riga (dopo DDFSTRT) NON avvia un fetch su quella riga.
                //
                // L'intro di Monkey Island accende i bitplani (BPU 0→5) a metà della
                // riga di transizione del bordo (riga 43, a CCK 124, ben oltre DDFSTRT
                // a CCK 56). Campionando BPU vivo ad ogni slot, quella riga prelevava
                // ~11 word parziali: questo (a) disallineava i piani pari/dispari di
                // 16px e (b) faceva avanzare TUTTI i puntatori di 11 word, traslando
                // l'intera immagine per il resto del frame (testo "sdoppiato" con
                // frange di colore). Con il latch a DDFSTRT la riga 43 vede BPU=0 →
                // 0 fetch → nessun drift, nessuna traslazione. Le righe con BPU stabile
                // (Workbench, gioco normale) non cambiano comportamento.
                // Latch all'apertura della finestra (primo match di DDFSTRT vivo):
                // campiona sia BPU sia ddfCckStart e li usa per tutta la riga, così un
                // cambio di DDFSTRT a metà riga non sposta la fase/finestra di fetch.
                if (!fetchWindowOpened && cck == ddfCckStart) {
                    fetchWindowOpened = true;
                    fetchUnitDdfStart = ddfCckStart;
                    fetchUnitBpu = denise.getBpu();  // = min((bplcon0>>12)&7,6), cached
                    bpuRelatchCck = -1;
                }
                // BPLCON0 scritto a finestra aperta: BPU efficace da write+4 CCK
                // (pipeline WinUAE; vedi setBPLCON0 per le misure [WRGA]).
                if (bpuRelatchCck >= 0 && cck >= bpuRelatchCck) {
                    fetchUnitBpu = denise.getBpu();
                    bpuRelatchCck = -1;
                }
                int bplIdx = fetchWindowOpened
                        ? getBitplaneIndexForRasterPosition(hpos, fetchUnitBpu, hires)
                        : -1;

                if (bplIdx >= 0) {
                    handleBitplaneDMA(cck, bplIdx, hires);
                    owner = OWNER_VALUES[BusOwner.BPL1.ordinal() + bplIdx];
                    busTakenByCustom = true;
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
            if (!busTakenByCustom && isSpriteDMAEnabled()
                    && vpos >= SPRITE_RESET_LINE && vpos < maxVpos
                    && DMA_SLOT_TABLE[cck] == DMASLOT_SPRITE) {
                int sprIdx = (cck - SLOT_SPRITE_0) / 4;
                if (spriteNeedsFetch(sprIdx)) {
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

        // CIA-B TOD: connected to H-Sync on Amiga 500
        if (nextHpos == 1) {
            if (ciaB != null) {
                ciaB.getPinSet().getTodPin().setExternalInput(true);
            }
        } else if (nextHpos == 21) {
            if (ciaB != null) {
                ciaB.getPinSet().getTodPin().setExternalInput(false);
            }
        }

        if (nextHpos < maxHpos) {
            // Ancora nella stessa riga — nessuna azione rilevante
            return;
        }

        // ── Fine riga ──────────────────────────────────────────────────────
        scheduleSyncEvent(EVFL_HSYNC);
        scheduleSyncEvent(EVFL_EOL);

        // LOL: su Agnus NTSC la riga alterna 227/228 CCK. Il toggle vale per la riga
        // che sta per iniziare (WinUAE: toggle nell'hsync handler, poi setmaxhpos()).
        updateLineLength(mode == VideoMode.NTSC && !lol);

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
        // --- CIA-A TOD Timing ---
        // CIA-A TOD: pulse at start of VBlank (50 Hz PAL power signal).
        // (La VERTB NON va qui: vedi assertVertbInterrupt() spostata a vpos==0.)
        if (vpos == maxVpos - 1) {
            if (ciaA != null) {
                ciaA.getPinSet().getTodPin().setExternalInput(true);
            }
        }

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
        if (ersyActive()) {
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
            updateBitplaneVerticalWindow();
            updateSpriteDMA(vpos);
            return;
        }

        // ── Fine frame (Start of Line 0) ───────────────────────────────────
        vpos = 0;
        frameCount++;

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
        updateBitplaneVerticalWindow();

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
        if (diagLogSide) {
            int sb = side ? 1 : 0;
            if (sb != prevSideBitLogged) {
                prevSideBitLogged = sb;
                System.out.printf("[SIDE] write side=%d (/SIDE=%s) prb=$%02X cyc=%d vpos=%d hpos=%d%n",
                        sb, side ? "LOW" : "HIGH", ciab_prb & 0xFF, masterClock, vpos, hpos);
            }
        }
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

        if (!sprDmaEnabled[sprIdx]) {
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

    private boolean spriteNeedsFetch(int sprIdx) {
        if (sprIdx < 0 || sprIdx > 7) return false;
        // Fase DATA: sprDmaEnabled è true nelle righe VSTART..VSTOP-1.
        if (sprDmaEnabled[sprIdx]) return true;
        // Fase POS/CTL: fetch quando vpos raggiunge sprVStop (mai a riga 0).
        return vpos > 0 && vpos == sprVStop[sprIdx];
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
        if (isSpriteDMAEnabled() && nextVpos == SPRITE_RESET_LINE) {
            // Forza il fetch POS/CTL alla riga 25 per tutti gli sprite.
            for (int i = 0; i < 8; i++) sprVStop[i] = SPRITE_RESET_LINE;
            Arrays.fill(sprDmaEnabled, false);
            return;
        }
        if (nextVpos >= maxVpos) {
            Arrays.fill(sprDmaEnabled, false);
            return;
        }
        if (!inVBlankArea(nextVpos)) {
            for (int i = 0; i < 8; i++) {
                if (nextVpos == sprVStart[i]) sprDmaEnabled[i] = true;
                if (nextVpos == sprVStop[i])  sprDmaEnabled[i] = false;
            }
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
        int effStop = getEffectiveDdfStopCck(hires);
        if (cck >= effStop - (hires ? 4 : 8)) {
            int mod = (bplIdx % 2 == 0) ? bpl1mod : bpl2mod;
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

    private void handleBitplaneDrawEvent(int cck) {
        if (denise == null || !isBitplaneDMAActive()) return;

        int bplcon0Val = denise.getBplcon0();
        int bplCount = Math.min((bplcon0Val >> 12) & 0x7, 6);
        if (bplCount <= 0) return;

        boolean hires = (bplcon0Val & 0x8000) != 0;
        int cycleLen = hires ? 4 : 8;
        int mask = cycleLen - 1;
        boolean drawOdd = (cck & mask) == (scrollOdd & mask);
        boolean drawEven = (cck & mask) == (scrollEven & mask);
        if (drawOdd || drawEven) {
            denise.drawBitplanesAtPixel(cck * 4 - 70, drawOdd, drawEven);
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
            default -> { /* IDLE/S0/S1/S_VMA: niente da decidere o trasferire qui */ }
        }
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

        // Chip RAM / Slow RAM / Custom: sincronizzazione al chip-bus (DMA + CCK).
        //
        // OPEN_BUS nelle finestre chip-decodificate: sull'A500 anche un accesso che
        // non seleziona alcuna risorsa ma cade nelle aree decodificate dal chipset
        // paga la sincronizzazione chip-bus (l'arbitro è comunque Agnus). Oracolo
        // WinUAE `fill_ce_banks` (memory.cpp): $000000-$1FFFFF e $D00000-$DFFFFF
        // sono CE_MEMBANK_CHIP16 INCONDIZIONATI — inclusi i mirror di chip RAM
        // oltre la taglia fisica e la dummy-bank $D80000-$DDFFFF ("A500 = custom
        // chips"). Le altre aree open-bus ($200000-$9FFFFF, $C80000-$CFFFFF) sono
        // FAST16: DTACK immediato, nessuna contesa. Root di G1/NEG2l: l'handler
        // irq6 fa `neg.l $DDF180` e da noi l'EA open-bus saltava la contesa →
        // gli accessi comprimevano a passo 2 CCK invece di quantizzarsi sugli
        // slot liberi (BPU=6) → marker bianco −8 CCK vs WinUAE.
        int physAddr = cpuBusReq.address & 0xFFFFFF;
        boolean openBusChipWindow = cpuCurrentRoute.target == Gary.Target.OPEN_BUS
                && (physAddr < 0x200000 || (physAddr >= 0xD00000 && physAddr < 0xE00000));
        boolean needsChipBusSync = cpuCurrentRoute.target == Gary.Target.CHIP_RAM
                || cpuCurrentRoute.target == Gary.Target.SLOW_RAM
                || cpuCurrentRoute.target == Gary.Target.CUSTOM_REGS
                || openBusChipWindow;

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

        // DIAG (phase-lock cycle-exact): hpos di OGNI accesso chip-bus della CPU
        // (una volta per accesso), per analizzare la fase rispetto al CCK.
        if (diagLogTiming) {
            System.out.printf("[ACC] pc=%06X tgt=%s a=%06X %s hpos=%d%n",
                    cpu.getOpcodeAddress() & 0xFFFFFF, cpuCurrentRoute.target,
                    addr & 0xFFFFFF, cpuBusReq.read ? "R" : "W", hpos);
        }
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

            if (diagLogTiming && (isCia || cpuCurrentRoute.target == Gary.Target.CUSTOM_REGS)) {
                System.out.printf("[TMRD] pc=%06X tgt=%s a=%03X v=%04X vpos=%d hpos=%d%n",
                        cpu.getOpcodeAddress() & 0xFFFFFF, cpuCurrentRoute.target,
                        cpuCurrentRoute.effectiveAddress & 0x1FE, word, vpos, hpos);
            }
            // DIAG (Fase D/E): valore delle DATA-read del loader IK+ nelle strutture
            // in slow-RAM ($C08000-$C0A000), per trovare quale valore (derivato dal
            // timing) diverge e fa prendere il branch di fallimento $C07BD4.
            if (diagLogTiming && cpuCurrentRoute.target == Gary.Target.SLOW_RAM
                    && (addr & 0xFFFFFF) >= 0xC08000 && (addr & 0xFFFFFF) < 0xC0A000) {
                System.out.printf("[SDAT] pc=%06X a=%06X v=%04X%n",
                        cpu.getOpcodeAddress() & 0xFFFFFF, addr & 0xFFFFFF, word);
            }

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

    // ── ERSY-freeze (vedi campi ersyFreezeVpos/Hpos) ────────────────────────────
    // Vero se BPLCON0 ha ERSY (bit 1) attivo. Non emuliamo genlock, quindi ERSY
    // implica sempre "sync fermo" (WinUAE issyncstopped: (con0&2) && !genlock).
    private boolean ersyActive() {
        return denise != null && (denise.getBplcon0() & 0x0002) != 0;
    }

    // Latcha la posizione del pennello al primo accesso sotto ERSY; la rilascia
    // quando ERSY cade. Per il rilevamento genlock ($FCAF96) i due VHPOSR letti
    // sotto ERSY restituiscono così lo stesso HP → esito "nessun genlock".
    private void updateErsyFreeze() {
        if (ersyActive()) {
            if (ersyFreezeVpos < 0) {
                ersyFreezeVpos = vposFrozen ? frozenVpos : vpos;
                ersyFreezeHpos = vposFrozen ? frozenHpos : hpos;
            }
        } else {
            ersyFreezeVpos = -1;
            ersyFreezeHpos = -1;
        }
    }

    /** Vpos usato dalle letture VPOSR/VHPOSR (ERSY-freeze → latch → live). */
    private int beamReadVpos() {
        updateErsyFreeze();
        if (ersyFreezeVpos >= 0) return ersyFreezeVpos;
        return beamReadLatchVpos >= 0 ? beamReadLatchVpos : (vposFrozen ? frozenVpos : vpos);
    }

    /** Hpos usato dalle letture VPOSR/VHPOSR (ERSY-freeze → latch → live). */
    private int beamReadHpos() {
        updateErsyFreeze();
        if (ersyFreezeHpos >= 0) return ersyFreezeHpos;
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
    public void copperWrite(int regAddr, int value) {
        regAddr &= 0x1FE;

        if (regAddr < 0x040) {
            return;
        }

        if (regAddr < 0x080 && !copper.isDanger()) {
            return;
        }

        __inCopperWrite = true;
        try { writeCustomRegister(regAddr, value); } finally { __inCopperWrite = false; }
        if (true) return;
        writeCustomRegister(regAddr, value);
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
    public static boolean neutralFloatingBus = false;

    private int faultyCustomRead(int regAddr) {
        int busVal = neutralFloatingBus ? 0 : (openBusLatch & 0xFFFF);
        writeCustomRegister(regAddr, busVal);
        return busVal;
    }

    private int readCustomRegisterImpl(int regAddr) {
        regAddr &= 0x1FE;

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
            return paula != null ? paula.readRegister(regAddr) : 0xFFFF;
        }

        // Strobe Denise
        if (regAddr == 0x038 || regAddr == 0x03A || regAddr == 0x03C) {
            if (denise != null) denise.readRegister(regAddr);
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
            if (io.github.zolfrik.amija.custom.AbstractDenise.DBG_COLOR && regAddr == 0x180) {
                int v = value & 0xFFF;
                boolean visible = vpos >= 30 && vpos < 300;
                String k = (__inCopperWrite ? "COP-" : "CPU-") + (v == 0x80B ? "purple" : v == 0x000 ? "black" : "other") + (visible ? "/vis" : "/vbl");
                io.github.zolfrik.amija.custom.AbstractDenise.DBG_C00_VPOS.merge(k, 1L, Long::sum);
            }
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
                // "LOL is always reset when VPOSW is written to. Implemented in all
                // NTSC Agnus versions and ECS/AGA Agnus in NTSC mode" (WinUAE
                // custom.cpp, VPOSW): la riga corrente torna corta.
                if (lol) updateLineLength(false);
                break;
            case 0x02C: // VHPOSW
                vpos = (vpos & 0x100) | ((value >> 8) & 0xFF);
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
                if (denise != null) denise.writeRegister(0x08E, value);
                break;
            case 0x090: // DIWSTOP
                diwStop = value;
                diwVStop = (diwStop >> 8) & 0xFF;
                if ((diwStop & 0x8000) == 0) diwVStop += 256;
                if (denise != null) denise.writeRegister(0x090, value);
                break;

            case 0x092: // DDFSTRT
                ddfStrt = value;
                ddfCckStart = ddfStrt & ddfMask();
                break;
            case 0x094: // DDFSTOP
                ddfStop = value;
                ddfCckStop = decodeDdfStopCck(ddfStop);
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
                bplPt[bidx] = (bplPt[bidx] & 0x0000FFFF) | ((value << 16) & dmaPtrHiMask);
                bplPtrInternal[bidx] = (bplPtrInternal[bidx] & 0x0000FFFF) | (bplPt[bidx] & dmaPtrHiMask);
                break;
            }
            case 0x0E2: case 0x0E6: case 0x0EA: case 0x0EE: case 0x0F2: case 0x0F6: {
                int bidx = (regAddr - 0x0E2) / 4;
                bplPt[bidx] = (bplPt[bidx] & dmaPtrHiMask) | (value & 0xFFFE);
                bplPtrInternal[bidx] = bplPt[bidx];
                break;
            }

            case 0x108: // BPL1MOD
                bpl1mod = (short) (value & 0xFFFE);
                break;
            case 0x10A: // BPL2MOD
                bpl2mod = (short) (value & 0xFFFE);
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
                if (beamcon0Writable()) beamcon0 = value;
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
            hpos = INITIAL_HPOS;         // la prima riga dura 1 CCK: vedi INITIAL_HPOS
            frameCount = 1;
        }
        Arrays.fill(trigger, NEVER);
        Arrays.fill(eventIds, EventID.EVENT_NONE);
        activeSlotMask = 0L;
        nextTrigger = NEVER;
        cpuBusIdle = false; // forza un azzeramento handshake al primo ciclo idle post-reset
        // Ri-arma il tick E-clock CIA: lo scheduler è appena stato azzerato e l'evento
        // SLOT_CIAA non si auto-rischedula più (lo faceva il polling). Senza questo le
        // CIA si fermerebbero dopo un'istruzione CPU RESET (softReset).
        scheduleAbs(EventSlot.SLOT_CIAA, masterClock + 10, EventID.CIA_EXECUTE);
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
        updateLineLength(false);             // LOL: si riparte sempre da riga corta
        vposFrozen = false; frozenVpos = 0; frozenHpos = 0;
        beamReadLatchVpos = -1; beamReadLatchHpos = -1;
        cpuAccessTransferred = false; copperBusProcessed = false;
        cpuRouteCacheValid = false;
        cpuCurrentRoute = null;
        diwStrt = 0; diwStop = 0; ddfStrt = 0; ddfStop = 0;
        diwVStart = 0; diwVStop = 0; ddfCckStart = 0; ddfCckStop = 0;
        bplcon1 = 0; scrollOdd = 0; scrollEven = 0;
        bitplaneVerticalActive = false;
        Arrays.fill(bplPt, 0); Arrays.fill(sprPt, 0);
        Arrays.fill(bplPtrInternal, 0); Arrays.fill(sprPtrInternal, 0);
        Arrays.fill(sprDmaEnabled, false);   // tutti gli sprite in fase POS/CTL
        Arrays.fill(sprVStart, 0);
        Arrays.fill(sprVStop, 0);
        bpl1mod = 0; bpl2mod = 0;
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
        dmaMasterEnabled   = (dmacon & 0x0200) != 0;
        bitplaneDmaEnabled = dmaMasterEnabled && (dmacon & 0x0100) != 0; // BPLEN
        spriteDmaEnabled   = dmaMasterEnabled && (dmacon & 0x0020) != 0; // SPREN
        diskDmaEnabled     = dmaMasterEnabled && (dmacon & 0x0010) != 0; // DSKEN
        audioDmaEnabled    = dmaMasterEnabled && (dmacon & 0x000F) != 0; // AUDxEN
        copperDmaEnabled   = dmaMasterEnabled && (dmacon & 0x0080) != 0; // COPEN
        blitterDmaEnabled  = dmaMasterEnabled && (dmacon & 0x0040) != 0; // BLTEN
        bitplaneDmaActive  = bitplaneDmaEnabled && bitplaneVerticalActive;
    }

    private boolean isBitplaneDMAActive() {
        return bitplaneDmaActive;
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
        boolean start = vpos == diwVStart;
        boolean stop = vpos == diwVStop;

        if (vpos == 0) {
            bitplaneVerticalActive = (diwVStart == 0) && (diwVStop != 0);
            if (bitplaneVerticalActive) bitplaneDmaOnAtWindowStart = bitplaneDmaEnabled;
        } else if (stop) {
            bitplaneVerticalActive = false;
        } else if (start) {
            bitplaneVerticalActive = true;
            // Stato del DMA bitplane all'APERTURA della finestra di display: serve a
            // distinguere un frame parziale (bitplane spenti in cima, accesi più giù →
            // mano insert-disk del KS) da uno split-screen legittimo (bitplane accesi
            // in cima, commutati a metà → pannello verbi/inventario di Monkey Island 2).
            bitplaneDmaOnAtWindowStart = bitplaneDmaEnabled;
        }
        // Vertical blank di Agnus: il VB inizia all'ULTIMA riga del field
        // (WinUAE custom.cpp vsync: `vpos == maxvpos + lof_store - 1` → agnus_vb,
        // che inibisce il fetch bitplane). PAL long frame: v312; short: v311.
        // Senza questo gate fetchavamo bitplane anche su v312 mentre WinUAE no
        // → il .loop di bbusy0 girava a 18 cck/iter invece di 17 su quella riga
        // → fase del beam-lock del frame successivo sfasata (bande in cima).
        // NB: il VB hardware copre anche v0..~v25; qui modelliamo solo la riga
        // d'inizio (subset sicuro: nessun test/gioco fetcha in quell'area).
        boolean lastLineOfField = vpos == ((field == 0) ? maxVpos : maxVpos - 1);
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

    public boolean isCopperEnabled() {
        return copperDmaEnabled;
    }

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
        if (cck < fetchUnitDdfStart || cck >= getEffectiveDdfStopCck(hires) || bplCount <= 0) return -1;
        if ((hpos & 1) != 0) return -1;

        int plane = getBitplaneIndexForFetchUnit(cck - fetchUnitDdfStart, hires);
        return plane < bplCount ? plane : -1;
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
    public boolean isBlitterFinished() { return !blitter.isBusy(); }
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
        return vpos < (mode == VideoMode.PAL ? 26 : 20);
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
            case CUSTOM_REGS                    -> (address % 2 == 0) ? (readCustomRegister(address) >> 8) & 0xFF : readCustomRegister(address) & 0xFF;
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
            case CUSTOM_REGS                    -> readCustomRegister(address);
            case RTC                            -> batteryClock.readWord(address);
            case CIA_A, CIA_B, CIA_BOTH         -> peekCiaData(route.target, address);
            default                             -> 0x0000;
        };
    }

    private int peekCiaData(Gary.Target target, int address) {
        int reg = (address >> 8) & 0x0F;
        int result = lastBusData;
        if (target == Gary.Target.CIA_A || target == Gary.Target.CIA_BOTH) {
            result = (result & 0xFF00) | (ciaA.readRegister(reg) & 0xFF);
        }
        if (target == Gary.Target.CIA_B || target == Gary.Target.CIA_BOTH) {
            result = (result & 0x00FF) | ((ciaB.readRegister(reg) & 0xFF) << 8);
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

