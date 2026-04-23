package com.positivity.price.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.price.internal.dto.CreateRestrictionRuleRequest;
import com.positivity.price.internal.dto.RestrictionRuleResponse;
import com.positivity.price.service.RestrictionRuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Restriction Rules", description = "CRUD operations for price restriction rules")
@RestController
@RequestMapping("/v1/price/restrictions/rules")
public class RestrictionRuleController {

    private static final Logger log = LoggerFactory.getLogger(RestrictionRuleController.class);

    private final RestrictionRuleService restrictionRuleService;

    public RestrictionRuleController(RestrictionRuleService restrictionRuleService) {
        this.restrictionRuleService = restrictionRuleService;
    }

    @Operation(summary = "Create a restriction rule")
    @ApiResponse(responseCode = "201", description = "Restriction rule created.")
    @ApiResponse(responseCode = "400", description = "Invalid request.")
    @ApiResponse(responseCode = "401", description = "Authentication required.")
    @ApiResponse(responseCode = "403", description = "Insufficient permissions.")
    @EmitEvent(id = "PRICE_RESTRICTION_RULE_CREATE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth", scopes = {
            "pricing:restriction:manage" })
    @PreAuthorize("hasAuthority('pricing:restriction:manage')")
    @PostMapping
    public ResponseEntity<@NonNull RestrictionRuleResponse> createRule(
            @Valid @RequestBody @NonNull CreateRestrictionRuleRequest request) {
        log.info("POST /v1/price/restrictions/rules productId={}", request.productId());
        RestrictionRuleResponse response = restrictionRuleService.createRule(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "Get a restriction rule by ID")
    @ApiResponse(responseCode = "200", description = "Restriction rule found.")
    @ApiResponse(responseCode = "401", description = "Authentication required.")
    @ApiResponse(responseCode = "404", description = "Restriction rule not found.")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/{ruleId}")
    public ResponseEntity<@NonNull RestrictionRuleResponse> getRuleById(@PathVariable @NonNull UUID ruleId) {
        log.info("GET /v1/price/restrictions/rules/{}", ruleId);
        RestrictionRuleResponse response = restrictionRuleService.getRuleById(ruleId);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "List all active restriction rules")
    @ApiResponse(responseCode = "200", description = "List of restriction rules.")
    @ApiResponse(responseCode = "401", description = "Authentication required.")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
    @PreAuthorize("isAuthenticated()")
    @GetMapping
    public ResponseEntity<@NonNull List<RestrictionRuleResponse>> listRules() {
        log.info("GET /v1/price/restrictions/rules");
        List<RestrictionRuleResponse> rules = restrictionRuleService.listRules();
        return ResponseEntity.ok(rules);
    }

    @Operation(summary = "Deactivate a restriction rule")
    @ApiResponse(responseCode = "200", description = "Restriction rule deactivated.")
    @ApiResponse(responseCode = "401", description = "Authentication required.")
    @ApiResponse(responseCode = "403", description = "Insufficient permissions.")
    @ApiResponse(responseCode = "404", description = "Restriction rule not found.")
    @EmitEvent(id = "PRICE_RESTRICTION_RULE_DEACTIVATE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth", scopes = {
            "pricing:restriction:manage" })
    @PreAuthorize("hasAuthority('pricing:restriction:manage')")
    @DeleteMapping("/{ruleId}")
    public ResponseEntity<@NonNull RestrictionRuleResponse> deactivateRule(@PathVariable @NonNull UUID ruleId) {
        log.info("DELETE /v1/price/restrictions/rules/{}", ruleId);
        RestrictionRuleResponse response = restrictionRuleService.deactivateRule(ruleId);
        return ResponseEntity.ok(response);
    }
}
