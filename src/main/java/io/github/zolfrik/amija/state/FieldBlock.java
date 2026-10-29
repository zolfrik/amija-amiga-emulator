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

import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Direct access to the state fields one class declares: four static methods generated for
 * that class, which read and write its fields with plain field instructions.
 *
 * <p>A snapshot reads and writes about a thousand scalar fields, once per frame and with the
 * caches cold from a frame of emulation. Through reflection or method handles every field
 * costs several cache misses on the accessor objects; generated code touches only the
 * object's own fields. The class is defined as a hidden class in the nest of the declaring
 * class, which is what gives it access to private fields.
 *
 * <p>The fields are {@link ReflectiveState}'s selection for the class, split into integral
 * and boolean scalars (widened to {@code long}), floating point scalars (widened to
 * {@code double}), enum scalars and arrays. Final scalars are constants and are left out;
 * final arrays are read but never written. The generated methods are
 * <pre>
 *   getScalars(Object owner, long[] ints, double[] fps, Object[] enums, int io, int fo, int eo)
 *   setScalars(the same)
 *   getArrays(Object owner, Object[] arrays, int ao)
 *   setArrays(the same)   // non-final arrays only
 * </pre>
 * each field going to or coming from its slot after the given offset.
 */
final class FieldBlock {

    final int ints, fps, enums, arrays;
    private final MethodHandle getScalars, setScalars, getArrays, setArrays;

    private static final Map<Class<?>, FieldBlock> CACHE = new ConcurrentHashMap<>();

    private static final MethodType SCALARS = MethodType.methodType(void.class, Object.class,
            long[].class, double[].class, Object[].class, int.class, int.class, int.class);
    private static final MethodType ARRAYS = MethodType.methodType(void.class, Object.class,
            Object[].class, int.class);

    /** Bound to one owner and its offsets: {@code (long[], double[], Object[])void}. */
    MethodHandle scalarGetter(Object owner, int io, int fo, int eo) {
        return bind(getScalars, owner, io, fo, eo);
    }

    MethodHandle scalarSetter(Object owner, int io, int fo, int eo) {
        return bind(setScalars, owner, io, fo, eo);
    }

    /** Bound to one owner and its offset: {@code (Object[])void}. */
    MethodHandle arrayGetter(Object owner, int ao) {
        return MethodHandles.insertArguments(getArrays, 2, ao).bindTo(owner);
    }

    MethodHandle arraySetter(Object owner, int ao) {
        return MethodHandles.insertArguments(setArrays, 2, ao).bindTo(owner);
    }

    private static MethodHandle bind(MethodHandle mh, Object owner, int io, int fo, int eo) {
        return MethodHandles.insertArguments(mh, 4, io, fo, eo).bindTo(owner);
    }

    /** The fields of {@code owner}'s class hierarchy, one block per declaring class. */
    static List<FieldBlock> of(Class<?> cls) {
        List<FieldBlock> out = new ArrayList<>();
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            FieldBlock b = forDeclaring(c, cls);
            if (b != null) out.add(b);
        }
        return out;
    }

    private static FieldBlock forDeclaring(Class<?> decl, Class<?> leaf) {
        FieldBlock cached = CACHE.get(decl);
        if (cached != null) return cached.isEmpty() ? null : cached;
        List<Field> intFields = new ArrayList<>(), fpFields = new ArrayList<>(),
                enumFields = new ArrayList<>(), arrayFields = new ArrayList<>();
        for (Field f : ReflectiveState.stateFields(leaf)) {
            if (f.getDeclaringClass() != decl) continue;
            Class<?> t = f.getType();
            boolean isFinal = Modifier.isFinal(f.getModifiers());
            if (t.isArray()) arrayFields.add(f);
            else if (isFinal) continue;
            else if (t == float.class || t == double.class) fpFields.add(f);
            else if (t.isPrimitive()) intFields.add(f);
            else enumFields.add(f);
        }
        FieldBlock b = new FieldBlock(decl, intFields, fpFields, enumFields, arrayFields);
        CACHE.put(decl, b);
        return b.isEmpty() ? null : b;
    }

    private boolean isEmpty() {
        return ints + fps + enums + arrays == 0;
    }

    private FieldBlock(Class<?> decl, List<Field> intFields, List<Field> fpFields,
            List<Field> enumFields, List<Field> arrayFields) {
        ints = intFields.size();
        fps = fpFields.size();
        enums = enumFields.size();
        arrays = arrayFields.size();
        if (isEmpty()) {
            getScalars = setScalars = getArrays = setArrays = null;
            return;
        }
        ClassDesc owner = desc(decl);
        ClassDesc self = ClassDesc.of(decl.getPackageName(), "AmijaStateAccess");
        MethodTypeDesc scalarsDesc = MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object,
                ConstantDescs.CD_long.arrayType(), ConstantDescs.CD_double.arrayType(),
                ConstantDescs.CD_Object.arrayType(), ConstantDescs.CD_int, ConstantDescs.CD_int,
                ConstantDescs.CD_int);
        MethodTypeDesc arraysDesc = MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object,
                ConstantDescs.CD_Object.arrayType(), ConstantDescs.CD_int);
        int flags = ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC;

        byte[] bytes = ClassFile.of().build(self, cb -> cb
                .withFlags(ClassFile.ACC_FINAL | ClassFile.ACC_SYNTHETIC)
                .withMethodBody("getScalars", scalarsDesc, flags, code -> {
                    // ints[io + k] = (long) owner.f
                    for (int k = 0; k < intFields.size(); k++) {
                        Field f = intFields.get(k);
                        slot(code, 1, 4, k);
                        read(code, owner, f);
                        if (f.getType() != long.class) code.i2l();
                        code.lastore();
                    }
                    for (int k = 0; k < fpFields.size(); k++) {
                        Field f = fpFields.get(k);
                        slot(code, 2, 5, k);
                        read(code, owner, f);
                        if (f.getType() == float.class) code.f2d();
                        code.dastore();
                    }
                    for (int k = 0; k < enumFields.size(); k++) {
                        slot(code, 3, 6, k);
                        read(code, owner, enumFields.get(k));
                        code.aastore();
                    }
                    code.return_();
                })
                .withMethodBody("setScalars", scalarsDesc, flags, code -> {
                    // owner.f = (T) ints[io + k]
                    for (int k = 0; k < intFields.size(); k++) {
                        Field f = intFields.get(k);
                        code.aload(0).checkcast(owner);
                        slot(code, 1, 4, k);
                        code.laload();
                        Class<?> t = f.getType();
                        if (t != long.class) {
                            code.l2i();
                            if (t == byte.class) code.i2b();
                            else if (t == short.class) code.i2s();
                            else if (t == char.class) code.i2c();
                        }
                        write(code, owner, f);
                    }
                    for (int k = 0; k < fpFields.size(); k++) {
                        Field f = fpFields.get(k);
                        code.aload(0).checkcast(owner);
                        slot(code, 2, 5, k);
                        code.daload();
                        if (f.getType() == float.class) code.d2f();
                        write(code, owner, f);
                    }
                    for (int k = 0; k < enumFields.size(); k++) {
                        Field f = enumFields.get(k);
                        code.aload(0).checkcast(owner);
                        slot(code, 3, 6, k);
                        code.aaload().checkcast(desc(f.getType()));
                        write(code, owner, f);
                    }
                    code.return_();
                })
                .withMethodBody("getArrays", arraysDesc, flags, code -> {
                    for (int k = 0; k < arrayFields.size(); k++) {
                        slot(code, 1, 2, k);
                        read(code, owner, arrayFields.get(k));
                        code.aastore();
                    }
                    code.return_();
                })
                .withMethodBody("setArrays", arraysDesc, flags, code -> {
                    for (int k = 0; k < arrayFields.size(); k++) {
                        Field f = arrayFields.get(k);
                        if (Modifier.isFinal(f.getModifiers())) continue;
                        code.aload(0).checkcast(owner);
                        slot(code, 1, 2, k);
                        code.aaload().checkcast(desc(f.getType()));
                        write(code, owner, f);
                    }
                    code.return_();
                }));
        try {
            MethodHandles.Lookup host = MethodHandles.privateLookupIn(decl, MethodHandles.lookup());
            MethodHandles.Lookup hidden = host.defineHiddenClass(bytes, true,
                    MethodHandles.Lookup.ClassOption.NESTMATE);
            Class<?> c = hidden.lookupClass();
            getScalars = hidden.findStatic(c, "getScalars", SCALARS);
            setScalars = hidden.findStatic(c, "setScalars", SCALARS);
            getArrays = hidden.findStatic(c, "getArrays", ARRAYS);
            setArrays = hidden.findStatic(c, "setArrays", ARRAYS);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("snapshot: cannot generate the accessors of " + decl, e);
        }
    }

    /** Pushes {@code array, offset + k}: the array in local {@code arrayLocal}. */
    private static void slot(CodeBuilder code, int arrayLocal, int offsetLocal, int k) {
        code.aload(arrayLocal).iload(offsetLocal).loadConstant(k).iadd();
    }

    private static void read(CodeBuilder code, ClassDesc owner, Field f) {
        code.aload(0).checkcast(owner).getfield(owner, f.getName(), desc(f.getType()));
    }

    private static void write(CodeBuilder code, ClassDesc owner, Field f) {
        code.putfield(owner, f.getName(), desc(f.getType()));
    }

    private static ClassDesc desc(Class<?> c) {
        return ClassDesc.ofDescriptor(c.descriptorString());
    }
}
