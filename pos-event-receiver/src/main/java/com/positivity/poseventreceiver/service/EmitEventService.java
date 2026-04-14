package com.positivity.poseventreceiver.service;

import com.positivity.poseventreceiver.internal.dto.EmitEventRequest;
import org.jspecify.annotations.NonNull;

public interface EmitEventService {

    /**
     * Listen to EventEmitted events published by the Events module
     * and store them in the Event Receiver persistence layer.
     *
     * @param request The event request containing id and timestamp
     */
    default void onEventEmitted(@NonNull EmitEventRequest request) {
        receiveEvent(request);
    }

    /**
     * Validates and persists an emitted event request.
     *
     * @param request The event request containing id and timing metadata
     * @return {@code true} when the event id is preregistered and the event is queued
     */
    boolean receiveEvent(@NonNull EmitEventRequest request);
}
