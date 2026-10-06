package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.exception.InvalidRequestParameterException;

/** Request-shape checks of the page parameters the receivables worklist reads take (#2502). */
final class PageParameters {

    private PageParameters() {}

    /**
     * @throws InvalidRequestParameterException (400 VALIDATION_ERROR) when {@code page} is negative
     *     or {@code size} is outside 1 to {@code maxSize}
     */
    static void check(int page, int size, int maxSize) {
        if (page < 0) {
            throw new InvalidRequestParameterException("page must be 0 or more");
        }
        if (size < 1 || size > maxSize) {
            throw new InvalidRequestParameterException("size must be between 1 and " + maxSize);
        }
    }
}
