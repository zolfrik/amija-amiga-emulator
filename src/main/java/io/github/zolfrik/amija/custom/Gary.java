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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Gary (Gate Array) — Amiga 500 address decoding e bus arbitration.
 *
 * Gary è il gate array responsabile della decodifica degli indirizzi e della
 * generazione dei chip-select per tutti i dispositivi del bus dell'Amiga 500.
 * Non è un chip documentato pubblicamente: il suo comportamento è stato
 * ricostruito per reverse engineering e da documentazione di terze parti.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * MAPPA DI MEMORIA AMIGA 500 (24-bit address bus, 16 MB)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 *   $000000 – $07FFFF   Chip RAM (512 KB, espandibile a 1 MB con ECS/AGA)
 *                       Con OVL=1 (overlay attivo): letture reindirizzate alla ROM
 *   $080000 – $0FFFFF   Chip RAM espansione / slow RAM bassa (dipende dall'hardware)
 *   $100000 – $1FFFFF   Chip RAM espansione ECS (solo con Agnus "Fat")
 *   $200000 – $9FFFFF   Fast RAM (autoconfig, non presente di default su A500)
 *   $A00000 – $BFFFFF   Decodifica CIA (mirroring su tutto il range)
 *   $C00000 – $D7FFFF   Slow RAM / "Ranger" RAM (trapdoor A500; senza HW = open bus)
 *   $D80000 – $DEFFFF   Custom registers / RTC / Open Bus (dipende dal modello)
 *   $DF0000 – $DFFFFF   Custom registers (Agnus/Paula/Denise) — $DFF000–$DFF1FF
 *                       ma decodifica incompleta risponde su tutta la pagina $DFxxxx
 *   $E00000 – $E7FFFF   Kickstart ROM Mirror (A500)
 *   $E80000 – $EFFFFF   Autoconfig / Zorro II I/O
 *   $F00000 – $F7FFFF   Non decodificato (open bus) — mirror ROM su alcuni modelli
 *   $F80000 – $FBFFFF   Kickstart ROM (256 KB — mirror della seconda metà su A500)
 *   $FC0000 – $FFFFFF   Kickstart ROM (256 KB)
 *   $F80000 – $FFFFFF   Kickstart ROM (512 KB — Kickstart 2.x e successivi)
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * DECODIFICA CIA
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Gary seleziona le CIA quando i bit superiori dell'indirizzo sono nel range $A0-$BF.
 * La selezione interna usa i bit A13 e A12:
 *
 *   /CIAB_CS attivo se: A13=0, A12=1  → $xxDxxx
 *   /CIAA_CS attivo se: A13=1, A12=0  → $xxExxx
 *
 * Le CIA dell'Amiga sono mappate su indirizzi di byte:
 *   CIA-A: indirizzi DISPARI ($BFE001, $BFE101, ..., $BFE F01)
 *          I bit A11..A8 selezionano il registro interno (0-15)
 *   CIA-B: indirizzi PARI   ($BFD000, $BFD100, ..., $BFD F00)
 *          I bit A11..A8 selezionano il registro interno (0-15)
 *
 * Nota: in realtà il 68000 accede sempre in word; UDS/LDS determinano quale
 * byte è valido. La CIA-A risponde su LDS=basso (byte basso), CIA-B su UDS.
 * Un accesso word a $BFExxx / $BFDxxx legge entrambe le CIA contemporaneamente
 * (comportamento usato da alcuni programmi per leggere due CIA in un accesso).
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * OVERLAY ROM (OVL)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Al reset Gary attiva il flip-flop OVL interno. Finché OVL è attivo:
 *   - Letture in $000000–$07FFFF sono reindirizzate alla Kickstart ROM
 *   - Scritture vanno comunque alla Chip RAM (il 68000 non può scrivere in ROM)
 *
 * Il flip-flop OVL si azzera automaticamente alla PRIMA operazione di scrittura
 * sul bus (qualsiasi indirizzo, qualsiasi target). Questo rispecchia il
 * comportamento hardware: Gary vede /AS attivo con R/W=LOW e azzera il flip-flop.
 *
 * Il CIA-A (bit 0 del registro PRA, pin PA0) può controllare il segnale /OVL
 * via software: PA0=1 → overlay attivo, PA0=0 → overlay disattivo. Quando il
 * CIA-A scrive su questo bit, il valore viene applicato direttamente al flip-flop
 * interno di Gary (setRomOverlay() sovrascrive lo stato del flip-flop).
 *
 * Mirroring dell'indirizzo durante l'overlay:
 *   ROM 256 KB ($FC0000–$FFFFFF, dimensione $40000):
 *     address_in_rom = (access_address) & (romSize - 1)
 *     read_from      = $FC0000 + address_in_rom
 *   ROM 512 KB ($F80000–$FFFFFF, dimensione $80000):
 *     address_in_rom = (access_address) & (romSize - 1)
 *     read_from      = $F80000 + address_in_rom
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * KEYBOARD RESET
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * L'Amiga 500 supporta il "long reset" da tastiera: tenere CTRL+A.Left+A.Right
 * per ~2 secondi. La tastiera tiene il segnale KDAT basso per ~500ms per
 * segnalare a Gary di eseguire il reset di sistema.
 * Gary monitora KDAT (non KCLK che è il clock seriale) e dopo la soglia
 * asserta /RESET e /HALT verso il 68000.
 *
 * @author alessio.carotenuto
 */
public final class Gary {

    // =========================================================================
    // Logging / debug
    // =========================================================================

    private static String hx(int v) {
        return String.format("0x%06X", v & 0xFFFFFF);
    }

    // =========================================================================
    // Tipi pubblici
    // =========================================================================

    public enum AccessType {
        READ, WRITE
    }

    public enum Target {
        CHIP_RAM,
        SLOW_RAM,
        FAST_RAM,
        KICKSTART_ROM,
        CUSTOM_REGS,
        CIA_A,
        CIA_B,
        RTC,
        AUTOCONFIG,
        HD_CONTROLLER, // Scheda Zorro II del controller hard disk (exprom + porte)
        OVERLAY_ROM,   // Lettura in area $000000 con overlay attivo → ROM
        OPEN_BUS,      // Area non decodificata: legge il valore corrente del bus dati
        OTHER_IO,
        CIA_BOTH       // Riservato per compatibilità; non più emesso da routeCia()
    }

    // -------------------------------------------------------------------------

    public static final class RegionRules {

        /** Se true, word/longword su indirizzo dispari genera Address Error sul 68000. */
        public boolean generateAddressErrorOnOddWord = true;

        /**
         * Se true, un accesso non valido secondo le regole della regione genera
         * un Bus Error invece di essere ignorato silenziosamente.
         */
        public boolean generateBusErrorOnIllegal = false;

        /**
         * Se true, questa regione accetta solo accessi a byte (no word/longword).
         * Usato per i registri CIA.
         */
        public boolean byteAccessOnly = false;

        /**
         * Numero di cicli aggiuntivi di wait state prima che DTACK venga asserted.
         * 0 = nessun wait state (DTACK immediato).
         */
        public int dtackCycles = 0;

        public RegionRules copy() {
            RegionRules r = new RegionRules();
            r.generateAddressErrorOnOddWord = this.generateAddressErrorOnOddWord;
            r.generateBusErrorOnIllegal     = this.generateBusErrorOnIllegal;
            r.byteAccessOnly                = this.byteAccessOnly;
            r.dtackCycles                   = this.dtackCycles;
            return r;
        }
    }

    // -------------------------------------------------------------------------

    public static final class Region {

        public final int start;
        public final int end;
        public final Target target;
        /** Nome del segnale chip-select generato da Gary per questa regione. */
        public final String chipSelect;
        public final RegionRules rules;
        /**
         * Dimensione fisica del componente associato (0 se non applicabile).
         * Usata per il calcolo del wrap-around (mirroring).
         */
        public int physicalSize;

        public Region(int start, int end, Target target, String chipSelect, RegionRules rules) {
            this(start, end, target, chipSelect, rules, 0);
        }

        public Region(int start, int end, Target target, String chipSelect, RegionRules rules, int physicalSize) {
            if (start < 0 || end < start) {
                throw new IllegalArgumentException(
                    "Intervallo non valido: start=" + hx(start) + " end=" + hx(end));
            }
            this.start        = start;
            this.end          = end;
            this.target       = Objects.requireNonNull(target);
            this.chipSelect   = Objects.requireNonNull(chipSelect);
            this.rules        = (rules == null ? new RegionRules() : rules.copy());
            this.physicalSize = physicalSize;
        }

        public boolean contains(int address) {
            return (address & 0xFFFFFF) >= start && (address & 0xFFFFFF) <= end;
        }

        @Override
        public String toString() {
            return String.format("Region[%s–%s → %s cs=%s]", hx(start), hx(end), target, chipSelect);
        }
    }

    // -------------------------------------------------------------------------

    public static final class Route {

        /** true se il ciclo di bus può procedere normalmente. */
        public boolean ok;
        public Target target;
        /** Chip-select attivato da Gary per questa route. */
        public String chipSelect;
        /**
         * Upper Data Strobe: true se il byte alto del bus dati (D15-D8) è valido.
         * Su 68000: UDS basso durante accessi al byte pari (A0=0) o word/longword.
         */
        public boolean uds;
        /**
         * Lower Data Strobe: true se il byte basso del bus dati (D7-D0) è valido.
         * Su 68000: LDS basso durante accessi al byte dispari (A0=1) o word/longword.
         */
        public boolean lds;
        /** Cicli di wait state aggiuntivi prima di DTACK. */
        public int dtackCycles;
        /** Se true: il 68000 deve eseguire la sequenza di Address Error. */
        public boolean addressError;
        /** Se true: il 68000 deve eseguire la sequenza di Bus Error. */
        public boolean busError;
        /** La regione sorgente della route (può essere null per aree non mappate). */
        public Region region;
        /**
         * Indirizzo effettivo da presentare alla risorsa target dopo eventuali
         * trasformazioni (es. mirroring ROM durante overlay).
         */
        public int effectiveAddress;

        /** Se true: il ciclo di bus è valido ma la scrittura deve essere scartata.
         *  Usato per le aree ROM: Gary asserta CS_ROM normalmente, il bus cycle
         *  si completa con DTACK, ma la ROM EPROM ignora il segnale /WE.
         *  Il chiamante deve leggere/non-scrivere senza generare Bus Error. */
        public boolean readOnly;

        /**
         * STEP 2 (PERFORMANCE_REPORT_FINAL): Route è ora un oggetto MUTABILE
         * riutilizzabile. {@link Gary#route(int, AccessType, boolean, boolean, boolean, Route)}
         * riempie un'istanza "scratch" fornita dal chiamante invece di allocare
         * una nuova Route ad ogni accesso al bus (~3.5M/s), eliminando la
         * pressione GC. I metodi factory statici restano disponibili (allocano
         * internamente) per i chiamanti di convenienza e i test.
         *
         * ATTENZIONE: poiché l'oggetto viene riutilizzato, ogni master di bus che
         * conserva la propria Route oltre la chiamata successiva di route() sullo
         * stesso scratch deve possedere uno scratch dedicato (vedi
         * Agnus.cpuScratchRoute / copperScratchRoute / blitterScratchRoute). I tre
         * master usano scratch distinti, quindi non c'è aliasing nonostante
         * vengano serviti nello stesso fronte di clock.
         */
        public Route() { }

        /** Imposta tutti i campi e restituisce {@code this} (fluent fill). */
        Route set(boolean ok, Target target, String chipSelect,
                  boolean uds, boolean lds, int dtackCycles,
                  boolean addressError, boolean busError,
                  Region region, int effectiveAddress, boolean readOnly) {
            this.ok               = ok;
            this.target           = target;
            this.chipSelect       = chipSelect;
            this.uds              = uds;
            this.lds              = lds;
            this.dtackCycles      = dtackCycles;
            this.addressError     = addressError;
            this.busError         = busError;
            this.region           = region;
            this.effectiveAddress = effectiveAddress;
            this.readOnly         = readOnly;
            return this;
        }

        Route fillOk(Region r, boolean uds, boolean lds, int dtackCycles, int effectiveAddress) {
            return set(true, r.target, r.chipSelect, uds, lds, dtackCycles, false, false, r, effectiveAddress, false);
        }

        Route fillOk(Region r, boolean uds, boolean lds, int dtackCycles) {
            return fillOk(r, uds, lds, dtackCycles, -1);
        }

        /** Route ok ma con scrittura soppressa (ROM write: ciclo valido, dato perso). */
        Route fillOkReadOnly(Region r, boolean uds, boolean lds, int dtackCycles, int effectiveAddress) {
            return set(true, r.target, r.chipSelect, uds, lds, dtackCycles, false, false, r, effectiveAddress, true);
        }

        Route fillAddressErr(Region r) {
            Target t  = (r != null) ? r.target     : Target.OPEN_BUS;
            String cs = (r != null) ? r.chipSelect : "CS_NONE";
            return set(false, t, cs, false, false, 0, true, false, r, -1, false);
        }

        Route fillBusErr(Region r) {
            Target t  = (r != null) ? r.target     : Target.OPEN_BUS;
            String cs = (r != null) ? r.chipSelect : "CS_NONE";
            return set(false, t, cs, false, false, 0, false, true, r, -1, false);
        }

        /**
         * Area non decodificata: open bus.
         * Sull'Amiga reale il ciclo si conclude comunque (auto-DTACK o timeout),
         * e la lettura restituisce il valore attuale del bus dati ($FFFF tipicamente).
         * region=null: nessun consumatore legge Route.region per l'open bus
         * (in passato veniva allocata una Region dummy, ora evitata per azzerare l'allocazione).
         */
        Route fillOpenBus(int address) {
            return set(true, Target.OPEN_BUS, "CS_NONE", true, true, 0, false, false, null, address & 0xFFFFFF, false);
        }

        public static Route ok(Region r, boolean uds, boolean lds,
                               int dtackCycles, int effectiveAddress) {
            return new Route().fillOk(r, uds, lds, dtackCycles, effectiveAddress);
        }

        public static Route ok(Region r, boolean uds, boolean lds, int dtackCycles) {
            return new Route().fillOk(r, uds, lds, dtackCycles, -1);
        }

        /** Route ok ma con scrittura soppressa (ROM write: ciclo valido, dato perso). */
        public static Route okReadOnly(Region r, boolean uds, boolean lds,
                                       int dtackCycles, int effectiveAddress) {
            return new Route().fillOkReadOnly(r, uds, lds, dtackCycles, effectiveAddress);
        }

        public static Route addressErr(Region r) {
            return new Route().fillAddressErr(r);
        }

        public static Route busErr(Region r) {
            return new Route().fillBusErr(r);
        }

        /**
         * Area non decodificata: open bus.
         * Sull'Amiga reale il ciclo si conclude comunque (auto-DTACK o timeout),
         * e la lettura restituisce il valore attuale del bus dati ($FFFF tipicamente).
         */
        public static Route openBus(int address) {
            return new Route().fillOpenBus(address);
        }

        @Override
        public String toString() {
            return String.format(
                "Route{ok=%s target=%-12s cs=%-12s uds=%s lds=%s dtack=%d errA=%s errB=%s ro=%s eff=%s}",
                ok, target, chipSelect, uds, lds, dtackCycles,
                addressError, busError, readOnly, hx(effectiveAddress));
        }
    }

    // =========================================================================
    // Stato interno Gary
    // =========================================================================

    private final List<Region> regions = new ArrayList<>();

    /**
     * STEP 2 (PERFORMANCE_REPORT_FINAL): lookup table per pagina di 64 KB.
     *
     * {@link #findRegionByAddress} sostituisce la scansione lineare O(N) su
     * {@code regions} (chiamata a ~3.5M/s) con un accesso O(1) indicizzato dai
     * bit A23..A16 dell'indirizzo. La mappa A500 ha tutte le regioni decodificate
     * via findRegionByAddress allineate a 64 KB e disgiunte, quindi ogni pagina
     * mappa esattamente a una regione.
     *
     * Correttezza: {@code pageLut[p]} è popolata SOLO se un'unica regione copre
     * INTERAMENTE la pagina p (ed è la prima della lista a intersecarla, come
     * farebbe la scansione lineare). Le pagine non uniformi (copertura parziale o
     * regioni sub-pagina, es. le CIA in $BFxxxx) sono marcate {@code pageAmbiguous}
     * e ricadono sulla scansione lineare esatta. Questo rende la LUT provabilmente
     * equivalente al comportamento originale. Nota: le CIA non vengono comunque
     * mai risolte via findRegionByAddress (route() intercetta prima $A00000–$BFFFFF).
     */
    private final Region[]  pageLut       = new Region[256];
    private final boolean[] pageAmbiguous = new boolean[256];

    /**
     * STEP 2: cache "prima regione per target" per evitare la scansione lineare
     * anche in {@link #findFirstRegionByTarget} (usata nel path CIA/overlay/mirror).
     */
    private final Region[]  firstRegionByTarget = new Region[Target.values().length];

    /**
     * Flip-flop OVL interno di Gary.
     * Inizia a true al reset (overlay ROM attivo).
     * Si azzera automaticamente alla PRIMA scrittura sul bus SOLO durante la
     * finestra di power-on (quando ciaOwnsOvl=false).
     * Può essere sovrascritto esplicitamente dal CIA-A (bit PA0 → segnale /OVL).
     */
    private boolean ovlFlipFlop = true;

    /**
     * Indica se CIA-A ha preso il controllo attivo del segnale /OVL.
     *
     * Sull'hardware reale, Gary contiene un circuito one-shot che azzera il
     * flip-flop OVL alla prima scrittura sul bus dopo il power-on. Questo
     * permette al 68000 di fetchare il reset vector dalla ROM ($000000–$000007)
     * e poi di scrivere sullo stack (prima write → OVL cleared) prima che il
     * Kickstart prenda il controllo del segnale via CIA-A PA0.
     *
     * Una volta che il Kickstart scrive CIA-A PRA (bit PA0 = OVL), il segnale è
     * guidato direttamente dal CIA-A output. Da quel momento Gary non deve più
     * sovrascriverlo con il suo auto-clear: CIA-A e Gary sono collegati allo
     * stesso pin fisico, e se CIA-A guida il pin HIGH (OVL attivo) e Gary
     * tenta di abbassarlo, si produce un conflitto di bus.
     *
     * Implementazione: quando setRomOverlay() viene chiamato dal CIA-A, questo
     * flag viene impostato a true e l'auto-clear viene disabilitato per il
     * resto della sessione. Viene azzerato solo al reset hardware.
     */
    private boolean ciaOwnsOvl = false;

    /**
     * Dimensione della ROM Kickstart installata.
     * 256 KB ($40000): Kickstart 1.2, 1.3 (A500 base)
     * 512 KB ($80000): Kickstart 2.x, 3.x
     */
    private int romSize = 256 * 1024; // Default 256 KB

    /** Base address della ROM (dipende da romSize). */
    private int romBase = 0xFC0000; // 256 KB → $FC0000–$FFFFFF

    /**
     * true se la Slow RAM (trapdoor) è presente nel sistema. Quanta ne è installata lo
     * dice {@code physicalSize} della sua Region: delimita la parte bassa della finestra
     * {@code $C00000–$D7FFFF}, e sopra quella soglia rispondono i registri custom (vedi
     * {@link #route}). È così che il Kickstart capisce dove finisce la trapdoor.
     */
    private boolean slowRamPresent = true;

    // -------------------------------------------------------------------------
    // Keyboard reset (/KBRST)
    // -------------------------------------------------------------------------

    /**
     * Stato della linea /KBRST proveniente dalla tastiera (pin 3 del connettore
     * tastiera dell'A500). È la MPU della tastiera a rilevare CTRL+AMIGA+AMIGA e a
     * tenerla bassa — per almeno 500 ms e comunque finché i tasti restano premuti;
     * la scheda madre si limita a propagarla su /RESET e /HALT.
     * Convenzione qui: true = asserita (bassa sul bus fisico).
     */
    private boolean kbResetLine = false;

    // -------------------------------------------------------------------------
    // Linee di output verso il 68000 (active-low sul bus reale)
    // -------------------------------------------------------------------------

    /**
     * Indica che Gary sta assertando /RESET verso il 68000.
     * true = linea asserted (bassa sul bus fisico).
     */
    private boolean resetAsserted = false;

    /**
     * Indica che Gary sta assertando /HALT verso il 68000.
     * true = linea asserted (bassa sul bus fisico).
     * /RESET e /HALT vengono assertati contemporaneamente per un reset completo.
     */
    private boolean haltAsserted = false;

    // =========================================================================
    // Costruttore e factory
    // =========================================================================

    public Gary() {}

    /**
     * Crea e restituisce una configurazione Gary per Amiga 500 standard
     * con Kickstart 1.3 (256 KB ROM, 512 KB Chip RAM, Slow RAM trapdoor).
     *
     * La decodifica CIA NON viene inserita come regione statica: è gestita
     * direttamente in {@link #route} tramite la decodifica A12/A13, come
     * avviene nell'hardware reale.
     */
    public static Gary defaultA500() {
        Gary g = new Gary();
        g.romSize = 256 * 1024;
        g.romBase = 0xFC0000;
        g.slowRamPresent = true;

        // Chip RAM: $000000–$07FFFF (512 KB fisici)
        // Gary decodifica l'intero primo megabyte per la Chip RAM ($000000–$0FFFFF).
        // Se è presente solo Agnus da 512KB (come nell'A500 standard), gli indirizzi
        // sopra $07FFFF mirrorano i primi 512KB a causa del wrap-around dei bit
        // di indirizzo sul bus chip (A19 ignorato).
        RegionRules rrChip = new RegionRules();
        g.addRegion(new Region(0x000000, 0x0FFFFF, Target.CHIP_RAM, "CS_CHIP", rrChip, 512 * 1024));

        
        // Custom Registers: Gary decodifica l'intera pagina $DFxxxx
        // (i registri veri sono $DFF000–$DFF1FF, ma la decodifica è incompleta)
        RegionRules rrCustom = new RegionRules();
        rrCustom.generateAddressErrorOnOddWord = false; // Denise/Paula accettano word su dispari
        g.addRegion(new Region(0xDF0000, 0xDFFFFF, Target.CUSTOM_REGS, "CS_CUSTOM", rrCustom));

        RegionRules rrRtc = new RegionRules();
        rrRtc.generateAddressErrorOnOddWord = false;
        g.addRegion(new Region(0xDC0000, 0xDCFFFF, Target.RTC, "CS_RTC", rrRtc));

        // Slow RAM ("Ranger"): $C00000–$D7FFFF (1.5 MB decodificati da Gary)
        //
        // IMPORTANTE: Gary decodifica l'intero range $C00000–$D7FFFF per la Slow RAM.
        // Tuttavia, la RAM fisica presente dipende dalla trapdoor installata.
        // Su una scheda standard da 512 KB, la RAM risponde a $C00000–$C7FFFF.
        // Gli indirizzi $C80000–$D7FFFF non hanno RAM fisica e restituiscono open bus.
        //
        // Per gestire correttamente questo aliasing/open bus, mappiamo l'intero
        // range decodificato da Gary, e usiamo physicalSize per il wrap se necessario,
        // ma la logica in Agnus deve gestire il fatto che la RAM fisica potrebbe
        // non coprire l'intera regione (in quel caso la lettura torna 0).
        //
        // dtackCycles=1: la Slow RAM sull'A500 è DRAM sincrona al color clock di
        // Agnus (chip bus). A differenza della Fast RAM (asincrona, DTACK immediato),
        // la Slow RAM richiede un ciclo di bus sincronizzato al CCK: questo si traduce
        // in un wait state aggiuntivo rispetto alle aree non-chip-bus. In pratica,
        // Agnus gestisce il timing CCK-sync (come per la Chip RAM), ma il ritardo
        // nominale a livello Gary è di 1 ciclo rispetto alla Fast RAM.
        RegionRules rrSlow = new RegionRules();
        rrSlow.dtackCycles = 1;
        g.addRegion(new Region(0xC00000, 0xD7FFFF, Target.SLOW_RAM, "CS_SLOW", rrSlow, 512 * 1024));

        // Kickstart ROM: $FC0000–$FFFFFF (256 KB)
        // Gary mappa anche $F80000–$FBFFFF come mirror della stessa ROM (decodifica incompleta)
        RegionRules rrKick = new RegionRules();
        g.addRegion(new Region(0xF80000, 0xFFFFFF, Target.KICKSTART_ROM, "CS_ROM", rrKick, 256 * 1024));

        // CIA-A: $BFE000–$BFEFFF (indirizzi dispari, byte basso D7-D0)
        // CIA-B: $BFD000–$BFDFFF (indirizzi pari, byte alto D15-D8)
        // Queste region sono necessarie affinché routeCia() possa trovare il target
        // tramite findFirstRegionByTarget(). La decodifica reale avviene via bit A12/A13
        // nel range $A00000-$BFFFFF (vedere routeCia()), ma il Region object funge da "handle".
        RegionRules rrCia = new RegionRules();
        rrCia.byteAccessOnly = false; // Permettiamo word access come da hardware

        g.addRegion(new Region(0xBFE000, 0xBFEFFF, Target.CIA_A, "CS_CIAA", rrCia));
        g.addRegion(new Region(0xBFD000, 0xBFDFFF, Target.CIA_B, "CS_CIAB", rrCia));

        return g;
    }

    // =========================================================================
    // Configurazione
    // =========================================================================

    public void addRegion(Region r) {
        regions.add(Objects.requireNonNull(r));
        rebuildPageLut();
    }

    public void clearRegions() {
        regions.clear();
        rebuildPageLut();
    }

    /** Rimuove tutte le regioni con il target specificato e ricostruisce la LUT. */
    public void removeRegionsByTarget(Target t) {
        boolean changed = regions.removeIf(r -> r.target == t);
        if (changed) rebuildPageLut();
    }

    /**
     * Abilita o disabilita la decodifica dello spazio di AutoConfig Zorro II
     * ($E80000–$E8FFFF). Va abilitato quando è presente una scheda Fast RAM in
     * attesa di configurazione: il Kickstart legge qui il descrittore della
     * scheda e ne assegna l'indirizzo base. La risposta effettiva (descrittore vs
     * bus flottante) è prodotta dalla scheda ({@code FastRam}); Gary si limita a
     * instradare gli accessi al target AUTOCONFIG.
     */
    public void setFastRamPresent(boolean present) {
        setAutoconfigSpacePresent(present);
    }

    /**
     * Abilita o disabilita la decodifica dello spazio di AutoConfig Zorro II
     * ($E80000–$E8FFFF). Va abilitato quando è presente almeno una scheda
     * (Fast RAM o controller hard disk) in attesa di configurazione: il Kickstart
     * legge qui i descrittori delle schede e ne assegna gli indirizzi base.
     */
    public void setAutoconfigSpacePresent(boolean present) {
        removeRegionsByTarget(Target.AUTOCONFIG);
        if (present) {
            RegionRules rr = new RegionRules();
            rr.generateAddressErrorOnOddWord = false; // accessi a nibble su byte
            addRegion(new Region(FastRam.AUTOCONFIG_BASE, FastRam.AUTOCONFIG_END,
                    Target.AUTOCONFIG, "CS_ACONFIG", rr));
        }
    }

    /**
     * Mappa la pagina (64 KB) di un controller hard disk all'indirizzo base
     * assegnato dall'AutoConfig. La pagina espone la boot ROM del controller e le
     * porte di comando del driver (vedi {@code HdController}). Possono coesistere
     * più controller (master/slave): ciascuno occupa la propria pagina.
     */
    public void mapHdController(int base) {
        RegionRules rr = new RegionRules();
        rr.generateAddressErrorOnOddWord = false;
        addRegion(new Region(base, base + 0xFFFF, Target.HD_CONTROLLER, "CS_HD", rr));
    }

    /** Rimuove tutte le pagine dei controller hard disk (reset). */
    public void unmapHdControllers() {
        removeRegionsByTarget(Target.HD_CONTROLLER);
    }

    /**
     * Mappa la Fast RAM all'indirizzo base assegnato dall'AutoConfig. La regione è
     * dinamica: viene creata solo dopo che il Kickstart ha configurato la scheda.
     * {@code sizeBytes} deve essere una potenza di due (wrap-around via maschera).
     */
    public void mapFastRam(int base, int sizeBytes) {
        removeRegionsByTarget(Target.FAST_RAM);
        if (sizeBytes <= 0) return;
        // Fast RAM: asincrona, nessun wait-state (DTACK immediato), niente DMA.
        RegionRules rr = new RegionRules();
        addRegion(new Region(base, base + sizeBytes - 1, Target.FAST_RAM, "CS_FAST", rr, sizeBytes));
    }

    /** Rimuove la regione Fast RAM (es. al reset, prima della riconfigurazione). */
    public void unmapFastRam() {
        removeRegionsByTarget(Target.FAST_RAM);
    }

    public void setSlowRamPresent(boolean present) {
        this.slowRamPresent = present;
    }

    /**
     * Byte di Slow RAM installati sulla trapdoor. Oltre questa soglia la finestra
     * {@code $C00000–$D7FFFF} espone i registri custom, che è il modo in cui il
     * Kickstart ne misura la dimensione.
     */
    public void setSlowRamSize(int sizeBytes) {
        int size = Math.max(0, sizeBytes);
        for (Region r : regions) {
            if (r.target == Target.SLOW_RAM) r.physicalSize = size;
        }
    }

    /**
     * Imposta la dimensione della ROM Kickstart e aggiorna automaticamente
     * il base address.
     *
     * <p>L'unico vincolo è che la dimensione sia una potenza di due non superiore ai
     * 512 KB della finestra {@code $F80000–$FFFFFF}: il mirroring hardware è una
     * maschera di bit sull'indirizzo ({@code addr & (romSize-1)}), e su una dimensione
     * non-potenza-di-due non esisterebbe. La ROM occupa sempre la coda dello spazio di
     * indirizzamento e viene replicata all'indietro fino a riempire la finestra —
     * 256 KB a {@code $FC0000} con mirror a {@code $F80000}, 512 KB a {@code $F80000},
     * 64 KB a {@code $FF0000} con tre mirror, e così via.
     *
     * <p>Le immagini vengono portate a questa forma da
     * {@link io.github.zolfrik.amija.memory.KickstartImage}, che scarta header, code
     * appese e metà duplicate dei dump.
     */
    public void setRomSize(int size) {
        if (size <= 0 || (size & (size - 1)) != 0 || size > 512 * 1024) {
            throw new IllegalArgumentException(
                "Dimensione ROM non valida: " + size
                + " (serve una potenza di due fino a 512K)");
        }
        this.romSize = size;
        // La ROM è ancorata alla fine dello spazio: 256 KB → $FC0000, 512 KB → $F80000.
        this.romBase = 0x1000000 - size;
        for (Region r : regions) {
            if (r.target == Target.KICKSTART_ROM) {
                r.physicalSize = size;
            }
        }
    }

    /**
     * Imposta la dimensione fisica della Chip RAM e la propaga alla relativa
     * Region. Gary decodifica comunque l'intero primo megabyte ($000000–$0FFFFF):
     * questo valore determina solo il punto in cui gli indirizzi "wrappano"
     * (aliasing) sui primi byte fisici.
     *
     * Con 512 KB (A500 standard, Agnus da 512K) gli accessi sopra $07FFFF
     * mirrorano i primi 512 KB; con 1 MB (Fatter Agnus 8372A) la chip RAM è
     * contigua fino a $0FFFFF, requisito di molti slave WHDLoad che pretendono
     * chip RAM fino almeno a $A0000 (640 KB).
     *
     * Valori validi: potenze di due (es. 524288 = 512 KB, 1048576 = 1 MB).
     */
    public void setChipRamSize(int size) {
        if (size <= 0 || (size & (size - 1)) != 0) {
            throw new IllegalArgumentException(
                "Dimensione Chip RAM non valida: " + size + " (deve essere una potenza di due)");
        }
        for (Region r : regions) {
            if (r.target == Target.CHIP_RAM) {
                r.physicalSize = size;
            }
        }
    }

    /**
     * Imposta lo stato del flip-flop OVL interno di Gary.
     *
     * Chiamato dal CIA-A quando il bit PA0 (segnale /OVL) cambia valore.
     * PA0=1 (HIGH) → overlay attivo (ROM visibile a $000000).
     * PA0=0 (LOW)  → overlay disattivo (Chip RAM visibile a $000000).
     *
     * Questa chiamata sovrascrive lo stato attuale del flip-flop, replicando
     * il collegamento hardware diretto tra il CIA-A e il Gary.
     *
     * Effetto collaterale: dalla prima chiamata in poi, il circuito di auto-clear
     * one-shot di Gary viene disabilitato (ciaOwnsOvl=true). Sull'hardware reale
     * questa è la transizione dal "boot mode" (Gary controlla OVL) al "run mode"
     * (CIA-A controlla OVL direttamente tramite PA0).
     */
    public void setRomOverlay(boolean enabled) {
        this.ovlFlipFlop = enabled;
        this.ciaOwnsOvl  = true;   // CIA-A ha preso il controllo del pin /OVL
    }

    /** Restituisce lo stato corrente del flip-flop OVL. */
    public boolean isRomOverlayEnabled() {
        return ovlFlipFlop;
    }

    /**
     * Restituisce true se CIA-A ha preso il controllo del segnale /OVL.
     * Quando true, l'auto-clear one-shot di Gary è disabilitato.
     * Utile per debugger e save state.
     */
    public boolean isCiaControllingOvl() {
        return ciaOwnsOvl;
    }

    // =========================================================================
    // Reset di sistema da tastiera
    // =========================================================================

    /**
     * Aggiorna la linea /KBRST proveniente dalla tastiera e la propaga su /RESET e
     * /HALT verso il 68000 — che sull'hardware sono asseriti insieme per un reset
     * completo, e restano bassi per tutta la durata dell'impulso della tastiera.
     *
     * <p>La temporizzazione (500 ms minimi, mantenimento finché i tasti sono
     * premuti) appartiene alla MPU della tastiera, non a Gary: vedi
     * {@code Keyboard.serviceResetLine}.
     *
     * @param asserted true = /KBRST bassa (reset richiesto)
     */
    public void setKeyboardResetLine(boolean asserted) {
        this.kbResetLine   = asserted;
        this.resetAsserted = asserted;
        this.haltAsserted  = asserted;
    }

    /** @return true se la tastiera sta tenendo bassa /KBRST. */
    public boolean isKeyboardResetLineAsserted() { return kbResetLine; }

    /** @return true se Gary sta assertando /RESET verso il 68000. */
    public boolean isResetAsserted() { return resetAsserted; }

    /** @return true se Gary sta assertando /HALT verso il 68000. */
    public boolean isHaltAsserted()  { return haltAsserted;  }

    /** Rilascia le linee /RESET e /HALT (e l'ingresso /KBRST che le pilota). */
    public void clearResetLines() {
        this.kbResetLine   = false;
        this.resetAsserted = false;
        this.haltAsserted  = false;
    }

    // =========================================================================
    // Reset di sistema
    // =========================================================================

    /**
     * Reset di sistema (power-on o RESET hardware).
     *
     * Al reset Gary:
     *   1. Attiva il flip-flop OVL (la ROM è visibile a $000000)
     *   2. Azzera i contatori interni
     *
     * Il flip-flop OVL si azzera automaticamente alla prima scrittura sul bus
     * (vedere logica in {@link #route}).
     */
    public void reset() {
        this.ovlFlipFlop    = true;
        this.ciaOwnsOvl     = false;  // al reset il CIA-A non guida ancora /OVL
        this.kbResetLine    = false;
        this.resetAsserted  = false;
        this.haltAsserted   = false;
    }

    // =========================================================================
    // Routing principale
    // =========================================================================

    /**
     * Risolve un accesso al bus e determina quale dispositivo deve rispondere.
     *
     * Il 68000 presenta un indirizzo a 24 bit, il tipo di accesso (R/W) e
     * i segnali UDS/LDS. Gary decodifica l'indirizzo e restituisce una Route che
     * descrive il target, i segnali UDS/LDS effettivi, i wait state e gli eventuali errori.
     *
     * Ordine di priorità della decodifica (come nell'hardware reale):
     *   1. CIA area ($A00000–$BFFFFF) — decodifica speciale con A12/A13
     *   2. ROM Overlay ($000000–$07FFFF con OVL attivo)
     *   3. Kickstart ROM Mirror ($E00000–$E7FFFF)
     *   4. Decodifica normale per regione registrata
     *   5. Open bus per tutto il resto
     *
     * Gary disabilita l'overlay alla prima operazione di scrittura sul bus (qualsiasi target).
     *
     * Route.readOnly=true indica che il bus cycle si completa normalmente
     * ma il dato scritto deve essere scartato (ROM write: EPROM ignora /WE).
     *
     * @param rawAddress Indirizzo a 24 bit generato dal 68000
     * @param type       READ o WRITE
     * @param uds        Upper Data Strobe (D15-D8)
     * @param lds        Lower Data Strobe (D7-D0)
     * @return Route che descrive come gestire il ciclo di bus
     */
    /** Backward compatibility for tests or other callers still using sizeBytes */
    public Route route(int rawAddress, AccessType type, int sizeBytes) {
        final int addr = rawAddress & 0x00FFFFFF;
        if (sizeBytes == 4 && addr >= 0xA00000 && addr <= 0xBFFFFF) {
            // Le CIA non accettano accessi a 32 bit
            return Route.busErr(findFirstRegionByTarget(Target.CIA_A));
        }

        boolean uds, lds;
        if (sizeBytes == 1) {
            uds = (addr & 1) == 0;
            lds = !uds;
        } else {
            uds = true;
            lds = true;
        }
        return route(addr, type, uds, lds);
    }

    public Route route(int rawAddress, AccessType type, boolean uds, boolean lds) {
        return route(rawAddress, type, uds, lds, true, new Route()); // Default to CPU access for compatibility
    }

    public Route route(int rawAddress, AccessType type, boolean uds, boolean lds, boolean isCpu) {
        return route(rawAddress, type, uds, lds, isCpu, new Route());
    }

    /**
     * STEP 2 (PERFORMANCE_REPORT_FINAL): variante zero-allocazione. Riempie e
     * restituisce l'oggetto {@code out} fornito dal chiamante invece di allocare
     * una nuova Route. Il chiamante deve possedere uno scratch dedicato per ogni
     * route che conserva oltre la chiamata successiva (vedi {@link Route}).
     */
    public Route route(int rawAddress, AccessType type, boolean uds, boolean lds, boolean isCpu, Route out) {
        final int addr = rawAddress & 0x00FFFFFF;

        // ── 1. Auto-clear OVL (Bug fix #3) ───────────────────────────────────
        // Gary disabilita l'overlay alla prima operazione di scrittura sul bus.
        // Qualsiasi ciclo di scrittura (CIA, Custom, RAM...) fa scattare il flip-flop.
        if (type == AccessType.WRITE && ovlFlipFlop && !ciaOwnsOvl) {
            ovlFlipFlop = false;
        }

        // ── 2. CIA ($A00000 – $BFFFFF) — priorità assoluta (Bug fix #1) ──────
        // Gary decodifica l'intero range per le CIA.
        if (addr >= 0xA00000 && addr <= 0xBFFFFF) {
            return routeCia(addr, type, uds, lds, out);
        }

        // ── 3. ROM Overlay ($000000–$07FFFF con OVL attivo) ──────────────────
        // routeOverlay() gestisce internamente READ→ROM e WRITE→Chip RAM.
        // IMPORTANTE: solo gli accessi CPU sono soggetti a overlay.
        // Il DMA (Copper, Blitter) vede sempre la Chip RAM.
        if (isCpu && ovlFlipFlop && addr < 0x080000) {
            return routeOverlay(addr, type, uds, lds, out);
        }

        // ── 4. Mirroring ROM a $E00000 (Bug fix #2) ──────────────────────────
        if (addr >= 0xE00000 && addr <= 0xE7FFFF) {
            Region rom = findFirstRegionByTarget(Target.KICKSTART_ROM);
            if (rom != null) {
                int effectiveAddr = addr & (romSize - 1);
                if (type == AccessType.WRITE) {
                    return out.fillOkReadOnly(rom, uds, lds, rom.rules.dtackCycles, effectiveAddr);
                }
                return out.set(true, Target.KICKSTART_ROM, rom.chipSelect,
                        uds, lds, rom.rules.dtackCycles, false, false, rom, effectiveAddr, false);
            }
        }

        // ── 5. Decodifica normale per regione registrata ──────────────────────
        Region hit = findRegionByAddress(addr);

        // ── 6. Finestra Slow RAM $C00000–$D7FFFF ──────────────────────────────
        // Dove la trapdoor non arriva rispondono i REGISTRI CUSTOM. Non è una
        // stranezza: Gary decodifica l'intero $C00000–$DFFFFF come chip-select dei
        // custom, e la RAM lenta si limita a coprirne la parte bassa, per quanta ne
        // è installata. Oracolo WinUAE (memory.cpp): `map_banks(&custom_bank, 0xC0,
        // 0xE0-0xC0)` e solo dopo `map_banks(&bogomem_bank, 0xC0, size)`.
        //
        // È il meccanismo con cui il Kickstart misura la Slow RAM: sale a passi di
        // 256 KB e a ogni passo prova a scrivere INTENA e rileggere INTENAR nel
        // mirror (p.es. $CBF09A/$CBF01C). Se i custom rispondono, lì la RAM non c'è.
        // Trattando l'intera finestra come Slow RAM il probe non vedeva mai i custom
        // e il Kickstart 1.2 concludeva di avere RAM fino a $DBFFFF: ci metteva lo
        // stack supervisore e moriva con lo schermo giallo al primo uso.
        if (hit != null && hit.target == Target.SLOW_RAM) {
            int slowOffset = addr - hit.start;
            if (slowRamPresent && slowOffset < hit.physicalSize) {
                return applyRules(hit, addr, type, uds, lds, slowOffset, out);
            }
            Region custom = findFirstRegionByTarget(Target.CUSTOM_REGS);
            if (custom == null) return out.fillOpenBus(addr);
            return applyRules(custom, addr, type, uds, lds, addr & 0xFFFF, out);
        }

        // ── 7. Open bus per tutto il resto ────────────────────────────────────
        if (hit == null) {
            return out.fillOpenBus(addr);
        }

        // Calcolo indirizzo relativo ed eventuale wrap-around (mirroring)
        int effectiveAddr = addr - hit.start;
        if (hit.physicalSize > 0) {
            if ((hit.physicalSize & (hit.physicalSize - 1)) == 0) {
                effectiveAddr &= (hit.physicalSize - 1);
            } else {
                effectiveAddr %= hit.physicalSize;
            }
        }

        return applyRules(hit, addr, type, uds, lds, effectiveAddr, out);
    }

    // =========================================================================
    // Decodifica CIA
    // =========================================================================

    /**
     * Decodifica un accesso nell'area $BFxxxx e determina quale CIA risponde.
     *
     * Sull'hardware A500 le CIA sono collegate al bus del 68000 nel modo seguente:
     *   - La CIA-A occupa il byte basso del bus dati (D7-D0): risponde su /LDS
     *     agli indirizzi DISPARI ($BFE001, $BFE101, ...).
     *     I bit A11..A8 dell'indirizzo selezionano il registro interno CIA (0-15).
     *   - La CIA-B occupa il byte alto del bus dati (D15-D8): risponde su /UDS
     *     agli indirizzi PARI ($BFD000, $BFD100, ...).
     *     I bit A11..A8 dell'indirizzo selezionano il registro interno CIA (0-15).
     *
     * Un accesso word a $BFExxx/$BFDxxx può leggere entrambe le CIA contemporaneamente
     * (CIA-A sul byte alto, CIA-B sul byte basso) — comportamento usato da alcuni
     * programmi per eseguire due accessi CIA in un unico ciclo di bus.
     *
     * La decodifica è "incomplete": qualsiasi indirizzo in $BFxxxx con la
     * combinazione A13/A12 corretta seleziona la CIA, indipendentemente dagli
     * altri bit (A0, A4..A11 vengono ignorati o usati per il registro interno).
     */
    private Route routeCia(int addr, AccessType type, boolean uds, boolean lds, Route out) {
        // Decodifica CIA hardware Amiga (Gary):
        // Gary seleziona le CIA basandosi sui bit A12 e A13 dell'indirizzo.
        //
        // Segnali Chip Select (active LOW):
        //   /CIAA_CS attivo se: A13=1, A12=0
        //   /CIAB_CS attivo se: A13=0, A12=1
        //
        // Indirizzi tipici ($BFxxxx):
        //   $BFE001: A13=1, A12=0 -> /CIAA_CS attivo
        //   $BFD000: A13=0, A12=1 -> /CIAB_CS attivo
        //   $BFC000: A13=0, A12=0 -> Nessuna CIA attiva (open bus)
        //   $BFF000: A13=1, A12=1 -> Nessuna CIA attiva (open bus)

        // Address error: word su indirizzo dispari
        if (uds && lds && (addr & 1) != 0) {
            return out.fillAddressErr(null);
        }

        boolean selectA = (addr & 0x1000) == 0 && (addr & 0x2000) != 0; // A12=0 AND A13=1 -> CIA-A
        boolean selectB = (addr & 0x2000) == 0 && (addr & 0x1000) != 0; // A13=0 AND A12=1 -> CIA-B

        if (selectA) {
            // ─── CIA-A ─── Byte basso D7-D0.
            // Gary attiva /CIAA_CS basandosi sulla decodifica dell'indirizzo.
            // I segnali UDS/LDS passano come richiesti dalla CPU (Bug fix #4).
            Region ciaA = findFirstRegionByTarget(Target.CIA_A);
            if (ciaA == null) return out.fillOpenBus(addr);
            return out.set(true, Target.CIA_A, ciaA.chipSelect,
                    uds, lds, 0, false, false, ciaA, addr, false);
        }

        if (selectB) {
            // ─── CIA-B ─── Byte alto D15-D8.
            // Gary attiva /CIAB_CS basandosi sulla decodifica dell'indirizzo.
            // I segnali UDS/LDS passano come richiesti dalla CPU (Bug fix #4).
            Region ciaB = findFirstRegionByTarget(Target.CIA_B);
            if (ciaB == null) return out.fillOpenBus(addr);
            return out.set(true, Target.CIA_B, ciaB.chipSelect,
                    uds, lds, 0, false, false, ciaB, addr, false);
        }

        // A12=1,A13=1 ($BFFxxx) oppure A12=0,A13=0 ($BFCxxx): nessun CS attivo → open bus
        return out.fillOpenBus(addr);
    }

    // =========================================================================
    // ROM Overlay
    // =========================================================================

    /**
     * Gestisce un accesso nell'area $000000–$07FFFF mentre l'overlay è attivo.
     *
     * READ: l'accesso viene reindirizzato alla Kickstart ROM con mirroring
     *   corretto dell'indirizzo. Il campo effectiveAddress della Route è un
     *   offset 0-based nella ROM (0 = primo byte). Il chiamante legge da
     *   romData[effectiveAddress] senza aggiungere romBase.
     *
     * WRITE: l'accesso va alla Chip RAM. Su hardware reale il 68000 non può
     *   scrivere in ROM (la ROM EPROM ignora /WE). Gary lascia passare la write
     *   alla Chip RAM perché il segnale /CHIP è generato in base all'indirizzo
     *   fisico ($000000–$07FFFF), non in base allo stato di OVL.
     *   Nota: con ciaOwnsOvl=false il chiamante ha già eseguito l'auto-clear di
     *   OVL prima di invocare questo metodo, quindi questo ramo WRITE viene
     *   raggiunto solo quando CIA-A mantiene OVL=1 esplicitamente dopo il boot.
     *
     * Mirroring dell'indirizzo nella ROM:
     *   effectiveAddr = addr & (romSize - 1)
     * Garantisce che $000000 legga dal byte 0 della ROM, e che se addr supera
     * romSize il valore si avvolga correttamente (come sull'hardware reale).
     */
    private Route routeOverlay(int addr, AccessType type, boolean uds, boolean lds, Route out) {
        // Verifica allineamento (word su dispari → address error)
        if (uds && lds && (addr & 1) != 0) {
            Region rom = findFirstRegionByTarget(Target.KICKSTART_ROM);
            return out.fillAddressErr(rom);
        }

        if (type == AccessType.WRITE) {
            // WRITE: indirizza alla Chip RAM (la ROM non è scrivibile).
            // Gary asserta CS_CHIP per l'area $000000–$07FFFF indipendentemente
            // dallo stato di OVL — OVL ridirecciona solo le letture.
            Region chipRam = findFirstRegionByTarget(Target.CHIP_RAM);
            if (chipRam == null) {
                return out.fillOpenBus(addr);
            }
            int effectiveAddr = addr; // offset diretto nella Chip RAM (base=$000000)
            if (chipRam.physicalSize > 0 && (chipRam.physicalSize & (chipRam.physicalSize - 1)) == 0) {
                effectiveAddr &= (chipRam.physicalSize - 1);
            }
            return out.fillOk(chipRam, uds, lds, chipRam.rules.dtackCycles, effectiveAddr);
        }

        // READ: reindirizza alla Kickstart ROM con mirroring
        Region rom = findFirstRegionByTarget(Target.KICKSTART_ROM);
        if (rom == null) {
            return out.fillOpenBus(addr);
        }

        // Offset 0-based nella ROM: il chiamante accede a romData[effectiveAddr].
        int effectiveAddr = addr & (romSize - 1);

        return out.set(true, Target.OVERLAY_ROM, rom.chipSelect,
                uds, lds, rom.rules.dtackCycles, false, false, rom, effectiveAddr, false);
    }

    // =========================================================================
    // Validazione accesso e generazione Route
    // =========================================================================

    /**
     * Applica le regole della regione all'accesso corrente e genera la Route.
     *
     * Contratto effectiveAddress per le regioni ROM (KICKSTART_ROM):
     *   effectiveAddress = (addr - region.start) & (physicalSize - 1)
     *   = offset 0-based nel dato ROM. Il chiamante legge romData[effectiveAddress].
     *   Le scritture a regioni ROM restituiscono readOnly=true: il bus cycle si
     *   completa normalmente (DTACK asserted, nessun Bus Error), ma il chiamante
     *   deve scartare il dato scritto (la EPROM ignora /WE sul pin fisico).
     *
     * @param reg            Regione target
     * @param addr           Indirizzo da decodificare (24 bit)
     * @param type           READ o WRITE
     * @param uds            Upper Data Strobe
     * @param lds            Lower Data Strobe
     * @param effectiveAddr  Indirizzo effettivo da presentare al dispositivo target
     */
    private Route applyRules(Region reg, int addr, AccessType type,
                             boolean uds, boolean lds, int effectiveAddr, Route out) {
        RegionRules rules = reg.rules;

        // Address Error: word su indirizzo dispari
        if (uds && lds && (addr & 1) != 0) {
            if (rules.generateAddressErrorOnOddWord) {
                return out.fillAddressErr(reg);
            }
            // Alcune regioni (es. Custom) tollerano accessi non allineati
        }

        // Regione che accetta solo byte (es. CIA in modalità byte-only)
        if (rules.byteAccessOnly && uds && lds) {
            return rules.generateBusErrorOnIllegal
                    ? out.fillBusErr(reg) : out.fillOpenBus(addr);
        }

        // ROM write: il bus cycle si completa con DTACK (nessun errore), ma il
        // dato viene perso. readOnly=true segnala al chiamante di scartare la write.
        if (type == AccessType.WRITE && reg.target == Target.KICKSTART_ROM) {
            return out.fillOkReadOnly(reg, uds, lds, rules.dtackCycles, effectiveAddr);
        }

        return out.fillOk(reg, uds, lds, rules.dtackCycles, effectiveAddr);
    }

    // =========================================================================
    // Ricerca regioni
    // =========================================================================

    /**
     * STEP 2: ricostruisce {@link #pageLut}, {@link #pageAmbiguous} e
     * {@link #firstRegionByTarget}. Da chiamare ad ogni modifica di {@code regions}.
     * Costo O(256 × N) ma eseguito solo durante il setup della mappa di memoria.
     */
    private void rebuildPageLut() {
        java.util.Arrays.fill(pageLut, null);
        java.util.Arrays.fill(pageAmbiguous, false);
        java.util.Arrays.fill(firstRegionByTarget, null);

        for (int p = 0; p < 256; p++) {
            int pageStart = p << 16;
            int pageEnd   = pageStart | 0xFFFF;
            // Prima regione (in ordine di lista) che interseca la pagina: è quella
            // che la scansione lineare restituirebbe per almeno un indirizzo di p.
            for (Region r : regions) {
                if (r.end < pageStart || r.start > pageEnd) continue; // nessuna intersezione
                if (r.start <= pageStart && r.end >= pageEnd) {
                    // Copre l'intera pagina → ogni indirizzo di p mappa a r (r è prima).
                    pageLut[p] = r;
                } else {
                    // Copertura parziale → la scansione lineare può variare entro la
                    // pagina: fallback lineare esatto per questa pagina.
                    pageAmbiguous[p] = true;
                }
                break; // solo la PRIMA regione intersecante determina l'esito
            }
        }

        // Cache prima-regione-per-target (preserva l'ordine di inserimento).
        for (Region r : regions) {
            int ti = r.target.ordinal();
            if (firstRegionByTarget[ti] == null) firstRegionByTarget[ti] = r;
        }
    }

    private Region findRegionByAddress(int address) {
        int p = (address >>> 16) & 0xFF;
        if (pageAmbiguous[p]) {
            // Raro: replica esatta della scansione lineare per le pagine non uniformi.
            for (Region r : regions) {
                if (r.contains(address)) return r;
            }
            return null;
        }
        return pageLut[p];
    }

    private Region findFirstRegionByTarget(Target t) {
        return firstRegionByTarget[t.ordinal()];
    }

    // =========================================================================
    // Utility
    // =========================================================================

    public void clearMapAndLoadDefaultA500() {
        clearRegions();
        Gary g = Gary.defaultA500();
        this.regions.addAll(g.regions);
        this.romSize = g.romSize;
        this.romBase = g.romBase;
        rebuildPageLut();
    }

    /** Restituisce la lista corrente delle regioni (sola lettura). */
    public List<Region> getRegions() {
        return java.util.Collections.unmodifiableList(regions);
    }
}

