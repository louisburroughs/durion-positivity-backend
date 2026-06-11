package com.positivity.shopmanager.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.shopmanager.service.AssignmentService;
import com.positivity.shopmanager.service.dto.AssignmentResponse;
import com.positivity.shopmanager.service.dto.CreateAssignmentRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/appointments/{appointmentId}/assignments")
@RequiredArgsConstructor
@Tag(
        name = "Appointment Assignments",
        description = "Create and list technician and resource assignments for an appointment")
public class AssignmentController {

    private final AssignmentService assignmentService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"shop:bay:assign"})
    @PreAuthorize("hasAuthority('shop:bay:assign')")
    @EmitEvent(id = "SHOPMGR_ASSIGNMENT_CREATED", apiVersion = "1")
    @Operation(
            summary = "Create appointment assignments",
            description =
                    "Creates assignments for the specified appointment using the requested mechanics or shop resources.")
    @ApiResponse(
            responseCode = "201",
            description = "Assignments created",
            content = @Content(schema = @Schema(implementation = AssignmentResponse.class)))
    @ApiResponse(responseCode = "403", description = "Forbidden")
    public @NonNull AssignmentResponse createAssignment(
            @Parameter(description = "Appointment identifier", required = true) @PathVariable UUID appointmentId,
            @RequestBody @NonNull CreateAssignmentRequest request) {
        var augmented = CreateAssignmentRequest.builder()
                .appointmentId(appointmentId)
                .mechanics(request.getMechanics())
                .resourceId(request.getResourceId())
                .resourceType(request.getResourceType())
                .override(request.isOverride())
                .overrideReason(request.getOverrideReason())
                .build();
        return assignmentService.create(augmented);
    }

    @GetMapping
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"appointments:view", "shop:schedule:view"})
    @PreAuthorize("hasAnyAuthority('appointments:view', 'shop:schedule:view')")
    @EmitEvent(id = "SHOPMGR_ASSIGNMENT_LIST_FETCHED", apiVersion = "1")
    @Operation(
            summary = "List appointment assignments",
            description = "Returns the current assignments associated with the specified appointment.")
    @ApiResponse(
            responseCode = "200",
            description = "Assignments returned",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = AssignmentResponse.class))))
    @ApiResponse(responseCode = "403", description = "Forbidden")
    public @NonNull List<AssignmentResponse> listAssignments(
            @Parameter(description = "Appointment identifier", required = true) @PathVariable UUID appointmentId) {
        return assignmentService.getByAppointmentId(appointmentId);
    }
}
