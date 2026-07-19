package com.positivity.accounting.internal.handler;

import com.positivity.accounting.internal.dto.SettlementGLPostingEvent;
import com.positivity.accounting.internal.exception.AccountingPeriodClosedException;
import com.positivity.accounting.internal.exception.AccountingPeriodHardLockedException;
import com.positivity.accounting.internal.service.SettlementPostingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Event handler for settlement GL posting work items (story F1c, issue #963).
 *
 * <p>Consumes {@link SettlementGLPostingEvent} work items delivered from the transactional outbox
 * (mirroring the C1 payment-application flow) and delegates the batched journal-entry posting to
 * {@link SettlementPostingService} — the service layer owns repository access (ADR-0011). This
 * handler only adapts the Spring event to the service call and maps failures for outbox retry.
 *
 * <p>Failure semantics: period-gate exceptions propagate unwrapped so the failure reason stays
 * visible in the outbox retry record (retry succeeds once the period is reopened, story B2); any
 * other failure is wrapped in {@link IllegalStateException} so {@link
 * com.positivity.accounting.internal.service.OutboxProcessor} marks the work item failed and retries
 * with backoff.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementGLPostingEventHandler {

    private final SettlementPostingService settlementPostingService;

    @EventListener
    @Transactional
    public void onSettlementGLPosting(@NonNull SettlementGLPostingEvent event) {
        String settlementId = event.getSettlementId();
        try {
            settlementPostingService.postSettlement(settlementId);
        } catch (AccountingPeriodClosedException | AccountingPeriodHardLockedException e) {
            log.warn(
                    "Settlement GL posting blocked by period gate | settlementId={} | error={}",
                    settlementId,
                    e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error(
                    "Failed to process SettlementGLPostingEvent | settlementId={} | error={}",
                    settlementId,
                    e.getMessage(),
                    e);
            throw new IllegalStateException("GL posting failed for settlement: " + settlementId, e);
        }
    }
}
