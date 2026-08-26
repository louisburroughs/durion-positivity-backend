package com.positivity.price.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.price.internal.dto.CreateRestrictionRuleRequest;
import com.positivity.price.internal.dto.RestrictionRuleResponse;
import com.positivity.price.internal.security.PricingPermissions;
import com.positivity.price.service.RestrictionRuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
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

    @Operation(operationId = "createRestrictionRule", summary = "Create A Restriction Rule", description = """
                    Creates an active sale-restriction rule that blocks or gates a product for a location tag and \
                    service tag combination.
                    Use this tool to configure a standing restriction policy; do not use overridePriceRestriction, \
                    which grants a one-time transaction exemption from an existing rule.
                    Preconditions: none are checked beyond authorization; duplicate rules for the same product and tag \
                    combination are not rejected, so each call appends a new rule.
                    Required inputs: productId (UUID), locationTag (ALL_LOCATIONS, RETAIL_STORE, WAREHOUSE, \
                    MOBILE_SERVICE, FRANCHISE, or TEST_LOCATION), serviceTag (POS_SALE, WORKORDER, ESTIMATE, INVOICE, \
                    or DELIVERY), effectiveFrom (date), and overrideable; effectiveTo is optional, and policyVersion \
                    defaults to 1 when omitted.
                    Emits a PRICE_RESTRICTION_RULE_CREATE event and the rule participates in evaluation immediately; \
                    a rule with overrideable false forces a BLOCK decision that cannot be overridden.
                    Returns 201 with the stored rule, and 400 when a required field is missing or a tag is not a valid \
                    enum value.
                    """)
    @ApiResponse(responseCode = "201", description = "Restriction rule created.")
    @ApiResponse(responseCode = "400", description = "Invalid request.")
    @ApiResponse(responseCode = "401", description = "Authentication required.")
    @ApiResponse(responseCode = "403", description = "Insufficient permissions.")
    @EmitEvent(id = "PRICE_RESTRICTION_RULE_CREATE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"pricing:restriction:manage"})
    @PreAuthorize("hasAuthority('" + PricingPermissions.RESTRICTION_MANAGE + "')")
    @PostMapping
    public ResponseEntity<@NonNull RestrictionRuleResponse> createRule(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Restriction rule definition scoping a product restriction to location and service tags.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Overrideable retail restriction",
                                                            value = """
                                                                    {"productId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4b01",
                                                                     "locationTag":"RETAIL_STORE",
                                                                     "serviceTag":"POS_SALE",
                                                                     "effectiveFrom":"2026-03-01",
                                                                     "effectiveTo":"2026-12-31",
                                                                     "policyVersion":3,
                                                                     "overrideable":true}
                                                                    """)))
                    @Valid
                    @RequestBody
                    @NonNull
                    CreateRestrictionRuleRequest request) {
        log.info("POST /v1/price/restrictions/rules productId={}", request.productId());
        RestrictionRuleResponse response = restrictionRuleService.createRule(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(operationId = "getRestrictionRuleById", summary = "Get A Restriction Rule By ID", description = """
                    Returns a single sale-restriction rule, active or inactive, identified by its rule id.
                    Use this tool when the rule id is already known, typically from an evaluation result's ruleIds; \
                    use listRestrictionRules instead to browse the active rule set.
                    Preconditions: none beyond the pricing:rule:view authority; the rule must also exist, and \
                    inactive rules remain readable through this operation.
                    Required inputs: ruleId (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when no restriction rule exists for the supplied id.
                    """)
    @ApiResponse(responseCode = "200", description = "Restriction rule found.")
    @ApiResponse(responseCode = "401", description = "Authentication required.")
    @ApiResponse(responseCode = "403", description = "Insufficient permissions.")
    @ApiResponse(responseCode = "404", description = "Restriction rule not found.")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"pricing:rule:view"})
    @PreAuthorize("hasAuthority('" + PricingPermissions.RULE_VIEW + "')")
    @GetMapping("/{ruleId}")
    public ResponseEntity<@NonNull RestrictionRuleResponse> getRuleById(@PathVariable @NonNull UUID ruleId) {
        log.info("GET /v1/price/restrictions/rules/{}", ruleId);
        RestrictionRuleResponse response = restrictionRuleService.getRuleById(ruleId);
        return ResponseEntity.ok(response);
    }

    @Operation(operationId = "listRestrictionRules", summary = "List All Active Restriction Rules", description = """
                    Lists every currently active sale-restriction rule that can affect pricing and sale decisions.
                    Use this tool to review the standing restriction policy; use getRestrictionRuleById instead to \
                    fetch one rule, including deactivated rules, which this listing omits.
                    Preconditions: none beyond the pricing:rule:view authority.
                    Required inputs: none; there is no request body, filtering, or paging.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with an empty array when no active restriction rules exist.
                    """)
    @ApiResponse(responseCode = "200", description = "List of restriction rules.")
    @ApiResponse(responseCode = "401", description = "Authentication required.")
    @ApiResponse(responseCode = "403", description = "Insufficient permissions.")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"pricing:rule:view"})
    @PreAuthorize("hasAuthority('" + PricingPermissions.RULE_VIEW + "')")
    @GetMapping
    public ResponseEntity<@NonNull List<RestrictionRuleResponse>> listRules() {
        log.info("GET /v1/price/restrictions/rules");
        List<RestrictionRuleResponse> rules = restrictionRuleService.listRules();
        return ResponseEntity.ok(rules);
    }

    @Operation(operationId = "deactivateRestrictionRule", summary = "Deactivate A Restriction Rule", description = """
                    Deactivates a sale-restriction rule so it no longer participates in restriction evaluation.
                    Use this tool to retire a standing restriction for everyone; do not use overridePriceRestriction, \
                    which leaves the rule active and exempts only a single transaction.
                    Preconditions: the rule must exist and still be active; deactivation is not idempotent, so \
                    repeating it fails.
                    Required inputs: ruleId (UUID) as a path parameter; there is no request body.
                    Emits a PRICE_RESTRICTION_RULE_DEACTIVATE event, sets active to false, and stamps effectiveTo with \
                    the current date.
                    Returns 404 when no restriction rule exists for the supplied id; calling it on an already inactive \
                    rule produces a server error rather than a no-op.
                    """)
    @ApiResponse(responseCode = "200", description = "Restriction rule deactivated.")
    @ApiResponse(responseCode = "401", description = "Authentication required.")
    @ApiResponse(responseCode = "403", description = "Insufficient permissions.")
    @ApiResponse(responseCode = "404", description = "Restriction rule not found.")
    @EmitEvent(id = "PRICE_RESTRICTION_RULE_DEACTIVATE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"pricing:restriction:manage"})
    @PreAuthorize("hasAuthority('" + PricingPermissions.RESTRICTION_MANAGE + "')")
    @DeleteMapping("/{ruleId}")
    public ResponseEntity<@NonNull RestrictionRuleResponse> deactivateRule(@PathVariable @NonNull UUID ruleId) {
        log.info("DELETE /v1/price/restrictions/rules/{}", ruleId);
        RestrictionRuleResponse response = restrictionRuleService.deactivateRule(ruleId);
        return ResponseEntity.ok(response);
    }
}
