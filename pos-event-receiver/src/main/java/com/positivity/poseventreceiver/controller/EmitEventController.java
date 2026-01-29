package com.positivity.poseventreceiver.controller;

import com.positivity.events.EventEmitted;
import com.positivity.poseventreceiver.dao.EventDao;
import com.positivity.poseventreceiver.dto.EmitEventRequest;
import com.positivity.poseventreceiver.entity.EmittedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.modulith.NamedInterface;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/v1/events")
@NamedInterface(name = "Event Receiver API")
public class EmitEventController {
    private final EventDao eventDao;

    @PostMapping
    public ResponseEntity<String> receiveEvent(@RequestBody EmitEventRequest request) {
        if (!eventDao.isPreregistered(request.id())) {
            return ResponseEntity.badRequest().body("ID not preregistered");
        }
        storeEvent(request);
        return ResponseEntity.ok("Event stored");
    }

    /**
     * Listen to EventEmitted events published by the Events module
     * and store them in the Event Receiver persistence layer.
     *
     * @param request The event request containing id and timestamp
     */
    @ApplicationModuleListener
    public void onEventEmitted(EmitEventRequest request) {
        log.info("Received EventEmitted event: id={}, timestamp={}", request.id(), request.timestamp());
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
        EmittedEvent event = new EmittedEvent(request.id(), request.timestamp(), request.publishedAt());
        eventDao.saveEmittedEvent(event);
    }
}
