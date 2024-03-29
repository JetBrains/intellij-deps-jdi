/*
 * Copyright (c) 1999, 2017, Oracle and/or its affiliates. All rights reserved.
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
import java.util.Map;

import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;
import com.sun.jdi.connect.Transport;
import com.sun.jdi.connect.VMStartException;
import com.sun.jdi.connect.spi.TransportService;

public class RawCommandLineLauncher extends AbstractLauncher {

    private static final String ARG_COMMAND = "command";
    private static final String ARG_ADDRESS = "address";
    private static final String ARG_QUOTE   = "quote";

    TransportService transportService;
    Transport transport;

    public TransportService transportService() {
        return transportService;
    }

    public Transport transport() {
        return transport;
    }

    public RawCommandLineLauncher() {
        super();

        try {
            transportService = SharedMemoryAttachingConnector.createSharedMemoryTransportService();
            transport = () -> "dt_shmem";
        } catch (ClassNotFoundException ignored) {
        } catch (Exception x) {
            x.printStackTrace();
        }

        if (transportService == null) {
            transportService = new SocketTransportService();
            transport = () -> "dt_socket";
        }

        addStringArgument(
                ARG_COMMAND,
                getString("raw.command.label"),
                getString("raw.command"),
                "",
                true);
        addStringArgument(
                ARG_QUOTE,
                getString("raw.quote.label"),
                getString("raw.quote"),
                "\"",
                true);

        addStringArgument(
                ARG_ADDRESS,
                getString("raw.address.label"),
                getString("raw.address"),
                "",
                true);
    }


    public VirtualMachine
        launch(Map<String, ? extends Connector.Argument> arguments)
        throws IOException, IllegalConnectorArgumentsException,
               VMStartException
    {
        String command = argument(ARG_COMMAND, arguments).value();
        String address = argument(ARG_ADDRESS, arguments).value();
        String quote = argument(ARG_QUOTE, arguments).value();

        if (quote.length() > 1) {
            throw new IllegalConnectorArgumentsException("Invalid length",
                                                         ARG_QUOTE);
        }

        TransportService.ListenKey listener = transportService.startListening(address);

        try {
            return launch(tokenizeCommand(command, quote.charAt(0)),
                          address, listener, transportService);
        } finally {
            transportService.stopListening(listener);
        }
    }

    public String name() {
        return VirtualMachineManagerImpl.connectorName("com.jetbrains.jdi.RawCommandLineLaunch");
    }

    public String description() {
        return getString("raw.description");
    }
}
