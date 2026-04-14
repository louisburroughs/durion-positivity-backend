package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.enums.APPaymentStatus;
import com.positivity.accounting.internal.repository.APPaymentRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists AP payment gateway failures in a separate transaction.
 */
@Service
@RequiredArgsConstructor
public class APPaymentFailurePersistenceService {

    private static final Logger log = LoggerFactory.getLogger(APPaymentFailurePersistenceService.class);

    private final APPaymentRepository paymentRepository;

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
}
