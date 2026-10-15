/*
 * AmiJa - a cycle-exact Amiga emulator in Java.
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
 * Gary (gate array): Amiga address decoding and bus arbitration.
 *
 * Gary is the gate array that decodes addresses and generates the chip selects for every
 * device on the Amiga bus. It is not publicly documented: its behaviour has been
 * reconstructed by reverse engineering and from third-party documentation.
 *
 * AMIGA 500 MEMORY MAP (24-bit address bus, 16 MB)
 *
 *   $000000-$07FFFF   Chip RAM (512 KB, expandable to 1 MB with ECS/AGA)
 *                     With OVL=1 (overlay on): reads redirected to the ROM
 *   $080000-$0FFFFF   Chip RAM expansion / low slow RAM (depends on the hardware)
 *   $100000-$1FFFFF   ECS Chip RAM expansion (only with the "Fat" Agnus)
 *   $200000-$9FFFFF   Fast RAM (autoconfig, not present on a stock A500)
 *   $A00000-$BFFFFF   CIA decoding (mirrored over the whole range)
 *   $C00000-$D7FFFF   Slow RAM / "Ranger" RAM (A500 trapdoor); above the installed
 *                     RAM the custom registers answer (mirrored every 64 KB)
 *   $D80000-$DDFFFF   Unmapped on the A500 (open bus), except the clock at $DC0000
 *   $DE0000-$DFFFFF   Custom registers (Agnus/Paula/Denise), $DFF000-$DFF1FF, but the
 *                     incomplete decoding answers on both pages
 *   $E00000-$E7FFFF   Kickstart ROM mirror (A500)
 *   $E80000-$EFFFFF   Autoconfig / Zorro II I/O
 *   $F00000-$F7FFFF   Not decoded (open bus); ROM mirror on some models
 *   $F80000-$FBFFFF   Kickstart ROM (256 KB: mirror of the upper half on the A500)
 *   $FC0000-$FFFFFF   Kickstart ROM (256 KB)
 *   $F80000-$FFFFFF   Kickstart ROM (512 KB: Kickstart 2.x and later)
 *
 * CIA DECODING
 *
 * Gary selects the CIAs when the upper address bits are in the range $A0-$BF. Each chip
 * select is one address line (see {@link #routeCia}):
 *
 *   /CIAB_CS active when A13 = 0  ($xxDxxx)
 *   /CIAA_CS active when A12 = 0  ($xxExxx)
 *
 * The CIAs are mapped on byte addresses:
 *   CIA-A: ODD addresses ($BFE001, $BFE101, ..., $BFEF01)
 *          bits A11..A8 select the internal register (0-15)
 *   CIA-B: EVEN addresses ($BFD000, $BFD100, ..., $BFDF00)
 *          bits A11..A8 select the internal register (0-15)
 *
 * The 68000 always accesses a word; UDS/LDS tell which byte is valid. CIA-A answers on
 * LDS (low byte), CIA-B on UDS. A word access to an address that selects both reads the
 * two CIAs at once.
 *
 * ROM OVERLAY (OVL)
 *
 * At reset Gary sets its internal OVL flip-flop. While OVL is on:
 *   - reads in $000000-$07FFFF are redirected to the Kickstart ROM
 *   - writes still go to the Chip RAM (the 68000 cannot write the ROM)
 *
 * The OVL flip-flop clears by itself at the FIRST write on the bus (any address, any
 * target): Gary sees /AS active with R/W low and clears the flip-flop.
 *
 * CIA-A (bit 0 of PRA, pin PA0) can drive /OVL from software: PA0=1 -> overlay on,
 * PA0=0 -> overlay off. When CIA-A writes this bit the value is applied straight to
 * Gary's internal flip-flop (setRomOverlay() overwrites its state).
 *
 * Address mirroring during the overlay:
 *   256 KB ROM ($FC0000-$FFFFFF, size $40000):
 *     address_in_rom = (access_address) & (romSize - 1)
 *     read_from      = $FC0000 + address_in_rom
 *   512 KB ROM ($F80000-$FFFFFF, size $80000):
 *     address_in_rom = (access_address) & (romSize - 1)
 *     read_from      = $F80000 + address_in_rom
 *
 * KEYBOARD RESET
 *
 * CTRL+AMIGA+AMIGA makes the keyboard MPU pull /KBRST low (see
 * {@link #setKeyboardResetLine}); Gary propagates it to /RESET and /HALT toward the 68000.
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
        HD_CONTROLLER, // Zorro II board of the hard disk controller (exprom + ports)
        GAYLE,         // A1200: Gayle's IDE/PCMCIA registers and ID register
        OVERLAY_ROM,   // read in the $000000 area with the overlay on: ROM
        OPEN_BUS,      // area not decoded: reads the current value of the data bus
        OTHER_IO,
        CIA_BOTH       // kept for compatibility; no longer issued by routeCia()
    }

    // -------------------------------------------------------------------------

    public static final class RegionRules {

        /** If true, a word/longword at an odd address raises an Address Error on the 68000. */
        public boolean generateAddressErrorOnOddWord = true;

        /**
         * If true, an access that breaks the region's rules raises a Bus Error instead of
         * being silently ignored.
         */
        public boolean generateBusErrorOnIllegal = false;

        /**
         * If true, this region accepts byte accesses only (no word/longword). Used for the
         * CIA registers.
         */
        public boolean byteAccessOnly = false;

        /**
         * Number of extra wait-state cycles before DTACK is asserted.
         * 0 = no wait state (immediate DTACK).
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
        /** Name of the chip-select signal Gary generates for this region. */
        public final String chipSelect;
        public final RegionRules rules;
        /**
         * Physical size of the component behind the region (0 if not applicable). Used for
         * the wrap-around (mirroring).
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

        /** True if the bus cycle can proceed normally. */
        public boolean ok;
        public Target target;
        /** Chip select asserted by Gary for this route. */
        public String chipSelect;
        /**
         * Upper Data Strobe: true if the high byte of the data bus (D15-D8) is valid.
         * On the 68000 UDS is low for byte accesses at even addresses (A0=0) and for
         * word/longword accesses.
         */
        public boolean uds;
        /**
         * Lower Data Strobe: true if the low byte of the data bus (D7-D0) is valid.
         * On the 68000 LDS is low for byte accesses at odd addresses (A0=1) and for
         * word/longword accesses.
         */
        public boolean lds;
        /** Extra wait-state cycles before DTACK. */
        public int dtackCycles;
        /** If true, the 68000 must run the Address Error sequence. */
        public boolean addressError;
        /** If true, the 68000 must run the Bus Error sequence. */
        public boolean busError;
        /** The region the route comes from (may be null for unmapped areas). */
        public Region region;
        /**
         * Effective address to present to the target after any transformation (e.g. ROM
         * mirroring during the overlay).
         */
        public int effectiveAddress;

        /** If true, the bus cycle is valid but the write must be discarded. Used for the
         *  ROM areas: Gary asserts CS_ROM normally and the cycle completes with DTACK, but
         *  the EPROM ignores /WE. The caller must not write, and raise no Bus Error. */
        public boolean readOnly;

        /**
         * Route is a MUTABLE, reusable object.
         * {@link Gary#route(int, AccessType, boolean, boolean, boolean, Route)} fills a
         * "scratch" instance supplied by the caller instead of allocating a new Route at
         * every bus access (~3.5M/s), which removes the GC pressure. The static factory
         * methods are still available (they allocate) for convenience callers and tests.
         *
         * Because the object is reused, every bus master that keeps its Route beyond the
         * next route() call on the same scratch must own a dedicated scratch (see
         * Agnus.cpuScratchRoute / copperScratchRoute). The two masters use distinct
         * scratches, so there is no aliasing even when they are served on the same clock
         * edge.
         */
        public Route() { }

        /** Sets every field and returns {@code this} (fluent fill). */
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

        /** Route ok but with the write suppressed (ROM write: valid cycle, data lost). */
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
         * Area not decoded: open bus. On a real Amiga the cycle completes anyway
         * (auto-DTACK or timeout) and the read returns the current value of the data bus.
         * region = null: no consumer reads Route.region for the open bus, so no dummy
         * Region is allocated.
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

        /** Route ok but with the write suppressed (ROM write: valid cycle, data lost). */
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
         * Area not decoded: open bus. On a real Amiga the cycle completes anyway
         * (auto-DTACK or timeout) and the read returns the current value of the data bus.
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
    // Gary internal state
    // =========================================================================

    private final List<Region> regions = new ArrayList<>();

    /**
     * Lookup table per 64 KB page.
     *
     * {@link #findRegionByAddress} replaces an O(N) linear scan of {@code regions} (called
     * ~3.5M/s) with an O(1) access indexed by bits A23..A16 of the address. On this map
     * every region resolved through findRegionByAddress is 64 KB aligned and disjoint, so
     * each page maps to exactly one region.
     *
     * Correctness: {@code pageLut[p]} is filled ONLY if a single region covers page p
     * ENTIRELY (and it is the first in the list to intersect it, as the linear scan would
     * find). Non-uniform pages (partial coverage or sub-page regions, e.g. the CIAs in
     * $BFxxxx) are marked {@code pageAmbiguous} and fall back to the exact linear scan, so
     * the LUT is provably equivalent to the scan. The CIAs are never resolved through
     * findRegionByAddress anyway (route() intercepts $A00000-$BFFFFF first).
     */
    private final Region[]  pageLut       = new Region[256];
    private final boolean[] pageAmbiguous = new boolean[256];

    /**
     * Cache of the first region per target, so {@link #findFirstRegionByTarget} (used on
     * the CIA/overlay/mirror paths) needs no linear scan either.
     */
    private final Region[]  firstRegionByTarget = new Region[Target.values().length];

    /**
     * Gary's internal OVL flip-flop. Starts true at reset (ROM overlay on). It clears by
     * itself at the FIRST write on the bus ONLY during the power-on window (while
     * ciaOwnsOvl = false). CIA-A can overwrite it explicitly (bit PA0, the /OVL signal).
     */
    private boolean ovlFlipFlop = true;

    /**
     * Whether CIA-A has taken active control of the /OVL signal.
     *
     * Gary contains a one-shot circuit that clears the OVL flip-flop at the first bus
     * write after power-on. That lets the 68000 fetch the reset vector from the ROM
     * ($000000-$000007) and then write to the stack (first write, OVL cleared) before the
     * Kickstart takes control of the signal through CIA-A PA0.
     *
     * Once the Kickstart writes CIA-A PRA (bit PA0 = OVL), the signal is driven by the
     * CIA-A output. From then on Gary must no longer override it with its auto-clear: CIA-A
     * and Gary are wired to the same pin, and Gary pulling it low while CIA-A drives it
     * high would be a bus conflict.
     *
     * When setRomOverlay() is called by CIA-A this flag is set and the auto-clear is
     * disabled for the rest of the session. It is cleared only by a hardware reset.
     */
    private boolean ciaOwnsOvl = false;

    /**
     * Size of the installed Kickstart ROM.
     * 256 KB ($40000): Kickstart 1.2, 1.3 (stock OCS machines)
     * 512 KB ($80000): Kickstart 2.x, 3.x
     */
    private int romSize = 256 * 1024; // Default 256 KB

    /** Base address of the ROM (depends on romSize). */
    private int romBase = 0xFC0000; // 256 KB -> $FC0000-$FFFFFF

    /**
     * True if Slow RAM (trapdoor) is present. How much is installed is given by the
     * {@code physicalSize} of its Region: it bounds the low part of the window
     * {@code $C00000-$D7FFFF}, and above that the custom registers answer (see
     * {@link #route}). That is how the Kickstart finds where the trapdoor ends.
     */
    private boolean slowRamPresent = true;

    /**
     * A1200 decoding (Gayle in place of Gary): the CIAs answer only in {@code $BF0000-$BFFFFF},
     * the Kickstart is mirrored at {@code $B00000-$B7FFFF}, Gayle's registers sit in
     * {@code $D80000-$DBFFFF} and {@code $DD0000-$DEFFFF}, and the custom chips only in
     * {@code $DF0000-$DFFFFF}. Everything else in {@code $A00000-$DEFFFF}, the Slow RAM window
     * above the installed RAM included, decodes to nothing. See {@link #setGayleDecode}.
     */
    private boolean gayleDecode = false;

    /** Gayle's two register windows (see {@link Gayle#decodes}). */
    private final Region gayleRegion = new Region(0xD80000, 0xDEFFFF, Target.GAYLE, "CS_GAYLE",
            gayleRules());

    private static RegionRules gayleRules() {
        RegionRules r = new RegionRules();
        r.generateAddressErrorOnOddWord = false;
        return r;
    }

    // -------------------------------------------------------------------------
    // Keyboard reset (/KBRST)
    // -------------------------------------------------------------------------

    /**
     * State of the /KBRST line from the keyboard (pin 3 of the keyboard connector).
     * The keyboard MPU detects CTRL+AMIGA+AMIGA and holds the line low, for at least 500 ms
     * and as long as the keys are held; the motherboard only propagates it to /RESET and
     * /HALT. Convention: true = asserted (low on the physical bus).
     */
    private boolean kbResetLine = false;

    // -------------------------------------------------------------------------
    // Output lines toward the 68000 (active low on the real bus)
    // -------------------------------------------------------------------------

    /**
     * Whether Gary is asserting /RESET toward the 68000.
     * true = line asserted (low on the physical bus).
     */
    private boolean resetAsserted = false;

    /**
     * Whether Gary is asserting /HALT toward the 68000.
     * true = line asserted (low on the physical bus).
     * /RESET and /HALT are asserted together for a full reset.
     */
    private boolean haltAsserted = false;

    // =========================================================================
    // Constructor and factory
    // =========================================================================

    public Gary() {}

    /**
     * Creates and returns a Gary configuration for a standard Amiga with Kickstart 1.3
     * (256 KB ROM, 512 KB Chip RAM, trapdoor Slow RAM).
     *
     * The CIA decoding is NOT a static region: it is done in {@link #route} through the
     * A12/A13 decoding, as on the hardware.
     */
    public static Gary defaultA500() {
        Gary g = new Gary();
        g.romSize = 256 * 1024;
        g.romBase = 0xFC0000;
        g.slowRamPresent = true;

        // Chip RAM: $000000-$1FFFFF (512 KB physical by default)
        //
        // The /CHIP chip select covers the first TWO megabytes, not one: the space the
        // largest Agnus (8375, 2 MB) can address. On smaller Agnus chips the addresses past
        // the installed RAM mirror the low bits because the high address lines are not
        // connected. physicalSize decides where the mirror starts: 512 KB -> $080000
        // mirrors $000000, 1 MB -> $100000 mirrors $000000, 2 MB -> no mirror.
        //
        // That is how the Kickstart measures the Chip RAM: it walks up in steps and
        // compares with address 0; where it finds its own pattern again, the RAM ends.
        // Stopping the decoding at $0FFFFF would cap it at 1 MB anyway, and a machine set
        // up with 2 MB of Chip would see only half of it, enough to make WHDLoad fail on
        // games with ws_BaseMemSize > $100000 (e.g. Dragon's Lair, $180000) with "you need
        // chipmem up to $180000".
        RegionRules rrChip = new RegionRules();
        g.addRegion(new Region(0x000000, 0x1FFFFF, Target.CHIP_RAM, "CS_CHIP", rrChip, 512 * 1024));

        
        // Custom registers: $DE0000-$DFFFFF, mirrored every 64 KB (the real registers are
        // $DFF000-$DFF1FF, but the decoding is incomplete).
        //
        // Page $DE is not a spare: the custom chip select covers the whole of
        // $C00000-$DFFFFF and on the A500 Gary opens ONE hole in it, $D80000-$DDFFFF, i.e.
        // SIX 64 KB pages $D8..$DD: $DE and $DF stay custom.
        //
        // Measured by vAmigaTS Memory/Rom/mirror/custD, which writes COLOR00 in $200 steps
        // from $D00180 upward and flashes where the custom chips answer: the reference (and
        // the photo of a real Amiga next to it) shows a hole 768 iterations long
        // ($D80000-$DDFFFF) and the flashing resuming at $DE0000; without this page the
        // hole was 896 iterations long and the final block started 12 lines later. See
        // docs/vamigats-import-order.md, Memory/Rom outcomes.
        //
        // physicalSize = 64 KB: the offset that reaches AbstractAgnus is the one inside the
        // page ($DFF180 -> $F180) both from here and from the fallback branch of the Slow
        // RAM window, which uses `addr & 0xFFFF`.
        RegionRules rrCustom = new RegionRules();
        rrCustom.generateAddressErrorOnOddWord = false; // Denise/Paula accept words at odd addresses
        g.addRegion(new Region(0xDE0000, 0xDFFFFF, Target.CUSTOM_REGS, "CS_CUSTOM", rrCustom,
                64 * 1024));

        RegionRules rrRtc = new RegionRules();
        rrRtc.generateAddressErrorOnOddWord = false;
        g.addRegion(new Region(0xDC0000, 0xDCFFFF, Target.RTC, "CS_RTC", rrRtc));

        // Slow RAM ("Ranger"): $C00000-$D7FFFF (1.5 MB decoded by Gary)
        //
        // Gary decodes the whole $C00000-$D7FFFF range, but the RAM present depends on the
        // trapdoor board: a standard 512 KB board answers at $C00000-$C7FFFF. Above the
        // installed RAM the custom registers answer (see route(), step 6).
        //
        // dtackCycles = 1: the trapdoor Slow RAM is DRAM synchronous to Agnus's colour clock
        // (chip bus). Unlike Fast RAM (asynchronous, immediate DTACK) it needs a bus cycle
        // synchronised to the CCK, i.e. one more wait state than the non-chip-bus areas.
        // Agnus handles the CCK sync (as for Chip RAM); the nominal delay at Gary's level
        // is 1 cycle over Fast RAM.
        RegionRules rrSlow = new RegionRules();
        rrSlow.dtackCycles = 1;
        g.addRegion(new Region(0xC00000, 0xD7FFFF, Target.SLOW_RAM, "CS_SLOW", rrSlow, 512 * 1024));

        // Kickstart ROM: $FC0000-$FFFFFF (256 KB)
        // Gary also maps $F80000-$FBFFFF as a mirror of the same ROM (incomplete decoding)
        RegionRules rrKick = new RegionRules();
        g.addRegion(new Region(0xF80000, 0xFFFFFF, Target.KICKSTART_ROM, "CS_ROM", rrKick, 256 * 1024));

        // CIA-A: $BFE000-$BFEFFF (odd addresses, low byte D7-D0)
        // CIA-B: $BFD000-$BFDFFF (even addresses, high byte D15-D8)
        // These regions let routeCia() find the target through findFirstRegionByTarget().
        // The real decoding uses bits A12/A13 over $A00000-$BFFFFF (see routeCia()); the
        // Region objects are only handles.
        RegionRules rrCia = new RegionRules();
        rrCia.byteAccessOnly = false; // word access allowed, as on the hardware

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

    /** Removes every region with the given target and rebuilds the LUT. */
    public void removeRegionsByTarget(Target t) {
        boolean changed = regions.removeIf(r -> r.target == t);
        if (changed) rebuildPageLut();
    }

    /**
     * Enables or disables the decoding of the Zorro II AutoConfig space ($E80000-$E8FFFF).
     * To be enabled while a Fast RAM board waits to be configured: the Kickstart reads the
     * board's descriptor here and assigns its base address. The answer itself (descriptor
     * or floating bus) comes from the board ({@code FastRam}); Gary only routes the
     * accesses to the AUTOCONFIG target.
     */
    public void setFastRamPresent(boolean present) {
        setAutoconfigSpacePresent(present);
    }

    /**
     * Enables or disables the decoding of the Zorro II AutoConfig space ($E80000-$E8FFFF).
     * To be enabled while at least one board (Fast RAM or hard disk controller) waits to be
     * configured: the Kickstart reads the boards' descriptors here and assigns their base
     * addresses.
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
     * Maps the 64 KB page of a hard disk controller at the base address assigned by
     * AutoConfig. The page exposes the controller's boot ROM and the driver's command ports
     * (see {@code HdController}). Several controllers can coexist (master/slave): each one
     * takes its own page.
     */
    public void mapHdController(int base) {
        RegionRules rr = new RegionRules();
        rr.generateAddressErrorOnOddWord = false;
        addRegion(new Region(base, base + 0xFFFF, Target.HD_CONTROLLER, "CS_HD", rr));
    }

    /** Removes every hard disk controller page (reset). */
    public void unmapHdControllers() {
        removeRegionsByTarget(Target.HD_CONTROLLER);
    }

    /**
     * Maps the Fast RAM at the base address assigned by AutoConfig. The region is dynamic:
     * it is created only after the Kickstart has configured the board.
     *
     * <p>With a power-of-two {@code sizeBytes} the wrap is the mask {@code size-1};
     * otherwise {@link #route} falls back to the modulo. Valid AutoConfig sizes are all
     * powers of two anyway (see {@code FastRam.VALID_SIZES}), so the modulo branch is never
     * taken; unlike {@link #setRomSize} and {@link #setChipRamSize} nothing is refused
     * here, because the size comes from the board, not from the user.
     */
    public void mapFastRam(int base, int sizeBytes) {
        removeRegionsByTarget(Target.FAST_RAM);
        if (sizeBytes <= 0) return;
        // Fast RAM: asynchronous, no wait states (immediate DTACK), no DMA.
        RegionRules rr = new RegionRules();
        addRegion(new Region(base, base + sizeBytes - 1, Target.FAST_RAM, "CS_FAST", rr, sizeBytes));
    }

    /** Removes the Fast RAM region (e.g. at reset, before reconfiguration). */
    public void unmapFastRam() {
        removeRegionsByTarget(Target.FAST_RAM);
    }

    public void setSlowRamPresent(boolean present) {
        this.slowRamPresent = present;
    }

    /**
     * Switches between the A500 decoding and the A1200 one (an AGA machine).
     *
     * <p>On the A500 the CIA chip selects are decoded over the whole of {@code $A00000-$BFFFFF}
     * and the custom chip select over {@code $C00000-$DFFFFF}, with the trapdoor RAM covering
     * its low part. On the A1200 the CIAs sit in {@code $BF0000-$BFFFFF} only, the Kickstart
     * is mirrored at {@code $B00000-$B7FFFF}, Gayle answers in {@code $D80000-$DBFFFF} and
     * {@code $DD0000-$DEFFFF}, and the custom chips in {@code $DF0000-$DFFFFF} only. Nothing
     * answers in the rest of {@code $A00000-$DEFFFF}: an access there is an open bus access
     * that never reaches the E clock or the chip bus. The Kickstart 3.1 probe of the PCMCIA
     * attribute space at {@code $A00000} is such an access. Inside the CIA page only
     * {@code $BFD000-$BFEFFF} reaches the CIAs ({@link #isGayleCiaSelect}), and there is no
     * Kickstart mirror at {@code $E00000}.
     */
    public void setGayleDecode(boolean on) {
        this.gayleDecode = on;
    }

    public boolean isGayleDecode() {
        return gayleDecode;
    }

    /**
     * Gayle selects a CIA only in the two 4 KB blocks {@code $xxD000-$xxDFFF} and
     * {@code $xxE000-$xxEFFF} of its CIA page; the rest of the page decodes to nothing.
     */
    public static boolean isGayleCiaSelect(int addr) {
        int block = addr & 0xF000;
        return block == 0xD000 || block == 0xE000;
    }

    /** Lowest address of the CIA decoding window. */
    private int ciaWindowStart() {
        return gayleDecode ? 0xBF0000 : 0xA00000;
    }

    /**
     * Bytes of Slow RAM installed on the trapdoor. Past this threshold the window
     * {@code $C00000-$D7FFFF} exposes the custom registers, which is how the Kickstart
     * measures its size.
     */
    public void setSlowRamSize(int sizeBytes) {
        int size = Math.max(0, sizeBytes);
        for (Region r : regions) {
            if (r.target == Target.SLOW_RAM) r.physicalSize = size;
        }
    }

    /**
     * Sets the size of the Kickstart ROM and updates the base address accordingly.
     *
     * <p>The only constraint is a power of two no larger than the 512 KB of the window
     * {@code $F80000-$FFFFFF}: the hardware mirroring is a bit mask on the address
     * ({@code addr & (romSize-1)}), which would not exist for a size that is not a power of
     * two. The ROM always sits at the top of the address space and is repeated downward
     * to fill the window: 256 KB at {@code $FC0000} with a mirror at {@code $F80000},
     * 512 KB at {@code $F80000}, 64 KB at {@code $FF0000} with three mirrors, and so on.
     *
     * <p>Images are brought to this form by
     * {@link io.github.zolfrik.amija.memory.KickstartImage}, which drops the headers,
     * appended tails and duplicated halves of dumps.
     */
    public void setRomSize(int size) {
        if (size <= 0 || (size & (size - 1)) != 0 || size > 512 * 1024) {
            throw new IllegalArgumentException(
                "Dimensione ROM non valida: " + size
                + " (serve una potenza di due fino a 512K)");
        }
        this.romSize = size;
        // The ROM is anchored at the end of the space: 256 KB -> $FC0000, 512 KB -> $F80000.
        this.romBase = 0x1000000 - size;
        for (Region r : regions) {
            if (r.target == Target.KICKSTART_ROM) {
                r.physicalSize = size;
            }
        }
    }

    /**
     * Sets the physical size of the Chip RAM and propagates it to its Region. Gary decodes
     * the first two megabytes ($000000-$1FFFFF) anyway: this value only sets where the
     * addresses wrap (alias) onto the first physical bytes.
     *
     * With 512 KB (stock OCS, 512K Agnus) accesses above $07FFFF mirror the first 512 KB;
     * with 1 MB (Fatter Agnus 8372A) Chip RAM is contiguous up to $0FFFFF; with 2 MB (Super
     * Agnus 8375) there is no mirror. That is how the Kickstart works out how much Chip RAM
     * there is, so it is also the ceiling WHDLoad sees when it checks the slave's
     * ws_BaseMemSize.
     *
     * Normally there is no need to call it: the AbstractAgnus constructor does, with the
     * size of the Chip bank it was given.
     *
     * Valid values: powers of two (e.g. 524288 = 512 KB, 2097152 = 2 MB).
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
     * Sets the state of Gary's internal OVL flip-flop.
     *
     * Called by CIA-A when bit PA0 (the /OVL signal) changes.
     * PA0=1 (HIGH) -> overlay on (ROM visible at $000000).
     * PA0=0 (LOW)  -> overlay off (Chip RAM visible at $000000).
     *
     * The call overwrites the flip-flop, like the direct wire between CIA-A and Gary.
     *
     * Side effect: from the first call on, Gary's one-shot auto-clear is disabled
     * (ciaOwnsOvl = true): the move from "boot mode" (Gary controls OVL) to "run mode"
     * (CIA-A drives OVL through PA0).
     */
    public void setRomOverlay(boolean enabled) {
        this.ovlFlipFlop = enabled;
        this.ciaOwnsOvl  = true;   // CIA-A has taken control of the /OVL pin
    }

    /** Returns the current state of the OVL flip-flop. */
    public boolean isRomOverlayEnabled() {
        return ovlFlipFlop;
    }

    /**
     * Returns true if CIA-A has taken control of the /OVL signal. When true, Gary's
     * one-shot auto-clear is disabled. Useful for debuggers and save states.
     */
    public boolean isCiaControllingOvl() {
        return ciaOwnsOvl;
    }

    // =========================================================================
    // System reset from the keyboard
    // =========================================================================

    /**
     * Updates the /KBRST line from the keyboard and propagates it to /RESET and /HALT
     * toward the 68000, which are asserted together for a full reset and stay low for the
     * whole keyboard pulse.
     *
     * <p>The timing (500 ms minimum, held while the keys are pressed) belongs to the
     * keyboard MPU, not to Gary: see {@code Keyboard.serviceResetLine}.
     *
     * @param asserted true = /KBRST low (reset requested)
     */
    public void setKeyboardResetLine(boolean asserted) {
        this.kbResetLine   = asserted;
        this.resetAsserted = asserted;
        this.haltAsserted  = asserted;
    }

    /** @return true if the keyboard is holding /KBRST low. */
    public boolean isKeyboardResetLineAsserted() { return kbResetLine; }

    /** @return true if Gary is asserting /RESET toward the 68000. */
    public boolean isResetAsserted() { return resetAsserted; }

    /** @return true if Gary is asserting /HALT toward the 68000. */
    public boolean isHaltAsserted()  { return haltAsserted;  }

    /** Releases the /RESET and /HALT lines (and the /KBRST input that drives them). */
    public void clearResetLines() {
        this.kbResetLine   = false;
        this.resetAsserted = false;
        this.haltAsserted  = false;
    }

    // =========================================================================
    // System reset
    // =========================================================================

    /**
     * System reset (power-on or hardware RESET).
     *
     * At reset Gary:
     *   1. Sets the OVL flip-flop (the ROM is visible at $000000)
     *   2. Clears its internal counters
     *
     * The OVL flip-flop clears by itself at the first write on the bus (see
     * {@link #route}).
     */
    public void reset() {
        resetOverlay();
        this.ciaOwnsOvl     = false;  // at reset CIA-A does not drive /OVL yet
        this.kbResetLine    = false;
        this.resetAsserted  = false;
        this.haltAsserted   = false;
    }

    // =========================================================================
    // Routing principale
    // =========================================================================

    /**
     * Resolves a bus access and determines which device must answer.
     *
     * The 68000 presents a 24-bit address, the access type (R/W) and the UDS/LDS signals.
     * Gary decodes the address and returns a Route describing the target, the effective
     * UDS/LDS, the wait states and any error.
     *
     * Decoding priority (as on the hardware):
     *   1. CIA area ($A00000-$BFFFFF), special decoding on A12/A13
     *   2. ROM overlay ($000000-$07FFFF with OVL on)
     *   3. Kickstart ROM mirror ($E00000-$E7FFFF)
     *   4. Normal decoding by registered region
     *   5. Open bus for everything else
     *
     * Gary disables the overlay at the first write on the bus (any target).
     *
     * Route.readOnly = true means the bus cycle completes normally but the data written
     * must be discarded (ROM write: the EPROM ignores /WE).
     *
     * @param rawAddress 24-bit address generated by the 68000
     * @param type       READ or WRITE
     * @param uds        Upper Data Strobe (D15-D8)
     * @param lds        Lower Data Strobe (D7-D0)
     * @return Route describing how to handle the bus cycle
     */
    /** Backward compatibility for tests or other callers still using sizeBytes */
    public Route route(int rawAddress, AccessType type, int sizeBytes) {
        final int addr = rawAddress & 0x00FFFFFF;
        if (sizeBytes == 4 && addr >= ciaWindowStart() && addr <= 0xBFFFFF) {
            // The CIAs do not accept 32-bit accesses
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
     * Zero-allocation variant: fills and returns the {@code out} object supplied by the
     * caller instead of allocating a new Route. The caller must own a dedicated scratch for
     * every route it keeps beyond the next call (see {@link Route}).
     */
    /**
     * The overlay at a /RESET of the board: the ROM shows at {@code $000000} again. On the A500
     * only a power-on reaches Gary (a RESET instruction leaves the flip-flop to CIA-A's PA0);
     * on the A1200 Gayle takes the RESET instruction too.
     */
    public void resetOverlay() {
        this.ovlFlipFlop = true;
    }

    public Route route(int rawAddress, AccessType type, boolean uds, boolean lds, boolean isCpu, Route out) {
        final int addr = rawAddress & 0x00FFFFFF;

        // -- 1. OVL auto-clear ----------------------------------------------------
        // Gary disables the overlay at the first write on the bus. Any write cycle (CIA,
        // custom, RAM...) triggers the flip-flop. Gayle clears it at the first write to
        // CIA-A instead (step 2).
        if (type == AccessType.WRITE && ovlFlipFlop && !ciaOwnsOvl && !gayleDecode) {
            ovlFlipFlop = false;
        }

        // -- 2. CIA ($A00000-$BFFFFF), absolute priority ----------------------------
        // Gary decodes the whole range for the CIAs; the A1200 only its last 64 KB, and
        // nothing answers below.
        if (addr >= 0xA00000 && addr <= 0xBFFFFF) {
            if (addr < ciaWindowStart()) {
                if (gayleDecode && addr >= 0xB00000 && addr <= 0xB7FFFF) {
                    return routeRomMirror(addr, type, uds, lds, out);
                }
                return out.fillOpenBus(addr);
            }
            if (gayleDecode) {
                if (!isGayleCiaSelect(addr)) return out.fillOpenBus(addr);
                // A12 low selects CIA-A, whatever the byte lane: its first write ends the
                // overlay, and PA0 never brings it back.
                if (type == AccessType.WRITE && (addr & 0x1000) == 0) ovlFlipFlop = false;
            }
            return routeCia(addr, type, uds, lds, out);
        }

        // -- 3. ROM overlay ($000000-$07FFFF with OVL on) ---------------------------
        // routeOverlay() sends READ to the ROM and WRITE to the Chip RAM. Only CPU
        // accesses are subject to the overlay: DMA (Copper, Blitter) always sees Chip RAM.
        if (isCpu && ovlFlipFlop && addr < 0x080000) {
            return routeOverlay(addr, type, uds, lds, out);
        }

        // -- 4. ROM mirror at $E00000 (not on the A1200) ---------------------------
        if (addr >= 0xE00000 && addr <= 0xE7FFFF && !gayleDecode) {
            return routeRomMirror(addr, type, uds, lds, out);
        }

        // -- 4b. A1200: Gayle's registers; no custom chips and no clock below $DF0000 --
        if (gayleDecode && addr >= 0xD80000 && addr <= 0xDEFFFF) {
            if (Gayle.decodes(addr)) return applyRules(gayleRegion, addr, type, uds, lds, addr, out);
            return out.fillOpenBus(addr);
        }

        // -- 5. Normal decoding by registered region ---------------------------------
        Region hit = findRegionByAddress(addr);

        // -- 6. Slow RAM window $C00000-$D7FFFF ---------------------------------------
        // Where the trapdoor does not reach, the CUSTOM REGISTERS answer: Gary decodes the
        // whole of $C00000-$DFFFFF as the custom chip select, and the slow RAM only covers
        // its low part, for as much as is installed.
        //
        // That is how the Kickstart measures the Slow RAM: it walks up in 256 KB steps and
        // at each step writes INTENA and reads INTENAR back in the mirror (e.g.
        // $CBF09A/$CBF01C). Where the custom chips answer, there is no RAM. Treating the
        // whole window as Slow RAM, the probe never saw the custom chips and Kickstart 1.2
        // concluded it had RAM up to $DBFFFF: it put the supervisor stack there and died
        // with a yellow screen at the first use.
        if (hit != null && hit.target == Target.SLOW_RAM) {
            int slowOffset = addr - hit.start;
            if (slowRamPresent && slowOffset < hit.physicalSize) {
                return applyRules(hit, addr, type, uds, lds, slowOffset, out);
            }
            // The A1200 has no custom chip select here: past the RAM nothing answers.
            if (gayleDecode) return out.fillOpenBus(addr);
            Region custom = findFirstRegionByTarget(Target.CUSTOM_REGS);
            if (custom == null) return out.fillOpenBus(addr);
            return applyRules(custom, addr, type, uds, lds, addr & 0xFFFF, out);
        }

        // -- 7. Open bus for everything else ----------------------------------------
        if (hit == null) {
            return out.fillOpenBus(addr);
        }

        // Relative address and any wrap-around (mirroring)
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

    /** A mirror of the Kickstart ROM outside its socket window. */
    private Route routeRomMirror(int addr, AccessType type, boolean uds, boolean lds, Route out) {
        Region rom = findFirstRegionByTarget(Target.KICKSTART_ROM);
        if (rom == null) return out.fillOpenBus(addr);
        int effectiveAddr = addr & (romSize - 1);
        if (type == AccessType.WRITE) {
            return out.fillOkReadOnly(rom, uds, lds, rom.rules.dtackCycles, effectiveAddr);
        }
        return out.set(true, Target.KICKSTART_ROM, rom.chipSelect,
                uds, lds, rom.rules.dtackCycles, false, false, rom, effectiveAddr, false);
    }

    // =========================================================================
    // CIA decoding
    // =========================================================================

    /**
     * Decodes an access in the $BFxxxx area and determines which CIA answers.
     *
     * On the A500 the CIAs are wired to the 68000 bus as follows:
     *   - CIA-A sits on the low byte of the data bus (D7-D0): it answers on /LDS at ODD
     *     addresses ($BFE001, $BFE101, ...). Bits A11..A8 select the internal register.
     *   - CIA-B sits on the high byte of the data bus (D15-D8): it answers on /UDS at EVEN
     *     addresses ($BFD000, $BFD100, ...). Bits A11..A8 select the internal register.
     *
     * The decoding is incomplete: any address in $BFxxxx with the right A13/A12
     * combination selects the CIA, whatever the other bits (A0 and A4..A11 are ignored or
     * used for the internal register).
     */
    private Route routeCia(int addr, AccessType type, boolean uds, boolean lds, Route out) {
        // CIA decoding: the two chip selects are active low and are ONE ADDRESS LINE EACH,
        // independent of each other:
        //
        //   /CIAA_CS = A12      CIA-A selected when A12 = 0
        //   /CIAB_CS = A13      CIA-B selected when A13 = 0
        //
        // So four combinations, not two:
        //   $BFE001: A12=0, A13=1 -> CIA-A only
        //   $BFD000: A12=1, A13=0 -> CIA-B only
        //   $BFC000: A12=0, A13=0 -> BOTH
        //   $BFF000: A12=1, A13=1 -> none
        //
        // With both selected, the bus lane decides which of the two drives the data: that
        // is what readCiaData/writeCiaData of AbstractAgnus do with `Target.CIA_BOTH`.
        //
        // Measured by vAmigaTS Memory/Ram/memtim/memtim1, which times three long reads in
        // ten regions: its three probes in the CIA space ($A00000, $A80000, $BF0000) all
        // have A12=A13=0, and must pay the synchronous E-clock cycle rather than an
        // open-bus access with immediate DTACK.
        //
        // Not handled here: with A12=A13=1 (no CIA) a 68000 access may still pay the
        // synchronous cycle, i.e. /VPA might be generated over the whole $A00000-$BFFFFF
        // window, not only where a chip answers. Here that case stays open bus with an
        // immediate DTACK. It is another property (the generation of /VPA, not the chip
        // select), no test of the bank measures it, and it needs its own measurement.

        // Address error: word at an odd address
        if (uds && lds && (addr & 1) != 0) {
            return out.fillAddressErr(null);
        }

        boolean selectA = (addr & 0x1000) == 0; // /CIAA_CS = A12
        boolean selectB = (addr & 0x2000) == 0; // /CIAB_CS = A13

        if (selectA && selectB) {
            // -- Both -- the bus lane decides who drives the data: CIA-A on the low byte
            // (LDS, odd addresses), CIA-B on the high byte (UDS, even).
            Region ciaA = findFirstRegionByTarget(Target.CIA_A);
            Region ciaB = findFirstRegionByTarget(Target.CIA_B);
            if (ciaA == null && ciaB == null) return out.fillOpenBus(addr);
            Region handle = ciaA != null ? ciaA : ciaB;
            return out.set(true, Target.CIA_BOTH, handle.chipSelect,
                    uds, lds, 0, false, false, handle, addr, false);
        }

        if (selectA) {
            // -- CIA-A -- low byte D7-D0. Gary asserts /CIAA_CS from the address decoding;
            // UDS/LDS pass through as requested by the CPU.
            Region ciaA = findFirstRegionByTarget(Target.CIA_A);
            if (ciaA == null) return out.fillOpenBus(addr);
            return out.set(true, Target.CIA_A, ciaA.chipSelect,
                    uds, lds, 0, false, false, ciaA, addr, false);
        }

        if (selectB) {
            // -- CIA-B -- high byte D15-D8. Gary asserts /CIAB_CS from the address
            // decoding; UDS/LDS pass through as requested by the CPU.
            Region ciaB = findFirstRegionByTarget(Target.CIA_B);
            if (ciaB == null) return out.fillOpenBus(addr);
            return out.set(true, Target.CIA_B, ciaB.chipSelect,
                    uds, lds, 0, false, false, ciaB, addr, false);
        }

        // A12=1, A13=1 ($BFFxxx): no chip select active, open bus (see the note at the top
        // of the method).
        return out.fillOpenBus(addr);
    }

    // =========================================================================
    // ROM Overlay
    // =========================================================================

    /**
     * Handles an access in $000000-$07FFFF while the overlay is on.
     *
     * READ: the access is redirected to the Kickstart ROM with the address mirrored. The
     *   Route's effectiveAddress is a 0-based offset into the ROM (0 = first byte); the
     *   caller reads romData[effectiveAddress] without adding romBase.
     *
     * WRITE: the access goes to the Chip RAM. The 68000 cannot write the ROM (the EPROM
     *   ignores /WE); Gary lets the write through to the Chip RAM because /CHIP is
     *   generated from the physical address ($000000-$07FFFF), not from OVL. With
     *   ciaOwnsOvl = false the caller has already auto-cleared OVL before calling this
     *   method, so this WRITE branch is reached only when CIA-A explicitly keeps OVL = 1
     *   after the boot.
     *
     * Mirroring in the ROM:
     *   effectiveAddr = addr & (romSize - 1)
     * so $000000 reads byte 0 of the ROM, and an address past romSize wraps around as on
     * the hardware.
     */
    private Route routeOverlay(int addr, AccessType type, boolean uds, boolean lds, Route out) {
        // Alignment check (word at an odd address -> address error)
        if (uds && lds && (addr & 1) != 0) {
            Region rom = findFirstRegionByTarget(Target.KICKSTART_ROM);
            return out.fillAddressErr(rom);
        }

        if (type == AccessType.WRITE) {
            // WRITE: routed to the Chip RAM (the ROM is not writable). Gary asserts CS_CHIP
            // for $000000-$07FFFF whatever the state of OVL: OVL redirects reads only.
            Region chipRam = findFirstRegionByTarget(Target.CHIP_RAM);
            if (chipRam == null) {
                return out.fillOpenBus(addr);
            }
            int effectiveAddr = addr; // direct offset into Chip RAM (base=$000000)
            if (chipRam.physicalSize > 0 && (chipRam.physicalSize & (chipRam.physicalSize - 1)) == 0) {
                effectiveAddr &= (chipRam.physicalSize - 1);
            }
            return out.fillOk(chipRam, uds, lds, chipRam.rules.dtackCycles, effectiveAddr);
        }

        // READ: redirected to the Kickstart ROM with mirroring
        Region rom = findFirstRegionByTarget(Target.KICKSTART_ROM);
        if (rom == null) {
            return out.fillOpenBus(addr);
        }

        // 0-based offset into the ROM: the caller accesses romData[effectiveAddr].
        int effectiveAddr = addr & (romSize - 1);

        return out.set(true, Target.OVERLAY_ROM, rom.chipSelect,
                uds, lds, rom.rules.dtackCycles, false, false, rom, effectiveAddr, false);
    }

    // =========================================================================
    // Access validation and Route generation
    // =========================================================================

    /**
     * Applies the region's rules to the current access and builds the Route.
     *
     * effectiveAddress contract for ROM regions (KICKSTART_ROM):
     *   effectiveAddress = (addr - region.start) & (physicalSize - 1)
     *   = 0-based offset into the ROM data. The caller reads romData[effectiveAddress].
     *   Writes to ROM regions return readOnly = true: the bus cycle completes normally
     *   (DTACK asserted, no Bus Error), but the caller must discard the data (the EPROM
     *   ignores /WE).
     *
     * @param reg            target region
     * @param addr           address to decode (24 bits)
     * @param type           READ or WRITE
     * @param uds            Upper Data Strobe
     * @param lds            Lower Data Strobe
     * @param effectiveAddr  effective address to present to the target device
     */
    private Route applyRules(Region reg, int addr, AccessType type,
                             boolean uds, boolean lds, int effectiveAddr, Route out) {
        RegionRules rules = reg.rules;

        // Address Error: word at an odd address
        if (uds && lds && (addr & 1) != 0) {
            if (rules.generateAddressErrorOnOddWord) {
                return out.fillAddressErr(reg);
            }
            // Some regions (e.g. Custom) tolerate unaligned accesses
        }

        // Region that accepts bytes only (e.g. CIA in byte-only mode)
        if (rules.byteAccessOnly && uds && lds) {
            return rules.generateBusErrorOnIllegal
                    ? out.fillBusErr(reg) : out.fillOpenBus(addr);
        }

        // ROM write: the bus cycle completes with DTACK (no error) but the data is lost.
        // readOnly = true tells the caller to discard the write.
        if (type == AccessType.WRITE && reg.target == Target.KICKSTART_ROM) {
            return out.fillOkReadOnly(reg, uds, lds, rules.dtackCycles, effectiveAddr);
        }

        return out.fillOk(reg, uds, lds, rules.dtackCycles, effectiveAddr);
    }

    // =========================================================================
    // Ricerca regioni
    // =========================================================================

    /**
     * Rebuilds {@link #pageLut}, {@link #pageAmbiguous} and {@link #firstRegionByTarget}.
     * To be called at every change of {@code regions}. Costs O(256 x N), but runs only
     * while the memory map is set up.
     */
    private void rebuildPageLut() {
        java.util.Arrays.fill(pageLut, null);
        java.util.Arrays.fill(pageAmbiguous, false);
        java.util.Arrays.fill(firstRegionByTarget, null);

        for (int p = 0; p < 256; p++) {
            int pageStart = p << 16;
            int pageEnd   = pageStart | 0xFFFF;
            // First region (in list order) that intersects the page: the one the linear
            // scan would return for at least one address of p.
            for (Region r : regions) {
                if (r.end < pageStart || r.start > pageEnd) continue; // no intersection
                if (r.start <= pageStart && r.end >= pageEnd) {
                    // Covers the whole page: every address of p maps to r (r comes first).
                    pageLut[p] = r;
                } else {
                    // Partial coverage: the linear scan may vary within the page, so this
                    // page falls back to the exact linear scan.
                    pageAmbiguous[p] = true;
                }
                break; // only the FIRST intersecting region decides the outcome
            }
        }

        // Cache of the first region per target (keeps the insertion order).
        for (Region r : regions) {
            int ti = r.target.ordinal();
            if (firstRegionByTarget[ti] == null) firstRegionByTarget[ti] = r;
        }
    }

    private Region findRegionByAddress(int address) {
        int p = (address >>> 16) & 0xFF;
        if (pageAmbiguous[p]) {
            // Rare: exact replica of the linear scan for non-uniform pages.
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

    /** Returns the current list of regions (read-only). */
    public List<Region> getRegions() {
        return java.util.Collections.unmodifiableList(regions);
    }
}

