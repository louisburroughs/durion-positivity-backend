package com.positivity.workorder.internal.service;

import com.positivity.workorder.internal.client.CatalogLaborTimeClient;
import com.positivity.workorder.internal.entity.ExtCatalogServiceReplica;
import com.positivity.workorder.internal.repository.ExtCatalogServiceReplicaRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Guide-time lookup for LABOR estimate items (#1569 Phase 1, sourcing plan §6.3 item 1).
 *
 * <p>Order of answers, each strictly weaker than the last: the catalog labor-time edge (the
 * vehicle-specific number), then the local {@code ext_catalog_service} replica's
 * vehicle-agnostic default hours (the edge-is-down fallback the fact schema v2 exists for),
 * then nothing — an empty answer means the writer types the hours, and estimating never fails
 * over a guide being unreachable.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LaborTimeDefaultingService {

    private final CatalogLaborTimeClient catalogLaborTimeClient;
    private final ExtCatalogServiceReplicaRepository catalogServiceReplicaRepository;
    private final VehicleReferenceService vehicleReferenceService;

    /**
     * A guide answer to snapshot onto the estimate line.
     *
     * @param hours the guide's hours in tenths — the prefill and the recorded baseline
     * @param sourceCode provenance source
     * @param sourceRevision provenance revision
     * @param matchGrade the edge's vehicle-match confidence; {@code DEFAULT_HOURS} for the
     *     replica fallback
     * @param overlapGroup shared-setup group for overlap-aware summation
     * @param includedOpCodes comma-joined Durion codes included in this time; null when none
     */
    public record GuideDefault(
            @NonNull BigDecimal hours,
            @Nullable String sourceCode,
            @Nullable String sourceRevision,
            @Nullable String matchGrade,
            @Nullable String overlapGroup,
            @Nullable String includedOpCodes) {}

    /**
     * @param serviceId the catalog service on the LABOR line
     * @param customerId the estimate's customer, for vehicle resolution; null skips to a
     *     vehicle-less (widened) lookup
     * @param vehicleId the estimate's vehicle; null likewise widens
     */
    @NonNull
    public Optional<GuideDefault> lookupGuideTime(
            @NonNull UUID serviceId, @Nullable UUID customerId, @Nullable UUID vehicleId) {
        VehicleReferenceService.VehicleReference vehicle = vehicleReferenceService.resolve(customerId, vehicleId);

        Optional<CatalogLaborTimeClient.GuideTime> resolved =
                catalogLaborTimeClient.resolveLaborTime(serviceId, vehicle.year(), vehicle.make(), vehicle.model());
        if (resolved.isPresent()) {
            CatalogLaborTimeClient.GuideTime time = resolved.get();
            return Optional.of(new GuideDefault(
                    time.laborHours(),
                    time.sourceCode(),
                    time.sourceRevision(),
                    time.matchGrade(),
                    time.overlapGroup(),
                    joinCodes(time.includedOpCodes())));
        }

        // Edge unreachable or a typed miss: the replica's vehicle-agnostic default is the
        // degraded prefill the fact schema v2 exists for. An inactive (tombstoned) service
        // deliberately answers nothing.
        return catalogServiceReplicaRepository
                .findById(serviceId)
                .filter(ExtCatalogServiceReplica::isActive)
                .map(ExtCatalogServiceReplica::getDefaultLaborHours)
                .filter(hours -> hours != null && hours.signum() > 0)
                .map(hours -> new GuideDefault(hours, "DURION", "replica-default", "DEFAULT_HOURS", null, null));
    }

    @Nullable
    private static String joinCodes(@NonNull List<String> codes) {
        return codes.isEmpty() ? null : String.join(",", codes);
    }
}
