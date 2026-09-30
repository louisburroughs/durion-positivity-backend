package com.positivity.shopmanager.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import com.positivity.shopmanager.internal.dto.AppointmentCreateRequest;
import com.positivity.shopmanager.internal.dto.AppointmentCreation;
import com.positivity.shopmanager.internal.dto.AppointmentResponse;
import com.positivity.shopmanager.internal.dto.CancelAppointmentRequest;
import com.positivity.shopmanager.internal.dto.RescheduleAppointmentRequest;
import com.positivity.shopmanager.internal.exception.KeylessDuplicateReplayException;
import com.positivity.shopmanager.internal.security.LocationScopeGuard;
import com.positivity.shopmanager.internal.security.ShopPermissions;
import com.positivity.shopmanager.internal.service.AppointmentsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Objects;
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

/**
 * Appointment booking and lookup.
 *
 * <p>Creating an appointment and reading one by id are additionally subject to the caller's
 * location scope (ADR-0061 §3, #1872): {@code @PreAuthorize} answers "may this caller book / read
 * appointments", and {@link LocationScopeGuard#requireAny} answers "…at this location". Both
 * endpoints accept either of two permissions, so the gate passes when <em>any</em> alternate the
 * caller holds covers the location. The by-id read, reschedule and cancel are gated on the stored
 * appointment's location, after the 404, so the create gate cannot be side-stepped by addressing
 * an appointment directly (reading, moving or cancelling one outside the caller's reach) and ids
 * cannot be probed.
 */
@Slf4j
@Tag(name = "Appointments API", description = "Operations for creating and loading appointments in shop management")
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class AppointmentsController {

    private static final String CREATE_SCOPE_DENIED_DESCRIPTION =
            "Caller holds appointments:create or shop:schedule:edit but its location scope does not cover the"
                    + " requested location (ApiError.code LOCATION_SCOPE_DENIED, see ../durion/docs/architecture/api/ERROR_ENVELOPE.md)";

    private static final String VIEW_SCOPE_DENIED_DESCRIPTION =
            "Caller holds appointments:view or shop:schedule:view but its location scope does not cover the"
                    + " appointment's location (ApiError.code LOCATION_SCOPE_DENIED, see ../durion/docs/architecture/api/ERROR_ENVELOPE.md)";

    private static final String RESCHEDULE_SCOPE_DENIED_DESCRIPTION =
            "Caller holds appointments:reschedule but its location scope does not cover the appointment's"
                    + " location (ApiError.code LOCATION_SCOPE_DENIED, see ../durion/docs/architecture/api/ERROR_ENVELOPE.md)";

    private static final String CANCEL_SCOPE_DENIED_DESCRIPTION =
            "Caller holds appointments:cancel but its location scope does not cover the appointment's"
                    + " location (ApiError.code LOCATION_SCOPE_DENIED, see ../durion/docs/architecture/api/ERROR_ENVELOPE.md)";

    private static final String REPLICATION_PENDING_DESCRIPTION =
            "A record this service replicates from another domain has not arrived yet (ApiError.code"
                    + " CRM_REPLICATION_PENDING for the customer or vehicle, LOCATION_REPLICATION_PENDING for the"
                    + " bay or mobile unit). This is not-yet, not no: retry after the Retry-After interval.";

    private static final String RETRY_AFTER_DESCRIPTION = "Seconds to wait before retrying";

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
                    that source; when resourceType is BAY or MOBILE_UNIT (stated or inferred, see below), resourceId \
                    must resolve to an ACTIVE resource at locationId that is eligible for the appointment's services \
                    and vehicle (DECISION-SHOPMGMT-021) — a BAY must claim every specialty operation on the \
                    appointment (or take no general work, per its bay type's specialty map) and accommodate the \
                    vehicle's GVWR class.
                    Required inputs: crmCustomerId, crmVehicleId and locationId (UUIDs), startAt and endAt (UTC \
                    instants, startAt before endAt) and at least one serviceRequestIds entry; an optional \
                    Idempotency-Key header (non-blank, max 128 characters) makes retries safe and replays the \
                    original response only when the retried request matches the stored appointment's scheduling \
                    fields. resourceType (BAY, MOBILE_UNIT or UNASSIGNED) is optional but is never a way to skip \
                    eligibility on a real resourceId: omit both to book UNASSIGNED; name a resourceId with \
                    resourceType omitted and it is inferred as BAY or MOBILE_UNIT from whichever replica holds that \
                    id (400 if resourceId is set with resourceType UNASSIGNED, or unset with BAY/MOBILE_UNIT; 503 \
                    LOCATION_REPLICATION_PENDING if a well-formed id matches neither replica yet).
                    Emits a SHOPMGR_APPOINTMENT_CREATE event and persists customer and vehicle snapshots on the \
                    appointment, which is created in SCHEDULED status with resourceType stored verbatim.
                    A caller whose appointments:create or shop:schedule:edit grant is location-scoped must have \
                    locationId within reach (ADR-0061).
                    Returns 400 when the slot is already booked, the idempotency key was reused with a different \
                    request, or sourceId is missing for a supplied sourceType; 403 LOCATION_SCOPE_DENIED when the \
                    caller's location scope does not cover locationId; 409 when the vehicle does not belong to the \
                    customer; 503 with a Retry-After header and CRM_REPLICATION_PENDING when the customer or \
                    vehicle has not replicated from CRM yet, or LOCATION_REPLICATION_PENDING when the named \
                    bay or mobile unit has not replicated from Location yet (retry, do not treat as unknown); and \
                    422 when the source estimate \
                    or work order is not eligible for scheduling, the start lies beyond the booking horizon, or the \
                    named resource fails DECISION-SHOPMGMT-021 eligibility (SERVICE_POSITION_INVALID, \
                    SERVICE_POSITION_INACTIVE, SERVICE_POSITION_NOT_EQUIPPED, \
                    SERVICE_POSITION_DUTY_CLASS_EXCEEDED — none of these are overridable).
                    """)
    @ApiResponse(responseCode = "201", description = "Appointment created successfully.")
    @ApiResponse(
            responseCode = "200",
            description = "Replay of an existing appointment: a repeated Idempotency-Key, or an exact keyless"
                    + " resubmission of the same booking (CAP-326). No new appointment was created.")
    @ApiResponse(
            responseCode = "400",
            description = "Validation error — duplicate source appointment, request fields are invalid, or"
                    + " resourceId/resourceType are contradictory (fieldErrors names resourceId): resourceType BAY"
                    + " or MOBILE_UNIT with no resourceId, or resourceId set with resourceType UNASSIGNED.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = CREATE_SCOPE_DENIED_DESCRIPTION,
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Scheduling conflict — a HARD rule fired (DECISION-SHOPMGMT-002 envelope listing every rule"
                    + " that fired, code verbatim) — or the vehicle does not belong to the supplied customer.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "Policy failure, never overridable. SOURCE_NOT_ELIGIBLE when the estimate or work order"
                    + " cannot be scheduled (ineligible status); BOOKING_HORIZON_EXCEEDED when startAt lies beyond"
                    + " the configured booking horizon (DECISION-SHOPMGMT-019; 180 facility-local days by default);"
                    + " or, for a resourceType of BAY or MOBILE_UNIT (DECISION-SHOPMGMT-021, fieldErrors names"
                    + " resourceId), SERVICE_POSITION_INVALID (resourceId malformed, of the other resource kind, or"
                    + " at another location),"
                    + " SERVICE_POSITION_INACTIVE (not ACTIVE), SERVICE_POSITION_NOT_EQUIPPED (a BAY does not claim"
                    + " a specialty operation on the appointment, or takes no general work) or"
                    + " SERVICE_POSITION_DUTY_CLASS_EXCEEDED (the vehicle's GVWR class exceeds the bay's"
                    + " maxDutyClass).",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "501",
            description = "Not implemented.",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "503",
            description = REPLICATION_PENDING_DESCRIPTION,
            headers =
                    @Header(
                            name = "Retry-After",
                            description = RETRY_AFTER_DESCRIPTION,
                            schema = @Schema(type = "integer")),
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "SHOPMGR_APPOINTMENT_CREATE", apiVersion = "1")
    @PostMapping("/appointments")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"appointments:create", "shop:schedule:edit"})
    @PreAuthorize(
            "hasAnyAuthority('" + ShopPermissions.APPOINTMENTS_CREATE + "','" + ShopPermissions.SCHEDULE_EDIT + "')")
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
                                                                     "resourceId":"01960003-0000-7000-8000-000000000010",
                                                                     "resourceType":"BAY",
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
        // locationId is @NotNull-validated before this method runs, so a missing one is a 400 for
        // every caller; the scope gate only ever sees a well-formed id (ADR-0061 §3, #1872).
        LocationScopeGuard.requireAny(
                request.getLocationId(), ShopPermissions.APPOINTMENTS_CREATE, ShopPermissions.SCHEDULE_EDIT);
        AppointmentCreation creation;
        try {
            creation = appointmentsService.createAppointment(request, idempotencyKey, correlationId);
        } catch (KeylessDuplicateReplayException raced) {
            // An exact keyless double-submit lost the race to its twin (CAP-326, spec D17 item 3):
            // the booking transaction is gone, so the twin is loaded afresh and replayed.
            creation = AppointmentCreation.replayed(
                    appointmentsService.getById(raced.getExistingAppointmentId().toString(), correlationId));
        }
        AppointmentResponse response = creation.appointment();
        if (creation.replayed()) {
            // Idempotency-Key or keyless exact duplicate: the existing appointment, not a new one.
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.created(ServletUriComponentsBuilder.fromCurrentRequest()
                        .path("/{appointmentId}")
                        .buildAndExpand(response.getAppointmentId())
                        .toUri())
                .body(response);
    }

    @Operation(operationId = "getAppointmentById", summary = "Get an Appointment by Its ID", description = """
                    Returns the full appointment record, including status, time window, service request ids, the \
                    customer and vehicle snapshots captured at booking, and the derived DECISION-SHOPMGMT-022 \
                    affected flag.
                    Use this tool when the appointment id is already known; use viewSchedule instead to browse \
                    appointments by location and date.
                    Preconditions: the appointment must exist; appointmentId must be a UUID in canonical text form.
                    Required inputs: appointmentId as a path parameter; there is no request body and no filtering.
                    No events are emitted and no state changes; this is a read-only projection.
                    A caller whose appointments:view or shop:schedule:view grant is location-scoped must have the \
                    appointment's location within reach (ADR-0061).
                    Returns 400 when appointmentId is not a valid UUID, 404 when no appointment exists for the \
                    supplied id, and 403 LOCATION_SCOPE_DENIED when the appointment exists but its location is \
                    outside the caller's scope.
                    """)
    @ApiResponse(responseCode = "200", description = "Appointment retrieved successfully.")
    @ApiResponse(
            responseCode = "400",
            description = "appointmentId is not a valid UUID.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = VIEW_SCOPE_DENIED_DESCRIPTION,
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Appointment not found.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "501",
            description = "Not implemented.",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/appointments/{appointmentId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"appointments:view", "shop:schedule:view"})
    @PreAuthorize(
            "hasAnyAuthority('" + ShopPermissions.APPOINTMENTS_VIEW + "','" + ShopPermissions.SCHEDULE_VIEW + "')")
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
        // Existence first (404 from the service), then scope: a 403 for an id that does not exist
        // would let a caller probe which appointment ids are real. An appointment without a
        // location answers "" which a scoped caller cannot cover.
        AppointmentResponse response = appointmentsService.getById(appointmentId, correlationId);
        String appointmentLocation = Objects.toString(response.getLocationId(), "");
        LocationScopeGuard.requireAny(
                appointmentLocation, ShopPermissions.APPOINTMENTS_VIEW, ShopPermissions.SCHEDULE_VIEW);
        return ResponseEntity.ok(response);
    }

    @Operation(
            operationId = "rescheduleAppointment",
            summary = "Move an Appointment to a New Time Window",
            description = """
                    Moves an existing appointment to a new time window and, optionally, onto a different resource \
                    (newResourceType/newResourceId, DECISION-SHOPMGMT-022 rule 3), recording the change in \
                    reschedule history and the appointment audit trail.
                    Use this tool when a booked appointment must change times or resource; do not use \
                    cancelAppointment, which terminates the appointment instead of moving it, and do not use \
                    createAppointment for a visit that is not yet booked.
                    Preconditions: the appointment must exist and be in SCHEDULED, CHECKED_IN or WAITING_FOR_PARTS \
                    status; when newResourceType and newResourceId are both absent, the appointment's own \
                    (unchanged) resource must still pass DECISION-SHOPMGMT-021 eligibility, inferring a missing or \
                    unrecognised stored resourceType from resourceId exactly as a fresh submit would; when either \
                    new-resource field is present, only the resource the appointment ends up on is validated and \
                    conflict-checked, never its old one, so moving off an ineligible resource always succeeds.
                    Required inputs: newStartAt and newEndAt (UTC instants, newStartAt before newEndAt) and a reason \
                    code; rescheduleReasonNotes (max 1000 characters) is mandatory when reason is OTHER, \
                    notifyCustomer defaults to true, and newResourceType/newResourceId are optional.
                    DECISION-SHOPMGMT-004: the first two reschedules of an appointment need no further permission, \
                    as does one that is shop-caused (reason EQUIPMENT_ISSUE, or the appointment was \
                    DECISION-SHOPMGMT-022 affected); the 3rd and later non-exempt reschedule needs \
                    appointments:reschedule:approve and a non-blank approvalReason (max 1000 characters).
                    Emits a SHOPMGR_APPOINTMENT_RESCHEDULE event; a downstream workorder reschedule notification is \
                    additionally published only when the appointment carries a workorderLinkRef.
                    A caller whose appointments:reschedule grant is location-scoped must have the appointment's \
                    location within reach (ADR-0061).
                    Returns 400 when the time window is invalid or notes are missing for reason OTHER, 404 when the \
                    appointment does not exist, 403 LOCATION_SCOPE_DENIED (location out of reach) or FORBIDDEN \
                    (approval required but appointments:reschedule:approve is not held), 409 when the appointment \
                    status does not permit rescheduling, and 422 for BOOKING_HORIZON_EXCEEDED, a \
                    SERVICE_POSITION_INVALID/INACTIVE/NOT_EQUIPPED/DUTY_CLASS_EXCEEDED failure on the resource the \
                    appointment ends up on (DECISION-SHOPMGMT-021, none overridable), or \
                    RESCHEDULE_APPROVAL_REASON_REQUIRED when approval is needed but approvalReason is missing or \
                    blank, and 503 with a Retry-After header and LOCATION_REPLICATION_PENDING when the target \
                    bay or mobile unit has not replicated from Location yet.
                    """)
    @ApiResponse(responseCode = "200", description = "Appointment rescheduled successfully.")
    @ApiResponse(
            responseCode = "400",
            description =
                    "Validation error — invalid times, missing mandatory fields, or blank notes required for OTHER reason.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = RESCHEDULE_SCOPE_DENIED_DESCRIPTION + " Or FORBIDDEN when this is the 3rd or later"
                    + " non-exempt reschedule of the appointment (DECISION-SHOPMGMT-004) and the caller does not"
                    + " hold appointments:reschedule:approve.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Appointment not found.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Appointment state conflict — appointment is not in a reschedulable status.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "Policy failure, never overridable; the appointment keeps its previous window and no"
                    + " reschedule is recorded. BOOKING_HORIZON_EXCEEDED — newStartAt lies beyond the configured"
                    + " booking horizon (DECISION-SHOPMGMT-019; 180 facility-local days by default). Or, for the"
                    + " BAY or MOBILE_UNIT resource the appointment ends up on (DECISION-SHOPMGMT-021, fieldErrors"
                    + " names resourceId): SERVICE_POSITION_INVALID (malformed, of the other resource kind, or at another location),"
                    + " SERVICE_POSITION_INACTIVE (not ACTIVE), SERVICE_POSITION_NOT_EQUIPPED (a BAY no longer"
                    + " claims a specialty operation on the appointment, or takes no general work) or"
                    + " SERVICE_POSITION_DUTY_CLASS_EXCEEDED (the vehicle's GVWR class exceeds the bay's"
                    + " maxDutyClass). Or RESCHEDULE_APPROVAL_REASON_REQUIRED (fieldErrors names approvalReason)"
                    + " when the caller holds appointments:reschedule:approve for a 3rd-or-later non-exempt"
                    + " reschedule but sent no non-blank approvalReason (DECISION-SHOPMGMT-004).",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "503",
            description = "LOCATION_REPLICATION_PENDING — the bay or mobile unit the appointment ends up on has not"
                    + " replicated from Location yet. Retry after the Retry-After interval; the appointment is"
                    + " unchanged.",
            headers =
                    @Header(
                            name = "Retry-After",
                            description = RETRY_AFTER_DESCRIPTION,
                            schema = @Schema(type = "integer")),
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @PutMapping("/appointments/{appointmentId}/reschedule")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"appointments:reschedule"})
    @PreAuthorize("hasAuthority('" + ShopPermissions.APPOINTMENTS_RESCHEDULE + "')")
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
        requireScopeOnStoredLocation(appointmentId, ShopPermissions.APPOINTMENTS_RESCHEDULE);
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
                    A caller whose appointments:cancel grant is location-scoped must have the appointment's \
                    location within reach (ADR-0061).
                    Returns 404 when the appointment does not exist, 403 LOCATION_SCOPE_DENIED when it exists but its \
                    location is outside the caller's scope, and 409 when the appointment is not in SCHEDULED status.
                    """)
    @ApiResponse(responseCode = "200", description = "Appointment cancelled successfully.")
    @ApiResponse(
            responseCode = "403",
            description = CANCEL_SCOPE_DENIED_DESCRIPTION,
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Appointment not found.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Appointment state conflict.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @DeleteMapping("/appointments/{appointmentId}/cancel")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"appointments:cancel"})
    @PreAuthorize("hasAuthority('" + ShopPermissions.APPOINTMENTS_CANCEL + "')")
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
        requireScopeOnStoredLocation(appointmentId, ShopPermissions.APPOINTMENTS_CANCEL);
        return ResponseEntity.ok(appointmentsService.cancelAppointment(appointmentId, request));
    }

    /**
     * Gate for the resource-addressed mutations (ADR-0061 §3, #1872): existence first (404 from
     * the service's read), then the caller's scope against the appointment's stored location — so
     * an id that does not exist never answers 403 and a scoped caller cannot move or cancel work
     * outside its reach. The location is read through the service rather than passed by the
     * client so the boundary is the stored fact, not a request field.
     */
    private void requireScopeOnStoredLocation(UUID appointmentId, String... alternates) {
        AppointmentResponse existing = appointmentsService.getById(appointmentId.toString(), null);
        LocationScopeGuard.requireAny(Objects.toString(existing.getLocationId(), ""), alternates);
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
