package com.positivity.accounting.internal.bankrec.intake;

/**
 * The error codes the bank reconciliation core answers (SPEC-manual-bank-reconciliation §4.10; story
 * S2, #2301), each with its ADR-0017 §2 status class: 400 for shape, 404 for an unknown id, 409 for
 * lifecycle, duplicate key and version, 422 for every other refusal. One condition, one code.
 *
 * <p>It lives beside the intake port because the port's refusals are part of its contract: an
 * adapter that calls {@link BankTransactionIntake#accept} sees these codes and nothing else. The
 * file adapter's own lifecycle codes (story S3) are catalogued here too, so every bank reconciliation
 * refusal is answered by one exception type and one handler.
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
    STATEMENT_ACTIVITY_MISMATCH(422),

    // ---- answered by the statement-file adapter (§4.3, §4.4, §4.10; story S3, #2302) ----

    /** An import id the tenant does not hold (404). */
    BANK_IMPORT_NOT_FOUND(404),
    /** A row id that is not a row of the import (404). */
    BANK_IMPORT_ROW_NOT_FOUND(404),
    /** The import's raw file is no longer retained (404; the import itself carries {@code retentionUntil}). */
    BANK_IMPORT_FILE_NOT_FOUND(404),
    /** The same file ({@code fileSha256}) is already COMMITTED for the account (409). */
    IMPORT_FILE_ALREADY_COMMITTED(409),
    /** A mapping, row or discard change on a {@code COMMITTED} import (409). */
    IMPORT_ALREADY_COMMITTED(409),
    /** A commit, mapping, row or discard change on a {@code DISCARDED} import (409). */
    IMPORT_DISCARDED(409),
    /** The file cannot be read at all: wrong encoding, binary, or no data row (422, G10). */
    STATEMENT_IMPORT_FAILED(422),
    /** Rejected or out-of-window rows remain, the mapping is unresolved, or E1 fails (422). */
    IMPORT_NOT_COMMITTABLE(422);

    private final int httpStatus;

    BankRecErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    /** The HTTP status this code is answered with. */
    public int httpStatus() {
        return httpStatus;
    }
}
