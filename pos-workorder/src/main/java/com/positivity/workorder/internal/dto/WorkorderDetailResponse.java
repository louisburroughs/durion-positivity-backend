package com.positivity.workorder.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.positivity.workorder.internal.entity.WorkorderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Comprehensive workorder detail response with role-based visibility.
 * Financial fields are conditionally included based on user authorities.
 * 
 * <p>
 * Implements CAP-005 Story #155 - Role-Based Visibility
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Comprehensive workorder detail with role-based visibility")
public class WorkorderDetailResponse {

    // Core fields
    @Schema(description = "Workorder ID", example = "550e8400-e29b-41d4-a716-446655440000", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID workorderId;

    @Schema(description = "Workorder number (from sequence)", example = "WO-2024-5001")
    private String workorderNumber;

    @Schema(description = "Workorder status", example = "WORK_IN_PROGRESS", requiredMode = Schema.RequiredMode.REQUIRED)
    private WorkorderStatus status;

    @Schema(description = "Customer ID", example = "550e8400-e29b-41d4-a716-446655440001", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID customerId;

    @Schema(description = "Customer name", example = "John Doe")
    private String customerName;

    @Schema(description = "Vehicle ID", example = "550e8400-e29b-41d4-a716-446655440002", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID vehicleId;

    @Schema(description = "Vehicle description", example = "2020 Toyota Camry (VIN: 1HGBH41JXMN109186)")
    private String vehicleDescription;

    @Schema(description = "Creation timestamp", example = "2024-01-27T10:00:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
    private Instant createdAt;

    @Schema(description = "Created by user ID", example = "550e8400-e29b-41d4-a716-446655440003", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID createdBy;

    // Derived status fields
    @Schema(description = "Is workorder started (derived from status)", example = "true")
    private Boolean isStarted;

    @Schema(description = "Is workorder in progress (derived from status)", example = "true")
    private Boolean isInProgress;

    @Schema(description = "Is workorder completed (derived from status)", example = "false")
    private Boolean isCompleted;

    @Schema(description = "Start timestamp", example = "2024-01-27T14:30:00Z")
    private LocalDateTime startedAt;

    @Schema(description = "Assigned technician ID", example = "550e8400-e29b-41d4-a716-446655440004")
    private UUID assignedTechnicianId;

    @Schema(description = "Assigned technician name", example = "Jane Smith")
    private String assignedTechnicianName;

    // Line items
    @Schema(description = "Service line items with labor totals")
    @Builder.Default
    private List<WorkorderServiceResponse> services = List.of();

    @Schema(description = "Part line items with usage totals")
    @Builder.Default
    private List<WorkorderPartResponse> parts = List.of();

    // Financial fields (conditionally included)
    @Schema(description = "Estimated total (conditionally included based on canViewFinancials)", example = "1245.50")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private BigDecimal estimatedTotal;

    @Schema(description = "Total labor cost (conditionally included)", example = "425.00")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private BigDecimal laborTotal;

    @Schema(description = "Total parts cost (conditionally included)", example = "720.50")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private BigDecimal partsTotal;

    @Schema(description = "Total tax (conditionally included)", example = "100.00")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private BigDecimal taxTotal;

    // Capability flags
    @Schema(description = "Capability flags indicating allowed actions", requiredMode = Schema.RequiredMode.REQUIRED)
    private WorkorderCapabilities capabilities;
}
