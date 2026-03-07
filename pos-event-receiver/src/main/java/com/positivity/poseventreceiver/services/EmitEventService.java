package com.positivity.poseventreceiver.services;

import com.positivity.poseventreceiver.internal.dto.EmitEventRequest;

public interface EmitEventService {

    /**
     * Listen to EventEmitted events published by the Events module
     * and store them in the Event Receiver persistence layer.
     *
     * @param request The event request containing id and timestamp
     */
    void onEventEmitted(EmitEventRequest request);

}