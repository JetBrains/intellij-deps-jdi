/*
 * Copyright (c) 1998, 2022, Oracle and/or its affiliates. All rights reserved.
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class ClassTypeImpl extends InvokableTypeImpl
                                 implements ClassType
{
    private static class IResult implements InvocationResult {
        private final JDWP.ClassType.InvokeMethod rslt;

        public IResult(JDWP.ClassType.InvokeMethod rslt) {
            this.rslt = rslt;
        }

        @Override
        public ObjectReferenceImpl getException() {
            return rslt.exception;
        }

        @Override
        public ValueImpl getResult() {
            return rslt.returnValue;
        }
    }

    private volatile ClassType superclass = null;
    private volatile InterfaceType[] interfaces = null;

    protected ClassTypeImpl(VirtualMachine aVm, long aRef) {
        super(aVm, aRef);
    }

    // marker object
    private static final ClassTypeImpl NULL = new ClassTypeImpl(null, -1);

    public ClassType superclass() {
        if (superclass == null) {
            ClassTypeImpl sup;
            try {
                sup = JDWP.ClassType.Superclass.
                    process(vm, this).superclass;
            } catch (JDWPException exc) {
                throw exc.toJDIException();
            }

            /*
             * If there is a superclass, cache its
             * ClassType here.
             */
            superclass = notnullize(sup, NULL);
            return sup;
        }

        return nullize(superclass, NULL);
    }

    public CompletableFuture<ClassType> superclassAsync() {
        if (superclass != null) {
            return CompletableFuture.completedFuture(nullize(superclass, NULL));
        }
        return JDWP.ClassType.Superclass.processAsync(vm, this).thenApply(s -> {
            ClassTypeImpl sup = s.superclass;
            superclass = notnullize(sup, NULL);
            return sup;
        });
    }

    @Override
    public List<InterfaceType> interfaces()  {
        if (interfaces == null) {
            interfaces = getInterfaces();
        }
        return unmodifiableList(interfaces);
    }

    public CompletableFuture<List<InterfaceType>> interfacesAsync()  {
        if (interfaces != null) {
            return CompletableFuture.completedFuture(unmodifiableList(interfaces));
        }
        return getInterfacesAsync().thenApply(r -> {
            interfaces = r;
            return unmodifiableList(r);
        });
    }

    @Override
    public List<InterfaceType> allInterfaces() {
        return getAllInterfaces();
    }

    public List<ClassType> subclasses() {
        List<ClassType> subs = new ArrayList<>();
        vm.forEachClass(refType -> {
            if (refType instanceof ClassType) {
                ClassType clazz = (ClassType)refType;
                ClassType superclass = clazz.superclass();
                if ((superclass != null) && superclass.equals(this)) {
                    subs.add(clazz);
                }
            }
        });
        return subs;
    }

    public boolean isEnum() {
        ClassType superclass = superclass();
        return superclass != null && superclass.name().equals("java.lang.Enum");
    }

    public void setValue(Field field, Value value)
        throws InvalidTypeException, ClassNotLoadedException {

        validateMirror(field);
        validateMirrorOrNull(value);
        validateFieldSet(field);

        // More validation specific to setting from a ClassType
        if(!field.isStatic()) {
            throw new IllegalArgumentException(
                            "Must set non-static field through an instance");
        }

        try {
            JDWP.ClassType.SetValues.FieldValue[] values =
                          new JDWP.ClassType.SetValues.FieldValue[1];
            values[0] = new JDWP.ClassType.SetValues.FieldValue(
                    ((FieldImpl)field).ref(),
                    // validate and convert if necessary
                    ValueImpl.prepareForAssignment(value, (FieldImpl)field));

            try {
                JDWP.ClassType.SetValues.process(vm, this, values);
            } catch (JDWPException exc) {
                throw exc.toJDIException();
            }
        } catch (ClassNotLoadedException e) {
            /*
             * Since we got this exception,
             * the field type must be a reference type. The value
             * we're trying to set is null, but if the field's
             * class has not yet been loaded through the enclosing
             * class loader, then setting to null is essentially a
             * no-op, and we should allow it without an exception.
             */
            if (value != null) {
                throw e;
            }
        }
    }

    PacketStream sendNewInstanceCommand(final ThreadReferenceImpl thread,
                                        final MethodImpl method,
                                        final ValueImpl[] args,
                                        final int options) {
        CommandSender sender = () -> JDWP.ClassType.NewInstance.enqueueCommand(
                                      vm, ClassTypeImpl.this, thread,
                                      method.ref(), args, options);

        PacketStream stream;
        if ((options & INVOKE_SINGLE_THREADED) != 0) {
            stream = thread.sendResumingCommand(sender);
        } else {
            stream = vm.sendResumingCommand(sender);
        }
        return stream;
    }

    public ObjectReference newInstance(ThreadReference threadIntf,
                                       Method methodIntf,
                                       List<? extends Value> origArguments,
                                       int options)
                                   throws InvalidTypeException,
                                          ClassNotLoadedException,
                                          IncompatibleThreadStateException,
                                          InvocationException {
        validateMirror(threadIntf);
        validateMirror(methodIntf);
        validateMirrorsOrNulls(origArguments);

        MethodImpl method = (MethodImpl)methodIntf;
        ThreadReferenceImpl thread = (ThreadReferenceImpl)threadIntf;

        validateConstructorInvocation(method);

        List<Value> arguments = method.validateAndPrepareArgumentsForInvoke(origArguments, options);
        ValueImpl[] args = arguments.toArray(new ValueImpl[0]);
        JDWP.ClassType.NewInstance ret;
        try {
            PacketStream stream =
                sendNewInstanceCommand(thread, method, args, options);
            ret = JDWP.ClassType.NewInstance.waitForReply(vm, stream);
        } catch (JDWPException exc) {
            if (exc.errorCode() == JDWP.Error.INVALID_THREAD) {
                throw new IncompatibleThreadStateException();
            } else {
                throw exc.toJDIException();
            }
        }

        /*
         * There is an implicit VM-wide suspend at the conclusion
         * of a normal (non-single-threaded) method invoke
         */
        if ((options & INVOKE_SINGLE_THREADED) == 0) {
            vm.notifySuspend();
        }

        if (ret.exception != null) {
            throw new InvocationException(ret.exception);
        } else {
            ObjectReferenceImpl newObject = ret.newObject;
            newObject.setType(this);
            return newObject;
        }
    }

    public Method concreteMethodByName(String name, String signature)  {
        Method method = null;
        for (Method candidate : visibleMethods()) {
            if (candidate.name().equals(name) &&
                candidate.signature().equals(signature) &&
                !candidate.isAbstract()) {

                method = candidate;
                break;
            }
        }
        return method;
    }

    void validateConstructorInvocation(Method method) {
        /*
         * Method must be in this class.
         */
        ReferenceTypeImpl declType = (ReferenceTypeImpl)method.declaringType();
        if (!declType.equals(this)) {
            throw new IllegalArgumentException("Invalid constructor");
        }

        /*
         * Method must be a constructor
         */
        if (!method.isConstructor()) {
            throw new IllegalArgumentException("Cannot create instance with non-constructor");
        }
    }

    public String toString() {
       return "class " + name() + " (" + loaderString() + ")";
    }

    @Override
    CommandSender getInvokeMethodSender(ThreadReferenceImpl thread,
                                        MethodImpl method,
                                        ValueImpl[] args,
                                        int options) {
        return () ->
            JDWP.ClassType.InvokeMethod.enqueueCommand(vm,
                                                       ClassTypeImpl.this,
                                                       thread,
                                                       method.ref(),
                                                       args,
                                                       options);
    }

    @Override
    InvocationResult waitForReply(PacketStream stream) throws JDWPException {
        return new IResult(JDWP.ClassType.InvokeMethod.waitForReply(vm, stream));
    }

    @Override
    CompletableFuture<InvocationResult> readReply(PacketStream stream) {
        return stream.readReply(packet -> new JDWP.ClassType.InvokeMethod(vm, stream)).thenApply(IResult::new);
    }

    @Override
    boolean canInvoke(Method method) {
        // Method must be in this class or a superclass.
        return ((ReferenceTypeImpl)method.declaringType()).isAssignableFrom(this);
    }
}
