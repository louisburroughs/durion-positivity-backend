package com.positivity.poseventreceiver.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.poseventreceiver.internal.dto.EventTypeRequest;
import com.positivity.poseventreceiver.internal.dto.EventTypeResponse;
import com.positivity.poseventreceiver.service.EventTypeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for managing EventTypes used by preregistered events.
 */
@Slf4j
@RequiredArgsConstructor
@RestController
@Validated
@RequestMapping("/v1/eventTypes")
@Tag(name = "Event Types", description = "Manage EventTypes for PreregisteredEvents")
public class EventTypeController {

    private final EventTypeService eventTypeService;

    @GetMapping
    @Operation(
            summary = "Get all event types",
            description = "Retrieve all available event types",
            tags = {"Event Types"})
    @ApiResponse(
            responseCode = "200",
            description = "List of event types returned successfully",
            content = @Content(schema = @Schema(implementation = EventTypeResponse.class)))
    public ResponseEntity<List<EventTypeResponse>> getAllEventTypes() {
        log.info("Fetching all event types");
        return ResponseEntity.ok(eventTypeService.getAllEventTypes());
    }

    @GetMapping("/active")
    @Operation(
            summary = "Get active event types",
            description = "Retrieve only active event types",
            tags = {"Event Types"})
    @ApiResponse(
            responseCode = "200",
            description = "List of active event types returned successfully",
            content = @Content(schema = @Schema(implementation = EventTypeResponse.class)))
    public ResponseEntity<List<EventTypeResponse>> getActiveEventTypes() {
        log.info("Fetching active event types");
        return ResponseEntity.ok(eventTypeService.getActiveEventTypes());
    }

    @GetMapping("/{id}")
    @Operation(
            summary = "Get event type by ID",
            description = "Retrieve a specific event type by its unique ID",
            tags = {"Event Types"})
    @ApiResponse(
            responseCode = "200",
            description = "Event type found and returned",
            content = @Content(schema = @Schema(implementation = EventTypeResponse.class)))
    @ApiResponse(responseCode = "404", description = "Event type not found")
    public ResponseEntity<EventTypeResponse> getEventTypeById(
            @Parameter(description = "EventType ID", required = true, example = "018e1c9f-6b5a-7890-abcd-1234567890ab")
                    @PathVariable
                    @NotNull
                    UUID id) {
        log.info("Fetching event type with id(mask): {}", maskForLog(id));
        return eventTypeService
                .getEventTypeById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/code/{typeCode}")
    @Operation(
            summary = "Get event type by code",
            description = "Retrieve a specific event type by its unique type code",
            tags = {"Event Types"})
    @ApiResponse(
            responseCode = "200",
            description = "Event type found and returned",
            content = @Content(schema = @Schema(implementation = EventTypeResponse.class)))
    @ApiResponse(responseCode = "404", description = "Event type not found")
    public ResponseEntity<EventTypeResponse> getEventTypeByCode(
            @Parameter(description = "Event type code", required = true, example = "ORDER_CREATED")
                    @PathVariable
                    @NotBlank
                    String typeCode) {
        log.info("Fetching event type with code(mask): {}", maskForLog(typeCode));
        try {
            // ResponseEntity.of: 200 + body when present, 404 when empty (Sonar S6863).
            return ResponseEntity.of(eventTypeService.getEventTypeByCode(typeCode));
        } catch (IllegalArgumentException e) {
            log.warn("Invalid event type code lookup(mask) '{}': {}", maskForLog(typeCode), e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping
    @EmitEvent(id = "EVENT_RECEIVER_EVENT_TYPE_CREATE", apiVersion = "1")
    @Operation(
            summary = "Create event type",
            description = "Create a new event type for preregistered events",
            tags = {"Event Types"})
    @ApiResponse(
            responseCode = "201",
            description = "Event type created successfully",
            content = @Content(schema = @Schema(implementation = EventTypeResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request parameters")
    public ResponseEntity<EventTypeResponse> createEventType(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Event type details",
                            required = true,
                            content = @Content(schema = @Schema(implementation = EventTypeRequest.class)))
                    @Valid
                    @NotNull
                    @RequestBody
                    EventTypeRequest request) {
        log.info("Creating new event type: typeCode(mask)={}", maskForLog(request.getTypeCode()));
        try {
            var created = eventTypeService.createEventType(request);
            if (created.isEmpty()) {
                log.warn("Event type with code(mask) already exists: {}", maskForLog(request.getTypeCode()));
                return ResponseEntity.badRequest().build();
            }
            var response = created.get();
            log.info(
                    "Event type created successfully: id={}, apiVersion={}, p50={}µs, p95={}µs, p99={}µs",
                    response.id(),
                    response.apiVersion(),
                    response.p50Micros(),
                    response.p95Micros(),
                    response.p99Micros());
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid create event type request: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    @PutMapping("/code/{typeCode}")
    @EmitEvent(id = "EVENT_RECEIVER_EVENT_TYPE_UPSERT", apiVersion = "1")
    @Operation(
            summary = "Upsert event type",
            description = "Create or update an event type by type code",
            tags = {"Event Types"})
    @ApiResponse(
            responseCode = "200",
            description = "Event type created or updated successfully",
            content = @Content(schema = @Schema(implementation = EventTypeResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request parameters")
    public ResponseEntity<EventTypeResponse> upsertEventType(
            @Parameter(description = "Event type code", required = true, example = "ORDER_ORDER_CREATE")
                    @PathVariable
                    @NotBlank
                    String typeCode,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Event type details",
                            required = true,
                            content = @Content(schema = @Schema(implementation = EventTypeRequest.class)))
                    @Valid
                    @NotNull
                    @RequestBody
                    EventTypeRequest request) {
        log.info("Upserting event type: typeCode(mask)={}", maskForLog(typeCode));
        try {
            var response = eventTypeService.upsertEventType(typeCode, request);
            log.info(
                    "Event type upserted successfully: id={}, apiVersion={}, p50={}µs, p95={}µs, p99={}µs",
                    response.id(),
                    response.apiVersion(),
                    response.p50Micros(),
                    response.p95Micros(),
                    response.p99Micros());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid upsert event type request for code(mask) {}: {}", maskForLog(typeCode), e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    @PutMapping("/{id}")
    @EmitEvent(id = "EVENT_RECEIVER_EVENT_TYPE_UPDATE", apiVersion = "1")
    @Operation(
            summary = "Update event type",
            description = "Update an existing event type",
            tags = {"Event Types"})
    @ApiResponse(
            responseCode = "200",
            description = "Event type updated successfully",
            content = @Content(schema = @Schema(implementation = EventTypeResponse.class)))
    @ApiResponse(responseCode = "404", description = "Event type not found")
    public ResponseEntity<EventTypeResponse> updateEventType(
            @Parameter(description = "EventType ID", required = true, example = "018e1c9f-6b5a-7890-abcd-1234567890ab")
                    @PathVariable
                    @NotNull
                    UUID id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Updated event type details",
                            required = true,
                            content = @Content(schema = @Schema(implementation = EventTypeRequest.class)))
                    @Valid
                    @NotNull
                    @RequestBody
                    EventTypeRequest request) {
        log.info("Updating event type: id(mask)={}", maskForLog(id));
        try {
            var updated = eventTypeService.updateEventType(id, request);
            if (updated.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            EventTypeResponse response = updated.get();
            log.info(
                    "Event type updated successfully: id={}, apiVersion={}, p50={}µs, p95={}µs, p99={}µs",
                    id,
                    response.apiVersion(),
                    response.p50Micros(),
                    response.p95Micros(),
                    response.p99Micros());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid update event type request for id(mask) {}: {}", maskForLog(id), e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    @DeleteMapping("/{id}")
    @EmitEvent(id = "EVENT_RECEIVER_EVENT_TYPE_DELETE", apiVersion = "1")
    @Operation(
            summary = "Delete event type",
            description = "Delete an event type by ID",
            tags = {"Event Types"})
    @ApiResponse(responseCode = "204", description = "Event type deleted successfully")
    @ApiResponse(responseCode = "404", description = "Event type not found")
    public ResponseEntity<Void> deleteEventType(
            @Parameter(
                            description = "EventType ID to delete",
                            required = true,
                            example = "018e1c9f-6b5a-7890-abcd-1234567890ab")
                    @PathVariable
                    @NotNull
                    UUID id) {
        log.info("Deleting event type: id(mask)={}", maskForLog(id));
        try {
            if (!eventTypeService.deleteEventType(id)) {
                log.warn("Event type not found: id(mask)={}", maskForLog(id));
                return ResponseEntity.notFound().build();
            }
            log.info("Event type deleted successfully: id(mask)={}", maskForLog(id));
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            log.warn("Invalid delete event type request for id(mask) {}: {}", maskForLog(id), e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    private String maskForLog(Object value) {
        if (value == null) {
            return "null";
        }
        String sanitized =
                value.toString().replace('\r', '_').replace('\n', '_').replace('\t', '_');
        int length = sanitized.length();
        if (length <= 4) {
            return "****";
        }
        return sanitized.substring(0, 2) + "***" + sanitized.substring(length - 2);
    }
}
