package com.positivity.inventory.internal.dto.putaway;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A putaway rule as returned by the rule API (issue #1514). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A configured putaway rule")
public class PutawayRuleResponse {

    @Schema(description = "Rule identifier", example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a40")
    private String ruleId;

    @Schema(description = "Match priority; lower wins within a tier", example = "10")
    private Integer priority;

    @Schema(description = "Tier this rule matches at: SKU, SUBCATEGORY, CATEGORY or ANY", example = "CATEGORY")
    private String matchType;

    @Schema(
            description = "The catalog id this rule matches; null for ANY",
            example = "01960030-0000-7000-8000-000000000001")
    private String matchValue;

    @Schema(description = "The bin this rule points at", example = "01960004-0001-7000-8000-000000000047")
    private String destinationLocationId;

    @Schema(description = "How the destination is chosen once this rule wins", example = "FIXED")
    private String destinationStrategy;

    @Schema(description = "Whether the rule participates in matching", example = "true")
    private Boolean isEnabled;

    @Schema(description = "When the rule was created")
    private Instant createdAt;

    @Schema(description = "When the rule was last modified")
    private Instant updatedAt;
}
