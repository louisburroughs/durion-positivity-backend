package com.positivity.tax.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import com.positivity.tax.internal.dto.TaxRegistrationCreateRequest;
import com.positivity.tax.internal.dto.TaxRegistrationResponse;
import com.positivity.tax.internal.dto.TaxRegistrationUpdateRequest;
import com.positivity.tax.internal.service.TaxRegistrationService;
import com.positivity.tax.internal.service.TaxRegistrationService.WriteResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The tenant tax-registration writes (CAP:550 S32c; ADR-0071 §5-7). They accept only the pos-accounting front door,
 * authenticated by its per-caller secret in its own security chain ({@code FrontDoorSecretFilter}); a person never
 * reaches them and no {@code tax:registration:*} permission exists, so the guard here is the authenticated front
 * door. Internal-only: no gateway route (ADR-0021).
 *
 * <p>Nothing here logs a request: it carries a registration number, which is never echoed or logged.
 */
@RestController
@RequestMapping("/v1/tax/registrations")
@PreAuthorize("isAuthenticated()")
@Tag(name = "Tax Registrations", description = "A tenant's indirect-tax registrations, written by pos-accounting")
public class TaxRegistrationController {

    static final String FRONT_DOOR_SCHEME = "accountingFrontDoorSecret";

    private final TaxRegistrationService service;

    public TaxRegistrationController(@NonNull TaxRegistrationService service) {
        this.service = service;
    }

    @PostMapping
    @SecurityRequirement(name = FRONT_DOOR_SCHEME)
    @Operation(operationId = "createTaxRegistration", summary = "Record a tax registration", description = """
                    Records a tenant's registration for one country's indirect-tax regime from a date, and queues \
                    tax.registration.changed on the outbox in the same transaction.
                    Use this tool only as the pos-accounting front door; do not use it from a screen or another \
                    service, which call POST /v1/accounting/tax-registrations instead.
                    Preconditions: the call carries pos-accounting's front-door secret and the forwarded X-User-Id \
                    and X-Tenant-Id (401 otherwise); the country has a tax profile and declares the regime (400).
                    Required inputs: countryCode, regime, registrationNumber, effectiveFrom, justification (at least \
                    10 characters) and requestId; effectiveTo is optional and inclusive.
                    The number must match the regime's configured shape (400 VALIDATION_ERROR with \
                    fieldErrors[registrationNumber]); it is never echoed or logged, and nothing is stored when it fails.
                    Emits a TAX_REGISTRATION_CREATE event, writes a history row naming the forwarded actor, and \
                    returns 201, or 200 with the first result for a replayed requestId.
                    Returns 409 TAX_REGISTRATION_OVERLAP when another registration of the same country and regime is \
                    in effect on any date this one covers.
                    """)
    @ApiResponse(responseCode = "201", description = "The registration was recorded")
    @ApiResponse(responseCode = "200", description = "A replayed requestId: the first result")
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_ERROR: a missing or malformed field, an undeclared regime or a malformed number",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = "Missing or wrong front-door secret, or no forwarded actor or tenant",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "TAX_REGISTRATION_OVERLAP",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "TAX_REGISTRATION_CREATE", apiVersion = "1")
    public ResponseEntity<TaxRegistrationResponse> create(@RequestBody TaxRegistrationCreateRequest request) {
        WriteResult result = service.create(request);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(result.registration());
    }

    @PutMapping("/{registrationId}")
    @SecurityRequirement(name = FRONT_DOOR_SCHEME)
    @Operation(operationId = "updateTaxRegistration", summary = "Change a tax registration", description = """
                    Changes a tax registration's number or dates, ending it by setting effectiveTo, and queues \
                    tax.registration.changed on the outbox in the same transaction; a registration is never deleted.
                    Use this tool only as the pos-accounting front door; do not use it to register another regime, \
                    which is createTaxRegistration instead.
                    Preconditions: the call carries pos-accounting's front-door secret and the forwarded X-User-Id \
                    and X-Tenant-Id (401 otherwise); the registration exists for the tenant (404); version is the \
                    current one (409 OPTIMISTIC_LOCK).
                    Required inputs: registrationId (path), registrationNumber, effectiveFrom, version, \
                    justification (at least 10 characters) and requestId; effectiveTo is optional and inclusive.
                    The number must match the regime's configured shape (400 VALIDATION_ERROR with \
                    fieldErrors[registrationNumber]); it is never echoed or logged, and nothing changes when it fails.
                    Emits a TAX_REGISTRATION_UPDATE event, writes a history row naming the forwarded actor, and \
                    returns 200, also with the first result for a replayed requestId.
                    Returns 409 TAX_REGISTRATION_OVERLAP when the new dates overlap another registration of the same \
                    country and regime; back-dating never changes an entry already posted.
                    """)
    @ApiResponse(responseCode = "200", description = "The registration was changed, or a replayed requestId")
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_ERROR: a missing or malformed field or a malformed number",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "401",
            description = "Missing or wrong front-door secret, or no forwarded actor or tenant",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "TAX_REGISTRATION_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "TAX_REGISTRATION_OVERLAP or OPTIMISTIC_LOCK",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "TAX_REGISTRATION_UPDATE", apiVersion = "1")
    public ResponseEntity<TaxRegistrationResponse> update(
            @Parameter(description = "Registration id") @PathVariable UUID registrationId,
            @RequestBody TaxRegistrationUpdateRequest request) {
        return ResponseEntity.ok(service.update(registrationId, request).registration());
    }
}
