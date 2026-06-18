package com.positivity.people.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;

public class WorkSessionSubmitRequest {

    @NotNull(message = "billableMinutes is required")
    @PositiveOrZero(message = "billableMinutes must be zero or greater")
    @Schema(description = "Total billable minutes for the session", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer billableMinutes;

    @NotNull(message = "breakMinutes is required")
    @PositiveOrZero(message = "breakMinutes must be zero or greater")
    @Schema(description = "Total break minutes for the session", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer breakMinutes;

    @NotNull(message = "submittedAt is required")
    @Schema(description = "Timestamp the session was submitted", requiredMode = Schema.RequiredMode.REQUIRED)
    private Instant submittedAt;

    public Integer getBillableMinutes() {
        return billableMinutes;
    }

    public void setBillableMinutes(Integer billableMinutes) {
        this.billableMinutes = billableMinutes;
    }

    public Integer getBreakMinutes() {
        return breakMinutes;
    }

    public void setBreakMinutes(Integer breakMinutes) {
        this.breakMinutes = breakMinutes;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(Instant submittedAt) {
        this.submittedAt = submittedAt;
    }
}
