package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.CollectionsAnalyticsReport;
import com.positivity.accounting.internal.dto.PaymentLagCohortRow;
import com.positivity.accounting.internal.dto.PaymentLagCohortsReport;
import com.positivity.accounting.internal.dto.VendorSpendReport;
import com.positivity.accounting.internal.dto.VendorSpendRow;
import com.positivity.accounting.internal.entity.APPayment;
import com.positivity.accounting.internal.entity.ExtInvoice;
import com.positivity.accounting.internal.entity.PaymentApplication;
import com.positivity.accounting.internal.entity.Vendor;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.enums.APPaymentStatus;
import com.positivity.accounting.internal.enums.CustomerCreditTransactionType;
import com.positivity.accounting.internal.repository.APPaymentRepository;
import com.positivity.accounting.internal.repository.CustomerCreditTransactionRepository;
import com.positivity.accounting.internal.repository.ExtInvoiceDepositCreditApplicationRepository;
import com.positivity.accounting.internal.repository.ExtInvoicePaymentReversalRepository;
import com.positivity.accounting.internal.repository.ExtInvoiceRepository;
import com.positivity.accounting.internal.repository.PaymentApplicationRepository;
import com.positivity.accounting.internal.repository.PaymentApplicationReversalRepository;
import com.positivity.accounting.internal.repository.ReceivablePaymentRepository;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import com.positivity.accounting.internal.repository.VendorRepository;
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
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /** Default row cap for {@link #getVendorSpend} when the caller omits {@code limit}. */
    private static final int DEFAULT_VENDOR_SPEND_LIMIT = 20;

    /** Hard cap so a caller cannot request an unbounded per-vendor fan-out. */
    private static final int MAX_VENDOR_SPEND_LIMIT = 100;

    /**
     * A/P payment statuses at and after which the gateway has confirmed the cash moved
     * ({@code GATEWAY_SUCCEEDED} — "allocations applied" per {@link APPaymentStatus}), so a
     * subsequent GL-posting failure ({@code GL_POST_FAILED}) does not un-count it as settled
     * cash.
     */
    private static final Set<APPaymentStatus> SETTLED_AP_PAYMENT_STATUSES = EnumSet.of(
            APPaymentStatus.GATEWAY_SUCCEEDED,
            APPaymentStatus.GL_POST_PENDING,
            APPaymentStatus.GL_POSTED,
            APPaymentStatus.GL_POST_FAILED);

    private static final int AVG_BILL_AMOUNT_SCALE = 2;

    private final Clock clock;
    private final ExtInvoiceRepository extInvoiceRepository;
    private final PaymentApplicationRepository paymentApplicationRepository;
    private final PaymentApplicationReversalRepository paymentApplicationReversalRepository;
    private final ExtInvoicePaymentReversalRepository extInvoicePaymentReversalRepository;
    private final ExtInvoiceDepositCreditApplicationRepository extInvoiceDepositCreditApplicationRepository;
    private final ReceivablePaymentRepository receivablePaymentRepository;
    private final CustomerCreditTransactionRepository customerCreditTransactionRepository;
    private final APPaymentRepository apPaymentRepository;
    private final VendorBillRepository vendorBillRepository;
    private final VendorRepository vendorRepository;

    public AccountingAnalyticsServiceImpl(
            Clock clock,
            ExtInvoiceRepository extInvoiceRepository,
            PaymentApplicationRepository paymentApplicationRepository,
            PaymentApplicationReversalRepository paymentApplicationReversalRepository,
            ExtInvoicePaymentReversalRepository extInvoicePaymentReversalRepository,
            ExtInvoiceDepositCreditApplicationRepository extInvoiceDepositCreditApplicationRepository,
            ReceivablePaymentRepository receivablePaymentRepository,
            CustomerCreditTransactionRepository customerCreditTransactionRepository,
            APPaymentRepository apPaymentRepository,
            VendorBillRepository vendorBillRepository,
            VendorRepository vendorRepository) {
        this.clock = clock;
        this.extInvoiceRepository = extInvoiceRepository;
        this.paymentApplicationRepository = paymentApplicationRepository;
        this.paymentApplicationReversalRepository = paymentApplicationReversalRepository;
        this.extInvoicePaymentReversalRepository = extInvoicePaymentReversalRepository;
        this.extInvoiceDepositCreditApplicationRepository = extInvoiceDepositCreditApplicationRepository;
        this.receivablePaymentRepository = receivablePaymentRepository;
        this.customerCreditTransactionRepository = customerCreditTransactionRepository;
        this.apPaymentRepository = apPaymentRepository;
        this.vendorBillRepository = vendorBillRepository;
        this.vendorRepository = vendorRepository;
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

        // Deposit-take invoices excluded (#1623, Accounting ruling): the down-payment document is
        // a contract liability, not a sale, and the settlement invoice is later raised gross for
        // the full workorder total — counting both would report a $500 deposit on a $2,000 job as
        // $2,500 invoiced across the two windows. Exclusion at the deposit-take document (never
        // netting the settlement) keeps the settlement's total traceable to the workorder price.
        BigDecimal invoiced = extInvoiceRepository.findByFinalizedAtBetween(startInstant, endInstant).stream()
                .filter(invoice -> invoice.getDepositSourceType() == null)
                .map(ExtInvoice::getTotal)
                .map(AccountingAnalyticsServiceImpl::nullSafe)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal applied =
                paymentApplicationRepository.findByApplicationTimestampBetween(startInstant, endInstant).stream()
                        .map(PaymentApplication::getAppliedAmount)
                        .map(AccountingAnalyticsServiceImpl::nullSafe)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Movement basis (issue #1605): a reversal reduces the window it was RECORDED in, not the
        // window its original application landed in. A January application reversed in March keeps
        // January whole and reduces March, so a closed period is never restated (consistent with
        // the PERIOD_CLOSED/PERIOD_HARD_LOCKED gate and ADR-0047 correction-by-reversal), and the
        // measure stays additive across sub-windows: Jan + Feb + Mar == Jan-Mar.
        // This is deliberately a different basis from the payment-application list endpoint (E10),
        // which excludes currently-reversed applications because it answers a point-in-time
        // question rather than measuring movement in a window.
        BigDecimal applicationReversals =
                nullSafe(paymentApplicationReversalRepository.sumAmountByReversedAtBetween(startInstant, endInstant));

        // Not clamped at zero: a window whose reversals exceed its applications is genuinely
        // net-negative cash application, and hiding that would misstate the period.
        BigDecimal collected = applied.subtract(applicationReversals);

        BigDecimal collectionRatePct = invoiced.compareTo(BigDecimal.ZERO) == 0
                ? null
                : collected.multiply(ONE_HUNDRED).divide(invoiced, RATE_SCALE, RoundingMode.HALF_UP);

        // Gross completed refunds attributed to the window their reversal was recorded in (#1620).
        // VOID reversals (an authorization released before capture) never produced collected cash,
        // so they are correctly absent: the replica behind this query never stores them at all
        // (see ExtInvoicePaymentReversalRepository#sumAmountByReversedAtBetween).
        // Both refund shapes together (ADR-0057 §4, the same both-feeds rule as nonCashSettled):
        // pos-invoice RefundRecord facts (replica, by reversedAt) plus accounting's own credit-balance
        // refunds (CustomerCreditTransaction REFUND rows, by createdAt) — one source alone reads
        // complete while systematically short. The two are disjoint subledgers, so no double-count.
        BigDecimal refunded = nullSafe(
                        extInvoicePaymentReversalRepository.sumAmountByReversedAtBetween(startInstant, endInstant))
                .add(nullSafe(customerCreditTransactionRepository.sumAmountByTypeAndCreatedAtBetween(
                        CustomerCreditTransactionType.REFUND, startInstant, endInstant)));

        // Mixed-basis subtraction (collected is movement-basis A/R relief, refunded is cash out) —
        // deliberately not the clean cash pair; see the field's own @Schema doc. Not clamped.
        BigDecimal netCashCollected = collected.subtract(refunded);

        // Cash actually taken in, independent of application (#1622).
        BigDecimal received =
                nullSafe(receivablePaymentRepository.sumTotalAmountByClearedAtBetween(startInstant, endInstant));

        // Settlement without new cash: pos-invoice deposit-credit draw-downs plus this module's own
        // customer-credit APPLICATION draw-downs, both attributed to the draw-down moment (#1621).
        BigDecimal nonCashSettled = nullSafe(
                        extInvoiceDepositCreditApplicationRepository.sumAmountAppliedByAppliedAtBetween(
                                startInstant, endInstant))
                .add(nullSafe(customerCreditTransactionRepository.sumAmountByTypeAndCreatedAtBetween(
                        CustomerCreditTransactionType.APPLICATION, startInstant, endInstant)));

        BigDecimal settled = collected.add(nonCashSettled);

        BigDecimal settlementRatePct = invoiced.compareTo(BigDecimal.ZERO) == 0
                ? null
                : settled.multiply(ONE_HUNDRED).divide(invoiced, RATE_SCALE, RoundingMode.HALF_UP);

        return CollectionsAnalyticsReport.builder()
                .startDate(startDate)
                .endDate(endDate)
                .generatedAt(Instant.now(clock))
                .invoiced(invoiced)
                .collected(collected)
                .applicationReversals(applicationReversals)
                .collectionRatePct(collectionRatePct)
                .refunded(refunded)
                .netCashCollected(netCashCollected)
                .received(received)
                .nonCashSettled(nonCashSettled)
                .settled(settled)
                .settlementRatePct(settlementRatePct)
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

    @Override
    public @NonNull VendorSpendReport getVendorSpend(
            @NonNull LocalDate startDate, @NonNull LocalDate endDate, int limit) {

        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("End date cannot be before start date");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        int effectiveLimit = Math.min(limit, MAX_VENDOR_SPEND_LIMIT);

        log.info("Generating vendor spend analytics for window {} to {}", startDate, endDate);

        LocalDateTime startOfDay = startDate.atStartOfDay();
        LocalDateTime endOfDay = endDate.atTime(LocalTime.MAX);

        List<APPayment> settledPayments = apPaymentRepository.findByStatusInAndPaymentDateBetween(
                SETTLED_AP_PAYMENT_STATUSES, startOfDay, endOfDay);
        List<VendorBill> billsInWindow = vendorBillRepository.findByBillDateBetween(startOfDay, endOfDay);

        Map<UUID, BigDecimal> paidByVendor = new LinkedHashMap<>();
        Map<UUID, String> fallbackNameByVendor = new LinkedHashMap<>();
        for (APPayment payment : settledPayments) {
            paidByVendor.merge(payment.getVendorId(), nullSafe(payment.getGrossAmount()), BigDecimal::add);
            fallbackNameByVendor.putIfAbsent(payment.getVendorId(), payment.getVendorName());
        }

        Map<UUID, Integer> billCountByVendor = new LinkedHashMap<>();
        Map<UUID, BigDecimal> billTotalByVendor = new LinkedHashMap<>();
        for (VendorBill bill : billsInWindow) {
            billCountByVendor.merge(bill.getVendorId(), 1, Integer::sum);
            billTotalByVendor.merge(bill.getVendorId(), nullSafe(bill.getTotalAmount()), BigDecimal::add);
            fallbackNameByVendor.putIfAbsent(bill.getVendorId(), bill.getVendorName());
        }

        Set<UUID> vendorIds = new LinkedHashSet<>();
        vendorIds.addAll(paidByVendor.keySet());
        vendorIds.addAll(billCountByVendor.keySet());

        Map<UUID, String> directoryNameByVendor = vendorIds.isEmpty()
                ? Map.of()
                : vendorRepository.findAllById(vendorIds).stream()
                        .collect(Collectors.toMap(Vendor::getVendorId, Vendor::getName));

        List<VendorSpendRow> allRows = new ArrayList<>();
        for (UUID vendorId : vendorIds) {
            BigDecimal paidAmount = paidByVendor.getOrDefault(vendorId, BigDecimal.ZERO);
            int billCount = billCountByVendor.getOrDefault(vendorId, 0);
            BigDecimal billTotal = billTotalByVendor.getOrDefault(vendorId, BigDecimal.ZERO);
            BigDecimal avgBillAmount = billCount == 0
                    ? BigDecimal.ZERO
                    : billTotal.divide(BigDecimal.valueOf(billCount), AVG_BILL_AMOUNT_SCALE, RoundingMode.HALF_UP);
            String name = directoryNameByVendor.getOrDefault(vendorId, fallbackNameByVendor.get(vendorId));

            allRows.add(VendorSpendRow.builder()
                    .vendorId(vendorId)
                    .name(name)
                    .paidAmount(paidAmount)
                    .billsIssuedInWindow(billCount)
                    .avgIssuedBillAmount(avgBillAmount)
                    .build());
        }

        allRows.sort(Comparator.comparing(VendorSpendRow::getPaidAmount).reversed());

        boolean truncated = allRows.size() > effectiveLimit;
        List<VendorSpendRow> rows = truncated ? new ArrayList<>(allRows.subList(0, effectiveLimit)) : allRows;

        return VendorSpendReport.builder()
                .startDate(startDate)
                .endDate(endDate)
                .generatedAt(Instant.now(clock))
                .limit(effectiveLimit)
                .truncated(truncated)
                .rows(rows)
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
