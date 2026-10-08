package com.positivity.accounting.internal.exception;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;

/**
 * A refusal of a register float, petty-expense category, bank opening balance or bank deposit command (#2511, #2572,
 * #2514; SPEC-accounting-workspace §4.5, §4.6, §7.1, OI-10): one stable code, one HTTP status, a message in business
 * words.
 */
public class CashSetupException extends RuntimeException {

    /** The codes and their statuses (S34 lists them in ERROR_CODES.md). */
    public enum Code {
        BANK_OPENING_BALANCE_ACCOUNT_NOT_ELIGIBLE(HttpStatus.UNPROCESSABLE_CONTENT),
        BANK_OPENING_BALANCE_ALREADY_ESTABLISHED(HttpStatus.CONFLICT),
        BANK_OPENING_BALANCE_EMPTY(HttpStatus.UNPROCESSABLE_CONTENT),
        BANK_OPENING_BALANCE_NOT_FIRST(HttpStatus.UNPROCESSABLE_CONTENT),
        DEPOSIT_ALREADY_REVERSED(HttpStatus.CONFLICT),
        DEPOSIT_BANK_ACCOUNT_NOT_ELIGIBLE(HttpStatus.UNPROCESSABLE_CONTENT),
        DEPOSIT_NOT_FOUND(HttpStatus.NOT_FOUND),
        DEPOSIT_REVERSAL_NOT_REVERSIBLE(HttpStatus.CONFLICT),
        DEPOSIT_SESSION_ALREADY_DEPOSITED(HttpStatus.CONFLICT),
        DEPOSIT_UNBALANCED(HttpStatus.UNPROCESSABLE_CONTENT),
        FLOAT_ALREADY_ESTABLISHED(HttpStatus.CONFLICT),
        FLOAT_AMOUNT_NEGATIVE(HttpStatus.UNPROCESSABLE_CONTENT),
        FLOAT_AMOUNT_UNCHANGED(HttpStatus.UNPROCESSABLE_CONTENT),
        FLOAT_BANK_ACCOUNT_NOT_ELIGIBLE(HttpStatus.UNPROCESSABLE_CONTENT),
        FLOAT_DATE_BEFORE_RELOCATION(HttpStatus.UNPROCESSABLE_CONTENT),
        FLOAT_REGISTER_LOCATION_MISMATCH(HttpStatus.UNPROCESSABLE_CONTENT),
        FLOAT_REGISTER_NOT_FOUND(HttpStatus.NOT_FOUND),
        FLOAT_REGISTER_SESSION_OPEN(HttpStatus.UNPROCESSABLE_CONTENT),
        FLOAT_RELOCATION_DATE_INVALID(HttpStatus.UNPROCESSABLE_CONTENT),
        FLOAT_RELOCATION_NOT_REVERSIBLE(HttpStatus.CONFLICT),
        FLOAT_RELOCATION_SAME_LOCATION(HttpStatus.UNPROCESSABLE_CONTENT),
        FLOAT_REVERSAL_BEFORE_RELOCATION(HttpStatus.UNPROCESSABLE_CONTENT),
        IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT),
        PETTY_EXPENSE_CATEGORY_EXISTS(HttpStatus.CONFLICT),
        PETTY_EXPENSE_CATEGORY_NOT_ALLOWED(HttpStatus.UNPROCESSABLE_CONTENT),
        PETTY_EXPENSE_ACCOUNT_NOT_ELIGIBLE(HttpStatus.UNPROCESSABLE_CONTENT),
        PETTY_EXPENSE_ACCOUNT_CHANGE_BACKDATED(HttpStatus.UNPROCESSABLE_CONTENT),
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
    private final @Nullable String referenceId;
    private final @Nullable String nextAction;

    public CashSetupException(@NonNull Code code, @NonNull String message) {
        this(code, message, null, null);
    }

    /**
     * A refusal that names the record in the way ({@code referenceId}, the {@code ApiError.referenceId}) and what
     * the caller does next.
     */
    public CashSetupException(
            @NonNull Code code, @NonNull String message, @Nullable String referenceId, @Nullable String nextAction) {
        super(message);
        this.code = code;
        this.referenceId = referenceId;
        this.nextAction = nextAction;
    }

    public @NonNull Code getCode() {
        return code;
    }

    public @Nullable String getReferenceId() {
        return referenceId;
    }

    public @Nullable String getNextAction() {
        return nextAction;
    }
}
