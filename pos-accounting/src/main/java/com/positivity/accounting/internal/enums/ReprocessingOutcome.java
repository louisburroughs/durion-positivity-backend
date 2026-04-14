package com.positivity.accounting.internal.enums;

/**
 * Outcome of a reprocessing attempt for a suspended accounting event.
 *
 * @see com.positivity.accounting.internal.entity.ReprocessingAttemptHistory
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide</a>
 */
public enum ReprocessingOutcome {
    /**
     * Reprocessing succeeded and event was posted to GL.
     */
    SUCCESS,

    /**
     * Reprocessing failed (mapping still invalid, validation error, etc.).
     */
    FAILURE
}
