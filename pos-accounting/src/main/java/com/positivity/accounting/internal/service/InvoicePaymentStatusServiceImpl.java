package com.positivity.accounting.internal.service;

import java.time.Clock;

import com.positivity.accounting.internal.dto.InvoiceStatusResponse;
import com.positivity.accounting.internal.entity.InvoiceStatusView;
import com.positivity.accounting.internal.entity.PaymentAppliedEvent;
import com.positivity.accounting.internal.dto.PaymentAppliedRequest;
import com.positivity.accounting.internal.enums.PaymentStatus;
import com.positivity.accounting.internal.repository.InvoiceStatusViewRepository;
import com.positivity.accounting.internal.repository.PaymentAppliedEventRepository;
import com.positivity.accounting.service.IdempotencyService;
import com.positivity.accounting.service.InvoicePaymentStatusService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Service for managing invoice payment status updates.
 * Implements business logic for mapping payment outcomes to invoice statuses.
 */
@Service
public class InvoicePaymentStatusServiceImpl implements InvoicePaymentStatusService {
    private final Clock clock;

    private static final Logger log = LoggerFactory.getLogger(InvoicePaymentStatusServiceImpl.class);

    private final PaymentAppliedEventRepository paymentEventRepository;
    private final InvoiceStatusViewRepository statusViewRepository;
    private final IdempotencyService idempotencyService;

    public InvoicePaymentStatusServiceImpl(
            PaymentAppliedEventRepository paymentEventRepository,
            InvoiceStatusViewRepository statusViewRepository,
            IdempotencyService idempotencyService,
            Clock clock) {
        this.clock = clock;
        this.paymentEventRepository = paymentEventRepository;
        this.statusViewRepository = statusViewRepository;
        this.idempotencyService = idempotencyService;
    }

    /**
     * Process a payment applied event and update invoice status.
     * Implements retry logic for transient failures only (DataAccessException,
     * OptimisticLockingFailureException). Domain exceptions such as
     * EntityNotFoundException are not retried.
     */
    @Override
    @Transactional
    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2), retryFor = {
            DataAccessException.class, OptimisticLockingFailureException.class })
    public InvoiceStatusResponse processPaymentApplied(PaymentAppliedRequest request) {
        log.info("Processing payment for invoice {} with idempotency key {}",
                request.getInvoiceId(), request.getIdempotencyKey());

        // Check idempotency
        if (idempotencyService.isKeyProcessed(request.getIdempotencyKey())) {
            log.info("Payment already processed - returning existing status");
            return buildInvoiceStatusResponse(request.getInvoiceId());
        }

        // Determine payment status
        PaymentStatus paymentStatus = determinePaymentStatus(
                request.getPaymentAmount(),
                request.getInvoiceTotal(),
                request.isPaymentFailed());

        // Create and save payment event
        PaymentAppliedEvent event = new PaymentAppliedEvent(
                request.getInvoiceId(),
                request.getTransactionReference(),
                request.getPaymentAmount(),
                request.getInvoiceTotal(),
                paymentStatus,
                request.getIdempotencyKey());
        paymentEventRepository.save(event);
        log.info("Saved payment event for invoice {} with status {}",
                request.getInvoiceId(), paymentStatus);

        // Update invoice status view
        updateInvoiceStatusView(request.getInvoiceId(), request.getTransactionReference());

        // Register idempotency key
        idempotencyService.registerKey(request.getIdempotencyKey(), request.getInvoiceId());

        return buildInvoiceStatusResponse(request.getInvoiceId());
    }

    /**
     * Get current status of an invoice.
     */
    @Override
    @Transactional(readOnly = true)
    public InvoiceStatusResponse getInvoiceStatus(UUID invoiceId) {
        return buildInvoiceStatusResponse(invoiceId);
    }

    /**
     * Build an {@link InvoiceStatusResponse} from the persisted status view.
     * Extracted so internal callers avoid a self-invocation that would bypass
     * the {@code @Transactional} proxy on {@link #getInvoiceStatus(UUID)}.
     */
    private InvoiceStatusResponse buildInvoiceStatusResponse(UUID invoiceId) {
        if (log.isInfoEnabled()) {
            log.info("Querying status for invoice {}", maskInvoiceId(invoiceId));
        }
        InvoiceStatusView statusView = statusViewRepository.findByInvoiceId(invoiceId)
                .orElseThrow(() -> new EntityNotFoundException("Invoice not found: " + invoiceId));

        return new InvoiceStatusResponse(
                statusView.getInvoiceId(),
                statusView.getCurrentStatus(),
                statusView.getTotalPaid(),
                statusView.getInvoiceTotal(),
                statusView.getLatestTransactionReference(),
                statusView.getLastUpdated());
    }

    private String maskInvoiceId(UUID invoiceId) {
        if (invoiceId == null) {
            return "null";
        }

        String value = invoiceId.toString();
        return value.substring(0, 8) + "...";
    }

    /**
     * Determine payment status based on amounts.
     */
    private PaymentStatus determinePaymentStatus(
            BigDecimal paymentAmount,
            BigDecimal invoiceTotal,
            boolean paymentFailed) {

        if (paymentFailed) {
            return PaymentStatus.FAILED;
        }

        int comparison = paymentAmount.compareTo(invoiceTotal);

        if (comparison >= 0) {
            return PaymentStatus.PAID;
        } else if (paymentAmount.compareTo(BigDecimal.ZERO) > 0) {
            return PaymentStatus.PARTIALLY_PAID;
        } else {
            return PaymentStatus.UNPAID;
        }
    }

    /**
     * Update the invoice status view based on all payment events.
     */
    private void updateInvoiceStatusView(UUID invoiceId, String latestTransactionRef) {
        // Calculate total paid (excluding failed payments)
        BigDecimal totalPaid = paymentEventRepository.calculateTotalPaid(invoiceId);

        // Get invoice total from latest event
        PaymentAppliedEvent latestEvent = paymentEventRepository
                .findByInvoiceIdOrderByTimestampDesc(invoiceId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new RuntimeException("No payment events found for invoice: " + invoiceId));

        BigDecimal invoiceTotal = latestEvent.getInvoiceTotal();

        // Determine overall status
        PaymentStatus overallStatus = determineOverallStatus(totalPaid, invoiceTotal);

        // Update or create status view
        InvoiceStatusView statusView = statusViewRepository.findByInvoiceId(invoiceId)
                .orElse(new InvoiceStatusView(invoiceId, overallStatus, totalPaid, invoiceTotal));

        statusView.setCurrentStatus(overallStatus);
        statusView.setTotalPaid(totalPaid);
        statusView.setInvoiceTotal(invoiceTotal);
        statusView.setLatestTransactionReference(latestTransactionRef);
        statusView.setLastUpdated(Instant.now(clock));

        statusViewRepository.saveAndFlush(statusView);
        log.info("Updated invoice status view for {} - status: {}, totalPaid: {}, total: {}",
                invoiceId, overallStatus, totalPaid, invoiceTotal);
    }

    /**
     * Determine overall payment status based on cumulative payments.
     */
    private PaymentStatus determineOverallStatus(BigDecimal totalPaid, BigDecimal invoiceTotal) {
        if (totalPaid.compareTo(invoiceTotal) >= 0) {
            return PaymentStatus.PAID;
        } else if (totalPaid.compareTo(BigDecimal.ZERO) > 0) {
            return PaymentStatus.PARTIALLY_PAID;
        } else {
            return PaymentStatus.UNPAID;
        }
    }
}
