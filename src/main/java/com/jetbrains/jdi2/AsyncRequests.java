package com.jetbrains.jdi2;

import com.jetbrains.jdi.*;
import com.sun.jdi.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public final class AsyncRequests {

    // start ==================== ArrayReferenceImpl ====================

    public static CompletableFuture<Integer> lengthAsync(ArrayReferenceImpl arrayReference) {
        if (arrayReference.length != -1) {
            return CompletableFuture.completedFuture(arrayReference.length);
        }
        VirtualMachineImpl vm = (VirtualMachineImpl) arrayReference.virtualMachine();
        return JDWP.ArrayReference.Length.processAsync(vm, arrayReference).thenApply(r -> {
            arrayReference.length = r.arrayLength;
            return r.arrayLength;
        });
    }

    public static CompletableFuture<List<Value>> getValuesAsync(ArrayReferenceImpl arrayReference, int index, int len) {
        return lengthAsync(arrayReference).thenCompose(__ -> { // preload length
            int length = len;
            if (length == -1) { // -1 means the rest of the array
                length = arrayReference.length() - index;
            }
            arrayReference.validateArrayAccess(index, length);
            if (length == 0) {
                return CompletableFuture.completedFuture(Collections.emptyList());
            }

            VirtualMachineImpl vm = (VirtualMachineImpl) arrayReference.virtualMachine();
            return JDWP.ArrayReference.GetValues.processAsync(vm, arrayReference, index, length)
                    .thenApply(r -> ArrayReferenceImpl.cast(r.values));
        });
    }

    public static CompletableFuture<Value> getValueAsync(ArrayReferenceImpl arrayReference, int index) {
        return getValuesAsync(arrayReference, index, 1).thenApply(r -> r.get(0));
    }

    public static CompletableFuture<List<Value>> getValuesAsync(ArrayReferenceImpl arrayReference) {
        return getValuesAsync(arrayReference, 0, -1);
    }

    // end ==================== ArrayReferenceImpl ====================

    // start ==================== ArrayTypeImpl ====================

    public static CompletableFuture<ArrayReference> newInstanceAsync(ArrayTypeImpl arrayType, int length) {
        VirtualMachineImpl vm = (VirtualMachineImpl) arrayType.virtualMachine();
        return JDWP.ArrayType.NewInstance.processAsync(vm, arrayType, length).thenApply(a -> {
            ArrayReferenceImpl res = (ArrayReferenceImpl) a.newArray;
            res.length = length;
            res.setType(arrayType);
            return res;
        });
    }

    // end ==================== ArrayTypeImpl ====================

    // start ==================== ReferenceTypeImpl ====================

    public static CompletableFuture<List<Method>> methodsAsync(ReferenceTypeImpl referenceType) {
        Method[] methods = referenceType.getFromCache(referenceType.methodsRef);
        if (methods != null) {
            return CompletableFuture.completedFuture(ReferenceTypeImpl.unmodifiableList(methods));
        }
        VirtualMachineImpl vm = (VirtualMachineImpl) referenceType.virtualMachine();
        CompletableFuture<Method[]> array;
        if (!vm.canGet1_5LanguageFeatures()) {
            array = JDWP.ReferenceType.Methods.processAsync(vm, referenceType)
                    .thenApply(m -> referenceType.readMethods(m.declared));
        } else {
            array = JDWP.ReferenceType.MethodsWithGeneric.processAsync(vm, referenceType)
                    .thenApply(m -> referenceType.readMethodsWithGeneric(m.declared));
        }
        return array.thenApply(res -> {
            res = referenceType.tryToCache(referenceType.methodsRef, res, n -> referenceType.methodsRef = n);
            return ReferenceTypeImpl.unmodifiableList(res);
        });
    }

    public static CompletableFuture<List<Method>> addAllMethodsAsync(ReferenceTypeImpl referenceType, List<Method> methodList, Set<ReferenceType> typeSet) {
        return collectRecursively(referenceType, AsyncRequests::methodsAsync, methodList, typeSet);
    }

    public static <T> CompletableFuture<List<T>> collectRecursively(ReferenceTypeImpl referenceType, Function<ReferenceTypeImpl, CompletableFuture<List<T>>> func, List<T> list, Set<ReferenceType> typeSet) {
        if (!typeSet.contains(referenceType)) {
            typeSet.add(referenceType);

            return func.apply(referenceType)
                    .thenAccept(list::addAll)
                    .thenCompose(__ -> referenceType.inheritedTypesAsync())
                    .thenCompose(types -> {
                        CompletableFuture<List<T>> res = CompletableFuture.completedFuture(list);
                        for (ReferenceType type : types) {
                            res = res.thenCombine(collectRecursively(((ReferenceTypeImpl) type), func, list, typeSet),
                                    (f, f2) -> list);
                        }
                        return res;
                    });
        }
        return CompletableFuture.completedFuture(list);
    }

    public static CompletableFuture<List<Field>> fieldsAsync(ReferenceTypeImpl referenceType) {
        Field[] fields = referenceType.getFromCache(referenceType.fieldsRef);
        if (fields != null) {
            return CompletableFuture.completedFuture(ReferenceTypeImpl.unmodifiableList(fields));
        }
        VirtualMachineImpl vm = (VirtualMachineImpl) referenceType.virtualMachine();
        CompletableFuture<Field[]> array;
        if (vm.canGet1_5LanguageFeatures()) {
            array = JDWP.ReferenceType.FieldsWithGeneric.processAsync(vm, referenceType)
                    .thenApply(r -> referenceType.readFieldsWithGenerics(r.declared));
        } else {
            array = JDWP.ReferenceType.Fields.processAsync(vm, referenceType)
                    .thenApply(r -> referenceType.readFields(r.declared));
        }
        return array.thenApply(res -> {
            res = referenceType.tryToCache(referenceType.fieldsRef, res, v -> referenceType.fieldsRef = v);
            return ReferenceTypeImpl.unmodifiableList(res);
        });
    }

    public static CompletableFuture<Method> getMethodMirrorAsync(ReferenceTypeImpl referenceType, long ref) {
        if (ref == 0) {
            // obsolete method
            return CompletableFuture.completedFuture(new ObsoleteMethodImpl(referenceType.virtualMachine(), referenceType));
        }
        return methodsAsync(referenceType).thenApply(methods -> {
            for (Method value : methods) {
                MethodImpl method = (MethodImpl) value;
                if (method.ref() == ref) {
                    return method;
                }
            }
            throw new IllegalArgumentException("Invalid method id: " + ref);
        });
    }

    static CompletableFuture<List<Field>> addAllFieldsAsync(ReferenceTypeImpl referenceType, List<Field> fieldList, Set<ReferenceType> typeSet) {
        return collectRecursively(referenceType, AsyncRequests::fieldsAsync, fieldList, typeSet);
    }

    public static CompletableFuture<List<Field>> allFieldsAsync(ReferenceTypeImpl referenceType) {
        //TODO: may improve further, but need to preserve the order
        List<Field> fieldList = Collections.synchronizedList(new ArrayList<>());
        Set<ReferenceType> typeSet = Collections.synchronizedSet(new HashSet<>());
        return addAllFieldsAsync(referenceType, fieldList, typeSet);
    }

    public static CompletableFuture<List<Method>> allMethodsAsync(ReferenceTypeImpl referenceType) {
        List<Method> methodList = Collections.synchronizedList(new ArrayList<>());
        Set<ReferenceType> typeSet = Collections.synchronizedSet(new HashSet<>());
        return addAllMethodsAsync(referenceType, methodList, typeSet);
    }

    public static CompletableFuture<List<Location>> allLineLocationsAsync(ReferenceTypeImpl referenceType, String stratumID, String sourceName) {
        return methodsAsync(referenceType).thenCombine(stratumAsync(referenceType, stratumID),
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

    public static CompletableFuture<List<Location>> locationsOfLineAsync(ReferenceTypeImpl referenceType, int lineNumber) {
        return locationsOfLineAsync(referenceType, referenceType.virtualMachine().getDefaultStratum(),
                null,
                lineNumber);
    }

    public static CompletableFuture<List<Location>> locationsOfLineAsync(ReferenceTypeImpl referenceType, String stratumID,
                                                                         String sourceName,
                                                                         int lineNumber) {
        return methodsAsync(referenceType).thenCombine(stratumAsync(referenceType, stratumID),
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

    public static CompletableFuture<List<Location>> allLineLocationsAsync(ReferenceTypeImpl referenceType) {
        return allLineLocationsAsync(referenceType, referenceType.virtualMachine().getDefaultStratum(), null);
    }

    public static CompletableFuture<SDE.Stratum> stratumAsync(ReferenceTypeImpl referenceType, String stratumID) {
        return sourceDebugExtensionInfoAsync(referenceType).thenApply(sde -> {
            if (!sde.isValid()) {
                sde = ReferenceTypeImpl.NO_SDE_INFO_MARK;
            }
            return sde.stratum(stratumID);
        });
    }

    public static CompletableFuture<Value> getValueAsync(ReferenceTypeImpl referenceType, Field sig) {
        return getValuesAsync(referenceType, Collections.singletonList(sig)).thenApply(m -> m.get(sig));
    }

    public static CompletableFuture<Map<Field, Value>> getValuesAsync(ReferenceTypeImpl referenceType, List<? extends Field> theFields) {
        referenceType.validateMirrors(theFields);

        int size = theFields.size();
        JDWP.ReferenceType.GetValues.Field[] queryFields = new JDWP.ReferenceType.GetValues.Field[size];

        for (int i = 0; i < size; i++) {
            FieldImpl field = (FieldImpl) theFields.get(i);

            referenceType.validateFieldAccess(field);

            // Do more validation specific to ReferenceType field getting
            if (!field.isStatic()) {
                throw new IllegalArgumentException("Attempt to use non-static field with ReferenceType");
            }
            queryFields[i] = new JDWP.ReferenceType.GetValues.Field(field.ref());
        }

        VirtualMachineImpl vm = (VirtualMachineImpl) referenceType.virtualMachine();
        return JDWP.ReferenceType.GetValues.processAsync(vm, referenceType, queryFields).thenApply(r -> {
            ValueImpl[] values = r.values;

            if (size != values.length) {
                throw new InternalException(
                        "Wrong number of values returned from target VM");
            }

            Map<Field, Value> map = new HashMap<>(size);
            for (int i = 0; i < size; i++) {
                FieldImpl field = (FieldImpl) theFields.get(i);
                map.put(field, values[i]);
            }

            return map;
        });
    }

    public static CompletableFuture<ClassObjectReference> classObjectAsync(ReferenceTypeImpl referenceType) {
        if (referenceType.classObject == null) {
            VirtualMachineImpl vm = (VirtualMachineImpl) referenceType.virtualMachine();
            return JDWP.ReferenceType.ClassObject.processAsync(vm, referenceType)
                    .thenApply(c -> {
                        referenceType.classObject = c.classObject;
                        return c.classObject;
                    });
        }
        return CompletableFuture.completedFuture(referenceType.classObject);
    }

    public static CompletableFuture<String> sourceNameAsync(ReferenceTypeImpl referenceType) {
        return sourceNamesAsync(referenceType, referenceType.virtualMachine().getDefaultStratum()).thenApply(strings -> strings.get(0));
    }

    public static CompletableFuture<List<String>> sourceNamesAsync(ReferenceTypeImpl referenceType, String stratumID) {
        return stratumAsync(referenceType, stratumID).thenCompose(stratum -> {
            if (stratum.isJava()) {
                return baseSourceNameAsync(referenceType).thenApply(List::of);
            }
            return CompletableFuture.completedFuture(stratum.sourceNames(referenceType));
        });
    }

    public static CompletableFuture<Boolean> hasMappedLineToAsync(ReferenceTypeImpl referenceType, String stratumID, int njplsLine, Predicate<String> sourcePathFilter) {
        return sourceDebugExtensionInfoAsync(referenceType).thenApply(sde -> referenceType.hasMappedLineTo(sde, stratumID, sourcePathFilter, njplsLine));
    }

    static CompletableFuture<String> baseSourceNameAsync(ReferenceTypeImpl referenceType) {
        String bsn = referenceType.baseSourceName;
        if (bsn == null) {
            // Does not need synchronization, since worst-case
            // static info is fetched twice
            VirtualMachineImpl vm = (VirtualMachineImpl) referenceType.virtualMachine();
            return JDWP.ReferenceType.SourceFile.processAsync(vm, referenceType)
                    .exceptionally(throwable -> {
                        if (JDWPException.isOfType(throwable, JDWP.Error.ABSENT_INFORMATION)) {
                            referenceType.baseSourceName = ReferenceTypeImpl.ABSENT_BASE_SOURCE_NAME;
                            throw new CompletionException(new AbsentInformationException());
                        }
                        throw (RuntimeException) throwable;
                    })
                    .thenApply(s -> {
                        referenceType.baseSourceName = s.sourceFile;
                        return s.sourceFile;
                    });
        }
        //noinspection StringEquality
        if (bsn == ReferenceTypeImpl.ABSENT_BASE_SOURCE_NAME) {
            return CompletableFuture.failedFuture(new AbsentInformationException());
        }
        return CompletableFuture.completedFuture(bsn);
    }

    private static CompletableFuture<SDE> sourceDebugExtensionInfoAsync(ReferenceTypeImpl referenceType) {
        VirtualMachineImpl vm = (VirtualMachineImpl) referenceType.virtualMachine();
        if (!vm.canGetSourceDebugExtension()) {
            return CompletableFuture.completedFuture(ReferenceTypeImpl.NO_SDE_INFO_MARK);
        }
        SDE sde = referenceType.getFromCache(referenceType.sdeRef);
        if (sde != null) {
            return CompletableFuture.completedFuture(sde);
        }
        return JDWP.ReferenceType.SourceDebugExtension.processAsync(vm, referenceType)
                .exceptionally(throwable -> {
                    if (!JDWPException.isOfType(throwable, JDWP.Error.ABSENT_INFORMATION)) {
                        throw (RuntimeException) throwable;
                    }
                    return null;
                })
                .thenApply(e -> {
                    SDE res = (e == null) ? ReferenceTypeImpl.NO_SDE_INFO_MARK : new SDE(e.extension);
                    res = referenceType.tryToCache(referenceType.sdeRef, res, v -> referenceType.sdeRef = v);
                    return res;
                });
    }

    public static CompletableFuture<List<String>> availableStrataAsync(ReferenceTypeImpl referenceType) {
        return sourceDebugExtensionInfoAsync(referenceType)
                .thenApply(sde -> sde.isValid() ? sde.availableStrata() : List.of(SDE.BASE_STRATUM_NAME));
    }

    private static CompletableFuture<byte[]> getConstantPoolInfoAsync(ReferenceTypeImpl referenceType) {
        VirtualMachineImpl vm = (VirtualMachineImpl) referenceType.virtualMachine();
        if (!vm.canGetConstantPool()) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }
        if (referenceType.constantPoolInfoGotten) {
            if (referenceType.constantPoolBytesRef == null) {
                return CompletableFuture.completedFuture(null);
            }
            byte[] cpbytes = referenceType.constantPoolBytesRef.get();
            if (cpbytes != null) {
                return CompletableFuture.completedFuture(cpbytes);
            }
        }

        return JDWP.ReferenceType.ConstantPool.processAsync(vm, referenceType).handle((jdwpCPool, e) -> {
            if (e != null) {
                if (JDWPException.isOfType(e, JDWP.Error.ABSENT_INFORMATION)) {
                    referenceType.constanPoolCount = 0;
                    referenceType.constantPoolBytesRef = null;
                    referenceType.constantPoolInfoGotten = true;
                    return null;
                }
                throw (RuntimeException) e;
            }
            referenceType.constanPoolCount = jdwpCPool.count;
            byte[] cpbytes = jdwpCPool.bytes;
            referenceType.constantPoolBytesRef = vm.createSoftReference(cpbytes);
            referenceType.constantPoolInfoGotten = true;
            return cpbytes;
        });
    }

    public static CompletableFuture<Integer> constantPoolCountAsync(ReferenceTypeImpl referenceType) {
        return getConstantPoolInfoAsync(referenceType).thenApply((bytes -> referenceType.constanPoolCount));
    }

    public static CompletableFuture<byte[]> constantPoolAsync(ReferenceTypeImpl referenceType) {
        return getConstantPoolInfoAsync(referenceType).thenApply(bytes -> {
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

    // end ==================== ReferenceTypeImpl ====================

    // start ==================== LocationImpl ====================

    public static CompletableFuture<Method> methodAsync(LocationImpl location) {
        if (location.method != null) {
            return CompletableFuture.completedFuture(location.method);
        }
        return getMethodMirrorAsync(location.declaringType, location.methodRef).thenApply(m -> location.method = m);
    }

    // end ==================== LocationImpl ====================

    // start ==================== MethodImpl ====================

    public static CompletableFuture<List<Location>> allLineLocationsAsync(MethodImpl method) {
        return allLineLocationsAsync(method, method.virtualMachine().getDefaultStratum(), null);
    }

    public static CompletableFuture<List<Location>> allLineLocationsAsync(MethodImpl method, String stratumID, String sourceName) {
        return stratumAsync(method.declaringType, stratumID).thenCompose(stratum -> method.allLineLocationsAsync(stratum, sourceName));
    }

    public static CompletableFuture<Boolean> isObsoleteAsync(MethodImpl method) {
        if (method.obsolete != null) {
            return CompletableFuture.completedFuture(method.obsolete);
        }
        VirtualMachineImpl vm = (VirtualMachineImpl) method.virtualMachine();
        return JDWP.Method.IsObsolete.processAsync(vm, method.declaringType, method.ref()).thenApply(r -> method.obsolete = r.isObsolete);
    }


}
