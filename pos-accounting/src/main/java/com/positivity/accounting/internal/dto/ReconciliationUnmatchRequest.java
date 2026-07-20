package com.positivity.accounting.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request to reverse a match (Story F2, issue #965). Supply either {@link #matchId}
 * (unmatch the whole match set) or {@link #statementLineIds} (unmatch by the lines'
 * match group); at least one must be present.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to reverse a match by matchId or by statement line ids")
public class ReconciliationUnmatchRequest {

    @Schema(description = "Match group id to reverse")
    private UUID matchId;

    @Schema(description = "Statement line ids whose match group should be reversed")
    private List<UUID> statementLineIds;
}
