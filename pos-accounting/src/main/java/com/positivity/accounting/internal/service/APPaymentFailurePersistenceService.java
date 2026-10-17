package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.APPayment;
import com.positivity.accounting.internal.enums.APPaymentStatus;
import com.positivity.accounting.internal.repository.APPaymentRepository;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists AP payment failures in a separate transaction: a gateway failure, and a refused GL posting (CAP:550 S42,
 * #2603), which must outlive the rolled-back posting.
 */
@Service
@RequiredArgsConstructor
public class APPaymentFailurePersistenceService {

    private static final Logger log = LoggerFactory.getLogger(APPaymentFailurePersistenceService.class);

    /** The statuses a refused posting may move to {@code GL_POST_FAILED}: never a payment already posted. */
    private static final Set<APPaymentStatus> REFUSABLE =
            EnumSet.of(APPaymentStatus.GL_POST_PENDING, APPaymentStatus.GL_POST_FAILED, APPaymentStatus.RETURN_PENDING);

    private final APPaymentRepository paymentRepository;

    /**
     * Commits the pay command's {@code GATEWAY_PENDING} row before the gateway is called (#2729). A crash or timeout
     * between the call and its answer then leaves a visible orphan (resumed by a resend of the same paymentRef, or
     * reconciled by an operator with reconcileApPaymentGateway) instead of a rolled-back nothing. The pay command's own
     * transaction, and the bill locks it holds, continue around this one.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public @NonNull APPayment persistGatewayPending(@NonNull APPayment payment) {
        return paymentRepository.save(payment);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @SuppressWarnings({"java:S1181", "java:S2221"}) // Best-effort persistence must not break caller flow
    public void persistGatewayFailure(@NonNull UUID paymentId, String errorMessage) {
        try {
            paymentRepository
                    .findById(paymentId)
                    .ifPresentOrElse(
                            payment -> {
                                payment.setStatus(APPaymentStatus.GATEWAY_FAILED);
                                payment.setGatewayResponse(errorMessage);
                                paymentRepository.save(payment);
                                log.info("Persisted gateway failure for payment {}", paymentId);
                            },
                            () -> log.warn("Could not find payment {} to persist gateway failure", paymentId));
        } catch (Exception ex) {
            log.error("Failed to persist gateway failure for payment {}: {}", paymentId, ex.getMessage(), ex);
            // Do not rethrow. Caller already handles primary gateway failure path.
        }
    }

    /**
     * Records a refused posting (CAP:550 S42, #2603): the payment goes {@code GL_POST_FAILED} with the refusal's code
     * as its {@code glPostError}, in its own transaction, after the posting's has rolled back. The row is locked first,
     * so a retry that posted meanwhile is never overwritten. Its remedy is {@code gl-posting-retry}.
     *
     * @param paymentId the payment
     * @param code      the refusal's code ({@link APPaymentPostingService#refusalCode})
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistGLPostRefusal(@NonNull UUID paymentId, @NonNull String code) {
        paymentRepository
                .lockById(paymentId)
                .filter(payment -> REFUSABLE.contains(payment.getStatus()))
                .ifPresent(payment -> {
                    // A RETURN_PENDING payment (#2642) keeps that status: the refusal is the mirror's or the
                    // original's, and glPostError names the remedy either way.
                    if (payment.getStatus() != APPaymentStatus.RETURN_PENDING) {
                        payment.setStatus(APPaymentStatus.GL_POST_FAILED);
                    }
                    payment.setGlPostError(code);
                    paymentRepository.save(payment);
                    log.warn("AP payment posting refused | paymentId={} | code={}", paymentId, code);
                });
    }
}
