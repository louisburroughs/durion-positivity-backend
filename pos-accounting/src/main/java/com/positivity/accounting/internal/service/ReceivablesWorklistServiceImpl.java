package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.dto.CustomerOpenInvoicesPage;
import com.positivity.accounting.internal.dto.OpenInvoiceRow;
import com.positivity.accounting.internal.dto.OpenInvoicesSummary;
import com.positivity.accounting.internal.dto.PaymentMatchSuggestion;
import com.positivity.accounting.internal.dto.ResolvedDisplayReference;
import com.positivity.accounting.internal.dto.UnappliedPaymentRow;
import com.positivity.accounting.internal.dto.UnappliedPaymentsPage;
import com.positivity.accounting.internal.dto.UnappliedPaymentsSummary;
import com.positivity.accounting.internal.entity.ExtInvoice;
import com.positivity.accounting.internal.entity.ReceivablePayment;
import com.positivity.accounting.internal.entity.ReceivablePayment.ReceivablePaymentStatus;
import com.positivity.accounting.internal.enums.DisplayReferenceType;
import com.positivity.accounting.internal.enums.SourceInvoiceState;
import com.positivity.accounting.internal.repository.AccountingEventOutcome;
import com.positivity.accounting.internal.repository.AccountingEventRepository;
import com.positivity.accounting.internal.repository.ExtInvoiceRepository;
import com.positivity.accounting.internal.repository.PaymentApplicationRepository;
import com.positivity.accounting.internal.repository.ReceivablePaymentRepository;
import com.positivity.accounting.internal.repository.ReceivablePaymentTotals;
import com.positivity.accounting.internal.service.UnappliedPaymentSuggester.OpenInvoice;
import com.positivity.domainevents.payment.PaymentSettledV1;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Set-based reads of the receivables worklist (#2502). A page costs a fixed number of queries: the
 * page and its totals, the customers' display values, the source invoices, the customers' candidate
 * invoices, the five grouped balance terms of {@link InvoiceBalanceCalculator#balancesDue}, the
 * applications of payments whose source invoice is paid, the automatic-application records and the
 * CASH walk-in parties — never one per row (item 7, #2563).
 *
 * <p>Each payment's source-invoice state is {@link SourceInvoiceRule}, the rule automatic application
 * decides cases b, c, d and g with (#2563): a payment is a possible duplicate only when its source invoice
 * is {@link SourceInvoiceState#PAID} and the payment applied nothing to it that is still in force.
 *
 * <p>An open invoice (BR-2) is an {@code ext_invoice} row whose party is the customer id in canonical
 * UUID form, in an AR-eligible lifecycle status ({@code FINALIZED}, {@code POSTED}), with a derived
 * balance above 0.00. Balances come only from {@link InvoiceBalanceCalculator} (BR-3), so the sum of
 * a customer's balances equals that customer's aged-receivables total today. Money is served at the
 * ledger currency's scale (P7).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReceivablesWorklistServiceImpl implements ReceivablesWorklistService {

    private static final Sort PAYMENT_ORDER = Sort.by(Sort.Order.asc("clearedAt"), Sort.Order.asc("paymentId"));

    /** Newest record first: by {@code receivedAt}, then by id (a UUID v7, so creation order). */
    private static final Comparator<AccountingEventOutcome> NEWEST_FIRST = Comparator.comparing(
                    AccountingEventOutcome::receivedAt)
            .thenComparing(AccountingEventOutcome::eventId)
            .reversed();

    private final ReceivablePaymentRepository receivablePaymentRepository;
    private final ExtInvoiceRepository extInvoiceRepository;
    private final PaymentApplicationRepository paymentApplicationRepository;
    private final AccountingEventRepository accountingEventRepository;
    private final InvoiceBalanceCalculator invoiceBalanceCalculator;
    private final DisplayReferenceResolver displayReferenceResolver;
    private final UnappliedPaymentSuggester suggester;
    private final LedgerCurrency ledgerCurrency;
    private final Clock clock;
    private final AccountingCalendarZoneResolver zoneResolver;

    @Override
    @NonNull
    public UnappliedPaymentsPage listUnappliedPayments(@Nullable UUID customerId, int page, int size) {
        Instant started = Instant.now(clock);
        Pageable pageable = PageRequest.of(page, size, PAYMENT_ORDER);
        Page<ReceivablePayment> payments;
        ReceivablePaymentTotals totals;
        if (customerId == null) {
            payments = receivablePaymentRepository.findByStatus(ReceivablePaymentStatus.AVAILABLE, pageable);
            totals = receivablePaymentRepository.totalsByStatus(ReceivablePaymentStatus.AVAILABLE);
        } else {
            payments = receivablePaymentRepository.findByStatusAndCustomerId(
                    ReceivablePaymentStatus.AVAILABLE, customerId, pageable);
            totals = receivablePaymentRepository.totalsByStatusAndCustomerId(
                    ReceivablePaymentStatus.AVAILABLE, customerId);
        }

        List<ReceivablePayment> rows = payments.getContent();
        Set<UUID> customerIds = new LinkedHashSet<>();
        Set<UUID> sourceInvoiceIds = new LinkedHashSet<>();
        for (ReceivablePayment payment : rows) {
            customerIds.add(payment.getCustomerId());
            if (payment.getSourceInvoiceId() != null) {
                sourceInvoiceIds.add(payment.getSourceInvoiceId());
            }
        }
        Map<UUID, ResolvedDisplayReference> customers =
                displayReferenceResolver.resolve(DisplayReferenceType.CUSTOMER, customerIds);
        // The invoices the payments were taken against, read once for their numbers and their state (#2563).
        Map<UUID, ExtInvoice> sourceInvoices = sourceInvoices(sourceInvoiceIds);
        List<ExtInvoice> candidates = candidateInvoices(customerIds);
        SourceInvoiceStates sourceStates = sourceInvoiceStates(rows, sourceInvoices, candidates);
        Map<UUID, List<OpenInvoice>> openByCustomer = openInvoicesByCustomer(candidates, sourceStates.balances());
        Set<UUID> paidByThemselves = paidByThemselves(sourceStates.states());
        Map<UUID, String> autoApplicationReasons = autoApplicationReasons(rows);
        // The CASH walk-in account never keeps a credit (#2508): its suggestions offer no leftOver. One query
        // per list call, and none for an empty page.
        Set<UUID> walkInPartyIds = rows.isEmpty() ? Set.of() : invoiceBalanceCalculator.walkInPartyIds();

        List<UnappliedPaymentRow> items = new ArrayList<>(rows.size());
        for (ReceivablePayment payment : rows) {
            ResolvedDisplayReference customer =
                    customers.getOrDefault(payment.getCustomerId(), ResolvedDisplayReference.EMPTY);
            BigDecimal unapplied = money(payment.getUnappliedAmount());
            PaymentMatchSuggestion suggestion = suggester.suggest(
                    payment.getSourceInvoiceId(),
                    unapplied,
                    openByCustomer.getOrDefault(payment.getCustomerId(), List.of()));
            if (walkInPartyIds.contains(payment.getCustomerId())) {
                suggestion.setLeftOver(null);
            }
            ExtInvoice sourceInvoice =
                    payment.getSourceInvoiceId() == null ? null : sourceInvoices.get(payment.getSourceInvoiceId());
            SourceInvoiceState sourceState = sourceStates.states().get(payment.getPaymentId());
            items.add(UnappliedPaymentRow.builder()
                    .paymentId(payment.getPaymentId())
                    .customerId(payment.getCustomerId())
                    .customerDisplayName(customer.displayName())
                    .customerReference(customer.displayReference())
                    .paymentMethod(payment.getPaymentMethod())
                    .receivedAt(payment.getClearedAt())
                    .currency(payment.getCurrency())
                    .totalAmount(money(payment.getTotalAmount()))
                    .unappliedAmount(unapplied)
                    .sourceInvoiceId(payment.getSourceInvoiceId())
                    .sourceInvoiceNumber(sourceInvoice == null ? null : sourceInvoice.getInvoiceNumber())
                    .sourceInvoiceState(sourceState)
                    .possibleDuplicate(sourceState == SourceInvoiceState.PAID
                            && !paidByThemselves.contains(payment.getPaymentId()))
                    .autoApplicationReason(autoApplicationReasons.get(payment.getPaymentId()))
                    .suggestion(suggestion)
                    .build());
        }

        UnappliedPaymentsSummary summary = UnappliedPaymentsSummary.builder()
                .count(totals.count())
                .totalUnappliedAmount(money(totals.totalUnapplied()))
                .currency(ledgerCurrency.code())
                .asOf(Instant.now(clock))
                .build();
        log.debug(
                "Listed unapplied payments: customerFilter={}, page={}, size={}, rows={}, total={}, took={}ms",
                customerId != null,
                page,
                size,
                items.size(),
                totals.count(),
                elapsedMillis(started));
        return UnappliedPaymentsPage.builder()
                .items(items)
                .page(page)
                .size(size)
                .totalElements(payments.getTotalElements())
                .totalPages(payments.getTotalPages())
                .summary(summary)
                .build();
    }

    @Override
    @NonNull
    public CustomerOpenInvoicesPage listOpenInvoices(@NonNull UUID customerId, int page, int size) {
        Instant started = Instant.now(clock);
        LocalDate today = zoneResolver.today();
        List<ExtInvoice> candidates = extInvoiceRepository.findByPartyIdInAndStatusIn(
                List.of(customerId.toString()), InvoiceBalanceCalculator.AR_ELIGIBLE_STATUSES);
        Map<UUID, BigDecimal> balances = invoiceBalanceCalculator.balancesDue(candidates);

        List<OpenInvoiceRow> open = candidates.stream()
                .filter(invoice -> InvoiceBalanceCalculator.isOpenReceivable(
                        invoice, balances.get(invoice.getInvoiceId()), ledgerCurrency.code()))
                .sorted(InvoiceBalanceCalculator.OLDEST_FIRST)
                .map(invoice -> openInvoiceRow(invoice, balances.get(invoice.getInvoiceId()), today))
                .toList();

        BigDecimal total = zero();
        BigDecimal overdueTotal = zero();
        long overdueCount = 0;
        for (OpenInvoiceRow row : open) {
            total = total.add(row.getBalanceDue());
            if (row.isOverdue()) {
                overdueCount++;
                overdueTotal = overdueTotal.add(row.getBalanceDue());
            }
        }
        OpenInvoicesSummary summary = OpenInvoicesSummary.builder()
                .count(open.size())
                .totalBalanceDue(total)
                .overdueCount(overdueCount)
                .overdueBalanceDue(overdueTotal)
                .currency(ledgerCurrency.code())
                .asOf(Instant.now(clock))
                .build();

        int from = (int) Math.min((long) page * size, open.size());
        int to = Math.min(from + size, open.size());
        log.debug(
                "Listed open invoices: page={}, size={}, open={}, took={}ms",
                page,
                size,
                open.size(),
                elapsedMillis(started));
        return CustomerOpenInvoicesPage.builder()
                .items(List.copyOf(open.subList(from, to)))
                .page(page)
                .size(size)
                .totalElements(open.size())
                .totalPages((open.size() + size - 1) / size)
                .summary(summary)
                .build();
    }

    /** The customers' AR-eligible invoices, from one query; none for an empty page. */
    private List<ExtInvoice> candidateInvoices(Set<UUID> customerIds) {
        if (customerIds.isEmpty()) {
            return List.of();
        }
        List<String> partyIds = customerIds.stream().map(UUID::toString).toList();
        return extInvoiceRepository.findByPartyIdInAndStatusIn(partyIds, InvoiceBalanceCalculator.AR_ELIGIBLE_STATUSES);
    }

    /** The replicated invoices among {@code invoiceIds}, by id, from one query; none when there are none. */
    private Map<UUID, ExtInvoice> sourceInvoices(Set<UUID> invoiceIds) {
        if (invoiceIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, ExtInvoice> byId = new HashMap<>();
        for (ExtInvoice invoice : extInvoiceRepository.findAllById(invoiceIds)) {
            byId.put(invoice.getInvoiceId(), invoice);
        }
        return byId;
    }

    /**
     * Each payment's source-invoice state by {@link SourceInvoiceRule}, the rule automatic application
     * decides cases b, c, d and g with (#2563); a payment that names no invoice is absent. One batch of
     * balance queries prices the customers' candidates and every source invoice whose balance decides, so
     * the suggestions and the states read the same balances.
     */
    private SourceInvoiceStates sourceInvoiceStates(
            List<ReceivablePayment> rows, Map<UUID, ExtInvoice> sourceInvoices, List<ExtInvoice> candidates) {
        Map<UUID, SourceInvoiceState> states = new HashMap<>();
        List<ExtInvoice> toPrice = new ArrayList<>(candidates);
        for (ReceivablePayment payment : rows) {
            if (payment.getSourceInvoiceId() == null) {
                continue;
            }
            ExtInvoice invoice = sourceInvoices.get(payment.getSourceInvoiceId());
            SourceInvoiceState standing = SourceInvoiceRule.standing(invoice, payment.getCustomerId());
            if (standing != null) {
                states.put(payment.getPaymentId(), standing);
            } else if (invoice != null) {
                toPrice.add(invoice);
            }
        }
        Map<UUID, BigDecimal> balances = invoiceBalanceCalculator.balancesDue(toPrice);

        for (ReceivablePayment payment : rows) {
            ExtInvoice invoice =
                    payment.getSourceInvoiceId() == null ? null : sourceInvoices.get(payment.getSourceInvoiceId());
            if (invoice != null && !states.containsKey(payment.getPaymentId())) {
                states.put(
                        payment.getPaymentId(),
                        SourceInvoiceRule.byBalance(
                                invoice,
                                balances.getOrDefault(invoice.getInvoiceId(), BigDecimal.ZERO),
                                ledgerCurrency.code()));
            }
        }
        return new SourceInvoiceStates(states, balances);
    }

    /**
     * Of the payments whose source invoice is {@link SourceInvoiceState#PAID}, those that paid it themselves,
     * in full or in part, with an application still in force (#2563): not possible duplicates. One query,
     * and none when no source invoice on the page is paid.
     */
    private Set<UUID> paidByThemselves(Map<UUID, SourceInvoiceState> states) {
        List<UUID> paid = states.entrySet().stream()
                .filter(entry -> entry.getValue() == SourceInvoiceState.PAID)
                .map(Map.Entry::getKey)
                .toList();
        if (paid.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(paymentApplicationRepository.findPaymentIdsWithLiveApplicationToSourceInvoice(paid));
    }

    /**
     * The reason on each payment's newest automatic-application record (#2503, #2563): its {@code
     * payment.payment.settled} record, keyed by the payment id, newest by {@code receivedAt} then id. A
     * record held for another reason (a currency hold) is not one; a newest record that was processed has
     * no reason. Payments without a reason are absent. One query, and none for an empty page.
     */
    private Map<UUID, String> autoApplicationReasons(List<ReceivablePayment> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        List<String> keys =
                rows.stream().map(payment -> payment.getPaymentId().toString()).toList();
        Map<String, AccountingEventOutcome> newest = new HashMap<>();
        for (AccountingEventOutcome outcome :
                accountingEventRepository.findOutcomesByEventTypeAndDomainKeyIdIn(PaymentSettledV1.EVENT_TYPE, keys)) {
            String reason = outcome.failureReasonCode();
            if (outcome.domainKeyId() == null
                    || (reason != null && !AutomaticPaymentApplicationService.OUTCOME_REASONS.contains(reason))) {
                continue;
            }
            newest.merge(
                    outcome.domainKeyId(), outcome, (kept, next) -> NEWEST_FIRST.compare(next, kept) < 0 ? next : kept);
        }
        Map<UUID, String> reasons = new HashMap<>();
        for (ReceivablePayment payment : rows) {
            AccountingEventOutcome outcome = newest.get(payment.getPaymentId().toString());
            if (outcome != null && outcome.failureReasonCode() != null) {
                reasons.put(payment.getPaymentId(), outcome.failureReasonCode());
            }
        }
        return reasons;
    }

    /**
     * The open invoices of each customer, oldest first, from the candidates and the balances one batch of
     * balance queries priced for them.
     */
    private Map<UUID, List<OpenInvoice>> openInvoicesByCustomer(
            List<ExtInvoice> candidates, Map<UUID, BigDecimal> balances) {
        Map<UUID, List<OpenInvoice>> byCustomer = new LinkedHashMap<>();
        candidates.stream()
                .filter(invoice -> InvoiceBalanceCalculator.isOpenReceivable(
                        invoice, balances.get(invoice.getInvoiceId()), ledgerCurrency.code()))
                .sorted(InvoiceBalanceCalculator.OLDEST_FIRST)
                .forEach(invoice -> byCustomer
                        .computeIfAbsent(UUID.fromString(invoice.getPartyId()), key -> new ArrayList<>())
                        .add(new OpenInvoice(
                                invoice.getInvoiceId(),
                                invoice.getInvoiceNumber(),
                                money(balances.get(invoice.getInvoiceId())))));
        return byCustomer;
    }

    private OpenInvoiceRow openInvoiceRow(ExtInvoice invoice, BigDecimal balance, LocalDate today) {
        LocalDate agingDate = InvoiceBalanceCalculator.receivableAgingDate(invoice);
        long daysOverdue = Math.max(0, ChronoUnit.DAYS.between(agingDate, today));
        return OpenInvoiceRow.builder()
                .invoiceId(invoice.getInvoiceId())
                .invoiceNumber(invoice.getInvoiceNumber())
                .workorderId(invoice.getWorkorderId())
                .documentDate(InvoiceBalanceCalculator.receivableDocumentDate(invoice))
                .dueDate(invoice.getDueDate())
                .total(money(invoice.getTotal()))
                .balanceDue(money(balance))
                .arStatus(invoiceBalanceCalculator
                        .deriveArStatus(invoice, balance)
                        .name())
                .overdue(daysOverdue > 0)
                .daysOverdue(daysOverdue)
                .currency(ledgerCurrency.code())
                .build();
    }

    private BigDecimal money(@Nullable BigDecimal amount) {
        return InvoiceBalanceCalculator.atCurrencyScale(
                amount == null ? BigDecimal.ZERO : amount, ledgerCurrency.code());
    }

    private BigDecimal zero() {
        return money(BigDecimal.ZERO);
    }

    private long elapsedMillis(Instant started) {
        return Duration.between(started, Instant.now(clock)).toMillis();
    }

    /**
     * Each payment's source-invoice state by payment id, and the balances priced to decide them and the
     * suggestions.
     */
    private record SourceInvoiceStates(Map<UUID, SourceInvoiceState> states, Map<UUID, BigDecimal> balances) {}
}
