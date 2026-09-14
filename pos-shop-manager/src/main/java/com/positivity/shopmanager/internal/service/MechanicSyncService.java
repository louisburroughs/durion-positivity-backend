package com.positivity.shopmanager.internal.service;

import com.positivity.shopmanager.internal.service.dto.HrMechanicEvent;
import java.util.List;
import org.jspecify.annotations.NonNull;

public interface MechanicSyncService {
    void processHrEvent(@NonNull HrMechanicEvent event);

    void reconcileFromHr();

    /**
     * Replace-set a mechanic's skill enrichment (shop-manager-owned data the HR feed never
     * carries), waiting out the replication window for the mechanic projection.
     *
     * <p>Equivalent to {@link #replaceSkills(String, List, boolean)} with {@code awaitReplication}
     * true: the single-mechanic API has one person to resolve and a caller waiting on the answer.
     */
    void replaceSkills(@NonNull String personId, @NonNull List<HrMechanicEvent.Payload.Skill> skills);

    /**
     * Replace-set a mechanic's skill enrichment, choosing whether to wait for the mechanic
     * projection to catch up.
     *
     * <p>Routed through {@link #processHrEvent} as a synthetic MECHANIC_SKILLS_UPDATED event so it
     * participates in the feed's dedupe and both logs. It is marked as an operator edit, so it
     * does not advance the mechanic's feed-ordering version (see
     * {@code HrMechanicEvent#operatorEdit}).
     *
     * <p>{@code awaitReplication} exists for the batch path (#1987). The wait is for consumer lag
     * on {@code people.events.v1}, which is a property of this service and not of any one person:
     * once a request has waited the window out and the projection still has not produced a
     * mechanic, waiting again for the next person in the same request buys nothing and costs
     * another window. A batch therefore spends the wait at most once and asks for no wait
     * thereafter, which is what keeps a chunk of unresolvable rows inside the caller's read
     * timeout rather than multiplying the wait by the row count.
     *
     * @throws com.positivity.shopmanager.internal.exception.MechanicReplicationPendingException
     *     when no mechanic exists for the person once the wait (if any) is spent — the person may
     *     be real and merely unreplicated, and this service cannot tell
     * @throws com.positivity.shopmanager.internal.exception.ShopManagerValidationException when
     *     {@code personId} is not a UUID, and so can never name a person on this platform
     */
    void replaceSkills(
            @NonNull String personId, @NonNull List<HrMechanicEvent.Payload.Skill> skills, boolean awaitReplication);
}
