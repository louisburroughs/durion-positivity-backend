package com.positivity.warranty.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.warranty.internal.dto.ProviderRequest;
import com.positivity.warranty.internal.dto.ProviderResponse;
import com.positivity.warranty.internal.enums.ProviderType;
import com.positivity.warranty.internal.security.WarrantyPermissions;
import com.positivity.warranty.internal.service.ProviderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Warranty provider CRUD (PRD §3.1, §8 row 1). */
@Tag(name = "Warranty Providers", description = "Who pays — manufacturers, program administrators, the dealer itself")
@RestController
@RequiredArgsConstructor
@Validated
@SecurityRequirement(name = "bearerAuth")
@RequestMapping("/v1/warranty/providers")
public class ProviderController {

    private static final String PROVIDER_EXAMPLE = """
            {"name":"Michelin North America",
             "providerType":"MANUFACTURER",
             "status":"ACTIVE",
             "apVendorId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a20",
             "manufacturerId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a11",
             "claimSubmissionMethod":"Portal",
             "portalUrl":"https://warranty.michelin.example.com",
             "contactName":"Claims Desk",
             "contactPhone":"+1-800-555-0100",
             "contactEmail":"claims@michelin.example.com"}
            """;

    private final ProviderService providerService;

    @Operation(operationId = "listProviders", summary = "List providers", description = """
                    Returns warranty providers, optionally filtered by lifecycle status and/or provider type.
                    Use this tool to browse who funds warranty programs; use getProvider instead when the \
                    provider id is already known.
                    Preconditions: none — an empty list is returned when nothing matches.
                    Required inputs: status (ACTIVE or INACTIVE) and providerType (MANUFACTURER, \
                    DISTRIBUTOR_PROGRAM, THIRD_PARTY_ADMIN, DEALER) are optional query parameters; when both are \
                    given the status filter is applied first and provider type filters the result.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with the list, which is empty rather than 404 when no provider matches.
                    """)
    @ApiResponse(responseCode = "200", description = "Providers returned.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.PROVIDER_VIEW + "')")
    @GetMapping
    public ResponseEntity<List<ProviderResponse>> listProviders(
            @Parameter(description = "Filter by lifecycle status (ACTIVE/INACTIVE)") @RequestParam(required = false)
                    String status,
            @Parameter(description = "Filter by provider type") @RequestParam(required = false)
                    ProviderType providerType) {
        return ResponseEntity.ok(providerService.list(status, providerType));
    }

    @Operation(operationId = "getProvider", summary = "Get provider", description = """
                    Returns one warranty provider, including its funding type, AP vendor link, and \
                    claim-submission contact details.
                    Use this tool when the provider id is already known; use listProviders instead to locate a \
                    provider by status or type.
                    Preconditions: the provider must exist.
                    Required inputs: id (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when no provider exists for the supplied id.
                    """)
    @ApiResponse(responseCode = "200", description = "Provider returned.")
    @ApiResponse(responseCode = "404", description = "Provider not found.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.PROVIDER_VIEW + "')")
    @GetMapping("/{id}")
    public ResponseEntity<ProviderResponse> getProvider(
            @Parameter(description = "Provider UUID", required = true) @PathVariable @NotNull UUID id) {
        return ResponseEntity.ok(providerService.getById(id));
    }

    @Operation(operationId = "createProvider", summary = "Create provider", description = """
                    Creates a warranty provider — the party that funds a warranty program, from a manufacturer or \
                    third-party administrator to the dealer itself.
                    Use this tool to register a new payer before configuring its policies with createPolicy; do \
                    not use updateProvider, which rewrites an existing provider.
                    Preconditions: none — there is no duplicate-name check, so repeating the call creates a \
                    second provider.
                    Required inputs: name and providerType (MANUFACTURER, DISTRIBUTOR_PROGRAM, THIRD_PARTY_ADMIN, \
                    or DEALER — DEALER means self-funded, which later makes vendor reimbursement NOT_APPLICABLE); \
                    status defaults to ACTIVE, and apVendorId optionally links the pos-accounting vendor for \
                    credit matching.
                    Emits a WARRANTY_PROVIDER_CREATE event; no policies or claims are touched.
                    Returns 201 with the created provider on success.
                    """)
    @ApiResponse(responseCode = "201", description = "Provider created.")
    @ApiResponse(responseCode = "400", description = "Invalid request.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.PROVIDER_MANAGE + "')")
    @EmitEvent(id = "WARRANTY_PROVIDER_CREATE", apiVersion = "1")
    @PostMapping
    public ResponseEntity<ProviderResponse> createProvider(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Warranty provider to create: funding type, lifecycle status, and"
                                    + " claim-submission contact details.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Manufacturer provider",
                                                            value = PROVIDER_EXAMPLE)))
                    @Valid
                    @NotNull
                    @RequestBody
                    ProviderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(providerService.create(request));
    }

    @Operation(operationId = "updateProvider", summary = "Update provider", description = """
                    Fully replaces an existing warranty provider's fields, including funding type, contact \
                    details, and the AP vendor link.
                    Use this tool to change provider data; do not use createProvider, which adds a new provider.
                    Preconditions: the provider must exist; omitting status keeps the current value, while every \
                    other field is rewritten from the request and null clears it.
                    Required inputs: id (UUID) as a path parameter, plus name and providerType; apVendorId, \
                    manufacturerId, claimSubmissionMethod, and the contact fields are optional.
                    Emits a WARRANTY_PROVIDER_UPDATE event.
                    Returns 404 when no provider exists for the supplied id.
                    """)
    @ApiResponse(responseCode = "200", description = "Provider updated.")
    @ApiResponse(responseCode = "400", description = "Invalid request.")
    @ApiResponse(responseCode = "404", description = "Provider not found.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.PROVIDER_MANAGE + "')")
    @EmitEvent(id = "WARRANTY_PROVIDER_UPDATE", apiVersion = "1")
    @PutMapping("/{id}")
    public ResponseEntity<ProviderResponse> updateProvider(
            @Parameter(description = "Provider UUID", required = true) @PathVariable @NotNull UUID id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Full replacement of the provider's fields (omitting status keeps the"
                                    + " current value).",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Manufacturer provider",
                                                            value = PROVIDER_EXAMPLE)))
                    @Valid
                    @NotNull
                    @RequestBody
                    ProviderRequest request) {
        return ResponseEntity.ok(providerService.update(id, request));
    }
}
