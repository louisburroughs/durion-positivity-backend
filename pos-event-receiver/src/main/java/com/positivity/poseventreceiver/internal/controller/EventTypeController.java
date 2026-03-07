package com.positivity.poseventreceiver.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.poseventreceiver.internal.dao.EventDao;
import com.positivity.poseventreceiver.internal.dto.EventTypeMapper;
import com.positivity.poseventreceiver.internal.dto.EventTypeRequest;
import com.positivity.poseventreceiver.internal.dto.EventTypeResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for managing EventTypes used by preregistered events.
 */
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/v1/eventTypes")
@Tag(name = "Event Types", description = "Manage EventTypes for PreregisteredEvents")
public class EventTypeController {

    private final EventDao eventDao;

    @GetMapping
    @Operation(summary = "Get all event types", description = "Retrieve all available event types")
    @ApiResponse(responseCode = "200", description = "List of event types returned successfully", content = @Content(schema = @Schema(implementation = EventTypeResponse.class)))
    public ResponseEntity<List<EventTypeResponse>> getAllEventTypes() {
        log.info("Fetching all event types");
        List<EventTypeResponse> eventTypes = eventDao.getAllEventTypes().stream()
                .map(EventTypeMapper::toResponse)
                .toList();
        return ResponseEntity.ok(eventTypes);
    }

    @GetMapping("/active")
    @Operation(summary = "Get active event types", description = "Retrieve only active event types")
    @ApiResponse(responseCode = "200", description = "List of active event types returned successfully", content = @Content(schema = @Schema(implementation = EventTypeResponse.class)))
    public ResponseEntity<List<EventTypeResponse>> getActiveEventTypes() {
        log.info("Fetching active event types");
        List<EventTypeResponse> eventTypes = eventDao.getActiveEventTypes().stream()
                .map(EventTypeMapper::toResponse)
                .toList();
        return ResponseEntity.ok(eventTypes);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get event type by ID", description = "Retrieve a specific event type by its unique ID")
    @ApiResponse(responseCode = "200", description = "Event type found and returned", content = @Content(schema = @Schema(implementation = EventTypeResponse.class)))
    @ApiResponse(responseCode = "404", description = "Event type not found")
    public ResponseEntity<EventTypeResponse> getEventTypeById(
            @Parameter(description = "EventType ID", required = true, example = "018e1c9f-6b5a-7890-abcd-1234567890ab") @PathVariable UUID id) {
        log.info("Fetching event type with id: {}", id);
        return eventDao.getEventType(id)
                .map(EventTypeMapper::toResponse)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/code/{typeCode}")
    @Operation(summary = "Get event type by code", description = "Retrieve a specific event type by its unique type code")
    @ApiResponse(responseCode = "200", description = "Event type found and returned", content = @Content(schema = @Schema(implementation = EventTypeResponse.class)))
    @ApiResponse(responseCode = "404", description = "Event type not found")
    public ResponseEntity<EventTypeResponse> getEventTypeByCode(
            @Parameter(description = "Event type code", required = true, example = "ORDER_CREATED") @PathVariable String typeCode) {
        log.info("Fetching event type with code: {}", typeCode);
        return eventDao.getEventTypeByCode(typeCode)
                .map(EventTypeMapper::toResponse)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    @EmitEvent(id = "EVENT_RECEIVER_EVENT_TYPE_CREATE", apiVersion = "1")
    @Operation(summary = "Create event type", description = "Create a new event type for preregistered events")
    @ApiResponse(responseCode = "201", description = "Event type created successfully", content = @Content(schema = @Schema(implementation = EventTypeResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request parameters")
    public ResponseEntity<EventTypeResponse> createEventType(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Event type details", required = true, content = @Content(schema = @Schema(implementation = EventTypeRequest.class))) @RequestBody EventTypeRequest request) {
        log.info("Creating new event type: typeCode={}", request.getTypeCode());

        if (eventDao.getEventTypeByCode(request.getTypeCode()).isPresent()) {
            log.warn("Event type with code already exists: {}", request.getTypeCode());
            return ResponseEntity.badRequest().build();
        }

        var eventType = EventTypeMapper.toNewEntity(request.getTypeCode(), request);
        var created = eventDao.saveEventType(eventType);
        log.info("Event type created successfully: id={}, apiVersion={}, p50={}µs, p95={}µs, p99={}µs",
                created.getId(), created.getApiVersion(), created.getP50Micros(),
                created.getP95Micros(), created.getP99Micros());
        return ResponseEntity.status(HttpStatus.CREATED).body(EventTypeMapper.toResponse(created));
    }

    @PutMapping("/code/{typeCode}")
    @EmitEvent(id = "EVENT_RECEIVER_EVENT_TYPE_UPSERT", apiVersion = "1")
    @Operation(summary = "Upsert event type", description = "Create or update an event type by type code")
    @ApiResponse(responseCode = "200", description = "Event type created or updated successfully", content = @Content(schema = @Schema(implementation = EventTypeResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request parameters")
    public ResponseEntity<EventTypeResponse> upsertEventType(
            @Parameter(description = "Event type code", required = true, example = "ORDER_ORDER_CREATE") @PathVariable String typeCode,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Event type details", required = true, content = @Content(schema = @Schema(implementation = EventTypeRequest.class))) @RequestBody EventTypeRequest request) {
        log.info("Upserting event type: typeCode={}", typeCode);

        return eventDao.getEventTypeByCode(typeCode)
                .map(existingType -> {
                    EventTypeMapper.applyRequest(existingType, request);
                    var updated = eventDao.saveEventType(existingType);
                    log.info("Event type updated via upsert: id={}, apiVersion={}, p50={}µs, p95={}µs, p99={}µs",
                            updated.getId(), updated.getApiVersion(), updated.getP50Micros(),
                            updated.getP95Micros(), updated.getP99Micros());
                    return ResponseEntity.ok(EventTypeMapper.toResponse(updated));
                })
                .orElseGet(() -> {
                    var eventType = EventTypeMapper.toNewEntity(typeCode, request);
                    var created = eventDao.saveEventType(eventType);
                    log.info("Event type created via upsert: id={}, apiVersion={}, p50={}µs, p95={}µs, p99={}µs",
                            created.getId(), created.getApiVersion(), created.getP50Micros(),
                            created.getP95Micros(), created.getP99Micros());
                    return ResponseEntity.ok(EventTypeMapper.toResponse(created));
                });
    }

    @PutMapping("/{id}")
    @EmitEvent(id = "EVENT_RECEIVER_EVENT_TYPE_UPDATE", apiVersion = "1")
    @Operation(summary = "Update event type", description = "Update an existing event type")
    @ApiResponse(responseCode = "200", description = "Event type updated successfully", content = @Content(schema = @Schema(implementation = EventTypeResponse.class)))
    @ApiResponse(responseCode = "404", description = "Event type not found")
    public ResponseEntity<EventTypeResponse> updateEventType(
            @Parameter(description = "EventType ID", required = true, example = "018e1c9f-6b5a-7890-abcd-1234567890ab") @PathVariable UUID id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Updated event type details", required = true, content = @Content(schema = @Schema(implementation = EventTypeRequest.class))) @RequestBody EventTypeRequest request) {
        log.info("Updating event type: id={}", id);

        return eventDao.getEventType(id)
                .map(eventType -> {
                    EventTypeMapper.applyRequest(eventType, request);
                    var updated = eventDao.saveEventType(eventType);
                    log.info("Event type updated successfully: id={}, apiVersion={}, p50={}µs, p95={}µs, p99={}µs",
                            id, updated.getApiVersion(), updated.getP50Micros(),
                            updated.getP95Micros(), updated.getP99Micros());
                    return ResponseEntity.ok(EventTypeMapper.toResponse(updated));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    @EmitEvent(id = "EVENT_RECEIVER_EVENT_TYPE_DELETE", apiVersion = "1")
    @Operation(summary = "Delete event type", description = "Delete an event type by ID")
    @ApiResponse(responseCode = "204", description = "Event type deleted successfully")
    @ApiResponse(responseCode = "404", description = "Event type not found")
    public ResponseEntity<Void> deleteEventType(
            @Parameter(description = "EventType ID to delete", required = true, example = "018e1c9f-6b5a-7890-abcd-1234567890ab") @PathVariable UUID id) {
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
