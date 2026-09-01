package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.CollectionsAnalyticsReport;
import com.positivity.accounting.internal.dto.PaymentLagCohortRow;
import com.positivity.accounting.internal.dto.PaymentLagCohortsReport;
import com.positivity.accounting.internal.entity.ExtInvoice;
import com.positivity.accounting.internal.entity.PaymentApplication;
import com.positivity.accounting.internal.repository.ExtInvoiceRepository;
import com.positivity.accounting.internal.repository.PaymentApplicationRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only implementation of the Wave 2 accounting analytics (Issue #1590 E2, Issue #1591 E3).
 *
 * @see AccountingAnalyticsService
 */
@Service
@Transactional(readOnly = true)
public class AccountingAnalyticsServiceImpl implements AccountingAnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(AccountingAnalyticsServiceImpl.class);

    private static final String COHORT_LE_30 = "<=30";
    private static final String COHORT_31_60 = "31-60";
    private static final String COHORT_61_90 = "61-90";
    private static final String COHORT_UNPAID = "unpaid";

    /** Fixed cohort order returned by {@link #getPaymentLagCohorts}. */
    private static final List<String> COHORT_ORDER = List.of(COHORT_LE_30, COHORT_31_60, COHORT_61_90, COHORT_UNPAID);

    private static final int DEFAULT_COHORT_LIMIT = COHORT_ORDER.size();
    private static final int MAX_COHORT_LIMIT = COHORT_ORDER.size();
    private static final int RATE_SCALE = 2;
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private final Clock clock;
    private final ExtInvoiceRepository extInvoiceRepository;
    private final PaymentApplicationRepository paymentApplicationRepository;

    public AccountingAnalyticsServiceImpl(
            Clock clock,
            ExtInvoiceRepository extInvoiceRepository,
            PaymentApplicationRepository paymentApplicationRepository) {
        this.clock = clock;
        this.extInvoiceRepository = extInvoiceRepository;
        this.paymentApplicationRepository = paymentApplicationRepository;
    }

    @Override
    public @NonNull CollectionsAnalyticsReport getCollectionsAnalytics(
            @NonNull LocalDate startDate, @NonNull LocalDate endDate) {

        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("End date cannot be before start date");
        }

        log.info("Generating collections analytics for window {} to {}", startDate, endDate);

        Instant startInstant = toStartInstant(startDate);
        Instant endInstant = toEndInstant(endDate);

        BigDecimal invoiced = extInvoiceRepository.findByFinalizedAtBetween(startInstant, endInstant).stream()
                .map(ExtInvoice::getTotal)
                .map(AccountingAnalyticsServiceImpl::nullSafe)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal collected =
                paymentApplicationRepository.findByApplicationTimestampBetween(startInstant, endInstant).stream()
                        .map(PaymentApplication::getAppliedAmount)
                        .map(AccountingAnalyticsServiceImpl::nullSafe)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal collectionRatePct = invoiced.compareTo(BigDecimal.ZERO) == 0
                ? null
                : collected.multiply(ONE_HUNDRED).divide(invoiced, RATE_SCALE, RoundingMode.HALF_UP);

        return CollectionsAnalyticsReport.builder()
                .startDate(startDate)
                .endDate(endDate)
                .generatedAt(Instant.now(clock))
                .invoiced(invoiced)
                .collected(collected)
                .collectionRatePct(collectionRatePct)
                .build();
    }

    @Override
    public @NonNull PaymentLagCohortsReport getPaymentLagCohorts(
            @NonNull LocalDate issuedFrom, @NonNull LocalDate issuedTo, int limit) {

        if (issuedTo.isBefore(issuedFrom)) {
            throw new IllegalArgumentException("issuedTo cannot be before issuedFrom");
        }

        log.info("Generating payment-lag cohorts for invoices issued {} to {}", issuedFrom, issuedTo);

        Instant startInstant = toStartInstant(issuedFrom);
        Instant endInstant = toEndInstant(issuedTo);

        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, BigDecimal> amounts = new LinkedHashMap<>();
        for (String cohort : COHORT_ORDER) {
            counts.put(cohort, 0);
            amounts.put(cohort, BigDecimal.ZERO);
        }

        List<ExtInvoice> invoices = extInvoiceRepository.findByFinalizedAtBetween(startInstant, endInstant);
        List<UUID> invoiceIds =
                invoices.stream().map(ExtInvoice::getInvoiceId).distinct().toList();
        Map<UUID, List<PaymentApplication>> applicationsByInvoice = invoiceIds.isEmpty()
                ? Map.of()
                : paymentApplicationRepository.findByInvoiceIdIn(invoiceIds).stream()
                        .collect(Collectors.groupingBy(PaymentApplication::getInvoiceId));

        for (ExtInvoice invoice : invoices) {
            String cohort = classify(invoice, applicationsByInvoice.getOrDefault(invoice.getInvoiceId(), List.of()));
            BigDecimal amount = nullSafe(invoice.getTotal());
            counts.merge(cohort, 1, Integer::sum);
            amounts.merge(cohort, amount, BigDecimal::add);
        }

        int capped = limit > 0 ? Math.min(limit, MAX_COHORT_LIMIT) : DEFAULT_COHORT_LIMIT;
        List<PaymentLagCohortRow> rows = new ArrayList<>();
        for (String cohort : COHORT_ORDER) {
            if (rows.size() >= capped) {
                break;
            }
            rows.add(PaymentLagCohortRow.builder()
                    .cohort(cohort)
                    .invoiceCount(counts.get(cohort))
                    .amount(amounts.get(cohort))
                    .build());
        }

        return PaymentLagCohortsReport.builder()
                .issuedFrom(issuedFrom)
                .issuedTo(issuedTo)
                .generatedAt(Instant.now(clock))
                .truncated(capped < DEFAULT_COHORT_LIMIT)
                .cohorts(rows)
                .build();
    }

    /**
     * Classifies one invoice into its payment-lag cohort per the boundary and unpaid/partial rules
     * documented on {@link AccountingAnalyticsService#getPaymentLagCohorts}.
     */
    private static String classify(ExtInvoice invoice, List<PaymentApplication> applications) {
        Instant issueAnchor = invoice.getFinalizedAt();
        if (issueAnchor == null) {
            // Defensive: findByFinalizedAtBetween never returns a null-finalizedAt row, but a
            // still-unissued invoice cannot be aged, so treat it as unpaid rather than throwing.
            return COHORT_UNPAID;
        }

        Instant paidAt = firstZeroBalanceTimestamp(applications);
        if (paidAt == null) {
            return COHORT_UNPAID;
        }

        long lagDays = Math.max(0, ChronoUnit.DAYS.between(issueAnchor, paidAt));
        if (lagDays <= 30) {
            return COHORT_LE_30;
        } else if (lagDays <= 60) {
            return COHORT_31_60;
        } else if (lagDays <= 90) {
            return COHORT_61_90;
        }
        return COHORT_UNPAID;
    }

    /**
     * Returns the application timestamp of the earliest {@link PaymentApplication} (chronological,
     * tiebroken by id) at which this invoice's {@code invoiceBalanceAfter} first reached zero, or
     * {@code null} when no application ever brought the balance to zero (no applications at all, or
     * a partially-applied invoice per the unpaid-until-fully-paid rule).
     */
    private static @Nullable Instant firstZeroBalanceTimestamp(List<PaymentApplication> applications) {
        return applications.stream()
                .filter(app -> app.getInvoiceBalanceAfter() != null
                        && app.getInvoiceBalanceAfter().compareTo(BigDecimal.ZERO) == 0)
                .min(Comparator.comparing(PaymentApplication::getApplicationTimestamp)
                        .thenComparing(PaymentApplication::getPaymentApplicationId))
                .map(PaymentApplication::getApplicationTimestamp)
                .orElse(null);
    }

    private static Instant toStartInstant(LocalDate date) {
        LocalDateTime startOfDay = date.atStartOfDay();
        return startOfDay.toInstant(ZoneOffset.UTC);
    }

    private static Instant toEndInstant(LocalDate date) {
        LocalDateTime endOfDay = date.atTime(LocalTime.MAX);
        return endOfDay.toInstant(ZoneOffset.UTC);
    }

    private static BigDecimal nullSafe(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
