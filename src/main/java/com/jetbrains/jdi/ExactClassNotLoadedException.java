/*
 * Copyright (C) 2024 JetBrains s.r.o.
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

import com.sun.jdi.ClassNotLoadedException;

// This exception provides the classloader that does not have the class loaded
public class ExactClassNotLoadedException extends ClassNotLoadedException {
    private final ClassLoaderReferenceImpl classLoaderReference;

    ExactClassNotLoadedException(String className, String message, ClassLoaderReferenceImpl classLoaderReference) {
        super(className, message);
        this.classLoaderReference = classLoaderReference;
    }

    public ClassLoaderReferenceImpl getClassLoader() {
        return classLoaderReference;
    }
}
