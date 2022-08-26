/*
 * Copyright (c) 1998, 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 * Copyright (C) 2019 JetBrains s.r.o.
 *
 * This program is free software; you can redistribute and/or modify it under
 * the terms of the GNU General Public License v2 with Classpath Exception.
 * The text of the license is available in the file LICENSE.TXT.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See LICENSE.TXT for more details.
 *
 * You may contact JetBrains s.r.o. at Na Hřebenech II 1718/10, 140 00 Prague,
 * Czech Republic or at legal@jetbrains.com.
 */

package com.jetbrains.jdi;

import com.sun.jdi.*;

import java.lang.ref.Reference;
import java.lang.ref.SoftReference;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.stream.Collectors;

public abstract class ReferenceTypeImpl extends TypeImpl implements ReferenceType {
    protected final long ref;
    private String signature = null;
    private volatile String baseSourceName = null;
    private String baseSourceDir = null;
    private String baseSourcePath = null;
    protected int modifiers = -1;
    private volatile SoftReference<Field[]> fieldsRef = null;
    private volatile SoftReference<Method[]> methodsRef = null;
    private volatile SoftReference<SDE> sdeRef = null;

    private ClassLoaderReference classLoader = null;
    private ClassObjectReference classObject = null;
    private ModuleReference module = null;

    private int status = 0;
    private boolean isPrepared = false;

    private boolean versionNumberGotten = false;
    private int majorVersion;
    private int minorVersion;

    private volatile boolean constantPoolInfoGotten = false;
    private volatile int constanPoolCount;
    private volatile SoftReference<byte[]> constantPoolBytesRef = null;

    /* to mark a SourceFile request that returned a genuine JDWP.Error.ABSENT_INFORMATION */
    private static final String ABSENT_BASE_SOURCE_NAME = "**ABSENT_BASE_SOURCE_NAME**";

    /* to mark when no info available */
    static final SDE NO_SDE_INFO_MARK = new SDE();

    // bits set when initialization was attempted (succeeded or failed)
    private static final int INITIALIZED_OR_FAILED =
        JDWP.ClassStatus.INITIALIZED | JDWP.ClassStatus.ERROR;

    protected ReferenceTypeImpl(VirtualMachine aVm, long aRef) {
        super(aVm);
        ref = aRef;
    }

    public void noticeRedefineClass() {
        //Invalidate information previously fetched and cached.
        //These will be refreshed later on demand.
        baseSourceName = null;
        baseSourcePath = null;
        modifiers = -1;
        fieldsRef = null;
        if (methodsRef != null) {
            Method[] methods = dereference(methodsRef);
            if (methods != null) {
                for (Method method : methods) {
                    ((MethodImpl) method).noticeRedefineClass();
                }
            }
        }
        methodsRef = null;
        sdeRef = null;
        versionNumberGotten = false;
        constantPoolInfoGotten = false;
    }

    Method getMethodMirror(long ref) {
        if (ref == 0) {
            // obsolete method
            return new ObsoleteMethodImpl(vm, this);
        }
        // Fetch all methods for the class, check performance impact
        // Needs no synchronization now, since methods() returns
        // unmodifiable local data
        for (Method value : methods()) {
            MethodImpl method = (MethodImpl) value;
            if (method.ref() == ref) {
                return method;
            }
        }
        throw new IllegalArgumentException("Invalid method id: " + ref);
    }

    CompletableFuture<Method> getMethodMirrorAsync(long ref) {
        if (ref == 0) {
            // obsolete method
            return CompletableFuture.completedFuture(new ObsoleteMethodImpl(vm, this));
        }
        return methodsAsync().thenApply(methods -> {
            for (Method value : methods) {
                MethodImpl method = (MethodImpl) value;
                if (method.ref() == ref) {
                    return method;
                }
            }
            throw new IllegalArgumentException("Invalid method id: " + ref);
        });
    }

    Field getFieldMirror(long ref) {
        // Fetch all fields for the class, check performance impact
        // Needs no synchronization now, since fields() returns
        // unmodifiable local data
        for (Field value : fields()) {
            FieldImpl field = (FieldImpl) value;
            if (field.ref() == ref) {
                return field;
            }
        }
        throw new IllegalArgumentException("Invalid field id: " + ref);
    }

    public boolean equals(Object obj) {
        if (obj instanceof ReferenceTypeImpl) {
            ReferenceTypeImpl other = (ReferenceTypeImpl)obj;
            return (ref() == other.ref()) &&
                (vm.equals(other.virtualMachine()));
        } else {
            return false;
        }
    }

    @Override
    public int hashCode() {
        return Long.hashCode(ref());
    }

    public int compareTo(ReferenceType object) {
        /*
         * Note that it is critical that compareTo() == 0
         * implies that equals() == true. Otherwise, TreeSet
         * will collapse classes.
         *
         * (Classes of the same name loaded by different class loaders
         * or in different VMs must not return 0).
         */
        ReferenceTypeImpl other = (ReferenceTypeImpl)object;
        int comp = name().compareTo(other.name());
        if (comp == 0) {
            long rf1 = ref();
            long rf2 = other.ref();
            // optimize for typical case: refs equal and VMs equal
            if (rf1 == rf2) {
                // sequenceNumbers are always positive
                comp = vm.sequenceNumber -
                 ((VirtualMachineImpl)(other.virtualMachine())).sequenceNumber;
            } else {
                comp = (rf1 < rf2)? -1 : 1;
            }
        }
        return comp;
    }

    public String signature() {
        if (signature == null) {
            // Does not need synchronization, since worst-case
            // static info is fetched twice
            try {
                setSignature(JDWP.ReferenceType.Signature.process(vm, this).signature);
            } catch (JDWPException exc) {
                throw exc.toJDIException();
            }
        }
        return signature;
    }

    public String genericSignature() {
        if (!vm.canGet1_5LanguageFeatures()) {
            return null;
        }
        JDWP.ReferenceType.SignatureWithGeneric result;
        try {
            result = JDWP.ReferenceType.SignatureWithGeneric.process(vm, this);
        } catch (JDWPException exc) {
            throw exc.toJDIException();
        }
        setSignature(result.signature);
        return result.genericSignature;
    }

    public ClassLoaderReference classLoader() {
        if (classLoader == null) {
            // Does not need synchronization, since worst-case
            // static info is fetched twice
            try {
                ClassLoaderReferenceImpl res = JDWP.ReferenceType.ClassLoader.process(vm, this).classLoader;
                classLoader = notnullize(res, ClassLoaderReferenceImpl.NULL);
                return res;
            } catch (JDWPException exc) {
                throw exc.toJDIException();
            }
        }
        return nullize(classLoader, ClassLoaderReferenceImpl.NULL);
    }

    public ModuleReference module() {
        if (module != null) {
            return module;
        }
        // Does not need synchronization, since worst-case
        // static info is fetched twice
        try {
            ModuleReferenceImpl m = JDWP.ReferenceType.Module.
                process(vm, this).module;
            module = vm.getModule(m.ref());
        } catch (JDWPException exc) {
            throw exc.toJDIException();
        }
        return module;
    }

    public boolean isPublic() {
        if (modifiers == -1)
            getModifiers();

        return((modifiers & VMModifiers.PUBLIC) > 0);
    }

    public boolean isProtected() {
        if (modifiers == -1)
            getModifiers();

        return((modifiers & VMModifiers.PROTECTED) > 0);
    }

    public boolean isPrivate() {
        if (modifiers == -1)
            getModifiers();

        return((modifiers & VMModifiers.PRIVATE) > 0);
    }

    public boolean isPackagePrivate() {
        return !isPublic() && !isPrivate() && !isProtected();
    }

    public boolean isAbstract() {
        if (modifiers == -1)
            getModifiers();

        return((modifiers & VMModifiers.ABSTRACT) > 0);
    }

    public boolean isFinal() {
        if (modifiers == -1)
            getModifiers();

        return((modifiers & VMModifiers.FINAL) > 0);
    }

    public boolean isStatic() {
        if (modifiers == -1)
            getModifiers();

        return((modifiers & VMModifiers.STATIC) > 0);
    }

    public boolean isPrepared() {
        // This ref type may have been prepared before we were getting
        // events, so get it once.  After that,
        // this status flag is updated through the ClassPrepareEvent,
        // there is no need for the expense of a JDWP query.
        if (status == 0) {
            updateStatus();
        }
        return isPrepared;
    }

    public boolean isVerified() {
        // Once true, it never resets, so we don't need to update
        if ((status & JDWP.ClassStatus.VERIFIED) == 0) {
            updateStatus();
        }
        return (status & JDWP.ClassStatus.VERIFIED) != 0;
    }

    public boolean isInitialized() {
        // Once initialization succeeds or fails, it never resets,
        // so we don't need to update
        if ((status & INITIALIZED_OR_FAILED) == 0) {
            updateStatus();
        }
        return (status & JDWP.ClassStatus.INITIALIZED) != 0;
    }

    public boolean failedToInitialize() {
        // Once initialization succeeds or fails, it never resets,
        // so we don't need to update
        if ((status & INITIALIZED_OR_FAILED) == 0) {
            updateStatus();
        }
        return (status & JDWP.ClassStatus.ERROR) != 0;
    }

    private Field[] readFields(JDWP.ReferenceType.Fields.FieldInfo[] jdwpFields) {
        return Arrays.stream(jdwpFields)
                .map(fi -> new FieldImpl(vm, this, fi.fieldID, fi.name, fi.signature, null, fi.modBits))
                .toArray(Field[]::new);
    }

    private Field[] readFieldsWithGenerics(JDWP.ReferenceType.FieldsWithGeneric.FieldInfo[] jdwpFields) {
        return Arrays.stream(jdwpFields)
                .map(fi -> new FieldImpl(vm, this, fi.fieldID, fi.name, fi.signature, fi.genericSignature, fi.modBits))
                .toArray(Field[]::new);
    }

    public CompletableFuture<List<Field>> fieldsAsync() {
        Field[] fields = dereference(fieldsRef);
        if (fields != null) {
            return CompletableFuture.completedFuture(unmodifiableList(fields));
        }
        CompletableFuture<Field[]> array;
        if (vm.canGet1_5LanguageFeatures()) {
            array = JDWP.ReferenceType.FieldsWithGeneric.processAsync(vm, this)
                    .thenApply(r -> readFieldsWithGenerics(r.declared));
        } else {
            array = JDWP.ReferenceType.Fields.processAsync(vm, this)
                    .thenApply(r -> readFields(r.declared));
        }
        return array.thenApply(res -> {
            fieldsRef = new SoftReference<>(res);
            return unmodifiableList(res);
        });
    }

    public List<Field> fields() {
        Field[] fields = dereference(fieldsRef);
        if (fields == null) {
            if (vm.canGet1_5LanguageFeatures()) {
                JDWP.ReferenceType.FieldsWithGeneric.FieldInfo[] jdwpFields;
                try {
                    jdwpFields = JDWP.ReferenceType.FieldsWithGeneric.
                        process(vm, this).declared;
                } catch (JDWPException exc) {
                    throw exc.toJDIException();
                }
                fields = readFieldsWithGenerics(jdwpFields);
            } else {
                JDWP.ReferenceType.Fields.FieldInfo[] jdwpFields;
                try {
                    jdwpFields = JDWP.ReferenceType.Fields.
                        process(vm, this).declared;
                } catch (JDWPException exc) {
                    throw exc.toJDIException();
                }
                fields = readFields(jdwpFields);
            }
            fieldsRef = new SoftReference<>(fields);
        }
        return unmodifiableList(fields);
    }

    abstract List<? extends ReferenceType> inheritedTypes();

    abstract CompletableFuture<List<? extends ReferenceType>> inheritedTypesAsync();

    void addVisibleFields(List<Field> visibleList, Map<String, Field> visibleTable, List<String> ambiguousNames) {
        for (Field field : visibleFields()) {
            String name = field.name();
            if (!ambiguousNames.contains(name)) {
                Field duplicate = visibleTable.get(name);
                if (duplicate == null) {
                    visibleList.add(field);
                    visibleTable.put(name, field);
                } else if (!field.equals(duplicate)) {
                    ambiguousNames.add(name);
                    visibleTable.remove(name);
                    visibleList.remove(duplicate);
                } else {
                    // identical field from two branches; do nothing
                }
            }
        }
    }

    public List<Field> visibleFields() {
        /*
         * Maintain two different collections of visible fields. The
         * list maintains a reasonable order for return. The
         * hash map provides an efficient way to lookup visible fields
         * by name, important for finding hidden or ambiguous fields.
         */
        List<Field> visibleList = new ArrayList<>();
        Map<String, Field>  visibleTable = new HashMap<>();

        /* Track fields removed from above collection due to ambiguity */
        List<String> ambiguousNames = new ArrayList<>();

        /* Add inherited, visible fields */
        for (ReferenceType referenceType : inheritedTypes()) {
            /*
             * TO DO: Be defensive and check for cyclic interface inheritance
             */
            ReferenceTypeImpl type = (ReferenceTypeImpl) referenceType;
            type.addVisibleFields(visibleList, visibleTable, ambiguousNames);
        }

        /*
         * Insert fields from this type, removing any inherited fields they
         * hide.
         */
        List<Field> retList = new ArrayList<>(fields());
        for (Field field : retList) {
            Field hidden = visibleTable.get(field.name());
            if (hidden != null) {
                visibleList.remove(hidden);
            }
        }
        retList.addAll(visibleList);
        return retList;
    }

    void addAllFields(List<Field> fieldList, Set<ReferenceType> typeSet) {
        /* Continue the recursion only if this type is new */
        if (!typeSet.contains(this)) {
            typeSet.add(this);

            /* Add local fields */
            fieldList.addAll(fields());

            /* Add inherited fields */
            for (ReferenceType referenceType : inheritedTypes()) {
                ReferenceTypeImpl type = (ReferenceTypeImpl) referenceType;
                type.addAllFields(fieldList, typeSet);
            }
        }
    }

    CompletableFuture<List<Field>> addAllFieldsAsync(List<Field> fieldList, Set<ReferenceType> typeSet) {
        return collectRecursively(ReferenceTypeImpl::fieldsAsync, fieldList, typeSet);
    }

    CompletableFuture<List<Method>> addAllMethodsAsync(List<Method> methodList, Set<ReferenceType> typeSet) {
        return collectRecursively(ReferenceTypeImpl::methodsAsync, methodList, typeSet);
    }

    private <T> CompletableFuture<List<T>> collectRecursively(Function<ReferenceTypeImpl, CompletableFuture<List<T>>> func, List<T> list, Set<ReferenceType> typeSet) {
        if (!typeSet.contains(this)) {
            typeSet.add(this);

            return func.apply(this)
                    .thenAccept(list::addAll)
                    .thenCompose(__ -> inheritedTypesAsync())
                    .thenCompose(types -> {
                        CompletableFuture<List<T>> res = CompletableFuture.completedFuture(list);
                        for (ReferenceType referenceType : types) {
                            res = res.thenCombine(((ReferenceTypeImpl) referenceType).collectRecursively(func, list, typeSet),
                                    (f, f2) -> list);
                        }
                        return res;
                    });
        }
        return CompletableFuture.completedFuture(list);
    }

    public List<Field> allFields() {
        List<Field> fieldList = new ArrayList<>();
        Set<ReferenceType> typeSet = new HashSet<>();
        addAllFields(fieldList, typeSet);
        return fieldList;
    }

    @SuppressWarnings("unused")
    public CompletableFuture<List<Field>> allFieldsAsync() {
        //TODO: may improve further, but need to preserve the order
        List<Field> fieldList = Collections.synchronizedList(new ArrayList<>());
        Set<ReferenceType> typeSet = Collections.synchronizedSet(new HashSet<>());
        return addAllFieldsAsync(fieldList, typeSet);
    }

    public Field fieldByName(String fieldName) {
        List<Field> searchList = visibleFields();

        for (Field f : searchList) {
            if (f.name().equals(fieldName)) {
                return f;
            }
        }
        //throw new NoSuchFieldException("Field '" + fieldName + "' not found in " + name());
        return null;
    }

    private Method[] readMethods(JDWP.ReferenceType.Methods.MethodInfo[] jdwpMethods) {
        return Arrays.stream(jdwpMethods)
                .map(mi -> MethodImpl.createMethodImpl(vm, this,
                        mi.methodID,
                        mi.name, mi.signature,
                        null,
                        mi.modBits))
                .toArray(Method[]::new);
    }

    private Method[] readMethodsWithGeneric(JDWP.ReferenceType.MethodsWithGeneric.MethodInfo[] jdwpMethods) {
        return Arrays.stream(jdwpMethods)
                .map(mi -> MethodImpl.createMethodImpl(vm, this,
                        mi.methodID,
                        mi.name, mi.signature,
                        mi.genericSignature,
                        mi.modBits))
                .toArray(Method[]::new);
    }

    public CompletableFuture<List<Method>> methodsAsync() {
        Method[] methods = dereference(methodsRef);
        if (methods != null) {
            return CompletableFuture.completedFuture(unmodifiableList(methods));
        }
        CompletableFuture<Method[]> array;
        if (!vm.canGet1_5LanguageFeatures()) {
            array = JDWP.ReferenceType.Methods.processAsync(vm, this)
                    .thenApply(m -> readMethods(m.declared));
        } else {
            array = JDWP.ReferenceType.MethodsWithGeneric.processAsync(vm, this)
                    .thenApply(m -> readMethodsWithGeneric(m.declared));
        }
        return array.thenApply(res -> {
            methodsRef = new SoftReference<>(res);
            return unmodifiableList(res);
        });
    }

    public List<Method> methods() {
        Method[] methods = dereference(methodsRef);
        if (methods == null) {
            if (!vm.canGet1_5LanguageFeatures()) {
                JDWP.ReferenceType.Methods.MethodInfo[] declared;
                try {
                    declared = JDWP.ReferenceType.Methods.
                            process(vm, this).declared;
                } catch (JDWPException exc) {
                    throw exc.toJDIException();
                }
                methods = readMethods(declared);
            } else {
                JDWP.ReferenceType.MethodsWithGeneric.MethodInfo[] declared;
                try {
                    declared = JDWP.ReferenceType.MethodsWithGeneric.
                            process(vm, this).declared;
                } catch (JDWPException exc) {
                    throw exc.toJDIException();
                }
                methods = readMethodsWithGeneric(declared);
            }
            methodsRef = new SoftReference<>(methods);
        }
        return unmodifiableList(methods);
    }

    /*
     * Utility method used by subclasses to build lists of visible
     * methods.
     */
    void addToMethodMap(Map<String, Method> methodMap, List<Method> methodList) {
        for (Method method : methodList)
            methodMap.put(method.name().concat(method.signature()), method);
        }

    abstract void addVisibleMethods(Map<String, Method> methodMap, Set<InterfaceType> seenInterfaces);

    public List<Method> visibleMethods() {
        /*
         * Build a collection of all visible methods. The hash
         * map allows us to do this efficiently by keying on the
         * concatenation of name and signature.
         */
        Map<String, Method> map = new HashMap<>();
        addVisibleMethods(map, new HashSet<>());

        /*
         * ... but the hash map destroys order. Methods should be
         * returned in a sensible order, as they are in allMethods().
         * So, start over with allMethods() and use the hash map
         * to filter that ordered collection.
         */
        List<Method> list = allMethods();
        list.retainAll(new HashSet<>(map.values()));
        return list;
    }

    public abstract List<Method> allMethods();

    public CompletableFuture<List<Method>> allMethodsAsync() {
        List<Method> methodList = Collections.synchronizedList(new ArrayList<>());
        Set<ReferenceType> typeSet = Collections.synchronizedSet(new HashSet<>());
        return addAllMethodsAsync(methodList, typeSet);
    }

    public List<Method> methodsByName(String name) {
        List<Method> methods = visibleMethods();
        ArrayList<Method> retList = new ArrayList<>(methods.size());
        for (Method candidate : methods) {
            if (candidate.name().equals(name)) {
                retList.add(candidate);
            }
        }
        retList.trimToSize();
        return retList;
    }

    public List<Method> methodsByName(String name, String signature) {
        List<Method> methods = visibleMethods();
        ArrayList<Method> retList = new ArrayList<>(methods.size());
        for (Method candidate : methods) {
            if (candidate.name().equals(name) &&
                candidate.signature().equals(signature)) {
                retList.add(candidate);
            }
        }
        retList.trimToSize();
        return retList;
    }

    InterfaceType[] getInterfaces() {
        try {
            return JDWP.ReferenceType.Interfaces.process(vm, this).interfaces;
        } catch (JDWPException exc) {
            throw exc.toJDIException();
        }
    }

    CompletableFuture<InterfaceType[]> getInterfacesAsync() {
        return JDWP.ReferenceType.Interfaces.processAsync(vm, this).thenApply(r -> r.interfaces);
    }

    <T> List<T> unmodifiableList(T[] array) {
        // List.of(array) in this case will copy the array
        return Collections.unmodifiableList(Arrays.asList(array));
    }

    public List<ReferenceType> nestedTypes() {
        List<ReferenceType> nested = new ArrayList<>();
        String outername = name();
        int outerlen = outername.length();
        vm.forEachClass(refType -> {
            String name = refType.name();
            int len = name.length();
            /* The separator is historically '$' but could also be '#' */
            if ( len > outerlen && name.startsWith(outername) ) {
                char c = name.charAt(outerlen);
                if ( c == '$' || c == '#' ) {
                    nested.add(refType);
                }
            }
        });
        return nested;
    }

    @SuppressWarnings("unused")
    public CompletableFuture<Value> getValueAsync(Field sig) {
        return getValuesAsync(Collections.singletonList(sig)).thenApply(m -> m.get(sig));
    }

    public Value getValue(Field sig) {
        return getValues(Collections.singletonList(sig)).get(sig);
    }

    void validateFieldAccess(Field field) {
        /*
         * Field must be in this object's class, a superclass, or
         * implemented interface
         */
        ReferenceTypeImpl declType = (ReferenceTypeImpl)field.declaringType();
        if (!declType.isAssignableFrom(this)) {
            throw new IllegalArgumentException("Invalid field");
        }
    }

    void validateFieldSet(Field field) {
        validateFieldAccess(field);
        if (field.isFinal()) {
            throw new IllegalArgumentException("Cannot set value of final field");
        }
    }

    public CompletableFuture<Map<Field,Value>> getValuesAsync(List<? extends Field> theFields) {
        validateMirrors(theFields);

        int size = theFields.size();
        JDWP.ReferenceType.GetValues.Field[] queryFields = new JDWP.ReferenceType.GetValues.Field[size];

        for (int i=0; i<size; i++) {
            FieldImpl field = (FieldImpl)theFields.get(i);

            validateFieldAccess(field);

            // Do more validation specific to ReferenceType field getting
            if (!field.isStatic()) {
                throw new IllegalArgumentException("Attempt to use non-static field with ReferenceType");
            }
            queryFields[i] = new JDWP.ReferenceType.GetValues.Field(field.ref());
        }

        return JDWP.ReferenceType.GetValues.processAsync(vm, this, queryFields).thenApply(r -> {
            ValueImpl[] values = r.values;

            if (size != values.length) {
                throw new InternalException(
                        "Wrong number of values returned from target VM");
            }

            Map<Field, Value> map = new HashMap<>(size);
            for (int i=0; i<size; i++) {
                FieldImpl field = (FieldImpl)theFields.get(i);
                map.put(field, values[i]);
            }

            return map;
        });
    }

    /**
     * Returns a map of field values
     */
    public Map<Field,Value> getValues(List<? extends Field> theFields) {
        validateMirrors(theFields);

        int size = theFields.size();
        JDWP.ReferenceType.GetValues.Field[] queryFields =
                         new JDWP.ReferenceType.GetValues.Field[size];

        for (int i=0; i<size; i++) {
            FieldImpl field = (FieldImpl)theFields.get(i);

            validateFieldAccess(field);

            // Do more validation specific to ReferenceType field getting
            if (!field.isStatic()) {
                throw new IllegalArgumentException(
                     "Attempt to use non-static field with ReferenceType");
            }
            queryFields[i] = new JDWP.ReferenceType.GetValues.Field(
                                         field.ref());
        }

        Map<Field, Value> map = new HashMap<>(size);

        ValueImpl[] values;
        try {
            values = JDWP.ReferenceType.GetValues.
                                     process(vm, this, queryFields).values;
        } catch (JDWPException exc) {
            throw exc.toJDIException();
        }

        if (size != values.length) {
            throw new InternalException(
                         "Wrong number of values returned from target VM");
        }
        for (int i=0; i<size; i++) {
            FieldImpl field = (FieldImpl)theFields.get(i);
            map.put(field, values[i]);
        }

        return map;
    }

    public ClassObjectReference classObject() {
        if (classObject == null) {
            // Are classObjects unique for an Object, or
            // created each time? Is this spec'ed?
            synchronized(this) {
                if (classObject == null) {
                    try {
                        classObject = JDWP.ReferenceType.ClassObject.
                            process(vm, this).classObject;
                    } catch (JDWPException exc) {
                        throw exc.toJDIException();
                    }
                }
            }
        }
        return classObject;
    }

    SDE.Stratum stratum(String stratumID) {
        SDE sde = sourceDebugExtensionInfo();
        if (!sde.isValid()) {
            sde = NO_SDE_INFO_MARK;
        }
        return sde.stratum(stratumID);
    }

    CompletableFuture<SDE.Stratum> stratumAsync(String stratumID) {
        return sourceDebugExtensionInfoAsync().thenApply(sde -> {
            if (!sde.isValid()) {
                sde = NO_SDE_INFO_MARK;
            }
            return sde.stratum(stratumID);
        });
    }

    public String sourceName() throws AbsentInformationException {
        return sourceNames(vm.getDefaultStratum()).get(0);
    }

    @SuppressWarnings("unused")
    public CompletableFuture<String> sourceNameAsync() {
        return sourceNamesAsync(vm.getDefaultStratum()).thenApply(strings -> strings.get(0));
    }

    public List<String> sourceNames(String stratumID)
            throws AbsentInformationException {
        SDE.Stratum stratum = stratum(stratumID);
        if (stratum.isJava()) {
            return List.of(baseSourceName());
        }
        return stratum.sourceNames(this);
    }

    public CompletableFuture<List<String>> sourceNamesAsync(String stratumID) {
        return stratumAsync(stratumID).thenCompose(stratum -> {
            if (stratum.isJava()) {
                return baseSourceNameAsync().thenApply(List::of);
            }
            return CompletableFuture.completedFuture(stratum.sourceNames(this));
        });
    }

    public List<String> sourcePaths(String stratumID)
            throws AbsentInformationException {
        SDE.Stratum stratum = stratum(stratumID);
        if (stratum.isJava()) {
            return List.of(baseSourceDir() + baseSourceName());
        }
        return stratum.sourcePaths(this);
    }

    String baseSourceName() throws AbsentInformationException {
        String bsn = baseSourceName;
        if (bsn == null) {
            // Does not need synchronization, since worst-case
            // static info is fetched twice
            try {
                bsn = JDWP.ReferenceType.SourceFile.
                    process(vm, this).sourceFile;
            } catch (JDWPException exc) {
                if (exc.errorCode() == JDWP.Error.ABSENT_INFORMATION) {
                    bsn = ABSENT_BASE_SOURCE_NAME;
                } else {
                    throw exc.toJDIException();
                }
            }
            baseSourceName = bsn;
        }
        if (bsn == ABSENT_BASE_SOURCE_NAME) {
            throw new AbsentInformationException();
        }
        return bsn;
    }

    CompletableFuture<String> baseSourceNameAsync() {
        String bsn = baseSourceName;
        if (bsn == null) {
            // Does not need synchronization, since worst-case
            // static info is fetched twice
            return JDWP.ReferenceType.SourceFile.processAsync(vm, this)
                    .exceptionally(throwable -> {
                        if (JDWPException.isOfType(throwable, JDWP.Error.ABSENT_INFORMATION)) {
                            baseSourceName = ABSENT_BASE_SOURCE_NAME;
                            throw new CompletionException(new AbsentInformationException());
                        }
                        throw (RuntimeException) throwable;
                    })
                    .thenApply(s -> {
                        baseSourceName = s.sourceFile;
                        return s.sourceFile;
                    });
        }
        if (bsn == ABSENT_BASE_SOURCE_NAME) {
            return CompletableFuture.failedFuture(new AbsentInformationException());
        }
        return CompletableFuture.completedFuture(bsn);
    }

    String baseSourcePath() throws AbsentInformationException {
        String bsp = baseSourcePath;
        if (bsp == null) {
            bsp = baseSourceDir() + baseSourceName();
            baseSourcePath = bsp;
        }
        return bsp;
    }

    String baseSourceDir() {
        if (baseSourceDir == null) {
            String typeName = name();
            StringBuilder sb = new StringBuilder(typeName.length() + 10);
            int index = 0;
            int nextIndex;

            while ((nextIndex = typeName.indexOf('.', index)) > 0) {
                sb.append(typeName, index, nextIndex);
                sb.append(java.io.File.separatorChar);
                index = nextIndex + 1;
            }
            baseSourceDir = sb.toString();
        }
        return baseSourceDir;
    }

    public String sourceDebugExtension()
                           throws AbsentInformationException {
        if (!vm.canGetSourceDebugExtension()) {
            throw new UnsupportedOperationException();
        }
        SDE sde = sourceDebugExtensionInfo();
        if (sde == NO_SDE_INFO_MARK) {
            throw new AbsentInformationException();
        }
        return sde.sourceDebugExtension;
    }

    private SDE sourceDebugExtensionInfo() {
        if (!vm.canGetSourceDebugExtension()) {
            return NO_SDE_INFO_MARK;
        }
        SDE sde = dereference(sdeRef);
        if (sde == null) {
            String extension = null;
            try {
                extension = JDWP.ReferenceType.SourceDebugExtension.
                    process(vm, this).extension;
            } catch (JDWPException exc) {
                if (exc.errorCode() != JDWP.Error.ABSENT_INFORMATION) {
                    sdeRef = new SoftReference<>(NO_SDE_INFO_MARK);
                    throw exc.toJDIException();
                }
            }
            if (extension == null) {
                sde = NO_SDE_INFO_MARK;
            } else {
                sde = new SDE(extension);
            }
            sdeRef = new SoftReference<>(sde);
        }
        return sde;
    }

    private CompletableFuture<SDE> sourceDebugExtensionInfoAsync() {
        if (!vm.canGetSourceDebugExtension()) {
            return CompletableFuture.completedFuture(NO_SDE_INFO_MARK);
        }
        SDE sde = dereference(sdeRef);
        if (sde != null) {
            return CompletableFuture.completedFuture(sde);
        }
        return JDWP.ReferenceType.SourceDebugExtension.processAsync(vm, this)
                .exceptionally(throwable -> {
                    if (!JDWPException.isOfType(throwable, JDWP.Error.ABSENT_INFORMATION)) {
                        throw (RuntimeException) throwable;
                    }
                    return null;
                })
                .thenApply(e -> {
                    SDE res = (e == null) ? NO_SDE_INFO_MARK : new SDE(e.extension);
                    sdeRef = new SoftReference<>(res);
                    return res;
                });
    }

    public List<String> availableStrata() {
        SDE sde = sourceDebugExtensionInfo();
        return sde.isValid() ? sde.availableStrata() : List.of(SDE.BASE_STRATUM_NAME);
    }

    public CompletableFuture<List<String>> availableStrataAsync() {
        return sourceDebugExtensionInfoAsync()
                .thenApply(sde -> sde.isValid() ? sde.availableStrata() : List.of(SDE.BASE_STRATUM_NAME));
    }

    /**
     * Always returns non-null stratumID
     */
    public String defaultStratum() {
        SDE sdei = sourceDebugExtensionInfo();
        if (sdei.isValid()) {
            return sdei.defaultStratumId;
        } else {
            return SDE.BASE_STRATUM_NAME;
        }
    }

    public int modifiers() {
        if (modifiers == -1)
            getModifiers();

        return modifiers;
    }

    public List<Location> allLineLocations()
                            throws AbsentInformationException {
        return allLineLocations(vm.getDefaultStratum(), null);
    }

    @SuppressWarnings("unused")
    public CompletableFuture<List<Location>> allLineLocationsAsync() {
        return allLineLocationsAsync(vm.getDefaultStratum(), null);
    }

    public List<Location> allLineLocations(String stratumID, String sourceName)
                            throws AbsentInformationException {
        boolean someAbsent = false; // A method that should have info, didn't
        SDE.Stratum stratum = stratum(stratumID);
        List<Location> list = new ArrayList<>();  // location list

        for (Method value : methods()) {
            MethodImpl method = (MethodImpl) value;
            try {
                list.addAll(
                        method.allLineLocations(stratum, sourceName));
            } catch (AbsentInformationException exc) {
                someAbsent = true;
            }
        }

        // If we retrieved no line info, and at least one of the methods
        // should have had some (as determined by an
        // AbsentInformationException being thrown) then we rethrow
        // the AbsentInformationException.
        if (someAbsent && list.size() == 0) {
            throw new AbsentInformationException();
        }
        return list;
    }

    public CompletableFuture<List<Location>> allLineLocationsAsync(String stratumID, String sourceName) {
        return methodsAsync().thenCombine(stratumAsync(stratumID),
                        (methods, stratum) ->
                                methods.stream()
                                        .map(method -> ((MethodImpl) method).allLineLocationsAsync(stratum, sourceName))
                                        .collect(Collectors.toList()))
                .thenCompose(futures ->
                        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                                .handle((__, ___) -> {
                                    // A method that should have info, didn't
                                    boolean someAbsent = false;

                                    List<Location> list = new ArrayList<>();
                                    for (CompletableFuture<List<Location>> future : futures) {
                                        try {
                                            list.addAll(future.join());
                                        } catch (Exception e) {
                                            someAbsent = true;
                                        }
                                    }

                                    if (someAbsent && list.size() == 0) {
                                        throw new CompletionException(new AbsentInformationException());
                                    }

                                    return list;
                                }));
    }

    public List<Location> locationsOfLine(int lineNumber)
                           throws AbsentInformationException {
        return locationsOfLine(vm.getDefaultStratum(),
                               null,
                               lineNumber);
    }

    @SuppressWarnings("unused")
    public CompletableFuture<List<Location>> locationsOfLineAsync(int lineNumber) {
        return locationsOfLineAsync(vm.getDefaultStratum(),
                null,
                lineNumber);
    }

    public List<Location> locationsOfLine(String stratumID,
                                String sourceName,
                                int lineNumber)
                           throws AbsentInformationException {
        // A method that should have info, didn't
        boolean someAbsent = false;
        // A method that should have info, did
        boolean somePresent = false;
        List<Method> methods = methods();
        SDE.Stratum stratum = stratum(stratumID);

        List<Location> list = new ArrayList<>();

        for (Method value : methods) {
            MethodImpl method = (MethodImpl) value;
            // eliminate native and abstract to eliminate
            // false positives
            if (!method.isAbstract() &&
                    !method.isNative()) {
                try {
                    list.addAll(
                            method.locationsOfLine(stratum,
                                    sourceName,
                                    lineNumber));
                    somePresent = true;
                } catch (AbsentInformationException exc) {
                    someAbsent = true;
                }
            }
        }
        if (someAbsent && !somePresent) {
            throw new AbsentInformationException();
        }
        return list;
    }

    public CompletableFuture<List<Location>> locationsOfLineAsync(String stratumID,
                                                                  String sourceName,
                                                                  int lineNumber) {
        return methodsAsync().thenCombine(stratumAsync(stratumID),
                        (methods, stratum) ->
                                methods.stream()
                                        .filter(method -> !method.isAbstract() && !method.isNative())
                                        // eliminate native and abstract to eliminate false positives
                                        .map(method -> ((MethodImpl) method).locationsOfLineAsync(stratum, sourceName, lineNumber))
                                        .collect(Collectors.toList()))
                .thenCompose(futures ->
                        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                                .handle((__, ___) -> {
                                    // A method that should have info, didn't
                                    boolean someAbsent = false;
                                    // A method that should have info, did
                                    boolean somePresent = false;

                                    List<Location> list = new ArrayList<>();
                                    for (CompletableFuture<List<Location>> future : futures) {
                                        try {
                                            list.addAll(future.join());
                                            somePresent = true;
                                        } catch (Exception e) {
                                            someAbsent = true;
                                        }
                                    }

                                    if (someAbsent && !somePresent) {
                                        throw new CompletionException(new AbsentInformationException());
                                    }

                                    return list;
                                }));
    }

    public List<ObjectReference> instances(long maxInstances) {
        if (!vm.canGetInstanceInfo()) {
            throw new UnsupportedOperationException(
                "target does not support getting instances");
        }

        if (maxInstances < 0) {
            throw new IllegalArgumentException("maxInstances is less than zero: "
                                              + maxInstances);
        }
        int intMax = (maxInstances > Integer.MAX_VALUE)?
            Integer.MAX_VALUE: (int)maxInstances;
        // JDWP can't currently handle more than this (in mustang)

        try {
            return Arrays.asList(
                    JDWP.ReferenceType.Instances.
                            process(vm, this, intMax).instances);
        } catch (JDWPException exc) {
            throw exc.toJDIException();
        }
    }

    private void getClassFileVersion() {
        if (!vm.canGetClassFileVersion()) {
            throw new UnsupportedOperationException();
        }
        JDWP.ReferenceType.ClassFileVersion classFileVersion;
        if (versionNumberGotten) {
        } else {
            try {
                classFileVersion = JDWP.ReferenceType.ClassFileVersion.process(vm, this);
            } catch (JDWPException exc) {
                if (exc.errorCode() == JDWP.Error.ABSENT_INFORMATION) {
                    majorVersion = 0;
                    minorVersion = 0;
                    versionNumberGotten = true;
                    return;
                } else {
                    throw exc.toJDIException();
                }
            }
            majorVersion = classFileVersion.majorVersion;
            minorVersion = classFileVersion.minorVersion;
            versionNumberGotten = true;
        }
    }

    public int majorVersion() {
        try {
            getClassFileVersion();
        } catch (RuntimeException exc) {
            throw exc;
        }
        return majorVersion;
    }

    public int minorVersion() {
        try {
            getClassFileVersion();
        } catch (RuntimeException exc) {
            throw exc;
        }
        return minorVersion;
    }

    private byte[] getConstantPoolInfo() {
        JDWP.ReferenceType.ConstantPool jdwpCPool;
        if (!vm.canGetConstantPool()) {
            throw new UnsupportedOperationException();
        }
        if (constantPoolInfoGotten) {
            if (constantPoolBytesRef == null) {
                return null;
            }
            byte[] cpbytes = constantPoolBytesRef.get();
            if (cpbytes != null) {
                return cpbytes;
            }
        }

        try {
            jdwpCPool = JDWP.ReferenceType.ConstantPool.process(vm, this);
        } catch (JDWPException exc) {
            if (exc.errorCode() == JDWP.Error.ABSENT_INFORMATION) {
                constanPoolCount = 0;
                constantPoolBytesRef = null;
                constantPoolInfoGotten = true;
                return null;
            } else {
                throw exc.toJDIException();
            }
        }
        byte[] cpbytes;
        constanPoolCount = jdwpCPool.count;
        cpbytes = jdwpCPool.bytes;
        constantPoolBytesRef = new SoftReference<>(cpbytes);
        constantPoolInfoGotten = true;
        return cpbytes;
    }

    private CompletableFuture<byte[]> getConstantPoolInfoAsync() {
        if (!vm.canGetConstantPool()) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }
        if (constantPoolInfoGotten) {
            if (constantPoolBytesRef == null) {
                return CompletableFuture.completedFuture(null);
            }
            byte[] cpbytes = constantPoolBytesRef.get();
            if (cpbytes != null) {
                return CompletableFuture.completedFuture(cpbytes);
            }
        }

        return JDWP.ReferenceType.ConstantPool.processAsync(vm, this).handle((jdwpCPool, e) -> {
            if (e != null) {
                if (JDWPException.isOfType(e, JDWP.Error.ABSENT_INFORMATION)) {
                    constanPoolCount = 0;
                    constantPoolBytesRef = null;
                    constantPoolInfoGotten = true;
                    return null;
                }
                throw (RuntimeException)e;
            }
            constanPoolCount = jdwpCPool.count;
            byte[] cpbytes = jdwpCPool.bytes;
            constantPoolBytesRef = new SoftReference<>(cpbytes);
            constantPoolInfoGotten = true;
            return cpbytes;
        });
    }

    public int constantPoolCount() {
        try {
            getConstantPoolInfo();
        } catch (RuntimeException exc) {
            throw exc;
        }
        return constanPoolCount;
    }

    @SuppressWarnings("unused")
    public CompletableFuture<Integer> constantPoolCountAsync() {
        return getConstantPoolInfoAsync().thenApply((bytes -> constanPoolCount));
    }

    public byte[] constantPool() {
        byte[] cpbytes;
        try {
            cpbytes = getConstantPoolInfo();
        } catch (RuntimeException exc) {
            throw exc;
        }
        if (cpbytes != null) {
            /*
             * Arrays are always modifiable, so it is a little unsafe
             * to return the cached bytecodes directly; instead, we
             * make a clone at the cost of using more memory.
             */
            return cpbytes.clone();
        } else {
            return null;
        }
    }

    @SuppressWarnings("unused")
    public CompletableFuture<byte[]> constantPoolAsync() {
        return getConstantPoolInfoAsync().thenApply(bytes -> {
            if (bytes != null) {
                /*
                 * Arrays are always modifiable, so it is a little unsafe
                 * to return the cached bytecodes directly; instead, we
                 * make a clone at the cost of using more memory.
                 */
                return bytes.clone();
            } else {
                return null;
            }
        });
    }

    // Does not need synchronization, since worst-case
    // static info is fetched twice
    void getModifiers() {
        if (modifiers != -1) {
            return;
        }
        try {
            modifiers = JDWP.ReferenceType.Modifiers.
                                  process(vm, this).modBits;
        } catch (JDWPException exc) {
            throw exc.toJDIException();
        }
    }

    void decodeStatus(int status) {
        this.status = status;
        if ((status & JDWP.ClassStatus.PREPARED) != 0) {
            isPrepared = true;
        }
    }

    void updateStatus() {
        try {
            decodeStatus(JDWP.ReferenceType.Status.process(vm, this).status);
        } catch (JDWPException exc) {
            throw exc.toJDIException();
        }
    }

    void markPrepared() {
        isPrepared = true;
    }

    long ref() {
        return ref;
    }

    int indexOf(Method method) {
        // Make sure they're all here - the obsolete method
        // won't be found and so will have index -1
        return methods().indexOf(method);
    }

    int indexOf(Field field) {
        // Make sure they're all here
        return fields().indexOf(field);
    }

    /*
     * Return true if an instance of this type
     * can be assigned to a variable of the given type
     */
    abstract boolean isAssignableTo(ReferenceType type);

    boolean isAssignableFrom(ReferenceType type) {
        return ((ReferenceTypeImpl)type).isAssignableTo(this);
    }

    boolean isAssignableFrom(ObjectReference object) {
        return object == null ||
               isAssignableFrom(object.referenceType());
    }

    void setStatus(int status) {
        decodeStatus(status);
    }

    void setSignature(String signature) {
        if (!Objects.equals(this.signature, signature)) {
            vm.cacheTypeBySignature(this, signature);
        }
        this.signature = signature;
    }

    private static boolean isOneDimensionalPrimitiveArray(String signature) {
        JNITypeParser sig = new JNITypeParser(signature);
        if (sig.isArray()) {
            JNITypeParser componentSig = new JNITypeParser(sig.componentSignature());
            return componentSig.isPrimitive();
        }
        return false;
    }

    Type findType(String signature) throws ClassNotLoadedException {
        Type type;
        JNITypeParser sig = new JNITypeParser(signature);
        if (sig.isVoid()) {
            type = vm.theVoidType();
        } else if (sig.isPrimitive()) {
            type = vm.primitiveTypeMirror(sig.jdwpTag());
        } else {
            // Must be a reference type.
            ClassLoaderReferenceImpl loader =
                    (ClassLoaderReferenceImpl) classLoader();
            if ((loader == null) ||
                    (isOneDimensionalPrimitiveArray(signature)) //Work around 4450091
            ) {
                // Caller wants type of boot class field
                type = vm.findBootType(signature);
            } else {
                // Caller wants type of non-boot class field
                type = loader.findType(signature);
            }
        }
        return type;
    }

    String loaderString() {
        if (classLoader() != null) {
            return "loaded by " + classLoader().toString();
        } else {
            return "no class loader";
        }
    }

    static <T> T notnullize(T value, T defaultValue) {
        return value != null ? value : defaultValue;
    }

    static <T> T nullize(T value, T defaultValue) {
        return value != defaultValue ? value : null;
    }

    static <T> T dereference(Reference<T> ref) {
        return ref == null ? null : ref.get();
    }
}
