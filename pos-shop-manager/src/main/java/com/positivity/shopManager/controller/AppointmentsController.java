package com.positivity.shopManager.controller;

import com.positivity.shopManager.dto.AppointmentCreateRequest;
import com.positivity.shopManager.dto.AppointmentResponse;
import com.positivity.shopManager.service.AppointmentsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
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
    @PostMapping("/appointments")
    public ResponseEntity<Object> createAppointment(
            @Parameter(description = "Idempotency key for safe retries") @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Parameter(description = "Appointment creation request body") @RequestBody(required = false) AppointmentCreateRequest request) {
        log.info("Create appointment requested. Idempotency-Key={}, payload={}", idempotencyKey, request);
        AppointmentResponse response = appointmentsService.create(request, idempotencyKey);
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
    public ResponseEntity<Object> getAppointment(
            @Parameter(description = "Appointment ID", example = "appt-123") @PathVariable String appointmentId) {
        log.info("Load appointment requested. appointmentId={}", appointmentId);
        AppointmentResponse response = appointmentsService.getById(appointmentId);
        if (response == null) {
            return ResponseEntity.status(501).build();
        }
        return ResponseEntity.ok(response);
    }
}
