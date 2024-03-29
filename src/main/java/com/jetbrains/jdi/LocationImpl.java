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

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.Location;
import com.sun.jdi.Method;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.VirtualMachine;

import java.util.concurrent.CompletableFuture;

public class LocationImpl extends MirrorImpl implements Location {
    private final ReferenceTypeImpl declaringType;
    private volatile Method method;
    private final long methodRef;
    private final long codeIndex;
    private LineInfo baseLineInfo = null;
    private LineInfo otherLineInfo = null;

    LocationImpl(VirtualMachine vm, Method method, long codeIndex) {
        super(vm);
        this.method = method;
        this.codeIndex = method.isNative() ? -1 : codeIndex;
        this.declaringType = (ReferenceTypeImpl) method.declaringType();
        this.methodRef = ((MethodImpl) method).ref();
    }

    /*
     * This constructor allows lazy creation of the method mirror. This
     * can be a performance savings if the method mirror does not yet
     * exist.
     */
    LocationImpl(VirtualMachine vm, ReferenceTypeImpl declaringType,
                 long methodRef, long codeIndex) {
        super(vm);

        this.method = null;
        this.codeIndex = codeIndex;
        this.declaringType = declaringType;
        this.methodRef = methodRef;
    }

    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Location)) return false;
        Location other = (Location) obj;
        if (!declaringType().equals(other.declaringType())) {
            return false;
        }
        // do not populate method if possible
        if (other instanceof LocationImpl) {
            if (methodRef != ((LocationImpl) other).methodRef) {
                return false;
            }
        }
        else {
            if (!method().equals(other.method())) {
                return false;
            }
        }
        return codeIndex() == other.codeIndex() && super.equals(obj);
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + declaringType.hashCode();
        result = 31 * result + (int) (methodRef ^ (methodRef >>> 32));
        result = 31 * result + (int) (codeIndex ^ (codeIndex >>> 32));
        return result;
    }

    public int compareTo(Location other) {
        int rc = method().compareTo(other.method());
        if (rc == 0) {
            long diff = codeIndex() - other.codeIndex();
            if (diff < 0)
                return -1;
            else if (diff > 0)
                return 1;
            else
                return 0;
        }
        return rc;
    }

    public ReferenceType declaringType() {
        return declaringType;
    }

    public Method method() {
        if (method == null) {
            method = declaringType.getMethodMirror(methodRef);
        }
        return method;
    }

    public CompletableFuture<Method> methodAsync() {
        if (method == null) {
            return declaringType.getMethodMirrorAsync(methodRef).thenApply(m -> method = m);
        }
        return CompletableFuture.completedFuture(method);
    }

    public long methodRef() {
        return methodRef;
    }

    public long codeIndex() {
        return codeIndex;
    }

    LineInfo getBaseLineInfo(SDE.Stratum stratum) {
        LineInfo lineInfo;

        /* check if there is cached info to use */
        if (baseLineInfo != null) {
            return baseLineInfo;
        }

        /* compute the line info */
        MethodImpl methodImpl = (MethodImpl)method();
        lineInfo = methodImpl.codeIndexToLineInfo(stratum, codeIndex());

        /* cache it */
        addBaseLineInfo(lineInfo);

        return lineInfo;
    }

    LineInfo getLineInfo(SDE.Stratum stratum) {
        LineInfo lineInfo;

        /* base stratum is done slightly differently */
        if (stratum.isJava()) {
            return getBaseLineInfo(stratum);
        }

        /* check if there is cached info to use */
        lineInfo = otherLineInfo; // copy because of concurrency
        if (lineInfo != null && stratum.id().equals(lineInfo.liStratum())) {
            return lineInfo;
        }

        int baseLineNumber = lineNumber(SDE.BASE_STRATUM_NAME);
        SDE.LineStratum lineStratum =
                  stratum.lineStratum(declaringType, baseLineNumber);

        if (lineStratum != null && lineStratum.lineNumber() != -1) {
            lineInfo = new StratumLineInfo(stratum.id(),
                                           lineStratum.lineNumber(),
                                           lineStratum.sourceName(),
                                           lineStratum.sourcePath());
        } else {
            /* find best match */
            MethodImpl methodImpl = (MethodImpl)method();
            lineInfo = methodImpl.codeIndexToLineInfo(stratum, codeIndex());
        }

        /* cache it */
        addStratumLineInfo(lineInfo);

        return lineInfo;
    }

    void addStratumLineInfo(LineInfo lineInfo) {
        otherLineInfo = lineInfo;
    }

    void addBaseLineInfo(LineInfo lineInfo) {
        baseLineInfo = lineInfo;
    }

    public String sourceName() throws AbsentInformationException {
        return sourceName(vm.getDefaultStratum());
    }

    public String sourceName(String stratumID)
                               throws AbsentInformationException {
        return sourceName(declaringType.stratum(stratumID));
    }

    String sourceName(SDE.Stratum stratum)
                               throws AbsentInformationException {
        return getLineInfo(stratum).liSourceName();
    }

    public String sourcePath() throws AbsentInformationException {
        return sourcePath(vm.getDefaultStratum());
    }

    public String sourcePath(String stratumID)
                               throws AbsentInformationException {
        return sourcePath(declaringType.stratum(stratumID));
    }

    String sourcePath(SDE.Stratum stratum)
                               throws AbsentInformationException {
        return getLineInfo(stratum).liSourcePath();
    }

    public int lineNumber() {
        return lineNumber(vm.getDefaultStratum());
    }

    public int lineNumber(String stratumID) {
        return lineNumber(declaringType.stratum(stratumID));
    }

    int lineNumber(SDE.Stratum stratum) {
        return getLineInfo(stratum).liLineNumber();
    }

    public String toString() {
        if (lineNumber() == -1) {
            return method().toString() + "+" + codeIndex();
        } else {
            return declaringType().name() + ":" + lineNumber();
        }
    }
}
