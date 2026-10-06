package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A page of a customer's open invoices, oldest first, with totals over all of them (#2502). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A page of the customer's open invoices, oldest first, with a served summary")
public class CustomerOpenInvoicesPage {

    @ArraySchema(arraySchema = @Schema(description = "Open invoices on this page", requiredMode = REQUIRED))
    private List<OpenInvoiceRow> items;

    @Schema(description = "Page index (0-based)", example = "0", requiredMode = REQUIRED)
    private int page;

    @Schema(description = "Page size", example = "100", requiredMode = REQUIRED)
    private int size;

    @Schema(description = "Number of open invoices", example = "2", requiredMode = REQUIRED)
    private long totalElements;

    @Schema(description = "Number of pages", example = "1", requiredMode = REQUIRED)
    private int totalPages;

    @Schema(description = "Totals over every open invoice of the customer", requiredMode = REQUIRED)
    private OpenInvoicesSummary summary;
}
