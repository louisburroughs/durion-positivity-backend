package com.positivity.price.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.price.internal.dto.AddEligibilityRuleRequest;
import com.positivity.price.internal.dto.EligibilityContext;
import com.positivity.price.internal.dto.EligibilityDecisionResponse;
import com.positivity.price.internal.dto.EligibilityRuleResponse;
import com.positivity.price.internal.dto.PromotionEligibilityRuleMapper;
import com.positivity.price.service.EligibilityEvaluationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Controller for promotion eligibility rule operations. Issue: #96 */
@RestController
@RequestMapping("/v1/promotions/offers/{promotionId}/rules")
@Tag(
        name = "Promotion Eligibility Rules",
        description = "Promotion eligibility rule management and evaluation operations")
public class PromotionEligibilityRuleController {

    private final EligibilityEvaluationService eligibilityEvaluationService;

    public PromotionEligibilityRuleController(EligibilityEvaluationService eligibilityEvaluationService) {
        this.eligibilityEvaluationService = eligibilityEvaluationService;
    }

    @PostMapping
    @EmitEvent(id = "PROMOTION_RULE_CREATE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"pricing:promotion:manage"})
    @PreAuthorize("hasAuthority('pricing:promotion:manage')")
    @Operation(
            operationId = "createPromotionEligibilityRule",
            summary = "Add Eligibility Rule To Promotion Offer",
            description = """
                    Creates an eligibility rule and attaches it to the promotion offer identified by promotionId, \
                    constraining who may receive the promotion.
                    Use this tool when a promotion must be limited by account list, fleet size, vehicle tag, audience \
                    type, or campaign code; do not use evaluatePromotionEligibility, which tests a context against the \
                    existing rules without changing them.
                    Preconditions: the promotion offer must exist; rules may be added regardless of the offer's status.
                    Required inputs: conditionType (ACCOUNT_ID_LIST, VEHICLE_TAG, ACCOUNT_FLEET_SIZE, AUDIENCE_TYPE, or \
                    CAMPAIGN_CODE), an operator supported by that type (IN, NOT_IN, EQUALS, GREATER_THAN_OR_EQUAL_TO), \
                    and value (max 255 characters; comma-separated for list types); ruleCombination is optional and \
                    defaults to AND, and at evaluation time the first rule's combination governs all rules.
                    Emits a PROMOTION_RULE_CREATE event and the rule takes effect on the next eligibility evaluation.
                    Returns 404 when the promotion offer does not exist, and 400 when conditionType, operator, or value \
                    is missing or invalid.
                    """)
    @ApiResponse(responseCode = "201", description = "Eligibility rule created.")
    @ApiResponse(responseCode = "400", description = "Invalid eligibility rule request.")
    @ApiResponse(responseCode = "404", description = "Promotion offer not found.")
    @ApiResponse(responseCode = "403", description = "Forbidden.")
    public ResponseEntity<EligibilityRuleResponse> addRule(
            @PathVariable("promotionId") UUID promotionId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Eligibility rule definition: the condition, operator, and comparison value to attach.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Fleet size rule", value = """
                                                                    {"conditionType":"ACCOUNT_FLEET_SIZE",
                                                                     "operator":"GREATER_THAN_OR_EQUAL_TO",
                                                                     "value":"25",
                                                                     "ruleCombination":"AND"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    AddEligibilityRuleRequest request) {
        var rule = eligibilityEvaluationService.addRule(promotionId, request);
        var response = PromotionEligibilityRuleMapper.toResponse(rule);
        URI location = URI.create("/v1/promotions/offers/" + promotionId + "/rules/" + response.getRuleId());
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"pricing:promotion:view"})
    @PreAuthorize("hasAuthority('pricing:promotion:view')")
    @Operation(
            operationId = "listPromotionEligibilityRules",
            summary = "List Eligibility Rules For Promotion Offer",
            description = """
                    Lists every eligibility rule currently attached to the promotion offer identified by promotionId.
                    Use this tool to inspect a promotion's audience constraints before editing them; use \
                    evaluatePromotionEligibility instead to test whether a concrete account, vehicle, or campaign \
                    context passes those rules.
                    Preconditions: none beyond the pricing:promotion:view authority; an unknown promotionId is not \
                    rejected.
                    Required inputs: promotionId (UUID) as a path parameter; there is no request body, filtering, or \
                    paging.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with an empty array both when the promotion has no rules and when the promotionId \
                    matches no offer, so this operation cannot verify that a promotion exists.
                    """)
    @ApiResponse(responseCode = "200", description = "Eligibility rules returned.")
    @ApiResponse(responseCode = "403", description = "Forbidden.")
    public ResponseEntity<List<EligibilityRuleResponse>> getRules(@PathVariable("promotionId") UUID promotionId) {
        List<EligibilityRuleResponse> responses = eligibilityEvaluationService.getRules(promotionId).stream()
                .map(PromotionEligibilityRuleMapper::toResponse)
                .toList();
        return ResponseEntity.ok(responses);
    }

    @DeleteMapping("/{ruleId}")
    @EmitEvent(id = "PROMOTION_RULE_DELETE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"pricing:promotion:manage"})
    @PreAuthorize("hasAuthority('pricing:promotion:manage')")
    @Operation(
            operationId = "deletePromotionEligibilityRule",
            summary = "Delete Promotion Eligibility Rule",
            description = """
                    Deletes a single eligibility rule from a promotion offer, widening the promotion's audience from \
                    the next evaluation onward.
                    Use this tool to remove one constraint while keeping the offer and its other rules; do not use \
                    deactivatePromotionOffer, which withdraws the entire offer rather than one rule.
                    Preconditions: a rule with ruleId must exist and belong to the promotion identified by promotionId.
                    Required inputs: promotionId (UUID) and ruleId (UUID) as path parameters; there is no request body.
                    Emits a PROMOTION_RULE_DELETE event; the deletion is permanent and cannot be undone.
                    Returns 404 when no rule with ruleId exists under that promotion, including when the rule belongs \
                    to a different offer.
                    """)
    @ApiResponse(responseCode = "204", description = "Eligibility rule deleted.")
    @ApiResponse(responseCode = "404", description = "Promotion offer or rule not found.")
    @ApiResponse(responseCode = "403", description = "Forbidden.")
    public ResponseEntity<Void> deleteRule(
            @PathVariable("promotionId") UUID promotionId, @PathVariable("ruleId") UUID ruleId) {
        eligibilityEvaluationService.deleteRule(promotionId, ruleId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/evaluate")
    @EmitEvent(id = "PROMOTION_RULE_EVALUATE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"pricing:promotion:apply"})
    @PreAuthorize("hasAuthority('pricing:promotion:apply')")
    @Operation(
            operationId = "evaluatePromotionEligibility",
            summary = "Evaluate Promotion Eligibility",
            description = """
                    Evaluates whether the supplied account, vehicle, audience, and campaign context passes the \
                    eligibility rules attached to a promotion offer, returning an eligible flag and a reason code.
                    Use this tool to pre-check eligibility without consuming promotion usage; do not use \
                    applyPromotionOffer, which applies the discount and increments the offer's usage count.
                    Preconditions: the first rule's ruleCombination governs the evaluation (AND requires every rule to \
                    pass, OR requires any one); a promotion with no rules, or an unknown promotionId, evaluates to \
                    eligible because only the stored rules are consulted.
                    Required inputs: a context body whose fields are all optional; accountId and vehicleId (UUIDs), \
                    audienceType (compared case-insensitively), and campaignCode, where omitting a field that a rule \
                    needs fails that rule with a missing-context reason code such as MISSING_ACCOUNT_CONTEXT.
                    Emits a PROMOTION_RULE_EVALUATE event; no promotion state or usage count changes.
                    Returns 200 with eligible true or false and a reason code such as ELIGIBLE, ACCOUNT_NOT_IN_LIST, \
                    FLEET_SIZE_TOO_SMALL, or VEHICLE_TAG_NOT_PRESENT; 400 occurs only for an unparseable body.
                    """)
    @ApiResponse(responseCode = "200", description = "Eligibility evaluation result returned.")
    @ApiResponse(responseCode = "400", description = "Invalid evaluation request.")
    @ApiResponse(responseCode = "403", description = "Forbidden.")
    public ResponseEntity<EligibilityDecisionResponse> evaluateEligibility(
            @PathVariable("promotionId") UUID promotionId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Account, vehicle, audience, and campaign context the promotion's rules are evaluated against.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Fleet account context", value = """
                                                                    {"accountId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                     "vehicleId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c",
                                                                     "audienceType":"COMMERCIAL",
                                                                     "campaignCode":"SPRING-FLEET-2026"}
                                                                    """)))
                    @RequestBody
                    EligibilityContext context) {
        var decision = eligibilityEvaluationService.evaluateEligibility(
                promotionId,
                context == null ? null : context.getAccountId(),
                context == null ? null : context.getVehicleId(),
                context == null ? null : context.getAudienceType(),
                context == null ? null : context.getCampaignCode());
        return ResponseEntity.ok(PromotionEligibilityRuleMapper.toDecisionResponse(decision));
    }
}
