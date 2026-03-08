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
        InvoiceStatusView view = statusViewRepository.findByInvoiceId(request.getInvoiceId())
                .orElseThrow(
                        () -> new EntityNotFoundException("Invoice status view not found: " + request.getInvoiceId()));

        if (request.getTransactionId() != null
                && request.getTransactionId().equals(view.getLatestTransactionReference())) {
            return PaymentOutcomeResponse.builder()
                    .invoiceStatus(view.getCurrentStatus())
                    .paidAmountMinor(view.getPaidAmountMinor())
                    .outstandingAmountMinor(view.getOutstandingAmountMinor())
                    .duplicate(true)
                    .postingError(view.isPostingError())
                    .build();
        }

        if (request.getTransactionId() == null
                && request.getIdempotencyKey() != null
                && request.getIdempotencyKey().equals(view.getLatestIdempotencyKey())) {
            return PaymentOutcomeResponse.builder()
                    .invoiceStatus(view.getCurrentStatus())
                    .paidAmountMinor(view.getPaidAmountMinor())
                    .outstandingAmountMinor(view.getOutstandingAmountMinor())
                    .duplicate(true)
                    .postingError(view.isPostingError())
                    .build();
        }

        PaymentOutcomeType outcomeType = request.getOutcomeType();
        if (outcomeType == PaymentOutcomeType.FULL_PAYMENT) {
            long currentPaid = view.getPaidAmountMinor() == null ? 0L : view.getPaidAmountMinor();
            view.setPaidAmountMinor(currentPaid + request.getAmountMinor());
            view.setOutstandingAmountMinor(0L);
            view.setCurrentStatus(PaymentStatus.PAID);
            view.setLatestTransactionReference(request.getTransactionId());
            view.setLatestIdempotencyKey(request.getIdempotencyKey());
            view.setLastUpdated(Instant.now(clock));
            statusViewRepository.save(view);

            InvoicePaymentRecorded event = new InvoicePaymentRecorded(
                    request.getInvoiceId(), request.getTransactionId(), request.getAmountMinor());
            eventPublisher.publishEvent(event);
            outboxService.saveToOutbox(
                    UUID.randomUUID(),
                    "Invoice",
                    request.getInvoiceId(),
                    "InvoicePaymentRecorded",
                    event);
        } else if (outcomeType == PaymentOutcomeType.PARTIAL_PAYMENT) {
            long currentPaid = view.getPaidAmountMinor() == null ? 0L : view.getPaidAmountMinor();
            long currentOutstanding = view.getOutstandingAmountMinor() == null ? 0L : view.getOutstandingAmountMinor();
            view.setPaidAmountMinor(currentPaid + request.getAmountMinor());
            view.setOutstandingAmountMinor(Math.max(0L, currentOutstanding - request.getAmountMinor()));
            view.setCurrentStatus(PaymentStatus.PARTIALLY_PAID);
            view.setLatestTransactionReference(request.getTransactionId());
            view.setLatestIdempotencyKey(request.getIdempotencyKey());
            view.setLastUpdated(Instant.now(clock));
            statusViewRepository.save(view);

            outboxService.saveToOutbox(
                    UUID.randomUUID(),
                    "Invoice",
                    request.getInvoiceId(),
                    "InvoicePaymentRecorded",
                    new InvoicePaymentRecorded(request.getInvoiceId(), request.getTransactionId(),
                            request.getAmountMinor()));
        } else if (outcomeType == PaymentOutcomeType.CHARGEBACK) {
            view.setCurrentStatus(PaymentStatus.CHARGEBACK);
            view.setLatestTransactionReference(request.getTransactionId());
            view.setLatestIdempotencyKey(request.getIdempotencyKey());
            view.setLastUpdated(Instant.now(clock));
            statusViewRepository.save(view);

            outboxService.saveToOutbox(
                    UUID.randomUUID(),
                    "Invoice",
                    request.getInvoiceId(),
                    "ChargebackReversal",
                    request);
        } else if (outcomeType == PaymentOutcomeType.OVERPAYMENT) {
            long totalAmountMinor = view.getTotalAmountMinor() == null ? 0L : view.getTotalAmountMinor();
            long excessMinor = request.getAmountMinor() - totalAmountMinor;
            view.setPaidAmountMinor(totalAmountMinor);
            view.setOutstandingAmountMinor(0L);
            view.setCurrentStatus(PaymentStatus.PAID);
            view.setLatestTransactionReference(request.getTransactionId());
            view.setLatestIdempotencyKey(request.getIdempotencyKey());
            view.setLastUpdated(Instant.now(clock));
            statusViewRepository.save(view);

            CustomerCredit credit = new CustomerCredit();
            credit.setCustomerId(request.getCustomerId());
            credit.setCurrency(request.getCurrency());
            credit.setAmount(BigDecimal.valueOf(excessMinor, 2));
            credit.setSourcePaymentId(request.getInvoiceId());
            customerCreditRepository.save(credit);

            outboxService.saveToOutbox(
                    UUID.randomUUID(),
                    "Invoice",
                    request.getInvoiceId(),
                    "InvoicePaymentRecorded",
                    new InvoicePaymentRecorded(request.getInvoiceId(), request.getTransactionId(),
                            request.getAmountMinor()));
        }

        return PaymentOutcomeResponse.builder()
                .invoiceStatus(view.getCurrentStatus())
                .paidAmountMinor(view.getPaidAmountMinor())
                .outstandingAmountMinor(view.getOutstandingAmountMinor())
                .duplicate(false)
                .postingError(view.isPostingError())
                .build();
    }

    @Override
    @Transactional
    public void handlePostingRetryExhausted(@NonNull UUID invoiceId, @NonNull String transactionId, int retryCount) {
        InvoiceStatusView view = statusViewRepository.findByInvoiceId(invoiceId)
                .orElseThrow(() -> new EntityNotFoundException("Invoice status view not found: " + invoiceId));

        view.setPostingError(true);
        statusViewRepository.save(view);

        ReconciliationRecord rec = new ReconciliationRecord();
        rec.setInvoiceId(invoiceId);
        rec.setTransactionId(transactionId);
        reconciliationRecordRepository.save(rec);

        eventPublisher.publishEvent(new InvoicePostingFailed(invoiceId, transactionId, retryCount));
    }
}
