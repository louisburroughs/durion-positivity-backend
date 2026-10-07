package com.positivity.accounting.internal.exception;

import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;

/**
 * A refusal of a register float or petty-expense category command (#2511; SPEC-accounting-workspace
 * §4.6, §7.1): one stable code, one HTTP status, a message in business words.
 */
public class CashSetupException extends RuntimeException {

    /** The codes and their statuses (S34 lists them in ERROR_CODES.md). */
    public enum Code {
        FLOAT_ALREADY_ESTABLISHED(HttpStatus.CONFLICT),
        FLOAT_AMOUNT_UNCHANGED(HttpStatus.UNPROCESSABLE_CONTENT),
        FLOAT_BANK_ACCOUNT_NOT_ELIGIBLE(HttpStatus.UNPROCESSABLE_CONTENT),
        IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT),
        PETTY_EXPENSE_CATEGORY_EXISTS(HttpStatus.CONFLICT),
        PETTY_EXPENSE_CATEGORY_NOT_ALLOWED(HttpStatus.UNPROCESSABLE_CONTENT),
        PETTY_EXPENSE_ACCOUNT_NOT_ELIGIBLE(HttpStatus.UNPROCESSABLE_CONTENT),
        PETTY_EXPENSE_CATEGORY_INACTIVE(HttpStatus.CONFLICT),
        PETTY_EXPENSE_CATEGORY_MANAGED(HttpStatus.UNPROCESSABLE_CONTENT),
        PETTY_EXPENSE_CATEGORY_NOT_FOUND(HttpStatus.NOT_FOUND),
        PETTY_EXPENSE_MAPPING_OVERLAP(HttpStatus.BAD_REQUEST),
        VERSION_CONFLICT(HttpStatus.CONFLICT);

        private final HttpStatus status;

        Code(HttpStatus status) {
            this.status = status;
        }

        public @NonNull HttpStatus status() {
            return status;
        }
    }

    private final Code code;

    public CashSetupException(@NonNull Code code, @NonNull String message) {
        super(message);
        this.code = code;
    }

    public @NonNull Code getCode() {
        return code;
    }
}
