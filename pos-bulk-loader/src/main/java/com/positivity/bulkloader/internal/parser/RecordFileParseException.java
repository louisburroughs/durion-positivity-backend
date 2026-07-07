package com.positivity.bulkloader.internal.parser;

/** Raised when an uploaded file cannot be parsed into records. */
public class RecordFileParseException extends RuntimeException {

    public RecordFileParseException(String message) {
        super(message);
    }

    public RecordFileParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
