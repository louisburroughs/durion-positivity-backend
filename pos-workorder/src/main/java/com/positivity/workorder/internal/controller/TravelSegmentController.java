package com.positivity.workorder.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.workorder.internal.dto.CreateTravelSegmentAdjustmentRequest;
import com.positivity.workorder.internal.dto.StartTravelSegmentRequest;
import com.positivity.workorder.internal.dto.StopTravelSegmentRequest;
import com.positivity.workorder.internal.dto.SubmitTravelSegmentsRequest;
import com.positivity.workorder.internal.dto.TravelSegmentAdjustmentResponse;
import com.positivity.workorder.internal.dto.TravelSegmentResponse;
import com.positivity.workorder.internal.security.WorkorderPermissions;
import com.positivity.workorder.internal.service.TravelSegmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Travel Segment API", description = "Endpoints for capturing mobile travel segments")
@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(
        name = "bearerAuth",
        scopes = {"workorder:labor:add"})
@RequestMapping("/v1/workorders/travelSegments")
@RequiredArgsConstructor
public class TravelSegmentController {

    private final TravelSegmentService travelSegmentService;

    @PostMapping("/start")
    @EmitEvent(id = "WORKORDER_TRAVEL_SEGMENT_START", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.LABOR_ADD + "')")
    @Operation(operationId = "startTravelSegment", summary = "Start a Travel Segment", description = """
                    Starts an IN_PROGRESS travel segment for a mobile technician, stamping the start time from \
                    the server clock and recording who created it.
                    Use this tool when a technician begins travel tied to a mobile work assignment; do not use \
                    stopTravelSegment, which ends an already-running segment.
                    Preconditions: no IN_PROGRESS travel segment may exist for the same mobile work assignment, \
                    and when actedForPersonId is set an onBehalfReasonCode is mandatory.
                    Required inputs: mobileWorkAssignmentId (UUID), technicianId (UUID), and segmentType \
                    (DEPART_SHOP, ARRIVE_CUSTOMER_SITE, DEPART_CUSTOMER_SITE, ARRIVE_SHOP, TRAVEL_BETWEEN_SITES, \
                    or DEADHEAD); fromLocationId, toLocationId, workOrderId, actedForPersonId, and \
                    onBehalfReasonCode (TECHNICIAN_UNAVAILABLE, FORGOT_TO_CLOCK, DATA_ENTRY_ERROR) are optional.
                    Emits a WORKORDER_TRAVEL_SEGMENT_START event.
                    Returns 201 with the new segment, 400 when actedForPersonId is given without \
                    onBehalfReasonCode, and 409 when an active segment already exists for the assignment.
                    """)
    @ApiResponse(responseCode = "201")
    public ResponseEntity<TravelSegmentResponse> startTravelSegment(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Travel segment start details identifying the assignment, technician, and leg.",
                            required = true,
                            content =
                                    @io.swagger.v3.oas.annotations.media.Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @io.swagger.v3.oas.annotations.media.ExampleObject(
                                                            name = "Depart shop",
                                                            value = """
                                                                    {"mobileWorkAssignmentId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a41",
                                                                     "technicianId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a42",
                                                                     "segmentType":"DEPART_SHOP",
                                                                     "fromLocationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a43",
                                                                     "workOrderId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a44"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    StartTravelSegmentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(travelSegmentService.startTravelSegment(request));
    }

    @PostMapping("/{travelSegmentId}/stop")
    @EmitEvent(id = "WORKORDER_TRAVEL_SEGMENT_STOP", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.LABOR_ADD + "')")
    @Operation(operationId = "stopTravelSegment", summary = "Stop an Active Travel Segment", description = """
                    Stops an IN_PROGRESS travel segment, transitioning it to COMPLETED and computing the duration \
                    in whole minutes from the server clock.
                    Use this tool when a technician arrives and the travel leg ends; do not use \
                    submitTravelSegments, which batches finished segments for approval.
                    Preconditions: the segment must exist and be IN_PROGRESS; COMPLETED, SUBMITTED, or APPROVED \
                    segments cannot be stopped again.
                    Required inputs: travelSegmentId (UUID) as a path parameter; toLocationId in the body is \
                    optional and overrides the destination recorded at start.
                    Emits a WORKORDER_TRAVEL_SEGMENT_STOP event carrying the computed duration.
                    Returns 404 when no segment exists for the id, and 409 when the segment is not IN_PROGRESS.
                    """)
    @ApiResponse(responseCode = "200")
    public ResponseEntity<TravelSegmentResponse> stopTravelSegment(
            @PathVariable UUID travelSegmentId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Final travel details recorded when the segment ends.",
                            required = true,
                            content =
                                    @io.swagger.v3.oas.annotations.media.Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @io.swagger.v3.oas.annotations.media.ExampleObject(
                                                            name = "Arrived at customer",
                                                            value = """
                                                                    {"toLocationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a45"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    StopTravelSegmentRequest request) {
        return ResponseEntity.ok(travelSegmentService.stopTravelSegment(travelSegmentId, request));
    }

    @PostMapping("/submit/{mobileWorkAssignmentId}")
    @EmitEvent(id = "WORKORDER_TRAVEL_SEGMENT_SUBMIT", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.LABOR_ADD + "')")
    @Operation(operationId = "submitTravelSegments", summary = "Submit Travel Segments for Approval", description = """
                    Submits the calling technician's IN_PROGRESS and COMPLETED travel segments for a mobile work \
                    assignment, transitioning them all to SUBMITTED for downstream approval.
                    Use this tool at end of day when travel is ready for review; do not use stopTravelSegment, \
                    which ends a single running leg without submitting it.
                    Preconditions: the security-context username must parse as the technician's UUID, and at \
                    least one IN_PROGRESS or COMPLETED segment must exist for that technician on the assignment.
                    Required inputs: mobileWorkAssignmentId (UUID) as a path parameter and a body with workDate \
                    (ISO date); the body is validated but submission is keyed entirely on the path id and the \
                    caller's identity.
                    Emits a WORKORDER_TRAVEL_SEGMENT_SUBMIT event.
                    Returns 200 with the submitted segments, 400 when the caller's username is not a UUID, and \
                    404 when no submittable segments exist for the assignment.
                    """)
    @ApiResponse(responseCode = "200")
    public ResponseEntity<List<TravelSegmentResponse>> submitTravelSegments(
            @PathVariable UUID mobileWorkAssignmentId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Submission context for the day's travel segments.",
                            required = true,
                            content =
                                    @io.swagger.v3.oas.annotations.media.Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @io.swagger.v3.oas.annotations.media.ExampleObject(
                                                            name = "End of day",
                                                            value = """
                                                                    {"workDate":"2026-08-13","notes":"All site visits complete"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    SubmitTravelSegmentsRequest request) {
        return ResponseEntity.ok(travelSegmentService.submitTravelSegments(mobileWorkAssignmentId));
    }

    @PostMapping("/{travelSegmentId}/adjustments")
    @EmitEvent(id = "WORKORDER_TRAVEL_SEGMENT_ADJUSTMENT", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.LABOR_ADD + "')")
    @Operation(
            operationId = "createTravelSegmentAdjustment",
            summary = "Create Post-Approval Travel Adjustment",
            description = """
                    Creates an adjustment record against an APPROVED travel segment, capturing corrected start \
                    and end times and a reason while leaving the original segment untouched.
                    Use this tool when approved travel times need correction after the fact; do not use \
                    stopTravelSegment or submitTravelSegments, which drive the normal pre-approval lifecycle.
                    Preconditions: the segment must exist and be in APPROVED status — adjustments cannot be \
                    created for IN_PROGRESS, COMPLETED, or SUBMITTED segments.
                    Required inputs: travelSegmentId (UUID) as a path parameter and a body with a non-blank \
                    adjustmentReason; adjustedStartAt and adjustedEndAt (ISO instants) are optional.
                    Emits a WORKORDER_TRAVEL_SEGMENT_ADJUSTMENT event; the adjusting user is recorded from the \
                    security context.
                    Returns 201 with the adjustment, 404 when no segment exists for the id, and 409 when the \
                    segment is not APPROVED.
                    """)
    @ApiResponse(responseCode = "201")
    public ResponseEntity<TravelSegmentAdjustmentResponse> createAdjustment(
            @PathVariable UUID travelSegmentId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Corrected travel times and the reason for adjusting an approved segment.",
                            required = true,
                            content =
                                    @io.swagger.v3.oas.annotations.media.Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @io.swagger.v3.oas.annotations.media.ExampleObject(
                                                            name = "Trim end time",
                                                            value = """
                                                                    {"adjustedStartAt":"2026-08-13T13:00:00Z",
                                                                     "adjustedEndAt":"2026-08-13T13:35:00Z",
                                                                     "adjustmentReason":"Technician forgot to stop the segment at arrival"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    CreateTravelSegmentAdjustmentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(travelSegmentService.createAdjustment(travelSegmentId, request));
    }
}
