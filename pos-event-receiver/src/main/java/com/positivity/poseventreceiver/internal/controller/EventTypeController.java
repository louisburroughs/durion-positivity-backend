package com.positivity.poseventreceiver.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.poseventreceiver.internal.dto.EventTypeRequest;
import com.positivity.poseventreceiver.internal.dto.EventTypeResponse;
import com.positivity.poseventreceiver.internal.service.EventTypeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
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
            operationId = "listEventTypes",
            summary = "List all registered event types",
            description = """
                    Lists every registered event type, active and inactive, with its typeCode, description, apiVersion \
                    and p50/p95/p99 latency thresholds in microseconds.
                    Use this tool when auditing the full event-type registry including deactivated types; use \
                    listActiveEventTypes instead when only the types currently accepted for monitoring matter.
                    Preconditions: none beyond service availability; GET requests pass the shared-secret filter \
                    without authentication.
                    Required inputs: none; there are no parameters, no paging and no filtering.
                    No events are emitted and no state changes; this is a read-only projection of the event_type table.
                    Returns 200 with the full list, which is empty when no event types have been registered.
                    """,
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
            operationId = "listActiveEventTypes",
            summary = "List only active event types",
            description = """
                    Lists the event types whose active flag is true, which are the types currently expected to appear \
                    in event traffic and latency monitoring.
                    Use this tool when only live, monitorable event types matter; use listEventTypes instead to audit \
                    the whole registry including deactivated types.
                    Preconditions: none beyond service availability; GET requests pass the shared-secret filter \
                    without authentication.
                    Required inputs: none; there are no parameters, no paging and no filtering.
                    No events are emitted and no state changes; this is a read-only projection of the event_type table \
                    filtered on active = true.
                    Returns 200 with the active list, which is empty when every registered type is inactive.
                    """,
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
            operationId = "getEventTypeById",
            summary = "Get an event type by id",
            description = """
                    Returns a single event type, resolved by its UUID primary key, including its typeCode, \
                    description, active flag, apiVersion and latency thresholds.
                    Use this tool when the event-type id is already known from a prior create or list call; use \
                    getEventTypeByCode instead when only the typeCode string such as ORDER_ORDER_CREATE is known.
                    Preconditions: an event type row with the supplied id must exist.
                    Required inputs: id (UUID) as a path parameter; there is no request body and no filtering.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when no event type exists for the supplied id.
                    """,
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
            operationId = "getEventTypeByCode",
            summary = "Get an event type by code",
            description = """
                    Returns a single event type resolved by its unique typeCode, the same code services reference in \
                    @EmitEvent annotations.
                    Use this tool when only the typeCode string is known; use getEventTypeById instead when the UUID \
                    of the registration row is already at hand.
                    Preconditions: an event type with the given code must exist; the code is trimmed and upper-cased \
                    before lookup, so matching is case-insensitive on input.
                    Required inputs: typeCode as a path parameter, containing only letters, digits and underscores \
                    after normalization.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when no event type matches the normalized code, and 400 when the code contains \
                    characters other than letters, digits and underscores.
                    """,
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
            operationId = "createEventType",
            summary = "Create a new event type",
            description = """
                    Creates a new event type registration whose typeCode other services reference from @EmitEvent \
                    annotations, with latency thresholds used to monitor event performance.
                    Use this tool when registering a brand-new typeCode that must not already exist; use \
                    upsertEventType instead for idempotent create-or-update registration, because a duplicate \
                    typeCode is rejected here.
                    Preconditions: no event type with the same normalized typeCode may exist, and the caller must \
                    present a valid X-Events-Api-Secret shared-secret header when pos.events.api-secret is configured.
                    Required inputs: typeCode (letters, digits and underscores; trimmed and upper-cased) and \
                    description; active defaults to false when omitted from the JSON body, apiVersion defaults to 1, \
                    and p50Micros/p95Micros/p99Micros default to 10000000 microseconds (10 seconds) and must satisfy \
                    p50 <= p95 <= p99 when supplied.
                    Emits an EVENT_RECEIVER_EVENT_TYPE_CREATE event and inserts one event_type row.
                    Returns 201 with the created type, 400 when the typeCode already exists or a field fails \
                    validation, and 401 when the shared secret is missing or invalid.
                    """,
            tags = {"Event Types"})
    @ApiResponse(
            responseCode = "201",
            description = "Event type created successfully",
            content = @Content(schema = @Schema(implementation = EventTypeResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request parameters")
    public ResponseEntity<EventTypeResponse> createEventType(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Event type registration to create, naming the code, description and optional"
                                    + " latency thresholds.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = EventTypeRequest.class),
                                            examples = @ExampleObject(name = "New event type", value = """
                                                                    {"typeCode":"ORDER_ORDER_CREATE",
                                                                     "description":"Emitted when an order is created",
                                                                     "active":true,
                                                                     "apiVersion":"1",
                                                                     "p50Micros":500000,
                                                                     "p95Micros":1000000,
                                                                     "p99Micros":2000000}
                                                                    """)))
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
            operationId = "upsertEventType",
            summary = "Create or update event type by code",
            description = """
                    Creates the event type when the typeCode is not yet registered, or updates it in place when it \
                    is, keyed by the typeCode path segment; this is the idempotent registration path that module \
                    EventTypeInitializers call at startup.
                    Use this tool when the caller does not care whether the type exists yet; use updateEventType \
                    instead when a missing type must fail with 404, and createEventType when a duplicate must be \
                    rejected.
                    Preconditions: the body typeCode, when supplied, must match the path typeCode after \
                    normalization, and the caller must present a valid X-Events-Api-Secret shared-secret header when \
                    pos.events.api-secret is configured.
                    Required inputs: typeCode as a path parameter plus a body with typeCode and description; active \
                    defaults to false when omitted, while a null apiVersion or null p50Micros/p95Micros/p99Micros \
                    keeps the stored values on update and falls back to 1 and 10000000 microseconds on create.
                    Emits an EVENT_RECEIVER_EVENT_TYPE_UPSERT event and inserts or updates one event_type row.
                    Returns 200 for both the create and update outcome, 400 when the path and body typeCode disagree \
                    or a field fails validation, and 401 when the shared secret is missing or invalid.
                    """,
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
                            description =
                                    "Event type registration to create or overwrite; its typeCode must match the path"
                                            + " code when provided.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = EventTypeRequest.class),
                                            examples = @ExampleObject(name = "Startup registration", value = """
                                                                    {"typeCode":"ORDER_ORDER_CREATE",
                                                                     "description":"Emitted when an order is created",
                                                                     "active":true,
                                                                     "apiVersion":"1",
                                                                     "p50Micros":500000,
                                                                     "p95Micros":1000000,
                                                                     "p99Micros":2000000}
                                                                    """)))
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
            operationId = "updateEventType",
            summary = "Update an existing event type",
            description = """
                    Updates an existing event type identified by its UUID, replacing the description and active flag \
                    and selectively overriding apiVersion and the latency thresholds; the stored typeCode itself is \
                    never changed by this operation.
                    Use this tool when the registration id is known and the type must already exist; use \
                    upsertEventType instead to create-or-update by typeCode without knowing the id.
                    Preconditions: an event type row with the supplied id must exist, and the caller must present a \
                    valid X-Events-Api-Secret shared-secret header when pos.events.api-secret is configured.
                    Required inputs: id (UUID) as a path parameter and a body with typeCode and description; active \
                    defaults to false when omitted, and a null apiVersion or null p50Micros/p95Micros/p99Micros keeps \
                    the currently stored values.
                    Emits an EVENT_RECEIVER_EVENT_TYPE_UPDATE event and updates one event_type row.
                    Returns 404 when no event type exists for the id, 400 when a field fails validation such as \
                    thresholds violating p50 <= p95 <= p99, and 401 when the shared secret is missing or invalid.
                    """,
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
                            description =
                                    "Replacement event type details; omitted optional fields fall back to stored or"
                                            + " default values.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = EventTypeRequest.class),
                                            examples = @ExampleObject(name = "Tighten thresholds", value = """
                                                                    {"typeCode":"ORDER_ORDER_CREATE",
                                                                     "description":"Emitted when an order is created",
                                                                     "active":true,
                                                                     "p50Micros":250000,
                                                                     "p95Micros":750000,
                                                                     "p99Micros":1500000}
                                                                    """)))
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
            operationId = "deleteEventType",
            summary = "Delete an event type by id",
            description = """
                    Deletes an event type registration by its UUID, removing the typeCode, metadata and latency \
                    thresholds from the registry.
                    Use this tool when a registration was created in error or is being retired permanently; do not \
                    use it for a temporary pause — updateEventType with active set to false deactivates the type \
                    reversibly instead.
                    Preconditions: an event type row with the supplied id must exist, and the caller must present a \
                    valid X-Events-Api-Secret shared-secret header when pos.events.api-secret is configured.
                    Required inputs: id (UUID) as a path parameter; there is no request body.
                    Emits an EVENT_RECEIVER_EVENT_TYPE_DELETE event and removes one event_type row; already recorded \
                    emitted_event history is not touched.
                    Returns 204 on successful deletion, 404 when no event type exists for the id, and 401 when the \
                    shared secret is missing or invalid.
                    """,
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
