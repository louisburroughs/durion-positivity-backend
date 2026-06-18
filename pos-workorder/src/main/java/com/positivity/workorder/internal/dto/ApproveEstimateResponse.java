package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.workorder.internal.entity.Estimate;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Response after approving an estimate")
public class ApproveEstimateResponse {

    @NotNull
    @Schema(description = "Estimate ID", example = "01960003-0000-7000-8000-000000000001", requiredMode = REQUIRED)
    private UUID estimateId;

    @Schema(description = "Estimate number", example = "EST-2024-1001", requiredMode = NOT_REQUIRED)
    private String estimateNumber;

    @NotNull
    @Schema(
            description = "Customer ID who approved",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    private UUID customerId;

    @NotNull
    @Schema(description = "Current status of estimate", example = "APPROVED", requiredMode = REQUIRED)
    private String status;

    @Schema(
            description = "When the estimate was approved",
            example = "2026-01-15T09:30:00",
            requiredMode = NOT_REQUIRED)
    private LocalDateTime approvedAt;

    @Schema(description = "Name of person who signed", example = "John Doe", requiredMode = NOT_REQUIRED)
    private String signerName;

    @Schema(
            description = "Message confirming approval",
            example = "Estimate approved successfully with signature captured",
            requiredMode = NOT_REQUIRED)
    private String message;

    public static ApproveEstimateResponse fromEntity(Estimate estimate) {
        return ApproveEstimateResponse.builder()
                .estimateId(estimate.getId())
                .estimateNumber(estimate.getEstimateNumber())
                .customerId(estimate.getCustomerId())
                .status(estimate.getStatus().name())
                .approvedAt(estimate.getApprovedAt())
                .signerName(estimate.getSignerName())
                .message("Estimate approved successfully with signature captured")
                .build();
    }
}
