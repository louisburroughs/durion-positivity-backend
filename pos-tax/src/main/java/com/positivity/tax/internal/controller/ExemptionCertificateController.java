package com.positivity.tax.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.tax.internal.dto.ExemptionCertificateRequest;
import com.positivity.tax.internal.dto.ExemptionCertificateResponse;
import com.positivity.tax.internal.security.TaxPermissions;
import com.positivity.tax.service.ExemptionCertificateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CRUD endpoints for the tax exemption-certificate registry (story T3, decision D-T1).
 * <p>
 * Thin controller: delegates to {@link ExemptionCertificateService}. Reads require
 * {@code tax:exemption:view}; mutations require {@code tax:exemption:manage} and emit
 * approval-preset audit events.
 */
@Slf4j
@RestController
@RequestMapping("/v1/tax/exemption-certificates")
@Tag(name = "Tax Exemption Certificates", description = "Registry of customer tax exemption certificates")
public class ExemptionCertificateController {

    private final ExemptionCertificateService service;

    public ExemptionCertificateController(ExemptionCertificateService service) {
        this.service = service;
    }

    /**
     * List exemption certificates, optionally filtered by customer.
     *
     * @param customerId optional customer filter
     * @return the certificates
     */
    @GetMapping
    @PreAuthorize("hasAuthority('" + TaxPermissions.EXEMPTION_VIEW + "')")
    @Operation(operationId = "listExemptionCertificates", summary = "List exemption certificates", description = """
                    Returns the registered tax exemption certificates, newest effective date first, optionally
                    narrowed to a single customer.
                    Use this tool to discover a certificate id before calculating tax with an exemption; do not use
                    it to test whether an exemption applies on a given date, which calculateTax resolves itself.
                    Preconditions: none; an unknown or unmatched customerId yields an empty list rather than an error.
                    Required inputs: none, and customerId is an optional query parameter matched exactly, not as a
                    substring.
                    No events are emitted and no state changes; this is a read-only registry projection.
                    Returns 200 with an empty array when no certificate matches, so an empty result is not an error
                    condition.
                    """)
    @ApiResponse(responseCode = "200", description = "Certificates listed")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"tax:exemption:view"})
    public ResponseEntity<List<ExemptionCertificateResponse>> list(@RequestParam(required = false) String customerId) {
        return ResponseEntity.ok(service.list(customerId));
    }

    /**
     * Get an exemption certificate by id.
     *
     * @param id the certificate id
     * @return the certificate
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + TaxPermissions.EXEMPTION_VIEW + "')")
    @Operation(operationId = "getExemptionCertificateById", summary = "Get an exemption certificate", description = """
                    Returns a single tax exemption certificate, including its scope, reason code and validity window.
                    Use this tool when the certificate id is already known; use listExemptionCertificates instead when
                    searching by customer.
                    Preconditions: the certificate must exist in the registry.
                    Required inputs: id (UUID) path parameter; there is no request body and no filtering.
                    No events are emitted and no state changes; this is a read-only registry projection.
                    Returns 404 when no certificate exists for the supplied id.
                    """)
    @ApiResponse(responseCode = "200", description = "Certificate found")
    @ApiResponse(responseCode = "404", description = "Certificate not found")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"tax:exemption:view"})
    public ResponseEntity<ExemptionCertificateResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(service.get(id));
    }

    /**
     * Create an exemption certificate.
     *
     * @param request the create payload
     * @return the created certificate
     */
    @PostMapping
    @PreAuthorize("hasAuthority('" + TaxPermissions.EXEMPTION_MANAGE + "')")
    @EmitEvent(id = "TAX_EXEMPTION_CERT_CREATE", apiVersion = "1")
    @Operation(
            operationId = "createExemptionCertificate",
            summary = "Create an exemption certificate",
            description = """
                    Registers a tax exemption certificate for a customer so that later calculations can treat their
                    line items as exempt.
                    Use this tool when a customer supplies a new certificate; do not use it to correct an existing
                    registration, which is updateExemptionCertificate.
                    Preconditions: none are enforced against other services, so the customerId is accepted as an
                    opaque identifier and is not verified against the customer registry.
                    Required inputs: customerId, reasonCode (RESALE, GOVERNMENT, NONPROFIT, AGRICULTURAL or OTHER)
                    and effectiveFrom; stateScope null means every state, expiresAt null means no expiry, and status
                    defaults to ACTIVE.
                    Emits a TAX_EXEMPTION_CERT_CREATE approval-preset audit event; no existing certificate is
                    superseded, so overlapping certificates for the same customer can coexist.
                    Returns 400 when customerId, reasonCode or effectiveFrom are missing or the reason code is not a
                    recognised value.
                    """)
    @ApiResponse(responseCode = "201", description = "Certificate created")
    @ApiResponse(responseCode = "400", description = "Invalid certificate payload")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"tax:exemption:manage"})
    public ResponseEntity<ExemptionCertificateResponse> create(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Exemption certificate to register for a customer.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Resale certificate for California",
                                                            value =
                                                                    "{\"customerId\":\"CUST-100234\",\"stateScope\":\"CA\",\"reasonCode\":\"RESALE\","
                                                                            + "\"certificateNumber\":\"RESALE-2026-0001\",\"effectiveFrom\":\"2026-01-01\","
                                                                            + "\"expiresAt\":\"2027-12-31\",\"status\":\"ACTIVE\"}")))
                    @Valid
                    @RequestBody
                    ExemptionCertificateRequest request) {
        ExemptionCertificateResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/v1/tax/exemption-certificates/" + created.id()))
                .body(created);
    }

    /**
     * Update an exemption certificate.
     *
     * @param id      the certificate id
     * @param request the update payload
     * @return the updated certificate
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + TaxPermissions.EXEMPTION_MANAGE + "')")
    @EmitEvent(id = "TAX_EXEMPTION_CERT_UPDATE", apiVersion = "1")
    @Operation(
            operationId = "updateExemptionCertificate",
            summary = "Update an exemption certificate",
            description = """
                    Replaces the stored details of an exemption certificate, including its customer, scope, reason
                    code and validity window.
                    Use this tool to correct or expire an existing registration; do not use it to register a further
                    certificate for the same customer, which is createExemptionCertificate.
                    Preconditions: the certificate must exist; expiring a certificate is done by setting expiresAt or
                    moving status to INACTIVE or REVOKED rather than by deleting it, because there is no delete endpoint.
                    Required inputs: id (UUID) path parameter plus the full body, since every supplied field replaces
                    the stored value; an omitted status leaves the current status unchanged.
                    Emits a TAX_EXEMPTION_CERT_UPDATE approval-preset audit event; tax already calculated against the
                    old values is not recalculated.
                    Returns 404 when no certificate exists for the supplied id, and 400 when customerId, reasonCode or
                    effectiveFrom are missing from the body.
                    """)
    @ApiResponse(responseCode = "200", description = "Certificate updated")
    @ApiResponse(responseCode = "404", description = "Certificate not found")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"tax:exemption:manage"})
    public ResponseEntity<ExemptionCertificateResponse> update(
            @PathVariable UUID id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Replacement details for the exemption certificate.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Expire a resale certificate",
                                                            value =
                                                                    "{\"customerId\":\"CUST-100234\",\"stateScope\":\"CA\",\"reasonCode\":\"RESALE\","
                                                                            + "\"certificateNumber\":\"RESALE-2026-0001\",\"effectiveFrom\":\"2026-01-01\","
                                                                            + "\"expiresAt\":\"2026-06-30\",\"status\":\"INACTIVE\"}")))
                    @Valid
                    @RequestBody
                    ExemptionCertificateRequest request) {
        return ResponseEntity.ok(service.update(id, request));
    }
}
