package com.positivity.order.internal.service;

import com.positivity.events.EmitEvent;
import com.positivity.order.internal.entity.SessionPolicy;
import com.positivity.order.internal.entity.SessionPolicyChange;
import com.positivity.order.internal.repository.SessionPolicyChangeRepository;
import com.positivity.order.internal.repository.SessionPolicyRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes one drawer-policy change and its history rows (CAP:550 S16, #2512). It is the only place the
 * {@code ORDER_SESSION_POLICY_UPDATE} event is emitted from, and {@link SessionPolicyServiceImpl} calls it
 * only when a setting changed, so a PUT that changes nothing emits nothing (review l6).
 */
@Component
@RequiredArgsConstructor
public class SessionPolicyWriter {

    private final SessionPolicyRepository sessionPolicyRepository;
    private final SessionPolicyChangeRepository sessionPolicyChangeRepository;

    /**
     * Saves {@code policy} (flushing, so a lost race fails here) and one history row per changed setting,
     * stamped with the version the change produced.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @EmitEvent(id = "ORDER_SESSION_POLICY_UPDATE", apiVersion = "1")
    public @NonNull SessionPolicy write(@NonNull SessionPolicy policy, @NonNull List<SessionPolicyChange> changes) {
        SessionPolicy saved = sessionPolicyRepository.saveAndFlush(policy);
        for (SessionPolicyChange change : changes) {
            change.setPolicyVersion(saved.getVersion());
            sessionPolicyChangeRepository.save(change);
        }
        return saved;
    }
}
