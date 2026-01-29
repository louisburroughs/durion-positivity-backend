package com.positivity.poseventreceiver.internal.controller;

import com.positivity.poseventreceiver.internal.dao.EventDao;
import com.positivity.poseventreceiver.internal.dto.EmitEventRequest;
import com.positivity.poseventreceiver.internal.entity.EmittedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/v1/events")
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
     * Common method to store an event in the persistence layer.
     *
     * @param request The event request containing id and timestamp
     */
    private void storeEvent(EmitEventRequest request) {
        EmittedEvent event = new EmittedEvent(request.id(), request.timestamp(), request.publishedAt());
        eventDao.saveEmittedEvent(event);
    }
}
