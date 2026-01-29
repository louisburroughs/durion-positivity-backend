package com.positivity.poseventreceiver.controller;

import com.positivity.poseventreceiver.dao.EventDao;
import com.positivity.poseventreceiver.dto.EventTypeRequest;
import com.positivity.poseventreceiver.entity.EventType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for managing EventTypes used by PreregisteredEvents.
 * Provides endpoints for creating, retrieving, and deleting event types.
 */
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/v1/event-types")
@Tag(name = "Event Types", description = "Manage EventTypes for PreregisteredEvents")
public class EventTypeController {
    private final EventDao eventDao;

    /**
     * Get all EventTypes.
     *
     * @return List of all EventTypes
     */
    @GetMapping
    @Operation(summary = "Get all event types", description = "Retrieve all available event types")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "List of event types returned successfully", content = @Content(schema = @Schema(implementation = EventType.class)))
    })
    public ResponseEntity<List<EventType>> getAllEventTypes() {
        log.info("Fetching all event types");
        List<EventType> eventTypes = eventDao.getAllEventTypes();
        return ResponseEntity.ok(eventTypes);
    }

    /**
     * Get active EventTypes only.
     *
     * @return List of active EventTypes
     */
    @GetMapping("/active")
    @Operation(summary = "Get active event types", description = "Retrieve only active event types")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "List of active event types returned successfully", content = @Content(schema = @Schema(implementation = EventType.class)))
    })
    public ResponseEntity<List<EventType>> getActiveEventTypes() {
        log.info("Fetching active event types");
        List<EventType> eventTypes = eventDao.getActiveEventTypes();
        return ResponseEntity.ok(eventTypes);
    }

    /**
     * Get EventType by ID.
     *
     * @param id EventType ID
     * @return EventType with the specified ID
     */
    @GetMapping("/{id}")
    @Operation(summary = "Get event type by ID", description = "Retrieve a specific event type by its unique ID")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Event type found and returned", content = @Content(schema = @Schema(implementation = EventType.class))),
            @ApiResponse(responseCode = "404", description = "Event type not found")
    })
    public ResponseEntity<EventType> getEventTypeById(
            @Parameter(description = "EventType ID", required = true, example = "1") @PathVariable Long id) {
        log.info("Fetching event type with id: {}", id);
        return eventDao.getEventType(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Get EventType by type code.
     *
     * @param typeCode Event type code
     * @return EventType with the specified code
     */
    @GetMapping("/code/{typeCode}")
    @Operation(summary = "Get event type by code", description = "Retrieve a specific event type by its unique type code")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Event type found and returned", content = @Content(schema = @Schema(implementation = EventType.class))),
            @ApiResponse(responseCode = "404", description = "Event type not found")
    })
    public ResponseEntity<EventType> getEventTypeByCode(
            @Parameter(description = "Event type code", required = true, example = "ORDER_CREATED") @PathVariable String typeCode) {
        log.info("Fetching event type with code: {}", typeCode);
        return eventDao.getEventTypeByCode(typeCode)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Create a new EventType.
     *
     * @param request EventType details
     * @return Created EventType
     */
    @PostMapping
    @Operation(summary = "Create event type", description = "Create a new event type for PreregisteredEvents")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Event type created successfully", content = @Content(schema = @Schema(implementation = EventType.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request parameters")
    })
    public ResponseEntity<EventType> createEventType(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Event type details", required = true, content = @Content(schema = @Schema(implementation = EventTypeRequest.class))) @RequestBody EventTypeRequest request) {
        log.info("Creating new event type: typeCode={}", request.getTypeCode());

        // Check if type code already exists
        if (eventDao.getEventTypeByCode(request.getTypeCode()).isPresent()) {
            log.warn("Event type with code already exists: {}", request.getTypeCode());
            return ResponseEntity.badRequest().build();
        }

        EventType eventType = new EventType(request.getTypeCode(), request.getDescription());
        eventType.setActive(request.isActive());
        EventType created = eventDao.saveEventType(eventType);
        log.info("Event type created successfully: id={}", created.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Update an existing EventType.
     *
     * @param id      EventType ID
     * @param request Updated EventType details
     * @return Updated EventType
     */
    @PutMapping("/{id}")
    @Operation(summary = "Update event type", description = "Update an existing event type")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Event type updated successfully", content = @Content(schema = @Schema(implementation = EventType.class))),
            @ApiResponse(responseCode = "404", description = "Event type not found")
    })
    public ResponseEntity<EventType> updateEventType(
            @Parameter(description = "EventType ID", required = true, example = "1") @PathVariable Long id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Updated event type details", required = true, content = @Content(schema = @Schema(implementation = EventTypeRequest.class))) @RequestBody EventTypeRequest request) {
        log.info("Updating event type: id={}", id);

        return eventDao.getEventType(id)
                .map(eventType -> {
                    eventType.setDescription(request.getDescription());
                    eventType.setActive(request.isActive());
                    EventType updated = eventDao.saveEventType(eventType);
                    log.info("Event type updated successfully: id={}", id);
                    return ResponseEntity.ok(updated);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Delete an EventType by ID.
     *
     * @param id EventType ID to delete
     * @return No content response
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "Delete event type", description = "Delete an event type by its ID")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Event type deleted successfully"),
            @ApiResponse(responseCode = "404", description = "Event type not found")
    })
    public ResponseEntity<Void> deleteEventType(
            @Parameter(description = "EventType ID to delete", required = true, example = "1") @PathVariable Long id) {
        log.info("Deleting event type: id={}", id);

        if (!eventDao.eventTypeExists(id)) {
            log.warn("Event type not found: id={}", id);
            return ResponseEntity.notFound().build();
        }

        eventDao.deleteEventType(id);
        log.info("Event type deleted successfully: id={}", id);
        return ResponseEntity.noContent().build();
    }
}
