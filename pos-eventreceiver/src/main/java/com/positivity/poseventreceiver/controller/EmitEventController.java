package com.positivity.poseventreceiver.controller;

import com.positivity.poseventreceiver.api.EmitEventRequest;
import com.positivity.poseventreceiver.dao.EventDao;
import com.positivity.poseventreceiver.model.EmittedEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/events")
public class EmitEventController {
    private final EventDao eventDao;

    @Autowired
    public EmitEventController(EventDao eventDao) {
        this.eventDao = eventDao;
    }

    @PostMapping
    public ResponseEntity<String> emitEvent(@RequestBody EmitEventRequest request) {
        if (!eventDao.isPreregistered(request.getId())) {
            return ResponseEntity.badRequest().body("ID not preregistered");
        }
        EmittedEvent event = new EmittedEvent(request.getId(), request.getTimestamp());
        eventDao.saveEmittedEvent(event);
        return ResponseEntity.ok("Event stored");
    }
}
