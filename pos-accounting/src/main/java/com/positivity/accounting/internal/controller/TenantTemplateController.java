package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.EnableTemplateAddOnRequest;
import com.positivity.accounting.internal.dto.TenantTemplateStatusResponse;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.accounting.internal.service.TenantTemplateService;
import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's tenant and the accounting template every tenant receives (#2526): a read of where
 * the tenant stands, and the tenant-setup choice of the retread-plant add-on (AW30). Both act on
 * the tenant of the caller's token; neither takes a tenant from the request.
 */
@RestController
@RequestMapping("/v1/accounting/tenant-template")
@Validated
@Tag(name = "Accounting Tenant Template", description = "The chart of accounts and mapping defaults a tenant receives")
public class TenantTemplateController {

    private final TenantTemplateService tenantTemplateService;

    public TenantTemplateController(@NonNull TenantTemplateService tenantTemplateService) {
        this.tenantTemplateService = tenantTemplateService;
    }

    @GetMapping("/status")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:coa:view"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.COA_VIEW + "')")
    @Operation(
            operationId = "getTenantTemplateStatus",
            summary = "Get Tenant Accounting Template Status",
            description = """
                    Reports where the caller's tenant stands against the accounting template every tenant \
                    receives: the reference chart of accounts, posting categories, mapping keys, GL mappings, \
                    default GL mappings and statement lines.
                    Use this tool to check that a new tenant was provisioned before its first invoice or \
                    payment posts, or to find out why a posting failed with GL_MAPPING_NOT_CONFIGURED; do not \
                    use listGLAccounts or resolveGLMapping, which show what the tenant holds but not what the \
                    template expected or why an entry was not created.
                    Preconditions: caller holds accounting:coa:view. Read-only and idempotent; it never \
                    applies the template.
                    Required inputs: none. The tenant is the caller's own, taken from the token.
                    Emits an ACCOUNTING_TENANT_TEMPLATE_STATUS_VIEW event and returns 200 with state \
                    (NOT_PROVISIONED, UP_TO_DATE, PENDING, NEEDS_ATTENTION), lastAppliedAt, counts by outcome, \
                    whether the retread-plant add-on is on, and attention: each entry in conflict or withheld \
                    with its reason and the template's and the tenant's values in business words.
                    """,
            tags = {"Accounting Tenant Template"})
    @ApiResponse(responseCode = "200", description = "The tenant's standing against the template")
    @ApiResponse(
            responseCode = "401",
            description = "No tenant on the request (TENANT_REQUIRED)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks accounting:coa:view",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "ACCOUNTING_TENANT_TEMPLATE_STATUS_VIEW", apiVersion = "1")
    public ResponseEntity<TenantTemplateStatusResponse> getStatus() {
        return ResponseEntity.ok(tenantTemplateService.status());
    }

    @PutMapping("/add-ons/retread-plant")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:coa:create"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.COA_CREATE + "')")
    @Operation(
            operationId = "enableRetreadPlantAddOn",
            summary = "Enable Retread Plant Accounting Add-On",
            description = """
                    Turns the retread-plant add-on on for the caller's tenant: the GL accounts only a shop \
                    that runs a retread plant needs (curing consumables, MRT equipment depreciation and \
                    leases, inventory charge, scrapped casings, production adjustments, rubber dust sales) and \
                    their Labor & Overhead report lines.
                    Use this tool once, at tenant setup, when the shop runs a retread plant; do not use \
                    createGLAccount, which adds one account without its report line and leaves the tenant's \
                    choice unrecorded.
                    Preconditions: caller holds accounting:coa:create. There is no switching off: nothing the \
                    template provisioned is ever removed. Idempotent: a replayed requestId, or a call for a \
                    tenant that already has the add-on, changes nothing and returns the current state.
                    Required inputs: justification (at least 10 characters) and requestId. The tenant is the \
                    caller's own, taken from the token.
                    Emits an ACCOUNTING_TENANT_TEMPLATE_ADD_ON_ENABLE event, writes one audit row naming the \
                    caller, and returns 200 with the tenant's template status after the accounts and lines \
                    were added; an account the tenant already holds under one of the add-on's codes is \
                    adopted when it is the same account and reported under attention when it is not.
                    """,
            tags = {"Accounting Tenant Template"})
    @ApiResponse(responseCode = "200", description = "The add-on is on; the tenant's standing against the template")
    @ApiResponse(
            responseCode = "400",
            description = "Missing or short justification, or missing requestId (VALIDATION_ERROR)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = "No tenant on the request (TENANT_REQUIRED)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks accounting:coa:create",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "ACCOUNTING_TENANT_TEMPLATE_ADD_ON_ENABLE", apiVersion = "1")
    public ResponseEntity<TenantTemplateStatusResponse> enableRetreadPlantAddOn(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            description = "Why the tenant needs the add-on, and the id naming this request.",
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = EnableTemplateAddOnRequest.class),
                                            examples =
                                                    @ExampleObject(name = "A shop with a retread plant", value = """
                                                                    {"justification":"We run a retread plant at the Tulsa shop",
                                                                     "requestId":"019a0000-0000-7000-8000-000000000009"}
                                                                    """)))
                    @RequestBody
                    EnableTemplateAddOnRequest request) {
        request.requireValid();
        return ResponseEntity.ok(tenantTemplateService.enableRetreadPlantAddOn(request));
    }
}
