package com.positivity.securityservice.internal.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class CrmMatchSummaryDto {
    int candidateCount;
    boolean anyMatches;
}
