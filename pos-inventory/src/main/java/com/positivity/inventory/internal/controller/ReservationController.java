package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.reservation.CreateReservationRequest;
import com.positivity.inventory.internal.dto.reservation.PromoteAllocationRequest;
import com.positivity.inventory.internal.dto.reservation.ReservationResponse;
import com.positivity.inventory.internal.observability.BusinessSpanSupport;
import com.positivity.inventory.service.ReservationService;
import com.positivity.shared.error.ApiError;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(
        name = "bearerAuth",
        scopes = {"inventory:adjustment:create"})
@RequestMapping("/v1/inventory/reservations")
@RequiredArgsConstructor
@Tag(
        name = "Inventory Reservations",
        description = "Reserve, promote, and cancel inventory allocations for workorder lines")
public class ReservationController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ReservationController.class);
    private static final Tracer TRACER = GlobalOpenTelemetry.getTracer("pos-inventory");
    private static final String DOMAIN = "inventory";
    private static final String TEAM = "inventory-eng";

    private final ReservationService reservationService;

    @PostMapping()
    @EmitEvent(id = "INVENTORY_RESERVATION_CREATE_OR_UPDATE", apiVersion = "1")
    @PreAuthorize("hasAuthority('inventory:adjustment:create')")
    @Operation(
            summary = "Create or update a reservation",
            description =
                    "Creates a reservation for a workorder line or updates an existing reservation with the requested quantity",
            tags = {"Inventory Reservations"})
    @ApiResponse(
            responseCode = "201",
            description = "Reservation created or updated",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ReservationResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Validation failure",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required reservation authority",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "Business rule validation failed",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<ReservationResponse> createOrUpdateReservation(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            description = "Reservation create/update payload",
                            content = @Content(schema = @Schema(implementation = CreateReservationRequest.class)))
                    @Valid
                    @RequestBody
                    CreateReservationRequest request) {
        Span span = TRACER.spanBuilder("Reserve Inventory").setSpanKind(SpanKind.INTERNAL).startSpan();
        span.setAttribute("app.operation.name", "Reserve Inventory");
        span.setAttribute("app.operation.type", "command");
        span.setAttribute("app.domain", DOMAIN);
        span.setAttribute("app.team", TEAM);
        try (Scope scope = span.makeCurrent()) {
            ReservationResponse response = reservationService.createOrUpdateReservation(request);
            span.setAttribute("app.operation.outcome", BusinessSpanSupport.OUTCOME_SUCCESS);
            BusinessSpanSupport.logWithTraceContext(log, "Reserved inventory {}", response.getReservationId());
            return ResponseEntity.created(URI.create("/v1/inventory/reservations/" + response.getReservationId()))
                    .body(response);
        } catch (RuntimeException e) {
            BusinessSpanSupport.recordFailure(span, e);
            throw e;
        } finally {
            span.end();
        }
    }

    @PostMapping("/{allocationId}/promote")
    @EmitEvent(id = "INVENTORY_ALLOCATION_PROMOTE_HARD", apiVersion = "1")
    @PreAuthorize("hasAuthority('inventory:adjustment:create')")
    @Operation(
            summary = "Promote allocation to hard",
            description = "Promotes an existing allocation to HARD state when ATP is sufficient. Requires a"
                    + " storageLocationId; the hardened allocation is pinned to that storage location and an"
                    + " ALLOCATION_CREATED ledger event is recorded against it. A repeat promote of an"
                    + " already-HARD allocation keeps its original location and writes no duplicate event.",
            tags = {"Inventory Reservations"})
    @ApiResponse(
            responseCode = "200",
            description = "Allocation promoted",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ReservationResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Validation failure",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required reservation authority",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Allocation, reservation, or storage location not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "Insufficient ATP or business rule validation failed",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<ReservationResponse> promoteToHard(
            @Parameter(description = "Allocation identifier to promote", required = true) @PathVariable
                    UUID allocationId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            description = "Promotion request details",
                            content = @Content(schema = @Schema(implementation = PromoteAllocationRequest.class)))
                    @Valid
                    @RequestBody
                    PromoteAllocationRequest request) {
        Span span = TRACER.spanBuilder("Promote Inventory Allocation").setSpanKind(SpanKind.INTERNAL).startSpan();
        span.setAttribute("app.operation.name", "Promote Inventory Allocation");
        span.setAttribute("app.operation.type", "command");
        span.setAttribute("app.domain", DOMAIN);
        span.setAttribute("app.team", TEAM);
        try (Scope scope = span.makeCurrent()) {
            ReservationResponse response = reservationService.promoteToHard(allocationId, request);
            span.setAttribute("app.operation.outcome", BusinessSpanSupport.OUTCOME_SUCCESS);
            BusinessSpanSupport.logWithTraceContext(log, "Promoted inventory allocation {}", allocationId);
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            BusinessSpanSupport.recordFailure(span, e);
            throw e;
        } finally {
            span.end();
        }
    }

    @DeleteMapping("/{workorderLineId}")
    @EmitEvent(id = "INVENTORY_RESERVATION_CANCEL", apiVersion = "1")
    @PreAuthorize("hasAuthority('inventory:adjustment:create')")
    @Operation(
            summary = "Cancel reservation by workorder line",
            description = "Cancels reservation and releases associated allocations for a workorder line",
            tags = {"Inventory Reservations"})
    @ApiResponse(responseCode = "204", description = "Reservation cancelled")
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required reservation authority",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Reservation not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<Void> cancelReservation(
            @Parameter(description = "Workorder line identifier", required = true) @PathVariable UUID workorderLineId) {
        reservationService.cancelReservation(workorderLineId);
        return ResponseEntity.noContent().build();
    }
}
