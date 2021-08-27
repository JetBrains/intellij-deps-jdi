/*
 * Copyright (c) 2000, 2017, Oracle and/or its affiliates. All rights reserved.
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
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Location;
import com.sun.jdi.VirtualMachine;

import java.lang.ref.SoftReference;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

/**
 * Represents methods with method bodies.
 * That is, non-native non-abstract methods.
 * Private to MethodImpl.
 */
public class ConcreteMethodImpl extends MethodImpl {

    /*
     * A subset of the line number info that is softly cached
     */
    static private class SoftLocationXRefs {
        final String stratumID;                        // The stratum of this information
        final Map<Integer, List<Location>> lineMapper; // Maps line number to location(s)
        final List<Location> lineLocations;            // List of locations ordered by code index

        /*
         * Note: these do not necessarily correspond to
         * the line numbers of the first and last elements
         * in the lineLocations list. Use these only for bounds
         * checking and with lineMapper.
         */
        @SuppressWarnings("unused")
        final int lowestLine;
        @SuppressWarnings("unused")
        final int highestLine;

        SoftLocationXRefs(String stratumID, Map<Integer, List<Location>> lineMapper,
                          List<Location> lineLocations, int lowestLine, int highestLine) {
            this.stratumID = stratumID;
            this.lineMapper = Collections.unmodifiableMap(lineMapper);
            this.lineLocations = Collections.unmodifiableList(lineLocations);
            this.lowestLine = lowestLine;
            this.highestLine = highestLine;
        }
    }

    private volatile Location location = null;
    private volatile SoftReference<SoftLocationXRefs> softBaseLocationXRefsRef;
    private volatile SoftReference<SoftLocationXRefs> softOtherLocationXRefsRef;
    private volatile SoftReference<List<LocalVariable>> variablesRef = null;
    private volatile boolean absentVariableInformation = false;
    private volatile long firstIndex = -1;
    private volatile long lastIndex = -1;
    private volatile SoftReference<byte[]> bytecodesRef = null;
    private volatile int argSlotCount = -1;

    ConcreteMethodImpl(VirtualMachine vm, ReferenceTypeImpl declaringType,
                       long ref, String name, String signature,
                       String genericSignature, int modifiers)
    {
        // The generic signature is set when this is created
        super(vm, declaringType, ref, name, signature, genericSignature, modifiers);
    }

    public Location location() {
        if (location == null) {
            getBaseLocations();
        }
        return location;
    }

    List<Location> sourceNameFilter(List<Location> list,
                                    SDE.Stratum stratum,
                                    String sourceName)
                            throws AbsentInformationException {
        if (sourceName == null) {
            return list;
        } else {
            /* needs sourceName filteration */
            List<Location> locs = new ArrayList<>();
            for (Location loc : list) {
                if (((LocationImpl)loc).sourceName(stratum).equals(sourceName)) {
                    locs.add(loc);
                }
            }
            return locs;
        }
    }

    List<Location> allLineLocations(SDE.Stratum stratum,
                                    String sourceName)
                            throws AbsentInformationException {
        List<Location> lineLocations = getLocations(stratum).lineLocations;

        if (lineLocations.size() == 0) {
            throw new AbsentInformationException();
        }

        return Collections.unmodifiableList(
            sourceNameFilter(lineLocations, stratum, sourceName));
    }

    CompletableFuture<List<Location>> allLineLocationsAsync(SDE.Stratum stratum, String sourceName) {
        return getLocationsAsync(stratum).thenApply(info -> {
            List<Location> lineLocations = info.lineLocations;
            if (lineLocations.size() == 0) {
                throw new CompletionException(new AbsentInformationException());
            }

            try {
                return Collections.unmodifiableList(sourceNameFilter(lineLocations, stratum, sourceName));
            } catch (AbsentInformationException e) {
                throw new CompletionException(e);
            }
        });
    }

    List<Location> locationsOfLine(SDE.Stratum stratum,
                                   String sourceName,
                                   int lineNumber)
                            throws AbsentInformationException {
        SoftLocationXRefs info = getLocations(stratum);

        if (info.lineLocations.size() == 0) {
            throw new AbsentInformationException();
        }

        /*
         * Find the locations which match the line number
         * passed in.
         */
        List<Location> list = info.lineMapper.get(lineNumber);

        if (list == null) {
            list = new ArrayList<>(0);
        }
        return Collections.unmodifiableList(
            sourceNameFilter(list, stratum, sourceName));
    }

    CompletableFuture<List<Location>> locationsOfLineAsync(SDE.Stratum stratum,
                                                           String sourceName,
                                                           int lineNumber) {
        return getLocationsAsync(stratum).thenApply(info -> {
            if (info.lineLocations.size() == 0) {
                throw new CompletionException(new AbsentInformationException());
            }

            /*
             * Find the locations which match the line number
             * passed in.
             */
            List<Location> list = info.lineMapper.get(lineNumber);

            if (list == null) {
                list = Collections.emptyList();
            }
            try {
                return Collections.unmodifiableList(sourceNameFilter(list, stratum, sourceName));
            } catch (AbsentInformationException e) {
                throw new CompletionException(e);
            }
        });
    }

    public Location locationOfCodeIndex(long codeIndex) {
        if (firstIndex == -1) {
            getBaseLocations();
        }

        /*
         * Check for invalid code index.
         */
        if (codeIndex < firstIndex || codeIndex > lastIndex) {
            return null;
        }

        return new LocationImpl(virtualMachine(), this, codeIndex);
    }

    LineInfo codeIndexToLineInfo(SDE.Stratum stratum,
                                 long codeIndex) {
        if (firstIndex == -1) {
            getBaseLocations();
        }

        /*
         * Check for invalid code index.
         */
        if (codeIndex < firstIndex || codeIndex > lastIndex) {
            throw new InternalError("Location with invalid code index");
        }

        List<Location> lineLocations = getLocations(stratum).lineLocations;

        /*
         * Check for absent line numbers.
         */
        if (lineLocations.size() == 0) {
            return super.codeIndexToLineInfo(stratum, codeIndex);
        }

        Iterator<Location> iter = lineLocations.iterator();
        /*
         * Treat code before the beginning of the first line table
         * entry as part of the first line.  javac will generate
         * code like this for some local classes. This "prolog"
         * code contains assignments from locals in the enclosing
         * scope to synthetic fields in the local class.  Same for
         * other language prolog code.
         */
        LocationImpl bestMatch = (LocationImpl)iter.next();
        while (iter.hasNext()) {
            LocationImpl current = (LocationImpl)iter.next();
            if (current.codeIndex() > codeIndex) {
                break;
            }
            bestMatch = current;
        }
        return bestMatch.getLineInfo(stratum);
    }

    public List<LocalVariable> variables() throws AbsentInformationException {
        return getVariables();
    }

    public CompletableFuture<List<LocalVariable>> variablesAsync() {
        return getVariablesAsync();
    }

    public List<LocalVariable> variablesByName(String name) throws AbsentInformationException {
        return getVariables().stream().filter(v -> v.name().equals(name)).collect(Collectors.toList());
    }

    public CompletableFuture<List<LocalVariable>> variablesByNameAsync(String name) {
        return getVariablesAsync().thenApply(variables ->
                variables.stream().filter(v -> v.name().equals(name)).collect(Collectors.toList()));
    }

    public List<LocalVariable> arguments() throws AbsentInformationException {
        return getVariables().stream().filter(LocalVariable::isArgument).collect(Collectors.toList());
    }

    public CompletableFuture<List<LocalVariable>> argumentsAsync() {
        return getVariablesAsync().thenApply(variables ->
                variables.stream().filter(LocalVariable::isArgument).collect(Collectors.toList()));
    }

    public byte[] bytecodes() {
        byte[] bytecodes = (bytecodesRef == null) ? null :
                                     bytecodesRef.get();
        if (bytecodes == null) {
            try {
                bytecodes = JDWP.Method.Bytecodes.
                                 process(vm, declaringType, ref).bytes;
            } catch (JDWPException exc) {
                throw exc.toJDIException();
            }
            bytecodesRef = new SoftReference<>(bytecodes);
        }
        /*
         * Arrays are always modifiable, so it is a little unsafe
         * to return the cached bytecodes directly; instead, we
         * make a clone at the cost of using more memory.
         */
        return bytecodes.clone();
    }

    public CompletableFuture<byte[]> bytecodesAsync() {
        byte[] bytecodes = (bytecodesRef == null) ? null : bytecodesRef.get();
        if (bytecodes != null) {
            return CompletableFuture.completedFuture(bytecodes.clone());
        }
        return JDWP.Method.Bytecodes.processAsync(vm, declaringType, ref).thenApply(b -> {
            bytecodesRef = new SoftReference<>(b.bytes);
            /*
             * Arrays are always modifiable, so it is a little unsafe
             * to return the cached bytecodes directly; instead, we
             * make a clone at the cost of using more memory.
             */
            return b.bytes.clone();
        });
    }

    int argSlotCount() throws AbsentInformationException {
        if (argSlotCount == -1) {
            getVariables();
        }
        return argSlotCount;
    }

    private SoftLocationXRefs getLocations(SDE.Stratum stratum) {
        if (stratum.isJava()) {
            return getBaseLocations();
        }
        String stratumID = stratum.id();
        SoftLocationXRefs info =
            (softOtherLocationXRefsRef == null) ? null :
               softOtherLocationXRefsRef.get();
        if (info != null && info.stratumID.equals(stratumID)) {
            return info;
        }

        List<Location> lineLocations = new ArrayList<>();
        Map<Integer, List<Location>> lineMapper = new HashMap<>();
        int lowestLine = -1;
        int highestLine = -1;
        SDE.LineStratum lastLineStratum = null;
        SDE.Stratum baseStratum = declaringType.stratum(SDE.BASE_STRATUM_NAME);
        for (Location lineLocation : getBaseLocations().lineLocations) {
            LocationImpl loc = (LocationImpl) lineLocation;
            int baseLineNumber = loc.lineNumber(baseStratum);
            SDE.LineStratum lineStratum =
                    stratum.lineStratum(declaringType, baseLineNumber);

            if (lineStratum == null) {
                // location not mapped in this stratum
                continue;
            }

            int lineNumber = lineStratum.lineNumber();

            // remove unmapped and dup lines
            if ((lineNumber != -1) &&
                    (!lineStratum.equals(lastLineStratum))) {
                lastLineStratum = lineStratum;

                // Remember the largest/smallest line number
                if (lineNumber > highestLine) {
                    highestLine = lineNumber;
                }
                if ((lineNumber < lowestLine) || (lowestLine == -1)) {
                    lowestLine = lineNumber;
                }

                loc.addStratumLineInfo(
                        new StratumLineInfo(stratumID,
                                lineNumber,
                                lineStratum.sourceName(),
                                lineStratum.sourcePath()));

                // Add to the location list
                lineLocations.add(loc);

                // Add to the line -> locations map
                lineMapper.computeIfAbsent(lineNumber, k -> new ArrayList<>(1)).add(loc);
            }
        }

        info = new SoftLocationXRefs(stratumID, lineMapper, lineLocations,
                                     lowestLine, highestLine);
        softOtherLocationXRefsRef = new SoftReference<>(info);
        return info;
    }

    private CompletableFuture<SoftLocationXRefs> getLocationsAsync(SDE.Stratum stratum) {
        if (stratum.isJava()) {
            return getBaseLocationsAsync();
        }
        String stratumID = stratum.id();
        SoftLocationXRefs info =
                (softOtherLocationXRefsRef == null) ? null :
                        softOtherLocationXRefsRef.get();
        if (info != null && info.stratumID.equals(stratumID)) {
            return CompletableFuture.completedFuture(info);
        }
        return declaringType.stratumAsync(SDE.BASE_STRATUM_NAME)
                .thenCombine(getBaseLocationsAsync(), (baseStratum, softLocationXRefs) -> {
                    List<Location> lineLocations = new ArrayList<>();
                    Map<Integer, List<Location>> lineMapper = new HashMap<>();
                    int lowestLine = -1;
                    int highestLine = -1;
                    SDE.LineStratum lastLineStratum = null;
                    for (Location lineLocation : softLocationXRefs.lineLocations) {
                        LocationImpl loc = (LocationImpl) lineLocation;
                        int baseLineNumber = loc.lineNumber(baseStratum);
                        SDE.LineStratum lineStratum =
                                stratum.lineStratum(declaringType, baseLineNumber);

                        if (lineStratum == null) {
                            // location not mapped in this stratum
                            continue;
                        }

                        int lineNumber = lineStratum.lineNumber();

                        // remove unmapped and dup lines
                        if ((lineNumber != -1) &&
                                (!lineStratum.equals(lastLineStratum))) {
                            lastLineStratum = lineStratum;

                            // Remember the largest/smallest line number
                            if (lineNumber > highestLine) {
                                highestLine = lineNumber;
                            }
                            if ((lineNumber < lowestLine) || (lowestLine == -1)) {
                                lowestLine = lineNumber;
                            }

                            loc.addStratumLineInfo(
                                    new StratumLineInfo(stratumID,
                                            lineNumber,
                                            lineStratum.sourceName(),
                                            lineStratum.sourcePath()));

                            // Add to the location list
                            lineLocations.add(loc);

                            // Add to the line -> locations map
                            lineMapper.computeIfAbsent(lineNumber, k -> new ArrayList<>(1)).add(loc);
                        }
                    }

                    SoftLocationXRefs res = new SoftLocationXRefs(stratumID, lineMapper, lineLocations,
                            lowestLine, highestLine);
                    softOtherLocationXRefsRef = new SoftReference<>(res);
                    return res;
                });
    }

    private SoftLocationXRefs getBaseLocations() {
        SoftLocationXRefs info = (softBaseLocationXRefsRef == null) ? null :
                                     softBaseLocationXRefsRef.get();
        if (info != null) {
            return info;
        }

        JDWP.Method.LineTable lntab;
        try {
            lntab = JDWP.Method.LineTable.process(vm, declaringType, ref);
        } catch (JDWPException exc) {
            /*
             * Note: the absent info error shouldn't happen here
             * because the first and last index are always available.
             */
            throw exc.toJDIException();
        }

        int count  = lntab.lines.length;

        List<Location> lineLocations = new ArrayList<>(count);
        Map<Integer, List<Location>>lineMapper = new HashMap<>();
        int lowestLine = -1;
        int highestLine = -1;
        for (int i = 0; i < count; i++) {
            long bci = lntab.lines[i].lineCodeIndex;
            int lineNumber = lntab.lines[i].lineNumber;

            /*
             * Some compilers will point multiple consecutive
             * lines at the same location. We need to choose
             * one of them so that we can consistently map back
             * and forth between line and location. So we choose
             * to record only the last line entry at a particular
             * location.
             */
            if ((i + 1 == count) || (bci != lntab.lines[i+1].lineCodeIndex)) {
                // Remember the largest/smallest line number
                if (lineNumber > highestLine) {
                    highestLine = lineNumber;
                }
                if ((lineNumber < lowestLine) || (lowestLine == -1)) {
                    lowestLine = lineNumber;
                }
                LocationImpl loc =
                    new LocationImpl(virtualMachine(), this, bci);
                loc.addBaseLineInfo(
                    new BaseLineInfo(lineNumber, declaringType));

                // Add to the location list
                lineLocations.add(loc);

                // Add to the line -> locations map
                lineMapper.computeIfAbsent(lineNumber, k -> new ArrayList<>(1)).add(loc);
            }
        }

        /*
         * firstIndex, lastIndex, and startLocation need to be
         * retrieved only once since they are strongly referenced.
         */
        if (location == null) {
            firstIndex = lntab.start;
            lastIndex = lntab.end;
            /*
             * The startLocation is the first one in the
             * location list if we have one;
             * otherwise, we construct a location for a
             * method start with no line info
             */
            if (count > 0) {
                location = lineLocations.get(0);
            } else {
                location = new LocationImpl(virtualMachine(), this,
                                            firstIndex);
            }
        }

        info = new SoftLocationXRefs(SDE.BASE_STRATUM_NAME,
                                     lineMapper, lineLocations,
                                     lowestLine, highestLine);
        softBaseLocationXRefsRef = new SoftReference<>(info);
        return info;
    }

    private CompletableFuture<SoftLocationXRefs> getBaseLocationsAsync() {
        SoftLocationXRefs info = (softBaseLocationXRefsRef == null) ? null :
                softBaseLocationXRefsRef.get();
        if (info != null) {
            return CompletableFuture.completedFuture(info);
        }

        return JDWP.Method.LineTable.processAsync(vm, declaringType, ref)
                .thenApply(lntab -> {
                    int count = lntab.lines.length;

                    List<Location> lineLocations = new ArrayList<>(count);
                    Map<Integer, List<Location>> lineMapper = new HashMap<>();
                    int lowestLine = -1;
                    int highestLine = -1;
                    for (int i = 0; i < count; i++) {
                        long bci = lntab.lines[i].lineCodeIndex;
                        int lineNumber = lntab.lines[i].lineNumber;

                        /*
                         * Some compilers will point multiple consecutive
                         * lines at the same location. We need to choose
                         * one of them so that we can consistently map back
                         * and forth between line and location. So we choose
                         * to record only the last line entry at a particular
                         * location.
                         */
                        if ((i + 1 == count) || (bci != lntab.lines[i + 1].lineCodeIndex)) {
                            // Remember the largest/smallest line number
                            if (lineNumber > highestLine) {
                                highestLine = lineNumber;
                            }
                            if ((lineNumber < lowestLine) || (lowestLine == -1)) {
                                lowestLine = lineNumber;
                            }
                            LocationImpl loc =
                                    new LocationImpl(virtualMachine(), this, bci);
                            loc.addBaseLineInfo(
                                    new BaseLineInfo(lineNumber, declaringType));

                            // Add to the location list
                            lineLocations.add(loc);

                            // Add to the line -> locations map
                            lineMapper.computeIfAbsent(lineNumber, k -> new ArrayList<>(1)).add(loc);
                        }
                    }

                    /*
                     * firstIndex, lastIndex, and startLocation need to be
                     * retrieved only once since they are strongly referenced.
                     */
                    if (location == null) {
                        firstIndex = lntab.start;
                        lastIndex = lntab.end;
                        /*
                         * The startLocation is the first one in the
                         * location list if we have one;
                         * otherwise, we construct a location for a
                         * method start with no line info
                         */
                        if (count > 0) {
                            location = lineLocations.get(0);
                        } else {
                            location = new LocationImpl(virtualMachine(), this,
                                    firstIndex);
                        }
                    }

                    SoftLocationXRefs res = new SoftLocationXRefs(SDE.BASE_STRATUM_NAME,
                            lineMapper, lineLocations,
                            lowestLine, highestLine);
                    softBaseLocationXRefsRef = new SoftReference<>(res);
                    return res;
                });
    }

    private List<LocalVariable> getVariables1_4() throws AbsentInformationException {
        JDWP.Method.VariableTable vartab;
        try {
            vartab = JDWP.Method.VariableTable.
                                     process(vm, declaringType, ref);
        } catch (JDWPException exc) {
            if (exc.errorCode() == JDWP.Error.ABSENT_INFORMATION) {
                absentVariableInformation = true;
                throw new AbsentInformationException();
            } else {
                throw exc.toJDIException();
            }
        }

        return createVariables1_4(vartab);
    }

    private CompletableFuture<List<LocalVariable>> getVariables1_4Async() {
        return JDWP.Method.VariableTable.processAsync(vm, declaringType, ref)
                .exceptionally(throwable -> {
                    if (JDWPException.isOfType(throwable, JDWP.Error.ABSENT_INFORMATION)) {
                        absentVariableInformation = true;
                        throw new CompletionException(new AbsentInformationException());
                    }
                    throw (RuntimeException) throwable;
                })
                .thenApply(this::createVariables1_4);
    }

    private List<LocalVariable> createVariables1_4(JDWP.Method.VariableTable vartab) {
        // Get the number of slots used by argument variables
        argSlotCount = vartab.argCnt;
        int count = vartab.slots.length;
        List<LocalVariable> variables = new ArrayList<>(count);
        for (int i=0; i<count; i++) {
            JDWP.Method.VariableTable.SlotInfo si = vartab.slots[i];

            /*
             * Skip "this*" entries because they are never real
             * variables from the JLS perspective.
             */
            if (!si.name.startsWith("this$") && !si.name.equals("this")) {
                Location scopeStart = new LocationImpl(virtualMachine(),
                                                       this, si.codeIndex);
                Location scopeEnd =
                    new LocationImpl(virtualMachine(), this,
                                     si.codeIndex + si.length - 1);
                LocalVariable variable =
                    new LocalVariableImpl(virtualMachine(), this,
                                          si.slot, scopeStart, scopeEnd,
                                          si.name, si.signature, null);
                // Add to the variable list
                variables.add(variable);
            }
        }
        return variables;
    }

    private List<LocalVariable> getVariables1() throws AbsentInformationException {

        if (!vm.canGet1_5LanguageFeatures()) {
            return getVariables1_4();
        }

        JDWP.Method.VariableTableWithGeneric vartab;
        try {
            vartab = JDWP.Method.VariableTableWithGeneric.
                                     process(vm, declaringType, ref);
        } catch (JDWPException exc) {
            if (exc.errorCode() == JDWP.Error.ABSENT_INFORMATION) {
                absentVariableInformation = true;
                throw new AbsentInformationException();
            } else {
                throw exc.toJDIException();
            }
        }

        return createVariables(vartab);
    }

    private CompletableFuture<List<LocalVariable>> getVariables1Async() {
        if (!vm.canGet1_5LanguageFeatures()) {
            return getVariables1_4Async();
        }

        return JDWP.Method.VariableTableWithGeneric.processAsync(vm, declaringType, ref)
                .exceptionally(throwable -> {
                    if (JDWPException.isOfType(throwable, JDWP.Error.ABSENT_INFORMATION)) {
                        absentVariableInformation = true;
                        throw new CompletionException(new AbsentInformationException());
                    }
                    throw (RuntimeException) throwable;
                })
                .thenApply(this::createVariables);
    }

    private List<LocalVariable> createVariables(JDWP.Method.VariableTableWithGeneric vartab) {
        // Get the number of slots used by argument variables
        argSlotCount = vartab.argCnt;
        int count = vartab.slots.length;
        List<LocalVariable> variables = new ArrayList<>(count);
        for (int i=0; i<count; i++) {
            JDWP.Method.VariableTableWithGeneric.SlotInfo si = vartab.slots[i];

            /*
             * Skip "this*" entries because they are never real
             * variables from the JLS perspective.
             */
            if (!si.name.startsWith("this$") && !si.name.equals("this")) {
                Location scopeStart = new LocationImpl(virtualMachine(),
                                                       this, si.codeIndex);
                Location scopeEnd =
                    new LocationImpl(virtualMachine(), this,
                                     si.codeIndex + si.length - 1);
                LocalVariable variable =
                    new LocalVariableImpl(virtualMachine(), this,
                                          si.slot, scopeStart, scopeEnd,
                                          si.name, si.signature,
                                          si.genericSignature);
                // Add to the variable list
                variables.add(variable);
            }
        }
        return variables;
    }

    private List<LocalVariable> getVariables() throws AbsentInformationException {
        if (absentVariableInformation) {
            throw new AbsentInformationException();
        }

        List<LocalVariable> variables = (variablesRef == null) ? null :
                                         variablesRef.get();
        if (variables != null) {
            return variables;
        }
        variables = getVariables1();
        variables = Collections.unmodifiableList(variables);
        variablesRef = new SoftReference<>(variables);
        return variables;
    }

    private CompletableFuture<List<LocalVariable>> getVariablesAsync() {
        if (absentVariableInformation) {
            return CompletableFuture.failedFuture(new AbsentInformationException());
        }

        List<LocalVariable> variables = (variablesRef == null) ? null : variablesRef.get();
        if (variables != null) {
            return CompletableFuture.completedFuture(variables);
        }
        return getVariables1Async().thenApply(v -> {
            List<LocalVariable> res = Collections.unmodifiableList(v);
            variablesRef = new SoftReference<>(res);
            return res;
        });
    }
}
