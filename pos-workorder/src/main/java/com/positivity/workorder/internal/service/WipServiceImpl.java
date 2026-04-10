package com.positivity.workorder.internal.service;

import java.time.Clock;

import com.positivity.workorder.internal.dto.WorkorderStatusDetail;
import com.positivity.workorder.internal.dto.WorkorderStatusHistoryEntry;
import com.positivity.workorder.internal.dto.WorkorderStatusView;
import com.positivity.workorder.internal.entity.TechnicianAssignment;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderPart;
import com.positivity.workorder.internal.entity.WorkorderServiceLine;
import com.positivity.workorder.internal.entity.WorkorderStateTransition;
import com.positivity.workorder.internal.enums.WorkorderItemStatus;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.repository.TechnicianAssignmentRepository;
import com.positivity.workorder.internal.repository.WorkorderPartRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.internal.repository.WorkorderServiceRepository;
import com.positivity.workorder.internal.repository.WorkorderStateTransitionRepository;
import com.positivity.workorder.service.WipService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.ZoneOffset;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class WipServiceImpl implements WipService {
        private final Clock clock;

        private static final String PARTS_PENDING = "PARTS_PENDING";
        private static final Set<WorkorderStatus> ACTIVE_WIP_STATUSES = EnumSet.of(
                        WorkorderStatus.APPROVED,
                        WorkorderStatus.ASSIGNED,
                        WorkorderStatus.WORK_IN_PROGRESS,
                        WorkorderStatus.AWAITING_PARTS,
                        WorkorderStatus.AWAITING_APPROVAL,
                        WorkorderStatus.READY_FOR_PICKUP);
        private static final Set<WorkorderItemStatus> TERMINAL_ITEM_STATUSES = EnumSet.of(
                        WorkorderItemStatus.COMPLETED,
                        WorkorderItemStatus.CANCELLED);
        private static final int MAX_SERVICE_DESCRIPTIONS = 3;

        private final WorkorderRepository workorderRepository;
        private final WorkorderStateTransitionRepository stateTransitionRepository;
        private final TechnicianAssignmentRepository technicianAssignmentRepository;
        private final WorkorderPartRepository workorderPartRepository;
        private final WorkorderServiceRepository workorderServiceRepository;
        private final CustomerReferenceService customerReferenceService;
        private final VehicleReferenceService vehicleReferenceService;

        @Override
        @Transactional(readOnly = true)
        public Page<WorkorderStatusView> getWipWorkorders(@NonNull String locationId,
                        boolean multiLocation,
                        @NonNull Pageable pageable) {
                log.debug("Fetching WIP workorders: multiLocation={}", multiLocation);
                Page<Workorder> workorders;
                if (multiLocation) {
                        workorders = workorderRepository.findByStatusIn(ACTIVE_WIP_STATUSES, pageable);
                        return enrichStatusPage(workorders, pageable);
                }

                // F5: Guard malformed locationId — propagate as 400-producing exception
                // (ADR-0017)
                try {
                        UUID shopUuid = UUID.fromString(locationId);
                        workorders = workorderRepository.findByShopIdAndStatusIn(shopUuid, ACTIVE_WIP_STATUSES,
                                        pageable);
                        return enrichStatusPage(workorders, pageable);
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
                                .findByWorkorder_IdOrderByTransitionedAtDesc(workorderId);
                List<WorkorderStatusHistoryEntry> history = transitions.stream()
                                .map(t -> WorkorderStatusHistoryEntry.builder()
                                                .status(t.getToStatus())
                                                .changedAt(t.getTransitionedAt())
                                                .changedBy(t.getTransitionedBy())
                                                .reason(t.getReason())
                                                .build())
                                .toList();

                List<String> partsBlocking = resolveBlockingPartNumbers(wo);
                String assignedTechnicianId = technicianAssignmentRepository
                                .findByWorkorder_IdAndCurrentTrue(workorderId)
                                .map(TechnicianAssignment::getTechnicianId)
                                .map(UUID::toString)
                                .orElse(null);
                CustomerReferenceService.CustomerContact customerContact = customerReferenceService
                                .resolve(wo.getCustomerId());
                if (customerContact == null) {
                        customerContact = new CustomerReferenceService.CustomerContact(
                                        wo.getCustomerId() != null ? "customer-" + wo.getCustomerId() : "",
                                        null);
                }
                VehicleReferenceService.VehicleReference vehicleReference = vehicleReferenceService
                                .resolve(wo.getVehicleId());
                if (vehicleReference == null) {
                        vehicleReference = new VehicleReferenceService.VehicleReference(
                                        wo.getVehicleId() != null ? "vehicle-" + wo.getVehicleId() : "",
                                        null);
                }

                Instant lastUpdatedAt = wo.getUpdatedAt() != null
                                ? wo.getUpdatedAt().toInstant(ZoneOffset.UTC)
                                : Instant.now(clock);
                String locationId = wo.getShopId() != null ? wo.getShopId().toString() : "";

                return WorkorderStatusDetail.builder()
                                .workorderId(wo.getId())
                                .status(wo.getStatus())
                                .assignedTechnicianId(assignedTechnicianId)
                                .locationId(locationId)
                                .estimatedCompletionTime(null)
                                .customerName(customerContact.name())
                                .phoneNumber(customerContact.phoneNumber())
                                .vehicleInfo(vehicleReference.vehicleInfo())
                                .vehicleVin(vehicleReference.vin())
                                .lastUpdatedAt(lastUpdatedAt)
                                .statusHistory(history)
                                .partsBlocking(partsBlocking)
                                .serviceDescription(resolveServiceDescription(workorderId))
                                .internalNotes(null)
                                .build();
        }

        private Page<WorkorderStatusView> enrichStatusPage(Page<Workorder> workorders, Pageable pageable) {
                List<Workorder> content = workorders.getContent();
                if (content.isEmpty()) {
                        return new PageImpl<>(List.of(), pageable, workorders.getTotalElements());
                }

                Set<UUID> workorderIds = content.stream().map(Workorder::getId)
                                .collect(java.util.stream.Collectors.toSet());
                Map<UUID, String> technicianByWorkorderId = resolveAssignedTechnicians(workorderIds);
                Map<UUID, CustomerReferenceService.CustomerContact> customerById = customerReferenceService.resolveAll(
                                content.stream()
                                                .map(Workorder::getCustomerId)
                                                .filter(java.util.Objects::nonNull)
                                                .collect(java.util.stream.Collectors.toSet()));
                Map<UUID, VehicleReferenceService.VehicleReference> vehicleById = vehicleReferenceService.resolveAll(
                                content.stream()
                                                .map(Workorder::getVehicleId)
                                                .filter(java.util.Objects::nonNull)
                                                .collect(java.util.stream.Collectors.toSet()));
                final Map<UUID, CustomerReferenceService.CustomerContact> customerLookup = customerById;
                final Map<UUID, VehicleReferenceService.VehicleReference> vehicleLookup = vehicleById;

                List<WorkorderStatusView> mapped = content.stream()
                                .map(wo -> toStatusView(wo, technicianByWorkorderId, customerLookup, vehicleLookup))
                                .toList();

                return new PageImpl<>(mapped, pageable, workorders.getTotalElements());
        }

        private Map<UUID, String> resolveAssignedTechnicians(Set<UUID> workorderIds) {
                if (workorderIds.isEmpty()) {
                        return Map.of();
                }
                List<TechnicianAssignment> assignments = technicianAssignmentRepository
                                .findByWorkorder_IdInAndCurrentTrue(workorderIds);
                if (assignments.isEmpty()) {
                        return Map.of();
                }
                return assignments.stream()
                                .collect(java.util.stream.Collectors.toMap(
                                                TechnicianAssignment::getWorkorderId,
                                                assignment -> assignment.getTechnicianId() != null
                                                                ? assignment.getTechnicianId().toString()
                                                                : null,
                                                (existing, replacement) -> existing));
        }

        @SuppressWarnings("java:S3358")
        private WorkorderStatusView toStatusView(
                        Workorder wo,
                        Map<UUID, String> technicianByWorkorderId,
                        Map<UUID, CustomerReferenceService.CustomerContact> customerById,
                        Map<UUID, VehicleReferenceService.VehicleReference> vehicleById) {
                Instant lastUpdatedAt = wo.getUpdatedAt() != null
                                ? wo.getUpdatedAt().toInstant(ZoneOffset.UTC)
                                : Instant.now(clock);
                CustomerReferenceService.CustomerContact customerContact = wo.getCustomerId() != null
                                ? customerById.get(wo.getCustomerId())
                                : null;
                VehicleReferenceService.VehicleReference vehicleReference = wo.getVehicleId() != null
                                ? vehicleById.get(wo.getVehicleId())
                                : null;
                String customerName = customerContact != null ? customerContact.name()
                                : (wo.getCustomerId() != null ? "customer-" + wo.getCustomerId() : "");
                String vehicleInfo = vehicleReference != null ? vehicleReference.vehicleInfo()
                                : (wo.getVehicleId() != null ? "vehicle-" + wo.getVehicleId() : "");
                return WorkorderStatusView.builder()
                                .workorderId(wo.getId())
                                .status(wo.getStatus())
                                .assignedTechnicianId(technicianByWorkorderId.get(wo.getId()))
                                .locationId(wo.getShopId() != null ? wo.getShopId().toString() : "")
                                .estimatedCompletionTime(null)
                                .customerName(customerName)
                                .vehicleInfo(vehicleInfo)
                                .lastUpdatedAt(lastUpdatedAt)
                                .build();
        }

        private List<String> resolveBlockingPartNumbers(Workorder wo) {
                if (!isAwaitingPartsWithId(wo)) {
                        return List.of();
                }

                Map<UUID, WorkorderPart> deduplicated = loadDeduplicatedParts(wo.getId());

                if (deduplicated.isEmpty()) {
                        return List.of(PARTS_PENDING);
                }

                List<String> blocking = deduplicated.values().stream()
                                .filter(this::isBlockingPart)
                                .map(this::toPartBlockingReference)
                                .filter(StringUtils::hasText)
                                .distinct()
                                .toList();

                return blocking.isEmpty() ? List.of(PARTS_PENDING) : blocking;
        }

        private boolean isAwaitingPartsWithId(Workorder wo) {
                return wo.getStatus() == WorkorderStatus.AWAITING_PARTS && wo.getId() != null;
        }

        private Map<UUID, WorkorderPart> loadDeduplicatedParts(UUID workorderId) {
                Map<UUID, WorkorderPart> deduplicated = new LinkedHashMap<>();
                addPartsById(deduplicated, workorderPartRepository.findByWorkorderId(workorderId));
                addPartsById(deduplicated, workorderPartRepository.findByWorkOrderService_WorkOrder_Id(workorderId));
                return deduplicated;
        }

        private void addPartsById(Map<UUID, WorkorderPart> deduplicated, List<WorkorderPart> parts) {
                if (parts == null) {
                        return;
                }
                for (WorkorderPart part : parts) {
                        if (part.getId() != null) {
                                deduplicated.putIfAbsent(part.getId(), part);
                        }
                }
        }

        private boolean isBlockingPart(WorkorderPart part) {
                WorkorderItemStatus status = part.getStatus();
                if (status != null && TERMINAL_ITEM_STATUSES.contains(status)) {
                        return false;
                }
                if (status == WorkorderItemStatus.PENDING_APPROVAL) {
                        return false;
                }
                return hasUnfulfilledQuantity(part);
        }

        private boolean hasUnfulfilledQuantity(WorkorderPart part) {
                BigDecimal required = zeroIfNull(part.getQuantity());
                BigDecimal issued = zeroIfNull(part.getQuantityIssued());
                BigDecimal consumed = zeroIfNull(part.getQuantityConsumed());
                BigDecimal returned = zeroIfNull(part.getQuantityReturned());

                // Coverage is what has already been consumed plus what remains available on the
                // workorder after returns.
                BigDecimal currentlyAvailable = issued.subtract(consumed).subtract(returned);
                if (currentlyAvailable.compareTo(BigDecimal.ZERO) < 0) {
                        currentlyAvailable = BigDecimal.ZERO;
                }
                BigDecimal covered = consumed.add(currentlyAvailable);

                if (required.compareTo(BigDecimal.ZERO) <= 0) {
                        return true;
                }
                return covered.compareTo(required) < 0;
        }

        private BigDecimal zeroIfNull(BigDecimal value) {
                return value != null ? value : BigDecimal.ZERO;
        }

        private String toPartBlockingReference(WorkorderPart part) {
                if (StringUtils.hasText(part.getDescription())) {
                        return part.getDescription().trim();
                }
                if (part.getProductEntityId() != null) {
                        return part.getProductEntityId().toString();
                }
                if (part.getNonInventoryProductEntityId() != null) {
                        return part.getNonInventoryProductEntityId().toString();
                }
                return part.getId() != null ? part.getId().toString() : PARTS_PENDING;
        }

        private String resolveServiceDescription(UUID workorderId) {
                List<WorkorderServiceLine> services = workorderServiceRepository.findByWorkOrder_Id(workorderId);
                if (services == null || services.isEmpty()) {
                        return "";
                }

                List<String> descriptions = services.stream()
                                .map(WorkorderServiceLine::getDescription)
                                .filter(StringUtils::hasText)
                                .map(String::trim)
                                .distinct()
                                .toList();

                if (descriptions.isEmpty()) {
                        return "";
                }
                if (descriptions.size() <= MAX_SERVICE_DESCRIPTIONS) {
                        return String.join("; ", descriptions);
                }

                List<String> limited = descriptions.subList(0, MAX_SERVICE_DESCRIPTIONS);
                return String.join("; ", limited) + " +" + (descriptions.size() - MAX_SERVICE_DESCRIPTIONS) + " more";
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
