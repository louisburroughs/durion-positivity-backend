package com.positivity.accounting.internal.bankfeed.file.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Paginated rows of an import, by row number (SPEC §4.4, §6.1; story S3, #2302). */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Paginated rows of an import, sorted by rowNumber")
public class BankImportRowListResponse {

    @Schema(description = "The file's column labels in file order", requiredMode = REQUIRED)
    private List<String> columns;

    @Schema(description = "Rows on the current page", requiredMode = REQUIRED)
    private List<BankImportRowResponse> rows;

    @Schema(description = "Total number of matching rows", example = "240", requiredMode = REQUIRED)
    private Long totalElements;

    @Schema(description = "Zero-based page index", example = "0", requiredMode = REQUIRED)
    private Integer pageNumber;

    @Schema(description = "Page size", example = "50", requiredMode = REQUIRED)
    private Integer pageSize;

    @Schema(description = "Total number of pages", example = "5", requiredMode = REQUIRED)
    private Integer totalPages;
}
