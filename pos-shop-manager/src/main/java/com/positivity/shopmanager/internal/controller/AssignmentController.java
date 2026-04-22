package com.positivity.shopmanager.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.shopmanager.service.AssignmentService;
import com.positivity.shopmanager.service.dto.AssignmentResponse;
import com.positivity.shopmanager.service.dto.CreateAssignmentRequest;
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
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RequestMapping("/v1/appointments/{appointmentId}/assignments")
@RequiredArgsConstructor
public class AssignmentController {

    private final AssignmentService assignmentService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('shop:bay:assign')")
    @EmitEvent(id = "SHOPMGR_ASSIGNMENT_CREATED", apiVersion = "1")
    public @NonNull AssignmentResponse createAssignment(
            @PathVariable UUID appointmentId, @RequestBody @NonNull CreateAssignmentRequest request) {
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
    @PreAuthorize("hasAnyAuthority('appointments:view', 'shop:schedule:view')")
    @EmitEvent(id = "SHOPMGR_ASSIGNMENT_LIST_FETCHED", apiVersion = "1")
    public @NonNull List<AssignmentResponse> listAssignments(@PathVariable UUID appointmentId) {
        return assignmentService.getByAppointmentId(appointmentId);
    }
}
