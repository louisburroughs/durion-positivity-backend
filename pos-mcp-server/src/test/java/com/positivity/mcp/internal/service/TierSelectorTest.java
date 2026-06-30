package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.mcp.internal.domain.ModelTier;
import com.positivity.mcp.internal.domain.RequestComplexity;
import com.positivity.mcp.internal.domain.RouterClassification;
import com.positivity.mcp.internal.enums.NltiIntentType;
import com.positivity.mcp.internal.enums.NltiRiskLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Gate 4: tier-selection rule (G4.2). */
class TierSelectorTest {

    private final TierSelector selector = new TierSelector();

    private RouterClassification c(
            NltiIntentType intent, NltiRiskLevel risk, RequestComplexity complexity, String domain) {
        return new RouterClassification(intent, risk, complexity, domain);
    }

    @Test
    @DisplayName("simple read query in a low-risk domain -> T2_SIMPLE")
    void simpleQuery() {
        assertThat(selector.select(
                        c(NltiIntentType.QUERY, NltiRiskLevel.LOW, RequestComplexity.SINGLE_LOOKUP, "workorder")))
                .isEqualTo(ModelTier.T2_SIMPLE);
    }

    @Test
    @DisplayName("any ACTION -> T2_COMPLEX")
    void actionIsComplex() {
        assertThat(selector.select(
                        c(NltiIntentType.ACTION, NltiRiskLevel.LOW, RequestComplexity.SINGLE_LOOKUP, "workorder")))
                .isEqualTo(ModelTier.T2_COMPLEX);
    }

    @Test
    @DisplayName("risk >= MEDIUM -> T2_COMPLEX")
    void mediumRiskIsComplex() {
        assertThat(selector.select(
                        c(NltiIntentType.QUERY, NltiRiskLevel.MEDIUM, RequestComplexity.SINGLE_LOOKUP, "workorder")))
                .isEqualTo(ModelTier.T2_COMPLEX);
        assertThat(selector.select(
                        c(NltiIntentType.QUERY, NltiRiskLevel.HIGH, RequestComplexity.SINGLE_LOOKUP, "workorder")))
                .isEqualTo(ModelTier.T2_COMPLEX);
    }

    @Test
    @DisplayName("multi-domain -> T2_COMPLEX")
    void multiDomainIsComplex() {
        assertThat(selector.select(
                        c(NltiIntentType.QUERY, NltiRiskLevel.LOW, RequestComplexity.MULTI_DOMAIN, "workorder")))
                .isEqualTo(ModelTier.T2_COMPLEX);
    }

    @Test
    @DisplayName("correctness-critical domains -> T2_COMPLEX even for simple low-risk reads")
    void riskyDomainsAreComplex() {
        for (String domain : new String[] {"accounting", "tax", "admin", "security", "ACCOUNTING"}) {
            assertThat(selector.select(
                            c(NltiIntentType.QUERY, NltiRiskLevel.LOW, RequestComplexity.SINGLE_LOOKUP, domain)))
                    .as("domain %s", domain)
                    .isEqualTo(ModelTier.T2_COMPLEX);
        }
    }

    @Test
    @DisplayName("safe default classification routes to T2_COMPLEX")
    void safeDefaultIsComplex() {
        assertThat(selector.select(RouterClassification.safeDefault())).isEqualTo(ModelTier.T2_COMPLEX);
    }
}
