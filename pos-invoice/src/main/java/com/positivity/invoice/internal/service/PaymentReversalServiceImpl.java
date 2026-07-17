package com.positivity.invoice.internal.service;

import com.positivity.invoice.internal.entity.Invoice;
import com.positivity.invoice.internal.entity.PaymentIntent;
import com.positivity.invoice.internal.entity.RefundRecord;
import com.positivity.invoice.internal.enums.PaymentIntentStatus;
import com.positivity.invoice.internal.enums.RefundReason;
import com.positivity.invoice.internal.enums.RefundStatus;
import com.positivity.invoice.internal.enums.VoidReason;
import com.positivity.invoice.internal.exception.InsufficientRefundableAmountException;
import com.positivity.invoice.internal.exception.InvalidPaymentStateException;
import com.positivity.invoice.internal.exception.InvoiceNotFoundException;
import com.positivity.invoice.internal.exception.PaymentGatewayException;
import com.positivity.invoice.internal.exception.PaymentIntentNotFoundException;
import com.positivity.invoice.internal.exception.PaymentWindowExpiredException;
import com.positivity.invoice.internal.payment.GatewayPaymentResult;
import com.positivity.invoice.internal.payment.GatewayRefundRequest;
import com.positivity.invoice.internal.payment.GatewayVoidRequest;
import com.positivity.invoice.internal.payment.PaymentGatewayPort;
import com.positivity.invoice.internal.repository.InvoiceRepository;
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
    private static final String ISSUE_MANUAL_REFUND = "ISSUE_MANUAL_REFUND";
    private static final String SUPERVISOR_OVERRIDE = "SUPERVISOR_OVERRIDE";
    private static final long VOID_WINDOW_HOURS = 24L;
    private static final long REFUND_WINDOW_DAYS = 180L;

    private final PaymentIntentRepository paymentIntentRepository;
    private final RefundRecordRepository refundRecordRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentGatewayPort paymentGatewayPort;
    private final Clock clock;

    public PaymentReversalServiceImpl(
            @NonNull PaymentIntentRepository paymentIntentRepository,
            @NonNull RefundRecordRepository refundRecordRepository,
            @NonNull InvoiceRepository invoiceRepository,
            @NonNull PaymentGatewayPort paymentGatewayPort,
            @NonNull Clock clock) {
        this.paymentIntentRepository = paymentIntentRepository;
        this.refundRecordRepository = refundRecordRepository;
        this.invoiceRepository = invoiceRepository;
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
            @Nullable String notes,
            @Nullable String externalReference) {
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

        // Idempotent replay guard: a caller retrying after a transport timeout (its refund may
        // have committed here already) supplies the same externalReference — return the
        // existing non-FAILED refund instead of paying the customer twice (warranty settlements
        // pass their settlement id).
        String normalizedReference = normalizeExternalReference(externalReference);
        if (normalizedReference != null) {
            for (RefundRecord existing : existingRefunds) {
                if (existing.getStatus() != RefundStatus.FAILED
                        && normalizedReference.equals(existing.getExternalReference())) {
                    return toResult(existing);
                }
            }
        }

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
        refundRecord.setPartyId(paymentIntent.getInvoice().getPartyId());
        refundRecord.setAmount(amount);
        refundRecord.setReason(reason);
        refundRecord.setNotes(notes);
        refundRecord.setExternalReference(normalizedReference);
        refundRecord.setRequestedBy(requestedBy);
        refundRecord.setRequestedAt(Instant.now(clock));
        refundRecord.setStatus(result.isSuccessful() ? RefundStatus.COMPLETED : RefundStatus.FAILED);
        if (result.isSuccessful()) {
            refundRecord.setGatewayReference(result.getGatewayReference());
            refundRecord.setCompletedAt(Instant.now(clock));
        }

        RefundRecord saved = refundRecordRepository.save(refundRecord);
        return toResult(saved);
    }

    @Override
    @NonNull
    public RefundPaymentResult refundInvoiceStandalone(
            @NonNull UUID invoiceId,
            @NonNull BigDecimal amount,
            @NonNull RefundReason reason,
            @Nullable String notes,
            @Nullable String externalReference) {
        requireAuthority(ISSUE_MANUAL_REFUND);
        Invoice invoice =
                invoiceRepository.findById(invoiceId).orElseThrow(() -> new InvoiceNotFoundException(invoiceId));

        List<RefundRecord> existingRefunds = refundRecordRepository.findByInvoice_Id(invoiceId);
        String normalizedReference = normalizeExternalReference(externalReference);
        RefundRecord replayed = findStandaloneReplay(existingRefunds, normalizedReference);
        if (replayed != null) {
            return toResult(replayed);
        }

        // Without a captured amount to bound against, cap cumulative refunds (payment-anchored
        // and standalone alike) at the invoice total so an invoice can never over-refund.
        BigDecimal alreadyRefunded = existingRefunds.stream()
                .filter(refund -> refund.getStatus() != RefundStatus.FAILED)
                .map(RefundRecord::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal refundable = invoice.getTotal().subtract(alreadyRefunded);
        if (amount.compareTo(refundable) > 0) {
            throw new InsufficientRefundableAmountException(
                    "Insufficient refundable amount: " + refundable + " < " + amount);
        }

        return saveStandaloneRefund(invoice, invoice.getPartyId(), amount, reason, notes, normalizedReference);
    }

    @Override
    @NonNull
    public RefundPaymentResult refundPartyStandalone(
            @NonNull String partyId,
            @NonNull BigDecimal amount,
            @NonNull RefundReason reason,
            @Nullable String notes,
            @Nullable String externalReference) {
        requireAuthority(ISSUE_MANUAL_REFUND);
        if (partyId.isBlank()) {
            throw new IllegalArgumentException("partyId must not be blank");
        }

        // Restrict replay candidates to purely party-anchored records: the lookup also returns
        // invoice-anchored standalone refunds (partyId is stamped from the invoice), and a
        // party-endpoint retry must not replay a record carrying a different anchor shape.
        String normalizedReference = normalizeExternalReference(externalReference);
        List<RefundRecord> candidates =
                refundRecordRepository.findByPartyIdAndPaymentIntentIsNull(partyId.trim()).stream()
                        .filter(existing -> existing.getInvoice() == null)
                        .toList();
        RefundRecord replayed = findStandaloneReplay(candidates, normalizedReference);
        if (replayed != null) {
            return toResult(replayed);
        }

        return saveStandaloneRefund(null, partyId.trim(), amount, reason, notes, normalizedReference);
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public List<RefundPaymentResult> listRefundsForInvoice(@NonNull UUID invoiceId) {
        if (!invoiceRepository.existsById(invoiceId)) {
            throw new InvoiceNotFoundException(invoiceId);
        }
        return refundRecordRepository.findAllAnchoredToInvoice(invoiceId).stream()
                .map(PaymentReversalServiceImpl::toResult)
                .toList();
    }

    /**
     * Idempotent replay guard for standalone refunds, mirroring {@link #refundPayment}: a retry
     * carrying the same externalReference returns the existing non-FAILED standalone record
     * instead of paying the customer twice.
     */
    @Nullable
    private static RefundRecord findStandaloneReplay(
            @NonNull List<RefundRecord> candidates, @Nullable String normalizedReference) {
        if (normalizedReference == null) {
            return null;
        }
        return candidates.stream()
                .filter(existing -> existing.getPaymentIntent() == null)
                .filter(existing -> existing.getStatus() != RefundStatus.FAILED)
                .filter(existing -> normalizedReference.equals(existing.getExternalReference()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Persists a standalone refund. There is no PaymentIntent and therefore no gateway leg —
     * the disbursement itself happens out of band (till, check, vendor payment) — so the record
     * completes immediately and exists to carry the refund liability.
     */
    @NonNull
    private RefundPaymentResult saveStandaloneRefund(
            @Nullable Invoice invoice,
            @Nullable String partyId,
            @NonNull BigDecimal amount,
            @NonNull RefundReason reason,
            @Nullable String notes,
            @Nullable String normalizedReference) {
        Instant now = Instant.now(clock);
        RefundRecord refundRecord = new RefundRecord();
        refundRecord.setInvoice(invoice);
        refundRecord.setPartyId(partyId);
        refundRecord.setAmount(amount);
        refundRecord.setReason(reason);
        refundRecord.setNotes(notes);
        refundRecord.setExternalReference(normalizedReference);
        refundRecord.setRequestedBy(SecurityContextHelper.getCurrentUsernameOrDefault("system"));
        refundRecord.setRequestedAt(now);
        refundRecord.setStatus(RefundStatus.COMPLETED);
        refundRecord.setCompletedAt(now);
        return toResult(refundRecordRepository.save(refundRecord));
    }

    @NonNull
    private static RefundPaymentResult toResult(@NonNull RefundRecord saved) {
        RefundPaymentResult resultDto = new RefundPaymentResult();
        resultDto.setRefundId(saved.getId());
        resultDto.setInvoiceId(
                saved.getInvoice() == null ? null : saved.getInvoice().getId());
        resultDto.setPaymentIntentId(
                saved.getPaymentIntent() == null
                        ? null
                        : saved.getPaymentIntent().getId());
        resultDto.setPartyId(saved.getPartyId());
        resultDto.setAmount(saved.getAmount());
        resultDto.setReason(saved.getReason());
        resultDto.setNotes(saved.getNotes());
        resultDto.setStatus(saved.getStatus());
        resultDto.setGatewayReference(saved.getGatewayReference());
        resultDto.setExternalReference(saved.getExternalReference());
        resultDto.setRequestedAt(saved.getRequestedAt());
        resultDto.setCompletedAt(saved.getCompletedAt());
        return resultDto;
    }

    /** Trim the optional external reference and collapse a blank value to {@code null}. */
    @Nullable
    private static String normalizeExternalReference(@Nullable String externalReference) {
        if (externalReference == null || externalReference.isBlank()) {
            return null;
        }
        return externalReference.trim();
    }
}
