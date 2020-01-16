/*
 * Copyright (c) 2005, 2017, Oracle and/or its affiliates. All rights reserved.
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
import java.util.Map;
import java.util.Properties;

import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;
import com.sun.jdi.connect.Transport;
import com.sun.jdi.connect.spi.Connection;
import com.sun.jdi.connect.spi.TransportService;

/*
 * An AttachingConnector that connects to a debuggee by specifying the process
 * id (pid) as the connector argument. If the process is a debuggee listening
 * on a transport address then this connector reads the transport address
 * and attempts to attach to it using the appropriate transport.
 */

public class ProcessAttachingConnector
        extends ConnectorImpl implements AttachingConnector
{
    /*
     * The arguments that this connector supports
     */
    static final String ARG_PID = "pid";
    static final String ARG_TIMEOUT = "timeout";

    com.sun.tools.attach.VirtualMachine vm;
    final Transport transport;

    public ProcessAttachingConnector() {
        addStringArgument(
            ARG_PID,
            getString("process_attaching.pid.label"),
            getString("process_attaching.pid"),
            "",
            true);

        addIntegerArgument(
            ARG_TIMEOUT,
            getString("generic_attaching.timeout.label"),       // use generic keys to keep
            getString("generic_attaching.timeout"),             // resource bundle small
            "",
            false,
            0, Integer.MAX_VALUE);

        transport = () -> "local";
    }

    /**
     * Attach to a target VM using the specified address and Connector arguments.
     */
    public VirtualMachine attach(Map<String,? extends Connector.Argument> args)
                throws IOException, IllegalConnectorArgumentsException
    {
        String pid = argument(ARG_PID, args).value();
        String t = argument(ARG_TIMEOUT, args).value();
        int timeout = 0;
        if (t.length() > 0) {
            timeout = Integer.decode(t);
        }

        // Use Attach API to attach to target VM and read value of
        // sun.jdwp.listenAddress property.

        String address;
        com.sun.tools.attach.VirtualMachine vm = null;
        try {
            vm = com.sun.tools.attach.VirtualMachine.attach(pid);
            Properties props = vm.getAgentProperties();
            address = props.getProperty("sun.jdwp.listenerAddress");
        } catch (Exception x) {
            throw new IOException(x.getMessage());
        } finally {
            if (vm != null) vm.detach();
        }

        // check that the property value is formatted correctly

        if (address == null) {
            throw new IOException("Not a debuggee, or not listening for debugger to attach");
        }
        int pos = address.indexOf(':');
        if (pos < 1) {
            throw new IOException("Unable to determine transport endpoint");
        }

        // parse into transport library name and address

        final String lib = address.substring(0, pos);
        address = address.substring(pos+1);

        TransportService ts = null;
        if (lib.equals("dt_socket")) {
            ts = new SocketTransportService();
        } else {
            if (lib.equals("dt_shmem")) {
                try {
                    ts = SharedMemoryAttachingConnector.createSharedMemoryTransportService();
                } catch (Exception x) { }
            }
        }
        if (ts == null) {
            throw new IOException("Transport " + lib + " not recognized");
        }

        // connect to the debuggee

        Connection connection = ts.attach(address, timeout, 0);
        return VirtualMachineManagerImpl.virtualMachineManager().createVirtualMachine(connection);
    }

    public String name() {
        return VirtualMachineManagerImpl.connectorName("com.jetbrains.jdi.ProcessAttach");
    }

    public String description() {
        return getString("process_attaching.description");
    }

    public Transport transport() {
        if (transport == null) {
            return () -> "local";
        }
        return transport;
    }
}
