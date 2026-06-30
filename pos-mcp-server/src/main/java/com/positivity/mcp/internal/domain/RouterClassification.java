package com.positivity.mcp.internal.domain;

import com.positivity.mcp.internal.enums.NltiIntentType;
import com.positivity.mcp.internal.enums.NltiRiskLevel;
import org.jspecify.annotations.NonNull;

/**
 * Output of the Gate 4 T1 router: the signals that drive {@link ModelTier} selection.
 */
public record RouterClassification(
        @NonNull NltiIntentType intentType,
        @NonNull NltiRiskLevel riskLevel,
        @NonNull RequestComplexity complexity,
        @NonNull String domain) {

    /**
     * Safe default used when the router output is missing or invalid: treat as a high-risk,
     * multi-domain action so selection routes to {@link ModelTier#T2_COMPLEX} — never silently to a
     * small model.
     */
    public static @NonNull RouterClassification safeDefault() {
        return new RouterClassification(
                NltiIntentType.UNKNOWN, NltiRiskLevel.HIGH, RequestComplexity.MULTI_DOMAIN, "master");
    }
}
