package com.positivity.inventory.internal.exception;

import java.util.UUID;

/**
 * No enabled putaway rule matches a received line (issue #1514).
 *
 * <p>Before #1514 this state routed the task at a hardcoded
 * {@code 00000000-0000-0000-0000-000000000001} "default location" that no environment has ever
 * actually had, so the task was created pointing at a bin that does not exist and the failure only
 * surfaced later, at execution, as a location error against a fabricated id.
 *
 * <p>The rule set is now expected to end in an enabled {@code ANY} rule, which matches every line
 * and is therefore the terminal fallback. Reaching this exception means that rule is missing — a
 * configuration gap, not a data problem with the receipt — so it is reported plainly and at once
 * rather than papered over. It maps to 422 via {@link PutawayValidationException}'s handler, and the
 * remedy is to create an enabled {@code ANY} rule through
 * {@code POST /v1/inventory/putaway/rules}.
 */
public class NoPutawayRuleMatchException extends PutawayValidationException {

    public static final String ERROR_CODE = "NO_PUTAWAY_RULE_MATCH";

    private final UUID productId;

    public NoPutawayRuleMatchException(UUID productId) {
        super(
                ERROR_CODE,
                String.format(
                        "No enabled putaway rule matches product %s, and no enabled ANY rule exists to fall back on."
                                + " Create an enabled ANY rule to give every received line a destination.",
                        productId));
        this.productId = productId;
    }

    public UUID getProductId() {
        return productId;
    }
}
