package com.positivity.supplier.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import com.positivity.supplier.internal.security.SupplierPermissions;
import com.positivity.supplier.internal.service.SupplierExchangeAuditService;
import com.positivity.supplier.internal.service.model.ExchangeAuditAccessView;
import com.positivity.supplier.internal.service.model.ExchangeAuditPayloadView;
import com.positivity.supplier.internal.service.model.ExchangeAuditSummary;
import com.positivity.supplier.internal.service.model.PagedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exchange-audit read API (ADR-0050 §7) — what this deployment sent to suppliers, what came back, and
 * who has since looked at it.
 *
 * <h2>Permission, and why it is not profile admin</h2>
 *
 * Every endpoint here requires {@code supplier:audit:read} (catalog bit 445), which
 * {@code supplier:profile:read} does <strong>not</strong> imply. Configuring an integration and reading
 * the commercial documents that flowed over it are different authorities: the payloads carry a trading
 * partner's prices, order volumes and account terms.
 *
 * <h2>Not nested under {@code /profiles/{vendorProfileId}}</h2>
 *
 * The other supplier admin controllers nest their resources under the owning profile. This one does not,
 * and that follows directly from the persistence decision: exchange-audit rows carry no foreign key to
 * the profile so that history outlives configuration (ADR-0050 §7), and profile deletion is a hard
 * delete. A path that implied "child of an existing profile" would promise something the data model
 * deliberately refuses — the audit trail of a deleted supplier is exactly what an investigation asks
 * for. The profile is therefore a query filter, not an owning path segment.
 *
 * <h2>One endpoint here is a write</h2>
 *
 * {@code GET .../payload} serves commercial content and records that it did so, in the same transaction
 * (ADR-0050 §7). It is safe to retry and it changes no supplier state, so {@code GET} is right, but it is
 * not free: each call adds a row naming the caller, and the content is withheld if that row cannot be
 * written.
 */
@Tag(
        name = "Supplier Exchange Audit",
        description = "Read the supplier exchange-audit trail. Metadata is free to browse; reading stored payload"
                + " content requires the same permission and is itself recorded.")
@RestController
@RequiredArgsConstructor
@Validated
@SecurityRequirement(name = "bearerAuth")
@RequestMapping("/v1/supplier/admin/audit")
public class SupplierExchangeAuditController {

    /** Bound so a caller cannot ask for an unbounded page of a table that grows with every retry. */
    private static final String MAX_PAGE_SIZE = "200";

    private final SupplierExchangeAuditService auditService;

    @Operation(
            operationId = "listSupplierExchanges",
            summary = "List supplier exchanges in a window",
            description = """
                    Returns a page of exchange-audit metadata for one vendor profile within a half-open time window,
                    newest first.
                    Use this tool to find the exchangeAuditId of a supplier call; use traceSupplierCorrelation instead
                    when every attempt of one logical call is wanted, and do not expect payload content here.
                    Preconditions: the caller must hold supplier:audit:read, which supplier:profile:read does not
                    imply; the vendor profile need not still exist, because audit history outlives configuration.
                    Required inputs: vendorProfileId, from (inclusive) and to (exclusive) as ISO-8601 instants;
                    capability is optional, page defaults to 0 and size defaults to 50 with a maximum of 200.
                    Emits a SUPPLIER_AUDIT_EXCHANGE_LIST event; no payload content is served, so no access row is
                    written against the exchanges listed.
                    Returns 400 when the window end is not after its start, the capability key is unrecognised, or the
                    page size is out of range.
                    """)
    @ApiResponse(responseCode = "200", description = "A page of exchange metadata, newest first.")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid request — window end not after its start, unknown capability key, or a page"
                    + " size outside the permitted range.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = "Authentication is missing or the bearer token is invalid."
                    + " The response has NO body: the gateway rejects unauthenticated calls with a bodiless"
                    + " status, so clients must not attempt to parse an error envelope here.",
            content = @Content(schema = @Schema(hidden = true)))
    @ApiResponse(
            responseCode = "403",
            description = "Authenticated caller lacks supplier:audit:read. Profile-admin permissions do not"
                    + " grant audit access.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @PreAuthorize("hasAuthority('" + SupplierPermissions.AUDIT_READ + "')")
    @EmitEvent(id = "SUPPLIER_AUDIT_EXCHANGE_LIST", apiVersion = "1")
    @GetMapping("/exchanges")
    public ResponseEntity<PagedResponse<ExchangeAuditSummary>> listExchanges(
            @Parameter(
                            description = "Vendor profile whose exchanges to list. A query filter rather than a path"
                                    + " segment because audit history deliberately outlives the profile:"
                                    + " exchanges of a deleted profile are still listed here.",
                            required = true,
                            schema =
                                    @Schema(
                                            type = "string",
                                            format = "uuid",
                                            example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b"))
                    @RequestParam
                    @NotNull
                    UUID vendorProfileId,
            @Parameter(
                            description = "Window start, INCLUSIVE. ISO-8601 instant.",
                            required = true,
                            schema = @Schema(type = "string", format = "date-time", example = "2026-08-01T00:00:00Z"))
                    @RequestParam
                    @NotNull
                    Instant from,
            @Parameter(
                            description = "Window end, EXCLUSIVE, and must be after 'from'. Half-open so adjacent"
                                    + " windows tile without listing a boundary attempt twice.",
                            required = true,
                            schema = @Schema(type = "string", format = "date-time", example = "2026-09-01T00:00:00Z"))
                    @RequestParam
                    @NotNull
                    Instant to,
            @Parameter(
                            description = "Canonical capability key to narrow to. Omit for all capabilities. An"
                                    + " unrecognised key is rejected rather than silently matching nothing.",
                            schema = @Schema(type = "string", example = "STOCK_INQUIRY"))
                    @RequestParam(required = false)
                    String capability,
            @Parameter(description = "Zero-based page index.", schema = @Schema(type = "integer", example = "0"))
                    @RequestParam(defaultValue = "0")
                    @Min(0)
                    int page,
            @Parameter(
                            description = "Page size, at most " + MAX_PAGE_SIZE + ".",
                            schema = @Schema(type = "integer", example = "50"))
                    @RequestParam(defaultValue = "50")
                    @Min(1)
                    @Max(200)
                    int size) {
        return ResponseEntity.ok(auditService.listExchanges(vendorProfileId, from, to, capability, page, size));
    }

    @Operation(
            operationId = "traceSupplierCorrelation",
            summary = "Trace one logical call by correlation id",
            description = """
                    Returns every exchange attempt sharing one correlation id, oldest first, which is the order in
                    which a retry sequence actually happened.
                    Use this tool when a single logical supplier call needs to be reconstructed across its retries; use
                    listSupplierExchanges instead to search a time window.
                    Preconditions: the caller must hold supplier:audit:read; an unknown correlation id is not an error
                    and yields an empty page.
                    Required inputs: correlationId path parameter; page defaults to 0 and size defaults to 50 with a
                    maximum of 200.
                    Emits a SUPPLIER_AUDIT_EXCHANGE_TRACE event; metadata only, so no payload access is recorded.
                    Returns 400 when the page size is outside the permitted range.
                    """)
    @ApiResponse(responseCode = "200", description = "A page of exchange metadata, oldest first.")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid request — page size outside the permitted range.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = "Authentication is missing or the bearer token is invalid."
                    + " The response has NO body: the gateway rejects unauthenticated calls with a bodiless"
                    + " status, so clients must not attempt to parse an error envelope here.",
            content = @Content(schema = @Schema(hidden = true)))
    @ApiResponse(
            responseCode = "403",
            description = "Authenticated caller lacks supplier:audit:read.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @PreAuthorize("hasAuthority('" + SupplierPermissions.AUDIT_READ + "')")
    @EmitEvent(id = "SUPPLIER_AUDIT_EXCHANGE_TRACE", apiVersion = "1")
    @GetMapping("/exchanges/by-correlation/{correlationId}")
    public ResponseEntity<PagedResponse<ExchangeAuditSummary>> traceCorrelation(
            @Parameter(
                            description = "Correlation id shared by every attempt of one logical supplier call.",
                            required = true,
                            schema = @Schema(type = "string", example = "018f0a1b2c3d7e4f8a9b0c1d2e3f4a71"))
                    @PathVariable
                    @NotBlank
                    String correlationId,
            @Parameter(description = "Zero-based page index.", schema = @Schema(type = "integer", example = "0"))
                    @RequestParam(defaultValue = "0")
                    @Min(0)
                    int page,
            @Parameter(
                            description = "Page size, at most " + MAX_PAGE_SIZE + ".",
                            schema = @Schema(type = "integer", example = "50"))
                    @RequestParam(defaultValue = "50")
                    @Min(1)
                    @Max(200)
                    int size) {
        return ResponseEntity.ok(auditService.traceCorrelation(correlationId, page, size));
    }

    @Operation(operationId = "getSupplierExchange", summary = "Get one exchange's audit metadata", description = """
                    Returns the metadata of a single supplier exchange attempt, without any payload content.
                    Use this tool to check requestPayloadPresent and responsePayloadPresent before calling
                    readSupplierExchangePayload, which is recorded; do not use it to read the documents themselves.
                    Preconditions: the caller must hold supplier:audit:read and the exchange-audit record must exist.
                    Required inputs: exchangeAuditId (UUIDv7) path parameter; there is no request body.
                    Emits a SUPPLIER_AUDIT_EXCHANGE_GET event; because no content is served, no access row is written.
                    Returns 404 when no exchange-audit record has that id.
                    """)
    @ApiResponse(responseCode = "200", description = "Exchange metadata returned.")
    @ApiResponse(
            responseCode = "404",
            description = "No exchange-audit record with that id.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = "Authentication is missing or the bearer token is invalid."
                    + " The response has NO body: the gateway rejects unauthenticated calls with a bodiless"
                    + " status, so clients must not attempt to parse an error envelope here.",
            content = @Content(schema = @Schema(hidden = true)))
    @ApiResponse(
            responseCode = "403",
            description = "Authenticated caller lacks supplier:audit:read.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @PreAuthorize("hasAuthority('" + SupplierPermissions.AUDIT_READ + "')")
    @EmitEvent(id = "SUPPLIER_AUDIT_EXCHANGE_GET", apiVersion = "1")
    @GetMapping("/exchanges/{exchangeAuditId}")
    public ResponseEntity<ExchangeAuditSummary> getExchange(
            @Parameter(
                            description = "Exchange-audit record identifier (UUIDv7).",
                            required = true,
                            schema =
                                    @Schema(
                                            type = "string",
                                            format = "uuid",
                                            example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a70"))
                    @PathVariable
                    @NotNull
                    UUID exchangeAuditId) {
        return ResponseEntity.ok(auditService.getExchange(exchangeAuditId));
    }

    @Operation(
            operationId = "readSupplierExchangePayload",
            summary = "Read one exchange's stored payload content",
            description = """
                    Returns the stored request and response documents of one supplier exchange.
                    Use this tool only when the documents themselves are needed; use getSupplierExchange instead for
                    routine inspection, because this call is itself recorded and each read adds an access row naming the
                    caller.
                    Preconditions: the caller must hold supplier:audit:read, the record must exist, and the access row
                    must be writable, since content is withheld when it cannot be written.
                    Required inputs: exchangeAuditId (UUIDv7) path parameter; there is no request body and no way to
                    request the unredacted originals.
                    Emits a SUPPLIER_AUDIT_PAYLOAD_READ event and writes an access row in the same transaction; null
                    payload fields are normal, meaning a METADATA_ONLY binding, a bodiless exchange, or content already
                    purged after 400 days, and a redacted flag means sensitive fields were replaced at capture time so
                    the originals were never stored.
                    Returns 404 when no record has that id, and 500 when stored content exists but cannot be decrypted,
                    in which case the read is still recorded with an UNREADABLE outcome.
                    """)
    @ApiResponse(responseCode = "200", description = "Stored content returned, and the read has been recorded.")
    @ApiResponse(
            responseCode = "404",
            description = "No exchange-audit record with that id. A record that exists but holds no payload is"
                    + " NOT a 404 — it returns 200 with null content.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "500",
            description = "Stored content exists but could not be decrypted — a malformed envelope, a key this"
                    + " deployment does not hold, or a failed integrity check. The typed code distinguishes"
                    + " which; the detail is logged server-side and never returned. The read is still recorded,"
                    + " with an UNREADABLE outcome.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = "Authentication is missing or the bearer token is invalid."
                    + " The response has NO body: the gateway rejects unauthenticated calls with a bodiless"
                    + " status, so clients must not attempt to parse an error envelope here.",
            content = @Content(schema = @Schema(hidden = true)))
    @ApiResponse(
            responseCode = "403",
            description = "Authenticated caller lacks supplier:audit:read. A denied read discloses nothing and is"
                    + " deliberately NOT written to the access trail.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @PreAuthorize("hasAuthority('" + SupplierPermissions.AUDIT_READ + "')")
    @EmitEvent(id = "SUPPLIER_AUDIT_PAYLOAD_READ", apiVersion = "1")
    @GetMapping("/exchanges/{exchangeAuditId}/payload")
    public ResponseEntity<ExchangeAuditPayloadView> readPayload(
            @Parameter(
                            description = "Exchange-audit record whose stored content to read.",
                            required = true,
                            schema =
                                    @Schema(
                                            type = "string",
                                            format = "uuid",
                                            example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a70"))
                    @PathVariable
                    @NotNull
                    UUID exchangeAuditId) {
        return ResponseEntity.ok(auditService.readPayload(exchangeAuditId));
    }

    @Operation(
            operationId = "listSupplierExchangeAccesses",
            summary = "List who read one exchange's payload content",
            description = """
                    Returns the access trail for one exchange, newest first: who read its payload, when, and what the
                    read yielded.
                    Use this tool to audit who has seen a trading partner's documents; do not expect metadata browsing
                    or denied attempts here, because a 403 disclosed nothing and is deliberately not recorded.
                    Preconditions: the caller must hold supplier:audit:read; an unknown exchangeAuditId returns an
                    empty page rather than 404, because access rows carry no foreign key to the exchange.
                    Required inputs: exchangeAuditId (UUIDv7) path parameter; page defaults to 0 and size defaults to
                    50 with a maximum of 200.
                    Emits a SUPPLIER_AUDIT_ACCESS_LIST event; reading this trail is not itself recorded, or every
                    inspection would generate the noise the next one has to sift.
                    Returns 400 when the page size is outside the permitted range.
                    """)
    @ApiResponse(responseCode = "200", description = "A page of access records, newest first.")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid request — page size outside the permitted range.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = "Authentication is missing or the bearer token is invalid."
                    + " The response has NO body: the gateway rejects unauthenticated calls with a bodiless"
                    + " status, so clients must not attempt to parse an error envelope here.",
            content = @Content(schema = @Schema(hidden = true)))
    @ApiResponse(
            responseCode = "403",
            description = "Authenticated caller lacks supplier:audit:read.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @PreAuthorize("hasAuthority('" + SupplierPermissions.AUDIT_READ + "')")
    @EmitEvent(id = "SUPPLIER_AUDIT_ACCESS_LIST", apiVersion = "1")
    @GetMapping("/exchanges/{exchangeAuditId}/accesses")
    public ResponseEntity<PagedResponse<ExchangeAuditAccessView>> listAccesses(
            @Parameter(
                            description = "Exchange-audit record whose access trail to list. An unknown id returns"
                                    + " an empty page rather than 404: 'nobody read it' is the honest answer, and"
                                    + " access rows carry no foreign key to the exchange by design.",
                            required = true,
                            schema =
                                    @Schema(
                                            type = "string",
                                            format = "uuid",
                                            example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a70"))
                    @PathVariable
                    @NotNull
                    UUID exchangeAuditId,
            @Parameter(description = "Zero-based page index.", schema = @Schema(type = "integer", example = "0"))
                    @RequestParam(defaultValue = "0")
                    @Min(0)
                    int page,
            @Parameter(
                            description = "Page size, at most " + MAX_PAGE_SIZE + ".",
                            schema = @Schema(type = "integer", example = "50"))
                    @RequestParam(defaultValue = "50")
                    @Min(1)
                    @Max(200)
                    int size) {
        return ResponseEntity.ok(auditService.listAccesses(exchangeAuditId, page, size));
    }
}
