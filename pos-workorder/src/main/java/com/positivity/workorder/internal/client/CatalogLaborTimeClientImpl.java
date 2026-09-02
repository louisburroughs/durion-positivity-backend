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
 * RestClient implementation of {@link CatalogLaborTimeClient}: {@code POST
 * /v1/catalog/labor-times/resolve} on the catalog service.
 *
 * <p><strong>This class is the grant.</strong> The ADR-0044 amendment dated 2026-09-02 permits
 * exactly this file to call pos-catalog synchronously (enforced file-scoped in the platform
 * {@code DomainWallsTest}); a second pos-workorder client reaching for pos-catalog fails the
 * build and argues its own case.
 *
 * <h2>Every failure is an empty answer</h2>
 *
 * A service writer is adding an estimate line. Nothing here rethrows: connection refused, a
 * timeout, a non-2xx, an unparsable body, and the edge's own typed misses
 * ({@code NO_TIME_AVAILABLE}, {@code SOURCE_UNAVAILABLE}) all become {@link Optional#empty()},
 * and the caller degrades to the replica default hours and then to a blank prefill. The edge is
 * already built never to fail for a vendor-side problem, so an exception reaching the catch
 * means something structural — logged at warn, and still not allowed to block estimating.
 */
@Slf4j
@Component
public class CatalogLaborTimeClientImpl implements CatalogLaborTimeClient {

    private final RestClient restClient;

    public CatalogLaborTimeClientImpl(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.catalog.service-id:catalog}") String serviceId) {
        this.restClient = restClientBuilder.baseUrl("http://" + serviceId).build();
    }

    @Override
    @NonNull
    public Optional<GuideTime> resolveLaborTime(
            @NonNull UUID serviceId, @Nullable String vehicleYear, @Nullable String make, @Nullable String model) {
        try {
            ResolveResponse response = restClient
                    .post()
                    .uri("/v1/catalog/labor-times/resolve")
                    .header("X-User", "pos-workorder")
                    .header("X-Authorities", "catalog:labor_time:resolve")
                    .body(new ResolveRequest(serviceId, vehicleYear, make, model, null, null, null))
                    .retrieve()
                    .body(ResolveResponse.class);
            if (response == null || !"RESOLVED".equals(response.status()) || response.laborHours() == null) {
                return Optional.empty();
            }
            return Optional.of(new GuideTime(
                    response.laborHours(),
                    response.timeType(),
                    response.sourceCode(),
                    response.sourceRevision(),
                    response.matchGrade(),
                    response.overlapGroup(),
                    response.includedOpCodes() == null ? List.of() : response.includedOpCodes()));
        } catch (RuntimeException e) {
            log.warn("Labor-time resolution unavailable for service {}: {}", serviceId, e.getMessage());
            return Optional.empty();
        }
    }

    // Wire records for the catalog.service.model contract (LaborTimeQuoteRequest/Response).
    record ResolveRequest(
            UUID serviceId,
            String vehicleYear,
            String make,
            String model,
            String submodel,
            String engineCode,
            String preferredTimeType) {}

    record ResolveResponse(
            String status,
            BigDecimal laborHours,
            String timeType,
            String sourceCode,
            String sourceRevision,
            String matchGrade,
            String overlapGroup,
            List<String> includedOpCodes) {}
}
