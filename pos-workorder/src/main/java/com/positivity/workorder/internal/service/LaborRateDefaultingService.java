package com.positivity.workorder.internal.service;

import com.positivity.workorder.internal.client.PriceLaborRateClient;
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
 * Rate lookup for LABOR estimate items (#1575 Tier 0, #1569 residual R4).
 *
 * <p>The second operand of a labor line. {@link LaborTimeDefaultingService} answers how many
 * hours; this answers what an hour costs, and the line total is the product. #1569 recorded the
 * price half as hand-typed with no modelled source; Tier 0 gives it one.
 *
 * <p>The operation category comes from the local {@code ext_catalog_service} replica rather than
 * a second call to pos-catalog: it is exactly the kind of slow-moving classification a fact is
 * for, it already rides {@code catalog.service.updated} at schema v2, and a rate lookup must not
 * fan out to two modules. A service the replica has not seen simply resolves the
 * category-agnostic rate, which is a real answer rather than a failure.
 *
 * <p>Fail-soft throughout: an unreachable price edge or a scope with no rate in force yields an
 * empty answer and the writer types the price. Nothing here may stop someone writing an
 * estimate.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LaborRateDefaultingService {

    private final PriceLaborRateClient priceLaborRateClient;
    private final ExtCatalogServiceReplicaRepository catalogServiceReplicaRepository;

    /**
     * A resolved rate to snapshot onto the estimate line.
     *
     * @param hourlyRate the rate after the labor matrix — the prefill and the recorded price
     * @param baseHourlyRate the rate before the matrix, so an adjusted quote records both
     * @param currency ISO currency of both amounts
     * @param scope how specific the answering rate was
     * @param rateId the pos-price row that answered — the provenance a re-quote pins to
     * @param appliedCodes comma-joined matrix codes that applied; null when none did
     */
    public record RateDefault(
            @NonNull BigDecimal hourlyRate,
            @Nullable BigDecimal baseHourlyRate,
            @Nullable String currency,
            @Nullable String scope,
            @Nullable UUID rateId,
            @Nullable String appliedCodes) {}

    /**
     * @param serviceId the catalog service on the LABOR line, for its operation category
     * @param locationId the estimate's location; null resolves the platform default rate
     * @param adjustmentCodes labor-matrix steps the writer opted into; may be empty
     */
    @NonNull
    public Optional<RateDefault> lookupLaborRate(
            @Nullable UUID serviceId, @Nullable UUID locationId, @Nullable List<String> adjustmentCodes) {
        String category = serviceId == null
                ? null
                : catalogServiceReplicaRepository
                        .findById(serviceId)
                        .map(ExtCatalogServiceReplica::getOperationCategory)
                        .orElse(null);

        return priceLaborRateClient
                .resolveLaborRate(locationId, category, adjustmentCodes == null ? List.of() : adjustmentCodes)
                .map(rate -> new RateDefault(
                        rate.hourlyRate(),
                        rate.baseHourlyRate(),
                        rate.currency(),
                        rate.scope(),
                        rate.rateId(),
                        joinCodes(rate.appliedCodes())));
    }

    @Nullable
    private static String joinCodes(@NonNull List<String> codes) {
        return codes.isEmpty() ? null : String.join(",", codes);
    }
}
