package com.positivity.shopmanager.service;

import com.positivity.shopmanager.internal.dto.HrMechanicEvent;
import org.jspecify.annotations.NonNull;

public interface MechanicSyncService {
    void processHrEvent(@NonNull HrMechanicEvent event);

    void reconcileFromHr();
}
