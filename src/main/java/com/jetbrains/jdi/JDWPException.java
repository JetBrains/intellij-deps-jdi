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

import com.sun.jdi.ClassNotPreparedException;
import com.sun.jdi.InconsistentDebugInfoException;
import com.sun.jdi.InternalException;
import com.sun.jdi.InvalidModuleException;
import com.sun.jdi.InvalidStackFrameException;
import com.sun.jdi.ObjectCollectedException;
import com.sun.jdi.OpaqueFrameException;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.VMOutOfMemoryException;

import java.util.stream.IntStream;

class JDWPException extends Exception {

    private static final long serialVersionUID = -6321344442751299874L;

    final short errorCode;

    JDWPException(short errorCode) {
        super();
        this.errorCode = errorCode;
    }

    short errorCode() {
        return errorCode;
    }

    RuntimeException toJDIException() {
        switch (errorCode) {
            case JDWP.Error.INVALID_OBJECT:
                return new ObjectCollectedException();
            case JDWP.Error.INVALID_MODULE:
                return new InvalidModuleException();
            case JDWP.Error.VM_DEAD:
                return new VMDisconnectedException();
            case JDWP.Error.OUT_OF_MEMORY:
                return new VMOutOfMemoryException();
            case JDWP.Error.CLASS_NOT_PREPARED:
                return new ClassNotPreparedException();
            case JDWP.Error.INVALID_FRAMEID:
            case JDWP.Error.NOT_CURRENT_FRAME:
                return new InvalidStackFrameException();
            case JDWP.Error.NOT_IMPLEMENTED:
                return new UnsupportedOperationException();
            case JDWP.Error.INVALID_INDEX:
            case JDWP.Error.INVALID_LENGTH:
                return new IndexOutOfBoundsException();
            case JDWP.Error.TYPE_MISMATCH:
                return new InconsistentDebugInfoException();
            case JDWP.Error.INVALID_THREAD:
                return new IllegalThreadStateException();
            case JDWP.Error.OPAQUE_FRAME:
                return new OpaqueFrameException();
            default:
                InternalException internalException = new InternalException("Unexpected JDWP Error: " + errorCode, errorCode);
                if (errorCode == JDWP.Error.INTERNAL) {
                    internalException.initCause(getCause());
                }
                return internalException;
        }
    }

    public static boolean isOfType(Throwable throwable, int... types) {
        throwable = AsyncUtils.unwrap(throwable);
        if (throwable instanceof InternalException) {
            int errorCode = ((InternalException) throwable).errorCode();
            return IntStream.of(types).anyMatch(t -> t == errorCode);
        }
        return false;
    }

}
