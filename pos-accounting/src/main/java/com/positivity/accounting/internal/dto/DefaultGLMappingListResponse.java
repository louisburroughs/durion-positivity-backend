package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "Paginated list of default GL account mappings")
public class DefaultGLMappingListResponse {

    @Schema(description = "Default GL mappings on the current page", requiredMode = REQUIRED)
    private List<DefaultGLMappingResponse> mappings;

    @Schema(description = "Zero-based page index", example = "0", requiredMode = REQUIRED)
    private int page;

    @Schema(description = "Page size", example = "20", requiredMode = REQUIRED)
    private int size;

    @Schema(description = "Total number of matching mappings", example = "137", requiredMode = REQUIRED)
    private long totalElements;

    @Schema(description = "Total number of pages", example = "7", requiredMode = REQUIRED)
    private int totalPages;
}
