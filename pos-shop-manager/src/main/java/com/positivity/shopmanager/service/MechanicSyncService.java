package com.positivity.shopmanager.service;

import com.positivity.shopmanager.service.dto.HrMechanicEvent;
import java.util.List;
import org.jspecify.annotations.NonNull;

public interface MechanicSyncService {
    void processHrEvent(@NonNull HrMechanicEvent event);

    void reconcileFromHr();

    /**
     * Replace-set a mechanic's skill enrichment (shop-manager-owned data the HR feed never
     * carries). Leaves the feed-owned sync version untouched. Throws 404 when no mechanic
     * exists for the person.
     */
    void replaceSkills(@NonNull String personId, @NonNull List<HrMechanicEvent.Payload.Skill> skills);
}
