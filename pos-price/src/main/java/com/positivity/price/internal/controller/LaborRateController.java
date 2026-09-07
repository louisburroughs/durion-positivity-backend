package com.positivity.price.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.price.internal.dto.LaborRateAdjustmentRequest;
import com.positivity.price.internal.dto.LaborRateAdjustmentResponse;
import com.positivity.price.internal.dto.LaborRateRequest;
import com.positivity.price.internal.dto.LaborRateResponse;
import com.positivity.price.internal.security.PricingPermissions;
import com.positivity.price.internal.service.LaborRateAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authoring for shop labor rates and the labor matrix (#1575 Tier 0, T0-3). Thin by design: all
 * validation and mapping live in {@link LaborRateAdminService}.
 */
@Tag(
        name = "Labor Rates",
        description = "Shop hourly labor rates and the labor matrix — the price half of a labor line,"
                + " paired with pos-catalog's estimated service time.")
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/labor-rates")
public class LaborRateController {

    private final LaborRateAdminService laborRateAdminService;

    @Operation(operationId = "createLaborRate", summary = "Create A Labor Rate", description = """
            Stores an hourly labor rate for a scope and a time window: a location and an operation category, \
            either of which may be omitted to widen the rate to every location or every category.
            Use this tool to set what an hour of shop labor costs; do not use it to set how long an operation \
            takes, which is the pos-catalog labor standard.
            Preconditions: none beyond authorization. Rates are never edited in place — correcting one means \
            closing its window and creating a new rate, so an invoice quoted at the old rate stays explainable.
            Required inputs: currency, hourlyRate greater than zero, and effectiveFrom; locationId, \
            operationCategory and effectiveTo are optional.
            Emits a PRICE_LABOR_RATE_CREATE event.
            Returns 201 with the stored rate, 422 when a field is not storable as asked, and 409 when a rate \
            already opens the same scope at the same instant.
            """)
    @ApiResponse(responseCode = "201", description = "Labor rate created.")
    @ApiResponse(responseCode = "401", description = "Authentication required.")
    @ApiResponse(responseCode = "403", description = "Insufficient permissions.")
    @ApiResponse(responseCode = "422", description = "The rate cannot be stored as described.")
    @EmitEvent(id = "PRICE_LABOR_RATE_CREATE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {PricingPermissions.LABOR_RATE_MANAGE})
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + PricingPermissions.LABOR_RATE_MANAGE + "')")
    @PostMapping
    public ResponseEntity<LaborRateResponse> createLaborRate(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The rate, its currency, the scope it applies to (omit locationId or"
                                    + " operationCategory to widen it) and the window it takes effect in.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = LaborRateRequest.class),
                                            examples =
                                                    @ExampleObject(
                                                            name = "A location's tire-service rate",
                                                            value = """
                                                            {"locationId":"0198f2a1-0000-7000-8000-00000000000a",
                                                             "operationCategory":"TIRE_SERVICE",
                                                             "currency":"USD","hourlyRate":105.00,
                                                             "effectiveFrom":"2026-01-01T00:00:00Z"}
                                                            """)))
                    @Valid
                    @RequestBody
                    LaborRateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(laborRateAdminService.createRate(request));
    }

    @Operation(operationId = "listLaborRates", summary = "List Labor Rates", description = """
            Lists every stored labor rate, newest effective window first, including windows that have closed.
            Use this tool to see what a location charges and when each rate took effect; do not use it to price \
            a specific job, which is resolveLaborRate — this returns the whole table, not the one applicable rate.
            Preconditions: none beyond authorization.
            Required inputs: none.
            Emits a PRICE_LABOR_RATE_LIST event; no state changes.
            Returns 200 with the rates, and an empty list when none are stored.
            """)
    @ApiResponse(responseCode = "200", description = "The stored labor rates.")
    @ApiResponse(responseCode = "401", description = "Authentication required.")
    @ApiResponse(responseCode = "403", description = "Insufficient permissions.")
    @EmitEvent(id = "PRICE_LABOR_RATE_LIST", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {PricingPermissions.LABOR_RATE_VIEW})
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + PricingPermissions.LABOR_RATE_VIEW + "')")
    @GetMapping
    public ResponseEntity<List<LaborRateResponse>> listLaborRates() {
        return ResponseEntity.ok(laborRateAdminService.listRates());
    }

    @Operation(operationId = "createLaborRateAdjustment", summary = "Create A Labor Matrix Step", description = """
            Stores one step of a shop's labor matrix: a percentage or flat adjustment that a quote opts into by \
            naming its code, for conditions a guide time assumes away such as corrosion, restricted access, \
            after-hours work or a fleet contract.
            Use this tool to configure what a shop charges beyond its base rate; do not use it to change the base \
            rate itself, which is createLaborRate.
            Preconditions: none beyond authorization. Steps apply in sequence order and percentage steps compound, \
            so the sequence changes the resulting rate and is part of the configuration, not a display hint.
            Required inputs: adjustmentCode, adjustmentType (PERCENT or FIXED), adjustmentValue, sequence and \
            effectiveFrom; locationId, operationCategory, description and effectiveTo are optional.
            Emits a PRICE_LABOR_RATE_ADJUSTMENT_CREATE event.
            Returns 201 with the stored step, 422 when a field is not storable as asked, and 409 when the same \
            code already opens the same scope at the same instant.
            """)
    @ApiResponse(responseCode = "201", description = "Labor matrix step created.")
    @ApiResponse(responseCode = "401", description = "Authentication required.")
    @ApiResponse(responseCode = "403", description = "Insufficient permissions.")
    @ApiResponse(responseCode = "422", description = "The step cannot be stored as described.")
    @EmitEvent(id = "PRICE_LABOR_RATE_ADJUSTMENT_CREATE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {PricingPermissions.LABOR_RATE_MANAGE})
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + PricingPermissions.LABOR_RATE_MANAGE + "')")
    @PostMapping("/adjustments")
    public ResponseEntity<LaborRateAdjustmentResponse> createLaborRateAdjustment(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The step's opt-in code, how it changes the running rate, and where it"
                                    + " sits in the matrix — sequence matters, because percentage steps compound.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = LaborRateAdjustmentRequest.class),
                                            examples = @ExampleObject(name = "Corroded fasteners, +15%", value = """
                                                            {"adjustmentCode":"CORROSION",
                                                             "description":"Seized or corroded fasteners",
                                                             "adjustmentType":"PERCENT","adjustmentValue":15.0,
                                                             "sequence":10,
                                                             "effectiveFrom":"2026-01-01T00:00:00Z"}
                                                            """)))
                    @Valid
                    @RequestBody
                    LaborRateAdjustmentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(laborRateAdminService.createAdjustment(request));
    }

    @Operation(operationId = "listLaborRateAdjustments", summary = "List Labor Matrix Steps", description = """
            Lists every stored labor-matrix step in application order, including windows that have closed.
            Use this tool to see which conditions a shop prices and by how much; do not use it to find which steps \
            apply to one job, which is resolveLaborRate — that applies the caller's opted-in codes and returns the \
            resulting rate.
            Preconditions: none beyond authorization.
            Required inputs: none.
            Emits a PRICE_LABOR_RATE_ADJUSTMENT_LIST event; no state changes.
            Returns 200 with the steps, and an empty list when none are stored.
            """)
    @ApiResponse(responseCode = "200", description = "The stored labor matrix steps.")
    @ApiResponse(responseCode = "401", description = "Authentication required.")
    @ApiResponse(responseCode = "403", description = "Insufficient permissions.")
    @EmitEvent(id = "PRICE_LABOR_RATE_ADJUSTMENT_LIST", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {PricingPermissions.LABOR_RATE_VIEW})
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + PricingPermissions.LABOR_RATE_VIEW + "')")
    @GetMapping("/adjustments")
    public ResponseEntity<List<LaborRateAdjustmentResponse>> listLaborRateAdjustments() {
        return ResponseEntity.ok(laborRateAdminService.listAdjustments());
    }
}
