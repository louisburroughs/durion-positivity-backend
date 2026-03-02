package com.positivity.workorder.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.workorder.internal.dto.OperationalContextOverrideRequest;
import com.positivity.workorder.internal.dto.OperationalContextResponse;
import com.positivity.workorder.internal.dto.WorkorderStartResponse;
import com.positivity.workorder.service.WorkorderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/workorders")
@RequiredArgsConstructor
@Tag(name = "Operational Context", description = "Workorder execution context operations")
public class OperationalContextController {

    private final WorkorderService workorderService;

    @GetMapping("/{workorderId}/operationalContext")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get operational context for workorder")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Operational context returned"),
            @ApiResponse(responseCode = "404", description = "Workorder not found")
    })
    public ResponseEntity<OperationalContextResponse> getOperationalContext(
            @PathVariable UUID workorderId) {
        return ResponseEntity.ok(workorderService.getOperationalContext(workorderId));
    }

    @PostMapping("/{workorderId}/operationalContext/override")
    @PreAuthorize("hasAuthority('workorder.operationalContext.override')")
    @EmitEvent(id = "WORKORDER_OPERATIONAL_CONTEXT_OVERRIDE", apiVersion = "1")
    @Operation(summary = "Manager override of operational context")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Override applied"),
            @ApiResponse(responseCode = "404", description = "Workorder not found"),
            @ApiResponse(responseCode = "409", description = "Context locked (work started)")
    })
    public ResponseEntity<OperationalContextResponse> overrideOperationalContext(
            @PathVariable UUID workorderId,
            @Valid @RequestBody OperationalContextOverrideRequest request) {
        return ResponseEntity.ok(workorderService.overrideOperationalContext(workorderId, request));
    }

    @PostMapping("/{workorderId}/start")
    @PreAuthorize("isAuthenticated()")
    @EmitEvent(id = "WORKORDER_START", apiVersion = "1")
    @Operation(summary = "Start work on workorder, locking operational context")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Work started, context locked"),
            @ApiResponse(responseCode = "404", description = "Workorder not found"),
            @ApiResponse(responseCode = "409", description = "Work already started")
    })
    public ResponseEntity<WorkorderStartResponse> startWork(@PathVariable UUID workorderId) {
        return ResponseEntity.ok(workorderService.startWork(workorderId));
    }
}