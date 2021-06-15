package com.jetbrains.jdi;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

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
}
