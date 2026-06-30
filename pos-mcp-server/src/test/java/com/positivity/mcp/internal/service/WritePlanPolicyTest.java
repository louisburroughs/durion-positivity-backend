package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.mcp.internal.entity.NltiWritePlan;
import com.positivity.mcp.internal.enums.ArgProvenance;
import com.positivity.mcp.internal.enums.NltiRequestStatus;
import com.positivity.mcp.internal.enums.NltiRiskLevel;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Gate 6 foundations: write-plan policy + entity defaults. */
class WritePlanPolicyTest {

    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 6, 30, 12, 0, 0, 0, ZoneOffset.UTC);

    @Test
    @DisplayName("isExpired: false before expiry, true at/after expiry")
    void expiry() {
        OffsetDateTime expires = T0.plusMinutes(10);
        assertThat(WritePlanPolicy.isExpired(expires, T0)).isFalse();
        assertThat(WritePlanPolicy.isExpired(expires, expires)).isTrue();
        assertThat(WritePlanPolicy.isExpired(expires, expires.plusSeconds(1))).isTrue();
    }

    @Test
    @DisplayName("inferredDefaultArgs returns only INFERRED_DEFAULT keys")
    void inferred() {
        Map<String, ArgProvenance> prov = Map.of(
                "customerId", ArgProvenance.PRIOR_TOOL_RESULT,
                "vehicleId", ArgProvenance.USER_TEXT,
                "priority", ArgProvenance.INFERRED_DEFAULT);
        assertThat(WritePlanPolicy.inferredDefaultArgs(prov)).containsExactly("priority");
    }

    @Test
    @DisplayName("allArgsHaveProvenance: true only when every arg has a marker")
    void provenanceComplete() {
        Map<String, ArgProvenance> prov = Map.of("a", ArgProvenance.USER_TEXT, "b", ArgProvenance.USER_CONTEXT);
        assertThat(WritePlanPolicy.allArgsHaveProvenance(Set.of("a", "b"), prov))
                .isTrue();
        assertThat(WritePlanPolicy.allArgsHaveProvenance(Set.of("a", "b", "c"), prov))
                .isFalse();
    }

    @Test
    @DisplayName("HIGH risk + any inferred default -> rejected; non-HIGH or no inferred -> allowed")
    void highRiskInferred() {
        Map<String, ArgProvenance> withInferred = Map.of("amount", ArgProvenance.INFERRED_DEFAULT);
        Map<String, ArgProvenance> grounded = Map.of("amount", ArgProvenance.USER_TEXT);
        assertThat(WritePlanPolicy.highRiskInferredRejected(NltiRiskLevel.HIGH, withInferred))
                .isTrue();
        assertThat(WritePlanPolicy.highRiskInferredRejected(NltiRiskLevel.MEDIUM, withInferred))
                .isFalse();
        assertThat(WritePlanPolicy.highRiskInferredRejected(NltiRiskLevel.HIGH, grounded))
                .isFalse();
    }

    @Test
    @DisplayName("new write plan defaults to PENDING_CONFIRMATION")
    void entityDefaultStatus() {
        assertThat(new NltiWritePlan().getStatus()).isEqualTo(NltiRequestStatus.PENDING_CONFIRMATION);
    }
}
