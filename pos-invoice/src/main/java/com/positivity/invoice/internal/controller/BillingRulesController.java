package com.positivity.invoice.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.invoice.internal.dto.BillingRulesDTO;
import com.positivity.invoice.service.BillingRulesService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for billing rules management.
 * CAP:092 - Preferences & Billing Rules
 */
@RestController
@RequestMapping("/v1/billing/rules")
@Tag(name = "Billing Rules", description = "Manage billing rules for commercial accounts")
public class BillingRulesController {

    private static final Logger log = LoggerFactory.getLogger(BillingRulesController.class);
    private static final String DEFAULT_USER = "api-user";

    private final BillingRulesService billingRulesService;

    public BillingRulesController(@NonNull BillingRulesService billingRulesService) {
        this.billingRulesService = billingRulesService;
    }

    @GetMapping("/{partyId}")
    @EmitEvent(id = "BILLING_RULES_GET", apiVersion = "1")
    @Operation(summary = "Get billing rules for a party/customer",
               description = "Retrieve the current billing rules configuration for a commercial account")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Billing rules found"),
        @ApiResponse(responseCode = "404", description = "No billing rules configured for this party")
    })
    public ResponseEntity<BillingRulesDTO> getBillingRules(@PathVariable @NonNull String partyId) {
        log.debug("GET /v1/billing/rules/{}", partyId);

        return billingRulesService.getBillingRules(partyId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{partyId}")
    @EmitEvent(id = "BILLING_RULES_UPSERT", apiVersion = "1")
    @Operation(summary = "Create or update billing rules",
               description = "Idempotent upsert of billing rules for a commercial account")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Billing rules updated"),
        @ApiResponse(responseCode = "201", description = "Billing rules created"),
        @ApiResponse(responseCode = "400", description = "Invalid billing rules data")
    })
    public ResponseEntity<BillingRulesDTO> upsertBillingRules(
            @PathVariable @NonNull String partyId,
            @Valid @RequestBody @NonNull BillingRulesDTO billingRulesDTO,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = DEFAULT_USER) String userId) {

        log.debug("PUT /v1/billing/rules/{} by user={}", partyId, userId);

        // Override partyId from path to ensure consistency
        billingRulesDTO.setPartyId(partyId);

        boolean isNew = billingRulesService.getBillingRules(partyId).isEmpty();
        BillingRulesDTO saved = billingRulesService.saveBillingRules(billingRulesDTO, userId);

        HttpStatus status = isNew ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(saved);
    }
}
