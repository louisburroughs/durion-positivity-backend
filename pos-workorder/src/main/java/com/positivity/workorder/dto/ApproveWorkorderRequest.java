package com.positivity.workorder.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Request to approve a work order with customer signature")
public class ApproveWorkorderRequest {

    @NotNull(message = "customerId is required")
    @Schema(description = "Customer ID who is approving the work order", example = "12345", required = true)
    private Long customerId;

    @Schema(description = "Base64-encoded signature image data (PNG format recommended)", example = "data:image/png;base64,iVBORw0KGgoAAAANS...")
    private String signatureData;

    @Schema(description = "MIME type of signature (e.g., image/png, image/jpeg)", example = "image/png")
    @Builder.Default
    private String signatureMimeType = "image/png";

    @Schema(description = "Name of person providing signature", example = "John Doe")
    private String signerName;

    @Schema(description = "Additional notes or comments", example = "Approved after reviewing repair details")
    private String notes;

    @Schema(description = "Individual line item approvals/rejections. If omitted, all items are considered approved.")
    private List<LineItemApprovalDto> lineItemApprovals;
}
