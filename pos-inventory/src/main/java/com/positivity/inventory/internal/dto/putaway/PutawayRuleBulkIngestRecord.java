package com.positivity.inventory.internal.dto.putaway;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.inventory.internal.enums.PutawayDestinationStrategy;
import com.positivity.inventory.internal.enums.PutawayRuleMatchType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.Data;

/** A single putaway rule within a bulk ingest request. */
@Data
@Schema(description = "One putaway rule: which received lines go where")
public class PutawayRuleBulkIngestRecord {

    @Schema(description = "Resolution order within a tier; lower wins", example = "10", requiredMode = REQUIRED)
    @NotNull
    @Min(0)
    private Integer priority;

    @Schema(description = "What the rule matches on", example = "CATEGORY", requiredMode = REQUIRED)
    @NotNull
    private PutawayRuleMatchType matchType;

    @Schema(
            description = "The catalog id the rule matches. Required for every tier except ANY, which is the"
                    + " terminal fallback and must not carry one.",
            example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a20",
            requiredMode = NOT_REQUIRED)
    private UUID matchValue;

    @Schema(
            description = "Storage location matched lines are routed to",
            example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a01",
            requiredMode = REQUIRED)
    @NotNull
    private UUID destinationLocationId;

    @Schema(description = "How a destination is chosen within the rule", example = "FIXED", requiredMode = NOT_REQUIRED)
    private PutawayDestinationStrategy destinationStrategy;

    @Schema(
            description = "Whether the rule is in effect; defaults to true",
            example = "true",
            requiredMode = NOT_REQUIRED)
    private Boolean isEnabled;
}
