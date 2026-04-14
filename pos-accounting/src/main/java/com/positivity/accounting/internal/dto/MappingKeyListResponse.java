package com.positivity.accounting.internal.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for paginated list of Mapping Keys.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MappingKeyListResponse {

    private List<MappingKeyResponse> results;
    private Long totalCount;
    private Integer pageNumber;
    private Integer pageSize;
    private Integer totalPages;
}
