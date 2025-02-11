/*
 * Copyright (c) 1998, 2024, Oracle and/or its affiliates. All rights reserved.
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

import java.io.IOException;
import java.lang.reflect.InaccessibleObjectException;
import java.util.*;
import java.util.stream.Collectors;

import com.sun.jdi.JDIPermission;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.LaunchingConnector;
import com.sun.jdi.connect.ListeningConnector;
import com.sun.jdi.connect.spi.Connection;

/* Public for use by com.sun.jdi.Bootstrap */
public class VirtualMachineManagerImpl implements VirtualMachineManagerService {
    public static boolean TEST = false;
    private final List<Connector> connectors = new ArrayList<>();
    private LaunchingConnector defaultConnector = null;
    private final List<VirtualMachine> targets = new ArrayList<>();
    private final ThreadGroup mainGroupForJDI;
    private ResourceBundle messages = null;
    private int vmSequenceNumber = 0;
    private static final int majorVersion = 9;
    private static final int minorVersion = 0;

    private static final Object lock = new Object();
    private static VirtualMachineManagerImpl vmm;

    public static VirtualMachineManagerImpl virtualMachineManager() {
        @SuppressWarnings("removal")
        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            JDIPermission vmmPermission =
                new JDIPermission("virtualMachineManager");
            sm.checkPermission(vmmPermission);
        }
        synchronized (lock) {
            if (vmm == null) {
                vmm = new VirtualMachineManagerImpl();
            }
        }
        return vmm;
    }

    public static VirtualMachineManagerImpl testVirtualMachineManager() {
        TEST = true;
        return virtualMachineManager();
    }

    protected VirtualMachineManagerImpl() {
        if (TEST) {
            System.err.println("Initializing JB VirtualMachineManager");
        }

        /*
         * Create a top-level thread group
         */
        ThreadGroup top = Thread.currentThread().getThreadGroup();
        ThreadGroup parent;
        while ((parent = top.getParent()) != null) {
            top = parent;
        }
        mainGroupForJDI = new ThreadGroup(top, "JDI main");


        /*
         * Create the connectors
         */
        addConnector(new SunCommandLineLauncher());
        addConnector(new RawCommandLineLauncher());
        addConnector(new SocketAttachingConnector());
        addConnector(new SocketListeningConnector());
        addConnector(new ProcessAttachingConnector());
        if (isWindows()) {
            try {
                addConnector(new SharedMemoryListeningConnector());
                addConnector(new SharedMemoryAttachingConnector());
            } catch (ReflectiveOperationException | InaccessibleObjectException x) {
                x.printStackTrace();
            }
        }

        // Set the default launcher. In order to be compatible
        // 1.2/1.3/1.4 we try to make the default launcher
        // "com.sun.jdi.CommandLineLaunch". If this connector
        // isn't found then we arbitarly pick the first connector.
        //
        boolean found = false;
        List<LaunchingConnector> launchers = launchingConnectors();
        for (LaunchingConnector lc: launchers) {
            if (lc.name().equals(connectorName("com.jetbrains.jdi.CommandLineLaunch"))) {
                setDefaultConnector(lc);
                found = true;
                break;
            }
        }
        if (!found && launchers.size() > 0) {
            setDefaultConnector(launchers.get(0));
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.US).startsWith("windows");
    }

    public LaunchingConnector defaultConnector() {
        if (defaultConnector == null) {
            throw new Error("no default LaunchingConnector");
        }
        return defaultConnector;
    }

    public void setDefaultConnector(LaunchingConnector connector) {
        defaultConnector = connector;
    }

    public List<LaunchingConnector> launchingConnectors() {
        return connectors.stream()
                .filter(connector -> connector instanceof LaunchingConnector)
                .map(connector -> (LaunchingConnector) connector)
                .collect(Collectors.toUnmodifiableList());
    }

    public List<AttachingConnector> attachingConnectors() {
        return connectors.stream()
                .filter(connector -> connector instanceof AttachingConnector)
                .map(connector -> (AttachingConnector) connector)
                .collect(Collectors.toUnmodifiableList());
    }

    public List<ListeningConnector> listeningConnectors() {
        return connectors.stream()
                .filter(connector -> connector instanceof ListeningConnector)
                .map(connector -> (ListeningConnector) connector)
                .collect(Collectors.toUnmodifiableList());
    }

    public List<Connector> allConnectors() {
        return Collections.unmodifiableList(connectors);
    }

    public List<VirtualMachine> connectedVirtualMachines() {
        return Collections.unmodifiableList(targets);
    }

    public void addConnector(Connector connector) {
        connectors.add(connector);
    }

    public void removeConnector(Connector connector) {
        connectors.remove(connector);
    }

    public synchronized VirtualMachine createVirtualMachine(
                                        Connection connection,
                                        Process process) throws IOException {

        if (!connection.isOpen()) {
            throw new IllegalStateException("connection is not open");
        }

        if (TEST) {
            System.err.println("Creating JB VirtualMachine");
        }

        VirtualMachine vm;
        try {
            vm = new VirtualMachineImpl(this, connection, process,
                                                   ++vmSequenceNumber);
        } catch (VMDisconnectedException e) {
            throw new IOException(e);
        }
        targets.add(vm);
        return vm;
    }

    public VirtualMachine createVirtualMachine(Connection connection) throws IOException {
        return createVirtualMachine(connection, null);
    }

    public void addVirtualMachine(VirtualMachine vm) {
        targets.add(vm);
    }

    void disposeVirtualMachine(VirtualMachine vm) {
        targets.remove(vm);
    }

    public int majorInterfaceVersion() {
        return majorVersion;
    }

    public int minorInterfaceVersion() {
        return minorVersion;
    }

    ThreadGroup mainGroupForJDI() {
        return mainGroupForJDI;
    }

    String getString(String key) {
        if (messages == null) {
            messages = ResourceBundle.getBundle("com.jetbrains.jdi.resources.jdi");
        }
        return messages.getString(key);
    }

    static String connectorName(String name) {
        return TEST ? name.replace("jetbrains", "sun") : name;
    }
}
