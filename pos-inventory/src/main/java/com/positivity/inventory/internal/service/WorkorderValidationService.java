package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.entity.ExtWorkorderPartReplica;
import com.positivity.inventory.internal.entity.ExtWorkorderReplica;
import com.positivity.inventory.internal.exception.ReplicationPendingCodes;
import com.positivity.inventory.internal.repository.ExtWorkorderPartReplicaRepository;
import com.positivity.inventory.internal.repository.ExtWorkorderReplicaRepository;
import com.positivity.web.common.ReplicationPendingException;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Workorder line validation served from the {@code ext_workorder} / {@code ext_workorder_part}
 * replicas (ADR-0044 §6, #897). Replaces the retired synchronous
 * {@code WorkorderValidationClient}; the verdict shape is unchanged. A workorder or part row the
 * replica does not hold yet is {@code 503 WORKORDER_REPLICATION_PENDING} (#1994), not a conflict or
 * a bad argument: the row arrives by event, so absence may only mean "not yet". A present line that
 * belongs to another workorder, or lacks a product, keeps its status.
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
                .orElseThrow(() -> workorderReplicationPending(
                        "The workorder has not replicated from Workorder yet; retry shortly", workorderUuid));
        if (workorder.getStatus() == null || workorder.getStatus().isBlank()) {
            throw new IllegalStateException("Workorder replica has no status for workorderId " + workorderId);
        }

        ExtWorkorderPartReplica matchedLine = extWorkorderPartReplicaRepository
                .findById(workorderLineUuid)
                .orElseThrow(() -> workorderReplicationPending(
                        "The workorder line has not replicated from Workorder yet; retry shortly", workorderLineUuid));
        // The replicated workorder is present, so a line that belongs to some other workorder is
        // not lag but a wrong id: it keeps the 400 it always had.
        if (!workorderUuid.equals(matchedLine.getWorkorderId())) {
            throw new IllegalArgumentException(
                    "workorderLineId " + workorderLineId + " not found on workorder " + workorderId);
        }

        if (matchedLine.getProductEntityId() == null) {
            throw new IllegalStateException(
                    "workorderLineId " + workorderLineId + " has no productEntityId on workorder " + workorderId);
        }

        return new WorkorderLineValidation(
                workorder.getStatus(), matchedLine.getProductEntityId().toString());
    }

    /**
     * Whether a workorder status is a terminal one that no longer accepts parts issued to it —
     * shared by {@code ReceivingServiceImpl} (cross-dock eligibility) and the cross-dock workorder
     * search (#2211), so the two paths cannot drift on what "closed" means.
     */
    public static boolean isClosedWorkorderStatus(@Nullable String status) {
        if (status == null) {
            return false;
        }
        String normalizedStatus = status.trim().toUpperCase(Locale.ROOT);
        return "COMPLETED".equals(normalizedStatus)
                || "CANCELLED".equals(normalizedStatus)
                || "CLOSED".equals(normalizedStatus);
    }

    /** The workorder or part row is not in the replica: not-yet rather than not-found (#1994). */
    private static ReplicationPendingException workorderReplicationPending(String message, UUID awaitedId) {
        return new ReplicationPendingException(
                ReplicationPendingCodes.WORKORDER_REPLICATION_PENDING, message, awaitedId);
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
