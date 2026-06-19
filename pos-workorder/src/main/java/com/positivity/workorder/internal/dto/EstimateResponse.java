package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.workorder.internal.entity.Estimate;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for estimate responses.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Response DTO for estimates")
public class EstimateResponse {

    @Schema(
            description = "Unique identifier for the estimate",
            example = "550e8400-e29b-41d4-a716-446655440000",
            requiredMode = REQUIRED)
    @NotNull
    private UUID id;

    @Schema(description = "Estimate number", example = "EST-2024-1000", requiredMode = REQUIRED)
    @NotNull
    private String estimateNumber;

    @Schema(description = "Customer ID", example = "550e8400-e29b-41d4-a716-446655440001", requiredMode = NOT_REQUIRED)
    private UUID customerId;

    @Schema(description = "Vehicle ID", example = "550e8400-e29b-41d4-a716-446655440002", requiredMode = NOT_REQUIRED)
    private UUID vehicleId;

    @Schema(description = "Location ID", example = "550e8400-e29b-41d4-a716-446655440003", requiredMode = NOT_REQUIRED)
    private UUID locationId;

    @Schema(description = "Currency UOM ID", example = "USD", requiredMode = NOT_REQUIRED)
    private String currencyUomId;

    @Schema(description = "Tax region ID", example = "550e8400-e29b-41d4-a716-446655440004", requiredMode = NOT_REQUIRED)
    private UUID taxRegionId;

    @Schema(description = "Estimate status", example = "DRAFT", requiredMode = REQUIRED)
    @NotNull
    private String status;

    @Schema(description = "Username who created the estimate", example = "john.doe", requiredMode = NOT_REQUIRED)
    private String createdByUserId;

    @Schema(description = "Date and time the estimate was created", example = "2026-01-15T09:30:00Z", requiredMode = NOT_REQUIRED)
    private Instant createdAt;

    @Schema(description = "Subtotal amount before tax", example = "150.00", requiredMode = NOT_REQUIRED)
    private BigDecimal subtotal;

    @Schema(description = "Tax amount", example = "12.38", requiredMode = NOT_REQUIRED)
    private BigDecimal taxAmount;

    @Schema(description = "Total amount including tax", example = "162.38", requiredMode = NOT_REQUIRED)
    private BigDecimal total;

    // CAP:003 — Approval workflow fields

    @Schema(
            description = "Date and time the estimate was submitted for approval",
            example = "2026-01-15T09:30:00",
            requiredMode = NOT_REQUIRED)
    private LocalDateTime submittedAt;

    @Schema(
            description = "Username who submitted the estimate for approval",
            example = "john.doe",
            requiredMode = NOT_REQUIRED)
    private String submittedBy;

    @Schema(
            description = "Date and time the approval window expires",
            example = "2026-01-15T09:30:00",
            requiredMode = NOT_REQUIRED)
    private LocalDateTime expiresAt;

    @Schema(
            description = "Date and time the estimate was approved",
            example = "2026-01-15T09:30:00",
            requiredMode = NOT_REQUIRED)
    private LocalDateTime approvedAt;

    @Schema(
            description = "Customer UUID who approved the estimate",
            example = "550e8400-e29b-41d4-a716-446655440005",
            requiredMode = NOT_REQUIRED)
    private UUID approvedBy;

    @Schema(description = "Base64-encoded signature image", example = "iVBORw0KGgo=", requiredMode = NOT_REQUIRED)
    private String signatureData;

    @Schema(description = "MIME type of the signature image", example = "image/png", requiredMode = NOT_REQUIRED)
    private String signatureMimeType;

    @Schema(description = "Name of person who signed", example = "Jane Customer", requiredMode = NOT_REQUIRED)
    private String signerName;

    @Schema(
            description = "Additional notes provided at approval time",
            example = "Customer approved all items",
            requiredMode = NOT_REQUIRED)
    private String approvalNotes;

    @Schema(
            description = "Purchase order number for commercial accounts",
            example = "PO-2024-12345",
            requiredMode = NOT_REQUIRED)
    private String purchaseOrderNumber;

    @Schema(description = "Optimistic locking version", example = "2", requiredMode = NOT_REQUIRED)
    private Integer version;

    @Schema(
            description = "CRM party identifier",
            example = "01952f4e-0000-7000-8000-000000000001",
            requiredMode = NOT_REQUIRED)
    private String crmPartyId;

    @Schema(
            description = "CRM vehicle identifier",
            example = "01952f4e-0000-7000-8000-000000000002",
            requiredMode = NOT_REQUIRED)
    private String crmVehicleId;

    @Schema(
            description = "List of CRM contact identifiers",
            example = "[\"01952f4e-0000-7000-8000-000000000003\"]",
            requiredMode = NOT_REQUIRED)
    private List<String> crmContactIds;

    /**
     * Convert entity to response DTO
     */
    public static EstimateResponse fromEntity(Estimate entity) {
        if (entity == null) {
            return null;
        }

        return EstimateResponse.builder()
                .id(entity.getId())
                .estimateNumber(entity.getEstimateNumber())
                .customerId(entity.getCustomerId())
                .vehicleId(entity.getVehicleId())
                .locationId(entity.getLocationId())
                .currencyUomId(entity.getCurrencyUomId())
                .taxRegionId(entity.getTaxRegionId())
                .status(entity.getStatus() != null ? entity.getStatus().name() : null)
                .createdByUserId(entity.getCreatedByUserId())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().truncatedTo(ChronoUnit.MILLIS) : null)
                .subtotal(entity.getSubtotal())
                .taxAmount(entity.getTaxAmount())
                .total(entity.getTotal())
                .submittedAt(entity.getSubmittedAt())
                .submittedBy(entity.getSubmittedBy())
                .expiresAt(entity.getExpiresAt())
                .approvedAt(entity.getApprovedAt())
                .approvedBy(entity.getApprovedBy())
                .signatureData(entity.getSignatureData())
                .signatureMimeType(entity.getSignatureMimeType())
                .signerName(entity.getSignerName())
                .approvalNotes(entity.getApprovalNotes())
                .purchaseOrderNumber(entity.getPurchaseOrderNumber())
                .version(entity.getVersion())
                .crmPartyId(entity.getCrmPartyId())
                .crmVehicleId(entity.getCrmVehicleId())
                .crmContactIds(entity.getCrmContactIds() != null ? List.copyOf(entity.getCrmContactIds()) : List.of())
                .build();
    }
}
