/*
 * Copyright (C) 2021 JetBrains s.r.o.
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

import java.util.concurrent.*;

import static java.util.concurrent.CompletableFuture.completedFuture;

public class AsyncUtils {
    public static Throwable unwrap(Throwable throwable) {
        return throwable instanceof CompletionException ? throwable.getCause() : throwable;
    }

    public static <T> CompletableFuture<T> toCompletableFuture(ThrowingSupplier<T> supplier) {
        try {
            return completedFuture(supplier.get());
        }
        catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    public static class JDWPCompletableFuture<T> extends CompletableFuture<T> {
        @Override
        public <U> CompletableFuture<U> newIncompleteFuture() {
            return new JDWPCompletableFuture<>();
        }

        @Override
        public T get() throws InterruptedException, ExecutionException {
            assertNotReaderThread();
            return super.get();
        }

        @Override
        public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            assertNotReaderThread();
            return super.get(timeout, unit);
        }

        private static void assertNotReaderThread() {
            if (Thread.currentThread() instanceof TargetVM.ReaderThread) {
                throw new IllegalStateException("Should not happen in JDWP reader thread");
            }
        }
    }
}
