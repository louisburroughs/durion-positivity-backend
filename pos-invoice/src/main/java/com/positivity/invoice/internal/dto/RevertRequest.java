package com.positivity.invoice.internal.dto;

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
public class RevertRequest {

    @NonNull
    @NotBlank
    private String managerApprovalCode;

    @NonNull
    @NotBlank
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
