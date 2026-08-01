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

import io.github.zolfrik.amija.memory.RAM;

/**
 * Espansione Fast RAM Zorro II con AutoConfig.
 *
 * <p>L'Amiga 500 base non ha Fast RAM: la memoria veloce è fornita da schede di
 * espansione collegate al bus Zorro II (slot trapdoor o bus laterale). A
 * differenza della Chip/Slow RAM, la Fast RAM è asincrona rispetto al chip-bus
 * (nessun wait-state, DTACK immediato) e <b>non</b> è accessibile ai chip custom
 * (Agnus/Copper/Blitter): solo la CPU la vede. Da qui il nome "Fast".
 *
 * <p>Perché serve l'AutoConfig: una scheda di espansione non ha un indirizzo
 * fisso cablato. Al boot il Kickstart (expansion.library) interroga lo spazio di
 * configurazione $E80000–$E8FFFF, legge il "descrittore" della scheda (tipo,
 * dimensione, produttore…), le assegna un indirizzo base (la prima scheda di RAM
 * Zorro II finisce tipicamente a $200000) e — grazie al flag {@code ERTF_MEMLIST}
 * — aggiunge automaticamente la memoria alla lista di sistema (AddMemList). È
 * proprio questo che permette a programmi e a WHDLoad di trovare e usare la Fast
 * RAM. Senza AutoConfig la RAM esisterebbe sul bus ma il sistema operativo non la
 * conoscerebbe.
 *
 * <p>Il protocollo di lettura della configurazione è a <b>nibble</b>: ogni
 * registro da 1 byte viene letto in due accessi (nibble alto e nibble basso) sui
 * 4 bit più alti del bus dati (D15–D12). Tutti i registri tranne {@code er_Type}
 * (offset 0) vengono restituiti <b>invertiti</b> e il sistema operativo li
 * complementa di nuovo.
 *
 * <p>Questa implementazione modella una singola scheda di RAM, ricalcando i byte
 * di descrittore e la macchina a stati di vAmiga ({@code RamExpansion}/
 * {@code ZorroBoard}) e WinUAE ({@code expansion.cpp}).
 *
 * @author alessio.carotenuto
 */
public final class FastRam implements AutoConfigDevice {

    /** Indirizzo base dello spazio di AutoConfig Zorro II (pagina di 64 KB). */
    public static final int AUTOCONFIG_BASE = 0xE80000;
    public static final int AUTOCONFIG_END  = 0xE8FFFF;

    /** Dimensioni di Fast RAM ammesse (in byte): potenze di due da 512 KB a 8 MB.
     *  Sono gli unici valori codificabili nei "size bits" del descrittore Zorro II. */
    private static final int[] VALID_SIZES = {
        512 * 1024, 1024 * 1024, 2 * 1024 * 1024, 4 * 1024 * 1024, 8 * 1024 * 1024
    };

    // Costanti AutoConfig (expansion/expansion.h).
    private static final int ERT_ZORROII  = 0xC0;
    private static final int ERTF_MEMLIST = 0x20;

    /** Stato della scheda nella sequenza di AutoConfig. */
    public enum State {
        /** In attesa di configurazione: risponde nello spazio $E8xxxx. */
        UNCONFIGURED,
        /** Indirizzo base assegnato dal Kickstart: la RAM è mappata e usabile. */
        CONFIGURED,
        /** La scheda è stata "shut up" dal software: non risponde più. */
        SHUTUP
    }

    private final int sizeBytes;
    private final RAM  ram;

    private State state;
    private int   baseAddr;

    /** Byte basso ($4A) dell'indirizzo base, latchato in attesa del commit a $48. */
    private int   pendingLoByte;

    /**
     * Crea una scheda Fast RAM della dimensione richiesta (in byte). Il valore
     * viene normalizzato alla dimensione Zorro II valida più vicina (per difetto);
     * 0 (o un valore inferiore a 512 KB) significa "nessuna Fast RAM".
     */
    public FastRam(int requestedBytes) {
        this.sizeBytes = normalizeBytes(requestedBytes);
        this.ram   = sizeBytes > 0 ? new RAM(sizeBytes, "Fast") : null;
        this.state = sizeBytes > 0 ? State.UNCONFIGURED : State.SHUTUP;
        this.baseAddr = 0;
    }

    /**
     * Normalizza una dimensione in byte alla dimensione Fast RAM Zorro II valida
     * più grande che non la supera; restituisce 0 se inferiore a 512 KB.
     */
    public static int normalizeBytes(int bytes) {
        int result = 0;
        for (int s : VALID_SIZES) {
            if (bytes >= s) result = s;
        }
        return result;
    }

    // =========================================================================
    // Stato
    // =========================================================================

    /** {@code true} se è presente della Fast RAM (size &gt; 0). */
    public boolean present()      { return ram != null; }

    /** {@code true} se la scheda ha ricevuto un indirizzo base ed è mappata. */
    public boolean configured()   { return state == State.CONFIGURED; }

    /** {@code true} se la scheda risponde ancora nello spazio di AutoConfig. */
    public boolean autoconfiguring() { return state == State.UNCONFIGURED; }

    /** Indirizzo base assegnato dal Kickstart (valido solo se {@link #configured()}). */
    public int baseAddr()         { return baseAddr; }

    @Override
    public int configuredBaseAddr() { return baseAddr; }

    /** Dimensione della Fast RAM in byte (0 se assente). */
    public int sizeBytes()        { return sizeBytes; }

    /** La RAM fisica sottostante (null se assente). */
    public RAM ram()              { return ram; }

    /** Numero di pagine da 64 KB occupate dalla scheda. */
    public int pages()            { return sizeBytes / 0x10000; }

    /**
     * Reset di sistema: la scheda torna in attesa di AutoConfig (come avviene al
     * power-on e dopo l'istruzione RESET della CPU, che resetta il bus Zorro).
     * Il contenuto della RAM è preservato.
     */
    public void reset() {
        this.state    = present() ? State.UNCONFIGURED : State.SHUTUP;
        this.baseAddr = 0;
        this.pendingLoByte = 0;
    }

    // =========================================================================
    // Descrittore AutoConfig
    // =========================================================================

    /** Bit di dimensione codificati in {@code er_Type} (cfr. vAmiga ZorroBoard). */
    private int sizeBits() {
        switch (pages()) {
            case 0x08: return 0b100; // 512 KB
            case 0x10: return 0b101; // 1 MB
            case 0x20: return 0b110; // 2 MB
            case 0x40: return 0b111; // 4 MB
            case 0x80: return 0b000; // 8 MB
            default:   return 0b000;
        }
    }

    /** Restituisce il byte {@code offset} (0..15) del descrittore di configurazione. */
    private int descriptorByte(int offset) {
        switch (offset) {
            case 0x0: return (ERT_ZORROII | ERTF_MEMLIST) | sizeBits();
            case 0x1: return 0x67;                       // product number
            case 0x2: return 0x80;                       // flags
            case 0x3: return 0x00;
            case 0x4: return (0x07B9 >> 8) & 0xFF;        // manufacturer hi
            case 0x5: return 0x07B9 & 0xFF;              // manufacturer lo
            case 0x6: return (27182 >> 24) & 0xFF;       // serial
            case 0x7: return (27182 >> 16) & 0xFF;
            case 0x8: return (27182 >>  8) & 0xFF;
            case 0x9: return 27182 & 0xFF;
            case 0xA: return 0x00;                        // initDiagVec hi
            case 0xB: return 0x00;                        // initDiagVec lo
            default:  return 0x00;
        }
    }

    /**
     * Lettura dallo spazio di AutoConfig ($E8xxxx). Restituisce un byte con il
     * nibble di configurazione nei bit 7-4 (verrà letto dalla CPU sul byte alto
     * del bus dati). Quando la scheda non è più in fase di AutoConfig restituisce
     * $FF (bus flottante: nessuna scheda risponde).
     */
    public int peekConfigByte(int addr) {
        if (state != State.UNCONFIGURED) return 0xFF;

        int offset = addr & 0xFF;
        int result = 0xFF;

        if ((offset & 1) == 0 && offset < 0x40) {
            int b = descriptorByte(offset >> 2);
            // offset&2 seleziona il nibble: alto per offset 0/4/8…, basso per 2/6/A…
            int nibble = ((offset & 2) != 0) ? (b & 0x0F) : ((b >> 4) & 0x0F);
            // er_Type (offset 0/2) è letto diretto; tutti gli altri registri invertiti.
            result = (offset < 4) ? (nibble << 4) : (~(nibble << 4) & 0xFF);
        } else if (offset == 0x40 || offset == 0x42) {
            result = 0x00; // interrupt pending register
        }
        return result & 0xFF;
    }

    /**
     * Scrittura nello spazio di AutoConfig ($E8xxxx). Il Kickstart assegna
     * l'indirizzo base scrivendo i registri $48 (A23-A20) e $4A (A19-A16); il
     * registro $4C ordina alla scheda di "shut up".
     *
     * @return {@code true} se la scrittura ha appena completato la configurazione
     *         (indirizzo base assegnato): il chiamante deve mappare la Fast RAM.
     */
    public boolean pokeConfigByte(int addr, int value) {
        if (state != State.UNCONFIGURED) return false;

        // L'indirizzo base (A23-A16) si compone di due byte: $4A porta A19-A16 nel
        // nibble alto, $48 porta A23-A20 nel nibble alto. La scrittura a $48
        // finalizza la configurazione. Formula identica a WinUAE (expansion.cpp):
        //   base = ((hi) | (lo >> 4)) << 16
        switch (addr & 0xFF) {
            case 0x4A: // ec_BaseAddress, parte bassa (A19-A16)
                pendingLoByte = value & 0xFF;
                return false;

            case 0x48: // ec_BaseAddress, parte alta (A23-A20): commit + attivazione
                baseAddr = ((value & 0xFF) | (pendingLoByte >> 4)) << 16;
                state = State.CONFIGURED;
                return true;

            case 0x4C: // ec_ShutUp
                state = State.SHUTUP;
                return false;

            default:
                return false;
        }
    }
}
