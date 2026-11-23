package com.jetbrains.jdi;

/**
 * The debuggee of {@link ClassPrepareEventWithoutThreadTest}.
 */
public class ClassPrepareEventWithoutThreadDebuggee {
    public static void main(String[] args) {
        stop();
    }

    static void stop() {
    }

    static class Prepared {
    }
}
