package com.positivity.documents.internal.exception;

public class RenderingException extends RuntimeException {

    private final boolean malformedInput;

    public RenderingException(String message, Throwable cause, boolean malformedInput) {
        super(message, cause);
        this.malformedInput = malformedInput;
    }

    public RenderingException(String message, Throwable cause) {
        this(message, cause, false);
    }

    public RenderingException(String message) {
        super(message);
        this.malformedInput = false;
    }

    public static RenderingException malformedInput(String message, Throwable cause) {
        return new RenderingException(message, cause, true);
    }

    public static RenderingException malformedInput(String message) {
        return new RenderingException(message, null, true);
    }

    public boolean isMalformedInput() {
        return malformedInput;
    }
}
