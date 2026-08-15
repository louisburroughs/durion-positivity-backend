package com.positivity.supplier.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import com.positivity.supplier.internal.security.SupplierPermissions;
import com.positivity.supplier.service.SupplierStockService;
import com.positivity.supplier.service.model.StockInquiryRequest;
import com.positivity.supplier.service.model.StockInquiryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Live vendor stock inquiry — the platform's single approved synchronous supplier read
 * (ADR-0044 amendment 2026-08-10, ADR-0049 §4, CAP-319 #1225).
 *
 * <h2>Why this endpoint always returns 200</h2>
 *
 * Its callers compose a product page and a procurement quote. Both have something useful to render
 * without live vendor stock and nothing useful to render if this call fails, so a vendor being
 * unreachable is not an error condition here — it is an answer, carried as a status on a 200 body.
 * Returning 502 or 504 for an unreachable vendor would make every caller implement the same
 * try/catch whose only sensible body is "carry on without it", and the first caller to forget one
 * would take down a page over a supplier's bad afternoon.
 *
 * <p>The 4xx responses that do exist are about the request, not the vendor: a malformed body, or a
 * caller without the authority. Those are defects worth failing on.
 *
 * <h2>Why POST for a read</h2>
 *
 * An inquiry names a set of articles, and a set does not fit sensibly in a query string. The
 * operation creates nothing, is safe to retry, and is annotated as an inquiry rather than a
 * mutation — the method is a consequence of the payload, not a claim about side effects.
 */
@Tag(
        name = "Supplier Stock Inquiry",
        description = "Ask a vendor, live, what stock it holds for a receiving location. Vendor-side failure is"
                + " reported as a status on a 200 response, never as an error status: callers are expected to"
                + " degrade and render without live stock rather than fail their own flow.")
@RestController
@RequiredArgsConstructor
@Validated
@SecurityRequirement(name = "bearerAuth")
@RequestMapping("/v1/supplier/stock")
public class SupplierStockInquiryController {

    private static final String UNAUTHENTICATED_DESCRIPTION = "Authentication is missing or the bearer token is"
            + " invalid. The response has NO body: the gateway rejects unauthenticated calls with a bodiless"
            + " status, so clients must not attempt to parse an error envelope here.";

    private static final String REQUEST_EXAMPLE = """
            {
              "inquiryId": "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
              "vendorProfileId": "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c",
              "deliveryLocationId": "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5f",
              "lines": [
                { "articleEan": "4019238001234", "supplierArticleCode": null, "requestedQuantity": 4 }
              ]
            }""";

    private static final String ANSWERED_EXAMPLE = """
            {
              "inquiryId": "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
              "status": "OK",
              "lines": [
                {
                  "articleEan": "4019238001234",
                  "supplierArticleCode": null,
                  "status": "AVAILABLE",
                  "availableQuantity": 8,
                  "earliestDeliveryDate": "2026-08-20",
                  "quotedUnitPrice": null,
                  "currency": null
                }
              ],
              "asOf": "2026-08-14T12:00:00Z"
            }""";

    private final SupplierStockService stockService;

    /**
     * Asks the vendor what it holds for the receiving location named in the request.
     *
     * @param request the inquiry: vendor profile, delivery location, and article lines
     * @return the typed answer; always 200 when the request itself is well formed
     */
    @PostMapping("/inquiries")
    @PreAuthorize("hasAuthority('" + SupplierPermissions.STOCK_INQUIRE + "')")
    @EmitEvent(id = "SUPPLIER_STOCK_INQUIRY", apiVersion = "1")
    @Operation(
            operationId = "inquireSupplierStock",
            summary = "Ask a vendor for live stock availability",
            description = """
                    Places a real-time availability inquiry with the vendor bound to the given profile, for the given \
                    receiving location, and returns what the vendor said; availability is consignee-specific, so an \
                    answer for one location is never valid for another.
                    Use this tool when a decision depends on what a vendor holds right now — composing a product \
                    page, quoting procurement; do not use it to read a vendor's prices, which arrive as \
                    price-catalogue events and are read from the price catalogue instead.
                    Preconditions: the caller must hold supplier:stock:inquire, and the vendor profile must exist, be \
                    enabled, carry a STOCK_INQUIRY binding and map a delivery account to the receiving location; a \
                    profile meeting none of these is reported as a status rather than an error.
                    Required inputs: a JSON body carrying inquiryId, vendorProfileId, deliveryLocationId and at \
                    least one line with a quantity of one or more and at least one article identifier.
                    Emits a SUPPLIER_STOCK_INQUIRY event; the inquiry creates nothing and changes nothing, so it is \
                    safe to repeat, and repeating it within the cache window returns the same answer without a \
                    second vendor call.
                    Returns 200 with a status for every outcome including vendor failure — OK means the vendor \
                    answered and the per-article outcomes are on the lines, SUPPLIER_UNAVAILABLE means it did not \
                    answer usefully, NOT_LISTED means it carries none of the inquired articles, \
                    CAPABILITY_NOT_CONFIGURED means this deployment has not bound stock inquiry for the vendor, and \
                    CONFIGURATION_ERROR means the profile is bound and wrong and is raised before any vendor call; a \
                    null quantity means the vendor stated nothing while a quantity of zero means it stated that it \
                    has none, and only the second justifies telling a customer an article is out of stock.
                    Returns 400 when the body is malformed — a missing identifier, an empty line list, a line with \
                    no article identity, or a quantity below one.
                    Returns 403 when the caller lacks supplier:stock:inquire.
                    """,
            requestBody =
                    @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            description = "The inquiry: which vendor to ask, which receiving location the question is"
                                    + " about, and the articles to ask about.",
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = StockInquiryRequest.class),
                                            examples = @ExampleObject(name = "oneArticle", value = REQUEST_EXAMPLE))))
    @ApiResponse(
            responseCode = "200",
            description = "The inquiry was processed. Read `status` before reading the lines — a non-OK status"
                    + " carries no vendor data, and that is the normal degraded case rather than an error.",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = StockInquiryResponse.class),
                            examples = @ExampleObject(name = "answered", value = ANSWERED_EXAMPLE)))
    @ApiResponse(
            responseCode = "400",
            description = "The request body is malformed: a missing identifier, an empty line list, a line with no"
                    + " article identity, or a quantity below 1.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "401", description = UNAUTHENTICATED_DESCRIPTION, content = @Content)
    @ApiResponse(
            responseCode = "403",
            description = "The caller lacks `supplier:stock:inquire`.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<StockInquiryResponse> inquireSupplierStock(@Valid @RequestBody StockInquiryRequest request) {
        return ResponseEntity.ok(stockService.inquireLiveStock(request));
    }
}
