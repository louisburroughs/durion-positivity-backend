package com.positivity.workorder.internal.service;

import com.positivity.workorder.internal.dto.WorkorderNumberRef;
import com.positivity.workorder.internal.dto.WorkorderSearchResult;
import com.positivity.workorder.internal.entity.Estimate;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Free-text workorder search resolving the query against customer names (via the
 * customer reference service) or the workorder id, enriching results with the
 * resolved customer display name.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkorderSearchServiceImpl implements WorkorderSearchService {

    /**
     * Widest {@code createdAt} bounds usable when the caller supplies no date filter. A {@code null}
     * parameter carries no type Postgres can infer inside a temporal comparison, and {@link
     * Instant#MIN}/{@link Instant#MAX} fall outside what a timestamp column can hold, so these are the
     * narrowest bounds that still cannot exclude any real workorder (mirrors {@code
     * TimeEntryServiceImpl.UNBOUNDED_START/END} in pos-people).
     */
    private static final Instant UNBOUNDED_CREATED_FROM = Instant.parse("0001-01-01T00:00:00Z");

    private static final Instant UNBOUNDED_CREATED_TO = Instant.parse("9999-12-31T23:59:59Z");

    private final WorkorderRepository workorderRepository;
    private final CustomerReferenceService customerReferenceService;
    private final VehicleReferenceService vehicleReferenceService;

    @Override
    public @NonNull Page<WorkorderSearchResult> search(
            @NonNull String q,
            @Nullable UUID customerId,
            @Nullable UUID vehicleId,
            @Nullable WorkorderStatus status,
            @Nullable LocalDate createdFrom,
            @Nullable LocalDate createdTo,
            @Nullable UUID technicianId,
            @NonNull Pageable pageable) {
        // Resolve customer ids whose display name matches the query -- but only when a query term
        // was actually supplied. An empty q sent to the name search is not a no-op: the customer
        // directory may treat it as "match everything" and hand back up to 10 arbitrary ids, which
        // would then inject spurious matches into what the caller intended as a filters-only listing
        // (mirrors InvoiceSearchServiceImpl's hasQuery guard, #1599/E11).
        List<UUID> nameMatchIds = StringUtils.hasText(q)
                ? customerReferenceService.searchIdsByName(q, 10).stream()
                        .map(CustomerReferenceService.CustomerRef::customerId)
                        .toList()
                : List.of();

        // JPQL IN requires a non-empty collection; use a sentinel that cannot match a real id.
        List<UUID> customerIds = nameMatchIds.isEmpty() ? List.of(new UUID(0, 0)) : nameMatchIds;

        // Treat the query as a workorder id when it parses as a UUID.
        UUID idQuery = parseUuidOrNull(q);

        Instant createdFromInstant = createdFrom == null ? UNBOUNDED_CREATED_FROM : rangeStart(createdFrom);
        Instant createdToInstant = createdTo == null ? UNBOUNDED_CREATED_TO : rangeEndExclusive(createdTo);

        Page<Workorder> page = workorderRepository.searchByQuery(
                q,
                customerIds,
                idQuery,
                customerId,
                vehicleId,
                status,
                createdFromInstant,
                createdToInstant,
                technicianId,
                pageable);

        // Enrich each row with the resolved customer display name and vehicle label/VIN.
        List<UUID> pageCustomerIds = page.getContent().stream()
                .map(Workorder::getCustomerId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<UUID, CustomerReferenceService.CustomerContact> contacts =
                customerReferenceService.resolveAll(pageCustomerIds);
        List<VehicleReferenceService.VehicleKey> vehicleKeys = page.getContent().stream()
                .filter(w -> w.getVehicleId() != null)
                .map(w -> new VehicleReferenceService.VehicleKey(w.getCustomerId(), w.getVehicleId()))
                .toList();
        Map<UUID, VehicleReferenceService.VehicleReference> vehicles = vehicleReferenceService.resolveAll(vehicleKeys);

        return page.map(workorder -> {
            CustomerReferenceService.CustomerContact contact =
                    workorder.getCustomerId() != null ? contacts.get(workorder.getCustomerId()) : null;
            VehicleReferenceService.VehicleReference vehicle =
                    workorder.getVehicleId() != null ? vehicles.get(workorder.getVehicleId()) : null;
            Estimate estimate = workorder.getEstimate();
            return WorkorderSearchResult.builder()
                    .workorderId(workorder.getId())
                    .workorderNumber(workorder.getWorkorderNumber())
                    .estimateNumber(estimate != null ? estimate.getEstimateNumber() : null)
                    .status(workorder.getStatus())
                    .customerId(workorder.getCustomerId())
                    .customerName(contact != null ? contact.name() : null)
                    .vehicleId(workorder.getVehicleId())
                    .vehicleLabel(vehicle != null ? vehicle.vehicleInfo() : null)
                    .vin(vehicle != null ? vehicle.vin() : null)
                    .createdAt(workorder.getCreatedAt())
                    .build();
        });
    }

    @Override
    public @NonNull List<WorkorderNumberRef> resolveNumbers(@NonNull Collection<UUID> workorderIds) {
        List<UUID> ids =
                workorderIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        return workorderRepository.findAllById(ids).stream()
                .map(w -> new WorkorderNumberRef(w.getId(), w.getWorkorderNumber()))
                .toList();
    }

    private static @Nullable UUID parseUuidOrNull(@NonNull String value) {
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** Inclusive start-of-day instant (UTC) for a {@code createdFrom} date, matching the analytics
     * endpoints' date-window convention (E5/E6/E7, #1593-#1595). */
    private static Instant rangeStart(LocalDate date) {
        return date.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** Exclusive end-of-window instant (UTC) for a {@code createdTo} date — start of the following
     * day, so the whole {@code createdTo} calendar day is included. */
    private static Instant rangeEndExclusive(LocalDate date) {
        return date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
