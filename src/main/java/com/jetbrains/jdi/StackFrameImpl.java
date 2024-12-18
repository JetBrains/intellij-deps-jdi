/*
 * Copyright (c) 1998, 2023, Oracle and/or its affiliates. All rights reserved.
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.jetbrains.jdi2.AsyncRequests;
import com.sun.jdi.*;

public class StackFrameImpl extends MirrorImpl
                            implements StackFrame, ThreadListener
{
    /* Once false, frame should not be used.
     * access synchronized on (vm.state())
     */
    private boolean isValid = true;

    private final ThreadReferenceImpl thread;
    private final long id;
    private final Location location;
    private volatile Map<String, LocalVariable> visibleVariables = null;
    private ObjectReference thisObject = null;

    StackFrameImpl(VirtualMachine vm, ThreadReferenceImpl thread,
                   long id, Location location) {
        super(vm);
        this.thread = thread;
        this.id = id;
        this.location = location;
        thread.addListener(this);
    }

    /*
     * ThreadListener implementation
     * Must be synchronized since we must protect against
     * sending defunct (isValid == false) stack ids to the back-end.
     */
    public boolean threadResumable(ThreadAction action) {
        synchronized (vm.state()) {
            if (isValid) {
                isValid = false;
                return false;   /* remove this stack frame as a listener */
            } else {
                throw new InternalException(
                                  "Invalid stack frame thread listener");
            }
        }
    }

    void validateStackFrame() {
        if (!isValid) {
            throw new InvalidStackFrameException("Thread has been resumed");
        }
    }

    /**
     * Return the frame location.
     * Need not be synchronized since it cannot be provably stale.
     */
    public Location location() {
        validateStackFrame();
        return location;
    }

    /**
     * Return the thread holding the frame.
     * Need not be synchronized since it cannot be provably stale.
     */
    public ThreadReference thread() {
        validateStackFrame();
        return thread;
    }

    public boolean equals(Object obj) {
        if (obj instanceof StackFrameImpl) {
            StackFrameImpl other = (StackFrameImpl)obj;
            return (id == other.id) &&
                   (thread().equals(other.thread())) &&
                   (location().equals(other.location())) &&
                    super.equals(obj);
        } else {
            return false;
        }
    }

    public int hashCode() {
        return (thread().hashCode() << 4) + ((int)id);
    }

    public ObjectReference thisObject() {
        validateStackFrame();
        MethodImpl currentMethod = (MethodImpl)location.method();
        if (currentMethod.isStatic() || currentMethod.isNative()) {
            return null;
        } else {
            if (thisObject == null) {
                PacketStream ps;

                /* protect against defunct frame id */
                synchronized (vm.state()) {
                    validateStackFrame();
                    ps = JDWP.StackFrame.ThisObject.
                                      enqueueCommand(vm, thread, id);
                }

                /* actually get it, now that order is guaranteed */
                try {
                    thisObject = JDWP.StackFrame.ThisObject.
                                      waitForReply(vm, ps).objectThis;
                } catch (JDWPException exc) {
                    switch (exc.errorCode()) {
                    case JDWP.Error.INVALID_FRAMEID:
                    case JDWP.Error.THREAD_NOT_SUSPENDED:
                    case JDWP.Error.INVALID_THREAD:
                        throw new InvalidStackFrameException();
                    default:
                        throw exc.toJDIException();
                    }
                }
            }
        }
        return thisObject;
    }

    @SuppressWarnings("unused")
    public CompletableFuture<ObjectReference> thisObjectAsync() {
        validateStackFrame();
        return AsyncRequests.methodAsync(((LocationImpl) location)).thenCompose(currentMethod -> {
            if (currentMethod.isStatic() || currentMethod.isNative()) {
                return CompletableFuture.completedFuture(null);
            }
            if (thisObject == null) {
                /* protect against defunct frame id */
                synchronized (vm.state()) {
                    validateStackFrame();
                    return JDWP.StackFrame.ThisObject.processAsync(vm, thread, id)
                            .exceptionally(throwable -> {
                                if (JDWPException.isOfType(throwable,
                                        JDWP.Error.INVALID_FRAMEID,
                                        JDWP.Error.THREAD_NOT_SUSPENDED,
                                        JDWP.Error.INVALID_THREAD)) {
                                    throw new InvalidStackFrameException();
                                }
                                throw (RuntimeException) throwable;
                            })
                            .thenApply(to -> thisObject = to.objectThis);
                }
            }
            return CompletableFuture.completedFuture(thisObject);
        });
    }

    /**
     * Build the visible variable map.
     * Need not be synchronized since it cannot be provably stale.
     */
    private Map<String, LocalVariable> createVisibleVariables() throws AbsentInformationException {
        if (visibleVariables == null) {
            createVisibleVariablesImpl(location.method().variables());
        }
        return visibleVariables;
    }

    private CompletableFuture<Map<String, LocalVariable>> createVisibleVariablesAsync() {
        if (visibleVariables == null) {
            return AsyncRequests.methodAsync(((LocationImpl) location))
                    .thenCompose(method -> ((MethodImpl) method).variablesAsync())
                    .thenApply(this::createVisibleVariablesImpl);
        }
        return CompletableFuture.completedFuture(visibleVariables);
    }

    private Map<String, LocalVariable> createVisibleVariablesImpl(List<LocalVariable> allVariables) {
        Map<String, LocalVariable> map = new HashMap<>(allVariables.size());

        for (LocalVariable variable : allVariables) {
            String name = variable.name();
            if (variable.isVisible(this)) {
                LocalVariable existing = map.get(name);
                if ((existing == null) ||
                        ((LocalVariableImpl) variable).hides(existing)) {
                    map.put(name, variable);
                }
            }
        }
        return visibleVariables = map;
    }

    /**
     * Return the list of visible variable in the frame.
     * Need not be synchronized since it cannot be provably stale.
     */
    public List<LocalVariable> visibleVariables() throws AbsentInformationException {
        validateStackFrame();
        List<LocalVariable> mapAsList = new ArrayList<>(createVisibleVariables().values());
        Collections.sort(mapAsList);
        return mapAsList;
    }

    @SuppressWarnings("unused")
    public CompletableFuture<List<LocalVariable>> visibleVariablesAsync() {
        validateStackFrame();
        return createVisibleVariablesAsync().thenApply(v -> {
            List<LocalVariable> mapAsList = new ArrayList<>(v.values());
            Collections.sort(mapAsList);
            return mapAsList;
        });
    }

    /**
     * Return a particular variable in the frame.
     * Need not be synchronized since it cannot be provably stale.
     */
    public LocalVariable visibleVariableByName(String name) throws AbsentInformationException  {
        validateStackFrame();
        return createVisibleVariables().get(name);
    }

    @SuppressWarnings("unused")
    public CompletableFuture<LocalVariable> visibleVariableByNameAsync(String name) {
        validateStackFrame();
        return createVisibleVariablesAsync().thenApply(variables -> variables.get(name));
    }

    public Value getValue(LocalVariable variable) {
        return getValues(List.of(variable)).get(variable);
    }

    @SuppressWarnings("unused")
    public CompletableFuture<Value> getValueAsync(LocalVariable variable) {
        return getValuesAsync(List.of(variable)).thenApply(res -> res.get(variable));
    }

    public Value[] getSlotsValues(List<? extends SlotLocalVariable> slotsVariables) {
        validateStackFrame();

        JDWP.StackFrame.GetValues.SlotInfo[] slots =
                slotsVariables.stream()
                        .map(v -> new JDWP.StackFrame.GetValues.SlotInfo(v.slot(),
                                (byte) v.signature().charAt(0)))
                        .toArray(JDWP.StackFrame.GetValues.SlotInfo[]::new);
        return getSlotsValues(slots);
    }

    private ValueImpl[] getSlotsValues(JDWP.StackFrame.GetValues.SlotInfo[] slots) {
        PacketStream ps;

        /* protect against defunct frame id */
        synchronized (vm.state()) {
            validateStackFrame();
            ps = JDWP.StackFrame.GetValues.enqueueCommand(vm, thread, id, slots);
        }

        /* actually get it, now that order is guaranteed */
        ValueImpl[] values;
        try {
            values = JDWP.StackFrame.GetValues.waitForReply(vm, ps).values;
        } catch (JDWPException exc) {
            switch (exc.errorCode()) {
                case JDWP.Error.INVALID_FRAMEID:
                case JDWP.Error.THREAD_NOT_SUSPENDED:
                case JDWP.Error.INVALID_THREAD:
                    throw new InvalidStackFrameException();
                default:
                    throw exc.toJDIException();
            }
        }

        if (slots.length != values.length) {
            throw new InternalException(
                    "Wrong number of values returned from target VM");
        }
        return values;
    }

    private CompletableFuture<ValueImpl[]> getSlotsValuesAsync(JDWP.StackFrame.GetValues.SlotInfo[] slots) {
        /* protect against defunct frame id */
        synchronized (vm.state()) {
            validateStackFrame();
            return JDWP.StackFrame.GetValues.processAsync(vm, thread, id, slots)
                    .exceptionally(throwable -> {
                        if (JDWPException.isOfType(throwable,
                                JDWP.Error.INVALID_FRAMEID,
                                JDWP.Error.THREAD_NOT_SUSPENDED,
                                JDWP.Error.INVALID_THREAD)) {
                            throw new InvalidStackFrameException();
                        }
                        throw (RuntimeException) throwable;
                    })
                    .thenApply(v -> {
                        ValueImpl[] values = v.values;
                        if (slots.length != values.length) {
                            throw new InternalException(
                                    "Wrong number of values returned from target VM");
                        }
                        return values;
                    });
        }
    }

    public Map<LocalVariable, Value> getValues(List<? extends LocalVariable> variables) {
        validateStackFrame();
        validateMirrors(variables);

        int count = variables.size();
        JDWP.StackFrame.GetValues.SlotInfo[] slots = createSlots(variables, count);

        ValueImpl[] values = getSlotsValues(slots);

        Map<LocalVariable, Value> map = new HashMap<>(count);
        for (int i = 0; i < count; ++i) {
            map.put(variables.get(i), values[i]);
        }
        return map;
    }

    public CompletableFuture<Map<LocalVariable, Value>> getValuesAsync(List<? extends LocalVariable> variables) {
        validateStackFrame();
        validateMirrors(variables);

        int count = variables.size();
        JDWP.StackFrame.GetValues.SlotInfo[] slots = createSlots(variables, count);

        return getSlotsValuesAsync(slots).thenApply(values -> {
            Map<LocalVariable, Value> map = new HashMap<>(count);
            for (int i = 0; i < count; ++i) {
                map.put(variables.get(i), values[i]);
            }
            return map;
        });
    }

    private JDWP.StackFrame.GetValues.SlotInfo[] createSlots(List<? extends LocalVariable> variables, int count) {
        JDWP.StackFrame.GetValues.SlotInfo[] slots =
                new JDWP.StackFrame.GetValues.SlotInfo[count];

        for (int i = 0; i < count; ++i) {
            LocalVariableImpl variable = (LocalVariableImpl) variables.get(i);
            if (!variable.isVisible(this)) {
                throw new IllegalArgumentException(variable.name() +
                        " is not valid at this frame location");
            }
            slots[i] = new JDWP.StackFrame.GetValues.SlotInfo(variable.slot(),
                    (byte) variable.signature().charAt(0));
        }
        return slots;
    }

    @SuppressWarnings("unused")
    public void setSlotValue(SlotLocalVariable variable, Value value) {
        validateStackFrame();
        validateMirrorOrNull(value);

        JDWP.StackFrame.SetValues.SlotInfo[] slotVals = new JDWP.StackFrame.SetValues.SlotInfo[1];
        slotVals[0] = new JDWP.StackFrame.SetValues.SlotInfo(variable.slot(), (ValueImpl) value);

        setSlotsValues(slotVals);
    }

    private void setSlotsValues(JDWP.StackFrame.SetValues.SlotInfo[] slotVals) {
        PacketStream ps;

        /* protect against defunct frame id */
        synchronized (vm.state()) {
            validateStackFrame();
            ps = JDWP.StackFrame.SetValues.enqueueCommand(vm, thread, id, slotVals);
        }

        /* actually set it, now that order is guaranteed */
        try {
            JDWP.StackFrame.SetValues.waitForReply(vm, ps);
        } catch (JDWPException exc) {
            switch (exc.errorCode()) {
                case JDWP.Error.INVALID_FRAMEID:
                case JDWP.Error.THREAD_NOT_SUSPENDED:
                case JDWP.Error.INVALID_THREAD:
                    throw new InvalidStackFrameException();
                default:
                    throw exc.toJDIException();
            }
        }
    }

    public void setValue(LocalVariable variableIntf, Value valueIntf)
        throws InvalidTypeException, ClassNotLoadedException {

        validateStackFrame();
        validateMirror(variableIntf);
        validateMirrorOrNull(valueIntf);

        LocalVariableImpl variable = (LocalVariableImpl)variableIntf;
        ValueImpl value = (ValueImpl)valueIntf;

        if (!variable.isVisible(this)) {
            throw new IllegalArgumentException(variable.name() +
                             " is not valid at this frame location");
        }

        try {
            // Validate and convert value if necessary
            value = ValueImpl.prepareForAssignment(value, variable);

            JDWP.StackFrame.SetValues.SlotInfo[] slotVals =
                new JDWP.StackFrame.SetValues.SlotInfo[1];
            slotVals[0] = new JDWP.StackFrame.SetValues.
                                       SlotInfo(variable.slot(), value);

            setSlotsValues(slotVals);
        } catch (ClassNotLoadedException e) {
            /*
             * Since we got this exception,
             * the variable type must be a reference type. The value
             * we're trying to set is null, but if the variable's
             * class has not yet been loaded through the enclosing
             * class loader, then setting to null is essentially a
             * no-op, and we should allow it without an exception.
             */
            if (value != null) {
                throw e;
            }
        }
    }

    public List<Value> getArgumentValues() {
        validateStackFrame();
        MethodImpl mmm = (MethodImpl)location.method();
        List<String> argSigs = mmm.argumentSignatures();
        int count = argSigs.size();
        JDWP.StackFrame.GetValues.SlotInfo[] slots =
                           new JDWP.StackFrame.GetValues.SlotInfo[count];

        int slot;
        if (mmm.isStatic()) {
            slot = 0;
        } else {
            slot = 1;
        }
        for (int ii = 0; ii < count; ++ii) {
            char sigChar = argSigs.get(ii).charAt(0);
            slots[ii] = new JDWP.StackFrame.GetValues.SlotInfo(slot++,(byte)sigChar);
            if (sigChar == 'J' || sigChar == 'D') {
                slot++;
            }
        }

        PacketStream ps;

        /* protect against defunct frame id */
        synchronized (vm.state()) {
            validateStackFrame();
            ps = JDWP.StackFrame.GetValues.enqueueCommand(vm, thread, id, slots);
        }

        ValueImpl[] values;
        try {
            values = JDWP.StackFrame.GetValues.waitForReply(vm, ps).values;
        } catch (JDWPException exc) {
            switch (exc.errorCode()) {
                case JDWP.Error.INVALID_FRAMEID:
                case JDWP.Error.THREAD_NOT_SUSPENDED:
                case JDWP.Error.INVALID_THREAD:
                    throw new InvalidStackFrameException();
                default:
                    throw exc.toJDIException();
            }
        }

        if (count != values.length) {
            throw new InternalException(
                      "Wrong number of values returned from target VM");
        }
        return Arrays.asList(values);
    }

    void pop() throws IncompatibleThreadStateException {
        validateStackFrame();
        // flush caches and disable caching until command completion
        try {
            PacketStream stream = thread.sendResumingCommand(
                    () -> JDWP.StackFrame.PopFrames.enqueueCommand(vm, thread, id));
            JDWP.StackFrame.PopFrames.waitForReply(vm, stream);
        } catch (JDWPException exc) {
            switch (exc.errorCode()) {
            case JDWP.Error.OPAQUE_FRAME:
                if (thread.isVirtual()) {
                    // We first need to find out if the current frame is native, or if the
                    // previous frame is native, in which case we throw NativeMethodException
                    for (int i = 0; i < 2; i++) {
                        StackFrameImpl sf;
                        try {
                            sf = (StackFrameImpl)thread.frame(i);
                        } catch (IndexOutOfBoundsException e) {
                            // This should never happen, but we need to check for it.
                            break;
                        }
                        sf.validateStackFrame();
                        MethodImpl meth = (MethodImpl)sf.location().method();
                        if (meth.isNative()) {
                            throw new NativeMethodException();
                        }
                    }
                    // No native frames involved. Must have been due to thread
                    // not being mounted.
                    throw new InvalidStackFrameException("Opaque frame");
//                    throw new OpaqueFrameException();
                } else {
                    throw new NativeMethodException();
                }
            case JDWP.Error.THREAD_NOT_SUSPENDED:
                throw new IncompatibleThreadStateException(
                         "Thread not current or suspended");
            case JDWP.Error.INVALID_THREAD:   /* zombie */
                throw new IncompatibleThreadStateException("zombie");
            case JDWP.Error.NO_MORE_FRAMES:
                throw new InvalidStackFrameException(
                         "No more frames on the stack");
            default:
                throw exc.toJDIException();
            }
        }

        // enable caching - suspended again
        vm.state().freeze();
    }

    public String toString() {
       return location.toString() + " in thread " + thread.toString();
    }
}
