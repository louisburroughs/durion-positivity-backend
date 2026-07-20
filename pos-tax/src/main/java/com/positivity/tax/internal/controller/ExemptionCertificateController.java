package com.positivity.tax.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.tax.internal.dto.ExemptionCertificateRequest;
import com.positivity.tax.internal.dto.ExemptionCertificateResponse;
import com.positivity.tax.service.ExemptionCertificateService;
import io.swagger.v3.oas.annotations.Operation;
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
    @PreAuthorize("hasAuthority('tax:exemption:view')")
    @Operation(summary = "List exemption certificates", description = "List certificates, optionally by customer")
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
    @PreAuthorize("hasAuthority('tax:exemption:view')")
    @Operation(summary = "Get an exemption certificate", description = "Fetch a single certificate by id")
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
    @PreAuthorize("hasAuthority('tax:exemption:manage')")
    @EmitEvent(id = "TAX_EXEMPTION_CERT_CREATE", apiVersion = "1")
    @Operation(summary = "Create an exemption certificate", description = "Register a new certificate")
    @ApiResponse(responseCode = "201", description = "Certificate created")
    @ApiResponse(responseCode = "400", description = "Invalid certificate payload")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"tax:exemption:manage"})
    public ResponseEntity<ExemptionCertificateResponse> create(
            @Valid @RequestBody ExemptionCertificateRequest request) {
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
    @PreAuthorize("hasAuthority('tax:exemption:manage')")
    @EmitEvent(id = "TAX_EXEMPTION_CERT_UPDATE", apiVersion = "1")
    @Operation(summary = "Update an exemption certificate", description = "Update an existing certificate")
    @ApiResponse(responseCode = "200", description = "Certificate updated")
    @ApiResponse(responseCode = "404", description = "Certificate not found")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"tax:exemption:manage"})
    public ResponseEntity<ExemptionCertificateResponse> update(
            @PathVariable UUID id, @Valid @RequestBody ExemptionCertificateRequest request) {
        return ResponseEntity.ok(service.update(id, request));
    }
}
