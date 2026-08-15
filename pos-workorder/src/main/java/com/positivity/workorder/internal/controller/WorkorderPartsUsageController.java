package com.positivity.workorder.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.workorder.internal.dto.*;
import com.positivity.workorder.internal.security.WorkorderPermissions;
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
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:parts:add"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.PARTS_ADD + "')")
    @EmitEvent(id = "WORKORDER_PART_ISSUE", apiVersion = "1")
    @Operation(operationId = "issueParts", summary = "Issue Parts to Workorder", description = """
                    Issues a quantity of a part line to the workorder, incrementing the line's quantityIssued and \
                    recording an ISSUE usage event with the acting user.
                    Use this tool when stock is handed to the job; do not use consumeParts, which records actual \
                    installation against previously issued quantity.
                    Preconditions: the workorder and part line must exist, the part must belong to the \
                    workorder, and the caller must have an authenticated username.
                    Required inputs: workorderId (UUID) as a path parameter, plus workorderPartId (UUID) and a \
                    positive quantity in the body; an Idempotency-Key header makes retries return the original \
                    usage event.
                    Emits a WORKORDER_PART_ISSUE event and marks the workorder fact changed for downstream \
                    replication.
                    Returns 201 with the ISSUE event, 400 when the quantity is not positive or the part cannot \
                    be found, 404 when the workorder does not exist, and 409 when the part belongs to a \
                    different workorder.
                    """)
    @ApiResponse(
            responseCode = "201",
            description = "Parts issued successfully",
            content = @Content(schema = @Schema(implementation = WorkorderPartUsageEventResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request (negative quantity, etc.)")
    @ApiResponse(responseCode = "404", description = "Workorder or part not found")
    @ApiResponse(responseCode = "409", description = "Idempotency conflict (duplicate key)")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Part line and quantity being issued from inventory to the job.",
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
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:parts:add"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.PARTS_ADD + "')")
    @EmitEvent(id = "WORKORDER_PART_CONSUME", apiVersion = "1")
    @Operation(operationId = "consumeParts", summary = "Record Part Consumption on Workorder", description = """
                    Records actual consumption of an issued part, incrementing the line's quantityConsumed and \
                    writing a CONSUME usage event.
                    Use this tool when a part is actually installed on the vehicle; do not use issueParts, which \
                    only reserves quantity, or returnParts, which sends unused quantity back.
                    Preconditions: the part must belong to the workorder and cumulative consumption must not \
                    exceed the quantity already issued.
                    Required inputs: workorderId (UUID) as a path parameter, plus workorderPartId (UUID) and a \
                    positive quantity in the body; an Idempotency-Key header makes retries return the original \
                    usage event.
                    Emits a WORKORDER_PART_CONSUME event and marks the workorder fact changed.
                    Returns 201 with the CONSUME event, 400 when the quantity is not positive, exceeds the \
                    issued quantity, or the part cannot be found, 404 when the workorder does not exist, and \
                    409 when the part belongs to a different workorder.
                    """)
    @ApiResponse(
            responseCode = "201",
            description = "Parts consumption recorded successfully",
            content = @Content(schema = @Schema(implementation = WorkorderPartUsageEventResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request (exceeds issued quantity, etc.)")
    @ApiResponse(responseCode = "404", description = "Workorder or part not found")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Part line and quantity actually consumed on the job.",
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
        } catch (IllegalArgumentException _) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Return unused parts to inventory.
     */
    @PostMapping("/return")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:parts:add"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.PARTS_ADD + "')")
    @EmitEvent(id = "WORKORDER_PART_RETURN", apiVersion = "1")
    @Operation(operationId = "returnParts", summary = "Return Unused Parts to Inventory", description = """
                    Returns unused issued quantity of a part line to inventory, incrementing quantityReturned \
                    and writing a RETURN usage event.
                    Use this tool for the normal return of leftover stock after partial consumption; do not use \
                    returnUnusedPartQuantity, which is the adjustment-flow return that also records a reason.
                    Preconditions: the part must belong to the workorder, and the return cannot exceed the \
                    available quantity — issued minus consumed minus already returned.
                    Required inputs: workorderId (UUID) as a path parameter, plus workorderPartId (UUID) and a \
                    positive quantity in the body; an Idempotency-Key header makes retries return the original \
                    usage event.
                    Emits a WORKORDER_PART_RETURN event and marks the workorder fact changed.
                    Returns 201 with the RETURN event, 400 when the quantity is not positive, exceeds the \
                    available quantity, or the part cannot be found, 404 when the workorder does not exist, \
                    and 409 when the part belongs to a different workorder.
                    """)
    @ApiResponse(
            responseCode = "201",
            description = "Parts returned successfully",
            content = @Content(schema = @Schema(implementation = WorkorderPartUsageEventResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request (exceeds available quantity, etc.)")
    @ApiResponse(responseCode = "404", description = "Workorder or part not found")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Part line and unused quantity going back to inventory.",
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
        } catch (IllegalArgumentException _) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Get usage history for all parts on a workorder, or a specific part if
     * partLineId is provided.
     */
    @GetMapping("/usageHistory")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:parts:view"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.PARTS_VIEW + "')")
    @Operation(operationId = "getPartsUsageHistory", summary = "Get Parts Usage History", description = """
                    Returns the ISSUE, CONSUME, and RETURN usage events for a workorder's parts, either for the \
                    whole workorder or filtered to one part line.
                    Use this tool when auditing how part quantities moved; use getPartAdjustmentHistory instead \
                    for substitutions, corrections, and reasoned returns from the adjustment flow.
                    Preconditions: when partLineId is supplied, the part must belong to the workorder.
                    Required inputs: workorderId (UUID) as a path parameter; partLineId (UUID) is an optional \
                    query filter.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with the events, 400 when the filtered part cannot be found, 404 when the \
                    workorder does not exist, and 409 when the filtered part belongs to a different workorder.
                    """)
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
