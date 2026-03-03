package com.positivity.workorder.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.workorder.internal.dto.CreateEstimateFromAppointmentRequest;
import com.positivity.workorder.internal.dto.CreateEstimateFromAppointmentResponse;
import com.positivity.workorder.service.EstimateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/workorders/estimates")
@RequiredArgsConstructor
@Tag(name = "Estimates from Appointments", description = "Create estimates from shop appointments")
public class EstimateFromAppointmentController {

    @SuppressWarnings("java:S1068")
    private final EstimateService estimateService;

    @PostMapping("/from-appointment")
    @EmitEvent(id = "WORKORDER_ESTIMATE_CREATE_FROM_APPOINTMENT", apiVersion = "1")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Create draft estimate from appointment", description = "Creates a new DRAFT estimate from an appointment. Idempotent: returns existing estimate if appointmentId already has one.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Estimate created"),
            @ApiResponse(responseCode = "200", description = "Existing estimate returned (idempotent)"),
            @ApiResponse(responseCode = "400", description = "Missing or invalid required fields"),
            @ApiResponse(responseCode = "401", description = "Unauthenticated"),
            @ApiResponse(responseCode = "403", description = "Forbidden")
    })
    public ResponseEntity<CreateEstimateFromAppointmentResponse> createEstimateFromAppointment(
            @Valid @RequestBody CreateEstimateFromAppointmentRequest request) {
        CreateEstimateFromAppointmentResponse response = estimateService.createEstimateFromAppointment(request);
        if (response.isCreated()) {
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        }
        return ResponseEntity.ok(response);
    }
}