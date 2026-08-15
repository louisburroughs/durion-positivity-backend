package com.positivity.catalog.internal.controller;

import com.positivity.catalog.internal.dto.ItemCostAuditDto;
import com.positivity.catalog.internal.dto.ItemCostsDto;
import com.positivity.catalog.internal.dto.UpdateStandardCostRequestDto;
import com.positivity.catalog.internal.security.CatalogPermissions;
import com.positivity.catalog.service.ItemCostService;
import com.positivity.events.EmitEvent;
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
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/products/items")
@Tag(name = "Item Cost API", description = "Manage standard, last, and average item costs")
public class ItemCostController {

    private final ItemCostService itemCostService;

    public ItemCostController(ItemCostService itemCostService) {
        this.itemCostService = itemCostService;
    }

    @PutMapping("/{itemId}/standard-cost")
    @PreAuthorize(
            "hasRole('ADMIN') or hasRole('MANAGER') or hasAuthority('" + CatalogPermissions.ITEM_COST_UPDATE + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_MANAGER", CatalogPermissions.ITEM_COST_UPDATE})
    @Operation(operationId = "updateStandardItemCost", summary = "Update Standard Item Cost", description = """
            Sets the standard cost of an item to a new value, rounding it to four decimal places, and writes \
            an audit entry recording the old value, new value, actor and reason.
            Use this tool for a deliberate manual cost change; do not use getItemCosts, which only reads the \
            current values — last and average cost cannot be set here, they move only from purchase-order \
            receipt events.
            Preconditions: none; an item with no cost record yet gets one initialised with zero quantity on \
            hand, so this call never fails on a missing item.
            Required inputs: itemId (UUID) path parameter plus a positive newCost and a non-blank reasonCode \
            in the body.
            Emits a CATALOG_ITEM_COST_STANDARD_UPDATE event and appends a MANUAL audit row.
            Returns 400 when newCost is missing or not positive, or when reasonCode is blank.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "Updated",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ItemCostsDto.class)))
    @ApiResponse(responseCode = "400", description = "Invalid payload")
    @EmitEvent(id = "CATALOG_ITEM_COST_STANDARD_UPDATE", apiVersion = "1")
    public ResponseEntity<ItemCostsDto> updateStandardCost(
            @Parameter(required = true) @PathVariable UUID itemId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The new standard cost and the business reason for changing it.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = UpdateStandardCostRequestDto.class),
                                            examples = @ExampleObject(name = "Annual cost revision", value = """
                                                                    {"newCost":42.5000,"reasonCode":"ANNUAL_REVIEW"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    UpdateStandardCostRequestDto request) {
        return ResponseEntity.ok(itemCostService.updateStandardCost(itemId, request));
    }

    @GetMapping("/{itemId}/costs")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MANAGER') or hasRole('CATALOG_VIEW')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_MANAGER", "ROLE_CATALOG_VIEW"})
    @Operation(operationId = "getItemCosts", summary = "Get Current Item Costs", description = """
            Returns the item's current standard, average and last known costs in one record.
            Use this tool to read cost values; do not use updateStandardItemCost, which changes the standard \
            cost, and use getItemCostAuditHistory for who changed what and when.
            Preconditions: none; an item with no cost record yet returns the itemId with all three cost \
            fields null rather than an error.
            Required inputs: itemId (UUID) as a path parameter; there is no request body.
            No events are emitted and no state changes; this is a read-only projection.
            Returns 200 in all cases, so callers must treat null cost fields as absence of cost data rather \
            than relying on a 404.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "Found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ItemCostsDto.class)))
    public ResponseEntity<ItemCostsDto> getItemCosts(@Parameter(required = true) @PathVariable UUID itemId) {
        return ResponseEntity.ok(itemCostService.getItemCosts(itemId));
    }

    @GetMapping("/{itemId}/costs/audit")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MANAGER') or hasRole('CATALOG_VIEW')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_MANAGER", "ROLE_CATALOG_VIEW"})
    @Operation(operationId = "getItemCostAuditHistory", summary = "Get Item Cost Audit History", description = """
            Returns every recorded cost change for an item, newest first, each entry naming the cost type \
            changed, old and new value, the actor, the change source and any reason code.
            Use this tool to trace how a cost reached its current value; use getItemCosts instead for just \
            the current numbers.
            Preconditions: none; entries exist only after manual standard-cost updates or purchase-order \
            receipt events have touched the item.
            Required inputs: itemId (UUID) as a path parameter; there is no request body and no paging.
            No events are emitted and no state changes; this is a read-only projection.
            Returns 200 with an empty array when the item has no cost history, so an empty result is not an \
            error condition.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "Found",
            content =
                    @Content(mediaType = "application/json", schema = @Schema(implementation = ItemCostAuditDto.class)))
    public ResponseEntity<List<ItemCostAuditDto>> getAuditHistory(
            @Parameter(required = true) @PathVariable UUID itemId) {
        return ResponseEntity.ok(itemCostService.getAuditHistory(itemId));
    }
}
