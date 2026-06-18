package com.positivity.invoice.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import org.jspecify.annotations.NonNull;

/**
 * Request payload for reverting a finalized invoice back to DRAFT (Story #13,
 * AC6).
 *
 * <p>
 * Reversion is only permitted:
 * <ul>
 * <li>Within 24h of finalization</li>
 * <li>Before the invoice has been POSTED to the general ledger</li>
 * <li>When a valid manager approval code is provided</li>
 * </ul>
 */
@Schema(description = "Request payload for reverting a finalized invoice back to DRAFT")
public class RevertRequest {

    @NonNull
    @NotBlank
    @Schema(
            description = "Manager approval code authorizing the reversion",
            example = "MGR-7788",
            requiredMode = REQUIRED)
    private String managerApprovalCode;

    @NonNull
    @NotBlank
    @Schema(
            description = "Reason the invoice is being reverted",
            example = "Incorrect line item billed",
            requiredMode = REQUIRED)
    private String reason;

    @NonNull
    public String getManagerApprovalCode() {
        return managerApprovalCode;
    }

    public void setManagerApprovalCode(@NonNull String managerApprovalCode) {
        this.managerApprovalCode = managerApprovalCode;
    }

    @NonNull
    public String getReason() {
        return reason;
    }

    public void setReason(@NonNull String reason) {
        this.reason = reason;
    }
}
