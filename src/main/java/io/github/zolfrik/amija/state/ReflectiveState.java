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

/**
 * Walker riflessivo <b>vincolato per tipo</b> per il salvataggio/ripristino dello stato
 * di un componente dell'emulatore (vedi il piano save state).
 *
 * <p>Per un dato oggetto "root" serializza i suoi <b>campi d'istanza</b> (salendo anche
 * lungo le superclassi, es. {@code AbstractAgnus}/{@code AbstractDenise}) <b>solo</b> se
 * il tipo del campo è:
 * <ul>
 *   <li>una primitiva ({@code int/long/short/byte/boolean/char/double/float});</li>
 *   <li>un array di primitive (a qualunque dimensione: 1D, 2D, …);</li>
 *   <li>un {@code enum} (salvato come ordinale) o un array di {@code enum}.</li>
 * </ul>
 * Ogni altro tipo di campo (riferimenti ad altri componenti, oggetti Swing/AWT, la linea
 * audio, la {@code decodeTable} del 68000, i pool di {@code BusOp}, {@code DiskImage}, gli
 * adapter di input host, i recorder per-linea…) viene <b>ignorato per costruzione</b>: mai
 * si segue un riferimento a oggetto. Questo, a differenza della serializzazione Java, tiene
 * fuori dallo snapshot thread, risorse native e grafi transitori.
 *
 * <p>Il formato è <b>auto-descrittivo</b> (ogni valore porta un tag di tipo e ogni campo il
 * proprio nome): il reader assegna per nome, saltando i campi sconosciuti e lasciando al
 * default quelli mancanti. Questo rende lo slot resiliente all'evoluzione del codice.
 *
 * <p>Non thread-safe: va usato a macchina in pausa.
 */
public final class ReflectiveState {

    private ReflectiveState() {}

    // Tag di tipo, scelti come caratteri leggibili per il debug degli slot.
    private static final byte T_BOOL = 'Z', T_BYTE = 'B', T_CHAR = 'C', T_SHORT = 'S',
            T_INT = 'I', T_LONG = 'J', T_FLOAT = 'F', T_DOUBLE = 'D',
            T_ENUM = 'E', T_PARR = 'a', T_OARR = 'A';

    // Cache dei campi serializzabili per classe (setAccessible già applicato).
    private static final Map<Class<?>, List<Field>> FIELD_CACHE = new HashMap<>();

    // =========================================================================
    // API
    // =========================================================================

    /** Scrive i campi serializzabili di {@code root} su {@code out}. */
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

    /** Ripristina in {@code root} i campi letti da {@code in} (assegnazione per nome). */
    public static void read(DataInputStream in, Object root) throws IOException {
        Map<String, Field> byName = new HashMap<>();
        for (Field f : stateFields(root.getClass())) byName.put(f.getName(), f);
        int count = in.readInt();
        for (int i = 0; i < count; i++) {
            String name = in.readUTF();
            Object val = decode(in); // consuma sempre i byte, anche se il campo non esiste più
            Field f = byName.get(name);
            if (f == null) continue; // campo sconosciuto in questa versione: scartato
            try {
                assign(root, f, val);
            } catch (IllegalAccessException e) {
                throw new IOException("load: campo non scrivibile " + f, e);
            }
        }
    }

    // =========================================================================
    // Selezione dei campi
    // =========================================================================

    private static List<Field> stateFields(Class<?> cls) {
        List<Field> cached = FIELD_CACHE.get(cls);
        if (cached != null) return cached;
        List<Field> out = new ArrayList<>();
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (f.isSynthetic()) continue;              // es. this$0 delle inner class
                if (!isSerializable(f.getType())) continue; // regola per-tipo
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
        } else { // array di enum o array multidimensionale
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
    // Decode (auto-descrittivo: consuma sempre, mappa al campo solo se presente)
    // =========================================================================

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
                int n = in.readInt();
                if (n < 0) return null;
                return readPrimArray(in, k, n);
            }
            case T_OARR: {
                int n = in.readInt();
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
    // Assegnazione al campo (coercizione al tipo dichiarato)
    // =========================================================================

    private static void assign(Object root, Field f, Object val)
            throws IllegalAccessException, IOException {
        Class<?> type = f.getType();
        boolean isFinal = Modifier.isFinal(f.getModifiers());

        if (type.isArray()) {
            // Gli array final (es. i registri d[]/a[] della CPU, i puntatori DMA di Agnus,
            // la Chip RAM) mantengono lo stesso riferimento: si copia il CONTENUTO in-place,
            // mai riscrivendo il campo final. Se forma/lunghezza combaciano si copia; altrimenti
            // (array non-final) si assegna un nuovo array.
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
            } else if (!isFinal) {
                f.set(root, arr);
            }
            // array final di forma diversa: caso non atteso, si lascia com'è.
            return;
        }

        // Campi scalari/enum FINAL: sono costanti (fissati in costruzione, uguali tra due
        // macchine con la stessa configurazione), quindi non vanno riscritti. Evita anche
        // la mutazione riflessiva di campi final, deprecata dalle JDK recenti.
        if (isFinal) return;

        if (val == null) {
            if (!type.isPrimitive()) f.set(root, null);
            return;
        }
        if (type.isEnum()) {
            int ord = (val instanceof EnumRef) ? ((EnumRef) val).ordinal : ((Number) val).intValue();
            Object[] consts = type.getEnumConstants();
            f.set(root, ord < 0 ? null : consts[ord]);
            return;
        }
        f.set(root, coercePrim(type, val));
    }

    private static Object buildArray(Class<?> ct, Object decoded) {
        if (ct.isPrimitive()) {
            return decoded; // già un array primitivo del kind letto dallo stream
        }
        Object[] src = (Object[]) decoded;
        Object arr = Array.newInstance(ct, src.length);
        for (int i = 0; i < src.length; i++) {
            if (src[i] == null) continue;
            if (ct.isEnum()) {
                int ord = ((EnumRef) src[i]).ordinal;
                Array.set(arr, i, ord < 0 ? null : ct.getEnumConstants()[ord]);
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

    /** Holder dell'ordinale di un enum letto, risolto alla costante nota il tipo del campo. */
    private static final class EnumRef {
        final int ordinal;
        EnumRef(int ordinal) { this.ordinal = ordinal; }
    }
}
