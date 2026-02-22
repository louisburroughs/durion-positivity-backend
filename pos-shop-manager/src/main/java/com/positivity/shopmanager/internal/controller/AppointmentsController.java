package com.positivity.shopmanager.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.shopmanager.internal.dto.AppointmentCreateRequest;
import com.positivity.shopmanager.internal.dto.AppointmentResponse;
import com.positivity.shopmanager.internal.service.AppointmentsServiceImpl;
import com.positivity.shopmanager.service.AppointmentsService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Slf4j
@Tag(name = "Appointments API", description = "Operations for creating and loading appointments in shop management")
@RestController
@RequestMapping("/v1/shop-manager")
@RequiredArgsConstructor
public class AppointmentsController {

    private final AppointmentsService appointmentsService;

    @Operation(summary = "Create appointment", description = "Create a new appointment (idempotent via Idempotency-Key header)")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Appointment created successfully."),
            @ApiResponse(responseCode = "409", description = "Scheduling conflict detected."),
            @ApiResponse(responseCode = "501", description = "Not implemented.")
    })
    @EmitEvent(id = "SHOP_APPOINTMENT_CREATE", apiVersion = "1")
    @PostMapping("/appointments")
    @PreAuthorize("hasAuthority('shop:schedule:edit')")
    public ResponseEntity<Object> createAppointment(
            @Parameter(description = "Idempotency key for safe retries") @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Parameter(description = "Correlation ID for request tracing") @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @Parameter(description = "Appointment creation request body") @RequestBody(required = false) AppointmentCreateRequest request) {
        log.info("Create appointment requested. Idempotency-Key={}, X-Correlation-Id={}, payload={}", idempotencyKey,
                correlationId, request);
        AppointmentResponse response = appointmentsService.create(request, idempotencyKey, correlationId);
        if (response == null) {
            return ResponseEntity.status(501).build();
        }
        return ResponseEntity.status(201).body(response);
    }

    @Operation(summary = "Load appointment", description = "Retrieve an appointment by ID")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Appointment retrieved successfully."),
            @ApiResponse(responseCode = "404", description = "Appointment not found."),
            @ApiResponse(responseCode = "501", description = "Not implemented.")
    })
    @GetMapping("/appointments/{appointmentId}")
    @PreAuthorize("hasAuthority('shop:schedule:view')")
    public ResponseEntity<Object> getAppointment(
            @Parameter(description = "Appointment ID", example = "appt-123") @PathVariable String appointmentId,
            @Parameter(description = "Correlation ID for request tracing") @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        log.info("Load appointment requested. appointmentId={}, X-Correlation-Id={}", appointmentId, correlationId);
        AppointmentResponse response = appointmentsService.getById(appointmentId, correlationId);
        if (response == null) {
            return ResponseEntity.status(501).build();
        }
        return ResponseEntity.ok(response);
    }
}
