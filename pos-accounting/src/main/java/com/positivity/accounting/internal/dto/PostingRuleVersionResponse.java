package com.positivity.accounting.internal.dto;

import com.positivity.accounting.internal.enums.PostingRuleSetState;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for Posting Rule Version.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PostingRuleVersionResponse {

    private UUID versionId;
    private UUID postingRuleSetId;
    private Integer versionNumber;
    private PostingRuleSetState state;
    private String rulesDefinition;
    private Instant createdAt;
    private String createdBy;
    private Instant modifiedAt;
    private String modifiedBy;
}
