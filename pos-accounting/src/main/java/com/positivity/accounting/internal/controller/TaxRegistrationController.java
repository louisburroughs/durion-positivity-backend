package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.ChangeTaxRegistrationRequest;
import com.positivity.accounting.internal.dto.RecordTaxRegistrationRequest;
import com.positivity.accounting.internal.dto.TaxRegistrationListResponse;
import com.positivity.accounting.internal.dto.TaxRegistrationView;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.accounting.internal.service.TaxRegistrationFrontDoorService;
import com.positivity.events.EmitEvent;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
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
 * The tenant's indirect-tax registrations (CAP:550 S32c; SPEC-accounting-workspace §4.7, AW58/AW59; ADR-0071 §5):
 * the front door to pos-tax's registry. Reads come from accounting's copy; writes are passed to pos-tax with the
 * person's id as the actor, taken only from the authenticated principal (ADR-0018), never from the body.
 *
 * <p>Nothing here logs a request: it carries a registration number, which is never logged.
 */
@RestController
@RequestMapping("/v1/accounting/tax-registrations")
@Validated
@Tag(name = "Accounting Tax Registrations", description = "Indirect-tax registrations the company holds")
public class TaxRegistrationController {

    private final TaxRegistrationFrontDoorService service;

    public TaxRegistrationController(@NonNull TaxRegistrationFrontDoorService service) {
        this.service = service;
    }

    @GetMapping
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:tax_registration:view"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.TAX_REGISTRATION_VIEW + "')")
    @Operation(
            operationId = "listTaxRegistrations",
            summary = "List Tax Registrations",
            description = """
                    Lists the company's indirect-tax registrations from accounting's copy of pos-tax's registry: \
                    per registration its country, regime, number, jurisdiction, effective dates (both inclusive), \
                    status and version.
                    Use this tool to show which regimes the company is registered for and from when; do not use \
                    it to change one, which is recordTaxRegistration or changeTaxRegistration instead.
                    Preconditions: caller holds accounting:tax_registration:view. Read-only and idempotent; the \
                    copy follows pos-tax's tax.registration.changed, so a write appears here shortly after it \
                    commits.
                    Required inputs: none; asOf (ISO-8601 date) returns only the registrations in effect that day.
                    Emits an ACCOUNTING_TAX_REGISTRATION_LIST event and returns 200; status is SCHEDULED, ACTIVE or \
                    ENDED on asOf, else on today's UTC date.
                    """,
            tags = {"Accounting Tax Registrations"})
    @ApiResponse(responseCode = "200", description = "The registrations")
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_ERROR: asOf is not an ISO-8601 date",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks accounting:tax_registration:view",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "ACCOUNTING_TAX_REGISTRATION_LIST", apiVersion = "1")
    public ResponseEntity<TaxRegistrationListResponse> list(
            @Parameter(description = "Only the registrations in effect on this date", example = "2026-10-08")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate asOf) {
        return ResponseEntity.ok(service.list(asOf));
    }

    @PostMapping
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:tax_registration:manage"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.TAX_REGISTRATION_MANAGE + "')")
    @Operation(
            operationId = "recordTaxRegistration",
            summary = "Record Tax Registration",
            description = """
                    Records that the company is registered for one country's indirect-tax regime from a date, in \
                    pos-tax's registry, through this front door.
                    Use this tool when the company registers for a regime; do not use it to end or correct an \
                    existing registration, which is changeTaxRegistration instead.
                    Preconditions: caller holds accounting:tax_registration:manage and is a person (403 otherwise); \
                    the country has a tax profile that declares the regime (422 relayed).
                    Required inputs: countryCode, regime, registrationNumber, effectiveFrom, justification (at least \
                    10 characters) and requestId; effectiveTo is optional and inclusive.
                    The number must match the regime's configured shape: otherwise 400 VALIDATION_ERROR with \
                    fieldErrors[registrationNumber], nothing is stored, and the value is never echoed or logged.
                    Emits an ACCOUNTING_TAX_REGISTRATION_CREATE event and returns 201, or 200 with the first result \
                    for a replayed requestId; pos-tax's 409 TAX_REGISTRATION_OVERLAP and IDEMPOTENCY_CONFLICT are \
                    relayed unchanged.
                    Returns 503 SERVICE_UNAVAILABLE with Retry-After when pos-tax cannot be reached, and nothing is \
                    stored.
                    """,
            tags = {"Accounting Tax Registrations"})
    @ApiResponse(responseCode = "201", description = "The registration was recorded")
    @ApiResponse(responseCode = "200", description = "A replayed requestId: the first result")
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_ERROR: a missing or invalid field or a malformed number",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks accounting:tax_registration:manage, or is not a person",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "TAX_REGISTRATION_OVERLAP or IDEMPOTENCY_CONFLICT, relayed from pos-tax",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "TAX_JURISDICTION_NOT_CONFIGURED or TAX_REGIME_NOT_DECLARED, relayed from pos-tax",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "503",
            description = "SERVICE_UNAVAILABLE: pos-tax cannot be reached; retry after Retry-After seconds",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "ACCOUNTING_TAX_REGISTRATION_CREATE", apiVersion = "1")
    public ResponseEntity<TaxRegistrationView> create(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            description = "The country's regime, the number as printed, its dates and why",
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = RecordTaxRegistrationRequest.class),
                                            examples = @ExampleObject(name = "Registered from January", value = """
                                                    {"countryCode":"CA","regime":"GST_HST","registrationNumber":"123456789 RT 0001","effectiveFrom":"2026-01-01","justification":"Registered with the tax authority","requestId":"019a0000-0000-7000-8000-000000000201"}
                                                    """)))
                    @RequestBody
                    RecordTaxRegistrationRequest request) {
        TaxRegistrationFrontDoorService.Result result = service.create(request, actor());
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(result.registration());
    }

    @PutMapping("/{registrationId}")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:tax_registration:manage"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.TAX_REGISTRATION_MANAGE + "')")
    @Operation(
            operationId = "changeTaxRegistration",
            summary = "Change Tax Registration",
            description = """
                    Changes a tax registration's number or dates in pos-tax's registry, or ends it by setting \
                    effectiveTo; the country and regime never change, and a registration is never deleted.
                    Use this tool to correct or end a registration; do not use it to register another regime, \
                    which is recordTaxRegistration instead.
                    Preconditions: caller holds accounting:tax_registration:manage and is a person (403 otherwise); \
                    the registration exists (404, relayed); version is the current one (409 OPTIMISTIC_LOCK, \
                    relayed).
                    Required inputs: registrationId (path), registrationNumber, effectiveFrom, version, \
                    justification (at least 10 characters) and requestId; effectiveTo is optional and inclusive.
                    The number must match the regime's configured shape (400 VALIDATION_ERROR with \
                    fieldErrors[registrationNumber]); back-dating never changes an entry already posted.
                    Emits an ACCOUNTING_TAX_REGISTRATION_UPDATE event and returns 200, also for a replayed requestId; \
                    pos-tax's 409 TAX_REGISTRATION_OVERLAP and IDEMPOTENCY_CONFLICT are relayed unchanged.
                    Returns 503 SERVICE_UNAVAILABLE with Retry-After when pos-tax cannot be reached, and nothing \
                    changes.
                    """,
            tags = {"Accounting Tax Registrations"})
    @ApiResponse(responseCode = "200", description = "The registration was changed, or a replayed requestId")
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_ERROR: a missing or invalid field or a malformed number",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks accounting:tax_registration:manage, or is not a person",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "TAX_REGISTRATION_NOT_FOUND, relayed from pos-tax",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "TAX_REGISTRATION_OVERLAP, OPTIMISTIC_LOCK or IDEMPOTENCY_CONFLICT, relayed from pos-tax",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "503",
            description = "SERVICE_UNAVAILABLE: pos-tax cannot be reached; retry after Retry-After seconds",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "ACCOUNTING_TAX_REGISTRATION_UPDATE", apiVersion = "1")
    public ResponseEntity<TaxRegistrationView> update(
            @Parameter(description = "Registration id") @PathVariable UUID registrationId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            description = "The registration's new number or dates, the version read, and why",
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = ChangeTaxRegistrationRequest.class),
                                            examples = @ExampleObject(name = "Ended in May", value = """
                                                    {"registrationNumber":"123456789 RT 0001","effectiveFrom":"2026-01-01","effectiveTo":"2026-05-31","version":0,"justification":"Deregistered at the end of May","requestId":"019a0000-0000-7000-8000-000000000202"}
                                                    """)))
                    @RequestBody
                    ChangeTaxRegistrationRequest request) {
        return ResponseEntity.ok(service.update(registrationId, request, actor()));
    }

    /**
     * The person behind the request: the stable user id of the authenticated principal (ADR-0018, ADR-0022), which the
     * gateway authenticated and forwarded. A request without a person's id cannot be audited, so it is refused (403).
     * The client forwards this same id to pos-tax as {@code X-User-Id} (ADR-0071 §5).
     */
    private static UUID actor() {
        try {
            return SecurityContextHelper.getCurrentUserIdAsUuid()
                    .orElseThrow(() -> new AccessDeniedException("A tax-registration write needs a person's identity"));
        } catch (IllegalStateException e) {
            // MissingPersonIdException included: no user id, or not a UUID, in the authentication details.
            throw new AccessDeniedException("A tax-registration write needs a person's identity");
        }
    }
}
