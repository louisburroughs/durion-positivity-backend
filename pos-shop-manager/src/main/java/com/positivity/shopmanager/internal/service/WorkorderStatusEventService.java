package com.positivity.shopmanager.internal.service;

import com.positivity.shopmanager.internal.dto.WorkorderStatusChangedEvent;
import org.jspecify.annotations.NonNull;

public interface WorkorderStatusEventService {

    /**
     * Processes a WorkorderStatusChanged event, updating the linked Appointment
     * status.
     *
     * @param event the event payload
     */
    void handleWorkorderStatusChanged(@NonNull WorkorderStatusChangedEvent event);
}
