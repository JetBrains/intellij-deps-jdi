/*
 * Copyright (c) 1998, 2020, Oracle and/or its affiliates. All rights reserved.
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

import com.sun.jdi.InvalidTypeException;
import com.sun.jdi.Type;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.VoidValue;

public class VoidValueImpl extends ValueImpl implements VoidValue {

    VoidValueImpl(VirtualMachine aVm) {
        super(aVm);
    }

    public boolean equals(Object obj) {
        return (obj instanceof VoidValue) && super.equals(obj);
    }

    public int hashCode() {
        /*
         * TO DO: Better hash code
         */
        return 47245;
    }

    public Type type() {
        return vm.theVoidType();
    }

    ValueImpl prepareForAssignmentTo(ValueContainer destination)
        throws InvalidTypeException
    {
        JNITypeParser sig = new JNITypeParser(destination.signature());
        if (sig.isVoid()) {
            return this;
        }
        throw new InvalidTypeException();
    }

    public String toString() {
        return "<void value>";
    }

    byte typeValueKey() {
        return JDWP.Tag.VOID;
    }
}
