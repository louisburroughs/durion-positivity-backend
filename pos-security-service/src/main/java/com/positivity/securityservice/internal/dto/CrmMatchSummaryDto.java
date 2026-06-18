package com.positivity.securityservice.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
@Schema(description = "Summary of CRM person-search candidates evaluated during registration")
public class CrmMatchSummaryDto {
    @Schema(description = "Total number of candidate persons returned", example = "3", requiredMode = REQUIRED)
    int candidateCount;

    @Schema(description = "True when any candidate matched", example = "true", requiredMode = REQUIRED)
    boolean anyMatches;

    @Schema(description = "Number of individual-customer candidates", example = "2", requiredMode = REQUIRED)
    int individualCustomerCandidateCount;

    @Schema(description = "Number of commercial-contact candidates", example = "1", requiredMode = REQUIRED)
    int commercialContactCandidateCount;

    @Schema(description = "Number of shared-identity candidates", example = "0", requiredMode = REQUIRED)
    int sharedIdentityCandidateCount;

    @Schema(description = "True when a candidate exactly matched the email", example = "true", requiredMode = REQUIRED)
    boolean exactEmailMatch;

    @Schema(description = "True when a candidate exactly matched the phone", example = "false", requiredMode = REQUIRED)
    boolean exactPhoneMatch;

    @Schema(description = "True when a candidate exactly matched the name", example = "false", requiredMode = REQUIRED)
    boolean exactNameMatch;

    @Schema(description = "True when manual review is required before completing registration", example = "false", requiredMode = REQUIRED)
    boolean reviewRequired;
}
