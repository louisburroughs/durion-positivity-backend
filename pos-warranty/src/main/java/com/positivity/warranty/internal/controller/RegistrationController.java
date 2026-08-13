package com.positivity.warranty.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.warranty.internal.dto.RegistrationRequest;
import com.positivity.warranty.internal.dto.RegistrationResponse;
import com.positivity.warranty.internal.enums.RegistrationStatus;
import com.positivity.warranty.internal.security.WarrantyPermissions;
import com.positivity.warranty.internal.service.RegistrationService;
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

/** Sold-coverage (registration) CRUD and lookup (PRD §3.3, §8 row 4). */
@Tag(
        name = "Warranty Registrations",
        description = "Sold/instantiated coverage — road-hazard add-ons and extended plans")
@RestController
@RequiredArgsConstructor
@Validated
@SecurityRequirement(name = "bearerAuth")
@RequestMapping("/v1/warranty/registrations")
public class RegistrationController {

    private static final String REGISTRATION_EXAMPLE = """
            {"policyId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a30",
             "customerId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a01",
             "vehicleId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a02",
             "sourceInvoiceId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a04",
             "sourceInvoiceLineId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a05",
             "contractNumber":"RH-2026-88412",
             "purchaseDate":"2026-05-14",
             "expiresAt":"2029-05-14",
             "status":"ACTIVE"}
            """;

    private final RegistrationService registrationService;

    @Operation(operationId = "searchRegistrations", summary = "Search registrations", description = """
                    Searches sold-coverage registrations, optionally filtered by customer, vehicle, and lifecycle \
                    status.
                    Use this tool to find a customer's road-hazard or extended-plan coverage; use getRegistration \
                    instead when the registration id is already known.
                    Preconditions: none — an empty list is returned when nothing matches.
                    Required inputs: customerId, vehicleId, and status (ACTIVE, EXPIRED, CANCELLED, EXHAUSTED) \
                    are all optional query parameters; with no filters every registration is returned.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with the list, which is empty rather than 404 when nothing matches.
                    """)
    @ApiResponse(responseCode = "200", description = "Registrations returned.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.REGISTRATION_VIEW + "')")
    @GetMapping
    public ResponseEntity<List<RegistrationResponse>> searchRegistrations(
            @Parameter(description = "Filter by customer id") @RequestParam(required = false) UUID customerId,
            @Parameter(description = "Filter by vehicle id") @RequestParam(required = false) UUID vehicleId,
            @Parameter(description = "Filter by lifecycle status") @RequestParam(required = false)
                    RegistrationStatus status) {
        return ResponseEntity.ok(registrationService.search(customerId, vehicleId, status));
    }

    @Operation(operationId = "getRegistration", summary = "Get registration", description = """
                    Returns one warranty registration — the sold/instantiated coverage binding a policy to a \
                    customer, and optionally to a vehicle and its originating invoice line.
                    Use this tool when the registration id is already known; use searchRegistrations instead to \
                    locate coverage by customer, vehicle, or status.
                    Preconditions: the registration must exist.
                    Required inputs: id (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when no registration exists for the supplied id.
                    """)
    @ApiResponse(responseCode = "200", description = "Registration returned.")
    @ApiResponse(responseCode = "404", description = "Registration not found.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.REGISTRATION_VIEW + "')")
    @GetMapping("/{id}")
    public ResponseEntity<RegistrationResponse> getRegistration(
            @Parameter(description = "Registration UUID", required = true) @PathVariable @NotNull UUID id) {
        return ResponseEntity.ok(registrationService.getById(id));
    }

    @Operation(operationId = "createRegistration", summary = "Create registration", description = """
                    Records sold/instantiated coverage — a registration binding a governing policy to a customer, \
                    and optionally to a vehicle and the originating invoice line.
                    Use this tool when coverage such as a road-hazard add-on or extended plan is sold; do not use \
                    createPolicy, which defines the reusable program a registration instantiates.
                    Preconditions: the governing policy must exist; policies flagged autoRegister create \
                    registrations automatically from invoiced sales, so manual creation covers the remaining \
                    cases.
                    Required inputs: policyId (UUID), customerId (UUID), and purchaseDate (ISO date); vehicleId, \
                    source invoice references, contractNumber, and expiresAt are optional, and status defaults to \
                    ACTIVE.
                    Emits a WARRANTY_REGISTRATION_CREATE event.
                    Returns 404 when the referenced policy cannot be resolved.
                    """)
    @ApiResponse(responseCode = "201", description = "Registration created.")
    @ApiResponse(responseCode = "400", description = "Invalid request.")
    @ApiResponse(responseCode = "404", description = "Referenced policy not found.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.REGISTRATION_MANAGE + "')")
    @EmitEvent(id = "WARRANTY_REGISTRATION_CREATE", apiVersion = "1")
    @PostMapping
    public ResponseEntity<RegistrationResponse> createRegistration(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Sold coverage to record: governing policy, covered customer and"
                                    + " vehicle, and the originating sale references.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Road-hazard registration",
                                                            value = REGISTRATION_EXAMPLE)))
                    @Valid
                    @NotNull
                    @RequestBody
                    RegistrationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(registrationService.create(request));
    }

    @Operation(operationId = "updateRegistration", summary = "Update registration", description = """
                    Fully replaces an existing warranty registration's fields, including the policy binding, \
                    source references, and coverage dates.
                    Use this tool to correct or expire sold coverage; do not use createRegistration, which \
                    records new coverage.
                    Preconditions: the registration and the referenced policy must exist; omitting status keeps \
                    the current value, while the other fields are rewritten from the request and null clears \
                    them.
                    Required inputs: id (UUID) as a path parameter, plus policyId, customerId, and purchaseDate; \
                    vehicleId, source invoice references, contractNumber, expiresAt, and status (ACTIVE, EXPIRED, \
                    CANCELLED, EXHAUSTED) are optional.
                    Emits a WARRANTY_REGISTRATION_UPDATE event.
                    Returns 404 when the registration or the referenced policy cannot be resolved.
                    """)
    @ApiResponse(responseCode = "200", description = "Registration updated.")
    @ApiResponse(responseCode = "400", description = "Invalid request.")
    @ApiResponse(responseCode = "404", description = "Registration or referenced policy not found.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.REGISTRATION_MANAGE + "')")
    @EmitEvent(id = "WARRANTY_REGISTRATION_UPDATE", apiVersion = "1")
    @PutMapping("/{id}")
    public ResponseEntity<RegistrationResponse> updateRegistration(
            @Parameter(description = "Registration UUID", required = true) @PathVariable @NotNull UUID id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Full replacement of the registration's fields (omitting status keeps"
                                    + " the current value).",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Road-hazard registration",
                                                            value = REGISTRATION_EXAMPLE)))
                    @Valid
                    @NotNull
                    @RequestBody
                    RegistrationRequest request) {
        return ResponseEntity.ok(registrationService.update(id, request));
    }
}
