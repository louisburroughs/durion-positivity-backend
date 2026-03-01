package com.positivity.workorder.internal.service;

import com.positivity.workorder.internal.dto.WorkorderStatusDetail;
import com.positivity.workorder.internal.dto.WorkorderStatusHistoryEntry;
import com.positivity.workorder.internal.dto.WorkorderStatusView;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderStateTransition;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.internal.repository.WorkorderStateTransitionRepository;
import com.positivity.workorder.service.WipService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class WipServiceImpl implements WipService {

        private static final Set<WorkorderStatus> ACTIVE_WIP_STATUSES = EnumSet.of(
                        WorkorderStatus.APPROVED,
                        WorkorderStatus.ASSIGNED,
                        WorkorderStatus.WORK_IN_PROGRESS,
                        WorkorderStatus.AWAITING_PARTS,
                        WorkorderStatus.AWAITING_APPROVAL,
                        WorkorderStatus.READY_FOR_PICKUP);

        private final WorkorderRepository workorderRepository;
        private final WorkorderStateTransitionRepository stateTransitionRepository;

        @Override
        @Transactional(readOnly = true)
        public Page<WorkorderStatusView> getWipWorkorders(@NonNull String locationId,
                        boolean multiLocation,
                        @NonNull Pageable pageable) {
                log.debug("Fetching WIP workorders: multiLocation={}", multiLocation);
                if (multiLocation) {
                        return workorderRepository.findByStatusIn(ACTIVE_WIP_STATUSES, pageable)
                                        .map(this::toStatusView);
                }

                // F5: Guard malformed locationId — propagate as 400-producing exception
                // (ADR-0017)
                try {
                        UUID shopUuid = UUID.fromString(locationId);
                        return workorderRepository.findByShopIdAndStatusIn(shopUuid, ACTIVE_WIP_STATUSES, pageable)
                                        .map(this::toStatusView);
                } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException("locationId is not a valid UUID: " + locationId, e);
                }
        }

        @Override
        @Transactional(readOnly = true)
        public WorkorderStatusDetail getWipDetail(@NonNull UUID workorderId) {
                log.debug("Fetching WIP detail: workorderId(mask)={}", maskForLog(workorderId));
                Workorder wo = workorderRepository.findById(workorderId)
                                .orElseThrow(() -> new IllegalArgumentException("Workorder not found: " + workorderId));

                List<WorkorderStateTransition> transitions = stateTransitionRepository
                                .findByWorkorderIdOrderByTransitionedAtDesc(workorderId);
                List<WorkorderStatusHistoryEntry> history = transitions.stream()
                                .map(t -> WorkorderStatusHistoryEntry.builder()
                                                .status(t.getToStatus())
                                                .changedAt(t.getTransitionedAt())
                                                .changedBy(t.getTransitionedBy())
                                                .reason(t.getReason())
                                                .build())
                                .toList();

                // TODO(#14): Resolve blocking part numbers from workorder parts line items
                // (Inventory SoR)
                List<String> partsBlocking = wo.getStatus() == WorkorderStatus.AWAITING_PARTS
                                ? List.of("PARTS_PENDING")
                                : List.of();

                Instant lastUpdatedAt = wo.getUpdatedAt() != null
                                ? wo.getUpdatedAt().toInstant(ZoneOffset.UTC)
                                : Instant.now();
                String locationId = wo.getShopId() != null ? wo.getShopId().toString() : "";

                return WorkorderStatusDetail.builder()
                                .workorderId(wo.getId())
                                .status(wo.getStatus())
                                // TODO(#14): Resolve from TechnicianAssignment entity; null = unassigned
                                .assignedTechnicianId(null)
                                .locationId(locationId)
                                .estimatedCompletionTime(null)
                                // TODO(#14): Resolve customerName/phoneNumber from CRM service
                                .customerName(wo.getCustomerId() != null ? "customer-" + wo.getCustomerId() : "")
                                .phoneNumber(null)
                                // TODO(#14): Resolve vehicleInfo/vehicleVin from VehicleReference service
                                .vehicleInfo(wo.getVehicleId() != null ? "vehicle-" + wo.getVehicleId() : "")
                                .vehicleVin(null)
                                .lastUpdatedAt(lastUpdatedAt)
                                .statusHistory(history)
                                .partsBlocking(partsBlocking)
                                // TODO(#14): Resolve serviceDescription from workorder service lines
                                .serviceDescription("")
                                .internalNotes(null)
                                .build();
        }

        @SuppressWarnings("java:S3358")
        private WorkorderStatusView toStatusView(Workorder wo) {
                Instant lastUpdatedAt = wo.getUpdatedAt() != null
                                ? wo.getUpdatedAt().toInstant(ZoneOffset.UTC)
                                : Instant.now();
                return WorkorderStatusView.builder()
                                .workorderId(wo.getId())
                                .status(wo.getStatus())
                                // TODO(#14): Resolve from TechnicianAssignment entity; null = unassigned
                                .assignedTechnicianId(null)
                                .locationId(wo.getShopId() != null ? wo.getShopId().toString() : "")
                                .estimatedCompletionTime(null)
                                // TODO(#14): Resolve customerName from CRM service
                                .customerName(wo.getCustomerId() != null ? "customer-" + wo.getCustomerId() : "")
                                // TODO(#14): Resolve vehicleInfo from VehicleReference service
                                .vehicleInfo(wo.getVehicleId() != null ? "vehicle-" + wo.getVehicleId() : "")
                                .lastUpdatedAt(lastUpdatedAt)
                                .build();
        }

        private String maskForLog(Object value) {
                if (value == null) {
                        return "null";
                }
                String sanitized = value.toString()
                                .replace('\r', '_')
                                .replace('\n', '_')
                                .replace('\t', '_');
                int length = sanitized.length();
                if (length <= 4) {
                        return "****";
                }
                return sanitized.substring(0, 2) + "***" + sanitized.substring(length - 2);
        }
}
