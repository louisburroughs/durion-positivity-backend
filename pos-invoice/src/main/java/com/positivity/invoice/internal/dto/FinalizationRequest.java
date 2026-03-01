package com.positivity.invoice.internal.dto;

import org.jspecify.annotations.Nullable;

/**
 * Request payload for invoice finalization.
 *
 * <p>Role/permission level is derived from the authenticated principal in
 * {@code SecurityContext} — never supplied by the caller (ADR-0018).
 */
public class FinalizationRequest {

    /**
     * Optional manager approval code. Required when the authenticated actor is a
     * SERVICE_ADVISOR and the invoice total exceeds $500.
     */
    @Nullable
    private String managerApprovalCode;

    /** Optional free-text reason for overriding the amount limit. */
    @Nullable
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
