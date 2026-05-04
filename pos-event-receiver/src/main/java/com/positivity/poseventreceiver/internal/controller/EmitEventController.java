package com.positivity.poseventreceiver.internal.controller;

import com.positivity.poseventreceiver.internal.dto.EmitEventRequest;
import com.positivity.poseventreceiver.service.EmitEventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * Event receiver controller for storing emitted events from other services.
 *
 * <h2>Security Model</h2>
 * <p>
 * This controller does NOT use JWT authentication. Instead, security is
 * enforced via:
 * <ul>
 * <li><b>Preregistration validation:</b> Only events with preregistered IDs are
 * accepted.
 * Event types must be registered at service startup via
 * {@code EventTypeInitializer}
 * classes using the pos-events module.</li>
 * <li><b>Internal network isolation:</b> This service runs within the Docker
 * network
 * (pos-network) and is not exposed externally.</li>
 * <li><b>Annotation-based emission:</b> Only code using {@code @EmitEvent} can
 * emit events,
 * and those annotations reference preregistered event type IDs.</li>
 * </ul>
 * </p>
 * <p>
 * This design avoids circular dependencies with pos-security-service during
 * startup,
 * while still ensuring that only valid, known event types are recorded.
 * </p>
 */
@Slf4j
@RequiredArgsConstructor
@RestController
@Validated
@RequestMapping("/v1/events")
@Tag(name = "Event Emission", description = "Receive and persist emitted events from internal services")
public class EmitEventController {
    private final EmitEventService emitEventService;

    /**
     * ⚠️ CRITICAL: DO NOT ADD @EmitEvent TO ANY METHOD IN THIS CONTROLLER! ⚠️
     *
     * This controller is the event receiver itself. Adding @EmitEvent here would
     * cause
     * INFINITE CIRCULAR RECURSION:
     * 1. External service emits event → pos-event-receiver receives it
     * 2. @EmitEvent on this method would emit another event → pos-event-receiver
     * receives it
     * 3. Repeat forever until stack overflow or resource exhaustion
     *
     * The pos-event-receiver module must NEVER emit events to itself.
     * This is an architectural invariant that must be preserved.
     */
    @PostMapping
    @Operation(summary = "Receive emitted event", description = "Stores a preregistered emitted event payload", tags = {
            "Event Emission" })
    @ApiResponse(responseCode = "200", description = "Event stored successfully")
    @ApiResponse(responseCode = "400", description = "Event type ID is not preregistered")
    // @EmitEvent - FORBIDDEN: See warning above. Would cause infinite recursion.
    public ResponseEntity<String> receiveEvent(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Event payload to persist", required = true, content = @Content(schema = @Schema(implementation = EmitEventRequest.class))) @Valid @NotNull @RequestBody EmitEventRequest request) {
        try {
            if (!emitEventService.receiveEvent(request)) {
                return ResponseEntity.badRequest().body("ID not preregistered");
            }
            return ResponseEntity.ok("Event stored");
        } catch (IllegalArgumentException e) {
            log.warn("Rejected emitted event payload: {}", e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}
