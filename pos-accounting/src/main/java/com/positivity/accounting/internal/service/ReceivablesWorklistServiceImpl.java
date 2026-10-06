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
import com.positivity.accounting.internal.repository.ExtInvoiceRepository;
import com.positivity.accounting.internal.repository.ReceivablePaymentRepository;
import com.positivity.accounting.internal.repository.ReceivablePaymentTotals;
import com.positivity.accounting.internal.service.UnappliedPaymentSuggester.OpenInvoice;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
 * page and its totals, one per display type, the customers' candidate invoices, and the five grouped
 * balance terms of {@link InvoiceBalanceCalculator#balancesDue} — never one per row (item 7).
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

    private final ReceivablePaymentRepository receivablePaymentRepository;
    private final ExtInvoiceRepository extInvoiceRepository;
    private final InvoiceBalanceCalculator invoiceBalanceCalculator;
    private final DisplayReferenceResolver displayReferenceResolver;
    private final UnappliedPaymentSuggester suggester;
    private final LedgerCurrency ledgerCurrency;
    private final Clock clock;

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
        Map<UUID, ResolvedDisplayReference> sourceInvoices =
                displayReferenceResolver.resolve(DisplayReferenceType.INVOICE, sourceInvoiceIds);
        Map<UUID, List<OpenInvoice>> openByCustomer = openInvoicesByCustomer(customerIds);
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
                    .sourceInvoiceNumber(
                            payment.getSourceInvoiceId() == null
                                    ? null
                                    : sourceInvoices
                                            .getOrDefault(payment.getSourceInvoiceId(), ResolvedDisplayReference.EMPTY)
                                            .displayReference())
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
        LocalDate today = LocalDate.now(clock);
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

    /**
     * The open invoices of each customer, oldest first, from one candidate query and one batch of
     * balance queries for all of them.
     */
    private Map<UUID, List<OpenInvoice>> openInvoicesByCustomer(Set<UUID> customerIds) {
        if (customerIds.isEmpty()) {
            return Map.of();
        }
        List<String> partyIds = customerIds.stream().map(UUID::toString).toList();
        List<ExtInvoice> candidates = extInvoiceRepository.findByPartyIdInAndStatusIn(
                partyIds, InvoiceBalanceCalculator.AR_ELIGIBLE_STATUSES);
        Map<UUID, BigDecimal> balances = invoiceBalanceCalculator.balancesDue(candidates);

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
}
