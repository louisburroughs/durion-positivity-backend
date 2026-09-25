package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.returns.ReasonCodeDto;
import com.positivity.inventory.internal.dto.returns.ReturnLineDto;
import com.positivity.inventory.internal.dto.returns.ReturnSubmissionResultDto;
import com.positivity.inventory.internal.dto.returns.ReturnSubmitRequest;
import com.positivity.inventory.internal.dto.returns.ReturnableItemDto;
import com.positivity.inventory.internal.receiving.service.ReturnService;
import com.positivity.inventory.internal.security.InventoryPermissionRegistry;
import com.positivity.security.common.LocationScope;
import com.positivity.security.common.SecurityContextHelper;
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
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.RETURN_VIEW + "')")
    @Operation(
            operationId = "listReturnableItems",
            summary = "List Returnable Items",
            description = """
                    Returns the items eligible to be returned to stock for a workorder, one row per work order \
                    part line, with the quantity still returnable (parts consumed against the line minus parts \
                    already returned against it, floored at 0).
                    Use this tool to build a return before submitReturnToStock; use listReturnReasonCodes instead \
                    for the reason codes a return line must carry. itemId and workorderLineId name the same work \
                    order line; submitReturnToStock's lines key off that id.
                    Preconditions: none; an unknown or partless workorderId yields an empty array.
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
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.RETURN_VIEW + "')")
    @Operation(
            operationId = "listReturnReasonCodes",
            summary = "List Return Reason Codes",
            description = """
                    Returns the fixed catalog of return reason codes: NOT_NEEDED, WRONG_PART and CUSTOMER_REFUSED \
                    (CAP-218 Story #177), each with a description and category.
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
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.RETURN_WRITE + "')")
    @Operation(
            operationId = "submitReturnToStock",
            summary = "Submit Return To Stock",
            description = """
                    Posts a return-to-stock submission for a workorder: persists the return record and its \
                    lines, posts a RETURN_TO_STOCK ledger entry per line (carrying the workorder and work order \
                    line), and acknowledges with a generated returnId, the processed line count and a SUBMITTED \
                    status.
                    Use this tool to hand back unused workorder parts; do not use receiveItemsIntoStaging or \
                    createGoodsReceipt, which receive vendor shipments rather than workorder returns.
                    Preconditions: each line's itemId must name a real work order part line, and its quantity may \
                    not exceed that line's returnable quantity (parts consumed minus parts already returned).
                    Required inputs: workorderId (UUID) and lines (non-empty), each naming itemId (UUID, the work \
                    order line), a positive quantity, a reasonCode from the closed set NOT_NEEDED, WRONG_PART or \
                    CUSTOMER_REFUSED, and a locationId; storageLocationId is optional.
                    Emits an INVENTORY_RETURN_SUBMIT_TO_STOCK event; the 202 response signals the posting completed.
                    Returns 400 when workorderId is missing, lines is empty, a quantity is not positive or a \
                    reasonCode is not one of the closed set, 404 when a line's itemId does not name a work order \
                    part line, and 422 RETURN_QUANTITY_EXCEEDED when a line's quantity exceeds what remains \
                    returnable.
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
            description = "FORBIDDEN when the caller lacks inventory:return:write;"
                    + " LOCATION_SCOPE_DENIED when the caller holds it but the token scopes it to"
                    + " locations that do not cover every line's locationId (ADR-0061)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "A line's itemId does not name a work order part line",
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
        // ADR-0061 §3 (#1872): every line names the location it returns stock to; each distinct
        // one is gated before anything is accepted. The body is validated by then, so no
        // locationId is null.
        LocationScope scope = SecurityContextHelper.locationScope();
        request.getLines().stream()
                .map(ReturnLineDto::getLocationId)
                .distinct()
                .forEach(locationId -> scope.require(InventoryPermissionRegistry.RETURN_WRITE, locationId));
        ReturnSubmissionResultDto response = returnService.submitToStock(request);
        return ResponseEntity.accepted().body(response);
    }
}
