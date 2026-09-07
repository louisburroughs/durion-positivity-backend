package com.positivity.price.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.price.internal.security.PricingPermissions;
import com.positivity.price.service.ShopLaborRateService;
import com.positivity.price.service.model.LaborRateQuoteRequest;
import com.positivity.price.service.model.LaborRateQuoteResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The ADR-0044 amendment 2026-09-07 REST edge for labor-rate resolution (#1575 Tier 0, T0-3).
 * Thin by design and deliberately shaped like pos-catalog's labor-time edge: the contract lives
 * in {@code price.service.model}, the logic in the resolution service, and the one approved
 * caller is pos-workorder's {@code PriceLaborRateClientImpl}, file-scoped in the platform
 * {@code DomainWallsTest}.
 */
@Tag(
        name = "Labor Rate Resolution",
        description = "Scoped service-to-service edge: resolve the hourly labor rate in force for a location"
                + " and operation category, with the shop labor matrix itemised.")
@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/v1/labor-rates")
public class LaborRateQuoteController {

    private final ShopLaborRateService shopLaborRateService;

    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + PricingPermissions.LABOR_RATE_QUOTE + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", PricingPermissions.LABOR_RATE_QUOTE})
    @PostMapping("/quote")
    @EmitEvent(id = "PRICE_LABOR_RATE_QUOTE", apiVersion = "1")
    @Operation(operationId = "resolveLaborRate", summary = "Resolve The Labor Rate For A Job", description = """
            Answers the one hourly labor rate in force for a location and operation category at a moment, then \
            applies the labor-matrix steps the caller opted into and itemises each one with the rate it produced.
            Use this tool from the approved pos-workorder client to price a LABOR estimate line, paired with the \
            catalog labor time that says how many hours; do not use it to browse what rates exist, which is \
            listLaborRates.
            Preconditions: none. Scope narrows from the location's rate for the category through the location \
            default and the platform category rate to the platform default; an unknown category or an \
            adjustment code with no in-force step widens or is skipped rather than failing.
            Required inputs: a body, which may be empty — every field is optional, and omitting locationId \
            resolves the platform default rate.
            Emits a PRICE_LABOR_RATE_QUOTE event; no state changes.
            Returns 200 always for a well-formed request — no rate in force is the typed NO_RATE_AVAILABLE \
            status, never an error, and callers degrade to a blank price.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "The resolved rate with its matrix derivation, or a typed miss.",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = LaborRateQuoteResponse.class)))
    public ResponseEntity<LaborRateQuoteResponse> resolveLaborRate(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Whose rate, for what class of work, at what moment, and which matrix"
                                    + " steps the writer agreed apply.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = LaborRateQuoteRequest.class),
                                            examples =
                                                    @ExampleObject(
                                                            name = "Tire work at a location, corroded fasteners",
                                                            value = """
                                                            {"locationId":"0198f2a1-0000-7000-8000-00000000000a",
                                                             "operationCategory":"TIRE_SERVICE",
                                                             "adjustmentCodes":["CORROSION"]}
                                                            """)))
                    @Valid
                    @RequestBody
                    LaborRateQuoteRequest request) {
        return ResponseEntity.ok(shopLaborRateService.resolveLaborRate(request));
    }
}
