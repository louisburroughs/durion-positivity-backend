package com.positivity.shopmanager.service;

import com.positivity.shopmanager.service.dto.HrMechanicEvent;
import java.util.List;
import org.jspecify.annotations.NonNull;

public interface MechanicSyncService {
    void processHrEvent(@NonNull HrMechanicEvent event);

    void reconcileFromHr();

    /**
     * Replace-set a mechanic's skill enrichment (shop-manager-owned data the HR feed never
     * carries). Routed through {@link #processHrEvent} as a synthetic MECHANIC_SKILLS_UPDATED
     * event stamped with a now-millis version, so it participates in the feed's dedupe and
     * stale-guard ordering (last-write-wins by timestamp) and advances the sync version.
     * Throws 404 when no mechanic exists for the person.
     */
    void replaceSkills(@NonNull String personId, @NonNull List<HrMechanicEvent.Payload.Skill> skills);
}
