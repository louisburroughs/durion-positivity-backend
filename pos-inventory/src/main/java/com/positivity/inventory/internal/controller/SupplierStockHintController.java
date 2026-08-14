package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.supplierhint.SupplierStockHintView;
import com.positivity.inventory.internal.enums.SupplierHintIdentityKind;
import com.positivity.inventory.service.SupplierStockHintService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Supplier availability hints: what vendors last said about their own stock (CAP-322, #1312).
 *
 * <p>Advisory throughout. Nothing here is owned stock, nothing here is availability-to-promise, and
 * nothing here should gate committing stock to a customer.
 */
@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(
        name = "bearerAuth",
        scopes = {"inventory:supplier_stock_hint:view"})
@RequestMapping("/v1/inventory/supplierStockHints")
@RequiredArgsConstructor
@Tag(
        name = "Supplier Stock Hints",
        description = "Vendor-reported availability hints; advisory, never owned stock or ATP")
public class SupplierStockHintController {

    private final SupplierStockHintService supplierStockHintService;

    @GetMapping("/byProduct/{productId}")
    @EmitEvent(id = "INVENTORY_SUPPLIER_STOCK_HINT_LIST_BY_PRODUCT", apiVersion = "1")
    @PreAuthorize("hasAuthority('inventory:supplier_stock_hint:view')")
    @Operation(
            operationId = "listSupplierStockHintsByProduct",
            summary = "List vendor availability hints for a product",
            description = """
                    Returns what each vendor last reported about its own availability of a product, one entry \
                    per vendor, newest fetch first.
                    Use this tool to tell a customer what a supplier says it can supply for a future need; do \
                    not use it to decide what can be promised or committed today, which is owned \
                    availability-to-promise and comes from the availability endpoints instead.
                    Preconditions: none; the product need not exist, and hints appear only for articles that \
                    have been resolved to it.
                    Required inputs: productId as a path parameter.
                    No state changes; this is a read of what vendors reported.
                    Returns 200 with an empty array when no vendor has reported the product, which means no \
                    vendor has said anything about it — NOT that it is unavailable. Read each entry's \
                    availability field: REPORTED carries the vendor's quantity (0 meaning the vendor \
                    explicitly has none), QUANTITY_NOT_STATED means the vendor listed the article without a \
                    quantity, and STALE_UNKNOWN means the vendor's figure is too old to repeat. Judge \
                    freshness on snapshotAsOf, the vendor's own figure, and check asOfSource: when it is \
                    FETCH the vendor stated no time and the age shown is only when we asked.
                    """,
            tags = {"Supplier Stock Hints"})
    @ApiResponse(
            responseCode = "200",
            description = "Vendor hints for the product, empty when none have been reported",
            content =
                    @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = SupplierStockHintView.class))))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks the supplier stock hint view authority",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<List<SupplierStockHintView>> listByProduct(
            @Parameter(description = "Catalog product to report vendor availability for", required = true) @PathVariable
                    UUID productId) {
        return ResponseEntity.ok(supplierStockHintService.findByProduct(productId));
    }

    @GetMapping("/byCode")
    @EmitEvent(id = "INVENTORY_SUPPLIER_STOCK_HINT_LIST_BY_CODE", apiVersion = "1")
    @PreAuthorize("hasAuthority('inventory:supplier_stock_hint:view')")
    @Operation(
            operationId = "listSupplierStockHintsByCode",
            summary = "List vendor availability hints for an article code",
            description = """
                    Returns what each vendor last reported about an article identified by a code, whether or \
                    not the article has been resolved to a catalog product.
                    Use this tool when the article is known by an EAN or by an article code rather than by a \
                    product id — in particular for articles no product in the catalog carries, whose hints are \
                    retained and reachable only this way.
                    Preconditions: none; an unknown code simply matches nothing.
                    Required inputs: identityKind (EAN, BUYER_ARTICLE or SUPPLIER_ARTICLE) and value, matched \
                    exactly after trimming.
                    No state changes; this is a read of what vendors reported.
                    Returns 200 with an empty array when no vendor stated that code. The availability, \
                    snapshotAsOf and asOfSource fields carry the same meanings as on the by-product listing, \
                    and an entry with a null resolvedProductId is an article we have not tied to a catalog \
                    product, not an error.
                    """,
            tags = {"Supplier Stock Hints"})
    @ApiResponse(
            responseCode = "200",
            description = "Vendor hints stating the code, empty when none do",
            content =
                    @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = SupplierStockHintView.class))))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks the supplier stock hint view authority",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<List<SupplierStockHintView>> listByCode(
            @Parameter(
                            description = "Identifier scheme the value belongs to",
                            required = true,
                            schema = @Schema(implementation = SupplierHintIdentityKind.class))
                    @RequestParam
                    SupplierHintIdentityKind identityKind,
            @Parameter(description = "Exact code value; trimmed, otherwise matched verbatim", required = true)
                    @RequestParam
                    String value) {
        return ResponseEntity.ok(supplierStockHintService.findByCode(identityKind, value));
    }
}
