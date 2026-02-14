package com.positivity.workorder.internal.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import com.positivity.workorder.internal.entity.Estimate;

import io.swagger.v3.oas.annotations.media.Schema;
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

    @Schema(description = "Unique identifier for the estimate", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID id;

    @Schema(description = "Estimate number", example = "EST-2024-1000")
    private String estimateNumber;

    @Schema(description = "Customer ID", example = "550e8400-e29b-41d4-a716-446655440001")
    private UUID customerId;

    @Schema(description = "Vehicle ID", example = "550e8400-e29b-41d4-a716-446655440002")
    private UUID vehicleId;

    @Schema(description = "Location ID", example = "550e8400-e29b-41d4-a716-446655440003")
    private UUID locationId;

    @Schema(description = "Currency UOM ID", example = "USD")
    private String currencyUomId;

    @Schema(description = "Tax region ID", example = "550e8400-e29b-41d4-a716-446655440004")
    private UUID taxRegionId;

    @Schema(description = "Estimate status", example = "DRAFT")
    private String status;

    @Schema(description = "User ID who created the estimate", example = "550e8400-e29b-41d4-a716-446655440005")
    private UUID createdByUserId;

    @Schema(description = "Date and time the estimate was created")
    private LocalDateTime createdAt;

    @Schema(description = "Subtotal amount before tax", example = "150.00")
    private BigDecimal subtotal;

    @Schema(description = "Tax amount", example = "12.38")
    private BigDecimal taxAmount;

    @Schema(description = "Total amount including tax", example = "162.38")
    private BigDecimal total;

    // CAP:003 — Approval workflow fields

    @Schema(description = "Date and time the estimate was submitted for approval")
    private LocalDateTime submittedAt;

    @Schema(description = "User ID who submitted the estimate for approval")
    private UUID submittedBy;

    @Schema(description = "Date and time the approval window expires")
    private LocalDateTime expiresAt;

    @Schema(description = "Date and time the estimate was approved")
    private LocalDateTime approvedAt;

    @Schema(description = "User ID who approved the estimate")
    private UUID approvedBy;

    @Schema(description = "Base64-encoded signature image")
    private String signatureData;

    @Schema(description = "MIME type of the signature image", example = "image/png")
    private String signatureMimeType;

    @Schema(description = "Name of person who signed")
    private String signerName;

    @Schema(description = "Additional notes provided at approval time")
    private String approvalNotes;

    @Schema(description = "Purchase order number for commercial accounts", example = "PO-2024-12345")
    private String purchaseOrderNumber;

    @Schema(description = "Optimistic locking version")
    private Integer version;

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
                .createdAt(entity.getCreatedAt())
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
                .build();
    }
}
