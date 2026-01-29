package com.positivity.accounting.internal.enums;

/**
 * Posting Rule Set version state machine.
 * 
 * State transitions:
 * - DRAFT → PUBLISHED → ARCHIVED
 * - DRAFT → (delete if never published)
 * 
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide</a>
 */
public enum PostingRuleSetState {
    /**
     * Version is editable, not used for JE generation.
     * Can be edited, deleted, or published.
     */
    DRAFT,

    /**
     * Version is immutable, active for JE generation.
     * Cannot be edited; can only be archived.
     */
    PUBLISHED,

    /**
     * Version is immutable, inactive (historical only).
     * Cannot be edited or reactivated.
     */
    ARCHIVED
}
