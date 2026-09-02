package com.positivity.supplier.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import com.positivity.supplier.internal.security.SupplierPermissions;
import com.positivity.supplier.internal.stockinquiry.service.SupplierStockAvailabilityService;
import com.positivity.supplier.internal.stockinquiry.service.model.StockAvailabilityView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Product-keyed live availability across every enabled stock-inquiry vendor (#1637 decision 1).
 *
 * <h2>Why this is a separate controller from the per-vendor inquiry</h2>
 *
 * {@link SupplierStockInquiryController} serves the ADR-0044 grant-surface contract: a caller that
 * already knows a vendor and an article identity asks that one vendor. This endpoint is the
 * frontend-facing composition over it — the caller knows only a catalog product, and which vendors
 * exist and which codes they are asked about is this module's business. The two carry different
 * DTOs, different permissions, and different callers, and folding them into one class would blur
 * exactly the boundary decision 1 draws.
 *
 * <h2>Why a partial answer is a 200</h2>
 *
 * The caller composes a product panel that has something to render for every combination of vendor
 * outcomes, including "nobody answered". Per-vendor failure is therefore a status inside the body.
 * The error statuses that exist are about the request: an unresolvable product identity is a 404,
 * because a question this module cannot even put to a vendor is different in kind from a vendor
 * that did not answer it.
 */
@Tag(
        name = "Supplier Stock Availability",
        description = "Live availability of one catalog product across every enabled stock-inquiry vendor. The"
                + " backend resolves catalog identity to vendor article codes internally and fans out concurrently"
                + " under a deadline; per-vendor failure is a status on a 200 body, never an error.")
@RestController
@RequiredArgsConstructor
@Validated
@SecurityRequirement(name = "bearerAuth")
@RequestMapping("/v1/supplier/stock")
public class SupplierStockAvailabilityController {

    private static final String UNAUTHENTICATED_DESCRIPTION = "Authentication is missing or the bearer token is"
            + " invalid. The response has NO body: the gateway rejects unauthenticated calls with a bodiless"
            + " status, so clients must not attempt to parse an error envelope here.";

    private static final String ANSWERED_EXAMPLE = """
            {
              "productId": "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
              "deliveryLocationId": "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5f",
              "requestedQuantity": 4,
              "stalenessThreshold": "PT15M",
              "vendors": [
                {
                  "vendorProfileId": "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c",
                  "vendorDisplayName": "Michelin Europe",
                  "status": "OK",
                  "fetchedAt": "2026-08-14T12:00:00Z",
                  "asOf": "2026-08-14T12:00:00Z",
                  "stale": false,
                  "lines": [
                    {
                      "status": "AVAILABLE",
                      "availableQuantity": 8,
                      "earliestDeliveryDate": "2026-08-20",
                      "quotedUnitPrice": null,
                      "currency": null
                    }
                  ]
                },
                {
                  "vendorProfileId": "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5d",
                  "vendorDisplayName": "Conti DACH",
                  "status": "SUPPLIER_UNAVAILABLE",
                  "fetchedAt": null,
                  "asOf": null,
                  "stale": null,
                  "lines": []
                }
              ]
            }""";

    private final SupplierStockAvailabilityService availabilityService;

    /**
     * Checks one product's live availability with every enabled stock-inquiry vendor.
     *
     * @param productId catalog product id; exactly one of this and {@code sku}
     * @param sku catalog SKU; exactly one of this and {@code productId}
     * @param deliveryLocationId the receiving location the question is about
     * @param quantity quantity whose availability is checked, default 1
     * @return per-vendor availability; always 200 when the product identity resolves
     */
    @GetMapping("/availability")
    @PreAuthorize("hasAuthority('" + SupplierPermissions.STOCK_AVAILABILITY_READ + "')")
    @EmitEvent(id = "SUPPLIER_STOCK_AVAILABILITY_GET", apiVersion = "1")
    @Operation(
            operationId = "getSupplierStockAvailability",
            summary = "Check a product's live availability across all enabled stock-inquiry vendors",
            description = """
                    Resolves the named catalog product to its vendor-queryable article identity internally and asks \
                    every enabled STOCK_INQUIRY vendor binding concurrently, for the named receiving location; no \
                    EAN, UPC or vendor article code appears in the request or the response, because product-to-vendor \
                    identity mapping stays a backend implementation detail.
                    Use this tool when composing a product panel that must show what every configured vendor holds \
                    right now; use the per-vendor inquiry instead when the caller already knows exactly which \
                    vendor to ask.
                    Preconditions: availability is consignee-specific, so the answer is valid only for the named \
                    delivery location, and the product must resolve to a code in the catalog replica.
                    Required inputs: exactly one of productId or sku, plus deliveryLocationId; quantity is optional, \
                    at least 1, default 1.
                    The fan-out runs under a configured deadline — a vendor that has not answered in time is \
                    reported as SUPPLIER_UNAVAILABLE while the vendors that did answer are returned alongside it, so \
                    the response is partial by design and every combination of per-vendor outcomes, an empty vendors \
                    list included, is a 200.
                    Each vendor result carries fetchedAt (when this platform obtained the answer; a cached answer \
                    keeps its original fetch instant) and the vendor-stated observation time asOf, and the stale \
                    flag is judged from asOf against the backend-owned stalenessThreshold echoed in the response so \
                    every client applies the same freshness rule.
                    Emits a SUPPLIER_STOCK_AVAILABILITY_GET event; availableQuantity is the canonical item/piece \
                    count — null means the vendor stated nothing, zero means it stated it has none, and no unit of \
                    measure or warehouse name travels because the supplier wire data carries neither.
                    Returns 400 when neither or both of productId and sku are given or quantity is below 1, 404 \
                    when the product identity resolves to no vendor-queryable code, 409 when the sku ambiguously \
                    names more than one replicated product, and 403 when the caller lacks \
                    supplier:stockavailability:read.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "The fan-out completed within the deadline budget. Partial by design: read each vendor's"
                    + " status — a non-answering vendor is a normal degraded case, not an error.",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = StockAvailabilityView.class),
                            examples = @ExampleObject(name = "mixedOutcomes", value = ANSWERED_EXAMPLE)))
    @ApiResponse(
            responseCode = "400",
            description = "Neither or both of `productId`/`sku` were given (the envelope's fieldErrors name both"
                    + " parameters), `deliveryLocationId` is missing, or `quantity` is below 1.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "401", description = UNAUTHENTICATED_DESCRIPTION, content = @Content)
    @ApiResponse(
            responseCode = "403",
            description = "The caller lacks `supplier:stockavailability:read`.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "The product identity resolves to no vendor-queryable code: the product is unknown to the"
                    + " catalog replica, or carries no EAN/UPC. Code `SUPPLIER_PRODUCT_CODES_NOT_FOUND`.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "The `sku` ambiguously names more than one replicated product — a replication defect"
                    + " refused rather than guessed at. Code `SUPPLIER_PRODUCT_SKU_AMBIGUOUS`.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<StockAvailabilityView> getSupplierStockAvailability(
            @Parameter(description = "Catalog product id. Exactly one of productId and sku must be given.")
                    @RequestParam(required = false)
                    UUID productId,
            @Parameter(
                            description = "Catalog SKU, matched case-insensitively (pos-catalog keeps SKUs unique"
                                    + " ignoring case). Exactly one of productId and sku must be given.")
                    @RequestParam(required = false)
                    String sku,
            @Parameter(description = "The receiving location the availability question is about.", required = true)
                    @RequestParam
                    UUID deliveryLocationId,
            @Parameter(description = "Quantity whose availability is checked; at least 1.")
                    @RequestParam(defaultValue = "1")
                    @Min(1)
                    int quantity) {
        return ResponseEntity.ok(availabilityService.checkAvailability(productId, sku, deliveryLocationId, quantity));
    }
}
