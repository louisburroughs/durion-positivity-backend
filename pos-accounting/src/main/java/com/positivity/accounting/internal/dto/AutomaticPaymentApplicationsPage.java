package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A page of automatic payment applications, newest first (#2503). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A page of payment applications made automatically since a given instant, newest first")
public class AutomaticPaymentApplicationsPage {

    @ArraySchema(arraySchema = @Schema(description = "Applications on this page", requiredMode = REQUIRED))
    private List<AutomaticPaymentApplicationRow> items;

    @Schema(description = "Page index (0-based)", example = "0", requiredMode = REQUIRED)
    private int page;

    @Schema(description = "Page size", example = "50", requiredMode = REQUIRED)
    private int size;

    @Schema(
            description = "Number of automatic applications since the given instant, reversed ones included",
            example = "7",
            requiredMode = REQUIRED)
    private long totalElements;

    @Schema(description = "Number of pages", example = "1", requiredMode = REQUIRED)
    private int totalPages;
}
