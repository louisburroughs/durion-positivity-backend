package com.positivity.location.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.location.internal.dto.TravelBufferPolicyRequest;
import com.positivity.location.internal.dto.TravelBufferPolicyResponse;
import com.positivity.location.internal.security.LocationPermissions;
import com.positivity.location.internal.service.TravelBufferPolicyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for travel buffer policy management.
 *
 * Issue: #76
 */
@Tag(name = "Travel Buffer Policy API", description = "Operations for managing travel buffer policies")
@RestController
@RequestMapping("/v1/travel-buffer-policies")
@RequiredArgsConstructor
public class TravelBufferPolicyController {

    private final TravelBufferPolicyService travelBufferPolicyService;

    @Operation(
            operationId = "createTravelBufferPolicy",
            summary = "Create a New Travel Buffer Policy",
            description = """
                    Creates a travel buffer policy that adds slack time around mobile unit travel for scheduling \
                    decisions.
                    Use this tool before assigning the policy to mobile units via createMobileUnit or \
                    patchMobileUnit; do not use patchTravelBufferPolicy, which edits an existing policy.
                    Preconditions: the name must not collide with an existing policy.
                    Required inputs: name and bufferType, one of FLAT_MINUTES, PERCENTAGE_OF_TRAVEL or \
                    DISTANCE_MULTIPLIER; bufferValue is optional, must be non-negative, and is interpreted \
                    according to the bufferType.
                    Emits a LOCATION_TRAVEL_BUFFER_POLICY_CREATE event.
                    Returns 201 with the created policy and 409 when the name is already taken.
                    """)
    @ApiResponse(responseCode = "201", description = "Travel buffer policy created")
    @ApiResponse(responseCode = "409", description = "Travel buffer policy name already taken")
    @EmitEvent(id = "LOCATION_TRAVEL_BUFFER_POLICY_CREATE", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + LocationPermissions.TRAVEL_BUFFER_POLICY_MANAGE + "')")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"location:travel-buffer-policy:manage"})
    @PostMapping
    public ResponseEntity<TravelBufferPolicyResponse> create(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Travel buffer policy to create, pairing a buffer type with its"
                                    + " numeric value.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Flat minutes buffer", value = """
                                                                    {"name":"Standard Metro Buffer",
                                                                     "bufferType":"FLAT_MINUTES",
                                                                     "bufferValue":30,
                                                                     "notes":"Applies during peak hours only"}
                                                                    """)))
                    @RequestBody
                    TravelBufferPolicyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(travelBufferPolicyService.create(request));
    }

    @Operation(operationId = "listTravelBufferPolicies", summary = "List All Travel Buffer Policies", description = """
                    Lists all travel buffer policies with their buffer type, value and notes.
                    Use this tool to discover policy ids for createMobileUnit or patchMobileUnit; use \
                    patchTravelBufferPolicy instead to change one.
                    Preconditions: none beyond the location:travel-buffer-policy:read authority.
                    Required inputs: none; there are no parameters, no paging and no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with the full unpaginated list.
                    """)
    @ApiResponse(responseCode = "200", description = "Travel buffer policies listed")
    @PreAuthorize("hasAuthority('" + LocationPermissions.TRAVEL_BUFFER_POLICY_READ + "')")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"location:travel-buffer-policy:read"})
    @GetMapping
    public ResponseEntity<List<TravelBufferPolicyResponse>> list() {
        return ResponseEntity.ok(travelBufferPolicyService.list());
    }

    @Operation(
            operationId = "patchTravelBufferPolicy",
            summary = "Patch Fields of Travel Buffer Policy",
            description = """
                    Applies a partial update to a travel buffer policy, accepting the keys bufferType, \
                    bufferValue and notes.
                    Use this tool to tune buffer behavior; do not use it to rename a policy, whose name is \
                    immutable after createTravelBufferPolicy.
                    Preconditions: the policy must exist; the resulting bufferType must remain one of \
                    FLAT_MINUTES, PERCENTAGE_OF_TRAVEL or DISTANCE_MULTIPLIER and the resulting bufferValue \
                    non-negative.
                    Required inputs: id (UUID) as a path parameter and a JSON object of the fields to change; \
                    keys other than bufferType, bufferValue and notes are silently ignored.
                    Emits a LOCATION_TRAVEL_BUFFER_POLICY_PATCH event.
                    Returns 400 when the id is not a valid UUID and 404 when no policy exists for it.
                    """)
    @ApiResponse(responseCode = "200", description = "Travel buffer policy patched")
    @ApiResponse(responseCode = "400", description = "Invalid travel buffer policy id")
    @ApiResponse(responseCode = "404", description = "Travel buffer policy not found")
    @PreAuthorize("hasAuthority('" + LocationPermissions.TRAVEL_BUFFER_POLICY_MANAGE + "')")
    @EmitEvent(id = "LOCATION_TRAVEL_BUFFER_POLICY_PATCH", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"location:travel-buffer-policy:manage"})
    @PatchMapping("/{id}")
    public ResponseEntity<TravelBufferPolicyResponse> patch(
            @PathVariable String id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Free-form patch object; only the keys bufferType, bufferValue and"
                                    + " notes are recognized.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Raise buffer value",
                                                            value = "{\"bufferValue\":45}")))
                    @RequestBody
                    Map<String, Object> patch) {
        return ResponseEntity.ok(travelBufferPolicyService.patch(id, patch));
    }
}
