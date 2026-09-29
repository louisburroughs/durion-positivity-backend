package com.positivity.accounting.internal.bankrec.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Paginated list of bank statements (SPEC §6.1; story S2, #2301). */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Paginated list of bank statements, sorted by startDate")
public class BankStatementListResponse {

    @Schema(description = "Statements on the current page", requiredMode = REQUIRED)
    private List<BankStatementResponse> statements;

    @Schema(description = "Total number of matching statements", example = "12", requiredMode = REQUIRED)
    private Long totalElements;

    @Schema(description = "Zero-based page index", example = "0", requiredMode = REQUIRED)
    private Integer pageNumber;

    @Schema(description = "Page size", example = "20", requiredMode = REQUIRED)
    private Integer pageSize;

    @Schema(description = "Total number of pages", example = "1", requiredMode = REQUIRED)
    private Integer totalPages;
}
