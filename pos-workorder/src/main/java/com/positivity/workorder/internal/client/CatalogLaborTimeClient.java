package com.positivity.workorder.internal.client;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The workorder-side face of the catalog labor-time resolution edge (#1569 Phase 1, ADR-0044
 * amendment 2026-09-02). One method, Optional-out: every failure and every typed miss is an
 * empty answer, because the caller's next move is the same either way — fall back to the
 * replica default hours, then to a blank prefill the service writer types over.
 */
public interface CatalogLaborTimeClient {

    /**
     * @param serviceId the catalog service being quoted
     * @param vehicleYear year or range; null = unknown, widens the match
     * @param make vehicle make; null = unknown
     * @param model vehicle model; null = unknown
     * @param locationId the location quoting the work. Its own authored times outrank published
     *     ones (#1575 Tier 0); null quotes as the platform and sees platform times only.
     * @return the resolved guide time, or empty on any miss or failure
     */
    @NonNull
    Optional<GuideTime> resolveLaborTime(
            @NonNull UUID serviceId,
            @Nullable String vehicleYear,
            @Nullable String make,
            @Nullable String model,
            @Nullable UUID locationId);

    /**
     * A resolved guide time with the provenance the estimate snapshot records.
     *
     * @param laborHours decimal hours in tenths
     * @param timeType which time class answered
     * @param sourceCode provenance source
     * @param sourceRevision provenance revision
     * @param matchGrade vehicle-match confidence as the edge reported it
     * @param overlapGroup shared-setup group for overlap-aware summation
     * @param includedOpCodes Durion operation codes already included in this time
     * @param ownerScope {@code SHOP} when the quoting location's own authored time answered,
     *     {@code PLATFORM} otherwise
     */
    record GuideTime(
            @NonNull BigDecimal laborHours,
            @Nullable String timeType,
            @Nullable String sourceCode,
            @Nullable String sourceRevision,
            @Nullable String matchGrade,
            @Nullable String overlapGroup,
            @NonNull List<String> includedOpCodes,
            @Nullable String ownerScope) {}
}
