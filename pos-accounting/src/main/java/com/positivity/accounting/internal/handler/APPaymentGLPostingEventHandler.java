package com.positivity.accounting.internal.handler;

import com.positivity.accounting.internal.dto.APPaymentGLPostingEvent;
import com.positivity.accounting.internal.service.APPaymentFailurePersistenceService;
import com.positivity.accounting.internal.service.APPaymentPostingService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Delivers an executed AP payment to the ledger from the transactional outbox (CAP:550 S42, #2603; AW40, AW41): {@link
 * APPaymentPostingService} posts the payment's own entry (Dr 2000 / Dr 6030 fee / Cr its bank) through the {@code
 * AP_PAYMENT} posting category. No accounting event and no posting-rule version is involved.
 *
 * <p><b>Failures</b> (ruling 4 of #2603):
 *
 * <ul>
 *   <li>A <b>refusal</b> is not transient: {@code GL_MAPPING_NOT_CONFIGURED}, {@code PERIOD_CLOSED}, {@code
 *       PERIOD_HARD_LOCKED} and {@code ACCOUNTING_TIME_ZONE_UNSET}. The money has moved, so the payment stands: it goes
 *       {@code GL_POST_FAILED} with the code as its {@code glPostError}, recorded in a transaction of its own, and the
 *       outbox row completes without spending its retries. The remedy is {@code gl-posting-retry}.
 *   <li><b>Any other exception</b> is transient and propagates, so the outbox retries the row with backoff.
 * </ul>
 *
 * <p>The handler runs no transaction of its own: the posting's transaction rolls back on a refusal before the refusal
 * is recorded, and nothing here would be committed by a caller's.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class APPaymentGLPostingEventHandler {

    private final APPaymentPostingService postingService;
    private final APPaymentFailurePersistenceService failures;

    /** Posts the payment the work item names. */
    @EventListener
    public void onAPPaymentGLPosting(@NonNull APPaymentGLPostingEvent event) {
        log.info(
                "Received APPaymentGLPostingEvent | eventId={} | paymentId={} | paymentRef={}",
                event.getEventId(),
                event.getPaymentId(),
                event.getPaymentRef());
        try {
            postingService.postPending(event.getPaymentId());
        } catch (RuntimeException e) {
            Optional<String> refusal = APPaymentPostingService.refusalCode(e);
            if (refusal.isEmpty()) {
                // Transient: the outbox retries the row.
                throw e;
            }
            log.warn(
                    "AP payment posting refused, payment left GL_POST_FAILED | paymentId={} | code={} | reason={}",
                    event.getPaymentId(),
                    refusal.get(),
                    e.getMessage());
            failures.persistGLPostRefusal(event.getPaymentId(), refusal.get());
        }
    }
}
