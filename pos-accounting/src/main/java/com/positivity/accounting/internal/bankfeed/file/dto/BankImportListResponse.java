package com.positivity.accounting.internal.bankfeed.file.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Paginated list of statement-file imports (SPEC §6.1; story S3, #2302). */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Paginated list of statement-file imports, newest first")
public class BankImportListResponse {

    @Schema(description = "Imports on the current page (without preview)", requiredMode = REQUIRED)
    private List<BankImportResponse> imports;

    @Schema(description = "Total number of matching imports", example = "3", requiredMode = REQUIRED)
    private Long totalElements;

    @Schema(description = "Zero-based page index", example = "0", requiredMode = REQUIRED)
    private Integer pageNumber;

    @Schema(description = "Page size", example = "20", requiredMode = REQUIRED)
    private Integer pageSize;

    @Schema(description = "Total number of pages", example = "1", requiredMode = REQUIRED)
    private Integer totalPages;
}
