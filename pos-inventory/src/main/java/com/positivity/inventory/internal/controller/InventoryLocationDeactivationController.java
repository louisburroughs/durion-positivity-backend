package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.DeactivateLocationRequest;
import com.positivity.inventory.internal.dto.DeactivateLocationResponse;
import com.positivity.inventory.service.InventoryLocationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/inventory/locations")
@Tag(name = "Inventory Management", description = "Operations related to inventory location management")
public class InventoryLocationDeactivationController {

    private final InventoryLocationService service;

    public InventoryLocationDeactivationController(InventoryLocationService service) {
        this.service = service;
    }

    /**
     * Deactivate a storage location.
     */
    @PostMapping("/{locationId}/deactivate")
    @EmitEvent(id = "INVENTORY_LOCATION_DEACTIVATE", apiVersion = "1")
    @PreAuthorize("hasAuthority('inventory:location:admin')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:location:admin"})
    @Operation(
            operationId = "deactivateInventoryLocation",
            summary = "Deactivate a storage location",
            description = """
                    Deactivates a storage location, atomically transferring any remaining on-hand stock to a \
                    destination location in the same site through paired TRANSFER_OUT and TRANSFER_IN ledger \
                    entries with reason LOCATION_DEACTIVATION_TRANSFER.
                    Use this tool to retire a bin or storage location; do not use triggerLocationSync, which \
                    repairs the roster replica, and do not use it for routine stock relocation between active \
                    locations.
                    Preconditions: the location must exist and be active; when it holds stock, the destination must \
                    exist, be active, differ from the source and belong to the same site.
                    Required inputs: locationId (UUID) path parameter; the body is optional and carries \
                    destinationLocationId, which becomes mandatory when the source holds stock.
                    Emits an INVENTORY_LOCATION_DEACTIVATE event, posts the transfer entries when stock is moved \
                    and publishes an audit event; the response reports status Inactive with the moved items.
                    Returns 404 when the source or destination location is unknown, 409 when the source or \
                    destination is already inactive, and 400 when destinationLocationId is missing while stock \
                    remains, equals the source, or belongs to a different site.
                    """,
            tags = {"Inventory Management"})
    @ApiResponse(
            responseCode = "200",
            description = "Deactivation completed",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = DeactivateLocationResponse.class)))
    @ApiResponse(responseCode = "400", description = "Bad request - invalid parameters or destination required")
    @ApiResponse(responseCode = "404", description = "Location not found")
    @ApiResponse(responseCode = "409", description = "Conflict - business rule violation")
    public ResponseEntity<DeactivateLocationResponse> deactivate(
            @Parameter(description = "Location ID to deactivate", required = true) @PathVariable("locationId")
                    UUID locationId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Optional destination for the remaining stock; mandatory when the"
                                    + " location still holds inventory.",
                            required = false,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Deactivate and move stock", value = """
                                                                    {"destinationLocationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a02"}
                                                                    """)))
                    @RequestBody(required = false)
                    DeactivateLocationRequest body) {
        DeactivateLocationResponse resp =
                service.deactivateLocation(locationId, body != null ? body.getDestinationLocationId() : null);
        return ResponseEntity.ok(resp);
    }
}
