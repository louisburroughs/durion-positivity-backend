package com.positivity.securityservice.internal.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class CrmMatchSummaryDto {
    int candidateCount;
    boolean anyMatches;
    int individualCustomerCandidateCount;
    int commercialContactCandidateCount;
    int sharedIdentityCandidateCount;
    boolean exactEmailMatch;
    boolean exactPhoneMatch;
    boolean exactNameMatch;
    boolean reviewRequired;
}
