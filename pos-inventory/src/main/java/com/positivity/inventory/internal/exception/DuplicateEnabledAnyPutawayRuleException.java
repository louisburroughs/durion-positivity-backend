package com.positivity.inventory.internal.exception;

import java.util.UUID;

/**
 * A second enabled {@code ANY} putaway rule was requested (issue #1514).
 *
 * <p>{@code ANY} is the terminal tier: it matches every line, so the first one the priority order
 * reaches always wins and any further enabled {@code ANY} rule is unreachable by construction. That
 * makes a second one silent dead configuration — an operator would author a fallback, see it
 * accepted, and never see it used. Refusing it keeps "which bin is the catch-all" a question with
 * exactly one answer.
 *
 * <p>Enabling a second one is refused the same way, since {@code isEnabled} is what makes a rule
 * reachable. Disabled {@code ANY} rules are unrestricted — they match nothing until enabled.
 */
public class DuplicateEnabledAnyPutawayRuleException extends RuntimeException {

    public static final String ERROR_CODE = "DUPLICATE_ENABLED_ANY_PUTAWAY_RULE";

    private final UUID existingRuleId;

    public DuplicateEnabledAnyPutawayRuleException(UUID existingRuleId) {
        super(String.format(
                "An enabled ANY putaway rule already exists (%s). ANY matches every line, so only one may be"
                        + " enabled at a time — disable or retarget the existing rule first.",
                existingRuleId));
        this.existingRuleId = existingRuleId;
    }

    private DuplicateEnabledAnyPutawayRuleException() {
        super("An enabled ANY putaway rule already exists. ANY matches every line, so only one may be enabled at"
                + " a time — list the rules to find it, then disable or retarget it first.");
        this.existingRuleId = null;
    }

    /**
     * The same conflict, detected by the database rather than by the pre-flight read.
     *
     * <p>A concurrent request won the race, so the winning rule's id is not known here: reading it
     * back would be a second query against a row another transaction just committed, and the caller's
     * next step is to list the rules regardless. The message says so instead of reporting a null id.
     */
    public static DuplicateEnabledAnyPutawayRuleException detectedByConstraint() {
        return new DuplicateEnabledAnyPutawayRuleException();
    }

    /** The existing rule's id, or null when the conflict was detected by the database constraint. */
    public UUID getExistingRuleId() {
        return existingRuleId;
    }
}
