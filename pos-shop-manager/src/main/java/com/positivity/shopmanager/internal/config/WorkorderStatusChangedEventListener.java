package com.positivity.shopmanager.internal.config;

import com.positivity.shopmanager.internal.dto.WorkorderStatusChangedEvent;
import com.positivity.shopmanager.service.WorkorderStatusEventService;
import org.jspecify.annotations.NonNull;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class WorkorderStatusChangedEventListener {

    private final WorkorderStatusEventService service;

    public WorkorderStatusChangedEventListener(@NonNull WorkorderStatusEventService service) {
        this.service = service;
    }

    @EventListener
    public void onWorkorderStatusChanged(WorkorderStatusChangedEvent event) {
        service.handleWorkorderStatusChanged(event);
    }
}
