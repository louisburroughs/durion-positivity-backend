package com.positivity.warranty.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.warranty.internal.dto.PolicyRequest;
import com.positivity.warranty.internal.dto.PolicyResponse;
import com.positivity.warranty.internal.enums.CoverageType;
import com.positivity.warranty.internal.security.WarrantyPermissions;
import com.positivity.warranty.internal.service.PolicyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
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

/** Warranty policy CRUD and applicable-policy lookup (PRD §3.2, §8 rows 2-3). */
@Tag(name = "Warranty Policies", description = "Structured coverage terms — one row per distinct program")
@RestController
@RequiredArgsConstructor
@Validated
@SecurityRequirement(name = "bearerAuth")
@RequestMapping("/v1/warranty/policies")
public class PolicyController {

    private static final String POLICY_EXAMPLE = """
            {"providerId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a10",
             "name":"Michelin passenger replacement — workmanship & materials",
             "coverageType":"MANUFACTURER_DEFECT",
             "appliesToType":"MANUFACTURER",
             "appliesToManufacturerId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a11",
             "effectiveFrom":"2025-01-01",
             "durationMonths":72,
             "treadPullPointThirtySeconds":2,
             "laborCovered":false,
             "prorationMethod":"TREAD_DEPTH",
             "requiresPartReturn":true,
             "requiresPhotoEvidence":true,
             "transferable":false,
             "autoRegister":false,
             "termsText":"Prorated on remaining tread down to the 2/32 pull point."}
            """;

    private final PolicyService policyService;

    @Operation(operationId = "listPolicies", summary = "List policies", description = """
                    Returns warranty policies, optionally filtered by owning provider and/or coverage type.
                    Use this tool to browse configured coverage programs; use findApplicablePolicies instead to \
                    resolve which policies cover a specific product on a specific sale date.
                    Preconditions: none — an empty list is returned when nothing matches.
                    Required inputs: providerId and coverageType are optional query parameters; when both are \
                    given the provider filter is applied first and coverage type filters the result.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with the list, which is empty rather than 404 when no policy matches.
                    """)
    @ApiResponse(responseCode = "200", description = "Policies returned.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.POLICY_VIEW + "')")
    @GetMapping
    public ResponseEntity<List<PolicyResponse>> listPolicies(
            @Parameter(description = "Filter by owning provider id") @RequestParam(required = false) UUID providerId,
            @Parameter(description = "Filter by coverage type") @RequestParam(required = false)
                    CoverageType coverageType) {
        return ResponseEntity.ok(policyService.list(providerId, coverageType));
    }

    @Operation(operationId = "findApplicablePolicies", summary = "Find applicable policies", description = """
                    Resolves the policies in effect on the original sale date whose appliesTo scope matches any \
                    provided catalog key, ordered most-specific-first (PRODUCT_LIST, then MANUFACTURER, then \
                    CATEGORY, then ALL).
                    Use this tool during intake or eligibility work to find the governing policy for a product; \
                    do not use listPolicies, which lists policies without effective-date or scope matching.
                    Preconditions: policies must exist with an effectiveFrom/effectiveTo window covering the sale \
                    date; ALL-scoped policies always match, while scoped policies match only when the \
                    corresponding key is provided and equal.
                    Required inputs: saleDate (ISO-8601 date) is required; productEntityId, manufacturerId, and \
                    categoryId are optional pos-catalog keys, and coverageType optionally restricts the result to \
                    one coverage type.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with matching policies most specific first (empty when none match), and 400 when \
                    saleDate is missing or malformed.
                    """)
    @ApiResponse(responseCode = "200", description = "Matching policies returned, most specific first.")
    @ApiResponse(responseCode = "400", description = "Missing or invalid parameters.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.POLICY_VIEW + "')")
    @GetMapping("/applicable")
    public ResponseEntity<List<PolicyResponse>> findApplicablePolicies(
            @Parameter(description = "pos-catalog productEntityId of the product sold") @RequestParam(required = false)
                    UUID productEntityId,
            @Parameter(description = "pos-catalog manufacturerId of the product sold") @RequestParam(required = false)
                    UUID manufacturerId,
            @Parameter(description = "pos-catalog category id of the product sold") @RequestParam(required = false)
                    UUID categoryId,
            @Parameter(description = "Original sale date (ISO-8601)", required = true)
                    @RequestParam
                    @NotNull
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate saleDate,
            @Parameter(description = "Restrict to one coverage type") @RequestParam(required = false)
                    CoverageType coverageType) {
        return ResponseEntity.ok(
                policyService.findApplicable(productEntityId, manufacturerId, categoryId, saleDate, coverageType));
    }

    @Operation(operationId = "getPolicy", summary = "Get policy", description = """
                    Returns one warranty policy with its full structured coverage terms, including proration \
                    method, caps, and evidence requirements.
                    Use this tool when the policy id is already known; use listPolicies or findApplicablePolicies \
                    instead to locate a policy.
                    Preconditions: the policy must exist.
                    Required inputs: id (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when no policy exists for the supplied id.
                    """)
    @ApiResponse(responseCode = "200", description = "Policy returned.")
    @ApiResponse(responseCode = "404", description = "Policy not found.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.POLICY_VIEW + "')")
    @GetMapping("/{id}")
    public ResponseEntity<PolicyResponse> getPolicy(
            @Parameter(description = "Policy UUID", required = true) @PathVariable @NotNull UUID id) {
        return ResponseEntity.ok(policyService.getById(id));
    }

    @Operation(operationId = "createPolicy", summary = "Create policy", description = """
                    Creates a warranty policy — one row of structured coverage terms owned by a provider, \
                    governing eligibility, proration, and evidence requirements for claims.
                    Use this tool to configure a new coverage program; do not use updatePolicy, which fully \
                    replaces an existing policy, and do not use createRegistration, which records coverage sold \
                    to one customer under a policy.
                    Preconditions: the owning provider must exist; the appliesTo scope must be internally \
                    consistent — PRODUCT_LIST needs a non-empty appliesToProductEntityIds, CATEGORY needs \
                    appliesToCategoryId, MANUFACTURER needs appliesToManufacturerId — and effectiveTo must not \
                    precede effectiveFrom.
                    Required inputs: providerId (UUID), name, coverageType, appliesToType, effectiveFrom (ISO \
                    date), and prorationMethod (NONE, TREAD_DEPTH, MILEAGE, or TIME); laborCovered, \
                    requiresPartReturn, requiresPhotoEvidence, transferable, and autoRegister all default to \
                    false.
                    Emits a WARRANTY_POLICY_CREATE event; no claims or registrations are touched.
                    Returns 404 when the referenced provider cannot be resolved, and 400 when the appliesTo scope \
                    keys or the effective window are inconsistent.
                    """)
    @ApiResponse(responseCode = "201", description = "Policy created.")
    @ApiResponse(responseCode = "400", description = "Invalid request.")
    @ApiResponse(responseCode = "404", description = "Referenced provider not found.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.POLICY_MANAGE + "')")
    @EmitEvent(id = "WARRANTY_POLICY_CREATE", apiVersion = "1")
    @PostMapping
    public ResponseEntity<PolicyResponse> createPolicy(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Structured coverage terms of the program: scope, effective window,"
                                    + " limits, proration method, and evidence requirements.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Manufacturer tread-depth policy",
                                                            value = POLICY_EXAMPLE)))
                    @Valid
                    @NotNull
                    @RequestBody
                    PolicyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(policyService.create(request));
    }

    @Operation(operationId = "updatePolicy", summary = "Update policy", description = """
                    Fully replaces an existing warranty policy's structured coverage terms; every field is \
                    rewritten from the request, and omitted optional booleans reset to false.
                    Use this tool to change a coverage program; do not use createPolicy, which adds a new \
                    program — claims already decided keep their frozen proration inputs regardless of policy \
                    edits.
                    Preconditions: the policy and the referenced provider must exist, the appliesTo scope keys \
                    must be consistent with appliesToType, and effectiveTo must not precede effectiveFrom.
                    Required inputs: id (UUID) as a path parameter and the same full body as createPolicy — this \
                    is full-update semantics, so omitting an optional field clears it or resets its default.
                    Emits a WARRANTY_POLICY_UPDATE event.
                    Returns 404 when the policy or the referenced provider cannot be resolved, and 400 when the \
                    appliesTo scope keys or the effective window are inconsistent.
                    """)
    @ApiResponse(responseCode = "200", description = "Policy updated.")
    @ApiResponse(responseCode = "400", description = "Invalid request.")
    @ApiResponse(responseCode = "404", description = "Policy or referenced provider not found.")
    @PreAuthorize("hasAuthority('" + WarrantyPermissions.POLICY_MANAGE + "')")
    @EmitEvent(id = "WARRANTY_POLICY_UPDATE", apiVersion = "1")
    @PutMapping("/{id}")
    public ResponseEntity<PolicyResponse> updatePolicy(
            @Parameter(description = "Policy UUID", required = true) @PathVariable @NotNull UUID id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Full replacement of the policy's structured coverage terms"
                                    + " (full-update semantics).",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Manufacturer tread-depth policy",
                                                            value = POLICY_EXAMPLE)))
                    @Valid
                    @NotNull
                    @RequestBody
                    PolicyRequest request) {
        return ResponseEntity.ok(policyService.update(id, request));
    }
}
