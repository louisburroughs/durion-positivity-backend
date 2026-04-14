package com.positivity.workorder.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.workorder.internal.dto.*;
import com.positivity.workorder.service.WorkorderPartUsageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for workorder parts usage tracking.
 *
 * CAP:005 Story #158 - Parts Usage Tracking
 *
 * Provides endpoints to issue, consume, and return parts on workorders,
 * as well as query usage history.
 */
@RestController
@RequestMapping("/v1/workorders/{workorderId}/parts")
@Tag(name = "Workorder Parts Usage", description = "Track parts issue, consumption, and returns")
public class WorkorderPartsUsageController {

    private final WorkorderPartUsageService usageService;

    public WorkorderPartsUsageController(WorkorderPartUsageService usageService) {
        this.usageService = usageService;
    }

    /**
     * Issue parts to a workorder.
     */
    @PostMapping("/issue")
    @PreAuthorize("hasAuthority('workorder:parts:add')")
    @EmitEvent(id = "WORKORDER_PART_ISSUE", apiVersion = "1")
    @Operation(
            summary = "Issue parts to workorder",
            description = "Issue parts from inventory, reserving them for consumption on the workorder")
    @ApiResponse(
            responseCode = "201",
            description = "Parts issued successfully",
            content = @Content(schema = @Schema(implementation = WorkorderPartUsageEventResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request (negative quantity, etc.)")
    @ApiResponse(responseCode = "404", description = "Workorder or part not found")
    @ApiResponse(responseCode = "409", description = "Idempotency conflict (duplicate key)")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Issue part request",
            required = true,
            content =
                    @Content(
                            schema = @Schema(implementation = IssuePartRequest.class),
                            examples =
                                    @ExampleObject(
                                            name = "issueParts",
                                            value =
                                                    "{\"workorderPartId\":\"550e8400-e29b-41d4-a716-446655440050\",\"quantity\":2}")))
    public ResponseEntity<WorkorderPartUsageEventResponse> issueParts(
            @PathVariable @NonNull UUID workorderId,
            @RequestBody @Valid @NonNull IssuePartRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) @Nullable String idempotencyKey) {

        var event = usageService.issuePartQuantity(
                workorderId, request.getWorkorderPartId(), request.getQuantity(), idempotencyKey);

        return ResponseEntity.status(HttpStatus.CREATED).body(WorkorderPartsUsageMapper.toResponse(event));
    }

    /**
     * Consume parts on a workorder.
     */
    @PostMapping("/consume")
    @PreAuthorize("hasAuthority('workorder:parts:add')")
    @EmitEvent(id = "WORKORDER_PART_CONSUME", apiVersion = "1")
    @Operation(
            summary = "Consume parts on workorder",
            description = "Record actual consumption of parts. Quantity consumed cannot exceed quantity issued.")
    @ApiResponse(
            responseCode = "201",
            description = "Parts consumption recorded successfully",
            content = @Content(schema = @Schema(implementation = WorkorderPartUsageEventResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request (exceeds issued quantity, etc.)")
    @ApiResponse(responseCode = "404", description = "Workorder or part not found")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Consume part request",
            required = true,
            content =
                    @Content(
                            schema = @Schema(implementation = ConsumePartRequest.class),
                            examples =
                                    @ExampleObject(
                                            name = "consumeParts",
                                            value =
                                                    "{\"workorderPartId\":\"550e8400-e29b-41d4-a716-446655440050\",\"quantity\":1}")))
    public ResponseEntity<WorkorderPartUsageEventResponse> consumeParts(
            @PathVariable @NonNull UUID workorderId,
            @RequestBody @Valid @NonNull ConsumePartRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) @Nullable String idempotencyKey) {

        try {
            var event = usageService.consumePartQuantity(
                    workorderId, request.getWorkorderPartId(), request.getQuantity(), idempotencyKey);

            return ResponseEntity.status(HttpStatus.CREATED).body(WorkorderPartsUsageMapper.toResponse(event));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Return unused parts to inventory.
     */
    @PostMapping("/return")
    @PreAuthorize("hasAuthority('workorder:parts:add')")
    @EmitEvent(id = "WORKORDER_PART_RETURN", apiVersion = "1")
    @Operation(
            summary = "Return unused parts to inventory",
            description = "Return unused parts after partial consumption or service completion")
    @ApiResponse(
            responseCode = "201",
            description = "Parts returned successfully",
            content = @Content(schema = @Schema(implementation = WorkorderPartUsageEventResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request (exceeds available quantity, etc.)")
    @ApiResponse(responseCode = "404", description = "Workorder or part not found")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Return part request",
            required = true,
            content =
                    @Content(
                            schema = @Schema(implementation = ReturnPartRequest.class),
                            examples =
                                    @ExampleObject(
                                            name = "returnParts",
                                            value =
                                                    "{\"workorderPartId\":\"550e8400-e29b-41d4-a716-446655440050\",\"quantity\":1}")))
    public ResponseEntity<WorkorderPartUsageEventResponse> returnParts(
            @PathVariable @NonNull UUID workorderId,
            @RequestBody @Valid @NonNull ReturnPartRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) @Nullable String idempotencyKey) {

        try {
            var event = usageService.returnPartQuantity(
                    workorderId, request.getWorkorderPartId(), request.getQuantity(), idempotencyKey);

            return ResponseEntity.status(HttpStatus.CREATED).body(WorkorderPartsUsageMapper.toResponse(event));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Get usage history for all parts on a workorder, or a specific part if
     * partLineId is provided.
     */
    @GetMapping("/usageHistory")
    @PreAuthorize("hasAuthority('workorder:parts:view')")
    @Operation(
            summary = "Get parts usage history",
            description = "Retrieve usage history (issue, consume, return events) for parts on the workorder")
    @ApiResponse(
            responseCode = "200",
            description = "Usage history retrieved successfully",
            content = @Content(schema = @Schema(implementation = WorkorderPartUsageEventResponse.class)))
    @ApiResponse(responseCode = "404", description = "Workorder or part not found")
    public ResponseEntity<List<WorkorderPartUsageEventResponse>> getUsageHistory(
            @PathVariable @NonNull UUID workorderId,
            @RequestParam(required = false)
                    @Nullable
                    @Parameter(
                            description = "Optional part line ID to filter history for a specific part",
                            example = "550e8400-e29b-41d4-a716-446655440050")
                    UUID partLineId) {

        List<WorkorderPartUsageEventResponse> responses;
        if (partLineId != null) {
            responses = usageService.getUsageHistory(workorderId, partLineId);
        } else {
            responses = usageService.getAllUsageHistory(workorderId);
        }

        return ResponseEntity.ok(responses);
    }
}
