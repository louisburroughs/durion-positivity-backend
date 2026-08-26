package com.positivity.price.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.price.internal.dto.RestrictionEvaluationRequest;
import com.positivity.price.internal.dto.RestrictionEvaluationResponse;
import com.positivity.price.internal.dto.RestrictionEvaluationResult;
import com.positivity.price.internal.dto.RestrictionOverrideRequest;
import com.positivity.price.internal.dto.RestrictionOverrideResponse;
import com.positivity.price.internal.security.PricingPermissions;
import com.positivity.price.service.RestrictionEvaluationService;
import com.positivity.price.service.RestrictionOverrideService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
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

    @Operation(operationId = "evaluatePriceRestrictions", summary = "Evaluate Price Restrictions", description = """
                    Evaluates each submitted product and context entry against active sale-restriction rules and \
                    returns a per-item decision of ALLOW, BLOCK, ALLOW_WITH_OVERRIDE, or RESTRICTION_UNKNOWN.
                    Use this tool before quoting or selling a product to learn whether the sale is blocked or needs an \
                    override; do not use overridePriceRestriction, which records the override itself after a decision \
                    of ALLOW_WITH_OVERRIDE.
                    Preconditions: none beyond the pricing:restrictions:view authority; items with no matching \
                    active rule resolve to ALLOW.
                    Required inputs: items (at least one), each with productId (UUID), locationTag, serviceTag, and \
                    context (BROWSE, QUOTE, CHECKOUT, INVOICE_FINALIZE, or COMMIT_SALE); any matching rule with \
                    overrideable false forces BLOCK, otherwise matching rules yield ALLOW_WITH_OVERRIDE with the \
                    matched ruleIds.
                    Emits a PRICE_RESTRICTIONS_EVALUATE event; the evaluation itself is read-only and each item is \
                    bounded by an 800 ms timeout.
                    Returns 503 when evaluation fails or times out for an item in a commit-path context (CHECKOUT, \
                    INVOICE_FINALIZE, COMMIT_SALE), while the same failure on BROWSE or QUOTE degrades that item to a \
                    RESTRICTION_UNKNOWN decision instead of an error.
                    """)
    @ApiResponse(responseCode = "200", description = "Evaluation results per product.")
    @ApiResponse(responseCode = "400", description = "Invalid request body.")
    @ApiResponse(responseCode = "401", description = "Authentication required.")
    @ApiResponse(responseCode = "403", description = "Insufficient permissions.")
    @ApiResponse(responseCode = "503", description = "Restriction evaluation service unavailable (commit path only).")
    @EmitEvent(id = "PRICE_RESTRICTIONS_EVALUATE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"pricing:restrictions:view"})
    @PreAuthorize("hasAuthority('" + PricingPermissions.RESTRICTIONS_VIEW + "')")
    @PostMapping("/restrictions:evaluate")
    public ResponseEntity<RestrictionEvaluationResponse> evaluateRestrictions(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Batch of product/location/service/context entries to test against the active restriction rules.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Checkout evaluation", value = """
                                                                    {"items":[
                                                                      {"productId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4b01",
                                                                       "locationTag":"RETAIL_STORE",
                                                                       "serviceTag":"POS_SALE",
                                                                       "context":"CHECKOUT"}]}
                                                                    """)))
                    @Valid
                    @RequestBody
                    RestrictionEvaluationRequest request) {
        log.info(
                "POST /v1/price/restrictions:evaluate items={}", request.items().size());
        List<RestrictionEvaluationResult> results = evaluationService.evaluate(request);
        return ResponseEntity.ok(new RestrictionEvaluationResponse(results));
    }

    @Operation(operationId = "overridePriceRestriction", summary = "Override Price Restrictions", description = """
                    Issues an audited, time-boxed override of a sale-restriction rule for a specific transaction and \
                    product.
                    Use this tool after evaluatePriceRestrictions returns ALLOW_WITH_OVERRIDE; do not use \
                    deactivateRestrictionRule, which retires the rule for everyone instead of exempting one \
                    transaction.
                    Preconditions: the referenced restriction rule must exist; the rule's overrideable flag is not \
                    re-checked here, so callers must honour a BLOCK decision from evaluation rather than overriding it.
                    Required inputs: ruleId, transactionId, and productId (UUIDs), overrideContext (BROWSE, QUOTE, \
                    CHECKOUT, INVOICE_FINALIZE, or COMMIT_SALE), and reasonCode; notes and approvedBy are optional.
                    Emits a PRICE_RESTRICTIONS_OVERRIDE event and persists an ISSUED audit record capturing the actor \
                    and the rule's policyVersion; the returned overrideId expires 24 hours after issue.
                    Returns 404 when the referenced restriction rule does not exist.
                    """)
    @ApiResponse(responseCode = "200", description = "Override issued. Returns overrideId and expiresAt.")
    @ApiResponse(responseCode = "400", description = "Invalid request body.")
    @ApiResponse(responseCode = "401", description = "Authentication required.")
    @ApiResponse(responseCode = "403", description = "Insufficient permissions to override restrictions.")
    @ApiResponse(responseCode = "404", description = "Restriction rule not found.")
    @EmitEvent(id = "PRICE_RESTRICTIONS_OVERRIDE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"pricing:restriction:override"})
    @PreAuthorize("hasAuthority('" + PricingPermissions.RESTRICTION_OVERRIDE + "')")
    @PostMapping("/restrictions:override")
    public ResponseEntity<RestrictionOverrideResponse> overrideRestrictions(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Override justification tying a restriction rule to the transaction and product it is waived for.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Manager-approved override", value = """
                                                                    {"ruleId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4c01",
                                                                     "transactionId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4c02",
                                                                     "productId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4b01",
                                                                     "overrideContext":"CHECKOUT",
                                                                     "reasonCode":"MANAGER_APPROVAL",
                                                                     "notes":"Customer is a licensed reseller",
                                                                     "approvedBy":"user-001"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    RestrictionOverrideRequest request) {
        log.info("POST /v1/price/restrictions:override context={}", request.overrideContext());
        RestrictionOverrideResponse response = overrideService.createOverride(request);
        return ResponseEntity.ok(response);
    }
}
