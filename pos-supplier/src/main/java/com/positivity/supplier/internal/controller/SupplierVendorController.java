package com.positivity.supplier.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import com.positivity.supplier.internal.security.SupplierPermissions;
import com.positivity.supplier.internal.service.model.PagedResponse;
import com.positivity.supplier.internal.vendor.service.SupplierVendorService;
import com.positivity.supplier.internal.vendor.service.model.RemitApprovalRequest;
import com.positivity.supplier.internal.vendor.service.model.RemitChangeRequest;
import com.positivity.supplier.internal.vendor.service.model.RemitChangeStatus;
import com.positivity.supplier.internal.vendor.service.model.RemitChangeView;
import com.positivity.supplier.internal.vendor.service.model.RemitRejectionRequest;
import com.positivity.supplier.internal.vendor.service.model.VendorCreateRequest;
import com.positivity.supplier.internal.vendor.service.model.VendorFactReplayResult;
import com.positivity.supplier.internal.vendor.service.model.VendorStatus;
import com.positivity.supplier.internal.vendor.service.model.VendorStatusChangeRequest;
import com.positivity.supplier.internal.vendor.service.model.VendorUpdateRequest;
import com.positivity.supplier.internal.vendor.service.model.VendorView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The vendor master (#2516, ADR-0070 Decision 2, SPEC §4.9): one vendor for every party the shop
 * buys from or pays, and remit-to changes a second person approves. No endpoint deletes a vendor.
 */
@Tag(
        name = "Supplier Vendors",
        description = "The vendor master: vendors, their status, and remit-to changes a second person approves")
@RestController
@RequiredArgsConstructor
@Validated
@SecurityRequirement(name = "bearerAuth")
@RequestMapping("/v1/supplier/vendors")
public class SupplierVendorController {

    private static final String UNAUTHENTICATED = "Authentication is missing or the bearer token is invalid."
            + " The response has NO body: the gateway rejects unauthenticated calls with a bodiless"
            + " status, so clients must not attempt to parse an error envelope here.";
    private static final String FORBIDDEN = "Authenticated caller lacks the required supplier permission.";
    private static final String JSON = "application/json";
    private static final String VENDOR_ID_DESCRIPTION =
            "Vendor identifier (UUIDv7). Must reference a vendor of the caller's tenant.";
    private static final String CHANGE_ID_DESCRIPTION =
            "Remit-to change identifier (UUIDv7). Must reference a change of the addressed vendor.";
    private static final String UUID_EXAMPLE = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b";
    private static final String VENDOR_NOT_FOUND = "SUPPLIER_VENDOR_NOT_FOUND: no vendor with that id in the tenant.";

    private static final String CREATE_EXAMPLE = """
            {"legalName":"Michelin North America, Inc.","displayName":"Michelin",
             "taxRegistrations":[{"scheme":"EIN","number":"12-3456789"}],
             "remitTo":{"payeeName":"Michelin North America, Inc.","addressLine1":"PO Box 100",
                        "city":"Greenville","region":"SC","postalCode":"29615","countryCode":"US"},
             "defaultPaymentTerms":"NET30","defaultCurrency":"USD"}
            """;
    private static final String UPDATE_EXAMPLE = """
            {"legalName":"Michelin North America, Inc.","displayName":"Michelin","taxRegistrations":[],
             "defaultPaymentTerms":"NET45","defaultCurrency":"USD","version":3}
            """;
    private static final String STATUS_EXAMPLE = """
            {"reason":"Vendor merged into Michelin; use MICHELIN from now on."}
            """;
    private static final String REMIT_CHANGE_EXAMPLE = """
            {"remitTo":{"payeeName":"Michelin North America, Inc.","addressLine1":"PO Box 200",
                        "city":"Greenville","region":"SC","postalCode":"29615","countryCode":"US"},
             "reason":"Vendor letter of 2026-10-01 gives a new lockbox address."}
            """;
    private static final String APPROVAL_EXAMPLE = """
            {"verificationNote":"Called the vendor's AR line on the number on file; lockbox confirmed."}
            """;
    private static final String REJECTION_EXAMPLE = """
            {"note":"The vendor's AR line knows nothing of this change."}
            """;

    private final SupplierVendorService vendorService;

    // ── Reads ───────────────────────────────────────────────────────────────────────

    @Operation(operationId = "listSupplierVendors", summary = "List vendors", description = """
                    Returns one page of the tenant's vendors ordered by vendorNumber, each with its approved
                    remit-to, default terms and status.
                    Use this tool to find a vendor by number or name, or to list active or inactive vendors; use
                    getSupplierVendor instead when the vendorId is already known.
                    Preconditions: none; only the caller's tenant's vendors are visible.
                    Required inputs: none. q matches vendorNumber, displayName or legalName (case-insensitive,
                    contains); status narrows to ACTIVE or INACTIVE; page is zero-based and size is 1 to 200
                    (default 50).
                    Emits a SUPPLIER_VENDOR_LIST audit event; nothing is changed.
                    Returns 200 with an empty page when nothing matches, and 400 when page or size is out of
                    range.
                    """)
    @ApiResponse(responseCode = "200", description = "A page of vendors.")
    @ApiResponse(
            responseCode = "400",
            description = "page or size out of range.",
            content = @Content(mediaType = JSON, schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = UNAUTHENTICATED,
            content = @Content(schema = @Schema(hidden = true)))
    @ApiResponse(
            responseCode = "403",
            description = FORBIDDEN,
            content = @Content(mediaType = JSON, schema = @Schema(implementation = ApiError.class)))
    @PreAuthorize("hasAuthority('" + SupplierPermissions.VENDOR_READ + "')")
    @EmitEvent(id = "SUPPLIER_VENDOR_LIST", apiVersion = "1")
    @GetMapping
    public ResponseEntity<PagedResponse<VendorView>> listVendors(
            @Parameter(
                            description = "Text matched against vendorNumber, displayName and legalName.",
                            example = "michelin")
                    @RequestParam(required = false)
                    @Nullable
                    String q,
            @Parameter(description = "Only vendors in this status.", example = "ACTIVE")
                    @RequestParam(required = false)
                    @Nullable
                    VendorStatus status,
            @Parameter(description = "Zero-based page index.", example = "0") @RequestParam(defaultValue = "0")
                    int page,
            @Parameter(description = "Page size, 1 to 200.", example = "50") @RequestParam(defaultValue = "50")
                    int size) {
        return ResponseEntity.ok(vendorService.listVendors(q, status, page, size));
    }

    @Operation(operationId = "getSupplierVendor", summary = "Get vendor", description = """
                    Returns one vendor with its tax registrations, approved remit-to and version, default terms
                    and currency, and status.
                    Use this tool when the vendorId is known, for example from a bill, a purchase order or a
                    profile; use listSupplierVendors instead to search by number or name.
                    Preconditions: the vendor must exist in the caller's tenant.
                    Required inputs: vendorId (UUIDv7) path parameter; there is no request body.
                    Emits a SUPPLIER_VENDOR_GET audit event; nothing is changed. A remit-to change waiting for
                    approval is not shown here; read it with listSupplierVendorRemitChanges.
                    Returns 404 SUPPLIER_VENDOR_NOT_FOUND when the tenant has no vendor with that id.
                    """)
    @ApiResponse(responseCode = "200", description = "The vendor.")
    @ApiResponse(
            responseCode = "404",
            description = VENDOR_NOT_FOUND,
            content = @Content(mediaType = JSON, schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = UNAUTHENTICATED,
            content = @Content(schema = @Schema(hidden = true)))
    @ApiResponse(
            responseCode = "403",
            description = FORBIDDEN,
            content = @Content(mediaType = JSON, schema = @Schema(implementation = ApiError.class)))
    @PreAuthorize("hasAuthority('" + SupplierPermissions.VENDOR_READ + "')")
    @EmitEvent(id = "SUPPLIER_VENDOR_GET", apiVersion = "1")
    @GetMapping("/{vendorId}")
    public ResponseEntity<VendorView> getVendor(
            @Parameter(
                            description = VENDOR_ID_DESCRIPTION,
                            required = true,
                            schema = @Schema(type = "string", format = "uuid", example = UUID_EXAMPLE))
                    @PathVariable
                    @NotNull
                    UUID vendorId) {
        return ResponseEntity.ok(vendorService.getVendor(vendorId));
    }

    @Operation(
            operationId = "listSupplierVendorRemitChanges",
            summary = "List a vendor's remit-to changes",
            description = """
                    Returns a vendor's remit-to change requests, newest first, with the proposed address, the
                    reason, the requester and, once decided, the decider and their note.
                    Use this tool to find a change waiting for approval (status=PENDING) or to read a vendor's
                    remit-to history; use getSupplierVendor instead for the remit-to in force.
                    Preconditions: the vendor must exist in the caller's tenant.
                    Required inputs: vendorId (UUIDv7) path parameter; status optionally narrows to PENDING,
                    APPROVED or REJECTED.
                    Emits a SUPPLIER_VENDOR_REMIT_CHANGE_LIST audit event; nothing is changed.
                    Returns 200 with an empty list when the vendor has no changes, and 404
                    SUPPLIER_VENDOR_NOT_FOUND when the vendor does not exist.
                    """)
    @ApiResponse(responseCode = "200", description = "The vendor's remit-to changes, newest first.")
    @ApiResponse(
            responseCode = "404",
            description = VENDOR_NOT_FOUND,
            content = @Content(mediaType = JSON, schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = UNAUTHENTICATED,
            content = @Content(schema = @Schema(hidden = true)))
    @ApiResponse(
            responseCode = "403",
            description = FORBIDDEN,
            content = @Content(mediaType = JSON, schema = @Schema(implementation = ApiError.class)))
    @PreAuthorize("hasAuthority('" + SupplierPermissions.VENDOR_READ + "')")
    @EmitEvent(id = "SUPPLIER_VENDOR_REMIT_CHANGE_LIST", apiVersion = "1")
    @GetMapping("/{vendorId}/remit-to-changes")
    public ResponseEntity<List<RemitChangeView>> listRemitChanges(
            @Parameter(
                            description = VENDOR_ID_DESCRIPTION,
                            required = true,
                            schema = @Schema(type = "string", format = "uuid", example = UUID_EXAMPLE))
                    @PathVariable
                    @NotNull
                    UUID vendorId,
            @Parameter(description = "Only changes in this status.", example = "PENDING")
                    @RequestParam(required = false)
                    @Nullable
                    RemitChangeStatus status) {
        return ResponseEntity.ok(vendorService.listRemitChanges(vendorId, status));
    }

    // ── Vendor commands ─────────────────────────────────────────────────────────────

    @Operation(operationId = "createSupplierVendor", summary = "Create vendor", description = """
                    Creates an ACTIVE vendor, with or without a supplier connection, and publishes
                    supplier.vendor.updated.
                    Use this tool to add a party the shop buys from or pays; do not use it to change a vendor,
                    which is updateSupplierVendor, or to connect a supplier, which is a vendor profile naming this
                    vendor.
                    Preconditions: a vendorNumber, when given, must not be used by another vendor of the tenant.
                    Required inputs: legalName, displayName, defaultPaymentTerms (DUE_ON_RECEIPT or NET1 to
                    NET120) and defaultCurrency (ISO 4217); vendorNumber is optional and allocated as V-000001,
                    V-000002 and so on when omitted, and never changes afterwards; taxRegistrations and remitTo
                    are optional.
                    Emits a SUPPLIER_VENDOR_CREATE audit event and queues one supplier.vendor.updated fact in the
                    same transaction; a remitTo given here is stored as version 1 without approval.
                    Returns 201 with the vendor, 400 VALIDATION_ERROR when a field is missing or malformed, and
                    409 SUPPLIER_VENDOR_NUMBER_TAKEN when the number is in use.
                    """)
    @ApiResponse(responseCode = "201", description = "Vendor created.")
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_ERROR: a field is missing or malformed.",
            content = @Content(mediaType = JSON, schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "SUPPLIER_VENDOR_NUMBER_TAKEN: another vendor of the tenant uses the number.",
            content = @Content(mediaType = JSON, schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = UNAUTHENTICATED,
            content = @Content(schema = @Schema(hidden = true)))
    @ApiResponse(
            responseCode = "403",
            description = FORBIDDEN,
            content = @Content(mediaType = JSON, schema = @Schema(implementation = ApiError.class)))
    @PreAuthorize("hasAuthority('" + SupplierPermissions.VENDOR_WRITE + "')")
    @EmitEvent(id = "SUPPLIER_VENDOR_CREATE", apiVersion = "1")
    @PostMapping
    public ResponseEntity<VendorView> createVendor(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Vendor to create.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = JSON,
                                            examples =
                                                    @ExampleObject(
                                                            name = "Vendor with a remit-to",
                                                            value = CREATE_EXAMPLE)))
                    @Valid
                    @NotNull
                    @RequestBody
                    VendorCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(vendorService.createVendor(request));
    }

    @Operation(operationId = "updateSupplierVendor", summary = "Update vendor", description = """
                    Replaces a vendor's legal and display names, tax registrations, default payment terms and
                    default currency, and publishes supplier.vendor.updated.
                    Use this tool to correct or complete a vendor; do not use it for the remit-to, which needs a
                    remit-to change a second person approves, or for the status, which is deactivation and
                    reactivation. The vendorNumber never changes.
                    Preconditions: the vendor must exist, and version must be the version the caller read.
                    Required inputs: vendorId (UUIDv7) path parameter plus the full body, because every field is
                    replaced; omitting taxRegistrations clears them.
                    Emits a SUPPLIER_VENDOR_UPDATE audit event and queues one supplier.vendor.updated fact in the
                    same transaction.
                    Returns 200 with the vendor, 400 VALIDATION_ERROR for a malformed field, 404
                    SUPPLIER_VENDOR_NOT_FOUND, and 409 CONFLICT when version is stale.
                    """)
    @ApiResponse(responseCode = "200", description = "Vendor updated.")
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_ERROR: a field is missing or malformed.",
            content = @Content(mediaType = JSON, schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = VENDOR_NOT_FOUND,
            content = @Content(mediaType = JSON, schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "CONFLICT: the vendor was changed since the caller read it (stale version).",
            content = @Content(mediaType = JSON, schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = UNAUTHENTICATED,
            content = @Content(schema = @Schema(hidden = true)))
    @ApiResponse(
            responseCode = "403",
            description = FORBIDDEN,
            content = @Content(mediaType = JSON, schema = @Schema(implementation = ApiError.class)))
    @PreAuthorize("hasAuthority('" + SupplierPermissions.VENDOR_WRITE + "')")
    @EmitEvent(id = "SUPPLIER_VENDOR_UPDATE", apiVersion = "1")
    @PutMapping("/{vendorId}")
    public ResponseEntity<VendorView> updateVendor(
            @Parameter(
                            description = VENDOR_ID_DESCRIPTION,
                            required = true,
                            schema = @Schema(type = "string", format = "uuid", example = UUID_EXAMPLE))
                    @PathVariable
                    @NotNull
                    UUID vendorId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Replacement values and the version the caller read.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = JSON,
                                            examples = @ExampleObject(name = "New terms", value = UPDATE_EXAMPLE)))
                    @Valid
                    @NotNull
                    @RequestBody
                    VendorUpdateRequest request) {
        return ResponseEntity.ok(vendorService.updateVendor(vendorId, request));
    }

    @Operation(operationId = "deactivateSupplierVendor", summary = "Deactivate vendor", description = """
                    Sets an ACTIVE vendor to INACTIVE with a reason and publishes supplier.vendor.updated with
                    status INACTIVE.
                    Use this tool when the shop stops dealing with a vendor, or to retire a duplicate after
                    re-pointing its profiles; do not use it to delete a vendor, which is impossible, and use
                    reactivateSupplierVendor instead to undo it.
                    Preconditions: the vendor must exist and be ACTIVE.
                    Required inputs: vendorId (UUIDv7) path parameter and a reason of at least 10 characters.
                    Emits a SUPPLIER_VENDOR_DEACTIVATE audit event and queues one supplier.vendor.updated fact.
                    The vendor's profiles stay enabled: its EDI documents still arrive, and accounting records
                    them as exceptions.
                    Returns 200 with the vendor, 400 VALIDATION_ERROR for a short reason, 404
                    SUPPLIER_VENDOR_NOT_FOUND, and 409 CONFLICT when the vendor is already inactive.
                    """)
    @ApiResponse(responseCode = "200", description = "Vendor deactivated.")
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_ERROR: the reason is shorter than 10 characters.",
            content = @Content(mediaType = JSON, schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = VENDOR_NOT_FOUND,
            content = @Content(mediaType = JSON, schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "CONFLICT: the vendor is already inactive.",
            content = @Content(mediaType = JSON, schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = UNAUTHENTICATED,
            content = @Content(schema = @Schema(hidden = true)))
    @ApiResponse(
            responseCode = "403",
            description = FORBIDDEN,
            content = @Content(mediaType = JSON, schema = @Schema(implementation = ApiError.class)))
    @PreAuthorize("hasAuthority('" + SupplierPermissions.VENDOR_WRITE + "')")
    @EmitEvent(id = "SUPPLIER_VENDOR_DEACTIVATE", apiVersion = "1")
    @PostMapping("/{vendorId}/deactivation")
    public ResponseEntity<VendorView> deactivateVendor(
            @Parameter(
                            description = VENDOR_ID_DESCRIPTION,
                            required = true,
                            schema = @Schema(type = "string", format = "uuid", example = UUID_EXAMPLE))
                    @PathVariable
                    @NotNull
                    UUID vendorId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Why the vendor is deactivated.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = JSON,
                                            examples =
                                                    @ExampleObject(name = "Duplicate vendor", value = STATUS_EXAMPLE)))
                    @Valid
                    @NotNull
                    @RequestBody
                    VendorStatusChangeRequest request) {
        return ResponseEntity.ok(vendorService.deactivateVendor(vendorId, request));
    }

    @Operation(operationId = "reactivateSupplierVendor", summary = "Reactivate vendor", description = """
                    Sets an INACTIVE vendor back to ACTIVE with a reason and publishes supplier.vendor.updated
                    with status ACTIVE.
                    Use this tool when the shop resumes dealing with a deactivated vendor; do not use it on an
                    active vendor, and use deactivateSupplierVendor instead for the opposite.
                    Preconditions: the vendor must exist and be INACTIVE.
                    Required inputs: vendorId (UUIDv7) path parameter and a reason of at least 10 characters.
                    Emits a SUPPLIER_VENDOR_REACTIVATE audit event and queues one supplier.vendor.updated fact.
                    Returns 200 with the vendor, 400 VALIDATION_ERROR for a short reason, 404
                    SUPPLIER_VENDOR_NOT_FOUND, and 409 CONFLICT when the vendor is already active.
                    """)
    @ApiResponse(responseCode = "200", description = "Vendor reactivated.")
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_ERROR: the reason is shorter than 10 characters.",
            content = @Content(mediaType = JSON, schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = VENDOR_NOT_FOUND,
            content = @Content(mediaType = JSON, schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "CONFLICT: the vendor is already active.",
            content = @Content(mediaType = JSON, schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = UNAUTHENTICATED,
            content = @Content(schema = @Schema(hidden = true)))
    @ApiResponse(
            responseCode = "403",
            description = FORBIDDEN,
            content = @Content(mediaType = JSON, schema = @Schema(implementation = ApiError.class)))
    @PreAuthorize("hasAuthority('" + SupplierPermissions.VENDOR_WRITE + "')")
    @EmitEvent(id = "SUPPLIER_VENDOR_REACTIVATE", apiVersion = "1")
    @PostMapping("/{vendorId}/reactivation")
    public ResponseEntity<VendorView> reactivateVendor(
            @Parameter(
                            description = VENDOR_ID_DESCRIPTION,
                            required = true,
                            schema = @Schema(type = "string", format = "uuid", example = UUID_EXAMPLE))
                    @PathVariable
                    @NotNull
                    UUID vendorId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Why the vendor is reactivated.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = JSON,
                                            examples =
                                                    @ExampleObject(name = "Resumed business", value = STATUS_EXAMPLE)))
                    @Valid
                    @NotNull
                    @RequestBody
                    VendorStatusChangeRequest request) {
        return ResponseEntity.ok(vendorService.reactivateVendor(vendorId, request));
    }

    // ── Remit-to changes ────────────────────────────────────────────────────────────

    @Operation(
            operationId = "requestSupplierVendorRemitChange",
            summary = "Request a remit-to change",
            description = """
                    Records a PENDING change to a vendor's remit-to address with a reason; nothing is applied or
                    published until someone other than the requester approves it.
                    Use this tool whenever a vendor's remit-to must change, including giving a first remit-to to
                    a vendor created without one; do not use updateSupplierVendor for the remit-to, and use
                    approveSupplierVendorRemitChange instead to apply a pending change.
                    Preconditions: the vendor must exist and must not already have a PENDING change.
                    Required inputs: vendorId (UUIDv7) path parameter, the proposed remitTo, and a reason of at
                    least 10 characters.
                    Emits a SUPPLIER_VENDOR_REMIT_CHANGE_REQUEST audit event; the vendor and its remitToVersion
                    are unchanged and no supplier.vendor.updated fact is queued.
                    Returns 201 with the change, 400 VALIDATION_ERROR for a malformed address or short reason,
                    404 SUPPLIER_VENDOR_NOT_FOUND, and 409 SUPPLIER_VENDOR_REMIT_CHANGE_PENDING when a change
                    is already waiting.
                    """)
    @ApiResponse(responseCode = "201", description = "Remit-to change recorded as PENDING.")
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_ERROR: the address is malformed or the reason is too short.",
            content = @Content(mediaType = JSON, schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = VENDOR_NOT_FOUND,
            content = @Content(mediaType = JSON, schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "SUPPLIER_VENDOR_REMIT_CHANGE_PENDING: the vendor already has a change waiting.",
            content = @Content(mediaType = JSON, schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = UNAUTHENTICATED,
            content = @Content(schema = @Schema(hidden = true)))
    @ApiResponse(
            responseCode = "403",
            description = FORBIDDEN,
            content = @Content(mediaType = JSON, schema = @Schema(implementation = ApiError.class)))
    @PreAuthorize("hasAuthority('" + SupplierPermissions.VENDOR_WRITE + "')")
    @EmitEvent(id = "SUPPLIER_VENDOR_REMIT_CHANGE_REQUEST", apiVersion = "1")
    @PostMapping("/{vendorId}/remit-to-changes")
    public ResponseEntity<RemitChangeView> requestRemitChange(
            @Parameter(
                            description = VENDOR_ID_DESCRIPTION,
                            required = true,
                            schema = @Schema(type = "string", format = "uuid", example = UUID_EXAMPLE))
                    @PathVariable
                    @NotNull
                    UUID vendorId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Proposed remit-to and why.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = JSON,
                                            examples =
                                                    @ExampleObject(name = "New lockbox", value = REMIT_CHANGE_EXAMPLE)))
                    @Valid
                    @NotNull
                    @RequestBody
                    RemitChangeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(vendorService.requestRemitChange(vendorId, request));
    }

    @Operation(
            operationId = "approveSupplierVendorRemitChange",
            summary = "Approve a remit-to change",
            description = """
                    Applies a PENDING remit-to change: the vendor's remit-to becomes the proposed address, its
                    remitToVersion rises by one, and supplier.vendor.updated is published naming the requester
                    and the approver.
                    Use this tool as the second person checking a requested remit-to change; use
                    rejectSupplierVendorRemitChange to refuse it instead.
                    Preconditions: the change must be PENDING, and the caller must not be its requester; no
                    tenant setting allows self-approval.
                    Required inputs: vendorId and changeId (UUIDv7) path parameters, and a verificationNote of at
                    least 10 characters saying how the change was checked.
                    Emits a SUPPLIER_VENDOR_REMIT_CHANGE_APPROVE audit event and queues one supplier.vendor.updated
                    fact in the same transaction.
                    Returns 200 with the change, 400 VALIDATION_ERROR for a short note, 403
                    SUPPLIER_VENDOR_REMIT_SELF_APPROVAL when the caller requested the change, 404 when the vendor
                    or change does not exist, and 409 SUPPLIER_VENDOR_REMIT_CHANGE_NOT_PENDING when it was
                    already decided.
                    """)
    @ApiResponse(responseCode = "200", description = "Remit-to change approved and applied.")
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_ERROR: the verification note is shorter than 10 characters.",
            content = @Content(mediaType = JSON, schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "SUPPLIER_VENDOR_REMIT_SELF_APPROVAL: the caller requested this change; or the caller"
                    + " lacks the required supplier permission (FORBIDDEN).",
            content = @Content(mediaType = JSON, schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "SUPPLIER_VENDOR_NOT_FOUND or SUPPLIER_VENDOR_REMIT_CHANGE_NOT_FOUND.",
            content = @Content(mediaType = JSON, schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "SUPPLIER_VENDOR_REMIT_CHANGE_NOT_PENDING: the change was already approved or rejected.",
            content = @Content(mediaType = JSON, schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = UNAUTHENTICATED,
            content = @Content(schema = @Schema(hidden = true)))
    @PreAuthorize("hasAuthority('" + SupplierPermissions.VENDOR_REMIT_APPROVE + "')")
    @EmitEvent(id = "SUPPLIER_VENDOR_REMIT_CHANGE_APPROVE", apiVersion = "1")
    @PostMapping("/{vendorId}/remit-to-changes/{changeId}/approval")
    public ResponseEntity<RemitChangeView> approveRemitChange(
            @Parameter(
                            description = VENDOR_ID_DESCRIPTION,
                            required = true,
                            schema = @Schema(type = "string", format = "uuid", example = UUID_EXAMPLE))
                    @PathVariable
                    @NotNull
                    UUID vendorId,
            @Parameter(
                            description = CHANGE_ID_DESCRIPTION,
                            required = true,
                            schema = @Schema(type = "string", format = "uuid", example = UUID_EXAMPLE))
                    @PathVariable
                    @NotNull
                    UUID changeId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "How the change was checked.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = JSON,
                                            examples = @ExampleObject(name = "Called back", value = APPROVAL_EXAMPLE)))
                    @Valid
                    @NotNull
                    @RequestBody
                    RemitApprovalRequest request) {
        return ResponseEntity.ok(vendorService.approveRemitChange(vendorId, changeId, request));
    }

    @Operation(operationId = "rejectSupplierVendorRemitChange", summary = "Reject a remit-to change", description = """
                    Refuses a PENDING remit-to change with a note; the vendor and its remit-to are unchanged and
                    nothing is published.
                    Use this tool when a requested remit-to change cannot be verified or is wrong; use
                    approveSupplierVendorRemitChange to apply it instead.
                    Preconditions: the change must be PENDING.
                    Required inputs: vendorId and changeId (UUIDv7) path parameters, and a note of at least 10
                    characters saying why.
                    Emits a SUPPLIER_VENDOR_REMIT_CHANGE_REJECT audit event; the decision and note are kept
                    permanently on the change.
                    Returns 200 with the change, 400 VALIDATION_ERROR for a short note, 404 when the vendor or
                    change does not exist, and 409 SUPPLIER_VENDOR_REMIT_CHANGE_NOT_PENDING when it was already
                    decided.
                    """)
    @ApiResponse(responseCode = "200", description = "Remit-to change rejected.")
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_ERROR: the note is shorter than 10 characters.",
            content = @Content(mediaType = JSON, schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "SUPPLIER_VENDOR_NOT_FOUND or SUPPLIER_VENDOR_REMIT_CHANGE_NOT_FOUND.",
            content = @Content(mediaType = JSON, schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "SUPPLIER_VENDOR_REMIT_CHANGE_NOT_PENDING: the change was already approved or rejected.",
            content = @Content(mediaType = JSON, schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = UNAUTHENTICATED,
            content = @Content(schema = @Schema(hidden = true)))
    @ApiResponse(
            responseCode = "403",
            description = FORBIDDEN,
            content = @Content(mediaType = JSON, schema = @Schema(implementation = ApiError.class)))
    @PreAuthorize("hasAuthority('" + SupplierPermissions.VENDOR_REMIT_APPROVE + "')")
    @EmitEvent(id = "SUPPLIER_VENDOR_REMIT_CHANGE_REJECT", apiVersion = "1")
    @PostMapping("/{vendorId}/remit-to-changes/{changeId}/rejection")
    public ResponseEntity<RemitChangeView> rejectRemitChange(
            @Parameter(
                            description = VENDOR_ID_DESCRIPTION,
                            required = true,
                            schema = @Schema(type = "string", format = "uuid", example = UUID_EXAMPLE))
                    @PathVariable
                    @NotNull
                    UUID vendorId,
            @Parameter(
                            description = CHANGE_ID_DESCRIPTION,
                            required = true,
                            schema = @Schema(type = "string", format = "uuid", example = UUID_EXAMPLE))
                    @PathVariable
                    @NotNull
                    UUID changeId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Why the change is refused.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = JSON,
                                            examples = @ExampleObject(name = "Unverified", value = REJECTION_EXAMPLE)))
                    @Valid
                    @NotNull
                    @RequestBody
                    RemitRejectionRequest request) {
        return ResponseEntity.ok(vendorService.rejectRemitChange(vendorId, changeId, request));
    }

    // ── Facts replay (ADR-0044 §4) ──────────────────────────────────────────────────

    @Operation(operationId = "replaySupplierVendorFacts", summary = "Re-emit vendor facts", description = """
                    Re-publishes supplier.vendor.updated for one bounded page of the caller's tenant's vendors, in
                    id order at each vendor's current version, so an event-fed replica in another module can be
                    seeded or repaired.
                    Use this tool to fill a consumer's vendor copy after a first deployment or an outage longer
                    than broker retention; do not use it to fix one vendor, which republishes on its next change.
                    Preconditions: none; only the caller's tenant's vendors are replayed, and replayed facts are
                    indistinguishable from live ones, so consumers apply them under their stale guard.
                    Required inputs: none; afterVendorId resumes after a previous page's cursor, and limit bounds
                    the page and is clamped into 1 to 1000 (default 200) rather than rejected.
                    Emits a SUPPLIER_VENDOR_FACT_REPLAY audit event and queues one fact per vendor in the page; no
                    vendor changes.
                    Returns 200 with emitted, a nextAfterVendorId cursor, and complete=true with a null cursor once
                    the last vendor was emitted.
                    """)
    @ApiResponse(responseCode = "200", description = "What this page emitted and where to resume.")
    @ApiResponse(
            responseCode = "400",
            description = "A parameter is malformed.",
            content = @Content(mediaType = JSON, schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = UNAUTHENTICATED,
            content = @Content(schema = @Schema(hidden = true)))
    @ApiResponse(
            responseCode = "403",
            description = FORBIDDEN,
            content = @Content(mediaType = JSON, schema = @Schema(implementation = ApiError.class)))
    @PreAuthorize("hasAuthority('" + SupplierPermissions.FACT_REPLAY + "')")
    @EmitEvent(id = "SUPPLIER_VENDOR_FACT_REPLAY", apiVersion = "1")
    @PostMapping("/facts/replay")
    public ResponseEntity<VendorFactReplayResult> replayFacts(
            @Parameter(description = "Resume after this vendor id (a previous page's cursor).", example = UUID_EXAMPLE)
                    @RequestParam(required = false)
                    @Nullable
                    UUID afterVendorId,
            @Parameter(description = "Page size, clamped into 1 to 1000.", example = "200")
                    @RequestParam(defaultValue = "200")
                    int limit) {
        return ResponseEntity.ok(vendorService.replayFacts(afterVendorId, limit));
    }
}
