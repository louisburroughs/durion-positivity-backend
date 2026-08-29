package com.positivity.inventory.internal.dto.cyclecount.plan;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.Data;

/** A single cycle count plan within a bulk ingest request. */
@Data
@Schema(description = "One cycle count plan: which zones are counted, and when")
public class CycleCountPlanBulkIngestRecord {

    @Schema(
            description = "Site the plan belongs to. Defaults to the request's locationId when omitted.",
            example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a10",
            requiredMode = NOT_REQUIRED)
    private UUID locationId;

    @Schema(
            description = "Name of the plan, used to recognise it on a re-run",
            example = "Q1 Fast Movers",
            requiredMode = REQUIRED)
    @NotBlank
    private String planName;

    @Schema(description = "Storage locations the plan walks", requiredMode = REQUIRED)
    @NotEmpty
    private List<UUID> zoneIds;

    @Schema(
            description = "Exact date to schedule the count for. Must be strictly in the future. Prefer"
                    + " scheduledDaysOut for a file that will be replayed, since a fixed date rots.",
            example = "2026-06-01",
            requiredMode = NOT_REQUIRED)
    private LocalDate scheduledDate;

    @Schema(
            description = "Days from today to schedule the count. Used when scheduledDate is omitted, so a"
                    + " seeded plan is always validly in the future however long after authoring it runs.",
            example = "30",
            requiredMode = NOT_REQUIRED)
    @Min(1)
    private Integer scheduledDaysOut;
}
