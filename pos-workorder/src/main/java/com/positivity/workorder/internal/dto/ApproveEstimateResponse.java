package com.positivity.workorder.internal.dto;

import com.positivity.workorder.internal.entity.Estimate;
import io.swagger.v3.oas.annotations.media.Schema;
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

    @Schema(description = "Estimate ID", example = "123")
    private UUID estimateId;

    @Schema(description = "Estimate number", example = "EST-2024-1001")
    private String estimateNumber;

    @Schema(description = "Customer ID who approved", example = "12345")
    private UUID customerId;

    @Schema(description = "Current status of estimate", example = "APPROVED")
    private String status;

    @Schema(description = "When the estimate was approved")
    private LocalDateTime approvedAt;

    @Schema(description = "Name of person who signed", example = "John Doe")
    private String signerName;

    @Schema(description = "Message confirming approval")
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
