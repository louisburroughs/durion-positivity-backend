package com.positivity.accounting.internal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response DTO for Posting Rule Set.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PostingRuleSetResponse {

    private UUID postingRuleSetId;
    private String name;
    private String eventType;
    private String description;
    private List<PostingRuleVersionResponse> versions;
    private Instant createdAt;
    private String createdBy;
    private Instant modifiedAt;
    private String modifiedBy;
}
