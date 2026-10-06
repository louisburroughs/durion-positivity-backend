package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A page of unapplied customer payments, oldest first, with totals over the whole filter (#2502). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A page of customer payments waiting to be matched, oldest first, with a served summary")
public class UnappliedPaymentsPage {

    @ArraySchema(arraySchema = @Schema(description = "Payments on this page", requiredMode = REQUIRED))
    private List<UnappliedPaymentRow> items;

    @Schema(description = "Page index (0-based)", example = "0", requiredMode = REQUIRED)
    private int page;

    @Schema(description = "Page size", example = "25", requiredMode = REQUIRED)
    private int size;

    @Schema(description = "Number of payments matching the filter", example = "3", requiredMode = REQUIRED)
    private long totalElements;

    @Schema(description = "Number of pages", example = "1", requiredMode = REQUIRED)
    private int totalPages;

    @Schema(description = "Totals over every payment matching the filter", requiredMode = REQUIRED)
    private UnappliedPaymentsSummary summary;
}
