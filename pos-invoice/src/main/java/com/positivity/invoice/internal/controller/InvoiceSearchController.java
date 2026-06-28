package com.positivity.invoice.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.invoice.internal.dto.InvoiceSearchResult;
import com.positivity.invoice.service.InvoiceSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    @Operation(
            summary = "Search invoices",
            description =
                    "Paginated free-text search for invoices matching the invoice number, "
                            + "customer name, or workorder number.")
    @ApiResponse(responseCode = "200", description = "Page of invoice search results returned.")
    @GetMapping("/search")
    @PreAuthorize("hasAuthority('invoice:manage')")
    @EmitEvent(id = "INVOICE_SEARCH", apiVersion = "1")
    public Page<InvoiceSearchResult> searchInvoices(
            @Parameter(
                            description =
                                    "Free-text query matching invoice number, customer name, or workorder number (optional)")
                    @RequestParam(required = false)
                    @Nullable
                    String q,
            @Parameter(schema = @Schema(implementation = Pageable.class)) @PageableDefault(size = 25)
                    Pageable pageable) {
        return invoiceSearchService.search(q == null ? "" : q.trim(), pageable);
    }
}
