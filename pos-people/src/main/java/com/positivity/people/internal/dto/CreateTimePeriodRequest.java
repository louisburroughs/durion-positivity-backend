package com.positivity.people.internal.dto;

import com.positivity.people.internal.enums.TimePeriodStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to create a pay period for timekeeping approval")
public class CreateTimePeriodRequest {

    @NotNull
    @Schema(
            description = "Tenant the period belongs to",
            example = "01960000-0000-7000-8000-000000000001",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID tenantId;

    @NotNull
    @Schema(
            description = "First date of the period (inclusive)",
            example = "2026-06-01",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDate startDate;

    @NotNull
    @Schema(
            description = "Last date of the period (inclusive)",
            example = "2026-06-14",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDate endDate;

    @Schema(
            description = "Initial lifecycle status; defaults to OPEN when omitted",
            example = "OPEN",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private TimePeriodStatus status;
}
