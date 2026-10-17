package com.positivity.tenant.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import com.positivity.tenant.internal.dto.ReadingTierCreateRequest;
import com.positivity.tenant.internal.dto.ReadingTierResponse;
import com.positivity.tenant.internal.dto.ReadingTierUpdateRequest;
import com.positivity.tenant.internal.security.TenantPermissions;
import com.positivity.tenant.internal.service.ReadingTierService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The reading-tier catalog (CAP:550 S45, #2619; AW63, AW68): each tier names a monthly document-reading allowance,
 * every tenant is on one tier, and a tier's allowance change reaches pos-accounting as one {@code
 * tenant.reading-allowance.changed} fact per tenant. Platform operators only ({@code PlatformTenantGuard}).
 */
@Tag(name = "Platform Reading Tier API", description = "Monthly document-reading allowances sold as tiers")
@RestController
@RequestMapping("/v1/platform/reading-tiers")
@RequiredArgsConstructor
public class PlatformReadingTierController {

    private final ReadingTierService readingTiers;

    @Operation(operationId = "listReadingTiers", summary = "List Reading Tiers", description = """
            Lists every reading tier, active or not, by code: the display name, the monthly document-reading \
            allowance (amount with its ISO 4217 currency) and the active flag.
            Use this tool to pick a readingTierCode for createTenant or updateTenant, or before changing a tier; \
            do not use it to read a tenant's own allowance, use getTenant instead, whose readingTierCode names \
            the tier the tenant is on.
            Preconditions: the caller is bound to the platform tenant (403 PLATFORM_TENANT_REQUIRED otherwise).
            Required inputs: none.
            Emits a READING_TIER_LIST event.
            Returns 200 with the list; STANDARD (20.00 USD) exists from the start.
            """)
    @ApiResponse(responseCode = "200", description = "The tiers")
    @EmitEvent(id = "READING_TIER_LIST", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + TenantPermissions.READING_TIER_READ + "')")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"platform:reading_tier:read"})
    @GetMapping
    public ResponseEntity<List<ReadingTierResponse>> list() {
        return ResponseEntity.ok(readingTiers.list());
    }

    @Operation(operationId = "createReadingTier", summary = "Create a Reading Tier", description = """
            Creates an active reading tier: a code that never changes, a display name and a monthly \
            document-reading allowance (an amount with its currency, ADR-0067 PC-3).
            Use this tool to sell a bigger (or smaller) allowance as a new tier, before assigning it to tenants \
            with updateTenant; do not use it to change an existing tier's allowance, use updateReadingTier instead.
            Preconditions: the code is not taken; the caller is bound to the platform tenant.
            Required inputs: code (upper case, 2 to 32 characters), displayName and monthlyAllowance \
            {amount, currencyCode}; amount is at least zero with four decimals at most, currencyCode an ISO 4217 \
            code (USD for now).
            Emits a READING_TIER_CREATE event; no tenant fact follows, since no tenant is on a new tier yet.
            Returns 201 with the tier, 400 VALIDATION_ERROR for a malformed field and 409 READING_TIER_EXISTS \
            when the code is taken.
            """)
    @ApiResponse(responseCode = "201", description = "Tier created")
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_ERROR: a malformed field",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "READING_TIER_EXISTS: the code is taken",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "READING_TIER_CREATE", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + TenantPermissions.READING_TIER_MANAGE + "')")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"platform:reading_tier:manage"})
    @PostMapping
    public ResponseEntity<ReadingTierResponse> create(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The tier to create: code, display name and monthly allowance",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "A PRO tier at 60.00 USD", value = """
                                                            {"code":"PRO","displayName":"Pro",\
                                                            "monthlyAllowance":{"amount":60.00,"currencyCode":"USD"}}
                                                            """)))
                    @Valid
                    @RequestBody
                    ReadingTierCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(readingTiers.create(request));
    }

    @Operation(operationId = "updateReadingTier", summary = "Change a Reading Tier", description = """
            Changes a tier's display name, its monthly allowance or its active flag; omitted fields are left as \
            they are. A changed allowance publishes one tenant.reading-allowance.changed fact per tenant on the \
            tier, in the same transaction, so each tenant's allowance moves at once while its month-to-date usage \
            is unchanged.
            Use this tool to reprice a tier or to retire one; do not use it to move one tenant to another tier, \
            use updateTenant with readingTierCode instead.
            Preconditions: the tier exists; deactivating needs no tenant other than decommissioned ones on it; \
            the caller is bound to the platform tenant.
            Required inputs: code (path); any of displayName, monthlyAllowance {amount, currencyCode} and active.
            Emits a READING_TIER_UPDATE event.
            Returns 200 with the tier, 400 VALIDATION_ERROR for a malformed field, 404 when the code is unknown \
            and 409 READING_TIER_IN_USE when a tenant still on the tier blocks its deactivation, or when the tier is \
            STANDARD (the default every new tenant starts on; it is repriced, never deactivated).
            """)
    @ApiResponse(responseCode = "200", description = "Tier changed")
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_ERROR: a malformed field",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "NOT_FOUND: no such tier",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "READING_TIER_IN_USE: a non-decommissioned tenant is on the tier, or the tier is STANDARD",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "READING_TIER_UPDATE", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + TenantPermissions.READING_TIER_MANAGE + "')")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"platform:reading_tier:manage"})
    @PatchMapping("/{code}")
    public ResponseEntity<ReadingTierResponse> update(
            @Parameter(description = "Tier code", example = "STANDARD") @PathVariable String code,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The changes: display name, monthly allowance, active flag",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Reprice STANDARD to 25.00 USD",
                                                            value = """
                                                            {"monthlyAllowance":{"amount":25.00,"currencyCode":"USD"}}
                                                            """)))
                    @Valid
                    @RequestBody
                    ReadingTierUpdateRequest request) {
        return ResponseEntity.ok(readingTiers.update(code, request));
    }
}
