package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.returns.ReasonCodeDto;
import com.positivity.inventory.internal.dto.returns.ReturnSubmissionResultDto;
import com.positivity.inventory.internal.dto.returns.ReturnSubmitRequest;
import com.positivity.inventory.internal.dto.returns.ReturnableItemDto;
import com.positivity.inventory.service.ReturnService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/inventory/returns")
@RequiredArgsConstructor
@Tag(name = "Returns", description = "Inventory return-to-stock endpoints")
public class ReturnController {

    private final ReturnService returnService;

    @GetMapping("/returnable-items")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:return:view"})
    @PreAuthorize("hasAuthority('inventory:return:view')")
    @Operation(
            operationId = "listReturnableItems",
            summary = "List Returnable Items",
            description = """
                    Returns the items eligible to be returned to stock for a workorder, with the quantity still \
                    returnable per item.
                    Use this tool to build a return before submitReturnToStock; use listReturnReasonCodes instead \
                    for the reason codes a return line must carry.
                    Preconditions: none checked; the current implementation is a placeholder that echoes a single \
                    stub item with quantityReturnable 0 for any workorderId until a returnable-item \
                    source-of-record exists.
                    Required inputs: workorderId (UUID) as a query parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 400 when workorderId is missing or not a valid UUID.
                    """,
            tags = {"Returns"})
    @ApiResponse(
            responseCode = "200",
            description = "Returnable items returned",
            content =
                    @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = ReturnableItemDto.class))))
    @ApiResponse(
            responseCode = "400",
            description = "Validation failure",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required return view authority",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<List<ReturnableItemDto>> listReturnableItems(@RequestParam UUID workorderId) {
        return ResponseEntity.ok(returnService.listReturnableItems(workorderId));
    }

    @GetMapping("/reason-codes")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:return:view"})
    @PreAuthorize("hasAuthority('inventory:return:view')")
    @Operation(
            operationId = "listReturnReasonCodes",
            summary = "List Return Reason Codes",
            description = """
                    Returns the fixed catalog of return reason codes: DAMAGED, WRONG_ITEM, EXCESS and DEFECTIVE, \
                    each with a description and category.
                    Use this tool to populate the reasonCode of return lines before submitReturnToStock; do not \
                    use listReturnableItems, which lists what can be returned rather than why.
                    Preconditions: none; the list is static in the service and takes no filters.
                    Required inputs: none; there are no parameters and no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with the full code list on every call, so callers can cache it per session.
                    """,
            tags = {"Returns"})
    @ApiResponse(
            responseCode = "200",
            description = "Return reason codes returned",
            content =
                    @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = ReasonCodeDto.class))))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required return view authority",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<List<ReasonCodeDto>> listReturnReasonCodes() {
        return ResponseEntity.ok(returnService.listReturnReasonCodes());
    }

    @PostMapping("/submit-to-stock")
    @EmitEvent(id = "INVENTORY_RETURN_SUBMIT_TO_STOCK", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:return:write"})
    @PreAuthorize("hasAuthority('inventory:return:write')")
    @Operation(
            operationId = "submitReturnToStock",
            summary = "Submit Return To Stock",
            description = """
                    Accepts a return-to-stock submission for a workorder and acknowledges it with a generated \
                    returnId, the processed line count and a SUBMITTED status.
                    Use this tool to hand back unused workorder parts; do not use receiveItemsIntoStaging or \
                    createGoodsReceipt, which receive vendor shipments rather than workorder returns.
                    Preconditions: none checked by the current implementation; the call is an acknowledgement \
                    stub, so no return record is persisted, no ledger entry posts and on-hand stock does not \
                    change yet.
                    Required inputs: workorderId (UUID) and lines (non-empty), each naming itemId (UUID), a \
                    positive quantity, a reasonCode from listReturnReasonCodes and a locationId; \
                    storageLocationId is optional.
                    Emits an INVENTORY_RETURN_SUBMIT_TO_STOCK event; the 202 response signals acceptance of the \
                    submission, not completed stock movement.
                    Returns 400 when workorderId is missing, lines is empty, a quantity is not positive or a \
                    reasonCode is blank.
                    """,
            tags = {"Returns"})
    @ApiResponse(
            responseCode = "202",
            description = "Return submitted and accepted",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ReturnSubmissionResultDto.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Validation failure",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required return write authority",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "Return submission violates business policy",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<ReturnSubmissionResultDto> submitToStock(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The workorder and the return lines being handed back to stock.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = ReturnSubmitRequest.class),
                                            examples = @ExampleObject(name = "Damaged part return", value = """
                                                                    {"workorderId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a50",
                                                                     "lines":[{"itemId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a51",
                                                                       "quantity":3,"reasonCode":"DAMAGED",
                                                                       "locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a52"}]}
                                                                    """)))
                    @Valid
                    @RequestBody
                    ReturnSubmitRequest request) {
        ReturnSubmissionResultDto response = returnService.submitToStock(request);
        return ResponseEntity.accepted().body(response);
    }
}
