package com.positivity.workorder.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.workorder.internal.dto.*;
import com.positivity.workorder.internal.security.WorkorderPermissions;
import com.positivity.workorder.internal.service.WorkorderPartAdjustmentService;
import com.positivity.workorder.internal.service.WorkorderSubstitutionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for workorder part adjustments (substitutions, returns,
 * corrections).
 *
 * CAP:005 Story #157 - Handle Part Substitutions and Returns
 *
 * Provides endpoints to:
 * - Substitute parts (preserving original for audit history)
 * - Return unused part quantities
 * - Correct part quantities (administrative corrections)
 * - Query adjustment history
 */
@RestController
@RequestMapping("/v1/workorders/{workorderId}/parts")
@Tag(name = "Workorder Part Adjustments", description = "Part substitutions, returns, and corrections")
public class WorkorderPartAdjustmentController {

    private final WorkorderPartAdjustmentService adjustmentService;
    private final WorkorderSubstitutionService substitutionService;

    public WorkorderPartAdjustmentController(
            WorkorderPartAdjustmentService adjustmentService, WorkorderSubstitutionService substitutionService) {
        this.adjustmentService = adjustmentService;
        this.substitutionService = substitutionService;
    }

    /**
     * Substitute one part for another.
     */
    @PostMapping("/substitute")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:parts:add"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.PARTS_ADD + "')")
    @EmitEvent(id = "WORKORDER_PART_SUBSTITUTE", apiVersion = "1")
    @Operation(operationId = "substitutePart", summary = "Substitute Part on Workorder", description = """
                    Substitutes one part line with a different part, preserving the original line for audit \
                    history, capturing a pricing snapshot, and recording a substitution adjustment event.
                    Use this tool when the planned part is replaced by an alternative; use \
                    suggestWorkorderSubstitutes first to find candidate parts, and do not use \
                    correctPartQuantity, which fixes quantities rather than parts.
                    Preconditions: the workorder and original part must exist, the original part must belong to \
                    the workorder and must not have any consumed quantity, and the substitute must differ from \
                    the original.
                    Required inputs: workorderId (UUID) as a path parameter, plus originalPartId and \
                    substitutePartId (UUIDs) and a reason in the body; notes are optional and an Idempotency-Key \
                    header deduplicates retries.
                    Emits a WORKORDER_PART_SUBSTITUTE event.
                    Returns 201 with the adjustment event, 404 when the workorder or part cannot be found, and \
                    400 when the part is already consumed, does not belong to the workorder, or the substitute \
                    equals the original.
                    """)
    @ApiResponse(
            responseCode = "201",
            description = "Part substituted successfully",
            content = @Content(schema = @Schema(implementation = WorkorderPartAdjustmentEventResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request (part already consumed, etc.)")
    @ApiResponse(responseCode = "404", description = "Workorder or part not found")
    @ApiResponse(responseCode = "409", description = "Idempotency conflict")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Original part, replacement part, and the reason for the swap.",
            required = true,
            content =
                    @Content(
                            schema = @Schema(implementation = SubstitutePartRequest.class),
                            examples =
                                    @ExampleObject(
                                            name = "substitutePart",
                                            value =
                                                    "{\"originalPartId\":\"550e8400-e29b-41d4-a716-446655440050\",\"substitutePartId\":\"550e8400-e29b-41d4-a716-446655440051\",\"reason\":\"Supplier substitution\",\"notes\":\"Equivalent OEM part\"}")))
    public ResponseEntity<WorkorderPartAdjustmentEventResponse> substitutePart(
            @PathVariable @NonNull UUID workorderId,
            @RequestBody @Valid @NonNull SubstitutePartRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) @Nullable String idempotencyKey) {

        try {
            // Issue #49: Delegate substitute flow to dedicated substitution service.
            WorkorderPartAdjustmentEventResponse response = substitutionService.substitutePartLine(
                    workorderId,
                    request.getOriginalPartId(),
                    request.getSubstitutePartId(),
                    request.getReason(),
                    idempotencyKey,
                    request.getNotes());

            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (NoSuchElementException _) {
            return ResponseEntity.notFound().build();
        } catch (IllegalArgumentException | IllegalStateException _) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Return unused part quantity.
     */
    @PostMapping("/returnUnused")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:parts:add"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.PARTS_ADD + "')")
    @EmitEvent(id = "WORKORDER_PART_RETURN_UNUSED", apiVersion = "1")
    @Operation(operationId = "returnUnusedPartQuantity", summary = "Return Unused Part Quantity", description = """
                    Returns unused part quantity through the adjustment flow, recording a reasoned return \
                    adjustment event alongside the quantity change.
                    Use this tool when a return needs an explicit reason and audit trail; use returnParts \
                    instead for the plain usage-flow return without a reason.
                    Preconditions: the workorder and part must exist, the part must belong to the workorder, and \
                    the return cannot exceed the quantity still available on the line.
                    Required inputs: workorderId (UUID) as a path parameter, plus workorderPartId (UUID), a \
                    positive quantity, and a reason in the body; notes are optional and an Idempotency-Key \
                    header deduplicates retries.
                    Emits a WORKORDER_PART_RETURN_UNUSED event.
                    Returns 201 with the adjustment event, 404 when the workorder or part cannot be found, and \
                    400 when the quantity is not positive, exceeds the available quantity, or the part does not \
                    belong to the workorder.
                    """)
    @ApiResponse(
            responseCode = "201",
            description = "Unused quantity returned successfully",
            content = @Content(schema = @Schema(implementation = WorkorderPartAdjustmentEventResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request (exceeds available quantity, etc.)")
    @ApiResponse(responseCode = "404", description = "Workorder or part not found")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Part line, quantity going back, and the reason for the reasoned return.",
            required = true,
            content =
                    @Content(
                            schema = @Schema(implementation = ReturnPartQuantityRequest.class),
                            examples =
                                    @ExampleObject(
                                            name = "returnUnused",
                                            value =
                                                    "{\"workorderPartId\":\"550e8400-e29b-41d4-a716-446655440050\",\"quantity\":1,\"reason\":\"Unused after repair\",\"notes\":\"Returned to stock\"}")))
    public ResponseEntity<WorkorderPartAdjustmentEventResponse> returnUnusedQuantity(
            @PathVariable @NonNull UUID workorderId,
            @RequestBody @Valid @NonNull ReturnPartQuantityRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) @Nullable String idempotencyKey) {

        try {
            WorkorderPartAdjustmentEventResponse response = adjustmentService.returnUnusedQuantity(
                    workorderId,
                    request.getWorkorderPartId(),
                    request.getQuantity(),
                    request.getReason(),
                    idempotencyKey,
                    request.getNotes());

            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (NoSuchElementException _) {
            return ResponseEntity.notFound().build();
        } catch (IllegalArgumentException | IllegalStateException _) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Correct part quantity (administrative correction).
     */
    @PostMapping("/correct")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:parts:add"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.PARTS_ADD + "')")
    @EmitEvent(id = "WORKORDER_PART_CORRECT", apiVersion = "1")
    @Operation(operationId = "correctPartQuantity", summary = "Correct Part Quantity", description = """
                    Applies an administrative correction to a part line's quantity, recording the correction and \
                    its reason as an adjustment event for data-entry fixes.
                    Use this tool only to repair mistaken quantities; do not use consumeParts or returnParts, \
                    which record real physical movement of stock.
                    Preconditions: the workorder and part must exist and the part must belong to the workorder.
                    Required inputs: workorderId (UUID) as a path parameter, plus workorderPartId (UUID), a \
                    positive newQuantity, and a reason in the body; notes and uomCode are optional (uomCode is \
                    the unit newQuantity is expressed in; omit to leave the part's existing unit unchanged) and \
                    an Idempotency-Key header deduplicates retries.
                    Emits a WORKORDER_PART_CORRECT event.
                    Returns 201 with the adjustment event, 404 when the workorder or part cannot be found, 400 \
                    when newQuantity is not positive or the part does not belong to the workorder, and 422 when \
                    uomCode has no conversion row for the product or the converted quantity exceeds its \
                    declared decimal scale.
                    """)
    @ApiResponse(
            responseCode = "201",
            description = "Part quantity corrected successfully",
            content = @Content(schema = @Schema(implementation = WorkorderPartAdjustmentEventResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request")
    @ApiResponse(responseCode = "404", description = "Workorder or part not found")
    @ApiResponse(
            responseCode = "422",
            description = "uomCode has no conversion row for the product (UOM_CONVERSION_UNDEFINED), or the "
                    + "converted quantity exceeds the product's declared decimal scale "
                    + "(FRACTIONAL_QUANTITY_NOT_ALLOWED)")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Part line, corrected quantity, and the reason for the administrative fix.",
            required = true,
            content =
                    @Content(
                            schema = @Schema(implementation = CorrectPartQuantityRequest.class),
                            examples =
                                    @ExampleObject(
                                            name = "correctPartQuantity",
                                            value =
                                                    "{\"workorderPartId\":\"550e8400-e29b-41d4-a716-446655440050\",\"newQuantity\":3,\"reason\":\"Data correction\",\"notes\":\"Corrected after inventory count\"}")))
    public ResponseEntity<WorkorderPartAdjustmentEventResponse> correctPartQuantity(
            @PathVariable @NonNull UUID workorderId,
            @RequestBody @Valid @NonNull CorrectPartQuantityRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) @Nullable String idempotencyKey) {

        try {
            WorkorderPartAdjustmentEventResponse response = adjustmentService.correctPartQuantity(
                    workorderId,
                    request.getWorkorderPartId(),
                    request.getNewQuantity(),
                    request.getUomCode(),
                    request.getReason(),
                    idempotencyKey,
                    request.getNotes());

            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (NoSuchElementException _) {
            return ResponseEntity.notFound().build();
        } catch (IllegalArgumentException | IllegalStateException _) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Get adjustment history for all parts on a workorder, or a specific part if
     * partId is provided.
     */
    @GetMapping("/adjustments")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:parts:view"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.PARTS_VIEW + "')")
    @Operation(operationId = "getPartAdjustmentHistory", summary = "Get Part Adjustment History", description = """
                    Returns the substitution, reasoned-return, and correction adjustment events for a \
                    workorder's parts, newest first, either for the whole workorder or filtered to one part.
                    Use this tool when auditing part swaps and corrections; use getPartsUsageHistory instead for \
                    the plain issue, consume, and return quantity movements.
                    Preconditions: none — an unknown workorder or part simply yields an empty list, and the \
                    partId filter is not validated against the workorder in the path.
                    Required inputs: workorderId (UUID) as a path parameter; partId (UUID) is an optional query \
                    filter.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with the adjustment events, possibly empty.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "Adjustment history retrieved successfully (newest first)",
            content =
                    @Content(
                            array =
                                    @ArraySchema(
                                            schema =
                                                    @Schema(
                                                            implementation =
                                                                    WorkorderPartAdjustmentEventResponse.class))))
    @ApiResponse(responseCode = "404", description = "Workorder or part not found")
    public ResponseEntity<List<WorkorderPartAdjustmentEventResponse>> getAdjustmentHistory(
            @PathVariable @NonNull UUID workorderId,
            @RequestParam(required = false)
                    @Nullable
                    @Parameter(
                            description = "Optional part ID to filter history for a specific part",
                            example = "550e8400-e29b-41d4-a716-446655440050")
                    UUID partId) {

        List<WorkorderPartAdjustmentEventResponse> responses;
        if (partId != null) {
            responses = adjustmentService.getPartAdjustmentHistory(partId);
        } else {
            responses = adjustmentService.getAdjustmentHistory(workorderId);
        }

        return ResponseEntity.ok(responses);
    }
}
