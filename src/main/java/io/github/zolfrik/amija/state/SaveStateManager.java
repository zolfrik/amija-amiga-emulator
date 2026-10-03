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

package io.github.zolfrik.amija.state;

import io.github.zolfrik.amija.common.AppHome;
import io.github.zolfrik.amija.config.AmigaConfig;
import io.github.zolfrik.amija.cpu.Motorola68000;
import io.github.zolfrik.amija.custom.AbstractAgnus;
import io.github.zolfrik.amija.custom.FastRam;
import io.github.zolfrik.amija.i18n.Messages;
import io.github.zolfrik.amija.input.GamePort;
import io.github.zolfrik.amija.memory.RAM;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Saving and restoring the whole state of the A500 machine to a file. There are
 * {@link #SLOT_COUNT} independent slots, one per folder ({@code savestate/Slot1/} ...
 * {@code savestate/Slot5/}); which one is active is said by the configuration
 * ({@code savestate.slot}), here only slot numbers are received. A custom compressed
 * (gzip) binary format, <b>not</b> Java serialisation: the state is gathered by
 * {@link ReflectiveState} on the machine's real components.
 *
 * <p>Header: magic {@code A500SAVE}, format version, the instant of the save, a
 * <b>fingerprint</b> of the configuration (chipset, RAM sizes, video mode, Kickstart, mounted
 * disks) and a full copy of {@code amiga.properties}. On load a different fingerprint is
 * refused with {@link IncompatibleStateException}; the copy of the configuration is there
 * so the caller can then <i>rebuild</i> the right machine ({@link #readInfo}) instead of just
 * saying no.
 *
 * <p>The snapshot must be taken/applied with the <b>machine paused</b> (as when the F12 popup
 * is open). Both on save and on load the machine is first brought to a <b>clean boundary</b>
 * (start of frame + CPU instruction boundary), so the 68000's bus queue and Denise's
 * per-scanline recorders (transient graphs that are not serialised) are empty and nothing is
 * lost.
 */
public final class SaveStateManager {

    private static final byte[] MAGIC = "A500SAVE".getBytes(StandardCharsets.US_ASCII);
    /**
     * Format version. 2: adds to the header the instant of the save and the copy of
     * {@code amiga.properties}. 3: adds the AutoConfig boards (FastRam, HdController) to the
     * roots, so the dynamic memory map is rebuilt on load. Slots of an earlier version no
     * longer load: they are a single snapshot, redone by pressing "Save State" again.
     */
    private static final int VERSION = 3;

    /** How many state slots exist. They are numbered from 1 to {@code SLOT_COUNT}. */
    public static final int SLOT_COUNT = 5;

    /** Folder containing the slot folders, relative to the home. */
    public static final String STATE_DIR = "savestate";

    /** Name of the state file, inside its slot's folder. */
    private static final String STATE_NAME = "state.a5s";

    /**
     * Screenshot accompanying the state, inside its slot's folder: a fixed name, so every
     * save overwrites that slot's previous image.
     */
    private static final String SHOT_NAME = "state.png";

    /**
     * The single slot from before there were five. If it exists it is moved to Slot 1 by
     * {@link #migrateLegacySlot()}: it was the user's only frozen game and there is no reason
     * to lose it.
     */
    private static final String LEGACY_FILE = "savestate/state1.a5s";
    private static final String LEGACY_SHOT = "savestate/state1.png";

    private SaveStateManager() {}

    /**
     * The slot number brought back into the valid range. An out-of-range value can only
     * come from a hand-written {@code amiga.properties}: falling back on an existing slot is
     * better than throwing an exception at the user.
     */
    public static int clampSlot(int slot) {
        return Math.min(SLOT_COUNT, Math.max(1, slot));
    }

    /** The slot's folder, resolved in the application home ({@link AppHome}). */
    public static File slotDir(int slot) {
        return AppHome.file(STATE_DIR + "/Slot" + clampSlot(slot));
    }

    /** The slot's state file, resolved in the home ({@link AppHome}). */
    public static File slotFile(int slot) {
        return new File(slotDir(slot), STATE_NAME);
    }

    /** The slot's screenshot, resolved in the home ({@link AppHome}). */
    public static File slotScreenshot(int slot) {
        return new File(slotDir(slot), SHOT_NAME);
    }

    /** {@code true} if that slot holds a state to load. */
    public static boolean slotPresent(int slot) {
        return slotFile(slot).isFile();
    }

    /**
     * Empties a slot: state and screenshot. Returns {@code true} if there was a state and now
     * there is none. The folder stays: it is where the next save will write.
     */
    public static boolean deleteSlot(int slot) throws IOException {
        boolean had = slotPresent(slot);
        Files.deleteIfExists(slotFile(slot).toPath());
        Files.deleteIfExists(slotScreenshot(slot).toPath());
        return had;
    }

    /**
     * Moves to Slot 1 the state written when there was a single slot
     * ({@code savestate/state1.a5s}). Does nothing if that file is not there or if Slot 1 is
     * already taken: then the user has already saved in the new scheme and the old copy is
     * the one too many, not the other way round. To be called once at start-up; an error here
     * prevents nothing and is only reported on the console.
     */
    public static void migrateLegacySlot() {
        File legacy = AppHome.file(LEGACY_FILE);
        if (!legacy.isFile() || slotPresent(1)) return;
        try {
            Files.createDirectories(slotDir(1).toPath());
            Files.move(legacy.toPath(), slotFile(1).toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            File shot = AppHome.file(LEGACY_SHOT);
            if (shot.isFile()) {
                Files.move(shot.toPath(), slotScreenshot(1).toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            System.out.println("[STATE] stato preesistente spostato nello Slot 1");
        } catch (IOException e) {
            System.err.println("[STATE] impossibile spostare lo stato preesistente"
                    + " nello Slot 1: " + e.getMessage());
        }
    }

    /** Refusal of a slot incompatible with the current machine/version. */
    public static final class IncompatibleStateException extends IOException {
        public IncompatibleStateException(String message) { super(message); }
    }

    /**
     * The header of a slot, read without touching the machine.
     *
     * @param savedAt instant of the save (epoch milliseconds)
     * @param config  {@code amiga.properties} as it was at the save: the machine this state
     *                requires
     */
    public record SlotInfo(long savedAt, String fingerprint, Properties config) { }

    // =========================================================================
    // Salvataggio
    // =========================================================================

    public static void save(File file, Motorola68000 cpu, AbstractAgnus agnus,
            GamePort port1, GamePort port2, AmigaConfig cfg) throws IOException {
        advanceToBoundary(agnus, cpu);
        List<Object> roots = buildRoots(cpu, agnus, port1, port2);
        byte[] configBlob = encodeConfig(cfg.snapshot());

        // The temporary file MUST be next to the destination: elsewhere it would land on
        // another file system and the move would stop being atomic.
        Path target = file.toPath().toAbsolutePath();
        Path dir = target.getParent() != null ? target.getParent() : Path.of(".");
        Files.createDirectories(dir);
        // Written to a temporary file next to the destination and moved at the end. Writing
        // straight onto the slot, an interruption halfway (crash, full disk, the JVM
        // exiting) would destroy the game that was there without saving a new one, losing
        // the whole value of the feature. With the move either the new slot or still the old
        // one is seen.
        Path tmp = Files.createTempFile(dir, file.getName(), ".tmp");
        try {
            try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(tmp))))) {
                out.write(MAGIC);
                out.writeInt(VERSION);
                out.writeLong(System.currentTimeMillis());
                out.writeUTF(fingerprint(cfg));
                out.writeInt(configBlob.length);
                out.write(configBlob);
                out.writeInt(roots.size());
                for (Object r : roots) {
                    out.writeUTF(r.getClass().getName());
                    ReflectiveState.write(out, r);
                }
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    // =========================================================================
    // Loading
    // =========================================================================

    public static void load(File file, Motorola68000 cpu, AbstractAgnus agnus,
            GamePort port1, GamePort port2, AmigaConfig cfg) throws IOException {
        // Bring the CURRENT machine to a clean boundary: so the 68000's bus queue (a
        // transient object not in the slot) is empty before the registers are overwritten,
        // and no pending bus operation corrupts the resume.
        advanceToBoundary(agnus, cpu);

        try (DataInputStream in = openSlot(file)) {
            SlotInfo info = readHeader(in);
            if (!info.fingerprint().equals(fingerprint(cfg))) {
                throw new IncompatibleStateException(Messages.t("savestate.err.machine"));
            }
            List<Object> roots = buildRoots(cpu, agnus, port1, port2);
            int n = in.readInt();
            if (n != roots.size()) {
                throw new IncompatibleStateException(Messages.t("savestate.err.structure"));
            }
            for (int i = 0; i < n; i++) {
                String cls = in.readUTF();
                Object r = roots.get(i);
                if (!cls.equals(r.getClass().getName())) {
                    throw new IncompatibleStateException(
                            Messages.t("savestate.err.component", i, cls));
                }
                ReflectiveState.read(in, r);
            }
            // Gary's Regions are not in the slot (they are objects): they are derived again
            // from the boards' state just restored. See buildRoots().
            agnus.rebuildAutoconfigMapping();
        }
    }

    /**
     * Reads only the slot's header: when it was saved and what the machine was like.
     * Touches nothing: used by the F12 popup to describe the slot and by whoever must
     * rebuild the machine before reloading it.
     */
    public static SlotInfo readInfo(File file) throws IOException {
        try (DataInputStream in = openSlot(file)) {
            return readHeader(in);
        }
    }

    private static DataInputStream openSlot(File file) throws IOException {
        return new DataInputStream(new GZIPInputStream(
                new BufferedInputStream(new FileInputStream(file))));
    }

    /**
     * Cap on the snapshot of {@code amiga.properties} inside the slot. The real file is under
     * 20 KB; 4 MB is plenty for any future growth and far from the point where allocating
     * blindly does damage.
     */
    private static final int MAX_CONFIG_BYTES = 4 << 20;

    /** Consumes magic, version and header, leaving the stream on the components. */
    private static SlotInfo readHeader(DataInputStream in) throws IOException {
        byte[] magic = new byte[MAGIC.length];
        in.readFully(magic);
        if (!Arrays.equals(magic, MAGIC)) {
            throw new IncompatibleStateException(Messages.t("savestate.err.badfile"));
        }
        int ver = in.readInt();
        if (ver != VERSION) {
            throw new IncompatibleStateException(
                    Messages.t("savestate.err.version", ver, VERSION));
        }
        long savedAt = in.readLong();
        String fp = in.readUTF();
        // The length is declared by the file: without a cap, a truncated or non-slot file
        // would give OutOfMemoryError (an Error, so outside the loader's catch) or
        // NegativeArraySizeException, instead of the "slot not compatible" message.
        int configBytes = in.readInt();
        if (configBytes < 0 || configBytes > MAX_CONFIG_BYTES) {
            throw new IncompatibleStateException(Messages.t("savestate.err.badfile"));
        }
        byte[] blob = new byte[configBytes];
        in.readFully(blob);
        return new SlotInfo(savedAt, fp, decodeConfig(blob));
    }

    // =========================================================================
    // Configuration inside the slot
    // =========================================================================

    private static byte[] encodeConfig(Properties props) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(4096);
        props.store(buf, "amiga.properties as of this save state");
        return buf.toByteArray();
    }

    private static Properties decodeConfig(byte[] blob) throws IOException {
        Properties p = new Properties();
        p.load(new ByteArrayInputStream(blob));
        return p;
    }

    // =========================================================================
    // Building the list of roots (identical in save and load)
    // =========================================================================

    private static List<Object> buildRoots(Motorola68000 cpu, AbstractAgnus agnus,
            GamePort port1, GamePort port2) {
        List<Object> roots = new ArrayList<>();
        add(roots, cpu);
        add(roots, agnus);
        add(roots, field(agnus, "gary"));   // address decoder: OVL flip-flop, reset/halt...
        add(roots, field(agnus, "copper"));
        add(roots, field(agnus, "blitter"));
        add(roots, field(agnus, "denise"));

        Object paula = field(agnus, "paula");
        add(roots, paula);
        // Paula's audio channels: inner classes with live DMA state (period, pointer,
        // counters) that persists across frames, so each one is a root of its own.
        Object channels = field(paula, "audioChannels");
        if (channels instanceof Object[]) {
            for (Object ch : (Object[]) channels) add(roots, ch);
        }

        addCia(roots, field(agnus, "ciaA"));
        addCia(roots, field(agnus, "ciaB"));
        add(roots, field(agnus, "keyboard"));
        add(roots, field(agnus, "batteryClock"));
        add(roots, port1);
        add(roots, port2);

        add(roots, field(agnus, "chipRAM"));
        add(roots, field(agnus, "slowRAM")); // may be null (no Slow RAM)
        Object fast = field(agnus, "fastRam");
        if (fast instanceof FastRam) {
            RAM fr = ((FastRam) fast).ram();
            add(roots, fr); // null if there is no Fast RAM
        }
        // The AutoConfig BOARDS, not just their RAM. FastRam and HdController keep in
        // primitive fields the state the Kickstart wrote during AutoConfig (where they are
        // in the chain and at which base address they ended up), and without them a state
        // saved on an already configured machine would restore the boards "to be
        // configured" with their memory window gone. Gary's map, a List the walker does not
        // see at all, is rebuilt from this state at the end of the load
        // (agnus.rebuildAutoconfigMapping()).
        for (Object dev : agnus.autoConfigDevicesForState()) add(roots, dev);

        add(roots, agnus.getDf0());
        add(roots, agnus.getDf1());
        add(roots, agnus.getDf2());
        add(roots, agnus.getDf3());
        return roots;
    }

    /** Adds a non-null root (absent optional components are skipped). */
    private static void add(List<Object> roots, Object o) {
        if (o != null) roots.add(o);
    }

    /**
     * Adds a CIA and its {@code CIAPinSet}, including the 5 pins (flag/tod/cnt/sp/irq):
     * their signal levels (IRQ line to Paula, keyboard handshake, disk inputs
     * /RDY-/TK0-/WPRO-/CHNG, OVL, LED) are persistent state in nested objects that the
     * walker, stopping at primitives, does not capture by itself.
     */
    private static void addCia(List<Object> roots, Object cia) {
        if (cia == null) return;
        add(roots, cia);
        Object pins = field(cia, "pinSet");
        if (pins == null) return;
        add(roots, pins);
        for (String p : new String[] { "flagPin", "todPin", "cntPin", "spPin", "irqPin" }) {
            add(roots, field(pins, p));
        }
    }

    // =========================================================================
    // Confine pulito
    // =========================================================================

    /**
     * Advances the machine (in colour-clock steps) to the start of the frame with the CPU
     * at an instruction boundary. Less than a frame of emulated time. Must be called with the
     * machine paused (no other thread touches Agnus).
     */
    private static void advanceToBoundary(AbstractAgnus agnus, Motorola68000 cpu) {
        final int GUARD = 8_000_000; // wide margin: a whole frame is ~70k colour clocks
        for (int i = 0; i < GUARD; i++) {
            if (agnus.getVpos() == 0 && cpu.isAtInstructionBoundary()) return;
            agnus.risingEdge();
            agnus.fallingEdge();
        }
    }

    // =========================================================================
    // Compatibility fingerprint
    // =========================================================================

    private static String fingerprint(AmigaConfig c) {
        StringBuilder b = new StringBuilder(128);
        b.append("v").append(VERSION).append(';');
        b.append("chipset=").append(c.chipset()).append(';');
        b.append("chip=").append(c.chipRamKb()).append(';');
        b.append("slow=").append(c.slowRamKb()).append(';');
        b.append("fast=").append(c.fastRamKb()).append(';');
        b.append("video=").append(c.videoMode()).append(';');
        b.append("ks=").append(baseName(c.kickstartPath())).append(';');
        for (int i = 0; i < 4; i++) {
            b.append("df").append(i).append('=').append(baseName(c.driveDisk(i))).append(';');
        }
        return b.toString();
    }

    private static String baseName(String path) {
        if (path == null || path.isBlank()) return "";
        return new File(path.trim()).getName();
    }

    // =========================================================================
    // Reflection: reading a field (private too, in superclasses too)
    // =========================================================================

    private static Object field(Object owner, String name) {
        for (Class<?> c = owner.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(owner);
            } catch (NoSuchFieldException ignored) {
                // try the superclass
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("save state: campo inaccessibile " + name, e);
            }
        }
        throw new IllegalStateException("save state: campo assente " + name
                + " in " + owner.getClass().getName());
    }
}
