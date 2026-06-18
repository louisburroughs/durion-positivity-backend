package com.positivity.customer.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@Schema(description = "Response containing potential duplicate party matches")
public class DuplicateCheckResponse {

    @Schema(
            description = "Whether any duplicate matches were found",
            example = "true",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private boolean duplicatesFound;

    @Schema(
            description = "ID of exact-match party, if any",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String exactMatchPartyId;

    @Valid
    @NotNull
    @Schema(
            description = "List of potential duplicate matches",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private List<PartyMatch> potentialDuplicates;

    @Data
    @Builder
    @Schema(description = "A single potential duplicate party match")
    public static class PartyMatch {

        @NotBlank
        @Schema(
                description = "Party ID",
                example = "01960003-0000-7000-8000-000000000001",
                requiredMode = Schema.RequiredMode.REQUIRED)
        private String partyId;

        @Schema(
                description = "Legal name of the party",
                example = "Acme Corporation",
                requiredMode = Schema.RequiredMode.REQUIRED)
        private String legalName;

        @Schema(
                description = "Match confidence score (0.0-1.0)",
                example = "0.95",
                requiredMode = Schema.RequiredMode.REQUIRED)
        private double score;

        @Schema(
                description = "Type of match",
                example = "EXACT",
                requiredMode = Schema.RequiredMode.REQUIRED,
                allowableValues = {"EXACT", "FUZZY"})
        private String matchType;
    }
}
