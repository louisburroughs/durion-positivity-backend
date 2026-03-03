package com.positivity.workorder.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.workorder.internal.dto.CreateTravelSegmentAdjustmentRequest;
import com.positivity.workorder.internal.dto.StartTravelSegmentRequest;
import com.positivity.workorder.internal.dto.StopTravelSegmentRequest;
import com.positivity.workorder.internal.dto.SubmitTravelSegmentsRequest;
import com.positivity.workorder.internal.dto.TravelSegmentAdjustmentResponse;
import com.positivity.workorder.internal.dto.TravelSegmentMapper;
import com.positivity.workorder.internal.dto.TravelSegmentResponse;
import com.positivity.workorder.service.TravelSegmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Travel Segment API", description = "Endpoints for capturing mobile travel segments")
@RestController
@RequestMapping("/v1/workorders/travelSegments")
@RequiredArgsConstructor
public class TravelSegmentController {

    private final TravelSegmentService travelSegmentService;

    @PostMapping("/start")
    @EmitEvent(id = "WORKORDER_TRAVEL_SEGMENT_START", apiVersion = "1")
    @PreAuthorize("hasAuthority('workorder:labor:add')")
    @Operation(summary = "Start a travel segment")
    @ApiResponse(responseCode = "201")
    public ResponseEntity<TravelSegmentResponse> startTravelSegment(
            @Valid @RequestBody StartTravelSegmentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(TravelSegmentMapper.toResponse(travelSegmentService.startTravelSegment(request)));
    }

    @PostMapping("/{travelSegmentId}/stop")
    @EmitEvent(id = "WORKORDER_TRAVEL_SEGMENT_STOP", apiVersion = "1")
    @PreAuthorize("hasAuthority('workorder:labor:add')")
    @Operation(summary = "Stop a travel segment")
    @ApiResponse(responseCode = "200")
    public ResponseEntity<TravelSegmentResponse> stopTravelSegment(
            @PathVariable UUID travelSegmentId,
            @Valid @RequestBody StopTravelSegmentRequest request) {
        return ResponseEntity
                .ok(TravelSegmentMapper.toResponse(travelSegmentService.stopTravelSegment(travelSegmentId, request)));
    }

    @PostMapping("/submit/{mobileWorkAssignmentId}")
    @EmitEvent(id = "WORKORDER_TRAVEL_SEGMENT_SUBMIT", apiVersion = "1")
    @PreAuthorize("hasAuthority('workorder:labor:add')")
    @Operation(summary = "Submit travel segments for a mobile work assignment")
    @ApiResponse(responseCode = "200")
    public ResponseEntity<TravelSegmentResponse> submitTravelSegments(
            @PathVariable UUID mobileWorkAssignmentId,
            @Valid @RequestBody SubmitTravelSegmentsRequest request) {
        String username = SecurityContextHelper.getCurrentUsernameOrDefault("system");
        UUID technicianId;
        try {
            technicianId = UUID.fromString(username);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(TravelSegmentMapper.toResponse(
                travelSegmentService.submitTravelSegments(mobileWorkAssignmentId, technicianId)));
    }

    @PostMapping("/{travelSegmentId}/adjustments")
    @EmitEvent(id = "WORKORDER_TRAVEL_SEGMENT_ADJUSTMENT", apiVersion = "1")
    @PreAuthorize("hasAuthority('workorder:labor:add')")
    @Operation(summary = "Create a post-approval adjustment for a travel segment")
    @ApiResponse(responseCode = "201")
    public ResponseEntity<TravelSegmentAdjustmentResponse> createAdjustment(
            @PathVariable UUID travelSegmentId,
            @Valid @RequestBody CreateTravelSegmentAdjustmentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(TravelSegmentMapper
                        .toAdjustmentResponse(travelSegmentService.createAdjustment(travelSegmentId, request)));
    }
}
