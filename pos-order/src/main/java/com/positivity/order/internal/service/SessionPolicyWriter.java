package com.positivity.order.internal.service;

import com.positivity.order.internal.entity.SessionPolicy;
import com.positivity.order.internal.entity.SessionPolicyChange;
import com.positivity.order.internal.repository.SessionPolicyChangeRepository;
import com.positivity.order.internal.repository.SessionPolicyRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Writes one drawer-policy change and its history rows (CAP:550 S16, #2512). It is the only place the
 * {@code ORDER_SESSION_POLICY_UPDATE} event comes from, and {@link SessionPolicyServiceImpl} calls it only
 * when a setting changed, so a PUT that changes nothing emits nothing (review l6). The event is emitted
 * after the transaction commits, never for a change that rolls back (round 2 LOW-1).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionPolicyWriter {

    private final SessionPolicyRepository sessionPolicyRepository;
    private final SessionPolicyChangeRepository sessionPolicyChangeRepository;
    private final SessionPolicyUpdateEmitter updateEmitter;

    /**
     * Saves {@code policy} (flushing, so a lost race fails here) and one history row per changed setting,
     * stamped with the version the change produced, and emits the update event once the caller's
     * transaction commits.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public @NonNull SessionPolicy write(@NonNull SessionPolicy policy, @NonNull List<SessionPolicyChange> changes) {
        SessionPolicy saved = sessionPolicyRepository.saveAndFlush(policy);
        for (SessionPolicyChange change : changes) {
            change.setPolicyVersion(saved.getVersion());
            sessionPolicyChangeRepository.save(change);
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                emitUpdated(saved.getVersion());
            }
        });
        return saved;
    }

    /** The change is committed by now: a failed emission is logged, never turned into a failed PUT. */
    private void emitUpdated(Long version) {
        try {
            updateEmitter.policyUpdated();
        } catch (RuntimeException e) {
            log.warn("Drawer policy version {} committed, but ORDER_SESSION_POLICY_UPDATE was not emitted", version, e);
        }
    }
}
