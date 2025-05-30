package com.positivity.poseventreceiver.controller;

import com.positivity.poseventreceiver.api.EmitEventRequest;
import com.positivity.poseventreceiver.dao.EventDao;
import com.positivity.poseventreceiver.model.EmittedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/events")
public class EmitEventController {
    private final EventDao eventDao;

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
