package com.positivity.invoice.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.invoice.internal.dto.InvoiceLineSearchResult;
import com.positivity.invoice.internal.dto.InvoiceSearchResult;
import com.positivity.invoice.internal.security.InvoicePermissions;
import com.positivity.invoice.service.InvoiceSearchService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Free-text invoice search backing the billing invoice finder. Matches the query against the
 * invoice number, the customer name, or the workorder number.
 */
@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(
        name = "bearerAuth",
        scopes = {"invoice:manage"})
@RequestMapping("/v1/invoices")
@Tag(name = "Invoice Search", description = "Invoice finder search and retrieval")
@RequiredArgsConstructor
public class InvoiceSearchController {

    private final InvoiceSearchService invoiceSearchService;

    /** Hard cap on page size so a caller cannot request an unbounded enrichment fan-out. */
    private static final int MAX_PAGE_SIZE = 50;

    @Operation(operationId = "searchInvoices", summary = "Search Invoices by Free Text", description = """
                    Searches invoices by a free-text term matched against the invoice number, the customer name \
                    (resolved via the customer service), or the workorder number, returning a page of finder rows.
                    Use this tool to locate an invoice when its id is unknown; use getInvoice instead once the \
                    invoiceId is known, and searchInvoiceLines for line-level warranty correlation.
                    Preconditions: none — but a blank or missing q short-circuits to an empty page rather than \
                    listing all invoices.
                    Required inputs: q (free-text term) plus optional page, size and sort parameters; size defaults \
                    to 25, is hard-capped at 50, and the default sort is createdAt descending.
                    Emits an INVOICE_SEARCH audit event; no state changes — this is a read-only projection.
                    Returns 400 when pagination parameters are malformed.
                    """)
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Page of invoice search results returned."),
        @ApiResponse(
                responseCode = "400",
                description = "Invalid pagination parameters.",
                content = @Content(schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(
                responseCode = "403",
                description = "Caller lacks the invoice:manage authority.",
                content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @GetMapping("/search")
    @PreAuthorize("hasAuthority('" + InvoicePermissions.MANAGE + "')")
    @EmitEvent(id = "INVOICE_SEARCH", apiVersion = "1")
    public Page<InvoiceSearchResult> searchInvoices(
            @Parameter(
                            description =
                                    "Free-text query matching invoice number, customer name, or workorder number (optional)")
                    @RequestParam(required = false)
                    @Nullable
                    String q,
            @Parameter(schema = @Schema(implementation = Pageable.class))
                    @PageableDefault(size = 25, sort = "createdAt", direction = Sort.Direction.DESC)
                    Pageable pageable) {
        return invoiceSearchService.search(q == null ? "" : q.trim(), capPageSize(pageable));
    }

    @Operation(operationId = "searchInvoiceLines", summary = "Search Invoice Lines by Party", description = """
                    Returns invoice line items belonging to one customer party, flattened with the owning invoice's \
                    number, status and creation time, newest invoice first and bounded to the newest 200 lines; \
                    built for warranty-claim origin-line correlation.
                    Use this tool to find the invoice line a warranty claim originates from; use searchInvoices \
                    instead for invoice-level free-text search.
                    Preconditions: none — an unknown party simply returns an empty list.
                    Required inputs: partyId (UUID) query parameter; q is an optional SKU or description term \
                    narrowing the lines.
                    Emits an INVOICE_ITEM_SEARCH audit event; no state changes — this is a read-only projection.
                    Returns 400 when partyId is missing or malformed.
                    """)
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "List of matching invoice line items returned."),
        @ApiResponse(
                responseCode = "400",
                description = "Missing or malformed partyId.",
                content = @Content(schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(
                responseCode = "403",
                description = "Caller lacks the invoice:manage authority.",
                content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @GetMapping("/items/search")
    @PreAuthorize("hasAuthority('" + InvoicePermissions.MANAGE + "')")
    @EmitEvent(id = "INVOICE_ITEM_SEARCH", apiVersion = "1")
    public List<InvoiceLineSearchResult> searchInvoiceLines(
            @Parameter(description = "Customer party identifier owning the invoices (required)") @RequestParam @NonNull
                    UUID partyId,
            @Parameter(description = "SKU / description term narrowing the lines (optional)")
                    @RequestParam(required = false)
                    @Nullable
                    String q) {
        return invoiceSearchService.searchLinesByParty(partyId, q);
    }

    /** Clamp the requested page size to {@link #MAX_PAGE_SIZE}, preserving page index and sort. */
    private static Pageable capPageSize(Pageable pageable) {
        if (pageable.isPaged() && pageable.getPageSize() > MAX_PAGE_SIZE) {
            return PageRequest.of(pageable.getPageNumber(), MAX_PAGE_SIZE, pageable.getSort());
        }
        return pageable;
    }
}
