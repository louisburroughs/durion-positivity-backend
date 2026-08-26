package com.positivity.vehiclefitment.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.vehiclefitment.internal.dto.CreateHintRequest;
import com.positivity.vehiclefitment.internal.dto.FilterProductsRequest;
import com.positivity.vehiclefitment.internal.dto.FilterProductsResponse;
import com.positivity.vehiclefitment.internal.dto.HintResponse;
import com.positivity.vehiclefitment.internal.dto.UpdateHintRequest;
import com.positivity.vehiclefitment.internal.security.VehicleFitmentPermissions;
import com.positivity.vehiclefitment.service.VehicleApplicabilityHintService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for managing vehicle applicability hints.
 */
@Slf4j
@Tag(
        name = "Vehicle Applicability Hints",
        description = "Endpoints for managing product fitment hints and filtering products by vehicle attributes")
@RequiredArgsConstructor
@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@PreAuthorize("isAuthenticated()")
@RequestMapping("/v1/vehicle-fitment/hints")
public class VehicleApplicabilityHintController {

    private final VehicleApplicabilityHintService hintService;

    @Operation(operationId = "createVehicleHint", summary = "Create Vehicle Applicability Hint", description = """
                    Creates a vehicle applicability hint that attaches a set of fitment tags to one product, making \
                    the product discoverable through filterProductsByVehicleAttributes.
                    Use this tool when declaring which vehicles a product fits for the first time; do not use \
                    updateVehicleHint, which replaces the tags on a hint that already exists.
                    Preconditions: none are checked against other services; productId is stored as given and is not \
                    verified against the catalog, so callers must supply a valid product id themselves.
                    Required inputs: productId (UUID) and fitmentTags, a non-empty list of tagType/tagValue pairs; \
                    tagType is one of MAKE, MODEL, YEAR_RANGE, TIRE_SIZE, AXLE_POSITION, ENGINE_SIZE or TRIM_LEVEL, \
                    tagValue is at most 120 characters, and YEAR_RANGE values use "2018-2022" or a single year like \
                    "2020".
                    Emits a VEHICLE_HINT_CREATED event and persists the hint with its tags in one transaction.
                    Returns 201 with the stored hint on success; because productId is not validated, an unknown \
                    product id still returns 201 rather than 404.
                    """)
    @ApiResponse(responseCode = "201", description = "Hint created successfully")
    @ApiResponse(responseCode = "400", description = "Invalid request data")
    @ApiResponse(responseCode = "404", description = "Product not found")
    @PreAuthorize("hasAuthority('" + VehicleFitmentPermissions.HINT_CREATE + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {VehicleFitmentPermissions.HINT_CREATE})
    @EmitEvent(id = "VEHICLE_HINT_CREATED", apiVersion = "1")
    @PostMapping
    public ResponseEntity<HintResponse> createHint(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Hint to create: the product identifier and the fitment tags that describe which vehicles the product applies to.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Make, model and year-range hint",
                                                            value = """
                                                                    {"productId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                     "fitmentTags":[
                                                                       {"tagType":"MAKE","tagValue":"Toyota"},
                                                                       {"tagType":"MODEL","tagValue":"Camry"},
                                                                       {"tagType":"YEAR_RANGE","tagValue":"2018-2022"}]}
                                                                    """)))
                    @Valid
                    @RequestBody
                    CreateHintRequest request) {
        try {
            HintResponse response = hintService.createHint(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException e) {
            log.error("Error creating hint: {}", sanitizeForLogging(e.getMessage()));
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    @Operation(operationId = "updateVehicleHint", summary = "Update Vehicle Applicability Hint", description = """
                    Replaces the full set of fitment tags on an existing vehicle applicability hint; the previous \
                    tags are deleted and the submitted list becomes the hint's only tags.
                    Use this tool to correct or extend the vehicles a product fits; do not use createVehicleHint, \
                    which adds a second hint to the product instead of changing this one.
                    Preconditions: the hint must already exist under the supplied hintId; the owning productId \
                    cannot be changed here.
                    Required inputs: hintId (UUID) as a path parameter and fitmentTags, a non-empty list of \
                    tagType/tagValue pairs; the list is a full replacement, so tags to keep must be resubmitted.
                    Emits a VEHICLE_HINT_UPDATED event and records the caller as updatedBy.
                    Returns 200 with the updated hint, and 404 when no hint exists for the supplied id.
                    """)
    @ApiResponse(responseCode = "200", description = "Hint updated successfully")
    @ApiResponse(responseCode = "400", description = "Invalid request data")
    @ApiResponse(responseCode = "404", description = "Hint not found")
    @PreAuthorize("hasAuthority('" + VehicleFitmentPermissions.HINT_UPDATE + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {VehicleFitmentPermissions.HINT_UPDATE})
    @EmitEvent(id = "VEHICLE_HINT_UPDATED", apiVersion = "1")
    @PutMapping("/{hintId}")
    public ResponseEntity<HintResponse> updateHint(
            @Parameter(description = "ID of the hint to update") @PathVariable UUID hintId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Replacement fitment tag list for the hint; it fully supersedes the tags currently stored.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Replace tags with a wider year range",
                                                            value = """
                                                                    {"fitmentTags":[
                                                                       {"tagType":"MAKE","tagValue":"Toyota"},
                                                                       {"tagType":"MODEL","tagValue":"Camry"},
                                                                       {"tagType":"YEAR_RANGE","tagValue":"2016-2024"}]}
                                                                    """)))
                    @Valid
                    @RequestBody
                    UpdateHintRequest request) {
        try {
            HintResponse response = hintService.updateHint(hintId, request);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.error("Error updating hint(mask) {}: {}", maskForLog(hintId), sanitizeForLogging(e.getMessage()));
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    @Operation(operationId = "deleteVehicleHint", summary = "Delete Vehicle Applicability Hint", description = """
                    Deletes a vehicle applicability hint together with all fitment tags attached to it, removing \
                    those match criteria from product filtering.
                    Use this tool when a hint's vehicle coverage should no longer apply to the product at all; do \
                    not use updateVehicleHint, which keeps the hint and replaces its tags instead.
                    Preconditions: the hint must exist under the supplied hintId.
                    Required inputs: hintId (UUID) as a path parameter; there is no request body.
                    Emits a VEHICLE_HINT_DELETED event; the delete is permanent, with no soft-delete or restore.
                    Returns 204 on successful deletion, and 404 when no hint exists for the supplied id.
                    """)
    @ApiResponse(responseCode = "204", description = "Hint deleted successfully")
    @ApiResponse(responseCode = "404", description = "Hint not found")
    @PreAuthorize("hasAuthority('" + VehicleFitmentPermissions.HINT_DELETE + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {VehicleFitmentPermissions.HINT_DELETE})
    @EmitEvent(id = "VEHICLE_HINT_DELETED", apiVersion = "1")
    @DeleteMapping("/{hintId}")
    public ResponseEntity<Void> deleteHint(
            @Parameter(description = "ID of the hint to delete") @PathVariable UUID hintId) {
        try {
            hintService.deleteHint(hintId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            log.error("Error deleting hint(mask) {}: {}", maskForLog(hintId), sanitizeForLogging(e.getMessage()));
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    @Operation(operationId = "getVehicleHint", summary = "Get Vehicle Applicability Hint", description = """
                    Returns one vehicle applicability hint, including its productId, fitment tags and audit fields.
                    Use this tool when the hintId is already known; use listVehicleHintsByProduct instead to find \
                    all hints attached to a product.
                    Preconditions: the hint must exist under the supplied hintId.
                    Required inputs: hintId (UUID) as a path parameter.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with the hint, and 404 when no hint exists for the supplied id.
                    """)
    @ApiResponse(responseCode = "200", description = "Hint retrieved successfully")
    @ApiResponse(responseCode = "404", description = "Hint not found")
    @PreAuthorize("hasAuthority('" + VehicleFitmentPermissions.HINT_VIEW + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {VehicleFitmentPermissions.HINT_VIEW})
    @GetMapping("/{hintId}")
    public ResponseEntity<HintResponse> getHint(
            @Parameter(description = "ID of the hint to retrieve") @PathVariable UUID hintId) {
        try {
            HintResponse response = hintService.getHint(hintId);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.error("Error retrieving hint(mask) {}: {}", maskForLog(hintId), sanitizeForLogging(e.getMessage()));
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    @Operation(operationId = "listVehicleHintsByProduct", summary = "List Hints for a Product", description = """
                    Returns every vehicle applicability hint recorded for one product, each with its full fitment \
                    tag list.
                    Use this tool to review a product's declared vehicle coverage; use getVehicleHint instead when \
                    a specific hintId is already known.
                    Preconditions: none; an unknown productId or one without hints simply yields an empty list \
                    rather than an error.
                    Required inputs: productId (UUID) as a path parameter.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with the hint list, which is empty when the product has no hints.
                    """)
    @ApiResponse(responseCode = "200", description = "Hints retrieved successfully")
    @PreAuthorize("hasAuthority('" + VehicleFitmentPermissions.HINT_VIEW + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {VehicleFitmentPermissions.HINT_VIEW})
    @GetMapping("/product/{productId}")
    public ResponseEntity<List<HintResponse>> getHintsByProductId(
            @Parameter(description = "ID of the product") @PathVariable UUID productId) {
        List<HintResponse> responses = hintService.getHintsByProductId(productId);
        return ResponseEntity.ok(responses);
    }

    @Operation(
            operationId = "filterProductsByVehicleAttributes",
            summary = "Filter Products by Vehicle Attributes",
            description = """
                    Finds product ids whose applicability hints are compatible with a supplied set of vehicle \
                    attributes such as make, model and year.
                    Use this tool to answer which products fit a given vehicle; use listVehicleHintsByProduct \
                    instead to go the other direction, from a product to its vehicle coverage.
                    Preconditions: hints must already exist, and attribute keys must match tag types \
                    case-insensitively (make, model, year_range, tire_size, axle_position, engine_size, \
                    trim_level); unknown keys are ignored rather than rejected.
                    Required inputs: vehicleAttributes, a non-empty map of attribute name to value; value matching \
                    is case-insensitive, a hint that lacks a given tag type is treated as compatible with that \
                    attribute, and a year value like "2020" matches YEAR_RANGE tags stored as "2018-2022" or as a \
                    single year.
                    Emits a FITMENT_PRODUCTS_FILTER event; no hint or product records are modified.
                    Returns 200 with the matching productIds and their count, which may be empty, and 400 when \
                    vehicleAttributes is missing, empty, or contains blank keys or values.
                    """)
    @ApiResponse(responseCode = "200", description = "Products filtered successfully")
    @ApiResponse(responseCode = "400", description = "Invalid request data")
    @PreAuthorize("hasAuthority('" + VehicleFitmentPermissions.CATALOG_VIEW + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {VehicleFitmentPermissions.CATALOG_VIEW})
    @EmitEvent(id = "FITMENT_PRODUCTS_FILTER", apiVersion = "1")
    @PostMapping("/filter-products")
    public ResponseEntity<FilterProductsResponse> filterProducts(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Vehicle attributes to match against stored fitment tags; keys name a tag type and values describe the vehicle being fitted.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Match a 2020 Toyota Camry", value = """
                                                                    {"vehicleAttributes":{"make":"Toyota",
                                                                                          "model":"Camry",
                                                                                          "year_range":"2020"}}
                                                                    """)))
                    @Valid
                    @RequestBody
                    FilterProductsRequest request) {
        FilterProductsResponse response = hintService.filterProductsByVehicleAttributes(request.getVehicleAttributes());
        return ResponseEntity.ok(response);
    }

    private String maskForLog(Object value) {
        if (value == null) {
            return "null";
        }
        String sanitized =
                value.toString().replace('\r', '_').replace('\n', '_').replace('\t', '_');
        int length = sanitized.length();
        if (length <= 4) {
            return "****";
        }
        return sanitized.substring(0, 2) + "***" + sanitized.substring(length - 2);
    }

    private String sanitizeForLogging(String input) {
        if (input == null) {
            return "null";
        }
        return input.replaceAll("[\\r\\n\\t]", "_").replaceAll("[\\p{Cntrl}]", "");
    }
}
