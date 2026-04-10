package com.positivity.workorder.internal.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.positivity.workorder.internal.dto.WorkorderCapabilities;
import com.positivity.workorder.internal.dto.WorkorderDetailResponse;
import com.positivity.workorder.internal.dto.WorkorderPartResponse;
import com.positivity.workorder.internal.dto.WorkorderServiceResponse;
import com.positivity.workorder.internal.entity.TechnicianAssignment;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderLaborEntry;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.repository.TechnicianAssignmentRepository;
import com.positivity.workorder.internal.repository.WorkorderLaborEntryRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.service.WorkorderDetailService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Implementation of WorkorderDetailService for role-based visibility.
 *
 * <p>
 * Implements CAP-005 Story #155 - Role-Based Visibility
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkorderDetailServiceImpl implements WorkorderDetailService {

        private final WorkorderRepository workorderRepository;
        private final TechnicianAssignmentRepository technicianAssignmentRepository;
        private final WorkorderLaborEntryRepository laborEntryRepository;

        @Override
        @Transactional(readOnly = true)
        public @NonNull WorkorderDetailResponse getWorkorderDetail(
                        @NonNull UUID workorderId,
                        @NonNull Set<String> userAuthorities) {

                // Load workorder with relations
                Workorder workorder = workorderRepository.findById(workorderId)
                                .orElseThrow(() -> new IllegalArgumentException("Workorder not found: " + workorderId));

                // Load current technician assignment
                Optional<TechnicianAssignment> currentAssignment = technicianAssignmentRepository
                                .findByWorkorder_IdAndCurrentTrue(workorderId);

                // Calculate capability flags
                WorkorderCapabilities capabilities = calculateCapabilities(userAuthorities);

                // Build service line items with labor totals
                List<WorkorderServiceResponse> serviceResponses = buildServiceResponses(
                                workorder.getServices(),
                                capabilities.getCanViewFinancials());

                // Build part line items
                List<WorkorderPartResponse> partResponses = buildPartResponses(
                                workorder.getServices(),
                                capabilities.getCanViewFinancials());

                // Calculate derived status fields
                boolean isStarted = isWorkorderStarted(workorder.getStatus());
                boolean isInProgress = workorder.getStatus() == WorkorderStatus.WORK_IN_PROGRESS;
                boolean isCompleted = workorder.getStatus() == WorkorderStatus.COMPLETED;

                // Build response
                WorkorderDetailResponse.WorkorderDetailResponseBuilder builder = WorkorderDetailResponse.builder()
                                .workorderId(workorder.getId())
                                .workorderNumber(generateWorkorderNumber(workorder.getId())) // Placeholder - implement
                                                                                             // sequence lookup
                                                                                             // if needed
                                .status(workorder.getStatus())
                                .customerId(workorder.getCustomerId())
                                .customerName(lookupCustomerName(workorder.getCustomerId()))
                                .vehicleId(workorder.getVehicleId())
                                .vehicleDescription(lookupVehicleDescription(workorder.getVehicleId()))
                                .createdAt(workorder.getUpdatedAt().atZone(java.time.ZoneId.systemDefault())
                                                .toInstant()) // Approximate
                                                              // creation
                                                              // time
                                .createdBy(null) // Not tracked in entity yet
                                .isStarted(isStarted)
                                .isInProgress(isInProgress)
                                .isCompleted(isCompleted)
                                .startedAt(null) // Will be populated from state transitions if available
                                .assignedTechnicianId(currentAssignment.map(TechnicianAssignment::getTechnicianId)
                                                .orElse(null))
                                .assignedTechnicianName(currentAssignment
                                                .map(ta -> "Technician-" + ta.getTechnicianId()).orElse(null))
                                .services(serviceResponses)
                                .parts(partResponses)
                                .capabilities(capabilities);

                // Conditionally include financial fields
                if (capabilities.getCanViewFinancials()) {
                        BigDecimal laborTotal = calculateLaborTotal(serviceResponses);
                        BigDecimal partsTotal = calculatePartsTotal(partResponses);
                        BigDecimal estimatedTotal = laborTotal.add(partsTotal);

                        builder.estimatedTotal(estimatedTotal)
                                        .laborTotal(laborTotal)
                                        .partsTotal(partsTotal)
                                        .taxTotal(BigDecimal.ZERO); // Tax calculation not implemented yet
                }

                return builder.build();
        }

        private WorkorderCapabilities calculateCapabilities(Set<String> authorities) {
                return WorkorderCapabilities.builder()
                                .canStart(authorities.contains("workorder:workorder:start"))
                                .canApprove(authorities.contains("workorder:workorder:approve"))
                                .canAssignTechnician(authorities.contains("workorder:workorder:assign-technician"))
                                .canRecordLabor(authorities.contains("workorder:labor:add"))
                                .canRecordPartsUsage(authorities.contains("workorder:parts:add"))
                                .canViewFinancials(authorities.contains("workorder:financials:view")
                                                || authorities.contains("workorder:invoice:view"))
                                .canEditWorkorder(authorities.contains("workorder:workorder:edit"))
                                .canDeleteWorkorder(authorities.contains("workorder:workorder:delete"))
                                .build();
        }

        private List<WorkorderServiceResponse> buildServiceResponses(
                        List<com.positivity.workorder.internal.entity.WorkorderServiceLine> services,
                        boolean includeFinancials) {

                if (services == null || services.isEmpty()) {
                        return List.of();
                }

                return services.stream()
                                .map(service -> {
                                        // Calculate total labor hours from labor entries
                                        BigDecimal totalLaborHours = laborEntryRepository
                                                        .findByWorkorderService_IdOrderByStartTimeDesc(service.getId())
                                                        .stream()
                                                        .map(WorkorderLaborEntry::getHoursWorked)
                                                        .reduce(BigDecimal.ZERO, BigDecimal::add);

                                        WorkorderServiceResponse.WorkorderServiceResponseBuilder builder = WorkorderServiceResponse
                                                        .builder()
                                                        .id(service.getId())
                                                        .serviceEntityId(service.getServiceEntityId())
                                                        .description(service.getDescription())
                                                        .status(service.getStatus())
                                                        .quantity(service.getQuantity())
                                                        .totalLaborHours(totalLaborHours);

                                        if (includeFinancials) {
                                                builder.unitPrice(service.getUnitPrice())
                                                                .laborCost(totalLaborHours.multiply(
                                                                                service.getUnitPrice() != null
                                                                                                ? service.getUnitPrice()
                                                                                                : BigDecimal.ZERO))
                                                                .lineTotal(service.getLineTotal());
                                        }

                                        return builder.build();
                                })
                                .toList();
        }

        private List<WorkorderPartResponse> buildPartResponses(
                        List<com.positivity.workorder.internal.entity.WorkorderServiceLine> services,
                        boolean includeFinancials) {

                if (services == null || services.isEmpty()) {
                        return List.of();
                }

                // Collect all parts from services
                return services.stream()
                                .flatMap(service -> service.getParts() != null ? service.getParts().stream()
                                                : java.util.stream.Stream.empty())
                                .map(part -> {
                                        WorkorderPartResponse.WorkorderPartResponseBuilder builder = WorkorderPartResponse
                                                        .builder()
                                                        .id(part.getId())
                                                        .productEntityId(part.getProductEntityId())
                                                        .description(part.getDescription())
                                                        .status(part.getStatus())
                                                        .quantity(part.getQuantity())
                                                        .quantityIssued(part.getQuantityIssued())
                                                        .quantityConsumed(part.getQuantityConsumed())
                                                        .quantityReturned(part.getQuantityReturned());

                                        if (includeFinancials) {
                                                BigDecimal partCost = (part.getQuantity() != null
                                                                && part.getUnitPrice() != null)
                                                                                ? part.getQuantity().multiply(
                                                                                                part.getUnitPrice())
                                                                                : BigDecimal.ZERO;

                                                builder.unitPrice(part.getUnitPrice())
                                                                .partCost(partCost)
                                                                .lineTotal(part.getLineTotal());
                                        }

                                        return builder.build();
                                })
                                .toList();
        }

        private boolean isWorkorderStarted(WorkorderStatus status) {
                return status == WorkorderStatus.WORK_IN_PROGRESS
                                || status == WorkorderStatus.COMPLETED
                                || status == WorkorderStatus.READY_FOR_PICKUP;
        }

        private BigDecimal calculateLaborTotal(List<WorkorderServiceResponse> services) {
                return services.stream()
                                .map(WorkorderServiceResponse::getLaborCost)
                                .filter(Objects::nonNull)
                                .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        private BigDecimal calculatePartsTotal(List<WorkorderPartResponse> parts) {
                return parts.stream()
                                .map(WorkorderPartResponse::getPartCost)
                                .filter(Objects::nonNull)
                                .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        private String generateWorkorderNumber(UUID workorderId) {
                // Placeholder - implement proper sequence lookup if needed
                return "WO-" + workorderId.toString().substring(0, 8).toUpperCase();
        }

        private String lookupCustomerName(UUID customerId) {
                // Placeholder - implement customer service lookup
                return "Customer-" + customerId;
        }

        private String lookupVehicleDescription(UUID vehicleId) {
                // Placeholder - implement vehicle service lookup
                return "Vehicle-" + vehicleId;
        }
}
