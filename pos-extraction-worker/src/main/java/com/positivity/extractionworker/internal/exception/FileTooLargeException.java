package com.positivity.extractionworker.internal.exception;

/** The upload passed {@code pos.extraction-worker.max-file-bytes}: answered 413 {@code FILE_TOO_LARGE}. */
public class FileTooLargeException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public FileTooLargeException(long maxBytes) {
        super("The file is larger than the " + maxBytes + " byte limit");
    }
}
