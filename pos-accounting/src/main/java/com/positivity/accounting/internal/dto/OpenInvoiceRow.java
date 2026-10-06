package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One open invoice of a customer, with what is still owed after applications, credits, credit memos
 * and deposits (#2502, BR-2, BR-3).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "An open invoice of the customer with its derived balance due")
public class OpenInvoiceRow {

    @Schema(
            description = "Invoice identifier",
            example = "0199a000-0000-7000-8000-000000001702",
            requiredMode = REQUIRED)
    private UUID invoiceId;

    @Schema(
            description = "Invoice number; null when the replica holds none",
            example = "INV-2026-01702",
            requiredMode = NOT_REQUIRED,
            nullable = true)
    private String invoiceNumber;

    @Schema(
            description = "Originating workorder, for a link only; null for invoices with no workorder",
            example = "0199a000-0000-7000-8000-000000000777",
            requiredMode = NOT_REQUIRED,
            nullable = true)
    private UUID workorderId;

    @Schema(description = "Invoice document date", example = "2026-09-04", requiredMode = REQUIRED)
    private LocalDate documentDate;

    @Schema(
            description = "Due date frozen at finalization; null when the replica holds none",
            example = "2026-10-04",
            requiredMode = NOT_REQUIRED,
            nullable = true)
    private LocalDate dueDate;

    @Schema(description = "Invoice total", example = "4615.00", requiredMode = REQUIRED)
    private BigDecimal total;

    @Schema(
            description = "What is still owed after applications, credits, credit memos and deposits",
            example = "4615.00",
            requiredMode = REQUIRED)
    private BigDecimal balanceDue;

    @Schema(
            description = "Accounting's receivable state: OPEN (nothing settled yet) or PARTIALLY_PAID",
            example = "OPEN",
            requiredMode = REQUIRED)
    private String arStatus;

    @Schema(
            description = "True when the aging date (due date, else document date) is before today",
            example = "false",
            requiredMode = REQUIRED)
    private boolean overdue;

    @Schema(description = "Days past the aging date; 0 when not due", example = "0", requiredMode = REQUIRED)
    private long daysOverdue;

    @Schema(description = "Currency (ISO 4217): the ledger currency", example = "USD", requiredMode = REQUIRED)
    private String currency;
}
