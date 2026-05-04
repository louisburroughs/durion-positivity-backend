package com.positivity.shopmanager.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.shopmanager.internal.dto.AppointmentCreateRequest;
import com.positivity.shopmanager.internal.dto.AppointmentResponse;
import com.positivity.shopmanager.internal.dto.CancelAppointmentRequest;
import com.positivity.shopmanager.internal.dto.RescheduleAppointmentRequest;
import com.positivity.shopmanager.service.AppointmentsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@Slf4j
@Tag(name = "Appointments API", description = "Operations for creating and loading appointments in shop management")
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class AppointmentsController {

        private final AppointmentsService appointmentsService;

        @Operation(summary = "Create appointment", description = "Create a new appointment")
        @ApiResponse(responseCode = "201", description = "Appointment created successfully.")
        @ApiResponse(responseCode = "400", description = "Validation or conflict error — requested slot is unavailable, duplicate source appointment, or request fields are invalid.")
        @ApiResponse(responseCode = "422", description = "Source not eligible — estimate or work order cannot be scheduled (ineligible status).")
        @ApiResponse(responseCode = "501", description = "Not implemented.")
        @EmitEvent(id = "SHOPMGR_APPOINTMENT_CREATE", apiVersion = "1")
        @PostMapping("/appointments")
        @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth", scopes = {
                        "appointments:create", "shop:schedule:edit" })
        @PreAuthorize("hasAnyAuthority('appointments:create','shop:schedule:edit')")
        public ResponseEntity<AppointmentResponse> createAppointment(
                        @Parameter(description = "Appointment creation request body") @Valid @RequestBody AppointmentCreateRequest request,
                        @Parameter(description = "Idempotency key for safe retries") @org.springframework.web.bind.annotation.RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                        @Parameter(description = "Correlation ID for request tracing") @org.springframework.web.bind.annotation.RequestHeader(value = "X-Correlation-Id", required = false) UUID correlationId) {
                log.info(
                                "Create appointment requested. X-Correlation-Id(mask)={}, Idempotency-Key(mask)={}",
                                maskForLog(correlationId),
                                maskForLog(idempotencyKey));
                AppointmentResponse response = appointmentsService.createAppointment(request, idempotencyKey,
                                correlationId);
                return ResponseEntity.created(ServletUriComponentsBuilder.fromCurrentRequest()
                                .path("/{appointmentId}")
                                .buildAndExpand(response.getAppointmentId())
                                .toUri())
                                .body(response);
        }

        @Operation(summary = "Load appointment", description = "Retrieve an appointment by ID")
        @ApiResponse(responseCode = "200", description = "Appointment retrieved successfully.")
        @ApiResponse(responseCode = "404", description = "Appointment not found.")
        @ApiResponse(responseCode = "501", description = "Not implemented.")
        @GetMapping("/appointments/{appointmentId}")
        @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth", scopes = { "appointments:view",
                        "shop:schedule:view" })
        @PreAuthorize("hasAnyAuthority('appointments:view','shop:schedule:view')")
        public ResponseEntity<AppointmentResponse> getAppointment(
                        @Parameter(description = "Appointment ID", example = "appt-123") @PathVariable String appointmentId,
                        @Parameter(description = "Correlation ID for request tracing") @org.springframework.web.bind.annotation.RequestHeader(value = "X-Correlation-Id", required = false) UUID correlationId) {
                log.info(
                                "Load appointment requested. appointmentId(mask)={}, X-Correlation-Id(mask)={}",
                                maskForLog(appointmentId),
                                maskForLog(correlationId));
                AppointmentResponse response = appointmentsService.getById(appointmentId, correlationId);
                return ResponseEntity.ok(response);
        }

        @Operation(summary = "Reschedule appointment", description = "Reschedule an existing appointment")
        @ApiResponse(responseCode = "200", description = "Appointment rescheduled successfully.")
        @ApiResponse(responseCode = "400", description = "Validation error — invalid times, missing mandatory fields, or blank notes required for OTHER reason.")
        @ApiResponse(responseCode = "404", description = "Appointment not found.")
        @ApiResponse(responseCode = "409", description = "Appointment state conflict — appointment is not in a reschedulable status.")
        @PutMapping("/appointments/{appointmentId}/reschedule")
        @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth", scopes = {
                        "appointments:reschedule" })
        @PreAuthorize("hasAuthority('appointments:reschedule')")
        @EmitEvent(id = "SHOPMGR_APPOINTMENT_RESCHEDULE", apiVersion = "1")
        public ResponseEntity<AppointmentResponse> rescheduleAppointment(
                        @PathVariable UUID appointmentId, @Valid @RequestBody RescheduleAppointmentRequest request) {
                return ResponseEntity.ok(appointmentsService.rescheduleAppointment(appointmentId, request));
        }

        @Operation(summary = "Cancel appointment", description = "Cancel a scheduled appointment")
        @ApiResponse(responseCode = "200", description = "Appointment cancelled successfully.")
        @ApiResponse(responseCode = "409", description = "Appointment state conflict.")
        @DeleteMapping("/appointments/{appointmentId}/cancel")
        @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth", scopes = {
                        "appointments:cancel" })
        @PreAuthorize("hasAuthority('appointments:cancel')")
        @EmitEvent(id = "SHOPMGR_APPOINTMENT_CANCEL", apiVersion = "1")
        public ResponseEntity<AppointmentResponse> cancelAppointment(
                        @PathVariable UUID appointmentId, @Valid @RequestBody CancelAppointmentRequest request) {
                return ResponseEntity.ok(appointmentsService.cancelAppointment(appointmentId, request));
        }

        private String maskForLog(Object value) {
                if (value == null) {
                        return "null";
                }
                String sanitized = value.toString().replace('\r', '_').replace('\n', '_').replace('\t', '_');
                int length = sanitized.length();
                if (length <= 4) {
                        return "****";
                }
                return sanitized.substring(0, 2) + "***" + sanitized.substring(length - 2);
        }
}
