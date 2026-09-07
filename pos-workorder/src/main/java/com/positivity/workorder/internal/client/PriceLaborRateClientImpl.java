package com.positivity.workorder.internal.client;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * RestClient implementation of {@link PriceLaborRateClient}: {@code POST /v1/labor-rates/quote}
 * on the price service.
 *
 * <p><strong>This class is the grant.</strong> The ADR-0044 amendment dated 2026-09-07 permits
 * exactly this file to call pos-price synchronously (enforced file-scoped in the platform
 * {@code DomainWallsTest}); a second pos-workorder client reaching for pos-price fails the build
 * and argues its own case.
 *
 * <h2>Every failure is an empty answer</h2>
 *
 * Same contract as the labor-time client, and for the same reason: a service writer is adding an
 * estimate line, and nothing here may stop them. A connection failure, a non-2xx, an unparsable
 * body and the edge's own {@code NO_RATE_AVAILABLE} all become {@link Optional#empty()}, and the
 * caller leaves the price blank for the writer to type.
 *
 * <p>Unlike the labor-time path there is deliberately no replica fallback behind this: a stale
 * rate is a wrong number on an invoice, where a stale vehicle-agnostic time is only a less
 * precise prefill (ADR-0044 amendment 2026-09-07).
 */
@Slf4j
@Component
public class PriceLaborRateClientImpl implements PriceLaborRateClient {

    private final RestClient restClient;

    public PriceLaborRateClientImpl(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.price.service-id:price}") String serviceId) {
        this.restClient = restClientBuilder.baseUrl("http://" + serviceId).build();
    }

    @Override
    @NonNull
    public Optional<LaborRate> resolveLaborRate(
            @Nullable UUID locationId, @Nullable String operationCategory, @NonNull List<String> adjustmentCodes) {
        try {
            QuoteResponse response = restClient
                    .post()
                    .uri("/v1/labor-rates/quote")
                    .header("X-User", "pos-workorder")
                    .header("X-Authorities", "pricing:labor_rate:quote")
                    .body(new QuoteRequest(locationId, operationCategory, adjustmentCodes, null))
                    .retrieve()
                    .body(QuoteResponse.class);
            if (response == null || !"RESOLVED".equals(response.status()) || response.hourlyRate() == null) {
                return Optional.empty();
            }
            return Optional.of(new LaborRate(
                    response.hourlyRate(),
                    response.baseHourlyRate(),
                    response.currency(),
                    response.scope(),
                    response.rateId(),
                    response.steps() == null
                            ? List.of()
                            : response.steps().stream()
                                    .map(AppliedAdjustment::code)
                                    .toList()));
        } catch (RuntimeException e) {
            log.warn(
                    "Labor-rate resolution unavailable for location {} category {}: {}",
                    locationId,
                    operationCategory,
                    e.getMessage());
            return Optional.empty();
        }
    }

    // Wire records for the price.service.model contract (LaborRateQuoteRequest/Response).
    record QuoteRequest(UUID locationId, String operationCategory, List<String> adjustmentCodes, String at) {}

    record QuoteResponse(
            String status,
            BigDecimal hourlyRate,
            BigDecimal baseHourlyRate,
            String currency,
            String scope,
            UUID rateId,
            String effectiveFrom,
            List<AppliedAdjustment> steps) {}

    record AppliedAdjustment(String code, String type, BigDecimal value, BigDecimal resultingRate) {}
}
