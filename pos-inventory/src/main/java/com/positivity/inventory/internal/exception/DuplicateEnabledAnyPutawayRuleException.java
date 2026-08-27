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

    public UUID getExistingRuleId() {
        return existingRuleId;
    }
}
