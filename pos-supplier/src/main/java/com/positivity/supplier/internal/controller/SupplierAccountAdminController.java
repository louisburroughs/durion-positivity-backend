package com.positivity.supplier.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import com.positivity.supplier.internal.security.SupplierPermissions;
import com.positivity.supplier.service.SupplierProfileAdminService;
import com.positivity.supplier.service.model.CommercialAccountRequest;
import com.positivity.supplier.service.model.CommercialAccountView;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Commercial account administration (ADR-0050 §5, durion-positivity-backend#1222): one
 * billing account per profile, one delivery account per (profile, pos-location). Account
 * numbers are ordinary profile data, distinct from credentials.
 */
@Tag(
        name = "Supplier Commercial Accounts",
        description = "Admin CRUD over vendor commercial accounts — billing and per-location delivery numbers")
@RestController
@RequiredArgsConstructor
@Validated
@SecurityRequirement(name = "bearerAuth")
@RequestMapping("/v1/supplier/admin/profiles/{vendorProfileId}/accounts")
public class SupplierAccountAdminController {

    private static final String ACCOUNT_EXAMPLE = """
            {"role":"DELIVERY","accountNumber":"FR-0042871","agencyCode":"PAR01",
             "deliveryLocationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a70"}
            """;

    private final SupplierProfileAdminService adminService;

    @Operation(operationId = "listCommercialAccounts", summary = "List commercial accounts", description = """
                    Returns the commercial accounts configured on a vendor profile, the BILLING account first and
                    then the per-location DELIVERY accounts.
                    Use this tool to find the accountId or the vendor account number for a location; do not use it to
                    read credentials, which are never returned here because account numbers are ordinary
                    configuration data.
                    Preconditions: the vendor profile must exist.
                    Required inputs: vendorProfileId (UUIDv7) path parameter; there is no request body or filtering.
                    Emits a SUPPLIER_ACCOUNT_LIST audit event; no configuration is changed.
                    Returns 404 when the vendor profile does not exist, and 200 with an empty array when the profile
                    exists but has no accounts.
                    """)
    @ApiResponse(responseCode = "200", description = "Accounts returned.")
    @ApiResponse(
            responseCode = "404",
            description = "Profile not found.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = "Authentication is missing or the bearer token is invalid."
                    + " The response has NO body: the gateway rejects unauthenticated calls with a bodiless"
                    + " status, so clients must not attempt to parse an error envelope here.",
            content = @Content(schema = @Schema(hidden = true)))
    @ApiResponse(
            responseCode = "403",
            description = "Authenticated caller lacks the required supplier permission.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @PreAuthorize("hasAuthority('" + SupplierPermissions.PROFILE_READ + "')")
    @EmitEvent(id = "SUPPLIER_ACCOUNT_LIST", apiVersion = "1")
    @GetMapping
    public ResponseEntity<List<CommercialAccountView>> listAccounts(
            @Parameter(
                            description =
                                    "Owning vendor profile identifier (UUIDv7). Must reference an existing vendor profile.",
                            required = true,
                            schema =
                                    @Schema(
                                            type = "string",
                                            format = "uuid",
                                            example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b"))
                    @PathVariable
                    @NotNull
                    UUID vendorProfileId) {
        return ResponseEntity.ok(adminService.listAccounts(vendorProfileId));
    }

    @Operation(operationId = "createCommercialAccount", summary = "Create commercial account", description = """
                    Adds a commercial account to a vendor profile, either the profile's single BILLING account or a
                    DELIVERY account mapping one pos-location to its vendor account number.
                    Use this tool when a location is first authorised to order from a supplier; do not use it to
                    correct an account number, which is updateCommercialAccount.
                    Preconditions: the vendor profile must exist and be ADMIN-managed, and the slot must be free —
                    one BILLING account per profile and one DELIVERY account per profile and location pair.
                    Required inputs: vendorProfileId (UUIDv7) path parameter plus role and accountNumber in the body;
                    deliveryLocationId is required for DELIVERY and must be absent for BILLING, and agencyCode is
                    optional.
                    Emits a SUPPLIER_ACCOUNT_CREATE audit event; no order or invoice records are touched.
                    Returns 404 when the profile does not exist, 409 when the profile is YAML-managed or the account
                    slot is already taken, and 400 when accountNumber is blank or deliveryLocationId does not match
                    the role.
                    """)
    @ApiResponse(responseCode = "201", description = "Account created.")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid request.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Profile not found.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Profile is YAML-managed or account slot already taken.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = "Authentication is missing or the bearer token is invalid."
                    + " The response has NO body: the gateway rejects unauthenticated calls with a bodiless"
                    + " status, so clients must not attempt to parse an error envelope here.",
            content = @Content(schema = @Schema(hidden = true)))
    @ApiResponse(
            responseCode = "403",
            description = "Authenticated caller lacks the required supplier permission.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @PreAuthorize("hasAuthority('" + SupplierPermissions.PROFILE_WRITE + "')")
    @EmitEvent(id = "SUPPLIER_ACCOUNT_CREATE", apiVersion = "1")
    @PostMapping
    public ResponseEntity<CommercialAccountView> createAccount(
            @Parameter(
                            description =
                                    "Owning vendor profile identifier (UUIDv7). Must reference an existing vendor profile.",
                            required = true,
                            schema =
                                    @Schema(
                                            type = "string",
                                            format = "uuid",
                                            example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b"))
                    @PathVariable
                    @NotNull
                    UUID vendorProfileId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Commercial account to create on the vendor profile.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Delivery account for one location",
                                                            value = ACCOUNT_EXAMPLE)))
                    @Valid
                    @NotNull
                    @RequestBody
                    CommercialAccountRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(adminService.createAccount(vendorProfileId, request));
    }

    @Operation(operationId = "updateCommercialAccount", summary = "Update commercial account", description = """
                    Replaces the role, account number, agency code and location mapping of an existing commercial
                    account.
                    Use this tool to correct a vendor-assigned account number or move a DELIVERY account to another
                    location; do not use it to add a second account, which is createCommercialAccount.
                    Preconditions: the profile must exist and be ADMIN-managed, the account must belong to that
                    profile, and the target slot must not already be occupied by another account.
                    Required inputs: vendorProfileId and accountId (UUIDv7) path parameters plus the full body,
                    because every field is replaced; omitting agencyCode clears it.
                    Emits a SUPPLIER_ACCOUNT_UPDATE audit event; orders already transmitted under the previous
                    account number are unaffected.
                    Returns 404 when the profile or account does not exist, 409 when the profile is YAML-managed or
                    the account slot is taken, and 400 when accountNumber is blank or deliveryLocationId does not
                    match the role.
                    """)
    @ApiResponse(responseCode = "200", description = "Account updated.")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid request.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Profile or account not found.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Profile is YAML-managed or account slot already taken.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = "Authentication is missing or the bearer token is invalid."
                    + " The response has NO body: the gateway rejects unauthenticated calls with a bodiless"
                    + " status, so clients must not attempt to parse an error envelope here.",
            content = @Content(schema = @Schema(hidden = true)))
    @ApiResponse(
            responseCode = "403",
            description = "Authenticated caller lacks the required supplier permission.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @PreAuthorize("hasAuthority('" + SupplierPermissions.PROFILE_WRITE + "')")
    @EmitEvent(id = "SUPPLIER_ACCOUNT_UPDATE", apiVersion = "1")
    @PutMapping("/{accountId}")
    public ResponseEntity<CommercialAccountView> updateAccount(
            @Parameter(
                            description =
                                    "Owning vendor profile identifier (UUIDv7). Must reference an existing vendor profile.",
                            required = true,
                            schema =
                                    @Schema(
                                            type = "string",
                                            format = "uuid",
                                            example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b"))
                    @PathVariable
                    @NotNull
                    UUID vendorProfileId,
            @Parameter(
                            description =
                                    "Commercial account identifier (UUIDv7). Must belong to the vendor profile in the path.",
                            required = true,
                            schema =
                                    @Schema(
                                            type = "string",
                                            format = "uuid",
                                            example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a62"))
                    @PathVariable
                    @NotNull
                    UUID accountId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Commercial account to store in place of the existing one on the vendor profile.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Delivery account for one location",
                                                            value = ACCOUNT_EXAMPLE)))
                    @Valid
                    @NotNull
                    @RequestBody
                    CommercialAccountRequest request) {
        return ResponseEntity.ok(adminService.updateAccount(vendorProfileId, accountId, request));
    }

    @Operation(operationId = "deleteCommercialAccount", summary = "Delete commercial account", description = """
                    Removes a commercial account from a vendor profile, freeing its BILLING or per-location DELIVERY
                    slot.
                    Use this tool when a location stops ordering from the supplier; to change the account number
                    instead, call updateCommercialAccount, which preserves the slot.
                    Preconditions: the profile must exist and be ADMIN-managed, and the account must belong to that
                    profile.
                    Required inputs: vendorProfileId and accountId (UUIDv7) path parameters; there is no request body.
                    Emits a SUPPLIER_ACCOUNT_DELETE audit event; ordering for the mapped location resolves to a
                    not-configured outcome from that point on.
                    Returns 404 when the profile or account does not exist, and 409 when the profile is YAML-managed.
                    """)
    @ApiResponse(responseCode = "204", description = "Account deleted.")
    @ApiResponse(
            responseCode = "404",
            description = "Profile or account not found.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Profile is YAML-managed.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = "Authentication is missing or the bearer token is invalid."
                    + " The response has NO body: the gateway rejects unauthenticated calls with a bodiless"
                    + " status, so clients must not attempt to parse an error envelope here.",
            content = @Content(schema = @Schema(hidden = true)))
    @ApiResponse(
            responseCode = "403",
            description = "Authenticated caller lacks the required supplier permission.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @PreAuthorize("hasAuthority('" + SupplierPermissions.PROFILE_WRITE + "')")
    @EmitEvent(id = "SUPPLIER_ACCOUNT_DELETE", apiVersion = "1")
    @DeleteMapping("/{accountId}")
    public ResponseEntity<Void> deleteAccount(
            @Parameter(
                            description =
                                    "Owning vendor profile identifier (UUIDv7). Must reference an existing vendor profile.",
                            required = true,
                            schema =
                                    @Schema(
                                            type = "string",
                                            format = "uuid",
                                            example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b"))
                    @PathVariable
                    @NotNull
                    UUID vendorProfileId,
            @Parameter(
                            description =
                                    "Commercial account identifier (UUIDv7). Must belong to the vendor profile in the path.",
                            required = true,
                            schema =
                                    @Schema(
                                            type = "string",
                                            format = "uuid",
                                            example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a62"))
                    @PathVariable
                    @NotNull
                    UUID accountId) {
        adminService.deleteAccount(vendorProfileId, accountId);
        return ResponseEntity.noContent().build();
    }
}
