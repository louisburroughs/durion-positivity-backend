package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Request to approve an estimate with customer signature")
public class ApproveEstimateRequest {

    @NotNull(message = "customerId is required")
    @Schema(
            description = "Customer ID who is approving the estimate",
            example = "00000000-0000-0000-0000-000000000001",
            requiredMode = REQUIRED)
    private UUID customerId;

    @Schema(
            description = "Base64-encoded signature image data (PNG format recommended)",
            example = "data:image/png;base64,iVBORw0KGgoAAAANS...",
            requiredMode = NOT_REQUIRED)
    private String signatureData;

    @Schema(
            description = "MIME type of signature (e.g., image/png, image/jpeg)",
            example = "image/png",
            requiredMode = NOT_REQUIRED)
    @Builder.Default
    private String signatureMimeType = "image/png";

    @Schema(description = "Name of person providing signature", example = "John Doe", requiredMode = NOT_REQUIRED)
    private String signerName;

    @Schema(
            description = "Additional notes or comments",
            example = "Approved with customer present",
            requiredMode = NOT_REQUIRED)
    private String notes;

    @Valid
    @Schema(
            description = "Individual line item approvals/rejections. If omitted, all items are considered approved.",
            requiredMode = NOT_REQUIRED)
    private List<LineItemApprovalDto> lineItemApprovals;

    @Schema(
            description = "Purchase order number (required when PO enforcement is enabled for the account)",
            example = "PO-2024-12345",
            requiredMode = NOT_REQUIRED)
    private String purchaseOrderNumber;
}
