package com.positivity.warranty.internal.service;

import com.positivity.warranty.internal.dto.EligibilityEvaluation;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Eligibility suggestion engine (PRD §6). Runs on demand and automatically at submit: finds
 * candidate policies (most-specific {@code appliesTo} match in effect on the original sale
 * date), checks each structured term producing per-term reasons, computes per-line proration,
 * persists the results onto the claim and its lines, and returns the evaluation.
 */
public interface EligibilityService {

    /**
     * Evaluate eligibility for the given claim, persisting results onto the claim
     * ({@code eligibilityResult}, {@code eligibilityReasons}, {@code suggestedOutcome},
     * {@code policyId}/{@code providerId} when a single policy wins) and its lines
     * ({@code prorationPct}, {@code prorationInputs}, {@code amountRequested}).
     */
    @NonNull
    EligibilityEvaluation evaluate(@NonNull UUID claimId);
}
