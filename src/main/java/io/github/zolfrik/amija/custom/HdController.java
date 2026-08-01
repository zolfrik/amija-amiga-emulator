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

import io.github.zolfrik.amija.memory.HardFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.function.BooleanSupplier;

/**
 * Controller hard disk "virtuale": scheda Zorro II con AutoConfig + boot ROM.
 *
 * <p>Strategia (mutuata da vAmiga {@code HdController}): la scheda espone una piccola
 * <b>expansion ROM</b> ({@code exprom}, un driver 68k {@code virtualhd.device}) che
 * il Kickstart rileva via AutoConfig (flag {@code ERTF_DIAGVALID}) e installa come
 * dispositivo di boot. Il driver non parla con hardware reale: comunica con questo
 * emulatore <i>host</i> scrivendo valori "magici" in alcune porte mappate subito
 * dopo la ROM. È l'host (questo codice) a eseguire materialmente le letture/scritture
 * del disco copiando i byte fra il file {@code .hdf} e la RAM Amiga ({@link Bus}).
 *
 * <p>Questo approccio è volutamente <b>minimale e veloce</b>: niente emulazione del
 * bus SCSI/IDE, le operazioni di I/O sono {@code memcpy}. È sufficiente per avviare i
 * giochi installati su {@code .hdf} con filesystem in ROM (OFS/FFS) e, in prospettiva,
 * per WHDLoad.
 *
 * <p>Protocollo delle porte (offset relativi a {@code baseAddr + initDiagVec}, dove
 * {@code EXPROM_SIZE} è la dimensione della ROM):
 * <ul>
 *   <li>read  {@code EXPROM_SIZE+0..6}: numero partizioni / numero driver FS /
 *       flag "disabilita autoboot" / cartelle condivise (0);</li>
 *   <li>write {@code EXPROM_SIZE+0/+2}: word alta/bassa del puntatore a una struttura
 *       in RAM Amiga;</li>
 *   <li>write {@code EXPROM_SIZE+4}: comando (0xFEDE=I/O, 0xFEDF=init partizione, …).</li>
 * </ul>
 */
public final class HdController implements AutoConfigDevice {

    // --- Costanti AutoConfig (expansion/expansion.h) ---
    private static final int ERT_ZORROII    = 0xC0;
    private static final int ERTF_DIAGVALID = 0x10;

    // --- Offset nella IOStdReq (exec/io.h) ---
    private static final int IO_UNIT    = 24;
    private static final int IO_COMMAND = 28;
    private static final int IO_ERROR   = 31;
    private static final int IO_ACTUAL  = 32;
    private static final int IO_LENGTH  = 36;
    private static final int IO_DATA    = 40;
    private static final int IO_OFFSET  = 44;

    // --- Comandi trackdisk/exec rilevanti ---
    private static final int CMD_READ   = 2;
    private static final int CMD_WRITE  = 3;
    private static final int TD_FORMAT  = 11;
    private static final int IOERR_NOCMD     = 0xFD; // -3
    private static final int IOERR_BADADDRESS = 0xFB; // -5

    // --- ROM ---
    private static final byte[] EXPROM = loadExprom();
    private static final int EXPROM_SIZE = EXPROM.length;

    /** Stato della scheda nella sequenza di AutoConfig. */
    private enum State { UNCONFIGURED, CONFIGURED, SHUTUP }

    /** Accesso alla RAM Amiga (chip/slow/fast) per byte, fornito da {@link Agnus}. */
    public interface Bus {
        int  readByte(int addr);
        void writeByte(int addr, int value);

        /**
         * Copia in blocco {@code len} byte da {@code src[srcOff..]} nella RAM Amiga a
         * {@code addr}. Le implementazioni usano {@code System.arraycopy} quando il range
         * ricade in un singolo banco; questo default ripiega sulla copia byte-per-byte.
         */
        default void writeBlock(int addr, byte[] src, int srcOff, int len) {
            for (int i = 0; i < len; i++) writeByte(addr + i, src[srcOff + i] & 0xFF);
        }

        /** Copia in blocco {@code len} byte dalla RAM Amiga a {@code addr} in {@code dst[dstOff..]}. */
        default void readBlock(int addr, byte[] dst, int dstOff, int len) {
            for (int i = 0; i < len; i++) dst[dstOff + i] = (byte) readByte(addr + i);
        }
    }

    private final int objid;
    private final HardFile hdf;
    private final int partitionNameBase;     // offset di numerazione DH per board in catena
    private final byte[] rom;                 // copia della ROM con il device name unico
    private final BooleanSupplier floppyInDf0; // se df0 ha un disco → disabilita autoboot
    private Bus bus;

    private State state;
    private int   baseAddr;
    private int   pointer;                     // puntatore trasmesso dal driver

    // LED di attività: istante (nanoTime) dell'ultima lettura/scrittura sul disco.
    // Aggiornato dal thread di emulazione a ogni comando I/O (eventi rari → costo nullo)
    // e letto una volta per frame dal thread di display: una semplice finestra temporale
    // mantiene il LED acceso il tempo sufficiente perché un singolo burst sia visibile.
    // Finestra di "minima durata accesa" del LED dall'ultima lettura/scrittura. Durante un
    // caricamento l'I/O arriva a raffiche con micro-pause fra un burst e l'altro (la CPU
    // elabora/decomprime fra una lettura e la successiva); una finestra troppo stretta fa
    // lampeggiare il LED anche mentre il disco è "in uso". 250 ms tiene il LED stabilmente
    // acceso attraverso queste micro-pause, restando comunque spento durante l'inattività
    // reale (es. gioco in esecuzione dalla RAM). Confronto: vAmiga torna IDLE 100 ms (di
    // tempo EMULATO) dopo l'ultimo I/O; alla nostra cadenza sotto-realtime 250 ms di tempo
    // host sono dello stesso ordine. WinUAE usa una decadenza analoga su più frame.
    private static final long ACTIVITY_WINDOW_NANOS = 250_000_000L; // ~250 ms
    private volatile long lastActivityNanos = Long.MIN_VALUE;

    public HdController(int objid, HardFile hdf, int partitionNameBase,
                        BooleanSupplier floppyInDf0) {
        this.objid = objid;
        this.hdf = hdf;
        this.partitionNameBase = partitionNameBase;
        this.floppyInDf0 = floppyInDf0 != null ? floppyInDf0 : () -> false;
        this.rom = EXPROM.clone();
        patchDeviceName(this.rom, objid);
        this.state = pluggedIn() ? State.UNCONFIGURED : State.SHUTUP;
    }

    public void setBus(Bus bus) { this.bus = bus; }

    public boolean pluggedIn() { return hdf != null; }

    public HardFile hardFile() { return hdf; }

    /** Numero di partizioni esposte (per la numerazione DH delle board successive). */
    public int numPartitions() { return hdf != null ? hdf.numPartitions() : 0; }

    /**
     * {@code true} se il disco ha eseguito una lettura/scrittura nell'ultima finestra di
     * attività (per accendere il LED HD). Letto dal thread di display, una volta per frame.
     */
    public boolean ledActive() {
        long t = lastActivityNanos;
        return t != Long.MIN_VALUE && (System.nanoTime() - t) < ACTIVITY_WINDOW_NANOS;
    }

    // =========================================================================
    // AutoConfigDevice
    // =========================================================================

    @Override public boolean autoconfiguring() { return state == State.UNCONFIGURED; }
    @Override public int configuredBaseAddr()  { return baseAddr; }

    @Override
    public void reset() {
        this.state = pluggedIn() ? State.UNCONFIGURED : State.SHUTUP;
        this.baseAddr = 0;
        this.pointer = 0;
        this.lastActivityNanos = Long.MIN_VALUE;
    }

    /** Byte del descrittore AutoConfig (idem schema di {@link FastRam}). */
    private int descriptorByte(int idx) {
        int serial = 31415 + objid;
        switch (idx) {
            case 0x0: return (ERT_ZORROII | ERTF_DIAGVALID) | 0b001; // type | size(1 pagina)
            case 0x1: return 0x88;                  // product
            case 0x2: return 0x00;                  // flags
            case 0x3: return 0x00;
            case 0x4: return (0x0539 >> 8) & 0xFF;   // manufacturer hi
            case 0x5: return 0x0539 & 0xFF;         // manufacturer lo
            case 0x6: return (serial >> 24) & 0xFF;  // serial
            case 0x7: return (serial >> 16) & 0xFF;
            case 0x8: return (serial >>  8) & 0xFF;
            case 0x9: return serial & 0xFF;
            case 0xA: return 0x00;                   // initDiagVec hi
            case 0xB: return 0x40;                   // initDiagVec lo
            default:  return 0x00;
        }
    }

    @Override
    public int peekConfigByte(int addr) {
        if (state != State.UNCONFIGURED) return 0xFF;
        int offset = addr & 0xFF;
        int result = 0xFF;
        if ((offset & 1) == 0 && offset < 0x40) {
            int b = descriptorByte(offset >> 2);
            int nibble = ((offset & 2) != 0) ? (b & 0x0F) : ((b >> 4) & 0x0F);
            result = (offset < 4) ? (nibble << 4) : (~(nibble << 4) & 0xFF);
        } else if (offset == 0x40 || offset == 0x42) {
            result = 0x00; // interrupt pending register
        }
        return result & 0xFF;
    }

    @Override
    public boolean pokeConfigByte(int addr, int value) {
        if (state != State.UNCONFIGURED) return false;
        switch (addr & 0xFF) {
            case 0x4A: // ec_BaseAddress (A19-A16)
                baseAddr |= (value & 0xF0) << 12;
                return false;
            case 0x48: // ec_BaseAddress (A23-A20): commit + attivazione
                baseAddr |= (value & 0xF0) << 16;
                state = State.CONFIGURED;
                return true;
            case 0x4C: // ec_ShutUp
                state = State.SHUTUP;
                return false;
            default:
                return false;
        }
    }

    // =========================================================================
    // Accesso alla pagina mappata (ROM + porte magiche)
    // =========================================================================

    /** Word letta dalla CPU all'offset di pagina (addr &amp; 0xFFFF). */
    public int peekWord(int pageOffset) {
        int off = pageOffset - 0x40; // initDiagVec
        switch (off - EXPROM_SIZE) {
            case 0: return hdf != null ? hdf.numPartitions() : 0;
            case 2: return 0;                 // numero di driver filesystem (solo ROM FS)
            case 4: return disableAutoboot() ? 1 : 0;
            case 6: return 0;                 // shared folders (non supportate)
            default:
                if (off >= 0 && off + 1 < rom.length) {
                    return ((rom[off] & 0xFF) << 8) | (rom[off + 1] & 0xFF);
                }
                if (off >= 0 && off < rom.length) {
                    return (rom[off] & 0xFF) << 8;
                }
                return 0;
        }
    }

    /** Byte letto dalla CPU all'offset di pagina. */
    public int peekByte(int pageOffset) {
        int word = peekWord(pageOffset & ~1);
        return (pageOffset & 1) == 0 ? (word >> 8) & 0xFF : word & 0xFF;
    }

    /** Word scritta dalla CPU all'offset di pagina. */
    public void pokeWord(int pageOffset, int value) {
        int off = pageOffset - 0x40;
        value &= 0xFFFF;
        if (off == EXPROM_SIZE) {
            pointer = (pointer & 0x0000FFFF) | (value << 16);
        } else if (off == EXPROM_SIZE + 2) {
            pointer = (pointer & 0xFFFF0000) | value;
        } else if (off == EXPROM_SIZE + 4) {
            switch (value) {
                case 0xFEDE: processCmd(pointer);  break;
                case 0xFEDF: processInit(pointer); break;
                case 0xFEE0: /* processResource */ break;
                case 0xFEE1: /* processInfoReq  */ break;
                case 0xFEE2: /* processInitSeg   */ break;
                default: break;
            }
        }
    }

    private boolean disableAutoboot() {
        return floppyInDf0.getAsBoolean() || hdf == null || !hdf.isBootable();
    }

    // =========================================================================
    // Comandi del driver
    // =========================================================================

    private void processCmd(int ptr) {
        if (bus == null || hdf == null) return;
        int cmd    = busReadWord(ptr + IO_COMMAND);
        long offset = busReadLong(ptr + IO_OFFSET) & 0xFFFFFFFFL;
        long length = busReadLong(ptr + IO_LENGTH) & 0xFFFFFFFFL;
        int addr   = busReadLong(ptr + IO_DATA);

        int error = 0;
        int actual = 0;
        switch (cmd) {
            case CMD_READ:
                lastActivityNanos = System.nanoTime(); // LED acceso prima del trasferimento
                error = doRead(offset, length, addr);
                actual = (int) length;
                break;
            case CMD_WRITE:
            case TD_FORMAT:
                lastActivityNanos = System.nanoTime(); // LED acceso prima del trasferimento
                error = doWrite(offset, length, addr);
                actual = (int) length;
                break;
            default:
                // CMD_RESET/UPDATE/CLEAR/STOP/START/FLUSH/TD_* di stato: no-op con successo.
                if (cmd < 1 || cmd > 18) error = IOERR_NOCMD;
                break;
        }

        busWriteByte(ptr + IO_ERROR, error & 0xFF);
        if (error == 0) busWriteLong(ptr + IO_ACTUAL, actual);
    }

    private int doRead(long offset, long length, int addr) {
        if (!hdf.inRange(offset, length)) return IOERR_BADADDRESS;
        // Trasferimento in blocco (memcpy) disco→RAM, come vAmiga mem.patch / WinUAE memcpy.
        bus.writeBlock(addr, hdf.rawData(), (int) offset, (int) length);
        return 0;
    }

    private int doWrite(long offset, long length, int addr) {
        if (!hdf.inRange(offset, length)) return IOERR_BADADDRESS;
        if (hdf.readOnly()) return 0; // scrittura silenziosamente ignorata su disco protetto
        // Trasferimento in blocco (memcpy) RAM→disco direttamente nell'array dell'immagine.
        bus.readBlock(addr, hdf.rawData(), (int) offset, (int) length);
        hdf.markModified();
        return 0;
    }

    /** Riempie il DOS environment di una partizione (struttura "devn" della exprom). */
    private void processInit(int ptr) {
        if (bus == null || hdf == null) return;
        int unit = busReadLong(ptr + 0x08);
        if (unit < 0 || unit >= hdf.numPartitions()) return;

        HardFile.Partition part = hdf.partition(unit);

        // Nome DOS "DH<n>" nel buffer puntato da devn_dosName.
        int namePtr = busReadLong(ptr + 0x00);
        if (namePtr != 0) {
            String dosName = "DH" + (partitionNameBase + unit);
            for (int i = 0; i < dosName.length(); i++) {
                bus.writeByte(namePtr + i, dosName.charAt(i));
            }
            bus.writeByte(namePtr + dosName.length(), 0);
        }

        int bootFlag = (part.flags & 1);
        if (disableAutoboot()) bootFlag = 0;

        busWriteLong(ptr + 0x0C, part.flags);       // devn_flags
        busWriteLong(ptr + 0x14, part.sizeBlock);   // devn_sizeBlock
        busWriteLong(ptr + 0x18, 0);                // devn_secOrg
        busWriteLong(ptr + 0x1C, hdf.heads());      // devn_numHeads
        busWriteLong(ptr + 0x20, 1);                // devn_secsPerBlk
        busWriteLong(ptr + 0x24, hdf.sectors());    // devn_blkTrack
        busWriteLong(ptr + 0x28, part.reserved);    // devn_resBlks
        busWriteLong(ptr + 0x30, 0);                // devn_interleave
        busWriteLong(ptr + 0x34, part.lowCyl);      // devn_lowCyl
        busWriteLong(ptr + 0x38, part.highCyl);     // devn_upperCyl
        busWriteLong(ptr + 0x3C, 30);               // devn_numBuffers
        busWriteLong(ptr + 0x40, 0);                // devn_memBufType
        busWriteLong(ptr + 0x44, 0x7FFFFFFF);       // devn_transferSize
        busWriteLong(ptr + 0x48, 0xFFFFFFFE);       // devn_addMask
        busWriteLong(ptr + 0x4C, 0);                // devn_bootPrio
        busWriteLong(ptr + 0x50, part.dosType);     // devn_dName
        busWriteLong(ptr + 0x54, bootFlag);         // devn_bootflags
        busWriteLong(ptr + 0x58, 0);                // devn_segList (0 = filesystem in ROM)
    }

    // =========================================================================
    // Helper di accesso al bus Amiga
    // =========================================================================

    private int busReadWord(int addr) {
        return (bus.readByte(addr) << 8) | bus.readByte(addr + 1);
    }

    private int busReadLong(int addr) {
        return (bus.readByte(addr) << 24) | (bus.readByte(addr + 1) << 16)
             | (bus.readByte(addr + 2) << 8) | bus.readByte(addr + 3);
    }

    private void busWriteByte(int addr, int value) {
        bus.writeByte(addr, value & 0xFF);
    }

    private void busWriteLong(int addr, int value) {
        bus.writeByte(addr,     (value >>> 24) & 0xFF);
        bus.writeByte(addr + 1, (value >>> 16) & 0xFF);
        bus.writeByte(addr + 2, (value >>>  8) & 0xFF);
        bus.writeByte(addr + 3,  value         & 0xFF);
    }

    // =========================================================================
    // ROM
    // =========================================================================

    private static byte[] loadExprom() {
        try (InputStream in = HdController.class.getResourceAsStream("/hd/exprom.bin")) {
            if (in == null) {
                System.err.println("[HDF] Risorsa /hd/exprom.bin non trovata: hard disk disabilitato");
                return new byte[0];
            }
            return in.readAllBytes();
        } catch (IOException e) {
            System.err.println("[HDF] Lettura di /hd/exprom.bin fallita: " + e.getMessage());
            return new byte[0];
        }
    }

    /** Rende unico il nome del device 68k della scheda: "virtualhd.device" → "hrddrive&lt;n&gt;.device". */
    private static void patchDeviceName(byte[] rom, int objid) {
        byte[] from = "virtualhd.device".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        String toStr = "hrddrive" + (objid % 10) + ".device"; // stessa lunghezza (16)
        byte[] to = toStr.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        if (to.length != from.length) return;
        int idx = indexOf(rom, from);
        if (idx >= 0) System.arraycopy(to, 0, rom, idx, to.length);
    }

    private static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
