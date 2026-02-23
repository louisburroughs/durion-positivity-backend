package com.positivity.location.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.location.internal.dto.TravelBufferPolicyRequest;
import com.positivity.location.internal.dto.TravelBufferPolicyResponse;
import com.positivity.location.service.TravelBufferPolicyService;
import java.util.List;
import java.util.Map;
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

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

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

    @Operation(summary = "Create travel buffer policy")
    @ApiResponse(responseCode = "201", description = "Travel buffer policy created")
    @EmitEvent(id = "LOCATION_TRAVEL_BUFFER_POLICY_CREATE", apiVersion = "1")
    @PreAuthorize("hasAuthority('location.travel-buffer-policy.manage')")
    @PostMapping
    public ResponseEntity<TravelBufferPolicyResponse> create(@RequestBody TravelBufferPolicyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(travelBufferPolicyService.create(request));
    }

    @Operation(summary = "List travel buffer policies")
    @ApiResponse(responseCode = "200", description = "Travel buffer policies listed")
    @PreAuthorize("hasAuthority('location.travel-buffer-policy.read')")
    @GetMapping
    public ResponseEntity<List<TravelBufferPolicyResponse>> list() {
        return ResponseEntity.ok(travelBufferPolicyService.list());
    }

    @Operation(summary = "Patch travel buffer policy")
    @ApiResponse(responseCode = "200", description = "Travel buffer policy patched")
    @ApiResponse(responseCode = "400", description = "Invalid travel buffer policy id")
    @ApiResponse(responseCode = "404", description = "Travel buffer policy not found")
    @PreAuthorize("hasAuthority('location.travel-buffer-policy.manage')")
    @PatchMapping("/{id}")
    public ResponseEntity<TravelBufferPolicyResponse> patch(
            @PathVariable String id,
            @RequestBody Map<String, Object> patch) {
        return ResponseEntity.ok(travelBufferPolicyService.patch(id, patch));
    }
}
