package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.PaymentApplicationRequest;
import com.positivity.accounting.internal.dto.PaymentApplicationResponse;
import com.positivity.accounting.internal.entity.PaymentApplicationReversal;
import com.positivity.accounting.internal.entity.ReceivablePayment;
import com.positivity.accounting.service.PaymentApplicationService;
import jakarta.persistence.OptimisticLockException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Primary;
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

    private final PaymentApplicationServiceImpl delegate;

    @Override
    @NonNull
    public ReceivablePayment handlePaymentCleared(
            @NonNull UUID paymentId,
            @NonNull UUID customerId,
            @NonNull String currency,
            @NonNull BigDecimal totalAmount,
            @NonNull Instant clearedAt,
            @NonNull UUID sourceEventId) {
        return delegate.handlePaymentCleared(paymentId, customerId, currency, totalAmount, clearedAt, sourceEventId);
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
    public PaymentApplicationReversal reversePaymentApplication(
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
