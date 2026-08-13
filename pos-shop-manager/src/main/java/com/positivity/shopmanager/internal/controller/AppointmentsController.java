package com.positivity.shopmanager.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.shopmanager.internal.dto.AppointmentCreateRequest;
import com.positivity.shopmanager.internal.dto.AppointmentResponse;
import com.positivity.shopmanager.internal.dto.CancelAppointmentRequest;
import com.positivity.shopmanager.internal.dto.RescheduleAppointmentRequest;
import com.positivity.shopmanager.service.AppointmentsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
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

    @Operation(operationId = "createAppointment", summary = "Create a New Shop Appointment", description = """
                    Creates a shop appointment that reserves a time window for a customer's vehicle at a location, \
                    optionally against a specific bay or mobile-unit resource and optionally linked to an originating \
                    estimate or work order.
                    Use this tool when booking new shop work; do not use rescheduleAppointment, which moves the time \
                    window of an appointment that already exists.
                    Preconditions: the customer and vehicle must exist in the local CRM replicas and the vehicle must \
                    belong to the customer; the requested window must not overlap another SCHEDULED appointment on \
                    the same resource at the location; when sourceType (ESTIMATE or WORK_ORDER) is set, sourceId is \
                    required, the source must be eligible for scheduling, and no appointment may already exist for \
                    that source.
                    Required inputs: crmCustomerId, crmVehicleId and locationId (UUIDs), startAt and endAt (UTC \
                    instants, startAt before endAt) and at least one serviceRequestIds entry; an optional \
                    Idempotency-Key header (non-blank, max 128 characters) makes retries safe and replays the \
                    original response only when the retried request matches the stored appointment's scheduling \
                    fields.
                    Emits a SHOPMGR_APPOINTMENT_CREATE event and persists customer and vehicle snapshots on the \
                    appointment, which is created in SCHEDULED status.
                    Returns 400 when the slot is already booked, the idempotency key was reused with a different \
                    request, or sourceId is missing for a supplied sourceType; 404 when the customer or vehicle is \
                    unknown; 409 when the vehicle does not belong to the customer; and 422 when the source estimate \
                    or work order is not eligible for scheduling.
                    """)
    @ApiResponse(responseCode = "201", description = "Appointment created successfully.")
    @ApiResponse(
            responseCode = "400",
            description =
                    "Validation or conflict error — requested slot is unavailable, duplicate source appointment, or request fields are invalid.")
    @ApiResponse(responseCode = "404", description = "Customer or vehicle not found in the local CRM replicas.")
    @ApiResponse(responseCode = "409", description = "Vehicle does not belong to the supplied customer.")
    @ApiResponse(
            responseCode = "422",
            description = "Source not eligible — estimate or work order cannot be scheduled (ineligible status).")
    @ApiResponse(responseCode = "501", description = "Not implemented.")
    @EmitEvent(id = "SHOPMGR_APPOINTMENT_CREATE", apiVersion = "1")
    @PostMapping("/appointments")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"appointments:create", "shop:schedule:edit"})
    @PreAuthorize("hasAnyAuthority('appointments:create','shop:schedule:edit')")
    public ResponseEntity<AppointmentResponse> createAppointment(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Booking details for the appointment: customer, vehicle, location, time window,"
                                            + " service requests and optional originating source document.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(name = "Appointment from an estimate", value = """
                                                                    {"crmCustomerId":"01960003-0000-7000-8000-000000000001",
                                                                     "crmVehicleId":"01960003-0000-7000-8000-000000000002",
                                                                     "locationId":"01960003-0000-7000-8000-000000000003",
                                                                     "resourceId":"BAY-04",
                                                                     "startAt":"2026-06-18T08:00:00Z",
                                                                     "endAt":"2026-06-18T10:00:00Z",
                                                                     "serviceRequestIds":["01960003-0000-7000-8000-000000000004"],
                                                                     "sourceType":"ESTIMATE",
                                                                     "sourceId":"EST-2026-000045"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    AppointmentCreateRequest request,
            @Parameter(description = "Idempotency key for safe retries")
                    @org.springframework.web.bind.annotation.RequestHeader(value = "Idempotency-Key", required = false)
                    String idempotencyKey,
            @Parameter(description = "Correlation ID for request tracing")
                    @org.springframework.web.bind.annotation.RequestHeader(value = "X-Correlation-Id", required = false)
                    UUID correlationId) {
        log.info(
                "Create appointment requested. X-Correlation-Id(mask)={}, Idempotency-Key(mask)={}",
                maskForLog(correlationId),
                maskForLog(idempotencyKey));
        AppointmentResponse response = appointmentsService.createAppointment(request, idempotencyKey, correlationId);
        return ResponseEntity.created(ServletUriComponentsBuilder.fromCurrentRequest()
                        .path("/{appointmentId}")
                        .buildAndExpand(response.getAppointmentId())
                        .toUri())
                .body(response);
    }

    @Operation(operationId = "getAppointmentById", summary = "Get an Appointment by Its ID", description = """
                    Returns the full appointment record, including status, time window, service request ids and the \
                    customer and vehicle snapshots captured at booking.
                    Use this tool when the appointment id is already known; use viewSchedule instead to browse \
                    appointments by location and date.
                    Preconditions: the appointment must exist; appointmentId must be a UUID in canonical text form.
                    Required inputs: appointmentId as a path parameter; there is no request body and no filtering.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 400 when appointmentId is not a valid UUID, and 404 when no appointment exists for the \
                    supplied id.
                    """)
    @ApiResponse(responseCode = "200", description = "Appointment retrieved successfully.")
    @ApiResponse(responseCode = "400", description = "appointmentId is not a valid UUID.")
    @ApiResponse(responseCode = "404", description = "Appointment not found.")
    @ApiResponse(responseCode = "501", description = "Not implemented.")
    @GetMapping("/appointments/{appointmentId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"appointments:view", "shop:schedule:view"})
    @PreAuthorize("hasAnyAuthority('appointments:view','shop:schedule:view')")
    public ResponseEntity<AppointmentResponse> getAppointment(
            @Parameter(description = "Appointment ID (UUID)", example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b")
                    @PathVariable
                    String appointmentId,
            @Parameter(description = "Correlation ID for request tracing")
                    @org.springframework.web.bind.annotation.RequestHeader(value = "X-Correlation-Id", required = false)
                    UUID correlationId) {
        log.info(
                "Load appointment requested. appointmentId(mask)={}, X-Correlation-Id(mask)={}",
                maskForLog(appointmentId),
                maskForLog(correlationId));
        AppointmentResponse response = appointmentsService.getById(appointmentId, correlationId);
        return ResponseEntity.ok(response);
    }

    @Operation(
            operationId = "rescheduleAppointment",
            summary = "Move an Appointment to a New Time Window",
            description = """
                    Moves an existing appointment to a new time window while preserving its resource, customer and \
                    service requests, recording the change in reschedule history and the appointment audit trail.
                    Use this tool when a booked appointment must change times; do not use cancelAppointment, which \
                    terminates the appointment instead of moving it, and do not use createAppointment for a visit \
                    that is not yet booked.
                    Preconditions: the appointment must exist and be in SCHEDULED, CHECKED_IN or WAITING_FOR_PARTS \
                    status; completed, cancelled and other statuses cannot be rescheduled.
                    Required inputs: newStartAt and newEndAt (UTC instants, newStartAt before newEndAt) and a reason \
                    code; rescheduleReasonNotes (max 1000 characters) is mandatory when reason is OTHER, and \
                    notifyCustomer defaults to true.
                    Emits a SHOPMGR_APPOINTMENT_RESCHEDULE event; a downstream workorder reschedule notification is \
                    additionally published only when the appointment carries a workorderLinkRef.
                    Returns 400 when the time window is invalid or notes are missing for reason OTHER, 404 when the \
                    appointment does not exist, and 409 when the appointment status does not permit rescheduling.
                    """)
    @ApiResponse(responseCode = "200", description = "Appointment rescheduled successfully.")
    @ApiResponse(
            responseCode = "400",
            description =
                    "Validation error — invalid times, missing mandatory fields, or blank notes required for OTHER reason.")
    @ApiResponse(responseCode = "404", description = "Appointment not found.")
    @ApiResponse(
            responseCode = "409",
            description = "Appointment state conflict — appointment is not in a reschedulable status.")
    @PutMapping("/appointments/{appointmentId}/reschedule")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"appointments:reschedule"})
    @PreAuthorize("hasAuthority('appointments:reschedule')")
    @EmitEvent(id = "SHOPMGR_APPOINTMENT_RESCHEDULE", apiVersion = "1")
    public ResponseEntity<AppointmentResponse> rescheduleAppointment(
            @PathVariable UUID appointmentId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "New time window with a mandatory reason code and optional notes recorded"
                                    + " in the reschedule history.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Customer-requested reschedule",
                                                            value = """
                                                                    {"newStartAt":"2026-06-19T08:00:00Z",
                                                                     "newEndAt":"2026-06-19T10:00:00Z",
                                                                     "reason":"CUSTOMER_REQUEST",
                                                                     "rescheduleReasonNotes":"Customer asked to move to Friday morning",
                                                                     "notifyCustomer":true}
                                                                    """)))
                    @Valid
                    @RequestBody
                    RescheduleAppointmentRequest request) {
        return ResponseEntity.ok(appointmentsService.rescheduleAppointment(appointmentId, request));
    }

    @Operation(
            operationId = "cancelAppointment",
            summary = "Cancel a Scheduled Appointment With Reason",
            description = """
                    Cancels a scheduled appointment, setting its status to CANCELLED with the supplied reason code \
                    and recording the cancellation in the appointment audit trail.
                    Use this tool when a booked visit will not happen; do not use rescheduleAppointment, which keeps \
                    the appointment alive at a new time.
                    Preconditions: the appointment must exist and be in SCHEDULED status; appointments that are \
                    already checked in, in progress, completed or cancelled are rejected.
                    Required inputs: cancellationReason (CUSTOMER_REQUEST, TECHNICIAN_UNAVAILABLE, WEATHER, \
                    VEHICLE_NOT_READY or OTHER); notes is optional free text up to 1000 characters.
                    Emits a SHOPMGR_APPOINTMENT_CANCEL event; a downstream workorder cancellation notification is \
                    additionally published only when the appointment carries a workorderLinkRef.
                    Returns 404 when the appointment does not exist, and 409 when the appointment is not in \
                    SCHEDULED status.
                    """)
    @ApiResponse(responseCode = "200", description = "Appointment cancelled successfully.")
    @ApiResponse(responseCode = "404", description = "Appointment not found.")
    @ApiResponse(responseCode = "409", description = "Appointment state conflict.")
    @DeleteMapping("/appointments/{appointmentId}/cancel")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"appointments:cancel"})
    @PreAuthorize("hasAuthority('appointments:cancel')")
    @EmitEvent(id = "SHOPMGR_APPOINTMENT_CANCEL", apiVersion = "1")
    public ResponseEntity<AppointmentResponse> cancelAppointment(
            @PathVariable UUID appointmentId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Cancellation reason code and optional free-text notes stored on the"
                                    + " appointment.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Customer-requested cancellation",
                                                            value = """
                                                                    {"cancellationReason":"CUSTOMER_REQUEST",
                                                                     "notes":"Customer rescheduled to next week"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    CancelAppointmentRequest request) {
        return ResponseEntity.ok(appointmentsService.cancelAppointment(appointmentId, request));
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
