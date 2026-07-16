package com.positivity.workorder.internal.service;

import com.positivity.workorder.internal.dto.WorkorderNumberRef;
import com.positivity.workorder.internal.dto.WorkorderSearchResult;
import com.positivity.workorder.internal.entity.Estimate;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.service.WorkorderSearchService;
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

/**
 * Free-text workorder search resolving the query against customer names (via the
 * customer reference service) or the workorder id, enriching results with the
 * resolved customer display name.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkorderSearchServiceImpl implements WorkorderSearchService {

    private final WorkorderRepository workorderRepository;
    private final CustomerReferenceService customerReferenceService;
    private final VehicleReferenceService vehicleReferenceService;

    @Override
    public @NonNull Page<WorkorderSearchResult> search(
            @NonNull String q, @Nullable UUID customerId, @Nullable UUID vehicleId, @NonNull Pageable pageable) {
        // Resolve customer ids whose display name matches the query.
        List<UUID> nameMatchIds = customerReferenceService.searchIdsByName(q, 10).stream()
                .map(CustomerReferenceService.CustomerRef::customerId)
                .toList();

        // JPQL IN requires a non-empty collection; use a sentinel that cannot match a real id.
        List<UUID> customerIds = nameMatchIds.isEmpty() ? List.of(new UUID(0, 0)) : nameMatchIds;

        // Treat the query as a workorder id when it parses as a UUID.
        UUID idQuery = parseUuidOrNull(q);

        Page<Workorder> page =
                workorderRepository.searchByQuery(q, customerIds, idQuery, customerId, vehicleId, pageable);

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
}
