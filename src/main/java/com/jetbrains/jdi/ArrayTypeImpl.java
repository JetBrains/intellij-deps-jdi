/*
 * Copyright (c) 1998, 2021, Oracle and/or its affiliates. All rights reserved.
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

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class ArrayTypeImpl extends ReferenceTypeImpl
    implements ArrayType
{
    protected ArrayTypeImpl(VirtualMachine aVm, long aRef) {
        super(aVm, aRef);
    }

    public ArrayReference newInstance(int length) {
        try {
            ArrayReferenceImpl res = (ArrayReferenceImpl) JDWP.ArrayType.NewInstance.process(vm, this, length).newArray;
            res.setLength(length);
            res.setType(this);
            return res;
        } catch (JDWPException exc) {
            throw exc.toJDIException();
        }
    }

    @SuppressWarnings("unused")
    public CompletableFuture<ArrayReference> newInstanceAsync(int length) {
        return JDWP.ArrayType.NewInstance.processAsync(vm, this, length).thenApply(a -> {
            ArrayReferenceImpl res = (ArrayReferenceImpl) a.newArray;
            res.setLength(length);
            res.setType(this);
            return res;
        });
    }

    public String componentSignature() {
        JNITypeParser sig = new JNITypeParser(signature());
        return sig.componentSignature();
    }

    public String componentTypeName() {
        JNITypeParser parser = new JNITypeParser(componentSignature());
        return parser.typeName();
    }

    Type type() throws ClassNotLoadedException {
        return findType(componentSignature());
    }

    @Override
    void addVisibleMethods(Map<String, Method> map, Set<InterfaceType> seenInterfaces) {
        // arrays don't have methods
    }

    public List<Method> allMethods() {
        return new ArrayList<>(0);   // arrays don't have methods
    }

    public Type componentType() throws ClassNotLoadedException {
        return findType(componentSignature());
    }

    static boolean isComponentAssignable(Type destination, Type source) {
        if (source instanceof PrimitiveType) {
            // Assignment of primitive arrays requires identical
            // component types.
            return source.equals(destination);
        } else {
            if (destination instanceof PrimitiveType) {
                return false;
            }

            ReferenceTypeImpl refSource = (ReferenceTypeImpl)source;
            ReferenceTypeImpl refDestination = (ReferenceTypeImpl)destination;
            // Assignment of object arrays requires availability
            // of widening conversion of component types
            return refSource.isAssignableTo(refDestination);
        }
    }

    /*
     * Return true if an instance of the  given reference type
     * can be assigned to a variable of this type
     */
    boolean isAssignableTo(ReferenceType destType) {
        if (destType instanceof ArrayType) {
            try {
                Type destComponentType = ((ArrayType)destType).componentType();
                return isComponentAssignable(destComponentType, componentType());
            } catch (ClassNotLoadedException e) {
                // One or both component types has not yet been
                // loaded => can't assign
                return false;
            }
        } else if (destType instanceof InterfaceType) {
            // Valid InterfaceType assignees are Cloneable or Serializable
            return destType.name().equals("java.lang.Cloneable") || destType.name().equals("java.io.Serializable");
        } else {
            // Only valid ClassType assignee is Object
            return destType.name().equals("java.lang.Object");
        }
    }

    List<ReferenceType> inheritedTypes() {
        return Collections.emptyList();
    }

    @Override
    CompletableFuture<List<? extends ReferenceType>> inheritedTypesAsync() {
        return CompletableFuture.completedFuture(Collections.emptyList());
    }

    void getModifiers() {
        if (modifiers != -1) {
            return;
        }
        /*
         * For object arrays, the return values for Interface
         * Accessible.isPrivate(), Accessible.isProtected(),
         * etc... are the same as would be returned for the
         * component type.  Fetch the modifier bits from the
         * component type and use those.
         *
         * For primitive arrays, the modifiers are always
         *   VMModifiers.FINAL | VMModifiers.PUBLIC
         *
         * Reference com.sun.jdi.Accessible.java.
         */
        try {
            Type t = componentType();
            if (t instanceof PrimitiveType) {
                modifiers = VMModifiers.FINAL | VMModifiers.PUBLIC;
            } else {
                ReferenceType rt = (ReferenceType)t;
                modifiers = rt.modifiers();
            }
        } catch (ClassNotLoadedException cnle) {
            cnle.printStackTrace();
        }
    }

    public String toString() {
       return "array class " + name() + " (" + loaderString() + ")";
    }

    /*
     * Save a pointless trip over the wire for these methods
     * which have undefined results for arrays.
     */
    public boolean isPrepared() { return true; }
    public boolean isVerified() { return true; }
    public boolean isInitialized() { return true; }
    public boolean failedToInitialize() { return false; }
    public boolean isAbstract() { return false; }

    /*
     * Defined always to be true for arrays
     */
    public boolean isFinal() { return true; }

    /*
     * Defined always to be false for arrays
     */
    public boolean isStatic() { return false; }
}
