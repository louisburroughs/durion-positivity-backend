package com.positivity.workorder.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.workorder.internal.dto.CreateEstimateFromAppointmentRequest;
import com.positivity.workorder.internal.dto.CreateEstimateFromAppointmentResponse;
import com.positivity.workorder.internal.security.WorkorderPermissions;
import com.positivity.workorder.service.EstimateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RequestMapping("/v1/workorders/estimates")
@RequiredArgsConstructor
@Tag(name = "Estimates from Appointments", description = "Create estimates from shop appointments")
public class EstimateFromAppointmentController {

    @SuppressWarnings("java:S1068")
    private final EstimateService estimateService;

    @PostMapping("/from-appointment")
    @EmitEvent(id = "WORKORDER_ESTIMATE_CREATE_FROM_APPOINTMENT", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:estimate:create"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.ESTIMATE_CREATE + "')")
    @Operation(
            operationId = "createEstimateFromAppointment",
            summary = "Create Draft Estimate From Appointment",
            description = """
                    Creates a DRAFT estimate seeded from a shop appointment, linking the appointment, customer, \
                    vehicle, and location ids onto the new estimate.
                    Use this tool when an appointment arrives and needs an estimate started; do not use \
                    createEstimate, which builds an estimate from scratch without an appointment link or \
                    idempotency guarantee.
                    Preconditions: the caller must hold workorder:estimate:create; the appointment id is not \
                    verified against the scheduling service, and an estimate already linked to the appointmentId \
                    short-circuits creation.
                    Required inputs: idempotencyKey, appointmentId, customerId, vehicleId, and locationId (all \
                    UUIDs); requestedServices is an optional list of free-text service descriptions.
                    Emits a WORKORDER_ESTIMATE_CREATE_FROM_APPOINTMENT event; the call is idempotent on \
                    appointmentId, so retries never create duplicates.
                    Returns 201 with created=true when a new estimate is persisted, and 200 with created=false \
                    and the existing estimateId when the appointment already has one.
                    """)
    @ApiResponse(responseCode = "201", description = "Estimate created")
    @ApiResponse(responseCode = "200", description = "Existing estimate returned (idempotent)")
    @ApiResponse(responseCode = "400", description = "Missing or invalid required fields")
    @ApiResponse(responseCode = "401", description = "Unauthenticated")
    @ApiResponse(responseCode = "403", description = "Forbidden")
    public ResponseEntity<CreateEstimateFromAppointmentResponse> createEstimateFromAppointment(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Appointment reference and party identifiers used to seed the new draft estimate.",
                            required = true,
                            content =
                                    @io.swagger.v3.oas.annotations.media.Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @io.swagger.v3.oas.annotations.media.ExampleObject(
                                                            name = "From appointment",
                                                            value = """
                                                                    {"idempotencyKey":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a01",
                                                                     "appointmentId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a02",
                                                                     "customerId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a03",
                                                                     "vehicleId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a04",
                                                                     "locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a05",
                                                                     "requestedServices":["Front brake inspection","Oil change"]}
                                                                    """)))
                    @Valid
                    @RequestBody
                    CreateEstimateFromAppointmentRequest request) {
        CreateEstimateFromAppointmentResponse response = estimateService.createEstimateFromAppointment(request);
        if (response.isCreated()) {
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        }
        return ResponseEntity.ok(response);
    }
}
