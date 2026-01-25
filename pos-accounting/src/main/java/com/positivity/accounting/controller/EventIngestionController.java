package com.positivity.accounting.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * REST Controller for Accounting Event Ingestion.
 * Handles submission, retrieval, and retry of business events that trigger
 * journal entry creation.
 */
@RestController
@RequestMapping("/v1/accounting/events")
public class EventIngestionController {

    private static final Logger log = LoggerFactory.getLogger(EventIngestionController.class);

    @GetMapping
    @PreAuthorize("hasAuthority('accounting:events:view')")
    public ResponseEntity<Void> listEvents(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String status) {
        log.info("Stub listEvents page={}, size={}, eventType={}, status={}", page, size, eventType, status);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    @GetMapping("/{eventId}")
    @PreAuthorize("hasAuthority('accounting:events:view')")
    public ResponseEntity<Void> getEvent(@PathVariable String eventId) {
        log.info("Stub getEvent eventId={}", eventId);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('accounting:events:submit')")
    public ResponseEntity<Void> submitEvent(@RequestBody(required = false) Object request) {
        log.info("Stub submitEvent");
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    @PostMapping("/{eventId}/retry")
    @PreAuthorize("hasAuthority('accounting:events:retry')")
    public ResponseEntity<Void> retryEventProcessing(
            @PathVariable String eventId,
            @RequestBody(required = false) Object request) {
        log.info("Stub retryEventProcessing eventId={}", eventId);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    @GetMapping("/{eventId}/processing-log")
    @PreAuthorize("hasAuthority('accounting:events:view')")
    public ResponseEntity<Void> getEventProcessingLog(@PathVariable String eventId) {
        log.info("Stub getEventProcessingLog eventId={}", eventId);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
}
