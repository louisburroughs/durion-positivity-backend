package com.positivity.price.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.price.internal.dto.RestrictionEvaluationRequest;
import com.positivity.price.internal.dto.RestrictionEvaluationResponse;
import com.positivity.price.internal.dto.RestrictionEvaluationResult;
import com.positivity.price.internal.dto.RestrictionOverrideRequest;
import com.positivity.price.internal.dto.RestrictionOverrideResponse;
import com.positivity.price.service.RestrictionEvaluationService;
import com.positivity.price.service.RestrictionOverrideService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Price Restrictions", description = "Price restriction evaluation and override operations")
@RestController
@RequestMapping("/v1/price")
public class PriceRestrictionsController {

    private static final Logger log = LoggerFactory.getLogger(PriceRestrictionsController.class);

    private final RestrictionEvaluationService evaluationService;
    private final RestrictionOverrideService overrideService;

    public PriceRestrictionsController(
            RestrictionEvaluationService evaluationService, RestrictionOverrideService overrideService) {
        this.evaluationService = evaluationService;
        this.overrideService = overrideService;
    }

    @Operation(
            summary = "Evaluate price restrictions",
            description = "Evaluates whether products are subject to restrictions in the given context.")
    @ApiResponse(responseCode = "200", description = "Evaluation results per product.")
    @ApiResponse(responseCode = "400", description = "Invalid request body.")
    @ApiResponse(responseCode = "401", description = "Authentication required.")
    @ApiResponse(responseCode = "503", description = "Restriction evaluation service unavailable (commit path only).")
    @EmitEvent(id = "PRICE_RESTRICTIONS_EVALUATE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/restrictions:evaluate")
    public ResponseEntity<RestrictionEvaluationResponse> evaluateRestrictions(
            @Valid @RequestBody RestrictionEvaluationRequest request) {
        log.info(
                "POST /v1/price/restrictions:evaluate items={}", request.items().size());
        List<RestrictionEvaluationResult> results = evaluationService.evaluate(request);
        return ResponseEntity.ok(new RestrictionEvaluationResponse(results));
    }

    @Operation(
            summary = "Override price restrictions",
            description = "Issues an override for price restrictions. Requires pricing:restriction:override authority.")
    @ApiResponse(responseCode = "200", description = "Override issued. Returns overrideId and expiresAt.")
    @ApiResponse(responseCode = "400", description = "Invalid request body.")
    @ApiResponse(responseCode = "401", description = "Authentication required.")
    @ApiResponse(responseCode = "403", description = "Insufficient permissions to override restrictions.")
    @EmitEvent(id = "PRICE_RESTRICTIONS_OVERRIDE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"pricing:restriction:override"})
    @PreAuthorize("hasAuthority('pricing:restriction:override')")
    @PostMapping("/restrictions:override")
    public ResponseEntity<RestrictionOverrideResponse> overrideRestrictions(
            @Valid @RequestBody RestrictionOverrideRequest request) {
        log.info("POST /v1/price/restrictions:override context={}", request.overrideContext());
        RestrictionOverrideResponse response = overrideService.createOverride(request);
        return ResponseEntity.ok(response);
    }
}
