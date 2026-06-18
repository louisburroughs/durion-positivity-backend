package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
    @Schema(description = "Workorder ID", example = "550e8400-e29b-41d4-a716-446655440000", requiredMode = REQUIRED)
    @NotNull
    private UUID workorderId;

    @Schema(description = "Workorder number (from sequence)", example = "WO-2024-5001", requiredMode = NOT_REQUIRED)
    private String workorderNumber;

    @Schema(description = "Workorder status", example = "WORK_IN_PROGRESS", requiredMode = REQUIRED)
    @NotNull
    private WorkorderStatus status;

    @Schema(description = "Customer ID", example = "550e8400-e29b-41d4-a716-446655440001", requiredMode = REQUIRED)
    @NotNull
    private UUID customerId;

    @Schema(description = "Customer name", example = "John Doe", requiredMode = NOT_REQUIRED)
    private String customerName;

    @Schema(description = "Vehicle ID", example = "550e8400-e29b-41d4-a716-446655440002", requiredMode = REQUIRED)
    @NotNull
    private UUID vehicleId;

    @Schema(
            description = "Vehicle description",
            example = "\"2020 Toyota Camry (VIN: 1HGBH41JXMN109186)\"",
            requiredMode = NOT_REQUIRED)
    private String vehicleDescription;

    @Schema(description = "Creation timestamp", example = "2024-01-27T10:00:00Z", requiredMode = REQUIRED)
    @NotNull
    private Instant createdAt;

    @Schema(
            description = "Created by user ID",
            example = "550e8400-e29b-41d4-a716-446655440003",
            requiredMode = REQUIRED)
    @NotNull
    private UUID createdBy;

    // Derived status fields
    @Schema(
            description = "Is workorder started (derived from status)",
            example = "true",
            type = "boolean",
            requiredMode = NOT_REQUIRED)
    private Boolean isStarted;

    @Schema(
            description = "Is workorder in progress (derived from status)",
            example = "true",
            type = "boolean",
            requiredMode = NOT_REQUIRED)
    private Boolean isInProgress;

    @Schema(
            description = "Is workorder completed (derived from status)",
            example = "false",
            type = "boolean",
            requiredMode = NOT_REQUIRED)
    private Boolean isCompleted;

    @Schema(description = "Start timestamp", example = "2024-01-27T14:30:00Z", requiredMode = NOT_REQUIRED)
    private LocalDateTime startedAt;

    @Schema(
            description = "Assigned technician ID",
            example = "550e8400-e29b-41d4-a716-446655440004",
            requiredMode = NOT_REQUIRED)
    private UUID assignedTechnicianId;

    @Schema(description = "Assigned technician name", example = "Jane Smith", requiredMode = NOT_REQUIRED)
    private String assignedTechnicianName;

    // Line items
    @Schema(description = "Service line items with labor totals", requiredMode = NOT_REQUIRED)
    @Builder.Default
    private List<WorkorderServiceResponse> services = List.of();

    @Schema(description = "Part line items with usage totals", requiredMode = NOT_REQUIRED)
    @Builder.Default
    private List<WorkorderPartResponse> parts = List.of();

    // Financial fields (conditionally included)
    @Schema(
            description = "Estimated total (conditionally included based on canViewFinancials)",
            example = "1245.50",
            requiredMode = NOT_REQUIRED)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private BigDecimal estimatedTotal;

    @Schema(description = "Total labor cost (conditionally included)", example = "425.00", requiredMode = NOT_REQUIRED)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private BigDecimal laborTotal;

    @Schema(description = "Total parts cost (conditionally included)", example = "720.50", requiredMode = NOT_REQUIRED)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private BigDecimal partsTotal;

    @Schema(description = "Total tax (conditionally included)", example = "100.00", requiredMode = NOT_REQUIRED)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private BigDecimal taxTotal;

    // Capability flags
    @Schema(description = "Capability flags indicating allowed actions", requiredMode = REQUIRED)
    @NotNull
    private WorkorderCapabilities capabilities;
}
