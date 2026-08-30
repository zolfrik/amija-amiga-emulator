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
 * Memoria RAM generica.
 *
 * Questa classe rappresenta un blocco di memoria lineare.
 * Le logiche di indirizzamento, masking e wrap-around devono essere gestite
 * esternamente dai componenti di sistema.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * CARATTERISTICHE
 * ═══════════════════════════════════════════════════════════════════════════
 *
 *   - Big-endian
 *   - Accessi word e long NON verificano l'allineamento.
 *   - Bound check rigoroso su ogni accesso.
 *
 * @author alessio.carotenuto
 */
public final class RAM {

    // =========================================================================
    // Stato interno
    // =========================================================================

    /** Array di byte che costituisce la memoria fisica. */
    private final byte[] mem;

    /** Dimensione in byte. */
    private final int size;

    /**
     * Etichetta descrittiva per il logging (es. "Chip", "Slow").
     * Non influisce sul comportamento.
     */
    private final String label;


    // =========================================================================
    // Costruttori
    // =========================================================================

    /**
     * Crea una RAM di dimensione {@code sizeBytes} con etichetta di default.
     *
     * @param sizeBytes Dimensione in byte (deve essere > 0)
     */
    public RAM(int sizeBytes) {
        this(sizeBytes, "RAM");
    }

    /**
     * Crea una RAM di dimensione {@code sizeBytes} con etichetta personalizzata.
     *
     * @param sizeBytes Dimensione in byte (deve essere > 0)
     * @param label     Etichetta per il logging (es. "Chip", "Slow")
     */
    public RAM(int sizeBytes, String label) {
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("RAM size must be > 0, got: " + sizeBytes);
        }
        this.mem   = new byte[sizeBytes];
        this.size  = sizeBytes;
        this.label = (label != null && !label.isBlank()) ? label : "RAM";
    }

    // =========================================================================
    // Accesso in lettura
    // =========================================================================

    /**
     * Legge 1 byte (valore unsigned 0–255).
     *
     * @param address Indirizzo fisico (24 bit effettivi)
     * @return Valore byte unsigned
     */
    public int readByte(int address) {
        if (address < 0 || address >= size) throwOob(address);
        return mem[address] & 0xFF;
    }

    /**
     * Legge 1 word a 16 bit in formato big-endian (MC68000).
     *
     * L'indirizzo non viene verificato per l'allineamento: questa responsabilità
     * appartiene al 68000 e a Gary. Accessi non allineati producono i byte
     * nell'ordine naturale big-endian, che è il comportamento corretto
     * per le operazioni di byte su bus a 16 bit.
     *
     * @param address Indirizzo fisico del byte alto (A0=0 convenzione 68000)
     * @return Valore word a 16 bit unsigned
     */
    public int readWord(int address) {
        // STEP 5 (PERFORMANCE_REPORT_FINAL): bounds check singolo equivalente ai
        // due idx() originali. Valido sse 0 <= address <= size-2, quindi si lancia
        // sse address < 0 || address >= size-1 (copre sia address che address+1,
        // senza rischio di overflow e corretto anche per indirizzi dispari).
        if (address < 0 || address >= size - 1) throwOob(address);
        return ((mem[address] & 0xFF) << 8) | (mem[address + 1] & 0xFF);
    }

    /**
     * Legge 1 longword a 32 bit in formato big-endian.
     *
     * @param address Indirizzo fisico del byte più significativo
     * @return Valore long a 32 bit (può essere interpretato come unsigned
     *         tramite Integer.toUnsignedLong())
     */
    public int readLong(int address) {
        // STEP 5: bounds check singolo equivalente ai quattro idx() originali.
        // Valido sse 0 <= address <= size-4, quindi si lancia sse address < 0 ||
        // address >= size-3 (copre da address ad address+3).
        if (address < 0 || address >= size - 3) throwOob(address);
        return ((mem[address]     & 0xFF) << 24)
             | ((mem[address + 1] & 0xFF) << 16)
             | ((mem[address + 2] & 0xFF) << 8)
             |  (mem[address + 3] & 0xFF);
    }

    // =========================================================================
    // Accesso in scrittura
    // =========================================================================

    /**
     * Scrive 1 byte.
     *
     * @param address Indirizzo fisico
     * @param value   Valore (solo i bit 7-0 vengono scritti)
     */
    public void writeByte(int address, int value) {
        if (address < 0 || address >= size) throwOob(address);
        mem[address] = (byte)(value & 0xFF);
    }

    /**
     * Scrive 1 word a 16 bit in formato big-endian.
     *
     * @param address Indirizzo fisico del byte alto
     * @param value   Valore a 16 bit (solo i bit 15-0 vengono scritti)
     */
    public void writeWord(int address, int value) {
        // STEP 5: bounds check singolo (vedi readWord).
        if (address < 0 || address >= size - 1) throwOob(address);
        mem[address]     = (byte)((value >>> 8) & 0xFF);
        mem[address + 1] = (byte)( value        & 0xFF);
    }

    /**
     * Scrive 1 longword a 32 bit in formato big-endian.
     *
     * @param address Indirizzo fisico del byte più significativo
     * @param value   Valore a 32 bit
     */
    public void writeLong(int address, int value) {
        // STEP 5: bounds check singolo (vedi readLong).
        if (address < 0 || address >= size - 3) throwOob(address);
        mem[address]     = (byte)((value >>> 24) & 0xFF);
        mem[address + 1] = (byte)((value >>> 16) & 0xFF);
        mem[address + 2] = (byte)((value >>>  8) & 0xFF);
        mem[address + 3] = (byte)( value         & 0xFF);
    }

    // =========================================================================
    // Operazioni di blocco
    // =========================================================================

    /**
     * Copia un blocco di byte nella RAM (es. caricamento dati iniziali).
     *
     * <p><b>Non c'è wrap-around.</b> Il range deve stare per intero dentro la RAM: un
     * blocco che sborda è un errore, non un blocco da avvolgere. (Il javadoc prometteva
     * il contrario — «se il range attraversa il boundary la copia avviene byte per byte
     * con wrap corretto» — ma di quel codice non c'è mai stata traccia: la copia è
     * sempre stata un solo {@code System.arraycopy}. L'aliasing della Chip RAM lo fa
     * Gary sull'indirizzo, non questa classe.)
     *
     * @param address Indirizzo di destinazione nella RAM
     * @param src     Array sorgente
     * @param off     Offset nell'array sorgente
     * @param len     Numero di byte da copiare
     */
    public void load(int address, byte[] src, int off, int len) {
        if (src == null)  throw new NullPointerException("src must not be null");
        if (off < 0 || len < 0 || off + len > src.length)
            throw new IndexOutOfBoundsException(
                    "Invalid range: off=" + off + " len=" + len + " src.length=" + src.length);
        checkBlock(address, len);
        System.arraycopy(src, off, mem, address, len);
    }

    /**
     * Copia un blocco di byte dalla RAM in un array esistente ({@code System.arraycopy}).
     * Variante di {@link #dumpBlock(int, int)} senza allocazione, per i trasferimenti in
     * blocco del driver hard disk.
     *
     * @param address Indirizzo sorgente nella RAM
     * @param dst     Array di destinazione
     * @param dstOff  Offset nell'array di destinazione
     * @param len     Numero di byte da copiare
     */
    public void copyTo(int address, byte[] dst, int dstOff, int len) {
        checkBlock(address, len);
        System.arraycopy(mem, address, dst, dstOff, len);
    }

    /**
     * Legge un blocco di byte dalla RAM in un nuovo array.
     *
     * @param address Indirizzo sorgente nella RAM
     * @param len     Numero di byte da leggere
     * @return Nuovo array di byte con i dati letti
     */
    public byte[] dumpBlock(int address, int len) {
        if (len < 0) throw new IllegalArgumentException("len must be >= 0");
        checkBlock(address, len);
        byte[] out = new byte[len];
        System.arraycopy(mem, address, out, 0, len);
        return out;
    }

    /**
     * Riempie l'intera RAM con il byte specificato.
     * Usato per inizializzazione o test di memoria.
     *
     * @param value Byte di riempimento (solo i bit 7-0)
     */
    public void fill(int value) {
        Arrays.fill(mem, (byte)(value & 0xFF));
    }

    /**
     * Azzera l'intera RAM (equivalente a {@code fill(0)}).
     * Chiamato durante il reset del sistema.
     */
    public void clear() {
        Arrays.fill(mem, (byte)0);
    }

    // =========================================================================
    // Debug
    // =========================================================================

    /**
     * Restituisce una rappresentazione hex dump di una finestra di memoria.
     *
     * Formato: {@code XXXXXXXX: YY YY YY ... \n}
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

    // =========================================================================
    // Accessori
    // =========================================================================

    /** Restituisce la dimensione della RAM in byte. */
    public int size()          { return size; }

    /** Restituisce l'etichetta descrittiva di questa istanza. */
    public String getLabel()   { return label; }

    // =========================================================================
    // Internals
    // =========================================================================

    /**
     * Calcola l'indice effettivo nell'array {@code mem} per l'indirizzo dato.
     *
     * @param address Offset relativo (0..size-1)
     * @return Indice valido in {@code mem[0..size-1]}
     * @throws IndexOutOfBoundsException se l'indirizzo è fuori range
     */
    /**
     * Verifica che l'intero blocco {@code [address, address+len)} stia dentro la RAM.
     *
     * <p>Prima solo l'indirizzo iniziale passava da {@link #idx}: la coda del range la
     * controllava di fatto {@code System.arraycopy}, che sì la becca — ma con un
     * messaggio che non dice né quale RAM né quale indirizzo, al contrario di tutto il
     * resto della classe.
     */
    private void checkBlock(int address, int len) {
        if (address < 0 || len < 0 || address > size - len) throwOob(address);
    }

    private int idx(int address) {
        if (address < 0 || address >= size) {
            throwOob(address);
        }
        return address;
    }

    /**
     * STEP 5 (PERFORMANCE_REPORT_FINAL): path d'errore isolato (cold).
     *
     * Spostando la costruzione del messaggio (String.format) fuori dai metodi di
     * accesso (readByte/Word/Long, writeByte/Word/Long), il bytecode del path
     * veloce resta compatto e inlinabile dal JIT; questo metodo viene compilato
     * come "cold" e non penalizza l'accesso valido.
     */
    private void throwOob(int address) {
        throw new IndexOutOfBoundsException(
                String.format("[RAM:%s] Address $%08X out of bounds (size=$%08X)",
                        label, address, size));
    }
}
