package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.enums.ArgProvenance;
import com.positivity.mcp.internal.enums.NltiRiskLevel;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;

/**
 * Pure Gate 6 write-plan policy helpers (no I/O). The confirm flow (endpoint, persistence,
 * execution) is live; these decision rules are unit-tested here so the gate's invariants are
 * verified before any wiring.
 */
public final class WritePlanPolicy {

    private WritePlanPolicy() {}

    /** A plan is expired at or after its expiry instant — confirming then must not execute. */
    public static boolean isExpired(@NonNull OffsetDateTime expiresAt, @NonNull OffsetDateTime now) {
        return !now.isBefore(expiresAt);
    }

    /** Argument names whose provenance is INFERRED_DEFAULT — these MUST be disclosed in the preview. */
    public static @NonNull Set<String> inferredDefaultArgs(@NonNull Map<String, ArgProvenance> provenance) {
        return provenance.entrySet().stream()
                .filter(e -> e.getValue() == ArgProvenance.INFERRED_DEFAULT)
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Drift guard: every plan argument must carry a provenance marker. */
    public static boolean allArgsHaveProvenance(
            @NonNull Set<String> argNames, @NonNull Map<String, ArgProvenance> provenance) {
        return provenance.keySet().containsAll(argNames);
    }

    /**
     * HIGH-risk plans must not rely on inferred defaults — those require explicit user selection.
     * Returns true when the plan must be rejected / sent back for explicit input.
     */
    public static boolean highRiskInferredRejected(
            @NonNull NltiRiskLevel riskLevel, @NonNull Map<String, ArgProvenance> provenance) {
        return riskLevel == NltiRiskLevel.HIGH && provenance.containsValue(ArgProvenance.INFERRED_DEFAULT);
    }
}
