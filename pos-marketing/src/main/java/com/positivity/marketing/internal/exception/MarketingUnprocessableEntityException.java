package com.positivity.marketing.internal.exception;

/**
 * A syntactically valid request the domain refuses — an unknown template token, an illegal
 * lifecycle transition, an unschedulable campaign. Maps to {@code 422} so callers can tell it
 * apart from a malformed body ({@code 400}).
 */
public class MarketingUnprocessableEntityException extends RuntimeException {

    public MarketingUnprocessableEntityException(String message) {
        super(message);
    }
}
