package com.positivity.invoice.internal.exception;

/**
 * A cash tender whose rounding triple is not what the increment allows (ADR-0067 A8, #2662): {@code settledAmount}
 * not on the increment's grid, a rounding above half an increment, a rounding that is not {@code settledAmount −
 * amount}, or rounding fields on a non-cash tender. Answered as 422 {@code CASH_ROUNDING_INVALID}.
 */
public class CashRoundingInvalidException extends RuntimeException {

    public static final String CODE = "CASH_ROUNDING_INVALID";

    public CashRoundingInvalidException(String message) {
        super(message);
    }
}
