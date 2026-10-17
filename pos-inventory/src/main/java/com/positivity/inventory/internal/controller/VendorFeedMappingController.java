package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.vendorfeed.UnmappedFeedVendorResponse;
import com.positivity.inventory.internal.dto.vendorfeed.VendorFeedMappingRequest;
import com.positivity.inventory.internal.dto.vendorfeed.VendorFeedMappingResponse;
import com.positivity.inventory.internal.dto.vendorfeed.VendorFeedMappingUpdateRequest;
import com.positivity.inventory.internal.enums.FeedVendorType;
import com.positivity.inventory.internal.security.InventoryPermissionRegistry;
import com.positivity.inventory.internal.service.VendorFeedMappingService;
import com.positivity.shared.error.ApiError;
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
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Maintains the mapping from manufacturer and distributor feed vendors to pos-supplier vendors (CAP:550 S36, #2525).
 * Reads need {@code inventory:replenishment:manage}, the permission the people who act on purchase suggestions hold;
 * changes need {@code inventory:vendor_feed_mapping:manage}.
 */
@RestController
@RequestMapping("/v1/inventory/vendor-feed-mappings")
@RequiredArgsConstructor
@Tag(
        name = "Vendor Feed Mappings",
        description = "Mapping from manufacturer and distributor feed vendors to pos-supplier vendors")
public class VendorFeedMappingController {

    private final VendorFeedMappingService vendorFeedMappingService;

    @GetMapping
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:replenishment:manage"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.REPLENISHMENT_MANAGE + "')")
    @EmitEvent(id = "INVENTORY_VENDOR_FEED_MAPPING_LIST", apiVersion = "1")
    @Operation(
            operationId = "listVendorFeedMappings",
            summary = "List Vendor Feed Mappings",
            description = """
                    Returns a page of mappings from manufacturer and distributor feed vendors to pos-supplier \
                    vendors, each with the vendor's number, display name and status from the local vendor copy.
                    Use this tool to see which pos-supplier vendor a feed vendor stands for, or which feed vendors \
                    a vendor covers; use listUnmappedFeedVendors instead to find the feed vendors that still need \
                    a mapping.
                    Preconditions: none; an empty page means no mapping matches the filters.
                    Required inputs: none; feedType (MANUFACTURER or DISTRIBUTOR) and vendorId are optional \
                    filters, and the page size defaults to 20.
                    Emits an INVENTORY_VENDOR_FEED_MAPPING_LIST event and changes no state.
                    Returns 400 when feedType is not a known value, and 200 with an empty page when nothing \
                    matches.
                    """,
            tags = {"Vendor Feed Mappings"})
    @ApiResponse(
            responseCode = "200",
            description = "Vendor feed mappings returned (paged)",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(description = "Page of vendor feed mappings")))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks inventory:replenishment:manage",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<Page<VendorFeedMappingResponse>> listVendorFeedMappings(
            @Parameter(description = "Feed filter (MANUFACTURER, DISTRIBUTOR)") @RequestParam(required = false)
                    FeedVendorType feedType,
            @Parameter(description = "pos-supplier vendor filter") @RequestParam(required = false) UUID vendorId,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(vendorFeedMappingService.list(feedType, vendorId, pageable));
    }

    @GetMapping("/unmapped")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:replenishment:manage"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.REPLENISHMENT_MANAGE + "')")
    @EmitEvent(id = "INVENTORY_VENDOR_FEED_MAPPING_UNMAPPED_LIST", apiVersion = "1")
    @Operation(
            operationId = "listUnmappedFeedVendors",
            summary = "List Unmapped Feed Vendors",
            description = """
                    Returns the manufacturer and distributor feed vendors that cannot yet be purchase candidates: \
                    those with no mapping (UNMAPPED), those mapped to a vendor missing from the local vendor copy \
                    (VENDOR_NOT_IN_COPY) and those mapped to an INACTIVE vendor (VENDOR_INACTIVE), each with the \
                    number of products it reports and when it last reported.
                    Use this tool to find the feed vendors that need a mapping before purchase suggestions can \
                    name a vendor; use listVendorFeedMappings instead to review mappings that already exist.
                    Preconditions: none; an empty list means every feed vendor maps to an active vendor.
                    Required inputs: none; feedType (MANUFACTURER or DISTRIBUTOR) is an optional filter that \
                    limits the scan to one feed.
                    Emits an INVENTORY_VENDOR_FEED_MAPPING_UNMAPPED_LIST event and changes no state.
                    Returns 400 when feedType is not a known value.
                    """,
            tags = {"Vendor Feed Mappings"})
    @ApiResponse(
            responseCode = "200",
            description = "Unmapped feed vendors returned",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = UnmappedFeedVendorResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks inventory:replenishment:manage",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<List<UnmappedFeedVendorResponse>> listUnmappedFeedVendors(
            @Parameter(description = "Feed filter (MANUFACTURER, DISTRIBUTOR)") @RequestParam(required = false)
                    FeedVendorType feedType) {
        return ResponseEntity.ok(vendorFeedMappingService.listUnmapped(feedType));
    }

    @PostMapping
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:vendor_feed_mapping:manage"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.VENDOR_FEED_MAPPING_MANAGE + "')")
    @EmitEvent(id = "INVENTORY_VENDOR_FEED_MAPPING_CREATE", apiVersion = "1")
    @Operation(
            operationId = "createVendorFeedMapping",
            summary = "Create Vendor Feed Mapping",
            description = """
                    Maps one manufacturer or distributor feed vendor to a pos-supplier vendor, and points the \
                    feed vendor's open (SUGGESTED or ACCEPTED) purchase suggestions at that vendor in the same \
                    transaction.
                    Use this tool when a feed vendor from listUnmappedFeedVendors needs its pos-supplier vendor; \
                    do not use it to change an existing mapping, because updateVendorFeedMapping re-points it.
                    Preconditions: the vendor must be known to pos-inventory from the pos-supplier vendor feed and \
                    ACTIVE, and the feed vendor must have no mapping yet.
                    Required inputs: feedType, feedVendorRef (trimmed; a UUID for a MANUFACTURER, stored \
                    lower-case) and vendorId; note is optional.
                    Emits an INVENTORY_VENDOR_FEED_MAPPING_CREATE event and stores the mapping.
                    Returns 201 with the mapping, 409 VENDOR_FEED_MAPPING_EXISTS when the feed vendor is already \
                    mapped, 422 VENDOR_NOT_FOUND or VENDOR_INACTIVE when the vendor is unknown or inactive, and \
                    400 when a field is missing or a manufacturer reference is not a UUID.
                    """,
            tags = {"Vendor Feed Mappings"})
    @ApiResponse(
            responseCode = "201",
            description = "Vendor feed mapping created",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = VendorFeedMappingResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Validation failure (missing field, or a manufacturer reference that is not a UUID)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks inventory:vendor_feed_mapping:manage",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "VENDOR_FEED_MAPPING_EXISTS: the feed vendor already has a mapping",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "VENDOR_NOT_FOUND: the vendor is not in the local copy; VENDOR_INACTIVE: it is inactive",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<VendorFeedMappingResponse> createVendorFeedMapping(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The feed vendor and the pos-supplier vendor it is; the vendor must be"
                                    + " active.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = VendorFeedMappingRequest.class),
                                            examples = @ExampleObject(name = "Map a manufacturer", value = """
                                                            {"feedType":"MANUFACTURER",
                                                             "feedVendorRef":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a30",
                                                             "vendorId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a31",
                                                             "note":"Michelin North America"}
                                                            """)))
                    @Valid
                    @RequestBody
                    VendorFeedMappingRequest request) {
        return ResponseEntity.status(201).body(vendorFeedMappingService.create(request));
    }

    @PutMapping("/{mappingId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:vendor_feed_mapping:manage"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.VENDOR_FEED_MAPPING_MANAGE + "')")
    @EmitEvent(id = "INVENTORY_VENDOR_FEED_MAPPING_UPDATE", apiVersion = "1")
    @Operation(
            operationId = "updateVendorFeedMapping",
            summary = "Update Vendor Feed Mapping",
            description = """
                    Points an existing feed vendor mapping at another pos-supplier vendor, and points the feed \
                    vendor's open (SUGGESTED or ACCEPTED) purchase suggestions at the new vendor in the same \
                    transaction.
                    Use this tool to re-point or re-annotate a mapping you have read; use createVendorFeedMapping \
                    instead for a feed vendor with no mapping, and deleteVendorFeedMapping to remove one.
                    Preconditions: the mapping must exist, the version must be the one last read, and the vendor \
                    must be known to pos-inventory and ACTIVE.
                    Required inputs: mappingId (UUIDv7) path parameter, and a body with vendorId and version; \
                    note is optional and null clears it.
                    Emits an INVENTORY_VENDOR_FEED_MAPPING_UPDATE event and stores the change.
                    Returns 404 VENDOR_FEED_MAPPING_NOT_FOUND when the mapping does not exist, 409 CONFLICT when \
                    the version is stale, and 422 VENDOR_NOT_FOUND or VENDOR_INACTIVE when the vendor is unknown \
                    or inactive.
                    """,
            tags = {"Vendor Feed Mappings"})
    @ApiResponse(
            responseCode = "200",
            description = "Vendor feed mapping updated",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = VendorFeedMappingResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Validation failure (missing vendorId or version)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks inventory:vendor_feed_mapping:manage",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "VENDOR_FEED_MAPPING_NOT_FOUND",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "CONFLICT: the version is stale, the mapping was changed since it was read",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "VENDOR_NOT_FOUND: the vendor is not in the local copy; VENDOR_INACTIVE: it is inactive",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<VendorFeedMappingResponse> updateVendorFeedMapping(
            @Parameter(description = "Vendor feed mapping identifier", required = true) @PathVariable UUID mappingId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The vendor to point the mapping at and the version last read.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = VendorFeedMappingUpdateRequest.class),
                                            examples = @ExampleObject(name = "Re-point a mapping", value = """
                                                            {"vendorId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a32",
                                                             "note":"Moved to the new legal entity",
                                                             "version":0}
                                                            """)))
                    @Valid
                    @RequestBody
                    VendorFeedMappingUpdateRequest request) {
        return ResponseEntity.ok(vendorFeedMappingService.update(mappingId, request));
    }

    @DeleteMapping("/{mappingId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:vendor_feed_mapping:manage"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.VENDOR_FEED_MAPPING_MANAGE + "')")
    @EmitEvent(id = "INVENTORY_VENDOR_FEED_MAPPING_DELETE", apiVersion = "1")
    @Operation(
            operationId = "deleteVendorFeedMapping",
            summary = "Delete Vendor Feed Mapping",
            description = """
                    Removes a feed vendor mapping, and clears the vendor on the feed vendor's open (SUGGESTED or \
                    ACCEPTED) purchase suggestions in the same transaction, so they are no longer convertible.
                    Use this tool when a feed vendor was mapped to the wrong vendor and should map to none; use \
                    updateVendorFeedMapping instead to point it at the right vendor in one step.
                    Preconditions: the mapping must exist.
                    Required inputs: mappingId (UUIDv7) path parameter; there is no request body.
                    Emits an INVENTORY_VENDOR_FEED_MAPPING_DELETE event and deletes the mapping; suggestions \
                    already converted or dismissed keep the vendor they had.
                    Returns 204 on success and 404 VENDOR_FEED_MAPPING_NOT_FOUND when the mapping does not exist.
                    """,
            tags = {"Vendor Feed Mappings"})
    @ApiResponse(responseCode = "204", description = "Vendor feed mapping deleted")
    @ApiResponse(
            responseCode = "403",
            description = "User lacks inventory:vendor_feed_mapping:manage",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "VENDOR_FEED_MAPPING_NOT_FOUND",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<Void> deleteVendorFeedMapping(
            @Parameter(description = "Vendor feed mapping identifier", required = true) @PathVariable UUID mappingId) {
        vendorFeedMappingService.delete(mappingId);
        return ResponseEntity.noContent().build();
    }
}
