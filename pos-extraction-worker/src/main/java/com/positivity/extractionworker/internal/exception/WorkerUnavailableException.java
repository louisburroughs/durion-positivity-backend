package com.positivity.extractionworker.internal.exception;

/**
 * The worker's own machinery failed (the child could not start, a decoder is missing): answered 503
 * {@code WORKER_UNAVAILABLE}. It is never a verdict on the file, so the caller retries and no file is
 * rejected for it (S26 ruling 2).
 */
public class WorkerUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public WorkerUnavailableException() {
        super("The worker could not run the parse; retry later");
    }
}
