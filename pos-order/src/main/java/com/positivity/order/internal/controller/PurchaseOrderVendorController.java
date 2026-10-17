package com.positivity.order.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.order.internal.dto.purchaseorder.ListPurchaseOrderVendorsRequest;
import com.positivity.order.internal.dto.purchaseorder.PurchaseOrderVendorResponse;
import com.positivity.order.internal.security.PurchaseOrderPermissions;
import com.positivity.order.internal.service.PurchaseOrderVendorService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The vendors a purchase order may name, read from pos-order's own vendor copy (CAP:550, #2680).
 *
 * <p>A controller of its own rather than more methods on {@link PurchaseOrderController}: pos-mcp-server's
 * {@code PurchaseOrderRagCoverageTest} parses that class, and the purchase-order personas (INVENTORY_LEAD,
 * INVENTORY_MANAGER, LOCATION_MANAGER) hold {@code order:purchase_order:view} but none of the vendor-master reads
 * ({@code supplier:vendor:read}, {@code accounting:ap:view}). The copy holds exactly the number, name and status
 * those screens need, so this read widens no one's sight of remit-to or tax-registration data (ADR-0044 R3; OI-5,
 * OI-16). {@link PurchaseOrderExceptionHandler} covers this controller, so 403 is {@code PURCHASE_ORDER_FORBIDDEN}
 * and a bad sort is 400 {@code PURCHASE_ORDER_BAD_REQUEST}; a filter that does not bind ({@code status} outside the
 * enum, {@code q} over 255 characters) is the platform's 400 {@code VALIDATION_ERROR} with {@code fieldErrors}.
 */
@RestController
@RequestMapping("/v1/orders/purchase-order-vendors")
@RequiredArgsConstructor
@Tag(name = "Purchase Orders", description = "Purchase order creation, approval, revision and cancellation")
public class PurchaseOrderVendorController {

    private final PurchaseOrderVendorService purchaseOrderVendorService;

    @GetMapping
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"order:purchase_order:view"})
    @PreAuthorize("hasAuthority('" + PurchaseOrderPermissions.PURCHASE_ORDER_VIEW + "')")
    @EmitEvent(id = "ORDER_PURCHASE_ORDER_VENDOR_LIST", apiVersion = "1")
    @Operation(
            operationId = "listPurchaseOrderVendors",
            summary = "List Purchase-Order Vendors",
            description = """
                    Returns a page of the vendors a purchase order may name, from pos-order's own copy of the \
                    pos-supplier vendor master: vendorId, vendorNumber, displayName and status, nothing else.
                    Use this tool to pick the vendorId for createPurchaseOrder or a vendor-changing revision; a \
                    vendor listed here as ACTIVE never answers 503 VENDOR_REPLICATION_PENDING on create, and a \
                    DRAFT order whose vendor is not in the ACTIVE list is one approvePurchaseOrder will refuse.
                    Preconditions: none; the copy is fed by supplier.vendor.updated, so a vendor created in \
                    pos-supplier moments ago may not be listed yet.
                    Required inputs: none; status (ACTIVE or INACTIVE, upper case) and q (a case-insensitive \
                    substring of the vendor number or display name, at most 255 characters) narrow the result, and \
                    page, size and sort (vendorNumber, displayName, status or vendorId; default displayName,asc) \
                    control pagination.
                    Emits an ORDER_PURCHASE_ORDER_VENDOR_LIST audit event; a read-only projection that never calls \
                    pos-supplier.
                    Returns 200 with an empty page when nothing matches; 400 PURCHASE_ORDER_BAD_REQUEST on a sort \
                    property outside the four listed, and 400 VALIDATION_ERROR (with fieldErrors) on a status that \
                    is not ACTIVE or INACTIVE or a q over 255 characters.
                    """,
            tags = {"Purchase Orders"})
    @ApiResponse(responseCode = "200", description = "Vendors returned")
    @ApiResponse(
            responseCode = "400",
            description = "PURCHASE_ORDER_BAD_REQUEST: a sort property other than vendorNumber, displayName, status or"
                    + " vendorId; VALIDATION_ERROR (fieldErrors): status not ACTIVE or INACTIVE, or q over 255"
                    + " characters",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required purchase order view authority",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<Page<PurchaseOrderVendorResponse>> listPurchaseOrderVendors(
            @ParameterObject @ModelAttribute @Valid ListPurchaseOrderVendorsRequest filter,
            @ParameterObject @PageableDefault(sort = "displayName") Pageable pageable) {
        return ResponseEntity.ok(purchaseOrderVendorService.listVendors(filter, pageable));
    }

    @GetMapping("/{vendorId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"order:purchase_order:view"})
    @PreAuthorize("hasAuthority('" + PurchaseOrderPermissions.PURCHASE_ORDER_VIEW + "')")
    @EmitEvent(id = "ORDER_PURCHASE_ORDER_VENDOR_GET", apiVersion = "1")
    @Operation(
            operationId = "getPurchaseOrderVendor",
            summary = "Get Purchase-Order Vendor",
            description = """
                    Returns one vendor of pos-order's vendor copy by its pos-supplier vendor id: vendorNumber, \
                    displayName and status.
                    Use this tool to name the vendor on a purchase-order screen when the vendorId is already \
                    known; use listPurchaseOrderVendors to find one.
                    Preconditions: none. An INACTIVE vendor is returned as such (200), since that is a fact about \
                    the vendor, not a refusal of this read.
                    Required inputs: vendorId (path).
                    Emits an ORDER_PURCHASE_ORDER_VENDOR_GET audit event; read-only, never calls pos-supplier.
                    Returns 503 VENDOR_REPLICATION_PENDING with Retry-After when the copy does not hold the id: \
                    pos-order cannot tell a vendor that does not exist from one whose fact has not arrived, so it \
                    never answers 404 (ADR-0017 §1). A malformed vendorId is 400.
                    """,
            tags = {"Purchase Orders"})
    @ApiResponse(
            responseCode = "200",
            description = "Vendor returned",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = PurchaseOrderVendorResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "vendorId is not a UUID",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required purchase order view authority",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "503",
            description = "VENDOR_REPLICATION_PENDING: the vendor has not reached pos-order's copy of the pos-supplier"
                    + " vendor master yet. Not-yet, not no: retry after the Retry-After interval.",
            headers =
                    @Header(
                            name = "Retry-After",
                            description = "Seconds to wait before retrying",
                            schema = @Schema(type = "integer")),
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<PurchaseOrderVendorResponse> getPurchaseOrderVendor(
            @Parameter(description = "The pos-supplier vendor id", required = true) @PathVariable UUID vendorId) {
        return ResponseEntity.ok(purchaseOrderVendorService.getVendor(vendorId));
    }
}
