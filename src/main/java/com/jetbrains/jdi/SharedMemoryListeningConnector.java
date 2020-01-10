/*
 * Copyright (c) 1999, 2013, Oracle and/or its affiliates. All rights reserved.
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

import com.sun.jdi.connect.IllegalConnectorArgumentsException;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Map;

/*
 * A ListeningConnector based on the SharedMemoryTransportService
 */
public class SharedMemoryListeningConnector extends GenericListeningConnector {

    static final String ARG_NAME = "name";

    public SharedMemoryListeningConnector() throws ReflectiveOperationException {
        super(SharedMemoryAttachingConnector.createSharedMemoryTransportService());

        addStringArgument(
            ARG_NAME,
            getString("memory_listening.name.label"),
            getString("memory_listening.name"),
            "",
            false);

        transport = () -> {
            return "dt_shmem";              // compatibility
        };
    }

    // override startListening so that "name" argument can be
    // converted into "address" argument

    public String
        startListening(Map<String, ? extends Argument> args)
        throws IOException, IllegalConnectorArgumentsException
    {
        String name = argument(ARG_NAME, args).value();

        // if the name argument isn't specified then we use the default
        // address for the transport service.
        if (name.length() == 0) {
            assert transportService.getClass().getSimpleName().equals("SharedMemoryTransportService");
            try {
                Method defaultAddressMethod = transportService.getClass().getDeclaredMethod("defaultAddress");
                defaultAddressMethod.setAccessible(true);
                name = (String) defaultAddressMethod.invoke(transportService);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        return super.startListening(name, args);
    }

    public String name() {
        return "com.jetbrains.jdi.SharedMemoryListen";
    }

    public String description() {
       return getString("memory_listening.description");
    }
}
