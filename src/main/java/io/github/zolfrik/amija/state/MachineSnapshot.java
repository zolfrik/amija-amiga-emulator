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

package io.github.zolfrik.amija.state;

import io.github.zolfrik.amija.cpu.Processor;
import io.github.zolfrik.amija.custom.AbstractAgnus;
import io.github.zolfrik.amija.custom.AbstractDenise;
import io.github.zolfrik.amija.custom.EventCpuBus;
import io.github.zolfrik.amija.input.GamePort;
import io.github.zolfrik.amija.memory.RAM;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * An in-memory snapshot of one running machine, for taking and restoring its state many
 * times per second (rewind, run-ahead, instant save slots).
 *
 * <p>It covers exactly what a save state file covers: the same roots
 * ({@link SaveStateManager#buildRoots}) and the same fields ({@link ReflectiveState}'s
 * selection: primitives, enums and arrays of them), and it restores them by the same rules
 * (final scalars are constants and are left alone, an array of the same shape is copied in
 * place). What differs is the medium. The snapshot belongs to one machine in one JVM, so it
 * needs no names, tags or versions: the fields of each class are read and written by code
 * generated for it ({@link FieldBlock}), scalars go into a {@code long[]} and every array
 * into a copy allocated on the first capture and reused afterwards. A capture or a restore
 * allocates nothing in the steady state.
 *
 * <p>Memory ({@link RAM}) is most of the state and is not walked field by field: each RAM
 * keeps a copy of its own, brought up to date one written page at a time
 * ({@link RAM#captureInto}). Between two operations of the same snapshot, only the pages the
 * machine wrote in between are copied.
 *
 * <p>Like the save state, it must be used with the machine paused, and both operations first
 * bring the machine to a clean boundary (start of frame, processor at an instruction
 * boundary).
 */
public final class MachineSnapshot {

    private final Processor cpu;
    private final EventCpuBus eventBus;
    private final AbstractAgnus agnus;
    private final AbstractDenise denise;

    // One pair of generated accessors per declaring class of each root, bound to the root
    // and to its slots: (long[], double[], Object[])void and (Object[])void.
    private final MethodHandle[] scalarGet, scalarSet, arrayGet, arraySet;

    // Integral and boolean scalars widened to long, floating point ones to double, enums.
    private final long[] intValue;
    private final double[] fpValue;
    private final Object[] enumValue;

    // The arrays as the machine holds them right now, and the copies.
    private final Object[] arrayLive;
    private final Object[] arrayCopy;

    // Memories, with their copies.
    private final RAM[] rams;
    private final byte[][] ramCopy;
    // Pages each RAM copied at the last capture (1 = copied).
    private final byte[][] ramChanged;

    private boolean captured;

    private MachineSnapshot(Processor cpu, EventCpuBus eventBus, AbstractAgnus agnus,
            List<Object> roots) {
        this.cpu = cpu;
        this.eventBus = eventBus;
        this.agnus = agnus;
        this.denise = SaveStateManager.field(agnus, "denise") instanceof AbstractDenise d ? d : null;

        List<MethodHandle> sGet = new ArrayList<>(), sSet = new ArrayList<>();
        List<MethodHandle> aGet = new ArrayList<>(), aSet = new ArrayList<>();
        List<RAM> ramList = new ArrayList<>();
        int ints = 0, fps = 0, enums = 0, arrays = 0;
        for (Object root : roots) {
            if (root instanceof RAM ram) {
                // Its state is the content alone (size and label are final).
                ramList.add(ram);
                continue;
            }
            for (FieldBlock b : FieldBlock.of(root.getClass())) {
                sGet.add(b.scalarGetter(root, ints, fps, enums));
                sSet.add(b.scalarSetter(root, ints, fps, enums));
                aGet.add(b.arrayGetter(root, arrays));
                aSet.add(b.arraySetter(root, arrays));
                ints += b.ints;
                fps += b.fps;
                enums += b.enums;
                arrays += b.arrays;
            }
        }
        scalarGet = sGet.toArray(new MethodHandle[0]);
        scalarSet = sSet.toArray(new MethodHandle[0]);
        arrayGet = aGet.toArray(new MethodHandle[0]);
        arraySet = aSet.toArray(new MethodHandle[0]);
        intValue = new long[ints];
        fpValue = new double[fps];
        enumValue = new Object[enums];
        arrayLive = new Object[arrays];
        arrayCopy = new Object[arrays];
        rams = ramList.toArray(new RAM[0]);
        ramCopy = new byte[rams.length][];
        ramChanged = new byte[rams.length][];
        for (int i = 0; i < rams.length; i++) {
            ramCopy[i] = new byte[rams[i].size()];
            ramChanged[i] = new byte[rams[i].pageCount()];
        }
    }

    /**
     * A snapshot of this machine, empty until the first {@link #capture()}. {@code eventBus}
     * is the bus of an event-driven processor, null for a clocked one.
     */
    public static MachineSnapshot of(Processor cpu, EventCpuBus eventBus, AbstractAgnus agnus,
            GamePort port1, GamePort port2) {
        return new MachineSnapshot(cpu, eventBus, agnus,
                SaveStateManager.buildRoots(cpu, eventBus, agnus, port1, port2));
    }

    /** {@code true} once a state has been captured. */
    public boolean isCaptured() {
        return captured;
    }

    /** Takes the state of the machine, replacing the one held. */
    public void capture() {
        SaveStateManager.advanceToBoundary(agnus, cpu, eventBus);
        if (denise != null) denise.syncRendering();
        try {
            for (MethodHandle h : scalarGet) h.invokeExact(intValue, fpValue, enumValue);
            for (MethodHandle h : arrayGet) h.invokeExact(arrayLive);
        } catch (Throwable t) {
            throw new IllegalStateException("snapshot: capture failed", t);
        }
        for (int i = 0; i < arrayLive.length; i++) {
            arrayCopy[i] = copyInto(arrayLive[i], arrayCopy[i]);
            arrayLive[i] = null;
        }
        for (int i = 0; i < rams.length; i++) rams[i].captureInto(ramCopy[i], this, ramChanged[i]);
        captured = true;
    }

    /** Puts the machine back in the captured state. The snapshot stays valid. */
    public void restore() {
        if (!captured) throw new IllegalStateException("snapshot: nothing captured");
        SaveStateManager.advanceToBoundary(agnus, cpu, eventBus);
        if (denise != null) denise.syncRendering();
        try {
            for (MethodHandle h : scalarSet) h.invokeExact(intValue, fpValue, enumValue);
            for (MethodHandle h : arrayGet) h.invokeExact(arrayLive);
            // An array of the same shape is copied in place; otherwise the field gets a fresh
            // copy, never the held one, which the next capture would write into. Final fields
            // keep their array whatever happens (the setters skip them).
            for (int i = 0; i < arrayLive.length; i++) {
                Object saved = arrayCopy[i];
                arrayLive[i] = saved == null ? null : copyInto(saved, arrayLive[i]);
            }
            for (MethodHandle h : arraySet) h.invokeExact(arrayLive);
        } catch (Throwable t) {
            throw new IllegalStateException("snapshot: restore failed", t);
        }
        Arrays.fill(arrayLive, null);
        for (int i = 0; i < rams.length; i++) rams[i].restoreFrom(ramCopy[i], this);
        // The memory map is derived from the AutoConfig boards just restored.
        agnus.rebuildAutoconfigMapping();
    }

    // =========================================================================
    // Export and import, for a history of states kept elsewhere
    // =========================================================================

    /** Number of memories in the snapshot. */
    public int ramCount() {
        return rams.length;
    }

    /**
     * The copy of memory {@code i} held by the snapshot. Valid until the next capture or
     * import; the caller must not change it.
     */
    public byte[] ramCopy(int i) {
        return ramCopy[i];
    }

    /**
     * Pages of memory {@code i} that the last capture copied, one flag per
     * {@link RAM#pageSize()} bytes: the pages that changed since this snapshot's previous
     * capture, restore or import. All of them after a full copy.
     */
    public byte[] ramChanged(int i) {
        return ramChanged[i];
    }

    /**
     * Everything the snapshot holds except the memories, as bytes. Only this snapshot can
     * read them back ({@link #importState}): the layout is its own.
     */
    public byte[] exportState() {
        if (!captured) throw new IllegalStateException("snapshot: nothing captured");
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(256 * 1024);
        try (java.io.ObjectOutputStream out = new java.io.ObjectOutputStream(bytes)) {
            out.writeObject(intValue);
            out.writeObject(fpValue);
            out.writeObject(enumValue);
            out.writeObject(arrayCopy);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("snapshot: export failed", e);
        }
        return bytes.toByteArray();
    }

    /**
     * Replaces the held state with one exported by this snapshot and with the given memory
     * contents, one per memory in {@link #ramCount()} order. The machine is not touched
     * until {@link #restore()}, which then copies every memory whole.
     */
    public void importState(byte[] state, byte[][] memories) {
        if (memories.length != rams.length) {
            throw new IllegalArgumentException("snapshot: " + memories.length
                    + " memories, " + rams.length + " expected");
        }
        try (java.io.ObjectInputStream in = new java.io.ObjectInputStream(
                new java.io.ByteArrayInputStream(state))) {
            long[] ints = (long[]) in.readObject();
            double[] fps = (double[]) in.readObject();
            Object[] enums = (Object[]) in.readObject();
            Object[] arrays = (Object[]) in.readObject();
            if (ints.length != intValue.length || fps.length != fpValue.length
                    || enums.length != enumValue.length || arrays.length != arrayCopy.length) {
                throw new IllegalArgumentException("snapshot: state of another machine");
            }
            System.arraycopy(ints, 0, intValue, 0, ints.length);
            System.arraycopy(fps, 0, fpValue, 0, fps.length);
            System.arraycopy(enums, 0, enumValue, 0, enums.length);
            System.arraycopy(arrays, 0, arrayCopy, 0, arrays.length);
        } catch (java.io.IOException | ClassNotFoundException e) {
            throw new IllegalStateException("snapshot: import failed", e);
        }
        for (int i = 0; i < rams.length; i++) {
            if (memories[i].length != ramCopy[i].length) {
                throw new IllegalArgumentException("snapshot: memory " + i + " of "
                        + memories[i].length + " bytes, " + ramCopy[i].length + " expected");
            }
            System.arraycopy(memories[i], 0, ramCopy[i], 0, ramCopy[i].length);
            rams[i].invalidateTracking();
        }
        captured = true;
    }

    /** Bytes held by the snapshot's copies (diagnostics). */
    public long sizeBytes() {
        long n = intValue.length * 8L + fpValue.length * 8L + enumValue.length * 4L;
        for (Object a : arrayCopy) n += arrayBytes(a);
        for (byte[] c : ramCopy) n += c.length;
        return n;
    }

    // =========================================================================
    // Array copies
    // =========================================================================

    /**
     * Copies {@code src} into {@code dst} when it has the same class and length (in depth for
     * arrays of arrays) and returns {@code dst}; otherwise returns a new deep copy.
     */
    private static Object copyInto(Object src, Object dst) {
        if (src == null) return null;
        int n = Array.getLength(src);
        Class<?> ct = src.getClass().getComponentType();
        if (dst == null || dst.getClass() != src.getClass() || Array.getLength(dst) != n) {
            dst = Array.newInstance(ct, n);
        }
        if (ct.isArray()) {
            Object[] s = (Object[]) src;
            Object[] d = (Object[]) dst;
            for (int i = 0; i < n; i++) d[i] = copyInto(s[i], d[i]);
        } else {
            System.arraycopy(src, 0, dst, 0, n);
        }
        return dst;
    }

    private static long arrayBytes(Object a) {
        if (a == null) return 0;
        int n = Array.getLength(a);
        Class<?> ct = a.getClass().getComponentType();
        if (ct.isArray()) {
            long sum = 0;
            for (Object e : (Object[]) a) sum += arrayBytes(e);
            return sum;
        }
        int unit = ct == byte.class || ct == boolean.class ? 1
                : ct == char.class || ct == short.class ? 2
                : ct == long.class || ct == double.class ? 8 : 4;
        return (long) n * unit;
    }
}
