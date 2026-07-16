package com.positivity.warranty.internal.client;

import java.math.BigDecimal;
import java.time.Instant;
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
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * RestClient implementation of {@link WorkorderClient}.
 *
 * <p>pos-workorder reads demand authority {@code workorder:workorder:view}
 * (the /detail endpoint gates financial fields by the caller's authorities).
 */
@Slf4j
@Component
public class WorkorderClientImpl implements WorkorderClient {

    private static final String SERVICE_USER = "pos-warranty-service";
    private static final String AUTHORITIES = "workorder:workorder:view";
    private static final int SEARCH_PAGE_SIZE = 50;

    private final RestClient restClient;
    private final String basePath;

    public WorkorderClientImpl(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.workorder.service-id:workorder}") String serviceId,
            @Value("${pos.workorder.base-path:/v1/workorders}") String basePath) {
        this.basePath = basePath;
        this.restClient = restClientBuilder.baseUrl("http://" + serviceId).build();
    }

    @Override
    public @NonNull List<WorkorderSummary> searchWorkorders(@Nullable UUID customerId, @Nullable UUID vehicleId) {
        try {
            SearchPageWire page = restClient
                    .get()
                    .uri(uriBuilder -> {
                        // Explicit newest-first sort so the single fetched page is deterministic
                        // even if the remote default ordering changes.
                        var builder = uriBuilder
                                .path(basePath + "/search")
                                .queryParam("size", SEARCH_PAGE_SIZE)
                                .queryParam("sort", "createdAt,desc");
                        if (customerId != null) {
                            builder = builder.queryParam("customerId", customerId);
                        }
                        if (vehicleId != null) {
                            builder = builder.queryParam("vehicleId", vehicleId);
                        }
                        return builder.build();
                    })
                    .header("X-User", SERVICE_USER)
                    .header("X-Authorities", AUTHORITIES)
                    .retrieve()
                    .body(SearchPageWire.class);
            if (page == null || page.content() == null) {
                return List.of();
            }
            if (page.totalElements() != null && page.totalElements() > SEARCH_PAGE_SIZE) {
                log.warn(
                        "Workorder candidate search truncated for customerId={} vehicleId={}:"
                                + " {} matches, only newest {} fetched",
                        customerId,
                        vehicleId,
                        page.totalElements(),
                        SEARCH_PAGE_SIZE);
            }
            return page.content().stream()
                    .map(row -> new WorkorderSummary(
                            row.workorderId(),
                            row.workorderNumber(),
                            row.status(),
                            row.customerId(),
                            row.vehicleId(),
                            row.vin(),
                            row.createdAt()))
                    .toList();
        } catch (RestClientResponseException ex) {
            log.warn(
                    "Workorder search failed for customerId={} vehicleId={}: HTTP {}",
                    customerId,
                    vehicleId,
                    ex.getStatusCode().value());
            return List.of();
        } catch (RestClientException ex) {
            log.warn(
                    "pos-workorder unreachable for search customerId={} vehicleId={}: {}",
                    customerId,
                    vehicleId,
                    ex.getMessage());
            return List.of();
        }
    }

    @Override
    public @NonNull Optional<WorkorderDetail> getWorkorderDetail(@NonNull UUID workorderId) {
        try {
            DetailWire response = restClient
                    .get()
                    .uri(basePath + "/{workorderId}/detail", workorderId)
                    .header("X-User", SERVICE_USER)
                    .header("X-Authorities", AUTHORITIES)
                    .retrieve()
                    .body(DetailWire.class);
            if (response == null) {
                return Optional.empty();
            }
            List<ServiceLine> services = response.services() == null
                    ? List.of()
                    : response.services().stream()
                            .map(svc -> new ServiceLine(
                                    svc.id(), svc.description(), svc.quantity(), svc.unitPrice(), svc.lineTotal()))
                            .toList();
            List<PartLine> parts = response.parts() == null
                    ? List.of()
                    : response.parts().stream()
                            .map(part -> new PartLine(
                                    part.id(),
                                    part.productEntityId(),
                                    part.description(),
                                    part.quantity(),
                                    part.unitPrice(),
                                    part.lineTotal(),
                                    part.photoEvidenceUrl()))
                            .toList();
            return Optional.of(new WorkorderDetail(
                    response.workorderId(),
                    response.workorderNumber(),
                    response.customerId(),
                    response.vehicleId(),
                    services,
                    parts));
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) {
                log.debug("Workorder {} not found", workorderId);
            } else {
                log.warn(
                        "Workorder detail failed for workorderId={}: HTTP {}",
                        workorderId,
                        ex.getStatusCode().value());
            }
            return Optional.empty();
        } catch (RestClientException ex) {
            log.warn("pos-workorder unreachable for workorderId={}: {}", workorderId, ex.getMessage());
            return Optional.empty();
        }
    }

    // -----------------------------------------------------------------------
    // Wire shapes (mirror the pos-workorder API contract; unknown properties ignored)
    // -----------------------------------------------------------------------

    private record SearchPageWire(List<SearchRowWire> content, Long totalElements) {}

    private record SearchRowWire(
            UUID workorderId,
            String workorderNumber,
            String status,
            UUID customerId,
            UUID vehicleId,
            String vin,
            Instant createdAt) {}

    private record DetailWire(
            UUID workorderId,
            String workorderNumber,
            UUID customerId,
            UUID vehicleId,
            List<ServiceWire> services,
            List<PartWire> parts) {}

    /**
     * Mirrors WorkorderServiceResponse: {@code quantity} is authorized labor hours,
     * {@code unitPrice} is the hourly rate.
     */
    private record ServiceWire(
            UUID id, String description, BigDecimal quantity, BigDecimal unitPrice, BigDecimal lineTotal) {}

    private record PartWire(
            UUID id,
            UUID productEntityId,
            String description,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal lineTotal,
            String photoEvidenceUrl) {}
}
