package com.positivity.customer.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for searching parties.
 * Issue #173: Party: Search and Merge Duplicate Parties
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SearchPartiesResponse {

    /**
     * List of party summary records
     */
    private List<PartySummary> results;

    /**
     * Total number of matching records
     */
    private Integer totalCount;

    /**
     * Current page number
     */
    private Integer pageNumber;

    /**
     * Items per page
     */
    private Integer pageSize;

    /**
     * Party summary for search results
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PartySummary {
        private String partyId;
        private String legalName;
        private String displayName;
        private String partyType;
        private String status;
        private String createdAt;
    }
}
