package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.dto.AutomaticPaymentApplicationRow;
import com.positivity.accounting.internal.dto.AutomaticPaymentApplicationsPage;
import com.positivity.accounting.internal.dto.ResolvedDisplayReference;
import com.positivity.accounting.internal.entity.CustomerCredit;
import com.positivity.accounting.internal.entity.PaymentApplication;
import com.positivity.accounting.internal.entity.PaymentApplicationReversal;
import com.positivity.accounting.internal.enums.ApplicationSource;
import com.positivity.accounting.internal.enums.DisplayReferenceType;
import com.positivity.accounting.internal.repository.CustomerCreditRepository;
import com.positivity.accounting.internal.repository.PaymentApplicationRepository;
import com.positivity.accounting.internal.repository.PaymentApplicationReversalRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
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
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Set-based read of automatic payment applications (#2503): a page costs a fixed number of queries —
 * the page and its count, the reversals of the page, the credits its requests issued, and one per
 * display type — never one per row. Money is served at the ledger currency's scale.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AutomaticPaymentApplicationQueryServiceImpl implements AutomaticPaymentApplicationQueryService {

    private static final Sort NEWEST_FIRST =
            Sort.by(Sort.Order.desc("applicationTimestamp"), Sort.Order.desc("paymentApplicationId"));

    private final PaymentApplicationRepository paymentApplicationRepository;
    private final PaymentApplicationReversalRepository reversalRepository;
    private final CustomerCreditRepository customerCreditRepository;
    private final DisplayReferenceResolver displayReferenceResolver;
    private final LedgerCurrency ledgerCurrency;

    @Override
    @NonNull
    public AutomaticPaymentApplicationsPage listAutomatic(@NonNull Instant since, int page, int size, boolean canUndo) {
        Page<PaymentApplication> applications =
                paymentApplicationRepository.findByApplicationSourceInAndApplicationTimestampGreaterThanEqual(
                        ApplicationSource.AUTOMATIC, since, PageRequest.of(page, size, NEWEST_FIRST));
        List<PaymentApplication> rows = applications.getContent();

        Set<UUID> applicationIds = new LinkedHashSet<>();
        Set<UUID> invoiceIds = new LinkedHashSet<>();
        Set<UUID> customerIds = new LinkedHashSet<>();
        Set<String> creditRequestIds = new LinkedHashSet<>();
        for (PaymentApplication application : rows) {
            applicationIds.add(application.getPaymentApplicationId());
            invoiceIds.add(application.getInvoiceId());
            customerIds.add(application.getCustomerId());
            creditRequestIds.add(
                    PaymentApplicationServiceImpl.APPLY_REQUEST_ID_PREFIX + application.getApplicationRequestId());
        }
        Map<UUID, Instant> reversedAt = new HashMap<>();
        Map<String, BigDecimal> creditByRequest = new HashMap<>();
        if (!rows.isEmpty()) {
            for (PaymentApplicationReversal reversal :
                    reversalRepository.findByOriginalPaymentApplication_PaymentApplicationIdIn(applicationIds)) {
                reversedAt.merge(reversal.getOriginalPaymentApplicationId(), reversal.getReversedAt(), (a, b) -> a);
            }
            for (CustomerCredit credit : customerCreditRepository.findByRequestIdIn(creditRequestIds)) {
                creditByRequest.put(credit.getRequestId(), credit.getAmount());
            }
        }
        Map<UUID, ResolvedDisplayReference> invoices =
                displayReferenceResolver.resolve(DisplayReferenceType.INVOICE, invoiceIds);
        Map<UUID, ResolvedDisplayReference> customers =
                displayReferenceResolver.resolve(DisplayReferenceType.CUSTOMER, customerIds);

        List<AutomaticPaymentApplicationRow> items = new ArrayList<>(rows.size());
        for (PaymentApplication application : rows) {
            UUID applicationId = application.getPaymentApplicationId();
            boolean reversed = reversedAt.containsKey(applicationId);
            ResolvedDisplayReference customer =
                    customers.getOrDefault(application.getCustomerId(), ResolvedDisplayReference.EMPTY);
            BigDecimal credit = creditByRequest.get(
                    PaymentApplicationServiceImpl.APPLY_REQUEST_ID_PREFIX + application.getApplicationRequestId());
            items.add(AutomaticPaymentApplicationRow.builder()
                    .paymentApplicationId(applicationId)
                    .paymentId(application.getPaymentId())
                    .invoiceId(application.getInvoiceId())
                    .source(application.getApplicationSource())
                    .appliedAt(application.getApplicationTimestamp())
                    .appliedAmount(money(application.getAppliedAmount()))
                    .currency(application.getCurrency())
                    .invoiceNumber(invoices.getOrDefault(application.getInvoiceId(), ResolvedDisplayReference.EMPTY)
                            .displayReference())
                    .customerDisplayName(customer.displayName())
                    .customerReference(customer.displayReference())
                    .creditCreatedAmount(credit == null ? null : money(credit))
                    .reversed(reversed)
                    .reversedAt(reversedAt.get(applicationId))
                    .actions(!reversed && canUndo ? List.of(AutomaticPaymentApplicationRow.ACTION_UNDO) : List.of())
                    .build());
        }
        log.debug(
                "Listed automatic payment applications: page={}, size={}, rows={}, total={}",
                page,
                size,
                items.size(),
                applications.getTotalElements());
        return AutomaticPaymentApplicationsPage.builder()
                .items(items)
                .page(page)
                .size(size)
                .totalElements(applications.getTotalElements())
                .totalPages(applications.getTotalPages())
                .build();
    }

    private BigDecimal money(@Nullable BigDecimal amount) {
        return InvoiceBalanceCalculator.atCurrencyScale(
                amount == null ? BigDecimal.ZERO : amount, ledgerCurrency.code());
    }
}
