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

package io.github.zolfrik.amija.memory;

import java.util.Arrays;

/**
 * ROM (Read-Only Memory) per il Kickstart e altre aree non scrivibili dell'Amiga 500.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * CARATTERISTICHE
 * ═══════════════════════════════════════════════════════════════════════════
 *
 *   - Big-endian
 *   - Bound check rigoroso su ogni accesso.
 *   - Scritture runtime IGNORATE silenziosamente (comportamento hardware reale:
 *     le ROM non reagiscono agli impulsi di scrittura sul bus, il dato va perso)
 *   - Operazioni di "programmazione" (loadImage, load) sempre permesse:
 *     rappresentano il caricamento dell'immagine ROM all'avvio dell'emulatore,
 *     non scritture runtime della CPU
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * ROM KICKSTART AMIGA 500
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Kickstart 1.3 (256 KB):
 *   ROM rom = new ROM(256 * 1024);
 *   rom.loadImage(kickstart13bytes);  // array da 262144 byte
 *   // Mappata da Gary a $FC0000–$FFFFFF e mirror a $F80000–$FBFFFF
 *
 * Kickstart 2.x / 3.x (512 KB):
 *   ROM rom = new ROM(512 * 1024);
 *   rom.loadImage(kickstart3bytes);   // array da 524288 byte
 *   // Mappata da Gary a $F80000–$FFFFFF
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * NOTE SULLE SCRITTURE
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Sull'hardware reale, un accesso in scrittura all'area ROM semplicemente non
 * ha effetto: il chip ROM non è connesso alla linea /WE del bus. Il dato
 * scritto dalla CPU viene ignorato. Agnus/Gary prevengono già la maggior parte
 * degli accessi in scrittura alla ROM, ma le scritture durante l'overlay
 * (indirizzo $000000–$07FFFF con OVL=1) vengono reindirizzate alla Chip RAM
 * dallo stesso Gary — quindi in pratica ROM.write*() non dovrebbe mai essere
 * chiamato per scritture della CPU. Se viene chiamato, il comportamento
 * corretto è ignorare silenziosamente (IGNORE policy).
 *
 * @author alessio.carotenuto
 */
public final class ROM {

    // =========================================================================
    // Stato interno
    // =========================================================================

    /** Array di byte che costituisce il contenuto della ROM. */
    private final byte[] mem;

    /** Dimensione della ROM in byte. */
    private final int size;


    /** Flag: la ROM contiene dati validi (dopo loadImage). */
    private boolean loaded = false;

    // =========================================================================
    // Costruttori
    // =========================================================================

    /**
     * Crea una ROM vuota della dimensione specificata.
     * Tutti i byte sono inizializzati a 0xFF (simula ROM non programmata).
     *
     * @param sizeBytes Dimensione in byte (deve essere > 0)
     */
    public ROM(int sizeBytes) {
        if (sizeBytes <= 0)
            throw new IllegalArgumentException("ROM size must be > 0, got: " + sizeBytes);
        this.mem  = new byte[sizeBytes];
        this.size = sizeBytes;
        // ROM non programmata = tutti 0xFF (come flash/EPROM vergine)
        Arrays.fill(this.mem, (byte)0xFF);
    }

    /**
     * Crea la ROM caricando direttamente un'immagine.
     * L'array viene copiato internamente.
     *
     * @param image Array di byte con l'immagine ROM (non deve essere null o vuoto)
     */
    public ROM(byte[] image) {
        if (image == null || image.length == 0)
            throw new IllegalArgumentException("ROM image must not be null or empty");
        this.mem  = new byte[image.length];
        this.size = image.length;
        System.arraycopy(image, 0, this.mem, 0, image.length);
        this.loaded = true;
    }

    // =========================================================================
    // Caricamento immagine (operazioni di "programmazione")
    // =========================================================================

    /**
     * Carica un'immagine ROM completa.
     *
     * Questa operazione rappresenta il caricamento del Kickstart ROM all'avvio
     * dell'emulatore — NON è una scrittura runtime della CPU.
     *
     * @param src Array sorgente di dimensione esattamente uguale a {@code size}
     * @throws IllegalArgumentException se la dimensione non corrisponde
     */
    public void loadImage(byte[] src) {
        if (src == null)
            throw new NullPointerException("src must not be null");
        if (src.length != size)
            throw new IllegalArgumentException(
                    "ROM image size mismatch: expected " + size + " bytes, got " + src.length);
        System.arraycopy(src, 0, mem, 0, size);
        this.loaded = true;
    }

    /**
     * Carica un'immagine ROM da un array più grande, specificando offset e lunghezza.
     * Utile quando l'immagine è parte di un file più grande (es. con header).
     *
     * @param src Array sorgente
     * @param off Offset nell'array sorgente
     * @param len Numero di byte da caricare (deve essere uguale a {@code size})
     */
    public void loadImage(byte[] src, int off, int len) {
        if (src == null) throw new NullPointerException("src");
        if (len != size)
            throw new IllegalArgumentException(
                    "ROM image size mismatch: expected " + size + " bytes, got " + len);
        if (off < 0 || off + len > src.length)
            throw new IndexOutOfBoundsException("Invalid range: off=" + off + " len=" + len);
        System.arraycopy(src, off, mem, 0, len);
        this.loaded = true;
    }

    /**
     * Carica un blocco di byte in una posizione specifica della ROM.
     * Usato per aggiornamenti parziali o patch dell'immagine ROM.
     *
     * @param address Indirizzo di destinazione nella ROM
     * @param src     Array sorgente
     * @param off     Offset nell'array sorgente
     * @param len     Numero di byte
     */
    public void load(int address, byte[] src, int off, int len) {
        if (src == null) throw new NullPointerException("src");
        if (off < 0 || len < 0 || off + len > src.length)
            throw new IndexOutOfBoundsException(
                    "Invalid range: off=" + off + " len=" + len + " src.length=" + src.length);

        int startIdx = idx(address);
        if (startIdx + len <= size) {
            System.arraycopy(src, off, mem, startIdx, len);
        } else {
            for (int i = 0; i < len; i++) {
                mem[idx(address + i)] = src[off + i];
            }
        }
    }

    // =========================================================================
    // Accesso in lettura
    // =========================================================================

    /**
     * Legge 1 byte (unsigned 0–255).
     *
     * @param address Indirizzo fisico (24 bit effettivi)
     * @return Valore byte unsigned
     */
    public int readByte(int address) {
        return mem[idx(address)] & 0xFF;
    }

    /**
     * Legge 1 word a 16 bit in formato big-endian.
     *
     * Il wrap della ROM garantisce il mirror hardware corretto:
     * una ROM da 256 KB usa {@code address & 0x3FFFF} → le letture a $FC0000
     * e a $F80000 (mirror) producono gli stessi dati.
     *
     * @param address Indirizzo fisico
     * @return Valore word unsigned a 16 bit
     */
    public int readWord(int address) {
        int i0 = idx(address);
        int i1 = idx(address + 1);
        return ((mem[i0] & 0xFF) << 8) | (mem[i1] & 0xFF);
    }

    /**
     * Legge 1 longword a 32 bit in formato big-endian.
     *
     * @param address Indirizzo fisico
     * @return Valore longword a 32 bit
     */
    public int readLong(int address) {
        int i0 = idx(address);
        int i1 = idx(address + 1);
        int i2 = idx(address + 2);
        int i3 = idx(address + 3);
        return ((mem[i0] & 0xFF) << 24)
             | ((mem[i1] & 0xFF) << 16)
             | ((mem[i2] & 0xFF) << 8)
             |  (mem[i3] & 0xFF);
    }

    // =========================================================================
    // Accesso in scrittura (runtime — sempre ignorato)
    // =========================================================================

    /**
     * Scrittura byte su ROM: ignorata silenziosamente.
     *
     * Sull'hardware reale le ROM non sono connesse a /WE:
     * la scrittura non ha effetto. Non lancia eccezioni per non
     * interrompere il normale flusso del bus durante il boot.
     */
    public void writeByte(int address, int value) {
    }

    /**
     * Scrittura word su ROM: ignorata silenziosamente.
     */
    public void writeWord(int address, int value) {
    }

    /**
     * Scrittura long su ROM: ignorata silenziosamente.
     */
    public void writeLong(int address, int value) {
    }

    // =========================================================================
    // Utilità di dump
    // =========================================================================

    /**
     * Legge un blocco di byte dalla ROM in un nuovo array.
     *
     * @param address Indirizzo sorgente
     * @param len     Numero di byte
     * @return Nuovo array con i dati letti
     */
    public byte[] dumpBlock(int address, int len) {
        if (len < 0) throw new IllegalArgumentException("len must be >= 0");
        byte[] out = new byte[len];
        int startIdx = idx(address);
        if (startIdx + len <= size) {
            System.arraycopy(mem, startIdx, out, 0, len);
        } else {
            for (int i = 0; i < len; i++) {
                out[i] = mem[idx(address + i)];
            }
        }
        return out;
    }

    /**
     * Hex dump di una finestra di memoria ROM.
     *
     * @param address      Indirizzo di inizio
     * @param len          Numero di byte
     * @param bytesPerLine Byte per riga di output
     * @return Stringa con il dump esadecimale
     */
    public String hexDump(int address, int len, int bytesPerLine) {
        if (len < 0 || bytesPerLine <= 0)
            throw new IllegalArgumentException("len>=0, bytesPerLine>0 required");
        StringBuilder sb = new StringBuilder(len * 3 + (len / bytesPerLine) * 12);
        for (int i = 0; i < len; i++) {
            if (i % bytesPerLine == 0) {
                if (i > 0) sb.append('\n');
                sb.append(String.format("%08X: ", address + i));
            }
            sb.append(String.format("%02X ", mem[idx(address + i)] & 0xFF));
        }
        return sb.toString();
    }

    /**
     * Calcola il checksum a 16 bit dell'immagine ROM (somma di tutte le word,
     * ignorando l'overflow). Utile per verificare l'integrità del Kickstart.
     *
     * Il Kickstart Amiga usa questa formula: la somma di tutte le word (inclusa
     * la checksum word stessa) deve dare 0xFFFFFFFF.
     *
     * @return Checksum a 32 bit (somma di tutte le longword)
     */
    public long checksum() {
        long sum = 0;
        for (int i = 0; i + 3 < size; i += 4) {
            long word = ((long)(mem[i]   & 0xFF) << 24)
                      | ((long)(mem[i+1] & 0xFF) << 16)
                      | ((long)(mem[i+2] & 0xFF) << 8)
                      |  (long)(mem[i+3] & 0xFF);
            sum = (sum + word) & 0xFFFFFFFFL;
        }
        return sum;
    }

    /**
     * Verifica se l'immagine ROM è valida confrontando il checksum.
     * Una ROM Kickstart Amiga valida ha checksum = 0xFFFFFFFF.
     *
     * @return true se il checksum è 0xFFFFFFFF
     */
    public boolean isChecksumValid() {
        return checksum() == 0xFFFFFFFFL;
    }

    // =========================================================================
    // Accessori
    // =========================================================================

    /** Restituisce la dimensione della ROM in byte. */
    public int size()            { return size; }


    /** Restituisce true se l'immagine ROM è stata caricata. */
    public boolean isLoaded()    { return loaded; }

    // =========================================================================
    // Internals
    // =========================================================================

    /**
     * Calcola l'indice effettivo in {@code mem} per l'indirizzo dato.
     * Implementa il wrapping hardware della ROM.
     *
     * @param address Indirizzo fisico
     * @return Indice in {@code mem[0..size-1]}
     */
    private int idx(int address) {
        int i = address % size;
        return (i < 0) ? i + size : i;
    }

}
