package com.positivity.poseventreceiver.internal.services;

import com.positivity.poseventreceiver.dao.EventDao;
import com.positivity.poseventreceiver.internal.dto.EmitEventRequest;
import com.positivity.poseventreceiver.internal.entity.EmittedEvent;
import com.positivity.poseventreceiver.services.EmitEventService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

@Slf4j
@RequiredArgsConstructor
@Service
public class EmitEventServiceImpl implements EmitEventService {
    private final EventDao eventDao;

    /**
     * Listen to EventEmitted events published by the Events module
     * and store them in the Event Receiver persistence layer.
     *
     * @param request The event request containing id and timestamp
     */
    @Override
    public void onEventEmitted(EmitEventRequest request) {
        log.info("Received EventEmitted event: id={}, apiVersion={}, timestamp={}, elapsedMs={}",
                request.id(), request.apiVersion(), request.timestamp(), request.elapsedMs());
        if (!eventDao.isPreregistered(request.id())) {
            log.error("ID not preregistered : id={}", request.id());
        }
        storeEvent(request);
        log.info("Successfully stored emitted event: id={}", request.id());

    }

    /**
     * Common method to store an event in the persistence layer.
     *
     * @param request The event request containing id and timestamp
     */
    private void storeEvent(EmitEventRequest request) {
        EmittedEvent event = new EmittedEvent(request.id(), request.apiVersion(), request.timestamp(),
                request.elapsedMs(), request.publishedAt());
        eventDao.saveEmittedEvent(event);
    }
}
