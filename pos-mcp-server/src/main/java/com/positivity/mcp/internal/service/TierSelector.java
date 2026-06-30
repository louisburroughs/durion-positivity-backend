package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.domain.ModelTier;
import com.positivity.mcp.internal.domain.RequestComplexity;
import com.positivity.mcp.internal.domain.RouterClassification;
import com.positivity.mcp.internal.enums.NltiIntentType;
import com.positivity.mcp.internal.enums.NltiRiskLevel;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * Pure Gate 4 tier-selection rule. Routes to {@link ModelTier#T2_COMPLEX} when the request is an
 * action, medium/high risk, multi-domain, or in a correctness-critical domain; otherwise
 * {@link ModelTier#T2_SIMPLE}.
 *
 * <p>This decides a <em>model</em>, never tool/permission access (Permission lock). Risky work is
 * never routed to a small model (Gate 4 drift guard).
 */
@Component
public class TierSelector {

    private static final Set<String> RISKY_DOMAINS = Set.of("accounting", "tax", "admin", "security");

    public @NonNull ModelTier select(@NonNull RouterClassification classification) {
        if (classification.intentType() == NltiIntentType.ACTION) {
            return ModelTier.T2_COMPLEX;
        }
        if (classification.riskLevel().ordinal() >= NltiRiskLevel.MEDIUM.ordinal()) {
            return ModelTier.T2_COMPLEX;
        }
        if (classification.complexity() == RequestComplexity.MULTI_DOMAIN) {
            return ModelTier.T2_COMPLEX;
        }
        if (RISKY_DOMAINS.contains(classification.domain().toLowerCase(Locale.ROOT))) {
            return ModelTier.T2_COMPLEX;
        }
        return ModelTier.T2_SIMPLE;
    }
}
