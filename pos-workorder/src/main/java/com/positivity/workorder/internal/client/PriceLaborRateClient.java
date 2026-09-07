package com.positivity.workorder.internal.client;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The workorder-side face of the pos-price labor-rate resolution edge (#1575 Tier 0, ADR-0044
 * amendment 2026-09-07). Shaped exactly like {@link CatalogLaborTimeClient}: one method,
 * Optional-out, because the caller's next move is the same for every failure and every typed
 * miss — leave the price for the service writer to type.
 */
public interface PriceLaborRateClient {

    /**
     * @param locationId the location quoting the work; null resolves the platform default rate
     * @param operationCategory the catalog operation category, from the local catalog-service
     *     replica; null asks for the category-agnostic rate
     * @param adjustmentCodes labor-matrix steps the writer opted into
     * @return the resolved rate, or empty on any miss or failure
     */
    @NonNull
    Optional<LaborRate> resolveLaborRate(
            @Nullable UUID locationId, @Nullable String operationCategory, @NonNull List<String> adjustmentCodes);

    /**
     * A resolved hourly rate with the provenance the estimate snapshot records.
     *
     * @param hourlyRate the rate after the labor matrix — what the line is priced at
     * @param baseHourlyRate the rate before the matrix, kept so the two stay comparable on the
     *     quote the way guide hours and agreed hours are
     * @param currency ISO currency of both amounts
     * @param scope how specific the answering rate row was
     * @param rateId the pos-price row that answered
     * @param appliedCodes the matrix step codes that actually applied, in the order they were
     *     applied — a subset of what was asked for, since an unpriced code is skipped
     */
    record LaborRate(
            @NonNull BigDecimal hourlyRate,
            @Nullable BigDecimal baseHourlyRate,
            @Nullable String currency,
            @Nullable String scope,
            @Nullable UUID rateId,
            @NonNull List<String> appliedCodes) {}
}
