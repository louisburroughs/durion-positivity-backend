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
        if (!eventDao.isPreregistered(request.getId())) {
            return ResponseEntity.badRequest().body("ID not preregistered");
        }
        EmittedEvent event = new EmittedEvent(request.getId(), request.getTimestamp());
        eventDao.saveEmittedEvent(event);
        return ResponseEntity.ok("Event stored");
    }

    /**
     * Listen to EventEmitted events published by the Events module
     * and store them in the Event Receiver persistence layer.
     *
     * @param event The domain event from pos-events
     */
    @ApplicationModuleListener
    public void onEventEmitted(EventEmitted event) {
        log.info("Received EventEmitted event: id={}, timestamp={}", event.eventId(), event.timestamp());

        try {
            // Store the event in the Event Receiver API
            EmittedEvent emittedEvent = new EmittedEvent(event.eventId(), event.timestamp());
            eventDao.saveEmittedEvent(emittedEvent);
            log.info("Successfully stored emitted event: id={}", event.eventId());
        } catch (Exception e) {
            log.error("Error storing emitted event: id={}, error={}", event.eventId(), e.getMessage(), e);
        }
    }
}
