package com.positivity.invoice.internal.service;

import com.positivity.invoice.internal.dto.InvoicingLagReport;
import com.positivity.invoice.internal.dto.InvoicingLagRow;
import com.positivity.invoice.internal.dto.RevenueByCustomerReport;
import com.positivity.invoice.internal.dto.RevenueByCustomerRow;
import com.positivity.invoice.internal.enums.InvoiceStatus;
import com.positivity.invoice.internal.repository.InvoiceRepository;
import com.positivity.invoice.internal.repository.InvoiceRepository.InvoicingLagPairProjection;
import com.positivity.invoice.internal.repository.InvoiceRepository.RevenueByCustomerProjection;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Invoice analytics (Wave 2 capability, issues #1589 and #1592).
 *
 * <p>No method-level transaction is declared for {@link #revenueByCustomer}: the customer-name
 * enrichment is a second local repository read (no remote call, unlike {@code
 * InvoiceSearchServiceImpl}'s workorder-number enrichment), so a single implicit
 * read-only transaction per repository call is sufficient and keeps each query short-lived.
 */
@Service
@RequiredArgsConstructor
public class InvoiceAnalyticsServiceImpl implements InvoiceAnalyticsService {

    /** DRAFT has not been billed; CANCELLED and ERROR never will be. */
    private static final Set<InvoiceStatus> REVENUE_STATUSES =
            EnumSet.of(InvoiceStatus.FINALIZED, InvoiceStatus.POSTED);

    private static final int MONEY_SCALE = 4;

    private final InvoiceRepository invoiceRepository;
    private final CustomerReferenceService customerReferenceService;

    @Override
    public @NonNull RevenueByCustomerReport revenueByCustomer(
            @NonNull LocalDate startDate, @NonNull LocalDate endDate, int limit) {
        validateRange(startDate, endDate);

        // Request one row more than the public limit so truncation is detectable without a
        // second COUNT query; the extra row, if present, is dropped below and never returned.
        List<RevenueByCustomerProjection> projections = invoiceRepository.revenueByCustomer(
                startOfDayUtc(startDate), endOfDayUtc(endDate), REVENUE_STATUSES, PageRequest.of(0, limit + 1));

        boolean truncated = projections.size() > limit;
        List<RevenueByCustomerProjection> page = truncated ? projections.subList(0, limit) : projections;

        Map<String, String> names = customerReferenceService.resolveNames(
                page.stream().map(RevenueByCustomerProjection::getCustomerId).toList());

        List<RevenueByCustomerRow> rows = page.stream()
                .map(p -> RevenueByCustomerRow.builder()
                        .customerId(p.getCustomerId())
                        .name(names.get(p.getCustomerId()))
                        .revenue(p.getRevenue())
                        .invoiceCount(p.getInvoiceCount())
                        .avgInvoiceValue(average(p.getRevenue(), p.getInvoiceCount()))
                        .lastInvoiceDate(p.getLastInvoiceDate())
                        .build())
                .toList();

        return RevenueByCustomerReport.builder()
                .startDate(startDate)
                .endDate(endDate)
                .limit(limit)
                .truncated(truncated)
                .rows(rows)
                .build();
    }

    @Override
    public @NonNull InvoicingLagReport invoicingLag(@NonNull LocalDate startDate, @NonNull LocalDate endDate) {
        validateRange(startDate, endDate);

        List<InvoicingLagPairProjection> pairs =
                invoiceRepository.invoicingLagPairs(startOfDayUtc(startDate), endOfDayUtc(endDate));

        long count = 0;
        double totalDays = 0.0;
        for (InvoicingLagPairProjection pair : pairs) {
            Instant workorderCreatedAt = pair.getWorkorderCreatedAt();
            // The single most important line in this method: a missing workorder-creation
            // timestamp is excluded from both the average and the count, never treated as zero
            // lag (#1592). This is the case whether the replica row is entirely missing (LEFT
            // JOIN miss) or present with a null workorderCreatedAt (fact not yet arrived, or
            // replicated before #1592).
            if (workorderCreatedAt == null) {
                continue;
            }
            totalDays += Duration.between(workorderCreatedAt, pair.getInvoiceCreatedAt())
                            .toMillis()
                    / 86_400_000.0;
            count++;
        }

        InvoicingLagRow row = InvoicingLagRow.builder()
                .avgDaysWoCreationToInvoice(count == 0 ? null : totalDays / count)
                .count(count)
                .build();

        return InvoicingLagReport.builder()
                .startDate(startDate)
                .endDate(endDate)
                .rows(List.of(row))
                .build();
    }

    private static @NonNull BigDecimal average(@NonNull BigDecimal revenue, long invoiceCount) {
        return revenue.divide(BigDecimal.valueOf(invoiceCount), MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static void validateRange(@NonNull LocalDate startDate, @NonNull LocalDate endDate) {
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("End date cannot be before start date");
        }
    }

    private static @NonNull Instant startOfDayUtc(@NonNull LocalDate date) {
        return date.atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    private static @NonNull Instant endOfDayUtc(@NonNull LocalDate date) {
        return date.atTime(LocalTime.MAX).toInstant(ZoneOffset.UTC);
    }
}
