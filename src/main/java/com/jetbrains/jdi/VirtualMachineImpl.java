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

import com.sun.jdi.*;
import com.sun.jdi.connect.spi.Connection;
import com.sun.jdi.event.EventQueue;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.text.MessageFormat;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.sun.jdi.ModuleReference;

public class VirtualMachineImpl extends MirrorImpl
             implements PathSearchingVirtualMachine, ThreadListener {
    // VM Level exported variables, these
    // are unique to a given vm
    public final int sizeofFieldRef;
    public final int sizeofMethodRef;
    public final int sizeofObjectRef;
    public final int sizeofClassRef;
    public final int sizeofFrameRef;
    public final int sizeofModuleRef;

    final int sequenceNumber;

    private final TargetVM target;
    private final EventQueueImpl eventQueue;
    private final EventRequestManagerImpl internalEventRequestManager;
    private final EventRequestManagerImpl eventRequestManager;
    final VirtualMachineManagerImpl vmManager;
    private final ThreadGroup threadGroupForJDI;

    // Allow direct access to this field so that that tracing code slows down
    // JDI as little as possible when not enabled.
    int traceFlags = TRACE_NONE;

    private Consumer<List<String>> debugTraceConsumer = VirtualMachineImpl::defaultPrintTrace;
    private static boolean useSoftReferences = true;

    static final int TRACE_RAW_SENDS     = 0x01000000;
    static final int TRACE_RAW_RECEIVES  = 0x02000000;

    boolean traceReceives = false;   // pre-compute because of frequency
    private final AtomicInteger sentPackets = new AtomicInteger();
    private final AtomicInteger waitPackets = new AtomicInteger();

    // ReferenceType access - updated with class prepare and unload events
    // Protected by "synchronized(state)". "retrievedAllTypes" may be
    // tested unsynchronized (since once true, it stays true), but must
    // be set synchronously
    private final Map<Long, ReferenceType> typesByID = new HashMap<>(300);
    private final Map<String, Object> typesBySignature = new HashMap<>(300);
    private volatile boolean retrievedAllTypes = false;

    private Map<Long, ModuleReference> modulesByID;

    // For other languages support
    private String defaultStratum = null;

    // ObjectReference cache
    // "objectsByID" protected by "synchronized(state)".
    private final Map<Long, SoftObjectReference> objectsByID = new HashMap<>();
    private final ReferenceQueue<ObjectReferenceImpl> referenceQueue = new ReferenceQueue<>();
    private static final int DISPOSE_THRESHOLD = 50;
    private final List<SoftObjectReference> batchedDisposeRequests =
            Collections.synchronizedList(new ArrayList<>(DISPOSE_THRESHOLD + 10));

    // These are cached once for the life of the VM
    private JDWP.VirtualMachine.Version versionInfo;
    private JDWP.VirtualMachine.ClassPaths pathInfo;
    private JDWP.VirtualMachine.Capabilities capabilities = null;
    private JDWP.VirtualMachine.CapabilitiesNew capabilitiesNew = null;

    // Per-vm singletons for primitive types and for void.
    // singleton-ness protected by "synchronized(state)".
    private final BooleanType theBooleanType;
    private final ByteType    theByteType;
    private final CharType    theCharType;
    private final ShortType   theShortType;
    private final IntegerType theIntegerType;
    private final LongType    theLongType;
    private final FloatType   theFloatType;
    private final DoubleType  theDoubleType;

    private final VoidType    theVoidType;

    private final VoidValueImpl voidVal;
    private final BooleanValueImpl trueValue;
    private final BooleanValueImpl falseValue;
    private final ByteValueImpl[] byteValues = new ByteValueImpl[256];

    // Launched debuggee process
    private final Process process;

    // coordinates state changes and corresponding listener notifications
    private final VMState state = new VMState(this);

    private final Object initMonitor = new Object();
    private boolean initComplete = false;
    private boolean shutdown = false;

    private void notifyInitCompletion() {
        synchronized(initMonitor) {
            initComplete = true;
            initMonitor.notifyAll();
        }
    }

    void waitInitCompletion() {
        synchronized(initMonitor) {
            while (!initComplete) {
                try {
                    initMonitor.wait();
                } catch (InterruptedException e) {
                    // ignore
                }
            }
        }
    }

    VMState state() {
        return state;
    }

    /*
     * ThreadListener implementation
     */
    public boolean threadResumable(ThreadAction action) {
        /*
         * If any thread is resumed, the VM is considered not suspended.
         * Just one thread is being resumed so pass it to thaw.
         */
        state.thaw(action.thread());
        return true;
    }

    VirtualMachineImpl(VirtualMachineManager manager,
                       Connection connection, Process process,
                       int sequenceNumber) {
        super(null);  // Can't use super(this)
        vm = this;

        this.vmManager = (VirtualMachineManagerImpl)manager;
        this.process = process;
        this.sequenceNumber = sequenceNumber;

        /* Create ThreadGroup to be used by all threads servicing
         * this VM.
         */
        threadGroupForJDI = new ThreadGroup(vmManager.mainGroupForJDI(),
                                            "JDI [" +
                                            this.hashCode() + "]");

        /*
         * Set up a thread to communicate with the target VM over
         * the specified transport.
         */
        target = new TargetVM(this, connection);

        /*
         * Set up a thread to handle events processed internally
         * the JDI implementation.
         */
        EventQueueImpl internalEventQueue = new EventQueueImpl(this, target);
        new InternalEventHandler(this, internalEventQueue);
        /*
         * Initialize client access to event setting and handling
         */
        eventQueue = new EventQueueImpl(this, target);
        eventRequestManager = new EventRequestManagerImpl(this);

        target.start();

        /*
         * Many ids are variably sized, depending on target VM.
         * Find out the sizes right away.
         */
        JDWP.VirtualMachine.IDSizes idSizes;
        try {
            idSizes = JDWP.VirtualMachine.IDSizes.process(vm);
        } catch (JDWPException exc) {
            throw exc.toJDIException();
        }
        sizeofFieldRef  = idSizes.fieldIDSize;
        sizeofMethodRef = idSizes.methodIDSize;
        sizeofObjectRef = idSizes.objectIDSize;
        sizeofClassRef = idSizes.referenceTypeIDSize;
        sizeofFrameRef  = idSizes.frameIDSize;
        sizeofModuleRef = idSizes.objectIDSize;

        /**
         * Set up requests needed by internal event handler.
         * Make sure they are distinguished by creating them with
         * an internal event request manager.
         *
         * Warning: create events only with SUSPEND_NONE policy.
         * In the current implementation other policies will not
         * be handled correctly when the event comes in. (notfiySuspend()
         * will not be properly called, and if the event is combined
         * with external events in the same set, suspend policy is not
         * correctly determined for the internal vs. external event sets)
         */
        internalEventRequestManager = new EventRequestManagerImpl(this);
        EventRequest er = internalEventRequestManager.createClassPrepareRequest();
        er.setSuspendPolicy(EventRequest.SUSPEND_NONE);
        internalEventRequestManager.setEnabledAsync(er, true);
        er = internalEventRequestManager.createClassUnloadRequest();
        er.setSuspendPolicy(EventRequest.SUSPEND_NONE);
        internalEventRequestManager.setEnabledAsync(er, true);

        theBooleanType = new BooleanTypeImpl(this);
        theByteType = new ByteTypeImpl(this);
        theCharType = new CharTypeImpl(this);
        theShortType = new ShortTypeImpl(this);
        theIntegerType = new IntegerTypeImpl(this);
        theLongType = new LongTypeImpl(this);
        theFloatType = new FloatTypeImpl(this);
        theDoubleType = new DoubleTypeImpl(this);

        theVoidType = new VoidTypeImpl(this);

        voidVal = new VoidValueImpl(this);
        trueValue = new BooleanValueImpl(this, true);
        falseValue = new BooleanValueImpl(this, false);

        /*
         * Tell other threads, notably TargetVM, that initialization
         * is complete.
         */
        notifyInitCompletion();
    }

    EventRequestManagerImpl getInternalEventRequestManager() {
        return internalEventRequestManager;
    }

    void validateVM() {
        /*
         * We no longer need to do this.  The spec now says
         * that a VMDisconnected _may_ be thrown in these
         * cases, not that it _will_ be thrown.
         * So, to simplify things we will just let the
         * caller's of this method proceed with their business.
         * If the debuggee is disconnected, either because it
         * crashed or finished or something, or because the
         * debugger called exit() or dispose(), then if
         * we end up trying to communicate with the debuggee,
         * code in TargetVM will throw a VMDisconnectedException.
         * This means that if we can satisfy a request without
         * talking to the debuggee, (eg, with cached data) then
         * VMDisconnectedException will _not_ be thrown.
         * if (shutdown) {
         *    throw new VMDisconnectedException();
         * }
         */
    }

    public boolean equals(Object obj) {
        return this == obj;
    }

    public int hashCode() {
        return System.identityHashCode(this);
    }

    public List<ModuleReference> allModules() {
        validateVM();
        List<ModuleReference> modules = retrieveAllModules();
        return Collections.unmodifiableList(modules);
    }

    public List<ReferenceType> classesByName(String className) {
        validateVM();
        return classesBySignature(JNITypeParser.typeNameToSignature(className));
    }

    List<ReferenceType> classesBySignature(String signature) {
        validateVM();
        if (retrievedAllTypes) {
            return findReferenceTypes(signature);
        } else {
            return Collections.unmodifiableList(retrieveClassesBySignature(signature));
        }
    }

    public List<ReferenceType> allClasses() {
        validateVM();

        if (!retrievedAllTypes) {
            retrieveAllClasses();
        }
        synchronized (state) {
            return List.copyOf(typesByID.values());
        }
    }

    @SuppressWarnings("unused")
    public CompletableFuture<List<ReferenceType>> allClassesAsync() {
        validateVM();

        CompletableFuture<Void> res;

        if (retrievedAllTypes) {
            res = CompletableFuture.completedFuture(null);
        } else {
            res = retrieveAllClassesAsync();
        }
        return res.thenApply(unused -> {
            synchronized (state) {
                return List.copyOf(typesByID.values());
            }
        });
    }

    /**
     * Performs an action for each loaded type.
     */
    public void forEachClass(Consumer<ReferenceType> action) {
        for (ReferenceType type : allClasses()) {
            try {
                action.accept(type);
            } catch (ObjectCollectedException ex) {
                // Some classes might be unloaded and garbage collected since
                // we retrieved the copy of all loaded classes and started
                // iterating over them. In this case calling methods on such types
                // might result in com.sun.jdi.ObjectCollectedException
                // being thrown. We ignore such classes and keep iterating.
                if ((vm.traceFlags & VirtualMachine.TRACE_OBJREFS) != 0) {
                    vm.printTrace("ObjectCollectedException was thrown while " +
                            "accessing unloaded class " + type.name());
                }
            }
        }
    }

    public void
        redefineClasses(Map<? extends ReferenceType, byte[]> classToBytes)
    {
        int cnt = classToBytes.size();
        JDWP.VirtualMachine.RedefineClasses.ClassDef[] defs =
            new JDWP.VirtualMachine.RedefineClasses.ClassDef[cnt];
        validateVM();
        if (!canRedefineClasses()) {
            throw new UnsupportedOperationException();
        }
        Iterator<?> it = classToBytes.entrySet().iterator();
        for (int i = 0; it.hasNext(); i++) {
            @SuppressWarnings("rawtypes")
            Map.Entry<?, ?> entry = (Map.Entry)it.next();
            ReferenceTypeImpl refType = (ReferenceTypeImpl)entry.getKey();
            validateMirror(refType);
            defs[i] = new JDWP.VirtualMachine.RedefineClasses
                       .ClassDef(refType, (byte[])entry.getValue());
        }

        // flush caches and disable caching until the next suspend
        vm.state().thaw();

        try {
            JDWP.VirtualMachine.RedefineClasses.
                process(vm, defs);
        } catch (JDWPException exc) {
            short errorCode = exc.errorCode();
            switch (errorCode) {
            case JDWP.Error.INVALID_CLASS_FORMAT :
                throw new ClassFormatError(
                    "class not in class file format");
            case JDWP.Error.CIRCULAR_CLASS_DEFINITION :
                throw new ClassCircularityError(
                    "circularity has been detected while initializing a class");
            case JDWP.Error.FAILS_VERIFICATION :
                throw new VerifyError(
                    "verifier detected internal inconsistency or security problem");
            case JDWP.Error.UNSUPPORTED_VERSION :
                throw new UnsupportedClassVersionError(
                    "version numbers of class are not supported");
            case JDWP.Error.ADD_METHOD_NOT_IMPLEMENTED:
                throw new JDWPUnsupportedOperationException(errorCode,
                    "add method not implemented");
            case JDWP.Error.SCHEMA_CHANGE_NOT_IMPLEMENTED :
                throw new JDWPUnsupportedOperationException(errorCode,
                    "schema change not implemented");
            case JDWP.Error.HIERARCHY_CHANGE_NOT_IMPLEMENTED:
                throw new JDWPUnsupportedOperationException(errorCode,
                    "hierarchy change not implemented");
            case JDWP.Error.DELETE_METHOD_NOT_IMPLEMENTED :
                throw new JDWPUnsupportedOperationException(errorCode,
                    "delete method not implemented");
            case JDWP.Error.CLASS_MODIFIERS_CHANGE_NOT_IMPLEMENTED:
                throw new JDWPUnsupportedOperationException(errorCode,
                    "changes to class modifiers not implemented");
            case JDWP.Error.METHOD_MODIFIERS_CHANGE_NOT_IMPLEMENTED :
                throw new JDWPUnsupportedOperationException(errorCode,
                    "changes to method modifiers not implemented");
            case JDWP.Error.CLASS_ATTRIBUTE_CHANGE_NOT_IMPLEMENTED :
                throw new JDWPUnsupportedOperationException(errorCode,
                    "changes to class attribute not implemented");
            case JDWP.Error.NAMES_DONT_MATCH :
                throw new NoClassDefFoundError(
                    "class names do not match");
            default:
                throw exc.toJDIException();
            }
        }

        // Delete any record of the breakpoints
        List<BreakpointRequest> toDelete = new ArrayList<>();
        EventRequestManager erm = eventRequestManager();
        it = erm.breakpointRequests().iterator();
        while (it.hasNext()) {
            BreakpointRequest req = (BreakpointRequest)it.next();
            if (classToBytes.containsKey(req.location().declaringType())) {
                toDelete.add(req);
            }
        }
        erm.deleteEventRequests(toDelete);

        // Invalidate any information cached for the classes just redefined.
        it = classToBytes.keySet().iterator();
        while (it.hasNext()) {
            ReferenceTypeImpl rti = (ReferenceTypeImpl)it.next();
            rti.noticeRedefineClass();
        }
    }

    public List<ThreadReference> allThreads() {
        validateVM();
        return state.allThreads();
    }

    @SuppressWarnings("unused")
    public CompletableFuture<List<ThreadReference>> allThreadsAsync() {
        validateVM();
        return state.allThreadsAsync();
    }

    public List<ThreadGroupReference> topLevelThreadGroups() {
        validateVM();
        return state.topLevelThreadGroups();
    }

    /*
     * Sends a command to the back end which is defined to do an
     * implicit vm-wide resume. The VM can no longer be considered
     * suspended, so certain cached data must be invalidated.
     */
    PacketStream sendResumingCommand(CommandSender sender) {
        return state.thawCommand(sender);
    }

    /*
     * The VM has been suspended. Additional caching can be done
     * as long as there are no pending resumes.
     */
    void notifySuspend() {
        state.freeze();
    }

    public void suspend() {
        validateVM();
        try {
            JDWP.VirtualMachine.Suspend.process(vm);
        } catch (JDWPException exc) {
            throw exc.toJDIException();
        }
        notifySuspend();
    }

    @SuppressWarnings("unused")
    public CompletableFuture<Void> suspendAsync() {
        validateVM();
        return JDWP.VirtualMachine.Suspend.processAsync(vm).thenAccept(suspend -> notifySuspend());
    }

    public void resume() {
        validateVM();
        try {
            PacketStream stream = state.thawCommand(() -> JDWP.VirtualMachine.Resume.enqueueCommand(vm));
            JDWP.VirtualMachine.Resume.waitForReply(vm, stream);
        } catch (VMDisconnectedException exc) {
            /*
             * If the debugger makes a VMDeathRequest with SUSPEND_ALL,
             * then when it does an EventSet.resume after getting the
             * VMDeathEvent, the normal flow of events is that the
             * BE shuts down, but the waitForReply comes back ok.  In this
             * case, the run loop in TargetVM that is waiting for a packet
             * gets an EOF because the socket closes. It generates a
             * VMDisconnectedEvent and everyone is happy.
             * However, sometimes, the BE gets shutdown before this
             * waitForReply completes.  In this case, TargetVM.waitForReply
             * gets awakened with no reply and so gens a VMDisconnectedException
             * which is not what we want.  It might be possible to fix this
             * in the BE, but it is ok to just ignore the VMDisconnectedException
             * here.  This will allow the VMDisconnectedEvent to be generated
             * correctly.  And, if the debugger should happen to make another
             * request, it will get a VMDisconnectedException at that time.
             */
        } catch (JDWPException exc) {
            switch (exc.errorCode()) {
                case JDWP.Error.VM_DEAD:
                    return;
                default:
                    throw exc.toJDIException();
            }
        }
    }

    public CompletableFuture<Void> resumeAsync() {
        validateVM();
        PacketStream stream = state.thawCommand(() -> JDWP.VirtualMachine.Resume.enqueueCommand(vm));
        return stream.readReply(p -> new JDWP.VirtualMachine.Resume(vm, stream))
                .exceptionally(throwable -> {
                    if (AsyncUtils.unwrap(throwable) instanceof VMDisconnectedException) {
                        return null;
                    }
                    throw (RuntimeException)throwable;
                })
                .thenAccept(__ -> {});
    }

    public EventQueue eventQueue() {
        /*
         * No VM validation here. We allow access to the event queue
         * after disconnection, so that there is access to the terminating
         * events.
         */
        return eventQueue;
    }

    public EventRequestManager eventRequestManager() {
        validateVM();
        return eventRequestManager;
    }

    EventRequestManagerImpl eventRequestManagerImpl() {
        return eventRequestManager;
    }

    public BooleanValueImpl mirrorOf(boolean value) {
        validateVM();
        return value ? trueValue : falseValue;
    }

    public ByteValueImpl mirrorOf(byte value) {
        validateVM();
        synchronized (byteValues) {
            ByteValueImpl res = byteValues[value & 0xFF];
            if (res == null) {
                res = new ByteValueImpl(this, value);
                byteValues[value & 0xFF] = res;
            }
            return res;
        }
    }

    public CharValueImpl mirrorOf(char value) {
        validateVM();
        return new CharValueImpl(this,value);
    }

    public ShortValueImpl mirrorOf(short value) {
        validateVM();
        return new ShortValueImpl(this,value);
    }

    public IntegerValueImpl mirrorOf(int value) {
        validateVM();
        return new IntegerValueImpl(this,value);
    }

    public LongValueImpl mirrorOf(long value) {
        validateVM();
        return new LongValueImpl(this,value);
    }

    public FloatValueImpl mirrorOf(float value) {
        validateVM();
        return new FloatValueImpl(this,value);
    }

    public DoubleValueImpl mirrorOf(double value) {
        validateVM();
        return new DoubleValueImpl(this,value);
    }

    public StringReferenceImpl mirrorOf(String value) {
        validateVM();
        try {
            return JDWP.VirtualMachine.CreateString.
                process(vm, value).stringObject;
        } catch (JDWPException exc) {
            throw exc.toJDIException();
        }
    }

    public CompletableFuture<StringReferenceImpl> mirrorOfAsync(String value) {
        validateVM();
        return JDWP.VirtualMachine.CreateString.processAsync(vm, value).thenApply(s -> s.stringObject);
    }

    public VoidValueImpl mirrorOfVoid() {
        return voidVal;
    }

    public long[] instanceCounts(List<? extends ReferenceType> classes) {
        if (!canGetInstanceInfo()) {
            throw new UnsupportedOperationException(
                "target does not support getting instances");
        }
        long[] retValue ;
        ReferenceTypeImpl[] rtArray = new ReferenceTypeImpl[classes.size()];
        int ii = 0;
        for (ReferenceType rti: classes) {
            validateMirror(rti);
            rtArray[ii++] = (ReferenceTypeImpl)rti;
        }
        try {
            retValue = JDWP.VirtualMachine.InstanceCounts.
                                process(vm, rtArray).counts;
        } catch (JDWPException exc) {
            throw exc.toJDIException();
        }

        return retValue;
    }

    public void dispose() {
        validateVM();
        shutdown = true;
        try {
            JDWP.VirtualMachine.Dispose.process(vm);
        } catch (JDWPException exc) {
            throw exc.toJDIException();
        }
        target.stopListening();
    }

    public void exit(int exitCode) {
        validateVM();
        shutdown = true;
        try {
            JDWP.VirtualMachine.Exit.process(vm, exitCode);
        } catch (JDWPException exc) {
            throw exc.toJDIException();
        }
        target.stopListening();
    }

    public Process process() {
        validateVM();
        return process;
    }

    private JDWP.VirtualMachine.Version versionInfo() {
       try {
           if (versionInfo == null) {
               // Need not be synchronized since it is static information
               versionInfo = JDWP.VirtualMachine.Version.process(vm);
           }
           return versionInfo;
       } catch (JDWPException exc) {
           throw exc.toJDIException();
       }
    }

    public String description() {
        validateVM();

        return MessageFormat.format(vmManager.getString("version_format"),
                                    "" + vmManager.majorInterfaceVersion(),
                                    "" + vmManager.minorInterfaceVersion(),
                                     versionInfo().description);
    }

    public String version() {
        validateVM();
        return versionInfo().vmVersion;
    }

    public String name() {
        validateVM();
        return versionInfo().vmName;
    }

    public boolean canWatchFieldModification() {
        validateVM();
        return capabilities().canWatchFieldModification;
    }

    public boolean canWatchFieldAccess() {
        validateVM();
        return capabilities().canWatchFieldAccess;
    }

    public boolean canGetBytecodes() {
        validateVM();
        return capabilities().canGetBytecodes;
    }

    public boolean canGetSyntheticAttribute() {
        validateVM();
        return capabilities().canGetSyntheticAttribute;
    }

    public boolean canGetOwnedMonitorInfo() {
        validateVM();
        return capabilities().canGetOwnedMonitorInfo;
    }

    public boolean canGetCurrentContendedMonitor() {
        validateVM();
        return capabilities().canGetCurrentContendedMonitor;
    }

    public boolean canGetMonitorInfo() {
        validateVM();
        return capabilities().canGetMonitorInfo;
    }

    private boolean hasNewCapabilities() {
        return versionInfo().jdwpMajor > 1 ||
            versionInfo().jdwpMinor >= 4;
    }

    boolean canGet1_5LanguageFeatures() {
        return versionInfo().jdwpMajor > 1 ||
            versionInfo().jdwpMinor >= 5;
    }

    public boolean canUseInstanceFilters() {
        validateVM();
        return hasNewCapabilities() &&
            capabilitiesNew().canUseInstanceFilters;
    }

    public boolean canRedefineClasses() {
        validateVM();
        return hasNewCapabilities() &&
            capabilitiesNew().canRedefineClasses;
    }

    @Deprecated//(since="15")
    public boolean canAddMethod() {
        validateVM();
        return hasNewCapabilities() &&
            capabilitiesNew().canAddMethod;
    }

    @Deprecated//(since="15")
    public boolean canUnrestrictedlyRedefineClasses() {
        validateVM();
        return hasNewCapabilities() &&
            capabilitiesNew().canUnrestrictedlyRedefineClasses;
    }

    public boolean canPopFrames() {
        validateVM();
        return hasNewCapabilities() &&
            capabilitiesNew().canPopFrames;
    }

    public boolean canGetMethodReturnValues() {
        return versionInfo().jdwpMajor > 1 ||
            versionInfo().jdwpMinor >= 6;
    }

    public boolean canGetInstanceInfo() {
        if (versionInfo().jdwpMajor > 1 ||
            versionInfo().jdwpMinor >= 6) {
            validateVM();
            return hasNewCapabilities() &&
                capabilitiesNew().canGetInstanceInfo;
        } else {
            return false;
        }
    }

    public boolean canUseSourceNameFilters() {
        return versionInfo().jdwpMajor > 1 ||
            versionInfo().jdwpMinor >= 6;
    }

    public boolean canForceEarlyReturn() {
        validateVM();
        return hasNewCapabilities() &&
            capabilitiesNew().canForceEarlyReturn;
    }

    public boolean canBeModified() {
        return true;
    }

    public boolean canGetSourceDebugExtension() {
        validateVM();
        return hasNewCapabilities() &&
            capabilitiesNew().canGetSourceDebugExtension;
    }

    public boolean canGetClassFileVersion() {
        return versionInfo().jdwpMajor > 1 ||
            versionInfo().jdwpMinor >= 6;
    }

    public boolean canGetConstantPool() {
        validateVM();
        return hasNewCapabilities() &&
            capabilitiesNew().canGetConstantPool;
    }

    public boolean canRequestVMDeathEvent() {
        validateVM();
        return hasNewCapabilities() &&
            capabilitiesNew().canRequestVMDeathEvent;
    }

    public boolean canRequestMonitorEvents() {
        validateVM();
        return hasNewCapabilities() &&
            capabilitiesNew().canRequestMonitorEvents;
    }

    public boolean canGetMonitorFrameInfo() {
        validateVM();
        return hasNewCapabilities() &&
            capabilitiesNew().canGetMonitorFrameInfo;
    }

    public boolean canGetModuleInfo() {
        validateVM();
        return versionInfo().jdwpMajor >= 9;
    }

    boolean mayCreateVirtualThreads() {
        return versionInfo().jdwpMajor >= 19;
    }

    public void setDebugTraceMode(int traceFlags) {
        validateVM();
        this.traceFlags = traceFlags;
        this.traceReceives = (traceFlags & TRACE_RECEIVES) != 0;
    }

    public void setDebugTraceConsumer(Consumer<List<String>> consumer) {
        if (consumer == null) {
            debugTraceConsumer = VirtualMachineImpl::defaultPrintTrace;
        }
        else {
            debugTraceConsumer = consumer;
        }
    }

    public void disableSoftReferences() {
        useSoftReferences = false;
    }

    <T> SoftReference<T> createSoftReference(T object) {
        if (useSoftReferences) {
            return new SoftReference<>(object);
        }
        else {
            return new HardSoftReference<>(object);
        }
    }

    // simple SoftReference implementation that works as a hard reference
    private static class HardSoftReference<T> extends SoftReference<T> {
        T hardRef;
        public HardSoftReference(T referent) {
            super(referent);
            hardRef = referent;
        }
    }

    void printTraceSafe(Supplier<String> stringSupplier) {
        try {
            printTrace(stringSupplier.get());
        }
        catch (Throwable t) {
            // the logging should not affect program execution
            printTrace("Error while JDI tracing: " + t.getMessage());
        }
    }

    void printTrace(String string) {
        debugTraceConsumer.accept(List.of(string));
    }

    void printTrace(List<String> strings) {
        debugTraceConsumer.accept(strings);
    }

    private static void defaultPrintTrace(List<String> strings) {
        StringBuilder builder = new StringBuilder();
        for (String string : strings) {
            builder.append("[JDI: ").append(string).append("]").append(System.lineSeparator());
        }
        System.err.print(builder);
    }

    void printReceiveTraceSafe(int depth, Supplier<String> stringSupplier) {
        try {
            printReceiveTrace(depth, stringSupplier.get());
        }
        catch (Throwable t) {
            // the logging should not affect program execution
            printTrace("Error while JDI tracing: " + t.getMessage());
        }
    }

    void printReceiveTrace(int depth, String string) {
        StringBuilder sb = new StringBuilder("Receiving:");
        for (int i = depth; i > 0; --i) {
            sb.append("    ");
        }
        sb.append(string);
        printTrace(sb.toString());
    }

    private ReferenceTypeImpl addReferenceType(long id, int tag, String signature) {
        ReferenceTypeImpl type;
        switch(tag) {
            case JDWP.TypeTag.CLASS:
                type = new ClassTypeImpl(vm, id);
                break;
            case JDWP.TypeTag.INTERFACE:
                type = new InterfaceTypeImpl(vm, id);
                break;
            case JDWP.TypeTag.ARRAY:
                type = new ArrayTypeImpl(vm, id);
                break;
            default:
                throw new InternalException("Invalid reference type tag");
        }

        if (signature == null && retrievedAllTypes) {
            // do not cache if signature is not provided
            return type;
        }

        typesByID.put(id, type);
        if (signature != null) {
            type.setSignature(signature);
        }

        if ((vm.traceFlags & VirtualMachine.TRACE_REFTYPES) != 0) {
           vm.printTrace("Caching new ReferenceType, sig=" + signature +
                         ", id=" + id);
        }

        return type;
    }

    void cacheTypeBySignature(ReferenceTypeImpl type, String signature) {
      synchronized (state) {
        typesBySignature.merge(signature, type, (oldValue, newValue) -> {
            if (oldValue instanceof ReferenceType[]) {
                ReferenceType[] oldArray = (ReferenceType[]) oldValue;
                ReferenceType[] newArray = Arrays.copyOf(oldArray, oldArray.length + 1);
                newArray[oldArray.length] = (ReferenceTypeImpl) newValue;
                return newArray;
            }
            assert oldValue instanceof ReferenceType;
            return new ReferenceType[]{(ReferenceType) oldValue, (ReferenceType) newValue};
        });
      }
    }

    void removeReferenceType(String signature) {
        /*
         * There can be multiple classes with the same name. Since
         * we can't differentiate here, we first request actual info
         * and then remove all obsolete
         */

        List<ReferenceType> toRemove = findReferenceTypes(signature);
        if (toRemove.size() > 1) {
            toRemove = new ArrayList<>(toRemove);
            // no synchronization while waiting for retrieveClassesBySignature
            toRemove.removeAll(retrieveClassesBySignature(signature));
        }
        removeReferenceTypes(signature, toRemove);
    }

    private void removeReferenceTypes(String signature, List<ReferenceType> toRemove) {
      synchronized (state) {
        List<ReferenceType> referenceTypes = new ArrayList<>(findReferenceTypes(signature));
        for (ReferenceType t : toRemove) {
            ReferenceTypeImpl type = (ReferenceTypeImpl) t;
            referenceTypes.remove(t);
            typesByID.remove(type.ref());
            state.referenceTypeRemoved(type);
            if ((vm.traceFlags & VirtualMachine.TRACE_REFTYPES) != 0) {
                vm.printTrace("Uncaching ReferenceType, sig=" + signature + ", id=" + type.ref());
            }
        }
        switch (referenceTypes.size()) {
            case 0:
                typesBySignature.remove(signature);
                break;
            case 1:
                typesBySignature.put(signature, referenceTypes.get(0));
                break;
            default:
                typesBySignature.put(signature, referenceTypes.toArray(new ReferenceType[0]));
        }
      }
    }

    private List<ReferenceType> findReferenceTypes(String signature) {
      synchronized (state) {
        Object res = typesBySignature.get(signature);
        if (res instanceof ReferenceType) {
            return List.of((ReferenceType) res);
        } else if (res instanceof ReferenceType[]) {
            return List.of((ReferenceType[]) res);
        }
        assert res == null;
        return List.of();
      }
    }

    ReferenceTypeImpl referenceType(long ref, byte tag) {
        return referenceType(ref, tag, null);
    }

    ClassTypeImpl classType(long ref) {
        return (ClassTypeImpl)referenceType(ref, JDWP.TypeTag.CLASS, null);
    }

    InterfaceTypeImpl interfaceType(long ref) {
        return (InterfaceTypeImpl)referenceType(ref, JDWP.TypeTag.INTERFACE, null);
    }

    ArrayTypeImpl arrayType(long ref) {
        return (ArrayTypeImpl)referenceType(ref, JDWP.TypeTag.ARRAY, null);
    }

    ReferenceTypeImpl referenceType(long id, int tag, String signature) {
        if ((vm.traceFlags & VirtualMachine.TRACE_REFTYPES) != 0) {
            StringBuilder sb = new StringBuilder();
            sb.append("Looking up ");
            if (tag == JDWP.TypeTag.CLASS) {
                sb.append("Class");
            } else if (tag == JDWP.TypeTag.INTERFACE) {
                sb.append("Interface");
            } else if (tag == JDWP.TypeTag.ARRAY) {
                sb.append("ArrayType");
            } else {
                sb.append("UNKNOWN TAG: ").append(tag);
            }
            if (signature != null) {
                sb.append(", signature='").append(signature).append('\'');
            }
            sb.append(", id=").append(id);
            vm.printTrace(sb.toString());
        }
        if (id == 0) {
            return null;
        } else {
            ReferenceTypeImpl retType;
            synchronized (state) {
                retType = (ReferenceTypeImpl)typesByID.get(id);
                if (retType == null) {
                    retType = addReferenceType(id, tag, signature);
                }
                else if (signature != null) {
                    retType.setSignature(signature);
                }
            }
            return retType;
        }
    }

    private JDWP.VirtualMachine.Capabilities capabilities() {
        if (capabilities == null) {
            try {
                capabilities = JDWP.VirtualMachine
                                 .Capabilities.process(vm);
            } catch (JDWPException exc) {
                throw exc.toJDIException();
            }
        }
        return capabilities;
    }

    private JDWP.VirtualMachine.CapabilitiesNew capabilitiesNew() {
        if (capabilitiesNew == null) {
            try {
                capabilitiesNew = JDWP.VirtualMachine
                                 .CapabilitiesNew.process(vm);
            } catch (JDWPException exc) {
                throw exc.toJDIException();
            }
        }
        return capabilitiesNew;
    }

    private ModuleReference addModule(long id) {
      synchronized (state) {
        if (modulesByID == null) {
            modulesByID = new HashMap<>(77);
        }
        ModuleReference module = new ModuleReferenceImpl(vm, id);
        modulesByID.put(id, module);
        return module;
      }
    }

    ModuleReference getModule(long id) {
        if (id == 0) {
            return null;
        } else {
            ModuleReference module = null;
            synchronized (state) {
                if (modulesByID != null) {
                    module = modulesByID.get(id);
                }
                if (module == null) {
                    module = addModule(id);
                }
            }
            return module;
        }
    }

    private List<ModuleReference> retrieveAllModules() {
      synchronized (state) {
        ModuleReferenceImpl[] reqModules;
        try {
            reqModules = JDWP.VirtualMachine.AllModules.process(vm).modules;
        } catch (JDWPException exc) {
            throw exc.toJDIException();
        }
        return Arrays.stream(reqModules)
                .map(ObjectReferenceImpl::ref)
                .map(this::getModule)
                .collect(Collectors.toList());
      }
    }

    private List<ReferenceType> retrieveClassesBySignature(String signature) {
        if ((vm.traceFlags & VirtualMachine.TRACE_REFTYPES) != 0) {
            vm.printTrace("Retrieving matching ReferenceTypes, sig=" + signature);
        }
        JDWP.VirtualMachine.ClassesBySignature.ClassInfo[] cinfos;
        try {
            cinfos = JDWP.VirtualMachine.ClassesBySignature.
                                      process(vm, signature).classes;
        } catch (JDWPException exc) {
            throw exc.toJDIException();
        }

        int count = cinfos.length;
        List<ReferenceType> list = new ArrayList<>(count);

        // Hold lock during processing to improve performance
        synchronized (state) {
            for (JDWP.VirtualMachine.ClassesBySignature.ClassInfo ci : cinfos) {
                ReferenceTypeImpl type = referenceType(ci.typeID,
                        ci.refTypeTag,
                        signature);
                type.setStatus(ci.status);
                list.add(type);
            }
        }
        return list;
    }

    private void retrieveAllClasses1_4() {
        JDWP.VirtualMachine.AllClasses.ClassInfo[] cinfos;
        try {
            cinfos = JDWP.VirtualMachine.AllClasses.process(vm).classes;
        } catch (JDWPException exc) {
            throw exc.toJDIException();
        }

        // Hold lock during processing to improve performance
        // and to have safe check/set of retrievedAllTypes
        synchronized (state) {
            if (!retrievedAllTypes) {
                // Number of classes
                int count = cinfos.length;
                for (JDWP.VirtualMachine.AllClasses.ClassInfo ci : cinfos) {
                    ReferenceTypeImpl type = referenceType(ci.typeID,
                            ci.refTypeTag,
                            ci.signature);
                    type.setStatus(ci.status);
                }
                retrievedAllTypes = true;
            }
        }
    }

    private CompletableFuture<Void> retrieveAllClasses1_4Async() {
        return JDWP.VirtualMachine.AllClasses.processAsync(vm).thenAccept(allClasses -> {
            // Hold lock during processing to improve performance
            // and to have safe check/set of retrievedAllTypes
            synchronized (state) {
                if (!retrievedAllTypes) {
                    for (JDWP.VirtualMachine.AllClasses.ClassInfo ci : allClasses.classes) {
                        ReferenceTypeImpl type = referenceType(ci.typeID, ci.refTypeTag, ci.signature);
                        type.setStatus(ci.status);
                    }
                    retrievedAllTypes = true;
                }
            }
        });
    }

    private void retrieveAllClasses() {
        if ((vm.traceFlags & VirtualMachine.TRACE_REFTYPES) != 0) {
            vm.printTrace("Retrieving all ReferenceTypes");
        }

        if (!vm.canGet1_5LanguageFeatures()) {
            retrieveAllClasses1_4();
            return;
        }

        /*
         * To save time (assuming the caller will be
         * using then) we will get the generic sigs too.
         */
        JDWP.VirtualMachine.AllClasses.ClassInfo[] cinfos;
        try {
            cinfos = JDWP.VirtualMachine.AllClasses.process(vm).classes;
        } catch (JDWPException exc) {
            throw exc.toJDIException();
        }

        // Hold lock during processing to improve performance
        // and to have safe check/set of retrievedAllTypes
        synchronized (state) {
            if (!retrievedAllTypes) {
                // Number of classes
                for (JDWP.VirtualMachine.AllClasses.ClassInfo ci : cinfos) {
                    ReferenceTypeImpl type = referenceType(ci.typeID,
                            ci.refTypeTag,
                            ci.signature);
                    type.setStatus(ci.status);
                }
                retrievedAllTypes = true;
            }
        }
    }

    private CompletableFuture<Void> retrieveAllClassesAsync() {
        if ((vm.traceFlags & VirtualMachine.TRACE_REFTYPES) != 0) {
            vm.printTrace("Retrieving all ReferenceTypes");
        }

        if (!vm.canGet1_5LanguageFeatures()) {
            return retrieveAllClasses1_4Async();
        }

        return JDWP.VirtualMachine.AllClasses.processAsync(vm).thenAccept(allClassesWithGeneric -> {
            // Hold lock during processing to improve performance
            // and to have safe check/set of retrievedAllTypes
            synchronized (state) {
                if (!retrievedAllTypes) {
                    for (JDWP.VirtualMachine.AllClasses.ClassInfo ci : allClassesWithGeneric.classes) {
                        ReferenceTypeImpl type = referenceType(ci.typeID, ci.refTypeTag, ci.signature);
                        type.setStatus(ci.status);
                    }
                    retrievedAllTypes = true;
                }
            }
        });
    }

    void sendToTarget(Packet packet) {
        sentPackets.incrementAndGet();
        target.send(packet);
    }

    void waitForTargetReply(Packet packet) {
        waitPackets.incrementAndGet();
        target.waitForReply(packet);
        /*
         * If any object disposes have been batched up, send them now.
         */
        processBatchedDisposes();
    }

    Type findBootType(String signature) throws ClassNotLoadedException {
        // first check already loaded classes
        for (ReferenceType type : classesBySignature(signature)) {
            if (type.classLoader() == null) {
                return type;
            }
        }

        for (ReferenceType type : retrieveClassesBySignature(signature)) {
            if (type.classLoader() == null) {
                return type;
            }
        }

        JNITypeParser parser = new JNITypeParser(signature);
        throw new ClassNotLoadedException(parser.typeName(),
                                         "Type " + parser.typeName() + " not loaded");
    }

    BooleanType theBooleanType() {
        return theBooleanType;
    }

    ByteType theByteType() {
        return theByteType;
    }

    CharType theCharType() {
        return theCharType;
    }

    ShortType theShortType() {
        return theShortType;
    }

    IntegerType theIntegerType() {
        return theIntegerType;
    }

    LongType theLongType() {
        return theLongType;
    }

    FloatType theFloatType() {
        return theFloatType;
    }

    DoubleType theDoubleType() {
        return theDoubleType;
    }

    VoidType theVoidType() {
        return theVoidType;
    }

    PrimitiveType primitiveTypeMirror(byte tag) {
        switch (tag) {
            case JDWP.Tag.BOOLEAN:
                return theBooleanType();
            case JDWP.Tag.BYTE:
                return theByteType();
            case JDWP.Tag.CHAR:
                return theCharType();
            case JDWP.Tag.SHORT:
                return theShortType();
            case JDWP.Tag.INT:
                return theIntegerType();
            case JDWP.Tag.LONG:
                return theLongType();
            case JDWP.Tag.FLOAT:
                return theFloatType();
            case JDWP.Tag.DOUBLE:
                return theDoubleType();
            default:
                throw new IllegalArgumentException("Unrecognized primitive tag " + tag);
        }
    }

    private void processBatchedDisposes() {
        if (shutdown) {
            return;
        }

        JDWP.VirtualMachine.DisposeObjects.Request[] requests = null;
        synchronized(batchedDisposeRequests) {
            int size = batchedDisposeRequests.size();
            if (size >= DISPOSE_THRESHOLD) {
                if ((traceFlags & TRACE_OBJREFS) != 0) {
                    printTrace("Dispose threshold reached. Will dispose "
                               + size + " object references...");
                }
                requests = new JDWP.VirtualMachine.DisposeObjects.Request[size];
                for (int i = 0; i < requests.length; i++) {
                    SoftObjectReference ref = batchedDisposeRequests.get(i);
                    if ((traceFlags & TRACE_OBJREFS) != 0) {
                        printTrace("Disposing object " + ref.key() +
                                   " (ref count = " + ref.count() + ")");
                    }

                    // This is kludgy. We temporarily re-create an object
                    // reference so that we can correctly pass its id to the
                    // JDWP command.
                    requests[i] =
                        new JDWP.VirtualMachine.DisposeObjects.Request(
                            new ObjectReferenceImpl(this, ref.key()),
                            ref.count());
                }
                batchedDisposeRequests.clear();
            }
        }
        if (requests != null) {
            JDWP.VirtualMachine.DisposeObjects.processAsync(vm, requests);
        }
    }

    private void batchForDispose(SoftObjectReference ref) {
        if ((traceFlags & TRACE_OBJREFS) != 0) {
            printTrace("Batching object " + ref.key() +
                       " for dispose (ref count = " + ref.count() + ")");
        }
        batchedDisposeRequests.add(ref);
    }

    private void processQueue() {
        Reference<?> ref;
        //if ((traceFlags & TRACE_OBJREFS) != 0) {
        //    printTrace("Checking for softly reachable objects");
        //}
        boolean found = false;
        while ((ref = referenceQueue.poll()) != null) {
            SoftObjectReference softRef = (SoftObjectReference)ref;
            removeObjectMirror(softRef);
            batchForDispose(softRef);
            found = true;
        }

        if (found) {
            // we can do that right here as now it is async
            processBatchedDisposes();
        }
    }

    ObjectReferenceImpl objectMirror(long id, int tag) {

      synchronized (state) {
        // Handle any queue elements that are not strongly reachable
        processQueue();

        if (id == 0) {
            return null;
        }
        ObjectReferenceImpl object = null;
        Long key = id;

        /*
         * Attempt to retrieve an existing object reference
         */
        SoftObjectReference ref = objectsByID.get(key);
        if (ref != null) {
            object = ref.object();
        }

        /*
         * If the object wasn't in the table, or it's soft reference was
         * cleared, create a new instance.
         */
        if (object == null) {
            switch (tag) {
                case JDWP.Tag.OBJECT:
                    object = new ObjectReferenceImpl(vm, id);
                    break;
                case JDWP.Tag.STRING:
                    object = new StringReferenceImpl(vm, id);
                    break;
                case JDWP.Tag.ARRAY:
                    object = new ArrayReferenceImpl(vm, id);
                    break;
                case JDWP.Tag.THREAD:
                    ThreadReferenceImpl thread =
                        new ThreadReferenceImpl(vm, id);
                    thread.addListener(this);
                    object = thread;
                    break;
                case JDWP.Tag.THREAD_GROUP:
                    object = new ThreadGroupReferenceImpl(vm, id);
                    break;
                case JDWP.Tag.CLASS_LOADER:
                    object = new ClassLoaderReferenceImpl(vm, id);
                    break;
                case JDWP.Tag.CLASS_OBJECT:
                    object = new ClassObjectReferenceImpl(vm, id);
                    break;
                default:
                    throw new IllegalArgumentException("Invalid object tag: " + tag);
            }
            ref = new SoftObjectReference(key, object, referenceQueue);

            /*
             * If there was no previous entry in the table, we add one here
             * If the previous entry was cleared, we replace it here.
             */
            objectsByID.put(key, ref);
            if ((traceFlags & TRACE_OBJREFS) != 0) {
                printTrace("Creating new " +
                           object.getClass().getName() + " (id = " + id + ")");
            }
        } else {
            ref.incrementCount();
        }

        return object;
      }
    }

    private void removeObjectMirror(SoftObjectReference ref) {
      synchronized (state) {
        /*
         * This will remove the soft reference if it has not been
         * replaced in the cache.
         */
        objectsByID.remove(ref.key());
      }
    }

    ObjectReferenceImpl objectMirror(long id) {
        return objectMirror(id, JDWP.Tag.OBJECT);
    }

    StringReferenceImpl stringMirror(long id) {
        return (StringReferenceImpl)objectMirror(id, JDWP.Tag.STRING);
    }

    ArrayReferenceImpl arrayMirror(long id) {
       return (ArrayReferenceImpl)objectMirror(id, JDWP.Tag.ARRAY);
    }

    ThreadReferenceImpl threadMirror(long id) {
        return (ThreadReferenceImpl)objectMirror(id, JDWP.Tag.THREAD);
    }

    ThreadGroupReferenceImpl threadGroupMirror(long id) {
        return (ThreadGroupReferenceImpl)objectMirror(id,
                                                      JDWP.Tag.THREAD_GROUP);
    }

    ClassLoaderReferenceImpl classLoaderMirror(long id) {
        return (ClassLoaderReferenceImpl)objectMirror(id,
                                                      JDWP.Tag.CLASS_LOADER);
    }

    ClassObjectReferenceImpl classObjectMirror(long id) {
        return (ClassObjectReferenceImpl)objectMirror(id,
                                                      JDWP.Tag.CLASS_OBJECT);
    }

    ModuleReferenceImpl moduleMirror(long id) {
        return (ModuleReferenceImpl)getModule(id);
    }

    /*
     * Implementation of PathSearchingVirtualMachine
     */
    private JDWP.VirtualMachine.ClassPaths getClasspath() {
        if (pathInfo == null) {
            try {
                pathInfo = JDWP.VirtualMachine.ClassPaths.process(vm);
            } catch (JDWPException exc) {
                throw exc.toJDIException();
            }
        }
        return pathInfo;
    }

   public List<String> classPath() {
       return Arrays.asList(getClasspath().classpaths);
   }

   public List<String> bootClassPath() {
       return Collections.emptyList();
   }

   public String baseDirectory() {
       return getClasspath().baseDir;
   }

    public void setDefaultStratum(String stratum) {
        defaultStratum = stratum;
        if (stratum == null) {
            stratum = "";
        }
        try {
            JDWP.VirtualMachine.SetDefaultStratum.process(vm,
                                                          stratum);
        } catch (JDWPException exc) {
            throw exc.toJDIException();
        }
    }

    public String getDefaultStratum() {
        return defaultStratum;
    }

    ThreadGroup threadGroupForJDI() {
        return threadGroupForJDI;
    }

   private static class SoftObjectReference extends SoftReference<ObjectReferenceImpl> {
       int count;
       final Long key;

       SoftObjectReference(Long key, ObjectReferenceImpl mirror,
                           ReferenceQueue<ObjectReferenceImpl> queue) {
           super(mirror, queue);
           this.count = 1;
           this.key = key;
       }

       int count() {
           return count;
       }

       void incrementCount() {
           count++;
       }

       Long key() {
           return key;
       }

       ObjectReferenceImpl object() {
           return get();
       }
   }

    @SuppressWarnings("unused")
    public int getSentPacketsNumber() {
        return sentPackets.get();
    }

    @SuppressWarnings("unused")
    public int getWaitPacketsNumber() {
        return waitPackets.get();
    }

    /**
     * @return true if there's no debugger commands being sent/read or waited for
     */
    @SuppressWarnings("unused")
    public boolean isIdle() {
        return target.isIdle();
    }

    @SuppressWarnings("unused")
    public CompletableFuture<Long> measureLatency() {
        return target.measureLatency();
    }

    TargetVM targetVM() {
        return target;
    }
}
