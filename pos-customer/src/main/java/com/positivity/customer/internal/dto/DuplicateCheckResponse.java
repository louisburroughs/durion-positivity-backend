package com.positivity.customer.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@Schema(description = "Response containing potential duplicate party matches")
public class DuplicateCheckResponse {

    @Schema(description = "Whether any duplicate matches were found", example = "true")
    private boolean duplicatesFound;

    @Schema(description = "ID of exact-match party, if any", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private String exactMatchPartyId;

    @Schema(description = "List of potential duplicate matches")
    private List<PartyMatch> potentialDuplicates;

    @Data
    @Builder
    @Schema(description = "A single potential duplicate party match")
    public static class PartyMatch {

        @Schema(description = "Party ID", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
        private String partyId;

        @Schema(description = "Legal name of the party", example = "Acme Corporation")
        private String legalName;

        @Schema(description = "Match confidence score (0.0-1.0)", example = "0.95")
        private double score;

        @Schema(
                description = "Type of match",
                example = "EXACT",
                allowableValues = {"EXACT", "FUZZY"})
        private String matchType;
    }
}
