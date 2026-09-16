package com.positivity.shopmanager.internal.service;

import com.positivity.shopmanager.internal.service.dto.HrMechanicEvent;
import org.jspecify.annotations.NonNull;

public interface MechanicSyncService {
    void processHrEvent(@NonNull HrMechanicEvent event);

    void reconcileFromHr();
}
