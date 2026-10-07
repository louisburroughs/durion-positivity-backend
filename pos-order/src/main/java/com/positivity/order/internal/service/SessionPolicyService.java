package com.positivity.order.internal.service;

import com.positivity.order.internal.service.model.SessionPolicyChangeView;
import com.positivity.order.internal.service.model.SessionPolicyView;
import com.positivity.order.internal.service.model.UpdateSessionPolicyCommand;
import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * The tenant's drawer policy (CAP:550 S16, #2512; SPEC-accounting-workspace §4.6 "Drawer limits", §7.2,
 * AW19): allowed and cashier limit per movement type, and the over/short tolerance. Order owns it.
 */
public interface SessionPolicyService {

    /** The policy in effect: the stored one, else the defaults. */
    @NonNull
    SessionPolicyView current();

    /** Every change, newest first. */
    @NonNull
    List<SessionPolicyChangeView> history();

    /**
     * Replace the two configurable types and the tolerance. A PUT that changes nothing writes nothing;
     * otherwise one history row per changed setting is written with the actor and the justification.
     *
     * @throws com.positivity.order.internal.exception.SessionPolicyValidationException on a field rule
     * @throws com.positivity.order.internal.exception.SessionPolicyConflictException when another PUT won
     */
    @NonNull
    SessionPolicyView update(@NonNull UpdateSessionPolicyCommand command);
}
