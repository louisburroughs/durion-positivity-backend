package com.positivity.price.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.price.internal.dto.AddEligibilityRuleRequest;
import com.positivity.price.internal.dto.EligibilityContext;
import com.positivity.price.internal.dto.EligibilityDecisionResponse;
import com.positivity.price.internal.dto.EligibilityRuleResponse;
import com.positivity.price.internal.dto.PromotionEligibilityRuleMapper;
import com.positivity.price.service.EligibilityEvaluationService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
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
@Tag(name = "Promotion Eligibility Rules")
@SecurityRequirement(name = "BearerAuth")
public class PromotionEligibilityRuleController {

    private final EligibilityEvaluationService eligibilityEvaluationService;

    public PromotionEligibilityRuleController(EligibilityEvaluationService eligibilityEvaluationService) {
        this.eligibilityEvaluationService = eligibilityEvaluationService;
    }

    @PostMapping
    @EmitEvent(id = "PROMOTION_RULE_CREATE", apiVersion = "1")
    @PreAuthorize("hasAuthority('Promotion:Manage')")
    public ResponseEntity<EligibilityRuleResponse> addRule(
            @PathVariable("promotionId") UUID promotionId,
            @Valid @RequestBody AddEligibilityRuleRequest request) {
        var rule = eligibilityEvaluationService.addRule(promotionId, request);
        var response = PromotionEligibilityRuleMapper.toResponse(rule);
        URI location = URI.create("/v1/promotions/offers/" + promotionId + "/rules/" + response.getRuleId());
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('Promotion:View')")
    public ResponseEntity<List<EligibilityRuleResponse>> getRules(@PathVariable("promotionId") UUID promotionId) {
        List<EligibilityRuleResponse> responses = eligibilityEvaluationService.getRules(promotionId).stream()
                .map(PromotionEligibilityRuleMapper::toResponse)
                .toList();
        return ResponseEntity.ok(responses);
    }

    @DeleteMapping("/{ruleId}")
    @EmitEvent(id = "PROMOTION_RULE_DELETE", apiVersion = "1")
    @PreAuthorize("hasAuthority('Promotion:Manage')")
    public ResponseEntity<Void> deleteRule(
            @PathVariable("promotionId") UUID promotionId,
            @PathVariable("ruleId") UUID ruleId) {
        eligibilityEvaluationService.deleteRule(promotionId, ruleId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/evaluate")
    @EmitEvent(id = "PROMOTION_RULE_EVALUATE", apiVersion = "1")
    @PreAuthorize("hasAuthority('Promotion:Apply')")
    public ResponseEntity<EligibilityDecisionResponse> evaluateEligibility(
            @PathVariable("promotionId") UUID promotionId,
            @RequestBody EligibilityContext context) {
        var decision = eligibilityEvaluationService.evaluateEligibility(
                promotionId,
                context == null ? null : context.getAccountId(),
                context == null ? null : context.getVehicleId());
        return ResponseEntity.ok(PromotionEligibilityRuleMapper.toDecisionResponse(decision));
    }
}