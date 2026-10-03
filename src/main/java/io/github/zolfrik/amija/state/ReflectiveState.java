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

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>Type-constrained</b> reflective walker for saving/restoring the state of an emulator
 * component (see the save state plan).
 *
 * <p>For a given "root" object it serialises its <b>instance fields</b> (going up the
 * superclasses too, e.g. {@code AbstractAgnus}/{@code AbstractDenise}) <b>only</b> if the
 * field's type is:
 * <ul>
 *   <li>a primitive ({@code int/long/short/byte/boolean/char/double/float});</li>
 *   <li>an array of primitives (of any dimension: 1D, 2D, ...);</li>
 *   <li>an {@code enum} (saved as its ordinal) or an array of {@code enum}.</li>
 * </ul>
 * Every other field type (references to other components, Swing/AWT objects, the audio line,
 * the 68000's {@code decodeTable}, the {@code BusOp} pools, {@code DiskImage}, the host input
 * adapters, the per-line recorders...) is <b>ignored by construction</b>: an object reference
 * is never followed. Unlike Java serialisation, this keeps threads, native resources and
 * transient graphs out of the snapshot.
 *
 * <p>The format is <b>self-describing</b> (every value carries a type tag and every field its
 * name): the reader assigns by name, skipping unknown fields and leaving missing ones at
 * their default. That makes the slot resilient to the evolution of the code.
 *
 * <p>Not thread-safe: to be used with the machine paused.
 */
public final class ReflectiveState {

    private ReflectiveState() {}

    // Type tags, chosen as readable characters for debugging the slots.
    private static final byte T_BOOL = 'Z', T_BYTE = 'B', T_CHAR = 'C', T_SHORT = 'S',
            T_INT = 'I', T_LONG = 'J', T_FLOAT = 'F', T_DOUBLE = 'D',
            T_ENUM = 'E', T_PARR = 'a', T_OARR = 'A';

    // Cache of the serialisable fields per class (setAccessible already applied).
    //
    // ConcurrentHashMap and not HashMap: the cache is STATIC, so it is shared by all the
    // machines alive in the same JVM, and the game sweep runs three in parallel. The rest of
    // the class is declared not thread-safe and must be used with the machine paused, but
    // "paused" is a PER-MACHINE property: two different machines, each correctly paused, can
    // enter here together, and on a HashMap in a concurrent resize the damage is not a wrong
    // value but a corrupt map (at worst an infinite loop in a bucket).
    private static final Map<Class<?>, List<Field>> FIELD_CACHE = new ConcurrentHashMap<>();

    // =========================================================================
    // API
    // =========================================================================

    /** Writes the serialisable fields of {@code root} to {@code out}. */
    public static void write(DataOutputStream out, Object root) throws IOException {
        List<Field> fields = stateFields(root.getClass());
        out.writeInt(fields.size());
        for (Field f : fields) {
            out.writeUTF(f.getName());
            try {
                encode(out, f.getType(), f.get(root));
            } catch (IllegalAccessException e) {
                throw new IOException("save: campo non accessibile " + f, e);
            }
        }
    }

    /** Restores into {@code root} the fields read from {@code in} (assigned by name). */
    public static void read(DataInputStream in, Object root) throws IOException {
        Map<String, Field> byName = new HashMap<>();
        for (Field f : stateFields(root.getClass())) byName.put(f.getName(), f);
        int count = in.readInt();
        for (int i = 0; i < count; i++) {
            String name = in.readUTF();
            Object val = decode(in); // always consumes the bytes, even if the field no longer exists
            Field f = byName.get(name);
            if (f == null) continue; // field unknown in this version: discarded
            try {
                assign(root, f, val);
            } catch (IllegalAccessException e) {
                throw new IOException("load: campo non scrivibile " + f, e);
            }
        }
    }

    // =========================================================================
    // Field selection
    // =========================================================================

    private static List<Field> stateFields(Class<?> cls) {
        List<Field> cached = FIELD_CACHE.get(cls);
        if (cached != null) return cached;
        List<Field> out = new ArrayList<>();
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (f.isSynthetic()) continue;              // e.g. this$0 of inner classes
                if (!isSerializable(f.getType())) continue; // per-type rule
                f.setAccessible(true);
                out.add(f);
            }
        }
        FIELD_CACHE.put(cls, out);
        return out;
    }

    private static boolean isSerializable(Class<?> t) {
        if (t.isPrimitive() || t.isEnum()) return true;
        if (t.isArray()) return arrayLeafOk(t.getComponentType());
        return false;
    }

    private static boolean arrayLeafOk(Class<?> ct) {
        if (ct.isPrimitive() || ct.isEnum()) return true;
        if (ct.isArray()) return arrayLeafOk(ct.getComponentType());
        return false;
    }

    // =========================================================================
    // Encode
    // =========================================================================

    private static void encode(DataOutputStream o, Class<?> type, Object v) throws IOException {
        if (type == boolean.class) { o.writeByte(T_BOOL); o.writeBoolean((Boolean) v); }
        else if (type == byte.class) { o.writeByte(T_BYTE); o.writeByte((Byte) v); }
        else if (type == char.class) { o.writeByte(T_CHAR); o.writeChar((Character) v); }
        else if (type == short.class) { o.writeByte(T_SHORT); o.writeShort((Short) v); }
        else if (type == int.class) { o.writeByte(T_INT); o.writeInt((Integer) v); }
        else if (type == long.class) { o.writeByte(T_LONG); o.writeLong((Long) v); }
        else if (type == float.class) { o.writeByte(T_FLOAT); o.writeFloat((Float) v); }
        else if (type == double.class) { o.writeByte(T_DOUBLE); o.writeDouble((Double) v); }
        else if (type.isEnum()) { o.writeByte(T_ENUM); o.writeInt(v == null ? -1 : ((Enum<?>) v).ordinal()); }
        else if (type.isArray()) { encodeArray(o, type.getComponentType(), v); }
        else throw new IOException("tipo non serializzabile: " + type);
    }

    private static void encodeArray(DataOutputStream o, Class<?> ct, Object arr) throws IOException {
        if (ct.isPrimitive()) {
            o.writeByte(T_PARR);
            o.writeByte(primTag(ct));
            if (arr == null) { o.writeInt(-1); return; }
            int n = Array.getLength(arr);
            o.writeInt(n);
            writePrimArray(o, ct, arr, n);
        } else { // enum array or multidimensional array
            o.writeByte(T_OARR);
            if (arr == null) { o.writeInt(-1); return; }
            int n = Array.getLength(arr);
            o.writeInt(n);
            for (int i = 0; i < n; i++) encode(o, ct, Array.get(arr, i));
        }
    }

    private static void writePrimArray(DataOutputStream o, Class<?> ct, Object arr, int n)
            throws IOException {
        if (ct == byte.class) { o.write((byte[]) arr); }
        else if (ct == boolean.class) { boolean[] a = (boolean[]) arr; for (int i = 0; i < n; i++) o.writeBoolean(a[i]); }
        else if (ct == char.class) { char[] a = (char[]) arr; for (int i = 0; i < n; i++) o.writeChar(a[i]); }
        else if (ct == short.class) { short[] a = (short[]) arr; for (int i = 0; i < n; i++) o.writeShort(a[i]); }
        else if (ct == int.class) { int[] a = (int[]) arr; for (int i = 0; i < n; i++) o.writeInt(a[i]); }
        else if (ct == long.class) { long[] a = (long[]) arr; for (int i = 0; i < n; i++) o.writeLong(a[i]); }
        else if (ct == float.class) { float[] a = (float[]) arr; for (int i = 0; i < n; i++) o.writeFloat(a[i]); }
        else if (ct == double.class) { double[] a = (double[]) arr; for (int i = 0; i < n; i++) o.writeDouble(a[i]); }
    }

    // =========================================================================
    // Decode (self-describing: always consumes, maps to the field only if present)
    // =========================================================================

    /**
     * Cap on the number of elements of an array read from the slot.
     *
     * <p>The length is declared by the <b>file</b>, and a file can be truncated, corrupted
     * halfway through a write or simply not be a slot. Without a cap,
     * {@code new int[in.readInt()]} on any value would give {@code OutOfMemoryError}, an
     * {@code Error}, so it would escape the loader's {@code catch (Exception)} and reach the
     * EDT instead of becoming the "slot not compatible" message.
     *
     * <p>The cap is generous: the largest array an emulated machine can have is Fast RAM at
     * its maximum, 8 MB in a {@code byte[]} (see {@code RAM.mem}), and 64 million elements
     * leave eight times that margin, enough not to revisit it if expansions grow one day. It
     * rejects the absurd numbers of a corrupt file; it is not a memory quota.
     */
    private static final int MAX_ARRAY_ELEMENTS = 1 << 26;

    private static int checkedLength(int n) throws IOException {
        if (n < -1 || n > MAX_ARRAY_ELEMENTS) {
            throw new IOException("array di " + n + " elementi nello slot: fuori scala "
                    + "(massimo " + MAX_ARRAY_ELEMENTS + "). Lo slot e' corrotto o troncato.");
        }
        return n;
    }

    private static Object decode(DataInputStream in) throws IOException {
        byte tag = in.readByte();
        switch (tag) {
            case T_BOOL: return in.readBoolean();
            case T_BYTE: return in.readByte();
            case T_CHAR: return in.readChar();
            case T_SHORT: return in.readShort();
            case T_INT: return in.readInt();
            case T_LONG: return in.readLong();
            case T_FLOAT: return in.readFloat();
            case T_DOUBLE: return in.readDouble();
            case T_ENUM: return new EnumRef(in.readInt());
            case T_PARR: {
                byte k = in.readByte();
                int n = checkedLength(in.readInt());
                if (n < 0) return null;
                return readPrimArray(in, k, n);
            }
            case T_OARR: {
                int n = checkedLength(in.readInt());
                if (n < 0) return null;
                Object[] a = new Object[n];
                for (int i = 0; i < n; i++) a[i] = decode(in);
                return a;
            }
            default: throw new IOException("tag di tipo sconosciuto: " + (char) tag);
        }
    }

    private static Object readPrimArray(DataInputStream in, byte kind, int n) throws IOException {
        switch (kind) {
            case T_BYTE: { byte[] a = new byte[n]; in.readFully(a); return a; }
            case T_BOOL: { boolean[] a = new boolean[n]; for (int i = 0; i < n; i++) a[i] = in.readBoolean(); return a; }
            case T_CHAR: { char[] a = new char[n]; for (int i = 0; i < n; i++) a[i] = in.readChar(); return a; }
            case T_SHORT: { short[] a = new short[n]; for (int i = 0; i < n; i++) a[i] = in.readShort(); return a; }
            case T_INT: { int[] a = new int[n]; for (int i = 0; i < n; i++) a[i] = in.readInt(); return a; }
            case T_LONG: { long[] a = new long[n]; for (int i = 0; i < n; i++) a[i] = in.readLong(); return a; }
            case T_FLOAT: { float[] a = new float[n]; for (int i = 0; i < n; i++) a[i] = in.readFloat(); return a; }
            case T_DOUBLE: { double[] a = new double[n]; for (int i = 0; i < n; i++) a[i] = in.readDouble(); return a; }
            default: throw new IOException("kind array sconosciuto: " + (char) kind);
        }
    }

    // =========================================================================
    // Assignment to the field (coerced to the declared type)
    // =========================================================================

    private static void assign(Object root, Field f, Object val)
            throws IllegalAccessException, IOException {
        Class<?> type = f.getType();
        boolean isFinal = Modifier.isFinal(f.getModifiers());

        if (type.isArray()) {
            // Final arrays (e.g. the CPU's d[]/a[] registers, Agnus's DMA pointers, the Chip
            // RAM) keep the same reference: the CONTENT is copied in place, never rewriting
            // the final field. If shape/length match it is copied; otherwise (non-final array)
            // a new array is assigned.
            Object arr = (val == null) ? null : buildArray(type.getComponentType(), val);
            Object cur = f.get(root);
            if (cur != null && arr != null
                    && cur.getClass() == arr.getClass()
                    && Array.getLength(cur) == Array.getLength(arr)) {
                int n = Array.getLength(arr);
                if (type.getComponentType().isPrimitive()) {
                    System.arraycopy(arr, 0, cur, 0, n);
                } else {
                    for (int i = 0; i < n; i++) Array.set(cur, i, Array.get(arr, i));
                }
            } else if (!isFinal && (arr == null || type.isInstance(arr))) {
                f.set(root, arr);
            }
            // Final array of a different shape: not expected, left as it is. Likewise for an
            // array whose type is no longer what it was (a field changed from short[] to
            // int[], e.g.): assigning it would give IllegalArgumentException, which is not
            // among the exceptions read() converts and would fail the whole load instead of
            // just the field. It is skipped, as an unknown field.
            return;
        }

        // FINAL scalar/enum fields: they are constants (set at construction, equal between
        // two machines with the same configuration), so they are not rewritten. That also
        // avoids the reflective mutation of final fields, deprecated by recent JDKs.
        if (isFinal) return;

        if (val == null) {
            if (!type.isPrimitive()) f.set(root, null);
            return;
        }
        if (type.isEnum()) {
            int ord = (val instanceof EnumRef) ? ((EnumRef) val).ordinal : ((Number) val).intValue();
            Object constant = enumConstant(type, ord);
            if (constant != NO_CONSTANT) {
                f.set(root, constant);
            }
            // Ordinal outside today's constants: the default is kept, as for a missing field.
            // See enumConstant().
            return;
        }
        f.set(root, coercePrim(type, val));
    }

    private static Object buildArray(Class<?> ct, Object decoded) {
        if (ct.isPrimitive()) {
            return decoded; // already a primitive array of the kind read from the stream
        }
        Object[] src = (Object[]) decoded;
        Object arr = Array.newInstance(ct, src.length);
        for (int i = 0; i < src.length; i++) {
            if (src[i] == null) continue;
            if (ct.isEnum()) {
                int ord = ((EnumRef) src[i]).ordinal;
                Object constant = enumConstant(ct, ord);
                Array.set(arr, i, constant == NO_CONSTANT ? null : constant);
            } else if (ct.isArray()) {
                Array.set(arr, i, buildArray(ct.getComponentType(), src[i]));
            }
        }
        return arr;
    }

    private static Object coercePrim(Class<?> t, Object v) {
        if (t == boolean.class) return (v instanceof Boolean) ? v : ((Number) v).intValue() != 0;
        if (t == char.class) return (v instanceof Character) ? v : (char) ((Number) v).intValue();
        Number n = (Number) v;
        if (t == byte.class) return n.byteValue();
        if (t == short.class) return n.shortValue();
        if (t == int.class) return n.intValue();
        if (t == long.class) return n.longValue();
        if (t == float.class) return n.floatValue();
        if (t == double.class) return n.doubleValue();
        return v;
    }

    private static byte primTag(Class<?> ct) {
        if (ct == boolean.class) return T_BOOL;
        if (ct == byte.class) return T_BYTE;
        if (ct == char.class) return T_CHAR;
        if (ct == short.class) return T_SHORT;
        if (ct == int.class) return T_INT;
        if (ct == long.class) return T_LONG;
        if (ct == float.class) return T_FLOAT;
        return T_DOUBLE;
    }

    /** Sentinel: the ordinal read matches no constant of today. */
    private static final Object NO_CONSTANT = new Object();

    /**
     * The constant of {@code type} with that ordinal, {@code null} for {@code -1} (how a null
     * enum is written), {@link #NO_CONSTANT} if the ordinal does not exist.
     *
     * <p>Not a theoretical case, and exactly what this class claims to handle: the format is
     * self-describing so that "the slot is resilient to the evolution of the code", and
     * removing or reordering an enum constant is code evolution like any other. An ordinal
     * left uncovered would throw {@code ArrayIndexOutOfBoundsException} and fail the WHOLE
     * load, exactly in the scenario the promise covers. It behaves like an unknown field
     * instead: skipped, with the default kept.
     */
    private static Object enumConstant(Class<?> type, int ordinal) {
        if (ordinal < 0) {
            return null;
        }
        Object[] consts = type.getEnumConstants();
        return (consts == null || ordinal >= consts.length) ? NO_CONSTANT : consts[ordinal];
    }

    /** Holder of the ordinal of an enum read, resolved to the constant once the field's type is known. */
    private static final class EnumRef {
        final int ordinal;
        EnumRef(int ordinal) { this.ordinal = ordinal; }
    }
}
