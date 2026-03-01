package com.positivity.invoice.internal.dto;

import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.Instant;

public class FinalizationRequest {

    private String finalizedBy;
    private Instant finalizedAt;

    // -- Story #13 scaffold fields --
    private String requestedBy;
    private String permissionLevel;
    private BigDecimal amountLimit;
    private boolean managerApprovalRequired;

    @Nullable
    private String managerApprovalCode;

    @Nullable
    private String overrideReason;

    @Nullable
    public String getFinalizedBy() {
        return finalizedBy;
    }

    public void setFinalizedBy(@Nullable String finalizedBy) {
        this.finalizedBy = finalizedBy;
    }

    @Nullable
    public Instant getFinalizedAt() {
        return finalizedAt;
    }

    public void setFinalizedAt(@Nullable Instant finalizedAt) {
        this.finalizedAt = finalizedAt;
    }

    @Nullable
    public String getRequestedBy() {
        return requestedBy;
    }

    public void setRequestedBy(@Nullable String requestedBy) {
        this.requestedBy = requestedBy;
    }

    @Nullable
    public String getPermissionLevel() {
        return permissionLevel;
    }

    public void setPermissionLevel(@Nullable String permissionLevel) {
        this.permissionLevel = permissionLevel;
    }

    @Nullable
    public BigDecimal getAmountLimit() {
        return amountLimit;
    }

    public void setAmountLimit(@Nullable BigDecimal amountLimit) {
        this.amountLimit = amountLimit;
    }

    public boolean isManagerApprovalRequired() {
        return managerApprovalRequired;
    }

    public void setManagerApprovalRequired(boolean managerApprovalRequired) {
        this.managerApprovalRequired = managerApprovalRequired;
    }

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
