package com.positivity.inventory.internal.dto.putaway;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.inventory.internal.enums.PutawayDestinationStrategy;
import com.positivity.inventory.internal.enums.PutawayRuleMatchType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Request body for creating or replacing a putaway rule (issue #1514). */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(
        description = "A putaway rule: what a received line must be for this rule to govern it, and where that line"
                + " should then be suggested to go")
public class PutawayRuleRequest {

    @Schema(
            description = "Lower wins. Ties are broken arbitrarily, so give rules in the same tier distinct"
                    + " priorities if the order between them matters.",
            example = "10",
            requiredMode = REQUIRED)
    @NotNull
    @Min(0)
    private Integer priority;

    @Schema(
            description = "Which tier this rule matches at. Resolution runs SKU, then SUBCATEGORY, then CATEGORY,"
                    + " then ANY, and stops at the first tier with a match — so a SKU rule always beats a"
                    + " subcategory rule for the same item, whatever their priorities.",
            example = "CATEGORY",
            requiredMode = REQUIRED)
    @NotNull
    private PutawayRuleMatchType matchType;

    @Schema(
            description = "The id this rule matches: a catalog product id for SKU, a catalog subcategory id for"
                    + " SUBCATEGORY, a catalog category id for CATEGORY. Must be omitted for ANY, which matches"
                    + " every line.",
            example = "01960030-0000-7000-8000-000000000001",
            requiredMode = NOT_REQUIRED)
    private String matchValue;

    @Schema(
            description = "The bin this rule points at. For FIXED it is the suggested destination outright; for"
                    + " LAST_USED and CLOSEST_AVAILABLE it is the anchor the strategy searches from and the"
                    + " fallback when the strategy cannot be honoured.",
            example = "01960004-0001-7000-8000-000000000047",
            requiredMode = REQUIRED)
    @NotNull
    private UUID destinationLocationId;

    @Schema(
            description = "How the destination is chosen once this rule wins; defaults to FIXED",
            example = "FIXED",
            requiredMode = NOT_REQUIRED)
    private PutawayDestinationStrategy destinationStrategy;

    @Schema(
            description = "Whether the rule participates in matching; defaults to true. A disabled rule is"
                    + " unreachable and is not subject to the single-enabled-ANY-rule constraint.",
            example = "true",
            requiredMode = NOT_REQUIRED)
    private Boolean isEnabled;

    /**
     * A typed rule with no value matches nothing; an {@code ANY} rule with a value implies a
     * restriction the matcher does not apply. Both are silent misconfiguration — an operator would
     * author a rule, see it accepted, and never see it fire — so both are rejected at the edge as
     * well as by the database CHECK constraint.
     */
    @AssertTrue(message = "matchValue is required for SKU, SUBCATEGORY and CATEGORY rules, and must be omitted for ANY")
    @Schema(hidden = true)
    public boolean isMatchValuePresentExactlyWhenRequired() {
        if (matchType == null) {
            return true; // @NotNull already reports this; do not double-report it.
        }
        boolean hasValue = matchValue != null && !matchValue.isBlank();
        return matchType.requiresMatchValue() == hasValue;
    }

    /**
     * A typed rule's value names a catalog id, so it has to be one. Caught here rather than at match
     * time, where an unparseable value would simply never match and look like a rule that does
     * nothing.
     */
    @AssertTrue(message = "matchValue must be a valid UUID")
    @Schema(hidden = true)
    public boolean isMatchValueAValidUuid() {
        if (matchValue == null || matchValue.isBlank()) {
            return true;
        }
        try {
            UUID.fromString(matchValue.trim());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
