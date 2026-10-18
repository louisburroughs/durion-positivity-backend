package com.positivity.extractionworker.internal.exception;

/** The page cannot be rendered (out of range, unreadable, over a limit): answered 422 {@code PAGE_NOT_RENDERABLE}. */
public class PageNotRenderableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public PageNotRenderableException() {
        super("The requested page cannot be rendered");
    }
}
