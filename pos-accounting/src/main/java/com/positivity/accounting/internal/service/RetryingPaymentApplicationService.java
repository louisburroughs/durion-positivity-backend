package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.PaymentApplicationRequest;
import com.positivity.accounting.internal.dto.PaymentApplicationResponse;
import com.positivity.accounting.internal.dto.PaymentApplicationReversalResponse;
import com.positivity.accounting.internal.dto.RemainderCreditRequest;
import com.positivity.accounting.internal.dto.RemainderCreditResponse;
import com.positivity.accounting.internal.entity.ReceivablePayment;
import com.positivity.accounting.internal.enums.ApplicationSource;
import jakarta.persistence.OptimisticLockException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Concurrency-hardening decorator for {@link PaymentApplicationService}
 * (Story C4, issue #936).
 *
 * <p>
 * {@link PaymentApplicationServiceImpl} is class-level {@code @Transactional},
 * so an optimistic-lock conflict on {@link ReceivablePayment} (its
 * {@code @Version} column) surfaces at commit time — <em>outside</em> the
 * transactional method. The retry therefore has to live outside the
 * transaction boundary: this bean is deliberately <strong>not</strong>
 * transactional and invokes the delegate through its Spring proxy, so each
 * attempt runs in its own fresh transaction with a fresh persistence context.
 *
 * <p>
 * Retry semantics for {@code applyPaymentToInvoices}:
 * <ul>
 * <li>First optimistic-lock conflict → retry the whole operation exactly once.
 * The retry re-reads fresh state and re-runs all validations, including the
 * {@code applicationRequestId} idempotency check (AD-010) — a replayed
 * request that lost a race still returns the recorded idempotent
 * response.</li>
 * <li>Second conflict → 409 CONFLICT via {@link ResponseStatusException},
 * matching the module's error conventions.</li>
 * <li>Any non-conflict failure propagates unchanged, without a retry.</li>
 * </ul>
 */
@Slf4j
@Service
@Primary
@RequiredArgsConstructor
public class RetryingPaymentApplicationService implements PaymentApplicationService {

    /**
     * The unique index on a credit's namespaced request id (V6, #2524). Two simultaneous remainder-credit
     * requests with the same key both pass the replay lookup; the loser's insert fails on this index. That
     * is a lost race, not an error: the retry finds the winner's credit and replays it (AC9).
     */
    static final String CREDIT_REQUEST_ID_INDEX = "uq_customer_credit_request_id";

    private final PaymentApplicationServiceImpl delegate;

    @Override
    @NonNull
    public ReceivablePayment handlePaymentCleared(
            @NonNull UUID paymentId,
            @NonNull UUID customerId,
            @NonNull String currency,
            @NonNull BigDecimal totalAmount,
            @NonNull Instant clearedAt,
            @NonNull UUID sourceEventId,
            @Nullable UUID sourceInvoiceId,
            @Nullable String paymentMethod) {
        return delegate.handlePaymentCleared(
                paymentId, customerId, currency, totalAmount, clearedAt, sourceEventId, sourceInvoiceId, paymentMethod);
    }

    @Override
    @NonNull
    public PaymentApplicationResponse applyPaymentToInvoices(
            @NonNull UUID paymentId, @NonNull PaymentApplicationRequest request) {
        try {
            return delegate.applyPaymentToInvoices(paymentId, request);
        } catch (RuntimeException firstFailure) {
            if (!isOptimisticLockConflict(firstFailure)) {
                throw firstFailure;
            }
            log.warn(
                    "Optimistic lock conflict applying payment {} (request {}); retrying once",
                    paymentId,
                    request.getApplicationRequestId(),
                    firstFailure);
            try {
                return delegate.applyPaymentToInvoices(paymentId, request);
            } catch (RuntimeException secondFailure) {
                if (!isOptimisticLockConflict(secondFailure)) {
                    throw secondFailure;
                }
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Payment " + paymentId + " was modified concurrently; please retry the request",
                        secondFailure);
            }
        }
    }

    /**
     * Not retried here: the automatic paths call it inside their own transaction (the settlement
     * listener's handler transaction, the event processor's), where a retry would rejoin a
     * transaction already marked rollback-only. A conflict rolls that transaction back and the
     * caller's own redelivery or retry re-runs it; the request id makes the re-run idempotent.
     */
    @Override
    @NonNull
    public PaymentApplicationResponse applyAutomatically(
            @NonNull UUID paymentId,
            @NonNull UUID invoiceId,
            @NonNull BigDecimal amount,
            @NonNull String requestId,
            @NonNull Instant appliedAt,
            @NonNull ApplicationSource source) {
        return delegate.applyAutomatically(paymentId, invoiceId, amount, requestId, appliedAt, source);
    }

    @Override
    public PaymentApplicationResponse.@Nullable CustomerCreditInfo creditUnappliedPayment(
            @NonNull UUID paymentId, @NonNull String creditRequestId) {
        return delegate.creditUnappliedPayment(paymentId, creditRequestId);
    }

    /**
     * Same one-retry rule as {@link #applyPaymentToInvoices} (CAP:550 S35, #2524), applied to an
     * optimistic-lock conflict and to a lost race on {@link #CREDIT_REQUEST_ID_INDEX}: the retry re-runs
     * the idempotency lookup, so a request that lost a race returns the credit the winner issued; a
     * second conflict is 409 OPTIMISTIC_LOCK.
     */
    @Override
    @NonNull
    public BigDecimal releaseRefundedRemainder(
            @NonNull UUID paymentId, @NonNull BigDecimal refundedAmount, @NonNull UUID refundId) {
        // Inside the refund handler's transaction: a conflict rolls the whole record back for redelivery.
        return delegate.releaseRefundedRemainder(paymentId, refundedAmount, refundId);
    }

    @Override
    @NonNull
    public RemainderCreditResponse creditPaymentRemainder(
            @NonNull UUID paymentId, @NonNull RemainderCreditRequest request) {
        try {
            return delegate.creditPaymentRemainder(paymentId, request);
        } catch (RuntimeException firstFailure) {
            if (!isOptimisticLockConflict(firstFailure) && !isRequestIdRace(firstFailure)) {
                throw firstFailure;
            }
            log.warn(
                    "Concurrent update crediting the remainder of payment {} (request {}); retrying once",
                    paymentId,
                    request.getRequestId(),
                    firstFailure);
            try {
                return delegate.creditPaymentRemainder(paymentId, request);
            } catch (RuntimeException secondFailure) {
                if (!isOptimisticLockConflict(secondFailure)) {
                    throw secondFailure;
                }
                // 409 OPTIMISTIC_LOCK (AccountingExceptionHandler#handleOptimisticLock), the code the
                // story names; the apply path keeps its older REQUEST_FAILED envelope.
                throw new OptimisticLockingFailureException(
                        "Payment " + paymentId + " was modified concurrently; please retry the request", secondFailure);
            }
        }
    }

    @Override
    public void voidPayment(@NonNull UUID paymentId) {
        delegate.voidPayment(paymentId);
    }

    @Override
    public void reversePayment(@NonNull UUID paymentId, @NonNull String reason) {
        delegate.reversePayment(paymentId, reason);
    }

    @Override
    @NonNull
    public PaymentApplicationReversalResponse reversePaymentApplication(
            @NonNull UUID paymentApplicationId, @NonNull String reason) {
        return delegate.reversePaymentApplication(paymentApplicationId, reason);
    }

    /**
     * Whether the failure (anywhere in its cause chain) is an optimistic-lock
     * conflict. Depending on where the flush happens, the conflict may surface
     * as Spring's {@link OptimisticLockingFailureException} (or a subclass) or
     * as a raw JPA {@link OptimisticLockException}, possibly wrapped in a
     * transaction-commit exception — so the whole chain is inspected.
     *
     * @param failure thrown exception
     * @return true if the failure is an optimistic-lock conflict
     */
    /**
     * Whether the failure is an integrity violation of {@link #CREDIT_REQUEST_ID_INDEX}: a
     * {@link DataIntegrityViolationException} somewhere in the cause chain, and the index named by it or
     * by one of its causes (the driver's message names the constraint). Any other integrity violation is
     * a real error and is not retried.
     */
    static boolean isRequestIdRace(@NonNull Throwable failure) {
        boolean integrityViolation = false;
        boolean namesIndex = false;
        for (Throwable current = failure; current != null; ) {
            integrityViolation |= current instanceof DataIntegrityViolationException;
            namesIndex |= String.valueOf(current.getMessage()).contains(CREDIT_REQUEST_ID_INDEX);
            Throwable cause = current.getCause();
            current = (cause == current) ? null : cause;
        }
        return integrityViolation && namesIndex;
    }

    static boolean isOptimisticLockConflict(@NonNull Throwable failure) {
        for (Throwable current = failure; current != null; ) {
            if (current instanceof OptimisticLockingFailureException || current instanceof OptimisticLockException) {
                return true;
            }
            Throwable cause = current.getCause();
            current = (cause == current) ? null : cause;
        }
        return false;
    }
}
