package com.positivity.invoice.internal.service;

import com.positivity.invoice.internal.entity.PaymentIntent;
import com.positivity.invoice.internal.entity.RefundRecord;
import com.positivity.invoice.internal.enums.PaymentIntentStatus;
import com.positivity.invoice.internal.enums.RefundReason;
import com.positivity.invoice.internal.enums.RefundStatus;
import com.positivity.invoice.internal.enums.VoidReason;
import com.positivity.invoice.internal.exception.InsufficientRefundableAmountException;
import com.positivity.invoice.internal.exception.InvalidPaymentStateException;
import com.positivity.invoice.internal.exception.PaymentGatewayException;
import com.positivity.invoice.internal.exception.PaymentIntentNotFoundException;
import com.positivity.invoice.internal.exception.PaymentWindowExpiredException;
import com.positivity.invoice.internal.payment.GatewayPaymentResult;
import com.positivity.invoice.internal.payment.GatewayRefundRequest;
import com.positivity.invoice.internal.payment.GatewayVoidRequest;
import com.positivity.invoice.internal.payment.PaymentGatewayPort;
import com.positivity.invoice.internal.repository.PaymentIntentRepository;
import com.positivity.invoice.internal.repository.RefundRecordRepository;
import com.positivity.invoice.service.PaymentReversalService;
import com.positivity.invoice.service.RefundPaymentResult;
import com.positivity.security.common.SecurityContextHelper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PaymentReversalServiceImpl implements PaymentReversalService {

    private static final String VOID_PAYMENT = "VOID_PAYMENT";
    private static final String REFUND_PAYMENT = "REFUND_PAYMENT";
    private static final String SUPERVISOR_OVERRIDE = "SUPERVISOR_OVERRIDE";
    private static final long VOID_WINDOW_HOURS = 24L;
    private static final long REFUND_WINDOW_DAYS = 180L;

    private final PaymentIntentRepository paymentIntentRepository;
    private final RefundRecordRepository refundRecordRepository;
    private final PaymentGatewayPort paymentGatewayPort;
    private final Clock clock;

    public PaymentReversalServiceImpl(
            @NonNull PaymentIntentRepository paymentIntentRepository,
            @NonNull RefundRecordRepository refundRecordRepository,
            @NonNull PaymentGatewayPort paymentGatewayPort,
            @NonNull Clock clock) {
        this.paymentIntentRepository = paymentIntentRepository;
        this.refundRecordRepository = refundRecordRepository;
        this.paymentGatewayPort = paymentGatewayPort;
        this.clock = clock;
    }

    private void requireAuthority(@NonNull String authority) {
        if (!SecurityContextHelper.hasAuthority(authority)) {
            throw new AccessDeniedException("Missing authority: " + authority);
        }
    }

    @Override
    public void voidPayment(
            @NonNull UUID invoiceId,
            @NonNull UUID paymentIntentId,
            @NonNull VoidReason reason,
            @Nullable String notes) {
        requireAuthority(VOID_PAYMENT);
        PaymentIntent paymentIntent = paymentIntentRepository
                .findById(paymentIntentId)
                .orElseThrow(() -> new PaymentIntentNotFoundException("PaymentIntent not found: " + paymentIntentId));

        if (paymentIntent.getInvoice() == null
                || !paymentIntent.getInvoice().getId().equals(invoiceId)) {
            throw new PaymentIntentNotFoundException("PaymentIntent not found for invoice: " + invoiceId);
        }

        if (paymentIntent.getStatus() != PaymentIntentStatus.AUTHORIZED) {
            throw new InvalidPaymentStateException(
                    "Payment must be AUTHORIZED to void, current: " + paymentIntent.getStatus());
        }

        Instant authorizedAt = paymentIntent.getCreatedAt();
        if (authorizedAt != null) {
            Instant windowCutoff = Instant.now(clock).minus(VOID_WINDOW_HOURS, ChronoUnit.HOURS);
            if (authorizedAt.isBefore(windowCutoff) && !SecurityContextHelper.hasAuthority(SUPERVISOR_OVERRIDE)) {
                throw new PaymentWindowExpiredException("Void window expired");
            }
        }

        GatewayVoidRequest voidRequest =
                new GatewayVoidRequest(paymentIntent.getGatewayReference(), paymentIntent.getAuthorizedAmount());
        GatewayPaymentResult voidResult = paymentGatewayPort.voidRemainder(voidRequest);
        if (!voidResult.isSuccessful()) {
            throw new PaymentGatewayException(
                    "Gateway void failed for reference: " + paymentIntent.getGatewayReference());
        }

        paymentIntent.setStatus(PaymentIntentStatus.VOIDED);
        paymentIntentRepository.save(paymentIntent);
    }

    @Override
    @NonNull
    public RefundPaymentResult refundPayment(
            @NonNull UUID invoiceId,
            @NonNull UUID paymentIntentId,
            @NonNull BigDecimal amount,
            @NonNull RefundReason reason,
            @Nullable String notes) {
        requireAuthority(REFUND_PAYMENT);
        PaymentIntent paymentIntent = paymentIntentRepository
                .findById(paymentIntentId)
                .orElseThrow(() -> new PaymentIntentNotFoundException("PaymentIntent not found: " + paymentIntentId));

        if (paymentIntent.getInvoice() == null
                || !paymentIntent.getInvoice().getId().equals(invoiceId)) {
            throw new PaymentIntentNotFoundException("PaymentIntent not found for invoice: " + invoiceId);
        }

        if (paymentIntent.getStatus() != PaymentIntentStatus.CAPTURED) {
            throw new InvalidPaymentStateException(
                    "Payment must be CAPTURED to refund, current: " + paymentIntent.getStatus());
        }

        List<RefundRecord> existingRefunds = refundRecordRepository.findByPaymentIntent_Id(paymentIntentId);

        Instant capturedAt = paymentIntent.getCreatedAt();
        if (capturedAt != null) {
            Instant windowCutoff = Instant.now(clock).minus(REFUND_WINDOW_DAYS, ChronoUnit.DAYS);
            if (capturedAt.isBefore(windowCutoff) && !SecurityContextHelper.hasAuthority(SUPERVISOR_OVERRIDE)) {
                throw new PaymentWindowExpiredException("Refund window of 180 days has expired");
            }
        }

        BigDecimal alreadyRefunded = existingRefunds.stream()
                .filter(refund -> refund.getStatus() != RefundStatus.FAILED)
                .map(RefundRecord::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal refundable = paymentIntent.getCapturedAmount().subtract(alreadyRefunded);
        if (amount.compareTo(refundable) > 0) {
            throw new InsufficientRefundableAmountException(
                    "Insufficient refundable amount: " + refundable + " < " + amount);
        }

        GatewayRefundRequest refundRequest = new GatewayRefundRequest(paymentIntent.getGatewayReference(), amount);
        GatewayPaymentResult result = paymentGatewayPort.refund(refundRequest);

        String requestedBy = SecurityContextHelper.getCurrentUsernameOrDefault("system");
        RefundRecord refundRecord = new RefundRecord();
        refundRecord.setPaymentIntent(paymentIntent);
        refundRecord.setInvoice(paymentIntent.getInvoice());
        refundRecord.setAmount(amount);
        refundRecord.setReason(reason);
        refundRecord.setNotes(notes);
        refundRecord.setRequestedBy(requestedBy);
        refundRecord.setRequestedAt(Instant.now(clock));
        refundRecord.setStatus(result.isSuccessful() ? RefundStatus.COMPLETED : RefundStatus.FAILED);
        if (result.isSuccessful()) {
            refundRecord.setGatewayReference(result.getGatewayReference());
            refundRecord.setCompletedAt(Instant.now(clock));
        }

        RefundRecord saved = refundRecordRepository.save(refundRecord);
        RefundPaymentResult resultDto = new RefundPaymentResult();
        resultDto.setRefundId(saved.getId());
        resultDto.setInvoiceId(
                saved.getInvoice() == null ? null : saved.getInvoice().getId());
        resultDto.setPaymentIntentId(
                saved.getPaymentIntent() == null
                        ? null
                        : saved.getPaymentIntent().getId());
        resultDto.setAmount(saved.getAmount());
        resultDto.setReason(saved.getReason());
        resultDto.setNotes(saved.getNotes());
        resultDto.setStatus(saved.getStatus());
        resultDto.setGatewayReference(saved.getGatewayReference());
        resultDto.setCompletedAt(saved.getCompletedAt());
        return resultDto;
    }
}
