package com.positivity.invoice.internal.service;

import com.positivity.invoice.internal.dto.InvoicingLagReport;
import com.positivity.invoice.internal.dto.RevenueByCustomerReport;
import java.time.LocalDate;
import org.jspecify.annotations.NonNull;

/**
 * Invoice analytics (Wave 2 capability, issues #1589 and #1592): revenue-by-customer and
 * workorder-creation-to-invoice lag reports.
 */
public interface InvoiceAnalyticsService {

    /**
     * Per-customer revenue over {@code [startDate, endDate]}, ordered by revenue descending and
     * bounded to {@code limit} rows.
     *
     * @param startDate window start date (inclusive)
     * @param endDate   window end date (inclusive)
     * @param limit     maximum rows to return (already clamped by the controller)
     * @return the report, with {@code truncated} set when more customers had revenue than
     *     {@code limit} allowed through
     */
    @NonNull
    RevenueByCustomerReport revenueByCustomer(@NonNull LocalDate startDate, @NonNull LocalDate endDate, int limit);

    /**
     * Average days from workorder creation to invoice creation for invoices created in
     * {@code [startDate, endDate]}. Invoices with no linked workorder, or whose workorder
     * replica has no known creation timestamp, are excluded from both the average and the
     * count — never treated as zero lag.
     *
     * @param startDate window start date (inclusive)
     * @param endDate   window end date (inclusive)
     * @return the report; its single row's average is {@code null} when no invoice qualifies
     */
    @NonNull
    InvoicingLagReport invoicingLag(@NonNull LocalDate startDate, @NonNull LocalDate endDate);
}
