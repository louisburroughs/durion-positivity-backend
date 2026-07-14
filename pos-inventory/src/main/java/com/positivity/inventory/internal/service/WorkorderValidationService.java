package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.entity.ExtWorkorderPartReplica;
import com.positivity.inventory.internal.entity.ExtWorkorderReplica;
import com.positivity.inventory.internal.repository.ExtWorkorderPartReplicaRepository;
import com.positivity.inventory.internal.repository.ExtWorkorderReplicaRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Workorder line validation served from the {@code ext_workorder} / {@code ext_workorder_part}
 * replicas (ADR-0044 §6, #897). Replaces the retired synchronous
 * {@code WorkorderValidationClient}; the verdict shape and failure semantics are unchanged.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkorderValidationService {

    private final ExtWorkorderReplicaRepository extWorkorderReplicaRepository;
    private final ExtWorkorderPartReplicaRepository extWorkorderPartReplicaRepository;

    @NonNull
    public WorkorderLineValidation getWorkorderLineValidation(
            @NonNull String workorderId, @NonNull String workorderLineId) {
        if (workorderLineId == null || workorderLineId.isBlank()) {
            throw new IllegalArgumentException("workorderLineId is required");
        }
        UUID workorderUuid = parseUuid(workorderId, "workorderId");
        UUID workorderLineUuid = parseUuid(workorderLineId, "workorderLineId");

        ExtWorkorderReplica workorder = extWorkorderReplicaRepository
                .findById(workorderUuid)
                .orElseThrow(() -> new IllegalStateException("No workorder replica row for workorderId " + workorderId
                        + " — verify the workorder.events.v1 feed is consumed"));
        if (workorder.getStatus() == null || workorder.getStatus().isBlank()) {
            throw new IllegalStateException("Workorder replica has no status for workorderId " + workorderId);
        }

        ExtWorkorderPartReplica matchedLine = extWorkorderPartReplicaRepository
                .findById(workorderLineUuid)
                .filter(line -> workorderUuid.equals(line.getWorkorderId()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "workorderLineId " + workorderLineId + " not found on workorder " + workorderId));

        if (matchedLine.getProductEntityId() == null) {
            throw new IllegalStateException(
                    "workorderLineId " + workorderLineId + " has no productEntityId on workorder " + workorderId);
        }

        return new WorkorderLineValidation(
                workorder.getStatus(), matchedLine.getProductEntityId().toString());
    }

    private UUID parseUuid(String value, String fieldName) {
        try {
            return UUID.fromString(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException(fieldName + " must be a valid UUID", ex);
        }
    }

    /** Validation verdict shape, unchanged from the retired client's contract. */
    public record WorkorderLineValidation(String status, String demandedProductId) {}
}
