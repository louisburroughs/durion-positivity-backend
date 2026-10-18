package com.positivity.extractionworker.internal.exception;

/** Every parse slot is taken: answered 503 {@code WORKER_BUSY} with {@code Retry-After}. */
public class WorkerBusyException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public WorkerBusyException() {
        super("All parse slots are in use; retry shortly");
    }
}
