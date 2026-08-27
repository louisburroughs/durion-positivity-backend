package com.positivity.inventory.service;

import com.positivity.inventory.internal.dto.putaway.PutawayRuleRequest;
import com.positivity.inventory.internal.dto.putaway.PutawayRuleResponse;
import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * CRUD over the putaway rule set (issue #1514).
 *
 * <p>Rules are Tier 2 configuration per {@code docs/DATA_SEED_STRATEGY.md} §2 — environment-specific
 * because they name per-environment location ids, and audited because every mutation here emits an
 * {@code @EmitEvent} — so they enter through this application layer rather than through Flyway.
 */
public interface PutawayRuleService {

    /** Every rule, ordered by tier precedence then priority: the order the matcher tries them in. */
    @NonNull
    List<PutawayRuleResponse> listRules();

    @NonNull
    PutawayRuleResponse getRule(@NonNull String ruleId);

    @NonNull
    PutawayRuleResponse createRule(@NonNull PutawayRuleRequest request);

    @NonNull
    PutawayRuleResponse updateRule(@NonNull String ruleId, @NonNull PutawayRuleRequest request);

    void deleteRule(@NonNull String ruleId);
}
