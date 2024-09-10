package com.jetbrains.jdi;

public class JDWPUnsupportedOperationException extends UnsupportedOperationException {
    private final short errorCode;

    public JDWPUnsupportedOperationException(short errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * @see JDWP.Error
     */
    public short getErrorCode() {
        return errorCode;
    }
}
