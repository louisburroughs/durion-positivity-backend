package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.PaymentOutcomeRequest;
import com.positivity.accounting.internal.dto.PaymentOutcomeResponse;
import com.positivity.accounting.internal.entity.CustomerCredit;
import com.positivity.accounting.internal.entity.InvoiceStatusView;
import com.positivity.accounting.internal.entity.ReconciliationRecord;
import com.positivity.accounting.internal.enums.PaymentOutcomeType;
import com.positivity.accounting.internal.enums.PaymentStatus;
import com.positivity.accounting.internal.event.InvoicePaymentRecorded;
import com.positivity.accounting.internal.event.InvoicePostingFailed;
import com.positivity.accounting.internal.repository.CustomerCreditRepository;
import com.positivity.accounting.internal.repository.InvoiceStatusViewRepository;
import com.positivity.accounting.internal.repository.ReconciliationRecordRepository;
import com.positivity.accounting.service.OutboxService;
import com.positivity.accounting.service.PaymentOutcomeProcessingService;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Processes invoice payment outcomes by dispatching behavior per outcome type,
 * enforcing idempotency guards, and persisting asynchronous posting intents
 * through
 * the outbox. Also handles posting retry exhaustion by flagging invoice posting
 * failure state, recording reconciliation data, and emitting escalation events.
 */
@Service
public class PaymentOutcomeProcessingServiceImpl implements PaymentOutcomeProcessingService {

    private static final String INVOICE_PAYMENT_RECORDED = "InvoicePaymentRecorded";
    private static final String CHARGEBACK_REVERSAL = "ChargebackReversal";
    private static final String INVOICE = "Invoice";
    private final InvoiceStatusViewRepository statusViewRepository;
    private final CustomerCreditRepository customerCreditRepository;
    private final ReconciliationRecordRepository reconciliationRecordRepository;
    private final OutboxService outboxService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public PaymentOutcomeProcessingServiceImpl(
            InvoiceStatusViewRepository statusViewRepository,
            CustomerCreditRepository customerCreditRepository,
            ReconciliationRecordRepository reconciliationRecordRepository,
            OutboxService outboxService,
            ApplicationEventPublisher eventPublisher,
            Clock clock) {
        this.statusViewRepository = statusViewRepository;
        this.customerCreditRepository = customerCreditRepository;
        this.reconciliationRecordRepository = reconciliationRecordRepository;
        this.outboxService = outboxService;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Override
    @Transactional
    public PaymentOutcomeResponse processPaymentOutcome(@NonNull PaymentOutcomeRequest request) {
        InvoiceStatusView view = loadInvoiceStatusView(request.getInvoiceId());
        if (isDuplicateRequest(request, view)) {
            return buildPaymentOutcomeResponse(view, true);
        }

        applyOutcome(request, view);
        return buildPaymentOutcomeResponse(view, false);
    }

    @Override
    @Transactional
    public void handlePostingRetryExhausted(@NonNull UUID invoiceId, @NonNull String transactionId, int retryCount) {
        InvoiceStatusView view = loadInvoiceStatusView(invoiceId);

        view.setPostingError(true);
        statusViewRepository.save(view);

        ReconciliationRecord rec = new ReconciliationRecord();
        rec.setInvoiceId(invoiceId);
        rec.setTransactionId(transactionId);
        reconciliationRecordRepository.save(rec);

        eventPublisher.publishEvent(new InvoicePostingFailed(invoiceId, transactionId, retryCount));
    }

    private @NonNull InvoiceStatusView loadInvoiceStatusView(@NonNull UUID invoiceId) {
        return statusViewRepository
                .findByInvoiceId(invoiceId)
                .orElseThrow(() -> new EntityNotFoundException("Invoice status view not found: " + invoiceId));
    }

    private boolean isDuplicateRequest(@NonNull PaymentOutcomeRequest request, @NonNull InvoiceStatusView view) {
        return isDuplicateTransactionId(request, view) || isDuplicateIdempotencyKey(request, view);
    }

    private boolean isDuplicateTransactionId(@NonNull PaymentOutcomeRequest request, @NonNull InvoiceStatusView view) {
        return request.getTransactionId() != null
                && request.getTransactionId().equals(view.getLatestTransactionReference());
    }

    private boolean isDuplicateIdempotencyKey(@NonNull PaymentOutcomeRequest request, @NonNull InvoiceStatusView view) {
        return request.getTransactionId() == null
                && request.getIdempotencyKey() != null
                && request.getIdempotencyKey().equals(view.getLatestIdempotencyKey());
    }

    private void applyOutcome(@NonNull PaymentOutcomeRequest request, @NonNull InvoiceStatusView view) {
        PaymentOutcomeType outcomeType = request.getOutcomeType();
        if (outcomeType == null) {
            return;
        }

        switch (outcomeType) {
            case FULL_PAYMENT -> handleFullPayment(request, view);
            case PARTIAL_PAYMENT -> handlePartialPayment(request, view);
            case CHARGEBACK -> handleChargeback(request, view);
            case OVERPAYMENT -> handleOverpayment(request, view);
        }
    }

    private void handleFullPayment(@NonNull PaymentOutcomeRequest request, @NonNull InvoiceStatusView view) {
        long updatedPaidMinor = getOrZero(view.getPaidAmountMinor()) + request.getAmountMinor();
        view.setPaidAmountMinor(updatedPaidMinor);
        view.setOutstandingAmountMinor(0L);
        view.setCurrentStatus(PaymentStatus.PAID);
        persistView(view, request);

        InvoicePaymentRecorded event = createInvoicePaymentRecorded(request);
        eventPublisher.publishEvent(event);
        saveToOutbox(request.getInvoiceId(), INVOICE_PAYMENT_RECORDED, event);
    }

    private void handlePartialPayment(@NonNull PaymentOutcomeRequest request, @NonNull InvoiceStatusView view) {
        long updatedPaidMinor = getOrZero(view.getPaidAmountMinor()) + request.getAmountMinor();
        long updatedOutstandingMinor =
                Math.max(0L, getOrZero(view.getOutstandingAmountMinor()) - request.getAmountMinor());

        view.setPaidAmountMinor(updatedPaidMinor);
        view.setOutstandingAmountMinor(updatedOutstandingMinor);
        view.setCurrentStatus(PaymentStatus.PARTIALLY_PAID);
        persistView(view, request);

        saveToOutbox(request.getInvoiceId(), INVOICE_PAYMENT_RECORDED, createInvoicePaymentRecorded(request));
    }

    private void handleChargeback(@NonNull PaymentOutcomeRequest request, @NonNull InvoiceStatusView view) {
        view.setCurrentStatus(PaymentStatus.CHARGEBACK);
        persistView(view, request);

        saveToOutbox(request.getInvoiceId(), CHARGEBACK_REVERSAL, request);
    }

    private void handleOverpayment(@NonNull PaymentOutcomeRequest request, @NonNull InvoiceStatusView view) {
        long totalAmountMinor = getOrZero(view.getTotalAmountMinor());
        long excessMinor = request.getAmountMinor() - totalAmountMinor;

        view.setPaidAmountMinor(totalAmountMinor);
        view.setOutstandingAmountMinor(0L);
        view.setCurrentStatus(PaymentStatus.PAID);
        persistView(view, request);

        createCustomerCredit(request, excessMinor);
        saveToOutbox(request.getInvoiceId(), INVOICE_PAYMENT_RECORDED, createInvoicePaymentRecorded(request));
    }

    private void persistView(@NonNull InvoiceStatusView view, @NonNull PaymentOutcomeRequest request) {
        view.setLatestTransactionReference(request.getTransactionId());
        view.setLatestIdempotencyKey(request.getIdempotencyKey());
        view.setLastUpdated(Instant.now(clock));
        statusViewRepository.save(view);
    }

    private void createCustomerCredit(@NonNull PaymentOutcomeRequest request, long excessMinor) {
        CustomerCredit credit = new CustomerCredit();
        credit.setCustomerId(request.getCustomerId());
        credit.setCurrency(request.getCurrency());
        credit.setAmount(BigDecimal.valueOf(excessMinor, 2));
        credit.setSourcePaymentId(request.getInvoiceId());
        customerCreditRepository.save(credit);
    }

    private void saveToOutbox(@NonNull UUID invoiceId, @NonNull String eventType, @NonNull Object payload) {
        outboxService.saveToOutbox(UUID.randomUUID(), INVOICE, invoiceId, eventType, payload);
    }

    private @NonNull InvoicePaymentRecorded createInvoicePaymentRecorded(@NonNull PaymentOutcomeRequest request) {
        return new InvoicePaymentRecorded(request.getInvoiceId(), request.getTransactionId(), request.getAmountMinor());
    }

    private long getOrZero(Long value) {
        return value == null ? 0L : value;
    }

    private @NonNull PaymentOutcomeResponse buildPaymentOutcomeResponse(
            @NonNull InvoiceStatusView view, boolean duplicate) {
        return PaymentOutcomeResponse.builder()
                .invoiceStatus(view.getCurrentStatus())
                .paidAmountMinor(view.getPaidAmountMinor())
                .outstandingAmountMinor(view.getOutstandingAmountMinor())
                .duplicate(duplicate)
                .postingError(view.isPostingError())
                .build();
    }
}
