package com.jetbrains.jdi;

import com.sun.jdi.ArrayType;
import com.sun.jdi.ClassObjectReference;
import com.sun.jdi.ClassType;
import com.sun.jdi.Method;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.LaunchingConnector;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A class prepare event has no thread if a debugger system thread prepared the class.
 * For example, `ArrayType.newInstance` prepares the element class in the JDWP thread when the class is loaded but not prepared yet.
 */
class ClassPrepareEventWithoutThreadTest {
    private static final String DEBUGGEE = ClassPrepareEventWithoutThreadDebuggee.class.getName();
    private static final String PREPARED = ClassPrepareEventWithoutThreadDebuggee.Prepared.class.getName();
    private static final long TIMEOUT_MS = 30_000;

    @Test
    void eventSetWithoutThreadCanBePrinted() throws Exception {
        LaunchingConnector connector = VirtualMachineManagerImpl.virtualMachineManager().defaultConnector();
        Map<String, Connector.Argument> arguments = connector.defaultArguments();
        arguments.get("main").setValue(DEBUGGEE);
        arguments.get("options").setValue("-cp \"" + System.getProperty("java.class.path") + "\"");
        VirtualMachine vm = connector.launch(arguments);
        try {
            EventRequestManager requests = vm.eventRequestManager();
            ClassPrepareRequest debuggeePrepareRequest = requests.createClassPrepareRequest();
            debuggeePrepareRequest.addClassFilter(DEBUGGEE);
            debuggeePrepareRequest.enable();
            // awaitEventSet resumes the set of VMStartEvent, which holds the debuggee at its start

            EventSet debuggeePrepared = awaitEventSet(vm, ClassPrepareEvent.class);
            ReferenceType debuggee = findEvent(debuggeePrepared, ClassPrepareEvent.class).referenceType();
            BreakpointRequest breakpointRequest = requests.createBreakpointRequest(debuggee.methodsByName("stop").get(0).location());
            breakpointRequest.enable();
            debuggeePrepared.resume();

            EventSet breakpointHit = awaitEventSet(vm, BreakpointEvent.class);
            ThreadReference thread = findEvent(breakpointHit, BreakpointEvent.class).thread();

            ClassPrepareRequest prepareRequest = requests.createClassPrepareRequest();
            prepareRequest.addClassFilter(PREPARED);
            prepareRequest.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
            prepareRequest.enable();

            // Loading the array class loads the element class, but does not prepare it
            ClassType classClass = (ClassType) vm.classesByName("java.lang.Class").get(0);
            Method forName = classClass.concreteMethodByName("forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;");
            ClassObjectReference arrayClass = (ClassObjectReference) classClass.invokeMethod(
                    thread, forName, List.of(vm.mirrorOf("[L" + PREPARED + ";"), vm.mirrorOf(true), debuggee.classLoader()),
                    ClassType.INVOKE_SINGLE_THREADED);
            // The JDWP thread prepares the element class to create the array
            ((ArrayType) arrayClass.reflectedType()).newInstance(1);

            EventSet prepared = awaitEventSet(vm, ClassPrepareEvent.class);
            ClassPrepareEvent event = findEvent(prepared, ClassPrepareEvent.class);
            assertEquals(PREPARED, event.referenceType().name());
            assertNull(event.thread(), "A debugger system thread must prepare the class");
            assertEquals(EventRequest.SUSPEND_ALL, prepared.suspendPolicy());
            String text = assertDoesNotThrow(prepared::toString);
            assertTrue(text.contains("ClassPrepareEvent(ER=") && text.contains("in a debugger system thread"), text);
            prepared.resume();
            breakpointHit.resume();
        }
        finally {
            try {
                vm.exit(0);
            }
            catch (VMDisconnectedException ignored) {
                // The debuggee has already finished
            }
        }
    }

    private static EventSet awaitEventSet(VirtualMachine vm, Class<? extends Event> eventClass) throws InterruptedException {
        while (true) {
            EventSet eventSet = vm.eventQueue().remove(TIMEOUT_MS);
            assertNotNull(eventSet, "No " + eventClass.getSimpleName() + " in " + TIMEOUT_MS + " ms");
            for (Event event : eventSet) {
                if (eventClass.isInstance(event)) return eventSet;
            }
            eventSet.resume();
        }
    }

    private static <T extends Event> T findEvent(EventSet eventSet, Class<T> eventClass) {
        for (Event event : eventSet) {
            if (eventClass.isInstance(event)) return eventClass.cast(event);
        }
        throw new AssertionError("No " + eventClass.getSimpleName() + " in " + eventSet);
    }
}
