package com.positivity.invoice.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/**
 * Request payload for invoice finalization.
 *
 * <p>
 * Role/permission level is derived from the authenticated principal in
 * {@code SecurityContext} — never supplied by the caller (ADR-0018).
 */
@Schema(description = "Request payload for finalizing an invoice")
public class FinalizationRequest {

    /**
     * Optional manager approval code. Required when the authenticated actor is a
     * SERVICE_ADVISOR and the invoice total exceeds $500.
     */
    @Nullable
    @Schema(
            description = "Manager approval code, required when approval threshold is exceeded",
            example = "MGR-7788",
            requiredMode = NOT_REQUIRED)
    private String managerApprovalCode;

    /** Optional free-text reason for overriding the amount limit. */
    @Nullable
    @Schema(
            description = "Free-text reason for overriding the amount limit",
            example = "Customer pre-approved by branch manager",
            requiredMode = NOT_REQUIRED)
    private String overrideReason;

    @Nullable
    public String getManagerApprovalCode() {
        return managerApprovalCode;
    }

    public void setManagerApprovalCode(@Nullable String managerApprovalCode) {
        this.managerApprovalCode = managerApprovalCode;
    }

    @Nullable
    public String getOverrideReason() {
        return overrideReason;
    }

    public void setOverrideReason(@Nullable String overrideReason) {
        this.overrideReason = overrideReason;
    }
}
