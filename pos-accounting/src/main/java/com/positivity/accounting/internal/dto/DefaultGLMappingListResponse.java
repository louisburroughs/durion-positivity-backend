package com.positivity.accounting.internal.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for paginated list of default GL mappings.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DefaultGLMappingListResponse {

    private List<DefaultGLMappingResponse> mappings;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
}
