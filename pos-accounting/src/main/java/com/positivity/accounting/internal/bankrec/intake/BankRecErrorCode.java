package com.positivity.accounting.internal.bankrec.intake;

/**
 * The error codes the bank reconciliation core answers (SPEC-manual-bank-reconciliation §4.10; story
 * S2, #2301), each with its ADR-0017 §2 status class: 400 for shape, 404 for an unknown id, 409 for
 * lifecycle, duplicate key and version, 422 for every other refusal. One condition, one code.
 *
 * <p>It lives beside the intake port because the port's refusals are part of its contract: an
 * adapter that calls {@link BankTransactionIntake#accept} sees these codes and nothing else.
 */
public enum BankRecErrorCode {
    /** Request shape: a missing or malformed field, a blank justification (400). */
    VALIDATION_ERROR(400),
    /** A justification or gap acknowledgement shorter than 10 characters (400, D15). */
    JUSTIFICATION_REQUIRED(400),
    /** An id the tenant does not hold (404; never reveals existence across tenants). */
    BANK_STATEMENT_NOT_FOUND(404),
    BANK_TRANSACTION_NOT_FOUND(404),
    GL_ACCOUNT_NOT_FOUND(404),
    /** A COMMITTED statement with the same account and window exists (409, U1). */
    STATEMENT_ALREADY_IMPORTED(409),
    /** A bank transaction not in a state the operation accepts (409, §3.8). */
    RECONCILIATION_LINE_INELIGIBLE(409),
    /** A {@code requestId} replayed with a different payload (409, §6.3). */
    IDEMPOTENCY_CONFLICT(409),
    /** A stale {@code version} (409, §6.3). */
    OPTIMISTIC_LOCK(409),
    /** A statement that already has an active, or a FINALIZED unsuperseded, reconciliation (409, §4.1; S4). */
    RECONCILIATION_WINDOW_ALREADY_RECONCILED(409),
    /** Accept, reject or unmatch on a match not in the state the action needs (409, §3.4; S4). */
    MATCH_STATE_INVALID(409),
    /** Reverse an adjustment already reversed (409, §4.9 path 2; S4). */
    ADJUSTMENT_ALREADY_REVERSED(409),
    /** A second POSTED gap bridge for one statement (409, §4.2; S4). */
    ADJUSTMENT_BRIDGE_ALREADY_POSTED(409),
    /** An {@code OTHER} adjustment beyond the caller's authority under the tenant threshold (403, §4.7; S4). */
    RECONCILIATION_ADJUSTMENT_APPROVAL_REQUIRED(403),
    /** More than one member on both sides of a match (422, M2, C6; S4). */
    MATCH_CARDINALITY_NOT_ALLOWED(422),
    /** A match with an M5 reason and no justification; {@code fieldErrors[justification]} lists them (422; S4). */
    MATCH_REQUIRES_REVIEW(422),
    /** An outstanding item the line, kind, state or window does not allow (422, §3.6; S4). */
    OUTSTANDING_ITEM_NOT_ELIGIBLE(422),
    /** A link the adjustment type requires is missing, or one it forbids is present (422, §3.5; S4). */
    ADJUSTMENT_LINK_REQUIRED(422),
    /** A named match, statement, amount or counter account that fails its link rule (422, §3.5; S4). */
    ADJUSTMENT_LINK_NOT_ELIGIBLE(422),
    /** A statementless reconciliation on an account without a feed link — every account in phase 1 (422; S4). */
    BANK_ACCOUNT_FEED_NOT_LINKED(422),
    /** The account is not a reconcilable {@code BANK_CASH} account (422, D5). */
    ACCOUNT_NOT_RECONCILABLE(422),
    /** A currency other than the account profile's — or the functional currency's (422, D18, ADR-0067). */
    CURRENCY_NOT_SUPPORTED(422),
    /** The window overlaps a COMMITTED statement (422, U2). */
    STATEMENT_PERIOD_OVERLAP(422),
    /** The statement does not continue the previous one and carries no gap acknowledgement (422, E2, D17). */
    STATEMENT_NOT_CONTIGUOUS(422),
    /** A gap acknowledgement on a statement that does continue the previous one (422, D17). */
    STATEMENT_GAP_ACKNOWLEDGEMENT_NOT_APPLICABLE(422),
    /** A manual-entry transaction dated outside the header window (422, §4.3). */
    STATEMENT_TRANSACTION_OUT_OF_WINDOW(422),
    /** {@code openingBalance + activity ≠ closingBalance} beyond one minor unit (422, E1). */
    STATEMENT_ACTIVITY_MISMATCH(422);

    private final int httpStatus;

    BankRecErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    /** The HTTP status this code is answered with. */
    public int httpStatus() {
        return httpStatus;
    }
}
