package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.VendorApSettingsRequest;
import com.positivity.accounting.internal.dto.VendorRemitToConfirmationRequest;
import com.positivity.accounting.internal.dto.VendorResponse;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.accounting.internal.service.VendorDirectoryService;
import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
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
 * The AP vendors (Issue #816; CAP:550 S24, #2517; SPEC-accounting-workspace §4.9): reads served from accounting's copy
 * of the pos-supplier vendor master, and the two vendor commands that stay accounting's (AW23), the remit-to
 * confirmation and the AP defaults. The vendor master itself is pos-supplier's; nothing here writes the copy. The
 * actor of a command is the caller (ADR-0018). No location is taken or reached: vendors are the tenant's (ADR-0061
 * does not apply).
 *
 * @see VendorDirectoryService
 */
@Slf4j
@RestController
@RequestMapping("/v1/accounting/vendors")
@RequiredArgsConstructor
@Tag(
        name = "Vendor Directory API",
        description = "Vendors from accounting's copy of the pos-supplier vendor master, and their AP settings")
@Validated
public class VendorDirectoryController {

    private static final String TAG = "Vendor Directory API";

    private final VendorDirectoryService vendorDirectoryService;

    @GetMapping
    @EmitEvent(id = "ACCOUNTING_VENDOR_SEARCH", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:ap:view"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.AP_VIEW + "')")
    @Operation(
            operationId = "searchVendors",
            summary = "Search Vendors By Name",
            description = """
                    Searches accounting's copy of the pos-supplier vendor master with a case-insensitive \
                    name-contains match, returning active and inactive vendors ordered by name, each with its \
                    vendorNumber, status, current remitToVersion and paymentDetailsChanged flag.
                    Use this tool to resolve a vendor name to its pos-supplier vendorId; use getVendorById instead \
                    when a vendor id is already known, and use pos-supplier's vendor endpoints to change a vendor.
                    Preconditions: the caller holds accounting:ap:view; a vendor appears once its \
                    supplier.vendor.updated fact has been copied (seed with POST /v1/supplier/vendors/facts/replay).
                    Required inputs: none; name is an optional contains term, status (ACTIVE or INACTIVE) an \
                    optional filter, and limit defaults to 20 with a server cap of 100.
                    Emits an ACCOUNTING_VENDOR_SEARCH audit event; no state changes.
                    Returns 200 with an empty list when no vendor matches, and 400 VALIDATION_ERROR for a status \
                    outside ACTIVE and INACTIVE.
                    """,
            tags = {TAG})
    @ApiResponse(
            responseCode = "200",
            description = "Matching vendors returned",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = VendorResponse.class))))
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_ERROR: status is not ACTIVE or INACTIVE",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<List<VendorResponse>> searchVendors(
            @Parameter(description = "Name search term (case-insensitive contains)", example = "acme")
                    @Nullable
                    @RequestParam(required = false)
                    String name,
            @Parameter(description = "Maximum results to return (server caps at 100)", example = "20")
                    @RequestParam(required = false, defaultValue = "20")
                    int limit,
            // Declared after limit so the generated SDK keeps searchVendors(name?, limit?, status?) (S24).
            @Parameter(description = "ACTIVE or INACTIVE; absent returns both", example = "ACTIVE")
                    @Nullable
                    @RequestParam(required = false)
                    String status) {
        // The status is logged only by the service, once validated.
        log.info("Received vendor search request | termLength={} | limit={}", name == null ? 0 : name.length(), limit);
        return ResponseEntity.ok(vendorDirectoryService.searchVendors(name, status, limit));
    }

    @GetMapping("/{vendorId}")
    @EmitEvent(id = "ACCOUNTING_VENDOR_GET", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:ap:view"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.AP_VIEW + "')")
    @Operation(
            operationId = "getVendorById",
            summary = "Get Vendor By Id",
            description = """
                    Returns one vendor from accounting's copy of the pos-supplier vendor master, with its \
                    vendorNumber, status, remitToVersion, paymentDetailsChanged and apSettings (the AP defaults \
                    and the last remit-to confirmation).
                    Use this tool when the vendor id is already known, for example before confirming a changed \
                    remit-to; use searchVendors instead when resolving a name typed by a user.
                    Preconditions: the caller holds accounting:ap:view and the vendor has been copied from \
                    pos-supplier.
                    Required inputs: vendorId (the pos-supplier vendor UUID) as a path parameter; there is no \
                    request body.
                    Emits an ACCOUNTING_VENDOR_GET audit event; no state changes.
                    Returns 503 VENDOR_REPLICATION_PENDING with Retry-After when the vendor is not in the copy yet.
                    """,
            tags = {TAG})
    @ApiResponse(
            responseCode = "200",
            description = "Vendor found",
            content = @Content(schema = @Schema(implementation = VendorResponse.class)))
    @ApiResponse(
            responseCode = "503",
            description =
                    "VENDOR_REPLICATION_PENDING: the vendor is not in accounting's copy of the pos-supplier vendor"
                            + " master yet. Not-yet, not no: retry after the Retry-After interval.",
            headers =
                    @Header(
                            name = "Retry-After",
                            description = "Seconds to wait before retrying",
                            schema = @Schema(type = "integer")),
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<VendorResponse> getVendorById(
            @Parameter(description = "pos-supplier vendor id", example = "550e8400-e29b-41d4-a716-446655440001")
                    @NonNull
                    @PathVariable
                    UUID vendorId) {
        log.info("Received vendor lookup request | vendorId={}", vendorId);
        return ResponseEntity.ok(vendorDirectoryService.getVendorById(vendorId));
    }

    @PostMapping("/{vendorId}/remit-to-confirmation")
    @EmitEvent(id = "ACCOUNTING_VENDOR_REMIT_TO_CONFIRM", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:ap:approve"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.AP_APPROVE + "')")
    @Operation(
            operationId = "confirmVendorRemitTo",
            summary = "Confirm Vendor Remit-To",
            description = """
                    Records that the caller confirmed the vendor's current remit-to version, when, and how it was \
                    verified, so its bills approved at an earlier version can be paid again.
                    A payment then passes for those bills, provided the payer is not the confirmer; a later \
                    remit-to change needs a new confirmation.
                    Use this tool after verifying a changed remit-to with the vendor; do not use it to change the \
                    remit-to itself, use pos-supplier's remit-to change approval instead.
                    Preconditions: the caller holds accounting:ap:approve and the vendor is in the copy.
                    Required inputs: remitToVersion (the vendor's current version) and justification (at least 10 \
                    characters).
                    Emits ACCOUNTING_VENDOR_REMIT_TO_CONFIRM and writes a REMIT_TO_CONFIRM audit row.
                    Returns 200 with the vendor read; 400 VALIDATION_ERROR or JUSTIFICATION_REQUIRED; 403 \
                    VENDOR_REMIT_TO_SELF_CONFIRMATION when the caller requested this remit-to in pos-supplier; 409 \
                    VENDOR_PAYMENT_DETAILS_CHANGED when the version is not the current one; 503 \
                    VENDOR_REPLICATION_PENDING (Retry-After) when the vendor is not in the copy yet.
                    """,
            tags = {TAG})
    @ApiResponse(
            responseCode = "200",
            description = "Confirmed; the vendor as getVendorById returns it",
            content = @Content(schema = @Schema(implementation = VendorResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_ERROR (no remitToVersion) or JUSTIFICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "FORBIDDEN without accounting:ap:approve; VENDOR_REMIT_TO_SELF_CONFIRMATION when the caller"
                    + " requested the vendor's current remit-to in pos-supplier (another approver confirms it)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "503",
            description =
                    "VENDOR_REPLICATION_PENDING: the vendor is not in accounting's copy of the pos-supplier vendor"
                            + " master yet. Not-yet, not no: retry after the Retry-After interval.",
            headers =
                    @Header(
                            name = "Retry-After",
                            description = "Seconds to wait before retrying",
                            schema = @Schema(type = "integer")),
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "VENDOR_PAYMENT_DETAILS_CHANGED: remitToVersion is not the vendor's current version",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<VendorResponse> confirmVendorRemitTo(
            @Parameter(description = "pos-supplier vendor id", example = "550e8400-e29b-41d4-a716-446655440001")
                    @NonNull
                    @PathVariable
                    UUID vendorId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The version confirmed and how it was verified.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Confirm version 3", value = """
                                                {"remitToVersion":3,
                                                 "justification":"Called the vendor's accounts desk; address confirmed"}
                                                """)))
                    @Valid
                    @RequestBody
                    @NonNull
                    VendorRemitToConfirmationRequest request) {
        return ResponseEntity.ok(vendorDirectoryService.confirmRemitTo(vendorId, request));
    }

    @PutMapping("/{vendorId}/ap-settings")
    @EmitEvent(id = "ACCOUNTING_VENDOR_AP_SETTINGS_SET", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:ap_approval_policy:manage"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.AP_APPROVAL_POLICY_MANAGE + "')")
    @Operation(
            operationId = "setVendorApSettings",
            summary = "Set Vendor AP Settings",
            description = """
                    Sets the vendor's AP defaults: defaultDebitClass (GOODS or EXPENSE) and \
                    defaultExpenseMappingKey (an active VENDOR_BILL key EXPENSE_<CODE>); a field left out is \
                    unchanged and a field sent as null clears it.
                    An approval falls back to them only when neither the approver's classification nor the \
                    proposal made at submission names a class or key; they never touch a posted entry, and each \
                    change writes an AP_VENDOR_SETTINGS_SET audit row, old to new.
                    Use this tool when a controller sets how a vendor's bills are classed by default; do not use \
                    it to classify one bill, use the approval's classification instead.
                    Preconditions: the caller holds accounting:ap_approval_policy:manage and the vendor is in the \
                    copy; an inactive vendor may be set.
                    Required inputs: justification (at least 10 characters) and requestId (a UUID generated once \
                    per change); EXPENSE needs a key, sent or already set.
                    Emits ACCOUNTING_VENDOR_AP_SETTINGS_SET; the call is idempotent on requestId: a replay writes \
                    nothing and returns the vendor as it is.
                    Returns 200 with the vendor read; 400 VALIDATION_ERROR with fieldErrors or \
                    JUSTIFICATION_REQUIRED; 403 FORBIDDEN; 409 IDEMPOTENCY_CONFLICT for a requestId already used \
                    with another body; 503 VENDOR_REPLICATION_PENDING (Retry-After); nothing is written on a refusal.
                    """,
            tags = {TAG})
    @ApiResponse(
            responseCode = "200",
            description = "The vendor as getVendorById returns it, after the change",
            content = @Content(schema = @Schema(implementation = VendorResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_ERROR with fieldErrors (a class outside GOODS/EXPENSE, a key that is not an"
                    + " active VENDOR_BILL key EXPENSE_<CODE>, EXPENSE without a key, no requestId) or"
                    + " JUSTIFICATION_REQUIRED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "FORBIDDEN without accounting:ap_approval_policy:manage",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "IDEMPOTENCY_CONFLICT: the requestId was already used for another vendor AP settings change",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "503",
            description =
                    "VENDOR_REPLICATION_PENDING: the vendor is not in accounting's copy of the pos-supplier vendor"
                            + " master yet. Not-yet, not no: retry after the Retry-After interval.",
            headers =
                    @Header(
                            name = "Retry-After",
                            description = "Seconds to wait before retrying",
                            schema = @Schema(type = "integer")),
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<VendorResponse> setVendorApSettings(
            @Parameter(description = "pos-supplier vendor id", example = "550e8400-e29b-41d4-a716-446655440001")
                    @NonNull
                    @PathVariable
                    UUID vendorId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The defaults to change, the justification and the request id.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Shop supplies by default", value = """
                                                {"defaultDebitClass":"EXPENSE",
                                                 "defaultExpenseMappingKey":"EXPENSE_SHOP_SUPPLIES",
                                                 "justification":"Header-only bills of this vendor are shop supplies",
                                                 "requestId":"0199c0de-7a1b-7c2d-8e3f-4a5b6c7d8e9f"}
                                                """)))
                    @Valid
                    @RequestBody
                    @NonNull
                    VendorApSettingsRequest request) {
        return ResponseEntity.ok(vendorDirectoryService.setApSettings(vendorId, request));
    }
}
