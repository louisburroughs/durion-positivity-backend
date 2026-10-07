package com.positivity.order.internal.service;

import com.positivity.events.EmitEvent;
import org.springframework.stereotype.Component;

/**
 * Emits {@code ORDER_SESSION_POLICY_UPDATE} (CAP:550 S16, #2512). {@link SessionPolicyWriter} calls it
 * only after the transaction that changed the drawer policy commits, so the event never announces a
 * change that rolled back, and a PUT that changes nothing never reaches it.
 */
@Component
public class SessionPolicyUpdateEmitter {

    /** The {@code @EmitEvent} aspect records and publishes the event when this returns. */
    @EmitEvent(id = "ORDER_SESSION_POLICY_UPDATE", apiVersion = "1")
    public void policyUpdated() {
        // Intentionally empty: the aspect around this method does the emitting.
    }
}
