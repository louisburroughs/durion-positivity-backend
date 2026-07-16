package com.positivity.warranty.internal.exception;

/**
 * Raised when a claim operation is not legal in the claim's current lifecycle state — an
 * illegal state-machine transition (PRD §5) or a blocked terminal move (e.g. close with open
 * reimbursements). Translated by {@link WarrantyExceptionHandler} into a 409 {@code ApiError}
 * whose {@code nextAction} lists the legal moves, per PRD §5 ("illegal transitions return the
 * standard ApiError envelope with nextAction listing the legal moves").
 */
public class IllegalClaimStateException extends RuntimeException {

    private final String code;
    private final String nextAction;

    public IllegalClaimStateException(String code, String message, String nextAction) {
        super(message);
        this.code = code;
        this.nextAction = nextAction;
    }

    public String getCode() {
        return code;
    }

    public String getNextAction() {
        return nextAction;
    }
}
