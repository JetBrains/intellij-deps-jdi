/*
 * Copyright (c) 1998, 2017, Oracle and/or its affiliates. All rights reserved.
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

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.sun.jdi.ThreadGroupReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VirtualMachine;

public class ThreadGroupReferenceImpl extends ObjectReferenceImpl
    implements ThreadGroupReference
{
    // Cached components that cannot change
    volatile String name;
    ThreadGroupReference parent;
    boolean triedParent;

    // This is cached only while the VM is suspended
    private static class Cache extends ObjectReferenceImpl.Cache {
        JDWP.ThreadGroupReference.Children kids = null;
    }

    protected ObjectReferenceImpl.Cache newCache() {
        return new Cache();
    }

    ThreadGroupReferenceImpl(VirtualMachine aVm, long aRef) {
        super(aVm, aRef);
        vm.state().addListener(this);
    }

    protected String description() {
        return "ThreadGroupReference " + uniqueID();
    }

    public String name() {
        if (name == null) {
            // Does not need synchronization, since worst-case
            // static info is fetched twice (Thread group name
            // cannot change)
            try {
                name = JDWP.ThreadGroupReference.Name.
                                     process(vm, this).groupName;
            } catch (JDWPException exc) {
                throw exc.toJDIException();
            }
        }
        return name;
    }

    @SuppressWarnings("unused")
    public CompletableFuture<String> nameAsync() {
        if (name != null) {
            return CompletableFuture.completedFuture(name);
        }
        return JDWP.ThreadGroupReference.Name.processAsync(vm, this).thenApply(n -> name = n.groupName);
    }

    public ThreadGroupReference parent() {
        if (!triedParent) {
            // Does not need synchronization, since worst-case
            // static info is fetched twice (Thread group parent cannot
            // change)
            try {
                parent = JDWP.ThreadGroupReference.Parent.
                                 process(vm, this).parentGroup;
                triedParent = true;
            } catch (JDWPException exc) {
                throw exc.toJDIException();
            }
        }
       return parent;
    }

    public void suspend() {
        for (ThreadReference thread : threads()) {
            thread.suspend();
        }

        for (ThreadGroupReference threadGroup : threadGroups()) {
            threadGroup.suspend();
        }
    }

    public void resume() {
        for (ThreadReference thread : threads()) {
            thread.resume();
        }

        for (ThreadGroupReference threadGroup : threadGroups()) {
            threadGroup.resume();
        }
    }

    private JDWP.ThreadGroupReference.Children kids() {
        JDWP.ThreadGroupReference.Children kids = null;
        try {
            Cache local = (Cache)getCache();

            if (local != null) {
                kids = local.kids;
            }
            if (kids == null) {
                kids = JDWP.ThreadGroupReference.Children
                                                  .process(vm, this);
                if (local != null) {
                    local.kids = kids;
                    if ((vm.traceFlags & VirtualMachine.TRACE_OBJREFS) != 0) {
                        vm.printTrace(description() +
                                      " temporarily caching children ");
                    }
                }
            }
        } catch (JDWPException exc) {
            throw exc.toJDIException();
        }
        return kids;
    }

    public List<ThreadReference> threads() {
        return Arrays.asList(kids().childThreads);
    }

    public List<ThreadGroupReference> threadGroups() {
        return Arrays.asList(kids().childGroups);
    }

    public String toString() {
        return "instance of " + referenceType().name() +
               "(name='" + name() + "', " + "id=" + uniqueID() + ")";
    }

    byte typeValueKey() {
        return JDWP.Tag.THREAD_GROUP;
    }
}
