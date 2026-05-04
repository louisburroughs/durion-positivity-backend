package com.positivity.bulkloader.internal.exception;

public class TusOffsetConflictException extends RuntimeException {

    private final long expected;
    private final long actual;

    public TusOffsetConflictException(long expected, long actual) {
        super("Upload-Offset conflict: client sent " + expected + " but current offset is " + actual);
        this.expected = expected;
        this.actual = actual;
    }

    public long getExpected() {
        return expected;
    }

    public long getActual() {
        return actual;
    }
}
