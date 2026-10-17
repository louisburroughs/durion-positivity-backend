package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.RecordAPPaymentReturnRequest;
import com.positivity.accounting.internal.entity.APPayment;
import com.positivity.accounting.internal.entity.APPaymentAllocation;
import com.positivity.accounting.internal.entity.APPaymentReturnRequest;
import com.positivity.accounting.internal.entity.AccountingAuditLog;
import com.positivity.accounting.internal.enums.APPaymentSettlementStatus;
import com.positivity.accounting.internal.enums.APPaymentStatus;
import com.positivity.accounting.internal.enums.PaymentMethod;
import com.positivity.accounting.internal.exception.IdempotencyConflictException;
import com.positivity.accounting.internal.exception.VendorBillException;
import com.positivity.accounting.internal.payment.GatewayPaymentResponse;
import com.positivity.accounting.internal.payment.PaymentGatewayException;
import com.positivity.accounting.internal.payment.PaymentGatewayProvider;
import com.positivity.accounting.internal.repository.APPaymentAllocationRepository;
import com.positivity.accounting.internal.repository.APPaymentRepository;
import com.positivity.accounting.internal.repository.APPaymentReturnRequestRepository;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The returned AP payment (#2642, AW42 applied to a payment; Accounting ruling of 2026-10-17): a charge that moved
 * money and came back. In one transaction the payment's allocations are reopened (kept as history, excluded from
 * every allocated sum, so each bill's open amount is restored), {@code unappliedAmount} becomes zero, the mirror entry
 * (the reversal of the payment's own entry) is posted when the original is posted, and the return is audited.
 *
 * <p>Two callers: the operator's {@code record-return} command ({@link #recordReturn}; a late return the bank
 * statement shows, or a gateway the settlement job cannot read) and the settlement job's re-read ({@link
 * #returnFromGateway}). A payment whose original posting is pending or failed ends {@code RETURN_PENDING}: its
 * allocations reopen at once (2000 is not debited yet) and {@link APPaymentPostingService} posts the mirror right after
 * the original. A mirror refused by a closed period leaves the payment {@code RETURN_PENDING} with {@code glPostError}
 * for {@code gl-posting-retry}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class APPaymentReturnService {

    static final String RETURN_OPERATION = "AP_PAYMENT_RETURN";
    static final String SYSTEM = "system";

    private final APPaymentRepository payments;
    private final APPaymentAllocationRepository allocations;
    private final APPaymentReturnRequestRepository returnRequests;
    private final APPaymentPostingService posting;
    private final AccountingAuditLogRepository auditLogs;
    private final ApLockTimeout lockTimeout;
    private final AccountingCalendarZoneResolver zoneResolver;
    private final ObjectProvider<PaymentGatewayProvider> gateway;
    private final Clock clock;

    /** {@code POST /v1/accounting/ap/payments/{paymentId}/record-return}. */
    @Transactional
    public @NonNull APPayment recordReturn(
            @NonNull UUID paymentId, @NonNull RecordAPPaymentReturnRequest request, @NonNull String currentUser) {
        lockTimeout.apply();
        APPayment payment = payments.lockById(paymentId)
                .orElseThrow(() -> new EntityNotFoundException("AP payment not found: " + paymentId));
        String hash = hash(paymentId, request);
        APPaymentReturnRequest earlier =
                returnRequests.findByRequestId(request.getRequestId()).orElse(null);
        if (earlier != null) {
            if (!earlier.getPaymentId().equals(paymentId)
                    || !earlier.getRequestHash().equals(hash)) {
                throw new IdempotencyConflictException(
                        "requestId " + request.getRequestId() + " was already used with a different payload");
            }
            return payment;
        }
        LocalDate today = zoneResolver.today();
        if (request.getReturnDate() != null && request.getReturnDate().isAfter(today)) {
            throw new VendorBillException(
                    VendorBillException.Code.VALIDATION_ERROR,
                    "returnDate " + request.getReturnDate() + " is after today's business date " + today,
                    List.of(new VendorBillException.FieldError("returnDate", "must not be after today")),
                    null);
        }
        requireReturnable(payment);
        String gatewayNote = gatewayNote(payment);
        applyReturn(
                payment,
                request.getReturnDate(),
                request.getFailureCode(),
                request.getJustification(),
                request.getOverrideJustification(),
                currentUser,
                gatewayNote);
        returnRequests.save(new APPaymentReturnRequest(request.getRequestId(), paymentId, hash));
        return payment;
    }

    /**
     * The settlement job's path: the gateway re-read answered that the charge failed. The payment is already locked
     * by the caller. Nothing is posted into a closed period without an operator: the mirror's refusal is recorded by
     * the caller as {@code glPostError} and the payment stays {@code RETURN_PENDING}.
     */
    @Transactional
    public void returnFromGateway(
            @NonNull APPayment payment, @Nullable String failureCode, @Nullable LocalDate failureDate) {
        requireReturnable(payment);
        applyReturn(payment, failureDate, failureCode, "Gateway re-read: the charge failed", null, SYSTEM, null);
    }

    private void requireReturnable(APPayment payment) {
        APPaymentStatus status = payment.getStatus();
        if (status == APPaymentStatus.RETURNED || status == APPaymentStatus.RETURN_PENDING) {
            throw new VendorBillException(
                    VendorBillException.Code.AP_PAYMENT_ALREADY_RETURNED,
                    "AP payment " + payment.getPaymentRef() + " is already " + status,
                    List.of(),
                    status == APPaymentStatus.RETURN_PENDING
                            ? "Its mirror entry is still owed: retryApPaymentGlPosting once glPostError is remedied"
                            : "Nothing to do; pay the vendor's bills again with a new paymentRef");
        }
        if (payment.getPaymentMethod() == PaymentMethod.CASH) {
            throw new VendorBillException(
                    VendorBillException.Code.AP_PAYMENT_NOT_RETURNABLE,
                    "AP payment " + payment.getPaymentRef()
                            + " is a drawer cash-on-delivery payment; a drawer payout is corrected by a drawer movement,"
                            + " never a return",
                    List.of(),
                    "Record a drawer movement on the register session instead");
        }
        String remedy =
                switch (status) {
                    case INITIATED, GATEWAY_FAILED -> "Nothing moved and nothing to return";
                    case GATEWAY_PENDING -> "Reconcile it with the gateway first (reconcileApPaymentGateway)";
                    case VOIDED -> "Already voided as never charged; nothing to return";
                    default -> null;
                };
        if (remedy != null) {
            throw new VendorBillException(
                    VendorBillException.Code.AP_PAYMENT_NOT_RETURNABLE,
                    "AP payment " + payment.getPaymentRef() + " is " + status
                            + "; only a charge that moved money (GL_POST_PENDING, GL_POSTED, GL_POST_FAILED) is returned",
                    List.of(),
                    remedy);
        }
    }

    private void applyReturn(
            APPayment payment,
            @Nullable LocalDate returnDate,
            @Nullable String failureCode,
            String justification,
            @Nullable String overrideJustification,
            String actor,
            @Nullable String gatewayNote) {
        String before = payment.getStatus().name();
        Instant now = Instant.now(clock);
        List<APPaymentAllocation> live = allocations.findLiveByPaymentId(payment.getPaymentId());
        for (APPaymentAllocation allocation : live) {
            allocation.setReopenedAt(now);
        }
        allocations.saveAll(live);
        payment.setUnappliedAmount(BigDecimal.ZERO);
        payment.setReturnedAt(now);
        payment.setReturnDate(returnDate);
        payment.setReturnFailureCode(failureCode);
        payment.setSettlementStatus(APPaymentSettlementStatus.RETURNED);
        if (payment.getStatus() == APPaymentStatus.GL_POSTED && payment.getGlJournalEntryId() != null) {
            posting.postReturn(payment, returnDate, overrideJustification, actor);
        } else {
            // GL_POST_PENDING or GL_POST_FAILED: the mirror follows the original posting (outbox or retry).
            payment.setStatus(APPaymentStatus.RETURN_PENDING);
            payments.save(payment);
        }
        AccountingAuditLog row = new AccountingAuditLog();
        row.setEntityType("AP_PAYMENT");
        row.setEntityId(payment.getPaymentId());
        row.setOperation(RETURN_OPERATION);
        row.setUserId(actor);
        row.setJustification(justification);
        row.setOldValue(before);
        row.setNewValue(payment.getStatus().name()
                + ";returnDate=" + payment.getReturnDate()
                + ";failureCode=" + failureCode
                + ";returnJournalEntryId=" + payment.getReturnJournalEntryId()
                + ";reopened=" + reopenedList(live)
                + (gatewayNote == null ? "" : ";gateway=" + gatewayNote));
        auditLogs.save(row);
        log.info(
                "AP payment returned | paymentId={} | status={} | failureCode={} | reopenedAllocations={} | by={}",
                payment.getPaymentId(),
                payment.getStatus(),
                failureCode,
                live.size(),
                actor);
    }

    /**
     * The gateway's current answer, recorded with the operator's attestation; it never refuses the command (the bank
     * can return what the processor shows as settled). Unavailable or unknown is recorded as such.
     */
    private @Nullable String gatewayNote(APPayment payment) {
        PaymentGatewayProvider provider = gateway.getIfAvailable();
        if (provider == null || payment.getGatewayTransactionId() == null) {
            return null;
        }
        try {
            Optional<GatewayPaymentResponse> answer = provider.getPaymentStatus(payment.getGatewayTransactionId());
            return answer.map(a -> a.getStatus().name() + (a.getFailureCode() == null ? "" : "/" + a.getFailureCode()))
                    .orElse("NOT_FOUND");
        } catch (PaymentGatewayException e) {
            log.warn(
                    "Gateway re-read failed while recording the return of AP payment {}: {}",
                    payment.getPaymentRef(),
                    e.getMessage());
            return "UNAVAILABLE";
        }
    }

    private static String reopenedList(List<APPaymentAllocation> live) {
        return live.stream()
                .map(a -> a.getVendorBillId() + ":"
                        + a.getAppliedAmount().stripTrailingZeros().toPlainString())
                .collect(Collectors.joining(","));
    }

    private static String hash(UUID paymentId, RecordAPPaymentReturnRequest request) {
        return new RegisterFloatServiceImpl.Hash()
                .field("RETURN")
                .field(paymentId)
                .field(request.getReturnDate())
                .field(request.getFailureCode())
                .field(request.getJustification().trim())
                .digest();
    }
}
