package com.positivity.invoice.internal.dto;

import org.jspecify.annotations.Nullable;

import java.time.Instant;

public class FinalizationRequest {

    private String finalizedBy;
    private Instant finalizedAt;

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
}
