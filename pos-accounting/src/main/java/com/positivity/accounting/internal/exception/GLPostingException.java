package com.positivity.accounting.internal.exception;

public class GLPostingException extends RuntimeException {

    public GLPostingException(String message) {
        super(message);
    }

    public GLPostingException(String message, Throwable cause) {
        super(message, cause);
    }
}
